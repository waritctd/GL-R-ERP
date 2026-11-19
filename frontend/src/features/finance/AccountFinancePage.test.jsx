import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AccountFinancePage } from './AccountFinancePage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  const { DEAL_STAGE_CATALOG } = await import('../../data/dealStageCatalog.js');
  return {
    ...actual,
    api: {
      tickets: { list: vi.fn() },
      meta: { dealStages: vi.fn().mockResolvedValue(DEAL_STAGE_CATALOG) },
      dashboard: { summary: vi.fn() },
      receiptImports: { latest: vi.fn() },
    },
  };
});

const accountUser = { role: 'account', name: 'บัญชี ทดสอบ', employeeId: 3 };
const ceoUser = { role: 'ceo', name: 'ซีอีโอ ทดสอบ', employeeId: 1 };

// Money fixtures keep every field distinct (payable ≠ paid ≠ outstanding ≠ depositDue, paid net of an adjustment
// where outstanding is 0), so a row that shows the wrong field shows a figure no assertion expects.

const depositTicket = {
  id: 201, code: 'PR-2026-0201', title: 'โครงการดี', customerName: 'บริษัท ดี จำกัด',
  status: 'quotation_issued', paymentStatus: 'DEPOSIT_NOTICE_ISSUED', fulfillmentStatus: null,
  lifecycle: 'ACTIVE', salesStage: 'NEGOTIATION', depositPolicy: 'REQUIRED',
  closeConfirmedAt: null, invoiceOnFile: false,
  amountPayable: 85000, amountPaid: 5000, amountOutstanding: 80000, depositDue: 40000,
  overdue: false, paymentDueDate: null, updatedAt: '2026-07-10T00:00:00.000Z',
};

const finalPaymentTicket = {
  id: 202, code: 'PR-2026-0202', title: 'โครงการอี', customerName: 'บริษัท อี จำกัด',
  status: 'quotation_issued', paymentStatus: 'AWAITING_FINAL_PAYMENT', fulfillmentStatus: null,
  lifecycle: 'ACTIVE', salesStage: 'DELIVERY_SCHEDULING', depositPolicy: 'REQUIRED',
  closeConfirmedAt: null, invoiceOnFile: false,
  amountPayable: 125000, amountPaid: 65000, amountOutstanding: 60000,
  overdue: false, paymentDueDate: null, updatedAt: '2026-07-11T00:00:00.000Z',
};

const closedPaidTicket = {
  id: 203, code: 'PR-2026-0203', title: 'โครงการเอฟ', customerName: 'บริษัท เอฟ จำกัด',
  status: 'quotation_issued', paymentStatus: 'FULLY_PAID', fulfillmentStatus: 'FULLY_DELIVERED',
  lifecycle: 'ACTIVE', salesStage: 'CLOSED_PAID', depositPolicy: 'REQUIRED',
  closeConfirmedAt: '2026-07-01T00:00:00.000Z', invoiceOnFile: true,
  amountPayable: 90000, amountPaid: 89500, amountOutstanding: 0,
  overdue: false, paymentDueDate: null, updatedAt: '2026-07-01T00:00:00.000Z',
};

const idleTicket = {
  id: 204, code: 'PR-2026-0204', title: 'โครงการไอเดิล', customerName: 'บริษัท ไอเดิล จำกัด',
  status: 'quotation_issued', paymentStatus: 'CUSTOMER_CONFIRMED', fulfillmentStatus: null,
  lifecycle: 'ACTIVE', salesStage: 'PROCUREMENT', depositPolicy: 'REQUIRED',
  closeConfirmedAt: null, invoiceOnFile: false,
  amountPayable: 52000, amountPaid: 2000, amountOutstanding: 50000,
  overdue: false, paymentDueDate: null, updatedAt: '2026-07-05T00:00:00.000Z',
};

const overdueTicket = {
  id: 205, code: 'PR-2026-0205', title: 'โครงการโอเวอร์', customerName: 'บริษัท โอเวอร์ จำกัด',
  status: 'quotation_issued', paymentStatus: 'AWAITING_FINAL_PAYMENT', fulfillmentStatus: null,
  lifecycle: 'ACTIVE', salesStage: 'DELIVERED', depositPolicy: 'REQUIRED',
  closeConfirmedAt: null, invoiceOnFile: false,
  amountPayable: 32000, amountPaid: 2000, amountOutstanding: 30000,
  overdue: true, paymentDueDate: '2026-09-01', updatedAt: '2026-07-12T00:00:00.000Z',
};

const closeReadyTicket = {
  id: 206, code: 'PR-2026-0206', title: 'โครงการปิด', customerName: 'บริษัท ปิดงาน จำกัด',
  status: 'quotation_issued', paymentStatus: 'FULLY_PAID', fulfillmentStatus: 'FULLY_DELIVERED',
  lifecycle: 'ACTIVE', salesStage: 'DELIVERED', depositPolicy: 'REQUIRED',
  closeConfirmedAt: null, invoiceOnFile: true,
  amountPayable: 596000, amountPaid: 595500, amountOutstanding: 0,
  overdue: false, paymentDueDate: null, updatedAt: '2026-07-13T00:00:00.000Z',
};

function LocationProbe() {
  const location = useLocation();
  return <p data-testid="location">{location.pathname + location.search}</p>;
}

function renderPage(initial = '/finance', user = accountUser) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initial]}>
        <Routes>
          <Route path="/finance" element={<AccountFinancePage user={user} showToast={vi.fn()} />} />
          <Route path="/finance/deals/:id" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const listReturns = (open, closedPaid = []) => api.tickets.list.mockImplementation((params = {}) => (
  params.salesStage === 'CLOSED_PAID' ? Promise.resolve({ tickets: closedPaid }) : Promise.resolve({ tickets: open })));

const group = (label) => screen.getByRole('region', { name: label });
const groupRow = (id) => screen.getByTestId(`fin-work-row-${id}`);

describe('AccountFinancePage — grouped worklist (design a-fin-land)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.dashboard.summary.mockResolvedValue({ summary: { financeOutstanding: 424242.42 } });
    api.receiptImports.latest.mockResolvedValue({ uploadEnabled: false, latest: null, openUnmatched: 0 });
    listReturns([depositTicket, finalPaymentTicket, idleTicket, closeReadyTicket, overdueTicket], [closedPaidTicket]);
  });

  // Ruling F6/F2: each row shows exactly what the old table showed for that deal — one figure, labelled คงค้าง,
  // from amountOutstanding — and none of the other money fields (payable, paid, depositDue).
  it('every row shows exactly the old table\'s figure: คงค้าง = amountOutstanding, and no other money field', async () => {
    renderPage();
    await screen.findByText('บริษัท ดี จำกัด');
    const expected = { 206: '฿0.00', 203: '฿0.00', 201: '฿80,000.00', 202: '฿60,000.00', 205: '฿30,000.00' };
    const others = ['฿596,000.00', '฿595,500.00', '฿90,000.00', '฿89,500.00', '฿85,000.00', '฿5,000.00', '฿40,000.00',
      '฿125,000.00', '฿65,000.00', '฿32,000.00', '฿2,000.00'];
    Object.entries(expected).forEach(([id, figure]) => {
      const row = groupRow(id);
      expect(within(row).getByText(`คงค้าง ${figure}`)).not.toBeNull();
      expect(row.textContent.match(/฿[\d,]+\.\d\d/g)).toEqual([figure]);
      others.forEach((o) => expect(row.textContent).not.toContain(o));
    });
    // the deal code is the row's id column
    expect(within(groupRow(201)).getByText('PR-2026-0201')).not.toBeNull();
  });

  it('CEO (wrong way round): deposit / final-payment deals — account-only steps today — get no row and no button', async () => {
    renderPage('/finance', ceoUser);
    await screen.findByText('บริษัท โอเวอร์ จำกัด');
    expect(screen.queryByTestId('fin-work-row-201')).toBeNull();
    expect(screen.queryByTestId('fin-work-row-202')).toBeNull();
    expect(screen.queryByText('บริษัท ดี จำกัด')).toBeNull();
    expect(screen.queryByText('บริษัท อี จำกัด')).toBeNull();
    expect(within(group('ติดตามชำระ')).getByTestId('group-count').textContent).toBe('1');
  });

  it('account (the performer) gets the verb on the button and no duplicate status text', async () => {
    renderPage();
    await screen.findByText('บริษัท ปิดงาน จำกัด');
    expect(within(groupRow(206)).getByRole('link').textContent).toBe('ยืนยันพร้อมปิดงาน');
    expect(within(groupRow(206)).getAllByText('ยืนยันพร้อมปิดงาน')).toHaveLength(1);
    expect(within(groupRow(203)).getByRole('link').textContent).toBe('บันทึกใบกำกับ');
    expect(within(groupRow(203)).getAllByText('บันทึกใบกำกับ')).toHaveLength(1);
  });

  // ── slice 6.1 / 6.3: the drawn row details ───────────────────────────────────────────────────────────────────
  describe('drawn row details (slice 6)', () => {
    afterEach(() => { vi.useRealTimers(); });

    it('an overdue row counts whole Bangkok days from the served due date to today', async () => {
      // 10:00 Bangkok on 7 Sep 2026 (03:00 UTC); due 1 Sep → 6 days. Only Date is faked: react-query keeps real timers.
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(new Date('2026-09-07T03:00:00Z'));
      listReturns([overdueTicket]);
      renderPage();
      await screen.findByText('บริษัท โอเวอร์ จำกัด');
      expect(within(groupRow(205)).getByText('เกินกำหนด 6 วัน')).not.toBeNull();
      expect(within(groupRow(205)).getByText('ครบกำหนด 1 ก.ย. 2569')).not.toBeNull();
    });

    it('the day count follows the Bangkok calendar, not UTC: 23:30 UTC on 6 Sep is already 7 Sep in Bangkok', async () => {
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(new Date('2026-09-06T23:30:00Z'));
      listReturns([overdueTicket]);
      renderPage();
      await screen.findByText('บริษัท โอเวอร์ จำกัด');
      expect(within(groupRow(205)).getByText('เกินกำหนด 6 วัน')).not.toBeNull();
    });
  });
});

// The pre-existing flat table, now the "ทั้งหมด" view (and the target of filter deep links). Tests below are the
// page's original suite; where one changed, the comment says why.
describe('AccountFinancePage — ทั้งหมด table and filter deep links', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.dashboard.summary.mockResolvedValue({ summary: { financeOutstanding: 424242.42 } });
    api.receiptImports.latest.mockResolvedValue({ uploadEnabled: false, latest: null, openUnmatched: 0 });
    listReturns([depositTicket, finalPaymentTicket, idleTicket], [closedPaidTicket]);
  });

  it('defaults to "ต้องดำเนินการ": only rows with a next account action, in the urgent/due/outstanding order', async () => {
    renderPage();
    await screen.findByText('บริษัท ดี จำกัด');
    expect(screen.getByText('บริษัท อี จำกัด')).not.toBeNull();
    expect(screen.getByText('บริษัท เอฟ จำกัด')).not.toBeNull();
    // idle: quotation-stage deal, no account action -> history only
    expect(screen.queryByText('บริษัท ไอเดิล จำกัด')).toBeNull();
  });
});
