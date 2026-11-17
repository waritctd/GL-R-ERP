import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { formatThaiDate } from '../../utils/format.js';
import { activityKindLabel } from '../tickets/dealTrackingMeta.js';
import { columnLabel, money } from './boardFormat.js';
// The m-call dialog lives in its own file so the deal page's header opens the same one.
import { CallModal } from './CallModal.jsx';

/**
 * ควรตามต่อ — the rep's quiet deals as served (SalesBoardService ranks them by days × value, max 5). One tap each:
 * โทรแล้ว logs a CALL activity (which clears the quiet clock), นัดเข้าพบ opens the plan-visit dialog on this deal (the
 * visit clears the clock once it is ticked done), ปิดงานนี้ hands off to the ปิดงาน · เสียงาน dialog.
 * When the rep has more quiet deals than are listed ({@code quietTotal}), ดูทั้งหมด (n) calls {@code onShowAll}, which
 * opens the board filtered to quiet deals.
 *
 * A row with {@code returning} is a PAUSED deal whose วันที่จะกลับมาดู has arrived (owner ruling 2026-11-16,
 * QuietRule#returningSql). It says so instead of "เงียบ n วัน", and offers ดำเนินการต่อ ({@code onResume}) — the way
 * out of the list besides re-pausing it with a later date on the deal page. Mark-lost and visits need an ACTIVE deal on
 * the server, so those two buttons are not offered on it.
 */
/** A returning paused deal's chip: the same words on the desktop list and the phone's compact one (TodayOnPhone). */
export const returningLabel = (deal) => `พักไว้ · ถึงวันกลับมาดู ${formatThaiDate(deal.pauseReturnOn)}`;

export function FollowUpList({
  deals, quietDays, quietTotal = 0, onShowAll, onLogCall, onBookVisit, onCloseDeal, onResume, loggingCall, resuming,
}) {
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
                {deal.returning ? (
                  <span className="rounded-full bg-info-bg px-2 py-px text-2xs font-bold text-info-dark">
                    {returningLabel(deal)}
                  </span>
                ) : (
                  <span className="rounded-full bg-warning-bg px-2 py-px text-2xs font-bold text-warning-dark">
                    เงียบ {deal.daysQuiet} วัน
                  </span>
                )}
              </span>
            </div>
            {deal.returning ? (
              <div className="flex flex-wrap gap-2">
                <Button type="button" size="sm" variant="secondary" disabled={resuming} onClick={() => onResume?.(deal)}>
                  ดำเนินการต่อ
                </Button>
              </div>
            ) : (
              <div className="flex flex-wrap gap-2">
                <Button type="button" size="sm" variant="secondary" onClick={() => setCalling(deal)}>
                  <Icon name="phone" size={14} />
                  โทรแล้ว
                </Button>
                <Button type="button" size="sm" variant="secondary" onClick={() => onBookVisit(deal)}>
                  <Icon name="calendar" size={14} />
                  นัดเข้าพบ
                </Button>
                <Button type="button" size="sm" variant="secondary" onClick={() => onCloseDeal(deal)}>ปิดงานนี้</Button>
              </div>
            )}
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
