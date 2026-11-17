import { useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { ThaiDateInput } from '../../components/common/ThaiDateInput.jsx';
import { bangkokTodayIso } from '../../utils/format.js';
import { FieldLabel, InfoNote } from './dealDialogParts.jsx';

/**
 * ย้ายไป S2 · สเปคผ่าน (drawing m-stage, 2026-11-16): the one stage a rep moves by hand from S1 (LEAD_APPROACH /
 * PRESENTATION → SPEC_APPROVED). From S3 the deal moves by its documents, which the note says.
 *
 * The server's readiness gate on a manual forward move is unchanged (spec §9: "a rep's manual move keeps it"): a
 * next follow-up date AND an activity since the last stage change. The parent reads which half is missing and this
 * dialog asks for exactly that — a follow-up date field (`needsFollowUp`) and the note made required (`needsActivity`;
 * the parent logs it as the activity) — rather than the old separate "เลื่อนไปขั้น N" form. When nothing is
 * missing it is the drawn two-line confirm.
 */
export function MoveToS2Modal({ deal, needsFollowUp = false, needsActivity = false, submitting, error, onClose, onSubmit }) {
  const [note, setNote] = useState('');
  const [nextFollowUpAt, setNextFollowUpAt] = useState('');
  const canSubmit = !submitting && (!needsFollowUp || Boolean(nextFollowUpAt)) && (!needsActivity || note.trim() !== '');

  return (
    <Modal
      title="ย้ายไป S2 · สเปคผ่าน"
      subtitle={deal?.projectName || deal?.title}
      onClose={onClose}
      placement="sheet-on-phone"
      testId="move-s2-modal"
      footer={(
        <>
          <Button type="button" variant="secondary" onClick={onClose}>ยกเลิก</Button>
          <Button
            type="button"
            variant="primary"
            disabled={!canSubmit}
            onClick={() => onSubmit({ note: note.trim() || null, nextFollowUpAt: needsFollowUp ? nextFollowUpAt : null })}
          >
            {submitting ? 'กำลังย้าย…' : 'ย้ายไป S2'}
          </Button>
        </>
      )}
    >
      <div className="flex flex-col gap-4">
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <span className="rounded-full bg-surface-subtle px-2.5 py-1 text-xs font-bold text-text-secondary">S1 อยู่ระหว่างเสนอสินค้า</span>
          <span aria-hidden="true" className="text-text-faint">→</span>
          <span className="rounded-full bg-primary/10 px-2.5 py-1 text-xs font-bold text-primary">S2 ผู้ออกแบบตกลงสเปค</span>
        </div>
        {needsFollowUp ? (
          <div className="grid gap-1.5">
            <FieldLabel htmlFor="move-s2-follow-up" required>วันติดตามครั้งถัดไป</FieldLabel>
            <ThaiDateInput
              id="move-s2-follow-up"
              value={nextFollowUpAt}
              min={bangkokTodayIso()}
              onChange={(event) => setNextFollowUpAt(event.target.value)}
              wrapperClassName="w-44"
            />
          </div>
        ) : null}
        <div className="grid gap-1.5">
          <FieldLabel htmlFor="move-s2-note" required={needsActivity} optional={!needsActivity}>โน้ตสั้น ๆ</FieldLabel>
          <textarea
            id="move-s2-note"
            className="min-h-16"
            value={note}
            placeholder="เช่น ผู้ออกแบบเลือก Raku 20×20 สีขาว"
            onChange={(event) => setNote(event.target.value)}
          />
          {needsActivity ? (
            <span className="text-xs text-text-muted">ยังไม่มีบันทึกการคุยหลังเปลี่ยนขั้นล่าสุด — โน้ตนี้จะบันทึกเป็นกิจกรรมก่อนย้าย</span>
          ) : null}
        </div>
        <InfoNote testId="move-s2-info">
          ตั้งแต่ S3 งานย้ายเอง ไม่ต้องกด: ใบเสนอราคาได้รับอนุมัติ → S3 · ได้รับมัดจำ → S7 · ส่งของครบ → S9
        </InfoNote>
        {error ? <p role="alert" className="m-0 rounded-md bg-danger-bg px-3 py-2 text-xs font-bold text-danger-dark">{error}</p> : null}
      </div>
    </Modal>
  );
}
