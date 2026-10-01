package com.MegaStream.app.playback.gate

import com.MegaStream.app.controlplane.*
import com.MegaStream.data.licensing.InMemoryLocalEntitlementStateStore
import com.MegaStream.data.licensing.LocalAppEntitlement
import com.MegaStream.data.licensing.OfflineLeaseVerifier
import com.MegaStream.domain.licensing.ClockReading
import com.MegaStream.domain.licensing.LicenseAccessState
import java.io.IOException
import java.time.Instant
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ControlPlaneLicenseActivationPortTest {
    @Test fun authenticatedDenialsUseExactDomainMappingAndMetadata() = runBlocking {
        for (state in listOf("unlicensed", "verification_required", "installation_disabled", "suspended", "revoked", "expired", "not_started")) {
            val fixture = Fixture(denial(state))
            assertTrue(fixture.port.activateDirect("secret-key") is ControlPlaneResult.Success)
            val stored = fixture.store.read().onlineDenial!!
            assertEquals(LicenseAccessState.valueOf(state.uppercase()), stored.state)
            assertEquals(Instant.parse(TIME).epochSecond, stored.serverEpochSeconds)
            if (state in setOf("suspended", "revoked", "expired", "not_started")) {
                assertEquals(ID, stored.licenseId)
                assertEquals(1L, stored.licenseRevision)
                assertNotNull(stored.startsAtEpochSeconds)
                assertNotNull(stored.endsAtEpochSeconds)
            }
            assertEquals(1, fixture.refreshed)
            assertEquals("Bearer $CREDENTIAL", fixture.requests.single().header("Authorization"))
        }
    }

    @Test fun transportAllowedWithInvalidSignatureNeverAuthorizes() = runBlocking {
        val fixture = Fixture("""{"decision":{"state":"allowed","licenseId":"$ID","licenseRevision":1,"startsAt":"2026-09-01T00:00:00Z","endsAt":"2026-10-10T00:00:00Z","offlineUntil":"2026-10-01T00:00:00Z"},"serverTime":"$TIME","refreshAfterSeconds":30,"lease":"not-signed"}""")
        assertTrue(fixture.port.activateDirect("secret-key") is ControlPlaneResult.Success)
        assertFalse(fixture.entitlement.gate().allowed)
        assertNull(fixture.store.read().lease)
    }

    @Test fun semanticInvalidResponseCannotApplyDenial() = runBlocking {
        val fixture = Fixture(denial("expired").replace("2026-09-29T00:00:00Z", "2026-10-10T00:00:00Z"))
        assertEquals("invalid_response", (fixture.port.activateDirect("secret") as ControlPlaneResult.Failure).error.code)
        assertNull(fixture.store.read().onlineDenial)
        assertEquals(0, fixture.refreshed)
    }

    @Test fun requestAndPendingPollNeverChangeEntitlement() = runBlocking {
        val fixture = Fixture("""{"code":"CODE","expiresAt":"2026-10-01T00:00:00Z","status":"pending","pollToken":"poll-secret"}""")
        val code = fixture.port.requestCode() as ControlPlaneResult.Success
        assertEquals("CODE", code.value.code)
        assertFalse(code.toString().contains("poll-secret"))
        fixture.payload = """{"status":"pending","serverTime":"$TIME","retryAfterSeconds":5}"""
        assertTrue((fixture.port.pollCode("CODE", "poll-secret") as ControlPlaneResult.Success).value is ActivationStatusResponse.Pending)
        assertNull(fixture.store.read().onlineDenial)
        assertEquals(0, fixture.refreshed)
    }

    @Test fun activatedPollAppliesAuthenticatedDecision() = runBlocking {
        val fixture = Fixture(denial("unlicensed"))
        assertTrue((fixture.port.pollCode("CODE", "poll-secret") as ControlPlaneResult.Success).value is ActivationStatusResponse.Activated)
        assertEquals(LicenseAccessState.UNLICENSED, fixture.store.read().onlineDenial!!.state)
        assertEquals(1, fixture.refreshed)
    }

    @Test fun providerAndNetworkFailuresNeverExposeSecrets() = runBlocking {
        val fixture = Fixture(denial("unlicensed"))
        for (exception in listOf(IOException("secret-key"), IllegalStateException("poll-secret"))) {
            val port = ControlPlaneLicenseActivationPort(
                ControlPlaneClient(CallExecutor { throw exception }), Provider { fixture.identity },
                Provider { fixture.entitlement }, {},
            )
            val reply = port.activateDirect("secret-key")
            assertTrue(reply is ControlPlaneResult.Failure)
            assertFalse(reply.toString().contains("secret-key"))
            assertFalse(reply.toString().contains("poll-secret"))
            assertNull(fixture.store.read().onlineDenial)
        }
    }

    @Test fun credentialsAreResolvedOffCallerThreadAndProviderFailureIsRedacted() = runBlocking {
        val fixture = Fixture(denial("unlicensed"))
        val callerThread = Thread.currentThread()
        var resolutionThread: Thread? = null
        val port = ControlPlaneLicenseActivationPort(
            ControlPlaneClient(CallExecutor { throw AssertionError("No request after credential failure") }),
            Provider { resolutionThread = Thread.currentThread(); throw IllegalStateException("credential-secret") },
            Provider { fixture.entitlement }, {},
        )
        val reply = port.requestCode()
        assertTrue(reply is ControlPlaneResult.Failure)
        assertNotSame(callerThread, resolutionThread)
        assertFalse(reply.toString().contains("credential-secret"))
    }

    @Test fun cancellationPropagatesWithoutApplyingResponse() = runBlocking {
        val fixture = Fixture(denial("unlicensed"))
        val port = ControlPlaneLicenseActivationPort(
            ControlPlaneClient(CallExecutor { throw CancellationException("cancelled") }),
            Provider { fixture.identity }, Provider { fixture.entitlement }, {},
        )
        try { port.activateDirect("secret"); fail("Cancellation must propagate") }
        catch (_: CancellationException) { assertNull(fixture.store.read().onlineDenial) }
    }

    private class Fixture(var payload: String) {
        val identity = InstallationCredentials(ID, CREDENTIAL)
        val store = InMemoryLocalEntitlementStateStore()
        val entitlement = LocalAppEntitlement(OfflineLeaseVerifier(emptyMap()), ID, "binding", store) {
            ClockReading(Instant.parse(TIME).epochSecond, 1000, "boot")
        }
        val requests = mutableListOf<Request>()
        var refreshed = 0
        val port = ControlPlaneLicenseActivationPort(ControlPlaneClient(CallExecutor { request ->
            requests += request
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(payload.toResponseBody("application/json".toMediaType())).build()
        }), Provider { identity }, Provider { entitlement }, { refreshed++ })
    }

    companion object {
        private const val ID = "12345678-1234-4234-8234-123456789abc"
        private val CREDENTIAL = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })
        private const val TIME = "2026-09-30T00:00:00Z"
        private fun denial(state: String): String {
            val metadata = if (state in setOf("suspended", "revoked", "expired", "not_started")) {
                val start = if (state == "not_started") "2026-10-01T00:00:00Z" else "2026-09-01T00:00:00Z"
                val end = if (state == "expired") "2026-09-29T00:00:00Z" else "2026-10-10T00:00:00Z"
                ",\"licenseId\":\"$ID\",\"licenseRevision\":1,\"startsAt\":\"$start\",\"endsAt\":\"$end\""
            } else ""
            return """{"decision":{"state":"$state"$metadata},"serverTime":"$TIME","refreshAfterSeconds":30}"""
        }
    }
}
