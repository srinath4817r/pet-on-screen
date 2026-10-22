const { BrowserWindow, screen } = require('electron');
const path = require('node:path');

function createWindows() {
  const display = screen.getPrimaryDisplay().workArea;
  const capsule = new BrowserWindow({
    width: 380, height: 104, x: Math.round(display.x + (display.width - 380) / 2), y: display.y,
    frame: false, transparent: true, backgroundColor: '#00000000', resizable: false, show: false,
    skipTaskbar: true, hasShadow: false, alwaysOnTop: true, focusable: true,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false, sandbox: true }
  });
  capsule.setAlwaysOnTop(true, 'screen-saver');
  capsule.setIgnoreMouseEvents(true, { forward: true });
  capsule.on('blur', () => {
    if (!capsule.isDestroyed()) capsule.webContents.executeJavaScript('IslandApp?.collapse()').catch(() => {});
  });
  capsule.loadFile(path.join(__dirname, 'renderer', 'poring-capsule.html'));
  capsule.webContents.on('dom-ready', () => capsule.webContents.insertCSS(`html,body{background:transparent!important}#t,#bar,#chgLvl{display:none!important}`));

  const pet = new BrowserWindow({
    width: 300, height: 250, x: Math.round(display.x + (display.width - 300) / 2), y: display.y + display.height - 290,
    frame: false, transparent: true, backgroundColor: '#00000000', resizable: false, show: false,
    skipTaskbar: true, hasShadow: false, alwaysOnTop: true, focusable: false,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false, sandbox: true }
  });
  pet.setAlwaysOnTop(true, 'screen-saver');
  pet.setIgnoreMouseEvents(true, { forward: true });
  pet.loadFile(path.join(__dirname, 'renderer', 'poring-capsule.html'), { query: { mode: 'desktop' } });
  pet.webContents.on('dom-ready', () => pet.webContents.insertCSS(`html,body{background:transparent!important}#t,#bar,#chgLvl{display:none!important}`));
  return { capsule, pet };
}
module.exports = { createWindows };
