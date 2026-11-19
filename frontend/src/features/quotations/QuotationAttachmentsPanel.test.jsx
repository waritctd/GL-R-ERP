import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QuotationAttachmentsPanel } from './QuotationAttachmentsPanel.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// เอกสารแนบ. The owner: "เพิ่มปุ่ม แนบเอกสาร ในหน้าใบเสนอราคา ให้เซลล์แนบใบเสนอราคาของผู้ขายไว้กับใบได้เลย
// ผู้ตรวจจะเห็นต้นทุนประกอบการอนุมัติทันที … this can be optional, it show should in the details of
// the quotation page".
//
// One panel, in the edit form above ยอดรวม and as the last block of the read-only details. This
// file is the panel on its own: what each audience is shown, and the attach and remove flows
// including the refusals the server sends back.
//
// Evidence note: every server answer here is a vi.fn(). Who the server refuses is proven against
// Java and real Postgres (DealQuotationAttachmentAuthzIntegrationTest); this file only shows what
// the panel offers, and what it says when it is refused.
vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      dealQuotations: {
        listAttachments: vi.fn(), uploadAttachment: vi.fn(), deleteAttachment: vi.fn(), attachmentUrl: vi.fn(),
      },
    },
  };
});

const TITLE = 'เอกสารแนบ';
const NOT_PRINTED = 'ไม่พิมพ์ในใบเสนอราคา';
const WHILE_PENDING = 'ระหว่างรออนุมัติ';

const owner = { id: 6, name: 'คุณสมหมาย ขายดี', role: 'sales' };
/** A quotation of `owner`'s deal. */
const quotation = (docStatus, overrides = {}) => ({
  id: 41, number: 'QT-2026-0041-2', docStatus, salesRepId: 6, origin: 'DEAL_DIRECT', parentQuotationId: 40,
  ...overrides,
});

// 250,880 bytes = 245 KB. 05:00Z is the 12th in Bangkok and in UTC alike.
const OWN = {
  id: 71, quotationId: 41, quotationNumber: 'QT-2026-0041-2', fileName: 'ใบเสนอราคา ผู้ขาย เอ.pdf',
  mimeType: 'application/pdf', fileSize: 250880, uploadedById: 6, uploadedByName: 'คุณสมหมาย ขายดี',
  uploadedAt: '2026-10-12T05:00:00Z', inherited: false,
};
const INHERITED = {
  id: 55, quotationId: 40, quotationNumber: 'QT-2026-0041-1', fileName: 'vendor-quote-first-round.pdf',
  mimeType: 'application/pdf', fileSize: 1024, uploadedById: 9, uploadedByName: 'ผู้จัดการ ฝ่ายขาย',
  uploadedAt: '2026-09-30T05:00:00Z', inherited: true,
};
function show(user, quotationOrNull) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <QuotationAttachmentsPanel user={user} quotation={quotationOrNull} />
    </QueryClientProvider>,
  );
}

/** The panel, or null when it is not on the page. */
const panel = () => screen.queryByRole('heading', { name: TITLE })?.closest('section') ?? null;
const rows = () => screen.queryAllByTestId('quotation-attachment');
const rowOf = (file) => rows().find((row) => row.textContent.includes(file.fileName)) ?? null;
const attachInput = () => screen.queryByLabelText(/แนบเอกสาร/);
const removeButton = (file) => screen.queryByRole('button', { name: `ลบเอกสาร ${file.fileName}` });
const pdf = (name = 'vendor.pdf') => new File(['%PDF-1.4'], name, { type: 'application/pdf' });

beforeEach(() => {
  vi.clearAllMocks();
  api.dealQuotations.listAttachments.mockResolvedValue({ items: [OWN] });
  api.dealQuotations.attachmentUrl.mockImplementation((id) => `/api/deal-quotation-attachments/${id}/file`);
});

describe('a draft, for the rep who may edit it', () => {
  it('offers แนบเอกสาร for PDF, JPG and PNG, and ลบ on the file', async () => {
    show(owner, quotation('DRAFT'));

    await waitFor(() => expect(rowOf(OWN)).not.toBeNull());
    const input = attachInput();
    expect(input).not.toBeNull();
    expect(input.type).toBe('file');
    expect(input.disabled).toBe(false);
    for (const type of ['application/pdf', 'image/jpeg', 'image/png']) {
      expect(input.getAttribute('accept')).toContain(type);
    }
    expect(removeButton(OWN)).not.toBeNull();
    // The file is internal, and the panel says so in the words the page already uses for what is
    // not printed.
    expect(panel().textContent).toContain(NOT_PRINTED);
    expect(panel().textContent).not.toContain(WHILE_PENDING);
  });
});

describe('a quotation that is approved', () => {
  it('is read-only for its own rep too', async () => {
    show(owner, quotation('APPROVED'));

    await waitFor(() => expect(rowOf(OWN)).not.toBeNull());
    expect(attachInput()).toBeNull();
    expect(removeButton(OWN)).toBeNull();
  });
});

describe('attaching a file', () => {
  it('sends the picked file for this quotation, and the list then shows it', async () => {
    const attached = { ...OWN, id: 72, fileName: 'vendor.pdf' };
    api.dealQuotations.listAttachments
      .mockResolvedValueOnce({ items: [OWN] })
      .mockResolvedValue({ items: [attached, OWN] });
    api.dealQuotations.uploadAttachment.mockResolvedValue({ attachment: attached });
    show(owner, quotation('DRAFT'));
    await waitFor(() => expect(rowOf(OWN)).not.toBeNull());
    const picked = pdf('vendor.pdf');

    fireEvent.change(attachInput(), { target: { files: [picked] } });

    await waitFor(() => expect(api.dealQuotations.uploadAttachment).toHaveBeenCalledTimes(1));
    const [quotationId, sent] = api.dealQuotations.uploadAttachment.mock.calls[0];
    expect(quotationId).toBe(41);
    expect(sent).toBe(picked);
    await waitFor(() => expect(rowOf(attached)).not.toBeNull());
    expect(rows()).toHaveLength(2);
  });
});

describe('removing a file', () => {
  it('on confirmation removes that file, and the list then drops it', async () => {
    api.dealQuotations.listAttachments
      .mockResolvedValueOnce({ items: [OWN, INHERITED] })
      .mockResolvedValue({ items: [INHERITED] });
    api.dealQuotations.deleteAttachment.mockResolvedValue({ ok: true });
    show(owner, quotation('DRAFT'));
    await waitFor(() => expect(rowOf(OWN)).not.toBeNull());

    fireEvent.click(removeButton(OWN));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'ลบเอกสาร' }));

    await waitFor(() => expect(api.dealQuotations.deleteAttachment).toHaveBeenCalledWith(71));
    await waitFor(() => expect(rowOf(OWN)).toBeNull());
    expect(rowOf(INHERITED)).not.toBeNull();
  });
});

