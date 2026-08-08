import { expect } from '@playwright/test';
import { DEMO_PASSWORD, personaFor } from './accounts.js';

// Browser auth helpers for the REAL backend (no VITE_USE_MOCKS).
//
// Two things differ from e2e/helpers/auth.js (the mock suite's equivalent), and both come from
// the same cause — session state lives in Postgres (hr.spring_session, via
// spring.session.store-type=jdbc), not in a JS module:
//
//  1. There is NO quick-login. LoginPage.jsx renders the `login-role-*` buttons only when
//     `import.meta.env.VITE_USE_MOCKS === 'true'`, and the real AuthService rejects a
//     `{role}` payload outright with 403 "Role login is disabled". Every login here is a real
//     email + password round-trip.
//  2. `page.goto()` is safe anywhere. The mock helper had to route every navigation through a
//     history.pushState shim because a hard navigation reset mockApi's in-memory `db`; here a
//     reload just re-reads the session cookie, so direct URL entry — the thing a user actually
//     does with a shared deep link — is testable.

/** Logs in through the real login form and waits for the authenticated AppShell. */
export async function loginAs(page, role) {
  const persona = personaFor(role);
  await loginWithCredentials(page, persona.email, DEMO_PASSWORD);
}

export async function loginWithCredentials(page, email, password) {
  if (page.url() === 'about:blank') {
    await page.goto('/');
  }
  await page.getByTestId('login-email').fill(email);
  await page.getByTestId('login-password').fill(password);
  await page.getByTestId('login-submit').click();
  await expect(userMenu(page)).toBeVisible();
}

export async function logout(page) {
  await userMenu(page).click();
  await page.getByRole('menuitem', { name: 'ออกจากระบบ' }).click();
  await expect(page.getByTestId('login-submit')).toBeVisible();
}

/**
 * The topbar account-menu trigger — the single identity/logout affordance since issue #396
 * removed the sidebar footer's duplicate. Present on every role's landing and at every
 * viewport, which is what makes it a reliable "we are authenticated" signal.
 */
export function userMenu(page) {
  return page.getByRole('button', { name: 'เมนูผู้ใช้' });
}

/**
 * Client-side navigation to `path`, without a hard browser navigation.
 *
 * Unlike the mock suite's identical-looking helper, this is a SPEED choice, not a correctness
 * one: mock state would not survive a real `page.goto` (its `db` is module state), whereas the
 * real session lives in Postgres and survives anything. A full navigation here costs a Vite dev
 * module graph reload — seconds, times every route times every role — so route sweeps push
 * history state instead. BrowserRouter re-derives its location from the popstate event.
 */
export async function spaGoto(page, path) {
  await page.evaluate((target) => {
    window.history.pushState({}, '', target);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, path);
}

/** The `user` object the backend issued for the current session — App.jsx's exact principal. */
export async function sessionUser(page) {
  const response = await page.request.get('/api/auth/me');
  if (!response.ok()) {
    throw new Error(`GET /api/auth/me returned ${response.status()} — no live session`);
  }
  const { user } = await response.json();
  return user;
}

/** True once the app has rendered an authenticated shell rather than the login screen. */
export async function expectAuthenticated(page) {
  await expect(userMenu(page)).toBeVisible();
  await expect(page.getByTestId('login-submit')).toHaveCount(0);
}

export async function expectLoggedOut(page) {
  await expect(page.getByTestId('login-submit')).toBeVisible();
}
