import { defineConfig, devices } from '@playwright/test';

// Opt-in visual-regression harness against the MOCK frontend (VITE_USE_MOCKS=true). Dev server ONLY — `vite
// build` / `vite preview` throw with mocks (frontend/src/api/index.js selects the API impl at
// build/module-eval time; the mock impl relies on Vite dev's ESM live-reload semantics).
export default defineConfig({
  testDir: './e2e-visual',
  // Playwright's DEFAULT testMatch is `**/*.@(spec|test).?(c|m)[jt]s?(x)` — it claims `*.test.js` as well as
  // `*.spec.js`. Splitting the extensions gives each runner an unambiguous half: .spec.js is Playwright's,
  // *.test.js is vitest's (see vitest.config.js `include`).
  testMatch: '**/*.spec.js',
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
