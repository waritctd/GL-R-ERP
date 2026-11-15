import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { shortWeekday, timeLabel, visitCounts } from './visitWeek.js';

function DayBadge({ iso, isToday }) {
  return (
    <span
      className={`grid w-10 shrink-0 justify-items-center rounded-md py-1 leading-tight ${isToday
        ? 'bg-primary-bg text-primary' : 'bg-surface-subtle text-text-secondary'}`}
    >
      <small className="text-2xs font-bold">{shortWeekday(iso)}</small>
      <b className="text-sm tabular-nums">{Number(iso.slice(8, 10))}</b>
    </span>
  );
}

function VisitState({ visit, onDone, onCancel }) {
  if (visit.status === 'DONE') {
    return (
      <span className="inline-flex shrink-0 items-center gap-1 rounded-full bg-success-bg px-2 py-0.5 text-2xs font-bold text-success">
        <Icon name="check" size={12} />
        ไปแล้ว
      </span>
    );
  }
  if (visit.status === 'CANCELLED') {
    return <span className="shrink-0 rounded-full bg-surface-subtle px-2 py-0.5 text-2xs font-bold text-text-muted">ยกเลิก</span>;
  }
  return (
    <span className="flex shrink-0 items-center gap-1">
      <Button
        type="button"
        size="sm"
        variant="secondary"
        aria-label={`ยกเลิกนัด: ${visit.contactName}`}
        title="ยกเลิกนัด"
        onClick={() => onCancel(visit)}
      >
        <Icon name="close" size={14} />
      </Button>
      <Button
        type="button"
        size="sm"
        variant="secondary"
        aria-label={`เข้าพบแล้ว: ${visit.contactName}`}
        title="เข้าพบแล้ว"
        onClick={() => onDone(visit)}
      >
        <Icon name="check" size={14} />
        <span className="mobile-xs:hidden">เข้าพบแล้ว</span>
      </Button>
    </span>
  );
}

/**
 * นัดเข้าพบสัปดาห์นี้ (spec 2026-11-15 §7.3 item 2) — the rep's visits this ISO week as served (VisitRepository's
 * date, time, id order). Tick a planned one to mark it done; + นัดเข้าพบ plans one, with or without a deal.
 */
export function VisitsThisWeek({ visits, todayIso, loading, failed, onPlan, onDone, onCancel }) {
  const { done, planned } = visitCounts(visits);
  return (
    <Panel
      flush
      data-testid="visits-this-week"
      title={(
        <span className="grid gap-0.5">
          <span>นัดเข้าพบสัปดาห์นี้</span>
          {visits ? <small className="text-xs font-normal text-text-muted">{`${done} จาก ${planned} ไปแล้ว`}</small> : null}
        </span>
      )}
      actions={(
        <Button type="button" size="sm" variant="secondary" onClick={onPlan}>
          <Icon name="plus" size={14} />
          นัดเข้าพบ
        </Button>
      )}
    >
      {failed ? <p className="m-0 px-5 py-4 text-sm font-bold text-danger mobile:px-3.5">โหลดไม่สำเร็จ</p>
        : loading ? null
          : visits.length === 0 ? (
            <p className="m-0 px-5 py-4 text-sm text-text-muted mobile:px-3.5">ยังไม่มีนัดเข้าพบสัปดาห์นี้</p>
          ) : (
            <ul className="m-0 list-none p-0">
              {visits.map((visit) => (
                <li
                  key={visit.id}
                  data-testid={`visit-${visit.id}`}
                  className="flex items-center gap-3 border-t border-border-subtle px-5 py-3 first:border-t-0 mobile:px-3.5"
                >
                  <DayBadge iso={visit.plannedDate} isToday={visit.plannedDate === todayIso} />
                  <span className="grid min-w-0 flex-1 gap-0.5">
                    <b className={`min-w-0 break-words text-sm ${visit.status === 'CANCELLED'
                      ? 'text-text-muted line-through' : 'text-text'}`}
                    >
                      {visit.contactName}
                    </b>
                    {timeLabel(visit.plannedTime) || visit.ticketTitle || visit.note ? (
                      <small className="min-w-0 break-words text-xs text-text-muted">
                        {[timeLabel(visit.plannedTime), visit.ticketTitle, visit.note].filter(Boolean).join(' · ')}
                      </small>
                    ) : null}
                  </span>
                  <VisitState visit={visit} onDone={onDone} onCancel={onCancel} />
                </li>
              ))}
            </ul>
          )}
    </Panel>
  );
}
