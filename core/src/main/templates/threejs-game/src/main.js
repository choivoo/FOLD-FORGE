import * as THREE from 'three';

// --- Renderer, scene, camera
const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.setPixelRatio(Math.min(2, window.devicePixelRatio));
renderer.setSize(window.innerWidth, window.innerHeight);
document.body.appendChild(renderer.domElement);

const scene = new THREE.Scene();
scene.background = new THREE.Color(0x0b0d12);
scene.fog = new THREE.Fog(0x0b0d12, 20, 60);
const camera = new THREE.PerspectiveCamera(60, window.innerWidth / window.innerHeight, 0.1, 200);

// --- Lighting
scene.add(new THREE.HemisphereLight(0xbfd4ff, 0x202030, 0.9));
const sun = new THREE.DirectionalLight(0xffffff, 1.4);
sun.position.set(6, 12, 4);
scene.add(sun);

// --- World
const ground = new THREE.Mesh(new THREE.PlaneGeometry(80, 80), new THREE.MeshStandardMaterial({ color: 0x1d2433 }));
ground.rotation.x = -Math.PI / 2;
scene.add(ground);
scene.add(new THREE.GridHelper(80, 40, 0x334055, 0x232b3a));
const player = new THREE.Mesh(new THREE.BoxGeometry(1, 1, 1), new THREE.MeshStandardMaterial({ color: 0xff8a3d }));
player.position.y = 0.5;
scene.add(player);
for (let i = 0; i < 12; i++) {
  const pillar = new THREE.Mesh(new THREE.CylinderGeometry(0.4, 0.5, 2 + Math.random() * 3, 12), new THREE.MeshStandardMaterial({ color: 0x3fa7ff }));
  pillar.position.set((Math.random() - 0.5) * 40, pillar.geometry.parameters.height / 2, (Math.random() - 0.5) * 40);
  scene.add(pillar);
}

// --- Input (keyboard + touch drag)
const keys = { left: false, right: false, up: false, down: false };
const joy = { x: 0, y: 0 };
const map = { ArrowLeft: 'left', KeyA: 'left', ArrowRight: 'right', KeyD: 'right', ArrowUp: 'up', KeyW: 'up', ArrowDown: 'down', KeyS: 'down' };
addEventListener('keydown', (e) => { if (map[e.code]) keys[map[e.code]] = true; });
addEventListener('keyup', (e) => { if (map[e.code]) keys[map[e.code]] = false; });
let origin = null;
renderer.domElement.addEventListener('pointerdown', (e) => { origin = { x: e.clientX, y: e.clientY }; });
renderer.domElement.addEventListener('pointermove', (e) => {
  if (!origin) return;
  const dx = e.clientX - origin.x, dy = e.clientY - origin.y, len = Math.max(50, Math.hypot(dx, dy));
  joy.x = dx / len; joy.y = dy / len;
});
const end = () => { origin = null; joy.x = 0; joy.y = 0; };
renderer.domElement.addEventListener('pointerup', end);
renderer.domElement.addEventListener('pointercancel', end);

// --- Resize
function onResize() {
  camera.aspect = window.innerWidth / window.innerHeight;
  camera.updateProjectionMatrix();
  renderer.setSize(window.innerWidth, window.innerHeight);
}
addEventListener('resize', onResize);

// --- Loop + FPS counter
const fpsEl = document.getElementById('fps');
let frames = 0, fpsTime = 0, fps = 0;
const clock = new THREE.Clock();
function animate() {
  const dt = Math.min(0.05, clock.getDelta());
  const vx = (keys.right ? 1 : 0) - (keys.left ? 1 : 0) + joy.x;
  const vz = (keys.down ? 1 : 0) - (keys.up ? 1 : 0) + joy.y;
  player.position.x = THREE.MathUtils.clamp(player.position.x + vx * 6 * dt, -38, 38);
  player.position.z = THREE.MathUtils.clamp(player.position.z + vz * 6 * dt, -38, 38);
  player.rotation.y += dt;
  camera.position.set(player.position.x, 8, player.position.z + 10);
  camera.lookAt(player.position);
  renderer.render(scene, camera);
  frames++; fpsTime += dt;
  if (fpsTime >= 0.5) { fps = Math.round(frames / fpsTime); fpsEl.textContent = fps + ' FPS'; frames = 0; fpsTime = 0; }
  requestAnimationFrame(animate);
}
animate();

window.__foldForgeTest = {
  version: 1,
  actions: ['left', 'right', 'up', 'down'],
  getState() {
    return { scene: 'world', player: { x: +player.position.x.toFixed(2), z: +player.position.z.toFixed(2) }, fps, gameOver: false, loading: false };
  },
  pressButton(name, down) { if (name in keys) keys[name] = !!down; },
  moveJoystick(x, y) { joy.x = x; joy.y = y; },
  restart() { player.position.set(0, 0.5, 0); },
  captureMetrics() { return { fps }; },
};
console.log('Three.js r' + THREE.REVISION + ' scene ready');
