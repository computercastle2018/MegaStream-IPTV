import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const read = (path) => readFileSync(new URL(`../main/java/com/streamvault/app/ui/screens/${path}`, import.meta.url), 'utf8');

// Source contracts only; Main verifies Android rendering and remote focus after building.
for (const [directory, screen, section] of [['movies', 'Movies', 'MOVIES'], ['series', 'Series', 'SERIES']]) {
    test(`${screen} top action is accessible in every template with busy and result states`, () => {
        const source = read(`${directory}/${screen}Screen.kt`);
        const action = source.slice(source.indexOf('topBarActions = {'), source.indexOf('if (uiState.isReorderMode'));
        assert.match(action, /onClick = viewModel::syncCatalog/);
        assert.doesNotMatch(action, /if\s*\(studio\)/);
        assert.match(action, /enabled = uiState.hasActiveProvider && !uiState.isCatalogRefreshing/);
        assert.match(action, /contentDescription = syncLabel/);
        assert.match(action, /stateDescription = syncProgress/);
        assert.match(action, /CircularProgressIndicator\(Modifier.size\(24.dp\)/);
        assert.match(source, /snackbarHostState.showSnackbar\(syncSuccess\)/);
        assert.match(action, /R.string.vod_catalog_sync_failed/);
    });

    test(`${screen} manual refresh reuses selective sync and leaves automatic cache policy separate`, () => {
        const source = read(`${directory}/${screen}ViewModel.kt`);
        assert.match(source, /manualCatalogRefresh = VodCatalogRefresh\(viewModelScope, \{ false \}\)/);
        assert.match(source, /manualCatalogRefresh.enter\(provider\?\.id, true\)/);
        assert.match(source, /manualCatalogRefresh.refreshIfNeeded\(force = true\)/);
        assert.match(source, new RegExp(`retrySection\\(providerId, SyncRepairSection\\.${section}\\)`));
        assert.match(source, /processQueuedXtreamIndexJobs\(providerId, ContentType\.(MOVIE|SERIES), force = true\)/);
        assert.doesNotMatch(source, /syncManager\.sync\(/);
        assert.match(source, /ContentCachePolicy.shouldRefresh/);
    });
}
