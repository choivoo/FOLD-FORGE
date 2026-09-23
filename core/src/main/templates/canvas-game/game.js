// {{PROJECT_NAME}} — top-down canvas game. Collect orbs, avoid drones.
// Controls: Arrows/WASD to move, touch-drag anywhere for a virtual joystick, R to restart.
const canvas = document.getElementById('game');
const ctx = canvas.getContext('2d');
const hud = document.getElementById('hud');

const input = { left: false, right: false, up: false, down: false, jx: 0, jy: 0 };
let state;

function resize() {
  canvas.width = Math.floor(window.innerWidth * devicePixelRatio);
  canvas.height = Math.floor(window.innerHeight * devicePixelRatio);
  ctx.setTransform(devicePixelRatio, 0, 0, devicePixelRatio, 0, 0);
}
window.addEventListener('resize', resize);
resize();

function rand(min, max) { return min + Math.random() * (max - min); }

function reset() {
  state = {
    scene: 'play',
    player: { x: innerWidth / 2, y: innerHeight / 2, r: 14, speed: 220, hp: 3, maxHp: 3, invuln: 0 },
    orbs: [],
    drones: [],
    score: 0,
    time: 0,
    gameOver: false,
  };
  for (let i = 0; i < 6; i++) spawnOrb();
  for (let i = 0; i < 3; i++) spawnDrone();
}

function spawnOrb() { state.orbs.push({ x: rand(30, innerWidth - 30), y: rand(60, innerHeight - 30), r: 8 }); }
function spawnDrone() {
  const edge = Math.random() < 0.5 ? 0 : innerWidth;
  state.drones.push({ x: edge, y: rand(40, innerHeight - 40), r: 12, speed: rand(50, 90) });
}

const keyMap = { ArrowLeft: 'left', KeyA: 'left', ArrowRight: 'right', KeyD: 'right', ArrowUp: 'up', KeyW: 'up', ArrowDown: 'down', KeyS: 'down' };
window.addEventListener('keydown', (e) => {
  if (keyMap[e.code]) { input[keyMap[e.code]] = true; e.preventDefault(); }
  if (e.code === 'KeyR') reset();
});
window.addEventListener('keyup', (e) => { if (keyMap[e.code]) input[keyMap[e.code]] = false; });

// Touch joystick: drag relative to touch start.
let touchOrigin = null;
canvas.addEventListener('pointerdown', (e) => { touchOrigin = { x: e.clientX, y: e.clientY }; if (state.gameOver) reset(); });
canvas.addEventListener('pointermove', (e) => {
  if (!touchOrigin) return;
  const dx = e.clientX - touchOrigin.x, dy = e.clientY - touchOrigin.y;
  const len = Math.max(40, Math.hypot(dx, dy));
  input.jx = dx / len; input.jy = dy / len;
});
const endTouch = () => { touchOrigin = null; input.jx = 0; input.jy = 0; };
canvas.addEventListener('pointerup', endTouch);
canvas.addEventListener('pointercancel', endTouch);

function update(dt) {
  if (state.gameOver) return;
  state.time += dt;
  const p = state.player;
  let vx = (input.right ? 1 : 0) - (input.left ? 1 : 0) + input.jx;
  let vy = (input.down ? 1 : 0) - (input.up ? 1 : 0) + input.jy;
  const len = Math.hypot(vx, vy);
  if (len > 1) { vx /= len; vy /= len; }
  p.x = Math.min(innerWidth - p.r, Math.max(p.r, p.x + vx * p.speed * dt));
  p.y = Math.min(innerHeight - p.r, Math.max(p.r + 40, p.y + vy * p.speed * dt));
  p.invuln = Math.max(0, p.invuln - dt);

  state.orbs = state.orbs.filter((o) => {
    if (Math.hypot(o.x - p.x, o.y - p.y) < o.r + p.r) { state.score += 10; return false; }
    return true;
  });
  while (state.orbs.length < 6) spawnOrb();
  if (state.drones.length < 3 + Math.floor(state.score / 100)) spawnDrone();

  for (const d of state.drones) {
    const a = Math.atan2(p.y - d.y, p.x - d.x);
    d.x += Math.cos(a) * d.speed * dt;
    d.y += Math.sin(a) * d.speed * dt;
    if (p.invuln === 0 && Math.hypot(d.x - p.x, d.y - p.y) < d.r + p.r) {
      p.hp -= 1;
      p.invuln = 1.2;
      d.x = d.x < innerWidth / 2 ? 0 : innerWidth;
      if (p.hp <= 0) { state.gameOver = true; state.scene = 'gameover'; }
    }
  }
}

function draw() {
  ctx.fillStyle = '#0d1017';
  ctx.fillRect(0, 0, innerWidth, innerHeight);
  ctx.strokeStyle = '#1a2030';
  for (let x = 0; x < innerWidth; x += 40) { ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, innerHeight); ctx.stroke(); }
  for (let y = 0; y < innerHeight; y += 40) { ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(innerWidth, y); ctx.stroke(); }
  for (const o of state.orbs) { ctx.fillStyle = '#57e3ff'; ctx.beginPath(); ctx.arc(o.x, o.y, o.r, 0, Math.PI * 2); ctx.fill(); }
  for (const d of state.drones) { ctx.fillStyle = '#ff4d6d'; ctx.fillRect(d.x - d.r, d.y - d.r, d.r * 2, d.r * 2); }
  const p = state.player;
  ctx.globalAlpha = p.invuln > 0 && Math.floor(p.invuln * 10) % 2 ? 0.4 : 1;
  ctx.fillStyle = '#ffb347';
  ctx.beginPath(); ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2); ctx.fill();
  ctx.globalAlpha = 1;
  if (state.gameOver) {
    ctx.fillStyle = 'rgba(0,0,0,0.6)'; ctx.fillRect(0, 0, innerWidth, innerHeight);
    ctx.fillStyle = '#fff'; ctx.font = 'bold 28px system-ui'; ctx.textAlign = 'center';
    ctx.fillText('GAME OVER — tap or press R', innerWidth / 2, innerHeight / 2);
  }
  hud.textContent = `Score ${state.score}   HP ${'♥'.repeat(Math.max(0, p.hp))}`;
}

let last = performance.now();
function loop(t) {
  const dt = Math.min(0.05, (t - last) / 1000);
  last = t;
  update(dt);
  draw();
  requestAnimationFrame(loop);
}
reset();
requestAnimationFrame(loop);

// FOLD FORGE test adapter: lets the QA agent read state and drive the game precisely.
window.__foldForgeTest = {
  version: 1,
  actions: ['left', 'right', 'up', 'down'],
  getState() {
    const p = state.player;
    return { scene: state.scene, player: { x: Math.round(p.x), y: Math.round(p.y), hp: p.hp, maxHp: p.maxHp }, enemies: state.drones.length, score: state.score, gameOver: state.gameOver, loading: false };
  },
  pressButton(name, down) { if (name in input) input[name] = !!down; },
  moveJoystick(x, y) { input.jx = x; input.jy = y; },
  restart() { reset(); },
  captureMetrics() { return window.__ff ? window.__ff.snapshotMetrics() : {}; },
  debug: {
    killPlayer() { state.player.hp = 0; state.gameOver = true; state.scene = 'gameover'; },
    placeOrbNearPlayer() { const p = state.player; state.orbs.push({ x: p.x + 40, y: p.y, r: 8 }); },
  },
  scenarios() {
    return [{
      id: 'collect-orb', name: 'Collect an orb', category: 'gameplay',
      steps: [
        { action: 'restart' }, { action: 'snapshot', as: 'before' },
        { action: 'debug', call: 'placeOrbNearPlayer' }, { action: 'press', button: 'right', ms: 500 },
        { expect: 'compare', path: 'score', op: 'gt', from: 'before', name: 'score increases after collecting orb' },
        { expect: 'noErrors' },
      ],
    }];
  },
};
