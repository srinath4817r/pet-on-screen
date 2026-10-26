const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('IslandNative', Object.freeze({
  setIgnoreMouse: value => ipcRenderer.send('window:ignore-mouse', Boolean(value)),
  getNotifications: () => Promise.reject(new Error('Permission needed: the Windows notification listener is not connected.')),
  onNotification: callback => { if (typeof callback === 'function') ipcRenderer.on('notification:received', (_e, data) => callback(data)); },
  getGithub: () => ipcRenderer.invoke('github:get'),
  onMedia: callback => {
    if (typeof callback !== 'function') return;
    callback({ title: '', artist: '', playing: false, available: false });
    ipcRenderer.on('media:changed', (_e, data) => callback(data));
  },
  onPlatform: callback => { if (typeof callback === 'function') ipcRenderer.on('platform:state', (_e, data) => callback(data)); },
  resizeCapsule: (width, height) => ipcRenderer.send('window:layout', { width, height }),
  getNetwork: () => ipcRenderer.invoke('network:get'),
  openNetworkSettings: kind => ipcRenderer.invoke('network:settings', kind),
  platform: 'win32',
  getCredential: service => ipcRenderer.invoke('credential:get', service),
  setCredential: (service, token) => ipcRenderer.invoke('credential:set', service, token),
  deleteCredential: service => ipcRenderer.invoke('credential:delete', service),
  mediaControl: command => ipcRenderer.invoke('media:control', command),
  openExternal: url => ipcRenderer.invoke('app:open-external', url),
  openPath: filePath => ipcRenderer.invoke('app:open-path', filePath),
  pickApp: () => ipcRenderer.invoke('app:pick'),
  desktopPlay: () => ipcRenderer.send('pet:desktop-play'),
  returnToCapsule: () => ipcRenderer.send('pet:return'),
  petHit: () => ipcRenderer.send('pet:hit'),
  movePetBy: (dx, dy) => ipcRenderer.send('pet:move-by', { dx, dy }),
  onPetActivity: callback => { if (typeof callback === 'function') ipcRenderer.on('pet:activity', (_e, data) => callback(data)); },
  onVisibility: callback => { if (typeof callback === 'function') ipcRenderer.on('app:visibility', (_e, hidden) => callback(hidden)); }
}));
