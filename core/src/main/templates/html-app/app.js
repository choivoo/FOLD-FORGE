const KEY = 'foldforge-notes';
const list = document.getElementById('list');
const input = document.getElementById('input');
const count = document.getElementById('count');
let notes = [];
try { notes = JSON.parse(localStorage.getItem(KEY) || '[]'); } catch (e) { notes = []; }

function save() { localStorage.setItem(KEY, JSON.stringify(notes)); }

function render() {
  list.innerHTML = '';
  notes.forEach((text, i) => {
    const li = document.createElement('li');
    const span = document.createElement('span');
    span.textContent = text;
    const del = document.createElement('button');
    del.textContent = '✕';
    del.setAttribute('aria-label', 'Delete note');
    del.onclick = () => { notes.splice(i, 1); save(); render(); };
    li.append(span, del);
    list.appendChild(li);
  });
  count.textContent = notes.length + (notes.length === 1 ? ' note' : ' notes');
}

document.getElementById('form').addEventListener('submit', (e) => {
  e.preventDefault();
  const text = input.value.trim();
  if (!text) return;
  notes.unshift(text);
  input.value = '';
  save();
  render();
});

render();
console.log('Notes loaded:', notes.length);
