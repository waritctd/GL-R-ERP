import { expect } from '@playwright/test';
import { DEMO_PASSWORD, personaFor } from './accounts.js';

// Browser auth helpers for the REAL backend (no VITE_USE_MOCKS). LoginPage.jsx renders the `login-role-*`
// buttons only when `import.meta.env.VITE_USE_MOCKS === 'true'`, and the real AuthService rejects a `{role}`
// payload outright with 403 "Role login is disabled".

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

/** The topbar account-menu trigger — the single identity/logout affordance since issue #396 removed the sidebar
 * footer's duplicate. Present on every role's landing and at every viewport, which is what makes it a reliable
 * "we are authenticated" signal. */
export function userMenu(page) {
  return page.getByRole('button', { name: 'เมนูผู้ใช้' });
}

/** Client-side navigation to `path`, without a hard browser navigation. A full navigation here costs a Vite dev
 * module graph reload — seconds, times every route times every role — so route sweeps push history state
 * instead. */
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
