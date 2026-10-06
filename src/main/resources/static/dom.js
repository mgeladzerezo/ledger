/** Tiny DOM helpers: every piece of server data goes in through textContent, never innerHTML. */

export function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [name, value] of Object.entries(attrs)) {
    if (value === false || value == null) continue;
    if (name.startsWith('on')) el.addEventListener(name.slice(2), value);
    else if (name === 'class') el.className = value;
    else el.setAttribute(name, value === true ? '' : value);
  }
  for (const child of children.flat()) {
    if (child == null || child === false) continue;
    el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return el;
}

/** Minor units to a display amount; every currency in the demo has two decimals. */
export function money(minor, currency) {
  const sign = minor < 0 ? '-' : '';
  const abs = Math.abs(minor);
  const text = `${Math.floor(abs / 100).toLocaleString('en-US')}.${String(abs % 100).padStart(2, '0')}`;
  return currency ? `${sign}${text} ${currency}` : `${sign}${text}`;
}

export function time(iso) {
  if (!iso) return '';
  return new Date(iso).toLocaleString(undefined, { dateStyle: 'short', timeStyle: 'medium' });
}

export function short(id) {
  return id ? String(id).slice(0, 8) : '';
}

export function pill(text, kind = '') {
  return h('span', { class: `pill ${kind}` }, text);
}

export function loading(text = 'Loading') {
  return h('div', { class: 'state' }, h('span', { class: 'spinner' }), text);
}

export function empty(text) {
  return h('div', { class: 'state' }, text);
}

export function failure(error) {
  return h('div', { class: 'state error', role: 'alert' }, error.message || String(error));
}

export function table(columns, rows, { onRow } = {}) {
  const head = h('tr', {}, columns.map(c => h('th', { class: c.num ? 'num' : '' }, c.title)));
  const body = rows.map(row => {
    const tr = h('tr', { class: onRow ? 'click' : '', tabindex: onRow ? '0' : false },
      columns.map(c => h('td', { class: c.num ? 'num' : '' }, c.render(row))));
    if (onRow) {
      tr.addEventListener('click', () => onRow(row));
      tr.addEventListener('keydown', e => { if (e.key === 'Enter') onRow(row); });
    }
    return tr;
  });
  return h('div', { class: 'table-wrap' }, h('table', {}, h('thead', {}, head), h('tbody', {}, body)));
}

export function toast(message) {
  const el = document.getElementById('toast');
  el.textContent = message;
  el.classList.add('show');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => el.classList.remove('show'), 3500);
}
