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
const PRINTED_WHEN_FILLED = 'พิมพ์ในใบเสนอราคาเมื่อกรอก';

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
  it('shows both, each under its own word, and says they print when filled', () => {
    const { getByTestId } = view({ orderedByPhone: PHONE, orderedByEmail: EMAIL });

    const block = getByTestId('doc-sales-contact');
    expect(block.textContent).toContain('Phone');
    expect(block.textContent).toContain(PHONE);
    expect(block.textContent).toContain('Email');
    expect(block.textContent).toContain(EMAIL);
    expect(block.textContent).toContain(PRINTED_WHEN_FILLED);
    expect(block.textContent).not.toContain('ไม่พิมพ์');
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

  it('a typed email beside a blank phone shows the email and no phone at all — not the master one', () => {
    const { getByTestId, queryByTestId } = view({ orderedByPhone: null, orderedByEmail: EMAIL, customerPhone: '02-000-0000' });

    expect(queryByTestId('doc-customer-phone')).toBeNull();
    const contact = getByTestId('doc-contact').textContent;
    expect(contact).toContain(EMAIL);
    expect(contact).not.toContain('081-234-5678');
    expect(contact).not.toContain('02-000-0000');
    expect(contact).not.toContain('thanaphon@example.co.th');
  });

  // The paper strips a label the rep typed into the phone ("โทร 02 314 354-2" — the shape 299 of
  // 4,320 customer-directory rows carry, which reps copy from) before printing "Phone 02 314 354-2",
  // and prints nothing for a label-only value. The screen mirrors the paper, so it does the same.
  it('strips a phone label the rep typed, the way the PDF does, and shows nothing for a label-only phone', () => {
    for (const typed of ['โทร 02 314 354-2', 'โทร. 02 314 354-2', 'Tel: 02 314 354-2', 'Tel. 02 314 354-2']) {
      const page = view({ orderedByPhone: typed, orderedByEmail: null });
      expect(page.getByTestId('doc-customer-phone').textContent, typed).toBe('โทร.02 314 354-2');
      expect(page.getByTestId('doc-contact').textContent, typed).toBe('ติดต่อผู้สั่งซื้อธนพล ศรีวัฒนกุล · โทร. 02 314 354-2');
      page.unmount();
    }
    for (const labelOnly of ['โทร.', 'Tel.', '   ']) {
      const page = view({ orderedByPhone: labelOnly, orderedByEmail: '  ' });
      expect(page.queryByTestId('doc-customer-phone'), labelOnly).toBeNull();
      expect(page.getByTestId('doc-contact').textContent, labelOnly).toBe('ติดต่อผู้สั่งซื้อธนพล ศรีวัฒนกุล');
      expect(page.queryByTestId('doc-sales-contact'), labelOnly).toBeNull();
      page.unmount();
    }
  });

  // Wrong way round: with nothing typed there is no block at all — no heading, no "printed when filled"
  // note about nothing.
  it('is absent when neither was typed', () => {
    const typed = view({ orderedByPhone: PHONE, orderedByEmail: EMAIL });
    expect(typed.queryByTestId('doc-sales-contact')).not.toBeNull(); // the control
    typed.unmount();

    for (const empty of [{}, { orderedByPhone: null, orderedByEmail: null }, { orderedByPhone: '', orderedByEmail: '   ' }]) {
      const page = view(empty);
      expect(page.queryByTestId('doc-sales-contact'), JSON.stringify(empty)).toBeNull();
      expect(page.container.textContent).not.toContain(PRINTED_WHEN_FILLED);
      page.unmount();
    }
  });

  // Wrong way round: the typed contact belongs on the header line (as printed) but never in the
  // fourth signature slot, which is still exactly the name.
  it('reaches the header line but does not leak into the signature slot', () => {
    const { getByTestId, getByText } = view({ orderedByPhone: PHONE, orderedByEmail: EMAIL });
    expect(getByTestId('doc-sales-contact')).not.toBeNull(); // the control: there IS something to leak

    expect(getByTestId('doc-contact').textContent)
      .toBe(`ติดต่อผู้สั่งซื้อธนพล ศรีวัฒนกุล · โทร. ${PHONE} · ${EMAIL}`);
    const signatures = getByText('ผู้เกี่ยวข้อง').closest('section');
    expect(within(signatures).getByText('คุณวิชัย มั่นคง')).not.toBeNull();
    expect(signatures.textContent).not.toContain('089-765-4321');
    expect(signatures.textContent).not.toContain(EMAIL);
    // …and the block itself is not part of the signature panel.
    expect(signatures.contains(getByTestId('doc-sales-contact'))).toBe(false);
  });
});
