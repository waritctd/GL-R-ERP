import { StatCard } from '../../components/common/StatCard.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { Avatar } from '../../components/common/Avatar.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { formatShortDate, requestStatus } from '../../utils/format.js';

export function EmployeeDashboard({ employee, profileRequests, onRoute }) {
  const pendingCount = profileRequests.filter((request) => request.status === 'pending').length;
  const years = employee?.hireDate ? new Date().getFullYear() - new Date(employee.hireDate).getFullYear() : 0;

  return (
    <div className="page-stack">
      <PageHeader title={`สวัสดี, คุณ${employee?.nickName || employee?.nameTh || ''}`} subtitle={`${employee?.positionTh || ''} · ${employee?.divisionTh || ''}`} />

      <section className="profile-strip">
        <Avatar employee={employee} size="lg" />
        <div>
          <h2>{employee?.nameTh}</h2>
          <p>{employee?.positionTh} · {employee?.departmentTh} · <code>{employee?.code}</code></p>
        </div>
        <StatusBadge tone={employee?.statusTone}>{employee?.statusTh}</StatusBadge>
      </section>

      <div className="stat-grid">
        <StatCard icon="briefcase" label="ตำแหน่ง" value={employee?.positionTh} tone="indigo" />
        <StatCard icon="building" label="สังกัด" value={employee?.divisionTh} tone="teal" />
        <StatCard icon="calendar" label="อายุงาน" value={`${years} ปี`} tone="amber" />
        <StatCard icon="clipboard" label="คำขอรออนุมัติ" value={pendingCount} tone="rose" />
      </div>

      <div className="dashboard-grid">
        <section className="panel">
          <div className="panel-header">
            <h2>การดำเนินการด่วน</h2>
          </div>
          <div className="action-list">
            <button type="button" onClick={() => onRoute('profile')}>ดูข้อมูลของฉัน</button>
            <button type="button" onClick={() => onRoute('myrequests')}>ติดตามคำขอแก้ไข</button>
          </div>
        </section>

        <section className="panel">
          <div className="panel-header">
            <h2>คำขอล่าสุดของฉัน</h2>
            <button type="button" className="text-button" onClick={() => onRoute('myrequests')}>ดูทั้งหมด</button>
          </div>
          <div className="request-feed">
            {profileRequests.slice(0, 5).map((request) => {
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
        </section>
      </div>
    </div>
  );
}
