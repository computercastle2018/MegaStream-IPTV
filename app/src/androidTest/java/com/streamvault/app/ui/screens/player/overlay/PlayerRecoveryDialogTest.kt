package com.MegaStream.app.ui.screens.player.overlay

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.MegaStream.app.R
import com.MegaStream.app.ui.screens.player.PlayerNoticeAction
import com.MegaStream.app.ui.theme.MegaStreamTheme
import com.MegaStream.player.PlayerError
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerRecoveryDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun movieFailureKeepsFocusedChoiceAndNeverExposesCredentialsOrExitsOnBack() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val retry = context.getString(R.string.player_retry)
        val home = context.getString(R.string.player_recovery_home)
        var retries = 0
        var exits = 0
        compose.setContent {
            MegaStreamTheme {
                PlayerErrorOverlay(
                    playerError = PlayerError.SourceError("https://provider.invalid/private-user/private-password/movie"),
                    contentType = "MOVIE",
                    hasAlternateStream = false,
                    hasLastChannel = false,
                    onAction = { if (it == PlayerNoticeAction.RETRY) retries++ },
                    onHome = { exits++ }
                )
            }
        }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onNodeWithText(retry).assertIsFocused()
        compose.onNodeWithText("private-password", substring = true).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.player_error_source)).assertExists()
        pressBack()
        compose.onNodeWithText(retry).assertExists()
        compose.runOnIdle { assertEquals(0, exits) }
        compose.onNodeWithText(retry).performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(0, exits)
        }
        compose.onNodeWithText(home).performClick()
        compose.runOnIdle { assertEquals(1, exits) }
    }
}
