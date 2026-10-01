package com.MegaStream.app.controlplane

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Single-process, cross-instance serialized storage. No decrypted credentials are cached. */
class InstallationCredentialStore internal constructor(
    private val persistence: Persistence,
    private val crypto: Crypto,
    private val generator: InstallationCredentialGenerator = SecureInstallationCredentialGenerator(),
) {
    constructor(
        context: Context,
        generator: InstallationCredentialGenerator = SecureInstallationCredentialGenerator(),
    ) : this(AndroidPersistence(context), AndroidCrypto(), generator)

    sealed class State {
        class Ready(val credentials: InstallationCredentials) : State() {
            override fun toString(): String = "Ready($credentials)"
        }
        data object RequiresReregistration : State()
        data object Unavailable : State()
    }

    /** Blocking disk/Keystore operation: call on an IO dispatcher, not the UI thread.
     * Expected provider/storage failures become fixed states; programming bugs still propagate.
     */
    fun loadOrCreate(): State = synchronized(lock) {
        try {
            val scope = persistence.scope
            failures[scope]?.let { return@synchronized it }
            val record = try {
                persistence.read()
            } catch (failure: Exception) {
                rethrowUnexpected(failure)
                return@synchronized invalidate(scope)
            }
            if (record.blocked) return@synchronized invalidate(scope)
            if (record.ciphertext == null) return@synchronized create(scope)
            try {
                val clear = crypto.decrypt(record.ciphertext)
                val parts = clear.split('\n')
                require(parts.size == 2)
                State.Ready(InstallationCredentials(parts[0], parts[1]))
            } catch (failure: Exception) {
                rethrowUnexpected(failure)
                invalidate(scope)
            }
        } catch (failure: Exception) {
            rethrowUnexpected(failure)
            State.Unavailable
        }
    }

    /** The only operation allowed to replace a previously stored or failed identity.
     * Blocking disk/Keystore operation: call on an IO dispatcher, not the UI thread.
     */
    fun resetForReregistration(): State = synchronized(lock) {
        try {
            val scope = persistence.scope
            failures[scope] = State.Unavailable
            // A durable tombstone must precede key deletion and identity replacement.
            if (!persistence.write(Record(null, true))) return@synchronized State.Unavailable
            crypto.deleteKey()
            create(scope)
        } catch (failure: Exception) {
            rethrowUnexpected(failure)
            State.Unavailable
        }
    }

    private fun create(scope: String): State {
        // commit() can change the in-memory preferences even when it returns false.
        // Keep this process-wide latch until an explicit, successful reset/write.
        failures[scope] = State.Unavailable
        return try {
            val credentials = generator.generate()
            val ciphertext = crypto.encrypt("${credentials.installationId}\n${credentials.credential}")
            if (!persistence.write(Record(ciphertext, false))) return State.Unavailable
            failures.remove(scope)
            State.Ready(credentials)
        } catch (failure: Exception) {
            rethrowUnexpected(failure)
            State.Unavailable
        }
    }

    private fun invalidate(scope: String): State {
        failures[scope] = State.RequiresReregistration
        try {
            // Drop ciphertext as well: even a later recovered key cannot revive an old token.
            persistence.write(Record(null, true))
        } catch (failure: Exception) {
            rethrowUnexpected(failure)
            // The in-process latch still fails closed if a durable marker is unavailable.
        }
        return State.RequiresReregistration
    }

    internal data class Record(val ciphertext: String?, val blocked: Boolean)

    internal interface Persistence {
        val scope: String
        fun read(): Record
        fun write(record: Record): Boolean
    }

    internal interface Crypto {
        fun encrypt(cleartext: String): String
        fun decrypt(ciphertext: String): String
        fun deleteKey()
    }

    private class AndroidPersistence(context: Context) : Persistence {
        private val app = context.applicationContext ?: context
        override val scope: String get() = app.applicationInfo.dataDir + "/" + PREFERENCES
        private val preferences by lazy { app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }

        override fun read(): Record {
            val blocked = preferences.getBoolean(BLOCKED, false)
            val ciphertext = if (preferences.contains(PAYLOAD)) {
                requireNotNull(preferences.getString(PAYLOAD, null))
            } else null
            // A previous write always includes the marker: a missing payload is not first use.
            require(blocked || ciphertext != null || !preferences.contains(BLOCKED))
            return Record(ciphertext, blocked)
        }

        override fun write(record: Record): Boolean = preferences.edit()
            .putString(PAYLOAD, record.ciphertext)
            .putBoolean(BLOCKED, record.blocked)
            .commit()
    }

    private class AndroidCrypto : Crypto {
        private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        private fun existingKey(store: KeyStore): SecretKey? = store.getKey(KEY_ALIAS, null) as SecretKey?

        private fun encryptionKey(): SecretKey {
            val store = keyStore()
            existingKey(store)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
                generateKey()
            }
        }

        override fun encrypt(cleartext: String): String {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
            cipher.updateAAD(AAD)
            require(cipher.iv.size == 12)
            val clear = cleartext.toByteArray(Charsets.UTF_8)
            return try {
                Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(clear))
            } finally {
                clear.fill(0)
            }
        }

        override fun decrypt(ciphertext: String): String {
            // Never create keys on the read path, including restored preferences on a new device.
            val key = requireNotNull(existingKey(keyStore()))
            val bytes = Base64.getDecoder().decode(ciphertext)
            require(bytes.size >= 28)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(AAD)
            val clear = cipher.doFinal(bytes, 12, bytes.size - 12)
            return try {
                clear.toString(Charsets.UTF_8)
            } finally {
                clear.fill(0)
            }
        }

        override fun deleteKey() = keyStore().deleteEntry(KEY_ALIAS)
    }

    private companion object {
        private fun rethrowUnexpected(failure: Exception) {
            when (failure) {
                is GeneralSecurityException, is IOException, is IllegalArgumentException,
                is ClassCastException, is ProviderException, is SecurityException -> Unit
                else -> throw failure
            }
        }

        val lock = Any()
        val failures = mutableMapOf<String, State>()
        const val PREFERENCES = "installation_credentials_v1"
        const val PAYLOAD = "encrypted_credentials"
        const val BLOCKED = "requires_reregistration"
        const val KEY_ALIAS = "com.MegaStream.app.installation_credentials.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val AAD = "MegaStream installation credentials v1".toByteArray(Charsets.UTF_8)
    }
}
