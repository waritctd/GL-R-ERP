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
// The 7 CTA buckets below are a priority cascade, evaluated in pipeline
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
import { nextFulfilmentActionCode } from './importActions.js';

export const SALES_ACTION = {
  // Slice 2 — flow A (SLICE-2-FLOW-A.md §E): the three buckets a LIVE direct quotation drives.
  SUBMIT_DIRECT_QUOTATION: 'submit_direct_quotation',
  AWAIT_DIRECT_APPROVAL: 'await_direct_approval',
  CONFIRM_ORDER_DIRECT: 'confirm_order_direct',
  // PR A: แก้ใบเสนอราคา — revise (or open the open revision of) an approved/issued quotation. Only
  // ever produced when the caller hands nextSalesAction a `reviseTarget`.
  REVISE_QUOTATION: 'revise_quotation',
  CREATE_PCR: 'create_pcr',
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
  [SALES_ACTION.REVISE_QUOTATION]: 'แก้ใบเสนอราคา',
  [SALES_ACTION.ISSUE_QUOTATION]: 'ออกใบเสนอราคา',
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
  [SALES_ACTION.CONFIRM_ORDER_DIRECT]: 1,
  [SALES_ACTION.ISSUE_QUOTATION]: 2,
  [SALES_ACTION.SUBMIT_DIRECT_QUOTATION]: 2,
  [SALES_ACTION.RECORD_QUOTATION_OUTCOME]: 3,
  [SALES_ACTION.CREATE_PCR]: 4,
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

/**
 * The one next action `deal` needs from its owning sales rep right now, or
 * null if nothing in the 7-bucket cascade applies (e.g. the request is with
 * import/CEO and the deal isn't due for a follow-up or stale).
 *
 * `pricingRequests` is the rep's OWN pricing-request queue (already scoped
 * server-side, see api.pricingRequests.queue) — filtered here to the ones
 * belonging to this ticket.
 */
export function nextSalesAction(deal, pricingRequests = [], { reviseTarget = null } = {}) {
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

  // 2. A price is approved and ready to quote — canCreateCustomerQuotation's
  //    own gate (pricingRequestMeta.js) is exactly pr.status === 'APPROVED_FOR_QUOTATION'.
  if (ownPrs.some((pr) => pr.status === 'APPROVED_FOR_QUOTATION')) {
    return { key: SALES_ACTION.ISSUE_QUOTATION, label: ACTION_LABEL[SALES_ACTION.ISSUE_QUOTATION] };
  }

  // 3. The customer accepted the quotation but the order isn't confirmed yet —
  //    canConfirmOrder's own gate: pr.status === 'QUOTATION_ACCEPTED' && !orderConfirmedAt.
  //    Also gated on the deal's own ticket status being one the order-confirm bridge can advance
  //    (pricingRequestMeta's confirmOrderBlockedReason / OrderConfirmationService
  //    .isBridgeableTicketStatus) — otherwise the CTA is a button that can only 409.
  if (ownPrs.some((pr) => pr.status === 'QUOTATION_ACCEPTED' && !pr.orderConfirmedAt)
      && (deal.status == null || BRIDGEABLE_TICKET_STATUSES.has(deal.status))) {
    return { key: SALES_ACTION.CONFIRM_ORDER, label: ACTION_LABEL[SALES_ACTION.CONFIRM_ORDER] };
  }

  // 3b. PR A: owner/buyer quote stages with an already-issued quotation — the rep's move is to
  //    revise it for the new recipient, not to record an outcome that never applies there. The
  //    caller resolves `reviseTarget` (ownership + status via canReviseDealQuotation).
  if (reviseTarget && REVISE_STAGES.has(deal.salesStage)) return reviseAction(reviseTarget);

  // 4. The quotation went out to the customer but nobody has recorded what the customer said yet —
  //    canRecordCustomerQuotationOutcome's own gate (pricingRequestMeta.js) requires the customer
  //    quotation's docStatus to be ISSUED, but that document-level detail isn't available here (this
  //    module is deliberately built from the ticket-list row + PR queue alone, never a per-ticket
  //    fetch — see this file's own module doc comment). pr.status === 'QUOTATION_ISSUED' is the
  //    PR-level proxy: CustomerQuotationService.issue is what sets it, and it stays QUOTATION_ISSUED
  //    until recordOutcome runs (an ACCEPTED outcome moves it to QUOTATION_ACCEPTED, handled by
  //    bucket 3 above; REJECTED/REVISION_REQUESTED leave the PR itself at a terminal/superseded
  //    status that falls out of `ownPrs` entirely once a revision exists, or stops matching this
  //    bucket once recorded). Before this bucket existed, a deal parked here fell all the way
  //    through to follow-up/log-activity — the sticky CTA never told the rep the accept/reject/
  //    revision controls (DealQuotationPanel, "ราคาและใบเสนอราคา") were the actual next step, so an
  //    issued quotation could sit un-decided indefinitely with no prompt anywhere on the page.
  //
  //    Placed AFTER bucket 3 (CONFIRM_ORDER), not before: a customer-change revision can leave one
  //    sibling PR at QUOTATION_ISSUED (a stale, since-superseded round) while another reaches
  //    QUOTATION_ACCEPTED — CONFIRM_ORDER must win that race, same earliest-unblocked-step principle
  //    as the rest of this cascade.
  if (ownPrs.some((pr) => pr.status === 'QUOTATION_ISSUED')) {
    return {
      key: SALES_ACTION.RECORD_QUOTATION_OUTCOME,
      label: ACTION_LABEL[SALES_ACTION.RECORD_QUOTATION_OUTCOME],
    };
  }

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

const REVISE_STAGES = new Set(['QUOTE_OWNER', 'QUOTE_BUYER']);

/** `target` = { quotationId, number, openDraftId? }. With an open revision draft the action points
 * at it (`to`) instead of minting a second one. */
function reviseAction(target) {
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
