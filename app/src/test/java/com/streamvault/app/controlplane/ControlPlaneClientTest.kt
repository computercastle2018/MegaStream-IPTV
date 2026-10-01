package com.MegaStream.app.controlplane

import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class ControlPlaneClientTest {
    @Test fun registrationIsAnonymousIdempotentAndChecksReturnedIdentity() {
        var captured: Request? = null
        val client = ControlPlaneClient(CallExecutor { request ->
            captured = request
            response(request, """{"installationId":"$ID","registeredAt":"$TIME"}""")
        })
        assertTrue(client.register(registration(), ID) is ControlPlaneResult.Success)
        assertEquals("/api/v1/installations/register", captured!!.url.encodedPath)
        assertEquals(ID, captured!!.header("Idempotency-Key"))
        assertNull(captured!!.header("Authorization"))
        assertTrue(body(captured!!).contains("\"channel\":\"stable\""))
        val mismatch = ControlPlaneClient(CallExecutor { response(it, """{"installationId":"00000000-0000-4000-8000-000000000000","registeredAt":"$TIME"}""") })
        assertFailure("invalid_response", mismatch.register(registration(), ID))
    }

    @Test fun authenticatedEndpointsUseOnlyFixedHostAndExpectedWireBodies() {
        val captured = mutableListOf<Request>()
        val client = ControlPlaneClient(CallExecutor { request ->
            captured += request
            when (request.url.encodedPath) {
                "/api/v1/activation-codes/request" -> response(request, """{"code":"CODE","expiresAt":"$TIME","status":"pending","pollToken":"poll-secret"}""")
                "/api/v1/activation-codes/status" -> response(request, """{"status":"pending","serverTime":"$TIME","retryAfterSeconds":5}""")
                "/api/v1/providers/assignments" -> response(request, """{"serverRevision":0,"assignments":[],"tombstones":[]}""")
                "/api/v1/devices/heartbeat" -> response(request, entitlementWithPolicy("""{"kioskMode":"off","allowLocalExit":true}"""))
                else -> response(request, ENTITLEMENT)
            }
        })
        assertTrue(client.activate(CREDENTIAL, "license-secret") is ControlPlaneResult.Success)
        assertTrue(client.requestActivationCode(CREDENTIAL) is ControlPlaneResult.Success)
        assertTrue(client.pollActivationCode(CREDENTIAL, "CODE", "poll-secret") is ControlPlaneResult.Success)
        assertTrue(client.heartbeat(CREDENTIAL, heartbeat()) is ControlPlaneResult.Success)
        assertTrue(client.getProviderAssignments(CREDENTIAL, Long.MAX_VALUE) is ControlPlaneResult.Success)
        captured.forEach {
            assertEquals("megastrem.megastation.uk", it.url.host)
            assertEquals("https", it.url.scheme)
            assertEquals("Bearer $CREDENTIAL", it.header("Authorization"))
            assertNull(it.header("Idempotency-Key"))
        }
        assertEquals("{}", body(captured[1]))
        assertEquals("""{"licenseKey":"license-secret"}""", body(captured[0]))
        assertEquals("""{"code":"CODE","pollToken":"poll-secret"}""", body(captured[2]))
        assertEquals("GET", captured.last().method)
        assertEquals(Long.MAX_VALUE.toString(), captured.last().url.queryParameter("afterRevision"))
    }

    @Test fun heartbeatRequiresPresentStrictDevicePolicyButActivationDoesNot() {
        listOf(ENTITLEMENT, entitlementWithPolicy("null"),
            entitlementWithPolicy("""{"kioskMode":"future","allowLocalExit":true}"""),
            entitlementWithPolicy("""{"kioskMode":"off"}"""),
            entitlementWithPolicy("""{"kioskMode":"off","allowLocalExit":true,"unexpected":true}""")
        ).forEach { payload ->
            val client = ControlPlaneClient(CallExecutor { response(it, payload) })
            assertFailure("invalid_response", client.heartbeat(CREDENTIAL, heartbeat()))
        }
        val activationClient = ControlPlaneClient(CallExecutor { response(it, ENTITLEMENT) })
        assertTrue(activationClient.activate(CREDENTIAL, "key") is ControlPlaneResult.Success)
    }

    private fun entitlementWithPolicy(policyJson: String): String =
        ENTITLEMENT.dropLast(1) + ",\"devicePolicy\":$policyJson}"

    @Test fun unitStatusEndpointsAcceptBoundedNonJsonSuccess() {
        val paths = mutableListOf<String>()
        val client = ControlPlaneClient(CallExecutor { paths += it.url.encodedPath; response(it, "", 204, null) })
        assertTrue(client.reportUpdateCommandStatus(CREDENTIAL, ID, UpdateCommandStatusRequest(UpdateCommandStatus.DOWNLOADING)) is ControlPlaneResult.Success)
        assertTrue(client.reportProviderAssignmentStatus(CREDENTIAL, ID, ProviderAssignmentStatusRequest(1, ProviderAssignmentState.ACTIVE)) is ControlPlaneResult.Success)
        assertEquals(listOf("/api/v1/updates/commands/$ID/status", "/api/v1/providers/assignments/$ID/status"), paths)
    }

    @Test fun invalidCredentialsIdsAndRevisionNeverReachNetwork() {
        val client = ControlPlaneClient(CallExecutor { throw AssertionError("Network must not execute") })
        assertFailure("invalid_request", client.activate("bad", "key"))
        assertFailure("invalid_request", client.register(registration(), "1-1-1-1-1"))
        assertFailure("invalid_request", client.getProviderAssignments(CREDENTIAL, -1))
        assertFailure("invalid_request", client.reportUpdateCommandStatus(CREDENTIAL, "../escape", UpdateCommandStatusRequest(UpdateCommandStatus.FAILED)))
    }

    @Test fun redirectsPriorChainsAndAlteredResponseUrlsAreRejected() {
        val transforms: List<(Request) -> Response> = listOf(
            { response(it, ENTITLEMENT, 302) },
            { response(it, ENTITLEMENT).newBuilder().priorResponse(response(it, "", 302).newBuilder().body(null).build()).build() },
            { response(it.newBuilder().url("https://evil.example/path").build(), ENTITLEMENT) },
            { response(it.newBuilder().url(ControlPlaneUrlPolicy.ORIGIN + "/other").build(), ENTITLEMENT) },
        )
        transforms.forEach { transform ->
            assertFailure("unsafe_response", ControlPlaneClient(CallExecutor(transform)).activate(CREDENTIAL, "key"))
        }
    }

    @Test fun malformedUnknownFieldsAndWrongMediaTypesFailClosed() {
        listOf("not json", ENTITLEMENT.dropLast(1) + ",\"unexpected\":true}", ENTITLEMENT.replace("unlicensed", "new_state")).forEach { payload ->
            assertFailure("invalid_response", ControlPlaneClient(CallExecutor { response(it, payload) }).activate(CREDENTIAL, "key"))
        }
        listOf(null, "text/html").forEach { type ->
            assertFailure("invalid_response", ControlPlaneClient(CallExecutor { response(it, ENTITLEMENT, type = type) }).activate(CREDENTIAL, "key"))
        }
    }

    @Test fun advertisedAndUnknownLengthBodiesAreBoundedAndClosed() {
        listOf(true, false).forEach { knownLength ->
            var closed = false
            val buffer = Buffer().writeUtf8("x".repeat(1_048_577))
            val source = object : okio.ForwardingSource(buffer) {
                override fun close() { closed = true; super.close() }
            }
            val buffered = source.buffer()
            val large = object : ResponseBody() {
                override fun contentType() = "application/json".toMediaType()
                override fun contentLength() = if (knownLength) 1_048_577L else -1L
                override fun source() = buffered
            }
            val client = ControlPlaneClient(CallExecutor { response(it, "").newBuilder().body(large).build() })
            assertFailure("payload_too_large", client.activate(CREDENTIAL, "key"))
            assertTrue(closed)
        }
    }

    @Test fun missingResponseBodyReturnsFixedFailureWithoutCloseException() {
        val client = ControlPlaneClient(CallExecutor { response(it, "").newBuilder().body(null).build() })
        assertFailure("invalid_response", client.activate(CREDENTIAL, "key"))
    }

    @Test fun networkExceptionsAndProblemEchoesCannotLeakSecrets() {
        assertFailure("network_error", ControlPlaneClient(CallExecutor { throw IOException("password=secret") }).activate(CREDENTIAL, "key"))
        val client = ControlPlaneClient(CallExecutor { response(it,
            """{"title":"Invalid request","status":400,"code":"invalid_request","traceId":"$ID"}""", 400, "application/problem+json") })
        val result = client.activate(CREDENTIAL, ID) as ControlPlaneResult.Failure
        assertEquals("invalid_request", result.error.code)
        assertNull(result.error.traceId)
        assertFalse(result.toString().contains(ID))
    }

    @Test fun diagnosticsRevalidateMutatedEventAndFrameListsBeforeSending() {
        val client = ControlPlaneClient(CallExecutor { throw AssertionError("Mutated diagnostics must not send") })
        val events = mutableListOf(event(AppStartedPayload(31, "2.1.6")))
        val request = DiagnosticsBatchRequest(events = events)
        repeat(50) { events += events[0] }
        assertFailure("invalid_request", client.diagnostics(CREDENTIAL, request))
        val frames = mutableListOf(DiagnosticFrame("Example", "run"))
        val crash = DiagnosticsBatchRequest(events = listOf(event(CrashPayload("ExampleException", frames))))
        repeat(32) { frames += frames[0] }
        assertFailure("invalid_request", client.diagnostics(CREDENTIAL, crash))
    }

    @Test fun diagnosticsSendSchemaVersionAndDecodeAcknowledgments() {
        var payload = ""
        val client = ControlPlaneClient(CallExecutor {
            payload = body(it)
            response(it, """{"acceptedEventIds":["$ID"],"duplicateEventIds":[],"rejected":[],"serverTime":"$TIME"}""")
        })
        val result = client.diagnostics(CREDENTIAL, DiagnosticsBatchRequest(events = listOf(event(AppStartedPayload(31, "2.1.6")))))
        assertEquals(listOf(ID), (result as ControlPlaneResult.Success).value.acceptedEventIds)
        assertTrue(payload.contains("\"schemaVersion\":1"))
        assertTrue(payload.contains("\"kind\":\"app_started\""))
    }

    private fun registration() = RegistrationRequest(ID, CREDENTIAL, 31, "2.1.6", "Example", "TV", 35, "15", DeviceAbi.ARM64_V8A, "en-US", false, "com.megastream.app", ReleaseChannel.STABLE)
    private fun heartbeat() = HeartbeatRequest(ID, 1, HeartbeatMode.FOREGROUND, 31, "2.1.6", false, packageName = "com.megastream.app", channel = ReleaseChannel.STABLE)
    private fun event(payload: DiagnosticPayload) = DiagnosticEvent(ID, ID, 1, TIME, payload)
    private fun body(request: Request): String = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
    private fun response(request: Request, body: String, code: Int = 200, type: String? = "application/json"): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("response")
        .body(body.toResponseBody(type?.toMediaType())).build()
    private fun assertFailure(code: String, result: ControlPlaneResult<*>) {
        assertTrue(result.toString(), result is ControlPlaneResult.Failure)
        assertEquals(code, (result as ControlPlaneResult.Failure).error.code)
        assertFalse(result.toString().contains("password=secret"))
    }
    private companion object {
        const val ID = "abcdef12-3456-4789-abcd-0123456789ab"
        const val TIME = "2026-09-30T00:00:00Z"
        val CREDENTIAL = "A".repeat(43)
        val ENTITLEMENT = """{"decision":{"state":"unlicensed"},"serverTime":"$TIME","refreshAfterSeconds":3600}"""
    }
}
