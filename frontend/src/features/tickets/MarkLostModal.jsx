import { useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { EMPTY_STAGE_CATALOG } from './stageCatalog.js';
import { labelledReasons, LOST_REASON_LABEL } from './stageMeta.js';

/**
 * Mark-lost modal (from the Claude Design prototype): one reason from the
 * standardized list + optional note. The deal keeps its stage — reopening
 * later resumes exactly where it was.
 */
export function MarkLostModal({ catalog = EMPTY_STAGE_CATALOG, onClose, onSubmit, submitting }) {
  const [reason, setReason] = useState(null);
  const [note, setNote] = useState('');
  // The CODE set is DealLostReason.ORDER, served by /api/meta/deal-stages; only the Thai wording is
  // this side's. The hardcoded list this replaced was a copy of that enum with no guard.
  const reasons = labelledReasons(catalog.lostReasons, LOST_REASON_LABEL);

  return (
    <Modal
      title="ทำเครื่องหมายเสียงาน"
      subtitle="เลือกเหตุผล — ดีลจะย้ายไปกลุ่ม “เสียงาน” และเปิดใหม่ได้ภายหลังโดยสถานะเดิมยังอยู่"
      onClose={onClose}
      footer={(
        <>
          <Button type="button" variant="secondary" onClick={onClose}>ยกเลิก</Button>
          <Button
            type="button"
            variant="danger"
            disabled={!reason || submitting}
            onClick={() => onSubmit({ reason, note: note.trim() || undefined })}
          >
            {submitting ? 'กำลังบันทึก…' : 'ยืนยันเสียงาน'}
          </Button>
        </>
      )}
    >
      <div className="flex flex-col gap-2" role="radiogroup" aria-label="เหตุผลที่เสียงาน">
        {reasons.map((r) => {
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
