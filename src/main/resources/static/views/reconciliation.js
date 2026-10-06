import { get, post } from '../api.js';
import { h, time, pill, table, empty, toast } from '../dom.js';

const KIND = { CLEAN: 'ok', WARNINGS: 'warn', FAILED: 'bad' };
const SEVERITY = { CRITICAL: 'bad', WARNING: 'warn', INFO: '' };

async function list() {
  const runs = await get('/api/v1/reconciliation/runs?limit=30');
  const run = h('button', { onclick: async () => {
    try { const r = await post('/api/v1/reconciliation/runs'); location.hash = `reconciliation/${r.id}`; }
    catch (e) { toast(e.message); }
  } }, 'Run now');
  return h('div', {},
    h('div', { class: 'row', style: 'justify-content:space-between;align-items:center' }, h('h1', {}, 'Reconciliation'), run),
    h('p', { class: 'muted' }, 'Checks that debits equal credits, every balance equals the sum of its entries, holds fit the balances and every transaction has its outbox event. Runs on a schedule and on demand.'),
    runs.length === 0 ? empty('No report yet.') : table([
      { title: 'Run', num: true, render: r => r.id },
      { title: 'Started', render: r => time(r.startedAt) },
      { title: 'Trigger', render: r => r.trigger.toLowerCase() },
      { title: 'Status', render: r => pill(r.status.toLowerCase(), KIND[r.status]) },
      { title: 'Findings', num: true, render: r => r.findingCount },
    ], runs, { onRow: r => { location.hash = `reconciliation/${r.id}`; } }));
}

async function detail(id) {
  const r = await get(`/api/v1/reconciliation/runs/${id}`);
  return h('div', {},
    h('p', { class: 'crumbs' }, h('a', { href: '#reconciliation' }, 'Reconciliation'), ` / run ${r.id}`),
    h('h1', {}, 'Report ', pill(r.status.toLowerCase(), KIND[r.status])),
    h('p', { class: 'muted' }, `Started ${time(r.startedAt)}, trigger: ${r.trigger.toLowerCase()}`),
    h('h2', {}, 'Checks'),
    table([
      { title: 'Check', render: c => c.name },
      { title: 'Severity', render: c => pill(c.severity.toLowerCase(), SEVERITY[c.severity]) },
      { title: 'What it verifies', render: c => h('span', { class: 'wrap' }, c.description) },
      { title: 'Findings', num: true, render: c => c.findings },
    ], r.checks),
    h('h2', {}, 'Findings'),
    r.findings.length === 0 ? empty('None. The books are consistent.') : table([
      { title: 'Check', render: f => f.check },
      { title: 'Severity', render: f => pill(f.severity.toLowerCase(), SEVERITY[f.severity]) },
      { title: 'Subject', render: f => f.subject },
      { title: 'Detail', render: f => f.detail },
    ], r.findings));
}

export async function reconciliation(args) {
  return args[0] ? detail(args[0]) : list();
}
