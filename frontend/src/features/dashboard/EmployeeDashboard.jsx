import { useMutation } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { StatCard } from '../../components/common/StatCard.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { Avatar } from '../../components/common/Avatar.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, Panel, StatGrid } from '../../components/common/Layout.jsx';
import { formatShortDate, requestStatus } from '../../utils/format.js';
import { hasPermission, canAccessPath } from '../../app/permissions.js';
import { SALES_ENABLED } from '../../app/features.js';
import { ActionQueue } from './ActionQueue.jsx';

/**
 * Reproduces `.dashboard-grid`: grid-template-columns: 1.15fr 0.85fr; gap: 18px;
 * align-items: start; ≤1040px: repeat(2, minmax(0, 1fr)); ≤720px: 1fr.
 * No shared primitive yet for this exact column ratio, so it's kept local
 * (matches the breakpoints of `.stat-grid`/`.dashboard-grid` in styles.css).
 */
const DASHBOARD_GRID = 'grid gap-[18px] items-start grid-cols-[1.15fr_0.85fr] max-[1040px]:grid-cols-2 max-[720px]:grid-cols-1';

const COMPANY_ROLES = ['hr', 'ceo'];

function numberValue(value) {
  return value ?? 0;
}

function attendanceStatus(value) {
  if (value === 'PRESENT') return 'มาทำงาน';
  if (value === 'ABSENT') return 'ขาดงาน';
  if (value === 'NO_RECORD') return 'ไม่มีข้อมูล';
  return value || 'ไม่มีข้อมูล';
}

function dashboardMode(user, summary) {
  if (summary?.headcount?.scope === 'all' || COMPANY_ROLES.includes(user?.role)) return 'company';
  if (summary?.headcount?.scope === 'division' || user?.manager) return 'manager';
  return 'employee';
}

// Route access for each card/queue target, derived only from permissions.js
// (the single source of truth for what a role may open) plus SALES_ENABLED —
// never invented. `/attendance` has no path guard (any signed-in user may
// open it), so it is always a valid click target.
function dashboardAccess(user) {
  const canOvertime = hasPermission(user?.role, 'canViewAllOvertime') || !!user?.employeeId;
  const canLeave = hasPermission(user?.role, 'canViewAllLeave') || !!user?.employeeId;
  return {
    employees: () => hasPermission(user?.role, 'canViewEmployees'),
    reviewRequests: () => hasPermission(user?.role, 'canReviewProfileRequests'),
    // /profile absorbed the former /my-requests page; both gate on employeeId.
    profile: () => !!user?.employeeId,
    overtime: () => canOvertime,
    leave: () => canLeave,
    tickets: () => SALES_ENABLED && hasPermission(user?.role, 'canViewTickets'),
    commissions: () => SALES_ENABLED && hasPermission(user?.role, 'canViewCommissions'),
    attendance: () => true,
  };
}

function dashboardCards(mode, summary, pendingCount, { navigate, access }) {
  const headcount = summary?.headcount ?? {};
  const pending = summary?.pendingApprovals ?? {};
  const attendance = summary?.attendance ?? {};
  const notifications = summary?.notifications ?? {};
  const tickets = summary?.tickets ?? {};
  const go = (path, allowed) => (allowed ? () => navigate(path) : undefined);

  if (mode === 'company') {
    return [
      { icon: 'users', label: 'พนักงาน Active', value: numberValue(headcount.active), helper: 'Headcount', tone: 'indigo', onClick: go('/employees', access.employees()) },
      { icon: 'clipboard', label: 'รออนุมัติทั้งหมด', value: numberValue(pending.total), helper: 'Approvals', tone: 'rose' },
      // Pending-work card (UX-04): "N need your review" is a false call to action for a
      // role with no path to `/requests` — hide it rather than render it inert. This is
      // unlike the headcount/attendance/notifications cards above and below, which are
      // read-only context a viewer legitimately wants even without an onClick.
      ...(access.reviewRequests() ? [
        { icon: 'userCog', label: 'แก้ไขข้อมูล', value: numberValue(pending.profileRequests), helper: 'Profile requests', tone: 'amber', onClick: go('/requests', access.reviewRequests()) },
      ] : []),
      { icon: 'clock', label: 'OT รออนุมัติ', value: numberValue(pending.overtime), helper: 'Overtime', tone: 'blue', onClick: go('/overtime', access.overtime()) },
      { icon: 'calendar', label: 'ลารออนุมัติ', value: numberValue(pending.leave), helper: 'Leave', tone: 'teal', onClick: go('/leave', access.leave()) },
      { icon: 'badgeCheck', label: 'มาวันนี้', value: numberValue(attendance.todayPresent), helper: 'Attendance', tone: 'teal', onClick: go('/attendance', access.attendance()) },
      { icon: 'clock', label: 'มาสายวันนี้', value: numberValue(attendance.lateToday), helper: 'Late today', tone: numberValue(attendance.lateToday) > 0 ? 'amber' : 'indigo', onClick: go('/attendance', access.attendance()) },
      { icon: 'bell', label: 'แจ้งเตือนยังไม่อ่าน', value: numberValue(notifications.unread), helper: 'Unread', tone: 'indigo' },
      ...(tickets.total > 0 ? [
        { icon: 'fileText', label: 'ใบขอราคาเปิดอยู่', value: numberValue(tickets.totalOpen), helper: 'Tickets', tone: 'blue', onClick: go('/tickets', access.tickets()) },
      ] : []),
    ];
  }

  if (mode === 'manager') {
    return [
      { icon: 'users', label: 'พนักงานในฝ่าย', value: numberValue(headcount.active), helper: 'Active', tone: 'indigo' },
      { icon: 'clock', label: 'OT รออนุมัติ', value: numberValue(pending.overtime), helper: 'Division', tone: 'amber', onClick: go('/overtime', access.overtime()) },
      { icon: 'calendar', label: 'ลารออนุมัติ', value: numberValue(pending.leave), helper: 'Division', tone: 'teal', onClick: go('/leave', access.leave()) },
      { icon: 'badgeCheck', label: 'มาวันนี้', value: numberValue(attendance.todayPresent), helper: 'Attendance', tone: 'teal', onClick: go('/attendance', access.attendance()) },
      { icon: 'clock', label: 'มาสายวันนี้', value: numberValue(attendance.lateToday), helper: 'Late today', tone: numberValue(attendance.lateToday) > 0 ? 'amber' : 'indigo', onClick: go('/attendance', access.attendance()) },
      { icon: 'bell', label: 'แจ้งเตือนยังไม่อ่าน', value: numberValue(notifications.unread), helper: 'Unread', tone: 'indigo' },
      ...(tickets.total > 0 ? [
        { icon: 'fileText', label: 'ใบขอราคาเปิดอยู่', value: numberValue(tickets.totalOpen), helper: 'Tickets', tone: 'blue', onClick: go('/tickets', access.tickets()) },
      ] : []),
    ];
  }

  // Employee mode renders its own three-tier layout (identity facts → today →
  // pending rows) instead of a flat card grid, so it does not use this list.
  return [];
}

/**
 * Employee mode, tier 3: the four pending counts as a compact list.
 *
 * Rendering these as equal-weight stat cards meant a `0` shouted exactly as
 * loud as a `1`, so nothing led the page. Here a zero recedes to a muted dash
 * and only a real count gets a badge — emphasis becomes a function of whether
 * something actually needs attention.
 */
function employeePendingRows(summary, pendingCount, { navigate, access }) {
  const pending = summary?.pendingApprovals ?? {};
  const notifications = summary?.notifications ?? {};
  const go = (path, allowed) => (allowed ? () => navigate(path) : undefined);
  return [
    { key: 'overtime', label: 'OT ของฉัน', value: numberValue(pending.overtime), tone: 'warning', onClick: go('/overtime', access.overtime()) },
    { key: 'leave', label: 'ลาของฉัน', value: numberValue(pending.leave), tone: 'info', onClick: go('/leave', access.leave()) },
    { key: 'profileRequests', label: 'คำขอแก้ไขข้อมูล', value: numberValue(pending.profileRequests ?? pendingCount), tone: 'warning', onClick: go('/profile', access.profile()) },
    // No onClick: the topbar bell dropdown is the only UI for notifications,
    // so a row that navigated nowhere would be a false affordance (UX-04).
    { key: 'notifications', label: 'แจ้งเตือนยังไม่อ่าน', value: numberValue(notifications.unread), tone: 'info' },
  ];
}

// The needs-action queue only lists items the *viewer* can act on (approve,
// review, pick up) — not status of their own already-submitted requests,
// which is visibility-of-status, not an action for them. That is why this
// is built only for 'company'/'manager' modes, never 'employee' mode.
function actionQueueItems(mode, summary, { navigate, access }) {
  if (mode === 'employee') return [];
  const pending = summary?.pendingApprovals ?? {};
  const notifications = summary?.notifications ?? {};
  const go = (path, allowed) => (allowed ? () => navigate(path) : undefined);
  const items = [
    // `actionable` is the explicit "the viewer has a way to act on this" signal (UX-04).
    // It is deliberately separate from `to`: `to` is only the in-panel navigation target,
    // and `notifications` below proves the two diverge — it has no `to` (the topbar bell
    // dropdown is its only UI) yet is still actionable, so filtering on "has a `to`" would
    // wrongly drop it. Filtered here (not in ActionQueue) because `access` — the only
    // source of truth for reachability — is already in scope, keeping ActionQueue a plain
    // renderer of whatever list it's handed.
    { key: 'profileRequests', label: 'คำขอแก้ไขข้อมูลรออนุมัติ', value: numberValue(pending.profileRequests), to: go('/requests', access.reviewRequests()), actionable: access.reviewRequests() },
    { key: 'overtime', label: 'OT รออนุมัติ', value: numberValue(pending.overtime), to: go('/overtime', access.overtime()), actionable: access.overtime() },
    { key: 'leave', label: 'ลารออนุมัติ', value: numberValue(pending.leave), to: go('/leave', access.leave()), actionable: access.leave() },
    { key: 'commissions', label: 'ค่าคอมมิชชั่นรออนุมัติ', value: numberValue(pending.commissions), to: go('/commissions', access.commissions()), actionable: access.commissions() },
    { key: 'notifications', label: 'แจ้งเตือนยังไม่อ่าน', value: numberValue(notifications.unread), actionable: true },
  ];
  return items.filter((item) => item.actionable);
}

function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}

export function EmployeeDashboard({ user, employee, profileRequests = [], dashboardSummary, showToast }) {
  const navigate = useNavigate();
  const pendingCount = profileRequests.filter((request) => request.status === 'pending').length;
  const years = employee?.hireDate ? new Date().getFullYear() - new Date(employee.hireDate).getFullYear() : 0;
  const mode = dashboardMode(user, dashboardSummary);
  const access = dashboardAccess(user);
  const cards = dashboardCards(mode, dashboardSummary, pendingCount, { navigate, access });
  const pendingRows = mode === 'employee'
    ? employeePendingRows(dashboardSummary, pendingCount, { navigate, access })
    : [];
  const queueItems = actionQueueItems(mode, dashboardSummary, { navigate, access });
  const title = mode === 'company'
    ? `สวัสดี, คุณ${employee?.nickName || employee?.nameTh || user?.name || ''}`
    : `สวัสดี, คุณ${employee?.nickName || employee?.nameTh || user?.name || ''}`;
  const subtitle = mode === 'company'
    ? 'ภาพรวมระบบทรัพยากรบุคคล'
    : mode === 'manager'
      ? `${employee?.positionTh || 'Manager'} · ${employee?.divisionTh || ''}`
      : `${employee?.positionTh || ''} · ${employee?.divisionTh || ''}`;
  // UX-04 (part 2): quickActions is filtered through `canAccessPath` — the
  // exact predicate the router (RequireAccess) uses — so this panel can never
  // offer a destination the router will reject. Filtering here, not by
  // hand-picking per role, is what keeps this list from drifting out of sync
  // with the router the way the stat cards/ActionQueue previously did.
  // Employee mode has no quick-actions panel: every destination it offered was
  // already one click away in the sidebar or the pending rows above, so it was
  // duplicate navigation occupying half the lower dashboard.
  const quickActionCandidates = mode === 'company'
    ? [
      ['/hr', 'ภาพรวม HR'],
      ['/employees', 'รายชื่อพนักงาน'],
      ['/requests', 'คำขอแก้ไขข้อมูล'],
      ['/attendance', 'ข้อมูลเวลาเข้างาน'],
    ]
    : [
      ['/overtime', 'รายการ OT'],
      ['/leave', 'รายการลา'],
    ];
  const quickActions = quickActionCandidates.filter(([path]) => canAccessPath(path, user));
  // Same predicate gates "ดูทั้งหมด" — company mode points at /requests, which
  // a CEO cannot reach (canReviewProfileRequests is hr-only).
  const requestsFeedPath = mode === 'company' ? '/requests' : '/profile';
  const canViewRequestsFeed = canAccessPath(requestsFeedPath, user);
  const latestPayrollPeriodId = dashboardSummary?.latestPayrollPeriodId;
  const ownPayslipMutation = useMutation({
    mutationFn: (periodId) => api.payroll.downloadOwnPayslip(periodId),
    onSuccess: (blob) => {
      downloadBlob(blob, `glr-my-payslip-${latestPayrollPeriodId}.pdf`);
      showToast?.('success', 'ดาวน์โหลดสลิปเงินเดือนแล้ว');
    },
    onError: (error) => showToast?.('error', error.message || 'ดาวน์โหลดสลิปเงินเดือนไม่สำเร็จ'),
  });

  function downloadMyPayslip() {
    if (!latestPayrollPeriodId) return;
    ownPayslipMutation.mutate(latestPayrollPeriodId);
  }

  return (
    <PageStack>
      <PageHeader
        title={title}
        subtitle={subtitle}
        actions={mode === 'employee' ? (
          <Button
            type="button"
            variant="secondary"
            onClick={downloadMyPayslip}
            disabled={!latestPayrollPeriodId || ownPayslipMutation.isPending}
          >
            <Icon name="fileText" />
            My payslip
          </Button>
        ) : null}
      />

      {mode !== 'employee' ? <ActionQueue items={queueItems} /> : null}

      {employee && mode !== 'employee' ? (
        <section className="profile-strip">
          <Avatar employee={employee} size="lg" />
          <div>
            <h2>{employee?.nameTh}</h2>
            <p>{employee?.positionTh} · {employee?.departmentTh} · <code>{employee?.code}</code></p>
          </div>
          <StatusBadge tone={employee?.statusTone}>{employee?.statusTh}</StatusBadge>
        </section>
      ) : null}

      {/* Employee tier 1 — identity, with ตำแหน่ง/สังกัด/อายุงาน folded in as a
          definition list. They are attributes of the person, so they belong
          beside the person; as stat cards they both repeated the line above and
          forced text into a numeric display slot. A bordered list inside this
          card, never a nested card (DESIGN.md). */}
      {employee && mode === 'employee' ? (
        <section className="profile-strip !flex-col !items-stretch !gap-0">
          <div className="flex items-center gap-[18px] max-[720px]:flex-col max-[720px]:items-start max-[720px]:gap-2.5">
            <Avatar employee={employee} size="lg" />
            <div className="min-w-0 flex-1">
              <h2>{employee?.nameTh}</h2>
              <p>{employee?.positionTh} · <code>{employee?.code}</code></p>
            </div>
            <StatusBadge tone={employee?.statusTone}>{employee?.statusTh}</StatusBadge>
          </div>
          <dl className="m-0 mt-4 pt-4 border-t border-border grid gap-2">
            {[
              ['ตำแหน่ง', employee?.positionTh || '-'],
              ['สังกัด', employee?.divisionTh || '-'],
              ['อายุงาน', `${years} ปี`],
            ].map(([term, value]) => (
              <div key={term} className="flex items-baseline justify-between gap-4">
                <dt className="!text-xs !text-text-muted">{term}</dt>
                <dd className="m-0 !text-sm !text-text text-right min-w-0 truncate">{value}</dd>
              </div>
            ))}
          </dl>
        </section>
      ) : null}

      {/* Employee tier 2 — the one fact that actually changes daily, given the
          page's single largest value so there is somewhere obvious to look. */}
      {mode === 'employee' ? (
        <button
          type="button"
          onClick={() => navigate('/attendance')}
          className="bg-surface border border-border rounded-md shadow-sm p-5 w-full text-left cursor-pointer flex items-center justify-between gap-4 transition-colors hover:border-primary/50 hover:bg-surface-hover focus-visible:outline-none focus-visible:shadow-[var(--shadow-focus-ring)] focus-visible:border-primary-hover max-[720px]:flex-col max-[720px]:items-start max-[720px]:gap-2"
        >
          <span className="flex items-center gap-3 min-w-0">
            <span className={`stat-icon !mb-0 stat-${dashboardSummary?.attendance?.todayStatus === 'PRESENT' ? 'teal' : 'amber'}`}>
              <Icon name="badgeCheck" size={21} />
            </span>
            <span className="min-w-0">
              <span className="block !text-sm !font-bold !text-text">สถานะวันนี้</span>
              <span className="block !text-xs !text-text-muted">เวลาทำงานวันนี้</span>
            </span>
          </span>
          <span className="text-3xl font-extrabold leading-tight !text-text truncate">
            {attendanceStatus(dashboardSummary?.attendance?.todayStatus)}
          </span>
        </button>
      ) : null}

      {/* Employee tier 3 — pending counts, quiet until one is non-zero. */}
      {mode === 'employee' ? (
        <Panel title="รอดำเนินการ">
          <div className="grid">
            {pendingRows.map((row) => {
              const RowTag = row.onClick ? 'button' : 'div';
              return (
                <RowTag
                  key={row.key}
                  type={row.onClick ? 'button' : undefined}
                  onClick={row.onClick}
                  className={`flex items-center justify-between gap-4 min-h-11 py-2 border-0 bg-transparent text-left w-full border-b border-surface-subtle last:border-b-0${
                    row.onClick
                      ? ' cursor-pointer transition-colors hover:bg-surface-hover focus-visible:outline-none focus-visible:shadow-[var(--shadow-focus-ring)]'
                      : ''
                  }`}
                >
                  <span className={`!text-sm ${row.value > 0 ? '!text-text !font-bold' : '!text-text-muted'}`}>
                    {row.label}
                  </span>
                  {row.value > 0 ? (
                    <StatusBadge tone={row.tone}>{row.value} รายการ</StatusBadge>
                  ) : (
                    <span className="!text-sm !text-text-muted" aria-label="ไม่มีรายการ">—</span>
                  )}
                </RowTag>
              );
            })}
          </div>
        </Panel>
      ) : null}

      {mode !== 'employee' ? (
        <StatGrid>
          {cards.map((card) => (
            <StatCard key={`${card.label}-${card.helper}`} {...card} />
          ))}
        </StatGrid>
      ) : null}

      <div className={mode === 'employee' ? '' : DASHBOARD_GRID}>
        {mode !== 'employee' && quickActions.length > 0 ? (
          <Panel title="การดำเนินการด่วน">
            <div className="action-list">
              {quickActions.map(([path, label]) => (
                <button type="button" key={path} onClick={() => navigate(path)}>{label}</button>
              ))}
            </div>
          </Panel>
        ) : null}

        <Panel
          title={mode === 'company' ? 'คำขอล่าสุด' : 'คำขอล่าสุดของฉัน'}
          actions={canViewRequestsFeed ? (
            <Button type="button" variant="text" onClick={() => navigate(requestsFeedPath)}>ดูทั้งหมด</Button>
          ) : null}
        >
          <div className="request-feed">
            {profileRequests.length === 0 ? (
              <div className="empty-state">ยังไม่มีคำขอล่าสุด</div>
            ) : profileRequests.slice(0, 5).map((request) => {
              const status = requestStatus(request.status);
              return (
                <div className="request-feed-item compact" key={request.id}>
                  <span>
                    <strong>ขอแก้ไข{request.fieldLabel}</strong>
                    <small>{formatShortDate(request.requestedAt)}</small>
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
