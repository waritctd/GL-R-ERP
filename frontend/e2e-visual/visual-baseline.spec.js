// Opt-in full-page visual-regression harness for the styles.css retirement. ── NO LONGER RUNS IN CI (owner
// decision, 2026-08-10) ──────────────────────── `.github/workflows/visual-ci.yml` was deleted and `visual`
// was removed from main's required status checks.
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { test, expect } from '@playwright/test';
import { loginAs, spaGoto } from './helpers/auth.js';
import { loadAcceptedChanges } from './helpers/accepted-changes.js';

test.skip(!process.env.VISUAL_BASELINE, 'set VISUAL_BASELINE=1 to run the visual-regression harness');

if (process.env.PW_CHROMIUM) {
  test.use({ launchOptions: { executablePath: process.env.PW_CHROMIUM } });
}

// WHY THE POINTER HAS TO BE PARKED BEFORE EVERY CAPTURE ──────────────────── Playwright's mouse does not move
// on its own, and `spaGoto` navigates via history.pushState rather than a click -- so the pointer stays
// wherever `loginAs` last clicked, for the whole test, across every route.
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

const snapshotName = (vpName, role, route) =>
  `${vpName}-${role}-${route === '/' ? 'home' : route.replace(/\//g, '-').replace(/^-/, '')}`;

// ACCEPTING AN INTENDED PIXEL CHANGE ─────────────────────────────────────── The gate runs at
// maxDiffPixelRatio: 0 against a baseline taken from the merge base, so a PR that deliberately changes the UI
// fails by construction.
const PR_NUMBER = process.env.VISUAL_PR_NUMBER;
const ALL_SNAPSHOTS = new Set(
  VIEWPORTS.flatMap(({ name }) => SURFACES.flatMap(([role, routes]) => routes.map((r) => snapshotName(name, role, r)))),
);
// Throws on a malformed line, or on an entry for THIS PR naming a snapshot that does not exist -- loud at load
// time beats a typo silently meaning "not accepted".
const ACCEPTED = loadAcceptedChanges(
  path.join(path.dirname(fileURLToPath(import.meta.url)), 'accepted-changes.txt'),
  PR_NUMBER,
  ALL_SNAPSHOTS,
);
if (ACCEPTED.size) {
  console.log(`[visual] ${ACCEPTED.size} accepted change(s) declared for PR #${PR_NUMBER}: ${[...ACCEPTED.keys()].join(', ')}`);
}

for (const { name: vpName, width, height } of VIEWPORTS) {
  for (const [role, routes] of SURFACES) {
    test(`${vpName}-${role}`, async ({ page }) => {
      // One login + N routes per test; comparing N full-page screenshots blows the 30s default well before the last
      // route.
      test.setTimeout(300_000);
      await page.setViewportSize({ width, height });
      await loginAs(page, role);
      // The login toast auto-dismisses on a timer; whether it is still on screen when a screenshot lands is a race,
      // not a style fact. Wait it out once so every capture below is deterministic.
      await page.getByText('เข้าสู่ระบบสำเร็จ').waitFor({ state: 'hidden', timeout: 20_000 }).catch(() => {});
      for (const route of routes) {
        await spaGoto(page, route);
        // let react-query settle + any layout observers run
        await page.waitForTimeout(900);
        await parkPointer(page);
        const name = snapshotName(vpName, role, route);
        const reason = ACCEPTED.get(name);
        try {
          await expect(page).toHaveScreenshot(`${name}.png`, {
            fullPage: true,
            animations: 'disabled',
            maxDiffPixelRatio: 0,
            timeout: 30_000,
          });
          if (reason) {
            // Not a failure: a change can legitimately show at one viewport and not another. But an entry that never fires
            // is usually a typo or a leftover, so say so rather than letting it sit there looking load-bearing.
            console.log(`[visual] NOTE ${name} is listed in accepted-changes.txt for PR #${PR_NUMBER} but did not differ — stale entry?`);
          }
        } catch (err) {
          if (!reason) throw err;
          // Playwright has already written -expected/-actual/-diff into test-results and attached them to the report by
          // the time it throws, so swallowing the error here keeps the evidence and only drops the failure.
          console.log(`[visual] ACCEPTED ${name} (PR #${PR_NUMBER}) — ${reason}`);
          console.log(
            String(err.message)
              .split('\n')
              .filter((l) => /pixels|ratio/.test(l))
              .slice(0, 1)
              .join('') || '  (see the uploaded -diff.png)',
          );
        }
      }
    });
  }
}
