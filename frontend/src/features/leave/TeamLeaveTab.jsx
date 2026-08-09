import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { hasPermission } from '../../app/permissions.js';
import { Button } from '../../components/common/Button.jsx';
import { CompactStatRow } from '../../components/common/CompactStatRow.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { DataTable, expandedRowRegionId } from '../../components/common/DataTable.jsx';
import { Icon } from '../../components/common/Icon.jsx';
// FilterField (#638) for the employee select this tab passes into LeaveFilterBar as a child --
// see that component's own comment on why a hand-rolled `<label>` here is the bug, not a shortcut.
import { FilterField, Panel } from '../../components/common/Layout.jsx';
import { StatePanel } from '../../components/common/StatePanel.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { downloadBlob } from '../../utils/download.js';
import { addDaysIso, leaveStatusLabel as statusInfo } from '../../utils/format.js';
import { LeaveFilterBar } from './LeaveFilterBar.jsx';
import {
  formatDateRange, formatDays, todayIso,
} from './leaveFormatting.js';
import {
  buildLeaveRequestColumns, LEAVE_REQUEST_TABLE_GRID, leaveRequestRowKey,
  PendingApproverNote, renderLeaveRequestExpanded,
} from './leaveRequestTable.jsx';
import { UpcomingLeaveList } from './UpcomingLeaveList.jsx';

// Same forward horizon MyLeaveTab.jsx uses for its own "วันลาที่กำลังจะถึง" panel -- the team and
// personal views must not disagree about how far "coming up" reaches.
const UPCOMING_LEAVE_WINDOW_DAYS = 90;

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
// Same filter bar / upcoming-leave / table shape MyLeaveTab.jsx carries -- and as of the
// 2026-08-10 IA restructure that is literal shared code, not a port: LeaveFilterBar.jsx and
// UpcomingLeaveList.jsx replaced what were two verbatim copies in this file and MyLeaveTab.jsx
// (the filter bar down to its own duplicated `items-end` rationale comment, the list panel down to
// the byte). Both tabs already shared their row rendering via leaveRequestTable.jsx; this closes
// the remaining duplication so a change lands on both surfaces at once.

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
    // Approver-side surface: drop the "waiting on ผู้จัดการ (คุณนา)" note where it names the
    // viewer. On a division manager's own team history that is most pending rows; for hr/ceo,
    // whose rows route to other people's managers, the note still renders and still means
    // something. See PendingApproverNote's own comment.
    viewer: user,
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
              <Icon name="ban" size={14} />
            </Button>
          ) : null}
        </span>
      );
    },
    // `user`, not `user.employeeId`: the whole object is now read (as `viewer`, which also
    // inspects `role`), so narrowing the dep to one field would keep a stale viewer after a
    // role change.
  }), [expandedId, onToggleExpand, onCancel, user, navigate]);

  function mobileCard(request) {
    const status = statusInfo(request.status);
    const isOwn = Number(request.employeeId) === Number(user.employeeId);
    const canCancel = isOwn && request.status === 'SUBMITTED';
    const canRetry = isOwn && ['AUTO_REJECTED', 'REJECTED'].includes(request.status);
    const expanded = expandedId === request.id;
    return (
      <>
        <div className="flex min-w-0 items-start justify-between gap-3">
          {/* Wraps, never truncates — see ReviewQueueTab.jsx's own comment: `truncate` cut the
              Buddhist-era year off the end date at 390px, which reads as a complete date. */}
          <strong className="min-w-0 text-sm font-extrabold text-text">
            {formatDateRange(request.startDate, request.endDate)}
          </strong>
          <span className="flex shrink-0 items-center gap-1.5">
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
        <PendingApproverNote request={request} viewer={user} />
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
            <Icon name="ban" size={14} />
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
      // Drops the inner card's chrome, since this table is wrapped in a titled `<Panel flush>`
      // below. No scroll override -- #650 fixed that in Panel's default. See MyLeaveTab.jsx's
      // own comment on this prop.
      panelClassName="rounded-none border-0"
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
  // Empty dates by default -- same change and same reasoning as MyLeaveTab.jsx's own
  // `initialFilters` (see its comment): `to: todayIso()` made future team leave unreachable, and
  // `from: monthStartIso()` emptied the tab on the 1st of each month. hrApi.js's `withQuery` drops
  // '' so no date parameter is sent, and LeaveService#list applies its +/-12-month default.
  const initialFilters = {
    from: '',
    to: '',
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

  // Own forward window, independent of the filter above -- see UpcomingLeaveList.jsx's own doc
  // comment for why this is a second query rather than a `.filter()` over `requests` (defect D1).
  // Deliberately NOT scoped to `appliedFilters.employeeId`: this panel answers "who on my team is
  // away soon", which is a question about the whole team even while the table below is narrowed to
  // one person.
  const upcomingFrom = todayIso();
  const upcomingTo = addDaysIso(upcomingFrom, UPCOMING_LEAVE_WINDOW_DAYS);
  const upcomingQuery = useQuery({
    queryKey: queryKeys.leaveRequests({ from: upcomingFrom, to: upcomingTo }),
    queryFn: () => api.leave.list({ from: upcomingFrom, to: upcomingTo })
      .then((response) => response.requests || []),
  });
  const upcomingRequests = useMemo(() => upcomingQuery.data ?? [], [upcomingQuery.data]);

  // Stat-row parity with the other two tabs (owner ruling, 2026-08-10). The four numbers are
  // chosen for what THIS tab is for -- a manager scanning their team, not an employee tracking
  // their own quota, so the fourth slot counts PEOPLE away rather than a personal quota balance.
  const totals = useMemo(() => {
    const submitted = requests.filter((request) => request.status === 'SUBMITTED').length;
    const approved = requests.filter((request) => request.status === 'APPROVED');
    const approvedDays = approved.reduce((sum, request) => sum + Number(request.totalDays || 0), 0);
    // Distinct employees with any non-cancelled/non-rejected leave in the visible range.
    const peopleAway = new Set(
      requests
        .filter((request) => ['SUBMITTED', 'APPROVED'].includes(request.status))
        .map((request) => request.employeeId),
    ).size;
    return { submitted, approved: approved.length, approvedDays, peopleAway };
  }, [requests]);

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
      <CompactStatRow
        items={[
          { key: 'total', label: 'คำขอทั้งหมด', value: requests.length, helper: 'ในช่วงที่เลือก' },
          { key: 'submitted', label: 'รออนุมัติ', value: totals.submitted, helper: 'Submitted' },
          { key: 'approved', label: 'อนุมัติแล้ว', value: totals.approved, helper: formatDays(totals.approvedDays) },
          { key: 'people', label: 'พนักงานที่ลา', value: totals.peopleAway, helper: 'อนุมัติแล้ว + รออนุมัติ' },
        ]}
      />

      <UpcomingLeaveList
        title={canViewAllLeave ? 'วันลาที่กำลังจะถึงของพนักงานทั้งหมด' : 'วันลาที่กำลังจะถึงของทีม'}
        requests={upcomingRequests}
        loading={upcomingQuery.isPending}
        showEmployee
        emptyTitle="ยังไม่มีวันลาที่กำลังจะถึง"
        emptyDescription="วันลาที่อนุมัติแล้วหรือรออนุมัติในช่วง 90 วันข้างหน้าจะแสดงที่นี่"
      />

      {/* Directly above the history table -- the one section it governs. */}
      <LeaveFilterBar
        values={filters}
        onChange={updateFilter}
        onSubmit={submitFilters}
        submitDisabled={requestsQuery.isPending}
        refreshing={refreshing}
      >
        {hasMultipleEmployeeOptions ? (
          <FilterField label="พนักงาน">
            <select value={filters.employeeId} onChange={(event) => updateFilter('employeeId', event.target.value)}>
              <option value="">ทุกคน</option>
              {employeeOptions.map((employee) => (
                <option key={employee.employeeId} value={employee.employeeId}>{employee.employeeName} · {employee.employeeCode}</option>
              ))}
            </select>
          </FilterField>
        ) : null}
      </LeaveFilterBar>

      <Panel title={canViewAllLeave ? 'ประวัติการลาพนักงานทั้งหมด' : 'ประวัติการลาของทีม'} flush>
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
