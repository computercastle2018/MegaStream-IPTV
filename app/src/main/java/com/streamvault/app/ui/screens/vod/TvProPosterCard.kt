package com.MegaStream.app.ui.screens.vod

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import coil3.compose.AsyncImage
import com.MegaStream.app.R
import com.MegaStream.app.ui.components.MovieCard
import com.MegaStream.app.ui.components.SeriesCard
import com.MegaStream.app.ui.interaction.TvClickableSurface
import com.MegaStream.app.ui.model.AppUiStyle
import com.MegaStream.app.ui.model.formatVodRatingLabel
import com.MegaStream.app.ui.theme.LocalAppUiStyle
import com.MegaStream.domain.model.Movie
import com.MegaStream.domain.model.Series

@Composable
internal fun CatalogMovieCard(movie: Movie, onClick: () -> Unit, modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null, isLocked: Boolean = false,
    isReorderMode: Boolean = false, isDragging: Boolean = false) {
    if (LocalAppUiStyle.current == AppUiStyle.TV_PRO) {
        TvProPosterCard(movie.name, movie.posterUrl, movie.rating, movie.isFavorite, isLocked,
            isDragging, onClick, onLongClick, modifier)
    } else MovieCard(movie, onClick, modifier, onLongClick, isLocked,
        isReorderMode = isReorderMode, isDragging = isDragging)
}

@Composable
internal fun CatalogSeriesCard(series: Series, onClick: () -> Unit, modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null, isLocked: Boolean = false,
    isReorderMode: Boolean = false, isDragging: Boolean = false) {
    if (LocalAppUiStyle.current == AppUiStyle.TV_PRO) {
        TvProPosterCard(series.name, series.posterUrl, series.rating, series.isFavorite, isLocked,
            isDragging, onClick, onLongClick, modifier)
    } else SeriesCard(series, onClick, modifier, onLongClick, isLocked,
        isReorderMode = isReorderMode, isDragging = isDragging)
}

@Composable
internal fun TvProPosterCard(title: String, imageUrl: String?, rating: Float, favorite: Boolean,
    locked: Boolean, dragging: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(4.dp)
    val lockedLabel = stringResource(R.string.a11y_locked)
    TvClickableSurface(onClick = onClick, onLongClick = onLongClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused }.semantics {
            if (locked) stateDescription = lockedLabel
        },
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(containerColor = colors.surfaceVariant,
            contentColor = colors.onSurface, focusedContainerColor = colors.primary, focusedContentColor = colors.onPrimary),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, if (dragging) colors.secondary else colors.primary), shape = shape))
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().background(colors.surface)) {
                if (!locked) AsyncImage(imageUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (!locked && rating > 0) Text(formatVodRatingLabel(rating),
                    Modifier.align(Alignment.BottomStart).background(colors.primary).padding(horizontal = 4.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall, color = colors.onPrimary)
                if (favorite && !locked) Icon(Icons.Default.Favorite, stringResource(R.string.a11y_favorite),
                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp), tint = colors.secondary)
                if (locked) Icon(Icons.Default.Lock, lockedLabel,
                    Modifier.align(Alignment.Center).size(28.dp), tint = colors.onSurface)
            }
            Box(Modifier.fillMaxWidth().height(30.dp).background(if (focused) colors.primary else colors.surfaceVariant)
                .padding(horizontal = 5.dp), contentAlignment = Alignment.CenterStart) {
                Text(title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
