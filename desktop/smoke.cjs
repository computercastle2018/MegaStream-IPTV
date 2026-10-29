const { _electron: electron } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

(async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'megastream-desktop-test-'));
  const server = http.createServer((request, response) => {
    if (request.url === '/test.ts') {
      response.writeHead(200, { 'Content-Type': 'video/mp2t' });
      fs.createReadStream(process.env.TEST_TS_FILE).pipe(response);
      return;
    }
    response.writeHead(200, { 'Content-Type': 'audio/x-mpegurl' });
    response.end(`#EXTM3U\n#EXTINF:-1 group-title="Test",Desktop Test Channel\nhttp://127.0.0.1:${server.address().port}/test.ts\n`);
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let application;
  try {
    application = await electron.launch({ executablePath: require('electron'), args: [path.join(__dirname, 'smoke-main.cjs'), `--user-data-dir=${directory}`],
      env: { ...process.env, TEST_PLAYLIST_URL: `http://127.0.0.1:${server.address().port}/playlist.m3u` } });
    const page = await application.firstWindow();
    await page.waitForSelector('.desktop-license');
    assert.equal(await page.evaluate(() => Boolean(window.megastreamDesktop)), true);
    await page.locator('#desktop-license-key').fill('TEST-LICENSE');
    await page.locator('.license-key-row button').click();
    await page.waitForSelector('.home-screen');
    await page.locator('.tile-live').click();
    await page.getByText('Desktop Test Channel', { exact: true }).click();
    await page.waitForSelector('video[controls]');
    await page.waitForFunction(() => document.querySelector('video')?.currentTime > 1);
    await page.screenshot({ path: path.join(directory, 'player.png') });
    await page.waitForFunction(() => getComputedStyle(document.querySelector('.player-top')).opacity === '0');
    await page.mouse.move(400, 200);
    await page.waitForFunction(() => getComputedStyle(document.querySelector('.player-top')).opacity === '1');
    const stored = await page.evaluate(() => ({ encrypted: window.megastreamDesktop.storage.getItem('MegaStream.providers'), plain: localStorage.getItem('MegaStream.providers') }));
    assert.ok(stored.encrypted.includes('Desktop test'));
    assert.equal(stored.plain, null);
    assert.equal(fs.readFileSync(path.join(directory, 'providers.encrypted')).includes(Buffer.from('Desktop test')), false);
    await page.reload();
    await page.waitForSelector('.home-screen');
    await page.screenshot({ path: path.join(directory, 'home.png') });
    console.log('PASS (isolated control-plane fixture): activation, remotely assigned playlist, hidden details policy, HTTP/MPEG-TS playback, encrypted persistence, auto-hide, reload. Screenshot:', path.join(directory, 'home.png'));
  } finally {
    if (application) await application.close();
    await new Promise(resolve => server.close(resolve));
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
