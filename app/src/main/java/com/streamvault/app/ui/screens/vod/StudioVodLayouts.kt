package com.MegaStream.app.ui.screens.vod

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import coil3.compose.AsyncImage
import com.MegaStream.app.R
import com.MegaStream.app.ui.components.shell.VodCategoryOption
import com.MegaStream.app.ui.interaction.TvButton
import com.MegaStream.app.ui.interaction.TvClickableSurface
import com.MegaStream.app.ui.interaction.TvIconButton

@Composable
internal fun StudioCatalogLayout(
    categories: List<VodCategoryOption>,
    selectedCategory: String,
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
    content: LazyGridScope.() -> Unit
) {
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val compact = maxWidth < 600.dp
        val categoryWidth = if (maxWidth < 1000.dp) 176.dp else 196.dp
        Column(Modifier.fillMaxSize()) {
            if (compact) {
                LazyRow(contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(categories, key = { index, option -> "$index:${option.name}" }) { _, option ->
                        StudioCategoryButton(option, option.name == selectedCategory, Modifier.width(156.dp))
                    }
                }
            }
            Row(Modifier.weight(1f)) {
                if (!compact) {
                    LazyColumn(
                        Modifier.width(categoryWidth).fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surface),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(categories, key = { index, option -> "$index:${option.name}" }) { _, option ->
                            StudioCategoryButton(option, option.name == selectedCategory, Modifier.fillMaxWidth())
                        }
                    }
                }
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(if (compact) 128.dp else 142.dp),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
internal fun StudioCategoryButton(option: VodCategoryOption, selected: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    TvClickableSurface(
        onClick = option.onClick,
        onLongClick = option.onLongClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) colors.primary.copy(alpha = 0.14f) else colors.surface,
            contentColor = if (selected) colors.primary else colors.onSurfaceVariant,
            focusedContainerColor = colors.primary,
            focusedContentColor = colors.onPrimary
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(border = BorderStroke(2.dp, colors.secondary), shape = shape)
        )
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (option.isLocked) Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(option.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (option.count > 0) Text(option.count.toString(), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun StudioSectionHeading(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (action != null && onAction != null) TvButton(onClick = onAction) {
            Text(action, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
internal fun StudioDetailLayout(
    title: String,
    imageUrl: String?,
    metadata: List<String>,
    onBack: () -> Unit,
    actions: @Composable () -> Unit,
    content: LazyListScope.() -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val compact = maxWidth < 600.dp
        val heroHeight = if (compact) 390.dp else (maxHeight * 0.74f).coerceIn(320.dp, 440.dp)
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "studio_detail_hero") {
                Box(Modifier.fillMaxWidth().height(heroHeight)) {
                    AsyncImage(model = imageUrl, contentDescription = title, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                        MaterialTheme.colorScheme.background.copy(alpha = 0.12f),
                        MaterialTheme.colorScheme.background.copy(alpha = 0.76f),
                        MaterialTheme.colorScheme.background
                    ))))
                    TvIconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.movie_detail_back))
                    }
                    Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(if (compact) 20.dp else 32.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(title, color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(metadata.filter { it.isNotBlank() }.joinToString("  |  "),
                            color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        actions()
                    }
                }
            }
            content()
        }
    }
}
