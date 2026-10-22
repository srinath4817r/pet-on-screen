const { Tray, Menu, nativeImage, app } = require('electron');
function createTray(actions) {
  const svg = '<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32" viewBox="0 0 32 32"><path fill="#ff6f95" d="M16 2C7 2 2 9 2 17c0 8 6 13 14 13s14-5 14-13C30 9 25 2 16 2Z"/><ellipse fill="#fff" opacity=".8" cx="11" cy="11" rx="4" ry="2"/></svg>';
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
