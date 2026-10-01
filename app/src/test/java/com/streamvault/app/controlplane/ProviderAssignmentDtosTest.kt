package com.MegaStream.app.controlplane

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProviderAssignmentDtosTest {
    private val assignmentId = "b0636ca1-561c-400d-88e4-c42e7e686f9a"
    private val profileId = "b0636ca1-561c-400d-88e4-c42e7e686f9b"
    private val timestamp = "2026-09-30T04:00:00Z"
    private val permissiveJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val fixtures = listOf(
        "xtream_codes" to """{
            "serverUrl":"http://provider.example:8080/",
            "username":"secret-user","password":"secret-password",
            "epgSyncMode":"background","fastSyncEnabled":true,"liveSyncMode":"stream_all",
            "epgUrl":"https://provider.example/epg?token=secret-epg",
            "httpUserAgent":"secret-agent","httpHeaders":{"Authorization":"secret-header"}
        }""",
        "m3u" to """{
            "serverUrl":"http://provider.example/",
            "m3uUrl":"http://user:password@provider.example/list?token=secret-playlist",
            "epgSyncMode":"upfront","vodClassificationEnabled":true
        }""",
        "stalker_portal" to """{
            "serverUrl":"https://provider.example/",
            "portalUrl":"http://provider.example/portal","stalkerMacAddress":"00:1A:79:11:22:33",
            "deviceProfile":"secret-device","timezone":"Asia/Riyadh","locale":"ar"
        }""",
    )

    private fun assignmentFixture(type: String, configuration: String): String = """{
        "assignmentId":"$assignmentId","profileId":"$profileId","assignmentRevision":3,"profileRevision":2,
        "policy":"optional","type":"$type","displayName":"secret-display",
        "configuration":$configuration
    }"""

    private fun fixtureAssignment(index: Int = 0): ProviderAssignment = fixtures[index].let { (type, configuration) ->
        ProviderAssignmentJson.decodeFromString(assignmentFixture(type, configuration))
    }

    @Test
    fun allConfigurationsRoundTripWithOnlyTheOuterTypeDiscriminator() {
        fixtures.forEachIndexed { index, (type, configuration) ->
            val assignment = ProviderAssignmentJson.decodeFromString<ProviderAssignment>(assignmentFixture(type, configuration))
            assertEquals(3L, assignment.assignmentRevision)
            assertEquals(2L, assignment.profileRevision)
            val encoded = ProviderAssignmentJson.encodeToString(assignment)
            val objectValue = ProviderAssignmentJson.parseToJsonElement(encoded).jsonObject
            assertEquals(type, objectValue.getValue("type").jsonPrimitive.content)
            assertFalse(objectValue.getValue("configuration").jsonObject.containsKey("type"))
            assertEquals(assignment, ProviderAssignmentJson.decodeFromString<ProviderAssignment>(encoded))
            when (index) {
                0 -> {
                    val config = assignment.configuration as XtreamProviderConfiguration
                    assertEquals("secret-password", config.password)
                    assertEquals(mapOf("Authorization" to "secret-header"), config.httpHeaders)
                    assertEquals(ProviderLiveSyncMode.STREAM_ALL, config.liveSyncMode)
                }
                1 -> assertEquals("http://user:password@provider.example/list?token=secret-playlist",
                    (assignment.configuration as M3uProviderConfiguration).m3uUrl)
                2 -> assertEquals("00:1A:79:11:22:33",
                    (assignment.configuration as StalkerProviderConfiguration).stalkerMacAddress)
            }
        }
    }

    @Test
    fun responseRoundTripPreservesAssignmentsTombstonesAndLongRevisions() {
        val response = ProviderAssignmentsResponse(Long.MAX_VALUE,
            fixtures.indices.map { fixtureAssignment(it) },
            listOf(ProviderAssignmentTombstone(assignmentId, Long.MAX_VALUE, timestamp)))
        assertEquals(response, ProviderAssignmentJson.decodeFromString<ProviderAssignmentsResponse>(
            ProviderAssignmentJson.encodeToString(response)))
        assertEquals(ProviderAssignmentsResponse(0, emptyList(), emptyList()),
            ProviderAssignmentJson.decodeFromString<ProviderAssignmentsResponse>(
                """{"serverRevision":0,"assignments":[],"tombstones":[]}"""))
    }

    @Test
    fun statusRoundTripsAllStatesAndSafeErrorCodesIncludingUnlimitedConnections() {
        ProviderAssignmentState.values().forEach { state ->
            val status = ProviderAssignmentStatusRequest(1, state, providerReportedMaxConnections = 0)
            assertEquals(status, ProviderAssignmentJson.decodeFromString<ProviderAssignmentStatusRequest>(
                ProviderAssignmentJson.encodeToString(status)))
        }
        ProviderAssignmentSafeErrorCode.values().forEach { code ->
            val status = ProviderAssignmentStatusRequest(3, ProviderAssignmentState.ERROR, code, timestamp, 1)
            assertEquals(status, ProviderAssignmentJson.decodeFromString<ProviderAssignmentStatusRequest>(
                ProviderAssignmentJson.encodeToString(status)))
        }
        assertEquals(ProviderAssignmentStatusRequest(1, ProviderAssignmentState.RECEIVED),
            ProviderAssignmentJson.decodeFromString<ProviderAssignmentStatusRequest>(
                """{"profileRevision":1,"state":"received"}"""))
    }

    @Test
    fun unknownKeysAndNestedDiscriminatorsAreRejectedWithPermissiveCaller() {
        val base = ProviderAssignmentJson.parseToJsonElement(assignmentFixture(fixtures[0].first, fixtures[0].second)).jsonObject
        val badAssignments = listOf(
            JsonObject(base + ("unexpected" to JsonPrimitive("secret"))),
            JsonObject(base + ("revision" to JsonPrimitive(3))),
            JsonObject(base + ("configuration" to JsonObject(base.getValue("configuration").jsonObject +
                ("type" to JsonPrimitive("xtream_codes"))))),
            JsonObject(base + ("configuration" to JsonObject(base.getValue("configuration").jsonObject +
                ("unexpected" to JsonPrimitive("secret"))))),
        )
        badAssignments.forEach { value -> rejects { permissiveJson.decodeFromString<ProviderAssignment>(value.toString()) } }
        rejects { permissiveJson.decodeFromString<ProviderAssignmentsResponse>(
            """{"serverRevision":0,"assignments":[],"tombstones":[],"unexpected":true}""") }
        rejects { permissiveJson.decodeFromString<ProviderAssignmentTombstone>(
            """{"assignmentId":"$assignmentId","assignmentRevision":1,"revokedAt":"$timestamp","unexpected":true}""") }
        rejects { permissiveJson.decodeFromString<ProviderAssignmentTombstone>(
            """{"assignmentId":"$assignmentId","assignmentRevision":1,"revision":1,"revokedAt":"$timestamp"}""") }
        rejects { permissiveJson.decodeFromString<ProviderAssignmentStatusRequest>(
            """{"profileRevision":1,"state":"active","unexpected":true}""") }
        fixtures.forEach { (type, config) ->
            val extra = JsonObject(ProviderAssignmentJson.parseToJsonElement(config).jsonObject + ("type" to JsonPrimitive(type)))
            rejects {
                when (type) {
                    "xtream_codes" -> permissiveJson.decodeFromString<XtreamProviderConfiguration>(extra.toString())
                    "m3u" -> permissiveJson.decodeFromString<M3uProviderConfiguration>(extra.toString())
                    else -> permissiveJson.decodeFromString<StalkerProviderConfiguration>(extra.toString())
                }
            }
        }
    }

    @Test
    fun unknownEnumsAndMismatchedConfigurationsAreRejectedWithPermissiveCaller() {
        val fixture = assignmentFixture(fixtures[0].first, fixtures[0].second)
        listOf(
            fixture.replace("\"optional\"", "\"OPTIONAL\""),
            fixture.replace("\"xtream_codes\"", "\"future_type\""),
            fixture.replace("\"background\"", "\"later\""),
            fixture.replace("\"stream_all\"", "\"everything\""),
            assignmentFixture("m3u", fixtures[0].second),
            assignmentFixture("stalker", fixtures[2].second),
        ).forEach { value -> rejects { permissiveJson.decodeFromString<ProviderAssignment>(value) } }
        listOf(
            """{"profileRevision":1,"state":"future_state"}""",
            """{"profileRevision":1,"state":"error","safeErrorCode":"raw secret exception"}""",
        ).forEach { value -> rejects { permissiveJson.decodeFromString<ProviderAssignmentStatusRequest>(value) } }
    }

    @Test
    fun requiredFieldsCannotBeOmittedOrCoerced() {
        val fixture = ProviderAssignmentJson.parseToJsonElement(assignmentFixture(fixtures[0].first, fixtures[0].second)).jsonObject
        fixture.keys.forEach { missing ->
            rejects { permissiveJson.decodeFromString<ProviderAssignment>(JsonObject(fixture - missing).toString()) }
        }
        fixtures.forEach { (type, configuration) ->
            val config = ProviderAssignmentJson.parseToJsonElement(configuration).jsonObject
            val optional = setOf("epgUrl", "httpUserAgent", "httpHeaders", "deviceProfile", "timezone", "locale")
            (config.keys - optional).forEach { missing ->
                rejects { permissiveJson.decodeFromString<ProviderAssignment>(assignmentFixture(type, JsonObject(config - missing).toString())) }
            }
        }
        listOf("{\"serverRevision\":0}", "{\"assignments\":[],\"tombstones\":[]}")
            .forEach { rejects { permissiveJson.decodeFromString<ProviderAssignmentsResponse>(it) } }
    }

    @Test
    fun identifiersRevisionsAndConnectionCountsAreValidated() {
        val assignment = fixtureAssignment()
        listOf("", "not-a-uuid", "1-1-1-1-1", "$assignmentId-extra", assignmentId.uppercase()).forEach { value ->
            rejects { assignment.copy(assignmentId = value) }
            rejects { assignment.copy(profileId = value) }
            rejects { ProviderAssignmentTombstone(value, 1, timestamp) }
        }
        listOf(0L, -1L).forEach { revision ->
            rejects { assignment.copy(assignmentRevision = revision) }
            rejects { assignment.copy(profileRevision = revision) }
            rejects { ProviderAssignmentTombstone(assignmentId, revision, timestamp) }
            rejects { ProviderAssignmentStatusRequest(revision, ProviderAssignmentState.ACTIVE) }
        }
        rejects { ProviderAssignmentsResponse(-1, emptyList(), emptyList()) }
        rejects { ProviderAssignmentStatusRequest(1, ProviderAssignmentState.ACTIVE, providerReportedMaxConnections = -1) }
        rejects { ProviderAssignmentJson.decodeFromString<ProviderAssignmentsResponse>(
            """{"serverRevision":-1,"assignments":[],"tombstones":[]}""") }
    }

    @Test
    fun utcTimestampsRequireValidCalendarAndTime() {
        listOf(timestamp, "2024-02-29T23:59:59.123456789Z").forEach { value ->
            assertEquals(value, ProviderAssignmentTombstone(assignmentId, 1, value).revokedAt)
        }
        listOf("", "2026-09-30", "2026-09-30T04:00:00", "2026-09-30T04:00:00+03:00",
            "2026-02-30T04:00:00Z", "2026-09-30T24:00:00Z", "2026-09-30T04:00:60Z",
            "2026-09-30T04:00:00+00:00", "2026-09-30t04:00:00z", "2026-09-30T04:00:00.1234567890Z").forEach { value ->
            rejects { ProviderAssignmentTombstone(assignmentId, 1, value) }
            rejects { ProviderAssignmentStatusRequest(1, ProviderAssignmentState.ACTIVE, providerReportedExpiresAt = value) }
        }
    }

    @Test
    fun providerUrlsAcceptHttpAndHttpsWithoutCredentialRewriting() {
        val config = fixtureAssignment().configuration as XtreamProviderConfiguration
        listOf("http://host.example/", "https://host.example/", "http://user:pass@host.example:8080/path?token=secret").forEach { url ->
            val copied = config.copy(serverUrl = url, epgUrl = url)
            val decoded = ProviderAssignmentJson.decodeFromString<XtreamProviderConfiguration>(ProviderAssignmentJson.encodeToString(copied))
            assertEquals(url, decoded.serverUrl)
            assertEquals(url, decoded.epgUrl)
            assertEquals("secret-user", decoded.username)
        }
        listOf("", "/relative", "ftp://host.example", "https:///missing-host", "http://bad host").forEach { url ->
            rejects { config.copy(serverUrl = url) }
            rejects { config.copy(epgUrl = url) }
            rejects { (fixtureAssignment(1).configuration as M3uProviderConfiguration).copy(m3uUrl = url) }
            rejects { (fixtureAssignment(2).configuration as StalkerProviderConfiguration).copy(portalUrl = url) }
        }
    }

    @Test
    fun headerBoundariesAndInjectionCharactersAreEnforced() {
        val config = fixtureAssignment().configuration as XtreamProviderConfiguration
        val headers = (1..20).associate { "X-Header-$it" to "value" }
        assertEquals(headers, config.copy(httpHeaders = headers).httpHeaders)
        val boundaryHeader = mapOf("A".repeat(64) to "v".repeat(512))
        assertEquals(boundaryHeader, config.copy(httpHeaders = boundaryHeader).httpHeaders)
        assertEquals(emptyMap<String, String>(), config.copy(httpHeaders = emptyMap()).httpHeaders)
        val invalid = listOf(headers + ("X-Header-21" to "value"), mapOf("A".repeat(65) to "v"),
            mapOf("X" to "v".repeat(513)), mapOf("" to "v"), mapOf("Bad Name" to "v"), mapOf("Bad:Name" to "v")) +
            listOf('\r', '\n', '\u0000').flatMap { char -> listOf(mapOf("X${char}Header" to "v"), mapOf("X" to "secret${char}value")) }
        invalid.forEach { headersValue -> rejects { config.copy(httpHeaders = headersValue) } }
        rejects { ProviderAssignmentJson.decodeFromString<XtreamProviderConfiguration>(
            fixtures[0].second.replace("{\"Authorization\":\"secret-header\"}", "\"not-an-object\"")) }
        rejects { ProviderAssignmentJson.decodeFromString<XtreamProviderConfiguration>(
            fixtures[0].second.replace("\"secret-header\"", "123")) }
    }

    @Test
    fun secretBearingObjectsHaveFixedRedactedStringRepresentations() {
        fixtures.indices.forEach { index ->
            val assignment = fixtureAssignment(index)
            assertEquals("ProviderAssignmentConfiguration([REDACTED])", assignment.configuration.toString())
            assertEquals("ProviderAssignment([REDACTED])", assignment.toString())
            assertEquals("ProviderAssignmentsResponse([REDACTED])",
                ProviderAssignmentsResponse(1, listOf(assignment), emptyList()).toString())
        }
        assertEquals("ProviderAssignmentStatusRequest([REDACTED])",
            ProviderAssignmentStatusRequest(1, ProviderAssignmentState.ERROR,
                ProviderAssignmentSafeErrorCode.AUTHENTICATION_FAILED, timestamp, 1).toString())
        val config = fixtureAssignment().configuration as XtreamProviderConfiguration
        try {
            config.copy(serverUrl = "secret invalid URL")
            fail("Expected invalid URL rejection")
        } catch (error: IllegalArgumentException) {
            assertEquals("Invalid provider URL", error.message)
            assertTrue(error.cause == null)
        }
    }

    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("Expected provider payload rejection")
        } catch (_: SerializationException) {
            // Never log parse exceptions: malformed input can contain provider secrets.
        } catch (_: IllegalArgumentException) {
            // Constructor validation is intentionally supported without serialization.
        }
    }
}
