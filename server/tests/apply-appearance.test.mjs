import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import test from 'node:test';

test('changing a design submits its existing form, including local/inherited selection', () => {
    const source = readFileSync(new URL('../MegaStream.Server/wwwroot/js/apply-appearance.js', import.meta.url), 'utf8');
    let change, submissions = 0;
    const select = { value: 'studio', form: { requestSubmit() { submissions++; } },
        addEventListener(name, handler) { assert.equal(name, 'change'); change = handler; } };
    runInNewContext(source, { document: { querySelectorAll(selector) {
        assert.equal(selector, 'select[data-apply-appearance]'); return [select];
    } } });
    for (const value of ['classic', 'modern', 'studio', '']) { select.value = value; change(); }
    assert.equal(submissions, 4);
    for (const view of ['DeviceDetails', 'LicenseDetails']) {
        const html = readFileSync(new URL(`../MegaStream.Server/Views/Admin/${view}.cshtml`, import.meta.url), 'utf8');
        assert.match(html, /name="UiStyle" data-apply-appearance/);
        assert.match(html, /js\/apply-appearance\.js/);
    }
});
