import { test, expect } from '@playwright/test';
import { REAL_ROLES } from './helpers/accounts.js';
import { anonApi, apiSessionsFor, disposeSessions } from './helpers/api.js';

// ─────────────────────────────────────────────────────────────────────────────
// BACKEND AUTHORIZATION — real Spring services, real Postgres, real sessions.
//
// This is the file the mock suite cannot have. e2e/rbac.spec.js says so in its own header:
// it proves react-router-dom's client-side guard renders-or-redirects per app/permissions.js,
// and explicitly disclaims proving anything about the server. CLAUDE.md is blunter — mockApi's
// authz is known to diverge from the Java services, and a mock that is MORE permissive than
// production (e.g. the mock letting HR approve OT that OvertimeService 403s) is a bug you
// only discover in prod.
//
// Every expectation below was read off the Java source AND observed against a running service;
// each entry cites the class that decides it. When one of these goes red, the answer is in that
// class — not in mockApi.js, and not in ROLE_PERMISSIONS.
//
// Written wrong-way-round on purpose. The assertions that matter are the `denied` lists: that a
// role CANNOT reach what it shouldn't. "hr can list employees" is the cheap half; "ceo cannot"
// is the half that catches a widened gate.
//
// Read-only by design: every request here is a GET, so the suite is idempotent and leaves the
// shared demo database exactly as it found it. Anything asserting a WRITE gate must stay in the
// deny direction (a 403 is refused before it mutates) for the same reason.
// ─────────────────────────────────────────────────────────────────────────────

const ALL = REAL_ROLES;
const except = (...roles) => ALL.filter((role) => !roles.includes(role));

const GATES = [
  {
    name: 'GET /api/employees — the HR directory',
    path: '/api/employees',
    allowed: ['hr'],
    denied: except('hr'),
    // EmployeeController#list: sessions.requireAnyRole(user, "hr") — a single role, and notably NOT ceo. The most
    // privileged persona in the system is refused here, which is exactly the kind of asymmetry a permissive mock
    // would paper over.
    source: 'EmployeeController#list',
  },
  {
    name: 'GET /api/attendance/devices — scanner administration',
    path: '/api/attendance/devices',
    allowed: ['hr', 'ceo'],
    denied: except('hr', 'ceo'),
    // AttendanceController#listDevices: requireAnyRole(user, "hr", "ceo").
    source: 'AttendanceController#listDevices',
  },
  {
    name: 'GET /api/tickets — the sales deal list',
    path: '/api/tickets',
    allowed: ['sales', 'sales_manager', 'import', 'ceo', 'account'],
    // hr and employee are the interesting denials: hr is privileged over the whole HR half of this product and
    // still has no business reading the sales pipeline.
    denied: except('sales', 'sales_manager', 'import', 'ceo', 'account'),
    // TicketService gates on TicketAccessPolicy.VIEWER_ROLES = {sales, import, ceo, account, sales_manager}.
    source: 'TicketAccessPolicy.VIEWER_ROLES',
  },
  {
    name: 'GET /api/pricing-requests — the pricing-request aggregate',
    path: '/api/pricing-requests',
    allowed: ['sales', 'sales_manager', 'import', 'ceo'],
    denied: except('sales', 'sales_manager', 'import', 'ceo'),
    // PricingRequestService.VIEWER_ROLES = {sales, import, ceo, sales_manager}. Deliberately one
    // role narrower than TicketAccessPolicy.VIEWER_ROLES above — `account` reads deals but not
    // pricing requests. That asymmetry is the reason these two rows exist separately: `account`
    // (seeded by V139) is in this row's denied set and the previous row's allowed set, which is
    // the assertion that actually pins the difference.
    source: 'PricingRequestService',
  },
  {
    name: 'GET /api/catalog/prices — catalog browsing',
    path: '/api/catalog/prices',
    allowed: ALL,
    denied: [],
    // Open to ANY authenticated user by product decision. Asserted so a future "tighten the
    // catalog" change has to be a deliberate one that updates this line, rather than a silent
    // regression against a documented decision.
    source: 'CatalogController (product decision #205)',
  },
  {
    name: 'GET /api/commissions/reps — the manual-commission rep picker',
    path: '/api/commissions/reps',
    allowed: ['sales_manager', 'ceo'],
    denied: except('sales_manager', 'ceo'),
    // CommissionController#reps / CommissionService.MANUAL_CREATE_ROLES: the identical gate as
    // POST /api/commissions/manual. hr is in the denied set on purpose: hr is the
    // ONLY role that can read /api/employees (the row above), and it deliberately cannot read
    // this narrower, purpose-built list — the same asymmetry the row above's comment calls out.
    source: 'CommissionController#reps / CommissionService.MANUAL_CREATE_ROLES',
  },
];

test.describe('backend authorization (real Java services)', () => {
  /** @type {Record<string, import('@playwright/test').APIRequestContext>} */
  let sessions;

  test.beforeAll(async () => {
    // One live session per persona, opened once. apiSessionFor throws if the backend resolves a different role
    // than requested, so seed drift fails here rather than silently re-pointing every assertion below at the wrong
    // persona.
    sessions = await apiSessionsFor(ALL);
  });

  test.afterAll(async () => {
    await disposeSessions(sessions);
  });

  for (const gate of GATES) {
    test(`${gate.name} — denied roles get 403`, async () => {
      test.skip(gate.denied.length === 0, 'open to every authenticated role by design');

      for (const role of gate.denied) {
        const response = await sessions[role].get(gate.path);
        expect(
          response.status(),
          `${role} must NOT reach ${gate.path} (${gate.source})`
        ).toBe(403);
      }
    });

    test(`${gate.name} — permitted roles get 200`, async () => {
      for (const role of gate.allowed) {
        const response = await sessions[role].get(gate.path);
        expect(
          response.status(),
          `${role} must reach ${gate.path} (${gate.source})`
        ).toBe(200);
      }
    });
  }

  test('every gated route answers 401 without a session', async () => {
    const anon = await anonApi();
    try {
      for (const gate of GATES) {
        const response = await anon.get(gate.path);
        // 401, never 403: SecurityConfig's `.anyRequest().authenticated()` rejects at the filter chain before any
        // controller runs, so an anonymous caller cannot even learn whether the role gate behind it would have let
        // someone in.
        expect(response.status(), `${gate.path} without a session`).toBe(401);
      }
    } finally {
      await anon.dispose();
    }
  });

  test('the denied roles are refused, not silently served an empty list', async () => {
    // The failure this guards against is a gate "fixed" by turning a 403 into a 200 with zero rows. That reads as
    // safe and is not: an empty page is indistinguishable from "there is nothing to see", so a scope bug that
    // leaks rows later would look like data appearing rather than a permission regression.
    const response = await sessions.ceo.get('/api/employees');
    expect(response.status()).toBe(403);
    expect(await response.text()).toContain('ไม่มีสิทธิ์เข้าถึงรายการนี้');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/tickets/{id}/document-chain — the document chain summary (2026-11-17). Its gate IS the deposit-notice read
// gate, DepositNoticeService#requireTicketViewer (DocumentChainService#forTicket calls it first):
//   1. requireRole(VIEWER_ROLES = sales, import, ceo, account, sales_manager) — everyone else 403, before any lookup;
//   2. the deal must exist — 404;
//   3. per role (dealDocumentScope): ceo / sales_manager every deal; sales only a deal it owns or keyed; account only a
//      deal inside its list scope; import never (403, after the 404).
// Pinned against real deals discovered from each role's own deal list, so no seed id is hard-coded. The scope SQL
// itself is proven by DocumentChainIntegrationTest (real Postgres, wrong-way-round, mutation-checked).
// ─────────────────────────────────────────────────────────────────────────────
test.describe('document chain summary — DepositNoticeService#requireTicketViewer', () => {
  /** @type {Record<string, import('@playwright/test').APIRequestContext>} */
  let sessions;
  const READERS = ['sales', 'sales_manager', 'ceo', 'account'];
  const NON_VIEWERS = except('sales', 'sales_manager', 'ceo', 'account', 'import');
  const chainPath = (id) => `/api/tickets/${id}/document-chain`;
  const MISSING = 2147480000;

  test.beforeAll(async () => { sessions = await apiSessionsFor(ALL); });
  test.afterAll(async () => { await disposeSessions(sessions); });

  /** The deals `role` itself can list (GET /api/tickets applies the same per-role scope). */
  async function dealsOf(role) {
    const response = await sessions[role].get('/api/tickets?size=50');
    expect(response.status(), `${role} lists its deals`).toBe(200);
    return (await response.json()).tickets ?? [];
  }

  test('roles that read no deal documents get 403 — before the deal is even looked up', async () => {
    for (const role of NON_VIEWERS) {
      expect((await sessions[role].get(chainPath(MISSING))).status(), `${role} on a missing deal`).toBe(403);
    }
  });

  test('every role that passes the role gate gets 404 for a deal that does not exist', async () => {
    for (const role of [...READERS, 'import']) {
      expect((await sessions[role].get(chainPath(MISSING))).status(), `${role} on a missing deal`).toBe(404);
    }
  });

  test('each reader gets 200 on a deal from its own list; import, hr and the rest get 403 on the same deal', async () => {
    let checked = 0;
    for (const role of READERS) {
      const [deal] = await dealsOf(role);
      if (!deal) continue; // nothing in this persona's scope on this seed
      checked += 1;
      expect((await sessions[role].get(chainPath(deal.id))).status(), `${role} on its own deal ${deal.id}`).toBe(200);
      for (const denied of ['import', ...NON_VIEWERS]) {
        expect((await sessions[denied].get(chainPath(deal.id))).status(), `${denied} on deal ${deal.id}`).toBe(403);
      }
    }
    test.skip(checked === 0, 'the seed has no deal any reader can list');
  });

  test('a sales rep is refused a deal it neither owns nor keyed (wrong-way-round)', async () => {
    const me = (await (await sessions.sales.get('/api/auth/me')).json()).user;
    const foreign = (await dealsOf('ceo')).find((deal) => Number(deal.createdById) !== Number(me.id)
      && Number(deal.keyedById) !== Number(me.id));
    test.skip(!foreign, 'every seeded deal belongs to the sales persona');
    expect((await sessions.sales.get(chainPath(foreign.id))).status(), `sales on deal ${foreign.id}`).toBe(403);
    expect((await sessions.ceo.get(chainPath(foreign.id))).status(), `ceo on deal ${foreign.id}`).toBe(200);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/tickets/{id}/import-requests/coverage — the stored ใบขอซื้อ coverage read, and (slice 6c) its
// `splitPreview`, the dry run of createDrafts' per-factory split shown in the "ยืนยันออกใบขอซื้อ" confirm.
// ImportRequestService#coverage:
//   1. the deal must exist — 404 for EVERY role (requireTicket runs before the role check);
//   2. requireRead: ceo / import / sales_manager every deal; sales only a deal it owns or keyed; everyone else 403;
//   3. splitPreview: an object only for createDrafts' own gate (ceo, the owning/keying rep); null for import and
//      sales_manager, who read this route for the covered flag only.
//   4. `itemFactory` must be `<ticketItemId>:<factoryId>` — anything else is a 400 (ImportRequestController).
// The split itself, the no-write guarantee and the gate's SQL are proven by ImportRequestSplitPreviewIntegrationTest
// (real Postgres, wrong-way-round, mutation-checked). Read-only: every request here is a GET.
// ─────────────────────────────────────────────────────────────────────────────
test.describe('stored IR coverage + split preview — ImportRequestService#coverage', () => {
  /** @type {Record<string, import('@playwright/test').APIRequestContext>} */
  let sessions;
  const NO_READ = except('ceo', 'import', 'sales_manager', 'sales');
  const coveragePath = (id, query = '') => `/api/tickets/${id}/import-requests/coverage${query}`;
  const MISSING = 2147480000;

  test.beforeAll(async () => { sessions = await apiSessionsFor(ALL); });
  test.afterAll(async () => { await disposeSessions(sessions); });

  async function dealsOf(role) {
    const response = await sessions[role].get('/api/tickets?size=50');
    expect(response.status(), `${role} lists its deals`).toBe(200);
    return (await response.json()).tickets ?? [];
  }

  test('every role gets 404 for a deal that does not exist — the deal is looked up before the role', async () => {
    for (const role of ALL) {
      expect((await sessions[role].get(coveragePath(MISSING))).status(), `${role} on a missing deal`).toBe(404);
    }
  });

  test('wrong-way-round: import and sales_manager get the covered flag but a null splitPreview; the rest are refused', async () => {
    const [deal] = await dealsOf('ceo');
    test.skip(!deal, 'the seed has no deal the ceo can list');
    for (const role of ['import', 'sales_manager']) {
      const response = await sessions[role].get(coveragePath(deal.id));
      expect(response.status(), `${role} on deal ${deal.id}`).toBe(200);
      const body = await response.json();
      expect(typeof body.hasUncoveredFactories, `${role} gets the covered flag`).toBe('boolean');
      expect(body.splitPreview, `${role} must NOT get the split preview`).toBeNull();
    }
    for (const role of NO_READ) {
      expect((await sessions[role].get(coveragePath(deal.id))).status(), `${role} on deal ${deal.id}`).toBe(403);
    }
    const ceo = await (await sessions.ceo.get(coveragePath(deal.id))).json();
    expect(Array.isArray(ceo.splitPreview?.factories), 'ceo gets the split preview').toBe(true);
    expect(Array.isArray(ceo.splitPreview?.linesWithoutFactory), 'ceo gets the unplaced lines').toBe(true);
  });

  /** Every deal id `role` can list, all pages (TicketService#list scopes sales to created_by OR keyed_by in SQL, so
   * this set IS "owns or keyed" without reading keyedById off a row, which a summary may omit). */
  async function allDealIdsOf(role) {
    const ids = new Set();
    for (let page = 0; ; page += 1) {
      const response = await sessions[role].get(`/api/tickets?size=500&page=${page}`);
      expect(response.status(), `${role} lists its deals (page ${page})`).toBe(200);
      const body = await response.json();
      (body.tickets ?? []).forEach((deal) => ids.add(Number(deal.id)));
      if ((body.tickets ?? []).length === 0 || ids.size >= Number(body.total ?? 0)) return ids;
    }
  }

  test('the owning rep gets the preview on its own deal and is refused a foreign one', async () => {
    // Fix round (review e2e risk): ownership comes from the server's own scope, never from createdById/keyedById on a
    // row, so a deal the persona only KEYED can never be mistaken for a foreign one.
    const ownIds = await allDealIdsOf('sales');
    const own = (await dealsOf('sales'))[0];
    if (own) {
      const response = await sessions.sales.get(coveragePath(own.id));
      expect(response.status(), `sales on its own deal ${own.id}`).toBe(200);
      expect(Array.isArray((await response.json()).splitPreview?.factories), 'owner gets the split preview').toBe(true);
    }
    const foreign = (await dealsOf('ceo')).find((deal) => !ownIds.has(Number(deal.id)));
    if (foreign) {
      expect((await sessions.sales.get(coveragePath(foreign.id))).status(), `sales on deal ${foreign.id}`).toBe(403);
    }
    test.skip(!own && !foreign, 'the seed gives the sales persona neither an own nor a foreign deal');
  });

  test('a malformed itemFactory pair is a 400, a well-formed one is read', async () => {
    const [deal] = await dealsOf('ceo');
    test.skip(!deal, 'the seed has no deal the ceo can list');
    for (const bad of ['12', 'a:b', '1:2:3']) {
      const response = await sessions.ceo.get(coveragePath(deal.id, `?itemFactory=${encodeURIComponent(bad)}`));
      expect(response.status(), `itemFactory=${bad}`).toBe(400);
    }
    // An unknown factory / a line not on this deal is ignored by the preview, never an error.
    expect((await sessions.ceo.get(coveragePath(deal.id, '?itemFactory=2147480000:2147480000'))).status()).toBe(200);
  });
});
