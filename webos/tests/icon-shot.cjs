/** Rasterises tmp/icon-megastream.svg to PNG at several sizes via headless Chrome. */
const { chromium } = require('playwright');
const path = require('node:path');
const fs = require('node:fs');

const SIZES = [
  { px: 512, out: '../../webos/public/icon.png' },
  { px: 512, out: '../../desktop/ui/icon.png' },
  { px: 130, out: '../../tmp/icon-130.png' },
  { px: 80, out: '../../tmp/icon-80.png' },
];

(async () => {
  const svgPath = path.resolve(__dirname, '../../tmp/icon-megastream.svg');
  const svg = fs.readFileSync(svgPath, 'utf8');
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    for (const { px, out } of SIZES) {
      const page = await browser.newPage({ viewport: { width: px, height: px }, deviceScaleFactor: 1 });
      await page.setContent(
        `<style>*{margin:0;padding:0}body{width:${px}px;height:${px}px;overflow:hidden;background:transparent}</style>` +
        svg.replace('width="512" height="512"', `width="${px}" height="${px}"`),
      );
      await page.screenshot({ path: path.resolve(__dirname, out), omitBackground: true });
      console.log(`wrote ${out} at ${px}px`);
      await page.close();
    }
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
