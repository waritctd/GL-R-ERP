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
// ── THIS CONFIG NO LONGER RUNS IN CI ─────────────────────────────────────────
// #616 wired it up as `.github/workflows/visual-ci.yml` and #617 made that job
// required. **#637 (2026-08-10) retired it** — owner decision — and that
// workflow file is deleted. The `visual` context was removed from main's
// required checks BEFORE the workflow was deleted, deliberately: dropping the
// workflow while the context stayed required would leave every future PR
// waiting forever on a check that never reports.
//
// Why it went: the gate compares at maxDiffPixelRatio 0 against the merge base,
// which is right for a CSS port (which must change nothing) and wrong for a
// deliberate redesign. The self-service responsive overhaul changes pixels on
// every surface at every band on purpose, so the gate would need an
// accepted-changes entry per surface per viewport — at which point the accept
// mechanism is a rubber stamp, which trains reviewers worse than no gate.
//
// The harness is deliberately NOT deleted, and still works. Run it by hand:
//
//   cd frontend && VISUAL_BASELINE=1 npm run test:visual
//
// What is now unguarded, so nothing catches it automatically: the four CSS-port
// failure modes the deleted workflow documented (a ported rule gaining priority,
// an element losing a rule it still needs, a deleted rule whose replacement is
// never applied, a merge reuniting new markup with a deleted class). `lint`,
// `test` and `build` were green for every one of them — jsdom has no layout
// engine. The workflow is in git history if it should come back once the
// redesign settles.
//
// Still opt-in locally too: the spec is skipped unless VISUAL_BASELINE=1, so a
// bare `npm test` / `npx playwright test` never picks it up. See the spec header
// for the capture-baseline-then-diff sequence and its same-day rule.
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
