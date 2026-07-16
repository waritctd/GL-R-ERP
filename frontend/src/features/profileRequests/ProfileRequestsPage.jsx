import { Avatar } from '../../components/common/Avatar.jsx';
import { Button } from '../../components/common/Button.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, RowActions } from '../../components/common/Layout.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatShortDate, requestStatus } from '../../utils/format.js';

export function ProfileRequestsPage({ profileRequests, onReview }) {
  const pendingCount = profileRequests.filter((request) => request.status === 'pending').length;

  return (
    <PageStack>
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
            <div className="request-table data-row" key={request.id}>
              <span className="employee-cell" data-label="พนักงาน">
                <Avatar employee={request.employee} size="sm" />
                <span>
                  <strong>{request.employee?.nameTh}</strong>
                  <small>{request.employee?.code}</small>
                </span>
              </span>
              <StatusBadge tone="indigo">{request.fieldLabel}</StatusBadge>
              <span className="old-value" data-label="ค่าเดิม">{request.oldValue}</span>
              <span data-label="ค่าใหม่"><strong>{request.newValue}</strong></span>
              <span data-label="วันที่">{formatShortDate(request.requestedAt)}</span>
              {request.status === 'pending' ? (
                <RowActions>
                  <Button
                    type="button"
                    variant="danger"
                    className="w-9 p-0"
                    onClick={() => onReview(request.id, 'rejected')}
                    title="ปฏิเสธ"
                    aria-label="ปฏิเสธ"
                  >
                    <Icon name="close" />
                  </Button>
                  <Button type="button" variant="success" onClick={() => onReview(request.id, 'approved')}>
                    <Icon name="check" />
                    อนุมัติ
                  </Button>
                </RowActions>
              ) : <StatusBadge tone={status.tone}>{status.label}</StatusBadge>}
            </div>
          );
        })}
      </section>
    </PageStack>
  );
}
