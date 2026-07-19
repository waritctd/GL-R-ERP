import { useState } from 'react';
import { Avatar } from '../../components/common/Avatar.jsx';
import { Button } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, RowActions } from '../../components/common/Layout.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { formatShortDate, requestStatus } from '../../utils/format.js';

export function ProfileRequestsPage({ profileRequests, onReview, showToast }) {
  const pendingCount = profileRequests.filter((request) => request.status === 'pending').length;
  const [confirmState, setConfirmState] = useState(null);
  const [busy, setBusy] = useState(false);

  function approve(id) {
    setConfirmState({ kind: 'approve', id });
  }

  function reject(id) {
    setConfirmState({ kind: 'reject', id });
  }

  async function confirmApprove() {
    setBusy(true);
    try {
      await onReview(confirmState.id, 'approved');
      setConfirmState(null);
    } catch (error) {
      showToast?.('error', error?.message || 'อนุมัติคำขอไม่สำเร็จ');
    } finally {
      setBusy(false);
    }
  }

  async function confirmReject(reason) {
    if (!reason?.trim()) return;
    setBusy(true);
    try {
      await onReview(confirmState.id, 'rejected', reason.trim());
      setConfirmState(null);
    } catch (error) {
      showToast?.('error', error?.message || 'ปฏิเสธคำขอไม่สำเร็จ');
    } finally {
      setBusy(false);
    }
  }

  const confirmRequest = confirmState
    ? profileRequests.find((request) => request.id === confirmState.id)
    : null;

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
                    onClick={() => reject(request.id)}
                    title="ปฏิเสธ"
                    aria-label="ปฏิเสธ"
                  >
                    <Icon name="close" />
                  </Button>
                  <Button type="button" variant="success" onClick={() => approve(request.id)}>
                    <Icon name="check" />
                    อนุมัติ
                  </Button>
                </RowActions>
              ) : <StatusBadge tone={status.tone}>{status.label}</StatusBadge>}
            </div>
          );
        })}
      </section>

      <ConfirmDialog
        open={confirmState?.kind === 'approve'}
        title="ยืนยันการอนุมัติคำขอแก้ไขข้อมูล"
        message={confirmRequest ? (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            <p className="confirm-dialog-message" style={{ margin: 0 }}>
              การอนุมัติจะบันทึกข้อมูลนี้ลงในทะเบียนพนักงานของ{' '}
              <strong>{confirmRequest.employee?.nameTh || confirmRequest.employeeId}</strong> ทันที
            </p>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13, borderTop: '1px solid var(--color-border)', paddingTop: 8 }}>
              <span style={{ color: 'var(--color-icon-muted)' }}>{confirmRequest.fieldLabel}</span>
              <span className="font-mono">{confirmRequest.oldValue} → {confirmRequest.newValue}</span>
            </div>
            <p style={{ margin: 0, fontSize: 12, color: 'var(--color-icon-muted)' }}>
              สถานะจะเปลี่ยนเป็น &quot;อนุมัติแล้ว&quot; และไม่สามารถย้อนกลับได้
            </p>
          </div>
        ) : 'ยืนยันการอนุมัติคำขอแก้ไขข้อมูลนี้? การอนุมัติจะบันทึกลงทะเบียนพนักงานทันที'}
        confirmLabel="อนุมัติ"
        busy={busy}
        onConfirm={confirmApprove}
        onCancel={() => setConfirmState(null)}
      />
      <ConfirmDialog
        open={confirmState?.kind === 'reject'}
        title="ปฏิเสธคำขอแก้ไขข้อมูล"
        message={confirmRequest ? (
          <p className="confirm-dialog-message" style={{ margin: 0 }}>
            ยืนยันการปฏิเสธคำขอแก้ไข <strong>{confirmRequest.fieldLabel}</strong> ของ{' '}
            <strong>{confirmRequest.employee?.nameTh || confirmRequest.employeeId}</strong>?
            สถานะจะเปลี่ยนเป็น &quot;ปฏิเสธแล้ว&quot; และไม่สามารถอนุมัติย้อนหลังได้
          </p>
        ) : 'ยืนยันการปฏิเสธคำขอนี้? สถานะจะเปลี่ยนเป็น "ปฏิเสธแล้ว" และไม่สามารถอนุมัติย้อนหลังได้'}
        confirmLabel="ปฏิเสธคำขอ"
        tone="danger"
        busy={busy}
        requireReason
        reasonLabel="เหตุผลการปฏิเสธ"
        reasonPlaceholder="ระบุเหตุผลที่ปฏิเสธคำขอนี้"
        onConfirm={confirmReject}
        onCancel={() => setConfirmState(null)}
      />
    </PageStack>
  );
}
