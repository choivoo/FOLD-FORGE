// {{PROJECT_NAME}} — side-scrolling platformer.
// Controls: ←/→ or A/D to run, Space/W/↑ to jump, R to restart. Touch buttons on screen.
const canvas = document.getElementById('game');
const ctx = canvas.getContext('2d');
const GRAVITY = 1800, RUN = 260, JUMP = 640;
const input = { left: false, right: false, jump: false };
let level, player, camX, state;

function buildLevel() {
  const platforms = [{ x: -200, y: 400, w: 900, h: 200 }];
  let x = 760;
  for (let i = 0; i < 14; i++) {
    const w = 140 + (i * 37) % 120;
    const y = 300 + ((i * 53) % 140) - 40;
    platforms.push({ x, y, w, h: 24 });
    x += w + 90 + (i * 29) % 60;
  }
  platforms.push({ x, y: 400, w: 600, h: 200 });
  const coins = platforms.slice(1, -1).map((p) => ({ x: p.x + p.w / 2, y: p.y - 40, taken: false }));
  const goal = { x: x + 400, y: 320, w: 30, h: 80 };
  return { platforms, coins, goal, width: x + 600 };
}

function reset() {
  level = buildLevel();
  player = { x: 40, y: 300, w: 28, h: 40, vx: 0, vy: 0, grounded: false, hp: 1 };
  camX = 0;
  state = { scene: 'play', score: 0, gameOver: false, won: false, deaths: 0 };
}

function resize() {
  canvas.width = innerWidth * devicePixelRatio;
  canvas.height = innerHeight * devicePixelRatio;
}
addEventListener('resize', resize);
resize();

const keys = { ArrowLeft: 'left', KeyA: 'left', ArrowRight: 'right', KeyD: 'right', Space: 'jump', ArrowUp: 'jump', KeyW: 'jump' };
addEventListener('keydown', (e) => { if (keys[e.code]) { input[keys[e.code]] = true; e.preventDefault(); } if (e.code === 'KeyR') reset(); });
addEventListener('keyup', (e) => { if (keys[e.code]) input[keys[e.code]] = false; });
for (const btn of document.querySelectorAll('[data-key]')) {
  const k = btn.dataset.key;
  btn.addEventListener('pointerdown', (e) => { e.preventDefault(); input[k] = true; if (state.gameOver) reset(); });
  const up = () => { input[k] = false; };
  btn.addEventListener('pointerup', up);
  btn.addEventListener('pointerleave', up);
  btn.addEventListener('pointercancel', up);
}

function update(dt) {
  if (state.gameOver) return;
  player.vx = ((input.right ? 1 : 0) - (input.left ? 1 : 0)) * RUN;
  if (input.jump && player.grounded) { player.vy = -JUMP; player.grounded = false; }
  player.vy += GRAVITY * dt;
  player.x += player.vx * dt;
  for (const p of level.platforms) if (overlap(player, p)) player.x = player.vx > 0 ? p.x - player.w : p.x + p.w;
  player.y += player.vy * dt;
  player.grounded = false;
  for (const p of level.platforms) {
    if (overlap(player, p)) {
      if (player.vy > 0) { player.y = p.y - player.h; player.grounded = true; } else { player.y = p.y + p.h; }
      player.vy = 0;
    }
  }
  for (const c of level.coins) {
    if (!c.taken && Math.abs(player.x + player.w / 2 - c.x) < 22 && Math.abs(player.y + player.h / 2 - c.y) < 28) { c.taken = true; state.score += 10; }
  }
  if (overlap(player, level.goal)) { state.won = true; state.gameOver = true; state.scene = 'win'; }
  if (player.y > 900) { state.deaths++; state.gameOver = true; state.scene = 'gameover'; player.hp = 0; }
  camX = Math.max(0, player.x - innerWidth * 0.35);
}

function overlap(a, b) { return a.x < b.x + b.w && a.x + a.w > b.x && a.y < b.y + b.h && a.y + a.h > b.y; }

function draw() {
  ctx.setTransform(devicePixelRatio, 0, 0, devicePixelRatio, 0, 0);
  const sky = ctx.createLinearGradient(0, 0, 0, innerHeight);
  sky.addColorStop(0, '#20305a'); sky.addColorStop(1, '#6a4c7a');
  ctx.fillStyle = sky; ctx.fillRect(0, 0, innerWidth, innerHeight);
  const scale = Math.min(1, innerHeight / 520);
  ctx.save();
  ctx.scale(scale, scale);
  ctx.translate(-camX, 0);
  ctx.fillStyle = '#2d2a3e';
  for (const p of level.platforms) ctx.fillRect(p.x, p.y, p.w, p.h);
  ctx.fillStyle = '#7ee0a1';
  for (const p of level.platforms) ctx.fillRect(p.x, p.y, p.w, 5);
  ctx.fillStyle = '#ffd34d';
  for (const c of level.coins) if (!c.taken) { ctx.beginPath(); ctx.arc(c.x, c.y, 9, 0, Math.PI * 2); ctx.fill(); }
  ctx.fillStyle = '#57e3ff'; ctx.fillRect(level.goal.x, level.goal.y, level.goal.w, level.goal.h);
  ctx.fillStyle = '#ff8a3d'; ctx.fillRect(player.x, player.y, player.w, player.h);
  ctx.restore();
  ctx.fillStyle = '#fff'; ctx.font = '600 16px system-ui';
  ctx.fillText(`Coins ${state.score / 10}`, 14, 26);
  if (state.gameOver) {
    ctx.fillStyle = 'rgba(0,0,0,0.55)'; ctx.fillRect(0, 0, innerWidth, innerHeight);
    ctx.fillStyle = '#fff'; ctx.textAlign = 'center'; ctx.font = 'bold 26px system-ui';
    ctx.fillText(state.won ? 'LEVEL CLEAR! Press R' : 'You fell! Press R', innerWidth / 2, innerHeight / 2);
    ctx.textAlign = 'left';
  }
}

let last = performance.now();
function loop(t) {
  const dt = Math.min(0.033, (t - last) / 1000);
  last = t;
  update(dt);
  draw();
  requestAnimationFrame(loop);
}
reset();
requestAnimationFrame(loop);

window.__foldForgeTest = {
  version: 1,
  actions: ['left', 'right', 'jump'],
  getState() {
    return { scene: state.scene, player: { x: Math.round(player.x), y: Math.round(player.y), grounded: player.grounded, hp: player.hp }, score: state.score, gameOver: state.gameOver, won: state.won, loading: false };
  },
  pressButton(name, down) { if (name in input) input[name] = !!down; },
  moveJoystick(x) { input.left = x < -0.3; input.right = x > 0.3; },
  restart() { reset(); },
  captureMetrics() { return window.__ff ? window.__ff.snapshotMetrics() : {}; },
  debug: { killPlayer() { player.y = 1000; } },
  scenarios() {
    return [{
      id: 'platformer-jump', name: 'Jump leaves the ground and lands', category: 'movement',
      steps: [
        { action: 'restart' }, { action: 'waitUntil', path: 'player.grounded', op: 'eq', value: true, timeout: 20000 },
        { action: 'snapshot', as: 'ground' },
        { expect: 'truthy', path: 'player.grounded', name: 'player starts grounded' },
        { action: 'waitUntil', path: 'player.y', op: 'lt', from: 'ground', press: 'jump', pressMs: 200, timeout: 20000 },
        { expect: 'compare', path: 'player.y', op: 'lt', from: 'ground', name: 'player rises when jumping' },
        { action: 'waitUntil', path: 'player.grounded', op: 'eq', value: true, timeout: 20000 },
        { expect: 'truthy', path: 'player.grounded', name: 'player lands again' },
      ],
    }];
  },
};
