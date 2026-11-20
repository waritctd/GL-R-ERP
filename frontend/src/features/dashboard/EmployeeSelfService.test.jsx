import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { EmployeeSelfService } from './EmployeeSelfService.jsx';

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

function setupMocks({ days = [], balances = [], leaveRequests = [], overtimeRequests = [] } = {}) {
  attendanceDaily.mockResolvedValue({ days });
  leaveBalances.mockResolvedValue({ balances });
  leaveList.mockResolvedValue({ requests: leaveRequests });
  overtimeList.mockResolvedValue({ requests: overtimeRequests });
}

function renderSelfService({ user = employeeUser, dashboardSummary, profileRequests = [], taxAllowanceSummary } = {}) {
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
          taxAllowanceSummary={taxAllowanceSummary}
          showToast={vi.fn()}
        />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('EmployeeSelfService — self-service landing', () => {
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
