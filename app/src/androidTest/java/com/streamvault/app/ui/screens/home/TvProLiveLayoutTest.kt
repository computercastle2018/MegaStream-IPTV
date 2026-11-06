@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.MegaStream.app.ui.screens.home

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.platform.testTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.MegaStream.app.ui.model.AppUiStyle
import com.MegaStream.app.ui.theme.MegaStreamTheme
import com.MegaStream.domain.model.Channel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvProLiveLayoutTest {
    @get:Rule val rule = createComposeRule()

    @Test fun compactRowsKeepProviderNumbersAndFallbackOrder() {
        var selected: Long? = null
        rule.setContent {
            MegaStreamTheme(uiStyle = AppUiStyle.TV_PRO) {
                Column {
                    CatalogLiveChannelRow(Channel(1L, "Numbered", number = 105), { selected = 1 }, true, 1,
                        modifier = Modifier.testTag("numbered"))
                    CatalogLiveChannelRow(Channel(2L, "Fallback"), { selected = 2 }, true, 2,
                        modifier = Modifier.testTag("fallback"), isLocked = true)
                }
            }
        }
        rule.onNodeWithText("105", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("2", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("fallback")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { org.junit.Assert.assertEquals(2L, selected) }
    }
}
