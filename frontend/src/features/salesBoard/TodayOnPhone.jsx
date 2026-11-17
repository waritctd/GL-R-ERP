import { useId } from 'react';
import { Link } from 'react-router-dom';
import { Button, buttonVariants } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Modal } from '../../components/common/Modal.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { cn } from '../../utils/cn.js';
import { canCreateDealQuotationStandalone } from '../quotations/quotationMeta.js';
import { baht } from './boardFormat.js';
import { returningLabel } from './FollowUpList.jsx';
import { CASH_SERIES } from './CashCard.jsx';
import { weekEndDay } from './cashFormat.js';
import { VisitRow } from './VisitsThisWeek.jsx';
import { dealName, dealPickGroups } from './dealPickGroups.js';
import { dayLineLabel, isOverdue, nextVisit, shortWeekday, timeLabel, weekStartIso } from './visitWeek.js';

const CARD = 'grid gap-1 rounded-md border border-border bg-surface p-3.5';
const CARD_TITLE = 'm-0 text-base font-extrabold text-text';
// The quick row and the way-out row: a full-width 52px tile, so a thumb finds it without aiming.
// Labels wrap rather than clip at 320px ("นัดเข้าพบทั้งสัปดาห์" is the longest).
const TILE = 'min-h-[52px] mobile:min-h-[52px] w-full min-w-0 px-2 py-1.5 text-center text-sm leading-snug whitespace-normal';

/** "นัดถัดไป · 14:00" today, "นัดถัดไป · ศ. 13 · 10:00" on a later day (visitWeek.nextVisit picks which visit). */
function nextHeading({ visit, isToday }) {
  const time = timeLabel(visit.plannedTime);
  if (isToday) return `นัดถัดไป · ${time || 'วันนี้'}`;
  return ['นัดถัดไป', `${shortWeekday(visit.plannedDate)} ${Number(visit.plannedDate.slice(8, 10))}`, time]
    .filter(Boolean).join(' · ');
}

/** The empty card must stay true while visits are missed (ruling 1): "nothing NEW ahead", and where the missed ones are. */
function NextVisitCard({ next, missedCount, onPlan }) {
  const ids = useId();
  if (!next) {
    return (
      <section data-testid="today-next-visit" aria-labelledby={`${ids}-h`} className={CARD}>
        <h2 id={`${ids}-h`} className="m-0 text-xs font-bold text-text-muted">นัดถัดไป</h2>
        <p className="m-0 text-base font-bold text-text">
          {missedCount > 0 ? `ไม่มีนัดใหม่ใน 7 วันข้างหน้า · ค้าง ${missedCount} นัดด้านล่าง` : 'ไม่มีนัดใน 7 วันข้างหน้า'}
        </p>
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

/** ค้าง n นัด — visits still PLANNED after their day: this week's and those carried over from earlier weeks. */
function MissedVisits({ visits, todayIso, onTick, onCancel }) {
  const ids = useId();
  if (!visits.length) return null;
  return (
    <section data-testid="today-missed" aria-labelledby={`${ids}-h`} className="rounded-md border border-warning-border bg-surface">
      <div className="grid gap-0.5 px-3.5 pb-1 pt-3">
        <h2 id={`${ids}-h`} className={CARD_TITLE}>{`ค้าง ${visits.length} นัด`}</h2>
        <small className="text-xs text-text-muted">ยังไม่ได้ติ๊ก — ติ๊กว่าไปแล้ว หรือยกเลิก</small>
      </div>
      <ul className="m-0 list-none p-0">
        {visits.map((visit) => (
          <VisitRow key={visit.id} visit={visit} todayIso={todayIso} onDone={onTick} onCancel={onCancel} />
        ))}
      </ul>
    </section>
  );
}

/**
 * ควรตามต่อ, compact. A quiet row is one link with its days-quiet chip. A RETURNING row (V220: a paused deal whose
 * วันที่จะกลับมาดู has arrived) carries what the desktop list (FollowUpList) gives it: the return label instead of
 * days quiet, and ดำเนินการต่อ — MyWork's own resume mutation — beside the link, never inside it.
 */
function FollowUpCompact({ deals, quietDays, onResume, resuming = false }) {
  const ids = useId();
  return (
    <section data-testid="today-follow-up" aria-labelledby={`${ids}-h`} className="rounded-md border border-border bg-surface">
      <h2 id={`${ids}-h`} className={`${CARD_TITLE} px-3.5 pb-1 pt-3`}>{`ควรตามต่อ (${deals.length})`}</h2>
      {deals.length === 0 ? (
        <p className="m-0 px-3.5 pb-3 text-sm text-text-muted">{`ไม่มีงานค้าง — ทุกงานมีความเคลื่อนไหวใน ${quietDays} วันที่ผ่านมา`}</p>
      ) : (
        <ul className="m-0 list-none p-0 pb-1">
          {deals.map((deal) => (
            <li key={deal.ticketId} data-testid={`today-follow-up-${deal.ticketId}`} className="border-t border-border-subtle first:border-t-0">
              {deal.returning ? (
                <div className="flex items-center gap-2 pr-3.5">
                  <Link
                    to={`/tickets/${deal.ticketId}`}
                    className="grid min-h-11 min-w-0 flex-1 content-center gap-0.5 py-2 pl-3.5 text-text no-underline hover:bg-surface-hover focus-visible:bg-surface-hover"
                  >
                    <span className="min-w-0 truncate text-sm font-bold">{dealName(deal)}</span>
                    <span className="justify-self-start rounded-full bg-info-bg px-2 py-px text-2xs font-bold text-info-dark">
                      {returningLabel(deal)}
                    </span>
                  </Link>
                  <Button type="button" variant="secondary" size="sm" className="min-h-11 mobile:min-h-11 shrink-0" disabled={resuming} onClick={() => onResume?.(deal)}>
                    ดำเนินการต่อ
                  </Button>
                </div>
              ) : (
                <Link
                  to={`/tickets/${deal.ticketId}`}
                  className="flex min-h-11 items-center justify-between gap-3 px-3.5 py-2 text-text no-underline hover:bg-surface-hover focus-visible:bg-surface-hover"
                >
                  <span className="min-w-0 truncate text-sm font-bold">{dealName(deal)}</span>
                  <span className="shrink-0 rounded-full bg-warning-bg px-2 py-px text-2xs font-bold text-warning-dark">
                    {`${deal.daysQuiet} วัน`}
                  </span>
                </Link>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

const weekTotal = (w) => CASH_SERIES.reduce((sum, s) => sum + (Number(w[s.key] ?? 0) || 0), 0);

/** The first week from this one on with money still expected: CashCard's bar date for it, as drawn ("13 พ.ย."). */
function nextDueLabel(cash, todayIso) {
  const weeks = cash.weeks ?? [];
  const thisMonday = weekStartIso(todayIso);
  const i = weeks.findIndex((w) => w.weekStart >= thisMonday && weekTotal(w) > 0);
  return i < 0 ? null : weekEndDay(weeks[i].weekStart, cash.month, i === weeks.length - 1);
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

/**
 * โทรแล้ว needs a deal: ควรตามต่อ first (the reason to call), then the rest of the rep's open deals. Rendered by MyWork
 * with its other dialogs, so it survives the switch between the phone screen and the full page.
 */
export function CallPicker({ followUp, board, onPick, onClose }) {
  const groups = dealPickGroups(followUp, board);
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


/** this week's PLANNED visits whose day has passed, after the ones carried over from earlier weeks, oldest first. */
function missedVisits(work, carriedOver, todayIso) {
  const thisWeek = (work?.visitsThisWeek ?? []).filter((v) => isOverdue(v, todayIso));
  const seen = new Set(carriedOver.map((v) => v.id));
  return [...carriedOver, ...thisWeek.filter((v) => !seen.has(v.id))];
}

/** this week's visits with the look-ahead's (today to `aheadTo`), the look-ahead's copy winning for the same id. */
function upcoming(work, ahead) {
  const byId = new Map((work?.visitsThisWeek ?? []).map((v) => [v.id, v]));
  ahead.forEach((v) => byId.set(v.id, v));
  return [...byId.values()];
}

/**
 * วันนี้ของฉัน — the rep's home at phone width (≤720px, the app's `mobile` breakpoint), design a-mywork's phone block:
 * the date and title, นัดถัดไป, one large บันทึกเข้าพบ, the quick row, ค้าง n นัด, a compact ควรตามต่อ and
 * เงินเข้าเดือนนี้. Every action is an existing flow MyWork owns (its dialogs, its mutations — the call picker and call
 * dialog included); "ดูหน้าเต็ม" returns the full stacked page, so nothing reachable on a phone before this screen
 * became unreachable. บันทึกเข้าพบ and the quick row do not wait on GET /api/sales/my-work: when it fails they stay,
 * beside the error and a retry.
 *
 * นัดถัดไป reads this week's visits plus `ahead` — GET /api/sales/visits from today to `aheadTo` (today + 7) — so a
 * Monday visit is "next" on a Sunday. `carriedOver` is MyWork's ค้างจากสัปดาห์ก่อน query (the 31 days before Monday).
 *
 * ส่งตัวอย่าง (slice 6d): built now that a sample sent is a deal activity of its own (SAMPLE_SENT, V223) — it opens
 * MyWork's SampleSentModal, one sheet: pick the งาน (the same groups โทรแล้ว's picker offers) · what was sent · ตามต่อ.
 * Offered exactly when โทรแล้ว is: both need one of the rep's open งาน.
 */
export function TodayOnPhone({
  work, failed, onRetry, todayIso, user, ahead = [], aheadTo, carriedOver = [], onLog, onCall, onSample, onPlan,
  onTick, onCancelVisit, onCreate, onShowFull, onResume, resuming = false,
}) {
  const followUp = work?.followUp ?? [];
  const board = work?.board ?? [];
  const canCall = followUp.length > 0 || board.length > 0;
  const canQuote = canCreateDealQuotationStandalone(user);
  const missed = missedVisits(work, carriedOver, todayIso);

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

      {failed ? (
        <div role="alert" className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-danger-border bg-surface p-3.5">
          <p className="m-0 text-sm font-bold text-danger">โหลดไม่สำเร็จ</p>
          <Button type="button" variant="secondary" onClick={onRetry}>
            <Icon name="refresh" size={16} />
            ลองใหม่
          </Button>
        </div>
      ) : !work ? (
        <div role="status" aria-busy="true" aria-label="กำลังโหลดงานวันนี้"><Skeleton height={88} /></div>
      ) : (
        <NextVisitCard
          next={nextVisit(upcoming(work, ahead), todayIso, aheadTo)}
          missedCount={missed.length}
          onPlan={onPlan}
        />
      )}

      {/* `log-visit-sticky`: the phone's one บันทึกเข้าพบ, the id the stacked page's sticky button had (e2e-real). */}
      <Button type="button" data-testid="log-visit-sticky" className="min-h-[56px] mobile:min-h-[56px] w-full text-base shadow-sm" onClick={onLog}>
        <Icon name="calendar" size={20} />
        บันทึกเข้าพบ
      </Button>

      {canCall || canQuote ? (
        <div data-testid="today-quick-actions" role="group" aria-label="ทำต่อ" className="grid auto-cols-fr grid-flow-col gap-2">
          {canCall ? (
            <Button type="button" variant="secondary" className={TILE} onClick={onCall}>
              <Icon name="phone" size={16} />
              โทรแล้ว
            </Button>
          ) : null}
          {canCall ? (
            <Button type="button" variant="secondary" className={TILE} onClick={onSample}>
              <Icon name="package" size={16} />
              ส่งตัวอย่าง
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

      <MissedVisits visits={missed} todayIso={todayIso} onTick={onTick} onCancel={onCancelVisit} />

      {work ? (
        <>
          <FollowUpCompact deals={followUp} quietDays={work.quietDays ?? 14} onResume={onResume} resuming={resuming} />
          {work.cash ? <CashCompact cash={work.cash} todayIso={todayIso} /> : null}
        </>
      ) : !failed ? (
        <div role="status" aria-busy="true" aria-label="กำลังโหลดงานที่ควรตามต่อ"><Skeleton height={120} /></div>
      ) : null}

      <nav aria-label="หน้าอื่น" className="grid grid-cols-2 gap-2">
        <Link to="/tickets" className={cn(buttonVariants({ variant: 'secondary' }), TILE, 'no-underline')}>ดูงานทั้งหมด</Link>
        <Link to="/visits" className={cn(buttonVariants({ variant: 'secondary' }), TILE, 'no-underline')}>นัดเข้าพบทั้งสัปดาห์</Link>
        <Button type="button" variant="secondary" className={TILE} onClick={onCreate}>
          <Icon name="plus" size={16} />
          งานใหม่
        </Button>
        <Button type="button" variant="secondary" className={TILE} onClick={onShowFull}>ดูหน้าเต็ม</Button>
      </nav>
    </section>
  );
}
