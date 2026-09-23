// Unified input: keyboard, on-screen joystick and action buttons.
export function createInput() {
  const input = { x: 0, y: 0, keys: { left: false, right: false, up: false, down: false }, attack: false, dash: false, menu: false, joyX: 0, joyY: 0 };

  const map = { ArrowLeft: 'left', KeyA: 'left', ArrowRight: 'right', KeyD: 'right', ArrowUp: 'up', KeyW: 'up', ArrowDown: 'down', KeyS: 'down' };
  addEventListener('keydown', (e) => {
    if (map[e.code]) { input.keys[map[e.code]] = true; e.preventDefault(); }
    if (e.code === 'KeyJ' || e.code === 'Space') input.attack = true;
    if (e.code === 'ShiftLeft' || e.code === 'ShiftRight') input.dash = true;
    if (e.code === 'Escape') input.menu = true;
  });
  addEventListener('keyup', (e) => { if (map[e.code]) input.keys[map[e.code]] = false; });

  const joystick = document.getElementById('joystick');
  const knob = document.getElementById('knob');
  let active = null;
  function setKnob(dx, dy) { knob.style.transform = `translate(${dx}px, ${dy}px)`; }
  joystick.addEventListener('pointerdown', (e) => { active = e.pointerId; joystick.setPointerCapture?.(e.pointerId); move(e); });
  joystick.addEventListener('pointermove', (e) => { if (active === e.pointerId) move(e); });
  const release = () => { active = null; input.joyX = 0; input.joyY = 0; setKnob(0, 0); };
  joystick.addEventListener('pointerup', release);
  joystick.addEventListener('pointercancel', release);
  function move(e) {
    const r = joystick.getBoundingClientRect();
    let dx = e.clientX - (r.left + r.width / 2);
    let dy = e.clientY - (r.top + r.height / 2);
    const max = r.width / 2;
    const len = Math.hypot(dx, dy);
    if (len > max) { dx = dx / len * max; dy = dy / len * max; }
    input.joyX = dx / max; input.joyY = dy / max;
    setKnob(dx, dy);
  }

  const tapButton = (id, key) => document.getElementById(id).addEventListener('pointerdown', (e) => { e.preventDefault(); input[key] = true; });
  tapButton('attack-btn', 'attack');
  tapButton('dash-btn', 'dash');
  tapButton('menu-btn', 'menu');

  input.axis = () => {
    let x = (input.keys.right ? 1 : 0) - (input.keys.left ? 1 : 0) + input.joyX;
    let y = (input.keys.down ? 1 : 0) - (input.keys.up ? 1 : 0) + input.joyY;
    const len = Math.hypot(x, y);
    if (len > 1) { x /= len; y /= len; }
    return { x, y };
  };
  /** Returns true once per press. */
  input.consume = (key) => { const v = input[key]; input[key] = false; return v; };
  return input;
}
