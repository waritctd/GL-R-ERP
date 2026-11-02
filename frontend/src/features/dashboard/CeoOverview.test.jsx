import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CeoOverview } from './CeoOverview.jsx';
import { api } from '../../api/index.js';
import { bangkokMonthStartIso } from '../../utils/format.js';
import {
  CASH_BY_REP, CASH_RECEIVED_THIS_MONTH, EXPECTED, SERVER_TOTALS, expectFigure, figureRows,
} from '../../test/fixtures/dashboardMoney.js';

globalThis.React = React;

// This component reads five endpoints the CEO already has full access to
// (see routes.js: canViewTickets/canViewCommissions/canViewPricingRequestQueue/
// canViewAllOvertime/canViewAllLeave all include 'ceo') and never calls a
// mutation itself — every row's CTA is a route change, asserted below via a
// real MemoryRouter navigation, not a mocked useNavigate. Per CLAUDE.md, this
// is UI-level only: it proves the component derives the right rows/routes
// from a given API response shape, not that the Java service enforces
// anything — there is no authz change in this branch to verify.
vi.mock('../../api/index.js', () => ({
  api: {
    tickets: { list: vi.fn() },
    pricingRequests: { queue: vi.fn() },
    commissions: { list: vi.fn() },
    overtime: { list: vi.fn() },
    leave: { list: vi.fn() },
  },
}));

const monthStart = bangkokMonthStartIso();

const ceoUser = { id: 1, employeeId: 999, name: 'ผู้บริหาร ทดสอบ', role: 'ceo' };
const ceoEmployee = { nameTh: 'สมศักดิ์ ผู้บริหาร', nickName: 'บอส' };

const dashboardSummary = {
  tickets: { closedThisMonth: 4 },
  headcount: { active: 42 },
  attendance: { lateToday: 3 },
  cashReceivedThisMonth: CASH_RECEIVED_THIS_MONTH,
  // This month's cash split per sales rep: the two add up to CASH_RECEIVED_THIS_MONTH (125,000.50).
  cashReceivedByRep: CASH_BY_REP,
  ...SERVER_TOTALS,
};

const never = () => new Promise(() => {});

function leaveRequest(id) {
  return {
    id, employeeName: `พนักงานลา ${id}`, leaveTypeNameTh: 'ลากิจ',
    startDate: '2026-07-25', endDate: '2026-07-26', managerEmployeeId: 999,
  };
}

function mockAllData() {
  api.pricingRequests.queue.mockResolvedValue({
    items: [
      { id: 501, status: 'READY_FOR_CEO_REVIEW', customerName: 'บริษัท ทดสอบราคา', requestCode: 'PR-501', ticketCode: 'TK-501' },
      { id: 502, status: 'CEO_REVIEWING', customerName: 'บริษัท สองราคา', requestCode: 'PR-502', ticketCode: 'TK-502' },
      { id: 503, status: 'SUBMITTED', customerName: 'บริษัท ยังไม่ถึง CEO', requestCode: 'PR-503', ticketCode: 'TK-503' },
    ],
  });
  api.tickets.list.mockResolvedValue({
    tickets: [
      {
        id: 701, code: 'TK-701', customerName: 'บริษัท รอตรวจปิดงาน', title: 'โครงการ 701',
        status: 'quotation_issued', lifecycle: 'ACTIVE',
        closeConfirmedAt: '2026-07-20T00:00:00.000Z', closeConfirmedByName: 'บัญชี คนดี',
        amountPayable: 100000, amountPaid: 100000, amountOutstanding: 0, overdue: false,
        closedAt: null, createdByName: 'สมชาย ขายดี',
      },
      {
        id: 702, code: 'TK-702', customerName: 'บริษัท ปิดแล้วเดือนนี้', title: 'โครงการ 702',
        status: 'closed', lifecycle: 'COMPLETED',
        closeConfirmedAt: null, closeConfirmedByName: null,
        amountPayable: 50000, amountPaid: 50000, amountOutstanding: 0, overdue: false,
        closedAt: monthStart, createdByName: 'สมหญิง ขายเก่ง',
      },
      {
        id: 703, code: 'TK-703', customerName: 'บริษัท ค้างชำระเกินกำหนด', title: 'โครงการ 703',
        status: 'quotation_issued', lifecycle: 'ACTIVE',
        closeConfirmedAt: null, closeConfirmedByName: null,
        amountPayable: 20000, amountPaid: 5000, amountOutstanding: 15000, overdue: true,
        closedAt: null, createdByName: 'สมชาย ขายดี',
      },
    ],
  });
  api.commissions.list.mockResolvedValue({
    commissions: [
      { id: 301, status: 'MANAGER_APPROVED', salesRepName: 'สมชาย ขายดี', kind: 'SALE' },
      { id: 303, status: 'MANAGER_APPROVED', salesRepName: 'สมชาย ขายดี', kind: 'ADJUSTMENT' },
      { id: 304, status: 'MANAGER_APPROVED', salesRepName: 'สมหญิง ขายเก่ง', kind: 'STOCK_BONUS' },
      { id: 302, status: 'SUBMITTED', salesRepName: 'ยังไม่ถึง CEO', kind: 'SALE' },
    ],
  });
  api.overtime.list.mockImplementation((params = {}) => {
    if (params.status === 'MANAGER_APPROVED') {
      return Promise.resolve({
        requests: [
          { id: 201, employeeName: 'พนักงาน โอทีผ่านผู้จัดการ', workDate: '2026-07-20', managerEmployeeId: 55, hasManagerApprover: true },
        ],
      });
    }
    if (params.status === 'SUBMITTED') {
      return Promise.resolve({
        requests: [
          // No manager stage at all -> the CEO reviews it directly. Note reports_to (55) is NOT the
          // CEO: under the old reports_to-based rule this row was invisible to them, which is the
          // regression the server-side routing change would otherwise have left in place.
          { id: 202, employeeName: 'พนักงาน ไม่มีผู้จัดการ', workDate: '2026-07-21', managerEmployeeId: 55, hasManagerApprover: false },
          // Has a ผู้จัดการ, so it is the manager's to review first — must stay off the CEO's list
          // even though reports_to happens to point straight at the CEO.
          { id: 203, employeeName: 'พนักงาน มีผู้จัดการอื่น', workDate: '2026-07-21', managerEmployeeId: 999, hasManagerApprover: true },
        ],
      });
    }
    return Promise.resolve({ requests: [] });
  });
  api.leave.list.mockImplementation((params = {}) => {
    if (params.status === 'SUBMITTED') {
      return Promise.resolve({
        requests: [
          leaveRequest(401),
          // CEO leave-approval reach (2026-09-01): managerEmployeeId here is someone OTHER than the
          // CEO (55, not 999) -- kept in the fixture specifically to prove leaveRows no longer
          // filters by FK-manager match. REVIEW_ALL_ROLES gaining "ceo" means the CEO can review
          // EVERY ใบลา.
          { id: 402, employeeName: 'พนักงาน ลามีผู้จัดการอื่น', managerEmployeeId: 55 },
        ],
      });
    }
    return Promise.resolve({ requests: [] });
  });
}

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location-probe">{location.pathname}{location.search}</div>;
}

let lastClient;

function renderOverview(summary = dashboardSummary) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  lastClient = queryClient;

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route
            path="/"
            element={(
              <CeoOverview user={ceoUser} employee={ceoEmployee} dashboardSummary={summary} showToast={vi.fn()} />
            )}
          />
          <Route path="*" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

// No @testing-library/jest-dom in this project (see src/test/setup.js) — plain
// .textContent assertions instead of toHaveTextContent, matching every other
// test file in this repo.
function probeText() {
  return screen.getByTestId('location-probe').textContent;
}

const group = (key) => screen.getByTestId(`worklist-group-${key}`);
const findGroup = (key) => screen.findByTestId(`worklist-group-${key}`);

describe('CeoOverview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockAllData();
  });

  it('fetches each worklist source once, with the same filters as before', async () => {
    renderOverview();
    await findGroup('price');

    expect(api.pricingRequests.queue).toHaveBeenCalledWith({ activeOnly: true });
    expect(api.tickets.list).toHaveBeenCalledWith({});
    expect(api.commissions.list).toHaveBeenCalledTimes(1);
    expect(api.overtime.list).toHaveBeenCalledWith({ status: 'MANAGER_APPROVED' });
    expect(api.overtime.list).toHaveBeenCalledWith({ status: 'SUBMITTED' });
    expect(api.leave.list).toHaveBeenCalledWith({ status: 'SUBMITTED' });
  });

  describe('loading (progressive, never all-or-nothing)', () => {
    it('shows the money band from the dashboard summary while every worklist query is still loading', () => {
      [api.tickets.list, api.pricingRequests.queue, api.commissions.list, api.overtime.list, api.leave.list]
        .forEach((fn) => fn.mockImplementation(never));
      renderOverview();

      // Synchronous: no await. The summary is already in hand, so the figures render on the first paint.
      expectFigure('เงินรับแล้ว (เดือนนี้)', EXPECTED.cash);
      expectFigure('ค้างรับ', EXPECTED.outstanding);
      expectFigure('ยอดคาดการณ์', EXPECTED.forecast);
      expect(screen.getByText('ทีมงานวันนี้')).not.toBeNull();
    });

    it('renders a group as soon as its own query lands, while a slow one (the deal list) keeps loading', async () => {
      api.tickets.list.mockImplementation(never);
      renderOverview();

      const price = await findGroup('price');
      expect(within(price).getByTestId('worklist-row-price-501')).not.toBeNull();
      expect((await findGroup('leave')).textContent).toContain('พนักงานลา 401');
      // Only the deal-list-backed parts wait: the ปิดงาน group and the overdue figure.
      expect(group('close').getAttribute('aria-busy')).toBe('true');
      expect(figureRows('เกินกำหนดชำระ')[0]).not.toContain('฿');
      // The total would be a partial sum here, so it waits for every source.
      expect(screen.queryByTestId('worklist-total')).toBeNull();
    });

    it('keeps what it already shows when a background refetch fails', async () => {
      renderOverview();
      await screen.findByTestId('worklist-row-close-701');
      api.tickets.list.mockRejectedValue(new Error('offline'));
      await act(async () => {
        await lastClient.refetchQueries();
        await new Promise((resolve) => { setTimeout(resolve, 20); }); // let the error notification render
      });
      // The refetch really failed (React Query keeps the last data alongside the error).
      expect(lastClient.getQueryCache().findAll().some((q) => q.state.status === 'error')).toBe(true);

      expect(screen.getByTestId('worklist-row-close-701')).not.toBeNull();
      expectFigure('เกินกำหนดชำระ', '฿15,000.00');
      expect(screen.queryByText('โหลดไม่สำเร็จ')).toBeNull();
    });

    it('shows a failed source as failed instead of loading forever, and the rest still render', async () => {
      api.tickets.list.mockRejectedValue(new Error('boom'));
      renderOverview();

      await findGroup('price');
      const close = await screen.findByText('โหลดไม่สำเร็จ');
      expect(close.closest('[data-testid="worklist-group-close"]').getAttribute('aria-busy')).toBeNull();
      expectFigure('เกินกำหนดชำระ', '—');
      expect(screen.getByTestId('worklist-row-leave-401')).not.toBeNull();
    });
  });

  describe('money band', () => {
    it('shows the same three money figures as /finance and the sales-manager dashboard', () => {
      renderOverview();

      expectFigure('เงินรับแล้ว (เดือนนี้)', EXPECTED.cash);
      expectFigure('ค้างรับ', EXPECTED.outstanding);
      expectFigure('ยอดคาดการณ์', EXPECTED.forecast);
    });

    it('shows overdue receivables from the overdue deals only', async () => {
      renderOverview();
      await findGroup('close');

      expectFigure('เกินกำหนดชำระ', '฿15,000.00');
    });

    it('shows an em dash for ค้างรับ and ยอดคาดการณ์ when the summary has no totals, never a sum of rows', () => {
      renderOverview({ ...dashboardSummary, financeOutstanding: undefined, forecastValue: undefined });

      expectFigure('ค้างรับ', '—');
      expectFigure('ยอดคาดการณ์', '—');
    });

    it('drops the four count tiles and the old closed-sales / pipeline figures', async () => {
      renderOverview();
      await findGroup('price');

      expect(document.querySelector('.stat-card')).toBeNull();
      ['อนุมัติราคา', 'ค่าคอมรออนุมัติ', 'OT·ลา รออนุมัติ', 'ยอดขายเดือนนี้', 'ยอดขายปิดแล้ว', 'Pipeline บริษัท']
        .forEach((label) => expect(screen.queryByText(label), label).toBeNull());
      expect(screen.queryByText(/Close verifications|Manager approved|Final approvals/)).toBeNull();
    });
  });

  describe('รอคุณตัดสินใจ worklist', () => {
    it('groups in money-blocking order with a count per group and a total badge', async () => {
      renderOverview();
      await findGroup('close');

      const order = [...document.querySelectorAll('[data-testid^="worklist-group-"]')]
        .map((el) => el.dataset.testid.replace('worklist-group-', ''));
      expect(order).toEqual(['price', 'close', 'commission', 'ot', 'leave']);

      expect(group('price').textContent).toContain('2 รายการ');
      expect(group('close').textContent).toContain('1 รายการ');
      expect(group('commission').textContent).toContain('3 รายการ');
      expect(group('ot').textContent).toContain('2 รายการ');
      expect(group('leave').textContent).toContain('2 รายการ');
      const total = screen.getByTestId('worklist-total');
      expect(total.textContent).toBe('10');
      expect(total.getAttribute('aria-label')).toBe('รวม 10 รายการ');
    });

    it('keeps only the rows the CEO can act on', async () => {
      renderOverview();
      await findGroup('close');

      expect(screen.getByTestId('worklist-row-price-501')).not.toBeNull();
      expect(screen.getByTestId('worklist-row-price-502')).not.toBeNull();
      expect(screen.queryByTestId('worklist-row-price-503')).toBeNull();

      expect(screen.getByTestId('worklist-row-close-701')).not.toBeNull();
      expect(screen.queryByTestId('worklist-row-close-702')).toBeNull();

      expect(screen.getByTestId('worklist-row-ot-201')).not.toBeNull();
      expect(screen.getByTestId('worklist-row-ot-202')).not.toBeNull();
      expect(screen.queryByTestId('worklist-row-ot-203')).toBeNull();

      expect(screen.getByTestId('worklist-row-leave-401')).not.toBeNull();
      expect(screen.getByTestId('worklist-row-leave-402')).not.toBeNull();
    });

    it('shows at most three rows per group and links the rest to the owning page', async () => {
      api.leave.list.mockResolvedValue({ requests: [401, 402, 403, 404, 405].map(leaveRequest) });
      renderOverview();
      const leave = await findGroup('leave');

      expect(leave.querySelectorAll('[data-testid^="worklist-row-leave-"]')).toHaveLength(3);
      expect(leave.textContent).toContain('5 รายการ');
      fireEvent.click(within(leave).getByRole('button', { name: /อีก 2 รายการ/ }));

      await screen.findByTestId('location-probe');
      expect(probeText()).toBe('/leave');
    });

    it('opens the owning page from a group\'s ดูทั้งหมด', async () => {
      renderOverview();
      const ot = await findGroup('ot');

      fireEvent.click(within(ot).getByRole('button', { name: /ดูทั้งหมด/ }));

      await screen.findByTestId('location-probe');
      expect(probeText()).toBe('/employee-requests?tab=ot');
    });

    it('collapses every commission awaiting the CEO into one row that opens the approval queue', async () => {
      renderOverview();
      const commission = await findGroup('commission');

      const row = within(commission).getByTestId('worklist-row-commission');
      // Each rep once, however many of their records wait; the SUBMITTED one is the manager's, not here.
      expect(row.textContent).toContain('สมชาย ขายดี, สมหญิง ขายเก่ง');
      expect(row.textContent).not.toContain('ยังไม่ถึง CEO');
      expect(row.textContent).toContain('ค่าคอมขาย');
      expect(row.textContent).toContain('ปรับปรุง/รับช่วงงาน');
      expect(row.textContent).toContain('โบนัสขายสต็อก');
      // The one row already stands for all three records: nothing is hidden behind "อีก N".
      expect(within(commission).queryByRole('button', { name: /อีก/ })).toBeNull();

      fireEvent.click(within(row).getByRole('button', { name: 'เปิดหน้าอนุมัติ' }));
      await screen.findByTestId('location-probe');
      expect(probeText()).toBe('/commissions?view=pending');
    });

    it('says so when nothing waits on the CEO', async () => {
      api.pricingRequests.queue.mockResolvedValue({ items: [] });
      api.tickets.list.mockResolvedValue({ tickets: [] });
      api.commissions.list.mockResolvedValue({ commissions: [] });
      api.overtime.list.mockResolvedValue({ requests: [] });
      api.leave.list.mockResolvedValue({ requests: [] });
      renderOverview();

      expect(await screen.findByText('ไม่มีรายการที่รอการตัดสินใจจากคุณตอนนี้')).not.toBeNull();
      expect(document.querySelector('[data-testid^="worklist-group-"]')).toBeNull();
    });

    it.each([
      ['price-501', 'ตั้งราคา', '/pricing-requests/501'],
      ['close-701', 'ตรวจปิดงาน', '/tickets/701'],
      ['ot-201', 'อนุมัติ', '/employee-requests?tab=ot'],
      ['leave-401', 'อนุมัติ', '/leave'],
    ])('deep-links the %s row CTA instead of mutating inline', async (rowId, cta, path) => {
      renderOverview();
      const row = await screen.findByTestId(`worklist-row-${rowId}`);

      fireEvent.click(within(row).getByRole('button', { name: cta }));

      await screen.findByTestId('location-probe');
      expect(probeText()).toBe(path);
    });
  });

  describe('side panels', () => {
    it('splits this month\'s cash per sales rep, largest first, adding up to เงินรับแล้ว, with no invented target', () => {
      renderOverview();

      const panel = screen.getByText('เงินรับตามพนักงานขาย').closest('section');
      const rows = [...panel.querySelectorAll('[data-testid="rep-row"]')];
      expect(rows.map((el) => el.querySelector('[data-testid="rep-name"]').textContent))
        .toEqual(['สมชาย ขายดี', 'สมหญิง ขายเก่ง']);
      const total = rows
        .map((el) => Number(el.querySelector('[data-testid="rep-amount"]').textContent.replace(/[฿,]/g, '')))
        .reduce((sum, n) => sum + n, 0);
      expect(total).toBeCloseTo(CASH_RECEIVED_THIS_MONTH, 2);
      expect(within(panel).queryByText(/%/)).toBeNull();
    });

    it('lists every rep, even more than eight', () => {
      const reps = Array.from({ length: 9 }, (_, i) => ({ repName: `เซลส์ ${i + 1}`, amount: 1000 }));
      renderOverview({ ...dashboardSummary, cashReceivedThisMonth: 9000, cashReceivedByRep: reps });

      const panel = screen.getByText('เงินรับตามพนักงานขาย').closest('section');
      expect(panel.querySelectorAll('[data-testid="rep-row"]')).toHaveLength(9);
    });

    it('explains the empty rep panel', () => {
      renderOverview({ ...dashboardSummary, cashReceivedByRep: [] });

      const panel = screen.getByText('เงินรับตามพนักงานขาย').closest('section');
      expect(within(panel).getByText('ยังไม่มีเงินรับเข้าเดือนนี้')).not.toBeNull();
    });

    it('shows headcount and lateness under ทีมงานวันนี้', () => {
      renderOverview();

      expectFigure('กำลังพล', '42 คน');
      expectFigure('มาสายวันนี้', '3 คน');
    });
  });
});
