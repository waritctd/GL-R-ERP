// Build-time feature flags. That is not hypothetical: it took the whole e2e-real job down on the first CI run
// of this branch, at the pre-existing SALES_ENABLED line. An oracle that disagrees with the app under test
// fails every route it disagrees about.
const env = import.meta.env ?? (typeof process !== 'undefined' ? process.env : {});

// Sales/CRM ships as part of the product. It was hidden for the HR-core-only v0.1.0 release; that release is
// historical and the freeze is lifted. The flag is an off-switch, not an on-switch: sales is enabled unless
// VITE_ENABLE_SALES is explicitly "false".
export const SALES_ENABLED = env.VITE_ENABLE_SALES !== 'false';

// Release lockdown (2026-08-30): everyone except HR and CEO sees ONLY the self-service surface — the '/'
// landing, /profile, /attendance, /leave, /employee-requests and /tax-allowance. Only a committed
// frontend/.env.production changed the served build.
export const SELF_SERVICE_ONLY = env.VITE_SELF_SERVICE_ONLY !== 'false';

// Maintenance mode (on-prem cutover): when "true", main.jsx renders only the
// static maintenance page — no router, no API calls — so a suspended backend
// shows a message instead of 502s. Unlike the flags above this is an ON-switch
// (`=== 'true'`): unset must mean "app runs normally". Production gets it from a
// committed frontend/.env.production (Vercel dashboard vars are unreliable here,
// see the SELF_SERVICE_ONLY note); delete that file to end maintenance.
export const MAINTENANCE = env.VITE_MAINTENANCE === 'true';
