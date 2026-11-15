import { useId } from 'react';
import { baht } from './boardFormat.js';
import { monthLabel, weekEndLabel } from './cashFormat.js';

/**
 * The three expected buckets, in the fixed categorical order of the validated palette (spec §10: #4f46e5, #0284c7,
 * #14b8a6 — validate_palette.js: all checks pass in light mode; #14b8a6 is under 3:1 against the surface, so the
 * numbers are always also in the direct labels and the table below). Colour marks identity only: every number and
 * label is in text ink.
 */
export const CASH_SERIES = [
  { key: 'deliveredAwaiting', label: 'ส่งของแล้ว รอเก็บ', color: '#4f46e5' },
  { key: 'deliveriesScheduled', label: 'นัดส่งของ', color: '#0284c7' },
  { key: 'depositsExpected', label: 'มัดจำที่คาด', color: '#14b8a6' },
];

const num = (v) => Number(v ?? 0) || 0;
const weekTotal = (w) => CASH_SERIES.reduce((sum, s) => sum + num(w[s.key]), 0);
const pct = (part, whole) => `${Math.round((part / whole) * 1000) / 10}%`;

/**
 * เงินที่จะเข้า {เดือน} (spec 2026-11-15 §7.3 item 3, design a-mywork card C): received so far against the month's
 * forecast, then one stacked bar per week of what is still expected — delivered awaiting payment, deliveries
 * scheduled, deposits expected — with the week's total as a direct label, a legend, and the same numbers as a table.
 * `cash` is SalesBoardService#cashForMonth's CashMonth; nothing is computed here but bar widths.
 */
export function CashCard({ cash, title }) {
  const ids = useId();
  const month = monthLabel(cash?.month);
  const heading = title ?? `เงินที่จะเข้า ${month}`;
  const weeks = cash?.weeks ?? [];
  const forecast = num(cash?.forecast);
  const received = num(cash?.received);
  const maxWeek = Math.max(0, ...weeks.map(weekTotal));
  const nothing = forecast === 0 && received === 0 && maxWeek === 0;
  const meterWidth = forecast > 0 ? Math.min(100, (received / forecast) * 100) : 0;

  return (
    <section
      aria-labelledby={`${ids}-h`}
      className="grid content-start gap-3 rounded-md border border-border bg-surface p-5 mobile:p-3.5"
    >
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 id={`${ids}-h`} className="m-0 text-lg">{heading}</h2>
        <small className="text-xs text-text-muted">เติมให้อัตโนมัติ</small>
      </div>

      {nothing ? (
        <p className="m-0 text-sm text-text-muted">ยังไม่มีเงินที่คาดว่าจะเข้าเดือนนี้</p>
      ) : (
        <>
          <div className="flex flex-wrap items-baseline gap-2">
            <strong data-testid="cash-received" className="text-[26px] font-extrabold tabular-nums text-text">
              {baht(received)}
            </strong>
            <small className="text-sm text-text-muted">รับแล้ว จากที่คาด {baht(forecast)}</small>
          </div>
          <div
            role="meter"
            aria-label="รับแล้วเทียบกับที่คาด"
            aria-valuemin={0}
            aria-valuemax={forecast}
            aria-valuenow={received}
            className="h-2 overflow-hidden rounded bg-surface-subtle"
          >
            <i className="block h-full rounded bg-success" style={{ width: `${meterWidth}%` }} />
          </div>

          {maxWeek > 0 ? (
            <>
              <ul className="m-0 grid list-none gap-1.5 p-0" aria-hidden="true">
                {weeks.map((w) => {
                  const total = weekTotal(w);
                  return (
                    <li
                      key={w.weekStart}
                      data-testid="cash-week"
                      className="grid grid-cols-[72px_minmax(0,1fr)_84px] items-center gap-2 text-xs"
                    >
                      <span className="text-text-muted">{weekEndLabel(w.weekStart, cash.month)}</span>
                      <span className="flex h-3.5 min-w-0">
                        <span
                          data-testid="cash-track"
                          className="flex h-full gap-[2px]"
                          style={{ width: total > 0 ? pct(total, maxWeek) : '0%' }}
                        >
                          {CASH_SERIES.filter((s) => num(w[s.key]) > 0).map((s) => (
                            <i
                              key={s.key}
                              data-testid="cash-seg"
                              data-series={s.key}
                              title={`${s.label} ${baht(w[s.key])}`}
                              className="block h-full rounded-sm first:rounded-l last:rounded-r"
                              style={{ flex: num(w[s.key]), backgroundColor: s.color }}
                            />
                          ))}
                        </span>
                      </span>
                      <span className="text-right font-bold tabular-nums text-text-secondary">
                        {total > 0 ? baht(total) : '—'}
                      </span>
                    </li>
                  );
                })}
              </ul>
              <ul aria-label="คำอธิบายสี" className="m-0 flex list-none flex-wrap gap-x-3.5 gap-y-1.5 p-0 text-xs text-text-secondary">
                {CASH_SERIES.map((s) => (
                  <li key={s.key} className="inline-flex items-center gap-1.5">
                    <i className="inline-block h-2.5 w-2.5 rounded-sm" style={{ backgroundColor: s.color }} aria-hidden="true" />
                    {s.label}
                  </li>
                ))}
              </ul>
            </>
          ) : null}

          <details className="text-xs text-text-secondary">
            <summary className="cursor-pointer select-none font-bold">ดูเป็นตาราง</summary>
            <div className="mt-2 overflow-x-auto">
              <table aria-label="เงินที่จะเข้า รายสัปดาห์" className="w-full border-collapse tabular-nums">
                <thead>
                  <tr className="text-left text-text-muted">
                    <th scope="col" className="py-1 pr-2 font-bold">สัปดาห์</th>
                    {CASH_SERIES.map((s) => (
                      <th key={s.key} scope="col" className="py-1 pr-2 text-right font-bold">{s.label}</th>
                    ))}
                    <th scope="col" className="py-1 text-right font-bold">รับแล้ว</th>
                  </tr>
                </thead>
                <tbody>
                  {weeks.map((w) => (
                    <tr key={w.weekStart} className="border-t border-border">
                      <td className="py-1 pr-2">{weekEndLabel(w.weekStart, cash.month)}</td>
                      {CASH_SERIES.map((s) => <td key={s.key} className="py-1 pr-2 text-right">{baht(num(w[s.key]))}</td>)}
                      <td className="py-1 text-right">{baht(num(w.received))}</td>
                    </tr>
                  ))}
                  <tr className="border-t border-border font-bold text-text">
                    <td className="py-1 pr-2">รวม</td>
                    {CASH_SERIES.map((s) => <td key={s.key} className="py-1 pr-2 text-right">{baht(num(cash[s.key]))}</td>)}
                    <td className="py-1 text-right">{baht(received)}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </details>
        </>
      )}
    </section>
  );
}
