// Words for the payment-due facts TicketSummaryDto already serves (paymentDueBasis / paymentDueCreditDays /
// paymentDueDate, derived server-side from the payable quotation's terms and the full-delivery date —
// TicketRepository#paymentTermsRowSql). Labels only: no date arithmetic happens here.
import { formatThaiDate } from '../../utils/format.js';

/** "เครดิต 30 วันนับจากวันส่งมอบ" / "ชำระเมื่อส่งมอบ", or null when the quotation has no structured term. */
export function paymentDueBasisText(basis, creditDays) {
  if (basis === 'CREDIT_FROM_DELIVERY') return `เครดิต ${creditDays} วันนับจากวันส่งมอบ`;
  if (basis === 'ON_DELIVERY') return 'ชำระเมื่อส่งมอบ';
  return null;
}

/**
 * One read-only line for the remaining-invoice dialog: the term, then the served due date — or, before the deal is
 * fully delivered (no due date served yet), that it is waiting for delivery. Null when there is no structured term.
 */
export function paymentTermsLine(summary) {
  const basis = paymentDueBasisText(summary?.paymentDueBasis, summary?.paymentDueCreditDays);
  if (!basis) return null;
  return summary.paymentDueDate
    ? `${basis} · ครบกำหนด ${formatThaiDate(summary.paymentDueDate)}`
    : `${basis} · ยังไม่ถึงกำหนด — รอส่งมอบ`;
}
