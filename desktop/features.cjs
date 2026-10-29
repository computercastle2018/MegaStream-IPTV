// Isolated catalog/favorites/sort/sync checks; never uses a real subscription.
const { _electron: electron } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
(async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'megastream-features-'));
  const revisions = { live: 0, movie: 0, series: 0 };
  const requests = { live: 0, movie: 0, series: 0 };
  const categories = ['Zulu', 'Alpha', 'Group 10', 'Group 2'];
  const server = http.createServer((request, response) => {
    const action = new URL(request.url, 'http://localhost').searchParams.get('action') || '';
    const type = action.includes('vod') ? 'movie' : action.includes('series') ? 'series' : 'live';
    let result = { user_info: { auth: 1 } };
    if (action.endsWith('_categories')) result = categories.map((category_name, index) => ({ category_id: String(index), category_name }));
    else if (action) {
      requests[type]++;
      result = categories.map((name, index) => ({ stream_id: index + 1, series_id: index + 1, name: `${type} ${name}`, category_id: String(index) }));
      if (revisions[type]) result.push({ stream_id: 100, series_id: 100, name: `Fresh ${type}`, category_id: '0' });
    }
    response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify(result));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let application;
  try {
    application = await electron.launch({ executablePath: require('electron'), args: [path.join(__dirname, 'smoke-main.cjs'), `--user-data-dir=${directory}`],
      env: { ...process.env, TEST_XTREAM_URL: `http://127.0.0.1:${server.address().port}` } });
    const page = await application.firstWindow();
    await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].showInactive());
    page.setDefaultTimeout(10000);
    await page.locator('#desktop-license-key').fill('TEST-LICENSE');
    await page.locator('.license-key-row button').click();
    for (const design of ['classic', 'modern', 'studio']) {
      await page.locator('.appearance-open').click();
      await page.locator(`input[name=design][value=${design}]`).check();
      await page.locator('.appearance-close').click();
      assert.equal(await page.locator('html').getAttribute('data-design'), design);
      await page.screenshot({ path: path.join(directory, `${design}-home.png`) });
      await page.locator('.tile-live').click();
      await page.locator('.channel-card').first().waitFor();
      for (const index of [1, 2, 0]) {
        await page.locator('.type-tab').nth(index).click();
        await page.locator('.content-grid [role=button]').first().waitFor();
      }
      await page.screenshot({ path: path.join(directory, `${design}-browse.png`) });
      await page.setViewportSize({ width: 820, height: 600 });
      await page.screenshot({ path: path.join(directory, `${design}-compact.png`) });
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
      await page.locator('.home-btn').click();
      await page.setViewportSize({ width: 1280, height: 800 });
    }
    await page.locator('.home-meta .lang-toggle').click();
    await page.locator('.appearance-open').click();
    await page.locator('input[name=design][value=modern]').check();
    await page.keyboard.press('Escape');
    assert.equal(await page.locator('dialog').count(), 0);
    await page.screenshot({ path: path.join(directory, 'arabic-modern-home.png') });
    await page.locator('.tile-live').click();
    await page.locator('.channel-card').first().waitFor();
    await page.setViewportSize({ width: 820, height: 600 });
    await page.screenshot({ path: path.join(directory, 'arabic-modern-compact.png') });
    await page.locator('.home-btn').click();
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.locator('.home-meta .lang-toggle').click();
    await page.locator('.appearance-open').click();
    await page.locator('input[name=design][value=studio]').check();
    await page.locator('.appearance-close').click();
    await application.evaluate(() => { global.testUiStyle = 'modern'; });
    await page.waitForFunction(() => document.documentElement.dataset.design === 'modern', null, { timeout: 12000 });
    await page.locator('.appearance-open').click();
    assert.equal(await page.locator('input[name=design]').first().isDisabled(), true);
    await page.locator('.appearance-close').click();
    await application.evaluate(() => { global.testUiStyle = null; });
    await page.waitForFunction(() => document.documentElement.dataset.design === 'studio', null, { timeout: 12000 });
    await page.reload();
    await page.waitForFunction(() => document.documentElement.dataset.design === 'studio');
    await page.locator('.appearance-open').click();
    await page.locator('input[name=design][value=classic]').check();
    await page.locator('.appearance-close').click();
    await page.locator('.tile-live').click();
    await page.locator('.channel-card').first().waitFor();
    assert.deepEqual((await page.locator('.cat-name').allTextContents()).slice(1), categories);
    await page.locator('.group-sort').selectOption('asc');
    assert.deepEqual((await page.locator('.cat-name').allTextContents()).slice(1), ['Alpha', 'Group 2', 'Group 10', 'Zulu']);
    await page.locator('.group-sort').selectOption('desc');
    assert.deepEqual((await page.locator('.cat-name').allTextContents()).slice(1), ['Zulu', 'Group 10', 'Group 2', 'Alpha']);
    await page.locator('.group-sort').selectOption('asc');
    const favorite = name => page.locator('.channel-card').filter({ hasText: name }).locator('.favorite-button');
    await favorite('live Zulu').click();
    await favorite('live Alpha').focus();
    await page.keyboard.press('Enter');
    assert.equal(await page.locator('.player-screen').count(), 0, 'Favorite activation must not start playback');
    await page.locator('.favorites-toggle').click();
    assert.equal(await page.locator('.channel-card').count(), 2);
    await page.reload();
    await page.locator('.tile-live').click();
    await page.locator('.channel-card').first().waitFor();
    assert.equal(await page.locator('.group-sort').inputValue(), 'asc');
    await page.locator('.favorites-toggle').click();
    assert.equal(await page.locator('.channel-card').count(), 2, 'Favorites must survive reload');
    await favorite('live Zulu').click();
    assert.equal(await page.locator('.channel-card').count(), 1);
    await page.locator('.favorites-toggle').click();
    for (const [index, type] of ['live', 'movie', 'series'].entries()) {
      if (index) await page.locator('.type-tab').nth(index).click();
      await page.locator('.content-grid [role=button]').first().waitFor();
      revisions[type] = 1;
      await page.locator('.sync-current').click();
      await page.getByText(`Fresh ${type}`, { exact: true }).waitFor();
    }
    const before = { ...requests };
    await page.locator('.home-btn').click();
    await page.locator('.sync-all').click();
    await page.getByRole('status').filter({ hasText: 'Sync complete' }).waitFor();
    for (const type of ['live', 'movie', 'series']) assert.ok(requests[type] > before[type], `Global sync must refresh ${type}`);
    await page.locator('.tile-live').click();
    await page.locator('.channel-card').first().waitFor();
    await page.screenshot({ path: path.join(directory, 'desktop.png') });
    await page.setViewportSize({ width: 820, height: 600 });
    await page.screenshot({ path: path.join(directory, 'compact.png') });
    console.log('PASS: provider/A-Z/Z-A group order, persisted favorites, keyboard star, per-section force refresh and global catalog sync. Screenshots:', directory);
  } finally {
    if (application) await application.close();
    await new Promise(resolve => server.close(resolve));
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
