package com.example.core.brain.correlation

import com.example.core.brain.model.EventCategory
import com.example.core.brain.model.NormalizedSecurityEvent
import com.example.core.brain.model.OntologyIncident
import com.example.core.model.EventSeverity
import com.example.core.model.IncidentSeverity
import com.example.core.model.IncidentStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Event Correlation Engine.
 * Detects related suspicious telemetry signals within a temporal correlation window.
 * Strictly prevents duplicate event ingestion and suppresses duplicate incident creation.
 * Uses a non-blocking coroutine Mutex so concurrent ingestion stays ordered and deadlock-free.
 */
class EventCorrelationEngine(
    private val correlationWindowMillis: Long = 15 * 60 * 1000L, // 15-minute sliding window
    private val fingerprintDao: com.example.data.local.dao.EventFingerprintDao? = null
) {

    private val mutex = Mutex()
    private val recentEventFingerprints = ConcurrentHashMap<String, Long>()
    private val activeIncidentsByCluster = ConcurrentHashMap<String, OntologyIncident>()

    data class CorrelationResult(
        val isDuplicateEvent: Boolean,
        val correlatedIncident: OntologyIncident?,
        val isNewIncidentCreated: Boolean,
        val correlationMessage: String
    )

    suspend fun processEvent(
        event: NormalizedSecurityEvent,
        currentRiskScore: Int
    ): CorrelationResult = mutex.withLock {
        cleanExpiredFingerprints(event.timestamp)

        // 1. Deduplication check: Suppress duplicate event bursts (in-memory + durable DAO check)
        val isInMemoryDuplicate = recentEventFingerprints.containsKey(event.deduplicationFingerprint)
        val isDurableDuplicate = if (!isInMemoryDuplicate && fingerprintDao != null) {
            try {
                fingerprintDao.hasFingerprint(event.deduplicationFingerprint)
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }

        if (isInMemoryDuplicate || isDurableDuplicate) {
            recentEventFingerprints[event.deduplicationFingerprint] = event.timestamp
            return@withLock CorrelationResult(
                isDuplicateEvent = true,
                correlatedIncident = null,
                isNewIncidentCreated = false,
                correlationMessage = "Duplicate event suppressed via fingerprint: ${event.deduplicationFingerprint}"
            )
        }

        recentEventFingerprints[event.deduplicationFingerprint] = event.timestamp
        if (fingerprintDao != null) {
            try {
                fingerprintDao.insertFingerprint(
                    com.example.data.local.entity.EventFingerprintEntity(
                        fingerprint = event.deduplicationFingerprint,
                        timestamp = event.timestamp
                    )
                )
                fingerprintDao.deleteExpiredFingerprints(event.timestamp - correlationWindowMillis)
            } catch (_: Exception) {
                // Fail safe: in-memory cache continues operating if database is busy
            }
        }

        // 2. Correlation Cluster Key (Groups related device anomalies)
        val clusterKey = "${event.deviceId}:CLUSTER"

        // 3. Check for existing active incident in cluster
        val existingIncident = activeIncidentsByCluster[clusterKey]?.let { inc ->
            val timeDelta = event.timestamp - inc.updatedTimestamp
            if (timeDelta <= correlationWindowMillis &&
                inc.status != IncidentStatus.RESOLVED &&
                inc.status != IncidentStatus.CLOSED
            ) {
                inc
            } else {
                null
            }
        }

        if (existingIncident != null) {
            // Merge into existing incident - DO NOT create duplicate incident!
            val updatedEvents = existingIncident.associatedEvents + event
            val updatedFactors = (existingIncident.primaryFactors + event.title).distinct()
            val maxScore = maxOf(existingIncident.riskScore, currentRiskScore)

            val updatedSeverity = when {
                maxScore >= 80 -> IncidentSeverity.CRITICAL
                maxScore >= 60 -> IncidentSeverity.HIGH
                maxScore >= 30 -> IncidentSeverity.MEDIUM
                else -> IncidentSeverity.LOW
            }

            val updatedIncident = existingIncident.copy(
                riskScore = maxScore,
                severity = updatedSeverity,
                associatedEvents = updatedEvents,
                primaryFactors = updatedFactors,
                evidenceSummary = "${updatedEvents.size} correlated telemetry signals clustered within window.",
                updatedTimestamp = maxOf(existingIncident.updatedTimestamp, event.timestamp)
            )

            activeIncidentsByCluster[clusterKey] = updatedIncident

            CorrelationResult(
                isDuplicateEvent = false,
                correlatedIncident = updatedIncident,
                isNewIncidentCreated = false,
                correlationMessage = "Appended event '${event.title}' to active incident ${updatedIncident.incidentId}."
            )
        } else {
            // If risk is notable (score >= 30 or severity >= WARNING), initiate a tracked incident
            if (currentRiskScore >= 30 || event.severity != EventSeverity.INFO) {
                val newIncidentId = "INC-" + UUID.randomUUID().toString().take(8).uppercase()
                val severity = when {
                    currentRiskScore >= 80 -> IncidentSeverity.CRITICAL
                    currentRiskScore >= 60 -> IncidentSeverity.HIGH
                    else -> IncidentSeverity.MEDIUM
                }

                val newIncident = OntologyIncident(
                    incidentId = newIncidentId,
                    deviceId = event.deviceId,
                    ownerId = event.ownerId,
                    correlationClusterKey = clusterKey,
                    status = IncidentStatus.INVESTIGATING,
                    severity = severity,
                    title = "Correlated Threat Cluster: ${event.title}",
                    riskScore = currentRiskScore,
                    associatedEvents = listOf(event),
                    primaryFactors = listOf(event.title),
                    evidenceSummary = "Initial anomalous telemetry signal registered: ${event.title}",
                    createdTimestamp = event.timestamp,
                    updatedTimestamp = event.timestamp
                )

                activeIncidentsByCluster[clusterKey] = newIncident

                CorrelationResult(
                    isDuplicateEvent = false,
                    correlatedIncident = newIncident,
                    isNewIncidentCreated = true,
                    correlationMessage = "Spawned new incident cluster $newIncidentId for ${event.title}."
                )
            } else {
                CorrelationResult(
                    isDuplicateEvent = false,
                    correlatedIncident = null,
                    isNewIncidentCreated = false,
                    correlationMessage = "Nominal event recorded without incident threshold escalation."
                )
            }
        }
    }

    fun getActiveIncidentForDevice(deviceId: String): OntologyIncident? {
        val clusterKey = "$deviceId:CLUSTER"
        return activeIncidentsByCluster[clusterKey]?.takeIf { it.status != IncidentStatus.RESOLVED }
    }

    suspend fun resolveIncident(
        incidentId: String,
        timestamp: Long = System.currentTimeMillis()
    ): OntologyIncident? = mutex.withLock {
        val entry = activeIncidentsByCluster.entries.find { it.value.incidentId == incidentId }
        entry?.let {
            val resolved = it.value.copy(
                status = IncidentStatus.RESOLVED,
                updatedTimestamp = timestamp,
                resolvedTimestamp = timestamp
            )
            activeIncidentsByCluster[it.key] = resolved
            resolved
        }
    }

    suspend fun clear() = mutex.withLock {
        recentEventFingerprints.clear()
        activeIncidentsByCluster.clear()
    }

    private fun cleanExpiredFingerprints(now: Long) {
        val iterator = recentEventFingerprints.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value > correlationWindowMillis) {
                iterator.remove()
            }
        }
    }
}
