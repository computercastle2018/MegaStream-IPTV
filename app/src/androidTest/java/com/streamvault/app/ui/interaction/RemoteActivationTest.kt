@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.MegaStream.app.ui.interaction

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.Text
import com.MegaStream.app.ui.theme.MegaStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RemoteActivationTest {
    @get:Rule val rule = createComposeRule()

    @Test fun focusChangeDuringOkDoesNotActivateAnotherButton() {
        var firstClicks = 0
        var secondClicks = 0
        val firstFocus = FocusRequester()
        val secondFocus = FocusRequester()
        rule.setContent {
            MegaStreamTheme {
                Column {
                    TvButton(onClick = { firstClicks++ }, modifier = Modifier.focusRequester(firstFocus)) { Text("First") }
                    TvClickableSurface(onClick = { secondClicks++ }, modifier = Modifier.focusRequester(secondFocus)) { Text("Second") }
                }
            }
        }
        val first = rule.onNodeWithText("First")
        val second = rule.onNodeWithText("Second")
        first.performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { keyDown(Key.DirectionCenter) }
        second.performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { keyUp(Key.DirectionCenter) }
        rule.runOnIdle {
            assertEquals(0, firstClicks)
            assertEquals(0, secondClicks)
        }
        second.performKeyInput { pressKey(Key.DirectionCenter) }
        first.performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle {
            assertEquals(1, firstClicks)
            assertEquals(1, secondClicks)
        }
    }
}
