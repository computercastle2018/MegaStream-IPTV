// Local codec fixtures only; no production subscriptions or streams.
const { _electron: electron } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

(async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'megastream-codecs-'));
  const encode = args => {
    const result = spawnSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', ...args], { timeout: 30000 });
    assert.equal(result.status, 0, 'FFmpeg must generate the local codec fixture');
  };
  for (const [name, size, encoder] of [
    ['megastream-hd-h264', '1920x1080', ['-c:v', 'libx264', '-preset', 'ultrafast', '-pix_fmt', 'yuv420p']],
    ['megastream-4k-hevc', '3840x2160', ['-c:v', 'hevc_videotoolbox', '-tag:v', 'hvc1', '-b:v', '8M']],
    ['megastream-4k-hevc10', '3840x2160', ['-c:v', 'hevc_videotoolbox', '-pix_fmt', 'p010le', '-profile:v', 'main10', '-tag:v', 'hvc1', '-b:v', '8M']],
  ]) {
    const input = path.join(directory, name + '.mp4');
    encode(['-f', 'lavfi', '-i', `testsrc2=size=${size}:rate=25`, '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000',
      '-t', '4', ...encoder, '-c:a', 'aac', '-movflags', '+faststart', input]);
    encode(['-i', input, '-c', 'copy', '-f', 'mpegts', path.join(directory, name + '.ts')]);
    encode(['-i', input, '-c', 'copy', '-hls_time', '2', '-hls_segment_filename', path.join(directory, name + '-%03d.ts'), path.join(directory, name + '.m3u8')]);
  }
  const files = ['megastream-hd-h264.mp4', 'megastream-4k-hevc.mp4', 'megastream-hd-h264.ts', 'megastream-4k-hevc.ts',
    'megastream-hd-h264.m3u8', 'megastream-4k-hevc.m3u8', 'megastream-4k-hevc10.m3u8', 'megastream-4k-redirect.m3u8'];
  const server = http.createServer((request, response) => {
    const name = new URL(request.url, 'http://localhost').pathname.slice(1);
    if (name === 'megastream-4k-redirect.m3u8') {
      response.writeHead(302, { Location: `http://localhost:${server.address().port}/megastream-4k-hevc.m3u8` });
      response.end();
      return;
    }
    if (files.includes(name) || /^megastream-(?:4k-hevc(?:10)?|hd-h264)-\d{3}\.ts$/.test(name)) {
      response.setHeader('Content-Type', name.endsWith('.m3u8') ? 'application/vnd.apple.mpegurl' : name.endsWith('.ts') ? 'video/mp2t' : 'video/mp4');
      fs.createReadStream(path.join(directory, name)).pipe(response);
    } else {
      response.end('#EXTM3U\n' + files.map(name => `#EXTINF:-1 group-title="Video",${name}\nhttp://127.0.0.1:${server.address().port}/${name}\n`).join(''));
    }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let application;
  try {
    application = await electron.launch({ executablePath: require('electron'), args: [path.join(__dirname, 'smoke-main.cjs'), `--user-data-dir=${directory}`],
      env: { ...process.env, TEST_PLAYLIST_URL: `http://127.0.0.1:${server.address().port}/playlist.m3u` } });
    const page = await application.firstWindow();
    await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].hide());
    await page.locator('#desktop-license-key').fill('TEST-LICENSE');
    await page.locator('.license-key-row button').click();
    await page.locator('.home-screen').waitFor();
    for (const name of files) {
      await page.locator('.home-tile').nth(name.endsWith('.mp4') ? 1 : 0).click();
      await page.getByText(name, { exact: true }).click();
      await page.locator('video').waitFor();
      await page.waitForFunction(() => document.querySelector('video')?.currentTime > 1, undefined, { timeout: 10000 });
      const result = await page.locator('video').evaluate(video => ({ width: video.videoWidth, height: video.videoHeight,
        frames: video.getVideoPlaybackQuality().totalVideoFrames, nativeHls: video.canPlayType('application/vnd.apple.mpegurl'), error: video.error?.code }));
      const screenshot = await page.locator('video').screenshot();
      const decoded = spawnSync('ffmpeg', ['-loglevel', 'error', '-i', 'pipe:0', '-vf', 'crop=iw/2:ih/2:iw/4:ih/4,scale=64:36', '-f', 'rawvideo', '-pix_fmt', 'rgb24', 'pipe:1'], { input: screenshot });
      assert.equal(decoded.status, 0);
      result.pixelRange = Math.max(...decoded.stdout) - Math.min(...decoded.stdout);
      console.log(name, JSON.stringify(result));
      assert.equal(result.height, name.includes('4k') ? 2160 : 1080);
      assert.ok(result.frames > 0 && result.pixelRange > 20, 'Video must have decoded, nonblank frames, not audio only');
      await page.screenshot({ path: path.join(directory, name + '.png') });
      await page.keyboard.press('Escape');
      await page.locator('.home-btn').click();
    }
    console.log('PASS: HD H264 and 4K HEVC show decoded nonblank video. Screenshots:', directory);
  } finally {
    if (application) await application.close();
    await new Promise(resolve => server.close(resolve));
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
