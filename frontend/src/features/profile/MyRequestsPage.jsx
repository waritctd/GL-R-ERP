import { useNavigate } from 'react-router-dom';
import { Button } from '../../components/common/Button.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatShortDate, requestStatus } from '../../utils/format.js';

export function MyRequestsPage({ profileRequests }) {
  const navigate = useNavigate();
  const pendingCount = profileRequests.filter((request) => request.status === 'pending').length;

  return (
    <div className="page-stack">
      <PageHeader
        title="คำขอของฉัน"
        subtitle={`${pendingCount} คำขอรออนุมัติ`}
        actions={<Button type="button" onClick={() => navigate('/profile')}>ส่งคำขอใหม่</Button>}
      />

      <section className="table-panel">
        <div className="request-table mine table-head">
          <span>ข้อมูลที่ขอแก้</span>
          <span>ค่าเดิม</span>
          <span>ค่าใหม่</span>
          <span>วันที่</span>
          <span>สถานะ</span>
        </div>
        {profileRequests.length === 0 ? (
          <EmptyState icon="clipboard" title="ยังไม่มีคำขอแก้ไข" />
        ) : profileRequests.map((request) => {
          const status = requestStatus(request.status);
          return (
            <div className="request-table mine table-row" key={request.id}>
              <StatusBadge tone="indigo">{request.fieldLabel}</StatusBadge>
              <span className="old-value">{request.oldValue}</span>
              <strong>{request.newValue}</strong>
              <span>{formatShortDate(request.requestedAt)}</span>
              <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
            </div>
          );
        })}
      </section>
    </div>
  );
}
