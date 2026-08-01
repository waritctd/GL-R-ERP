import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CommissionPage } from './CommissionPage.jsx';
import { api } from '../../api/index.js';

// Issue #422 B5: after CommissionPage's approve/reject/clawback/createFromDeal/
// createManualCommission/updateDeductions mutations succeed, they must also invalidate the
// ['payroll'] query-key prefix -- payroll figures (line.commissionPay) are fed by commission
// approvals, and PayrollPage now reads through react-query (B1), so an open PayrollPage tab
// should be able to pick the change up on its own next poll/focus refetch instead of only via a
// manual reload.
//
// Kept in its own file rather than folded into CommissionPage.test.jsx (issue #405's incentive/
// stock-bonus coverage, which landed on main while this branch was in review): that suite mocks
// only api.commissions.list/payrollReady, whereas these cases need the mutation methods too, and
// the two concerns are unrelated. Same one-concern-per-file convention as
// PayrollPage.inputDraft.test.jsx / PayrollPage.carryForward.test.jsx.

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      commissions: {
        list: vi.fn(),
        payrollReady: vi.fn(),
        approve: vi.fn(),
        reject: vi.fn(),
        clawback: vi.fn(),
        createFromDeal: vi.fn(),
        createManualCommission: vi.fn(),
        updateDeductions: vi.fn(),
      },
      tickets: {
        list: vi.fn(),
        get: vi.fn(),
      },
    },
  };
});

const salesManagerUser = { role: 'sales_manager', employeeId: 42, name: 'ผู้จัดการฝ่ายขาย' };

// SUBMITTED + sales_manager satisfies CommissionPage's own canReviewRecord (record.status ===
// 'SUBMITTED' && user.role === 'sales_manager'), and `kind: 'SALE'` (not one of the
// MANUAL_KIND_LABELS keys) exercises the invoiceDetails-bearing render/confirm path.
const submittedRecord = {
  id: 501,
  kind: 'SALE',
  status: 'SUBMITTED',
  salesRepId: 7,
  salesRepName: 'พนักงานขาย ทดสอบ',
  actualReceived: 100000,
  commissionableBase: 90000,
  weightMultiplier: 1,
  dealAmountMismatch: false,
  managerApprovedAt: null,
  managerApprovedByName: null,
  ceoApprovedAt: null,
  ceoApprovedByName: null,
  invoiceDetails: {
    invoiceNumber: 'INV-2026-0501',
    invoiceDate: '2026-07-01',
    invoiceAttachmentFileName: 'invoice.pdf',
    grossAmount: 100000,
    bankFees: 0,
    suspenseVat: 0,
    transportFee: 0,
    cutFee: 0,
    shortfall: 0,
    withholdingTax: 0,
    overpayment: 0,
  },
};

function renderCommissionPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
  const view = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/commissions']}>
        <CommissionPage user={salesManagerUser} showToast={vi.fn()} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { ...view, invalidateSpy };
}

describe('CommissionPage issue #422 B5 (payroll-prefix invalidation)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.commissions.list.mockResolvedValue({ commissions: [submittedRecord] });
    api.commissions.approve.mockResolvedValue({ commission: { ...submittedRecord, status: 'MANAGER_APPROVED' } });
    api.tickets.list.mockResolvedValue({ tickets: [] });
  });

  it('invalidates the [\'payroll\'] prefix after a successful approve, alongside its own load()', async () => {
    const { invalidateSpy } = renderCommissionPage();

    const approveButton = await screen.findByTestId('commission-approve');
    fireEvent.click(approveButton);

    const confirmButton = await screen.findByRole('button', { name: /^อนุมัติ$/ });
    fireEvent.click(confirmButton);

    await waitFor(() => expect(api.commissions.approve).toHaveBeenCalledWith(501));
    await waitFor(() => expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['payroll'] }));
    // The pre-existing commissions list also reloads -- this fix must not replace that behaviour.
    await waitFor(() => expect(api.commissions.list).toHaveBeenCalledTimes(2));
  });
});
