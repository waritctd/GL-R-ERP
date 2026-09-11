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
  // Owner testing feedback 2026-09-11, verbatim: "ระยะเวลานำเข้า / จีน 30-45 วัน / ไทย มีในสตอค
  // 3-7 วัน" -- and "แค่เปลี่ยนตัวเลขที่มีอยู่", i.e. these two defaults, not new logic.
  // อิตาลี/สเปน were not mentioned and keep 75-90.
  { code: 'จีน', label: 'จีน', leadTimeMinDays: 30, leadTimeMaxDays: 45 },
  { code: 'ไทย-สต็อก', label: 'ไทย-สต็อก', leadTimeMinDays: 3, leadTimeMaxDays: 7 },
  { code: 'อื่นๆ', label: 'อื่นๆ', leadTimeMinDays: null, leadTimeMaxDays: null },
];

export function defaultLeadTimeForOrigin(originCountry) {
  const option = ORIGIN_COUNTRY_OPTIONS.find((o) => o.code === originCountry);
  return { leadTimeMinDays: option?.leadTimeMinDays ?? null, leadTimeMaxDays: option?.leadTimeMaxDays ?? null };
}

// ISO-3166 alpha-2 -> the ORIGIN_COUNTRY_OPTIONS code above (owner feedback F1, 2026-09-10:
// "autofill as much as you can"). ProductPriceDto.originCountryCode is `price_catalog.factories
// .country` joined through `product_prices.factory_id` -- the SAME base table CatalogRepository
// #findPricingKeys reads, never sales.factory_config.country (V151). Deliberately a SHORT,
// explicit map rather than a country-name lookup: only these four codes have an agreed Thai
// ประเทศต้นทาง option and an agreed default lead-time range, and any other code (a European
// factory outside IT/ES, say) must leave the field BLANK for the rep to choose rather than be
// silently filed under "อื่นๆ" -- "อื่นๆ" carries no lead time, so guessing it would look filled
// in while telling the customer nothing.
const ORIGIN_COUNTRY_BY_ISO_CODE = {
  IT: 'อิตาลี',
  ES: 'สเปน',
  CN: 'จีน',
  TH: 'ไทย-สต็อก',
};

/** '' (never null) when the code is unknown/absent, so a caller can spread the result into a
 * patch and have `originCountry: ''` mean "still unset" exactly like emptyQuotationItem does. */
export function originCountryFromCode(originCountryCode) {
  if (!originCountryCode) return '';
  return ORIGIN_COUNTRY_BY_ISO_CODE[String(originCountryCode).trim().toUpperCase()] ?? '';
}

// ── List-page status tabs (owner feedback F5, 2026-09-10) ────────────────────────────────────
// "for สถานะ make it ทั้งหมด, รออนุมัติ, แก้, ยกเลิก" + อนุมัติแล้ว, kept as a fifth tab per the
// owner's ruling the same evening. `key` is what travels in the URL (`/quotations?status=`), so
// these strings are a user-visible contract -- a bookmarked or shared tab link must keep working.
//
// แก้ is NOT a docStatus: it is DRAFT rows that were sent back with a reason (`approvalNote`) OR
// DRAFT revisions in progress (`parentQuotationId`), which is why it travels as its own
// server-side `needsRework=true` filter rather than `status=DRAFT` -- see
// DealQuotationDtos.DealQuotationCountsDto's javadoc, which defines the two the same way. A
// client-side post-filter would be wrong here for the reason CLAUDE.md gives about truncation:
// the server may cap the list, and filtering after the cap filters a different set of rows.
export const DEAL_QUOTATION_STATUS_TABS = [
  { key: 'all', label: 'ทั้งหมด', countKey: 'all', params: {} },
  { key: 'PENDING_APPROVAL', label: 'รออนุมัติ', countKey: 'pendingApproval', params: { status: 'PENDING_APPROVAL' } },
  { key: 'NEEDS_REWORK', label: 'แก้', countKey: 'needsRework', params: { needsRework: true } },
  { key: 'CANCELLED', label: 'ยกเลิก', countKey: 'cancelled', params: { status: 'CANCELLED' } },
  { key: 'APPROVED', label: 'อนุมัติแล้ว', countKey: 'approved', params: { status: 'APPROVED' } },
];

/** Approvers (sales_manager/ceo) land on the queue that is waiting on THEM; everyone else on
 * ทั้งหมด. Only used when the URL carries no `?status=` -- an explicit tab in the URL always
 * wins, so a shared link opens the same tab for whoever follows it. */
export function defaultDealQuotationStatusTab(user) {
  return canApproveDealQuotation(user) ? 'PENDING_APPROVAL' : 'all';
}

export function dealQuotationStatusTab(key) {
  return DEAL_QUOTATION_STATUS_TABS.find((tab) => tab.key === key) ?? null;
}

// ── ตำแหน่งติดตั้ง location groups (owner feedback F1, 2026-09-10) ────────────────────────────
// "if there were to be a ตำแหน่งติดตั้ง there might be multiple รายการ in one ตำแหน่งติดตั้ง".
//
// THE DATA MODEL IS UNCHANGED: `location_label` stays a per-ITEM column, the wire format stays a
// flat `items` array, and the renderer still prints one heading per RUN of equal labels. Grouping
// is purely an editor-side view over that flat list -- a group is "the maximal run of consecutive
// items carrying the same label", which is exactly what the document prints. That equivalence is
// what makes the round-trip lossless: what the rep sees grouped is what the customer sees grouped.
//
// The editor therefore keeps ONE flat, ordered `items` array (unchanged from before) plus a
// parallel ordered `groups` list, and maintains the invariant that items sharing a groupId are
// CONTIGUOUS in that array, in group order. Save is then `items.map(...)` with no re-sorting at
// all, and load is the reverse walk below.

/** What a blank ตำแหน่งติดตั้ง label reads as in the editor. A blank label prints NO heading row on
 * the document (the renderer groups only non-blank labels), so this text is UI-only and is never
 * sent to the server -- `locationLabel` stays null for such a group. */
export const UNLABELLED_LOCATION_TEXT = 'ไม่ระบุตำแหน่ง';

let locationGroupSeq = 0;
export function newLocationGroupId() {
  locationGroupSeq += 1;
  return `loc-${locationGroupSeq}`;
}

/**
 * Rebuilds the editor's groups from a flat, ordered item list — "loading rebuilds groups from
 * consecutive equal labels". Returns `{ groups, items }` where every item carries the `groupId`
 * of the run it belongs to; the returned items keep their input order, so the contiguity
 * invariant above holds by construction.
 *
 * A null/undefined/'' label is its own value, so two blank-labelled items that are ADJACENT share
 * one unlabelled group, while a blank item between two "ชั้น 1" items splits ชั้น 1 into two
 * groups — which is precisely what the printed document does with the same rows. Never
 * "collect all blanks together": that would reorder the items and change the document.
 */
export function locationGroupsFromItems(items) {
  const groups = [];
  let previousLabel = null;
  const nextItems = (items ?? []).map((item, index) => {
    const label = item.locationLabel ?? '';
    if (index === 0 || label !== previousLabel) {
      groups.push({ groupId: newLocationGroupId(), label });
    }
    previousLabel = label;
    return { ...item, groupId: groups[groups.length - 1].groupId };
  });
  if (groups.length === 0) groups.push({ groupId: newLocationGroupId(), label: '' });
  return { groups, items: nextItems };
}

// ── MED-4: the round trip is LOSSY, so the editor prevents the lossy states ──────────────────────
// Review finding MED-4: two deliberately separate groups carrying the SAME label collapse into one
// on reload, and a group with no items vanishes entirely.
//
// **Why a stable per-item group key cannot fix this, plainly:** a group key would have to survive
// `items[] -> server -> items[]`, and the wire format carries exactly one grouping field,
// `locationLabel` — a per-ITEM column on `sales.quotation_item`. Adding a key means a schema
// change AND a wire change. And even then it would not buy what it looks like it buys, because the
// DOCUMENT has the same limitation by design: QuotationRenderer prints one heading per RUN of
// equal labels, so two adjacent same-label groups are not merely lost in the editor — they are
// literally one heading on the printed page, which is what the customer sees. An editor that
// showed two groups there would be lying about the output. An empty group is the same story: it
// contributes no item rows, so it cannot exist on the document at all.
//
// So the editor makes the ambiguity UNREACHABLE instead of pretending to round-trip it: a
// duplicate label is a save-blocking error, an empty group is a visible warning, and neither is
// ever silently swallowed. Both helpers are pure functions of the editor's own state.

/** Group ids whose trimmed label duplicates an EARLIER group's — blank counts as a value, since
 * two blank-labelled groups merge exactly like two "ชั้น 1" ones. The first occurrence is never
 * reported: it is the one that is fine, and the later one is what the rep has to rename. */
export function duplicateLocationLabelGroupIds(groups) {
  const seen = new Set();
  const duplicates = new Set();
  (groups ?? []).forEach((group) => {
    const key = (group.label ?? '').trim();
    if (seen.has(key)) duplicates.add(group.groupId);
    else seen.add(key);
  });
  return duplicates;
}

/** Group ids holding no items — they print nothing and do not survive a save, so the editor says
 * so out loud rather than letting one quietly disappear on the next reload. */
export function emptyLocationGroupIds(groups, items) {
  const populated = new Set((items ?? []).map((item) => item.groupId));
  return new Set((groups ?? []).filter((g) => !populated.has(g.groupId)).map((g) => g.groupId));
}

// ── The "แก้" tab's predicate (owner feedback F5, 2026-09-10) ───────────────────────────────────
/**
 * A DRAFT that was sent back with a reason, OR a DRAFT revision in progress. Both senses, per the
 * owner's ruling that แก้ means both. Mirrors DealQuotationRepository.NEEDS_REWORK_PREDICATE:
 *
 *   (q.doc_status = 'DRAFT' AND (q.approval_note IS NOT NULL OR q.parent_quotation_id IS NOT NULL))
 *
 * Note a SUPERSEDED or APPROVED row with an approvalNote is NOT included — the DRAFT status is part
 * of the definition, not incidental to it.
 *
 * ⚠️ `!= null`, NOT `Boolean(...)` (review finding MED-5). SQL's IS NOT NULL is TRUE for the empty
 * string, so a truthiness test diverges on exactly that row: a DRAFT carrying `approvalNote: ''` is
 * IN the แก้ tab on the real backend and was OUT of it in the mock, in the direction where the mock
 * shows FEWER rows than production and nobody notices. `!= null` catches null and undefined and
 * nothing else, which is the JS spelling of IS NOT NULL.
 *
 * Lives here rather than inside mockApi.js so it can be tested as the pure predicate it is —
 * mockApi imports it, exactly as it already imports canTransitionDealQuotation.
 */
export function isDealQuotationNeedingRework(row) {
  return row?.docStatus === 'DRAFT' && (row.approvalNote != null || row.parentQuotationId != null);
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
//
// v3 (2026-09-11): `priceMode` makes the PRICE half mode-aware — SPECIAL_SQM also needs the
// ราคาพิเศษ, DIRECT_NET needs the net per piece and treats the list price as optional (a blank one
// is sent as the net itself, which prints "Net" — see itemInputFromRow). A PLAIN row is validated
// by validatePlainItem instead; an ADJUSTMENT row never reaches here (it lives in its own list).
export function validateQuotationItem(item, priceMode = 'NET') {
  if (lineTypeOf(item) === LINE_TYPE_PLAIN) return validatePlainItem(item);
  const errors = {};
  if (!item?.model?.trim()) errors.model = 'กรุณาระบุรุ่น';
  if (!item?.color?.trim()) errors.color = 'กรุณาระบุสี';
  if (!item?.texture?.trim()) errors.texture = 'กรุณาระบุผิว';
  if (!item?.sizeText?.trim()) errors.sizeText = 'กรุณาระบุขนาด';
  if (!(Number(item?.thicknessMm) > 0)) errors.thicknessMm = 'กรุณาระบุความหนา (มม.)';
  if (!(Number(item?.piecesPerBox) >= 1)) errors.piecesPerBox = 'กรุณาระบุแผ่น/กล่อง';
  if (!(Number(item?.sqmPerPiece) > 0)) errors.sqmPerPiece = 'กรุณาระบุตร.ม./แผ่น';
  if (priceMode === 'DIRECT_NET') {
    if (!(Number(item?.directNetPrice) > 0)) errors.directNetPrice = 'กรุณาระบุราคาสุทธิ/แผ่น';
    // Optional here, but a typed one must still be positive — the server refuses a non-positive
    // unitPrice on every TILE row whatever the mode.
    if (item?.unitPrice !== '' && item?.unitPrice != null && !(Number(item.unitPrice) > 0)) {
      errors.unitPrice = 'ราคาตั้งต้องมากกว่าศูนย์';
    }
  } else if (!(Number(item?.unitPrice) > 0)) {
    errors.unitPrice = priceMode === 'SPECIAL_SQM' ? 'กรุณาระบุราคาตั้ง/แผ่น' : 'กรุณาระบุราคา/หน่วย';
  }
  if (priceMode === 'SPECIAL_SQM') {
    if (!(Number(item?.specialPriceSqm) > 0)) errors.specialPriceSqm = 'กรุณาระบุราคาพิเศษ (บาท/ตร.ม.)';
    else if (!withinDecimals(item.specialPriceSqm, 2)) errors.specialPriceSqm = 'ทศนิยมได้ไม่เกิน 2 ตำแหน่ง';
  }
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
  'description', 'model', 'color', 'texture', 'sizeText', 'thicknessMm', 'sqmPerPiece', 'piecesPerBox',
  'unitPrice', 'specialPriceSqm', 'directNetPrice', 'quantity', 'unit', 'areaSqm', 'piecesInput',
  'adjustmentPct', 'adjustmentAmount',
];
const QUOTATION_ITEM_FIELD_LABELS = {
  model: 'รุ่น', color: 'สี', texture: 'ผิว', sizeText: 'ขนาด', thicknessMm: 'ความหนา',
  sqmPerPiece: 'ตร.ม./แผ่น', piecesPerBox: 'แผ่น/กล่อง', unitPrice: 'ราคา/หน่วย',
  areaSqm: 'จำนวน (พื้นที่)', piecesInput: 'จำนวน (แผ่น)',
  // v3
  description: 'รายละเอียด', quantity: 'จำนวน', unit: 'หน่วย',
  specialPriceSqm: 'ราคาพิเศษ', directNetPrice: 'ราคาสุทธิ/แผ่น',
  adjustmentPct: 'เปอร์เซ็นต์ส่วนลด', adjustmentAmount: 'จำนวนเงินส่วนลด',
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

// ── Quotation v3 / v3b (owner feedback pass 3, 2026-09-11) ─────────────────────────────────────
// Owner's standing rule for this whole feature: "sales should have manual type as less as
// possible". Everything below exists so a rep picks a mode ONCE per quotation and the rows adapt,
// rather than choosing per row. Mirrors th.co.glr.hr.dealquotation.WastageCalculator's constants
// (LINE_TYPE_*, PRICE_MODE_*, DOCUMENT_LANGUAGE_*, CURRENCY_*) and DealQuotationService's
// language/mode rules — the SERVICE enforces every one of these; the UI only avoids offering what
// it would refuse.

export const LINE_TYPE_TILE = 'TILE';
export const LINE_TYPE_PLAIN = 'PLAIN';
export const LINE_TYPE_ADJUSTMENT = 'ADJUSTMENT';

/** A pre-v3 row (and every mock/fixture row that predates V168) carries no lineType; the server
 * normalises a stored NULL to TILE on read, and so does this. */
export function lineTypeOf(item) {
  return item?.lineType || LINE_TYPE_TILE;
}

export const DOCUMENT_LANGUAGE_OPTIONS = [
  { code: 'TH', label: 'ไทย', currency: 'THB', hint: 'ใบเสนอราคาภาษาไทย (F-SM-002) · บาท · มี VAT 7%' },
  { code: 'EN', label: 'English', currency: 'USD', hint: 'ใบเสนอราคาภาษาอังกฤษ (F-SM-008) · USD · ไม่มี VAT' },
];

/** TH→THB, EN→USD — DealQuotationService#resolveCurrency refuses any other pairing, so the UI
 * derives the currency rather than offering it as a second choice. */
export function currencyForLanguage(documentLanguage) {
  return documentLanguage === 'EN' ? 'USD' : 'THB';
}

/** VAT applies to the Thai form only (v3b: the English F-SM-008 has no VAT row at all). */
export function vatRateForLanguage(documentLanguage) {
  return documentLanguage === 'EN' ? 0 : 0.07;
}

export const PRICE_MODE_OPTIONS = [
  { code: 'NET', label: 'ราคาตั้ง − ส่วนลด %', hint: 'กรอกราคาตั้งต่อแผ่นและส่วนลด % (แบบเดิม)' },
  { code: 'SPECIAL_SQM', label: 'ราคาพิเศษ บาท/ตร.ม.', hint: 'กรอกราคาพิเศษต่อ ตร.ม. รวม VAT แล้ว ระบบคำนวณราคาสุทธิต่อแผ่นให้' },
  { code: 'DIRECT_NET', label: 'ราคาสุทธิต่อแผ่น', hint: 'กรอกราคาสุทธิต่อแผ่นตรง ๆ' },
];

/**
 * The tile price modes a document in `documentLanguage` may use. SPECIAL_SQM is a Thai-market
 * concept (a VAT-INCLUSIVE บาท/ตร.ม. price divided back out by 1.07), and the English form carries
 * no VAT, so DealQuotationService#requirePriceModeAvailableInLanguage refuses it there with a 400.
 * Not offering it is the UI half of that rule, never a substitute for it.
 */
export function availablePriceModes(documentLanguage) {
  return PRICE_MODE_OPTIONS.filter((opt) => !(documentLanguage === 'EN' && opt.code === 'SPECIAL_SQM'));
}

/**
 * What a language switch does to the price mode. A document already in SPECIAL_SQM that becomes
 * English is MOVED to DIRECT_NET — and `moved` is true so the caller says so out loud, rather than
 * the mode changing silently under the rep (the brief's own words: "with a clear message rather
 * than silently"). DIRECT_NET, not NET, because it is the mode that still lets the rep state a net
 * per piece, which is what a ราคาพิเศษ document was expressing.
 */
export function priceModeForLanguage(priceMode, documentLanguage) {
  const allowed = availablePriceModes(documentLanguage).some((opt) => opt.code === priceMode);
  if (allowed) return { priceMode, moved: false };
  return { priceMode: 'DIRECT_NET', moved: true };
}

/**
 * The controlled หน่วย list for a PLAIN row. The backend accepts any string up to 30 characters
 * (ItemInput.unit is `@Size(max = 30)`, matching sales.quotation_item.raw_unit), so this list is a
 * UI choice, not a server rule: every unit the owner's nine documents actually print, split by the
 * language of the form they appear on (JOB/Bags/Barrels are all from her English samples). The
 * current document's language is listed first; the other stays available because a Thai document
 * can legitimately carry "SQM" and vice versa.
 */
export const PLAIN_UNIT_OPTIONS = {
  TH: ['แผ่น', 'ตร.ม.', 'กล่อง', 'ชุด', 'ชิ้น', 'ถุง', 'ถัง', 'งาน'],
  EN: ['JOB', 'SQM', 'Bags', 'Barrels'],
};

/**
 * One-click starting points for the PLAIN rows the owner's documents actually carry, so the
 * commonest non-tile lines take a click and a price rather than a typed sentence. Only offered on a
 * blank row (the component decides that); each fills description + unit (+ quantity 1 for a
 * one-off service) and nothing the rep has not seen.
 */
export const PLAIN_ROW_PRESETS = {
  TH: [
    { label: 'ค่าขนส่ง', description: 'ค่าขนส่ง', unit: 'งาน', quantity: 1 },
    { label: 'ค่าบริการตัดกระเบื้อง', description: 'ค่าบริการตัดกระเบื้องตามแบบ', unit: 'แผ่น' },
  ],
  EN: [
    { label: 'Transportation', description: 'Transportation Charges', unit: 'JOB', quantity: 1 },
  ],
};

/** `$` for USD rather than `US$`: the document's own column heading already says "Amount (USD)"
 * and the settings block names the currency, and the เป็นเงิน column floor (QuotationDocumentView's
 * ITEM_GRID) was measured for a one-glyph symbol. A negative amount — an ADJUSTMENT row's
 * เป็นเงิน — prints the sign BEFORE the symbol ("-฿38,198.21"), not "฿-38,198.21". */
export function formatQuotationMoney(value, currency = 'THB') {
  if (value === null || value === undefined || value === '') return '-';
  const amount = Number(value);
  if (!Number.isFinite(amount)) return '-';
  const symbol = currency === 'USD' ? '$' : '฿';
  const body = Math.abs(amount).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  return `${amount < 0 ? '-' : ''}${symbol}${body}`;
}

/** 3 → "3", 2.5 → "2.5", 1350 → "1,350" — DealQuotationLines#format's `#,##0.##`. */
function formatPlainNumber(value) {
  const amount = Number(value);
  if (!Number.isFinite(amount)) return '';
  return amount.toLocaleString('en-US', { maximumFractionDigits: 2 });
}

/**
 * The ส่วนลดพิเศษ description, for the EDITOR's preview only — the saved row's `descriptionLine`
 * comes from DealQuotationLines#adjustmentDescription and is what is printed. Same phrasing and the
 * same zero-padded dd/MM/BE date that method emits ("31/07/2569"), so the preview and the saved
 * line read identically. A flat adjustment with the rep's own wording keeps that wording, exactly
 * as the service does.
 */
export function adjustmentDescriptionPreview(adjustment) {
  if (adjustment?.adjustmentKind === 'AMOUNT' && adjustment?.description?.trim()) {
    return adjustment.description.trim();
  }
  const pct = adjustment?.adjustmentKind === 'AMOUNT' ? null : adjustment?.adjustmentPct;
  const head = `ส่วนลดพิเศษ${pct !== null && pct !== undefined && pct !== '' ? ` ${formatPlainNumber(pct)}%` : ''}`;
  const deadline = adjustment?.adjustmentDeadline;
  if (!deadline || !/^\d{4}-\d{2}-\d{2}$/.test(deadline)) return head;
  const [year, month, day] = deadline.split('-').map(Number);
  return `${head} สำหรับการสั่งซื้อภายใน ${String(day).padStart(2, '0')}/${String(month).padStart(2, '0')}/${year + 543}`;
}

/**
 * A client-side ESTIMATE of a ส่วนลดพิเศษ row's magnitude, shown only while the editor holds unsaved
 * edits — once saved, the editor shows the server's own figure instead (see QuotationAdjustmentRow).
 *
 * Why the editor needs one at all: `calculate-line` is a single-row preview with no quotation, so it
 * has no "rows above" to take a percentage OF (DealQuotationService#calculateLine passes a ZERO base)
 * and would answer 0 for every percentage adjustment. The estimate is `base × pct / 100` rounded
 * half-up to satang — WastageCalculator#adjustmentAmount's rule — with the base being every
 * NON-adjustment row's line amount, because two adjustments do NOT compound on the server. It is
 * labelled an estimate wherever it is shown. It is deliberately NOT in mockApi.js: a mock that
 * mirrored it would make mock-driven tests evidence of nothing about the server's figure.
 */
export function estimateAdjustmentAmount(adjustment, base) {
  if (adjustment?.adjustmentKind === 'AMOUNT') {
    const flat = Number(adjustment.adjustmentAmount);
    return Number.isFinite(flat) && flat > 0 ? flat : null;
  }
  const pct = Number(adjustment?.adjustmentPct);
  if (!(pct > 0) || !Number.isFinite(Number(base))) return null;
  return Math.round(Number(base) * pct) / 100;
}

/**
 * The ส่วนลด column of the document view, for one row — DealQuotationRenderAdapter#discountLabel,
 * so the screen and the printed page say the same thing. ADJUSTMENT prints NOTHING (the owner's
 * QN6900704-2 leaves the cell empty); a TILE row reads พิเศษ in SPECIAL_SQM, and in DIRECT_NET when
 * the typed net differs from the list price; everything else is `Net` or `N%`. On an English
 * document the word is "Special" — the English form has no Thai text in the item table.
 */
export function documentDiscountLabel(item, priceMode, documentLanguage = 'TH') {
  const type = lineTypeOf(item);
  if (type === LINE_TYPE_ADJUSTMENT) return '';
  const special = documentLanguage === 'EN' ? 'Special' : 'พิเศษ';
  if (type === LINE_TYPE_TILE) {
    if (priceMode === 'SPECIAL_SQM') return special;
    if (priceMode === 'DIRECT_NET') {
      const differs = item.unitPrice != null && item.netUnitPrice != null
        && Number(item.unitPrice) !== Number(item.netUnitPrice);
      return differs ? special : 'Net';
    }
  }
  const pct = Number(item.discountPct);
  return !pct ? 'Net' : `${formatPlainNumber(pct)}%`;
}

/** Up to `places` decimals — the backend's `@Digits(fraction = N)` bounds (quantity 2, the
 * ราคาพิเศษ 2, adjustmentPct 3). A value beyond them is a 400 from bean validation, so the editor
 * says so on the field instead of letting the save fail. */
function withinDecimals(value, places) {
  const text = String(value ?? '');
  const dot = text.indexOf('.');
  return dot < 0 || text.length - dot - 1 <= places;
}

/** PLAIN row completeness — DealQuotationService#buildPlainItem's own list (รายละเอียด, จำนวน,
 * หน่วย) plus the positive price #requirePriceValidForType demands of every non-adjustment row. */
export function validatePlainItem(item) {
  const errors = {};
  if (!item?.description?.trim()) errors.description = 'กรุณาระบุรายละเอียด';
  if (!(Number(item?.quantity) > 0)) errors.quantity = 'กรุณาระบุจำนวน';
  else if (!withinDecimals(item.quantity, 2)) errors.quantity = 'จำนวนทศนิยมได้ไม่เกิน 2 ตำแหน่ง';
  if (!item?.unit?.trim()) errors.unit = 'กรุณาเลือกหน่วย';
  if (!(Number(item?.unitPrice) > 0)) errors.unitPrice = 'กรุณาระบุราคา/หน่วย';
  return errors;
}

/** ส่วนลดพิเศษ completeness — #requirePriceValidForType's "exactly one of percent / flat, and
 * positive". The deadline is optional on the server (the description simply omits the date). */
export function validateAdjustment(adjustment) {
  const errors = {};
  if (adjustment?.adjustmentKind === 'AMOUNT') {
    if (!(Number(adjustment?.adjustmentAmount) > 0)) errors.adjustmentAmount = 'กรุณาระบุจำนวนเงินส่วนลด';
  } else {
    const pct = Number(adjustment?.adjustmentPct);
    if (!(pct > 0)) errors.adjustmentPct = 'กรุณาระบุเปอร์เซ็นต์ส่วนลด';
    else if (pct > 100) errors.adjustmentPct = 'ส่วนลดต้องไม่เกิน 100%';
    else if (!withinDecimals(adjustment.adjustmentPct, 3)) errors.adjustmentPct = 'ทศนิยมได้ไม่เกิน 3 ตำแหน่ง';
  }
  return errors;
}

// ── "ข้อมูลที่ยังไม่ครบ" checklist (owner, 2026-09-11) ──────────────────────────────────────────
// "validate with the quotation which field have not been filled yet". The fields are the ones her
// reference documents print in their HEADER — customer name, ที่อยู่, เลขที่ผู้เสียภาษี, โทร., the
// ผู้สั่งซื้อ and its โทร./อีเมล, โครงการ — plus each item's own completeness.
//
// ⚠️ Two tiers, and the split is deliberately NOT invented here. A check BLOCKS บันทึกร่าง /
// ส่งขออนุมัติ only when the backend ALREADY refuses the same state:
//   customer / project   → TicketService.create ("ต้องเลือกโครงการก่อนสร้างดีล"), inline path only
//   contact              → DealQuotationService#resolveContact / #submit ("กรุณาระบุผู้สั่งซื้อ")
//   items                → #buildItem / #requireStoredItemComplete, and "at least one row"
//   locationLabels       → the editor's own MED-4 rule (an unsaveable-without-loss state, pre-existing)
//   priceModeLanguage    → #requirePriceModeAvailableInLanguage (SPECIAL_SQM on EN → 400)
// Everything else is a WARNING: shown, clickable, never blocking. An earlier owner ruling
// (F7, 2026-09-10) says a customer with no tax id must still be quotable, and the owner has NOT
// ruled on whether a missing ที่อยู่ or โทร. should block — so they do not, until she does.
//
// `dealProject` is a separate key from `project` on purpose: on the ?ticket= / existing-draft paths
// the project belongs to the DEAL and cannot be chosen here, and neither the quotation create nor
// its submit refuses a deal without one (only TicketService.create does, for NEW deals since V50).
export const QUOTATION_CHECK = Object.freeze({
  CUSTOMER: 'customer',
  PROJECT: 'project',
  DEAL_PROJECT: 'dealProject',
  CONTACT: 'contact',
  CONTACT_PHONE: 'contactPhone',
  CONTACT_EMAIL: 'contactEmail',
  CUSTOMER_ADDRESS: 'customerAddress',
  CUSTOMER_TAX_ID: 'customerTaxId',
  CUSTOMER_PHONE: 'customerPhone',
  LOCATION_LABELS: 'locationLabels',
  PRICE_MODE_LANGUAGE: 'priceModeLanguage',
  ITEMS: 'items',
});

/** THE blocking set — the one place that decides which checklist entries disable บันทึกร่าง and
 * ส่งขออนุมัติ. Every other check is a warning. Change it only on an owner ruling. */
export const QUOTATION_BLOCKING_CHECKS = Object.freeze(new Set([
  QUOTATION_CHECK.CUSTOMER,
  QUOTATION_CHECK.PROJECT,
  QUOTATION_CHECK.CONTACT,
  QUOTATION_CHECK.LOCATION_LABELS,
  QUOTATION_CHECK.PRICE_MODE_LANGUAGE,
  QUOTATION_CHECK.ITEMS,
]));

/** DOM id of each editor field the checklist can focus. The three customer-detail ids are the
 * shared CustomerDetailsFields' (the same ids on every entry path — only one path renders at a
 * time). */
export const QUOTATION_FIELD_IDS = Object.freeze({
  customer: 'deal-customer',
  project: 'deal-project',
  customerAddress: 'deal-customer-address',
  customerTaxId: 'deal-customer-tax-id',
  customerPhone: 'deal-customer-phone',
});

// Per-row field → the id prefix the row component gives that control (`${prefix}-${index}`).
// Kept beside QUOTATION_ITEM_FIELD_ORDER so a new required field cannot be added to the summary
// without the checklist being able to jump to it.
const ITEM_FIELD_ID_PREFIX = {
  TILE: {
    model: 'model', color: 'color', texture: 'texture', sizeText: 'size', thicknessMm: 'thickness',
    sqmPerPiece: 'sqm', piecesPerBox: 'ppb', unitPrice: 'price', specialPriceSqm: 'special',
    directNetPrice: 'direct-net', areaSqm: 'qty', piecesInput: 'qty',
  },
  PLAIN: { description: 'plain-desc', quantity: 'plain-qty', unit: 'plain-unit', unitPrice: 'plain-price' },
  ADJUSTMENT: { adjustmentPct: 'adj-pct', adjustmentAmount: 'adj-amount' },
};

/** The DOM id of the FIRST missing field in a row's `errors` (validateQuotationItem /
 * validateAdjustment output), in the row's own left-to-right order — or null if none. */
export function firstMissingItemFieldId(item, errors, index) {
  const prefixes = ITEM_FIELD_ID_PREFIX[lineTypeOf(item)] ?? ITEM_FIELD_ID_PREFIX.TILE;
  const first = QUOTATION_ITEM_FIELD_ORDER.find((key) => errors?.[key] && prefixes[key]);
  return first ? `${prefixes[first]}-${index}` : null;
}

function blankValue(value) {
  return value === null || value === undefined || String(value).trim() === '';
}

/**
 * The checklist, as `{ check, message, targetId, blocking }` entries, in the order the editor
 * reads top to bottom. Pure: every input is editor state the caller already holds.
 *
 * `customer` / `contact` carry the details the document prints. A field whose value is
 * `undefined` is UNKNOWN (still loading, or a stand-in seeded from a name only) and produces no
 * warning — only a known-empty one (null / '') does, so the list never flashes "missing" at a
 * value that simply has not arrived yet.
 *
 * The blocking messages are the exact strings the editor showed before this checklist existed
 * (and, for ผู้สั่งซื้อ, the backend's own 400 wording), so a rep sees one sentence for one problem.
 */
export function buildQuotationChecklist({
  isInlineCreate = false,
  customer = null,
  hasProject = false,
  // undefined = not loaded yet (the deal is still in flight) → no warning; null/'' = no project.
  projectName = undefined,
  contact = null,
  contactFieldId = 'quotation-contact',
  items = [],
  itemErrorsByRow = [],
  adjustments = [],
  adjustmentErrorsByRow = [],
  duplicateGroupIndex = null,
  priceModeLanguageConflict = false,
} = {}) {
  const entries = [];
  const push = (check, message, targetId = null) => {
    entries.push({ check, message, targetId, blocking: QUOTATION_BLOCKING_CHECKS.has(check) });
  };

  if (isInlineCreate) {
    if (!customer) push(QUOTATION_CHECK.CUSTOMER, 'ต้องเลือกลูกค้าก่อนบันทึกร่าง', QUOTATION_FIELD_IDS.customer);
    if (!hasProject) push(QUOTATION_CHECK.PROJECT, 'ต้องเลือกโครงการก่อนบันทึกร่าง', QUOTATION_FIELD_IDS.project);
  } else if (projectName !== undefined && blankValue(projectName)) {
    push(QUOTATION_CHECK.DEAL_PROJECT, 'ดีลนี้ยังไม่มีโครงการ (แก้ได้ที่หน้ารายละเอียดดีล)');
  }
  if (!contact?.id) push(QUOTATION_CHECK.CONTACT, 'กรุณาระบุผู้สั่งซื้อ', contactFieldId);

  if (customer) {
    if (customer.address !== undefined && blankValue(customer.address)) {
      push(QUOTATION_CHECK.CUSTOMER_ADDRESS, 'ยังไม่ได้กรอกที่อยู่ลูกค้า', QUOTATION_FIELD_IDS.customerAddress);
    }
    if (customer.taxId !== undefined && blankValue(customer.taxId)) {
      push(QUOTATION_CHECK.CUSTOMER_TAX_ID, 'ยังไม่ได้กรอกเลขที่ผู้เสียภาษี', QUOTATION_FIELD_IDS.customerTaxId);
    }
    if (customer.phone !== undefined && blankValue(customer.phone)) {
      push(QUOTATION_CHECK.CUSTOMER_PHONE, 'ยังไม่ได้กรอกเบอร์โทรลูกค้า', QUOTATION_FIELD_IDS.customerPhone);
    }
  }
  if (contact?.id) {
    if (contact.phone !== undefined && blankValue(contact.phone)) {
      push(QUOTATION_CHECK.CONTACT_PHONE, 'ผู้สั่งซื้อยังไม่มีเบอร์โทร', contactFieldId);
    }
    if (contact.email !== undefined && blankValue(contact.email)) {
      push(QUOTATION_CHECK.CONTACT_EMAIL, 'ผู้สั่งซื้อยังไม่มีอีเมล', contactFieldId);
    }
  }

  if (duplicateGroupIndex != null) {
    push(QUOTATION_CHECK.LOCATION_LABELS,
      'ชื่อตำแหน่งติดตั้งซ้ำกัน กรุณาตั้งชื่อให้ต่างกัน (ตำแหน่งที่ชื่อซ้ำจะถูกรวมเป็นตำแหน่งเดียวในเอกสาร)',
      `group-label-${duplicateGroupIndex}`);
  }
  if (priceModeLanguageConflict) {
    push(QUOTATION_CHECK.PRICE_MODE_LANGUAGE, 'เอกสารภาษาอังกฤษใช้ราคาพิเศษ บาท/ตร.ม. ไม่ได้ กรุณาเลือกวิธีกรอกราคาอื่น');
  }

  if (items.length === 0) {
    // An adjustment is a percentage of the rows ABOVE it, so a quotation that is only a
    // ส่วนลดพิเศษ is refused by the server — said specifically when that is the case.
    push(QUOTATION_CHECK.ITEMS, adjustments.length
      ? 'ส่วนลดพิเศษต้องมีรายการสินค้าอย่างน้อย 1 รายการอยู่ด้านบน'
      : 'ต้องมีรายการสินค้าอย่างน้อย 1 รายการ');
    return entries;
  }
  items.forEach((item, index) => {
    const summary = quotationItemMissingSummary(itemErrorsByRow[index], index);
    if (summary) push(QUOTATION_CHECK.ITEMS, summary, firstMissingItemFieldId(item, itemErrorsByRow[index], index));
  });
  // Adjustments print after every product row, so their "รายการที่ N" continues the count — the
  // same N the server's own 400 would name (it numbers rows after moving these last).
  adjustments.forEach((adjustment, adjIndex) => {
    const index = items.length + adjIndex;
    const summary = quotationItemMissingSummary(adjustmentErrorsByRow[adjIndex], index);
    if (summary) push(QUOTATION_CHECK.ITEMS, summary, firstMissingItemFieldId(adjustment, adjustmentErrorsByRow[adjIndex], index));
  });
  return entries;
}

/** Joins only the parts that are present — so a missing value leaves no dangling separator
 * ("คุณธนพล · โทร. 081…" with no " · " when there is no email, and '' when there is nothing). */
export function joinPresent(parts, separator = ' · ') {
  return (parts ?? []).filter((part) => !blankValue(part)).map((part) => String(part).trim()).join(separator);
}
