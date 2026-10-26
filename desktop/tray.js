const { Tray, Menu, nativeImage, app } = require('electron');
const fs = require('node:fs');
const path = require('node:path');
function createTray(actions) {
  const svg = fs.readFileSync(path.join(__dirname, 'assets', 'poring-logo.svg'), 'utf8');
  const icon = nativeImage.createFromDataURL(`data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`);
  const tray = new Tray(icon);
  tray.setToolTip('Poring Pet');
  const menu = Menu.buildFromTemplate([
    { label: 'Show / Hide', click: actions.toggle },
    { label: 'Capsule only', type: 'radio', click: () => actions.setMode('capsule') },
    { label: 'Desktop only', type: 'radio', click: () => actions.setMode('desktop') },
    { label: 'Both', type: 'radio', checked: true, click: () => actions.setMode('both') },
    { type: 'separator' }, { label: 'Quit', click: () => app.quit() }
  ]);
  tray.setContextMenu(menu);
  tray.on('click', actions.toggle);
  return tray;
}
module.exports = { createTray };
