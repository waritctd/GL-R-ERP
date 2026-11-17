import { defineConfig, devices } from '@playwright/test';

// Browser + API e2e against the REAL stack: Vite dev server → Spring Boot → Postgres. The repository's ONLY
// e2e config since 2026-08-08. A green mock run was evidence about plumbing, never about permissions;
// everything this suite asserts came from the real service.
const FRONTEND_PORT = Number(process.env.E2E_REAL_FRONTEND_PORT || 5251);

// Remote target: run this suite against a DEPLOYED frontend ──────────────────────────────── Set E2E_BASE_URL
// to point the browser at a deployment (UAT) instead of a server this config starts. LOOPBACK IS REFUSED.
const REMOTE_BASE_URL = (process.env.E2E_BASE_URL || '').trim().replace(/\/$/, '');
const IS_REMOTE = REMOTE_BASE_URL.length > 0;

// Release lockdown (SELF_SERVICE_ONLY — frontend/src/app/features.js). This suite has to set the flag in TWO
// places and they must agree, because two different processes read it: 1. THIS process. route-coverage.spec.js
// imports app/permissions.js and uses canAccessPath as its permission oracle.
if (!IS_REMOTE) {
  process.env.VITE_SELF_SERVICE_ONLY = 'false';
}

if (IS_REMOTE) {
  let parsed;
  try {
    parsed = new URL(REMOTE_BASE_URL);
  } catch {
    throw new Error(`E2E_BASE_URL is not a valid URL: '${REMOTE_BASE_URL}'`);
  }
  if (parsed.protocol !== 'https:') {
    throw new Error(
      `E2E_BASE_URL must be https (got '${parsed.protocol}//'). This suite's anti-mock protection ` +
        'assumes a real deployment: a built bundle cannot serve mocks because src/api/index.js ' +
        'throws under PROD + VITE_USE_MOCKS. A plain-http target is almost always a local tunnel ' +
        'or proxy, for which that reasoning does not hold.'
    );
  }
  if (['127.0.0.1', 'localhost', '::1', '0.0.0.0'].includes(parsed.hostname)) {
    throw new Error(
      `E2E_BASE_URL must not be loopback (got '${parsed.hostname}'). reuseExistingServer:false ` +
        'exists so a stray mock-mode dev server can never be adopted as this suite\'s frontend; ' +
        'pointing E2E_BASE_URL at localhost would reintroduce exactly that hole. To run against a ' +
        'local stack, leave E2E_BASE_URL unset — this config starts its own server.'
    );
  }
}

// In remote mode the browser and the direct-to-Spring API contexts must share ONE origin, or they get two
// unrelated GLR_HR_SESSION cookie jars and every authenticated API assertion fails against a session the
// browser never established.
const BACKEND_URL = process.env.E2E_BACKEND_URL || (IS_REMOTE ? REMOTE_BASE_URL : 'http://127.0.0.1:8080');

// Escape hatch for sandboxes and CI images that ship a preinstalled Chromium at a different browser revision
// than this @playwright/test pins. Unset — the normal case, including the CI workflow, which runs `npx
// playwright install chromium` — this is a no-op.
const CHROMIUM_EXECUTABLE = process.env.E2E_CHROMIUM_EXECUTABLE || undefined;

export default defineConfig({
  testDir: './e2e-real',
  // Inert today — e2e-real/ contains no *.test.js — but Playwright's default testMatch claims `*.test.js` as
  // well as `*.spec.js`, so the first vitest-style file added here would be collected by Playwright and fail the
  // suite at load time. That is exactly what happened in e2e-visual/.
  testMatch: '**/*.spec.js',
  // In remote mode three specs are excluded, for two different reasons. A green run would say nothing, which is
  // worse than no run. The npm script narrows again with --grep @uat-sales. Two independent mechanisms, because
  // a single --grep typo would otherwise point the whole suite at a shared deployment.
  testIgnore: IS_REMOTE
    ? ['**/route-coverage.spec.js', '**/api-surface.spec.js', '**/write-authz.spec.js']
    : ['**/uat-*.spec.js'],
  globalSetup: './e2e-real/global-setup.js',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  // Serial. The backend is a single shared, stateful system (one Postgres, one session store, and a per-IP login
  // rate limiter in front of /api/auth/login) — parallel workers would contend on all three, and the rate
  // limiter in particular turns contention into flake.
  workers: 1,
  reporter: [['html', { open: 'never', outputFolder: 'playwright-report-real' }], ['list']],
  outputDir: 'test-results-real',
  use: {
    baseURL: IS_REMOTE ? REMOTE_BASE_URL : `http://127.0.0.1:${FRONTEND_PORT}`,
    trace: 'on-first-retry',
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        launchOptions: { executablePath: CHROMIUM_EXECUTABLE },
      },
    },
  ],
  // Starts only the local frontend; global setup requires a separately running backend/database. Remote runs
  // start no servers.
  webServer: IS_REMOTE ? undefined : {
    command: `npm run dev -- --port ${FRONTEND_PORT} --strictPort`,
    env: {
      ...process.env,
      // Explicitly FALSE, never merely absent. src/api/index.js picks the impl with `VITE_USE_MOCKS === 'true'`, and
      // Vite gives an inline VITE_* env var priority over .env files — so pinning it here beats both a developer's
      // exported shell var and a committed-by-accident .env.local.
      VITE_USE_MOCKS: 'false',
      // vite.config.js proxies /api here, so the browser's fetches reach the real Spring app.
      VITE_API_PROXY_TARGET: BACKEND_URL,
      // Explicitly FALSE for the same reason VITE_USE_MOCKS is: frontend/.env.development already sets it, but an
      // inline var beats every .env file, so pinning it here makes the frontend under test unlocked no matter what
      // env files a checkout happens to carry.
      VITE_SELF_SERVICE_ONLY: 'false',
    },
    url: `http://127.0.0.1:${FRONTEND_PORT}`,
    reuseExistingServer: false,
    timeout: 120_000,
  },
});
