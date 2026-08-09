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
// ── THIS CONFIG RUNS IN CI, AS A REQUIRED CHECK ──────────────────────────────
// #616 wired it up as `.github/workflows/visual-ci.yml` and #617 made that job
// required, with no `paths:` filter. Read that workflow's header before changing
// anything here — especially before concluding the job should not exist.
//
// It used to say the opposite, and the reasoning is worth keeping straight: PR
// #592 did delete the old mock e2e suite and its e2e-ci.yml to get to "one e2e
// job per PR instead of two" (owner ruling). Reinstating a second e2e-shaped job
// was then a later, explicit owner decision, taken on the evidence of four
// CSS-port regressions that `npm run lint`, `npm test` and `npm run build` all
// passed — jsdom has no layout engine, so nothing else could catch them. The two
// jobs answer different questions: e2e-real asks "does the real backend still
// honour this?", this one asks "did the pixels move?".
//
// Still opt-in LOCALLY: the spec is skipped unless VISUAL_BASELINE=1, so a bare
// `npm test` / `npx playwright test` never picks it up. Run it by hand with
// `npm run test:visual`, or directly — see the spec header for the
// capture-baseline-then-diff sequence and its same-day rule.
export default defineConfig({
  testDir: './e2e-visual',
  // Playwright's DEFAULT testMatch is `**/*.@(spec|test).?(c|m)[jt]s?(x)` — it claims
  // `*.test.js` as well as `*.spec.js`. e2e-visual/helpers/accepted-changes.test.js is a
  // VITEST file, and without this line Playwright collects it, chokes on the vitest
  // imports, and fails the whole run before a single screenshot is taken (hit for real
  // while building it). Splitting the extensions gives each runner an unambiguous half:
  // *.spec.js is Playwright's, *.test.js is vitest's (see vitest.config.js `include`).
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
