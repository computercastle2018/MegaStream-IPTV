const { app, BrowserWindow, protocol, net, ipcMain, safeStorage, Menu, MenuItem, Tray, nativeImage } = require('electron');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { assetPath, validateStorage } = require('./security.cjs');
const { encryptedStore } = require('./encrypted-store.cjs');
const { ControlPlane } = require('./control-plane.cjs');

// A standard, non-secure scheme permits user-provided HTTP IPTV servers without disabling webSecurity.
protocol.registerSchemesAsPrivileged([{ scheme: 'megastream', privileges: { standard: true, supportFetchAPI: true, stream: true, corsEnabled: true } }]);
let window;
let tray;
let showWindow;
if (!app.requestSingleInstanceLock()) app.quit();
else {
  app.on('second-instance', () => showWindow?.());
  app.whenReady().then(() => {
    const store = encryptedStore(path.join(app.getPath('userData'), 'providers.encrypted'), safeStorage);
    let minimizeToTray = store.get('desktop.minimizeToTray') === true;
    let dockHideTimer;
    let lastDockHide = 0;
    const control = new ControlPlane(store, app.getVersion(), state => {
      if (window && !window.isDestroyed()) window.webContents.send('desktop-control-state', state);
    });
    const trusted = event => event.sender === window?.webContents && event.senderFrame === window.webContents.mainFrame && event.senderFrame.url.startsWith('megastream://app/');
    ipcMain.handle('desktop-control', async (event, action, value) => {
      if (!trusted(event)) throw new Error('Invalid sender');
      try {
        if (action === 'state') return control.state;
        if (action === 'sync') return await control.sync();
        if (action === 'activate') return await control.activate(value);
        if (action === 'code') return await control.requestCode();
        throw new Error('Invalid action');
      } catch { throw new Error('Unable to complete license request'); }
    });
    protocol.handle('megastream', async request => {
      try {
        const response = await net.fetch(pathToFileURL(assetPath(path.join(__dirname, 'ui'), request.url)).href);
        response.headers.set('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' http: https: data:; connect-src 'self' http: https: blob:; media-src http: https: blob:; worker-src 'self' blob:; font-src 'self'; object-src 'none'; base-uri 'none'; frame-src 'none'");
        return response;
      } catch { return new Response('Not found', { status: 404 }); }
    });
    ipcMain.on('provider-storage', (event, operation, key, value) => {
      try {
        if (!trusted(event)) throw new Error('Invalid sender');
        validateStorage(operation, key, value);
        if (operation !== 'getItem') {
          if (key === 'MegaStream.providers') {
            if (!control.state.allowSubscriptionDetails) throw new Error('Subscription editing disabled');
            const remote = JSON.parse(store.get(key) || '[]').filter(provider => provider.id.startsWith('remote:'));
            const local = operation === 'setItem' ? JSON.parse(value).filter(provider => !provider.id.startsWith('remote:')) : [];
            store.set(key, JSON.stringify([...local, ...remote]));
          } else store.set(key, operation === 'setItem' ? value : null);
        }
        event.returnValue = { value: store.get(key) };
      } catch { event.returnValue = { error: 'Unable to access encrypted subscription storage' }; }
    });
    const createWindow = () => {
      window = new BrowserWindow({ width: 1280, height: 800, minWidth: 820, minHeight: 600, backgroundColor: '#0b0f14', title: 'MegaStream', webPreferences: { preload: path.join(__dirname, 'preload.cjs'), sandbox: true, contextIsolation: true, nodeIntegration: false } });
      window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
      window.webContents.on('will-navigate', (event, url) => { if (!url.startsWith('megastream://app/')) event.preventDefault(); });
      window.webContents.session.setPermissionRequestHandler((_contents, _permission, callback) => callback(false));
      window.webContents.session.webRequest.onHeadersReceived({ urls: ['http://*/*', 'https://*/*'] }, (details, callback) => {
        const headers = { ...details.responseHeaders };
        if (details.webContentsId === window?.webContents.id) {
          for (const key of Object.keys(headers)) if (key.toLowerCase().startsWith('access-control-')) delete headers[key];
          // Cross-origin redirects give anonymous fetches an opaque (null) origin.
          // This override is scoped to this app's webContents, never other clients.
          headers['Access-Control-Allow-Origin'] = ['*'];
        }
        callback({ responseHeaders: headers });
      });
      window.loadURL('megastream://app/index.html');
      window.on('focus', () => control.syncExperience().catch(() => control.sync()));
      window.on('minimize', () => {
        if (!minimizeToTray || !tray || tray.isDestroyed()) return;
        window.hide();
        clearTimeout(dockHideTimer);
        dockHideTimer = setTimeout(() => {
          if (minimizeToTray && window && !window.isVisible()) {
            app.dock.hide(); lastDockHide = Date.now();
          }
        }, Math.max(0, 1100 - (Date.now() - lastDockHide)));
      });
      window.on('closed', () => { window = null; });
    };
    showWindow = async () => {
      clearTimeout(dockHideTimer);
      if (process.platform === 'darwin') await app.dock.show();
      if (!window) createWindow();
      if (window.isMinimized()) window.restore();
      window.show(); window.focus();
    };
    const applicationMenu = Menu.buildFromTemplate([
      ...(process.platform === 'darwin' ? [{ role: 'appMenu' }] : []),
      { role: 'editMenu' }, { role: 'viewMenu', id: 'view' }, { role: 'windowMenu' }
    ]);
    if (process.platform === 'darwin') {
      const label = 'إخفاء من Dock عند التصغير';
      const updateTrayMenu = () => tray.setContextMenu(Menu.buildFromTemplate([
        { id: 'restore-window', label: 'إظهار MegaStream', click: showWindow },
        { type: 'separator' },
        { label, type: 'checkbox', checked: minimizeToTray, click: item => setMinimizeToTray(item.checked) },
        { type: 'separator' }, { role: 'quit', label: 'خروج نهائي' }
      ]));
      const setMinimizeToTray = enabled => {
        store.set('desktop.minimizeToTray', enabled);
        minimizeToTray = enabled;
        applicationMenu.getMenuItemById('minimize-to-tray').checked = enabled;
        updateTrayMenu();
        if (!enabled) showWindow();
      };
      const icon = nativeImage.createFromNamedImage('NSComputer').resize({ width: 18, height: 18 });
      icon.setTemplateImage(true);
      tray = new Tray(icon);
      tray.setToolTip('MegaStream');
      applicationMenu.getMenuItemById('view').submenu.append(new MenuItem({ type: 'separator' }));
      applicationMenu.getMenuItemById('view').submenu.append(new MenuItem({
        id: 'minimize-to-tray', label, type: 'checkbox', checked: minimizeToTray,
        click: item => setMinimizeToTray(item.checked)
      }));
      updateTrayMenu();
    }
    Menu.setApplicationMenu(applicationMenu);
    createWindow();
    control.sync();
    const timer = setInterval(() => { if (control.code) control.pollCode().catch(() => control.sync()); else control.sync(); }, 30000);
    const experienceTimer = setInterval(() => control.syncExperience().catch(() => control.sync()), 5000);
    app.on('before-quit', () => { clearInterval(timer); clearInterval(experienceTimer); clearTimeout(dockHideTimer); tray?.destroy(); });
    app.on('activate', () => showWindow());
  });
  app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
}
