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
// always needs "สร้างใบขอราคา" first, even if it also happens to be overdue
// on follow-up — there is nothing to follow up ABOUT yet. Once a deal has a
// live pricing request past that point, later buckets take over.

import { bangkokTodayIso } from '../../utils/format.js';

export const SALES_ACTION = {
  CREATE_PCR: 'create_pcr',
  ISSUE_QUOTATION: 'issue_quotation',
  CONFIRM_ORDER: 'confirm_order',
  FOLLOW_UP: 'follow_up',
  LOG_ACTIVITY: 'log_activity',
};

const ACTION_LABEL = {
  [SALES_ACTION.CREATE_PCR]: 'สร้างใบขอราคา',
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

// Pricing-request statuses that mean "this request is with import/CEO right
// now" — nothing here for sales to click. Anything not explicitly matched by
// the cascade below (SUBMITTED, IMPORT_REVIEWING, AWAITING_FACTORY_RESPONSE,
// COSTING_IN_PROGRESS, READY_FOR_CEO_REVIEW, CEO_REVIEWING,
// MORE_INFO_REQUIRED, QUOTATION_ISSUED, COSTING_REVISION_REQUIRED) falls
// through to the follow-up/activity buckets, same as a deal with no pending
// pricing-request action at all. (MORE_INFO_REQUIRED is a genuine sales
// action too — answer import's question — but is not one of this Overview's
// 5 CTA buckets; it is left for a future iteration rather than overloading
// one of the five with an inaccurate label.)
const LIVE_PR_STATUSES = new Set(['DRAFT', 'CANCELLED', 'SUPERSEDED']);

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
  const hasLivePr = ownPrs.some((pr) => !LIVE_PR_STATUSES.has(pr.status));
  if (!hasLivePr) {
    return { key: SALES_ACTION.CREATE_PCR, label: ACTION_LABEL[SALES_ACTION.CREATE_PCR] };
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
