import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const read = (name) => readFileSync(new URL(`../main/java/com/streamvault/app/ui/screens/${name}`, import.meta.url), 'utf8');

// Source contracts only; device rendering and remote focus are checked by Main.
test('Dashboard lazy shelves are not measured intrinsically by FlowRow', () => {
    const dashboard = read('dashboard/DashboardScreen.kt');
    const shelves = dashboard.slice(dashboard.indexOf('fun DashboardScreen('), dashboard.indexOf('private fun StudioDashboardHero('));
    assert.doesNotMatch(shelves, /FlowRow\(/);
    assert.match(shelves, /items\(orderedSections, key/);
});

test('Studio Settings keeps ten categories and managed choices cannot write local preference', () => {
    const screen = read('settings/SettingsScreen.kt');
    const pane = read('settings/SettingsContentPane.kt');
    const templates = read('settings/SettingsTemplates.kt');
    const sectionNav = screen.slice(screen.indexOf('private fun StudioSettingsSections'));
    const names = [...sectionNav.matchAll(/R\.string\.(settings_\w+)/g)].map(([ , name]) => name);
    assert.deepEqual(names.slice(0, 10), [
        'settings_providers', 'settings_playback', 'settings_browsing', 'settings_privacy',
        'settings_recording_title', 'settings_backup_restore', 'settings_epg_sources_section',
        'settings_templates', 'settings_license', 'settings_about',
    ]);
    assert.match(pane, /LocalAppUiStyleManaged\.current/);
    assert.match(pane, /if \(!managed\) viewModel\.setAppUiStyle\(it\)/);
    assert.match(templates, /AppUiStyleOption\(style, style == selectedStyle, !managed/);
    for (const style of ['CLASSIC', 'MODERN', 'STUDIO']) {
        assert.match(templates, new RegExp(`AppUiStyle\\.${style} ->`));
    }
});

test('Studio Search measures result columns beside its filters and reuses gated results', () => {
    const source = read('search/SearchScreen.kt');
    assert.match(source, /BoxWithConstraints\(Modifier\.weight\(1f\)\.fillMaxHeight\(\)\)/);
    assert.match(source, /maxWidth\.value \+ 12f/);
    assert.match(source, /resultsContent\(channelsPerRow, postersPerRow\)/);
    assert.match(source, /resultsContent\(channelColumns, posterColumns\)/);
    assert.match(source, /studio_search_featured/);
    assert.match(source, /pendingMovie = featuredMovie; showPinDialog = true/);
});

test('Studio guide fits a bounded sidebar and shares legacy gated grid callbacks', () => {
    const source = read('epg/EpgScreen.kt');
    assert.match(source, /Modifier\.width\(154\.dp\)\.fillMaxHeight\(\)/);
    assert.equal((source.match(/EpgGrid\(/g) ?? []).length, 1);
    assert.equal((source.match(/guideGrid\(Modifier\.fillMaxWidth\(\)\.weight\(1f\)\)/g) ?? []).length, 2);
    assert.match(source, /LockedGuideAction\.SelectCategory\(category\)/);
    assert.match(source, /LockedGuideAction\.PlayChannel\(channel, returnRoute\)/);
    assert.match(source, /GuideHeroSection\(/);
});
