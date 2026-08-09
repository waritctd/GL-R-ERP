import { useEffect, useMemo, useRef, useState } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm, useWatch } from 'react-hook-form';
import { z } from 'zod';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { CompactStatRow } from '../../components/common/CompactStatRow.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { FormField, fieldErrorId } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { formGridSpan2, Panel, PageStack, RowActions } from '../../components/common/Layout.jsx';
import { SafeForm } from '../../components/common/SafeForm.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { UpcomingHolidays } from '../../components/common/UpcomingHolidays.jsx';
import {
  addDaysIso as addDaysToIso,
  formatBangkokTime,
  overtimeStatusLabel as statusInfo,
  pendingApproverText,
} from '../../utils/format.js';

const OVERTIME_TABLE_GRID = 'grid-cols-[minmax(0,1.15fr)_minmax(0,1.35fr)_minmax(0,1.35fr)_minmax(0,1fr)_minmax(0,1.15fr)_minmax(0,0.75fr)] nav-drawer:min-w-[940px] reflow-cards';
// FilterBar (Layout.jsx) renders a <div>; this form needs native submit semantics
// (Enter-to-submit on the search button), so its exact utility string is reproduced
// here rather than wrapping a <form> inside a non-form primitive.
//
// `items-end`, not `items-center` — see MyLeaveTab.jsx's identical FILTER_BAR_CLASS
// for the full explanation. This form is unconditional (renders for every viewer,
// including employee self-view), so the stagger was live on the OT tab of "คำขอ".
const FILTER_BAR_CLASS = 'flex flex-wrap gap-[10px] items-end bg-surface border border-border rounded-md p-[14px]';
// FormGrid (Layout.jsx) renders a <div>; the submit form needs to be a native <form>
// for onSubmit/noValidate, so its exact (2-column) utility string is reproduced here.
const FORM_GRID_CLASS = 'grid gap-[14px] mobile:grid-cols-1 grid-cols-2';

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

const addDaysIso = (days) => {
  const date = new Date(`${todayIso()}T00:00:00+07:00`);
  date.setUTCDate(date.getUTCDate() + days);
  return dateIso(date);
};

const monthStartIso = () => {
  const parts = bangkokDateParts();
  return `${parts.year}-${parts.month}-01`;
};

function defaultForm(employeeId = '') {
  // Same-day is the common case now that advance notice is gone.
  const date = todayIso();
  return {
    employeeId: employeeId ? String(employeeId) : '',
    workDate: date,
    startTime: '18:00',
    endDate: date,
    endTime: '20:00',
    dayType: 'WORKDAY',
    reason: '',
  };
}

const OT_TIME_RANGE_MESSAGE = 'เวลาสิ้นสุดต้องอยู่หลังเวลาเริ่ม';
// A2: mirrors OvertimeService#validatePlannedWindow's ≤24h guard -- วันที่สิ้นสุด can only be
// วันที่ทำ OT itself (same-day OT) or the very next day (an overnight window past midnight).
const OT_END_DATE_RANGE_MESSAGE = 'วันที่สิ้นสุดต้องเป็นวันที่ทำ OT หรือวันถัดไปเท่านั้น';

// Mirrors OvertimeService: advance notice was removed, so the only limits on a backdated request
// are how far back it may reach and that the reason explains why it is late.
export const OT_RETROACTIVE_WINDOW_DAYS = 60;
export const OT_BACKDATED_REASON_MIN_LENGTH = 20;
const OT_WINDOW_MESSAGE = `ย้อนหลังได้ไม่เกิน ${OT_RETROACTIVE_WINDOW_DAYS} วัน`;
const OT_BACKDATED_REASON_MESSAGE =
  `กรุณาอธิบายเหตุผลที่ขอย้อนหลังให้ชัดเจน (อย่างน้อย ${OT_BACKDATED_REASON_MIN_LENGTH} ตัวอักษร)`;

export function createOvertimeFormSchema({
  requireEmployeeId,
  retroactiveWindowDays = OT_RETROACTIVE_WINDOW_DAYS,
} = {}) {
  return z.object({
    employeeId: z.string(),
    workDate: z.string().min(1, 'กรุณาเลือกวันที่ทำ OT'),
    startTime: z.string().min(1, 'กรุณาเลือกเวลาเริ่ม'),
    endDate: z.string().min(1, 'กรุณาเลือกวันที่สิ้นสุด'),
    endTime: z.string().min(1, 'กรุณาเลือกเวลาสิ้นสุด'),
    dayType: z.enum(['WORKDAY', 'HOLIDAY']),
    reason: z.string().min(1, 'กรุณาระบุเหตุผลความจำเป็น'),
  }).superRefine((data, context) => {
    if (requireEmployeeId && !data.employeeId) {
      context.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['employeeId'],
        message: 'กรุณาเลือกพนักงาน',
      });
    }
    // Composed as plain "YYYY-MM-DDTHH:MM" strings -- comparable lexicographically the same way the
    // old single datetime-local pair was, just built from four separate fields now.
    const start = data.workDate && data.startTime ? `${data.workDate}T${data.startTime}` : '';
    const end = data.endDate && data.endTime ? `${data.endDate}T${data.endTime}` : '';
    if (start && end && end <= start) {
      context.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['endTime'],
        message: OT_TIME_RANGE_MESSAGE,
      });
    }
    // Mirrors OvertimeService#validatePlannedWindow's new ≤24h guard -- see
    // OT_END_DATE_RANGE_MESSAGE above.
    if (data.workDate && data.endDate
      && (data.endDate < data.workDate || data.endDate > addDaysToIso(data.workDate, 1))) {
      context.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['endDate'],
        message: OT_END_DATE_RANGE_MESSAGE,
      });
    }
    if (data.workDate && data.workDate < todayIso()) {
      if (data.workDate < addDaysIso(-retroactiveWindowDays)) {
        context.addIssue({
          code: z.ZodIssueCode.custom,
          path: ['workDate'],
          message: OT_WINDOW_MESSAGE,
        });
      }
      if (data.reason.trim().length < OT_BACKDATED_REASON_MIN_LENGTH) {
        context.addIssue({
          code: z.ZodIssueCode.custom,
          path: ['reason'],
          message: OT_BACKDATED_REASON_MESSAGE,
        });
      }
    }
  });
}

function apiDateTime(value) {
  return value.length === 16 ? `${value}:00+07:00` : `${value}+07:00`;
}

function formatWorkDate(value) {
  if (!value) return '-';
  const date = new Date(`${value}T00:00:00+07:00`);
  if (Number.isNaN(date.getTime())) return '-';
  return new Intl.DateTimeFormat('th-TH', {
    dateStyle: 'medium',
    timeZone: 'Asia/Bangkok',
  }).format(date);
}

function formatDateTime(value) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '-';
  return new Intl.DateTimeFormat('th-TH', {
    dateStyle: 'medium',
    timeStyle: 'short',
    hour12: false,
    timeZone: 'Asia/Bangkok',
  }).format(date);
}

function formatMinutes(value) {
  const minutes = Number(value || 0);
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  if (hours <= 0) return `${rest} นาที`;
  if (rest === 0) return `${hours} ชม.`;
  return `${hours} ชม. ${rest} นาที`;
}

// A2: true when a request's plannedEndAt falls on a different Bangkok-local calendar date than
// workDate -- i.e. an overnight window (e.g. 22:00 -> next-day 02:00). Compares against the
// request's OWN workDate rather than re-deriving "today", since this is about the window's shape,
// not whether the request is backdated.
function isOvernightWindow(request) {
  if (!request.plannedEndAt || !request.workDate) return false;
  const endDate = new Date(request.plannedEndAt);
  if (Number.isNaN(endDate.getTime())) return false;
  return dateIso(endDate) !== request.workDate;
}

// feat/ot-nonworkday-rate-suggestion: request.calculationNote carries either the
// calendar-unverified flag or the claim-disagreement flag as a bracketed prefix (see
// OvertimeService#resolveDayTypeSubmitNote) -- once approved, that flag is PREPENDED to the
// approval-time note (preserveDayTypeClaimFlag), so this still detects it post-approval. Also
// true when the CURRENT (read-time) suggestion has since drifted from what is stored -- e.g. HR
// corrected the calendar/schedule after submission. Checked as a prefix, not an exact string
// match: the exact Thai text is not a stable contract.
function hasDayTypeFlag(request) {
  return (Boolean(request.calculationNote) && request.calculationNote.trimStart().startsWith('['))
    || (Boolean(request.suggestedDayType) && request.suggestedDayType !== request.dayType);
}

// Debounces `value` by `delayMs` -- mirrors LeaveRequestPage's identically-named private helper
// (same calendar-context endpoint, same reason: don't fire one request per keystroke). Kept as
// its own small local copy rather than a new shared hook module for a four-line function two
// call sites use.
function useDebouncedValue(value, delayMs) {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);
  return debounced;
}

export function OvertimePanel({ user, currentEmployee, showToast }) {
  const queryClient = useQueryClient();
  const initialFilters = {
    from: monthStartIso(),
    to: addDaysIso(30),
    employeeId: '',
    status: '',
  };
  const [filters, setFilters] = useState(initialFilters);
  const [appliedFilters, setAppliedFilters] = useState(initialFilters);
  const [confirmState, setConfirmState] = useState(null);

  // --- Reads (TanStack Query) ---
  const employeesQuery = useQuery({
    queryKey: queryKeys.overtimeEmployees(),
    queryFn: () => api.overtime.employees().then((response) => response.employees || []),
  });
  const employeeOptions = useMemo(() => employeesQuery.data ?? [], [employeesQuery.data]);

  // Issue #422 B4: a modest poll + window-focus refetch on this queue specifically -- OT
  // approvals are genuinely multi-user (an approval made in another session must show up here
  // without a manual reload). The app-wide `refetchOnWindowFocus: false` default
  // (api/queryClient.js) stays put; this is a per-query opt-in, not a default flip.
  const requestsQuery = useQuery({
    queryKey: queryKeys.overtimeRequests(appliedFilters),
    queryFn: () => api.overtime.list({
      from: appliedFilters.from,
      to: appliedFilters.to,
      status: appliedFilters.status,
      ...(appliedFilters.employeeId ? { employeeId: appliedFilters.employeeId } : {}),
    }).then((response) => response.requests || []),
    refetchInterval: 60_000,
    refetchOnWindowFocus: true,
  });
  const requests = useMemo(() => requestsQuery.data ?? [], [requestsQuery.data]);
  const loading = requestsQuery.isLoading || requestsQuery.isFetching;

  // Preserve the original error-toast behavior of the imperative loaders.
  useEffect(() => {
    if (employeesQuery.error) showToast('error', employeesQuery.error.message || 'โหลดรายชื่อพนักงานสำหรับ OT ไม่สำเร็จ');
  }, [employeesQuery.error, showToast]);
  useEffect(() => {
    if (requestsQuery.error) showToast('error', requestsQuery.error.message || 'โหลดข้อมูล OT ไม่สำเร็จ');
  }, [requestsQuery.error, showToast]);

  const submitEmployeeOptions = useMemo(
    () => employeeOptions.filter((employee) => employee.self || employee.directReport),
    [employeeOptions],
  );
  const canSubmitForTeam = submitEmployeeOptions.some((employee) => employee.directReport);
  const hasMultipleEmployeeOptions = employeeOptions.length > 1;
  const hasMultipleSubmitOptions = submitEmployeeOptions.length > 1;
  const overtimeFormSchema = useMemo(
    () => createOvertimeFormSchema({ requireEmployeeId: hasMultipleSubmitOptions }),
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
    resolver: zodResolver(overtimeFormSchema),
    defaultValues: defaultForm(currentEmployee?.id || user.employeeId || ''),
    mode: 'onChange',
    reValidateMode: 'onChange',
  });
  const [selectedWorkDate, startTime, selectedEndDate, endTime, selectedEmployeeId, selectedDayType] = useWatch({
    control,
    name: ['workDate', 'startTime', 'endDate', 'endTime', 'employeeId', 'dayType'],
  });
  const isBackdated = Boolean(selectedWorkDate) && selectedWorkDate < todayIso();
  // Composed the same way submitOvertime composes the real payload -- comparing the two "date+time"
  // strings lexicographically is equivalent to comparing them as instants, since both share the
  // same fixed +07:00 offset (Thailand has no DST) and the same zero-padded width.
  const hasTimeRangeError = Boolean(
    selectedWorkDate && startTime && selectedEndDate && endTime
      && `${selectedEndDate}T${endTime}` <= `${selectedWorkDate}T${startTime}`,
  );
  const plannedEndError = hasTimeRangeError ? OT_TIME_RANGE_MESSAGE : errors.endTime?.message;

  // feat/ot-nonworkday-rate-suggestion: filing on someone else's behalf must not preview using
  // the ACTOR's own schedule -- /api/leave/calendar-context is self-scoped
  // (LeaveCalendarContextService.java:42-45), so a manager filing for a subordinate would
  // otherwise get back their own resolved schedule, not the subordinate's. '' (not yet resolved)
  // defaults to "self", matching this form's own default filing target.
  const isFilingForSelf = !selectedEmployeeId || Number(selectedEmployeeId) === Number(user.employeeId);

  // Suggestion-verdict lookup for the currently selected วันที่ทำ OT (#ot-holiday-visibility, PR 2).
  // Debounced like LeaveRequestPage's own calendar-context read (same endpoint) so typing into
  // the date field doesn't fire one request per keystroke. Scoped to the single selected date,
  // NOT the ~90-day-forward window UpcomingHolidays shows above -- a backdated work date (up to
  // OT_RETROACTIVE_WINDOW_DAYS in the past) falls outside that window, and this must still answer
  // correctly for it.
  const debouncedVerdictWorkDate = useDebouncedValue(selectedWorkDate, 400);
  const dayTypeVerdictQuery = useQuery({
    queryKey: queryKeys.leaveCalendarContext(debouncedVerdictWorkDate, debouncedVerdictWorkDate),
    queryFn: () => api.leave.calendarContext({ from: debouncedVerdictWorkDate, to: debouncedVerdictWorkDate })
      .then((response) => response.calendarContext),
    enabled: Boolean(debouncedVerdictWorkDate) && isFilingForSelf,
  });
  // nonWorkingDates, not holidays[0] -- the schedule-aware answer (a holiday OR the caller's own
  // resolved non-workday), mirroring OvertimeService#suggestDayType's exact predicate
  // (LeaveCalendarContextDto.java:20-25). holidays is still read, but only to show the specific
  // hr.holiday NAME when one exists for this date -- it is no longer what decides the verdict.
  const verdictNonWorkingDates = dayTypeVerdictQuery.data?.nonWorkingDates ?? [];
  const verdictHolidayMatch = (dayTypeVerdictQuery.data?.holidays ?? [])
    .find((holiday) => holiday.holidayDate === debouncedVerdictWorkDate) ?? null;
  const verdictResolved = Boolean(debouncedVerdictWorkDate) && isFilingForSelf && dayTypeVerdictQuery.isSuccess;
  const verdictIsNonWorkday = verdictResolved && verdictNonWorkingDates.includes(debouncedVerdictWorkDate);

  // Pre-flight ONLY -- a prediction of OvertimeService#suggestDayType (backend) and its mock
  // mirror, never a substitute for it. Owner ruling 2026-08-08: NEITHER direction is refused
  // anymore -- the employee's field is a REQUEST, pre-filled with the suggestion but freely
  // editable, and the approver (not this badge) decides what becomes pay. Both directions are
  // therefore informational, not a warning that submit will fail.
  const dayTypeMismatch = !verdictResolved ? null
    : verdictIsNonWorkday && selectedDayType === 'WORKDAY'
      ? { kind: 'under-claim', nameTh: verdictHolidayMatch?.nameTh }
      : !verdictIsNonWorkday && selectedDayType === 'HOLIDAY'
        ? { kind: 'over-claim' }
        : null;

  // feat/ot-nonworkday-rate-suggestion: ประเภท OT pre-fills the suggestion once it resolves for
  // the current วันที่ทำ OT/employee, mirroring what the approve dialog defaults its own selector
  // to. Auto-fills ONLY while the field is still PRISTINE for this (date, employee) pair --
  // "dirtied" tracks whether the employee has already made their own choice, which is a REQUEST
  // that may deliberately disagree with the suggestion (owner ruling 2026-08-08) and must never be
  // silently overwritten out from under them once made.
  const dayTypeDirtyRef = useRef(false);
  useEffect(() => {
    dayTypeDirtyRef.current = false;
  }, [selectedWorkDate, selectedEmployeeId]);
  useEffect(() => {
    if (!verdictResolved || dayTypeDirtyRef.current) return;
    setValue('dayType', verdictIsNonWorkday ? 'HOLIDAY' : 'WORKDAY');
  }, [verdictResolved, verdictIsNonWorkday, setValue]);

  const totals = useMemo(() => {
    const submitted = requests.filter((request) => request.status === 'SUBMITTED').length;
    const managerApproved = requests.filter((request) => request.status === 'MANAGER_APPROVED').length;
    const approved = requests.filter((request) => request.status === 'APPROVED');
    const payableMinutes = approved.reduce((sum, request) => sum + Number(request.payableMinutes || 0), 0);
    return { submitted, managerApproved, approved: approved.length, payableMinutes };
  }, [requests]);

  // Seed the acting employee once the option list lands (preserves pre-Query behavior).
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

  // Issue #422 B3 fix: invalidate the ['overtime'] PREFIX, not the exact
  // queryKeys.overtimeRequests(appliedFilters) -- every other filter combination (a different
  // date range/status/employee a sibling tab or another reviewer has open) used to stay stale
  // until its own filters were reapplied.
  function invalidateOvertime() {
    return queryClient.invalidateQueries({ queryKey: ['overtime'] });
  }

  // Issue #422 B5 fix: approve/reject each change line.overtimePay, a figure PayrollPage reads,
  // with no way for that screen to find out short of a manual reload. Invalidating the
  // ['payroll'] prefix alongside invalidateOvertime() lets an open PayrollPage tab pick the
  // change up on its own next poll/focus refetch (issue #422 B1).
  function invalidatePayrollUpstream() {
    return queryClient.invalidateQueries({ queryKey: ['payroll'] });
  }

  function updateFilter(field, value) {
    setFilters((current) => ({ ...current, [field]: value }));
  }

  function submitFilters(event) {
    event.preventDefault();
    setAppliedFilters(filters);
  }

  const createMutation = useMutation({
    mutationFn: (payload) => api.overtime.create(payload).then((response) => response.request),
    onSuccess: () => {
      reset(defaultForm(currentEmployee?.id || user.employeeId || ''));
      showToast('success', 'ส่งคำขอ OT แล้ว');
      invalidateOvertime();
    },
    onError: (error) => showToast('error', error.message || 'ส่งคำขอ OT ไม่สำเร็จ'),
  });
  // feat/ot-nonworkday-rate-suggestion: the day-type claim can no longer 400 at submit (owner
  // ruling 2026-08-08 -- a disagreeing claim is accepted and flagged, never refused), so the
  // inline field-level surfacing this used to need (isDayTypeClaimError and the createMutation
  // .reset() effect keyed on selectedWorkDate/selectedDayType) is dead code and was removed. The
  // toast in onError above already covers every submit failure that remains possible.

  const approveMutation = useMutation({
    mutationFn: ({ id, dayType }) => api.overtime.approve(id, dayType ? { dayType } : {}).then((response) => response.request),
    onSuccess: () => {
      showToast('success', 'อนุมัติ OT แล้ว');
      setConfirmState(null);
      invalidateOvertime();
      invalidatePayrollUpstream();
    },
    onError: (error) => showToast('error', error.message || 'อนุมัติ OT ไม่สำเร็จ'),
  });

  const rejectMutation = useMutation({
    mutationFn: ({ id, reviewerNote }) => api.overtime.reject(id, { reviewerNote }).then((response) => response.request),
    onSuccess: () => {
      showToast('success', 'ปฏิเสธคำขอ OT แล้ว');
      setConfirmState(null);
      invalidatePayrollUpstream();
      invalidateOvertime();
    },
    onError: (error) => showToast('error', error.message || 'ปฏิเสธ OT ไม่สำเร็จ'),
  });

  const cancelMutation = useMutation({
    mutationFn: ({ id, reviewerNote }) => api.overtime.cancel(id, { reviewerNote: reviewerNote?.trim() || null }).then((response) => response.request),
    onSuccess: () => {
      showToast('success', 'ยกเลิกคำขอ OT แล้ว');
      setConfirmState(null);
      invalidateOvertime();
    },
    onError: (error) => showToast('error', error.message || 'ยกเลิก OT ไม่สำเร็จ'),
  });

  const saving = createMutation.isPending || approveMutation.isPending
    || rejectMutation.isPending || cancelMutation.isPending;

  // Keeps วันที่สิ้นสุด inside the ≤24h window OvertimeService#validatePlannedWindow enforces (A2)
  // as วันที่ทำ OT changes. Extends LeaveRequestPage's handleStartDateChange (:507-512) idea -- that
  // version only ever needs to push endDate FORWARD, because leave's start/end pair has no upper
  // bound; OT's window is capped at workDate+1, so moving วันที่ทำ OT BACKWARD needs the same
  // treatment, or วันที่สิ้นสุด -- a field the employee never touched -- is left showing an error out
  // of nowhere.
  function handleWorkDateChange(event) {
    const value = event.target.value;
    const currentEndDate = getValues('endDate');
    if (currentEndDate < value || currentEndDate > addDaysToIso(value, 1)) {
      setValue('endDate', value, { shouldDirty: true, shouldValidate: true });
    }
  }

  function submitOvertime(values) {
    createMutation.mutate({
      employeeId: values.employeeId ? Number(values.employeeId) : null,
      workDate: values.workDate,
      plannedStartAt: apiDateTime(`${values.workDate}T${values.startTime}`),
      plannedEndAt: apiDateTime(`${values.endDate}T${values.endTime}`),
      dayType: values.dayType,
      reason: values.reason.trim(),
    });
  }

  // Approve now goes through a confirmation step (matches the reference
  // CommissionPage/TicketDetailPage pattern) instead of firing immediately,
  // so the reviewer sees what they're approving before committing to it.
  //
  // feat/ot-nonworkday-rate-suggestion: seeds confirmState.dayType with the suggestion ONLY when
  // there is something worth the approver's active attention -- a non-workday suggestion (3x,
  // real money) or an explicit flag note -- and ONLY on the first approval stage (SUBMITTED);
  // the second-stage CEO sign-off (MANAGER_APPROVED) can never override, matching the server's
  // freeze point (OvertimeService#ceoApprove never reads dayType). null means "no override to
  // offer" -- the dialog shows no selector and approveMutation sends no dayType, so the server
  // falls back to its own fresh suggestion.
  function approve(id) {
    const request = requests.find((item) => item.id === id);
    const canOverrideDayType = request?.status === 'SUBMITTED';
    const flagged = canOverrideDayType
      && (request.suggestedDayType === 'HOLIDAY' || hasDayTypeFlag(request));
    setConfirmState({ kind: 'approve', id, dayType: flagged ? (request.suggestedDayType || 'WORKDAY') : null });
  }

  function confirmApprove() {
    approveMutation.mutate({ id: confirmState.id, dayType: confirmState.dayType });
  }

  function reject(id) {
    setConfirmState({ kind: 'reject', id });
  }

  function confirmReject(reviewerNote) {
    if (!reviewerNote?.trim()) return;
    rejectMutation.mutate({ id: confirmState.id, reviewerNote: reviewerNote.trim() });
  }

  // A ฝ่าย manager's list is already scoped server-side to their division, so any request they see
  // (other than their own) is one they may review. reports_to is NOT consulted: it stopped granting
  // approval rights when overtime moved to the division-only rule attendance already used, and a
  // button shown on the strength of it would just 403.
  function managesRequest(request) {
    return Boolean(user.manager && Number(request.employeeId) !== Number(user.employeeId));
  }

  // hasManagerApprover === false means the server will route SUBMITTED straight to the CEO: either
  // the employee's ฝ่าย has no ผู้จัดการ, or they are one themselves. Mirrors
  // OvertimeService.approve()'s dispatch -- offering the wrong button here is a guaranteed 403.
  function hasManagerStage(request) {
    return request.hasManagerApprover !== false;
  }

  function canManagerApprove(request) {
    return request.status === 'SUBMITTED' && hasManagerStage(request) && managesRequest(request);
  }

  function canCeoApprove(request) {
    if (user.role !== 'ceo') return false;
    return request.status === 'MANAGER_APPROVED'
      || (request.status === 'SUBMITTED' && !hasManagerStage(request));
  }

  function canReviewRequest(request) {
    return canManagerApprove(request) || canCeoApprove(request);
  }

  function canManagerCancel(request) {
    return ['SUBMITTED', 'MANAGER_APPROVED', 'APPROVED'].includes(request.status) && managesRequest(request);
  }

  // Always confirm before cancelling -- matches the self-cancel pattern in
  // MyLeaveTab.requestCancel and SpecialMoneyPanel.cancel. The row action is
  // already gated by (canCancel || managerCancellable), so eligibility is
  // decided at render time; this function no longer needs to re-check it.
  function cancel(id) {
    setConfirmState({ kind: 'cancel', id });
  }

  function doCancel(id, reviewerNote) {
    cancelMutation.mutate({ id, reviewerNote });
  }

  // UpcomingHolidays' own window: "next ~90 days" per the design brief -- independent of the
  // requests-list filter range above (appliedFilters), which exists for a different purpose
  // (browsing past/pending OT requests, not "what's coming up").
  const holidayWindowFrom = todayIso();
  const holidayWindowTo = addDaysIso(90);

  return (
    <PageStack>
      {/*
        No PageHeader here: this is a tab inside RequestsPage, which owns the page title. Two
        stacked headers is what you get if this panel keeps its own.
      */}
      <div className="flex items-center justify-between gap-3">
        <p className="m-0 text-sm text-text-muted">
          {canSubmitForTeam ? 'ยื่นคำขอแทนทีมและอนุมัติจากเวลาสแกนจริง' : 'ยื่นคำขอ OT และดูประวัติของคุณ'}
        </p>
        <Button type="button" variant="secondary" onClick={() => requestsQuery.refetch()} disabled={loading}>
          <Icon name="refresh" />
          รีเฟรช
        </Button>
      </div>

      <CompactStatRow
        items={[
          { key: 'total', label: 'คำขอทั้งหมด', value: requests.length, helper: 'ในช่วงที่เลือก' },
          // A1: this bucket mixes BOTH routes (some SUBMITTED rows wait on a manager, some go
          // straight to the CEO -- see overtimeStatusLabel's own comment), so it must not name
          // only one of the two possible holders.
          { key: 'submitted', label: 'รออนุมัติ', value: totals.submitted, helper: 'Submitted' },
          { key: 'managerApproved', label: 'รอ CEO', value: totals.managerApproved, helper: 'Manager approved' },
          { key: 'approved', label: 'อนุมัติแล้ว', value: totals.approved, helper: 'Approved' },
          { key: 'payable', label: 'ชั่วโมงจ่ายได้', value: formatMinutes(totals.payableMinutes), helper: 'Approved payable' },
        ]}
      />

      <SafeForm className={FILTER_BAR_CLASS} onSubmit={submitFilters}>
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
            <option value="MANAGER_APPROVED">รอ CEO</option>
            <option value="APPROVED">อนุมัติแล้ว</option>
            <option value="REJECTED">ปฏิเสธแล้ว</option>
            <option value="CANCELLED">ยกเลิกแล้ว</option>
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
        <Button type="submit" disabled={loading}>
          <Icon name="search" />
          ค้นหา
        </Button>
      </SafeForm>

      {/* Holiday answer arrives BEFORE the claim is made (#ot-holiday-visibility): this panel sits
          above the whole submit form, not just above the day-type select, so an employee can see
          which upcoming days are company holidays before they even open the date field below. */}
      <UpcomingHolidays from={holidayWindowFrom} to={holidayWindowTo} />

      <Panel title="ยื่นคำขอ OT">
        <SafeForm className={FORM_GRID_CLASS} onSubmit={handleSubmit(submitOvertime)} noValidate>
          {hasMultipleSubmitOptions ? (
            <FormField label="พนักงาน" htmlFor="ot-employee" error={errors.employeeId?.message}>
              <select
                id="ot-employee"
                {...register('employeeId')}
                value={selectedEmployeeId ?? ''}
                onChange={(event) => setValue('employeeId', event.target.value, { shouldDirty: true, shouldValidate: true })}
                aria-invalid={Boolean(errors.employeeId)}
                aria-describedby={errors.employeeId ? fieldErrorId('ot-employee') : undefined}
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
            <FormField label="พนักงาน" htmlFor="ot-employee-display">
              <input id="ot-employee-display" value={currentEmployee?.nameTh || user.name || '-'} disabled />
            </FormField>
          )}
          <FormField
            label="วันที่ทำ OT"
            htmlFor="ot-work-date"
            error={errors.workDate?.message}
            hint={isBackdated ? 'คำขอย้อนหลัง — โปรดระบุเหตุผลให้ชัดเจน' : undefined}
            required
          >
            <input
              id="ot-work-date"
              type="date"
              min={addDaysIso(-OT_RETROACTIVE_WINDOW_DAYS)}
              {...register('workDate', { onChange: handleWorkDateChange })}
              aria-invalid={Boolean(errors.workDate)}
              required
            />
          </FormField>

          {/* Verdict badge (#ot-holiday-visibility): resolves the SAME schedule/holiday predicate
              OvertimeService#suggestDayType uses server-side (feat/ot-nonworkday-rate-suggestion:
              a non-workday per the caller's OWN resolved schedule, not just a recorded holiday),
              for the currently selected วันที่ทำ OT above -- so the answer is in front of the
              employee before they touch ประเภท OT below. This is a SUGGESTION only -- the
              approver's decision is what becomes pay. aria-live announces it updating as the date
              field changes, matching LeaveRequestPage's identical convention for its own dynamic
              calendar-context note. */}
          <div className={formGridSpan2} aria-live="polite" aria-busy={dayTypeVerdictQuery.isFetching}>
            {!isFilingForSelf ? (
              // /api/leave/calendar-context is self-scoped -- filing for someone else previews
              // nothing rather than the wrong employee's schedule (see isFilingForSelf's comment).
              <span className="inline-flex w-fit items-center gap-1.5 rounded-full border border-dashed border-border-muted bg-surface px-3 py-1.5 text-xs font-medium text-text-muted">
                <Icon name="info" size={13} />
                ระบบจะเสนออัตราให้เมื่อส่งคำขอ
              </span>
            ) : verdictIsNonWorkday ? (
              // The name is deliberately OUTSIDE the pill. `hr.holiday.name_th` is not a short
              // label: production's 19 BANK rows average 35 chars and reach **149**
              // ("ชดเชยวันคล้ายวันพระบรมราชสมภพ ... (วันเสาร์ที่ 5 ธันวาคม 2569)"), which is why V129
              // had to widen the column from VARCHAR(120) to TEXT. Interpolated into the pill it
              // either stretched it off-grid or wrapped inside a `rounded-full`, which reads as
              // broken -- a pill shape asserts "one short line" and a 149-char label breaks that
              // promise. Pill keeps the fixed verdict + rate; the variable-length name sits beside
              // it as ordinary text that is allowed to wrap. No hr.holiday row explains a
              // schedule-only non-workday (a weekend), so the name is omitted then.
              <span className="flex flex-wrap items-center gap-x-2 gap-y-1">
                <span className="inline-flex w-fit shrink-0 items-center gap-1.5 rounded-full border border-warning-border bg-warning-bg-soft px-3 py-1.5 text-xs font-bold text-warning-dark">
                  <Icon name="triangleAlert" size={13} />
                  {verdictHolidayMatch ? 'วันหยุดบริษัท · 3x' : 'วันหยุดประจำสัปดาห์ · 3x'}
                </span>
                {verdictHolidayMatch ? (
                  <span className="min-w-0 text-xs text-text-muted">{verdictHolidayMatch.nameTh}</span>
                ) : null}
              </span>
            ) : verdictResolved ? (
              <span className="inline-flex w-fit items-center gap-1.5 rounded-full border border-border bg-surface-subtle px-3 py-1.5 text-xs font-bold text-text-muted">
                <Icon name="check" size={13} />
                วันทำงานปกติ · 1.5x
              </span>
            ) : (
              <span className="inline-flex w-fit items-center gap-1.5 rounded-full border border-dashed border-border-muted bg-surface px-3 py-1.5 text-xs font-medium text-text-muted">
                <Icon name="clock" size={13} />
                ปฏิทินยังไม่ได้โหลด
              </span>
            )}
          </div>

          <FormField label="ประเภท OT" htmlFor="ot-day-type" error={errors.dayType?.message} required>
            <select
              id="ot-day-type"
              {...register('dayType')}
              value={selectedDayType ?? ''}
              onChange={(event) => {
                // The employee is making their OWN choice from here on -- see dayTypeDirtyRef's
                // comment: the suggestion-fill effect must not overwrite it again for this date.
                dayTypeDirtyRef.current = true;
                setValue('dayType', event.target.value, { shouldDirty: true, shouldValidate: true });
              }}
              aria-invalid={Boolean(errors.dayType)}
              aria-describedby={errors.dayType ? fieldErrorId('ot-day-type') : undefined}
            >
              <option value="WORKDAY">วันทำงานปกติ · 1.5x</option>
              <option value="HOLIDAY">วันหยุด/วันหยุดนักขัตฤกษ์ · 3x</option>
            </select>
          </FormField>

          {/* Pre-flight ONLY -- see dayTypeMismatch's own comment above. Neither direction is
              refused anymore (owner ruling 2026-08-08): both are flagged for the approver to
              review, and the request is always accepted. */}
          {dayTypeMismatch ? (
            <div className={formGridSpan2}>
              <p className="m-0 rounded-lg border border-warning-border bg-warning-bg-soft px-3 py-2 text-xs text-warning-dark">
                {dayTypeMismatch.kind === 'over-claim'
                  ? 'วันที่เลือกไม่ใช่วันหยุดตามปฏิทินหรือตารางเวลาทำงานของคุณ — ระบบจะแนะนำผู้อนุมัติให้ใช้อัตรา 1.5x แม้เลือก "วันหยุด/วันหยุดนักขัตฤกษ์" ไว้ ผู้อนุมัติเป็นผู้ตัดสินใจอัตราสุดท้าย'
                  : `วันที่เลือกเป็นวันหยุด${dayTypeMismatch.nameTh ? ` (${dayTypeMismatch.nameTh})` : 'ตามตารางเวลาทำงานของคุณ'} — ระบบจะแนะนำผู้อนุมัติให้ใช้อัตรา 3x แม้เลือก "วันทำงานปกติ" ไว้ ผู้อนุมัติเป็นผู้ตัดสินใจอัตราสุดท้าย`}
              </p>
            </div>
          ) : null}

          {/* A2: two dates + two times, mirroring LeaveRequestPage's own start/end fields --
              never datetime-local. วันที่ทำ OT above already carries the date the employee is
              filing for; these three fields answer "what time did it start" / "does it cross
              midnight" / "what time did it end", without asking for the same date a third time. */}
          <FormField label="เวลาเริ่ม" htmlFor="ot-start-time" error={errors.startTime?.message} required>
            <input
              id="ot-start-time"
              type="time"
              {...register('startTime')}
              className={errors.startTime ? 'border-danger focus:border-danger focus:[box-shadow:0_0_0_3px_var(--color-danger-bg)]' : ''}
              aria-invalid={Boolean(errors.startTime)}
              aria-describedby={errors.startTime ? fieldErrorId('ot-start-time') : undefined}
              required
            />
          </FormField>
          <FormField
            label="วันที่สิ้นสุด"
            htmlFor="ot-end-date"
            error={errors.endDate?.message}
            required
          >
            <input
              id="ot-end-date"
              type="date"
              min={selectedWorkDate}
              // Mirrors the zod ≤24h check above in the native picker itself. Guarded: before the
              // form settles (or if it's ever cleared) selectedWorkDate is '', and addDaysToIso('')
              // would split into NaN/undefined date parts -- undefined here just means "no cap yet",
              // never a broken one.
              max={selectedWorkDate ? addDaysToIso(selectedWorkDate, 1) : undefined}
              {...register('endDate')}
              aria-invalid={Boolean(errors.endDate)}
              aria-describedby={errors.endDate ? fieldErrorId('ot-end-date') : undefined}
              required
            />
          </FormField>
          <FormField
            label="เวลาสิ้นสุด"
            htmlFor="ot-end-time"
            error={plannedEndError}
            required
          >
            <input
              id="ot-end-time"
              type="time"
              {...register('endTime')}
              className={plannedEndError ? 'border-danger focus:border-danger focus:[box-shadow:0_0_0_3px_var(--color-danger-bg)]' : ''}
              aria-invalid={Boolean(plannedEndError)}
              aria-describedby={plannedEndError ? fieldErrorId('ot-end-time') : undefined}
              required
            />
          </FormField>
          <div className={formGridSpan2}>
            <FormField label="เหตุผลความจำเป็น" htmlFor="ot-reason" error={errors.reason?.message} required>
              <textarea
                id="ot-reason"
                className={errors.reason ? 'border-danger focus:border-danger focus:[box-shadow:0_0_0_3px_var(--color-danger-bg)]' : ''}
                rows={3}
                {...register('reason')}
                aria-invalid={Boolean(errors.reason)}
                aria-describedby={errors.reason ? fieldErrorId('ot-reason') : undefined}
                required
              />
            </FormField>
          </div>
          <RowActions className={formGridSpan2}>
            <Button type="submit" disabled={saving || hasTimeRangeError} className="mobile:min-h-11 mobile:w-full">
              <Icon name="plus" />
              ส่งคำขอ
            </Button>
          </RowActions>
        </SafeForm>
      </Panel>

      <Panel flush>
        <div className={`${OVERTIME_TABLE_GRID} table-head`}>
          <span>วันที่ / พนักงาน</span>
          <span>แผน OT</span>
          <span>เหตุผล</span>
          <span>เวลาจริง / จ่ายได้</span>
          <span>ขั้นอนุมัติ</span>
          <span />
        </div>
        {loading ? (
          <EmptyState icon="clock" title="กำลังโหลดคำขอ OT" />
        ) : requests.length === 0 ? (
          <EmptyState icon="clock" title="ยังไม่มีคำขอ OT" description="ลองเปลี่ยนช่วงวันที่หรือยื่นคำขอใหม่" />
        ) : requests.map((request) => {
          const status = statusInfo(request.status, request.pendingApproverRole);
          const isPending = request.status === 'SUBMITTED' || request.status === 'MANAGER_APPROVED';
          const pendingApproverNote = isPending
            ? pendingApproverText(request.pendingApproverRole, request.pendingApproverName)
            : null;
          const reviewable = canReviewRequest(request);
          const approveTitle = canCeoApprove(request) ? 'CEO อนุมัติ' : 'ผู้จัดการอนุมัติ';
          const canCancel = request.status === 'SUBMITTED' && Number(request.employeeId) === Number(user.employeeId);
          const managerCancellable = canManagerCancel(request);
          return (
            <div className={`${OVERTIME_TABLE_GRID} data-row`} key={request.id}>
              {/* Mobile order (step 8 rule 7 — summary -> actions -> details): this
                  span is the summary (who/when) and stays first on every viewport. */}
              <span data-label="วันที่ / พนักงาน" className="mobile:order-1">
                <strong>{formatWorkDate(request.workDate)}</strong>
                <small>{request.employeeName || request.employeeCode || request.employeeId}</small>
              </span>
              {/* A2: times only -- the "วันที่ / พนักงาน" column to the left already prints
                  วันที่ทำ OT once, so repeating it here would be the same date shown twice in one
                  row. "(+1 วัน)" is the only date-shaped thing this column ever adds, and only for
                  the overnight case where plannedEndAt genuinely lands on a different day. */}
              <span data-label="แผน OT" className="mobile:order-4">
                <strong>
                  {formatBangkokTime(request.plannedStartAt)}–{formatBangkokTime(request.plannedEndAt)} น.
                  {isOvernightWindow(request) ? ' (+1 วัน)' : ''}
                </strong>
                <small>
                  {formatMinutes(request.plannedMinutes)} · {request.dayType === 'HOLIDAY' ? '3x' : '1.5x'}
                  {/* feat/ot-nonworkday-rate-suggestion: surfaces BEFORE approval that the system
                      disagrees with the stored/requested rate -- either the employee's own claim
                      disagreed with the suggestion at submit time, or the suggestion has since
                      drifted (a calendar/schedule correction). Only meaningful while a decision is
                      still pending; once approved/rejected/cancelled the approver's (or nobody's)
                      call is already final. */}
                  {isPending && hasDayTypeFlag(request) ? (
                    <span
                      className="ml-1 inline-flex items-center gap-1 text-warning-dark"
                      title={request.calculationNote || 'ระบบแนะนำอัตราอื่นจากที่ระบุไว้ — โปรดตรวจสอบก่อนอนุมัติ'}
                    >
                      <Icon name="triangleAlert" size={11} />
                      {/* The stored rate at submit is always the SUGGESTION, never the employee's
                          raw claim (see OvertimeService#submit's SECURITY comment) -- so
                          suggestedDayType usually already equals the rate shown just above, and
                          repeating "แนะนำ 3x" beside an already-3x rate would read as a contradiction
                          that isn't there. Only name the suggested rate when it has actually
                          DRIFTED from what is stored (a calendar/schedule correction since
                          submission/approval); otherwise a plain "ตรวจสอบ" is honest about there
                          being a flag (see the tooltip/calculationNote for what it is) without
                          implying the visible rate is wrong. */}
                      {request.suggestedDayType && request.suggestedDayType !== request.dayType
                        ? `แนะนำ ${request.suggestedDayType === 'HOLIDAY' ? '3x' : '1.5x'}`
                        : 'ตรวจสอบ'}
                    </span>
                  ) : null}
                </small>
              </span>
              <span data-label="เหตุผล" className="mobile:order-5">
                <strong>{request.reason}</strong>
                <small>{request.reviewerNote || request.calculationNote || '-'}</small>
              </span>
              {/* Pure-numeric column: right-aligned + mono on desktop so the figures
                  line up for scanning (DESIGN.md mono is for "figures needing
                  alignment"). Left on mobile, where it reads as a stacked card. */}
              <span data-label="เวลาจริง / จ่ายได้" className="mobile:order-6 min-[721px]:text-right">
                <strong className="font-mono">{formatMinutes(request.actualMinutes)}</strong>
                <small className="font-mono">จ่ายได้ {formatMinutes(request.payableMinutes)}</small>
              </span>
              <span data-label="ขั้นอนุมัติ" className="mobile:order-2">
                <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
                {/* No "รอ" prefix -- the StatusBadge above already says that (รอผู้จัดการ/รอ CEO);
                    repeating it made this note a superset of the badge's own text, which broke an
                    e2e locator matching the badge's exact label (review #pending-approver-info). */}
                {pendingApproverNote ? <small className="text-text-muted">{pendingApproverNote}</small> : null}
                {/* A1: only print the ผู้จัดการ audit line when this request actually HAS a
                    manager stage (hasManagerStage mirrors OvertimeService.approve()'s own
                    routing). On the manager-less route this slot could never fill -- it always
                    read "-" -- which is exactly the contradiction the owner's screenshot showed:
                    a badge naming CEO sitting next to an audit line naming a manager who never
                    existed for this request. */}
                {hasManagerStage(request) ? (
                  <small>ผู้จัดการ: {request.managerApprovedAt ? `${request.managerApprovedByName || '-'} · ${formatDateTime(request.managerApprovedAt)}` : '-'}</small>
                ) : null}
                <small>CEO: {request.ceoApprovedAt ? `${request.ceoApprovedByName || '-'} · ${formatDateTime(request.ceoApprovedAt)}` : '-'}</small>
              </span>
              {/* Approve/reject visually differentiated per DESIGN.md (danger stays
                  outlined, not filled) and step 9 rule 2 — mirrors the exact
                  success/danger icon-button tinting CommissionPage uses for the
                  same manager/CEO review pattern. */}
              <RowActions className="mobile:order-3 mobile:flex-wrap">
                {reviewable ? (
                  <>
                    <Button
                      type="button"
                      variant="icon"
                      title={approveTitle}
                      aria-label={approveTitle}
                      disabled={saving}
                      style={{ color: 'var(--color-success)', borderColor: 'var(--color-success)' }}
                      onClick={() => approve(request.id)}
                    >
                      <Icon name="check" size={14} />
                    </Button>
                    <Button
                      type="button"
                      variant="icon"
                      title="ปฏิเสธ"
                      aria-label="ปฏิเสธ"
                      disabled={saving}
                      style={{ color: 'var(--color-danger)', borderColor: 'var(--color-danger)' }}
                      onClick={() => reject(request.id)}
                    >
                      <Icon name="close" size={14} />
                    </Button>
                  </>
                ) : null}
                {(canCancel || managerCancellable) ? (
                  <Button type="button" variant="icon" title="ยกเลิก" aria-label="ยกเลิก" disabled={saving} onClick={() => cancel(request.id)}>
                    <Icon name="close" size={14} />
                  </Button>
                ) : null}
              </RowActions>
            </div>
          );
        })}
      </Panel>

      <ConfirmDialog
        open={confirmState?.kind === 'approve'}
        title="ยืนยันการอนุมัติ OT"
        message={(() => {
          const request = requests.find((item) => item.id === confirmState?.id);
          if (!request) return 'ยืนยันการอนุมัติคำขอ OT นี้?';
          const isCeoStep = canCeoApprove(request);
          const isDirectCeoStep = isCeoStep && request.status === 'SUBMITTED';
          // feat/ot-nonworkday-rate-suggestion: only the FIRST approval stage (SUBMITTED) may set
          // the rate -- the freeze point does not move, so the second-stage CEO sign-off
          // (MANAGER_APPROVED) shows no selector and always inherits what was already frozen,
          // matching OvertimeService#ceoApprove never reading dayType.
          const canOverrideDayType = request.status === 'SUBMITTED';
          const flagged = canOverrideDayType
            && (request.suggestedDayType === 'HOLIDAY' || hasDayTypeFlag(request));
          // The rate that will ACTUALLY apply once this approval completes: the approver's live
          // selection when one is on offer, else whatever is already frozen/suggested.
          const effectiveDayType = (canOverrideDayType && confirmState?.dayType) || request.dayType;
          // Three outcomes, because there are three routes. Quoted from api.overtime.approve
          // (src/api/mockApi.js): the SUBMITTED->MANAGER_APPROVED step computes
          // payableMinutes = round(actualMinutes * multiplier) right there; the
          // MANAGER_APPROVED->APPROVED (CEO) step only flips status and keeps the minutes the
          // manager step already computed. The manager-less route does BOTH at once, so its copy
          // has to promise the calculation as well as the final status -- telling a CEO their
          // one click "only flips the status" would be wrong on the one route that pays out.
          const rateLabel = effectiveDayType === 'HOLIDAY' ? '3x (วันหยุด)' : '1.5x (วันทำงานปกติ)';
          let nextStep;
          if (isDirectCeoStep) {
            // A3: state only the outcome -- the employee-facing/CEO-facing notifications for this
            // same route dropped the "no supervisor step" framing too (OvertimeService#notifySubmitted).
            nextStep = `สถานะจะเปลี่ยนเป็น "อนุมัติแล้ว" และคำนวณชั่วโมงจ่ายได้ตามอัตรา ${rateLabel}`;
          } else if (isCeoStep) {
            nextStep = 'สถานะจะเปลี่ยนเป็น "อนุมัติแล้ว" พร้อมชั่วโมงจ่ายได้ที่คำนวณไว้';
          } else {
            nextStep = `สถานะจะเปลี่ยนเป็น "รอ CEO" และคำนวณชั่วโมงจ่ายได้ตามอัตรา ${rateLabel}`;
          }
          return (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              <p className="confirm-dialog-message text-text-secondary leading-normal" style={{ margin: 0 }}>
                ตรวจสอบแผน OT ของ <strong>{request.employeeName || request.employeeCode || request.employeeId}</strong> ก่อนอนุมัติ
              </p>
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13, borderTop: '1px solid var(--color-border)', paddingTop: 8 }}>
                <span style={{ color: 'var(--color-icon-muted)' }}>วันที่ / เวลา</span>
                {/* A2: the date shows exactly ONCE here (nowhere else in this dialog prints it) --
                    the old `.slice(-5)` hack existed only because plannedEndAt was being run
                    through the full date+time formatter a second time just to throw the date part
                    away. "(+1 วัน)" replaces that hack for the one case where the end genuinely
                    lands on a different day. */}
                <span className="font-mono">
                  {formatWorkDate(request.workDate)} · {formatBangkokTime(request.plannedStartAt)}–{formatBangkokTime(request.plannedEndAt)} น.
                  {isOvernightWindow(request) ? ' (+1 วัน)' : ''}
                </span>
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 14, fontWeight: 700 }}>
                <span>เวลาที่วางแผน</span>
                <span className="font-mono">{formatMinutes(request.plannedMinutes)} · {effectiveDayType === 'HOLIDAY' ? '3x' : '1.5x'}</span>
              </div>
              {/* feat/ot-nonworkday-rate-suggestion: the approver's DECISION -- shown only when
                  there is something worth an active choice (a non-workday suggestion, or an
                  explicit flag), and only on the stage that can still set the rate at all.
                  Defaults to the suggestion; downgrading/upgrading from there is an explicit,
                  visible act, never a silent default. */}
              {flagged ? (
                <label className="flex flex-col gap-1 border-t border-border pt-2 text-sm">
                  <span className="font-medium text-text">
                    ระบบตรวจพบว่าวันนี้อาจเป็นวันหยุด — เลือกอัตราที่ถูกต้องก่อนอนุมัติ
                  </span>
                  <select
                    value={confirmState?.dayType || 'WORKDAY'}
                    onChange={(event) => setConfirmState((current) => ({ ...current, dayType: event.target.value }))}
                    aria-label="อัตราที่จะอนุมัติ"
                  >
                    <option value="WORKDAY">วันทำงานปกติ · 1.5x</option>
                    <option value="HOLIDAY">วันหยุด/วันหยุดนักขัตฤกษ์ · 3x</option>
                  </select>
                  {request.calculationNote ? (
                    <small className="text-text-muted">{request.calculationNote}</small>
                  ) : null}
                </label>
              ) : null}
              <p style={{ margin: 0, fontSize: 12, color: 'var(--color-icon-muted)' }}>{nextStep}</p>
            </div>
          );
        })()}
        confirmLabel="อนุมัติ"
        busy={saving}
        onConfirm={confirmApprove}
        onCancel={() => setConfirmState(null)}
      />
      <ConfirmDialog
        open={confirmState?.kind === 'reject'}
        title="ปฏิเสธคำขอ OT"
        message='ยืนยันการปฏิเสธคำขอ OT นี้? สถานะจะเปลี่ยนเป็น "ปฏิเสธแล้ว" และไม่สามารถอนุมัติย้อนหลังได้'
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
        title="ยกเลิกคำขอ OT"
        message='ยืนยันการยกเลิกคำขอ OT นี้? สถานะจะเปลี่ยนเป็น "ยกเลิกแล้ว" ถาวร'
        confirmLabel="ยกเลิกคำขอ"
        tone="danger"
        busy={saving}
        requireReason
        optionalReason
        reasonLabel="หมายเหตุการยกเลิก (ถ้ามี)"
        onConfirm={(reason) => doCancel(confirmState.id, reason)}
        onCancel={() => setConfirmState(null)}
      />
    </PageStack>
  );
}
