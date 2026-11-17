import { useId, useState } from 'react';
import { Button } from '../../components/common/Button.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { bangkokTodayIso } from '../../utils/format.js';
import { dealName, dealPickGroups } from './dealPickGroups.js';
import { FollowUpPicks } from './FollowUpPicks.jsx';

const INPUT = 'w-full rounded-md border border-border-input bg-surface px-2.5 py-2 text-base font-normal text-text';
const LABEL = 'grid gap-1.5 text-sm font-bold text-text';
const Required = () => <span className="text-danger" aria-hidden="true">*</span>;

// The server's cap on an activity's note (DealActivityRequest.note, @Size(max = 2000)) — what was sent IS that note.
const NOTE_MAX = 2000;

/**
 * ส่งตัวอย่าง — slice 6d, the prototype's item 6.7 (owner answer 1): one sheet with the three drawn fields, pick the
 * งาน · what was sent · follow up when. It records a deal activity of kind SAMPLE_SENT through the same
 * POST /api/tickets/{id}/activities as โทรแล้ว (the caller sends it); what was sent is the activity's note and is
 * required (TicketService#addActivity 400s a blank one), ตามต่อ is FollowUpPicks — the optional nextFollowUpAt of
 * slice 6.4, which writes only the deal's next follow-up date.
 *
 * Two ways in:
 *   - วันนี้ของฉัน: `followUp` + `board` (GET /api/sales/my-work) — the rep picks the งาน from the same groups โทรแล้ว's
 *     picker offers (dealPickGroups). A paused งาน coming back (`returning`) is not ACTIVE, so ตามต่อ is not offered
 *     for it (the server refuses a date there); before a งาน is picked it is offered, as the drawing shows it.
 *   - the งาน page: `deal` is fixed; `followUpAllowed` is the page's answer to "would the server take a date".
 *
 * onSubmit(deal, note, nextFollowUpAt) — `nextFollowUpAt` is `undefined` when none was picked (or none is allowed),
 * so the caller's request carries no key at all.
 */
export function SampleSentModal({
  deal = null, followUp = [], board = [], followUpAllowed = true, submitting, onClose, onSubmit,
}) {
  const ids = useId();
  const groups = deal ? [] : dealPickGroups(followUp, board);
  const options = groups.flatMap((g) => g.deals);
  const [pickedId, setPickedId] = useState('');
  const [note, setNote] = useState('');
  const [followUpDate, setFollowUpDate] = useState('');
  const chosen = deal ?? options.find((d) => String(d.ticketId) === pickedId) ?? null;
  const dateAllowed = deal ? followUpAllowed : !chosen?.returning;
  const ready = Boolean(chosen) && note.trim() !== '';

  const submit = () => onSubmit(chosen, note.trim(), (dateAllowed && followUpDate) || undefined);

  return (
    <Modal
      title="ส่งตัวอย่าง"
      subtitle={deal ? dealName(deal) : 'เลือกงาน แล้วบันทึกว่าส่งอะไรไป'}
      placement="sheet-on-phone"
      onClose={onClose}
      footer={(
        <>
          <span className="mr-auto text-xs text-text-muted mobile:hidden">บันทึกแล้วงานนี้ออกจาก “ควรตามต่อ”</span>
          <Button type="button" variant="secondary" onClick={onClose}>ยกเลิก</Button>
          <Button type="button" disabled={submitting || !ready} onClick={submit}>
            {submitting ? 'กำลังบันทึก…' : 'บันทึก'}
          </Button>
        </>
      )}
    >
      <div className="grid gap-4">
        {deal ? null : (
          <label className={LABEL} htmlFor={`${ids}-deal`}>
            <span>งาน <Required /></span>
            <select
              id={`${ids}-deal`}
              value={pickedId}
              onChange={(event) => {
                // A date picked for one งาน is not carried over to another.
                setPickedId(event.target.value);
                setFollowUpDate('');
              }}
              required
              className={`${INPUT} min-h-11`}
            >
              <option value="" disabled>เลือกงาน</option>
              {groups.map((group) => (
                <optgroup key={group.label} label={group.label}>
                  {group.deals.map((d) => <option key={d.ticketId} value={String(d.ticketId)}>{dealName(d)}</option>)}
                </optgroup>
              ))}
            </select>
          </label>
        )}
        <label className={LABEL} htmlFor={`${ids}-what`}>
          <span>ส่งอะไร <Required /></span>
          <input
            id={`${ids}-what`}
            value={note}
            onChange={(event) => setNote(event.target.value)}
            maxLength={NOTE_MAX}
            required
            autoComplete="off"
            placeholder="เช่น ตัวอย่างกระเบื้อง Raku 3 สี"
            className={`${INPUT} min-h-11`}
          />
        </label>
        {dateAllowed ? (
          // Keyed by the งาน: switching it starts the control over too (the date itself is cleared on the switch).
          <FollowUpPicks
            key={chosen ? chosen.ticketId : 'none'}
            label="ตามต่อ"
            todayIso={bangkokTodayIso()}
            value={followUpDate}
            onChange={setFollowUpDate}
          />
        ) : null}
      </div>
    </Modal>
  );
}
