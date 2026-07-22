import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CeoOverview } from './CeoOverview.jsx';
import { api } from '../../api/index.js';
import { bangkokMonthStartIso } from '../../utils/format.js';

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
};

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
      { id: 302, status: 'SUBMITTED', salesRepName: 'ยังไม่ถึง CEO', kind: 'SALE' },
    ],
  });
  api.overtime.list.mockImplementation((params = {}) => {
    if (params.status === 'MANAGER_APPROVED') {
      return Promise.resolve({
        requests: [
          { id: 201, employeeName: 'พนักงาน โอทีผ่านผู้จัดการ', workDate: '2026-07-20', managerEmployeeId: 55 },
        ],
      });
    }
    if (params.status === 'SUBMITTED') {
      return Promise.resolve({
        requests: [
          // Manager-less division: reports straight to the CEO (managerEmployeeId === ceoUser.employeeId).
          { id: 202, employeeName: 'พนักงาน ไม่มีผู้จัดการ', workDate: '2026-07-21', managerEmployeeId: 999 },
          // Has a manager who is NOT the CEO — must be excluded from the CEO's worklist.
          { id: 203, employeeName: 'พนักงาน มีผู้จัดการอื่น', workDate: '2026-07-21', managerEmployeeId: 55 },
        ],
      });
    }
    return Promise.resolve({ requests: [] });
  });
  api.leave.list.mockImplementation((params = {}) => {
    if (params.status === 'SUBMITTED') {
      return Promise.resolve({
        requests: [
          {
            id: 401, employeeName: 'พนักงาน ลาไม่มีผู้จัดการ', leaveTypeNameTh: 'ลากิจ',
            startDate: '2026-07-25', endDate: '2026-07-26', managerEmployeeId: 999,
          },
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

function renderOverview() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route
            path="/"
            element={(
              <CeoOverview user={ceoUser} employee={ceoEmployee} dashboardSummary={dashboardSummary} showToast={vi.fn()} />
            )}
          />
          <Route path="*" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

// `label` can also match a worklist-row CTA with the same Thai text (e.g.
// "ตรวจปิดงาน" is both the ตรวจปิดงาน pulse tile's label and its row's own
// CTA button) — scope to the element that actually carries the `.stat-label`
// class, not just any element with matching text.
function statCardValue(label) {
  const labelEl = screen.getAllByText(label).find((el) => el.classList.contains('stat-label'));
  const card = labelEl.closest('.stat-card');
  return card.querySelector('.stat-value').textContent;
}

// No @testing-library/jest-dom in this project (see src/test/setup.js) — plain
// .textContent assertions instead of toHaveTextContent, matching every other
// test file in this repo.
function probeText() {
  return screen.getByTestId('location-probe').textContent;
}

describe('CeoOverview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockAllData();
  });

  it('derives the exec pulse counts from the mocked cross-domain data', async () => {
    renderOverview();

    await screen.findByText('อนุมัติราคา');

    // ราคา: only READY_FOR_CEO_REVIEW/CEO_REVIEWING count (SUBMITTED does not).
    expect(statCardValue('อนุมัติราคา')).toBe('2');
    // ปิดงาน: only the ticket with closeConfirmedAt set and still open.
    expect(statCardValue('ตรวจปิดงาน')).toBe('1');
    // ค่าคอม: only MANAGER_APPROVED.
    expect(statCardValue('ค่าคอมรออนุมัติ')).toBe('1');
    // OT (MANAGER_APPROVED + manager-less-direct-to-CEO) + ลา (manager-less-direct-to-CEO) = 2 + 1.
    expect(statCardValue('OT·ลา รออนุมัติ')).toBe('3');
    // ยอดขายเดือนนี้: sum of amountPaid for tickets closed this month (only TK-702).
    expect(statCardValue('ยอดขายเดือนนี้')).toBe('฿50,000');

    expect(api.pricingRequests.queue).toHaveBeenCalledWith({ activeOnly: true });
    expect(api.tickets.list).toHaveBeenCalledWith({});
    expect(api.overtime.list).toHaveBeenCalledWith({ status: 'MANAGER_APPROVED' });
    expect(api.overtime.list).toHaveBeenCalledWith({ status: 'SUBMITTED' });
    expect(api.leave.list).toHaveBeenCalledWith({ status: 'SUBMITTED' });
  });

  it('renders one worklist row per domain and excludes rows the CEO cannot act on', async () => {
    renderOverview();

    await screen.findByText('รออนุมัติจากคุณ');

    // Two price rows (501, 502) — 503 (SUBMITTED) must not appear.
    expect(screen.getByTestId('worklist-row-price-501')).not.toBeNull();
    expect(screen.getByTestId('worklist-row-price-502')).not.toBeNull();
    expect(screen.queryByTestId('worklist-row-price-503')).toBeNull();

    expect(screen.getByTestId('worklist-row-close-701')).not.toBeNull();
    expect(screen.queryByTestId('worklist-row-close-702')).toBeNull();

    expect(screen.getByTestId('worklist-row-commission-301')).not.toBeNull();
    expect(screen.queryByTestId('worklist-row-commission-302')).toBeNull();

    expect(screen.getByTestId('worklist-row-ot-201')).not.toBeNull();
    expect(screen.getByTestId('worklist-row-ot-202')).not.toBeNull();
    expect(screen.queryByTestId('worklist-row-ot-203')).toBeNull();

    expect(screen.getByTestId('worklist-row-leave-401')).not.toBeNull();
    expect(screen.queryByTestId('worklist-row-leave-402')).toBeNull();
  });

  it('deep-links the ราคา CTA to the pricing-request detail page instead of mutating inline', async () => {
    renderOverview();
    const row = await screen.findByTestId('worklist-row-price-501');

    expect(within(row).getByText('ตั้งราคา')).not.toBeNull();
    fireEvent.click(within(row).getByText('ตั้งราคา'));

    await screen.findByTestId('location-probe');
    expect(probeText()).toContain('/pricing-requests/501');
    // No pricing-decision/costing mutation method exists on the mocked api surface at
    // all (see the vi.mock above) — this component genuinely cannot call one.
  });

  it('deep-links the ปิดงาน CTA to the ticket detail page', async () => {
    renderOverview();
    const row = await screen.findByTestId('worklist-row-close-701');

    fireEvent.click(within(row).getByText('ตรวจปิดงาน'));

    await screen.findByTestId('location-probe');
    expect(probeText()).toContain('/tickets/701');
  });

  it('deep-links the ค่าคอม CTA to /commissions', async () => {
    renderOverview();
    const row = await screen.findByTestId('worklist-row-commission-301');

    fireEvent.click(within(row).getByText('อนุมัติ'));

    await screen.findByTestId('location-probe');
    expect(probeText()).toContain('/commissions');
  });

  it('deep-links the OT CTA to the OT tab of the requests page', async () => {
    renderOverview();
    const row = await screen.findByTestId('worklist-row-ot-201');

    fireEvent.click(within(row).getByText('อนุมัติ'));

    await screen.findByTestId('location-probe');
    expect(probeText()).toContain('/employee-requests');
    expect(probeText()).toContain('tab=ot');
  });

  it('deep-links the ลา CTA to /leave', async () => {
    renderOverview();
    const row = await screen.findByTestId('worklist-row-leave-401');

    fireEvent.click(within(row).getByText('อนุมัติ'));

    await screen.findByTestId('location-probe');
    expect(probeText()).toContain('/leave');
  });

  it('renders the company snapshot from the derived ticket figures and dashboardSummary', async () => {
    renderOverview();

    await screen.findByText('ผลบริษัทเดือนนี้');

    expect(screen.getByText('ยอดขายปิดแล้ว').nextSibling.textContent).toBe('฿50,000');
    // Prefers the real dashboardSummary.tickets.closedThisMonth (4) over the
    // client-derived count (1) when the summary provides it.
    expect(screen.getByText('ปิดงานแล้ว').nextSibling.textContent).toBe('4 ดีล');
    // Pipeline: active, not-yet-closed tickets only (701 + 703 = 120,000).
    expect(screen.getByText('Pipeline บริษัท').nextSibling.textContent).toBe('฿120,000');
    // Overdue receivables: only the overdue ticket's outstanding balance (703).
    expect(screen.getByText('ลูกหนี้ค้าง/เกินกำหนด').nextSibling.textContent).toBe('฿15,000');
    expect(screen.getByText('กำลังพล').nextSibling.textContent).toBe('42 คน');
    expect(screen.getByText('มาสายวันนี้').nextSibling.textContent).toBe('3 คน');
  });

  it('renders a per-rep breakdown for deals closed this month, with no invented target/quota', async () => {
    renderOverview();

    await screen.findByText('ยอดตามทีม/ฝ่าย');
    // Only สมหญิง's deal (TK-702) closed this month.
    expect(screen.getByText('สมหญิง ขายเก่ง')).not.toBeNull();
    // No percentage/quota text is rendered anywhere in that panel.
    expect(screen.queryByText(/%/)).toBeNull();
  });
});
