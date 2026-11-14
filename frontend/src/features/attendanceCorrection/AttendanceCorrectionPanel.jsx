import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { CollapsibleSection } from '../../components/common/CollapsibleSection.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
// FilterField is main's (#ef3c744e) shrinkable/uniform filter control -- kept, the status filter below still
// uses it. formGridSpan2 and PageStack went with the submit form and the PageStack root respectively, both of
// which moved out of this file.
import { FilterField, Panel, RowActions } from '../../components/common/Layout.jsx';
import { SafeForm } from '../../components/common/SafeForm.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { attendanceCorrectionStatusLabel as statusInfo } from '../../utils/format.js';

const CORRECTION_TABLE_GRID = 'grid-cols-[minmax(0,1fr)_minmax(0,1.15fr)_minmax(0,1.4fr)_minmax(0,0.9fr)_minmax(0,0.75fr)] nav-drawer:min-w-[860px] reflow-cards';
const CORRECTION_TYPE_LABELS = {
  CHECK_IN: 'เวลาสแกนเข้างาน',
  CHECK_OUT: 'เวลาสแกนออกงาน',
  BOTH: 'เวลาสแกนเข้าและออกงาน',
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

/** The list/review half of "ขอแก้ไขเวลาเข้า-ออกงาน" -- the submit half moved into
 * AttendanceCorrectionRequestModal.jsx, opened from a button on AttendancePage.jsx
 * (fix/attendance-correction-on-attendance-page). */
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
      // Deliberate addition (fix/attendance-correction-on-attendance-page): approving doesn't just flip this
      // request's own status -- AttendanceCorrectionService#approve (backend) inserts a real hr.attendance_punch row
      // and flips the day's is_manual_override via AttendanceDailyService#applyManualCorrection.
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

  // Only `submitted` survives the density pass: it is the header badge's number, i.e. the one figure that says
  // whether this collapsed section has work in it. `approved`/total went with the stat strip — see the note at
  // the top of the return.
  const totals = useMemo(() => ({
    submitted: requests.filter((request) => request.status === 'SUBMITTED').length,
  }), [requests]);

  return (
    // Not <PageStack>: this panel is now embedded inside AttendancePage.jsx's OWN PageStack rather than owning a
    // page of its own, and a bare fragment lets its sections become direct children of that outer grid. The header
    // keeps the pending count so collapsing never hides WORK.
    <>
    <CollapsibleSection
      title="คำขอแก้ไขเวลาเข้า-ออกงาน"
      subtitle={isCeo
        ? 'พิจารณาคำขอแก้ไขเวลาเข้า-ออกงานของพนักงาน'
        : 'กดปุ่ม "ขอแก้ไขเวลา" ด้านบนเพื่อยื่นคำขอแก้ไขเวลาสแกนนิ้ว และดูประวัติคำขอของคุณได้ด้านล่าง'}
      defaultOpen={false}
      headerRight={(
        <span className="flex items-center gap-2">
          {totals.submitted > 0 ? (
            <StatusBadge tone="warning">{`รอพิจารณา ${totals.submitted}`}</StatusBadge>
          ) : null}
          <Button
            type="button"
            variant="secondary"
            onClick={() => requestsQuery.refetch()}
            disabled={loading}
            // Distinct from AttendancePage's own "รีเฟรช" (refetches the day table, not this list) -- both buttons now
            // live on the same page (fix/attendance-correction-on-attendance-page), and an identical accessible name is a
            // real problem for anyone navigating by button list.
            aria-label="รีเฟรชคำขอแก้ไขเวลา"
          >
            <Icon name="refresh" />
            รีเฟรช
          </Button>
        </span>
      )}
    >
      {/* The three-tile CompactStatRow that used to sit here is gone (density pass, 2026-08-10). At 768px the strip
       * also wrapped 2-then-1, leaving a half-empty tile block above a table that is the actual content. */}
      <SafeForm className="flex flex-wrap items-end gap-[10px]" onSubmit={(event) => event.preventDefault()}>
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

      {/* `overflow-x-auto`: CORRECTION_TABLE_GRID holds this table at `nav-drawer:min-w-[860px]` across the whole
       * 721-1040px band, and `<Panel flush>` is `overflow-hidden` (it must be — a flush body runs edge to edge, so
       * the card radius is what clips its corners). */}
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
          // CEO has no "ขอแก้ไขเวลา" button to press (it's gated !isCeo, AttendancePage.jsx) -- the subtitle two lines
          // above already branches on isCeo for the same reason; this branches the same way instead of telling the CEO
          // to press a button they don't have.
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
                {/* Omit absent reviewer notes rather than showing a dash on pending rows. */}
                {request.reviewerNote ? <small>{request.reviewerNote}</small> : null}
              </span>
              <span data-label="สถานะ" className="mobile:order-2">
                <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
                {request.reviewedAt
                  ? <small>{`${request.reviewedByName || '-'} · ${formatDateTime(request.reviewedAt)}`}</small>
                  : null}
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
      </CollapsibleSection>


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
