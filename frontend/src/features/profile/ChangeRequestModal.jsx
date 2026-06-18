import { useState } from 'react';
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
          <button type="button" className="secondary-button" onClick={onClose}>ยกเลิก</button>
          <button type="submit" form="change-request-form" className="primary-button">
            <Icon name="check" />
            ส่งคำขอ
          </button>
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
