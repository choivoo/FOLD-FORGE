const N = 4;
const boardEl = document.getElementById('board');
const info = document.getElementById('info');
let tiles, moves;

function solvedBoard() { return [...Array(N * N - 1).keys()].map((i) => i + 1).concat(0); }
function isSolved() { return tiles.every((v, i) => v === solvedBoard()[i]); }

function neighbors(i) {
  const r = Math.floor(i / N), c = i % N, out = [];
  if (r > 0) out.push(i - N); if (r < N - 1) out.push(i + N);
  if (c > 0) out.push(i - 1); if (c < N - 1) out.push(i + 1);
  return out;
}

function shuffle(steps = 200) {
  tiles = solvedBoard();
  let empty = tiles.indexOf(0), prev = -1;
  for (let s = 0; s < steps; s++) {
    const opts = neighbors(empty).filter((n) => n !== prev);
    const pick = opts[Math.floor(Math.random() * opts.length)];
    [tiles[empty], tiles[pick]] = [tiles[pick], tiles[empty]];
    prev = empty; empty = pick;
  }
  moves = 0;
  render();
}

function tryMove(i) {
  const empty = tiles.indexOf(0);
  if (!neighbors(empty).includes(i)) return false;
  [tiles[empty], tiles[i]] = [tiles[i], tiles[empty]];
  moves++;
  render();
  return true;
}

// Direction = direction the tile moves into the empty slot.
function slide(dir) {
  const e = tiles.indexOf(0), r = Math.floor(e / N), c = e % N;
  const from = { up: r < N - 1 ? e + N : -1, down: r > 0 ? e - N : -1, left: c < N - 1 ? e + 1 : -1, right: c > 0 ? e - 1 : -1 }[dir];
  return from >= 0 ? tryMove(from) : false;
}

function render() {
  boardEl.innerHTML = '';
  tiles.forEach((v, i) => {
    const b = document.createElement('button');
    b.className = 'tile' + (v === 0 ? ' empty' : '') + (v === i + 1 ? ' ok' : '');
    b.textContent = v || '';
    b.setAttribute('aria-label', v ? 'Tile ' + v : 'Empty');
    if (v) b.onclick = () => tryMove(i);
    boardEl.appendChild(b);
  });
  const done = isSolved();
  document.body.classList.toggle('win', done);
  info.textContent = done ? `Solved in ${moves} moves!` : `Moves ${moves}`;
}

addEventListener('keydown', (e) => {
  const map = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right' };
  if (map[e.key]) { slide(map[e.key]); e.preventDefault(); }
});
document.getElementById('shuffle').onclick = () => shuffle();
shuffle();

window.__foldForgeTest = {
  version: 1,
  actions: ['up', 'down', 'left', 'right'],
  getState() { return { scene: 'board', moves, solved: isSolved(), board: tiles.slice(), score: isSolved() ? 1000 - moves : 0, gameOver: false, loading: false }; },
  pressButton(name, down) { if (down) slide(name); },
  restart() { shuffle(); },
  debug: {
    nearlySolve() { tiles = solvedBoard(); [tiles[N * N - 1], tiles[N * N - 2]] = [tiles[N * N - 2], tiles[N * N - 1]]; moves = 0; render(); },
  },
  scenarios() {
    return [{
      id: 'puzzle-solve', name: 'Final move solves the puzzle', category: 'gameplay',
      steps: [
        { action: 'debug', call: 'nearlySolve' }, { expect: 'falsy', path: 'solved', name: 'not solved before final move' },
        { action: 'press', button: 'left', ms: 50 }, { action: 'wait', ms: 100 },
        { expect: 'truthy', path: 'solved', name: 'solved after final move' },
        { expect: 'compare', path: 'moves', op: 'eq', value: 1, name: 'move counted' },
      ],
    }];
  },
};
