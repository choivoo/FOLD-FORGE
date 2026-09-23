# {{PROJECT_NAME}}

Playable 3D action RPG starter built on Three.js (bundled offline in `vendor/`).

- **Move:** on-screen joystick, Arrow keys / WASD
- **Attack:** ⚔ button or J · **Dash:** ⇢ button or Shift · **Menu:** ☰ or Esc
- Defeat wild monsters to earn XP and level up. Progress (level/XP) is saved in `localStorage`.

Source layout: `src/main.js` (game loop), `src/world.js`, `src/entities.js`, `src/input.js`, `src/hud.js`, `src/save.js`.
The `window.__foldForgeTest` adapter in `src/main.js` exposes state, input and debug hooks plus
scenario definitions for the FOLD FORGE Gameplay QA Agent.
