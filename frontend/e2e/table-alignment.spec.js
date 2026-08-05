import { expect, test } from '@playwright/test';
import { loginAs, spaGoto } from './helpers/auth.js';

// Column alignment is geometry, so these have to run in a real browser — jsdom
// reports every rect as zero, and a vitest version would pass whether or not
// the columns line up. Asserting on `align: 'right'` being present in the
// column definition would be worse than useless: it would restate the source
// rather than test that the pixels moved.

test.describe('table columns align on the edge that carries meaning', () => {
  test('leave review: the trailing action lines up whatever the button count', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await loginAs(page, 'hr');
    await spaGoto(page, '/leave');
    // `role: 'tab'`, not 'button' — Tabs.jsx renders a <button role="tab">, and
    // a role-button locator here just times out.
    await page.getByRole('tab', { name: /รอพิจารณา/ }).click();
    // Wait for a row's action buttons, not just the row: `.data-row` is present
    // while the queue is still resolving, and measuring then finds zero cells
    // and reports "nothing to compare" rather than a misalignment.
    await expect(page.locator('.data-row button').first()).toBeVisible();

    // Row action counts vary: a pending request offers approve/reject/cancel,
    // an already-decided one only cancel. Left-packed, the shorter rows ended
    // 88px short of the longer ones (1292 vs 1380) and the column read ragged.
    const edges = await page.evaluate(() => {
      const seen = [];
      document.querySelectorAll('.data-row').forEach((row) => {
        const cell = row.children[row.children.length - 1];
        const buttons = cell.querySelectorAll('button');
        if (!buttons.length) return;
        seen.push({
          count: buttons.length,
          right: Math.round(buttons[buttons.length - 1].getBoundingClientRect().right),
        });
      });
      return seen;
    });

    expect(edges.length, 'no action cells found to compare').toBeGreaterThan(1);
    // The test is only meaningful if the fixture actually contains rows with
    // DIFFERENT button counts — with a uniform count every alignment passes.
    const counts = new Set(edges.map((e) => e.count));
    expect(counts.size, `every row had ${[...counts]} button(s); this fixture cannot detect raggedness`)
      .toBeGreaterThan(1);

    // Tolerance, not equality. Sub-pixel layout differs between machines — CI
    // measured 1367 and 1365 for a correctly-aligned column, which an
    // exact-match assertion failed. 2px still catches what this guards: the
    // defect was an 88px gap (1292 vs 1380), and anything approaching that
    // is a raggedness a reader would see.
    const rights = edges.map((e) => e.right);
    const spread = Math.max(...rights) - Math.min(...rights);
    expect(
      spread,
      `action buttons end at ${[...new Set(rights)].join(', ')} — spread ${spread}px, should share a right edge`,
    ).toBeLessThanOrEqual(2);
  });

  test('employee list: salary column and its header share a right edge', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await loginAs(page, 'hr');
    await spaGoto(page, '/employees');
    await expect(page.locator('.data-row').first()).toBeVisible();

    // Money compares by its digits, so it anchors right. Left-aligned, ฿9,000
    // and ฿156,000 start at the same x and agree only on where they end.
    const result = await page.evaluate(() => {
      const header = Array.from(document.querySelectorAll('.table-head > *'))
        .find((h) => h.textContent.includes('เงินเดือน'));
      const rights = [];
      document.querySelectorAll('.data-row').forEach((row) => {
        const cell = Array.from(row.children).find((c) => /฿/.test(c.textContent));
        if (!cell) return;
        const inner = cell.querySelector('code') || cell;
        rights.push(Math.round(inner.getBoundingClientRect().right));
      });
      return { headerRight: header ? Math.round(header.getBoundingClientRect().right) : null, rights };
    });

    expect(result.rights.length, 'no salary cells found').toBeGreaterThan(1);

    // Same tolerance rationale as the action column above.
    const salarySpread = Math.max(...result.rights) - Math.min(...result.rights);
    expect(
      salarySpread,
      `salary values end at ${[...new Set(result.rights)].join(', ')} — spread ${salarySpread}px`,
    ).toBeLessThanOrEqual(2);

    // Header and body must agree, or the label points at the wrong column.
    expect(result.headerRight, 'no เงินเดือน header found').not.toBeNull();
    expect(
      Math.abs(result.headerRight - result.rights[0]),
      `header right edge ${result.headerRight} vs values ${result.rights[0]}`,
    ).toBeLessThanOrEqual(2);
  });
});
