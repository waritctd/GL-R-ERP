import { test, expect } from '@playwright/test';
import { DEMO_PASSWORD, PERSONAS, REAL_ROLES } from './helpers/accounts.js';
import { anonApi, apiSessionsFor, disposeSessions } from './helpers/api.js';
import { ANONYMOUS_ALLOWLIST, apiSurface, isHeavyPath, isReadable } from './helpers/surface.js';

// ─────────────────────────────────────────────────────────────────────────────
// WHOLE-SURFACE COVERAGE — every endpoint the frontend declares, against the real backend.
//
// api-authz.spec.js asserts a handful of gates in depth, each hand-verified against the Java
// class that decides it. That is the right shape for a gate, and the wrong shape for coverage:
// it says nothing about the other ~200 endpoints, and a new one added tomorrow is outside it.
//
// This file takes the opposite approach. It walks API_ROUTES — the app's own table, the one
// hrApi.js actually calls — so the surface under test is defined by the code rather than by a
// list someone has to remember to update. What it asserts of every endpoint is deliberately
// narrow but universally true, and each property is one that only a real backend can answer:
//
//   1. the authentication boundary holds everywhere    (anonymous ⇒ 401)
//   2. no endpoint 5xx's for any authenticated role     (a 500 is a bug, whoever asks)
//   3. the surface is actually covered                  (nothing silently skipped)
//
// Property 3 is what keeps 1 and 2 honest over time. Without it "we cover the whole API" is a
// claim about the day it was written.
// ─────────────────────────────────────────────────────────────────────────────

const SURFACE = apiSurface();
// The subset hrApi.js actually GETs. Sweeping the rest with a GET would say nothing about them:
// this backend answers a wrong-method request 500 rather than 405 (see surface.js), so ~130
// endpoints would report a "server error" that is entirely an artefact of the question asked.
// Their authentication is still covered — the anonymous sweeps below hit the whole surface with
// both GET and POST.
const READABLE_SURFACE = SURFACE.filter((entry) => isReadable(entry.name));

test.describe('API surface', () => {
  test('the surface is non-trivial and every entry is a concrete /api path', () => {
    // A guard on the walker itself. If API_ROUTES were restructured such that the walk stopped
    // resolving (a nested export, a factory whose arity changed), SURFACE would quietly shrink
    // and every sweep below would still pass — while covering nothing. Fail loudly instead.
    expect(SURFACE.length).toBeGreaterThan(150);
    for (const { name, path } of SURFACE) {
      expect(path, `${name} resolved to a non-/api path`).toMatch(/^\/api\//);
    }
  });

  // Both read and write verbs, because SecurityConfig's allowlist entries are METHOD-scoped
  // (`.requestMatchers(HttpMethod.POST, "/api/auth/login")`). A sweep that only sent GETs would
  // be blind to the change that actually matters — someone adding a permitAll for POST on some
  // path, leaving it anonymously writable while every GET still correctly answered 401.
  //
  // Sending a POST anonymously is safe by construction: `.anyRequest().authenticated()` rejects
  // in the filter chain, before Spring MVC resolves a handler, so nothing runs and nothing is
  // written. A 405 in these results would itself be a finding — it would mean the request got
  // past authentication far enough for method resolution to matter.
  for (const method of ['get', 'post']) {
    test(`every endpoint rejects an anonymous ${method.toUpperCase()} with 401`, async () => {
      test.setTimeout(120_000);
      const anon = await anonApi();
      const leaked = [];
      try {
        for (const { name, path } of SURFACE) {
          if (ANONYMOUS_ALLOWLIST.includes(path)) continue;
          const response =
            method === 'get'
              ? await anon.get(path, { failOnStatusCode: false })
              : await anon.post(path, { data: {}, failOnStatusCode: false });
          if (response.status() !== 401) {
            leaked.push(`${name} ${path} → ${response.status()}`);
          }
        }
      } finally {
        await anon.dispose();
      }

      expect(
        leaked,
        `endpoints reachable without a session via ${method.toUpperCase()} (each must be ` +
          "either 401 or in surface.js's ANONYMOUS_ALLOWLIST, mirroring SecurityConfig)"
      ).toEqual([]);
    });
  }

  test('the two allowlisted endpoints really are anonymously reachable', async () => {
    // The complement of the sweep above. Silencing a sweep failure by appending a path to
    // ANONYMOUS_ALLOWLIST must not become a quiet way to drop a real endpoint out of the
    // authentication assertion — anything added there has to be justified here too.
    //
    // Status alone cannot carry that: AuthService answers a bad login with 401 as well, so a
    // blanket "not 401" would also be satisfied by an endpoint the filter chain had rejected
    // outright. Each path therefore gets an assertion only a request that REACHED ITS
    // CONTROLLER could produce.
    expect(ANONYMOUS_ALLOWLIST).toEqual(['/api/auth/login', '/api/attendance/punch']);

    const anon = await anonApi();
    try {
      // Real credentials → a real session. Only the controller can produce this.
      const login = await anon.post('/api/auth/login', {
        data: { email: PERSONAS.hr.email, password: DEMO_PASSWORD },
        failOnStatusCode: false,
      });
      expect(login.status(), 'POST /api/auth/login must be reachable without a session').toBe(200);

      // Device endpoint: an empty body fails @Valid (400) or the X-GLR-Agent-Token check (403).
      // Either proves it got past the filter chain; a 401 would mean it did not. Nothing is
      // written — validation and the token check both run before any record is created.
      const punch = await anon.post('/api/attendance/punch', {
        data: {},
        failOnStatusCode: false,
      });
      expect(
        punch.status(),
        'POST /api/attendance/punch is allowlisted for token-authenticated scanners, so the ' +
          'filter chain must not reject it with 401'
      ).not.toBe(401);
      expect(
        punch.status(),
        'and it must still refuse an unauthenticated, empty punch'
      ).toBeGreaterThanOrEqual(400);
    } finally {
      await anon.dispose();
    }
  });
});

test.describe('API surface — authenticated sweep', () => {
  /** @type {Record<string, import('@playwright/test').APIRequestContext>} */
  let sessions;

  test.beforeAll(async () => {
    sessions = await apiSessionsFor(REAL_ROLES);
  });

  test.afterAll(async () => {
    await disposeSessions(sessions);
  });

  test('the readable subset resolved from hrApi.js is substantial', () => {
    // The no-5xx sweep below only covers endpoints hrApi.js actually GETs. If the verb parser
    // in surface.js broke, READABLE would collapse toward empty and every sweep would pass
    // while testing almost nothing — the same "green means covered" lie the drift guards exist
    // to prevent. Pin a floor well under the current 86 but far above a broken parser's output.
    expect(READABLE_SURFACE.length).toBeGreaterThan(60);
    expect(READABLE_SURFACE.length).toBeLessThan(SURFACE.length);
  });

  // Real defects this sweep found, recorded rather than skipped.
  //
  // Listing them as an EXACT expectation (not a filter, not a skip) keeps them visible in both
  // directions: a new server error fails the test, and so does fixing one of these without
  // removing its entry. A silent exclusion would let the first quietly become permanent.
  const KNOWN_SERVER_ERRORS = [
    {
      entry: 'priceImport.profile() /api/price-import/profile/999999 → 500',
      // PriceImportController gates on requireAnyRole(user, "ceo", "import"); everyone else is
      // refused with 403 before reaching the defect.
      roles: ['ceo', 'import'],
      // GET with a real factory id returns 200 and its profile JSON; an unknown id 500s instead
      // of 404. A missing row is not a server fault — this should be the same "ไม่พบรายการนี้"
      // 404 the neighbouring endpoints return. Left unfixed here deliberately: this suite is
      // test-only work, and changing a controller's response status is an API-contract change
      // that belongs in its own branch (CLAUDE.md, "as a side effect").
      reason: 'unknown factoryId yields 500 rather than 404',
    },
  ];

  for (const role of REAL_ROLES) {
    test(`${role} — no readable endpoint answers with a server error`, async () => {
      test.setTimeout(120_000);
      const failures = [];
      let swept = 0;

      for (const { name, path } of READABLE_SURFACE) {
        if (isHeavyPath(path)) continue;
        const response = await sessions[role].get(path, { failOnStatusCode: false });
        swept += 1;
        // 4xx is a legitimate answer — 403 (role gate), 404 (the placeholder id), 400 (a
        // required query param this sweep doesn't supply). All mean the request was understood
        // and handled. 5xx means it wasn't: an unhandled exception, a broken query, a null
        // dereference. That is a defect regardless of which role provoked it.
        if (response.status() >= 500) {
          failures.push(`${name} ${path} → ${response.status()}`);
        }
      }

      const expected = KNOWN_SERVER_ERRORS.filter((known) => known.roles.includes(role))
        .map((known) => known.entry)
        .sort();

      expect(swept, 'endpoints swept').toBeGreaterThan(60);
      expect(
        failures.sort(),
        `server errors reached by ${role}. Anything here beyond KNOWN_SERVER_ERRORS is a new ` +
          'defect; anything missing from it has been fixed and should be deleted from that list'
      ).toEqual(expected);
    });
  }
});
