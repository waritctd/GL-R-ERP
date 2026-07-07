import { useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { Icon } from '../../components/common/Icon.jsx';

export function ChangeRequestModal({ requestField, onClose, onSubmit }) {
  const [value, setValue] = useState('');

  function submit(event) {
    event.preventDefault();
    onSubmit({ ...requestField, newValue: value });
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
      <form id="change-request-form" className="form-grid single" onSubmit={submit}>
        <label>
          ค่าเดิม
          <input value={requestField.oldValue} readOnly />
        </label>
        <label>
          ค่าใหม่
          <textarea value={value} onChange={(event) => setValue(event.target.value)} rows="3" required />
        </label>
      </form>
    </Modal>
  );
}
