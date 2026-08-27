import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { test, expect } from '@playwright/test';
import { canAccessPath } from '../src/app/permissions.js';
import { REAL_ROLES } from './helpers/accounts.js';
import { loginAs, sessionUser, spaGoto } from './helpers/auth.js';

// ─────────────────────────────────────────────────────────────────────────────
// WHOLE-APP ROUTE COVERAGE — every route in App.jsx, opened by every seeded role, in a real
// browser against the real backend.
//
// e2e/rbac.spec.js already walks most of these routes against the MOCK frontend and checks
// render-vs-redirect against canAccessPath. It says in its own header that this proves nothing
// about the server. Two things are different here, and both need a real backend:
//
//   1. For every route a role may open, the page must actually LOAD — no error boundary, and
//      no 5xx from any request it fires. Against mocks every page loads by construction,
//      because the fixture always answers; the interesting failures (a broken query, a DTO the
//      UI can't parse, an endpoint the page calls that doesn't exist) only exist server-side.
//   2. The permission oracle is the REAL principal. canAccessPath is called with the `user`
//      object the backend issued for that session, not a hand-maintained stand-in — so if
//      DivisionAccessPolicy resolved a different role or employeeId than the frontend's table
//      assumes, that shows up here.
//
// The route list is checked against App.jsx on every run (see the completeness test), so a new
// route cannot be added without either being covered or being explicitly excluded with a reason.
// ─────────────────────────────────────────────────────────────────────────────

// Concrete ids for the dynamic segments. Where the demo seed has a real row, the real id is
// used so the page renders its populated state rather than a not-found; where it does not, the
// id is a placeholder and the assertion is only that the page handles "no such record" without
// erroring — which is itself worth asserting.
const ROUTES = [
  '/',
  '/hr',
  '/employees',
  '/employees/4', // DEMO-HR01 (V21) — a real employee row
  '/requests',
  '/my-requests', // Navigate alias → /profile
  '/profile',
  '/tax-allowance',
  '/tax-allowance-review',
  '/employee-requests',
  '/overtime', // Navigate alias → /employee-requests?tab=ot
  '/leave',
  '/leave/new',
  '/payroll',
  '/payroll/deduction-shortfalls', // Garnishment shortfall ledger (issue #376 read surface)
  '/payroll/deduction-consents', // Written-consent register (issue #376 read + write surface)
  '/settings/leave-policy', // §5 announcement PDF upload (issue #744)
  '/settings/attendance-calendar',
  '/attendance',
  '/tickets',
  '/tickets/1', // DEMO-TKT-01 (V21) — a real deal
  '/tickets/1/deposit',
  '/pricing-requests',
  '/pricing-requests/1',
  '/fulfilment', // งานนำเข้า — Import's fulfilment workspace (import/ceo; every other role refuses)
  '/commissions',
  '/finance',
  '/price-import',
  '/ceo-settings',
  '/catalog',
];

// Declared in App.jsx but deliberately outside this sweep, each with the reason.
const EXCLUDED_ROUTE_PATTERNS = [
  // The catch-all; it has no path of its own, it redirects whatever is left to '/'.
  '*',
  // A test-only probe component mounted for the mock suite's SafeForm guard spec — not a
  // product route, and not something a real user can reach meaningfully.
  '/__e2e/safe-form-submitter-probe',
];

function declaredRoutes() {
  // Parsed from the source rather than imported, because App.jsx's routes are JSX inside a
  // component — there is no exported table to read. This is the coverage oracle: it is what
  // makes "the whole app" a claim the suite re-checks rather than a claim made once.
  const appJsx = readFileSync(
    fileURLToPath(new URL('../src/App.jsx', import.meta.url)),
    'utf8'
  );
  return [...appJsx.matchAll(/path="([^"]+)"/g)].map((match) => match[1]);
}

/** '/employees/4' → '/employees/:id', so a concrete route can be matched to its declaration. */
function toPattern(route, declared) {
  if (declared.includes(route)) return route;
  const segments = route.split('/');
  return (
    declared.find((candidate) => {
      const candidateSegments = candidate.split('/');
      if (candidateSegments.length !== segments.length) return false;
      return candidateSegments.every(
        (segment, i) => segment.startsWith(':') || segment === segments[i]
      );
    }) ?? null
  );
}

test.describe('route coverage is complete', () => {
  test('every route declared in App.jsx is swept or explicitly excluded', () => {
    const declared = declaredRoutes();
    expect(declared.length, 'parsed no routes out of App.jsx — the regex has gone stale').
      toBeGreaterThan(20);

    const coveredPatterns = new Set(
      ROUTES.map((route) => toPattern(route, declared)).filter(Boolean)
    );
    const uncovered = declared.filter(
      (route) => !EXCLUDED_ROUTE_PATTERNS.includes(route) && !coveredPatterns.has(route)
    );

    expect(
      uncovered,
      'routes exist in App.jsx that this suite never opens — add them to ROUTES (with a ' +
        'concrete id if they are dynamic), or to EXCLUDED_ROUTE_PATTERNS with a reason'
    ).toEqual([]);
  });

  test('every route in ROUTES still exists in App.jsx', () => {
    const declared = declaredRoutes();
    const orphaned = ROUTES.filter((route) => toPattern(route, declared) === null);
    // The other direction: a route deleted from the app should not linger here quietly
    // passing as "denied to everyone" — which is exactly what a removed route looks like.
    expect(orphaned, 'ROUTES entries with no matching <Route> in App.jsx').toEqual([]);
  });
});

test.describe('every role can open every route it is allowed to open', () => {
  for (const role of REAL_ROLES) {
    test(`${role} — all ${ROUTES.length} routes load or refuse cleanly`, async ({ page }) => {
      test.setTimeout(180_000);

      await loginAs(page, role);
      // The real principal, straight from the session — see this file's header.
      const user = await sessionUser(page);
      expect(user.role).toBe(role);

      // Attributed to whichever route was open when the response landed — a bare list of
      // failing endpoints would say a page somewhere is broken without saying which one.
      let currentRoute = '(during login)';
      const serverErrors = [];
      page.on('response', (response) => {
        if (response.status() >= 500 && response.url().includes('/api/')) {
          serverErrors.push(
            `${currentRoute} → ${response.status()} ${new URL(response.url()).pathname}`
          );
        }
      });

      // Uncaught JS exceptions, attributed the same way. This is deliberately NOT the same check
      // as the error boundary below: React catches a throw during *render* and shows the fallback,
      // but a throw from an event handler, an effect's async continuation, or a promise rejection
      // never reaches a boundary at all. Those leave the page looking fine and silently broken,
      // which is precisely the failure this sweep could not see before.
      //
      // Asserted with no allowlist, because an uncaught exception has no benign form.
      const pageErrors = [];
      page.on('pageerror', (error) => {
        pageErrors.push(`${currentRoute} → ${error.name}: ${error.message}`);
      });

      const crashed = [];
      const misrendered = [];

      for (const route of ROUTES) {
        currentRoute = route;
        await spaGoto(page, route);
        // Settle before waiting on idle. React Query fires this route's requests during the
        // render that follows popstate, so networkidle asked for immediately would resolve
        // against the PREVIOUS route's quiet network and wave this one through unloaded.
        await page.waitForTimeout(250);
        await page
          .waitForLoadState('networkidle', { timeout: 15_000 })
          .catch(() => {/* a page that keeps polling never idles; the checks below still hold */});

        const allowed = canAccessPath(route, user);
        const deniedShown = await page
          .getByRole('heading', { name: 'ไม่มีสิทธิ์เข้าถึงหน้านี้' })
          .isVisible()
          .catch(() => false);
        // ErrorBoundary's DefaultErrorFallback — a component threw while rendering this route.
        const boundaryShown = await page
          .getByText('โหลดหน้านี้ไม่สำเร็จ')
          .first()
          .isVisible()
          .catch(() => false);

        if (boundaryShown) crashed.push(route);
        if (allowed && deniedShown) misrendered.push(`${route} — allowed but refused`);
        if (!allowed && !deniedShown) misrendered.push(`${route} — denied but rendered`);
      }

      expect(crashed, `${role}: routes that hit the React error boundary`).toEqual([]);
      expect(misrendered, `${role}: guard disagreed with canAccessPath`).toEqual([]);
      expect(serverErrors, `${role}: 5xx responses while walking the app`).toEqual([]);
      expect(pageErrors, `${role}: uncaught JS exceptions while walking the app`).toEqual([]);
    });
  }
});
