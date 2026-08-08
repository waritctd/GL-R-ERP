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
// PW_CHROMIUM overrides the browser binary for sandboxes whose preinstalled
// Chromium does not match the pinned @playwright/test revision.
import { test, expect } from '@playwright/test';
import { loginAs, spaGoto } from './helpers/auth.js';

test.skip(!process.env.VISUAL_BASELINE, 'set VISUAL_BASELINE=1 to run the visual-regression harness');

if (process.env.PW_CHROMIUM) {
  test.use({ launchOptions: { executablePath: process.env.PW_CHROMIUM } });
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
