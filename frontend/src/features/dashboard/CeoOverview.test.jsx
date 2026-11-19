import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CeoOverview } from './CeoOverview.jsx';
import { api } from '../../api/index.js';
import { bangkokMonthStartIso } from '../../utils/format.js';
import {
  CASH_BY_REP, CASH_RECEIVED_THIS_MONTH, EXPECTED, SERVER_TOTALS, expectFigure, figureRows,
} from '../../test/fixtures/dashboardMoney.js';

globalThis.React = React;

// This component reads five endpoints the CEO already has full access to and never calls a mutation itself —
// every row's CTA is a route change, asserted below via a real MemoryRouter navigation, not a mocked
// useNavigate.
vi.mock('../../api/index.js', () => ({
  api: {
    tickets: { list: vi.fn() },
    pricingRequests: { queue: vi.fn() },
    commissions: { list: vi.fn(), pendingApproval: vi.fn() },
    overtime: { list: vi.fn() },
    leave: { list: vi.fn() },
    salesBoard: { teamOverview: vi.fn() },
    salesAnalytics: { snapshot: vi.fn() },
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

function leaveRequest(id) {
  return {
    id, employeeName: `พนักงานลา ${id}`, leaveTypeNameTh: 'ลากิจ',
    startDate: '2026-07-25', endDate: '2026-07-26', managerEmployeeId: 999,
  };
}

function mockAllData() {
  api.salesBoard.teamOverview.mockResolvedValue({
    quietDays: 14,
    reps: [{ employeeId: 21, name: 'สมชาย ขายดี', openDeals: 3, openValue: 450000, quietDeals: 1, lastUpdateAt: null }],
    stale: [],
  });
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
  // The CEO's queue is what the approval page shows: SALE records from pending-approval (any month)
  // plus this round's manual entries (inserted MANAGER_APPROVED) from the month's list.
  api.commissions.pendingApproval.mockResolvedValue({
    commissions: [{ commission: { id: 301, status: 'MANAGER_APPROVED', salesRepName: 'สมชาย ขายดี', kind: 'SALE' }, ticketCode: 'TK-301' }],
  });
  api.commissions.list.mockResolvedValue({
    commissions: [
      { id: 303, status: 'MANAGER_APPROVED', salesRepName: 'สมชาย ขายดี', kind: 'ADJUSTMENT' },
      { id: 304, status: 'MANAGER_APPROVED', salesRepName: 'สมหญิง ขายเก่ง', kind: 'STOCK_BONUS' },
      // Already in pending-approval: never counted twice.
      { id: 301, status: 'MANAGER_APPROVED', salesRepName: 'สมชาย ขายดี', kind: 'SALE' },
      { id: 302, status: 'SUBMITTED', salesRepName: 'ยังไม่ถึง CEO', kind: 'SALE' },
      // Manual entries already decided (or rejected) are not waiting on the CEO.
      { id: 305, status: 'APPROVED', salesRepName: 'อนุมัติแล้ว', kind: 'INCENTIVE' },
      { id: 306, status: 'REJECTED', salesRepName: 'ถูกปฏิเสธ', kind: 'ADJUSTMENT' },
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
          // No manager stage at all -> the CEO reviews it directly. Note reports_to (55) is NOT the CEO: under the old
          // reports_to-based rule this row was invisible to them, which is the regression the server-side routing change
          // would otherwise have left in place.
          { id: 202, employeeName: 'พนักงาน ไม่มีผู้จัดการ', workDate: '2026-07-21', managerEmployeeId: 55, hasManagerApprover: false },
          // Has a ผู้จัดการ, so it is the manager's to review first — must stay off the CEO's list even though
          // reports_to happens to point straight at the CEO.
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

function SearchProbe() {
  return <div data-testid="search-probe">{useLocation().search}</div>;
}

function renderOverview(summary = dashboardSummary, entry = '/') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[entry]}>
        <Routes>
          <Route
            path="/"
            element={(
              <>
                <CeoOverview user={ceoUser} employee={ceoEmployee} dashboardSummary={summary} showToast={vi.fn()} />
                <SearchProbe />
              </>
            )}
          />
          <Route path="*" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const findGroup = (key) => screen.findByTestId(`worklist-group-${key}`);

describe('CeoOverview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockAllData();
  });

  describe('money band', () => {
    it('shows the same three money figures as /finance and the sales-manager dashboard', () => {
      renderOverview();

      expectFigure('เงินรับแล้ว (เดือนนี้)', EXPECTED.cash);
      expectFigure('ค้างรับ', EXPECTED.outstanding);
      expectFigure('ยอดคาดการณ์', EXPECTED.forecast);
    });

    it('shows the server\'s overdue total, never a sum of the 500-row deal list', async () => {
      renderOverview();
      await findGroup('close');

      // The one overdue deal in the list owes 15,000; the server total covers every deal.
      expectFigure('เกินกำหนดชำระ', EXPECTED.overdue);
      expect(figureRows('เกินกำหนดชำระ')[0]).not.toContain('฿15,000.00');
    });
  });

  describe('รอคุณตัดสินใจ worklist', () => {
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
  });
});
