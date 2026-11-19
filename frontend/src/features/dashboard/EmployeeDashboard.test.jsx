import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { EmployeeDashboard } from './EmployeeDashboard.jsx';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      payroll: {
        downloadOwnPayslip: vi.fn(),
      },
    },
  };
});

const ceoUser = { employeeId: 1, name: 'ผู้บริหาร ทดสอบ', role: 'ceo' };
const hrUser = { employeeId: 2, name: 'ฝ่ายบุคคล ทดสอบ', role: 'hr' };
const employee = { nameTh: 'ทดสอบ ทดสอบ', positionTh: 'ตำแหน่ง', divisionTh: 'ฝ่าย' };

// UX-04: a role can see pending-work counts for surfaces it has no path to (CEO has no /requests, /employees
// route access — see permissions.js ROLE_PERMISSIONS: canReviewProfileRequests/canViewEmployees are hr-only,
// while canViewAllOvertime/canViewAllLeave/canViewCommissions include ceo).
const fullPendingSummary = {
  headcount: { scope: 'all', active: 28 },
  pendingApprovals: { total: 4, profileRequests: 2, overtime: 1, leave: 1, commissions: 0 },
  attendance: { todayPresent: 20, lateToday: 2 },
  notifications: { unread: 3 },
  tickets: { total: 0 },
};

// Several labels (OT/leave/notifications) are shared between a StatCard and its matching ต้องดำเนินการ row, so
// plain getByText would match both — scope each query to the element that actually carries that role's
// semantics.
function statCardLabel(label) {
  return screen.getAllByText(label).find((el) => el.classList.contains('stat-label'));
}

function renderDashboard({ user, dashboardSummary }) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/dashboard']}>
        <EmployeeDashboard user={user} employee={employee} profileRequests={[]} dashboardSummary={dashboardSummary} showToast={vi.fn()} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('EmployeeDashboard — company mode, CEO (UX-04)', () => {
  it('hides the profile-requests pending card (no path to /requests)', () => {
    renderDashboard({ user: ceoUser, dashboardSummary: fullPendingSummary });
    expect(screen.queryByText('แก้ไขข้อมูล')).toBeNull();
  });
});

describe('EmployeeDashboard — company mode, HR (regression guard)', () => {
  it('still renders every card, including the profile-requests card', () => {
    renderDashboard({ user: hrUser, dashboardSummary: fullPendingSummary });

    const profileCard = statCardLabel('แก้ไขข้อมูล');
    expect(profileCard.closest('button')).not.toBeNull();

    const headcountLabel = statCardLabel('พนักงาน Active');
    expect(headcountLabel.closest('button')).not.toBeNull();
  });
});
