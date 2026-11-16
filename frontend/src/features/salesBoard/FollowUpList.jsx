import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { activityKindLabel } from '../tickets/dealTrackingMeta.js';
import { columnLabel, money } from './boardFormat.js';

// The m-call dialog's four outcomes. Stored as the activity note's first line (deal_activity has no outcome
// column); the rep's own note follows.
//
// Every outcome — ไม่รับสาย (no answer) included — writes a CALL deal_activity row, and QuietRule counts any
// deal_activity as a touch, so an unanswered call also clears the quiet clock. Accepted (round-2 review LOW 3): a
// rep who keeps trying is still working the deal, and the outcome line stays visible as "ครั้งล่าสุด: …" on the
// follow-up row. Spec 2026-11-15 §7.5 records the same decision.
const CALL_OUTCOMES = ['คุยแล้ว ยังสนใจ', 'ขอใบเสนอราคาใหม่', 'ไม่รับสาย', 'ขอเลื่อนโครงการ'];

/** m-call. Exported for the phone's โทรแล้ว quick action, which picks the deal first. */
export function CallModal({ deal, submitting, onClose, onSubmit }) {
  const [outcome, setOutcome] = useState(null);
  const [note, setNote] = useState('');
  const submit = () => onSubmit([outcome, note.trim()].filter(Boolean).join(' — ') || undefined);

  return (
    <Modal
      title="โทรแล้ว"
      subtitle={`${deal.title || deal.customerName} · เงียบ ${deal.daysQuiet} วัน`}
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

/**
 * ควรตามต่อ — the rep's quiet deals as served (SalesBoardService ranks them by days × value, max 5). One tap each:
 * โทรแล้ว logs a CALL activity (which clears the quiet clock), นัดเข้าพบ opens the plan-visit dialog on this deal (the
 * visit clears the clock once it is ticked done), ปิดดีลนี้ hands off to the existing mark-lost flow.
 * When the rep has more quiet deals than are listed ({@code quietTotal}), ดูทั้งหมด (n) calls {@code onShowAll}, which
 * opens the board filtered to quiet deals.
 */
export function FollowUpList({ deals, quietDays, quietTotal = 0, onShowAll, onLogCall, onBookVisit, onCloseDeal, loggingCall }) {
  const [calling, setCalling] = useState(null);

  if (deals.length === 0) {
    return (
      <div className="flex items-center gap-3 px-5 py-4 text-text-muted mobile:px-3.5">
        <Icon name="check" size={18} />
        <span className="text-sm font-bold">ไม่มีดีลค้าง — ทุกดีลมีความเคลื่อนไหวใน {quietDays} วันที่ผ่านมา</span>
      </div>
    );
  }

  return (
    <>
      <ul className="m-0 list-none p-0">
        {deals.map((deal) => (
          <li
            key={deal.ticketId}
            data-testid={`follow-up-${deal.ticketId}`}
            className="grid gap-2 border-t border-border-subtle px-5 py-3 first:border-t-0 mobile:px-3.5"
          >
            <div className="flex min-w-0 items-start justify-between gap-3">
              <div className="grid min-w-0 gap-0.5">
                <Link
                  to={`/tickets/${deal.ticketId}`}
                  className="min-w-0 break-words text-sm font-bold text-text no-underline hover:underline focus-visible:underline"
                >
                  {deal.title || deal.customerName}
                </Link>
                <span className="min-w-0 break-words text-xs text-text-muted">
                  {[columnLabel(deal.boardColumn), deal.designerName ?? deal.customerName].filter(Boolean).join(' · ')}
                </span>
                {deal.lastActivityKind ? (
                  <span className="min-w-0 break-words text-xs text-text-muted">
                    {`ครั้งล่าสุด: ${[activityKindLabel(deal.lastActivityKind), deal.lastActivityNote].filter(Boolean).join(' · ')}`}
                  </span>
                ) : null}
              </div>
              <span className="grid shrink-0 justify-items-end gap-1">
                <span className="text-sm font-bold tabular-nums text-text">{money(deal.value, deal.currency)}</span>
                <span className="rounded-full bg-warning-bg px-2 py-px text-2xs font-bold text-warning-dark">
                  เงียบ {deal.daysQuiet} วัน
                </span>
              </span>
            </div>
            <div className="flex flex-wrap gap-2">
              <Button type="button" size="sm" variant="secondary" onClick={() => setCalling(deal)}>
                <Icon name="phone" size={14} />
                โทรแล้ว
              </Button>
              <Button type="button" size="sm" variant="secondary" onClick={() => onBookVisit(deal)}>
                <Icon name="calendar" size={14} />
                นัดเข้าพบ
              </Button>
              <Button type="button" size="sm" variant="secondary" onClick={() => onCloseDeal(deal)}>ปิดดีลนี้</Button>
            </div>
          </li>
        ))}
      </ul>
      {quietTotal > deals.length && onShowAll ? (
        <div className="border-t border-border-subtle px-5 py-2.5 mobile:px-3.5">
          <Button type="button" size="sm" variant="secondary" onClick={onShowAll}>{`ดูทั้งหมด (${quietTotal})`}</Button>
        </div>
      ) : null}
      {calling ? (
        <CallModal
          deal={calling}
          submitting={loggingCall}
          onClose={() => setCalling(null)}
          onSubmit={async (note) => {
            // A failure is reported by the caller (toast) and the dialog stays open with what was typed.
            if (await onLogCall(calling, note)) setCalling(null);
          }}
        />
      ) : null}
    </>
  );
}
