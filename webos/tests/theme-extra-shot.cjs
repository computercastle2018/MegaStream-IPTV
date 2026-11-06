/**
 * Throwaway theme-verification shots: classic home dashboard + provider setup
 * (auth) screen at 1920x1080 in EN. Run with:
 *
 *   export PATH="/opt/homebrew/bin:$PATH"
 *   node tests/theme-extra-shot.cjs
 */
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const path = require('node:path');

(async () => {
  const viteUrl = require('node:url').pathToFileURL(path.resolve(__dirname, '../node_modules/vite/dist/node/index.js')).href;
  const { createServer, transformWithEsbuild } = await import(viteUrl);
  const root = path.resolve(__dirname, '..');
  const channels = Array.from({ length: 24 }, (_, i) => ({
    id: `c${i}`, kind: 'live', name: `Channel ${i + 1}`, number: i + 1,
    groupTitle: 'News', streamUrl: 'https://example.invalid/x.ts', streamFormat: 'ts',
  }));
  const fixturePlugin = {
    name: 'theme-extra-fixture',
    enforce: 'pre',
    configureServer(vite) {
      vite.middlewares.use('/fixture.m3u', (_request, response) => {
        response.end('#EXTM3U\n' + channels.map((c, i) =>
          `#EXTINF:-1 tvg-id="${c.id}" tvg-chno="${c.number}" group-title="News",${c.name}\nhttps://example.invalid/${i}.ts`).join('\n'));
      });
      vite.middlewares.use('/fixture', async (_request, response) => {
        response.setHeader('Content-Type', 'text/html');
        const html = `<div id="root"></div><script type="module">
          import React from 'react';
          import { createRoot } from 'react-dom/client';
          import { init } from '@noriginmedia/norigin-spatial-navigation';
          import HomeScreen from '/src/screens/HomeScreen.tsx';
          import ProviderSetupScreen from '/src/screens/ProviderSetupScreen.tsx';
          import { LanguageProvider } from '/src/i18n/LanguageContext.tsx';
          import { AppearanceProvider } from '/src/components/Appearance.tsx';
          import '/src/index.css';
          import '/src/components/WebosTv.css';
          const params = new URLSearchParams(location.search);
          document.documentElement.dataset.platform = params.get('platform') || 'desktop';
          localStorage.setItem('MegaStream.design', params.get('design') || 'classic');
          localStorage.setItem('MegaStream.lang', params.get('lang') || 'en');
          init({debug:false, visualDebug:false});
          const provider = { id:'fixture', name:'Fixture Provider', type:'m3u', url:'/fixture.m3u' };
          const screen = params.get('screen') || 'home';
          function Fixture() {
            return <LanguageProvider><AppearanceProvider>
              {screen === 'auth'
                ? <ProviderSetupScreen providers={[]} onChange={()=>{}} onConnect={()=>{}} onDone={()=>{}} canGoBack={false} />
                : <HomeScreen provider={provider} onOpenType={()=>{}} onChangePlaylist={()=>{}} onSettings={()=>{}} />}
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
    const shots = [
      ['theme-home-classic-en', 'desktop', 'classic', 'en', 'home', '.home-screen'],
      ['theme-home-classic-ar', 'desktop', 'classic', 'ar', 'home', '.home-screen'],
      ['theme-auth-en', 'desktop', 'classic', 'en', 'auth', '.auth-screen'],
    ];
    for (const [name, platform, design, lang, screen, waitFor] of shots) {
      const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
      const errors = [];
      page.on('pageerror', e => errors.push(e.message));
      await page.goto(`${base}?platform=${platform}&design=${design}&lang=${lang}&screen=${screen}`);
      await page.locator(waitFor).waitFor({ timeout: 20000 });
      await page.waitForTimeout(900);
      await page.screenshot({ path: path.join(outDir, `${name}.png`) });
      console.log(`${name}.png`, errors.length ? `ERRORS: ${errors.join(' | ')}` : '');
      await page.close();
    }
  } finally {
    if (browser) await browser.close();
    await server.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
