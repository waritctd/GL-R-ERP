import { Avatar } from '../../components/common/Avatar.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatShortDate, requestStatus } from '../../utils/format.js';

export function ProfileRequestsPage({ profileRequests, onReview }) {
  const pendingCount = profileRequests.filter((request) => request.status === 'pending').length;

  return (
    <div className="page-stack">
      <PageHeader title="คำขอแก้ไขข้อมูล" subtitle={`${pendingCount} คำขอรอการอนุมัติ`} />

      <section className="table-panel">
        <div className="request-table table-head">
          <span>พนักงาน</span>
          <span>ข้อมูลที่ขอแก้</span>
          <span>ค่าเดิม</span>
          <span>ค่าใหม่</span>
          <span>วันที่</span>
          <span>การดำเนินการ</span>
        </div>
        {profileRequests.length === 0 ? (
          <EmptyState icon="clipboard" title="ยังไม่มีคำขอ" />
        ) : profileRequests.map((request) => {
          const status = requestStatus(request.status);
          return (
            <div className="request-table table-row" key={request.id}>
              <span className="employee-cell">
                <Avatar employee={request.employee} size="sm" />
                <span>
                  <strong>{request.employee?.nameTh}</strong>
                  <small>{request.employee?.code}</small>
                </span>
              </span>
              <StatusBadge tone="indigo">{request.fieldLabel}</StatusBadge>
              <span className="old-value">{request.oldValue}</span>
              <strong>{request.newValue}</strong>
              <span>{formatShortDate(request.requestedAt)}</span>
              {request.status === 'pending' ? (
                <span className="row-actions">
                  <button type="button" className="danger-button icon-only" onClick={() => onReview(request.id, 'rejected')} title="ปฏิเสธ">
                    <Icon name="close" />
                  </button>
                  <button type="button" className="success-button" onClick={() => onReview(request.id, 'approved')}>
                    <Icon name="check" />
                    อนุมัติ
                  </button>
                </span>
              ) : <StatusBadge tone={status.tone}>{status.label}</StatusBadge>}
            </div>
          );
        })}
      </section>
    </div>
  );
}
