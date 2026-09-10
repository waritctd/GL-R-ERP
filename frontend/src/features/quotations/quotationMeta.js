// Canonical metadata for the Quotation v2 "direct deal quotation" aggregate
// (QUOTATION-V2-PLAN.md, owner ruling 2026-09-09): the pricing-chain-BYPASS quotation. Sales adds
// items straight onto a quotation, types unit price + discount, sales_manager/ceo approves. This
// is a SIBLING feature to the PCR-chain CustomerQuotation (features/pricingRequests/
// pricingRequestMeta.js) -- do not merge the two, and do not touch that file or
// DealQuotationPanel.jsx (the PCR-chain's own quotation panel) from here.
//
// Mirrors th.co.glr.hr.dealquotation package (DealQuotationService / QuotationStatus gaining
// PENDING_APPROVAL/APPROVED). NOTE: every predicate here only drives which buttons/options the UI
// shows -- the backend re-checks everything server-side. Mock authz (mockApi.js) approximates the
// Java service and is NOT authoritative -- see CLAUDE.md "Mock API contract". At the time this
// file was written the backend slice had not landed yet (frontend built in parallel against the
// same plan section), so these predicates are the frontend's best-effort mirror of the plan's
// Authz section, not yet verified against a real service -- verify before relying on them for an
// authz claim.

// ── Status machine ───────────────────────────────────────────────────────────────────────────
// DRAFT -> (submit, sales/sales_manager) -> PENDING_APPROVAL -> (approve) -> APPROVED
//                                                              -> (reject+reason) -> DRAFT
// DRAFT -> (cancel) -> CANCELLED
// APPROVED -> (revise) -> a NEW DRAFT child; the parent becomes SUPERSEDED only once THAT child is
// itself APPROVED (not before -- the customer's last approved document stays valid until
// replaced). Editing is DRAFT-only.
export const DEAL_QUOTATION_TRANSITIONS = {
  DRAFT: ['PENDING_APPROVAL', 'CANCELLED'],
  PENDING_APPROVAL: ['APPROVED', 'DRAFT'],
  // The APPROVED -> SUPERSEDED edge is the side effect of a child revision being approved, not a
  // status a caller ever requests directly (there is no "supersede" endpoint in the plan).
  APPROVED: ['SUPERSEDED'],
  SUPERSEDED: [],
  CANCELLED: [],
};

export function canTransitionDealQuotation(from, to) {
  return !!from && !!to && (DEAL_QUOTATION_TRANSITIONS[from] ?? []).includes(to);
}

// Thai status labels + StatusBadge tone. Canonical source -- do not re-add a page-local copy.
// Distinct map from utils/format.js's quotationStatusLabel, which is the PCR-chain
// CustomerQuotation's own (different) enum -- DRAFT/READY_TO_ISSUE/ISSUED/SENT/ACCEPTED/
// REJECTED/REVISION_REQUESTED/EXPIRED/CANCELLED/SUPERSEDED. The two share some keys with
// different meanings; never import one where the other belongs.
const DEAL_QUOTATION_STATUS_LABELS = {
  DRAFT: { label: 'ร่าง', tone: 'neutral' },
  PENDING_APPROVAL: { label: 'รออนุมัติ', tone: 'warning' },
  APPROVED: { label: 'อนุมัติแล้ว', tone: 'success' },
  SUPERSEDED: { label: 'ถูกแทนที่', tone: 'neutral' },
  CANCELLED: { label: 'ยกเลิก', tone: 'danger' },
};

export function dealQuotationStatusLabel(status) {
  return DEAL_QUOTATION_STATUS_LABELS[status] ?? { label: status, tone: 'neutral' };
}

// ── Ownership / role predicates. Mirrors QUOTATION-V2-PLAN.md's Authz section. ────────────────
//
// "ticket.created_by = actor" throughout the plan is the SAME comparison every other sales-scoped
// ownership check in this codebase makes -- `user.id === ticket.createdById` (see
// TicketDetailPage.jsx:703, DealDepositPanel.jsx:89, TicketService.java's actor.id() checks) --
// NOT user.employeeId. A DealQuotationDto's own `salesRepId` field is defined by the plan to
// literally equal `ticket.created_by` at creation time, so predicates that only receive the
// quotation (not the ticket) compare against `salesRepId` instead -- same comparison, one hop
// removed.

/** The per-employee "can create quotations" capability (owner ruling, Ploy 2026-09-09 --
 * ภิญญดา, employee 144, QC&ISO / role `qc`), mirroring `AuthResponse.canCreateQuotation` /
 * `hr.employee.can_create_quotation` (V166). A CAPABILITY, not a role -- deliberately not
 * further role-restricted, same shape as the existing `admin` capability (App.jsx's
 * userFromAuthResponse flattens it onto `user.canCreateQuotation`). It grants "any deal", same
 * as sales_manager, for create/edit/view -- see DealQuotationService.requireEditAccess /
 * requireViewAccess javadoc. It does NOT extend to approve/reject, which stay sales_manager/ceo
 * only (canApproveDealQuotation below never checks this). This is a UI hint only -- the backend
 * re-reads the grant live on every request (EmployeeAuthRepository#canCreateQuotation), so
 * forging it on the client gains nothing. */
export function hasDealQuotationGrant(user) {
  return Boolean(user?.canCreateQuotation);
}

/** Mirrors DealQuotationService.create: sales (deal owner), sales_manager (any deal), or a
 * canCreateQuotation-granted employee (any deal, regardless of role). */
export function canCreateDealQuotation(user, ticket) {
  if (!user || !ticket) return false;
  if (user.role === 'sales_manager' || hasDealQuotationGrant(user)) return true;
  return user.role === 'sales' && ticket.createdById != null && Number(ticket.createdById) === Number(user.id);
}

/** The role-only half of canCreateDealQuotation's own check, for the moment BEFORE a ticket
 * exists (owner ask 2026-09-10, "inline deal creation" -- QuotationEditorPage's /quotations/new
 * with no `?ticket=`). There is no deal yet to own, so the "sales (deal owner)" ownership
 * comparison above cannot run -- but the CREATOR of a brand-new ticket always becomes its
 * `createdById`/owner (TicketService.create sets it to the acting user), so the same three
 * audiences canCreateDealQuotation already grants "own deal" access to are exactly the ones who
 * would immediately re-pass that check the instant the ticket exists. Never use this once a
 * ticket (or `ticket` prop) is available -- canCreateDealQuotation(user, ticket) is the real,
 * ownership-checked gate and must win whenever there is something to check ownership against. */
export function canCreateDealQuotationStandalone(user) {
  if (!user) return false;
  return user.role === 'sales' || user.role === 'sales_manager' || hasDealQuotationGrant(user);
}

/** Mirrors DealQuotationService.update/submit/cancel/createRevision's owner gate: sales (deal
 * owner), sales_manager, or a canCreateQuotation-granted employee -- all "any deal". DOES NOT
 * check docStatus; combine with isDealQuotationEditable / the specific transition (submit/
 * cancel/revise each have their own status precondition, see canSubmitDealQuotation /
 * canCancelDealQuotation / canReviseDealQuotation below). */
export function canEditDealQuotation(user, quotation) {
  if (!user || !quotation) return false;
  if (user.role === 'sales_manager' || hasDealQuotationGrant(user)) return true;
  return user.role === 'sales' && quotation.salesRepId != null && Number(quotation.salesRepId) === Number(user.id);
}

/** DRAFT is the only editable status (plan: "Editing is DRAFT-only (WHERE clause enforced)"). */
export function isDealQuotationEditable(quotation) {
  return quotation?.docStatus === 'DRAFT';
}

export function canSubmitDealQuotation(user, quotation) {
  return canEditDealQuotation(user, quotation) && canTransitionDealQuotation(quotation?.docStatus, 'PENDING_APPROVAL');
}

export function canCancelDealQuotation(user, quotation) {
  return canEditDealQuotation(user, quotation) && canTransitionDealQuotation(quotation?.docStatus, 'CANCELLED');
}

/** "APPROVED -> (revise) -> new DRAFT child": only an APPROVED quotation may be revised. */
export function canReviseDealQuotation(user, quotation) {
  return canEditDealQuotation(user, quotation) && quotation?.docStatus === 'APPROVED';
}

/** Mirrors DealQuotationService.approve/reject's gate: sales_manager or ceo, role-only (the
 * plan states "No self-exclusion (matches repo convention; flagged)") -- the caller combines
 * this with canDecideDealQuotation below, or its own PENDING_APPROVAL check, before rendering a
 * button. Kept role-only (no `quotation` param) per this file's own contract with the caller. */
export function canApproveDealQuotation(user) {
  return user?.role === 'sales_manager' || user?.role === 'ceo';
}

/** canApproveDealQuotation ANDed with the one status a decision may actually be made against --
 * the button-gating shape most callers want. */
export function canDecideDealQuotation(user, quotation) {
  return canApproveDealQuotation(user) && quotation?.docStatus === 'PENDING_APPROVAL';
}

/** Mirrors the plan's "view/list/download" role set exactly: sales (own deals), sales_manager,
 * ceo, import, account (the latter two read-only) -- OR a canCreateQuotation-granted employee,
 * who may view any deal (DealQuotationService.requireViewAccess: the grant already lets them
 * act on any deal, so it lets them view any deal too). Role-only, per this file's contract with
 * the caller -- per-row "own deals only" scoping for `sales` happens server-side (and in
 * mockApi.js's list/listForTicket filters), the same split canViewCustomerQuotation-style
 * predicates elsewhere in this codebase draw between the route guard and the row filter. */
export function canViewDealQuotation(user) {
  if (!user) return false;
  return hasDealQuotationGrant(user) || ['sales', 'sales_manager', 'ceo', 'import', 'account'].includes(user.role);
}

/** Read-only viewers per the plan ("import, account read-only") -- never see write actions
 * regardless of docStatus, distinct from canEditDealQuotation's sales/sales_manager audience. */
export function isDealQuotationReadOnlyViewer(user) {
  return user?.role === 'import' || user?.role === 'account';
}

// ── Terms card options (editor) ────────────────────────────────────────────────────────────────

export const DEPOSIT_PERCENT_PRESETS = [30, 50];

export const REMAINDER_MODE_OPTIONS = [
  { code: 'CREDIT', label: 'เครดิต' },
  { code: 'ON_DELIVERY', label: 'ชำระเมื่อส่งมอบ' },
];

export function remainderModeLabel(value) {
  return REMAINDER_MODE_OPTIONS.find((o) => o.code === value)?.label ?? value ?? '-';
}

export const VALIDITY_DAYS_OPTIONS = [15, 30, 45, 60];

// ── Item row options (editor) ────────────────────────────────────────────────────────────────

export const QUANTITY_MODE_OPTIONS = [
  { code: 'AREA', label: 'ตร.ม.' },
  { code: 'PIECES', label: 'แผ่น' },
];

export const WASTAGE_PERCENT_PRESETS = [0, 5, 10];

// ประเทศต้นทาง select + its default lead-time range (min/max days), editable per line. Mirrors
// the plan's "อิตาลี/สเปน/จีน/ไทย-สต็อก/อื่นๆ" list exactly, in that order.
export const ORIGIN_COUNTRY_OPTIONS = [
  { code: 'อิตาลี', label: 'อิตาลี', leadTimeMinDays: 75, leadTimeMaxDays: 90 },
  { code: 'สเปน', label: 'สเปน', leadTimeMinDays: 75, leadTimeMaxDays: 90 },
  { code: 'จีน', label: 'จีน', leadTimeMinDays: 60, leadTimeMaxDays: 75 },
  { code: 'ไทย-สต็อก', label: 'ไทย-สต็อก', leadTimeMinDays: 30, leadTimeMaxDays: 45 },
  { code: 'อื่นๆ', label: 'อื่นๆ', leadTimeMinDays: null, leadTimeMaxDays: null },
];

export function defaultLeadTimeForOrigin(originCountry) {
  const option = ORIGIN_COUNTRY_OPTIONS.find((o) => o.code === originCountry);
  return { leadTimeMinDays: option?.leadTimeMinDays ?? null, leadTimeMaxDays: option?.leadTimeMaxDays ?? null };
}

// ── Item completeness (frontend pass 4, owner ruling 2026-09-10) ────────────────────────────────
// "Autofill as much as possible when the item is in the database; sales can also fill in their own
// item if it is not in the database, but ALL info about the tile has to be completed." A catalog
// pick only ever autofills what QuotationItemRow.pickCatalog copies across from ProductPriceDto --
// it never guarantees completeness (a catalog gap, e.g. NO_THICKNESS, affects roughly a third of
// the prod catalog per prod-catalog-41pct-unpriceable-no-thickness.md), so this validates the SAME
// fields regardless of whether the row was picked from the catalog or typed by hand. Deliberately
// does NOT distinguish "catalog-linked" from "custom" -- a catalog pick only ever pre-fills fields,
// it never exempts the row from having to carry them.
//
// Pure function of one item, keyed by the EXACT `item` field name (see emptyQuotationItem in
// QuotationItemRow.jsx) so a caller can render the message directly under that field's FormField
// (`error={errors.thicknessMm}`) as well as build a per-row summary from the same result --
// QuotationEditorPage does both from one call, never two independent checks that could drift.
//
// Optional per the plan (never validated here): ยี่ห้อ (brand), รหัสสินค้า (productCode),
// ตำแหน่งติดตั้ง (locationLabel), ประเทศต้นทาง (originCountry) + its lead-time range, หมายเหตุ
// (itemNotes), ส่วนลด % (discountPct, defaults to 0).
export function validateQuotationItem(item) {
  const errors = {};
  if (!item?.model?.trim()) errors.model = 'กรุณาระบุรุ่น';
  if (!item?.color?.trim()) errors.color = 'กรุณาระบุสี';
  if (!item?.texture?.trim()) errors.texture = 'กรุณาระบุผิว';
  if (!item?.sizeText?.trim()) errors.sizeText = 'กรุณาระบุขนาด';
  if (!(Number(item?.thicknessMm) > 0)) errors.thicknessMm = 'กรุณาระบุความหนา (มม.)';
  if (!(Number(item?.piecesPerBox) >= 1)) errors.piecesPerBox = 'กรุณาระบุแผ่น/กล่อง';
  if (!(Number(item?.sqmPerPiece) > 0)) errors.sqmPerPiece = 'กรุณาระบุตร.ม./แผ่น';
  if (!(Number(item?.unitPrice) > 0)) errors.unitPrice = 'กรุณาระบุราคา/หน่วย';
  if (item?.quantityMode === 'PIECES') {
    if (!(Number(item?.piecesInput) >= 1)) errors.piecesInput = 'กรุณาระบุจำนวนแผ่น';
  } else if (!(Number(item?.areaSqm) > 0)) {
    errors.areaSqm = 'กรุณาระบุพื้นที่ (ตร.ม.)';
  }
  return errors;
}

// Short Thai field labels for the per-row "รายการที่ N: ขาด X, Y" summary -- deliberately shorter
// than validateQuotationItem's own full sentences ("กรุณาระบุความหนา (มม.)" -> "ความหนา"), and in
// the same left-to-right order the item row itself lays the fields out in, so the summary reads
// in the order the rep will actually scan the row.
const QUOTATION_ITEM_FIELD_ORDER = [
  'model', 'color', 'texture', 'sizeText', 'thicknessMm', 'sqmPerPiece', 'piecesPerBox',
  'unitPrice', 'areaSqm', 'piecesInput',
];
const QUOTATION_ITEM_FIELD_LABELS = {
  model: 'รุ่น', color: 'สี', texture: 'ผิว', sizeText: 'ขนาด', thicknessMm: 'ความหนา',
  sqmPerPiece: 'ตร.ม./แผ่น', piecesPerBox: 'แผ่น/กล่อง', unitPrice: 'ราคา/หน่วย',
  areaSqm: 'จำนวน (พื้นที่)', piecesInput: 'จำนวน (แผ่น)',
};

/** "รายการที่ {index+1}: ขาด {field1}, {field2}" or null once `errors` (validateQuotationItem's
 * own return value) is empty. Kept a separate, tiny function rather than folded into
 * validateQuotationItem so a caller that only wants the per-field errors (QuotationItemRow's
 * inline hints) never pays for string-building it does not use. */
export function quotationItemMissingSummary(errors, index) {
  const keys = Object.keys(errors ?? {});
  if (keys.length === 0) return null;
  const ordered = QUOTATION_ITEM_FIELD_ORDER.filter((key) => keys.includes(key));
  const labels = ordered.map((key) => QUOTATION_ITEM_FIELD_LABELS[key]);
  return `รายการที่ ${index + 1}: ขาด ${labels.join(', ')}`;
}
