const content = document.getElementById('content');
const data = {
  home: [['Welcome', 'This is a responsive mobile UI shell.'], ['Fold ready', 'Cards reflow from 1 to 3 columns on unfolded screens.'], ['Theming', 'Tap ◐ to toggle light and dark.']],
  explore: [['Discover', 'Put your feed, maps or catalog here.'], ['Search', 'Wire a search field to your data.']],
  profile: [['Account', 'Profile details and settings.']],
};
let activeTab = 'home';
function render(tab) {
  activeTab = tab;
  content.innerHTML = data[tab].map(([t, d]) => `<article class="card"><h2>${t}</h2><p>${d}</p></article>`).join('');
  document.querySelectorAll('.tab').forEach((b) => b.classList.toggle('active', b.dataset.tab === tab));
}
document.querySelectorAll('.tab').forEach((b) => b.addEventListener('click', () => render(b.dataset.tab)));
document.getElementById('theme').onclick = () => document.body.classList.toggle('light');
render('home');
window.__foldForgeTest = {
  version: 1, actions: ['home', 'explore', 'profile'],
  getState() { return { scene: activeTab, cards: content.children.length, light: document.body.classList.contains('light'), gameOver: false, loading: false }; },
  pressButton(name, down) { if (down && data[name]) render(name); },
  restart() { render('home'); },
};
