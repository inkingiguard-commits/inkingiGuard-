package com.example.domain.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/**
 * Abstraction for resolving the AES-256-GCM wrapping key.
 * Allows deterministic unit testing of Android Keystore failure paths without weakening production behavior.
 */
interface VaultKeyProvider {
    fun getOrCreateSecretKey(alias: String): SecretKey?
    val lastError: String?
}

/**
 * Hardware-backed Android Keystore key provider.
 * On real Android devices, only AndroidKeyStore is permitted and any Keystore failure fails closed.
 * Under Robolectric JVM unit tests (where AndroidKeyStore is absent from the host JDK), an ephemeral
 * in-memory AES-256 key is generated so AES/GCM/NoPadding authenticated encryption still runs instead
 * of plaintext/Base64 fallback.
 */
class AndroidKeyStoreKeyProvider : VaultKeyProvider {

    @Volatile
    override var lastError: String? = null
        private set

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private val robolectricKeys = ConcurrentHashMap<String, SecretKey>()

        private fun isRobolectricRuntime(): Boolean {
            if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) return true
            return try {
                Class.forName("org.robolectric.RuntimeEnvironment")
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    override fun getOrCreateSecretKey(alias: String): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (!keyStore.containsAlias(alias)) {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
                val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGenerator.init(keyGenParameterSpec)
                keyGenerator.generateKey()
            }
            val key = keyStore.getKey(alias, null) as? SecretKey
            if (key == null) {
                lastError = "AndroidKeyStore returned null key for alias $alias"
            } else {
                lastError = null
            }
            key
        } catch (e: Exception) {
            if (isRobolectricRuntime()) {
                lastError = null
                robolectricKeys.computeIfAbsent(alias) {
                    val keyGen = KeyGenerator.getInstance("AES")
                    keyGen.init(256, SecureRandom())
                    keyGen.generateKey()
                }
            } else {
                lastError = "${e::class.java.simpleName}: ${e.message ?: "AndroidKeyStore unavailable"}"
                null
            }
        }
    }
}

/**
 * Offline Security Vault.
 * Protects critical security states, recovery secrets, device identity, and salted PBKDF2 Master PIN
 * records using hardware-backed Android KeyStore (AES/GCM/NoPadding).
 *
 * Security invariants:
 * - Zero plaintext or Base64 fallback if Keystore fails (fails closed).
 * - Master PIN uses PBKDF2WithHmacSHA256 with a cryptographically random 128-bit per-enrollment salt
 *   and 120,000 iterations, verified in constant time via MessageDigest.isEqual.
 * - Never stores raw PINs, never initializes a default PIN such as "1234".
 */
class OfflineSecurityVault(
    context: Context,
    private val keyProvider: VaultKeyProvider = AndroidKeyStoreKeyProvider()
) {

    private val appContext: Context = context.applicationContext ?: context
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(VAULT_PREFS_NAME, Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()

    @Volatile
    private var inMemoryFailedAttempts: Int = 0

    @Volatile
    private var inMemoryLockoutUntilMillis: Long = 0L

    private val _vaultHealth = MutableStateFlow(buildHealthSnapshot())
    val vaultHealth: StateFlow<VaultHealthSnapshot> = _vaultHealth.asStateFlow()

    sealed class PinValidationResult {
        data object Valid : PinValidationResult()
        data class InvalidFormat(val reason: String) : PinValidationResult()
        data class WeakPin(val reason: String) : PinValidationResult()
    }

    sealed class PinEnrollmentResult {
        data class Success(val enrolledAtMillis: Long) : PinEnrollmentResult()
        data class Rejected(val reason: String) : PinEnrollmentResult()
        data class VaultUnavailable(val reason: String) : PinEnrollmentResult()
    }

    sealed class PinVerificationResult {
        data object Verified : PinVerificationResult()
        data object NotConfigured : PinVerificationResult()
        data class InvalidPin(val remainingAttempts: Int) : PinVerificationResult()
        data class LockedOut(val retryAfterMillis: Long) : PinVerificationResult()
        data class VaultUnavailable(val reason: String) : PinVerificationResult()
    }

    data class VaultHealthSnapshot(
        val isKeystoreHealthy: Boolean,
        val lastError: String?,
        val isPinConfigured: Boolean,
        val failedAttempts: Int,
        val lockoutRemainingMillis: Long
    )

    companion object {
        const val VAULT_PREFS_NAME = "inkingi_vault_encrypted"
        private const val KEY_ALIAS = "InkingiGuardMasterKey"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val GCM_TAG_LENGTH_BYTES = 16

        const val KEY_MASTER_PIN_RECORD = "inkingi_master_pin_pbkdf2_v2"
        private const val KEY_PIN_LOCKOUT_STATE = "inkingi_pin_lockout_state_v1"

        const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        const val PBKDF2_ITERATIONS = 120_000
        const val MIN_PBKDF2_ITERATIONS = 100_000
        const val SALT_LENGTH_BYTES = 16
        const val DERIVED_KEY_LENGTH_BITS = 256
        const val RECORD_VERSION_V2 = 2

        const val MIN_PIN_LENGTH = 4
        const val MAX_PIN_LENGTH = 8
        const val MAX_FAILED_ATTEMPTS = 5
        const val LOCKOUT_DURATION_MS = 30_000L

        private val WEAK_COMMON_PINS = setOf(
            "0000", "1111", "2222", "3333", "4444", "5555", "6666", "7777", "8888", "9999",
            "1234", "4321", "0123", "3210", "2345", "5432", "3456", "6543", "4567", "7654",
            "5678", "8765", "6789", "9876", "1212", "1122", "2580",
            "000000", "123456", "654321", "111111", "123123", "12345678"
        )

        fun validatePinStrength(pin: String): PinValidationResult {
            if (pin.length !in MIN_PIN_LENGTH..MAX_PIN_LENGTH || !pin.all { it in '0'..'9' }) {
                return PinValidationResult.InvalidFormat(
                    "Master PIN must contain $MIN_PIN_LENGTH to $MAX_PIN_LENGTH numeric digits."
                )
            }
            if (pin in WEAK_COMMON_PINS) {
                return PinValidationResult.WeakPin(
                    "Default, sequential, or repetitive PINs (such as 1234 or 0000) are not allowed."
                )
            }
            if (pin.toSet().size == 1) {
                return PinValidationResult.WeakPin(
                    "PIN cannot consist of a single repeated digit."
                )
            }
            if (isSequentialDigits(pin)) {
                return PinValidationResult.WeakPin(
                    "Sequential PIN digits are not allowed."
                )
            }
            return PinValidationResult.Valid
        }

        private fun isSequentialDigits(pin: String): Boolean {
            if (pin.length < 4) return false
            var ascending = true
            var descending = true
            for (i in 1 until pin.length) {
                val diff = pin[i] - pin[i - 1]
                if (diff != 1) ascending = false
                if (diff != -1) descending = false
            }
            return ascending || descending
        }
    }

    @Volatile
    private var lastCryptoError: String? = null

    private fun getSecretKey(): SecretKey? {
        val key = try {
            keyProvider.getOrCreateSecretKey(KEY_ALIAS)
        } catch (e: Exception) {
            lastCryptoError = "${e::class.java.simpleName}: ${e.message ?: "Key provider error"}"
            null
        }
        if (key == null) {
            lastCryptoError = keyProvider.lastError ?: lastCryptoError ?: "Hardware Keystore key unavailable."
        }
        return key
    }

    fun encryptAndStore(key: String, value: String): Boolean {
        val encoded = encryptValue(value) ?: return false
        return try {
            prefs.edit().putString(key, encoded).commit()
        } catch (e: Exception) {
            lastCryptoError = "Vault write failed: ${e.message ?: e::class.java.simpleName}"
            false
        }
    }

    fun retrieveAndDecrypt(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return decryptValue(stored)
    }

    fun remove(key: String): Boolean {
        return prefs.edit().remove(key).commit()
    }

    fun contains(key: String): Boolean = prefs.contains(key)

    /**
     * Encrypts [value] using AES/GCM/NoPadding with the Android Keystore key.
     * Fails closed (returns null) if Keystore is unavailable or encryption fails.
     * NEVER falls back to unencrypted Base64 or plaintext.
     */
    fun encryptValue(value: String): String? {
        return try {
            val secretKey = getSecretKey() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            if (iv == null || iv.size != GCM_IV_LENGTH) {
                lastCryptoError = "Invalid GCM IV length generated."
                return null
            }
            val encryptedBytes = cipher.doFinal(value.toByteArray(Charsets.UTF_8))

            val combined = ByteArray(iv.size + encryptedBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)

            lastCryptoError = null
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            lastCryptoError = "${e::class.java.simpleName}: ${e.message ?: "AES-GCM encryption failed"}"
            null
        }
    }

    /**
     * Decrypts a Base64(IV || ciphertext+GCM tag) payload produced by [encryptValue].
     * Fails closed (returns null) if Keystore is unavailable, ciphertext was tampered with,
     * or payload is plaintext/Base64 rather than authentic AES-GCM ciphertext.
     */
    fun decryptValue(encodedPayload: String): String? {
        if (encodedPayload.isBlank()) return null
        return try {
            val secretKey = getSecretKey() ?: return null
            val combined = Base64.decode(encodedPayload, Base64.NO_WRAP)
            if (combined.size <= GCM_IV_LENGTH + GCM_TAG_LENGTH_BYTES) {
                lastCryptoError = "Encrypted vault payload truncated or unauthenticated."
                return null
            }

            val iv = ByteArray(GCM_IV_LENGTH)
            val encryptedBytes = ByteArray(combined.size - GCM_IV_LENGTH)
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH)
            System.arraycopy(combined, GCM_IV_LENGTH, encryptedBytes, 0, encryptedBytes.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val plaintext = String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
            lastCryptoError = null
            plaintext
        } catch (e: Exception) {
            lastCryptoError = "${e::class.java.simpleName}: ${e.message ?: "AES-GCM decryption failed"}"
            null
        }
    }

    fun hasStoredPinRecord(): Boolean = prefs.contains(KEY_MASTER_PIN_RECORD)

    fun isMasterPinConfigured(): Boolean {
        if (!prefs.contains(KEY_MASTER_PIN_RECORD)) return false
        return readMasterPinRecord() != null
    }

    /**
     * Enrolls or replaces the Master PIN using salted PBKDF2WithHmacSHA256 (120,000 iterations,
     * 16-byte SecureRandom salt) stored AES-GCM encrypted inside the OfflineSecurityVault.
     * Rejects default/weak PINs (including "1234") and fails closed if Keystore is unavailable.
     */
    @Synchronized
    fun enrollMasterPin(pin: String, nowMillis: Long = System.currentTimeMillis()): PinEnrollmentResult {
        when (val validation = validatePinStrength(pin)) {
            is PinValidationResult.InvalidFormat -> return PinEnrollmentResult.Rejected(validation.reason)
            is PinValidationResult.WeakPin -> return PinEnrollmentResult.Rejected(validation.reason)
            is PinValidationResult.Valid -> Unit
        }

        val salt = ByteArray(SALT_LENGTH_BYTES)
        secureRandom.nextBytes(salt)

        val derivedHash = derivePbkdf2Hash(pin, salt, PBKDF2_ITERATIONS)
            ?: return PinEnrollmentResult.VaultUnavailable("PBKDF2 key derivation failed on this device.")

        val recordJson = try {
            JSONObject().apply {
                put("version", RECORD_VERSION_V2)
                put("algorithm", PBKDF2_ALGORITHM)
                put("iterations", PBKDF2_ITERATIONS)
                put("saltBase64", Base64.encodeToString(salt, Base64.NO_WRAP))
                put("hashBase64", Base64.encodeToString(derivedHash, Base64.NO_WRAP))
                put("enrolledAt", nowMillis)
            }.toString()
        } finally {
            Arrays.fill(derivedHash, 0.toByte())
            Arrays.fill(salt, 0.toByte())
        }

        val stored = encryptAndStore(KEY_MASTER_PIN_RECORD, recordJson)
        if (!stored) {
            val err = lastCryptoError ?: keyProvider.lastError ?: "Android Keystore unavailable."
            return PinEnrollmentResult.VaultUnavailable(
                "Hardware security vault unavailable; Master PIN was not stored ($err)."
            )
        }

        resetLockoutState()
        refreshHealthSnapshot()
        return PinEnrollmentResult.Success(nowMillis)
    }

    @Synchronized
    fun changeMasterPin(
        currentPin: String,
        newPin: String,
        nowMillis: Long = System.currentTimeMillis()
    ): PinEnrollmentResult {
        return when (val verifyRes = verifyMasterPinDetailed(currentPin, nowMillis)) {
            is PinVerificationResult.Verified -> enrollMasterPin(newPin, nowMillis)
            is PinVerificationResult.NotConfigured -> enrollMasterPin(newPin, nowMillis)
            is PinVerificationResult.InvalidPin ->
                PinEnrollmentResult.Rejected("Current PIN is incorrect (${verifyRes.remainingAttempts} attempts remaining).")
            is PinVerificationResult.LockedOut ->
                PinEnrollmentResult.Rejected("PIN verification locked for ${(verifyRes.retryAfterMillis + 999L) / 1000L}s.")
            is PinVerificationResult.VaultUnavailable ->
                PinEnrollmentResult.VaultUnavailable(verifyRes.reason)
        }
    }

    /**
     * Verifies [pin] against the AES-GCM-encrypted PBKDF2WithHmacSHA256 vault record in constant time.
     * Strictly fails closed when no PIN is enrolled, Keystore fails, ciphertext is tampered with,
     * or brute-force lockout is active.
     */
    @Synchronized
    fun verifyMasterPinDetailed(pin: String, nowMillis: Long = System.currentTimeMillis()): PinVerificationResult {
        val lockoutState = readLockoutState(nowMillis)
        if (lockoutState.lockoutUntilMillis > nowMillis) {
            val remainingMs = lockoutState.lockoutUntilMillis - nowMillis
            refreshHealthSnapshot(nowMillis)
            return PinVerificationResult.LockedOut(remainingMs)
        }

        if (!prefs.contains(KEY_MASTER_PIN_RECORD)) {
            refreshHealthSnapshot(nowMillis)
            return PinVerificationResult.NotConfigured
        }

        val record = readMasterPinRecord()
        if (record == null) {
            val reason = lastCryptoError ?: keyProvider.lastError ?: "Vault record unreadable or Keystore failed."
            refreshHealthSnapshot(nowMillis)
            return PinVerificationResult.VaultUnavailable(reason)
        }

        if (pin.isEmpty() || !pin.all { it in '0'..'9' }) {
            return recordFailedAttempt(lockoutState.failedAttempts, nowMillis)
        }

        val candidateHash = derivePbkdf2Hash(pin, record.salt, record.iterations)
        if (candidateHash == null) {
            Arrays.fill(record.salt, 0.toByte())
            Arrays.fill(record.hash, 0.toByte())
            return PinVerificationResult.VaultUnavailable("PBKDF2 verification unavailable.")
        }

        val isMatch = try {
            MessageDigest.isEqual(candidateHash, record.hash)
        } finally {
            Arrays.fill(candidateHash, 0.toByte())
            Arrays.fill(record.salt, 0.toByte())
            Arrays.fill(record.hash, 0.toByte())
        }

        return if (isMatch) {
            resetLockoutState()
            refreshHealthSnapshot(nowMillis)
            PinVerificationResult.Verified
        } else {
            recordFailedAttempt(lockoutState.failedAttempts, nowMillis)
        }
    }

    fun verifyMasterPin(pin: String): Boolean {
        return verifyMasterPinDetailed(pin) is PinVerificationResult.Verified
    }

    @Synchronized
    fun clearMasterPin() {
        prefs.edit()
            .remove(KEY_MASTER_PIN_RECORD)
            .remove(KEY_PIN_LOCKOUT_STATE)
            .commit()
        inMemoryFailedAttempts = 0
        inMemoryLockoutUntilMillis = 0L
        refreshHealthSnapshot()
    }

    fun currentHealthSnapshot(nowMillis: Long = System.currentTimeMillis()): VaultHealthSnapshot {
        val snapshot = buildHealthSnapshot(nowMillis)
        _vaultHealth.value = snapshot
        return snapshot
    }

    private fun refreshHealthSnapshot(nowMillis: Long = System.currentTimeMillis()) {
        _vaultHealth.value = buildHealthSnapshot(nowMillis)
    }

    private fun buildHealthSnapshot(nowMillis: Long = System.currentTimeMillis()): VaultHealthSnapshot {
        val lockout = peekLockoutState(nowMillis)
        val hasRecord = prefs.contains(KEY_MASTER_PIN_RECORD)
        val recordValid = if (hasRecord) readMasterPinRecord() != null else false
        val keyAvailable = getSecretKey() != null
        val healthy = keyAvailable && lastCryptoError == null && (!hasRecord || recordValid)
        return VaultHealthSnapshot(
            isKeystoreHealthy = healthy,
            lastError = if (healthy) null else (lastCryptoError ?: keyProvider.lastError),
            isPinConfigured = recordValid,
            failedAttempts = lockout.failedAttempts,
            lockoutRemainingMillis = (lockout.lockoutUntilMillis - nowMillis).coerceAtLeast(0L)
        )
    }

    private data class PinRecord(
        val iterations: Int,
        val salt: ByteArray,
        val hash: ByteArray
    )

    private fun readMasterPinRecord(): PinRecord? {
        val decrypted = retrieveAndDecrypt(KEY_MASTER_PIN_RECORD) ?: return null
        return try {
            val obj = JSONObject(decrypted)
            val version = obj.optInt("version", -1)
            val algorithm = obj.optString("algorithm", "")
            val iterations = obj.optInt("iterations", 0)
            val saltB64 = obj.optString("saltBase64", "")
            val hashB64 = obj.optString("hashBase64", "")
            if (version != RECORD_VERSION_V2 ||
                algorithm != PBKDF2_ALGORITHM ||
                iterations < MIN_PBKDF2_ITERATIONS ||
                saltB64.isBlank() ||
                hashB64.isBlank()
            ) {
                lastCryptoError = "Invalid PIN vault record metadata."
                return null
            }
            val salt = Base64.decode(saltB64, Base64.NO_WRAP)
            val hash = Base64.decode(hashB64, Base64.NO_WRAP)
            if (salt.size < SALT_LENGTH_BYTES || hash.size < 32) {
                lastCryptoError = "Corrupted PIN vault cryptographic material."
                return null
            }
            PinRecord(iterations = iterations, salt = salt, hash = hash)
        } catch (e: Exception) {
            lastCryptoError = "Corrupted PIN vault JSON: ${e::class.java.simpleName}"
            null
        }
    }

    private fun derivePbkdf2Hash(pin: String, salt: ByteArray, iterations: Int): ByteArray? {
        val chars = pin.toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, DERIVED_KEY_LENGTH_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
            factory.generateSecret(spec).encoded
        } catch (e: Exception) {
            lastCryptoError = "PBKDF2 derivation failed: ${e::class.java.simpleName}"
            null
        } finally {
            spec.clearPassword()
            Arrays.fill(chars, '\u0000')
        }
    }

    private data class LockoutState(
        val failedAttempts: Int,
        val lockoutUntilMillis: Long
    )

    private fun peekLockoutState(nowMillis: Long): LockoutState {
        val persistedJson = retrieveAndDecrypt(KEY_PIN_LOCKOUT_STATE)
        val state = if (!persistedJson.isNullOrBlank()) {
            try {
                val obj = JSONObject(persistedJson)
                val persistedFailures = obj.optInt("failedAttempts", 0).coerceAtLeast(0)
                val persistedUntil = obj.optLong("lockoutUntilMillis", 0L).coerceAtLeast(0L)
                LockoutState(
                    failedAttempts = maxOf(persistedFailures, inMemoryFailedAttempts),
                    lockoutUntilMillis = maxOf(persistedUntil, inMemoryLockoutUntilMillis)
                )
            } catch (_: Exception) {
                LockoutState(inMemoryFailedAttempts, inMemoryLockoutUntilMillis)
            }
        } else {
            LockoutState(inMemoryFailedAttempts, inMemoryLockoutUntilMillis)
        }
        return if (state.lockoutUntilMillis in 1..nowMillis) {
            LockoutState(0, 0L)
        } else {
            state
        }
    }

    private fun readLockoutState(nowMillis: Long): LockoutState {
        val state = peekLockoutState(nowMillis)
        if (state.failedAttempts == 0 && state.lockoutUntilMillis == 0L &&
            (inMemoryFailedAttempts > 0 || prefs.contains(KEY_PIN_LOCKOUT_STATE))
        ) {
            resetLockoutState()
        }
        return state
    }

    private fun recordFailedAttempt(previousFailures: Int, nowMillis: Long): PinVerificationResult {
        val nextFailures = previousFailures + 1
        val lockoutUntil = if (nextFailures >= MAX_FAILED_ATTEMPTS) {
            nowMillis + LOCKOUT_DURATION_MS
        } else {
            0L
        }
        inMemoryFailedAttempts = nextFailures
        inMemoryLockoutUntilMillis = lockoutUntil

        val stateJson = JSONObject().apply {
            put("failedAttempts", nextFailures)
            put("lockoutUntilMillis", lockoutUntil)
        }.toString()
        encryptAndStore(KEY_PIN_LOCKOUT_STATE, stateJson)
        refreshHealthSnapshot(nowMillis)

        return if (lockoutUntil > nowMillis) {
            PinVerificationResult.LockedOut(lockoutUntil - nowMillis)
        } else {
            PinVerificationResult.InvalidPin((MAX_FAILED_ATTEMPTS - nextFailures).coerceAtLeast(0))
        }
    }

    private fun resetLockoutState() {
        inMemoryFailedAttempts = 0
        inMemoryLockoutUntilMillis = 0L
        prefs.edit().remove(KEY_PIN_LOCKOUT_STATE).commit()
    }
}
