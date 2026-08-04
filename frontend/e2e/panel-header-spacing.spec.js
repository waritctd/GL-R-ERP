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
// Both mechanisms are covered on purpose, because only testing one leaves the
// other unguarded:
//   /ceo-settings  → migrated, sized by <Panel> (Tailwind utilities)
//   /commissions   → still legacy `.panel-header` markup, sized by the
//                    styles.css rule the remaining call sites resolve to
//
// The legacy half is deliberately temporary and has already moved once: it
// pointed at /pricing-requests/:id until that page was migrated too. Any page
// named here is a page someone is queued up to migrate, so expect to re-point
// it — and when the LAST `.panel-header` call site goes (CommissionPage,
// TicketDetailPage, DepositNoticePage, TicketDashboard are what remain), the
// styles.css rule gets deleted and this half of the spec should be deleted
// with it rather than re-pointed at nothing.

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

async function openLegacyPanelPage(page) {
  // /commissions rather than a deal page: the Deal* panels were migrated onto
  // <Panel> already, so ticket detail no longer renders `.panel-header` on its
  // default tab. CommissionPage still does, at the top level.
  await loginAs(page, 'sales');
  await spaGoto(page, '/commissions');
  await expect(page.locator('.panel-header h2').first()).toBeVisible();
}

test.describe('panel headers do not strand their title', () => {
  test('migrated panels (<Panel>) keep titles on the type scale', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await loginAs(page, 'ceo');
    await spaGoto(page, '/ceo-settings');
    await expect(page.locator('section h2').first()).toBeVisible();

    assertTitlesOnScale(await headingMetrics(page), '/ceo-settings');
  });

  test('legacy .panel-header markup keeps titles on the same type scale', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await openLegacyPanelPage(page);

    // This is the guard on the styles.css rule itself. If `.panel h2,
    // .panel-header h2` is ever re-scoped back to `.panel` alone, the headings
    // here revert to the 24px UA default and this fails — which the
    // /ceo-settings test cannot detect, because <Panel> sets the size with a
    // Tailwind class regardless of what styles.css says.
    assertTitlesOnScale(await headingMetrics(page), '/commissions');
  });

  test('the .table-panel > .panel-header override still zeroes the bottom margin', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await openLegacyPanelPage(page);

    // Probed against a constructed element rather than found on the page.
    //
    // What is under test here is a CSS RULE, not any particular markup:
    // `.panel-header` carries a 16px `margin-bottom` that suits a `.panel`
    // (whose content follows inside the card's own padding), and
    // `.table-panel > .panel-header` overrides it to 0 because a table-panel
    // body brings its own inset — without the override the margin stacked on
    // top of it and, on headers that also draw a `border-bottom`, pushed the
    // rule away from the title it belongs to.
    //
    // Binding that to a page meant the test evaporated as pages migrated: it
    // has already had to move once, and the pages still rendering this exact
    // combination render it only in states the mock seed does not reach. The
    // rule is live for every remaining call site regardless, so it is measured
    // directly and the assertion stops depending on migration progress.
    const margins = await page.evaluate(() => {
      const panel = document.createElement('section');
      panel.className = 'table-panel';
      const header = document.createElement('div');
      header.className = 'panel-header';
      panel.appendChild(header);

      const plain = document.createElement('div');
      plain.className = 'panel-header';

      document.body.append(panel, plain);
      const result = {
        inTablePanel: parseFloat(getComputedStyle(header).marginBottom),
        standalone: parseFloat(getComputedStyle(plain).marginBottom),
      };
      panel.remove();
      plain.remove();
      return result;
    });

    expect(margins.inTablePanel, 'table-panel header re-introduced its bottom margin').toBe(0);
    // The control: a `.panel-header` OUTSIDE a table-panel must keep its 16px.
    // Without this, deleting the base rule entirely would also pass.
    expect(margins.standalone, 'the base .panel-header margin was lost').toBe(16);
  });
});
