// Canonical metadata for the PricingRequest aggregate (commit 6): one deal may
// have several pricing requests (one per recipient / re-quote round), each
// walking DRAFT -> SUBMITTED -> IMPORT_REVIEWING -> (MORE_INFO_REQUIRED <->
// IMPORT_REVIEWING) -> CANCELLED. Mirrors PricingRequestStatus /
// PricingRequestRecipient / QuantityType / PricingRequestEventKind (backend
// pricingrequest/ package).
//
// NOTE: every predicate here only drives which buttons/options the UI shows —
// the backend (PricingRequestService) re-checks everything server-side. Mock
// authz approximates the Java service and is NOT authoritative (see
// CLAUDE.md "Mock API contract").

export const PRICING_REQUEST_STATUSES = [
  'DRAFT', 'SUBMITTED', 'IMPORT_REVIEWING', 'MORE_INFO_REQUIRED', 'CANCELLED',
];

// Mirrors PricingRequestStatus.ALLOWED — forward/lateral transitions only.
// DRAFT -> DRAFT is deliberately absent: editing a draft's fields is a
// mutation guarded by status = DRAFT, not a state transition.
const ALLOWED_TRANSITIONS = {
  DRAFT: ['SUBMITTED', 'CANCELLED'],
  SUBMITTED: ['IMPORT_REVIEWING', 'CANCELLED'],
  IMPORT_REVIEWING: ['MORE_INFO_REQUIRED'],
  MORE_INFO_REQUIRED: ['IMPORT_REVIEWING', 'CANCELLED'],
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

/** Mirrors PricingRequestService.requestInformation: the ASSIGNED import user, IMPORT_REVIEWING only. */
export function canRequestInformation(user, pr) {
  return user?.role === 'import' && pr?.assignedImportId != null
    && Number(pr.assignedImportId) === Number(user.id)
    && pr?.status === 'IMPORT_REVIEWING';
}

/** Mirrors PricingRequestService.respondInformation: owner sales, MORE_INFO_REQUIRED only. */
export function canRespondInformation(user, pr) {
  return user?.role === 'sales' && pr?.ticketCreatedById != null
    && Number(pr.ticketCreatedById) === Number(user.id)
    && pr?.status === 'MORE_INFO_REQUIRED';
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
