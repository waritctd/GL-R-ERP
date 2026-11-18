import { useId, useState } from 'react';
import { ThaiDateInput } from '../../components/common/ThaiDateInput.jsx';
import { addDaysIso } from '../../utils/format.js';
import { shortDayLabel } from './visitWeek.js';

// Slice 6.4 (prototype screens E and F): ตามครั้งถัดไป on โทรแล้ว, ตามต่อ on the visit dialogs. The picked date is the
// deal's next follow-up date — the one ข้อมูลงาน shows as "ติดตามครั้งถัดไป" and the stage-readiness gate reads. It is
// sent as the optional `nextFollowUpAt` of the activity / visit request, which writes ONLY sales.ticket
// .next_follow_up_at (never PUT /tracking). Nothing is picked until the rep picks: skipping it keeps the deal's date.
const CHOICES = [
  { key: '3d', text: '3 วัน', days: 3 },
  { key: '1w', text: '1 สัปดาห์', days: 7 },
  { key: '2w', text: '2 สัปดาห์', days: 14 },
  { key: 'pick', text: 'เลือกวัน', days: null },
];

// The same pill as the dialogs' outcome choices (CallModal, VisitDialogs' Picks): 44px on a phone.
const PILL = 'min-h-[38px] rounded-full border px-3 text-sm font-bold mobile:min-h-[44px]';
const ON = 'border-primary bg-primary-bg text-primary';
const OFF = 'border-border-input bg-surface text-text-secondary hover:bg-surface-hover';

/**
 * `value` is '' or an ISO date; `todayIso` is the Asia/Bangkok date the quick choices count from (and the earliest day
 * เลือกวัน accepts — the server refuses a past date). Tapping the picked choice again clears it.
 *
 * The checked pill is DERIVED from `value` (fix round 1, M2): a quick choice when the value is its date, เลือกวัน for
 * any other date. The only local state is "เลือกวัน opened, no date typed yet"; a parent that switches what the date is
 * for remounts this control (a `key`), which clears that too.
 */
export function FollowUpPicks({ label = 'ตามครั้งถัดไป', todayIso, value, onChange }) {
  const ids = useId();
  const [picking, setPicking] = useState(false);
  const preset = value
    ? (CHOICES.find((option) => option.days != null && addDaysIso(todayIso, option.days) === value)?.key ?? 'pick')
    : null;
  const choice = preset ?? (picking ? 'pick' : null);

  function pick(option) {
    if (choice === option.key) {
      setPicking(false);
      onChange('');
      return;
    }
    setPicking(option.days == null);
    onChange(option.days == null ? '' : addDaysIso(todayIso, option.days));
  }

  return (
    <fieldset className="m-0 grid gap-2 border-0 p-0">
      <legend className="mb-2 p-0 text-sm font-bold text-text">
        {label} <em className="font-normal not-italic text-text-muted">(ไม่บังคับ)</em>
      </legend>
      <div className="flex flex-wrap gap-2" role="radiogroup" aria-label={label}>
        {CHOICES.map((option) => (
          <button
            key={option.key}
            type="button"
            role="radio"
            aria-checked={choice === option.key}
            onClick={() => pick(option)}
            className={`${PILL} ${choice === option.key ? ON : OFF}`}
          >
            {option.text}
          </button>
        ))}
      </div>
      {choice === 'pick' ? (
        <ThaiDateInput
          id={`${ids}-date`}
          aria-label="วันที่ตามครั้งถัดไป"
          value={value}
          min={todayIso}
          onChange={(event) => onChange(event.target.value)}
          className="w-full rounded-md border border-border-input bg-surface px-2.5 py-2 text-sm font-normal text-text"
        />
      ) : null}
      {value ? (
        <p className="m-0 text-xs text-text-muted" aria-live="polite">
          {`ตามครั้งถัดไป ${shortDayLabel(value, todayIso)} · แสดงในข้อมูลงาน`}
        </p>
      ) : null}
    </fieldset>
  );
}

/** The request with `nextFollowUpAt` only when a date was picked — an unused control leaves it exactly as before. */
export function withFollowUp(payload, nextFollowUpAt) {
  return nextFollowUpAt ? { ...payload, nextFollowUpAt } : payload;
}
