// Opt-in full-page visual-regression harness for the styles.css retirement.
//
// A CSS→Tailwind port is supposed to change nothing on screen, and neither
// Vitest (jsdom has no layout engine) nor the behavioural e2e specs can prove
// that. This does: it captures every main surface at the app's three responsive
// bands and diffs them pixel-for-pixel against a baseline taken before the
// change. It has already caught two regressions no other check saw — Tailwind's
// `text-*` utilities bundling a line-height the ported rule did not set, and a
// `@layer legacy` override losing to a utility it used to beat on specificity.
//
// SKIPPED by default: the baselines are large PNGs of payroll/employee/
// commission screens, so they are gitignored (see .gitignore) rather than
// committed to this public repo, and a spec with no baselines would fail CI.
//
// Usage, per migration slice (or `npm run test:visual` in place of
// `npx playwright test --config playwright.visual.config.js` below):
//   1. On the unchanged tree:
//        VISUAL_BASELINE=1 npx playwright test --config playwright.visual.config.js visual-baseline --update-snapshots
//   2. Make the change.
//   3. VISUAL_BASELINE=1 npx playwright test --config playwright.visual.config.js visual-baseline
//      Any output is a real difference — read the *-diff.png under
//      test-results/ before assuming it is noise.
//
// ── CAPTURE THE BASELINE AND THE DIFF ON THE SAME DAY ────────────────────────
// These screenshots contain today's date: the dashboard greeting ("8 ส.ค. 2569"),
// request rows, and the attendance grid all render it. A baseline taken before
// midnight and diffed after produces a wall of red — 12 of 15 surfaces on
// 2026-08-08/09 — that is entirely one changed digit per screenshot. It reads
// exactly like a catastrophic regression and is not one.
//
// The tell: `import` is the only role whose dashboard renders no date, so a pure
// rollover shows up as "everything failed except import". If you see that shape,
// re-capture the baseline and re-run before investigating anything else. Always
// open a *-diff.png — the pass/fail count alone cannot distinguish a rollover
// from a real defect, and this harness's whole value is that a human looks.
//
// (Freezing the clock would remove the hazard, but the app reads Bangkok "today"
// in several places — useIsMobile-style hooks, attendance's day grid, leave
// balances — so a frozen date changes which rows render and would need its own
// verification. Same-day capture is the cheaper correct answer.)
//
// PW_CHROMIUM overrides the browser binary for sandboxes whose preinstalled
// Chromium does not match the pinned @playwright/test revision.
import { test, expect } from '@playwright/test';
import { loginAs, spaGoto } from './helpers/auth.js';

test.skip(!process.env.VISUAL_BASELINE, 'set VISUAL_BASELINE=1 to run the visual-regression harness');

if (process.env.PW_CHROMIUM) {
  test.use({ launchOptions: { executablePath: process.env.PW_CHROMIUM } });
}

// ── WHY THE POINTER HAS TO BE PARKED BEFORE EVERY CAPTURE ────────────────────
// Playwright's mouse does not move on its own, and `spaGoto` navigates via
// history.pushState rather than a click -- so the pointer stays wherever
// `loginAs` last clicked, for the whole test, across every route. Whatever
// element happens to land under that stale coordinate on each new page renders
// `:hover`, and the screenshot bakes it in.
//
// That is not stable across runs. StatCard renders a real <button> with a
// hover affordance whenever it has an onClick (see StatCard.jsx), and
// /ticket-overview is a grid of them, so a few pixels of layout or scroll
// difference between two runs moves the highlight to a DIFFERENT tile and the
// diff reports a change nobody made. Observed for real on PR #615 run
// 31270806243: `mobile-ceo-ticket-overview` failed at 797 pixels / ratio 0.01
// on the first attempt and passed on retry, with the expected image showing
// "ยกเลิกเดือนนี้" shaded and the actual showing the tile BELOW it shaded
// instead -- the hover had simply moved down one tile. Reproduced locally at
// 527 pixels / ratio 0.01 by parking the pointer over a tile on purpose.
//
// It is a required check with maxDiffPixelRatio: 0, so an intermittent false
// diff blocks unrelated PRs at random -- worse than no check at all.
//
// (0, 0) does NOT fix it: measured, the topbar still matches `:hover` there.
// A negative coordinate is outside the content area entirely and leaves
// `document.querySelectorAll(':hover')` empty -- also measured. Do not
// "simplify" this to (0, 0).
async function parkPointer(page) {
  await page.mouse.move(-20, -20);
}

const VIEWPORTS = [
  { name: 'desktop', width: 1440, height: 1000 },
  { name: 'tablet', width: 768, height: 1000 },
  { name: 'mobile', width: 375, height: 800 },
];

// role -> routes reachable for that role, chosen to cover every styles.css block.
const SURFACES = [
  ['hr', ['/', '/employees', '/requests', '/profile', '/payroll', '/attendance', '/employee-requests', '/leave', '/tax-allowance']],
  ['ceo', ['/', '/tickets', '/ticket-overview', '/pricing-requests', '/commissions', '/finance', '/ceo-settings', '/catalog', '/price-import']],
  ['employee', ['/', '/profile', '/leave', '/employee-requests']],
  ['sales', ['/', '/tickets']],
  ['import', ['/', '/procurement']],
];

for (const { name: vpName, width, height } of VIEWPORTS) {
  for (const [role, routes] of SURFACES) {
    test(`${vpName}-${role}`, async ({ page }) => {
      // One login + N routes per test; comparing N full-page screenshots blows
      // the 30s default well before the last route.
      test.setTimeout(300_000);
      await page.setViewportSize({ width, height });
      await loginAs(page, role);
      // The login toast auto-dismisses on a timer; whether it is still on
      // screen when a screenshot lands is a race, not a style fact. Wait it
      // out once so every capture below is deterministic.
      await page.getByText('เข้าสู่ระบบสำเร็จ').waitFor({ state: 'hidden', timeout: 20_000 }).catch(() => {});
      for (const route of routes) {
        await spaGoto(page, route);
        // let react-query settle + any layout observers run
        await page.waitForTimeout(900);
        await parkPointer(page);
        const slug = route === '/' ? 'home' : route.replace(/\//g, '-').replace(/^-/, '');
        await expect(page).toHaveScreenshot(`${vpName}-${role}-${slug}.png`, {
          fullPage: true,
          animations: 'disabled',
          maxDiffPixelRatio: 0,
          timeout: 30_000,
        });
      }
    });
  }
}
