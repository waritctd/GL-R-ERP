import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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
        downloadRemainingInvoice: vi.fn(),
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
    maxItems: 22,
    itemsTotal: 37114.18,
    depositAmount: 18557.09,
    netAmount: 18557.09,
    vatAmount: 1299.0,
    totalPayable: 19856.09,
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

describe('RemainingInvoiceDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('prefills every field from the backend defaults once options load', async () => {
    api.tickets.remainingInvoiceOptions.mockResolvedValue({ options: baseOptions() });
    renderDialog();

    const referenceInput = await screen.findByDisplayValue('QT-2026-0099');
    expect(referenceInput).not.toBeNull();
    expect(screen.getByLabelText(/วันที่/).value).toBe('2026-09-01');
    expect(screen.getByLabelText(/เลขอ้างอิงมัดจำ/).value).toBe('AI2600145');
    // defaultSelected note is checked, the other is not.
    expect(screen.getByText('หมายเหตุ 1').closest('label').querySelector('input').checked).toBe(true);
    expect(screen.getByText('หมายเหตุ 2').closest('label').querySelector('input').checked).toBe(false);
    // Preview numbers rendered from the options payload.
    expect(screen.getByTestId('remaining-invoice-preview').textContent).toContain('19,856.09');
  });

  it('a bare download (defaults untouched) works immediately', async () => {
    api.tickets.remainingInvoiceOptions.mockResolvedValue({ options: baseOptions() });
    api.tickets.downloadRemainingInvoice.mockResolvedValue(new Blob(['x'], { type: 'application/vnd.ms-excel' }));
    renderDialog();

    await screen.findByDisplayValue('QT-2026-0099');
    expect(screen.getByTestId('remaining-invoice-download').disabled).toBe(false);
    fireEvent.click(screen.getByTestId('remaining-invoice-download'));

    await waitFor(() => expect(api.tickets.downloadRemainingInvoice).toHaveBeenCalledTimes(1));
    expect(api.tickets.downloadRemainingInvoice).toHaveBeenCalledWith(701, {
      reference: 'QT-2026-0099',
      depositReference: 'AI2600145',
      issueDate: '2026-09-01',
      noteIds: [1],
    });
  });

  it('clearing the reference field sends an explicit empty string, not the default', async () => {
    api.tickets.remainingInvoiceOptions.mockResolvedValue({ options: baseOptions() });
    api.tickets.downloadRemainingInvoice.mockResolvedValue(new Blob(['x']));
    renderDialog();

    await screen.findByDisplayValue('QT-2026-0099');
    fireEvent.click(screen.getByRole('button', { name: 'ล้างอ้างอิง' }));
    fireEvent.click(screen.getByTestId('remaining-invoice-download'));

    await waitFor(() => expect(api.tickets.downloadRemainingInvoice).toHaveBeenCalledTimes(1));
    expect(api.tickets.downloadRemainingInvoice).toHaveBeenCalledWith(701, expect.objectContaining({
      reference: '',
    }));
  });

  it('typing a free-text customer PO number sends it', async () => {
    api.tickets.remainingInvoiceOptions.mockResolvedValue({ options: baseOptions() });
    api.tickets.downloadRemainingInvoice.mockResolvedValue(new Blob(['x']));
    renderDialog();

    const input = await screen.findByDisplayValue('QT-2026-0099');
    fireEvent.change(input, { target: { value: 'PO-CUSTOMER-9999' } });
    fireEvent.click(screen.getByTestId('remaining-invoice-download'));

    await waitFor(() => expect(api.tickets.downloadRemainingInvoice).toHaveBeenCalledTimes(1));
    expect(api.tickets.downloadRemainingInvoice).toHaveBeenCalledWith(701, expect.objectContaining({
      reference: 'PO-CUSTOMER-9999',
    }));
  });

  it('over-capacity disables the download button and shows a refusal, without a failed round trip', async () => {
    api.tickets.remainingInvoiceOptions.mockResolvedValue({
      options: baseOptions({ itemCount: 23, maxItems: 22 }),
    });
    renderDialog();

    await screen.findByDisplayValue('QT-2026-0099');
    expect(screen.getByText(/เกินความจุของแบบฟอร์ม/)).not.toBeNull();
    expect(screen.getByTestId('remaining-invoice-download').disabled).toBe(true);

    fireEvent.click(screen.getByTestId('remaining-invoice-download'));
    expect(api.tickets.downloadRemainingInvoice).not.toHaveBeenCalled();
  });

  // Finding 6: blockingReason must disable the download button and surface the reason text,
  // distinctly from the over-capacity case above.
  it('a non-null blockingReason disables the download button and shows the reason', async () => {
    api.tickets.remainingInvoiceOptions.mockResolvedValue({
      options: baseOptions({
        blockingReason: 'ยังไม่ได้ออกใบแจ้งยอดมัดจำจากใบเสนอราคา QT-2026-0099',
      }),
    });
    renderDialog();

    expect(await screen.findByTestId('remaining-invoice-blocking-reason')).not.toBeNull();
    expect(screen.getByText(/ยังไม่ได้ออกใบแจ้งยอดมัดจำจากใบเสนอราคา QT-2026-0099/)).not.toBeNull();
    expect(screen.getByTestId('remaining-invoice-download').disabled).toBe(true);

    fireEvent.click(screen.getByTestId('remaining-invoice-download'));
    expect(api.tickets.downloadRemainingInvoice).not.toHaveBeenCalled();
  });

  // Finding 6: the ใบเสนอราคา picker only appears once 2+ quotations qualify — the common
  // one-quotation deal must never show it, wrong-way-round from the "shows with 2+" case below.
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

  // Finding 3 (Opus review): the prefill effect used to be keyed on defaultQuotationId/docNumber,
  // both INVARIANT across a quotation switch (defaultQuotationId is always "the newest qualifying
  // quotation" — see DepositNoticeService#resolveRemainingInvoice — regardless of which one was
  // actually requested), so it only re-prefilled by accident, when `options` happened to pass
  // through `undefined` mid-refetch. The OLD test above never caught this because both quotations
  // returned IDENTICALLY-shaped options — switching never changed any displayed field either way.
  // This test uses DIFFERENTLY-shaped options per quotation and switches A -> B -> A: the final
  // leg switches back to a quotation whose (ticketId, quotationId) query key was already fetched
  // once before, so React Query serves it from cache in the SAME render with no undefined blip —
  // exactly the case the old accident could not survive.
  it('prefill never leaks between quotations when switching A -> B -> A', async () => {
    const quotationOptions = [
      { value: 501, label: 'QT-2026-0501 (ผู้ออกแบบ)' },
      { value: 502, label: 'QT-2026-0502 (ผู้ซื้อ)' },
    ];
    const shapeFor = (quotationId) => (quotationId === 501
      ? baseOptions({
        quotationOptions, defaultQuotationId: 502, // invariant — always the newest, never 501
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

    // Initial load (no explicit selection) -> the server's own default, quotation B (502).
    await screen.findByDisplayValue('QT-2026-0502');
    expect(screen.getByLabelText(/เลขอ้างอิงมัดจำ/).value).toBe('AI-BUYER-2');

    // A fresh element for EACH switch below — the Modal body swaps to a Skeleton while a
    // never-before-seen (ticketId, quotationId) key is in flight (optionsQuery.isLoading), which
    // unmounts and later remounts the <select> as a NEW DOM node; a reference captured before an
    // uncached switch goes stale once that remount happens.
    const quotationSelect = () => screen.getByLabelText(/ใบเสนอราคา \(มีมากกว่า 1 ฉบับ/);
    await screen.findByLabelText(/ใบเสนอราคา \(มีมากกว่า 1 ฉบับ/);

    // A: switch to 501 (fresh fetch) -> fields must show A's own defaults.
    fireEvent.change(quotationSelect(), { target: { value: '501' } });
    await screen.findByDisplayValue('QT-2026-0501');
    expect(screen.getByLabelText(/เลขอ้างอิงมัดจำ/).value).toBe('AI-DESIGNER-1');

    // B: switch to 502 explicitly (fresh fetch under this exact key) -> B's own defaults.
    fireEvent.change(quotationSelect(), { target: { value: '502' } });
    await screen.findByDisplayValue('QT-2026-0502');
    expect(screen.getByLabelText(/เลขอ้างอิงมัดจำ/).value).toBe('AI-BUYER-2');

    // A again: switch back to 501 — React Query now serves this from cache (already fetched
    // above), so there is no loading/undefined beat this time. This is the leg that exposes the
    // old bug: reference/depositReference must show A's defaults again, never leak B's.
    fireEvent.change(quotationSelect(), { target: { value: '501' } });
    await screen.findByDisplayValue('QT-2026-0501');
    expect(screen.getByLabelText(/เลขอ้างอิงมัดจำ/).value).toBe('AI-DESIGNER-1');
  });

  it('shows a loading state while options are in flight and an error state if the fetch fails', async () => {
    let reject;
    api.tickets.remainingInvoiceOptions.mockReturnValue(new Promise((_, r) => { reject = r; }));
    renderDialog();

    // Download button should not be reachable/enabled before options resolve.
    expect(screen.queryByTestId('remaining-invoice-download')).not.toBeNull();
    expect(screen.getByTestId('remaining-invoice-download').disabled).toBe(true);

    reject(new Error('ไม่มีสิทธิ์เข้าถึงรายการนี้'));
    expect(await screen.findByText('ไม่มีสิทธิ์เข้าถึงรายการนี้')).not.toBeNull();
  });
});
