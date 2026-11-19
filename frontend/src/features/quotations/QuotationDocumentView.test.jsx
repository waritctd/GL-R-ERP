import { render, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { QuotationDocumentView } from './QuotationDocumentView.jsx';

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

describe('QuotationDocumentView -- ยืนราคา (V178, owner ruling 2026-09-14)', () => {
  function validityText(container) {
    const label = Array.from(container.querySelectorAll('span'))
      .find((el) => el.textContent === 'ยืนราคา');
    return label.nextElementSibling.textContent;
  }

  it('DATE mode shows the exact deadline, in the same Thai date format the view already uses', () => {
    const { container } = render(<QuotationDocumentView quotation={docQuotation({
      validityMode: 'DATE', validityUntil: '2026-12-31',
    })} />);
    expect(validityText(container)).toBe('ถึง 31 ธ.ค. 2569');
  });
});

// "ไม่รับมัดจำ": mirrors DealQuotationRenderAdapter#depositLine's "0% is ไม่รับมัดจำ, not a phantom
// deposit" rule.
describe('QuotationDocumentView -- มัดจำ / ไม่รับมัดจำ (V181, owner ruling 2026-09-16)', () => {
  function fieldText(container, label) {
    const span = Array.from(container.querySelectorAll('span'))
      .find((el) => el.textContent === label);
    return span.nextElementSibling.textContent;
  }

  it('an ordinary positive deposit is unchanged: มัดจำ N%, ส่วนที่เหลือ label', () => {
    const { container } = render(<QuotationDocumentView quotation={docQuotation({
      depositPercent: 30, remainderMode: 'CREDIT', creditDays: 30,
    })} />);
    expect(fieldText(container, 'มัดจำ')).toBe('30%');
    expect(fieldText(container, 'ส่วนที่เหลือ')).toBe('เครดิต 30 วัน');
  });

  it('depositPercent 0 with a chosen term shows "ไม่รับมัดจำ" and the term text', () => {
    const { container } = render(<QuotationDocumentView quotation={docQuotation({
      depositPercent: 0, remainderMode: null, creditDays: null, fullPaymentTerm: 'ON_DELIVERY',
    })} />);
    expect(fieldText(container, 'มัดจำ')).toBe('ไม่รับมัดจำ');
    expect(fieldText(container, 'เงื่อนไขการชำระเงิน'))
      .toBe('บริษัทขอรับเงินค่าสินค้า 100% เมื่อส่งมอบสินค้า');
  });
});

describe('QuotationDocumentView ผู้สั่งซื้อ signature slot -- owner-directed reversal of F2 (2026-09-26)', () => {
  // Owner 2026-11-06: the slot stays blank for the customer's pen even when a name is stored.
  it('leaves the ผู้สั่งซื้อ slot as a dash even when an orderedByName is stored', () => {
    const { getByText } = render(
      <QuotationDocumentView quotation={docQuotation({ orderedByName: 'คุณวิชัย มั่นคง' })} />,
    );
    const signatures = within(getByText('ผู้เกี่ยวข้อง').closest('section'));
    expect(signatures.getByText('ผู้สั่งซื้อ')).not.toBeNull();
    expect(signatures.queryByText('คุณวิชัย มั่นคง')).toBeNull();
    expect(signatures.getByText('-')).not.toBeNull();
  });
});

// ── Quotation v3 / v3b rows and the English form ──
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

  // Mixed price modes: on a MIXED document each tile row was priced in its own mode and its ส่วนลด
  // cell says so, exactly what DealQuotationRenderAdapter#discountLabel prints, so page and PDF
  // agree row by row. Wrong-way-round built in: read by the DOCUMENT's mode ("MIXED" is none of the
  // three) every row would fall through to the percent rule and the two พิเศษ rows would say Net.
  it('a MIXED document labels each tile row by ITS mode: 20%, พิเศษ, พิเศษ, Net — and prints the sub-line on the ราคาพิเศษ row only', () => {
    const tile = (id, seq, extra) => ({
      id, seq, lineType: 'TILE', descriptionLine: `Row ${seq}`, sizeLine: '60x120', calculationLine: '(calc)',
      specialPriceLine: null, quantity: 10, unit: 'แผ่น', piecesFinal: 10, unitPrice: 1210.25, ...extra,
    });
    const mixed = {
      ...quotation, docStatus: 'APPROVED', priceMode: 'MIXED',
      items: [
        tile(31, 1, { priceMode: 'NET', discountPct: 20, netUnitPrice: 968.2, lineAmount: 9682 }),
        tile(32, 2, {
          priceMode: 'SPECIAL_SQM', netUnitPrice: 531.16, lineAmount: 5311.6,
          specialPriceLine: '(ราคาพิเศษ 790 บาท/ตรม ราคารวมภาษีมูลค่าเพิ่ม)',
        }),
        tile(33, 3, { priceMode: 'DIRECT_NET', netUnitPrice: 531.16, lineAmount: 5311.6 }),
        tile(34, 4, { priceMode: 'DIRECT_NET', netUnitPrice: 1210.25, lineAmount: 12102.5 }),
      ],
    };
    const { container } = render(<QuotationDocumentView quotation={mixed} />);
    const rows = [...container.querySelectorAll('.data-row[data-line-type="TILE"]')];

    expect(rows.map((tileRow) => cell(tileRow, 'ส่วนลด'))).toEqual(['20%', 'พิเศษ', 'พิเศษ', 'Net']);
    const withSubLine = rows.map((tileRow) => [...tileRow.querySelectorAll('span')]
      .some((el) => el.textContent.startsWith('(ราคาพิเศษ')));
    expect(withSubLine).toEqual([false, true, false, false]);
    // The engine's own word for the document's setting never reaches the customer-facing view.
    expect(container.textContent).not.toMatch(/MIXED|SPECIAL_SQM|DIRECT_NET/);
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
});

