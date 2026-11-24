package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.core.model.AuditEvent
import com.example.core.model.CommandStatus
import com.example.core.model.CommandType
import com.example.core.model.Incident
import com.example.core.model.IncidentSeverity
import com.example.core.model.IncidentStatus
import com.example.core.model.QueueItem
import com.example.core.model.QueuePriority
import com.example.core.model.SecurityCommand
import com.example.core.model.SecurityState
import com.example.core.model.SyncStatus
import com.example.core.model.TheftReport

@Entity(tableName = "incidents")
data class IncidentEntity(
    @PrimaryKey
    val incidentId: String,
    val deviceId: String,
    val ownerId: String,
    val title: String,
    val status: String,
    val severity: String,
    val riskScore: Int,
    val triggeringFactorsJson: String,
    val createdTimestamp: Long,
    val resolvedTimestamp: Long?,
    val theftReportId: String?
) {
    fun toDomain(): Incident = Incident(
        incidentId = incidentId,
        deviceId = deviceId,
        ownerId = ownerId,
        title = title,
        status = try { IncidentStatus.valueOf(status) } catch (_: Exception) { IncidentStatus.ACTIVE },
        severity = try { IncidentSeverity.valueOf(severity) } catch (_: Exception) { IncidentSeverity.MEDIUM },
        riskScore = riskScore,
        triggeringFactors = triggeringFactorsJson.split(";;;").filter { it.isNotBlank() },
        createdTimestamp = createdTimestamp,
        resolvedTimestamp = resolvedTimestamp,
        theftReportId = theftReportId
    )

    companion object {
        fun fromDomain(incident: Incident): IncidentEntity = IncidentEntity(
            incidentId = incident.incidentId,
            deviceId = incident.deviceId,
            ownerId = incident.ownerId,
            title = incident.title,
            status = incident.status.name,
            severity = incident.severity.name,
            riskScore = incident.riskScore,
            triggeringFactorsJson = incident.triggeringFactors.joinToString(";;;"),
            createdTimestamp = incident.createdTimestamp,
            resolvedTimestamp = incident.resolvedTimestamp,
            theftReportId = incident.theftReportId
        )
    }
}

@Entity(tableName = "theft_reports")
data class TheftReportEntity(
    @PrimaryKey
    val reportId: String,
    val deviceId: String,
    val ownerId: String,
    val incidentId: String,
    val timestamp: Long,
    val lastKnownLatitude: Double,
    val lastKnownLongitude: Double,
    val carrierName: String,
    val simState: String,
    val batteryPercentage: Int,
    val cryptographicSignature: String
) {
    fun toDomain(): TheftReport = TheftReport(
        reportId = reportId,
        deviceId = deviceId,
        ownerId = ownerId,
        incidentId = incidentId,
        timestamp = timestamp,
        lastKnownLatitude = lastKnownLatitude,
        lastKnownLongitude = lastKnownLongitude,
        carrierName = carrierName,
        simState = simState,
        batteryPercentage = batteryPercentage,
        cryptographicSignature = cryptographicSignature
    )

    companion object {
        fun fromDomain(report: TheftReport): TheftReportEntity = TheftReportEntity(
            reportId = report.reportId,
            deviceId = report.deviceId,
            ownerId = report.ownerId,
            incidentId = report.incidentId,
            timestamp = report.timestamp,
            lastKnownLatitude = report.lastKnownLatitude,
            lastKnownLongitude = report.lastKnownLongitude,
            carrierName = report.carrierName,
            simState = report.simState,
            batteryPercentage = report.batteryPercentage,
            cryptographicSignature = report.cryptographicSignature
        )
    }
}

@Entity(tableName = "security_commands")
data class SecurityCommandEntity(
    @PrimaryKey
    val commandId: String,
    val deviceId: String,
    val ownerId: String,
    val commandType: String,
    val createdTimestamp: Long,
    val expirationTimestamp: Long,
    val status: String,
    val authorizationResult: String
) {
    fun toDomain(): SecurityCommand = SecurityCommand(
        commandId = commandId,
        deviceId = deviceId,
        ownerId = ownerId,
        commandType = try { CommandType.valueOf(commandType) } catch (_: Exception) { CommandType.REQUEST_LOCATION },
        createdTimestamp = createdTimestamp,
        expirationTimestamp = expirationTimestamp,
        status = try { CommandStatus.valueOf(status) } catch (_: Exception) { CommandStatus.PENDING },
        authorizationResult = authorizationResult
    )

    companion object {
        fun fromDomain(command: SecurityCommand): SecurityCommandEntity = SecurityCommandEntity(
            commandId = command.commandId,
            deviceId = command.deviceId,
            ownerId = command.ownerId,
            commandType = command.commandType.name,
            createdTimestamp = command.createdTimestamp,
            expirationTimestamp = command.expirationTimestamp,
            status = command.status.name,
            authorizationResult = command.authorizationResult
        )
    }
}

@Entity(tableName = "audit_events")
data class AuditEventEntity(
    @PrimaryKey
    val auditId: String,
    val incidentId: String?,
    val deviceId: String,
    val stateBefore: String,
    val stateAfter: String,
    val reason: String,
    val riskScore: Int,
    val actor: String,
    val timestamp: Long,
    val authorizationResult: String
) {
    fun toDomain(): AuditEvent = AuditEvent(
        auditId = auditId,
        incidentId = incidentId,
        deviceId = deviceId,
        stateBefore = try { SecurityState.valueOf(stateBefore) } catch (_: Exception) { SecurityState.NORMAL },
        stateAfter = try { SecurityState.valueOf(stateAfter) } catch (_: Exception) { SecurityState.NORMAL },
        reason = reason,
        riskScore = riskScore,
        actor = actor,
        timestamp = timestamp,
        authorizationResult = authorizationResult
    )

    companion object {
        fun fromDomain(audit: AuditEvent): AuditEventEntity = AuditEventEntity(
            auditId = audit.auditId,
            incidentId = audit.incidentId,
            deviceId = audit.deviceId,
            stateBefore = audit.stateBefore.name,
            stateAfter = audit.stateAfter.name,
            reason = audit.reason,
            riskScore = audit.riskScore,
            actor = audit.actor,
            timestamp = audit.timestamp,
            authorizationResult = audit.authorizationResult
        )
    }
}

@Entity(tableName = "security_queue")
data class QueueItemEntity(
    @PrimaryKey
    val id: String,
    val eventType: String,
    val timestamp: Long,
    val priority: String,
    val payloadJson: String,
    val retryCount: Int,
    val syncStatus: String
) {
    fun toDomain(): QueueItem = QueueItem(
        id = id,
        eventType = eventType,
        timestamp = timestamp,
        priority = try { QueuePriority.valueOf(priority) } catch (_: Exception) { QueuePriority.NORMAL },
        payloadJson = payloadJson,
        retryCount = retryCount,
        syncStatus = try { SyncStatus.valueOf(syncStatus) } catch (_: Exception) { SyncStatus.PENDING }
    )

    companion object {
        fun fromDomain(item: QueueItem): QueueItemEntity = QueueItemEntity(
            id = item.id,
            eventType = item.eventType,
            timestamp = item.timestamp,
            priority = item.priority.name,
            payloadJson = item.payloadJson,
            retryCount = item.retryCount,
            syncStatus = item.syncStatus.name
        )
    }
}
