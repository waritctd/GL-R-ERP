// Phase A of the role-scoped งานขาย (sales) views: which sections of a deal's
// TicketDetailPage each of the 5 in-scope sales roles may see, and which
// deals belong in that role's list-page worklist.
//
// Presentation only. The backend/mockApi role gates (ROLE_PERMISSIONS,
// TicketService's own checks) already decide who may actually read or act on
// any of this — per CLAUDE.md, mock authz is a faithful-shape stand-in, NOT
// authoritative. Hiding a section here never grants or removes a real
// permission; it only decides what a role's screen leads with. Reuses the
// same stage metadata TicketDetailPage/DealStagePanel already trust
// (stageMeta.js) rather than inventing a second notion of "what stage means
// what".
//
// Scope: sales, sales_manager, import, account, ceo. hr/employee never reach
// this page (route-gated in app/permissions.js) and warehouse/qc roles are
// out of scope for this phase.

import { phaseOf, stageIndex } from './stageMeta.js';

// Every section id this module knows how to hide, kept as a single list so
// visibleSections() can never accidentally omit a key a caller depends on.
const SECTION_IDS = [
  'pricingRequest', // PricingRequestPanel: the designer/owner/buyer pricing-request + factory-quote/costing chain
  'payment',        // "การชำระเงิน": amounts payable/paid/outstanding, receipt history, record-payment
  'delivery',       // "การส่งมอบสินค้า": procurement/fulfilment substeps + delivery recording
  'quotation',      // "ใบเสนอราคา": issued quotation revisions + downloads
  'depositNotice',  // the deposit-notice view/issue affordance inside the stage panel's doc row
  'priceApproval',  // "การอนุมัติราคา": the CEO approve/reject decision panel
  'dealTracking',   // "การติดตามดีล" (Slice B2, handoff 103): win%, designer/owner/buyer, activity log
];

function allTrue() {
  return Object.fromEntries(SECTION_IDS.map((id) => [id, true]));
}

function allFalse() {
  return Object.fromEntries(SECTION_IDS.map((id) => [id, false]));
}

/**
 * Which of a deal's TicketDetailPage sections `role` may see.
 *
 * ceo and sales_manager are an unconditional pass-through — both are
 * oversight roles (sales_manager is read+comment ONLY, per the note on
 * ROLE_PERMISSIONS in api/routes.js, but that never narrows what it may
 * *read*). sales sees its own full pipeline: the one thing sales must not
 * see (the CEO-only costing formula / raw landed-cost) is already
 * field-scoped elsewhere — showCalcBreakdown, gated on
 * ROLE_PERMISSIONS.canApproveReject === ['ceo'] — and is out of scope for
 * this section-level module.
 *
 * import: leads with pricing/factory-quote/costing + procurement/fulfilment
 * + its own item columns (already field-scoped to raw+proposed, never
 * approved — ROLE_PERMISSIONS.canProposePrices). It has no reason to see the
 * selling-stage machinery: quotations, the deposit notice, or the payment
 * ledger.
 *
 * account: leads with the payment section. It has no reason to see the
 * pricing-request/factory-cost chain, the procurement/shipping detail, or
 * (already field-scoped, not re-stated here) item cost columns.
 */
export function visibleSections(role) {
  if (role === 'ceo' || role === 'sales_manager' || role === 'sales') return allTrue();

  if (role === 'import') {
    return {
      ...allTrue(),
      payment: false,
      quotation: false,
      depositNotice: false,
      priceApproval: false,
      dealTracking: false,
    };
  }

  if (role === 'account') {
    return {
      ...allTrue(),
      pricingRequest: false,
      delivery: false,
      priceApproval: false,
      dealTracking: false,
    };
  }

  // Any other/unknown role (hr, employee, ...): the sales stack is already
  // off-limits to them at the route level (App.jsx / app/permissions.js) —
  // default to nothing rather than leak a section to a role this page never
  // expects to render for.
  return allFalse();
}

const PROCUREMENT_IDX = stageIndex('PROCUREMENT');
const TERMINAL_TICKET_STATUSES = new Set(['closed', 'cancelled']);
const PAYMENT_ACTION_PENDING = new Set(['DEPOSIT_NOTICE_ISSUED', 'AWAITING_FINAL_PAYMENT']);

/**
 * Whether `deal` belongs in `role`'s worklist/inbox on TicketListPage.
 *
 * Presentation-only categorization for emphasis — never a security boundary.
 * The list endpoint already scopes `sales` to its own deals server-side
 * (mockApi.js tickets.list filters by createdById when role === 'sales'; the
 * real TicketService is the authority, not this function or the mock — see
 * CLAUDE.md), so every row `sales` is ever handed already belongs in its
 * inbox.
 *
 * `deal` is expected to be a TicketListPage row (or a ticket `summary`) with
 * at least: lifecycle, status, salesStage, paymentStatus, overdue.
 */
export function dealInScope(role, deal) {
  if (!deal) return false;
  if (role === 'ceo' || role === 'sales_manager') return true;
  if (role === 'sales') return true;

  const lost = deal.lifecycle === 'CLOSED_LOST';
  const closed = lost || deal.lifecycle === 'CANCELLED' || TERMINAL_TICKET_STATUSES.has(deal.status);

  if (role === 'import') {
    if (closed) return false;
    const phase = phaseOf(deal.salesStage)?.id;
    const idx = stageIndex(deal.salesStage);
    // Phase 2 (spec) / phase 3 (bidding & negotiation) is when a
    // PricingRequest is typically in flight for this deal. The list DTO
    // doesn't carry pricing-request rows itself (those are a per-ticket
    // fetch — see PricingRequestPanel), so phase membership is the closest
    // available proxy for "has an active pricing request" at list-row
    // granularity; this is a known approximation, not an exact readout of
    // PricingRequest.status.
    //
    // idx >= PROCUREMENT is the literal "salesStage index ≥ PROCUREMENT"
    // rule: import's own procurement/fulfilment execution stages.
    return phase === 2 || phase === 3 || idx >= PROCUREMENT_IDX;
  }

  if (role === 'account') {
    // A money action is pending: a deposit or final-payment confirmation is
    // awaited, or the deal is overdue on an outstanding balance.
    return PAYMENT_ACTION_PENDING.has(deal.paymentStatus) || Boolean(deal.overdue);
  }

  return false;
}

export const SALES_VIEW_SECTION_IDS = SECTION_IDS;
