import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { CollapsibleSection } from '../../components/common/CollapsibleSection.jsx';
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

  // Only `submitted` survives the density pass: it is the header badge's number, i.e. the one
  // figure that says whether this collapsed section has work in it. `approved`/total went with the
  // stat strip — see the note at the top of the return.
  const totals = useMemo(() => ({
    submitted: requests.filter((request) => request.status === 'SUBMITTED').length,
  }), [requests]);

  return (
    // Not <PageStack>: this panel is now embedded inside AttendancePage.jsx's OWN PageStack
    // (fix/attendance-correction-on-attendance-page) rather than owning a page of its own, and a
    // bare fragment lets its sections become direct children of that outer grid -- so they pick
    // up its 18px gap for free, with no extra wrapper and no risk of a nested grid's `gap`
    // stacking with its parent's. Same pattern MyLeaveTab.jsx/TeamLeaveTab.jsx already use for the
    // identical shape (a tab's content embedded in LeaveSurfacePage.jsx's own PageStack).
    // Fragment: the CollapsibleSection holds the body, and the three ConfirmDialogs stay OUTSIDE
    // it. A collapsed section unmounts its children, so a dialog left inside would vanish mid-
    // confirmation if anything collapsed the section under it.
    //
    // Collapsed by default, for every role (owner, 2026-08-10). This is a correction workflow: an
    // employee files one when a scan is wrong, which is rare, and the CEO reviews them in batches.
    // Expanded it put a stat strip, a filter bar and a full table between the attendance table
    // above and the bottom of the page, on a screen whose actual job is "show me the day".
    //
    // The header keeps the pending count so collapsing never hides WORK. That is the difference
    // between progressive disclosure and burying something: a CEO with three requests waiting sees
    // "3" without expanding, and the query runs whether or not the body is mounted (it lives above
    // this return), so the count is live either way.
    //
    // CollapsibleSection also replaces the hand-rolled header this panel used to carry — same
    // title, subtitle and refresh button, but as one titled surface instead of a bare heading
    // followed by three sibling blocks. Its body unmounts when collapsed, which is safe here:
    // every piece of state it renders (statusFilter, the query) lives above.
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
        </span>
      )}
    >
      {/*
        The three-tile CompactStatRow that used to sit here is gone (density pass, 2026-08-10).
        It repeated itself and its own header: the section header already shows "รอพิจารณา N", so
        "รอ CEO 2" said the same number a second line later, and "คำขอทั้งหมด" / "อนุมัติแล้ว" are
        derivable by looking at the five rows directly beneath them. At 768px the strip also
        wrapped 2-then-1, leaving a half-empty tile block above a table that is the actual content.
        For a secondary, collapsed-by-default section, one number in the header is the right budget.

        The status filter keeps its own <form> (Enter-to-submit) but no longer sits in a bordered
        card of its own — a single select framed as a panel was a card doing no grouping work.
      */}
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

      {/*
        `overflow-x-auto`: CORRECTION_TABLE_GRID holds this table at `nav-drawer:min-w-[860px]`
        across the whole 721-1040px band, and `<Panel flush>` is `overflow-hidden` (it must be — a
        flush body runs edge to edge, so the card radius is what clips its corners). With no scroll
        region between them the excess is not scrollable, it is gone: measured at 768px, an 860px
        grid inside a 702px card, so ~158px — the สถานะ column and the row action — was unreachable
        by any gesture.

        This one only became visible once the section had rows to render at all; with the seed
        empty it showed an EmptyState and there was nothing to clip. Fourth call site of the same
        `min-w-* inside Panel flush` pairing (attendance, welfare and the OT history are the
        others), which is why the durable fix belongs in the shared component rather than here.
      */}
      <Panel flush className="overflow-x-auto">
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
                {/* Nothing, not "-". A SUBMITTED row has no reviewer note yet by definition, and
                    a dash is a value: it reads as "there is a note and it is empty". Every pending
                    row printed one here and another under สถานะ, so the two busiest columns each
                    carried a meaningless character on exactly the rows a reviewer scans most. */}
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
