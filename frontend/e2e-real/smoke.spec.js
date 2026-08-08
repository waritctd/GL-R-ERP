import { test, expect } from '@playwright/test';
import { PERSONAS, REAL_ROLES, personaFor } from './helpers/accounts.js';
import { expectAuthenticated, loginAs } from './helpers/auth.js';
import { captureJson } from './helpers/network.js';

// End-to-end through every layer: browser → Vite dev proxy → Spring controller → service →
// repository → Postgres, and back into the DOM.
//
// The assertions deliberately key on strings that exist ONLY as rows in the demo database
// ('DEMO-HR01', 'DEMO-TKT-01'). A component rendering hard-coded placeholder data, a fixture,
// or a mock cannot produce them, so seeing one in the DOM is positive evidence the round trip
// actually happened rather than evidence that a page merely rendered without crashing.

/** Pathname of a response's URL, for exact-matching an endpoint in waitForResponse. */
const apiPath = (response) => new URL(response.url()).pathname;

test.describe('real data reaches the browser', () => {
  test('hr sees the employee directory served from Postgres', async ({ page }) => {
    await loginAs(page, 'hr');

    // captureJson, not waitForResponse + a later .json(): HR's shell already fetches
    // /api/employees on the `/` landing (App.jsx:126 → useHrData.js:22), so this waiter can bind
    // to a response belonging to the document goto() is about to replace — and a deferred read of
    // an evicted body is the flake this suite hit on 2026-08-08. helpers/network.js has the full
    // account. Keep the body read eager; do not "simplify" this back to a bare waitForResponse.
    const [{ status, json }] = await Promise.all([
      // Matched on the exact pathname, not a substring: /employees is also the prefix of
      // several other endpoints, and a substring match silently binds to whichever fires
      // first — which is how this assertion would end up inspecting the wrong payload.
      captureJson(page, (r) => apiPath(r) === '/api/employees' && r.request().method() === 'GET'),
      page.goto('/employees'),
    ]);
    expect(status).toBe(200);

    // EmployeesResponse serialises its list as `employees` (not `items` — the record component
    // is renamed on the wire), each row carrying `code` for the employee_code column.
    const { employees, total } = json;
    expect(employees.length).toBeGreaterThan(0);
    expect(total).toBeGreaterThan(0);

    // Tie the DOM back to the row the API actually returned, rather than asserting a count the
    // seed is free to grow. V21 seeds this employee_code, the repository read it back, and the
    // table rendered it — one assertion spanning all three.
    expect(employees.map((employee) => employee.code)).toContain(PERSONAS.hr.employeeCode);
    await expect(page.getByText(PERSONAS.hr.employeeCode, { exact: false }).first()).toBeVisible();
  });

  test('sales sees the deal pipeline served from Postgres', async ({ page }) => {
    await loginAs(page, 'sales');

    // Same eager-read requirement as the employees test above, and for the same reason: sales'
    // `/` landing fetches /api/tickets itself (SalesOverview.jsx:92), so the response this binds
    // to may belong to the document goto() replaces.
    const [{ status, json }] = await Promise.all([
      captureJson(page, (r) => apiPath(r) === '/api/tickets' && r.request().method() === 'GET'),
      page.goto('/tickets'),
    ]);
    expect(status).toBe(200);

    // TicketListResponse serialises its list as `tickets`, same renaming as employees above.
    const { tickets } = json;
    expect(tickets.length).toBeGreaterThan(0);
    // The demo deals are seeded with the 'DEMO-TKT-' code prefix (V21).
    expect(tickets.some((ticket) => ticket.code?.startsWith('DEMO-TKT-'))).toBe(true);
    await expect(page.getByText('DEMO-TKT-', { exact: false }).first()).toBeVisible();
  });

  // One test per persona rather than one loop over all six: each login is a full page load
  // against a dev server, so the loop blew the default 30s timeout, and a single failure in it
  // told you nothing about the other five.
  for (const role of REAL_ROLES) {
    test(`${role} sees its own backend-supplied identity in the shell`, async ({ page }) => {
      const persona = personaFor(role);
      await loginAs(page, role);
      await expectAuthenticated(page);

      // Rendered from AuthResponse — i.e. read off the hr.employee row this session actually
      // authenticated against, not from anything the client chose.
      await expect(page.getByText(persona.email).first()).toBeVisible();
    });
  }
});

test.describe('the route guard and the service agree', () => {
  // Defence in depth, asserted as such. e2e/rbac.spec.js can only ever check the first half
  // (the client-side guard) and says so; this checks that the server independently refuses the
  // same thing. The dangerous configuration is not "both deny" or "both allow" — it is a UI
  // that hides a control the API would have honoured anyway, which is invisible to either
  // layer's tests taken alone.
  const DENIED = [
    { role: 'hr', path: '/tickets', api: '/api/tickets' },
    { role: 'employee', path: '/employees', api: '/api/employees' },
    { role: 'ceo', path: '/employees', api: '/api/employees' },
  ];

  for (const { role, path, api } of DENIED) {
    test(`${role} is refused ${path} by the guard AND by the service`, async ({ page }) => {
      await loginAs(page, role);
      await page.goto(path);

      // Layer 1 — RequireAccess renders AccessDeniedPage in place (issue #391 keeps the URL).
      await expect(
        page.getByRole('heading', { name: 'ไม่มีสิทธิ์เข้าถึงหน้านี้' })
      ).toBeVisible();
      expect(page.url()).toContain(path);

      // Layer 2 — the same session calling the API directly, bypassing the UI entirely. This is
      // the half that matters: a client-side guard is a UX affordance, not a security control.
      const response = await page.request.get(api, { failOnStatusCode: false });
      expect(response.status(), `${role} calling ${api} directly`).toBe(403);
    });
  }
});
