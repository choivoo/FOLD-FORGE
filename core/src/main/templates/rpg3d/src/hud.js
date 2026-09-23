import { xpForLevel } from './entities.js';

const hpFill = document.getElementById('hp-fill');
const hpText = document.getElementById('hp-text');
const xpFill = document.getElementById('xp-fill');
const xpText = document.getElementById('xp-text');
const stats = document.getElementById('stats');

export function updateHud(player, monsters) {
  hpFill.style.width = `${Math.max(0, player.hp / player.maxHp) * 100}%`;
  hpText.textContent = `HP ${Math.max(0, Math.ceil(player.hp))} / ${player.maxHp}`;
  xpFill.style.width = `${(player.xp / xpForLevel(player.level)) * 100}%`;
  xpText.textContent = `Lv ${player.level} · XP ${player.xp}/${xpForLevel(player.level)}`;
  stats.textContent = `Defeated ${player.kills} · Nearby ${monsters}`;
}

export function show(id, visible) {
  document.getElementById(id).classList.toggle('hidden', !visible);
}
