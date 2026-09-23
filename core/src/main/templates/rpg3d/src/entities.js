import * as THREE from 'three';
import { clampToWorld, resolveObstacles, WORLD_SIZE } from './world.js';

// Original monster species for this starter (feel free to redesign).
export const SPECIES = [
  { name: 'Mossling', color: 0x7ed957, hp: 30, damage: 6, speed: 2.2, xp: 12, scale: 0.8 },
  { name: 'Emberkit', color: 0xff7a3d, hp: 45, damage: 9, speed: 2.8, xp: 20, scale: 0.9 },
  { name: 'Glimwing', color: 0x8fb8ff, hp: 60, damage: 12, speed: 3.2, xp: 32, scale: 1.0 },
];

export function xpForLevel(level) { return 40 + (level - 1) * 30; }

export function createPlayer(scene) {
  const group = new THREE.Group();
  const body = new THREE.Mesh(new THREE.CapsuleGeometry(0.45, 0.9, 4, 12), new THREE.MeshStandardMaterial({ color: 0x3d7dff }));
  body.position.y = 0.95;
  const head = new THREE.Mesh(new THREE.SphereGeometry(0.35, 16, 12), new THREE.MeshStandardMaterial({ color: 0xffd7b0 }));
  head.position.y = 1.9;
  const sword = new THREE.Mesh(new THREE.BoxGeometry(0.12, 0.12, 1.2), new THREE.MeshStandardMaterial({ color: 0xdfe6f0, metalness: 0.6, roughness: 0.3 }));
  sword.position.set(0.55, 1.1, 0.4);
  group.add(body, head, sword);
  scene.add(group);
  return {
    mesh: group, sword,
    hp: 100, maxHp: 100, level: 1, xp: 0, attackPower: 14,
    speed: 5, facing: 0, attackTimer: 0, attackCooldown: 0, dashTimer: 0, dashCooldown: 0,
    invuln: 0, kills: 0, dead: false,
  };
}

export class MonsterManager {
  constructor(scene, obstacles) {
    this.scene = scene;
    this.obstacles = obstacles;
    this.monsters = [];
    this.respawnTimer = 0;
  }

  spawn(x, z, speciesIndex) {
    const sp = SPECIES[speciesIndex ?? Math.floor(Math.random() * SPECIES.length)];
    const mesh = new THREE.Group();
    const body = new THREE.Mesh(new THREE.SphereGeometry(0.7, 18, 14), new THREE.MeshStandardMaterial({ color: sp.color, roughness: 0.6 }));
    body.scale.set(1, 0.8, 1);
    body.position.y = 0.6;
    const eyeMat = new THREE.MeshStandardMaterial({ color: 0x111111 });
    const e1 = new THREE.Mesh(new THREE.SphereGeometry(0.1, 8, 6), eyeMat);
    const e2 = e1.clone();
    e1.position.set(-0.22, 0.8, 0.58); e2.position.set(0.22, 0.8, 0.58);
    mesh.add(body, e1, e2);
    mesh.scale.setScalar(sp.scale);
    if (x === undefined) {
      const a = Math.random() * Math.PI * 2, d = 12 + Math.random() * (WORLD_SIZE - 14);
      x = Math.cos(a) * d; z = Math.sin(a) * d;
    }
    mesh.position.set(x, 0, z);
    this.scene.add(mesh);
    const m = { mesh, body, species: sp, hp: sp.hp, maxHp: sp.hp, attackCooldown: 0, hitFlash: 0, wanderAngle: Math.random() * 6.28, wanderTimer: 0 };
    this.monsters.push(m);
    return m;
  }

  remove(m) {
    this.scene.remove(m.mesh);
    this.monsters = this.monsters.filter((o) => o !== m);
  }

  clear() { [...this.monsters].forEach((m) => this.remove(m)); }

  update(dt, player, onPlayerHit) {
    const p = player.mesh.position;
    for (const m of this.monsters) {
      const pos = m.mesh.position;
      const dx = p.x - pos.x, dz = p.z - pos.z;
      const dist = Math.hypot(dx, dz);
      m.attackCooldown = Math.max(0, m.attackCooldown - dt);
      if (!player.dead && dist < 9) {
        if (dist > 1.4) {
          pos.x += dx / dist * m.species.speed * dt;
          pos.z += dz / dist * m.species.speed * dt;
        } else if (m.attackCooldown === 0) {
          m.attackCooldown = 1.2;
          onPlayerHit(m);
        }
        m.mesh.rotation.y = Math.atan2(dx, dz);
      } else {
        m.wanderTimer -= dt;
        if (m.wanderTimer <= 0) { m.wanderAngle += (Math.random() - 0.5) * 2; m.wanderTimer = 1 + Math.random() * 2; }
        pos.x += Math.sin(m.wanderAngle) * dt;
        pos.z += Math.cos(m.wanderAngle) * dt;
        m.mesh.rotation.y = m.wanderAngle;
      }
      clampToWorld(pos);
      resolveObstacles(pos, 0.7, this.obstacles);
      m.hitFlash = Math.max(0, m.hitFlash - dt);
      m.body.material.emissive.setHex(m.hitFlash > 0 ? 0xffffff : 0x000000);
      m.body.position.y = 0.6 + Math.abs(Math.sin(performance.now() / 250 + pos.x)) * 0.15;
    }
    this.respawnTimer -= dt;
    if (this.monsters.length < 6 && this.respawnTimer <= 0) { this.spawn(); this.respawnTimer = 4; }
  }
}
