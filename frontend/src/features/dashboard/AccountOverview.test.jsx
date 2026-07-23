import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AccountOverview } from './AccountOverview.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: { list: vi.fn() },
    },
  };
});

const accountUser = { role: 'account', name: 'บัญชี ทดสอบ', employeeId: 3 };
const employee = { nickName: 'ปุ้ย', nameTh: 'บัญชี ทดสอบ' };

// A row overdue on final payment — should surface as "ติดตามชำระ" (overdue
// outranks the plain "รับชำระส่วนที่เหลือ" wording) and sort first.
const overdueTicket = {
  id: 101, code: 'PR-2026-0101', title: 'โครงการเอ', customerName: 'บริษัท เอ จำกัด',
  status: 'quotation_issued', paymentStatus: 'AWAITING_FINAL_PAYMENT', fulfillmentStatus: null,
  lifecycle: 'ACTIVE', salesStage: 'DELIVERY_SCHEDULING', depositPolicy: 'REQUIRED',
  closeConfirmedAt: null, invoiceOnFile: false,
  amountPayable: 200000, amountPaid: 100000, amountOutstanding: 100000,
  overdue: true, dueDate: '2026-06-01', updatedAt: '2026-07-01T00:00:00.000Z',
};

// A deposit-notice-issued row, not overdue — "ยืนยันรับมัดจำ".
const depositTicket = {
  id: 102, code: 'PR-2026-0102', title: 'โครงการบี', customerName: 'บริษัท บี จำกัด',
  status: 'quotation_issued', paymentStatus: 'DEPOSIT_NOTICE_ISSUED', fulfillmentStatus: null,
  lifecycle: 'ACTIVE', salesStage: 'NEGOTIATION', depositPolicy: 'REQUIRED',
  closeConfirmedAt: null, invoiceOnFile: false,
  amountPayable: 50000, amountPaid: 0, amountOutstanding: 50000,
  overdue: false, dueDate: null, updatedAt: '2026-07-10T00:00:00.000Z',
};

// A deal with no pending account step at all — must NOT appear in the worklist.
const noActionTicket = {
  id: 103, code: 'PR-2026-0103', title: 'โครงการซี', customerName: 'บริษัท ซี จำกัด',
  status: 'quotation_issued', paymentStatus: 'CUSTOMER_CONFIRMED', fulfillmentStatus: null,
  lifecycle: 'ACTIVE', salesStage: 'ORDER_RECEIVED', depositPolicy: 'REQUIRED',
  closeConfirmedAt: null, invoiceOnFile: false,
  amountPayable: 10000, amountPaid: 0, amountOutstanding: 10000,
  overdue: false, dueDate: null, updatedAt: '2026-07-05T00:00:00.000Z',
};

function renderOverview() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/']}>
        <AccountOverview user={accountUser} employee={employee} showToast={vi.fn()} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('AccountOverview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.tickets.list.mockImplementation((params = {}) => {
      if (params.salesStage === 'CLOSED_PAID') return Promise.resolve({ tickets: [] });
      return Promise.resolve({ tickets: [overdueTicket, depositTicket, noActionTicket] });
    });
  });

  it('renders ฿ money-pulse buckets derived from the scoped ticket list', async () => {
    renderOverview();
    // "เกินกำหนด" also labels the right-rail month-summary row, so this waits
    // for load via a label unique to the pulse instead of racing findByText
    // against a query that resolves to more than one match.
    await screen.findByText('รอปิดงาน');
    expect(screen.getAllByText('เกินกำหนด').length).toBeGreaterThan(0);
    expect(screen.getByText('รอรับมัดจำ')).not.toBeNull();
    expect(screen.getByText('รอชำระส่วนที่เหลือ')).not.toBeNull();
    expect(screen.getByText('ออกค่าคอม')).not.toBeNull();
    // Overdue bucket carries the overdue ticket's outstanding balance (฿100,000).
    expect(screen.getAllByText('฿100,000').length).toBeGreaterThan(0);
  });

  it('shows the per-status next-action CTA for each worklist row', async () => {
    renderOverview();
    const worklist = await screen.findByText('สิ่งที่ต้องทำ');
    const panel = worklist.closest('section');
    expect(within(panel).getByText('ติดตามชำระ')).not.toBeNull();
    expect(within(panel).getByText('ยืนยันรับมัดจำ')).not.toBeNull();
    // The no-action deal never gets a row.
    expect(within(panel).queryByText('บริษัท ซี จำกัด')).toBeNull();
  });

  it('sorts the worklist overdue-first', async () => {
    renderOverview();
    const worklist = await screen.findByText('สิ่งที่ต้องทำ');
    const panel = worklist.closest('section');
    const names = within(panel).getAllByText(/บริษัท (เอ|บี) จำกัด/).map((el) => el.textContent);
    expect(names[0]).toBe('บริษัท เอ จำกัด');
  });

  it('never renders SalesTabs (no pipeline tabs on the money Overview)', async () => {
    renderOverview();
    await screen.findByText('สิ่งที่ต้องทำ');
    expect(screen.queryByLabelText('งานขาย (Sales)')).toBeNull();
    expect(screen.queryByText('ดีลทั้งหมด')).toBeNull();
  });
});
