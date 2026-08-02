import { useMemo } from 'react';
import { Icon } from '../../components/common/Icon.jsx';
import { SkeletonText } from '../../components/common/Skeleton.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import {
  formatMoney, formatThaiDate, overdueBadgeLabel, paymentStageLabel,
} from '../../utils/format.js';
import { EVENT_KIND_LABEL, PAYMENT_TRACK_KINDS } from './DealHistoryPanel.jsx';
import { PAYMENT_SUBSTEPS } from './stageMeta.js';

// Slice A "chip diet": moved out of DealStagePanel (see its own doc comment)
// into the money tab's own payment section — the one place on the page that
// didn't already show this. The payment stage's inner progression
// (ลูกค้ายืนยัน → ... → ชำระครบแล้ว) is finer-grained than both the header's
// single "การชำระเงิน" chip (DealStateHeader) and this panel's own
// paymentStageLabel badge in its header. Slice E: moved verbatim from
// TicketDetailPage.jsx into this file along with the rest of the money
// section — it still answers a different question than the timeline below
// ("where are we in the fixed sequence, including steps that haven't
// happened yet") from what the timeline answers ("what has actually
// happened, in order, interleaved with amounts") — so it stays, not folded
// into the timeline rows.
function PaymentSubstepChips({ currentCode }) {
  const currentIdx = PAYMENT_SUBSTEPS.findIndex((s) => s.code === currentCode);
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      <span className="text-2xs font-bold text-text-muted">การชำระเงิน:</span>
      {PAYMENT_SUBSTEPS.map((step, i) => {
        const done = i < currentIdx;
        const current = i === currentIdx;
        return (
          <span
            key={step.code}
            className={`inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-2xs font-bold ${
              done ? 'bg-success-bg text-success-dark'
                : current ? 'bg-info-bg text-info'
                  : 'bg-surface-subtle text-text-muted'
            }`}
          >
            {done ? <Icon name="check" size={11} /> : null}
            {step.label}
          </span>
        );
      })}
    </div>
  );
}

// Mirrors DealDepositPanel's own (unexported) `bypassesNotice` — duplicated
// rather than imported, matching the precedent DealFulfilmentPanel's own
// StepRoleTag/StepNumber comment sets for small single-purpose helpers no
// file exports yet (moved verbatim from TicketDetailPage.jsx, Slice E). A
// policy other than the default REQUIRED skips the deposit-notice/payment
// steps entirely, so the substep progression above doesn't mean anything for
// those deals.
function depositBypassesNotice(policy) {
  return ['NOT_REQUIRED', 'WAIVED', 'CREDIT_CUSTOMER'].includes(policy);
}

/** `Date.parse`-safe timestamp, or `null` for a missing/unparseable value —
 * same discipline as DealHistoryPanel's own `toTimestamp`: a row with no
 * parseable timestamp sorts last rather than crashing `Array#sort` or
 * floating to the top as `NaN` would. Not imported from that file because it
 * isn't exported there either (a 4-line pure function, not shared state —
 * unlike PAYMENT_TRACK_KINDS/EVENT_KIND_LABEL, which this file DOES import
 * rather than redeclare). */
function toTimestamp(value) {
  if (!value) return null;
  const t = new Date(value).getTime();
  return Number.isNaN(t) ? null : t;
}

function MoneyEventRow({ event }) {
  return (
    <li className="flex items-start gap-2">
      <span className="mt-1.5 h-2 w-2 shrink-0 rounded-full bg-success" aria-hidden="true" />
      <div className="min-w-0 flex-1">
        <strong className="block text-sm text-text">{EVENT_KIND_LABEL[event.kind] ?? event.kind}</strong>
        {event.actorName ? <span className="text-xs text-icon-muted">{event.actorName}</span> : null}
        <small className="block text-2xs text-text-muted">{formatThaiDate(event.createdAt)}</small>
      </div>
    </li>
  );
}

function MoneyReceiptRow({ receipt }) {
  return (
    <li className="flex items-start gap-2">
      <span className="mt-1.5 h-2 w-2 shrink-0 rounded-full bg-info" aria-hidden="true" />
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-baseline gap-2">
          <strong className="text-sm text-text">{receipt.kind}</strong>
          <span className="font-mono text-sm text-text">{formatMoney(receipt.amount)}</span>
        </div>
        <small className="block text-2xs text-text-muted">
          {receipt.recordedByName || '-'}{receipt.note ? ` · ${receipt.note}` : ''}
        </small>
        <small className="block text-2xs text-text-muted">{formatThaiDate(receipt.receivedAt)}</small>
      </div>
    </li>
  );
}

/**
 * "การชำระเงิน" (Slice E — see the sales/CRM notes on turning การเงิน into a
 * timeline): the deal's money history read as one chronological spine —
 * money-track events (`events` filtered to `PAYMENT_TRACK_KINDS`, imported
 * from DealHistoryPanel.jsx rather than redeclared — two copies of that set
 * drifting apart is exactly how an event would silently stop appearing on
 * one of the two surfaces) merged with `paymentReceipts`, newest first.
 * Everything the old three-box ledger showed is still here — the balance
 * figures (now a compact strip, not the centrepiece), the billing/due-date
 * line, the record-payment/set-billing actions, the payment-stage and
 * overdue badges — just organised around the sequence of events instead of
 * a static snapshot.
 *
 * Sort discipline copied from DealHistoryPanel's own comparator (see its
 * "FIX 4" comment for the non-transitive-comparator bug this guards
 * against): one consistent secondary key for every row regardless of
 * source, not a same-kind-only tiebreak, and a `null` timestamp sorts last
 * rather than floating to the top as `NaN` would.
 *
 * `DealDepositPanel` (its own "มัดจำ" section, its own testids the e2e
 * deposit-fulfilment-close spec drives directly) is NOT part of this
 * component — it renders separately, in the same place in the การเงิน tab,
 * exactly as before.
 */
export function DealMoneyTimeline({
  events = [],
  paymentReceipts = [],
  paymentsLoading = false,
  summary,
  canRecordPayment = false,
  canSetBilling = false,
  actionLoading = false,
  onRecordPayment,
  onSetBilling,
}) {
  const rows = useMemo(() => {
    const eventRows = events
      .filter((event) => PAYMENT_TRACK_KINDS.has(event.kind))
      .map((event) => ({ key: `event-${event.id}`, type: 'event', ts: toTimestamp(event.createdAt), event }));
    const receiptRows = paymentReceipts.map((receipt) => ({
      key: `receipt-${receipt.receiptId}`, type: 'receipt', ts: toTimestamp(receipt.receivedAt), receipt,
    }));
    return [...eventRows, ...receiptRows].sort((a, b) => {
      if (a.ts == null && b.ts == null) return 0;
      if (a.ts == null) return 1;
      if (b.ts == null) return -1;
      if (b.ts !== a.ts) return b.ts - a.ts;
      // Same instant: one fixed secondary key applies to EVERY pair
      // (event/event, receipt/receipt, and event-vs-receipt), not just a
      // same-kind special case — that non-transitive shortcut is the exact
      // bug DealHistoryPanel's own "FIX 4" comment documents. Events sort
      // before receipts at a shared instant (arbitrary but fixed); within
      // one kind, a numerically higher id (written later) sorts first.
      const aRank = a.type === 'event' ? 0 : 1;
      const bRank = b.type === 'event' ? 0 : 1;
      if (aRank !== bRank) return aRank - bRank;
      const aId = a.type === 'event' ? a.event.id : a.receipt.receiptId;
      const bId = b.type === 'event' ? b.event.id : b.receipt.receiptId;
      return bId - aId;
    });
  }, [events, paymentReceipts]);

  const outstanding = Number(summary.amountOutstanding ?? 0);

  return (
    <section className="panel">
      <div className="panel-header" style={{ alignItems: 'center' }}>
        <h2>การชำระเงิน</h2>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <StatusBadge tone={paymentStageLabel(summary.paymentStage).tone}>
            {paymentStageLabel(summary.paymentStage).label}
          </StatusBadge>
          {summary.overdue && (
            <StatusBadge tone={overdueBadgeLabel(true).tone}>{overdueBadgeLabel(true).label}</StatusBadge>
          )}
        </div>
      </div>
      <div style={{ padding: '0 18px 16px', display: 'flex', flexDirection: 'column', gap: 14 }}>
        {summary.paymentStatus && !depositBypassesNotice(summary.depositPolicy) ? (
          <PaymentSubstepChips currentCode={summary.paymentStatus} />
        ) : null}

        {/* Compact balance readout — de-emphasised relative to the old three
            large boxes, since the owner asked for the timeline (below) to be
            the centrepiece and the balance to be a readout, not the star.
            Outstanding stays visually heavier (it's the one live number a
            viewer actually needs to act on) while payable/paid sit flat
            beside it in the same strip. */}
        <div className="flex flex-wrap items-end gap-x-6 gap-y-2 rounded-lg border border-border-subtle bg-surface-muted px-3 py-2">
          <div className="flex flex-col">
            <span className="text-2xs text-text-muted">ยอดที่ต้องชำระ</span>
            <strong className="text-sm text-text">{formatMoney(summary.amountPayable ?? 0)}</strong>
          </div>
          <div className="flex flex-col">
            <span className="text-2xs text-text-muted">ชำระแล้ว</span>
            <strong className="text-sm text-text">{formatMoney(summary.amountPaid ?? 0)}</strong>
          </div>
          <div className="flex flex-col">
            <span className="text-2xs font-bold text-text-muted">คงเหลือ</span>
            <strong className={`text-lg ${outstanding > 0 ? 'text-danger' : 'text-text'}`}>
              {formatMoney(outstanding)}
            </strong>
          </div>
        </div>

        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px 18px', fontSize: 13, color: 'var(--color-text-muted)' }}>
          <span>วันวางบิล <strong style={{ color: 'var(--color-text-secondary)' }}>{formatThaiDate(summary.billingDate)}</strong></span>
          <span>ครบกำหนด <strong style={{ color: summary.overdue ? 'var(--color-danger)' : 'var(--color-text-secondary)' }}>{formatThaiDate(summary.dueDate)}</strong></span>
          {summary.nextFollowUpAt && <span>ติดตามครั้งถัดไป <strong style={{ color: 'var(--color-text-secondary)' }}>{formatThaiDate(summary.nextFollowUpAt)}</strong></span>}
        </div>

        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          {canRecordPayment && (
            <button type="button" className="primary-button" disabled={actionLoading} onClick={onRecordPayment}>
              บันทึกรับชำระเงิน
            </button>
          )}
          {canSetBilling && (
            <button type="button" className="secondary-button" disabled={actionLoading} onClick={onSetBilling}>
              ตั้งค่าการวางบิล
            </button>
          )}
        </div>

        <div style={{ borderTop: '1px solid var(--color-border)', paddingTop: 10 }}>
          <h3 style={{ margin: '0 0 8px', fontSize: 13, fontWeight: 700 }}>ไทม์ไลน์การชำระเงิน</h3>
          {paymentsLoading ? (
            <SkeletonText lines={2} />
          ) : rows.length === 0 ? (
            <EmptyState icon="clock" title="ยังไม่มีความเคลื่อนไหว" description="เหตุการณ์และการรับชำระของดีลนี้จะปรากฏที่นี่" />
          ) : (
            <ul className="m-0 flex list-none flex-col gap-2.5 p-0">
              {rows.map((row) => (
                row.type === 'event'
                  ? <MoneyEventRow key={row.key} event={row.event} />
                  : <MoneyReceiptRow key={row.key} receipt={row.receipt} />
              ))}
            </ul>
          )}
        </div>
      </div>
    </section>
  );
}
