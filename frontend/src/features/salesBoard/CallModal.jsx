import { useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';

// The m-call dialog's four outcomes. Stored as the activity note's first line (deal_activity has no outcome
// column); the rep's own note follows.
//
// Every outcome — ไม่รับสาย (no answer) included — writes a CALL deal_activity row, and QuietRule counts any
// deal_activity as a touch, so an unanswered call also clears the quiet clock. Accepted (round-2 review LOW 3): a
// rep who keeps trying is still working the deal, and the outcome line stays visible as "ครั้งล่าสุด: …" on the
// follow-up row. Spec 2026-11-15 §7.5 records the same decision.
const CALL_OUTCOMES = ['คุยแล้ว ยังสนใจ', 'ขอใบเสนอราคาใหม่', 'ไม่รับสาย', 'ขอเลื่อนโครงการ'];

/**
 * m-call — โทรแล้ว. Shared by งานของฉัน's follow-up list and the deal page's header (extracted from FollowUpList so the
 * two open the SAME dialog). `deal` needs a title or customerName; `daysQuiet` is shown when the caller knows it (the
 * follow-up list does; the deal page does not). onSubmit receives the joined note.
 */
export function CallModal({ deal, submitting, onClose, onSubmit }) {
  const [outcome, setOutcome] = useState(null);
  const [note, setNote] = useState('');
  const submit = () => onSubmit([outcome, note.trim()].filter(Boolean).join(' — ') || undefined);
  const name = deal.title || deal.customerName;

  return (
    <Modal
      title="โทรแล้ว"
      subtitle={deal.daysQuiet != null ? `${name} · เงียบ ${deal.daysQuiet} วัน` : name}
      onClose={onClose}
      footer={(
        <>
          <span className="mr-auto text-xs text-text-muted mobile:hidden">บันทึกแล้วดีลนี้ออกจาก “ควรตามต่อ”</span>
          <Button type="button" variant="secondary" onClick={onClose}>ยกเลิก</Button>
          {/* An outcome is required (review M4): an empty save would clear the quiet clock with nothing logged. */}
          <Button type="button" disabled={submitting || !outcome} onClick={submit}>{submitting ? 'กำลังบันทึก…' : 'บันทึก'}</Button>
        </>
      )}
    >
      <div className="grid gap-4">
        <fieldset className="m-0 grid gap-2 border-0 p-0">
          <legend className="mb-2 p-0 text-sm font-bold text-text">ผลการโทร</legend>
          <div className="flex flex-wrap gap-2" role="radiogroup" aria-label="ผลการโทร">
            {CALL_OUTCOMES.map((label) => (
              <button
                key={label}
                type="button"
                role="radio"
                aria-checked={outcome === label}
                onClick={() => setOutcome(outcome === label ? null : label)}
                className={`min-h-[38px] rounded-full border px-3 text-sm font-bold mobile:min-h-[44px] ${outcome === label
                  ? 'border-primary bg-primary-bg text-primary'
                  : 'border-border-input bg-surface text-text-secondary hover:bg-surface-hover'}`}
              >
                {label}
              </button>
            ))}
          </div>
        </fieldset>
        <label className="grid gap-1.5 text-sm font-bold text-text">
          <span>โน้ต <em className="font-normal not-italic text-text-muted">(ไม่บังคับ)</em></span>
          <textarea
            value={note}
            onChange={(event) => setNote(event.target.value)}
            rows={3}
            className="w-full rounded-md border border-border-input bg-surface p-2.5 text-sm font-normal text-text"
          />
        </label>
      </div>
    </Modal>
  );
}
