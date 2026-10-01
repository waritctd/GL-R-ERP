// "Is it even your turn" wrapper around the per-role next-action resolvers
// (salesActions.nextSalesAction / importActions.nextImportAction /
// accountActions.nextAccountAction) — ticket-detail IA rebuild Phase 1 (see
// docs/ui-repair/02-information-architecture/TICKET_INFORMATION_ARCHITECTURE.md,
// "Work-state banner" / "Action bar (sticky)").
//
// FIX 1 (P2, clutter-follow-up review round 2): this used to gate on the
// deal's CURRENT stage BEFORE ever calling a resolver — a deal sitting in a
// stage gated to a role other than the viewer read as "not your turn" even
// when that viewer's own resolver had a real action pending. That was wrong
// for every AUTO stage (SALES_STAGES' `auto: true` entries): an auto stage's
// `gate` names the role whose action CAUSED entry into it, not the role whose
// action is pending NOW — so the role about to act is always looking at the
// stage BEFORE the one their own action would produce. Two real examples
// (verified against TicketService.java):
//   - Account needs to confirm the deposit (nextAccountAction ->
//     confirmDeposit) while the deal still sits at ORDER_RECEIVED (gate:
//     'sales' — TicketService.java:1029 only advances to DEPOSIT_RECEIVED
//     once account confirms). The old gate-first check showed account
//     "รอฝ่ายขาย" and hid their own pending action.
//   - Import needs to issue the IR (nextImportAction -> issueImportRequest)
//     while the deal still sits at DEPOSIT_RECEIVED (gate: 'account' —
//     TicketService.java:702 only advances to PROCUREMENT once import issues
//     the IR). Same false "รอฝ่ายบัญชี" negative.
//
// The fix: ask the matching resolver FIRST, unconditionally. There is deliberately NO "whose turn
// is it" fallback any more: when the resolver has nothing pending the module returns no action and
// the header says nothing (GLA-156 — the "รอ<ฝ่าย>" bar looked expandable, did nothing and named
// no next step, and on auto stages it named the wrong department).
//
// Presentation only, same convention as salesViewScope.js / accountActions.js
// / importActions.js: this NEVER claims an action the server would reject,
// and never hides one the server would allow. canEditStage / canAdvance /
// canLost / canHold / canDormant / canResume in DealStagePanel.jsx (backed by
// the real `GET /{id}/actions`) remain the sole authority on what is
// actually clickable; this module only decides which ONE action (if any)
// leads the sticky bar.

import { nextSalesAction, SALES_ACTION } from './salesActions.js';
import { nextImportAction } from './importActions.js';
import { nextAccountAction } from './accountActions.js';

/** The approver-side twin of salesActions' AWAIT_DIRECT_APPROVAL bucket (slice 2). */
export const DIRECT_APPROVAL_ACTION_KEY = 'approve_direct_quotation';

/**
 * The single next action for `user` on `deal`, or the "whose turn is it"
 * waiting state when the matching resolver has nothing pending for this
 * viewer right now.
 *
 * `pricingRequests` is passed straight through to nextSalesAction /
 * nextImportAction (see their own doc comments for the expected shape —
 * the ticket's own scoped list, e.g. `api.pricingRequests.listForTicket`).
 *
 * Returns `{ action }` — whatever the matching resolver returned for sales/
 * import/account viewers, `null` for every other role and for any deal that is
 * not ACTIVE. There is deliberately no "waiting on <department>" fallback: the
 * "รอฝ่ายขาย" banner looked expandable, did nothing and named no next step
 * (GLA-156), so it was removed rather than fixed.
 *
 * Slice 2 adds exactly two things, both keyed on a live direct quotation
 * (TicketSummaryDto.liveDirectQuotation) and both naming a concrete next step:
 * - sales_manager / ceo on a PENDING_APPROVAL one get their own action
 *   "อนุมัติใบเสนอราคา" -> /quotations/:id;
 * - the rep on that same state gets no button but `bannerText` — "รอ ผจก.ขาย/CEO
 *   อนุมัติใบเสนอราคา {number}" — a waiting line that names the document and who
 *   decides it (unlike the retired department-only banner above). Absent otherwise.
 */
export function resolveWorkState(user, deal, pricingRequests = [], { reviseTarget = null } = {}) {
  const role = user?.role;
  if (!deal || deal.lifecycle !== 'ACTIVE') return { action: null };

  // Slice 2 (SLICE-2-FLOW-A.md §E): a live direct quotation waiting for approval is the approvers'
  // move. sales_manager/ceo — exactly canApproveDealQuotation's audience — get their own action to
  // the quotation, where the real (server-gated) อนุมัติ button is. They get NOTHING new for a
  // DRAFT/APPROVED one: submitting and confirming the order are the rep's.
  if ((role === 'sales_manager' || role === 'ceo') && deal.liveDirectQuotation?.docStatus === 'PENDING_APPROVAL') {
    const live = deal.liveDirectQuotation;
    return {
      action: {
        key: DIRECT_APPROVAL_ACTION_KEY,
        label: 'อนุมัติใบเสนอราคา',
        to: `/quotations/${live.id}`,
        quotationId: live.id,
        quotationNumber: live.number,
      },
    };
  }

  const action = role === 'sales' ? nextSalesAction(deal, pricingRequests, { reviseTarget })
    : role === 'import' ? nextImportAction(deal, pricingRequests)
      : role === 'account' ? nextAccountAction(deal)
        : null;

  // The rep's AWAIT_DIRECT_APPROVAL bucket is a WAITING state, not a button: it becomes the
  // header's one work-state line, naming who it waits on and which document.
  if (action?.key === SALES_ACTION.AWAIT_DIRECT_APPROVAL) {
    return {
      action: null,
      bannerText: `รอ ผจก.ขาย/CEO อนุมัติใบเสนอราคา ${action.quotationNumber ?? ''}`.trim(),
    };
  }

  if (action) return { action };

  return { action: null };
}
