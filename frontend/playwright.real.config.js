import { defineConfig, devices } from '@playwright/test';

// Browser + API e2e against the REAL stack: Vite dev server → Spring Boot → Postgres.
// The repository's ONLY e2e config since 2026-08-08. It used to have a sibling,
// `playwright.config.js`, driving the MOCK frontend (VITE_USE_MOCKS=true); that suite and its CI
// job were removed so a PR runs one e2e job rather than two. What that cost is itemised in
// e2e-real/README.md — most of it was visual/layout coverage this suite does not attempt.
//
// Why this one survived: CLAUDE.md is explicit that mockApi.js's authorization is not
// authoritative and diverges from the Java services (issue #199 — the mock let HR approve OT that
// the real OvertimeService 403s). A green mock run was evidence about plumbing, never about
// permissions; everything this suite asserts came from the real service.
//
// The port stays distinct from every other frontend in the repo — 5174 is `npm run dev`, 5200 is
// the `frontend-mock` launch config (still available for manual mock-mode work). Combined with
// reuseExistingServer:false and --strictPort below, this suite always starts its own server and
// never adopts one that happens to be listening: adopting a mock-mode server would leave every
// assertion in this directory green and meaningless.
const FRONTEND_PORT = Number(process.env.E2E_REAL_FRONTEND_PORT || 5251);

// ── Remote target: run this suite against a DEPLOYED frontend ────────────────────────────────
// Set E2E_BASE_URL to point the browser at a deployment (UAT) instead of a server this config
// starts. That must NOT reopen the hole reuseExistingServer:false exists to close — "adopting a
// mock-mode server would leave every assertion in this directory green and meaningless" — so the
// escape hatch is narrowed two ways, and both refusals are deliberate:
//
//   1. LOOPBACK IS REFUSED. The exact failure being guarded against is "a mock dev server is
//      already listening on this machine". Allowing E2E_BASE_URL=http://127.0.0.1:5200 would
//      reintroduce it through the front door, with an env var standing in for reuseExistingServer.
//   2. PLAIN HTTP IS REFUSED. Every real deployment of this app is https; an http remote is
//      almost certainly someone's tunnel or proxy in front of a mock.
//
// What replaces the anti-mock guarantee in remote mode is STRUCTURAL, and stronger than the local
// one. A deployment is a `vite build` output, and src/api/index.js throws at module load under
// `import.meta.env.PROD && VITE_USE_MOCKS === 'true'` — so a production bundle CANNOT be serving
// mocks, because it would not boot at all. global-setup.js asserts the observable half of that:
// the served document loads a hashed /assets/index-*.js, which only `vite build` emits (a dev
// server serves /src/main.jsx and /@vite/client).
const REMOTE_BASE_URL = (process.env.E2E_BASE_URL || '').trim().replace(/\/$/, '');
const IS_REMOTE = REMOTE_BASE_URL.length > 0;

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

// In remote mode the browser and the direct-to-Spring API contexts must share ONE origin, or they
// get two unrelated GLR_HR_SESSION cookie jars and every authenticated API assertion fails against
// a session the browser never established. The deployed frontend rewrites /api/* to the backend
// (vercel.json), so its own origin is the correct value for both.
const BACKEND_URL = process.env.E2E_BACKEND_URL || (IS_REMOTE ? REMOTE_BASE_URL : 'http://127.0.0.1:8080');

// Escape hatch for sandboxes and CI images that ship a preinstalled Chromium at a different
// browser revision than this @playwright/test pins. Playwright resolves browsers by revision,
// not by "is there a chromium here", so a preinstalled one at the wrong revision is invisible
// to it and the run dies on "Executable doesn't exist". Unset — the normal case, including the
// CI workflow, which runs `npx playwright install chromium` — this is a no-op.
const CHROMIUM_EXECUTABLE = process.env.E2E_CHROMIUM_EXECUTABLE || undefined;

export default defineConfig({
  testDir: './e2e-real',
  // Inert today — e2e-real/ contains no *.test.js — but Playwright's default testMatch
  // claims `*.test.js` as well as `*.spec.js`, so the first vitest-style file added here
  // would be collected by Playwright and fail the suite at load time. That is exactly what
  // happened in e2e-visual/. Pinning the extension now costs nothing and disarms it.
  testMatch: '**/*.spec.js',
  // In remote mode three specs are excluded, for two different reasons.
  //
  // MEANINGLESS against a deployment: route-coverage.spec.js reads ../src/App.jsx and
  // ../src/app/permissions.js off disk, and helpers/surface.js reads docs/api/api-surface.json —
  // so against a deployed frontend they assert about THIS WORKING COPY, not about what is
  // actually running. A green run would say nothing, which is worse than no run.
  //
  // ACTIVELY UNSAFE against a shared, long-lived environment: write-authz.spec.js fires ~880
  // write requests, and api-surface.spec.js sweeps every operation × 9 roles — and every
  // anonymous 401/403 it collects feeds LoginRateLimitFilter's per-IP counter (20 per 900s),
  // which on UAT locks out real testers and cannot be cleared by restarting anything we control.
  //
  // The npm script narrows again with --grep @uat-sales. Two independent mechanisms, because a
  // single --grep typo would otherwise point the whole suite at a shared deployment.
  //
  // The exclusion is SYMMETRIC, and that half is not decorative: the uat-* specs read sessions
  // that only the REMOTE global-setup captures, so in a local run they fail on a missing
  // .auth/ file — a confusing red in `npm run test:e2e` and in the e2e-real CI job, neither of
  // which has any business touching UAT. Caught by listing both modes rather than by reasoning:
  // the local run silently grew from 10 files to 11.
  testIgnore: IS_REMOTE
    ? ['**/route-coverage.spec.js', '**/api-surface.spec.js', '**/write-authz.spec.js']
    : ['**/uat-*.spec.js'],
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
  // Frontend only. The backend and its database are NOT started here on purpose: booting a
  // Spring Boot app plus a Postgres is a stack-lifecycle job with its own failure modes, and
  // burying it in a webServer entry turns "your DB is down" into an opaque Playwright timeout.
  // global-setup.js checks for a reachable, demo-seeded backend and says exactly what to run.
  //
  // Remote mode starts nothing — the frontend under test is already deployed. `undefined` rather
  // than a disabled entry: Playwright then skips the whole webServer lifecycle, so there is no
  // port probe, no --strictPort race against whatever is running locally, and no code path at all
  // by which a stray local server could become this suite's frontend.
  webServer: IS_REMOTE ? undefined : {
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
