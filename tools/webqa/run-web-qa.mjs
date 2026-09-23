// FOLD FORGE web QA: serves each template exactly like the in-app preview sandbox (runtime injected
// into HTML, files under /project/), then drives it with the same QA scenario engine the app uses.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright-core';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const TPL = path.join(root, 'core/src/main/templates');
const RUNTIME = fs.readFileSync(path.join(root, 'core/src/main/resources/foldforge/runtime/foldforge-runtime.js'), 'utf8');
const TEMPLATES = (process.argv[2] ? process.argv[2].split(',') : ['forge-runner', 'canvas-game', 'platformer', 'puzzle', 'interactive-story', 'mobile-ui', 'threejs-game', 'rpg3d', 'html-app', 'blank-web']);
const MIME = { html: 'text/html', js: 'text/javascript', css: 'text/css', json: 'application/json', md: 'text/plain', txt: 'text/plain' };

function resolveFile(tpl, rel) {
  const meta = JSON.parse(fs.readFileSync(path.join(TPL, tpl, 'template.json'), 'utf8'));
  if (meta.shared && meta.shared[rel]) return path.join(TPL, meta.shared[rel]);
  const p = path.join(TPL, tpl, rel);
  if (!p.startsWith(path.join(TPL, tpl))) return null;
  return p;
}

let current = 'forge-runner';
const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname === '/__ff/runtime.js') { res.writeHead(200, { 'content-type': 'text/javascript' }); return res.end(RUNTIME); }
  if (!url.pathname.startsWith('/project/')) { res.writeHead(404); return res.end(); }
  let rel = decodeURIComponent(url.pathname.slice('/project/'.length)) || 'index.html';
  const file = resolveFile(current, rel);
  if (!file || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404); return res.end('not found'); }
  const ext = path.extname(file).slice(1);
  let body = fs.readFileSync(file);
  if (ext === 'html' || ext === 'js' || ext === 'css' || ext === 'md' || ext === 'json') {
    if (!file.endsWith('.min.js')) body = Buffer.from(body.toString('utf8').replaceAll('{{PROJECT_NAME}}', 'QA ' + current));
  }
  // Mutation mode: inject a known bug to prove the QA agent detects it (npm test -- rpg3d with MUTATE=1).
  if (process.env.MUTATE && current === 'rpg3d' && rel === 'src/main.js') {
    body = Buffer.from(body.toString('utf8').replace('target.hp -= player.attackPower;', 'target.hp -= 0; // injected bug: hitbox never deals damage'));
  }
  if (ext === 'html') {
    const tag = '<script>window.__ffConfig={"fpsLimit":0};</script><script src="/__ff/runtime.js"></script>';
    const s = body.toString('utf8');
    const m = s.match(/<head[^>]*>/i);
    body = Buffer.from(m ? s.replace(m[0], m[0] + tag) : tag + s);
  }
  res.writeHead(200, { 'content-type': MIME[ext] || 'application/octet-stream', 'cache-control': 'no-store' });
  res.end(body);
});

const step = (o) => o;
function generate(probe, custom) {
  const out = [];
  out.push({ id: 'smoke', name: 'Smoke', category: 'smoke', steps: [
    step({ action: 'wait', ms: 800 }), step({ expect: 'noErrors' }),
    ...(probe.canvas ? [step({ expect: 'canvasNotBlank' })] : []),
    step({ expect: 'stateAvailable', severity: 'warn' })] });
  const move = ['right', 'left', 'up'].find((a) => probe.actions.includes(a));
  if (move && probe.adapter && probe.stateKeys.includes('player')) out.push({ id: 'movement', name: 'Movement', category: 'movement', steps: [
    step({ action: 'restart' }), step({ action: 'wait', ms: 300 }), step({ action: 'snapshot', as: 'before' }),
    step({ action: 'press', button: move, ms: 600 }), step({ expect: 'changed', path: 'player', from: 'before' }), step({ expect: 'noErrors' })] });
  out.push(...custom);
  out.push({ id: 'ui-layout', name: 'UI layout', category: 'ui', steps: [step({ expect: 'layout', name: 'layout' })] });
  out.push({ id: 'touch', name: 'Touch', category: 'touch', steps: [step({ action: 'tap', x: 0.5, y: 0.5 }), step({ action: 'drag', x1: 0.3, y1: 0.6, x2: 0.6, y2: 0.6, ms: 300 }), step({ expect: 'noErrors' })] });
  out.push({ id: 'restart', name: 'Restart', category: 'restart', steps: [step({ action: 'restart' }), step({ action: 'wait', ms: 400 }),
    ...(probe.stateKeys.includes('gameOver') ? [step({ expect: 'falsy', path: 'gameOver' })] : []), step({ expect: 'noErrors' })] });
  out.push({ id: 'performance', name: 'Performance', category: 'performance', steps: [step({ action: 'sampleFps', ms: 1500, as: 'perf' }), step({ expect: 'fps', min: 20, from: 'perf', name: 'average FPS' })] });
  return out;
}

async function runScenario(page, scn) {
  const id = await page.evaluate((s) => window.__ff.qa.start(JSON.stringify(s)), scn);
  for (let i = 0; i < 300; i++) {
    await page.waitForTimeout(100);
    const r = JSON.parse(await page.evaluate((x) => window.__ff.qa.poll(x), id));
    if (r.done) return r.result;
  }
  return { id: scn.id, status: 'FAIL', checks: [{ name: 'timeout', status: 'FAIL', message: 'timeout' }] };
}

await new Promise((r) => server.listen(0, '127.0.0.1', r));
const port = server.address().port;
const browser = await chromium.launch({
  executablePath: process.env.CHROME || '/opt/pw-browsers/chromium-1194/chrome-linux/chrome',
  args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
});
const summary = [];
let totalFail = 0;
for (const tpl of TEMPLATES) {
  current = tpl;
  for (const vp of [{ w: 360, h: 780, label: 'fold-outer' }, { w: 884, h: 1000, label: 'fold-inner' }]) {
    const page = await browser.newPage({ viewport: { width: vp.w, height: vp.h }, hasTouch: true });
    const consoleErrors = [];
    page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
    page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message));
    // CPU_THROTTLE=N slows the page N× (CDP) to reproduce slow CI runners / low-end devices.
    if (Number(process.env.CPU_THROTTLE) > 1) {
      const cdp = await page.context().newCDPSession(page);
      await cdp.send('Emulation.setCPUThrottlingRate', { rate: Number(process.env.CPU_THROTTLE) });
    }
    // FRAME_COST_MS=N burns N ms in every animation frame to emulate a low-FPS GPU (e.g. software GL on CI).
    if (Number(process.env.FRAME_COST_MS) > 0) {
      await page.addInitScript((ms) => {
        const raf = window.requestAnimationFrame.bind(window);
        window.requestAnimationFrame = (cb) => raf((t) => { const end = performance.now() + ms; while (performance.now() < end); cb(t); });
      }, Number(process.env.FRAME_COST_MS));
    }
    await page.goto(`http://127.0.0.1:${port}/project/index.html`, { waitUntil: 'load' });
    await page.waitForTimeout(1200);
    const probe = JSON.parse(await page.evaluate(() => window.__ff.probe()));
    const custom = JSON.parse(await page.evaluate(() => window.__ff.customScenarios()));
    const results = [];
    for (const s of generate(probe, custom)) results.push(await runScenario(page, s));
    const checks = results.flatMap((r) => r.checks || []);
    const pass = checks.filter((c) => c.status === 'PASS').length;
    const fail = checks.filter((c) => c.status === 'FAIL');
    const warn = checks.filter((c) => c.status === 'WARN').length;
    totalFail += fail.length;
    summary.push({ template: tpl, viewport: vp.label, adapter: probe.adapter, webgl: probe.webgl, scenarios: results.length, pass, fail: fail.length, warn,
      failures: fail.map((f) => `${f.name}: ${f.message}`), consoleErrors: consoleErrors.slice(0, 5) });
    await page.close();
  }
}
await browser.close();
server.close();
for (const s of summary) {
  console.log(`${s.fail ? 'FAIL' : 'PASS'}  ${s.template.padEnd(18)} ${s.viewport.padEnd(10)} adapter=${s.adapter} webgl=${s.webgl} scenarios=${s.scenarios} PASS ${s.pass} FAIL ${s.fail} WARN ${s.warn}`);
  s.failures.forEach((f) => console.log('      - ' + f));
  s.consoleErrors.forEach((e) => console.log('      console: ' + e));
}
fs.writeFileSync(path.join(root, 'tools/webqa/last-report.json'), JSON.stringify(summary, null, 2));
process.exit(totalFail ? 1 : 0);
