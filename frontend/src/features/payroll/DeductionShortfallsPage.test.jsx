import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DeductionShortfallsPage } from './DeductionShortfallsPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: { payroll: { getDeductionShortfalls: vi.fn() } },
  };
});

// Field-for-field with PayrollDeductionShortfallDto. Only LEGAL_EXECUTION_GARNISHMENT is representable —
// recordGarnishmentShortfalls writes no other kind.
const rows = [
  {
    id: 1,
    employeeId: 3,
    employeeCode: 'GLR-1003',
    employeeName: 'สมชาย ใจดี',
    payrollMonth: '2026-07-01',
    deductionKind: 'LEGAL_EXECUTION_GARNISHMENT',
    requestedAmount: 5000,
    actualAmount: 3120.5,
    shortfallAmount: 1879.5,
    shortfallReason: 'ป.วิ.พ. ม.302 -- เพดานการอายัดเงินเดือนตามประเภท SALARY ส่วนต่างยังไม่ได้ส่งให้เจ้าหนี้',
    payrollPeriodId: 7,
    createdAt: '2026-07-25T03:00:00Z',
    updatedAt: '2026-07-25T03:00:00Z',
  },
  {
    id: 2,
    employeeId: 20,
    employeeCode: 'GLR-1020',
    employeeName: 'มานี รักงาน',
    payrollMonth: '2026-06-01',
    deductionKind: 'LEGAL_EXECUTION_GARNISHMENT',
    requestedAmount: 4000,
    actualAmount: 2600,
    shortfallAmount: 1400,
    shortfallReason: 'ป.วิ.พ. ม.302 -- เพดานการอายัดเงินเดือนตามประเภท SALARY',
    payrollPeriodId: 6,
    createdAt: '2026-06-25T03:00:00Z',
    updatedAt: '2026-06-25T03:00:00Z',
  },
];

function renderPage({ entry = '/payroll/deduction-shortfalls' } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[entry]}>
        <DeductionShortfallsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('DeductionShortfallsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.payroll.getDeductionShortfalls.mockResolvedValue({ items: rows });
  });

  it('passes ?employeeId through to the server as the real query param', async () => {
    renderPage({ entry: '/payroll/deduction-shortfalls?employeeId=3' });

    await waitFor(() => expect(api.payroll.getDeductionShortfalls).toHaveBeenCalledWith({ employeeId: '3' }));
  });
});
