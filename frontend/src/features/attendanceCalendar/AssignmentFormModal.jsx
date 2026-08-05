import { useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { todayIso } from './dateHelpers.js';
import { SCOPE_TYPES, scopeIdLabel } from './scopeTypes.js';

/**
 * Create modal for one hr.work_schedule_assignment row. `min={todayIso()}` on effectiveFrom is the
 * CLIENT-SIDE half of the back-dating guard — WorkScheduleAssignmentAdminService#create is the
 * authority (a 400 comes back regardless of what the client allowed to be typed/pasted), so
 * `formError` always carries the exact server message when the server rejects it anyway; the `min`
 * attribute is a courtesy that stops most attempts before the round trip, not the enforcement.
 *
 * `formError` covers both the 400 back-dating rejection and the 409 conflicting-successor case
 * (WorkScheduleAssignmentAdminService#create's own two failure modes) — both render as one inline
 * banner near the date fields since either can only be understood in the context of the dates just
 * entered, exactly where the user is already looking.
 */
export function AssignmentFormModal({ schedules, busy, formError, onClose, onSubmit }) {
  const [scopeType, setScopeType] = useState('EMPLOYEE');
  const [scopeId, setScopeId] = useState('');
  const [workScheduleId, setWorkScheduleId] = useState('');
  const [effectiveFrom, setEffectiveFrom] = useState(todayIso());
  const [effectiveTo, setEffectiveTo] = useState('');
  const [fieldErrors, setFieldErrors] = useState({});

  const today = todayIso();

  function handleSubmit(event) {
    event.preventDefault();
    const errors = {};
    if (!scopeId || Number.isNaN(Number(scopeId))) errors.scopeId = `กรุณาระบุ${scopeIdLabel(scopeType)}`;
    if (!workScheduleId) errors.workScheduleId = 'กรุณาเลือกตารางเวลาทำงาน';
    if (!effectiveFrom) {
      errors.effectiveFrom = 'กรุณาระบุวันที่เริ่มมีผล';
    } else if (effectiveFrom < today) {
      // Client-side echo of WorkScheduleAssignmentAdminService#create's own rule — same reasoning
      // as HolidayFormModal's 500-char echo: catch the obvious case before the round trip, but the
      // server's own message (via `formError`, on a 400 that slips past this) is what's actually
      // authoritative.
      errors.effectiveFrom = `ไม่สามารถกำหนดวันที่เริ่มมีผลย้อนหลังได้ (ต้องไม่ก่อนวันที่ ${today})`;
    }
    if (effectiveTo && effectiveFrom && effectiveTo < effectiveFrom) {
      errors.effectiveTo = 'วันที่สิ้นสุดต้องไม่มาก่อนวันที่เริ่มมีผล';
    }
    setFieldErrors(errors);
    if (Object.keys(errors).length > 0) return;

    onSubmit({
      scopeType,
      scopeId: Number(scopeId),
      workScheduleId: Number(workScheduleId),
      effectiveFrom,
      effectiveTo: effectiveTo || null,
    });
  }

  return (
    <Modal
      title="มอบหมายตารางเวลาทำงาน"
      onClose={busy ? undefined : onClose}
      footer={(
        <>
          <Button type="button" variant="secondary" onClick={onClose} disabled={busy}>ยกเลิก</Button>
          <Button type="submit" form="assignment-form" variant="primary" disabled={busy}>
            {busy ? 'กำลังบันทึก...' : 'บันทึก'}
          </Button>
        </>
      )}
    >
      <form id="assignment-form" onSubmit={handleSubmit} className="grid gap-3.5">
        <FormField label="ขอบเขต" htmlFor="assignment-scope-type" required>
          <select
            id="assignment-scope-type"
            value={scopeType}
            onChange={(event) => setScopeType(event.target.value)}
          >
            {SCOPE_TYPES.map((entry) => (
              <option key={entry.value} value={entry.value}>{entry.label}</option>
            ))}
          </select>
        </FormField>

        <FormField label={scopeIdLabel(scopeType)} htmlFor="assignment-scope-id" error={fieldErrors.scopeId} required>
          <input
            id="assignment-scope-id"
            type="number"
            min="1"
            value={scopeId}
            onChange={(event) => setScopeId(event.target.value)}
          />
        </FormField>

        <FormField label="ตารางเวลาทำงาน" htmlFor="assignment-schedule" error={fieldErrors.workScheduleId} required>
          <select
            id="assignment-schedule"
            value={workScheduleId}
            onChange={(event) => setWorkScheduleId(event.target.value)}
          >
            <option value="">— เลือกตารางเวลาทำงาน —</option>
            {(schedules ?? []).map((schedule) => (
              <option key={schedule.workScheduleId} value={schedule.workScheduleId}>
                {schedule.code} — {schedule.nameTh}
              </option>
            ))}
          </select>
        </FormField>

        <FormField label="วันที่เริ่มมีผล" htmlFor="assignment-from" error={fieldErrors.effectiveFrom} required>
          <input
            id="assignment-from"
            type="date"
            min={today}
            value={effectiveFrom}
            onChange={(event) => setEffectiveFrom(event.target.value)}
          />
        </FormField>

        <FormField
          label="วันที่สิ้นสุด (ไม่บังคับ)"
          htmlFor="assignment-to"
          error={fieldErrors.effectiveTo}
          hint="เว้นว่างไว้หากยังไม่กำหนดวันสิ้นสุด — วันนี้ยังนับเป็นวันที่มีผล (inclusive)"
        >
          <input
            id="assignment-to"
            type="date"
            min={effectiveFrom || today}
            value={effectiveTo}
            onChange={(event) => setEffectiveTo(event.target.value)}
          />
        </FormField>

        {formError ? (
          <p role="alert" className="m-0 rounded-md border border-danger-border bg-surface p-3 text-xs font-bold text-danger">
            {formError}
          </p>
        ) : null}
      </form>
    </Modal>
  );
}
