// "What does MY deal need from ME right now": a single next-action CTA per deal, used by the
// "สิ่งที่ต้องทำ" worklist on SalesOverview.jsx.
//
// Presentation only, like salesViewScope.js's dealInScope / TicketListPage's worklistReason: never
// a security boundary (the button this CTA points at re-checks server-side). Built from data the
// caller already has (a ticket-list row `deal` from api.tickets.list, and the rep's own
// `pricingRequests` from api.pricingRequests.queue), so it never triggers a per-ticket fetch.
//
// Bucket 0: three direct-quotation buckets come first; a live DEAL_DIRECT quotation owns the
// deal's CTA outright.
//
// The other buckets are a priority cascade in pipeline order (earliest-unblocked step wins): a
// deal with no live pricing request needs "สร้างคำขอราคา" first, even if a follow-up is overdue.
// Exception: a deal already priced OUTSIDE the PricingRequest chain (legacy: its customer
// quotation went out through the retired ticket-level engine) skips bucket 1 even with zero
// pricing requests; see bucket 1's comment.
//
// Bucket 4 (RECORD_QUOTATION_OUTCOME) points the rep at the accept/reject/revision controls on
// DealQuotationPanel's "ราคาและใบเสนอราคา" section (the "เอกสาร" tab); without it an issued
// quotation could sit unrecorded with nothing on the page pointing at them. It is AFTER bucket 3
// (CONFIRM_ORDER) so an accepted request still prioritises confirming the order.
//
// Bucket 5 (RECORD_DELIVERY, stages 13-14 ส่งมอบสินค้า handed to sales) reuses importActions.js's
// nextFulfilmentActionCode rather than keeping a second delivery-ready status list. It sits AFTER
// bucket 1: a delivery-ready deal matching bucket 1's guard still gets CREATE_PCR. See bucket 5.

import { bangkokTodayIso } from '../../utils/format.js';
import { DEAD_PRICING_REQUEST_STATUSES, QUOTATION_ROUTE as PARTY_ROUTE } from '../quotations/quotationMeta.js';
import { depositSettled } from './dealMoneyStatus.js';
import { nextFulfilmentActionCode } from './importActions.js';
import { isRemainingInvoiceReady } from './remainingInvoiceReadiness.js';

export const SALES_ACTION = {
  // The three buckets a LIVE direct quotation drives. AWAIT_DIRECT_APPROVAL also covers a
  // PRICING_REQUEST-origin quotation awaiting approval.
  SUBMIT_DIRECT_QUOTATION: 'submit_direct_quotation',
  AWAIT_DIRECT_APPROVAL: 'await_direct_approval',
  CONFIRM_ORDER_DIRECT: 'confirm_order_direct',
  // แก้ใบเสนอราคา: revise (or open the open revision of) an approved/issued quotation. Only produced
  // when the caller hands nextSalesAction a `reviseTarget`.
  REVISE_QUOTATION: 'revise_quotation',
  ADVANCE_STAGE: 'advance_stage',
  CREATE_PCR: 'create_pcr',
  // Once a designer/owner quotation is sent, เสนอราคา to a later party on the route, or, when that
  // party's request is still a private DRAFT, send it to import.
  QUOTE_NEXT_PARTY: 'quote_next_party',
  SUBMIT_PRICING_REQUEST: 'submit_pricing_request',
  ISSUE_QUOTATION: 'issue_quotation',
  CONFIRM_ORDER: 'confirm_order',
  // The deal page's main button makes the ขั้น 10 deposit notice and the ขั้น 13 invoice.
  CREATE_DEPOSIT_NOTICE: 'create_deposit_notice',
  CREATE_REMAINING_INVOICE: 'create_remaining_invoice',
  // Deposit paid, before the IR: the owning rep confirms each line's source (stock vs import).
  CHOOSE_PROCUREMENT_SOURCE: 'choose_procurement_source',
  // ขั้น 11, deposit paid: the owning rep creates the import request (IR).
  CREATE_IMPORT_REQUEST: 'create_import_request',
  RECORD_QUOTATION_OUTCOME: 'record_quotation_outcome',
  RECORD_DELIVERY: 'record_delivery',
  FOLLOW_UP: 'follow_up',
  LOG_ACTIVITY: 'log_activity',
};

const ACTION_LABEL = {
  [SALES_ACTION.SUBMIT_DIRECT_QUOTATION]: 'ส่งขออนุมัติใบเสนอราคา',
  [SALES_ACTION.AWAIT_DIRECT_APPROVAL]: 'รออนุมัติใบเสนอราคา',
  // The same words as CONFIRM_ORDER below on purpose (IA §8): to the rep it is the same real-world act — the
  // customer ordered — whichever route priced the deal.
  [SALES_ACTION.CONFIRM_ORDER_DIRECT]: 'ยืนยันคำสั่งซื้อ',
  [SALES_ACTION.CREATE_PCR]: 'สร้างคำขอราคา',
  // Generic for the worklist; the deal page names the next stage (เลื่อนไป: 2. นำเสนอสินค้า).
  [SALES_ACTION.ADVANCE_STAGE]: 'เลื่อนขั้น',
  [SALES_ACTION.REVISE_QUOTATION]: 'แก้ใบเสนอราคา',
  [SALES_ACTION.CONFIRM_ORDER]: 'ยืนยันคำสั่งซื้อ',
  [SALES_ACTION.CREATE_DEPOSIT_NOTICE]: 'สร้างใบแจ้งยอดเงินรับมัดจำ',
  [SALES_ACTION.CREATE_REMAINING_INVOICE]: 'สร้างใบแจ้งหนี้',
  [SALES_ACTION.CHOOSE_PROCUREMENT_SOURCE]: 'จัดซื้อสินค้า',
  [SALES_ACTION.CREATE_IMPORT_REQUEST]: 'ออกใบขอซื้อ (IR)',
  // "Record the quotation outcome".
  [SALES_ACTION.RECORD_QUOTATION_OUTCOME]: 'บันทึกผลใบเสนอราคา',
  // Same wording IMPORT_ACTION_LABELS.recordDelivery (importActions.js) already uses for the identical
  // real-world action.
  [SALES_ACTION.RECORD_DELIVERY]: 'บันทึกส่งมอบ',
  [SALES_ACTION.FOLLOW_UP]: 'ติดตามลูกค้า',
  [SALES_ACTION.LOG_ACTIVITY]: 'บันทึกกิจกรรม',
};

// Sort weight when two deals need DIFFERENT actions (lower = more urgent). Confirm-order,
// issue-quotation, record-quotation-outcome and record-delivery sit entirely in the rep's own
// hands, so each outranks a bare follow-up/log-activity nudge, mirroring the cascade order above.
// RECORD_QUOTATION_OUTCOME ranks just after CONFIRM_ORDER/ISSUE_QUOTATION: an unrecorded decision
// is the stall this worklist exists to surface, and it takes one click. CREATE_PCR (building
// something new) ranks below.
//
// Each direct-quotation bucket shares the rank of its pricing-request twin (confirm order = 1,
// sending a quotation onward = ISSUE_QUOTATION's 2). AWAIT_DIRECT_APPROVAL waits on ผจก.ขาย/CEO,
// not the rep, so it takes the waiting rank (5).
const ACTION_RANK = {
  [SALES_ACTION.CONFIRM_ORDER]: 1,
  [SALES_ACTION.QUOTE_NEXT_PARTY]: 1,
  [SALES_ACTION.CONFIRM_ORDER_DIRECT]: 1,
  [SALES_ACTION.CREATE_DEPOSIT_NOTICE]: 1,
  [SALES_ACTION.CREATE_REMAINING_INVOICE]: 1,
  [SALES_ACTION.CHOOSE_PROCUREMENT_SOURCE]: 1,
  [SALES_ACTION.CREATE_IMPORT_REQUEST]: 1,
  [SALES_ACTION.ISSUE_QUOTATION]: 2,
  [SALES_ACTION.SUBMIT_DIRECT_QUOTATION]: 2,
  [SALES_ACTION.RECORD_QUOTATION_OUTCOME]: 3,
  [SALES_ACTION.CREATE_PCR]: 4,
  [SALES_ACTION.SUBMIT_PRICING_REQUEST]: 4,
  [SALES_ACTION.ADVANCE_STAGE]: 4,
  [SALES_ACTION.REVISE_QUOTATION]: 4,
  [SALES_ACTION.RECORD_DELIVERY]: 5,
  [SALES_ACTION.AWAIT_DIRECT_APPROVAL]: 5,
  [SALES_ACTION.FOLLOW_UP]: 6,
  [SALES_ACTION.LOG_ACTIVITY]: 7,
};

/** Whether `deal.nextFollowUpAt` is due today or already overdue, compared in Asia/Bangkok (see
 * CLAUDE.md/memory note on the timezone-flake class of bug — a bare `new Date()` comparison would disagree
 * with the server about "today" near the UTC day boundary). */
export function followUpStatus(deal, todayIso = bangkokTodayIso()) {
  if (!deal?.nextFollowUpAt) return null;
  const followUpDate = String(deal.nextFollowUpAt).slice(0, 10);
  if (followUpDate < todayIso) return 'overdue';
  if (followUpDate === todayIso) return 'today';
  return null;
}

// LIVE_PR_STATUSES holds the NOT-live statuses (the name reads backwards). DRAFT is private to the
// rep who created it; CANCELLED/SUPERSEDED are dead ends. A request is "live" (with import/CEO
// right now, nothing for sales to click) by being OUTSIDE this set (see hasLivePr below).
//
// Of the eight live statuses, APPROVED_FOR_QUOTATION, QUOTATION_ACCEPTED and QUOTATION_ISSUED have
// their own buckets (2, 3, 4). The remaining five (SUBMITTED, IMPORT_REVIEWING,
// AWAITING_FACTORY_RESPONSE, READY_FOR_CEO_REVIEW, CEO_REVIEWING) fall through to
// follow-up/activity. COSTING_REVISION_REQUIRED, COSTING_IN_PROGRESS and MORE_INFO_REQUIRED are
// retired, so no live request carries them.
const LIVE_PR_STATUSES = new Set(['DRAFT', 'CANCELLED', 'SUPERSEDED']);

// Ticket statuses that prove a customer-facing price already went out. NOT legacy-only:
// OrderConfirmationService.confirmOrder still flips 'draft' -> 'quotation_issued' under the current
// flow (TicketRepository.markQuotationIssuedForOrderConfirmation), so this set alone cannot tell a
// pre-PCR-chain deal from a current one; bucket 1 pairs it with "no PricingRequest rows at all".
// 'document_issued' is genuinely legacy. 'closed' is absent on purpose: verifyClose sets
// lifecycle=COMPLETED alongside it, so nextSalesAction's `lifecycle !== 'ACTIVE'` guard returns
// first.
// The ticket statuses OrderConfirmationService.confirmOrder can proceed from (mirror of
// pricingRequestMeta's BRIDGEABLE_TICKET_STATUSES).
const BRIDGEABLE_TICKET_STATUSES = new Set(['draft', 'quotation_issued']);

// No deposit notice is made for these (deposit policy).
const DEPOSIT_NOT_NEEDED = new Set(['NOT_REQUIRED', 'WAIVED', 'CREDIT_CUSTOMER']);

const PAID_FURTHER = new Set(['AWAITING_FINAL_PAYMENT', 'FULLY_PAID']);

const QUOTED_STATUSES = new Set(['quotation_issued', 'document_issued']);

const PRE_SPEC_STAGES = new Set(['LEAD_APPROACH', 'PRESENTATION']);

// The quote route in S-order (DESIGNER → OWNER → BUYER). The labels are a short phrase for after a
// verb ("เสนอราคาผู้ซื้อ/ผู้รับเหมา"), matching the ขั้น 8 stage label, not the ผู้รับ chip labels.
export const PARTY_LABEL = { DESIGNER: 'ผู้ออกแบบ', OWNER: 'เจ้าของโครงการ', BUYER: 'ผู้ซื้อ/ผู้รับเหมา' };
// A request's quotation "went out": sent at least once and not awaiting a revision. A REJECTED or
// EXPIRED one counts — that party said no.
const SENT_DOC_STATUSES = new Set(['ISSUED', 'SUPERSEDED', 'REJECTED', 'EXPIRED']);
const DECLINED_DOC_STATUSES = new Set(['REJECTED', 'EXPIRED']);
const isDesignerOrOwner = (pr) => pr.recipientType === 'DESIGNER' || pr.recipientType === 'OWNER';
// Route order, then the newer request: the last one is the furthest along the route.
const byRoute = (a, b) => PARTY_ROUTE.indexOf(a.recipientType) - PARTY_ROUTE.indexOf(b.recipientType) || a.id - b.id;

/** "เสนอใบราคา<party>": writes the request's new-engine quotation, or opens its open DRAFT (`to`). */
function issueQuotationAction(pr, quotation) {
  return {
    key: SALES_ACTION.ISSUE_QUOTATION,
    label: `เสนอใบราคา${PARTY_LABEL[pr.recipientType] ?? ''}`,
    pricingRequestId: pr.id,
    ...(quotation?.docStatus === 'DRAFT' ? { to: `/quotations/${quotation.id}` } : {}),
  };
}

/** A quotation to redo: its redo draft is open, the customer asked for changes, or the buyer said
 * no or let it expire (DealQuotationService#createFromPricingRequest re-quotes those). A designer
 * or owner who said no is not quoted again: the route moved on. */
function isRedo(pr, quotation) {
  return quotation?.docStatus === 'DRAFT' || quotation?.docStatus === 'REVISION_REQUESTED'
    || (DECLINED_DOC_STATUSES.has(quotation?.docStatus) && !isDesignerOrOwner(pr));
}

/** The quotation awaiting ผจก.ขาย/CEO on one of the deal's live requests (its first one, or a
 * revision). A cancelled request keeps its quotation PENDING_APPROVAL on the server; that one is
 * not the deal's to wait on or approve — nor is any once a request is accepted (the deal's order). */
export function pendingRequestQuotation(deal, pricingRequests, quotationByRequest) {
  const ownPrs = pricingRequests.filter((pr) => pr.ticketId === deal.id);
  if (ownPrs.some((pr) => pr.status === 'QUOTATION_ACCEPTED')) return undefined;
  return ownPrs
    .filter((pr) => !DEAD_PRICING_REQUEST_STATUSES.has(pr.status))
    .map((pr) => quotationByRequest?.get(pr.id))
    .find((quotation) => quotation?.docStatus === 'PENDING_APPROVAL');
}

/** The sent designer/owner quotation whose outcome is still open (ISSUED, or QUOTATION_ISSUED with
 * no row), furthest along the route, offered as บันทึกผลใบเสนอราคา on its request page beside
 * whatever the primary is. Null otherwise. */
export function outcomeSecondaryAction(deal, pricingRequests, quotationByRequest) {
  const open = pricingRequests
    .filter((pr) => pr.ticketId === deal.id && pr.status === 'QUOTATION_ISSUED' && isDesignerOrOwner(pr)
      && (quotationByRequest?.get(pr.id) == null || quotationByRequest.get(pr.id).docStatus === 'ISSUED'))
    .sort(byRoute)
    .at(-1);
  return open
    ? { key: SALES_ACTION.RECORD_QUOTATION_OUTCOME, label: ACTION_LABEL[SALES_ACTION.RECORD_QUOTATION_OUTCOME], pricingRequestId: open.id }
    : null;
}

/** The on-route parties after `party`, in route order. */
const partiesAfter = (party, offRouteParties) =>
  PARTY_ROUTE.slice(PARTY_ROUTE.indexOf(party) + 1).filter((next) => !offRouteParties.has(next));

/** The "เสนอราคา<next party>" route choice, shared by the pricing-request and the direct-quotation paths. */
const quoteNextPartyAction = (anchorParty, parties, extra) => ({
  key: SALES_ACTION.QUOTE_NEXT_PARTY,
  label: `เสนอราคา${PARTY_LABEL[parties[0]]}`,
  parties,
  anchorParty,
  ...extra,
});

/**
 * Once a designer/owner quotation is sent, the rep picks the next party. The anchor is the request
 * furthest along the route whose quotation went out (with no row for it, QUOTATION_ISSUED alone
 * decides). The later on-route parties are offered when none of them has a live request holding
 * the choice back (a later party that said no is the anchor itself); a later party's private
 * DRAFT is sent instead. Null otherwise, and while the route is unknown.
 */
function routeChoiceAction(ownPrs, quotationOf, offRouteParties) {
  const anchor = ownPrs
    .filter((pr) => pr.status === 'QUOTATION_ISSUED' && PARTY_ROUTE.includes(pr.recipientType)
      && (quotationOf(pr) == null || SENT_DOC_STATUSES.has(quotationOf(pr).docStatus)))
    .sort(byRoute)
    .at(-1);
  if (!anchor || !isDesignerOrOwner(anchor) || offRouteParties == null) return null;
  const parties = partiesAfter(anchor.recipientType, offRouteParties);
  if (parties.length === 0) return null;
  const holding = ownPrs.filter((pr) => parties.includes(pr.recipientType) && !DEAD_PRICING_REQUEST_STATUSES.has(pr.status));
  if (holding.length > 0) {
    const draft = parties.map((party) => holding.find((pr) => pr.recipientType === party && pr.status === 'DRAFT')).find(Boolean);
    return draft
      ? { key: SALES_ACTION.SUBMIT_PRICING_REQUEST, label: `ส่งคำขอราคาถึง${PARTY_LABEL[draft.recipientType]}`, pricingRequestId: draft.id }
      : null;
  }
  const anchorQuotation = quotationOf(anchor);
  // An OWNER may buy directly: "no contractor" is then a choice, via the owner's outcome (open while ISSUED).
  const skipBuyer = anchor.recipientType === 'OWNER' && (anchorQuotation == null || anchorQuotation.docStatus === 'ISSUED');
  return quoteNextPartyAction(anchor.recipientType, parties, { anchorDeclined: DECLINED_DOC_STATUSES.has(anchorQuotation?.docStatus), skipBuyer });
}

/**
 * The one next action `deal` needs from its owning sales rep right now, or null if nothing in the
 * cascade applies (e.g. the request is with import/CEO and the deal isn't due for a follow-up).
 *
 * `pricingRequests` is the rep's OWN queue (already scoped server-side), filtered here to this
 * ticket.
 *
 * `offRouteParties`: a Set of the parties the deal's served route skips (empty = all on it); absent
 * while the route is unknown. `quotationByRequest`: a Map, pricingRequestId -> the newest
 * PRICING_REQUEST-origin quotation `{ id, number, docStatus }`; only the deal page has it.
 */
export function nextSalesAction(deal, pricingRequests = [], { reviseTarget = null, offRouteParties = null, quotationByRequest = null, depositNotice = null, remainingInvoice, importRequests, items, procurementSource } = {}) {
  if (!deal || deal.lifecycle !== 'ACTIVE') return null;

  // 0. A LIVE direct quotation. Checked BEFORE bucket 1 on purpose: a deal whose price is written
  //    by hand on a DEAL_DIRECT quotation must never be told "สร้างคำขอราคา", which would open the
  //    mixed-origin case the server refuses. Keyed only on TicketSummaryDto.liveDirectQuotation (the
  //    newest live DEAL_DIRECT row, server-picked), so this stays a list-row-only computation; a
  //    non-live status is ignored.
  const directAction = directQuoteNextPartyAction(deal, offRouteParties) ?? liveDirectQuotationAction(deal.liveDirectQuotation);
  // confirmOrderFromDirectQuotation 409s unless the deal is still 'draft'; a deal already past it
  // is never offered that confirm and falls through to the ordinary cascade. A list row always
  // carries `status`.
  const confirmRefused = directAction?.key === SALES_ACTION.CONFIRM_ORDER_DIRECT
    && (deal.status ?? 'draft') !== 'draft';
  if (directAction && !confirmRefused) return directAction;
  // An APPROVED direct quotation the confirm bucket refuses (deal already past 'draft') is revised
  // rather than left to fall through to CREATE_PCR. A confirmed flow-A deal keeps its direct
  // quotation APPROVED + live, so the deposit notice must outrank this revise.
  const depositAction = depositNoticeAction(deal, depositNotice);
  if (confirmRefused && reviseTarget) return depositAction ?? importRequestAction(deal, importRequests, items, procurementSource) ?? reviseAction(reviseTarget);

  // ขั้น 1 → 2 → 3 are moved by hand and the price request starts at ขั้น 3, so on ขั้น 1–2 the one
  // action is moving the stage; nothing below takes it over.
  if (PRE_SPEC_STAGES.has(deal.salesStage)) {
    return { key: SALES_ACTION.ADVANCE_STAGE, label: ACTION_LABEL[SALES_ACTION.ADVANCE_STAGE] };
  }

  const ownPrs = pricingRequests.filter((pr) => pr.ticketId === deal.id);

  // 1. No pricing request has ever been SUBMITTED for this deal (none exist, or every one is still
  //    a private DRAFT / dead). A new deal's legacy `status` freezes at 'draft' under the current
  //    flow, so PR existence (not ticket.status) is the reliable signal (see TicketListPage's
  //    DealStageCell).
  //
  //    Deals created before the PricingRequest chain (legacy/demo deals) have zero pricing requests
  //    forever, so this bucket would park even a PROCUREMENT-stage deal with an issued quotation,
  //    paid deposit and issued import request on "สร้างคำขอราคา". `pricedOutsidePcrChain` below is
  //    the guard, and BOTH limbs matter:
  //
  //    - `ownPrs.length === 0`: the deal never entered the chain. Not the same as `!hasLivePr`: a
  //      customer-change revision leaves {parent SUPERSEDED, child DRAFT}, which is "no LIVE
  //      request" but still in the chain; its DRAFT is sent below, not a second request created.
  //    - evidence a customer-facing price went out: a quoted status, or any paymentStatus (its
  //      amount-payable precondition means a price exists).
  //
  //    Together those are true only of a pre-chain deal. confirmOrder sets 'quotation_issued' and
  //    confirmCustomer sets paymentStatus under the CURRENT flow too, so the price evidence alone
  //    would strand a revision's rep.
  //
  //    NOT gated on salesStage or fulfillmentStatus: a rep may manually set ORDER_RECEIVED, and
  //    TicketService.reserveStock sets fulfillmentStatus FROM_STOCK (and auto-advances the stage)
  //    with no pricing precondition. Either would suppress this bucket on a never-priced deal,
  //    leaving no CTA and a "รอฝ่ายขาย" banner naming the rep. Legacy mid-flight statuses
  //    (submitted/in_review/price_proposed/approved) are excluded for the same reason: that engine
  //    is retired, so those deals DO still need a pricing request.
  const hasLivePr = ownPrs.some((pr) => !LIVE_PR_STATUSES.has(pr.status));
  if (!hasLivePr) {
    const pricedOutsidePcrChain = ownPrs.length === 0
      && (QUOTED_STATUSES.has(deal.status) || deal.paymentStatus != null);
    if (!pricedOutsidePcrChain) {
      // Several: the customer-change revision's draft (it has a parent) first, then the newest.
      const draft = ownPrs.filter((pr) => pr.status === 'DRAFT')
        .sort((a, b) => (b.parentPricingRequestId != null) - (a.parentPricingRequestId != null) || b.id - a.id)[0];
      if (draft) {
        return { key: SALES_ACTION.SUBMIT_PRICING_REQUEST, label: `ส่งคำขอราคาถึง${PARTY_LABEL[draft.recipientType]}`, pricingRequestId: draft.id };
      }
      return { key: SALES_ACTION.CREATE_PCR, label: ACTION_LABEL[SALES_ACTION.CREATE_PCR] };
    }
  }

  // The newest PRICING_REQUEST-origin quotation the deal page read for a request. Undefined on the
  // dashboard and for a request with none (legacy quotation, list not loaded): the request status
  // alone then decides.
  const quotationOf = (pr) => quotationByRequest?.get(pr.id);

  // An acceptance is the order (bucket 3), so nothing is quoted after it — no later request's
  // quotation, route choice or redo once any request is accepted (spec B1).
  const accepted = ownPrs.some((pr) => pr.status === 'QUOTATION_ACCEPTED');

  // 2. A price is approved and ready to quote: canCreateCustomerQuotation's gate
  //    (pricingRequestMeta.js) is exactly pr.status === 'APPROVED_FOR_QUOTATION'. "เสนอใบราคา<party>"
  //    writes the quotation from that request; a DRAFT already written is opened instead (`to`),
  //    and one awaiting approval waits (below).
  const approved = !accepted && ownPrs.find((pr) => pr.status === 'APPROVED_FOR_QUOTATION'
    && quotationOf(pr)?.docStatus !== 'PENDING_APPROVAL');
  if (approved) return issueQuotationAction(approved, quotationOf(approved));

  // 3. The customer accepted but the order isn't confirmed: canConfirmOrder's gate is
  //    pr.status === 'QUOTATION_ACCEPTED' && !orderConfirmedAt. Also gated on the ticket status
  //    being one the order-confirm bridge can advance (confirmOrderBlockedReason /
  //    OrderConfirmationService.isBridgeableTicketStatus), else the CTA can only 409. Whoever
  //    accepted: an acceptance is the order.
  if (ownPrs.some((pr) => pr.status === 'QUOTATION_ACCEPTED' && !pr.orderConfirmedAt)
      && (deal.status == null || BRIDGEABLE_TICKET_STATUSES.has(deal.status))) {
    return { key: SALES_ACTION.CONFIRM_ORDER, label: ACTION_LABEL[SALES_ACTION.CONFIRM_ORDER] };
  }

  // ขั้น 10, the customer confirmed the order -> make the deposit notice.
  if (depositAction) return depositAction;

  // ขั้น 11, deposit paid and nothing ordered yet -> create the IR.
  const importAction = importRequestAction(deal, importRequests, items, procurementSource);
  if (importAction) return importAction;

  // A live request's quotation awaiting ผจก.ขาย/CEO: the rep waits.
  const pending = pendingRequestQuotation(deal, ownPrs, quotationByRequest);
  if (pending) {
    return {
      key: SALES_ACTION.AWAIT_DIRECT_APPROVAL,
      label: ACTION_LABEL[SALES_ACTION.AWAIT_DIRECT_APPROVAL],
      quotationId: pending.id,
      quotationNumber: pending.number,
    };
  }

  // A designer/owner quotation went out: pick the next party (see routeChoiceAction).
  const routeAction = accepted ? null : routeChoiceAction(ownPrs, quotationOf, offRouteParties);
  if (routeAction) return routeAction;

  // 4. The quotation went out to the customer but nobody has recorded what the customer said. The
  //    BUYER's (or a legacy/unknown recipient's): a designer/owner outcome is never prompted, their
  //    quotation leads to the route choice above. canRecordCustomerQuotationOutcome needs the
  //    quotation ISSUED: the deal page's row says so; without one, QUOTATION_ISSUED is the proxy.
  //
  //    AFTER bucket 3 (CONFIRM_ORDER): a customer-change revision can leave one sibling PR at
  //    QUOTATION_ISSUED (a stale, superseded round) while another reaches QUOTATION_ACCEPTED, and
  //    CONFIRM_ORDER must win that race.
  //    `pricingRequestId`: the outcome controls (both quotation engines) live on that request's page.
  const issued = ownPrs.find((pr) => pr.status === 'QUOTATION_ISSUED' && !isDesignerOrOwner(pr)
    && (quotationOf(pr) == null || quotationOf(pr).docStatus === 'ISSUED'));
  if (issued) {
    return {
      key: SALES_ACTION.RECORD_QUOTATION_OUTCOME,
      label: ACTION_LABEL[SALES_ACTION.RECORD_QUOTATION_OUTCOME],
      pricingRequestId: issued.id,
    };
  }

  // A quotation to redo (see isRedo) is written again for the same party: "เสนอใบราคา<party>", as in
  // bucket 2. After bucket 4, so a live quotation's outcome comes first; the request furthest along
  // the route wins, so an earlier party's stale redo draft never takes over a later party's.
  const redo = accepted ? null
    : ownPrs.filter((pr) => pr.status === 'QUOTATION_ISSUED' && isRedo(pr, quotationOf(pr))).sort(byRoute).at(-1);
  if (redo) return issueQuotationAction(redo, quotationOf(redo));

  // 5. The deal is delivery-ready (goods received / from stock / mid-delivery) and nothing upstream
  //    (buckets 1-4) is still open. Reuses nextFulfilmentActionCode (importActions.js), the same
  //    decision DealFulfilmentPanel's `can.recordDelivery`/`can.completeDelivery` gates use.
  //
  //    Stages 13-14 (ส่งมอบสินค้า) are sales's, additive to import/CEO:
  //    TicketService.canWriteDelivery = FULFILMENT_ROLES ∪ (SALES_ROLES ∧ deal owner).
  //    ImportOverview no longer prompts for it, but import/CEO still get the real button on
  //    DealFulfilmentPanel.
  //
  //    A delivery-ready deal that ALSO matches bucket 1's guard (zero pricing requests AND no
  //    price evidence) still returns CREATE_PCR above and never reaches here. That is correct: the
  //    only realistic way to get a delivery-ready fulfillmentStatus with no pricing evidence is
  //    TicketService.reserveStock's FROM_STOCK auto-advance, i.e. a deal never priced, which bucket
  //    1 exists to catch. Pinned by the delivery-vs-bucket-1 case in salesActions.test.js and
  //    workState.test.js's 'still offers create_pcr after ... FROM_STOCK'.
  // ขั้น 13: the invoice is made at นัดส่งสินค้า, before บันทึกส่งมอบ, until it is issued.
  // `remainingInvoice` undefined = the caller (dashboard worklist) doesn't know -> never offered.
  if (remainingInvoice !== undefined && isRemainingInvoiceReady(deal)
      && (remainingInvoice === null || remainingInvoice.status === 'DRAFT')) {
    return {
      key: SALES_ACTION.CREATE_REMAINING_INVOICE,
      label: remainingInvoice ? 'ออกใบแจ้งหนี้' : ACTION_LABEL[SALES_ACTION.CREATE_REMAINING_INVOICE],
    };
  }

  if (nextFulfilmentActionCode(deal) === 'recordDelivery') {
    return { key: SALES_ACTION.RECORD_DELIVERY, label: ACTION_LABEL[SALES_ACTION.RECORD_DELIVERY] };
  }

  // 6. Follow-up due today or overdue.
  const followUp = followUpStatus(deal);
  if (followUp) {
    return { key: SALES_ACTION.FOLLOW_UP, label: ACTION_LABEL[SALES_ACTION.FOLLOW_UP], followUp };
  }

  // 7. No activity logged in STALE_ACTIVITY_DAYS days — `deal.stale` is already computed server/mock-side
  // (mirrors TicketRepository.enrichSummary, see dealTrackingMeta.js's computeStale) and included on every
  // api.tickets.list() row, so it is reused here rather than recomputed.
  if (deal.stale) {
    return { key: SALES_ACTION.LOG_ACTIVITY, label: ACTION_LABEL[SALES_ACTION.LOG_ACTIVITY] };
  }

  return null;
}

/** Gated on paymentStatus only: a direct-quotation deal has no pricing request. */
function depositNoticeAction(deal, depositNotice) {
  if (deal.paymentStatus !== 'CUSTOMER_CONFIRMED' || DEPOSIT_NOT_NEEDED.has(deal.depositPolicy)) return null;
  return {
    key: SALES_ACTION.CREATE_DEPOSIT_NOTICE,
    label: depositNotice?.status === 'DRAFT' ? 'ออกใบแจ้งยอดเงินรับมัดจำ' : ACTION_LABEL[SALES_ACTION.CREATE_DEPOSIT_NOTICE],
  };
}

/** `importRequests` undefined = unknown (still the create label). A DRAFT row -> the issue label. */
function importRequestAction(deal, importRequests, items, procurementSource) {
  // Deposit settled (a no-deposit deal stays at CUSTOMER_CONFIRMED and may issue there), but not the paid-further
  // states: by then the IR is long past (same behaviour as before). `items` is the page's own line list (the deal
  // summary has none); a non-empty list fully covered from stock orders nothing from a factory.
  const depositReady = depositSettled(deal) && !PAID_FURTHER.has(deal.paymentStatus);
  const allFromStock = (items?.length ?? 0) > 0 && items.every((i) => Number(i.qtyFromStock ?? 0) >= Number(i.qty ?? 0));
  if (!depositReady || allFromStock || deal.fulfillmentStatus != null) return null;
  if (procurementSource?.pending === true) {
    return { key: SALES_ACTION.CHOOSE_PROCUREMENT_SOURCE, label: ACTION_LABEL[SALES_ACTION.CHOOSE_PROCUREMENT_SOURCE] };
  }
  return {
    key: SALES_ACTION.CREATE_IMPORT_REQUEST,
    label: importRequests?.some((r) => r.status === 'DRAFT') ? 'ออกเลขใบขอซื้อ (IR)' : ACTION_LABEL[SALES_ACTION.CREATE_IMPORT_REQUEST],
  };
}

export const REVISE_STAGES = new Set(['QUOTE_OWNER', 'QUOTE_BUYER']);

/** `target` = { quotationId, number, openDraftId? }. With an open revision draft the action points at it (`to`)
 * instead of minting a second one. */
export function reviseAction(target) {
  const action = {
    key: SALES_ACTION.REVISE_QUOTATION,
    label: ACTION_LABEL[SALES_ACTION.REVISE_QUOTATION],
    quotationId: target.quotationId,
    quotationNumber: target.number,
  };
  if (target.openDraftId != null) {
    action.label = 'ไปที่ฉบับแก้ไข';
    action.to = `/quotations/${target.openDraftId}`;
  }
  return action;
}

const DIRECT_QUOTATION_BUCKET = {
  DRAFT: SALES_ACTION.SUBMIT_DIRECT_QUOTATION,
  PENDING_APPROVAL: SALES_ACTION.AWAIT_DIRECT_APPROVAL,
  APPROVED: SALES_ACTION.CONFIRM_ORDER_DIRECT,
};

/** The bucket a live direct quotation puts its deal in, or null. Every action names the quotation
 * (`quotationId`/`quotationNumber`) so the caller can route to it or name it in a banner; SUBMIT also carries
 * `to`, because its real control (ส่งขออนุมัติ) lives on the quotation editor. */
function liveDirectQuotationAction(live) {
  const key = live ? DIRECT_QUOTATION_BUCKET[live.docStatus] : null;
  if (!key) return null;
  const action = { key, label: ACTION_LABEL[key], quotationId: live.id, quotationNumber: live.number };
  if (key === SALES_ACTION.SUBMIT_DIRECT_QUOTATION) action.to = `/quotations/${live.id}`;
  return action;
}

/** An APPROVED designer/owner direct quotation on a 'draft' deal with a later on-route party: the same route
 * choice as routeChoiceAction, flagged `direct` (ยืนยันคำสั่งซื้อ stays a secondary action). Null otherwise. */
function directQuoteNextPartyAction(deal, offRouteParties) {
  const live = deal.liveDirectQuotation;
  if (live?.docStatus !== 'APPROVED' || (deal.status ?? 'draft') !== 'draft' || offRouteParties == null
    || (live.recipientType !== 'DESIGNER' && live.recipientType !== 'OWNER')) return null;
  const parties = partiesAfter(live.recipientType, offRouteParties);
  if (parties.length === 0) return null;
  return quoteNextPartyAction(live.recipientType, parties, { anchorDeclined: false, direct: true, skipBuyer: live.recipientType === 'OWNER', quotationId: live.id, quotationNumber: live.number });
}

/** Sorts `{ deal, action }` worklist rows overdue-first: an overdue follow-up always leads regardless of what
 * other deals' actions are, then rows are grouped by ACTION_RANK, then (within the same action) the
 * longest-waiting deal (oldest stageUpdatedAt) sorts first. Does not mutate `items`. */
export function sortWorklist(items) {
  return [...items].sort((a, b) => {
    const overdueA = a.action.followUp === 'overdue' ? 0 : 1;
    const overdueB = b.action.followUp === 'overdue' ? 0 : 1;
    if (overdueA !== overdueB) return overdueA - overdueB;

    const rankDiff = ACTION_RANK[a.action.key] - ACTION_RANK[b.action.key];
    if (rankDiff !== 0) return rankDiff;

    const dateA = new Date(a.deal.stageUpdatedAt ?? a.deal.updatedAt ?? 0).getTime();
    const dateB = new Date(b.deal.stageUpdatedAt ?? b.deal.updatedAt ?? 0).getTime();
    return dateA - dateB;
  });
}
