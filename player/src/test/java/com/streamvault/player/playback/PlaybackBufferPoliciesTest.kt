package com.MegaStream.player.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackBufferPoliciesTest {

    @Test
    fun `normal live uses fast live startup buffer`() {
        val policy = PlaybackBufferPolicies.forPlayback(isLive = true, compatibilityMode = false)

        assertThat(policy.label).isEqualTo("fast-live")
        assertThat(policy.minBufferMs).isEqualTo(3_000)
        assertThat(policy.maxBufferMs).isEqualTo(18_000)
        assertThat(policy.playbackBufferMs).isEqualTo(350)
        assertThat(policy.rebufferMs).isEqualTo(1_200)
    }

    @Test
    fun `compatibility live uses larger live buffer`() {
        val policy = PlaybackBufferPolicies.forPlayback(isLive = true, compatibilityMode = true)

        assertThat(policy.label).isEqualTo("compat-live")
        assertThat(policy.minBufferMs).isEqualTo(15_000)
        assertThat(policy.maxBufferMs).isEqualTo(45_000)
        assertThat(policy.playbackBufferMs).isEqualTo(800)
        assertThat(policy.rebufferMs).isEqualTo(2_500)
    }

    @Test
    fun `vod uses larger stable buffer`() {
        val policy = PlaybackBufferPolicies.forPlayback(isLive = false, compatibilityMode = false)

        assertThat(policy.label).isEqualTo("stable-vod")
        assertThat(policy.minBufferMs).isEqualTo(50_000)
        assertThat(policy.maxBufferMs).isEqualTo(120_000)
        assertThat(policy.playbackBufferMs).isEqualTo(800)
        assertThat(policy.rebufferMs).isEqualTo(2_500)
    }
}
