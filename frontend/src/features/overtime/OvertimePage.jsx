import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
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

function defaultForm(employeeId = '') {
  const date = todayIso();
  return {
    employeeId: employeeId || '',
    workDate: date,
    plannedStartAt: `${date}T18:00`,
    plannedEndAt: `${date}T20:00`,
    dayType: 'WORKDAY',
    reason: '',
  };
}

function statusInfo(status) {
  const map = {
    SUBMITTED: { label: 'รออนุมัติ', tone: 'warning' },
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
    to: todayIso(),
    employeeId: '',
    status: '',
  };
  const [filters, setFilters] = useState(initialFilters);
  const [appliedFilters, setAppliedFilters] = useState(initialFilters);
  const [form, setForm] = useState(() => defaultForm(currentEmployee?.id || user.employeeId || ''));
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

  const otTimeRangeInvalid = Boolean(
    form.plannedStartAt && form.plannedEndAt && form.plannedEndAt <= form.plannedStartAt,
  );

  const submitEmployeeOptions = useMemo(
    () => employeeOptions.filter((employee) => employee.self || employee.directReport || user.role === 'admin'),
    [employeeOptions, user.role],
  );
  const canSubmitForTeam = submitEmployeeOptions.some((employee) => employee.directReport);
  const hasMultipleEmployeeOptions = employeeOptions.length > 1;
  const hasMultipleSubmitOptions = submitEmployeeOptions.length > 1;

  const totals = useMemo(() => {
    const submitted = requests.filter((request) => request.status === 'SUBMITTED').length;
    const approved = requests.filter((request) => request.status === 'APPROVED');
    const payableMinutes = approved.reduce((sum, request) => sum + Number(request.payableMinutes || 0), 0);
    return { submitted, approved: approved.length, payableMinutes };
  }, [requests]);

  // Seed the acting employee once the option list lands (preserves pre-Query behavior).
  useEffect(() => {
    if (!employeesQuery.data) return;
    setForm((current) => {
      const nextEmployeeId = current.employeeId
        || submitEmployeeOptions.find((employee) => employee.self)?.employeeId
        || submitEmployeeOptions[0]?.employeeId
        || '';
      if (nextEmployeeId === current.employeeId) return current;
      return { ...current, employeeId: nextEmployeeId };
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [employeesQuery.data]);

  function invalidateOvertime() {
    return queryClient.invalidateQueries({ queryKey: queryKeys.overtimeRequests(appliedFilters) });
  }

  function updateFilter(field, value) {
    setFilters((current) => ({ ...current, [field]: value }));
  }

  function updateForm(field, value) {
    setForm((current) => {
      if (field !== 'workDate') return { ...current, [field]: value };
      const startTime = current.plannedStartAt.slice(11) || '18:00';
      const endTime = current.plannedEndAt.slice(11) || '20:00';
      return {
        ...current,
        workDate: value,
        plannedStartAt: `${value}T${startTime}`,
        plannedEndAt: `${value}T${endTime}`,
      };
    });
  }

  function submitFilters(event) {
    event.preventDefault();
    setAppliedFilters(filters);
  }

  const createMutation = useMutation({
    mutationFn: (payload) => api.overtime.create(payload).then((response) => response.request),
    onSuccess: () => {
      setForm(defaultForm(currentEmployee?.id || user.employeeId || ''));
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

  function submitOvertime(event) {
    event.preventDefault();
    if (otTimeRangeInvalid) return;
    createMutation.mutate({
      employeeId: form.employeeId ? Number(form.employeeId) : null,
      workDate: form.workDate,
      plannedStartAt: apiDateTime(form.plannedStartAt),
      plannedEndAt: apiDateTime(form.plannedEndAt),
      dayType: form.dayType,
      reason: form.reason.trim(),
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

  function canReviewRequest(request) {
    return request.status === 'SUBMITTED' && managesRequest(request);
  }

  function canManagerCancel(request) {
    return ['SUBMITTED', 'APPROVED'].includes(request.status) && managesRequest(request);
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
    <div className="page-stack">
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

      <section className="stat-grid">
        <StatCard label="คำขอทั้งหมด" value={requests.length} helper="ในช่วงที่เลือก" icon="clock" tone="indigo" />
        <StatCard label="รออนุมัติ" value={totals.submitted} helper="Submitted" icon="clipboard" tone="amber" />
        <StatCard label="อนุมัติแล้ว" value={totals.approved} helper="Approved" icon="check" tone="teal" />
        <StatCard label="ชั่วโมงจ่ายได้" value={formatMinutes(totals.payableMinutes)} helper="Approved payable" icon="calendar" tone="blue" />
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
          <h2>ยื่นคำขอ OT</h2>
        </div>
        <form className="form-grid" onSubmit={submitOvertime}>
          {hasMultipleSubmitOptions ? (
            <label>
              พนักงาน
              <select value={form.employeeId} onChange={(event) => updateForm('employeeId', event.target.value)} required>
                <option value="">เลือกพนักงาน</option>
                {submitEmployeeOptions.map((employee) => (
                  <option key={employee.employeeId} value={employee.employeeId}>
                    {employee.employeeName} · {employee.employeeCode}{employee.directReport ? ' · ลูกทีม' : ''}
                  </option>
                ))}
              </select>
            </label>
          ) : (
            <label>
              พนักงาน
              <input value={currentEmployee?.nameTh || user.name || '-'} disabled />
            </label>
          )}
          <label>
            วันที่ทำ OT
            <input type="date" value={form.workDate} onChange={(event) => updateForm('workDate', event.target.value)} required />
          </label>
          <label>
            ประเภท OT
            <select value={form.dayType} onChange={(event) => updateForm('dayType', event.target.value)}>
              <option value="WORKDAY">วันทำงานปกติ · 1.5x</option>
              <option value="HOLIDAY">วันหยุด/วันหยุดนักขัตฤกษ์ · 3x</option>
            </select>
          </label>
          <FormField label="เริ่ม" htmlFor="ot-planned-start">
            <input
              id="ot-planned-start"
              type="datetime-local"
              value={form.plannedStartAt}
              onChange={(event) => updateForm('plannedStartAt', event.target.value)}
              required
            />
          </FormField>
          <FormField
            label="สิ้นสุด"
            htmlFor="ot-planned-end"
            error={otTimeRangeInvalid ? 'เวลาสิ้นสุดต้องอยู่หลังเวลาเริ่ม' : undefined}
          >
            <input
              id="ot-planned-end"
              type="datetime-local"
              value={form.plannedEndAt}
              onChange={(event) => updateForm('plannedEndAt', event.target.value)}
              className={otTimeRangeInvalid ? 'is-invalid' : ''}
              aria-invalid={otTimeRangeInvalid}
              aria-describedby={otTimeRangeInvalid ? fieldErrorId('ot-planned-end') : undefined}
              required
            />
          </FormField>
          <label className="span-2">
            เหตุผลความจำเป็น
            <textarea rows={3} value={form.reason} onChange={(event) => updateForm('reason', event.target.value)} required />
          </label>
          <div className="span-2 row-actions">
            <Button type="submit" disabled={saving || otTimeRangeInvalid}>
              <Icon name="plus" />
              ส่งคำขอ
            </Button>
          </div>
        </form>
      </section>

      <section className="table-panel">
        <div className="overtime-table table-head">
          <span>วันที่ / พนักงาน</span>
          <span>แผน OT</span>
          <span>เหตุผล</span>
          <span>เวลาจริง / จ่ายได้</span>
          <span>สถานะ</span>
          <span />
        </div>
        {loading ? (
          <EmptyState icon="clock" title="กำลังโหลดคำขอ OT" />
        ) : requests.length === 0 ? (
          <EmptyState icon="clock" title="ยังไม่มีคำขอ OT" description="ลองเปลี่ยนช่วงวันที่หรือยื่นคำขอใหม่" />
        ) : requests.map((request) => {
          const status = statusInfo(request.status);
          const reviewable = canReviewRequest(request);
          const canCancel = request.status === 'SUBMITTED' && Number(request.employeeId) === Number(user.employeeId);
          const managerCancellable = canManagerCancel(request);
          return (
            <div className="overtime-table table-row" key={request.id}>
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
              <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
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
    </div>
  );
}
