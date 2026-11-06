// Read the actual Windows executable resources; no Windows installation required.
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { NtExecutable, NtExecutableResource, Resource, Data } = require('resedit');
const { convertIcon } = require('app-builder-lib/out/util/iconConverter');

(async () => {
  const outDir = fs.mkdtempSync(path.join(os.tmpdir(), 'megastream-branding-'));
  const converted = await convertIcon({ sources: [path.resolve(__dirname, '../webos/store/icon-512.png')],
    fallbackSources: [], roots: [__dirname], format: 'ico', outDir });
  assert.equal(converted.icons.length, 1);
  const expected = Data.IconFile.from(fs.readFileSync(converted.icons[0].file)).icons.map(icon => icon.data);
  const exe = NtExecutable.from(fs.readFileSync(path.join(__dirname, 'release/win-unpacked/MegaStream.exe')));
  const resources = NtExecutableResource.from(exe).entries;
  const actual = Resource.IconGroupEntry.fromEntries(resources).flatMap(group => group.getIconItemsFromEntries(resources));
  const digest = item => crypto.createHash('sha256').update(Buffer.from(item.isRaw() ? item.bin : item.generate())).digest('hex');
  const icons = new Set(actual.map(digest));
  for (const icon of expected) assert.ok(icons.has(digest(icon)), 'Windows executable must embed the actual MegaStream brand icon');
  const version = Resource.VersionInfo.fromEntries(resources)[0];
  const strings = version.getStringValues(version.getAllLanguagesForStringValues()[0]);
  assert.equal(strings.ProductName, 'MegaStream');
  assert.ok(strings.ProductVersion.startsWith(require('./package.json').version));
  console.log('PASS: Windows executable embeds every MegaStream icon size and correct product/version metadata.');
})().catch(error => { console.error(error); process.exitCode = 1; });
