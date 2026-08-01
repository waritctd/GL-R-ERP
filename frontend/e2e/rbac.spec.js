import { test, expect } from '@playwright/test';
import { SEEDED_ROLES, loginAs, seededUser, spaGoto } from './helpers/auth.js';
// The exact function App.jsx's <RequireAccess> uses to decide render-vs-
// redirect (app/RequireAccess.jsx). permissions.js has no React/JSX
// dependency (only imports ROLE_PERMISSIONS, a plain object from
// api/routes.js), so it is directly importable here — this spec is the
// app's OWN guard used as its own oracle, not a hand-maintained matrix.
import { canAccessPath } from '../src/app/permissions.js';

// ─────────────────────────────────────────────────────────────────────────
// FRONTEND-GATING ONLY. This spec proves react-router-dom's client-side
// route guard (RequireAccess -> canAccessPath) renders or redirects exactly
// as app/permissions.js says it should, for every seeded role, against a
// real browser + the mock frontend. It does NOT verify backend
// authorization — the mock's authz is known non-authoritative (CLAUDE.md);
// real-Java authz is Stage L (backend/.../AttendanceScopeIntegrationTest
// and friends). Never cite this spec as evidence a route/action is
// permission-safe against the real Spring services.
//
// Scope: the 7 seeded roles only (hr, employee, sales, sales_manager,
// import, account, ceo). warehouse/qc are out of scope — no seeded persona
// exists for either yet (owner decision, see docs/agent-handoffs).
// ─────────────────────────────────────────────────────────────────────────


// Guarded routes, pulled from App.jsx's <Route element={<RequireAccess
// user={user} />}> block. SALES_ENABLED defaults true under
// VITE_USE_MOCKS=true (no VITE_ENABLE_SALES=false override in this suite),
// so every sales-gated route below is registered. Dynamic segments get one
// concrete id each — RequireAccess decides on the pathname alone, before
// the page ever fetches anything, so the underlying record's existence
// doesn't affect the render-vs-redirect outcome being tested here.
//
// /catalog IS now asserted: its <Route> was moved INSIDE the RequireAccess
// wrapper (fix/catalog-route-guard) so the canViewCatalog guard is actually
// enforced — this spec now guards against it regressing back outside. What that
// guard controls is who sees the SCREEN: GET /api/catalog and /api/catalog/prices
// are open to any authenticated user by product decision (#205; owner ruling
// 2026-08-01 closing #388), so this is a UX assertion, not a security one.
// /overtime and /my-requests remain excluded: both are RequireAccess-guarded
// Navigate ALIASES (to /employee-requests?tab=ot and /profile respectively)
// with guards identical to their canonical targets, so testing them adds
// redirect-chain complexity with no unique signal.
const GUARDED_ROUTES = [
  '/hr',
  '/employees',
  '/employees/1',
  '/requests',
  '/profile',
  '/employee-requests',
  '/leave',
  '/payroll',
  '/ticket-overview',
  '/tickets',
  '/tickets/1',
  '/commissions',
  '/catalog',
  '/finance',
  '/price-import',
  '/ceo-settings',
  '/factory-purchase-orders',
  '/factory-purchase-orders/1',
  '/procurement',
  '/pricing-requests',
  '/pricing-requests/1',
];

test.describe('rbac gating (frontend-gating only, not a backend authz proof)', () => {
  for (const role of SEEDED_ROLES) {
    test(`${role} — render-vs-redirect matches canAccessPath for every guarded route`, async ({ page }) => {
      test.setTimeout(60_000);
      await loginAs(page, role);
      // The exact `user` shape App.jsx holds for this persona at runtime
      // (see helpers/auth.js's SEEDED_ROLE_EMPLOYEE_ID doc comment) — NOT a
      // synthetic stand-in. This matters for the 3 routes gated on
      // `!!user.employeeId` (/profile, /employee-requests, /leave): sales,
      // import, and account are seeded with employeeId: null (no linked
      // payroll employee row), so those 3 personas are genuinely denied
      // those self-service routes at runtime — correctly reflected here,
      // not papered over with a forced-truthy id.
      const user = seededUser(role);

      for (const routePath of GUARDED_ROUTES) {
        const expected = canAccessPath(routePath, user);
        await spaGoto(page, routePath);

        // Both branches now land on `routePath` — issue #391 stopped denied routes
        // redirecting to '/' so a shared deep link keeps its URL and the refusal
        // survives a reload. The URL alone therefore no longer distinguishes allow
        // from deny; the access-denied view's presence is what does.
        await expect
          .poll(() => new URL(page.url()).pathname, {
            message: `${role} @ ${routePath}: URL must be preserved either way (#391)`,
          })
          .toBe(routePath);

        const denial = page.getByRole('heading', { name: 'ไม่มีสิทธิ์เข้าถึงหน้านี้' });
        if (expected) {
          await expect(denial, `${role} @ ${routePath}: expected ALLOW (render), canAccessPath said true`)
            .toBeHidden();
        } else {
          await expect(denial, `${role} @ ${routePath}: expected DENY in place, canAccessPath said false`)
            .toBeVisible();
        }
      }
    });
  }
});
