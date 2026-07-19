import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, within } from '@testing-library/react';
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
const employeeUser = { employeeId: 3, name: 'พนักงาน ทดสอบ', role: 'employee' };
const employee = { nameTh: 'ทดสอบ ทดสอบ', positionTh: 'ตำแหน่ง', divisionTh: 'ฝ่าย' };

// UX-04: a role can see pending-work counts for surfaces it has no path to
// (CEO has no /requests, /employees route access — see permissions.js
// ROLE_PERMISSIONS: canReviewProfileRequests/canViewEmployees are hr-only,
// while canViewAllOvertime/canViewAllLeave/canViewCommissions include ceo).
const fullPendingSummary = {
  headcount: { scope: 'all', active: 28 },
  pendingApprovals: { total: 4, profileRequests: 2, overtime: 1, leave: 1, commissions: 0 },
  attendance: { todayPresent: 20, lateToday: 2 },
  notifications: { unread: 3 },
  tickets: { total: 0 },
};

// Only the item CEO cannot reach (profileRequests) has a positive count;
// everything CEO *can* reach is at zero. The queue should end up empty.
const onlyUnreachablePendingSummary = {
  headcount: { scope: 'all', active: 1 },
  pendingApprovals: { total: 1, profileRequests: 1, overtime: 0, leave: 0, commissions: 0 },
  attendance: { todayPresent: 0, lateToday: 0 },
  notifications: { unread: 0 },
  tickets: { total: 0 },
};

// Several labels (OT/leave/notifications) are shared between a StatCard and its
// matching ต้องดำเนินการ row, so plain getByText would match both — scope each
// query to the element that actually carries that role's semantics.
function statCardLabel(label) {
  return screen.getAllByText(label).find((el) => el.classList.contains('stat-label'));
}

function queueSection() {
  return screen.getByText('ต้องดำเนินการ').closest('section');
}

function quickActionsSection() {
  const heading = screen.queryByText('การดำเนินการด่วน');
  return heading ? heading.closest('section') : null;
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

  it('does not show a ต้องดำเนินการ row for profile requests, but keeps rows CEO can act on', () => {
    renderDashboard({ user: ceoUser, dashboardSummary: fullPendingSummary });
    const queue = within(queueSection());
    expect(queue.queryByText('คำขอแก้ไขข้อมูลรออนุมัติ')).toBeNull();
    // CEO has canViewAllOvertime/canViewAllLeave, so these remain real actions.
    expect(queue.getByText('OT รออนุมัติ').closest('button')).not.toBeNull();
    expect(queue.getByText('ลารออนุมัติ').closest('button')).not.toBeNull();
  });

  it('keeps informational cards for CEO as non-interactive, read-only context', () => {
    renderDashboard({ user: ceoUser, dashboardSummary: fullPendingSummary });

    const headcountLabel = statCardLabel('พนักงาน Active');
    expect(headcountLabel.closest('button')).toBeNull();
    expect(headcountLabel.closest('div.stat-card')).not.toBeNull();

    const rollup = statCardLabel('รออนุมัติทั้งหมด');
    expect(rollup.closest('button')).toBeNull();

    const attendanceLabel = statCardLabel('มาวันนี้');
    // /attendance has no path guard, so it stays a real button for every role.
    expect(attendanceLabel.closest('button')).not.toBeNull();

    const notificationsLabel = statCardLabel('แจ้งเตือนยังไม่อ่าน');
    expect(notificationsLabel.closest('button')).toBeNull();
  });

  it('shows the ต้องดำเนินการ empty state when the only pending item is unreachable', () => {
    renderDashboard({ user: ceoUser, dashboardSummary: onlyUnreachablePendingSummary });
    expect(screen.getByText('ไม่มีรายการที่ต้องดำเนินการตอนนี้')).not.toBeNull();
    expect(screen.queryByText('คำขอแก้ไขข้อมูลรออนุมัติ')).toBeNull();
  });

  // UX-04 follow-up: quickActions/"ดูทั้งหมด" are now filtered through
  // canAccessPath (the same predicate RequireAccess uses), so a CEO can no
  // longer be offered buttons the router would bounce straight back from /.
  it('offers no quick-action button whose destination the router would reject', () => {
    renderDashboard({ user: ceoUser, dashboardSummary: fullPendingSummary });
    const panel = quickActionsSection();
    const actions = within(panel);
    expect(actions.queryByText('ภาพรวม HR')).toBeNull();
    expect(actions.queryByText('รายชื่อพนักงาน')).toBeNull();
    expect(actions.queryByText('คำขอแก้ไขข้อมูล')).toBeNull();
    // /attendance has no path guard, so it remains a real action for CEO.
    expect(actions.getByText('ข้อมูลเวลาเข้างาน').closest('button')).not.toBeNull();
  });

  it('does not render the "ดูทั้งหมด" action (company mode points at /requests, unreachable for CEO)', () => {
    renderDashboard({ user: ceoUser, dashboardSummary: fullPendingSummary });
    expect(screen.queryByText('ดูทั้งหมด')).toBeNull();
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

  it('still renders every ต้องดำเนินการ row HR can act on', () => {
    renderDashboard({ user: hrUser, dashboardSummary: fullPendingSummary });

    const queue = within(queueSection());
    expect(queue.getByText('คำขอแก้ไขข้อมูลรออนุมัติ').closest('button')).not.toBeNull();
    expect(queue.getByText('OT รออนุมัติ').closest('button')).not.toBeNull();
    expect(queue.getByText('ลารออนุมัติ').closest('button')).not.toBeNull();
  });

  // Regression guard: HR can reach /hr, /employees, /requests, /attendance,
  // so canAccessPath filtering must not remove any of these for HR.
  it('still renders all four quick-action buttons and "ดูทั้งหมด"', () => {
    renderDashboard({ user: hrUser, dashboardSummary: fullPendingSummary });
    const actions = within(quickActionsSection());
    expect(actions.getByText('ภาพรวม HR').closest('button')).not.toBeNull();
    expect(actions.getByText('รายชื่อพนักงาน').closest('button')).not.toBeNull();
    expect(actions.getByText('คำขอแก้ไขข้อมูล').closest('button')).not.toBeNull();
    expect(actions.getByText('ข้อมูลเวลาเข้างาน').closest('button')).not.toBeNull();
    expect(screen.getByText('ดูทั้งหมด')).not.toBeNull();
  });
});

describe('EmployeeDashboard — employee mode: three-tier hierarchy', () => {
  // The quick-actions panel offered only duplicate navigation (every target was
  // already in the sidebar or the pending rows), so employee mode drops it.
  it('renders no quick-actions panel', () => {
    renderDashboard({ user: employeeUser, dashboardSummary: undefined });
    expect(quickActionsSection()).toBeNull();
  });

  // Tier 1: position/division/tenure live beside the person, not as stat cards
  // that repeated the identity line and forced text into a numeric slot.
  it('shows employment facts as a definition list, not stat cards', () => {
    renderDashboard({ user: employeeUser, dashboardSummary: undefined });
    const term = screen.getAllByText('ตำแหน่ง').find((el) => el.tagName === 'DT');
    expect(term).toBeDefined();
    expect(statCardLabel('ตำแหน่ง')).toBeUndefined();
    expect(statCardLabel('สังกัด')).toBeUndefined();
    expect(statCardLabel('อายุงาน')).toBeUndefined();
  });

  // Tier 3 is the core fix: a zero must not carry the same weight as a real
  // count, otherwise nothing on the page leads.
  it('renders a zero pending count as a muted dash and a real count as a badge', () => {
    renderDashboard({
      user: employeeUser,
      dashboardSummary: { pendingApprovals: { overtime: 0, leave: 2, profileRequests: 0 }, notifications: { unread: 0 } },
    });
    const pendingPanel = screen.getByText('รอดำเนินการ').closest('section');
    const rows = within(pendingPanel);

    expect(rows.getByText('ลาของฉัน')).not.toBeNull();
    expect(rows.getByText('2 รายการ')).not.toBeNull();
    // Three zero rows (OT, profile requests, notifications) all render as dashes.
    expect(rows.getAllByText('—')).toHaveLength(3);
  });

  it('links the profile-requests row to /profile, which absorbed /my-requests', () => {
    renderDashboard({
      user: employeeUser,
      dashboardSummary: { pendingApprovals: { profileRequests: 1 }, notifications: { unread: 0 } },
    });
    const pendingPanel = screen.getByText('รอดำเนินการ').closest('section');
    const row = within(pendingPanel).getByText('คำขอแก้ไขข้อมูล').closest('button');
    expect(row).not.toBeNull();
  });
});
