import { get, post, newKey } from '../api.js';
import { h, money, time, short, pill, table, empty, toast } from '../dom.js';

async function list(isCurrent) {
  const rows = [];
  const holder = h('div');
  const more = h('button', { class: 'ghost more' }, 'Load more');
  let cursor = '';
  async function load() {
    const page = await get(`/api/v1/transactions?limit=30${cursor}`);
    if (!isCurrent()) return;
    rows.push(...page.items);
    cursor = page.nextCursor ? `&cursor=${page.nextCursor}` : '';
    more.hidden = !page.nextCursor;
    holder.replaceChildren(rows.length === 0 ? empty('The journal is empty.') : table([
      { title: '#', num: true, render: t => t.seq },
      { title: 'Time', render: t => time(t.createdAt) },
      { title: 'Kind', render: t => t.kind.toLowerCase() },
      { title: 'Description', render: t => t.description },
      { title: 'Debits', num: true, render: t => money(t.entries.filter(e => e.side === 'DEBIT').reduce((s, e) => s + e.amount, 0), t.entries[0]?.currency) },
      { title: 'Id', render: t => h('span', { class: 'mono' }, short(t.id)) },
    ], rows, { onRow: t => { location.hash = `journal/${t.id}`; } }));
  }
  more.addEventListener('click', load);
  await load();
  return h('div', {}, h('h1', {}, 'Journal'), holder, more);
}

async function detail(id) {
  const t = await get(`/api/v1/transactions/${id}`);
  const reverse = h('button', { class: 'ghost', onclick: async () => {
    try {
      const reversal = await post(`/api/v1/transactions/${id}/reversals`, { reason: 'reversed from the dashboard' }, newKey());
      toast('Reversal posted');
      location.hash = `journal/${reversal.id}`;
    } catch (e) { toast(e.message); }
  } }, 'Reverse');
  const link = (label, tid) => h('a', { href: `#journal/${tid}` }, `${label} ${short(tid)}`);
  return h('div', {},
    h('p', { class: 'crumbs' }, h('a', { href: '#journal' }, 'Journal'), ` / ${short(t.id)}`),
    h('h1', {}, t.description),
    h('div', { class: 'card' },
      h('div', {}, pill(t.kind.toLowerCase()), ' ', t.reversedBy ? pill('reversed', 'warn') : null),
      h('p', { class: 'muted' }, `${time(t.createdAt)} by ${t.createdBy}`),
      t.idempotencyKey ? h('p', {}, 'Idempotency-Key ', h('code', {}, t.idempotencyKey)) : null,
      t.reversesId ? h('p', {}, link('Reverses', t.reversesId)) : null,
      t.reversedBy ? h('p', {}, link('Reversed by', t.reversedBy)) : null,
      t.reversesId || t.reversedBy ? null : reverse),
    h('h2', {}, 'Entries (append-only)'),
    table([
      { title: 'Line', num: true, render: e => e.lineNo },
      { title: 'Account', render: e => h('a', { href: `#accounts/${e.accountId}` }, `account ${e.accountId}`) },
      { title: 'Side', render: e => e.side.toLowerCase() },
      { title: 'Amount', num: true, render: e => money(e.amount, e.currency) },
    ], t.entries));
}

export async function journal(args, isCurrent) {
  return args[0] ? detail(args[0]) : list(isCurrent);
}
