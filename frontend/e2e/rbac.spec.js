import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
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

const __dirname = path.dirname(fileURLToPath(import.meta.url));

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
// enforced — this spec now guards against it regressing back outside. (NOTE:
// GET /api/catalog still has no backend role check — a separate follow-up.)
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

        if (expected) {
          await expect
            .poll(() => new URL(page.url()).pathname, {
              message: `${role} @ ${routePath}: expected ALLOW (render), canAccessPath said true`,
            })
            .toBe(routePath);
        } else {
          await expect
            .poll(() => new URL(page.url()).pathname, {
              message: `${role} @ ${routePath}: expected REDIRECT to /, canAccessPath said false`,
            })
            .toBe('/');
        }
      }
    });
  }
});

test('rbac oracle vs docs/ux-ui-audit/data/shoot-manifest.json — drift is logged, not asserted', async () => {
  // The manifest is a UX-audit-era regression baseline, not this spec's
  // source of truth (that's canAccessPath itself, above). It predates
  // /finance, /pricing-requests, /procurement, and the canViewDealPipeline
  // split (role-scoped-views program) — so drift here is EXPECTED, not a
  // failure. This just makes that drift visible instead of silent.
  const manifestPath = path.join(__dirname, '..', '..', 'docs', 'ux-ui-audit', 'data', 'shoot-manifest.json');
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));

  const seen = new Set();
  const drifts = [];
  for (const entry of manifest) {
    const key = `${entry.role}|${entry.path}`;
    if (seen.has(key)) continue; // dedupe across viewports (desktop/mobile shoot the same path twice)
    seen.add(key);

    if (!SEEDED_ROLES.includes(entry.role)) continue;

    const liveOracleAllow = canAccessPath(entry.path, seededUser(entry.role));
    const manifestAllow = !entry.redirected;

    if (liveOracleAllow !== manifestAllow) {
      drifts.push({ role: entry.role, path: entry.path, manifestAllow, liveOracleAllow });
    }
  }

  // eslint-disable-next-line no-console
  console.log(
    drifts.length
      ? `[rbac drift vs shoot-manifest] ${drifts.length} path(s) differ from the stale manifest baseline:\n${JSON.stringify(drifts, null, 2)}`
      : '[rbac drift vs shoot-manifest] no drift found',
  );

  // Informational only — never fail the suite on manifest staleness.
  expect(Array.isArray(drifts)).toBe(true);
});
