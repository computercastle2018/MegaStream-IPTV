package com.MegaStream.app.controlplane

import com.MegaStream.app.ui.model.AppUiStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceExperienceTest {
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
