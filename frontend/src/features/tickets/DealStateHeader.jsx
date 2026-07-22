import { Icon } from '../../components/common/Icon.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import {
  dealLifecycleLabel, dealStageLabel, formatMoney, fulfilmentStatusLabel, paymentStageLabel,
  pricingRequestStatusLabel,
} from '../../utils/format.js';
import { activePricingRequestsSummary } from '../pricingRequests/pricingRequestMeta.js';

/**
 * One compact "stat chip": a label over either a StatusBadge (when `tone` is
 * given) or a plain value (deal value, which has no status tone of its own).
 */
function StatChip({ label, value, tone }) {
  return (
    <div className="flex min-w-0 flex-col gap-1 rounded-lg border border-border bg-surface-subtle px-3 py-2">
      <span className="text-2xs font-bold uppercase tracking-wide text-text-muted">{label}</span>
      {tone ? (
        <StatusBadge tone={tone}>{value}</StatusBadge>
      ) : (
        <span className="truncate text-sm font-extrabold text-text">{value}</span>
      )}
    </div>
  );
}

/**
 * Deal Workspace state header (Phase 2 Slice S2 — see
 * docs/agent-handoffs/104_feat-deal-workspace-unification.md): the single
 * glanceable summary of "where is this deal, and whose move is it" that sits
 * above every other section on the page — deal code/title/customer +
 * lifecycle badge, a compact stat strip (sales stage × PCR status × payment
 * status × fulfilment status × deal value), and a "ถึงคิวคุณ" line naming the
 * current owner-role's next action with one primary CTA that mirrors it.
 *
 * Every value here is read straight off the ticket summary / the already-
 * fetched pricingRequests list, or derived by the parent from the SAME `can`
 * flags (themselves gated on `GET /{id}/actions`) that drive every button
 * elsewhere on the page — this component never invents a status or action
 * the data can't support, it only surfaces what TicketDetailPage already
 * computed, once, at the top.
 *
 * `nextAction`: this viewer's own next step (text), or null if it isn't
 * their turn. `waitingHint`: who/what the deal is waiting on instead, when
 * `nextAction` is null. `primaryAction`: the same CTA node the page already
 * renders in the stage panel — passed through, not duplicated, so the two
 * never disagree.
 */
export function DealStateHeader({
  summary, pricingRequests = [], primaryAction, nextAction, waitingHint, onRefresh,
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

  const queueText = nextAction ?? waitingHint ?? null;

  return (
    <section className="flex flex-col gap-4 rounded-xl border border-border bg-surface p-4 sm:p-5">
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

      <div className="grid grid-cols-2 gap-2 sm:grid-cols-5">
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
      </div>

      {queueText ? (
        <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-info-border bg-info-bg px-4 py-3">
          <div className="flex min-w-0 items-start gap-2">
            <Icon name="chevronRight" size={14} className="mt-0.5 shrink-0 text-info" />
            <span className="text-sm font-bold text-info">
              {nextAction ? `ถึงคิวคุณ: ${nextAction}` : queueText}
            </span>
          </div>
          {nextAction && primaryAction ? <div className="shrink-0">{primaryAction}</div> : null}
        </div>
      ) : null}
    </section>
  );
}
