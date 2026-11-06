@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.MegaStream.app.ui.screens.vod

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.MegaStream.app.ui.components.shell.VodCategoryOption
import com.MegaStream.app.ui.model.AppUiStyle
import com.MegaStream.app.ui.model.formatVodRatingLabel
import com.MegaStream.app.ui.theme.MegaStreamTheme
import com.MegaStream.domain.model.Movie
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvProCatalogLayoutTest {
    @get:Rule val rule = createComposeRule()

    @Test fun english_gridEdgesReturnToCategory() = gridEdges(LayoutDirection.Ltr)
    @Test fun arabic_gridEdgesReturnToCategory() = gridEdges(LayoutDirection.Rtl)

    private fun gridEdges(direction: LayoutDirection) {
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                MegaStreamTheme(uiStyle = AppUiStyle.TV_PRO) {
                    CatalogStyleLayout(listOf(VodCategoryOption("Group", 8, {})), "Group", rememberLazyGridState()) {
                        items((1..8).toList()) { index ->
                            CatalogMovieCard(Movie(index.toLong(), "Movie $index"), {},
                                Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                        }
                    }
                }
            }
        }
        val group = rule.onNodeWithText("Group")
        group.performSemanticsAction(SemanticsActions.RequestFocus)
        group.performKeyInput { pressKey(if (direction == LayoutDirection.Rtl) Key.DirectionLeft else Key.DirectionRight) }
        rule.onNodeWithText("Movie 1").assertIsFocused()
        rule.onNodeWithText("Movie 1").performKeyInput {
            pressKey(if (direction == LayoutDirection.Rtl) Key.DirectionRight else Key.DirectionLeft)
        }
        group.assertIsFocused()
        group.performKeyInput { pressKey(Key.DirectionUp) }
        group.assertIsNotFocused()
        rule.waitForIdle()
        com.MegaStream.app.ui.captureTvPro("tvpro-catalog-${direction.name}")
    }

    @Test fun lockedPoster_hidesRatingAndKeepsPinClick() {
        var clicks = 0
        rule.setContent {
            MegaStreamTheme(uiStyle = AppUiStyle.TV_PRO) {
                CatalogMovieCard(Movie(1L, "Protected", rating = 8.5f), { clicks++ },
                    Modifier.size(112.dp, 168.dp), isLocked = true)
            }
        }
        rule.onNodeWithText(formatVodRatingLabel(8.5f)).assertDoesNotExist()
        rule.onNodeWithText("Protected")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { org.junit.Assert.assertEquals(1, clicks) }
    }
}
