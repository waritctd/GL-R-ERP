import { test, expect } from '@playwright/test';
import { uatSessionFor, disposeSessions } from './helpers/api.js';
import { UAT_SALES_ROLES, uatPersonaFor } from './helpers/uat-accounts.js';

// ─────────────────────────────────────────────────────────────────────────────────────────────
// UAT HANDSHAKE — the smallest thing that proves the remote harness works.
//
// Writes NOTHING. Its whole job is to fail loudly, once, on the environment unknowns that would
// otherwise surface as every sales spec failing for reasons that name nothing:
//
//   • Vercel Deployment Protection on the branch preview (global-setup detects it by content-type)
//   • the /api rewrite not reaching the backend from a non-browser client
//   • GLR_HR_SESSION / XSRF-TOKEN not surviving a storageState round trip over https
//   • Render `starter` cold-start blowing the default timeout
//   • a wrong E2E_UAT_PASSWORD, or a persona renamed out from under helpers/uat-accounts.js
//
// Everything above is checked in global-setup.js, which runs BEFORE this file. So if this spec
// runs at all, most of it already passed. What is left to assert here is the half global-setup
// cannot: that the sessions it captured are still usable from inside a test, through the same
// helper the journey specs will use.
//
// Cost: 5 logins (in global-setup), 0 writes, 0 rows. Safe to re-run.
// ─────────────────────────────────────────────────────────────────────────────────────────────

test.describe('@uat-sales UAT harness handshake', () => {
  /** @type {Record<string, import('@playwright/test').APIRequestContext>} */
  const sessions = {};

  test.beforeAll(async () => {
    for (const role of UAT_SALES_ROLES) {
      sessions[role] = await uatSessionFor(role);
    }
  });

  test.afterAll(async () => {
    await disposeSessions(sessions);
  });

  test('every captured session is live and resolves to the role it was captured for', async () => {
    // The assertion that matters is the ROLE, not the 200. A session that authenticates but
    // resolves to a different role than the spec assumes is the shape that makes every downstream
    // permission assertion meaningless while looking green — DivisionAccessPolicy derives the role
    // from the employee's division at login, so a data move in UAT changes it with no code change
    // anywhere. global-setup asserts this once at capture; asserting it again here proves the
    // session SURVIVED serialisation rather than that it was correct at birth.
    const observed = {};
    for (const role of UAT_SALES_ROLES) {
      const response = await sessions[role].get('/api/auth/me', { failOnStatusCode: false });
      expect(response.status(), `${role}: captured session is no longer authenticated`).toBe(200);
      const { user } = await response.json();
      observed[role] = { role: user.role, email: user.email };
    }

    const expected = Object.fromEntries(
      UAT_SALES_ROLES.map((role) => [role, { role, email: uatPersonaFor(role).email }])
    );
    expect(observed, 'captured sessions do not match the personas they were captured for').toEqual(
      expected
    );
  });

  test('a run id was stamped, so anything this run creates is findable afterwards', () => {
    // Every row the journey specs create is tagged with this. Without it a failed run leaves
    // untraceable rows in a shared database, and the documented manual sweep
    // (WHERE title LIKE 'E2E-%') has nothing to match on.
    expect(process.env.E2E_RUN_ID, 'global-setup did not stamp E2E_RUN_ID').toMatch(
      /^E2E-\d{8}T\d{6}Z-[0-9a-f]{4}$/
    );
  });

  test('the sales pipeline is readable by the roles that own it', async () => {
    // A deliberately shallow read: it proves the deployment has a working sales surface and that
    // TicketAccessPolicy.VIEWER_ROLES admits these four, without coupling to any seeded row.
    // Asserting a COUNT here would couple this suite to fixtures humans edit — see §B.3 of the
    // plan on why UAT-TKT-* is never touched.
    for (const role of ['sales', 'import', 'ceo', 'account']) {
      const response = await sessions[role].get('/api/tickets', { failOnStatusCode: false });
      expect(response.status(), `${role} cannot read /api/tickets`).toBe(200);
    }
  });

  test('write credentials are present in the captured sessions', async () => {
    // XSRF-TOKEN is what separates "the session works" from "the session can write". Without it
    // every journey would fail on its FIRST write with a 403 that is indistinguishable at a glance
    // from an authorization denial — the exact confusion helpers/api.js's header is written to
    // design out. Cheaper to catch here than inside a 20-step journey.
    const { cookies } = await sessions.sales.storageState();
    const names = cookies.map((cookie) => cookie.name);
    expect(names, 'sales session is missing its session cookie').toContain('GLR_HR_SESSION');
    expect(names, 'sales session cannot perform a write (CsrfCookieFilter would 403 it)').toContain(
      'XSRF-TOKEN'
    );
  });
});
