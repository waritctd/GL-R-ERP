// Account (ฝ่ายบัญชี) role-scoped views: the single "what does ฝ่ายบัญชี do next
// on this deal" helper, factored out of the account-only gates that already
// live in DealDepositPanel.jsx (Step 3 "รับชำระมัดจำ") and TicketDetailPage.jsx
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

// Mirrors DealDepositPanel.jsx's bypassesNotice() / backend DepositPolicy
// .bypassesDepositNotice() exactly — kept as its own small copy (not
// imported) because DealDepositPanel's version is a private, unexported
// function local to that component.
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

// Mirrors TicketService#requireClosePrerequisites (the predicate behind
// closeReady()/canConfirmClose): dual-track deals need FULLY_PAID + a fully
// delivered fulfilment status + no outstanding balance + the invoice on
// file; legacy (pre-dual-track) deals only need to be fully paid.
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

// Mirrors TicketService#canConfirmClose's own lifecycle/closeConfirmedAt gates
// (the CLOSE_CONFIRM_ROLES role check itself is enforced server-side and by
// canConfirmPayments — this helper is never the authority on WHO may act).
function closeReady(ticket) {
  const active = ticket.lifecycle == null || ticket.lifecycle === 'ACTIVE';
  return active && ticket.closeConfirmedAt == null && closePrerequisitesMet(ticket);
}

/**
 * The one money action ฝ่ายบัญชี should take on `ticket` right now, or `null`
 * if none of account's steps apply (nothing pending, or the deal isn't in
 * account's world at all).
 *
 * Checked in priority order — overdue first (money already late outranks a
 * routine confirmation), matching the money-worklist's own overdue-first
 * sort:
 *   1. overdue balance                              -> "ติดตามชำระ"
 *   2. DEPOSIT_NOTICE_ISSUED                          -> "ยืนยันรับมัดจำ"
 *   3. final payment due (DEPOSIT_PAID/AWAITING_FINAL_PAYMENT/deposit-bypassed)
 *                                                      -> "รับชำระส่วนที่เหลือ"
 *   4. close-ready (fully paid + fully delivered, not yet confirmed)
 *                                                      -> "ยืนยันพร้อมปิดงาน"
 *   5. CLOSED_PAID                                     -> "บันทึกใบกำกับ + ออกค่าคอม"
 *
 * KNOWN LIMITATION (step 5): the backend has no per-ticket "commission
 * already recorded?" flag on TicketSummaryDto, and account has no route to
 * GET /api/commissions (canListCommissionRecords excludes it, see
 * api/routes.js) to check the commission list directly — CommissionPage.jsx's
 * own createFromDeal flow has the exact same gap (see its "eligibleTickets"
 * comment). So a CLOSED_PAID deal always resolves to step 5 here even if its
 * commission was already recorded; the create-from-deal form itself is what
 * catches a genuine duplicate (ticketId uniqueness), so this never lets
 * anyone double-submit — it can only over-suggest the step once it's already
 * done.
 */
export function nextAccountAction(ticket) {
  if (!ticket) return null;
  const outstanding = ticket.amountOutstanding != null && Number(ticket.amountOutstanding) > 0;

  if (ticket.overdue && outstanding) {
    return { key: 'chaseOverdue', label: 'ติดตามชำระ', to: `/tickets/${ticket.id}`, urgent: true };
  }
  if (ticket.status === 'quotation_issued' && ticket.paymentStatus === 'DEPOSIT_NOTICE_ISSUED') {
    return { key: 'confirmDeposit', label: 'ยืนยันรับมัดจำ', to: `/tickets/${ticket.id}`, urgent: false };
  }
  if (ticket.status === 'quotation_issued' && finalPaymentDue(ticket)) {
    return { key: 'confirmFinalPayment', label: 'รับชำระส่วนที่เหลือ', to: `/tickets/${ticket.id}`, urgent: false };
  }
  if (closeReady(ticket)) {
    return { key: 'confirmCloseReady', label: 'ยืนยันพร้อมปิดงาน', to: `/tickets/${ticket.id}`, urgent: false };
  }
  if (ticket.salesStage === 'CLOSED_PAID') {
    return {
      key: 'recordInvoiceCommission',
      label: 'บันทึกใบกำกับ + ออกค่าคอม',
      to: `/commissions?ticketId=${ticket.id}`,
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
  if (ticket.paymentStatus === 'AWAITING_FINAL_PAYMENT') return 'finalPaymentPending';
  if (closeReady(ticket)) return 'closeReady';
  if (ticket.salesStage === 'CLOSED_PAID') return 'commissionPending';
  return null;
}
