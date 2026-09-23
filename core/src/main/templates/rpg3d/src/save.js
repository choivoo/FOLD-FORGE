const KEY = 'foldforge-rpg-save-v1';

export function loadSave() {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return null;
    const data = JSON.parse(raw);
    if (typeof data.level !== 'number' || typeof data.xp !== 'number') return null;
    return data;
  } catch (e) {
    console.warn('Save data unreadable, starting fresh', e);
    return null;
  }
}

export function writeSave(player) {
  try {
    localStorage.setItem(KEY, JSON.stringify({ level: player.level, xp: player.xp, kills: player.kills, savedAt: Date.now() }));
  } catch (e) {
    console.warn('Could not save progress', e);
  }
}

export function clearSave() {
  try { localStorage.removeItem(KEY); } catch (e) { /* ignore */ }
}
