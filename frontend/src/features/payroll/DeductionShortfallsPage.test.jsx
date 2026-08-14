import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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

// Field-for-field with PayrollDeductionShortfallDto. Only LEGAL_EXECUTION_GARNISHMENT is
// representable — recordGarnishmentShortfalls writes no other kind.
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

  it('renders a row per shortfall with money formatted as THB', async () => {
    renderPage();

    expect(await screen.findByText('สมชาย ใจดี')).not.toBeNull();
    expect(screen.getByText('มานี รักงาน')).not.toBeNull();
    // requested / actual / shortfall for row 1, all straight from the DTO.
    expect(screen.getByText('฿5,000.00')).not.toBeNull();
    expect(screen.getByText('฿3,120.50')).not.toBeNull();
    expect(screen.getByText('฿1,879.50')).not.toBeNull();
  });

  // Regression: initialSort's key is `dir`, not `direction` (DataTable.jsx reads
  // `initialSort.dir === 'desc'`). Spelling it wrong silently sorted OLDEST month first, which
  // contradicts the server's own `ORDER BY s.payroll_month DESC`. Feeding rows in ascending order
  // is what makes this test able to fail — pre-sorted input would pass either way.
  it('defaults to newest payroll month first, even when the server order is reversed', async () => {
    api.payroll.getDeductionShortfalls.mockResolvedValue({ items: [rows[1], rows[0]] });
    renderPage();
    await screen.findByText('สมชาย ใจดี');

    const months = screen.getAllByText(/^(ก\.ค\.|มิ\.ย\.) 2569$/).map((node) => node.textContent);
    expect(months).toEqual(['ก.ค. 2569', 'มิ.ย. 2569']);
  });

  it('renders payrollMonth as a Thai month + BE year, not a raw date', async () => {
    renderPage();

    // 2026-07-01 -> ก.ค. 2569
    expect(await screen.findByText('ก.ค. 2569')).not.toBeNull();
    expect(screen.getByText('มิ.ย. 2569')).not.toBeNull();
    expect(screen.queryByText('2026-07-01')).toBeNull();
  });

  it('labels the deduction kind in Thai rather than showing the enum', async () => {
    renderPage();

    expect((await screen.findAllByText('อายัดเงินเดือนตามหมายบังคับคดี')).length).toBe(2);
    expect(screen.queryByText('LEGAL_EXECUTION_GARNISHMENT')).toBeNull();
  });

  it('summarises row count, employees affected and the displayed total', async () => {
    renderPage();
    // Wait for data before reading the strip — CompactStatRow's skeleton carries the same testid.
    await screen.findByText('สมชาย ใจดี');

    const summary = screen.getByTestId('compact-stat-row');
    // Two rows, two distinct employees.
    expect(within(summary).getAllByText('2').length).toBe(2);
    // 1879.50 + 1400.00 — a presentation sum of the rows shown, labelled as such.
    expect(within(summary).getByText('฿3,279.50')).not.toBeNull();
  });

  it('keeps the long legal reason behind a per-row disclosure', async () => {
    renderPage();
    await screen.findByText('สมชาย ใจดี');

    // Collapsed by default: renderExpanded returns null until the row is opened, so the paragraph
    // of legal Thai is not sitting inline under every row.
    expect(screen.queryByText(/ส่วนต่างยังไม่ได้ส่งให้เจ้าหนี้/)).toBeNull();

    const toggle = screen.getByRole('button', { name: /ดูเหตุผลที่หักไม่ครบของ สมชาย ใจดี/ });
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    fireEvent.click(toggle);

    expect(await screen.findByText(/ส่วนต่างยังไม่ได้ส่งให้เจ้าหนี้/)).not.toBeNull();
    expect(screen.getByRole('button', { name: /ซ่อนเหตุผลที่หักไม่ครบของ สมชาย ใจดี/ }).getAttribute('aria-expanded')).toBe('true');
  });

  it('passes ?employeeId through to the server as the real query param', async () => {
    renderPage({ entry: '/payroll/deduction-shortfalls?employeeId=3' });

    await waitFor(() => expect(api.payroll.getDeductionShortfalls).toHaveBeenCalledWith({ employeeId: '3' }));
  });

  it('asks for every row when no employee is pinned', async () => {
    renderPage();

    await waitFor(() => expect(api.payroll.getDeductionShortfalls).toHaveBeenCalledWith({}));
  });

  it('reads an empty ledger as the good outcome, not a failure', async () => {
    api.payroll.getDeductionShortfalls.mockResolvedValue({ items: [] });
    renderPage();

    // DataTable announces the zero-row message in a live region as well as the EmptyState title,
    // so the text legitimately appears more than once.
    expect((await screen.findAllByText('ไม่มียอดค้างหัก')).length).toBeGreaterThan(0);
    expect(screen.getByText(/หักเงินตามหมายบังคับคดีได้ครบ/)).not.toBeNull();
  });

  it('surfaces a load failure with a retry', async () => {
    api.payroll.getDeductionShortfalls.mockRejectedValue(new Error('boom'));
    renderPage();

    expect(await screen.findByText('โหลดรายการยอดค้างหักไม่สำเร็จ')).not.toBeNull();
    expect(screen.getByRole('button', { name: 'ลองใหม่' })).not.toBeNull();
  });

  it('explains that the ledger is read-only and written by the payroll run', async () => {
    renderPage();

    expect(await screen.findByText(/ประมวลผลเงินเดือน/)).not.toBeNull();
    expect(screen.getByText(/การดูอย่างเดียว/)).not.toBeNull();
  });
});
