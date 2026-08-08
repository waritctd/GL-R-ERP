import { defineConfig, devices } from '@playwright/test';

// Browser + API e2e against the REAL stack: Vite dev server → Spring Boot → Postgres.
// The sibling `playwright.config.js` drives the MOCK frontend (VITE_USE_MOCKS=true); this
// config is its counterpart and the two never share a test directory, a port, or a report dir.
//
// Why both exist: CLAUDE.md is explicit that mockApi.js's authorization is not authoritative
// and diverges from the Java services (issue #199 — the mock let HR approve OT that the real
// OvertimeService 403s). A green mock run is evidence about plumbing, never about permissions.
// This suite is the other half: everything it asserts came from the real service.
//
// The port is deliberately distinct from every other frontend in the repo — 5174 is `npm run
// dev`, 5200 is the `frontend-mock` launch config, 5250 is the mock e2e suite. 5251 is ours.
// Combined with reuseExistingServer:false and --strictPort below, this suite always starts its
// own server and never adopts one that happens to be listening: adopting a mock-mode server
// would leave every assertion in this directory green and meaningless.
const FRONTEND_PORT = Number(process.env.E2E_REAL_FRONTEND_PORT || 5251);
const BACKEND_URL = process.env.E2E_BACKEND_URL || 'http://127.0.0.1:8080';

// Escape hatch for sandboxes and CI images that ship a preinstalled Chromium at a different
// browser revision than this @playwright/test pins. Playwright resolves browsers by revision,
// not by "is there a chromium here", so a preinstalled one at the wrong revision is invisible
// to it and the run dies on "Executable doesn't exist". Unset — the normal case, including the
// CI workflow, which runs `npx playwright install chromium` — this is a no-op.
const CHROMIUM_EXECUTABLE = process.env.E2E_CHROMIUM_EXECUTABLE || undefined;

export default defineConfig({
  testDir: './e2e-real',
  globalSetup: './e2e-real/global-setup.js',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  // Serial. The backend is a single shared, stateful system (one Postgres, one session store,
  // and a per-IP login rate limiter in front of /api/auth/login) — parallel workers would
  // contend on all three, and the rate limiter in particular turns contention into flake.
  workers: 1,
  reporter: [['html', { open: 'never', outputFolder: 'playwright-report-real' }], ['list']],
  outputDir: 'test-results-real',
  use: {
    baseURL: `http://127.0.0.1:${FRONTEND_PORT}`,
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
  // Frontend only. The backend and its database are NOT started here on purpose: booting a
  // Spring Boot app plus a Postgres is a stack-lifecycle job with its own failure modes, and
  // burying it in a webServer entry turns "your DB is down" into an opaque Playwright timeout.
  // global-setup.js checks for a reachable, demo-seeded backend and says exactly what to run.
  webServer: {
    command: `npm run dev -- --port ${FRONTEND_PORT} --strictPort`,
    env: {
      ...process.env,
      // Explicitly FALSE, never merely absent. src/api/index.js picks the impl with
      // `VITE_USE_MOCKS === 'true'`, and Vite gives an inline VITE_* env var priority over
      // .env files — so pinning it here beats both a developer's exported shell var and a
      // committed-by-accident .env.local. Without this line the whole suite would keep
      // passing while silently asserting the mock's behaviour under a "real backend" name,
      // which is the exact failure CLAUDE.md's mock-contract section warns about.
      VITE_USE_MOCKS: 'false',
      // vite.config.js proxies /api here, so the browser's fetches reach the real Spring app.
      VITE_API_PROXY_TARGET: BACKEND_URL,
    },
    url: `http://127.0.0.1:${FRONTEND_PORT}`,
    reuseExistingServer: false,
    timeout: 120_000,
  },
});
