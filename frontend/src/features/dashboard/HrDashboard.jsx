import { useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button } from '../../components/common/Button.jsx';
import { StatCard } from '../../components/common/StatCard.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { Avatar } from '../../components/common/Avatar.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { formatShortDate, requestStatus } from '../../utils/format.js';
import { divisions, findDivision } from '../../data/referenceData.js';

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

  return (
    <div className="page-stack">
      <PageHeader
        title={`สวัสดี, คุณ${employee?.nickName || employee?.nameTh || ''}`}
        subtitle="ภาพรวมระบบทรัพยากรบุคคล"
      />

      <div className="stat-grid">
        <StatCard icon="users" label="พนักงานทั้งหมด" value={dashboardStats.totalCount} helper="Total employees" tone="indigo" />
        <StatCard icon="badgeCheck" label="ทำงานปกติ" value={dashboardStats.activeCount} helper="Active" tone="teal" />
        <StatCard icon="clipboard" label="รออนุมัติทั้งหมด" value={dashboardStats.pendingCount} helper="Approvals" tone="rose" />
        <StatCard icon="badgeCheck" label="มาวันนี้" value={dashboardStats.todayPresent} helper="Attendance" tone="teal" />
      </div>

      <div className="stat-grid">
        <StatCard icon="userCog" label="แก้ไขข้อมูล" value={dashboardStats.pendingProfileRequests} helper="Profile requests" tone="amber" />
        <StatCard icon="clock" label="OT รออนุมัติ" value={dashboardStats.pendingOvertime} helper="Overtime" tone="blue" />
        <StatCard icon="calendar" label="ลารออนุมัติ" value={dashboardStats.pendingLeave} helper="Leave" tone="teal" />
        <StatCard icon="bell" label="แจ้งเตือนยังไม่อ่าน" value={dashboardStats.unreadNotifications} helper="Unread" tone="indigo" />
      </div>

      <div className="dashboard-grid">
        <section className="panel">
          <div className="panel-header">
            <h2>จำนวนพนักงานตามฝ่าย</h2>
            <Button type="button" variant="text" onClick={() => navigate('/employees')}>ดูรายชื่อ</Button>
          </div>
          <div className="bar-list">
            {dashboardStats.divisionRows.map(({ division, count }) => {
              return (
                <div className="bar-row" key={division.id}>
                  <span>
                    <strong>{division.th}</strong>
                    <small>{division.en}</small>
                  </span>
                  <div className="bar-track">
                    <i style={{ width: `${Math.max(8, Math.round((count / dashboardStats.maxHeadcount) * 100))}%` }} />
                  </div>
                  <b>{count}</b>
                </div>
              );
            })}
          </div>
        </section>

        <section className="panel">
          <div className="panel-header">
            <h2>คำขอล่าสุด</h2>
            <Button type="button" variant="text" onClick={() => navigate('/requests')}>ดูทั้งหมด</Button>
          </div>
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
        </section>
      </div>
    </div>
  );
}
