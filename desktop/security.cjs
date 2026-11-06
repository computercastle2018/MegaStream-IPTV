const path = require('node:path');

const storageKeys = new Set(['MegaStream.providers', 'MegaStream.activeProviderId']);
function assetPath(root, input) {
  const url = new URL(input);
  if (url.protocol !== 'megastream:' || url.hostname !== 'app') throw new Error('Invalid origin');
  const relative = decodeURIComponent(url.pathname).replace(/^\/+/, '') || 'index.html';
  const resolved = path.resolve(root, relative);
  if (!resolved.startsWith(path.resolve(root) + path.sep)) throw new Error('Invalid path');
  return resolved;
}
function validateStorage(operation, key, value) {
  if (!['getItem', 'setItem', 'removeItem'].includes(operation) || !storageKeys.has(key)) throw new Error('Invalid storage request');
  if (operation === 'setItem' && (typeof value !== 'string' || Buffer.byteLength(value) > 1024 * 1024)) throw new Error('Invalid storage value');
}
module.exports = { assetPath, validateStorage };
