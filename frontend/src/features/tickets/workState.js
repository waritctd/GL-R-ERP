// "Is it even your turn" wrapper around the per-role next-action resolvers
// (salesActions.nextSalesAction / importActions.nextImportAction /
// accountActions.nextAccountAction); see
// docs/ui-repair/02-information-architecture/TICKET_INFORMATION_ARCHITECTURE.md, "Work-state
// banner" / "Action bar (sticky)".
//
// The matching resolver is asked FIRST, unconditionally, never gated on the deal's CURRENT stage:
// an AUTO stage's `gate` (SALES_STAGES' `auto: true` entries) names the role whose action CAUSED
// entry into it, not the role whose action is pending NOW, so the role about to act is always
// looking at the stage BEFORE the one their own action would produce. Two real examples (verified
// against TicketService.java):
//   - Account needs to confirm the deposit (nextAccountAction -> confirmDeposit) while the deal
//     still sits at ORDER_RECEIVED (gate: 'sales'; TicketService.java:1029 only advances to
//     DEPOSIT_RECEIVED once account confirms).
//   - Import needs to issue the IR (nextImportAction -> issueImportRequest) while the deal still
//     sits at DEPOSIT_RECEIVED (gate: 'account'; TicketService.java:702 only advances to
//     PROCUREMENT once import issues the IR).
//
// There is deliberately NO "whose turn is it" fallback: when the resolver has nothing pending the
// module returns no action and the header says nothing (a "รอ<ฝ่าย>" bar looked expandable, did
// nothing, named no next step, and on auto stages named the wrong department).
//
// Presentation only, same convention as salesViewScope.js / accountActions.js / importActions.js:
// this NEVER claims an action the server would reject, and never hides one the server would allow.
// canEditStage / canAdvance / canLost / canHold / canDormant / canResume in DealStagePanel.jsx
// (backed by the real `GET /{id}/actions`) remain the sole authority on what is actually
// clickable; this module only decides which ONE action (if any) leads the sticky bar.

import { canApproveDealQuotationNow } from '../quotations/quotationMeta.js';
import {
  nextSalesAction, outcomeSecondaryAction, PARTY_LABEL, pendingRequestQuotation, reviseAction, REVISE_STAGES, SALES_ACTION,
} from './salesActions.js';
import { nextImportAction } from './importActions.js';
import { nextAccountAction } from './accountActions.js';

/** The approver-side twin of salesActions' AWAIT_DIRECT_APPROVAL bucket. */
const DIRECT_APPROVAL_ACTION_KEY = 'approve_direct_quotation';

/**
 * The single next action for `user` on `deal`, or the "whose turn is it"
 * waiting state when the matching resolver has nothing pending for this
 * viewer right now.
 *
 * `pricingRequests` is passed straight through to nextSalesAction /
 * nextImportAction (see their own doc comments for the expected shape —
 * the ticket's own scoped list, e.g. `api.pricingRequests.listForTicket`).
 *
 * Returns `{ action }` — whatever the matching resolver returned for sales/import/account viewers,
 * `null` for every other role and for any deal that is not ACTIVE. There is deliberately no
 * "waiting on <department>" fallback (see the header).
 *
 * Two things are keyed on a live direct quotation (TicketSummaryDto.liveDirectQuotation) and both
 * name a concrete next step:
 * - sales_manager / ceo on a PENDING_APPROVAL one get their own action
 *   "อนุมัติใบเสนอราคา" -> /quotations/:id;
 * - the rep on that same state gets no button but `bannerText` — "รอ ผจก.ขาย/CEO
 *   อนุมัติใบเสนอราคา {number}" — a waiting line that names the document and who decides it.
 *   Absent otherwise.
 * The same two apply to a PRICING_REQUEST-origin quotation awaiting approval, read from
 * `quotationByRequest` (see nextSalesAction).
 */
export function resolveWorkState(user, deal, pricingRequests = [], { reviseTarget = null, offRouteParties = null, quotationByRequest = null, depositNotice = null, remainingInvoice, importRequests, items } = {}) {
  const role = user?.role;
  if (!deal || deal.lifecycle !== 'ACTIVE') return { action: null };

  // A quotation waiting for approval is the approvers' move.
  // sales_manager/ceo — exactly canApproveDealQuotation's audience — get their own action to the
  // quotation, where the real (server-gated) อนุมัติ button is. They get NOTHING new for a
  // DRAFT/APPROVED one: submitting and confirming the order are the rep's.
  if (role === 'sales_manager' || role === 'ceo') {
    const pending = deal.liveDirectQuotation?.docStatus === 'PENDING_APPROVAL'
      ? deal.liveDirectQuotation
      : pendingRequestQuotation(deal, pricingRequests, quotationByRequest);
    // Only one this viewer may approve now: a price moved from the CEO's needs the CEO.
    if (pending && canApproveDealQuotationNow(user, pending)) {
      return {
        action: {
          key: DIRECT_APPROVAL_ACTION_KEY,
          label: 'อนุมัติใบเสนอราคา',
          to: `/quotations/${pending.id}`,
          quotationId: pending.id,
          quotationNumber: pending.number,
        },
      };
    }
  }

  const action = role === 'sales' ? nextSalesAction(deal, pricingRequests, { reviseTarget, offRouteParties, quotationByRequest, depositNotice, remainingInvoice, importRequests, items })
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

  // The เสนอราคา<party> primary comes with a line naming the sent quotation.
  if (action?.key === SALES_ACTION.QUOTE_NEXT_PARTY) return { action, bannerText: quoteNextPartyBanner(action) };

  if (action) return { action };

  return { action: null };
}

/** "ใบเสนอราคาผู้ออกแบบส่งแล้ว เลือกเสนอราคาเจ้าของโครงการ หรือข้ามไปผู้ซื้อ"; one party left reads
 * "…ส่งแล้ว ถัดไปเสนอราคา<next>"; a quotation the party said no to reads "…ไม่ผ่าน" instead. */
export function quoteNextPartyBanner({ anchorParty, anchorDeclined, parties, direct, skipBuyer }) {
  const sent = `ใบเสนอราคา${PARTY_LABEL[anchorParty]}${anchorDeclined ? 'ไม่ผ่าน' : direct ? 'อนุมัติแล้ว' : 'ส่งแล้ว'}`;
  const [next, ...later] = parties.map((party) => PARTY_LABEL[party]);
  if (skipBuyer) return `${sent} เลือกเสนอราคา${next} หรือไม่มีผู้รับเหมา`;
  return later.length > 0
    ? `${sent} เลือกเสนอราคา${next} หรือข้ามไป${later.join(', ')}`
    : `${sent} ถัดไปเสนอราคา${next}`;
}

/**
 * ADDITIONAL (secondary) actions beside the sticky primary, for the header's ⋯ menu. Sales only.
 * - บันทึกผลใบเสนอราคา for a sent designer/owner quotation whose outcome is still open
 *   (salesActions.outcomeSecondaryAction), whatever the primary is.
 * - At the owner / buyer quote stages the client may want a revision or be happy with the
 *   original, so แก้ใบเสนอราคา is offered alongside — never instead of — the primary. `reviseTarget`
 *   is resolved by the caller through quotationMeta.canReviseDealQuotation (owner + status live
 *   there). Skipped when revise is already the primary (approved direct quotation past draft).
 * - ยืนยันคำสั่งซื้อ for the APPROVED direct quotation when the primary is the route choice for it, or for
 *   `approvedDirect` beside a newer live one. `primary`: the caller's resolved action (else computed here).
 */
export function secondaryWorkActions(user, deal, pricingRequests = [], { reviseTarget = null, offRouteParties = null, quotationByRequest = null, depositNotice = null, remainingInvoice, importRequests, items, approvedDirect = null, primary: knownPrimary } = {}) {
  if (user?.role !== 'sales' || !deal || deal.lifecycle !== 'ACTIVE') return [];
  const outcome = outcomeSecondaryAction(deal, pricingRequests, quotationByRequest);
  // The caller's already-resolved primary action (null included) saves running the cascade twice.
  const primary = knownPrimary !== undefined ? knownPrimary
    : resolveWorkState(user, deal, pricingRequests, { reviseTarget, offRouteParties, quotationByRequest, depositNotice, remainingInvoice, importRequests, items }).action;
  const revise = reviseTarget && REVISE_STAGES.has(deal.salesStage) && primary?.key !== SALES_ACTION.REVISE_QUOTATION
    ? reviseAction(reviseTarget) : null;
  // The direct route choice replaces ยืนยันคำสั่งซื้อ as the primary; it stays one menu away. So does an APPROVED
  // row the summary does not serve (a newer live direct quotation hides it): `approvedDirect` is that row, read off
  // the deal's quotation list by an owner caller; the confirm 409s once the deal is past 'draft'.
  const confirmRow = primary?.key === SALES_ACTION.QUOTE_NEXT_PARTY && primary.direct
    ? { id: primary.quotationId, number: primary.quotationNumber }
    : primary?.key !== SALES_ACTION.CONFIRM_ORDER_DIRECT && approvedDirect && (deal.status ?? 'draft') === 'draft' ? approvedDirect : null;
  const confirmDirect = confirmRow
    ? { key: SALES_ACTION.CONFIRM_ORDER_DIRECT, label: 'ยืนยันคำสั่งซื้อ', quotationId: confirmRow.id, quotationNumber: confirmRow.number }
    : null;
  return [outcome, revise, confirmDirect].filter(Boolean);
}
