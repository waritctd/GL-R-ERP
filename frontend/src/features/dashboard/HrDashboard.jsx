import { useMemo } from 'react';
import { StatCard } from '../../components/common/StatCard.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { Avatar } from '../../components/common/Avatar.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { formatShortDate, requestStatus } from '../../utils/format.js';
import { divisions } from '../../data/demoData.js';

export function HrDashboard({ employee, employees, profileRequests, onRoute }) {
  const dashboardStats = useMemo(() => {
    const headcountByDivision = new Map(divisions.map((division) => [division.id, 0]));
    let activeCount = 0;
    let probationCount = 0;

    employees.forEach((item) => {
      if (item.active) activeCount += 1;
      if (item.statusId === 'PRB') probationCount += 1;
      if (headcountByDivision.has(item.divisionId)) {
        headcountByDivision.set(item.divisionId, headcountByDivision.get(item.divisionId) + 1);
      }
    });

    return {
      activeCount,
      probationCount,
      pendingCount: profileRequests.reduce((count, item) => count + (item.status === 'pending' ? 1 : 0), 0),
      headcountByDivision,
      maxHeadcount: Math.max(...headcountByDivision.values(), 1),
    };
  }, [employees, profileRequests]);

  return (
    <div className="page-stack">
      <PageHeader
        title={`สวัสดี, คุณ${employee?.nickName || employee?.nameTh || ''}`}
        subtitle="ภาพรวมระบบทรัพยากรบุคคล"
      />

      <div className="stat-grid">
        <StatCard icon="users" label="พนักงานทั้งหมด" value={employees.length} helper="Total employees" tone="indigo" />
        <StatCard icon="badgeCheck" label="ทำงานปกติ" value={dashboardStats.activeCount} helper="Active" tone="teal" />
        <StatCard icon="clock" label="ทดลองงาน" value={dashboardStats.probationCount} helper="Probation" tone="amber" />
        <StatCard icon="clipboard" label="รออนุมัติ" value={dashboardStats.pendingCount} helper="Profile requests" tone="rose" />
      </div>

      <div className="dashboard-grid">
        <section className="panel">
          <div className="panel-header">
            <h2>จำนวนพนักงานตามฝ่าย</h2>
            <button type="button" className="text-button" onClick={() => onRoute('employees')}>ดูรายชื่อ</button>
          </div>
          <div className="bar-list">
            {divisions.map((division) => {
              const count = dashboardStats.headcountByDivision.get(division.id) || 0;
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
            <button type="button" className="text-button" onClick={() => onRoute('requests')}>ดูทั้งหมด</button>
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
