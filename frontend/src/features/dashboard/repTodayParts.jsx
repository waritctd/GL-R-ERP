import { Link } from 'react-router-dom';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { bangkokNowHm, timeLabel } from '../salesBoard/visitWeek.js';
import { TODAY_GROUPS } from './repToday.js';

const NAME = 'min-w-0 truncate text-sm font-bold text-text no-underline';

const Failed = () => <p className="m-0 text-sm font-bold text-danger">โหลดไม่สำเร็จ</p>;
const Empty = ({ children }) => <p className="m-0 flex items-center gap-2 text-sm font-bold text-text-muted"><Icon name="check" size={18} />{children}</p>;

/** One measure of the report's completeness: its label, `done จาก total`, and a thin bar. */
function Measure({ label, done, total, unit }) {
  return (
    <div className="grid gap-1">
      <div className="flex items-baseline justify-between gap-3 text-sm">
        <span className="text-text-muted">{label}</span>
        <b className="tabular-nums">{done} จาก {total} {unit}</b>
      </div>
    </div>
  );
}

/**
 * รายงานสัปดาห์ของคุณ (board TD1): how much of the report's data is complete — payment dates of ordered jobs and visit
 * results due — a segmented bar of both, and what the rep entered this week. `rep` is the weekly report's row for the rep.
 */
export function ReportBlock({ rep, nextMonday, team = false }) {
  const total = rep.ordersOpen + rep.visitsDue;
  const done = rep.ordersWithDate + rep.visitsDueWithResult;
  const segments = [
    { label: 'กำหนดรับเงินของงานที่สั่งซื้อ', done: rep.ordersWithDate, total: rep.ordersOpen, unit: 'งาน' },
    { label: 'ผลการเข้าพบที่ถึงกำหนด', done: rep.visitsDueWithResult, total: rep.visitsDue, unit: 'นัด' },
  ];
  const counts = [['เลื่อนขั้น', rep.stageMoves, 'งาน'], ['ใบเสนอราคา', rep.quotationsIssued, 'ใบ'], ['เข้าพบ', rep.visitsDone, 'นัด'], ['ยืนยันคำสั่งซื้อ', rep.ordersConfirmed, 'งาน']];
  return (
    <div className="grid grid-cols-[minmax(0,1.3fr)_minmax(0,1fr)] gap-6 mobile:grid-cols-1 mobile:gap-4">
      <div className="grid content-start gap-3">
        <p className="m-0 text-sm font-bold">
          ข้อมูลที่ใช้จัดทำรายงาน <span className="tabular-nums text-text-muted">{done} จาก {total} รายการ ครบถ้วน</span>
        </p>
        {total > 0 ? (
          <div className="flex gap-1" role="img" aria-label={`ข้อมูลรายงานครบถ้วน ${done} จาก ${total} รายการ`}>
            {segments.filter((s) => s.total > 0).map((s) => (
              <span key={s.label} style={{ flexGrow: s.total }} className="h-2.5 min-w-0 basis-0 overflow-hidden rounded-full bg-surface-subtle">
                <i className="block h-full bg-accent" style={{ width: `${Math.round((s.done / s.total) * 100)}%` }} />
              </span>
            ))}
          </div>
        ) : null}
        {segments.map((s) => <Measure key={s.label} {...s} />)}
        {total > done && !team ? (
          <p className="m-0 text-xs text-text-muted">อีก {total - done} รายการอัปเดตได้จากรายการด้านล่าง</p>
        ) : null}
      </div>
      <div className="grid content-start gap-3">
        <p className="m-0 text-sm font-bold">{team ? 'สัปดาห์นี้ทีมบันทึกแล้ว' : 'สัปดาห์นี้คุณบันทึกแล้ว'}</p>
        <dl className="m-0 grid grid-cols-2 gap-x-4 gap-y-2">
          {counts.map(([label, n, unit]) => (
            <div key={label} className="grid gap-0.5">
              <dt className="text-xs text-text-muted">{label}</dt>
              <dd className="m-0 text-sm font-bold tabular-nums">{n} {unit}</dd>
            </div>
          ))}
        </dl>
        <p className="m-0 text-xs text-text-muted">ระบบจะสรุปรายงานสัปดาห์นี้{team ? '' : 'ให้แก่ผู้จัดการฝ่ายขาย'} {nextMonday} 06:00 น.</p>
      </div>
    </div>
  );
}

/** One worklist row: project link, code · reason, amount and the button that opens the action. */
export function Row({ row, busy, onAct }) {
  const name = row.to ? <Link to={row.to} className={`${NAME} hover:underline`}>{row.name}</Link> : <span className={NAME}>{row.name}</span>;
  return (
    <li data-testid={`today-row-${row.key}`} className="flex items-center gap-3 py-3 mobile:flex-wrap">
      <span className="grid min-w-0 flex-1 gap-0.5 mobile:basis-full">
        {name}
        <small className="text-xs text-text-muted">{[row.code, row.reason].filter(Boolean).join(' · ')}</small>
      </span>
      {row.amount ? <b className="shrink-0 text-sm tabular-nums">{row.amount}</b> : null}
      <Button type="button" variant="secondary" size="sm" className="shrink-0 mobile:min-h-11 mobile:flex-1" disabled={busy} onClick={() => onAct(row.act)}>
        {row.button}
      </Button>
    </li>
  );
}

/** รายการที่ต้องอัปเดต: the four groups, empty ones left out. */
export function TodayWorklist({ rows, busy, onAct }) {
  if (!rows.length) return <Empty>ไม่มีรายการที่ต้องอัปเดตตอนนี้</Empty>;
  return (
    <div className="grid gap-4">
      {TODAY_GROUPS.map((g) => {
        const list = rows.filter((r) => r.group === g.key);
        return list.length ? (
          <section key={g.key} data-testid={`today-group-${g.key}`} aria-label={g.label}>
            <h3 className="m-0 flex flex-wrap items-baseline gap-x-2 border-b border-border-subtle pb-1.5 text-sm font-extrabold">
              {g.label}<span className="text-xs font-normal tabular-nums text-text-muted">{list.length} รายการ</span>
            </h3>
            <ul className="m-0 grid list-none divide-y divide-border-subtle p-0">
              {list.map((row) => <Row key={row.key} row={row} busy={busy} onAct={onAct} />)}
            </ul>
          </section>
        ) : null;
      })}
    </div>
  );
}

/** นัดหมายวันนี้: today's visits by time, done ones muted; the next one can be ticked or moved. */
export function TodayVisits({ visits, tomorrowCount, onDone, onMove }) {
  const done = visits.filter((v) => v.status === 'DONE').length;
  const next = visits.find((v) => v.status === 'PLANNED');
  return (
    <Panel title="นัดหมายวันนี้" actions={<span className="text-xs text-text-muted">เข้าพบแล้ว {done} จาก {visits.length}</span>}>
      {visits.length === 0 ? <p className="m-0 text-sm text-text-muted">วันนี้ไม่มีนัดหมาย</p> : (
        <ul className="m-0 grid list-none divide-y divide-border-subtle p-0">
          {visits.map((v) => {
            const muted = v.status === 'DONE';
            return (
              <li key={v.id} data-testid={`today-visit-${v.id}`} className={`grid gap-1.5 py-2.5 ${muted ? 'opacity-70' : ''}`}>
                <span className="flex items-baseline gap-2.5">
                  <b className="w-12 shrink-0 text-sm tabular-nums">{timeLabel(v.plannedTime) || '–'}</b>
                  <span className="grid min-w-0 flex-1 gap-0.5">
                    <span className="flex flex-wrap items-center gap-1.5 text-sm font-bold">
                      {v.contactName}
                      {v === next ? <StatusBadge tone="teal">ถัดไป</StatusBadge> : null}
                    </span>
                    <small className="text-xs text-text-muted">
                      {[v.ticketTitle, muted ? 'เข้าพบแล้ว' : v.note].filter(Boolean).join(' · ')}
                    </small>
                  </span>
                </span>
                {v === next ? (
                  <span className="flex gap-2 pl-[3.625rem] mobile:pl-0">
                    <Button type="button" size="sm" className="mobile:min-h-11 mobile:flex-1" onClick={() => onDone(v)}>เข้าพบแล้ว</Button>
                    <Button type="button" variant="secondary" size="sm" className="mobile:min-h-11 mobile:flex-1" onClick={() => onMove(v)}>เลื่อนนัด</Button>
                  </span>
                ) : null}
              </li>
            );
          })}
        </ul>
      )}
      <div className="mt-2 flex flex-wrap items-center justify-between gap-2 text-xs text-text-muted">
        <span>{tomorrowCount > 0 ? `พรุ่งนี้ ${tomorrowCount} นัด` : ''}</span>
        <Link to="/visits" className="inline-flex min-h-11 items-center font-bold text-link no-underline">ดูนัดทั้งสัปดาห์</Link>
      </div>
    </Panel>
  );
}

/** บันทึกแล้ววันนี้: the weekly report's `today` entries, each already counted in the report. */
export function SavedToday({ entries, failedLoad }) {
  return (
    <Panel title="บันทึกแล้ววันนี้" actions={entries ? <span className="text-xs text-text-muted">นับในรายงานสัปดาห์แล้ว {entries.length} รายการ</span> : null}>
      {failedLoad ? <Failed /> : !entries ? null : entries.length === 0 ? <p className="m-0 text-sm text-text-muted">วันนี้ยังไม่มีรายการที่บันทึก</p> : (
        <ul className="m-0 grid list-none divide-y divide-border-subtle p-0">
          {entries.map((e) => (
            <li key={`${e.at}-${e.kind}-${e.ticketId}`} className="flex items-baseline gap-2.5 py-2 text-sm">
              <b className="w-12 shrink-0 tabular-nums">{bangkokNowHm(new Date(e.at))}</b>
              <span className="grid min-w-0 gap-0.5">
                <span>{e.label}</span>
                {e.ticketTitle ? <small className="text-xs text-text-muted">{e.ticketTitle}</small> : null}
              </span>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}

/** รอฝ่ายอื่นดำเนินการ: nothing for the rep to click, just whose move it is and for how long. */
export function WaitingOthers({ rows }) {
  return (
    <Panel title="รอฝ่ายอื่นดำเนินการ" actions={<span className="text-xs text-text-muted">{rows.length} รายการ</span>}>
      {rows.length === 0 ? <p className="m-0 text-sm text-text-muted">ไม่มีรายการที่รอฝ่ายอื่น</p> : (
        <ul className="m-0 grid list-none divide-y divide-border-subtle p-0">
          {rows.map((r) => (
            <li key={r.key} className="grid gap-0.5 py-2.5">
              <span className="flex items-baseline justify-between gap-3 text-sm">
                <b>{r.who}</b>
                {r.days != null ? <small className="shrink-0 text-xs text-text-muted">{r.days > 0 ? `รอ ${r.days} วัน` : 'รอตั้งแต่วันนี้'}</small> : null}
              </span>
              <Link to={r.to} className="truncate text-xs text-text-muted no-underline hover:underline">{[r.name, r.code].filter(Boolean).join(' · ')}</Link>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}
