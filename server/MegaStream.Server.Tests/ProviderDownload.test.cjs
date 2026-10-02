const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

test('M3U export keeps errors inline and downloads a named blob only after successful completion', async () => {
    const listeners = new Map();
    const button = { disabled: false, textContent: 'Download' };
    const revealButton = { setAttribute() {} };
    const error = { textContent: '' };
    const output = { hidden: true, replaceChildren() {} };
    const form = {
        action: 'https://admin.invalid/admin/providers/installations/device/subscriptions/1/export',
        querySelector: () => button,
        addEventListener: (_, handler) => listeners.set('export', handler)
    };
    const reveal = { querySelector: () => revealButton, addEventListener() {} };
    const root = { querySelector: selector => ({
        '[data-provider-reveal]': reveal, '[data-provider-credentials]': output,
        '[data-provider-error]': error, '[data-provider-export]': form,
        '[data-provider-reveal] button': revealButton
    })[selector] };
    const downloads = [];
    const revoked = [];
    let response;
    const requests = [];
    const context = {
        document: {
            hidden: false, querySelectorAll: () => [root], addEventListener() {},
            createElement: () => ({ click() { downloads.push({ name: this.download, url: this.href }); } })
        },
        window: { addEventListener() {} },
        FormData: class { constructor(value) { this.form = value; } },
        AbortSignal,
        fetch: async (url, options) => { requests.push({ url, options }); return response; },
        URL: { createObjectURL: () => 'blob:download', revokeObjectURL: value => revoked.push(value) },
        setTimeout: callback => callback()
    };
    vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../MegaStream.Server/wwwroot/js/provider-credentials.js'), 'utf8'), context);
    let prevented = 0;
    const submit = () => listeners.get('export')({ preventDefault: () => prevented++ });
    response = { ok: false };
    await submit();
    assert.equal(prevented, 1);
    assert.equal(downloads.length, 0);
    assert.notEqual(error.textContent, '');
    assert.equal(button.disabled, false);
    response = {
        ok: true,
        headers: { get: name => name === 'Content-Type' ? 'audio/x-mpegurl; charset=utf-8' : "attachment; filename*=UTF-8''Kings-Pro.m3u" },
        blob: async () => ({})
    };
    await submit();
    assert.equal(error.textContent, '');
    assert.deepEqual(downloads, [{ name: 'Kings-Pro.m3u', url: 'blob:download' }]);
    assert.deepEqual(revoked, ['blob:download']);
    assert.equal(requests[1].options.method, 'POST');
    assert.equal(requests[1].options.cache, 'no-store');
    assert.equal(requests[1].options.credentials, 'same-origin');
    assert.equal(requests[1].options.redirect, 'error');
    assert.equal(requests[1].options.body.form, form);
    response = { ...response, blob: async () => { throw new Error('partial download'); } };
    await submit();
    assert.equal(downloads.length, 1);
    assert.notEqual(error.textContent, '');
    assert.equal(button.disabled, false);
});
