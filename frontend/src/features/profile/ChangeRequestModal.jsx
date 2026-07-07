import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { FormField, fieldErrorId } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { FormGrid } from '../../components/common/Layout.jsx';

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

  function submit(values) {
    onSubmit({ ...requestField, newValue: values.newValue });
  }

  return (
    <Modal
      title={`ขอแก้ไข${requestField.fieldLabel}`}
      subtitle="ส่งให้ HR ตรวจสอบ"
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
      <FormGrid as="form" single id="change-request-form" onSubmit={handleSubmit(submit)} noValidate>
        <label>
          ค่าเดิม
          <input value={requestField.oldValue} readOnly />
        </label>
        <FormField label="ค่าใหม่" htmlFor="change-request-new-value" error={errors.newValue?.message}>
          <textarea
            id="change-request-new-value"
            rows="3"
            {...register('newValue')}
            aria-invalid={Boolean(errors.newValue)}
            aria-describedby={errors.newValue ? fieldErrorId('change-request-new-value') : undefined}
            required
          />
        </FormField>
      </FormGrid>
    </Modal>
  );
}
