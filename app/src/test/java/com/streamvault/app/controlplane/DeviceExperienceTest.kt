package com.MegaStream.app.controlplane

import com.MegaStream.app.ui.model.AppUiStyle
import org.junit.Assert.assertEquals
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
}
