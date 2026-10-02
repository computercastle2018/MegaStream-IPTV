package com.MegaStream.app.controlplane

import com.MegaStream.domain.model.Provider
import com.MegaStream.domain.model.ProviderStatus
import com.MegaStream.domain.model.ProviderType
import com.MegaStream.domain.manager.ProviderCredentials
import com.MegaStream.domain.repository.ProviderRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.verify
import org.mockito.kotlin.never
import org.junit.Assert.*
import org.junit.Test

class LocalSubscriptionsTest {
    @Test fun snapshotContainsOnlyMetadataAndKeepsArabicLabels() {
        val provider = provider().copy(name = "اشتراك الرياضة")
        val row = LocalSubscription.from(provider)
        assertEquals(provider.name, row.name)
        assertEquals("xtream_codes", row.type)
        assertEquals("active", row.status)
        assertTrue(row.enabled)
        assertEquals(1900000000000L, row.expiresAt)
        val body = Json.encodeToString(LocalSubscriptionsRequest(listOf(row)))
        listOf("provider.invalid", "user-secret", "password-secret", "username", "password", "serverUrl", "m3uUrl", "epgUrl", "httpHeaders").forEach {
            assertFalse("Must not transmit $it", body.contains(it))
        }
    }

    @Test fun pastedSecretsInLabelsAreRemovedBeforeTransmission() {
        listOf("https://provider.invalid/get.php?username=user&password=secret", "user-secret subscription", "password-secret", "www.provider.invalid", "name\nsecret").forEach {
            assertEquals("Subscription 7", LocalSubscription.from(provider().copy(name = it)).name)
        }
    }

    @Test fun transportUsesDeviceCredentialAndEmptySnapshotRemovesDeletedProviders() {
        var sent: okhttp3.Request? = null
        val client = ControlPlaneClient(CallExecutor { request ->
            sent = request
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(204).message("No Content").build()
        })
        val credential = "A".repeat(43)
        assertTrue(client.reportLocalSubscriptions(credential, LocalSubscriptionsRequest(emptyList())) is ControlPlaneResult.Success)
        assertEquals("/api/v1/devices/subscriptions", sent!!.url.encodedPath)
        assertEquals("megastrem.megastation.uk", sent!!.url.host)
        assertEquals("Bearer $credential", sent!!.header("Authorization"))
        val buffer = okio.Buffer()
        sent!!.body!!.writeTo(buffer)
        assertEquals("{\"subscriptions\":[]}", buffer.readUtf8())
    }

    @Test fun duplicateAndOversizeSnapshotsAreRejected() {
        val row = LocalSubscription.from(provider())
        assertThrows(IllegalArgumentException::class.java) { LocalSubscriptionsRequest(listOf(row, row)) }
        assertThrows(IllegalArgumentException::class.java) { LocalSubscriptionsRequest((1L..101L).map { row.copy(localId = it) }) }
    }

    @Test fun legacySummaryPayloadRetainsUnknownCredentialsAndStartDate() {
        val wire = """{"subscriptions":[{"localId":7,"name":"Sports","type":"xtream_codes","enabled":true,"status":"active","expiresAt":null,"maxConnections":1}]}"""
        val row = Json.decodeFromString<LocalSubscriptionsRequest>(wire).subscriptions.single()
        assertNull(row.credentials)
        assertNull(row.startedAt)
        assertEquals(7L, row.localId)
    }

    @Test fun credentialsUseAgreedNestedShapeAndRedactedDiagnostics() {
        val xtream = LocalSubscription.from(provider().copy(httpHeaders = "Authorization: Bearer header-secret",
            epgUrl = "https://provider.invalid/epg.xml", httpUserAgent = "ExamplePlayer"), clearCredentials())
        val m3u = LocalSubscription.from(provider().copy(id = 8, type = ProviderType.M3U,
            m3uUrl = "https://provider.invalid/list.m3u?token=playlist-secret"))
        val stalker = LocalSubscription.from(provider().copy(id = 9, type = ProviderType.STALKER_PORTAL,
            stalkerMacAddress = "00:11:22:33:44:55", stalkerDeviceProfile = "MAG254"))
        val request = LocalSubscriptionsRequest(listOf(xtream, m3u, stalker))
        val rows = Json.parseToJsonElement(Json.encodeToString(request)).jsonObject.getValue("subscriptions").jsonArray
        val config = rows[0].jsonObject.getValue("credentials").jsonObject
        assertEquals("https://provider.invalid", config.getValue("serverUrl").jsonPrimitive.content)
        assertEquals("user-secret", config.getValue("xtream").jsonObject.getValue("username").jsonPrimitive.content)
        assertEquals("password-secret", config.getValue("xtream").jsonObject.getValue("password").jsonPrimitive.content)
        assertEquals("Bearer header-secret", config.getValue("httpHeaders").jsonObject.getValue("Authorization").jsonPrimitive.content)
        assertEquals("ExamplePlayer", config.getValue("httpUserAgent").jsonPrimitive.content)
        assertEquals("https://provider.invalid/epg.xml", config.getValue("epgUrl").jsonPrimitive.content)
        assertEquals(m3u.credentials!!.m3u!!.m3uUrl, rows[1].jsonObject.getValue("credentials").jsonObject.getValue("m3u").jsonObject.getValue("m3uUrl").jsonPrimitive.content)
        assertEquals("00:11:22:33:44:55", rows[2].jsonObject.getValue("credentials").jsonObject.getValue("stalker").jsonObject.getValue("stalkerMacAddress").jsonPrimitive.content)
        val diagnostics = listOf(request, xtream, xtream.credentials, xtream.credentials!!.xtream,
            m3u.credentials, m3u.credentials!!.m3u, stalker.credentials, stalker.credentials!!.stalker).joinToString()
        listOf("password-secret", "user-secret", "playlist-secret", "header-secret", "provider.invalid", "00:11:22:33:44:55").forEach {
            assertFalse(diagnostics.contains(it))
        }
    }

    @Test fun uploaderUsesSupportedCredentialGetterAndIncludesDisabledProviders() = runBlocking<Unit> {
        val repository = repository(listOf(provider().copy(password = "enc:v1:opaque", isActive = false,
            status = ProviderStatus.DISABLED)), mapOf(7L to clearCredentials()))
        var sent: Request? = null
        val client = ControlPlaneClient(CallExecutor { sent = it; response(it) })
        assertTrue(LocalSubscriptionsUploader(repository, client, identity()).upload() is ControlPlaneResult.Success)
        val wire = body(requireNotNull(sent))
        assertTrue(wire.contains("password-secret"))
        assertFalse(wire.contains("enc:v1:opaque"))
        assertFalse(wire.contains("Bearer ${identity().credential}"))
        val row = Json.parseToJsonElement(wire).jsonObject.getValue("subscriptions").jsonArray.single().jsonObject
        assertEquals("false", row.getValue("enabled").jsonPrimitive.content)
        assertEquals("disabled", row.getValue("status").jsonPrimitive.content)
        assertEquals("https", sent!!.url.scheme)
        assertEquals("megastrem.megastation.uk", sent!!.url.host)
        assertEquals("/api/v1/devices/subscriptions", sent!!.url.encodedPath)
        assertEquals("Bearer ${identity().credential}", sent!!.header("Authorization"))
        verify(repository).getProviderCredentials(7L)
        verify(repository, never()).getAllProviderCredentials()
    }

    @Test fun missingUnreadableAndChangedAccountsKeepCompleteSummaries() = runBlocking {
        val scenarios = listOf(
            repository(listOf(provider()), emptyMap()),
            repository(listOf(provider()), mapOf(7L to clearCredentials().copy(serverUrl = "https://other.invalid"))),
            repository(listOf(provider()), mapOf(7L to clearCredentials().copy(username = "changed-user"))),
            repository(listOf(provider()), emptyMap()).also {
                whenever(it.getProviderCredentials(7L)).thenThrow(SecurityException("secret error"))
            },
        )
        for (repository in scenarios) {
            var sent: Request? = null
            val client = ControlPlaneClient(CallExecutor { sent = it; response(it) })
            assertTrue(LocalSubscriptionsUploader(repository, client, identity()).upload() is ControlPlaneResult.Success)
            val rows = Json.parseToJsonElement(body(requireNotNull(sent))).jsonObject.getValue("subscriptions").jsonArray
            assertEquals(1, rows.size)
            rows.forEach { assertEquals("null", it.jsonObject["credentials"]?.toString() ?: "null") }
            assertFalse(body(sent!!).contains("password-secret"))
        }
    }

    @Test fun duplicateAccountsUseEachProviderIdAndUnreadableRowDoesNotHideOthers() = runBlocking<Unit> {
        val providers = listOf(provider(), provider().copy(id = 8), provider().copy(id = 9))
        val repository = repository(providers, mapOf(7L to clearCredentials(),
            8L to clearCredentials().copy(password = "second-password")))
        whenever(repository.getProviderCredentials(9L)).thenThrow(SecurityException("unreadable secret"))
        var sent: Request? = null
        val client = ControlPlaneClient(CallExecutor { sent = it; response(it) })
        assertTrue(LocalSubscriptionsUploader(repository, client, identity()).upload() is ControlPlaneResult.Success)
        val rows = Json.parseToJsonElement(body(requireNotNull(sent))).jsonObject.getValue("subscriptions").jsonArray
            .associate { it.jsonObject.getValue("localId").jsonPrimitive.content.toLong() to it.jsonObject }
        assertEquals(setOf(7L, 8L, 9L), rows.keys)
        assertEquals("password-secret", rows.getValue(7L).getValue("credentials").jsonObject
            .getValue("xtream").jsonObject.getValue("password").jsonPrimitive.content)
        assertEquals("second-password", rows.getValue(8L).getValue("credentials").jsonObject
            .getValue("xtream").jsonObject.getValue("password").jsonPrimitive.content)
        assertEquals("null", rows.getValue(9L)["credentials"]?.toString() ?: "null")
        for (id in rows.keys) verify(repository).getProviderCredentials(id)
        verify(repository, never()).getAllProviderCredentials()
    }

    @Test fun invalidConfigurationIsOmittedWithoutPartialCredentialSettings() {
        for (invalid in listOf(provider().copy(httpHeaders = "Host: forbidden"),
            provider().copy(serverUrl = "https://user:secret@provider.invalid"),
            provider().copy(epgUrl = "https://provider.invalid/#secret"))) {
            assertNull(LocalSubscription.from(invalid, clearCredentials().copy(serverUrl = invalid.serverUrl)).credentials)
        }
        assertNull(LocalSubscription.from(provider().copy(type = ProviderType.M3U, m3uUrl = "")).credentials)
        assertNull(LocalSubscription.from(provider().copy(type = ProviderType.STALKER_PORTAL, stalkerMacAddress = "invalid")).credentials)
    }

    @Test fun entireUtf8SnapshotIsBoundedWithoutTruncationOrTransmission() = runBlocking {
        val providers = (1L..100L).map { provider().copy(id = it, type = ProviderType.M3U,
            name = "اشتراك", m3uUrl = "https://provider.invalid/list.m3u?token=" + "s".repeat(1000)) }
        assertThrows(IllegalArgumentException::class.java) { LocalSubscriptionsRequest(providers.map { LocalSubscription.from(it) }) }
        val client = ControlPlaneClient(CallExecutor { throw AssertionError("Oversized report must not be transmitted") })
        assertTrue(LocalSubscriptionsUploader(repository(providers, emptyMap()), client, identity()).upload() is ControlPlaneResult.Failure)
    }

    @Test fun uploaderFailureCannotEchoNestedCredentialAsCorrelationIdentifier() = runBlocking {
        val secret = "abcdef0123456789abcdef0123456789ab"
        val client = ControlPlaneClient(CallExecutor { request ->
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(400).message("Bad request")
                .body("""{"title":"Invalid request","status":400,"code":"invalid_request","traceId":"$secret"}""".toResponseBody("application/problem+json".toMediaType())).build()
        })
        val result = LocalSubscriptionsUploader(repository(listOf(provider()), mapOf(7L to clearCredentials().copy(password = secret))),
            client, identity()).upload() as ControlPlaneResult.Failure
        assertNull(result.error.traceId)
        assertFalse(result.toString().contains(secret))
    }

    @Test fun credentialLookupCancellationNeverUploadsPartialSnapshot() = runBlocking {
        val repository = repository(listOf(provider()), emptyMap())
        whenever(repository.getProviderCredentials(7L)).thenThrow(CancellationException("cancelled"))
        val client = ControlPlaneClient(CallExecutor { throw AssertionError("Cancelled report must not transmit") })
        try {
            LocalSubscriptionsUploader(repository, client, identity()).upload()
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }

    private suspend fun repository(providers: List<Provider>, credentials: Map<Long, ProviderCredentials>): ProviderRepository {
        val repository = mock<ProviderRepository>()
        whenever(repository.getProviders()).thenReturn(flowOf(providers))
        for (provider in providers) whenever(repository.getProviderCredentials(provider.id)).thenReturn(credentials[provider.id])
        return repository
    }

    private fun clearCredentials() = ProviderCredentials("https://provider.invalid", "user-secret", "password-secret")
    private fun identity() = InstallationCredentials("00000000-0000-4000-8000-000000000001", "A".repeat(43))
    private fun response(request: Request): Response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .code(204).message("No Content").build()
    private fun body(request: Request): String = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()

    private fun provider() = Provider(id = 7, name = "Sports", type = ProviderType.XTREAM_CODES,
        serverUrl = "https://provider.invalid", username = "user-secret", password = "password-secret",
        status = ProviderStatus.ACTIVE, expirationDate = 1900000000000L, maxConnections = 2)
}
