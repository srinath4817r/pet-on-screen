const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const source = fs.readFileSync(path.join(root, '_source', 'poring-capsule.html'), 'utf8');
let html = source;
function replaceRange(startToken, endToken, replacement) {
  const start = html.indexOf(startToken);
  const end = html.indexOf(endToken, start);
  if (start < 0 || end < 0) throw new Error(`Could not locate renderer section: ${startToken}`);
  html = html.slice(0, start) + replacement + html.slice(end);
}

html = html.replace(
  'https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js',
  'vendor/three.min.js'
);
html = html.replace(
  'background: radial-gradient(ellipse at 50% 30%, #3a2c58 0%, #1a142b 60%, #0d0914 100%);',
  'background: transparent;'
);
replaceRange('const demoPlay=()=>', 'const stopAll=', 'const demoPlay=()=>{};\n');
html = html.replace(
  'const live=!sys&&an;if(live){fa=fa||new Uint8Array(an.frequencyBinCount);an.getByteFrequencyData(fa)}',
  'if(!an||sys)return;fa=fa||new Uint8Array(an.frequencyBinCount);an.getByteFrequencyData(fa)'
).replace(
  'const v=live?fa[Math.min(i*2+1,fa.length-1)]/255:.25+.5*Math.abs(Math.sin(Date.now()/260+i*.9));',
  'const v=fa[Math.min(i*2+1,fa.length-1)]/255;'
);
html = html.replace(
  "const track=()=>P.tr[P.i]||{n:'System media unavailable',a:'Choose a local audio file to play',none:1};",
  "const track=()=>P.tr[P.i]||(SYS.available?{n:'Nothing playing',a:'No active system media session',none:1}:{n:'System media unavailable',a:'Choose a local audio file to play',none:1});"
).replace(
  "const SYS={title:'',artist:'',playing:false};",
  "const SYS={title:'',artist:'',playing:false,available:false};"
).replace(
  "SYS.playing=!!(m&&m.playing);sync()",
  "SYS.playing=!!(m&&m.playing);SYS.available=m?m.available!==false:false;sync()"
).replace(
  "$('#nbs').textContent=!b.known?'Desktop app only':b.on?'On':'Off'",
  "$('#nbs').textContent=!b.known?(NAT().getNetwork?'Permission needed':'Desktop app only'):b.on?'On':'Off'"
).replace(
  "$('#nws').textContent=w.on?(w.ssid?'Connected · '+w.ssid:'Online'):'Off'",
  "$('#nws').textContent=w.on?(w.ssid?'Connected · '+w.ssid:w.connected?'Online':'On'):'Off'"
).replace(
  "const a=apps.find(x=>x.id===(n&&n.app));",
  "const a=apps.find(x=>x.id===(n&&n.app)||x.h==='native');"
);

fs.mkdirSync(path.join(root, 'desktop', 'renderer', 'vendor'), { recursive: true });
fs.writeFileSync(path.join(root, 'desktop', 'renderer', 'poring-capsule.html'), html);
fs.copyFileSync(path.join(root, 'node_modules', 'three', 'build', 'three.min.js'), path.join(root, 'desktop', 'renderer', 'vendor', 'three.min.js'));
console.log('Prepared transparent desktop renderer with live service shortcuts and no synthetic media.');
