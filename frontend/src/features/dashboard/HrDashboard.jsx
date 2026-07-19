import { useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button } from '../../components/common/Button.jsx';
import { StatCard } from '../../components/common/StatCard.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { Avatar } from '../../components/common/Avatar.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, Panel, StatGrid } from '../../components/common/Layout.jsx';
import { formatShortDate, requestStatus } from '../../utils/format.js';
import { divisions, findDivision } from '../../data/referenceData.js';
import { ActionQueue } from './ActionQueue.jsx';

/**
 * Reproduces `.dashboard-grid`: grid-template-columns: 1.15fr 0.85fr; gap: 18px;
 * align-items: start; ≤1040px: repeat(2, minmax(0, 1fr)); ≤720px: 1fr.
 * No shared primitive yet for this exact column ratio, so it's kept local
 * (matches the breakpoints of `.stat-grid`/`.dashboard-grid` in styles.css).
 */
const DASHBOARD_GRID = 'grid gap-[18px] items-start grid-cols-[1.15fr_0.85fr] max-[1040px]:grid-cols-2 max-[720px]:grid-cols-1';

export function HrDashboard({ employee, employees, profileRequests, dashboardSummary }) {
  const navigate = useNavigate();
  const dashboardStats = useMemo(() => {
    const headcountByDivision = new Map(divisions.map((division) => [division.id, { division, count: 0 }]));
    const extraDivisions = new Map();
    let activeCount = 0;
    let probationCount = 0;

    employees.forEach((item) => {
      if (item.active) activeCount += 1;
      if (item.statusId === 'PRB') probationCount += 1;
      const knownDivision = findDivision(item.divisionId, item.divisionTh);
      if (knownDivision && headcountByDivision.has(knownDivision.id)) {
        headcountByDivision.get(knownDivision.id).count += 1;
      } else {
        const key = item.divisionId || item.divisionTh || 'unknown';
        const current = extraDivisions.get(key) || {
          division: { id: key, th: item.divisionTh || 'ไม่ระบุฝ่าย', en: item.divisionId || 'Unassigned' },
          count: 0,
        };
        current.count += 1;
        extraDivisions.set(key, current);
      }
    });

    const fallbackDivisionRows = [...headcountByDivision.values(), ...extraDivisions.values()];
    const summaryRows = dashboardSummary?.headcount?.byDivision?.map((item) => ({
      division: {
        id: item.divisionId ?? item.divisionCode ?? item.divisionName,
        th: item.divisionName || 'ไม่ระบุฝ่าย',
        en: item.divisionCode || '',
      },
      count: item.total ?? item.active ?? 0,
    })) ?? [];
    const divisionRows = summaryRows.length > 0 ? summaryRows : fallbackDivisionRows;
    const pending = dashboardSummary?.pendingApprovals ?? {};
    const attendance = dashboardSummary?.attendance ?? {};
    const notifications = dashboardSummary?.notifications ?? {};

    return {
      totalCount: dashboardSummary?.headcount?.total ?? employees.length,
      activeCount: dashboardSummary?.headcount?.active ?? activeCount,
      probationCount,
      pendingCount: pending.total ?? profileRequests.reduce((count, item) => count + (item.status === 'pending' ? 1 : 0), 0),
      pendingProfileRequests: pending.profileRequests ?? 0,
      pendingOvertime: pending.overtime ?? 0,
      pendingLeave: pending.leave ?? 0,
      todayPresent: attendance.todayPresent ?? 0,
      lateToday: attendance.lateToday ?? 0,
      unreadNotifications: notifications.unread ?? 0,
      divisionRows,
      maxHeadcount: Math.max(...divisionRows.map((item) => item.count), 1),
    };
  }, [employees, profileRequests, dashboardSummary]);

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
        title={`สวัสดี, คุณ${employee?.nickName || employee?.nameTh || ''}`}
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
        <Panel
          title="จำนวนพนักงานตามฝ่าย"
          actions={<Button type="button" variant="text" onClick={() => navigate('/employees')}>ดูรายชื่อ</Button>}
        >
          <div className="bar-list">
            {dashboardStats.divisionRows.map(({ division, count }) => {
              const widthPct = Math.max(8, Math.round((count / dashboardStats.maxHeadcount) * 100));
              return (
                <div className="bar-row" key={division.id}>
                  <span>
                    <strong>{division.th}</strong>
                    <small>{division.en}</small>
                  </span>
                  <div className="bar-track">
                    <i className="w-[var(--bar-width)]" style={{ '--bar-width': `${widthPct}%` }} />
                  </div>
                  <b>{count}</b>
                </div>
              );
            })}
          </div>
        </Panel>

        <Panel
          title="คำขอล่าสุด"
          actions={<Button type="button" variant="text" onClick={() => navigate('/requests')}>ดูทั้งหมด</Button>}
        >
          <div className="request-feed">
            {profileRequests.slice(0, 5).map((request) => {
              const status = requestStatus(request.status);
              return (
                <div className="request-feed-item" key={request.id}>
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
