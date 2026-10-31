/**
 * Throwaway theme-verification shots: renders the browse screen (ContentScreen)
 * at 1920x1080 in AR + EN for a given platform/design and writes PNGs to
 * output/theme-browse-<lang>.png. Run with:
 *
 *   export PATH="/opt/homebrew/bin:$PATH"
 *   node tests/theme-browse-shot.cjs
 */
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const path = require('node:path');

(async () => {
  const viteUrl = require('node:url').pathToFileURL(path.resolve(__dirname, '../node_modules/vite/dist/node/index.js')).href;
  const { createServer, transformWithEsbuild } = await import(viteUrl);
  const root = path.resolve(__dirname, '..');
  const fixturePlugin = {
    name: 'theme-browse-fixture',
    enforce: 'pre',
    configureServer(vite) {
      vite.middlewares.use('/fixture.m3u', (_request, response) => response.end('#EXTM3U\n' +
        Array.from({ length: 240 }, (_, i) => `#EXTINF:-1 group-title="Group ${String(i % 12).padStart(2, '0')}",Channel ${String(i).padStart(3, '0')}\nhttps://example.invalid/${i}.ts`).join('\n')));
      vite.middlewares.use('/fixture', async (_request, response) => {
        response.setHeader('Content-Type', 'text/html');
        const html = `<div id="root"></div><script type="module">
          import React, { useState } from 'react';
          import { createRoot } from 'react-dom/client';
          import { init } from '@noriginmedia/norigin-spatial-navigation';
          import ContentScreen, { initialBrowseState } from '/src/screens/ContentScreen.tsx';
          import { LanguageProvider } from '/src/i18n/LanguageContext.tsx';
          import { AppearanceProvider } from '/src/components/Appearance.tsx';
          import '/src/index.css';
          import '/src/components/WebosTv.css';
          const params = new URLSearchParams(location.search);
          document.documentElement.dataset.platform = params.get('platform') || 'webos';
          localStorage.setItem('MegaStream.design', params.get('design') || 'modern');
          localStorage.setItem('MegaStream.lang', params.get('lang') || 'en');
          init({debug:false, visualDebug:false});
          const provider = { id:'fixture', name:'Theme fixture', type:'m3u', url:'/fixture.m3u' };
          function Fixture() {
            const [view, setView] = useState(initialBrowseState);
            return <LanguageProvider><AppearanceProvider>
              <ContentScreen provider={provider} providers={[provider]} contentType="live" onChangeType={()=>{}}
                onSelectProvider={()=>{}} onSelect={()=>{}} onManageProviders={()=>{}}
                viewState={view} onViewStateChange={setView}/>
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
    for (const lang of ['ar', 'en']) {
      const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
      const errors = [];
      page.on('pageerror', e => errors.push(e.message));
      await page.goto(`${base}?platform=webos&design=modern&lang=${lang}`);
      await page.locator('.channel-card').first().waitFor({ timeout: 20000 });
      await page.waitForTimeout(800);
      // Focus a category item + a card so focus rings are visible in the shot.
      await page.evaluate(() => {
        document.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', keyCode: 40, which: 40, bubbles: true }));
        document.dispatchEvent(new KeyboardEvent('keyup', { key: 'ArrowDown', keyCode: 40, which: 40, bubbles: true }));
      });
      await page.waitForTimeout(400);
      const file = path.join(outDir, `theme-browse-${lang}.png`);
      await page.screenshot({ path: file });
      console.log(`browse/${lang} -> ${path.basename(file)}`, errors.length ? `ERRORS: ${errors.join(' | ')}` : '');
      await page.close();
    }
  } finally {
    if (browser) await browser.close();
    await server.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
