import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { isOverdue, shortWeekday, timeLabel } from './visitWeek.js';

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

function VisitState({ visit, todayIso, onDone, onCancel }) {
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
  // Ticking is for a visit that has happened: never ahead of its day (review M2; VisitService refuses it too).
  const canTick = visit.plannedDate <= todayIso;
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
      {canTick ? (
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
      ) : null}
    </span>
  );
}

/** One visit row: day badge, who, time · deal · note, and its state. Overdue = PLANNED and its day has passed. */
export function VisitRow({ visit, todayIso, onDone, onCancel }) {
  const overdue = isOverdue(visit, todayIso);
  return (
    <li
      data-testid={`visit-${visit.id}`}
      className={`flex items-center gap-3 border-t border-border-subtle px-5 py-3 first:border-t-0 mobile:px-3.5 ${overdue
        ? 'bg-warning-bg/40' : ''}`}
    >
      <DayBadge iso={visit.plannedDate} isToday={visit.plannedDate === todayIso} />
      <span className="grid min-w-0 flex-1 gap-0.5">
        <span className="flex min-w-0 flex-wrap items-center gap-1.5">
          <b className={`min-w-0 break-words text-sm ${visit.status === 'CANCELLED'
            ? 'text-text-muted line-through' : 'text-text'}`}
          >
            {visit.contactName}
          </b>
          {overdue ? (
            <span className="inline-flex items-center gap-1 rounded-full border border-warning-border bg-warning-bg px-1.5 py-px text-2xs font-bold text-warning-dark">
              <Icon name="clock" size={10} />
              เลยกำหนด
            </span>
          ) : null}
        </span>
        {timeLabel(visit.plannedTime) || visit.ticketTitle || visit.note ? (
          <small className="min-w-0 break-words text-xs text-text-muted">
            {[timeLabel(visit.plannedTime), visit.ticketTitle, visit.note].filter(Boolean).join(' · ')}
          </small>
        ) : null}
      </span>
      <VisitState visit={visit} todayIso={todayIso} onDone={onDone} onCancel={onCancel} />
    </li>
  );
}

/** ‹ · สัปดาห์นี้ · › — shared by this card and the นัดเข้าพบ page. */
export function WeekNav({ weekOffset, onWeek }) {
  return (
    <span className="flex gap-1" role="group" aria-label="เลือกสัปดาห์">
      <Button type="button" size="sm" variant="secondary" aria-label="สัปดาห์ก่อน" title="สัปดาห์ก่อน" onClick={() => onWeek(weekOffset - 1)}>
        ‹
      </Button>
      <Button type="button" size="sm" variant={weekOffset === 0 ? 'primary' : 'secondary'} aria-pressed={weekOffset === 0} onClick={() => onWeek(0)}>
        สัปดาห์นี้
      </Button>
      <Button type="button" size="sm" variant="secondary" aria-label="สัปดาห์หน้า" title="สัปดาห์หน้า" onClick={() => onWeek(weekOffset + 1)}>
        ›
      </Button>
    </span>
  );
}

/** ค้างจากสัปดาห์ก่อน — the rep's PLANNED visits dated before this week, still to tick or cancel (review H1). */
export function CarriedOverVisits({ visits, todayIso, onDone, onCancel }) {
  if (!visits?.length) return null;
  return (
    <Panel
      flush
      data-testid="visits-carried-over"
      title={(
        <span className="grid gap-0.5">
          <span>{`ค้างจากสัปดาห์ก่อน (${visits.length})`}</span>
          <small className="text-xs font-normal text-text-muted">นัดที่ยังไม่ได้ติ๊ก — ติ๊กว่าไปแล้ว หรือยกเลิก</small>
        </span>
      )}
    >
      <ul className="m-0 list-none p-0">
        {visits.map((visit) => (
          <VisitRow key={visit.id} visit={visit} todayIso={todayIso} onDone={onDone} onCancel={onCancel} />
        ))}
      </ul>
    </Panel>
  );
}
