@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.MegaStream.app.ui.screens.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.Text
import com.MegaStream.app.ui.interaction.TvButton
import com.MegaStream.app.ui.theme.MegaStreamTheme
import com.MegaStream.domain.model.Category
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CategoryNavigationTest {
    @get:Rule val rule = createComposeRule()

    @Test fun arabic_arrowsFollowVisiblePanels() = followsPanels(LayoutDirection.Rtl)
    @Test fun english_arrowsFollowVisiblePanels() = followsPanels(LayoutDirection.Ltr)

    private fun followsPanels(direction: LayoutDirection) {
        val content = FocusRequester()
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                MegaStreamTheme {
                    Column {
                        TvButton(onClick = {}) { Text("Search") }
                        Row {
                            TvButton(onClick = {}) { Text("Menu") }
                            Box(Modifier.width(200.dp)) {
                                CategoryItem(Category(1, name = "Group"), isSelected = true,
                                    focusRequester = FocusRequester(), onClick = {}, onFocused = {},
                                    onJumpToContent = { content.requestFocus() })
                            }
                            TvButton(onClick = {}, modifier = Modifier.focusRequester(content)) { Text("Channel") }
                        }
                    }
                }
            }
        }
        val group = rule.onNodeWithText("Group")
        group.performSemanticsAction(SemanticsActions.RequestFocus)
        group.performKeyInput { pressKey(if (direction == LayoutDirection.Rtl) Key.DirectionLeft else Key.DirectionRight) }
        rule.onNodeWithText("Channel").assertIsFocused()
        group.performSemanticsAction(SemanticsActions.RequestFocus)
        group.performKeyInput { pressKey(if (direction == LayoutDirection.Rtl) Key.DirectionRight else Key.DirectionLeft) }
        rule.onNodeWithText("Menu").assertIsFocused()
    }
}
