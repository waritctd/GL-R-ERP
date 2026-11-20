import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RemainingInvoiceDialog } from './RemainingInvoiceDialog.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: {
        remainingInvoiceOptions: vi.fn(),
        // slice 6.1: the dialog reads the deal's served payment terms (same query as the deal page).
        get: vi.fn(),
      },
      storedRemainingInvoices: {
        listForTicket: vi.fn(),
        createDraft: vi.fn(),
        update: vi.fn(),
        issue: vi.fn(),
        revise: vi.fn(),
        deleteDraft: vi.fn(),
        download: vi.fn(),
        bundleSelection: vi.fn(),
        saveBundleSelection: vi.fn(),
      },
      attachments: { list: vi.fn(), upload: vi.fn() },
      depositNotices: {
        noteTemplates: vi.fn(),
        preparerOptions: vi.fn(),
      },
    },
  };
});

// Mirrors RemainingInvoiceOptionsDto's own shape — see backend/.../RemainingInvoiceOptionsDto.java.
function baseOptions(overrides = {}) {
  return {
    docNumber: 'GLRI69001',
    defaultIssueDate: '2026-09-01',
    defaultReference: 'QT-2026-0099',
    referenceOptions: [{ value: 'QT-2026-0099', label: 'QT-2026-0099' }],
    defaultDepositReference: 'AI2600145',
    depositReferenceOptions: [
      { value: 'AI2600145', label: 'AI2600145' },
      { value: 'GLRD69001', label: 'GLRD69001' },
      { value: '', label: '(ไม่ระบุ)' },
    ],
    noteTemplates: [
      { id: 1, text: 'หมายเหตุ 1', defaultSelected: true, sortOrder: 1 },
      { id: 2, text: 'หมายเหตุ 2', defaultSelected: false, sortOrder: 2 },
    ],
    itemCount: 1,
    itemsTotal: 37114.18,
    depositAmount: 18557.09,
    netAmount: 18557.09,
    vatAmount: 1299.0,
    totalPayable: 19856.09,
    ...overrides,
  };
}

// Mirrors RemainingInvoiceDocumentDto's own shape — see backend/.../RemainingInvoiceDocumentDto.java.
function docRow(overrides = {}) {
  return {
    id: 900, ticketId: 701, customerQuotationId: null, depositNoticeId: null,
    baseNumber: null, version: 1, docNumber: null, status: 'DRAFT', supersededById: null,
    reference: 'QT-2026-0099', depositReference: 'AI2600145', docDate: '2026-09-01',
    notes: ['หมายเหตุ 1'],
    customerName: 'ACME', customerTaxId: null, customerBranch: null, customerAddress: null, projectName: null,
    itemsTotal: 37114.18, depositDeduction: 18557.09, netAmount: 18557.09, vatAmount: 1299.0, grandTotal: 19856.09,
    createdById: 1, createdByName: 'Sales', preparerId: 1, preparerName: 'Sales', createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-01T00:00:00Z',
    issuedById: null, issuedByName: null, issuedAt: null,
    ...overrides,
  };
}

function renderDialog(props = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const onClose = vi.fn();
  render(
    <QueryClientProvider client={queryClient}>
      <RemainingInvoiceDialog ticketId={701} onClose={onClose} {...props} />
    </QueryClientProvider>,
  );
  return { onClose };
}

// ผู้จัดทำ options: the deal's owner, keyer and sales managers ({ id, name }).
const PREPARER_OPTIONS = [{ id: 1, name: 'Sales' }, { id: 2, name: 'Other Rep' }];
beforeEach(() => {
  api.depositNotices.preparerOptions.mockResolvedValue({ items: PREPARER_OPTIONS });
});

describe('RemainingInvoiceDialog — no live stored document (create flow)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [] });
  });

  it('creating a draft (defaults untouched) posts the SAME fields the preview showed', async () => {
    api.tickets.remainingInvoiceOptions.mockResolvedValue({ options: baseOptions() });
    api.storedRemainingInvoices.createDraft.mockResolvedValue({ remainingInvoice: docRow() });
    renderDialog();

    await screen.findByDisplayValue('QT-2026-0099');
    expect(screen.getByTestId('remaining-invoice-create-draft').disabled).toBe(false);
    fireEvent.click(screen.getByTestId('remaining-invoice-create-draft'));

    await waitFor(() => expect(api.storedRemainingInvoices.createDraft).toHaveBeenCalledTimes(1));
    expect(api.storedRemainingInvoices.createDraft).toHaveBeenCalledWith(701, {
      quotationId: undefined,
      reference: 'QT-2026-0099',
      depositReference: 'AI2600145',
      docDate: '2026-09-01',
      notes: ['หมายเหตุ 1'],
    });
  });

  it('a non-null blockingReason disables the create-draft button and shows the reason', async () => {
    api.tickets.remainingInvoiceOptions.mockResolvedValue({
      options: baseOptions({
        blockingReason: 'ยังไม่ได้ออกใบแจ้งยอดมัดจำจากใบเสนอราคา QT-2026-0099',
      }),
    });
    renderDialog();

    expect(await screen.findByTestId('remaining-invoice-blocking-reason')).not.toBeNull();
    expect(screen.getByText(/ยังไม่ได้ออกใบแจ้งยอดมัดจำจากใบเสนอราคา QT-2026-0099/)).not.toBeNull();
    expect(screen.getByTestId('remaining-invoice-create-draft').disabled).toBe(true);

    fireEvent.click(screen.getByTestId('remaining-invoice-create-draft'));
    expect(api.storedRemainingInvoices.createDraft).not.toHaveBeenCalled();
  });
});

describe('RemainingInvoiceDialog — a live DRAFT exists', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.depositNotices.noteTemplates.mockResolvedValue({
      templates: [
        { id: 1, text: 'หมายเหตุ 1', defaultSelected: true, sortOrder: 1 },
        { id: 2, text: 'หมายเหตุ 2', defaultSelected: false, sortOrder: 2 },
      ],
    });
    // DraftEditor also queries this endpoint, for the deposit-reference DATALIST only.
    api.tickets.remainingInvoiceOptions.mockResolvedValue({ options: baseOptions() });
  });

  it('บันทึก calls update with the edited fields', async () => {
    api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [docRow()] });
    api.storedRemainingInvoices.update.mockResolvedValue({ remainingInvoice: docRow() });
    renderDialog();

    const input = await screen.findByDisplayValue('QT-2026-0099');
    fireEvent.change(input, { target: { value: 'PO-EDIT-1' } });
    fireEvent.click(screen.getByTestId('remaining-invoice-save-draft'));

    await waitFor(() => expect(api.storedRemainingInvoices.update).toHaveBeenCalledWith(900,
      expect.objectContaining({ reference: 'PO-EDIT-1' })));
  });

  it('ออกใบแจ้งหนี้ calls issue', async () => {
    api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [docRow()] });
    api.storedRemainingInvoices.issue.mockResolvedValue({ remainingInvoice: docRow({ status: 'ISSUED', docNumber: 'GLR6900001-1' }) });
    renderDialog();

    await screen.findByTestId('remaining-invoice-draft-editor');
    fireEvent.click(screen.getByTestId('remaining-invoice-issue'));
    fireEvent.click(within(await screen.findByRole('dialog', { name: 'ยืนยันการออกเอกสาร' })).getByRole('button', { name: 'ออกใบแจ้งหนี้' }));

    await waitFor(() => expect(api.storedRemainingInvoices.issue).toHaveBeenCalledWith(900));
  });

  // W4-07 (owner ruling 2026-10-30): issuing mints a legal document number and cannot be undone,
  // so the click only opens a confirmation; update+issue run on confirm, exactly once.
  describe('W4-07 confirm before issuing', () => {
    function confirmDialog() {
      return screen.findByRole('dialog', { name: 'ยืนยันการออกเอกสาร' });
    }

    // The server re-snapshots money from live data on update + issue, so the confirm shows a FRESH
    // preview (api.tickets.remainingInvoiceOptions), never the draft's last saved snapshot.
    it('first issue: refetches the preview, shows ITS amounts (not the draft snapshot) once loaded, calls no issue api until confirmed', async () => {
      api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [docRow()] });
      api.storedRemainingInvoices.update.mockResolvedValue({ remainingInvoice: docRow() });
      api.storedRemainingInvoices.issue.mockResolvedValue({ remainingInvoice: docRow({ status: 'ISSUED', docNumber: 'GLR6900001-1' }) });
      let resolveFresh;
      api.tickets.remainingInvoiceOptions
        .mockResolvedValueOnce({ options: baseOptions() })
        .mockReturnValueOnce(new Promise((resolve) => { resolveFresh = resolve; }));
      renderDialog();

      await screen.findByTestId('remaining-invoice-draft-editor');
      await waitFor(() => expect(api.tickets.remainingInvoiceOptions).toHaveBeenCalledTimes(1));
      fireEvent.click(screen.getByTestId('remaining-invoice-issue'));

      const dialog = await confirmDialog();
      expect(api.tickets.remainingInvoiceOptions).toHaveBeenCalledTimes(2);
      expect(dialog.textContent).toContain('กำลังคำนวณยอด');
      expect(dialog.textContent).not.toContain('฿19,856.09');
      const confirm = within(dialog).getByRole('button', { name: 'ออกใบแจ้งหนี้' });
      expect(confirm.disabled).toBe(true);

      await act(async () => {
        resolveFresh({
          options: baseOptions({ itemsTotal: 40000, depositAmount: 20000, netAmount: 20000, vatAmount: 1400, totalPayable: 21400 }),
        });
      });
      await waitFor(() => expect(dialog.textContent).toContain('฿21,400.00'));
      const text = dialog.textContent;
      expect(text).toContain('QT-2026-0099');
      expect(text).toContain('ยอดสินค้า');
      expect(text).toContain('฿40,000.00');
      expect(text).toContain('หักมัดจำ');
      expect(text).toContain('฿20,000.00');
      expect(text).toContain('ภาษีมูลค่าเพิ่ม');
      expect(text).toContain('฿1,400.00');
      expect(text).toContain('ยอดชำระ');
      // The draft's stale saved snapshot is not what the server will issue.
      expect(text).not.toContain('฿19,856.09');
      expect(text).not.toContain('฿37,114.18');
      expect(text).toContain('ระบบจะออกเลขที่เอกสารใหม่เมื่อยืนยัน');
      expect(api.storedRemainingInvoices.update).not.toHaveBeenCalled();
      expect(api.storedRemainingInvoices.issue).not.toHaveBeenCalled();

      expect(confirm.disabled).toBe(false);
      fireEvent.click(confirm);
      fireEvent.click(confirm);

      await waitFor(() => expect(api.storedRemainingInvoices.issue).toHaveBeenCalledTimes(1));
      expect(api.storedRemainingInvoices.update).toHaveBeenCalledTimes(1);
      expect(api.storedRemainingInvoices.issue).toHaveBeenCalledWith(900);
    });

    // The server's issue would 409 on a blocking reason, so the confirm names it and cannot proceed.
    it('a blockingReason on the fresh preview is shown and keeps confirm disabled', async () => {
      api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [docRow()] });
      api.tickets.remainingInvoiceOptions.mockResolvedValue({
        options: baseOptions({ blockingReason: 'ยังไม่มีใบแจ้งยอดเงินรับมัดจำ' }),
      });
      renderDialog();

      await screen.findByTestId('remaining-invoice-draft-editor');
      fireEvent.click(screen.getByTestId('remaining-invoice-issue'));

      const dialog = await confirmDialog();
      await waitFor(() => expect(dialog.textContent).toContain('ยังไม่มีใบแจ้งยอดเงินรับมัดจำ'));
      expect(dialog.textContent).not.toContain('฿19,856.09');
      expect(within(dialog).getByRole('button', { name: 'ออกใบแจ้งหนี้' }).disabled).toBe(true);
    });

    it('revision: shows the predicted next number from the ISSUED row of the same quotation', async () => {
      const issuedRow = docRow({
        id: 880, status: 'ISSUED', docNumber: 'GLR6900004-1', baseNumber: 'GLR6900004', version: 1, customerQuotationId: 55,
      });
      const revisionDraft = docRow({ id: 881, status: 'DRAFT', docNumber: null, baseNumber: null, customerQuotationId: 55 });
      api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [issuedRow, revisionDraft] });
      renderDialog();

      await screen.findByTestId('remaining-invoice-draft-editor');
      fireEvent.click(screen.getByTestId('remaining-invoice-issue'));

      const dialog = await confirmDialog();
      expect(dialog.textContent).toContain('GLR6900004-2');
      expect(dialog.textContent).not.toContain('ระบบจะออกเลขที่เอกสารใหม่เมื่อยืนยัน');
      expect(api.storedRemainingInvoices.issue).not.toHaveBeenCalled();
    });

    it('cancel closes the confirm and calls neither update nor issue', async () => {
      api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [docRow()] });
      renderDialog();

      await screen.findByTestId('remaining-invoice-draft-editor');
      fireEvent.click(screen.getByTestId('remaining-invoice-issue'));
      fireEvent.click(within(await confirmDialog()).getByRole('button', { name: 'ยกเลิก' }));

      await waitFor(() => expect(screen.queryByRole('dialog', { name: 'ยืนยันการออกเอกสาร' })).toBeNull());
      expect(api.storedRemainingInvoices.update).not.toHaveBeenCalled();
      expect(api.storedRemainingInvoices.issue).not.toHaveBeenCalled();
    });
  });

  // editing a field then clicking "ออกใบแจ้งหนี้" directly must not silently discard the edit — issue on the
  // backend freezes whatever was LAST SAVED, and only re-snapshots the COMPUTED content, never the dialog
  // fields. The dialog itself must therefore save the pending edit before issuing.
  it('ออกใบแจ้งหนี้ saves a pending (unsaved) edit before issuing, in that order', async () => {
    api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [docRow()] });
    api.storedRemainingInvoices.update.mockResolvedValue({ remainingInvoice: docRow({ reference: 'PO-NEVER-SAVED' }) });
    api.storedRemainingInvoices.issue.mockResolvedValue({
      remainingInvoice: docRow({ status: 'ISSUED', docNumber: 'GLR6900001-1', reference: 'PO-NEVER-SAVED' }),
    });
    renderDialog();

    const input = await screen.findByDisplayValue('QT-2026-0099');
    fireEvent.change(input, { target: { value: 'PO-NEVER-SAVED' } });
    // "บันทึก" is deliberately NEVER clicked here — going straight to issue.
    fireEvent.click(screen.getByTestId('remaining-invoice-issue'));
    fireEvent.click(within(await screen.findByRole('dialog', { name: 'ยืนยันการออกเอกสาร' })).getByRole('button', { name: 'ออกใบแจ้งหนี้' }));

    await waitFor(() => expect(api.storedRemainingInvoices.issue).toHaveBeenCalledWith(900));
    expect(api.storedRemainingInvoices.update).toHaveBeenCalledWith(900,
      expect.objectContaining({ reference: 'PO-NEVER-SAVED' }));

    // Order matters: the save must land BEFORE the issue that freezes the row.
    const updateOrder = api.storedRemainingInvoices.update.mock.invocationCallOrder[0];
    const issueOrder = api.storedRemainingInvoices.issue.mock.invocationCallOrder[0];
    expect(updateOrder).toBeLessThan(issueOrder);
  });
});

// Drawing m-remaining (2026-11-16): the summary line and footer are rebuilt; every request is unchanged. These
// figures all differ (itemsTotal ≠ deposit ≠ net), so showing one field in another's place fails.
describe('RemainingInvoiceDialog — drawing m-remaining', () => {
  const money = { itemsTotal: 912300.45, depositAmount: 300100.15, netAmount: 612200.3, vatAmount: 42854.02, totalPayable: 655054.32 };

  beforeEach(() => {
    vi.clearAllMocks();
    api.depositNotices.noteTemplates.mockResolvedValue({
      templates: [{ id: 1, text: 'หมายเหตุ 1', defaultSelected: true, sortOrder: 1 }, { id: 2, text: 'หมายเหตุ 2', defaultSelected: false, sortOrder: 2 }],
    });
    api.tickets.remainingInvoiceOptions.mockResolvedValue({ options: baseOptions(money) });
  });

  it('create: the summary line is the preview\'s netAmount = itemsTotal − depositAmount', async () => {
    api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [] });
    renderDialog();
    await screen.findByDisplayValue('QT-2026-0099');
    expect(screen.getByTestId('remaining-invoice-summary').textContent)
      .toBe('ส่วนที่เหลือ ฿612,200.30 (ก่อน VAT) = มูลค่า ฿912,300.45 − มัดจำ ฿300,100.15');
  });
});

// write actions (create/save/issue/revise/delete) hidden unless the viewer is the deal's OWNING sales rep —
// mirrors RemainingInvoiceService#requireDepositNoticeIssueGate exactly (no CEO carve-out for THIS document
// either — see that class's own Javadoc).
describe('RemainingInvoiceDialog — a live ISSUED document exists', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('an ISSUED document offers an edit control: it opens the form, and บันทึก updates the SAME document in place', async () => {
    const issuedRow = docRow({ status: 'ISSUED', docNumber: 'GLR6900001-1', baseNumber: 'GLR6900001' });
    api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [issuedRow] });
    api.storedRemainingInvoices.update.mockResolvedValue({ remainingInvoice: issuedRow });
    api.storedRemainingInvoices.issue.mockResolvedValue({ remainingInvoice: issuedRow });
    api.tickets.remainingInvoiceOptions.mockResolvedValue({ options: baseOptions() });
    api.depositNotices.noteTemplates.mockResolvedValue({ templates: [] });
    renderDialog();

    fireEvent.click(await screen.findByTestId('remaining-invoice-edit'));
    const input = await screen.findByDisplayValue('QT-2026-0099');
    fireEvent.change(input, { target: { value: 'PO-ISSUED-EDIT' } });
    // Still an ISSUED document: no second issue action while editing it, and its download is not a draft preview.
    expect(screen.queryByTestId('remaining-invoice-issue')).toBeNull();
    expect(screen.getByTestId('remaining-invoice-download-draft').textContent).toBe('ดาวน์โหลด');
    fireEvent.click(screen.getByTestId('remaining-invoice-save-draft'));

    await waitFor(() => expect(api.storedRemainingInvoices.update).toHaveBeenCalledWith(900,
      expect.objectContaining({ reference: 'PO-ISSUED-EDIT' })));
    expect(api.storedRemainingInvoices.issue).not.toHaveBeenCalled();
  });
});

describe('RemainingInvoiceDialog — canWrite=false (not this deal\'s owning sales rep)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.depositNotices.noteTemplates.mockResolvedValue({ templates: [] });
  });

  it('shows a clear waiting state instead of the create form when nothing is issued yet', async () => {
    api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [] });
    renderDialog({ canWrite: false });

    expect(await screen.findByTestId('remaining-invoice-waiting-for-sales')).not.toBeNull();
    expect(screen.queryByTestId('remaining-invoice-create-draft')).toBeNull(); // no create-draft button
    expect(api.tickets.remainingInvoiceOptions).not.toHaveBeenCalled(); // never even previews
  });

  it('shows the read-only issued summary with a download sheet, WITHOUT the revise button', async () => {
    const issuedRow = docRow({ status: 'ISSUED', docNumber: 'GLR6900001-1', baseNumber: 'GLR6900001' });
    api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [issuedRow] });
    api.storedRemainingInvoices.download.mockResolvedValue(new Blob(['x']));
    api.storedRemainingInvoices.saveBundleSelection.mockResolvedValue({ ok: true });
    // the server's defaults: the quotation and every PO; other files unticked
    api.storedRemainingInvoices.bundleSelection.mockResolvedValue({ quotation: true, attachmentIds: [5] });
    api.attachments.list.mockResolvedValue({ attachments: [
      { id: 5, fileName: 'po.pdf', attachType: 'PO' }, { id: 6, fileName: 'extra.pdf', attachType: 'OTHER' },
    ] });
    renderDialog({ canWrite: false });

    expect(await screen.findByTestId('remaining-invoice-issued-summary')).not.toBeNull();
    expect(screen.queryByTestId('remaining-invoice-revise')).toBeNull();

    fireEvent.click(screen.getByTestId('remaining-invoice-download'));
    const confirm = await screen.findByTestId('document-download-confirm');
    await waitFor(() => expect(screen.getByTestId('include-attachment-5').checked).toBe(true));
    expect(screen.getByTestId('include-quotation').checked).toBe(true);
    expect(screen.getByTestId('include-attachment-6').checked).toBe(false);

    fireEvent.click(screen.getByTestId('include-quotation'));
    fireEvent.click(screen.getByTestId('include-attachment-6'));
    fireEvent.click(confirm);
    await waitFor(() => expect(api.storedRemainingInvoices.download).toHaveBeenCalledWith(issuedRow.id));
    expect(api.storedRemainingInvoices.saveBundleSelection)
      .toHaveBeenCalledWith(issuedRow.id, { quotation: false, attachmentIds: [5, 6] });
  });

  it('hides the edit control from a viewer who cannot write', async () => {
    api.storedRemainingInvoices.listForTicket.mockResolvedValue({
      remainingInvoices: [docRow({ status: 'ISSUED', docNumber: 'GLR6900001-1', baseNumber: 'GLR6900001' })],
    });
    renderDialog({ canWrite: false });

    await screen.findByTestId('remaining-invoice-issued-summary');
    expect(screen.queryByTestId('remaining-invoice-edit')).toBeNull();
  });

  it('a SUPERSEDED version stays read-only: no edit control in the history list or beside it', async () => {
    const superseded = docRow({ id: 899, status: 'SUPERSEDED', docNumber: 'GLR6900001-1', version: 1, supersededById: 900 });
    const issuedRow = docRow({ id: 900, status: 'ISSUED', docNumber: 'GLR6900001-2', version: 2, baseNumber: 'GLR6900001' });
    api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [superseded, issuedRow] });
    renderDialog();

    const history = await screen.findByTestId('remaining-invoice-history');
    expect(within(history).queryByTestId('remaining-invoice-edit')).toBeNull();
    expect(within(history).queryByRole('button', { name: 'แก้ไขฉบับปัจจุบัน' })).toBeNull();
    // Exactly one edit control on the page: the live ISSUED document's.
    expect(screen.getAllByTestId('remaining-invoice-edit')).toHaveLength(1);
    // The two actions are explained under the buttons: edit keeps the number, a new version replaces it.
    const help = screen.getByTestId('remaining-invoice-action-help');
    expect(help.textContent).toContain('แก้ไขฉบับปัจจุบัน');
    expect(help.textContent).toContain('ใช้เลขที่เดิม');
    expect(help.textContent).toContain('ออกฉบับใหม่');
    expect(help.textContent).toContain('จะถูกแทนที่');
  });
});
