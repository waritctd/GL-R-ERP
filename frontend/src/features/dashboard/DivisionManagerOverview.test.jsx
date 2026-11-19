import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { DivisionManagerOverview } from './DivisionManagerOverview.jsx';

globalThis.React = React;

// vi.mock factories are hoisted above imports/top-level consts, so the mock fns they close over must
// themselves be created via vi.hoisted (plain top-level `const overtimeList = vi.fn()` would still be in the
// TDZ when the hoisted factory runs).
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

// Manager-scoped worklist data: the API already scopes to what this caller may review, including the caller's
// OWN pending OT request — the component must drop that one since a manager cannot self-approve.
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

  it('renders the pulse cards (OT/leave pending, late today, team total), dropping the manager\'s own OT row from the count', async () => {
    renderOverview();
    await waitFor(() => expect(overtimeList).toHaveBeenCalled());

    // StatCard renders as a <button> here (every pulse card has an onClick) — closest('button') is the whole card;
    // label/value are sibling <div>s inside it. Only the non-self OT request (id 1) counts — the manager's own
    // request (id 2, employeeId === manager.employeeId) is excluded (no self-approval).
    const otCard = (await screen.findByText('รออนุมัติ OT')).closest('button');
    expect(within(otCard).getByText('1')).not.toBeNull();

    const leaveCard = screen.getByText('รออนุมัติลา').closest('button');
    expect(within(leaveCard).getByText('1')).not.toBeNull();

    const lateCard = screen.getByText('มาสายวันนี้').closest('button');
    expect(within(lateCard).getByText('1')).not.toBeNull();

    const teamCard = screen.getByText('ทีมทั้งหมด').closest('button');
    expect(within(teamCard).getByText('12')).not.toBeNull();
  });

  it('renders no sales pipeline surface', async () => {
    renderOverview();
    await waitFor(() => expect(overtimeList).toHaveBeenCalled());
    expect(screen.queryByText('งานทั้งหมด')).toBeNull();
    expect(screen.queryByText('ดีลทั้งหมด')).toBeNull();
    expect(screen.queryByText(/quotation/i)).toBeNull();
  });
});
