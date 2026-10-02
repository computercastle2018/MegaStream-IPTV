package com.MegaStream.app.ui.components.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import com.MegaStream.domain.util.isPlaybackComplete
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.MegaStream.app.R
import com.MegaStream.app.ui.components.ChannelLogoBadge
import com.MegaStream.app.ui.components.rememberCrossfadeImageModel
import com.MegaStream.app.ui.design.AppColors
import com.MegaStream.app.ui.design.AppMotion
import com.MegaStream.app.ui.design.FocusSpec
import com.MegaStream.app.ui.interaction.mouseClickable
import com.MegaStream.app.ui.interaction.rememberTvInteractionSounds
import com.MegaStream.domain.model.Channel
import com.MegaStream.domain.model.Episode
import com.MegaStream.domain.model.Movie
import com.MegaStream.domain.model.Series
import com.MegaStream.app.ui.model.AppUiStyle
import com.MegaStream.app.ui.theme.LocalAppUiStyle


@Composable
internal fun studioColor(classic: Color, studio: Color): Color =
    if (LocalAppUiStyle.current == AppUiStyle.STUDIO) studio else classic

@Composable
internal fun mediaCardShape(classicRadius: Dp): RoundedCornerShape =
    RoundedCornerShape(if (LocalAppUiStyle.current == AppUiStyle.STUDIO) 8.dp else classicRadius)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MediaActionRow(content: @Composable RowScope.() -> Unit) {
    if (LocalAppUiStyle.current == AppUiStyle.STUDIO) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) { content() }
    } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

// Keep legacy colors exact; only Studio follows the selected MaterialTheme.
internal object MediaSurfaceColors {
    val Canvas: Color
        @Composable get() = studioColor(AppColors.Canvas, MaterialTheme.colorScheme.background)
    val CanvasElevated: Color
        @Composable get() = studioColor(AppColors.CanvasElevated, MaterialTheme.colorScheme.background)
    val Surface: Color
        @Composable get() = studioColor(AppColors.Surface, MaterialTheme.colorScheme.surface)
    val SurfaceElevated: Color
        @Composable get() = studioColor(AppColors.SurfaceElevated, MaterialTheme.colorScheme.surface)
    val SurfaceEmphasis: Color
        @Composable get() = studioColor(AppColors.SurfaceEmphasis, MaterialTheme.colorScheme.surfaceVariant)
    val SurfaceAccent: Color
        @Composable get() = studioColor(AppColors.SurfaceAccent, MaterialTheme.colorScheme.surfaceVariant)
    val Brand: Color
        @Composable get() = studioColor(AppColors.Brand, MaterialTheme.colorScheme.primary)
    val BrandMuted: Color
        @Composable get() = studioColor(AppColors.BrandMuted, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
    val Focus: Color
        @Composable get() = studioColor(AppColors.Focus, MaterialTheme.colorScheme.secondary)
    val TextPrimary: Color
        @Composable get() = studioColor(AppColors.TextPrimary, MaterialTheme.colorScheme.onSurface)
    val TextSecondary: Color
        @Composable get() = studioColor(AppColors.TextSecondary, MaterialTheme.colorScheme.onSurfaceVariant)
    val TextTertiary: Color
        @Composable get() = studioColor(AppColors.TextTertiary, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f))
    val TextDisabled: Color
        @Composable get() = studioColor(AppColors.TextDisabled, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
    val Live: Color
        @Composable get() = studioColor(AppColors.Live, MaterialTheme.colorScheme.error)
    val Success: Color
        @Composable get() = studioColor(AppColors.Success, MaterialTheme.colorScheme.primary)
    val Warning: Color
        @Composable get() = studioColor(AppColors.Warning, MaterialTheme.colorScheme.secondary)
    val Info: Color
        @Composable get() = studioColor(AppColors.Info, MaterialTheme.colorScheme.primary)
    val HeroTop: Color
        @Composable get() = studioColor(AppColors.HeroTop, MaterialTheme.colorScheme.background.copy(alpha = 0.38f))
    val HeroBottom: Color
        @Composable get() = studioColor(AppColors.HeroBottom, MaterialTheme.colorScheme.background.copy(alpha = 0.96f))
    val Outline: Color
        @Composable get() = studioColor(AppColors.Outline, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f))
    val Divider: Color
        @Composable get() = studioColor(AppColors.Divider, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
}

private object LiveChannelRowTicker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val nowMs = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000L)
        }
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 30_000L),
        initialValue = System.currentTimeMillis()
    )
}

@Composable
fun LiveChannelRowCard(
    channel: Channel,
    sourceBadgeLabel: String? = null,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 68.dp
) {
    val isUltraCompact = rowHeight <= 60.dp
    val isDense = rowHeight <= 56.dp
    val contentPadding = if (isUltraCompact) 5.dp else 6.dp
    val horizontalPadding = if (isUltraCompact) 8.dp else 10.dp
    val logoWidth = if (isDense) 42.dp else if (isUltraCompact) 46.dp else 52.dp
    val logoPadding = if (isDense) 5.dp else if (isUltraCompact) 6.dp else 8.dp
    val contentSpacing = if (isUltraCompact) 8.dp else 10.dp
    val badgeSpacing = if (isUltraCompact) 3.dp else 4.dp
    val nowMs by LiveChannelRowTicker.nowMs.collectAsStateWithLifecycle()

    Box(
        modifier = modifier
            .clip(mediaCardShape(18.dp))
            .background(MediaSurfaceColors.SurfaceElevated)
            .fillMaxWidth()
            .height(rowHeight)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalPadding, vertical = contentPadding),
            horizontalArrangement = Arrangement.spacedBy(contentSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(logoWidth)
                    .fillMaxHeight()
                    .clip(mediaCardShape(12.dp))
            ) {
                ChannelLogoBadge(
                    channelName = channel.name,
                    logoUrl = channel.logoUrl,
                    backgroundColor = MediaSurfaceColors.SurfaceEmphasis,
                    contentPadding = PaddingValues(logoPadding),
                    textStyle = MaterialTheme.typography.titleLarge,
                    textColor = MediaSurfaceColors.TextSecondary,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (!isDense) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(badgeSpacing),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusPill(label = stringResource(R.string.card_live_badge), containerColor = MediaSurfaceColors.Live)
                        sourceBadgeLabel?.takeIf { it.isNotBlank() }?.let { label ->
                            StatusPill(
                                label = label,
                                containerColor = MediaSurfaceColors.SurfaceEmphasis,
                                contentColor = MediaSurfaceColors.TextPrimary
                            )
                        }
                        if (channel.isFavorite) {
                            StatusPill(label = stringResource(R.string.badge_saved), containerColor = MediaSurfaceColors.Warning, contentColor = Color.Black)
                        }
                        if (channel.catchUpSupported) {
                            StatusPill(label = stringResource(R.string.badge_catch_up), containerColor = MediaSurfaceColors.Brand)
                        }
                    }
                }
                Text(
                    text = buildString {
                        val numberLabel = channel.number.takeIf { it > 0 }?.toString()?.padStart(2, '0')
                        if (numberLabel != null) {
                            append(numberLabel)
                            append("  ")
                        } else if (channel.number == 0) {
                            append("--  ")
                        }
                        append(channel.name)
                    },
                    style = if (isDense) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleSmall,
                    color = MediaSurfaceColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val program = channel.currentProgram
                if (program != null) {
                    Text(
                        text = program.title,
                        style = if (isDense) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                        color = MediaSurfaceColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val totalDuration = (program.endTime - program.startTime).coerceAtLeast(1L)
                    val elapsed = (nowMs - program.startTime).coerceAtLeast(0L)
                    if (!isDense) {
                        LinearProgressIndicator(
                            progress = { (elapsed.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .clip(RoundedCornerShape(999.dp)),
                            color = MediaSurfaceColors.Info,
                            trackColor = MediaSurfaceColors.SurfaceEmphasis
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.label_no_schedule),
                        style = if (isDense) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                        color = MediaSurfaceColors.TextTertiary
                    )
                }
            }
        }
    }
}

@Composable
fun LiveChannelRowSurface(
    channel: Channel,
    onClick: () -> Unit,
    sourceBadgeLabel: String? = null,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isLocked: Boolean = false,
    isReorderMode: Boolean = false,
    isDragging: Boolean = false,
    rowHeight: Dp = 68.dp
) {
    var isFocused by remember { mutableStateOf(false) }
    val sounds = rememberTvInteractionSounds()
    val focusRequester = remember { FocusRequester() }
    val favoriteLabel = stringResource(R.string.a11y_favorite)
    val catchUpLabel = stringResource(R.string.a11y_catch_up_available)
    val lockedLabel = stringResource(R.string.a11y_locked)
    val channelDescription = buildString {
        append(
            channel.number.takeIf { it > 0 }?.let {
                stringResource(R.string.a11y_channel_with_number, it, channel.name)
            } ?: channel.name
        )
        channel.currentProgram?.title?.takeIf { it.isNotBlank() }?.let {
            append(". ")
            append(stringResource(R.string.a11y_now_playing, it))
        }
        if (channel.isFavorite) {
            append(". ")
            append(favoriteLabel)
        }
        if (channel.catchUpSupported) {
            append(". ")
            append(catchUpLabel)
        }
    }
    val scale by animateFloatAsState(
        targetValue = if (isDragging) FocusSpec.FocusedScale else 1f,
        animationSpec = AppMotion.FocusSpec,
        label = "liveRowScale"
    )

    Surface(
        onClick = {
            sounds.playSelect()
            onClick()
        },
        onLongClick = onLongClick,
        modifier = modifier
            .focusRequester(focusRequester)
            .fillMaxWidth()
            .mouseClickable(
                focusRequester = focusRequester,
                onLongClick = onLongClick,
                onClick = {
                    sounds.playSelect()
                    onClick()
                }
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .semantics(mergeDescendants = true) {
                contentDescription = channelDescription
                if (isLocked) {
                    stateDescription = lockedLabel
                }
            }
            .onFocusChanged {
                if (it.isFocused && !isFocused) {
                    sounds.playNavigate()
                }
                isFocused = it.isFocused
            },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(mediaCardShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MediaSurfaceColors.SurfaceElevated,
            focusedContainerColor = MediaSurfaceColors.SurfaceEmphasis
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(
                    width = if (isDragging) 4.dp else FocusSpec.BorderWidth,
                    color = if (isDragging) MediaSurfaceColors.Warning else MediaSurfaceColors.Focus
                ),
                shape = mediaCardShape(16.dp)
            )
        )
    ) {
        Box {
            LiveChannelRowCard(
                channel = channel,
                sourceBadgeLabel = sourceBadgeLabel,
                modifier = Modifier.fillMaxWidth(),
                rowHeight = rowHeight
            )
            if (isLocked) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MediaSurfaceColors.HeroBottom.copy(alpha = 0.82f)),
                    contentAlignment = Alignment.Center
                ) {
                    StatusPill(
                        label = stringResource(R.string.home_locked_short),
                        containerColor = MediaSurfaceColors.SurfaceEmphasis,
                        contentColor = MediaSurfaceColors.TextPrimary
                    )
                }
            }
            if (isReorderMode && isDragging) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                ) {
                    StatusPill(
                        label = stringResource(R.string.badge_moving),
                        containerColor = MediaSurfaceColors.Warning,
                        contentColor = Color.Black
                    )
                }
            }
            if (!isLocked && !isReorderMode && channel.isFavorite) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                        .padding(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        tint = MediaSurfaceColors.Warning,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun MoviePosterCard(movie: Movie, modifier: Modifier = Modifier) {
    PosterCard(
        imageUrl = movie.posterUrl,
        title = movie.name,
        subtitle = movie.year,
        modifier = modifier
    )
}

@Composable
fun SeriesPosterCard(series: Series, modifier: Modifier = Modifier) {
    PosterCard(
        imageUrl = series.posterUrl,
        title = series.name,
        subtitle = series.releaseDate ?: series.genre,
        modifier = modifier
    )
}

@Composable
fun EpisodeRowCard(episode: Episode, modifier: Modifier = Modifier) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val previewWidth = if (screenWidth < 700.dp) 124.dp else 164.dp
    val durationMs = episode.durationSeconds.toLong() * 1000L
    val showProgress = episode.watchProgress > 5000L && durationMs > 0L &&
        !isPlaybackComplete(episode.watchProgress, durationMs)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(mediaCardShape(18.dp))
            .background(MediaSurfaceColors.SurfaceElevated)
            .padding(16.dp)
    ) {
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(previewWidth)
                        .aspectRatio(16f / 9f)
                        .clip(mediaCardShape(12.dp))
                        .background(MediaSurfaceColors.SurfaceEmphasis),
                    contentAlignment = Alignment.Center
                ) {
                    // Fallback label always visible; covered by AsyncImage on successful load
                    Text(
                        text = stringResource(R.string.label_episode, episode.episodeNumber),
                        style = MaterialTheme.typography.titleMedium,
                        color = MediaSurfaceColors.TextSecondary
                    )
                    if (!episode.coverUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = episode.coverUrl,
                            contentDescription = episode.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = episode.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MediaSurfaceColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    ContentMetadataStrip(
                        values = listOf(stringResource(R.string.label_episode_full, episode.episodeNumber), episode.duration ?: "")
                    )
                    episode.plot?.takeIf { it.isNotBlank() }?.let { plot ->
                        Text(
                            text = plot,
                            style = MaterialTheme.typography.bodySmall,
                            color = MediaSurfaceColors.TextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (showProgress) {
                LinearProgressIndicator(
                    progress = { (episode.watchProgress.toFloat() / durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .height(3.dp),
                    color = MediaSurfaceColors.Brand,
                    trackColor = MediaSurfaceColors.SurfaceEmphasis
                )
            }
        }
    }
}

@Composable
private fun PosterCard(
    imageUrl: String?,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier
) {
    val posterShape = mediaCardShape(12.dp)
    var imageLoaded by remember(imageUrl) { mutableStateOf(false) }
    var imageFailed by remember(imageUrl) { mutableStateOf(false) }
    val showFallback = imageUrl.isNullOrBlank() || imageFailed || !imageLoaded

    Box(
        modifier = modifier
            .clip(posterShape)
            .background(MediaSurfaceColors.SurfaceEmphasis)
    ) {
        // Fallback letter: only shown while no URL, still loading, or load failed
        if (showFallback) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title.take(1).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MediaSurfaceColors.TextSecondary
                )
            }
        }
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = rememberCrossfadeImageModel(imageUrl),
                contentDescription = title,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(posterShape),
                contentScale = if (LocalAppUiStyle.current == AppUiStyle.STUDIO) ContentScale.Crop else ContentScale.Fit,
                onSuccess = { imageLoaded = true },
                onError = { imageFailed = true }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.52f)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, MediaSurfaceColors.HeroBottom)
                    )
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MediaSurfaceColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MediaSurfaceColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
