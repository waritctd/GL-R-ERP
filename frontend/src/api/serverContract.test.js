import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { describe, expect, it, vi } from 'vitest';

/**
 * THE GUARD THIS FILE EXISTS TO ADD — hrApi.js against the SPRING CONTROLLERS.
 *
 * `contract.test.js` already pins mockApi.js against hrApi.js in both directions. Nothing pinned
 * hrApi.js against the thing that actually answers its requests. A server endpoint added, removed,
 * renamed, or re-verbed had no tripwire at all: the frontend kept calling the old path, every
 * mock-driven test stayed green (the mock answers whatever hrApi asks), and the only place the
 * disagreement showed up was a 404 in production. That is the same unguarded-mirror family this
 * repo has been closing all week — `WIN_PROBABILITY_DEFAULTS` vs `WinProbabilityDefaults.java`,
 * `stageMeta.js` vs `DealStage` — one layer up.
 *
 * TWO DIRECTIONS, DELIBERATELY ASYMMETRIC:
 *
 *   1. client → server  (`hrApi calls no endpoint that does not exist`)  — HARD FAIL, no allowlist.
 *      A call with no endpoint behind it is already broken. There is no legitimate reason to have
 *      one, so there is no way to excuse one.
 *
 *   2. server → client  (`every endpoint is called, or listed in SERVER_ONLY`) — allowlisted.
 *      An endpoint with no hrApi caller is NOT automatically dead. It may be reached by the Python
 *      scanner agents, a script, the e2e suite, or a documented curl operation; or it may be a
 *      capability built backend-first whose UI has not landed. Deleting one on the strength of "no
 *      hrApi caller" would be a production break. So this direction reports and requires a written
 *      reason rather than assuming litter.
 *
 * WHY THE CLIENT SIDE IS INSTRUMENTED RATHER THAN PARSED. hrApi.js does not hold paths; it composes
 * them — `withQuery(...)`, `API_ROUTES.tickets.action(id, 'close/confirm')`, template literals with
 * an appended `?reason=`, and a dozen bare `fetch()` calls for blob downloads and multipart uploads.
 * A regex over the source would have to re-implement all of that and would silently under-report the
 * moment someone composed a path a new way — failing GREEN, which is the failure mode this file is
 * here to prevent. Mocking `./client.js` and `globalThis.fetch` and then CALLING every method means
 * the captured set is what the code actually issues. `assertEveryMethodProbed` below then fails if
 * any method produced no observable request at all, so a method this harness cannot drive is a loud
 * error rather than a quiet omission from the comparison.
 *
 * WHY THE SERVER SIDE IS PARSED RATHER THAN INTROSPECTED. Spring's own
 * `RequestMappingHandlerMapping` would be authoritative, but it lives in the JVM, and the comparison
 * has to happen somewhere. Parsing Java from vitest is the precedent already set by
 * `dealTrackingMeta.test.js` and `stageCatalog.test.js`. The parse is kept narrow and every
 * assumption it makes is asserted (see `parser sanity`), so a rotted regex fails loudly.
 */

// ─────────────────────────────────────────────────────────────────────────────
// Reading the backend source
// ─────────────────────────────────────────────────────────────────────────────

const CONTROLLER_ROOT = 'backend/src/main/java';

/**
 * Walk up from the working directory to the repo root and resolve a backend path. Deliberately
 * THROWS when it cannot be found rather than skipping — a guard that quietly disables itself when
 * the layout moves is the same silent failure it was written to prevent. Same idiom as
 * `dealTrackingMeta.test.js#readBackendSource`.
 */
function resolveBackendPath(relativePath) {
  let dir = process.cwd();
  for (let depth = 0; depth < 8; depth += 1) {
    const candidate = resolve(dir, relativePath);
    if (existsSync(candidate)) return candidate;
    const parent = dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  throw new Error(
    `could not find ${relativePath} above ${process.cwd()} — this guard compares hrApi.js against `
    + 'the Spring controllers and must not silently skip',
  );
}

function controllerFiles() {
  const root = resolveBackendPath(CONTROLLER_ROOT);
  const found = [];
  const walk = (dir) => {
    for (const name of readdirSync(dir)) {
      const path = join(dir, name);
      if (statSync(path).isDirectory()) walk(path);
      else if (name.endsWith('Controller.java')) found.push(path);
    }
  };
  walk(root);
  return found.sort();
}

// ─────────────────────────────────────────────────────────────────────────────
// Parsing the controllers
// ─────────────────────────────────────────────────────────────────────────────

const VERBS = {
  GetMapping: 'GET',
  PostMapping: 'POST',
  PutMapping: 'PUT',
  PatchMapping: 'PATCH',
  DeleteMapping: 'DELETE',
};

/**
 * Blank out comments and preserve string literals, so an annotation quoted inside Javadoc — this
 * codebase's class comments are full of them — is never read as a live mapping.
 */
function stripComments(source) {
  let out = '';
  let i = 0;
  while (i < source.length) {
    if (source.startsWith('/*', i)) {
      const end = source.indexOf('*/', i + 2);
      const stop = end === -1 ? source.length : end + 2;
      // Keep newlines so offsets and line structure survive.
      out += source.slice(i, stop).replace(/[^\n]/g, ' ');
      i = stop;
    } else if (source.startsWith('//', i)) {
      const end = source.indexOf('\n', i);
      const stop = end === -1 ? source.length : end;
      out += ' '.repeat(stop - i);
      i = stop;
    } else if (source[i] === '"') {
      let j = i + 1;
      while (j < source.length && source[j] !== '"') {
        if (source[j] === '\\') j += 1;
        j += 1;
      }
      out += source.slice(i, j + 1);
      i = j + 1;
    } else {
      out += source[i];
      i += 1;
    }
  }
  return out;
}

/** The balanced `(...)` starting at `open`, returned as `[argsText, closingIndex]`. */
function readParens(source, open) {
  let depth = 0;
  for (let i = open; i < source.length; i += 1) {
    const ch = source[i];
    if (ch === '"') {
      i += 1;
      while (i < source.length && source[i] !== '"') {
        if (source[i] === '\\') i += 1;
        i += 1;
      }
      continue;
    }
    if (ch === '(') depth += 1;
    else if (ch === ')') {
      depth -= 1;
      if (depth === 0) return [source.slice(open + 1, i), i];
    }
  }
  return ['', open];
}

/**
 * The path out of a mapping annotation's argument list. Handles `("/x")`, `(value = "/x", ...)`,
 * `(path = "/x")` and `()`. Deliberately returns '' for an annotation whose only argument is a
 * non-path attribute — `@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)` is a mapping on
 * the class-level path, not on a subpath.
 */
function pathFromAnnotationArgs(args) {
  if (!args || !args.trim()) return '';
  const named = args.match(/(?:^|[,(\s])(?:value|path)\s*=\s*"([^"]*)"/);
  if (named) return named[1];
  const trimmed = args.trim();
  if (trimmed.startsWith('"')) return trimmed.match(/^"([^"]*)"/)?.[1] ?? '';
  return '';
}

/** The declared method name following an annotation: skip further annotations, then read the identifier before `(`. */
function handlerNameAfter(source, from) {
  let i = from;
  for (;;) {
    while (i < source.length && /\s/.test(source[i])) i += 1;
    if (source[i] !== '@') break;
    i += 1;
    while (i < source.length && /[\w.]/.test(source[i])) i += 1;
    while (i < source.length && /\s/.test(source[i])) i += 1;
    if (source[i] === '(') i = readParens(source, i)[1] + 1;
  }
  let generics = 0;
  for (let j = i; j < source.length; j += 1) {
    if (source[j] === '<') generics += 1;
    else if (source[j] === '>') generics -= 1;
    else if (source[j] === '(' && generics === 0) {
      return source.slice(i, j).match(/(\w+)\s*$/)?.[1] ?? '?';
    }
  }
  return '?';
}

/**
 * Whether a `@Deprecated` annotation sits immediately before this mapping.
 *
 * This matters more than it looks. V141 severed three costing write routes: they are still routed,
 * still return a well-formed response, and are `@Deprecated` stubs that throw 409 unconditionally.
 * A path-and-verb comparison sees nothing wrong — the endpoint genuinely exists — so without this
 * the guard would call a permanently-failing call site "matched". Comments are already stripped, so
 * a `{@code @Deprecated}` inside Javadoc cannot be mistaken for the real annotation.
 */
function isDeprecatedAt(source, mappingIndex) {
  const boundary = Math.max(
    source.lastIndexOf(';', mappingIndex),
    source.lastIndexOf('}', mappingIndex),
    source.lastIndexOf('{', mappingIndex),
  );
  return /@Deprecated\b/.test(source.slice(boundary + 1, mappingIndex));
}

/** Every `{ verb, path, controller, handler, deprecated }` declared across the Spring controllers. */
function parseControllers() {
  const endpoints = [];
  const methodLevelRequestMappings = [];

  for (const file of controllerFiles()) {
    const controller = file.split('/').pop().replace('.java', '');
    const source = stripComments(readFileSync(file, 'utf8'));

    // The class declaration itself, NOT its preceding annotations — the class-level
    // @RequestMapping sits between them, so anchoring on the annotations would skip it and
    // every path in the file would lose its /api/... prefix.
    const classIndex = source.match(/\bclass\s+\w+[^;{]*\{/)?.index ?? source.length;

    let base = '';
    const requestMapping = /@RequestMapping\s*(\()?/g;
    let match;
    while ((match = requestMapping.exec(source)) !== null) {
      if (match.index > classIndex) {
        // A method-level @RequestMapping would carry its verb in `method = RequestMethod.X`,
        // which this parser does not read. None exist today; record it so the sanity test can
        // fail loudly rather than this parser silently dropping the endpoint.
        methodLevelRequestMappings.push(`${controller} @ offset ${match.index}`);
        continue;
      }
      base = match[1] ? pathFromAnnotationArgs(readParens(source, match.index + match[0].length - 1)[0]) : '';
    }

    const verbMapping = /@(GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping)\s*(\()?/g;
    while ((match = verbMapping.exec(source)) !== null) {
      let sub = '';
      let after = match.index + match[0].length;
      if (match[2]) {
        const [args, close] = readParens(source, match.index + match[0].length - 1);
        sub = pathFromAnnotationArgs(args);
        after = close + 1;
      }
      endpoints.push({
        verb: VERBS[match[1]],
        path: (`${base}${sub}`.replace(/\/+$/, '') || base || '/'),
        controller,
        handler: handlerNameAfter(source, after),
        deprecated: isDeprecatedAt(source, match.index),
      });
    }
  }
  return { endpoints, methodLevelRequestMappings };
}

const { endpoints: SERVER_ENDPOINTS, methodLevelRequestMappings } = parseControllers();

/** Path variables are positional; their NAMES differ between client and server by design. */
const normaliseServerPath = (path) => path.replace(/\{[^}]*\}/g, '{}');

const endpointKey = (e) => `${e.verb} ${normaliseServerPath(e.path)}`;
const SERVER_KEYS = new Set(SERVER_ENDPOINTS.map(endpointKey));
const DEPRECATED_KEYS = new Set(SERVER_ENDPOINTS.filter((e) => e.deprecated).map(endpointKey));

// ─────────────────────────────────────────────────────────────────────────────
// Capturing what hrApi.js actually issues
// ─────────────────────────────────────────────────────────────────────────────

const CAPTURED = [];

vi.mock('./client.js', async () => {
  const actual = await vi.importActual('./client.js');
  return {
    ...actual,
    apiRequest: (path, options = {}) => {
      CAPTURED.push({ verb: (options.method ?? 'GET').toUpperCase(), rawPath: String(path) });
      return Promise.resolve({});
    },
    csrfHeaders: () => ({}),
  };
});

const { api } = await import('./hrApi.js');

/**
 * The sentinel every placeholder argument carries. Any path segment containing it is a path
 * VARIABLE — that is how a composed client path is matched against a server `{id}`.
 */
const SENTINEL = '__P__';

/**
 * Argument profiles, tried in order until one runs without throwing. Different methods want
 * different shapes: an id (string), a payload (object), and `priceImport.updateProfile` which
 * runs `JSON.parse(json)` on its argument and needs valid JSON text.
 */
const ARGUMENT_PROFILES = [() => SENTINEL, () => ({}), () => '{}', () => 1];

function normaliseClientPath(path) {
  return path
    .split('?')[0]
    .split('/')
    .map((segment) => (segment.includes(SENTINEL) || /^\d+$/.test(segment) ? '{}' : segment))
    .join('/');
}

/** Drive every hrApi method and return `{ calls, unprobed }`. */
function probeClient() {
  const previousFetch = globalThis.fetch;
  const previousFormData = globalThis.FormData;
  globalThis.fetch = (url, options = {}) => {
    CAPTURED.push({ verb: (options.method ?? 'GET').toUpperCase(), rawPath: String(url) });
    return Promise.resolve({
      ok: true,
      status: 200,
      json: async () => ({}),
      blob: async () => ({}),
      text: async () => '',
      headers: { get: () => 'application/json' },
    });
  };
  // jsdom's FormData rejects a plain object for `append`; hrApi passes File-shaped values.
  globalThis.FormData = class { append() {} };

  const calls = [];
  const unprobed = [];
  try {
    for (const [namespace, methods] of Object.entries(api)) {
      for (const [name, fn] of Object.entries(methods)) {
        if (typeof fn !== 'function') continue;
        const before = CAPTURED.length;
        let returnedPath = null;
        for (const profile of ARGUMENT_PROFILES) {
          try {
            // Arity is a lower bound: several methods declare fewer parameters than they use
            // via defaults, so pass a few extra — surplus arguments are ignored.
            const result = fn(...Array.from({ length: Math.max(fn.length, 4) }, profile));
            // `*Url` helpers return a path for a link or image attribute instead of fetching it.
            if (typeof result === 'string' && result.startsWith('/api')) returnedPath = result;
            break;
          } catch {
            // try the next profile
          }
        }
        const issued = CAPTURED.slice(before);
        for (const call of issued) {
          calls.push({ namespace, name, verb: call.verb, path: normaliseClientPath(call.rawPath) });
        }
        if (returnedPath) {
          calls.push({ namespace, name, verb: 'GET', path: normaliseClientPath(returnedPath) });
        }
        if (!issued.length && !returnedPath) unprobed.push(`${namespace}.${name}`);
      }
    }
  } finally {
    globalThis.fetch = previousFetch;
    globalThis.FormData = previousFormData;
  }
  return { calls, unprobed };
}

const { calls: CLIENT_CALLS, unprobed: UNPROBED_METHODS } = probeClient();

/** A HEAD probe (leave.policyDocumentAvailable) is answered by the GET mapping. */
const clientKey = ({ verb, path }) => `${verb === 'HEAD' ? 'GET' : verb} ${path}`;
const CLIENT_KEYS = new Set(CLIENT_CALLS.map(clientKey));

// ─────────────────────────────────────────────────────────────────────────────
// Endpoints with no hrApi caller
// ─────────────────────────────────────────────────────────────────────────────
//
// Same spirit — and the same two-way enforcement — as contract.test.js's KNOWN_GAPS and
// ARITY_EXEMPTIONS: every entry must name a real, still-uncalled endpoint and carry a written
// reason. A stale entry is deleted by its own test below, so an endpoint that later gains a caller
// cannot leave a pre-approved hole behind it.
//
// AN ENTRY HERE IS NOT A CLAIM THAT THE ENDPOINT IS DEAD. Read each reason: some are reached by
// clients this harness cannot see (the Python scanner agents), and some are capabilities built
// backend-first whose UI has not landed. Whether to surface or retire any of them is a product
// decision, recorded in the PR that added this file — not something a test should decide.
const SERVER_ONLY = {
  // ── Live, via a client this harness cannot see ────────────────────────────
  'POST /api/attendance/punch':
    'The physical scanners post here with an X-GLR-Agent-Token instead of a session — see '
    + 'agents/attendance/showroom_agent.py. One of SecurityConfig\'s anonymous exceptions, and pinned by '
    + 'e2e-real/api-surface.spec.js ANONYMOUS_ALLOWLIST. Live; deliberately not reachable from hrApi.js.',
  'POST /api/attendance/devices/{}/agent-token':
    'Mints/rotates the device token the punch endpoint above authenticates with. Run by hand from an HR session '
    + '(curl/Postman) — the runbook is agents/attendance/WAREHOUSE_SCANNER_SETUP.md, and three more files under '
    + '"ERP Documentation/" document the same call. The plaintext is shown exactly once, which is why there is no '
    + 'UI. Covered by AttendanceControllerTest. LIVE, not dead.',

  // ── Deliberately dormant: the UI was built, shipped, then removed ─────────
  // All eight ProcurementController mappings. PR #683 (ebaf6888, 2026-08-11) deleted the จัดซื้อ & นำเข้า
  // page and every client layer together, and its commit body states the backend was kept on purpose:
  // "left in place, dormant and with no frontend caller. Nothing is dropped". It even predicts this
  // guard under Known risks: "a future contract-style sweep will flag them as unreachable. That is
  // intended." 0 factory POs have ever existed in production. ProcurementService remains well covered
  // by ProcurementServiceIntegrationTest; only the HTTP door has nobody behind it.
  'GET /api/factory-purchase-orders': 'ProcurementController — dormant by owner ruling, PR #683. See the block comment above.',
  'GET /api/factory-purchase-orders/{}': 'ProcurementController — dormant by owner ruling, PR #683. See the block comment above.',
  'POST /api/factory-purchase-orders/{}/cancel': 'ProcurementController — dormant by owner ruling, PR #683. See the block comment above.',
  'POST /api/factory-purchase-orders/{}/goods-received': 'ProcurementController — dormant by owner ruling, PR #683. See the block comment above.',
  'POST /api/factory-purchase-orders/{}/proforma': 'ProcurementController — dormant by owner ruling, PR #683. See the block comment above.',
  'POST /api/factory-purchase-orders/{}/shipping': 'ProcurementController — dormant by owner ruling, PR #683. See the block comment above.',
  'GET /api/pricing-requests/{}/factory-purchase-orders': 'ProcurementController — dormant by owner ruling, PR #683. See the block comment above.',
  'POST /api/pricing-requests/{}/factory-purchase-orders': 'ProcurementController — dormant by owner ruling, PR #683. See the block comment above.',

  // ── Built backend-first; the UI pass has not landed ───────────────────────
  'POST /api/pricing-decisions/{}/recalculate-cost':
    'V141 "CEO owns costing" (PR #702, 2026-08-13). This REPLACES the severed POST /api/pricing-costings/{}/recalculate '
    + 'that hrApi still calls — see CALLS_DEPRECATED below, which is the other half of the same unfinished migration. '
    + 'Covered by PricingDecisionIntegrationTest and PricingCostingAuthzIntegrationTest.',
  'PUT /api/pricing-decisions/{}/items/{}/cost-override':
    'V141 (PR #702). Genuinely new behaviour, not a duplicate: a per-line manual cost sitting BESIDE the computed '
    + 'figure, which it never destroys, with a mandatory reason and staleness re-stamping. No routes.js entry, so '
    + 'e2e-real\'s API_ROUTES-derived sweep cannot see it either. Awaiting the frontend pass.',
  'POST /api/pricing-formula-config/freight-rates': 'Freight-row add/delete, PR #455. See the DELETE entry below.',
  'DELETE /api/pricing-formula-config/freight-rates/{}':
    'Per-row add/delete for the freight matrix (issue #436, PR #455 — which touched 3 backend files and 0 frontend). '
    + 'CeoSettingsPage.jsx still edits freight AMOUNT ONLY through the whole-config write, so V109\'s six blank cells '
    + 'remain unfillable and a new origin country still needs a migration — the exact gap #436 set out to close. '
    + 'Covered by PricingFormulaConfigControllerTest and PricingFormulaConfigFreightRowIntegrationTest.',
  'GET /api/payroll/deduction-consents': 'Written-consent record, issue #376. See the PUT entry below.',
  'PUT /api/payroll/deduction-consents':
    'HR bookkeeping of which deductions have written employee consent on file (issue #376, PR #411). Deliberately a '
    + 'recorded field and NOT an enforcement gate — nothing in PayrollCalculator reads it. Service-level tests only; '
    + 'the HTTP layer is untested because nothing calls it.',
  'GET /api/payroll/deduction-shortfalls':
    'Read-only ledger of garnishment deductions that could not be taken in full (issue #376, PR #411). The table is '
    + 'WRITTEN on every payroll run by DeductionObligationService#recordGarnishmentShortfalls, so the data accumulates '
    + 'in production — only the read surface has no client, meaning HR cannot see what the system is recording.',
  'POST /api/leave/policy-document':
    'The UPLOAD half of the §5 announcement PDF (PR #494). It has never had a frontend client. V133 says rows reach '
    + 'the table only through this endpoint, so the table is necessarily empty in every environment. Covered by '
    + 'LeaveControllerPolicyDocumentIntegrationTest. NOTE the GET half is still called by hrApi — but only by '
    + 'policyDocumentAvailable/downloadPolicyDocument, which LeavePolicyBar.jsx no longer calls since the PDF was '
    + 'bundled at frontend/public/policy/ (2026-08-11 owner ruling).',
  'POST /api/employees/{}/reset-password':
    'HR-only temporary-password issue, covered by EmployeeControllerTest and EmployeeServiceResetPasswordTest. '
    + 'Operationally significant: README.md and PasswordBackfillRunner both designate this as THE onboarding path '
    + 'for a new employee\'s first password (it replaced employee-code-derived passwords, removed for security in '
    + 'PR #150), yet there is no button and no documented curl recipe. Onboarding needs a hand-crafted POST today.',

  // ── Orphaned by a frontend removal; the clearest deletion candidate ───────
  'GET /api/deal-estimate-markup': 'Orphaned by PR #682. See the PUT entry below.',
  'PUT /api/deal-estimate-markup':
    'The ราคาตั้ง (ประมาณการ) display multiplier (V112, PR #438), removed from the frontend entirely by PR #682 after '
    + 'UAT — reps were reading a catalog-price-times-markup figure as a selling price. Two frontend tests now assert '
    + 'it must NOT come back (TicketCreateModal.test.jsx, CeoSettingsPage.test.jsx). The controller, repository, DTOs, '
    + 'V112 table and two backend test classes all survive, asserting a contract nothing consumes. This is the one '
    + 'entry where deletion is the straightforward answer — left for an owner ruling, and note FxRateController cites '
    + 'this controller as precedent for its own open-read decision.',
};

// ─────────────────────────────────────────────────────────────────────────────
// hrApi calls that reach a @Deprecated endpoint
// ─────────────────────────────────────────────────────────────────────────────
//
// The path-and-verb comparison above cannot see this class of divergence, and it is the one this
// audit actually found in the wild. V141 (PR #702) moved landed costing from Import to the CEO and
// SEVERED three write routes rather than deleting them: they are still routed, still match on path
// and verb, and throw 409 unconditionally. `PricingCostingController`'s own Javadoc says keeping the
// route shape is deliberate so "the client contract stays stable" while the backend is cleaned
// first. Meanwhile PricingRequestDetailPage.jsx still drives all three, so against a real backend
// those three buttons now always fail — invisible under VITE_USE_MOCKS=true, because mockApi never
// learned they were severed.
//
// Listing them as an EXACT expectation, the same shape as api-surface.spec.js's KNOWN_SERVER_ERRORS:
// a NEW deprecated call site fails this test, and so does finishing the migration without removing
// the entry. Either direction should require a decision rather than passing quietly.
const CALLS_DEPRECATED = {
  'POST /api/pricing-requests/{}/costings':
    'pricingRequests.createCosting → severed by V141; the CEO equivalent is startPricingDecision. Always 409s.',
  'POST /api/pricing-costings/{}/recalculate':
    'pricingRequests.recalculateCosting → severed by V141; replaced by POST /api/pricing-decisions/{}/recalculate-cost, '
    + 'which is in SERVER_ONLY above because no client calls it yet. Always 409s.',
  'POST /api/pricing-costings/{}/submit':
    'pricingRequests.submitCosting → severed by V141; there is no Import-submitted costing to hand over any more. '
    + 'Always 409s.',
};

// ─────────────────────────────────────────────────────────────────────────────
// The assertions
// ─────────────────────────────────────────────────────────────────────────────

describe('controller surface / hrApi.js contract — parser sanity', () => {
  // Every assertion below is trivially true against an empty extraction. These run first so a
  // rotted regex, a moved backend directory or a harness that stopped driving hrApi fails LOUDLY
  // instead of passing on zero matches. The floors are set well under today's real numbers
  // (39 controllers, 278 mappings, 261 captured calls) and far above a broken parser's output.
  it('found a plausible number of controllers and endpoints', () => {
    expect(controllerFiles().length).toBeGreaterThan(30);
    expect(SERVER_ENDPOINTS.length).toBeGreaterThan(250);
    expect(SERVER_KEYS.size).toBeGreaterThan(250);
  });

  it('resolved every endpoint against its class-level @RequestMapping', () => {
    // The bug this catches is specific and was made once while writing this file: anchoring the
    // class-level lookup on the annotations rather than the class declaration silently drops the
    // base path, and all 278 endpoints come out as bare subpaths like `/deal-stages`.
    const unprefixed = SERVER_ENDPOINTS.filter((e) => !e.path.startsWith('/api/'));
    expect(unprefixed, 'endpoints whose class-level @RequestMapping did not resolve').toEqual([]);
    expect(SERVER_ENDPOINTS.filter((e) => e.handler === '?')).toEqual([]);
  });

  it('sees no method-level @RequestMapping, which it would not understand', () => {
    // This parser reads the verb from @GetMapping/@PostMapping/etc. A method-level
    // @RequestMapping(method = RequestMethod.GET) would carry its verb somewhere this parser does
    // not look, and the endpoint would vanish from the comparison rather than fail it.
    expect(methodLevelRequestMappings).toEqual([]);
  });

  it('drove every hrApi method to an observable request', () => {
    // The client-side anti-vacuity check. A method this harness cannot call — a new argument shape
    // none of ARGUMENT_PROFILES satisfies — would otherwise drop silently out of the comparison,
    // and its endpoint would look uncalled while its call went unchecked.
    expect(UNPROBED_METHODS, 'hrApi methods that issued no request under any argument profile')
      .toEqual([]);
    expect(CLIENT_CALLS.length).toBeGreaterThan(200);
  });
});

describe('controller surface / hrApi.js contract', () => {
  // ── THE DIRECTION THAT MATTERS MOST ────────────────────────────────────────
  it('hrApi calls no endpoint the backend does not serve', () => {
    const broken = [];
    for (const call of CLIENT_CALLS) {
      const key = clientKey(call);
      if (SERVER_KEYS.has(key)) continue;
      const otherVerbs = [...SERVER_KEYS]
        .filter((k) => k.endsWith(` ${call.path}`))
        .map((k) => k.split(' ')[0]);
      broken.push(
        `${call.namespace}.${call.name} → ${key}`
        + (otherVerbs.length ? ` (the path exists server-side under ${otherVerbs.join(', ')})` : ''),
      );
    }
    // No allowlist, by design: a client call with no endpoint behind it is already a 404 in
    // production. The "path exists under another verb" hint is there because a verb mismatch is
    // the likeliest cause and the least obvious from the path alone.
    expect([...new Set(broken)].sort()).toEqual([]);
  });

  // ── THE REPORTING DIRECTION ────────────────────────────────────────────────
  it('every endpoint is either called by hrApi or documented in SERVER_ONLY', () => {
    const undocumented = [...SERVER_KEYS]
      .filter((key) => !CLIENT_KEYS.has(key) && !SERVER_ONLY[key])
      .sort();
    expect(
      undocumented,
      'endpoints with no hrApi caller and no SERVER_ONLY entry. If a UI was meant to call it, wire '
      + 'it up; if it is reached by something else (a script, the scanner agents, e2e) or is a '
      + 'capability awaiting its UI, add an entry saying which. Do NOT delete a server endpoint to '
      + 'silence this.',
    ).toEqual([]);
  });

  // ── A CALL THAT MATCHES ON PATH AND VERB AND STILL ALWAYS FAILS ───────────
  //
  // HOW AN ALWAYS-409 ENDPOINT IS CLASSIFIED HERE, stated because getting it wrong both ways is
  // easy. A severed route is NOT missing: it is still routed, so the two tests above see a normal
  // match and report nothing. That is correct — answering 409 with a clear message is a deliberate
  // design in this repo, chosen precisely so a stale caller gets an explanation instead of a 404,
  // and flagging it as a broken call would be a false positive on an intentional pattern.
  //
  // Note the repo has TWO retirement shapes and only one of them lands here:
  //   • route DELETED, service method kept @Deprecated — TicketService#submit/#pickup. No mapping
  //     exists, so these never enter the comparison at all. Nothing to report.
  //   • route KEPT and severed — the three V141 costing writes below. These do enter it.
  //
  // So this test asks the narrower question the other two cannot: is a live hrApi method pointed at
  // a route the backend has already severed? That is not a false positive and not a design choice
  // — it is an unfinished migration, and it is invisible under VITE_USE_MOCKS=true because mockApi
  // never learned the route was severed.
  it('no hrApi method calls a @Deprecated endpoint beyond the documented migration', () => {
    const calling = [...new Set(
      CLIENT_CALLS
        .filter((call) => DEPRECATED_KEYS.has(clientKey(call)))
        .map((call) => `${call.namespace}.${call.name} → ${clientKey(call)}`),
    )].sort();
    const documented = calling.filter((entry) => CALLS_DEPRECATED[entry.split(' → ')[1]]);
    expect(
      calling.filter((entry) => !documented.includes(entry)),
      'hrApi calls a route the backend marked @Deprecated. These match on path and verb, so the '
      + 'contract tests above pass while every one of these calls fails at runtime.',
    ).toEqual([]);
  });

  it('every CALLS_DEPRECATED entry is still deprecated and still called', () => {
    // Self-verifying, and it doubles as the anti-vacuity check on the @Deprecated parse: if
    // isDeprecatedAt() stopped matching, DEPRECATED_KEYS would empty and these assertions fail.
    for (const [key, reason] of Object.entries(CALLS_DEPRECATED)) {
      expect(SERVER_KEYS.has(key), `CALLS_DEPRECATED lists "${key}", which no controller serves`).toBe(true);
      expect(
        DEPRECATED_KEYS.has(key),
        `CALLS_DEPRECATED["${key}"] is no longer @Deprecated — delete the entry`,
      ).toBe(true);
      expect(
        CLIENT_KEYS.has(key),
        `CALLS_DEPRECATED["${key}"] is no longer called by hrApi — the migration finished, delete the entry`,
      ).toBe(true);
      expect(reason.length, `CALLS_DEPRECATED["${key}"] needs a reason`).toBeGreaterThan(20);
    }
  });

  it('every SERVER_ONLY entry is a real, still-uncalled endpoint with a written reason', () => {
    for (const [key, reason] of Object.entries(SERVER_ONLY)) {
      expect(SERVER_KEYS.has(key), `SERVER_ONLY lists "${key}", which no controller serves`).toBe(true);
      // Stale entries rot: once hrApi starts calling an endpoint, its exemption must go, or the
      // next time that endpoint disappears server-side nothing notices.
      expect(
        CLIENT_KEYS.has(key),
        `SERVER_ONLY["${key}"] is now called by hrApi — delete the entry`,
      ).toBe(false);
      expect(reason.length, `SERVER_ONLY["${key}"] needs a reason`).toBeGreaterThan(20);
    }
  });
});
