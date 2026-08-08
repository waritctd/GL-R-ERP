import { expect } from '@playwright/test';

// Visual-regression harness auth helper — quick-login and SPA-only navigation
// for the mock frontend (VITE_USE_MOCKS=true). Ported from the Stage K2
// Phase 1 e2e suite's helpers/auth.js (deleted by PR #592 along with the
// rest of frontend/e2e/); trimmed to only what visual-baseline.spec.js
// actually uses — the rbac-oracle exports (`seededUser`,
// `SEEDED_ROLE_EMPLOYEE_ID`, `SEEDED_ROLES`) and the credential-login /
// logout / role-switch helpers are dropped, not ported, since nothing here
// imports them.
//
// Hard constraint (still true, still load-bearing): mockApi.js's `db` and
// `sessionUser` are plain JS module state — they live for as long as the
// page's JS runtime does and reset on ANY full browser navigation (a real
// `page.goto(url)`, a hard reload). Every helper below is written around
// that: log in ONCE with a real `page.goto('/')`, then only ever move
// around with SPA-style navigation (`spaGoto`, for direct URL entry) so
// seeded/mutated mock state survives for the rest of the test.

// goto('/') only if the tab hasn't loaded the app yet — repeat calls inside
// a test must never re-navigate (see module doc above).
async function ensureAppLoaded(page) {
  if (page.url() === 'about:blank') {
    await page.goto('/');
  }
}

// Password-less quick-login (LoginPage's DEMO buttons, VITE_USE_MOCKS=true
// only). Waits for the authenticated AppShell to replace LoginPage.
//
// Issue #396 fix: the sidebar footer's own name/avatar/logout block was
// removed (it duplicated the topbar UserMenu identity, and doubled the
// logout control on screen at once) -- the topbar account-menu trigger
// (avatar button, "เมนูผู้ใช้") is now the ONE login-success/logout
// affordance, and unlike the old sidebar control it is never hidden behind
// the off-canvas nav drawer at narrow widths (see AppShell's topbar, always
// rendered regardless of viewport).
export async function loginAs(page, role) {
  await ensureAppLoaded(page);
  const quickButton = page.getByTestId(`login-role-${role}`);
  await expect(quickButton).toBeVisible();
  await quickButton.click();
  await expect(page.getByRole('button', { name: 'เมนูผู้ใช้' })).toBeVisible();
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
