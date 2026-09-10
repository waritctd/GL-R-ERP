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
