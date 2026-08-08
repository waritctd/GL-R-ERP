import { test, expect } from '@playwright/test';
import { REAL_ROLES } from './helpers/accounts.js';
import { apiSessionsFor, apiWrite, disposeSessions } from './helpers/api.js';
import { writeEndpointsWithId } from './helpers/surface.js';

// ─────────────────────────────────────────────────────────────────────────────
// WRITE-SIDE BREADTH — every resource-scoped write endpoint, every role.
//
// write-overtime.spec.js goes deep on one workflow. This goes wide: it takes every write
// endpoint in API_ROUTES that addresses a resource by id (98 of them, across POST/PUT/PATCH/
// DELETE) and calls each one as each of the six seeded roles — 588 requests — asserting a
// single property:
//
//     NO ROLE EVER GETS A 2xx FROM A WRITE AGAINST A RESOURCE THAT DOES NOT EXIST.
//
// That is a real correctness property, not a tautology. A 2xx here would mean an endpoint
// mutating (or creating) something without first establishing that the thing it was asked to
// act on exists — the shape of bug where `POST /api/x/{id}/approve` on a bogus id quietly
// writes a row, or an upsert-by-id invents a record with an attacker-chosen key.
//
// WHY THIS IS SAFE TO RUN AGAINST A SHARED DATABASE. The id is a placeholder that matches no
// row, and the set is deliberately restricted to resource-scoped writes (see
// surface.js#writeEndpointsWithId). Collection-level writes — POST /api/employees, POST
// /api/overtime — are excluded precisely because those CAN create something: sweeping them
// across six roles would mean writing rows into a shared database in order to discover who is
// allowed to write rows. The measured result of the whole sweep is 0 × 2xx, which is both the
// assertion and the evidence that nothing was mutated.
// ─────────────────────────────────────────────────────────────────────────────

const WRITE_ENDPOINTS = writeEndpointsWithId();

// Real defects, recorded as an EXACT expectation rather than skipped — the same pattern (and
// for the same reason) as api-surface.spec.js's KNOWN_SERVER_ERRORS: a new server error fails
// the sweep, and so does fixing one of these without removing its entry.
//
// PriceImportController answers a non-existent id with 500 rather than 404, on every verb.
// api-surface.spec.js already records the GET half of this (`GET /api/price-import/profile/
// {factoryId}`); these are the write half, and together they make it one coherent finding
// rather than three unrelated ones: that controller has no missing-resource handling at all.
// Only `import` and `ceo` reach it — every other role is refused 403 before the lookup, which
// is itself worth noticing, since it means the gap is invisible to five of the six roles.
const KNOWN_SERVER_ERRORS = [
  'ceo POST /api/price-import/commit/999999',
  'ceo POST /api/price-import/validate/999999',
  'import POST /api/price-import/commit/999999',
  'import POST /api/price-import/validate/999999',
];

test.describe('write endpoints refuse a resource that does not exist', () => {
  /** @type {Record<string, import('@playwright/test').APIRequestContext>} */
  let sessions;

  test.beforeAll(async () => {
    sessions = await apiSessionsFor(REAL_ROLES);
  });

  test.afterAll(async () => {
    await disposeSessions(sessions);
  });

  test('the swept set is substantial and covers every write verb', () => {
    // A guard on the derivation itself. If the verb parser in surface.js broke, this set would
    // collapse toward empty and the sweep below would pass while testing nothing.
    expect(WRITE_ENDPOINTS.length).toBeGreaterThan(70);
    const verbs = new Set(WRITE_ENDPOINTS.map((endpoint) => endpoint.verb));
    expect([...verbs].sort()).toEqual(['DELETE', 'PATCH', 'POST', 'PUT']);
    expect(verbs.has('GET'), 'GET must never appear in the write set').toBe(false);
  });

  test('no role gets a 2xx, and the only server errors are the known ones', async () => {
    test.setTimeout(300_000);

    const succeeded = [];
    const serverErrors = [];

    for (const role of REAL_ROLES) {
      for (const { path, verb } of WRITE_ENDPOINTS) {
        const response = await apiWrite(sessions[role], verb, path, {});
        const status = response.status();

        if (status >= 200 && status < 300) {
          succeeded.push(`${role} ${verb} ${path} → ${status}`);
        } else if (status >= 500) {
          serverErrors.push(`${role} ${verb} ${path}`);
        }
        // 400 (empty body fails validation), 403 (role gate) and 404 (no such row) are all
        // correct answers here — each means the request was understood and refused.
      }
    }

    expect(
      succeeded,
      'a write against a non-existent resource succeeded — the endpoint is missing an ' +
        'existence check and may be creating or mutating something it should not'
    ).toEqual([]);

    expect(
      serverErrors.sort(),
      'server errors from write endpoints. Anything beyond KNOWN_SERVER_ERRORS is a new defect; ' +
        'anything missing from it has been fixed and should be deleted from that list'
    ).toEqual([...KNOWN_SERVER_ERRORS].sort());
  });
});
