package com.MegaStream.app.ui.screens.vod

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.tv.material3.*
import coil3.compose.AsyncImage
import com.MegaStream.app.R
import com.MegaStream.app.ui.components.SearchInput
import com.MegaStream.app.ui.components.shell.VodCategoryOption
import com.MegaStream.app.ui.interaction.TvClickableSurface
import com.MegaStream.app.ui.interaction.TvIconButton
import com.MegaStream.app.ui.model.AppUiStyle
import com.MegaStream.app.ui.theme.LocalAppUiStyle

@Composable
internal fun CatalogStyleLayout(
    categories: List<VodCategoryOption>,
    selectedCategory: String,
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
    content: LazyGridScope.() -> Unit
) {
    if (LocalAppUiStyle.current != AppUiStyle.TV_PRO) {
        StudioCatalogLayout(categories, selectedCategory, gridState, modifier, content)
        return
    }
    val contentFocus = remember { FocusRequester() }
    val categoryFocus = remember { FocusRequester() }
    val towardCategories = if (LocalLayoutDirection.current == LayoutDirection.Rtl) FocusDirection.Right else FocusDirection.Left
    var categorySearch by rememberSaveable { mutableStateOf("") }
    BoxWithConstraints(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    val availableWidth = maxWidth
    Row(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.width((availableWidth * 0.25f).coerceIn(148.dp, 240.dp)).fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface).focusRequester(categoryFocus).focusRestorer().focusGroup(),
            contentPadding = PaddingValues(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            item(key = "category_search") {
                SearchInput(categorySearch, { categorySearch = it },
                    placeholder = stringResource(R.string.vod_classic_category_search))
            }
            itemsIndexed(categories.filter { categorySearch.isBlank() || it.name.contains(categorySearch.trim(), true) },
                key = { index, option -> "$index:${option.name}" }) { _, option ->
                TvProCategoryButton(option, option.name == selectedCategory,
                    Modifier.fillMaxWidth().focusProperties { end = contentFocus })
            }
        }
        LazyVerticalGrid(
            state = gridState, columns = if (availableWidth >= 800.dp) GridCells.Fixed(5) else GridCells.Adaptive(100.dp),
            modifier = Modifier.weight(1f).fillMaxHeight().focusRequester(contentFocus).focusRestorer()
                .focusProperties {
                    onExit = {
                        if (requestedFocusDirection == towardCategories) categoryFocus.requestFocus()
                    }
                }.focusGroup(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
    }
}

@Composable
internal fun TvProCategoryButton(option: VodCategoryOption, isSelected: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(2.dp)
    TvClickableSurface(
        onClick = option.onClick, onLongClick = option.onLongClick,
        modifier = modifier.semantics { selected = isSelected },
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) colors.secondary else colors.surface,
            contentColor = if (isSelected) colors.onSecondary else colors.onSurface,
            focusedContainerColor = colors.primary, focusedContentColor = colors.onPrimary
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, colors.onSurface), shape = shape)
        )
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 42.dp).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (option.isLocked) Icon(Icons.Default.Lock, null, Modifier.size(14.dp))
            Text(option.name, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (option.count > 0) Text(option.count.toString(), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun CatalogDetailLayout(
    title: String,
    imageUrl: String?,
    metadata: List<String>,
    onBack: () -> Unit,
    actions: @Composable () -> Unit,
    content: LazyListScope.() -> Unit
) {
    if (LocalAppUiStyle.current != AppUiStyle.TV_PRO) {
        StudioDetailLayout(title, imageUrl, metadata, onBack, actions, content)
        return
    }
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "tvpro_detail") {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.width(112.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.movie_detail_back))
                    }
                    AsyncImage(imageUrl, title, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                }
                Column(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(metadata.filter { it.isNotBlank() }.joinToString(" | "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    actions()
                }
            }
        }
        content()
    }
}
