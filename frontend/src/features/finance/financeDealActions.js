// Ordering of the money actions the server offers on a finance deal (FinanceDealDto.availableActions).
// The server decides WHICH actions exist; this only decides which one leads the header.
//
// Priority mirrors the order a deal's money moves: confirm the deposit, take the final payment,
// record any other payment, confirm the close, set up billing, then the stage moves. Reversing a
// close confirmation is never the headline action.
const PRIORITY = [
  'DEPOSIT_PAID',
  'FINAL_PAYMENT',
  'RECORD_PAYMENT',
  'CONFIRM_CLOSE',
  'SET_BILLING',
  'ADVANCE_STAGE',
  'UPDATE_STAGE',
  'REVOKE_CLOSE_CONFIRM',
];

const NEVER_PRIMARY = new Set(['REVOKE_CLOSE_CONFIRM']);

function rank(action) {
  const i = PRIORITY.indexOf(action?.action);
  return i === -1 ? PRIORITY.length : i;
}

/** The actions in display priority. Unrecognised ones sort last rather than being dropped. */
export function orderActions(actions) {
  return [...(actions ?? [])].sort((a, b) => rank(a) - rank(b));
}

/** The one action that leads the header, or null (no fake/disabled button when there is none). */
export function pickPrimaryAction(actions) {
  return orderActions(actions).find((a) => !NEVER_PRIMARY.has(a.action)) ?? null;
}
