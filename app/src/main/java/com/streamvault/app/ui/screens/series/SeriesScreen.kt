package com.MegaStream.app.ui.screens.series

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.text.BasicTextField
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.*
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.MegaStream.app.ui.components.SearchInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.MegaStream.app.device.rememberIsTelevisionDevice
import com.MegaStream.app.navigation.Routes
import com.MegaStream.app.ui.components.CategoryRow
import com.MegaStream.app.ui.components.ContinueWatchingRow
import com.MegaStream.app.ui.components.SavedCategoryContextCard
import com.MegaStream.app.ui.components.SavedCategoryShortcut
import com.MegaStream.app.ui.components.SavedCategoryShortcutsRow
import com.MegaStream.app.ui.components.SelectionChip
import com.MegaStream.app.ui.components.SelectionChipRow
import com.MegaStream.app.ui.components.SeriesCard
import com.MegaStream.app.ui.theme.*
import com.MegaStream.domain.model.Category
import com.MegaStream.domain.model.LibraryFilterType
import com.MegaStream.domain.model.LibrarySortBy
import com.MegaStream.domain.model.Series
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.MegaStream.app.R
import androidx.compose.foundation.border
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.MegaStream.app.ui.components.ReorderTopBar
import com.MegaStream.app.ui.components.dialogs.DeleteGroupDialog
import com.MegaStream.app.ui.components.dialogs.RenameGroupDialog
import com.MegaStream.app.ui.components.shell.BrowseHeroPanel
import com.MegaStream.app.ui.components.shell.BrowseSearchLaunchCard
import com.MegaStream.app.ui.components.shell.LoadMoreCard
import com.MegaStream.app.ui.components.shell.InfiniteScrollEffect
import com.MegaStream.app.ui.components.shell.AppNavigationChrome
import com.MegaStream.app.ui.components.shell.AppMessageState
import com.MegaStream.app.ui.components.shell.AppScreenScaffold
import com.MegaStream.app.ui.components.shell.VodActionChip
import com.MegaStream.app.ui.components.shell.VodActionChipRow
import com.MegaStream.app.ui.components.shell.VodCategoryOption
import com.MegaStream.app.ui.components.shell.VodCategoryPickerDialog
import com.MegaStream.app.ui.components.shell.VodBrowseOptionsDialog
import com.MegaStream.app.ui.components.shell.VodClassicCategoryOption
import com.MegaStream.app.ui.components.shell.VodClassicContentHeader
import com.MegaStream.app.ui.components.shell.VodClassicSplitLayout
import com.MegaStream.app.ui.components.shell.VodHeroStrip
import com.MegaStream.app.ui.components.shell.StudioCatalogHero
import com.MegaStream.app.ui.screens.vod.StudioCatalogLayout
import com.MegaStream.app.ui.screens.vod.StudioSectionHeading
import com.MegaStream.app.ui.screens.vod.StudioContentAccess
import com.MegaStream.app.ui.screens.vod.studioContentAccess
import com.MegaStream.app.ui.model.AppUiStyle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import com.MegaStream.app.ui.interaction.TvIconButton
import com.MegaStream.app.ui.components.shell.VodSectionHeader
import com.MegaStream.app.ui.design.FocusRestoreHost
import com.MegaStream.app.ui.design.requestFocusSafely
import com.MegaStream.app.ui.model.VodViewMode
import com.MegaStream.app.ui.screens.vod.HandleVodUserMessage
import com.MegaStream.app.ui.screens.vod.ProtectedVodPinDialog
import com.MegaStream.app.ui.screens.vod.VodBrowseDefaults
import com.MegaStream.app.ui.screens.vod.vodActiveFilterSortDetail
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SeriesScreen(
    onSeriesClick: (Long) -> Unit,
    onNavigate: (String) -> Unit,
    currentRoute: String,
    viewModel: SeriesViewModel = hiltViewModel()
) {
    remember(viewModel) {
        viewModel.resetPreviewRowsForScreenEntry()
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val studio = LocalAppUiStyle.current == AppUiStyle.STUDIO
    LaunchedEffect(studio, viewModel) { viewModel.enterStudioCatalog(studio) }
    val snackbarHostState = remember { SnackbarHostState() }
    val initialContentFocusRequester = remember { FocusRequester() }
    var showPinDialog by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var pendingSeriesId by remember { mutableStateOf<Long?>(null) }
    var pendingCategory by remember { mutableStateOf<Category?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    HandleVodUserMessage(
        userMessage = uiState.userMessage,
        snackbarHostState = snackbarHostState,
        onShown = viewModel::userMessageShown
    )

    BackHandler(enabled = uiState.selectedCategory != null && !uiState.isReorderMode) {
        viewModel.selectCategory(null)
    }

    ProtectedVodPinDialog(
        visible = showPinDialog,
        error = pinError,
        incorrectPinMessage = context.getString(R.string.series_incorrect_pin),
        onDismissRequest = {
            showPinDialog = false
            pinError = null
            pendingSeriesId = null
            pendingCategory = null
        },
        onVerified = {
            showPinDialog = false
            pinError = null
            pendingSeriesId?.let(onSeriesClick)
            pendingCategory?.let(viewModel::unlockCategory)
            pendingSeriesId = null
            pendingCategory = null
        },
        onErrorChange = { pinError = it },
        verifyPin = viewModel::verifyPin
    )

    Box(modifier = Modifier.fillMaxSize()) {
        FocusRestoreHost(
            enabled = !uiState.isLoading && uiState.errorMessage == null,
            onRestore = {
                delay(100)
                initialContentFocusRequester.requestFocusSafely(tag = "SeriesScreen", target = "Initial series content")
            }
        ) {
        AppScreenScaffold(
            currentRoute = currentRoute,
            onNavigate = onNavigate,
            title = stringResource(R.string.nav_series),
            subtitle = null,
            navigationChrome = AppNavigationChrome.TopBar,
            compactHeader = true,
            showScreenHeader = false,
            topBarActions = {
                if (studio) TvIconButton(onClick = viewModel::refreshStudioCatalog, enabled = !uiState.isCatalogRefreshing) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.studio_refresh_catalog))
                }
            }
        ) {
        if (studio && uiState.isCatalogRefreshing) {
            androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
        }
        if (studio && uiState.catalogRefreshError != null) {
            Text(uiState.catalogRefreshError.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
        }
        if (uiState.isReorderMode && uiState.reorderCategory != null) {
            ReorderTopBar(
                categoryName = uiState.reorderCategory!!.name,
                onSave = { viewModel.saveReorder() },
                onCancel = { viewModel.exitCategoryReorderMode() },
                subtitle = stringResource(R.string.series_reorder_subtitle)
            )
        }

        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Text(
                        text = stringResource(R.string.series_loading),
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
        } else if (uiState.errorMessage != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AppMessageState(
                    title = stringResource(R.string.home_error_load_failed),
                    subtitle = uiState.errorMessage ?: ""
                )
            }
        } else if (!uiState.hasProviders) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AppMessageState(
                    title = stringResource(R.string.home_add_first_provider),
                    subtitle = stringResource(R.string.home_add_first_provider_subtitle)
                )
            }
        } else if (!uiState.hasActiveProvider || (uiState.selectedCategory == null && uiState.seriesByCategory.isEmpty() && uiState.libraryCount == 0 && uiState.searchQuery.isBlank() && !uiState.isLoadingPreviewRows)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AppMessageState(
                    title = stringResource(R.string.vod_sync_needed_title),
                    subtitle = stringResource(R.string.vod_sync_needed_subtitle)
                )
            }
        } else if (uiState.selectedCategory == null && uiState.searchQuery.isBlank() && uiState.seriesByCategory.isEmpty() && !uiState.isLoadingPreviewRows) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AppMessageState(
                    title = stringResource(R.string.series_no_found),
                    subtitle = stringResource(R.string.series_no_found_subtitle)
                )
            }
        } else {
            SeriesVodContent(
                uiState = uiState,
                selectedFilterType = uiState.selectedLibraryFilterType,
                onSelectedFilterTypeChange = viewModel::setSelectedLibraryFilterType,
                selectedSortBy = uiState.selectedLibrarySortBy,
                onSelectedSortByChange = viewModel::setSelectedLibrarySortBy,
                searchQuery = uiState.searchQuery,
                onSearchQueryChange = viewModel::setSearchQuery,
                onSeriesClick = onSeriesClick,
                onProtectedSeriesClick = { seriesId ->
                    pendingCategory = null
                    pendingSeriesId = seriesId
                    showPinDialog = true
                },
                onProtectedCategoryClick = { category ->
                    pendingSeriesId = null
                    pendingCategory = category
                    showPinDialog = true
                },
                onShowDialog = viewModel::onShowDialog,
                onShowCategoryOptions = viewModel::showCategoryOptions,
                onSelectCategory = viewModel::selectCategory,
                onSelectFullLibraryBrowse = viewModel::selectFullLibraryBrowse,
                onOpenContinueWatching = {
                    viewModel.setSelectedLibraryFilterType(LibraryFilterType.IN_PROGRESS)
                    viewModel.setSelectedLibrarySortBy(LibrarySortBy.LIBRARY)
                    viewModel.selectFullLibraryBrowse()
                },
                onOpenTopRated = {
                    viewModel.setSelectedLibraryFilterType(LibraryFilterType.TOP_RATED)
                    viewModel.setSelectedLibrarySortBy(LibrarySortBy.RATING)
                    viewModel.selectFullLibraryBrowse()
                },
                onOpenFresh = {
                    viewModel.setSelectedLibraryFilterType(LibraryFilterType.RECENTLY_UPDATED)
                    viewModel.setSelectedLibrarySortBy(LibrarySortBy.UPDATED)
                    viewModel.selectFullLibraryBrowse()
                },
                onLoadMore = viewModel::loadMoreSelectedCategory,
                onLoadMorePreviewRows = viewModel::loadMorePreviewRows,
                onDismissReorder = viewModel::exitCategoryReorderMode,
                initialFocusRequester = initialContentFocusRequester
            )
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

    if (uiState.showDialog && uiState.selectedSeriesForDialog != null) {
        val series = uiState.selectedSeriesForDialog!!
        com.MegaStream.app.ui.components.dialogs.AddToGroupDialog(
            contentTitle = series.name,
            groups = uiState.categories.filter { it.isVirtual && it.id != VodBrowseDefaults.FAVORITES_SENTINEL_ID },
            isFavorite = series.isFavorite,
            memberOfGroups = uiState.dialogGroupMemberships,
            onDismiss = { viewModel.onDismissDialog() },
            onToggleFavorite = {
                if (series.isFavorite) viewModel.removeFavorite(series) else viewModel.addFavorite(series)
            },
            onAddToGroup = { group -> viewModel.addToGroup(series, group) },
            onRemoveFromGroup = { group -> viewModel.removeFromGroup(series, group) },
            onCreateGroup = { name -> viewModel.createCustomGroup(name) }
        )
    }

    if (uiState.showDeleteGroupDialog && uiState.groupToDelete != null) {
        DeleteGroupDialog(
            groupName = uiState.groupToDelete!!.name,
            onDismissRequest = { viewModel.cancelDeleteGroup() },
            onConfirmDelete = { viewModel.confirmDeleteGroup() }
        )
    }

    if (uiState.selectedCategoryForOptions != null) {
        val category = uiState.selectedCategoryForOptions!!
        com.MegaStream.app.ui.components.dialogs.CategoryOptionsDialog(
            category = category,
            onDismissRequest = { viewModel.dismissCategoryOptions() },
            onHide = if (!category.isVirtual) {
                { viewModel.hideCategory(category) }
            } else null,
            onRename = if (category.isVirtual && category.id != VodBrowseDefaults.FAVORITES_SENTINEL_ID) {
                { viewModel.requestRenameGroup(category) }
            } else null,
            onDelete = if (category.isVirtual && category.id != VodBrowseDefaults.FAVORITES_SENTINEL_ID) {
                { viewModel.requestDeleteGroup(category) }
            } else null,
            onReorderChannels = if (category.isVirtual) {
                { viewModel.enterCategoryReorderMode(category) }
            } else null
        )
    }

    if (uiState.showRenameGroupDialog && uiState.groupToRename != null) {
        RenameGroupDialog(
            initialName = uiState.groupToRename!!.name,
            errorMessage = uiState.renameGroupError,
            onDismissRequest = { viewModel.cancelRenameGroup() },
            onConfirm = { name -> viewModel.confirmRenameGroup(name) }
        )
    }
}

@Composable
private fun SeriesVodContent(
    uiState: SeriesUiState,
    selectedFilterType: LibraryFilterType,
    onSelectedFilterTypeChange: (LibraryFilterType) -> Unit,
    selectedSortBy: LibrarySortBy,
    onSelectedSortByChange: (LibrarySortBy) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSeriesClick: (Long) -> Unit,
    onProtectedSeriesClick: (Long) -> Unit,
    onProtectedCategoryClick: (Category) -> Unit,
    onShowDialog: (Series) -> Unit,
    onShowCategoryOptions: (String) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onSelectFullLibraryBrowse: () -> Unit,
    onOpenContinueWatching: () -> Unit,
    onOpenTopRated: () -> Unit,
    onOpenFresh: () -> Unit,
    onLoadMore: () -> Unit,
    onLoadMorePreviewRows: () -> Unit,
    onDismissReorder: () -> Unit,
    initialFocusRequester: FocusRequester
) {
    val studio = LocalAppUiStyle.current == AppUiStyle.STUDIO
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val isTelevisionDevice = rememberIsTelevisionDevice()
    val favoriteCardWidth = when {
        screenWidth < 700.dp -> 136.dp
        !isTelevisionDevice && screenWidth < 900.dp -> 148.dp
        !isTelevisionDevice && screenWidth < 1280.dp -> 152.dp
        else -> 160.dp
    }
    val loadingSectionHeight = when {
        screenWidth < 700.dp -> 220.dp
        !isTelevisionDevice && screenWidth < 1280.dp -> 260.dp
        else -> 300.dp
    }
    var showCategoryPicker by remember { mutableStateOf(false) }
    val favoriteSeries = uiState.seriesByCategory[uiState.favoriteCategoryName].orEmpty()
    val freshSeries = if (studio) uiState.newestAddedItems else uiState.libraryLensRows[SeriesLibraryLens.FRESH].orEmpty()
    val topRatedSeries = uiState.libraryLensRows[SeriesLibraryLens.TOP_RATED].orEmpty()
    val continueWatching = uiState.continueWatching
    val heroSeries = freshSeries.firstOrNull() ?: topRatedSeries.firstOrNull() ?: favoriteSeries.firstOrNull()
    val categoryByName = remember(uiState.providerCategories, uiState.categories, uiState.favoriteCategoryName) {
        buildMap<String, Category> {
            uiState.providerCategories.forEach { put(it.name, it) }
            uiState.categories.forEach { put(it.name, it) }
            put(
                uiState.favoriteCategoryName,
                Category(
                    id = VodBrowseDefaults.FAVORITES_SENTINEL_ID,
                    name = uiState.favoriteCategoryName,
                    type = com.MegaStream.domain.model.ContentType.SERIES,
                    isVirtual = true
                )
            )
        }
    }
    val isCategoryLocked = remember(uiState.parentalControlLevel, uiState.unlockedCategoryIds, categoryByName) {
        { category: Category ->
            (category.isAdult || category.isUserProtected) &&
                uiState.parentalControlLevel in 1..2 &&
                kotlin.math.abs(category.id) !in uiState.unlockedCategoryIds
        }
    }
    val isCategoryHidden = remember(uiState.parentalControlLevel, uiState.unlockedCategoryIds, categoryByName) {
        { category: Category ->
            (category.isAdult || category.isUserProtected) &&
                uiState.parentalControlLevel >= 3 &&
                kotlin.math.abs(category.id) !in uiState.unlockedCategoryIds
        }
    }
    val isSeriesLocked = remember(uiState.parentalControlLevel, uiState.unlockedCategoryIds) {
        { series: Series ->
            val categoryId = series.categoryId
            (series.isAdult || series.isUserProtected) &&
                uiState.parentalControlLevel in 1..2 &&
                (categoryId == null || kotlin.math.abs(categoryId) !in uiState.unlockedCategoryIds)
        }
    }
    val openProtectedCategory: (Category) -> Unit = onProtectedCategoryClick
    val browseCategoryNames = remember(uiState.categoryNames, uiState.providerCategories, uiState.categories, uiState.favoriteCategoryName) {
        buildList {
            val names = linkedSetOf<String>()
            uiState.categoryNames.forEach(names::add)
            uiState.providerCategories.forEach { names.add(it.name) }
            uiState.categories.forEach { names.add(it.name) }
            names.add(uiState.favoriteCategoryName)
            addAll(names)
        }
    }
    val visibleCategoryNames = remember(browseCategoryNames, categoryByName, uiState.parentalControlLevel, uiState.unlockedCategoryIds) {
        browseCategoryNames.filter { name ->
            val category = categoryByName[name]
            category == null || !isCategoryHidden(category)
        }
    }
    val visibleCategoryNameSet = remember(visibleCategoryNames) {
        visibleCategoryNames.toSet()
    }
    val catEntries = remember(uiState.seriesByCategory, visibleCategoryNameSet, uiState.favoriteCategoryName) {
        uiState.seriesByCategory.entries
            .filter { (name, items) ->
                name != uiState.favoriteCategoryName && name in visibleCategoryNameSet && items.isNotEmpty()
            }
            .toList()
    }
    val fallbackSeriesId = if (heroSeries == null) {
        favoriteSeries.firstOrNull()?.id
            ?: freshSeries.firstOrNull()?.id
            ?: topRatedSeries.firstOrNull()?.id
            ?: catEntries.firstOrNull()?.value?.firstOrNull()?.id
    } else null
    val categoryOptions = remember(visibleCategoryNames, uiState.categoryCounts, categoryByName, uiState.parentalControlLevel, uiState.unlockedCategoryIds) {
        visibleCategoryNames.map { name ->
            val matchedCategory = categoryByName[name]
            val locked = matchedCategory?.let(isCategoryLocked) == true
            VodCategoryOption(
                name = name,
                count = uiState.categoryCounts[name] ?: 0,
                onClick = {
                    if (locked && matchedCategory != null) openProtectedCategory(matchedCategory) else onSelectCategory(name)
                },
                onLongClick = matchedCategory?.takeIf { !locked }?.let { category ->
                    { onShowCategoryOptions(category.name) }
                },
                isLocked = locked
            )
        }
    }

    if (showCategoryPicker) {
        VodCategoryPickerDialog(
            title = stringResource(R.string.vod_category_picker_title),
            subtitle = stringResource(R.string.vod_category_picker_subtitle),
            categories = categoryOptions,
            onDismiss = { showCategoryPicker = false }
        )
    }


    if (studio) {
        val categoriesById = remember(uiState.providerCategories, uiState.categories) {
            (uiState.providerCategories + uiState.categories).associateBy { kotlin.math.abs(it.id) }
        }
        fun access(series: Series): StudioContentAccess {
            val category = series.categoryId?.let { categoriesById[kotlin.math.abs(it)] }
            return studioContentAccess(
                series = series,
                category = category,
                parentalLevel = uiState.parentalControlLevel,
                unlockedCategoryIds = uiState.unlockedCategoryIds
            )
        }
        val openContent: (Series) -> Unit = { series ->
            when (access(series)) {
                StudioContentAccess.VISIBLE -> onSeriesClick(series.id)
                StudioContentAccess.LOCKED -> onProtectedSeriesClick(series.id)
                StudioContentAccess.HIDDEN -> Unit
            }
        }
        val openOptions: (Series) -> Unit = { series ->
            if (access(series) == StudioContentAccess.VISIBLE) onShowDialog(series) else openContent(series)
        }
        val overview = uiState.selectedCategory == null
        val catalogItems = (if (uiState.isReorderMode) uiState.filteredSeries
            else if (overview) catEntries.flatMap { it.value }.distinctBy { it.id }
            else uiState.selectedCategoryItems).filter { access(it) != StudioContentAccess.HIDDEN }
        val latestItems = freshSeries.filter { access(it) == StudioContentAccess.VISIBLE }
        val featured = if (overview && searchQuery.isBlank()) latestItems.firstOrNull()
            ?: catalogItems.firstOrNull { access(it) == StudioContentAccess.VISIBLE } else null
        val overviewLabel = stringResource(R.string.studio_catalog_discover)
        val allLabel = stringResource(R.string.library_full_browse_title_series)
        val freshLabel = stringResource(R.string.studio_latest_added)
        val continueLabel = stringResource(R.string.library_lens_continue)
        val topLabel = stringResource(R.string.library_lens_top_rated)
        val openLatest = {
            onSelectFullLibraryBrowse()
            onSelectedFilterTypeChange(LibraryFilterType.RECENTLY_UPDATED)
            onSelectedSortByChange(LibrarySortBy.UPDATED)
        }
        val railOptions = listOf(
            VodCategoryOption(overviewLabel, 0, { onSelectCategory(null) }),
            VodCategoryOption(allLabel, uiState.libraryCount, onSelectFullLibraryBrowse),
            VodCategoryOption(freshLabel, 0, openLatest),
            VodCategoryOption(continueLabel, 0, onOpenContinueWatching),
            VodCategoryOption(topLabel, 0, onOpenTopRated)
        ) + categoryOptions
        var optionsVisible by rememberSaveable { mutableStateOf(false) }
        var searchVisible by rememberSaveable { mutableStateOf(searchQuery.isNotBlank()) }
        val searchFocus = remember { FocusRequester() }
        var dragging by remember { mutableStateOf<Series?>(null) }
        val catalogState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
        LaunchedEffect(uiState.selectedCategory, searchQuery, selectedFilterType, selectedSortBy) {
            catalogState.scrollToItem(0)
        }
        LaunchedEffect(searchVisible) {
            if (searchVisible) {
                catalogState.scrollToItem(if (featured == null) 0 else 1)
                withFrameNanos { }
                searchFocus.requestFocusSafely(tag = "StudioCatalog", target = "Search")
            }
        }
        val loading = if (overview) uiState.isLoadingPreviewRows else uiState.isLoadingSelectedCategory
        val canLoadMore = if (overview) uiState.hasMorePreviewRows else uiState.canLoadMoreSelectedCategory
        val loadMore = if (overview) onLoadMorePreviewRows else onLoadMore
        InfiniteScrollEffect(
            gridState = catalogState, enabled = uiState.vodInfiniteScroll && !uiState.isReorderMode,
            canLoadMore = canLoadMore, isLoading = loading, onLoadMore = loadMore
        )
        if (optionsVisible) VodBrowseOptionsDialog(
            title = stringResource(R.string.nav_series),
            filterTitle = stringResource(R.string.library_filter_title), filterChips = seriesFilterChips(),
            selectedFilterKey = selectedFilterType.name,
            onFilterSelected = { key ->
                LibraryFilterType.entries.firstOrNull { it.name == key }?.let {
                    if (overview) onSelectFullLibraryBrowse()
                    onSelectedFilterTypeChange(it)
                }
            },
            sortTitle = stringResource(R.string.library_sort_title), sortChips = seriesSortChips(),
            selectedSortKey = selectedSortBy.name,
            onSortSelected = { key ->
                LibrarySortBy.entries.firstOrNull { it.name == key }?.let {
                    if (overview) onSelectFullLibraryBrowse()
                    onSelectedSortByChange(it)
                }
            }, onDismiss = { optionsVisible = false }
        )
        StudioCatalogLayout(
            categories = railOptions,
            selectedCategory = if (overview) overviewLabel else when (uiState.selectedCategory) {
                uiState.fullLibraryCategoryName -> when (selectedFilterType) {
                    LibraryFilterType.RECENTLY_UPDATED -> freshLabel
                    LibraryFilterType.IN_PROGRESS -> continueLabel
                    LibraryFilterType.TOP_RATED -> topLabel
                    else -> allLabel
                }
                else -> uiState.selectedCategory.orEmpty()
            },
            gridState = catalogState,
            modifier = Modifier.onPreviewKeyEvent { event ->
                if (uiState.isReorderMode && event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN &&
                    event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    dragging = null
                    onDismissReorder()
                    true
                } else false
            }
        ) {
            if (featured != null && !uiState.isReorderMode) item(key = "studio_hero", span = { GridItemSpan(maxLineSpan) }) {
                StudioCatalogHero(
                    title = featured.name, imageUrl = featured.backdropUrl ?: featured.posterUrl,
                    metadata = featured.plot ?: featured.genre,
                    onClick = { openContent(featured) },
                    modifier = Modifier.focusRequester(initialFocusRequester)
                )
            }
            item(key = "studio_tools", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StudioSectionHeading(if (overview) allLabel else
                        uiState.selectedCategory?.takeUnless { it == uiState.fullLibraryCategoryName } ?: allLabel)
                    if (!uiState.isReorderMode) VodActionChipRow(
                        actions = listOf(
                            VodActionChip("search", stringResource(R.string.search_title), onClick = { searchVisible = !searchVisible }),
                            VodActionChip("options", stringResource(R.string.library_action_filters_sort),
                                detail = vodActiveFilterSortDetail(selectedFilterType, selectedSortBy),
                                onClick = { optionsVisible = true })
                        )
                    )
                    if (searchVisible && !uiState.isReorderMode) {
                        SearchInput(
                            value = searchQuery,
                            onValueChange = { query ->
                                if (overview) onSelectFullLibraryBrowse()
                                onSearchQueryChange(query)
                            },
                            placeholder = stringResource(R.string.series_search_placeholder),
                            onSearch = {},
                            focusRequester = searchFocus
                        )
                    }
                }
            }
            if (overview && searchQuery.isBlank() && latestItems.isNotEmpty() && !uiState.isReorderMode) {
                item(key = "studio_latest", span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        StudioSectionHeading(freshLabel, allLabel, openLatest)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(vertical = 4.dp)) {
                            items(latestItems, key = { it.id }) { series ->
                                SeriesCard(series = series, width = 112.dp, height = 168.dp,
                                    onClick = { openContent(series) }, onLongClick = { openOptions(series) })
                            }
                        }
                    }
                }
            }
            gridItems(catalogItems, key = { it.id }) { series ->
                val locked = access(series) == StudioContentAccess.LOCKED
                SeriesCard(
                    series = series, isLocked = locked,
                    isReorderMode = uiState.isReorderMode, isDragging = dragging?.id == series.id,
                    modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).then(
                        if (featured == null && series.id == catalogItems.firstOrNull()?.id)
                            Modifier.focusRequester(initialFocusRequester) else Modifier
                    ),
                    onClick = {
                        if (uiState.isReorderMode) dragging = if (dragging?.id == series.id) null else series
                        else openContent(series)
                    },
                    onLongClick = { if (!uiState.isReorderMode) openOptions(series) }
                )
            }
            if (loading) item(key = "studio_loading", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
            if (!loading && catalogItems.isEmpty()) item(key = "studio_empty", span = { GridItemSpan(maxLineSpan) }) {
                AppMessageState(title = stringResource(R.string.series_no_found),
                    subtitle = stringResource(R.string.series_no_found_subtitle))
            }
            if (canLoadMore && !loading && !uiState.isReorderMode) item(key = "studio_more", span = { GridItemSpan(maxLineSpan) }) {
                LoadMoreCard(label = stringResource(R.string.library_load_more,
                    if (overview) catalogItems.size else uiState.selectedCategoryLoadedCount,
                    if (overview) uiState.libraryCount else uiState.selectedCategoryTotalCount), onClick = loadMore)
            }
        }
        return
    }


    if (uiState.vodViewMode == VodViewMode.CLASSIC && !studio) {
        SeriesVodClassicContent(
            uiState = uiState,
            selectedFilterType = selectedFilterType,
            onSelectedFilterTypeChange = onSelectedFilterTypeChange,
            selectedSortBy = selectedSortBy,
            onSelectedSortByChange = onSelectedSortByChange,
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange,
            onSeriesClick = onSeriesClick,
            onProtectedSeriesClick = onProtectedSeriesClick,
            onProtectedCategoryClick = onProtectedCategoryClick,
            onShowDialog = onShowDialog,
            onShowCategoryOptions = onShowCategoryOptions,
            onSelectCategory = onSelectCategory,
            onSelectFullLibraryBrowse = onSelectFullLibraryBrowse,
            onOpenContinueWatching = onOpenContinueWatching,
            onOpenFresh = onOpenFresh,
            onLoadMore = onLoadMore,
            onDismissReorder = onDismissReorder,
            initialFocusRequester = initialFocusRequester
        )
        return
    }

    if (uiState.selectedCategory == null) {
        val freshRow: @Composable () -> Unit = {
            CategoryRow(
                title = stringResource(if (studio) R.string.studio_latest_updated_series else R.string.library_lens_fresh_series),
                items = freshSeries, onSeeAll = null, keySelector = { it.id }
            ) { series ->
                val locked = isSeriesLocked(series)
                SeriesCard(series = series, isLocked = locked,
                    onClick = { if (locked) onProtectedSeriesClick(series.id) else onSeriesClick(series.id) },
                    onLongClick = { onShowDialog(series) })
            }
        }
        val previewListState = androidx.compose.foundation.lazy.rememberLazyListState()
        InfiniteScrollEffect(
            listState = previewListState,
            enabled = uiState.vodInfiniteScroll,
            canLoadMore = uiState.hasMorePreviewRows,
            isLoading = uiState.isLoadingPreviewRows,
            onLoadMore = onLoadMorePreviewRows
        )
        LazyColumn(
            state = previewListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 28.dp)
        ) {
            item(key = "hero") {
            if (heroSeries != null) {
                if (studio) {
                    StudioCatalogHero(
                        title = heroSeries.name,
                        eyebrow = stringResource(if (heroSeries.lastModified > 0) R.string.studio_latest_updated_series else R.string.nav_series),
                        imageUrl = if (isSeriesLocked(heroSeries)) null else heroSeries.backdropUrl ?: heroSeries.posterUrl,
                        metadata = heroSeries.plot ?: heroSeries.genre,
                        onClick = { if (isSeriesLocked(heroSeries)) onProtectedSeriesClick(heroSeries.id) else onSeriesClick(heroSeries.id) },
                        modifier = Modifier.focusRequester(initialFocusRequester)
                    )
                } else {
                VodHeroStrip(
                        title = heroSeries.name,
                        subtitle = heroSeries.plot?.takeIf { it.isNotBlank() }
                            ?: heroSeries.genre
                            ?: stringResource(R.string.series_library_lens_subtitle),
                        actionLabel = stringResource(R.string.player_resume).substringBefore(" "),
                        onClick = {
                            val isLocked = isSeriesLocked(heroSeries)
                            if (isLocked) onProtectedSeriesClick(heroSeries.id) else onSeriesClick(heroSeries.id)
                        },
                        modifier = Modifier
                            .padding(top = 8.dp, bottom = 6.dp)
                            .focusRequester(initialFocusRequester)
                    )
                }
            }
            }
            if (studio && freshSeries.isNotEmpty()) {
                item(key = "fresh_row") { freshRow() }
            }
            item(key = "actions") {
            VodActionChipRow(
                    actions = buildList {
                        add(
                            VodActionChip(
                                key = "browse_all",
                                label = stringResource(R.string.library_full_browse_title_series),
                                detail = stringResource(R.string.library_full_browse_subtitle, uiState.libraryCount),
                                onClick = onSelectFullLibraryBrowse
                            )
                        )
                        add(
                            VodActionChip(
                                key = "categories",
                                label = stringResource(R.string.series_categories_title),
                                detail = "${visibleCategoryNames.count { name -> categoryByName[name]?.id != VodBrowseDefaults.FAVORITES_SENTINEL_ID }} groups",
                                onClick = { showCategoryPicker = true }
                            )
                        )
                        if (favoriteSeries.isNotEmpty()) {
                            add(
                                VodActionChip(
                                    key = "favorites",
                                    label = stringResource(R.string.favorites_title),
                                    detail = stringResource(R.string.library_saved_items_count, favoriteSeries.size),
                                    onClick = { onSelectCategory(uiState.favoriteCategoryName) }
                                )
                            )
                        }
                        if (continueWatching.isNotEmpty()) {
                            add(
                                VodActionChip(
                                    key = "resume",
                                    label = stringResource(R.string.library_lens_continue),
                                    detail = "${continueWatching.size} items",
                                    onClick = onOpenContinueWatching
                                )
                            )
                        }
                        if (topRatedSeries.isNotEmpty()) {
                            add(
                                VodActionChip(
                                    key = SeriesLibraryLens.TOP_RATED.name,
                                    label = stringResource(R.string.library_lens_top_rated),
                                    detail = "${topRatedSeries.size} picks",
                                    onClick = onOpenTopRated
                                )
                            )
                        }
                        if (freshSeries.isNotEmpty()) {
                            add(
                                VodActionChip(
                                    key = SeriesLibraryLens.FRESH.name,
                                    label = stringResource(R.string.library_lens_fresh_series),
                                    detail = "${freshSeries.size} picks",
                                    onClick = onOpenFresh
                                )
                            )
                        }
                    },
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                )
            }
            if (continueWatching.isNotEmpty()) {
            item(key = "continue_watching") {
                ContinueWatchingRow(
                        items = continueWatching,
                        onItemClick = { history -> onSeriesClick(history.seriesId ?: history.contentId) }
                    )
            }
            }
            if (favoriteSeries.isNotEmpty()) {
            item(key = "favorites_row") {
                CategoryRow(
                        title = stringResource(R.string.favorites_title),
                        items = favoriteSeries,
                        onSeeAll = { onSelectCategory(uiState.favoriteCategoryName) },
                        keySelector = { it.id }
                    ) { series ->
                        val isLocked = isSeriesLocked(series)
                        SeriesCard(
                            series = series,
                            isLocked = isLocked,
                            onClick = { if (isLocked) onProtectedSeriesClick(series.id) else onSeriesClick(series.id) },
                            onLongClick = { onShowDialog(series) },
                            modifier = Modifier.width(favoriteCardWidth)
                        )
                }
            }
            }
            if (!studio && freshSeries.isNotEmpty()) {
            item(key = "fresh_row") {
                freshRow()
            }
            }
            if (topRatedSeries.isNotEmpty()) {
            item(key = "top_rated_row") {
                CategoryRow(
                        title = stringResource(R.string.library_lens_top_rated),
                        items = topRatedSeries,
                        onSeeAll = null,
                        keySelector = { it.id }
                    ) { series ->
                        val isLocked = isSeriesLocked(series)
                        SeriesCard(
                            series = series,
                            isLocked = isLocked,
                            onClick = { if (isLocked) onProtectedSeriesClick(series.id) else onSeriesClick(series.id) },
                            onLongClick = { onShowDialog(series) }
                        )
                }
            }
            }
            items(catEntries, key = { it.key }) { entry ->
                val categoryName = entry.key
                val seriesList = entry.value
                val matchedCategory = categoryByName[categoryName]
                val lockedCategory = matchedCategory?.let(isCategoryLocked) == true
                CategoryRow(
                    title = categoryName,
                    items = seriesList,
                    onSeeAll = {
                        if (lockedCategory && matchedCategory != null) openProtectedCategory(matchedCategory) else onSelectCategory(categoryName)
                    },
                    keySelector = { it.id }
                ) { series ->
                    val isLocked = isSeriesLocked(series)
                    SeriesCard(
                        series = series,
                        isLocked = isLocked,
                        onClick = { if (isLocked) onProtectedSeriesClick(series.id) else onSeriesClick(series.id) },
                        onLongClick = { onShowDialog(series) },
                        modifier = if (series.id == fallbackSeriesId) Modifier.focusRequester(initialFocusRequester) else Modifier
                    )
                }
            }
            if (uiState.isLoadingPreviewRows) {
                item(key = "preview_loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
                    }
                }
            }
            if (uiState.hasMorePreviewRows && !uiState.isLoadingPreviewRows && !uiState.vodInfiniteScroll && catEntries.isNotEmpty()) {
                item(key = "load_more_preview_trigger") {
                    LoadMoreCard(
                        label = stringResource(
                            R.string.library_load_more,
                            catEntries.size,
                            visibleCategoryNames.size
                        ),
                        onClick = onLoadMorePreviewRows,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }
            }
        }
        return
    }

    val baseSeries = uiState.selectedCategoryItems
    val filteredGridSeries = remember(baseSeries, uiState.isReorderMode, uiState.filteredSeries) {
        if (uiState.isReorderMode) uiState.filteredSeries else baseSeries
    }
    var draggingSeries by remember { mutableStateOf<Series?>(null) }
    var showBrowseOptions by rememberSaveable(uiState.selectedCategory) { mutableStateOf(false) }
    var showSearchBar by rememberSaveable(uiState.selectedCategory) { mutableStateOf(searchQuery.isNotBlank()) }
    val initialGridSeriesId = filteredGridSeries.firstOrNull()?.id

    if (showBrowseOptions) {
        VodBrowseOptionsDialog(
            title = stringResource(R.string.nav_series),
            filterTitle = stringResource(R.string.library_filter_title),
            filterChips = seriesFilterChips(),
            selectedFilterKey = selectedFilterType.name,
            onFilterSelected = { key ->
                LibraryFilterType.entries.firstOrNull { it.name == key }?.let(onSelectedFilterTypeChange)
            },
            sortTitle = stringResource(R.string.library_sort_title),
            sortChips = seriesSortChips(),
            selectedSortKey = selectedSortBy.name,
            onSortSelected = { key ->
                LibrarySortBy.entries.firstOrNull { it.name == key }?.let(onSelectedSortByChange)
            },
            onDismiss = { showBrowseOptions = false }
        )
    }

    val modernGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    InfiniteScrollEffect(
        gridState = modernGridState,
        enabled = !uiState.isReorderMode,
        canLoadMore = uiState.canLoadMoreSelectedCategory,
        isLoading = uiState.isLoadingSelectedCategory,
        onLoadMore = onLoadMore
    )
    LazyVerticalGrid(
        state = modernGridState,
        columns = GridCells.Adaptive(minSize = if (studio) 156.dp else 136.dp),
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (uiState.isReorderMode && event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                    if (event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                        draggingSeries = null
                        onDismissReorder()
                        true
                    } else false
                } else false
            },
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            VodSectionHeader(
                title = when (uiState.selectedCategory) {
                    uiState.fullLibraryCategoryName -> stringResource(R.string.library_full_browse_title_series)
                    else -> uiState.selectedCategory ?: stringResource(R.string.nav_series)
                }
            )
        }

        if (!uiState.isReorderMode) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                val browseOptionsDetail = vodActiveFilterSortDetail(selectedFilterType, selectedSortBy)
                val hasActiveFilterSort = selectedFilterType != LibraryFilterType.ALL || selectedSortBy != LibrarySortBy.LIBRARY
                VodActionChipRow(
                    actions = buildList {
                        add(
                            VodActionChip(
                                key = "back_home",
                                label = stringResource(R.string.nav_series),
                                onClick = { onSelectCategory(null) }
                            )
                        )
                        add(
                            VodActionChip(
                                key = "categories",
                                label = stringResource(R.string.series_categories_title),
                                onClick = { showCategoryPicker = true }
                            )
                        )
                        add(
                            VodActionChip(
                                key = "search_toggle",
                                label = stringResource(
                                    if (showSearchBar) R.string.library_action_hide_search else R.string.search_title
                                ),
                                onClick = { showSearchBar = !showSearchBar }
                            )
                        )
                        add(
                            VodActionChip(
                                key = "browse_options",
                                label = stringResource(R.string.library_action_filters_sort),
                                detail = browseOptionsDetail,
                                onClick = { showBrowseOptions = true }
                            )
                        )
                        if (uiState.selectedCategory != uiState.fullLibraryCategoryName) {
                            add(
                                VodActionChip(
                                    key = uiState.fullLibraryCategoryName,
                                    label = stringResource(R.string.library_full_browse_title_series),
                                    onClick = onSelectFullLibraryBrowse
                                )
                            )
                        }
                    },
                    selectedKey = if (hasActiveFilterSort) "browse_options" else null,
                    modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
                )
            }

            if (showSearchBar) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SearchInput(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        placeholder = stringResource(R.string.series_search_placeholder),
                        onSearch = {},
                        focusRequester = initialFocusRequester,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }

        if (uiState.isLoadingSelectedCategory) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(loadingSectionHeight),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(color = Color.White)
                        Text(
                            text = stringResource(R.string.series_loading),
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        } else if (filteredGridSeries.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(loadingSectionHeight),
                    contentAlignment = Alignment.Center
                ) {
                    AppMessageState(
                        title = stringResource(R.string.series_no_found),
                        subtitle = stringResource(R.string.series_no_found_subtitle)
                    )
                }
            }
        } else {
            gridItems(filteredGridSeries, key = { it.id }) { series ->
                val isLocked = isSeriesLocked(series)
                val isDraggingThis = draggingSeries == series
                SeriesCard(
                    series = series,
                    isLocked = isLocked,
                    isReorderMode = uiState.isReorderMode,
                    isDragging = isDraggingThis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .then(if (!showSearchBar && series.id == initialGridSeriesId) Modifier.focusRequester(initialFocusRequester) else Modifier),
                    onClick = {
                        if (uiState.isReorderMode) {
                            draggingSeries = if (isDraggingThis) null else series
                        } else if (isLocked) {
                            onProtectedSeriesClick(series.id)
                        } else {
                            onSeriesClick(series.id)
                        }
                    },
                    onLongClick = {
                        if (!uiState.isReorderMode) onShowDialog(series)
                    }
                )
            }
        }
    }
}

@Composable
private fun SeriesVodClassicContent(
    uiState: SeriesUiState,
    selectedFilterType: LibraryFilterType,
    onSelectedFilterTypeChange: (LibraryFilterType) -> Unit,
    selectedSortBy: LibrarySortBy,
    onSelectedSortByChange: (LibrarySortBy) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSeriesClick: (Long) -> Unit,
    onProtectedSeriesClick: (Long) -> Unit,
    onProtectedCategoryClick: (Category) -> Unit,
    onShowDialog: (Series) -> Unit,
    onShowCategoryOptions: (String) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onSelectFullLibraryBrowse: () -> Unit,
    onOpenContinueWatching: () -> Unit,
    onOpenFresh: () -> Unit,
    onLoadMore: () -> Unit,
    onDismissReorder: () -> Unit,
    initialFocusRequester: FocusRequester
) {
    val allLabel = stringResource(R.string.vod_classic_all)
    val continueLabel = stringResource(R.string.vod_classic_continue_watching)
    val recentLabel = stringResource(R.string.vod_classic_recently_added)
    val categoryByName = remember(uiState.providerCategories, uiState.categories, uiState.favoriteCategoryName) {
        buildMap<String, Category> {
            uiState.providerCategories.forEach { put(it.name, it) }
            uiState.categories.forEach { put(it.name, it) }
            put(
                uiState.favoriteCategoryName,
                Category(
                    id = VodBrowseDefaults.FAVORITES_SENTINEL_ID,
                    name = uiState.favoriteCategoryName,
                    type = com.MegaStream.domain.model.ContentType.SERIES,
                    isVirtual = true
                )
            )
        }
    }
    val isCategoryLocked = remember(uiState.parentalControlLevel, uiState.unlockedCategoryIds, categoryByName) {
        { category: Category ->
            (category.isAdult || category.isUserProtected) &&
                uiState.parentalControlLevel in 1..2 &&
                kotlin.math.abs(category.id) !in uiState.unlockedCategoryIds
        }
    }
    val isCategoryHidden = remember(uiState.parentalControlLevel, uiState.unlockedCategoryIds, categoryByName) {
        { category: Category ->
            (category.isAdult || category.isUserProtected) &&
                uiState.parentalControlLevel >= 3 &&
                kotlin.math.abs(category.id) !in uiState.unlockedCategoryIds
        }
    }
    val isSeriesLocked = remember(uiState.parentalControlLevel, uiState.unlockedCategoryIds) {
        { series: Series ->
            val categoryId = series.categoryId
            (series.isAdult || series.isUserProtected) &&
                uiState.parentalControlLevel in 1..2 &&
                (categoryId == null || kotlin.math.abs(categoryId) !in uiState.unlockedCategoryIds)
        }
    }
    val openProtectedCategory: (Category) -> Unit = onProtectedCategoryClick
    val browseCategoryNames = remember(uiState.categoryNames, uiState.providerCategories, uiState.categories, uiState.favoriteCategoryName) {
        buildList {
            val names = linkedSetOf<String>()
            uiState.categoryNames.forEach(names::add)
            uiState.providerCategories.forEach { names.add(it.name) }
            uiState.categories.forEach { names.add(it.name) }
            names.add(uiState.favoriteCategoryName)
            addAll(names)
        }
    }
    val visibleCategoryNames = remember(browseCategoryNames, categoryByName, uiState.parentalControlLevel, uiState.unlockedCategoryIds) {
        browseCategoryNames.filter { name ->
            val category = categoryByName[name]
            category == null || !isCategoryHidden(category)
        }
    }
    var categoryQuery by rememberSaveable { mutableStateOf("") }
    var showBrowseOptions by rememberSaveable(uiState.selectedCategory) { mutableStateOf(false) }
    var showSearchBar by rememberSaveable(uiState.selectedCategory) { mutableStateOf(searchQuery.isNotBlank()) }
    val baseSeries = uiState.selectedCategoryItems
    val filteredGridSeries = remember(baseSeries, uiState.isReorderMode, uiState.filteredSeries) {
        if (uiState.isReorderMode) uiState.filteredSeries else baseSeries
    }
    var draggingSeries by remember { mutableStateOf<Series?>(null) }
    val initialGridSeriesId = filteredGridSeries.firstOrNull()?.id

    LaunchedEffect(uiState.vodViewMode, uiState.selectedCategory, uiState.isReorderMode) {
        if (uiState.vodViewMode == VodViewMode.CLASSIC && uiState.selectedCategory == null && !uiState.isReorderMode) {
            onSelectCategory(uiState.favoriteCategoryName)
        }
    }

    if (showBrowseOptions) {
        VodBrowseOptionsDialog(
            title = stringResource(R.string.nav_series),
            filterTitle = stringResource(R.string.library_filter_title),
            filterChips = seriesFilterChips(),
            selectedFilterKey = selectedFilterType.name,
            onFilterSelected = { key ->
                LibraryFilterType.entries.firstOrNull { it.name == key }?.let(onSelectedFilterTypeChange)
            },
            sortTitle = stringResource(R.string.library_sort_title),
            sortChips = seriesSortChips(),
            selectedSortKey = selectedSortBy.name,
            onSortSelected = { key ->
                LibrarySortBy.entries.firstOrNull { it.name == key }?.let(onSelectedSortByChange)
            },
            onDismiss = { showBrowseOptions = false }
        )
    }

    val selectedKey = when {
        uiState.selectedCategory == uiState.favoriteCategoryName -> "favorites"
        uiState.selectedCategory == uiState.fullLibraryCategoryName && selectedFilterType == LibraryFilterType.IN_PROGRESS -> "continue"
        uiState.selectedCategory == uiState.fullLibraryCategoryName && selectedFilterType == LibraryFilterType.RECENTLY_UPDATED -> "recent"
        uiState.selectedCategory == null || uiState.selectedCategory == uiState.fullLibraryCategoryName -> "all"
        else -> "category:${uiState.selectedCategory}"
    }
    val continueCount = remember(uiState.continueWatching) {
        uiState.continueWatching.map { it.seriesId ?: it.contentId }.distinct().size
    }
    val recentCount = uiState.libraryLensRows[SeriesLibraryLens.FRESH]?.size ?: 0
    val railOptions = remember(
        visibleCategoryNames,
        uiState.categoryCounts,
        uiState.favoriteCategoryName,
        uiState.selectedCategory,
        selectedFilterType,
        categoryQuery,
        continueCount,
        recentCount,
        uiState.libraryCount,
        uiState.unlockedCategoryIds,
        uiState.parentalControlLevel
    ) {
        buildList {
            add(
                VodClassicCategoryOption(
                    key = "all",
                    label = allLabel,
                    count = uiState.libraryCount,
                    isSelected = selectedKey == "all",
                    onClick = onSelectFullLibraryBrowse
                )
            )
            add(
                VodClassicCategoryOption(
                    key = "favorites",
                    label = uiState.favoriteCategoryName,
                    count = uiState.categoryCounts[uiState.favoriteCategoryName] ?: 0,
                    isSelected = selectedKey == "favorites",
                    onClick = { onSelectCategory(uiState.favoriteCategoryName) }
                )
            )
            add(
                VodClassicCategoryOption(
                    key = "continue",
                    label = continueLabel,
                    count = continueCount,
                    isSelected = selectedKey == "continue",
                    onClick = onOpenContinueWatching
                )
            )
            add(
                VodClassicCategoryOption(
                    key = "recent",
                    label = recentLabel,
                    count = recentCount,
                    isSelected = selectedKey == "recent",
                    onClick = onOpenFresh
                )
            )
            visibleCategoryNames
                .filterNot { it == uiState.favoriteCategoryName }
                .forEach { name ->
                    val matchedCategory = categoryByName[name]
                    val locked = matchedCategory?.let(isCategoryLocked) == true
                    add(
                        VodClassicCategoryOption(
                            key = "category:$name",
                            label = name,
                            count = uiState.categoryCounts[name] ?: 0,
                            isSelected = selectedKey == "category:$name",
                            onClick = {
                                if (locked && matchedCategory != null) openProtectedCategory(matchedCategory) else onSelectCategory(name)
                            },
                            onLongClick = matchedCategory?.takeIf { !locked }?.let { { onShowCategoryOptions(name) } },
                            isLocked = locked
                        )
                    )
                }
        }.filter { option ->
            categoryQuery.isBlank() || option.label.contains(categoryQuery.trim(), ignoreCase = true)
        }
    }

    VodClassicSplitLayout(
        railTitle = stringResource(R.string.nav_series),
        railSearchValue = categoryQuery,
        onRailSearchValueChange = { categoryQuery = it },
        railSearchPlaceholder = stringResource(R.string.vod_classic_category_search),
        categories = railOptions
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val hasActiveFilterSortClassic = selectedFilterType != LibraryFilterType.ALL || selectedSortBy != LibrarySortBy.LIBRARY
            VodClassicContentHeader(
                title = when {
                    selectedKey == "all" -> allLabel
                    selectedKey == "continue" -> continueLabel
                    selectedKey == "recent" -> recentLabel
                    else -> uiState.selectedCategory ?: allLabel
                },
                subtitle = stringResource(
                    R.string.vod_classic_results_count,
                    filteredGridSeries.size
                ),
                actions = buildList {
                    add(
                        VodActionChip(
                            key = "search_toggle",
                            label = stringResource(
                                if (showSearchBar) R.string.library_action_hide_search else R.string.search_title
                            ),
                            onClick = { showSearchBar = !showSearchBar }
                        )
                    )
                    add(
                        VodActionChip(
                            key = "browse_options",
                            label = stringResource(R.string.library_action_filters_sort),
                            detail = vodActiveFilterSortDetail(selectedFilterType, selectedSortBy),
                            onClick = { showBrowseOptions = true }
                        )
                    )
                },
                selectedActionKey = if (hasActiveFilterSortClassic) "browse_options" else null
            )

            if (showSearchBar) {
                SearchInput(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = stringResource(R.string.series_search_placeholder),
                    onSearch = {},
                    focusRequester = initialFocusRequester,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            val classicGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
            InfiniteScrollEffect(
                gridState = classicGridState,
                enabled = !uiState.isReorderMode,
                canLoadMore = uiState.canLoadMoreSelectedCategory,
                isLoading = uiState.isLoadingSelectedCategory,
                onLoadMore = onLoadMore
            )
            LazyVerticalGrid(
                state = classicGridState,
                columns = GridCells.Adaptive(minSize = 100.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onPreviewKeyEvent { event ->
                        if (uiState.isReorderMode && event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                            if (event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                                draggingSeries = null
                                onDismissReorder()
                                true
                            } else false
                        } else false
                    },
                contentPadding = PaddingValues(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (uiState.isLoadingSelectedCategory) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Color.White)
                        }
                    }
                } else if (filteredGridSeries.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        AppMessageState(
                            title = stringResource(R.string.series_no_found),
                            subtitle = stringResource(R.string.vod_classic_empty_category)
                        )
                    }
                } else {
                    gridItems(filteredGridSeries, key = { it.id }) { series ->
                        val isLocked = isSeriesLocked(series)
                        val isDraggingThis = draggingSeries == series
                        SeriesCard(
                            series = series,
                            isLocked = isLocked,
                            isReorderMode = uiState.isReorderMode,
                            isDragging = isDraggingThis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(2f / 3f)
                                .then(if (!showSearchBar && series.id == initialGridSeriesId) Modifier.focusRequester(initialFocusRequester) else Modifier),
                            onClick = {
                                if (uiState.isReorderMode) {
                                    draggingSeries = if (isDraggingThis) null else series
                                } else if (isLocked) {
                                    onProtectedSeriesClick(series.id)
                                } else {
                                    onSeriesClick(series.id)
                                }
                            },
                            onLongClick = {
                                if (!uiState.isReorderMode) onShowDialog(series)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun seriesLibraryLensLabel(lens: SeriesLibraryLens): String =
    when (lens) {
        SeriesLibraryLens.FAVORITES -> stringResource(R.string.library_lens_favorites)
        SeriesLibraryLens.CONTINUE -> stringResource(R.string.library_lens_continue)
        SeriesLibraryLens.TOP_RATED -> stringResource(R.string.library_lens_top_rated)
        SeriesLibraryLens.FRESH -> stringResource(R.string.library_lens_fresh_series)
    }

private fun seriesFilterChips(): List<SelectionChip> {
    return listOf(
        SelectionChip(LibraryFilterType.ALL.name, "All"),
        SelectionChip(LibraryFilterType.FAVORITES.name, "Favorites"),
        SelectionChip(LibraryFilterType.IN_PROGRESS.name, "Resume"),
        SelectionChip(LibraryFilterType.UNWATCHED.name, "Unwatched"),
        SelectionChip(LibraryFilterType.RECENTLY_UPDATED.name, "Updated"),
        SelectionChip(LibraryFilterType.TOP_RATED.name, "Top Rated")
    )
}

private fun seriesSortChips(): List<SelectionChip> {
    return LibrarySortBy.entries.map { sort ->
        SelectionChip(
            key = sort.name,
            label = when (sort) {
                LibrarySortBy.LIBRARY -> "Library Order"
                LibrarySortBy.TITLE -> "A-Z"
                LibrarySortBy.RELEASE -> "Newest"
                LibrarySortBy.UPDATED -> "Recently Updated"
                LibrarySortBy.RATING -> "Rating"
                LibrarySortBy.WATCH_COUNT -> "Recent Activity"
            }
        )
    }
}
