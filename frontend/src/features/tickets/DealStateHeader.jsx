import { Icon } from '../../components/common/Icon.jsx';
import { OverflowMenu } from '../../components/common/OverflowMenu.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import {
  dealLifecycleLabel, dealStageLabel, formatMoney, fulfilmentStatusLabel, paymentStageLabel,
  pricingRequestStatusLabel,
} from '../../utils/format.js';
import { activePricingRequestsSummary } from '../pricingRequests/pricingRequestMeta.js';

/**
 * One compact "stat chip": a label (dt) over either a StatusBadge (when
 * `tone` is given) or a plain value (dd) — deal value has no status tone of
 * its own. Nesting fix (card-diet, 2026-07): this used to be its own
 * bordered/background box (`rounded-lg border border-border bg-surface-subtle
 * px-3 py-2`) sitting INSIDE DealStateHeader's own card — a nested card,
 * never right per DESIGN.md. Renders as a plain dt/dd pair now; the parent
 * `<dl>` grid spacing is what keeps the five values distinct, not per-item
 * chrome.
 */
function StatChip({ label, value, tone }) {
  return (
    <div className="flex min-w-0 flex-col gap-1">
      <dt className="text-2xs font-bold uppercase tracking-wide text-text-muted">{label}</dt>
      <dd className="m-0 min-w-0">
        {tone ? (
          <StatusBadge tone={tone}>{value}</StatusBadge>
        ) : (
          <span className="block truncate text-sm font-extrabold text-text">{value}</span>
        )}
      </dd>
    </div>
  );
}

/**
 * Deal Workspace state header (Phase 2 Slice S2, folded into the ticket-
 * detail IA rebuild Phase 1 — see
 * docs/ui-repair/02-information-architecture/TICKET_INFORMATION_ARCHITECTURE.md
 * "Persistent header" / "Action bar (sticky)"): the single glanceable
 * summary of "where is this deal, and whose move is it" — deal code/title/
 * customer + lifecycle badge, a compact stat strip (sales stage × PCR status
 * × payment status × fulfilment status × deal value), ONE work-state banner
 * line ("whose move, what's blocking" — region 4/6/7 of the IA), and the
 * sticky action bar's primary CTA + "⋯" overflow trigger. `sticky top-0`:
 * this is the one thing that should never scroll out of view on a page this
 * long (see the Phase-1 handoff's measured "4,324px / 12 panels / 15
 * buttons" finding).
 *
 * Every value here is read straight off the ticket summary / the already-
 * fetched pricingRequests list, or derived by the parent (workState.js's
 * resolveWorkState + the SAME `can` flags, themselves gated on
 * `GET /{id}/actions`, that drive every button elsewhere on the page) — this
 * component never invents a status or action the data can't support, it
 * only surfaces what TicketDetailPage already computed, once, at the top.
 *
 * `bannerText`: the ONE work-state line (already composed by the parent —
 * "ถึงคิวคุณ: ..." / "รอฝ่ายนำเข้า — รอชำระมัดจำ" / etc.), or null when there is
 * nothing to say (e.g. the deal is on hold/dormant/lost — DealStagePanel
 * already renders a dedicated banner for those states). `primaryAction`: the
 * one CTA node this viewer may act on right now, or null — never a second
 * copy of a button rendered elsewhere on the page. `overflowItems`: the
 * "⋯" menu's items (see OverflowMenu.jsx), already filtered to what this
 * viewer may do — empty/undefined renders no trigger at all.
 */
export function DealStateHeader({
  summary, pricingRequests = [], primaryAction, bannerText, overflowItems, onRefresh,
}) {
  const lifecycle = dealLifecycleLabel(summary.lifecycle ?? 'ACTIVE');
  const stage = dealStageLabel(summary.salesStage);
  const payment = paymentStageLabel(summary.paymentStage);
  const fulfilment = summary.fulfillmentStatus ? fulfilmentStatusLabel(summary.fulfillmentStatus) : null;

  // Most-recently-created non-cancelled pricing request stands in for "PCR
  // status" at a glance — activePricingRequestsSummary already sorts oldest
  // first (see pricingRequestMeta.js), so the last entry is the newest.
  const pricingSummary = activePricingRequestsSummary(pricingRequests);
  const latestPr = pricingSummary ? pricingSummary.requests[pricingSummary.requests.length - 1] : null;
  const pricingStatus = latestPr ? pricingRequestStatusLabel(latestPr.status) : null;

  return (
    <section className="sticky top-0 z-10 flex flex-col gap-4 rounded-xl border border-border bg-surface p-4 shadow-sm sm:p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <code className="rounded bg-surface-subtle px-2 py-0.5 text-xs text-text-muted">{summary.code}</code>
            <StatusBadge tone={lifecycle.tone}>{lifecycle.label}</StatusBadge>
          </div>
          <h1 className="mt-2 truncate text-xl font-extrabold text-text sm:text-2xl">
            {summary.customerName || summary.title}
          </h1>
          {summary.projectName ? (
            <p className="mt-0.5 truncate text-sm text-text-muted">{summary.projectName}</p>
          ) : null}
        </div>
        {onRefresh ? (
          <button type="button" className="icon-button" onClick={onRefresh} title="รีเฟรช" aria-label="รีเฟรช">
            <Icon name="refresh" />
          </button>
        ) : null}
      </div>

      <dl className="m-0 grid grid-cols-2 gap-x-3 gap-y-3 sm:grid-cols-5">
        <StatChip label="ขั้นตอนดีล" value={stage.label} tone={stage.tone} />
        <StatChip
          label="ใบขอราคา (PCR)"
          value={pricingStatus ? pricingStatus.label : 'ยังไม่มี'}
          tone={pricingStatus ? pricingStatus.tone : 'neutral'}
        />
        <StatChip label="การชำระเงิน" value={payment.label} tone={payment.tone} />
        <StatChip
          label="การนำเข้า"
          value={fulfilment ? fulfilment.label : 'ยังไม่เริ่ม'}
          tone={fulfilment ? fulfilment.tone : 'neutral'}
        />
        <StatChip label="มูลค่าดีล" value={formatMoney(summary.amountPayable ?? 0)} />
      </dl>

      {/* The sticky action bar (IA "Action bar (sticky)"): ONE work-state
          line + the one primary CTA this viewer may act on + the "⋯"
          overflow. Hidden entirely when there is nothing to say and nothing
          to do — never an empty chrome bar. */}
      {bannerText || primaryAction || (overflowItems && overflowItems.length > 0) ? (
        <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-info-border bg-info-bg px-4 py-3">
          <div className="flex min-w-0 items-start gap-2">
            {bannerText ? (
              <>
                <Icon name="chevronRight" size={14} className="mt-0.5 shrink-0 text-info" />
                <span className="text-sm font-bold text-info">{bannerText}</span>
              </>
            ) : null}
          </div>
          <div className="flex shrink-0 items-center gap-2">
            {primaryAction ? <div className="shrink-0">{primaryAction}</div> : null}
            <OverflowMenu items={overflowItems} />
          </div>
        </div>
      ) : null}
    </section>
  );
}
