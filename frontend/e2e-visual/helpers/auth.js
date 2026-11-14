import { expect } from '@playwright/test';

// Visual-regression harness auth helper — quick-login and SPA-only navigation for the mock frontend
// (VITE_USE_MOCKS=true).

// goto('/') only if the tab hasn't loaded the app yet — repeat calls inside a test must never re-navigate (see
// module doc above).
async function ensureAppLoaded(page) {
  if (page.url() === 'about:blank') {
    await page.goto('/');
  }
}

// Password-less quick-login (LoginPage's DEMO buttons, VITE_USE_MOCKS=true only). Waits for the authenticated
// AppShell to replace LoginPage.
export async function loginAs(page, role) {
  await ensureAppLoaded(page);
  const quickButton = page.getByTestId(`login-role-${role}`);
  await expect(quickButton).toBeVisible();
  await quickButton.click();
  await expect(page.getByRole('button', { name: 'เมนูผู้ใช้' })).toBeVisible();
}

// Client-side ("SPA") navigation to `path` without a hard browser navigation — pushes history state and
// dispatches `popstate`, which react-router-dom's BrowserRouter (createBrowserHistory) listens for and
// re-derives its location from `window.location`.
export async function spaGoto(page, path) {
  await page.evaluate((p) => {
    window.history.pushState({}, '', p);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, path);
}
