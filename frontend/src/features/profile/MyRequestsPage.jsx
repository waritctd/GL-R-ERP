import { useNavigate } from 'react-router-dom';
import { Button } from '../../components/common/Button.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack } from '../../components/common/Layout.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatShortDate, requestStatus } from '../../utils/format.js';

const MY_REQUESTS_TABLE_GRID = 'grid-cols-[minmax(0,1.2fr)_minmax(0,2fr)_minmax(0,2fr)_minmax(0,0.9fr)_minmax(0,1fr)] max-[1040px]:min-w-[900px] reflow-cards';

export function MyRequestsPage({ profileRequests }) {
  const navigate = useNavigate();
  const pendingCount = profileRequests.filter((request) => request.status === 'pending').length;

  return (
    <PageStack>
      <PageHeader
        title="คำขอของฉัน"
        subtitle={`${pendingCount} คำขอรออนุมัติ`}
        actions={<Button type="button" onClick={() => navigate('/profile')}>ส่งคำขอใหม่</Button>}
      />

      <section className="table-panel">
        <div className={`${MY_REQUESTS_TABLE_GRID} table-head`}>
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
            <div className={`${MY_REQUESTS_TABLE_GRID} data-row`} key={request.id}>
              <StatusBadge tone="indigo">{request.fieldLabel}</StatusBadge>
              <span className="old-value">{request.oldValue}</span>
              <strong>{request.newValue}</strong>
              <span>{formatShortDate(request.requestedAt)}</span>
              <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
            </div>
          );
        })}
      </section>
    </PageStack>
  );
}
