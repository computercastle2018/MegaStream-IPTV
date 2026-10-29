const { _electron: electron } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

(async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'megastream-live-verification-'));
  let application;
  let id;
  try {
    for (let attempt = 0; attempt < 2; attempt++) {
      application = await electron.launch({ executablePath: process.env.TEST_EXECUTABLE,
        args: [`--user-data-dir=${directory}`] });
      const page = await application.firstWindow();
      await page.waitForSelector('.desktop-license');
      const state = await page.evaluate(() => window.megastreamDesktop.control.sync());
      assert.equal(state.online, true);
      assert.equal(state.state, 'unlicensed');
      if (id) assert.equal(state.installationId, id); else id = state.installationId;
      if (attempt === 1) {
        await page.getByRole('button', { name: /العربية/ }).click();
        await page.waitForFunction(() => document.documentElement.dir === 'rtl');
        await page.screenshot({ path: path.join(directory, 'license-ar.png') });
      }
      await application.close(); application = null;
    }
    console.log(JSON.stringify({ verified: true, installationId: id, profile: directory, screenshot: path.join(directory, 'license-ar.png'), checks: ['packaged macOS registration', 'live heartbeat', 'stable identity after restart', 'Arabic license UI'] }));
  } finally { if (application) await application.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
