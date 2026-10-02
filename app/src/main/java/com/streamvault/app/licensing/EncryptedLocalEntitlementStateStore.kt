package com.MegaStream.app.licensing

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.MegaStream.data.licensing.LocalEntitlementSnapshot
import com.MegaStream.data.licensing.LocalEntitlementStateStore
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Deliberately contains neither a provider cause nor any stored values. */
typealias LocalEntitlementStoreException = com.MegaStream.data.licensing.LocalEntitlementStoreException

/**
 * Blocking, single-process storage: use an IO dispatcher. Calls across instances are serialized.
 * All entitlement read/validate/write transactions sharing backing storage MUST share one store
 * instance: the entitlement layer locks that instance for the entire transaction, not just each call.
 * Authenticated local storage cannot prevent malicious full-backup rollback without hardware
 * monotonic storage. If committing a tombstone fails, only this process's latch is guaranteed;
 * restart safety requires that the write-ahead tombstone reached durable storage.
 * Do not treat this store as server authentication or put credentials/raw JWTs here.
 */
class EncryptedLocalEntitlementStateStore internal constructor(
    private val persistence: Persistence,
    private val crypto: Crypto,
) : LocalEntitlementStateStore {
    constructor(context: Context) : this(AndroidPersistence(context), GcmCrypto(AndroidKeys()))

    override fun read(): LocalEntitlementSnapshot = synchronized(lock) {
        try {
            check(persistence.scope !in failedScopes)
            val record = persistence.read()
            check(!record.blocked)
            val ciphertext = record.ciphertext
            if (ciphertext == null) {
                // Preferences disappearing while a key survives is not first use.
                check(!crypto.hasKey())
                LocalEntitlementSnapshot()
            } else {
                val clear = crypto.decrypt(ciphertext)
                try { LocalEntitlementSnapshotCodec.decode(clear) } finally { clear.fill(0) }
            }
        } catch (_: Exception) {
            // This trust boundary replaces provider/storage/format failures, never with a default
            // snapshot. Provider exception messages and causes may contain sensitive values.
            invalidate()
            throw LocalEntitlementStoreException()
        }
    }

    override fun write(snapshot: LocalEntitlementSnapshot): Unit = synchronized(lock) {
        try {
            failedScopes.add(persistence.scope)
            // Always use write-ahead invalidation, even on first write. If payload commit fails,
            // a restarted process sees a durable tombstone rather than an older permissive lease.
            check(persistence.write(Record(null, true)))
            val clear = LocalEntitlementSnapshotCodec.encode(snapshot)
            val ciphertext = try { crypto.encrypt(clear) } finally { clear.fill(0) }
            check(persistence.write(Record(ciphertext, false)))
            failedScopes.remove(persistence.scope)
        } catch (_: Exception) {
            // This trust boundary replaces provider/storage/format failures, never with a default
            // snapshot. Provider exception messages and causes may contain sensitive values.
            invalidate()
            throw LocalEntitlementStoreException()
        }
        Unit
    }

    private fun invalidate() {
        // A failed commit may still mutate SharedPreferences' in-memory map. Only a successful
        // explicit write clears this process-wide latch; an ordinary read cannot recover it.
        try {
            failedScopes.add(persistence.scope)
            persistence.write(Record(null, true))
        } catch (_: Exception) { /* latch remains if scope was available */ }
    }

    internal class Record(val ciphertext: String?, val blocked: Boolean)
    internal interface Persistence {
        /** Stable backing-file identity, shared by every wrapper of the same preferences. */
        val scope: String
        fun read(): Record
        /** Atomically replace payload and marker, synchronously reporting durable commit. */
        fun write(record: Record): Boolean
    }
    internal interface Crypto {
        fun hasKey(): Boolean
        fun encrypt(clear: ByteArray): String
        fun decrypt(ciphertext: String): ByteArray
    }
    internal interface Keys {
        /** Lookup only. MUST NOT create or replace a key. */
        fun existing(): SecretKey?
        /** Called only by an explicit write. */
        fun forEncryption(): SecretKey
    }

    /** JCE framing is shared unchanged by Android and JVM tests; only key access is injected. */
    internal class GcmCrypto(private val keys: Keys) : Crypto {
        override fun hasKey(): Boolean = keys.existing() != null
        override fun encrypt(clear: ByteArray): String {
            require(clear.size <= LocalEntitlementSnapshotCodec.MAX_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keys.forEncryption())
            cipher.updateAAD(AAD)
            require(cipher.iv.size == IV_BYTES)
            return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(clear))
        }
        override fun decrypt(ciphertext: String): ByteArray {
            // Bound BEFORE Base64 decoder allocation, and reject noncanonical padding/whitespace.
            require(ciphertext.length in MIN_BASE64..MAX_BASE64)
            val bytes = Base64.getDecoder().decode(ciphertext)
            require(bytes.size in MIN_CIPHERTEXT..MAX_CIPHERTEXT)
            require(Base64.getEncoder().encodeToString(bytes) == ciphertext)
            val key = requireNotNull(keys.existing())
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            cipher.updateAAD(AAD)
            return cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
        }
        companion object {
            private const val TRANSFORMATION = "AES/GCM/NoPadding"
            private const val IV_BYTES = 12
            private const val TAG_BITS = 128
            private const val MIN_CIPHERTEXT = IV_BYTES + TAG_BITS / 8
            internal const val MAX_CIPHERTEXT = LocalEntitlementSnapshotCodec.MAX_BYTES + MIN_CIPHERTEXT
            private const val MIN_BASE64 = ((MIN_CIPHERTEXT + 2) / 3) * 4
            internal const val MAX_BASE64 = ((MAX_CIPHERTEXT + 2) / 3) * 4
            private val AAD = "MegaStream local entitlement snapshot v1".toByteArray(Charsets.UTF_8)
        }
    }

    private class AndroidPersistence(context: Context) : Persistence {
        private val app = context.applicationContext ?: context
        override val scope: String = app.applicationInfo.dataDir + "/" + PREFERENCES
        private val preferences by lazy { app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }
        override fun read(): Record = recordFromPreferences(preferences.all)
        override fun write(record: Record): Boolean = preferences.edit()
            .clear() // Dedicated namespace: explicit recovery also removes unrecognized old shapes.
            .putString(PAYLOAD, record.ciphertext)
            .putBoolean(BLOCKED, record.blocked)
            .commit()
    }

    private class AndroidKeys : Keys {
        private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        override fun existing(): SecretKey? = store().getKey(KEY_ALIAS, null) as SecretKey?
        override fun forEncryption(): SecretKey = existing() ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore",
        ).run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }

    internal companion object {
        /** Interpret the actual preference shape without relying on coercing getter defaults. */
        internal fun recordFromPreferences(entries: Map<String, *>): Record {
            if (entries.isEmpty()) return Record(null, false)
            require(entries.keys.all { it == PAYLOAD || it == BLOCKED })
            val blocked = entries[BLOCKED] as? Boolean ?: error("Invalid storage shape")
            if (blocked) {
                require(entries.keys == setOf(BLOCKED))
                return Record(null, true)
            }
            require(entries.keys == setOf(PAYLOAD, BLOCKED))
            val ciphertext = entries[PAYLOAD] as? String ?: error("Invalid storage shape")
            return Record(ciphertext, false)
        }

        private val lock = Any()
        private val failedScopes = mutableSetOf<String>()
        private const val PREFERENCES = "local_entitlement_state_v1"
        private const val PAYLOAD = "encrypted_snapshot"
        private const val BLOCKED = "blocked"
        private const val KEY_ALIAS = "com.MegaStream.app.local_entitlement_state.v1"
    }
}
