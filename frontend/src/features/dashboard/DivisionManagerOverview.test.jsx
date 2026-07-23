import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { DivisionManagerOverview } from './DivisionManagerOverview.jsx';

globalThis.React = React;

// vi.mock factories are hoisted above imports/top-level consts, so the mock
// fns they close over must themselves be created via vi.hoisted (plain
// top-level `const overtimeList = vi.fn()` would still be in the TDZ when
// the hoisted factory runs).
const { overtimeList, leaveList, attendanceDaily, navigateMock } = vi.hoisted(() => ({
  overtimeList: vi.fn(),
  leaveList: vi.fn(),
  attendanceDaily: vi.fn(),
  navigateMock: vi.fn(),
}));

vi.mock('react-router-dom', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, useNavigate: () => navigateMock };
});

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      overtime: { list: overtimeList },
      leave: { list: leaveList },
      attendance: { daily: attendanceDaily },
    },
  };
});

const manager = { employeeId: 100, name: 'ผู้จัดการ ทดสอบ', role: 'employee', manager: true };
const employee = { nameTh: 'ผู้จัดการ ทดสอบ', positionTh: 'ผู้จัดการฝ่าย', divisionTh: 'ฝ่ายคลังสินค้า' };

// Manager-scoped worklist data: the API already scopes to what this caller may
// review (division-wide for OT, direct-report FK for leave — see mockApi's
// canReviewOvertime/canReviewLeave), including the caller's OWN pending OT
// request (employeeId 100 === manager.employeeId) — the component must drop
// that one since a manager cannot self-approve.
const otRequests = [
  {
    id: 1,
    employeeId: 201,
    employeeName: 'สมชาย ใจดี',
    workDate: '2026-07-23',
    plannedStartAt: '2026-07-23T18:00:00+07:00',
    plannedEndAt: '2026-07-23T20:00:00+07:00',
    status: 'SUBMITTED',
  },
  {
    id: 2,
    employeeId: manager.employeeId,
    employeeName: 'ผู้จัดการ ทดสอบ',
    workDate: '2026-07-23',
    plannedStartAt: '2026-07-23T18:00:00+07:00',
    plannedEndAt: '2026-07-23T20:00:00+07:00',
    status: 'SUBMITTED',
  },
];

const leaveRequests = [
  {
    id: 11,
    employeeId: 202,
    employeeName: 'สมหญิง ใจงาม',
    leaveTypeNameTh: 'ลาพักร้อน',
    startDate: '2026-07-24',
    endDate: '2026-07-24',
    status: 'SUBMITTED',
  },
];

const attendanceDays = [
  { employee_id: 201, employee_name: 'สมชาย ใจดี', check_in: '2026-07-23T08:47:00+07:00', check_out: '2026-07-23T17:32:00+07:00', status: 'LATE' },
  { employee_id: 202, employee_name: 'สมหญิง ใจงาม', check_in: '2026-07-23T08:24:00+07:00', check_out: '2026-07-23T17:35:00+07:00', status: 'PRESENT' },
  { employee_id: 100, employee_name: 'ผู้จัดการ ทดสอบ', check_in: '2026-07-23T08:20:00+07:00', check_out: null, status: 'MISSING_CHECK_OUT' },
];

const dashboardSummary = {
  headcount: { scope: 'division', active: 12, byDivision: [{ divisionName: 'ฝ่ายคลังสินค้า' }] },
};

function renderOverview() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/']}>
        <DivisionManagerOverview user={manager} employee={employee} dashboardSummary={dashboardSummary} showToast={vi.fn()} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('DivisionManagerOverview', () => {
  beforeEach(() => {
    overtimeList.mockReset().mockResolvedValue({ requests: otRequests });
    leaveList.mockReset().mockResolvedValue({ requests: leaveRequests });
    attendanceDaily.mockReset().mockResolvedValue({ days: attendanceDays });
    navigateMock.mockReset();
  });

  it('greets with the division name and requests OT/leave scoped to SUBMITTED', async () => {
    renderOverview();
    expect(screen.getByText(/ภาพรวมทีม/)).not.toBeNull();
    expect(screen.getByText(/ฝ่ายคลังสินค้า/)).not.toBeNull();
    await waitFor(() => {
      expect(overtimeList).toHaveBeenCalledWith({ status: 'SUBMITTED' });
      expect(leaveList).toHaveBeenCalledWith({ status: 'SUBMITTED' });
    });
  });

  it('renders the pulse cards (OT/leave pending, late today, team total), dropping the manager\'s own OT row from the count', async () => {
    renderOverview();
    await waitFor(() => expect(overtimeList).toHaveBeenCalled());

    // StatCard renders as a <button> here (every pulse card has an onClick) —
    // closest('button') is the whole card; label/value are sibling <div>s inside it.
    // Only the non-self OT request (id 1) counts — the manager's own request
    // (id 2, employeeId === manager.employeeId) is excluded (no self-approval).
    const otCard = (await screen.findByText('รออนุมัติ OT')).closest('button');
    expect(within(otCard).getByText('1')).not.toBeNull();

    const leaveCard = screen.getByText('รออนุมัติลา').closest('button');
    expect(within(leaveCard).getByText('1')).not.toBeNull();

    const lateCard = screen.getByText('มาสายวันนี้').closest('button');
    expect(within(lateCard).getByText('1')).not.toBeNull();

    const teamCard = screen.getByText('ทีมทั้งหมด').closest('button');
    expect(within(teamCard).getByText('12')).not.toBeNull();
  });

  it('lists the OT/leave worklist with an approval-chain badge and deep-links to the real approve surfaces, not an inline mutation', async () => {
    renderOverview();
    const worklist = (await screen.findByText('คำขอที่รอคุณอนุมัติ')).closest('section');
    const scoped = within(worklist);

    // Only the reviewable rows: สมชาย (OT) and สมหญิง (leave). The manager's
    // own pending OT request must not appear here. `findByText` (not
    // `getByText`): the panel title renders before the mocked list()/list()
    // queries resolve, so the row content lands asynchronously.
    expect(await scoped.findByText('สมชาย ใจดี')).not.toBeNull();
    expect(scoped.getByText('สมหญิง ใจงาม')).not.toBeNull();
    expect(scoped.queryAllByText('ผู้จัดการ ทดสอบ')).toHaveLength(0);

    // Chain badges: OT is 3-step (employee -> manager -> CEO); leave is
    // 2-step/terminal at the manager in this codebase (no CEO stage — see
    // LeavePage.canReviewRequest / mockApi canReviewLeave). The narrower
    // "คุณ (ตอนนี้)" match (not bare /คุณ/) avoids also matching the panel
    // title "คำขอที่รอคุณอนุมัติ", which contains "คุณ" as a substring.
    expect(scoped.getAllByText('CEO')).toHaveLength(1);
    expect(scoped.getAllByText(/คุณ \(ตอนนี้\)/)).toHaveLength(2);

    const approveButtons = scoped.getAllByRole('button', { name: /อนุมัติ/ });
    expect(approveButtons).toHaveLength(2);

    fireEvent.click(approveButtons[0]);
    expect(navigateMock).toHaveBeenCalledWith('/employee-requests?tab=ot');

    fireEvent.click(approveButtons[1]);
    expect(navigateMock).toHaveBeenCalledWith('/leave');

    // No inline approve/reject mutation wiring exists on this page — api.overtime
    // and api.leave only expose `list` in this test's mock, so a call to any
    // approve/reject method would throw "not a function" and fail the test.
  });

  it('shows an empty state when there is nothing to approve', async () => {
    overtimeList.mockResolvedValue({ requests: [] });
    leaveList.mockResolvedValue({ requests: [] });
    renderOverview();
    expect(await screen.findByText('ไม่มีคำขอที่รอคุณอนุมัติตอนนี้')).not.toBeNull();
  });

  it('renders team attendance today (present/late/not-checked-out + rows)', async () => {
    renderOverview();
    const panel = (await screen.findByText('การลงเวลาทีมวันนี้')).closest('section');
    const scoped = within(panel);
    expect(scoped.getByText('มาแล้ว')).not.toBeNull();
    expect(scoped.getByText('มาสาย')).not.toBeNull();
    expect(scoped.getByText('ยังไม่ออก')).not.toBeNull();
    await waitFor(() => expect(scoped.getByText('สมชาย ใจดี')).not.toBeNull());
  });

  it('renders the ของฉัน self-service quick actions', async () => {
    renderOverview();
    const panel = screen.getByText('ของฉัน (self-service)').closest('section');
    const scoped = within(panel);
    expect(scoped.getByText('ลงเวลา')).not.toBeNull();
    expect(scoped.getByText('ขอลา')).not.toBeNull();
    expect(scoped.getByText('ขอ OT')).not.toBeNull();
  });

  it('renders no sales pipeline surface', async () => {
    renderOverview();
    await waitFor(() => expect(overtimeList).toHaveBeenCalled());
    expect(screen.queryByText('รายการดีล')).toBeNull();
    expect(screen.queryByText('ดีลทั้งหมด')).toBeNull();
    expect(screen.queryByText(/quotation/i)).toBeNull();
  });
});
