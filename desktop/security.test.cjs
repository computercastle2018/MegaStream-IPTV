const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { assetPath, validateStorage } = require('./security.cjs');
test('bundled assets resolve without escaping the UI directory', () => {
  const root = path.resolve('ui');
  assert.equal(assetPath(root, 'megastream://app/assets/player.js'), path.join(root, 'assets/player.js'));
  for (const url of ['https://example.com/index.html', 'megastream://other/index.html', 'megastream://app/%2e%2e%2fsecret', 'megastream://app/assets%2f..%2f..%2fsecret']) {
    assert.throws(() => assetPath(root, url));
  }
});
test('renderer cannot access arbitrary storage keys or oversized values', () => {
  validateStorage('setItem', 'MegaStream.providers', '[]');
  for (const args of [['setItem', '../../secret', 'x'], ['execute', 'MegaStream.providers'], ['setItem', 'MegaStream.providers', 'x'.repeat(1024 * 1024 + 1)]]) {
    assert.throws(() => validateStorage(...args));
  }
});
