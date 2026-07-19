import { useState } from 'react';
import { Modal } from '../../components/common/Modal.jsx';
import { CANCEL_REASONS } from './stageMeta.js';

/**
 * Cancel-deal modal — same shape as MarkLostModal, deliberately different
 * vocabulary. Lost means we were beaten on a deal we were competing for;
 * cancelled means the opportunity itself went away. Cancel previously recorded
 * nothing at all, so a cancelled deal carried no explanation (V56).
 *
 * Cancelling is irreversible, so the reason is required before the button
 * enables — matching mark-lost rather than the old bare confirm dialog.
 */
export function CancelDealModal({ onClose, onSubmit, submitting }) {
  const [reason, setReason] = useState(null);
  const [note, setNote] = useState('');

  return (
    <Modal
      title="ยกเลิกดีล"
      subtitle="เลือกเหตุผลที่โครงการไม่ได้ไปต่อ — ใช้ “เสียงาน” แทนหากลูกค้าซื้อจากคู่แข่ง"
      onClose={onClose}
      footer={(
        <>
          <button type="button" className="secondary-button" onClick={onClose}>ปิด</button>
          <button
            type="button"
            className="danger-button"
            disabled={!reason || submitting}
            onClick={() => onSubmit({ reason, note: note.trim() || undefined })}
          >
            {submitting ? 'กำลังบันทึก…' : 'ยืนยันยกเลิกดีล'}
          </button>
        </>
      )}
    >
      <div className="flex flex-col gap-2" role="radiogroup" aria-label="เหตุผลที่ยกเลิก">
        {CANCEL_REASONS.map((r) => {
          const selected = reason === r.code;
          return (
            <button
              key={r.code}
              type="button"
              role="radio"
              aria-checked={selected}
              className={`flex items-center gap-3 rounded-lg border px-3 py-2.5 text-left ${
                selected ? 'border-danger bg-danger-bg' : 'border-border-input bg-surface'
              }`}
              onClick={() => setReason(r.code)}
            >
              <span
                className={`grid h-5 w-5 shrink-0 place-items-center rounded-full border ${
                  selected ? 'border-danger bg-danger' : 'border-border-strong bg-surface'
                }`}
              >
                {selected ? <span className="h-2 w-2 rounded-full bg-surface" /> : null}
              </span>
              <span className="text-sm font-semibold text-text">{r.label}</span>
            </button>
          );
        })}
      </div>

      <label className="mt-4 flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
        หมายเหตุ (ถ้ามี)
        <textarea
          className="min-h-16"
          value={note}
          placeholder="รายละเอียดเพิ่มเติม…"
          onChange={(e) => setNote(e.target.value)}
        />
      </label>
    </Modal>
  );
}
