import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { hasPermission } from '../../app/permissions.js';
import { CompactStatRow } from '../../components/common/CompactStatRow.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
// FilterField (#638) for the employee select this tab passes into LeaveFilterBar as a child --
// see that component's own comment on why a hand-rolled `<label>` here is the bug, not a shortcut.
import { FilterField, Panel } from '../../components/common/Layout.jsx';
import { StatePanel } from '../../components/common/StatePanel.jsx';
import { addDaysIso } from '../../utils/format.js';
import { LeaveFilterBar } from './LeaveFilterBar.jsx';
import { LeaveReportDownload } from './LeaveReportDownload.jsx';
import {
  formatDays, todayIso,
} from './leaveFormatting.js';
import { LeaveRequestsSection } from './leaveRequestTable.jsx';
import { TeamQuotaSummary } from './TeamQuotaSummary.jsx';
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

      {/* Owner ask: "there is no summary of like how much each employee have left" -- direct
          reports only (LeaveService#teamBalances), never the whole division. Its own query, so a
          slow/failed quota fetch never blocks the request table below it. */}
      <TeamQuotaSummary
        title={canViewAllLeave ? 'โควตาวันลาคงเหลือของพนักงานทั้งหมด' : 'โควตาวันลาคงเหลือของทีม'}
        emptyDescription={canViewAllLeave
          ? 'โควตาวันลาคงเหลือของพนักงานแต่ละคนจะแสดงที่นี่เมื่อมีข้อมูล'
          : 'เมื่อมีพนักงานที่รายงานตรงต่อคุณ โควตาวันลาคงเหลือของแต่ละคนจะแสดงที่นี่'}
      />

      <LeaveReportDownload scope="team" showToast={showToast} />

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
        <LeaveRequestsSection
          requestsQuery={requestsQuery}
          rows={requests}
          hasCustomFilters={hasCustomFilters}
          onClearFilters={clearFilters}
          expandedId={expandedId}
          onToggleExpand={(id) => setExpandedId((current) => (current === id ? null : id))}
          onCancel={requestCancel}
          user={user}
          showToast={showToast}
          // Approver-side surface: drop the "waiting on ผู้จัดการ (คุณนา)" note where it names the viewer.
          viewer={user}
          summaryPrefix={(request) => `${request.employeeName || request.employeeCode} · `}
          // hr/ceo see every active employee here, so "...ที่รายงานตรงต่อคุณ" would be false for them.
          emptyState={(
            <StatePanel
              state="empty"
              title={canViewAllLeave ? 'ยังไม่มีคำขอลาของพนักงาน' : 'ยังไม่มีคำขอลาของทีม'}
              description={canViewAllLeave
                ? 'คำขอลาของพนักงานทุกคนจะแสดงที่นี่เมื่อมีการยื่น'
                : 'คำขอลาของคุณและลูกทีมที่รายงานตรงต่อคุณจะแสดงที่นี่เมื่อมีการยื่น'}
            />
          )}
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
