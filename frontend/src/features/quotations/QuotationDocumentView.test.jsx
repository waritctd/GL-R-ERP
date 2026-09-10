import { render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { QuotationDocumentView } from './QuotationDocumentView.jsx';

// F2 (Opus final-pass review, Quotation v2): ITEM_GRID used to end in `mobile:grid-cols-1`, so at
// 390px `.table-head` stacked into six orphan labels (รายละเอียด/จำนวน/ราคา.../ส่วนลด/สุทธิ/
// เป็นเงิน with no values under them) and every body value rendered unlabelled underneath. Fixed
// by switching to the repo's `reflow-cards` card-reflow treatment (see OvertimePanel.jsx's
// OVERTIME_TABLE_GRID for the same pattern): `.table-head` is hidden below 720px (styles.css
// ~L1330) and each `.data-row` cell carries its own `data-label`, printed via CSS `::before`.
//
// jsdom never evaluates styles.css (vitest.config.js sets `css: false`), so the label/value
// pairing this test can prove is in the MARKUP -- each value cell carries the `data-label`
// attribute CSS keys off -- matching the convention `OvertimePage.test.jsx` already uses for the
// same CSS-only reflow mechanism. The matchMedia stub is included anyway (repo convention for
// "mobile width" assertions, e.g. TicketListPage.test.jsx's stubMobile) so this test still means
// what it says if the component ever grows a JS-side mobile branch.

const realMatchMedia = window.matchMedia;

afterEach(() => {
  window.matchMedia = realMatchMedia;
});

function stubMobileViewport() {
  window.matchMedia = (query) => ({
    matches: query === '(max-width: 720px)',
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
  });
}

const quotation = {
  id: 1,
  docStatus: 'ISSUED',
  customerName: 'บริษัท ทดสอบ จำกัด',
  projectName: 'โครงการทดสอบ',
  salesRepName: 'คุณสมหมาย ขายดี',
  salesRepPhone: '0812345678',
  quotationDate: '2026-09-01',
  items: [
    {
      id: 11,
      seq: 1,
      locationLabel: 'ห้องนั่งเล่น',
      descriptionLine: 'กระเบื้องพอร์ซเลน 60x60',
      sizeLine: '60x60 ซม.',
      calculationLine: '(พื้นที่ 20 ตร.ม. รวม 60 แผ่น)',
      piecesFinal: 60,
      unitPrice: 350,
      discountPct: 10,
      netUnitPrice: 315,
      lineAmount: 18900,
    },
  ],
  subtotalAmount: 18900,
  vatAmount: 1323,
  grandTotal: 20223,
  depositPercent: 30,
  remainderMode: 'CREDIT',
  creditDays: 30,
  validityDays: 30,
  createdByName: 'คุณสมหมาย ขายดี',
};

describe('QuotationDocumentView -- mobile card reflow (F2)', () => {
  it('gives every item value its own data-label, matching the head row, at mobile width', () => {
    stubMobileViewport();
    const { container } = render(<QuotationDocumentView quotation={quotation} />);

    const dataRow = container.querySelector('.data-row');
    expect(dataRow).toBeTruthy();

    const expectedLabels = {
      'รายละเอียด': 'กระเบื้องพอร์ซเลน 60x60',
      'จำนวน': '60 แผ่น',
      'ราคา/หน่วย': '350.00',
      'ส่วนลด': '10%',
      'สุทธิ': '315.00',
      'เป็นเงิน': '18,900.00',
    };

    for (const [label, expectedText] of Object.entries(expectedLabels)) {
      const cell = dataRow.querySelector(`[data-label="${label}"]`);
      expect(cell, `missing data-label="${label}"`).toBeTruthy();
      expect(cell.textContent).toContain(expectedText);
    }

    // The head row must still declare the same six labels so the CSS's `.table-head { display:
    // none }` / `[data-label]::before` pairing stays in sync if a column is ever reordered.
    const headRow = container.querySelector('.table-head');
    expect(headRow.textContent).toContain('รายละเอียด');
    expect(headRow.textContent).toContain('เป็นเงิน');

    // The reflow itself is CSS (`.reflow-cards`, styles.css ~L1330) -- confirm the row carries the
    // class the mobile stylesheet keys off, since jsdom does not evaluate styles.css directly.
    expect(dataRow.className).toContain('reflow-cards');
    expect(headRow.className).toContain('reflow-cards');
  });
});

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Owner feedback pass 1, 2026-09-10 — F1 (one ตำแหน่งติดตั้ง heading per RUN) and F2 (ผู้สั่งซื้อ
// in the signature block).
// ─────────────────────────────────────────────────────────────────────────────────────────────

function docItem(seq, locationLabel, descriptionLine) {
  return {
    id: 100 + seq, seq, locationLabel, descriptionLine, sizeLine: '', calculationLine: '',
    piecesFinal: 10, unitPrice: 100, discountPct: 0, netUnitPrice: 100, lineAmount: 1000,
  };
}

function docQuotation(overrides = {}) {
  return {
    id: 1, docStatus: 'APPROVED', customerName: 'บริษัท ทดสอบ จำกัด', projectName: null,
    salesRepName: 'คุณสมหมาย ขายดี', salesRepPhone: null, createdByName: 'คุณสมหมาย ขายดี',
    approvedByName: 'ผึ้ง', quotationDate: '2026-09-01',
    subtotalAmount: 1000, vatAmount: 70, grandTotal: 1070,
    depositPercent: 30, remainderMode: 'CREDIT', creditDays: 30, validityDays: 30, validityDate: null,
    customerNotes: null, contactName: 'ณัฐพงศ์ ศรีวิไล', items: [],
    ...overrides,
  };
}

describe('QuotationDocumentView tablet-band column floors (owner review V5, 2026-09-10)', () => {
  // ⚠️ WHAT THIS CAN AND CANNOT PROVE. jsdom does no grid layout, so it cannot observe the bug
  // this pins: at 721px the six-column document was forced to `.reflow-cards`'s shared 900px floor
  // inside a 655px container and scrolled 245px sideways, hiding เป็นเงิน entirely. That was
  // measured in a real browser and can only be RE-measured in one. This is a TEXT guard on the
  // two class-string decisions that fix it, so a later edit cannot quietly drop them:
  //
  //   1. the five NUMERIC columns carry a `min-content` floor, so the grid can never shrink a
  //      baht figure below its own text (รายละเอียด keeps `minmax(0,…)` — it wraps, so it is the
  //      column that should absorb the squeeze);
  //   2. `tablet:min-w-0` releases the shared 900px floor for 721–1040px, which is only safe
  //      BECAUSE of (1).
  //
  // Drop either and the browser regresses while this file stays green unless it checks the string.
  function itemGridClasses(container) {
    const head = container.querySelector('.table-head');
    expect(head, 'no .table-head to read the grid classes from').toBeTruthy();
    return head.className;
  }

  it('floors every money column at min-content and lets only รายละเอียด be squeezed', () => {
    const { container } = render(<QuotationDocumentView quotation={quotation} />);
    const classes = itemGridClasses(container);

    // One `minmax(0,…)` — รายละเอียด, the wrapping column — and five `minmax(min-content,…)`.
    const shrinkable = classes.match(/minmax\(0,/g) ?? [];
    const floored = classes.match(/minmax\(min-content,/g) ?? [];
    expect(shrinkable, 'only รายละเอียด may shrink below its content').toHaveLength(1);
    expect(floored, 'the five numeric columns must each carry a min-content floor').toHaveLength(5);

    // Order matters: รายละเอียด is the FIRST track. A floored first column would stop the
    // description wrapping and put the squeeze back on the money.
    expect(classes).toContain('grid-cols-[minmax(0,3fr)_minmax(min-content,');
  });

  it('releases the shared 900px .reflow-cards floor in the 721-1040px band', () => {
    const { container } = render(<QuotationDocumentView quotation={quotation} />);
    const classes = itemGridClasses(container);

    expect(classes).toContain('tablet:min-w-0');
    // Still opted into the card reflow: below 721px the rows must become labelled cards, which
    // the mobile-card-reflow suite above is what actually proves.
    expect(classes).toContain('reflow-cards');
    // The head row and the data rows must share the grid, or they stop lining up.
    expect(container.querySelector('.data-row').className).toContain('tablet:min-w-0');
  });
});

describe('QuotationDocumentView ตำแหน่งติดตั้ง grouping (F1)', () => {
  it('prints the heading once per RUN of equal labels, not once per row', () => {
    const { container } = render(<QuotationDocumentView quotation={docQuotation({
      items: [docItem(1, 'ชั้น 1', 'A'), docItem(2, 'ชั้น 1', 'B'), docItem(3, 'ชั้น 2', 'C')],
    })} />);

    const headings = [...container.querySelectorAll('.text-2xs.font-bold.uppercase')]
      .map((el) => el.textContent)
      .filter((text) => text.startsWith('ชั้น'));
    expect(headings).toEqual(['ชั้น 1', 'ชั้น 2']);
  });

  // Two separate runs of the same label print TWICE — the heading marks where a run starts, and
  // deduping by value would silently drop the second one's heading from the customer's document.
  it('prints the label again when a second, non-adjacent run of it starts', () => {
    const { container } = render(<QuotationDocumentView quotation={docQuotation({
      items: [docItem(1, 'ชั้น 1', 'A'), docItem(2, 'ชั้น 2', 'B'), docItem(3, 'ชั้น 1', 'C')],
    })} />);

    const headings = [...container.querySelectorAll('.text-2xs.font-bold.uppercase')]
      .map((el) => el.textContent)
      .filter((text) => text.startsWith('ชั้น'));
    expect(headings).toEqual(['ชั้น 1', 'ชั้น 2', 'ชั้น 1']);
  });

  it('prints no heading at all for a blank label', () => {
    const { container } = render(<QuotationDocumentView quotation={docQuotation({
      items: [docItem(1, null, 'A'), docItem(2, '', 'B')],
    })} />);

    const headings = [...container.querySelectorAll('.text-2xs.font-bold.uppercase')]
      .map((el) => el.textContent);
    expect(headings.filter((t) => t.startsWith('ชั้น'))).toEqual([]);
  });
});

describe('QuotationDocumentView ผู้สั่งซื้อ signature slot (F2)', () => {
  it('prints the frozen contact name as the fourth signature slot', () => {
    const { getByText } = render(<QuotationDocumentView quotation={docQuotation()} />);
    expect(getByText('ผู้สั่งซื้อ')).not.toBeNull();
    expect(getByText('ณัฐพงศ์ ศรีวิไล')).not.toBeNull();
  });

  it('falls back to a dash rather than an empty slot when the snapshot is missing', () => {
    const { getByText, container } = render(<QuotationDocumentView quotation={docQuotation({ contactName: null })} />);
    expect(getByText('ผู้สั่งซื้อ')).not.toBeNull();
    expect(container.textContent).toContain('-');
  });
});
