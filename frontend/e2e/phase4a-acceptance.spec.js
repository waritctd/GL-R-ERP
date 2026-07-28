import { expect, test } from '@playwright/test';
import { loginAs, spaGoto } from './helpers/auth.js';

// Phase 4A acceptance gate. Two jobs, both demanded by the acceptance review of
// `refactor/ui-phase-4-ticket-worklist`:
//
//  1. Assert the mobile filter sheet's modal contract in a real browser —
//     Escape closes it, focus is trapped inside it, and the page behind is
//     inert. The unit suite covers this in jsdom, which implements neither
//     `inert` nor real tab traversal, so the browser assertions here are the
//     ones that actually prove the behaviour.
//  2. Re-capture the required desktop/mobile browser matrix on the exact branch
//     head and smoke every page that calls the shared DataTable, failing on
//     console errors, page errors, and horizontal overflow.
//
// Evidence is written under docs/ui-repair/evidence/proposed/phase-4a-ticket-worklist/
// only when CAPTURE_EVIDENCE=1, so an ordinary `npm run test:e2e` asserts
// without rewriting tracked PNGs.

const CAPTURE = process.env.CAPTURE_EVIDENCE === '1';
const EVIDENCE_ROOT = '../docs/ui-repair/evidence/proposed/phase-4a-ticket-worklist';

const DESKTOP = { width: 1366, height: 768 };
const MOBILE = { width: 390, height: 844 };

// Known, pre-existing mock-environment noise: the mock Vite app has no backend
// proxy target, so quick-login's `POST /api/auth/login` always fails before the
// in-memory fallback takes over. Documented in PHASE_4A_QA_RESULTS.md. Anything
// NOT matching these is a real finding and fails the test.
const KNOWN_NOISE = [/\/api\/auth\/login/, /ERR_ABORTED/, /502/, /Failed to load resource/];

// `loginAs` waits for the "ออกจากระบบ" control, which lives inside the collapsed
// nav drawer below 1040px (the drawer now covers the whole 721–1040px tablet
// band too, not just ≤720px — see styles.css's `@media (max-width: 1040px)`
// drawer rules) and so is not visible at those widths. Always log in at
// desktop width, then resize — the app is a SPA and the mock session lives in
// module state, so resizing afterwards costs nothing and keeps the helper's
// contract intact.
async function loginAtViewport(page, role, viewport) {
  await page.setViewportSize(DESKTOP);
  await loginAs(page, role);
  if (viewport !== DESKTOP) await page.setViewportSize(viewport);
}

function watchForProblems(page) {
  const problems = [];
  page.on('console', (message) => {
    if (message.type() !== 'error') return;
    const text = message.text();
    if (KNOWN_NOISE.some((pattern) => pattern.test(text))) return;
    problems.push(`console: ${text}`);
  });
  page.on('pageerror', (error) => problems.push(`pageerror: ${error.message}`));
  return problems;
}

async function assertNoHorizontalOverflow(page, label) {
  const overflow = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
  }));
  // 1px of slack for sub-pixel rounding at fractional device ratios.
  expect(
    overflow.scrollWidth - overflow.clientWidth,
    `${label} overflows horizontally (${overflow.scrollWidth} > ${overflow.clientWidth})`,
  ).toBeLessThanOrEqual(1);
}

async function openFilterSheet(page) {
  const toggle = page.getByRole('button', { name: /^ตัวกรอง/ });
  await expect(toggle).toBeVisible();
  await toggle.focus();
  await toggle.click();
  return toggle;
}

test.describe('Phase 4A — mobile filter sheet modal contract', () => {
  for (const role of ['sales', 'sales_manager', 'ceo']) {
    test(`${role}: Escape closes the sheet and restores focus to the toggle`, async ({ page }) => {
      const problems = watchForProblems(page);
      await loginAtViewport(page, role, MOBILE);
      await spaGoto(page, '/tickets');

      const toggle = await openFilterSheet(page);
      const sheet = page.getByRole('dialog', { name: 'ตัวกรองเพิ่มเติม' });
      await expect(sheet).toBeVisible();
      await expect(sheet).toHaveAttribute('aria-modal', 'true');

      await page.keyboard.press('Escape');

      await expect(sheet).toHaveCount(0);
      await expect(toggle).toBeFocused();
      expect(problems, `unexpected console/page errors: ${problems.join(' | ')}`).toEqual([]);
    });
  }

  test('Escape still closes the sheet while a lifecycle filter is applied', async ({ page }) => {
    await loginAtViewport(page, 'sales', MOBILE);
    // The regression this guards: openness used to be
    // `open || hasActiveFilter`, which made the sheet undismissable by ANY
    // means once a lifecycle/flag filter was applied.
    await spaGoto(page, '/tickets?life=ON_HOLD');

    const sheet = page.getByRole('dialog', { name: 'ตัวกรองเพิ่มเติม' });
    await expect(sheet).toBeVisible();

    await page.keyboard.press('Escape');
    await expect(sheet).toHaveCount(0);

    // Closing must not hide the fact that a filter is applied, nor stop it
    // filtering.
    await expect(page.getByLabel('ตัวกรองที่ใช้')).toBeVisible();
    await expect(page.getByText('สถานะงาน: พักไว้ชั่วคราว')).toBeVisible();
    await expect(page.getByRole('button', { name: /^ตัวกรอง/ })).toContainText('1');
  });

  test('marks the page behind the sheet inert and clears it on close', async ({ page }) => {
    await loginAtViewport(page, 'sales', MOBILE);
    await spaGoto(page, '/tickets');
    await openFilterSheet(page);

    const pageRoot = page.locator('.ticket-list-page');
    await expect(pageRoot).toHaveAttribute('inert', '');
    await expect(pageRoot).toHaveAttribute('aria-hidden', 'true');
    // The sheet is portalled to <body>, so inerting the page root cannot
    // swallow the sheet itself.
    await expect(page.locator('.ticket-list-page .ticket-filter-sheet')).toHaveCount(0);
    await expect(page.locator('body > .ticket-filter-sheet')).toHaveCount(1);

    await page.keyboard.press('Escape');
    await expect(pageRoot).not.toHaveAttribute('inert', /.*/);
    await expect(pageRoot).not.toHaveAttribute('aria-hidden', /.*/);
  });

  test('inert actually blocks pointer and keyboard access to the page behind', async ({ page }) => {
    await loginAtViewport(page, 'sales', MOBILE);
    await spaGoto(page, '/tickets');
    await openFilterSheet(page);

    // Real `inert` semantics, which jsdom cannot model. Located by CSS, not by
    // role: `inert` removes the subtree from the accessibility tree, so a
    // role-based locator cannot see it at all — which is itself part of the
    // contract, asserted separately below.
    const behind = page.locator('.ticket-list-page input[type="search"]');
    await expect(behind).toHaveCount(1);

    const outcome = await behind.evaluate((el) => {
      el.focus();
      return {
        focused: document.activeElement === el,
        // An inert element reports no pointer target at its own centre.
        hitTestReachesIt: (() => {
          const box = el.getBoundingClientRect();
          const hit = document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2);
          return el === hit || el.contains(hit);
        })(),
      };
    });

    expect(outcome.focused, 'a control behind an inert page root must not take focus').toBe(false);
    expect(outcome.hitTestReachesIt, 'a control behind an inert page root must not be hit-testable').toBe(false);

    // And it is gone from the accessibility tree while the sheet is open.
    await expect(page.locator('.ticket-list-page').getByRole('searchbox', { name: 'ค้นหาดีล' })).toHaveCount(0);
  });

  test('traps Tab inside the sheet across a full cycle', async ({ page }) => {
    await loginAtViewport(page, 'sales', MOBILE);
    await spaGoto(page, '/tickets');
    await openFilterSheet(page);

    const sheet = page.getByRole('dialog', { name: 'ตัวกรองเพิ่มเติม' });
    await expect(sheet).toBeVisible();

    // Opening hands focus to the first control in the sheet.
    expect(await sheet.evaluate((el) => el.contains(document.activeElement))).toBe(true);

    const focusableCount = await sheet.evaluate((el) => el.querySelectorAll(
      'a[href], button:not([disabled]), textarea, input, select, [tabindex]:not([tabindex="-1"])',
    ).length);
    expect(focusableCount).toBeGreaterThan(1);

    // Tab all the way round and one past — focus must never leave the sheet.
    for (let i = 0; i <= focusableCount; i += 1) {
      await page.keyboard.press('Tab');
      expect(
        await sheet.evaluate((el) => el.contains(document.activeElement)),
        `focus escaped the sheet after ${i + 1} Tab press(es)`,
      ).toBe(true);
    }

    // And backwards, past the first control.
    for (let i = 0; i <= focusableCount; i += 1) {
      await page.keyboard.press('Shift+Tab');
      expect(
        await sheet.evaluate((el) => el.contains(document.activeElement)),
        `focus escaped the sheet after ${i + 1} Shift+Tab press(es)`,
      ).toBe(true);
    }
  });

  test('stays an inline region (not a modal) on desktop', async ({ page }) => {
    await loginAtViewport(page, 'sales', DESKTOP);
    await spaGoto(page, '/tickets');
    await openFilterSheet(page);

    await expect(page.getByRole('dialog', { name: 'ตัวกรองเพิ่มเติม' })).toHaveCount(0);
    await expect(page.getByRole('region', { name: 'ตัวกรองเพิ่มเติม' })).toBeVisible();
    // Desktop keeps the sheet in the page's normal flow and never inerts.
    await expect(page.locator('.ticket-list-page .ticket-filter-sheet')).toHaveCount(1);
    await expect(page.locator('.ticket-list-page')).not.toHaveAttribute('inert', /.*/);

    // Escape is still honoured at desktop width.
    await page.keyboard.press('Escape');
    await expect(page.getByRole('region', { name: 'ตัวกรองเพิ่มเติม' })).toHaveCount(0);
  });
});

// Regressions found by the independent review of this branch. Each one is a
// layout/visibility defect that jsdom cannot observe, which is why they survived
// a green unit suite — so each gets a browser assertion here.
test.describe('Phase 4A — independent-review regressions', () => {
  // The /tickets worklist widened the SHARED `.ticket-table` class from 4 to 6
  // columns and added it to the 1040px `min-width: 900px` list. TicketDashboard
  // and AccountFinancePage also use that class and emit exactly 4 cells, and
  // both render inside `overflow: hidden` panels with no scroller — so the
  // widened template left dead track and the forced minimum clipped their last
  // column. The worklist now owns `.ticket-worklist-table` instead.
  test('shared .ticket-table keeps its 4-column contract for other consumers', async ({ page }) => {
    await loginAtViewport(page, 'account', { width: 768, height: 1024 });

    const shared = await page.evaluate(() => {
      const probe = document.createElement('div');
      probe.className = 'ticket-table table-head';
      probe.style.width = '900px';
      for (let i = 0; i < 4; i += 1) probe.appendChild(document.createElement('span'));
      document.body.appendChild(probe);
      const tracks = getComputedStyle(probe).gridTemplateColumns.split(' ').length;
      const minWidth = getComputedStyle(probe).minWidth;
      probe.remove();
      return { tracks, minWidth };
    });

    expect(shared.tracks, '.ticket-table must stay 4 columns for its 4-cell consumers').toBe(4);
    expect(shared.minWidth, '.ticket-table must not carry a forced 900px minimum').not.toBe('900px');
  });

  test('account finance row keeps its CTA inside the clipping panel at 768x1024', async ({ page }) => {
    await loginAtViewport(page, 'account', { width: 768, height: 1024 });
    await spaGoto(page, '/finance');

    // Wait for the row rather than sampling immediately after SPA nav.
    const row = page.locator('.ticket-table.data-row').first();
    await expect(row).toBeVisible();

    // The 4th cell is the row's primary action. It must sit inside the panel's
    // painted box, because the panel is `overflow: hidden` with no scrollbar.
    const fits = await row.evaluate((el) => {
      const panel = el.closest('section');
      const cta = el.querySelector(':scope > span:nth-child(4)');
      return {
        ctaRight: cta.getBoundingClientRect().right,
        panelRight: panel.getBoundingClientRect().right,
      };
    });
    expect(
      fits.ctaRight,
      `finance CTA is clipped (${fits.ctaRight} > ${fits.panelRight})`,
    ).toBeLessThanOrEqual(fits.panelRight + 1);
  });

  // The expansion row is a <tr> inside a `display: block` tbody, so without an
  // explicit display the browser generated an anonymous shrink-to-fit table
  // around it and the panel hugged the left edge instead of spanning the row.
  //
  // Asserted against a synthetic table rather than a live page: the two
  // `renderExpanded` callers (CommissionPage, AttendancePage) both need seeded
  // rows the mock does not produce — commission records require invoices, and
  // the attendance toggle only renders for days with `punch_count > 0`. This
  // probe reproduces DataTable's exact structure and the real stylesheet, so it
  // measures the precise mechanism that was broken. Mutation-checked: reverting
  // the `.data-table-expanded-row` display rules makes this fail and nothing
  // else. The live-page render is recorded as not-reproducible in
  // PHASE_4A_QA_RESULTS.md rather than claimed.
  test('expanded detail panel spans the full row width', async ({ page }) => {
    await loginAtViewport(page, 'sales', DESKTOP);

    const measured = await page.evaluate(() => {
      const host = document.createElement('div');
      host.style.width = '900px';
      host.innerHTML = `
        <section class="table-panel">
          <table class="data-table-table">
            <thead><tr class="table-head ticket-table"><th>a</th><th>b</th><th>c</th><th>d</th></tr></thead>
            <tbody>
              <tr class="data-row ticket-table"><td>1</td><td>2</td><td>3</td><td>4</td></tr>
              <tr class="data-table-expanded-row"><td colspan="4"><div>panel</div></td></tr>
            </tbody>
          </table>
        </section>`;
      document.body.appendChild(host);
      const expanded = host.querySelector('.data-table-expanded-row');
      const cell = expanded.querySelector('td');
      const dataRow = host.querySelector('tr.data-row');
      const result = {
        rowDisplay: getComputedStyle(expanded).display,
        cellDisplay: getComputedStyle(cell).display,
        expandedWidth: expanded.getBoundingClientRect().width,
        dataRowWidth: dataRow.getBoundingClientRect().width,
      };
      host.remove();
      return result;
    });

    expect(measured.rowDisplay, 'expansion row must be block, not table-row').toBe('block');
    expect(measured.cellDisplay, 'expansion cell must be block').toBe('block');
    expect(
      Math.abs(measured.expandedWidth - measured.dataRowWidth),
      `expansion panel is ${measured.expandedWidth}px vs row ${measured.dataRowWidth}px — shrink-to-fit regression`,
    ).toBeLessThanOrEqual(2);
  });

  // The compact 721–1040px tablet rail this test used to guard is gone — the
  // persistent icon-only sidebar was removed and that band now uses the same
  // off-canvas drawer as mobile (styles.css's `@media (max-width: 1040px)`
  // sets `.sidebar { visibility: hidden; transform: translateX(-100%) }`
  // until `.is-mobile-drawer-open` is applied). At 900px the sidebar (and
  // everything inside it, `visibility` being inherited) is therefore off-
  // screen and hidden by default; only opening the drawer via the hamburger
  // should reveal the logout control. This test guards that contract instead.
  test('sidebar is off-canvas at 900px; opening the drawer reveals the logout control', async ({ page }) => {
    await loginAtViewport(page, 'sales', { width: 900, height: 800 });

    const trigger = page.getByRole('button', { name: 'เปิดเมนูนำทาง' });
    await expect(trigger).toBeVisible();

    const logout = page.getByRole('button', { name: 'ออกจากระบบ' });
    await expect(logout).toBeHidden();

    await trigger.click();

    await expect(logout).toBeVisible();
    const iconWidth = await logout.evaluate((el) => {
      const svg = el.querySelector('svg');
      return svg ? svg.getBoundingClientRect().width : 0;
    });
    expect(iconWidth, 'logout icon is collapsed to zero width inside the drawer').toBeGreaterThan(0);
  });

});

// Every page that renders the shared DataTable, with the role that can reach it.
// This is the complete caller set: `grep -rl DataTable frontend/src` also hits
// CatalogSearchPage and PriceImportPage, but both only MENTION DataTable in
// comments explaining why they use a plain <table> instead, so neither is
// affected by the shared-table change.
const DATATABLE_SURFACES = [
  { key: 'sales-tickets', role: 'sales', path: '/tickets', heading: 'รายการดีล' },
  { key: 'sales-manager-tickets', role: 'sales_manager', path: '/tickets', heading: 'รายการดีล' },
  { key: 'ceo-tickets', role: 'ceo', path: '/tickets', heading: 'รายการดีล' },
  { key: 'employees', role: 'hr', path: '/employees' },
  { key: 'attendance', role: 'hr', path: '/attendance' },
  { key: 'payroll', role: 'hr', path: '/payroll' },
  { key: 'procurement', role: 'import', path: '/procurement' },
  { key: 'pricing-requests', role: 'ceo', path: '/pricing-requests' },
  { key: 'commissions', role: 'ceo', path: '/commissions' },
];

test.describe('Phase 4A — DataTable caller smoke + evidence matrix', () => {
  for (const surface of DATATABLE_SURFACES) {
    for (const [label, viewport] of [['desktop-1366x768', DESKTOP], ['mobile-390x844', MOBILE]]) {
      test(`${surface.key} @ ${label}`, async ({ page }) => {
        const problems = watchForProblems(page);
        await loginAtViewport(page, surface.role, viewport);
        await spaGoto(page, surface.path);

        // The route must actually render its own page, not silently bounce to a
        // fallback — that is what makes this a smoke test rather than a
        // screenshot.
        await expect(page).toHaveURL(new RegExp(`${surface.path}$`));
        if (surface.heading) {
          await expect(page.getByRole('heading', { name: surface.heading, level: 1 })).toBeVisible();
        }
        // Either the table/list rendered, or a legitimate empty/error state did.
        await expect(
          page.locator('table, [role="table"], .data-table, .record-card-list, .empty-state, [role="alert"]').first(),
        ).toBeVisible();

        await assertNoHorizontalOverflow(page, `${surface.key} @ ${label}`);

        if (CAPTURE) {
          await page.screenshot({
            path: `${EVIDENCE_ROOT}/datatable-callers/${surface.key}/${label}.png`,
            fullPage: false,
          });
        }

        expect(problems, `unexpected console/page errors: ${problems.join(' | ')}`).toEqual([]);
      });
    }
  }

  // The tablet shell is its own required matrix row: both orientations.
  for (const [label, viewport] of [
    ['tablet-1024x768', { width: 1024, height: 768 }],
    ['tablet-768x1024', { width: 768, height: 1024 }],
  ]) {
    test(`tablet shell @ ${label}`, async ({ page }) => {
      const problems = watchForProblems(page);
      await loginAtViewport(page, 'sales', viewport);
      await spaGoto(page, '/tickets');

      await expect(page.getByRole('heading', { name: 'รายการดีล', level: 1 })).toBeVisible();
      await assertNoHorizontalOverflow(page, `tablet shell @ ${label}`);

      if (CAPTURE) {
        await page.screenshot({ path: `${EVIDENCE_ROOT}/shared/${label}.png`, fullPage: false });
      }
      expect(problems, `unexpected console/page errors: ${problems.join(' | ')}`).toEqual([]);
    });
  }

  // Role captures for the three roles that can reach the worklist, at the two
  // required viewports — these overwrite the tracked role evidence.
  for (const role of ['sales', 'sales-manager', 'ceo']) {
    for (const [label, viewport] of [['desktop-1366x768', DESKTOP], ['mobile-390x844', MOBILE]]) {
      test(`role evidence ${role} @ ${label}`, async ({ page }) => {
        await loginAtViewport(page, role.replace('-', '_'), viewport);
        await spaGoto(page, '/tickets');
        await expect(page.getByRole('heading', { name: 'รายการดีล', level: 1 })).toBeVisible();
        if (CAPTURE) {
          await page.screenshot({ path: `${EVIDENCE_ROOT}/${role}/${label}.png`, fullPage: false });
        }
      });
    }
  }

  // The mobile filter surface, captured with the sheet open — the state the
  // acceptance fix changed.
  test('mobile filter surface evidence', async ({ page }) => {
    await loginAtViewport(page, 'sales', MOBILE);
    await spaGoto(page, '/tickets');
    await openFilterSheet(page);
    await expect(page.getByRole('dialog', { name: 'ตัวกรองเพิ่มเติม' })).toBeVisible();
    if (CAPTURE) {
      await page.screenshot({ path: `${EVIDENCE_ROOT}/shared/mobile-filter-surface.png`, fullPage: false });
    }
  });
});
