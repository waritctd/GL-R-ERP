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
      },
      storedRemainingInvoices: {
        listForTicket: vi.fn(),
        createDraft: vi.fn(),
        update: vi.fn(),
        issue: vi.fn(),
        revise: vi.fn(),
        deleteDraft: vi.fn(),
        download: vi.fn(),
      },
      depositNotices: {
        noteTemplates: vi.fn(),
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
    createdById: 1, createdByName: 'Sales', createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-01T00:00:00Z',
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

// This project does not wire up jest-dom's matchers (see LoginPage.test.jsx's own note) —
// assertions below read plain DOM properties (.value/.checked/.disabled) rather than
// toHaveValue/toBeChecked/toBeDisabled.

describe('RemainingInvoiceDialog — no live stored document (create flow)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [] });
  });

  it('prefills every field from the backend defaults once options load', async () => {
    api.tickets.remainingInvoiceOptions.mockResolvedValue({ options: baseOptions() });
    renderDialog();

    const referenceInput = await screen.findByDisplayValue('QT-2026-0099');
    expect(referenceInput).not.toBeNull();
    expect(screen.getByLabelText(/วันที่/).value).toBe('2026-09-01');
    expect(screen.getByLabelText(/เลขอ้างอิงมัดจำ/).value).toBe('AI2600145');
    expect(screen.getByText('หมายเหตุ 1').closest('label').querySelector('input').checked).toBe(true);
    expect(screen.getByText('หมายเหตุ 2').closest('label').querySelector('input').checked).toBe(false);
    expect(screen.getByTestId('remaining-invoice-preview').textContent).toContain('19,856.09');
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

  it('clearing the reference field sends an explicit empty string, not the default', async () => {
    api.tickets.remainingInvoiceOptions.mockResolvedValue({ options: baseOptions() });
    api.storedRemainingInvoices.createDraft.mockResolvedValue({ remainingInvoice: docRow() });
    renderDialog();

    await screen.findByDisplayValue('QT-2026-0099');
    fireEvent.click(screen.getByRole('button', { name: 'ล้างอ้างอิง' }));
    fireEvent.click(screen.getByTestId('remaining-invoice-create-draft'));

    await waitFor(() => expect(api.storedRemainingInvoices.createDraft).toHaveBeenCalledTimes(1));
    expect(api.storedRemainingInvoices.createDraft).toHaveBeenCalledWith(701, expect.objectContaining({
      reference: '',
    }));
  });

  it('typing a free-text customer PO number sends it', async () => {
    api.tickets.remainingInvoiceOptions.mockResolvedValue({ options: baseOptions() });
    api.storedRemainingInvoices.createDraft.mockResolvedValue({ remainingInvoice: docRow() });
    renderDialog();

    const input = await screen.findByDisplayValue('QT-2026-0099');
    fireEvent.change(input, { target: { value: 'PO-CUSTOMER-9999' } });
    fireEvent.click(screen.getByTestId('remaining-invoice-create-draft'));

    await waitFor(() => expect(api.storedRemainingInvoices.createDraft).toHaveBeenCalledTimes(1));
    expect(api.storedRemainingInvoices.createDraft).toHaveBeenCalledWith(701, expect.objectContaining({
      reference: 'PO-CUSTOMER-9999',
    }));
  });

  it('a large item count never blocks creating a draft (the sheet flows onto extra rows/pages)', async () => {
    api.tickets.remainingInvoiceOptions.mockResolvedValue({
      options: baseOptions({ itemCount: 120 }),
    });
    api.storedRemainingInvoices.createDraft.mockResolvedValue({ remainingInvoice: { id: 7, status: 'DRAFT', items: [] } });
    renderDialog();

    await screen.findByDisplayValue('QT-2026-0099');
    expect(screen.queryByText(/เกินความจุ/)).toBeNull();
    expect(screen.getByTestId('remaining-invoice-create-draft').disabled).toBe(false);

    fireEvent.click(screen.getByTestId('remaining-invoice-create-draft'));
    await waitFor(() => expect(api.storedRemainingInvoices.createDraft).toHaveBeenCalledTimes(1));
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

  it('does not show the quotation picker when only one (or zero) quotations qualify', async () => {
    api.tickets.remainingInvoiceOptions.mockResolvedValue({
      options: baseOptions({ quotationOptions: [], defaultQuotationId: null }),
    });
    renderDialog();

    await screen.findByDisplayValue('QT-2026-0099');
    expect(screen.queryByLabelText(/ใบเสนอราคา \(มีมากกว่า 1 ฉบับ/)).toBeNull();
  });

  it('shows the quotation picker once 2+ quotations qualify, and re-fetches options on selection', async () => {
    const quotationOptions = [
      { value: 501, label: 'QT-2026-0501 (ผู้ออกแบบ)' },
      { value: 502, label: 'QT-2026-0502 (ผู้ซื้อ)' },
    ];
    api.tickets.remainingInvoiceOptions.mockResolvedValue({
      options: baseOptions({ quotationOptions, defaultQuotationId: 502 }),
    });
    renderDialog();

    const select = await screen.findByLabelText(/ใบเสนอราคา \(มีมากกว่า 1 ฉบับ/);
    expect(select).not.toBeNull();
    expect(within(select.closest('label')).getByText('QT-2026-0501 (ผู้ออกแบบ)')).not.toBeNull();
    expect(within(select.closest('label')).getByText('QT-2026-0502 (ผู้ซื้อ)')).not.toBeNull();

    api.tickets.remainingInvoiceOptions.mockClear();
    fireEvent.change(select, { target: { value: '501' } });

    await waitFor(() => expect(api.tickets.remainingInvoiceOptions).toHaveBeenCalledWith(701, 501));
  });

  it('prefill never leaks between quotations when switching A -> B -> A', async () => {
    const quotationOptions = [
      { value: 501, label: 'QT-2026-0501 (ผู้ออกแบบ)' },
      { value: 502, label: 'QT-2026-0502 (ผู้ซื้อ)' },
    ];
    const shapeFor = (quotationId) => (quotationId === 501
      ? baseOptions({
        quotationOptions, defaultQuotationId: 502,
        defaultReference: 'QT-2026-0501',
        referenceOptions: [{ value: 'QT-2026-0501', label: 'QT-2026-0501' }],
        defaultDepositReference: 'AI-DESIGNER-1',
        depositReferenceOptions: [
          { value: 'AI-DESIGNER-1', label: 'AI-DESIGNER-1' },
          { value: '', label: '(ไม่ระบุ)' },
        ],
      })
      : baseOptions({
        quotationOptions, defaultQuotationId: 502,
        defaultReference: 'QT-2026-0502',
        referenceOptions: [{ value: 'QT-2026-0502', label: 'QT-2026-0502' }],
        defaultDepositReference: 'AI-BUYER-2',
        depositReferenceOptions: [
          { value: 'AI-BUYER-2', label: 'AI-BUYER-2' },
          { value: '', label: '(ไม่ระบุ)' },
        ],
      }));
    api.tickets.remainingInvoiceOptions.mockImplementation((ticketId, quotationId) => (
      Promise.resolve({ options: shapeFor(quotationId) })
    ));
    renderDialog();

    await screen.findByDisplayValue('QT-2026-0502');
    expect(screen.getByLabelText(/เลขอ้างอิงมัดจำ/).value).toBe('AI-BUYER-2');

    const quotationSelect = () => screen.getByLabelText(/ใบเสนอราคา \(มีมากกว่า 1 ฉบับ/);
    await screen.findByLabelText(/ใบเสนอราคา \(มีมากกว่า 1 ฉบับ/);

    fireEvent.change(quotationSelect(), { target: { value: '501' } });
    await screen.findByDisplayValue('QT-2026-0501');
    expect(screen.getByLabelText(/เลขอ้างอิงมัดจำ/).value).toBe('AI-DESIGNER-1');

    fireEvent.change(quotationSelect(), { target: { value: '502' } });
    await screen.findByDisplayValue('QT-2026-0502');
    expect(screen.getByLabelText(/เลขอ้างอิงมัดจำ/).value).toBe('AI-BUYER-2');

    fireEvent.change(quotationSelect(), { target: { value: '501' } });
    await screen.findByDisplayValue('QT-2026-0501');
    expect(screen.getByLabelText(/เลขอ้างอิงมัดจำ/).value).toBe('AI-DESIGNER-1');
  });

  it('shows a loading state while the stored-document list is in flight, and an error state if it fails', async () => {
    let reject;
    api.storedRemainingInvoices.listForTicket.mockReturnValue(new Promise((_, r) => { reject = r; }));
    renderDialog();

    expect(screen.queryByTestId('remaining-invoice-create-draft')).toBeNull();
    reject(new Error('ไม่มีสิทธิ์เข้าถึงรายการนี้'));
    expect(await screen.findByText('ไม่มีสิทธิ์เข้าถึงรายการนี้')).not.toBeNull();
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
    // P7 (GLA-99 step 2 review-round-2): DraftEditor now queries this endpoint too, for the
    // deposit-reference DATALIST only — see that test below for the "never overwrites the stored
    // value" invariant this default keeps out of every other test's way.
    api.tickets.remainingInvoiceOptions.mockResolvedValue({ options: baseOptions() });
  });

  it('prefills every FIELD VALUE from the stored draft row, never from the stateless preview', async () => {
    api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [docRow()] });
    // A deliberately DIFFERENT preview shape — if the editor's field values ever leaked from this
    // response instead of the stored draft, this test would show it immediately.
    api.tickets.remainingInvoiceOptions.mockResolvedValue({
      options: baseOptions({ defaultReference: 'SHOULD-NOT-APPEAR', defaultDepositReference: 'SHOULD-NOT-APPEAR-EITHER' }),
    });
    renderDialog();

    expect(await screen.findByTestId('remaining-invoice-draft-editor')).not.toBeNull();
    expect(screen.getByDisplayValue('QT-2026-0099')).not.toBeNull();
    expect(screen.getByLabelText(/เลขอ้างอิงมัดจำ/).value).toBe('AI2600145');
    expect(screen.queryByDisplayValue('SHOULD-NOT-APPEAR')).toBeNull();
    expect(screen.queryByDisplayValue('SHOULD-NOT-APPEAR-EITHER')).toBeNull();
  });

  // P7 (Opus review, GLA-99 step 2 review-round-2): the deposit-reference suggestion list review-
  // round-1 had dropped as "not cheap" is restored — a NON-BLOCKING query feeding a <datalist>
  // only, same pattern as the reference field's own `list=` attribute. This pins BOTH halves: the
  // endpoint IS called now (for the datalist), and it still never overwrites the field's own value.
  it('P7: offers a datalist of matched deposit-reference suggestions without ever overwriting the stored value', async () => {
    api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [docRow()] });
    api.tickets.remainingInvoiceOptions.mockResolvedValue({
      options: baseOptions({
        depositReferenceOptions: [
          { value: 'AI2600145', label: 'AI2600145' },
          { value: 'GLRD69001', label: 'GLRD69001' },
        ],
      }),
    });
    renderDialog();

    await screen.findByTestId('remaining-invoice-draft-editor');
    await waitFor(() => expect(api.tickets.remainingInvoiceOptions).toHaveBeenCalledWith(701, null));

    const input = screen.getByLabelText(/เลขอ้างอิงมัดจำ/);
    // Stored value, unchanged by the fetched suggestion list.
    expect(input.value).toBe('AI2600145');
    const datalist = document.getElementById(input.getAttribute('list'));
    expect(within(datalist).getByText('GLRD69001')).not.toBeNull();
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

    // The server recomputes anyway, so a failed preview does not trap the user — but it must not show stale amounts.
    it('a failed preview refetch shows an error line instead of amounts and leaves confirm enabled', async () => {
      api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [docRow()] });
      api.tickets.remainingInvoiceOptions
        .mockResolvedValueOnce({ options: baseOptions() })
        .mockRejectedValueOnce(new Error('boom'));
      renderDialog();

      await screen.findByTestId('remaining-invoice-draft-editor');
      await waitFor(() => expect(api.tickets.remainingInvoiceOptions).toHaveBeenCalledTimes(1));
      fireEvent.click(screen.getByTestId('remaining-invoice-issue'));

      const dialog = await confirmDialog();
      await waitFor(() => expect(dialog.textContent).toContain('คำนวณยอดไม่สำเร็จ'));
      expect(dialog.textContent).not.toContain('฿19,856.09');
      expect(within(dialog).getByRole('button', { name: 'ออกใบแจ้งหนี้' }).disabled).toBe(false);
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

    it('legacy data with several ISSUED rows: the prediction uses the one of the draft\'s own quotation, not the first ISSUED', async () => {
      const otherChain = docRow({
        id: 870, status: 'ISSUED', docNumber: 'GLR6900002-3', baseNumber: 'GLR6900002', version: 3, customerQuotationId: 11,
      });
      const ownChain = docRow({
        id: 880, status: 'ISSUED', docNumber: 'GLR6900004-1', baseNumber: 'GLR6900004', version: 1, customerQuotationId: 55,
      });
      const revisionDraft = docRow({ id: 881, status: 'DRAFT', docNumber: null, baseNumber: null, customerQuotationId: 55 });
      api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [otherChain, ownChain, revisionDraft] });
      renderDialog();

      await screen.findByTestId('remaining-invoice-draft-editor');
      fireEvent.click(screen.getByTestId('remaining-invoice-issue'));

      const dialog = await confirmDialog();
      expect(dialog.textContent).toContain('GLR6900004-2');
      expect(dialog.textContent).not.toContain('GLR6900002-4');
    });

    it('an ISSUED row of a DIFFERENT quotation does not predict a number (first issue for this quotation)', async () => {
      const issuedRow = docRow({
        id: 880, status: 'ISSUED', docNumber: 'GLR6900004-1', baseNumber: 'GLR6900004', version: 1, customerQuotationId: 55,
      });
      const draftOther = docRow({ id: 881, status: 'DRAFT', docNumber: null, baseNumber: null, customerQuotationId: 77 });
      api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [issuedRow, draftOther] });
      renderDialog();

      await screen.findByTestId('remaining-invoice-draft-editor');
      fireEvent.click(screen.getByTestId('remaining-invoice-issue'));

      const dialog = await confirmDialog();
      expect(dialog.textContent).toContain('ระบบจะออกเลขที่เอกสารใหม่เมื่อยืนยัน');
      expect(dialog.textContent).not.toContain('GLR6900004-2');
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

    // Both dialogs listen on `document`; one Escape must only dismiss the topmost (the confirm),
    // never also close the dialog under it and drop the unsaved อ้างอิง/วันที่/หมายเหตุ.
    it('Escape on the confirm closes only the confirm: the outer dialog stays open with the unsaved edit', async () => {
      api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [docRow()] });
      const { onClose } = renderDialog();

      fireEvent.change(await screen.findByDisplayValue('QT-2026-0099'), { target: { value: 'PO-UNSAVED' } });
      fireEvent.click(screen.getByTestId('remaining-invoice-issue'));
      await confirmDialog();

      fireEvent.keyDown(document, { key: 'Escape' });

      await waitFor(() => expect(screen.queryByRole('dialog', { name: 'ยืนยันการออกเอกสาร' })).toBeNull());
      expect(onClose).not.toHaveBeenCalled();
      expect(screen.getByDisplayValue('PO-UNSAVED')).toBeTruthy();
      expect(api.storedRemainingInvoices.issue).not.toHaveBeenCalled();

      // With the confirm gone the outer dialog is topmost again, so Escape closes it.
      fireEvent.keyDown(document, { key: 'Escape' });
      expect(onClose).toHaveBeenCalledTimes(1);
    });
  });

  it('ลบร่าง calls deleteDraft', async () => {
    api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [docRow()] });
    api.storedRemainingInvoices.deleteDraft.mockResolvedValue({ deleted: true });
    renderDialog();

    await screen.findByTestId('remaining-invoice-draft-editor');
    fireEvent.click(screen.getByTestId('remaining-invoice-delete-draft'));

    await waitFor(() => expect(api.storedRemainingInvoices.deleteDraft).toHaveBeenCalledWith(900));
  });

  // R2 (Opus review, GLA-99 step 2 review-round-1): editing a field then clicking "ออกใบแจ้งหนี้"
  // directly (WITHOUT clicking "บันทึก" first) must not silently discard the edit — issue() on the
  // backend freezes whatever was LAST SAVED, and only re-snapshots the COMPUTED content (O3), never
  // the dialog fields. The dialog itself must therefore save the pending edit before issuing.
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

  // P4 (Opus review, GLA-99 step 2 review-round-2, 2026-09-20): before this fix, `draft &&
  // canWrite` won the main render slot outright and the live ISSUED predecessor — plus its own
  // download button — disappeared entirely while a revision DRAFT was open (RemainingInvoiceService
  // #revise's own Javadoc: "the form being corrected STAYS ISSUED until the replacement is
  // actually issued"). A caller mid-revision could not download the very document they were about
  // to replace.
  it('P4: keeps the live ISSUED document (and its download) visible in version history while a revision DRAFT is open', async () => {
    const issuedRow = docRow({ id: 900, status: 'ISSUED', docNumber: 'GLR6900001-1', baseNumber: 'GLR6900001', version: 1 });
    const revisionDraft = docRow({ id: 901, status: 'DRAFT', docNumber: null, baseNumber: null, version: 1 });
    api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [issuedRow, revisionDraft] });
    renderDialog();

    // The revision draft's own editor wins the main slot.
    expect(await screen.findByTestId('remaining-invoice-draft-editor')).not.toBeNull();
    // But the predecessor's own ISSUED row, and its download, must still be reachable via the
    // version history — it is still the live document until this revision is itself issued.
    const history = screen.getByTestId('remaining-invoice-history');
    expect(within(history).getByText('GLR6900001-1')).not.toBeNull();
    expect(screen.getByTestId('remaining-invoice-download-version-900')).not.toBeNull();
    // The read-only ISSUED summary panel itself is not ALSO shown — the draft editor still wins
    // the main slot; only the history list carries the live document alongside it.
    expect(screen.queryByTestId('remaining-invoice-issued-summary')).toBeNull();
  });
});

describe('RemainingInvoiceDialog — a live ISSUED document exists', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows the issued summary and lets the caller download or revise', async () => {
    const issuedRow = docRow({ status: 'ISSUED', docNumber: 'GLR6900001-1', baseNumber: 'GLR6900001' });
    api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [issuedRow] });
    api.storedRemainingInvoices.download.mockResolvedValue(new Blob(['x']));
    renderDialog();

    const summary = await screen.findByTestId('remaining-invoice-issued-summary');
    // This ONE live row also appears a second time, in the version-history list below (ISSUED
    // rows are listed there too, per plan) — scope to the summary panel specifically.
    expect(within(summary).getByText('GLR6900001-1')).not.toBeNull();

    fireEvent.click(screen.getByTestId('remaining-invoice-download'));
    await waitFor(() => expect(api.storedRemainingInvoices.download).toHaveBeenCalledWith(900));

    fireEvent.click(screen.getByTestId('remaining-invoice-revise'));
    await waitFor(() => expect(api.storedRemainingInvoices.revise).toHaveBeenCalledWith(900));
  });

  it('lists SUPERSEDED versions with their own download buttons', async () => {
    const superseded = docRow({ id: 899, status: 'SUPERSEDED', docNumber: 'GLR6900001-1', version: 1, supersededById: 900 });
    const issuedRow = docRow({ id: 900, status: 'ISSUED', docNumber: 'GLR6900001-2', version: 2, baseNumber: 'GLR6900001' });
    api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [superseded, issuedRow] });
    renderDialog();

    expect(await screen.findByTestId('remaining-invoice-history')).not.toBeNull();
    expect(within(screen.getByTestId('remaining-invoice-history')).getByText('GLR6900001-1')).not.toBeNull();
    expect(within(screen.getByTestId('remaining-invoice-history')).getByText(/ถูกแทนที่แล้ว/)).not.toBeNull();
  });
});

// R4 (Opus review, GLA-99 step 2 review-round-1): write actions (create/save/issue/revise/delete)
// hidden unless the viewer is the deal's OWNING sales rep — mirrors
// RemainingInvoiceService#requireDepositNoticeIssueGate exactly (no CEO carve-out for THIS
// document either — see that class's own Javadoc). `canWrite={false}` is what a caller passes for
// every other role (account/ceo/sales_manager/a different sales rep/import).
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

  it('shows the waiting state, not the draft editor, when only a DRAFT exists (nothing issued yet)', async () => {
    api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [docRow()] });
    renderDialog({ canWrite: false });

    expect(await screen.findByTestId('remaining-invoice-waiting-for-sales')).not.toBeNull();
    expect(screen.queryByTestId('remaining-invoice-draft-editor')).toBeNull();
  });

  it('shows the read-only issued summary with download but WITHOUT the revise button', async () => {
    const issuedRow = docRow({ status: 'ISSUED', docNumber: 'GLR6900001-1', baseNumber: 'GLR6900001' });
    api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [issuedRow] });
    api.storedRemainingInvoices.download.mockResolvedValue(new Blob(['x']));
    renderDialog({ canWrite: false });

    expect(await screen.findByTestId('remaining-invoice-issued-summary')).not.toBeNull();
    expect(screen.getByTestId('remaining-invoice-download')).not.toBeNull();
    expect(screen.queryByTestId('remaining-invoice-revise')).toBeNull();

    fireEvent.click(screen.getByTestId('remaining-invoice-download'));
    await waitFor(() => expect(api.storedRemainingInvoices.download).toHaveBeenCalledWith(issuedRow.id));
  });

  it('still lists version history for a non-writer', async () => {
    const superseded = docRow({ id: 899, status: 'SUPERSEDED', docNumber: 'GLR6900001-1', version: 1, supersededById: 900 });
    const issuedRow = docRow({ id: 900, status: 'ISSUED', docNumber: 'GLR6900001-2', version: 2, baseNumber: 'GLR6900001' });
    api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [superseded, issuedRow] });
    renderDialog({ canWrite: false });

    expect(await screen.findByTestId('remaining-invoice-history')).not.toBeNull();
  });
});
