package com.MegaStream.domain.model

import org.junit.Assert.*
import org.junit.Test

class PlaybackVideoQualityPolicyTest {
    @Test
    fun unmanaged_keepsLocalCapsAutoAndManualTrackChoices() {
        val policy = PlaybackVideoQualityPolicy()
        for (local in listOf(null, 480, 720, 1080, 2160)) {
            assertEquals(local, policy.maxVideoHeight(local, false))
            assertTrue(policy.permitsManualTrack(2160))
            assertTrue(policy.permitsManualTrack(-1))
        }
    }

    @Test
    fun adminOverride_winsOverLocalSettingsAndBlocksOversizedOrUnknownManualTracks() {
        val policy = PlaybackVideoQualityPolicy(1080)
        for (local in listOf(null, 480, 2160)) assertEquals(1080, policy.maxVideoHeight(local, false))
        for (height in listOf(480, 720, 1080)) assertTrue(policy.permitsManualTrack(height))
        for (height in listOf(-1, 0, 1440, 2160)) assertFalse(policy.permitsManualTrack(height))
        assertNull(policy.copy(adminMaxVideoHeight = null).maxVideoHeight(null, false))
        assertTrue(policy.copy(adminMaxVideoHeight = null).permitsManualTrack(2160))
    }

    @Test
    fun compatibilityAndMultiview_neverRaiseAnAdminOrLocalLowerCap() {
        for ((admin, local, expected) in listOf(Triple(null, null, 720), Triple(null, 1080, 720),
            Triple(null, 480, 480), Triple(480, 2160, 480), Triple(2160, null, 720))) {
            assertEquals(expected, PlaybackVideoQualityPolicy(admin).maxVideoHeight(local, true))
        }
    }

    @Test
    fun malformedAdminPolicy_isRejectedNotSilentlyTreatedAsUnmanaged() {
        for (height in listOf(-1, 0, 1081, 99999)) {
            assertThrows(IllegalArgumentException::class.java) { PlaybackVideoQualityPolicy(height) }
        }
    }
}
