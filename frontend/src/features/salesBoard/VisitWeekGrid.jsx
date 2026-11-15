import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { Panel } from '../../components/common/Layout.jsx';
import { addDaysIso } from '../../utils/format.js';
import { VISIT_STATUS_LABEL, displayStatus, isoWeekdayIndex, weekDays, weekRangeLabel, weekStartIso } from './visitWeek.js';

// Each status is told apart by an icon, a border style and a text label — never colour alone.
const CHIP = {
  DONE: 'border-success-border bg-success-bg text-success-dark',
  PLANNED: 'border-dashed border-info-border bg-info-bg text-info-dark',
  CANCELLED: 'border-border bg-surface-subtle text-text-muted',
  OVERDUE: 'border-warning-border bg-warning-bg text-warning-dark',
};
const CHIP_ICON = { DONE: 'check', CANCELLED: 'close', OVERDUE: 'clock' };

function VisitChip({ visit, todayIso }) {
  const status = displayStatus(visit, todayIso);
  return (
    <span
      data-testid={`visit-chip-${visit.id}`}
      title={`${visit.contactName} · ${VISIT_STATUS_LABEL[status]}`}
      className={`inline-flex max-w-full items-center gap-1 rounded border px-1.5 py-0.5 text-2xs font-bold ${CHIP[status]}`}
    >
      {CHIP_ICON[status] ? <Icon name={CHIP_ICON[status]} size={10} /> : null}
      <span className={`min-w-0 truncate ${status === 'CANCELLED' ? 'line-through' : ''}`}>{visit.contactName}</span>
      <span className="sr-only">{` · ${VISIT_STATUS_LABEL[status]}`}</span>
    </span>
  );
}

function Legend() {
  return (
    <span className="flex flex-wrap gap-1.5" aria-hidden="true">
      {['DONE', 'PLANNED', 'OVERDUE', 'CANCELLED'].map((status) => (
        <span key={status} className={`rounded border px-1.5 py-px text-2xs font-bold ${CHIP[status]}`}>
          {VISIT_STATUS_LABEL[status]}
        </span>
      ))}
    </span>
  );
}

/** Rows: every rep in the team table, then any other visit owner, in table order. */
function gridRows(reps, visits) {
  const rows = reps.map((r) => ({ id: r.employeeId, name: r.name || `#${r.employeeId}` }));
  visits.forEach((v) => {
    if (!rows.some((r) => r.id === v.ownerId)) rows.push({ id: v.ownerId, name: v.ownerName || `#${v.ownerId}` });
  });
  return rows;
}

/**
 * ตารางเข้าพบ (spec 2026-11-15 §7.4 item 4): reps × Mon–Fri, done / planned / overdue / cancelled. This week comes
 * embedded in team-overview; ‹ › page other weeks through GET /api/sales/visits/team (review H1). Saturday and Sunday
 * columns appear only when someone has a weekend visit. Desktop is a grid; phone is one block per rep.
 */
export function VisitWeekGrid({ reps, visits: thisWeekVisits, todayIso }) {
  const [weekOffset, setWeekOffset] = useState(0);
  const monday = addDaysIso(weekStartIso(todayIso), 7 * weekOffset);
  const sunday = addDaysIso(monday, 6);
  const otherWeek = useQuery({
    queryKey: queryKeys.salesVisitsTeam(monday, sunday),
    queryFn: () => api.salesVisits.team({ from: monday, to: sunday }),
    enabled: weekOffset !== 0,
  });
  const visits = weekOffset === 0 ? thisWeekVisits : (otherWeek.data ?? []);
  const all = weekDays(monday, todayIso);
  const hasWeekend = visits.some((v) => isoWeekdayIndex(v.plannedDate) >= 5);
  const days = hasWeekend ? all : all.slice(0, 5);
  const rows = gridRows(reps, visits);
  const cellOf = (repId, iso) => visits.filter((v) => v.ownerId === repId && v.plannedDate === iso);

  return (
    <Panel
      flush
      data-testid="visit-week"
      title={(
        <span className="grid gap-0.5">
          <span>{weekOffset === 0 ? 'ตารางเข้าพบสัปดาห์นี้' : 'ตารางเข้าพบ'}</span>
          <small className="text-xs font-normal text-text-muted">{weekRangeLabel(monday)}</small>
        </span>
      )}
      actions={(
        <span className="flex flex-wrap items-center justify-end gap-2">
          <Legend />
          <span className="flex gap-1" role="group" aria-label="เลือกสัปดาห์">
            <Button type="button" size="sm" variant="secondary" aria-label="สัปดาห์ก่อน" title="สัปดาห์ก่อน" onClick={() => setWeekOffset(weekOffset - 1)}>‹</Button>
            <Button type="button" size="sm" variant={weekOffset === 0 ? 'primary' : 'secondary'} aria-pressed={weekOffset === 0} onClick={() => setWeekOffset(0)}>สัปดาห์นี้</Button>
            <Button type="button" size="sm" variant="secondary" aria-label="สัปดาห์หน้า" title="สัปดาห์หน้า" onClick={() => setWeekOffset(weekOffset + 1)}>›</Button>
          </span>
        </span>
      )}
    >
      {weekOffset !== 0 && otherWeek.isLoading ? null : otherWeek.isError && weekOffset !== 0 ? (
        <p className="m-0 px-5 py-4 text-sm font-bold text-danger mobile:px-3.5">โหลดไม่สำเร็จ</p>
      ) : visits.length === 0 ? (
        <p className="m-0 px-5 py-4 text-sm text-text-muted mobile:px-3.5">
          {weekOffset === 0 ? 'ยังไม่มีนัดเข้าพบสัปดาห์นี้' : 'ไม่มีนัดเข้าพบในสัปดาห์นี้'}
        </p>
      ) : (
        <>
          <table className="w-full table-fixed border-collapse text-sm mobile:hidden">
            <thead>
              <tr className="text-left text-xs text-text-muted">
                <th scope="col" className="w-32 px-5 py-2.5 font-bold"><span className="sr-only">พนักงานขาย</span></th>
                {days.map((d) => (
                  <th
                    key={d.iso}
                    scope="col"
                    className={`px-1.5 py-2.5 font-bold ${d.isToday ? 'text-primary' : ''}`}
                    aria-current={d.isToday ? 'date' : undefined}
                  >
                    {`${d.short} ${d.dayOfMonth}`}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.id} data-testid={`visit-row-${row.id}`} className="border-t border-border-subtle align-top">
                  <th scope="row" className="px-5 py-2 text-left font-bold text-text">
                    <span className="block truncate">{row.name}</span>
                  </th>
                  {days.map((d) => (
                    <td
                      key={d.iso}
                      data-testid={`visit-cell-${row.id}-${d.iso}`}
                      className={`px-1.5 py-2 ${d.isToday ? 'bg-primary-bg/40' : ''}`}
                    >
                      <span className="grid gap-1">
                        {cellOf(row.id, d.iso).map((v) => <VisitChip key={v.id} visit={v} todayIso={todayIso} />)}
                      </span>
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>

          <ul className="m-0 hidden list-none p-0 mobile:block" aria-label="ตารางเข้าพบสัปดาห์นี้ รายคน">
            {rows.map((row) => {
              const own = visits.filter((v) => v.ownerId === row.id);
              return (
                <li key={row.id} className="grid gap-1.5 border-t border-border-subtle px-3.5 py-3 first:border-t-0">
                  <b className="text-sm text-text">{row.name}</b>
                  {own.length === 0 ? <span className="text-xs text-text-muted">ไม่มีนัด</span> : (
                    <span className="grid gap-1">
                      {days.filter((d) => own.some((v) => v.plannedDate === d.iso)).map((d) => (
                        <span key={d.iso} className="flex min-w-0 items-start gap-2">
                          <span className={`w-12 shrink-0 text-xs font-bold ${d.isToday ? 'text-primary' : 'text-text-muted'}`}>
                            {`${d.short} ${d.dayOfMonth}`}
                          </span>
                          <span className="flex min-w-0 flex-wrap gap-1">
                            {own.filter((v) => v.plannedDate === d.iso).map((v) => (
                              <VisitChip key={v.id} visit={{ ...v, id: `m-${v.id}` }} todayIso={todayIso} />
                            ))}
                          </span>
                        </span>
                      ))}
                    </span>
                  )}
                </li>
              );
            })}
          </ul>
        </>
      )}
    </Panel>
  );
}

/** The per-rep dots column: one dot per booked visit, filled when done; the label carries the counts. */
export function VisitDots({ repId, done, planned }) {
  const label = planned === 0 ? 'ไม่มีนัดสัปดาห์นี้' : `เข้าพบแล้ว ${done} จาก ${planned} นัด`;
  return (
    <span data-testid={`rep-visits-${repId}`} role="img" aria-label={label} title={label} className="inline-flex flex-wrap items-center gap-1">
      {planned === 0 ? <span aria-hidden="true" className="text-xs text-text-muted">—</span> : null}
      {Array.from({ length: planned }, (_, i) => (
        <i
          key={i}
          data-dot={i < done ? 'done' : 'planned'}
          aria-hidden="true"
          className={`block size-2.5 rounded-full border ${i < done ? 'border-success bg-success' : 'border-text-muted bg-transparent'}`}
        />
      ))}
    </span>
  );
}
