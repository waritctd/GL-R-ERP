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
import { hasPermission } from '../../app/permissions.js';
import { SALES_ENABLED } from '../../app/features.js';
import { ActionQueue } from './ActionQueue.jsx';

/**
 * Reproduces `.dashboard-grid`: grid-template-columns: 1.15fr 0.85fr; gap: 18px;
 * align-items: start; ≤1040px: repeat(2, minmax(0, 1fr)); ≤720px: 1fr.
 * No shared primitive yet for this exact column ratio, so it's kept local
 * (matches the breakpoints of `.stat-grid`/`.dashboard-grid` in styles.css).
 */
const DASHBOARD_GRID = 'grid gap-[18px] items-start grid-cols-[1.15fr_0.85fr] max-[1040px]:grid-cols-2 max-[720px]:grid-cols-1';

const COMPANY_ROLES = ['hr', 'admin', 'ceo'];

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
    myRequests: () => hasPermission(user?.role, 'canSubmitProfileRequests'),
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
      { icon: 'userCog', label: 'แก้ไขข้อมูล', value: numberValue(pending.profileRequests), helper: 'Profile requests', tone: 'amber', onClick: go('/requests', access.reviewRequests()) },
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

  return [
    { icon: 'badgeCheck', label: 'สถานะวันนี้', value: attendanceStatus(attendance.todayStatus), helper: 'Attendance', tone: attendance.todayStatus === 'PRESENT' ? 'teal' : 'amber', onClick: go('/attendance', access.attendance()) },
    { icon: 'clock', label: 'OT ของฉัน', value: numberValue(pending.overtime), helper: 'Pending', tone: 'amber', onClick: go('/overtime', access.overtime()) },
    { icon: 'calendar', label: 'ลาของฉัน', value: numberValue(pending.leave), helper: 'Pending', tone: 'teal', onClick: go('/leave', access.leave()) },
    { icon: 'clipboard', label: 'คำขอแก้ไขข้อมูล', value: numberValue(pending.profileRequests ?? pendingCount), helper: 'Pending', tone: 'rose', onClick: go('/my-requests', access.myRequests()) },
    { icon: 'bell', label: 'แจ้งเตือนยังไม่อ่าน', value: numberValue(notifications.unread), helper: 'Unread', tone: 'indigo' },
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
    { key: 'profileRequests', label: 'คำขอแก้ไขข้อมูลรออนุมัติ', value: numberValue(pending.profileRequests), to: go('/requests', access.reviewRequests()) },
    { key: 'overtime', label: 'OT รออนุมัติ', value: numberValue(pending.overtime), to: go('/overtime', access.overtime()) },
    { key: 'leave', label: 'ลารออนุมัติ', value: numberValue(pending.leave), to: go('/leave', access.leave()) },
    { key: 'commissions', label: 'ค่าคอมมิชชั่นรออนุมัติ', value: numberValue(pending.commissions), to: go('/commissions', access.commissions()) },
    { key: 'notifications', label: 'แจ้งเตือนยังไม่อ่าน', value: numberValue(notifications.unread) },
  ];
  return items;
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
  const queueItems = actionQueueItems(mode, dashboardSummary, { navigate, access });
  const title = mode === 'company'
    ? `สวัสดี, คุณ${employee?.nickName || employee?.nameTh || user?.name || ''}`
    : `สวัสดี, คุณ${employee?.nickName || employee?.nameTh || user?.name || ''}`;
  const subtitle = mode === 'company'
    ? 'ภาพรวมระบบทรัพยากรบุคคล'
    : mode === 'manager'
      ? `${employee?.positionTh || 'Manager'} · ${employee?.divisionTh || ''}`
      : `${employee?.positionTh || ''} · ${employee?.divisionTh || ''}`;
  const quickActions = mode === 'company'
    ? [
      ['/hr', 'ภาพรวม HR'],
      ['/employees', 'รายชื่อพนักงาน'],
      ['/requests', 'คำขอแก้ไขข้อมูล'],
      ['/attendance', 'ข้อมูลเวลาเข้างาน'],
    ]
    : [
      ['/profile', 'ดูข้อมูลของฉัน'],
      ['/my-requests', 'ติดตามคำขอแก้ไข'],
      ['/overtime', 'รายการ OT'],
      ['/leave', 'รายการลา'],
    ];
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

      {employee ? (
        <section className="profile-strip">
          <Avatar employee={employee} size="lg" />
          <div>
            <h2>{employee?.nameTh}</h2>
            <p>{employee?.positionTh} · {employee?.departmentTh} · <code>{employee?.code}</code></p>
          </div>
          <StatusBadge tone={employee?.statusTone}>{employee?.statusTh}</StatusBadge>
        </section>
      ) : null}

      <StatGrid>
        {cards.map((card) => (
          <StatCard key={`${card.label}-${card.helper}`} {...card} />
        ))}
        {mode === 'employee' ? (
          <>
            <StatCard icon="briefcase" label="ตำแหน่ง" value={employee?.positionTh || '-'} tone="indigo" />
            <StatCard icon="building" label="สังกัด" value={employee?.divisionTh || '-'} tone="teal" />
            <StatCard icon="calendar" label="อายุงาน" value={`${years} ปี`} tone="amber" />
          </>
        ) : null}
      </StatGrid>

      <div className={DASHBOARD_GRID}>
        <Panel title="การดำเนินการด่วน">
          <div className="action-list">
            {quickActions.map(([path, label]) => (
              <button type="button" key={path} onClick={() => navigate(path)}>{label}</button>
            ))}
          </div>
        </Panel>

        <Panel
          title={mode === 'company' ? 'คำขอล่าสุด' : 'คำขอล่าสุดของฉัน'}
          actions={<Button type="button" variant="text" onClick={() => navigate(mode === 'company' ? '/requests' : '/my-requests')}>ดูทั้งหมด</Button>}
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
