import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { FormField, fieldErrorId } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { FormGrid } from '../../components/common/Layout.jsx';
import { SafeForm } from '../../components/common/SafeForm.jsx';

const changeRequestFormSchema = z.object({
  newValue: z.string().min(1, 'กรุณาระบุค่าใหม่'),
});

export function ChangeRequestModal({ requestField, onClose, onSubmit }) {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm({
    resolver: zodResolver(changeRequestFormSchema),
    defaultValues: { newValue: '' },
    mode: 'onChange',
    reValidateMode: 'onChange',
  });

  const currentValue = requestField.oldValue || '-';
  const newValueId = 'change-request-new-value';
  const newValueHelpId = `${newValueId}-help`;
  const newValueErrorId = fieldErrorId(newValueId);
  const newValueDescribedBy = errors.newValue
    ? `${newValueErrorId} ${newValueHelpId}`
    : newValueHelpId;

  function submit(values) {
    onSubmit({ ...requestField, newValue: values.newValue });
  }

  return (
    <Modal
      title={`ขอแก้ไข${requestField.fieldLabel}`}
      subtitle="ตรวจสอบค่าเดิม แล้วกรอกค่าใหม่ที่จะส่งให้ HR"
      onClose={onClose}
      footer={(
        <>
          <Button type="button" variant="secondary" onClick={onClose}>ยกเลิก</Button>
          <Button type="submit" form="change-request-form">
            <Icon name="check" />
            ส่งคำขอ
          </Button>
        </>
      )}
    >
      <SafeForm id="change-request-form" onSubmit={handleSubmit(submit)} noValidate>
        <FormGrid single>
          <div
            className="rounded-md border border-border bg-surface-muted p-3"
            aria-labelledby="change-request-current-label"
          >
            <span id="change-request-current-label" className="block text-xs font-bold text-text-muted">
              ข้อมูลปัจจุบัน
            </span>
            <strong className="mt-1 block text-sm text-text">{requestField.fieldLabel}</strong>
            <span className="mt-1 block whitespace-pre-wrap text-sm text-text-secondary">{currentValue}</span>
          </div>
          <FormField
            label={`ค่าใหม่ของ${requestField.fieldLabel}`}
            htmlFor={newValueId}
            error={errors.newValue?.message}
            required
          >
            <textarea
              id={newValueId}
              rows="3"
              {...register('newValue')}
              aria-invalid={Boolean(errors.newValue)}
              aria-describedby={newValueDescribedBy}
              required
            />
            <small id={newValueHelpId}>กรอกเฉพาะค่าใหม่ที่จะให้ HR ตรวจสอบ</small>
          </FormField>
        </FormGrid>
      </SafeForm>
    </Modal>
  );
}
