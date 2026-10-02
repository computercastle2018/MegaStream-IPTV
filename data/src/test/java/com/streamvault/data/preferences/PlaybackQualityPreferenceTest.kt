package com.MegaStream.data.preferences

import org.junit.Assert.*
import org.junit.Test

class PlaybackQualityPreferenceTest {
    @Test
    fun unsetDefaultsTo1080AndExplicitAutoSurvivesPersistenceRoundTrip() {
        assertEquals(1080, storedPlaybackMaxVideoHeight(null))
        assertNull(storedPlaybackMaxVideoHeight(encodePlaybackMaxVideoHeight(null)))
    }

    @Test
    fun existingLocalCapsRemainUnchangedAfterPersistenceRoundTrip() {
        for (height in listOf(480, 720, 1080, 2160)) {
            assertEquals(height, storedPlaybackMaxVideoHeight(encodePlaybackMaxVideoHeight(height)))
        }
    }
}
