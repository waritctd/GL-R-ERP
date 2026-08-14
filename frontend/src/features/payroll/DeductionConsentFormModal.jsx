import { useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { SafeForm } from '../../components/common/SafeForm.jsx';
import { CONSENT_APPLICABLE_DEDUCTION_KINDS, payrollDeductionKindLabel } from '../../utils/format.js';

/**
 * Record/edit one written-consent row (issue #376's write half, exposed for #744).
 *
 * ── THE CONTROL MUST NOT READ AS AN APPROVAL ─────────────────────────────────
 * This is the screen where an HR user ticks a box next to an employee's name and a deduction, so it
 * is the single most likely place in the app to be misread as authorising that deduction. It does
 * not: `PayrollCalculator` never reads `consent_on_file`, and no payroll figure changes as a result
 * of anything submitted here. The wording is chosen to keep that true to a reader:
 *   - the checkbox is phrased as a FILING FACT ("มีหนังสือ...เก็บไว้ในแฟ้มแล้ว"), not a permission
 *     ("อนุญาตให้หัก"), and carries its own inline note that ticking it changes no deduction;
 *   - the submit verb is "บันทึก" (record), never "อนุมัติ" (approve) or "ยืนยันสิทธิ์";
 *   - the dialog repeats the non-consequence rather than relying on the reader having read the
 *     page behind it, since a modal covers that explanation while it is open.
 *
 * ── EMPLOYEE + KIND ARE THE PRIMARY KEY, SO EDIT LOCKS THEM ──────────────────
 * `hr.deduction_written_consent` is UNIQUE (employee_id, deduction_kind) and the endpoint is an
 * UPSERT on exactly that pair (V107 / DeductionWrittenConsentRepository#upsert). Editing a row and
 * changing either field would therefore not rename anything — it would silently write a DIFFERENT
 * row and leave the original untouched. Both are read-only in edit mode for that reason, the same
 * shape as HolidayFormModal locking its date.
 *
 * Only the four CONSENT_APPLICABLE_DEDUCTION_KINDS are offered. That is not a client-side rule
 * inventing a constraint: the server 400s on anything else, so offering more would build a picker
 * whose options are rejected on submit.
 */
export function DeductionConsentFormModal({
  mode, // 'create' | 'edit'
  row, // the existing row, in edit mode
  employees,
  employeesLoading,
  busy,
  formError,
  onClose,
  onSubmit,
}) {
  const isEdit = mode === 'edit';
  const [employeeId, setEmployeeId] = useState(isEdit ? String(row?.employeeId ?? '') : '');
  const [deductionKind, setDeductionKind] = useState(isEdit ? (row?.deductionKind ?? '') : '');
  const [consentOnFile, setConsentOnFile] = useState(isEdit ? !!row?.consentOnFile : false);
  const [consentDocumentReference, setConsentDocumentReference] = useState(row?.consentDocumentReference ?? '');
  const [consentDate, setConsentDate] = useState(row?.consentDate ?? '');
  const [notes, setNotes] = useState(row?.notes ?? '');
  const [employeeError, setEmployeeError] = useState('');
  const [kindError, setKindError] = useState('');

  function handleSubmit(event) {
    event.preventDefault();
    let hasError = false;
    // Both are @NotNull on DeductionWrittenConsentUpsertRequest — caught here so a missing pick is
    // named on the field rather than coming back as a generic 400.
    if (!employeeId) {
      setEmployeeError('กรุณาเลือกพนักงาน');
      hasError = true;
    } else {
      setEmployeeError('');
    }
    if (!deductionKind) {
      setKindError('กรุณาเลือกประเภทการหัก');
      hasError = true;
    } else {
      setKindError('');
    }
    if (hasError) return;

    onSubmit({
      employeeId: Number(employeeId),
      deductionKind,
      consentOnFile,
      // Trimmed to null rather than '': the columns are nullable TEXT, and an empty string would
      // record "" as though it were a real document reference.
      consentDocumentReference: consentDocumentReference.trim() || null,
      consentDate: consentDate || null,
      notes: notes.trim() || null,
    });
  }

  return (
    <Modal
      title={isEdit ? 'แก้ไขบันทึกหนังสือยินยอม' : 'บันทึกหนังสือยินยอม'}
      subtitle={isEdit ? `${row?.employeeName} — ${payrollDeductionKindLabel(row?.deductionKind)}` : undefined}
      onClose={busy ? undefined : onClose}
      footer={(
        <>
          <Button type="button" variant="secondary" onClick={onClose} disabled={busy}>ยกเลิก</Button>
          {/* "บันทึก", never "อนุมัติ" — this records what HR holds on paper; it approves nothing. */}
          <Button type="submit" form="deduction-consent-form" variant="primary" disabled={busy}>
            {busy ? 'กำลังบันทึก...' : 'บันทึก'}
          </Button>
        </>
      )}
    >
      <SafeForm id="deduction-consent-form" onSubmit={handleSubmit} className="grid gap-3.5">
        <FormField label="พนักงาน" htmlFor="consent-employee" error={employeeError} required>
          <select
            id="consent-employee"
            value={employeeId}
            onChange={(event) => setEmployeeId(event.target.value)}
            // Locked on edit: (employee, kind) is the row's primary key, so changing it here would
            // write a different row rather than edit this one.
            disabled={isEdit || employeesLoading}
          >
            <option value="">{employeesLoading ? 'กำลังโหลดรายชื่อ...' : 'เลือกพนักงาน'}</option>
            {employees.map((employee) => (
              <option key={employee.id} value={employee.id}>
                {employee.nameTh} ({employee.code})
              </option>
            ))}
          </select>
        </FormField>

        <FormField label="ประเภทการหัก" htmlFor="consent-kind" error={kindError} required>
          <select
            id="consent-kind"
            value={deductionKind}
            onChange={(event) => setDeductionKind(event.target.value)}
            disabled={isEdit}
          >
            <option value="">เลือกประเภทการหัก</option>
            {CONSENT_APPLICABLE_DEDUCTION_KINDS.map((value) => (
              <option key={value} value={value}>{payrollDeductionKindLabel(value)}</option>
            ))}
          </select>
        </FormField>

        {/* A tonal inset, not a nested card (DESIGN.md §4's one-panel-deep rule): this groups the
            consent fact with the note about what it does not do, so the two are read together. */}
        <div className="grid gap-2 rounded-md bg-surface-subtle p-3">
          <label htmlFor="consent-on-file" className="flex items-start gap-2.5 text-sm font-bold text-text">
            <input
              id="consent-on-file"
              type="checkbox"
              checked={consentOnFile}
              onChange={(event) => setConsentOnFile(event.target.checked)}
              className="mt-0.5 h-4 w-4 shrink-0"
            />
            มีหนังสือยินยอมของพนักงานเก็บไว้ในแฟ้มแล้ว
          </label>
          <p className="m-0 text-xs text-text-secondary leading-normal">
            เป็นการบันทึกว่าฝ่ายบุคคลได้เอกสารมาเก็บไว้แล้วหรือยัง
            <strong>การติ๊กหรือไม่ติ๊กช่องนี้ไม่มีผลต่อการหักเงิน</strong>
            และไม่เปลี่ยนสลิปเงินเดือนของพนักงาน
          </p>
        </div>

        <FormField
          label="เลขที่เอกสาร"
          htmlFor="consent-reference"
          hint="คัดลอกจากเอกสารจริงเท่านั้น ไม่ต้องกรอกถ้ายังไม่มีเอกสาร"
        >
          <input
            id="consent-reference"
            type="text"
            value={consentDocumentReference}
            onChange={(event) => setConsentDocumentReference(event.target.value)}
            placeholder="เช่น CONSENT-2569-0012"
          />
        </FormField>

        <FormField label="วันที่ในหนังสือ" htmlFor="consent-date">
          <input
            id="consent-date"
            type="date"
            value={consentDate}
            onChange={(event) => setConsentDate(event.target.value)}
          />
        </FormField>

        <FormField label="หมายเหตุ" htmlFor="consent-notes">
          <textarea
            id="consent-notes"
            rows={3}
            value={notes}
            onChange={(event) => setNotes(event.target.value)}
            placeholder="เช่น ส่งแบบฟอร์มให้พนักงานแล้ว รอเซ็นกลับ"
          />
        </FormField>

        {formError ? (
          <p role="alert" className="m-0 rounded-md border border-danger-border bg-surface p-3 text-xs font-bold text-danger">
            {formError}
          </p>
        ) : null}
      </SafeForm>
    </Modal>
  );
}
