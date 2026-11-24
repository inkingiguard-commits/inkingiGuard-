package com.example.domain.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.core.model.ConfidenceLevel
import com.example.core.model.MotionEvent
import com.example.core.model.MovementType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import kotlin.math.sqrt

/**
 * Sensor Intelligence Engine.
 * Dynamically queries hardware sensor availability on the device.
 * Normalizes live telemetry and analyzes kinetic motion vectors.
 * Never crashes if any sensor is missing on the specific hardware.
 */
class SensorIntelligenceEngine(
    context: Context,
    private val deviceId: String
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    data class SensorHardwareStatus(
        val typeId: Int,
        val displayName: String,
        val isAvailable: Boolean,
        val vendor: String? = null,
        val powerMa: Float = 0f,
        val lastNormalizedReading: String = "Idle"
    )

    data class SensorMeshSnapshot(
        val availableSensors: List<SensorHardwareStatus>,
        val currentAccelerationMagnitude: Float = 9.8f,
        val currentRotationRate: Float = 0f,
        val currentLightLux: Float? = null,
        val currentProximityCm: Float? = null,
        val currentPressureHpa: Float? = null,
        val lastMotionEvent: MotionEvent? = null,
        val isMonitoringActive: Boolean = false
    )

    private val _meshSnapshot = MutableStateFlow(SensorMeshSnapshot(availableSensors = emptyList()))
    val meshSnapshot: StateFlow<SensorMeshSnapshot> = _meshSnapshot.asStateFlow()

    private var accelSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private var lightSensor: Sensor? = null
    private var proximitySensor: Sensor? = null
    private var pressureSensor: Sensor? = null
    private var rotationSensor: Sensor? = null
    private var magSensor: Sensor? = null

    private var lastAccelUpdateTime = 0L
    private var lastReportedAccel = 9.8f
    private var lastGyroUpdateTime = 0L
    private var lastReportedGyro = 0f
    private var lastLightUpdateTime = 0L
    private var lastLightVal = -1f
    private var lastProxUpdateTime = 0L
    private var lastProxVal = -1f
    private var lastPressUpdateTime = 0L
    private var lastPressVal = -1f

    init {
        detectSensors()
    }

    private fun detectSensors() {
        if (sensorManager == null) {
            _meshSnapshot.value = _meshSnapshot.value.copy(availableSensors = emptyList())
            return
        }

        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val sigMotionSensor = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

        val list = listOf(
            SensorHardwareStatus(
                typeId = Sensor.TYPE_ACCELEROMETER,
                displayName = "Accelerometer",
                isAvailable = accelSensor != null,
                vendor = accelSensor?.vendor,
                powerMa = accelSensor?.power ?: 0f
            ),
            SensorHardwareStatus(
                typeId = Sensor.TYPE_GYROSCOPE,
                displayName = "Gyroscope",
                isAvailable = gyroSensor != null,
                vendor = gyroSensor?.vendor,
                powerMa = gyroSensor?.power ?: 0f
            ),
            SensorHardwareStatus(
                typeId = Sensor.TYPE_ROTATION_VECTOR,
                displayName = "Rotation Vector",
                isAvailable = rotationSensor != null,
                vendor = rotationSensor?.vendor,
                powerMa = rotationSensor?.power ?: 0f
            ),
            SensorHardwareStatus(
                typeId = Sensor.TYPE_MAGNETIC_FIELD,
                displayName = "Magnetometer",
                isAvailable = magSensor != null,
                vendor = magSensor?.vendor,
                powerMa = magSensor?.power ?: 0f
            ),
            SensorHardwareStatus(
                typeId = Sensor.TYPE_PROXIMITY,
                displayName = "Proximity",
                isAvailable = proximitySensor != null,
                vendor = proximitySensor?.vendor,
                powerMa = proximitySensor?.power ?: 0f
            ),
            SensorHardwareStatus(
                typeId = Sensor.TYPE_LIGHT,
                displayName = "Ambient Light",
                isAvailable = lightSensor != null,
                vendor = lightSensor?.vendor,
                powerMa = lightSensor?.power ?: 0f
            ),
            SensorHardwareStatus(
                typeId = Sensor.TYPE_PRESSURE,
                displayName = "Pressure / Barometer",
                isAvailable = pressureSensor != null,
                vendor = pressureSensor?.vendor,
                powerMa = pressureSensor?.power ?: 0f
            ),
            SensorHardwareStatus(
                typeId = Sensor.TYPE_SIGNIFICANT_MOTION,
                displayName = "Significant Motion",
                isAvailable = sigMotionSensor != null,
                vendor = sigMotionSensor?.vendor,
                powerMa = sigMotionSensor?.power ?: 0f
            )
        )

        _meshSnapshot.value = _meshSnapshot.value.copy(availableSensors = list)
    }

    fun startListening() {
        val sm = sensorManager ?: return
        accelSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        gyroSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        lightSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        proximitySensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        pressureSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        _meshSnapshot.value = _meshSnapshot.value.copy(isMonitoringActive = true)
    }

    fun stopListening() {
        sensorManager?.unregisterListener(this)
        _meshSnapshot.value = _meshSnapshot.value.copy(isMonitoringActive = false)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values.getOrNull(0) ?: 0f
                val y = event.values.getOrNull(1) ?: 0f
                val z = event.values.getOrNull(2) ?: 0f
                val magnitude = sqrt(x * x + y * y + z * z)
                val now = System.currentTimeMillis()

                val motionEvent = analyzeAcceleration(magnitude)
                val isAnomaly = magnitude > 18.0f || (motionEvent != null && motionEvent.movementType == MovementType.SUDDEN_ACCELERATION)
                val shouldUpdate = isAnomaly || (now - lastAccelUpdateTime > 400L && kotlin.math.abs(magnitude - lastReportedAccel) > 0.4f)

                if (shouldUpdate) {
                    lastAccelUpdateTime = now
                    lastReportedAccel = magnitude
                    _meshSnapshot.value = _meshSnapshot.value.copy(
                        currentAccelerationMagnitude = magnitude,
                        lastMotionEvent = motionEvent ?: _meshSnapshot.value.lastMotionEvent
                    )
                }
            }

            Sensor.TYPE_GYROSCOPE -> {
                val gx = event.values.getOrNull(0) ?: 0f
                val gy = event.values.getOrNull(1) ?: 0f
                val gz = event.values.getOrNull(2) ?: 0f
                val rotationRate = sqrt(gx * gx + gy * gy + gz * gz)
                val now = System.currentTimeMillis()

                val isAnomaly = rotationRate > 4.5f
                val shouldUpdate = isAnomaly || (now - lastGyroUpdateTime > 500L && kotlin.math.abs(rotationRate - lastReportedGyro) > 0.3f)

                if (shouldUpdate) {
                    lastGyroUpdateTime = now
                    lastReportedGyro = rotationRate

                    val motionEvent = if (isAnomaly) {
                        MotionEvent(
                            eventId = UUID.randomUUID().toString(),
                            deviceId = deviceId,
                            sensorType = "GYROSCOPE",
                            movementType = MovementType.UNUSUAL_ROTATION,
                            confidence = ConfidenceLevel.MEDIUM_CONFIDENCE,
                            timestamp = now,
                            accelerationMagnitude = _meshSnapshot.value.currentAccelerationMagnitude,
                            rotationRate = rotationRate,
                            details = "Angular velocity spike of %.2f rad/s".format(rotationRate)
                        )
                    } else null

                    _meshSnapshot.value = _meshSnapshot.value.copy(
                        currentRotationRate = rotationRate,
                        lastMotionEvent = motionEvent ?: _meshSnapshot.value.lastMotionEvent
                    )
                }
            }

            Sensor.TYPE_LIGHT -> {
                val lux = event.values.getOrNull(0) ?: 0f
                val now = System.currentTimeMillis()
                if (now - lastLightUpdateTime > 500L || kotlin.math.abs(lux - lastLightVal) > 5f) {
                    lastLightUpdateTime = now
                    lastLightVal = lux
                    _meshSnapshot.value = _meshSnapshot.value.copy(currentLightLux = lux)
                }
            }

            Sensor.TYPE_PROXIMITY -> {
                val cm = event.values.getOrNull(0) ?: 0f
                val now = System.currentTimeMillis()
                if (now - lastProxUpdateTime > 500L || kotlin.math.abs(cm - lastProxVal) > 1f) {
                    lastProxUpdateTime = now
                    lastProxVal = cm
                    _meshSnapshot.value = _meshSnapshot.value.copy(currentProximityCm = cm)
                }
            }

            Sensor.TYPE_PRESSURE -> {
                val hpa = event.values.getOrNull(0) ?: 0f
                val now = System.currentTimeMillis()
                if (now - lastPressUpdateTime > 1000L || kotlin.math.abs(hpa - lastPressVal) > 2f) {
                    lastPressUpdateTime = now
                    lastPressVal = hpa
                    _meshSnapshot.value = _meshSnapshot.value.copy(currentPressureHpa = hpa)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op for standard telemetry
    }

    private fun analyzeAcceleration(magnitude: Float): MotionEvent? {
        val diffFromGravity = kotlin.math.abs(magnitude - 9.81f)
        return when {
            magnitude > 18.0f -> {
                MotionEvent(
                    eventId = UUID.randomUUID().toString(),
                    deviceId = deviceId,
                    sensorType = "ACCELEROMETER",
                    movementType = MovementType.SUDDEN_ACCELERATION,
                    confidence = ConfidenceLevel.HIGH_CONFIDENCE,
                    timestamp = System.currentTimeMillis(),
                    accelerationMagnitude = magnitude,
                    rotationRate = _meshSnapshot.value.currentRotationRate,
                    details = "Kinetic acceleration spike: %.1f m/s²".format(magnitude)
                )
            }
            diffFromGravity > 3.0f -> {
                MotionEvent(
                    eventId = UUID.randomUUID().toString(),
                    deviceId = deviceId,
                    sensorType = "ACCELEROMETER",
                    movementType = MovementType.NORMAL_HANDHELD,
                    confidence = ConfidenceLevel.LOW_CONFIDENCE,
                    timestamp = System.currentTimeMillis(),
                    accelerationMagnitude = magnitude,
                    rotationRate = _meshSnapshot.value.currentRotationRate,
                    details = "Active device handling: %.1f m/s²".format(magnitude)
                )
            }
            else -> null
        }
    }

    /**
     * Diagnostic simulator helper to test physical anomaly response when on JVM test/emulator
     */
    fun triggerSimulatedKineticAnomaly() {
        val spikeEvent = MotionEvent(
            eventId = UUID.randomUUID().toString(),
            deviceId = deviceId,
            sensorType = "ACCELEROMETER_DIAGNOSTIC",
            movementType = MovementType.SUDDEN_ACCELERATION,
            confidence = ConfidenceLevel.HIGH_CONFIDENCE,
            timestamp = System.currentTimeMillis(),
            accelerationMagnitude = 21.4f,
            rotationRate = 5.2f,
            details = "Simulated kinetic impact diagnostic test"
        )
        _meshSnapshot.value = _meshSnapshot.value.copy(
            currentAccelerationMagnitude = 21.4f,
            currentRotationRate = 5.2f,
            lastMotionEvent = spikeEvent
        )
    }

    fun clearMotionEvent() {
        _meshSnapshot.value = _meshSnapshot.value.copy(
            currentAccelerationMagnitude = 9.8f,
            currentRotationRate = 0f,
            lastMotionEvent = null
        )
    }
}
