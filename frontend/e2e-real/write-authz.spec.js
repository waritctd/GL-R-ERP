import { test, expect } from '@playwright/test';
import { REAL_ROLES } from './helpers/accounts.js';
import { apiSessionsFor, apiWrite, disposeSessions } from './helpers/api.js';
import { writeEndpointsWithId } from './helpers/surface.js';

// WRITE-SIDE BREADTH — every resource-scoped write endpoint, every role. write-overtime.spec.js goes deep on
// one workflow. That is a real correctness property, not a tautology. WHY THIS IS SAFE TO RUN AGAINST A SHARED
// DATABASE.

const WRITE_ENDPOINTS = writeEndpointsWithId();

// Empty, and that is the assertion — the same pattern (and for the same reason) as
// api-surface.spec.js's KNOWN_SERVER_ERRORS: a new server error fails the sweep, and so does
// fixing one without removing its entry.
//
// An unknown id must answer 404, never 500: PriceImportService#requireDraft catches
// EmptyResultDataAccessException and raises 404 before the DRAFT status check — a missing row is
// not a status conflict, so 409 would be a different wrong answer.
//
// Only `import` and `ceo` ever reach such a lookup; every other role is refused 403 before it,
// which is why a missing-resource 500 stays invisible to a sweep run as another role.
const KNOWN_SERVER_ERRORS = [];

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
    // A guard on the derivation itself. If the verb parser in surface.js broke, this set would collapse toward
    // empty and the sweep below would pass while testing nothing.
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
        // 400 (empty body fails validation), 403 (role gate) and 404 (no such row) are all correct answers here — each
        // means the request was understood and refused.
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
