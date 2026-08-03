import { useState } from 'react';
import { FormField } from '../../components/common/FormField.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';

/**
 * Create/edit modal for one hr.holiday row. In edit mode the date is read-only (PUT is keyed on the
 * path-variable date — HolidayController#update — there is no rename-the-date operation), and the
 * row's current `source` is shown as a badge so the BANK->COMPANY reclassification confirm (owned
 * by the caller, HolidaysTab.jsx) makes sense in context.
 *
 * `formError` is a server-side error the caller wants surfaced inline (409 duplicate date on
 * create, or any other 400 the backend raises) rather than a passing toast — the modal stays open
 * so the field is fixable without re-opening it. `onUseExisting` is present only for the 409 case:
 * it lets the caller offer "แก้ไขรายการเดิม" right where the conflict was discovered.
 */
export function HolidayFormModal({
  mode, // 'create' | 'edit'
  initialDate,
  initialNameTh,
  source,
  busy,
  formError,
  onUseExisting,
  onClose,
  onSubmit,
}) {
  const [holidayDate, setHolidayDate] = useState(initialDate ?? '');
  const [nameTh, setNameTh] = useState(initialNameTh ?? '');
  const [dateError, setDateError] = useState('');
  const [nameError, setNameError] = useState('');

  const isEdit = mode === 'edit';

  function handleSubmit(event) {
    event.preventDefault();
    let hasError = false;
    if (!isEdit && !holidayDate) {
      setDateError('กรุณาระบุวันที่วันหยุด');
      hasError = true;
    } else {
      setDateError('');
    }
    const trimmedName = nameTh.trim();
    if (!trimmedName) {
      setNameError('กรุณาระบุชื่อวันหยุด');
      hasError = true;
    } else if (trimmedName.length > 500) {
      // Mirrors HolidayAdminService.MAX_NAME_LENGTH — a client-side echo of the same 500-char
      // bound the server enforces, so a paste accident is caught before the round trip.
      setNameError('ชื่อวันหยุดยาวเกินไป (สูงสุด 500 ตัวอักษร)');
      hasError = true;
    } else {
      setNameError('');
    }
    if (hasError) return;
    onSubmit({ holidayDate, nameTh: trimmedName });
  }

  return (
    <Modal
      title={isEdit ? 'แก้ไขวันหยุด' : 'เพิ่มวันหยุดบริษัท'}
      subtitle={isEdit ? `วันที่ ${initialDate}` : undefined}
      onClose={busy ? undefined : onClose}
      footer={(
        <>
          <button type="button" className="secondary-button" onClick={onClose} disabled={busy}>ยกเลิก</button>
          <button type="submit" form="holiday-form" className="primary-button" disabled={busy}>
            {busy ? 'กำลังบันทึก...' : 'บันทึก'}
          </button>
        </>
      )}
    >
      <form id="holiday-form" onSubmit={handleSubmit} className="grid gap-3.5">
        {isEdit ? (
          <div>
            <span className="mb-1 block text-xs font-bold text-text-muted">แหล่งที่มาปัจจุบัน</span>
            <StatusBadge tone={source === 'BANK' ? 'info' : 'neutral'}>
              {source === 'BANK' ? 'ธนาคารแห่งประเทศไทย (BANK)' : 'วันหยุดบริษัท (COMPANY)'}
            </StatusBadge>
          </div>
        ) : null}

        <FormField label="วันที่" htmlFor="holiday-date" error={dateError} required>
          <input
            id="holiday-date"
            type="date"
            value={holidayDate}
            onChange={(event) => setHolidayDate(event.target.value)}
            disabled={isEdit}
          />
        </FormField>

        <FormField label="ชื่อวันหยุด" htmlFor="holiday-name" error={nameError} required>
          <input
            id="holiday-name"
            type="text"
            value={nameTh}
            onChange={(event) => setNameTh(event.target.value)}
            maxLength={500}
            placeholder="เช่น วันหยุดชดเชยสงกรานต์"
          />
        </FormField>

        {formError ? (
          <div className="grid gap-2 rounded-md border border-danger-border bg-surface p-3">
            <p role="alert" className="m-0 text-xs font-bold text-danger">{formError}</p>
            {onUseExisting ? (
              <button type="button" className="secondary-button justify-self-start" onClick={onUseExisting}>
                แก้ไขรายการเดิม
              </button>
            ) : null}
          </div>
        ) : null}
      </form>
    </Modal>
  );
}
