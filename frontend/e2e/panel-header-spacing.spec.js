import { expect, test } from '@playwright/test';
import { loginAs, spaGoto } from './helpers/auth.js';

// Regression guard for the dead band under panel titles.
//
// `.panel h2 { margin: 0; font-size: var(--text-lg) }` was scoped to `.panel`,
// so it never reached `.table-panel` — the class every table-topped card used.
// Those h2s kept the UA default `font-size: 1.5em` / `margin: 0.83em 0`,
// measured at 24px text with 19.92px above and below inside a 101px header,
// leaving 51–59px of empty space above the first row. It also meant a section
// title rendered at 16px or 24px purely by which class its card carried.
//
// Asserted on the RENDERED RESULT, not on `.table-panel > .panel-header`, which
// is what the first version of this spec did. That version broke the moment a
// page was migrated onto the `<Panel>` component — it was pinned to the very
// class names the migration exists to retire, so it failed on correct output.
// A title is a title whichever markup produced it; that is what is checked here.
//
// The legacy half of this spec is GONE, on purpose. It probed the styles.css
// `.panel-header` rules directly, and those rules were deleted once the last
// call site was migrated — a guard for a rule that no longer exists is not a
// guard, it is a fixture pinning dead code in place. What remains is the part
// that still has a subject: <Panel>'s own output.

async function headingMetrics(page) {
  return page.evaluate(() => {
    const expected = getComputedStyle(document.documentElement)
      .getPropertyValue('--text-lg').trim();
    const rows = [];
    document.querySelectorAll('section').forEach((section) => {
      const h2 = section.querySelector('h2');
      if (!h2 || h2.getBoundingClientRect().height === 0) return;
      const cs = getComputedStyle(h2);
      rows.push({
        title: h2.textContent.trim().slice(0, 24),
        font: cs.fontSize,
        marginTop: parseFloat(cs.marginTop),
        marginBottom: parseFloat(cs.marginBottom),
      });
    });
    return { expected, rows };
  });
}

function assertTitlesOnScale({ expected, rows }, label) {
  expect(rows.length, `${label}: no card headings found to check`).toBeGreaterThan(0);
  for (const row of rows) {
    expect(row.font, `${label}: "${row.title}" is ${row.font}, expected --text-lg (${expected})`)
      .toBe(expected);
    // The UA default is 0.83em top and bottom. That margin IS the dead band —
    // 19.92px above and below a 24px title — so it is the thing to pin.
    expect(row.marginTop, `${label}: "${row.title}" kept a top margin`).toBe(0);
    expect(row.marginBottom, `${label}: "${row.title}" kept a bottom margin`).toBe(0);
  }
}

test.describe('panel headers do not strand their title', () => {
  test('migrated panels (<Panel>) keep titles on the type scale', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await loginAs(page, 'ceo');
    await spaGoto(page, '/ceo-settings');
    await expect(page.locator('section h2').first()).toBeVisible();

    assertTitlesOnScale(await headingMetrics(page), '/ceo-settings');
  });

});
