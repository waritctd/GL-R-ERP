import { useEffect, useMemo, useState } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import imageCompression from 'browser-image-compression';
import { useForm, useWatch } from 'react-hook-form';
import { z } from 'zod';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { hasPermission } from '../../app/permissions.js';
import { Button } from '../../components/common/Button.jsx';
import { CollapsibleSection } from '../../components/common/CollapsibleSection.jsx';
import { CompactStatRow } from '../../components/common/CompactStatRow.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { DataTable, expandedRowRegionId } from '../../components/common/DataTable.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { FieldList } from '../../components/common/FieldList.jsx';
import { FileUploadField } from '../../components/common/FileUploadField.jsx';
import { FormField, fieldErrorId } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { formGridSpan2, Panel } from '../../components/common/Layout.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { StatePanel } from '../../components/common/StatePanel.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { leaveStatusLabel as statusInfo } from '../../utils/format.js';
import {
  formatDateRange, formatDays, monthStartIso, todayIso, yearFrom,
} from './leaveFormatting.js';
import {
  buildLeaveRequestColumns, LEAVE_PURPOSE_OPTIONS, LEAVE_REQUEST_TABLE_GRID, leaveRequestRowKey,
  renderLeaveRequestExpanded,
} from './leaveRequestTable.jsx';

// FilterBar (Layout.jsx) renders a <div>; this form needs native submit semantics
// (Enter-to-submit on the search button), so its exact utility string is reproduced
// here rather than wrapping a <form> inside a non-form primitive.
const FILTER_BAR_CLASS = 'flex flex-wrap gap-[10px] items-center bg-surface border border-border rounded-md p-[14px]';
// FormGrid (Layout.jsx) renders a <div>; the submit form needs to be a native <form>
// for onSubmit/noValidate, so its exact (2-column) utility string is reproduced here.
const FORM_GRID_CLASS = 'grid gap-[14px] max-[720px]:grid-cols-1 grid-cols-2';

// Leave-surface IA rebuild Phase A1 (later narrowed by owner feedback, "one primary card, not
// every quota card at once"): the everyday-vs-rare balance split. SICK/PERSONAL/VACATION are what
// every employee actually files day to day; MATERNITY/MILITARY/ORDINATION are real but uncommon,
// and MILITARY's annualQuotaDays (366) is a deliberate sentinel, not a policy number (see
// mockApi.js's db.leaveTypes comment) -- showing "เหลือ 366 วัน" for it is worse than not showing
// a quota figure at all. Named constant, not an inline `code === 'SICK' || ...` check at each call
// site, so the split has exactly one definition to update if the everyday set ever changes.
const EVERYDAY_LEAVE_TYPE_CODES = new Set(['SICK', 'PERSONAL', 'VACATION']);

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

function defaultForm(employeeId = '', leaveTypeCode = 'VACATION') {
  const date = todayIso();
  return {
    employeeId: employeeId ? String(employeeId) : '',
    leaveTypeCode,
    startDate: date,
    endDate: date,
    reason: '',
    // Sub-day leave (2026-07-25): checkbox forces endDate=startDate and reveals the two time
    // pickers below. Paper-form (ใบลาหยุด F-HR-020) contact-during-leave block: autofilled from
    // api.leave.contactDefaults, editable.
    subDay: false,
    startTime: '',
    endTime: '',
    contactHouseNo: '',
    contactSubdistrict: '',
    contactDistrict: '',
    contactProvince: '',
    contactPhone: '',
    // §5.2 purpose/emergency-filing (V125). purposeCode is free-standing (only shown/meaningful for
    // PERSONAL -- see the leaveTypeCode select below). requestedAsEmergency declares this late
    // filing should be considered under the <=3/month "โดยไม่หักเงิน" tolerance instead of being
    // auto-rejected for missing the 1-day notice.
    purposeCode: '',
    requestedAsEmergency: false,
  };
}

const LEAVE_START_PAST_MESSAGE = 'วันที่เริ่มลาต้องไม่ก่อนวันนี้';
const LEAVE_TIME_ORDER_MESSAGE = 'เวลาสิ้นสุดต้องหลังเวลาเริ่ม';

function createLeaveFormSchema({ requireEmployeeId, minStartDate }) {
  return z.object({
    employeeId: z.string(),
    leaveTypeCode: z.string().min(1, 'กรุณาเลือกประเภทการลา'),
    startDate: z.string().min(1, 'กรุณาเลือกวันที่เริ่ม'),
    endDate: z.string().min(1, 'กรุณาเลือกวันที่สิ้นสุด'),
    reason: z.string().min(1, 'กรุณาระบุเหตุผลการลา'),
    subDay: z.boolean().optional(),
    startTime: z.string().optional(),
    endTime: z.string().optional(),
    contactHouseNo: z.string().optional(),
    contactSubdistrict: z.string().optional(),
    contactDistrict: z.string().optional(),
    contactProvince: z.string().optional(),
    contactPhone: z.string().optional(),
    purposeCode: z.string().optional(),
    requestedAsEmergency: z.boolean().optional(),
  }).superRefine((data, context) => {
    if (requireEmployeeId && !data.employeeId) {
      context.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['employeeId'],
        message: 'กรุณาเลือกพนักงาน',
      });
    }
    if (data.startDate && data.startDate < minStartDate) {
      context.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['startDate'],
        message: LEAVE_START_PAST_MESSAGE,
      });
    }
    if (data.subDay) {
      if (!data.startTime) {
        context.addIssue({ code: z.ZodIssueCode.custom, path: ['startTime'], message: 'กรุณาระบุเวลาเริ่ม' });
      }
      if (!data.endTime) {
        context.addIssue({ code: z.ZodIssueCode.custom, path: ['endTime'], message: 'กรุณาระบุเวลาสิ้นสุด' });
      }
      if (data.startTime && data.endTime && data.endTime <= data.startTime) {
        context.addIssue({ code: z.ZodIssueCode.custom, path: ['endTime'], message: LEAVE_TIME_ORDER_MESSAGE });
      }
    }
  });
}

function isPermissionError(error) {
  return error?.status === 403;
}

/**
 * The employee's own request table, split into DataTable (loading skeleton / real rows /
 * pagination / error+retry) plus a StatePanel for the three zero-row cases the pre-A1 table
 * collapsed into one generic EmptyState: `filtered` (non-default filters applied, offers a
 * clear-filters action), `empty` (teaching copy -- first-time, no data at all yet), and `denied`
 * (the request errored with a 403).
 */
function OwnRequestsSection({
  requestsQuery, rows, hasCustomFilters, onClearFilters, expandedId, onToggleExpand, onCancel, user,
}) {
  const loading = requestsQuery.isPending;
  const denied = requestsQuery.isError && isPermissionError(requestsQuery.error);
  const hasError = requestsQuery.isError && !denied;

  const columns = useMemo(() => buildLeaveRequestColumns({
    expandedId,
    onToggleExpand,
    renderActions: (request) => {
      const canCancel = request.status === 'SUBMITTED' && Number(request.employeeId) === Number(user.employeeId);
      if (!canCancel) return null;
      return (
        <Button type="button" variant="icon" title="ยกเลิก" aria-label="ยกเลิก" onClick={() => onCancel(request.id)}>
          <Icon name="close" size={14} />
        </Button>
      );
    },
  }), [expandedId, onToggleExpand, onCancel, user.employeeId]);

  function mobileCard(request) {
    const status = statusInfo(request.status);
    const canCancel = request.status === 'SUBMITTED' && Number(request.employeeId) === Number(user.employeeId);
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
        description="ลาคือการหยุดงานที่ได้รับอนุมัติ ยื่นคำขอที่ฟอร์ม “ยื่นคำขอลา” ด้านบน ระบุประเภทและช่วงวันที่ ระบบจะตรวจโควตาและอนุมัติอัตโนมัติถ้าเข้าเงื่อนไข"
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
      renderExpanded={(request) => (expandedId === request.id ? renderLeaveRequestExpanded(request) : null)}
      showPagination
    />
  );
}

/**
 * The one balance card the owner asked for ("really show just the primary one default because
 * if we show everything the page will just be over populated with cards") -- driven by whichever
 * leave type is currently selected in the request form's "ประเภทการลา" select, so it tracks the
 * form instead of asking the reader to cross-reference a grid. `loading` covers both "balances
 * haven't loaded yet" and "no acting employee resolved yet" (balancesQuery is disabled until
 * then) so this never flashes a bare `0` before real data lands; a resolved-but-missing balance
 * (the selected type has no row) gets its own EmptyState rather than silently rendering zeros.
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
  // Rare types never headline remainingDays/annualQuotaDays (MILITARY's 366 sentinel) -- the paid
  // cap is the meaningful headline figure instead. See rareBalanceSummary's comment above.
  const headlineLabel = isEveryday ? 'คงเหลือ' : 'สิทธิ์จ่ายค่าจ้างสูงสุด';
  const headlineValue = isEveryday
    ? formatDays(balance.remainingDays)
    : (leaveType?.paidDaysCap != null ? formatDays(leaveType.paidDaysCap) : '-');
  const summary = isEveryday ? everydayBalanceSummary(balance) : rareBalanceSummary(balance, leaveType);

  return (
    <div className="grid gap-1" data-testid="primary-balance-card">
      <span className="block min-w-0 truncate text-base font-bold text-text-secondary">{name}</span>
      <span className="mt-1 block text-xs font-bold uppercase tracking-wide text-text-muted">{headlineLabel}</span>
      <strong className="block text-4xl font-extrabold leading-tight tabular-nums text-text">{headlineValue}</strong>
      <small className="mt-1 block text-sm text-text-muted">{summary}</small>
    </div>
  );
}

export function MyLeaveTab({ user, currentEmployee, showToast, formAnchorRef }) {
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
  const [attachmentFile, setAttachmentFile] = useState(null);
  const [fileInputKey, setFileInputKey] = useState(0);
  const [expandedId, setExpandedId] = useState(null);

  // Same as the pre-A1 LeavePage.jsx: `canReviewAll` (hr today) also widens the SUBMIT-ON-BEHALF-OF
  // picker below to every active employee, not just this user's own direct reports -- unrelated to
  // review/cancel row actions, which this tab no longer renders at all (ReviewQueueTab.jsx owns
  // those exclusively now, so this flag is not threaded any further than the one filter below).
  const canReviewAll = hasPermission(user.role, 'canReviewLeave');

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

  const submitEmployeeOptions = useMemo(
    () => employeeOptions.filter((employee) => employee.self || employee.directReport || canReviewAll),
    [employeeOptions, canReviewAll],
  );
  const hasMultipleSubmitOptions = submitEmployeeOptions.length > 1;
  const leaveFormSchema = useMemo(
    () => createLeaveFormSchema({ requireEmployeeId: hasMultipleSubmitOptions, minStartDate: todayIso() }),
    [hasMultipleSubmitOptions],
  );
  const {
    register,
    handleSubmit,
    reset,
    setValue,
    getValues,
    control,
    formState: { errors },
  } = useForm({
    resolver: zodResolver(leaveFormSchema),
    defaultValues: defaultForm(currentEmployee?.id || user.employeeId || ''),
    mode: 'onChange',
    reValidateMode: 'onChange',
  });
  const [formEmployeeId, formStartDate, formLeaveTypeCode, formSubDay] = useWatch({
    control,
    name: ['employeeId', 'startDate', 'leaveTypeCode', 'subDay'],
  });
  const startDateInPast = Boolean(formStartDate && formStartDate < todayIso());
  const startDateError = startDateInPast ? LEAVE_START_PAST_MESSAGE : errors.startDate?.message;

  const balancesYear = yearFrom(formStartDate);
  const balancesQuery = useQuery({
    queryKey: queryKeys.leaveBalances(formEmployeeId, balancesYear),
    queryFn: () => api.leave.balances({
      ...(formEmployeeId ? { employeeId: formEmployeeId } : {}),
      year: balancesYear,
    }).then((response) => response.balances || []),
    enabled: !!formEmployeeId,
  });
  const balances = useMemo(() => balancesQuery.data ?? [], [balancesQuery.data]);
  const everydayBalances = useMemo(
    () => balances.filter((balance) => EVERYDAY_LEAVE_TYPE_CODES.has(balance.leaveTypeCode)),
    [balances],
  );
  const selectedBalance = useMemo(
    () => balances.find((balance) => balance.leaveTypeCode === formLeaveTypeCode),
    [balances, formLeaveTypeCode],
  );
  const selectedLeaveType = useMemo(
    () => leaveTypes.find((type) => type.code === formLeaveTypeCode),
    [leaveTypes, formLeaveTypeCode],
  );
  // Covers both "no acting employee resolved yet" (balancesQuery is `enabled: !!formEmployeeId`,
  // so it never even starts a fetch until then) and "the fetch is in flight" -- either way the
  // primary card must show its skeleton, not a stale/zeroed balance from before formEmployeeId
  // was known.
  const primaryBalanceLoading = !formEmployeeId || balancesQuery.isPending;

  // Paper-form (ใบลาหยุด F-HR-020) contact-during-leave autofill + read-only position/dept/division.
  const contactDefaultsQuery = useQuery({
    queryKey: queryKeys.leaveContactDefaults(formEmployeeId),
    queryFn: () => api.leave.contactDefaults({
      ...(formEmployeeId ? { employeeId: formEmployeeId } : {}),
    }).then((response) => response.contactDefaults || null),
    enabled: !!formEmployeeId,
  });
  const contactDefaults = contactDefaultsQuery.data ?? null;

  // FIX (Phase A1): the pre-A1 page fired `showToast('error', ...)` from FIVE separate
  // `useEffect`s keyed on each query's `.error` (employees/types/requests/balances/
  // contactDefaults) -- with no inline error UI or retry at all for any of them, AND
  // `showToast` has an unstable identity (CLAUDE.md), so a dependency array that ever grew to
  // include it directly (rather than as the last, harmless-if-stale entry these already were)
  // would infinite-loop. The table's own load failure now has real inline UI instead (DataTable's
  // `error`+`onRetry` via OwnRequestsSection above); the other four reference-data queries
  // (employees/types/balances/contactDefaults) already degrade to their existing empty-state
  // fallbacks below (`balances.length === 0` -> EmptyState, empty `<select>` options, etc.) when
  // their query errors, since each one's `.data ?? []`/`?? null` default already treats "errored"
  // and "genuinely empty" the same way -- removing the toast is a strict improvement (no more
  // infinite-loop risk) with no silent-failure regression beyond what already existed.
  function applyContactDefaults(defaults) {
    if (!defaults) return;
    setValue('contactHouseNo', defaults.contactHouseNo || '');
    setValue('contactSubdistrict', defaults.contactSubdistrict || '');
    setValue('contactDistrict', defaults.contactDistrict || '');
    setValue('contactProvince', defaults.contactProvince || '');
    setValue('contactPhone', defaults.contactPhone || '');
  }

  useEffect(() => {
    applyContactDefaults(contactDefaults);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [contactDefaults]);

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

  // Seed form defaults once reference data lands (preserves the pre-Query behavior:
  // default the acting employee and leave type from whatever the queries return).
  useEffect(() => {
    if (!employeesQuery.data && !leaveTypesQuery.data) return;
    const currentEmployeeId = getValues('employeeId');
    const currentLeaveTypeCode = getValues('leaveTypeCode');
    const nextEmployeeId = currentEmployeeId
      || submitEmployeeOptions.find((employee) => employee.self)?.employeeId
      || submitEmployeeOptions[0]?.employeeId
      || '';
    const nextLeaveTypeCode = currentLeaveTypeCode || leaveTypes[0]?.code || 'VACATION';
    if (String(nextEmployeeId) === String(currentEmployeeId) && nextLeaveTypeCode === currentLeaveTypeCode) return;
    setValue('employeeId', String(nextEmployeeId), { shouldValidate: true });
    setValue('leaveTypeCode', nextLeaveTypeCode, { shouldValidate: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [employeesQuery.data, leaveTypesQuery.data]);

  // Issue #422 B3 fix: invalidate the ['leave'] PREFIX, not the exact
  // queryKeys.leaveRequests(appliedFilters)/leaveBalances(formEmployeeId, balancesYear) pair --
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

  function handleStartDateChange(event) {
    const value = event.target.value;
    if (getValues('subDay') || getValues('endDate') < value) {
      setValue('endDate', value, { shouldDirty: true, shouldValidate: true });
    }
  }

  function handleSubDayToggle(event) {
    if (event.target.checked) {
      setValue('endDate', getValues('startDate'), { shouldDirty: true, shouldValidate: true });
    } else {
      setValue('startTime', '', { shouldDirty: true, shouldValidate: true });
      setValue('endTime', '', { shouldDirty: true, shouldValidate: true });
    }
  }

  function submitFilters(event) {
    event.preventDefault();
    setAppliedFilters(filters);
  }

  const createMutation = useMutation({
    mutationFn: (payload) => api.leave.create(payload).then((response) => response.request),
    onSuccess: (created) => {
      const nextEmployeeId = formEmployeeId || currentEmployee?.id || user.employeeId || '';
      reset(defaultForm(nextEmployeeId, formLeaveTypeCode));
      applyContactDefaults(contactDefaults);
      setAttachmentFile(null);
      setFileInputKey((key) => key + 1);
      if (created?.status === 'AUTO_REJECTED') {
        showToast('error', created.systemNote || 'คำขอลาไม่ผ่านเงื่อนไข');
      } else {
        showToast('success', 'ส่งคำขอลาและอนุมัติอัตโนมัติแล้ว');
      }
      invalidateLeave();
    },
    onError: (error) => showToast('error', error.message || 'ส่งคำขอลาไม่สำเร็จ'),
  });

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

  const saving = createMutation.isPending || cancelMutation.isPending;

  async function prepareAttachment(file) {
    if (!file) return null;
    if (!['application/pdf', 'image/jpeg', 'image/png'].includes(file.type)) {
      throw new Error('รองรับเฉพาะไฟล์ PDF, JPG หรือ PNG');
    }
    if (!file.type.startsWith('image/')) {
      return file;
    }
    return imageCompression(file, {
      maxSizeMB: 2,
      maxWidthOrHeight: 1600,
      useWebWorker: true,
    });
  }

  async function submitLeave(values) {
    let preparedAttachment = null;
    try {
      preparedAttachment = await prepareAttachment(attachmentFile);
    } catch (error) {
      showToast('error', error.message || 'เตรียมไฟล์แนบไม่สำเร็จ');
      return;
    }
    createMutation.mutate({
      employeeId: values.employeeId ? Number(values.employeeId) : null,
      leaveTypeCode: values.leaveTypeCode,
      startDate: values.startDate,
      endDate: values.subDay ? values.startDate : values.endDate,
      reason: values.reason.trim(),
      startTime: values.subDay && values.startTime ? values.startTime : null,
      endTime: values.subDay && values.endTime ? values.endTime : null,
      contactHouseNo: values.contactHouseNo?.trim() || null,
      contactSubdistrict: values.contactSubdistrict?.trim() || null,
      contactDistrict: values.contactDistrict?.trim() || null,
      contactProvince: values.contactProvince?.trim() || null,
      contactPhone: values.contactPhone?.trim() || null,
      purposeCode: values.leaveTypeCode === 'PERSONAL' && values.purposeCode ? values.purposeCode : null,
      requestedAsEmergency: values.leaveTypeCode === 'PERSONAL' ? Boolean(values.requestedAsEmergency) : null,
      attachmentFile: preparedAttachment,
    });
  }

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
          "the same mistake wearing a coat"), it's exactly ONE balance: whichever leave type is
          selected in the "ประเภทการลา" field below. Everything else sits behind one disclosure,
          rendered as compact FieldList rows (a `<dl>`), never a second grid of cards. */}
      <Panel title="โควตาวันลา">
        <PrimaryLeaveBalanceCard
          loading={primaryBalanceLoading}
          balance={selectedBalance}
          leaveType={selectedLeaveType}
          isEveryday={EVERYDAY_LEAVE_TYPE_CODES.has(formLeaveTypeCode)}
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

      <Panel title="ยื่นคำขอลา">
        <form
          ref={formAnchorRef}
          tabIndex={-1}
          className={`${FORM_GRID_CLASS} focus-visible:outline-none`}
          onSubmit={handleSubmit(submitLeave)}
          noValidate
        >
          {hasMultipleSubmitOptions ? (
            <FormField label="พนักงาน" htmlFor="leave-employee" error={errors.employeeId?.message}>
              <select
                id="leave-employee"
                {...register('employeeId')}
                value={formEmployeeId ?? ''}
                onChange={(event) => setValue('employeeId', event.target.value, { shouldValidate: true })}
                aria-invalid={Boolean(errors.employeeId)}
                aria-describedby={errors.employeeId ? fieldErrorId('leave-employee') : undefined}
                required
              >
                <option value="">เลือกพนักงาน</option>
                {submitEmployeeOptions.map((employee) => (
                  <option key={employee.employeeId} value={employee.employeeId}>
                    {employee.employeeName} · {employee.employeeCode}{employee.directReport ? ' · ลูกทีม' : ''}
                  </option>
                ))}
              </select>
            </FormField>
          ) : (
            <FormField label="พนักงาน" htmlFor="leave-employee-display">
              <input id="leave-employee-display" value={currentEmployee?.nameTh || user.name || '-'} disabled />
            </FormField>
          )}
          <FormField label="ประเภทการลา" htmlFor="leave-type-code" error={errors.leaveTypeCode?.message} required>
            <select
              id="leave-type-code"
              {...register('leaveTypeCode')}
              value={formLeaveTypeCode ?? ''}
              onChange={(event) => setValue('leaveTypeCode', event.target.value, { shouldValidate: true })}
              aria-invalid={Boolean(errors.leaveTypeCode)}
              aria-describedby={errors.leaveTypeCode ? fieldErrorId('leave-type-code') : undefined}
              required
            >
              {leaveTypes.map((type) => (
                <option key={type.code} value={type.code}>{type.nameTh || type.nameEn}</option>
              ))}
            </select>
            {/* The "คงเหลือ ... จากสิทธิ์ ..." duplicate line that used to live here was deleted --
                the "โควตาวันลา" primary card above (PrimaryLeaveBalanceCard) already tracks this
                same select and says exactly this, larger. */}
          </FormField>

          {formLeaveTypeCode === 'PERSONAL' ? (
            <>
              <FormField label="เหตุผลการลากิจ (ถ้ามี)" htmlFor="leave-purpose-code">
                <select id="leave-purpose-code" {...register('purposeCode')}>
                  <option value="">-- ไม่ระบุ --</option>
                  {LEAVE_PURPOSE_OPTIONS.map((option) => (
                    <option key={option.value} value={option.value}>{option.label}</option>
                  ))}
                </select>
              </FormField>
              <div className={formGridSpan2}>
                <label className="flex cursor-pointer items-center gap-2 text-sm font-medium">
                  <input type="checkbox" className="h-4 w-4 shrink-0" {...register('requestedAsEmergency')} />
                  ลากิจฉุกเฉิน (แจ้งหัวหน้างานก่อนเริ่มงานแล้ว และจะยื่นใบลาในวันแรกที่กลับมาทำงาน) — ใช้ได้ไม่เกิน 3 ครั้ง/เดือน โดยไม่หักเงิน
                </label>
              </div>
            </>
          ) : null}

          <FormField
            label="วันที่เริ่ม"
            htmlFor="leave-start-date"
            error={startDateError}
            required
          >
            <input
              id="leave-start-date"
              type="date"
              {...register('startDate', { onChange: handleStartDateChange })}
              className={startDateError ? 'is-invalid' : ''}
              aria-invalid={Boolean(startDateError)}
              aria-describedby={startDateError ? fieldErrorId('leave-start-date') : undefined}
              required
            />
          </FormField>
          <FormField label="วันที่สิ้นสุด" htmlFor="leave-end-date" error={errors.endDate?.message} required>
            <input
              id="leave-end-date"
              type="date"
              {...register('endDate')}
              min={formStartDate}
              aria-invalid={Boolean(errors.endDate)}
              aria-describedby={errors.endDate ? fieldErrorId('leave-end-date') : undefined}
              required
            />
          </FormField>
          <FormField label="เอกสารแนบ" htmlFor="leave-attachment-file">
            <FileUploadField
              key={fileInputKey}
              id="leave-attachment-file"
              accept="application/pdf,image/jpeg,image/png,.pdf,.jpg,.jpeg,.png"
              onChange={(event) => setAttachmentFile(event.target.files?.[0] || null)}
              helperText="PDF, JPG หรือ PNG · ลาป่วยต้องแนบใบรับรองแพทย์"
            />
          </FormField>

          <div className={formGridSpan2}>
            <label className="flex cursor-pointer items-center gap-2 text-sm font-medium">
              <input
                type="checkbox"
                className="h-4 w-4 shrink-0"
                {...register('subDay', { onChange: handleSubDayToggle })}
              />
              ลาบางส่วนของวัน (ระบุเวลา)
            </label>
          </div>
          {formSubDay ? (
            <>
              <FormField label="เวลาเริ่ม" htmlFor="leave-start-time" error={errors.startTime?.message} required>
                <input
                  id="leave-start-time"
                  type="time"
                  {...register('startTime')}
                  aria-invalid={Boolean(errors.startTime)}
                  aria-describedby={errors.startTime ? fieldErrorId('leave-start-time') : undefined}
                  required
                />
              </FormField>
              <FormField label="เวลาสิ้นสุด" htmlFor="leave-end-time" error={errors.endTime?.message} required>
                <input
                  id="leave-end-time"
                  type="time"
                  {...register('endTime')}
                  aria-invalid={Boolean(errors.endTime)}
                  aria-describedby={errors.endTime ? fieldErrorId('leave-end-time') : undefined}
                  required
                />
              </FormField>
            </>
          ) : null}

          <FormField label="ตำแหน่ง" htmlFor="leave-position-display">
            <input id="leave-position-display" value={contactDefaults?.positionTh || '-'} disabled />
          </FormField>
          <FormField label="แผนก / ฝ่าย" htmlFor="leave-department-display">
            <input
              id="leave-department-display"
              value={[contactDefaults?.departmentTh, contactDefaults?.divisionTh].filter(Boolean).join(' / ') || '-'}
              disabled
            />
          </FormField>
          <div className={formGridSpan2}>
            <p className="m-0 text-sm font-semibold text-text">ที่อยู่ที่ติดต่อได้ระหว่างลา</p>
          </div>
          <FormField label="บ้านเลขที่ / ที่อยู่" htmlFor="leave-contact-house-no">
            <input id="leave-contact-house-no" {...register('contactHouseNo')} />
          </FormField>
          <FormField label="ตำบล / แขวง" htmlFor="leave-contact-subdistrict">
            <input id="leave-contact-subdistrict" {...register('contactSubdistrict')} />
          </FormField>
          <FormField label="อำเภอ / เขต" htmlFor="leave-contact-district">
            <input id="leave-contact-district" {...register('contactDistrict')} />
          </FormField>
          <FormField label="จังหวัด" htmlFor="leave-contact-province">
            <input id="leave-contact-province" {...register('contactProvince')} />
          </FormField>
          <FormField label="เบอร์โทรติดต่อ" htmlFor="leave-contact-phone">
            <input id="leave-contact-phone" {...register('contactPhone')} />
          </FormField>

          <div className={formGridSpan2}>
            <FormField label="เหตุผลการลา" htmlFor="leave-reason" error={errors.reason?.message} required>
              <textarea
                id="leave-reason"
                className={errors.reason ? 'is-invalid' : ''}
                rows={3}
                {...register('reason')}
                aria-invalid={Boolean(errors.reason)}
                aria-describedby={errors.reason ? fieldErrorId('leave-reason') : undefined}
                required
              />
            </FormField>
          </div>
          <div className={`${formGridSpan2} flex justify-end`}>
            <Button type="submit" disabled={saving || startDateInPast} className="max-[720px]:min-h-11 max-[720px]:w-full">
              <Icon name="plus" />
              ส่งคำขอ
            </Button>
          </div>
        </form>
        <p className="mt-3 text-sm text-text-muted">
          อ้างอิง พ.ร.บ. คุ้มครองแรงงาน พ.ศ. 2541: ลากิจธุระจำเป็นไม่น้อยกว่า 3 วันต่อปี,
          ลาป่วยได้รับค่าจ้างไม่เกิน 30 วันทำงานต่อปี และลาพักร้อนไม่น้อยกว่า 6 วันต่อปีหลังทำงานครบ 1 ปี.
          <a href="https://www.mol.go.th/employee/สิทธิตามกฎหมายแรงงาน" target="_blank" rel="noreferrer"> กระทรวงแรงงาน</a>
        </p>
      </Panel>

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
