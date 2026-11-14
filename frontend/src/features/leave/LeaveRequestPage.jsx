import { useEffect, useMemo, useRef, useState } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import imageCompression from 'browser-image-compression';
import { useForm, useWatch } from 'react-hook-form';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { z } from 'zod';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { hasPermission } from '../../app/permissions.js';
import { Button } from '../../components/common/Button.jsx';
import { CollapsibleSection } from '../../components/common/CollapsibleSection.jsx';
import { FileUploadField } from '../../components/common/FileUploadField.jsx';
import { FormField, fieldErrorId } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { formGridSpan2, Panel, PageStack } from '../../components/common/Layout.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { QuotaBar } from '../../components/common/QuotaBar.jsx';
import { SafeForm } from '../../components/common/SafeForm.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { UpcomingHolidays } from '../../components/common/UpcomingHolidays.jsx';
import { addDaysIso, formatThaiDate } from '../../utils/format.js';
import { EVERYDAY_LEAVE_TYPE_CODES } from './MyLeaveTab.jsx';
import { LeaveRulePanel, LeaveRuleWarningList } from './LeaveRulePanel.jsx';
import { LEAVE_PURPOSE_OPTIONS } from './leaveRequestTable.jsx';
import { formatDate, formatDays, todayIso, yearFrom } from './leaveFormatting.js';
import { ThaiDateController } from '../../components/common/ThaiDateInput.jsx';

// Leave-request composer. The same gate chain as submit runs as a dry-run
// (POST /api/leave/preview, LeaveService#preview) at three checkpoints, so rules are shown before
// an employee fills in every field: eligibility when a type is picked (step 1), a debounced QUICK
// check while dates/details are entered (step 2, skips the department-coverage fan-out), and one
// FULL re-check before submit (step 3, the only point department coverage runs). Every rule
// threshold shown is the backend's verdict; this file only decides WHERE and WHEN to ask (see
// CLAUDE.md: do not reimplement the 17 rules in JavaScript).
//
// Route: `/leave/new`, deep-linkable with `?type=&start=&end=` (the retry link of a rejected
// request, and the auto-rejection notification) and `?returnTab=` (which LeaveSurfacePage.jsx tab
// to return to after cancel/back/submit).

const LEAVE_START_PAST_MESSAGE = 'วันที่เริ่มลาต้องไม่ก่อนวันนี้';
const LEAVE_START_PAST_PERSONAL_MESSAGE = 'วันที่เริ่มลาต้องไม่ก่อนวันนี้ หากเป็นลากิจฉุกเฉิน ให้เลือก “ลากิจฉุกเฉิน” ด้านล่างก่อน';
const LEAVE_TIME_ORDER_MESSAGE = 'เวลาสิ้นสุดต้องหลังเวลาเริ่ม';

// SICK, and only SICK, is exempt from the "start date must not be before today" block below
// ("ลาป่วยย้อนหลังได้ตลอด"): there is no retroactive window, unlike OT, and employees filing for
// themselves get it too (paper F-HR-020 sick forms reach HR days late).
//
// The backend's own §5.1 gates still surface in the preview at steps 2 and 3
// (step2FieldTarget routes the SICK certificate codes to the attachment field):
// LeaveService#sickCertificateRuleOutcome returns SICK_CERTIFICATE_WINDOW when an attachment is
// present and today is more than certificate_filing_window_days working days after startDate. That
// gate is a WARNING (WARN_UNPAID_ALL): a late sick leave submits, carries the warning, and is
// unpaid if approved.
//
// SICK-only is a policy choice, not a backend limit. advance_notice_days is 0 for SICK,
// LEAVE_WITHOUT_PAY, MATERNITY, MILITARY and ORDINATION alike, so the backend would accept a
// backdated request for any of those.
const BACKDATE_ALLOWED_LEAVE_TYPE_CODES = new Set(['SICK']);

// "ลากิจฉุกเฉิน" (PERSONAL with `requestedAsEmergency === true`) gets the same exemption as SICK:
// no retroactive window. The backend already accepts it: LeaveService#autoRejectNote's §5.2
// emergency branch treats a backdated PERSONAL request as an emergency exception while the
// monthly tolerance (3) lasts; past that it is EMERGENCY_TOLERANCE_EXHAUSTED (WARN_UNPAID_ALL,
// submits unpaid), not a rejection.
//
// A backdated PERSONAL request without the box ticked stays blocked here. The backend would only
// raise ADVANCE_NOTICE (a WARN_UNPAID_* outcome), so this block is UX: it steers the employee to
// tick the box when that is what they mean.
const EMERGENCY_BACKDATE_LEAVE_TYPE_CODES = new Set(['PERSONAL']);

// Single source of truth for the past-start-date rule -- both the zod schema and the render-time
// `startDateInPast` derivation below call THIS, so the exemption cannot drift between the two.
function isStartDateBlockedAsPast(leaveTypeCode, startDate, minStartDate, requestedAsEmergency) {
  if (!startDate || !minStartDate) return false;
  if (BACKDATE_ALLOWED_LEAVE_TYPE_CODES.has(leaveTypeCode)) return false;
  if (EMERGENCY_BACKDATE_LEAVE_TYPE_CODES.has(leaveTypeCode) && requestedAsEmergency === true) return false;
  return startDate < minStartDate;
}

// Paired with isStartDateBlockedAsPast above -- PERSONAL gets its own message pointing at the ลากิจฉุกเฉิน
// checkbox as the way out, since "just wait" is not the right instruction for a type that DOES have an
// exemption, unlike every other still-blocked type.
function pastStartMessage(leaveTypeCode) {
  return EMERGENCY_BACKDATE_LEAVE_TYPE_CODES.has(leaveTypeCode) ? LEAVE_START_PAST_PERSONAL_MESSAGE : LEAVE_START_PAST_MESSAGE;
}

// Step 1's secondary disclosure leads each rare type with its own gating condition -- this only echoes each
// type's OWN metadata columns, never a computed eligibility DECISION about whether THIS employee clears them
// -- that verdict comes only from the real preview call, never from this text.
function typeGatingConditionText(leaveType) {
  if (!leaveType) return null;
  const parts = [];
  if (Number(leaveType.minServiceMonths) > 0) {
    parts.push(`อายุงานอย่างน้อย ${leaveType.minServiceMonths} เดือน`);
  }
  if (leaveType.oncePerEmployment) {
    parts.push('ใช้ได้ครั้งเดียวตลอดการทำงาน');
  }
  if (leaveType.requiresAttachment) {
    parts.push('ต้องแนบเอกสาร');
  }
  return parts.length > 0 ? parts.join(' · ') : 'ไม่มีเงื่อนไขพิเศษ';
}

// Maps a blocking LeaveRuleOutcome to the step-2 field it attaches beneath (per-field placement,
// not one banner). Only one outcome blocks at a time (LeaveService#preview returns the FIRST gate
// hit, same as #submit).
//
// The SICK_* codes and EMERGENCY_TOLERANCE_EXHAUSTED are WARN gates now (LeaveRuleCode
// enforcement()), so `preview.blocking` never carries them and those branches are not reached
// today. They stay because the contract is "map ANY code to its field" and warnings render as one
// list, not per field.
function step2FieldTarget(code) {
  if (['SICK_CERTIFICATE_WINDOW', 'SICK_CERTIFICATE_REQUIRED', 'SICK_NO_CERT_TOLERANCE_EXHAUSTED'].includes(code)) {
    return 'attachment';
  }
  if (code === 'EMERGENCY_TOLERANCE_EXHAUSTED') return 'emergency';
  return 'dates';
}

function defaultForm({
  employeeId = '', leaveTypeCode = '', startDate, endDate,
}) {
  const today = todayIso();
  const start = startDate || today;
  return {
    employeeId: employeeId ? String(employeeId) : '',
    leaveTypeCode: leaveTypeCode || '',
    startDate: start,
    endDate: endDate || start,
    reason: '',
    // Partial-day span: ticked by default ("all day" is the common case); unticking reveals
    // startTime/endTime, which may span more than one calendar day. See
    // handleAllDayToggle/createLeaveFormSchema below.
    allDay: true,
    startTime: '',
    endTime: '',
    contactHouseNo: '',
    contactSubdistrict: '',
    contactDistrict: '',
    contactProvince: '',
    contactPhone: '',
    purposeCode: '',
    requestedAsEmergency: false,
    // §5.3.5 pool choice (V161): defaults to carry-in first -- see QuotaPoolPreferenceField's own comment on why,
    // and LeaveQuotaPoolPreference.orDefault on the backend for the same default.
    quotaPoolPreference: 'CARRIED_IN_FIRST',
  };
}

function createLeaveFormSchema({ requireEmployeeId, minStartDate }) {
  return z.object({
    employeeId: z.string(),
    leaveTypeCode: z.string().min(1, 'กรุณาเลือกประเภทการลา'),
    startDate: z.string().min(1, 'กรุณาเลือกวันที่เริ่ม'),
    endDate: z.string().min(1, 'กรุณาเลือกวันที่สิ้นสุด'),
    reason: z.string().min(1, 'กรุณาระบุเหตุผลการลา'),
    allDay: z.boolean().optional(),
    startTime: z.string().optional(),
    endTime: z.string().optional(),
    contactHouseNo: z.string().optional(),
    contactSubdistrict: z.string().optional(),
    contactDistrict: z.string().optional(),
    contactProvince: z.string().optional(),
    contactPhone: z.string().optional(),
    purposeCode: z.string().optional(),
    requestedAsEmergency: z.boolean().optional(),
    // §5.3.5 pool choice: always one of the two enum names once touched, but left as a bare optional string rather
    // than z.enum.
    quotaPoolPreference: z.string().optional(),
  }).superRefine((data, context) => {
    if (requireEmployeeId && !data.employeeId) {
      context.addIssue({ code: z.ZodIssueCode.custom, path: ['employeeId'], message: 'กรุณาเลือกพนักงาน' });
    }
    if (isStartDateBlockedAsPast(data.leaveTypeCode, data.startDate, minStartDate, data.requestedAsEmergency)) {
      context.addIssue({ code: z.ZodIssueCode.custom, path: ['startDate'], message: pastStartMessage(data.leaveTypeCode) });
    }
    // Partial-day span: times are required only when `allDay === false` (the default is `true`). The
    // end-after-start ordering check applies only when start and end fall on the SAME date; a
    // multi-day span is guarded by the backend's (end_date, end_time) > (start_date, start_time) check
    // (LeaveService#validateSubDayTimes, chk_leave_time_order).
    if (data.allDay === false) {
      if (!data.startTime) {
        context.addIssue({ code: z.ZodIssueCode.custom, path: ['startTime'], message: 'กรุณาระบุเวลาเริ่ม' });
      }
      if (!data.endTime) {
        context.addIssue({ code: z.ZodIssueCode.custom, path: ['endTime'], message: 'กรุณาระบุเวลาสิ้นสุด' });
      }
      if (data.startDate === data.endDate && data.startTime && data.endTime && data.endTime <= data.startTime) {
        context.addIssue({ code: z.ZodIssueCode.custom, path: ['endTime'], message: LEAVE_TIME_ORDER_MESSAGE });
      }
    }
  });
}

/** Debounces `value` by `delayMs`; used only for the step-2 QUICK preview's input. */
function useDebouncedValue(value, delayMs) {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);
  return debounced;
}

function StepHeading({ innerRef, step, total, title }) {
  return (
    <h2 ref={innerRef} tabIndex={-1} className="m-0 text-lg font-bold text-text focus-visible:outline-none">
      <span className="mr-2 text-text-muted">ขั้นตอนที่ {step}/{total}</span>
      {title}
    </h2>
  );
}

// A preview call can fail outright instead of returning a LeaveRuleOutcome -- most notably
// LeaveService#resolveTargetEmployee's plain 403 ApiException when the acting employee is HR/CEO requesting
// for themselves (SELF_SUBMIT_BLOCKED_ROLES).
function previewErrorMessage(error) {
  return error?.message || 'ไม่สามารถตรวจสอบคำขอลานี้ได้ กรุณาลองใหม่อีกครั้ง';
}

function PreviewErrorNotice({ message, className }) {
  if (!message) return null;
  return (
    <div className={`rounded-md border border-danger-border bg-surface p-3 ${className || ''}`}>
      <div className="flex items-start gap-2.5">
        <Icon name="triangleAlert" size={16} className="mt-0.5 shrink-0 text-danger" />
        <div className="grid min-w-0 gap-1">
          <strong className="text-sm font-bold text-danger">ไม่สามารถตรวจสอบคำขอนี้ได้</strong>
          <p className="m-0 text-sm text-text">{message}</p>
        </div>
      </div>
    </div>
  );
}

// Step 1's type button -- a blocked type is disabled and shows the real backend verdict instead of a guess; an
// unresolved (still-loading) type shows a skeleton rather than a false "available".
function TypeChoice({ type, outcomeQuery, selected, onSelect, showCondition }) {
  const loading = outcomeQuery.isPending;
  const blocking = outcomeQuery.data?.blocking ?? null;
  const previewError = outcomeQuery.isError ? previewErrorMessage(outcomeQuery.error) : null;
  const disabled = loading || Boolean(blocking) || Boolean(previewError);
  return (
    <div className="grid gap-2">
      <button
        type="button"
        disabled={disabled}
        onClick={() => onSelect(type.code)}
        aria-pressed={selected}
        className={`flex w-full items-center justify-between gap-3 rounded-md border-[1.5px] px-4 py-3 text-left transition-colors disabled:cursor-not-allowed disabled:opacity-60 ${
          selected ? 'border-primary bg-info-bg-alt' : 'border-border-input bg-surface hover:border-primary-hover'
        }`}
      >
        <span className="grid gap-0.5">
          <span className="text-sm font-bold text-text">{type.nameTh || type.nameEn}</span>
          {showCondition ? (
            <span className="text-xs text-text-muted">{typeGatingConditionText(type)}</span>
          ) : null}
        </span>
        {loading ? <Skeleton width={64} height={14} /> : (
          selected ? <Icon name="check" size={16} className="shrink-0 text-primary" /> : null
        )}
      </button>
      {!loading && blocking ? <LeaveRulePanel outcome={blocking} tone="blocking" /> : null}
      {!loading && previewError ? <PreviewErrorNotice message={previewError} /> : null}
    </div>
  );
}

// Which of the employee's two quota pools (last year's carry-in, or this year's own grant) a
// request should draw down FIRST. Rendered only when the caller (showQuotaPoolSelector) has
// confirmed BOTH `carriesForward` AND `carriedInRemainingDays > 0`; this component does not
// re-check.
//
// Not the "ปี {split.quotaYear}" line step 3 renders from quotaYearSplits: that is the CALENDAR
// split of a request straddling 31 Dec, a different axis. Option labels therefore never lead with a
// bare "ปี {number}"; each names the pool (สะสม / ปีนี้) first.
function QuotaPoolPreferenceField({ register, balance }) {
  // Both years are derived from carriedInFromYear alone: the Javadoc on LeaveBalanceDto ties carriedInExpiresOn
  // to `31 Dec of carriedInFromYear + 1` by construction, so re-deriving from the clock could only ever disagree
  // with the balance this control is actually describing.
  const carriedInYearBe = Number(balance.carriedInFromYear) + 543;
  const ownYearBe = Number(balance.carriedInFromYear) + 1 + 543;
  return (
    <fieldset className="grid gap-2.5 rounded-md border border-border-input bg-surface p-3">
      <legend className="px-1 text-sm font-bold text-text">
        มี{balance.leaveTypeNameTh}สะสมจากปีก่อน — เลือกโควตาที่จะหักก่อน
      </legend>
      <label className="flex cursor-pointer items-start gap-2.5 text-sm">
        <input type="radio" className="mt-0.5 h-4 w-4 shrink-0" value="CARRIED_IN_FIRST" {...register('quotaPoolPreference')} />
        <span className="grid gap-0.5">
          <span className="font-semibold text-text">หักจากโควตาสะสม (ปี {carriedInYearBe}) ก่อน</span>
          <span className="text-xs text-text-muted">
            เหลือ {formatDays(balance.carriedInRemainingDays)} · หมดอายุ {formatThaiDate(balance.carriedInExpiresOn)}
            {' '}— แนะนำ เพราะสิทธิ์นี้ใช้ไม่ทันจะหมดอายุไปเปล่าๆ
          </span>
        </span>
      </label>
      <label className="flex cursor-pointer items-start gap-2.5 text-sm">
        <input type="radio" className="mt-0.5 h-4 w-4 shrink-0" value="OWN_FIRST" {...register('quotaPoolPreference')} />
        <span className="grid gap-0.5">
          <span className="font-semibold text-text">หักจากโควตาปีนี้ (ปี {ownYearBe}) ก่อน</span>
          <span className="text-xs text-text-muted">
            เหลือ {formatDays(balance.ownQuotaRemainingDays)} · เก็บโควตาสะสมไว้ใช้ภายหลัง
          </span>
        </span>
      </label>
    </fieldset>
  );
}

export function LeaveRequestPage({ user, currentEmployee, showToast }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [searchParams] = useSearchParams();
  const returnTab = searchParams.get('returnTab') || 'me';

  const prefillType = searchParams.get('type') || '';
  const prefillStart = searchParams.get('start') || '';
  const prefillEnd = searchParams.get('end') || '';

  const [step, setStep] = useState(prefillType ? 2 : 1);
  const [attachmentFile, setAttachmentFile] = useState(null);

  const step1HeadingRef = useRef(null);
  const step2HeadingRef = useRef(null);
  const step3HeadingRef = useRef(null);

  useEffect(() => {
    const ref = step === 1 ? step1HeadingRef : step === 2 ? step2HeadingRef : step3HeadingRef;
    ref.current?.focus();
  }, [step]);

  function goBackToSurface(focusCta) {
    navigate(`/leave?tab=${encodeURIComponent(returnTab)}`, { state: focusCta ? { focusRequestCta: true } : {} });
  }

  // --- Reference data ---
  const employeesQuery = useQuery({
    queryKey: queryKeys.leaveEmployees(),
    queryFn: () => api.leave.employees().then((r) => r.employees || []),
  });
  const employeeOptions = useMemo(() => employeesQuery.data ?? [], [employeesQuery.data]);
  const canReviewAll = hasPermission(user.role, 'canReviewLeave');
  const submitEmployeeOptions = useMemo(
    () => employeeOptions.filter((employee) => employee.self || employee.directReport || canReviewAll),
    [employeeOptions, canReviewAll],
  );
  const hasMultipleSubmitOptions = submitEmployeeOptions.length > 1;

  const leaveTypesQuery = useQuery({
    queryKey: queryKeys.leaveTypes(),
    queryFn: () => api.leave.types().then((r) => r.leaveTypes || []),
  });
  const leaveTypes = useMemo(() => leaveTypesQuery.data ?? [], [leaveTypesQuery.data]);
  const everydayTypes = useMemo(() => leaveTypes.filter((t) => EVERYDAY_LEAVE_TYPE_CODES.has(t.code)), [leaveTypes]);
  const rareTypes = useMemo(() => leaveTypes.filter((t) => !EVERYDAY_LEAVE_TYPE_CODES.has(t.code)), [leaveTypes]);

  // --- Form ---
  const leaveFormSchema = useMemo(
    () => createLeaveFormSchema({ requireEmployeeId: hasMultipleSubmitOptions, minStartDate: todayIso() }),
    [hasMultipleSubmitOptions],
  );
  const {
    register, handleSubmit, setValue, getValues, trigger, control, formState: { errors },
  } = useForm({
    resolver: zodResolver(leaveFormSchema),
    defaultValues: defaultForm({
      employeeId: currentEmployee?.id || user.employeeId || '',
      leaveTypeCode: prefillType,
      startDate: prefillStart,
      endDate: prefillEnd,
    }),
    mode: 'onChange',
    reValidateMode: 'onChange',
  });
  const [
    employeeId, leaveTypeCode, startDate, endDate, allDay, startTime, endTime, purposeCode, requestedAsEmergency,
    quotaPoolPreference,
  ] = useWatch({
    control,
    name: [
      'employeeId', 'leaveTypeCode', 'startDate', 'endDate', 'allDay', 'startTime', 'endTime', 'purposeCode',
      'requestedAsEmergency', 'quotaPoolPreference',
    ],
  });
  // Not memoised: `leaveTypeCode` is watched, so this recomputes on a type switch. That clears only
  // the derived half (this flag, and the button's disabled state); the message already in
  // `errors.startDate` is cleared by selectType's trigger('startDate') below.
  const startDateInPast = isStartDateBlockedAsPast(leaveTypeCode, startDate, todayIso(), requestedAsEmergency);
  const startDateError = startDateInPast ? pastStartMessage(leaveTypeCode) : errors.startDate?.message;

  // Seed the acting employee once options load, same convention as the pre-A2 form.
  useEffect(() => {
    if (!employeesQuery.data) return;
    const current = getValues('employeeId');
    if (current) return;
    const next = submitEmployeeOptions.find((e) => e.self)?.employeeId || submitEmployeeOptions[0]?.employeeId || '';
    if (next) setValue('employeeId', String(next), { shouldValidate: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [employeesQuery.data]);

  const selectedLeaveType = useMemo(() => leaveTypes.find((t) => t.code === leaveTypeCode), [leaveTypes, leaveTypeCode]);

  // Step 1: dateless eligibility preview, one call per type, so a categorically-ineligible type
  // (once-per-employment already used, min-service not met, probation not passed, missing hire date) is disabled
  // with its real reason BEFORE the employee picks anything else.
  const eligibilityQueries = useQueries({
    queries: leaveTypes.map((type) => ({
      queryKey: queryKeys.leavePreview({ employeeId, leaveTypeCode: type.code }),
      queryFn: () => api.leave.preview({
        employeeId: employeeId ? Number(employeeId) : undefined,
        leaveTypeCode: type.code,
      }).then((r) => r.preview),
      enabled: Boolean(employeeId) && Boolean(type.code),
      staleTime: 30_000,
    })),
  });
  const eligibilityByCode = useMemo(() => {
    const map = new Map();
    leaveTypes.forEach((type, index) => map.set(type.code, eligibilityQueries[index]));
    return map;
  }, [leaveTypes, eligibilityQueries]);

  function selectType(code) {
    setValue('leaveTypeCode', code, { shouldValidate: true });
    // `shouldValidate` re-runs the schema but writes back only the named field's error, so a
    // past-date error from the previous type would persist after switching to a backdate-exempt one.
    // Re-validate startDate, but only once it has a value, or the empty field would show its required
    // error before the user reaches step 2.
    if (getValues('startDate')) trigger('startDate');

    // A different type invalidates whatever purpose/emergency choice belonged to the old one.
    setValue('purposeCode', '', { shouldValidate: true });
    setValue('requestedAsEmergency', false, { shouldValidate: true });
    // A different type has its own (possibly absent) carry-in balance, so a leftover OWN_FIRST pick
    // must not carry over.
    setValue('quotaPoolPreference', 'CARRIED_IN_FIRST', { shouldValidate: true });
  }

  const step1Blocking = leaveTypeCode ? (eligibilityByCode.get(leaveTypeCode)?.data?.blocking ?? null) : null;
  const canAdvanceStep1 = Boolean(leaveTypeCode) && !step1Blocking && (!hasMultipleSubmitOptions || Boolean(employeeId));

  // §5.3.5 pool choice (V161): the acting employee's real per-type balance, so the composer can tell whether
  // there is a real carry-in to choose between at all (see showQuotaPoolSelector below) and can label/caption
  // the two pools with real numbers -- QuotaPoolPreferenceField never fabricates them.
  const balancesQuery = useQuery({
    queryKey: queryKeys.leaveBalances(employeeId, yearFrom(startDate)),
    queryFn: () => api.leave.balances({
      employeeId: Number(employeeId),
      year: yearFrom(startDate),
    }).then((r) => r.balances || []),
    enabled: Boolean(employeeId) && Boolean(leaveTypeCode),
  });
  const balances = useMemo(() => balancesQuery.data ?? [], [balancesQuery.data]);
  const selectedTypeBalance = useMemo(
    () => balances.find((b) => b.leaveTypeCode === leaveTypeCode),
    [balances, leaveTypeCode],
  );
  // Render gate: the type must be able to have a second pool (carriesForward) AND this employee
  // must have carry-in left (carriedInRemainingDays > 0). carriesForward alone is true for every
  // VACATION request even when the pool is spent, and the control would ask a one-answer question
  // (CLAUDE.md: no dead control).
  const showQuotaPoolSelector = Boolean(selectedLeaveType?.carriesForward)
    && Number(selectedTypeBalance?.carriedInRemainingDays || 0) > 0;

  // Step 2: debounced QUICK preview (department coverage skipped -- coverageEvaluated will read false, and that
  // is shown honestly rather than as a clean pass).
  const step2Params = useMemo(() => {
    if (!employeeId || !leaveTypeCode || !startDate || !endDate) return null;
    return {
      employeeId: Number(employeeId),
      leaveTypeCode,
      startDate,
      // A timed span may cover more than one calendar day, so endDate is sent as chosen.
      endDate,
      // Partial-day span (V166): widened from "no sub-day times in this shape at all" -- see LeavePreviewRequest's
      // own Javadoc on the backend. undefined (not sent) when allDay, exactly like every other "only relevant
      // sometimes" field this params object already builds.
      startTime: !allDay && startTime ? startTime : undefined,
      endTime: !allDay && endTime ? endTime : undefined,
      purposeCode: leaveTypeCode === 'PERSONAL' && purposeCode ? purposeCode : undefined,
      requestedAsEmergency: leaveTypeCode === 'PERSONAL' ? Boolean(requestedAsEmergency) : undefined,
      hasAttachment: Boolean(attachmentFile),
      depth: 'QUICK',
      // §5.3.5 pool choice: sent unconditionally, even when showQuotaPoolSelector is false for the
      // currently-selected type.
      quotaPoolPreference,
    };
  }, [
    employeeId, leaveTypeCode, startDate, endDate, allDay, startTime, endTime, purposeCode,
    requestedAsEmergency, attachmentFile, quotaPoolPreference,
  ]);
  const debouncedStep2Params = useDebouncedValue(step2Params, 400);
  const step2PreviewQuery = useQuery({
    queryKey: queryKeys.leavePreview(debouncedStep2Params ?? {}),
    queryFn: ({ signal }) => api.leave.preview(debouncedStep2Params, { signal }).then((r) => r.preview),
    enabled: step === 2 && Boolean(debouncedStep2Params),
  });
  const step2Preview = step2PreviewQuery.data ?? null;
  const step2Blocking = step2Preview?.blocking ?? null;
  const step2BlockingTarget = step2Blocking ? step2FieldTarget(step2Blocking.code) : null;
  // WARN_UNPAID_* gates are non-blocking, so they never gate `canAdvanceStep1`/advanceFromStep2.
  // Read only when nothing blocks (LeavePreviewDto pairs a non-null `blocking` with an empty
  // ruleWarnings); a dateless call returns `[]`, which already reads as "nothing to show".
  const step2Warnings = !step2Blocking ? (step2Preview?.ruleWarnings ?? []) : [];
  const step2Fetching = step2PreviewQuery.isFetching;
  // Same "not every failure is a LeaveRuleOutcome" gap as step 1's TypeChoice -- see previewErrorMessage's own
  // comment.
  const step2Error = step2PreviewQuery.isError ? previewErrorMessage(step2PreviewQuery.error) : null;

  const previewCounters = step2Preview?.counters
    ?? (eligibilityByCode.get(leaveTypeCode)?.data?.counters)
    ?? null;

  // --- Step 2: calendar context (holidays + resolved schedule), so "why is this N days and not
  // the full calendar span" is answered next to the date fields. The window is exactly the selected
  // [startDate, endDate], since a wider one would show non-working days the request never touches.
  // Debounced like step2Params so typing a date doesn't fire a request per keystroke.
  const calendarContextParams = useMemo(() => {
    if (!startDate || !endDate || endDate < startDate) return null;
    return { from: startDate, to: endDate };
  }, [startDate, endDate]);
  const debouncedCalendarContextParams = useDebouncedValue(calendarContextParams, 400);
  const calendarContextQuery = useQuery({
    queryKey: queryKeys.leaveCalendarContext(
      debouncedCalendarContextParams?.from, debouncedCalendarContextParams?.to),
    queryFn: () => api.leave.calendarContext(debouncedCalendarContextParams).then((r) => r.calendarContext),
    enabled: step === 2 && Boolean(debouncedCalendarContextParams),
  });
  const nonWorkingDates = calendarContextQuery.data?.nonWorkingDates ?? [];
  const holidaysInRange = calendarContextQuery.data?.holidays ?? [];

  async function advanceFromStep2() {
    const valid = await trigger(['startDate', 'endDate', 'reason', 'startTime', 'endTime']);
    if (!valid || startDateInPast) return;
    setStep(3);
  }

  // --- Step 3: contact defaults (paper-form autofill) + the one explicit FULL preview call. ---
  const contactDefaultsQuery = useQuery({
    queryKey: queryKeys.leaveContactDefaults(employeeId),
    queryFn: () => api.leave.contactDefaults({ ...(employeeId ? { employeeId } : {}) }).then((r) => r.contactDefaults || null),
    enabled: step === 3 && !!employeeId,
  });
  useEffect(() => {
    const defaults = contactDefaultsQuery.data;
    if (!defaults) return;
    setValue('contactHouseNo', defaults.contactHouseNo || '');
    setValue('contactSubdistrict', defaults.contactSubdistrict || '');
    setValue('contactDistrict', defaults.contactDistrict || '');
    setValue('contactProvince', defaults.contactProvince || '');
    setValue('contactPhone', defaults.contactPhone || '');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [contactDefaultsQuery.data]);

  const step3Params = useMemo(() => {
    if (step !== 3 || !employeeId || !leaveTypeCode || !startDate || !endDate) return null;
    return {
      employeeId: Number(employeeId),
      leaveTypeCode,
      startDate,
      // Partial-day span (V166): same "sent as-is" reasoning as step2Params above.
      endDate,
      startTime: !allDay && startTime ? startTime : undefined,
      endTime: !allDay && endTime ? endTime : undefined,
      purposeCode: leaveTypeCode === 'PERSONAL' && purposeCode ? purposeCode : undefined,
      requestedAsEmergency: leaveTypeCode === 'PERSONAL' ? Boolean(requestedAsEmergency) : undefined,
      hasAttachment: Boolean(attachmentFile),
      depth: 'FULL',
      // Same "always send, backend ignores it when inapplicable" reasoning as step2Params above.
      quotaPoolPreference,
    };
  }, [
    step, employeeId, leaveTypeCode, startDate, endDate, allDay, startTime, endTime, purposeCode,
    requestedAsEmergency, attachmentFile, quotaPoolPreference,
  ]);
  const step3PreviewQuery = useQuery({
    queryKey: queryKeys.leavePreview(step3Params ?? {}),
    queryFn: ({ signal }) => api.leave.preview(step3Params, { signal }).then((r) => r.preview),
    enabled: Boolean(step3Params),
  });
  const step3Preview = step3PreviewQuery.data ?? null;
  const step3Blocking = step3Preview?.blocking ?? null;
  // Same "not every failure is a LeaveRuleOutcome" gap as step 1/2 -- see previewErrorMessage's own comment.
  const step3Error = step3PreviewQuery.isError ? previewErrorMessage(step3PreviewQuery.error) : null;
  // Same "blocking and ruleWarnings are never both set" contract as step2Warnings. This decides the
  // submit button's label ("ส่งคำขอ (ไม่รับค่าจ้าง)" once any warning exists): step 3 is the
  // terminal FULL preview right before submit, so it is authoritative, not step 2's QUICK snapshot.
  const step3Warnings = !step3Blocking ? (step3Preview?.ruleWarnings ?? []) : [];

  const balance = useMemo(() => {
    const type = leaveTypes.find((t) => t.code === leaveTypeCode);
    return type ? { type } : null;
  }, [leaveTypes, leaveTypeCode]);

  // --- Submit ---
  async function prepareAttachment(file) {
    if (!file) return null;
    if (!['application/pdf', 'image/jpeg', 'image/png'].includes(file.type)) {
      throw new Error('รองรับเฉพาะไฟล์ PDF, JPG หรือ PNG');
    }
    // imageCompression() returns a plain Blob without `file.name`, so the FormData part would get the
    // filename "blob". Re-wrapping in a File restores the original name. PDFs skip compression.
    if (!file.type.startsWith('image/')) return file;
    return new File(
      [await imageCompression(file, { maxSizeMB: 2, maxWidthOrHeight: 1600, useWebWorker: true })],
      file.name,
      { type: file.type },
    );
  }

  const createMutation = useMutation({
    mutationFn: (payload) => api.leave.create(payload).then((r) => r.request),
    onSuccess: (created) => {
      queryClient.invalidateQueries({ queryKey: ['leave'] });
      if (created?.status === 'AUTO_REJECTED') {
        showToast('error', created.systemNote || 'คำขอลาไม่ผ่านเงื่อนไข');
      } else {
        // A rule-passing request lands SUBMITTED and waits for a human, so the copy must not say the
        // leave was granted.
        showToast('success', 'ส่งคำขอลาแล้ว รอผู้อนุมัติพิจารณา');
      }
      goBackToSurface(true);
    },
    onError: (error) => showToast('error', error.message || 'ส่งคำขอลาไม่สำเร็จ'),
  });

  async function onSubmit(values) {
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
      // A timed request may span several days, so endDate is sent as chosen.
      endDate: values.endDate,
      reason: values.reason.trim(),
      startTime: !values.allDay && values.startTime ? values.startTime : null,
      endTime: !values.allDay && values.endTime ? values.endTime : null,
      contactHouseNo: values.contactHouseNo?.trim() || null,
      contactSubdistrict: values.contactSubdistrict?.trim() || null,
      contactDistrict: values.contactDistrict?.trim() || null,
      contactProvince: values.contactProvince?.trim() || null,
      contactPhone: values.contactPhone?.trim() || null,
      purposeCode: values.leaveTypeCode === 'PERSONAL' && values.purposeCode ? values.purposeCode : null,
      requestedAsEmergency: values.leaveTypeCode === 'PERSONAL' ? Boolean(values.requestedAsEmergency) : null,
      // §5.3.5 pool choice (V161): same "always send" reasoning as step2Params/step3Params above.
      quotaPoolPreference: values.quotaPoolPreference || 'CARRIED_IN_FIRST',
      attachmentFile: preparedAttachment,
    });
  }

  // Partial-day span (V166): endDate is no longer force-locked to startDate while timed -- only bumped forward
  // when it would otherwise fall BEFORE the new startDate (the one invariant that must always hold, mirrors
  // chk_leave_date_order/validateDateRange on the backend), regardless of allDay.
  function handleStartDateChange(event) {
    const value = event.target.value;
    if (getValues('endDate') < value) {
      setValue('endDate', value, { shouldDirty: true, shouldValidate: true });
    }
  }

  // Unticking "ลาทั้งวัน" reveals the time fields, prefilled to the standard workday (08:30-17:30)
  // so the control starts looking like a full day. Re-ticking clears both times (whole-day
  // null/null) and leaves startDate/endDate as chosen.
  function handleAllDayToggle(event) {
    if (event.target.checked) {
      setValue('startTime', '', { shouldDirty: true, shouldValidate: true });
      setValue('endTime', '', { shouldDirty: true, shouldValidate: true });
    } else {
      if (!getValues('startTime')) setValue('startTime', '08:30', { shouldDirty: true, shouldValidate: true });
      if (!getValues('endTime')) setValue('endTime', '17:30', { shouldDirty: true, shouldValidate: true });
    }
  }

  // Revalidate the start date when emergency status changes so past-date errors do not remain stale.
  function handleEmergencyToggle() {
    if (getValues('startDate')) trigger('startDate');
  }

  const showTimedFields = !allDay;

  // Upcoming-holidays panel: a fixed ~90-day forward window from today, independent of the chosen
  // dates, same as OvertimePanel.jsx's UpcomingHolidays. Not `calendarContextParams` below, which
  // is scoped to the SELECTED range.
  const holidayWindowFrom = todayIso();
  const holidayWindowTo = addDaysIso(holidayWindowFrom, 90);

  // This <form> wraps all 3 steps; `canSubmit={step === 3}` is a restriction (never a permission)
  // on top of SafeForm's submitter guard, so BOTH must pass. Without it Enter in a single remaining
  // text field could silently file a real request: step 1 has only `type="button"` choices, step 2
  // would have just the date fields, and jsdom does not implement implicit submission, so tests
  // would not catch it. Step 3's submit button is always rendered (only ever `disabled`). A test
  // proving step 3 submits must carry a real submitter; see `submitWithSubmitter` in
  // LeaveRequestPage.test.jsx.

  return (
    <PageStack className="relative">
      <PageHeader
        title="ยื่นคำขอลา"
        subtitle="ระบบตรวจเงื่อนไขทั้งหมดให้ก่อนคุณกดส่ง จะได้ไม่ต้องแก้แล้วแก้อีก"
        breadcrumbs={[{ label: 'การลา', onClick: () => goBackToSurface(false) }, { label: 'ยื่นคำขอลา' }]}
      />

      {/* The holiday answer arrives before the date is picked: this sits above the step-2 panel, not
          inside it (UpcomingHolidays renders its own card; no card-in-a-card), and only on step 2
          (step 3 has its own reactive note for the selected dates). */}
      {step === 2 ? (
        <UpcomingHolidays from={holidayWindowFrom} to={holidayWindowTo} />
      ) : null}

      <SafeForm onSubmit={handleSubmit(onSubmit)} canSubmit={step === 3} noValidate className="grid gap-[18px]">
        {/* ── Step 1: เลือกประเภท ───────────────────────────────────────────── */}
        {step === 1 ? (
          <Panel>
            <StepHeading innerRef={step1HeadingRef} step={1} total={3} title="เลือกประเภทการลา" />
            <div className="mt-4 grid gap-4">
              {hasMultipleSubmitOptions ? (
                <FormField label="พนักงาน" htmlFor="leave-employee" error={errors.employeeId?.message} required>
                  <select
                    id="leave-employee"
                    {...register('employeeId')}
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
              ) : null}

              {leaveTypesQuery.isPending ? (
                <div className="grid gap-2" aria-busy="true" aria-label="กำลังโหลดประเภทการลา">
                  <Skeleton height={52} />
                  <Skeleton height={52} />
                  <Skeleton height={52} />
                </div>
              ) : (
                <>
                  <div className="grid gap-2">
                    {everydayTypes.map((type) => (
                      <TypeChoice
                        key={type.code}
                        type={type}
                        outcomeQuery={eligibilityByCode.get(type.code) ?? { isPending: true }}
                        selected={leaveTypeCode === type.code}
                        onSelect={selectType}
                        showCondition={false}
                      />
                    ))}
                  </div>
                  {rareTypes.length > 0 ? (
                    <CollapsibleSection title="ประเภทอื่นๆ" subtitle="ลาคลอด ลาทหาร ลาอุปสมบท" defaultOpen={Boolean(rareTypes.find((t) => t.code === leaveTypeCode))}>
                      <div className="grid gap-2">
                        {rareTypes.map((type) => (
                          <TypeChoice
                            key={type.code}
                            type={type}
                            outcomeQuery={eligibilityByCode.get(type.code) ?? { isPending: true }}
                            selected={leaveTypeCode === type.code}
                            onSelect={selectType}
                            showCondition
                          />
                        ))}
                      </div>
                    </CollapsibleSection>
                  ) : null}
                </>
              )}
            </div>
            <div className="mt-5 flex justify-end gap-[10px]">
              <Button type="button" variant="secondary" onClick={() => goBackToSurface(true)}>ยกเลิก</Button>
              <Button type="button" disabled={!canAdvanceStep1} onClick={() => setStep(2)}>ถัดไป</Button>
            </div>
          </Panel>
        ) : null}

        {/* ── Step 2: วันที่และรายละเอียด ────────────────────────────────────── */}
        {step === 2 ? (
          <Panel>
            <StepHeading innerRef={step2HeadingRef} step={2} total={3} title={`วันที่และรายละเอียด · ${selectedLeaveType?.nameTh || ''}`} />
            {/* Single aria-live region for every dynamic verdict step 2 produces -- "polite", never "assertive": this
             * updates on every keystroke via the 400ms debounce and would machine-gun a screen reader otherwise. */}
            <div className="mt-4 grid gap-4" aria-live="polite" aria-busy={step2Fetching}>
              <div className={formGridSpan2}>
                <p className="m-0 rounded-md border border-info-border bg-info-bg-alt px-3 py-2 text-xs text-info">
                  ตรวจแบบเร็ว: ยังไม่ตรวจภาระงานของแผนก (coverageEvaluated) — จะตรวจครั้งสุดท้ายในขั้นตอน “ตรวจสอบก่อนส่ง”
                </p>
              </div>
              {step2Error ? (
                <div className={formGridSpan2}>
                  <PreviewErrorNotice message={step2Error} />
                </div>
              ) : null}

              <div className="grid grid-cols-2 gap-4 mobile:grid-cols-1">
                <FormField label="วันที่เริ่ม" htmlFor="leave-start-date" error={startDateError} required>
                  <ThaiDateController
                    id="leave-start-date"
                    control={control}
                    name="startDate"
                    onChange={handleStartDateChange}
                    aria-invalid={Boolean(startDateError)}
                    aria-describedby={startDateError ? fieldErrorId('leave-start-date') : undefined}
                    required
                  />
                </FormField>
                <FormField label="วันที่สิ้นสุด" htmlFor="leave-end-date" error={errors.endDate?.message} required>
                  <ThaiDateController
                    id="leave-end-date"
                    control={control}
                    name="endDate"
                    min={startDate}
                    aria-invalid={Boolean(errors.endDate)}
                    aria-describedby={errors.endDate ? fieldErrorId('leave-end-date') : undefined}
                    required
                  />
                </FormField>
              </div>
              {step2BlockingTarget === 'dates' && step2Blocking ? (
                <div className={formGridSpan2}>
                  <LeaveRulePanel outcome={step2Blocking} tone="blocking" />
                </div>
              ) : null}

              {/* Calendar context (#leave-calendar-context, Phase C): why totalDays can be smaller than the calendar span --
               * lists which selected dates are holidays/non-working per the employee's OWN resolved schedule, not a full
               * calendar widget. */}
              {calendarContextParams ? (
                <div className={formGridSpan2} data-testid="calendar-context-note">
                  {nonWorkingDates.length > 0 ? (
                    <div className="rounded-lg border border-warning-border bg-warning-bg-soft px-3 py-2 text-xs text-warning-dark">
                      <p className="m-0">
                        ช่วงที่เลือกมี {nonWorkingDates.length} วันที่ไม่นับเป็นวันทำงาน (วันหยุดบริษัท/วันหยุดประจำสัปดาห์
                        ตามตารางเวลาทำงานของคุณ): {nonWorkingDates.map((date) => formatDate(date)).join(', ')}
                      </p>
                      {/* Real วันหยุดบริษัท names run up to 149 chars, so each holiday gets its own line rather than
                          being comma-joined into the sentence above. */}
                      {holidaysInRange.length > 0 ? (
                        <div className="mt-2 border-t border-warning-border pt-2">
                          <p className="m-0 font-semibold">วันหยุดบริษัทในช่วงนี้</p>
                          <ul className="m-0 mt-1 grid list-none gap-1 p-0">
                            {holidaysInRange.map((holiday) => (
                              <li key={holiday.holidayDate} className="flex flex-wrap items-baseline gap-x-1.5">
                                <strong className="shrink-0">{formatDate(holiday.holidayDate)}</strong>
                                <span className="min-w-0">{holiday.nameTh}</span>
                              </li>
                            ))}
                          </ul>
                        </div>
                      ) : null}
                    </div>
                  ) : !calendarContextQuery.isFetching && calendarContextQuery.data ? (
                    <p className="m-0 text-xs text-muted">ทุกวันในช่วงที่เลือกเป็นวันทำงานตามตารางเวลาของคุณ</p>
                  ) : null}
                </div>
              ) : null}

              {/* Partial-day span: ticked by default (whole-day, matching defaultForm's allDay: true);
                  unticking reveals a start TIME + end TIME pair for the start/end DATE fields above, which may
                  differ (see handleAllDayToggle). Grouped in a bordered panel like QuotaPoolPreferenceField so
                  the toggle and the fields it reveals read as one unit. */}
              <div className={`${formGridSpan2} grid gap-3 rounded-md border border-border-input bg-surface p-3`}>
                <label className="flex cursor-pointer items-center gap-2 text-sm font-semibold text-text">
                  <input
                    type="checkbox"
                    className="h-4 w-4 shrink-0"
                    {...register('allDay', { onChange: handleAllDayToggle })}
                  />
                  ลาทั้งวัน
                </label>
                {showTimedFields ? (
                  <div className="grid grid-cols-2 gap-4 mobile:grid-cols-1">
                    <FormField
                      label="เวลาเริ่ม"
                      htmlFor="leave-start-time"
                      hint={startDate && endDate && startDate !== endDate ? formatDate(startDate) : undefined}
                      error={errors.startTime?.message}
                      required
                    >
                      <input
                        id="leave-start-time"
                        type="time"
                        {...register('startTime')}
                        aria-invalid={Boolean(errors.startTime)}
                        aria-describedby={errors.startTime ? fieldErrorId('leave-start-time') : undefined}
                        required
                      />
                    </FormField>
                    <FormField
                      label="เวลาสิ้นสุด"
                      htmlFor="leave-end-time"
                      hint={startDate && endDate && startDate !== endDate ? formatDate(endDate) : undefined}
                      error={errors.endTime?.message}
                      required
                    >
                      <input
                        id="leave-end-time"
                        type="time"
                        {...register('endTime')}
                        aria-invalid={Boolean(errors.endTime)}
                        aria-describedby={errors.endTime ? fieldErrorId('leave-end-time') : undefined}
                        required
                      />
                    </FormField>
                  </div>
                ) : null}
              </div>

              {/* §5.3.5 pool choice (V161): sits with the other "mechanics of this request" fields (dates, sub-day), above
               * เหตุผลการลา -- same field-order reasoning as the note right below: this is part of WHAT is being asked for,
               * not WHY. */}
              {showQuotaPoolSelector ? (
                <div className={formGridSpan2}>
                  <QuotaPoolPreferenceField register={register} balance={selectedTypeBalance} />
                </div>
              ) : null}

              {/* เหตุผลการลา is required, so it sits directly under the dates rather than after the optional
                  fields. */}
              <div className={formGridSpan2}>
                <FormField label="เหตุผลการลา" htmlFor="leave-reason" error={errors.reason?.message} required>
                  <textarea
                    id="leave-reason"
                    rows={3}
                    {...register('reason')}
                    aria-invalid={Boolean(errors.reason)}
                    aria-describedby={errors.reason ? fieldErrorId('leave-reason') : undefined}
                    required
                  />
                </FormField>
              </div>

              {leaveTypeCode === 'PERSONAL' ? (
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
                    {/* FormField's own `label` (not an inline <label> wrapping the checkbox) -- its id-match augmentation only
                     * inspects DIRECT children, so the <input> must be the direct child for the hint/error aria-describedby wiring
                     * below to actually land on the control a screen reader focuses, not on a wrapper. */}
                    <FormField
                      label="ลากิจฉุกเฉิน (แจ้งหัวหน้างานก่อนเริ่มงานแล้ว และจะยื่นใบลาในวันแรกที่กลับมาทำงาน)"
                      htmlFor="leave-requested-as-emergency"
                      hint={previewCounters ? `เหลือสิทธิ์ลากิจฉุกเฉินเดือนนี้ ${previewCounters.emergencyFilingsRemaining} ครั้ง` : undefined}
                      error={step2BlockingTarget === 'emergency' && step2Blocking ? step2Blocking.messageTh : undefined}
                    >
                      <input
                        id="leave-requested-as-emergency"
                        type="checkbox"
                        className="h-4 w-4"
                        {...register('requestedAsEmergency', { onChange: handleEmergencyToggle })}
                      />
                    </FormField>
                    {step2BlockingTarget === 'emergency' && step2Blocking ? (
                      <LeaveRulePanel outcome={step2Blocking} tone="blocking" className="mt-2" />
                    ) : null}
                  </div>
                </>
              ) : null}

              <div className={formGridSpan2}>
                <FormField
                  label="เอกสารแนบ"
                  htmlFor="leave-attachment-file"
                  hint={leaveTypeCode === 'SICK' && previewCounters
                    ? `เหลือสิทธิ์ลาป่วยไม่มีใบรับรองแพทย์เดือนนี้ ${previewCounters.noCertificateOccasionsRemaining} ครั้ง`
                    : undefined}
                  error={step2BlockingTarget === 'attachment' && step2Blocking ? step2Blocking.messageTh : undefined}
                >
                  <FileUploadField
                    id="leave-attachment-file"
                    accept="application/pdf,image/jpeg,image/png,.pdf,.jpg,.jpeg,.png"
                    onChange={(event) => setAttachmentFile(event.target.files?.[0] || null)}
                    helperText="PDF, JPG หรือ PNG · ลาป่วยต้องแนบใบรับรองแพทย์"
                  />
                </FormField>
                {step2BlockingTarget === 'attachment' && step2Blocking ? (
                  <LeaveRulePanel outcome={step2Blocking} tone="blocking" className="mt-2" />
                ) : null}
              </div>

              {/* Also suppressed when a WARN gate fired, not only a BLOCK: a green "ผ่านเงื่อนไขแล้ว" directly
                  above the amber "…จะไม่ได้รับค่าจ้าง" panel contradicts it. A "passed" line belongs here only
                  when there is nothing to flag. */}
              {!step2Blocking && step2Warnings.length === 0 && step2Preview ? (
                <div className={formGridSpan2}>
                  <p className="m-0 text-sm font-semibold text-success-dark">
                    ผ่านเงื่อนไขที่ตรวจแบบเร็วแล้ว ({formatDays(step2Preview.totalDays)})
                  </p>
                </div>
              ) : null}

              {/* §5 WARN_UNPAID_* gates: the request would still submit, but part of it becomes unpaid if approved -- shown
               * as early as step 2's own debounced check, not held back until step 3's terminal review, so the employee sees
               * the consequence while they can still change dates/attachments to avoid it. */}
              {step2Warnings.length > 0 ? (
                <div className={formGridSpan2}>
                  <LeaveRuleWarningList warnings={step2Warnings} />
                </div>
              ) : null}
            </div>

            <div className="mt-5 flex justify-between gap-[10px]">
              <Button type="button" variant="secondary" onClick={() => setStep(1)}>ย้อนกลับ</Button>
              <Button type="button" onClick={advanceFromStep2} disabled={startDateInPast}>ถัดไป: ตรวจสอบก่อนส่ง</Button>
            </div>
          </Panel>
        ) : null}

        {/* ── Step 3: ตรวจสอบก่อนส่ง ─────────────────────────────────────────── */}
        {step === 3 ? (
          <Panel>
            <StepHeading innerRef={step3HeadingRef} step={3} total={3} title="ตรวจสอบก่อนส่ง" />

            <div className="mt-4 grid gap-4">
              {step3PreviewQuery.isFetching && !step3Preview ? (
                <div aria-busy="true" aria-label="กำลังตรวจสอบคำขอ" className="grid gap-2">
                  <Skeleton height={80} />
                </div>
              ) : null}

              {/* Terminal, blocking verdict: role="alert" (unlike step 2's polite live region) -- this is the final word,
               * requires action, and does not update on every keystroke. */}
              {step3Blocking ? (
                <div role="alert">
                  <LeaveRulePanel outcome={step3Blocking} tone="blocking" />
                </div>
              ) : null}

              {step3Error ? (
                <div role="alert">
                  <PreviewErrorNotice message={step3Error} />
                </div>
              ) : null}

              {/* §5 WARN_UNPAID_* gates: non-blocking -- submit stays enabled -- but this is the LAST thing shown before the
               * employee commits, so it sits with the same prominence as a blocking verdict would rather than folded into
               * that box. */}
              {step3Warnings.length > 0 ? (
                <div aria-live="polite">
                  <LeaveRuleWarningList warnings={step3Warnings} />
                </div>
              ) : null}

              {!step3Blocking && step3Preview ? (
                <div className="grid gap-3 rounded-md border border-border-subtle bg-surface-subtle p-3">
                  <div className="grid grid-cols-2 gap-3 text-sm mobile:grid-cols-1">
                    <span><strong className="block text-text-muted">รวมวันลา</strong>{formatDays(step3Preview.totalDays)}</span>
                    <span><strong className="block text-text-muted">รับค่าจ้าง / ไม่รับค่าจ้าง</strong>{formatDays(step3Preview.paidDays)} / {formatDays(step3Preview.unpaidDays)}</span>
                  </div>
                  {step3Preview.quotaYearSplits?.length > 1 ? (
                    <div className="grid gap-1.5">
                      <strong className="text-sm text-text-muted">แบ่งตามปีโควตา (คำขอคาบเกี่ยวปี)</strong>
                      {step3Preview.quotaYearSplits.map((split) => (
                        <p key={split.quotaYear} className="m-0 text-sm">
                          ปี {split.quotaYear}: {formatDays(split.totalDays)} (รับค่าจ้าง {formatDays(split.paidDays)} · ไม่รับค่าจ้าง {formatDays(split.unpaidDays)}) —
                          คงเหลือหลังคำขอ {formatDays(split.quotaRemainingAfter)}
                        </p>
                      ))}
                    </div>
                  ) : null}
                  {leaveTypeCode === 'PERSONAL' && previewCounters ? (
                    <p className="m-0 text-sm text-text-muted">
                      คำขอนี้จะใช้สิทธิ์ลากิจฉุกเฉินเดือนนี้ 1 ใน {previewCounters.emergencyFilingsRemaining + (requestedAsEmergency ? 1 : 0)} ครั้งที่เหลือ (ถ้าเลือกลากิจฉุกเฉิน)
                    </p>
                  ) : null}
                  {leaveTypeCode === 'SICK' && previewCounters ? (
                    <p className="m-0 text-sm text-text-muted">
                      เหลือสิทธิ์ลาป่วยไม่มีใบรับรองแพทย์เดือนนี้ {previewCounters.noCertificateOccasionsRemaining} ครั้ง
                    </p>
                  ) : null}
                </div>
              ) : null}

              {balance?.type ? (
                <div className="grid gap-1.5">
                  {/* MILITARY's annualQuotaDays is a 366 sentinel ("no annual ceiling; the 60-day paid cap is the
                      real rule"), for which QuotaBar returns null (cap == null). The paid cap is rendered by hand
                      below. */}
                  {/* `formatValue={formatDays}`: see MyLeaveTab.jsx's matching QuotaBar; values carry their own
                      unit, so the caption drops "(วัน)". */}
                  {/* §5.3.5 carry-forward: `cap` folds in carriedInDays (same as MyLeaveTab.jsx's
                      PrimaryLeaveBalanceCard) so a request drawing on BOTH the carry-in pool and this year's quota
                      can exceed the flat annual figure without pinning the bar at 100% red and firing a false
                      "เกินโควตาประจำปี". Reads `selectedTypeBalance` (the per-type balance from balancesQuery),
                      not `balance`, which is only the flat `{ type }` record with no carriedInDays. Uses
                      carriedInDays (the full grant), not carriedInRemainingDays, so the bar stays measured against
                      the year's entitlement. The MILITARY branch is untouched; the guard is structural, not
                      resting on only VACATION having carriedInDays. */}
                  <QuotaBar
                    label={balance.type.nameTh}
                    caption={`โควตา${balance.type.nameTh}`}
                    used={step3Preview ? Number(step3Preview.totalDays || 0) : 0}
                    cap={balance.type.code === 'MILITARY'
                      ? null
                      : Number(balance.type.annualQuotaDays || 0) + Number(selectedTypeBalance?.carriedInDays || 0)}
                    formatValue={formatDays}
                    overMessage="คำขอนี้เกินโควตาประจำปี ส่วนที่เกินจะไม่รับค่าจ้าง"
                  />
                  {balance.type.code === 'MILITARY' && balance.type.paidDaysCap != null ? (
                    <p className="m-0 text-xs text-text-muted">
                      ไม่มีเพดานวันลาต่อปี แต่จ่ายค่าจ้างสูงสุด {formatDays(balance.type.paidDaysCap)} ต่อปี
                    </p>
                  ) : null}
                </div>
              ) : null}

              <CollapsibleSection title="ข้อมูลติดต่อระหว่างลา / ตำแหน่งงาน" subtitle="ดึงจากข้อมูลพนักงานอัตโนมัติ แก้ไขได้" defaultOpen={false}>
                <div className="grid grid-cols-2 gap-4 mobile:grid-cols-1">
                  <FormField label="ตำแหน่ง" htmlFor="leave-position-display">
                    <input id="leave-position-display" value={contactDefaultsQuery.data?.positionTh || '-'} disabled />
                  </FormField>
                  <FormField label="แผนก / ฝ่าย" htmlFor="leave-department-display">
                    <input
                      id="leave-department-display"
                      value={[contactDefaultsQuery.data?.departmentTh, contactDefaultsQuery.data?.divisionTh].filter(Boolean).join(' / ') || '-'}
                      disabled
                    />
                  </FormField>
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
                </div>
              </CollapsibleSection>
            </div>

            <div className="mt-5 flex justify-between gap-[10px]">
              <Button type="button" variant="secondary" onClick={() => setStep(2)}>ย้อนกลับ</Button>
              <Button
                type="submit"
                disabled={createMutation.isPending || Boolean(step3Blocking) || Boolean(step3Error) || step3PreviewQuery.isFetching}
              >
                <Icon name="plus" />
                {/* A warning never disables submit, but the pay consequence is spelled out on the button itself.
                    Keyed off step3Warnings (the terminal FULL preview), never step2Warnings. */}
                {step3Warnings.length > 0 ? 'ส่งคำขอ (ไม่รับค่าจ้าง)' : 'ส่งคำขอ'}
              </Button>
            </div>
          </Panel>
        ) : null}
      </SafeForm>
    </PageStack>
  );
}
