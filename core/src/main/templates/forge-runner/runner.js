// Forge Runner — FOLD FORGE demo game.
const canvas = document.getElementById('game');
const ctx = canvas.getContext('2d');
const scoreEl = document.getElementById('score');
const GROUND = 0.78;
const input = { left: false, right: false, jumpQueued: false };
let s;

function reset() {
  s = { t: 0, speed: 320, dist: 0, score: 0, best: s ? s.best : 0, gameOver: false, obstacles: [], spawnIn: 1.2,
        player: { x: 90, y: 0, vy: 0, w: 34, h: 44, grounded: true, jumps: 0 } };
}
function resize() { canvas.width = innerWidth * devicePixelRatio; canvas.height = innerHeight * devicePixelRatio; }
addEventListener('resize', resize);
resize();

function jump() {
  if (s.gameOver) { reset(); return; }
  if (s.player.grounded) { s.player.vy = -760; s.player.grounded = false; s.player.jumps++; }
}
addEventListener('keydown', (e) => {
  if (e.code === 'Space' || e.code === 'ArrowUp' || e.code === 'KeyW') { input.jumpQueued = true; e.preventDefault(); }
  if (e.code === 'ArrowLeft' || e.code === 'KeyA') input.left = true;
  if (e.code === 'ArrowRight' || e.code === 'KeyD') input.right = true;
  if (e.code === 'KeyR') reset();
});
addEventListener('keyup', (e) => {
  if (e.code === 'ArrowLeft' || e.code === 'KeyA') input.left = false;
  if (e.code === 'ArrowRight' || e.code === 'KeyD') input.right = false;
});
canvas.addEventListener('pointerdown', () => { input.jumpQueued = true; });
document.getElementById('jump').addEventListener('pointerdown', (e) => { e.preventDefault(); input.jumpQueued = true; });

function update(dt) {
  if (input.jumpQueued) { input.jumpQueued = false; jump(); }
  if (s.gameOver) return;
  const p = s.player, groundY = innerHeight * GROUND;
  s.t += dt;
  s.speed = Math.min(720, 320 + s.t * 8) * (input.right ? 1.25 : input.left ? 0.8 : 1);
  s.dist += s.speed * dt;
  s.score = Math.floor(s.dist / 10);
  p.vy += 2200 * dt;
  p.y += p.vy * dt;
  if (p.y >= 0) { p.y = 0; p.vy = 0; p.grounded = true; }
  s.spawnIn -= dt;
  if (s.spawnIn <= 0) {
    const tall = Math.random() < 0.35;
    s.obstacles.push({ x: innerWidth + 40, w: tall ? 26 : 40, h: tall ? 70 : 38 });
    s.spawnIn = 0.9 + Math.random() * 1.1;
  }
  for (const o of s.obstacles) o.x -= s.speed * dt;
  s.obstacles = s.obstacles.filter((o) => o.x + o.w > -20);
  const px = p.x, py = groundY + p.y - p.h;
  for (const o of s.obstacles) {
    if (px + p.w - 6 > o.x && px + 6 < o.x + o.w && py + p.h > groundY - o.h) {
      s.gameOver = true;
      s.best = Math.max(s.best, s.score);
      console.log('Run ended — score ' + s.score);
    }
  }
}

function draw() {
  const groundY = innerHeight * GROUND;
  ctx.setTransform(devicePixelRatio, 0, 0, devicePixelRatio, 0, 0);
  const g = ctx.createLinearGradient(0, 0, 0, innerHeight);
  g.addColorStop(0, '#141a2e'); g.addColorStop(1, '#2b1d2e');
  ctx.fillStyle = g; ctx.fillRect(0, 0, innerWidth, innerHeight);
  ctx.fillStyle = '#1f2436';
  for (let i = 0; i < 8; i++) {
    const x = ((i * 220 - s.dist * 0.3) % (innerWidth + 220) + innerWidth + 220) % (innerWidth + 220) - 110;
    ctx.fillRect(x, groundY - 120 - (i % 3) * 40, 90, 120 + (i % 3) * 40);
  }
  ctx.fillStyle = '#ff8a3d'; ctx.fillRect(0, groundY, innerWidth, 4);
  ctx.fillStyle = '#20263a'; ctx.fillRect(0, groundY + 4, innerWidth, innerHeight - groundY);
  ctx.fillStyle = '#ff4d6d';
  for (const o of s.obstacles) ctx.fillRect(o.x, groundY - o.h, o.w, o.h);
  const p = s.player;
  ctx.fillStyle = '#57e3ff';
  ctx.fillRect(p.x, groundY + p.y - p.h, p.w, p.h);
  scoreEl.textContent = s.score + (s.best ? '  ·  best ' + s.best : '');
  if (s.gameOver) {
    ctx.fillStyle = 'rgba(0,0,0,0.55)'; ctx.fillRect(0, 0, innerWidth, innerHeight);
    ctx.fillStyle = '#fff'; ctx.textAlign = 'center'; ctx.font = 'bold 28px system-ui';
    ctx.fillText('CRASH! Score ' + s.score, innerWidth / 2, innerHeight / 2 - 10);
    ctx.font = '16px system-ui'; ctx.fillText('Tap or press Space to run again', innerWidth / 2, innerHeight / 2 + 22);
    ctx.textAlign = 'left';
  }
}

let last = performance.now();
function loop(t) { const dt = Math.min(0.033, (t - last) / 1000); last = t; update(dt); draw(); requestAnimationFrame(loop); }
reset();
requestAnimationFrame(loop);

window.__foldForgeTest = {
  version: 1,
  actions: ['jump', 'left', 'right'],
  getState() {
    return { scene: s.gameOver ? 'gameover' : 'run', player: { y: Math.round(s.player.y), grounded: s.player.grounded, jumps: s.player.jumps }, score: s.score, obstacles: s.obstacles.length, speed: Math.round(s.speed), gameOver: s.gameOver, loading: false };
  },
  pressButton(name, down) { if (name === 'jump' && down) input.jumpQueued = true; if (name === 'left' || name === 'right') input[name] = !!down; },
  restart() { reset(); },
  captureMetrics() { return window.__ff ? window.__ff.snapshotMetrics() : {}; },
  debug: { killPlayer() { s.gameOver = true; }, clearObstacles() { s.obstacles = []; s.spawnIn = 3; } },
  scenarios() {
    return [{
      id: 'runner-core', name: 'Runner: score grows and jump works', category: 'gameplay',
      steps: [
        { action: 'restart' }, { action: 'debug', call: 'clearObstacles' }, { action: 'snapshot', as: 'start' },
        { action: 'wait', ms: 600 }, { expect: 'compare', path: 'score', op: 'gt', from: 'start', name: 'score increases while running' },
        { action: 'press', button: 'jump', ms: 60 }, { action: 'wait', ms: 120 },
        { expect: 'compare', path: 'player.y', op: 'lt', value: 0, name: 'player is airborne after jump' },
        { action: 'wait', ms: 800 }, { expect: 'truthy', path: 'player.grounded', name: 'player lands' },
        { expect: 'noErrors' },
      ],
    }];
  },
};
