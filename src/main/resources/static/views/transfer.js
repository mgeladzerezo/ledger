import { get, raw, newKey } from '../api.js';
import { h, money, empty } from '../dom.js';

/**
 * The form keeps one Idempotency-Key until the user asks for a new one, so pressing "Send" twice
 * is a retry: the second answer is the stored response of the first, flagged Idempotent-Replayed.
 */
export async function transfer() {
  const page = await get('/api/v1/accounts?limit=200');
  const accounts = page.items.filter(a => !a.system);
  if (accounts.length < 2) return empty('A transfer needs two accounts. Create them through the API, or start with the demo flag.');

  let key = newKey();
  const keyText = h('code', {}, key);
  const from = h('select', { id: 'from' }, accounts.map(a => h('option', { value: a.id }, `${a.code} (${money(a.available, a.currency)})`)));
  const to = h('select', { id: 'to' }, accounts.map((a, i) => h('option', { value: a.id, selected: i === 1 }, a.code)));
  const amount = h('input', { id: 'amount', type: 'number', min: '1', step: '1', value: '1000', inputmode: 'numeric' });
  const log = h('div');

  const send = async () => {
    const source = accounts.find(a => String(a.id) === from.value);
    const body = { fromAccountId: Number(from.value), toAccountId: Number(to.value), amount: Number(amount.value), currency: source.currency };
    const sentKey = key;
    let line;
    try {
      const r = await raw('/api/v1/transfers', { method: 'POST', body, key: sentKey });
      const replayed = r.headers.get('Idempotent-Replayed') === 'true';
      line = h('div', { class: 'card' },
        h('div', {}, h('strong', {}, `HTTP ${r.status}`), replayed ? ' replayed from the stored response, nothing moved a second time' : ' executed'),
        h('div', { class: 'muted' }, 'Idempotency-Key ', h('code', {}, sentKey)),
        h('pre', {}, JSON.stringify(r.body, null, 2)));
    } catch (error) {
      line = h('div', { class: 'card' }, h('strong', {}, 'No response'), ' (network failure). Press Send again: it reuses the same key, so it is safe.');
    }
    log.prepend(line);
  };

  const form = h('form', { class: 'row', onsubmit: e => { e.preventDefault(); send(); } },
    h('label', {}, 'From', from), h('label', {}, 'To', to),
    h('label', {}, 'Amount (minor units)', amount),
    h('button', { type: 'submit' }, 'Send'),
    h('button', { type: 'button', class: 'ghost', onclick: () => { key = newKey(); keyText.textContent = key; } }, 'New key'));

  return h('div', {},
    h('h1', {}, 'Transfer'),
    h('div', { class: 'card' }, form,
      h('p', { class: 'muted', style: 'margin-top:12px' }, 'Idempotency-Key in use: ', keyText),
      h('p', { class: 'muted' }, 'Send the same transfer twice with the same key and the second answer is a replay. Change the amount and send again without a new key to see the 422 for a reused key with a different body.')),
    log);
}
