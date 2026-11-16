import { useId, useState } from 'react';
import { Link } from 'react-router-dom';
import { Button, buttonVariants } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { cn } from '../../utils/cn.js';
import { canCreateDealQuotationStandalone } from '../quotations/quotationMeta.js';
import { baht } from './boardFormat.js';
import { CASH_SERIES } from './CashCard.jsx';
import { weekEndLabel } from './cashFormat.js';
import { CallModal } from './FollowUpList.jsx';
import { dayLineLabel, nextVisit, shortWeekday, timeLabel, weekStartIso } from './visitWeek.js';

const CARD = 'grid gap-1 rounded-md border border-border bg-surface p-3.5';
const CARD_TITLE = 'm-0 text-base font-extrabold text-text';
// The quick row and the way-out row: a full-width 52px tile, so a thumb finds it without aiming.
// Labels wrap rather than clip at 320px ("นัดเข้าพบทั้งสัปดาห์" is the longest).
const TILE = 'min-h-[52px] mobile:min-h-[52px] w-full min-w-0 px-2 py-1.5 text-center text-sm leading-snug whitespace-normal';
const dealName = (d) => d.title || d.customerName || `#${d.ticketId}`;

/** "นัดถัดไป · 14:00" today, "นัดถัดไป · ศ. 13 · 10:00" later this week (visitWeek.nextVisit picks which visit). */
function nextHeading({ visit, isToday }) {
  const time = timeLabel(visit.plannedTime);
  if (isToday) return `นัดถัดไป · ${time || 'วันนี้'}`;
  return ['นัดถัดไป', `${shortWeekday(visit.plannedDate)} ${Number(visit.plannedDate.slice(8, 10))}`, time]
    .filter(Boolean).join(' · ');
}

function NextVisitCard({ next, onPlan }) {
  const ids = useId();
  if (!next) {
    return (
      <section data-testid="today-next-visit" aria-labelledby={`${ids}-h`} className={CARD}>
        <h2 id={`${ids}-h`} className="m-0 text-xs font-bold text-text-muted">นัดถัดไป</h2>
        <p className="m-0 text-base font-bold text-text">ไม่มีนัดที่เหลือสัปดาห์นี้</p>
        <Button type="button" variant="secondary" className="mt-1.5 justify-self-start" onClick={onPlan}>
          <Icon name="plus" size={16} />
          นัดเข้าพบ
        </Button>
      </section>
    );
  }
  const { visit } = next;
  const what = [visit.note, visit.ticketTitle].filter(Boolean).join(' · ');
  return (
    <section data-testid="today-next-visit" aria-labelledby={`${ids}-h`} className={CARD}>
      <h2 id={`${ids}-h`} className="m-0 text-xs font-bold text-primary">{nextHeading(next)}</h2>
      <b className="break-words text-lg leading-snug text-text">{visit.contactName}</b>
      {what ? <span className="break-words text-sm text-text-muted">{what}</span> : null}
    </section>
  );
}

function FollowUpCompact({ deals, quietDays }) {
  const ids = useId();
  return (
    <section data-testid="today-follow-up" aria-labelledby={`${ids}-h`} className="rounded-md border border-border bg-surface">
      <h2 id={`${ids}-h`} className={`${CARD_TITLE} px-3.5 pb-1 pt-3`}>{`ควรตามต่อ (${deals.length})`}</h2>
      {deals.length === 0 ? (
        <p className="m-0 px-3.5 pb-3 text-sm text-text-muted">{`ไม่มีงานค้าง — ทุกงานมีความเคลื่อนไหวใน ${quietDays} วันที่ผ่านมา`}</p>
      ) : (
        <ul className="m-0 list-none p-0 pb-1">
          {deals.map((deal) => (
            <li key={deal.ticketId} className="border-t border-border-subtle first:border-t-0">
              <Link
                to={`/tickets/${deal.ticketId}`}
                className="flex min-h-11 items-center justify-between gap-3 px-3.5 py-2 text-text no-underline hover:bg-surface-hover focus-visible:bg-surface-hover"
              >
                <span className="min-w-0 truncate text-sm font-bold">{dealName(deal)}</span>
                <span className="shrink-0 rounded-full bg-warning-bg px-2 py-px text-2xs font-bold text-warning-dark">
                  {`${deal.daysQuiet} วัน`}
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

const weekTotal = (w) => CASH_SERIES.reduce((sum, s) => sum + (Number(w[s.key] ?? 0) || 0), 0);

/** The first week from this one on with money still expected, labelled as CashCard labels its bars. */
function nextDueLabel(cash, todayIso) {
  const weeks = cash.weeks ?? [];
  const thisMonday = weekStartIso(todayIso);
  const i = weeks.findIndex((w) => w.weekStart >= thisMonday && weekTotal(w) > 0);
  return i < 0 ? null : weekEndLabel(weeks[i].weekStart, cash.month, i === weeks.length - 1);
}

/** เงินเข้าเดือนนี้ — CashCard's headline only: received, against expected, and the next week money is due. */
function CashCompact({ cash, todayIso }) {
  const ids = useId();
  const received = Number(cash.received ?? 0) || 0;
  const forecast = Number(cash.forecast ?? 0) || 0;
  const due = nextDueLabel(cash, todayIso);
  return (
    <section data-testid="today-cash" aria-labelledby={`${ids}-h`} className={CARD}>
      <h2 id={`${ids}-h`} className={CARD_TITLE}>เงินเข้าเดือนนี้</h2>
      {forecast === 0 && received === 0 && !due ? (
        <p className="m-0 text-sm text-text-muted">ยังไม่มีเงินที่คาดว่าจะเข้าเดือนนี้</p>
      ) : (
        <>
          <strong className="text-[22px] font-extrabold tabular-nums text-text">{baht(received)}</strong>
          <span className="text-sm text-text-muted">
            {[`จากที่คาด ${baht(forecast)}`, due ? `รอบถัดไป ${due}` : null].filter(Boolean).join(' · ')}
          </span>
        </>
      )}
    </section>
  );
}

/** โทรแล้ว needs a deal: ควรตามต่อ first (the reason to call), then the rest of the rep's open deals. */
function CallPicker({ followUp, board, onPick, onClose }) {
  const quietIds = new Set(followUp.map((d) => d.ticketId));
  const groups = [
    { label: 'ควรตามต่อ', deals: followUp },
    { label: 'งานอื่นของฉัน', deals: board.filter((d) => !quietIds.has(d.ticketId)) },
  ].filter((g) => g.deals.length);
  return (
    <Modal
      title="โทรหางานไหน"
      subtitle="เลือกงาน แล้วบันทึกผลการโทร"
      placement="sheet-on-phone"
      onClose={onClose}
      footer={<Button type="button" variant="secondary" onClick={onClose}>ยกเลิก</Button>}
    >
      <div className="grid gap-4">
        {groups.map((group) => (
          <section key={group.label} className="grid gap-1.5">
            <h3 className="m-0 text-sm font-bold text-text-muted">{group.label}</h3>
            <ul className="m-0 grid list-none gap-1.5 p-0">
              {group.deals.map((deal) => (
                <li key={deal.ticketId}>
                  <button
                    type="button"
                    onClick={() => onPick(deal)}
                    className="flex min-h-11 w-full items-center justify-between gap-3 rounded-md border border-border-input bg-surface px-3 py-2 text-left text-text hover:bg-surface-hover"
                  >
                    <span className="min-w-0 break-words text-sm font-bold">{dealName(deal)}</span>
                    {deal.quiet ? (
                      <span className="shrink-0 rounded-full bg-warning-bg px-2 py-px text-2xs font-bold text-warning-dark">
                        {`เงียบ ${deal.daysQuiet} วัน`}
                      </span>
                    ) : null}
                  </button>
                </li>
              ))}
            </ul>
          </section>
        ))}
      </div>
    </Modal>
  );
}

/**
 * วันนี้ของฉัน — the rep's home at phone width (≤720px, the app's `mobile` breakpoint), design a-mywork's phone block:
 * the date and title, นัดถัดไป, one large บันทึกเข้าพบ, the quick row, a compact ควรตามต่อ and เงินเข้าเดือนนี้. Every
 * action is an existing flow MyWork owns (its dialogs, its call mutation); "ดูหน้าเต็ม" returns the full stacked page,
 * so nothing reachable on a phone before this screen became unreachable.
 *
 * Not built from the drawing: "ส่งตัวอย่าง" — no activity kind or endpoint records a sample sent on a deal
 * (DealActivityKind has none), so the button would be a fake action.
 */
export function TodayOnPhone({
  work, failed, todayIso, user, loggingCall, onLogCall, onLog, onPlan, onCreate, onShowFull,
}) {
  const [picking, setPicking] = useState(false);
  const [calling, setCalling] = useState(null);
  const followUp = work?.followUp ?? [];
  const board = work?.board ?? [];
  const canCall = followUp.length > 0 || board.length > 0;
  const canQuote = canCreateDealQuotationStandalone(user);

  return (
    <section
      data-testid="today-phone"
      aria-labelledby="today-phone-title"
      className="grid min-w-0 gap-3.5 pb-[max(12px,env(safe-area-inset-bottom))]"
    >
      <header className="grid gap-0.5">
        <small className="text-sm font-bold text-text-muted">{dayLineLabel(todayIso)}</small>
        <h1 id="today-phone-title" className="m-0 text-[26px] font-extrabold leading-tight text-text">วันนี้ของฉัน</h1>
      </header>

      {failed ? <p className="m-0 text-sm font-bold text-danger">โหลดไม่สำเร็จ</p> : !work ? (
        <div className="grid gap-2" role="status" aria-busy="true" aria-label="กำลังโหลดงานวันนี้">
          <Skeleton height={88} />
          <Skeleton height={52} />
          <Skeleton height={120} />
        </div>
      ) : (
        <>
          <NextVisitCard next={nextVisit(work.visitsThisWeek, todayIso)} onPlan={onPlan} />

          <Button type="button" className="min-h-[56px] mobile:min-h-[56px] w-full text-base shadow-sm" onClick={onLog}>
            <Icon name="calendar" size={20} />
            บันทึกเข้าพบ
          </Button>

          {canCall || canQuote ? (
            <div data-testid="today-quick-actions" role="group" aria-label="ทำต่อ" className="grid auto-cols-fr grid-flow-col gap-2">
              {canCall ? (
                <Button type="button" variant="secondary" className={TILE} onClick={() => setPicking(true)}>
                  <Icon name="phone" size={16} />
                  โทรแล้ว
                </Button>
              ) : null}
              {canQuote ? (
                // The same entry point and gate as ใบเสนอราคา's own "สร้างใบเสนอราคา" (canCreateDealQuotationStandalone).
                <Link to="/quotations/new" className={cn(buttonVariants({ variant: 'secondary' }), TILE, 'no-underline')}>
                  <Icon name="fileText" size={16} />
                  ทำใบเสนอราคา
                </Link>
              ) : null}
            </div>
          ) : null}

          <FollowUpCompact deals={followUp} quietDays={work.quietDays ?? 14} />
          {work.cash ? <CashCompact cash={work.cash} todayIso={todayIso} /> : null}
        </>
      )}

      <nav aria-label="หน้าอื่น" className="grid grid-cols-2 gap-2">
        <Link to="/tickets" className={cn(buttonVariants({ variant: 'secondary' }), TILE, 'no-underline')}>ดูงานทั้งหมด</Link>
        <Link to="/visits" className={cn(buttonVariants({ variant: 'secondary' }), TILE, 'no-underline')}>นัดเข้าพบทั้งสัปดาห์</Link>
        <Button type="button" variant="secondary" className={TILE} onClick={onCreate}>
          <Icon name="plus" size={16} />
          งานใหม่
        </Button>
        <Button type="button" variant="secondary" className={TILE} onClick={onShowFull}>ดูหน้าเต็ม</Button>
      </nav>

      {picking ? (
        <CallPicker
          followUp={followUp}
          board={board}
          onClose={() => setPicking(false)}
          onPick={(deal) => {
            setPicking(false);
            setCalling(deal);
          }}
        />
      ) : null}
      {calling ? (
        <CallModal
          deal={calling}
          submitting={loggingCall}
          onClose={() => setCalling(null)}
          onSubmit={async (note) => {
            // A failure is toasted by MyWork and the dialog stays open with what was typed.
            if (await onLogCall(calling, note)) setCalling(null);
          }}
        />
      ) : null}
    </section>
  );
}
