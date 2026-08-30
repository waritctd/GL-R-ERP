// Sales/CRM ships as part of the product. It was hidden for the HR-core-only
// v0.1.0 release; that release is historical and the freeze is lifted.
//
// The flag is an off-switch, not an on-switch: sales is enabled unless
// VITE_ENABLE_SALES is explicitly "false". That direction matters — the
// production build sets no VITE_ vars at all (vercel.json has no env block and
// .env* is gitignored), so an `=== 'true'` check left sales disabled in
// production regardless of what the deploy intended.
export const SALES_ENABLED = import.meta.env.VITE_ENABLE_SALES !== 'false';

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
//   1. The production build sets no VITE_ vars at all, so an `=== 'true'`
//      check would ship an UNLOCKED production — the failure that must not
//      happen.
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
export const SELF_SERVICE_ONLY = import.meta.env.VITE_SELF_SERVICE_ONLY !== 'false';
