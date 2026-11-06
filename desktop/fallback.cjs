// Local regression for a provider whose HLS endpoint fails but TS is available.
const { _electron: electron } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
(async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'megastream-fallback-'));
  const media = path.join(directory, 'sample.ts');
  const generated = spawnSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', 'testsrc2=size=1920x1080:rate=25',
    '-t', '4', '-c:v', 'libx264', '-preset', 'ultrafast', '-f', 'mpegts', media]);
  assert.equal(generated.status, 0);
  const attempts = { parsing: 0, http: 0, unavailable: 0 };
  let availableOnRetry = false;
  const server = http.createServer((request, response) => {
    const url = new URL(request.url, 'http://localhost');
    if (url.pathname.endsWith('player_api.php')) {
      const data = {
        get_live_categories: [{ category_id: '1', category_name: 'Test' }],
        get_live_streams: [{ stream_id: 1, name: 'Recovered', category_id: '1' }, { stream_id: 2, name: 'Unavailable', category_id: '1' },
          { stream_id: 3, name: 'HTTP recovered', category_id: '1' }],
      };
      response.setHeader('Content-Type', 'application/json');
      response.end(JSON.stringify(data[url.searchParams.get('action')] || { user_info: { auth: 1 } }));
    } else if (url.pathname.endsWith('/1.m3u8')) {
      response.setHeader('Content-Type', 'application/x-mpegurl');
      response.end('<html>Not an HLS manifest</html>');
    } else if (url.pathname.endsWith('/1.ts') || url.pathname.endsWith('/3.ts') || (availableOnRetry && url.pathname.endsWith('/2.ts'))) {
      attempts[url.pathname.endsWith('/1.ts') ? 'parsing' : url.pathname.endsWith('/3.ts') ? 'http' : 'unavailable']++;
      response.setHeader('Content-Type', 'video/mp2t');
      fs.createReadStream(media).pipe(response);
    } else {
      if (url.pathname.endsWith('/2.ts')) attempts.unavailable++;
      response.writeHead(404); response.end();
    }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let application;
  try {
    application = await electron.launch({ executablePath: require('electron'), args: [path.join(__dirname, 'smoke-main.cjs'), `--user-data-dir=${directory}`],
      env: { ...process.env, TEST_XTREAM_URL: `http://127.0.0.1:${server.address().port}` } });
    const page = await application.firstWindow();
    await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].hide());
    await page.getByLabel('License key', { exact: true }).fill('TEST-LICENSE');
    await page.getByRole('button', { name: 'Activate', exact: true }).click();
    await page.locator('.tile-live').click();
    for (const channel of ['Recovered', 'HTTP recovered']) {
    await page.getByText(channel, { exact: true }).click();
    await page.waitForFunction(() => document.querySelector('video')?.getVideoPlaybackQuality().totalVideoFrames > 10, undefined, { timeout: 20000 });
    assert.equal(await page.locator('video').evaluate(video => video.videoHeight), 1080);
    await page.keyboard.press('Escape');
    }
    assert.equal(attempts.parsing, 1);
    assert.equal(attempts.http, 1);
    await page.getByText('Unavailable', { exact: true }).click();
    await page.locator('.player-screen .error').waitFor({ timeout: 20000 });
    assert.equal(attempts.unavailable, 1, 'Unavailable channels must not loop indefinitely');
    assert.equal(await page.locator('.retry-playback').count(), 1, 'Playback error must offer Retry');
    availableOnRetry = true;
    await page.locator('.retry-playback').click();
    await page.waitForFunction(() => document.querySelector('video')?.getVideoPlaybackQuality().totalVideoFrames > 10, undefined, { timeout: 20000 });
    assert.equal(attempts.unavailable, 2, 'Retry must make one fresh fallback attempt');
    await page.keyboard.press('Escape');
    await page.locator('.channel-card').first().waitFor();
    console.log('PASS: HLS failure recovers through TS with decoded HD frames; missing sources stop after one fallback and Back remains usable.');
  } finally {
    if (application) await application.close();
    await new Promise(resolve => server.close(resolve));
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
