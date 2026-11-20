import { Link } from 'react-router-dom';
import { Icon } from '../../components/common/Icon.jsx';
import { orderTotalLabel } from '../tickets/orderChain.js';
import {
  BOARD_COLUMNS, MONEY_CHIP_COLUMNS, cardMoneyChip, money, otherCurrencyTotals, ownerShortName, quarterLabel, sumValue,
  totalLabel,
} from './boardFormat.js';

function Chip({ tone = 'neutral', children }) {
  // index.css tokens; each foreground reads at 4.5:1 or better on its own background.
  const tones = {
    neutral: 'bg-surface-subtle text-text-secondary',
    warning: 'bg-warning-bg text-warning-dark',
    danger: 'bg-danger-bg text-danger-dark',
    success: 'bg-success-bg text-success-dark',
    info: 'bg-info-bg text-info-dark',
  };
  return (
    <span className={`inline-flex shrink-0 items-center whitespace-nowrap rounded-full px-2 py-px text-2xs font-bold tabular-nums ${tones[tone]}`}>
      {children}
    </span>
  );
}

/**
 * The card's second line. In รอมัดจำ the deal waits on its buyer, so a card that knows the buyer names them instead of
 * the designer — on ONE line, truncated, with the full name in `title`, so every card in the column keeps one height.
 * In เสนอราคาแล้ว the value's own quotation number follows, never broken across lines.
 */
function cardWho(deal) {
  // The awarded buyer (owner rule #65: the customer comes from the quotation) wins over the rep's typed buyer name.
  const awarded = deal.awardedBuyerName || deal.buyerName;
  if (deal.boardColumn === 4 && awarded) return { buyer: `ผู้ซื้อ: ${awarded}` };
  return { designer: deal.designerName };
}

function DealCard({ deal, showCode, showOwner }) {
  const { buyer, designer } = cardWho(deal);
  const quarter = quarterLabel(deal.expectedQuarter);
  const meta = [designer, quarter].filter(Boolean).join(' · ');
  const quotationNumber = deal.boardColumn === 3 ? deal.quotationNumber : null;
  const moneyChip = MONEY_CHIP_COLUMNS.has(deal.boardColumn) ? cardMoneyChip(deal) : null;
  const owner = showOwner && deal.ownerName ? (
    <span
      data-testid="board-card-owner"
      title={`ผู้ดูแล: ${deal.ownerName}`}
      className="ml-1.5 inline-flex shrink-0 items-center rounded-full border border-border bg-surface-muted px-1.5 align-baseline text-2xs font-bold text-text-secondary"
    >
      <span className="sr-only">ผู้ดูแล </span>
      {ownerShortName(deal.ownerName)}
    </span>
  ) : null;
  return (
    <li
      data-testid={`board-card-${deal.ticketId}`}
      // `relative` + the link's ::after overlay make the whole card the tap target (≥44px on a phone), while the
      // link's own name stays the deal title for assistive tech.
      className={`relative grid gap-1 rounded-md border bg-surface p-3 hover:border-border-strong ${deal.quiet ? 'border-warning-border' : 'border-border'}`}
    >
      <Link
        to={`/tickets/${deal.ticketId}`}
        // The link's ::after covers the whole card, so a pointer over the truncated buyer line is over the LINK: the
        // full buyer name has to be the link's tooltip to be reachable at all.
        title={buyer}
        className="min-w-0 break-words text-sm font-bold text-text no-underline after:absolute after:inset-0 after:rounded-md after:content-[''] hover:underline focus-visible:underline focus-visible:after:outline-2 focus-visible:after:outline-offset-2 focus-visible:after:outline-primary"
      >
        {deal.title || deal.customerName}
        {deal.orderCount > 1 ? <span className="font-normal text-text-muted">{` · ${deal.orderCount} ออเดอร์`}</span> : null}
      </Link>
      {buyer ? (
        <span className="flex min-w-0 items-baseline text-xs text-text-muted">
          <span className="min-w-0 truncate" title={buyer}>{buyer}</span>
          {/* pl-1, not a leading space: a flex item drops its leading white space. */}
          {quarter ? <span className="shrink-0 whitespace-nowrap pl-1">{`· ${quarter}`}</span> : null}
          {owner}
        </span>
      ) : meta || quotationNumber || showCode || owner ? (
        <span className="min-w-0 break-words text-xs text-text-muted">
          {showCode && deal.repCode ? <b className="mr-1 font-extrabold text-text-secondary">{deal.repCode}</b> : null}
          {meta}
          {quotationNumber ? <span className="whitespace-nowrap">{`${meta ? ' · ' : ''}${quotationNumber}`}</span> : null}
          {owner}
        </span>
      ) : null}
      <span className="flex flex-wrap items-center justify-between gap-2">
        <span className="flex flex-wrap items-baseline gap-1.5">
          <span className="text-sm font-bold tabular-nums text-text">{money(deal.value, deal.currency)}</span>
          {orderTotalLabel(deal) ? <span className="text-xs text-text-muted">{orderTotalLabel(deal)}</span> : null}
          {deal.valueExpired ? <Chip>หมดอายุ</Chip> : null}
        </span>
        <span className="flex flex-wrap gap-1">
          {deal.quiet ? <Chip tone="warning">เงียบ {deal.daysQuiet} วัน</Chip> : null}
          {/* รอมัดจำ / ส่งของ · เก็บเงิน: the money chip replaces the % chip when one applies (approved prototype). */}
          {moneyChip ? <Chip tone={moneyChip.tone}>{moneyChip.text}</Chip>
            : deal.winProbability != null ? <Chip>{deal.winProbability}%</Chip> : null}
        </span>
      </span>
    </li>
  );
}

/**
 * The rep's deals in the five display columns (spec 2026-11-15 §7.1). Columns 3–5 move by themselves from documents
 * (quotation, order, deposit, delivery), so each carries a tag saying what moves a deal in. Five columns on a desktop, three on
 * a tablet, one stacked column on a phone — never a horizontal page scroll. `showOwner` (sales_manager / ceo on
 * งานทั้งหมด) tags each card with its rep's first name; a rep looking at their own deals does not need it.
 */
export function DealBoard({ deals, showOwner = false }) {
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
                {inColumn.length} งาน · {totalLabel(sumValue(inColumn), otherCurrencyTotals(inColumn))}
              </small>
              {col.auto ? (
                <span className="flex min-w-0 items-center gap-1 text-2xs font-bold text-accent-dark" title={col.moveRule}>
                  <Icon name="refresh" size={12} className="shrink-0" />
                  <span data-testid="board-column-hint" title={col.moveRule} className="min-w-0 truncate">{col.moveHint}</span>
                </span>
              ) : null}
            </header>
            {inColumn.length ? (
              <ul className="m-0 grid list-none gap-2 p-0">
                {inColumn.map((deal) => <DealCard key={deal.ticketId} deal={deal} showCode={showCode} showOwner={showOwner} />)}
              </ul>
            ) : (
              <p className="m-0 px-0.5 text-xs text-text-faint">ไม่มีงาน</p>
            )}
          </section>
        );
      })}
    </div>
  );
}
