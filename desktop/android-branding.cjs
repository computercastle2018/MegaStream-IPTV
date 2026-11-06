const { spawnSync } = require('node:child_process');
const path = require('node:path');
const os = require('node:os');
const fs = require('node:fs');
const root = path.resolve(__dirname, '..');
const icon = path.join(root, 'webos/public/icon.png');
const resources = path.join(root, 'app/src/main/res');
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'megastream-branding-'));
function run(command, args) {
  const result = spawnSync(command, args, { encoding: 'utf8' });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(result.stderr);
}
(async () => { try {
  const banner = path.join(temporary, 'banner.png');
  const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
  const browser = await chromium.launch({ channel: 'chrome' });
  try {
    const page = await browser.newPage({ viewport: { width: 640, height: 360 }, deviceScaleFactor: 1 });
    await page.setContent(`<body style="margin:0;background:#100608;color:white;display:flex;align-items:center;height:360px;gap:25px;padding:0 40px;font:bold 50px Arial;box-sizing:border-box"><img width="200" height="200" src="data:image/png;base64,${fs.readFileSync(icon).toString('base64')}"><span>MegaStream</span></body>`);
    await page.locator('img').evaluate(image => image.decode());
    await page.screenshot({ path: banner });
  } finally { await browser.close(); }
  for (const [density, size, bannerWidth] of [['mdpi', 48, 320], ['hdpi', 72, 480],
    ['xhdpi', 96, 640], ['xxhdpi', 144, 960], ['xxxhdpi', 192, 1280]]) {
    run('cwebp', ['-quiet', '-lossless', '-resize', String(size), String(size), icon,
      '-o', path.join(resources, `mipmap-${density}/ic_launcher_vault.webp`)]);
    run('cwebp', ['-quiet', '-lossless', '-resize', String(size * 2), String(size * 2), icon,
      '-o', path.join(resources, `drawable-${density}/ic_launcher_vault_art.webp`)]);
    run('cwebp', ['-quiet', '-lossless', '-resize', String(bannerWidth), '0', banner,
      '-o', path.join(resources, `drawable-${density}/app_banner.webp`)]);
  }
  run('cwebp', ['-quiet', '-lossless', banner, '-o', path.join(resources, 'drawable/app_banner.webp')]);
} finally {
  fs.rmSync(temporary, { recursive: true, force: true });
}
})().catch(error => { console.error(error); process.exitCode = 1; });
