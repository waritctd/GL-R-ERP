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
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { EVERYDAY_LEAVE_TYPE_CODES } from './MyLeaveTab.jsx';
import { LeaveRulePanel } from './LeaveRulePanel.jsx';
import { LEAVE_PURPOSE_OPTIONS } from './leaveRequestTable.jsx';
import { formatDate, formatDays, todayIso } from './leaveFormatting.js';

// Leave-request composer, Phase A2 (#485). The owner's own framing of the problem this closes:
// "employee if rejected, or when requesting, the rules should also be restated to them so they
// won't have cognitive overload." Before this page, all 17 §5 rules (LeaveService#autoRejectNote)
// were enforced only at submit time -- an employee filled in sixteen fields, submitted, and was
// auto-rejected, which is how they learned a rule existed at all. This page runs the SAME gate
// chain as a dry-run (POST /api/leave/preview, LeaveService#preview) at three checkpoints instead
// of one: a categorical eligibility check the moment a type is picked (step 1, before any date
// exists to be wrong), a debounced as-you-type check once dates/details are entered (step 2,
// QUICK depth -- skips the department-coverage fan-out), and one full re-check right before
// submission (step 3, FULL depth -- the only point department coverage actually runs). Every rule
// threshold shown here is the backend's own verdict; this file only decides WHERE and WHEN to ask
// for it -- see CLAUDE.md's "do not reimplement any of the 17 rules in JavaScript".
//
// Route: `/leave/new`, deep-linkable with `?type=&start=&end=` (a rejected request's own "ยื่น
// คำขอใหม่" retry link -- MyLeaveTab.jsx's `retryLeaveRequestHref` -- and the auto-rejection
// notification both point here) and `?returnTab=` (which LeaveSurfacePage.jsx tab to land back on
// after cancel/back/submit).

const LEAVE_START_PAST_MESSAGE = 'วันที่เริ่มลาต้องไม่ก่อนวันนี้';
const LEAVE_TIME_ORDER_MESSAGE = 'เวลาสิ้นสุดต้องหลังเวลาเริ่ม';

// Step 1's secondary disclosure leads each rare type with its own gating condition (the brief's
// own framing: "each leading with its *gating condition*") -- this only echoes each type's OWN
// metadata columns (minServiceMonths/oncePerEmployment/requiresAttachment, already present on the
// LeaveTypeDto the /types endpoint returns), never a computed eligibility DECISION about whether
// THIS employee clears them -- that verdict comes only from the real preview call (`outcomeQuery`
// in `TypeChoice` below), never from this text.
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

// Maps a blocking LeaveRuleOutcome to the step-2 field it should attach beneath -- "per-field
// placement, not one banner". Only one outcome is ever blocking at a time (LeaveService#preview
// returns the FIRST gate hit, same as #submit), so this only ever picks one target per response.
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
    subDay: false,
    startTime: '',
    endTime: '',
    contactHouseNo: '',
    contactSubdistrict: '',
    contactDistrict: '',
    contactProvince: '',
    contactPhone: '',
    purposeCode: '',
    requestedAsEmergency: false,
  };
}

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
      context.addIssue({ code: z.ZodIssueCode.custom, path: ['employeeId'], message: 'กรุณาเลือกพนักงาน' });
    }
    if (data.startDate && data.startDate < minStartDate) {
      context.addIssue({ code: z.ZodIssueCode.custom, path: ['startDate'], message: LEAVE_START_PAST_MESSAGE });
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

/** Debounces `value` by `delayMs` -- used only for the step-2 QUICK preview's input. */
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
// LeaveService#resolveTargetEmployee's plain 403 ApiException when the acting employee is HR/CEO
// requesting for themselves (SELF_SUBMIT_BLOCKED_ROLES). That has no `code` to look up in
// LeaveRulePanel's RULE_META and no §5 section to link to, so it is never routed through
// LeaveRulePanel -- doing so would render an "undefined" heading and a rules-tab link that goes
// nowhere useful. `error.message` is already the backend's own Thai sentence (ApiExceptionHandler
// serializes ApiException.getMessage() verbatim), so this only decides where/how to show it.
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

// Step 1's type button -- a blocked type is disabled and shows the real backend verdict instead
// of a guess; an unresolved (still-loading) type shows a skeleton rather than a false "available".
// A type whose preview call errored outright (see PreviewErrorNotice above) stays disabled too,
// with its own real reason -- never falls through to looking selectable just because `blocking`
// happens to read null on an errored query.
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
    employeeId, leaveTypeCode, startDate, endDate, subDay, purposeCode, requestedAsEmergency,
  ] = useWatch({
    control,
    name: ['employeeId', 'leaveTypeCode', 'startDate', 'endDate', 'subDay', 'purposeCode', 'requestedAsEmergency'],
  });
  const startDateInPast = Boolean(startDate && startDate < todayIso());
  const startDateError = startDateInPast ? LEAVE_START_PAST_MESSAGE : errors.startDate?.message;

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

  // --- Step 1: dateless eligibility preview, one call per type, so a categorically-ineligible
  // type (once-per-employment already used, min-service not met, probation not passed, missing
  // hire date) is disabled with its real reason BEFORE the employee picks anything else. ---
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
    // A different type invalidates whatever purpose/emergency choice belonged to the old one.
    setValue('purposeCode', '', { shouldValidate: true });
    setValue('requestedAsEmergency', false, { shouldValidate: true });
  }

  const step1Blocking = leaveTypeCode ? (eligibilityByCode.get(leaveTypeCode)?.data?.blocking ?? null) : null;
  const canAdvanceStep1 = Boolean(leaveTypeCode) && !step1Blocking && (!hasMultipleSubmitOptions || Boolean(employeeId));

  // --- Step 2: debounced QUICK preview (department coverage skipped -- coverageEvaluated will
  // read false, and that is shown honestly rather than as a clean pass). ---
  const step2Params = useMemo(() => {
    if (!employeeId || !leaveTypeCode || !startDate || !endDate) return null;
    return {
      employeeId: Number(employeeId),
      leaveTypeCode,
      startDate,
      endDate: subDay ? startDate : endDate,
      purposeCode: leaveTypeCode === 'PERSONAL' && purposeCode ? purposeCode : undefined,
      requestedAsEmergency: leaveTypeCode === 'PERSONAL' ? Boolean(requestedAsEmergency) : undefined,
      hasAttachment: Boolean(attachmentFile),
      depth: 'QUICK',
    };
  }, [employeeId, leaveTypeCode, startDate, endDate, subDay, purposeCode, requestedAsEmergency, attachmentFile]);
  const debouncedStep2Params = useDebouncedValue(step2Params, 400);
  const step2PreviewQuery = useQuery({
    queryKey: queryKeys.leavePreview(debouncedStep2Params ?? {}),
    queryFn: ({ signal }) => api.leave.preview(debouncedStep2Params, { signal }).then((r) => r.preview),
    enabled: step === 2 && Boolean(debouncedStep2Params),
  });
  const step2Preview = step2PreviewQuery.data ?? null;
  const step2Blocking = step2Preview?.blocking ?? null;
  const step2BlockingTarget = step2Blocking ? step2FieldTarget(step2Blocking.code) : null;
  const step2Fetching = step2PreviewQuery.isFetching;
  // Same "not every failure is a LeaveRuleOutcome" gap as step 1's TypeChoice -- see
  // previewErrorMessage's own comment.
  const step2Error = step2PreviewQuery.isError ? previewErrorMessage(step2PreviewQuery.error) : null;

  const previewCounters = step2Preview?.counters
    ?? (eligibilityByCode.get(leaveTypeCode)?.data?.counters)
    ?? null;

  // --- Step 2: calendar context (holidays + resolved schedule), so "why is this N days and not
  // the full calendar span" is answered next to the date fields instead of only showing up as a
  // smaller totalDays number. WINDOW CHOICE: exactly the currently-selected [startDate, endDate]
  // -- not a fixed lookahead -- because the note only needs to explain the range the employee has
  // actually picked; a wider window would show non-working days the request never touches. Debounced
  // same as step2Params so a date still being typed doesn't fire one request per keystroke.
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
      endDate: subDay ? startDate : endDate,
      purposeCode: leaveTypeCode === 'PERSONAL' && purposeCode ? purposeCode : undefined,
      requestedAsEmergency: leaveTypeCode === 'PERSONAL' ? Boolean(requestedAsEmergency) : undefined,
      hasAttachment: Boolean(attachmentFile),
      depth: 'FULL',
    };
  }, [step, employeeId, leaveTypeCode, startDate, endDate, subDay, purposeCode, requestedAsEmergency, attachmentFile]);
  const step3PreviewQuery = useQuery({
    queryKey: queryKeys.leavePreview(step3Params ?? {}),
    queryFn: ({ signal }) => api.leave.preview(step3Params, { signal }).then((r) => r.preview),
    enabled: Boolean(step3Params),
  });
  const step3Preview = step3PreviewQuery.data ?? null;
  const step3Blocking = step3Preview?.blocking ?? null;
  // Same "not every failure is a LeaveRuleOutcome" gap as step 1/2 -- see previewErrorMessage's
  // own comment. This is the terminal, submit-gating checkpoint: an errored FULL preview must
  // keep the submit button disabled exactly like a real `blocking` verdict would, not silently
  // read as a clean pass because `step3Blocking` happens to be null on an errored query.
  const step3Error = step3PreviewQuery.isError ? previewErrorMessage(step3PreviewQuery.error) : null;

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
    // imageCompression() returns a plain Blob, not a File -- it does not carry `file.name`
    // through, so the FormData part built from it defaults to the literal filename "blob" per the
    // Fetch/FormData spec. Re-wrapping in a File restores the original name. PDFs skip
    // compression entirely and never hit this path. See #498/#504 for the reference fix.
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
        showToast('success', 'ส่งคำขอลาและอนุมัติอัตโนมัติแล้ว');
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

  const formSubDay = subDay;

  // HARDENING, not a fix for a live bug (HTML implicit submission, fix/form-enter-submits-real-
  // records): this <form> wraps all 3 steps and is not otherwise gated by `step`, so it is safe
  // today only because three facts happen to stack up, none of them load-bearing by design. Step
  // 1's choices are all `<button type="button">` (zero fields that block implicit submission).
  // Step 2 always renders at least two blocking fields (startDate + endDate, or four once sub-day
  // adds startTime/endTime) — notably `endDate` stays MOUNTED (merely `disabled`) rather than
  // removed from the DOM when sub-day is checked, which is what keeps that count at 2 instead of
  // dropping to 1. Step 3 always renders the one real `<Button type="submit">` in this form.
  // Change any one of those three — e.g. unmounting `endDate` instead of disabling it — and Enter
  // in whatever single text field remained would silently file a real leave request, the same as
  // the tax-allowance bug this gate shape is copied from (TaxAllowanceForm.jsx's
  // `handleFormSubmit`, #tax-allowance-ia-hub-review), and jsdom would not catch it — it does not
  // implement implicit submission at all (see LeaveRequestPage.test.jsx's `fireEvent.submit(form)`
  // cases, which exercise the same 'submit' event a real Enter keypress produces). Gating
  // explicitly here removes the dependency on those three accidents continuing to hold.
  function handleFormSubmit(event) {
    if (step !== 3) {
      event.preventDefault();
      return;
    }
    handleSubmit(onSubmit)(event);
  }

  return (
    <PageStack className="relative">
      <PageHeader
        title="ยื่นคำขอลา"
        subtitle="ระบบตรวจเงื่อนไขทั้งหมดให้ก่อนคุณกดส่ง จะได้ไม่ต้องแก้แล้วแก้อีก"
        breadcrumbs={[{ label: 'การลา', onClick: () => goBackToSurface(false) }, { label: 'ยื่นคำขอลา' }]}
      />

      <form onSubmit={handleFormSubmit} noValidate className="grid gap-[18px]">
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
            {/* Single aria-live region for every dynamic verdict step 2 produces (per-field FormField
                hint/error text below, plus the one LeaveRulePanel this response can ever carry) --
                "polite", never "assertive": this updates on every keystroke via the 400ms debounce
                and would machine-gun a screen reader otherwise. aria-busy mirrors the in-flight
                fetch so a screen reader knows a re-check is running, not that nothing changed. */}
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

              <div className="grid grid-cols-2 gap-4 max-[720px]:grid-cols-1">
                <FormField label="วันที่เริ่ม" htmlFor="leave-start-date" error={startDateError} required>
                  <input
                    id="leave-start-date"
                    type="date"
                    {...register('startDate', { onChange: handleStartDateChange })}
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
                    min={startDate}
                    disabled={formSubDay}
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

              {/* Calendar context (#leave-calendar-context, Phase C): why totalDays can be smaller
                  than the calendar span -- lists which selected dates are holidays/non-working per
                  the employee's OWN resolved schedule, not a full calendar widget. */}
              {calendarContextParams ? (
                <div className={formGridSpan2}>
                  {nonWorkingDates.length > 0 ? (
                    <p className="m-0 rounded-lg border border-warning-border bg-warning-bg-soft px-3 py-2 text-xs text-warning-dark">
                      ช่วงที่เลือกมี {nonWorkingDates.length} วันที่ไม่นับเป็นวันทำงาน (วันหยุด/วันหยุดประจำสัปดาห์
                      ตามตารางเวลาทำงานของคุณ): {nonWorkingDates.map((date) => formatDate(date)).join(', ')}
                      {holidaysInRange.length > 0 ? (
                        <> — วันหยุด: {holidaysInRange.map((h) => `${formatDate(h.holidayDate)} (${h.nameTh})`).join(', ')}</>
                      ) : null}
                    </p>
                  ) : !calendarContextQuery.isFetching && calendarContextQuery.data ? (
                    <p className="m-0 text-xs text-muted">ทุกวันในช่วงที่เลือกเป็นวันทำงานตามตารางเวลาของคุณ</p>
                  ) : null}
                </div>
              ) : null}

              <div className={formGridSpan2}>
                <label className="flex cursor-pointer items-center gap-2 text-sm font-medium">
                  <input type="checkbox" className="h-4 w-4 shrink-0" {...register('subDay', { onChange: handleSubDayToggle })} />
                  ลาบางส่วนของวัน (ระบุเวลา)
                </label>
              </div>
              {formSubDay ? (
                <div className="grid grid-cols-2 gap-4 max-[720px]:grid-cols-1">
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
                </div>
              ) : null}

              {/* Field-order fix (Phase A2 brief): เหตุผลการลา is required and used to sit last,
                  after eight optional fields -- moved directly under the dates it belongs with. */}
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
                    {/* FormField's own `label` (not an inline <label> wrapping the checkbox) --
                        its id-match augmentation only inspects DIRECT children, so the <input>
                        must be the direct child for the hint/error aria-describedby wiring below
                        to actually land on the control a screen reader focuses, not on a wrapper. */}
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
                        {...register('requestedAsEmergency')}
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

              {!step2Blocking && step2Preview ? (
                <div className={formGridSpan2}>
                  <p className="m-0 text-sm font-semibold text-success-dark">
                    ผ่านเงื่อนไขที่ตรวจแบบเร็วแล้ว ({formatDays(step2Preview.totalDays)})
                  </p>
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

              {/* Terminal, blocking verdict: role="alert" (unlike step 2's polite live region) --
                  this is the final word, requires action, and does not update on every keystroke. */}
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

              {!step3Blocking && step3Preview ? (
                <div className="grid gap-3 rounded-md border border-border-subtle bg-surface-subtle p-3">
                  <div className="grid grid-cols-2 gap-3 text-sm max-[720px]:grid-cols-1">
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
                  {/* MILITARY's annualQuotaDays is a 366 sentinel ("no annual ceiling; the 60-day
                      paid cap is the real rule", V120) -- QuotaBar returns null when cap == null,
                      exactly right for it. The paid cap itself is rendered by hand below instead. */}
                  <QuotaBar
                    label={balance.type.nameTh}
                    caption={`โควตา${balance.type.nameTh} (วัน)`}
                    used={step3Preview ? Number(step3Preview.totalDays || 0) : 0}
                    cap={balance.type.code === 'MILITARY' ? null : balance.type.annualQuotaDays}
                    formatValue={(value) => new Intl.NumberFormat('th-TH', { maximumFractionDigits: 2 }).format(Number(value) || 0)}
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
                <div className="grid grid-cols-2 gap-4 max-[720px]:grid-cols-1">
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
                ส่งคำขอ
              </Button>
            </div>
          </Panel>
        ) : null}
      </form>
    </PageStack>
  );
}
