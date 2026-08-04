import { useCallback, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { hasPermission } from '../../app/permissions.js';
import { Button } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { DataTable, expandedRowRegionId } from '../../components/common/DataTable.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { StatePanel } from '../../components/common/StatePanel.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { downloadBlob } from '../../utils/download.js';
import { leaveStatusLabel as statusInfo } from '../../utils/format.js';
import { formatDateRange, formatDays } from './leaveFormatting.js';
import {
  buildLeaveRequestColumns, LEAVE_REQUEST_TABLE_GRID, leaveRequestRowKey, renderLeaveRequestExpanded,
} from './leaveRequestTable.jsx';
import { canManagerCancelRequest, canReviewRequest } from './leaveSurfaceTabs.js';
import { confirmDialogMessageClass } from '../../components/common/ConfirmDialog.jsx';

// Leave-surface IA rebuild, Phase A1. This tab is the fix for the Context section's headline
// complaint: an approver used to see the submit form sitting directly above the queue they were
// reviewing (LeavePage.jsx's single table did both jobs at once). ReviewQueueTab now owns
// exclusively the requests THIS user may act on -- approve, reject, or cancel -- with the same
// per-row predicates leaveSurfaceTabs.js already uses to decide whether this tab is even visible.
//
// Phase A4 (#485 shipped GET /api/leave/attachments/{id}, session/scope-gated, but nothing in the
// frontend called it until now): the medical-certificate attachment download lives in the
// expand-row disclosure below, plus inline handling for the backend's optimistic-locking-free 409
// ("already decided by someone else" -- LeaveService#requireStatus) and a confirmation dialog on
// the requester's own self-cancel path (MyLeaveTab.jsx). A persistent (non-expand-row) detail
// panel remains out of scope.

function isPermissionError(error) {
  return error?.status === 403;
}

export function ReviewQueueTab({ user, showToast }) {
  const queryClient = useQueryClient();
  const [confirmState, setConfirmState] = useState(null); // { kind: 'approve'|'reject'|'cancel', id }
  const [expandedId, setExpandedId] = useState(null);
  // Phase A4: LeaveService has no @Version / optimistic lock -- two approvers (or one stale tab)
  // can both attempt to decide the same SUBMITTED request. requireStatus() 409s the loser with
  // "คำขอลานี้ได้รับการพิจารณาไปแล้ว" (or one of cancel's own already-decided messages). That must
  // not render as a transient toast that vanishes before anyone reads it -- `conflict` pins an
  // inline explanation to the specific row, force-expanded so it's impossible to miss, until the
  // user dismisses it (toggling that row's expand state -- see toggleExpand below) or successfully
  // acts on it again.
  const [conflict, setConflict] = useState(null); // { id, message } | null
  const [downloadingAttachmentId, setDownloadingAttachmentId] = useState(null);

  const canReviewAll = hasPermission(user.role, 'canReviewLeave');

  // Unfiltered by design -- a SUBMITTED request awaiting decision can carry any date, and a
  // reviewer should never lose sight of one because it falls outside some default range. Shares
  // its cache key with LeaveSurfacePage.jsx's own `reviewSignalQuery` (both read
  // `queryKeys.leaveRequests({})`), so mounting this tab costs no extra request once that one has
  // already landed.
  const requestsQuery = useQuery({
    queryKey: queryKeys.leaveRequests({}),
    queryFn: () => api.leave.list({}).then((response) => response.requests || []),
    refetchInterval: 60_000,
    refetchOnWindowFocus: true,
  });
  const allRequests = useMemo(() => requestsQuery.data ?? [], [requestsQuery.data]);
  const actionableRequests = useMemo(
    () => allRequests.filter((request) => canReviewRequest(request, user, canReviewAll)
      || canManagerCancelRequest(request, user, canReviewAll)),
    [allRequests, user, canReviewAll],
  );
  const loading = requestsQuery.isPending;
  const denied = requestsQuery.isError && isPermissionError(requestsQuery.error);
  const hasError = requestsQuery.isError && !denied;
  const refreshing = requestsQuery.isFetching && !requestsQuery.isPending;

  function invalidateLeave() {
    return queryClient.invalidateQueries({ queryKey: ['leave'] });
  }

  // Issue #422 B5: approve/reject/cancel each change a figure PayrollPage reads
  // (unpaidLeaveDays, via suggested-inputs) with no way for that screen to find out short of a
  // manual reload. Invalidating the ['payroll'] prefix alongside invalidateLeave() lets an open
  // PayrollPage tab pick the change up on its own next poll/focus refetch.
  function invalidatePayrollUpstream() {
    return queryClient.invalidateQueries({ queryKey: ['payroll'] });
  }

  const clearConflict = useCallback((id) => {
    setConflict((current) => (current?.id === id ? null : current));
  }, []);

  // Phase A4: the shared 409 branch every approve/reject/cancel onError funnels through.
  // requireStatus() (LeaveService, read before writing this) 409s with a message that already
  // differs by action ("คำขอลานี้ได้รับการพิจารณาไปแล้ว" for approve/reject, one of two more
  // specific messages for cancel) -- surfaced verbatim rather than a fixed string, same as
  // HolidaysTab.jsx's own 409 handling. Every OTHER status still gets the plain toast the caller
  // already had; only 409 gets the inline, force-expanded, non-vanishing treatment.
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

  // Phase A4: at most one of approve/reject/cancel can be in flight at once (they are only ever
  // fired from the single-open ConfirmDialog below), so ONE pending-row id -- not a per-mutation
  // set -- is enough to know which row's action buttons must disable to stop a double-click firing
  // two competing decisions on the same request from this client.
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
          {cancellable ? (
            <Button type="button" variant="icon" title="ยกเลิก" aria-label="ยกเลิก" disabled={rowSaving} onClick={() => requestCancel(request.id)}>
              <Icon name="close" size={14} />
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
    return (
      <>
        <div className="flex min-w-0 items-start justify-between gap-3">
          <strong className="min-w-0 truncate text-sm font-extrabold text-text">
            {formatDateRange(request.startDate, request.endDate)}
          </strong>
          <span className="flex items-center gap-1.5">
            <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
            <button
              type="button"
              className="icon-button"
              aria-expanded={expanded}
              aria-controls={expandedRowRegionId(request.id)}
              title={expanded ? 'ซ่อนรายละเอียด' : 'ดูรายละเอียด'}
              aria-label={expanded ? 'ซ่อนรายละเอียด' : 'ดูรายละเอียด'}
              onClick={() => toggleExpand(request.id)}
            >
              <Icon name={expanded ? 'chevronUp' : 'chevronDown'} size={14} />
            </button>
          </span>
        </div>
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
            {cancellable ? (
              <Button type="button" variant="secondary" className="min-h-11 flex-1" disabled={rowSaving} onClick={() => requestCancel(request.id)}>
                <Icon name="close" size={14} />
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

  return (
    <>
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
              <p className={confirmDialogMessageClass} style={{ margin: 0 }}>
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
              {/* Next-step copy quoted from api.leave.approve (src/api/mockApi.js): it only sets
                  status -> APPROVED plus the reviewer stamp -- it does not recompute
                  quotaRemainingAfter, which was already fixed at create() time. */}
              <p style={{ margin: 0, fontSize: 12, color: 'var(--color-icon-muted)' }}>{'สถานะจะเปลี่ยนเป็น "อนุมัติแล้ว"'}</p>
            </div>
          );
        })()}
        confirmLabel="อนุมัติ"
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
