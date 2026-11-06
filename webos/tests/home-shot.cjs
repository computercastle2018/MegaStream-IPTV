/**
 * Renders the home dashboard (modern/studio design) in both English and Arabic
 * and writes PNG screenshots so the layout can be compared against the
 * reference design. Run with:
 *
 *   export PATH="/opt/homebrew/bin:$PATH"
 *   node tests/home-shot.cjs
 */
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const path = require('node:path');
const os = require('node:os');

const LANGS = ['ar', 'en'];
const DESIGNS = ['modern', 'studio'];
const SIZES = [[1920, 1080], [1280, 720]];

(async () => {
  const viteUrl = require('node:url').pathToFileURL(path.resolve(__dirname, '../node_modules/vite/dist/node/index.js')).href;
  const { createServer, transformWithEsbuild } = await import(viteUrl);
  const root = path.resolve(__dirname, '..');
  const channels = Array.from({ length: 24 }, (_, i) => ({
    id: `c${i}`, kind: 'live', name: `Channel ${i + 1}`, number: i + 1,
    groupTitle: 'News', streamUrl: 'https://example.invalid/x.ts', streamFormat: 'ts',
  }));
  const movies = Array.from({ length: 12 }, (_, i) => ({
    id: `m${i}`, kind: 'movie', name: `Movie ${i + 1}`, groupTitle: 'Action',
    streamUrl: 'https://example.invalid/m.mkv', streamFormat: 'auto',
  }));
  const series = Array.from({ length: 8 }, (_, i) => ({
    id: `s${i}`, kind: 'series', name: `Series ${i + 1}`, groupTitle: 'Drama',
    streamFormat: 'auto',
  }));

  const fixturePlugin = {
    name: 'home-shot-fixture',
    enforce: 'pre',
    configureServer(vite) {
      vite.middlewares.use('/fixture.m3u', (_request, response) => {
        response.end('#EXTM3U\n' + channels.map((c, i) =>
          `#EXTINF:-1 tvg-id="${c.id}" tvg-chno="${c.number}" group-title="News",${c.name}\nhttps://example.invalid/${i}.ts`).join('\n'));
      });
      vite.middlewares.use('/fixture', async (_request, response) => {
        response.setHeader('Content-Type', 'text/html');
        const html = `<div id="root"></div><script type="module">
          import React, { useState } from 'react';
          import { createRoot } from 'react-dom/client';
          import { init } from '@noriginmedia/norigin-spatial-navigation';
          import HomeScreen from '/src/screens/HomeScreen.tsx';
          import { LanguageProvider } from '/src/i18n/LanguageContext.tsx';
          import { AppearanceProvider } from '/src/components/Appearance.tsx';
          import '/src/index.css';
          import '/src/components/WebosTv.css';
          const params = new URLSearchParams(location.search);
          document.documentElement.dataset.platform = params.get('platform') || 'webos';
          localStorage.setItem('MegaStream.design', params.get('design') || 'modern');
          localStorage.setItem('MegaStream.lang', params.get('lang') || 'en');
          init({debug:false, visualDebug:false});
          const provider = { id:'fixture', name:'Fixture Provider', type:'m3u', url:'/fixture.m3u' };
          function Fixture() {
            return <LanguageProvider><AppearanceProvider>
              <HomeScreen provider={provider} onOpenType={()=>{}} onChangePlaylist={()=>{}} onSettings={()=>{}} />
            </AppearanceProvider></LanguageProvider>;
          }
          createRoot(document.getElementById('root')).render(<Fixture/>);
        </script>`;
        const script = html.match(/<script type="module">([\s\S]*?)<\/script>/)[1];
        const { code } = await transformWithEsbuild(script, 'fixture.tsx', { loader: 'tsx', jsx: 'automatic' });
        response.end(await vite.transformIndexHtml('/fixture', html.replace(script, code)));
      });
    },
  };

  const server = await createServer({
    root, configFile: false, plugins: [fixturePlugin],
    define: { __APP_VERSION__: JSON.stringify('fixture') },
    esbuild: { jsx: 'automatic' },
    server: { host: '127.0.0.1', port: 0 }, logLevel: 'error',
  });
  await server.listen();
  const base = `http://127.0.0.1:${server.httpServer.address().port}/fixture`;
  const outDir = path.resolve(__dirname, '../../output');
  let browser;
  try {
    browser = await chromium.launch({ channel: 'chrome', headless: true });
    for (const design of DESIGNS) {
      for (const lang of LANGS) {
        for (const [width, height] of SIZES) {
          const page = await browser.newPage({ viewport: { width, height } });
          const errors = [];
          page.on('pageerror', e => errors.push(e.message));
          await page.goto(`${base}?platform=webos&design=${design}&lang=${lang}`);
          await page.locator('.home-screen').waitFor({ timeout: 20000 });
          await page.waitForTimeout(1200);
          const metrics = await page.evaluate(() => ({
            homeTiles: document.querySelectorAll('.home-tile').length,
            hasHomeSide: !!document.querySelector('.home-side'),
            homeSideBottom: document.querySelector('.home-side')?.getBoundingClientRect().bottom ?? null,
            scrollWidth: document.documentElement.scrollWidth,
            innerWidth: window.innerWidth,
            scrollHeight: document.documentElement.scrollHeight,
            innerHeight: window.innerHeight,
            focusables: document.querySelectorAll('[tabindex="0"]').length,
          }));
          const file = path.join(outDir, `home-${design}-${lang}-${width}.png`);
          await page.screenshot({ path: file });
          console.log(`${design}/${lang}/${width} -> ${path.basename(file)}`,
            JSON.stringify(metrics), errors.length ? `ERRORS: ${errors.join(' | ')}` : '');
          await page.close();
        }
      }
    }
  } finally {
    if (browser) await browser.close();
    await server.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
