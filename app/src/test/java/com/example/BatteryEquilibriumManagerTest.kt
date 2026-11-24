package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.device.BatteryEquilibriumManager
import com.example.domain.device.EquilibriumMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BatteryEquilibriumManagerTest {

    private lateinit var context: Context
    private lateinit var manager: BatteryEquilibriumManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        manager = BatteryEquilibriumManager(context)
    }

    @Test
    fun `battery 80 percent is WARRIOR mode with 15min gps and safe non-destructive flags`() {
        val state = manager.maintainEquilibre(batteryLevel = 80, isCharging = false, isTheftDetected = false)
        assertEquals(EquilibriumMode.WARRIOR, state.mode)
        assertEquals(15, state.gpsIntervalMinutes)
        assertFalse("Unsupported camera capture must remain disabled", state.allowCamera)
        assertTrue(state.isAdvisoryOnly)
        assertTrue(state.keepsConnectivityActive)
        assertTrue(state.keepsRecoveryServiceActive)
        assertTrue(state.actions.contains("full_defense"))
        assertTrue(state.actions.contains("gps_15min"))
    }

    @Test
    fun `battery 80 percent during theft keeps camera disabled and keeps recovery service active`() {
        val state = manager.maintainEquilibre(batteryLevel = 80, isCharging = false, isTheftDetected = true)
        assertEquals(EquilibriumMode.WARRIOR, state.mode)
        assertFalse("Camera opt-in/capture was removed until real CameraX support exists", state.allowCamera)
        assertTrue(state.actions.contains("keep_recovery_service_active"))
    }

    @Test
    fun `battery 25 percent is BALANCE mode advisory only and preserves connectivity`() {
        val state = manager.maintainEquilibre(batteryLevel = 25, isCharging = false, isTheftDetected = false)
        assertEquals(EquilibriumMode.BALANCE, state.mode)
        assertEquals(15, state.gpsIntervalMinutes)
        assertFalse(state.allowCamera)
        assertTrue(state.isAdvisoryOnly)
        assertTrue(state.keepsConnectivityActive)
        assertTrue(state.keepsRecoveryServiceActive)
        assertTrue(state.actions.contains("advisory_reduce_brightness_50_percent"))
        assertTrue(state.actions.contains("keep_connectivity_active"))
        assertTrue(state.actions.contains("keep_defense_active"))
        assertTrue(state.actions.contains("advisory_only_no_process_kill"))
    }

    @Test
    fun `battery 15 percent boundary does not hibernate or kill connectivity`() {
        val state = manager.maintainEquilibre(batteryLevel = 15, isCharging = false, isTheftDetected = false)
        assertEquals(EquilibriumMode.BALANCE, state.mode)
        assertEquals(15, state.gpsIntervalMinutes)
        assertTrue(state.keepsConnectivityActive)
        assertTrue(state.keepsRecoveryServiceActive)
        assertFalse(state.actions.any { it.contains("hibernate", ignoreCase = true) })
    }

    @Test
    fun `battery 10 percent is LOW mode advisory only and keeps emergency calls and GPS recovery active`() {
        val normalState = manager.maintainEquilibre(batteryLevel = 10, isCharging = false, isTheftDetected = false)
        assertEquals(EquilibriumMode.LOW, normalState.mode)
        assertEquals(30, normalState.gpsIntervalMinutes)
        assertFalse(normalState.allowCamera)
        assertTrue(normalState.isAdvisoryOnly)
        assertTrue(normalState.keepsConnectivityActive)
        assertTrue(normalState.keepsRecoveryServiceActive)
        assertTrue(normalState.actions.contains("preserve_phone_usability"))
        assertTrue(normalState.actions.contains("allow_emergency_calls"))
        assertTrue(normalState.actions.contains("advisory_only_no_process_kill"))
        assertFalse(normalState.actions.any { it.contains("hibernate", ignoreCase = true) })

        val theftState = manager.maintainEquilibre(batteryLevel = 10, isCharging = false, isTheftDetected = true)
        assertEquals(EquilibriumMode.LOW, theftState.mode)
        assertEquals("GPS recovery must stay active frequently during theft even at 10%", 1, theftState.gpsIntervalMinutes)
        assertTrue(theftState.keepsConnectivityActive)
        assertTrue(theftState.keepsRecoveryServiceActive)
    }

    @Test
    fun `battery 4 percent SURVIVAL mode never hibernates never kills services and never claims fake SMS`() {
        val state = manager.maintainEquilibre(batteryLevel = 4, isCharging = false, isTheftDetected = true)
        assertEquals(EquilibriumMode.SURVIVAL, state.mode)
        assertEquals(1, state.gpsIntervalMinutes)
        assertFalse(state.allowCamera)
        assertTrue(state.isAdvisoryOnly)
        assertTrue(state.keepsConnectivityActive)
        assertTrue(state.keepsRecoveryServiceActive)
        assertFalse(state.actions.any { it.contains("hibernate", ignoreCase = true) })
        assertFalse(state.actions.contains("send_sms_to_owner_battery_critical"))
        assertTrue(state.actions.contains("preserve_phone_usability"))
        assertTrue(state.actions.contains("allow_emergency_calls"))
        assertTrue(state.actions.contains("keep_recovery_service_active"))
        assertTrue(state.actions.contains("no_hibernation_user_active"))
    }

    @Test
    fun `executeBalanceMode returns safe advisory recommendations without throwing`() {
        val advisories = manager.executeBalanceMode()
        assertTrue(advisories.isNotEmpty())
        assertTrue(advisories.all { it.startsWith("advisory_") })
    }
}
