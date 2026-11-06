@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.MegaStream.app.ui.components.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.Text
import com.MegaStream.app.R
import com.MegaStream.app.navigation.Routes
import com.MegaStream.app.ui.interaction.TvButton
import com.MegaStream.app.ui.model.AppUiStyle
import com.MegaStream.app.ui.screens.dashboard.DashboardFeature
import com.MegaStream.app.ui.screens.dashboard.StudioDashboardPanelsRow
import com.MegaStream.app.ui.theme.MegaStreamTheme
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShellNavigationTest {
    @get:Rule val rule = createComposeRule()

    @Test fun rtl_contentReturnsToActiveDestination() = returnsToActiveDestination(LayoutDirection.Rtl)
    @Test fun ltr_contentReturnsToActiveDestination() = returnsToActiveDestination(LayoutDirection.Ltr)

    @Test fun sidebarCanHideAndRestoreUsingRemote() {
        rule.setContent {
            MegaStreamTheme {
                AppScreenScaffold(currentRoute = Routes.HOME, onNavigate = {}, title = "Browse") { Text("Content") }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        rule.onNodeWithContentDescription(context.getString(R.string.hide_sidebar))
            .performSemanticsAction(SemanticsActions.RequestFocus).performKeyInput { pressKey(Key.DirectionCenter) }
        rule.onNodeWithText(context.getString(R.string.nav_movies)).assertDoesNotExist()
        rule.onNodeWithText("Content").assertExists()
        rule.onNodeWithContentDescription(context.getString(R.string.show_sidebar))
            .performKeyInput { pressKey(Key.DirectionCenter) }
        rule.onNodeWithText(context.getString(R.string.nav_movies)).assertExists()
    }

    @Test fun studio_dashboardSectionsAreWholeCardsInRemoteOrder() {
        var opened = ""
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MegaStreamTheme(uiStyle = AppUiStyle.STUDIO) {
                    StudioDashboardPanelsRow(
                        feature = DashboardFeature(title = "Live"), heroChannels = emptyList(), liveChannelCount = 0,
                        recentMovies = emptyList(), recentSeries = emptyList(), movieCount = 0, seriesCount = 0,
                        onWatchLive = { opened = Routes.LIVE_TV }, onOpenMovies = { opened = Routes.MOVIES },
                        onOpenSeries = { opened = Routes.SERIES }
                    )
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val live = rule.onNodeWithText(context.getString(R.string.nav_live_tv))
        val movies = rule.onNodeWithText(context.getString(R.string.nav_movies))
        val series = rule.onNodeWithText(context.getString(R.string.nav_series))
        rule.onAllNodes(isFocusable()).assertCountEquals(3)
        live.performSemanticsAction(SemanticsActions.RequestFocus)
        live.performKeyInput { pressKey(Key.DirectionLeft) }
        movies.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals(Routes.MOVIES, opened) }
        movies.performKeyInput { pressKey(Key.DirectionLeft) }
        series.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals(Routes.SERIES, opened) }
        series.performKeyInput { pressKey(Key.DirectionRight) }
        movies.assertIsFocused()
    }

    private fun returnsToActiveDestination(direction: LayoutDirection) {
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                MegaStreamTheme {
                    AppScreenScaffold(currentRoute = Routes.HOME, onNavigate = {}, title = "Browse") {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                            TvButton(onClick = {}, modifier = Modifier.testTag("content")) { Text("Content") }
                        }
                    }
                }
            }
        }
        rule.onNodeWithTag("content").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNodeWithTag("content").performKeyInput {
            pressKey(if (direction == LayoutDirection.Rtl) Key.DirectionRight else Key.DirectionLeft)
        }
        val home = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.nav_home)
        rule.onNodeWithText(home).assertIsFocused()
    }
}
