package com.MegaStream.app.controlplane

import com.MegaStream.domain.model.Provider
import com.MegaStream.domain.model.ProviderStatus
import com.MegaStream.domain.model.ProviderType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

    private fun provider() = Provider(id = 7, name = "Sports", type = ProviderType.XTREAM_CODES,
        serverUrl = "https://provider.invalid", username = "user-secret", password = "password-secret",
        status = ProviderStatus.ACTIVE, expirationDate = 1900000000000L, maxConnections = 2)
}
