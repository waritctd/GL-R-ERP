import { render, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { QuotationDocumentView } from './QuotationDocumentView.jsx';

// ผู้ติดต่อ's Phone and Email on the quotation's details — owner: "it should show in the details of
// the quotation but doesn't need to be render in the actual pdf".
//
// QuotationDocumentView is the on-screen copy of a quotation that is no longer an editable draft:
// what an approver, the CEO, import and account read. Everything else on it reproduces what the
// PDF prints, so this block has to say that it does NOT — a phone number shown on a page that
// otherwise mirrors the document would be read as part of the document.
//
// It is a second, separate thing from the `doc-contact` line above it ("ติดต่อผู้สั่งซื้อ"), which is
// the customer-master contact snapshot the document's header DOES print.

const PHONE = '089-765-4321 ต่อ 77';
const EMAIL = 'bid.contact@contractor-b.example';
const NOT_PRINTED = 'ไม่พิมพ์ในใบเสนอราคา';

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

describe('QuotationDocumentView — ผู้ติดต่อ\'s Phone and Email (sales record, not printed)', () => {
  it('shows both, each under its own word, and says they are not printed', () => {
    const { getByTestId } = view({ orderedByPhone: PHONE, orderedByEmail: EMAIL });

    const block = getByTestId('doc-sales-contact');
    expect(block.textContent).toContain('Phone');
    expect(block.textContent).toContain(PHONE);
    expect(block.textContent).toContain('Email');
    expect(block.textContent).toContain(EMAIL);
    expect(block.textContent).toContain(NOT_PRINTED);
  });

  it('shows only the one that was typed, with no bare label for the other', () => {
    const phoneOnly = view({ orderedByPhone: PHONE, orderedByEmail: null });
    expect(phoneOnly.getByTestId('doc-sales-contact').textContent).toContain(PHONE);
    expect(phoneOnly.getByTestId('doc-sales-contact').textContent).not.toContain('Email');
    phoneOnly.unmount();

    const emailOnly = view({ orderedByPhone: '   ', orderedByEmail: EMAIL });
    expect(emailOnly.getByTestId('doc-sales-contact').textContent).toContain(EMAIL);
    expect(emailOnly.getByTestId('doc-sales-contact').textContent).not.toContain('Phone');
  });

  // Wrong way round: with nothing typed there is no block at all — no heading, no "not printed"
  // note about nothing.
  it('is absent when neither was typed', () => {
    const typed = view({ orderedByPhone: PHONE, orderedByEmail: EMAIL });
    expect(typed.queryByTestId('doc-sales-contact')).not.toBeNull(); // the control
    typed.unmount();

    for (const empty of [{}, { orderedByPhone: null, orderedByEmail: null }, { orderedByPhone: '', orderedByEmail: '   ' }]) {
      const page = view(empty);
      expect(page.queryByTestId('doc-sales-contact'), JSON.stringify(empty)).toBeNull();
      expect(page.container.textContent).not.toContain(NOT_PRINTED);
      page.unmount();
    }
  });

  // Wrong way round: the two contacts stay two things. The printed header line is still exactly
  // the customer-master snapshot, and the fourth signature slot is still exactly the name.
  it('does not leak into the lines that reproduce the printed document', () => {
    const { getByTestId, getByText } = view({ orderedByPhone: PHONE, orderedByEmail: EMAIL });
    expect(getByTestId('doc-sales-contact')).not.toBeNull(); // the control: there IS something to leak

    expect(getByTestId('doc-contact').textContent)
      .toBe('ติดต่อผู้สั่งซื้อธนพล ศรีวัฒนกุล · โทร. 081-234-5678 · thanaphon@example.co.th');
    const signatures = getByText('ผู้เกี่ยวข้อง').closest('section');
    expect(within(signatures).getByText('คุณวิชัย มั่นคง')).not.toBeNull();
    expect(signatures.textContent).not.toContain('089-765-4321');
    expect(signatures.textContent).not.toContain(EMAIL);
    // …and the block itself is not part of the signature panel.
    expect(signatures.contains(getByTestId('doc-sales-contact'))).toBe(false);
  });
});
