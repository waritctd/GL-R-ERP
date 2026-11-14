import { Link } from 'react-router-dom';
import { Icon } from '../../components/common/Icon.jsx';
import { BOARD_COLUMNS, baht, quarterLabel, sumValue } from './boardFormat.js';

function Chip({ tone = 'neutral', children }) {
  const tones = {
    neutral: 'bg-surface-subtle text-text-secondary',
    warning: 'bg-warning-bg text-warning-dark',
  };
  return (
    <span className={`inline-flex shrink-0 items-center rounded-full px-2 py-px text-2xs font-bold tabular-nums ${tones[tone]}`}>
      {children}
    </span>
  );
}

function DealCard({ deal, showCode }) {
  const meta = [deal.designerName, quarterLabel(deal.expectedQuarter)].filter(Boolean).join(' · ');
  return (
    <li
      data-testid={`board-card-${deal.ticketId}`}
      className={`grid gap-1 rounded-md border bg-surface p-3 ${deal.quiet ? 'border-warning-border' : 'border-border'}`}
    >
      <Link
        to={`/tickets/${deal.ticketId}`}
        className="min-w-0 break-words text-sm font-bold text-text no-underline hover:underline focus-visible:underline"
      >
        {deal.title || deal.customerName}
      </Link>
      {meta || showCode ? (
        <span className="min-w-0 break-words text-xs text-text-muted">
          {showCode && deal.repCode ? <b className="mr-1 font-extrabold text-text-secondary">{deal.repCode}</b> : null}
          {meta}
        </span>
      ) : null}
      <span className="flex flex-wrap items-center justify-between gap-2">
        <span className="text-sm font-bold tabular-nums text-text">{baht(deal.value)}</span>
        <span className="flex flex-wrap gap-1">
          {deal.quiet ? <Chip tone="warning">เงียบ {deal.daysQuiet} วัน</Chip> : null}
          {deal.winProbability != null ? <Chip>{deal.winProbability}%</Chip> : null}
        </span>
      </span>
    </li>
  );
}

/**
 * The rep's deals in the five display columns (spec 2026-11-15 §7.1). Columns 3–5 move by themselves from documents
 * (quotation, order, deposit, delivery), so they carry an อัปเดตอัตโนมัติ tag. Five columns on a desktop, three on
 * a tablet, one stacked column on a phone — never a horizontal page scroll.
 */
export function DealBoard({ deals }) {
  return (
    <div data-testid="deal-board" className="grid grid-cols-5 gap-3 nav-drawer:grid-cols-3 mobile:grid-cols-1">
      {BOARD_COLUMNS.map((col) => {
        const inColumn = deals.filter((d) => d.boardColumn === col.column);
        const showCode = col.column === 3 || col.column === 5;
        return (
          <section
            key={col.column}
            data-testid={`board-column-${col.column}`}
            aria-label={`${col.label} · ${col.code}`}
            className="flex min-w-0 flex-col gap-2 rounded-md bg-surface-muted p-2.5"
          >
            <header className="grid gap-0.5 px-0.5">
              <h3 className="m-0 flex flex-wrap items-baseline gap-1.5 text-sm font-extrabold text-text">
                {col.label}
                <span className="text-2xs font-bold text-text-muted">· {col.code}</span>
              </h3>
              <small className="text-xs tabular-nums text-text-muted">
                {inColumn.length} ดีล · {baht(sumValue(inColumn))}
              </small>
              {col.auto ? (
                <span className="inline-flex items-center gap-1 text-2xs font-bold text-accent-dark">
                  <Icon name="refresh" size={12} />
                  อัปเดตอัตโนมัติ
                </span>
              ) : null}
            </header>
            {inColumn.length ? (
              <ul className="m-0 grid list-none gap-2 p-0">
                {inColumn.map((deal) => <DealCard key={deal.ticketId} deal={deal} showCode={showCode} />)}
              </ul>
            ) : (
              <p className="m-0 px-0.5 text-xs text-text-faint">ไม่มีดีล</p>
            )}
          </section>
        );
      })}
    </div>
  );
}
