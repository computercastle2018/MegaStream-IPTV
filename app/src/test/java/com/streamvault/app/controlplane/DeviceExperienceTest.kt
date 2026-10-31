package com.MegaStream.app.controlplane

import com.MegaStream.app.ui.model.AppUiStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DeviceExperienceTest {
    @Test
    fun appearanceRequiresExplicitExpectedStyleAndOnlyAcceptsKnownTemplates() {
        assertEquals("{\"uiStyle\":\"studio\",\"expectedUiStyle\":null}",
            Json.encodeToString(DeviceAppearanceRequest("studio", null)))
        for (style in AppUiStyle.entries) {
            for (expected in listOf(null, "classic", "modern", "studio")) {
                val request = DeviceAppearanceRequest(style.storageValue, expected)
                assertEquals(request, Json.decodeFromString<DeviceAppearanceRequest>(Json.encodeToString(request)))
            }
        }
        for (payload in listOf(
            """{"uiStyle":"classic"}""",
            """{"uiStyle":"unknown","expectedUiStyle":null}""",
            """{"uiStyle":"studio","expectedUiStyle":"unknown"}""",
            """{"uiStyle":"studio","expectedUiStyle":null,"extra":true}""",
        )) {
            assertThrows(IllegalArgumentException::class.java) { Json.decodeFromString<DeviceAppearanceRequest>(payload) }
        }
    }

    @Test
    fun remoteStyleWinsAndClearingRestoresEachLocalChoice() {
        for (local in AppUiStyle.entries) {
            for (remote in AppUiStyle.entries) {
                val managed = DeviceExperience(true, uiStyle = remote.storageValue)
                assertEquals(remote, managed.effectiveAppUiStyle(local.storageValue))
                assertEquals(local, managed.copy(uiStyle = null).effectiveAppUiStyle(local.storageValue))
            }
        }
    }

    @Test
    fun unmanagedUsesExistingLocalFallbackWithoutMakingItAnOverride() {
        val experience = DeviceExperience(false)
        for (local in listOf(null, "unknown")) {
            assertEquals(AppUiStyle.CLASSIC, experience.effectiveAppUiStyle(local))
            assertEquals(null, experience.uiStyle)
        }
    }

    @Test
    fun qualityPolicy_isIndependentOfUiStyleAndRejectsUnknownAdminValues() {
        val experience = DeviceExperience(false, uiStyle = "studio", playbackQuality = "1080")
        assertEquals("1080", experience.copy(uiStyle = "classic").playbackQuality)
        assertEquals(AppUiStyle.STUDIO, experience.copy(playbackQuality = "auto").effectiveAppUiStyle("classic"))
        assertThrows(IllegalArgumentException::class.java) { experience.copy(playbackQuality = "1081") }
    }
}
