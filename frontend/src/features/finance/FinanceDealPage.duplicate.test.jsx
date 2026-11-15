import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FinanceDealPage } from './FinanceDealPage.jsx';
import { makeFinanceDeal } from '../../test/fixtures/financeDeal.js';
import { api } from '../../api/index.js';
import { ApiError } from '../../api/client.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  const { DEAL_STAGE_CATALOG } = await import('../../data/dealStageCatalog.js');
  return {
    ...actual,
    api: {
      meta: { dealStages: vi.fn().mockResolvedValue(DEAL_STAGE_CATALOG) },
      finance: {
        getDeal: vi.fn(), addComment: vi.fn(), confirmDepositPaid: vi.fn(), confirmFinalPayment: vi.fn(),
        recordPayment: vi.fn(), confirmCloseReady: vi.fn(), revokeCloseConfirmation: vi.fn(), updateStage: vi.fn(),
      },
      commissions: { createFromDeal: vi.fn() },
      receiptImports: { latest: vi.fn() },
    },
  };
});

const action = (name, label) => ({ action: name, label, targetStage: null, requiredFields: [] });

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/finance/deals/501']}>
        <Routes>
          <Route path="/finance/deals/:id" element={<FinanceDealPage user={{ role: 'account', name: 'บัญชี' }} showToast={vi.fn()} />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

// Review H1, reverse order: the server refuses a hand-typed receipt that may be one already uploaded from Express
// (409 POSSIBLE_DUPLICATE); the page says which document and only re-sends with confirmPossibleDuplicate on a yes.
describe('FinanceDealPage — a manual receipt that may duplicate an Express one', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.finance.getDeal.mockResolvedValue({ deal: makeFinanceDeal({ availableActions: [action('DEPOSIT_PAID', 'รับมัดจำ')] }) });
  });

  it('ยืนยันรับมัดจำ: names the uploaded document and re-sends only after a second yes', async () => {
    api.finance.confirmDepositPaid
      .mockRejectedValueOnce(new ApiError('อาจซ้ำกับ RE-6911-0188 ที่อัปโหลดแล้ว', 409,
        { code: 'POSSIBLE_DUPLICATE', expressDocNo: 'RE-6911-0188' }))
      .mockResolvedValueOnce({ deal: makeFinanceDeal({ availableActions: [] }) });
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'ยืนยันรับมัดจำ' }));
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'ยืนยันรับมัดจำ' }));
    await waitFor(() => expect(api.finance.confirmDepositPaid).toHaveBeenCalledTimes(1));
    expect(api.finance.confirmDepositPaid).toHaveBeenLastCalledWith(501);

    const warn = await screen.findByRole('dialog', { name: 'อาจซ้ำกับรายการที่อัปโหลดแล้ว' });
    expect(within(warn).getByText(/อาจซ้ำกับ RE-6911-0188 ที่อัปโหลดแล้ว/)).toBeTruthy();
    fireEvent.click(within(warn).getByRole('button', { name: 'เป็นคนละรายการ บันทึกต่อ' }));

    await waitFor(() => expect(api.finance.confirmDepositPaid).toHaveBeenCalledTimes(2));
    expect(api.finance.confirmDepositPaid).toHaveBeenLastCalledWith(501, { confirmPossibleDuplicate: true });
  });

  it('cancelling the warning sends nothing more', async () => {
    api.finance.confirmDepositPaid.mockRejectedValueOnce(new ApiError('อาจซ้ำ', 409,
      { code: 'POSSIBLE_DUPLICATE', expressDocNo: 'RE-1' }));
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'ยืนยันรับมัดจำ' }));
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'ยืนยันรับมัดจำ' }));
    const warn = await screen.findByRole('dialog', { name: 'อาจซ้ำกับรายการที่อัปโหลดแล้ว' });
    fireEvent.click(within(warn).getByRole('button', { name: 'ไม่บันทึก' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'อาจซ้ำกับรายการที่อัปโหลดแล้ว' })).toBeNull());
    expect(api.finance.confirmDepositPaid).toHaveBeenCalledTimes(1);
  });
});

// Slice 5 prefills the บันทึกรับชำระ modal; sales-cash's 409 retry must survive that: the hand-typed receipt is
// re-sent with the SAME body plus confirmPossibleDuplicate, and the Express-fallback header follows the upload flag.
describe('FinanceDealPage — บันทึกรับชำระ (prefilled modal) still honours the possible-duplicate retry', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.receiptImports.latest.mockResolvedValue({ uploadEnabled: true, latest: null, openUnmatched: 0 });
    api.finance.getDeal.mockResolvedValue({ deal: makeFinanceDeal({ availableActions: [action('RECORD_PAYMENT', 'บันทึกรับชำระ')] }) });
  });

  async function openModal() {
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'บันทึกรับชำระ' }));
    return screen.findByRole('dialog', { name: 'บันทึกรับชำระ' });
  }

  it('409 POSSIBLE_DUPLICATE -> บันทึกต่อ re-sends the typed body with confirmPossibleDuplicate', async () => {
    api.finance.recordPayment
      .mockRejectedValueOnce(new ApiError('อาจซ้ำ', 409, { code: 'POSSIBLE_DUPLICATE', expressDocNo: 'RE-6911-0188' }))
      .mockResolvedValueOnce({ deal: makeFinanceDeal({ availableActions: [] }) });
    const dialog = await openModal();
    fireEvent.change(within(dialog).getByLabelText('จำนวนเงิน'), { target: { value: '5000' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึกรับชำระ' }));
    await waitFor(() => expect(api.finance.recordPayment).toHaveBeenCalledTimes(1));
    const firstBody = api.finance.recordPayment.mock.calls[0][1];
    expect(firstBody.amount).toBe(5000);
    expect(firstBody.confirmPossibleDuplicate).toBeUndefined();

    const warn = await screen.findByRole('dialog', { name: 'อาจซ้ำกับรายการที่อัปโหลดแล้ว' });
    fireEvent.click(within(warn).getByRole('button', { name: 'เป็นคนละรายการ บันทึกต่อ' }));
    await waitFor(() => expect(api.finance.recordPayment).toHaveBeenCalledTimes(2));
    expect(api.finance.recordPayment).toHaveBeenLastCalledWith(501, { ...firstBody, confirmPossibleDuplicate: true });
  });

  // Found by the real-browser run (e2e-real/cash-and-ops.spec.js): both dialogs are fixed z-50 overlays, so the one
  // later in the DOM paints on top. The warning used to render BEFORE the payment modal, which hid it — and its
  // บันทึกต่อ button — behind the form it was warning about. jsdom has no paint order, so the DOM order is the proxy.
  it('the possible-duplicate warning is stacked ABOVE the still-open payment modal (later in the DOM)', async () => {
    api.finance.recordPayment.mockRejectedValueOnce(
      new ApiError('อาจซ้ำ', 409, { code: 'POSSIBLE_DUPLICATE', expressDocNo: 'RE-6911-0188' }),
    );
    const dialog = await openModal();
    fireEvent.change(within(dialog).getByLabelText('จำนวนเงิน'), { target: { value: '5000' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึกรับชำระ' }));
    const warn = await screen.findByRole('dialog', { name: 'อาจซ้ำกับรายการที่อัปโหลดแล้ว' });
    const form = screen.getByRole('dialog', { name: 'บันทึกรับชำระ' });
    expect(form.compareDocumentPosition(warn) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('the Express-fallback header appears with the upload flag on and is gone with it off', async () => {
    let dialog = await openModal();
    await waitFor(() => expect(within(dialog).queryByText(/ใช้เมื่อรายการจาก Express จับคู่ไม่ได้/)).not.toBeNull());
    cleanup();
    api.receiptImports.latest.mockResolvedValue({ uploadEnabled: false, latest: null, openUnmatched: 0 });
    dialog = await openModal();
    await waitFor(() => expect(api.receiptImports.latest).toHaveBeenCalledTimes(2));
    expect(within(dialog).queryByText(/ใช้เมื่อรายการจาก Express จับคู่ไม่ได้/)).toBeNull();
  });
});
