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
// production (issue #199: the mock let HR approve OT that OvertimeService 403s) is a bug you
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
    // EmployeeController#list: sessions.requireAnyRole(user, "hr") — a single role, and
    // notably NOT ceo. The most privileged persona in the system is refused here, which is
    // exactly the kind of asymmetry a permissive mock would paper over. It is not an oversight:
    // ROLE_PERMISSIONS.canViewEmployees is ['hr'] too, so frontend and backend agree.
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
    // hr and employee are the interesting denials: hr is privileged over the whole HR half of
    // this product and still has no business reading the sales pipeline.
    denied: except('sales', 'sales_manager', 'import', 'ceo', 'account'),
    // TicketService gates on TicketAccessPolicy.VIEWER_ROLES =
    // {sales, import, ceo, account, sales_manager}. This row now covers all five: `account` got
    // its seeded persona in V139, and warehouse/qc got theirs at the same time, so the denial
    // side is derived with except() rather than hand-listed and cannot fall behind a new role.
    source: 'TicketAccessPolicy.VIEWER_ROLES',
  },
  {
    name: 'GET /api/pricing-requests — the pricing-request aggregate',
    path: '/api/pricing-requests',
    allowed: ['sales', 'sales_manager', 'import', 'ceo'],
    denied: except('sales', 'sales_manager', 'import', 'ceo'),
    // PricingRequestService.VIEWER_ROLES = {sales, import, ceo, sales_manager}. Deliberately one
    // role narrower than TicketAccessPolicy.VIEWER_ROLES above — `account` reads deals but not
    // pricing requests. That asymmetry is the reason these two rows exist separately, and until
    // V139 seeded an `account` persona it was pure prose: both rows looked identical from the
    // seeded personas' point of view, so nothing tested the one role that tells them apart.
    // `account` is now in this row's denied set and the previous row's allowed set, which is the
    // assertion that actually pins the difference.
    source: 'PricingRequestService',
  },
  {
    name: 'GET /api/catalog/prices — catalog browsing',
    path: '/api/catalog/prices',
    allowed: ALL,
    denied: [],
    // Open to ANY authenticated user by product decision — issue #205, owner ruling 2026-08-01
    // closing #388. Asserted so a future "tighten the catalog" change has to be a deliberate one
    // that updates this line, rather than a silent regression against a documented ruling.
    source: 'CatalogController (product decision #205)',
  },
];

test.describe('backend authorization (real Java services)', () => {
  /** @type {Record<string, import('@playwright/test').APIRequestContext>} */
  let sessions;

  test.beforeAll(async () => {
    // One live session per persona, opened once. apiSessionFor throws if the backend resolves a
    // different role than requested, so seed drift fails here rather than silently re-pointing
    // every assertion below at the wrong persona.
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
        // 401, never 403: SecurityConfig's `.anyRequest().authenticated()` rejects at the filter
        // chain before any controller runs, so an anonymous caller cannot even learn whether the
        // role gate behind it would have let someone in.
        expect(response.status(), `${gate.path} without a session`).toBe(401);
      }
    } finally {
      await anon.dispose();
    }
  });

  test('the denied roles are refused, not silently served an empty list', async () => {
    // The failure this guards against is a gate "fixed" by turning a 403 into a 200 with zero
    // rows. That reads as safe and is not: an empty page is indistinguishable from "there is
    // nothing to see", so a scope bug that leaks rows later would look like data appearing
    // rather than a permission regression. Assert the refusal itself carries the service's
    // message, i.e. it came from SessionContext#requireAnyRole and not from an empty query.
    const response = await sessions.ceo.get('/api/employees');
    expect(response.status()).toBe(403);
    expect(await response.text()).toContain('ไม่มีสิทธิ์เข้าถึงรายการนี้');
  });
});
