import { useCallback, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { hasPermission } from '../../app/permissions.js';
import { Button } from '../../components/common/Button.jsx';
import { CompactStatRow } from '../../components/common/CompactStatRow.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { DataTable, expandedRowRegionId } from '../../components/common/DataTable.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { StatePanel } from '../../components/common/StatePanel.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { downloadBlob } from '../../utils/download.js';
import { addDaysIso, leaveStatusLabel as statusInfo } from '../../utils/format.js';
import { formatDateRange, formatDays, todayIso } from './leaveFormatting.js';
import { LeaveRuleWarningList } from './LeaveRulePanel.jsx';
import {
  buildLeaveRequestColumns, LEAVE_REQUEST_TABLE_GRID, leaveRequestRowKey,
  PendingApproverNote, renderLeaveRequestExpanded,
} from './leaveRequestTable.jsx';
import { canManagerCancelRequest, canReviewRequest } from './leaveSurfaceTabs.js';

// This tab owns exclusively the requests THIS user may act on (approve, reject, or cancel), with
// the same per-row predicates leaveSurfaceTabs.js uses to decide whether the tab is visible. The
// medical-certificate attachment download lives in the expand-row disclosure, with inline handling
// of the backend's 409 ("already decided by someone else", LeaveService#requireStatus) and a
// confirmation dialog on the requester's own self-cancel path (MyLeaveTab.jsx).

function isPermissionError(error) {
  return error?.status === 403;
}

export function ReviewQueueTab({ user, showToast }) {
  const queryClient = useQueryClient();
  const [confirmState, setConfirmState] = useState(null); // { kind: 'approve'|'reject'|'cancel', id }
  const [expandedId, setExpandedId] = useState(null);
  // LeaveService has no @Version / optimistic lock: two approvers (or one stale tab) can both
  // attempt to decide the same SUBMITTED request, and requireStatus() 409s the loser. That must
  // not be a transient toast that vanishes unread: `conflict` pins an inline explanation to the
  // row, force-expanded, until the user dismisses it (toggleExpand) or acts on it again.
  const [conflict, setConflict] = useState(null); // { id, message } | null
  const [downloadingAttachmentId, setDownloadingAttachmentId] = useState(null);

  const canReviewAll = hasPermission(user.role, 'canReviewLeave');

  // No CLIENT-side date filter, by design: a SUBMITTED request awaiting decision can carry any date.
  // The server default is a WINDOW of +/-12 months (LeaveService.DEFAULT_WINDOW_MONTHS), not
  // "unfiltered": `list({})` sends no dates and findRequests matches on overlap, so a narrower
  // default dropped still-SUBMITTED requests out of this queue. Widen the constant if a pending
  // request older than a year is ever a real case.
  //
  // Shares its cache key with LeaveSurfacePage.jsx's `reviewSignalQuery`
  // (`queryKeys.leaveRequests({})`), so mounting this tab costs no extra request.
  const requestsQuery = useQuery({
    queryKey: queryKeys.leaveRequests({}),
    queryFn: () => api.leave.list({}).then((response) => response.requests || []),
    refetchInterval: 60_000,
    refetchOnWindowFocus: true,
  });
  const allRequests = useMemo(() => requestsQuery.data ?? [], [requestsQuery.data]);
  // Decisions first, then the already-approved rows this user may only void. `list()` returns one
  // stream ordered by start_date DESC (LeaveRepository#findRequests); interleaved, a queue headed
  // "รอคุณพิจารณา 4" would open on an approved row and, since this tab paginates, could push a real
  // decision onto page 2. A STABLE partition (`filter` twice, no comparator) keeps the API's own
  // date order within each group.
  const actionableRequests = useMemo(() => {
    const reviewable = allRequests.filter((request) => canReviewRequest(request, user, canReviewAll));
    const cancellableOnly = allRequests.filter((request) => !canReviewRequest(request, user, canReviewAll)
      && canManagerCancelRequest(request, user, canReviewAll));
    return [...reviewable, ...cancellableOnly];
  }, [allRequests, user, canReviewAll]);
  const loading = requestsQuery.isPending;
  const denied = requestsQuery.isError && isPermissionError(requestsQuery.error);
  const hasError = requestsQuery.isError && !denied;
  const refreshing = requestsQuery.isFetching && !requestsQuery.isPending;

  // Stat-row parity with the other two tabs, chosen for a QUEUE: how much is waiting on me and how
  // urgent. Counts are over `actionableRequests` (the rows shown), not `allRequests`.
  // `awaitingDecision` is the reviewable subset (canReviewRequest == SUBMITTED); the rest are
  // already-APPROVED rows this user may only cancel, which are not "waiting on" anyone.
  const totals = useMemo(() => {
    const reviewable = actionableRequests.filter((request) => canReviewRequest(request, user, canReviewAll));
    const pendingDays = reviewable.reduce((sum, request) => sum + Number(request.totalDays || 0), 0);
    const people = new Set(reviewable.map((request) => request.employeeId)).size;
    // "Starting within 7 days" is the urgency signal a queue needs -- a request whose leave begins on Monday
    // matters more than one three months out, and nothing else on this tab surfaces that.
    const soonCutoff = addDaysIso(todayIso(), 7);
    const startingSoon = reviewable.filter((request) => request.startDate <= soonCutoff).length;
    return {
      awaitingDecision: reviewable.length, pendingDays, people, startingSoon,
    };
  }, [actionableRequests, user, canReviewAll]);

  function invalidateLeave() {
    return queryClient.invalidateQueries({ queryKey: ['leave'] });
  }

  // approve/reject/cancel each change a figure PayrollPage reads (unpaidLeaveDays, via
  // suggested-inputs); invalidating the ['payroll'] prefix lets an open PayrollPage pick it up on
  // its next poll/focus refetch.
  function invalidatePayrollUpstream() {
    return queryClient.invalidateQueries({ queryKey: ['payroll'] });
  }

  const clearConflict = useCallback((id) => {
    setConflict((current) => (current?.id === id ? null : current));
  }, []);

  // The shared 409 branch every approve/reject/cancel onError funnels through. requireStatus()
  // 409s with a message that already differs by action, surfaced verbatim (like HolidaysTab.jsx).
  // Every OTHER status keeps the plain toast; only 409 gets the inline, force-expanded treatment.
  function handleReviewError(error, id, fallbackMessage) {
    if (error?.status === 409) {
      setConfirmState(null);
      setConflict({ id, message: error.message || 'คำขอลานี้ได้รับการพิจารณาไปแล้ว' });
      setExpandedId(id);
      invalidateLeave();
      return;
    }
    showToast('error', error.message || fallbackMessage);
  }

  const approveMutation = useMutation({
    mutationFn: (id) => api.leave.approve(id, {}).then((response) => response.request),
    onSuccess: (data, id) => {
      showToast('success', 'อนุมัติวันลาแล้ว');
      setConfirmState(null);
      clearConflict(id);
      invalidateLeave();
      invalidatePayrollUpstream();
    },
    onError: (error, id) => handleReviewError(error, id, 'อนุมัติวันลาไม่สำเร็จ'),
  });

  const rejectMutation = useMutation({
    mutationFn: ({ id, reviewerNote }) => api.leave.reject(id, { reviewerNote }).then((response) => response.request),
    onSuccess: (data, variables) => {
      showToast('success', 'ปฏิเสธคำขอลาแล้ว');
      setConfirmState(null);
      clearConflict(variables.id);
      invalidateLeave();
      invalidatePayrollUpstream();
    },
    onError: (error, variables) => handleReviewError(error, variables.id, 'ปฏิเสธวันลาไม่สำเร็จ'),
  });

  const cancelMutation = useMutation({
    mutationFn: ({ id, reviewerNote }) => api.leave.cancel(id, { reviewerNote: reviewerNote?.trim() || null }).then((response) => response.request),
    onSuccess: (data, variables) => {
      showToast('success', 'ยกเลิกคำขอลาแล้ว');
      setConfirmState(null);
      clearConflict(variables.id);
      invalidateLeave();
      invalidatePayrollUpstream();
    },
    onError: (error, variables) => handleReviewError(error, variables.id, 'ยกเลิกวันลาไม่สำเร็จ'),
  });

  // At most one of approve/reject/cancel can be in flight (they are fired from the single-open
  // ConfirmDialog below), so ONE pending-row id is enough to disable that row's action buttons
  // and stop a double-click firing two competing decisions.
  const pendingRowId = approveMutation.isPending
    ? approveMutation.variables
    : (rejectMutation.isPending ? rejectMutation.variables?.id
      : (cancelMutation.isPending ? cancelMutation.variables?.id : null));

  async function downloadAttachment(request) {
    setDownloadingAttachmentId(request.id);
    try {
      const blob = await api.leave.downloadAttachment(request.attachmentId);
      downloadBlob(blob, `leave-attachment-${request.id}`, 'pdf');
    } catch (error) {
      showToast('error', error.message || 'ดาวน์โหลดเอกสารไม่สำเร็จ');
    } finally {
      setDownloadingAttachmentId(null);
    }
  }

  const toggleExpand = useCallback((id) => {
    setExpandedId((current) => (current === id ? null : id));
    clearConflict(id);
  }, [clearConflict]);

  const saving = approveMutation.isPending || rejectMutation.isPending || cancelMutation.isPending;

  function approve(id) {
    setConfirmState({ kind: 'approve', id });
  }

  function confirmApprove() {
    approveMutation.mutate(confirmState.id);
  }

  function reject(id) {
    setConfirmState({ kind: 'reject', id });
  }

  function confirmReject(reviewerNote) {
    if (!reviewerNote?.trim()) return;
    rejectMutation.mutate({ id: confirmState.id, reviewerNote: reviewerNote.trim() });
  }

  function requestCancel(id) {
    setConfirmState({ kind: 'cancel', id });
  }

  function confirmCancel(reviewerNote) {
    cancelMutation.mutate({ id: confirmState.id, reviewerNote });
  }

  const columns = useMemo(() => buildLeaveRequestColumns({
    expandedId,
    onToggleExpand: toggleExpand,
    // Suppresses the "waiting on ผู้จัดการ (คุณนา)" note on rows waiting on THIS user -- which, on a division
    // manager's own queue, is every pending row. See PendingApproverNote's own comment.
    viewer: user,
    renderActions: (request) => {
      const reviewable = canReviewRequest(request, user, canReviewAll);
      const cancellable = canManagerCancelRequest(request, user, canReviewAll);
      const rowSaving = pendingRowId === request.id;
      return (
        <>
          {reviewable ? (
            <>
              <Button
                type="button"
                variant="icon"
                title="อนุมัติ"
                aria-label="อนุมัติ"
                disabled={rowSaving}
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
                disabled={rowSaving}
                style={{ color: 'var(--color-danger)', borderColor: 'var(--color-danger)' }}
                onClick={() => reject(request.id)}
              >
                <Icon name="close" size={14} />
              </Button>
            </>
          ) : null}
          {/* `ban`, not `close`: this button sits beside "ปฏิเสธ", and they are different ACTIONS
              (ปฏิเสธ decides a pending request, ยกเลิก voids an approved one), so they must not
              share the ✕ glyph. */}
          {cancellable ? (
            <Button type="button" variant="icon" title="ยกเลิก" aria-label="ยกเลิก" disabled={rowSaving} onClick={() => requestCancel(request.id)}>
              <Icon name="ban" size={14} />
            </Button>
          ) : null}
        </>
      );
    },
  }), [expandedId, pendingRowId, user, canReviewAll, toggleExpand]);

  function mobileCard(request) {
    const status = statusInfo(request.status);
    const reviewable = canReviewRequest(request, user, canReviewAll);
    const cancellable = canManagerCancelRequest(request, user, canReviewAll);
    const expanded = expandedId === request.id;
    const rowSaving = pendingRowId === request.id;
    // Same collapsed-row unpaid-days badge as leaveRequestTable.jsx's shared desktop status column;
    // reads `unpaidDays` (the request's total unpaid figure, of which a §5 WARN gate's
    // unpaidByRuleDays is always a SUBSET, per LeaveRequestDto's Javadoc) rather than
    // unpaidByRuleDays alone: either source of unpaid days deserves the same visibility.
    const hasUnpaidDays = Number(request.unpaidDays || 0) > 0;
    return (
      <>
        <div className="flex min-w-0 items-start justify-between gap-3">
          {/* Wraps, never truncates: the date range is the card's headline and `truncate` at 390px
              cut the Buddhist-era YEAR off the end date ("7 ก.ย. 2569 - 11 ก.ย. 25…"), which looks
              like a complete date. The string is short, so wrapping costs at most one line. */}
          <strong className="min-w-0 text-sm font-extrabold text-text">
            {formatDateRange(request.startDate, request.endDate)}
          </strong>
          <span className="flex shrink-0 items-start gap-1.5">
            {/* Stacked vertically, never inline: two badges on one line in a narrow column force
                truncation. See leaveRequestTable.jsx's desktop status column for the same treatment. */}
            <span className="flex flex-col items-start gap-1">
              <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
              {hasUnpaidDays ? (
                <StatusBadge tone="warning">ไม่รับค่าจ้าง {formatDays(request.unpaidDays)}</StatusBadge>
              ) : null}
            </span>
            <Button
              variant="icon"
              aria-expanded={expanded}
              aria-controls={expandedRowRegionId(request.id)}
              title={expanded ? 'ซ่อนรายละเอียด' : 'ดูรายละเอียด'}
              aria-label={expanded ? 'ซ่อนรายละเอียด' : 'ดูรายละเอียด'}
              onClick={() => toggleExpand(request.id)}
            >
              <Icon name={expanded ? 'chevronUp' : 'chevronDown'} size={14} />
            </Button>
          </span>
        </div>
        <PendingApproverNote request={request} viewer={user} />
        <span className="min-w-0 truncate text-xs text-text-muted">
          {request.employeeName || request.employeeCode} · {request.leaveTypeNameTh || request.leaveTypeCode} · {formatDays(request.totalDays)}
        </span>
        <span className="min-w-0 truncate text-xs text-text-muted">{request.reason}</span>
        {reviewable || cancellable ? (
          <div className="mt-1 flex gap-2">
            {reviewable ? (
              <>
                <Button
                  type="button"
                  variant="secondary"
                  className="min-h-11 flex-1"
                  style={{ color: 'var(--color-success)', borderColor: 'var(--color-success)' }}
                  disabled={rowSaving}
                  onClick={() => approve(request.id)}
                >
                  <Icon name="check" size={14} />
                  อนุมัติ
                </Button>
                <Button
                  type="button"
                  variant="secondary"
                  className="min-h-11 flex-1"
                  style={{ color: 'var(--color-danger)', borderColor: 'var(--color-danger)' }}
                  disabled={rowSaving}
                  onClick={() => reject(request.id)}
                >
                  <Icon name="close" size={14} />
                  ปฏิเสธ
                </Button>
              </>
            ) : null}
            {/* `ban`, matching the desktop icon column. */}
            {cancellable ? (
              <Button type="button" variant="secondary" className="min-h-11 flex-1" disabled={rowSaving} onClick={() => requestCancel(request.id)}>
                <Icon name="ban" size={14} />
                ยกเลิก
              </Button>
            ) : null}
          </div>
        ) : null}
      </>
    );
  }

  const confirmRequest = allRequests.find((item) => item.id === confirmState?.id)
    ?? actionableRequests.find((item) => item.id === confirmState?.id);

  // §5 WARN_UNPAID_* gates: the CONFIRM button is the actual commit action (the row's own icon
  // button only OPENS this dialog), so the pay consequence belongs on the button the approver
  // clicks. `unpaidByRuleDays` is a SUBSET of the request's own unpaidDays (LeaveRequestDto's
  // Javadoc); reading it, not totalDays, stays accurate alongside an ordinary quota-exceedance
  // unpaid amount.
  const approveConfirmLabel = Number(confirmRequest?.unpaidByRuleDays || 0) > 0
    ? `อนุมัติ (ไม่จ่ายค่าจ้าง ${formatDays(confirmRequest.unpaidByRuleDays)})`
    : 'อนุมัติ';

  return (
    <>
      <CompactStatRow
        items={[
          { key: 'awaiting', label: 'รอคุณพิจารณา', value: totals.awaitingDecision, helper: 'อนุมัติ / ปฏิเสธได้' },
          { key: 'soon', label: 'เริ่มใน 7 วัน', value: totals.startingSoon, helper: 'ควรพิจารณาก่อน' },
          // `wrapValue`: a sum over every pending request, so it carries the same multi-unit duration MyLeaveTab's own
          // "โควตาคงเหลือ" tile does. See CompactStatRow.
          { key: 'days', label: 'วันลารวม', value: formatDays(totals.pendingDays), helper: 'ที่รอพิจารณา', wrapValue: true },
          { key: 'people', label: 'พนักงาน', value: totals.people, helper: 'ที่ยื่นคำขอ' },
        ]}
      />

      {refreshing ? (
        <span className="inline-flex items-center gap-1 text-xs text-text-muted" aria-live="polite">
          <Icon name="refresh" size={12} />
          กำลังอัปเดต…
        </span>
      ) : null}

      {!loading && denied ? (
        <StatePanel state="denied" description={requestsQuery.error?.message || 'ไม่มีสิทธิ์เข้าถึงรายการนี้'} />
      ) : !loading && !hasError && actionableRequests.length === 0 ? (
        <StatePanel
          state="empty"
          title="ไม่มีคำขอที่ต้องพิจารณาในตอนนี้"
          description="คำขอลาของลูกทีมที่รอการอนุมัติ หรือที่คุณยกเลิกแทนได้ จะแสดงที่นี่"
        />
      ) : (
        <DataTable
          columns={columns}
          rows={actionableRequests}
          getRowKey={leaveRequestRowKey}
          gridClassName={LEAVE_REQUEST_TABLE_GRID}
          // No `panelClassName="overflow-x-auto"` here, deliberately: Panel's `flush` default is
          // already `overflow-x-auto overflow-y-hidden`, and per-call-site patches were deleted in
          // favour of that default.
          loading={loading}
          error={hasError}
          onRetry={() => requestsQuery.refetch()}
          mobileCard={mobileCard}
          renderExpanded={(request) => {
            if (expandedId !== request.id) return null;
            return (
              <>
                {conflict?.id === request.id ? (
                  <StatePanel
                    compact
                    state="error"
                    title="คำขอนี้ถูกพิจารณาไปแล้ว"
                    description={`สถานะปัจจุบัน: ${statusInfo(request.status).label}${conflict.message ? ` — ${conflict.message}` : ''}`}
                    className="mb-3"
                  />
                ) : null}
                {/* §5 WARN_UNPAID_* gates: the approver must see this BEFORE approving (see
                    LeaveRequestDto's ruleWarnings Javadoc). Placed first so expanding a row to review
                    it surfaces the pay consequence immediately, before the approve/reject icon
                    buttons (always visible, not gated on expand). Shows ALL warnings, not just the
                    first -- see LeaveRuleWarningList's own comment. */}
                {request.ruleWarnings?.length > 0 ? (
                  <LeaveRuleWarningList warnings={request.ruleWarnings} className="mb-3" />
                ) : null}
                {renderLeaveRequestExpanded(request)}
                {request.attachmentId ? (
                  <div className="mt-3 flex flex-wrap items-center justify-between gap-3 rounded-md border border-border bg-surface px-3 py-2">
                    <span className="inline-flex min-w-0 items-center gap-2 text-sm text-text">
                      <Icon name="fileText" size={16} className="text-icon-muted" />
                      <span className="min-w-0 truncate">{request.attachmentFileName || 'เอกสารแนบ'}</span>
                    </span>
                    <Button
                      type="button"
                      variant="secondary"
                      disabled={downloadingAttachmentId === request.id}
                      onClick={() => downloadAttachment(request)}
                    >
                      <Icon name="fileText" size={14} />
                      {downloadingAttachmentId === request.id ? 'กำลังดาวน์โหลด…' : 'ดาวน์โหลด'}
                    </Button>
                  </div>
                ) : null}
              </>
            );
          }}
          showPagination
        />
      )}

      <ConfirmDialog
        open={confirmState?.kind === 'approve'}
        title="ยืนยันการอนุมัติวันลา"
        message={(() => {
          if (!confirmRequest) return 'ยืนยันการอนุมัติคำขอลานี้?';
          return (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              <p className="confirm-dialog-message text-text-secondary leading-normal" style={{ margin: 0 }}>
                ตรวจสอบคำขอลาของ <strong>{confirmRequest.employeeName || confirmRequest.employeeCode || confirmRequest.employeeId}</strong> ก่อนอนุมัติ
              </p>
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13, borderTop: '1px solid var(--color-border)', paddingTop: 8 }}>
                <span style={{ color: 'var(--color-icon-muted)' }}>ประเภท / ช่วงวันที่</span>
                <span>{confirmRequest.leaveTypeNameTh || confirmRequest.leaveTypeCode} · {formatDateRange(confirmRequest.startDate, confirmRequest.endDate)}</span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 14, fontWeight: 700 }}>
                <span>จำนวนวันลา</span>
                <span className="font-mono">{formatDays(confirmRequest.totalDays)}</span>
              </div>
              {/* §5 WARN_UNPAID_* gates: same figure the confirm button's own label reads, repeated
                  in the line-item breakdown so the number on the button is never the first place
                  it appears. */}
              {Number(confirmRequest.unpaidByRuleDays || 0) > 0 ? (
                <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13, color: 'var(--color-warning-dark)' }}>
                  <span>ไม่จ่ายค่าจ้าง (เงื่อนไข §5)</span>
                  <span className="font-mono">{formatDays(confirmRequest.unpaidByRuleDays)}</span>
                </div>
              ) : null}
              {/* Next-step copy quoted from api.leave.approve (src/api/mockApi.js): it only sets
                  status -> APPROVED plus the reviewer stamp. */}
              <p style={{ margin: 0, fontSize: 12, color: 'var(--color-icon-muted)' }}>{'สถานะจะเปลี่ยนเป็น "อนุมัติแล้ว"'}</p>
            </div>
          );
        })()}
        confirmLabel={approveConfirmLabel}
        busy={saving}
        onConfirm={confirmApprove}
        onCancel={() => setConfirmState(null)}
      />
      <ConfirmDialog
        open={confirmState?.kind === 'reject'}
        title="ปฏิเสธคำขอลา"
        message='ยืนยันการปฏิเสธคำขอลานี้? สถานะจะเปลี่ยนเป็น "ปฏิเสธแล้ว" และไม่สามารถอนุมัติย้อนหลังได้'
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
        title="ยกเลิกคำขอลา"
        message='ยืนยันการยกเลิกคำขอลานี้? สถานะจะเปลี่ยนเป็น "ยกเลิกแล้ว" ถาวร'
        confirmLabel="ยกเลิกคำขอ"
        tone="danger"
        busy={saving}
        requireReason
        optionalReason
        reasonLabel="หมายเหตุการยกเลิก (ถ้ามี)"
        onConfirm={confirmCancel}
        onCancel={() => setConfirmState(null)}
      />
    </>
  );
}
