// Isolated startup regression: no production accounts, providers or user data.
const { _electron: electron } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');

(async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'megastream-startup-'));
  const output = process.env.TEST_OUTPUT_DIR || directory;
  fs.mkdirSync(output, { recursive: true });
  const server = http.createServer((_request, response) => {
    response.setHeader('Content-Type', 'audio/x-mpegurl');
    response.end('#EXTM3U\n#EXTINF:-1 group-title="Test",Startup fixture\nhttps://example.invalid/fixture.ts\n');
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let application, page;
  const errors = [];
  try {
    application = await electron.launch({ executablePath: require('electron'),
      args: [path.join(__dirname, 'smoke-main.cjs'), `--user-data-dir=${directory}`],
      env: { ...process.env, TEST_PLAYLIST_URL: `http://127.0.0.1:${server.address().port}/fixture.m3u`,
        ...(process.env.STARTUP_PACKAGED === '1' ? { TEST_APPLICATION_MAIN: path.join(__dirname, 'release/win-unpacked/resources/app.asar/main.cjs') } : {}) },
      timeout: 30000 });
    application.process().stderr.on('data', data => process.stderr.write(data));
    page = await application.firstWindow();
    page.setDefaultTimeout(15000);
    page.on('pageerror', error => errors.push(error.message));
    page.on('requestfailed', request => {
      if (request.url().startsWith('megastream://app/')) errors.push(`Asset failed: ${request.url()}`);
    });
    await page.locator('.desktop-license').waitFor();
    await page.getByText('Connected to control panel', { exact: true }).waitFor();
    assert.equal(await page.evaluate(() => Boolean(window.megastreamDesktop)), true, 'Preload bridge must load');
    await page.getByLabel('License key', { exact: true }).fill('TEST-LICENSE');
    await page.getByRole('button', { name: 'Activate', exact: true }).click();
    await page.locator('.home-screen').waitFor();
    await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].showInactive());
    const screenshot = path.join(output, 'startup.png');
    await page.screenshot({ path: screenshot, timeout: 30000 });
    const range = await application.evaluate(({ nativeImage }, screenshot) => {
      const pixels = nativeImage.createFromPath(screenshot).resize({ width: 80, height: 50 }).toBitmap();
      let low = 255, high = 0;
      for (let i = 0; i < pixels.length; i++) if (i % 4 !== 3) { low = Math.min(low, pixels[i]); high = Math.max(high, pixels[i]); }
      return high - low;
    }, screenshot);
    assert.ok(range > 40, 'Rendered startup window must not be a black frame');
    assert.deepEqual(errors, []);
    console.log('PASS: preload, license UI, signed fixture activation, catalog home and nonblack rendered pixels.', { platform: process.platform, pixelRange: range, screenshot });
  } catch (error) {
    console.error('Startup failure:', errors);
    if (page) console.error(await page.evaluate(() => ({
      state: document.readyState, bridge: Boolean(window.megastreamDesktop),
      rootChildren: document.getElementById('root')?.childElementCount, body: document.body.innerText.slice(0, 300),
    })).catch(() => 'Renderer unavailable'));
    throw error;
  } finally {
    if (application) await application.close();
    await new Promise(resolve => server.close(resolve));
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
