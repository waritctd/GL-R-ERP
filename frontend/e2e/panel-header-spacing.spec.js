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

// The legacy half of this spec is probed against CONSTRUCTED elements, not
// found on a page. It has now been invalidated three times by the migration it
// is meant to outlive: it asserted on /ceo-settings, then /pricing-requests/:id,
// then /commissions, and each was migrated in turn. What is under test is a CSS
// RULE, and a rule does not need a page to use it in order to be measurable.
//
// Delete this half — and `assertLegacyRule` with it — when the last
// `.panel-header` call site goes and the styles.css rules are removed. It
// should not be re-pointed at anything.
async function measureLegacyRule(page) {
  return page.evaluate(() => {
    const expected = getComputedStyle(document.documentElement)
      .getPropertyValue('--text-lg').trim();

    const tablePanel = document.createElement('section');
    tablePanel.className = 'table-panel';
    const insideHeader = document.createElement('div');
    insideHeader.className = 'panel-header';
    const insideHeading = document.createElement('h2');
    insideHeading.textContent = 'x';
    insideHeader.appendChild(insideHeading);
    tablePanel.appendChild(insideHeader);

    const loneHeader = document.createElement('div');
    loneHeader.className = 'panel-header';

    document.body.append(tablePanel, loneHeader);
    const result = {
      expected,
      headingFont: getComputedStyle(insideHeading).fontSize,
      headingMarginTop: parseFloat(getComputedStyle(insideHeading).marginTop),
      headingMarginBottom: parseFloat(getComputedStyle(insideHeading).marginBottom),
      marginInTablePanel: parseFloat(getComputedStyle(insideHeader).marginBottom),
      marginStandalone: parseFloat(getComputedStyle(loneHeader).marginBottom),
    };
    tablePanel.remove();
    loneHeader.remove();
    return result;
  });
}

test.describe('panel headers do not strand their title', () => {
  test('migrated panels (<Panel>) keep titles on the type scale', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await loginAs(page, 'ceo');
    await spaGoto(page, '/ceo-settings');
    await expect(page.locator('section h2').first()).toBeVisible();

    assertTitlesOnScale(await headingMetrics(page), '/ceo-settings');
  });

  test('the legacy styles.css rules still size and space a .panel-header', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await loginAs(page, 'hr');

    const m = await measureLegacyRule(page);

    // `.panel h2, .panel-header h2` — re-scoping this back to `.panel` alone
    // returns these headings to the 24px/0.83em UA default, which is the
    // 101px header and the 51-59px dead band this whole spec exists for. The
    // /ceo-settings test above cannot see it: <Panel> sets the size with a
    // Tailwind class regardless of what styles.css says.
    expect(m.headingFont, `legacy .panel-header h2 is ${m.headingFont}, expected ${m.expected}`)
      .toBe(m.expected);
    expect(m.headingMarginTop, 'legacy .panel-header h2 kept a top margin').toBe(0);
    expect(m.headingMarginBottom, 'legacy .panel-header h2 kept a bottom margin').toBe(0);

    // `.table-panel > .panel-header { margin-bottom: 0 }` — without it the
    // header's own 16px stacks on the flush body's inset and pushes the rule
    // away from its title.
    expect(m.marginInTablePanel, 'table-panel header re-introduced its bottom margin').toBe(0);
    // The control: a `.panel-header` OUTSIDE a table-panel must keep its 16px.
    // Without this, deleting the base rule entirely would also pass.
    expect(m.marginStandalone, 'the base .panel-header margin was lost').toBe(16);
  });
});
