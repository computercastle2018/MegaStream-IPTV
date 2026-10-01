package com.MegaStream.app.controlplane

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlPlaneDtosTest {
    private val id = "12345678-1234-4234-8234-123456789abc"
    private val now = "2026-09-30T00:00:00Z"
    private val secret = "A".repeat(43)
    private val memory = MemorySnapshot(1, 2, 3, 4, 5, false)
    private val frame = DiagnosticFrame("com.example.Player\$Companion", "<init>", 42)

    private fun registration() = RegistrationRequest(
        id, secret, 31, "2.1.6", "Example", "TV", 27, "8.1",
        DeviceAbi.ARM64_V8A, "ar-SA", false, "com.megastream.app", ReleaseChannel.STABLE,
    )
    private fun event(payload: DiagnosticPayload) = DiagnosticEvent(id, id, 0, now, payload)
    private fun allowed() = EntitlementResponse(
        EntitlementDecision(EntitlementState.ALLOWED, id, 1, now, now, now), now, 60, "opaque-lease-secret",
    )
    private fun invalid(block: () -> Unit) {
        try { block() } catch (_: IllegalArgumentException) { return }
        throw AssertionError("Expected invalid input to be rejected")
    }

    @Test fun registrationUsesExactCamelCaseAndSnakeCaseEnums() {
        val request = registration()
        val encoded = Json.encodeToString(request)
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(setOf("installationId", "credential", "appVersionCode", "appVersionName", "manufacturer", "model", "androidApi", "androidRelease", "abi", "locale", "managedDevice", "packageName", "channel"), obj.keys)
        assertEquals(JsonPrimitive("arm64_v8a"), obj["abi"])
        assertEquals(JsonPrimitive("stable"), obj["channel"])
        assertEquals(request, Json.decodeFromString<RegistrationRequest>(encoded))
        invalid { Json.decodeFromString<RegistrationRequest>(encoded.replace("arm64_v8a", "future_abi")) }
        invalid { Json.decodeFromString<RegistrationRequest>(encoded.replace("\"androidApi\":27", "\"androidApi\":26")) }
    }

    @Test fun allNinePayloadsRoundTripWithoutTypeDiscriminator() {
        val fixtures = listOf(
            "app_started" to AppStartedPayload(31, "2.1.6"),
            "playback_started" to PlaybackStartedPayload(id, "News HD", SourceType.XTREAM_CODES, StreamType.HLS, PlaybackMode.LIVE),
            "playback_sample" to PlaybackSamplePayload(id, VideoCodec.H264, AudioCodec.AAC, VideoDecoder.HARDWARE, AudioDecoder.PLATFORM, 1920, 1080, 0, 1, 1000, 500, memory),
            "playback_problem" to PlaybackProblemPayload(id, PlaybackProblemCategory.HTTP, "http_error", 503, 1, memory),
            "memory_pressure" to MemoryPressurePayload(memory, TrimLevel.RUNNING_LOW),
            "crash" to CrashPayload("java.lang.IllegalStateException", listOf(frame)),
            "anr" to AnrPayload(AnrEvidence.WATCHDOG_SUSPECTED, 5000, listOf(frame)),
            "playback_ended" to PlaybackEndedPayload(id, PlaybackEndReason.CHANNEL_CHANGED, 15000),
            "app_ended" to AppEndedPayload(ExitReason.CLEAN_EXIT, ExitEvidence.REPORTED),
        )
        fixtures.forEach { (kind, payload) ->
            val original = event(payload)
            val encoded = Json.encodeToString(original)
            val obj = Json.parseToJsonElement(encoded).jsonObject
            assertEquals(setOf("eventId", "appSessionId", "sequence", "occurredAt", "kind", "payload"), obj.keys)
            assertEquals(JsonPrimitive(kind), obj["kind"])
            assertFalse(obj.getValue("payload").jsonObject.containsKey("type"))
            assertEquals(original, Json.decodeFromString<DiagnosticEvent>(encoded))
        }
    }

    @Test fun customEventSerializerRejectsMismatchUnknownKindsAndUnknownProperties() {
        val source = Json.encodeToString(event(AppStartedPayload(31, "2.1.6")))
        invalid { Json.decodeFromString<DiagnosticEvent>(source.replace("app_started", "crash")) }
        invalid { Json.decodeFromString<DiagnosticEvent>(source.replace("app_started", "future_event")) }
        val permissive = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        invalid { permissive.decodeFromString<DiagnosticEvent>(source.dropLast(1) + ",\"unexpected\":1}") }
        invalid { permissive.decodeFromString<DiagnosticEvent>(source.replace("\"appVersionCode\":31", "\"appVersionCode\":31,\"url\":\"secret\"")) }
        val playback = Json.encodeToString(event(PlaybackStartedPayload(id, null, SourceType.M3U, StreamType.HLS, PlaybackMode.LIVE)))
        invalid { permissive.decodeFromString<DiagnosticEvent>(playback.replace("\"hls\"", "\"future_stream\"")) }
    }

    @Test fun activationStatusUsesUnwrappedShapesAndRejectsAmbiguity() {
        val pending: ActivationStatusResponse = ActivationStatusResponse.Pending(ActivationPendingResponse(ActivationCodeStatus.PENDING, now, 5))
        val activated: ActivationStatusResponse = ActivationStatusResponse.Activated(allowed())
        for (value in listOf(pending, activated)) {
            val encoded = Json.encodeToString(value)
            assertFalse(encoded.contains("\"response\""))
            assertFalse(encoded.contains("\"type\""))
            assertEquals(value, Json.decodeFromString<ActivationStatusResponse>(encoded))
        }
        val encoded = Json.encodeToString(activated)
        invalid { Json.decodeFromString<ActivationStatusResponse>(encoded.dropLast(1) + ",\"status\":\"pending\"}") }
        invalid { Json.decodeFromString<ActivationStatusResponse>("{}") }
        invalid { Json.decodeFromString<ActivationStatusResponse>("{\"status\":\"approved\",\"serverTime\":\"$now\",\"retryAfterSeconds\":5}") }
    }

    @Test fun uuidsCredentialsTimestampsAndNumbersAreValidated() {
        invalid { registration().copy(installationId = id.uppercase()) }
        invalid { registration().copy(installationId = "1-1-1-1-1") }
        invalid { registration().copy(credential = secret.dropLast(1) + "B") }
        invalid { registration().copy(credential = secret + "=") }
        invalid { registration().copy(androidApi = 26) }
        invalid { registration().copy(appVersionCode = -1) }
        invalid { event(AppStartedPayload(1, "1")).copy(sequence = -1) }
        listOf("2026-02-30T00:00:00Z", "2026-09-30T00:00:00+00:00", "2026-09-30T00:00:60Z", "2026-09-30T24:00:00Z", "not-a-date").forEach {
            invalid { RegistrationResponse(id, it) }
        }
        assertEquals(now, RegistrationResponse(id, now).registeredAt)
        assertEquals(null, RecoveredExit(ExitReason.OS_EXIT, ExitEvidence.RECOVERED_OS).occurredAt)
        invalid { memory.copy(javaUsedBytes = -1) }
        invalid { Json.decodeFromString<MemorySnapshot>(Json.encodeToString(memory).replace("\"pssBytes\":4", "\"pssBytes\":-1")) }
    }

    @Test fun batchAndDiagnosticBoundsAreEnforcedAndSchemaIsAlwaysEncoded() {
        val e = event(AppStartedPayload(1, "1"))
        invalid { DiagnosticsBatchRequest(events = emptyList()) }
        invalid { DiagnosticsBatchRequest(events = List(51) { e }) }
        invalid { DiagnosticsBatchRequest(2, listOf(e)) }
        val batch = DiagnosticsBatchRequest(events = List(50) { e })
        assertEquals(batch, Json.decodeFromString<DiagnosticsBatchRequest>(Json.encodeToString(batch)))
        assertTrue(Json.encodeToString(batch).contains("\"schemaVersion\":1"))
        CrashPayload("java.lang.Exception", List(32) { frame })
        invalid { CrashPayload("java.lang.Exception", List(33) { frame }) }
        invalid { AnrPayload(AnrEvidence.OS_EXIT_REASON, frames = List(33) { frame }) }
        invalid { CrashPayload("A".repeat(121), emptyList()) }
        invalid { DiagnosticFrame("https://provider", "method") }
        invalid { DiagnosticFrame("java.lang.Exception", "error: password") }
        invalid { PlaybackStartedPayload(id, "A".repeat(121), SourceType.M3U, StreamType.HLS, PlaybackMode.LIVE) }
        listOf("https://provider/stream", "password=secret", "Bearer secret", "A".repeat(43)).forEach {
            invalid { PlaybackStartedPayload(id, it, SourceType.M3U, StreamType.HLS, PlaybackMode.LIVE) }
        }
        invalid { PlaybackProblemPayload(id, PlaybackProblemCategory.HTTP, "UpperCase", retryAttempt = 0) }
    }

    @Test fun entitlementLicenseAndLeaseRequirementsFollowDecision() {
        invalid { EntitlementDecision(EntitlementState.ALLOWED) }
        invalid { EntitlementDecision(EntitlementState.EXPIRED) }
        invalid { allowed().copy(lease = null) }
        invalid { EntitlementDecision(EntitlementState.UNLICENSED, offlineUntil = now) }
        val denied = EntitlementDecision(EntitlementState.UNLICENSED)
        assertEquals(denied, Json.decodeFromString<EntitlementDecision>("{\"state\":\"unlicensed\"}"))
        invalid { EntitlementResponse(denied, now, 60, "secret-lease") }
        EntitlementResponse(denied, now, 60)
        EntitlementDecision(EntitlementState.EXPIRED, id, 1, now, now)
    }

    @Test fun secretsNeverAppearInToStringIncludingWrappers() {
        val licenseKey = "license-key-secret"
        val code = "CODESECRET"
        val token = "poll-token-secret"
        val objects = listOf<Any>(
            registration(), ActivateRequest(licenseKey), ActivationCodePollRequest(code, token),
            ActivationCodeResponse(code, now, ActivationCodeStatus.PENDING, token),
            allowed(), ActivationStatusResponse.Activated(allowed()),
            ActivationStatusResponse.Pending(ActivationPendingResponse(ActivationCodeStatus.PENDING, now, 5)),
        )
        objects.forEach { value ->
            listOf(secret, licenseKey, code, token, "opaque-lease-secret").forEach {
                assertFalse("Secret leaked by ${value.javaClass.simpleName}", value.toString().contains(it))
            }
        }
    }

    @Test fun entitlementDevicePolicyDecodesClosedModesAndRemainsOptional() {
        val fixtures = listOf("off" to KioskMode.OFF, "playback" to KioskMode.PLAYBACK, "always" to KioskMode.ALWAYS)
        fixtures.forEach { (wireMode, expectedMode) ->
            val fixture = """{"decision":{"state":"unlicensed"},"serverTime":"2026-09-30T00:00:00Z","refreshAfterSeconds":60,"devicePolicy":{"kioskMode":"$wireMode","allowLocalExit":false}}"""
            val entitlement = Json.decodeFromString<EntitlementResponse>(fixture)
            assertEquals(DevicePolicy(expectedMode, false), entitlement.devicePolicy)
            assertEquals(Json.parseToJsonElement("""{"kioskMode":"$wireMode","allowLocalExit":false}"""),
                Json.parseToJsonElement(Json.encodeToString(entitlement)).jsonObject["devicePolicy"])
        }
        val noPolicy = """{"decision":{"state":"unlicensed"},"serverTime":"2026-09-30T00:00:00Z","refreshAfterSeconds":60}"""
        assertEquals(null, Json.decodeFromString<EntitlementResponse>(noPolicy).devicePolicy)
        invalid {
            Json.decodeFromString<EntitlementResponse>("""{"decision":{"state":"unlicensed"},"serverTime":"2026-09-30T00:00:00Z","refreshAfterSeconds":60,"devicePolicy":{"kioskMode":"future_mode","allowLocalExit":true}}""")
        }
    }

    @Test fun unknownRejectionTextIsDiscardedButNonStringsAreRejected() {
        val sensitive = "https://provider/?token=do-not-retain"
        val result = Json.decodeFromString<RejectedDiagnosticEvent>("{\"eventId\":\"$id\",\"code\":\"$sensitive\"}")
        assertEquals(DiagnosticRejectionCode.UNKNOWN, result.code)
        assertFalse(result.toString().contains(sensitive))
        assertFalse(Json.encodeToString(result).contains(sensitive))
        assertEquals(DiagnosticRejectionCode.INVALID_PAYLOAD, Json.decodeFromString<DiagnosticRejectionCode>("\"invalid_payload\""))
        invalid { Json.decodeFromString<RejectedDiagnosticEvent>("{\"eventId\":\"$id\",\"code\":42}") }
    }

    @Test fun lockedEntitlementAndPendingFixturesDecodeWithoutWrapperFields() {
        val entitlementJson = """
            {
              "decision": {
                "state":"allowed",
                "licenseId":"12345678-1234-4234-8234-123456789abc",
                "licenseRevision":7,
                "startsAt":"2026-09-01T00:00:00Z",
                "endsAt":"2027-09-01T00:00:00Z",
                "offlineUntil":"2026-10-03T00:00:00Z"
              },
              "serverTime":"2026-09-30T00:00:00Z",
              "refreshAfterSeconds":3600,
              "lease":"opaque.signed.lease",
              "updateCommand": {
                "commandId":"12345678-1234-4234-8234-123456789abc",
                "releaseId":"release-arm64-32",
                "versionCode":32,
                "versionName":"2.1.7",
                "mandatory":true,
                "installMode":"managed",
                "downloadUrl":"https://megastrem.megastation.uk/releases/32.apk",
                "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "signingCertificateSha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "sizeBytes":123456,
                "notes":"Security update"
              }
            }
        """.trimIndent()
        val entitlement = Json.decodeFromString<EntitlementResponse>(entitlementJson)
        assertEquals(EntitlementDecision(EntitlementState.ALLOWED, id, 7,
            "2026-09-01T00:00:00Z", "2027-09-01T00:00:00Z", "2026-10-03T00:00:00Z"), entitlement.decision)
        assertEquals(now, entitlement.serverTime)
        assertEquals(3600L, entitlement.refreshAfterSeconds)
        assertEquals("opaque.signed.lease", entitlement.lease)
        assertEquals(UpdateCommand(id, "release-arm64-32", 32, "2.1.7", true, InstallMode.MANAGED,
            "https://megastrem.megastation.uk/releases/32.apk", "a".repeat(64), "b".repeat(64),
            123456, "Security update"), entitlement.updateCommand)
        assertEquals(ActivationStatusResponse.Activated(entitlement),
            Json.decodeFromString<ActivationStatusResponse>(entitlementJson))
        val pendingJson = """{"status":"pending","serverTime":"2026-09-30T00:00:00Z","retryAfterSeconds":5}"""
        assertEquals(ActivationStatusResponse.Pending(ActivationPendingResponse(ActivationCodeStatus.PENDING, now, 5)),
            Json.decodeFromString<ActivationStatusResponse>(pendingJson))
    }

    @Test fun heartbeatWireContainsExactKeysAndTypedNestedSnapshots() {
        val heartbeat = HeartbeatRequest(id, 9, HeartbeatMode.PLAYBACK, 31, "2.1.6", true, memory,
            RecoveredExit(ExitReason.LOW_MEMORY_KILL, ExitEvidence.RECOVERED_OS, now),
            "com.megastream.app", ReleaseChannel.BETA)
        val encoded = Json.encodeToString(heartbeat)
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(setOf("appSessionId", "sequence", "mode", "appVersionCode", "appVersionName",
            "managedDevice", "memory", "recoveredExit", "packageName", "channel"), obj.keys)
        assertEquals(JsonPrimitive("com.megastream.app"), obj["packageName"])
        assertEquals(JsonPrimitive("beta"), obj["channel"])
        assertEquals(JsonPrimitive("playback"), obj["mode"])
        assertEquals(Json.parseToJsonElement("""{"javaUsedBytes":1,"javaMaxBytes":2,"nativeHeapBytes":3,"pssBytes":4,"availableSystemBytes":5,"lowMemory":false}"""), obj["memory"])
        assertEquals(Json.parseToJsonElement("""{"reason":"low_memory_kill","evidence":"recovered_os","occurredAt":"2026-09-30T00:00:00Z"}"""), obj["recoveredExit"])
        assertEquals(heartbeat, Json.decodeFromString<HeartbeatRequest>(encoded))
    }

    @Test fun updateCommandRejectsForeignUrlsAndAllowsBoundedReleaseIds() {
        val command = UpdateCommand(id, "release-arm64-32", 32, "2.1.7", false, InstallMode.PROMPT,
            "https://megastrem.megastation.uk/releases/32.apk?token=secret", "a".repeat(64), sizeBytes = 1024, notes = "Update")
        assertEquals(command, Json.decodeFromString<UpdateCommand>(Json.encodeToString(command)))
        assertFalse(command.toString().contains("token=secret"))
        val explicitPort = "https://megastrem.megastation.uk:443/releases/32.apk"
        assertEquals(explicitPort, command.copy(downloadUrl = explicitPort).downloadUrl)
        listOf("http://megastrem.megastation.uk/x", "https://evil.example/x", "https://megastrem.megastation.uk:/x", "https://user@megastrem.megastation.uk/x", "https://megastrem.megastation.uk/x#fragment").forEach {
            invalid { command.copy(downloadUrl = it) }
        }
    }
}
