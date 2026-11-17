import { test, expect } from '@playwright/test';
import { REAL_ROLES } from './helpers/accounts.js';
import { anonApi, apiSessionsFor, apiWrite, disposeSessions } from './helpers/api.js';

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
// GET /api/sales/visits/deal/{ticketId}/planned — a deal's PLANNED visits for the deal page (slice 6.5). The read is the
// INTERSECTION of two existing rules (VisitService#plannedOnDeal):
//   1. requireRole(VISIT_ROLES = sales, sales_manager, ceo) — everyone else 403, before any lookup;
//   2. TicketService#requireDealReadAccess — GET /api/tickets/{id}'s rule: 404 for a missing deal; a sales rep only a
//      deal it owns or keyed (403 otherwise);
//   3. rows: every owner's for sales_manager / ceo (as /visits/team), the caller's OWN for sales (as /visits).
// The owner predicate itself is proven by DealPlannedVisitsScopeIntegrationTest (real Postgres, wrong-way-round,
// mutation-checked); here it is pinned on whatever the seed holds: a sales caller never receives another owner's row.
// That row loop is vacuous on a seed with no planned visits; deal-follow-up.spec.js creates its own and is not.
// ─────────────────────────────────────────────────────────────────────────────
test.describe('planned visits on a deal — VisitService#plannedOnDeal', () => {
  /** @type {Record<string, import('@playwright/test').APIRequestContext>} */
  let sessions;
  const READERS = ['sales', 'sales_manager', 'ceo'];
  const NON_READERS = except('sales', 'sales_manager', 'ceo');
  const plannedPath = (id) => `/api/sales/visits/deal/${id}/planned`;
  const MISSING = 2147480000;

  test.beforeAll(async () => { sessions = await apiSessionsFor(ALL); });
  test.afterAll(async () => { await disposeSessions(sessions); });

  async function dealsOf(role) {
    const response = await sessions[role].get('/api/tickets?size=50');
    expect(response.status(), `${role} lists its deals`).toBe(200);
    return (await response.json()).tickets ?? [];
  }

  test('roles outside the visit roles get 403 — before the deal is even looked up', async () => {
    for (const role of NON_READERS) {
      expect((await sessions[role].get(plannedPath(MISSING))).status(), `${role} on a missing deal`).toBe(403);
    }
  });

  test('the visit roles get 404 for a deal that does not exist', async () => {
    for (const role of READERS) {
      expect((await sessions[role].get(plannedPath(MISSING))).status(), `${role} on a missing deal`).toBe(404);
    }
  });

  test('each reader gets 200 on a deal from its own list, and a sales rep only its own PLANNED rows', async () => {
    const me = (await (await sessions.sales.get('/api/auth/me')).json()).user;
    let checked = 0;
    for (const role of READERS) {
      const [deal] = await dealsOf(role);
      if (!deal) continue;
      checked += 1;
      const response = await sessions[role].get(plannedPath(deal.id));
      expect(response.status(), `${role} on its own deal ${deal.id}`).toBe(200);
      const rows = await response.json();
      expect(Array.isArray(rows)).toBe(true);
      for (const row of rows) {
        expect(row.status, `${role}: only PLANNED`).toBe('PLANNED');
        expect(Number(row.ticketId), `${role}: only this deal`).toBe(Number(deal.id));
        if (role === 'sales') expect(Number(row.ownerId), 'sales: only its own visits').toBe(Number(me.id));
      }
      for (const denied of NON_READERS) {
        expect((await sessions[denied].get(plannedPath(deal.id))).status(), `${denied} on deal ${deal.id}`).toBe(403);
      }
    }
    test.skip(checked === 0, 'the seed has no deal any reader can list');
  });

  test('a sales rep is refused the planned visits of a deal it neither owns nor keyed (wrong-way-round)', async () => {
    const me = (await (await sessions.sales.get('/api/auth/me')).json()).user;
    const foreign = (await dealsOf('ceo')).find((deal) => Number(deal.createdById) !== Number(me.id)
      && Number(deal.keyedById) !== Number(me.id));
    test.skip(!foreign, 'every seeded deal belongs to the sales persona');
    expect((await sessions.sales.get(plannedPath(foreign.id))).status(), `sales on deal ${foreign.id}`).toBe(403);
    expect((await sessions.ceo.get(plannedPath(foreign.id))).status(), `ceo on deal ${foreign.id}`).toBe(200);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// nextFollowUpAt on POST /api/tickets/{id}/activities and POST /api/sales/visits (slice 6.4) — DENY DIRECTION ONLY, as
// this file's header requires: every request below is refused before it writes, so the shared database is untouched.
// Who may set a deal's follow-up date is who may already set it through PUT /tracking: TicketService
// #requireDealOwnership (the deal's owner/keyer with the sales role, sales_manager, ceo). A visit adds VisitService's
// VISIT_ROLES and requireDealWriteAccess in front. The allowed direction and "nothing written" are proven by
// DealActivityFollowUpIntegrationTest and VisitFollowUpIntegrationTest (real Postgres, mutation-checked).
// ─────────────────────────────────────────────────────────────────────────────
test.describe('follow-up date writes — refused before anything is written', () => {
  /** @type {Record<string, import('@playwright/test').APIRequestContext>} */
  let sessions;
  const bangkokToday = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Bangkok' }).format(new Date());
  const plusDays = (iso, n) => {
    const d = new Date(`${iso}T00:00:00Z`);
    d.setUTCDate(d.getUTCDate() + n);
    return d.toISOString().slice(0, 10);
  };

  test.beforeAll(async () => { sessions = await apiSessionsFor(ALL); });
  test.afterAll(async () => { await disposeSessions(sessions); });

  async function foreignDeal() {
    const me = (await (await sessions.sales.get('/api/auth/me')).json()).user;
    const listed = await sessions.ceo.get('/api/tickets?size=50');
    expect(listed.status()).toBe(200);
    return ((await listed.json()).tickets ?? []).find((deal) => Number(deal.createdById) !== Number(me.id)
      && Number(deal.keyedById) !== Number(me.id));
  }

  async function nextFollowUpOf(id) {
    const response = await sessions.ceo.get(`/api/tickets/${id}`);
    expect(response.status()).toBe(200);
    return (await response.json()).ticket.summary.nextFollowUpAt ?? null;
  }

  test('a call carrying nextFollowUpAt is 403 for another rep and for every role outside the deal writers', async () => {
    const deal = await foreignDeal();
    test.skip(!deal, 'every seeded deal belongs to the sales persona');
    const before = await nextFollowUpOf(deal.id);
    const body = { kind: 'CALL', activityDate: bangkokToday(), note: 'e2e: refused', nextFollowUpAt: plusDays(bangkokToday(), 7) };
    for (const role of ['sales', ...except('sales', 'sales_manager', 'ceo')]) {
      const response = await apiWrite(sessions[role], 'post', `/api/tickets/${deal.id}/activities`, body);
      expect(response.status(), `${role} POST activities with nextFollowUpAt on deal ${deal.id}`).toBe(403);
    }
    expect(await nextFollowUpOf(deal.id), 'the deal\'s follow-up date is untouched').toBe(before);
  });

  test('a one-step visit log carrying nextFollowUpAt is 403 on another rep\'s deal and for every non-visit role', async () => {
    const deal = await foreignDeal();
    test.skip(!deal, 'every seeded deal belongs to the sales persona');
    const before = await nextFollowUpOf(deal.id);
    const body = {
      ticketId: deal.id, contactName: 'e2e: refused', plannedDate: bangkokToday(), done: true,
      nextFollowUpAt: plusDays(bangkokToday(), 7),
    };
    for (const role of ['sales', ...except('sales', 'sales_manager', 'ceo')]) {
      const response = await apiWrite(sessions[role], 'post', '/api/sales/visits', body);
      expect(response.status(), `${role} POST /api/sales/visits with nextFollowUpAt on deal ${deal.id}`).toBe(403);
    }
    expect(await nextFollowUpOf(deal.id), 'the deal\'s follow-up date is untouched').toBe(before);
  });
});
