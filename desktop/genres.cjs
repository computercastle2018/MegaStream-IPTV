// Mixed-language provider groups, without using real subscriptions or streams.
const { _electron: electron } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
(async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'megastream-genres-'));
  const groups = ['NEWS - الأخبار', 'أخبار عربية', 'BEIN SPORTS HD', 'رياضة عربية', 'UK Movies', 'مسلسلات', 'وثائقيات', 'KSA - السعودية'];
  const server = http.createServer((request, response) => {
    const action = new URL(request.url, 'http://localhost').searchParams.get('action');
    const result = !action ? { user_info: { auth: 1 } }
      : action.endsWith('_categories') ? groups.map((category_name, index) => ({ category_id: String(index), category_name }))
      : groups.map((_, index) => ({ stream_id: index + 1, name: `Channel ${index}`, category_id: String(index) }));
    response.setHeader('Content-Type', 'application/json');
    response.end(JSON.stringify(result));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let application;
  try {
    application = await electron.launch({ executablePath: require('electron'), args: [path.join(__dirname, 'smoke-main.cjs'), `--user-data-dir=${directory}`],
      env: { ...process.env, TEST_XTREAM_URL: `http://127.0.0.1:${server.address().port}` } });
    const page = await application.firstWindow();
    page.setDefaultTimeout(15000);
    await page.getByLabel('License key', { exact: true }).fill('TEST-LICENSE');
    await page.getByRole('button', { name: 'Activate', exact: true }).click();
    await page.locator('.tile-live').click();
    await page.locator('.channel-card').first().waitFor();
    for (const [genre, names] of [['news', ['Channel 0', 'Channel 1']], ['sports', ['Channel 2', 'Channel 3']],
      ['movies', ['Channel 4']], ['series', ['Channel 5']], ['documentary', ['Channel 6']], ['other', ['Channel 7']]]) {
      await page.locator('.genre-select').selectOption(genre);
      assert.deepEqual(await page.locator('.channel-card .name').allTextContents(), names);
      assert.equal(await page.locator('.category-item').count(), names.length + 1);
    }
    await page.locator('.genre-select').selectOption('sports');
    await page.locator('.channel-card').first().locator('.favorite-button').click();
    await page.locator('.favorites-toggle').click();
    assert.equal(await page.locator('.channel-card').count(), 1);
    await page.locator('.home-btn').click();
    await page.locator('.tile-live').click();
    await page.locator('.channel-card').first().waitFor();
    assert.equal(await page.locator('.genre-select').inputValue(), 'sports');
    assert.equal(await page.locator('.channel-card').count(), 1, 'Genre and favorites survive navigation');
    await page.locator('.favorites-toggle').click();
    await page.locator('.genre-select').selectOption('all');
    assert.equal(await page.locator('.channel-card').count(), groups.length);
    console.log('PASS: News/Sports/Movies/Series/Documentaries/Other classify Arabic and English groups, preserve provider groups, and combine with favorites/navigation.');
  } finally {
    if (application) await application.close();
    await new Promise(resolve => server.close(resolve));
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
