import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { CompactStatRow } from '../../components/common/CompactStatRow.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { QuotaBar } from '../../components/common/QuotaBar.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { StatePanel } from '../../components/common/StatePanel.jsx';
import { UpcomingHolidays } from '../../components/common/UpcomingHolidays.jsx';
import { addDaysIso } from '../../utils/format.js';
import { LeaveFilterBar } from './LeaveFilterBar.jsx';
import { LeaveReportDownload } from './LeaveReportDownload.jsx';
import {
  formatDays, todayIso, yearFrom,
} from './leaveFormatting.js';
import { canSubmitOwnLeave } from './leaveSurfaceTabs.js';
import { LeaveRequestsSection } from './leaveRequestTable.jsx';
import { summarizeLeaveRequests, useCancelLeave, useLeaveFilters } from './useLeaveTabState.jsx';
import { UpcomingLeaveList } from './UpcomingLeaveList.jsx';

// How far ahead "วันลาที่กำลังจะถึง" looks. Same ~90-day forward window UpcomingHolidays renders with directly
// above it -- the two panels answer the same shape of question ("what is coming up") and would read as
// inconsistent if one looked 90 days ahead and the other some other distance.
const UPCOMING_LEAVE_WINDOW_DAYS = 90;

// The everyday-vs-rare balance split. SICK/PERSONAL/VACATION are what employees file day to day;
// MATERNITY/MILITARY/ORDINATION are uncommon, and MILITARY's annualQuotaDays (366) is a sentinel,
// not a policy number (see mockApi.js's db.leaveTypes comment), so "เหลือ 366 วัน" would be
// misleading. A named constant keeps one definition. Exported: LeaveRequestPage.jsx reuses it for
// its step-1 primary/secondary type disclosure.
export const EVERYDAY_LEAVE_TYPE_CODES = new Set(['SICK', 'PERSONAL', 'VACATION']);

// A rare type's meaningful figures are the paid-days cap and the eligibility gate, never a quota or a
// remaining-days count -- both are derived from annualQuotaDays, and MILITARY's is the 366 sentinel above.
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

// §5.3.5 carry-forward: `annualQuotaDays` is THIS year's own quota only (LeaveBalanceDto's
// Javadoc) while `remainingDays` already folds in carry-in (annualQuotaDays + carriedInDays -
// approved - pending). The card names the carried-in days and prints "เหลือ" so it agrees with the
// "ดูโควตา" option label (balanceOptionLabel) instead of showing two seemingly contradictory
// numbers.
function everydayBalanceSummary(balance) {
  const carriedInDays = Number(balance.carriedInDays || 0);
  const carryNote = carriedInDays > 0 ? ` (+ สะสมจากปีก่อน ${formatDays(balance.carriedInDays)})` : '';
  return `ใช้แล้ว ${formatDays(balance.approvedDays)} · รออนุมัติ ${formatDays(balance.pendingDays)} · สิทธิ์ ${formatDays(balance.annualQuotaDays)}${carryNote} · เหลือ ${formatDays(balance.remainingDays)}`;
}

// Deliberately never reads balance.annualQuotaDays/remainingDays -- see rareLeaveConditionText's comment above
// on why (MILITARY's 366 sentinel).
function rareBalanceSummary(balance, leaveType) {
  const parts = [`ใช้แล้ว ${formatDays(balance.approvedDays)}`, `รออนุมัติ ${formatDays(balance.pendingDays)}`];
  if (leaveType?.paidDaysCap != null) {
    parts.push(`จ่ายค่าจ้างสูงสุด ${formatDays(leaveType.paidDaysCap)}`);
  }
  const condition = rareLeaveConditionText(leaveType);
  if (condition) parts.push(condition);
  return parts.join(' · ');
}

/**
 * The "ดูโควตา" select's option text: the type name, plus its remaining days for the three
 * everyday types. The open dropdown answers "how much do I have left, of each kind" at a glance;
 * the card below stays the one detailed view.
 *
 * Rare types (MATERNITY/MILITARY/ORDINATION) get no figure, the same split the balance card,
 * aggregate stat and composer apply: their annualQuotaDays is a sentinel (MILITARY's 366) or a
 * per-occasion entitlement, so "เหลือ N วัน" would mean nothing. See rareLeaveConditionText above.
 *
 * Falls back to the bare name while balances load (`balance` is absent, same branch as a rare
 * type).
 */
function balanceOptionLabel(leaveType, balance) {
  const name = leaveType.nameTh || leaveType.nameEn;
  if (!balance || !EVERYDAY_LEAVE_TYPE_CODES.has(leaveType.code)) return name;
  return `${name} · เหลือ ${formatDays(balance.remainingDays)}`;
}

/**
 * The one balance card, driven by the leave type picked in the "ดูโควตา" select above it (Panel's
 * `actions`), a browsing control; submission lives in the /leave/new composer. `loading` covers
 * both "balances haven't loaded" and "no acting employee resolved yet" (balancesQuery is disabled
 * until then) so it never flashes a bare `0`; a resolved-but-missing balance gets its own
 * EmptyState rather than zeros.
 *
 * The everyday headline is a QuotaBar. `used` is what the API already subtracts to produce
 * `balance.remainingDays` (`annualQuotaDays - approvedDays - pendingDays`), so the bar shows the
 * same figure as a proportion. `cap` is `isEveryday ? balance.annualQuotaDays : null`, reusing the
 * everyday/rare split above; QuotaBar renders nothing when `cap == null`, which is the suppression
 * rare types need (MILITARY's 366 is a sentinel for "no annual ceiling; the paid cap is the real
 * rule", and MATERNITY/ORDINATION are per-occasion entitlements). The paid-cap headline and
 * condition text for rare types render alongside it unconditionally.
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
  // Same arithmetic as the API's balance.remainingDays; see the doc comment above.
  const used = Number(balance.approvedDays || 0) + Number(balance.pendingDays || 0);
  const summary = isEveryday ? everydayBalanceSummary(balance) : rareBalanceSummary(balance, leaveType);
  // §5.3.5 carry-forward: `cap` folds in carriedInDays, because approved+pending legitimately draws
  // on the carried-in pool too; with the flat annual figure the bar pinned at 100% red and fired a
  // false "exceeded your annual quota" warning. The bar's 100% means "used everything actually
  // available this year" (same as everydayBalanceSummary above).
  const cap = Number(balance.annualQuotaDays || 0) + Number(balance.carriedInDays || 0);

  return (
    <div className="grid gap-2" data-testid="primary-balance-card">
      <span className="block min-w-0 truncate text-base font-bold text-text-secondary">{name}</span>
      {/* `formatValue={formatDays}`: values carry their own unit as a duration, so the caption drops
          "(วัน)" rather than contradict a value reading "3 ชั่วโมง". */}
      <QuotaBar
        label={name}
        caption={`โควตา${name}`}
        used={used}
        cap={isEveryday ? cap : null}
        formatValue={formatDays}
        overMessage="ใช้วันลาเกินโควตาประจำปีแล้ว ส่วนที่เกินอาจถูกปฏิเสธอัตโนมัติ"
      />
      {/* Rare types (MATERNITY/MILITARY/ORDINATION): QuotaBar renders nothing above (cap=null), so the paid-cap
       * headline is the meaningful figure instead. See rareBalanceSummary's comment on why this never reads
       * annualQuotaDays/remainingDays. */}
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
  const navigate = useNavigate();
  // Empty by default, not monthStartIso()/todayIso(): `to: todayIso()` hid the employee's own FUTURE
  // leave (findRequests matches `start_date <= :toDate`), and `from: monthStartIso()` showed an
  // empty history on the 1st of a month.
  //
  // Empty strings are dropped by hrApi.js's `withQuery`, so no date parameter is sent and
  // LeaveService#list applies its own +/-12-month default. `status` stays '' (all statuses).
  const initialFilters = {
    from: '',
    to: '',
    status: '',
  };
  const {
    filters, appliedFilters, hasCustomFilters, updateFilter, clearFilters, submitFilters,
  } = useLeaveFilters(initialFilters);
  const { requestCancel, cancelDialog } = useCancelLeave(showToast);
  const [expandedId, setExpandedId] = useState(null);

  // This tab is read-only (browse balances, browse your own history, cancel a SUBMITTED request);
  // the submission form lives in the /leave/new composer, which owns its own on-behalf-of picker.
  const ownEmployeeId = currentEmployee?.id || user.employeeId || '';
  const [previewTypeCode, setPreviewTypeCode] = useState('');

  // --- Reads (TanStack Query) ---
  const leaveTypesQuery = useQuery({
    queryKey: queryKeys.leaveTypes(),
    queryFn: () => api.leave.types().then((response) => response.leaveTypes || []),
  });
  const leaveTypes = useMemo(() => leaveTypesQuery.data ?? [], [leaveTypesQuery.data]);

  // A modest poll + window-focus refetch: leave approvals are multi-user (an approval made in
  // another session or the "รอพิจารณา" tab must show here without a reload). `isPending` drives the
  // skeleton (first load only); `isFetching` only powers a quiet header indicator, so background
  // refetches never blank the rows already on screen.
  //
  // `employeeId` is ALWAYS the actor's own id: with it omitted, LeaveService#list serves "self OR
  // reports_to_employee_id = actor" (LeaveRepository#findRequests), putting a manager's reports into
  // a panel titled "คำขอลาของฉัน". The team-wide view lives in TeamLeaveTab.jsx. `enabled` mirrors
  // balancesQuery: never fetch before an acting employee id is known.
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
  // Covers both "no acting employee resolved yet" (balancesQuery is `enabled: !!ownEmployeeId`) and
  // "fetch in flight": the primary card shows its skeleton, not a stale or zeroed balance.
  const primaryBalanceLoading = !ownEmployeeId || balancesQuery.isPending;

  const totals = useMemo(() => {
    const { submitted, approved, approvedDays } = summarizeLeaveRequests(requests);
    // EVERYDAY types only: summing every balance gave "โควตาคงเหลือ 564 วัน". MILITARY's 366 is a
    // sentinel for "no annual ceiling, the 60-day PAID cap is the real rule", and MATERNITY's 98 and
    // ORDINATION's 60 are conditional per-occasion entitlements, not a running balance.
    const remainingDays = everydayBalances.reduce((sum, balance) => sum + Number(balance.remainingDays || 0), 0);
    return { submitted, approved, approvedDays, remainingDays };
  }, [requests, everydayBalances]);

  // The forward window shared by BOTH "what's coming up" panels below -- company holidays (UpcomingHolidays) and
  // the employee's own leave (UpcomingLeaveList). Computed once so the two can never drift to different
  // horizons; they sit adjacent and would read as inconsistent.
  const upcomingFrom = todayIso();
  const upcomingTo = addDaysIso(upcomingFrom, UPCOMING_LEAVE_WINDOW_DAYS);

  // "วันลาที่กำลังจะถึง" reads its OWN forward window rather than deriving from `requestsQuery`:
  // deriving from the history filter would cap the panel at `to` and empty it when the employee
  // narrows the filter to a past range. One extra request keeps it independent of the filter.
  //
  // Scoped to `ownEmployeeId` like requestsQuery (otherwise "self OR reports_to = actor" would put a
  // report's leave into the employee's OWN tab). The team-wide equivalent is in TeamLeaveTab.jsx.
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

  // Seed the balance-preview select once leave types load (preserves the pre-A2 behavior: default to the first
  // everyday type, falling back to whatever the API returns first).
  useEffect(() => {
    if (previewTypeCode || leaveTypes.length === 0) return;
    const everyday = leaveTypes.find((type) => EVERYDAY_LEAVE_TYPE_CODES.has(type.code));
    setPreviewTypeCode(everyday?.code || leaveTypes[0].code);
  }, [leaveTypes, previewTypeCode]);

  return (
    <>
      <CompactStatRow
        items={[
          { key: 'total', label: 'คำขอทั้งหมด', value: requests.length, helper: 'ในช่วงที่เลือก' },
          { key: 'submitted', label: 'รออนุมัติ', value: totals.submitted, helper: 'Submitted' },
          { key: 'approved', label: 'อนุมัติแล้ว', value: totals.approved, helper: formatDays(totals.approvedDays) },
          // `wrapValue`: this is a SUM across leave types, so it is the one tile that reliably carries all three units
          // ("39 วัน 3 ชั่วโมง 10 นาที") -- long enough to be ellipsised by the default truncation at desktop width,
          // never mind a phone. See CompactStatRow.
          { key: 'remaining', label: 'โควตาคงเหลือ', value: formatDays(totals.remainingDays), helper: 'รวมประเภทที่เลือกได้', wrapValue: true },
        ]}
      />

      <LeaveReportDownload scope="own" showToast={showToast} />

      {/* Exactly ONE balance at a time, picked via the plain "ดูโควตา" select in this panel's header
          (the submission form lives in /leave/new). */}
      <Panel
        title="โควตาวันลา"
        actions={(
          // `whitespace-nowrap`: the Panel header is a flex row and the label broke mid-word.
          //
          // `mobile:` (<=720px) stacks the label above the select and gives it the full row. A native
          // <select> clips its selected option with no ellipsis or tooltip, and the longest label ("ลาป่วย ·
          // เหลือ 29 วัน 6 ชั่วโมง 15 นาที") measures 250px against the 170px available side by side at 390px;
          // stacked it gets 270px. PanelHeader already wraps, so nothing else moves.
          <label className="flex min-w-0 items-center gap-2 whitespace-nowrap text-sm font-semibold text-text-muted mobile:w-full mobile:flex-col mobile:items-start mobile:gap-1">
            ดูโควตา
            {/* Fixed width, not content width: option labels grow by "· เหลือ …" once balancesQuery lands,
                and a content-sized select would shift the panel header on data load. 19.5rem fits the longest
                duration label (18.5rem left the 721px..1040px band one pixel short); `min-w-0` + `max-w-full`
                let it shrink inside the flex header, and `mobile:w-full` takes the stacked row above. */}
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
        {/* Statutory floor, not GL&R's own quota -- a footnote to the figures above, so it is separated by a hairline
         * and kept to one paragraph. It stays here rather than moving to LeavePolicyBar: that bar points at the
         * COMPANY's §5 announcement, which is a different document saying different numbers. */}
        <p className="mt-4 border-t border-border-subtle pt-3 text-sm text-text-muted">
          อ้างอิง พ.ร.บ. คุ้มครองแรงงาน พ.ศ. 2541: ลากิจธุระจำเป็นไม่น้อยกว่า 3 วันต่อปี,
          ลาป่วยได้รับค่าจ้างไม่เกิน 30 วันทำงานต่อปี และลาพักร้อนไม่น้อยกว่า 6 วันต่อปีหลังทำงานครบ 1 ปี.
          <a href="https://www.mol.go.th/employee/สิทธิตามกฎหมายแรงงาน" target="_blank" rel="noreferrer"> กระทรวงแรงงาน</a>
        </p>
      </Panel>

      {/* The two "what is coming up" panels share the same forward window: company holidays, then this
          employee's approved/pending leave. Both sit ABOVE the filter bar because neither is affected
          by it. The filter bar is the shared LeaveFilterBar below. */}
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
          emptyState={(
            <StatePanel
              state="empty"
              title="ยังไม่มีคำขอลา"
              description="ลาคือการหยุดงานที่ได้รับอนุมัติ กดปุ่ม “ยื่นคำขอลา” ด้านบนเพื่อเริ่ม เลือกประเภทและช่วงวันที่ ระบบจะตรวจโควตาให้ก่อน จากนั้นส่งให้ผู้อนุมัติพิจารณา"
              // hr/ceo oversee leave but do not request it for themselves, so no CTA for them.
              // Presentation only -- the real rule is LeaveService#resolveTargetEmployee.
              action={canSubmitOwnLeave(user) ? (
                <Button type="button" onClick={() => navigate('/leave/new')}><Icon name="plus" />ยื่นคำขอลา</Button>
              ) : undefined}
            />
          )}
        />
      </Panel>

      {cancelDialog}
    </>
  );
}
