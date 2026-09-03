import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { CompactStatRow } from '../../components/common/CompactStatRow.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { DataTable, expandedRowRegionId } from '../../components/common/DataTable.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { QuotaBar } from '../../components/common/QuotaBar.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { StatePanel } from '../../components/common/StatePanel.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { UpcomingHolidays } from '../../components/common/UpcomingHolidays.jsx';
import { downloadBlob } from '../../utils/download.js';
import { addDaysIso, leaveStatusLabel as statusInfo } from '../../utils/format.js';
import { LeaveFilterBar } from './LeaveFilterBar.jsx';
import {
  formatDateRange, formatDays, todayIso, yearFrom,
} from './leaveFormatting.js';
import { canSubmitOwnLeave } from './leaveSurfaceTabs.js';
import {
  buildLeaveRequestColumns, LEAVE_REQUEST_TABLE_GRID, leaveRequestRowKey,
  PendingApproverNote, renderLeaveRequestExpanded,
} from './leaveRequestTable.jsx';
import { UpcomingLeaveList } from './UpcomingLeaveList.jsx';

// How far ahead "วันลาที่กำลังจะถึง" looks. Same ~90-day forward window UpcomingHolidays renders
// with directly above it -- the two panels answer the same shape of question ("what is coming up")
// and would read as inconsistent if one looked 90 days ahead and the other some other distance.
const UPCOMING_LEAVE_WINDOW_DAYS = 90;

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

// §5.3.5 carry-forward (V127 field, wired through here 2026-09-03): `annualQuotaDays` is
// deliberately THIS year's own quota only (LeaveBalanceDto's own Javadoc), while `remainingDays`
// already has any carry-in folded in (annualQuotaDays + carriedInDays - approved - pending). This
// used to print `สิทธิ์ {annualQuotaDays}` with no acknowledgement of that gap -- an employee with
// a carry-over saw e.g. "สิทธิ์ 6" here and "· เหลือ 7" on the "ดูโควตา" select's own option label
// (balanceOptionLabel, which already read the correct merged remainingDays), two numbers that look
// contradictory side by side even though both were individually correct. Naming the carried-in
// days right here, and adding "เหลือ" so the reconciled figure appears on THIS card too (not only
// in the select), is what makes the two agree instead of one silently dropping a term.
function everydayBalanceSummary(balance) {
  const carriedInDays = Number(balance.carriedInDays || 0);
  const carryNote = carriedInDays > 0 ? ` (+ สะสมจากปีก่อน ${formatDays(balance.carriedInDays)})` : '';
  return `ใช้แล้ว ${formatDays(balance.approvedDays)} · รออนุมัติ ${formatDays(balance.pendingDays)} · สิทธิ์ ${formatDays(balance.annualQuotaDays)}${carryNote} · เหลือ ${formatDays(balance.remainingDays)}`;
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
              <Icon name="ban" size={14} />
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
        <PendingApproverNote request={request} />
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
        title="ยังไม่มีคำขอลา"
        description="ลาคือการหยุดงานที่ได้รับอนุมัติ กดปุ่ม “ยื่นคำขอลา” ด้านบนเพื่อเริ่ม เลือกประเภทและช่วงวันที่ ระบบจะตรวจโควตาให้ก่อน จากนั้นส่งให้ผู้อนุมัติพิจารณา"
        // Leave HR-submit gate (2026-08-03): mirrors LeaveSurfacePage.jsx's page-header CTA --
        // hr/ceo oversee leave but do not request it for themselves (owner ruling). This is the
        // SECOND of the two "ยื่นคำขอลา" entry points on this page; hiding it here too keeps the
        // UI coherent (no button that only leads to a server-side 403). Presentation only -- the
        // real rule is LeaveService#resolveTargetEmployee.
        action={canSubmitOwnLeave(user) ? (
          <Button type="button" onClick={() => navigate('/leave/new')}><Icon name="plus" />ยื่นคำขอลา</Button>
        ) : undefined}
      />
    );
  }

  return (
    <DataTable
      columns={columns}
      rows={rows}
      getRowKey={leaveRequestRowKey}
      gridClassName={LEAVE_REQUEST_TABLE_GRID}
      // DataTable brings its OWN `<Panel flush>` card, and this table is additionally wrapped in a
      // titled `<Panel flush>` below, so the two 1px borders sat adjacent: a card inside a card.
      // Every other DataTable caller in the app renders it bare; these two leave tabs keep the
      // outer panel only for its section title, so the inner card's chrome is what has to go, not
      // the heading.
      //
      // Scroll is deliberately NOT set here. The 980px grid floor inside an `overflow-hidden`
      // flush Panel used to clip 229px of this table -- measured at 834px, unreachable by any
      // gesture -- but #650 fixed that in Panel's own default and removed the three call-site
      // patches that had each worked around it. See ReviewQueueTab.jsx's comment at the same spot.
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

/**
 * The "ดูโควตา" select's own option text -- the type name, plus its remaining days for the three
 * everyday types.
 *
 * This is what makes the removal of the old "โควตาการลาทั้งหมด" disclosure a simplification rather
 * than a loss (owner ruling, 2026-08-11: "the manual selection already shows each type's remaining
 * quota, so the collapsible is redundant"). It was only redundant once the select actually said so:
 * before this it listed bare type names, and comparing two types meant opening the disclosure or
 * cycling the select and re-reading the card each time. One open dropdown now answers "how much
 * do I have left, of each kind" in a single glance, and the card below stays the one detailed view.
 *
 * Rare types (MATERNITY/MILITARY/ORDINATION) deliberately get NO figure -- the same
 * everyday/rare split the balance card, the aggregate stat and the composer all apply, for the
 * same reason: their annualQuotaDays is either a sentinel (MILITARY's 366, V120) or a
 * per-occasion entitlement, so a "เหลือ N วัน" derived from it would be a number that means
 * nothing. See rareLeaveConditionText's comment above.
 *
 * Also falls back to the bare name while balances are still loading -- `balance` is simply absent
 * then, which is the same branch as a rare type.
 */
function balanceOptionLabel(leaveType, balance) {
  const name = leaveType.nameTh || leaveType.nameEn;
  if (!balance || !EVERYDAY_LEAVE_TYPE_CODES.has(leaveType.code)) return name;
  return `${name} · เหลือ ${formatDays(balance.remainingDays)}`;
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
  // §5.3.5 carry-forward: `cap` used to be the bare annualQuotaDays, so a carry-over let `used`
  // (approved+pending, which legitimately draws on the carried-in pool too) exceed it -- the bar
  // pinned at 100% red and `overMessage` fired a false "exceeded your annual quota" warning for an
  // employee who still had real carried-in days left. Folding carriedInDays into the cap is the
  // same fix as everydayBalanceSummary's own reconciliation above: the bar's 100% now means "used
  // everything actually available this year", not "used more than the flat annual figure alone".
  const cap = Number(balance.annualQuotaDays || 0) + Number(balance.carriedInDays || 0);

  return (
    <div className="grid gap-2" data-testid="primary-balance-card">
      <span className="block min-w-0 truncate text-base font-bold text-text-secondary">{name}</span>
      {/* `formatValue={formatDays}` (2026-08-31): the bar used to print bare decimals ("0.38 / 7")
          under a caption that supplied the unit -- the same unreadable form the rest of this
          surface just left behind. The values now carry their own unit as a duration, so the
          caption drops its "(วัน)" rather than contradicting a value reading "3 ชั่วโมง". */}
      <QuotaBar
        label={name}
        caption={`โควตา${name}`}
        used={used}
        cap={isEveryday ? cap : null}
        formatValue={formatDays}
        overMessage="ใช้วันลาเกินโควตาประจำปีแล้ว ส่วนที่เกินอาจถูกปฏิเสธอัตโนมัติ"
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
  // Empty by default (2026-08-10 restructure), NOT monthStartIso()/todayIso(). Two separate
  // problems came from pre-filling these:
  //
  //  1. `to: todayIso()` made it impossible for this tab to show the employee's own FUTURE leave
  //     at all -- findRequests matches `start_date <= :toDate`, so tomorrow's approved vacation was
  //     filtered out server-side. That is defect D1, and it is why the panel titled "ปฏิทินวันลา"
  //     only ever listed leave that had already started.
  //  2. `from: monthStartIso()` meant arriving at this tab on the 1st of a month showed an empty
  //     history, and last month's leave required manually widening a filter the employee never set.
  //
  // Empty strings are dropped by hrApi.js's `withQuery` (hrApi.js:7), so no date parameter is sent
  // and LeaveService#list applies its own +/-12-month default -- "recent" with nothing typed in.
  // `status` stays '' for the same reason (all statuses).
  const initialFilters = {
    from: '',
    to: '',
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
  //
  // Bugfix (2026-08): this used to omit `employeeId` whenever no filter value was applied, which
  // for any actor with direct reports meant LeaveService#list's own default scoping ("self OR
  // reports_to_employee_id = actor" -- see LeaveRepository#findRequests) genuinely served their
  // reports' requests into a panel titled "คำขอลาของฉัน". `employeeId` is now ALWAYS the actor's
  // own id -- this tab shows exactly one person's requests, never more. The team-wide view that
  // used to leak in here moved to TeamLeaveTab.jsx ("ลูกทีม"), correctly labelled. `enabled`
  // mirrors balancesQuery below: never fetch before an acting employee id is actually known.
  const requestsQuery = useQuery({
    queryKey: queryKeys.leaveRequests({ ...appliedFilters, employeeId: ownEmployeeId }),
    queryFn: () => api.leave.list({
      from: appliedFilters.from,
      to: appliedFilters.to,
      status: appliedFilters.status,
      employeeId: ownEmployeeId,
    }).then((response) => response.requests || []),
    enabled: !!ownEmployeeId,
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

  // The forward window shared by BOTH "what's coming up" panels below -- company holidays
  // (UpcomingHolidays) and the employee's own leave (UpcomingLeaveList). Computed once so the two
  // can never drift to different horizons; they sit adjacent and would read as inconsistent.
  // Same ~90-day convention OvertimePanel.jsx's own UpcomingHolidays call uses.
  const upcomingFrom = todayIso();
  const upcomingTo = addDaysIso(upcomingFrom, UPCOMING_LEAVE_WINDOW_DAYS);

  // "วันลาที่กำลังจะถึง" reads its OWN forward window instead of deriving from `requestsQuery`
  // above -- the fix for defect D1, and the reason this is a second request rather than a
  // `.filter()`.
  //
  // Deriving it from the history query (what the old "ปฏิทินวันลา" did) coupled a
  // forward-looking panel to a backward-looking filter, with two consequences: the panel could
  // never show anything past `to`, and the moment an employee narrowed the filter to a past range
  // the "upcoming" panel would empty out even though their upcoming leave had not changed. A
  // dedicated query costs one request and makes the panel's contents independent of whatever the
  // employee is browsing below it.
  //
  // Scoped to `ownEmployeeId` for the same reason requestsQuery is: LeaveService#list's default
  // scoping for an actor with direct reports is "self OR reports_to = actor", which would put a
  // report's leave into a panel on the employee's OWN tab. The team-wide equivalent lives in
  // TeamLeaveTab.jsx, correctly labelled.
  const upcomingQuery = useQuery({
    queryKey: queryKeys.leaveRequests({ from: upcomingFrom, to: upcomingTo, employeeId: ownEmployeeId }),
    queryFn: () => api.leave.list({
      from: upcomingFrom,
      to: upcomingTo,
      employeeId: ownEmployeeId,
    }).then((response) => response.requests || []),
    enabled: !!ownEmployeeId,
  });
  const upcomingRequests = useMemo(() => upcomingQuery.data ?? [], [upcomingQuery.data]);

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
          // `wrapValue`: this is a SUM across leave types, so it is the one tile that reliably
          // carries all three units ("39 วัน 3 ชั่วโมง 10 นาที") -- long enough to be ellipsised
          // by the default truncation at desktop width, never mind a phone. See CompactStatRow.
          { key: 'remaining', label: 'โควตาคงเหลือ', value: formatDays(totals.remainingDays), helper: 'รวมประเภทที่เลือกได้', wrapValue: true },
        ]}
      />

      {/* Card-diet, round 2 (owner feedback, 2026-08): showing all seven balances at once
          "over-populated the page with cards" -- the fix is not fewer, smaller cards (that's
          "the same mistake wearing a coat"), it's exactly ONE balance at a time, picked via the
          plain "ดูโควตา" select in this panel's header (Phase A2: no longer tied to a submission
          form's own type field, since that form moved to /leave/new).

          Round 3 (owner ruling, 2026-08-11) removed what round 2 left behind: everything else used
          to sit under a "โควตาการลาทั้งหมด" CollapsibleSection here, repeating every balance as
          FieldList rows. That disclosure is now gone -- it restated, in a second format, what the
          select above it can say directly (see balanceOptionLabel), so the panel carried the same
          numbers twice and a chevron nobody needed to press. `git show <this commit>^` has it. */}
      <Panel
        title="โควตาวันลา"
        actions={(
          // `whitespace-nowrap`: the Panel header is a flex row and this label was breaking
          // mid-word into "ดู" / "โควตา" stacked above each other next to the select.
          //
          // `mobile:` (<=720px) stacks the label above the select and gives the select the full
          // row. A native <select> clips its own selected-option text with no ellipsis and no
          // tooltip, so a label it cannot fit is simply lost -- and the option labels grew when
          // the remaining figure became a duration: the longest ("ลาป่วย · เหลือ 29 วัน 6 ชั่วโมง
          // 15 นาที") measures 250px of text against the 170px this control had at 390px. Side by
          // side there is no width to give it there; stacked, the row yields 270px and it fits.
          // PanelHeader already wraps, so nothing else in the header moves.
          <label className="flex min-w-0 items-center gap-2 whitespace-nowrap text-sm font-semibold text-text-muted mobile:w-full mobile:flex-col mobile:items-start mobile:gap-1">
            ดูโควตา
            {/* Fixed width, not content width. The option labels grow by "· เหลือ …" the moment
                balancesQuery lands, and a content-sized select would jump wider on that first
                paint -- a layout shift in the panel header, on data load, for no reason. Sized
                (19.5rem) for the longest label a duration can produce -- measured, not guessed:
                at 18.5rem the 721px..1040px band left the widest option exactly one pixel short; `min-w-0` + `max-w-full` let it shrink inside the flex header rather than
                push the title, and `mobile:w-full` takes the stacked row above. */}
            <select
              aria-label="เลือกประเภทการลาที่ต้องการดูโควตา"
              className="w-[19.5rem] min-w-0 max-w-full mobile:w-full"
              value={previewTypeCode}
              onChange={(event) => setPreviewTypeCode(event.target.value)}
            >
              {leaveTypes.map((type) => (
                <option key={type.code} value={type.code}>
                  {balanceOptionLabel(type, balances.find((balance) => balance.leaveTypeCode === type.code))}
                </option>
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
        {/* Statutory floor, not GL&R's own quota -- a footnote to the figures above, so it is
            separated by a hairline and kept to one paragraph. It stays here rather than moving to
            LeavePolicyBar: that bar points at the COMPANY's §5 announcement, which is a different
            document saying different numbers. */}
        <p className="mt-4 border-t border-border-subtle pt-3 text-sm text-text-muted">
          อ้างอิง พ.ร.บ. คุ้มครองแรงงาน พ.ศ. 2541: ลากิจธุระจำเป็นไม่น้อยกว่า 3 วันต่อปี,
          ลาป่วยได้รับค่าจ้างไม่เกิน 30 วันทำงานต่อปี และลาพักร้อนไม่น้อยกว่า 6 วันต่อปีหลังทำงานครบ 1 ปี.
          <a href="https://www.mol.go.th/employee/สิทธิตามกฎหมายแรงงาน" target="_blank" rel="noreferrer"> กระทรวงแรงงาน</a>
        </p>
      </Panel>

      {/* The two "what is coming up" panels, adjacent and on the SAME forward window: company
          holidays first (the shared calendar everyone is subject to), then this employee's own
          approved/pending leave. Both sit ABOVE the filter bar, because neither is affected by it
          -- the pre-restructure layout put the filter above the holidays panel it had no effect
          on, which is defect D4.

          Rebase note (#638): main's version of this file had just converted the INLINE filter bar
          here to FilterField. That inline bar no longer exists on either tab -- it is now the
          shared LeaveFilterBar below, which carries the FilterField conversion once instead of
          twice. Nothing from #638 is lost; see LeaveFilterBar.jsx. */}
      <UpcomingHolidays from={upcomingFrom} to={upcomingTo} />

      <UpcomingLeaveList
        title="วันลาที่กำลังจะถึง"
        requests={upcomingRequests}
        loading={!ownEmployeeId || upcomingQuery.isPending}
        emptyTitle="ยังไม่มีวันลาที่กำลังจะถึง"
        emptyDescription="วันลาที่อนุมัติแล้วหรือรออนุมัติในช่วง 90 วันข้างหน้าจะแสดงที่นี่"
      />

      {/* Directly above "ประวัติการลา" -- the one section it governs. */}
      <LeaveFilterBar
        values={filters}
        onChange={updateFilter}
        onSubmit={submitFilters}
        submitDisabled={requestsQuery.isPending}
        refreshing={refreshing}
      />

      <Panel title="ประวัติการลา" flush>
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
