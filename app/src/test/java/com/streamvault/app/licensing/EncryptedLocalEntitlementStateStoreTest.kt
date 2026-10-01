package com.MegaStream.app.licensing

import com.MegaStream.data.licensing.LocalEntitlementSnapshot
import com.MegaStream.domain.licensing.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
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

class EncryptedLocalEntitlementStateStoreTest {
    private class Keys : EncryptedLocalEntitlementStateStore.Keys {
        var key: SecretKey? = null
        var creations = 0
        override fun existing(): SecretKey? = key
        override fun forEncryption(): SecretKey = key ?: KeyGenerator.getInstance("AES").run {
            init(256); generateKey().also { key = it; creations++ }
        }
    }
    private class Disk(var record: EncryptedLocalEntitlementStateStore.Record = EncryptedLocalEntitlementStateStore.Record(null, false))
    private class Persistence(
        val disk: Disk = Disk(),
        override val scope: String = UUID.randomUUID().toString(),
    ) : EncryptedLocalEntitlementStateStore.Persistence {
        var memory = disk.record
        var attempts = 0
        var failAt: Set<Int> = emptySet()
        var throwRead = false
        var throwWrite = false
        val writes = mutableListOf<EncryptedLocalEntitlementStateStore.Record>()
        override fun read(): EncryptedLocalEntitlementStateStore.Record {
            if (throwRead) error(SECRET)
            return memory
        }
        override fun write(record: EncryptedLocalEntitlementStateStore.Record): Boolean {
            attempts++; writes.add(record)
            memory = record // SharedPreferences can mutate memory even when commit returns false.
            if (throwWrite) error(SECRET)
            if (attempts in failAt) return false
            disk.record = record
            return true
        }
    }
    private class Fixture(val persistence: Persistence = Persistence(), val keys: Keys = Keys()) {
        val crypto = EncryptedLocalEntitlementStateStore.GcmCrypto(keys)
        val store = EncryptedLocalEntitlementStateStore(persistence, crypto)
    }
    private fun failure(block: () -> Unit): LocalEntitlementStoreException {
        try { block(); fail("Expected sanitized store failure") } catch (error: LocalEntitlementStoreException) {
            assertEquals("Local entitlement storage unavailable", error.message)
            assertNull(error.cause)
            assertTrue(error.suppressed.isEmpty())
            assertFalse(error.toString().contains(SECRET))
            return error
        }
        throw AssertionError("unreachable")
    }
    private fun corrupt(bytes: ByteArray) {
        val f = Fixture()
        val ciphertext = f.crypto.encrypt(bytes)
        f.persistence.memory = EncryptedLocalEntitlementStateStore.Record(ciphertext, false)
        failure { f.store.read() }
        assertTrue(f.persistence.disk.record.blocked)
        assertNull(f.persistence.disk.record.ciphertext)
    }

    @Test fun pristineReadReturnsEmptyWithoutCreatingKeyOrWriting() {
        val f = Fixture()
        assertEquals(LocalEntitlementSnapshot(), f.store.read())
        assertEquals(0, f.keys.creations)
        assertEquals(0, f.persistence.attempts)
    }
    @Test fun allFieldsRoundTripThroughProductionGcmAndFreshInstance() {
        val f = Fixture()
        f.store.write(FULL)
        val fresh = EncryptedLocalEntitlementStateStore(Persistence(f.persistence.disk, f.persistence.scope), f.crypto)
        assertEquals(FULL, fresh.read())
        assertEquals(1, f.keys.creations)
        assertFalse(f.persistence.disk.record.ciphertext!!.contains(FULL.lease!!.credentialBinding))
    }
    @Test fun emptyExplicitSnapshotIsEncryptedNotTreatedAsPristine() {
        val f = Fixture(); f.store.write(LocalEntitlementSnapshot())
        assertNotNull(f.persistence.disk.record.ciphertext)
        assertEquals(LocalEntitlementSnapshot(), f.store.read())
        f.keys.key = null
        failure { f.store.read() }
        assertEquals(1, f.keys.creations)
    }
    @Test fun nullableNestedFieldsPreserveAbsentIdentityAndMetadata() {
        val f = Fixture()
        val value = LocalEntitlementSnapshot(onlineDenial = OnlineEntitlementDecision(LicenseAccessState.UNLICENSED, 0, null, null, null, null, null))
        f.store.write(value); assertEquals(value, f.store.read())
    }
    @Test fun everyLeaseEnumIsPreservedWithoutOrdinalCoupling() {
        for (state in LicenseAccessState.entries) {
            val snapshot = FULL.copy(lease = FULL.lease!!.copy(decision = state))
            val f = Fixture(); f.store.write(snapshot); assertEquals(snapshot, f.store.read())
        }
    }
    @Test fun everyMatchingDenialStateIsPreservedWithoutOrdinalCoupling() {
        for (state in LicenseAccessState.entries.filter { it != LicenseAccessState.ALLOWED }) {
            val snapshot = FULL.copy(authenticatedDenial = state, onlineDenial = FULL.onlineDenial!!.copy(state = state))
            val f = Fixture(); f.store.write(snapshot); assertEquals(snapshot, f.store.read())
        }
    }
    @Test fun allowedOrConflictingDenialStatesCannotBeWritten() {
        val invalid = listOf(
            LocalEntitlementSnapshot(authenticatedDenial = LicenseAccessState.ALLOWED),
            LocalEntitlementSnapshot(onlineDenial = FULL.onlineDenial!!.copy(state = LicenseAccessState.ALLOWED)),
            FULL.copy(authenticatedDenial = LicenseAccessState.REVOKED),
        )
        for (snapshot in invalid) {
            val f = Fixture(); failure { f.store.write(snapshot) }
            assertTrue(f.persistence.disk.record.blocked)
            assertNull(f.persistence.disk.record.ciphertext)
        }
    }
    @Test fun authenticatedPayloadWithAllowedOrConflictingDenialsFailsDecode() {
        val expired = LicenseAccessState.EXPIRED
        val authenticated = LocalEntitlementSnapshot(authenticatedDenial = expired)
        val online = LocalEntitlementSnapshot(onlineDenial = FULL.onlineDenial!!.copy(state = expired))
        val matching = online.copy(authenticatedDenial = expired)
        for ((snapshot, replacement) in listOf(
            authenticated to "ALLOWED", online to "ALLOWED", matching to "REVOKED",
        )) {
            val bytes = LocalEntitlementSnapshotCodec.encode(snapshot)
            val name = "EXPIRED".toByteArray(Charsets.UTF_8)
            val offset = (0..bytes.size - name.size).first { start ->
                name.indices.all { bytes[start + it] == name[it] }
            }
            replacement.toByteArray(Charsets.UTF_8).copyInto(bytes, offset)
            corrupt(bytes)
        }
    }
    @Test fun missingPreferencesWithSurvivingKeyFailsClosed() {
        val f = Fixture(); f.keys.forEncryption()
        failure { f.store.read() }
        assertTrue(f.persistence.disk.record.blocked)
        assertEquals(1, f.keys.creations)
    }
    @Test fun missingKeyNeverGeneratesReplacementAndDropsPayload() {
        val f = Fixture(); f.store.write(FULL); f.keys.key = null
        failure { f.store.read() }
        assertNull(f.persistence.disk.record.ciphertext)
        assertTrue(f.persistence.disk.record.blocked)
        assertEquals(1, f.keys.creations)
    }
    @Test fun recoveredKeyCannotResurrectTombstonedPayload() {
        val f = Fixture(); f.store.write(FULL); val oldKey = f.keys.key; f.keys.key = null
        failure { f.store.read() }; f.keys.key = oldKey
        val restart = Fixture(Persistence(f.persistence.disk), f.keys)
        failure { restart.store.read() }
    }
    @Test fun gcmRejectsTamperedIvCiphertextAndTag() {
        val source = Fixture(); source.store.write(FULL)
        val bytes = Base64.getDecoder().decode(source.persistence.disk.record.ciphertext)
        for (index in listOf(0, 12, bytes.lastIndex)) {
            val altered = bytes.copyOf().apply { this[index] = (this[index].toInt() xor 1).toByte() }
            val f = Fixture(Persistence(Disk(EncryptedLocalEntitlementStateStore.Record(Base64.getEncoder().encodeToString(altered), false))), source.keys)
            failure { f.store.read() }
            assertTrue(f.persistence.disk.record.blocked)
        }
    }
    @Test fun credentialStoreAadCannotAuthenticateEntitlementPayload() {
        val f = Fixture()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, f.keys.forEncryption())
        cipher.updateAAD("MegaStream installation credentials v1".toByteArray())
        val clear = LocalEntitlementSnapshotCodec.encode(FULL)
        val token = try { Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(clear)) } finally { clear.fill(0) }
        f.persistence.memory = EncryptedLocalEntitlementStateStore.Record(token, false)
        failure { f.store.read() }
    }
    @Test fun productionFrameCanBeDecryptedByIndependentJce() {
        val f = Fixture(); f.store.write(FULL)
        val bytes = Base64.getDecoder().decode(f.persistence.disk.record.ciphertext)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, f.keys.key, GCMParameterSpec(128, bytes, 0, 12))
        cipher.updateAAD("MegaStream local entitlement snapshot v1".toByteArray())
        val clear = cipher.doFinal(bytes, 12, bytes.size - 12)
        try { assertEquals(FULL, LocalEntitlementSnapshotCodec.decode(clear)) } finally { clear.fill(0) }
    }
    @Test fun malformedNoncanonicalAndOversizedBase64FailClosed() {
        val source = Fixture(); source.store.write(FULL)
        val valid = source.persistence.disk.record.ciphertext!!
        for (text in listOf("!", valid + "\n", "A".repeat(EncryptedLocalEntitlementStateStore.GcmCrypto.MAX_BASE64 + 1), "AAAA", valid.trimEnd('=') + "=".repeat(4))) {
            val f = Fixture(Persistence(Disk(EncryptedLocalEntitlementStateStore.Record(text, false))), source.keys)
            failure { f.store.read() }
        }
    }
    @Test fun ciphertextDecodedLengthIsBoundedEvenWithinBase64Limit() {
        val f = Fixture()
        val encoded = Base64.getEncoder().encodeToString(ByteArray(EncryptedLocalEntitlementStateStore.GcmCrypto.MAX_CIPHERTEXT + 1))
        f.persistence.memory = EncryptedLocalEntitlementStateStore.Record(encoded, false)
        failure { f.store.read() }
        assertEquals(0, f.keys.creations)
    }
    @Test fun unknownVersionAndTruncatedVersionFailClosed() {
        corrupt(byteArrayOf(0, 0, 0, 2))
        corrupt(byteArrayOf(0, 0, 0))
    }
    @Test fun trailingBytesAreNotIgnored() {
        corrupt(LocalEntitlementSnapshotCodec.encode(LocalEntitlementSnapshot()) + byteArrayOf(0))
    }
    @Test fun nonBooleanPresenceAndValueFlagsAreRejected() {
        val base = LocalEntitlementSnapshotCodec.encode(LocalEntitlementSnapshot())
        for (index in 4..10) corrupt(base.copyOf().apply { this[index] = 2 })
    }
    @Test fun invalidEnumNameIsRejected() {
        val bytes = LocalEntitlementSnapshotCodec.encode(LocalEntitlementSnapshot(authenticatedDenial = LicenseAccessState.EXPIRED))
        bytes[11] = 'X'.code.toByte() // first byte of EXPIRED, after version/presences/length
        corrupt(bytes)
    }
    @Test fun malformedUtf8CannotBeReplacementDecoded() {
        val bytes = LocalEntitlementSnapshotCodec.encode(LocalEntitlementSnapshot(anchor = TrustedTimeAnchor(1, 2, "boot")))
        bytes[26] = 0xC0.toByte(); bytes[27] = 0xAF.toByte()
        corrupt(bytes)
    }
    @Test fun oversizedAndNegativeStringLengthsAreRejectedBeforeAllocation() {
        val bytes = LocalEntitlementSnapshotCodec.encode(LocalEntitlementSnapshot(anchor = TrustedTimeAnchor(1, 2, "boot")))
        for (size in listOf(Int.MAX_VALUE, -1, 4097)) corrupt(bytes.copyOf().also { ByteBuffer.wrap(it).putInt(22, size) })
    }
    @Test fun noncanonicalUuidBindingTimestampRevisionAndIntervalCannotPersist() {
        val lease = FULL.lease!!
        val invalid = listOf(
            FULL.copy(lease = lease.copy(installationId = "1-1-1-1-1")),
            FULL.copy(lease = lease.copy(licenseId = lease.licenseId.uppercase())),
            FULL.copy(lease = lease.copy(tokenId = "bad")),
            FULL.copy(lease = lease.copy(credentialBinding = "A".repeat(42) + "B")),
            FULL.copy(lease = lease.copy(issuedAtEpochSeconds = -1)),
            FULL.copy(lease = lease.copy(licenseRevision = 0)),
            FULL.copy(lease = lease.copy(policyVersion = 2)),
            FULL.copy(lease = lease.copy(notBeforeEpochSeconds = lease.expiresAtEpochSeconds)),
            FULL.copy(anchor = FULL.anchor!!.copy(elapsedRealtimeMillis = -1)),
            FULL.copy(lastClockReading = FULL.lastClockReading!!.copy(wallEpochSeconds = -1)),
            FULL.copy(trustedTimeHighWaterEpochSeconds = -1),
            FULL.copy(onlineDenial = FULL.onlineDenial!!.copy(licenseRevision = -1)),
        )
        for (value in invalid) { val f = Fixture(); failure { f.store.write(value) }; assertTrue(f.persistence.disk.record.blocked) }
    }
    @Test fun hugeStringAndUnpairedSurrogateCannotReachEncryption() {
        for (issuer in listOf("a".repeat(65_537), "\uD800")) {
            val f = Fixture(); failure { f.store.write(FULL.copy(lease = FULL.lease!!.copy(issuer = issuer))) }
            assertEquals(0, f.keys.creations)
        }
    }
    @Test fun failedPayloadCommitLatchesAllInstancesDespiteMemoryMutation() {
        val f = Fixture(); f.persistence.failAt = setOf(2, 3)
        failure { f.store.write(FULL) }
        // Simulate the failed payload commit's visible in-memory value resurfacing.
        f.persistence.memory = f.persistence.writes[1]
        failure { EncryptedLocalEntitlementStateStore(f.persistence, f.crypto).read() }
        assertTrue(f.persistence.disk.record.blocked)
    }
    @Test fun failedReplacementCannotReviveOldDurableLeaseAfterRestart() {
        val f = Fixture()
        val grant = FULL.copy(authenticatedDenial = null, onlineDenial = null, verificationRequired = false)
        f.store.write(grant)
        f.persistence.failAt = setOf(4, 5)
        failure { f.store.write(FULL) }
        assertTrue(f.persistence.disk.record.blocked)
        assertNull(f.persistence.disk.record.ciphertext)
        // A fresh scope simulates a restarted process with no static latch but the same disk/key.
        failure { Fixture(Persistence(f.persistence.disk), f.keys).store.read() }
    }
    @Test fun failedWriteAheadMarkerNeverAttemptsNewPayloadAndLatches() {
        val f = Fixture(); f.store.write(FULL)
        val old = f.persistence.disk.record.ciphertext
        f.persistence.failAt = setOf(3, 4)
        failure { f.store.write(LocalEntitlementSnapshot()) }
        assertEquals(old, f.persistence.disk.record.ciphertext)
        assertTrue(f.persistence.writes.drop(2).all { it.blocked && it.ciphertext == null })
        failure { f.store.read() }
    }
    @Test fun successfulExplicitWriteRecoversLatchAndDurableMarker() {
        val f = Fixture(); f.persistence.failAt = setOf(2)
        failure { f.store.write(FULL) }
        f.persistence.failAt = emptySet()
        val recovered = FULL.copy(verificationRequired = false)
        EncryptedLocalEntitlementStateStore(f.persistence, f.crypto).write(recovered)
        assertEquals(recovered, f.store.read())
        assertFalse(f.persistence.disk.record.blocked)
    }
    @Test fun persistenceExceptionsExposeNoSecretsAndRemainLatched() {
        val f = Fixture(); f.persistence.throwRead = true; f.persistence.throwWrite = true
        val error = failure { f.store.read() }
        assertFalse(error.stackTraceToString().contains(SECRET))
        f.persistence.throwRead = false; f.persistence.throwWrite = false
        failure { f.store.read() }
    }
    @Test fun cryptoExceptionsExposeNoSecretsAndClearPlaintextBuffer() {
        val p = Persistence(); var captured: ByteArray? = null
        val crypto = object : EncryptedLocalEntitlementStateStore.Crypto {
            override fun hasKey() = false
            override fun encrypt(clear: ByteArray): String { captured = clear; error(SECRET) }
            override fun decrypt(ciphertext: String): ByteArray = error(SECRET)
        }
        failure { EncryptedLocalEntitlementStateStore(p, crypto).write(FULL) }
        assertTrue(captured!!.all { it == 0.toByte() })
        assertTrue(p.disk.record.blocked)
    }
    @Test fun decryptedPlaintextIsClearedOnBothSuccessAndDecodeFailure() {
        for (bytes in listOf(LocalEntitlementSnapshotCodec.encode(FULL), byteArrayOf(1, 2, 3))) {
            val p = Persistence(Disk(EncryptedLocalEntitlementStateStore.Record("fake", false)))
            val crypto = object : EncryptedLocalEntitlementStateStore.Crypto {
                override fun hasKey() = true
                override fun encrypt(clear: ByteArray): String = error("unused")
                override fun decrypt(ciphertext: String) = bytes
            }
            val store = EncryptedLocalEntitlementStateStore(p, crypto)
            if (bytes.size == 3) failure { store.read() } else assertEquals(FULL, store.read())
            assertTrue(bytes.all { it == 0.toByte() })
        }
    }
    @Test fun scopeLatchDoesNotPoisonIndependentStorage() {
        val bad = Fixture(); bad.persistence.throwRead = true; failure { bad.store.read() }
        val good = Fixture(); good.store.write(FULL); assertEquals(FULL, good.store.read())
    }
    @Test fun crossInstanceReadsAndWritesAreSerialized() {
        val p = Persistence(); val keys = Keys(); val crypto = EncryptedLocalEntitlementStateStore.GcmCrypto(keys)
        val active = AtomicInteger(); val maxActive = AtomicInteger()
        val boundary = object : EncryptedLocalEntitlementStateStore.Persistence {
            override val scope = p.scope
            private fun <T> access(block: () -> T): T {
                val count = active.incrementAndGet(); maxActive.accumulateAndGet(count, ::maxOf)
                return try { repeat(100) { Thread.yield() }; block() } finally { active.decrementAndGet() }
            }
            override fun read() = access { p.read() }
            override fun write(record: EncryptedLocalEntitlementStateStore.Record) = access { p.write(record) }
        }
        val first = EncryptedLocalEntitlementStateStore(boundary, crypto)
        val second = EncryptedLocalEntitlementStateStore(boundary, crypto)
        val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 4).map { index -> pool.submit {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                repeat(20) { val store = if (index % 2 == 0) first else second; store.write(FULL); assertEquals(FULL, store.read()) }
            } }
            start.countDown(); futures.forEach { it.get(15, TimeUnit.SECONDS) }
            assertEquals(1, maxActive.get())
            assertEquals(1, keys.creations)
        } finally { pool.shutdownNow() }
    }

    @Test fun zeroRevisionUnlicensedMetadataIsPreserved() {
        val f = Fixture()
        val snapshot = LocalEntitlementSnapshot(onlineDenial = OnlineEntitlementDecision(
            LicenseAccessState.UNLICENSED, 100, null, 0, null, null, null,
        ))
        f.store.write(snapshot)
        assertEquals(snapshot, f.store.read())
    }
    @Test fun strictPreferenceShapesRejectPartialUnknownAndWrongTypedRecords() {
        val malformed = listOf(
            mapOf("future_version" to "payload"),
            mapOf("encrypted_snapshot" to "payload"),
            mapOf("blocked" to false),
            mapOf("blocked" to true, "encrypted_snapshot" to "payload"),
            mapOf("blocked" to "false", "encrypted_snapshot" to "payload"),
            mapOf("blocked" to false, "encrypted_snapshot" to 123),
            mapOf("blocked" to false, "encrypted_snapshot" to null),
            mapOf("blocked" to false, "encrypted_snapshot" to "payload", "future_version" to 2),
        )
        for (entries in malformed) {
            val p = Persistence()
            val adapter = object : EncryptedLocalEntitlementStateStore.Persistence {
                override val scope = p.scope
                override fun read() = EncryptedLocalEntitlementStateStore.recordFromPreferences(entries)
                override fun write(record: EncryptedLocalEntitlementStateStore.Record) = p.write(record)
            }
            failure { EncryptedLocalEntitlementStateStore(adapter, EncryptedLocalEntitlementStateStore.GcmCrypto(Keys())).read() }
            assertTrue(p.disk.record.blocked)
            assertNull(p.disk.record.ciphertext)
        }
        val pristine = EncryptedLocalEntitlementStateStore.recordFromPreferences(emptyMap<String, Any>())
        assertNull(pristine.ciphertext); assertFalse(pristine.blocked)
        val blocked = EncryptedLocalEntitlementStateStore.recordFromPreferences(mapOf("blocked" to true))
        assertTrue(blocked.blocked); assertNull(blocked.ciphertext)
        val payload = EncryptedLocalEntitlementStateStore.recordFromPreferences(mapOf("blocked" to false, "encrypted_snapshot" to "payload"))
        assertFalse(payload.blocked); assertEquals("payload", payload.ciphertext)
    }
    @Test fun oversizedDecryptedBufferIsRejectedAndWiped() {
        val clear = ByteArray(LocalEntitlementSnapshotCodec.MAX_BYTES + 1) { 7 }
        val p = Persistence(Disk(EncryptedLocalEntitlementStateStore.Record("fake", false)))
        val crypto = object : EncryptedLocalEntitlementStateStore.Crypto {
            override fun hasKey() = true
            override fun encrypt(clear: ByteArray): String = error("unused")
            override fun decrypt(ciphertext: String) = clear
        }
        failure { EncryptedLocalEntitlementStateStore(p, crypto).read() }
        assertTrue(clear.all { it == 0.toByte() })
        assertTrue(p.disk.record.blocked)
    }
    @Test fun negativeDecodedTimestampAndRevisionAreRejected() {
        val timestamp = LocalEntitlementSnapshotCodec.encode(LocalEntitlementSnapshot(anchor = TrustedTimeAnchor(1, 2, "boot")))
        ByteBuffer.wrap(timestamp).putLong(6, -1)
        corrupt(timestamp)
        val snapshot = LocalEntitlementSnapshot(onlineDenial = OnlineEntitlementDecision(
            LicenseAccessState.UNLICENSED, 1, null, 1, null, null, null,
        ))
        val revision = LocalEntitlementSnapshotCodec.encode(snapshot)
        // Fixed prefix + state length/name + server time + absent license ID + present revision.
        ByteBuffer.wrap(revision).putLong(11 + 4 + "UNLICENSED".length + 8 + 2, -1)
        corrupt(revision)
    }
    @Test fun productionEncryptionHonorsExactPlaintextAllocationBoundary() {
        val f = Fixture()
        val maximum = ByteArray(LocalEntitlementSnapshotCodec.MAX_BYTES) { 42 }
        val encoded = f.crypto.encrypt(maximum)
        val clear = f.crypto.decrypt(encoded)
        try { assertArrayEquals(maximum, clear) } finally { clear.fill(0); maximum.fill(0) }
        try {
            f.crypto.encrypt(ByteArray(LocalEntitlementSnapshotCodec.MAX_BYTES + 1))
            fail("oversized encryption accepted")
        } catch (_: IllegalArgumentException) { /* expected bounded input rejection */ }
    }
    @Test fun storeAndRecordStringRepresentationsDoNotExposePayload() {
        val f = Fixture(); f.store.write(FULL)
        val record = f.persistence.disk.record
        val diagnostic = f.store.toString() + record.toString()
        assertFalse(diagnostic.contains(record.ciphertext!!))
        assertFalse(diagnostic.contains(FULL.lease!!.credentialBinding))
        assertFalse(diagnostic.contains(FULL.lease!!.installationId))
    }
    @Test fun crossInstanceWriteWaitsForEntireReadIncludingDecryption() {
        val f = Fixture(); f.store.write(FULL)
        val decryptEntered = CountDownLatch(1)
        val releaseDecrypt = CountDownLatch(1)
        val writeAttempted = CountDownLatch(1)
        val encryptionEntered = CountDownLatch(1)
        val persistenceEntered = CountDownLatch(1)
        val crypto = object : EncryptedLocalEntitlementStateStore.Crypto {
            override fun hasKey() = f.crypto.hasKey()
            override fun encrypt(clear: ByteArray): String {
                encryptionEntered.countDown()
                return f.crypto.encrypt(clear)
            }
            override fun decrypt(ciphertext: String): ByteArray {
                decryptEntered.countDown()
                check(releaseDecrypt.await(5, TimeUnit.SECONDS))
                return f.crypto.decrypt(ciphertext)
            }
        }
        val persistence = object : EncryptedLocalEntitlementStateStore.Persistence {
            override val scope = f.persistence.scope
            override fun read() = f.persistence.read()
            override fun write(record: EncryptedLocalEntitlementStateStore.Record): Boolean {
                persistenceEntered.countDown()
                return f.persistence.write(record)
            }
        }
        val reader = EncryptedLocalEntitlementStateStore(persistence, crypto)
        val writer = EncryptedLocalEntitlementStateStore(persistence, crypto)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val reading = pool.submit<LocalEntitlementSnapshot> { reader.read() }
            assertTrue(decryptEntered.await(5, TimeUnit.SECONDS))
            val writing = pool.submit { writeAttempted.countDown(); writer.write(LocalEntitlementSnapshot()) }
            assertTrue(writeAttempted.await(5, TimeUnit.SECONDS))
            assertFalse(persistenceEntered.await(150, TimeUnit.MILLISECONDS))
            assertEquals(1L, encryptionEntered.count)
            assertFalse(writing.isDone)
            releaseDecrypt.countDown()
            assertEquals(FULL, reading.get(5, TimeUnit.SECONDS))
            writing.get(5, TimeUnit.SECONDS)
            assertTrue(persistenceEntered.await(5, TimeUnit.SECONDS))
            assertTrue(encryptionEntered.await(5, TimeUnit.SECONDS))
            assertEquals(LocalEntitlementSnapshot(), f.store.read())
        } finally { releaseDecrypt.countDown(); pool.shutdownNow() }
    }

    companion object {
        private const val SECRET = "sentinel-installation-secret-raw-jwt"
        private val FULL = LocalEntitlementSnapshot(
            lease = OfflineLease("megastream-control", "megastream-app", "12345678-1234-1234-1234-123456789abc",
                Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() }),
                100, 101, 500, 90, 1000, "22345678-1234-1234-1234-123456789abc", 7,
                "32345678-1234-1234-1234-123456789abc", 1, LicenseAccessState.ALLOWED),
            anchor = TrustedTimeAnchor(200, 321, "boot-identity"),
            authenticatedDenial = LicenseAccessState.SUSPENDED,
            lastClockReading = ClockReading(202, 2222, "boot-identity"),
            verificationRequired = true,
            trustedTimeHighWaterEpochSeconds = 210,
            onlineDenial = OnlineEntitlementDecision(LicenseAccessState.SUSPENDED, 203,
                "22345678-1234-1234-1234-123456789abc", 8, 90, 1000, 400),
        )
    }
}
