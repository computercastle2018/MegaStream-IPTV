package com.MegaStream.app.controlplane

import java.security.GeneralSecurityException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.*
import org.junit.Test

class InstallationCredentialStoreTest {
    private class MemoryPersistence : InstallationCredentialStore.Persistence {
        override val scope = UUID.randomUUID().toString()
        var record = InstallationCredentialStore.Record(null, false)
        var commitSucceeds = true
        override fun read() = record
        override fun write(record: InstallationCredentialStore.Record): Boolean {
            this.record = record // Model SharedPreferences updating memory even if disk commit fails.
            return commitSucceeds
        }
    }

    /** Real JCE authenticated encryption; only the Android key provider is substituted. */
    private class TestCrypto : InstallationCredentialStore.Crypto {
        var key: SecretKey? = null
        override fun encrypt(cleartext: String): String {
            if (key == null) key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(cleartext.toByteArray()))
        }
        override fun decrypt(ciphertext: String): String {
            val existing = key ?: throw GeneralSecurityException("Missing key")
            val bytes = Base64.getDecoder().decode(ciphertext)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, existing, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            return cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8)
        }
        override fun deleteKey() { key = null }
    }

    @Test fun `new installation stores encrypted values and reloads without generating`() {
        val disk = MemoryPersistence()
        val crypto = TestCrypto()
        val first = ready(InstallationCredentialStore(disk, crypto).loadOrCreate())
        assertFalse(disk.record.ciphertext!!.contains(first.installationId))
        assertFalse(disk.record.ciphertext!!.contains(first.credential))
        val next = ready(InstallationCredentialStore(disk, crypto,
            InstallationCredentialGenerator { error("Must not generate existing identity") }).loadOrCreate())
        assertEquals(first.installationId, next.installationId)
        assertEquals(first.credential, next.credential)
    }

    @Test fun `missing key requires explicit reset and remains blocked after key recovery`() {
        val disk = MemoryPersistence()
        val crypto = TestCrypto()
        val store = InstallationCredentialStore(disk, crypto)
        val old = ready(store.loadOrCreate())
        val oldKey = crypto.key
        val oldRecord = disk.record
        crypto.key = null
        assertSame(InstallationCredentialStore.State.RequiresReregistration, store.loadOrCreate())
        assertTrue(disk.record.blocked)
        assertNull(disk.record.ciphertext)
        crypto.key = oldKey
        disk.record = oldRecord
        assertSame(InstallationCredentialStore.State.RequiresReregistration,
            InstallationCredentialStore(disk, crypto).loadOrCreate())
        val fresh = ready(store.resetForReregistration())
        assertNotEquals(old.installationId, fresh.installationId)
        assertNotEquals(old.credential, fresh.credential)
        assertEquals(fresh.credential, ready(store.loadOrCreate()).credential)
    }

    @Test fun `tampered ciphertext is detected without caching and marked persistently`() {
        val disk = MemoryPersistence()
        val crypto = TestCrypto()
        val store = InstallationCredentialStore(disk, crypto)
        ready(store.loadOrCreate())
        val bytes = Base64.getDecoder().decode(disk.record.ciphertext)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        disk.record = disk.record.copy(ciphertext = Base64.getEncoder().encodeToString(bytes))
        assertSame(InstallationCredentialStore.State.RequiresReregistration, store.loadOrCreate())
        assertTrue(disk.record.blocked)
        // A new scope simulates a fresh process: the durable marker alone still blocks loading.
        val restarted = MemoryPersistence().apply { record = disk.record }
        assertSame(InstallationCredentialStore.State.RequiresReregistration,
            InstallationCredentialStore(restarted, crypto).loadOrCreate())
    }

    @Test fun `failed credential commit cannot produce ready from mutated memory`() {
        val disk = MemoryPersistence().apply { commitSucceeds = false }
        val crypto = TestCrypto()
        assertSame(InstallationCredentialStore.State.Unavailable,
            InstallationCredentialStore(disk, crypto).loadOrCreate())
        assertNotNull(disk.record.ciphertext)
        disk.commitSucceeds = true
        val other = InstallationCredentialStore(disk, crypto)
        assertSame(InstallationCredentialStore.State.Unavailable, other.loadOrCreate())
        ready(other.resetForReregistration())
    }

    @Test fun `failed tombstone commit preserves key but never returns the old credential`() {
        val disk = MemoryPersistence()
        val crypto = TestCrypto()
        val store = InstallationCredentialStore(disk, crypto)
        ready(store.loadOrCreate())
        val oldKey = crypto.key
        disk.commitSucceeds = false
        assertSame(InstallationCredentialStore.State.Unavailable, store.resetForReregistration())
        assertSame(oldKey, crypto.key)
        assertSame(InstallationCredentialStore.State.Unavailable,
            InstallationCredentialStore(disk, crypto).loadOrCreate())
    }

    @Test fun `corrupt marker commit failure still blocks another instance`() {
        val disk = MemoryPersistence()
        val crypto = TestCrypto()
        ready(InstallationCredentialStore(disk, crypto).loadOrCreate())
        crypto.key = null
        disk.commitSucceeds = false
        assertSame(InstallationCredentialStore.State.RequiresReregistration,
            InstallationCredentialStore(disk, crypto).loadOrCreate())
        assertSame(InstallationCredentialStore.State.RequiresReregistration,
            InstallationCredentialStore(disk, crypto).loadOrCreate())
    }

    @Test fun `concurrent instances create one identity and all return the same credential`() {
        val disk = MemoryPersistence()
        val crypto = TestCrypto()
        val generations = AtomicInteger()
        val generator = InstallationCredentialGenerator {
            generations.incrementAndGet()
            SecureInstallationCredentialGenerator().generate()
        }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..8).map {
                executor.submit<InstallationCredentialStore.State> {
                    check(start.await(5, TimeUnit.SECONDS))
                    InstallationCredentialStore(disk, crypto, generator).loadOrCreate()
                }
            }
            start.countDown()
            val credentials = futures.map { ready(it.get(10, TimeUnit.SECONDS)) }
            assertEquals(1, generations.get())
            assertEquals(1, credentials.map { it.installationId }.distinct().size)
            assertEquals(1, credentials.map { it.credential }.distinct().size)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun ready(state: InstallationCredentialStore.State): InstallationCredentials {
        assertTrue("Expected Ready, got $state", state is InstallationCredentialStore.State.Ready)
        return (state as InstallationCredentialStore.State.Ready).credentials
    }
}
