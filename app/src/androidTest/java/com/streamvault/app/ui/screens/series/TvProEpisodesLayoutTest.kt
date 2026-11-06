@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.MegaStream.app.ui.screens.series

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.Text
import com.MegaStream.app.ui.model.AppUiStyle
import com.MegaStream.app.R
import com.MegaStream.app.ui.theme.MegaStreamTheme
import com.MegaStream.domain.model.Episode
import com.MegaStream.domain.model.Season
import com.MegaStream.domain.model.Series
import com.MegaStream.domain.util.EpisodeBrowseQuery
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvProEpisodesLayoutTest {
    @get:Rule val rule = createComposeRule()

    @Test fun english_seasonAndEpisodeNavigationRetainsNewestFirst() = navigateEpisodes(LayoutDirection.Ltr)
    @Test fun arabic_seasonAndEpisodeNavigationRetainsNewestFirst() = navigateEpisodes(LayoutDirection.Rtl)

    private fun navigateEpisodes(direction: LayoutDirection) {
        val series = Series(1L, "Series", seasons = listOf(
            Season(1, "Season One", episodes = listOf(Episode(1L, "Oldest", 1, 1))),
            Season(2, "Season Two", episodes = listOf(Episode(2L, "Newest", 2, 2,
                durationSeconds = 100, watchProgress = 50_000L)))
        ))
        var played: Long? = null
        rule.setContent {
            var query by remember { mutableStateOf(EpisodeBrowseQuery()) }
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                MegaStreamTheme(uiStyle = AppUiStyle.TV_PRO) {
                    TvProSeriesDetail(series, query, { query = it },
                        { query = query.copy(seasonNumber = it.seasonNumber) }, { played = it.id }, {}, {}, { Text("Information") })
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        rule.onNodeWithText(context.getString(R.string.series_episode_in_progress))
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        rule.onNodeWithText("Oldest").assertDoesNotExist()
        rule.onNodeWithText("Newest").assertExists()
        rule.onNodeWithText(context.getString(R.string.series_episode_all))
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        val season = rule.onNodeWithText("Season Two")
        season.performSemanticsAction(SemanticsActions.RequestFocus)
        season.assertIsFocused()
        season.performKeyInput { pressKey(if (direction == LayoutDirection.Rtl) Key.DirectionLeft else Key.DirectionRight) }
        val newest = rule.onNodeWithText("Newest")
        newest.assertIsFocused()
        newest.performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { org.junit.Assert.assertEquals(2L, played) }
        newest.performKeyInput { pressKey(if (direction == LayoutDirection.Rtl) Key.DirectionRight else Key.DirectionLeft) }
        season.assertIsFocused()
        season.performKeyInput { pressKey(Key.DirectionCenter) }
        rule.onNodeWithText("Oldest").assertDoesNotExist()
        newest.assertExists()
        rule.waitForIdle()
        com.MegaStream.app.ui.captureTvPro("tvpro-episodes-${direction.name}")
    }
}
