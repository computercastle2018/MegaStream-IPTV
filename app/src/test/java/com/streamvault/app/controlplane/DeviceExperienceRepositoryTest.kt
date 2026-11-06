package com.MegaStream.app.controlplane

import android.content.SharedPreferences
import com.MegaStream.app.ui.model.AppUiStyle
import java.io.IOException
import javax.inject.Provider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class DeviceExperienceRepositoryTest {
    @Test fun pendingChoiceSurvivesRestartAndOfflineGetWithoutLosingOriginalExpectation() = runTest {
        val f = Fixture()
        val repository = f.repository()
        assertTrue(repository.queueAppUiStyle(AppUiStyle.MODERN))
        assertEquals("modern", repository.state.value.uiStyle)
        assertTrue(repository.queueAppUiStyle(AppUiStyle.STUDIO))
        f.failPost = true
        val restarted = f.repository()
        assertEquals("studio", restarted.state.value.uiStyle)
        assertTrue(restarted.refresh())
        assertEquals("studio", restarted.state.value.uiStyle)
        assertEquals("classic", f.pending().expectedUiStyle)
        assertEquals(listOf("POST", "GET"), f.requests.map { it.method })
        f.failPost = false
        f.remoteStyle = "studio"
        assertTrue(restarted.refresh())
        assertFalse(f.values.containsKey("pending-appearance"))
        assertEquals("studio", f.repository().state.value.uiStyle)
        assertEquals("classic", Json.decodeFromString<DeviceAppearanceRequest>(f.postBody).expectedUiStyle)
    }

    @Test fun conflictDropsPendingAndNewAdminChoiceWinsWithoutTouchingProviderOrQualityPolicies() = runTest {
        val f = Fixture()
        val repository = f.repository()
        repository.queueAppUiStyle(AppUiStyle.MODERN)
        f.postStatus = 409
        f.remoteStyle = "studio"
        assertTrue(repository.refresh())
        assertFalse(f.values.containsKey("pending-appearance"))
        assertEquals("studio", repository.state.value.uiStyle)
        assertFalse(repository.state.value.allowSubscriptionDetails)
        assertEquals("720", repository.state.value.playbackQuality)
        assertEquals(listOf("/api/v1/devices/appearance", "/api/v1/devices/experience"), f.requests.map { it.url.encodedPath })
        f.requests.clear()
        repository.refresh()
        assertEquals(listOf("GET"), f.requests.map { it.method })
    }

    @Test fun inheritedExpectedNullIsRetainedAcrossRepeatedChoicesAndFailedRequests() = runTest {
        val f = Fixture()
        f.values.remove("ui-style")
        val repository = f.repository()
        repository.queueAppUiStyle(AppUiStyle.STUDIO)
        repository.queueAppUiStyle(AppUiStyle.MODERN)
        f.postStatus = 503
        f.failGet = true
        assertFalse(repository.refresh())
        assertNull(f.pending().expectedUiStyle)
        assertEquals("modern", f.repository().state.value.uiStyle)
        assertTrue(f.values.containsKey("pending-appearance"))
    }

    private class Fixture {
        val values = mutableMapOf<String, Any>("ui-style" to "classic")
        val requests = mutableListOf<Request>()
        var failPost = false
        var failGet = false
        var postStatus = 204
        var remoteStyle = "classic"
        var postBody = ""
        private val preferences: SharedPreferences = mock()
        private val editor: SharedPreferences.Editor = mock()
        private val client = ControlPlaneClient(CallExecutor { request ->
            requests += request
            if (request.method == "POST") {
                postBody = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                if (failPost) throw IOException("Offline")
                response(request, "", postStatus)
            } else {
                if (failGet) throw IOException("Offline")
                response(request, """{"allowSubscriptionDetails":false,"uiStyle":"$remoteStyle","playbackQuality":"720"}""", 200)
            }
        })

        init {
            whenever(preferences.getString(any(), anyOrNull())).thenAnswer { values[it.getArgument<String>(0)] as? String }
            whenever(preferences.getBoolean(any(), any())).thenAnswer { values[it.getArgument<String>(0)] as? Boolean ?: it.getArgument<Boolean>(1) }
            whenever(preferences.getStringSet(any(), anyOrNull())).thenReturn(emptySet())
            whenever(preferences.edit()).thenReturn(editor)
            whenever(editor.putString(any(), anyOrNull())).thenAnswer {
                val key = it.getArgument<String>(0)
                val value = it.getArgument<String?>(1)
                if (value == null) values.remove(key) else values[key] = value
                editor
            }
            whenever(editor.putBoolean(any(), any())).thenAnswer {
                values[it.getArgument<String>(0)] = it.getArgument<Boolean>(1)
                editor
            }
            whenever(editor.remove(any())).thenAnswer { values.remove(it.getArgument<String>(0)); editor }
            whenever(editor.commit()).thenReturn(true)
        }

        fun pending() = Json.decodeFromString<DeviceAppearanceRequest>(values.getValue("pending-appearance") as String)
        fun repository() = DeviceExperienceRepository(preferences,
            Provider { InstallationCredentials("6e244c20-c9ef-422d-a1da-2d84570bc949", "A".repeat(43)) }, client, { null })

        private fun response(request: Request, body: String, status: Int) = Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(status).message("test")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }
}
