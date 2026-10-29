// Opt-in runtime diagnostic using an encrypted copy of the existing profile.
// Never logs subscription URLs, headers, credentials, or provider responses.
const { _electron: electron } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
(async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'megastream-live-check-'));
  fs.copyFileSync(path.join(os.homedir(), 'Library/Application Support/megastream-desktop/providers.encrypted'), path.join(directory, 'providers.encrypted'));
  let application;
  let stage = 'launch';
  try {
    application = await electron.launch({ executablePath: require('electron'), args: [path.join(__dirname, 'live-main.cjs')], env: { ...process.env, TEST_PROFILE_PATH: directory } });
    const page = await application.firstWindow();
    await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].hide());
    let imageFailures = 0;
    page.on('requestfailed', request => {
      if (/\.m3u8|\.ts(?:\?|$)/i.test(request.url())) console.log('STREAM_NETWORK', request.failure()?.errorText);
      if (request.resourceType() === 'image' && imageFailures++ < 8) console.log('IMAGE_NETWORK', request.failure()?.errorText);
    });
    page.on('response', response => {
      if (/\.m3u8(?:\?|$)/i.test(response.url())) console.log('MANIFEST_STATUS', response.status());
    });
    stage = 'license';
    await page.locator('.home-screen').waitFor({ timeout: 30000 });
    console.log('REMOTE_APPEARANCE', (await page.evaluate(() => window.megastreamDesktop.control.state())).uiStyle ?? 'local');
    stage = 'browse';
    await page.locator('.tile-live').click();
    await page.locator('.channel-card').first().waitFor({ timeout: 30000 });
    stage = 'channel';
    let channelNames;
    if (process.env.TEST_CATEGORY) {
      await page.locator('.category-item').filter({ has: page.getByText(process.env.TEST_CATEGORY, { exact: true }) }).click();
      const names = await page.locator('.channel-card .name').allTextContents();
      channelNames = process.env.TEST_SAUDI_PRIMARY
        ? [names.find(name => /Quran|قرآن/i.test(name)), names.find(name => /Sunnah|سنة/i.test(name)), names.find(name => /Saudi.*1|KSA.*1/i.test(name))].filter(Boolean)
        : names.slice(0, 3);
      if (!channelNames.length) throw new Error('No matching channels in this group');
    } else {
      channelNames = [process.env.TEST_CHANNEL_NAME || 'BEIN SPORTS 1 4k'];
      await page.locator('.search-toggle').click();
      await page.locator('input[type=search]').fill(channelNames[0]);
    }
    for (const [index, channelName] of channelNames.entries()) {
    const card = page.locator('.channel-card').filter({ has: page.getByText(channelName, { exact: true }) }).first();
    await card.waitFor();
    console.log('LOGO_COUNT', await card.locator('img').count());
    if (await card.locator('img').count()) console.log('LOGO_STATE', JSON.stringify(await card.locator('img').evaluate(image => ({ loaded: image.complete && image.naturalWidth > 0 }))));
    stage = 'playback';
    await card.click();
    await page.waitForFunction(() => document.querySelector('video')?.videoWidth > 0 || document.querySelector('.center-msg.error'), undefined, { timeout: 30000 });
    await page.waitForFunction(() => document.querySelector('video')?.getVideoPlaybackQuality().totalVideoFrames > 10, undefined, { timeout: 15000 }).catch(() => {});
    const result = await page.locator('video').evaluate(video => ({ width: video.videoWidth, height: video.videoHeight, frames: video.getVideoPlaybackQuality().totalVideoFrames }));
    console.log('VIDEO_STATE', JSON.stringify({ channel: channelName, ...result }));
    if (result.frames <= 10) process.exitCode = 1;
    const message = await page.locator('.center-msg.error').count() ? await page.locator('.center-msg.error').textContent() : '';
    console.log('PLAYER_RESULT', /manifestLoadError/.test(message) ? 'manifestLoadError' : /error|failed/i.test(message) ? 'failed' : 'video decoded');
    await page.screenshot({ path: path.join(directory, `playback-${index}.png`) });
    console.log('SCREENSHOT', path.join(directory, `playback-${index}.png`));
    await page.keyboard.press('Escape');
    await page.locator('.channel-card').first().waitFor();
    }
  } catch { console.log('DIAGNOSTIC_STAGE', stage); throw new Error('Live check failed'); }
  finally { if (application) await application.close(); }
})().catch(() => { console.error('Live diagnostic failed without exposing connection data'); process.exitCode = 1; });
