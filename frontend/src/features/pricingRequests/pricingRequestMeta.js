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

export const PRICING_REQUEST_STATUSES = [
  'DRAFT',
  'SUBMITTED',
  'IMPORT_REVIEWING',
  'AWAITING_FACTORY_RESPONSE',
  'COSTING_IN_PROGRESS',
  'READY_FOR_CEO_REVIEW',
  'CEO_REVIEWING',
  'APPROVED_FOR_QUOTATION',
  'COSTING_REVISION_REQUIRED',
  'MORE_INFO_REQUIRED',
  'CANCELLED',
  'SUPERSEDED',
];

// Mirrors PricingRequestStatus.ALLOWED — forward/lateral transitions only.
// DRAFT -> DRAFT is deliberately absent: editing a draft's fields is a
// mutation guarded by status = DRAFT, not a state transition.
const ALLOWED_TRANSITIONS = {
  DRAFT: ['SUBMITTED', 'CANCELLED'],
  SUBMITTED: ['IMPORT_REVIEWING', 'CANCELLED'],
  IMPORT_REVIEWING: ['AWAITING_FACTORY_RESPONSE', 'COSTING_IN_PROGRESS', 'MORE_INFO_REQUIRED', 'CANCELLED', 'SUPERSEDED'],
  AWAITING_FACTORY_RESPONSE: ['COSTING_IN_PROGRESS', 'MORE_INFO_REQUIRED', 'CANCELLED', 'SUPERSEDED'],
  COSTING_IN_PROGRESS: ['AWAITING_FACTORY_RESPONSE', 'READY_FOR_CEO_REVIEW', 'MORE_INFO_REQUIRED', 'CANCELLED', 'SUPERSEDED'],
  // Step 3 (CEO Selling Price Decision, "one return-to-Import path"): the old
  // READY_FOR_CEO_REVIEW -> COSTING_IN_PROGRESS direct reopen entry (Costing v2 path, commit 5)
  // let Import silently reopen a SUBMITTED costing without any CEO action — removed, since that
  // is exactly what made "submitted costing is immutable" false. The CEO must now explicitly
  // start review (-> CEO_REVIEWING); the only reopen path afterward is
  // CEO_REVIEWING -> COSTING_REVISION_REQUIRED -> COSTING_IN_PROGRESS. Mirrors the backend fix to
  // PricingRequestStatus.ALLOWED.
  READY_FOR_CEO_REVIEW: ['CEO_REVIEWING', 'SUPERSEDED'],
  CEO_REVIEWING: ['APPROVED_FOR_QUOTATION', 'COSTING_REVISION_REQUIRED'],
  COSTING_REVISION_REQUIRED: ['COSTING_IN_PROGRESS'],
  APPROVED_FOR_QUOTATION: [],
  MORE_INFO_REQUIRED: ['IMPORT_REVIEWING', 'AWAITING_FACTORY_RESPONSE', 'COSTING_IN_PROGRESS', 'CANCELLED'],
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

/** Mirrors PricingRequestService.requestInformation: any import user in active Step 2 statuses. */
export function canRequestInformation(user, pr) {
  return user?.role === 'import'
    && ['IMPORT_REVIEWING', 'AWAITING_FACTORY_RESPONSE', 'COSTING_IN_PROGRESS'].includes(pr?.status);
}

/** Mirrors PricingRequestService.respondInformation: owner sales, MORE_INFO_REQUIRED only. */
export function canRespondInformation(user, pr) {
  return user?.role === 'sales' && pr?.ticketCreatedById != null
    && Number(pr.ticketCreatedById) === Number(user.id)
    && pr?.status === 'MORE_INFO_REQUIRED';
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
