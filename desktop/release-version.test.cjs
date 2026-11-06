const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

test('all platform packages and reported builds share the release version', () => {
  const read = name => fs.readFileSync(path.join(__dirname, '..', name), 'utf8');
  const desktop = JSON.parse(read('desktop/package.json'));
  const webos = JSON.parse(read('webos/package.json'));
  const appinfo = JSON.parse(read('webos/public/appinfo.json'));
  const android = read('app/build.gradle.kts');
  const version = android.match(/versionName = "([^"]+)"/)[1];
  const build = Number(android.match(/versionCode = (\d+)/)[1]);
  assert.deepEqual([desktop.version, webos.version, appinfo.version], [version, version, version]);
  assert.equal(appinfo.title, `MegaStream ${version}`);
  assert.ok(webos.scripts['install-tv'].includes(`_${version}_all.ipk`));
  for (const source of ['desktop/control-plane.cjs', 'webos/src/services/webosControl.ts']) {
    assert.equal(Number(read(source).match(/const versionCode = (\d+);/)[1]), build, source);
  }
});
