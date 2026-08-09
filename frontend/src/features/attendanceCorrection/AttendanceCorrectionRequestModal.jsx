import { useMemo } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useForm, useWatch } from 'react-hook-form';
import { z } from 'zod';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';
import { FormField, fieldErrorId } from '../../components/common/FormField.jsx';
import { formGridSpan2 } from '../../components/common/Layout.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { SafeForm } from '../../components/common/SafeForm.jsx';

// FormGrid (Layout.jsx) renders a <div>; this submit form needs native submit semantics
// (Enter-to-submit, and a modal-FOOTER button linked in via form="<id>" -- see SafeForm.jsx's
// own doc comment on that exact pattern, and EmployeeFormModal.jsx for another call site that
// uses it) plus this exact 2-col shape, so the utility string is reproduced here the same way
// AttendanceCorrectionPanel.jsx's own former submit form (and OvertimePanel.jsx) did.
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

/**
 * The submit half of "ขอแก้ไขเวลาเข้า-ออกงาน" -- split out of what used to be a standing
 * `<Panel>` inside AttendanceCorrectionPanel.jsx (fix/attendance-correction-on-attendance-page)
 * so the request FORM lives behind a button on /attendance instead of a tab on
 * /employee-requests. AttendanceCorrectionPanel.jsx keeps the list/review half and still shares
 * this feature's one query key ('attendanceCorrection') -- this modal invalidates it on a
 * successful create exactly as the old inline form did, so the list below picks the new
 * SUBMITTED row up on its own.
 *
 * Deliberately does NOT take a `user` prop even though its caller (AttendancePage.jsx) has one
 * in scope: AttendancePage decides whether this modal is reachable at all
 * (`canRequestCorrection`) before ever mounting it, and the create payload never carries an
 * employeeId either -- `AttendanceCorrectionService#submit` derives the actor from the session
 * (`requireEmployeeId(user)`, backend). There is nothing left here for a `user` prop to gate.
 */
export function AttendanceCorrectionRequestModal({ showToast, onClose }) {
  const queryClient = useQueryClient();

  const formSchema = useMemo(() => createAttendanceCorrectionFormSchema(), []);
  const {
    register, handleSubmit, setValue, getValues, control, formState: { errors },
  } = useForm({
    resolver: zodResolver(formSchema),
    defaultValues: defaultForm(),
    mode: 'onChange',
    reValidateMode: 'onChange',
  });
  const correctionType = useWatch({ control, name: 'correctionType' });
  const showCheckIn = correctionType !== 'CHECK_OUT';
  const showCheckOut = correctionType !== 'CHECK_IN';

  const createMutation = useMutation({
    mutationFn: (payload) => api.attendanceCorrection.create(payload).then((response) => response.request),
    onSuccess: () => {
      // No reset(defaultForm()) here -- onClose() below unmounts this modal immediately, so a form
      // reset right before that would never have anything left to render into (N2, adversarial
      // review: it was dead code, not a guard against a re-open carrying stale values).
      showToast('success', 'ส่งคำขอแก้ไขเวลาแล้ว');
      queryClient.invalidateQueries({ queryKey: ['attendanceCorrection'] });
      onClose();
    },
    onError: (error) => showToast('error', error.message || 'ส่งคำขอแก้ไขเวลาไม่สำเร็จ'),
  });

  const saving = createMutation.isPending;

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

  return (
    <Modal
      title="ขอแก้ไขเวลาเข้า-ออกงาน"
      subtitle="ยื่นคำขอเมื่อลืมสแกนนิ้ว — คำขอจะส่งให้ CEO พิจารณา"
      onClose={saving ? undefined : onClose}
      footer={(
        <>
          <Button type="button" variant="secondary" onClick={onClose} disabled={saving}>
            ยกเลิก
          </Button>
          <Button type="submit" form="attendance-correction-form" variant="primary" disabled={saving}>
            <Icon name="plus" />
            ส่งคำขอ
          </Button>
        </>
      )}
    >
      <SafeForm id="attendance-correction-form" className={FORM_GRID_CLASS} onSubmit={handleSubmit(submitCorrection)} noValidate>
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
      </SafeForm>
    </Modal>
  );
}
