package com.example.core.brain.baseline

import com.example.core.brain.model.EventCategory
import com.example.core.brain.model.NormalizedSecurityEvent
import com.example.core.model.EventSeverity
import com.example.domain.security.OfflineSecurityVault
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Privacy-Preserving Behavioral Learning Engine.
 *
 * Principles:
 * - Learns device security telemetry baseline patterns strictly locally over time.
 * - Tracks observation sample count, confidence, freshness, and whether sufficient data exists.
 * - Detects explainable deviations across:
 *   1. Permitted SIM operator / state changes.
 *   2. Permitted Network connectivity patterns (SSID/types frequency, sudden disconnections).
 *   3. Unexpected system reboots and anomalous power cycles.
 *   4. Master authentication failure frequencies.
 *   5. Permitted motion metrics (acceleration magnitude running mean & standard deviation).
 *   6. Permitted location coordinate bounding centroid & radius.
 * - Safe by design:
 *   - NEVER collects private message content, keystrokes, passwords, private app data, camera or microphone data.
 *   - Anomalies are treated as evidence items, NOT proof of theft.
 *   - Never automatically confirms theft or bypasses owner verification.
 *   - Supports pause, resume, and full baseline reset.
 *   - Safely handles missing permissions and insufficient data without throwing exceptions.
 */

data class BehavioralObservation(
    val simCarrier: String? = null,
    val networkType: String? = null,
    val isNetworkConnected: Boolean = true,
    val isRebootAnomaly: Boolean = false,
    val failedAuthAttempts: Int = 0,
    val accelerationMagnitude: Float? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val isInsideTrustedZone: Boolean? = null,
    val isCharging: Boolean? = null,
    val zoneName: String? = null,
    val role: com.example.core.model.UserRole = com.example.core.model.UserRole.OWNER,
    val timestamp: Long = System.currentTimeMillis()
)

data class BaselineProfile(
    val sampleCount: Int = 0,
    val minRequiredSamples: Int = 10,
    val isBaselineEstablished: Boolean = false,
    val confidence: Float = 0.0f,
    val firstSampleTimestamp: Long = 0L,
    val lastSampleTimestamp: Long = 0L,
    val isStale: Boolean = false,
    val isPaused: Boolean = false,

    // Learned telemetry representations (aggregated statistical metrics only, zero private contents)
    val knownCarriers: Set<String> = emptySet(),
    val knownNetworkTypes: Set<String> = emptySet(),
    val totalRebootAnomaliesRecorded: Int = 0,
    val meanFailedAuthPerSession: Float = 0.0f,

    // Motion distribution statistics (Welford's algorithm parameters)
    val motionMeanMagnitude: Float = 9.8f,
    val motionVarianceMagnitude: Float = 1.0f,
    val motionSampleCount: Int = 0,

    // Geolocation centroid and dispersion (rough geographic bounding)
    val centroidLatitude: Double? = null,
    val centroidLongitude: Double? = null,
    val maxObservedRadiusMeters: Double = 0.0,
    val locationSampleCount: Int = 0
) {
    fun isFresh(now: Long = System.currentTimeMillis(), thresholdMillis: Long = 7 * 24 * 60 * 60 * 1000L): Boolean {
        if (sampleCount == 0) return false
        return (now - lastSampleTimestamp) <= thresholdMillis
    }

    fun toJson(): String {
        val obj = JSONObject()
        obj.put("sampleCount", sampleCount)
        obj.put("minRequiredSamples", minRequiredSamples)
        obj.put("isBaselineEstablished", isBaselineEstablished)
        obj.put("confidence", confidence.toDouble())
        obj.put("firstSampleTimestamp", firstSampleTimestamp)
        obj.put("lastSampleTimestamp", lastSampleTimestamp)
        obj.put("isStale", isStale)
        obj.put("isPaused", isPaused)

        val carriers = JSONArray()
        knownCarriers.forEach { carriers.put(it) }
        obj.put("knownCarriers", carriers)

        val networks = JSONArray()
        knownNetworkTypes.forEach { networks.put(it) }
        obj.put("knownNetworkTypes", networks)

        obj.put("totalRebootAnomaliesRecorded", totalRebootAnomaliesRecorded)
        obj.put("meanFailedAuthPerSession", meanFailedAuthPerSession.toDouble())
        obj.put("motionMeanMagnitude", motionMeanMagnitude.toDouble())
        obj.put("motionVarianceMagnitude", motionVarianceMagnitude.toDouble())
        obj.put("motionSampleCount", motionSampleCount)

        if (centroidLatitude != null) obj.put("centroidLatitude", centroidLatitude)
        if (centroidLongitude != null) obj.put("centroidLongitude", centroidLongitude)
        obj.put("maxObservedRadiusMeters", maxObservedRadiusMeters)
        obj.put("locationSampleCount", locationSampleCount)
        return obj.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): BaselineProfile {
            val obj = JSONObject(jsonStr)
            val carriers = mutableSetOf<String>()
            val carriersArr = obj.optJSONArray("knownCarriers")
            if (carriersArr != null) {
                for (i in 0 until carriersArr.length()) {
                    carriers.add(carriersArr.getString(i))
                }
            }

            val networks = mutableSetOf<String>()
            val networksArr = obj.optJSONArray("knownNetworkTypes")
            if (networksArr != null) {
                for (i in 0 until networksArr.length()) {
                    networks.add(networksArr.getString(i))
                }
            }

            return BaselineProfile(
                sampleCount = obj.optInt("sampleCount", 0),
                minRequiredSamples = obj.optInt("minRequiredSamples", 10),
                isBaselineEstablished = obj.optBoolean("isBaselineEstablished", false),
                confidence = obj.optDouble("confidence", 0.0).toFloat(),
                firstSampleTimestamp = obj.optLong("firstSampleTimestamp", 0L),
                lastSampleTimestamp = obj.optLong("lastSampleTimestamp", 0L),
                isStale = obj.optBoolean("isStale", false),
                isPaused = obj.optBoolean("isPaused", false),
                knownCarriers = carriers,
                knownNetworkTypes = networks,
                totalRebootAnomaliesRecorded = obj.optInt("totalRebootAnomaliesRecorded", 0),
                meanFailedAuthPerSession = obj.optDouble("meanFailedAuthPerSession", 0.0).toFloat(),
                motionMeanMagnitude = obj.optDouble("motionMeanMagnitude", 9.8).toFloat(),
                motionVarianceMagnitude = obj.optDouble("motionVarianceMagnitude", 1.0).toFloat(),
                motionSampleCount = obj.optInt("motionSampleCount", 0),
                centroidLatitude = if (obj.has("centroidLatitude") && !obj.isNull("centroidLatitude")) obj.optDouble("centroidLatitude") else null,
                centroidLongitude = if (obj.has("centroidLongitude") && !obj.isNull("centroidLongitude")) obj.optDouble("centroidLongitude") else null,
                maxObservedRadiusMeters = obj.optDouble("maxObservedRadiusMeters", 0.0),
                locationSampleCount = obj.optInt("locationSampleCount", 0)
            )
        }
    }
}

data class BehavioralAnomaly(
    val factorKey: String,
    val category: EventCategory,
    val title: String,
    val explanation: String,
    val severity: EventSeverity,
    val deviationScore: Int,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toNormalizedEvent(deviceId: String, ownerId: String): NormalizedSecurityEvent {
        return NormalizedSecurityEvent(
            eventId = "ANOM-" + UUID.randomUUID().toString().take(8).uppercase(),
            deviceId = deviceId,
            ownerId = ownerId,
            category = category,
            source = "BehavioralLearningEngine",
            timestamp = timestamp,
            severity = severity,
            baseRiskImpact = deviationScore,
            title = title,
            details = explanation,
            attributes = mapOf(
                "factorKey" to factorKey,
                "deviationScore" to deviationScore.toString()
            )
        )
    }
}

class BehavioralLearningEngine(
    private val minRequiredSamples: Int = 10,
    private val staleThresholdMillis: Long = 7 * 24 * 60 * 60 * 1000L, // 7 days without observation = stale
    private val persistenceVault: OfflineSecurityVault? = null
) {

    private var profile: BaselineProfile = loadPersistedProfileOrNew()

    companion object {
        const val VAULT_KEY_BASELINE = "BEHAVIORAL_BASELINE_PROFILE_SUMMARY"

        // Simple equirectangular distance calculation in meters (privacy-preserving local calculation)
        fun approximateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val meanLat = Math.toRadians((lat1 + lat2) / 2.0)
            val x = dLon * Math.cos(meanLat)
            val y = dLat
            return sqrt(x * x + y * y) * 6371000.0
        }
    }

    private fun loadPersistedProfileOrNew(): BaselineProfile {
        val saved = persistenceVault?.retrieveAndDecrypt(VAULT_KEY_BASELINE)
        if (!saved.isNullOrBlank()) {
            return try {
                BaselineProfile.fromJson(saved)
            } catch (_: Exception) {
                BaselineProfile(minRequiredSamples = minRequiredSamples)
            }
        }
        return BaselineProfile(minRequiredSamples = minRequiredSamples)
    }

    private fun persistProfile() {
        persistenceVault?.let { vault ->
            try {
                vault.encryptAndStore(VAULT_KEY_BASELINE, profile.toJson())
            } catch (_: Exception) {}
        }
    }

    @Synchronized
    fun getProfile(): BaselineProfile = profile

    @Synchronized
    fun isPaused(): Boolean = profile.isPaused

    @Synchronized
    fun pause() {
        profile = profile.copy(isPaused = true)
        persistProfile()
    }

    @Synchronized
    fun resume() {
        profile = profile.copy(isPaused = false)
        persistProfile()
    }

    @Synchronized
    fun reset() {
        profile = BaselineProfile(minRequiredSamples = minRequiredSamples)
        persistProfile()
    }

    @Synchronized
    fun isFresh(now: Long = System.currentTimeMillis()): Boolean {
        return profile.isFresh(now, staleThresholdMillis)
    }

    @Synchronized
    fun checkFreshness(now: Long = System.currentTimeMillis()): Boolean {
        val stale = (now - profile.lastSampleTimestamp) > staleThresholdMillis && profile.sampleCount > 0
        if (stale && !profile.isStale) {
            profile = profile.copy(isStale = true)
        }
        return !profile.isStale
    }

    /**
     * Ingests an observation into the learning model if not paused.
     * Computes running statistics using privacy-preserving aggregation.
     */
    @Synchronized
    fun recordObservation(observation: BehavioralObservation) {
        if (profile.isPaused) return

        // PRIVACY & SECURITY GUARD: Do not contaminate OWNER baseline with GUEST or TRUSTED_PERSON activity
        if (observation.role != com.example.core.model.UserRole.OWNER) {
            return
        }

        // Anti-poisoning guard: even before full establishment, do not absorb overt attack signals into normal baseline
        val hasPreEstablishmentWarning = profile.sampleCount >= 3 && (
            observation.failedAuthAttempts >= 3 ||
                observation.isInsideTrustedZone == false ||
                observation.isRebootAnomaly ||
                (observation.accelerationMagnitude != null && observation.accelerationMagnitude > 20.0f)
            )
        val allowIdentityUpdate = !profile.isBaselineEstablished && !hasPreEstablishmentWarning

        val count = profile.sampleCount + 1
        val firstTs = if (profile.sampleCount == 0) observation.timestamp else profile.firstSampleTimestamp
        val lastTs = observation.timestamp

        // 1. SIM Carriers: Do not contaminate baseline with foreign or anomalous SIM
        val updatedCarriers = if (!observation.simCarrier.isNullOrBlank()) {
            if (!allowIdentityUpdate && profile.knownCarriers.isNotEmpty() && !profile.knownCarriers.contains(observation.simCarrier)) {
                profile.knownCarriers
            } else {
                profile.knownCarriers + observation.simCarrier
            }
        } else {
            profile.knownCarriers
        }

        // 2. Network types: Do not contaminate baseline with foreign network type
        val updatedNetworks = if (!observation.networkType.isNullOrBlank()) {
            if (!allowIdentityUpdate && profile.knownNetworkTypes.isNotEmpty() && !profile.knownNetworkTypes.contains(observation.networkType)) {
                profile.knownNetworkTypes
            } else {
                profile.knownNetworkTypes + observation.networkType
            }
        } else {
            profile.knownNetworkTypes
        }

        // 3. Reboot counts
        val updatedReboots = profile.totalRebootAnomaliesRecorded + if (observation.isRebootAnomaly) 1 else 0

        // 4. Mean auth failures: Do not normalize active attacks into baseline
        val updatedMeanAuth = if (observation.failedAuthAttempts >= 3 && (profile.isBaselineEstablished || profile.sampleCount >= 3)) {
            profile.meanFailedAuthPerSession
        } else {
            ((profile.meanFailedAuthPerSession * profile.sampleCount) + observation.failedAuthAttempts) / count.toFloat()
        }

        // 5. Motion online statistics with EWMA recency decay so old habits do not permanently dominate
        var mMean = profile.motionMeanMagnitude
        var mVar = profile.motionVarianceMagnitude
        var mCount = profile.motionSampleCount
        if (observation.accelerationMagnitude != null && observation.accelerationMagnitude > 0f) {
            val isExtremeShock = (profile.isBaselineEstablished || profile.sampleCount >= 3) && observation.accelerationMagnitude > 16.0f
            if (!isExtremeShock) {
                mCount++
                if (mCount <= minRequiredSamples) {
                    val delta = observation.accelerationMagnitude - mMean
                    mMean += delta / mCount
                    val delta2 = observation.accelerationMagnitude - mMean
                    val m2 = (mVar * (mCount - 1).coerceAtLeast(1)) + (delta * delta2)
                    mVar = (m2 / mCount).coerceAtLeast(0.1f)
                } else {
                    val alpha = 0.08f
                    val delta = observation.accelerationMagnitude - mMean
                    mMean += alpha * delta
                    mVar = ((1f - alpha) * mVar + alpha * (delta * delta)).coerceAtLeast(0.1f)
                }
            }
        }

        // 6. Geolocation centroid with EWMA recency decay & perimeter guard
        var cLat = profile.centroidLatitude
        var cLng = profile.centroidLongitude
        var maxRadius = profile.maxObservedRadiusMeters
        var lCount = profile.locationSampleCount
        if (observation.latitude != null && observation.longitude != null) {
            val isDisplacementExited = (profile.isBaselineEstablished || profile.sampleCount >= 3) && observation.isInsideTrustedZone == false
            if (!isDisplacementExited) {
                lCount++
                if (cLat == null || cLng == null) {
                    cLat = observation.latitude
                    cLng = observation.longitude
                    maxRadius = 100.0 // initial nominal meters
                } else {
                    val distMeters = approximateDistanceMeters(cLat, cLng, observation.latitude, observation.longitude)
                    val locAlpha = if (lCount <= minRequiredSamples) (1.0 / lCount) else 0.08
                    cLat += (observation.latitude - cLat) * locAlpha
                    cLng += (observation.longitude - cLng) * locAlpha
                    if (distMeters > maxRadius) {
                        maxRadius = (maxRadius * 0.95 + distMeters * 0.05).coerceAtMost(50_000.0)
                    }
                }
            }
        }

        val isEstablished = count >= minRequiredSamples
        val confidence = (count.toFloat() / (minRequiredSamples * 2).toFloat()).coerceIn(0.0f, 1.0f)

        profile = profile.copy(
            sampleCount = count,
            isBaselineEstablished = isEstablished,
            confidence = confidence,
            firstSampleTimestamp = firstTs,
            lastSampleTimestamp = lastTs,
            isStale = false,
            knownCarriers = updatedCarriers,
            knownNetworkTypes = updatedNetworks,
            totalRebootAnomaliesRecorded = updatedReboots,
            meanFailedAuthPerSession = updatedMeanAuth,
            motionMeanMagnitude = mMean,
            motionVarianceMagnitude = mVar,
            motionSampleCount = mCount,
            centroidLatitude = cLat,
            centroidLongitude = cLng,
            maxObservedRadiusMeters = maxRadius,
            locationSampleCount = lCount
        )
        persistProfile()
    }

    /**
     * Evaluates an observation against the established baseline to detect deviations.
     * Returns empty list if baseline is not yet established, if paused, or if data is stale.
     * ANOMALIES ARE EVIDENCE ONLY, NOT PROOF OF THEFT.
     */
    @Synchronized
    fun evaluateDeviations(
        observation: BehavioralObservation,
        now: Long = System.currentTimeMillis()
    ): List<BehavioralAnomaly> {
        val currentProfile = profile

        // 1. Guard: Check if paused
        if (currentProfile.isPaused) return emptyList()

        // 2. PRIVACY & SECURITY GUARD: GUEST and TRUSTED_PERSON authorized sessions do not evaluate theft deviations against OWNER baseline
        if (observation.role != com.example.core.model.UserRole.OWNER) return emptyList()

        // 3. Guard: Check staleness
        val isStale = (now - currentProfile.lastSampleTimestamp) > staleThresholdMillis && currentProfile.sampleCount > 0
        if (isStale) {
            profile = profile.copy(isStale = true)
            return emptyList()
        }

        // 4. Guard: Check if baseline is established
        if (!currentProfile.isBaselineEstablished) {
            return emptyList()
        }

        val anomalies = mutableListOf<BehavioralAnomaly>()

        // Check A: SIM Carrier deviation
        if (!observation.simCarrier.isNullOrBlank() &&
            currentProfile.knownCarriers.isNotEmpty() &&
            !currentProfile.knownCarriers.contains(observation.simCarrier)
        ) {
            anomalies.add(
                BehavioralAnomaly(
                    factorKey = "BEHAVIORAL_SIM_DEVIATION",
                    category = EventCategory.SIM_TELEPHONY,
                    title = "Unrecognized SIM Carrier Deviation",
                    explanation = "Observed SIM '${observation.simCarrier}' deviates from learned carrier profile (${currentProfile.knownCarriers.joinToString(", ")}).",
                    severity = EventSeverity.CRITICAL,
                    deviationScore = 30,
                    timestamp = observation.timestamp
                )
            )
        }

        // Check B: Multiple unexpected authentication failures relative to baseline
        if (observation.failedAuthAttempts >= 3 && observation.failedAuthAttempts > (currentProfile.meanFailedAuthPerSession + 2.0f)) {
            anomalies.add(
                BehavioralAnomaly(
                    factorKey = "BEHAVIORAL_AUTH_SPIKE",
                    category = EventCategory.AUTHENTICATION_ACCESS,
                    title = "Authentication Failure Frequency Spike",
                    explanation = "${observation.failedAuthAttempts} authentication failures significantly exceeds learned average (${"%.1f".format(currentProfile.meanFailedAuthPerSession)}).",
                    severity = EventSeverity.WARNING,
                    deviationScore = 35,
                    timestamp = observation.timestamp
                )
            )
        }

        // Check C: Sudden unexpected reboot deviation
        if (observation.isRebootAnomaly) {
            anomalies.add(
                BehavioralAnomaly(
                    factorKey = "BEHAVIORAL_REBOOT_ANOMALY",
                    category = EventCategory.SYSTEM_LIFECYCLE,
                    title = "Anomalous System Restart Pattern",
                    explanation = "Unscheduled system restart event deviates from device nominal uptime baseline.",
                    severity = EventSeverity.WARNING,
                    deviationScore = 20,
                    timestamp = observation.timestamp
                )
            )
        }

        // Check D: Kinetic motion z-score spike (> 3 standard deviations)
        if (observation.accelerationMagnitude != null && currentProfile.motionSampleCount >= 5) {
            val stdDev = sqrt(currentProfile.motionVarianceMagnitude.toDouble()).toFloat().coerceAtLeast(0.5f)
            val zScore = abs(observation.accelerationMagnitude - currentProfile.motionMeanMagnitude) / stdDev
            if (zScore > 3.0f && observation.accelerationMagnitude > 16.0f) {
                anomalies.add(
                    BehavioralAnomaly(
                        factorKey = "BEHAVIORAL_KINETIC_DISRUPTION",
                        category = EventCategory.KINETIC_MOTION,
                        title = "Kinetic Motion Statistical Anomaly",
                        explanation = "Acceleration magnitude (${"%.1f".format(observation.accelerationMagnitude)} m/s²) deviates by ${"%.1f".format(zScore)}σ from learned baseline (${"%.1f".format(currentProfile.motionMeanMagnitude)} ± ${"%.1f".format(stdDev)}).",
                        severity = EventSeverity.WARNING,
                        deviationScore = 15,
                        timestamp = observation.timestamp
                    )
                )
            }
        }

        // Check E: Geolocation perimeter deviation (outside trusted zone AND outside learned centroid radius)
        if (observation.latitude != null && observation.longitude != null &&
            currentProfile.centroidLatitude != null && currentProfile.centroidLongitude != null
        ) {
            val dist = approximateDistanceMeters(
                currentProfile.centroidLatitude,
                currentProfile.centroidLongitude,
                observation.latitude,
                observation.longitude
            )
            val isZoneExited = observation.isInsideTrustedZone == false
            // Beyond 2.5x the max observed historical radius and flagged as outside zone
            if (isZoneExited && dist > (currentProfile.maxObservedRadiusMeters * 2.5).coerceAtLeast(1000.0)) {
                anomalies.add(
                    BehavioralAnomaly(
                        factorKey = "BEHAVIORAL_LOCATION_DISPERSION",
                        category = EventCategory.GEOLOCATION_DISPLACEMENT,
                        title = "Geographic Dispersion Beyond Behavioral Centroid",
                        explanation = "Device location is ${"%.0f".format(dist)}m from learned geographic centroid (baseline radius: ${"%.0f".format(currentProfile.maxObservedRadiusMeters)}m) outside trusted boundary.",
                        severity = EventSeverity.WARNING,
                        deviationScore = 20,
                        timestamp = observation.timestamp
                    )
                )
            }
        }

        // Check F: Radio network severed while uncharacteristic of learned profile
        if (!observation.isNetworkConnected && currentProfile.knownNetworkTypes.isNotEmpty()) {
            anomalies.add(
                BehavioralAnomaly(
                    factorKey = "BEHAVIORAL_RADIO_SEVERED",
                    category = EventCategory.NETWORK_CONNECTIVITY,
                    title = "Radio Connectivity Disrupted",
                    explanation = "Device radio connection severed unexpectedly against continuous baseline connectivity.",
                    severity = EventSeverity.INFO,
                    deviationScore = 10,
                    timestamp = observation.timestamp
                )
            )
        }

        // Check G: Unrecognized network interface type deviation
        if (!observation.networkType.isNullOrBlank() &&
            currentProfile.knownNetworkTypes.isNotEmpty() &&
            !currentProfile.knownNetworkTypes.contains(observation.networkType)
        ) {
            anomalies.add(
                BehavioralAnomaly(
                    factorKey = "BEHAVIORAL_NETWORK_DEVIATION",
                    category = EventCategory.NETWORK_CONNECTIVITY,
                    title = "Unrecognized Network Interface Type",
                    explanation = "Observed network '${observation.networkType}' deviates from learned baseline networks (${currentProfile.knownNetworkTypes.joinToString(", ")}).",
                    severity = EventSeverity.INFO,
                    deviationScore = 15,
                    timestamp = observation.timestamp
                )
            )
        }

        return anomalies
    }
}
