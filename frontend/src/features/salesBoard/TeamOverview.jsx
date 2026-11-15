import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Panel } from '../../components/common/Layout.jsx';
import { Skeleton } from '../../components/common/Skeleton.jsx';
import { bangkokTodayIso } from '../../utils/format.js';
import { baht, columnLabel, money, otherCurrencyTotals, sinceLabel, sumValue, totalLabel } from './boardFormat.js';
import { monthLabel } from './cashFormat.js';
import { VisitDots, VisitWeekGrid } from './VisitWeekGrid.jsx';

// A manager's or the CEO's own deals stay in the table, labelled (review LOW); a rep's row carries no label.
const ROLE_LABEL = { sales_manager: 'ผจก.ขาย', ceo: 'CEO' };

/** Adds up each rep's per-currency map: { USD: 1000, … }. */
function mergeOther(reps) {
  const totals = {};
  reps.forEach((r) => Object.entries(r.otherCurrencyValues ?? {}).forEach(([code, v]) => {
    totals[code] = (totals[code] ?? 0) + Number(v);
  }));
  return totals;
}

const ROW = 'px-5 py-3 mobile:px-3.5';

/** A zero in a per-rep money column is "nothing yet", not a figure: a dash, never ฿0. */
const cashCell = (value) => (Number(value ?? 0) === 0 ? '—' : baht(value));

function Tile({ testId, label, value, sub, warn }) {
  return (
    <div data-testid={testId} className="grid gap-1 rounded-md border border-border bg-surface p-4 mobile:p-3.5">
      <small className="text-xs font-bold text-text-muted">{label}</small>
      <strong className={`text-2xl font-extrabold tabular-nums ${warn ? 'text-warning' : 'text-text'}`}>{value}</strong>
      <span className="text-xs text-text-muted">{sub}</span>
    </div>
  );
}

function QuietChip({ count }) {
  return count > 0
    ? <span className="whitespace-nowrap rounded-full bg-warning-bg px-2 py-px text-2xs font-bold text-warning-dark">{count} ดีล</span>
    : <span className="whitespace-nowrap rounded-full bg-surface-subtle px-2 py-px text-2xs font-bold text-text-muted">ไม่มี</span>;
}

/**
 * ภาพรวมทีมขาย (spec 2026-11-15 §7.4): tiles (held, quiet, visits this week), one row per rep with the week's visit
 * dots, the team's quiet deals ranked by value × days quiet, and the week visit grid. All numbers are served by
 * GET /api/sales/team-overview (sales_manager / ceo). The cash tile (slice 3) is received — Express and manual receipts
 * alike — against the team's expected payments for the Bangkok month; each row carries that rep's two figures. A
 * rep's name opens the deal list's existing ?owner= filter.
 */
export function TeamOverview({ showTitle = true }) {
  const query = useQuery({ queryKey: queryKeys.salesBoardTeam(), queryFn: () => api.salesBoard.teamOverview() });
  const team = query.data;

  const openDeals = team?.reps.reduce((sum, r) => sum + r.openDeals, 0) ?? 0;
  const openValue = team?.reps.reduce((sum, r) => sum + Number(r.openValue ?? 0), 0) ?? 0;
  const quietDeals = team?.reps.reduce((sum, r) => sum + r.quietDeals, 0) ?? 0;
  const quietDays = team?.quietDays ?? 14;
  const visitsDone = team?.reps.reduce((sum, r) => sum + (r.visitsDone ?? 0), 0) ?? 0;
  const visitsPlanned = team?.reps.reduce((sum, r) => sum + (r.visitsPlanned ?? 0), 0) ?? 0;
  const cash = team?.cash ?? null;
  const month = monthLabel(cash?.month);

  return (
    <section aria-label="ภาพรวมทีมขาย" className="grid gap-[18px]">
      {showTitle ? <h2 className="m-0 text-xl font-extrabold text-text">ภาพรวมทีมขาย</h2> : null}

      {query.isError ? (
        <p className="m-0 text-sm font-bold text-danger">โหลดไม่สำเร็จ</p>
      ) : !team ? (
        <div className="grid gap-2" role="status" aria-busy="true" aria-label="กำลังโหลดภาพรวมทีมขาย">
          <Skeleton height={72} />
          <Skeleton height={160} />
        </div>
      ) : (
        <>
          <div className={`grid gap-3 mobile:grid-cols-2 ${cash ? 'grid-cols-4' : 'grid-cols-3'}`}>
            {cash ? (
              <Tile
                testId="tile-cash"
                label={`เงินเข้าแล้ว ${month}`}
                value={baht(cash.received)}
                sub={`จากที่ทีมคาด ${baht(cash.forecast)} · มาจาก Express`}
              />
            ) : null}
            <Tile
              testId="tile-held"
              label="ดีลที่ถืออยู่"
              value={`${openDeals} ดีล`}
              sub={`มูลค่า ${totalLabel(openValue, mergeOther(team.reps))}`}
            />
            <Tile
              testId="tile-quiet"
              label={`ดีลเงียบเกิน ${quietDays} วัน`}
              value={`${quietDeals} ดีล`}
              sub={quietDeals > 0 ? 'รายชื่อด้านล่าง' : 'ทุกดีลมีความเคลื่อนไหว'}
              warn={quietDeals > 0}
            />
            <Tile
              testId="tile-visits"
              label="เข้าพบสัปดาห์นี้"
              value={`${visitsDone} / ${visitsPlanned}`}
              sub="ไปแล้ว / นัดไว้"
            />
          </div>

          <Panel flush title="รายคน">
            {team.reps.length === 0 ? (
              <p className={`m-0 text-sm text-text-muted ${ROW}`}>ยังไม่มีดีลที่เปิดอยู่</p>
            ) : (
              <table className="w-full border-collapse text-sm">
                <thead>
                  <tr className="text-left text-xs text-text-muted">
                    <th scope="col" className="px-5 py-2.5 font-bold mobile:px-3.5">พนักงานขาย</th>
                    <th scope="col" className="px-3 py-2.5 text-right font-bold mobile:px-1.5">ดีล</th>
                    <th scope="col" className="px-3 py-2.5 text-right font-bold mobile:px-1.5">มูลค่า</th>
                    <th scope="col" className="whitespace-nowrap px-3 py-2.5 font-bold mobile:px-1.5">ดีลเงียบ</th>
                    <th scope="col" className="whitespace-nowrap px-3 py-2.5 text-right font-bold mobile:hidden">
                      {month ? `คาดรับ ${month}` : 'คาดรับเดือนนี้'}
                    </th>
                    <th scope="col" className="whitespace-nowrap px-3 py-2.5 text-right font-bold mobile:hidden">รับแล้ว</th>
                    <th scope="col" className="whitespace-nowrap px-3 py-2.5 font-bold mobile:hidden">เข้าพบสัปดาห์นี้</th>
                    <th scope="col" className="whitespace-nowrap px-5 py-2.5 font-bold mobile:px-3.5">อัปเดตล่าสุด</th>
                  </tr>
                </thead>
                <tbody>
                  {team.reps.map((rep) => (
                    <tr key={rep.employeeId} className="border-t border-border-subtle">
                      <td className="px-5 py-3 mobile:px-3.5">
                        <Link
                          to={`/tickets?owner=${rep.employeeId}`}
                          className="font-bold text-text no-underline hover:underline focus-visible:underline"
                        >
                          {rep.name || `#${rep.employeeId}`}
                        </Link>
                        {ROLE_LABEL[rep.role] ? (
                          <span className="ml-1.5 whitespace-nowrap rounded-full bg-surface-subtle px-1.5 py-px text-2xs font-bold text-text-muted">
                            {ROLE_LABEL[rep.role]}
                          </span>
                        ) : null}
                      </td>
                      <td className="px-3 py-3 text-right tabular-nums mobile:px-1.5">{rep.openDeals}</td>
                      <td className="px-3 py-3 text-right font-bold tabular-nums whitespace-nowrap mobile:px-1.5">{totalLabel(rep.openValue, rep.otherCurrencyValues)}</td>
                      <td className="px-3 py-3 mobile:px-1.5"><QuietChip count={rep.quietDeals} /></td>
                      <td data-testid="rep-expected" className="px-3 py-3 text-right tabular-nums whitespace-nowrap mobile:hidden">
                        {cashCell(rep.expected)}
                      </td>
                      <td data-testid="rep-received" className="px-3 py-3 text-right font-bold tabular-nums whitespace-nowrap mobile:hidden">
                        {cashCell(rep.received)}
                      </td>
                      <td className="px-3 py-3 mobile:hidden">
                        <VisitDots repId={rep.employeeId} done={rep.visitsDone ?? 0} planned={rep.visitsPlanned ?? 0} />
                      </td>
                      <td className="px-5 py-3 whitespace-nowrap text-text-muted mobile:px-3.5">{sinceLabel(rep.lastUpdateAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Panel>

          <Panel
            flush
            title={(
              <span className="grid gap-0.5">
                <span>ดีลที่ควรโทรตาม</span>
                <small className="text-xs font-normal text-text-muted">เรียงตามมูลค่า × วันที่เงียบ</small>
              </span>
            )}
            actions={(
              <span className="text-sm tabular-nums text-text-muted">
                {totalLabel(sumValue(team.stale), otherCurrencyTotals(team.stale))}
              </span>
            )}
          >
            {team.stale.length === 0 ? (
              <p className={`m-0 text-sm font-bold text-text-muted ${ROW}`}>
                ไม่มีดีลเงียบ — ทุกดีลมีความเคลื่อนไหวใน {quietDays} วันที่ผ่านมา
              </p>
            ) : (
              <ul data-testid="team-stale" className="m-0 list-none p-0">
                {team.stale.map((deal) => (
                  <li key={deal.ticketId} className={`flex items-start justify-between gap-3 border-t border-border-subtle first:border-t-0 ${ROW}`}>
                    <div className="grid min-w-0 gap-0.5">
                      <Link
                        to={`/tickets/${deal.ticketId}`}
                        className="min-w-0 break-words text-sm font-bold text-text no-underline hover:underline focus-visible:underline"
                      >
                        {deal.title || deal.customerName}
                      </Link>
                      <span className="min-w-0 break-words text-xs text-text-muted">
                        {[deal.ownerName, columnLabel(deal.boardColumn)].filter(Boolean).join(' · ')}
                      </span>
                    </div>
                    <span className="grid shrink-0 justify-items-end gap-1">
                      <span className="text-sm font-bold tabular-nums text-text">{money(deal.value, deal.currency)}</span>
                      <span className="rounded-full bg-warning-bg px-2 py-px text-2xs font-bold text-warning-dark">
                        เงียบ {deal.daysQuiet} วัน
                      </span>
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </Panel>

          <VisitWeekGrid reps={team.reps} visits={team.teamVisitsThisWeek ?? []} todayIso={bangkokTodayIso()} />
        </>
      )}
    </section>
  );
}
