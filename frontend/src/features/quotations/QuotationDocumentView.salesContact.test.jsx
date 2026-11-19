import { render, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { QuotationDocumentView } from './QuotationDocumentView.jsx';

// ผู้ติดต่อ's Phone and Email on the quotation's details — first a sales record only, reversed by the
// owner on 2026-10-13: they print on the document's customer contact line when filled.
//
// QuotationDocumentView is the on-screen copy of a quotation that is no longer an editable draft:
// what an approver, the CEO, import and account read. It mirrors the PDF, so the header lines
// (`doc-contact`, `doc-customer-phone`) show the typed values in place of the customer-master
// ones, field by field, and this block lists the two typed values under their own words.

const PHONE = '089-765-4321 ต่อ 77';
const EMAIL = 'bid.contact@contractor-b.example';

function docQuotation(overrides = {}) {
  return {
    id: 1, docStatus: 'APPROVED', customerName: 'บริษัท ทดสอบ จำกัด', projectName: 'โครงการทดสอบ',
    salesRepName: 'คุณสมหมาย ขายดี', salesRepPhone: '081-000-0000', createdByName: 'คุณสมหมาย ขายดี',
    approvedByName: 'ผึ้ง', quotationDate: '2026-10-11',
    subtotalAmount: 1000, vatAmount: 70, grandTotal: 1070,
    depositPercent: 30, remainderMode: 'CREDIT', creditDays: 30, validityDays: 30, validityDate: null,
    customerNotes: null, items: [],
    contactName: 'ธนพล ศรีวัฒนกุล', contactPhone: '081-234-5678', contactEmail: 'thanaphon@example.co.th',
    orderedByName: 'คุณวิชัย มั่นคง',
    ...overrides,
  };
}

const view = (overrides) => render(<QuotationDocumentView quotation={docQuotation(overrides)} />);

describe('QuotationDocumentView — ผู้ติดต่อ\'s Phone and Email (printed when filled)', () => {

  // The panel reproduces what the paper prints (owner, 2026-10-13: the typed values, or nothing).
  // So the header lines above the block — ติดต่อผู้สั่งซื้อ and โทร. — show the typed phone and
  // email, and show no phone/email at all when none was typed: no fallback to the master ones.
  it('the header lines show the typed phone and email in place of the master ones', () => {
    const { getByTestId } = view({ orderedByPhone: PHONE, orderedByEmail: EMAIL, customerPhone: '02-000-0000' });

    expect(getByTestId('doc-customer-phone').textContent).toContain(PHONE);
    expect(getByTestId('doc-customer-phone').textContent).not.toContain('02-000-0000');
    const contact = getByTestId('doc-contact').textContent;
    expect(contact).toContain('ธนพล ศรีวัฒนกุล');
    expect(contact).toContain(PHONE);
    expect(contact).toContain(EMAIL);
    expect(contact).not.toContain('081-234-5678');
    expect(contact).not.toContain('thanaphon@example.co.th');
  });

  // Wrong way round: the typed contact belongs on the header line (as printed) but never in the
  // fourth signature slot, which is always a dash.
  it('reaches the header line but does not leak into the signature slot', () => {
    const { getByTestId, getByText } = view({ orderedByPhone: PHONE, orderedByEmail: EMAIL });
    expect(getByTestId('doc-sales-contact')).not.toBeNull(); // the control: there IS something to leak

    expect(getByTestId('doc-contact').textContent)
      .toBe(`ติดต่อผู้สั่งซื้อธนพล ศรีวัฒนกุล · โทร. ${PHONE} · ${EMAIL}`);
    const signatures = getByText('ผู้เกี่ยวข้อง').closest('section');
    expect(within(signatures).queryByText('คุณวิชัย มั่นคง')).toBeNull();
    expect(signatures.textContent).not.toContain('089-765-4321');
    expect(signatures.textContent).not.toContain(EMAIL);
    // …and the block itself is not part of the signature panel.
    expect(signatures.contains(getByTestId('doc-sales-contact'))).toBe(false);
  });
});
