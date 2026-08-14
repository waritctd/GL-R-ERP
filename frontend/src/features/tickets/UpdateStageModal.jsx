import { useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { dealStageLabel } from '../../utils/format.js';

/**
 * Manual stage-change modal. **It renders a decision; it does not make one.**
 *
 * Every stage in `stageDecisions` arrives from the backend
 * (GET /api/tickets/{id}/actions → TicketService.stageDecisions) already carrying:
 *   • `allowed`        — would updateStage accept this move, for this user, on this deal?
 *   • `requiresReason` — would it additionally demand a written justification?
 *   • `blockedReason`  — if not allowed, the server's own message, verbatim.
 *
 * This component previously computed all three for itself, from a copy of the stage list, a copy
 * of TicketService.requireStageWriteAccess and a copy of the note rule. The note copy was the
 * pre-#704 version — `distance > 1` plus one hardcoded backward pair — so it demanded a written
 * reason for three of the business's four normal routes (an owner buying direct, a contractor
 * arriving with a BOQ at S8, an all-from-stock deal skipping procurement) that the backend had
 * already stopped requiring. There is now nothing here to disagree with.
 *
 * The blocked stages are SHOWN, not hidden. That is the point of the new payload: an option that
 * silently vanishes leaves the rep guessing, while "ยังไปไม่ได้ — ยังไม่ได้รับชำระมัดจำ" tells them
 * what to do next. The select still offers only what will actually be accepted.
 */
export function UpdateStageModal({ deal, stageDecisions = [], onClose, onSubmit, submitting }) {
  const options = stageDecisions.filter((decision) => decision.allowed);
  const blocked = stageDecisions.filter(
    (decision) => !decision.allowed && decision.stage !== deal?.salesStage,
  );
  const [stage, setStage] = useState(options[0]?.stage ?? '');
  const [note, setNote] = useState('');
  const [showBlocked, setShowBlocked] = useState(false);

  const selected = options.find((decision) => decision.stage === stage) ?? null;
  const noteRequired = Boolean(selected?.requiresReason);
  const canSave = stage !== '' && (!noteRequired || note.trim() !== '') && !submitting;

  // Direction is used ONLY to choose between two pieces of copy — never to decide whether the note
  // is required, which is `requiresReason` above and nothing else. `no` is the backend's own
  // display sequence, so comparing two of them is a lookup, not a rule. (The service picks between
  // the same two messages the same way.)
  const current = stageDecisions.find((decision) => decision.stage === deal?.salesStage) ?? null;
  const backward = Boolean(selected && current && selected.no < current.no);

  return (
    <Modal
      title="แก้ไขสถานะดีล"
      subtitle="ขั้นตอนที่เลือกได้คือขั้นตอนที่ระบบยืนยันแล้วว่าดีลนี้ไปได้จริง"
      testId="update-stage-modal"
      onClose={onClose}
      footer={(
        <>
          <Button type="button" variant="secondary" onClick={onClose}>ยกเลิก</Button>
          <Button
            type="button"
            variant="primary"
            disabled={!canSave}
            onClick={() => onSubmit({ stage, note: note.trim() || undefined })}
          >
            {submitting ? 'กำลังบันทึก…' : 'บันทึก'}
          </Button>
        </>
      )}
    >
      <div className="flex flex-col gap-3">
        {options.length === 0 ? (
          <div
            className="rounded-lg border border-border bg-surface-subtle px-3 py-2.5 text-xs text-text-muted"
            data-testid="update-stage-none-available"
          >
            ตอนนี้ยังไม่มีขั้นตอนที่ย้ายไปได้ — ดูเหตุผลของแต่ละขั้นตอนด้านล่าง
          </div>
        ) : (
          <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
            สถานะใหม่
            <select value={stage} onChange={(event) => setStage(event.target.value)}>
              {options.map((decision) => (
                <option key={decision.stage} value={decision.stage}>
                  {decision.no}. {dealStageLabel(decision.stage).label}
                </option>
              ))}
            </select>
          </label>
        )}

        {noteRequired ? (
          <div className="rounded-lg border border-warning-border bg-warning-bg-soft px-3 py-2 text-xs text-warning-dark">
            {backward
              ? 'กำลังย้อนสถานะกลับ — ต้องระบุเหตุผลประกอบการแก้ไข'
              : 'กำลังข้ามขั้นตอนสำคัญ — ต้องระบุเหตุผลประกอบการแก้ไข'}
          </div>
        ) : null}

        {options.length > 0 ? (
          <label className="flex flex-col gap-1.5 text-sm font-bold text-text-secondary">
            หมายเหตุ {noteRequired ? '(จำเป็น)' : '(ถ้ามี)'}
            <textarea
              className="min-h-20"
              value={note}
              placeholder="ระบุรายละเอียด…"
              onChange={(event) => setNote(event.target.value)}
            />
          </label>
        ) : null}

        {blocked.length > 0 ? (
          <div className="rounded-lg border border-border bg-surface-muted">
            <button
              type="button"
              className="flex w-full items-center justify-between gap-2 bg-transparent px-3 py-2 text-left text-xs font-bold text-text-secondary"
              aria-expanded={showBlocked}
              onClick={() => setShowBlocked((open) => !open)}
              data-testid="update-stage-blocked-toggle"
            >
              <span>ขั้นตอนที่ยังไปไม่ได้ ({blocked.length})</span>
              <span className="text-text-muted">{showBlocked ? 'ซ่อน' : 'ดูเหตุผล'}</span>
            </button>
            {showBlocked ? (
              <ul
                className="m-0 flex list-none flex-col gap-2 border-t border-border px-3 py-2.5"
                data-testid="update-stage-blocked-list"
              >
                {blocked.map((decision) => (
                  <li key={decision.stage} className="grid grid-cols-[24px_1fr] gap-2.5">
                    <span className="grid h-6 w-6 shrink-0 place-items-center rounded-lg bg-surface-subtle text-2xs font-extrabold text-text-muted">
                      {decision.no}
                    </span>
                    <span className="min-w-0">
                      <span className="block text-xs font-bold text-text-secondary">
                        {dealStageLabel(decision.stage).label}
                      </span>
                      {/* The server's own refusal message, verbatim — so the tooltip a rep reads
                          before clicking is exactly the error they would have got by clicking. */}
                      <span className="block text-2xs leading-snug text-text-muted [overflow-wrap:anywhere]">
                        {decision.blockedReason}
                      </span>
                    </span>
                  </li>
                ))}
              </ul>
            ) : null}
          </div>
        ) : null}
      </div>
    </Modal>
  );
}
