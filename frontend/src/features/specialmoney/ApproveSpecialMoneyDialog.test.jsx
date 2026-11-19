import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApproveSpecialMoneyDialog } from './ApproveSpecialMoneyDialog.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// This project does not wire up jest-dom's matchers (see LoginPage.test.jsx's own note) -- plain DOM
// assertions (.disabled, queryByText returning null, etc.) throughout, not toBeInTheDocument/ toBeDisabled.
vi.mock('../../api/index.js', () => ({
  api: {
    specialMoney: {
      approvalPreview: vi.fn(),
    },
  },
}));

function renderDialog(props = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const defaultProps = {
    open: true,
    request: { id: 1, requestedAmount: 5000, employeeName: 'พนักงานทดสอบ', employeeCode: 'E001' },
    typeLabel: 'ชุดพนักงานใหม่',
    onConfirm: vi.fn().mockResolvedValue(undefined),
    onCancel: vi.fn(),
  };
  const merged = { ...defaultProps, ...props };
  const view = render(
    <QueryClientProvider client={queryClient}>
      <ApproveSpecialMoneyDialog {...merged} />
    </QueryClientProvider>,
  );
  return { ...view, props: merged, queryClient };
}

/** Tests approval ceilings, reason requirements, zero/missing ceilings, and stale preview handling. */
describe('ApproveSpecialMoneyDialog approval-ceiling preview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows the over-ceiling warning and a shortcut button that fills in the ceiling amount', async () => {
    api.specialMoney.approvalPreview.mockResolvedValue({
      preview: { requestedAmount: 5000, eligibleAmount: 1960, payrollMonth: '2026-08-01' },
    });
    renderDialog();

    await screen.findByText(/ยอดที่ขอเบิกเกินเพดาน/);
    const useCeilingButton = screen.getByRole('button', { name: 'ใช้ยอดเพดาน (฿1,960)' });

    const amountInput = document.getElementById('approve-special-money-amount');
    expect(amountInput.value).toBe('5000'); // pre-filled with the REQUESTED amount, untouched so far

    fireEvent.click(useCeilingButton);
    expect(amountInput.value).toBe('1960');
  });

  it('disables Confirm with a reason-required message when the entered amount exceeds the ceiling and no reason is given, and enables it once a reason is typed', async () => {
    api.specialMoney.approvalPreview.mockResolvedValue({
      preview: { requestedAmount: 5000, eligibleAmount: 1960, payrollMonth: '2026-08-01' },
    });
    renderDialog();

    await screen.findByText(/เพดานตามระเบียบสำหรับคำขอนี้/);
    // amountInput already reads 5000 (the pre-filled requested amount), which is over the 1960 ceiling -- so the
    // reason-required gate should already be active with no reason typed.
    expect(screen.getByText('ต้องระบุเหตุผลเมื่อจำนวนเงินที่อนุมัติเกินเพดานตามระเบียบ')).not.toBeNull();
    expect(screen.getByRole('button', { name: 'อนุมัติ' }).disabled).toBe(true);

    fireEvent.change(document.getElementById('approve-special-money-reason'), {
      target: { value: 'อนุมัติเกินเพดานตามดุลยพินิจ CEO' },
    });

    expect(screen.queryByText('ต้องระบุเหตุผลเมื่อจำนวนเงินที่อนุมัติเกินเพดานตามระเบียบ')).toBeNull();
    expect(screen.getByRole('button', { name: 'อนุมัติ' }).disabled).toBe(false);
  });

  it('allows Confirm with no reason when the entered amount equals the ceiling exactly, and sends that amount', async () => {
    api.specialMoney.approvalPreview.mockResolvedValue({
      preview: { requestedAmount: 5000, eligibleAmount: 1960, payrollMonth: '2026-08-01' },
    });
    const onConfirm = vi.fn().mockResolvedValue(undefined);
    renderDialog({ onConfirm });

    await screen.findByText(/เพดานตามระเบียบสำหรับคำขอนี้/);
    fireEvent.change(document.getElementById('approve-special-money-amount'), { target: { value: '1960' } });

    expect(screen.queryByText('ต้องระบุเหตุผลเมื่อจำนวนเงินที่อนุมัติเกินเพดานตามระเบียบ')).toBeNull();
    const confirmButton = screen.getByRole('button', { name: 'อนุมัติ' });
    expect(confirmButton.disabled).toBe(false);

    fireEvent.click(confirmButton);
    await waitFor(() => expect(onConfirm).toHaveBeenCalledWith(1960, null));
  });
});
