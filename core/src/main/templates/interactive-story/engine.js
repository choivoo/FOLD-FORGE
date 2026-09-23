const textEl = document.getElementById('text');
const choicesEl = document.getElementById('choices');
let story, current, flags, visited;

async function load() {
  try {
    const res = await fetch('story.json');
    story = await res.json();
  } catch (e) {
    console.error('Could not load story.json', e);
    textEl.textContent = 'Story failed to load.';
    return;
  }
  start();
}

function start() { flags = new Set(); visited = []; go(story.start); }

function go(id) {
  const node = story.nodes[id];
  if (!node) { console.error('Missing story node: ' + id); return; }
  current = id;
  visited.push(id);
  textEl.textContent = node.text;
  choicesEl.innerHTML = '';
  node.choices.filter((c) => !c.requires || flags.has(c.requires)).forEach((c, i) => {
    const b = document.createElement('button');
    b.className = 'btn';
    b.textContent = c.label;
    b.onclick = () => { if (c.set) flags.add(c.set); go(c.to); };
    b.dataset.index = i;
    choicesEl.appendChild(b);
  });
}

document.getElementById('restart').onclick = start;
load();

window.__foldForgeTest = {
  version: 1,
  actions: ['choose'],
  getState() { return { scene: current, visited: visited ? visited.length : 0, flags: flags ? [...flags] : [], ended: !!(story && story.nodes[current] && story.nodes[current].choices.length === 0), gameOver: false, loading: !story }; },
  pressButton(name, down) { if (down && /^\d$/.test(name)) choicesEl.querySelectorAll('button')[+name]?.click(); },
  restart() { start(); },
  scenarios() {
    return [{
      id: 'story-branch', name: 'Story advances and reaches an ending', category: 'gameplay',
      steps: [
        { action: 'restart' }, { action: 'snapshot', as: 's0' },
        { action: 'press', button: '0', ms: 30 }, { expect: 'changed', path: 'scene', from: 's0', name: 'choice advances story' },
        { action: 'press', button: '0', ms: 30 }, { action: 'press', button: '0', ms: 30 },
        { expect: 'truthy', path: 'ended', name: 'ending reached' }, { expect: 'noErrors' },
      ],
    }];
  },
};
