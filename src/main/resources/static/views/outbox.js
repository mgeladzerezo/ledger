import { get, post, settings } from '../api.js';
import { h, time, short, pill, table, empty, toast } from '../dom.js';

const KIND = { SENT: 'ok', PENDING: 'warn', DEAD: 'bad' };

function stat(label, value, note) {
  return h('div', { class: 'card stat' }, h('div', { class: 'label' }, label), h('div', { class: 'value' }, value),
    note ? h('div', { class: 'muted' }, note) : null);
}

export async function outbox() {
  const [stats, deliveries, consumer] = await Promise.all([
    get('/api/v1/outbox/stats'),
    get('/api/v1/outbox/deliveries?limit=40'),
    settings().consumerVisible ? get('/api/v1/demo/consumer').catch(() => null) : null,
  ]);
  const cards = [
    stat('Events', stats.events),
    stat('Pending', stats.pending, stats.oldestPendingSeconds == null ? 'nothing waiting' : `oldest ${Math.round(stats.oldestPendingSeconds)} s`),
    stat('Sent', stats.sent),
    stat('Dead-lettered', stats.dead),
  ];
  if (consumer) {
    cards.push(stat('Consumer received', consumer.deliveriesAccepted, `${consumer.uniqueEvents} unique, ${consumer.duplicates} duplicates discarded`));
  }
  return h('div', {},
    h('h1', {}, 'Outbox and deliveries'),
    h('p', { class: 'muted' }, 'Every committed transaction writes an event in the same database transaction. The relay delivers it at least once; the consumer deduplicates by event id. Updates every 3 seconds.'),
    h('div', { class: 'grid' }, cards),
    deliveries.items.length === 0 ? empty('No deliveries yet.') : table([
      { title: 'Seq', num: true, render: d => d.sequence },
      { title: 'Event', render: d => h('span', { class: 'mono' }, short(d.eventId)) },
      { title: 'Type', render: d => d.eventType },
      { title: 'Status', render: d => pill(d.status.toLowerCase(), KIND[d.status]) },
      { title: 'Attempts', num: true, render: d => d.attempts },
      { title: 'Last', render: d => d.lastError || (d.lastStatus ? `HTTP ${d.lastStatus}` : '') },
      { title: 'Sent', render: d => time(d.sentAt) },
      { title: '', render: d => d.status === 'DEAD' ? h('button', { class: 'ghost', onclick: async () => {
        try { await post(`/api/v1/outbox/deliveries/${d.id}/retry`); toast('Requeued'); } catch (e) { toast(e.message); }
      } }, 'Retry') : '' },
    ], deliveries.items));
}
outbox.refresh = 3000;
