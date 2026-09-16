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
//
// Owner clarification (2026-09-15): ตีกลับ ITSELF never renumbers -- the reject edge above is
// the WHOLE of "DRAFT -> (reject+reason) -> DRAFT", same row/number. Renumbering happens one step
// LATER, the next time submit() runs on that now-rejected DRAFT (approvalNote != null): it mints
// a revision of ITSELF instead of resubmitting the same row, exactly the same "new DRAFT child;
// parent -> SUPERSEDED once the child reaches APPROVED, not before" shape the APPROVED/(revise)
// edge already has -- just reached from a DRAFT parent instead of an APPROVED one. Hence DRAFT's
// own SUPERSEDED edge below (mirrors DealQuotationRepository#supersede's own WHERE clause, widened
// the same way and for the same reason).
export const DEAL_QUOTATION_TRANSITIONS = {
  DRAFT: ['PENDING_APPROVAL', 'CANCELLED', 'SUPERSEDED'],
  PENDING_APPROVAL: ['APPROVED', 'DRAFT'],
  // The APPROVED/DRAFT -> SUPERSEDED edges are the side effect of a child revision being
  // approved, not a status a caller ever requests directly (there is no "supersede" endpoint in
  // the plan).
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
  // Owner rewording (2026-09-15): "ถูกแทนที่" read as a state worth its own filter/queue; this
  // status needs neither (see QuotationListPage.jsx's own "ร่าง and ถูกแทนที่ are deliberately
  // GONE as tabs" comment -- a superseded document is history) -- "ฉบับที่ไม่ได้ใช้แล้ว" reads as
  // the plain, unremarkable end state it actually is.
  SUPERSEDED: { label: 'ฉบับที่ไม่ได้ใช้แล้ว', tone: 'neutral' },
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

// Item 4 ("ไม่รับมัดจำ", owner ruling 2026-09-16): 0% is no longer enterable as an ordinary
// percentage (see the custom-input validation in QuotationEditorPage) -- the presets stay 30/50,
// and a document with no deposit ticks the checkbox instead, which shows FULL_PAYMENT_TERM_OPTIONS
// in place of the % chips/custom input and the remainder controls below.
export const DEPOSIT_PERCENT_PRESETS = [30, 50];

export const REMAINDER_MODE_OPTIONS = [
  { code: 'CREDIT', label: 'เครดิต' },
  { code: 'ON_DELIVERY', label: 'ชำระเมื่อส่งมอบ' },
];

export function remainderModeLabel(value) {
  return REMAINDER_MODE_OPTIONS.find((o) => o.code === value)?.label ?? value ?? '-';
}

/** Item 4 ("ไม่รับมัดจำ", V181, owner ruling 2026-09-16) — mirrors
 * {@code WastageCalculator.FULL_PAYMENT_TERM_*} exactly (same three codes, same order). Shown as a
 * native `<select>` only when the "ไม่รับมัดจำ" checkbox is ticked (depositPercent resolves to 0);
 * the deposit % chips/custom input and the remainder (เครดิต/ชำระเมื่อส่งมอบ) controls are hidden
 * in that state instead. Owner correction 2026-09-16: the third option's Thai text reads
 * "เมื่อ...หรือก่อน...", the REVERSE word order of the other two options' own "ก่อน...หรือเมื่อ..."
 * phrasing -- and its code is ON_OR_BEFORE_DELIVERY, renamed from an earlier BEFORE_OR_ON_DELIVERY.
 * No credit-days option exists in this mode (owner ruling) -- only these three fixed terms. */
export const FULL_PAYMENT_TERM_OPTIONS = [
  { code: 'BEFORE_DELIVERY', label: 'บริษัทขอรับเงินค่าสินค้า 100% ก่อนส่งมอบสินค้า' },
  { code: 'ON_DELIVERY', label: 'บริษัทขอรับเงินค่าสินค้า 100% เมื่อส่งมอบสินค้า' },
  { code: 'ON_OR_BEFORE_DELIVERY', label: 'บริษัทขอรับเงินค่าสินค้า 100% เมื่อส่งมอบสินค้าหรือก่อนส่งมอบสินค้า' },
];

export function fullPaymentTermLabel(value) {
  return FULL_PAYMENT_TERM_OPTIONS.find((o) => o.code === value)?.label ?? value ?? '-';
}

export const VALIDITY_DAYS_OPTIONS = [15, 30, 45, 60];

/** V178 (owner feedback 2026-09-14): กำหนดยืนยันราคา (remark 7) as a day count from the document
 * date, or a specific calendar date — mirrors {@code WastageCalculator.VALIDITY_MODE_*} and the
 * remainderMode toggle's own shape exactly. DATE is offered only when {@link hasSpecialPricing}
 * is true — see the toggle in QuotationEditorPage's เงื่อนไข panel. */
export const VALIDITY_MODE_OPTIONS = [
  { code: 'DAYS', label: 'จำนวนวัน' },
  { code: 'DATE', label: 'ระบุวันที่' },
];

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
  // Owner rewording (2026-09-15): "แก้" -> "ฉบับแก้" -- names the DOCUMENT, not the verb, and
  // covers both cases this tab bundles: sales แก้ ฉบับที่ถูกอนุมัติแล้ว (a DRAFT revision of an
  // APPROVED document, in progress) OR ceo ตีกลับแล้วต้องแก้ (a rejected DRAFT). Same
  // needsRework=true filter, unchanged.
  { key: 'NEEDS_REWORK', label: 'ฉบับแก้', countKey: 'needsRework', params: { needsRework: true } },
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

// ── แผ่น/ตร.ม. (owner feedback 2026-09-12) ───────────────────────────────────────────────────────
// "ขณะเพิ่มสินค้า ปัจจุบันแสดงเป็น จำนวน ตรม ต่อ แผ่น ขอแค่เป็น จำนวนแผ่นต่อตารางเมตรแทน" -- a rep
// thinks in "how many pieces make up one ตร.ม.", not "how much area one piece covers", so the
// editor now shows/accepts the RECIPROCAL of what it stores. `sqmPerPiece` (ตร.ม./แผ่น) stays the
// wire/DB field verbatim -- CLAUDE.md requires stating a sales contract change explicitly and this
// one is avoidable, so the swap happens only here, at the UI edge, on the way in and out.
//
// ⚠️ These two are a DISPLAY-ONLY mirror of `WastageCalculator#piecesPerSqm`'s formula
// (`round(1/x, 2, HALF_UP)`), never a replacement for it: SPECIAL_SQM's net-per-piece figure is
// still always the SERVER's (see itemInputFromRow's own comment on that), and nothing here feeds
// money math. They exist solely so the input can render/accept "แผ่น/ตร.ม." while the value that
// actually travels to the server, and back, is still ตร.ม./แผ่น.
function round2(n) {
  return Math.round((Number(n) + Number.EPSILON) * 100) / 100;
}

/** ตร.ม./แผ่น (stored `item.sqmPerPiece`) → แผ่น/ตร.ม. (what the field shows). `null` when there
 * is nothing to invert yet, so an empty/zero field reads as empty rather than as `Infinity`. */
export function piecesPerSqmFromSqmPerPiece(sqmPerPiece) {
  const n = Number(sqmPerPiece);
  return n > 0 ? round2(1 / n) : null;
}

/**
 * แผ่น/ตร.ม. (what the rep typed) → ตร.ม./แผ่น (what gets stored in `item.sqmPerPiece`).
 * Rounded to 6dp, matching the column's own precision everywhere `sqm_per_piece` is persisted
 * (`NUMERIC(10,6)` -- V24, V165): that headroom below the 2dp the reverse direction rounds to is
 * what makes the round trip exact. Typing 16.39 stores 1/16.39 as 0.061013, and
 * `piecesPerSqmFromSqmPerPiece(0.061013)` reads back exactly 16.39, never 16.38/16.40 --
 * see quotationMeta.test.js for the fixture this is checked against.
 */
export function sqmPerPieceFromPiecesPerSqm(piecesPerSqm) {
  const n = Number(piecesPerSqm);
  if (!(n > 0)) return null;
  return Math.round((1 / n + Number.EPSILON) * 1e6) / 1e6;
}

/**
 * Thai SPECIAL_SQM only: the rep types `unitPrice` as the LIST price PER PIECE, but ราคาพิเศษ is
 * typed per ตร.ม. INCLUDING VAT, and nothing on the row said which box was which (owner feedback
 * 2026-09-14 — a rep typed a per-ตร.ม. figure into the per-piece box). This converts the list price
 * the same way ราคาพิเศษ is already read, so the rep can see the per-ตร.ม. figure to compare against
 * before typing ราคาพิเศษ: `round2(unitPrice × piecesPerSqm × 1.07)`, reusing
 * `piecesPerSqmFromSqmPerPiece` above for the reciprocal (1/sqmPerPiece rounded 2dp HALF_UP) rather
 * than inventing a second one. VAT is hardcoded at 1.07 rather than reading `vatRateForLanguage`
 * because this helper is only ever called from the Thai SPECIAL_SQM branch (English per-sqm has no
 * VAT and never calls this). DISPLAY-ONLY: never stored on the item, never sent in the payload, and
 * never fed into the printed document — see QuotationItemRow's own price-fields comment. `null`
 * when either input is missing or non-positive, so the caller renders a guidance hint instead.
 */
export function listPricePerSqmIncVat(unitPrice, sqmPerPiece) {
  const price = Number(unitPrice);
  if (!(price > 0)) return null;
  const piecesPerSqm = piecesPerSqmFromSqmPerPiece(sqmPerPiece);
  if (piecesPerSqm == null) return null;
  return round2(price * piecesPerSqm * 1.07);
}

// ⚠️ SHARED GRAMMAR (2026-09-16, owner complaint re-reported 2026-09-16, "แก้ขนาด/รหัสสินค้าเอง
// แต่ PDF ยังใช้ค่าเดิม"): this pattern and the backend's `DealQuotationLines#TWO_DIMENSIONS` MUST
// stay identical. Before 2026-09-16 they had quietly drifted -- this one already tolerated a third
// dimension and a trailing cm/ซม unit, but had no per-number unit, no decimal-comma, no mm unit and
// no trailing free text, so a size the rep typed could recompute แผ่น/ตร.ม. on screen (this parser
// accepted it) while the printed PDF still showed the linked catalogue's size (the backend's
// stricter parser rejected the same text and fell back to "keep the catalogue"). The vector table
// pinning both sides is in `quotationMeta.test.js` and `DealQuotationLinesTest` -- same inputs,
// same {width, height, unit} result -- so the two can never drift apart again without a red test.
//
// One number, one separator (x / X / × / * with optional surrounding spaces), a second number, an
// optional THIRD `separator number` (thickness, e.g. "60x60x0.9" for a 9mm tile -- ignored, never
// treated as a second dimension pair), then optional trailing free text in parentheses (e.g.
// "(หนา 9)" -- ignored). Numbers accept a decimal POINT OR COMMA ("29,7" is 29.7 -- this column has
// no thousands separators to confuse it with). Each of the first two numbers may carry its OWN
// trailing unit token, OR a single one may follow the second number (group 4) -- which is how "the
// unit once at the end" and "unit after the second number" collapse into the same grammar position
// when there is no third dimension: cm/cm./mm/mm. (English, case-insensitive) or ซม/ซม./ซ.ม. (Thai
// centimetres) / มม/มม./ม.ม. (Thai millimetres) -- see `unitFamily` below. Anything else -- a
// spelled-out shape, a comma-then-letters tail, letters before/between the numbers, only one
// number -- fails the match and yields `null` rather than a guess (a bare "60" or a product-name
// string like "JOLLY 60x60" must never resolve).
//
// Group 1 = width digits, group 2 = width's own unit token (or undefined), group 3 = height
// digits, group 4 = height's own unit token (or undefined).
//
// ⚠️ F1 (BLOCKER, 2026-09-16 review) — this pattern previously had FOUR independent
// `\s*(unit)?\s*` positions: a leading and trailing `\s*` around each optional, possibly-empty unit
// group. Because the unit group can match empty, a whitespace run of length n between two required
// tokens could be split n+1 ways between the leading and trailing `\s*`, and these splits multiply
// across the four positions — a ~degree-5 polynomial blow-up, measured on this exact (pre-fix) code
// at 389ms / 3,357ms (Node, 128 / 248 chars; the reviewer's own run measured 130ms / 4,092ms)
// against `"30" + " "×n + "x60" + " "×n + "x1" + " "×n + "!"`. Fixed by folding each position to a
// SINGLE leading `\s*` with the unit token consuming its own trailing whitespace inside the (now
// single) optional group — `\s*(?:(unit)\s*)?` — so there is exactly one way to distribute a
// whitespace run rather than n+1 (this does not change what the pattern MATCHES, only how many ways
// it can try to match it — see `MAX_SIZE_TEXT_LENGTH` below for the second, independent line of
// defense). See `quotationMeta.test.js`'s own "F1" describe block for the timing vectors that pin
// both.
const UNIT_ALTERNATION = 'cm\\.?|mm\\.?|ซ\\.?ม\\.?|ม\\.?ม\\.?';
const SIZE_PATTERN = new RegExp(
  '^\\s*(\\d+(?:[.,]\\d+)?)\\s*(?:(' + UNIT_ALTERNATION + ')\\s*)?'
  + '[xX×*]\\s*(\\d+(?:[.,]\\d+)?)\\s*(?:(' + UNIT_ALTERNATION + ')\\s*)?'
  + '(?:[xX×*]\\s*\\d+(?:[.,]\\d+)?\\s*(?:(?:' + UNIT_ALTERNATION + ')\\s*)?)?'
  + '(?:\\([^)]*\\))?\\s*$',
  'i',
);

/** ReDoS guard (F1, BLOCKER, 2026-09-16 review) — the maximum `sizeText` length `parseSizeText` will
 * attempt to match against `SIZE_PATTERN` at all. 64 comfortably covers every real "WxH[xT] [unit]
 * (note)" free-text cell in this file's own vector table with room to spare, so this never rejects a
 * genuine size — it exists purely so a pasted multi-hundred-character string can never reach the
 * matcher, as a second, independent line of defense alongside the grammar fix above. Same number,
 * same reasoning, on the backend side — `DealQuotationLines#MAX_SIZE_TEXT_LENGTH`. */
const MAX_SIZE_TEXT_LENGTH = 64;

/** F2 (HIGH, 2026-09-16 review) — every Unicode space separator (general category `Z`: NBSP, thin
 * space, ideographic space, narrow no-break space, …), the BOM/ZWNBSP (U+FEFF, category `Cf` so not
 * covered by `\p{Z}`) and the line/paragraph separators. */
const SIZE_TEXT_WHITESPACE = /[\p{Z}\uFEFF\u2028\u2029]/gu;

/**
 * F2 (HIGH, 2026-09-16 review) — folds every character `SIZE_TEXT_WHITESPACE` matches to a plain
 * ASCII space, then trims ASCII whitespace AND raw control characters from both ends. Mirrors
 * `DealQuotationLines#normalize` on the backend exactly.
 *
 * JS's own `\s`/`.trim()` already treat most Unicode space separators, and the BOM, as whitespace —
 * but NOT a bare control byte like U+0001, which Java's ASCII-only `String.trim()` (`<= U+0020`)
 * has always stripped; the explicit `[\x00-\x20]` trim below closes that gap from the JS side. The
 * OTHER direction — NBSP, U+3000, U+2009, U+202F, U+FEFF, U+2028 — used to parse HERE (JS `\s` is
 * Unicode-aware) but return `null` on the backend (Java `\s` is ASCII-only): a rep's pasted (often
 * Excel/Word-sourced) size recomputed แผ่น/ตร.ม. on screen while the printed PDF kept the catalogue
 * size, exactly the divergence this branch exists to close. Folding to plain ASCII BEFORE the shared
 * grammar ever runs, on both sides, is what keeps the two engines agreeing rather than trying to
 * reconcile two different `\s` definitions inside the pattern itself. See
 * `quotationMeta.test.js`'s own "F2" describe block for the full vector table, pinned identically in
 * `DealQuotationLinesTest`.
 */
function normalizeSizeText(text) {
  // Intentional control-character range: mirrors String#trim()'s own "<= U+0020" definition
  // (control bytes included), see this function's own doc.
  // eslint-disable-next-line no-control-regex
  return text.replace(SIZE_TEXT_WHITESPACE, ' ').replace(/^[\x00-\x20]+|[\x00-\x20]+$/g, '');
}

/** `cm`/`cm.`/`ซม`/`ซม.`/`ซ.ม.` → `'cm'`; `mm`/`mm.`/`มม`/`มม.`/`ม.ม.` → `'mm'`; no token captured
 * → `null` ("unspecified"). Thai ซ (cm) and ม (mm) never share a leading character, so a plain
 * `startsWith` is unambiguous. */
function unitFamily(token) {
  if (!token) return null;
  const t = token.toLowerCase();
  if (t.startsWith('cm')) return 'cm';
  if (t.startsWith('mm')) return 'mm';
  if (t.startsWith('ซ')) return 'cm';
  if (t.startsWith('ม')) return 'mm';
  return null;
}

/**
 * The shared size grammar — see `SIZE_PATTERN` above for the full spec (separators, per-number/
 * trailing units, decimal comma, ignored third dimension, ignored trailing parenthetical). Exported
 * so `quotationMeta.test.js` can pin the shared vector table directly against the parse RESULT
 * (same inputs, same {width, height, unit} shape as the backend's package-private
 * `DealQuotationLines#parseTwoDimensions`), not just against a derived value.
 *
 * Unit resolution: an explicit unit on EITHER number wins outright and becomes `unit`. When both
 * numbers carry one and they genuinely conflict (a shape no real rep types, e.g. "30cm x 60mm")
 * there is no sane single reading, so this falls back to `null` ("unspecified") rather than
 * silently preferring one side.
 *
 * @return `{width, height, unit}` (unit is `'cm'`, `'mm'`, or `null`), or `null` when `sizeText` is
 *     blank, exceeds `MAX_SIZE_TEXT_LENGTH`, does not match the grammar, or either number is not
 *     strictly positive.
 */
export function parseSizeText(sizeText) {
  const normalized = normalizeSizeText(sizeText ?? '');
  // F1 (ReDoS guard): reject BEFORE the regex ever runs, independent of the grammar fix above.
  if (!normalized || normalized.length > MAX_SIZE_TEXT_LENGTH) return null;
  const match = SIZE_PATTERN.exec(normalized);
  if (!match) return null;
  const width = Number(match[1].replace(',', '.'));
  const height = Number(match[3].replace(',', '.'));
  if (!(width > 0) || !(height > 0)) return null;
  const widthUnit = unitFamily(match[2]);
  const heightUnit = unitFamily(match[4]);
  let unit = widthUnit ?? heightUnit ?? null;
  if (widthUnit && heightUnit && widthUnit !== heightUnit) unit = null;
  return { width, height, unit };
}

/**
 * ขนาด (ซม.) free text → ตร.ม./แผ่น, for the auto-fill-from-size fallback (owner decision
 * 2026-09-14). A 2026-09-12 ruling ("Do not infer anything") had removed inference from this same
 * field because the column mixed centimetres and millimetres with no way to tell which a given row
 * meant. That column is now explicitly labelled ขนาด (ซม.) — the unit is DECLARED by the field
 * (defaulting to cm when unspecified, same as before 2026-09-16), not guessed by this function, so
 * the 2026-09-12 objection no longer applies to it. This is still only ever a FALLBACK: a
 * catalogue-resolved `sqmPerPiece` (`resolveTileSqmPerPiece` in QuotationItemRow.jsx) is never
 * replaced by a size-derived one, and the backend still does not parse `sizeText` at all — it
 * accepts whatever `sqmPerPiece` the editor sends.
 *
 * Reads whatever `parseSizeText`/`SIZE_PATTERN` above recognises. An UNSPECIFIED or explicit `cm`
 * unit computes cm² → m² (÷ 10000), exactly the field's pre-2026-09-16 behaviour; an explicit `mm`
 * unit computes mm² → m² (÷ 1,000,000) instead — e.g. "300x600mm" is a genuinely small 0.18 m²/piece
 * tile, not the 18 m²/piece a cm reading would wrongly imply. Rounds to 6dp, exactly like
 * `sqmPerPieceFromPiecesPerSqm` above (the NUMERIC(10,6) storage precision).
 *
 * Returns `null` for a non-positive dimension or a result outside [0.001, 10] m² per piece — the
 * same sanity bound `WastageCalculator` enforces server-side (MIN_SQM_PER_PIECE / MAX_SQM_PER_PIECE,
 * WastageCalculator.java:87-88). For an UNSPECIFIED unit this is what catches millimetres typed into
 * a field labelled cm: "600x1200" parses fine as a pair of numbers but reads (as cm, the unspecified
 * default) as 72 m²/piece, so it is rejected here rather than silently handed to the server as a
 * "reasonable" catalogue-scale tile. An EXPLICIT `mm` unit is trusted and converted properly instead
 * of being second-guessed by this same bound.
 */
export function sqmPerPieceFromSizeCm(sizeText) {
  const parsed = parseSizeText(sizeText);
  if (!parsed) return null;
  const { width, height, unit } = parsed;
  const areaM2 = unit === 'mm' ? (width * height) / 1e6 : (width * height) / 1e4;
  const sqmPerPiece = Math.round((areaM2 + Number.EPSILON) * 1e6) / 1e6;
  if (sqmPerPiece < 0.001 || sqmPerPiece > 10) return null;
  return sqmPerPiece;
}

/**
 * The `[widthCm, heightCm]` pair behind {@link compareToCatalogFaceSize}'s CATALOGUE side, without
 * `sqmPerPieceFromSizeCm`'s own area sanity bound.
 *
 * <p>⚠️ Review fix (F3, 2026-09-16): the comment this replaces claimed `catalogSizeText` "is always
 * plain cm digits with no unit token" — that is FALSE. `sizeTextFromCatalog` (QuotationItemRow.jsx)
 * has a THIRD branch, reached for the ~49 prod rows with no `width_mm`/`height_mm` at all, that falls
 * back to the catalogue's raw `sizeRaw`/`size` string verbatim — dirty free text that can (and, per
 * this repo's own "size_raw is NOT a size" note, often does) carry its own unit token, or be junk
 * that is not a size at all. Silently reading a parsed unit token as "these must be cm digits
 * anyway" would read a catalogue "600x1200 mm" as a 600cm x 1200cm tile — 100x too big, and (worse)
 * would make a rep's correctly-typed "60x120" fail to match its OWN catalogue row. So an explicit
 * unit on the catalogue side is now HONOURED (an explicit `mm` reading is converted to its actual cm
 * face size) rather than discarded; `null` ("unspecified", the normal V174 `size_cm` /
 * width_mm+height_mm-derived case) is unchanged.
 *
 * @return `[widthCm, heightCm]`, or `null` on anything `SIZE_PATTERN` does not recognise as a plain
 *     size pair (a junk `size_raw` fallback included — that is a parse failure, never a wrong
 *     reading).
 */
function parseSizeCmPair(sizeText) {
  const parsed = parseSizeText(sizeText);
  if (!parsed) return null;
  const { width, height, unit } = parsed;
  return unit === 'mm' ? [width / 10, height / 10] : [width, height];
}

/**
 * Compares `sizeText` (the rep's typed field, read via the full shared grammar — including an
 * explicit unit) against `catalogSizeText` (typically plain cm digits, but see {@link
 * parseSizeCmPair}'s own doc for the sizeRaw-fallback case where it isn't) order-insensitively.
 * Mirrors `DealQuotationLines#sizeLine`'s REFINEMENT rule (prod QT-2026-0034-1, 2026-09-15) and its
 * 2026-09-16 unit-resolution fix.
 *
 * <p><b>Unit resolution (2026-09-16):</b> `sizeText`'s parsed `unit` explicit (`'cm'` or `'mm'`)
 * checks ONLY that reading — "300x600mm" against a 60x60cm/600x600mm catalogue row is a DIFFERENT
 * tile, full stop, never re-checked against the cm reading just because it would happen to also
 * fail there. `null` ("unspecified", no unit typed) keeps the pre-2026-09-16 ambiguous-case rule
 * unchanged: check BOTH the centimetre reading (as both are typed/stored) and the millimetre
 * reading (a rep who typed the catalogue's millimetre figures straight into this cm-labelled field,
 * e.g. "600x600" against a 60x60cm/600x600mm catalogue row) — a genuine, documented ambiguity this
 * grammar does not resolve (a typed "60x120" with no unit reads as matching EITHER a 60x120
 * MILLIMETRE catalogue row or a 60x120 CENTIMETRE one; only an explicit unit token disambiguates).
 *
 * @return `true` (matches), `false` (both parsed and DIFFER), or `null` ("can't tell" — either
 *     text failed to parse as a plain size pair). The `null` case is exactly where this function
 *     diverges in spirit from `sizeLine`'s FALLBACK branch on the backend: an unparseable typed
 *     size there simply keeps printing the catalogue dims (never treated as "a different size"),
 *     which is why the two exported wrappers below read this three-way result asymmetrically
 *     rather than collapsing it to a boolean here — see their own docs.
 */
function compareToCatalogFaceSize(sizeText, catalogSizeText) {
  const typed = parseSizeText(sizeText);
  const catalog = parseSizeCmPair(catalogSizeText);
  if (!typed || !catalog) return null;
  const closeEnough = (a, b) => Math.abs(a - b) < 1e-6;
  const matchesPair = (a, b, w, h) => (closeEnough(a, w) && closeEnough(b, h)) || (closeEnough(a, h) && closeEnough(b, w));
  const { width: typedWidth, height: typedHeight, unit } = typed;
  const [catalogWidth, catalogHeight] = catalog;
  const checkCm = unit !== 'mm';
  const checkMm = unit !== 'cm';
  return (checkCm && matchesPair(typedWidth, typedHeight, catalogWidth, catalogHeight))
    || (checkMm && matchesPair(typedWidth, typedHeight, catalogWidth * 10, catalogHeight * 10));
}

/**
 * Whether `sizeText` states the SAME face size as `catalogSizeText` — see
 * {@link compareToCatalogFaceSize} for the comparison itself.
 *
 * @return `true` only when both parse AND match; `false` otherwise, INCLUDING when either text
 *     fails to parse — "matches" and "can't tell" both read as "not confirmed to match" here. Do
 *     not use this to decide whether to KEEP a catalogue-resolved value on an unparseable edit —
 *     use {@link sizeTextDiffersFromCatalogFaceSize} for that (its `false` on "can't tell" means
 *     the opposite thing this function's `false` does).
 */
export function sizeTextMatchesCatalogFaceSize(sizeText, catalogSizeText) {
  return compareToCatalogFaceSize(sizeText, catalogSizeText) === true;
}

/**
 * Whether `sizeText` is CONFIRMED to state a DIFFERENT face size than `catalogSizeText` — the
 * predicate `QuotationItemRow`'s ขนาด (ซม.) `onChange` actually needs to decide whether a
 * catalogue-resolved แผ่น/ตร.ม. (`sqmPerPieceSource === 'catalog'`) should be recomputed.
 *
 * Review fix (2026-09-15): an earlier version of this file used `!sizeTextMatchesCatalogFaceSize`
 * for that decision, which reads "can't tell" (either side unparseable) the SAME as "confirmed
 * different" — the opposite of `DealQuotationLines#sizeLine`'s own backend rule, where a blank or
 * unparseable typed size simply KEEPS the catalogue dims. That bug wiped a catalogue-resolved
 * แผ่น/ตร.ม. mid-typing (clearing the field, or a first keystroke like "3" or "30x" while retyping)
 * and, whenever `catalogSizeText` itself happened to be unparseable (a catalogue row with no
 * width/height falls back to a dirty `sizeRaw` string — see `sizeTextFromCatalog`), on EVERY ขนาด
 * edit including a plain typo fix.
 *
 * @return `true` ONLY when both texts parse to a size pair AND they differ. `false` both when they
 *     match AND when either fails to parse — "can't tell" must never trigger a recompute, exactly
 *     like the backend's FALLBACK branch never treats an unparseable typed size as "different".
 */
export function sizeTextDiffersFromCatalogFaceSize(sizeText, catalogSizeText) {
  return compareToCatalogFaceSize(sizeText, catalogSizeText) === false;
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
//
// `requireLeadTime` (owner feedback #7, 2026-09-14) is OFF by default deliberately: a draft may
// still be saved with no lead time (DealQuotationService#submit is the only backend gate), so the
// checklist that blocks บันทึกร่าง/ส่งขออนุมัติ alike (buildQuotationChecklist's `itemErrorsByRow`)
// must keep calling this with the default. Only a SUBMIT-specific caller passes `true` — see
// QuotationEditorPage's own `submitItemErrorsByRow`.
export function validateQuotationItem(item, priceMode = 'NET', documentLanguage = 'TH', { requireLeadTime = false } = {}) {
  if (lineTypeOf(item) === LINE_TYPE_PLAIN) return validatePlainItem(item);
  // English per-sqm (owner decision 2026-09-13): the USD/ตร.ม. IS the unit price, and the quantity
  // needs both box figures — DealQuotationService#requireItemComplete's perSqm branch.
  const perSqm = isEnglishPerSqm(priceMode, documentLanguage);
  const errors = {};
  if (!item?.model?.trim()) errors.model = 'กรุณาระบุรุ่น';
  if (!item?.color?.trim()) errors.color = 'กรุณาระบุสี';
  if (!item?.texture?.trim()) errors.texture = 'กรุณาระบุผิว';
  if (!item?.sizeText?.trim()) errors.sizeText = 'กรุณาระบุขนาด';
  if (!(Number(item?.thicknessMm) > 0)) errors.thicknessMm = 'กรุณาระบุความหนา (มม.)';
  if (!(Number(item?.piecesPerBox) >= 1)) errors.piecesPerBox = 'กรุณาระบุแผ่น/กล่อง';
  if (!(Number(item?.sqmPerPiece) > 0)) errors.sqmPerPiece = 'กรุณาระบุแผ่น/ตร.ม.';
  if (priceMode === 'DIRECT_NET') {
    if (!(Number(item?.directNetPrice) > 0)) errors.directNetPrice = 'กรุณาระบุราคาสุทธิ/แผ่น';
    // Optional here, but a typed one must still be positive — the server refuses a non-positive
    // unitPrice on every TILE row whatever the mode.
    if (item?.unitPrice !== '' && item?.unitPrice != null && !(Number(item.unitPrice) > 0)) {
      errors.unitPrice = 'ราคาตั้งต้องมากกว่าศูนย์';
    }
  } else if (!perSqm && !(Number(item?.unitPrice) > 0)) {
    errors.unitPrice = priceMode === 'SPECIAL_SQM' ? 'กรุณาระบุราคาตั้ง (บาท/แผ่น)' : 'กรุณาระบุราคา/หน่วย';
  }
  if (priceMode === 'SPECIAL_SQM') {
    if (!(Number(item?.specialPriceSqm) > 0)) {
      errors.specialPriceSqm = perSqm ? 'กรุณาระบุราคา (USD/ตร.ม.)' : 'กรุณาระบุราคาพิเศษ (บาท/ตร.ม.)';
    } else if (!withinDecimals(item.specialPriceSqm, 2)) errors.specialPriceSqm = 'ทศนิยมได้ไม่เกิน 2 ตำแหน่ง';
  }
  if (perSqm) {
    if (!(Number(item?.sqmPerBox) > 0)) errors.sqmPerBox = 'กรุณาระบุ ตร.ม./กล่อง';
    else if (!withinDecimals(item.sqmPerBox, 6)) errors.sqmPerBox = 'ทศนิยมได้ไม่เกิน 6 ตำแหน่ง';
    // Owner-approved "sell loose pieces" (V182): this used to also flag `roundToFullBox === false`
    // here as "defence in depth", but review (2026-09-16, F1) found that claim false and the branch
    // actively harmful — QuotationItemRow's `itemInputFromRow` already forces `roundToFullBox: true`
    // onto the wire in this mode regardless of the row's own state (the real, and only needed,
    // guard against DealQuotationService#requireBoxDataForPerSqm's 400), so this checklist entry
    // was never preventing a server rejection. What it DID do: a row ticked under NET/TH and then
    // switched to English per-sqm kept its stored `false` forever — the checkbox is disabled and
    // renders UNCHECKED here (roundToFullBoxDisabledReason), so there was no on-screen control left
    // to clear it, and the row became permanently unable to save. Fixed at the actual source instead
    // — QuotationEditorPage's `applyPriceMode` now resets `roundToFullBox` to `true` on every row the
    // moment the document reaches this mode — which makes `roundToFullBox === false` genuinely
    // unreachable here, so the branch is dropped rather than kept pointing at a state that cannot
    // occur.
  }
  if (item?.quantityMode === 'PIECES') {
    if (!(Number(item?.piecesInput) >= 1)) errors.piecesInput = 'กรุณาระบุจำนวนแผ่น';
  } else if (!(Number(item?.areaSqm) > 0)) {
    errors.areaSqm = 'กรุณาระบุพื้นที่ (ตร.ม.)';
  }
  // Owner feedback #7 (2026-09-14): mirrors DealQuotationService#requireEveryTileItemHasALeadTime
  // — SUBMIT only (see this function's own Javadoc for why the default leaves it off).
  if (requireLeadTime && (item?.leadTimeMinDays == null || item?.leadTimeMaxDays == null)) {
    errors.leadTimeMinDays = 'กรุณาระบุระยะเวลานำเข้า (วัน)';
  }
  return errors;
}

// Short Thai field labels for the per-row "รายการที่ N: ขาด X, Y" summary -- deliberately shorter
// than validateQuotationItem's own full sentences ("กรุณาระบุความหนา (มม.)" -> "ความหนา"), and in
// the same left-to-right order the item row itself lays the fields out in, so the summary reads
// in the order the rep will actually scan the row.
const QUOTATION_ITEM_FIELD_ORDER = [
  'description', 'model', 'color', 'texture', 'sizeText', 'thicknessMm', 'sqmPerPiece', 'piecesPerBox',
  'sqmPerBox', 'unitPrice', 'specialPriceSqm', 'directNetPrice', 'quantity', 'unit', 'areaSqm', 'piecesInput',
  'adjustmentPct', 'adjustmentAmount', 'leadTimeMinDays',
];
const QUOTATION_ITEM_FIELD_LABELS = {
  model: 'รุ่น', color: 'สี', texture: 'ผิว', sizeText: 'ขนาด', thicknessMm: 'ความหนา',
  sqmPerPiece: 'แผ่น/ตร.ม.', piecesPerBox: 'แผ่น/กล่อง', sqmPerBox: 'ตร.ม./กล่อง', unitPrice: 'ราคา/หน่วย',
  areaSqm: 'จำนวน (พื้นที่)', piecesInput: 'จำนวน (แผ่น)',
  // v3
  description: 'รายละเอียด', quantity: 'จำนวน', unit: 'หน่วย',
  specialPriceSqm: 'ราคาต่อ ตร.ม.', directNetPrice: 'ราคาสุทธิ/แผ่น',
  adjustmentPct: 'เปอร์เซ็นต์ส่วนลด', adjustmentAmount: 'จำนวนเงินส่วนลด',
  // #7 (2026-09-14): submit-only, see validateQuotationItem's `requireLeadTime`.
  leadTimeMinDays: 'ระยะเวลานำเข้า (วัน)',
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

/** Owner decision 2026-09-13: on an ENGLISH document SPECIAL_SQM is a USD price per square metre
 * with no VAT, and the printed quantity is square metres = boxes × ตร.ม./กล่อง — the same mode code
 * (WastageCalculator#isEnglishPerSqm), so only its label and hint change with the language. */
const PRICE_MODE_OPTION_EN_PER_SQM = {
  code: 'SPECIAL_SQM', label: 'ราคา USD/ตร.ม.',
  hint: 'กรอกราคาต่อ ตร.ม. เป็น USD (ไม่มี VAT) · พิมพ์จำนวนเป็น ตร.ม. = จำนวนกล่อง × ตร.ม./กล่อง',
};

/** SPECIAL_SQM on an English document — DealQuotationService's per-sqm branch. */
export function isEnglishPerSqm(priceMode, documentLanguage) {
  return documentLanguage === 'EN' && priceMode === 'SPECIAL_SQM';
}

// ── Owner-approved "sell loose pieces" (2026-09-16, V182) ───────────────────────────────────────

/** Mirrors {@code DealQuotationService#requireBoxDataForPerSqm}'s wording, verbatim — an English
 * per-sqm quantity is `boxes × sqmPerBox` (WastageCalculator#sqmQuantityFromBoxes), which has no
 * "loose pieces" term to express, so the option is refused together with that mode. */
export const ROUND_TO_FULL_BOX_DISABLED_PER_SQM_REASON =
  'ราคาต่อ ตร.ม. (เอกสารภาษาอังกฤษ) ต้องปัดขึ้นเต็มกล่องเสมอ ไม่รองรับการขายแผ่นไม่เต็มกล่อง';

/** Why the "ขายแผ่นไม่เต็มกล่อง" checkbox is disabled for this row right now, or `null` when it is
 * enabled — ONE place for both `QuotationItemRow`'s `disabled` attribute and its own hint text, so
 * the two can never disagree about the reason. */
export function roundToFullBoxDisabledReason(item, priceMode, documentLanguage) {
  if (!(Number(item?.piecesPerBox) >= 1)) return 'กรอกแผ่น/กล่องก่อน';
  if (isEnglishPerSqm(priceMode, documentLanguage)) return ROUND_TO_FULL_BOX_DISABLED_PER_SQM_REASON;
  return null;
}

/**
 * The "sell loose pieces" checkbox's live, plain-language summary — computed ONLY from values the
 * row already displays (`piecesPerBox`/`piecesFinal`/`boxes`, all server-computed by calculate-line
 * and stored on the row), never a second copy of the wastage/box-rounding arithmetic itself. This
 * is a short companion to `item.calculationLine` (the full printed sentence), meant to sit right
 * under the checkbox for immediate feedback as the rep toggles it.
 *
 * @return `null` before the server has computed anything for this row yet (a half-typed row, or no
 *     แผ่น/กล่อง entered) — the caller shows nothing rather than a stale or invented number.
 */
export function roundToFullBoxSummary(item) {
  const ppb = Number(item?.piecesPerBox);
  if (!(ppb >= 1)) return null;
  const piecesFinal = item?.piecesFinal;
  const boxes = item?.boxes;
  if (piecesFinal == null || boxes == null) return null;
  if (item?.roundToFullBox === false) {
    const loose = piecesFinal - boxes * ppb;
    if (boxes > 0 && loose > 0) return `${boxes} กล่อง + ${loose} แผ่น (${piecesFinal} แผ่น)`;
    if (boxes > 0) return `${boxes} กล่อง (${piecesFinal} แผ่น)`;
    return `${piecesFinal} แผ่น (ไม่ครบ 1 กล่อง)`;
  }
  return `ปัดขึ้นเต็มกล่อง → ${boxes} กล่อง (${piecesFinal} แผ่น)`;
}

/**
 * The tile price modes a document in `documentLanguage` may use. Every mode is available in both
 * languages since the owner's 2026-09-13 decision; on English, SPECIAL_SQM carries its USD/ตร.ม.
 * label (see PRICE_MODE_OPTION_EN_PER_SQM).
 */
export function availablePriceModes(documentLanguage) {
  return PRICE_MODE_OPTIONS.map((opt) => (documentLanguage === 'EN' && opt.code === 'SPECIAL_SQM'
    ? PRICE_MODE_OPTION_EN_PER_SQM : opt));
}

/**
 * Owner ruling 2026-09-13, superseding the per-sqm-only rule of the same day: "Clear all prices on
 * switch." A TH↔EN switch changes the CURRENCY every typed amount is in (and, for SPECIAL_SQM, its
 * VAT meaning), and the editor has no exchange rate — so every currency amount the rep typed is
 * cleared to `null` (the null-on-clear convention, never '') for them to re-enter, in EVERY price
 * mode, together with the money derived from it:
 *   - TILE: unitPrice (ราคาตั้ง/แผ่น), directNetPrice, specialPriceSqm
 *   - PLAIN: unitPrice
 *   - ADJUSTMENT entered as a FLAT amount: adjustmentAmount
 *   - every row's derived netUnitPrice / lineAmount / specialPriceLine (and an adjustment's echoed
 *     unitPrice, which is the same figure)
 * KEPT — nothing here is money: discountPct, a PERCENTAGE adjustment's adjustmentPct, quantities,
 * areas, sqmPerPiece / sqmPerBox / piecesPerBox, wastage, descriptions, units, deadlines.
 *
 * ⚠️ An EDITOR rule only. The server accepts whatever a PUT carries — it cannot tell a re-typed
 * price from a stale one — so nothing here is server-enforced.
 */
export function rowsWithPricesCleared(rows) {
  const derived = { netUnitPrice: null, lineAmount: null };
  return (rows ?? []).map((row) => {
    const type = lineTypeOf(row);
    if (type === LINE_TYPE_ADJUSTMENT) {
      return {
        ...row, ...derived, unitPrice: null,
        adjustmentAmount: row.adjustmentKind === 'AMOUNT' || (row.adjustmentKind == null && row.adjustmentPct == null)
          ? null : row.adjustmentAmount,
      };
    }
    if (type === LINE_TYPE_PLAIN) return { ...row, ...derived, unitPrice: null };
    return {
      ...row, ...derived, unitPrice: null, directNetPrice: null, specialPriceSqm: null, specialPriceLine: null,
    };
  });
}

/**
 * Whether a row carries the price its mode needs, so the editor's live preview has something honest
 * to show. calculate-line infers the price mode FROM THE ROW (it has no quotation), so previewing a
 * row whose own mode's price is blank would come back priced under ANOTHER mode (a ราคาพิเศษ row with
 * no ราคาพิเศษ is priced as NET off its list price) — a plausible number for a price nobody typed.
 * A DIRECT_NET row needs only its net (the list price is optional there); a Thai SPECIAL_SQM row
 * needs both its list price and its ราคาพิเศษ; the English per-sqm row needs its USD/ตร.ม.
 */
export function rowHasPriceForPreview(row, priceMode = 'NET', documentLanguage = 'TH') {
  const present = (value) => value !== '' && value != null;
  const type = lineTypeOf(row);
  if (type === LINE_TYPE_ADJUSTMENT) return true;
  if (type === LINE_TYPE_PLAIN) return present(row?.unitPrice);
  if (priceMode === 'DIRECT_NET') return present(row?.directNetPrice);
  if (priceMode === 'SPECIAL_SQM') {
    return present(row?.specialPriceSqm) && (isEnglishPerSqm(priceMode, documentLanguage) || present(row?.unitPrice));
  }
  return present(row?.unitPrice);
}

/**
 * What a language switch does to the price mode. Since the owner's 2026-09-13 decision no mode is
 * unavailable in either language, so this never moves one today; it stays the single place that
 * would, should a pairing ever be withdrawn again. Historically: a document in SPECIAL_SQM that
 * became English was MOVED to DIRECT_NET — and `moved` is true so the caller says so out loud, rather than
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
export function adjustmentDescriptionPreview(adjustment, documentLanguage = 'TH') {
  if (adjustment?.adjustmentKind === 'AMOUNT' && adjustment?.description?.trim()) {
    return adjustment.description.trim();
  }
  const pct = adjustment?.adjustmentKind === 'AMOUNT' ? null : adjustment?.adjustmentPct;
  const pctText = pct !== null && pct !== undefined && pct !== '' ? ` ${formatPlainNumber(pct)}%` : '';
  const deadline = adjustment?.adjustmentDeadline;
  const validDeadline = deadline && /^\d{4}-\d{2}-\d{2}$/.test(deadline);
  // Owner ruling 2026-09-13 (4) — DealQuotationLines#adjustmentDescription's English form:
  // "Special discount 3% for orders placed by July 31, 2026" (month name, Gregorian year).
  if (documentLanguage === 'EN') {
    const head = `Special discount${pctText}`;
    if (!validDeadline) return head;
    const [year, month, day] = deadline.split('-').map(Number);
    return `${head} for orders placed by ${ENGLISH_MONTHS[month - 1]} ${day}, ${year}`;
  }
  const head = `ส่วนลดพิเศษ${pctText}`;
  if (!validDeadline) return head;
  const [year, month, day] = deadline.split('-').map(Number);
  return `${head} สำหรับการสั่งซื้อภายใน ${String(day).padStart(2, '0')}/${String(month).padStart(2, '0')}/${year + 543}`;
}

// A literal list rather than Intl/toLocaleDateString: the backend pins Locale.US, and a browser
// locale must not be able to print a Thai or Buddhist-era month on the English preview.
const ENGLISH_MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August',
  'September', 'October', 'November', 'December'];

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
    // English per-sqm prints "Net": the USD/sqm IS the net (her QN6900933) — renderer's discountLabel.
    if (priceMode === 'SPECIAL_SQM') return documentLanguage === 'EN' ? 'Net' : special;
    if (priceMode === 'DIRECT_NET') {
      const differs = item.unitPrice != null && item.netUnitPrice != null
        && Number(item.unitPrice) !== Number(item.netUnitPrice);
      return differs ? special : 'Net';
    }
  }
  const pct = Number(item.discountPct);
  return !pct ? 'Net' : `${formatPlainNumber(pct)}%`;
}

/**
 * V178 (owner ruling 2026-09-14): remark 7's ระบุวันที่ (DATE) validity variant — and the
 * จำนวนวัน / ระบุวันที่ toggle offering it at all — is gated on the document having special
 * pricing. Mirrors {@code th.co.glr.hr.dealquotation.DealQuotationRenderAdapter#hasSpecialPricing}
 * EXACTLY: same five rules, same TILE/PLAIN/ADJUSTMENT split (via {@link lineTypeOf}), decided
 * from the DATA rather than from {@link documentDiscountLabel}'s printed word — an English
 * per-sqm SPECIAL_SQM row prints "Net" but rule (a) still counts it. A quotation has special
 * pricing when ANY row satisfies:
 *   a. priceMode SPECIAL_SQM and the row is a TILE (any TILE row counts, discount aside);
 *   b. priceMode DIRECT_NET and the row is a TILE whose netUnitPrice differs from its unitPrice
 *      (the row whose ส่วนลด cell reads พิเศษ — see documentDiscountLabel's own DIRECT_NET branch);
 *   c. priceMode NET (i.e. neither of the above) and the row is a TILE with discountPct > 0;
 *   d. the row is PLAIN with discountPct > 0;
 *   e. the row is an ADJUSTMENT (ส่วนลดพิเศษ) row.
 */
export function hasSpecialPricing(priceMode, rows) {
  const isPositive = (value) => value != null && value !== '' && Number(value) > 0;
  for (const row of rows ?? []) {
    const type = lineTypeOf(row);
    if (type === LINE_TYPE_ADJUSTMENT) return true; // (e)
    if (type === LINE_TYPE_PLAIN) {
      if (isPositive(row?.discountPct)) return true; // (d)
      continue;
    }
    // TILE (lineTypeOf's own default when lineType is null/blank)
    if (priceMode === 'SPECIAL_SQM') return true; // (a)
    if (priceMode === 'DIRECT_NET') {
      if (row?.unitPrice != null && row?.netUnitPrice != null
        && Number(row.unitPrice) !== Number(row.netUnitPrice)) {
        return true; // (b)
      }
    } else if (isPositive(row?.discountPct)) {
      return true; // (c)
    }
  }
  return false;
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
  FULL_PAYMENT_TERM: 'fullPaymentTerm',
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
  customerName: 'deal-customer-name',
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
    // Matches QuotationItemRow's `lead-${index}` input id.
    leadTimeMinDays: 'lead',
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
 * Whether the depositPercent this editor will actually SUBMIT is 0, mirroring
 * {@code DealQuotationService#isZeroDeposit} exactly. Opus review fix (2026-09-16, F2): depositPercent
 * reaches 0 on the editor two ways — ticking "ไม่รับมัดจำ" (`noDeposit`), or picking the custom
 * "อื่นๆ" percent input (`depositPercentCustom`) and typing "0" (`depositPercent`) WITHOUT ticking
 * the box. `buildUpsertPayload`'s own ternary (`terms.noDeposit ? 0 : Number(terms.depositPercent)`)
 * evaluates to depositPercent = 0 either way, so both routes must be recognised identically here —
 * exported so both {@link buildQuotationChecklist} (the visible checklist entry) and
 * QuotationEditorPage's own submit-only guard (mirroring its pre-existing hasMissingLeadTimes
 * pattern — a DRAFT still saves with this state; only #submit refuses it) share the ONE
 * computation, rather than risk the two silently drifting apart.
 */
export function isEffectiveZeroDeposit({ noDeposit = false, depositPercentCustom = false, depositPercent = '' } = {}) {
  if (noDeposit) return true;
  return depositPercentCustom && depositPercent !== '' && Number(depositPercent) === 0;
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
  // Item 4 ("ไม่รับมัดจำ", V181, owner ruling 2026-09-16) — NOT in QUOTATION_BLOCKING_CHECKS: unlike
  // ผู้สั่งซื้อ/รายการสินค้า above, DealQuotationService#create/#update accept a zero-deposit DRAFT
  // with no term chosen yet -- only #submit refuses it (this checklist's own contract is "blocks
  // ONLY when the backend already refuses the SAME state" for both บันทึกร่าง AND ส่งขออนุมัติ, and
  // there is no create/update refusal here to mirror). A visible, non-blocking reminder instead —
  // QuotationEditorPage's own SEPARATE submit-only guard (mirroring its pre-existing
  // hasMissingLeadTimes pattern) is what actually disables ส่งขออนุมัติ for this state; see
  // #isEffectiveZeroDeposit below, which both that guard and this check now share.
  noDeposit = false,
  // Opus review fix (2026-09-16, F2): depositPercent reaches 0 on this editor TWO ways — the
  // "ไม่รับมัดจำ" checkbox (`noDeposit` above) and typing "0" into the custom "อื่นๆ" input while
  // UNticked (`depositPercentCustom` + `depositPercent`). Both were previously conflated with just
  // `noDeposit`, so a rep who typed 0 without ticking the box got no checklist entry at all (and
  // no submit block — see QuotationEditorPage's now-fixed depositZeroError), even though
  // DealQuotationService#submit's isZeroDeposit() gate refuses depositPercent === 0 identically
  // regardless of which route produced it. Both new params default to the "never triggers" shape
  // so every existing caller/test that only ever passed `noDeposit` keeps behaving exactly as
  // before.
  depositPercentCustom = false,
  depositPercent = '',
  fullPaymentTerm = '',
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
  // ผู้ออกแบบ (unitCode) and ฝ่าย (deptCode) are deliberately NOT checked — owner ruling 2026-09-16,
  // "make ผู้ออกแบบ optional including ฝ่าย". The backend never required either; this list used to
  // warn "ยังไม่ได้เลือกผู้ออกแบบ", which read as a required field.

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
  if (isEffectiveZeroDeposit({ noDeposit, depositPercentCustom, depositPercent }) && blankValue(fullPaymentTerm)) {
    push(QUOTATION_CHECK.FULL_PAYMENT_TERM, 'มัดจำ 0% กรุณาเลือกเงื่อนไขการชำระเงิน', 'fullPaymentTerm');
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
