// Isolated navigation regression test; never uses production accounts or media.
const { _electron: electron } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

(async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'megastream-navigation-'));
  const server = http.createServer((request, response) => {
    if (request.url === '/images/valid.png') {
      response.setHeader('Content-Type', 'image/png');
      response.end(fs.readFileSync(path.join(__dirname, '../webos/store/icon-512.png')));
      return;
    }
    if (request.url === '/images/missing.png') { response.writeHead(404); response.end(); return; }
    if (request.url.startsWith('/player_api.php')) {
      const action = new URL(request.url, 'http://localhost').searchParams.get('action');
      const groups = ['News', 'Sports', ...Array.from({ length: 30 }, (_, index) => `Group ${index}`), 'Long group'];
      const categories = names => names.map((category_name, index) => ({ category_id: String(index), category_name }));
      const results = {
        get_live_categories: categories(groups),
        get_live_streams: [...['News One', 'Sports One', 'Sports Two', ...groups.slice(2).map((_, index) => `Extra ${index}`)].map((name, index) => ({ stream_id: index + 1, name, category_id: String(index < 1 ? 0 : index < 3 ? 1 : index - 1),
          stream_icon: index < 2 ? '/images/valid.png' : '/images/missing.png' })),
          ...Array.from({ length: 50 }, (_, index) => ({ stream_id: 100 + index, name: `Long ${index}`, category_id: '32' }))],
        get_vod_categories: categories(['Cinema A', 'Cinema B', 'Long movies']),
        get_vod_streams: [...['Movie One', 'Movie Two'].map((name, index) => ({ stream_id: index + 1, name, category_id: String(index), stream_icon: '/images/valid.png' })),
          ...Array.from({ length: 100 }, (_, index) => ({ stream_id: 100 + index, name: `Long movie ${index}`, category_id: '2' }))],
        get_series_categories: categories(['Drama', 'Comedy', 'Long series']),
        get_series: [...['Series One', 'Series Two'].map((name, index) => ({ series_id: index + 1, name, category_id: String(index), cover: '/images/valid.png' })),
          ...Array.from({ length: 100 }, (_, index) => ({ series_id: 100 + index, name: `Long series ${index}`, category_id: '2' }))],
        get_series_info: { episodes: { '1': [{ id: '1', title: 'Episode One', season: 1, episode_num: 1 }] } },
      };
      response.setHeader('Content-Type', 'application/json');
      response.end(JSON.stringify(action ? results[action] ?? [] : { user_info: { auth: 1, status: 'Active' } }));
      return;
    }
    const extra = Array.from({ length: 30 }, (_, index) => `#EXTINF:-1 group-title="Group ${index}",Extra ${index}\nhttp://127.0.0.1:${server.address().port}/extra${index}.ts\n`).join('');
    response.end(`#EXTM3U\n#EXTINF:-1 group-title="News",News One\nhttp://127.0.0.1:${server.address().port}/one.ts\n#EXTINF:-1 group-title="Sports",Sports One\nhttp://127.0.0.1:${server.address().port}/two.ts\n#EXTINF:-1 group-title="Sports",Sports Two\nhttp://127.0.0.1:${server.address().port}/three.ts\n${extra}#EXTINF:-1 group-title="Cinema A",Movie One\nhttp://127.0.0.1:${server.address().port}/one.mp4\n#EXTINF:-1 group-title="Cinema B",Movie Two\nhttp://127.0.0.1:${server.address().port}/two.mp4\n`);
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let application;
  try {
    application = await electron.launch({ executablePath: require('electron'), args: [path.join(__dirname, 'smoke-main.cjs'), `--user-data-dir=${directory}`],
      env: { ...process.env, TEST_ALLOW_DETAILS: '1', TEST_PLAYLIST_URL: `http://127.0.0.1:${server.address().port}/playlist.m3u`,
        ...(process.env.TEST_XTREAM ? { TEST_XTREAM_URL: `http://127.0.0.1:${server.address().port}` } : {}) } });
    const page = await application.firstWindow();
    page.setDefaultTimeout(10000);
    await page.locator('#desktop-license-key').fill('TEST-LICENSE');
    await page.locator('.license-key-row button').click();
    await page.waitForSelector('.home-screen');
    if (await page.locator('html').getAttribute('lang') !== 'en') await page.locator('.lang-toggle').click();
    await page.locator('.tile-live').click();
    await page.locator('.channel-card').first().waitFor();
    assert.equal(await page.locator('.home-btn').count(), 1, 'Browse must provide a dedicated Home button');
    const header = await page.locator('.app-header').boundingBox();
    await page.locator('.category-item').filter({ hasText: 'Group 29' }).scrollIntoViewIfNeeded();
    assert.equal((await page.locator('.app-header').boundingBox()).y, header.y, 'Groups scrolling must not move the section tabs');
    await page.locator('.channel-card').last().scrollIntoViewIfNeeded();
    assert.equal((await page.locator('.app-header').boundingBox()).y, header.y, 'Content scrolling must not move the section tabs');
    if (process.env.TEST_XTREAM) {
      await page.locator('.category-item').filter({ hasText: 'Long group' }).click();
      assert.equal(await page.locator('.content-grid').evaluate(grid => grid.scrollTop), 0, 'Switching groups must start the new channel list at the top');
    }
    await page.locator('.search-toggle').click();
    const search = page.locator('input[type=search]');
    await search.fill('Sports');
    await search.press('Backspace');
    assert.equal(await search.inputValue(), 'Sport', 'Backspace must edit text, not navigate');
    await search.press('Escape');
    await page.locator('.search-toggle').waitFor();
    await page.locator('.category-item').filter({ hasText: 'Sports' }).click();
    assert.equal(await page.locator('.category-item.focused .cat-name').textContent(), 'Sports', 'Selecting a group must keep focus in the groups rail');
    if (process.env.TEST_XTREAM) {
      await page.waitForFunction(() => document.querySelector('.channel-card img')?.naturalWidth > 0, undefined, { timeout: 5000 });
      await page.locator('.channel-card').filter({ hasText: 'Sports Two' }).locator('[data-image-fallback]').waitFor();
    }
    await page.getByText('Sports Two', { exact: true }).click();
    await page.locator('.player-screen').waitFor();
    assert.equal(await page.locator('.home-btn').count(), 1, 'Player must provide a dedicated Home button');
    await page.keyboard.press('Escape');
    await page.locator('.channel-card').first().waitFor();
    assert.equal(await page.locator('.category-item.active .cat-name').textContent(), 'Sports', 'Return must preserve category');
    assert.equal(await page.locator('.channel-card.focused .name').textContent(), 'Sports Two', 'Return must restore selected channel focus');
    await page.screenshot({ path: path.join(directory, 'return.png') });
    await page.keyboard.press('ArrowLeft');
    await page.waitForFunction(() => document.querySelector('.channel-card.focused .name')?.textContent !== 'Sports Two');
    assert.notEqual(await page.locator('.channel-card.focused .name').textContent(), 'Sports Two', 'Arrow must move to the previous channel');
    await page.locator('.lang-toggle').click();
    await page.locator('.channel-card').filter({ hasText: 'Sports Two' }).focus();
    await page.keyboard.press('ArrowRight');
    await page.waitForFunction(() => document.querySelector('.channel-card.focused .name')?.textContent === 'Sports One');
    await page.screenshot({ path: path.join(directory, 'navigation.png') });
    await page.locator('.lang-toggle').click();
    await page.locator('.type-tab').filter({ hasText: 'Movies' }).click();
    await page.locator('.poster-card').first().waitFor();
    if (process.env.TEST_XTREAM) {
      await page.locator('.poster-card').last().scrollIntoViewIfNeeded();
      await page.locator('.category-item').filter({ hasText: 'Long movies' }).click();
      assert.equal(await page.locator('.content-grid').evaluate(grid => grid.scrollTop), 0, 'Movies group must reset scrolling');
    }
    await page.locator('.category-item').filter({ hasText: 'Cinema B' }).click();
    assert.equal(await page.locator('.poster-name').textContent(), 'Movie Two');
    await page.keyboard.press('ArrowUp');
    await page.keyboard.press('Enter');
    await page.waitForFunction(() => document.querySelector('.poster-name')?.textContent === 'Movie One');
    assert.equal(await page.locator('.type-tab.active').textContent(), 'Movies', 'Group navigation must not switch content type');
    if (process.env.TEST_XTREAM) {
      await page.locator('.type-tab').filter({ hasText: 'Series' }).click();
      await page.locator('.poster-card').first().waitFor();
      await page.locator('.poster-card').last().scrollIntoViewIfNeeded();
      await page.locator('.category-item').filter({ hasText: 'Long series' }).click();
      assert.equal(await page.locator('.content-grid').evaluate(grid => grid.scrollTop), 0, 'Series group must reset scrolling');
      await page.locator('.category-item').filter({ hasText: 'Comedy' }).click();
      await page.keyboard.press('ArrowUp');
      await page.keyboard.press('Enter');
      await page.waitForFunction(() => document.querySelector('.poster-name')?.textContent === 'Series One');
      assert.equal(await page.locator('.type-tab.active').textContent(), 'Series');
      await page.getByText('Series One', { exact: true }).click();
      await page.locator('.episodes-screen').waitFor();
      await page.locator('.home-btn').click();
      await page.locator('.home-screen').waitFor();
      await page.locator('.tile-live').click();
    } else {
    await page.locator('.type-tab').filter({ hasText: 'Live' }).click();
    }
    await page.locator('.channel-card').first().waitFor();
    assert.equal(await page.locator('.category-item.active .cat-name').textContent(), 'Sports', 'Each section must retain its own group');
    await page.locator('.home-btn').click();
    await page.locator('.home-screen').waitFor();
    await page.keyboard.press('Backspace');
    assert.equal(await page.locator('.home-screen').count(), 1, 'Home Backspace must not open connection setup');
    assert.equal(await page.locator('.desktop-license-modal').count(), 0);
    await page.getByRole('button', { name: 'Change playlist' }).click();
    const address = page.getByPlaceholder('http://host:port');
    await address.fill('http://localhost');
    await address.press('Backspace');
    assert.equal(await address.inputValue(), 'http://localhos', 'Connection fields must allow normal editing');
    await address.press('ArrowLeft');
    assert.equal(await address.evaluate(input => document.activeElement === input), true, 'Arrow keys must edit text without moving spatial focus');
    await address.press('Escape');
    await page.locator('.home-screen').waitFor();
    console.log('PASS: text editing, search close, category and channel restoration, arrow navigation, home back. Screenshot:', path.join(directory, 'navigation.png'));
  } finally {
    if (application) await application.close();
    await new Promise(resolve => server.close(resolve));
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
