import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { CollapsibleSection } from '../../components/common/CollapsibleSection.jsx';
import { CompactStatRow } from '../../components/common/CompactStatRow.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { DataTable, expandedRowRegionId } from '../../components/common/DataTable.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { FieldList } from '../../components/common/FieldList.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { QuotaBar } from '../../components/common/QuotaBar.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { StatePanel } from '../../components/common/StatePanel.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { downloadBlob } from '../../utils/download.js';
import { leaveStatusLabel as statusInfo } from '../../utils/format.js';
import {
  formatDateRange, formatDays, monthStartIso, todayIso, yearFrom,
} from './leaveFormatting.js';
import {
  buildLeaveRequestColumns, LEAVE_REQUEST_TABLE_GRID, leaveRequestRowKey,
  renderLeaveRequestExpanded,
} from './leaveRequestTable.jsx';

// FilterBar (Layout.jsx) renders a <div>; this form needs native submit semantics
// (Enter-to-submit on the search button), so its exact utility string is reproduced
// here rather than wrapping a <form> inside a non-form primitive.
const FILTER_BAR_CLASS = 'flex flex-wrap gap-[10px] items-center bg-surface border border-border rounded-md p-[14px]';

// Leave-surface IA rebuild Phase A1 (later narrowed by owner feedback, "one primary card, not
// every quota card at once"): the everyday-vs-rare balance split. SICK/PERSONAL/VACATION are what
// every employee actually files day to day; MATERNITY/MILITARY/ORDINATION are real but uncommon,
// and MILITARY's annualQuotaDays (366) is a deliberate sentinel, not a policy number (see
// mockApi.js's db.leaveTypes comment) -- showing "เหลือ 366 วัน" for it is worse than not showing
// a quota figure at all. Named constant, not an inline `code === 'SICK' || ...` check at each call
// site, so the split has exactly one definition to update if the everyday set ever changes.
// Exported: LeaveRequestPage.jsx (Phase A2) reuses this same split for its step-1 primary/secondary
// type disclosure, so both surfaces agree on exactly which three types are "everyday".
export const EVERYDAY_LEAVE_TYPE_CODES = new Set(['SICK', 'PERSONAL', 'VACATION']);

// A rare type's meaningful figures are the paid-days cap and the eligibility gate, never a quota
// or a remaining-days count -- both are derived from annualQuotaDays, and MILITARY's is the 366
// sentinel above. minServiceMonths/oncePerEmployment mirror the hr.leave_type columns (see
// mockApi.js's db.leaveTypes comment); maxConsecutiveDays/firstYearMaxDays are not surfaced here
// because the seeded rare types never set them, and this file is not the place to invent copy for
// fields no rare type currently uses.
function rareLeaveConditionText(leaveType) {
  if (!leaveType) return null;
  const parts = [];
  if (Number(leaveType.minServiceMonths) > 0) {
    parts.push(`อายุงานอย่างน้อย ${leaveType.minServiceMonths} เดือน`);
  }
  if (leaveType.oncePerEmployment) {
    parts.push('ใช้ได้ครั้งเดียวตลอดการทำงาน');
  }
  return parts.length > 0 ? parts.join(' · ') : null;
}

function everydayBalanceSummary(balance) {
  return `ใช้แล้ว ${formatDays(balance.approvedDays)} · รออนุมัติ ${formatDays(balance.pendingDays)} · สิทธิ์ ${formatDays(balance.annualQuotaDays)}`;
}

// Deliberately never reads balance.annualQuotaDays/remainingDays -- see rareLeaveConditionText's
// comment above on why (MILITARY's 366 sentinel). approved/pending stay real, request-driven
// counts regardless of the sentinel, so they stay meaningful for every rare type; paidDaysCap and
// the eligibility gate come from the leave-type record, not the balance.
function rareBalanceSummary(balance, leaveType) {
  const parts = [`ใช้แล้ว ${formatDays(balance.approvedDays)}`, `รออนุมัติ ${formatDays(balance.pendingDays)}`];
  if (leaveType?.paidDaysCap != null) {
    parts.push(`จ่ายค่าจ้างสูงสุด ${formatDays(leaveType.paidDaysCap)}`);
  }
  const condition = rareLeaveConditionText(leaveType);
  if (condition) parts.push(condition);
  return parts.join(' · ');
}

function isPermissionError(error) {
  return error?.status === 403;
}

// Phase A2 (#485): a rejected/auto-rejected request's own retry link -- one of the composer's
// three named deep-link entry points (LeaveRequestPage.jsx's own doc comment lists all three:
// this row action, the empty state below, and the auto-rejection notification). Prefills the same
// type/dates the rejected request used so the employee starts from what they already chose, not a
// blank form -- they are here to fix ONE thing the system flagged, not re-decide everything.
function retryLeaveRequestHref(request) {
  const params = new URLSearchParams({
    type: request.leaveTypeCode,
    start: request.startDate,
    end: request.endDate,
  });
  return `/leave/new?${params.toString()}`;
}

/**
 * The employee's own request table, split into DataTable (loading skeleton / real rows /
 * pagination / error+retry) plus a StatePanel for the three zero-row cases the pre-A1 table
 * collapsed into one generic EmptyState: `filtered` (non-default filters applied, offers a
 * clear-filters action), `empty` (teaching copy -- first-time, no data at all yet), and `denied`
 * (the request errored with a 403).
 */
function OwnRequestsSection({
  requestsQuery, rows, hasCustomFilters, onClearFilters, expandedId, onToggleExpand, onCancel, user, showToast,
}) {
  const navigate = useNavigate();
  // Phase A4: the medical-certificate download for the requester's OWN expanded row -- the
  // reviewer-side equivalent lives in ReviewQueueTab.jsx. Local to this section (not lifted to
  // MyLeaveTab) since only this table's expanded row ever needs it.
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
            <button
              type="button"
              className="icon-button"
              aria-expanded={expanded}
              aria-controls={expandedRowRegionId(request.id)}
              title={expanded ? 'ซ่อนรายละเอียด' : 'ดูรายละเอียด'}
              aria-label={expanded ? 'ซ่อนรายละเอียด' : 'ดูรายละเอียด'}
              onClick={() => onToggleExpand(request.id)}
            >
              <Icon name={expanded ? 'chevronUp' : 'chevronDown'} size={14} />
            </button>
          </span>
        </div>
        <span className="min-w-0 truncate text-xs text-text-muted">
          {request.leaveTypeNameTh || request.leaveTypeCode} · {formatDays(request.totalDays)}
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
        title="ยังไม่มีคำขอลา"
        description="ลาคือการหยุดงานที่ได้รับอนุมัติ กดปุ่ม “ยื่นคำขอลา” ด้านบนเพื่อเริ่ม เลือกประเภทและช่วงวันที่ ระบบจะตรวจโควตาและอนุมัติอัตโนมัติถ้าเข้าเงื่อนไข"
        action={<Button type="button" onClick={() => navigate('/leave/new')}><Icon name="plus" />ยื่นคำขอลา</Button>}
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

// QuotaBar's `formatValue` defaults to `formatMoney` (every other current caller is money); leave
// balances are day counts, so this mirrors the plain-number formatter LeaveRequestPage.jsx's own
// step-3 QuotaBar already uses -- unit-less, because the caption below spells out "(วัน)" itself.
const daysNumberFormat = new Intl.NumberFormat('th-TH', { maximumFractionDigits: 2 });
function formatDaysNumber(value) {
  return daysNumberFormat.format(Number(value) || 0);
}

/**
 * The one balance card the owner asked for ("really show just the primary one default because
 * if we show everything the page will just be over populated with cards") -- driven by whichever
 * leave type is currently picked in the "ดูโควตา" select above it (Panel's `actions`), so it
 * tracks a plain browsing control rather than a request-submission form (Phase A2, #485: the
 * submission form itself moved to the /leave/new composer -- this select's only job is choosing
 * which type's balance to preview). `loading` covers both "balances haven't loaded yet" and "no
 * acting employee resolved yet" (balancesQuery is disabled until then) so this never flashes a
 * bare `0` before real data lands; a resolved-but-missing balance (the selected type has no row)
 * gets its own EmptyState rather than silently rendering zeros.
 *
 * Phase A5 (#489/#493 convergence): the everyday headline used to be a bespoke `<strong>` number
 * with no visual proportion and no `role="progressbar"`. QuotaBar (promoted from the tax-allowance
 * form, already adopted by the /leave/new composer's step 3) replaces it. `used` is exactly what
 * the API already subtracts to produce `balance.remainingDays` (mockApi.js:
 * `remainingDays = annualQuotaDays - approvedDays - pendingDays`) -- the bar's empty portion IS
 * remainingDays, just shown as proportion instead of a lone digit, so this is a display change,
 * not an arithmetic one. `cap` is `isEveryday ? balance.annualQuotaDays : null` -- reusing the
 * same everyday/rare split this file already computes, rather than hand-rolling a fourth
 * MILITARY-sentinel check (the balance card, the composer, and the rules tab each already needed
 * one). QuotaBar itself renders nothing when `cap == null`, which is exactly the suppression rare
 * types need: MILITARY's annualQuotaDays (366) is a sentinel ("no annual ceiling; the paid cap is
 * the real rule", V120), and MATERNITY/ORDINATION are per-occasion entitlements, not a running
 * balance -- none of the three have a cap a progress bar could meaningfully fill toward. The
 * paid-cap headline + condition text that already existed for rare types renders unconditionally
 * alongside it, unchanged.
 */
function PrimaryLeaveBalanceCard({ loading, balance, leaveType, isEveryday }) {
  if (loading) {
    return (
      <div className="grid gap-2" aria-busy="true" aria-label="กำลังโหลดข้อมูลโควตา">
        <Skeleton width="35%" height={14} />
        <Skeleton width="20%" height={12} />
        <Skeleton width="45%" height={36} />
        <Skeleton width="70%" height={14} />
      </div>
    );
  }
  if (!balance) {
    return <EmptyState icon="calendar" title="ยังไม่มีข้อมูลโควตาสำหรับประเภทนี้" />;
  }

  const name = balance.leaveTypeNameTh || leaveType?.nameTh || balance.leaveTypeCode;
  // Same arithmetic mockApi.js used to derive balance.remainingDays -- see the doc comment above.
  const used = Number(balance.approvedDays || 0) + Number(balance.pendingDays || 0);
  const summary = isEveryday ? everydayBalanceSummary(balance) : rareBalanceSummary(balance, leaveType);

  return (
    <div className="grid gap-2" data-testid="primary-balance-card">
      <span className="block min-w-0 truncate text-base font-bold text-text-secondary">{name}</span>
      <QuotaBar
        label={name}
        caption={`โควตา${name} (วัน)`}
        used={used}
        cap={isEveryday ? balance.annualQuotaDays : null}
        formatValue={formatDaysNumber}
        overMessage="ใช้วันลาเกินโควตาประจำปีแล้ว ส่วนที่เกินอาจไม่ได้รับอนุมัติอัตโนมัติ"
      />
      {/* Rare types (MATERNITY/MILITARY/ORDINATION): QuotaBar renders nothing above (cap=null),
          so the paid-cap headline is the meaningful figure instead. See rareBalanceSummary's
          comment on why this never reads annualQuotaDays/remainingDays. */}
      {!isEveryday ? (
        <>
          <span className="mt-1 block text-xs font-bold uppercase tracking-wide text-text-muted">สิทธิ์จ่ายค่าจ้างสูงสุด</span>
          <strong className="block text-4xl font-extrabold leading-tight tabular-nums text-text">
            {leaveType?.paidDaysCap != null ? formatDays(leaveType.paidDaysCap) : '-'}
          </strong>
        </>
      ) : null}
      <small className="mt-1 block text-sm text-text-muted">{summary}</small>
    </div>
  );
}

export function MyLeaveTab({ user, currentEmployee, showToast }) {
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

  // Phase A2 (#485): the request-submission form (employee/type picker, dates, contact block,
  // attachment) moved to the /leave/new composer -- this tab is read-only (browse balances, browse
  // your own request history, cancel a SUBMITTED one). "Own" here is literal: the composer owns
  // its own on-behalf-of employee picker for whoever may file for a report, so this tab no longer
  // needs one either.
  const ownEmployeeId = currentEmployee?.id || user.employeeId || '';
  const [previewTypeCode, setPreviewTypeCode] = useState('');

  // --- Reads (TanStack Query) ---
  const employeesQuery = useQuery({
    queryKey: queryKeys.leaveEmployees(),
    queryFn: () => api.leave.employees().then((response) => response.employees || []),
  });
  const employeeOptions = useMemo(() => employeesQuery.data ?? [], [employeesQuery.data]);

  const leaveTypesQuery = useQuery({
    queryKey: queryKeys.leaveTypes(),
    queryFn: () => api.leave.types().then((response) => response.leaveTypes || []),
  });
  const leaveTypes = useMemo(() => leaveTypesQuery.data ?? [], [leaveTypesQuery.data]);

  // Issue #422 B4: a modest poll + window-focus refetch on this list specifically -- leave
  // approvals are genuinely multi-user (a manager/HR approval made in another session, or in the
  // "รอพิจารณา" tab, must show up here without a manual reload). FIX (Phase A1): this used to
  // double as the table's `loading` flag (`isLoading || isFetching`), which meant every one of
  // these background refetches blanked the table into an EmptyState -- `isPending` below drives
  // the skeleton (first load only); `isFetching` alone now only powers a quiet, non-destructive
  // header indicator that never removes the rows already on screen.
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

  const balancesYear = yearFrom();
  const balancesQuery = useQuery({
    queryKey: queryKeys.leaveBalances(ownEmployeeId, balancesYear),
    queryFn: () => api.leave.balances({
      ...(ownEmployeeId ? { employeeId: ownEmployeeId } : {}),
      year: balancesYear,
    }).then((response) => response.balances || []),
    enabled: !!ownEmployeeId,
  });
  const balances = useMemo(() => balancesQuery.data ?? [], [balancesQuery.data]);
  const everydayBalances = useMemo(
    () => balances.filter((balance) => EVERYDAY_LEAVE_TYPE_CODES.has(balance.leaveTypeCode)),
    [balances],
  );
  const selectedBalance = useMemo(
    () => balances.find((balance) => balance.leaveTypeCode === previewTypeCode),
    [balances, previewTypeCode],
  );
  const selectedLeaveType = useMemo(
    () => leaveTypes.find((type) => type.code === previewTypeCode),
    [leaveTypes, previewTypeCode],
  );
  // Covers both "no acting employee resolved yet" (balancesQuery is `enabled: !!ownEmployeeId`, so
  // it never even starts a fetch until then) and "the fetch is in flight" -- either way the
  // primary card must show its skeleton, not a stale/zeroed balance from before ownEmployeeId was
  // known.
  const primaryBalanceLoading = !ownEmployeeId || balancesQuery.isPending;

  const hasMultipleEmployeeOptions = employeeOptions.length > 1;

  const totals = useMemo(() => {
    const submitted = requests.filter((request) => request.status === 'SUBMITTED').length;
    const approved = requests.filter((request) => request.status === 'APPROVED');
    const approvedDays = approved.reduce((sum, request) => sum + Number(request.totalDays || 0), 0);
    // EVERYDAY types ONLY -- summing every balance here produced "โควตาคงเหลือ 564 วัน", which is
    // not a number that means anything. MILITARY's annual_quota_days is 366, a deliberate SENTINEL
    // standing for "no annual ceiling, the 60-day PAID cap is the real rule" (V120), not a quota an
    // employee holds; MATERNITY's 98 and ORDINATION's 60 are per-occasion entitlements gated behind
    // conditions (once per employment, 12 months' service), not a running balance either. Adding
    // them to ลากิจ/ลาป่วย/ลาพักร้อน tells someone they have a year and a half of leave banked.
    // The rare-type rows in the "โควตาการลาทั้งหมด" disclosure below already suppress these same
    // numbers for the same reason -- this is that rule applied to the aggregate, which was the
    // one place it leaked.
    const remainingDays = everydayBalances.reduce((sum, balance) => sum + Number(balance.remainingDays || 0), 0);
    return { submitted, approved: approved.length, approvedDays, remainingDays };
  }, [requests, everydayBalances]);

  const activeCalendarItems = useMemo(
    () => requests
      .filter((request) => ['SUBMITTED', 'APPROVED'].includes(request.status))
      .slice()
      .sort((first, second) => first.startDate.localeCompare(second.startDate))
      .slice(0, 8),
    [requests],
  );

  // Seed the balance-preview select once leave types load (preserves the pre-A2 behavior: default
  // to the first everyday type, falling back to whatever the API returns first).
  useEffect(() => {
    if (previewTypeCode || leaveTypes.length === 0) return;
    const everyday = leaveTypes.find((type) => EVERYDAY_LEAVE_TYPE_CODES.has(type.code));
    setPreviewTypeCode(everyday?.code || leaveTypes[0].code);
  }, [leaveTypes, previewTypeCode]);

  // Issue #422 B3 fix: invalidate the ['leave'] PREFIX, not the exact
  // queryKeys.leaveRequests(appliedFilters)/leaveBalances(ownEmployeeId, balancesYear) pair --
  // every other filter combination (a different date range/status/employee, this same tab or
  // ReviewQueueTab's own unfiltered query) used to stay stale until its own filters were
  // reapplied.
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
          { key: 'remaining', label: 'โควตาคงเหลือ', value: formatDays(totals.remainingDays), helper: 'รวมประเภทที่เลือกได้' },
        ]}
      />

      {/* Card-diet, round 2 (owner feedback, 2026-08): showing all seven balances at once
          "over-populated the page with cards" -- the fix is not fewer, smaller cards (that's
          "the same mistake wearing a coat"), it's exactly ONE balance at a time, picked via the
          plain "ดูโควตา" select in this panel's header (Phase A2: no longer tied to a submission
          form's own type field, since that form moved to /leave/new). Everything else sits behind
          one disclosure, rendered as compact FieldList rows (a `<dl>`), never a second grid of
          cards. */}
      <Panel
        title="โควตาวันลา"
        actions={(
          <label className="flex items-center gap-2 text-sm font-semibold text-text-muted">
            ดูโควตา
            <select
              aria-label="เลือกประเภทการลาที่ต้องการดูโควตา"
              value={previewTypeCode}
              onChange={(event) => setPreviewTypeCode(event.target.value)}
            >
              {leaveTypes.map((type) => (
                <option key={type.code} value={type.code}>{type.nameTh || type.nameEn}</option>
              ))}
            </select>
          </label>
        )}
      >
        <PrimaryLeaveBalanceCard
          loading={primaryBalanceLoading}
          balance={selectedBalance}
          leaveType={selectedLeaveType}
          isEveryday={EVERYDAY_LEAVE_TYPE_CODES.has(previewTypeCode)}
        />
        <div className="mt-4">
          <CollapsibleSection title="โควตาการลาทั้งหมด" subtitle="ทุกประเภท รวมเงื่อนไขพิเศษ" defaultOpen={false}>
            {balances.length === 0 ? (
              <EmptyState icon="calendar" title="ยังไม่มีข้อมูลโควตา" />
            ) : (
              <FieldList>
                {balances.map((balance) => {
                  const leaveType = leaveTypes.find((type) => type.code === balance.leaveTypeCode);
                  const isEveryday = EVERYDAY_LEAVE_TYPE_CODES.has(balance.leaveTypeCode);
                  return (
                    <div key={balance.leaveTypeCode}>
                      <dt>{balance.leaveTypeNameTh || balance.leaveTypeCode}</dt>
                      {/* Rare rows deliberately never read balance.annualQuotaDays/remainingDays --
                          see rareBalanceSummary's comment above (MILITARY's 366 sentinel). */}
                      <dd>{isEveryday ? everydayBalanceSummary(balance) : rareBalanceSummary(balance, leaveType)}</dd>
                    </div>
                  );
                })}
              </FieldList>
            )}
          </CollapsibleSection>
        </div>
        <p className="mt-3 text-sm text-text-muted">
          อ้างอิง พ.ร.บ. คุ้มครองแรงงาน พ.ศ. 2541: ลากิจธุระจำเป็นไม่น้อยกว่า 3 วันต่อปี,
          ลาป่วยได้รับค่าจ้างไม่เกิน 30 วันทำงานต่อปี และลาพักร้อนไม่น้อยกว่า 6 วันต่อปีหลังทำงานครบ 1 ปี.
          <a href="https://www.mol.go.th/employee/สิทธิตามกฎหมายแรงงาน" target="_blank" rel="noreferrer"> กระทรวงแรงงาน</a>
        </p>
      </Panel>

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

      <Panel title="คำขอลาของฉัน" className="!p-0">
        <OwnRequestsSection
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
