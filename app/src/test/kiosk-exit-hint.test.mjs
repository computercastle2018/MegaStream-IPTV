import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const source = readFileSync(new URL('../main/java/com/streamvault/app/kiosk/integration/KioskHost.kt', import.meta.url), 'utf8');

test('exit hint expires even when focused, but confirmation remains interactive', () => {
    assert.doesNotMatch(source, /exitFocused/);
    assert.match(source, /LaunchedEffect\(interaction, state\.confirmationPending\)/);
    assert.match(source, /if \(!state\.confirmationPending\)\s*\{\s*delay\(4000\)\s*val restoreFocus = returnFocused\s*exitVisible = false/);
    assert.match(source, /if \(restoreFocus\)\s*\{\s*withFrameNanos \{ \}\s*playerFocus\.value\?\.requestFocusSafely/);
});

test('stationary pointer events cannot keep the exit hint visible', () => {
    assert.match(source, /event\.type == PointerEventType\.Move && event\.changes\.any \{ it\.position != it\.previousPosition \}/);
    assert.match(source, /event\.type == PointerEventType\.Scroll && event\.changes\.any \{ it\.scrollDelta != Offset\.Zero \}/);
});

test('visible return action navigates Home and never exits the application', () => {
    assert.match(source, /onClick = onReturnHome/);
    assert.doesNotMatch(source, /onClick = controller::beginManualExit/);
    assert.match(source, /R\.string\.return_to_home/);
    const activity = readFileSync(new URL('../main/java/com/streamvault/app/MainActivity.kt', import.meta.url), 'utf8');
    assert.match(activity, /fun returnToHome\(\)\s*\{\s*_externalNavigationRequestFlow\.value = ExternalNavigationRequest\.Destination\(ExternalDestination\.Home\)/);
});
