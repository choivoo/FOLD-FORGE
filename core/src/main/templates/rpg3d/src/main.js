import * as THREE from 'three';
import { createWorld, clampToWorld, resolveObstacles } from './world.js';
import { createPlayer, MonsterManager, xpForLevel } from './entities.js';
import { createInput } from './input.js';
import { updateHud, show } from './hud.js';
import { loadSave, writeSave, clearSave } from './save.js';

// ---------------------------------------------------------------- setup
const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.setPixelRatio(Math.min(2, devicePixelRatio));
renderer.setSize(innerWidth, innerHeight);
document.body.prepend(renderer.domElement);

const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(55, innerWidth / innerHeight, 0.1, 200);
const { obstacles } = createWorld(scene);
const player = createPlayer(scene);
const monsters = new MonsterManager(scene, obstacles);
const input = createInput();
const game = { paused: false, gameOver: false, scene: 'world', time: 0 };

const saved = loadSave();
if (saved) { player.level = saved.level; player.xp = saved.xp; player.kills = saved.kills || 0; applyLevelStats(); player.hp = player.maxHp; }
for (let i = 0; i < 5; i++) monsters.spawn();

function applyLevelStats() {
  player.maxHp = 100 + (player.level - 1) * 15;
  player.attackPower = 14 + (player.level - 1) * 4;
}

function respawnPlayer() {
  player.dead = false;
  player.hp = player.maxHp;
  player.mesh.position.set(0, 0, 0);
  player.invuln = 1.5;
  game.gameOver = false;
  game.scene = 'world';
  show('gameover', false);
}

function newGame() {
  clearSave();
  player.level = 1; player.xp = 0; player.kills = 0;
  applyLevelStats();
  monsters.clear();
  for (let i = 0; i < 5; i++) monsters.spawn();
  respawnPlayer();
  setPaused(false);
}

function setPaused(p) {
  game.paused = p;
  game.scene = p ? 'menu' : (game.gameOver ? 'gameover' : 'world');
  show('menu', p);
}

document.getElementById('resume-btn').addEventListener('click', () => setPaused(false));
document.getElementById('reset-btn').addEventListener('click', () => newGame());
document.getElementById('retry-btn').addEventListener('click', () => respawnPlayer());

addEventListener('resize', () => {
  camera.aspect = innerWidth / innerHeight;
  camera.updateProjectionMatrix();
  renderer.setSize(innerWidth, innerHeight);
});

// ---------------------------------------------------------------- combat
function gainXp(amount) {
  player.xp += amount;
  while (player.xp >= xpForLevel(player.level)) {
    player.xp -= xpForLevel(player.level);
    player.level++;
    applyLevelStats();
    player.hp = player.maxHp;
    console.log(`Level up! Now level ${player.level}`);
  }
  writeSave(player);
}

function performAttack() {
  if (player.attackCooldown > 0 || player.dead) return;
  player.attackCooldown = 0.35;
  player.attackTimer = 0.2;
  const p = player.mesh.position;
  // Aim assist: face the nearest monster in range.
  let target = null, best = 2.6;
  for (const m of monsters.monsters) {
    const d = m.mesh.position.distanceTo(p);
    if (d < best) { best = d; target = m; }
  }
  if (!target) return;
  player.facing = Math.atan2(target.mesh.position.x - p.x, target.mesh.position.z - p.z);
  target.hp -= player.attackPower;
  target.hitFlash = 0.12;
  if (target.hp <= 0) {
    monsters.remove(target);
    player.kills++;
    gainXp(target.species.xp);
  }
}

function onPlayerHit(monster) {
  if (player.invuln > 0 || player.dead) return;
  player.hp -= monster.species.damage;
  player.invuln = 0.4;
  if (player.hp <= 0) killPlayer();
}

function killPlayer() {
  player.hp = 0;
  player.dead = true;
  game.gameOver = true;
  game.scene = 'gameover';
  show('gameover', true);
}

// ---------------------------------------------------------------- loop
const clock = new THREE.Clock();
const camOffset = new THREE.Vector3(0, 9, 9);
function update(dt) {
  if (input.consume('menu')) setPaused(!game.paused);
  if (game.paused || game.gameOver) return;
  game.time += dt;
  const axis = input.axis();
  player.attackCooldown = Math.max(0, player.attackCooldown - dt);
  player.dashCooldown = Math.max(0, player.dashCooldown - dt);
  player.dashTimer = Math.max(0, player.dashTimer - dt);
  player.invuln = Math.max(0, player.invuln - dt);
  if (input.consume('dash') && player.dashCooldown === 0) { player.dashTimer = 0.2; player.dashCooldown = 1; player.invuln = Math.max(player.invuln, 0.25); }
  if (input.consume('attack')) performAttack();

  const speed = player.speed * (player.dashTimer > 0 ? 3 : 1);
  const pos = player.mesh.position;
  if (Math.hypot(axis.x, axis.y) > 0.05) {
    pos.x += axis.x * speed * dt;
    pos.z += axis.y * speed * dt;
    player.facing = Math.atan2(axis.x, axis.y);
  } else if (player.dashTimer > 0) {
    pos.x += Math.sin(player.facing) * speed * dt;
    pos.z += Math.cos(player.facing) * speed * dt;
  }
  clampToWorld(pos);
  resolveObstacles(pos, 0.5, obstacles);
  player.mesh.rotation.y = player.facing;
  player.sword.rotation.x = player.attackTimer > 0 ? -1.2 : 0;
  player.attackTimer = Math.max(0, player.attackTimer - dt);
  player.mesh.visible = !(player.invuln > 0 && Math.floor(player.invuln * 20) % 2);
  if (player.hp < player.maxHp) player.hp = Math.min(player.maxHp, player.hp + dt * 1.5);
  monsters.update(dt, player, onPlayerHit);
}

function render() {
  const target = player.mesh.position;
  camera.position.lerp(new THREE.Vector3().copy(target).add(camOffset), 0.15);
  camera.lookAt(target.x, 1, target.z);
  renderer.render(scene, camera);
  updateHud(player, monsters.monsters.length);
}

function frame() {
  const dt = Math.min(0.05, clock.getDelta());
  update(dt);
  render();
  requestAnimationFrame(frame);
}
camera.position.copy(camOffset);
frame();

// ---------------------------------------------------------------- FOLD FORGE QA adapter
function nearestEnemyDist() {
  let best = Infinity;
  for (const m of monsters.monsters) best = Math.min(best, m.mesh.position.distanceTo(player.mesh.position));
  return best === Infinity ? null : +best.toFixed(2);
}

window.__foldForgeTest = {
  version: 1,
  actions: ['left', 'right', 'up', 'down', 'attack', 'dash', 'menu'],
  getState() {
    const p = player.mesh.position;
    return {
      scene: game.scene, paused: game.paused, gameOver: game.gameOver, loading: false,
      player: { x: +p.x.toFixed(2), z: +p.z.toFixed(2), hp: Math.ceil(player.hp), maxHp: player.maxHp, level: player.level, xp: player.xp, attackPower: player.attackPower },
      enemies: monsters.monsters.length, nearestEnemy: nearestEnemyDist(), kills: player.kills, score: player.kills * 10 + player.xp,
    };
  },
  pressButton(name, down) {
    if (name in input.keys) { input.keys[name] = !!down; return; }
    if (down && (name === 'attack' || name === 'a')) input.attack = true;
    if (down && (name === 'dash' || name === 'x')) input.dash = true;
    if (down && name === 'menu') input.menu = true;
  },
  moveJoystick(x, y) { input.joyX = x; input.joyY = y; },
  restart() { respawnPlayer(); setPaused(false); },
  captureMetrics() { return window.__ff ? window.__ff.snapshotMetrics() : {}; },
  debug: {
    spawnEnemyNear() {
      const p = player.mesh.position;
      monsters.spawn(p.x + Math.sin(player.facing) * 1.6, p.z + Math.cos(player.facing) * 1.6, 0);
    },
    killPlayer,
    setHp(v) { player.hp = v; },
    newGame,
  },
  scenarios() {
    return [
      {
        id: 'rpg-combat', name: 'Combat: defeat a monster and gain XP', category: 'combat',
        steps: [
          { action: 'restart' }, { action: 'wait', ms: 300 }, { action: 'snapshot', as: 'before' },
          { action: 'debug', call: 'spawnEnemyNear' }, { action: 'wait', ms: 200 },
          { action: 'press', button: 'attack', ms: 80 }, { action: 'wait', ms: 400 },
          { action: 'press', button: 'attack', ms: 80 }, { action: 'wait', ms: 400 },
          { action: 'press', button: 'attack', ms: 80 }, { action: 'wait', ms: 400 },
          { expect: 'compare', path: 'kills', op: 'gt', from: 'before', name: 'monster defeated' },
          { expect: 'changed', path: 'score', from: 'before', name: 'XP / score awarded' },
          { expect: 'noErrors' },
        ],
      },
      {
        id: 'rpg-menu', name: 'Menu: pause and resume', category: 'ui',
        steps: [
          { action: 'restart' }, { action: 'press', button: 'menu', ms: 80 }, { action: 'wait', ms: 200 },
          { expect: 'truthy', path: 'paused', name: 'game paused' },
          { expect: 'layout', name: 'pause menu layout' },
          { action: 'press', button: 'menu', ms: 80 }, { action: 'wait', ms: 200 },
          { expect: 'falsy', path: 'paused', name: 'game resumed' },
        ],
      },
      {
        id: 'rpg-death', name: 'Death and restart', category: 'death',
        steps: [
          { action: 'restart' }, { action: 'debug', call: 'killPlayer' }, { action: 'wait', ms: 200 },
          { expect: 'truthy', path: 'gameOver', name: 'game over shown' },
          { action: 'restart' }, { action: 'wait', ms: 300 },
          { expect: 'falsy', path: 'gameOver', name: 'restarted after death' },
          { expect: 'compare', path: 'player.hp', op: 'gt', value: 0, name: 'HP restored' },
          { expect: 'noErrors' },
        ],
      },
    ];
  },
};
console.log('{{PROJECT_NAME}} ready — Three.js r' + THREE.REVISION);
