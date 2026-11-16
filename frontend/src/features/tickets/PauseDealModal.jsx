import { useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { ThaiDateInput } from '../../components/common/ThaiDateInput.jsx';
import { addDaysIso, bangkokTodayIso } from '../../utils/format.js';
import { FieldLabel, RadioCards } from './dealDialogParts.jsx';

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
export function PauseDealModal({ canHold, canDormant, initialKind = 'ON_HOLD', submitting, onClose, onSubmit }) {
  const today = bangkokTodayIso();
  const fallbackKind = canHold ? 'ON_HOLD' : 'DORMANT';
  const [kind, setKind] = useState((initialKind === 'ON_HOLD' ? canHold : canDormant) ? initialKind : fallbackKind);
  const [returnOn, setReturnOn] = useState('');
  const [note, setNote] = useState('');
  const chips = [
    { label: '2 สัปดาห์', value: addDaysIso(today, 14) },
    { label: '1 เดือน', value: addMonthsIso(today, 1) },
    { label: '2 เดือน', value: addMonthsIso(today, 2) },
  ];
  const options = KIND_OPTIONS.map((option) => ({
    ...option, disabled: option.value === 'ON_HOLD' ? !canHold : !canDormant,
  }));
  const canSubmit = !submitting && (kind === 'ON_HOLD' ? canHold : canDormant);

  return (
    <Modal
      title="พักงาน"
      subtitle="งานที่พักไว้ไม่นับเป็นงานเงียบ"
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
            {submitting ? 'กำลังบันทึก…' : 'พักงาน'}
          </Button>
        </>
      )}
    >
      <div className="flex flex-col gap-4">
        <RadioCards label="แบบไหน" options={options} value={kind} onChange={setKind} />
        <div className="grid gap-1.5">
          <FieldLabel htmlFor="pause-return-on">วันที่จะกลับมาดู</FieldLabel>
          <div className="flex flex-wrap items-center gap-2">
            <ThaiDateInput
              id="pause-return-on"
              value={returnOn}
              min={today}
              onChange={(event) => setReturnOn(event.target.value)}
              wrapperClassName="w-44"
            />
            {chips.map((chip) => (
              <button
                key={chip.label}
                type="button"
                aria-pressed={returnOn === chip.value}
                onClick={() => setReturnOn(chip.value)}
                className={`min-h-11 rounded-full border px-3.5 text-xs font-bold ${
                  returnOn === chip.value ? 'border-primary bg-primary/10 text-primary' : 'border-border bg-surface text-text hover:bg-surface-hover'
                }`}
              >
                {chip.label}
              </button>
            ))}
          </div>
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
