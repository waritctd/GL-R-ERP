import { defineConfig, devices } from '@playwright/test';

// Opt-in visual-regression harness against the MOCK frontend
// (VITE_USE_MOCKS=true). Restores the harness PR #592 deleted along with the
// rest of the old mock e2e suite (frontend/e2e/, frontend/playwright.config.js,
// .github/workflows/e2e-ci.yml) — see frontend/e2e-visual/visual-baseline.spec.js
// for the spec itself, its skip guard, and its usage instructions.
//
// Dev server ONLY — `vite build` / `vite preview` throw with mocks
// (frontend/src/api/index.js selects the API impl at build/module-eval
// time; the mock impl relies on Vite dev's ESM live-reload semantics).
// Port 5252: 5174 is the human `npm run dev` port, 5200 is the
// `.claude/launch.json` `frontend-mock` port, and 5251 is `e2e-real`'s
// (playwright.real.config.js, E2E_REAL_FRONTEND_PORT) — this suite always
// spins its own dedicated mock instance on a free port (reuseExistingServer:
// false — never silently reuses a non-mock server on another port).
//
// Deliberately NOT wired into any CI workflow, and it must stay that way.
// PR #592 deleted the old mock e2e suite and its e2e-ci.yml specifically to
// get to "one e2e job per PR instead of two" (owner ruling) — frontend/e2e-real/
// is that one job, via `npm run test:e2e`. This config is for local, opt-in
// use only (VISUAL_BASELINE=1), invoked directly or via `npm run test:visual`;
// do not add a workflow that runs it in CI — that would recreate the exact
// two-job situation #592 removed.
export default defineConfig({
  testDir: './e2e-visual',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: [['html', { open: 'never' }], ['list']],
  use: {
    baseURL: 'http://127.0.0.1:5252',
    trace: 'on-first-retry',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: {
    command: 'npm run dev -- --port 5252 --strictPort',
    env: { ...process.env, VITE_USE_MOCKS: 'true' },
    url: 'http://127.0.0.1:5252',
    reuseExistingServer: false,
    timeout: 120_000,
  },
});
