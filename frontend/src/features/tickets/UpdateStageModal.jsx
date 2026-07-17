import { useState } from 'react';
import { Modal } from '../../components/common/Modal.jsx';
import { dealStageLabel } from '../../utils/format.js';
import { allowedTargetStages, stageIndex } from './stageMeta.js';

/**
 * Manual stage-change modal (from the Claude Design prototype). The select is
 * limited to stages this user may set on this deal; moving backward or skipping
 * forward multiple stages requires a note (mirrors TicketService.updateStage).
 */
export function UpdateStageModal({ user, deal, onClose, onSubmit, submitting }) {
  const options = allowedTargetStages(user, deal);
  const [stage, setStage] = useState(options[0]?.code ?? '');
  const [note, setNote] = useState('');

  const distance = stage !== '' ? stageIndex(stage) - stageIndex(deal.salesStage) : 0;
  const backward = distance < 0;
  const skipForward = distance > 1;
  const noteRequired = backward || skipForward;
  const canSave = stage !== '' && (!noteRequired || note.trim() !== '') && !submitting;

  return (
    <Modal
      title="แก้ไขสถานะดีล"
      subtitle="สถานะที่ระบบอัปเดตอัตโนมัติจากขั้นตอนของดีลจะไม่แสดงในรายการ ยกเว้นฝ่ายที่รับผิดชอบ"
      onClose={onClose}
      footer={(
        <>
          <button type="button" className="secondary-button" onClick={onClose}>ยกเลิก</button>
          <button
            type="button"
            className="primary-button"
            disabled={!canSave}
            onClick={() => onSubmit({ stage, note: note.trim() || undefined })}
          >
            {submitting ? 'กำลังบันทึก…' : 'บันทึก'}
          </button>
        </>
      )}
    >
      <div className="flex flex-col gap-3">
        <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
          สถานะใหม่
          <select
            value={stage}
            onChange={(e) => setStage(e.target.value)}
          >
            {options.map((s) => (
              <option key={s.code} value={s.code}>
                {s.no}. {dealStageLabel(s.code).label}
              </option>
            ))}
          </select>
        </label>

        {backward ? (
          <div className="rounded-lg border border-warning-border bg-warning-bg-soft px-3 py-2 text-xs text-warning-dark">
            กำลังย้อนสถานะกลับ — ต้องระบุเหตุผลประกอบการแก้ไข
          </div>
        ) : null}
        {skipForward ? (
          <div className="rounded-lg border border-warning-border bg-warning-bg-soft px-3 py-2 text-xs text-warning-dark">
            กำลังข้ามขั้นตอน — ต้องระบุเหตุผลประกอบการแก้ไข
          </div>
        ) : null}

        <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
          หมายเหตุ {noteRequired ? '(จำเป็น)' : '(ถ้ามี)'}
          <textarea
            className="min-h-20"
            value={note}
            placeholder="ระบุรายละเอียด…"
            onChange={(e) => setNote(e.target.value)}
          />
        </label>
      </div>
    </Modal>
  );
}
