const { app, ipcMain, globalShortcut, dialog, shell, Notification, BrowserWindow, powerMonitor, Menu, safeStorage } = require('electron');
const { execFile, spawn } = require('node:child_process');
const { promisify } = require('node:util');
const path = require('node:path');
const { fileURLToPath } = require('node:url');
const fs = require('node:fs');
const os = require('node:os');
const { read, write } = require('./settings');
const { createWindows } = require('./windows');
const { createTray } = require('./tray');

const hasSingleInstanceLock = app.requestSingleInstanceLock();
if (!hasSingleInstanceLock) {
  dialog.showErrorBox('Poring Pet is already running', 'Poring Pet is already running on this PC. Check the system tray to open or hide it.');
  app.exit(0);
} else {
  app.on('second-instance', () => {
    if (!wins) return;
    setHidden(false);
    wins.capsule?.showInactive();
  });
}

const chromiumCache = path.join(app.getPath('userData'), 'Cache');
fs.mkdirSync(chromiumCache, { recursive: true });
app.setPath('cache', chromiumCache);
app.commandLine.appendSwitch('disk-cache-dir', chromiumCache);

let wins; let tray; let settings; let visible = true; let petOut = false; let fullScreen = false; let fullscreenOverride = false; let mediaBridge;
let platformState = { wifi: { on: false, connected: false, ssid: '', signal: null, available: [] }, bluetooth: { on: false, known: false, connected: [], available: [] } };
let lastForegroundPid = null, maximizedTarget = false, edgeDirection = 1;
const pickedPaths = new Set();
const shortcuts = new Set();
const allowedProtocols = new Set(['https:', 'http:', 'mailto:']);
const execFileAsync = promisify(execFile);
const allWindows = () => [wins?.capsule, wins?.pet].filter(w => w && !w.isDestroyed());
const credentialFile = () => path.join(app.getPath('userData'), 'credentials.json');
function readCredentials() {
  try { return JSON.parse(fs.readFileSync(credentialFile(), 'utf8')); } catch { return {}; }
}
function writeCredentials(data) {
  fs.mkdirSync(path.dirname(credentialFile()), { recursive: true });
  fs.writeFileSync(credentialFile(), JSON.stringify(data), { encoding: 'utf8', mode: 0o600 });
}
function setHidden(hidden) {
  visible = !hidden;
  if (hidden) fullscreenOverride = false;
  else if (fullScreen) fullscreenOverride = true;
  for (const win of allWindows()) {
    win.webContents.send('app:visibility', Boolean(hidden));
    win.setAlwaysOnTop(true, 'screen-saver');
    win.hide();
  }
  if (visible) applyMode();
}
function applyMode() {
  if (!wins || !visible) return;
  if (fullScreen && !fullscreenOverride) { allWindows().forEach(win => win.hide()); return; }
  const show = (win, yes) => { if (!win.__pageReady) return; if (yes) { win.setAlwaysOnTop(true, 'screen-saver'); win.showInactive(); } else win.hide(); };
  if (settings.mode === 'capsule') { show(wins.capsule, true); show(wins.pet, false); }
  else if (settings.mode === 'desktop') { show(wins.capsule, true); show(wins.pet, true); }
  else if (petOut) { show(wins.capsule, true); show(wins.pet, true); }
  else { show(wins.capsule, true); show(wins.pet, false); }
}
function setCapsuleBounds(width, height) {
  if (!wins?.capsule || wins.capsule.isDestroyed()) return;
  const display = require('electron').screen.getDisplayMatching(wins.capsule.getBounds()).workArea;
  const w = Math.max(310, Math.min(680, Math.round(Number(width) || 380)));
  const h = Math.max(84, Math.min(650, Math.round(Number(height) || 104)));
  wins.capsule.setBounds({ x: Math.round(display.x + (display.width - w) / 2), y: display.y + 6, width: w, height: h }, true);
}
function placePet(random = false) {
  if (!wins?.pet || wins.pet.isDestroyed()) return;
  const display = require('electron').screen.getDisplayMatching(wins.pet.getBounds());
  const area = display.workArea, bounds = wins.pet.getBounds();
  const x = random ? Math.round(area.x + Math.random() * Math.max(0, area.width - bounds.width)) : Math.round(area.x + (area.width - bounds.width) / 2);
  const y = random ? Math.round(area.y + Math.random() * Math.max(0, area.height - bounds.height)) : area.y + area.height - bounds.height;
    if (bounds.x !== x || bounds.y !== y) wins.pet.setBounds({ x, y, width: bounds.width, height: bounds.height }, true);
}
function placePetOnTaskbarEdge(direction = 1) {
  if (!wins?.pet || wins.pet.isDestroyed()) return;
  const display = require('electron').screen.getDisplayMatching(wins.pet.getBounds());
  const area = display.workArea, bounds = wins.pet.getBounds();
  const margin = 24;
  const x = direction < 0 ? area.x + margin : area.x + area.width - bounds.width - margin;
  const y = area.y + area.height - bounds.height;
  wins.pet.setBounds({ x, y, width: bounds.width, height: bounds.height }, true);
}
function randomPetAction() {
  return ['LookAround', 'Happy', 'Run', 'Jump', 'Spin', 'Roll', 'Dash', 'Coding', 'Sleep'][Math.floor(Math.random() * 9)];
}
function handlePlatformState(state) {
  if (!state) return;
  if (state.type === 'hotkey') { setHidden(visible); return; }
  if (state.type !== 'platform') return;
  if (state.foregroundPid !== lastForegroundPid) {
    lastForegroundPid = state.foregroundPid;
    for (const win of allWindows()) if (win.isVisible() && !(fullScreen && !fullscreenOverride)) {
      win.setAlwaysOnTop(true, 'screen-saver');
      win.moveTop();
    }
  }
  const changedFullScreen = fullScreen !== Boolean(state.fullScreen);
  fullScreen = Boolean(state.fullScreen);
  maximizedTarget = Boolean(state.maximized);
  if (maximizedTarget && (settings.mode === 'desktop' || petOut)) placePetOnTaskbarEdge(edgeDirection);
  if (changedFullScreen && !fullScreen) fullscreenOverride = false;
  platformState = { wifi: state.wifi || platformState.wifi, bluetooth: state.bluetooth || platformState.bluetooth };
  for (const win of allWindows()) win.webContents.send('platform:state', { ...state, wifi: platformState.wifi, bluetooth: platformState.bluetooth });
  if (changedFullScreen) applyMode();
}
function startPlatformMonitor() {
  let scriptPath = path.join(__dirname, 'platform-monitor.ps1');
  if (scriptPath.includes('app.asar')) scriptPath = scriptPath.replace('app.asar', 'app.asar.unpacked');
  const child = spawn('powershell.exe', ['-NoLogo', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', scriptPath], { windowsHide: true, stdio: ['ignore', 'pipe', 'ignore'] });
  let pending = '';
  child.stdout.setEncoding('utf8');
  child.stdout.on('data', chunk => {
    pending += chunk;
    const lines = pending.split(/\r?\n/); pending = lines.pop() || '';
    for (const line of lines) { try { handlePlatformState(JSON.parse(line)); } catch {} }
  });
  child.on('error', () => {});
  child.on('exit', () => { if (!app.isQuitting) setTimeout(startPlatformMonitor, 5000); });
  app.on('will-quit', () => child.kill());
}
function startMediaBridge() {
  let exe = path.join(__dirname, 'native', 'PoringMedia.exe');
  if (exe.includes('app.asar')) exe = exe.replace('app.asar', 'app.asar.unpacked');
  if (!fs.existsSync(exe)) return;
  mediaBridge = spawn(exe, [], { windowsHide: true, stdio: ['pipe', 'pipe', 'ignore'] });
  let pending = '';
  mediaBridge.stdout.setEncoding('utf8');
  mediaBridge.stdout.on('data', chunk => {
    pending += chunk;
    const lines = pending.split(/\r?\n/); pending = lines.pop() || '';
    for (const line of lines) {
      try { const media = JSON.parse(line); for (const win of allWindows()) win.webContents.send('media:changed', media); } catch {}
    }
  });
  mediaBridge.on('error', () => {});
  mediaBridge.on('exit', () => { if (!app.isQuitting) setTimeout(startMediaBridge, 5000); });
  app.on('will-quit', () => mediaBridge?.kill());
}
function setMode(mode) {
  settings.mode = mode; write(settings);
  petOut = false;
  applyMode();
}
function registerHotkey() {
  globalShortcut.unregisterAll();
  settings.hotkey = 'Control+Alt+Space';
  write(settings);
}
function configureStartup() {
  if (process.platform !== 'win32') return;
  settings.startWithWindows = true;
  write(settings);
  const executable = app.getPath('exe');
  const args = process.defaultApp && process.argv[1] ? [path.resolve(process.argv[1])] : [];
  app.setLoginItemSettings({ openAtLogin: true, path: executable, args, name: 'PoringPet' });
  // Electron 31 can write an unquoted Run command when the install path has spaces.
  // Keep a correctly quoted per-user Run entry so Windows can launch the installed app.
  const quote = value => `"${String(value).replace(/"/g, '')}"`;
  const command = [quote(executable), ...args.map(quote)].join(' ');
  execFile('reg.exe', ['add', 'HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run', '/v', 'PoringPet', '/t', 'REG_SZ', '/d', command, '/f'], { windowsHide: true }, error => {
    if (error) console.error(`Could not configure Windows startup: ${error.message}`);
  });
}
function bindIpc() {
  ipcMain.on('window:ignore-mouse', (event, ignore) => {
    const win = BrowserWindow.fromWebContents(event.sender);
    if (win && allWindows().includes(win)) win.setIgnoreMouseEvents(Boolean(ignore), { forward: true });
  });
  ipcMain.handle('app:open-external', async (_event, value) => {
    if (typeof value !== 'string') throw new Error('Invalid URL.');
    let url; try { url = new URL(value); } catch { throw new Error('Invalid URL.'); }
    if (!allowedProtocols.has(url.protocol)) throw new Error('Only HTTP, HTTPS and mailto links can be opened.');
    return shell.openExternal(url.href);
  });
  ipcMain.handle('app:pick', async () => {
    const result = await dialog.showOpenDialog({ properties: ['openFile'], filters: [{ name: 'Applications', extensions: ['exe', 'lnk'] }] });
    const value = result.canceled ? null : result.filePaths[0];
    if (value) pickedPaths.add(path.resolve(value));
    return value;
  });
  ipcMain.handle('app:open-path', async (_event, value) => {
    if (typeof value !== 'string' || !value.trim() || value.length > 2048) return { ok: false, error: 'Enter an application path or browse to an app.' };
    let candidate = value.trim().replace(/^"(.*)"$/, '$1').replace(/%([^%]+)%/g, (_match, name) => process.env[name] || `%${name}%`);
    try { if (/^file:/i.test(candidate)) candidate = fileURLToPath(candidate); } catch { return { ok: false, error: 'That file URL is not valid.' }; }
    let resolved = path.resolve(candidate);
    if (!fs.existsSync(resolved) && !/[\\/]/.test(candidate)) {
      try { const found = await execFileAsync('where.exe', [candidate], { timeout: 3000, windowsHide: true, maxBuffer: 4096 }); resolved = path.resolve(found.stdout.split(/\r?\n/)[0].trim()); } catch {}
    }
    if (!fs.existsSync(resolved)) return { ok: false, error: `App location not found: ${candidate}` };
    let stat; try { stat = fs.statSync(resolved); } catch { return { ok: false, error: 'Windows could not read that app location.' }; }
    if (!stat.isFile() && !stat.isDirectory()) return { ok: false, error: 'Choose an app, shortcut, or folder.' };
    shortcuts.add(resolved);
    const error = await shell.openPath(resolved);
    return error ? { ok: false, error } : { ok: true };
  });
  ipcMain.handle('media:control', async (_event, command) => {
    if (!['toggle', 'next', 'prev'].includes(command)) throw new Error('Invalid media command.');
    if (mediaBridge?.stdin.writable) { mediaBridge.stdin.write(command + '\n'); return true; }
    const vk = { prev: '0xB1', next: '0xB0', toggle: '0xB3' }[command];
    const script = "Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public static class PoringMediaKey { [DllImport(\"user32.dll\")] public static extern void keybd_event(byte key, byte scan, uint flags, UIntPtr extra); }'; [PoringMediaKey]::keybd_event([byte]" + vk + ",0,0,[UIntPtr]::Zero); [PoringMediaKey]::keybd_event([byte]" + vk + ",0,2,[UIntPtr]::Zero)";
    await execFileAsync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script], { timeout: 4000, windowsHide: true });
    return true;
  });
  ipcMain.handle('network:get', async () => platformState);
  ipcMain.handle('credential:get', async (_event, service) => {
    if (!['github', 'vercel'].includes(service)) throw new Error('Unknown service.');
    if (!safeStorage.isEncryptionAvailable()) throw new Error('Windows secure credential storage is unavailable.');
    const stored = readCredentials()[service];
    return stored ? safeStorage.decryptString(Buffer.from(stored, 'base64')) : '';
  });
  ipcMain.handle('credential:set', async (_event, service, token) => {
    if (!['github', 'vercel'].includes(service) || typeof token !== 'string' || token.length < 8 || token.length > 4096) throw new Error('Invalid credential.');
    if (!safeStorage.isEncryptionAvailable()) throw new Error('Windows secure credential storage is unavailable.');
    const data = readCredentials(); data[service] = safeStorage.encryptString(token).toString('base64'); writeCredentials(data); return true;
  });
  ipcMain.handle('credential:delete', async (_event, service) => {
    if (!['github', 'vercel'].includes(service)) throw new Error('Unknown service.');
    const data = readCredentials(); delete data[service]; writeCredentials(data); return true;
  });
  ipcMain.handle('network:settings', async (_event, kind) => {
    return shell.openExternal(kind === 'bluetooth' ? 'ms-settings:bluetooth' : 'ms-settings:network-wifi');
  });
  ipcMain.on('window:layout', (event, size) => {
    if (event.sender === wins?.capsule?.webContents && size && Number.isFinite(size.width) && Number.isFinite(size.height)) setCapsuleBounds(size.width, size.height);
  });
  ipcMain.handle('github:get', async () => {
    let token;
    try { token = readCredentials().github ? safeStorage.decryptString(Buffer.from(readCredentials().github, 'base64')) : ''; } catch { token = ''; }
    if (!token) try {
      ({ stdout: token } = await execFileAsync('gh', ['auth', 'token'], { timeout: 7000, windowsHide: true, maxBuffer: 4096 }));
      token = token.trim();
    } catch { return { note: 'Connect a GitHub token here, or install GitHub CLI and run `gh auth login`.', items: [] }; }
    if (!token) return { note: 'GitHub CLI has no active login. Run `gh auth login`.', items: [] };
    try {
      const headers = { Authorization: `Bearer ${token}`, Accept: 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28' };
      const get = async endpoint => {
        const response = await fetch(`https://api.github.com${endpoint}`, { headers });
        const data = await response.json();
        if (!response.ok) throw new Error(response.status === 401 ? 'GitHub login expired. Run `gh auth login` again.' : response.status === 403 ? 'GitHub rate limit or permissions reached.' : data.message || `GitHub returned ${response.status}.`);
        return data;
      };
      const [user, notifications, issues, repos] = await Promise.all([
        get('/user'), get('/notifications?per_page=10'), get('/issues?filter=assigned&state=open&per_page=10'), get('/user/repos?sort=pushed&per_page=5')
      ]);
      const items = [
        ...notifications.map(n => ({ title: n.subject.title, subtitle: `${n.repository.full_name} · notification`, time: n.updated_at, url: n.subject.url?.replace('api.github.com/repos/', 'github.com/').replace('/issues/', '/issues/') })),
        ...issues.map(i => ({ title: i.title, subtitle: `${i.repository_url.split('/').slice(-2).join('/')} · assigned ${i.pull_request ? 'pull request' : 'issue'}`, time: i.updated_at, url: i.html_url })),
        ...repos.map(r => ({ title: r.full_name, subtitle: `${r.stargazers_count} stars · ${r.language || 'language not specified'}`, time: r.pushed_at, url: r.html_url }))
      ];
      token = '';
      return { note: `Live GitHub data · @${user.login}`, items };
    } catch (error) {
      token = '';
      return { note: error.message || 'GitHub is unavailable.', items: [] };
    }
  });
  ipcMain.on('pet:desktop-play', () => { petOut = true; placePet(false); wins.capsule.webContents.executeJavaScript('IslandApp?.setPetOut(true)'); applyMode(); });
  ipcMain.on('pet:return', () => { petOut = false; wins.capsule.webContents.executeJavaScript('IslandApp?.setPetOut(false)'); applyMode(); });
  ipcMain.on('pet:hit', () => wins?.pet?.webContents.send('pet:activity', { action: 'Surprised' }));
  ipcMain.on('pet:move-by', (_event, delta) => {
    const win = wins?.pet;
    if (!win || win.isDestroyed() || !delta || !Number.isFinite(delta.dx) || !Number.isFinite(delta.dy)) return;
    const display = require('electron').screen.getDisplayMatching(win.getBounds());
    const bounds = win.getBounds(), area = display.workArea;
    const x = Math.max(area.x - bounds.width + 60, Math.min(area.x + area.width - 60, bounds.x + Math.round(delta.dx)));
    const y = Math.max(area.y, Math.min(area.y + area.height - 40, bounds.y + Math.round(delta.dy)));
    win.setBounds({ ...bounds, x, y }, true);
  });
}
app.whenReady().then(() => {
  Menu.setApplicationMenu(null);
  settings = read(); configureStartup(); wins = createWindows(); bindIpc(); registerHotkey(); startPlatformMonitor(); startMediaBridge();
  const recentRendererCrashes = new Map();
  for (const [name, win] of Object.entries(wins)) {
    win.webContents.on('render-process-gone', (_event, details) => {
      if (app.isQuitting || win.isDestroyed()) return;
      const now = Date.now(), recent = (recentRendererCrashes.get(name) || []).filter(time => now - time < 60000);
      recent.push(now); recentRendererCrashes.set(name, recent);
      console.error(`Poring ${name} renderer stopped (${details.reason}); recovery ${recent.length}/3 in the last minute.`);
      if (recent.length <= 3) setTimeout(() => { if (!app.isQuitting && !win.isDestroyed()) win.reload(); }, 1200);
    });
  }
  tray = createTray({ toggle: () => setHidden(visible), setMode });
  setMode(settings.mode);
  placePet(false);
  setCapsuleBounds(380, 104);
  setInterval(() => {
    if ((fullScreen && !fullscreenOverride) || !visible || !(settings.mode === 'desktop' || petOut)) return;
    if (Math.random() < .45) {
      edgeDirection *= -1;
      placePetOnTaskbarEdge(edgeDirection);
      wins?.pet?.webContents.send('pet:activity', { action: ['Run', 'Roll', 'Walk'][Math.floor(Math.random() * 3)] });
    } else wins?.pet?.webContents.send('pet:activity', { action: randomPetAction() });
  }, 7000);
  let loaded = 0;
  for (const win of allWindows()) win.webContents.on('did-finish-load', async () => {
    win.__pageReady = true;
    applyMode();
    if (!process.env.PORING_TEST_UI) return;
    try {
      const result = await win.webContents.executeJavaScript("({title:document.title,three:typeof THREE,api:typeof IslandApp,background:getComputedStyle(document.body).backgroundImage,htmlBackground:getComputedStyle(document.documentElement).backgroundImage,bar:getComputedStyle(document.querySelector('#bar')).display,header:getComputedStyle(document.querySelector('#t')).display,hint:getComputedStyle(document.querySelector('#hint')).display})");
      console.log(`UI_LOAD_OK ${JSON.stringify(result)}`);
      const shots = path.join(os.tmpdir(), 'poring-pet-ui-test');
      fs.mkdirSync(shots, { recursive: true });
      fs.writeFileSync(path.join(shots, win === wins.capsule ? 'capsule.png' : 'pet.png'), (await win.capturePage()).toPNG());
    } catch (error) { console.error(`UI_LOAD_FAIL ${error.message}`); }
    if (++loaded === 2) app.quit();
  });
  powerMonitor.on('resume', () => allWindows().forEach(w => w.setAlwaysOnTop(true, 'screen-saver')));
  app.on('activate', () => setHidden(false));
});
app.on('will-quit', () => globalShortcut.unregisterAll());
app.on('before-quit', () => { app.isQuitting = true; });
app.on('window-all-closed', event => event.preventDefault());
