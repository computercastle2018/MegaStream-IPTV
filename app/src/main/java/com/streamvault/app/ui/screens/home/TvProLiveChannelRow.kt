package com.MegaStream.app.ui.screens.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import coil3.compose.AsyncImage
import com.MegaStream.app.R
import com.MegaStream.app.ui.components.shell.LiveChannelRowSurface
import com.MegaStream.app.ui.interaction.TvClickableSurface
import com.MegaStream.domain.model.Channel

@Composable
internal fun CatalogLiveChannelRow(
    channel: Channel,
    onClick: () -> Unit,
    tvPro: Boolean,
    rowNumber: Int,
    sourceBadgeLabel: String? = null,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isLocked: Boolean = false,
    isReorderMode: Boolean = false,
    isDragging: Boolean = false,
    rowHeight: Dp = 44.dp
) {
    if (!tvPro) {
        LiveChannelRowSurface(channel, onClick, sourceBadgeLabel, modifier, onLongClick,
            isLocked, isReorderMode, isDragging, rowHeight)
        return
    }
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(2.dp)
    val lockedLabel = stringResource(R.string.a11y_locked)
    val description = stringResource(R.string.a11y_channel_with_number,
        channel.number.takeIf { it > 0 } ?: rowNumber, channel.name)
    TvClickableSurface(
        onClick = onClick, onLongClick = onLongClick,
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = description
            if (isLocked) stateDescription = lockedLabel
        },
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isDragging) colors.secondary else colors.surface,
            contentColor = if (isDragging) colors.onSecondary else colors.onSurface,
            focusedContainerColor = colors.primary, focusedContentColor = colors.onPrimary
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, colors.primary), shape = shape)
        )
    ) {
        Row(Modifier.fillMaxWidth().height(rowHeight).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text((channel.number.takeIf { it > 0 } ?: rowNumber).toString(),
                Modifier.width(32.dp), style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            AsyncImage(channel.logoUrl, null, contentScale = ContentScale.Fit, modifier = Modifier.size(30.dp))
            Column(Modifier.weight(1f)) {
                Text(channel.name, style = MaterialTheme.typography.labelLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                sourceBadgeLabel?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (isLocked) Icon(Icons.Default.Lock, lockedLabel, Modifier.size(14.dp))
            if (channel.isFavorite) Icon(Icons.Default.Favorite, stringResource(R.string.a11y_favorite), Modifier.size(14.dp))
        }
    }
}
