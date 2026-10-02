package com.MegaStream.app.ui.screens.series

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.MegaStream.app.R
import com.MegaStream.app.ui.components.shell.mediaCardShape
import com.MegaStream.app.ui.components.shell.MediaActionRow
import com.MegaStream.app.ui.components.shell.studioColor
import com.MegaStream.app.ui.model.AppUiStyle
import com.MegaStream.app.ui.theme.LocalAppUiStyle
import com.MegaStream.app.device.rememberIsTelevisionDevice
import com.MegaStream.app.ui.components.rememberCrossfadeImageModel
import com.MegaStream.app.util.formatPositionMs
import com.MegaStream.app.ui.components.shell.ContentMetadataStrip
import com.MegaStream.app.ui.components.shell.EpisodeRowCard
import com.MegaStream.app.ui.components.shell.ExternalRatingsStrip
import com.MegaStream.app.ui.components.shell.StatusPill
import com.MegaStream.app.ui.components.shell.MediaSurfaceColors as AppColors
import com.MegaStream.app.ui.model.formatVodRatingLabel
import com.MegaStream.domain.model.Episode
import com.MegaStream.domain.model.ExternalRatings
import com.MegaStream.domain.model.Season
import com.MegaStream.domain.model.Series
import com.MegaStream.app.ui.interaction.TvClickableSurface
import com.MegaStream.app.ui.interaction.TvButton
import com.MegaStream.app.ui.interaction.TvIconButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.filled.PlayArrow
import com.MegaStream.app.ui.design.requestFocusSafely
import com.MegaStream.app.ui.screens.vod.StudioDetailLayout
import com.MegaStream.app.ui.screens.vod.StudioCategoryButton
import com.MegaStream.app.ui.components.shell.VodCategoryOption
import androidx.compose.runtime.saveable.rememberSaveable
import com.MegaStream.domain.util.EpisodeBrowseQuery
import com.MegaStream.domain.util.EpisodeOrder
import com.MegaStream.domain.util.EpisodeWatchFilter
import com.MegaStream.domain.util.browseSeriesEpisodes

private const val EPISODE_DETAIL_PAGE_SIZE = 100

@Composable
fun SeriesDetailScreen(
    onEpisodeClick: (Episode) -> Unit,
    onResumeClick: ((Episode) -> Unit)? = null,
    onBack: () -> Unit,
    viewModel: SeriesDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val series = uiState.series

    if (uiState.isLoading) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.Canvas),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.series_loading_details), color = AppColors.TextSecondary)
        }
        return
    }

    if (series == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.Canvas),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = uiState.error ?: stringResource(R.string.series_not_found),
                color = AppColors.Live
            )
        }
        return
    }

    SeriesDetailContent(
        series = series,
        selectedSeason = uiState.selectedSeason,
        resumeEpisode = uiState.resumeEpisode,
        unwatchedEpisodeCount = uiState.unwatchedEpisodeCount,
        externalRatings = uiState.externalRatings,
        isLoadingExternalRatings = uiState.isLoadingExternalRatings,
        onToggleFavorite = viewModel::toggleFavorite,
        onSeasonSelected = viewModel::selectSeason,
        onEpisodeClick = onEpisodeClick,
        onResumeClick = onResumeClick ?: onEpisodeClick,
        onBack = onBack
    )
}

@Composable
private fun SeriesDetailContent(
    series: Series,
    selectedSeason: Season?,
    resumeEpisode: Episode?,
    unwatchedEpisodeCount: Int,
    externalRatings: ExternalRatings,
    isLoadingExternalRatings: Boolean,
    onToggleFavorite: () -> Unit,
    onSeasonSelected: (Season) -> Unit,
    onEpisodeClick: (Episode) -> Unit,
    onResumeClick: (Episode) -> Unit,
    onBack: () -> Unit
) {
    val isTelevisionDevice = rememberIsTelevisionDevice()
    var seasonFilter by rememberSaveable(series.id) { mutableStateOf<Int?>(null) }
    var episodeSearch by rememberSaveable(series.id) { mutableStateOf("") }
    var episodeFilter by rememberSaveable(series.id) { mutableStateOf(EpisodeWatchFilter.ALL.name) }
    var episodeOrder by rememberSaveable(series.id) { mutableStateOf(EpisodeOrder.NEWEST.name) }
    val browseQuery = EpisodeBrowseQuery(seasonFilter, episodeSearch,
        EpisodeWatchFilter.valueOf(episodeFilter), EpisodeOrder.valueOf(episodeOrder))
    val onBrowseChange: (EpisodeBrowseQuery) -> Unit = { query ->
        seasonFilter = query.seasonNumber
        episodeSearch = query.search
        episodeFilter = query.watchFilter.name
        episodeOrder = query.order.name
    }
    val onBrowseSeason: (Season) -> Unit = { season ->
        seasonFilter = season.seasonNumber
        onSeasonSelected(season)
    }
    if (LocalAppUiStyle.current == AppUiStyle.STUDIO) {
        val actionFocus = remember { FocusRequester() }
        LaunchedEffect(series.id, resumeEpisode?.id) {
            actionFocus.requestFocusSafely(tag = "StudioSeriesDetail", target = "Playback action")
        }
        StudioDetailLayout(
            title = series.name,
            imageUrl = series.backdropUrl ?: series.posterUrl,
            metadata = listOf(series.releaseDate.orEmpty(), series.genre.orEmpty(),
                if (unwatchedEpisodeCount > 0) stringResource(R.string.series_unwatched_badge, unwatchedEpisodeCount) else ""),
            onBack = onBack,
            actions = {
                MediaActionRow {
                    if (resumeEpisode != null) TvButton(
                        onClick = { onResumeClick(resumeEpisode) },
                        modifier = Modifier.focusRequester(actionFocus),
                        shape = ButtonDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (resumeEpisode.watchProgress > 5000L) stringResource(R.string.series_detail_resume,
                            resumeEpisode.seasonNumber, resumeEpisode.episodeNumber, formatPositionMs(resumeEpisode.watchProgress))
                            else stringResource(R.string.series_detail_play_episode,
                                resumeEpisode.seasonNumber, resumeEpisode.episodeNumber), maxLines = 2)
                    }
                    TvIconButton(onClick = onToggleFavorite,
                        modifier = if (resumeEpisode == null) Modifier.focusRequester(actionFocus) else Modifier) {
                        Icon(if (series.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = stringResource(if (series.isFavorite) R.string.favorites_remove else R.string.favorites_add),
                            tint = if (series.isFavorite) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        ) {
            item(key = "studio_series_episodes") {
                StudioEpisodePicker(series, browseQuery, onBrowseChange, onBrowseSeason, onEpisodeClick)
            }
            item(key = "studio_series_information") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ExternalRatingsStrip(ratings = externalRatings, isLoading = isLoadingExternalRatings)
                    Text(series.plot?.takeIf { it.isNotBlank() } ?: stringResource(R.string.series_plot_fallback),
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ContentMetadataStrip(values = listOf(series.releaseDate.orEmpty(), series.genre.orEmpty()))
                }
            }
        }
        return
    }
    val filteredEpisodes = remember(series, browseQuery) { browseSeriesEpisodes(series, browseQuery) }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Canvas)
    ) {
        val compactLayout = !isTelevisionDevice && maxWidth < 900.dp
        val heroHeight = when {
            maxWidth < 700.dp -> 220.dp
            !isTelevisionDevice && maxWidth < 900.dp -> 280.dp
            else -> 420.dp
        }
        val contentPadding = if (compactLayout) {
            PaddingValues(horizontal = 16.dp, vertical = 20.dp)
        } else {
            PaddingValues(horizontal = 56.dp, vertical = 36.dp)
        }
        val posterWidth = if (compactLayout) 132.dp else 220.dp
        var visibleEpisodeLimit by remember(series.id, browseQuery) {
            mutableStateOf(EPISODE_DETAIL_PAGE_SIZE)
        }
        val visibleEpisodes = filteredEpisodes.take(visibleEpisodeLimit)

        AsyncImage(
            model = rememberCrossfadeImageModel(series.backdropUrl ?: series.posterUrl),
            contentDescription = series.name,
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight)
                .align(Alignment.TopCenter),
            contentScale = ContentScale.Crop
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            AppColors.HeroTop,
                            AppColors.HeroBottom
                        )
                    )
                )
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                TvButton(
                    onClick = onBack,
                    colors = ButtonDefaults.colors(
                        containerColor = AppColors.Surface.copy(alpha = 0.72f),
                        contentColor = AppColors.TextPrimary
                    ),
                    border = ButtonDefaults.border(
                        border = Border(border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.Outline))
                    )
                ) {
                    Text(stringResource(R.string.series_detail_back))
                }
            }

            item {
                if (compactLayout) {
                    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Box(
                            modifier = Modifier
                                .width(posterWidth)
                                .aspectRatio(2f / 3f)
                                .clip(mediaCardShape(24.dp))
                                .background(AppColors.SurfaceElevated)
                        ) {
                            AsyncImage(
                                model = rememberCrossfadeImageModel(series.posterUrl ?: series.backdropUrl),
                                contentDescription = series.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusPill(label = stringResource(R.string.nav_series), containerColor = AppColors.BrandMuted)
                                series.rating.takeIf { it > 0f }?.let {
                                    StatusPill(
                                        label = formatVodRatingLabel(it),
                                        containerColor = AppColors.Warning,
                                        contentColor = Color.Black
                                    )
                                }
                                if (unwatchedEpisodeCount > 0) {
                                    StatusPill(
                                        label = stringResource(R.string.series_unwatched_badge, unwatchedEpisodeCount),
                                        containerColor = AppColors.SurfaceEmphasis
                                    )
                                }
                            }
                            Text(
                                text = series.name,
                                style = MaterialTheme.typography.displayMedium,
                                color = AppColors.TextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            ContentMetadataStrip(
                                values = listOf(
                                    series.releaseDate.orEmpty(),
                                    series.genre.orEmpty(),
                                    series.seasons.firstOrNull { it.seasonNumber == seasonFilter }?.name.orEmpty()
                                )
                            )
                            ExternalRatingsStrip(
                                ratings = externalRatings,
                                isLoading = isLoadingExternalRatings
                            )
                            Text(
                                text = series.plot ?: stringResource(R.string.series_plot_fallback),
                                style = MaterialTheme.typography.bodyLarge,
                                color = AppColors.TextSecondary,
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis
                            )
                            resumeEpisode?.let { ep ->
                                val hasProgress = ep.watchProgress > 5000L
                                SeriesDetailActions(
                                    series = series,
                                    resumeEpisode = ep,
                                    hasProgress = hasProgress,
                                    onResumeClick = onResumeClick,
                                    onToggleFavorite = onToggleFavorite
                                )
                            }
                            if (resumeEpisode == null) {
                                SeriesDetailFavoriteAction(series = series, onToggleFavorite = onToggleFavorite)
                            }
                        }
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Box(
                            modifier = Modifier
                                .width(posterWidth)
                                .aspectRatio(2f / 3f)
                                .clip(mediaCardShape(24.dp))
                                .background(AppColors.SurfaceElevated)
                        ) {
                            AsyncImage(
                                model = rememberCrossfadeImageModel(series.posterUrl ?: series.backdropUrl),
                                contentDescription = series.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusPill(label = stringResource(R.string.nav_series), containerColor = AppColors.BrandMuted)
                                series.rating.takeIf { it > 0f }?.let {
                                    StatusPill(
                                        label = formatVodRatingLabel(it),
                                        containerColor = AppColors.Warning,
                                        contentColor = Color.Black
                                    )
                                }
                                if (unwatchedEpisodeCount > 0) {
                                    StatusPill(
                                        label = stringResource(R.string.series_unwatched_badge, unwatchedEpisodeCount),
                                        containerColor = AppColors.SurfaceEmphasis
                                    )
                                }
                            }
                            Text(
                                text = series.name,
                                style = MaterialTheme.typography.displayMedium,
                                color = AppColors.TextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            ContentMetadataStrip(
                                values = listOf(
                                    series.releaseDate.orEmpty(),
                                    series.genre.orEmpty(),
                                    series.seasons.firstOrNull { it.seasonNumber == seasonFilter }?.name.orEmpty()
                                )
                            )
                            ExternalRatingsStrip(
                                ratings = externalRatings,
                                isLoading = isLoadingExternalRatings
                            )
                            Text(
                                text = series.plot ?: stringResource(R.string.series_plot_fallback),
                                style = MaterialTheme.typography.bodyLarge,
                                color = AppColors.TextSecondary,
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis
                            )
                            resumeEpisode?.let { ep ->
                                val hasProgress = ep.watchProgress > 5000L
                                SeriesDetailActions(
                                    series = series,
                                    resumeEpisode = ep,
                                    hasProgress = hasProgress,
                                    onResumeClick = onResumeClick,
                                    onToggleFavorite = onToggleFavorite
                                )
                            }
                            if (resumeEpisode == null) {
                                SeriesDetailFavoriteAction(series = series, onToggleFavorite = onToggleFavorite)
                            }
                        }
                    }
                }
            }

            if (series.seasons.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = stringResource(R.string.series_seasons),
                            style = MaterialTheme.typography.titleLarge,
                            color = AppColors.TextPrimary
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(vertical = 2.dp)
                        ) {
                            item(key = "all_seasons") {
                                TvButton(onClick = { onBrowseChange(browseQuery.copy(seasonNumber = null)) },
                                    colors = ButtonDefaults.colors(containerColor = if (seasonFilter == null)
                                        AppColors.BrandMuted else AppColors.SurfaceElevated)) {
                                    Text(stringResource(R.string.series_episode_all_seasons))
                                }
                            }
                            items(series.seasons, key = { it.seasonNumber }) { season ->
                                SeasonChip(
                                    season = season,
                                    isSelected = season.seasonNumber == seasonFilter,
                                    onClick = { onBrowseSeason(season) }
                                )
                            }
                        }
                    }
                }
            }

            item(key = "episode_filters") {
                EpisodeBrowseControls(browseQuery, onBrowseChange)
            }
            item(key = "episode_count") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.series_episodes, filteredEpisodes.size),
                        style = MaterialTheme.typography.titleLarge, color = AppColors.TextPrimary)
                    Text(series.seasons.firstOrNull { it.seasonNumber == seasonFilter }?.name
                        ?: stringResource(R.string.series_episode_all_seasons),
                        style = MaterialTheme.typography.bodyMedium, color = AppColors.TextTertiary)
                }
            }
            if (filteredEpisodes.isEmpty()) item(key = "episode_empty") {
                Text(stringResource(R.string.library_filter_empty), color = AppColors.TextSecondary)
            }
            items(visibleEpisodes, key = { it.id }) { episode ->
                EpisodeItem(episode = episode, onClick = { onEpisodeClick(episode) })
            }
            if (visibleEpisodes.size < filteredEpisodes.size) item(key = "episode_more") {
                TvButton(onClick = {
                    visibleEpisodeLimit = (visibleEpisodeLimit + EPISODE_DETAIL_PAGE_SIZE).coerceAtMost(filteredEpisodes.size)
                }) {
                    Text(stringResource(R.string.library_load_more, visibleEpisodes.size, filteredEpisodes.size))
                }
            }
        }
    }
}

@Composable
private fun StudioEpisodePicker(
    series: Series,
    query: EpisodeBrowseQuery,
    onQueryChange: (EpisodeBrowseQuery) -> Unit,
    onSeasonSelected: (Season) -> Unit,
    onEpisodeClick: (Episode) -> Unit
) {
    var visibleLimit by remember(series.id, query) { mutableStateOf(EPISODE_DETAIL_PAGE_SIZE) }
    val episodes = remember(series, query) { browseSeriesEpisodes(series, query) }
    val episodeState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(series.id, query) { episodeState.scrollToItem(0) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        EpisodeBrowseControls(query, onQueryChange)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 600.dp
            val allSeasonsOption = VodCategoryOption(stringResource(R.string.series_episode_all_seasons),
                series.seasons.sumOf { it.episodes.size }, { onQueryChange(query.copy(seasonNumber = null)) })
            Column(Modifier.fillMaxWidth().height(if (compact) 440.dp else 360.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.series_episodes, episodes.size),
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                if (compact) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)) {
                    item(key = "all_seasons") {
                        StudioCategoryButton(allSeasonsOption, query.seasonNumber == null, Modifier.width(156.dp))
                    }
                    items(series.seasons.sortedByDescending { it.seasonNumber }, key = { it.seasonNumber }) { season ->
                        StudioCategoryButton(VodCategoryOption(season.name, season.episodes.size, { onSeasonSelected(season) }),
                            season.seasonNumber == query.seasonNumber, Modifier.width(156.dp))
                    }
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (!compact) LazyColumn(Modifier.width(160.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)) {
                        item { Text(stringResource(R.string.series_seasons), color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge) }
                        item(key = "all_seasons") {
                            StudioCategoryButton(allSeasonsOption, query.seasonNumber == null, Modifier.fillMaxWidth())
                        }
                        items(series.seasons.sortedByDescending { it.seasonNumber }, key = { it.seasonNumber }) { season ->
                            StudioCategoryButton(VodCategoryOption(season.name, season.episodes.size, { onSeasonSelected(season) }),
                                season.seasonNumber == query.seasonNumber, Modifier.fillMaxWidth())
                        }
                    }
                    LazyColumn(state = episodeState, modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                        if (episodes.isEmpty()) item {
                            Text(stringResource(R.string.library_filter_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                        items(episodes.take(visibleLimit), key = { it.id }) { episode ->
                            EpisodeItem(episode, onClick = { onEpisodeClick(episode) })
                        }
                        if (visibleLimit < episodes.size) item {
                            TvButton(onClick = { visibleLimit = (visibleLimit + EPISODE_DETAIL_PAGE_SIZE).coerceAtMost(episodes.size) }) {
                                Text(stringResource(R.string.library_load_more, visibleLimit, episodes.size))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesDetailActions(
    series: Series,
    resumeEpisode: Episode,
    hasProgress: Boolean,
    onResumeClick: (Episode) -> Unit,
    onToggleFavorite: () -> Unit
) {
    MediaActionRow {
        TvButton(
            onClick = { onResumeClick(resumeEpisode) },
            colors = ButtonDefaults.colors(
                containerColor = AppColors.Brand,
                contentColor = studioColor(Color.White, MaterialTheme.colorScheme.onPrimary)
            )
        ) {
            Text(
                text = if (hasProgress) {
                    stringResource(
                        R.string.series_detail_resume,
                        resumeEpisode.seasonNumber,
                        resumeEpisode.episodeNumber,
                        formatPositionMs(resumeEpisode.watchProgress)
                    )
                } else {
                    stringResource(
                        R.string.series_detail_play_episode,
                        resumeEpisode.seasonNumber,
                        resumeEpisode.episodeNumber
                    )
                }
            )
        }
        SeriesDetailFavoriteAction(series = series, onToggleFavorite = onToggleFavorite)
    }
}

@Composable
private fun SeriesDetailFavoriteAction(
    series: Series,
    onToggleFavorite: () -> Unit
) {
    TvIconButton(
        onClick = onToggleFavorite,
        colors = ButtonDefaults.colors(
            containerColor = if (series.isFavorite) AppColors.Brand else AppColors.SurfaceEmphasis,
            contentColor = if (series.isFavorite) studioColor(Color.White, MaterialTheme.colorScheme.onPrimary) else AppColors.TextSecondary
        )
    ) {
        Icon(
            imageVector = if (series.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = stringResource(
                if (series.isFavorite) R.string.favorites_remove else R.string.favorites_add
            )
        )
    }
}

@Composable
fun SeasonChip(
    season: Season,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    TvClickableSurface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(999.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) AppColors.BrandMuted else AppColors.SurfaceElevated,
            contentColor = AppColors.TextPrimary,
            focusedContainerColor = AppColors.SurfaceEmphasis,
            focusedContentColor = AppColors.TextPrimary
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = androidx.compose.foundation.BorderStroke(1.dp, if (isSelected) AppColors.Brand else AppColors.Outline),
                shape = RoundedCornerShape(999.dp)
            ),
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, AppColors.Focus),
                shape = RoundedCornerShape(999.dp)
            )
        )
    ) {
        Text(
            text = season.name,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
fun EpisodeItem(
    episode: Episode,
    onClick: () -> Unit
) {
    TvClickableSurface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = mediaCardShape(18.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AppColors.SurfaceElevated,
            focusedContainerColor = AppColors.SurfaceEmphasis
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        EpisodeRowCard(
            episode = episode,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
