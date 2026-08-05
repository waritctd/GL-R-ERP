import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { hasPermission } from '../../app/permissions.js';
import { Button } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { DataTable, expandedRowRegionId } from '../../components/common/DataTable.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { StatePanel } from '../../components/common/StatePanel.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { downloadBlob } from '../../utils/download.js';
import { leaveStatusLabel as statusInfo } from '../../utils/format.js';
import {
  formatDateRange, formatDays, monthStartIso, todayIso,
} from './leaveFormatting.js';
import {
  buildLeaveRequestColumns, LEAVE_REQUEST_TABLE_GRID, leaveRequestRowKey,
  renderLeaveRequestExpanded,
} from './leaveRequestTable.jsx';

// Bugfix (2026-08): this tab is the correctly-labelled, correctly-scoped home for what
// used to leak into MyLeaveTab.jsx's "ของฉัน" tab. LeaveService#list -- for any actor
// outside VIEW_ALL_ROLES (hr/ceo) -- treats an omitted `employeeId` as "self OR
// reports_to_employee_id = actor" (LeaveRepository#findRequests' `filter.managerEmployeeId()`
// branch), so this tab's UNFILTERED query already returns exactly "me and my direct
// reports" -- team-wide leave, as advertised. MyLeaveTab.jsx now always scopes its own
// query to the actor's own employeeId; nothing here changed on the backend, only where
// the (always-correct) response gets labelled and shown. See leaveSurfaceTabs.js's
// `team` tab entry (hasTeamMembers) for the visibility gate.
//
// Same filter bar / calendar / table shape MyLeaveTab.jsx used to carry -- ported
// (not rewritten) out of that file, reusing the exact same shared primitives
// (buildLeaveRequestColumns/LEAVE_REQUEST_TABLE_GRID/leaveRequestRowKey/
// renderLeaveRequestExpanded from leaveRequestTable.jsx) ReviewQueueTab.jsx already
// uses, so all three tabs render a leave-request row identically.

const FILTER_BAR_CLASS = 'flex flex-wrap gap-[10px] items-end bg-surface border border-border rounded-md p-[14px]';

function isPermissionError(error) {
  return error?.status === 403;
}

// Ported verbatim from MyLeaveTab.jsx -- see that file's own comment on the three named
// deep-link entry points this retry link is one of.
function retryLeaveRequestHref(request) {
  const params = new URLSearchParams({
    type: request.leaveTypeCode,
    start: request.startDate,
    end: request.endDate,
  });
  return `/leave/new?${params.toString()}`;
}

/**
 * The team's request table -- ported from MyLeaveTab.jsx's `OwnRequestsSection`, with
 * team-appropriate copy (there is no "your first leave request" teaching moment here;
 * this is a browsing surface, not the actor's own submission funnel). The cancel/retry
 * action gate (`isOwn`) is UNCHANGED: a row here only gets action buttons when it is the
 * viewing actor's own request, exactly as it silently already behaved for other people's
 * rows on the pre-fix "ของฉัน" tab -- moving the code did not change who may act on what.
 */
function TeamRequestsSection({
  requestsQuery, rows, hasCustomFilters, onClearFilters, expandedId, onToggleExpand, onCancel, user, showToast,
}) {
  const navigate = useNavigate();
  const [downloadingAttachmentId, setDownloadingAttachmentId] = useState(null);
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
  const loading = requestsQuery.isPending;
  const denied = requestsQuery.isError && isPermissionError(requestsQuery.error);
  const hasError = requestsQuery.isError && !denied;
  // Review fix (2026-08): hr/ceo see every active employee here (api.leave.employees()
  // returns the whole company for VIEW_ALL_ROLES -- ROLE_PERMISSIONS.canViewAllLeave mirrors
  // it), so the "...ที่รายงานตรงต่อคุณ" ("...who report directly to you") clause is false for
  // them. A real division manager keeps the original copy unchanged.
  const canViewAllLeave = hasPermission(user?.role, 'canViewAllLeave');

  const columns = useMemo(() => buildLeaveRequestColumns({
    expandedId,
    onToggleExpand,
    renderActions: (request) => {
      const isOwn = Number(request.employeeId) === Number(user.employeeId);
      const canCancel = isOwn && request.status === 'SUBMITTED';
      const canRetry = isOwn && ['AUTO_REJECTED', 'REJECTED'].includes(request.status);
      if (!canCancel && !canRetry) return null;
      return (
        <span className="flex items-center gap-1">
          {canRetry ? (
            <Button
              type="button"
              variant="icon"
              title="ยื่นคำขอใหม่"
              aria-label="ยื่นคำขอใหม่"
              onClick={() => navigate(retryLeaveRequestHref(request))}
            >
              <Icon name="refresh" size={14} />
            </Button>
          ) : null}
          {canCancel ? (
            <Button type="button" variant="icon" title="ยกเลิก" aria-label="ยกเลิก" onClick={() => onCancel(request.id)}>
              <Icon name="close" size={14} />
            </Button>
          ) : null}
        </span>
      );
    },
  }), [expandedId, onToggleExpand, onCancel, user.employeeId, navigate]);

  function mobileCard(request) {
    const status = statusInfo(request.status);
    const isOwn = Number(request.employeeId) === Number(user.employeeId);
    const canCancel = isOwn && request.status === 'SUBMITTED';
    const canRetry = isOwn && ['AUTO_REJECTED', 'REJECTED'].includes(request.status);
    const expanded = expandedId === request.id;
    return (
      <>
        <div className="flex min-w-0 items-start justify-between gap-3">
          <strong className="min-w-0 truncate text-sm font-extrabold text-text">
            {formatDateRange(request.startDate, request.endDate)}
          </strong>
          <span className="flex items-center gap-1.5">
            <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
            <Button
              type="button"
              variant="icon"
              aria-expanded={expanded}
              aria-controls={expandedRowRegionId(request.id)}
              title={expanded ? 'ซ่อนรายละเอียด' : 'ดูรายละเอียด'}
              aria-label={expanded ? 'ซ่อนรายละเอียด' : 'ดูรายละเอียด'}
              onClick={() => onToggleExpand(request.id)}
            >
              <Icon name={expanded ? 'chevronUp' : 'chevronDown'} size={14} />
            </Button>
          </span>
        </div>
        <span className="min-w-0 truncate text-xs text-text-muted">
          {request.employeeName || request.employeeCode} · {request.leaveTypeNameTh || request.leaveTypeCode} · {formatDays(request.totalDays)}
        </span>
        <span className="min-w-0 truncate text-xs text-text-muted">{request.reason}</span>
        {canRetry ? (
          <Button
            type="button"
            variant="secondary"
            className="mt-1 min-h-11"
            onClick={() => navigate(retryLeaveRequestHref(request))}
          >
            <Icon name="refresh" size={14} />
            ยื่นคำขอใหม่
          </Button>
        ) : null}
        {canCancel ? (
          <Button type="button" variant="secondary" className="mt-1 min-h-11" onClick={() => onCancel(request.id)}>
            <Icon name="close" size={14} />
            ยกเลิกคำขอ
          </Button>
        ) : null}
      </>
    );
  }

  if (!loading && denied) {
    return (
      <StatePanel
        state="denied"
        description={requestsQuery.error?.message || 'ไม่มีสิทธิ์เข้าถึงรายการนี้'}
      />
    );
  }

  if (!loading && !hasError && rows.length === 0) {
    return hasCustomFilters ? (
      <StatePanel
        state="filtered"
        action={<Button type="button" variant="secondary" onClick={onClearFilters}>ล้างตัวกรอง</Button>}
      />
    ) : (
      <StatePanel
        state="empty"
        title={canViewAllLeave ? 'ยังไม่มีคำขอลาของพนักงาน' : 'ยังไม่มีคำขอลาของทีม'}
        description={canViewAllLeave
          ? 'คำขอลาของพนักงานทุกคนจะแสดงที่นี่เมื่อมีการยื่น'
          : 'คำขอลาของคุณและลูกทีมที่รายงานตรงต่อคุณจะแสดงที่นี่เมื่อมีการยื่น'}
      />
    );
  }

  return (
    <DataTable
      columns={columns}
      rows={rows}
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
  );
}

export function TeamLeaveTab({ user, showToast }) {
  const queryClient = useQueryClient();
  const initialFilters = {
    from: monthStartIso(),
    to: todayIso(),
    employeeId: '',
    status: '',
  };
  const [filters, setFilters] = useState(initialFilters);
  const [appliedFilters, setAppliedFilters] = useState(initialFilters);
  const [confirmState, setConfirmState] = useState(null);
  const [expandedId, setExpandedId] = useState(null);

  // Review fix (2026-08): see TeamRequestsSection's own comment on why this is role-aware --
  // same predicate, computed again here (this component has its own `user` in scope) for the
  // Panel title below, which lives outside TeamRequestsSection.
  const canViewAllLeave = hasPermission(user?.role, 'canViewAllLeave');

  // Shares its cache key with LeaveSurfacePage.jsx's own `teamSignalQuery` (both read
  // `queryKeys.leaveEmployees()`), so mounting this tab costs no extra request once that
  // one has already landed -- same convention ReviewQueueTab.jsx/reviewSignalQuery use
  // for `queryKeys.leaveRequests({})`.
  const employeesQuery = useQuery({
    queryKey: queryKeys.leaveEmployees(),
    queryFn: () => api.leave.employees().then((response) => response.employees || []),
  });
  const employeeOptions = useMemo(() => employeesQuery.data ?? [], [employeesQuery.data]);
  // Same "does this actor have a team at all" signal leaveSurfaceTabs.js's `team` tab
  // isVisible reuses (hasTeamMembers) -- kept in sync deliberately: if this filter has
  // nothing to offer beyond "ทุกคน" == "just me", the tab itself is already hidden.
  const hasMultipleEmployeeOptions = employeeOptions.length > 1;

  // Unfiltered by default, this already returns exactly "me and my direct reports" for
  // any non-hr/ceo actor (LeaveService#list's own default scoping -- see this file's
  // header comment) -- the correct team-wide data the pre-fix "ของฉัน" tab used to show
  // under the wrong label. `appliedFilters.employeeId`, when set, narrows to one team
  // member (still constrained server-side to self-or-report).
  const requestsQuery = useQuery({
    queryKey: queryKeys.leaveRequests(appliedFilters),
    queryFn: () => api.leave.list({
      from: appliedFilters.from,
      to: appliedFilters.to,
      status: appliedFilters.status,
      ...(appliedFilters.employeeId ? { employeeId: appliedFilters.employeeId } : {}),
    }).then((response) => response.requests || []),
    refetchInterval: 60_000,
    refetchOnWindowFocus: true,
  });
  const requests = useMemo(() => requestsQuery.data ?? [], [requestsQuery.data]);
  const refreshing = requestsQuery.isFetching && !requestsQuery.isPending;
  const hasCustomFilters = JSON.stringify(appliedFilters) !== JSON.stringify(initialFilters);

  const activeCalendarItems = useMemo(
    () => requests
      .filter((request) => ['SUBMITTED', 'APPROVED'].includes(request.status))
      .slice()
      .sort((first, second) => first.startDate.localeCompare(second.startDate))
      .slice(0, 8),
    [requests],
  );

  function invalidateLeave() {
    return queryClient.invalidateQueries({ queryKey: ['leave'] });
  }

  function invalidatePayrollUpstream() {
    return queryClient.invalidateQueries({ queryKey: ['payroll'] });
  }

  function updateFilter(field, value) {
    setFilters((current) => ({ ...current, [field]: value }));
  }

  function clearFilters() {
    setFilters(initialFilters);
    setAppliedFilters(initialFilters);
  }

  function submitFilters(event) {
    event.preventDefault();
    setAppliedFilters(filters);
  }

  const cancelMutation = useMutation({
    mutationFn: ({ id, reviewerNote }) => api.leave.cancel(id, { reviewerNote: reviewerNote?.trim() || null }).then((response) => response.request),
    onSuccess: () => {
      showToast('success', 'ยกเลิกคำขอลาแล้ว');
      setConfirmState(null);
      invalidateLeave();
      invalidatePayrollUpstream();
    },
    onError: (error) => showToast('error', error.message || 'ยกเลิกวันลาไม่สำเร็จ'),
  });

  const saving = cancelMutation.isPending;

  function requestCancel(id) {
    setConfirmState({ kind: 'cancel', id });
  }

  function doCancel(id, reviewerNote) {
    cancelMutation.mutate({ id, reviewerNote });
  }

  return (
    <>
      <form className={FILTER_BAR_CLASS} onSubmit={submitFilters}>
        <label>
          จากวันที่
          <input type="date" value={filters.from} onChange={(event) => updateFilter('from', event.target.value)} />
        </label>
        <label>
          ถึงวันที่
          <input type="date" value={filters.to} onChange={(event) => updateFilter('to', event.target.value)} />
        </label>
        <label>
          สถานะ
          <select value={filters.status} onChange={(event) => updateFilter('status', event.target.value)}>
            <option value="">ทุกสถานะ</option>
            <option value="SUBMITTED">รออนุมัติ</option>
            <option value="APPROVED">อนุมัติแล้ว</option>
            <option value="REJECTED">ปฏิเสธแล้ว</option>
            <option value="CANCELLED">ยกเลิกแล้ว</option>
            <option value="AUTO_REJECTED">โควตาไม่พอ</option>
          </select>
        </label>
        {hasMultipleEmployeeOptions ? (
          <label>
            พนักงาน
            <select value={filters.employeeId} onChange={(event) => updateFilter('employeeId', event.target.value)}>
              <option value="">ทุกคน</option>
              {employeeOptions.map((employee) => (
                <option key={employee.employeeId} value={employee.employeeId}>{employee.employeeName} · {employee.employeeCode}</option>
              ))}
            </select>
          </label>
        ) : null}
        <Button type="submit" disabled={requestsQuery.isPending}>
          <Icon name="search" />
          ค้นหา
        </Button>
        {refreshing ? (
          <span className="inline-flex items-center gap-1 text-xs text-text-muted" aria-live="polite">
            <Icon name="refresh" size={12} />
            กำลังอัปเดต…
          </span>
        ) : null}
      </form>

      <Panel title="ปฏิทินวันลา">
        <div className="leave-calendar-list">
          {activeCalendarItems.length === 0 ? (
            <EmptyState icon="calendar" title="ยังไม่มีรายการวันลาในช่วงนี้" />
          ) : activeCalendarItems.map((request) => {
            const status = statusInfo(request.status);
            return (
              <div className="leave-calendar-item" key={request.id}>
                <span>
                  <strong>{formatDateRange(request.startDate, request.endDate)}</strong>
                  <small>{request.employeeName || request.employeeCode} · {request.leaveTypeNameTh}</small>
                </span>
                <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
              </div>
            );
          })}
        </div>
      </Panel>

      <Panel title={canViewAllLeave ? 'คำขอลาพนักงานทั้งหมด' : 'คำขอลาของทีม'} flush>
        <TeamRequestsSection
          requestsQuery={requestsQuery}
          rows={requests}
          hasCustomFilters={hasCustomFilters}
          onClearFilters={clearFilters}
          expandedId={expandedId}
          onToggleExpand={(id) => setExpandedId((current) => (current === id ? null : id))}
          onCancel={requestCancel}
          user={user}
          showToast={showToast}
        />
      </Panel>

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
        onConfirm={(reason) => doCancel(confirmState.id, reason)}
        onCancel={() => setConfirmState(null)}
      />
    </>
  );
}
