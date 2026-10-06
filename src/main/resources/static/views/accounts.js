import { get } from '../api.js';
import { h, money, time, short, pill, table, empty } from '../dom.js';

async function allAccounts() {
  const out = [];
  let cursor = '';
  do {
    const page = await get(`/api/v1/accounts?limit=200${cursor}`);
    out.push(...page.items);
    cursor = page.nextCursor ? `&cursor=${page.nextCursor}` : '';
  } while (cursor);
  return out;
}

async function list() {
  const [accounts, trial] = await Promise.all([allAccounts(), get('/api/v1/reports/trial-balance')]);
  const stats = trial.totals.map(t => h('div', { class: 'card stat' },
    h('div', { class: 'label' }, `Trial balance ${t.currency}`),
    h('div', { class: 'value' }, money(t.debitBalance, t.currency)),
    t.balanced ? pill('debits = credits', 'ok') : pill('OUT OF BALANCE', 'bad')));
  const customers = accounts.filter(a => !a.system);
  return h('div', {},
    h('h1', {}, 'Accounts'),
    h('div', { class: 'grid' }, stats),
    customers.length === 0 ? empty('No accounts yet. Create one through the API or start with the demo flag.') :
      table([
        { title: 'Code', render: a => a.code },
        { title: 'Name', render: a => a.name },
        { title: 'Type', render: a => a.type.toLowerCase() },
        { title: 'Balance', num: true, render: a => money(a.balance, a.currency) },
        { title: 'Held', num: true, render: a => a.held ? money(a.held, a.currency) : '' },
        { title: 'Available', num: true, render: a => money(a.available, a.currency) },
        { title: 'Status', render: a => pill(a.status.toLowerCase(), a.status === 'OPEN' ? 'ok' : 'warn') },
      ], customers, { onRow: a => { location.hash = `accounts/${a.id}`; } }),
    h('p', { class: 'muted', style: 'margin-top:12px' },
      'System accounts (cash, clearing) are in the trial balance totals but hidden from this list. Updates every 4 seconds.'));
}

async function detail(id, isCurrent) {
  const account = await get(`/api/v1/accounts/${id}`);
  const body = h('tbody');
  const more = h('button', { class: 'ghost more' }, 'Load more');
  let cursor = '';
  async function load() {
    const page = await get(`/api/v1/accounts/${id}/entries?limit=25${cursor}`);
    if (!isCurrent()) return;
    for (const { entry, kind, description } of page.items) {
      body.append(h('tr', { class: 'click', onclick: () => { location.hash = `journal/${entry.transactionId}`; } },
        h('td', {}, time(entry.createdAt)),
        h('td', {}, kind.toLowerCase()),
        h('td', {}, entry.side.toLowerCase()),
        h('td', { class: 'num' }, money(entry.amount, entry.currency)),
        h('td', { class: 'wrap' }, description || ''),
        h('td', { class: 'mono' }, short(entry.transactionId))));
    }
    cursor = page.nextCursor ? `&cursor=${page.nextCursor}` : '';
    more.hidden = !page.nextCursor;
  }
  more.addEventListener('click', load);
  await load();
  return h('div', {},
    h('p', { class: 'crumbs' }, h('a', { href: '#accounts' }, 'Accounts'), ` / ${account.code}`),
    h('h1', {}, account.name),
    h('div', { class: 'grid' },
      stat('Balance', money(account.balance, account.currency)),
      stat('Held by authorisations', money(account.held, account.currency)),
      stat('Available', money(account.available, account.currency))),
    h('h2', {}, 'Entries, newest first'),
    h('div', { class: 'table-wrap' }, h('table', {},
      h('thead', {}, h('tr', {}, ['Time', 'Kind', 'Side', 'Amount', 'Description', 'Transaction'].map((t, i) => h('th', { class: i === 3 ? 'num' : '' }, t)))),
      body)),
    more);
}

function stat(label, value) {
  return h('div', { class: 'card stat' }, h('div', { class: 'label' }, label), h('div', { class: 'value' }, value));
}

export async function accounts(args, isCurrent) {
  return args[0] ? detail(args[0], isCurrent) : list();
}
accounts.refresh = 4000;
