import { useMemo, useState } from 'react';
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
import { attendanceCorrectionStatusLabel as statusInfo } from '../../utils/format.js';

const CORRECTION_TABLE_GRID = 'grid-cols-[minmax(0,1fr)_minmax(0,1.15fr)_minmax(0,1.4fr)_minmax(0,0.9fr)_minmax(0,0.75fr)] max-[1040px]:min-w-[860px] reflow-cards';
// FilterBar/FormGrid (Layout.jsx) render <div>s; the submit form needs native submit semantics
// (Enter-to-submit) and this exact 2-col shape, so both utility strings are reproduced here the
// same way OvertimePanel.jsx does for its own filter/submit forms.
const FILTER_BAR_CLASS = 'flex flex-wrap gap-[10px] items-end bg-surface border border-border rounded-md p-[14px]';
const FORM_GRID_CLASS = 'grid gap-[14px] max-[720px]:grid-cols-1 grid-cols-2';

const CORRECTION_TYPE_LABELS = {
  CHECK_IN: 'เวลาเข้างาน',
  CHECK_OUT: 'เวลาออกงาน',
  BOTH: 'ทั้งเข้างานและออกงาน',
};

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

function defaultForm() {
  const date = todayIso();
  return {
    workDate: date,
    correctionType: 'CHECK_IN',
    requestedCheckIn: `${date}T08:30`,
    requestedCheckOut: `${date}T17:30`,
    reason: '',
  };
}

export const CORRECTION_REASON_MIN_LENGTH = 5;

export function createAttendanceCorrectionFormSchema() {
  return z.object({
    workDate: z.string().min(1, 'กรุณาเลือกวันที่'),
    correctionType: z.enum(['CHECK_IN', 'CHECK_OUT', 'BOTH']),
    requestedCheckIn: z.string(),
    requestedCheckOut: z.string(),
    reason: z.string().min(CORRECTION_REASON_MIN_LENGTH, 'กรุณาระบุเหตุผลให้ชัดเจน'),
  }).superRefine((data, context) => {
    if (data.workDate && data.workDate > todayIso()) {
      context.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['workDate'],
        message: 'ไม่สามารถขอแก้ไขเวลาสำหรับวันที่ในอนาคตได้',
      });
    }
    if (data.correctionType !== 'CHECK_OUT' && !data.requestedCheckIn) {
      context.addIssue({ code: z.ZodIssueCode.custom, path: ['requestedCheckIn'], message: 'กรุณาระบุเวลาเข้างาน' });
    }
    if (data.correctionType !== 'CHECK_IN' && !data.requestedCheckOut) {
      context.addIssue({ code: z.ZodIssueCode.custom, path: ['requestedCheckOut'], message: 'กรุณาระบุเวลาออกงาน' });
    }
    if (data.correctionType === 'BOTH' && data.requestedCheckIn && data.requestedCheckOut
        && data.requestedCheckOut <= data.requestedCheckIn) {
      context.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['requestedCheckOut'],
        message: 'เวลาออกงานต้องอยู่หลังเวลาเข้างาน',
      });
    }
  });
}

function apiDateTime(value) {
  if (!value) return null;
  return value.length === 16 ? `${value}:00+07:00` : `${value}+07:00`;
}

function formatWorkDate(value) {
  if (!value) return '-';
  const date = new Date(`${value}T00:00:00+07:00`);
  if (Number.isNaN(date.getTime())) return '-';
  return new Intl.DateTimeFormat('th-TH', { dateStyle: 'medium', timeZone: 'Asia/Bangkok' }).format(date);
}

function formatTime(value) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '-';
  return new Intl.DateTimeFormat('th-TH', { timeStyle: 'short', hour12: false, timeZone: 'Asia/Bangkok' }).format(date);
}

function formatDateTime(value) {
  if (!value) return '-';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '-';
  return new Intl.DateTimeFormat('th-TH', {
    dateStyle: 'medium', timeStyle: 'short', hour12: false, timeZone: 'Asia/Bangkok',
  }).format(date);
}

export function AttendanceCorrectionPanel({ user, showToast }) {
  const queryClient = useQueryClient();
  const isCeo = user.role === 'ceo';
  const [confirmState, setConfirmState] = useState(null);
  const [statusFilter, setStatusFilter] = useState('');

  const requestsQuery = useQuery({
    queryKey: queryKeys.attendanceCorrectionRequests({ status: statusFilter }),
    queryFn: () => api.attendanceCorrection.list({ status: statusFilter || undefined })
      .then((response) => response.requests || []),
    refetchInterval: 60_000,
    refetchOnWindowFocus: true,
  });
  const requests = useMemo(() => requestsQuery.data ?? [], [requestsQuery.data]);
  const loading = requestsQuery.isLoading || requestsQuery.isFetching;

  const formSchema = useMemo(() => createAttendanceCorrectionFormSchema(), []);
  const {
    register, handleSubmit, reset, setValue, getValues, control, formState: { errors },
  } = useForm({
    resolver: zodResolver(formSchema),
    defaultValues: defaultForm(),
    mode: 'onChange',
    reValidateMode: 'onChange',
  });
  const correctionType = useWatch({ control, name: 'correctionType' });
  const showCheckIn = correctionType !== 'CHECK_OUT';
  const showCheckOut = correctionType !== 'CHECK_IN';

  function invalidateAttendanceCorrection() {
    return queryClient.invalidateQueries({ queryKey: ['attendanceCorrection'] });
  }

  const createMutation = useMutation({
    mutationFn: (payload) => api.attendanceCorrection.create(payload).then((response) => response.request),
    onSuccess: () => {
      reset(defaultForm());
      showToast('success', 'ส่งคำขอแก้ไขเวลาแล้ว');
      invalidateAttendanceCorrection();
    },
    onError: (error) => showToast('error', error.message || 'ส่งคำขอแก้ไขเวลาไม่สำเร็จ'),
  });

  const approveMutation = useMutation({
    mutationFn: ({ id, reviewerNote }) => api.attendanceCorrection.approve(id, { reviewerNote }).then((response) => response.request),
    onSuccess: () => {
      showToast('success', 'อนุมัติคำขอแก้ไขเวลาแล้ว');
      setConfirmState(null);
      invalidateAttendanceCorrection();
    },
    onError: (error) => showToast('error', error.message || 'อนุมัติคำขอไม่สำเร็จ'),
  });

  const rejectMutation = useMutation({
    mutationFn: ({ id, reviewerNote }) => api.attendanceCorrection.reject(id, { reviewerNote }).then((response) => response.request),
    onSuccess: () => {
      showToast('success', 'ปฏิเสธคำขอแก้ไขเวลาแล้ว');
      setConfirmState(null);
      invalidateAttendanceCorrection();
    },
    onError: (error) => showToast('error', error.message || 'ปฏิเสธคำขอไม่สำเร็จ'),
  });

  const cancelMutation = useMutation({
    mutationFn: (id) => api.attendanceCorrection.cancel(id).then((response) => response.request),
    onSuccess: () => {
      showToast('success', 'ยกเลิกคำขอแก้ไขเวลาแล้ว');
      setConfirmState(null);
      invalidateAttendanceCorrection();
    },
    onError: (error) => showToast('error', error.message || 'ยกเลิกคำขอไม่สำเร็จ'),
  });

  const saving = createMutation.isPending || approveMutation.isPending
    || rejectMutation.isPending || cancelMutation.isPending;

  function handleWorkDateChange(event) {
    const value = event.target.value;
    const checkInTime = getValues('requestedCheckIn').slice(11) || '08:30';
    const checkOutTime = getValues('requestedCheckOut').slice(11) || '17:30';
    setValue('requestedCheckIn', `${value}T${checkInTime}`, { shouldDirty: true, shouldValidate: true });
    setValue('requestedCheckOut', `${value}T${checkOutTime}`, { shouldDirty: true, shouldValidate: true });
  }

  function submitCorrection(values) {
    createMutation.mutate({
      workDate: values.workDate,
      correctionType: values.correctionType,
      requestedCheckIn: showCheckIn ? apiDateTime(values.requestedCheckIn) : null,
      requestedCheckOut: showCheckOut ? apiDateTime(values.requestedCheckOut) : null,
      reason: values.reason.trim(),
    });
  }

  function approve(id) {
    setConfirmState({ kind: 'approve', id });
  }

  function confirmApprove() {
    approveMutation.mutate({ id: confirmState.id, reviewerNote: null });
  }

  function reject(id) {
    setConfirmState({ kind: 'reject', id });
  }

  function confirmReject(reviewerNote) {
    if (!reviewerNote?.trim()) return;
    rejectMutation.mutate({ id: confirmState.id, reviewerNote: reviewerNote.trim() });
  }

  function cancel(id) {
    setConfirmState({ kind: 'cancel', id });
  }

  function confirmCancel() {
    cancelMutation.mutate(confirmState.id);
  }

  const totals = useMemo(() => ({
    submitted: requests.filter((request) => request.status === 'SUBMITTED').length,
    approved: requests.filter((request) => request.status === 'APPROVED').length,
  }), [requests]);

  return (
    <PageStack>
      {/* No PageHeader here: this is a tab inside RequestsPage, which owns the page title. */}
      <div className="flex items-center justify-between gap-3">
        <p className="m-0 text-sm text-text-muted">
          {isCeo
            ? 'พิจารณาคำขอแก้ไขเวลาเข้า-ออกงานของพนักงาน'
            : 'ยื่นคำขอแก้ไขเวลาเข้า-ออกงานเมื่อลืมสแกนนิ้ว และดูประวัติของคุณ'}
        </p>
        <Button type="button" variant="secondary" onClick={() => requestsQuery.refetch()} disabled={loading}>
          <Icon name="refresh" />
          รีเฟรช
        </Button>
      </div>

      <CompactStatRow
        items={[
          { key: 'total', label: 'คำขอทั้งหมด', value: requests.length, helper: 'ทั้งหมด' },
          { key: 'submitted', label: 'รอ CEO', value: totals.submitted, helper: 'Submitted' },
          { key: 'approved', label: 'อนุมัติแล้ว', value: totals.approved, helper: 'Approved' },
        ]}
      />

      {!isCeo ? (
        <Panel title="ยื่นคำขอแก้ไขเวลาเข้า-ออกงาน">
          <SafeForm className={FORM_GRID_CLASS} onSubmit={handleSubmit(submitCorrection)} noValidate>
            <FormField label="วันที่ที่ลืมสแกน" htmlFor="ac-work-date" error={errors.workDate?.message} required>
              <input
                id="ac-work-date"
                type="date"
                max={todayIso()}
                {...register('workDate', { onChange: handleWorkDateChange })}
                aria-invalid={Boolean(errors.workDate)}
                required
              />
            </FormField>
            <FormField label="รายการที่ต้องแก้ไข" htmlFor="ac-type" error={errors.correctionType?.message} required>
              <select
                id="ac-type"
                {...register('correctionType')}
                value={correctionType ?? ''}
                onChange={(event) => setValue('correctionType', event.target.value, { shouldDirty: true, shouldValidate: true })}
                aria-invalid={Boolean(errors.correctionType)}
                aria-describedby={errors.correctionType ? fieldErrorId('ac-type') : undefined}
              >
                <option value="CHECK_IN">ลืมสแกนตอนเข้างาน</option>
                <option value="CHECK_OUT">ลืมสแกนตอนออกงาน</option>
                <option value="BOTH">ลืมสแกนทั้งเข้าและออกงาน</option>
              </select>
            </FormField>
            {showCheckIn ? (
              <FormField label="เวลาเข้างานที่ถูกต้อง" htmlFor="ac-check-in" error={errors.requestedCheckIn?.message} required>
                <input
                  id="ac-check-in"
                  type="datetime-local"
                  {...register('requestedCheckIn')}
                  aria-invalid={Boolean(errors.requestedCheckIn)}
                  required
                />
              </FormField>
            ) : null}
            {showCheckOut ? (
              <FormField label="เวลาออกงานที่ถูกต้อง" htmlFor="ac-check-out" error={errors.requestedCheckOut?.message} required>
                <input
                  id="ac-check-out"
                  type="datetime-local"
                  {...register('requestedCheckOut')}
                  aria-invalid={Boolean(errors.requestedCheckOut)}
                  required
                />
              </FormField>
            ) : null}
            <div className={formGridSpan2}>
              <FormField label="เหตุผล" htmlFor="ac-reason" error={errors.reason?.message} required>
                <textarea
                  id="ac-reason"
                  rows={3}
                  {...register('reason')}
                  aria-invalid={Boolean(errors.reason)}
                  aria-describedby={errors.reason ? fieldErrorId('ac-reason') : undefined}
                  required
                />
              </FormField>
            </div>
            <RowActions className={formGridSpan2}>
              <Button type="submit" disabled={saving} className="max-[720px]:min-h-11 max-[720px]:w-full">
                <Icon name="plus" />
                ส่งคำขอ
              </Button>
            </RowActions>
          </SafeForm>
        </Panel>
      ) : null}

      <SafeForm className={FILTER_BAR_CLASS} onSubmit={(event) => event.preventDefault()}>
        <label>
          สถานะ
          <select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)}>
            <option value="">ทุกสถานะ</option>
            <option value="SUBMITTED">รอ CEO</option>
            <option value="APPROVED">อนุมัติแล้ว</option>
            <option value="REJECTED">ปฏิเสธแล้ว</option>
            <option value="CANCELLED">ยกเลิกแล้ว</option>
          </select>
        </label>
      </SafeForm>

      <Panel flush>
        <div className={`${CORRECTION_TABLE_GRID} table-head`}>
          <span>วันที่ / พนักงาน</span>
          <span>รายการที่แก้ไข</span>
          <span>เหตุผล</span>
          <span>สถานะ</span>
          <span />
        </div>
        {loading ? (
          <EmptyState icon="clock" title="กำลังโหลดคำขอแก้ไขเวลา" />
        ) : requests.length === 0 ? (
          <EmptyState icon="clock" title="ยังไม่มีคำขอแก้ไขเวลา" description="ยื่นคำขอใหม่ได้ด้านบน" />
        ) : requests.map((request) => {
          const status = statusInfo(request.status);
          const canReview = request.canReview;
          const canCancel = request.status === 'SUBMITTED' && Number(request.employeeId) === Number(user.employeeId);
          return (
            <div className={`${CORRECTION_TABLE_GRID} data-row`} key={request.id}>
              <span data-label="วันที่ / พนักงาน" className="max-[720px]:order-1">
                <strong>{formatWorkDate(request.workDate)}</strong>
                <small>{request.employeeName || request.employeeCode || request.employeeId}</small>
              </span>
              <span data-label="รายการที่แก้ไข" className="max-[720px]:order-4">
                <strong>{CORRECTION_TYPE_LABELS[request.correctionType] || request.correctionType}</strong>
                <small className="font-mono">
                  {request.requestedCheckIn ? `เข้า ${formatTime(request.requestedCheckIn)}` : ''}
                  {request.requestedCheckIn && request.requestedCheckOut ? ' · ' : ''}
                  {request.requestedCheckOut ? `ออก ${formatTime(request.requestedCheckOut)}` : ''}
                </small>
              </span>
              <span data-label="เหตุผล" className="max-[720px]:order-5">
                <strong>{request.reason}</strong>
                <small>{request.reviewerNote || '-'}</small>
              </span>
              <span data-label="สถานะ" className="max-[720px]:order-2">
                <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
                <small>{request.reviewedAt ? `${request.reviewedByName || '-'} · ${formatDateTime(request.reviewedAt)}` : '-'}</small>
              </span>
              <span className="row-actions max-[720px]:order-3">
                {canReview ? (
                  <>
                    <Button
                      type="button"
                      variant="icon"
                      title="CEO อนุมัติ"
                      aria-label="CEO อนุมัติ"
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
                {canCancel ? (
                  <Button type="button" variant="icon" title="ยกเลิก" aria-label="ยกเลิก" disabled={saving} onClick={() => cancel(request.id)}>
                    <Icon name="close" size={14} />
                  </Button>
                ) : null}
              </span>
            </div>
          );
        })}
      </Panel>

      <ConfirmDialog
        open={confirmState?.kind === 'approve'}
        title="ยืนยันการอนุมัติคำขอแก้ไขเวลา"
        message='ยืนยันการอนุมัติ? ระบบจะบันทึกเวลาเข้า-ออกงานที่ขอแก้ไขเป็นเวลาที่ใช้จริงสำหรับวันนี้'
        confirmLabel="อนุมัติ"
        busy={saving}
        onConfirm={confirmApprove}
        onCancel={() => setConfirmState(null)}
      />
      <ConfirmDialog
        open={confirmState?.kind === 'reject'}
        title="ปฏิเสธคำขอแก้ไขเวลา"
        message='ยืนยันการปฏิเสธคำขอนี้? สถานะจะเปลี่ยนเป็น "ปฏิเสธแล้ว" และไม่สามารถอนุมัติย้อนหลังได้'
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
        title="ยกเลิกคำขอแก้ไขเวลา"
        message='ยืนยันการยกเลิกคำขอนี้? สถานะจะเปลี่ยนเป็น "ยกเลิกแล้ว" ถาวร'
        confirmLabel="ยกเลิกคำขอ"
        tone="danger"
        busy={saving}
        onConfirm={confirmCancel}
        onCancel={() => setConfirmState(null)}
      />
    </PageStack>
  );
}
