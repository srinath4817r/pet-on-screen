const { spawn } = require('node:child_process');
const path = require('node:path');
const electron = require('electron');

const child = spawn(electron, ['.'], {
  cwd: path.resolve(__dirname, '..'),
  env: { ...process.env, PORING_TEST_UI: '1', ELECTRON_DISABLE_SECURITY_WARNINGS: 'true' },
  windowsHide: true,
  stdio: ['ignore', 'pipe', 'pipe']
});
let output = '';
child.stdout.on('data', data => { output += data.toString(); process.stdout.write(data); });
child.stderr.on('data', data => { output += data.toString(); process.stderr.write(data); });
const timer = setTimeout(() => { child.kill(); console.error('FAIL: renderer load timed out'); process.exitCode = 1; }, 30000);
child.on('exit', code => {
  clearTimeout(timer);
  const matches = output.match(/UI_LOAD_OK/g) || [];
  const rows = [...output.matchAll(/UI_LOAD_OK (\{[^\r\n]+\})/g)].map(m => JSON.parse(m[1]));
  const visualCheck = rows.length === 2 && rows.every(row => row.three === 'object' && row.api === 'object' && row.background === 'none' && row.htmlBackground === 'none' && row.bar === 'none' && row.header === 'none' && row.hint === 'none');
  if (code !== 0 || matches.length !== 2 || output.includes('UI_LOAD_FAIL') || !visualCheck) {
    console.error(`FAIL: expected both renderers to load; got ${matches.length}/2 (exit ${code})`);
    process.exitCode = 1;
  } else console.log('PASS: capsule and desktop pet are transparent, frameless renderers with local Three.js');
});
