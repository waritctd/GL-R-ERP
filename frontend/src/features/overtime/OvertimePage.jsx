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
import { FormField, fieldErrorId } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { formGridSpan2, Panel, PageStack, RowActions, StatGrid } from '../../components/common/Layout.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatCard } from '../../components/common/StatCard.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';

const OVERTIME_TABLE_GRID = 'grid-cols-[minmax(0,1.15fr)_minmax(0,1.35fr)_minmax(0,1.35fr)_minmax(0,1fr)_minmax(0,1.15fr)_minmax(0,0.75fr)] max-[1040px]:min-w-[940px] reflow-cards';
// FilterBar (Layout.jsx) renders a <div>; this form needs native submit semantics
// (Enter-to-submit on the search button), so its exact utility string is reproduced
// here rather than wrapping a <form> inside a non-form primitive.
const FILTER_BAR_CLASS = 'flex flex-wrap gap-[10px] items-center bg-surface border border-border rounded-md p-[14px]';
// FormGrid (Layout.jsx) renders a <div>; the submit form needs to be a native <form>
// for onSubmit/noValidate, so its exact (2-column) utility string is reproduced here.
const FORM_GRID_CLASS = 'grid gap-[14px] max-[720px]:grid-cols-1 grid-cols-2';

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
  const date = addDaysIso(3);
  return {
    employeeId: employeeId ? String(employeeId) : '',
    workDate: date,
    plannedStartAt: `${date}T18:00`,
    plannedEndAt: `${date}T20:00`,
    dayType: 'WORKDAY',
    reason: '',
  };
}

const OT_TIME_RANGE_MESSAGE = 'เวลาสิ้นสุดต้องอยู่หลังเวลาเริ่ม';

function createOvertimeFormSchema({ requireEmployeeId }) {
  return z.object({
    employeeId: z.string(),
    workDate: z.string().min(1, 'กรุณาเลือกวันที่ทำ OT'),
    plannedStartAt: z.string().min(1, 'กรุณาเลือกเวลาเริ่ม'),
    plannedEndAt: z.string().min(1, 'กรุณาเลือกเวลาสิ้นสุด'),
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
    if (data.plannedStartAt && data.plannedEndAt && data.plannedEndAt <= data.plannedStartAt) {
      context.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['plannedEndAt'],
        message: OT_TIME_RANGE_MESSAGE,
      });
    }
  });
}

function statusInfo(status) {
  const map = {
    SUBMITTED: { label: 'รอผู้จัดการ', tone: 'warning' },
    MANAGER_APPROVED: { label: 'รอ CEO', tone: 'info' },
    APPROVED: { label: 'อนุมัติแล้ว', tone: 'success' },
    REJECTED: { label: 'ปฏิเสธแล้ว', tone: 'danger' },
    CANCELLED: { label: 'ยกเลิกแล้ว', tone: 'neutral' },
  };
  return map[status] ?? { label: status || '-', tone: 'neutral' };
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

export function OvertimePage({ user, currentEmployee, showToast }) {
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

  const requestsQuery = useQuery({
    queryKey: queryKeys.overtimeRequests(appliedFilters),
    queryFn: () => api.overtime.list({
      from: appliedFilters.from,
      to: appliedFilters.to,
      status: appliedFilters.status,
      ...(appliedFilters.employeeId ? { employeeId: appliedFilters.employeeId } : {}),
    }).then((response) => response.requests || []),
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
    () => employeeOptions.filter((employee) => employee.self || employee.directReport || user.role === 'admin'),
    [employeeOptions, user.role],
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
  const [plannedStartAt, plannedEndAt, selectedEmployeeId, selectedDayType] = useWatch({
    control,
    name: ['plannedStartAt', 'plannedEndAt', 'employeeId', 'dayType'],
  });
  const hasTimeRangeError = Boolean(
    plannedStartAt && plannedEndAt && plannedEndAt <= plannedStartAt,
  );
  const plannedEndError = hasTimeRangeError ? OT_TIME_RANGE_MESSAGE : errors.plannedEndAt?.message;

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

  function invalidateOvertime() {
    return queryClient.invalidateQueries({ queryKey: queryKeys.overtimeRequests(appliedFilters) });
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

  const approveMutation = useMutation({
    mutationFn: (id) => api.overtime.approve(id, {}).then((response) => response.request),
    onSuccess: () => {
      showToast('success', 'อนุมัติ OT แล้ว');
      invalidateOvertime();
    },
    onError: (error) => showToast('error', error.message || 'อนุมัติ OT ไม่สำเร็จ'),
  });

  const rejectMutation = useMutation({
    mutationFn: ({ id, reviewerNote }) => api.overtime.reject(id, { reviewerNote }).then((response) => response.request),
    onSuccess: () => {
      showToast('success', 'ปฏิเสธคำขอ OT แล้ว');
      setConfirmState(null);
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

  function handleWorkDateChange(event) {
    const value = event.target.value;
    const startTime = getValues('plannedStartAt').slice(11) || '18:00';
    const endTime = getValues('plannedEndAt').slice(11) || '20:00';
    setValue('plannedStartAt', `${value}T${startTime}`, { shouldDirty: true, shouldValidate: true });
    setValue('plannedEndAt', `${value}T${endTime}`, { shouldDirty: true, shouldValidate: true });
  }

  function submitOvertime(values) {
    createMutation.mutate({
      employeeId: values.employeeId ? Number(values.employeeId) : null,
      workDate: values.workDate,
      plannedStartAt: apiDateTime(values.plannedStartAt),
      plannedEndAt: apiDateTime(values.plannedEndAt),
      dayType: values.dayType,
      reason: values.reason.trim(),
    });
  }

  function approve(id) {
    approveMutation.mutate(id);
  }

  function reject(id) {
    setConfirmState({ kind: 'reject', id });
  }

  function confirmReject(reviewerNote) {
    if (!reviewerNote?.trim()) return;
    rejectMutation.mutate({ id: confirmState.id, reviewerNote: reviewerNote.trim() });
  }

  // A ฝ่าย manager's list is already scoped server-side to their division, so any request
  // they see (other than their own) is one they may review; direct reports-to managers too.
  function managesRequest(request) {
    const directManager = request.managerEmployeeId
      && Number(request.managerEmployeeId) === Number(user.employeeId);
    const divisionManager = user.manager
      && Number(request.employeeId) !== Number(user.employeeId);
    return Boolean(directManager || divisionManager);
  }

  function canManagerApprove(request) {
    return request.status === 'SUBMITTED' && managesRequest(request);
  }

  function canCeoApprove(request) {
    return request.status === 'MANAGER_APPROVED' && user.role === 'ceo';
  }

  function canReviewRequest(request) {
    return canManagerApprove(request) || canCeoApprove(request);
  }

  function canManagerCancel(request) {
    return ['SUBMITTED', 'MANAGER_APPROVED', 'APPROVED'].includes(request.status) && managesRequest(request);
  }

  function cancel(id) {
    const request = requests.find((item) => item.id === id);
    if (request && canManagerCancel(request)) {
      setConfirmState({ kind: 'cancel', id });
      return;
    }
    doCancel(id, '');
  }

  function doCancel(id, reviewerNote) {
    cancelMutation.mutate({ id, reviewerNote });
  }

  return (
    <PageStack>
      <PageHeader
        title="จัดการล่วงเวลา"
        subtitle={canSubmitForTeam ? 'ยื่นคำขอแทนทีมและอนุมัติจากเวลาสแกนจริง' : 'ยื่นคำขอ OT และดูประวัติของคุณ'}
        actions={(
          <Button type="button" variant="secondary" onClick={() => requestsQuery.refetch()} disabled={loading}>
            <Icon name="refresh" />
            รีเฟรช
          </Button>
        )}
      />

      <StatGrid>
        <StatCard label="คำขอทั้งหมด" value={requests.length} helper="ในช่วงที่เลือก" icon="clock" tone="indigo" />
        <StatCard label="รอผู้จัดการ" value={totals.submitted} helper="Submitted" icon="clipboard" tone="amber" />
        <StatCard label="รอ CEO" value={totals.managerApproved} helper="Manager approved" icon="clipboard" tone="indigo" />
        <StatCard label="อนุมัติแล้ว" value={totals.approved} helper="Approved" icon="check" tone="teal" />
        <StatCard label="ชั่วโมงจ่ายได้" value={formatMinutes(totals.payableMinutes)} helper="Approved payable" icon="calendar" tone="blue" />
      </StatGrid>

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
            <option value="SUBMITTED">รอผู้จัดการ</option>
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
      </form>

      <Panel title="ยื่นคำขอ OT">
        <form className={FORM_GRID_CLASS} onSubmit={handleSubmit(submitOvertime)} noValidate>
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
          <FormField label="วันที่ทำ OT" htmlFor="ot-work-date" error={errors.workDate?.message}>
            <input
              id="ot-work-date"
              type="date"
              {...register('workDate', { onChange: handleWorkDateChange })}
              aria-invalid={Boolean(errors.workDate)}
              aria-describedby={errors.workDate ? fieldErrorId('ot-work-date') : undefined}
              required
            />
          </FormField>
          <FormField label="ประเภท OT" htmlFor="ot-day-type" error={errors.dayType?.message}>
            <select
              id="ot-day-type"
              {...register('dayType')}
              value={selectedDayType ?? ''}
              onChange={(event) => setValue('dayType', event.target.value, { shouldDirty: true, shouldValidate: true })}
              aria-invalid={Boolean(errors.dayType)}
              aria-describedby={errors.dayType ? fieldErrorId('ot-day-type') : undefined}
            >
              <option value="WORKDAY">วันทำงานปกติ · 1.5x</option>
              <option value="HOLIDAY">วันหยุด/วันหยุดนักขัตฤกษ์ · 3x</option>
            </select>
          </FormField>
          <FormField label="เริ่ม" htmlFor="ot-planned-start" error={errors.plannedStartAt?.message}>
            <input
              id="ot-planned-start"
              type="datetime-local"
              {...register('plannedStartAt')}
              className={errors.plannedStartAt ? 'is-invalid' : ''}
              aria-invalid={Boolean(errors.plannedStartAt)}
              aria-describedby={errors.plannedStartAt ? fieldErrorId('ot-planned-start') : undefined}
              required
            />
          </FormField>
          <FormField
            label="สิ้นสุด"
            htmlFor="ot-planned-end"
            error={plannedEndError}
          >
            <input
              id="ot-planned-end"
              type="datetime-local"
              {...register('plannedEndAt')}
              className={plannedEndError ? 'is-invalid' : ''}
              aria-invalid={Boolean(plannedEndError)}
              aria-describedby={plannedEndError ? fieldErrorId('ot-planned-end') : undefined}
              required
            />
          </FormField>
          <div className={formGridSpan2}>
            <FormField label="เหตุผลความจำเป็น" htmlFor="ot-reason" error={errors.reason?.message}>
              <textarea
                id="ot-reason"
                className={errors.reason ? 'is-invalid' : ''}
                rows={3}
                {...register('reason')}
                aria-invalid={Boolean(errors.reason)}
                aria-describedby={errors.reason ? fieldErrorId('ot-reason') : undefined}
                required
              />
            </FormField>
          </div>
          <RowActions className={formGridSpan2}>
            <Button type="submit" disabled={saving || hasTimeRangeError}>
              <Icon name="plus" />
              ส่งคำขอ
            </Button>
          </RowActions>
        </form>
      </Panel>

      <section className="table-panel">
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
          const status = statusInfo(request.status);
          const reviewable = canReviewRequest(request);
          const approveTitle = canCeoApprove(request) ? 'CEO อนุมัติ' : 'ผู้จัดการอนุมัติ';
          const canCancel = request.status === 'SUBMITTED' && Number(request.employeeId) === Number(user.employeeId);
          const managerCancellable = canManagerCancel(request);
          return (
            <div className={`${OVERTIME_TABLE_GRID} table-row`} key={request.id}>
              <span data-label="วันที่ / พนักงาน">
                <strong>{formatWorkDate(request.workDate)}</strong>
                <small>{request.employeeName || request.employeeCode || request.employeeId}</small>
              </span>
              <span data-label="แผน OT">
                <strong>{formatDateTime(request.plannedStartAt)}</strong>
                <small>
                  {formatDateTime(request.plannedEndAt)} · {formatMinutes(request.plannedMinutes)} · {request.dayType === 'HOLIDAY' ? '3x' : '1.5x'}
                </small>
              </span>
              <span data-label="เหตุผล">
                <strong>{request.reason}</strong>
                <small>{request.reviewerNote || request.calculationNote || '-'}</small>
              </span>
              <span data-label="เวลาจริง / จ่ายได้">
                <strong>{formatMinutes(request.actualMinutes)}</strong>
                <small>จ่ายได้ {formatMinutes(request.payableMinutes)}</small>
              </span>
              <span data-label="ขั้นอนุมัติ">
                <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
                <small>ผู้จัดการ: {request.managerApprovedAt ? `${request.managerApprovedByName || '-'} · ${formatDateTime(request.managerApprovedAt)}` : '-'}</small>
                <small>CEO: {request.ceoApprovedAt ? `${request.ceoApprovedByName || '-'} · ${formatDateTime(request.ceoApprovedAt)}` : '-'}</small>
              </span>
              <span className="row-actions">
                {reviewable ? (
                  <>
                    <Button type="button" variant="icon" title={approveTitle} aria-label={approveTitle} disabled={saving} onClick={() => approve(request.id)}>
                      <Icon name="check" size={14} />
                    </Button>
                    {canManagerApprove(request) ? (
                      <Button type="button" variant="icon" title="ปฏิเสธ" aria-label="ปฏิเสธ" disabled={saving} onClick={() => reject(request.id)}>
                        <Icon name="close" size={14} />
                      </Button>
                    ) : null}
                  </>
                ) : null}
                {(canCancel || managerCancellable) ? (
                  <Button type="button" variant="icon" title="ยกเลิก" aria-label="ยกเลิก" disabled={saving} onClick={() => cancel(request.id)}>
                    <Icon name="close" size={14} />
                  </Button>
                ) : null}
              </span>
            </div>
          );
        })}
      </section>

      <ConfirmDialog
        open={confirmState?.kind === 'reject'}
        title="ปฏิเสธคำขอ OT"
        message="ยืนยันการปฏิเสธคำขอ OT นี้?"
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
        message="ยืนยันการยกเลิกคำขอ OT นี้?"
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
