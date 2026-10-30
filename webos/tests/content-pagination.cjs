const assert = require('node:assert/strict');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { pathToFileURL } = require('node:url');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

(async () => {
  const { createServer, transformWithEsbuild } = await import(pathToFileURL(path.resolve(__dirname, '../node_modules/vite/dist/node/index.js')).href);
  const root = path.resolve(__dirname, '..');
  const fixturePlugin = { name: 'large-catalog-fixture', enforce: 'pre',
    load(id) {
      if (process.env.SOURCE_BASELINE === '1' && id === path.join(root, 'src/screens/ContentScreen.tsx'))
        return execFileSync('git', ['show', 'HEAD:webos/src/screens/ContentScreen.tsx'], { cwd: root, encoding: 'utf8' });
    }, configureServer(vite) {
  vite.middlewares.use('/fixture.m3u', (_request, response) => response.end('#EXTM3U\n' +
    Array.from({ length: 12000 }, (_, i) => `#EXTINF:-1 group-title="Group ${String(i % 300).padStart(3, '0')}",Channel ${String(i).padStart(5, '0')}\nhttps://example.invalid/${i}.ts`).join('\n')));
  vite.middlewares.use('/fixture', async (_request, response) => {
    response.setHeader('Content-Type', 'text/html');
    const html = `<div id="root"></div><script type="module">
      import React, { useState, Profiler } from 'react';
      import { createRoot } from 'react-dom/client';
      import { init, getCurrentFocusKey } from '@noriginmedia/norigin-spatial-navigation';
      import ContentScreen, { initialBrowseState } from '/src/screens/ContentScreen.tsx';
      import { LanguageProvider } from '/src/i18n/LanguageContext.tsx';
      import { AppearanceProvider } from '/src/components/Appearance.tsx';
      import HomeScreen from '/src/screens/HomeScreen.tsx';
      import '/src/index.css';
      import '/src/components/WebosTv.css';
      const params = new URLSearchParams(location.search);
      document.documentElement.dataset.platform = params.get('platform') || 'desktop';
      localStorage.setItem('MegaStream.design', params.get('design') || 'classic');
      localStorage.setItem('MegaStream.lang', params.get('lang') || 'en');
      localStorage.removeItem('MegaStream.groupOrder.fixture.live');
      init({debug:false, visualDebug:false});
      const provider = { id:'fixture', name:'Large fixture', type:'m3u', url:'/fixture.m3u' };
      window.commits = []; window.scrollCalls = 0;
      const scroll = Element.prototype.scrollIntoView;
      Element.prototype.scrollIntoView = function(...args) { window.scrollCalls++; return scroll.apply(this,args); };
      window.currentFocus = getCurrentFocusKey;
      function Fixture() {
        const [view, setView] = useState(initialBrowseState), [mounted, setMounted] = useState(true), [tick, setTick] = useState(0), [home, setHome] = useState(false);
        window.showHome = () => setHome(true);
        window.poll = () => setTick(tick + 1);
        window.remount = () => { setMounted(false); setTimeout(() => setMounted(true),0); };
        return <LanguageProvider><AppearanceProvider><Profiler id="browse" onRender={(_id, phase, duration) => window.commits.push({phase,duration})}>
          {home ? <HomeScreen provider={provider} onOpenType={()=>setHome(false)} onChangePlaylist={()=>{}} onSettings={()=>{}}/> : mounted && <ContentScreen provider={provider} providers={[provider]} contentType="live" onChangeType={()=>{}}
            onSelectProvider={()=>{}} onSelect={item=>window.selected=item.id} onManageProviders={()=>{}}
            viewState={view} onViewStateChange={setView}/>}</Profiler></AppearanceProvider></LanguageProvider>;
      }
      createRoot(document.getElementById('root')).render(<Fixture/>);
    </script>`;
    const script = html.match(/<script type="module">([\s\S]*?)<\/script>/)[1];
    const { code } = await transformWithEsbuild(script, 'fixture.tsx', { loader: 'tsx', jsx: 'automatic' });
    response.end(await vite.transformIndexHtml('/fixture', html.replace(script, code)));
  });
  } };
  const server = await createServer({ root, configFile: false, plugins: [fixturePlugin],
    define: { __APP_VERSION__: JSON.stringify('fixture') }, esbuild: { jsx: 'automatic' },
    server: { host: '127.0.0.1', port: 0 }, logLevel: 'error' });
  await server.listen();
  let browser;
  try {
    browser = await chromium.launch({ channel: 'chrome', headless: true });
    const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
    page.setDefaultTimeout(6000);
    const errors = []; page.on('pageerror', error => { errors.push(error.message); console.error(error.message); });
    await page.goto(`http://127.0.0.1:${server.httpServer.address().port}/fixture`);
    await page.getByText('Channel 00000', { exact: true }).waitFor({ timeout: 30000 });
    const metrics = await page.evaluate(() => ({ cards: document.querySelectorAll('.channel-card').length,
      categories: document.querySelectorAll('.category-item').length, focusable: document.querySelectorAll('[tabindex="0"]').length,
      elements: document.querySelectorAll('*').length, renderMs: Math.round(window.commits.reduce((sum,c)=>sum+c.duration,0)) }));
    const focusBefore = await page.evaluate(() => window.currentFocus());
    const keyTimes = [];
    for (let i = 0; i < 20; i++) {
      keyTimes.push(await page.evaluate(() => new Promise(resolve => {
        const start = performance.now();
        document.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', keyCode: 40, which: 40, bubbles: true }));
        document.dispatchEvent(new KeyboardEvent('keyup', { key: 'ArrowDown', keyCode: 40, which: 40, bubbles: true }));
        requestAnimationFrame(() => requestAnimationFrame(() => resolve(performance.now() - start)));
      })));
    }
    assert.notEqual(await page.evaluate(() => window.currentFocus()), focusBefore, 'D-pad must move focus');
    metrics.dpadPaintP95Ms = Math.round(keyTimes.sort((a,b)=>a-b)[18]);
    console.log(JSON.stringify(metrics));
    if (process.env.BASELINE === '1') return;
    assert(metrics.cards <= 100, `unbounded channel cards: ${metrics.cards}`);
    assert(metrics.categories <= 41, `unbounded category focus registrations: ${metrics.categories}`);
    assert(metrics.focusable < 300);
    await page.getByRole('button', { name: 'Next channels', exact: true }).click();
    await page.getByText('Channel 00100', { exact: true }).waitFor();
    assert.equal(await page.locator('.channel-card.focused .name').innerText(), 'Channel 00100');
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Enter');
    await page.waitForFunction(() => typeof window.selected === 'string');
    const selected = await page.evaluate(() => window.selected);
    await page.evaluate(() => window.remount());
    await page.waitForFunction(id => document.querySelector('.channel-card.focused .num')?.textContent === String(Number(id.split(':').pop()) + 1), selected);
    await page.getByRole('button', { name: 'Previous channels', exact: true }).click();
    for (let i = 1; i < 120; i++) await page.getByRole('button', { name: 'Next channels', exact: true }).click();
    await page.getByText('Channel 11999', { exact: true }).waitFor();
    await page.locator('.favorite-button').last().click();
    await page.locator('.favorites-toggle').click();
    assert.equal(await page.locator('.channel-card').count(), 1);
    assert.equal(await page.locator('.channel-card .name').innerText(), 'Channel 11999');
    await page.locator('.favorites-toggle').click();
    await page.getByRole('button', { name: 'Next groups', exact: true }).click();
    await page.getByText('Group 040', { exact: true }).click();
    assert.equal(await page.locator('.channel-card').count(), 40);
    await page.getByText('All', { exact: true }).click();
    await page.locator('.group-sort').selectOption('desc');
    assert.equal(await page.locator('.category-item .cat-name').nth(1).innerText(), 'Group 299');
    await page.locator('.search-toggle').click();
    await page.locator('input[type=search]').fill('Channel 11999');
    assert.equal(await page.locator('.channel-card').count(), 1);
    assert.equal(await page.locator('.channel-card .name').innerText(), 'Channel 11999');
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => !document.querySelector('input[type=search]'));
    assert.equal(await page.locator('.channel-card').count(), 100);
    const focus = await page.evaluate(() => window.currentFocus());
    const commits = await page.evaluate(() => window.commits.length);
    await page.evaluate(() => window.poll());
    await page.waitForFunction(count => window.commits.length > count, commits);
    assert.equal(await page.evaluate(() => window.currentFocus()), focus);
    for (const design of ['modern', 'studio']) {
      for (const width of [1920, 1280]) {
        await page.setViewportSize({ width, height: width === 1920 ? 1080 : 720 });
        await page.goto(`http://127.0.0.1:${server.httpServer.address().port}/fixture?platform=webos&design=${design}&lang=ar`);
        await page.getByText('Channel 00000', { exact: true }).waitFor();
        assert.equal(await page.locator('.channel-card').count(), 40);
        assert.equal(await page.locator('.category-item').count(), 21);
        const layout = await page.evaluate(() => {
          const rect = selector => document.querySelector(selector).getBoundingClientRect();
          const header = rect('.app-header'), toolbar = rect('.browse-toolbar'), rail = rect('.category-rail'), content = rect('.browse-content');
          return { headerHeight: header.height + toolbar.height, railRight: rail.right, contentRight: content.right,
            overlap: Math.max(0, Math.min(rail.right, content.right) - Math.max(rail.left, content.left)),
            overflow: document.documentElement.scrollWidth > innerWidth, bottom: Math.max(rail.bottom, content.bottom) > innerHeight };
        });
        assert(layout.headerHeight <= 136, JSON.stringify(layout));
        assert(layout.railRight > layout.contentRight, 'RTL groups must be on the right');
        assert.equal(layout.overlap, 0);
        assert.equal(layout.overflow, false);
        assert.equal(layout.bottom, false);
        await page.getByRole('button', { name: 'ترتيب المجموعات', exact: true }).click();
        await page.locator('.remote-options').waitFor();
        await page.keyboard.press('ArrowDown');
        await page.keyboard.press('Enter');
        await page.locator('.remote-options').waitFor({ state: 'detached' });
        assert.match(await page.getByRole('button', { name: 'ترتيب المجموعات', exact: true }).innerText(), /أبجدي/);
        await page.getByRole('button', { name: 'ترتيب المجموعات', exact: true }).click();
        await page.locator('.remote-options').waitFor();
        await page.evaluate(() => window.dispatchEvent(new KeyboardEvent('keydown', { keyCode: 461, bubbles: true })));
        await page.locator('.remote-options').waitFor({ state: 'detached' });
        await page.getByRole('button', { name: 'الفئة', exact: true }).click();
        await page.locator('.remote-options').waitFor();
        await page.keyboard.press('Escape');
        await page.locator('.remote-options').waitFor({ state: 'detached' });
        assert.equal(await page.locator('.genre-select,.group-sort').count(), 0, 'LG filters must not trap native-select focus');
        await page.locator('.channel-card').first().focus();
        await page.keyboard.press('ArrowRight');
        assert.equal(await page.locator('.category-item.focused').count(), 1, 'Right returns to groups');
        await page.keyboard.press('ArrowLeft');
        assert.equal(await page.locator('.channel-card.focused .name').innerText(), 'Channel 00000');
        await page.getByRole('button', { name: 'الصفحة التالية', exact: true }).click();
        await page.getByText('Channel 00040', { exact: true }).waitFor();
        await page.keyboard.press('ArrowUp');
        await page.waitForFunction(() => document.querySelector('.channel-card.focused .name')?.textContent === 'Channel 00039');
        await page.keyboard.press('ArrowDown');
        await page.waitForFunction(() => document.querySelector('.channel-card.focused .name')?.textContent === 'Channel 00040');
        await page.getByRole('button', { name: 'المجموعات التالية', exact: true }).click();
        await page.getByText('Group 020', { exact: true }).click();
        await page.keyboard.press('ArrowLeft');
        assert.equal(await page.locator('.channel-card.focused .name').innerText(), 'Channel 00020');
        await page.locator('.category-item').last().click();
        await page.keyboard.press('ArrowDown');
        await page.waitForFunction(() => document.querySelector('.category-item.focused .cat-name')?.textContent === 'Group 040');
        await page.keyboard.press('ArrowUp');
        await page.waitForFunction(() => document.querySelector('.category-item.focused .cat-name')?.textContent === 'Group 039');
        await page.screenshot({ path: path.join(require('node:os').tmpdir(), `lg-tv-${design}-${width}.png`) });
        await page.evaluate(() => window.showHome());
        await page.locator('.home-screen').waitFor();
        assert.equal(await page.locator('.home-tile').count(), 4);
        assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth && document.querySelector('.home-side').getBoundingClientRect().bottom <= innerHeight));
        await page.screenshot({ path: path.join(require('node:os').tmpdir(), `lg-home-${design}-${width}.png`) });
        console.log(`PASS: LG ${design} RTL ${width}, 40 cards/21 groups, compact header, D-pad pane/page boundaries, home`);
      }
    }
    for (const [platform, design] of [['webos', 'classic'], ['desktop', 'modern'], ['desktop', 'studio']]) {
      await page.goto(`http://127.0.0.1:${server.httpServer.address().port}/fixture?platform=${platform}&design=${design}`);
      await page.getByText('Channel 00000', { exact: true }).waitFor();
      assert.equal(await page.locator('.channel-card').count(), 100);
      assert.equal(await page.locator('.category-item').count(), 41);
      assert(await page.evaluate(() => {
        const geometry = () => [...document.querySelectorAll('.app-header,.browse-toolbar,.category-rail,.content-grid,.channel-card')]
          .map(node => { const r = node.getBoundingClientRect(); return [r.x, r.y, r.width, r.height]; });
        const before = JSON.stringify(geometry());
        const css = document.querySelector('style[data-vite-dev-id$="/WebosTv.css"]');
        css.media = 'not all';
        const equal = before === JSON.stringify(geometry());
        css.media = '';
        return equal;
      }), `${platform}/${design} must not change when TV stylesheet is disabled`);
      console.log(`PASS: ${platform}/${design} unaffected by TV stylesheet`);
    }
    assert.deepEqual(errors, []);
    console.log('PASS: bounded catalog, D-pad, all 120 pages, favorites, category pages/sort, search, return-focus and unchanged poll');
  } finally {
    if (browser) await browser.close();
    await server.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
