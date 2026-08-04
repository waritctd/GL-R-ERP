import { expect, test } from '@playwright/test';
import { loginAs, spaGoto } from './helpers/auth.js';

// Regression guard for the dead band under panel titles.
//
// `.panel h2 { margin: 0; font-size: var(--text-lg) }` was scoped to `.panel`,
// so it never reached `.table-panel` — the class every table-topped card uses.
// Those h2s kept the UA default `font-size: 1.5em` / `margin: 0.83em 0`,
// measured at 24px text with 19.92px above and below inside a 101px header,
// leaving 51–59px of empty space above the first row. It also meant a section
// title rendered at 16px or 24px purely by which class its card carried.
//
// Asserted on `/ceo-settings` because all four of its panels are `.table-panel`
// — the branch that was NOT covered by the old selector. A `.panel`-based page
// would have passed this test before the fix.

test.describe('panel headers do not strand their title', () => {
  test('table-panel titles use the same type scale as panel titles', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await loginAs(page, 'ceo');
    await spaGoto(page, '/ceo-settings');
    await expect(page.locator('.table-panel .panel-header h2').first()).toBeVisible();

    const headers = await page.evaluate(() => {
      const expected = getComputedStyle(document.documentElement)
        .getPropertyValue('--text-lg').trim();
      return {
        expected,
        rows: Array.from(document.querySelectorAll('.table-panel > .panel-header')).map((header) => {
          const h2 = header.querySelector('h2');
          const cs = getComputedStyle(h2);
          return {
            title: h2.textContent.trim().slice(0, 20),
            font: cs.fontSize,
            marginTop: parseFloat(cs.marginTop),
            marginBottom: parseFloat(cs.marginBottom),
            headerHeight: header.getBoundingClientRect().height,
            hasActions: header.children.length > 1,
          };
        }),
      };
    });

    expect(headers.rows.length, 'no table-panel headers found to check').toBeGreaterThan(0);

    for (const row of headers.rows) {
      expect(row.font, `"${row.title}" title is ${row.font}, expected --text-lg (${headers.expected})`)
        .toBe(headers.expected);
      expect(row.marginTop, `"${row.title}" title kept its UA top margin`).toBe(0);
      expect(row.marginBottom, `"${row.title}" title kept its UA bottom margin`).toBe(0);
      // A title-only header is padding + one line of 16px text. 101px was the
      // broken value; anything approaching it means the reset stopped applying.
      // Headers carrying an action button are legitimately taller, so they are
      // held to the button's own 44px touch height plus the header's padding.
      const ceiling = row.hasActions ? 80 : 60;
      expect(row.headerHeight, `"${row.title}" header is ${row.headerHeight}px tall`)
        .toBeLessThanOrEqual(ceiling);
    }
  });

  test('a table-panel header does not double-space against its body', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await loginAs(page, 'ceo');
    await spaGoto(page, '/ceo-settings');
    await expect(page.locator('.table-panel .panel-header h2').first()).toBeVisible();

    // `.panel-header`'s 16px `margin-bottom` suits a `.panel`, whose content
    // follows inside the card's own padding. A `.table-panel` body brings its
    // own inset, so the margin stacked on top of it — and on the headers that
    // also draw a `border-bottom`, it pushed the rule away from its title.
    const margin = await page.evaluate(() => {
      const header = document.querySelector('.table-panel > .panel-header');
      return parseFloat(getComputedStyle(header).marginBottom);
    });

    expect(margin, 'table-panel header re-introduced its bottom margin').toBe(0);
  });
});
