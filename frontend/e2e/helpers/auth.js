import { expect } from '@playwright/test';

// Stage K2 Phase 1 auth helper — quick-login, role-switch, and SPA-only
// navigation for the mock frontend (VITE_USE_MOCKS=true).
//
// Hard constraint: mockApi.js's `db` and `sessionUser` are plain JS module
// state — they live for as long as the page's JS runtime does and reset on
// ANY full browser navigation (a real `page.goto(url)`, a hard reload).
// Every helper below is written around that: log in ONCE with a real
// `page.goto('/')`, then only ever move around with SPA-style navigation
// (client clicks, or `spaGoto` for direct URL entry) so seeded/mutated mock
// state survives for the rest of the test.

export const SEEDED_ROLES = ['hr', 'employee', 'sales', 'sales_manager', 'import', 'account', 'ceo'];

// Mirrors frontend/src/data/demoData.js's seeded quick-login users. `POST
// /auth/login {role}` (mockApi.js `auth.login`) always resolves to the
// FIRST active user of that role, so each role has exactly one reachable
// quick-login persona. Only the PRESENCE of `employeeId` (not its numeric
// value) affects any guard in app/permissions.js's PATH_GUARDS — sales,
// import, and account are seeded with `employeeId: null` (no linked payroll
// employee row); hr, employee, sales_manager, and ceo all have one. This is
// a data fact about the seed (read directly from demoData.js), not
// permission logic — it exists so rbac.spec's canAccessPath(...) oracle
// call is built from the *exact* `user` shape App.jsx holds for that
// persona at runtime, not a guessed stand-in.
const SEEDED_ROLE_EMPLOYEE_ID = {
  hr: 21, // employees[20]
  employee: 9, // employees[8] — the "employee@glr.co.th" quick-login persona (a 2nd
  // role:'employee' user, warehouse.manager@glr.co.th/employees[5], is seeded too, but
  // POST /auth/login {role:'employee'} always returns the first active match).
  sales: null,
  sales_manager: 2, // employees[1]
  import: null,
  account: null,
  ceo: 1, // employees[0]
};

// The exact `user` shape App.jsx holds after a given role's quick-login —
// i.e. what RequireAccess (app/permissions.js's canAccessPath) actually
// guards against at runtime for that persona. Pass straight into
// `canAccessPath(path, seededUser(role))`.
export function seededUser(role) {
  return { role, employeeId: SEEDED_ROLE_EMPLOYEE_ID[role] ?? null, manager: false };
}

// goto('/') only if the tab hasn't loaded the app yet — repeat calls inside
// a test must never re-navigate (see module doc above).
async function ensureAppLoaded(page) {
  if (page.url() === 'about:blank') {
    await page.goto('/');
  }
}

// Password-less quick-login (LoginPage's DEMO buttons, VITE_USE_MOCKS=true
// only). Waits for the authenticated AppShell to replace LoginPage.
export async function loginAs(page, role) {
  await ensureAppLoaded(page);
  const quickButton = page.getByTestId(`login-role-${role}`);
  await expect(quickButton).toBeVisible();
  await quickButton.click();
  await expect(page.getByRole('button', { name: 'ออกจากระบบ' })).toBeVisible();
}

// Credential login path (password `demo1234` for every seeded user —
// demoData.js).
export async function loginWithCredentials(page, email, password) {
  await ensureAppLoaded(page);
  await page.getByTestId('login-email').fill(email);
  await page.getByTestId('login-password').fill(password);
  await page.getByTestId('login-submit').click();
  await expect(page.getByRole('button', { name: 'ออกจากระบบ' })).toBeVisible();
}

export async function logout(page) {
  await page.getByRole('button', { name: 'ออกจากระบบ' }).click();
  await expect(page.getByTestId('login-submit')).toBeVisible();
}

// Switches to a different role in-session — logout, then quick-login again.
// NEVER a hard reload: see module doc above (mock db mutations must
// survive the switch for cross-role journeys).
export async function switchRole(page, role) {
  await logout(page);
  await loginAs(page, role);
}

// Client-side ("SPA") navigation to `path` without a hard browser
// navigation — pushes history state and dispatches `popstate`, which
// react-router-dom's BrowserRouter (createBrowserHistory) listens for and
// re-derives its location from `window.location`. A real `page.goto(path)`
// after the initial load would wipe the mock's in-memory session/db (see
// module doc above), so any direct "type a URL and go" style navigation in
// a logged-in test must go through this helper instead of `page.goto`.
export async function spaGoto(page, path) {
  await page.evaluate((p) => {
    window.history.pushState({}, '', p);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, path);
}
