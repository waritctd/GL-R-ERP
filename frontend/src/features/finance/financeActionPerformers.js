// Who the SERVER lets perform each finance step nextAccountAction() names. The worklist uses it only to decide
// whether a row's button may carry the action's name (a verb the viewer can act on) or must read "เปิด" — never to
// grant anything: the deal finance page shows only the actions FinanceDealDto.availableActions lists.
//
// Read from the Java (backend/src/main/java/th/co/glr/hr/...):
//   confirmDeposit          -> DEPOSIT_PAID   ticket/TicketService.java:82  DEPOSIT_CONFIRM_ROLES = {account}, offered at :3476
//   confirmFinalPayment     -> FINAL_PAYMENT  ticket/TicketService.java:91  PAYMENT_RECORD_ROLES  = {account}, offered at :3515
//   confirmCloseReady       -> CONFIRM_CLOSE  ticket/TicketService.java:99  CLOSE_CONFIRM_ROLES   = {account}, canConfirmClose :3742
//   recordInvoiceCommission -> RECORD_INVOICE finance/FinanceDealService.java:220-224, added only when role is "account"
//   chaseOverdue            -> no server action (following up a late payment writes nothing), so nobody "performs" it
// The CEO reaches /finance (canConfirmPayments) but is on none of these lists. Keep this table in step with those lines.
const PERFORMERS = Object.freeze({
  confirmDeposit: Object.freeze(['account']),
  confirmFinalPayment: Object.freeze(['account']),
  confirmCloseReady: Object.freeze(['account']),
  recordInvoiceCommission: Object.freeze(['account']),
  chaseOverdue: Object.freeze([]),
});

/** True when the server offers the step `actionKey` to a viewer with `role`. Unknown steps: nobody. */
export function canPerformFinanceStep(actionKey, role) {
  return Object.hasOwn(PERFORMERS, actionKey ?? '') && PERFORMERS[actionKey].includes(role);
}
