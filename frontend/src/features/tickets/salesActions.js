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
// The 5 CTA buckets below are a priority cascade, evaluated in pipeline
// order (earliest-unblocked-step wins): a deal with no live pricing request
// normally needs "สร้างคำขอราคา" first, even if it also happens to be overdue
// on follow-up — there is nothing to follow up ABOUT yet. Once a deal has a
// live pricing request past that point, later buckets take over. Exception:
// a deal that was already priced OUTSIDE the PricingRequest chain (a legacy
// deal whose customer quotation went out through the retired ticket-level
// engine) skips bucket 1 even with zero pricing requests — see bucket 1's own
// comment below for why, and for why the guard is narrower than it first
// looks.

import { bangkokTodayIso } from '../../utils/format.js';

export const SALES_ACTION = {
  CREATE_PCR: 'create_pcr',
  ISSUE_QUOTATION: 'issue_quotation',
  CONFIRM_ORDER: 'confirm_order',
  FOLLOW_UP: 'follow_up',
  LOG_ACTIVITY: 'log_activity',
};

const ACTION_LABEL = {
  [SALES_ACTION.CREATE_PCR]: 'สร้างคำขอราคา',
  [SALES_ACTION.ISSUE_QUOTATION]: 'ออกใบเสนอราคา',
  [SALES_ACTION.CONFIRM_ORDER]: 'ยืนยันคำสั่งซื้อ',
  [SALES_ACTION.FOLLOW_UP]: 'ติดตามลูกค้า',
  [SALES_ACTION.LOG_ACTIVITY]: 'บันทึกกิจกรรม',
};

// Sort weight when two deals need DIFFERENT actions (lower = more urgent).
// A pending confirm-order/issue-quotation is a task sitting entirely in the
// rep's own hands with no external dependency, so it outranks a bare
// follow-up/log-activity nudge — mirrors the cascade order above.
const ACTION_RANK = {
  [SALES_ACTION.CONFIRM_ORDER]: 1,
  [SALES_ACTION.ISSUE_QUOTATION]: 2,
  [SALES_ACTION.CREATE_PCR]: 3,
  [SALES_ACTION.FOLLOW_UP]: 4,
  [SALES_ACTION.LOG_ACTIVITY]: 5,
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
// QUOTATION_ACCEPTED have their own buckets below (2 and 3); the other six — SUBMITTED,
// IMPORT_REVIEWING, AWAITING_FACTORY_RESPONSE, READY_FOR_CEO_REVIEW, CEO_REVIEWING,
// QUOTATION_ISSUED — fall through to follow-up/activity, same as a deal with no pending
// pricing-request action at all. COSTING_REVISION_REQUIRED does NOT belong in that list any more:
// V141 retired it, so no live request can carry it. V140 is the migration that retired
// COSTING_IN_PROGRESS and MORE_INFO_REQUIRED; the latter was the one genuine sales action in that
// old list ("answer import's question"), and with the ขอข้อมูลเพิ่มเติม round-trip retired there is
// no sixth CTA bucket waiting to be built here.
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
const QUOTED_STATUSES = new Set(['quotation_issued', 'document_issued']);

/**
 * The one next action `deal` needs from its owning sales rep right now, or
 * null if nothing in the 5-bucket cascade applies (e.g. the request is with
 * import/CEO and the deal isn't due for a follow-up or stale).
 *
 * `pricingRequests` is the rep's OWN pricing-request queue (already scoped
 * server-side, see api.pricingRequests.queue) — filtered here to the ones
 * belonging to this ticket.
 */
export function nextSalesAction(deal, pricingRequests = []) {
  if (!deal || deal.lifecycle !== 'ACTIVE') return null;

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
  if (ownPrs.some((pr) => pr.status === 'QUOTATION_ACCEPTED' && !pr.orderConfirmedAt)) {
    return { key: SALES_ACTION.CONFIRM_ORDER, label: ACTION_LABEL[SALES_ACTION.CONFIRM_ORDER] };
  }

  // 4. Follow-up due today or overdue.
  const followUp = followUpStatus(deal);
  if (followUp) {
    return { key: SALES_ACTION.FOLLOW_UP, label: ACTION_LABEL[SALES_ACTION.FOLLOW_UP], followUp };
  }

  // 5. No activity logged in STALE_ACTIVITY_DAYS days — `deal.stale` is
  //    already computed server/mock-side (mirrors TicketRepository.enrichSummary,
  //    see dealTrackingMeta.js's computeStale) and included on every
  //    api.tickets.list() row, so it is reused here rather than recomputed.
  if (deal.stale) {
    return { key: SALES_ACTION.LOG_ACTIVITY, label: ACTION_LABEL[SALES_ACTION.LOG_ACTIVITY] };
  }

  return null;
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
