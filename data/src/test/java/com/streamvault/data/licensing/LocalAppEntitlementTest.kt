package com.MegaStream.data.licensing

import com.MegaStream.domain.licensing.ClockReading
import com.MegaStream.domain.licensing.LicenseAccessState
import com.MegaStream.domain.licensing.OfflineLease
import com.MegaStream.domain.licensing.OnlineEntitlementDecision
import com.google.common.truth.Truth.assertThat
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class LocalAppEntitlementTest {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun signedLeaseWithSmallDeviceClockLagWaitsUntilValid() = runTest {
        val entitlement = LocalAppEntitlement(verifier, INSTALLATION, BINDING, store) {
            ClockReading(NOW + testScheduler.currentTime / 1000, 1000 + testScheduler.currentTime, "boot-1")
        }
        val fresh = lease().copy(issuedAtEpochSeconds = NOW + 2, notBeforeEpochSeconds = NOW + 2)
        assertThat(entitlement.applyOnlineDecision(online(fresh), token(fresh)).allowed).isTrue()
        assertThat(testScheduler.currentTime).isEqualTo(2000L)
        assertThat(store.read().lease!!.expiresAtEpochSeconds).isEqualTo(fresh.expiresAtEpochSeconds)
    }

    private val keys = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    private val verifier = OfflineLeaseVerifier(mapOf("test-key" to keys.public))
    private val store = InMemoryLocalEntitlementStateStore()
    private var reading = ClockReading(NOW, 1_000L, "boot-1")

    @Test
    fun recreationRestoresLeaseAndCurrentDecision() = runBlocking {
        val entitlement = entitlement()
        assertThat(entitlement.receive(token()).allowed).isTrue()
        advance(30)

        val recreated = entitlement()

        assertThat(recreated.decision.value.allowed).isTrue()
        assertThat(recreated.gate().offlineDeadlineEpochSeconds).isEqualTo(NOW + GRACE)
        assertThat(store.read().anchor!!.serverEpochSeconds).isEqualTo(NOW)
    }

    @Test
    fun failedRefreshesPreserveTrustAndNeverExtendDeadline() = runBlocking {
        val entitlement = entitlement()
        entitlement.receive(token())
        val accepted = store.read()
        advance(GRACE - 1)

        for (response in listOf(null, "not-a-jws", token().dropLast(6))) {
            assertThat(entitlement.receive(response).allowed).isTrue()
            assertTrustUnchanged(accepted)
        }
        advance(1)

        assertThat(entitlement.gate().state).isEqualTo(LicenseAccessState.VERIFICATION_REQUIRED)
        assertThat(entitlement.decision.value).isEqualTo(entitlement.gate())
        assertThat(entitlement.decision.value.offlineDeadlineEpochSeconds).isEqualTo(NOW + GRACE)
    }

    @Test
    fun eachAuthenticatedDenialSurvivesFailureAndRecreation() = runBlocking {
        for (denial in LicenseAccessState.entries.filter { it != LicenseAccessState.ALLOWED }) {
            val isolated = InMemoryLocalEntitlementStateStore()
            val entitlement = entitlement(isolated)
            entitlement.receive(token())
            val denied = lease().copy(issuedAtEpochSeconds = NOW + 1, decision = denial)
            reading = ClockReading(NOW + 1, 2_000, "boot-1")
            assertThat(entitlement.receive(token(denied)).state).isEqualTo(denial)
            val accepted = isolated.read()

            assertThat(entitlement.receive(null).state).isEqualTo(denial)
            assertThat(entitlement.receive("invalid").state).isEqualTo(denial)
            reading = reading.copy(bootId = "boot-2", elapsedRealtimeMillis = 0)
            assertThat(entitlement(isolated).gate().state).isEqualTo(denial)
            assertThat(isolated.read().lease).isEqualTo(accepted.lease)
            reading = ClockReading(NOW, 1_000, "boot-1")
        }
    }

    @Test
    fun denialOnlyClearedByNewerCurrentlyValidSameLicenseWithoutRevisionDowngrade() = runBlocking {
        val entitlement = entitlement()
        entitlement.receive(token())
        advance(10)
        val denied = lease().copy(issuedAtEpochSeconds = NOW + 10, licenseRevision = 3, decision = LicenseAccessState.SUSPENDED)
        entitlement.receive(token(denied))
        advance(10)
        val allowed = denied.copy(
            issuedAtEpochSeconds = NOW + 20,
            decision = LicenseAccessState.ALLOWED,
            tokenId = UUID.randomUUID().toString(),
        )
        val rejected = listOf(
            allowed.copy(issuedAtEpochSeconds = NOW + 9),
            allowed.copy(issuedAtEpochSeconds = NOW + 10),
            allowed.copy(licenseRevision = 2),
            allowed.copy(licenseId = UUID.randomUUID().toString()),
            allowed.copy(notBeforeEpochSeconds = NOW + 21),
            allowed.copy(licenseStartsAtEpochSeconds = NOW + 21),
        )
        for (candidate in rejected) {
            assertThat(entitlement.receive(token(candidate)).state).isEqualTo(LicenseAccessState.SUSPENDED)
            assertThat(store.read().onlineDenial).isEqualTo(online(denied).copy(offlineUntilEpochSeconds = null))
        }

        assertThat(entitlement.receive(token(allowed)).allowed).isTrue()
        assertThat(store.read().authenticatedDenial).isNull()
    }

    @Test
    fun replayAndEqualIssuedAtDoNotResetAnchor() = runBlocking {
        val entitlement = entitlement()
        val original = token()
        entitlement.receive(original)
        val accepted = store.read()
        advance(100)

        entitlement.receive(original)
        assertTrustUnchanged(accepted)
        entitlement.receive(token(lease().copy(tokenId = UUID.randomUUID().toString())))
        assertTrustUnchanged(accepted)
        advance(GRACE - 100)
        assertThat(entitlement.gate().allowed).isFalse()
    }

    @Test
    fun sameSecondOnlineDenialClosesAccessButEqualAllowedCannotReopen() = runBlocking {
        val entitlement = entitlement()
        entitlement.receive(token())
        val denied = lease().copy(decision = LicenseAccessState.SUSPENDED)

        assertThat(entitlement.receive(token(denied)).state).isEqualTo(LicenseAccessState.SUSPENDED)
        assertThat(entitlement.receive(token()).state).isEqualTo(LicenseAccessState.SUSPENDED)
    }

    @Test
    fun rebootRequiresStrictlyNewerTokenAndUncertaintySurvivesRecreation() = runBlocking {
        val entitlement = entitlement()
        val original = token()
        entitlement.receive(original)
        advance(10)
        reading = reading.copy(bootId = "boot-2", elapsedRealtimeMillis = 0)

        assertThat(entitlement.gate().state).isEqualTo(LicenseAccessState.VERIFICATION_REQUIRED)
        assertThat(entitlement().receive(original).allowed).isFalse()
        assertThat(entitlement().receive(token(lease().copy(issuedAtEpochSeconds = NOW + 10))).allowed).isTrue()
        assertThat(store.read().verificationRequired).isFalse()
    }

    @Test
    fun elapsedRollbackAboveOriginalAnchorCannotReopenExpiredLease() = runBlocking {
        val entitlement = entitlement()
        entitlement.receive(token(lease().copy(expiresAtEpochSeconds = NOW + 100)))
        advance(100)
        assertThat(entitlement.gate().allowed).isFalse()
        reading = ClockReading(NOW + 50, 51_000, "boot-1")

        assertThat(entitlement.gate().state).isEqualTo(LicenseAccessState.VERIFICATION_REQUIRED)
        reading = ClockReading(NOW + 101, 102_000, "boot-1")
        assertThat(entitlement().gate().allowed).isFalse()
        assertThat(entitlement.receive(token(lease().copy(issuedAtEpochSeconds = NOW + 101))).allowed).isTrue()
    }

    @Test
    fun wallRollbackAfterForwardObservationLatchesUntilFreshAcceptance() = runBlocking {
        val entitlement = entitlement()
        entitlement.receive(token())
        reading = reading.copy(wallEpochSeconds = NOW + 100)
        entitlement.gate()
        reading = reading.copy(wallEpochSeconds = NOW + 50)

        assertThat(entitlement.gate().allowed).isFalse()
        reading = reading.copy(wallEpochSeconds = NOW + 100)
        assertThat(entitlement().gate().allowed).isFalse()
        assertThat(entitlement.receive(token(lease().copy(issuedAtEpochSeconds = NOW + 100))).allowed).isTrue()
    }

    @Test
    fun futureOrExpiredResponseCannotReplaceExistingTrust() = runBlocking {
        val entitlement = entitlement()
        entitlement.receive(token())
        val accepted = store.read()
        advance(10)
        for (candidate in listOf(
            lease().copy(issuedAtEpochSeconds = NOW + 11),
            lease().copy(issuedAtEpochSeconds = NOW + 1, expiresAtEpochSeconds = NOW + 10),
            lease().copy(issuedAtEpochSeconds = NOW + 1, installationId = UUID.randomUUID().toString()),
            lease().copy(issuedAtEpochSeconds = NOW + 1, credentialBinding = encode(ByteArray(32) { 1 })),
        )) {
            assertThat(entitlement.receive(token(candidate)).allowed).isTrue()
            assertTrustUnchanged(accepted)
        }
    }

    @Test
    fun delayedFreshTokenUsesReceiptTimeWithoutMovingDeadline() = runBlocking {
        val entitlement = entitlement()
        entitlement.receive(token())
        advance(100)
        val delayed = lease().copy(issuedAtEpochSeconds = NOW + 1, expiresAtEpochSeconds = NOW + 101)

        assertThat(entitlement.receive(token(delayed)).allowed).isTrue()
        assertThat(store.read().anchor!!.serverEpochSeconds).isEqualTo(NOW + 100)
        advance(1)
        assertThat(entitlement.gate().allowed).isFalse()
    }

    @Test
    fun delayedGrantAfterRollbackCannotReopenPastPersistedTrustedFloor() = runBlocking {
        val entitlement = entitlement()
        entitlement.receive(token(lease().copy(expiresAtEpochSeconds = NOW + 100)))
        advance(100)
        assertThat(entitlement.gate().allowed).isFalse()
        assertThat(store.read().trustedTimeHighWaterEpochSeconds).isEqualTo(NOW + 100)
        reading = ClockReading(NOW + 50, 51_000, "boot-1")
        val delayed = lease().copy(issuedAtEpochSeconds = NOW + 1, expiresAtEpochSeconds = NOW + 90)

        assertThat(entitlement().receive(token(delayed)).allowed).isFalse()
        assertThat(store.read().trustedTimeHighWaterEpochSeconds).isEqualTo(NOW + 100)
        assertThat(store.read().lease!!.issuedAtEpochSeconds).isEqualTo(NOW)
        assertThat(store.read().verificationRequired).isTrue()
    }

    @Test
    fun freshAllowedCannotEraseDenialWhileReceiptWallIsBelowPersistedFloor() = runBlocking {
        val entitlement = entitlement()
        entitlement.receive(token())
        advance(1)
        val denial = lease().copy(issuedAtEpochSeconds = NOW + 1, decision = LicenseAccessState.SUSPENDED)
        entitlement.receive(token(denial))
        advance(99)
        entitlement.gate()
        val accepted = store.read()
        reading = ClockReading(NOW + 50, 51_000, "boot-1")
        val fresh = lease().copy(issuedAtEpochSeconds = NOW + 2)

        assertThat(entitlement.receive(token(fresh)).state).isEqualTo(LicenseAccessState.SUSPENDED)
        assertTrustUnchanged(accepted)
        assertThat(store.read().verificationRequired).isTrue()
    }

    @Test
    fun initializationDetectsRollbackBeforeGateIsCalled() = runBlocking {
        val entitlement = entitlement()
        entitlement.receive(token())
        advance(100)
        entitlement.gate()
        reading = ClockReading(NOW + 50, 51_000, "boot-1")

        assertThat(entitlement().decision.value.allowed).isFalse()
        assertThat(store.read().verificationRequired).isTrue()
    }

    @Test
    fun persistedLeaseCannotAuthorizeAnotherInstallationOrCredential() = runBlocking {
        entitlement().receive(token())
        for ((installation, binding) in listOf(
            UUID.randomUUID().toString() to BINDING,
            INSTALLATION to Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 1 }),
        )) {
            val other = LocalAppEntitlement(verifier, installation, binding, store) { reading }
            assertThat(other.decision.value.state).isEqualTo(LicenseAccessState.VERIFICATION_REQUIRED)
            assertThat(other.gate().allowed).isFalse()
        }
    }

    @Test
    fun sharedStoreMakesExistingInstanceObserveNewDenialOnGate() = runBlocking {
        val first = entitlement()
        val second = entitlement()
        first.receive(token())
        advance(1)
        second.receive(token(lease().copy(issuedAtEpochSeconds = NOW + 1, decision = LicenseAccessState.REVOKED)))

        assertThat(first.gate().state).isEqualTo(LicenseAccessState.REVOKED)
        assertThat(first.decision.value.state).isEqualTo(LicenseAccessState.REVOKED)
    }

    @Test
    fun unsignedAuthenticatedDenialPreservesLeaseAndBlocksReplayUntilFreshRecovery() = runBlocking {
        val entitlement = entitlement()
        val original = lease()
        entitlement.receive(token(original))
        val accepted = store.read()
        advance(10)
        val denial = online(original).copy(state = LicenseAccessState.SUSPENDED, serverEpochSeconds = NOW + 10,
            offlineUntilEpochSeconds = null)

        assertThat(entitlement.applyOnlineDecision(denial, null).state).isEqualTo(LicenseAccessState.SUSPENDED)
        assertThat(store.read().lease).isEqualTo(accepted.lease)
        assertThat(store.read().anchor).isEqualTo(accepted.anchor)
        assertThat(entitlement().receive(null).state).isEqualTo(LicenseAccessState.SUSPENDED)
        assertThat(entitlement.receive(token(original)).state).isEqualTo(LicenseAccessState.SUSPENDED)
        advance(1)
        val fresh = lease().copy(issuedAtEpochSeconds = NOW + 11)
        assertThat(entitlement.applyOnlineDecision(online(fresh), token(fresh)).allowed).isTrue()
        assertThat(store.read().onlineDenial).isNull()
    }

    @Test
    fun onlineAllowedRequiresSignedLeaseAndExactMatchingMetadata() = runBlocking {
        val entitlement = entitlement()
        val claims = lease()
        val matching = online(claims)
        val signed = token(claims)
        val mismatches = listOf(
            matching.copy(licenseId = UUID.randomUUID().toString()),
            matching.copy(licenseRevision = 2),
            matching.copy(serverEpochSeconds = NOW - 1),
            matching.copy(startsAtEpochSeconds = NOW - 101),
            matching.copy(endsAtEpochSeconds = NOW + GRACE + 1),
            matching.copy(offlineUntilEpochSeconds = NOW + GRACE - 1),
            matching.copy(offlineUntilEpochSeconds = null),
            matching.copy(state = LicenseAccessState.SUSPENDED),
        )
        assertThat(entitlement.applyOnlineDecision(matching, null).allowed).isFalse()
        for (mismatch in mismatches) {
            assertThat(entitlement.applyOnlineDecision(mismatch, signed).allowed).isFalse()
            assertThat(store.read().lease).isNull()
            assertThat(store.read().authenticatedDenial).isNull()
        }
        assertThat(entitlement.applyOnlineDecision(matching, signed).allowed).isTrue()
    }

    @Test
    fun onlineGrantAllowsExactStartButRejectsExactLicenseEndDespiteOfflineGrace() = runBlocking {
        val startsNow = lease().copy(licenseStartsAtEpochSeconds = NOW)
        val first = entitlement(InMemoryLocalEntitlementStateStore())
        assertThat(first.applyOnlineDecision(online(startsNow), token(startsNow)).allowed).isTrue()
        val endsNow = lease().copy(licenseEndsAtEpochSeconds = NOW)
        val second = entitlement(InMemoryLocalEntitlementStateStore())
        assertThat(second.applyOnlineDecision(online(endsNow), token(endsNow)).allowed).isFalse()
    }

    @Test
    fun terminalDenialsCannotBeWeakenedByIntermediateDecisionsOrNewerGrants() = runBlocking {
        for (terminal in listOf(LicenseAccessState.REVOKED, LicenseAccessState.INSTALLATION_DISABLED)) {
            val isolated = InMemoryLocalEntitlementStateStore()
            val entitlement = entitlement(isolated)
            val initial = lease()
            entitlement.receive(token(initial))
            entitlement.applyOnlineDecision(online(initial).copy(state = terminal, offlineUntilEpochSeconds = null), null)
            advance(1)
            val fresh = lease().copy(issuedAtEpochSeconds = reading.wallEpochSeconds, licenseRevision = 5)
            val nonterminal = online(fresh).copy(state = LicenseAccessState.SUSPENDED, offlineUntilEpochSeconds = null)
            assertThat(entitlement.applyOnlineDecision(nonterminal, null).state).isEqualTo(terminal)
            assertThat(entitlement.receive(token(fresh.copy(decision = LicenseAccessState.SUSPENDED))).state).isEqualTo(terminal)
            assertThat(entitlement.applyOnlineDecision(online(fresh), token(fresh)).state).isEqualTo(terminal)
            assertThat(entitlement.receive(token(fresh)).state).isEqualTo(terminal)
            reading = ClockReading(NOW, 1_000, "boot-1")
        }
    }

    @Test
    fun unactivatedRecoverableDenialsRequireNewGrantButDisabledRemainsTerminal() = runBlocking {
        for (state in listOf(LicenseAccessState.VERIFICATION_REQUIRED, LicenseAccessState.UNLICENSED,
                LicenseAccessState.INSTALLATION_DISABLED)) {
            val entitlement = entitlement(InMemoryLocalEntitlementStateStore())
            val denial = online(lease()).copy(state = state, licenseId = null, licenseRevision = null,
                startsAtEpochSeconds = null, endsAtEpochSeconds = null, offlineUntilEpochSeconds = null)
            assertThat(entitlement.applyOnlineDecision(denial, null).state).isEqualTo(state)
            assertThat(entitlement.receive(token()).allowed).isFalse()
            advance(1)
            val fresh = lease().copy(issuedAtEpochSeconds = NOW + 1)
            assertThat(entitlement.receive(token(fresh)).allowed).isEqualTo(state != LicenseAccessState.INSTALLATION_DISABLED)
            reading = ClockReading(NOW, 1_000, "boot-1")
        }
    }

    @Test
    fun nullIdentityDenialRetainsKnownLicenseForRecoveryAndCannotBypassRevision() = runBlocking {
        val entitlement = entitlement()
        val original = lease().copy(licenseRevision = 3)
        entitlement.receive(token(original))
        advance(1)
        val denial = online(original).copy(state = LicenseAccessState.VERIFICATION_REQUIRED, serverEpochSeconds = NOW + 1,
            licenseId = null, licenseRevision = null, startsAtEpochSeconds = null, endsAtEpochSeconds = null,
            offlineUntilEpochSeconds = null)
        entitlement.applyOnlineDecision(denial, null)
        advance(1)
        val fresh = lease().copy(issuedAtEpochSeconds = NOW + 2, licenseRevision = 3)
        assertThat(entitlement.receive(token(fresh.copy(licenseId = UUID.randomUUID().toString()))).allowed).isFalse()
        assertThat(entitlement.receive(token(fresh.copy(licenseRevision = 2))).allowed).isFalse()
        assertThat(entitlement.receive(token(fresh)).allowed).isTrue()
    }

    @Test
    fun intermediateDenialCannotMigrateKnownLicenseIdentityThenClearIt() = runBlocking {
        val entitlement = entitlement()
        val original = lease()
        entitlement.receive(token(original))
        advance(1)
        val denial = online(original).copy(state = LicenseAccessState.SUSPENDED, serverEpochSeconds = NOW + 1,
            offlineUntilEpochSeconds = null)
        entitlement.applyOnlineDecision(denial, null)
        val accepted = store.read()
        advance(1)
        val other = lease().copy(licenseId = UUID.randomUUID().toString(), issuedAtEpochSeconds = NOW + 2)
        entitlement.applyOnlineDecision(online(other).copy(state = LicenseAccessState.SUSPENDED,
            offlineUntilEpochSeconds = null), null)
        entitlement.receive(token(other.copy(decision = LicenseAccessState.SUSPENDED)))
        assertTrustUnchanged(accepted)
        advance(1)
        val grant = other.copy(issuedAtEpochSeconds = NOW + 3)
        assertThat(entitlement.applyOnlineDecision(online(grant), token(grant)).state).isEqualTo(LicenseAccessState.SUSPENDED)
        assertTrustUnchanged(accepted)
    }

    @Test
    fun knownInstallationDisabledWithNullRevisionImmediatelyBlocksAccess() = runBlocking {
        val entitlement = entitlement()
        val original = lease().copy(licenseRevision = 3)
        entitlement.receive(token(original))
        advance(1)
        val disabled = online(original).copy(state = LicenseAccessState.INSTALLATION_DISABLED,
            serverEpochSeconds = NOW + 1, licenseRevision = null,
            startsAtEpochSeconds = null, endsAtEpochSeconds = null, offlineUntilEpochSeconds = null)

        assertThat(entitlement.applyOnlineDecision(disabled, null).state).isEqualTo(LicenseAccessState.INSTALLATION_DISABLED)
        assertThat(store.read().onlineDenial!!.licenseRevision).isEqualTo(3L)
    }

    @Test
    fun nullableDenialRevisionRetainsHigherOnlineRevisionThanPersistedLease() = runBlocking {
        val entitlement = entitlement()
        val original = lease()
        entitlement.receive(token(original))
        advance(1)
        val suspended = online(original).copy(state = LicenseAccessState.SUSPENDED,
            serverEpochSeconds = NOW + 1, licenseRevision = 3, offlineUntilEpochSeconds = null)
        entitlement.applyOnlineDecision(suspended, null)
        advance(1)
        val uncertain = suspended.copy(state = LicenseAccessState.VERIFICATION_REQUIRED,
            serverEpochSeconds = NOW + 2, licenseRevision = null, startsAtEpochSeconds = null, endsAtEpochSeconds = null)
        entitlement.applyOnlineDecision(uncertain, null)
        assertThat(store.read().onlineDenial!!.licenseRevision).isEqualTo(3L)
        advance(1)
        val downgrade = lease().copy(issuedAtEpochSeconds = NOW + 3, licenseRevision = 2)
        assertThat(entitlement.applyOnlineDecision(online(downgrade), token(downgrade)).allowed).isFalse()
    }

    // Simulated authenticated server boundary: denial responses carry metadata only, never a JWT.
    private suspend fun LocalAppEntitlement.receive(compactLease: String?) = if (compactLease == null) {
        recordRefreshFailure()
    } else {
        val claims = try {
            verifier.verify(compactLease, INSTALLATION, BINDING)
        } catch (_: IllegalArgumentException) {
            null
        }
        if (claims == null) applyOnlineDecision(online(lease()), compactLease)
        else if (claims.decision == LicenseAccessState.ALLOWED) applyOnlineDecision(online(claims), compactLease)
        else applyOnlineDecision(online(claims).copy(offlineUntilEpochSeconds = null), null)
    }

    private fun online(claims: OfflineLease) = OnlineEntitlementDecision(
        state = claims.decision,
        serverEpochSeconds = claims.issuedAtEpochSeconds,
        licenseId = claims.licenseId,
        licenseRevision = claims.licenseRevision,
        startsAtEpochSeconds = claims.licenseStartsAtEpochSeconds,
        endsAtEpochSeconds = claims.licenseEndsAtEpochSeconds,
        offlineUntilEpochSeconds = claims.expiresAtEpochSeconds,
    )

    private fun entitlement(backingStore: LocalEntitlementStateStore = store) =
        LocalAppEntitlement(verifier, INSTALLATION, BINDING, backingStore) { reading }

    private fun advance(seconds: Long) {
        reading = reading.copy(
            wallEpochSeconds = reading.wallEpochSeconds + seconds,
            elapsedRealtimeMillis = reading.elapsedRealtimeMillis + seconds * 1_000,
        )
    }

    private fun assertTrustUnchanged(accepted: LocalEntitlementSnapshot) {
        assertThat(store.read().lease).isEqualTo(accepted.lease)
        assertThat(store.read().anchor).isEqualTo(accepted.anchor)
        assertThat(store.read().authenticatedDenial).isEqualTo(accepted.authenticatedDenial)
        assertThat(store.read().onlineDenial).isEqualTo(accepted.onlineDenial)
    }

    private fun lease() = OfflineLease(
        issuer = "https://megastrem.megastation.uk",
        audience = "megastream-android",
        installationId = INSTALLATION,
        credentialBinding = BINDING,
        issuedAtEpochSeconds = NOW,
        notBeforeEpochSeconds = NOW,
        expiresAtEpochSeconds = NOW + GRACE,
        licenseStartsAtEpochSeconds = NOW - 100,
        licenseEndsAtEpochSeconds = NOW + GRACE,
        licenseId = "20000000-0000-0000-0000-000000000002",
        licenseRevision = 1,
        tokenId = UUID.randomUUID().toString(),
        policyVersion = 1,
        decision = LicenseAccessState.ALLOWED,
    )

    private fun token(claims: OfflineLease = lease()): String {
        val header = "{\"alg\":\"ES256\",\"kid\":\"test-key\",\"typ\":\"JWT\"}"
        val payload = buildJsonObject {
            put("iss", claims.issuer)
            put("aud", claims.audience)
            put("sub", claims.installationId)
            put("credentialBinding", claims.credentialBinding)
            put("iat", claims.issuedAtEpochSeconds)
            put("nbf", claims.notBeforeEpochSeconds)
            put("exp", claims.expiresAtEpochSeconds)
            put("licenseStartsAt", claims.licenseStartsAtEpochSeconds)
            put("licenseEndsAt", claims.licenseEndsAtEpochSeconds)
            put("lid", claims.licenseId)
            put("lrv", claims.licenseRevision)
            put("jti", claims.tokenId)
            put("policyVersion", claims.policyVersion)
            put("decision", claims.decision.name.lowercase())
        }.toString()
        val signingInput = "${encode(header.toByteArray())}.${encode(payload.toByteArray())}"
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private)
            update(signingInput.toByteArray(Charsets.US_ASCII))
            sign()
        }
        return "$signingInput.${encode(joseSignature(der))}"
    }

    private fun joseSignature(der: ByteArray): ByteArray {
        val raw = ByteArray(64)
        var offset = 2
        for (component in 0..1) {
            val length = der[offset + 1].toInt() and 0xff
            val integer = der.copyOfRange(offset + 2, offset + 2 + length).dropWhile { it == 0.toByte() }.toByteArray()
            integer.copyInto(raw, component * 32 + 32 - integer.size)
            offset += 2 + length
        }
        return raw
    }

    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private companion object {
        const val NOW = 1_800_000_000L
        const val GRACE = 72L * 60 * 60
        const val INSTALLATION = "10000000-0000-0000-0000-000000000001"
        val BINDING: String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))
    }
}
