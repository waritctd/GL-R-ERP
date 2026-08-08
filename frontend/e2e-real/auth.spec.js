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

// Real auth lifecycle in a real browser against the real Spring backend and a real Postgres
// session store. Nothing here is stubbed: each login is a BCrypt check in AuthService against
// a hr.employee row seeded by V21__demo_seed_accounts.sql.

test.describe('this suite is actually talking to the backend', () => {
  // These two run first and exist to make the rest of the file trustworthy. If the frontend
  // were accidentally serving mocks (a stray VITE_USE_MOCKS=true — see playwright.real.config.js)
  // every other spec in this directory would still pass while proving nothing about the Java
  // services. Both assertions below are false under mocks, so that misconfiguration fails loudly
  // here rather than silently downgrading the whole suite into a second mock run.

  test('login issues a real network request to POST /api/auth/login', async ({ page }) => {
    await page.goto('/');
    await page.getByTestId('login-email').fill(PERSONAS.hr.email);
    await page.getByTestId('login-password').fill(DEMO_PASSWORD);

    const [response] = await Promise.all([
      page.waitForResponse((r) => r.url().includes('/api/auth/login') && r.request().method() === 'POST'),
      page.getByTestId('login-submit').click(),
    ]);

    // mockApi.js resolves in-process and never touches the network, so reaching this line at
    // all is the proof. The body check pins it to the real AuthResponse from the Java service.
    expect(response.status()).toBe(200);
    const { user } = await response.json();
    expect(user.email).toBe(PERSONAS.hr.email);
    await expectAuthenticated(page);
  });

  test('the mock-only quick-login buttons are not rendered', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('login-submit')).toBeVisible();

    // LoginPage.jsx renders these only under VITE_USE_MOCKS === 'true'. Their absence is the
    // second, independent signal that this is the real build.
    for (const role of ['hr', 'employee', 'sales', 'ceo']) {
      await expect(page.getByTestId(`login-role-${role}`)).toHaveCount(0);
    }
  });
});

test.describe('credential login', () => {
  for (const role of REAL_ROLES) {
    test(`${role} logs in and the backend derives the expected role`, async ({ page }) => {
      const persona = personaFor(role);
      await loginWithCredentials(page, persona.email, DEMO_PASSWORD);
      await expectAuthenticated(page);

      // page.request shares the browser context's cookie jar, so this is the same authenticated
      // session the UI is using. The role is not stored anywhere — DivisionAccessPolicy.roleFor
      // derives it from the employee's division on every login — so asserting it here exercises
      // that policy against real seeded rows.
      const me = await page.request.get('/api/auth/me');
      expect(me.status()).toBe(200);
      const { user } = await me.json();
      expect(user.role).toBe(role);
      expect(user.email).toBe(persona.email);
      // V21 seeds must_change_password FALSE; if that ever flips, App.jsx swaps the whole shell
      // for ChangePasswordModal and every downstream spec here breaks for a non-obvious reason.
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

  test('an unknown email is rejected with the same message as a wrong password', async ({ page }) => {
    await page.goto('/');
    await page.getByTestId('login-email').fill('nobody.here@demo.invalid');
    await page.getByTestId('login-password').fill(DEMO_PASSWORD);
    await page.getByTestId('login-submit').click();

    // Same 401 + same string for "no such user" and "bad password" — AuthService throws
    // INVALID_CREDENTIALS for both on purpose, so the response cannot be used to enumerate
    // which addresses are real accounts.
    await expect(page.getByText('อีเมลหรือรหัสผ่านไม่ถูกต้อง')).toBeVisible();
    await expectLoggedOut(page);
  });
});

test.describe('session lifecycle', () => {
  test('the session survives a full page reload', async ({ page }) => {
    await loginAs(page, 'hr');

    // The mock suite cannot test this at all: a hard navigation wipes mockApi's in-memory db,
    // so its helper routes every navigation through a history.pushState shim. Here the session
    // lives in Postgres (spring.session.store-type=jdbc, hr.spring_session) behind the
    // GLR_HR_SESSION cookie, so a reload — and by extension a shared deep link — must survive.
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

    // The real assertion. Clearing client state would satisfy the UI check above while leaving
    // a live session behind; AuthService.logout calls session.invalidate(), so the same cookie
    // must now be worthless.
    const me = await page.request.get('/api/auth/me');
    expect(me.status()).toBe(401);
  });

  test('an unauthenticated browser cannot reach a guarded API route', async ({ page }) => {
    await page.goto('/');

    // SecurityConfig allowlists only login, the device punch endpoint and /actuator/health;
    // everything else is `.anyRequest().authenticated()` → 401 with no session.
    for (const path of ['/api/auth/me', '/api/employees', '/api/tickets']) {
      expect((await page.request.get(path)).status(), `${path} without a session`).toBe(401);
    }
  });
});

test.describe('mock-vs-real divergence', () => {
  test('role login — the mock quick-login payload — is refused by the real service', async ({ page }) => {
    await page.goto('/');

    // mockApi.js's auth.login({role}) resolves straight to the first active user of that role,
    // which is what the mock suite's loginAs() drives. The real AuthService rejects the same
    // payload with 403 "Role login is disabled" before any lookup happens. Pinning it here
    // keeps that password-less path from ever quietly working against a real deployment.
    const response = await page.request.post('/api/auth/login', {
      data: { role: 'hr' },
      failOnStatusCode: false,
    });
    expect(response.status()).toBe(403);
    await expectLoggedOut(page);
  });
});
