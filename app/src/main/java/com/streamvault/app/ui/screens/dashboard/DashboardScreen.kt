package com.MegaStream.app.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PlayArrow
import com.MegaStream.app.ui.notifications.DeviceNotificationsAction
import com.MegaStream.app.ui.time.createTimeFormat
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Search
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.MegaStream.app.R
import com.MegaStream.app.ui.components.shell.mediaCardShape
import com.MegaStream.app.ui.model.AppUiStyle
import com.MegaStream.app.ui.theme.LocalAppUiStyle
import com.MegaStream.app.device.rememberIsTelevisionDevice
import com.MegaStream.app.ui.components.ChannelLogoBadge
import com.MegaStream.app.navigation.Routes
import com.MegaStream.app.ui.components.CategoryRow
import com.MegaStream.app.ui.components.ChannelCard
import com.MegaStream.app.ui.components.ContinueWatchingRow
import com.MegaStream.app.ui.components.MovieCard
import com.MegaStream.app.ui.components.rememberCrossfadeImageModel
import com.MegaStream.app.ui.components.SeriesCard
import com.MegaStream.app.ui.components.shell.AppNavigationChrome
import com.MegaStream.app.ui.components.shell.AppHeroHeader
import com.MegaStream.app.ui.components.shell.AppScreenScaffold
import com.MegaStream.app.ui.components.shell.StatusPill
import com.MegaStream.app.ui.components.shell.MediaSurfaceColors as AppColors
import com.MegaStream.app.ui.time.LocalAppTimeFormat
import com.MegaStream.app.ui.time.createDateTimeFormat
import com.MegaStream.app.ui.components.shell.MediaSurfaceColors.Brand as Primary
import com.MegaStream.app.ui.components.shell.MediaSurfaceColors.Focus as FocusBorder
import com.MegaStream.app.ui.components.shell.MediaSurfaceColors.SurfaceElevated as SurfaceElevated
import com.MegaStream.app.ui.components.shell.MediaSurfaceColors.SurfaceEmphasis as SurfaceHighlight
import com.MegaStream.app.ui.components.shell.MediaSurfaceColors.TextPrimary as OnBackground
import com.MegaStream.app.ui.components.shell.MediaSurfaceColors.TextPrimary as TextPrimary
import com.MegaStream.app.ui.components.shell.MediaSurfaceColors.TextTertiary as OnSurfaceDim
import com.MegaStream.app.ui.components.shell.MediaSurfaceColors.TextTertiary as TextTertiary
import com.MegaStream.domain.model.Channel
import com.MegaStream.domain.model.Movie
import com.MegaStream.domain.model.PlaybackHistory
import com.MegaStream.domain.model.Series
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.BorderStroke
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.MegaStream.app.ui.interaction.TvClickableSurface
import com.MegaStream.app.ui.interaction.TvButton
import com.MegaStream.app.ui.interaction.TvIconButton

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    onNavigate: (String) -> Unit,
    onAddProvider: () -> Unit,
    onRecentChannelClick: (Channel, Long?) -> Unit,
    onFavoriteChannelClick: (Channel, Long?) -> Unit,
    onMovieClick: (Movie) -> Unit,
    onSeriesClick: (Series) -> Unit,
    onPlaybackHistoryClick: (PlaybackHistory) -> Unit,
    currentRoute: String,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isStudio = LocalAppUiStyle.current == AppUiStyle.STUDIO
    val recordingChannelIds by viewModel.recordingChannelIds.collectAsStateWithLifecycle()
    val scheduledChannelIds by viewModel.scheduledChannelIds.collectAsStateWithLifecycle()
    val provider = uiState.provider
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.userMessageShown()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppScreenScaffold(
            currentRoute = currentRoute,
            onNavigate = onNavigate,
            title = stringResource(R.string.nav_home),
            subtitle = provider?.name,
            navigationChrome = AppNavigationChrome.TopBar,
            initialTopNavigationFocusRoute = Routes.LIVE_TV,
            compactHeader = true,
            showScreenHeader = false
        ) {
            if (provider == null) {
                EmptyDashboard(
                    onAddProvider = onAddProvider,
                    onOpenSettings = { onNavigate(Routes.SETTINGS) }
                )
                return@AppScreenScaffold
            }
            val orderedSections = rememberDashboardSections(uiState)

            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 28.dp)
            ) {
                if (isStudio) {
                    item(key = "studio_home_hero") {
                        val movie = uiState.recentMovies.firstOrNull {
                            !it.isAdult && !it.isUserProtected &&
                                (!it.backdropUrl.isNullOrBlank() || !it.posterUrl.isNullOrBlank())
                        }
                        val series = uiState.recentSeries.firstOrNull {
                            !it.isAdult && !it.isUserProtected &&
                                (!it.backdropUrl.isNullOrBlank() || !it.posterUrl.isNullOrBlank())
                        }
                        val feature = when {
                            movie != null -> DashboardFeature(
                                title = movie.name,
                                summary = movie.plot?.takeIf { it.isNotBlank() } ?: movie.year.orEmpty(),
                                artworkUrl = movie.backdropUrl?.takeIf { it.isNotBlank() } ?: movie.posterUrl,
                                actionLabel = stringResource(R.string.nav_movies)
                            )
                            series != null -> DashboardFeature(
                                title = series.name,
                                summary = series.plot.orEmpty(),
                                artworkUrl = series.backdropUrl?.takeIf { it.isNotBlank() } ?: series.posterUrl,
                                actionLabel = stringResource(R.string.nav_series)
                            )
                            else -> DashboardFeature(title = provider.name)
                        }
                        StudioDashboardTopBar(
                            expirationDate = uiState.providerHealth.expirationDate,
                            onOpenSettings = { onNavigate(Routes.SETTINGS) }
                        )
                        Spacer(Modifier.height(10.dp))
                        StudioDashboardPanelsRow(
                            feature = feature,
                            heroChannels = uiState.heroChannels,
                            liveChannelCount = uiState.stats.liveChannelCount,
                            recentMovies = uiState.recentMovies,
                            recentSeries = uiState.recentSeries,
                            movieCount = uiState.stats.movieLibraryCount,
                            seriesCount = uiState.stats.seriesLibraryCount,
                            onWatchLive = { onNavigate(Routes.LIVE_TV) },
                            onOpenMovies = { onNavigate(Routes.MOVIES) },
                            onOpenSeries = { onNavigate(Routes.SERIES) }
                        )
                    }
                    item(key = "dashboard_feature_tiles") {
                        DashboardFeatureTilesRow(
                            heroChannels = uiState.heroChannels,
                            favoriteChannelTotal = uiState.favoriteChannelTotal,
                            continueWatching = uiState.continueWatching,
                            currentCombinedProfileId = uiState.currentCombinedProfileId,
                            onNavigate = onNavigate,
                            onFavoriteChannelClick = onFavoriteChannelClick,
                            onPlaybackHistoryClick = onPlaybackHistoryClick
                        )
                    }
                    item(key = "dashboard_quick_actions") {
                        DashboardQuickActionsRow(onNavigate = onNavigate)
                    }
                }
                if (uiState.isLoading && orderedSections.isEmpty()) {
                    item(key = "dashboard_loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 28.dp, bottom = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Primary)
                        }
                    }
                }
                if (uiState.providerWarnings.isNotEmpty()) {
                    item(key = "provider_warnings") {
                        DashboardProviderWarningCard(
                            warnings = uiState.providerWarnings,
                            onOpenSettings = { onNavigate(Routes.SETTINGS) }
                        )
                    }
                }
                uiState.updateNotice?.let { updateNotice ->
                    item(key = "update_notice") {
                        DashboardUpdateCard(
                            notice = updateNotice,
                            onOpenSettings = { onNavigate(Routes.SETTINGS) },
                            onInstallUpdate = viewModel::installDownloadedUpdate
                        )
                    }
                }
                items(orderedSections, key = { it.name }) { section ->
                    when (section) {
                    DashboardHomeSection.LIVE_SHORTCUTS -> DashboardShortcutRow(
                        title = stringResource(R.string.dashboard_live_shortcuts),
                        subtitle = stringResource(R.string.dashboard_live_shortcuts_subtitle),
                        shortcuts = uiState.liveShortcuts,
                        onShortcutClick = { shortcut ->
                            shortcut.categoryId?.let { categoryId ->
                                onNavigate(Routes.liveTv(categoryId))
                            } ?: onNavigate(Routes.LIVE_TV)
                        }
                    )

                    DashboardHomeSection.FAVORITE_CHANNELS -> FavoriteChannelsRow(
                        title = stringResource(R.string.dashboard_favorite_channels),
                        channels = uiState.favoriteChannels,
                        onSeeAll = { onNavigate(Routes.liveTv(com.MegaStream.domain.model.VirtualCategoryIds.FAVORITES)) },
                        onChannelClick = { channel ->
                            onFavoriteChannelClick(channel, uiState.currentCombinedProfileId)
                        }
                    )

                    DashboardHomeSection.RECENT_CHANNELS -> CategoryRow(
                        title = stringResource(R.string.dashboard_recent_channels),
                        items = uiState.recentChannels,
                        keySelector = { it.id },
                        onSeeAll = { onNavigate(Routes.liveTv(com.MegaStream.domain.model.VirtualCategoryIds.RECENT)) }
                    ) { channel ->
                        ChannelCard(
                            channel = channel,
                            compact = isStudio,
                            isRecording = channel.id in recordingChannelIds,
                            isScheduledRecording = channel.id in scheduledChannelIds,
                            onClick = { onRecentChannelClick(channel, uiState.currentCombinedProfileId) }
                        )
                    }

                    DashboardHomeSection.CONTINUE_WATCHING -> ContinueWatchingRow(
                        items = uiState.continueWatching,
                        onItemClick = onPlaybackHistoryClick
                    )

                    DashboardHomeSection.RECENT_MOVIES -> CategoryRow(
                        title = stringResource(R.string.dashboard_recent_movies),
                        items = uiState.recentMovies,
                        keySelector = { it.id },
                        onSeeAll = { onNavigate(Routes.MOVIES) }
                    ) { movie ->
                        MovieCard(
                            movie = movie,
                            onClick = { onMovieClick(movie) }
                        )
                    }

                    DashboardHomeSection.RECENT_SERIES -> CategoryRow(
                        title = stringResource(R.string.dashboard_recent_series),
                        items = uiState.recentSeries,
                        keySelector = { it.id },
                        onSeeAll = { onNavigate(Routes.SERIES) }
                    ) { series ->
                        SeriesCard(
                            series = series,
                            subtitle = series.releaseDate ?: stringResource(R.string.dashboard_updated_series_badge),
                            onClick = { onSeriesClick(series) }
                        )
                    }
                }
            }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )
    }
}

@Composable
private fun StudioDashboardTopBar(
    expirationDate: Long?,
    onOpenSettings: () -> Unit
) {
    val (timeLabel, dateLabel) = rememberDashboardClock()
    val daysRemaining = remember(expirationDate) {
        expirationDate?.let {
            ((it - System.currentTimeMillis() + 86_400_000L - 1) / 86_400_000L).toInt().coerceAtLeast(0)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .background(AppColors.Surface, RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.Star,
                contentDescription = null,
                tint = AppColors.Warning,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = stringResource(R.string.dashboard_daily_tip),
                style = MaterialTheme.typography.labelMedium,
                color = TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            modifier = Modifier
                .background(AppColors.SurfaceElevated, RoundedCornerShape(20.dp))
                .border(1.dp, Primary.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(Primary, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(15.dp)
                )
            }
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleSmall,
                color = OnBackground
            )
            Text(
                text = dateLabel,
                style = MaterialTheme.typography.labelMedium,
                color = TextTertiary,
                maxLines = 1
            )
            Text(
                text = timeLabel,
                style = MaterialTheme.typography.titleSmall,
                color = OnBackground
            )
        }
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (daysRemaining != null) {
                Row(
                    modifier = Modifier
                        .background(AppColors.Surface, RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(AppColors.Success, RoundedCornerShape(50))
                    )
                    Text(
                        text = stringResource(
                            R.string.dashboard_status_active_days,
                            pluralStringResource(R.plurals.dashboard_days_remaining, daysRemaining, daysRemaining)
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = OnBackground,
                        maxLines = 1
                    )
                }
            }
            DeviceNotificationsAction()
            TvIconButton(onClick = onOpenSettings) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = stringResource(R.string.nav_settings)
                )
            }
        }
    }
}

@Composable
private fun rememberDashboardClock(): Pair<String, String> {
    val appTimeFormat = LocalAppTimeFormat.current
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            nowMs = System.currentTimeMillis()
        }
    }
    val timeFormat = remember(appTimeFormat) { appTimeFormat.createTimeFormat() }
    val dateFormat = remember {
        java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG, java.util.Locale.getDefault())
    }
    val timeLabel = remember(nowMs, timeFormat) { timeFormat.format(Date(nowMs)) }
    val dateLabel = remember(nowMs, dateFormat) { dateFormat.format(Date(nowMs)) }
    return timeLabel to dateLabel
}

@Composable
private fun StudioDashboardPanelsRow(
    feature: DashboardFeature,
    heroChannels: List<Channel>,
    liveChannelCount: Int,
    recentMovies: List<Movie>,
    recentSeries: List<Series>,
    movieCount: Int,
    seriesCount: Int,
    onWatchLive: () -> Unit,
    onOpenMovies: () -> Unit,
    onOpenSeries: () -> Unit
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val bandHeight = when {
        screenHeight >= 720.dp -> 430.dp
        screenHeight >= 600.dp -> 380.dp
        else -> 330.dp
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .height(bandHeight),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StudioDashboardHero(
            feature = feature,
            heroChannels = heroChannels,
            liveChannelCount = liveChannelCount,
            bandHeight = bandHeight,
            onWatchLive = onWatchLive,
            modifier = Modifier.weight(1.5f)
        )
        StudioVodFanPanel(
            title = stringResource(R.string.nav_movies),
            count = movieCount,
            countLabel = pluralStringResource(R.plurals.dashboard_movie_count_label, movieCount),
            posters = recentMovies.mapNotNull { it.posterUrl?.takeIf(String::isNotBlank) }.take(3),
            icon = painterResource(R.drawable.ic_movie_24),
            fallbackColors = listOf(Primary.copy(alpha = 0.45f), AppColors.SurfaceElevated),
            onOpen = onOpenMovies,
            modifier = Modifier.weight(1f)
        )
        StudioVodFanPanel(
            title = stringResource(R.string.nav_series),
            count = seriesCount,
            countLabel = pluralStringResource(R.plurals.dashboard_series_count_label, seriesCount),
            posters = recentSeries.mapNotNull { it.posterUrl?.takeIf(String::isNotBlank) }.take(3),
            icon = painterResource(R.drawable.ic_series_24),
            fallbackColors = listOf(AppColors.Warning.copy(alpha = 0.30f), AppColors.SurfaceElevated),
            onOpen = onOpenSeries,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StudioDashboardHero(
    feature: DashboardFeature,
    heroChannels: List<Channel>,
    liveChannelCount: Int,
    bandHeight: Dp,
    onWatchLive: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val isTelevisionDevice = rememberIsTelevisionDevice()
    LaunchedEffect(isTelevisionDevice) {
        if (isTelevisionDevice) {
            delay(150)
            focusRequester.requestFocus()
        }
    }
    val heroShape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(Primary.copy(alpha = 0.30f), heroShape)
            .padding(2.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(18.dp),
            colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            border = Border(
                border = BorderStroke(2.dp, Primary.copy(alpha = 0.55f)),
                shape = RoundedCornerShape(18.dp)
            )
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                feature.artworkUrl?.takeIf { it.isNotBlank() }?.let { artwork ->
                    AsyncImage(
                        model = rememberCrossfadeImageModel(artwork),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize()
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.background.copy(alpha = 0.72f),
                                        Color.Transparent,
                                        MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
                                        MaterialTheme.colorScheme.background.copy(alpha = 0.88f)
                                    )
                                )
                            )
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .background(Primary.copy(alpha = 0.20f), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(AppColors.Live, RoundedCornerShape(50))
                        )
                        Text(
                            text = stringResource(R.string.card_live_badge),
                            style = MaterialTheme.typography.labelMedium,
                            color = OnBackground
                        )
                    }
                    Text(
                        text = stringResource(R.string.nav_live_tv),
                        style = MaterialTheme.typography.headlineSmall,
                        color = OnBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.dashboard_channels_format, liveChannelCount),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextTertiary
                        )
                        Spacer(Modifier.weight(1f))
                        TvButton(
                            onClick = onWatchLive,
                            modifier = Modifier.focusRequester(focusRequester),
                            colors = ButtonDefaults.colors(
                                containerColor = Color.White,
                                contentColor = Color(0xFF1D1115)
                            )
                        ) {
                            Text(stringResource(R.string.dashboard_watch_now))
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                    val tileSize = when {
                        bandHeight >= 420.dp -> 48.dp
                        bandHeight >= 370.dp -> 44.dp
                        else -> 38.dp
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(4) { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                repeat(4) { column ->
                                    val channel = heroChannels.getOrNull(row * 4 + column)
                                    if (channel != null) {
                                        ChannelLogoBadge(
                                            channelName = channel.name,
                                            logoUrl = channel.logoUrl,
                                            modifier = Modifier.size(tileSize),
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(tileSize)
                                                .background(AppColors.SurfaceElevated, RoundedCornerShape(10.dp))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardHero(
    providerName: String,
    feature: DashboardFeature,
    stats: DashboardStats,
    onOpenLiveTv: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSavedLibrary: () -> Unit,
    onFeatureAction: () -> Unit
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val isTelevisionDevice = rememberIsTelevisionDevice()
    // Default the D-pad focus to the Live TV action when the dashboard opens,
    // so a single OK press starts watching. Only on TV, where focus drives
    // navigation; on touch devices it's a no-op.
    val liveTvFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (isTelevisionDevice) {
            delay(150)
            runCatching { liveTvFocusRequester.requestFocus() }
        }
    }
    val heroHeight = when {
        screenWidth < 700.dp -> 176.dp
        !isTelevisionDevice && screenWidth < 1280.dp -> 196.dp
        else -> 220.dp
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        if (!feature.artworkUrl.isNullOrBlank()) {
            AsyncImage(
                model = rememberCrossfadeImageModel(feature.artworkUrl),
                contentDescription = feature.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(heroHeight)
                    .clip(mediaCardShape(28.dp))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(heroHeight)
                    .clip(mediaCardShape(28.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.88f),
                                Color.Black.copy(alpha = 0.72f),
                                Color.Black.copy(alpha = 0.34f)
                            )
                        )
                    )
            )
        }

        AppHeroHeader(
            eyebrow = providerName,
            title = feature.title.ifBlank { stringResource(R.string.dashboard_title) },
            subtitle = feature.summary.ifBlank { stringResource(R.string.dashboard_subtitle, providerName) },
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight),
            footer = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill(label = stringResource(R.string.nav_live_tv), containerColor = AppColors.BrandMuted)
                        StatusPill(label = stringResource(R.string.nav_epg), containerColor = AppColors.SurfaceEmphasis)
                        StatusPill(label = stringResource(R.string.favorites_title), containerColor = AppColors.Warning, contentColor = Color.Black)
                    }
                    DashboardStatRow(stats = stats)
                }
            },
            actions = {
                DashboardActionButton(
                    label = stringResource(R.string.nav_live_tv),
                    onClick = onOpenLiveTv,
                    modifier = Modifier.focusRequester(liveTvFocusRequester)
                )
                DashboardActionButton(label = stringResource(R.string.nav_epg), onClick = onOpenGuide)
                DashboardActionButton(label = stringResource(R.string.dashboard_search_library), onClick = onOpenSearch)
                DashboardActionButton(label = stringResource(R.string.favorites_title), onClick = onOpenSavedLibrary)
                if (feature.actionLabel.isNotBlank()) {
                    DashboardActionButton(
                        label = feature.actionLabel,
                        onClick = onFeatureAction
                    )
                }
            }
        )
    }
}

@Composable
private fun StudioVodFanPanel(
    title: String,
    count: Int,
    countLabel: String,
    posters: List<String>,
    icon: Painter,
    fallbackColors: List<Color>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(20.dp),
        colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = OnBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TvIconButton(onClick = onOpen) {
                    Icon(
                        painter = icon,
                        contentDescription = title,
                        tint = Primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                StudioPosterCard(
                    posterUrl = posters.getOrNull(2),
                    fallbackColors = fallbackColors,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(x = 34.dp)
                        .zIndex(1f)
                        .graphicsLayer { rotationZ = 12f }
                )
                StudioPosterCard(
                    posterUrl = posters.getOrNull(0),
                    fallbackColors = fallbackColors,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .zIndex(2f)
                )
                StudioPosterCard(
                    posterUrl = posters.getOrNull(1),
                    fallbackColors = fallbackColors,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(x = (-34).dp)
                        .zIndex(1f)
                        .graphicsLayer { rotationZ = -12f }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = OnBackground
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = countLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextTertiary
                )
            }
        }
    }
}

@Composable
private fun StudioPosterCard(
    posterUrl: String?,
    fallbackColors: List<Color>,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(86.dp)
            .height(128.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(fallbackColors))
    ) {
        posterUrl?.takeIf { it.isNotBlank() }?.let { poster ->
            AsyncImage(
                model = rememberCrossfadeImageModel(poster),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun DashboardFeatureTilesRow(
    heroChannels: List<Channel>,
    favoriteChannelTotal: Int,
    continueWatching: List<PlaybackHistory>,
    currentCombinedProfileId: Long?,
    onNavigate: (String) -> Unit,
    onFavoriteChannelClick: (Channel, Long?) -> Unit,
    onPlaybackHistoryClick: (PlaybackHistory) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val logoChannel = heroChannels.firstOrNull()
        StudioFeatureTile(
            title = stringResource(R.string.dashboard_tile_guide),
            subtitle = stringResource(R.string.dashboard_tile_guide_subtitle),
            onClick = { onNavigate(Routes.EPG) },
            modifier = Modifier.weight(1f)
        ) {
            if (logoChannel != null) {
                ChannelLogoBadge(
                    channelName = logoChannel.name,
                    logoUrl = logoChannel.logoUrl,
                    modifier = Modifier.size(34.dp),
                    shape = RoundedCornerShape(8.dp)
                )
            } else {
                Icon(
                    painterResource(R.drawable.ic_live_tv_24),
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        StudioFeatureTile(
            title = stringResource(R.string.dashboard_tile_matches),
            subtitle = stringResource(R.string.dashboard_tile_matches_subtitle),
            onClick = { onNavigate(Routes.EPG) },
            modifier = Modifier.weight(1f)
        )
        StudioFeatureTile(
            title = stringResource(R.string.dashboard_tile_favorites),
            subtitle = stringResource(R.string.dashboard_stat_favorites, favoriteChannelTotal),
            onClick = {
                onNavigate(
                    com.MegaStream.domain.model.VirtualCategoryIds.let { Routes.liveTv(it.FAVORITES) }
                )
            },
            modifier = Modifier.weight(1f)
        )
        StudioFeatureTile(
            title = stringResource(R.string.dashboard_tile_continue),
            subtitle = continueWatching.firstOrNull()?.title
                ?: stringResource(R.string.dashboard_tile_continue_empty),
            onClick = {
                val first = continueWatching.firstOrNull()
                if (first != null) onPlaybackHistoryClick(first) else onNavigate(Routes.MOVIES)
            },
            modifier = Modifier.weight(1f)
        )
        StudioFeatureTile(
            title = stringResource(R.string.dashboard_tile_kids),
            subtitle = stringResource(R.string.dashboard_tile_kids_subtitle),
            onClick = { onNavigate(Routes.LIVE_TV) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StudioFeatureTile(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = AppColors.SurfaceEmphasis
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, AppColors.SurfaceEmphasis), 16.dp),
            focusedBorder = Border(BorderStroke(1.dp, Primary), 16.dp)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            leading?.invoke()
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = OnBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DashboardQuickActionsRow(onNavigate: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StudioQuickActionTile(
            icon = painterResource(R.drawable.ic_search_24),
            title = stringResource(R.string.dashboard_quick_search),
            subtitle = stringResource(R.string.dashboard_quick_search_subtitle),
            onClick = { onNavigate(Routes.SEARCH) },
            modifier = Modifier.weight(1f)
        )
        StudioQuickActionTile(
            icon = painterResource(R.drawable.ic_live_tv_24),
            title = stringResource(R.string.dashboard_quick_guide),
            subtitle = stringResource(R.string.dashboard_quick_guide_subtitle),
            onClick = { onNavigate(Routes.EPG) },
            modifier = Modifier.weight(1f)
        )
        StudioQuickActionTile(
            icon = painterResource(R.drawable.ic_live_tv_24),
            title = stringResource(R.string.dashboard_quick_most_watched),
            subtitle = stringResource(R.string.dashboard_quick_your_history),
            onClick = { onNavigate(Routes.liveTv(com.MegaStream.domain.model.VirtualCategoryIds.RECENT)) },
            modifier = Modifier.weight(1f)
        )
        StudioQuickActionTile(
            icon = painterResource(R.drawable.ic_movie_24),
            title = stringResource(R.string.dashboard_quick_top_movies),
            subtitle = stringResource(R.string.dashboard_quick_top_rated),
            onClick = { onNavigate(Routes.MOVIES) },
            modifier = Modifier.weight(1f)
        )
        StudioQuickActionTile(
            icon = painterResource(R.drawable.ic_series_24),
            title = stringResource(R.string.dashboard_quick_top_series),
            subtitle = stringResource(R.string.dashboard_quick_top_rated),
            onClick = { onNavigate(Routes.SERIES) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StudioQuickActionTile(
    icon: Painter,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AppColors.Surface,
            focusedContainerColor = AppColors.SurfaceEmphasis
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, AppColors.SurfaceEmphasis), 16.dp),
            focusedBorder = Border(BorderStroke(1.dp, Primary), 16.dp)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(22.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = OnBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun DashboardStatRow(
    stats: DashboardStats
) {
    val statItems = listOf(
        stringResource(R.string.dashboard_stat_live, stats.liveChannelCount),
        stringResource(R.string.dashboard_stat_favorites, stats.favoriteChannelCount),
        stringResource(R.string.dashboard_stat_recent, stats.recentChannelCount),
        stringResource(R.string.dashboard_stat_resume, stats.continueWatchingCount),
        stringResource(R.string.dashboard_stat_movies, stats.movieLibraryCount),
        stringResource(R.string.dashboard_stat_series, stats.seriesLibraryCount)
    )

    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(statItems, key = { it }) { statLabel ->
            Surface(
                shape = RoundedCornerShape(999.dp),
                colors = SurfaceDefaults.colors(
                    containerColor = AppColors.Surface.copy(alpha = 0.64f)
                )
            ) {
                Text(
                    text = statLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun DashboardShortcutRow(
    title: String,
    subtitle: String,
    shortcuts: List<DashboardLiveShortcut>,
    onShortcutClick: (DashboardLiveShortcut) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(shortcuts, key = { "${it.type}:${it.categoryId}:${it.label}" }) { shortcut ->
                DashboardShortcutCard(
                    shortcut = shortcut,
                    onClick = { onShortcutClick(shortcut) }
                )
            }
        }
    }
}

@Composable
private fun DashboardShortcutCard(
    shortcut: DashboardLiveShortcut,
    onClick: () -> Unit
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val isTelevisionDevice = rememberIsTelevisionDevice()
    val cardWidth = when {
        screenWidth < 700.dp -> 148.dp
        !isTelevisionDevice && screenWidth < 1280.dp -> 160.dp
        else -> 170.dp
    }
    val accentColor = when (shortcut.type) {
        DashboardShortcutType.FAVORITES -> Color(0xFFFFC857)
        DashboardShortcutType.RECENT -> Color(0xFF4FD1C5)
        DashboardShortcutType.LAST_GROUP -> Color(0xFF60A5FA)
        DashboardShortcutType.CUSTOM_GROUP -> Primary
    }

    TvClickableSurface(
        onClick = onClick,
        modifier = Modifier
            .width(cardWidth)
            .height(76.dp),
        shape = ClickableSurfaceDefaults.shape(mediaCardShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = SurfaceElevated,
            focusedContainerColor = SurfaceHighlight
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.28f)),
                shape = mediaCardShape(16.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, FocusBorder),
                shape = mediaCardShape(16.dp)
            )
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(accentColor)
                )
                Text(
                    text = shortcut.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = shortcut.detail,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DashboardActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TvButton(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = Primary.copy(alpha = 0.18f),
            focusedContainerColor = Primary.copy(alpha = 0.32f),
            contentColor = TextPrimary
        )
    ) {
        Text(text = label)
    }
}

@Composable
private fun DashboardProviderHealthCard(
    providerName: String,
    health: DashboardProviderHealth,
    onOpenDiagnostics: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val appTimeFormat = LocalAppTimeFormat.current
    val dateTimeFormat = remember(appTimeFormat) { appTimeFormat.createDateTimeFormat() }
    val syncLabel = remember(health.lastSyncedAt, dateTimeFormat) {
        if (health.lastSyncedAt <= 0L) {
            context.getString(R.string.dashboard_provider_no_sync)
        } else {
            context.getString(R.string.dashboard_provider_synced_at, dateTimeFormat.format(Date(health.lastSyncedAt)))
        }
    }
    val expiryLabel = remember(health.expirationDate) {
        health.expirationDate?.let {
            val format = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            context.getString(R.string.dashboard_provider_expires_at, format.format(Date(it)))
        } ?: context.getString(R.string.dashboard_provider_no_expiry)
    }
    val statusLabel = when (health.status) {
        com.MegaStream.domain.model.ProviderStatus.ACTIVE -> stringResource(R.string.settings_status_active)
        com.MegaStream.domain.model.ProviderStatus.PARTIAL -> stringResource(R.string.settings_status_partial)
        com.MegaStream.domain.model.ProviderStatus.ERROR -> stringResource(R.string.settings_status_error)
        com.MegaStream.domain.model.ProviderStatus.EXPIRED -> stringResource(R.string.settings_status_expired)
        com.MegaStream.domain.model.ProviderStatus.DISABLED -> stringResource(R.string.settings_status_disabled)
        com.MegaStream.domain.model.ProviderStatus.UNKNOWN -> stringResource(R.string.settings_status_unknown)
    }
    val sourceLabel = when (health.type) {
        com.MegaStream.domain.model.ProviderType.XTREAM_CODES -> stringResource(R.string.dashboard_provider_xtream)
        com.MegaStream.domain.model.ProviderType.M3U -> stringResource(R.string.dashboard_provider_m3u)
        com.MegaStream.domain.model.ProviderType.STALKER_PORTAL -> "Stalker/MAG Portal"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 4.dp),
        shape = mediaCardShape(22.dp),
        colors = SurfaceDefaults.colors(containerColor = SurfaceHighlight)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.dashboard_provider_health_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Text(
                    text = providerName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = OnSurfaceDim
                )
                Text(
                    text = "$syncLabel | $expiryLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    DashboardHealthPill(
                        label = statusLabel,
                        value = stringResource(R.string.dashboard_provider_status)
                    )
                }
                item {
                    DashboardHealthPill(
                        label = sourceLabel,
                        value = stringResource(R.string.dashboard_provider_source)
                    )
                }
                item {
                    DashboardHealthPill(
                        label = health.maxConnections.toString(),
                        value = stringResource(R.string.dashboard_provider_connections)
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 0.dp),
            horizontalArrangement = Arrangement.End
        ) {
            DashboardActionButton(
                label = stringResource(R.string.dashboard_warning_review),
                onClick = onOpenDiagnostics
            )
        }
    }
}

@Composable
private fun DashboardHealthPill(
    label: String,
    value: String
) {
    Surface(
        shape = mediaCardShape(18.dp),
        colors = SurfaceDefaults.colors(containerColor = Color.White.copy(alpha = 0.08f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceDim
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = TextPrimary
            )
        }
    }
}

@Composable
private fun DashboardProviderWarningCard(
    warnings: List<String>,
    onOpenSettings: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 6.dp),
        shape = mediaCardShape(20.dp),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.dashboard_warning_title),
                style = MaterialTheme.typography.titleMedium,
                color = Primary
            )
            Text(
                text = warnings.take(3).joinToString(" | "),
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceDim
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DashboardActionButton(
                    label = stringResource(R.string.dashboard_warning_review),
                    onClick = onOpenSettings
                )
            }
        }
    }
}

@Composable
private fun DashboardUpdateCard(
    notice: DashboardUpdateNotice,
    onOpenSettings: () -> Unit,
    onInstallUpdate: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 6.dp),
        shape = mediaCardShape(20.dp),
        colors = SurfaceDefaults.colors(containerColor = Primary.copy(alpha = 0.16f)),
        border = Border(BorderStroke(1.dp, Primary.copy(alpha = 0.45f)))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.dashboard_update_title, notice.latestVersionName),
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
            Text(
                text = stringResource(
                    if (notice.installReady) {
                        R.string.dashboard_update_install_ready
                    } else {
                        R.string.dashboard_update_available
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceDim
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DashboardActionButton(
                    label = stringResource(
                        if (notice.installReady) {
                            R.string.dashboard_update_open_installer
                        } else {
                            R.string.dashboard_update_open_settings
                        }
                    ),
                    onClick = {
                        if (notice.installReady) {
                            onInstallUpdate()
                        } else {
                            onOpenSettings()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun EmptyDashboard(
    onAddProvider: () -> Unit,
    onOpenSettings: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val isTelevisionDevice = rememberIsTelevisionDevice()
        val contentModifier = if (maxWidth < 900.dp) {
            Modifier.fillMaxWidth(0.9f)
        } else if (!isTelevisionDevice && maxWidth < 1280.dp) {
            Modifier.fillMaxWidth(0.76f)
        } else {
            Modifier.width(720.dp)
        }

        Surface(
            shape = mediaCardShape(28.dp),
            colors = SurfaceDefaults.colors(
                containerColor = SurfaceHighlight
            )
        ) {
            Column(
                modifier = contentModifier
                    .padding(horizontal = 32.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = stringResource(R.string.dashboard_empty_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = OnBackground
                )
                Text(
                    text = stringResource(R.string.dashboard_empty_body),
                    style = MaterialTheme.typography.titleMedium,
                    color = OnSurfaceDim
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(onClick = onAddProvider) {
                        Text(stringResource(R.string.settings_add_provider))
                    }
                    TvButton(
                        onClick = onOpenSettings,
                        colors = ButtonDefaults.colors(
                            containerColor = SurfaceElevated,
                            focusedContainerColor = Primary.copy(alpha = 0.24f),
                            contentColor = TextPrimary
                        )
                    ) {
                        Text(stringResource(R.string.nav_settings))
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberDashboardSections(
    uiState: DashboardUiState
): List<DashboardHomeSection> {
    return remember(
        uiState.feature.actionType,
        uiState.liveShortcuts,
        uiState.favoriteChannels,
        uiState.recentChannels,
        uiState.continueWatching,
        uiState.recentMovies,
        uiState.recentSeries
    ) {
        val preferred = listOf(
            DashboardHomeSection.FAVORITE_CHANNELS,
            DashboardHomeSection.RECENT_CHANNELS,
            DashboardHomeSection.LIVE_SHORTCUTS,
            DashboardHomeSection.CONTINUE_WATCHING,
            DashboardHomeSection.RECENT_MOVIES,
            DashboardHomeSection.RECENT_SERIES
        )

        preferred.filter { section ->
            when (section) {
                DashboardHomeSection.LIVE_SHORTCUTS -> uiState.liveShortcuts.isNotEmpty()
                DashboardHomeSection.FAVORITE_CHANNELS -> uiState.favoriteChannels.isNotEmpty()
                DashboardHomeSection.RECENT_CHANNELS -> uiState.recentChannels.isNotEmpty()
                DashboardHomeSection.CONTINUE_WATCHING -> uiState.continueWatching.isNotEmpty()
                DashboardHomeSection.RECENT_MOVIES -> uiState.recentMovies.isNotEmpty()
                DashboardHomeSection.RECENT_SERIES -> uiState.recentSeries.isNotEmpty()
            }
        }
    }
}

private enum class DashboardHomeSection {
    LIVE_SHORTCUTS,
    FAVORITE_CHANNELS,
    RECENT_CHANNELS,
    CONTINUE_WATCHING,
    RECENT_MOVIES,
    RECENT_SERIES
}

@Composable
private fun FavoriteChannelsRow(
    title: String,
    channels: List<Channel>,
    onSeeAll: () -> Unit,
    onChannelClick: (Channel) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
            TvClickableSurface(
                onClick = onSeeAll,
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Primary.copy(alpha = 0.12f),
                    focusedContainerColor = Primary.copy(alpha = 0.22f),
                    contentColor = TextTertiary
                )
            ) {
                Text(
                    text = stringResource(R.string.category_see_all),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(channels, key = { it.id }) { channel ->
                FavoriteChannelLogoCard(
                    channel = channel,
                    onClick = { onChannelClick(channel) }
                )
            }
        }
    }
}

@Composable
private fun FavoriteChannelLogoCard(
    channel: Channel,
    onClick: () -> Unit
) {
    TvClickableSurface(
        onClick = onClick,
        modifier = Modifier.width(86.dp),
        shape = ClickableSurfaceDefaults.shape(mediaCardShape(18.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = SurfaceElevated,
            focusedContainerColor = SurfaceHighlight
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, FocusBorder),
                shape = mediaCardShape(18.dp)
            )
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(999.dp))
            ) {
                ChannelLogoBadge(
                    channelName = channel.name,
                    logoUrl = channel.logoUrl,
                    shape = RoundedCornerShape(999.dp),
                    backgroundColor = AppColors.SurfaceEmphasis,
                    contentPadding = PaddingValues(8.dp),
                    textStyle = MaterialTheme.typography.labelLarge,
                    textColor = TextPrimary,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Text(
                text = channel.name,
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
