// Sales Overview (role-scoped views, Sales branch): "what does MY deal need
// from ME right now" — a single next-action CTA per deal, used by the
// "สิ่งที่ต้องทำ" worklist on SalesOverview.jsx.
//
// Presentation only, same convention as salesViewScope.js's dealInScope /
// TicketListPage's worklistReason: never a security boundary, and never
// authoritative over what the real service will actually allow (the button
// this CTA points at re-checks everything server-side). Built entirely from
// data the caller already has — a ticket-list row (`deal`, as returned by
// api.tickets.list) plus the sales rep's own pricing-request queue
// (`pricingRequests`, as returned by api.pricingRequests.queue) — so this
// never triggers a per-ticket detail fetch.
//
// Slice 2 put three direct-quotation buckets IN FRONT of all of these (see
// "bucket 0" inside nextSalesAction): a live DEAL_DIRECT quotation owns the
// deal's CTA outright.
//
// The CTA buckets below are a priority cascade, evaluated in pipeline
// order (earliest-unblocked-step wins): a deal with no live pricing request
// normally needs "สร้างคำขอราคา" first, even if it also happens to be overdue
// on follow-up — there is nothing to follow up ABOUT yet. Once a deal has a
// live pricing request past that point, later buckets take over. Exception:
// a deal that was already priced OUTSIDE the PricingRequest chain (a legacy
// deal whose customer quotation went out through the retired ticket-level
// engine) skips bucket 1 even with zero pricing requests — see bucket 1's own
// comment below for why, and for why the guard is narrower than it first
// looks.
//
// Bucket 4 (RECORD_QUOTATION_OUTCOME) is a discoverability fix, not a new
// capability: the accept/reject/revision controls already existed on
// DealQuotationPanel's "ราคาและใบเสนอราคา" section (the "เอกสาร" tab), but a PR
// sitting at QUOTATION_ISSUED used to fall all the way through this cascade
// to follow-up/log-activity — the top sticky CTA never told the owning rep
// the customer's decision was waiting to be recorded, so an issued quotation
// could sit un-accepted indefinitely with nothing on the page pointing at the
// controls that would advance it. Placed AFTER bucket 3 (CONFIRM_ORDER) so an
// already-accepted PR still prioritises confirming the order. See its own
// comment below.
//
// Bucket 5 (RECORD_DELIVERY — stages 13-14 ส่งมอบสินค้า handed to sales, owner
// ruling 2026-08-17) reuses importActions.js's nextFulfilmentActionCode
// rather than keeping a second copy of the delivery-ready status list — see
// that module's own header for the "shared with sales" note. It sits AFTER
// bucket 1, not before: a delivery-ready deal that also matches bucket 1's
// guard (zero pricing requests, no evidence a price ever went out) still gets
// CREATE_PCR, because bucket 1's early `return` fires first — same
// earliest-unblocked-step cascade principle as everything else here, not a
// special case carved out for delivery. See bucket 5's own comment below for
// why that interaction was checked, on purpose, and left alone.

import { bangkokTodayIso } from '../../utils/format.js';
import { DEAD_PRICING_REQUEST_STATUSES, QUOTATION_RECIPIENT_OPTIONS } from '../quotations/quotationMeta.js';
import { nextFulfilmentActionCode } from './importActions.js';

export const SALES_ACTION = {
  // Slice 2 — flow A (SLICE-2-FLOW-A.md §E): the three buckets a LIVE direct quotation drives.
  // AWAIT_DIRECT_APPROVAL also covers a PRICING_REQUEST-origin quotation awaiting approval.
  SUBMIT_DIRECT_QUOTATION: 'submit_direct_quotation',
  AWAIT_DIRECT_APPROVAL: 'await_direct_approval',
  CONFIRM_ORDER_DIRECT: 'confirm_order_direct',
  // PR A: แก้ใบเสนอราคา — revise (or open the open revision of) an approved/issued quotation. Only
  // ever produced when the caller hands nextSalesAction a `reviseTarget`.
  REVISE_QUOTATION: 'revise_quotation',
  ADVANCE_STAGE: 'advance_stage',
  CREATE_PCR: 'create_pcr',
  // Owner ruling 2026-10-27: once a designer/owner quotation is sent, เสนอราคา to a later party on
  // the route — or, when that party's request is still a private DRAFT, send it to import.
  QUOTE_NEXT_PARTY: 'quote_next_party',
  SUBMIT_PRICING_REQUEST: 'submit_pricing_request',
  ISSUE_QUOTATION: 'issue_quotation',
  CONFIRM_ORDER: 'confirm_order',
  RECORD_QUOTATION_OUTCOME: 'record_quotation_outcome',
  RECORD_DELIVERY: 'record_delivery',
  FOLLOW_UP: 'follow_up',
  LOG_ACTIVITY: 'log_activity',
};

const ACTION_LABEL = {
  [SALES_ACTION.SUBMIT_DIRECT_QUOTATION]: 'ส่งขออนุมัติใบเสนอราคา',
  [SALES_ACTION.AWAIT_DIRECT_APPROVAL]: 'รออนุมัติใบเสนอราคา',
  // The same words as CONFIRM_ORDER below on purpose (IA §8): to the rep it is the same real-world
  // act — the customer ordered — whichever route priced the deal.
  [SALES_ACTION.CONFIRM_ORDER_DIRECT]: 'ยืนยันคำสั่งซื้อ',
  [SALES_ACTION.CREATE_PCR]: 'สร้างคำขอราคา',
  // Generic for the worklist; the deal page names the next stage (เลื่อนไป: 2. นำเสนอสินค้า).
  [SALES_ACTION.ADVANCE_STAGE]: 'เลื่อนขั้น',
  [SALES_ACTION.REVISE_QUOTATION]: 'แก้ใบเสนอราคา',
  [SALES_ACTION.CONFIRM_ORDER]: 'ยืนยันคำสั่งซื้อ',
  // "Record the quotation outcome" — deliberately not "ติดตามผล"/"ติดตามลูกค้า" (that's
  // FOLLOW_UP's own label, a nudge to go chase the customer): by the time this bucket fires, the
  // quotation is already out and canRecordCustomerQuotationOutcome's own gate is what the button
  // opens, so the verb is the recording action itself, matching CREATE_PCR/ISSUE_QUOTATION/
  // CONFIRM_ORDER's terse imperative-verb tone (2-4 words, no subject, no punctuation).
  [SALES_ACTION.RECORD_QUOTATION_OUTCOME]: 'บันทึกผลใบเสนอราคา',
  // Same wording IMPORT_ACTION_LABELS.recordDelivery (importActions.js) already uses for the
  // identical real-world action. Deliberately NOT imported from there: the two labels live in two
  // independent cascades (this module's SALES_ACTION vs. import's fulfilment codes), and this
  // module has no other reason to depend on IMPORT_ACTION_LABELS — see nextFulfilmentActionCode's
  // own import below for the one piece of import's module this file DOES intentionally share.
  [SALES_ACTION.RECORD_DELIVERY]: 'บันทึกส่งมอบ',
  [SALES_ACTION.FOLLOW_UP]: 'ติดตามลูกค้า',
  [SALES_ACTION.LOG_ACTIVITY]: 'บันทึกกิจกรรม',
};

// Sort weight when two deals need DIFFERENT actions (lower = more urgent). A
// pending confirm-order/issue-quotation/record-quotation-outcome/record-delivery is a task sitting
// entirely in the rep's own hands with no external dependency, so each
// outranks a bare follow-up/log-activity nudge — mirrors the cascade order
// above. RECORD_QUOTATION_OUTCOME ranks alongside that group (just after
// CONFIRM_ORDER/ISSUE_QUOTATION): a decision sitting unrecorded is exactly
// the kind of stall this whole worklist exists to surface, and recording it
// takes one click once the rep knows the answer — no less urgent than the
// other "just do it" buckets, and well ahead of CREATE_PCR (which requires
// building something new) or a bare nudge.
//
// Slice 2: each direct-quotation bucket shares the rank of its pricing-request twin — confirming an
// order is confirming an order (1), sending a quotation onward is ISSUE_QUOTATION's weight (2) — and
// AWAIT_DIRECT_APPROVAL, which is waiting on ผจก.ขาย/CEO rather than on the rep, takes the waiting
// rank (5): visible in the worklist, never ahead of something the rep can actually do.
const ACTION_RANK = {
  [SALES_ACTION.CONFIRM_ORDER]: 1,
  [SALES_ACTION.QUOTE_NEXT_PARTY]: 1,
  [SALES_ACTION.CONFIRM_ORDER_DIRECT]: 1,
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

/**
 * Whether `deal.nextFollowUpAt` is due today or already overdue, compared in
 * Asia/Bangkok (see CLAUDE.md/memory note on the timezone-flake class of bug —
 * a bare `new Date()` comparison would disagree with the server about "today"
 * near the UTC day boundary). Returns 'overdue' | 'today' | null (not due yet,
 * or no follow-up date set at all).
 */
export function followUpStatus(deal, todayIso = bangkokTodayIso()) {
  if (!deal?.nextFollowUpAt) return null;
  const followUpDate = String(deal.nextFollowUpAt).slice(0, 10);
  if (followUpDate < todayIso) return 'overdue';
  if (followUpDate === todayIso) return 'today';
  return null;
}

// LIVE_PR_STATUSES holds the NOT-live statuses — the name reads backwards from what it holds, and
// that backwards reading is why this comment used to narrate the set's CONTENTS as if they were
// the live ones. DRAFT is still private to the rep who created it; CANCELLED/SUPERSEDED are dead
// ends. A request counts as "live" — with import/CEO right now, nothing here for sales to click —
// precisely by being OUTSIDE this set (see hasLivePr below: `!LIVE_PR_STATUSES.has(pr.status)`).
//
// The eight statuses that ARE live this way split further: APPROVED_FOR_QUOTATION and
// QUOTATION_ACCEPTED have their own buckets below (2 and 3), and QUOTATION_ISSUED now does too
// (bucket 4, RECORD_QUOTATION_OUTCOME — see that bucket's own comment for why it used to fall
// through instead). The remaining five — SUBMITTED, IMPORT_REVIEWING, AWAITING_FACTORY_RESPONSE,
// READY_FOR_CEO_REVIEW, CEO_REVIEWING — fall through to follow-up/activity, same as a deal with no
// pending pricing-request action at all. COSTING_REVISION_REQUIRED does NOT belong in that list
// any more: V141 retired it, so no live request can carry it. V140 is the migration that retired
// COSTING_IN_PROGRESS and MORE_INFO_REQUIRED; the latter was the one genuine sales action in that
// old list ("answer import's question"), and with the ขอข้อมูลเพิ่มเติม round-trip retired there is
// no extra CTA bucket waiting to be built for that specific gap.
const LIVE_PR_STATUSES = new Set(['DRAFT', 'CANCELLED', 'SUPERSEDED']);

// Ticket statuses that prove a customer-facing price already went out. NOT
// legacy-only: OrderConfirmationService.confirmOrder still flips 'draft' ->
// 'quotation_issued' under the redesigned flow (TicketRepository
// .markQuotationIssuedForOrderConfirmation), so this set alone cannot tell a
// pre-PCR-chain deal from a current one — bucket 1 pairs it with "this deal
// has no PricingRequest rows at all". 'document_issued' is genuinely legacy
// (nothing writes it any more). 'closed' is deliberately absent: verifyClose
// sets lifecycle=COMPLETED alongside it, and V51 backfilled every historical
// row, so nextSalesAction's own `lifecycle !== 'ACTIVE'` guard returns first.
// The ticket statuses OrderConfirmationService.confirmOrder can proceed from (mirror of
// pricingRequestMeta's BRIDGEABLE_TICKET_STATUSES).
const BRIDGEABLE_TICKET_STATUSES = new Set(['draft', 'quotation_issued']);

const QUOTED_STATUSES = new Set(['quotation_issued', 'document_issued']);

const PRE_SPEC_STAGES = new Set(['LEAD_APPROACH', 'PRESENTATION']);

// The quote route in S-order (DESIGNER → OWNER → BUYER). The labels are a short phrase for after a
// verb ("เสนอราคาผู้ซื้อ"), not the ผู้รับ chip labels (ผู้ซื้อ / ผู้รับเหมา).
const PARTY_ROUTE = QUOTATION_RECIPIENT_OPTIONS.map((option) => option.code);
export const PARTY_LABEL = { DESIGNER: 'ผู้ออกแบบ', OWNER: 'เจ้าของโครงการ', BUYER: 'ผู้ซื้อ' };
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

/** Owner ruling 2026-10-27: a quotation to redo — its redo draft is open, the customer asked for
 * changes, or the buyer said no or let it expire (DealQuotationService#createFromPricingRequest
 * re-quotes those). A designer or owner who said no is not quoted again: the route moved on. */
function isRedo(pr, quotation) {
  return quotation?.docStatus === 'DRAFT' || quotation?.docStatus === 'REVISION_REQUESTED'
    || (DECLINED_DOC_STATUSES.has(quotation?.docStatus) && !isDesignerOrOwner(pr));
}

/** pricingRequestId -> that request's newest PRICING_REQUEST-origin quotation, from the deal's
 * quotation list (DealQuotationDto rows). The `quotationByRequest` nextSalesAction reads. */
export function newestQuotationByRequest(rows) {
  const byRequest = new Map();
  for (const q of rows) {
    if (q.origin !== 'PRICING_REQUEST' || q.pricingRequestId == null) continue;
    const held = byRequest.get(q.pricingRequestId);
    if (!held || q.id > held.id) byRequest.set(q.pricingRequestId, q);
  }
  return byRequest;
}

/** The quotation awaiting ผจก.ขาย/CEO on one of the deal's live requests (its first one, or a
 * revision). A cancelled request keeps its quotation PENDING_APPROVAL on the server; that one is
 * not the deal's to wait on or approve. */
export function pendingRequestQuotation(deal, pricingRequests, quotationByRequest) {
  return pricingRequests
    .filter((pr) => pr.ticketId === deal.id && !DEAD_PRICING_REQUEST_STATUSES.has(pr.status))
    .map((pr) => quotationByRequest?.get(pr.id))
    .find((quotation) => quotation?.docStatus === 'PENDING_APPROVAL');
}

/**
 * Owner ruling 2026-10-27: once a designer/owner quotation is sent, the rep picks the next party.
 * The anchor is the request furthest along the route whose quotation went out (with no row for it,
 * QUOTATION_ISSUED alone decides). The later on-route parties are offered when none of them has a
 * live request holding the choice back (a later party that said no is the anchor itself); a later
 * party's private DRAFT is sent instead. Null otherwise — and while the route is unknown.
 */
function routeChoiceAction(ownPrs, quotationOf, offRouteParties) {
  const anchor = ownPrs
    .filter((pr) => pr.status === 'QUOTATION_ISSUED' && PARTY_ROUTE.includes(pr.recipientType)
      && (quotationOf(pr) == null || SENT_DOC_STATUSES.has(quotationOf(pr).docStatus)))
    .sort(byRoute)
    .at(-1);
  if (!anchor || !isDesignerOrOwner(anchor) || offRouteParties == null) return null;
  const parties = PARTY_ROUTE.slice(PARTY_ROUTE.indexOf(anchor.recipientType) + 1)
    .filter((party) => !offRouteParties.has(party));
  if (parties.length === 0) return null;
  const holding = ownPrs.filter((pr) => parties.includes(pr.recipientType) && !DEAD_PRICING_REQUEST_STATUSES.has(pr.status));
  if (holding.length > 0) {
    const draft = parties.map((party) => holding.find((pr) => pr.recipientType === party && pr.status === 'DRAFT')).find(Boolean);
    return draft
      ? { key: SALES_ACTION.SUBMIT_PRICING_REQUEST, label: `ส่งคำขอราคาถึง${PARTY_LABEL[draft.recipientType]}`, pricingRequestId: draft.id }
      : null;
  }
  const anchorQuotation = quotationOf(anchor);
  return {
    key: SALES_ACTION.QUOTE_NEXT_PARTY,
    label: `เสนอราคา${PARTY_LABEL[parties[0]]}`,
    parties,
    anchorParty: anchor.recipientType,
    anchorDeclined: DECLINED_DOC_STATUSES.has(anchorQuotation?.docStatus),
    // Its outcome is still open (ISSUED, or no row): the page offers to record it in the ⋯ menu.
    outcomeRequestId: anchorQuotation == null || anchorQuotation.docStatus === 'ISSUED' ? anchor.id : null,
  };
}

/**
 * The one next action `deal` needs from its owning sales rep right now, or
 * null if nothing in the cascade applies (e.g. the request is with
 * import/CEO and the deal isn't due for a follow-up or stale).
 *
 * `pricingRequests` is the rep's OWN pricing-request queue (already scoped
 * server-side, see api.pricingRequests.queue) — filtered here to the ones
 * belonging to this ticket.
 *
 * `offRouteParties`: a Set of the parties the deal's served route skips (empty = all on it); absent
 * while the route is unknown. `quotationByRequest`: a Map, pricingRequestId -> the newest
 * PRICING_REQUEST-origin quotation `{ id, number, docStatus }` — only the deal page has it.
 */
export function nextSalesAction(deal, pricingRequests = [], { reviseTarget = null, offRouteParties = null, quotationByRequest = null } = {}) {
  if (!deal || deal.lifecycle !== 'ACTIVE') return null;

  // 0. Slice 2 — flow A (SLICE-2-FLOW-A.md §E, IA §4): a LIVE direct quotation. Checked BEFORE
  //    bucket 1 on purpose: a deal whose price is being written by hand on a DEAL_DIRECT quotation
  //    must never be told "สร้างคำขอราคา" — that would open the mixed-origin case the server refuses
  //    (IA §7, pricing-request refusal kept while a live direct quotation exists). Keyed only on
  //    TicketSummaryDto.liveDirectQuotation (S2-B4 — the newest live DEAL_DIRECT row, server-picked),
  //    so this stays a list-row-only computation like the rest of this module; a non-live status is
  //    ignored even if one ever arrives.
  const directAction = liveDirectQuotationAction(deal.liveDirectQuotation);
  // confirmOrderFromDirectQuotation (slice 1) 409s unless the deal is still 'draft'; a deal already
  // past it (a legacy deal whose order is already under way) is never offered that confirm — it
  // falls through to the ordinary cascade below instead. A list row always carries `status`.
  const confirmRefused = directAction?.key === SALES_ACTION.CONFIRM_ORDER_DIRECT
    && (deal.status ?? 'draft') !== 'draft';
  if (directAction && !confirmRefused) return directAction;
  // PR A: an APPROVED direct quotation the confirm bucket refuses (deal already past 'draft') is
  // revised rather than left to fall through to CREATE_PCR.
  if (confirmRefused && reviseTarget) return reviseAction(reviseTarget);

  // Owner ruling 2026-10-15: ขั้น 1 → 2 → 3 are moved by hand, and the price request starts at ขั้น 3.
  // So on ขั้น 1–2 the one action is moving the stage — nothing below (a due follow-up, an older
  // price request) takes it over.
  if (PRE_SPEC_STAGES.has(deal.salesStage)) {
    return { key: SALES_ACTION.ADVANCE_STAGE, label: ACTION_LABEL[SALES_ACTION.ADVANCE_STAGE] };
  }

  const ownPrs = pricingRequests.filter((pr) => pr.ticketId === deal.id);

  // 1. No pricing request has ever been SUBMITTED for this deal (none exist,
  //    or every one that exists is still a private DRAFT / dead) — mirrors
  //    TicketListPage's DealStageCell note: a new deal's legacy `status`
  //    freezes at 'draft' forever under the redesigned flow, so PR existence
  //    (not ticket.status) is the only reliable signal here.
  //
  //    UAT bug: deals created before the PricingRequest chain existed (every
  //    legacy/demo deal — e.g. demoData ticket 12, PR-2026-0012) have zero
  //    pricing requests forever, so this bucket parked them on "create a
  //    pricing request" permanently — even a PROCUREMENT-stage deal with a
  //    quotation already issued, deposit already paid, and the import request
  //    already issued kept offering "สร้างคำขอราคา". `pricedOutsidePcrChain`
  //    below is the guard, and BOTH of its limbs matter:
  //
  //    - `ownPrs.length === 0` — this deal never entered the chain at all.
  //      Not the same test as `!hasLivePr`: a customer-change revision leaves
  //      {parent SUPERSEDED, child DRAFT}, which is "no LIVE request" but is
  //      emphatically still in the chain, and its rep does need a CTA.
  //    - evidence a customer-facing price already went out: a quoted status,
  //      or any paymentStatus (whose own amount-payable precondition means a
  //      price exists, even though recordPayment itself checks no status).
  //
  //    Together those are true only of a pre-chain deal. Either alone is not:
  //    OrderConfirmationService.confirmOrder sets 'quotation_issued' and then
  //    confirmCustomer sets paymentStatus under the CURRENT flow too, so
  //    testing the price evidence alone would strand a revision's rep.
  //
  //    NOT gated on salesStage or fulfillmentStatus, though both look
  //    tempting: a rep may manually set ORDER_RECEIVED (allowedTargetStages
  //    does not filter `auto` stages), and TicketService.reserveStock sets
  //    fulfillmentStatus FROM_STOCK — and auto-advances the stage — with no
  //    pricing precondition at all. Either would suppress this bucket on a
  //    deal that has genuinely never been priced, leaving the rep no CTA and
  //    a "รอฝ่ายขาย" banner naming themselves: a silent dead end, strictly
  //    worse than the bug being fixed. Legacy mid-flight statuses
  //    (submitted/in_review/price_proposed/approved) are excluded for the
  //    same reason — that engine is retired, so those deals DO still need a
  //    pricing request.
  const hasLivePr = ownPrs.some((pr) => !LIVE_PR_STATUSES.has(pr.status));
  if (!hasLivePr) {
    const pricedOutsidePcrChain = ownPrs.length === 0
      && (QUOTED_STATUSES.has(deal.status) || deal.paymentStatus != null);
    if (!pricedOutsidePcrChain) {
      return { key: SALES_ACTION.CREATE_PCR, label: ACTION_LABEL[SALES_ACTION.CREATE_PCR] };
    }
  }

  // The newest PRICING_REQUEST-origin quotation the deal page read for a request. Undefined on the
  // dashboard and for a request with none (a legacy old-engine quotation, the list not loaded):
  // the request status alone then decides, as before.
  const quotationOf = (pr) => quotationByRequest?.get(pr.id);

  // 2. A price is approved and ready to quote — canCreateCustomerQuotation's
  //    own gate (pricingRequestMeta.js) is exactly pr.status === 'APPROVED_FOR_QUOTATION'.
  //    "เสนอใบราคา<party>" writes the new-engine quotation from that request (owner ruling R2); a
  //    DRAFT already written is opened instead (`to`), and one awaiting approval waits (below).
  const approved = ownPrs.find((pr) => pr.status === 'APPROVED_FOR_QUOTATION'
    && quotationOf(pr)?.docStatus !== 'PENDING_APPROVAL');
  if (approved) return issueQuotationAction(approved, quotationOf(approved));

  // 3. The customer accepted the quotation but the order isn't confirmed yet —
  //    canConfirmOrder's own gate: pr.status === 'QUOTATION_ACCEPTED' && !orderConfirmedAt.
  //    Also gated on the deal's own ticket status being one the order-confirm bridge can advance
  //    (pricingRequestMeta's confirmOrderBlockedReason / OrderConfirmationService
  //    .isBridgeableTicketStatus) — otherwise the CTA is a button that can only 409.
  //    Whoever accepted: an acceptance is the order (owner ruling 2026-10-27).
  if (ownPrs.some((pr) => pr.status === 'QUOTATION_ACCEPTED' && !pr.orderConfirmedAt)
      && (deal.status == null || BRIDGEABLE_TICKET_STATUSES.has(deal.status))) {
    return { key: SALES_ACTION.CONFIRM_ORDER, label: ACTION_LABEL[SALES_ACTION.CONFIRM_ORDER] };
  }

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
  const routeAction = routeChoiceAction(ownPrs, quotationOf, offRouteParties);
  if (routeAction) return routeAction;

  // 4. The quotation went out to the customer but nobody has recorded what the customer said yet.
  //    The BUYER's (or a legacy/unknown recipient's): a designer/owner outcome is never prompted —
  //    their quotation leads to the route choice above. canRecordCustomerQuotationOutcome needs the
  //    quotation ISSUED: the deal page's row says so; without one, QUOTATION_ISSUED is the proxy.
  //    Before this bucket existed, an issued quotation fell through to follow-up/log-activity with no
  //    prompt anywhere on the page.
  //
  //    Placed AFTER bucket 3 (CONFIRM_ORDER), not before: a customer-change revision can leave one
  //    sibling PR at QUOTATION_ISSUED (a stale, since-superseded round) while another reaches
  //    QUOTATION_ACCEPTED — CONFIRM_ORDER must win that race, same earliest-unblocked-step principle
  //    as the rest of this cascade.
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
  const redo = ownPrs.filter((pr) => pr.status === 'QUOTATION_ISSUED' && isRedo(pr, quotationOf(pr))).sort(byRoute).at(-1);
  if (redo) return issueQuotationAction(redo, quotationOf(redo));

  // 5. The deal is delivery-ready (goods received / from stock / mid-
  //    delivery) and nothing upstream of it (buckets 1-4) is still open.
  //    Reuses nextFulfilmentActionCode (importActions.js) — the SAME
  //    decision DealFulfilmentPanel's own `can.recordDelivery`/
  //    `can.completeDelivery` gates and (formerly) ImportOverview's worklist
  //    read — instead of keeping a second copy of the delivery-ready status
  //    list here. See that module's own header comment for the "shared with
  //    sales" note this bucket is the reason for.
  //
  //    Stages 13-14 (ส่งมอบสินค้า) are Sales's as of the 2026-08-17 owner
  //    ruling, ADDITIVE to import/CEO — TicketService.canWriteDelivery =
  //    FULFILMENT_ROLES ∪ (SALES_ROLES ∧ deal owner), not a transfer.
  //    ImportOverview no longer PROMPTS for it (nextImportAction now returns
  //    null on a delivery-ready deal — see that function's own comment for
  //    why), but the underlying capability is untouched: import/CEO still
  //    get the real button on DealFulfilmentPanel if they navigate there.
  //
  //    Interaction with bucket 1, checked deliberately rather than left to
  //    accident: a delivery-ready deal that ALSO matches bucket 1's guard
  //    (zero pricing requests AND no evidence a price ever went out — see
  //    bucket 1's own comment above) still returns CREATE_PCR above, never
  //    reaches here. That is correct, not a gap: the only realistic way to
  //    reach a delivery-ready fulfillmentStatus with ZERO pricing evidence
  //    at all is TicketService.reserveStock's FROM_STOCK auto-advance, which
  //    bucket 1's own comment already documents as having "no pricing
  //    precondition at all" — i.e. a deal that genuinely has never been
  //    priced, exactly what bucket 1 exists to catch. The CREATE_PCR UAT-bug
  //    guard was about a deal that already had price EVIDENCE and was
  //    wrongly asked for a second pricing request, not about suppressing
  //    CREATE_PCR on a deal that plausibly never had one. Changing bucket 1's
  //    priority over this bucket is out of scope for this change; pinned
  //    below (see the delivery-vs-bucket-1 case in salesActions.test.js) and
  //    already exercised, before this bucket existed, by workState.test.js's
  //    'still offers create_pcr after ... FROM_STOCK' case.
  if (nextFulfilmentActionCode(deal) === 'recordDelivery') {
    return { key: SALES_ACTION.RECORD_DELIVERY, label: ACTION_LABEL[SALES_ACTION.RECORD_DELIVERY] };
  }

  // 6. Follow-up due today or overdue.
  const followUp = followUpStatus(deal);
  if (followUp) {
    return { key: SALES_ACTION.FOLLOW_UP, label: ACTION_LABEL[SALES_ACTION.FOLLOW_UP], followUp };
  }

  // 7. No activity logged in STALE_ACTIVITY_DAYS days — `deal.stale` is
  //    already computed server/mock-side (mirrors TicketRepository.enrichSummary,
  //    see dealTrackingMeta.js's computeStale) and included on every
  //    api.tickets.list() row, so it is reused here rather than recomputed.
  if (deal.stale) {
    return { key: SALES_ACTION.LOG_ACTIVITY, label: ACTION_LABEL[SALES_ACTION.LOG_ACTIVITY] };
  }

  return null;
}

export const REVISE_STAGES = new Set(['QUOTE_OWNER', 'QUOTE_BUYER']);

/** `target` = { quotationId, number, openDraftId? }. With an open revision draft the action points
 * at it (`to`) instead of minting a second one. */
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
 * (`quotationId`/`quotationNumber`) so the caller can route to it or name it in a banner; SUBMIT
 * also carries `to`, because its real control (ส่งขออนุมัติ) lives on the quotation editor. */
function liveDirectQuotationAction(live) {
  const key = live ? DIRECT_QUOTATION_BUCKET[live.docStatus] : null;
  if (!key) return null;
  const action = { key, label: ACTION_LABEL[key], quotationId: live.id, quotationNumber: live.number };
  if (key === SALES_ACTION.SUBMIT_DIRECT_QUOTATION) action.to = `/quotations/${live.id}`;
  return action;
}

/**
 * Sorts `{ deal, action }` worklist rows overdue-first: an overdue follow-up
 * always leads regardless of what other deals' actions are, then rows are
 * grouped by ACTION_RANK, then (within the same action) the longest-waiting
 * deal (oldest stageUpdatedAt) sorts first. Does not mutate `items`.
 */
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
