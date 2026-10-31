import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';

const root = new URL('./', import.meta.url);
const ui = 'app/src/main/java/com/streamvault/app/ui/';
const read = path => readFileSync(new URL(ui + path, root), 'utf8');
const palette = read('components/shell/AppMediaCards.kt');
assert.match(palette, /LocalAppUiStyle.current == AppUiStyle.STUDIO/);
assert.match(palette, /8.dp else classicRadius/);
for (const [, name] of read('components/shell/AppShell.kt').matchAll(/AppColors\.(\w+)/g)) {
    assert.match(palette, new RegExp(`val ${name}: Color`), `Missing shell color: ${name}`);
    assert.match(palette, new RegExp(`studioColor\\(AppColors\\.${name},`), `Missing legacy color: ${name}`);
}

const dashboard = read('screens/dashboard/DashboardScreen.kt');
const selection = dashboard.slice(dashboard.indexOf('item(key = "studio_home_hero")'), dashboard.indexOf('if (uiState.isLoading'));
assert.equal((selection.match(/!it.isAdult && !it.isUserProtected/g) || []).length, 2);
assert.match(selection, /else -> DashboardFeature\(title = provider.name\)/);
assert.doesNotMatch(selection, /uiState.feature/);
assert.doesNotMatch(dashboard, /icons.filled.(LiveTv|Movie)/);
const hero = dashboard.slice(dashboard.indexOf('private fun StudioDashboardHero'), dashboard.indexOf('private fun DashboardHero'));
assert.match(hero, /AsyncImage/);
assert.doesNotMatch(hero, /\.clip\(/);

const search = read('screens/search/SearchScreen.kt');
const baseline = execFileSync('git', ['show', `HEAD:${ui}screens/search/SearchScreen.kt`], { cwd: root, encoding: 'utf8' });
const viewModel = source => source.slice(source.indexOf('class SearchViewModel'), source.indexOf('private data class SearchFilterParams'));
assert.equal(viewModel(search), viewModel(baseline), 'Search ViewModel changed');
for (const name of ['onClick', 'onLongClick', 'onPreviewKeyEvent', 'verifyPin', 'unlockCategory', 'requestFocusSafely']) {
    const pattern = new RegExp(`\\b${name}\\b`, 'g');
    assert.equal((search.match(pattern) || []).length, (baseline.match(pattern) || []).length, `Search interaction changed: ${name}`);
}
console.log('Studio shared UI source checks passed (not an Android runtime test).');
