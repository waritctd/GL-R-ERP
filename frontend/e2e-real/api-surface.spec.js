import { test, expect } from '@playwright/test';
import { DEMO_PASSWORD, PERSONAS, REAL_ROLES } from './helpers/accounts.js';
import { anonApi, apiSessionsFor, disposeSessions } from './helpers/api.js';
import { ANONYMOUS_ALLOWLIST, apiSurface, isHeavyPath, readableSurface } from './helpers/surface.js';

// ─────────────────────────────────────────────────────────────────────────────
// WHOLE-SURFACE COVERAGE — every endpoint the BACKEND serves, against the real backend.
//
// api-authz.spec.js asserts a handful of gates in depth, each hand-verified against the Java
// class that decides it. That is the right shape for a gate, and the wrong shape for coverage:
// it says nothing about the other ~270 endpoints, and a new one added tomorrow is outside it.
//
// This file takes the opposite approach. The surface comes from `docs/api/api-surface.json` —
// generated from springdoc's /v3/api-docs, i.e. Spring's own handler mappings — so it is defined
// by what the application dispatches, not by what the frontend happens to call. What it asserts
// of every endpoint is deliberately narrow but universally true, and each property is one that
// only a real backend can answer:
//
//   1. the authentication boundary holds everywhere    (anonymous ⇒ 401)
//   2. no endpoint 5xx's for any authenticated role     (a 500 is a bug, whoever asks)
//   3. the surface is actually covered                  (nothing silently skipped)
//
// Property 3 is what keeps 1 and 2 honest over time. Without it "we cover the whole API" is a
// claim about the day it was written — and for a while it was exactly that: driving the sweep
// from API_ROUTES left 49 endpoints invisible and collapsed ~22 deal actions into one path that
// matches no controller. See helpers/surface.js for the full account.
// ─────────────────────────────────────────────────────────────────────────────

const SURFACE = apiSurface();
// The GET subset, taken from each endpoint's OWN declared verb rather than inferred by parsing
// hrApi.js's `method:` option bags. That inference is what previously left 23 routes with no
// resolved verb at all, silently outside the authenticated sweep.
const READABLE_SURFACE = readableSurface();

test.describe('API surface', () => {
  test('the surface is non-trivial and every entry is a concrete /api path', () => {
    // A guard on the derivation itself. If the digest failed to load or its shape changed,
    // SURFACE would quietly shrink and every sweep below would still pass — while covering
    // nothing. Fail loudly instead.
    expect(SURFACE.length).toBeGreaterThan(250);
    for (const { name, path } of SURFACE) {
      expect(path, `${name} resolved to a non-/api path`).toMatch(/^\/api\//);
      // No unsubstituted template parameter may reach the wire: `/api/tickets/{id}/cancel` sent
      // literally would 400 on type conversion and the sweep would report that instead of the
      // endpoint's real behaviour.
      expect(path, `${name} still contains an unsubstituted path parameter`).not.toMatch(/[{}]/);
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

  test("every endpoint rejects an anonymous request sent with its OWN verb with 401", async () => {
    // The GET/POST sweeps above are broad but ask most endpoints a question they do not answer.
    // This one asks each endpoint exactly what it implements — a DELETE at a DELETE-only route,
    // a PATCH at a PATCH-only route — so nothing is refused merely for using the wrong verb.
    // Only possible now that the surface carries verbs from the backend's own handler mappings.
    test.setTimeout(120_000);
    const anon = await anonApi();
    const leaked = [];
    try {
      for (const { name, path, verb } of SURFACE) {
        if (ANONYMOUS_ALLOWLIST.includes(path)) continue;
        const method = verb.toLowerCase();
        const response = await anon.fetch(path, {
          method,
          failOnStatusCode: false,
          ...(method === 'get' || method === 'delete' ? {} : { data: {} }),
        });
        if (response.status() !== 401) leaked.push(`${name} → ${response.status()}`);
      }
    } finally {
      await anon.dispose();
    }

    expect(
      leaked,
      'endpoints reachable without a session when asked with the verb they actually implement'
    ).toEqual([]);
  });

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

  test('the readable subset declared by the backend is substantial', () => {
    // The no-5xx sweep below covers the endpoints the backend declares as GET. If the digest
    // failed to load, READABLE would collapse toward empty and every sweep would pass while
    // testing almost nothing — the same "green means covered" lie the drift guards exist to
    // prevent. Pin a floor well under the current 104 but far above a broken derivation.
    expect(READABLE_SURFACE.length).toBeGreaterThan(90);
    expect(READABLE_SURFACE.length).toBeLessThan(SURFACE.length);
  });

  // Real defects this sweep found, recorded rather than skipped.
  //
  // Listing them as an EXACT expectation (not a filter, not a skip) keeps them visible in both
  // directions: a new server error fails the test, and so does fixing one of these without
  // removing its entry. A silent exclusion would let the first quietly become permanent.
  // Empty, and that is the assertion — every entry this list once held has been fixed.
  //
  // It previously carried `priceImport.profile() /api/price-import/profile/999999 → 500`, reachable
  // only by ceo and import (PriceImportController gates on requireAnyRole(user, "ceo", "import"),
  // so every other role was refused 403 before the defect). PriceImportService#getRawProfile now
  // catches EmptyResultDataAccessException and raises a 404, matching the idiom #loadProfile in the
  // same class already used for the identical query.
  //
  // Keep this as an exact expectation rather than deleting the machinery: the value is symmetric.
  // A new server error fails the test, and so does fixing one without removing its entry. Add a row
  // here only with the same detail — which roles reach it, and why it is not simply fixed.
  const KNOWN_SERVER_ERRORS = [];

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

      expect(swept, 'endpoints swept').toBeGreaterThan(85);
      expect(
        failures.sort(),
        `server errors reached by ${role}. Anything here beyond KNOWN_SERVER_ERRORS is a new ` +
          'defect; anything missing from it has been fixed and should be deleted from that list'
      ).toEqual(expected);
    });
  }
});
