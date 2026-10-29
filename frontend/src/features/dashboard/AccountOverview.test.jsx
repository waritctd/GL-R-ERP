import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { cleanup, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AccountOverview } from './AccountOverview.jsx';
import { api } from '../../api/index.js';
import { AccountFinancePage } from '../finance/AccountFinancePage.jsx';
import { DEAL_STAGE_CATALOG } from '../../data/dealStageCatalog.js';
import { closedDemoDeal } from '../../test/fixtures/dashboardMoney.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: { list: vi.fn() },
      meta: { dealStages: vi.fn() },
      dashboard: { summary: vi.fn() },
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

function renderOverview(dashboardSummary) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/']}>
        <AccountOverview user={accountUser} employee={employee} dashboardSummary={dashboardSummary} showToast={vi.fn()} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('AccountOverview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.meta.dealStages.mockResolvedValue(DEAL_STAGE_CATALOG);
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
    // commissionPending bucket: account records the invoice; the commission itself is not account's to issue.
    expect(screen.getByText('บันทึกใบกำกับ')).not.toBeNull();
    expect(screen.queryByText(/ออกค่าคอม/)).toBeNull();
    // Overdue bucket carries the overdue ticket's outstanding balance (฿100,000.00).
    expect(screen.getAllByText('฿100,000.00').length).toBeGreaterThan(0);
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
  // Serve `tickets` from the scoped list and `closedPaid` from the CLOSED_PAID query.
  function serve(tickets, closedPaid = []) {
    api.tickets.list.mockImplementation((params = {}) => Promise.resolve(
      { tickets: params.salesStage === 'CLOSED_PAID' ? closedPaid : tickets },
    ));
  }
  const tile = (label) => screen.getByText(label).closest('.rounded-md').textContent;
  const summaryRow = (label) => screen.getByText(label).nextSibling.textContent;

  it('รอรับมัดจำ shows the issued deposit notice total, not the whole-deal outstanding', async () => {
    serve([{ ...depositTicket, amountPayable: 213545.25, amountOutstanding: 213545.25, depositDue: 85418.10 }]);
    renderOverview();
    await screen.findByText('รอปิดงาน');
    expect(tile('รอรับมัดจำ')).toContain('฿85,418.10');
    expect(tile('รอรับมัดจำ')).toContain('1 ดีล');
    expect(tile('รอรับมัดจำ')).not.toContain('213,545.25');
  });

  it('รอชำระส่วนที่เหลือ counts a deposit-paid deal the to-do offers รับชำระส่วนที่เหลือ for', async () => {
    const depositPaid = {
      ...depositTicket, id: 104, code: 'PR-2026-0104', customerName: 'บริษัท ดี จำกัด',
      paymentStatus: 'DEPOSIT_PAID', salesStage: 'DELIVERY_SCHEDULING', amountOutstanding: 40000,
    };
    // Deposit-bypassed deals are offered the same step.
    const bypassed = {
      ...noActionTicket, id: 105, code: 'PR-2026-0105', customerName: 'บริษัท อี จำกัด',
      depositPolicy: 'CREDIT_CUSTOMER', paymentStatus: null, amountOutstanding: 25000,
    };
    serve([depositPaid, bypassed]);
    renderOverview();
    await screen.findByText('รอปิดงาน');
    const todo = screen.getByText('สิ่งที่ต้องทำ').closest('section');
    const offered = within(todo).getAllByText('รับชำระส่วนที่เหลือ').length;
    expect(offered).toBe(2);
    expect(tile('รอชำระส่วนที่เหลือ')).toContain(`${offered} ดีล`);
  });

  it('สรุปเดือนนี้ รับแล้ว (เดือนนี้) is the summary cashReceivedThisMonth, not a guess from updatedAt', async () => {
    // amountPaid on a deal updated this month is what the old guess would have summed.
    serve([{ ...overdueTicket, updatedAt: new Date().toISOString() }]);
    renderOverview({ cashReceivedThisMonth: 321000.5 });
    await screen.findByText('สรุปเดือนนี้');
    expect(summaryRow('รับแล้ว (เดือนนี้)')).toBe('฿321,000.50');
  });

  it('ค้างรับ is the summary\'s server total, not a sum of the loaded rows', async () => {
    // rows would add up to ฿150,000.00 (the closed DEMO-TKT-05 deal and the no-to-do deal are not counted either way)
    serve([overdueTicket, depositTicket, noActionTicket], [closedDemoDeal]);
    renderOverview({ financeOutstanding: 777000.5 });
    await screen.findByText('สรุปเดือนนี้');
    expect(summaryRow('ค้างรับ')).toBe('฿777,000.50');
  });

  it('ค้างรับ shows an em dash while the summary has no total', async () => {
    serve([overdueTicket, depositTicket, noActionTicket], [closedDemoDeal]);
    renderOverview({});
    await screen.findByText('สรุปเดือนนี้');
    expect(summaryRow('ค้างรับ')).toBe('—');
  });

  it('ค้างรับ equals the ค้างรับทั้งหมด on AccountFinancePage: both read the summary\'s total', async () => {
    serve([overdueTicket, depositTicket, noActionTicket], [closedDemoDeal]);
    api.dashboard.summary.mockResolvedValue({ summary: { financeOutstanding: 777000.5 } });
    renderOverview({ financeOutstanding: 777000.5 });
    await screen.findByText('สรุปเดือนนี้');
    const dashboardValue = summaryRow('ค้างรับ');
    expect(dashboardValue).toBe('฿777,000.50');
    cleanup();

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/finance']}>
          <AccountFinancePage user={accountUser} showToast={vi.fn()} />
        </MemoryRouter>
      </QueryClientProvider>,
    );
    const subtitle = await screen.findByText(/ค้างรับทั้งหมด ฿/);
    expect(subtitle.textContent).toContain(`ค้างรับทั้งหมด ${dashboardValue}`);
  });
});
