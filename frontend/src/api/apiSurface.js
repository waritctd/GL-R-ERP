import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { API_ROUTES } from './routes.js';

// The API surface, resolved on BOTH sides so the two can be compared.
//
// The backend side comes from `docs/api/api-surface.json`, which ApiSurfaceContractTest generates
// from springdoc's /v3/api-docs — Spring's own handler mappings, i.e. what the app will actually
// dispatch. That test fails if the committed digest drifts, so this module is never reading a
// stale transcription.
//
// The frontend side is resolved from routes.js + hrApi.js here. It replaces the previous approach
// of treating API_ROUTES *as* the surface, which was the bug: API_ROUTES is a consumer of the API,
// so an endpoint no route happened to call was invisible, and `tickets.action(id, action)`
// collapsed ~22 real deal actions into one path that matches no controller at all.
//
// Node-only (readFileSync): used by vitest and by the Playwright suite, never shipped to a browser.

const HTTP_VERBS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'];

/** Distinct sentinels per argument position — position matters, see resolveCallSites. */
const SENTINELS = ['__A1__', '__A2__', '__A3__', '__A4__'];

const read = (relative) =>
  readFileSync(fileURLToPath(new URL(relative, import.meta.url)), 'utf8');

/** Collapse `{id}` / `{ticketId}` / a sentinel to a single `{}` so both sides compare equal. */
export function normalisePath(path) {
  return path
    .split('?')[0]
    .replace(/\{[^}]*\}/g, '{}')
    .replace(/__A\d+__/g, '{}')
    .replace(/\/+/g, '/')
    .replace(/(.)\/$/, '$1');
}

/** Every operation the backend serves, as `{ verb, path, key }`. */
export function backendOperations() {
  const digest = JSON.parse(read('../../../docs/api/api-surface.json'));
  return digest.operations.map((entry) => {
    const [verb, path] = entry.split(' ');
    return { verb, path, key: `${verb} ${normalisePath(path)}`, raw: entry };
  });
}

/**
 * Every `API_ROUTES` entry, as a path TEMPLATE with one sentinel per parameter.
 *
 * A factory is called with named sentinels rather than a uniform placeholder so the argument
 * POSITION survives into the template — which is what lets resolveCallSites put a literal like
 * 'cancel' into the slot it was actually passed in.
 */
export function routeTemplates() {
  const found = [];
  const walk = (node, prefix) => {
    for (const [key, value] of Object.entries(node)) {
      const name = prefix ? `${prefix}.${key}` : key;
      if (typeof value === 'string') {
        found.push({ name, template: value });
      } else if (typeof value === 'function') {
        let template;
        try {
          template = value(...SENTINELS);
        } catch {
          template = undefined; // factory needs a shape we cannot synthesise; reported by caller
        }
        if (typeof template === 'string' && template.startsWith('/api/')) {
          found.push({ name, template });
        } else {
          found.push({ name, template: null });
        }
      } else if (value && typeof value === 'object') {
        walk(value, name);
      }
    }
  };
  walk(API_ROUTES, '');
  return found;
}

/**
 * Every call hrApi.js makes, resolved to a concrete `{ verb, path }`.
 *
 * Two things this has to get right, both of which the previous verb-map got wrong:
 *
 *  1. **hrApi.js reaches the API two ways.** `apiRequest(route, { method })` for JSON, and a bare
 *     `fetch(route, { credentials: 'include' })` for the ~17 blob downloads (payslip PDFs,
 *     quotation xlsx, bank/tax exports). Scanning only apiRequest misses every download endpoint
 *     and reports it as dead backend surface — it accounted for 22 of the 47 "uncalled" endpoints
 *     when this was first measured.
 *
 *  2. **Literal factory arguments are positional.** `tickets.action(id, 'cancel')` must resolve to
 *     `/api/tickets/{}/cancel`. Substituting the first free sentinel instead yields
 *     `/api/tickets/cancel/{}` — a path that matches nothing, turning all 22 deal actions into
 *     phantom mismatches.
 */
export function frontendCallSites() {
  const source = read('./hrApi.js');
  const templates = routeTemplates();
  const calls = [];

  for (const { name, template } of templates) {
    if (!template) continue;
    const reference = `API_ROUTES.${name}`;
    let index = source.indexOf(reference);

    while (index !== -1) {
      // Reject a prefix match: `catalog.price` is a prefix of `catalog.prices`, and
      // `pricingRequests.attachment` of `pricingRequests.attachmentFile`.
      const after = source[index + reference.length];
      if (after && /[A-Za-z0-9_$]/.test(after)) {
        index = source.indexOf(reference, index + reference.length);
        continue;
      }

      const enclosing = enclosingCall(source, index);
      const insideRequest = enclosing && enclosing.text.includes(reference);
      // Not inside apiRequest/fetch means hrApi is handing the raw URL to the caller — the four
      // `…Url:` helpers (attachments.fileUrl, specialMoney.attachmentDownloadUrl,
      // pricingRequests.attachmentUrl / factoryQuoteAttachmentUrl) return a plain string used as
      // an <a href>. The browser issues a GET when the user clicks it, so the endpoint IS
      // reached — just not by this module's own code. Treating these as uncalled would have
      // written four false "dead endpoint" claims into the reconciliation's allowlist.
      const explicit = insideRequest
        ? enclosing.text.match(/method:\s*'(POST|PUT|PATCH|DELETE)'/)
        : null;
      // Both apiRequest() and fetch() default to GET when no method is given, as does an href.
      const verb = explicit ? explicit[1] : 'GET';
      let path = template;
      literalArgumentsAt(source, index).forEach((literal, position) => {
        if (literal !== null && position < SENTINELS.length) {
          path = path.split(SENTINELS[position]).join(literal);
        }
      });
      calls.push({
        name,
        verb,
        path: normalisePath(path),
        key: `${verb} ${normalisePath(path)}`,
        via: insideRequest ? 'request' : 'url-helper',
      });
      index = source.indexOf(reference, index + reference.length);
    }
  }
  return calls;
}

/** The `apiRequest(...)` or `fetch(...)` call that encloses `index`, with balanced parens. */
function enclosingCall(source, index) {
  const start = Math.max(source.lastIndexOf('apiRequest(', index), source.lastIndexOf('fetch(', index));
  if (start === -1) return null;
  let depth = 0;
  for (let i = source.indexOf('(', start); i < source.length; i++) {
    if (source[i] === '(') depth += 1;
    else if (source[i] === ')') {
      depth -= 1;
      if (depth === 0) return { text: source.slice(start, i + 1) };
    }
  }
  return null;
}

/**
 * The argument list of the route factory invoked at `index`, one entry per position:
 * a string for a literal, `null` for a variable (an id, which stays a sentinel).
 */
function literalArgumentsAt(source, index) {
  const match = source.slice(index, index + 300).match(/^[^(]*\(([^)]*)\)/);
  if (!match) return [];
  return match[1].split(',').map((argument) => {
    const literal = argument.trim().match(/^'([^']*)'$/);
    return literal ? literal[1] : null;
  });
}

export { HTTP_VERBS };
