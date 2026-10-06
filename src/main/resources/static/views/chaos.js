import { get, post, settings } from '../api.js';
import { h, pill, toast } from '../dom.js';

/**
 * Explains each failpoint and, only when the server runs with ledger.chaos.enabled=true, arms it.
 * Arming makes the server process halt the next time the failpoint is reached.
 */
export async function chaos() {
  const state = await get('/api/v1/chaos/failpoints');
  const rearm = async (id) => {
    if (!confirm(`Arm "${id}"? The server process will halt the next time this point is reached.`)) return;
    try { await post(`/api/v1/chaos/failpoints/${id}/arm`); toast(`${id} armed`); location.reload(); }
    catch (e) { toast(e.message); }
  };
  const disarm = async () => {
    try { await post('/api/v1/chaos/failpoints/disarm'); location.reload(); } catch (e) { toast(e.message); }
  };
  return h('div', {},
    h('h1', {}, 'Chaos'),
    h('p', {}, 'A failpoint is a named place in the money path or the outbox relay where the process can be made to die with Runtime.halt(): no shutdown hooks, no connection close. The crash test suite arms each one in a separate process, retries with the same idempotency key and checks the books.'),
    state.enabled
      ? h('div', { class: 'notice' }, 'Chaos is enabled on this instance. Arming a failpoint kills the server the next time it is reached. With docker compose, the container restarts and the dashboard recovers.')
      : h('div', { class: 'notice' }, 'Chaos is disabled on this instance (ledger.chaos.enabled=false). The list below is documentation only; arming is refused by the server.'),
    state.enabled ? h('button', { class: 'ghost', onclick: disarm }, 'Disarm all') : null,
    state.failpoints.map(f => h('div', { class: `chaos-item ${f.armed ? 'armed' : ''}` },
      h('div', {}, h('strong', {}, f.id), ' ', f.armed ? pill('armed', 'bad') : null),
      h('p', { class: 'muted' }, 'State at the crash: ', f.state),
      h('p', {}, 'Why it is safe: ', f.recovery),
      state.enabled && settings().chaos ? h('button', { class: 'danger', onclick: () => rearm(f.id) }, 'Arm') : null)));
}
