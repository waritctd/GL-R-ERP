import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { CompactStatRow } from '../../components/common/CompactStatRow.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
// FilterField is main's (#ef3c744e) shrinkable/uniform filter control -- kept, the status filter
// below still uses it. formGridSpan2 and PageStack went with the submit form and the PageStack
// root respectively, both of which moved out of this file.
import { FilterField, Panel, RowActions } from '../../components/common/Layout.jsx';
import { SafeForm } from '../../components/common/SafeForm.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { attendanceCorrectionStatusLabel as statusInfo } from '../../utils/format.js';

const CORRECTION_TABLE_GRID = 'grid-cols-[minmax(0,1fr)_minmax(0,1.15fr)_minmax(0,1.4fr)_minmax(0,0.9fr)_minmax(0,0.75fr)] nav-drawer:min-w-[860px] reflow-cards';
// FilterBar (Layout.jsx) renders a <div>; the status-filter form needs native submit semantics
// (Enter-to-submit) so this exact utility string is reproduced here the same way OvertimePanel.jsx
// does for its own filter form.
const FILTER_BAR_CLASS = 'flex flex-wrap gap-[10px] items-end bg-surface border border-border rounded-md p-[14px]';

const CORRECTION_TYPE_LABELS = {
  CHECK_IN: 'เวลาเข้างาน',
  CHECK_OUT: 'เวลาออกงาน',
  BOTH: 'ทั้งเข้างานและออกงาน',
};

function formatWorkDate(value) {
  if (!value) return '-';
  const date = new Date(`${value}T00:00:00+07:00`);
  if (Number.isNaN(date.getTime())) return '-';
  return new Intl.DateTimeFormat('th-TH', { dateStyle: 'medium', timeZone: 'Asia/Bangkok' }).format(date);
}

function formatTime(value) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '-';
  return new Intl.DateTimeFormat('th-TH', { timeStyle: 'short', hour12: false, timeZone: 'Asia/Bangkok' }).format(date);
}

function formatDateTime(value) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '-';
  return new Intl.DateTimeFormat('th-TH', {
    dateStyle: 'medium', timeStyle: 'short', hour12: false, timeZone: 'Asia/Bangkok',
  }).format(date);
}

/**
 * The list/review half of "ขอแก้ไขเวลาเข้า-ออกงาน" -- the submit half moved into
 * AttendanceCorrectionRequestModal.jsx, opened from a button on AttendancePage.jsx
 * (fix/attendance-correction-on-attendance-page). This component now renders as its own
 * section on /attendance, below that page's daily attendance table, rather than as a tab
 * inside RequestsPage (/employee-requests) -- see RequestsPage.jsx's own header comment for
 * where the tab used to be and why it moved.
 */
export function AttendanceCorrectionPanel({ user, showToast }) {
  const queryClient = useQueryClient();
  const isCeo = user.role === 'ceo';
  const [confirmState, setConfirmState] = useState(null);
  const [statusFilter, setStatusFilter] = useState('');

  const requestsQuery = useQuery({
    queryKey: queryKeys.attendanceCorrectionRequests({ status: statusFilter }),
    queryFn: () => api.attendanceCorrection.list({ status: statusFilter || undefined })
      .then((response) => response.requests || []),
    refetchInterval: 60_000,
    refetchOnWindowFocus: true,
  });
  const requests = useMemo(() => requestsQuery.data ?? [], [requestsQuery.data]);
  const loading = requestsQuery.isLoading || requestsQuery.isFetching;

  function invalidateAttendanceCorrection() {
    return queryClient.invalidateQueries({ queryKey: ['attendanceCorrection'] });
  }

  const approveMutation = useMutation({
    mutationFn: ({ id, reviewerNote }) => api.attendanceCorrection.approve(id, { reviewerNote }).then((response) => response.request),
    onSuccess: () => {
      showToast('success', 'อนุมัติคำขอแก้ไขเวลาแล้ว');
      setConfirmState(null);
      invalidateAttendanceCorrection();
      // Deliberate addition (fix/attendance-correction-on-attendance-page): approving doesn't
      // just flip this request's own status -- AttendanceCorrectionService#approve (backend)
      // inserts a real hr.attendance_punch row and flips the day's is_manual_override via
      // AttendanceDailyService#applyManualCorrection. This list now sits on the SAME page as
      // the attendance day table (it used to be a standalone tab on /employee-requests, nowhere
      // near attendance data), so an approval must also invalidate that table's query or it
      // keeps showing the pre-correction day until the next 60s poll. queryKeys.attendanceDaily
      // and .attendanceDailyScoped both start with ['attendance', 'daily', ...], so invalidating
      // the ['attendance'] prefix catches both the self-view and the HR/CEO/manager-scoped query
      // in one call. Reject and cancel never write to attendance_punch/attendance_daily (same
      // backend javadoc), so they intentionally do NOT invalidate this -- only approve does.
      queryClient.invalidateQueries({ queryKey: ['attendance'] });
    },
    onError: (error) => showToast('error', error.message || 'อนุมัติคำขอไม่สำเร็จ'),
  });

  const rejectMutation = useMutation({
    mutationFn: ({ id, reviewerNote }) => api.attendanceCorrection.reject(id, { reviewerNote }).then((response) => response.request),
    onSuccess: () => {
      showToast('success', 'ปฏิเสธคำขอแก้ไขเวลาแล้ว');
      setConfirmState(null);
      invalidateAttendanceCorrection();
    },
    onError: (error) => showToast('error', error.message || 'ปฏิเสธคำขอไม่สำเร็จ'),
  });

  const cancelMutation = useMutation({
    mutationFn: (id) => api.attendanceCorrection.cancel(id).then((response) => response.request),
    onSuccess: () => {
      showToast('success', 'ยกเลิกคำขอแก้ไขเวลาแล้ว');
      setConfirmState(null);
      invalidateAttendanceCorrection();
    },
    onError: (error) => showToast('error', error.message || 'ยกเลิกคำขอไม่สำเร็จ'),
  });

  const saving = approveMutation.isPending || rejectMutation.isPending || cancelMutation.isPending;

  function approve(id) {
    setConfirmState({ kind: 'approve', id });
  }

  function confirmApprove() {
    approveMutation.mutate({ id: confirmState.id, reviewerNote: null });
  }

  function reject(id) {
    setConfirmState({ kind: 'reject', id });
  }

  function confirmReject(reviewerNote) {
    if (!reviewerNote?.trim()) return;
    rejectMutation.mutate({ id: confirmState.id, reviewerNote: reviewerNote.trim() });
  }

  function cancel(id) {
    setConfirmState({ kind: 'cancel', id });
  }

  function confirmCancel() {
    cancelMutation.mutate(confirmState.id);
  }

  const totals = useMemo(() => ({
    submitted: requests.filter((request) => request.status === 'SUBMITTED').length,
    approved: requests.filter((request) => request.status === 'APPROVED').length,
  }), [requests]);

  return (
    // Not <PageStack>: this panel is now embedded inside AttendancePage.jsx's OWN PageStack
    // (fix/attendance-correction-on-attendance-page) rather than owning a page of its own, and a
    // bare fragment lets its sections become direct children of that outer grid -- so they pick
    // up its 18px gap for free, with no extra wrapper and no risk of a nested grid's `gap`
    // stacking with its parent's. Same pattern MyLeaveTab.jsx/TeamLeaveTab.jsx already use for the
    // identical shape (a tab's content embedded in LeaveSurfacePage.jsx's own PageStack).
    <>
      <div className="flex items-center justify-between gap-3">
        <div>
          {/* This panel's own section heading, not a <PageHeader> -- it used to sit inside
              RequestsPage, which owned the page's only h1, so it never needed one of its own.
              Embedded on AttendancePage.jsx now, below that page's h1 and daily table, it needs
              something identifying where this block starts. */}
          <h2 className="m-0 min-w-0 text-lg break-words">คำขอแก้ไขเวลาเข้า-ออกงาน</h2>
          <p className="m-0 mt-1 text-sm text-text-muted">
            {isCeo
              ? 'พิจารณาคำขอแก้ไขเวลาเข้า-ออกงานของพนักงาน'
              : 'กดปุ่ม "ขอแก้ไขเวลา" ด้านบนเพื่อยื่นคำขอเมื่อลืมสแกนนิ้ว และดูประวัติคำขอของคุณได้ด้านล่าง'}
          </p>
        </div>
        <Button
          type="button"
          variant="secondary"
          onClick={() => requestsQuery.refetch()}
          disabled={loading}
          // Distinct from AttendancePage's own "รีเฟรช" (refetches the day table, not this list) --
          // both buttons now live on the same page (fix/attendance-correction-on-attendance-page),
          // and an identical accessible name is a real problem for anyone navigating by button
          // list. Visible text stays "รีเฟรช" -- pattern matches AttendancePage.jsx's own
          // scanDetail button: a short visible label paired with a fuller aria-label.
          aria-label="รีเฟรชคำขอแก้ไขเวลา"
        >
          <Icon name="refresh" />
          รีเฟรช
        </Button>
      </div>

      <CompactStatRow
        items={[
          { key: 'total', label: 'คำขอทั้งหมด', value: requests.length, helper: 'ทั้งหมด' },
          { key: 'submitted', label: 'รอ CEO', value: totals.submitted, helper: 'Submitted' },
          { key: 'approved', label: 'อนุมัติแล้ว', value: totals.approved, helper: 'Approved' },
        ]}
      />

      <SafeForm className={FILTER_BAR_CLASS} onSubmit={(event) => event.preventDefault()}>
        <FilterField label="สถานะ">
          <select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)}>
            <option value="">ทุกสถานะ</option>
            <option value="SUBMITTED">รอ CEO</option>
            <option value="APPROVED">อนุมัติแล้ว</option>
            <option value="REJECTED">ปฏิเสธแล้ว</option>
            <option value="CANCELLED">ยกเลิกแล้ว</option>
          </select>
        </FilterField>
      </SafeForm>

      <Panel flush>
        <div className={`${CORRECTION_TABLE_GRID} table-head`}>
          <span>วันที่ / พนักงาน</span>
          <span>รายการที่แก้ไข</span>
          <span>เหตุผล</span>
          <span>สถานะ</span>
          <span />
        </div>
        {loading ? (
          <EmptyState icon="clock" title="กำลังโหลดคำขอแก้ไขเวลา" />
        ) : requests.length === 0 ? (
          // CEO has no "ขอแก้ไขเวลา" button to press (it's gated !isCeo, AttendancePage.jsx) --
          // the subtitle two lines above already branches on isCeo for the same reason; this
          // branches the same way instead of telling the CEO to press a button they don't have.
          <EmptyState
            icon="clock"
            title="ยังไม่มีคำขอแก้ไขเวลา"
            description={isCeo ? 'ยังไม่มีคำขอที่ต้องพิจารณา' : 'กดปุ่ม "ขอแก้ไขเวลา" ด้านบนเพื่อยื่นคำขอ'}
          />
        ) : requests.map((request) => {
          const status = statusInfo(request.status);
          const canReview = request.canReview;
          const canCancel = request.status === 'SUBMITTED' && Number(request.employeeId) === Number(user.employeeId);
          return (
            <div className={`${CORRECTION_TABLE_GRID} data-row`} key={request.id}>
              <span data-label="วันที่ / พนักงาน" className="mobile:order-1">
                <strong>{formatWorkDate(request.workDate)}</strong>
                <small>{request.employeeName || request.employeeCode || request.employeeId}</small>
              </span>
              <span data-label="รายการที่แก้ไข" className="mobile:order-4">
                <strong>{CORRECTION_TYPE_LABELS[request.correctionType] || request.correctionType}</strong>
                <small className="font-mono">
                  {request.requestedCheckIn ? `เข้า ${formatTime(request.requestedCheckIn)}` : ''}
                  {request.requestedCheckIn && request.requestedCheckOut ? ' · ' : ''}
                  {request.requestedCheckOut ? `ออก ${formatTime(request.requestedCheckOut)}` : ''}
                </small>
              </span>
              <span data-label="เหตุผล" className="mobile:order-5">
                <strong>{request.reason}</strong>
                <small>{request.reviewerNote || '-'}</small>
              </span>
              <span data-label="สถานะ" className="mobile:order-2">
                <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
                <small>{request.reviewedAt ? `${request.reviewedByName || '-'} · ${formatDateTime(request.reviewedAt)}` : '-'}</small>
              </span>
              <RowActions className="mobile:order-3 mobile:flex-wrap">
                {canReview ? (
                  <>
                    <Button
                      type="button"
                      variant="icon"
                      title="CEO อนุมัติ"
                      aria-label="CEO อนุมัติ"
                      disabled={saving}
                      style={{ color: 'var(--color-success)', borderColor: 'var(--color-success)' }}
                      onClick={() => approve(request.id)}
                    >
                      <Icon name="check" size={14} />
                    </Button>
                    <Button
                      type="button"
                      variant="icon"
                      title="ปฏิเสธ"
                      aria-label="ปฏิเสธ"
                      disabled={saving}
                      style={{ color: 'var(--color-danger)', borderColor: 'var(--color-danger)' }}
                      onClick={() => reject(request.id)}
                    >
                      <Icon name="close" size={14} />
                    </Button>
                  </>
                ) : null}
                {canCancel ? (
                  <Button type="button" variant="icon" title="ยกเลิก" aria-label="ยกเลิก" disabled={saving} onClick={() => cancel(request.id)}>
                    <Icon name="close" size={14} />
                  </Button>
                ) : null}
              </RowActions>
            </div>
          );
        })}
      </Panel>

      <ConfirmDialog
        open={confirmState?.kind === 'approve'}
        title="ยืนยันการอนุมัติคำขอแก้ไขเวลา"
        message='ยืนยันการอนุมัติ? ระบบจะบันทึกเวลาเข้า-ออกงานที่ขอแก้ไขเป็นเวลาที่ใช้จริงสำหรับวันนี้'
        confirmLabel="อนุมัติ"
        busy={saving}
        onConfirm={confirmApprove}
        onCancel={() => setConfirmState(null)}
      />
      <ConfirmDialog
        open={confirmState?.kind === 'reject'}
        title="ปฏิเสธคำขอแก้ไขเวลา"
        message='ยืนยันการปฏิเสธคำขอนี้? สถานะจะเปลี่ยนเป็น "ปฏิเสธแล้ว" และไม่สามารถอนุมัติย้อนหลังได้'
        confirmLabel="ปฏิเสธคำขอ"
        tone="danger"
        busy={saving}
        requireReason
        reasonLabel="เหตุผลการปฏิเสธ"
        onConfirm={confirmReject}
        onCancel={() => setConfirmState(null)}
      />
      <ConfirmDialog
        open={confirmState?.kind === 'cancel'}
        title="ยกเลิกคำขอแก้ไขเวลา"
        message='ยืนยันการยกเลิกคำขอนี้? สถานะจะเปลี่ยนเป็น "ยกเลิกแล้ว" ถาวร'
        confirmLabel="ยกเลิกคำขอ"
        tone="danger"
        busy={saving}
        onConfirm={confirmCancel}
        onCancel={() => setConfirmState(null)}
      />
    </>
  );
}
