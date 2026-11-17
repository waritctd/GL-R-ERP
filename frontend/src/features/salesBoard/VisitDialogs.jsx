import { useId, useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { ThaiDateInput } from '../../components/common/ThaiDateInput.jsx';
import { bangkokTodayIso } from '../../utils/format.js';
import { FollowUpPicks, withFollowUp } from './FollowUpPicks.jsx';
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
      <span>งาน <Optional /></span>
      <select id={id} value={value} onChange={(e) => onChange(e.target.value)} className={INPUT}>
        <option value="">ไม่มีงาน</option>
        {deals.map((d) => <option key={d.ticketId} value={String(d.ticketId)}>{dealLabel(d)}</option>)}
      </select>
    </label>
  );
}

/**
 * Slice 6.4: whether ตามต่อ may set this visit's deal's follow-up date — the server's own answer, VisitDto
 * .dealFollowUpOpen (fix round 1): on a deal, the visit's owner still holds it (so the tick lands on it) and the deal is
 * ACTIVE (TicketService#requireVisitFollowUpWritable). dealMovedToName could not say this: it is empty for a deal paused
 * after the visit was planned, and for an owner with no Thai name.
 */
const landsOnDeal = (visit) => Boolean(visit?.ticketId) && visit.dealFollowUpOpen === true;

function FollowUpField({ value, onChange }) {
  return <FollowUpPicks label="ตามต่อ" todayIso={bangkokTodayIso()} value={value} onChange={onChange} />;
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
      subtitle="ไปพบผู้ออกแบบหรือเจ้าของได้ แม้ยังไม่มีงาน"
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

/**
 * The tick on one planned visit: optional outcome + note, then done. ตามต่อ (slice 6.4) only when the tick lands on the
 * visit's deal; onSubmit receives the joined note and the picked date (`undefined` when none or not on a deal).
 */
export function VisitDoneModal({ visit, submitting, onClose, onSubmit }) {
  const [outcome, setOutcome] = useState(null);
  const [note, setNote] = useState('');
  const [followUp, setFollowUp] = useState('');
  const onDeal = landsOnDeal(visit);
  return (
    <Modal
      title="เข้าพบแล้ว"
      subtitle={[visit.contactName, visit.ticketTitle].filter(Boolean).join(' · ')}
      onClose={onClose}
      footer={(
        <>
          {visit.ticketId ? footerHint('บันทึกแล้วงานนี้ออกจาก “ควรตามต่อ”') : null}
          <Button type="button" variant="secondary" onClick={onClose}>ยกเลิก</Button>
          <Button
            type="button"
            disabled={submitting}
            onClick={() => onSubmit(joinNote(outcome, note), (onDeal && followUp) || undefined)}
          >
            {submitting ? 'กำลังบันทึก…' : 'บันทึก'}
          </Button>
        </>
      )}
    >
      <div className="grid gap-4">
        <OutcomeField value={outcome} onChange={setOutcome} />
        <NoteField value={note} onChange={setNote} />
        {onDeal ? <FollowUpField value={followUp} onChange={setFollowUp} /> : null}
      </div>
    </Modal>
  );
}

const UNPLANNED = 'unplanned';

/**
 * m-visit-log — บันทึกเข้าพบ, the phone's one-step log after a visit: pick one of today's planned visits (it is
 * ticked done) or ไม่ได้นัด (a visit is created DONE for today, optionally on a deal).
 * onSubmit receives `{ visit, note }` or `{ contactName, ticketId, note }`, plus `nextFollowUpAt` (slice 6.4 ตามต่อ)
 * only when one was picked and the log lands on a deal — the picked visit's, or the deal chosen for ไม่ได้นัด (the
 * rep's own deals, so the log lands on it).
 */
export function LogVisitModal({ todaysPlanned, deals, submitting, onClose, onSubmit }) {
  const ids = useId();
  const [choice, setChoice] = useState(todaysPlanned[0] ? String(todaysPlanned[0].id) : UNPLANNED);
  const [outcome, setOutcome] = useState(null);
  const [note, setNote] = useState('');
  const [contactName, setContactName] = useState('');
  const [ticketId, setTicketId] = useState('');
  const [followUp, setFollowUp] = useState('');
  const unplanned = choice === UNPLANNED;
  const ready = unplanned ? contactName.trim() : choice;
  const picked = unplanned ? null : todaysPlanned.find((v) => String(v.id) === choice);
  const onDeal = unplanned ? Boolean(ticketId) : landsOnDeal(picked);
  const nextFollowUpAt = onDeal ? followUp : '';
  // A date picked for one visit or deal is not carried to another (value here, the pill by the field's key below).
  const pickVisit = (key) => {
    if (key !== choice) setFollowUp('');
    setChoice(key);
  };
  const pickDeal = (id) => {
    if (id !== ticketId) setFollowUp('');
    setTicketId(id);
  };

  const submit = () => {
    const text = joinNote(outcome, note);
    if (unplanned) {
      onSubmit(withFollowUp(
        { contactName: contactName.trim(), ticketId: ticketId ? Number(ticketId) : null, note: text }, nextFollowUpAt,
      ));
    } else {
      onSubmit(withFollowUp({ visit: picked, note: text }, nextFollowUpAt));
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
          <Picks label="นัดวันนี้" options={choices} value={choice} onChange={(key) => pickVisit(key ?? choice)} />
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
            <DealSelect id={`${ids}-deal`} deals={deals} value={ticketId} onChange={pickDeal} />
          </>
        ) : null}
        <OutcomeField value={outcome} onChange={setOutcome} />
        <NoteField value={note} onChange={setNote} />
        {onDeal ? <FollowUpField key={`${choice}:${ticketId}`} value={followUp} onChange={setFollowUp} /> : null}
      </div>
    </Modal>
  );
}
