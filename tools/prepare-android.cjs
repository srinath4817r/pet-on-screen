const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const out = path.join(root, 'android', 'app', 'src', 'main', 'assets');
fs.mkdirSync(path.join(out, 'vendor'), { recursive: true });
const source = fs.readFileSync(path.join(root, 'desktop', 'renderer', 'poring-capsule.html'), 'utf8');
const bridge = `<script>
(() => {
  const parse = (value, fallback) => { try { return JSON.parse(value); } catch { return fallback; } };
  if (!navigator.getBattery) navigator.getBattery = async () => {
    const battery = {};
    for (const key of ['level', 'charging', 'chargingTime', 'dischargingTime']) {
      Object.defineProperty(battery, key, { get: () => {
        const value = parse(PoringBridge.getBattery(), {})[key];
        return value ?? (key === 'level' ? 0 : key === 'charging' ? false : Infinity);
      } });
    }
    battery.addEventListener = () => {};
    return battery;
  };
  window.IslandNative = {
    platform: 'android',
    getNotifications: async (...args) => {
      const rows = parse(PoringBridge.getNotifications(...args), null);
      if (!Array.isArray(rows)) throw new Error('Permission needed: allow notification access in Android settings.');
      return rows;
    },
    onNotification: callback => {
      if (typeof callback === 'function') window.addEventListener('poring-notification', event => callback(event.detail));
    },
    getGithub: async (...args) => parse(PoringBridge.getGithub(...args), {note:'GitHub sign-in is not connected',items:[]}),
    getNetwork: async (...args) => parse(PoringBridge.getNetwork(...args), {wifi:{on:false,ssid:''}}),
    getCredential: service => PoringBridge.getCredential(service),
    setCredential: (service, token) => PoringBridge.setCredential(service, token),
    deleteCredential: service => PoringBridge.deleteCredential(service),
    openNetworkSettings: kind => PoringBridge.openNetworkSettings(kind),
    setWifi: value => PoringBridge.setWifi(value),
    setBluetooth: value => PoringBridge.setBluetooth(value),
    onMedia: callback => {
      if (typeof callback !== 'function') return;
      const refresh = () => callback(parse(PoringBridge.getMedia(), { available: false }));
      refresh();
      return setInterval(refresh, 2500);
    },
    mediaControl: command => PoringBridge.mediaControl(command),
    openExternal: url => PoringBridge.openExternal(url),
    openPath: value => PoringBridge.openPath(value),
    pickApp: () => PoringBridge.pickApp(),
    setIgnoreMouse: value => PoringBridge.setIgnoreMouse(value),
    desktopPlay: () => PoringBridge.desktopPlay(),
    returnToCapsule: () => PoringBridge.returnToCapsule()
  };
})();
</script>`;
const output = source.replace('</head>', `${bridge}\n</head>`);
if (output === source) throw new Error('Could not locate the source document head.');
fs.writeFileSync(path.join(out, 'poring-capsule.html'), output);
fs.copyFileSync(path.join(root, 'desktop', 'renderer', 'vendor', 'three.min.js'), path.join(out, 'vendor', 'three.min.js'));
console.log('Prepared Android page with the native bridge and local Three.js.');
