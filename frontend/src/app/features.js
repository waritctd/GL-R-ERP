// Frozen sales/CRM stack is hidden for the v0.1.0 HR-core release. The code stays in the
// repo (frozen, not deleted); flip VITE_ENABLE_SALES=true to re-enable nav + routes.
export const SALES_ENABLED = import.meta.env.VITE_ENABLE_SALES === 'true';

// One-click sign-in as a seeded UAT persona on the login screen. Real credentials against the real
// backend, but it ships a known shared password in the bundle, so it stays off everywhere except
// the uat branch's .env.production. See features/auth/uatQuickLogin.js.
export const UAT_QUICK_LOGIN_ENABLED = import.meta.env.VITE_UAT_QUICK_LOGIN === 'true';
