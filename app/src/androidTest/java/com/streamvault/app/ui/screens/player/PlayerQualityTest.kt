@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.MegaStream.app.ui.screens.player

import android.os.Handler
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.MegaStream.app.R
import com.MegaStream.app.ui.screens.player.overlay.PlayerTrackSelectionDialog
import com.MegaStream.app.ui.theme.MegaStreamTheme
import com.MegaStream.player.TrackType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerQualityTest {
    @get:Rule val rule = createComposeRule()

    @Test fun manualQualityOffersCapsEvenBeforeSourceTracksLoad() {
        val selected = mutableStateOf<Int?>(1080)
        val fixed = mutableStateOf(false)
        rule.setContent {
            MegaStreamTheme {
                PlayerTrackSelectionDialog(TrackType.VIDEO, emptyList(), emptyList(), emptyList(),
                    videoMaxHeight = selected.value, onSelectVideoMaxHeight = { selected.value = it },
                    fixedVideoQuality = fixed.value, onFixedVideoQualityChange = { fixed.value = it },
                    onDismiss = {}, onSelectAudio = {}, onSelectVideo = {}, onSelectSubtitle = {})
            }
        }
        for ((label, height) in listOf("480p" to 480, "2160p (4K)" to 2160)) {
            rule.onNodeWithText(label).performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
                .performKeyInput { pressKey(Key.DirectionCenter) }
            rule.runOnIdle { assertEquals(height, selected.value) }
        }
        val auto = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.player_quality_auto_bandwidth)
        rule.onNodeWithText(auto).performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals(null, selected.value) }
        val fixedLabel = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.player_quality_fixed)
        rule.onNodeWithText(fixedLabel).performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals(true, fixed.value) }
    }

    @Test fun bandwidthDropLowersQualityBeforeTwentySecondBufferRunsOut() {
        var bandwidth = 10_000_000L
        val meter = object : BandwidthMeter {
            override fun getBitrateEstimate() = bandwidth
            override fun getTransferListener(): TransferListener? = null
            override fun addEventListener(handler: Handler, listener: BandwidthMeter.EventListener) = Unit
            override fun removeEventListener(listener: BandwidthMeter.EventListener) = Unit
        }
        val group = TrackGroup(
            Format.Builder().setId("low").setSampleMimeType(MimeTypes.VIDEO_H264).setHeight(480).setAverageBitrate(400_000).build(),
            Format.Builder().setId("high").setSampleMimeType(MimeTypes.VIDEO_H264).setHeight(1080).setAverageBitrate(5_000_000).build()
        )
        // The policy is internal to the player module; exercise its real factory without exporting test hooks.
        val policy = Class.forName("com.MegaStream.player.playback.AdaptiveTrackSelectionPolicies")
        val factory = policy.getMethod("factory").invoke(policy.getField("INSTANCE").get(null)) as AdaptiveTrackSelection.Factory
        val selection = factory.createTrackSelections(arrayOf(ExoTrackSelection.Definition(group, 0, 1)),
            meter, MediaPeriodId("quality-test"), Timeline.EMPTY).single()!!
        val iterators = arrayOf(MediaChunkIterator.EMPTY, MediaChunkIterator.EMPTY)
        selection.updateSelectedTrack(0, 20_000_000, C.TIME_UNSET, emptyList(), iterators)
        assertEquals(1080, selection.selectedFormat.height)
        bandwidth = 1_000_000L
        selection.updateSelectedTrack(0, 20_000_000, C.TIME_UNSET, emptyList(), iterators)
        assertEquals(480, selection.selectedFormat.height)
        bandwidth = 10_000_000L
        selection.updateSelectedTrack(0, 2_000_000, C.TIME_UNSET, emptyList(), iterators)
        assertEquals(480, selection.selectedFormat.height)
        selection.updateSelectedTrack(0, 20_000_000, C.TIME_UNSET, emptyList(), iterators)
        assertEquals(1080, selection.selectedFormat.height)
    }
}
