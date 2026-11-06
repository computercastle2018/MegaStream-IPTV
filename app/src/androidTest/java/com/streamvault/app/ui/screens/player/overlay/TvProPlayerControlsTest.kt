@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.MegaStream.app.ui.screens.player.overlay

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.MegaStream.app.R
import com.MegaStream.app.ui.model.AppUiStyle
import com.MegaStream.app.ui.captureTvPro
import com.MegaStream.app.ui.interaction.TvIconButton
import com.MegaStream.app.ui.theme.MegaStreamTheme
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvProPlayerControlsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun episodeOverlayKeepsTransportSeekAndTrackActions() {
        val playFocus = FocusRequester()
        val calls = mutableListOf<String>()
        var position = 0L
        showOverlay(playFocus = playFocus, onAction = { calls += it }, onSeek = { position = it })
        rule.onNodeWithTag("tvpro-player-topbar").assertIsDisplayed()
        rule.onNodeWithTag("tvpro-player-seek").assertIsDisplayed()
        rule.runOnIdle { playFocus.requestFocus() }
        rule.onNodeWithTag("tvpro-player-play").assertIsFocused()
        rule.waitForIdle()
        captureTvPro("tvpro-player")
        rule.onNodeWithTag("tvpro-player-play")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        rule.onNodeWithTag("tvpro-player-seek").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        rule.runOnIdle {
            assertEquals(60_000L, position)
            assertEquals(listOf("play"), calls)
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        rule.onNodeWithContentDescription(context.getString(R.string.player_rewind))
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        for ((resource, action) in listOf(
            R.string.player_subs to "subtitles", R.string.player_video_quality to "quality",
            R.string.player_episodes to "episodes", R.string.player_audio to "audio"
        )) {
            rule.onNodeWithText(context.getString(resource)).performScrollTo().assertHasClickAction()
                .performSemanticsAction(SemanticsActions.RequestFocus)
                .performKeyInput { pressKey(Key.DirectionCenter) }
            rule.runOnIdle { assertEquals(action, calls.last()) }
        }
        rule.runOnIdle { assertEquals(1, calls.count { it == "rewind" }) }
        rule.onNodeWithText("EN")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals("language", calls.last()) }
        rule.onNodeWithContentDescription(context.getString(R.string.device_notifications))
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals("notifications", calls.last()) }
    }

    @Test fun liveWithoutTimeshiftKeepsPlayButDoesNotOfferSeeking() {
        val playFocus = FocusRequester()
        showOverlay(contentType = "LIVE", playFocus = playFocus)
        rule.runOnIdle { playFocus.requestFocus() }
        rule.onNodeWithTag("tvpro-player-play").assertIsFocused()
        rule.onNodeWithTag("tvpro-player-seek").assertDoesNotExist()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        rule.onNodeWithContentDescription(context.getString(R.string.player_rewind)).assertDoesNotExist()
        rule.onNodeWithContentDescription(context.getString(R.string.player_forward)).assertDoesNotExist()
    }

    private fun showOverlay(
        contentType: String = "SERIES_EPISODE",
        playFocus: FocusRequester,
        onAction: (String) -> Unit = {},
        onSeek: (Long) -> Unit = {}
    ) {
        rule.setContent {
            MegaStreamTheme(uiStyle = AppUiStyle.TV_PRO) {
                PlayerControlsOverlay(
                    visible = true, title = "Episode 8", contentType = contentType,
                    isPlaying = true, currentProgram = null, currentChannelName = "Channel",
                    displayChannelNumber = 1, currentPosition = 30_000L, duration = 120_000L,
                    aspectRatioLabel = "16:9", subtitleTrackCount = 2, audioTrackCount = 2,
                    videoQualityCount = 2, currentRecordingStatus = null, isMuted = false,
                    mediaTitle = null, playButtonFocusRequester = playFocus,
                    onClose = {}, onTogglePlayPause = { onAction("play") },
                    onSeekBackward = { onAction("rewind") }, onSeekForward = { onAction("forward") },
                    onRestartProgram = {}, onOpenArchive = {}, onStartRecording = {},
                    onStopRecording = {}, onScheduleRecording = {}, onToggleAspectRatio = {},
                    onOpenSubtitleTracks = { onAction("subtitles") },
                    onOpenAudioTracks = { onAction("audio") }, onOpenVideoTracks = { onAction("quality") },
                    showEpisodesAction = true, onOpenEpisodes = { onAction("episodes") },
                    onOpenSplitScreen = {}, onToggleMute = {}, onSeekToPosition = onSeek,
                    topBarActions = {
                        TvIconButton(onClick = { onAction("language") }) { Text("EN") }
                        TvIconButton(onClick = { onAction("notifications") }) {
                            Icon(Icons.Default.Notifications,
                                androidx.compose.ui.res.stringResource(R.string.device_notifications))
                        }
                    }
                )
            }
        }
    }
}
