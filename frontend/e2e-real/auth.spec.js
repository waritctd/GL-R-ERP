import { test, expect } from '@playwright/test';
import { DEMO_PASSWORD, PERSONAS, REAL_ROLES, personaFor } from './helpers/accounts.js';
import {
  expectAuthenticated,
  expectLoggedOut,
  loginAs,
  loginWithCredentials,
  logout,
  userMenu,
} from './helpers/auth.js';
import { captureJson } from './helpers/network.js';

// Real auth lifecycle in a real browser against the real Spring backend and a real Postgres session store.
// Nothing here is stubbed: each login is a BCrypt check in AuthService against a hr.employee row seeded by
// V21__demo_seed_accounts.sql.

test.describe('this suite is actually talking to the backend', () => {
  // This runs first and exists to make the rest of the file trustworthy. If the frontend were accidentally
  // serving mocks (a stray VITE_USE_MOCKS=true — see playwright.real.config.js) every other spec in this
  // directory would still pass while proving nothing about the Java services.

  test('login issues a real network request to POST /api/auth/login', async ({ page }) => {
    await page.goto('/');
    await page.getByTestId('login-email').fill(PERSONAS.hr.email);
    await page.getByTestId('login-password').fill(DEMO_PASSWORD);

    // captureJson rather than waitForResponse + a later .json(). Buffering eagerly costs nothing and removes the
    // dependency on that implementation detail; see helpers/network.js.
    const [{ status, json }] = await Promise.all([
      captureJson(page, (r) => r.url().includes('/api/auth/login') && r.request().method() === 'POST'),
      page.getByTestId('login-submit').click(),
    ]);

    // mockApi.js resolves in-process and never touches the network, so reaching this line at all is the proof. The
    // body check pins it to the real AuthResponse from the Java service.
    expect(status).toBe(200);
    expect(json.user.email).toBe(PERSONAS.hr.email);
    await expectAuthenticated(page);
  });
});

test.describe('credential login', () => {
  for (const role of REAL_ROLES) {
    test(`${role} logs in and the backend derives the expected role`, async ({ page }) => {
      const persona = personaFor(role);
      await loginWithCredentials(page, persona.email, DEMO_PASSWORD);
      await expectAuthenticated(page);

      // page.request shares the browser context's cookie jar, so this is the same authenticated session the UI is
      // using.
      const me = await page.request.get('/api/auth/me');
      expect(me.status()).toBe(200);
      const { user } = await me.json();
      expect(user.role).toBe(role);
      expect(user.email).toBe(persona.email);
      // V21 seeds must_change_password FALSE; if that ever flips, App.jsx swaps the whole shell for
      // ChangePasswordModal and every downstream spec here breaks for a non-obvious reason.
      expect(user.mustChangePassword).toBe(false);
    });
  }

  test('a wrong password is rejected and stays on the login page', async ({ page }) => {
    await page.goto('/');
    await page.getByTestId('login-email').fill(PERSONAS.employee.email);
    await page.getByTestId('login-password').fill('definitely-not-the-password');
    await page.getByTestId('login-submit').click();

    // AuthService's INVALID_CREDENTIALS message, surfaced by App.jsx's handleLogin.
    await expect(page.getByText('อีเมลหรือรหัสผ่านไม่ถูกต้อง')).toBeVisible();
    await expectLoggedOut(page);
    await expect(userMenu(page)).toHaveCount(0);
  });
});

test.describe('session lifecycle', () => {
  test('the session survives a full page reload', async ({ page }) => {
    await loginAs(page, 'hr');

    // The mock suite cannot test this at all: a hard navigation wipes mockApi's in-memory db, so its helper routes
    // every navigation through a history.pushState shim.
    await page.reload();
    await expectAuthenticated(page);

    const me = await page.request.get('/api/auth/me');
    expect(me.status()).toBe(200);
  });

  test('logout invalidates the session server-side, not just in the UI', async ({ page }) => {
    await loginAs(page, 'hr');
    await expect((await page.request.get('/api/auth/me')).status()).toBe(200);

    await logout(page);
    await expectLoggedOut(page);

    // The real assertion. Clearing client state would satisfy the UI check above while leaving a live session
    // behind; AuthService.logout calls session.invalidate(), so the same cookie must now be worthless.
    const me = await page.request.get('/api/auth/me');
    expect(me.status()).toBe(401);
  });
});

test.describe('mock-vs-real divergence', () => {
  test('role login — the mock quick-login payload — is refused by the real service', async ({ page }) => {
    await page.goto('/');

    // mockApi.js's auth.login({role}) resolves straight to the first active user of that role, which is what the
    // mock suite's loginAs() drives. Pinning it here keeps that password-less path from ever quietly working
    // against a real deployment.
    const response = await page.request.post('/api/auth/login', {
      data: { role: 'hr' },
      failOnStatusCode: false,
    });
    expect(response.status()).toBe(403);
    await expectLoggedOut(page);
  });
});
