package com.example.domain.command

import com.example.core.brain.model.PolicyDecision
import com.example.core.brain.orchestration.SecurityBrain
import com.example.core.model.CommandStatus
import com.example.core.model.CommandType
import com.example.core.model.SecurityCommand
import com.example.core.model.SecurityState
import com.example.core.security.SecurityStateManager
import com.example.data.local.dao.SecurityCommandDao
import com.example.data.local.entity.SecurityCommandEntity
import com.example.domain.alerts.LostPhoneAlarmManager
import com.example.domain.management.DeviceLockManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Validates and executes authorized owner commands.
 * Fails closed for unauthorized, expired, replayed, or non-matching commands,
 * and routes theft/recovery state transitions through the authoritative SecurityBrain
 * and persisted RecoveryMonitoringService lifecycle.
 */
class SecurityCommandProcessor(
    private val expectedDeviceId: String,
    private val lockManager: DeviceLockManager,
    private val stateManager: SecurityStateManager,
    private val commandDao: SecurityCommandDao,
    private val alarmManager: LostPhoneAlarmManager? = null,
    private val securityBrain: SecurityBrain? = null,
    private val expectedDeviceIdProvider: () -> String = { expectedDeviceId },
    private val expectedOwnerProvider: (() -> String?)? = null,
    private val persistTrackingState: (active: Boolean, stateName: String?) -> Boolean = { _, _ -> true },
    private val startRecoveryService: (intervalMillis: Long) -> Boolean = { true },
    private val stopRecoveryService: () -> Boolean = { true },
    private val monitoringIntervalProvider: (SecurityState) -> Long = { state ->
        when (state) {
            SecurityState.OWNER_CONFIRMED_STOLEN,
            SecurityState.STOLEN_MODE,
            SecurityState.RECOVERY_MODE -> 10_000L
            SecurityState.DANGER,
            SecurityState.SUSPECTED_LOST -> 20_000L
            SecurityState.WARNING -> 60_000L
            SecurityState.NORMAL,
            SecurityState.RECOVERED -> 300_000L
        }
    },
    private val onConfigureMonitoring: (intervalMillis: Long, requestFreshFix: Boolean, enqueueOfflineSync: Boolean) -> Unit = { _, _, _ -> },
    private val onServiceActivationDeferred: suspend (SecurityState, String) -> Unit = { _, _ -> },
    private val clockProvider: () -> Long = { System.currentTimeMillis() }
) {

    companion object {
        private const val MAX_COMMAND_ID_LENGTH = 128
        private const val MAX_PAYLOAD_LENGTH = 512
        private const val MAX_FUTURE_CLOCK_SKEW_MS = 300_000L
        private val VALID_COMMAND_ID_REGEX = Regex("^[A-Za-z0-9_.:-]+$")
        private val UNTRUSTED_ACTORS = setOf("GUEST", "UNAUTHORIZED", "UNKNOWN", "ANONYMOUS")
    }

    sealed class CommandExecutionResult {
        data class Success(val message: String) : CommandExecutionResult()
        data class PendingServiceActivation(val message: String) : CommandExecutionResult()
        data class Failed(val reason: String) : CommandExecutionResult()
        data class Rejected(val reason: String) : CommandExecutionResult()
        data class Unsupported(val reason: String) : CommandExecutionResult()
        data class AlreadyProcessed(val status: CommandStatus, val message: String) : CommandExecutionResult()
    }

    private data class RecordedCommandFingerprint(
        val deviceId: String,
        val ownerId: String,
        val commandType: CommandType,
        val payload: String,
        val status: CommandStatus
    )

    private val executionMutex = Mutex()
    private val processedFingerprints = ConcurrentHashMap<String, RecordedCommandFingerprint>()

    private val _recoveryServiceActive = MutableStateFlow<Boolean?>(null)
    val recoveryServiceActive: StateFlow<Boolean?> = _recoveryServiceActive.asStateFlow()

    private val _isRemoteTransitionInProgress = MutableStateFlow(false)
    val isRemoteTransitionInProgress: StateFlow<Boolean> = _isRemoteTransitionInProgress.asStateFlow()

    @Volatile
    private var lastRemoteHandledServiceOutcome: Pair<SecurityState, Boolean>? = null

    fun consumeRemoteHandledServiceState(state: SecurityState): Boolean? {
        val current = lastRemoteHandledServiceOutcome ?: return null
        if (current.first == state) {
            lastRemoteHandledServiceOutcome = null
            return current.second
        }
        return null
    }

    suspend fun executeCommand(command: SecurityCommand, authorizedBy: String): CommandExecutionResult =
        executionMutex.withLock {
            val cleanCommandId = command.commandId.trim()
            val cleanAuthorizedBy = authorizedBy.trim()
            val cleanOwnerId = command.ownerId.trim()
            val resolvedDeviceId = expectedDeviceIdProvider().trim()

            // 1. Validate Command ID format
            if (cleanCommandId.isBlank() ||
                cleanCommandId.length > MAX_COMMAND_ID_LENGTH ||
                !VALID_COMMAND_ID_REGEX.matches(cleanCommandId)
            ) {
                return@withLock CommandExecutionResult.Rejected("Command rejected: invalid commandId.")
            }

            // 2. Idempotency & Anti-Replay check (do not re-execute or overwrite terminal states)
            val existingEntity = findExistingCommand(cleanCommandId)
            val existingFingerprint = processedFingerprints[cleanCommandId]
            val existingStatus = existingFingerprint?.status
                ?: existingEntity?.toDomain()?.status?.takeIf { it != CommandStatus.PENDING }

            if (existingStatus != null) {
                val existingDomain = existingEntity?.toDomain()
                val immutableMismatch = if (existingFingerprint != null) {
                    existingFingerprint.deviceId != command.deviceId.trim() ||
                        existingFingerprint.ownerId != cleanOwnerId ||
                        existingFingerprint.commandType != command.commandType ||
                        existingFingerprint.payload != command.payload
                } else if (existingDomain != null) {
                    existingDomain.deviceId != command.deviceId.trim() ||
                        existingDomain.ownerId != cleanOwnerId ||
                        existingDomain.commandType != command.commandType
                } else {
                    false
                }

                if (immutableMismatch) {
                    return@withLock CommandExecutionResult.Rejected(
                        "Command rejected: replay attempt with altered parameters for commandId '$cleanCommandId'."
                    )
                }

                return@withLock CommandExecutionResult.AlreadyProcessed(
                    status = existingStatus,
                    message = "Command '$cleanCommandId' already processed with status ${existingStatus.name}."
                )
            }

            // 3. Validate Payload safety
            if (command.payload.length > MAX_PAYLOAD_LENGTH ||
                command.payload.any { ch -> ch.code < 32 && ch != '\n' && ch != '\r' && ch != '\t' }
            ) {
                persistFinalOutcome(
                    command = command,
                    status = CommandStatus.REJECTED,
                    authResult = "INVALID_PAYLOAD",
                    isNew = (existingEntity == null)
                )
                return@withLock CommandExecutionResult.Rejected("Command rejected: invalid payload.")
            }

            // 4. Validate Target Device ID
            if (command.deviceId.isBlank() || resolvedDeviceId.isBlank() || command.deviceId.trim() != resolvedDeviceId) {
                persistFinalOutcome(
                    command = command,
                    status = CommandStatus.REJECTED,
                    authResult = "DEVICE_MISMATCH",
                    isNew = (existingEntity == null)
                )
                return@withLock CommandExecutionResult.Rejected("Command rejected: target device mismatch.")
            }

            // 5. Validate Owner Authorization
            val expectedUid = try {
                expectedOwnerProvider?.invoke()?.trim()?.takeIf { it.isNotEmpty() }
            } catch (_: Exception) {
                null
            }
            val actorUntrusted = cleanAuthorizedBy.isBlank() ||
                cleanOwnerId.isBlank() ||
                cleanAuthorizedBy.uppercase() in UNTRUSTED_ACTORS ||
                cleanOwnerId.uppercase() in UNTRUSTED_ACTORS ||
                cleanOwnerId != cleanAuthorizedBy ||
                (expectedUid != null && (cleanAuthorizedBy != expectedUid || cleanOwnerId != expectedUid))

            if (actorUntrusted) {
                persistFinalOutcome(
                    command = command,
                    status = CommandStatus.REJECTED,
                    authResult = "OWNER_AUTH_FAILED",
                    isNew = (existingEntity == null)
                )
                return@withLock CommandExecutionResult.Rejected("Command rejected: owner authorization failed.")
            }

            // 6. Validate Timestamps & Expiration
            val now = clockProvider()
            if (command.createdTimestamp <= 0L || command.expirationTimestamp <= command.createdTimestamp) {
                persistFinalOutcome(
                    command = command,
                    status = CommandStatus.REJECTED,
                    authResult = "INVALID_TIMESTAMPS",
                    isNew = (existingEntity == null)
                )
                return@withLock CommandExecutionResult.Rejected("Command rejected: invalid timestamps.")
            }
            if (command.createdTimestamp - now > MAX_FUTURE_CLOCK_SKEW_MS) {
                persistFinalOutcome(
                    command = command,
                    status = CommandStatus.REJECTED,
                    authResult = "FUTURE_TIMESTAMP_INVALID",
                    isNew = (existingEntity == null)
                )
                return@withLock CommandExecutionResult.Rejected("Command rejected: timestamp exceeds allowed clock skew.")
            }
            if (now > command.expirationTimestamp) {
                persistFinalOutcome(
                    command = command,
                    status = CommandStatus.EXPIRED,
                    authResult = "COMMAND_EXPIRED",
                    isNew = (existingEntity == null)
                )
                return@withLock CommandExecutionResult.Rejected("Command rejected: authorization window expired.")
            }

            // Insert PENDING anchor before executing side effects if brand new
            if (existingEntity == null) {
                try {
                    commandDao.insertCommand(
                        SecurityCommandEntity.fromDomain(command.copy(status = CommandStatus.PENDING))
                    )
                } catch (e: Exception) {
                    return@withLock CommandExecutionResult.Failed(
                        "Failed to record pending command before execution: ${e.message}"
                    )
                }
            }

            // 7. Dispatch by Command Type
            val result = when (command.commandType) {
                CommandType.LOCK_DEVICE -> {
                    when (val lockResult = lockManager.lockDeviceNow()) {
                        is DeviceLockManager.LockExecutionResult.Success -> {
                            CommandExecutionResult.Success("Device screen locked via DevicePolicyManager.")
                        }
                        is DeviceLockManager.LockExecutionResult.Unavailable -> {
                            CommandExecutionResult.Unsupported(lockResult.reason)
                        }
                        is DeviceLockManager.LockExecutionResult.SecurityError -> {
                            CommandExecutionResult.Rejected(lockResult.message)
                        }
                    }
                }

                CommandType.ACTIVATE_STOLEN_MODE -> {
                    executeStateTransitionCommand(
                        targetState = SecurityState.STOLEN_MODE,
                        commandId = cleanCommandId,
                        authorizedBy = cleanAuthorizedBy
                    )
                }

                CommandType.ACTIVATE_RECOVERY_MODE -> {
                    executeStateTransitionCommand(
                        targetState = SecurityState.RECOVERY_MODE,
                        commandId = cleanCommandId,
                        authorizedBy = cleanAuthorizedBy
                    )
                }

                CommandType.MARK_RECOVERED -> {
                    executeMarkRecoveredCommand(
                        commandId = cleanCommandId,
                        authorizedBy = cleanAuthorizedBy
                    )
                }

                CommandType.REQUEST_LOCATION -> {
                    val currentState = securityBrain?.currentState?.value ?: stateManager.currentState.value
                    val interval = monitoringIntervalProvider(currentState)
                    onConfigureMonitoring(interval, true, true)
                    CommandExecutionResult.Success("High-priority location beacon dispatched.")
                }

                CommandType.TRIGGER_ALARM -> {
                    if (alarmManager != null) {
                        alarmManager.startAlarm()
                        CommandExecutionResult.Success("Audible location alarm activated.")
                    } else {
                        CommandExecutionResult.Unsupported("Alarm subsystem not attached.")
                    }
                }

                CommandType.STOP_ALARM -> {
                    if (alarmManager != null) {
                        alarmManager.stopAlarm()
                        CommandExecutionResult.Success("Audible location alarm stopped.")
                    } else {
                        CommandExecutionResult.Unsupported("Alarm subsystem not attached.")
                    }
                }
            }

            val updatedStatus = when (result) {
                is CommandExecutionResult.Success -> CommandStatus.EXECUTED
                is CommandExecutionResult.PendingServiceActivation -> CommandStatus.PENDING_SERVICE_ACTIVATION
                is CommandExecutionResult.Failed -> CommandStatus.FAILED
                is CommandExecutionResult.Rejected -> CommandStatus.REJECTED
                is CommandExecutionResult.Unsupported -> CommandStatus.UNSUPPORTED_ON_DEVICE
                is CommandExecutionResult.AlreadyProcessed -> result.status
            }

            val persistedOk = persistFinalOutcome(
                command = command,
                status = updatedStatus,
                authResult = "PROCESSED_BY_${cleanAuthorizedBy}_${updatedStatus.name}",
                isNew = false
            )
            if (!persistedOk && result is CommandExecutionResult.Success) {
                return@withLock CommandExecutionResult.Failed("Failed to persist final command execution state.")
            }

            result
        }

    private suspend fun executeStateTransitionCommand(
        targetState: SecurityState,
        commandId: String,
        authorizedBy: String
    ): CommandExecutionResult {
        val opaqueToken = "REMOTE_${targetState.name}_${commandId}_$authorizedBy"
        _isRemoteTransitionInProgress.value = true
        return try {
            val policyApproved = if (securityBrain != null) {
                val decision = when (targetState) {
                    SecurityState.STOLEN_MODE -> securityBrain.requestDeclareStolen(
                        isOwnerConfirmed = true,
                        authorizationToken = opaqueToken,
                        persistTransition = { persistTrackingState(true, SecurityState.STOLEN_MODE.name) }
                    )
                    SecurityState.RECOVERY_MODE -> securityBrain.requestActivateRecoveryMode(
                        authorizationToken = opaqueToken,
                        persistTransition = { persistTrackingState(true, SecurityState.RECOVERY_MODE.name) }
                    )
                    else -> return CommandExecutionResult.Rejected("Unsupported remote target state ${targetState.name}.")
                }
                if (decision.decision != PolicyDecision.APPROVED) {
                    return CommandExecutionResult.Failed(decision.explanation)
                }
                true
            } else {
                val persisted = try {
                    persistTrackingState(true, targetState.name)
                } catch (_: Exception) {
                    false
                }
                if (!persisted) {
                    return CommandExecutionResult.Failed(
                        "${targetState.name} aborted: tracking persistence failed."
                    )
                }
                true
            }

            if (!policyApproved) {
                return CommandExecutionResult.Failed("Policy denied ${targetState.name}.")
            }

            when (targetState) {
                SecurityState.STOLEN_MODE -> stateManager.confirmStolenByOwner(authorizedBy)
                SecurityState.RECOVERY_MODE -> stateManager.activateRecoveryMode(authorizedBy)
                else -> Unit
            }

            val interval = monitoringIntervalProvider(targetState)
            onConfigureMonitoring(interval, true, true)
            val serviceStarted = try {
                startRecoveryService(interval)
            } catch (_: Exception) {
                false
            }
            _recoveryServiceActive.value = serviceStarted
            lastRemoteHandledServiceOutcome = targetState to serviceStarted

            if (serviceStarted) {
                CommandExecutionResult.Success("${targetState.name} engaged with adaptive recovery monitoring.")
            } else {
                val reason = "${targetState.name} state persisted, but RecoveryMonitoringService could not start immediately (missing location permission or OS foreground restriction)."
                onServiceActivationDeferred(targetState, reason)
                CommandExecutionResult.PendingServiceActivation(reason)
            }
        } finally {
            _isRemoteTransitionInProgress.value = false
        }
    }

    private suspend fun executeMarkRecoveredCommand(
        commandId: String,
        authorizedBy: String
    ): CommandExecutionResult {
        val opaqueToken = "REMOTE_RECOVERED_${commandId}_$authorizedBy"
        _isRemoteTransitionInProgress.value = true
        return try {
            if (securityBrain != null) {
                val decision = securityBrain.requestMarkRecovered(
                    authorizationToken = opaqueToken,
                    persistTransition = { persistTrackingState(false, SecurityState.RECOVERED.name) }
                )
                if (decision.decision != PolicyDecision.APPROVED) {
                    return CommandExecutionResult.Failed(decision.explanation)
                }
            } else {
                val persisted = try {
                    persistTrackingState(false, SecurityState.RECOVERED.name)
                } catch (_: Exception) {
                    false
                }
                if (!persisted) {
                    return CommandExecutionResult.Failed("MARK_RECOVERED aborted: recovered state persistence failed.")
                }
            }

            stateManager.markRecovered(authorizedBy)
            val stopped = try {
                stopRecoveryService()
            } catch (_: Exception) {
                false
            }
            if (stopped) {
                _recoveryServiceActive.value = false
            }
            lastRemoteHandledServiceOutcome = SecurityState.RECOVERED to false

            val nominalInterval = monitoringIntervalProvider(SecurityState.RECOVERED)
            onConfigureMonitoring(nominalInterval, false, true)

            if (stopped) {
                CommandExecutionResult.Success("Device marked recovered and active theft tracking stopped.")
            } else {
                CommandExecutionResult.Failed("Device marked recovered in persistence, but foreground service stop failed.")
            }
        } finally {
            _isRemoteTransitionInProgress.value = false
        }
    }

    private suspend fun findExistingCommand(commandId: String): SecurityCommandEntity? {
        return try {
            commandDao.getAllCommands().firstOrNull()?.firstOrNull { it.commandId == commandId }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun persistFinalOutcome(
        command: SecurityCommand,
        status: CommandStatus,
        authResult: String,
        isNew: Boolean
    ): Boolean {
        val finalCommand = command.copy(
            status = status,
            authorizationResult = authResult
        )
        processedFingerprints[command.commandId.trim()] = RecordedCommandFingerprint(
            deviceId = command.deviceId.trim(),
            ownerId = command.ownerId.trim(),
            commandType = command.commandType,
            payload = command.payload,
            status = status
        )
        return try {
            val entity = SecurityCommandEntity.fromDomain(finalCommand)
            if (isNew) {
                commandDao.insertCommand(entity)
            }
            commandDao.updateCommand(entity)
            true
        } catch (_: Exception) {
            false
        }
    }
}

