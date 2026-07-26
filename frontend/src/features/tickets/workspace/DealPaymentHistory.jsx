import { SkeletonText } from '../../../components/common/Skeleton.jsx';
import { formatMoney, formatThaiDate } from '../../../utils/format.js';

export function DealPaymentHistory({ receipts = [], loading = false }) {
  return (
    <div className="border-t border-border pt-4" data-testid="deal-payment-history">
      <h3 className="m-0 mb-3 text-sm font-extrabold text-text">ประวัติรับชำระ</h3>
      {loading ? (
        <SkeletonText lines={2} />
      ) : receipts.length === 0 ? (
        <div className="rounded-md bg-surface-subtle px-3 py-3 text-sm text-text-muted">
          ยังไม่มีรายการรับชำระ
        </div>
      ) : (
        <div className="divide-y divide-border-subtle border-y border-border-subtle">
          {receipts.map((receipt) => (
            <div key={receipt.receiptId} className="grid gap-1 py-2.5 text-sm sm:grid-cols-[120px_100px_1fr] sm:gap-4">
              <time className="text-xs text-text-muted" dateTime={receipt.receivedAt}>{formatThaiDate(receipt.receivedAt)}</time>
              <strong className="text-text-secondary">{receipt.kind}</strong>
              <div className="min-w-0">
                <div className="font-bold tabular-nums text-text">{formatMoney(receipt.amount)}</div>
                <div className="break-words text-xs text-text-muted">
                  {receipt.recordedByName || '-'}{receipt.note ? ` · ${receipt.note}` : ''}
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
