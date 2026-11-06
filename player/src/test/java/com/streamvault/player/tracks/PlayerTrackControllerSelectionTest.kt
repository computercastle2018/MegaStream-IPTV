package com.MegaStream.player.tracks

import com.google.common.truth.Truth.assertThat
import com.MegaStream.player.PLAYER_TRACK_AUTO_ID
import org.junit.Test

class PlayerTrackControllerSelectionTest {
    @Test fun `fixed mode selects one rendition while adaptive mode retains lower renditions`() {
        val heights = listOf(360, 480, 720, 1080, 2160)
        assertThat(qualityTrackIndices(heights, 1080, null, false)).containsExactly(3)
        assertThat(qualityTrackIndices(heights, 1080, null, true)).containsExactly(0, 1, 2, 3)
        assertThat(qualityTrackIndices(heights, 2160, 720, false)).containsExactly(2)
        assertThat(qualityTrackIndices(listOf(720), 480, 1080, true)).containsExactly(0)
        assertThat(qualityTrackIndices(listOf(2160, -1), 1080, 1080, false)).isEmpty()
        assertThat(qualityTrackIndices(listOf(Int.MIN_VALUE, 480), 1080, null, false)).containsExactly(1)
    }

    @Test
    fun `stale selected video track falls back to auto when multiple tracks remain`() {
        assertThat(
            normalizeSelectedVideoTrackId(
                selectedTrackId = "stale-id",
                availableTrackIds = listOf("720p", "1080p")
            )
        ).isEqualTo(PLAYER_TRACK_AUTO_ID)
    }

    @Test
    fun `stale selected video track falls back to only available track`() {
        assertThat(
            normalizeSelectedVideoTrackId(
                selectedTrackId = "stale-id",
                availableTrackIds = listOf("1080p")
            )
        ).isEqualTo("1080p")
    }

    @Test
    fun `current selected video track is preserved when still available`() {
        assertThat(
            normalizeSelectedVideoTrackId(
                selectedTrackId = "720p",
                availableTrackIds = listOf("720p", "1080p")
            )
        ).isEqualTo("720p")
    }
}
