package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.core.model.DeviceProfile
import com.example.core.model.TrustedPerson
import com.example.core.model.UserRole
import com.example.domain.security.OfflineSecurityVault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class DeviceSettingsRepository(
    context: Context,
    private val vault: OfflineSecurityVault = OfflineSecurityVault(context.applicationContext ?: context)
) {

    companion object {
        const val PREFS_NAME = "inkingi_guard_prefs"
        const val LEGACY_PREFS_NAME = "inkingi_prefs"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_STOLEN_ACTIVE = "is_stolen_active"
        const val KEY_ACTIVE_SECURITY_STATE = "active_security_state"
        const val KEY_LAST_SYNC_AT = "last_location_sync_at"
        const val KEY_RECOVERY_CONTACT_PHONE = "recovery_contact_phone"
        const val KEY_LOCK_SCREEN_RECOVERY_MESSAGE = "lock_screen_recovery_message"
        const val KEY_LEARNING_PAUSED = "behavioral_learning_paused"
        private const val KEY_LEGACY_PIN_HASH = "pin_hash"
        private const val VAULT_PROTECTION_MARKER = "PBKDF2_VAULT_PROTECTED"
        private val PHONE_FORMAT_REGEX = Regex("^\\+?[0-9][0-9\\s().-]{4,28}[0-9]$")

        fun isValidRecoveryPhone(phone: String): Boolean {
            val trimmed = phone.trim()
            if (trimmed.isEmpty() || trimmed.length > 30) return false
            if (!PHONE_FORMAT_REGEX.matches(trimmed)) return false
            val digitCount = trimmed.count { it.isDigit() }
            return digitCount in 6..15
        }

        fun getDeviceId(context: Context): String {
            val primaryPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            val existing = primaryPrefs.getString(KEY_DEVICE_ID, null)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: legacyPrefs.getString(KEY_DEVICE_ID, null)?.trim()?.takeIf { it.isNotEmpty() }
            val resolved = existing ?: ("IG-" + UUID.randomUUID().toString().take(8).uppercase())
            if (primaryPrefs.getString(KEY_DEVICE_ID, null) != resolved) {
                primaryPrefs.edit().putString(KEY_DEVICE_ID, resolved).apply()
            }
            if (legacyPrefs.getString(KEY_DEVICE_ID, null) != resolved) {
                legacyPrefs.edit().putString(KEY_DEVICE_ID, resolved).apply()
            }
            return resolved
        }

        fun getDeviceName(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_DEVICE_NAME, null)?.trim()?.takeIf { it.isNotEmpty() }
                ?: android.os.Build.MODEL?.takeIf { it.isNotBlank() }
                ?: "Android Secure Node"
        }

        fun setStolenTrackingActive(context: Context, active: Boolean, stateName: String? = null) {
            setStolenTrackingActiveCommitted(context, active, stateName)
        }

        fun setStolenTrackingActiveCommitted(
            context: Context,
            active: Boolean,
            stateName: String? = null
        ): Boolean {
            return try {
                val primaryPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                val primaryOk = primaryPrefs.edit().apply {
                    putBoolean(KEY_STOLEN_ACTIVE, active)
                    if (stateName != null) putString(KEY_ACTIVE_SECURITY_STATE, stateName)
                    else if (!active) remove(KEY_ACTIVE_SECURITY_STATE)
                }.commit()
                val legacyOk = legacyPrefs.edit().apply {
                    putBoolean(KEY_STOLEN_ACTIVE, active)
                    if (stateName != null) putString(KEY_ACTIVE_SECURITY_STATE, stateName)
                    else if (!active) remove(KEY_ACTIVE_SECURITY_STATE)
                }.commit()
                primaryOk && legacyOk
            } catch (_: Exception) {
                false
            }
        }

        fun isStolenTrackingActive(context: Context): Boolean {
            val primaryPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            return primaryPrefs.getBoolean(KEY_STOLEN_ACTIVE, false) ||
                legacyPrefs.getBoolean(KEY_STOLEN_ACTIVE, false)
        }

        fun getPersistedSecurityStateName(context: Context): String? {
            val primaryPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            return primaryPrefs.getString(KEY_ACTIVE_SECURITY_STATE, null)
                ?: legacyPrefs.getString(KEY_ACTIVE_SECURITY_STATE, null)
        }

        fun saveLastSuccessfulSyncTimestamp(context: Context, timestampMs: Long) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_SYNC_AT, timestampMs)
                .apply()
        }

        fun getLastSuccessfulSyncTimestamp(context: Context): Long? {
            val ts = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_SYNC_AT, -1L)
            return if (ts > 0L) ts else null
        }

        fun getSavedRecoveryContact(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val saved = prefs.getString(KEY_RECOVERY_CONTACT_PHONE, "")?.trim().orEmpty()
            return if (isValidRecoveryPhone(saved)) saved else ""
        }

        fun getSavedLockScreenMessage(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_LOCK_SCREEN_RECOVERY_MESSAGE, "")
                ?.trim()
                ?.take(150)
                .orEmpty()
        }

        fun isLearningPaused(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_LEARNING_PAUSED, false)
        }
    }

    private val appContext: Context = context.applicationContext ?: context

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // Purge any legacy unencrypted static-salted SHA-256 PIN hash so it can never be reused
        if (prefs.contains(KEY_LEGACY_PIN_HASH)) {
            prefs.edit().remove(KEY_LEGACY_PIN_HASH).apply()
        }
    }

    private val _deviceProfile = MutableStateFlow(loadProfile())
    val deviceProfile: StateFlow<DeviceProfile> = _deviceProfile.asStateFlow()

    private val _isPinConfigured = MutableStateFlow(vault.isMasterPinConfigured())
    val isPinConfigured: StateFlow<Boolean> = _isPinConfigured.asStateFlow()

    val vaultHealth: StateFlow<OfflineSecurityVault.VaultHealthSnapshot> = vault.vaultHealth

    private val _currentLanguage = MutableStateFlow(prefs.getString("pref_language", "fr") ?: "fr")
    val currentLanguage: StateFlow<String> = _currentLanguage.asStateFlow()

    private val _currentUserRole = MutableStateFlow(loadUserRole())
    val currentUserRole: StateFlow<UserRole> = _currentUserRole.asStateFlow()

    private val _trustedPersons = MutableStateFlow(loadTrustedPersons())
    val trustedPersons: StateFlow<List<TrustedPerson>> = _trustedPersons.asStateFlow()

    private val _recoveryContactPhone = MutableStateFlow(loadRecoveryContact())
    val recoveryContactPhone: StateFlow<String> = _recoveryContactPhone.asStateFlow()

    private val _lockScreenRecoveryMessage = MutableStateFlow(loadLockScreenMessage())
    val lockScreenRecoveryMessage: StateFlow<String> = _lockScreenRecoveryMessage.asStateFlow()

    private val _isLearningPaused = MutableStateFlow(prefs.getBoolean(KEY_LEARNING_PAUSED, false))
    val isLearningPaused: StateFlow<Boolean> = _isLearningPaused.asStateFlow()

    private fun loadRecoveryContact(): String {
        val saved = prefs.getString(KEY_RECOVERY_CONTACT_PHONE, "")?.trim().orEmpty()
        return if (isValidRecoveryPhone(saved)) saved else ""
    }

    private fun loadLockScreenMessage(): String {
        return prefs.getString(KEY_LOCK_SCREEN_RECOVERY_MESSAGE, "")
            ?.trim()
            ?.take(150)
            .orEmpty()
    }

    fun saveLockScreenRecoveryConfig(message: String, contactPhone: String): Boolean {
        val sanitizedMsg = message.trim().take(150)
        val sanitizedPhone = contactPhone.trim().take(30)
        if (sanitizedMsg.isBlank()) return false
        if (sanitizedPhone.isNotBlank() && !isValidRecoveryPhone(sanitizedPhone)) return false

        prefs.edit()
            .putString(KEY_LOCK_SCREEN_RECOVERY_MESSAGE, sanitizedMsg)
            .putString(KEY_RECOVERY_CONTACT_PHONE, sanitizedPhone)
            .apply()

        _lockScreenRecoveryMessage.value = sanitizedMsg
        _recoveryContactPhone.value = sanitizedPhone
        return true
    }

    private fun loadProfile(): DeviceProfile {
        val deviceId = getDeviceId(appContext)
        val isRegistered = prefs.getBoolean("is_registered", false)
        val deviceName = prefs.getString("device_name", getDeviceName(appContext)) ?: getDeviceName(appContext)
        val ownerEmail = prefs.getString("owner_email", "")?.trim().orEmpty()
        val pinProtectedMarker = if (vault.isMasterPinConfigured()) VAULT_PROTECTION_MARKER else ""
        val biometric = prefs.getBoolean("biometric_enabled", true)

        return DeviceProfile(
            deviceId = deviceId,
            deviceName = deviceName,
            ownerEmail = ownerEmail,
            isRegistered = isRegistered,
            masterPinHash = pinProtectedMarker,
            biometricEnabled = biometric
        )
    }

    private fun loadUserRole(): UserRole {
        val roleStr = prefs.getString("current_user_role", UserRole.OWNER.name) ?: UserRole.OWNER.name
        return try {
            UserRole.valueOf(roleStr)
        } catch (_: Exception) {
            UserRole.OWNER
        }
    }

    fun switchUserRole(targetRole: UserRole, masterPin: String?): Boolean {
        // If switching away from GUEST back to OWNER, require master PIN verification
        if (targetRole == UserRole.OWNER) {
            if (masterPin.isNullOrBlank() || !verifyPin(masterPin)) {
                return false
            }
        }
        prefs.edit().putString("current_user_role", targetRole.name).apply()
        _currentUserRole.value = targetRole
        return true
    }

    private fun loadTrustedPersons(): List<TrustedPerson> {
        val raw = prefs.getString("trusted_persons_json", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<TrustedPerson>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    TrustedPerson(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        phoneNumber = obj.getString("phoneNumber"),
                        relationship = obj.optString("relationship", "Proche"),
                        canReceiveTheftAlerts = obj.optBoolean("canReceiveTheftAlerts", true),
                        addedTimestamp = obj.optLong("addedTimestamp", System.currentTimeMillis())
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveTrustedPersons(persons: List<TrustedPerson>) {
        val array = JSONArray()
        persons.forEach { p ->
            val obj = JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("phoneNumber", p.phoneNumber)
                put("relationship", p.relationship)
                put("canReceiveTheftAlerts", p.canReceiveTheftAlerts)
                put("addedTimestamp", p.addedTimestamp)
            }
            array.put(obj)
        }
        prefs.edit().putString("trusted_persons_json", array.toString()).apply()
        _trustedPersons.value = persons
    }

    fun addTrustedPerson(name: String, phoneNumber: String, relationship: String, pin: String): Boolean {
        if (!verifyPin(pin)) return false
        val newPerson = TrustedPerson(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            phoneNumber = phoneNumber.trim(),
            relationship = relationship.trim(),
            canReceiveTheftAlerts = true
        )
        val updated = _trustedPersons.value + newPerson
        saveTrustedPersons(updated)
        return true
    }

    fun removeTrustedPerson(id: String, pin: String): Boolean {
        if (!verifyPin(pin)) return false
        val updated = _trustedPersons.value.filter { it.id != id }
        saveTrustedPersons(updated)
        return true
    }

    fun registerDevice(deviceName: String, ownerEmail: String, pin: String, biometric: Boolean): Boolean {
        val enrollRes = vault.enrollMasterPin(pin)
        if (enrollRes !is OfflineSecurityVault.PinEnrollmentResult.Success) {
            _isPinConfigured.value = vault.isMasterPinConfigured()
            return false
        }

        val cleanName = deviceName.trim().ifBlank { getDeviceName(appContext) }
        val cleanEmail = ownerEmail.trim()
        prefs.edit()
            .putBoolean("is_registered", true)
            .putString("device_name", cleanName)
            .putString("owner_email", cleanEmail)
            .remove(KEY_LEGACY_PIN_HASH)
            .putBoolean("biometric_enabled", biometric)
            .apply()

        _isPinConfigured.value = true
        _deviceProfile.value = _deviceProfile.value.copy(
            isRegistered = true,
            deviceName = cleanName,
            ownerEmail = cleanEmail,
            masterPinHash = VAULT_PROTECTION_MARKER,
            biometricEnabled = biometric
        )
        return true
    }

    fun enrollMasterPin(pin: String): OfflineSecurityVault.PinEnrollmentResult {
        val res = vault.enrollMasterPin(pin)
        val configured = vault.isMasterPinConfigured()
        _isPinConfigured.value = configured
        if (res is OfflineSecurityVault.PinEnrollmentResult.Success) {
            prefs.edit().remove(KEY_LEGACY_PIN_HASH).apply()
            _deviceProfile.value = _deviceProfile.value.copy(
                masterPinHash = VAULT_PROTECTION_MARKER
            )
        }
        return res
    }

    fun changeMasterPin(currentPin: String, newPin: String): OfflineSecurityVault.PinEnrollmentResult {
        val res = vault.changeMasterPin(currentPin, newPin)
        val configured = vault.isMasterPinConfigured()
        _isPinConfigured.value = configured
        if (res is OfflineSecurityVault.PinEnrollmentResult.Success) {
            prefs.edit().remove(KEY_LEGACY_PIN_HASH).apply()
            _deviceProfile.value = _deviceProfile.value.copy(
                masterPinHash = VAULT_PROTECTION_MARKER
            )
        }
        return res
    }

    fun updateOwnerEmailIfBlank(email: String) {
        val clean = email.trim()
        if (clean.isBlank()) return
        if (_deviceProfile.value.ownerEmail.isBlank()) {
            prefs.edit().putString("owner_email", clean).apply()
            _deviceProfile.value = _deviceProfile.value.copy(ownerEmail = clean)
        }
    }

    fun refreshVaultStatus() {
        val snapshot = vault.currentHealthSnapshot()
        _isPinConfigured.value = snapshot.isPinConfigured
        _deviceProfile.value = _deviceProfile.value.copy(
            masterPinHash = if (snapshot.isPinConfigured) VAULT_PROTECTION_MARKER else ""
        )
    }

    fun verifyPin(pin: String): Boolean {
        return verifyPinDetailed(pin) is OfflineSecurityVault.PinVerificationResult.Verified
    }

    fun verifyPinDetailed(pin: String): OfflineSecurityVault.PinVerificationResult {
        val result = vault.verifyMasterPinDetailed(pin)
        _isPinConfigured.value = vault.isMasterPinConfigured()
        return result
    }

    fun setLanguage(lang: String) {
        val target = if (lang == "en") "en" else "fr"
        prefs.edit().putString("pref_language", target).apply()
        _currentLanguage.value = target
    }

    fun setLearningPaused(paused: Boolean) {
        prefs.edit().putBoolean(KEY_LEARNING_PAUSED, paused).apply()
        _isLearningPaused.value = paused
    }
}
