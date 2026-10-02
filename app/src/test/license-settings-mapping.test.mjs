import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import test from 'node:test';

const root = fileURLToPath(new URL('../../../', import.meta.url));
const read = (path) => readFileSync(join(root, 'app/src/main/java/com/streamvault/app', path), 'utf8');

// Source contract check only; Android rendering and focus require instrumentation tests.
test('settings rail indices match their content, with Templates and License immediately before About', () => {
    const rail = read('ui/screens/settings/SettingsNavigationRail.kt');
    const pane = read('ui/screens/settings/SettingsContentPane.kt');
    const labels = [...rail.matchAll(/label = (?:stringResource\(R\.string\.(\w+)\)|"([^"]+)")/g)]
        .map((match) => match[1] ?? match[2]);
    const mapping = [
        ['settings_providers', 'providerSection'],
        ['settings_playback', 'settingsPlaybackSection'],
        ['settings_browsing', 'settingsBrowsingSection'],
        ['settings_privacy', 'settingsPrivacySection'],
        ['settings_recording_title', 'settingsRecordingSection'],
        ['settings_backup_restore', 'settingsBackupSection'],
        ['EPG Sources', 'epgSourcesSection'],
        ['settings_templates', 'showAppUiStyleDialog = true'],
        ['settings_license', 'LicenseActivationRoute'],
        ['settings_about', 'settingsAboutSection'],
    ];
    assert.deepEqual(labels, mapping.map(([label]) => label));
    for (const [index, [, content]] of mapping.entries()) {
        const section = pane.match(new RegExp(
            `selectedCategory == ${index}\\) \\{([\\s\\S]*?)(?=\\n\\s*\\} else if|\\n    LazyColumn|$)`
        ));
        assert.ok(section, `Missing category ${index}`);
        assert.ok(section[1].includes(content), `Category ${index} must render ${content}`);
    }
});

test('settings license remains embedded and the standalone Settings activation button is absent', () => {
    const pane = read('ui/screens/settings/SettingsContentPane.kt');
    const route = read('navigation/LicenseActivationRoute.kt');
    const navigation = read('navigation/AppNavigation.kt');
    assert.match(pane, /settingsMode = true/);
    assert.match(route, /if \(!settingsMode && verdict is PlaybackGateVerdict\.Allowed\) onContinue\(\)/);
    const settings = navigation.slice(navigation.indexOf('route = Routes.SETTINGS_DESTINATION'),
        navigation.indexOf('composable(Routes.PLUGINS)'));
    assert.ok(settings.includes('SettingsScreen('));
    assert.ok(!settings.includes('license_nav_open'));
    assert.ok(!settings.includes('Routes.LICENSE_ACTIVATION'));
});
