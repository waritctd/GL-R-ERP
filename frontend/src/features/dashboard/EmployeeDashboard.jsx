import { useNavigate } from 'react-router-dom';
import { Button } from '../../components/common/Button.jsx';
import { StatCard } from '../../components/common/StatCard.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { Avatar } from '../../components/common/Avatar.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, Panel, StatGrid } from '../../components/common/Layout.jsx';
import { formatShortDate, requestStatus } from '../../utils/format.js';

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

function dashboardCards(mode, summary, pendingCount) {
  const headcount = summary?.headcount ?? {};
  const pending = summary?.pendingApprovals ?? {};
  const attendance = summary?.attendance ?? {};
  const notifications = summary?.notifications ?? {};
  const tickets = summary?.tickets ?? {};

  if (mode === 'company') {
    return [
      { icon: 'users', label: 'พนักงาน Active', value: numberValue(headcount.active), helper: 'Headcount', tone: 'indigo' },
      { icon: 'clipboard', label: 'รออนุมัติทั้งหมด', value: numberValue(pending.total), helper: 'Approvals', tone: 'rose' },
      { icon: 'userCog', label: 'แก้ไขข้อมูล', value: numberValue(pending.profileRequests), helper: 'Profile requests', tone: 'amber' },
      { icon: 'clock', label: 'OT รออนุมัติ', value: numberValue(pending.overtime), helper: 'Overtime', tone: 'blue' },
      { icon: 'calendar', label: 'ลารออนุมัติ', value: numberValue(pending.leave), helper: 'Leave', tone: 'teal' },
      { icon: 'badgeCheck', label: 'มาวันนี้', value: numberValue(attendance.todayPresent), helper: 'Attendance', tone: 'teal' },
      { icon: 'clock', label: 'มาสายวันนี้', value: numberValue(attendance.lateToday), helper: 'Late today', tone: numberValue(attendance.lateToday) > 0 ? 'amber' : 'indigo' },
      { icon: 'bell', label: 'แจ้งเตือนยังไม่อ่าน', value: numberValue(notifications.unread), helper: 'Unread', tone: 'indigo' },
      ...(tickets.total > 0 ? [
        { icon: 'fileText', label: 'ใบขอราคาเปิดอยู่', value: numberValue(tickets.totalOpen), helper: 'Tickets', tone: 'blue' },
      ] : []),
    ];
  }

  if (mode === 'manager') {
    return [
      { icon: 'users', label: 'พนักงานในฝ่าย', value: numberValue(headcount.active), helper: 'Active', tone: 'indigo' },
      { icon: 'clock', label: 'OT รออนุมัติ', value: numberValue(pending.overtime), helper: 'Division', tone: 'amber' },
      { icon: 'calendar', label: 'ลารออนุมัติ', value: numberValue(pending.leave), helper: 'Division', tone: 'teal' },
      { icon: 'badgeCheck', label: 'มาวันนี้', value: numberValue(attendance.todayPresent), helper: 'Attendance', tone: 'teal' },
      { icon: 'clock', label: 'มาสายวันนี้', value: numberValue(attendance.lateToday), helper: 'Late today', tone: numberValue(attendance.lateToday) > 0 ? 'amber' : 'indigo' },
      { icon: 'bell', label: 'แจ้งเตือนยังไม่อ่าน', value: numberValue(notifications.unread), helper: 'Unread', tone: 'indigo' },
      ...(tickets.total > 0 ? [
        { icon: 'fileText', label: 'ใบขอราคาเปิดอยู่', value: numberValue(tickets.totalOpen), helper: 'Tickets', tone: 'blue' },
      ] : []),
    ];
  }

  return [
    { icon: 'badgeCheck', label: 'สถานะวันนี้', value: attendanceStatus(attendance.todayStatus), helper: 'Attendance', tone: attendance.todayStatus === 'PRESENT' ? 'teal' : 'amber' },
    { icon: 'clock', label: 'OT ของฉัน', value: numberValue(pending.overtime), helper: 'Pending', tone: 'amber' },
    { icon: 'calendar', label: 'ลาของฉัน', value: numberValue(pending.leave), helper: 'Pending', tone: 'teal' },
    { icon: 'clipboard', label: 'คำขอแก้ไขข้อมูล', value: numberValue(pending.profileRequests ?? pendingCount), helper: 'Pending', tone: 'rose' },
    { icon: 'bell', label: 'แจ้งเตือนยังไม่อ่าน', value: numberValue(notifications.unread), helper: 'Unread', tone: 'indigo' },
  ];
}

export function EmployeeDashboard({ user, employee, profileRequests = [], dashboardSummary }) {
  const navigate = useNavigate();
  const pendingCount = profileRequests.filter((request) => request.status === 'pending').length;
  const years = employee?.hireDate ? new Date().getFullYear() - new Date(employee.hireDate).getFullYear() : 0;
  const mode = dashboardMode(user, dashboardSummary);
  const cards = dashboardCards(mode, dashboardSummary, pendingCount);
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

  return (
    <PageStack>
      <PageHeader title={title} subtitle={subtitle} />

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
