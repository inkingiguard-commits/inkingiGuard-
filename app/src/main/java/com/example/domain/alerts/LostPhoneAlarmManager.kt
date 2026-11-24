package com.example.domain.alerts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Manages audible alarms for locating a misplaced/lost device nearby.
 *
 * Guarantees:
 * - Uses Android RingtoneManager with TYPE_ALARM or TYPE_RINGTONE.
 * - Streams over USAGE_ALARM / STREAM_ALARM so it is clearly audible.
 * - Configurable safety timeout (default 2 minutes) to prevent battery exhaustion.
 * - Never touches the microphone or accesses unauthorized audio recording APIs.
 */
class LostPhoneAlarmManager(private val context: Context) {

    companion object {
        private const val TAG = "LostPhoneAlarmManager"
        private const val DEFAULT_TIMEOUT_MS = 120_000L // 2 minutes auto-stop
    }

    private var activeRingtone: Ringtone? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isPlaying = false
    var onAlarmStopped: (() -> Unit)? = null

    private val stopAlarmRunnable = Runnable {
        stopAlarm()
        Log.i(TAG, "Find My Phone alarm stopped automatically after safety timeout.")
        onAlarmStopped?.invoke()
    }

    fun isAlarmActive(): Boolean = isPlaying

    @Synchronized
    fun startAlarm(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        if (isPlaying) {
            Log.d(TAG, "Alarm already actively sounding.")
            return true
        }

        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val ringtone = if (alarmUri != null) RingtoneManager.getRingtone(context, alarmUri) else null

            if (ringtone != null) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()

                ringtone.audioAttributes = audioAttributes

                // Maximize audio volume on STREAM_ALARM safely
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                audioManager?.let { am ->
                    val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                    am.setStreamVolume(AudioManager.STREAM_ALARM, (maxVol * 0.9f).toInt().coerceAtLeast(1), 0)
                }

                ringtone.play()
                activeRingtone = ringtone
            }

            isPlaying = true

            // Schedule safety timeout
            mainHandler.removeCallbacks(stopAlarmRunnable)
            mainHandler.postDelayed(stopAlarmRunnable, timeoutMs)

            Log.i(TAG, "Find My Phone alarm sounding successfully.")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start lost phone alarm: ${e.message}")
            return false
        }
    }

    @Synchronized
    fun stopAlarm() {
        try {
            mainHandler.removeCallbacks(stopAlarmRunnable)
            activeRingtone?.let {
                if (it.isPlaying) {
                    it.stop()
                }
            }
            activeRingtone = null
            isPlaying = false
            Log.i(TAG, "Find My Phone alarm silenced by owner.")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping alarm: ${e.message}")
        }
    }
}
