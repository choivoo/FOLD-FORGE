import * as THREE from 'three';

export const WORLD_SIZE = 60;

// Builds the explorable meadow: ground, trees, rocks and lighting.
export function createWorld(scene) {
  scene.background = new THREE.Color(0x9fc9ff);
  scene.fog = new THREE.Fog(0x9fc9ff, 30, 80);
  scene.add(new THREE.HemisphereLight(0xdff0ff, 0x3b5a2b, 1.0));
  const sun = new THREE.DirectionalLight(0xfff2d6, 1.6);
  sun.position.set(20, 30, 10);
  scene.add(sun);

  const ground = new THREE.Mesh(
    new THREE.PlaneGeometry(WORLD_SIZE * 2, WORLD_SIZE * 2, 1, 1),
    new THREE.MeshStandardMaterial({ color: 0x4f8a3c }),
  );
  ground.rotation.x = -Math.PI / 2;
  scene.add(ground);

  const obstacles = [];
  const trunkMat = new THREE.MeshStandardMaterial({ color: 0x6b4a2b });
  const leafMat = new THREE.MeshStandardMaterial({ color: 0x2f6b34 });
  const rockMat = new THREE.MeshStandardMaterial({ color: 0x8a8f99, flatShading: true });
  let seed = 7;
  const rnd = () => { seed = (seed * 16807) % 2147483647; return seed / 2147483647; };
  for (let i = 0; i < 40; i++) {
    const x = (rnd() - 0.5) * WORLD_SIZE * 1.8, z = (rnd() - 0.5) * WORLD_SIZE * 1.8;
    if (Math.hypot(x, z) < 8) continue;
    if (rnd() < 0.65) {
      const tree = new THREE.Group();
      const trunk = new THREE.Mesh(new THREE.CylinderGeometry(0.25, 0.35, 1.6, 8), trunkMat);
      trunk.position.y = 0.8;
      const leaves = new THREE.Mesh(new THREE.ConeGeometry(1.3, 2.6, 9), leafMat);
      leaves.position.y = 2.6;
      tree.add(trunk, leaves);
      tree.position.set(x, 0, z);
      scene.add(tree);
      obstacles.push({ x, z, r: 0.6 });
    } else {
      const rock = new THREE.Mesh(new THREE.DodecahedronGeometry(0.6 + rnd() * 0.6), rockMat);
      rock.position.set(x, 0.4, z);
      scene.add(rock);
      obstacles.push({ x, z, r: 0.9 });
    }
  }
  return { obstacles };
}

export function clampToWorld(v) {
  v.x = Math.max(-WORLD_SIZE, Math.min(WORLD_SIZE, v.x));
  v.z = Math.max(-WORLD_SIZE, Math.min(WORLD_SIZE, v.z));
}

export function resolveObstacles(pos, radius, obstacles) {
  for (const o of obstacles) {
    const dx = pos.x - o.x, dz = pos.z - o.z;
    const d = Math.hypot(dx, dz), min = o.r + radius;
    if (d < min && d > 0.0001) { pos.x = o.x + dx / d * min; pos.z = o.z + dz / d * min; }
  }
}
