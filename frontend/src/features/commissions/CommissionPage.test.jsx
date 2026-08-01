import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CommissionPage } from './CommissionPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// Issue #405: covers the new HR payroll-ready table columns (อินเซนทีฟ / โบนัสขายของในสต๊อค) and
// the sales rep's own monthly-summary incentive line, including the manual-INCENTIVE suppression
// guard. Mirrors the real DTO shape CommissionService#payrollReadySummary now returns
// (incentiveAmount/stockBonusAmount, additive fields) and the real CommissionRecord shape list()
// returns, so this test exercises CommissionPage exactly as the real API would drive it.
vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      commissions: {
        list: vi.fn(),
        payrollReady: vi.fn(),
      },
      tickets: { list: vi.fn().mockResolvedValue({ tickets: [] }) },
    },
  };
});

function renderPage(user) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <CommissionPage user={user} showToast={vi.fn()} />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

const hrUser = { id: 900, employeeId: 900, name: 'HR Test', role: 'hr' };
const salesUser = { id: 10, employeeId: 10, name: 'พนักงานขาย ทดสอบ', role: 'sales' };

function invoiceDetails(overrides = {}) {
  return {
    id: 1,
    invoiceNumber: 'INV-405-0001',
    invoiceDate: '2026-08-01',
    grossAmount: 3210000,
    bankFees: 0,
    suspenseVat: 0,
    transportFee: 0,
    cutFee: 0,
    shortfall: 0,
    withholdingTax: 0,
    overpayment: 0,
    invoiceAttachmentId: 1,
    invoiceAttachmentFileName: 'invoice.pdf',
    createdAt: '2026-08-01T00:00:00Z',
    updatedAt: '2026-08-01T00:00:00Z',
    ...overrides,
  };
}

function saleRecord(overrides = {}) {
  return {
    id: 501,
    kind: 'SALE',
    status: 'APPROVED',
    salesRepId: 10,
    salesRepName: 'พนักงานขาย ทดสอบ',
    submittedById: 10,
    payrollMonth: '2026-08-01',
    // 3,210,000.00 / 1.07 = 3,000,000.00 exactly -- lands on the first INCENTIVE threshold.
    actualReceived: 3210000.0,
    commissionableBase: 3000000.0,
    weightMultiplier: 1,
    approvedById: 2,
    approvedAt: '2026-08-05T00:00:00Z',
    managerApprovedBy: 2,
    managerApprovedByName: 'ผู้จัดการฝ่ายขาย',
    managerApprovedAt: '2026-08-05T00:00:00Z',
    ceoApprovedBy: 3,
    ceoApprovedByName: 'CEO',
    ceoApprovedAt: '2026-08-05T00:00:00Z',
    rejectedById: null,
    rejectedByName: null,
    rejectedAt: null,
    rejectionReason: null,
    cancellationOfId: null,
    cancellationReason: null,
    dealPayableAmountSnapshot: null,
    dealAmountMismatch: false,
    manualAmount: null,
    manualReason: null,
    createdAt: '2026-08-01T00:00:00Z',
    updatedAt: '2026-08-01T00:00:00Z',
    invoiceDetails: invoiceDetails(),
    ...overrides,
  };
}

function manualIncentiveRecord(overrides = {}) {
  return {
    id: 502,
    kind: 'INCENTIVE',
    status: 'APPROVED',
    salesRepId: 10,
    salesRepName: 'พนักงานขาย ทดสอบ',
    submittedById: 3,
    payrollMonth: '2026-08-01',
    actualReceived: 0,
    commissionableBase: 0,
    weightMultiplier: 1,
    approvedById: 3,
    approvedAt: '2026-08-06T00:00:00Z',
    managerApprovedBy: null,
    managerApprovedByName: null,
    managerApprovedAt: null,
    ceoApprovedBy: 3,
    ceoApprovedByName: 'CEO',
    ceoApprovedAt: '2026-08-06T00:00:00Z',
    rejectedById: null,
    rejectedByName: null,
    rejectedAt: null,
    rejectionReason: null,
    cancellationOfId: null,
    cancellationReason: null,
    dealPayableAmountSnapshot: null,
    dealAmountMismatch: false,
    manualAmount: 15000,
    manualReason: 'hand-entered before auto-compute shipped',
    createdAt: '2026-08-06T00:00:00Z',
    updatedAt: '2026-08-06T00:00:00Z',
    invoiceDetails: null,
    ...overrides,
  };
}

async function setMonthInput(value) {
  const input = screen.getByLabelText('รอบเดือน');
  fireEvent.change(input, { target: { value } });
}

describe('CommissionPage — HR payroll-ready table (issue #405)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders the อินเซนทีฟ and โบนัสขายของในสต๊อค columns with the DTO values', async () => {
    api.commissions.payrollReady.mockResolvedValue({
      summary: {
        payrollMonth: '2026-08-01',
        status: 'PAYROLL_READY',
        totalCommissionableBase: 3246381.33,
        totalCommissionAmount: 73757.39,
        totalIncentiveAmount: 15000,
        totalStockBonusAmount: 2000,
        salesReps: [{
          salesRepId: 10,
          salesRepName: 'เจนเนตร',
          commissionableBase: 3246381.33,
          commissionAmount: 73757.39,
          manualAdjustmentAmount: 0,
          incentiveAmount: 15000,
          stockBonusAmount: 2000,
        }],
      },
    });

    renderPage(hrUser);

    expect(await screen.findByText('อินเซนทีฟ')).not.toBeNull();
    expect(screen.getByText('โบนัสขายของในสต๊อค')).not.toBeNull();
    expect(await screen.findByText('เจนเนตร')).not.toBeNull();
    expect(screen.getByText('฿15,000.00')).not.toBeNull();
    expect(screen.getByText('฿2,000.00')).not.toBeNull();
  });

  it('shows a zero stock bonus (all-zero column, not hidden) when the feature is config-gated off', async () => {
    api.commissions.payrollReady.mockResolvedValue({
      summary: {
        payrollMonth: '2026-08-01',
        status: 'PAYROLL_READY',
        totalCommissionableBase: 3246381.33,
        totalCommissionAmount: 71757.39,
        totalIncentiveAmount: 15000,
        totalStockBonusAmount: 0,
        salesReps: [{
          salesRepId: 10,
          salesRepName: 'เจนเนตร',
          commissionableBase: 3246381.33,
          commissionAmount: 71757.39,
          manualAdjustmentAmount: 0,
          incentiveAmount: 15000,
          stockBonusAmount: 0,
        }],
      },
    });

    renderPage(hrUser);

    expect(await screen.findByText('โบนัสขายของในสต๊อค')).not.toBeNull();
    // The column header renders even though every value is zero -- not conditionally hidden.
    expect(screen.getByText('฿0.00')).not.toBeNull();
  });
});

describe('CommissionPage — sales rep monthly incentive line (issue #405)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows the auto-computed incentive line once the rep reaches the first threshold in August 2026', async () => {
    api.commissions.list.mockResolvedValue({ commissions: [saleRecord()] });

    renderPage(salesUser);

    await waitFor(() => expect(api.commissions.list).toHaveBeenCalled());
    await setMonthInput('2026-08');

    expect(await screen.findByText('อินเซนทีฟ (นอกขั้นบันได)')).not.toBeNull();
    // Two 15,000.00 occurrences would also be plausible depending on layout; assert the exact
    // incentive figure is present at least once.
    expect(screen.getAllByText('฿15,000.00').length).toBeGreaterThan(0);
  });

  it('suppresses the auto-computed incentive line when an approved manual INCENTIVE already exists for the month', async () => {
    api.commissions.list.mockResolvedValue({ commissions: [saleRecord(), manualIncentiveRecord()] });

    renderPage(salesUser);

    await waitFor(() => expect(api.commissions.list).toHaveBeenCalled());
    await setMonthInput('2026-08');

    // Wait for the panel (proves records loaded and the month change took effect) before
    // asserting the suppressed line is absent.
    expect(await screen.findByText('ขั้นบันไดค่าคอมเดือนนี้ (ประมาณการ)')).not.toBeNull();
    expect(screen.queryByText('อินเซนทีฟ (นอกขั้นบันได)')).toBeNull();
  });

  it('stays at zero incentive for a payroll month before the 2026-08-01 effective date (fix-forward)', async () => {
    api.commissions.list.mockResolvedValue({
      commissions: [saleRecord({ payrollMonth: '2026-07-01' })],
    });

    renderPage(salesUser);

    await waitFor(() => expect(api.commissions.list).toHaveBeenCalled());
    await setMonthInput('2026-07');

    expect(await screen.findByText('ขั้นบันไดค่าคอมเดือนนี้ (ประมาณการ)')).not.toBeNull();
    expect(screen.queryByText('อินเซนทีฟ (นอกขั้นบันได)')).toBeNull();
  });
});
