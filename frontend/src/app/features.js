// Sales/CRM ships as part of the product. It was hidden for the HR-core-only
// v0.1.0 release; that release is historical and the freeze is lifted.
//
// The flag is an off-switch, not an on-switch: sales is enabled unless
// VITE_ENABLE_SALES is explicitly "false". That direction matters — the
// production build sets no VITE_ vars at all (vercel.json has no env block and
// .env* is gitignored), so an `=== 'true'` check left sales disabled in
// production regardless of what the deploy intended.
export const SALES_ENABLED = import.meta.env.VITE_ENABLE_SALES !== 'false';

// One-click sign-in as a seeded UAT persona on the login screen. Real credentials against the real
// backend, but it ships a known shared password in the bundle, so it stays off everywhere except
// the uat branch's .env.production. See features/auth/uatQuickLogin.js.
export const UAT_QUICK_LOGIN_ENABLED = import.meta.env.VITE_UAT_QUICK_LOGIN === 'true';
