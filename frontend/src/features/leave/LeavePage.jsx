import { useEffect, useMemo, useState } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm, useWatch } from 'react-hook-form';
import { z } from 'zod';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { hasPermission } from '../../app/permissions.js';
import { Button } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { FormField, fieldErrorId } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { StatCard } from '../../components/common/StatCard.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';

function bangkokDateParts(date = new Date()) {
  return Object.fromEntries(new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Bangkok',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(date).map((part) => [part.type, part.value]));
}

const todayIso = () => {
  const parts = bangkokDateParts();
  return `${parts.year}-${parts.month}-${parts.day}`;
};

const monthStartIso = () => {
  const parts = bangkokDateParts();
  return `${parts.year}-${parts.month}-01`;
};

function yearFrom(dateString) {
  return Number((dateString || todayIso()).slice(0, 4));
}

function defaultForm(employeeId = '', leaveTypeCode = 'VACATION') {
  const date = todayIso();
  return {
    employeeId: employeeId ? String(employeeId) : '',
    leaveTypeCode,
    startDate: date,
    endDate: date,
    reason: '',
    attachmentName: '',
    attachmentUrl: '',
  };
}

const LEAVE_START_PAST_MESSAGE = 'วันที่เริ่มลาต้องไม่ก่อนวันนี้';

function createLeaveFormSchema({ requireEmployeeId, minStartDate }) {
  return z.object({
    employeeId: z.string(),
    leaveTypeCode: z.string().min(1, 'กรุณาเลือกประเภทการลา'),
    startDate: z.string().min(1, 'กรุณาเลือกวันที่เริ่ม'),
    endDate: z.string().min(1, 'กรุณาเลือกวันที่สิ้นสุด'),
    reason: z.string().min(1, 'กรุณาระบุเหตุผลการลา'),
    attachmentName: z.string(),
    attachmentUrl: z.string(),
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
  });
}

function statusInfo(status) {
  const map = {
    SUBMITTED: { label: 'รออนุมัติ', tone: 'warning' },
    APPROVED: { label: 'อนุมัติแล้ว', tone: 'success' },
    REJECTED: { label: 'ปฏิเสธแล้ว', tone: 'danger' },
    CANCELLED: { label: 'ยกเลิกแล้ว', tone: 'neutral' },
    AUTO_REJECTED: { label: 'โควตาไม่พอ', tone: 'danger' },
  };
  return map[status] ?? { label: status || '-', tone: 'neutral' };
}

function formatDate(value) {
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

function formatDateRange(start, end) {
  return start === end ? formatDate(start) : `${formatDate(start)} - ${formatDate(end)}`;
}

function formatDays(value) {
  const days = Number(value || 0);
  return `${new Intl.NumberFormat('th-TH', { maximumFractionDigits: 2 }).format(days)} วัน`;
}

export function LeavePage({ user, currentEmployee, showToast }) {
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

  const requestsQuery = useQuery({
    queryKey: queryKeys.leaveRequests(appliedFilters),
    queryFn: () => api.leave.list({
      from: appliedFilters.from,
      to: appliedFilters.to,
      status: appliedFilters.status,
      ...(appliedFilters.employeeId ? { employeeId: appliedFilters.employeeId } : {}),
    }).then((response) => response.requests || []),
  });
  const requests = useMemo(() => requestsQuery.data ?? [], [requestsQuery.data]);
  const loading = requestsQuery.isLoading || requestsQuery.isFetching;

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
  const [formEmployeeId, formStartDate, formLeaveTypeCode] = useWatch({
    control,
    name: ['employeeId', 'startDate', 'leaveTypeCode'],
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

  // Preserve the original error-toast behavior of the imperative loaders.
  useEffect(() => {
    if (employeesQuery.error) showToast('error', employeesQuery.error.message || 'โหลดข้อมูลตั้งต้นวันลาไม่สำเร็จ');
  }, [employeesQuery.error, showToast]);
  useEffect(() => {
    if (leaveTypesQuery.error) showToast('error', leaveTypesQuery.error.message || 'โหลดข้อมูลตั้งต้นวันลาไม่สำเร็จ');
  }, [leaveTypesQuery.error, showToast]);
  useEffect(() => {
    if (requestsQuery.error) showToast('error', requestsQuery.error.message || 'โหลดข้อมูลวันลาไม่สำเร็จ');
  }, [requestsQuery.error, showToast]);
  useEffect(() => {
    if (balancesQuery.error) showToast('error', balancesQuery.error.message || 'โหลดโควตาวันลาไม่สำเร็จ');
  }, [balancesQuery.error, showToast]);

  const canSubmitForTeam = submitEmployeeOptions.some((employee) => employee.directReport) || canReviewAll;
  const hasMultipleEmployeeOptions = employeeOptions.length > 1;

  const totals = useMemo(() => {
    const submitted = requests.filter((request) => request.status === 'SUBMITTED').length;
    const approved = requests.filter((request) => request.status === 'APPROVED');
    const approvedDays = approved.reduce((sum, request) => sum + Number(request.totalDays || 0), 0);
    const remainingDays = balances.reduce((sum, balance) => sum + Number(balance.remainingDays || 0), 0);
    return { submitted, approved: approved.length, approvedDays, remainingDays };
  }, [requests, balances]);

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

  function invalidateLeave() {
    return Promise.all([
      queryClient.invalidateQueries({ queryKey: queryKeys.leaveRequests(appliedFilters) }),
      queryClient.invalidateQueries({ queryKey: queryKeys.leaveBalances(formEmployeeId, balancesYear) }),
    ]);
  }

  function updateFilter(field, value) {
    setFilters((current) => ({ ...current, [field]: value }));
  }

  function handleStartDateChange(event) {
    const value = event.target.value;
    if (getValues('endDate') < value) {
      setValue('endDate', value, { shouldDirty: true, shouldValidate: true });
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
      if (created?.status === 'AUTO_REJECTED') {
        showToast('error', created.systemNote || 'โควตาวันลาไม่เพียงพอ');
      } else {
        showToast('success', 'ส่งคำขอลาแล้ว');
      }
      invalidateLeave();
    },
    onError: (error) => showToast('error', error.message || 'ส่งคำขอลาไม่สำเร็จ'),
  });

  const approveMutation = useMutation({
    mutationFn: (id) => api.leave.approve(id, {}).then((response) => response.request),
    onSuccess: () => {
      showToast('success', 'อนุมัติวันลาแล้ว');
      invalidateLeave();
    },
    onError: (error) => showToast('error', error.message || 'อนุมัติวันลาไม่สำเร็จ'),
  });

  const rejectMutation = useMutation({
    mutationFn: ({ id, reviewerNote }) => api.leave.reject(id, { reviewerNote }).then((response) => response.request),
    onSuccess: () => {
      showToast('success', 'ปฏิเสธคำขอลาแล้ว');
      setConfirmState(null);
      invalidateLeave();
    },
    onError: (error) => showToast('error', error.message || 'ปฏิเสธวันลาไม่สำเร็จ'),
  });

  const cancelMutation = useMutation({
    mutationFn: ({ id, reviewerNote }) => api.leave.cancel(id, { reviewerNote: reviewerNote?.trim() || null }).then((response) => response.request),
    onSuccess: () => {
      showToast('success', 'ยกเลิกคำขอลาแล้ว');
      setConfirmState(null);
      invalidateLeave();
    },
    onError: (error) => showToast('error', error.message || 'ยกเลิกวันลาไม่สำเร็จ'),
  });

  const saving = createMutation.isPending || approveMutation.isPending
    || rejectMutation.isPending || cancelMutation.isPending;

  function submitLeave(values) {
    createMutation.mutate({
      employeeId: values.employeeId ? Number(values.employeeId) : null,
      leaveTypeCode: values.leaveTypeCode,
      startDate: values.startDate,
      endDate: values.endDate,
      reason: values.reason.trim(),
      attachmentName: values.attachmentName.trim() || null,
      attachmentUrl: values.attachmentUrl.trim() || null,
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

  function canReviewRequest(request) {
    return request.status === 'SUBMITTED'
      && (canReviewAll || (request.managerEmployeeId && Number(request.managerEmployeeId) === Number(user.employeeId)));
  }

  function canManagerCancel(request) {
    return ['SUBMITTED', 'APPROVED'].includes(request.status)
      && (canReviewAll || (request.managerEmployeeId && Number(request.managerEmployeeId) === Number(user.employeeId)));
  }

  return (
    <div className="page-stack">
      <PageHeader
        title="จัดการการลา"
        subtitle={canSubmitForTeam ? 'ยื่นคำขอแทนทีม ตรวจโควตา และอนุมัติวันลา' : 'ยื่นคำขอลาและดูโควตาของคุณ'}
        actions={(
          <Button type="button" variant="secondary" onClick={() => requestsQuery.refetch()} disabled={loading}>
            <Icon name="refresh" />
            รีเฟรช
          </Button>
        )}
      />

      <section className="stat-grid">
        <StatCard label="คำขอทั้งหมด" value={requests.length} helper="ในช่วงที่เลือก" icon="clipboard" tone="indigo" />
        <StatCard label="รออนุมัติ" value={totals.submitted} helper="Submitted" icon="clock" tone="amber" />
        <StatCard label="อนุมัติแล้ว" value={totals.approved} helper={formatDays(totals.approvedDays)} icon="check" tone="teal" />
        <StatCard label="โควตาคงเหลือ" value={formatDays(totals.remainingDays)} helper="รวมประเภทที่เลือกได้" icon="calendar" tone="blue" />
      </section>

      <section className="panel">
        <div className="panel-header">
          <h2>โควตาวันลา</h2>
        </div>
        <div className="leave-balance-grid">
          {balances.length === 0 ? (
            <EmptyState icon="calendar" title="ยังไม่มีข้อมูลโควตา" />
          ) : balances.map((balance) => (
            <div className="leave-balance-card" key={balance.leaveTypeCode}>
              <span>{balance.leaveTypeNameTh || balance.leaveTypeCode}</span>
              <strong>{formatDays(balance.remainingDays)}</strong>
              <small>ใช้แล้ว {formatDays(balance.approvedDays)} · รออนุมัติ {formatDays(balance.pendingDays)} · สิทธิ์ {formatDays(balance.annualQuotaDays)}</small>
            </div>
          ))}
        </div>
      </section>

      <form className="filter-bar" onSubmit={submitFilters}>
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
        <Button type="submit" disabled={loading}>
          <Icon name="search" />
          ค้นหา
        </Button>
      </form>

      <section className="panel">
        <div className="panel-header">
          <h2>ยื่นคำขอลา</h2>
        </div>
        <form className="form-grid" onSubmit={handleSubmit(submitLeave)} noValidate>
          {hasMultipleSubmitOptions ? (
            <FormField label="พนักงาน" htmlFor="leave-employee" error={errors.employeeId?.message}>
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
          ) : (
            <FormField label="พนักงาน" htmlFor="leave-employee-display">
              <input id="leave-employee-display" value={currentEmployee?.nameTh || user.name || '-'} disabled />
            </FormField>
          )}
          <FormField label="ประเภทการลา" htmlFor="leave-type-code" error={errors.leaveTypeCode?.message}>
            <select
              id="leave-type-code"
              {...register('leaveTypeCode')}
              aria-invalid={Boolean(errors.leaveTypeCode)}
              aria-describedby={errors.leaveTypeCode ? fieldErrorId('leave-type-code') : undefined}
              required
            >
              {leaveTypes.map((type) => (
                <option key={type.code} value={type.code}>{type.nameTh || type.nameEn}</option>
              ))}
            </select>
          </FormField>
          <FormField
            label="วันที่เริ่ม"
            htmlFor="leave-start-date"
            error={startDateError}
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
          <FormField label="วันที่สิ้นสุด" htmlFor="leave-end-date" error={errors.endDate?.message}>
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
          <FormField label="ชื่อเอกสาร" htmlFor="leave-attachment-name">
            <input
              id="leave-attachment-name"
              {...register('attachmentName')}
              placeholder="Medical certificate.pdf"
            />
          </FormField>
          <FormField label="ลิงก์เอกสาร" htmlFor="leave-attachment-url">
            <input
              id="leave-attachment-url"
              {...register('attachmentUrl')}
              placeholder="https://..."
            />
          </FormField>
          <div className="span-2">
            <FormField label="เหตุผลการลา" htmlFor="leave-reason" error={errors.reason?.message}>
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
          <div className="span-2 row-actions">
            <Button type="submit" disabled={saving || startDateInPast}>
              <Icon name="plus" />
              ส่งคำขอ
            </Button>
          </div>
        </form>
      </section>

      <section className="panel">
        <div className="panel-header">
          <h2>ปฏิทินวันลา</h2>
        </div>
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
      </section>

      <section className="table-panel">
        <div className="leave-table table-head">
          <span>ช่วงลา / พนักงาน</span>
          <span>ประเภท / จำนวนวัน</span>
          <span>เหตุผล / เอกสาร</span>
          <span>สถานะ</span>
          <span>อนุมัติ / หมายเหตุ</span>
          <span />
        </div>
        {loading ? (
          <EmptyState icon="calendar" title="กำลังโหลดคำขอลา" />
        ) : requests.length === 0 ? (
          <EmptyState icon="calendar" title="ยังไม่มีคำขอลา" description="ลองเปลี่ยนช่วงวันที่หรือยื่นคำขอใหม่" />
        ) : requests.map((request) => {
          const status = statusInfo(request.status);
          const reviewable = canReviewRequest(request);
          const canCancel = request.status === 'SUBMITTED' && Number(request.employeeId) === Number(user.employeeId);
          const managerCancellable = canManagerCancel(request);
          return (
            <div className="leave-table table-row" key={request.id}>
              <span data-label="ช่วงลา / พนักงาน">
                <strong>{formatDateRange(request.startDate, request.endDate)}</strong>
                <small>{request.employeeName || request.employeeCode || request.employeeId}</small>
              </span>
              <span data-label="ประเภท / จำนวนวัน">
                <strong>{request.leaveTypeNameTh || request.leaveTypeCode}</strong>
                <small>{formatDays(request.totalDays)} · เหลือ {formatDays(request.quotaRemainingAfter)}</small>
              </span>
              <span data-label="เหตุผล / เอกสาร">
                <strong>{request.reason}</strong>
                <small>{request.attachmentName || request.attachmentUrl || '-'}</small>
              </span>
              <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
              <span data-label="อนุมัติ / หมายเหตุ">
                <strong>{request.reviewedByName || '-'}</strong>
                <small>{request.reviewerNote || request.systemNote || formatDateTime(request.requestedAt)}</small>
              </span>
              <span className="row-actions">
                {reviewable ? (
                  <>
                    <Button type="button" variant="icon" title="อนุมัติ" aria-label="อนุมัติ" disabled={saving} onClick={() => approve(request.id)}>
                      <Icon name="check" size={14} />
                    </Button>
                    <Button type="button" variant="icon" title="ปฏิเสธ" aria-label="ปฏิเสธ" disabled={saving} onClick={() => reject(request.id)}>
                      <Icon name="close" size={14} />
                    </Button>
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
        title="ปฏิเสธคำขอลา"
        message="ยืนยันการปฏิเสธคำขอลานี้?"
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
        title="ยกเลิกคำขอลา"
        message="ยืนยันการยกเลิกคำขอลานี้?"
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
