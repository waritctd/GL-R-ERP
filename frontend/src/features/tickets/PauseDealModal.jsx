import { useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { ThaiDateInput } from '../../components/common/ThaiDateInput.jsx';
import { addDaysIso, bangkokTodayIso } from '../../utils/format.js';
import { FieldLabel, InfoNote, RadioCards } from './dealDialogParts.jsx';

/** `iso` + `months` calendar months, clamped to the target month's last day (31 Dec + 2 → 28 Feb). Pure date math on
 * a YYYY-MM-DD string, like addDaysIso. */
export function addMonthsIso(iso, months) {
  const [year, month, day] = iso.split('-').map(Number);
  const total = year * 12 + (month - 1) + months;
  const y = Math.floor(total / 12);
  const m = (total % 12) + 1;
  const lastDay = new Date(Date.UTC(y, m, 0)).getUTCDate();
  return `${String(y).padStart(4, '0')}-${String(m).padStart(2, '0')}-${String(Math.min(day, lastDay)).padStart(2, '0')}`;
}

const KIND_OPTIONS = [
  { value: 'ON_HOLD', title: 'พักชั่วคราว', sub: 'ลูกค้าเลื่อนโครงการ แต่ยังจะกลับมา' },
  { value: 'DORMANT', title: 'ไม่เคลื่อนไหว', sub: 'ติดต่อไม่ได้ ยังไม่อยากปิดงาน' },
];

/**
 * พักงาน (drawing m-hold, 2026-11-16) — ONE dialog for what used to be "พักดีลไว้" and "พัก dormant" (owner ruling).
 * The choice maps onto the two EXISTING lifecycles (ON_HOLD / DORMANT); no new lifecycle. Each choice is offered
 * only when the server lists it (`canHold` = PLACE_ON_HOLD, `canDormant` = MARK_DORMANT): on an ON_HOLD deal only
 * ไม่เคลื่อนไหว can be picked, exactly as before. วันที่จะกลับมาดู is optional (null = the pause behaves as it always
 * did); from that Asia/Bangkok date the deal comes back into ควรตามต่อ (QuietRule#returningSql).
 */
export function PauseDealModal({
  canHold, canDormant, initialKind = 'ON_HOLD', submitting, onClose, onSubmit,
  // Fix round 1, ruling 5: opened on an already-paused deal (`changing`) it changes the date in place — the same
  // request in the SAME state updates only the date and note on the server (no lifecycle switch, no notification).
  changing = false, currentReturnOn = null,
}) {
  const today = bangkokTodayIso();
  const fallbackKind = canHold ? 'ON_HOLD' : 'DORMANT';
  const [kind, setKind] = useState((initialKind === 'ON_HOLD' ? canHold : canDormant) ? initialKind : fallbackKind);
  const [returnOn, setReturnOn] = useState(currentReturnOn ?? '');
  // The server refuses a date before today (Asia/Bangkok); say so here instead of after a round trip.
  const pastDate = Boolean(returnOn) && returnOn < today;
  // Review round 2, L-5: the field refused a typed entry (it reverts to the old value and does not report a change).
  // Until the rep fixes it, or empties the field on purpose, nothing is sent — a refused date never becomes "no date".
  const [dateRefused, setDateRefused] = useState(false);
  const [note, setNote] = useState('');
  const chips = [
    { label: '2 สัปดาห์', value: addDaysIso(today, 14) },
    { label: '1 เดือน', value: addMonthsIso(today, 1) },
    { label: '2 เดือน', value: addMonthsIso(today, 2) },
  ];
  const options = KIND_OPTIONS.map((option) => ({
    ...option, disabled: option.value === 'ON_HOLD' ? !canHold : !canDormant,
  }));
  const canSubmit = !submitting && !pastDate && !dateRefused && (kind === 'ON_HOLD' ? canHold : canDormant);
  // Changing the date in place: staying in the same pause state is a date-only change (no notification); picking the
  // other state is a real switch, which notifies exactly as pausing does (TicketService.markDormant).
  const switching = changing && kind !== initialKind;

  return (
    <Modal
      title={changing ? 'เปลี่ยนวันที่จะกลับมาดู' : 'พักงาน'}
      // The drawing's "งานที่พักไว้ไม่นับเป็นงานเงียบ" contradicted ruling 4 (a deal whose date arrives IS counted with
      // the quiet ones), so the subtitle says what actually happens.
      subtitle="งานที่พักไว้ไม่นับเป็นงานเงียบ จนถึงวันที่จะกลับมาดู"
      onClose={onClose}
      placement="sheet-on-phone"
      testId="pause-deal-modal"
      footer={(
        <>
          <Button type="button" variant="secondary" onClick={onClose}>ยกเลิก</Button>
          <Button
            type="button"
            variant="primary"
            disabled={!canSubmit}
            onClick={() => onSubmit({ kind, pauseReturnOn: returnOn || null, note: note.trim() || undefined })}
          >
            {submitting ? 'กำลังบันทึก…' : switching ? 'เปลี่ยนแบบการพักและแจ้งเตือน' : changing ? 'บันทึกวันที่' : 'พักงาน'}
          </Button>
        </>
      )}
    >
      <div className="flex flex-col gap-4">
        <RadioCards label="แบบไหน" options={options} value={kind} onChange={setKind} />
        {changing ? (
          <InfoNote testId="pause-change-notice">
            เปลี่ยนเฉพาะวันที่ ไม่มีการแจ้งเตือน · ถ้าเปลี่ยนแบบการพัก ระบบจะแจ้งเตือนผู้จัดการฝ่ายขายและผู้บริหาร
            (และฝ่ายนำเข้า / บัญชี ถ้าเกี่ยวข้อง) เหมือนการพักครั้งใหม่
          </InfoNote>
        ) : null}
        <div className="grid gap-1.5">
          <FieldLabel htmlFor="pause-return-on">วันที่จะกลับมาดู</FieldLabel>
          <div className="flex flex-wrap items-center gap-2">
            <ThaiDateInput
              id="pause-return-on"
              value={returnOn}
              min={today}
              onChange={(event) => setReturnOn(event.target.value)}
              onErrorChange={(message) => setDateRefused(Boolean(message))}
              wrapperClassName="w-44"
            />
            {chips.map((chip) => (
              <button
                key={chip.label}
                type="button"
                aria-pressed={returnOn === chip.value}
                onClick={() => { setReturnOn(chip.value); setDateRefused(false); }}
                className={`min-h-11 rounded-full border px-3.5 text-xs font-bold ${
                  returnOn === chip.value ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface text-text hover:bg-surface-hover'
                }`}
              >
                {chip.label}
              </button>
            ))}
          </div>
          {pastDate ? (
            <p role="alert" className="m-0 text-xs font-bold text-danger">วันที่จะกลับมาดูต้องเป็นวันนี้หรือหลังจากนี้</p>
          ) : null}
          <span className="text-xs text-text-muted">ถึงวันนั้นงานจะกลับมาที่ “ควรตามต่อ” เอง</span>
        </div>
        <div className="grid gap-1.5">
          <FieldLabel htmlFor="pause-note" optional>หมายเหตุ</FieldLabel>
          <textarea
            id="pause-note"
            className="min-h-16"
            value={note}
            placeholder="เช่น เจ้าของโครงการรอผลขออนุญาตก่อสร้าง"
            onChange={(event) => setNote(event.target.value)}
          />
        </div>
      </div>
    </Modal>
  );
}
