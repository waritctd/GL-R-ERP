import { useState } from 'react';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { addDaysIso, formatMoney, formatThaiMonthYearLongFromMonthInputValue } from '../../utils/format.js';

// The report's five money lines, in the reps' old sheet order (line 1 is the total, drawn last).
const LINES = [
  { key: 'received', no: 2, label: 'รับชำระแล้ว', hint: 'ฝ่ายบัญชีบันทึกรับชำระแล้ว' },
  { key: 'deliveredAwaiting', no: 3, label: 'ส่งมอบแล้ว รอรับชำระ', hint: 'รอรับชำระส่วนที่เหลือ' },
  { key: 'deliveriesScheduled', no: 4, label: 'นัดส่งมอบแล้ว รอรับชำระ', hint: 'รับชำระในวันส่งมอบ' },
  { key: 'depositsExpected', no: 5, label: 'เงินมัดจำที่คาดว่าจะได้รับ', hint: 'ออกใบแจ้งมัดจำแล้ว หรือระบุวันที่ไว้' },
];

const weekTotal = (w) => w.received + w.deliveredAwaiting + w.deliveriesScheduled + w.depositsExpected;
// Table cells carry no ฿ (the panel says รวม VAT once); a zero reads as a dash.
const cell = (value) => (Number(value) === 0 ? '–' : formatMoney(value).slice(1));

/** "23–27" for a week's working days, clipped to the month it is listed under ("30" for a week that spills over). */
export function weekShort(weekStart, month) {
  const [y, m] = month.split('-').map(Number);
  const first = `${month}-01`;
  const last = `${month}-${new Date(Date.UTC(y, m, 0)).getUTCDate()}`;
  const start = weekStart < first ? first : weekStart;
  const friday = addDaysIso(weekStart, 4);
  const end = friday > last ? last : friday;
  const [from, to] = [Number(start.slice(8)), Number(end.slice(8))];
  return from === to ? String(from) : `${from}–${to}`;
}

function Hero({ ledger }) {
  const remaining = ledger.total - ledger.received;
  const pct = ledger.total > 0 ? Math.round((ledger.received / ledger.total) * 100) : 0;
  return (
    <div className="grid grid-cols-2 gap-6 mobile:grid-cols-1 mobile:gap-3">
      <div className="grid gap-0.5">
        <small className="text-xs font-bold text-text-muted">1 · ประมาณการยอดรับเงินเดือนนี้</small>
        <b className="text-2xl font-extrabold tabular-nums text-text">{formatMoney(ledger.total)}</b>
      </div>
      <div className="grid gap-1.5">
        <div className="flex flex-wrap justify-between gap-2 text-sm">
          <span className="text-text-muted">2 · รับชำระแล้ว</span>
          <b className="tabular-nums">{formatMoney(ledger.received)} · {pct}%</b>
        </div>
        <div className="h-2 overflow-hidden rounded-full bg-surface-subtle" role="img" aria-label={`รับชำระแล้ว ${pct} เปอร์เซ็นต์ของประมาณการ`}>
          <i className="block h-full bg-accent" style={{ width: `${pct}%` }} />
        </div>
        <div className="flex flex-wrap justify-between gap-2 text-sm">
          <span className="text-text-muted">คงเหลือที่คาดว่าจะรับ</span>
          <b className="tabular-nums">{formatMoney(remaining)}</b>
        </div>
      </div>
    </div>
  );
}

function DesktopTable({ ledger }) {
  const { weeks, month } = ledger;
  const max = Math.max(1, ...weeks.map(weekTotal));
  const px = (v) => `${Math.round((v / max) * 48)}px`;
  const th = 'px-3 py-2 text-right text-xs font-bold text-text-muted';
  const cur = (w) => (w.isCurrent ? 'bg-surface-subtle' : '');
  return (
    <div className="overflow-x-auto mobile:hidden">
      <table className="w-full min-w-[720px] border-collapse text-sm tabular-nums">
        <thead>
          <tr>
            <th scope="col" className="px-3 py-2 text-left align-bottom text-xs font-bold text-text-muted">
              <span className="flex items-center gap-1.5"><i className="size-2 rounded-full bg-accent" />รับชำระแล้ว</span>
              <span className="flex items-center gap-1.5"><i className="size-2 rounded-full bg-info-border" />คาดว่าจะรับ</span>
            </th>
            {weeks.map((w) => {
              const expected = weekTotal(w) - w.received;
              return (
                <td key={w.weekStart} className={`px-3 pt-2 align-bottom ${cur(w)}`}>
                  <div
                    className="ml-auto flex h-14 w-8 flex-col justify-end"
                    role="img"
                    aria-label={`สัปดาห์ ${weekShort(w.weekStart, month)} รับแล้ว ${formatMoney(w.received)} คาดว่าจะรับอีก ${formatMoney(expected)}`}
                  >
                    <i className="block w-full bg-info-border" style={{ height: px(expected) }} />
                    <i className="block w-full bg-accent" style={{ height: px(w.received) }} />
                  </div>
                </td>
              );
            })}
            <td />
          </tr>
          <tr className="border-b border-border">
            <th scope="col" className="px-3 py-2 text-left text-xs font-bold text-text-muted">สัปดาห์ที่รับเงิน</th>
            {weeks.map((w) => (
              <th key={w.weekStart} scope="col" className={`${th} ${cur(w)}`}>
                {weekShort(w.weekStart, month)}
                {w.isCurrent ? <span className="block text-primary">สัปดาห์นี้</span> : null}
                {w.slipped > 0 ? <StatusBadge tone="warning">เลื่อนกำหนด</StatusBadge> : null}
              </th>
            ))}
            <th scope="col" className={th}>รวมเดือน</th>
          </tr>
        </thead>
        <tbody>
          {LINES.map((line) => (
            <tr key={line.key} className="border-b border-border-subtle">
              <th scope="row" className="px-3 py-2 text-left font-bold">
                {line.no} · {line.label}
                <small className="block text-xs font-normal text-text-muted">{line.hint}</small>
              </th>
              {weeks.map((w) => <td key={w.weekStart} className={`px-3 py-2 text-right ${cur(w)}`}>{cell(w[line.key])}</td>)}
              <td className="px-3 py-2 text-right font-bold">{cell(ledger[line.key])}</td>
            </tr>
          ))}
        </tbody>
        <tfoot>
          <tr className="font-extrabold">
            <th scope="row" className="px-3 py-2 text-left">1 · ประมาณการยอดรับเงินรวม</th>
            {weeks.map((w) => <td key={w.weekStart} className={`px-3 py-2 text-right ${cur(w)}`}>{cell(weekTotal(w))}</td>)}
            <td className="px-3 py-2 text-right">{cell(ledger.total)}</td>
          </tr>
        </tfoot>
      </table>
    </div>
  );
}

function PhoneWeeks({ ledger }) {
  const { weeks, month } = ledger;
  const [picked, setPicked] = useState(null);
  const chosen = weeks.find((w) => w.weekStart === picked) ?? weeks.find((w) => w.isCurrent) ?? weeks[0];
  if (!chosen) return null;
  const label = `${weekShort(chosen.weekStart, month)} ${formatThaiMonthYearLongFromMonthInputValue(month).split(' ')[0]}`;
  return (
    <div className="hidden gap-3 mobile:grid">
      <div role="group" aria-label="เลือกสัปดาห์" className="flex gap-2 overflow-x-auto pb-1">
        {weeks.map((w) => (
          <button
            key={w.weekStart}
            type="button"
            aria-pressed={w === chosen}
            onClick={() => setPicked(w.weekStart)}
            className={`grid min-h-11 min-w-[84px] shrink-0 cursor-pointer content-center rounded-md border px-3 py-1 text-center text-sm font-bold ${w === chosen ? 'border-primary bg-primary-bg text-text' : 'border-border bg-surface text-text-secondary'}`}
          >
            {weekShort(w.weekStart, month)}
            <small className="text-xs font-normal tabular-nums text-text-muted">{cell(weekTotal(w))}</small>
          </button>
        ))}
      </div>
      <dl className="m-0 grid divide-y divide-border-subtle">
        {LINES.map((line) => (
          <div key={line.key} className="flex items-baseline justify-between gap-3 py-2.5">
            <dt className="text-sm text-text-muted">{line.no} · {line.label}</dt>
            <dd className="m-0 text-sm font-bold tabular-nums">{cell(chosen[line.key])}</dd>
          </div>
        ))}
        {chosen.slipped > 0 ? (
          <div className="flex items-center justify-between gap-3 py-2.5">
            <dt><StatusBadge tone="warning">เลื่อนกำหนด</StatusBadge></dt>
            <dd className="m-0 text-sm tabular-nums text-text-muted">ในจำนวนนี้ {formatMoney(chosen.slipped)}</dd>
          </div>
        ) : null}
        <div className="flex items-baseline justify-between gap-3 py-2.5">
          <dt className="text-sm font-extrabold">รวม {label}</dt>
          <dd className="m-0 text-base font-extrabold tabular-nums">{formatMoney(weekTotal(chosen))}</dd>
        </div>
      </dl>
    </div>
  );
}

/** Money for the month: the hero, then the by-week ledger (a table on desktop, week chips and five lines on a phone). */
export function WeeklyLedger({ ledger, moneyFrom, formatDate }) {
  return (
    <div className="grid gap-4">
      <Hero ledger={ledger} />
      <DesktopTable ledger={ledger} />
      <PhoneWeeks ledger={ledger} />
      <p className="m-0 text-xs text-text-muted">
        สัปดาห์ที่ผ่านมาแสดงเฉพาะยอดที่รับชำระจริง ยอดที่คาดไว้แต่ยังไม่ได้รับจะยกมาแสดงในสัปดาห์ปัจจุบันพร้อมป้าย “เลื่อนกำหนด”
        {moneyFrom ? ` · นับเฉพาะงานที่ยืนยันคำสั่งซื้อในพอร์ทัลตั้งแต่ ${formatDate(moneyFrom)}` : ''}
      </p>
    </div>
  );
}
