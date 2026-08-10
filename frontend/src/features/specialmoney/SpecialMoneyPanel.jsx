import { useEffect, useMemo, useState } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm, useWatch } from 'react-hook-form';
import { z } from 'zod';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { FileUploadField } from '../../components/common/FileUploadField.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { formGridSpan2, FormGrid, Panel, RowActions } from '../../components/common/Layout.jsx';
import { SafeForm } from '../../components/common/SafeForm.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import {
  pendingApproverText, specialMoneyStatusLabel as statusInfo, SPECIAL_MONEY_STATUSES,
} from '../../utils/format.js';
import { AttachmentList } from './AttachmentList.jsx';
import { EntitlementPanel } from './EntitlementPanel.jsx';
import { RuleCard } from './RuleCard.jsx';
import { THAI_PROVINCES, isExcludedProvince } from './thaiProvinces.js';
import { TypePicker } from './TypePicker.jsx';
import {
  AID_TYPES,
  TYPE_GROUPS,
  UNIFORM_TYPES,
  estimateAmount,
  evidenceLabel,
  formatThaiMonthYear,
  payrollCutoffInfo,
} from './specialMoneyRules.js';

const TABLE_GRID = 'grid-cols-[minmax(0,1.3fr)_minmax(0,1fr)_minmax(0,0.9fr)_minmax(0,0.7fr)] nav-drawer:min-w-[820px] reflow-cards';

// The policy PDF is bundled at build time (frontend/public/policy/, served as-is at this path —
// see App.jsx's other public/ references for the convention). "ระเบียบสวัสดิการ_8_6_61.pdf" as the
// download filename mirrors the source document's own name (สวัสดิการ_8_6_61.pdf) rather than the
// URL's slug, so a saved copy is recognisable regardless of what the asset happens to be named on
// disk.
const POLICY_PDF_HREF = '/policy/welfare-policy-2561.pdf';
const POLICY_PDF_DOWNLOAD_NAME = 'ระเบียบสวัสดิการ_8_6_61.pdf';

function bangkokDateParts(date = new Date()) {
  return Object.fromEntries(new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Bangkok',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(date).map((part) => [part.type, part.value]));
}

const dateIso = (date = new Date()) => {
  const parts = bangkokDateParts(date);
  return `${parts.year}-${parts.month}-${parts.day}`;
};

const todayIso = () => dateIso();

function currentBuddhistYear() {
  return new Date().getFullYear() + 543;
}

// Bangkok-anchored current year, distinct from currentBuddhistYear()'s local-time
// `new Date().getFullYear()` above. The history period selector below builds date-range
// BOUNDARIES that get sent straight to the backend's `event_date BETWEEN :from AND :to` filter
// (SpecialMoneyRepository#findRequests), so the year it picks must agree with the Bangkok
// calendar the backend filters against -- a UTC-vs-Bangkok mismatch near midnight would otherwise
// silently shift the window by a year. currentBuddhistYear() itself is left untouched: it only
// drives display text on the (unrelated) entitlement panel above, matching this file's existing
// convention there.
function currentBangkokYear() {
  return Number(bangkokDateParts().year);
}

// History table period selector (distinct from reviewQueueWindow's own fixed wide window below --
// see that constant's comment). 'THIS_YEAR' is the default: the EntitlementPanel directly above is
// already year-scoped ("โควตาคงเหลือปี <BE year>"), so defaulting the table to the same calendar
// year lets an employee reconcile "ใช้ไปแล้ว ฿X" against the rows that produced it.
const HISTORY_PERIODS = {
  THIS_YEAR: 'ปีนี้',
  LAST_YEAR: 'ปีที่แล้ว',
  ALL: 'ทั้งหมด',
};

function yearWindow(year) {
  return { from: `${year}-01-01`, to: `${year}-12-31` };
}

function defaultForm(employeeId = '') {
  return {
    employeeId: employeeId ? String(employeeId) : '',
    requestType: '',
    eventDate: todayIso(),
    eventEndDate: todayIso(),
    receiptDate: todayIso(),
    province: '',
    role: '',
    relation: '',
    // UNIFORM_ANNUAL has two filing routes (evaluateUniformAnnual): SELF_BUY (receipt required,
    // amount computed per-piece) or TAILORED (no receipt check, flat amount up to the cap). Kept
    // hardcoded to SELF_BUY before this fix, which made the tailored route the RuleCard itself
    // advertises impossible to actually file.
    uniformMode: 'SELF_BUY',
    shirtCount: '0',
    trouserCount: '0',
    // UNIFORM_PREPROBATION_KIT only: evaluateUniformPreprobationKit adds the belt's cost when
    // `detail.needsBackSupport` parses true. Boolean here; stringified in detailFor().
    needsBackSupport: false,
    requestedAmountInput: '',
    reason: '',
  };
}

function specialMoneyFormSchema({ requireEmployeeId }) {
  return z.object({
    employeeId: z.string(),
    requestType: z.string().min(1, 'กรุณาเลือกประเภทคำขอ'),
    eventDate: z.string(),
    eventEndDate: z.string(),
    receiptDate: z.string(),
    province: z.string(),
    role: z.string(),
    relation: z.string(),
    uniformMode: z.string(),
    shirtCount: z.string(),
    trouserCount: z.string(),
    needsBackSupport: z.boolean().optional(),
    requestedAmountInput: z.string(),
    reason: z.string().min(1, 'กรุณาระบุเหตุผล/รายละเอียด'),
  }).superRefine((data, context) => {
    if (requireEmployeeId && !data.employeeId) {
      context.addIssue({ code: z.ZodIssueCode.custom, path: ['employeeId'], message: 'กรุณาเลือกพนักงาน' });
    }
    if (!data.requestType) return;
    if (['TRAVEL_PER_DIEM', 'TRAVEL_LODGING'].includes(data.requestType)) {
      if (!data.eventDate) context.addIssue({ code: z.ZodIssueCode.custom, path: ['eventDate'], message: 'กรุณาเลือกวันที่เริ่มเดินทาง' });
      if (!data.eventEndDate) context.addIssue({ code: z.ZodIssueCode.custom, path: ['eventEndDate'], message: 'กรุณาเลือกวันที่สิ้นสุด' });
      if (data.eventDate && data.eventEndDate && data.eventEndDate < data.eventDate) {
        context.addIssue({ code: z.ZodIssueCode.custom, path: ['eventEndDate'], message: 'วันที่สิ้นสุดต้องอยู่หลังวันที่เริ่ม' });
      }
      if (!data.province) context.addIssue({ code: z.ZodIssueCode.custom, path: ['province'], message: 'กรุณาเลือกจังหวัดปลายทาง' });
      if (data.requestType === 'TRAVEL_PER_DIEM' && !data.role) {
        context.addIssue({ code: z.ZodIssueCode.custom, path: ['role'], message: 'กรุณาเลือกบทบาท' });
      }
      if (data.requestType === 'TRAVEL_LODGING' && !(Number(data.requestedAmountInput) > 0)) {
        context.addIssue({ code: z.ZodIssueCode.custom, path: ['requestedAmountInput'], message: 'กรุณาระบุจำนวนเงิน' });
      }
    } else if (data.requestType === 'MEDICAL') {
      if (!data.receiptDate) context.addIssue({ code: z.ZodIssueCode.custom, path: ['receiptDate'], message: 'กรุณาเลือกวันที่ใบเสร็จ' });
      if (!(Number(data.requestedAmountInput) > 0)) {
        context.addIssue({ code: z.ZodIssueCode.custom, path: ['requestedAmountInput'], message: 'กรุณาระบุจำนวนเงิน' });
      }
    } else if (AID_TYPES.includes(data.requestType)) {
      if (!data.eventDate) context.addIssue({ code: z.ZodIssueCode.custom, path: ['eventDate'], message: 'กรุณาเลือกวันที่เกิดเหตุการณ์' });
      if (data.requestType === 'AID_FUNERAL' && !data.relation) {
        context.addIssue({ code: z.ZodIssueCode.custom, path: ['relation'], message: 'กรุณาเลือกความสัมพันธ์กับผู้เสียชีวิต' });
      }
    } else if (UNIFORM_TYPES.includes(data.requestType)) {
      if (!data.eventDate) context.addIssue({ code: z.ZodIssueCode.custom, path: ['eventDate'], message: 'กรุณาเลือกวันที่เบิก' });
      // Receipt date is only checked by evaluateUniformAnnual on the SELF_BUY route -- the
      // TAILORED route reads no receipt date at all. Requiring it unconditionally (as before)
      // made the tailored route the RuleCard itself advertises impossible to file.
      if (data.requestType === 'UNIFORM_ANNUAL' && data.uniformMode === 'SELF_BUY' && !data.receiptDate) {
        context.addIssue({ code: z.ZodIssueCode.custom, path: ['receiptDate'], message: 'กรุณาเลือกวันที่ใบเสร็จ' });
      }
      // Piece count is checked in BOTH UNIFORM_ANNUAL modes (evaluateUniformAnnual's own comment:
      // "The 4-piece limit is the document's, not the self-buy route's"), but
      // UNIFORM_PREPROBATION_KIT's evaluator never reads shirtCount/trouserCount at all -- the
      // kit's piece counts are fixed by policy, not chosen by the requester.
      if (data.requestType !== 'UNIFORM_PREPROBATION_KIT') {
        const pieces = Number(data.shirtCount || 0) + Number(data.trouserCount || 0);
        if (pieces <= 0) {
          context.addIssue({ code: z.ZodIssueCode.custom, path: ['shirtCount'], message: 'กรุณาระบุจำนวนชิ้นอย่างน้อย 1 ชิ้น' });
        }
      }
      // Manual amount entry: UNIFORM_NEW_STAFF and UNIFORM_PREPROBATION_KIT always (neither is
      // priced from shirt/trouser rates); UNIFORM_ANNUAL only on the TAILORED route (SELF_BUY's
      // amount is fully computed from the per-piece rates, same as before).
      const needsManualAmount = data.requestType === 'UNIFORM_NEW_STAFF'
        || data.requestType === 'UNIFORM_PREPROBATION_KIT'
        || (data.requestType === 'UNIFORM_ANNUAL' && data.uniformMode === 'TAILORED');
      if (needsManualAmount && !(Number(data.requestedAmountInput) > 0)) {
        context.addIssue({ code: z.ZodIssueCode.custom, path: ['requestedAmountInput'], message: 'กรุณาระบุจำนวนเงิน' });
      }
    } else {
      // TRAINING / OTHER
      if (!data.eventDate) context.addIssue({ code: z.ZodIssueCode.custom, path: ['eventDate'], message: 'กรุณาเลือกวันที่' });
      if (!(Number(data.requestedAmountInput) > 0)) {
        context.addIssue({ code: z.ZodIssueCode.custom, path: ['requestedAmountInput'], message: 'กรุณาระบุจำนวนเงิน' });
      }
    }
  });
}

function formatDate(value) {
  if (!value) return '-';
  const date = new Date(`${value}T00:00:00+07:00`);
  if (Number.isNaN(date.getTime())) return '-';
  return new Intl.DateTimeFormat('th-TH', { dateStyle: 'medium', timeZone: 'Asia/Bangkok' }).format(date);
}

// th-TH, no decimals — kept local (not utils/format.js's `formatMoney`, which uses en-US with 2
// decimals for payroll-style figures) because the estimate readout and request-list amounts here
// have always rendered this way; several tests pin the exact string (e.g. '฿1,200').
function formatMoney(value) {
  return `฿${Number(value || 0).toLocaleString('th-TH')}`;
}

// Submit errors arrive from the real backend as ONE 400 string with every policy violation the
// evaluator found joined by "; " (SpecialMoneyPolicyEvaluator accumulates every violation rather
// than short-circuiting on the first one -- see PolicyDecision's own javadoc -- and
// SpecialMoneyController surfaces them all in one ApiException message). Splitting here is what
// turns that back into a real list instead of one run-on sentence.
function splitViolations(message) {
  if (!message) return ['ส่งคำขอไม่สำเร็จ'];
  const parts = message.split('; ').map((part) => part.trim()).filter(Boolean);
  return parts.length > 0 ? parts : ['ส่งคำขอไม่สำเร็จ'];
}

export function SpecialMoneyPanel({ user, currentEmployee, showToast }) {
  const queryClient = useQueryClient();
  const [confirmState, setConfirmState] = useState(null);
  const [statusFilter, setStatusFilter] = useState('');
  const [historyPeriod, setHistoryPeriod] = useState('THIS_YEAR');
  const [evidenceFile, setEvidenceFile] = useState(null);
  const [submitViolations, setSubmitViolations] = useState([]);

  const employeesQuery = useQuery({
    queryKey: queryKeys.specialMoneyEmployees(),
    queryFn: () => api.specialMoney.employees().then((response) => response.employees || []),
  });
  const employeeOptions = useMemo(() => employeesQuery.data ?? [], [employeesQuery.data]);

  const typesQuery = useQuery({
    queryKey: queryKeys.specialMoneyTypes(),
    queryFn: () => api.specialMoney.types().then((response) => response.types || []),
  });
  const typeOptions = useMemo(() => typesQuery.data ?? [], [typesQuery.data]);

  // Fixed, rule-derived grouping (plan §"Type grouping") — TYPE_GROUPS' order, filtered down to
  // whatever GET /types actually returned, so a type the server doesn't offer never renders an
  // empty radio.
  const groupedTypeOptions = useMemo(() => TYPE_GROUPS.map((group) => ({
    ...group,
    items: group.types
      .map((requestType) => typeOptions.find((item) => item.requestType === requestType))
      .filter(Boolean),
  })), [typeOptions]);

  // The history table's own period window -- MUST be sent explicitly. Without it,
  // `SpecialMoneyController#list` defaults `from`/`to` to the current calendar month
  // (SpecialMoneyService.list():66-68), silently scoping this table to "this month" with nothing
  // on screen saying so (see reviewQueueWindow's comment below for the full story; this is the
  // employee-facing half of the same bug). 'ALL' intentionally reuses the exact 10-years-back /
  // 1-year-forward shape reviewQueueWindow uses, kept as a separate computation so a future change
  // to the CEO queue's window doesn't silently also change this employee-facing one.
  const historyWindow = useMemo(() => {
    if (historyPeriod === 'ALL') {
      // Not literally "everything" -- this deliberately matches reviewQueueWindow's own
      // 10-years-back/1-year-forward shape below, so an aid claim old enough to still be
      // findable in the CEO queue is also findable here. Kept as its own copy (not a shared
      // helper) so a future change to one window doesn't silently also change the other; if
      // they ever need to diverge, this is where that would start. Also unlike THIS_YEAR/
      // LAST_YEAR below, this branch uses local `today.getFullYear()` rather than
      // currentBangkokYear() -- at 10-year width a UTC/Bangkok boundary mismatch is harmless
      // (off by at most one day out of ~4000), so the extra Intl call isn't worth it here.
      const today = new Date();
      return {
        from: dateIso(new Date(today.getFullYear() - 10, today.getMonth(), today.getDate())),
        to: dateIso(new Date(today.getFullYear() + 1, today.getMonth(), today.getDate())),
      };
    }
    const year = historyPeriod === 'LAST_YEAR' ? currentBangkokYear() - 1 : currentBangkokYear();
    return yearWindow(year);
  }, [historyPeriod]);

  // Drives the empty state below (and the once-per-lifetime hint). Derived FROM historyWindow.from
  // itself (not recomputed independently via currentBangkokYear()) so the two literally cannot
  // drift apart: historyWindow is memoized on [historyPeriod] alone, so if this were computed
  // separately, a re-render that crosses a Bangkok New Year with the page left open could show a
  // label year one year ahead of the window the query is still actually using. Reading it back off
  // historyWindow.from makes them the same value by construction.
  const historyPeriodYear = historyPeriod === 'ALL' ? null : Number(historyWindow.from.slice(0, 4));

  // `status: statusFilter` here is deliberately the BARE state value ('' when unset) -- do not
  // "clean up" this to `statusFilter || undefined` to match the queryFn's own normalization two
  // lines down. Under historyPeriod 'ALL' with no status filter, this table's date window becomes
  // IDENTICAL to reviewQueueWindow's below, and queryKeys.specialMoneyRequests() would then produce
  // the exact same cache key for both queries EXCEPT for this one slot ('' here vs `undefined` on
  // reviewQueueWindow, which never carries a `status` property at all) -- that's the only thing
  // keeping the history table's and the CEO review queue's cache entries from colliding in that
  // case. See the "keeps every historyPeriod x status combination distinct from the review queue's
  // key" test below, which guards this directly.
  const filters = useMemo(
    () => ({ status: statusFilter, from: historyWindow.from, to: historyWindow.to }),
    [statusFilter, historyWindow],
  );
  const requestsQuery = useQuery({
    queryKey: queryKeys.specialMoneyRequests(filters),
    queryFn: () => api.specialMoney.list({
      status: statusFilter || undefined,
      from: historyWindow.from,
      to: historyWindow.to,
    }).then((response) => response.requests || []),
  });
  const requests = useMemo(() => requestsQuery.data ?? [], [requestsQuery.data]);
  const loading = requestsQuery.isLoading || requestsQuery.isFetching;

  // CEO review queue -- fetched with an EXPLICIT wide date window, deliberately separate from the
  // history table's query above. `SpecialMoneyController#list` defaults `from`/`to` to the
  // current calendar month when both are omitted (SpecialMoneyService.list():66-68,
  // `effectiveTo.withDayOfMonth(1)`), and `SpecialMoneyRepository#findRequests` filters on
  // `s.event_date BETWEEN :fromDate AND :toDate` -- so a bare `list({status})` call, on the real
  // backend, silently drops any pending request whose event_date falls outside "this month". A
  // UNIFORM_ANNUAL claim (event_date must be in June) would disappear from the queue on 1 July; a
  // funeral claim for a July event filed in August would never appear at all -- and the CEO would
  // just see "ไม่มีคำขอรออนุมัติ", an affirmative false statement. Ten years back covers aid claims
  // (evaluateFixedAid deliberately has no filing deadline); one year forward covers anything
  // event-dated in advance. This does NOT touch the backend's own default -- that stays as-is,
  // this only ensures the frontend never relies on it for "what needs the CEO's attention".
  const reviewQueueWindow = useMemo(() => {
    const today = new Date();
    return {
      from: dateIso(new Date(today.getFullYear() - 10, today.getMonth(), today.getDate())),
      to: dateIso(new Date(today.getFullYear() + 1, today.getMonth(), today.getDate())),
    };
  }, []);
  const reviewQueueQuery = useQuery({
    queryKey: queryKeys.specialMoneyRequests(reviewQueueWindow),
    queryFn: () => api.specialMoney.list({ from: reviewQueueWindow.from, to: reviewQueueWindow.to })
      .then((response) => response.requests || []),
    enabled: user.role === 'ceo',
  });

  useEffect(() => {
    if (employeesQuery.error) showToast('error', employeesQuery.error.message || 'โหลดรายชื่อพนักงานไม่สำเร็จ');
  }, [employeesQuery.error, showToast]);
  useEffect(() => {
    if (requestsQuery.error) showToast('error', requestsQuery.error.message || 'โหลดคำขอเงินสวัสดิการไม่สำเร็จ');
  }, [requestsQuery.error, showToast]);
  useEffect(() => {
    if (typesQuery.error) showToast('error', typesQuery.error.message || 'โหลดประเภทคำขอไม่สำเร็จ');
  }, [typesQuery.error, showToast]);

  const submitEmployeeOptions = useMemo(
    () => employeeOptions.filter((employee) => employee.self || employee.directReport),
    [employeeOptions],
  );
  const hasMultipleSubmitOptions = submitEmployeeOptions.length > 1;
  const formSchema = useMemo(
    () => specialMoneyFormSchema({ requireEmployeeId: hasMultipleSubmitOptions }),
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
    resolver: zodResolver(formSchema),
    defaultValues: defaultForm(currentEmployee?.id || user.employeeId || ''),
    mode: 'onChange',
    reValidateMode: 'onChange',
  });

  const watched = useWatch({ control });
  const selectedEmployeeId = watched.employeeId;
  const requestType = watched.requestType || '';

  // Violations describe the type that was submitted, so they stop being true the moment the
  // employee picks a different one. Without this, three AID_WEDDING violations stay on screen
  // under a MEDICAL form until the next submit -- rules the employee is now being told they
  // broke on a claim they are no longer making.
  useEffect(() => {
    setSubmitViolations([]);
  }, [requestType]);

  useEffect(() => {
    if (!employeesQuery.data) return;
    const currentEmployeeId = getValues('employeeId');
    const nextEmployeeId = currentEmployeeId
      || submitEmployeeOptions.find((employee) => employee.self)?.employeeId
      || submitEmployeeOptions[0]?.employeeId
      || '';
    if (nextEmployeeId === currentEmployeeId) return;
    setValue('employeeId', String(nextEmployeeId), { shouldValidate: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [employeesQuery.data]);

  // The entitlement panel is scoped to whichever employee the form is currently submitting for
  // (self by default) -- "showing entitlement before intent" (plan's layer A).
  const usageEmployeeId = selectedEmployeeId ? Number(selectedEmployeeId) : (currentEmployee?.id || user.employeeId || null);
  const usageQuery = useQuery({
    queryKey: queryKeys.specialMoneyUsage(usageEmployeeId, new Date().getFullYear()),
    queryFn: () => api.specialMoney.usage({ employeeId: usageEmployeeId, year: new Date().getFullYear() }).then((response) => response.usage),
    enabled: Boolean(usageEmployeeId),
  });
  const usage = usageQuery.data;
  const medicalUsedThisYear = Number(usage?.approvedAmountThisYearByType?.MEDICAL || 0);

  const cutoff = useMemo(() => payrollCutoffInfo(), []);

  const estimate = useMemo(() => estimateAmount(requestType, {
    eventDate: watched.eventDate,
    eventEndDate: watched.eventEndDate,
    province: watched.province,
    role: watched.role,
    uniformMode: watched.uniformMode,
    shirtCount: watched.shirtCount,
    trouserCount: watched.trouserCount,
    requestedAmount: watched.requestedAmountInput,
  }, { medicalUsedThisYear, lifetimeCountByType: usage?.approvedCountLifetimeByType || {} }), [
    requestType, watched.eventDate, watched.eventEndDate, watched.province, watched.role, watched.uniformMode,
    watched.shirtCount, watched.trouserCount, watched.requestedAmountInput, medicalUsedThisYear, usage,
  ]);

  // Types whose amount is fully server-computed from other fields (no manual
  // amount input shown) vs. types where the requester types an amount.
  const AUTO_COMPUTED_TYPES = ['TRAVEL_PER_DIEM', ...AID_TYPES, 'UNIFORM_ANNUAL'];
  const finalAmount = AUTO_COMPUTED_TYPES.includes(requestType) ? estimate.amount : Number(watched.requestedAmountInput || 0);

  const selectedTypeMeta = typeOptions.find((item) => item.requestType === requestType);

  const blockingWarning = requestType === 'TRAVEL_PER_DIEM' && watched.province && isExcludedProvince(watched.province);
  // "เบิกชุดฟอร์มปีนี้ไปแล้ว" -- only answerable now that GET /usage carries
  // activeCountThisYearByType (SpecialMoneyUsageDto, added alongside this redesign).
  const uniformAlreadyClaimedThisYear = requestType === 'UNIFORM_ANNUAL'
    && Number(usage?.activeCountThisYearByType?.UNIFORM_ANNUAL || 0) >= 1;

  function invalidateSpecialMoney() {
    return Promise.all([
      // Prefix-invalidate every specialMoney list query, not just the history table's exact
      // `filters` key -- the review queue above is now a SECOND list query with its own (wide)
      // date window, and a mutation (approve/reject/cancel/create) must refresh both.
      queryClient.invalidateQueries({ queryKey: ['specialMoney', 'list'] }),
      queryClient.invalidateQueries({ queryKey: queryKeys.specialMoneyUsage(usageEmployeeId, new Date().getFullYear()) }),
    ]);
  }

  const createMutation = useMutation({
    // Evidence needs a request id, so the file can only go up AFTER create. Chained here rather
    // than left to the user: an evidence-required type cannot be approved without an attachment,
    // so a request filed with the file forgotten is stuck until someone attaches one.
    mutationFn: async (payload) => {
      const created = await api.specialMoney.create(payload).then((response) => response.request);
      if (evidenceFile) {
        await api.specialMoney.addAttachment(created.id, evidenceFile);
      }
      return created;
    },
    onSuccess: () => {
      reset(defaultForm(currentEmployee?.id || user.employeeId || ''));
      setEvidenceFile(null);
      setSubmitViolations([]);
      showToast('success', 'ส่งคำขอเงินสวัสดิการแล้ว');
      invalidateSpecialMoney();
    },
    onError: (error) => {
      const violations = splitViolations(error.message);
      setSubmitViolations(violations);
      showToast('error', violations.length > 1 ? `ส่งคำขอไม่สำเร็จ (${violations.length} ข้อ) — ดูรายละเอียดด้านล่าง` : violations[0]);
    },
  });

  const approveMutation = useMutation({
    mutationFn: (id) => api.specialMoney.approve(id, {}).then((response) => response.request),
    onSuccess: () => {
      showToast('success', 'อนุมัติคำขอแล้ว');
      setConfirmState(null);
      invalidateSpecialMoney();
    },
    onError: (error) => showToast('error', error.message || 'อนุมัติไม่สำเร็จ'),
  });

  const rejectMutation = useMutation({
    mutationFn: ({ id, reviewerNote }) => api.specialMoney.reject(id, { reviewerNote }).then((response) => response.request),
    onSuccess: () => {
      showToast('success', 'ปฏิเสธคำขอแล้ว');
      setConfirmState(null);
      invalidateSpecialMoney();
    },
    onError: (error) => showToast('error', error.message || 'ปฏิเสธไม่สำเร็จ'),
  });

  const cancelMutation = useMutation({
    mutationFn: ({ id, reviewerNote }) => api.specialMoney.cancel(id, { reviewerNote: reviewerNote?.trim() || null }).then((response) => response.request),
    onSuccess: () => {
      showToast('success', 'ยกเลิกคำขอแล้ว');
      setConfirmState(null);
      invalidateSpecialMoney();
    },
    onError: (error) => showToast('error', error.message || 'ยกเลิกไม่สำเร็จ'),
  });

  const saving = createMutation.isPending || approveMutation.isPending || rejectMutation.isPending || cancelMutation.isPending;

  function detailFor(values) {
    if (values.requestType === 'TRAVEL_PER_DIEM') return { destination: 'DOMESTIC', province: values.province, role: values.role };
    if (values.requestType === 'TRAVEL_LODGING') return { destination: 'DOMESTIC', province: values.province };
    if (values.requestType === 'UNIFORM_ANNUAL') {
      // Sends whichever mode the requester actually picked -- this used to hardcode 'SELF_BUY'
      // regardless of the form, which forced every claim down the self-buy branch (May-receipt
      // check included) even when the employee used the company tailor.
      return { uniformMode: values.uniformMode, shirtCount: values.shirtCount, trouserCount: values.trouserCount };
    }
    // evaluateUniformPreprobationKit reads ONLY needsBackSupport -- see DETAIL_KEYS's comment in
    // specialMoneyRules.js. Sent as a STRING ("true"/"false"): `detail` is Map<String,String> on
    // the wire.
    if (values.requestType === 'UNIFORM_PREPROBATION_KIT') {
      return { needsBackSupport: values.needsBackSupport ? 'true' : 'false' };
    }
    if (UNIFORM_TYPES.includes(values.requestType)) {
      return { shirtCount: values.shirtCount, trouserCount: values.trouserCount };
    }
    if (values.requestType === 'AID_FUNERAL') return { relation: values.relation };
    return {};
  }

  function submitSpecialMoney(values) {
    setSubmitViolations([]);
    const isTravel = ['TRAVEL_PER_DIEM', 'TRAVEL_LODGING'].includes(values.requestType);
    const eventDate = values.requestType === 'MEDICAL' ? values.receiptDate : values.eventDate;
    const payload = {
      requestType: values.requestType,
      employeeId: values.employeeId ? Number(values.employeeId) : null,
      eventDate,
      eventEndDate: isTravel ? values.eventEndDate : null,
      // UNIFORM_ANNUAL only sends a receiptDate on the SELF_BUY route -- evaluateUniformAnnual
      // never reads it for TAILORED, and the field itself isn't even shown in that mode (see the
      // form below), so sending the stale default date would misrepresent what was actually filed.
      receiptDate: (values.requestType === 'MEDICAL'
        || (values.requestType === 'UNIFORM_ANNUAL' && values.uniformMode === 'SELF_BUY'))
        ? values.receiptDate
        : null,
      requestedAmount: finalAmount,
      reason: values.reason.trim(),
      detail: detailFor(values),
    };
    createMutation.mutate(payload);
  }

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

  // Welfare is CEO-only, in one stage, for every employee -- there is no manager stage to offer a
  // button for. MANAGER_APPROVED is still accepted because rows written before that rule was
  // introduced can still be sitting in it. Mirrors SpecialMoneyService.approve().
  function canCeoApprove(request) {
    return user.role === 'ceo' && ['SUBMITTED', 'MANAGER_APPROVED'].includes(request.status);
  }
  // Mirrors SpecialMoneyService.cancel(): only the employee or whoever filed on
  // their behalf, and only while SUBMITTED -- no manager-cancel path here,
  // unlike overtime.
  // Mirrors SpecialMoneyService.requireCanAttach(): the requester only, while still SUBMITTED.
  function canAttach(request) {
    const isEmployee = Number(request.employeeId) === Number(user.employeeId);
    const isRequester = request.requestedById != null && Number(request.requestedById) === Number(user.employeeId);
    return request.status === 'SUBMITTED' && (isEmployee || isRequester);
  }

  function canCancel(request) {
    const isEmployee = Number(request.employeeId) === Number(user.employeeId);
    const isRequester = request.requestedById != null && Number(request.requestedById) === Number(user.employeeId);
    return request.status === 'SUBMITTED' && (isEmployee || isRequester);
  }
  function cancel(id) {
    setConfirmState({ kind: 'cancel', id });
  }
  function doCancel(id, reviewerNote) {
    cancelMutation.mutate({ id, reviewerNote });
  }

  const requestTypeError = errors.requestType?.message;

  // [CEO] Review queue -- "the CEO's actual job, first" (plan's IA). Only a CEO ever gets an
  // approve/reject button (canCeoApprove), so this section only renders for that role; HR can
  // still see every request via the history table below (canViewAllSpecialMoney), but never gets
  // a decide affordance -- HR/CEO viewing rights and CEO-only decision rights are deliberately
  // different gates (see this file's canCeoApprove and the CLAUDE.md note on the same split).
  const reviewQueue = (reviewQueueQuery.data ?? []).filter((request) => canCeoApprove(request));

  return (
    <div className="grid grid-cols-1 gap-[18px] min-w-0">
      {user.role === 'ceo' ? (
        <Panel title="รอ CEO อนุมัติ">
          {reviewQueue.length === 0 ? (
            <EmptyState icon="badgeCheck" title="ไม่มีคำขอรออนุมัติ" />
          ) : (
            // Typography + a divider between requests (card-diet rule, plan §"Craft rules") --
            // this used to be its own `rounded-md border` box per request, sitting inside this
            // Panel's own card chrome, the exact "bordered boxes inside a Panel" pattern the plan
            // calls out by name. `divide-y` puts one border line between requests instead.
            <div className="divide-y divide-border-subtle">
              {reviewQueue.map((request) => {
                const typeMeta = typeOptions.find((item) => item.requestType === request.requestType);
                const blockedByEvidence = Boolean(typeMeta?.evidenceRequired) && !request.attachmentCount;
                return (
                  <div key={request.id} className="py-3.5 first:pt-0 last:pb-0">
                    <div className="flex flex-wrap items-start justify-between gap-3">
                      <div className="min-w-0">
                        <div className="text-sm font-extrabold text-text">{typeMeta?.thaiLabel || request.requestType}</div>
                        <div className="mt-0.5 text-xs text-text-muted">
                          {request.employeeName || request.employeeCode} · {formatDate(request.eventDate)}
                        </div>
                        {request.reason ? <p className="m-0 mt-1.5 text-sm text-text">{request.reason}</p> : null}
                      </div>
                      <div className="shrink-0 text-lg font-extrabold tabular-nums text-text">
                        {formatMoney(request.approvedAmount ?? request.requestedAmount)}
                      </div>
                    </div>
                    <AttachmentList requestId={request.id} canUpload={false} showToast={showToast} className="mt-3" />
                    {blockedByEvidence ? (
                      <p className="m-0 mt-2 flex items-center gap-1.5 text-xs font-bold text-warning" role="alert">
                        <Icon name="triangleAlert" size={13} />
                        ยังไม่ได้แนบเอกสาร — อนุมัติไม่ได้
                      </p>
                    ) : null}
                    <RowActions className="mt-3">
                      <Button
                        type="button"
                        variant="success"
                        disabled={saving || blockedByEvidence}
                        title="CEO อนุมัติ"
                        aria-label="CEO อนุมัติ"
                        onClick={() => approve(request.id)}
                      >
                        <Icon name="check" size={14} />
                        อนุมัติ
                      </Button>
                      <Button
                        type="button"
                        variant="danger"
                        disabled={saving}
                        title="ปฏิเสธคำขอ"
                        aria-label="ปฏิเสธคำขอ"
                        onClick={() => reject(request.id)}
                      >
                        <Icon name="close" size={14} />
                        ปฏิเสธ
                      </Button>
                    </RowActions>
                  </div>
                );
              })}
            </div>
          )}
        </Panel>
      ) : null}

      {/* Reference bar -- payroll cutoff countdown + the bundled source policy, per the plan's
          "permanent link to the source PDF" goal. Downloaded filename mirrors the source
          document's own name rather than the URL slug. */}
      <div className="flex flex-wrap items-center gap-2.5 rounded-md border border-border bg-surface px-3.5 py-2.5 text-sm">
        <Icon name="calendar" size={16} />
        <span className="min-w-0">
          ตัดรอบเงินเดือนวันที่ 25 · เหลืออีก <strong className="tabular-nums">{cutoff.daysRemaining}</strong> วัน — คำขอที่อนุมัติหลังวันที่ 25
          จะจ่ายในงวดถัดไป (จ่ายในงวด {formatThaiMonthYear(cutoff.targetMonth)})
        </span>
        <a
          href={POLICY_PDF_HREF}
          target="_blank"
          rel="noopener noreferrer"
          download={POLICY_PDF_DOWNLOAD_NAME}
          className="ml-auto inline-flex shrink-0 items-center gap-1.5 font-bold text-primary mobile:ml-0"
        >
          <Icon name="fileText" size={15} />
          ระเบียบสวัสดิการ (PDF)
        </a>
      </div>

      {/* My entitlement -- "entitlement before intent" (plan's layer A). */}
      <Panel title={`โควตาคงเหลือปี ${currentBuddhistYear()}`}>
        <EntitlementPanel usage={usage} typeOptions={typeOptions} year={currentBuddhistYear()} loading={usageQuery.isLoading} />
      </Panel>

      {/* Request -- group -> type -> RULE CARD -> fields -> estimate -> evidence. */}
      <Panel title="ยื่นคำขอเงินสวัสดิการ">
        <SafeForm onSubmit={handleSubmit(submitSpecialMoney)} noValidate>
          <FormGrid>
            {hasMultipleSubmitOptions ? (
              <FormField label="พนักงาน" htmlFor="smr-employee" error={errors.employeeId?.message}>
                <select id="smr-employee" {...register('employeeId')} required>
                  <option value="">เลือกพนักงาน</option>
                  {submitEmployeeOptions.map((employee) => (
                    <option key={employee.employeeId} value={employee.employeeId}>
                      {employee.employeeName} · {employee.employeeCode}{employee.directReport ? ' · ลูกทีม' : ''}
                    </option>
                  ))}
                </select>
              </FormField>
            ) : (
              <FormField label="พนักงาน" htmlFor="smr-employee-display">
                <input id="smr-employee-display" value={currentEmployee?.nameTh || user.name || '-'} disabled />
              </FormField>
            )}
  
            <div className={formGridSpan2}>
              {/* TypePicker replaces the flat 12-option <select> (plan §"Type grouping"). The
                  hidden input keeps react-hook-form's own registration/validation wiring for
                  'requestType' identical to before -- TypePicker.onSelect writes through
                  setValue(), same as every other controlled field in this form. */}
              <input type="hidden" {...register('requestType')} />
              <span id="smr-type-label" className="mb-1.5 block text-sm font-bold text-text">
                ประเภทคำขอ<span className="field-required text-danger ml-0.5" aria-hidden="true"> *</span>
              </span>
              {/* `role="group"` is required for `aria-labelledby` to mean anything here -- a bare
                  `<div>` has no role that exposes an accessible-name slot, so the label
                  association was previously a no-op for assistive tech despite looking wired up.
                  `aria-describedby` -> the error paragraph below is likewise new: the error existed
                  on screen but was never programmatically associated with the control it
                  describes. */}
              <div
                role="group"
                aria-labelledby="smr-type-label"
                aria-describedby={requestTypeError ? 'smr-type-error' : undefined}
              >
                <TypePicker
                  groups={groupedTypeOptions}
                  value={requestType}
                  onSelect={(value) => setValue('requestType', value, { shouldValidate: true, shouldDirty: true })}
                />
              </div>
              {requestTypeError ? (
                <p id="smr-type-error" className="m-0 mt-1.5 text-xs font-bold text-danger" role="alert">{requestTypeError}</p>
              ) : null}
            </div>
  
            {requestType ? (
              <div className={formGridSpan2}>
                <RuleCard requestType={requestType} typeMeta={selectedTypeMeta} className="border-t border-border pt-3" />
                {uniformAlreadyClaimedThisYear ? (
                  <p className="m-0 mt-2 flex items-center gap-1.5 text-xs font-bold text-warning" role="alert">
                    <Icon name="triangleAlert" size={13} />
                    เบิกชุดฟอร์มประจำปีนี้ไปแล้ว — ปีนี้เบิกซ้ำไม่ได้
                  </p>
                ) : null}
              </div>
            ) : null}
  
            {['TRAVEL_PER_DIEM', 'TRAVEL_LODGING'].includes(requestType) ? (
              <>
                <FormField label="วันที่เริ่มเดินทาง" htmlFor="smr-event-date" error={errors.eventDate?.message} required>
                  <input id="smr-event-date" type="date" {...register('eventDate')} required />
                </FormField>
                <FormField label="วันที่สิ้นสุด" htmlFor="smr-event-end-date" error={errors.eventEndDate?.message} required>
                  <input id="smr-event-end-date" type="date" {...register('eventEndDate')} required />
                </FormField>
                <FormField
                  label="จังหวัดปลายทาง"
                  htmlFor="smr-province"
                  error={errors.province?.message}
                  hint={blockingWarning ? undefined : 'ต้องเลือกจากรายการ — ระบบตรวจสอบจังหวัดยกเว้นด้วยชื่อที่ตรงกันเท่านั้น'}
                  required
                >
                  <select id="smr-province" {...register('province')} required>
                    <option value="">เลือกจังหวัด</option>
                    {THAI_PROVINCES.map((province) => (
                      <option key={province} value={province}>{province}</option>
                    ))}
                  </select>
                </FormField>
                {requestType === 'TRAVEL_PER_DIEM' ? (
                  <FormField label="บทบาท" htmlFor="smr-role" error={errors.role?.message} required>
                    <select id="smr-role" {...register('role')} required>
                      <option value="">เลือกบทบาท</option>
                      <option value="driver">คนขับ</option>
                      <option value="loader">ผู้ช่วย/ยกของ</option>
                    </select>
                  </FormField>
                ) : (
                  <FormField label="จำนวนเงินค่าที่พัก" htmlFor="smr-amount" error={errors.requestedAmountInput?.message} required>
                    <input id="smr-amount" type="number" min="0" step="1" {...register('requestedAmountInput')} required />
                  </FormField>
                )}
              </>
            ) : null}
  
            {requestType === 'MEDICAL' ? (
              <>
                <FormField label="วันที่ใบเสร็จ" htmlFor="smr-receipt-date" error={errors.receiptDate?.message} required>
                  <input id="smr-receipt-date" type="date" {...register('receiptDate')} required />
                </FormField>
                <FormField label="จำนวนเงินที่ขอเบิก" htmlFor="smr-amount" error={errors.requestedAmountInput?.message} required>
                  <input id="smr-amount" type="number" min="0" step="1" {...register('requestedAmountInput')} required />
                </FormField>
              </>
            ) : null}
  
            {AID_TYPES.includes(requestType) ? (
              <>
                <FormField label="วันที่เกิดเหตุการณ์" htmlFor="smr-event-date" error={errors.eventDate?.message} required>
                  <input id="smr-event-date" type="date" {...register('eventDate')} required />
                </FormField>
                {requestType === 'AID_FUNERAL' ? (
                  <FormField label="ความสัมพันธ์กับผู้เสียชีวิต" htmlFor="smr-relation" error={errors.relation?.message} required>
                    <select id="smr-relation" {...register('relation')} required>
                      <option value="">เลือกความสัมพันธ์</option>
                      <option value="parent">บิดา/มารดา</option>
                      <option value="spouse">คู่สมรส</option>
                      <option value="child">บุตร</option>
                    </select>
                  </FormField>
                ) : null}
              </>
            ) : null}
  
            {UNIFORM_TYPES.includes(requestType) ? (
              <>
                {requestType === 'UNIFORM_ANNUAL' ? (
                  <FormField label="วิธีเบิก" htmlFor="smr-uniform-mode" required>
                    <select id="smr-uniform-mode" {...register('uniformMode')}>
                      <option value="SELF_BUY">ซื้อเอง (ใบเสร็จเดือนพฤษภาคม)</option>
                      <option value="TAILORED">ตัดชุดกับร้านที่บริษัทกำหนด</option>
                    </select>
                  </FormField>
                ) : null}
                <FormField label="วันที่เบิก" htmlFor="smr-event-date" error={errors.eventDate?.message} required>
                  <input id="smr-event-date" type="date" {...register('eventDate')} required />
                </FormField>
                {requestType === 'UNIFORM_ANNUAL' && watched.uniformMode !== 'TAILORED' ? (
                  <FormField label="วันที่ใบเสร็จ" htmlFor="smr-receipt-date" error={errors.receiptDate?.message} required>
                    <input id="smr-receipt-date" type="date" {...register('receiptDate')} required />
                  </FormField>
                ) : null}
                {/* Piece count applies in BOTH UNIFORM_ANNUAL modes (evaluateUniformAnnual checks
                    it regardless of uniformMode), but UNIFORM_PREPROBATION_KIT's piece counts are
                    fixed by policy -- its evaluator never reads shirtCount/trouserCount. */}
                {requestType !== 'UNIFORM_PREPROBATION_KIT' ? (
                  <>
                    <FormField label="จำนวนเสื้อ (ชิ้น)" htmlFor="smr-shirt-count" error={errors.shirtCount?.message}>
                      <input id="smr-shirt-count" type="number" min="0" step="1" {...register('shirtCount')} />
                    </FormField>
                    <FormField label="จำนวนกางเกง (ชิ้น)" htmlFor="smr-trouser-count" error={errors.trouserCount?.message}>
                      <input id="smr-trouser-count" type="number" min="0" step="1" {...register('trouserCount')} />
                    </FormField>
                  </>
                ) : null}
                {requestType === 'UNIFORM_PREPROBATION_KIT' ? (
                  <div className={formGridSpan2}>
                    <label className="flex cursor-pointer items-center gap-2 text-sm font-medium">
                      <input type="checkbox" className="h-4 w-4 shrink-0" {...register('needsBackSupport')} />
                      ต้องการสายรัดหลัง (กรณีของเดิมไม่เพียงพอ)
                    </label>
                  </div>
                ) : null}
                {requestType === 'UNIFORM_NEW_STAFF'
                  || requestType === 'UNIFORM_PREPROBATION_KIT'
                  || (requestType === 'UNIFORM_ANNUAL' && watched.uniformMode === 'TAILORED') ? (
                  <FormField
                    label={requestType === 'UNIFORM_ANNUAL' ? 'จำนวนเงินที่ขอเบิก (ตามใบแจ้งหนี้ร้านตัด)' : 'จำนวนเงินที่ขอเบิก'}
                    htmlFor="smr-amount"
                    error={errors.requestedAmountInput?.message}
                    required
                  >
                    <input id="smr-amount" type="number" min="0" step="1" {...register('requestedAmountInput')} required />
                  </FormField>
                ) : null}
              </>
            ) : null}
  
            {(requestType === 'TRAINING' || requestType === 'OTHER') ? (
              <>
                <FormField label="วันที่" htmlFor="smr-event-date" error={errors.eventDate?.message} required>
                  <input id="smr-event-date" type="date" {...register('eventDate')} required />
                </FormField>
                <FormField label="จำนวนเงินที่ขอเบิก" htmlFor="smr-amount" error={errors.requestedAmountInput?.message} required>
                  <input id="smr-amount" type="number" min="0" step="1" {...register('requestedAmountInput')} required />
                </FormField>
              </>
            ) : null}
  
            {requestType ? (
              <div className={formGridSpan2}>
                {/* The tax chip lives in RuleCard's "ภาษี" row now (rendered just above, once per
                    type) -- it used to also render here, which meant it showed twice on screen for
                    the same type. */}
                <div className="border border-border rounded-md p-3.5">
                  <div className="text-2xl font-extrabold tabular-nums" data-testid="smr-computed-amount">{formatMoney(finalAmount)}</div>
                  {estimate.working ? <div className="text-xs text-icon-muted mt-1">{estimate.working}</div> : null}
                  <div className="text-xs text-icon-muted mt-0.5">
                    จ่ายในงวด {formatThaiMonthYear(cutoff.targetMonth)}
                  </div>
                </div>
                {estimate.warnings.map((warning) => (
                  <p key={warning} className="m-0 mt-2 text-xs font-bold text-danger" role="alert">{warning}</p>
                ))}
              </div>
            ) : null}
  
            <div className={formGridSpan2}>
              <FormField label="เหตุผล / รายละเอียด" htmlFor="smr-reason" error={errors.reason?.message} required>
                <textarea id="smr-reason" rows={3} {...register('reason')} required />
              </FormField>
            </div>
  
            {requestType ? (
              <div className={formGridSpan2}>
                <FormField
                  label="หลักฐานประกอบ"
                  htmlFor="smr-evidence"
                  // Type-aware: TRAVEL_PER_DIEM is evidenceRequired=false (the only such type -- see
                  // RuleCard's own "ไม่บังคับแนบหลักฐาน" row directly above this field), so telling its
                  // requester the request "จะอนุมัติไม่ได้จนกว่าจะแนบเอกสาร" was a direct contradiction
                  // of what the rule card two elements up on the same screen already said correctly.
                  hint={selectedTypeMeta?.evidenceRequired === false
                    ? `${evidenceLabel(requestType)} (ไม่บังคับ) — ไฟล์ PDF, JPG หรือ PNG`
                    : `${evidenceLabel(requestType)} — ไฟล์ PDF, JPG หรือ PNG. คำขอประเภทที่ต้องมีหลักฐานจะอนุมัติไม่ได้จนกว่าจะแนบเอกสาร`}
                >
                  <FileUploadField
                    id="smr-evidence"
                    accept="application/pdf,image/jpeg,image/png,.pdf,.jpg,.jpeg,.png"
                    onChange={(event) => setEvidenceFile(event.target.files?.[0] || null)}
                  />
                </FormField>
              </div>
            ) : null}
  
            {/* Submit errors arrive as ONE 400 string with every violation joined by "; " -- split
                into a real list instead of one run-on sentence (plan §"Backend contract"). */}
            {submitViolations.length > 0 ? (
              <div className={formGridSpan2}>
                <div className="rounded-md border border-danger-border bg-danger-bg p-3" role="alert">
                  {/* text-danger-DARK, not text-danger. `--color-danger` (#dc2626) on
                      `--color-danger-bg` (#fee2e2) measures 3.95:1 and fails WCAG AA's 4.5:1 —
                      12px bold is below the large-text threshold (>=18.66px bold), so it gets no
                      relief there. `--color-danger-dark` (#b91c1c) measures 5.30:1 on the same
                      tint and is the app's established danger-on-danger-bg pairing, asserted in
                      designTokens.test.js and already applied on the sales side by
                      fix/ui-contrast-tokens (see TicketCreateModal.jsx:300). These two callouts
                      were the remaining self-service stragglers. */}
                  <p className="m-0 text-xs font-bold text-danger-dark">ส่งคำขอไม่สำเร็จ:</p>
                  <ul className="m-0 mt-1.5 list-disc pl-4 text-sm text-text">
                    {/* Keyed by index, not by the string: the evaluator can emit the same violation
                        twice (e.g. one per uniform piece type), and duplicate keys would collide. */}
                    {submitViolations.map((violation, index) => (
                      <li key={index}>{violation}</li>
                    ))}
                  </ul>
                </div>
              </div>
            ) : null}
  
            <RowActions className={formGridSpan2}>
              <Button type="submit" disabled={saving || !requestType || blockingWarning} className="mobile:min-h-11 mobile:w-full">
                <Icon name="plus" />
                ส่งคำขอ
              </Button>
            </RowActions>
          </FormGrid>
        </SafeForm>
      </Panel>

      {/* My requests -- history; rejected rows carry note + rule + evidence. */}
      <Panel
        title="คำขอเงินสวัสดิการ"
        actions={(
          <div className="flex flex-wrap items-center gap-2">
            {/* w-auto on both selects: legacy styles.css sets `select { width: 100% }` (its
                width:100%, not a flex-basis override, still wins the cascade over a bare `flex`
                utility per this file's Tailwind-vs-legacy note elsewhere), so two selects
                sharing this row would each claim the full row width and stack vertically instead
                of sitting side by side -- w-auto is a Tailwind utility in a later layer, so it
                wins and restores content-based sizing. Browser-verified: both selects render
                side by side at 1280px with this class; without it they stack and the header
                grows ~40px -> ~88px tall. */}
            {/* Period selector -- makes the table's date window explicit and user-controllable
                instead of silently defaulting (see historyWindow's comment above). Defaults to
                'THIS_YEAR' to match the entitlement panel above. */}
            <select
              value={historyPeriod}
              onChange={(event) => setHistoryPeriod(event.target.value)}
              aria-label="กรองตามช่วงเวลา"
              className="w-auto"
            >
              {Object.entries(HISTORY_PERIODS).map(([value, label]) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </select>
            {/* Options are derived from the canonical label map rather than
                duplicated inline — the inline copy is how this filter kept saying
                'รอผู้จัดการ' after the manager stage was removed. */}
            <select
              value={statusFilter}
              onChange={(event) => setStatusFilter(event.target.value)}
              aria-label="กรองตามสถานะ"
              className="w-auto"
            >
              <option value="">ทุกสถานะ</option>
              {SPECIAL_MONEY_STATUSES.map((status) => (
                <option key={status} value={status}>{statusInfo(status).label}</option>
              ))}
            </select>
          </div>
        )}
      >
        {/*
          The wide grid needs its OWN horizontal scroll region, or the page gets one.

          `TABLE_GRID` carries `nav-drawer:min-w-[820px]`, i.e. a hard 820px floor for the whole
          721–1040px band (below 721px `reflow-cards` turns these rows into cards, so the floor
          stops applying). This Panel is not `flush`, so it has no `overflow-hidden`, and nothing
          else between here and `.content-scroll` scrolls sideways — so at tablet widths the table
          simply pushed the page out. Measured at 768px before this wrapper: the grid rendered
          820px wide and ran 137px past the content edge, giving `.content-scroll` 105px of
          horizontal overflow, so the whole page slid sideways under the user's thumb.

          `overflow-x-auto` moves that scroll onto the table itself, which is what the wide-table
          pattern elsewhere in the app does. Note the alternative — letting a `flush` Panel clip it
          with `overflow-hidden` — is worse, not better: it trades a scrolling page for columns
          that are silently unreachable.
        */}
        <div className="overflow-x-auto">
          <div className={`${TABLE_GRID} table-head`}>
            <span>ประเภท / รายละเอียด</span>
            <span>สถานะ</span>
            <span>งวดจ่าย</span>
            <span />
          </div>
        {loading ? (
          <EmptyState icon="badgeDollar" title="กำลังโหลดคำขอ" />
        ) : requests.length === 0 ? (
          <EmptyState
            icon="badgeDollar"
            // The period is named in the title itself -- this table used to say the bare
            // "ยังไม่มีคำขอเงินสวัสดิการ" regardless of which window (silently "this month" on the
            // real backend) produced zero rows, which is exactly the false-negative this whole
            // change exists to fix.
            title={historyPeriodYear
              ? `ยังไม่มีคำขอเงินสวัสดิการในปี ${historyPeriodYear + 543}`
              : 'ยังไม่มีคำขอเงินสวัสดิการ'}
            description={historyPeriod === 'ALL'
              ? 'ยื่นคำขอใหม่ด้านบน'
              // Once-per-lifetime types (ONCE_PER_LIFETIME_TYPES) are reported from a LIFETIME
              // count on the entitlement panel above -- under a year-scoped default it can say
              // "ใช้สิทธิแล้ว" for a claim made in an earlier year, while this table shows nothing
              // for the current one. This line is unconditional advice shown on EVERY year-scoped
              // empty result, not a targeted guard -- it does not check usage.
              // approvedCountLifetimeByType (the API has no per-year breakdown of it to check
              // against). It is phrased as a suggestion ("หากเคย...ลองเปลี่ยน...") specifically so
              // it is never literally false when shown for an unrelated reason (e.g. an employee
              // who has simply never filed anything).
              : 'ยื่นคำขอใหม่ด้านบน หรือหากเคยยื่นคำขอในปีก่อนหน้า ลองเปลี่ยนช่วงเวลาเป็น "ทั้งหมด"'}
          />
        ) : requests.map((request) => {
          const status = statusInfo(request.status);
          const typeMeta = typeOptions.find((item) => item.requestType === request.requestType);
          const attachable = canAttach(request);
          const isRejected = request.status === 'REJECTED';
          const showAttachments = !isRejected && (attachable || Number(request.attachmentCount || 0) > 0);
          const isPending = request.status === 'SUBMITTED' || request.status === 'MANAGER_APPROVED';
          const pendingApproverNote = isPending
            ? pendingApproverText(request.pendingApproverRole, request.pendingApproverName)
            : null;
          return (
            <div className={`${TABLE_GRID} data-row`} key={request.id}>
              <span data-label="ประเภท / รายละเอียด" className="mobile:order-1">
                <strong>{typeMeta?.thaiLabel || request.requestType}</strong>
                <small>
                  {request.employeeName || request.employeeCode} · {formatDate(request.eventDate)} ·{' '}
                  <span className="tabular-nums">{formatMoney(request.approvedAmount ?? request.requestedAmount)}</span>
                </small>
              </span>
              <span data-label="สถานะ" className="mobile:order-2">
                <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
                {/* Only when a NAME was resolved. The role-only note was correct when the badge
                    read a generic pending label, but specialMoneyStatusLabel now names the role
                    itself ("รอ CEO อนุมัติ" -- welfare has been single-stage CEO-only since #482),
                    and the note then merely repeated it: two lines for one fact. A name is the
                    note's only remaining content -- "CEO (คุณราม)" tells you who to chase, which
                    the badge cannot. Same reasoning as OvertimePanel's ขั้นอนุมัติ column. */}
                {request.pendingApproverName && pendingApproverNote
                  ? <small className="text-text-muted">{pendingApproverNote}</small>
                  : null}
                {typeMeta?.evidenceRequired && request.status === 'SUBMITTED' && !request.attachmentCount ? (
                  <small className="text-warning">ยังไม่ได้แนบเอกสาร — อนุมัติไม่ได้</small>
                ) : null}
              </span>
              <span data-label="งวดจ่าย" className="mobile:order-3">
                {request.payrollMonth ? `งวด ${formatThaiMonthYear(new Date(`${request.payrollMonth}T00:00:00`))}` : 'ยังไม่กำหนด'}
              </span>
              <RowActions className="mobile:order-4 mobile:flex-wrap">
                {canCancel(request) ? (
                  <Button type="button" variant="icon" title="ยกเลิกคำขอ" aria-label="ยกเลิกคำขอ" disabled={saving} onClick={() => cancel(request.id)}>
                    <Icon name="close" size={14} />
                  </Button>
                ) : null}
              </RowActions>
              {/* Rejection (plan §"Rejection"): reviewerNote in a real callout (never truncated
                  by `.data-row`'s ellipsis rule -- this sits in a plain <div>, not a
                  `.data-row > span`), then the same rule card, then the attached evidence. This
                  extra child stays a direct child of THIS .data-row (not a sibling wrapper), so
                  `.reflow-cards.data-row + .data-row`'s adjacency-based row spacing keeps working
                  in the ≤720px card layout. */}
              {isRejected ? (
                <div style={{ gridColumn: '1 / -1' }} className="mt-1 flex flex-col gap-2.5 border-t border-border-subtle pt-3">
                  {request.reviewerNote ? (
                    <div className="flex items-start gap-2.5 rounded-md border border-danger-border bg-danger-bg p-3" role="alert">
                      {/* Same danger-on-danger-bg contrast fix as the callout above. */}
                      <Icon name="triangleAlert" size={16} className="mt-0.5 shrink-0 text-danger-dark" />
                      <div className="min-w-0">
                        <div className="text-xs font-bold text-danger-dark">เหตุผลที่ CEO ปฏิเสธ</div>
                        <p className="m-0 mt-0.5 text-sm text-text">{request.reviewerNote}</p>
                      </div>
                    </div>
                  ) : null}
                  <RuleCard requestType={request.requestType} typeMeta={typeMeta} />
                  <AttachmentList requestId={request.id} canUpload={false} showToast={showToast} />
                </div>
              ) : showAttachments ? (
                <div style={{ gridColumn: '1 / -1' }} className="mt-1 border-t border-border-subtle pt-3">
                  <AttachmentList requestId={request.id} canUpload={attachable} showToast={showToast} />
                </div>
              ) : null}
            </div>
          );
        })}
        </div>
      </Panel>

      <ConfirmDialog
        open={confirmState?.kind === 'approve'}
        title="ยืนยันการอนุมัติ"
        message="ยืนยันการอนุมัติคำขอเงินสวัสดิการนี้?"
        confirmLabel="อนุมัติ"
        busy={saving}
        onConfirm={confirmApprove}
        onCancel={() => setConfirmState(null)}
      />
      <ConfirmDialog
        open={confirmState?.kind === 'reject'}
        title="ปฏิเสธคำขอ"
        message='ยืนยันการปฏิเสธคำขอนี้? สถานะจะเปลี่ยนเป็น "ปฏิเสธ"'
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
        title="ยกเลิกคำขอ"
        message='ยืนยันการยกเลิกคำขอนี้? สถานะจะเปลี่ยนเป็น "ยกเลิก" ถาวร'
        confirmLabel="ยกเลิกคำขอ"
        tone="danger"
        busy={saving}
        requireReason
        optionalReason
        reasonLabel="หมายเหตุการยกเลิก (ถ้ามี)"
        onConfirm={(reason) => doCancel(confirmState.id, reason)}
        onCancel={() => setConfirmState(null)}
      />
    </div>
  );
}
