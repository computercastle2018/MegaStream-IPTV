package com.MegaStream.app.ui.screens.series

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.tv.material3.*
import coil3.compose.AsyncImage
import com.MegaStream.app.R
import com.MegaStream.app.ui.components.shell.VodCategoryOption
import com.MegaStream.app.ui.interaction.TvButton
import com.MegaStream.app.ui.interaction.TvClickableSurface
import com.MegaStream.app.ui.interaction.TvIconButton
import com.MegaStream.app.ui.screens.vod.TvProCategoryButton
import com.MegaStream.domain.model.Episode
import com.MegaStream.domain.model.Season
import com.MegaStream.domain.model.Series
import com.MegaStream.domain.util.EpisodeBrowseQuery
import com.MegaStream.domain.util.browseSeriesEpisodes

@Composable
internal fun TvProSeriesDetail(
    series: Series, query: EpisodeBrowseQuery, onQueryChange: (EpisodeBrowseQuery) -> Unit,
    onSeasonSelected: (Season) -> Unit, onEpisodeClick: (Episode) -> Unit, onBack: () -> Unit,
    actions: @Composable () -> Unit, information: @Composable () -> Unit
) {
    val episodes = remember(series, query) { browseSeriesEpisodes(series, query) }
    var visibleLimit by remember(series.id, query) { mutableStateOf(100) }
    val episodeState = rememberLazyListState()
    val seasonFocus = remember { FocusRequester() }
    val episodeFocus = remember { FocusRequester() }
    val seasonRequesters = remember(series.id) { mutableMapOf<Int?, FocusRequester>() }
    val episodeRequesters = remember(series.id) { mutableMapOf<Long, FocusRequester>() }
    var lastSeason by remember(series.id) { mutableStateOf<Int?>(null) }
    var lastEpisode by remember(series.id) { mutableStateOf<Long?>(null) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    LaunchedEffect(series.id, query) { episodeState.scrollToItem(0) }
    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(Modifier.fillMaxHeight().weight(0.27f).background(MaterialTheme.colorScheme.surface)
            .focusRequester(seasonFocus).focusGroup(),
            contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item(key = "back") {
                TvIconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.movie_detail_back))
                }
            }
            item(key = "all") {
                TvProCategoryButton(VodCategoryOption(stringResource(R.string.series_episode_all_seasons),
                    series.seasons.sumOf { it.episodes.size }, { onQueryChange(query.copy(seasonNumber = null)) }),
                    query.seasonNumber == null, Modifier.fillMaxWidth()
                        .focusRequester(seasonRequesters.getOrPut(null) { FocusRequester() })
                        .onFocusChanged { if (it.isFocused) lastSeason = null }
                        .focusProperties { if (rtl) left = episodeFocus else right = episodeFocus })
            }
            items(series.seasons.sortedByDescending { it.seasonNumber }, key = { it.seasonNumber }) { season ->
                TvProCategoryButton(VodCategoryOption(season.name, season.episodes.size, { onSeasonSelected(season) }),
                    query.seasonNumber == season.seasonNumber,
                    Modifier.fillMaxWidth()
                        .focusRequester(seasonRequesters.getOrPut(season.seasonNumber) { FocusRequester() })
                        .onFocusChanged { if (it.isFocused) lastSeason = season.seasonNumber }
                        .focusProperties { if (rtl) left = episodeFocus else right = episodeFocus })
            }
            item(key = "information") { information() }
        }
        Column(Modifier.weight(0.73f).fillMaxHeight().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(series.name, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            actions()
            EpisodeBrowseControls(query, onQueryChange)
            Text(stringResource(R.string.series_episodes, episodes.size), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(state = episodeState,
                modifier = Modifier.weight(1f).fillMaxWidth().focusRequester(episodeFocus)
                    .focusProperties {
                        onEnter = {
                            val target = lastEpisode?.takeIf { id -> episodes.any { it.id == id } }
                                ?: episodes.firstOrNull()?.id
                            episodeRequesters[target]?.requestFocus()
                        }
                    }.focusGroup(),
                verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(4.dp)) {
                if (episodes.isEmpty()) item {
                    Text(stringResource(R.string.library_filter_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(episodes.take(visibleLimit), key = { it.id }) { episode ->
                    TvProEpisodeRow(episode, { onEpisodeClick(episode) },
                        Modifier.focusRequester(episodeRequesters.getOrPut(episode.id) { FocusRequester() })
                            .onFocusChanged { if (it.isFocused) lastEpisode = episode.id }
                            .focusProperties {
                                val target = seasonRequesters[lastSeason] ?: seasonFocus
                                if (rtl) right = target else left = target
                            })
                }
                if (visibleLimit < episodes.size) item {
                    TvButton(onClick = { visibleLimit = (visibleLimit + 100).coerceAtMost(episodes.size) }) {
                        Text(stringResource(R.string.library_load_more, visibleLimit, episodes.size))
                    }
                }
            }
        }
    }
}

@Composable
internal fun TvProEpisodeRow(episode: Episode, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(2.dp)
    val durationMs = episode.durationSeconds.toLong() * 1000L
    val progress = if (durationMs > 0) (episode.watchProgress.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    TvClickableSurface(onClick = onClick, modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(shape), scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(containerColor = colors.surfaceVariant,
            contentColor = colors.onSurface, focusedContainerColor = colors.primary, focusedContentColor = colors.onPrimary),
        border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, colors.primary), shape = shape))
    ) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(132.dp).aspectRatio(16f / 9f).background(colors.surface), contentAlignment = Alignment.Center) {
                    AsyncImage(episode.coverUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Icon(Icons.Default.PlayArrow, null, tint = colors.onSurface)
                    Text(episode.episodeNumber.toString(), Modifier.align(Alignment.TopStart)
                        .background(colors.surface).padding(horizontal = 6.dp, vertical = 2.dp),
                        color = colors.secondary, style = MaterialTheme.typography.labelLarge)
                }
                Column(Modifier.weight(1f).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(episode.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.series_detail_play_episode, episode.seasonNumber, episode.episodeNumber),
                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (episode.watchProgress > 0 && durationMs > 0) LinearProgressIndicator(
                progress = { progress }, modifier = Modifier.fillMaxWidth().height(3.dp),
                color = colors.secondary, trackColor = colors.surface)
        }
    }
}
