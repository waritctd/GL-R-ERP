// Canonical metadata for the PricingRequest aggregate (commit 6): one deal may
// have several pricing requests (one per recipient / re-quote round), each
// walking from sales submission through Import factory communication and
// costing submission. Mirrors PricingRequestStatus /
// PricingRequestRecipient / QuantityType / PricingRequestEventKind (backend
// pricingrequest/ package).
//
// NOTE: every predicate here only drives which buttons/options the UI shows —
// the backend (PricingRequestService) re-checks everything server-side. Mock
// authz approximates the Java service and is NOT authoritative (see
// CLAUDE.md "Mock API contract").

// There is deliberately no bare PRICING_REQUEST_STATUSES array here. One existed until it was
// removed as dead: nothing in the repo ever imported it, and ALLOWED_TRANSITIONS below already
// carries the identical eleven statuses as its key set — but is actually read, by canTransition.
// A second copy that nothing consumes only drifts: that array had already gone stale once and
// needed QUOTATION_ISSUED / QUOTATION_ACCEPTED added to catch it up, silently and with no test to
// notice. If you need the status set, take Object.keys(ALLOWED_TRANSITIONS).

// Mirrors PricingRequestStatus.ALLOWED (backend pricingrequest/PricingRequestStatus.java:107-179)
// — key for key, value for value. DRAFT -> DRAFT is deliberately absent: editing a draft's fields
// is a mutation guarded by status = DRAFT, not a state transition.
//
// ⚠️ THIS IS A HAND-COPIED MIRROR — but it is now GUARDED. pricingRequestMeta.test.js parses
// PricingRequestStatus.java out of the backend source tree and asserts this object against it, key
// for key and value for value, the same way stageCatalog.test.js and dealTrackingMeta.test.js
// already guard DealStage / WinProbabilityDefaults. Issue #743.
//
// That guard is what this comment used to say did not exist, and its absence is exactly how the
// table went stale: a vitest suite CAN pin these values but cannot, on its own, notice that the
// backend moved — which is why the old suite asserted
// canTransition('READY_FOR_CEO_REVIEW','CANCELLED') === false under a heading claiming to mirror
// the backend, for months after #718 made it true (issue #731). Re-syncing by hand resets that
// clock; the parse stops it.
//
// The durable fix is still to SERVE the verdict on the pricing-request DTO the way `stageDecisions`
// does for deal stages — pricing requests have no equivalent of /api/meta/deal-stages — at which
// point there is no mirror left to test. Until such a field exists, the parse is the strongest
// guard available.
//
// Re-synced 2026-08-14 against the Java source, which had moved in three ways:
//   - #718 widened cancel: READY_FOR_CEO_REVIEW / CEO_REVIEWING / APPROVED_FOR_QUOTATION all now
//     declare a CANCELLED edge, and cancel is refused only from QUOTATION_ISSUED onward. Those
//     three missing edges are what hid the ยกเลิก button (issue #731).
//   - Owner ruling 2026-08-13 (reissue-through-CEO-chain): EVERY non-terminal status declares a
//     SUPERSEDED edge, and QUOTATION_ACCEPTED deliberately does NOT — it is terminal, because
//     amending an accepted deal is an ORDER amendment, not a quotation revision. That set is what
//     canCreateCustomerRevision now reads (issue #734).
//   - V141 retired COSTING_REVISION_REQUIRED entirely (the CEO owns costing; returnToImport goes
//     straight to AWAITING_FACTORY_RESPONSE), so its key is gone from here too. This USED TO be
//     a mock-only relic — mockApi.js's returnPricingDecisionToImport wrote the retired status and
//     demoSales.js seeded a fixture in it — but issue #741 fixed both: the mock write now matches
//     the backend, and demoSales.js:615 seeds AWAITING_FACTORY_RESPONSE with a comment recording
//     why.
// Exported for the guard only — pricingRequestMeta.test.js compares it against the parsed Java.
// Application code goes through canTransition; do not read the table directly. Three callers do
// today: canCancelPricingRequest below (:269, asks CANCELLED), mockApi.js's pricing-request cancel
// handler (imports canTransition at :41 as pricingRequestCanTransition, also asks CANCELLED), and
// PricingRequestDetailPage.jsx's canCreateCustomerRevision (:594, asks SUPERSEDED — issue #734
// above). Not all three ask the same question, which is why the guard checks every edge for every
// status rather than just the ones any one caller happens to exercise.
//
// #753 decision (2026-08-14): KEEP this table and canTransition as-is; do not delete, do not edit
// an entry. The durable fix is still to SERVE the verdict rather than mirror it (above) — not done
// here because the verdict would have to ride on PricingRequestSummaryDto, not just
// PricingRequestDetailDto: PricingRequestPanel.jsx (:161, :178) calls canCancelPricingRequest on
// LIST rows from api.pricingRequests.listForTicket (returns List<PricingRequestSummaryDto>, the
// same summary shape the /pricing-requests queue endpoint returns), never a detail fetch. That is
// a list-response-payload change, out of scope for the untidy sweep that closed #753. What holds
// the line meanwhile: this file's test parses PricingRequestStatus.java out of the backend source
// and asserts this table against it key-for-key, value-for-value, both directions, plus a
// non-vacuous-parse assertion — a backend edge change turns the suite red instead of drifting
// silently. That guard, not the re-sync above, is what stopped the clock.
export const ALLOWED_TRANSITIONS = {
  DRAFT: ['SUBMITTED', 'CANCELLED'],
  // SUBMITTED -> READY_FOR_CEO_REVIEW is the factory-quote carry-forward edge: a customer-change
  // revision whose items are identical to its parent's copies the parent's factory quotes and has
  // nothing left for Import to do (PricingRequestService.carryFactoryQuotesForwardOnSubmit).
  SUBMITTED: ['IMPORT_REVIEWING', 'READY_FOR_CEO_REVIEW', 'CANCELLED', 'SUPERSEDED'],
  // V140: Import's states. COSTING_IN_PROGRESS merged into AWAITING_FACTORY_RESPONSE
  // (เจรจาราคากับโรงงาน) and MORE_INFO_REQUIRED left the product.
  IMPORT_REVIEWING: ['AWAITING_FACTORY_RESPONSE', 'CANCELLED', 'SUPERSEDED'],
  // V141: FactoryQuoteService.markReadyForCosting auto-advances straight to READY_FOR_CEO_REVIEW
  // once every item's quote is resolvable — there is no Import-driven costing step in between.
  AWAITING_FACTORY_RESPONSE: ['READY_FOR_CEO_REVIEW', 'CANCELLED', 'SUPERSEDED'],
  // The back-edge to AWAITING_FACTORY_RESPONSE is FactoryQuoteService.receive()'s revision branch:
  // a factory sends a revised price while the request already sits with the CEO, so the request is
  // pulled back until Import re-marks the revised quote ready.
  READY_FOR_CEO_REVIEW: ['CEO_REVIEWING', 'AWAITING_FACTORY_RESPONSE', 'CANCELLED', 'SUPERSEDED'],
  // V141: PricingDecisionService.returnToImport sends the request to AWAITING_FACTORY_RESPONSE,
  // not to a dedicated "revise the costing" status — the CEO's next startReview recomputes cost.
  CEO_REVIEWING: ['APPROVED_FOR_QUOTATION', 'AWAITING_FACTORY_RESPONSE', 'CANCELLED', 'SUPERSEDED'],
  // Step 4: the ONLY forward exit is issuing a customer quotation (CustomerQuotationService.issue).
  APPROVED_FOR_QUOTATION: ['QUOTATION_ISSUED', 'CANCELLED', 'SUPERSEDED'],
  // Step 5: the customer's ACCEPTED outcome is the one forward exit from QUOTATION_ISSUED
  // (CustomerQuotationService.recordOutcome). REJECTED/REVISION_REQUESTED/EXPIRED deliberately
  // do NOT transition the pricing request at all. No CANCELLED edge — #718's cutoff.
  QUOTATION_ISSUED: ['QUOTATION_ACCEPTED', 'SUPERSEDED'],
  QUOTATION_ACCEPTED: [],
  SUPERSEDED: [],
  CANCELLED: [],
};

export function canTransition(from, to) {
  return !!from && !!to && (ALLOWED_TRANSITIONS[from] ?? []).includes(to);
}

export const RECIPIENT_OPTIONS = [
  { code: 'DESIGNER', label: 'ผู้ออกแบบ (Designer)' },
  { code: 'OWNER', label: 'เจ้าของโครงการ' },
  { code: 'BUYER', label: 'ผู้ซื้อ / ผู้รับเหมา' },
];

export function pricingRequestRecipientLabel(value) {
  return RECIPIENT_OPTIONS.find((r) => r.code === value)?.label ?? value ?? '-';
}

export const QUANTITY_TYPE_OPTIONS = [
  { code: 'REFERENCE', label: 'อ้างอิง (เช่น 1 ตร.ม. ต่อแบบ)' },
  { code: 'ESTIMATE', label: 'ประมาณการ (ยังไม่ยืนยัน)' },
  { code: 'CONFIRMED', label: 'ยืนยันแล้ว (จาก BOQ)' },
];

export function quantityTypeLabel(value) {
  return QUANTITY_TYPE_OPTIONS.find((q) => q.code === value)?.label ?? value ?? '-';
}

// Mirrors th.co.glr.hr.pricingrequest.UnitBasis: the canonical unit-basis codes shared across
// the pricing-request / factory-quote / costing aggregate (financial-integrity review Finding
// B, commit 3). requestedUnit stays a free-text display label alongside this canonical code —
// PricingRequestCreateModal's unit input writes both from the same select.
export const UNIT_BASIS_OPTIONS = [
  { code: 'PER_PIECE', label: 'แผ่น' },
  { code: 'PER_SQM', label: 'ตร.ม.' },
  { code: 'PER_BOX', label: 'กล่อง' },
  { code: 'PER_LINEAR_M', label: 'เมตร' },
];

export function unitBasisLabel(value) {
  return UNIT_BASIS_OPTIONS.find((u) => u.code === value)?.label ?? value ?? '-';
}

/** Mirrors PricingRequestService.createDraft: sales (deal owner), deal must be ACTIVE. */
export function canCreatePricingRequest(user, deal) {
  return user?.role === 'sales' && deal?.createdById != null
    && Number(deal.createdById) === Number(user.id)
    && (deal?.lifecycle ?? 'ACTIVE') === 'ACTIVE';
}

/** Mirrors PricingRequestService.updateDraft: owner sales, DRAFT only. */
export function canUpdatePricingRequest(user, pr) {
  return user?.role === 'sales' && pr?.ticketCreatedById != null
    && Number(pr.ticketCreatedById) === Number(user.id)
    && pr?.status === 'DRAFT';
}

/** Mirrors PricingRequestService.submit: owner sales, DRAFT only. */
export function canSubmitPricingRequest(user, pr) {
  return user?.role === 'sales' && pr?.ticketCreatedById != null
    && Number(pr.ticketCreatedById) === Number(user.id)
    && pr?.status === 'DRAFT';
}

/** Mirrors PricingRequestService.pickup: any import user, SUBMITTED only. */
export function canPickupPricingRequest(user, pr) {
  return user?.role === 'import' && pr?.status === 'SUBMITTED';
}

// ── Step 3: CEO Selling Price Decision. Mirrors PricingDecisionService. ──────────────────

/** Mirrors PricingDecisionService.startReview: ceo only, READY_FOR_CEO_REVIEW only. */
export function canStartCeoReview(user, pr) {
  return user?.role === 'ceo' && pr?.status === 'READY_FOR_CEO_REVIEW';
}

/** Mirrors PricingDecisionService.update/recalculate/approve/returnToImport: ceo only, and
 * only while the request is CEO_REVIEWING (the decision itself must also be DRAFT — the caller
 * passes the decision, not just the pricing request, when that distinction matters). */
export function canActOnPricingDecision(user, pr) {
  return user?.role === 'ceo' && pr?.status === 'CEO_REVIEWING';
}

/** Mirrors PricingDecisionService.RAW_DECISION_ROLES: only import/ceo ever see cost/margin. */
export function canSeeRawPricingDecision(user) {
  return user?.role === 'import' || user?.role === 'ceo';
}

/** Mirrors PricingDecisionService.salesView's SALES_VIEW_ROLES + owner scoping for 'sales'. */
export function canSeePricingDecisionSalesView(user, pr) {
  if (!user || !['sales', 'sales_manager', 'ceo', 'import'].includes(user.role)) return false;
  if (user.role === 'sales') return pr?.ticketCreatedById != null && Number(pr.ticketCreatedById) === Number(user.id);
  return true;
}

// ── Step 4: Customer Quotation Generation and Issuance. Mirrors CustomerQuotationService. ──

/** Mirrors CustomerQuotationService.create's gate: sales (ticket owner) only, and only once the
 * pricing request is APPROVED_FOR_QUOTATION (the very first quotation for this request — a
 * correction after issue instead goes through createRevision, gated by
 * canManageCustomerQuotation on the existing ISSUED quotation, not this). */
export function canCreateCustomerQuotation(user, pr) {
  if (user?.role !== 'sales') return false;
  if (pr?.ticketCreatedById == null || Number(pr.ticketCreatedById) !== Number(user.id)) return false;
  return pr?.status === 'APPROVED_FOR_QUOTATION';
}

/** Mirrors CustomerQuotationService's edit/issue/cancel/createRevision owner gate: sales (ticket
 * owner) only — ceo/import/sales_manager are always read-only on this aggregate, never just
 * hidden buttons on an otherwise-writable page. */
export function canManageCustomerQuotation(user, pr) {
  if (user?.role !== 'sales') return false;
  return pr?.ticketCreatedById != null && Number(pr.ticketCreatedById) === Number(user.id);
}

/** Mirrors CustomerQuotationService.VIEW_ROLES: sales (owner-scoped)/sales_manager/ceo/import may
 * read; account has no positive grant anywhere on this aggregate and stays forbidden. */
export function canViewCustomerQuotation(user, pr) {
  if (!user || !['sales', 'sales_manager', 'ceo', 'import'].includes(user.role)) return false;
  if (user.role === 'sales') return pr?.ticketCreatedById != null && Number(pr.ticketCreatedById) === Number(user.id);
  return true;
}

/** Mirrors CustomerQuotationService.requireDraft: only a DRAFT/READY_TO_ISSUE quotation may be
 * edited, previewed-for-editing, or cancelled — an ISSUED one is immutable (rule 8). */
export function isCustomerQuotationEditable(quotation) {
  return ['DRAFT', 'READY_TO_ISSUE'].includes(quotation?.docStatus);
}

/**
 * Mirrors the reissue-through-CEO-chain refusal in CustomerQuotationService.update (:226-240,
 * owner ruling 2026-08-13): a REVISION may not carry ANY discount, so a non-zero salesDiscount on
 * a quotation with a parent is a 409. A first quotation is untouched — there the CEO's own
 * decision supplied the ceiling/floor, so discounting inside it is authority the CEO granted.
 *
 * This reads the SERVER's own `parentQuotationId` (CustomerQuotationDtos.java:29) rather than
 * re-deriving "is this a revision" from docStatus. That distinction is the whole bug (issue #733):
 * a revision IS a DRAFT, so isCustomerQuotationEditable is true for it and the page offered a
 * discount input that could only ever 409. #703 also stopped createRevision carrying prior
 * discounts forward, so a rep opening a revision sees every discount at zero — retyping one is the
 * natural reaction, and it was the exact action the backend rejected.
 *
 * Editability of the discount is therefore narrower than editability of the quotation: description
 * and item notes stay writable on a revision, only the money does not.
 */
export function isCustomerQuotationDiscountEditable(quotation) {
  return isCustomerQuotationEditable(quotation) && quotation?.parentQuotationId == null;
}

// ── Step 5: Customer Decision and Commercial Revisions. Mirrors CustomerQuotationService.recordOutcome. ──

/** Mirrors CustomerQuotationService.recordOutcome's gate: sales (ticket owner) only, and only
 * while the quotation is ISSUED — the same owner scoping as canManageCustomerQuotation, plus the
 * docStatus gate recordOutcome's own WHERE clause enforces server-side. */
export function canRecordCustomerQuotationOutcome(user, pr, quotation) {
  return canManageCustomerQuotation(user, pr) && quotation?.docStatus === 'ISSUED';
}

/** Once a REVISION_REQUESTED outcome is recorded, Sales must explicitly choose commercial-only
 * (createRevision, reachable from ISSUED or REVISION_REQUESTED) vs cost-affecting
 * (createCustomerChangeRevision, reachable from most non-terminal pricing-request statuses —
 * see canCreateCustomerRevision's own inline gate in PricingRequestDetailPage). This predicate
 * only covers the commercial-only button's own visibility. */
export function canCreateCommercialOnlyRevision(user, pr, quotation) {
  return canManageCustomerQuotation(user, pr) && ['ISSUED', 'REVISION_REQUESTED'].includes(quotation?.docStatus);
}

// ── Step 6: Deposit, Payment, and Order Confirmation. Mirrors OrderConfirmationService. ──

/** Mirrors OrderConfirmationService.confirmOrder's gate: sales (ticket owner) only, reachable
 * ONLY from QUOTATION_ACCEPTED (Step 5's terminal status), and only until the bridge has already
 * run once (orderConfirmedAt is set on the FIRST successful call and never cleared). */
export function canConfirmOrder(user, pr) {
  return canManageCustomerQuotation(user, pr) && pr?.status === 'QUOTATION_ACCEPTED' && !pr?.orderConfirmedAt;
}

/** Mirrors OrderConfirmationService.createDepositNoticeFromQuotation's gate: sales (ticket owner)
 * only, reachable once the bridge action above has run (the endpoint's own precondition —
 * ticket.status=quotation_issued — is only true after confirmOrder). */
export function canCreateDepositNoticeFromQuotation(user, pr) {
  return canManageCustomerQuotation(user, pr) && pr?.status === 'QUOTATION_ACCEPTED' && Boolean(pr?.orderConfirmedAt);
}

/** Mirrors PricingRequestService.cancel: owner sales OR ceo, any cancellable status. */
export function canCancelPricingRequest(user, pr) {
  if (!pr) return false;
  const isOwnerOrCeo = user?.role === 'ceo'
    || (pr.ticketCreatedById != null && Number(pr.ticketCreatedById) === Number(user?.id));
  return isOwnerOrCeo && canTransition(pr.status, 'CANCELLED');
}

/**
 * Deal-level pricing-request summary for DealStagePanel's glance strip (Fix 3
 * of the review-remediation plan). Replaces the old `latestPricingRequest`
 * (highest-id-wins) reduction, which was actively misleading in two ways: (a)
 * a DRAFT created after an active IMPORT_REVIEWING request has a higher id
 * and hid the request Import is actually working on, and (b) a cancelled
 * newest request made the whole strip vanish even though an older request
 * was still live. This surfaces every non-CANCELLED request instead of
 * picking one "latest" — CANCELLED requests are dead ends with nothing left
 * to track, so they're the one status intentionally excluded.
 *
 * Returns null when there is nothing live to show (no requests at all, or
 * every request is CANCELLED) so the caller can render nothing, same as
 * before.
 */
export function activePricingRequestsSummary(pricingRequests = []) {
  const active = pricingRequests.filter((pr) => pr.status !== 'CANCELLED');
  if (active.length === 0) return null;
  const counts = {};
  for (const pr of active) {
    counts[pr.status] = (counts[pr.status] ?? 0) + 1;
  }
  return {
    total: active.length,
    counts,
    // Stable order (creation order, oldest first) rather than re-sorting by
    // status, so the strip doesn't reshuffle every time one request's status
    // changes.
    requests: [...active].sort((a, b) => a.id - b.id),
  };
}
