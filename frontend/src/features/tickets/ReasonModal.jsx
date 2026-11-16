import { useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { FieldLabel, PickChips, WarnNote } from './dealDialogParts.jsx';

/**
 * One required reason picked from chips + an optional note — the shared body of ปิดงาน · เสียงาน (m-lost) and
 * ยกเลิกงาน (m-cancel), owner-approved drawings 2026-11-16. The vocabulary (title, labels, reasons, codes, warning)
 * comes from the caller; `reasons` is `[{ code, label, tag? }]`, where `tag` is a display code such as "F2".
 */
export function ReasonModal({
  title, subtitle, closeLabel, submitLabel, ariaLabel, reasons, notePlaceholder, warning, onClose, onSubmit, submitting,
}) {
  const [reason, setReason] = useState(null);
  const [note, setNote] = useState('');

  return (
    <Modal
      title={title}
      subtitle={subtitle}
      onClose={onClose}
      placement="sheet-on-phone"
      footer={(
        <>
          <Button type="button" variant="secondary" onClick={onClose}>{closeLabel}</Button>
          <Button
            type="button"
            variant="danger"
            disabled={!reason || submitting}
            onClick={() => onSubmit({ reason, note: note.trim() || undefined })}
          >
            {submitting ? 'กำลังบันทึก…' : submitLabel}
          </Button>
        </>
      )}
    >
      <div className="flex flex-col gap-4">
        <PickChips
          label={ariaLabel}
          tone="danger"
          value={reason}
          onChange={setReason}
          options={reasons.map((r) => ({ value: r.code, label: r.label, code: r.tag }))}
        />
        <div className="grid gap-1.5">
          <FieldLabel htmlFor="reason-modal-note" optional>หมายเหตุ</FieldLabel>
          <textarea
            id="reason-modal-note"
            className="min-h-20"
            value={note}
            placeholder={notePlaceholder}
            onChange={(e) => setNote(e.target.value)}
          />
        </div>
        {warning ? <WarnNote>{warning}</WarnNote> : null}
      </div>
    </Modal>
  );
}
