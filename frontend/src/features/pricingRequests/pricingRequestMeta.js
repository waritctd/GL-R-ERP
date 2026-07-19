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

// The internal DRAFT->SUBMITTED->IMPORT_REVIEWING journey, rendered as
// progress chips inside DealStagePanel (replaces the old ticket.status-keyed
// PRICING_SUBSTEPS, which is permanently stuck at 'draft' now that ticket
// creation no longer auto-submits — see TicketService.create/submit,
// commit 5). MORE_INFO_REQUIRED is a lateral detour (back to
// IMPORT_REVIEWING), not forward progress, but still needs its own chip so a
// request waiting on the sales rep doesn't misleadingly render as
// "IMPORT_REVIEWING done".
export const PRICING_REQUEST_SUBSTEPS = [
  { code: 'DRAFT', label: 'ร่างคำขอราคา' },
  { code: 'SUBMITTED', label: 'ส่งให้ Import แล้ว' },
  { code: 'IMPORT_REVIEWING', label: 'Import กำลังเสนอราคา' },
  { code: 'MORE_INFO_REQUIRED', label: 'รอข้อมูลเพิ่มเติม' },
];

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

/** Most recent (highest id) pricing request for a deal, or null if none exist. */
export function latestPricingRequest(pricingRequests = []) {
  if (!pricingRequests.length) return null;
  return [...pricingRequests].sort((a, b) => b.id - a.id)[0];
}
