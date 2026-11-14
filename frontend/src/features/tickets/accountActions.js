// Account (ฝ่ายบัญชี) role-scoped views: the single "what does ฝ่ายบัญชี do next
// on this deal" helper, factored out of the account-only gates that already
// live in /finance/deals/:id and TicketDetailPage.jsx
// (`can.confirmFinalPayment` / `can.confirmClose`) so AccountOverview.jsx and
// AccountFinancePage.jsx never invent a second copy of "when is this deal
// account's turn" logic that could drift from what the primary-action button
// on the deal page itself actually offers.
//
// Presentation only (per CLAUDE.md / salesViewScope.js's own doc comment):
// every gate here mirrors a real server-side check, but the server —
// TicketService's canConfirmFinalPaymentNow/canConfirmClose/DEPOSIT_PAID
// action gate — is the authority. This never grants an action the backend
// won't also accept; it only decides which one CTA a worklist row leads with.
//
// Works off a TicketSummaryDto-shaped object — the same shape both
// `api.tickets.list()` rows and a ticket detail's `summary` already share
// (see backend/.../ticket/TicketSummaryDto.java), so one helper serves both
// the Overview's list-derived rows and a single ticket's `can.*` computation.

import { isPastProcurement } from '../finance/moneyMilestone.js';

// Mirrors backend DepositPolicy.bypassesDepositNotice() exactly (dealMoneyStatus.js
// has the same triple) — kept as its own small copy.
const DEPOSIT_POLICY_BYPASSES_NOTICE = new Set(['NOT_REQUIRED', 'WAIVED', 'CREDIT_CUSTOMER']);

function depositBypassed(ticket) {
  return DEPOSIT_POLICY_BYPASSES_NOTICE.has(ticket.depositPolicy)
    && (ticket.paymentStatus == null || ticket.paymentStatus === 'CUSTOMER_CONFIRMED');
}

// Mirrors TicketService#canConfirmFinalPaymentNow exactly.
function finalPaymentDue(ticket) {
  return ticket.paymentStatus === 'AWAITING_FINAL_PAYMENT'
    || ticket.paymentStatus === 'DEPOSIT_PAID'
    || depositBypassed(ticket);
}

// The one predicate behind the รับชำระส่วนที่เหลือ to-do step; accountMoneyBucket reuses it.
// Owner ruling 2026-11-07: closed until money-milestone 3 (PROCUREMENT) is done. Unknown/null stage = closed.
function offersFinalPayment(ticket) {
  return ticket.status === 'quotation_issued' && finalPaymentDue(ticket) && isPastProcurement(ticket.salesStage);
}

// "Does account still owe/await a step on this deal", ignoring the final-payment stage gate: a balance owed
// while goods are in transit stays in ค้างรับ. Mirrors the SQL ACCOUNT_HAS_TODO, not what the deal page offers.
export function accountOwesStep(ticket) {
  if (!ticket) return false;
  // SQL ACCOUNT_HAS_TODO has no stage condition: lift EVERY stage the gate would close (incl. null/unknown).
  const lifted = !isPastProcurement(ticket.salesStage) ? { ...ticket, salesStage: 'DELIVERY_SCHEDULING' } : ticket;
  return nextAccountAction(lifted) != null;
}

// Owed money that only the final-payment stage gate is holding back (not overdue, so not chased):
// the landing's รอสินค้าเข้า panel. Shows no action, only that goods are still on the way.
export function awaitingGoodsForFinalPayment(ticket) {
  return accountOwesStep(ticket) && nextAccountAction(ticket) == null && finalPaymentDue(ticket);
}

// Mirrors TicketService#requireClosePrerequisites (the predicate behind closeReady()/canConfirmClose):
// dual-track deals need FULLY_PAID + a fully delivered fulfilment status + no outstanding balance + the
// invoice on file; legacy (pre-dual-track) deals only need to be fully paid.
function closePrerequisitesMet(ticket) {
  const outstanding = ticket.amountOutstanding != null && Number(ticket.amountOutstanding) > 0;
  if (outstanding) return false;
  const dualTrackOk = ticket.status === 'quotation_issued'
    && ticket.paymentStatus === 'FULLY_PAID'
    && ticket.fulfillmentStatus === 'FULLY_DELIVERED'
    && !!ticket.invoiceOnFile;
  const legacyOk = ticket.status === 'document_issued'
    && (ticket.paymentStatus == null || ticket.paymentStatus === 'FULLY_PAID');
  return dualTrackOk || legacyOk;
}

// Mirrors TicketService#canConfirmClose's own lifecycle/closeConfirmedAt gates (the CLOSE_CONFIRM_ROLES role
// check itself is enforced server-side and by canConfirmPayments — this helper is never the authority on WHO
// may act).
function closeReady(ticket) {
  const active = ticket.lifecycle == null || ticket.lifecycle === 'ACTIVE';
  return active && ticket.closeConfirmedAt == null && closePrerequisitesMet(ticket);
}

/** The one money action ฝ่ายบัญชี should take on `ticket` right now, or `null` if none of account's steps apply (nothing pending, or the deal isn't in account's world at all). overdue balance -> "ติดตามชำระ" 2.
 * @param {string} [viewerRole] GLA-118 (owner ruling 2026-09-20, part A): steps 2 and 3 both */
export function nextAccountAction(ticket, viewerRole) {
  if (!ticket) return null;
  const outstanding = ticket.amountOutstanding != null && Number(ticket.amountOutstanding) > 0;
  const canRecordPayments = viewerRole === undefined || viewerRole === 'account';
  // H1 lockdown: account is refused on the /tickets deal routes and works a deal on the finance page. The CEO
  // reaches /finance too (owner ruling), so every viewer of this worklist helper lands on the finance deal page.
  // (dealHref, used by the ticket list, still sends the ceo to /tickets/:id.)
  const dealPath = `/finance/deals/${ticket.id}`;

  if (ticket.overdue && outstanding) {
    return { key: 'chaseOverdue', label: 'ติดตามชำระ', to: dealPath, urgent: true };
  }
  if (canRecordPayments && ticket.status === 'quotation_issued' && ticket.paymentStatus === 'DEPOSIT_NOTICE_ISSUED') {
    return { key: 'confirmDeposit', label: 'ยืนยันรับมัดจำ', to: dealPath, urgent: false };
  }
  if (canRecordPayments && offersFinalPayment(ticket)) {
    return { key: 'confirmFinalPayment', label: 'รับชำระส่วนที่เหลือ', to: dealPath, urgent: false };
  }
  if (closeReady(ticket)) {
    return { key: 'confirmCloseReady', label: 'ยืนยันพร้อมปิดงาน', to: dealPath, urgent: false };
  }
  if (ticket.salesStage === 'CLOSED_PAID' && !ticket.commissionRecorded) {
    return {
      key: 'recordInvoiceCommission',
      label: 'บันทึกใบกำกับ',
      to: dealPath,
      urgent: false,
    };
  }
  return null;
}

/** Money-pulse bucket a deal belongs to, for the five-across Overview cards. */
export function accountMoneyBucket(ticket) {
  if (!ticket) return null;
  const outstanding = ticket.amountOutstanding != null && Number(ticket.amountOutstanding) > 0;
  if (ticket.overdue && outstanding) return 'overdue';
  if (ticket.paymentStatus === 'DEPOSIT_NOTICE_ISSUED') return 'depositPending';
  if (offersFinalPayment(ticket)) return 'finalPaymentPending';
  if (closeReady(ticket)) return 'closeReady';
  if (ticket.salesStage === 'CLOSED_PAID' && !ticket.commissionRecorded) return 'commissionPending';
  return null;
}
