import { useId, useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { ThaiDateInput } from '../../components/common/ThaiDateInput.jsx';
import { timeLabel, weekRangeLabel, weekStartIso } from './visitWeek.js';

// The m-visit-log dialog's three outcomes. Stored as the note's first line (sales.visit and deal_activity have no
// outcome column), as the call dialog does; optional, because the tick itself is the fact being recorded.
const VISIT_OUTCOMES = ['ส่งตัวอย่าง', 'ขอใบเสนอราคา', 'แค่แนะนำ'];

const INPUT = 'w-full rounded-md border border-border-input bg-surface px-2.5 py-2 text-sm font-normal text-text';
const LABEL = 'grid gap-1.5 text-sm font-bold text-text';
const Optional = () => <em className="font-normal not-italic text-text-muted">(ไม่บังคับ)</em>;

const joinNote = (outcome, note) => [outcome, note.trim()].filter(Boolean).join(' — ') || undefined;
const dealLabel = (d) => d.title || d.customerName || `#${d.ticketId}`;

function Picks({ label, options, value, onChange }) {
  return (
    <div className="flex flex-wrap gap-2" role="radiogroup" aria-label={label}>
      {options.map(({ key, text }) => (
        <button
          key={key}
          type="button"
          role="radio"
          aria-checked={value === key}
          onClick={() => onChange(value === key ? null : key)}
          className={`min-h-[38px] rounded-full border px-3 text-sm font-bold mobile:min-h-[44px] ${value === key
            ? 'border-primary bg-primary-bg text-primary'
            : 'border-border-input bg-surface text-text-secondary hover:bg-surface-hover'}`}
        >
          {text}
        </button>
      ))}
    </div>
  );
}

function OutcomeField({ value, onChange }) {
  return (
    <fieldset className="m-0 grid gap-2 border-0 p-0">
      <legend className="mb-2 p-0 text-sm font-bold text-text">ผล <Optional /></legend>
      <Picks label="ผล" options={VISIT_OUTCOMES.map((o) => ({ key: o, text: o }))} value={value} onChange={onChange} />
    </fieldset>
  );
}

function NoteField({ value, onChange }) {
  return (
    <label className={LABEL}>
      <span>โน้ต <Optional /></span>
      <textarea value={value} onChange={(e) => onChange(e.target.value)} rows={3} className={INPUT} />
    </label>
  );
}

function DealSelect({ id, deals, value, onChange }) {
  return (
    <label className={LABEL} htmlFor={id}>
      <span>ดีล <Optional /></span>
      <select id={id} value={value} onChange={(e) => onChange(e.target.value)} className={INPUT}>
        <option value="">ไม่มีดีล</option>
        {deals.map((d) => <option key={d.ticketId} value={String(d.ticketId)}>{dealLabel(d)}</option>)}
      </select>
    </label>
  );
}

const footerHint = (text) => <span className="mr-auto text-xs text-text-muted mobile:hidden">{text}</span>;

/** Which week the chosen date lands in — the visit appears in that week of the card and the team grid. */
function weekHint(plannedDate, todayIso) {
  const monday = weekStartIso(plannedDate);
  return monday === weekStartIso(todayIso)
    ? `นัดนี้อยู่ในสัปดาห์นี้ · ${weekRangeLabel(monday)}`
    : `นัดนี้อยู่ในสัปดาห์ ${weekRangeLabel(monday, todayIso)}`;
}

/**
 * m-visit-plan — นัดเข้าพบ. A visit may be to a designer or owner before any deal exists, so ดีล is optional.
 * `initialDeal` (the follow-up row's deal) preselects the deal and prefills พบใคร with its designer.
 */
export function PlanVisitModal({ deals, initialDeal = null, todayIso, submitting, onClose, onSubmit }) {
  const ids = useId();
  const [contactName, setContactName] = useState(initialDeal?.designerName ?? '');
  const [ticketId, setTicketId] = useState(initialDeal ? String(initialDeal.ticketId) : '');
  const [plannedDate, setPlannedDate] = useState(todayIso);
  const [plannedTime, setPlannedTime] = useState('');
  const [note, setNote] = useState('');
  const ready = contactName.trim() && plannedDate;
  // The deal list is the rep's open deals; a prefilled deal outside it (should not happen) is still offered.
  const options = initialDeal && !deals.some((d) => d.ticketId === initialDeal.ticketId) ? [initialDeal, ...deals] : deals;

  const submit = () => onSubmit({
    contactName: contactName.trim(),
    ticketId: ticketId ? Number(ticketId) : null,
    plannedDate,
    plannedTime: plannedTime || null,
    note: note.trim() || null,
  });

  return (
    <Modal
      title="นัดเข้าพบ"
      subtitle="ไปพบผู้ออกแบบหรือเจ้าของได้ แม้ยังไม่มีดีล"
      onClose={onClose}
      footer={(
        <>
          <Button type="button" variant="secondary" onClick={onClose}>ยกเลิก</Button>
          <Button type="button" disabled={submitting || !ready} onClick={submit}>
            {submitting ? 'กำลังบันทึก…' : 'บันทึกนัด'}
          </Button>
        </>
      )}
    >
      <div className="grid gap-4">
        <label className={LABEL} htmlFor={`${ids}-contact`}>
          <span>พบใคร <span className="text-danger" aria-hidden="true">*</span></span>
          <input
            id={`${ids}-contact`}
            value={contactName}
            onChange={(e) => setContactName(e.target.value)}
            maxLength={200}
            required
            placeholder="เช่น คุณอรุณ · อรุณ อาร์คิเทค"
            className={INPUT}
          />
        </label>
        <DealSelect id={`${ids}-deal`} deals={options} value={ticketId} onChange={setTicketId} />
        <div className="grid grid-cols-2 gap-3 mobile-xs:grid-cols-1">
          <label className={LABEL}>
            <span>วันที่</span>
            <ThaiDateInput value={plannedDate} onChange={(e) => setPlannedDate(e.target.value)} className={INPUT} />
          </label>
          <label className={LABEL} htmlFor={`${ids}-time`}>
            <span>เวลา <Optional /></span>
            <input
              id={`${ids}-time`}
              type="time"
              value={plannedTime}
              onChange={(e) => setPlannedTime(e.target.value)}
              className={INPUT}
            />
          </label>
        </div>
        {plannedDate ? (
          <p className="m-0 -mt-2 text-xs text-text-muted" aria-live="polite">{weekHint(plannedDate, todayIso)}</p>
        ) : null}
        <label className={LABEL} htmlFor={`${ids}-note`}>
          <span>เรื่องที่จะคุย <Optional /></span>
          <textarea
            id={`${ids}-note`}
            value={note}
            onChange={(e) => setNote(e.target.value)}
            rows={2}
            placeholder="เช่น นำตัวอย่าง Raku ไปเทียบสี"
            className={INPUT}
          />
        </label>
      </div>
    </Modal>
  );
}

/** The tick on one planned visit: optional outcome + note, then done. */
export function VisitDoneModal({ visit, submitting, onClose, onSubmit }) {
  const [outcome, setOutcome] = useState(null);
  const [note, setNote] = useState('');
  return (
    <Modal
      title="เข้าพบแล้ว"
      subtitle={[visit.contactName, visit.ticketTitle].filter(Boolean).join(' · ')}
      onClose={onClose}
      footer={(
        <>
          {visit.ticketId ? footerHint('บันทึกแล้วดีลนี้ออกจาก “ควรตามต่อ”') : null}
          <Button type="button" variant="secondary" onClick={onClose}>ยกเลิก</Button>
          <Button type="button" disabled={submitting} onClick={() => onSubmit(joinNote(outcome, note))}>
            {submitting ? 'กำลังบันทึก…' : 'บันทึก'}
          </Button>
        </>
      )}
    >
      <div className="grid gap-4">
        <OutcomeField value={outcome} onChange={setOutcome} />
        <NoteField value={note} onChange={setNote} />
      </div>
    </Modal>
  );
}

const UNPLANNED = 'unplanned';

/**
 * m-visit-log — บันทึกเข้าพบ, the phone's one-step log after a visit: pick one of today's planned visits (it is
 * ticked done) or ไม่ได้นัด (a visit is created DONE for today, optionally on a deal).
 * onSubmit receives `{ visit, note }` or `{ contactName, ticketId, note }`.
 */
export function LogVisitModal({ todaysPlanned, deals, submitting, onClose, onSubmit }) {
  const ids = useId();
  const [choice, setChoice] = useState(todaysPlanned[0] ? String(todaysPlanned[0].id) : UNPLANNED);
  const [outcome, setOutcome] = useState(null);
  const [note, setNote] = useState('');
  const [contactName, setContactName] = useState('');
  const [ticketId, setTicketId] = useState('');
  const unplanned = choice === UNPLANNED;
  const ready = unplanned ? contactName.trim() : choice;

  const submit = () => {
    const text = joinNote(outcome, note);
    if (unplanned) {
      onSubmit({ contactName: contactName.trim(), ticketId: ticketId ? Number(ticketId) : null, note: text });
    } else {
      onSubmit({ visit: todaysPlanned.find((v) => String(v.id) === choice), note: text });
    }
  };

  const choices = [
    ...todaysPlanned.map((v) => ({
      key: String(v.id), text: [v.contactName, timeLabel(v.plannedTime)].filter(Boolean).join(' · '),
    })),
    { key: UNPLANNED, text: 'ไม่ได้นัด' },
  ];

  return (
    <Modal
      title="บันทึกเข้าพบ"
      subtitle="หลังเข้าพบลูกค้า — เลือกจากนัดวันนี้ หรือบันทึกที่ไม่ได้นัด"
      placement="sheet-on-phone"
      onClose={onClose}
      footer={(
        <>
          <Button type="button" variant="secondary" onClick={onClose}>ยกเลิก</Button>
          <Button type="button" disabled={submitting || !ready} onClick={submit}>
            {submitting ? 'กำลังบันทึก…' : 'บันทึก'}
          </Button>
        </>
      )}
    >
      <div className="grid gap-4">
        <fieldset className="m-0 grid gap-2 border-0 p-0">
          <legend className="mb-2 p-0 text-sm font-bold text-text">นัดวันนี้</legend>
          <Picks label="นัดวันนี้" options={choices} value={choice} onChange={(key) => setChoice(key ?? choice)} />
        </fieldset>
        {unplanned ? (
          <>
            <label className={LABEL} htmlFor={`${ids}-contact`}>
              <span>พบใคร <span className="text-danger" aria-hidden="true">*</span></span>
              <input
                id={`${ids}-contact`}
                value={contactName}
                onChange={(e) => setContactName(e.target.value)}
                maxLength={200}
                required
                className={INPUT}
              />
            </label>
            <DealSelect id={`${ids}-deal`} deals={deals} value={ticketId} onChange={setTicketId} />
          </>
        ) : null}
        <OutcomeField value={outcome} onChange={setOutcome} />
        <NoteField value={note} onChange={setNote} />
      </div>
    </Modal>
  );
}
