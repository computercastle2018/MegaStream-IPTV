// Test entrypoint only; captures the real native tray menu for interaction tests.
const { Tray } = require('electron');
const original = Tray.prototype.setContextMenu;
Tray.prototype.setContextMenu = function (menu) {
  global.testTray = this;
  global.testTrayMenu = menu;
  return original.call(this, menu);
};
require('./smoke-main.cjs');
