package com.MegaStream.app.ui.screens.series

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import com.MegaStream.app.R
import com.MegaStream.app.ui.components.ChipRowItem
import com.MegaStream.app.ui.components.ChipRowSection
import com.MegaStream.app.ui.components.SearchInput
import com.MegaStream.app.ui.design.requestFocusSafely
import com.MegaStream.app.ui.interaction.TvIconButton
import com.MegaStream.domain.util.EpisodeBrowseQuery
import com.MegaStream.domain.util.EpisodeOrder
import com.MegaStream.domain.util.EpisodeWatchFilter

@Composable
internal fun EpisodeBrowseControls(query: EpisodeBrowseQuery, onChange: (EpisodeBrowseQuery) -> Unit) {
    var searchVisible by rememberSaveable { mutableStateOf(query.search.isNotBlank()) }
    var focusPending by rememberSaveable { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row {
            ChipRowSection(
                chips = EpisodeOrder.entries.map { order ->
                    ChipRowItem(order.name, stringResource(if (order == EpisodeOrder.NEWEST)
                        R.string.series_episode_newest else R.string.series_episode_oldest),
                        onClick = { onChange(query.copy(order = order)) })
                },
                selectedKey = query.order.name, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)
            )
            TvIconButton(onClick = {
                searchVisible = !searchVisible || query.search.isNotBlank()
                focusPending = searchVisible
            }) {
                Icon(Icons.Default.Search, contentDescription = stringResource(R.string.series_episode_search))
            }
        }
        ChipRowSection(
            chips = EpisodeWatchFilter.entries.map { filter ->
                val label = when (filter) {
                    EpisodeWatchFilter.ALL -> R.string.series_episode_all
                    EpisodeWatchFilter.UNWATCHED -> R.string.series_episode_unwatched
                    EpisodeWatchFilter.IN_PROGRESS -> R.string.series_episode_in_progress
                    EpisodeWatchFilter.COMPLETED -> R.string.series_episode_completed
                }
                ChipRowItem(filter.name, stringResource(label), onClick = { onChange(query.copy(watchFilter = filter)) })
            },
            selectedKey = query.watchFilter.name, contentPadding = PaddingValues(4.dp)
        )
        if (searchVisible) {
            LaunchedEffect(focusPending) {
                if (focusPending) {
                    searchFocus.requestFocusSafely(tag = "EpisodeBrowse", target = "Episode search")
                    focusPending = false
                }
            }
            SearchInput(value = query.search, onValueChange = { onChange(query.copy(search = it)) },
                placeholder = stringResource(R.string.series_episode_search), onSearch = {}, focusRequester = searchFocus)
        }
    }
}
