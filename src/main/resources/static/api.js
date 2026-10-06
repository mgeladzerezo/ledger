/** Client for the ledger API. Reads the demo key from /ui/config, otherwise asks for one. */

const STORAGE = 'ledger.apiKey';
let config = { demo: false, chaos: false, apiKey: null, consumerVisible: false };

export class ApiError extends Error {
  constructor(problem, status) {
    super(problem.detail || problem.title || `HTTP ${status}`);
    this.status = status;
    this.problem = problem;
  }
}

function stored() {
  try { return sessionStorage.getItem(STORAGE); } catch { return null; }
}

export function setKey(key) {
  try { sessionStorage.setItem(STORAGE, key); } catch { /* storage may be blocked; the key then lasts until reload */ }
  config.apiKey = key;
}

export async function loadConfig() {
  try {
    const response = await fetch('/ui/config');
    if (response.ok) config = await response.json();
  } catch { /* the health pill reports an unreachable server */ }
  config.apiKey = config.apiKey || stored();
  return config;
}

export const settings = () => config;

/** @returns {{status:number, headers:Headers, body:any}} for any HTTP status; throws only when the network fails */
export async function raw(path, { method = 'GET', body, key } = {}) {
  const headers = { 'X-API-Key': config.apiKey || '' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (key) headers['Idempotency-Key'] = key;
  const response = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await response.text();
  let parsed = null;
  try { parsed = text ? JSON.parse(text) : null; } catch { parsed = { title: text }; }
  return { status: response.status, headers: response.headers, body: parsed };
}

export async function call(path, options) {
  const result = await raw(path, options);
  if (result.status >= 400) throw new ApiError(result.body || {}, result.status);
  return result.body;
}

export const get = path => call(path);
export const post = (path, body, key) => call(path, { method: 'POST', body: body ?? {}, key });

export function newKey() {
  return crypto.randomUUID();
}
