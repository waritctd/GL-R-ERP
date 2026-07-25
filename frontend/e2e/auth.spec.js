import { test, expect } from '@playwright/test';
import { SEEDED_ROLES, loginAs, loginWithCredentials, logout } from './helpers/auth.js';

// Stage K2 Phase 1 — foundation coverage: every seeded role can quick-login
// and land on an authenticated view (not the login page), logout returns to
// the login page, and the credential (email/password) login path works too.
// Frontend-only (VITE_USE_MOCKS=true) — see rbac.spec.js for the
// frontend-gating note that applies across this whole suite.

test.describe('auth — quick login', () => {
  for (const role of SEEDED_ROLES) {
    test(`${role} quick-login lands on an authenticated view`, async ({ page }) => {
      await loginAs(page, role);

      // Not on the login screen anymore.
      await expect(page.getByTestId('login-submit')).toHaveCount(0);
      // The role's landing renders inside the authenticated AppShell —
      // its sidebar logout control is present on every role's landing.
      await expect(page.getByRole('button', { name: 'ออกจากระบบ' })).toBeVisible();
    });
  }
});

test('logout returns to the login page', async ({ page }) => {
  await loginAs(page, 'hr');
  await logout(page);

  await expect(page.getByTestId('login-submit')).toBeVisible();
  await expect(page.getByTestId('login-role-hr')).toBeVisible();
});

test('credential login (email + demo1234) works', async ({ page }) => {
  // Mirrors demoData.js's hr@glr.co.th seed user — password demo1234 for
  // every seeded account.
  await loginWithCredentials(page, 'hr@glr.co.th', 'demo1234');

  await expect(page.getByTestId('login-submit')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'ออกจากระบบ' })).toBeVisible();
});

test('credential login rejects a wrong password', async ({ page }) => {
  await page.goto('/');
  await page.getByTestId('login-email').fill('hr@glr.co.th');
  await page.getByTestId('login-password').fill('wrong-password');
  await page.getByTestId('login-submit').click();

  // Stays on the login page with the Thai invalid-credentials message
  // (App.jsx's handleLogin maps 401 -> 'อีเมลหรือรหัสผ่านไม่ถูกต้อง').
  await expect(page.getByText('อีเมลหรือรหัสผ่านไม่ถูกต้อง')).toBeVisible();
  await expect(page.getByTestId('login-submit')).toBeVisible();
});
