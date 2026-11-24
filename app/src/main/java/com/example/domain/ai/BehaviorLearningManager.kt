package com.example.domain.ai

import android.util.Log
import com.example.core.model.UserRole
import com.example.data.local.dao.BehaviorEventDao
import com.example.data.local.entity.BehaviorEventEntity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.pow

data class BaselineProfile(
    val frequentHoursSummary: String = "Données insuffisantes",
    val frequentZonesSummary: String = "Données insuffisantes",
    val isBaselineEstablished: Boolean = false,
    val learningDays: Int = 0,
    val totalObservations: Int = 0,
    val syncStatus: String = "LOCAL", // "LOCAL", "SYNCHRONIZED", "FAILED"
    val confidence: Float = 0.0f,
    val learnedFrequentHours: Set<Int> = emptySet(),
    val learnedFrequentZones: Set<String> = emptySet(),
    val isPaused: Boolean = false,
    val windowDays: Int = 30
)

/**
 * Behavior Learning Engine for InkingiGuard:
 * - Records genuine, timestamped location observations and device-use events with owner's informed permission.
 * - Stores learning events locally in Room database table `events` scoped by deviceId.
 * - Uses a 30-day rolling window with explainable exponential age decay (14-day half-life) so old patterns never permanently dominate.
 * - Prevents rapid baseline poisoning via per-day weight caps and multi-day support thresholds.
 * - Compares current hour and location zone against actual learned frequentHours and frequentZones.
 * - Never treats missing or insufficient baseline data as suspicious, and never invents patterns.
 * - Does not declare theft based on GPS deviation or unusual event alone.
 */
class BehaviorLearningManager(
    private val behaviorEventDao: BehaviorEventDao,
    private val deviceId: String
) {
    private val _baselineFlow = MutableStateFlow(BaselineProfile())
    val baselineFlow = _baselineFlow.asStateFlow()

    @Volatile
    private var isLearningPaused: Boolean = false

    fun setLearningPaused(paused: Boolean) {
        isLearningPaused = paused
        _baselineFlow.value = _baselineFlow.value.copy(isPaused = paused)
    }

    fun isLearningPaused(): Boolean = isLearningPaused

    val learningDays: Flow<Int> = behaviorEventDao.getUniqueLearningDays()

    suspend fun loadInitialBaseline() = withContext(Dispatchers.IO) {
        computeAndSyncBaseline()
    }

    companion object {
        const val ROLLING_WINDOW_DAYS = 30
        const val DAY_MILLIS = 86_400_000L
        const val ROLLING_WINDOW_MILLIS = ROLLING_WINDOW_DAYS * DAY_MILLIS
        const val DECAY_HALF_LIFE_DAYS = 14.0
        const val MAX_DAILY_WEIGHT_PER_BUCKET = 3.0
        const val MIN_TRUSTED_DAYS = 3
        const val MIN_TRUSTED_OBSERVATIONS = 10
        const val ESTABLISHED_DAYS = 7
        const val ESTABLISHED_OBSERVATIONS = 14
        const val MIN_CONFIDENCE_FOR_EVALUATION = 0.4f
        const val MIN_PATTERN_WEIGHT = 1.5

        val AUTOMATIC_TELEMETRY_ACTIONS = setOf(
            "TELEMETRY_EVAL",
            "TELEMETRY_CHECK",
            "TELEMETRY_HEARTBEAT",
            "HEARTBEAT",
            "PERIODIC_CHECK"
        )

        private fun isUnknownZoneLabel(zone: String?): Boolean {
            val trimmed = zone?.trim().orEmpty()
            if (trimmed.isEmpty()) return true
            return trimmed.contains("inconnue", ignoreCase = true) ||
                trimmed.contains("inconnu", ignoreCase = true) ||
                trimmed.contains("unknown", ignoreCase = true) ||
                trimmed.contains("hors", ignoreCase = true) ||
                trimmed.contains("outside", ignoreCase = true) ||
                trimmed.contains("déterminer", ignoreCase = true)
        }
    }

    suspend fun recordObservation(
        zoneName: String? = null,
        role: UserRole = UserRole.OWNER,
        action: String = "APP_OPEN",
        isAutomaticTelemetry: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        if (isLearningPaused) {
            return@withContext false
        }
        // Guard: Routine automatic telemetry evaluation loops and heartbeats must not falsely inflate behavioral observations
        if (isAutomaticTelemetry || action in AUTOMATIC_TELEMETRY_ACTIONS) {
            return@withContext false
        }

        val cal = Calendar.getInstance()
        val currentHour = cal.get(Calendar.HOUR_OF_DAY)
        val resolvedZone = if (!zoneName.isNullOrBlank()) zoneName.trim() else "Zone inconnue"

        val actionTag = when (role) {
            UserRole.GUEST -> "GUEST_SESSION"
            UserRole.TRUSTED_PERSON -> "TRUSTED_PERSON_SESSION"
            UserRole.OWNER -> action
        }

        val event = BehaviorEventEntity(
            heure = currentHour,
            zone_nom = resolvedZone,
            action = actionTag,
            device_id = deviceId
        )
        behaviorEventDao.insertEvent(event)

        if (role == UserRole.OWNER) {
            computeAndSyncBaseline()
        }
        true
    }

    suspend fun recordAppOpenEvent(
        zoneName: String? = null,
        role: UserRole = UserRole.OWNER
    ): Boolean = recordObservation(zoneName, role, "APP_OPEN")

    suspend fun resetBaseline(): Boolean = withContext(Dispatchers.IO) {
        try {
            behaviorEventDao.clearForDevice(deviceId)
            _baselineFlow.value = BaselineProfile(
                frequentHoursSummary = "Données insuffisantes",
                frequentZonesSummary = "Données insuffisantes",
                isBaselineEstablished = false,
                learningDays = 0,
                totalObservations = 0,
                syncStatus = "LOCAL",
                confidence = 0.0f,
                learnedFrequentHours = emptySet(),
                learnedFrequentZones = emptySet(),
                isPaused = isLearningPaused
            )
            val currentUserId = try {
                FirebaseAuth.getInstance().currentUser?.uid
            } catch (_: Exception) {
                null
            }
            if (currentUserId != null) {
                FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(currentUserId)
                    .collection("patterns")
                    .document(deviceId)
                    .delete()
                    .await()
            }
            true
        } catch (e: Exception) {
            Log.w("BehaviorLearningManager", "Error resetting baseline: ${e.message}")
            false
        }
    }

    suspend fun computeAndSyncBaseline(nowMs: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        try {
            val cutoffTimestamp = nowMs - ROLLING_WINDOW_MILLIS
            val windowEvents = behaviorEventDao.getOwnerEventsSince(deviceId, cutoffTimestamp)
                .filter { it.device_id.isEmpty() || it.device_id == deviceId }

            val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)

            val actualUniqueDays: Int
            val totalEvents: Int
            val learnedHours: Set<Int>
            val learnedZones: Set<String>
            val sortedHoursWithScores: List<Pair<Int, Double>>
            val sortedZonesWithCounts: List<Pair<String, Int>>

            if (windowEvents.isNotEmpty()) {
                val distinctDays = mutableSetOf<String>()
                // Track per-day capped decay weight so single-day spam cannot poison the model
                val dayHourWeights = mutableMapOf<Pair<String, Int>, Double>()
                val dayZoneWeights = mutableMapOf<Pair<String, String>, Double>()
                val hourDistinctDays = mutableMapOf<Int, MutableSet<String>>()
                val zoneDistinctDays = mutableMapOf<String, MutableSet<String>>()
                val zoneRawCounts = mutableMapOf<String, Int>()

                for (evt in windowEvents) {
                    val dayKey = dateFormatter.format(Date(evt.timestamp))
                    distinctDays.add(dayKey)

                    val ageDays = ((nowMs - evt.timestamp).coerceAtLeast(0L)).toDouble() / DAY_MILLIS.toDouble()
                    val decayWeight = 0.5.pow(ageDays / DECAY_HALF_LIFE_DAYS).coerceIn(0.05, 1.0)

                    val h = evt.heure.coerceIn(0, 23)
                    val hBucket = dayKey to h
                    val prevH = dayHourWeights[hBucket] ?: 0.0
                    dayHourWeights[hBucket] = (prevH + decayWeight).coerceAtMost(MAX_DAILY_WEIGHT_PER_BUCKET)
                    hourDistinctDays.getOrPut(h) { mutableSetOf() }.add(dayKey)

                    val cleanZone = evt.zone_nom.trim()
                    if (!isUnknownZoneLabel(cleanZone)) {
                        val zKey = cleanZone.lowercase(Locale.ROOT)
                        val zBucket = dayKey to cleanZone
                        val prevZ = dayZoneWeights[zBucket] ?: 0.0
                        dayZoneWeights[zBucket] = (prevZ + decayWeight).coerceAtMost(MAX_DAILY_WEIGHT_PER_BUCKET)
                        zoneDistinctDays.getOrPut(zKey) { mutableSetOf() }.add(dayKey)
                        zoneRawCounts[cleanZone] = (zoneRawCounts[cleanZone] ?: 0) + 1
                    }
                }

                actualUniqueDays = distinctDays.size
                totalEvents = windowEvents.size

                val aggregatedHours = mutableMapOf<Int, Double>()
                for ((bucket, weight) in dayHourWeights) {
                    val h = bucket.second
                    aggregatedHours[h] = (aggregatedHours[h] ?: 0.0) + weight
                }

                val aggregatedZones = mutableMapOf<String, Double>()
                for ((bucket, weight) in dayZoneWeights) {
                    val z = bucket.second
                    aggregatedZones[z] = (aggregatedZones[z] ?: 0.0) + weight
                }

                sortedHoursWithScores = aggregatedHours.entries
                    .sortedByDescending { it.value }
                    .map { it.key to it.value }

                sortedZonesWithCounts = aggregatedZones.entries
                    .sortedByDescending { it.value }
                    .map { it.key to (zoneRawCounts[it.key] ?: 1) }

                // Require multi-day support once multiple days are present to stop rapid poisoning
                val minDaysRequiredPerFeature = if (actualUniqueDays >= 2) 2 else 1
                learnedHours = if (actualUniqueDays >= MIN_TRUSTED_DAYS && totalEvents >= MIN_TRUSTED_OBSERVATIONS) {
                    aggregatedHours.entries
                        .filter { (hour, weight) ->
                            val days = hourDistinctDays[hour]?.size ?: 0
                            days >= minDaysRequiredPerFeature && weight >= MIN_PATTERN_WEIGHT
                        }
                        .map { it.key }
                        .toSet()
                } else {
                    emptySet()
                }

                learnedZones = if (actualUniqueDays >= MIN_TRUSTED_DAYS && totalEvents >= MIN_TRUSTED_OBSERVATIONS) {
                    aggregatedZones.entries
                        .filter { (zone, weight) ->
                            val days = zoneDistinctDays[zone.lowercase(Locale.ROOT)]?.size ?: 0
                            days >= minDaysRequiredPerFeature && weight >= MIN_PATTERN_WEIGHT
                        }
                        .map { it.key }
                        .toSet()
                } else {
                    emptySet()
                }
            } else {
                // Compatibility fallback for stub/legacy DAOs that expose aggregated counts directly
                val fallbackTotal = behaviorEventDao.getTotalEventCount()
                val fallbackDays = behaviorEventDao.getUniqueLearningDaysCount()
                actualUniqueDays = fallbackDays
                totalEvents = fallbackTotal

                if (fallbackTotal > 0) {
                    val freqHours = behaviorEventDao.getFrequentHours()
                    val freqZones = behaviorEventDao.getFrequentZones()
                    sortedHoursWithScores = freqHours.map { it.heure to it.cnt.toDouble() }
                    sortedZonesWithCounts = freqZones
                        .filter { !isUnknownZoneLabel(it.zone_nom) }
                        .map { it.zone_nom to it.cnt }

                    val hasTrustedSupport = fallbackDays >= MIN_TRUSTED_DAYS && fallbackTotal >= MIN_TRUSTED_OBSERVATIONS
                    learnedHours = if (hasTrustedSupport) {
                        freqHours.filter { it.cnt >= 2 || freqHours.size == 1 }.map { it.heure }.toSet()
                    } else {
                        emptySet()
                    }
                    learnedZones = if (hasTrustedSupport) {
                        freqZones
                            .filter { !isUnknownZoneLabel(it.zone_nom) && (it.cnt >= 2 || freqZones.size == 1) }
                            .map { it.zone_nom }
                            .toSet()
                    } else {
                        emptySet()
                    }
                } else {
                    sortedHoursWithScores = emptyList()
                    sortedZonesWithCounts = emptyList()
                    learnedHours = emptySet()
                    learnedZones = emptySet()
                }
            }

            val dayProgress = (actualUniqueDays.toFloat() / ESTABLISHED_DAYS.toFloat()).coerceIn(0f, 1f)
            val obsProgress = (totalEvents.toFloat() / ESTABLISHED_OBSERVATIONS.toFloat()).coerceIn(0f, 1f)
            val confidence = if (totalEvents == 0) 0.0f else (dayProgress * 0.6f + obsProgress * 0.4f).coerceIn(0f, 1f)

            val hoursText = if (sortedHoursWithScores.isNotEmpty() && totalEvents >= 5) {
                val displayHours = if (learnedHours.isNotEmpty()) {
                    sortedHoursWithScores.filter { it.first in learnedHours }.take(3).map { "${it.first}h" }
                } else {
                    sortedHoursWithScores.take(3).map { "${it.first}h" }
                }
                if (displayHours.isNotEmpty()) {
                    "Heures fréquentes observées: ${displayHours.joinToString(", ")}"
                } else {
                    "En cours de collecte ($totalEvents observations)"
                }
            } else if (totalEvents > 0) {
                "En cours de collecte ($totalEvents observation${if (totalEvents > 1) "s" else ""})"
            } else {
                "Données insuffisantes"
            }

            val zonesText = if (sortedZonesWithCounts.isNotEmpty() && totalEvents >= 5) {
                val displayZones = if (learnedZones.isNotEmpty()) {
                    sortedZonesWithCounts.filter { it.first in learnedZones }.take(2)
                } else {
                    sortedZonesWithCounts.take(2)
                }
                if (displayZones.isNotEmpty()) {
                    displayZones.joinToString(", ") { "${it.first} (${it.second}x)" }
                } else {
                    "Périmètre à déterminer"
                }
            } else if (sortedZonesWithCounts.isNotEmpty()) {
                sortedZonesWithCounts.first().first
            } else if (totalEvents > 0) {
                "Périmètre à déterminer"
            } else {
                "Données insuffisantes"
            }

            // A baseline is established only with at least 7 distinct observation days and 14+ events
            val isEstablished = actualUniqueDays >= ESTABLISHED_DAYS &&
                totalEvents >= ESTABLISHED_OBSERVATIONS &&
                learnedHours.isNotEmpty()

            var currentSyncStatus = "LOCAL"
            try {
                val currentUserId = FirebaseAuth.getInstance().currentUser?.uid
                if (currentUserId != null) {
                    val firestoreData = mapOf(
                        "deviceId" to deviceId,
                        "ownerUid" to currentUserId,
                        "heures" to hoursText,
                        "zones" to zonesText,
                        "jours" to actualUniqueDays,
                        "totalObservations" to totalEvents,
                        "confidence" to confidence,
                        "isEstablished" to isEstablished,
                        "lastUpdate" to nowMs
                    )

                    FirebaseFirestore.getInstance()
                        .collection("users")
                        .document(currentUserId)
                        .collection("patterns")
                        .document(deviceId)
                        .set(firestoreData)
                        .await()
                    currentSyncStatus = "SYNCHRONIZED"
                }
            } catch (e: Exception) {
                currentSyncStatus = "FAILED"
                Log.w("BehaviorLearningManager", "Firestore patterns sync unavailable/buffered: ${e.message}")
            }

            val profile = BaselineProfile(
                frequentHoursSummary = hoursText,
                frequentZonesSummary = zonesText,
                isBaselineEstablished = isEstablished,
                learningDays = actualUniqueDays,
                totalObservations = totalEvents,
                syncStatus = currentSyncStatus,
                confidence = confidence,
                learnedFrequentHours = learnedHours,
                learnedFrequentZones = learnedZones,
                isPaused = isLearningPaused,
                windowDays = ROLLING_WINDOW_DAYS
            )
            _baselineFlow.value = profile
        } catch (e: Exception) {
            Log.e("BehaviorLearningManager", "Error calculating baseline: ${e.message}")
        }
    }

    /**
     * Explainable risk evaluation comparing observation with actual learned hours and zones.
     * - Never treats missing, insufficient, or paused baseline data as suspicious.
     * - Deviation alone increases risk by +20% but NEVER declares theft by itself.
     * - When a recognized GUEST or TRUSTED_PERSON is using the device, this is explicitly explained
     *   and NEVER triggers theft classification.
     */
    fun isWithinBaseline(
        currentHour: Int,
        currentZoneName: String?,
        role: UserRole = UserRole.OWNER,
        lang: String = "fr"
    ): Pair<Boolean, String> {
        val isEn = lang.equals("en", ignoreCase = true)
        if (role == UserRole.GUEST) {
            return Pair(
                true,
                if (isEn) "Guest mode active — Temporary access authorized by owner."
                else "Mode Invité actif — Utilisation temporaire autorisée par le propriétaire."
            )
        }
        if (role == UserRole.TRUSTED_PERSON) {
            return Pair(
                true,
                if (isEn) "Trusted person active — Session recognized and authorized."
                else "Personne de confiance active — Session reconnue et autorisée."
            )
        }
        if (isLearningPaused) {
            return Pair(
                true,
                if (isEn) "Learning paused — Baseline frozen (protection & GPS remain active)"
                else "Apprentissage en pause — Profil figé (protection et GPS actifs)"
            )
        }

        val profile = _baselineFlow.value
        val actualDays = profile.learningDays

        val hasReliableBaseline = actualDays >= MIN_TRUSTED_DAYS &&
            profile.totalObservations >= MIN_TRUSTED_OBSERVATIONS &&
            profile.confidence >= MIN_CONFIDENCE_FOR_EVALUATION &&
            profile.learnedFrequentHours.isNotEmpty()

        if (!hasReliableBaseline) {
            return Pair(
                true,
                if (isEn) "In learning phase ($actualDays/7 distinct days, ${profile.totalObservations} obs)"
                else "En phase d'apprentissage ($actualDays/7 jours réels, ${profile.totalObservations} obs)"
            )
        }

        val resolvedZone = currentZoneName?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (isEn) "Unknown zone" else "Zone inconnue"

        // Check against actual learned frequent hours (no hardcoded 06:00-22:00 window)
        val isNormalHours = currentHour in profile.learnedFrequentHours

        // Check against actual learned frequent zones (when reliable named zones were observed)
        val hasLearnedZones = profile.learnedFrequentZones.isNotEmpty()
        val isKnownZone = if (!hasLearnedZones) {
            // Do not flag zone deviation when no habitual named zones have been learned yet
            true
        } else {
            !isUnknownZoneLabel(resolvedZone) &&
                profile.learnedFrequentZones.any { it.equals(resolvedZone, ignoreCase = true) }
        }

        return if (isNormalHours && isKnownZone) {
            Pair(
                true,
                if (isEn) "Within baseline — $resolvedZone (${currentHour}h)"
                else "Dans baseline — $resolvedZone ($currentHour h)"
            )
        } else {
            val deviationReason = if (!isNormalHours && !isKnownZone) {
                if (isEn) "Unusual hour (${currentHour}h) and unlearned zone ($resolvedZone)"
                else "Heure inhabituelle ($currentHour h) et zone non répertoriée"
            } else if (!isNormalHours) {
                if (isEn) "Unusual hour (${currentHour}h vs learned ${profile.learnedFrequentHours.sorted().joinToString("/") { "${it}h" }})"
                else "Heure inhabituelle ($currentHour h)"
            } else {
                if (isEn) "Outside habitual zone ($resolvedZone)"
                else "Hors zone habituelle"
            }
            Pair(
                false,
                if (isEn) "⚠️ Outside baseline (+20% risk) — $deviationReason"
                else "⚠️ Hors baseline (+20% risque) — $deviationReason"
            )
        }
    }
}
