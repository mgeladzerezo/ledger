import { loadConfig, settings, setKey } from './api.js';
import { failure, loading } from './dom.js';
import { accounts } from './views/accounts.js';
import { transfer } from './views/transfer.js';
import { journal } from './views/journal.js';
import { outbox } from './views/outbox.js';
import { reconciliation } from './views/reconciliation.js';
import { chaos } from './views/chaos.js';

const routes = { accounts, transfer, journal, outbox, reconciliation, chaos };
const view = document.getElementById('view');
let timer = null;
let generation = 0;

async function render() {
  clearInterval(timer);
  const [name, ...args] = (location.hash.slice(1) || 'accounts').split('/');
  const route = routes[name] || accounts;
  document.querySelectorAll('#tabs a').forEach(a =>
    a.getAttribute('href') === `#${routes[name] ? name : 'accounts'}` ? a.setAttribute('aria-current', 'page') : a.removeAttribute('aria-current'));

  const mine = ++generation;
  const draw = async (first) => {
    try {
      if (first) view.replaceChildren(loading());
      const node = await route(args, () => mine === generation);
      if (mine === generation && node) view.replaceChildren(node);
    } catch (error) {
      if (mine === generation && first) view.replaceChildren(failure(error));
    }
  };
  await draw(true);
  if (route.refresh && args.length === 0) timer = setInterval(() => draw(false), route.refresh);
}


async function pollHealth() {
  const pill = document.getElementById('health');
  try {
    const response = await fetch('/actuator/health');
    const up = response.ok;
    pill.textContent = up ? 'healthy' : 'unhealthy';
    pill.className = `pill ${up ? 'ok' : 'bad'}`;
  } catch {
    pill.textContent = 'unreachable';
    pill.className = 'pill bad';
  }
}

function askForKey() {
  const key = prompt('API key (sent as X-API-Key)');
  if (key) { setKey(key); render(); }
}

await loadConfig();
const keyButton = document.getElementById('key-button');
keyButton.hidden = settings().demo;
keyButton.addEventListener('click', askForKey);
if (!settings().apiKey) askForKey();
window.addEventListener('hashchange', render);
pollHealth();
setInterval(pollHealth, 5000);
render();
