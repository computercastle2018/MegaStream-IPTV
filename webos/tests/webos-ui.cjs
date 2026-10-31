const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

(async () => {
  const root = path.resolve(__dirname, '../dist');
  const pair = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  const jwk = { ...pair.publicKey.export({ format: 'jwk' }), kid: 'ui-fixture' };
  let device, redeemed = false, style = 'studio', notices = [];
  const registrations = [];
  const server = http.createServer((request, response) => {
    const route = new URL(request.url, 'http://localhost').pathname;
    if (route === '/fixture.m3u') {
      response.setHeader('Access-Control-Allow-Origin', '*');
      return response.end('#EXTM3U\n#EXTINF:-1 group-title="News",Fixture News\nhttps://example.com/legal.ts\n#EXTINF:-1 group-title="Sports",Fixture Sports\nhttps://example.com/legal2.ts');
    }
    const file = path.join(root, route === '/' ? 'index.html' : route);
    if (!file.startsWith(root + path.sep) || !fs.existsSync(file)) { response.statusCode = 404; return response.end(); }
    response.setHeader('Content-Type', file.endsWith('.js') ? 'text/javascript' : file.endsWith('.css') ? 'text/css' : 'text/html');
    fs.createReadStream(file).pipe(response);
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let sourceServer;
  let baseUrl = `http://127.0.0.1:${server.address().port}`;
  if (process.env.WEBOS_UI_SOURCE === '1') {
    const { createServer } = await import(require('node:url').pathToFileURL(path.resolve(__dirname, '../node_modules/vite/dist/node/index.js')).href);
    sourceServer = await createServer({ root: path.resolve(__dirname, '..'), server: { host: '127.0.0.1', port: 0 }, logLevel: 'error' });
    await sourceServer.listen();
    baseUrl = `http://127.0.0.1:${sourceServer.httpServer.address().port}`;
  }
  let browser;
  try {
    browser = await chromium.launch({ channel: 'chrome', headless: true });
    const context = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
    await context.addInitScript(() => { window.PalmSystem = {}; });
    await context.route('https://megastrem.megastation.uk/api/v1/**', async route => {
      const request = route.request(), name = request.url().split('/api/v1/')[1];
      const body = request.postDataJSON(); let result = {};
      if (name === 'installations/register') { device = body; registrations.push(device.installationId); }
      if (name === 'devices/heartbeat') {
        const now = Math.floor(Date.now() / 1000), encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
        const input = encode({ alg: 'ES256', kid: jwk.kid }) + '.' + encode({ iss: 'https://megastrem.megastation.uk', aud: 'megastream-webos',
          sub: device.installationId, credentialBinding: crypto.createHash('sha256').update(Buffer.from(device.credential, 'base64url')).digest('base64url'),
          iat: now, nbf: now, exp: now + 300, licenseStartsAt: now - 60, licenseEndsAt: now + 600, decision: 'allowed' });
        result = { decision: { state: 'allowed' }, lease: input + '.' + crypto.sign('sha256', Buffer.from(input), { key: pair.privateKey, dsaEncoding: 'ieee-p1363' }).toString('base64url') };
      }
      if (name === 'public/signing-key') result = { keys: [jwk] };
      if (name.startsWith('devices/experience')) result = { allowSubscriptionDetails: true, notifications: notices, uiStyle: style };
      if (name === 'devices/appearance') style = body.uiStyle;
      if (name === 'providers/redeem') { assert.equal(body.code, 'CODE-FIXTURE'); redeemed = true; }
      if (name === 'providers/assignments') result = { assignments: redeemed ? [{ assignmentId: 'fixture', profileRevision: 1,
        displayName: 'Fixture', type: 'm3u', configuration: { m3uUrl: `http://127.0.0.1:${server.address().port}/fixture.m3u` } }] : [], tombstones: [] };
      await route.fulfill({ json: result, headers: { 'Access-Control-Allow-Origin': '*' } });
    });
    const page = await context.newPage();
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    await page.goto(baseUrl);
    await page.locator('.auth-screen').waitFor();
    const remote = async (keyCode, repeat = false) => {
      await page.evaluate(({ keyCode, repeat }) => window.dispatchEvent(new KeyboardEvent('keydown', { keyCode, which: keyCode, repeat, bubbles: true, cancelable: true })), { keyCode, repeat });
      await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => resolve())));
    };
    await remote(403);
    assert.equal(await page.locator('.auth-screen').count(), 1, 'No provider means no shortcut navigation');
    await page.getByRole('button', { name: 'Activation code', exact: true }).click();
    await page.getByLabel('Provider activation code').fill('CODE-FIXTURE');
    await page.getByRole('button', { name: 'Load subscription', exact: true }).click();
    await page.locator('.tile-live').waitFor();
    assert.equal(await page.locator('.home-screen .remote-live').count(), 1);
    assert.equal(await page.locator('.home-screen .remote-movie').count(), 1);
    assert.equal(await page.locator('.home-screen .remote-series').count(), 0, 'M3U has no Series shortcut');
    await remote(405);
    assert.equal(await page.locator('.home-screen').count(), 1);
    await remote(403, true);
    assert.equal(await page.locator('.home-screen').count(), 1, 'Repeated colored key ignored');
    await remote(403);
    await page.getByText('Fixture News', { exact: true }).waitFor();
    await remote(36);
    await page.locator('.home-screen').waitFor();
    await remote(404);
    await page.locator('.content-grid.poster').waitFor();
    await remote(406);
    await page.locator('input[type="search"]').waitFor();
    assert.equal(await page.locator('.type-tab.active').innerText(), 'Movies');
    await remote(403);
    assert.equal(await page.locator('.type-tab.active').innerText(), 'Movies', 'Editing search must suppress red key');
    assert.equal(await page.locator('input[type="search"]').count(), 1);
    await page.keyboard.press('Escape');
    await page.locator('input[type="search"]').waitFor({ state: 'detached' });
    await remote(36);
    await page.locator('.home-screen').waitFor();
    await page.locator('.appearance-open').click();
    await page.locator('dialog[open]').waitFor();
    await remote(403);
    assert.equal(await page.locator('dialog[open]').count(), 1, 'Open native dialog suppresses shortcuts');
    assert.equal(await page.locator('.home-screen').count(), 1);
    await page.locator('.appearance-close').click();
    await page.evaluate(() => window.dispatchEvent(new Event('megastream-license-open')));
    await page.locator('.desktop-license-modal [role="dialog"]').waitFor();
    await remote(403);
    assert.equal(await page.locator('.home-screen').count(), 1, 'License dialog suppresses shortcuts');
    await page.locator('.desktop-license-modal').getByRole('button', { name: 'Close', exact: true }).click();
    await page.evaluate(() => { const select = document.createElement('select'); select.id = 'shortcut-select-fixture'; select.append(new Option('Fixture', 'fixture')); document.body.append(select); select.focus(); });
    await remote(403);
    assert.equal(await page.locator('.home-screen').count(), 1, 'Active native select suppresses shortcuts');
    await page.evaluate(() => document.getElementById('shortcut-select-fixture').remove());
    await remote(406);
    await page.locator('input[type="search"]').waitFor();
    assert.equal(await page.locator('.type-tab.active').innerText(), 'Movies', 'Blue preserves current type from Home');
    await page.keyboard.press('Escape');
    await remote(36);
    await page.locator('.home-screen').waitFor();
    console.log('PASS: LG red/green/blue/Home, M3U Series guard, repeat/editable/native-select/dialog/license guards and Home markers');
    if (process.env.SHORTCUTS_ONLY === '1') { assert.deepEqual(errors, []); return; }
    await page.locator('.tile-live').click();
    await page.getByText('Fixture News', { exact: true }).waitFor();
    await page.locator('.favorite-button').first().click();
    await page.locator('.favorites-toggle').click();
    assert.equal(await page.locator('.channel-card').count(), 1);
    await page.locator('.home-btn').click();
    for (style of ['classic', 'modern', 'studio']) {
      await page.waitForFunction(value => document.documentElement.dataset.design === value, style, { timeout: 12000 });
    }
    notices = [{ id: crypto.randomUUID(), title: 'Notice heading', message: 'Separate notice body', createdAt: new Date().toISOString() }];
    await page.getByRole('heading', { name: 'Notice heading', exact: true }).waitFor({ timeout: 12000 });
    assert.equal(await page.locator('.notification-card p').innerText(), 'Separate notice body');
    await page.screenshot({ path: path.resolve(__dirname, '../../output/lg-3.0.7-notification-ui.png') });
    await page.locator('.notification-card .notification-close').click();
    assert.equal(await page.locator('.notification-card').count(), 0);
    await page.getByRole('button', { name: /^Notifications/ }).click();
    await page.locator('.notification-inbox').waitFor();
    await page.keyboard.press('Escape');
    assert.equal(await page.locator('.notification-inbox').count(), 0);
    notices = [{ id: crypto.randomUUID(), title: 'Automatic close', message: 'One-minute notification', createdAt: new Date().toISOString() }];
    await page.getByRole('heading', { name: 'Automatic close', exact: true }).waitFor({ timeout: 12000 });
    await page.locator('.notification-card').waitFor({ state: 'detached', timeout: 65000 });
    const installation = registrations[0];
    await page.reload(); await page.locator('.tile-live').waitFor();
    assert.equal(registrations[1], installation);
    assert.deepEqual(errors, []);
    console.log('PASS: LG license, code redemption, preserved identity, remote designs, favorites, popup and inbox UI');
  } finally {
    if (browser) await browser.close();
    if (sourceServer) await sourceServer.close();
    await new Promise(resolve => server.close(resolve));
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
