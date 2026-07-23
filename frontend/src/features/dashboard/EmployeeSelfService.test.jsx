import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { EmployeeSelfService } from './EmployeeSelfService.jsx';
import { bangkokTodayIso } from '../../utils/format.js';

globalThis.React = React;

const attendanceDaily = vi.fn();
const leaveBalances = vi.fn();
const leaveList = vi.fn();
const overtimeList = vi.fn();
const downloadOwnPayslip = vi.fn();

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      attendance: { daily: (...args) => attendanceDaily(...args) },
      leave: { balances: (...args) => leaveBalances(...args), list: (...args) => leaveList(...args) },
      overtime: { list: (...args) => overtimeList(...args) },
      payroll: { downloadOwnPayslip: (...args) => downloadOwnPayslip(...args) },
    },
  };
});

const employeeUser = { employeeId: 3, name: 'พนักงาน ทดสอบ', role: 'employee', manager: false };
const employee = { nameTh: 'ทดสอบ ทดสอบ', nickName: 'เอ', positionTh: 'ตำแหน่ง', divisionTh: 'ฝ่าย' };

// Matches the component's own Bangkok-timezone "today", not raw UTC — the
// clock card looks up `attendanceDays.find(day => day.work_date === todayIso)`
// using bangkokTodayIso(), so the fixture must use the same clock.
const today = bangkokTodayIso();

function setupMocks({ days = [], balances = [], leaveRequests = [], overtimeRequests = [] } = {}) {
  attendanceDaily.mockResolvedValue({ days });
  leaveBalances.mockResolvedValue({ balances });
  leaveList.mockResolvedValue({ requests: leaveRequests });
  overtimeList.mockResolvedValue({ requests: overtimeRequests });
}

function renderSelfService({ user = employeeUser, dashboardSummary, profileRequests = [] } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/']}>
        <EmployeeSelfService
          user={user}
          employee={employee}
          profileRequests={profileRequests}
          dashboardSummary={dashboardSummary}
          showToast={vi.fn()}
        />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('EmployeeSelfService — self-service landing', () => {
  it('renders the clock card, the three tiles, and the own-requests panel', async () => {
    setupMocks({
      days: [{ work_date: today, check_in: `${today}T08:55:00+07:00`, check_out: null, late_minutes: 0 }],
      balances: [
        { leaveTypeCode: 'VACATION', leaveTypeNameTh: 'ลาพักร้อน', remainingDays: 4 },
        { leaveTypeCode: 'PERSONAL', leaveTypeNameTh: 'ลากิจ', remainingDays: 2 },
        { leaveTypeCode: 'SICK', leaveTypeNameTh: 'ลาป่วย', remainingDays: 28 },
      ],
    });
    renderSelfService();

    expect(screen.getByText('สวัสดี คุณเอ')).not.toBeNull();
    await waitFor(() => expect(screen.getByText('เข้างานแล้ว')).not.toBeNull());
    expect(screen.getByText('เวลาทำงานเดือนนี้')).not.toBeNull();
    expect(screen.getByText('วันลาคงเหลือ')).not.toBeNull();
    expect(screen.getByText('สลิปเงินเดือน')).not.toBeNull();
    expect(screen.getByText('คำขอของฉัน')).not.toBeNull();
  });

  it('shows the employee\'s own attendance for today (not a hardcoded value)', async () => {
    setupMocks({
      days: [{ work_date: today, check_in: `${today}T09:15:00+07:00`, check_out: null, late_minutes: 15 }],
    });
    renderSelfService();
    await waitFor(() => expect(screen.getByText('เข้างานแล้ว')).not.toBeNull());
    expect(screen.getByText(/เข้างาน 09:15 น\./)).not.toBeNull();
  });

  it('renders "ยังไม่ลงเวลา" when there is no punch yet today', async () => {
    setupMocks({ days: [] });
    renderSelfService();
    await waitFor(() => expect(screen.getByText('ยังไม่ลงเวลา')).not.toBeNull());
  });

  it('renders own leave/OT/profile-change requests with status and approval chain', async () => {
    setupMocks({
      leaveRequests: [{
        id: 1, leaveTypeCode: 'VACATION', leaveTypeNameTh: 'ลาพักร้อน', startDate: '2026-07-10', endDate: '2026-07-11',
        status: 'APPROVED', requestedAt: '2026-07-01T00:00:00Z', reviewedByName: 'หัวหน้าฝ่าย ทดสอบ', reviewedAt: '2026-07-02T00:00:00Z',
      }],
      overtimeRequests: [{
        id: 2, workDate: '2026-07-15', status: 'MANAGER_APPROVED', requestedAt: '2026-07-05T00:00:00Z',
        managerApprovedByName: 'หัวหน้าฝ่าย ทดสอบ', managerApprovedAt: '2026-07-06T00:00:00Z',
        ceoApprovedByName: null, ceoApprovedAt: null,
      }],
    });
    renderSelfService({
      profileRequests: [{ id: 3, fieldLabel: 'เบอร์โทรศัพท์', status: 'pending', requestedAt: '2026-07-08' }],
    });

    // Wait for the async leave/OT/profile-request queries to settle, not just
    // the (synchronously rendered) panel header.
    const leaveTitle = await screen.findByText('ลาพักร้อน');
    const scoped = within(leaveTitle.closest('section'));
    // "OT" also appears in the panel's "+ ขอ OT" action button, so match the
    // full own-request row title, not a loose substring.
    expect(scoped.getByText('OT 15/07/2569')).not.toBeNull();
    expect(scoped.getByText('ขอแก้ไขเบอร์โทรศัพท์')).not.toBeNull();
    // Approval chain: the manager step should show who approved.
    expect(scoped.getByText(/หัวหน้าฝ่าย · หัวหน้าฝ่าย ทดสอบ/)).not.toBeNull();
  });

  it('has no approval/management action anywhere on the page (self-service only)', async () => {
    setupMocks({
      leaveRequests: [{
        id: 1, leaveTypeCode: 'SICK', leaveTypeNameTh: 'ลาป่วย', startDate: '2026-07-10', endDate: '2026-07-10',
        status: 'SUBMITTED', requestedAt: '2026-07-01T00:00:00Z',
      }],
    });
    renderSelfService({
      profileRequests: [{ id: 9, fieldLabel: 'อีเมล', status: 'pending', requestedAt: '2026-07-08' }],
    });
    await screen.findByText('คำขอของฉัน');
    expect(screen.queryByLabelText('อนุมัติ')).toBeNull();
    expect(screen.queryByLabelText('ปฏิเสธ')).toBeNull();
    expect(screen.queryByText('อนุมัติ')).toBeNull();
  });

  it('offers the "+ ขอลา" / "+ ขอ OT" create actions', async () => {
    setupMocks({});
    renderSelfService();
    await screen.findByText('คำขอของฉัน');
    expect(screen.getByText('ขอลา').closest('button')).not.toBeNull();
    expect(screen.getByText('ขอ OT').closest('button')).not.toBeNull();
  });
});
