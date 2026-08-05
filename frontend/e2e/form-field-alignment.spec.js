import { expect, test } from '@playwright/test';
import { loginAs, spaGoto } from './helpers/auth.js';

// Regression guard for the app-wide field stagger.
//
// `.form-field` / the global `label { display: grid }` rule stack a label above
// a control in auto rows. Rendered as a stretched grid item of a two-column
// `FormGrid`, the row's spare height used to be distributed INTO those rows, so
// the control itself grew — and grew by a different amount depending on how
// many rows that field had, i.e. whether it carried a hint line. Two fields
// side by side in one row therefore rendered at different heights and different
// vertical offsets. `items-start` (FormGrid), `content-start` (FormField) and a
// fixed `height` on the base control rule (styles.css) each stop that, and this
// spec is what tells you if any of the three is reverted.
//
// These assertions only mean something in a real browser: jsdom has no layout
// engine, so `getBoundingClientRect()` there is all zeros and a vitest version
// of this test would pass no matter what the CSS says.

/**
 * The pair must be two controls on the SAME `FormGrid` row where exactly one
 * field carries a hint — that asymmetry is the thing that used to produce the
 * stagger, so a pair without it would pass even with the fix reverted.
 *
 * Both rects are read in ONE `page.evaluate`. Measuring them in two round-trips
 * is unsound for a layout assertion: these forms reflow as their async caps/
 * options land, so the two reads can land either side of a reflow and report a
 * difference that never existed on screen. Waiting for the hint element first
 * is the settle signal — FormField only renders `#<id>-hint` once the field is
 * fully built, so its presence means the row has its final height.
 */
async function expectRowAligned(page, { withHint, withoutHint, label }) {
  await expect(page.locator(`${withHint}-hint`)).toBeVisible();

  const { hinted, plain } = await page.evaluate(([a, b]) => {
    const read = (sel) => {
      const rect = document.querySelector(sel).getBoundingClientRect();
      return { top: rect.top, height: rect.height };
    };
    return { hinted: read(a), plain: read(b) };
  }, [withHint, withoutHint]);

  expect(hinted.height, `${label}: hinted control has no height`).toBeGreaterThan(0);
  expect(
    Math.abs(hinted.height - plain.height),
    `${label}: controls on one row differ in height (${hinted.height} vs ${plain.height})`,
  ).toBeLessThanOrEqual(1);
  expect(
    Math.abs(hinted.top - plain.top),
    `${label}: controls on one row differ in vertical offset (${hinted.top} vs ${plain.top})`,
  ).toBeLessThanOrEqual(1);
}

test.describe('two-column form rows stay aligned', () => {
  test('ล.ย.01 declaration: month select lines up with document-reference input', async ({ page }) => {
    await loginAs(page, 'employee');
    await spaGoto(page, '/tax-allowance');

    // `มีผลตั้งแต่งวดเดือน` carries the hint "เว้นว่างไว้ = มีผลตั้งแต่เดือนมกราคม";
    // `เลขที่เอกสารอ้างอิง (ถ้ามี)` carries none. This is the exact row that was
    // reported: the select rendered ~32px and sat ~10px above a ~47px input.
    await expect(page.locator('#ta-effective-month')).toBeVisible();
    await expectRowAligned(page, {
      withHint: '#ta-effective-month',
      withoutHint: '#ta-document-reference',
      label: 'ล.ย.01 effective-month row',
    });
  });

  test('employee form: salary-block controls line up across the hint boundary', async ({ page }) => {
    await loginAs(page, 'hr');
    await spaGoto(page, '/employees');
    await page.getByRole('button', { name: 'เพิ่มพนักงาน' }).first().click();

    // `ภาษีหัก ณ ที่จ่าย (กำหนดเอง)` has hint "เว้นว่าง = คำนวณอัตโนมัติ";
    // `ค่าตอบแทนกรรมการ` beside it has none. In source the two fields differ by
    // nothing else — which is what made this the clearest reproduction.
    await expect(page.locator('#employee-directorRemuneration')).toBeVisible();
    await expectRowAligned(page, {
      withHint: '#employee-withholdingTaxOverride',
      withoutHint: '#employee-directorRemuneration',
      label: 'employee remuneration row',
    });
  });

  test('adorned inputs keep the inset that clears their symbol', async ({ page }) => {
    await loginAs(page, 'employee');
    await spaGoto(page, '/tax-allowance');
    await expect(page.locator('.currency-input input').first()).toBeVisible();

    // `.currency-input input` overrides the base control padding with a 34px
    // left inset so the absolutely-positioned ฿ glyph does not sit on the
    // value. That override is a SPECIFICITY win, not a source-order one, so
    // raising the base rule's specificity silently breaks it — which is what
    // an `input:not([type='checkbox']):not([type='radio'])` base selector
    // (0,2,1 vs the override's 0,1,1) did. The suite is blind to this without
    // an explicit read: nothing else asserts padding, and the field still has
    // the right height and position while reading `฿0000`.
    const padding = await page.evaluate(() => {
      const input = document.querySelector('.currency-input input');
      return getComputedStyle(input).paddingLeft;
    });

    expect(padding, 'currency input lost its symbol inset').toBe('34px');
  });

  test('bare checkboxes are not stretched by the base control rule', async ({ page }) => {
    await loginAs(page, 'employee');
    await spaGoto(page, '/tax-allowance');
    // Wait for the form itself, not just the route change: `spaGoto` returns
    // before the lazy route has painted, and a bare `querySelector` in that
    // window finds nothing and reports a missing checkbox rather than a
    // stretched one.
    await expect(page.locator('#ta-disabilityCardHolder')).toBeAttached();

    // styles.css's `input, select, textarea` box applied `width: 100%` and a
    // 40px padded border-box to native checkboxes too, rendering a bare
    // checkbox as a full-width bar. Nothing overrode it, so this is a floor,
    // not a preference: a checkbox must stay checkbox-sized.
    const box = await page.evaluate(() => {
      const input = document.querySelector('#ta-disabilityCardHolder');
      if (!input) return null;
      const rect = input.getBoundingClientRect();
      return { width: rect.width, height: rect.height };
    });

    // Asserted as a band, not a ceiling. A ceiling alone passes at 1px, which
    // would be just as broken as 668px and just as invisible. The band is the
    // chosen 16px box plus room for sub-pixel rounding — tight enough that
    // falling back to the 13px UA default, or inheriting the 40px text-control
    // box again, both fail.
    expect(box, 'no checkbox rendered on the ล.ย.01 form').not.toBeNull();
    expect(box.width, `checkbox is ${box.width}px wide, expected ~16`).toBeGreaterThanOrEqual(15);
    expect(box.width, `checkbox is ${box.width}px wide, expected ~16`).toBeLessThanOrEqual(18);
    expect(box.height, `checkbox is ${box.height}px tall, expected ~16`).toBeGreaterThanOrEqual(15);
    expect(box.height, `checkbox is ${box.height}px tall, expected ~16`).toBeLessThanOrEqual(18);
  });

  test('inputs sharing a flex row with a button stay flush at mobile width', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await loginAs(page, 'import');
    await spaGoto(page, '/factory-purchase-orders');
    await spaGoto(page, '/factory-purchase-orders/1');
    await expect(page.locator('input').first()).toBeVisible();

    // ≤720px is the only band where this can break, and it is exactly the band
    // the other tests never visit. `align-items: stretch` stretches a flex item
    // only when its cross-size is `auto`, so under the old `min-height: 40px`
    // these inputs were silently pulled up to the 44px height styles.css gives
    // the legacy button classes carried at this width. The base rule's fixed `height` opts out
    // of that stretch, so the floor has to be stated rather than inherited —
    // without it the inputs end 4px above the button and the row reads ragged.
    await page.setViewportSize({ width: 700, height: 900 });
    await page.waitForTimeout(300);

    const ragged = await page.evaluate(() => {
      const bad = [];
      document.querySelectorAll('div.flex.flex-wrap').forEach((row) => {
        const controls = Array.from(row.children)
          .filter((child) => /^(INPUT|BUTTON|SELECT)$/.test(child.tagName));
        if (controls.length < 2) return;
        const bottoms = controls.map((c) => Math.round(c.getBoundingClientRect().bottom));
        // Only rows that wrapped onto one line can be compared; a wrapped row
        // legitimately has several bottoms.
        const tops = new Set(controls.map((c) => Math.round(c.getBoundingClientRect().top)));
        if (tops.size !== 1) return;
        if (new Set(bottoms).size > 1) {
          bad.push(controls.map((c, i) => `${c.tagName}=${bottoms[i]}`).join(' '));
        }
      });
      return bad;
    });

    expect(ragged, `controls on one row end at different heights: ${ragged.join(' | ')}`).toEqual([]);
  });
});

test.describe('filter rows align on the control, not the label stack', () => {
  test('ล.ย.01 register: year select is centred against the status chips', async ({ page }) => {
    await loginAs(page, 'hr');
    await spaGoto(page, '/tax-allowance-review');
    await expect(page.locator('#tax-allowance-year')).toBeVisible();

    // A labelled field is a stack — label above control — so `items-center` on
    // the row centres the STACK, not the control, and drops the select below
    // the chips it sits beside (measured: 64px field vs 41px chip row, select
    // ~12px low). `FilterRow`'s `items-end` bottom-aligns the boxes, which for
    // a 33px pill beside a 40px select works out to optical centre.
    //
    // Centre, not bottom, is the assertion: the chip scroller carries 4px of
    // symmetric padding so its focus ring is not clipped, so the visible pill's
    // bottom edge legitimately sits 4px inside the row's bottom.
    const delta = await page.evaluate(() => {
      const sel = document.querySelector('#tax-allowance-year').getBoundingClientRect();
      const chip = document.querySelector('[aria-label="กรองตามสถานะ"] button').getBoundingClientRect();
      const mid = (r) => (r.top + r.bottom) / 2;
      return Math.abs(mid(sel) - mid(chip));
    });

    expect(delta, `select and chips are ${delta}px out of centre`).toBeLessThanOrEqual(2);
  });

  // The self-service filter bars are hand-rolled: each is a native <form> (for
  // Enter-to-submit), so it reproduces FilterBar's utility string inline rather
  // than using the component. That copy carried `items-center` long after #530
  // fixed the component's own row, and the same stagger was live on the pages
  // UAT phase 1 ships — a bare `<label>` stack next to an unlabelled submit
  // Button, so the Button sat 11px high of the controls beside it.
  //
  // Asserted per-route rather than once, because each file owns its own copy of
  // the string; fixing one says nothing about the others.
  for (const { route, label } of [
    { route: '/leave', label: 'วันลา' },
    { route: '/employee-requests', label: 'คำขอ OT' },
  ]) {
    test(`${label}: the search button sits flush with the filters beside it`, async ({ page }) => {
      await loginAs(page, 'employee');
      await spaGoto(page, route);
      await expect(page.getByRole('button', { name: /ค้นหา/ }).first()).toBeVisible();

      const rows = await page.evaluate(() => {
        const button = Array.from(document.querySelectorAll('button'))
          .find((b) => /ค้นหา/.test(b.textContent));
        const form = button.closest('form');
        const controls = Array.from(form.querySelectorAll('input[type="date"], select'));
        const bottom = (el) => Math.round(el.getBoundingClientRect().bottom);
        return { button: bottom(button), controls: controls.map(bottom) };
      });

      expect(rows.controls.length, 'no date/select filters found to compare against')
        .toBeGreaterThan(0);
      for (const controlBottom of rows.controls) {
        expect(
          Math.abs(rows.button - controlBottom),
          `search button ends at ${rows.button}, a filter beside it at ${controlBottom}`,
        ).toBeLessThanOrEqual(2);
      }
    });
  }
});
