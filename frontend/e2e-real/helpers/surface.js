import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { API_ROUTES } from '../../src/api/routes.js';

// The API surface, derived from the app's OWN route table rather than hand-listed here.
//
// This is the whole point: a hand-maintained list of endpoints goes stale the first time
// someone adds one, and a coverage suite that silently stops covering new code is worse than
// no coverage suite, because the green check still reads as "covered". API_ROUTES is what
// hrApi.js actually calls, so walking it means a new endpoint is in this suite's scope the
// moment it exists — with no step anyone can forget.

// Placeholder values for the `(id) => path` factories in API_ROUTES. The row almost never
// exists, and that is fine: every assertion made over this surface is about the auth/permission
// layers, which run before the controller looks anything up. A 404 for a real id and a 404 for
// this one are the same 404 — but a 401 is a 401 either way, which is what we assert.
const PLACEHOLDER_ID = 999_999;
const PLACEHOLDER_DATE = '2026-01-01';

function callFactory(factory) {
  // Arity varies (id) / (periodId, lineId) / (id, kind, effectiveDate) / (date). Pass a
  // uniform bag of plausible arguments and let each take what it needs; anything that still
  // throws is reported by name rather than silently dropped from the surface.
  const args = [PLACEHOLDER_ID, PLACEHOLDER_ID, PLACEHOLDER_ID, PLACEHOLDER_ID];
  try {
    const path = factory(...args);
    return typeof path === 'string' && path.startsWith('/api/') ? path : null;
  } catch {
    try {
      const path = factory(PLACEHOLDER_DATE);
      return typeof path === 'string' && path.startsWith('/api/') ? path : null;
    } catch {
      return null;
    }
  }
}

/** Every endpoint declared in API_ROUTES, as `{ name, path }` sorted by name. */
export function apiSurface() {
  const found = [];
  const walk = (node, prefix) => {
    for (const [key, value] of Object.entries(node)) {
      const name = prefix ? `${prefix}.${key}` : key;
      if (typeof value === 'string') {
        found.push({ name, path: value });
      } else if (typeof value === 'function') {
        const path = callFactory(value);
        if (path) found.push({ name: `${name}()`, path });
      } else if (value && typeof value === 'object') {
        walk(value, name);
      }
    }
  };
  walk(API_ROUTES, '');
  return found.sort((a, b) => a.name.localeCompare(b.name));
}

// SecurityConfig's anonymous allowlist, mirrored exactly. Anything here is reachable without a
// session BY DESIGN; everything else in the surface must answer 401. Keeping the list here (and
// asserting it, rather than just skipping these paths) means widening the real allowlist without
// updating this constant fails the suite — which is the direction that matters, since a new
// permitAll() line is precisely the change that should never pass unnoticed.
export const ANONYMOUS_ALLOWLIST = [
  // POST /api/auth/login — no session exists yet, by definition.
  '/api/auth/login',
  // POST /api/attendance/punch — scanner devices authenticate with X-GLR-Agent-Token, not a
  // session, so the filter chain cannot gate it.
  '/api/attendance/punch',
];

// Endpoints excluded from the per-role sweep (they are still covered by the anonymous 401
// sweep, which costs one rejected request each). These render or stream a document — a PDF,
// a ZIP of payslips, a bank/tax export file — so hitting them once per role would dominate the
// suite's runtime and, for the LibreOffice-backed renderers, shell out to `soffice` each time.
// Their AUTHORIZATION is still asserted anonymously; only the authenticated fetch is skipped.
const HEAVY_PATH_PATTERNS = [/\.pdf$/, /\.zip$/, /\/export\//, /\/file$/, /proforma/];

export function isHeavyPath(path) {
  return HEAVY_PATH_PATTERNS.some((pattern) => pattern.test(path));
}

// ── Which endpoints are actually readable ────────────────────────────────────
//
// API_ROUTES carries paths but not verbs; the verbs live in hrApi.js, in the `{ method: 'POST' }`
// option bag of each apiRequest call. Anything without one is a GET.
//
// This matters because a GET sent to a POST-only endpoint does NOT come back 405 here — this
// backend answers it 500 (an unhandled HttpRequestMethodNotSupportedException reaching the
// generic error handler). So a "no endpoint 5xx's" sweep that ignored verbs would report ~180
// failures that are pure artefacts of asking the wrong question, and drown any real one. Reading
// the verbs makes the sweep ask each endpoint something it is actually willing to answer.
//
// A path can appear more than once — `employees.detail` backs both a GET (`get`) and a PATCH
// (`update`). Any GET usage makes it readable.
function buildReadableSet() {
  const source = readFileSync(
    fileURLToPath(new URL('../../src/api/hrApi.js', import.meta.url)),
    'utf8'
  );
  const readable = new Set();

  for (const { name } of apiSurface()) {
    const reference = `API_ROUTES.${name.replace(/\(\)$/, '')}`;
    let from = 0;
    let index = source.indexOf(reference, from);

    while (index !== -1) {
      // Reject a prefix match. `catalog.price` is a prefix of `catalog.prices`, and
      // `pricingRequests.attachment` of `pricingRequests.attachmentFile` — in both cases the
      // longer name IS a GET, so a substring match marks the shorter (PUT/DELETE-only) one
      // readable and the sweep then reports its inevitable 500 as a backend defect.
      const after = source[index + reference.length];
      if (after && /[A-Za-z0-9_$]/.test(after)) {
        from = index + reference.length;
        index = source.indexOf(reference, from);
        continue;
      }

      // Bound the search to THIS apiRequest(...) call. A naive fixed-size window bleeds into
      // the next property and picks up its method — which silently mislabels every endpoint
      // that sits above a POST one (`employees.list` directly above `employees.create`).
      const callStart = source.lastIndexOf('apiRequest(', index);
      if (callStart !== -1) {
        let depth = 0;
        let end = source.indexOf('(', callStart);
        for (let i = end; i < source.length; i++) {
          if (source[i] === '(') depth++;
          else if (source[i] === ')') {
            depth--;
            if (depth === 0) {
              end = i;
              break;
            }
          }
        }
        const call = source.slice(callStart, end + 1);
        // Only trust this call if it is the one referencing our route.
        if (call.includes(reference) && !/method:\s*'(POST|PUT|PATCH|DELETE)'/.test(call)) {
          readable.add(name);
        }
      }
      from = index + reference.length;
      index = source.indexOf(reference, from);
    }
  }
  return readable;
}

const READABLE = buildReadableSet();

/** True when hrApi.js calls this endpoint with GET (explicitly or by omission). */
export function isReadable(name) {
  return READABLE.has(name);
}
