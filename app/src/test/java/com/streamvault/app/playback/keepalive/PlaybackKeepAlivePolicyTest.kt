package com.MegaStream.app.playback.keepalive

import com.MegaStream.player.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackKeepAlivePolicyTest {
    private val active = PlaybackKeepAliveInput(
        hasStream = true,
        playbackState = PlaybackState.READY,
        isPlaying = false,
        screenAttached = true,
        foregroundEligible = true
    )

    @Test
    fun `state and playing matrix respects terminal overrides`() {
        val expected = mapOf(
            PlaybackState.IDLE to (false to true),
            PlaybackState.BUFFERING to (true to true),
            PlaybackState.READY to (true to true),
            PlaybackState.ENDED to (false to false),
            PlaybackState.ERROR to (false to false)
        )
        for ((state, outcomes) in expected) {
            for ((playing, outcome) in listOf(false to outcomes.first, true to outcomes.second)) {
                assertEquals("$state playing=$playing", outcome,
                    reducePlaybackKeepAlive(active.copy(playbackState = state, isPlaying = playing), false))
            }
        }
    }

    @Test
    fun `missing stream or release stops even an existing service`() {
        for (requested in listOf(false, true)) {
            for (state in PlaybackState.entries) {
                assertFalse(reducePlaybackKeepAlive(
                    active.copy(hasStream = false, playbackState = state, isPlaying = true), requested))
                assertFalse(reducePlaybackKeepAlive(
                    active.copy(released = true, playbackState = state, isPlaying = true), requested))
            }
        }
    }

    @Test
    fun `cold start requires attached foreground while existing request carries`() {
        for (attached in listOf(false, true)) {
            for (foreground in listOf(false, true)) {
                val input = active.copy(screenAttached = attached, foregroundEligible = foreground)
                assertEquals(attached && foreground, reducePlaybackKeepAlive(input, false))
                assertTrue(reducePlaybackKeepAlive(input, true))
            }
        }
    }

    @Test
    fun `terminal and inactive states end carried background playback`() {
        for (state in listOf(PlaybackState.ENDED, PlaybackState.ERROR, PlaybackState.IDLE)) {
            assertFalse(reducePlaybackKeepAlive(active.copy(
                screenAttached = false, foregroundEligible = false, playbackState = state,
                isPlaying = state != PlaybackState.IDLE
            ), true))
        }
    }

    @Test
    fun `revoked queued start cannot resurrect service or kill newer owner`() {
        assertEquals(PlaybackKeepAliveCommand.STOP,
            reducePlaybackKeepAliveCommand(isStart = true, permitValid = false, hasOwners = false))
        assertEquals(PlaybackKeepAliveCommand.IGNORE_STALE_OWNER,
            reducePlaybackKeepAliveCommand(isStart = true, permitValid = false, hasOwners = true))
        assertEquals(PlaybackKeepAliveCommand.PROMOTE,
            reducePlaybackKeepAliveCommand(isStart = true, permitValid = true, hasOwners = true))
    }

    @Test
    fun `null restart stop and unknown commands shut down regardless of owners`() {
        for (hasOwners in listOf(false, true)) {
            for (validPermit in listOf(false, true)) {
                assertEquals(PlaybackKeepAliveCommand.STOP,
                    reducePlaybackKeepAliveCommand(false, validPermit, hasOwners))
            }
        }
    }
}
