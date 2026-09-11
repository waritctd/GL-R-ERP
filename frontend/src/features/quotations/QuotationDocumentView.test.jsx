import { render, within } from '@testing-library/react';
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

describe('QuotationDocumentView money-column floors (owner review V5, 2026-09-10)', () => {
  // ⚠️ WHAT THIS CAN AND CANNOT PROVE. jsdom does no grid layout, so it cannot observe either bug
  // pinned here — both were measured in a real browser and can only be RE-measured in one. This is
  // a TEXT guard on the class-string decisions, so a later edit cannot quietly drop them.
  //
  //   1. Every NUMERIC column carries a FIXED floor, so the grid can never shrink a baht figure
  //      below its own text. Measured: เป็นเงิน was cut at every table width under 850px AND on
  //      desktop at 1041–1150px where the sidebar returns.
  //   2. The floors are FIXED lengths, never `min-content`. The head row and each data row are
  //      SEPARATE grid containers, so `min-content` sizes each row from ITS OWN numbers and
  //      right-aligned currency then staggers row to row (measured at up to 23px of drift before
  //      this was corrected). Fixed floors give every row the identical track list.
  //   3. รายละเอียด keeps `minmax(0, …)` and stays the FIRST track — it wraps, so it is the one
  //      column that should absorb the squeeze.
  //   4. `tablet:min-w-0` releases `.reflow-cards`'s shared 900px floor for 721–1040px, which is
  //      safe only because of (1).
  function gridClasses(el, what) {
    expect(el, `no ${what} to read the grid classes from`).toBeTruthy();
    return el.className;
  }

  it('floors every money column at a fixed width and lets only รายละเอียด be squeezed', () => {
    const { container } = render(<QuotationDocumentView quotation={quotation} />);

    // Asserted on the head row AND a data row. They share one constant today, so this cannot
    // diverge — but splitting ITEM_GRID and flooring only one is exactly how the columns would
    // stop lining up, and a guard that only reads the head row would stay green through it.
    for (const [el, what] of [
      [container.querySelector('.table-head'), '.table-head'],
      [container.querySelector('.data-row'), '.data-row'],
    ]) {
      const classes = gridClasses(el, what);

      const shrinkable = classes.match(/minmax\(0,/g) ?? [];
      expect(shrinkable, `${what}: only รายละเอียด may shrink below its content`).toHaveLength(1);

      // `min-content` is the specific WRONG floor: it re-sizes per row and staggers the currency.
      expect(classes, `${what}: min-content floors stagger the columns row to row`)
        .not.toContain('min-content');

      // Five fixed rem floors, one per numeric column.
      const fixed = classes.match(/minmax\(\d+(?:\.\d+)?rem,/g) ?? [];
      expect(fixed, `${what}: each numeric column needs its own fixed floor`).toHaveLength(5);

      // The WHOLE track list, pinned literally — counting floors is not enough. Shrinking all
      // five to `minmax(1rem, …)`, or permuting them so เป็นเงิน gets ส่วนลด's 3.5rem, keeps the
      // counts above intact and the suite green while silently clipping เป็นเงิน again by 27px
      // and 49px respectively (both measured in a browser at 721px, with no overflow and no
      // scrollbar to show for it). The magnitudes ARE the fix, so the magnitudes are the guard.
      //
      // The five values are the measured min-content of each column's widest formattable value at
      // the app's 16px root, rounded up: 999,999 แผ่น 90px · ฿999,999.99 88px · 99.99% 54px ·
      // ฿999,999.99 88px · ฿999,999,999.99 123px — except เป็นเงิน, sized for the FALLBACK font
      // (130.5px) rather than Sarabun, see the source comment. Re-measure before changing any, and
      // keep รายละเอียด's `minmax(0,3fr)` FIRST — a floored first column would stop the
      // description wrapping and put the squeeze straight back on the money.
      expect(classes).toContain(
        'grid-cols-[minmax(0,3fr)_minmax(5.75rem,0.8fr)_minmax(5.75rem,0.9fr)'
        + '_minmax(3.5rem,0.6fr)_minmax(5.75rem,0.9fr)_minmax(8.25rem,1fr)]',
      );
    }
  });

  it('releases the shared 900px .reflow-cards floor in the 721-1040px band', () => {
    const { container } = render(<QuotationDocumentView quotation={quotation} />);

    for (const [el, what] of [
      [container.querySelector('.table-head'), '.table-head'],
      [container.querySelector('.data-row'), '.data-row'],
    ]) {
      const classes = gridClasses(el, what);
      expect(classes, `${what}: the shared 900px floor must be released`).toContain('tablet:min-w-0');
      // Still opted into the card reflow: below 721px these rows must become labelled cards,
      // which the mobile-card-reflow suite above is what actually proves.
      expect(classes).toContain('reflow-cards');
    }
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
    // Scoped to the signature panel: since 2026-09-11 the contact name ALSO heads the customer
    // block (ติดต่อผู้สั่งซื้อ), exactly as the printed header does, so an unscoped query is ambiguous.
    const signatures = within(getByText('ผู้เกี่ยวข้อง').closest('section'));
    expect(signatures.getByText('ผู้สั่งซื้อ')).not.toBeNull();
    expect(signatures.getByText('ณัฐพงศ์ ศรีวิไล')).not.toBeNull();
  });

  it('falls back to a dash rather than an empty slot when the snapshot is missing', () => {
    const { getByText, container } = render(<QuotationDocumentView quotation={docQuotation({ contactName: null })} />);
    expect(getByText('ผู้สั่งซื้อ')).not.toBeNull();
    expect(container.textContent).toContain('-');
  });
});

// ── Quotation v3 / v3b rows and the English form (owner feedback pass 3, 2026-09-11) ────────────
describe('QuotationDocumentView — PLAIN / ADJUSTMENT rows, like the printed form', () => {
  const v3 = {
    ...quotation,
    docStatus: 'APPROVED',
    priceMode: 'SPECIAL_SQM',
    items: [
      {
        id: 21, seq: 1, lineType: 'TILE', descriptionLine: 'Trilogy Ash', sizeLine: '60x60', calculationLine: '(calc)',
        specialPriceLine: '(ราคาพิเศษ 1,350 บาท/ตรม ราคารวมภาษีมูลค่าเพิ่ม)',
        quantity: 330, unit: 'แผ่น', piecesFinal: 330, unitPrice: 850, netUnitPrice: 453.84, lineAmount: 149767.2,
      },
      {
        id: 22, seq: 2, lineType: 'PLAIN', descriptionLine: 'Mapei Adhesive (20kg/Bag)', sizeLine: null, calculationLine: null,
        quantity: 85, unit: 'Bags', piecesFinal: 0, unitPrice: 350, discountPct: 0, netUnitPrice: 350, lineAmount: 29750,
      },
      {
        id: 23, seq: 3, lineType: 'ADJUSTMENT', descriptionLine: 'ส่วนลดพิเศษ 3% สำหรับการสั่งซื้อภายใน 31/07/2569',
        quantity: -1, unit: null, piecesFinal: 0, unitPrice: 38198.21, discountPct: null, netUnitPrice: 38198.21, lineAmount: -38198.21,
      },
    ],
  };

  function row(container, lineType) {
    return container.querySelector(`.data-row[data-line-type="${lineType}"]`);
  }
  function cell(rowEl, label) {
    return rowEl.querySelector(`[data-label="${label}"]`).textContent;
  }

  it('an ADJUSTMENT prints จำนวน -1, NO unit, an EMPTY ส่วนลด, positive ราคา/คงเหลือ and a NEGATIVE เป็นเงิน', () => {
    const { container } = render(<QuotationDocumentView quotation={v3} />);
    const adj = row(container, 'ADJUSTMENT');
    expect(cell(adj, 'จำนวน')).toBe('-1');
    expect(cell(adj, 'ส่วนลด')).toBe('');
    expect(cell(adj, 'ราคา/หน่วย')).toBe('฿38,198.21');
    expect(cell(adj, 'สุทธิ')).toBe('฿38,198.21');
    expect(cell(adj, 'เป็นเงิน')).toBe('-฿38,198.21');
  });

  it('a PLAIN row prints its own quantity and unit, and Net', () => {
    const { container } = render(<QuotationDocumentView quotation={v3} />);
    const plain = row(container, 'PLAIN');
    expect(cell(plain, 'จำนวน')).toBe('85 Bags');
    expect(cell(plain, 'ส่วนลด')).toBe('Net');
  });

  it('a SPECIAL_SQM tile prints พิเศษ and its ราคาพิเศษ sub-line as a <span> (the .data-row wrapper-clip rule)', () => {
    const { container } = render(<QuotationDocumentView quotation={v3} />);
    const tileRow = row(container, 'TILE');
    expect(cell(tileRow, 'ส่วนลด')).toBe('พิเศษ');
    const subLine = [...tileRow.querySelectorAll('span')].find((el) => el.textContent.startsWith('(ราคาพิเศษ'));
    expect(subLine.tagName).toBe('SPAN');
    expect(tileRow.querySelector('div')).toBeNull();
  });
});

describe('QuotationDocumentView — the English (USD) document', () => {
  const en = {
    ...quotation,
    documentLanguage: 'EN', currency: 'USD', priceMode: 'DIRECT_NET',
    subtotalAmount: 18900, vatAmount: 0, grandTotal: 18900,
    salesRepNameEn: 'Jennet Longsakul', createdByNameEn: null,
  };

  it('uses the F-SM-008 column headings, USD amounts, and NO VAT row', () => {
    const { container } = render(<QuotationDocumentView quotation={en} />);
    const head = container.querySelector('.table-head').textContent;
    expect(head).toContain('Description & Conditions');
    expect(head).toContain('Amount (USD)');
    expect(container.textContent).toContain('Grand Total (USD)');
    expect(container.textContent).toContain('$18,900.00');
    expect(container.textContent).not.toContain('ภาษีมูลค่าเพิ่ม');
    expect(container.textContent).not.toContain('฿');
  });

  it('prints English signatory names, falling back to the Thai one rather than an empty slot', () => {
    const { container } = render(<QuotationDocumentView quotation={en} />);
    expect(container.textContent).toContain('Quoted by');
    expect(container.textContent).toContain('Jennet Longsakul');
    // createdByNameEn is null → the Thai name, not a blank.
    expect(container.textContent).toContain('Printed by');
    expect(container.textContent).toContain('คุณสมหมาย ขายดี');
  });
});

describe('QuotationDocumentView — the header lines the document prints (owner, 2026-09-11)', () => {
  const filled = {
    ...quotation,
    customerAddress: '201 ซอยสุขุมวิท 63\nเขตวัฒนา กทม. 10110',
    customerTaxId: '0105551234567',
    customerPhone: '02-000-0000',
    contactName: 'ธนพล ศรีวัฒนกุล', contactPhone: '081-234-5678', contactEmail: 'thanaphon@example.co.th',
  };

  it('shows every filled field', () => {
    const { getByTestId } = render(<QuotationDocumentView quotation={filled} />);
    expect(getByTestId('doc-address').textContent).toContain('201 ซอยสุขุมวิท 63');
    expect(getByTestId('doc-tax-id').textContent).toContain('0105551234567');
    expect(getByTestId('doc-customer-phone').textContent).toContain('02-000-0000');
    expect(getByTestId('doc-contact').textContent).toBe('ติดต่อผู้สั่งซื้อธนพล ศรีวัฒนกุล · โทร. 081-234-5678 · thanaphon@example.co.th');
  });

  it('a missing field leaves NO bare label and NO dangling separator', () => {
    const { queryByTestId, getByTestId } = render(<QuotationDocumentView quotation={{
      ...filled, customerAddress: null, customerTaxId: '  ', customerPhone: '', contactPhone: null,
    }} />);
    expect(queryByTestId('doc-address')).toBeNull();
    expect(queryByTestId('doc-tax-id')).toBeNull();
    expect(queryByTestId('doc-customer-phone')).toBeNull();
    const contactLine = getByTestId('doc-contact').textContent;
    expect(contactLine).toBe('ติดต่อผู้สั่งซื้อธนพล ศรีวัฒนกุล · thanaphon@example.co.th');
    expect(contactLine).not.toMatch(/·\s*·|·\s*$|โทร\.\s*(·|$)/);
  });

  it('renders no ผู้สั่งซื้อ line at all when there is nothing to put in it', () => {
    const { queryByTestId } = render(<QuotationDocumentView quotation={{ ...quotation, contactName: null }} />);
    expect(queryByTestId('doc-contact')).toBeNull();
  });
});
