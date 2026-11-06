const { _electron: electron } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
(async () => {
  if (process.platform !== 'darwin') { console.log('SKIP: native macOS menu-bar test'); return; }
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'megastream-tray-'));
  let application;
  const launch = () => electron.launch({ executablePath: require('electron'), args: [path.join(__dirname, 'tray-main.cjs'), `--user-data-dir=${directory}`] });
  const waitHidden = async () => {
    for (let count = 0; count < 40; count++) {
      if (await application.evaluate(({ app, BrowserWindow }) => !app.dock.isVisible() && !BrowserWindow.getAllWindows()[0].isVisible())) return;
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    console.log('TRAY_STATE', await application.evaluate(({ app, BrowserWindow }) => ({ dock: app.dock.isVisible(),
      visible: BrowserWindow.getAllWindows()[0].isVisible(), minimized: BrowserWindow.getAllWindows()[0].isMinimized(),
      enabled: app.applicationMenu.getMenuItemById('minimize-to-tray').checked })));
    assert.fail('Minimize must hide both the native window and Dock icon');
  };
  try {
    application = await launch();
    await application.firstWindow();
    assert.equal(await application.evaluate(() => !global.testTray.isDestroyed() && global.testTray.getBounds().width > 0), true);
    assert.equal(await application.evaluate(({ app }) => app.applicationMenu.getMenuItemById('minimize-to-tray').checked), false);
    await application.evaluate(({ app, BrowserWindow }) => {
      const item = app.applicationMenu.getMenuItemById('minimize-to-tray');
      item.click(item);
      BrowserWindow.getAllWindows()[0].minimize();
    });
    await waitHidden();
    await application.evaluate(() => global.testTrayMenu.getMenuItemById('restore-window').click());
    assert.equal(await application.evaluate(({ app, BrowserWindow }) => app.dock.isVisible() && BrowserWindow.getAllWindows()[0].isVisible()), true);
    await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].minimize());
    await waitHidden();
    await application.evaluate(() => global.testTrayMenu.getMenuItemById('restore-window').click());
    await application.close();
    application = await launch();
    await application.firstWindow();
    assert.equal(await application.evaluate(({ app }) => app.applicationMenu.getMenuItemById('minimize-to-tray').checked), true);
    console.log('PASS: real macOS tray icon, optional Dock hiding, menu restore, rapid re-minimize, and preference persistence after restart.');
  } finally { if (application) await application.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
