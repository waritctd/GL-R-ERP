import React from 'react';
import { onlineManager, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QuotationAttachmentsPanel } from './QuotationAttachmentsPanel.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// เอกสารแนบ. The owner: "เพิ่มปุ่ม แนบเอกสาร ในหน้าใบเสนอราคา ให้เซลล์แนบใบเสนอราคาของผู้ขายไว้กับใบได้เลย
// ผู้ตรวจจะเห็นต้นทุนประกอบการอนุมัติทันที … this can be optional, it show should in the details of
// the quotation page".
//
// One panel, the last block of the quotation page, for a draft being edited and for the read-only
// details alike. This file is the panel on its own: what each audience is shown, and the attach
// and remove flows including the refusals the server sends back.
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
const FULL = 'แนบครบ 10 ไฟล์แล้ว';
const WHILE_PENDING = 'ระหว่างรออนุมัติ';

const owner = { id: 6, name: 'คุณสมหมาย ขายดี', role: 'sales' };
const manager = { id: 9, name: 'ผู้จัดการ ฝ่ายขาย', role: 'sales_manager' };
const ceo = { id: 8, name: 'ราม', role: 'ceo' };
const account = { id: 5, name: 'คุณบัญชี รอบคอบ', role: 'account' };
const importer = { id: 7, name: 'คุณนำเข้า พานิช', role: 'import' };
const accountWithGrant = { id: 2, name: 'บัญชี ได้สิทธิ์', role: 'account', canCreateQuotation: true };

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
const own = (n) => ({ ...OWN, id: 100 + n, fileName: `vendor-${n}.pdf` });

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
/** The panel once its list has settled into rows (or into one of its sentences). */
async function settledPanel() {
  await waitFor(() => expect(panel()).not.toBeNull());
  return panel();
}
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

describe('who is shown the panel', () => {
  // Wrong way round, and the control comes FIRST: the very same quotation shows the CEO a panel
  // with a file in it. Only then is "account sees nothing" a statement about account.
  it.each([
    ['account', account], ['import', importer], ['account holding the quotation grant', accountWithGrant],
  ])('%s gets no panel at all, and the list is never requested for them', async (_who, excluded) => {
    const approver = show(ceo, quotation('PENDING_APPROVAL'));
    await waitFor(() => expect(rowOf(OWN)).not.toBeNull());
    approver.unmount();
    api.dealQuotations.listAttachments.mockClear();

    const { container } = show(excluded, quotation('PENDING_APPROVAL'));
    // Long enough for a request to have been made, had one been going to be.
    await new Promise((resolve) => { setTimeout(resolve, 50); });

    expect(container.textContent).toBe('');
    expect(panel()).toBeNull();
    expect(api.dealQuotations.listAttachments).not.toHaveBeenCalled();
  });
});

describe('a quotation that has never been saved', () => {
  it('says a file can be attached after the first บันทึกร่าง, and offers no picker', async () => {
    show(owner, null);

    const shown = await settledPanel();
    expect(shown.textContent).toContain('บันทึกร่าง');
    expect(shown.textContent).toContain(NOT_PRINTED);
    expect(attachInput()).toBeNull();
    expect(shown.querySelector('input[type="file"]')).toBeNull();
    expect(api.dealQuotations.listAttachments).not.toHaveBeenCalled();
  });
});

describe('a draft, for the rep who may edit it', () => {
  it('lists each file with its name, size, who attached it and when, as a link to the file', async () => {
    show(owner, quotation('DRAFT'));

    await waitFor(() => expect(rowOf(OWN)).not.toBeNull());
    const row = rowOf(OWN);
    expect(row.textContent).toContain('ใบเสนอราคา ผู้ขาย เอ.pdf');
    expect(row.textContent).toContain('245 KB');
    expect(row.textContent).toContain('คุณสมหมาย ขายดี');
    expect(row.textContent).toContain('12 ต.ค. 2569');
    const link = within(row).getByRole('link', { name: /ใบเสนอราคา ผู้ขาย เอ\.pdf/ });
    expect(link.getAttribute('href')).toBe('/api/deal-quotation-attachments/71/file');
    expect(api.dealQuotations.listAttachments).toHaveBeenCalledWith(41);
  });

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

  it('the sales manager, who may edit any deal, is offered the same', async () => {
    show(manager, quotation('DRAFT'));

    await waitFor(() => expect(rowOf(OWN)).not.toBeNull());
    expect(attachInput()).not.toBeNull();
    expect(removeButton(OWN)).not.toBeNull();
  });
});

describe('a quotation awaiting approval', () => {
  it('still lets its rep attach, takes ลบ away, and says so', async () => {
    show(owner, quotation('PENDING_APPROVAL'));

    await waitFor(() => expect(rowOf(OWN)).not.toBeNull());
    expect(attachInput()).not.toBeNull();
    expect(attachInput().disabled).toBe(false);
    expect(removeButton(OWN)).toBeNull();
    expect(panel().textContent).toContain(WHILE_PENDING);
    expect(panel().textContent).toContain('ลบไม่ได้');
  });

  it('shows the approver the files to open, and nothing to change', async () => {
    show(ceo, quotation('PENDING_APPROVAL'));

    await waitFor(() => expect(rowOf(OWN)).not.toBeNull());
    expect(within(rowOf(OWN)).getByRole('link', { name: /ใบเสนอราคา ผู้ขาย เอ\.pdf/ }).getAttribute('href'))
      .toBe('/api/deal-quotation-attachments/71/file');
    expect(attachInput()).toBeNull();
    expect(removeButton(OWN)).toBeNull();
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

describe('files inherited from the document a revision revises', () => {
  it('name the earlier document, and have no ลบ even on a draft its rep may edit', async () => {
    api.dealQuotations.listAttachments.mockResolvedValue({ items: [OWN, INHERITED] });
    show(owner, quotation('DRAFT'));

    await waitFor(() => expect(rowOf(INHERITED)).not.toBeNull());
    const inherited = rowOf(INHERITED);
    expect(inherited.textContent).toContain('QT-2026-0041-1');
    expect(inherited.textContent).toContain('ฉบับก่อนหน้า');
    expect(within(inherited).getByRole('link', { name: /vendor-quote-first-round\.pdf/ }).getAttribute('href'))
      .toBe('/api/deal-quotation-attachments/55/file');
    expect(removeButton(INHERITED)).toBeNull();
    // Control: the file stored on THIS quotation, in the same list, can be removed and names no earlier document.
    expect(removeButton(OWN)).not.toBeNull();
    expect(rowOf(OWN).textContent).not.toContain('ฉบับก่อนหน้า');
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

  it('shows the server\'s own words when the file is refused, and leaves the list as it was', async () => {
    api.dealQuotations.uploadAttachment.mockRejectedValue(new Error('รองรับเฉพาะไฟล์ PDF, JPG หรือ PNG'));
    show(owner, quotation('DRAFT'));
    await waitFor(() => expect(rowOf(OWN)).not.toBeNull());

    fireEvent.change(attachInput(), { target: { files: [pdf('notes.pdf')] } });

    await waitFor(() => expect(within(panel()).queryByRole('alert')).not.toBeNull());
    expect(within(panel()).getByRole('alert').textContent).toContain('รองรับเฉพาะไฟล์ PDF, JPG หรือ PNG');
    expect(rows()).toHaveLength(1);
    expect(rowOf(OWN)).not.toBeNull();
  });

  it('is closed at ten files of this quotation, and says so; inherited files are not counted', async () => {
    api.dealQuotations.listAttachments.mockResolvedValue({
      items: [...Array.from({ length: 10 }, (_, n) => own(n + 1)), INHERITED],
    });
    const full = show(owner, quotation('DRAFT'));
    await waitFor(() => expect(rows()).toHaveLength(11));
    expect(attachInput().disabled).toBe(true);
    expect(panel().textContent).toContain(FULL);
    full.unmount();

    // Control: nine of its own beside an inherited one is ten rows, and still open.
    api.dealQuotations.listAttachments.mockResolvedValue({
      items: [...Array.from({ length: 9 }, (_, n) => own(n + 1)), INHERITED],
    });
    show(owner, quotation('DRAFT'));
    await waitFor(() => expect(rows()).toHaveLength(10));
    expect(attachInput().disabled).toBe(false);
    expect(panel().textContent).not.toContain(FULL);
  });
});

describe('removing a file', () => {
  it('asks first, naming the file; cancelling removes nothing', async () => {
    show(owner, quotation('DRAFT'));
    await waitFor(() => expect(rowOf(OWN)).not.toBeNull());

    fireEvent.click(removeButton(OWN));

    const dialog = await screen.findByRole('dialog');
    expect(dialog.textContent).toContain('ใบเสนอราคา ผู้ขาย เอ.pdf');
    expect(api.dealQuotations.deleteAttachment).not.toHaveBeenCalled();
    fireEvent.click(within(dialog).getByRole('button', { name: 'ยกเลิก' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(api.dealQuotations.deleteAttachment).not.toHaveBeenCalled();
    expect(rowOf(OWN)).not.toBeNull();
  });

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

  it('shows the server\'s own words when the removal is refused, and keeps the file listed', async () => {
    api.dealQuotations.deleteAttachment.mockRejectedValue(
      new Error('ใบเสนอราคาไม่ได้อยู่ในสถานะร่างแล้ว จึงลบเอกสารแนบไม่ได้'),
    );
    show(owner, quotation('DRAFT'));
    await waitFor(() => expect(rowOf(OWN)).not.toBeNull());

    fireEvent.click(removeButton(OWN));
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'ลบเอกสาร' }));

    await waitFor(() => expect(within(panel()).queryByRole('alert')).not.toBeNull());
    expect(within(panel()).getByRole('alert').textContent).toContain('จึงลบเอกสารแนบไม่ได้');
    expect(rowOf(OWN)).not.toBeNull();
  });
});

describe('when there is nothing to list', () => {
  it('tells a rep who may attach that there is none yet, and still offers the picker', async () => {
    api.dealQuotations.listAttachments.mockResolvedValue({ items: [] });
    show(owner, quotation('DRAFT'));

    const shown = await settledPanel();
    await waitFor(() => expect(shown.textContent).toContain('ยังไม่มีเอกสารแนบ'));
    expect(attachInput()).not.toBeNull();
    expect(rows()).toHaveLength(0);
  });

  it('tells a reader there is none', async () => {
    api.dealQuotations.listAttachments.mockResolvedValue({ items: [] });
    show(ceo, quotation('PENDING_APPROVAL'));

    const shown = await settledPanel();
    await waitFor(() => expect(shown.textContent).toContain('ไม่มีเอกสารแนบ'));
    // "Not yet" is for someone who could add one.
    expect(shown.textContent).not.toContain('ยังไม่มีเอกสารแนบ');
    expect(attachInput()).toBeNull();
  });

  // The request can be HELD BACK before it settles: offline, or between retries while the tab is
  // hidden. That is not an answer. An approver told "ไม่มีเอกสารแนบ" then would decide without the
  // cost the rep attached.
  it('does not say there is none while the list request is held back', async () => {
    onlineManager.setOnline(false);
    try {
      show(ceo, quotation('PENDING_APPROVAL'));
      await new Promise((resolve) => { setTimeout(resolve, 50); });

      // Control: the request really is paused, not sent.
      expect(api.dealQuotations.listAttachments).not.toHaveBeenCalled();
      expect(panel().textContent).toContain('กำลังโหลดเอกสารแนบ');
      expect(panel().textContent).not.toContain('ไม่มีเอกสารแนบ');
    } finally {
      onlineManager.setOnline(true);
    }
  });

  it('says so when the list could not be loaded, with the server\'s reason', async () => {
    api.dealQuotations.listAttachments.mockRejectedValue(new Error('ไม่มีสิทธิ์เข้าถึงรายการนี้'));
    show(ceo, quotation('PENDING_APPROVAL'));

    const shown = await settledPanel();
    await waitFor(() => expect(shown.textContent).toContain('โหลดเอกสารแนบไม่สำเร็จ'));
    expect(shown.textContent).toContain('ไม่มีสิทธิ์เข้าถึงรายการนี้');
    expect(rows()).toHaveLength(0);
  });
});
