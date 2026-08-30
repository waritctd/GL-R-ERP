// Build-time feature flags.
//
// Read through `env` rather than `import.meta.env.VITE_x` directly, because
// this module is evaluated in TWO kinds of host and only one of them defines
// `import.meta.env`:
//
//   - Vite-processed (browser bundle, dev server, vitest) — `import.meta.env`
//     is injected and carries every VITE_-prefixed var.
//   - Plain Node, no Vite — the PLAYWRIGHT RUNNER process.
//     `e2e-real/route-coverage.spec.js` imports `app/permissions.js` to use
//     canAccessPath as its permission oracle, and permissions.js imports this
//     file, so features.js is evaluated by Playwright's own transform where
//     `import.meta.env` is `undefined` and a bare property read THROWS. That is
//     not hypothetical: it took the whole e2e-real job down on the first CI run
//     of this branch, at the pre-existing SALES_ENABLED line.
//
// `process.env` is the fallback so the runner's oracle can be pointed at the
// same values as the dev server it drives — playwright.real.config.js sets
// VITE_SELF_SERVICE_ONLY on both sides for exactly that reason. An oracle that
// disagrees with the app under test fails every route it disagrees about.
const env = import.meta.env ?? (typeof process !== 'undefined' ? process.env : {});

// Sales/CRM ships as part of the product. It was hidden for the HR-core-only
// v0.1.0 release; that release is historical and the freeze is lifted.
//
// The flag is an off-switch, not an on-switch: sales is enabled unless
// VITE_ENABLE_SALES is explicitly "false". That direction matters — the
// production build does not set this var, so an `=== 'true'` check left sales
// disabled in production regardless of what the deploy intended. (This comment
// used to say production sets "no VITE_ vars at all" because vercel.json has no
// env block and .env* is gitignored. That is false — it misses the Vercel
// dashboard; see the SELF_SERVICE_ONLY note below. The conclusion for THIS flag
// is unchanged: VITE_ENABLE_SALES is genuinely absent from the deployed bundle.)
export const SALES_ENABLED = env.VITE_ENABLE_SALES !== 'false';

// Release lockdown (2026-08-30): everyone except HR and CEO sees ONLY the
// self-service surface — the '/' landing, /profile, /attendance, /leave,
// /employee-requests and /tax-allowance. Sales/CRM, the HR admin queues and
// the payroll/finance surfaces are removed from the nav AND refused by
// canAccessPath (permissions.js), which is where the allowlist lives.
//
// Deliberately an OFF-switch (`!== 'false'`), the same direction as
// SALES_ENABLED above and for a stronger version of the same reason. Two
// facts force it:
//
//   1. This var is not set in the production build, so an `=== 'true'` check
//      would ship an UNLOCKED production — the failure that must not happen.
//      (Production's env is NOT empty, despite what the SALES_ENABLED comment
//      above used to claim: read out of the deployed bundle on 2026-08-30 it
//      carries VITE_USE_MOCKS plus ~15 VITE_VERCEL_* system vars, set in the
//      Vercel DASHBOARD — a source neither vercel.json nor .gitignore covers.
//      It simply does not carry THIS var. Setting it to 'false' there is the
//      unlock lever when the release is over.)
//   2. On this Vercel project there is no reliable way to set one. The
//      phase-1 UAT branch tried `vercel.json` build.env (ignored outright —
//      "legacy config, not applied for Git-connected projects") and then
//      inlining the vars into `buildCommand` (also did not reach the bundle,
//      verified by asset-hash comparison). Only a committed
//      frontend/.env.production changed the served build. See commits
//      8c11e7b9 and 6e9dd661 on feat/uat-phase1-self-service.
//
// So the lock cannot depend on a deploy-time variable existing. It is on by
// default and turned OFF for local work by frontend/.env.development (dev
// server) and vitest.config.js's `test.env` (the suite), both of which are
// development-mode-only and can never reach a production build.
export const SELF_SERVICE_ONLY = env.VITE_SELF_SERVICE_ONLY !== 'false';
