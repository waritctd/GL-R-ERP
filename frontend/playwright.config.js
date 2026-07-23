import { defineConfig, devices } from '@playwright/test';

// Stage K2 Phase 1 — Playwright browser e2e against the MOCK frontend
// (VITE_USE_MOCKS=true). See docs/agent-handoffs/ for the branch handoff.
//
// Dev server ONLY — `vite build` / `vite preview` throw with mocks
// (frontend/src/api/index.js selects the API impl at build/module-eval
// time; the mock impl relies on Vite dev's ESM live-reload semantics).
// Port 5250: 5174 is the human `npm run dev` port and 5200 is the
// `.claude/launch.json` `frontend-mock` port — both may already be
// running locally, so this suite always spins its own dedicated
// mock instance on a free port (reuseExistingServer: false — never
// silently reuses a non-mock server on another port).
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: [['html', { open: 'never' }], ['list']],
  use: {
    baseURL: 'http://127.0.0.1:5250',
    trace: 'on-first-retry',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: {
    command: 'npm run dev -- --port 5250 --strictPort',
    env: { ...process.env, VITE_USE_MOCKS: 'true' },
    url: 'http://127.0.0.1:5250',
    reuseExistingServer: false,
    timeout: 120_000,
  },
});
