// Real local video and isolated application data; never uses a production account.
const { _electron: electron } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

(async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'megastream-resume-'));
  const media = path.join(directory, 'sample.mp4');
  const encoded = spawnSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i',
    'testsrc2=size=320x180:rate=10', '-t', '90', '-an', '-c:v', 'libx264', '-preset', 'ultrafast',
    '-pix_fmt', 'yuv420p', '-movflags', '+faststart', media], { timeout: 30000 });
  assert.equal(encoded.status, 0, encoded.stderr?.toString());
  const hls = spawnSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-i', media, '-c', 'copy', '-hls_time', '3',
    '-hls_list_size', '0', path.join(directory, 'sample.m3u8')], { timeout: 30000 });
  assert.equal(hls.status, 0, hls.stderr?.toString());
  let mediaRequests = 0;
  const server = http.createServer((request, response) => {
    response.setHeader('Cache-Control', 'no-store');
    const url = new URL(request.url, 'http://localhost');
    if (url.pathname !== '/player_api.php') {
      mediaRequests++;
      const name = path.basename(url.pathname);
      if (name === '2.m3u8' || /^sample\d+\.ts$/.test(name)) {
        response.setHeader('Content-Type', name.endsWith('.m3u8') ? 'application/vnd.apple.mpegurl' : 'video/mp2t');
        return fs.createReadStream(path.join(directory, name === '2.m3u8' ? 'sample.m3u8' : name)).pipe(response);
      }
      const size = fs.statSync(media).size;
      const range = /^bytes=(\d+)-(\d*)$/.exec(request.headers.range || '');
      const start = range ? Number(range[1]) : 0;
      const end = range?.[2] ? Math.min(Number(range[2]), size - 1) : size - 1;
      response.writeHead(range ? 206 : 200, { 'Content-Type': 'video/mp4', 'Accept-Ranges': 'bytes',
        'Content-Length': end - start + 1, ...(range ? { 'Content-Range': `bytes ${start}-${end}/${size}` } : {}) });
      return fs.createReadStream(media, { start, end }).pipe(response);
    }
    const action = url.searchParams.get('action');
    let result = { user_info: { auth: 1 } };
    if (action?.endsWith('_categories')) result = [{ category_id: '1', category_name: 'Fixture' }];
    else if (action === 'get_live_streams') result = [{ stream_id: 1, name: 'Live fixture', category_id: '1' }];
    else if (action === 'get_vod_streams') result = [
      { stream_id: 1, name: 'Movie fixture', container_extension: 'mp4', category_id: '1' },
      { stream_id: 2, name: 'HLS fixture', container_extension: 'm3u8', category_id: '1' },
    ];
    else if (action === 'get_series') result = [{ series_id: 1, name: 'Series fixture', category_id: '1' }];
    else if (action === 'get_series_info') result = { episodes: { 1: [1, 2].map(id => ({
      id, title: `Episode ${id}`, episode_num: id, container_extension: 'mp4',
    })) } };
    response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify(result));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let application;
  try {
    const options = { executablePath: require('electron'), args: [path.join(__dirname, 'smoke-main.cjs'), `--user-data-dir=${directory}`],
      env: { ...process.env, TEST_XTREAM_URL: `http://127.0.0.1:${server.address().port}` } };
    const launch = async () => {
      application = await electron.launch(options);
      const page = await application.firstWindow();
      page.setDefaultTimeout(15000);
      await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].hide());
      await page.getByLabel('License key', { exact: true }).fill('TEST-LICENSE');
      await page.getByRole('button', { name: 'Activate', exact: true }).click();
      await page.locator('.home-screen').waitFor();
      return page;
    };
    let page = await launch();
    const openMovie = async () => { await page.locator('.home-tile').nth(1).click(); await page.getByText('Movie fixture', { exact: true }).click(); };
    const checkpoint = async position => {
      await page.waitForFunction(() => document.querySelector('video')?.currentTime > 0);
      await page.locator('video').evaluate((video, position) => { video.currentTime = position; }, position);
      await page.waitForFunction(position => { const video = document.querySelector('video'); return !video.seeking && video.currentTime >= position; }, position);
      await page.locator('video').evaluate(video => video.pause());
    };
    await openMovie();
    await checkpoint(24);
    await page.locator('.player-top .home-btn').click();
    await application.close(); application = null;
    page = await launch();
    const before = mediaRequests;
    await openMovie();
    await page.getByRole('dialog', { name: 'Continue watching' }).waitFor();
    assert.equal(await page.locator('video').evaluate(video => video.currentSrc), '', 'No stream loads before the resume choice');
    assert.equal(mediaRequests, before);
    assert.equal(await page.locator('.resume-primary').evaluate(el => document.activeElement === el), true, 'Resume receives initial keyboard focus');
    await page.getByRole('button', { name: 'Resume watching', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('video')?.currentTime >= 24);
    await checkpoint(32);
    await page.locator('.player-top .home-btn').click();
    await openMovie();
    await page.getByRole('dialog').waitFor();
    await page.keyboard.press('Escape');
    await page.locator('.home-btn').click();
    await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].showInactive());
    for (const [design, lang, width] of [['classic', 'en', 1280], ['modern', 'ar', 820], ['studio', 'ar', 1280]]) {
      await page.locator('.appearance-open').click();
      await page.locator(`input[name="design"][value="${design}"]`).check();
      await page.keyboard.press('Escape');
      if (await page.locator('html').getAttribute('lang') !== lang) await page.locator('.home-screen .lang-toggle').click();
      await page.setViewportSize({ width, height: 720 });
      await openMovie();
      await page.getByRole('dialog').waitFor();
      assert.match(await page.locator('#resume-title').innerText(), lang === 'ar' ? /متابعة المشاهدة/ : /Continue watching/);
      assert.equal(await page.locator('.resume-dialog').evaluate(el => el.scrollWidth <= el.clientWidth), true);
      await page.screenshot({ path: path.join(directory, `resume-${design}-${lang}.png`), timeout: 30000 });
      await page.keyboard.press('Escape');
      await page.locator('.home-btn').click();
    }
    await page.locator('.home-screen .lang-toggle').click();
    await openMovie();
    await page.getByRole('button', { name: 'Start over', exact: true }).click();
    await page.waitForFunction(() => { const video = document.querySelector('video'); return video.currentTime > 0 && video.currentTime < 5; });
    await checkpoint(18);
    // Exercise the media-error boundary without Chromium's same-URL media cache hiding a server failure.
    await page.locator('video').evaluate(video => video.dispatchEvent(new Event('error')));
    await page.locator('.retry-playback').waitFor();
    await page.locator('.retry-playback').click();
    await page.waitForFunction(() => document.querySelector('video')?.currentTime >= 18);
    await page.getByRole('button', { name: 'Home', exact: true }).click();
    await openMovie();
    await page.keyboard.press('Escape');
    await page.locator('.channels-body').waitFor();
    await page.locator('.home-btn').click();
    await page.locator('.appearance-open').click();
    await page.getByLabel('Remember movie and episode playback position').uncheck();
    await page.keyboard.press('Escape');
    await page.reload();
    await page.locator('.home-screen').waitFor();
    await openMovie();
    await page.waitForFunction(() => document.querySelector('video')?.currentTime > 0);
    assert.equal(await page.getByRole('dialog').count(), 0);
    await page.getByRole('button', { name: 'Home', exact: true }).click();
    await page.locator('.appearance-open').click();
    await page.getByLabel('Remember movie and episode playback position').check();
    await page.keyboard.press('Escape');
    await page.locator('.home-tile').nth(2).click();
    await page.getByText('Series fixture', { exact: true }).click();
    await page.getByText('Episode 1', { exact: true }).click();
    await checkpoint(15);
    await page.getByRole('button', { name: 'Back', exact: true }).click();
    await page.getByText('Episode 2', { exact: true }).click();
    await page.waitForFunction(() => document.querySelector('video')?.currentTime > 0);
    assert.equal(await page.getByRole('dialog').count(), 0, 'Each episode has its own position');
    await page.getByRole('button', { name: 'Back', exact: true }).click();
    await page.getByText('Episode 1', { exact: true }).click();
    await page.getByRole('button', { name: 'Resume watching', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('video')?.currentTime >= 15);
    await page.locator('video').evaluate(video => { video.currentTime = video.duration - 0.2; });
    await page.waitForFunction(() => document.querySelector('video')?.ended);
    await page.getByRole('button', { name: 'Back', exact: true }).click();
    await page.getByText('Episode 1', { exact: true }).click();
    await page.waitForFunction(() => document.querySelector('video')?.currentTime > 0);
    assert.equal(await page.getByRole('dialog').count(), 0, 'Completed content starts again');
    await page.getByRole('button', { name: 'Home', exact: true }).click();
    await page.locator('.home-tile').nth(1).click();
    await page.getByText('HLS fixture', { exact: true }).click();
    await checkpoint(22);
    await page.getByRole('button', { name: 'Home', exact: true }).click();
    await page.locator('.home-tile').nth(1).click();
    await page.getByText('HLS fixture', { exact: true }).click();
    await page.getByRole('button', { name: 'Resume watching', exact: true }).click();
    await page.waitForFunction(() => { const video = document.querySelector('video'); return video.currentTime >= 22 && video.getVideoPlaybackQuality().totalVideoFrames > 5; });
    console.log('PASS: saved movie after app restart, resume, start over, retry, cancel, persisted opt-out, per-episode progress, completion, HLS seek and decoded frames. Screenshots:', directory);
  } finally {
    if (application) await application.close();
    await new Promise(resolve => server.close(resolve));
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
