const { app } = require('electron');
const fs = require('node:fs');
const path = require('node:path');

const defaults = { mode: 'both', hotkey: 'Control+Alt', startWithWindows: true, shortcuts: [] };
const file = () => path.join(app.getPath('userData'), 'settings.json');

function read() {
  try { return { ...defaults, ...JSON.parse(fs.readFileSync(file(), 'utf8')) }; }
  catch { return { ...defaults }; }
}
function write(value) {
  fs.mkdirSync(path.dirname(file()), { recursive: true });
  fs.writeFileSync(file(), JSON.stringify(value, null, 2), { encoding: 'utf8', mode: 0o600 });
}
module.exports = { read, write };
