import { useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button } from '../../components/common/Button.jsx';
import { StatCard } from '../../components/common/StatCard.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { Avatar } from '../../components/common/Avatar.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, Panel, StatGrid } from '../../components/common/Layout.jsx';
import { formatShortDate, greetingName, requestStatus } from '../../utils/format.js';
import { ActionQueue } from './ActionQueue.jsx';
import { buildHrDashboardStats, DivisionHeadcountPanel } from './hrHeadcount.jsx';

/**
 * Reproduces `.dashboard-grid`: grid-template-columns: 1.15fr 0.85fr; gap: 18px;
 * align-items: start; ≤1040px: repeat(2, minmax(0, 1fr)); ≤720px: 1fr.
 * No shared primitive yet for this exact column ratio, so it's kept local
 * (matches the breakpoints of `.stat-grid`/`.dashboard-grid` in styles.css).
 */
const DASHBOARD_GRID = 'grid gap-[18px] items-start grid-cols-[1.15fr_0.85fr] nav-drawer:grid-cols-2 mobile:grid-cols-1';

export function HrDashboard({ employee, employees, profileRequests, dashboardSummary }) {
  const navigate = useNavigate();
  const dashboardStats = useMemo(() => ({
    ...buildHrDashboardStats(employees, dashboardSummary),
    probationCount: employees.filter((item) => item.statusId === 'PRB').length,
    pendingCount: dashboardSummary?.pendingApprovals?.total
      ?? profileRequests.reduce((count, item) => count + (item.status === 'pending' ? 1 : 0), 0),
  }), [employees, profileRequests, dashboardSummary]);

  // /hr is gated to hr/admin (canViewEmployees) — both roles hold
  // canReviewProfileRequests, canViewAllOvertime, canViewAllLeave, so these
  // targets are always reachable here (no per-role branching needed, unlike
  // the shared EmployeeDashboard which every role lands on).
  const queueItems = [
    { key: 'profileRequests', label: 'คำขอแก้ไขข้อมูลรออนุมัติ', value: dashboardStats.pendingProfileRequests, to: () => navigate('/requests') },
    { key: 'overtime', label: 'OT รออนุมัติ', value: dashboardStats.pendingOvertime, to: () => navigate('/overtime') },
    { key: 'leave', label: 'ลารออนุมัติ', value: dashboardStats.pendingLeave, to: () => navigate('/leave') },
    { key: 'notifications', label: 'แจ้งเตือนยังไม่อ่าน', value: dashboardStats.unreadNotifications },
  ];

  return (
    <PageStack>
      <PageHeader
        title={`สวัสดี, ${greetingName(employee?.nickName || employee?.nameTh)}`}
        subtitle="ภาพรวมระบบทรัพยากรบุคคล"
      />

      <ActionQueue items={queueItems} />

      <StatGrid>
        {/* The list now defaults to currently-employed staff, so the total card has
            to ask for every status explicitly or it would open a shorter list than
            the number it advertises. `activeCount` counts `active` (ACT+PRB), which
            is exactly the list's default — and why this card is labelled
            ปฏิบัติงานอยู่ rather than ทำงานปกติ, which means ACT alone. */}
        <StatCard icon="users" label="พนักงานทั้งหมด" value={dashboardStats.totalCount} helper="Total employees" tone="indigo" onClick={() => navigate('/employees?status=all')} />
        <StatCard icon="badgeCheck" label="ปฏิบัติงานอยู่" value={dashboardStats.activeCount} helper="Active" tone="teal" onClick={() => navigate('/employees')} />
        <StatCard icon="clipboard" label="รออนุมัติทั้งหมด" value={dashboardStats.pendingCount} helper="Approvals" tone="rose" />
        <StatCard icon="badgeCheck" label="มาวันนี้" value={dashboardStats.todayPresent} helper="Attendance" tone="teal" onClick={() => navigate('/attendance')} />
      </StatGrid>

      <StatGrid>
        <StatCard icon="userCog" label="แก้ไขข้อมูล" value={dashboardStats.pendingProfileRequests} helper="Profile requests" tone="amber" onClick={() => navigate('/requests')} />
        <StatCard icon="clock" label="OT รออนุมัติ" value={dashboardStats.pendingOvertime} helper="Overtime" tone="blue" onClick={() => navigate('/overtime')} />
        <StatCard icon="calendar" label="ลารออนุมัติ" value={dashboardStats.pendingLeave} helper="Leave" tone="teal" onClick={() => navigate('/leave')} />
        <StatCard icon="bell" label="แจ้งเตือนยังไม่อ่าน" value={dashboardStats.unreadNotifications} helper="Unread" tone="indigo" />
      </StatGrid>

      <div className={DASHBOARD_GRID}>
        <DivisionHeadcountPanel divisionRows={dashboardStats.divisionRows} maxHeadcount={dashboardStats.maxHeadcount} />

        <Panel
          title="คำขอล่าสุด"
          actions={<Button type="button" variant="text" onClick={() => navigate('/requests')}>ดูทั้งหมด</Button>}
        >
          <div className="request-feed grid gap-2.5">
            {profileRequests.slice(0, 5).map((request) => {
              const status = requestStatus(request.status);
              return (
                <div className="request-feed-item flex items-center gap-3 min-w-0 py-2.5 border-b border-surface-subtle" key={request.id}>
                  <Avatar employee={request.employee} size="sm" />
                  <span>
                    <strong>{request.employee?.nameTh}</strong>
                    <small>ขอแก้ไข{request.fieldLabel} · {formatShortDate(request.requestedAt)}</small>
                  </span>
                  <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
                </div>
              );
            })}
          </div>
        </Panel>
      </div>
    </PageStack>
  );
}
