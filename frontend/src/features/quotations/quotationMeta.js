// Canonical metadata for the "direct deal quotation" aggregate (QUOTATION-V2-PLAN.md): the
// pricing-chain-BYPASS quotation. Sales adds items straight onto a quotation, types unit price +
// discount, sales_manager/ceo approves. A SIBLING of the PCR-chain CustomerQuotation
// (features/pricingRequests/pricingRequestMeta.js): do not merge the two, and do not touch that
// file or DealQuotationPanel.jsx from here.
//
// Mirrors th.co.glr.hr.dealquotation (DealQuotationService / QuotationStatus). Every predicate here
// only drives which buttons/options the UI shows; the backend re-checks everything server-side. Mock
// authz (mockApi.js) approximates the Java service and is NOT authoritative (CLAUDE.md "Mock API
// contract"). These predicates are a best-effort mirror of the plan's Authz section: verify against
// the service before relying on them for an authz claim.

// ── Status machine ───────────────────────────────────────────────────────────────────────────
// DRAFT -> (submit, sales/sales_manager) -> PENDING_APPROVAL -> (approve) -> APPROVED
//                                                              -> (reject+reason) -> DRAFT
// DRAFT -> (cancel) -> CANCELLED
// APPROVED -> (revise) -> a NEW DRAFT child; the parent becomes SUPERSEDED only once THAT child is
// itself APPROVED (the customer's last approved document stays valid until replaced). Editing is
// DRAFT-only.
//
// ตีกลับ ITSELF never renumbers: the reject edge is the whole of "DRAFT -> (reject+reason) -> DRAFT",
// same row/number. Renumbering happens the next time submit() runs on that rejected DRAFT
// (approvalNote != null): it mints a revision of ITSELF, the same "new DRAFT child; parent ->
// SUPERSEDED once the child reaches APPROVED" shape as APPROVED/(revise), reached from a DRAFT
// parent. Hence DRAFT's own SUPERSEDED edge below (mirrors DealQuotationRepository#supersede's WHERE
// clause).
//
// A PRICING_REQUEST-origin quotation's own tail is on the SAME shared map: DRAFT -> PENDING_APPROVAL
// -> (one approval, by sales_manager or ceo) -> ISSUED -> (validity_date passes) -> EXPIRED.
// `ISSUED`/`EXPIRED` are unreachable for a DEAL_DIRECT row in practice (its single-shot approve()
// only requests the APPROVED edge, and a DEAL_DIRECT quotation never expires): which edge a caller
// requests decides what a row reaches, not which edges the table allows.
import { isOwnerOrKeyer } from '../tickets/dealOwnership.js';
import { bangkokTodayIso } from '../../utils/format.js';
export const DEAL_QUOTATION_TRANSITIONS = {
  DRAFT: ['PENDING_APPROVAL', 'CANCELLED', 'SUPERSEDED'],
  PENDING_APPROVAL: ['APPROVED', 'DRAFT', 'ISSUED'],
  // The APPROVED/DRAFT -> SUPERSEDED edges are the side effect of a child revision being
  // approved, not a status a caller ever requests directly (there is no "supersede" endpoint in
  // the plan).
  APPROVED: ['SUPERSEDED'],
  SUPERSEDED: [],
  CANCELLED: [],
  ISSUED: ['EXPIRED'],
  EXPIRED: [],
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
  // "ฉบับที่ไม่ได้ใช้แล้ว": a superseded document is plain history, needing no filter/queue of its own
  // (see QuotationListPage.jsx's "ร่าง and ถูกแทนที่ are deliberately GONE as tabs" comment).
  SUPERSEDED: { label: 'ฉบับที่ไม่ได้ใช้แล้ว', tone: 'neutral' },
  CANCELLED: { label: 'ยกเลิก', tone: 'danger' },
  // Only reached by a PRICING_REQUEST-origin row (DEAL_DIRECT never expires and never separately
  // "issues": its APPROVED is terminal-until-revised).
  ISSUED: { label: 'ออกใบแล้ว', tone: 'success' },
  EXPIRED: { label: 'หมดอายุ', tone: 'neutral' },
  // The customer-response statuses after ISSUED (same wording as utils/format's quotationStatusLabel).
  SENT: { label: 'ส่งแล้ว', tone: 'info' },
  ACCEPTED: { label: 'ลูกค้ายอมรับ', tone: 'success' },
  REJECTED: { label: 'ลูกค้าปฏิเสธ', tone: 'danger' },
  REVISION_REQUESTED: { label: 'ลูกค้าขอแก้ไข', tone: 'warning' },
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

/** The per-employee "can create quotations" capability (`AuthResponse.canCreateQuotation` /
 * `hr.employee.can_create_quotation`). A CAPABILITY, not a role (App.jsx's userFromAuthResponse
 * flattens it onto `user.canCreateQuotation`). It grants "any deal", like sales_manager, for
 * create/edit/view (see DealQuotationService.requireEditAccess / requireViewAccess), but NOT
 * approve/reject, which stay sales_manager/ceo only (canApproveDealQuotation never checks this).
 * A UI hint only: the backend re-reads the grant live on every request
 * (EmployeeAuthRepository#canCreateQuotation), so forging it on the client gains nothing. */
export function hasDealQuotationGrant(user) {
  return Boolean(user?.canCreateQuotation);
}

/** Mirrors DealQuotationService.create: sales (deal owner), sales_manager (any deal), or a
 * canCreateQuotation-granted employee (any deal, regardless of role). */
export function canCreateDealQuotation(user, ticket) {
  if (!user || !ticket) return false;
  if (user.role === 'sales_manager' || hasDealQuotationGrant(user)) return true;
  return user.role === 'sales' && isOwnerOrKeyer(ticket, user.id);
}

/** The role-only half of canCreateDealQuotation, for the moment BEFORE a ticket exists
 * (QuotationEditorPage's /quotations/new with no `?ticket=`). With no deal yet to own, the "sales
 * (deal owner)" comparison can't run, but the CREATOR of a new ticket becomes its
 * `createdById`/owner (TicketService.create), so the same three audiences that get "own deal"
 * access would re-pass that check once the ticket exists. Never use this once a ticket is
 * available: canCreateDealQuotation(user, ticket) is the real, ownership-checked gate. */
export function canCreateDealQuotationStandalone(user) {
  if (!user) return false;
  return user.role === 'sales' || user.role === 'sales_manager' || hasDealQuotationGrant(user);
}

/** Mirrors DealQuotationService.update/submit/cancel/createRevision's owner gate: sales (deal
 * owner), sales_manager, or a canCreateQuotation-granted employee (the latter two "any deal").
 * Does NOT check docStatus; combine with isDealQuotationEditable / the specific transition
 * (canSubmitDealQuotation / canCancelDealQuotation / canReviseDealQuotation below). */
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

/** "APPROVED -> (revise) -> new DRAFT child": a DEAL_DIRECT quotation may be revised only when
 * APPROVED. A PRICING_REQUEST-origin quotation may be revised when ISSUED or REVISION_REQUESTED, and
 * ONLY by its owning sales rep (mirrors DealQuotationService#createRevision, which reuses the
 * recordOutcome gate there) -- sales_manager / granted employees may NOT, unlike for DEAL_DIRECT.
 * Display-only; the server re-checks. */
export function canReviseDealQuotation(user, quotation, order) {
  if (quotation?.origin === 'PRICING_REQUEST') {
    return user?.role === 'sales'
      && quotation.salesRepId != null && Number(quotation.salesRepId) === Number(user.id)
      && (quotation.docStatus === 'ISSUED' || quotation.docStatus === 'REVISION_REQUESTED');
  }
  // Once the order is confirmed from a direct quotation it is the agreed order and cannot be revised
  // (the server's createRevision answers 409). `order` is the deal's confirmation fact from
  // orderConfirmationOf: omitted = no gate asked for; null = unknown (loading / errored) -> hidden.
  if (order === null || order?.confirmed) return false;
  return canEditDealQuotation(user, quotation) && quotation?.docStatus === 'APPROVED';
}

/** Whether the deal's order has been confirmed, read from its ticket events: true once a DEAL_PROMOTED_FROM_QUOTATION
 * event exists. Returns null when the events are not loaded (unknown), which callers treat as "hide". */
export function orderConfirmationOf(events) {
  if (!Array.isArray(events)) return null;
  const promoted = events.find((e) => e.kind === 'DEAL_PROMOTED_FROM_QUOTATION');
  return { confirmed: Boolean(promoted) };
}

/** "สร้างดีลจากใบเสนอราคา": only an APPROVED direct (DEAL_DIRECT) quotation may be promoted into the
 * pipeline, by the same audience that may write it (canEditDealQuotation mirrors
 * DealQuotationService#requireEditAccess, promoteToDeal's own gate). A PRICING_REQUEST-origin
 * quotation never qualifies (it has its own confirm-order step). Whether the deal is ALREADY
 * promoted is the caller's second question (ticket.quotationOnly); the server answers a replay
 * idempotently. Display-only: the server re-checks. */
export function canPromoteDealQuotationToDeal(user, quotation) {
  return canEditDealQuotation(user, quotation)
    && quotation?.docStatus === 'APPROVED'
    && (quotation?.origin ?? 'DEAL_DIRECT') === 'DEAL_DIRECT';
}

/** "คัดลอกเป็นใบใหม่": whether `user` may open `quotation` as the starting point of a brand-new
 * quotation on a NEW deal. The ONE gate for the button and for `/quotations/new?copyFrom=`, so the
 * two cannot disagree.
 *
 * Two conditions, and no third:
 *   - the viewer may start a quotation at all — canCreateDealQuotationStandalone, the gate the
 *     "สร้างใบเสนอราคา" entry already uses, because saving a copy IS that same inline create;
 *   - the source is a direct-deal quotation. Never one written from a คำขอราคา: its prices are the
 *     CEO's decision for THAT request and are not the rep's to re-issue to another customer.
 *     Stated as "is DEAL_DIRECT", not "is not PRICING_REQUEST", so an origin added later is
 *     refused until someone decides otherwise.
 *
 * Deliberately no status condition: the source is only read, never changed, so a draft, a
 * superseded or a cancelled quotation is as good a starting point as an approved one. And no
 * ownership condition of its own: the server decides who may READ the source
 * (DealQuotationService#requireViewAccess), and a reader who may create gets their own new deal. */
export function canCopyDealQuotation(user, quotation) {
  return quotation?.origin === 'DEAL_DIRECT' && canCreateDealQuotationStandalone(user);
}

/** "เปลี่ยนลูกค้า": whether `user` may re-point `ticket`'s deal at another customer while
 * `quotation` is on the page. Mirrors TicketService.changeCustomer: requireDealOwnership (the
 * owning sales rep, sales_manager or ceo, NOT a canCreateQuotation grant holder, who may edit a
 * quotation but not rewrite the deal under it), plus the quotation is a DRAFT and a direct-deal one
 * (a pricing-request quotation's customer is the one its price was asked for). Origin is stated
 * positively, like canCopyDealQuotation, so a later origin is refused until decided. The server
 * also refuses once any OTHER quotation of the deal has left DRAFT, or a pricing request is live;
 * this cannot see that. */
export function canChangeDealCustomer(user, ticket, quotation) {
  if (!user || !ticket || !quotation) return false;
  if (quotation.docStatus !== 'DRAFT' || quotation.origin !== 'DEAL_DIRECT') return false;
  if (user.role === 'sales_manager' || user.role === 'ceo') return true;
  return user.role === 'sales' && isOwnerOrKeyer(ticket, user.id);
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

// ── PRICING_REQUEST-origin approval ─────────────────────────────────────────────────────────────
// No second approval slot: canDecideDealQuotation/canApproveDealQuotation above gate a
// PRICING_REQUEST-origin approval the same as a DEAL_DIRECT one (either role, one call). This origin
// adds only the CEO-required-when-changed gate below, which the editor uses to explain (not merely
// block) a sales_manager's disabled button.
// ─────────────────────────────────────────────────────────────────────────────────────────────

/** Whether this PRICING_REQUEST-origin quotation no longer matches the CEO's own decision — ANY
 * line's price/discount, the header price mode, or a removed CEO-linked line — mirrors
 * DealQuotationService#requireCeoApprovalIfChanged's own comparison exactly (client-side echo for
 * UI wording ONLY; the server recomputes this itself and never trusts a flag from here). Always
 * `false` for a DEAL_DIRECT quotation, which has no CEO decision to compare against. */
function dealQuotationRequiresCeoApproval(quotation) {
  if (!quotation || quotation.origin !== 'PRICING_REQUEST') return false;
  return Boolean(quotation.priceModeChangedFromCeo)
    || (quotation.items ?? []).some((item) => item.priceChangedFromCeo)
    || (quotation.itemsRemovedFromCeoCount ?? 0) > 0;
}

/** canDecideDealQuotation, further refused for a sales_manager once the quotation no longer
 * matches the CEO's decision — the one case an otherwise-eligible sales_manager may NOT approve.
 * Always agrees with canDecideDealQuotation for a ceo (the CEO's own authority is never gated by
 * this) or a DEAL_DIRECT quotation (dealQuotationRequiresCeoApproval is always false there). */
export function canApproveDealQuotationNow(user, quotation) {
  if (!canDecideDealQuotation(user, quotation)) return false;
  return user?.role === 'ceo' || !dealQuotationRequiresCeoApproval(quotation);
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

// ── ผู้รับใบเสนอราคา ──────────────────────────────────────────────────────────────────────────
// The three recipients, in S-order, each paired with TicketService#stageForQuotationRecipient's own
// quote stage (DESIGNER -> S4, OWNER -> S5, BUYER -> S8). The frontend only READS that pairing, to
// preselect the recipient from a deal already at a quote stage; the recipient -> stage MOVE is the
// server's (DealQuotationService#advanceDealToRecipientStage; mockApi mirrors it in
// advanceDealToQuotationRecipientStage). UNSPECIFIED is never offered (IA §8). Labels are Thai-only:
// pricingRequestMeta's RECIPIENT_OPTIONS carries the mixed-script "ผู้ออกแบบ (Designer)", which
// DESIGN.md §18 bans on a new control.
export const QUOTATION_RECIPIENT_OPTIONS = Object.freeze([
  Object.freeze({ code: 'DESIGNER', label: 'ผู้ออกแบบ', stage: 'QUOTE_DESIGN_SIDE' }),
  Object.freeze({ code: 'OWNER', label: 'เจ้าของโครงการ', stage: 'QUOTE_OWNER' }),
  Object.freeze({ code: 'BUYER', label: 'ผู้ซื้อ / ผู้รับเหมา', stage: 'QUOTE_BUYER' }),
]);

export function quotationRecipientOption(code) {
  return QUOTATION_RECIPIENT_OPTIONS.find((o) => o.code === code) ?? null;
}

/** ลูกค้ายอมรับ always asks first. DESIGNER/OWNER accepting makes that quotation the order and skips
 * the later parties; the BUYER is last, so nothing is skipped. */
export function acceptOutcomeConfirmCopy(recipientType) {
  const title = 'ยืนยันว่าลูกค้ายอมรับใบเสนอราคานี้';
  if (recipientType !== 'DESIGNER' && recipientType !== 'OWNER') {
    return { title, message: 'ถ้าลูกค้ายอมรับ ขั้นถัดไปคือยืนยันคำสั่งซื้อ' };
  }
  return {
    title,
    message: `ใบเสนอราคานี้ส่งถึง${quotationRecipientOption(recipientType).label} ถ้าลูกค้ายอมรับ ดีลจะใช้ใบนี้ยืนยันคำสั่งซื้อและข้ามผู้รับรายถัดไป`,
  };
}

/** ConfirmDialog copy for ปฏิเสธ / ขอแก้ไข; both ask for the customer's reason. */
export function outcomeReasonConfirmCopy(outcome) {
  const rejected = outcome === 'REJECTED';
  return {
    title: rejected ? 'ลูกค้าปฏิเสธใบเสนอราคา' : 'ลูกค้าขอแก้ไขใบเสนอราคา',
    message: 'บันทึกแล้วใบเสนอราคานี้จะไม่อยู่ในสถานะรอลูกค้าตอบรับอีก',
    tone: rejected ? 'danger' : 'default',
    reasonLabel: 'เหตุผลจากลูกค้า',
  };
}

/** Mirrors TicketService#stageForQuotationRecipient. null for UNSPECIFIED/unknown. */
export function stageForQuotationRecipient(code) {
  return quotationRecipientOption(code)?.stage ?? null;
}

/** The inverse, for flow B's preselect: a deal already AT a quote stage names its recipient.
 * '' for every other stage — the rep is then asked, never guessed for. */
export function recipientForDealStage(stage) {
  return QUOTATION_RECIPIENT_OPTIONS.find((o) => o.stage === stage)?.code ?? '';
}

/** On a NEW deal the entry channel pre-fills ผู้รับใบเสนอราคา (editable; the editor re-fills only
 * while the rep has not picked one). This pairs a channel with the party it names; it says nothing
 * about the ROUTE, which is only read from the served catalog below. '' for UNSPECIFIED / unknown:
 * the rep is asked, never guessed for. */
const RECIPIENT_FOR_ENTRY_CHANNEL = Object.freeze({
  DESIGNER_LED: 'DESIGNER',
  OWNER_DIRECT: 'OWNER',
  BUYER_DIRECT: 'BUYER',
});

export function recipientForEntryChannel(entryChannel) {
  return RECIPIENT_FOR_ENTRY_CHANNEL[entryChannel] ?? '';
}

// ── One pricing route per deal ──────────────────────────────────────────────────────────────────
// The server refuses a คำขอราคา while a live direct quotation exists (DirectQuotationLocks). The
// reverse: while the deal has a LIVE pricing request, no direct quotation may be started: the deal
// page hides ใบเสนอราคาตรง, the /quotations/new picker shows an inline notice and disables บันทึกร่าง,
// and the server 409s DealQuotationService#create with the sentence below. "Live" = any status
// except the two dead ends, so a private DRAFT and a finished QUOTATION_ACCEPTED both count. This is
// the ONE definition; every surface above (and mockApi's create) reads it from here.
export const DEAD_PRICING_REQUEST_STATUSES = Object.freeze(new Set(['CANCELLED', 'SUPERSEDED']));

export const LIVE_PRICING_REQUEST_BLOCK_MESSAGE =
  'ดีลนี้มีคำขอราคาที่ยังดำเนินการอยู่ — ใช้ใบเสนอราคาจากคำขอราคา หรือยกเลิกคำขอราคาก่อน';

// A revision keeps its parent's recipient. The editor's picker hint and the 409
// DealQuotationService#update / mockApi return are ONE sentence; the 409 names the stored party
// between the two halves, the hint does not.
const REVISION_RECIPIENT_LOCKED_HEAD = 'ฉบับแก้ไขต้องส่งถึงผู้รับเดิม';
const REVISION_RECIPIENT_LOCKED_TAIL = 'หากจะเสนอราคาให้ผู้รับรายอื่น ให้สร้างใบเสนอราคาใหม่บนดีลนี้';
export const REVISION_RECIPIENT_LOCKED_MESSAGE =
  `${REVISION_RECIPIENT_LOCKED_HEAD} — ${REVISION_RECIPIENT_LOCKED_TAIL}`;
export const revisionRecipientRefusal = (storedLabel) =>
  `${REVISION_RECIPIENT_LOCKED_HEAD} (${storedLabel}) — ${REVISION_RECIPIENT_LOCKED_TAIL}`;

/** pricingRequestId -> that request's newest PRICING_REQUEST-origin quotation, from a deal's
 * quotation list (DealQuotationDto rows) — the `quotationByRequest` salesActions.nextSalesAction
 * reads. A cancelled row is skipped: a revision draft cancelled after ⋯ แก้ใบเสนอราคา must not
 * stand in for the quotation it revised (the deal page and the request page read it the same way). */
export function newestQuotationByRequest(rows) {
  const byRequest = new Map();
  for (const q of rows) {
    if (q.origin !== 'PRICING_REQUEST' || q.pricingRequestId == null || q.docStatus === 'CANCELLED') continue;
    const held = byRequest.get(q.pricingRequestId);
    if (!held || q.id > held.id) byRequest.set(q.pricingRequestId, q);
  }
  return byRequest;
}

/** True when `pricingRequests` holds a live request ON `ticketId` (ids compared as numbers). */
export function hasLivePricingRequest(pricingRequests, ticketId) {
  if (!Array.isArray(pricingRequests) || ticketId == null) return false;
  return pricingRequests.some((pr) => pr != null
    && Number(pr.ticketId) === Number(ticketId)
    && !DEAD_PRICING_REQUEST_STATUSES.has(pr.status));
}

/** The live DEAL_DIRECT statuses: DealQuotationService's N6 predicate (DirectQuotationLocks) and
 * TicketSummaryDto.liveDirectQuotation both key on exactly these. */
const LIVE_DIRECT_QUOTATION_STATUSES = Object.freeze(new Set(['DRAFT', 'PENDING_APPROVAL', 'APPROVED']));

export function isLiveDirectQuotation(live) {
  return Boolean(live) && LIVE_DIRECT_QUOTATION_STATUSES.has(live.docStatus);
}

/**
 * V204 deal parties (docs/decisions/deal-party-model.md): which party a NEW quotation on a deal will
 * belong to. The named one (`?party=`), else — as DealQuotationService#create defaults it — the deal's
 * own customer's party, preferring the recipient's role. Null when the parties are not known.
 */
export function quotationTargetParty(parties, { partyId = null, customerId = null, recipientType = null } = {}) {
  if (!Array.isArray(parties)) return null;
  if (partyId != null) return parties.find((p) => Number(p.id) === Number(partyId)) ?? null;
  const own = parties.filter((p) => customerId != null && Number(p.customerId) === Number(customerId));
  return own.find((p) => p.role === recipientType) ?? own[0] ?? null;
}

/** The live direct quotation of ONE party (the create-time 409 is per party since V204), in the
 * `{ id, number, docStatus }` shape TicketSummaryDto.liveDirectQuotation carries, or null. */
export function liveQuotationOfParty(party) {
  if (!party || party.latestQuotationId == null) return null;
  const live = { id: party.latestQuotationId, number: party.latestQuotationNumber, docStatus: party.latestQuotationStatus };
  return isLiveDirectQuotation(live) ? live : null;
}

/** The quote route in S-order (DESIGNER → OWNER → BUYER). */
export const QUOTATION_ROUTE = QUOTATION_RECIPIENT_OPTIONS.map((option) => option.code);

function rowBlocksNewDirectQuotation(live, recipientType, dealStatus) {
  if (!isLiveDirectQuotation(live)) return false;
  if (dealStatus !== 'draft') return true;
  const liveIndex = QUOTATION_ROUTE.indexOf(live.recipientType);
  const nextIndex = QUOTATION_ROUTE.indexOf(recipientType);
  return !(live.docStatus === 'APPROVED' && liveIndex >= 0 && nextIndex > liveIndex);
}

/** The newest live row (highest id) that blocks a new quotation to `recipientType`, or null. `live` is one
 * row or every row the deal holds (the server checks all of them; the deal summary serves only the newest). */
export function blockingDirectQuotation(live, recipientType, dealStatus = 'draft') {
  return [live].flat().filter(Boolean)
    .filter((row) => rowBlocksNewDirectQuotation(row, recipientType, dealStatus))
    .reduce((newest, row) => (newest == null || (row.id ?? 0) > (newest.id ?? 0) ? row : newest), null);
}

/** N6 blocks unless every live quotation is APPROVED, addressed to a party strictly EARLIER on the route than
 * `recipientType`, and the deal is still in 'draft' (no order confirmed yet) — the server's DirectQuotationLocks rule. */
export function blocksNewDirectQuotation(live, recipientType, dealStatus = 'draft') {
  return blockingDirectQuotation(live, recipientType, dealStatus) != null;
}

/** N6's reason, word for word the server's 409: the editor disables บันทึกร่าง with it BEFORE the rep
 * reaches that refusal (DESIGN.md §14: a disabled action says why). */
function liveDirectQuotationBlockMessage(live) {
  return `ดีลนี้มีใบเสนอราคาที่ใช้งานอยู่ (${live?.number ?? '-'}) — แก้ไขฉบับนั้น หรือสร้างฉบับแก้ไขแทนการออกเลขใหม่`;
}

/** The confirm-order dialog copy for an APPROVED direct quotation (the promote endpoint), shared by
 * the quotation editor and the deal page's sticky CTA so the two never drift. `stage` is the part
 * both render in <strong>. */
export const CONFIRM_ORDER_FROM_QUOTATION_COPY = Object.freeze({
  lead: (number) => `ระบบจะนำดีลของ${number ? `ใบเสนอราคา ${number}` : 'ใบเสนอราคานี้'} เข้าสู่ขั้นตอนการขาย ที่ขั้น`,
  stage: 'ได้รับคำสั่งซื้อ',
  // Follows the quotation's deposit %: 0 takes no deposit, unknown (null) keeps the usual wording.
  tail: (depositPercent) => (depositPercent != null && Number(depositPercent) === 0
    ? 'พร้อมรายการสินค้าตามใบเสนอราคานี้ — ใบเสนอราคานี้ไม่เก็บมัดจำ สั่งสินค้าและส่งมอบได้เลย การดำเนินการนี้ย้อนกลับไม่ได้'
    : 'พร้อมรายการสินค้าตามใบเสนอราคานี้ — จากนั้นออกใบแจ้งรับมัดจำ สั่งสินค้า และส่งมอบได้ตามปกติ การดำเนินการนี้ย้อนกลับไม่ได้'),
  // The deal's OTHER open direct quotations (DRAFT / PENDING_APPROVAL), which the confirm cancels (empty when none).
  cancels: (numbers) => (numbers?.length ? `ใบเสนอราคาที่ยังเปิดอยู่ ${numbers.join(', ')} จะถูกยกเลิกอัตโนมัติ` : ''),
});

// An old-engine quotation that no longer stands (CustomerQuotationRepository#hasLiveQuotation's
// non-live set): it does not hide the request's new-engine quotation.
const DEAD_CUSTOMER_QUOTATION_STATUSES = new Set(['CANCELLED', 'SUPERSEDED', 'EXPIRED', 'REJECTED']);

/** The quotation a request's deal page and its confirm-order dialog show: the new-engine one, unless a
 * live old-engine one is open. The engines are never both live, so this is the quotation the server's
 * confirmOrder uses (the ACCEPTED new-engine one if any, else the old-engine one). */
export function shownCustomerQuotation(newEngine, legacy) {
  return newEngine && !(legacy && !DEAD_CUSTOMER_QUOTATION_STATUSES.has(legacy.docStatus)) ? newEngine : legacy ?? null;
}

// ── เอกสารแนบ (the files attached to a quotation) ─────────────────────────────────────────────
// What the quotation page DRAWS. DealQuotationAttachmentService decides who gets what, and
// DealQuotationAttachmentAuthzIntegrationTest proves it. These three only echo that rule.

/** Whether this viewer is shown a quotation's เอกสารแนบ at all. That is whoever may view a
 * quotation, except import and account. Those two open the quotation itself and are refused its
 * files, with or without the canCreateQuotation grant. */
export function canSeeDealQuotationAttachments(user) {
  return canViewDealQuotation(user) && !isDealQuotationReadOnlyViewer(user);
}

/** Whether this viewer may attach a file now. That is whoever may edit the quotation, while it is
 * a draft or awaits approval. False for a quotation that has never been saved. */
export function canAttachToDealQuotation(user, quotation) {
  return canSeeDealQuotationAttachments(user) && canEditDealQuotation(user, quotation)
    && (quotation.docStatus === 'DRAFT' || quotation.docStatus === 'PENDING_APPROVAL');
}

/** Whether this viewer may remove this file now. That is whoever may edit the quotation, while it
 * is a draft, and only a file stored on this quotation. A file inherited from an earlier document
 * is that document's. */
export function canRemoveDealQuotationAttachment(user, quotation, attachment) {
  return canSeeDealQuotationAttachments(user) && canEditDealQuotation(user, quotation)
    && isDealQuotationEditable(quotation) && !attachment?.inherited;
}

// ── Terms card options (editor) ────────────────────────────────────────────────────────────────

// "ไม่รับมัดจำ": 0% is not enterable as an ordinary percentage (see the custom-input validation in
// QuotationEditorPage). The presets stay 30/50, and a document with no deposit ticks the checkbox
// instead, which shows FULL_PAYMENT_TERM_OPTIONS in place of the % chips/custom input and the
// remainder controls below.
export const DEPOSIT_PERCENT_PRESETS = [30, 50];

export const REMAINDER_MODE_OPTIONS = [
  { code: 'CREDIT', label: 'เครดิต' },
  { code: 'ON_DELIVERY', label: 'ชำระเมื่อส่งมอบ' },
];

export function remainderModeLabel(value) {
  return REMAINDER_MODE_OPTIONS.find((o) => o.code === value)?.label ?? value ?? '-';
}

/** "ไม่รับมัดจำ": mirrors {@code WastageCalculator.FULL_PAYMENT_TERM_*} exactly (same three codes,
 * same order). Shown as a native `<select>` only when the checkbox is ticked (depositPercent
 * resolves to 0); the deposit % chips/custom input and the remainder (เครดิต/ชำระเมื่อส่งมอบ)
 * controls are hidden then. The third option reads "เมื่อ...หรือก่อน...", the REVERSE word order of
 * the other two ("ก่อน...หรือเมื่อ..."), and its code is ON_OR_BEFORE_DELIVERY. No credit-days
 * option exists in this mode: only these three fixed terms. */
export const FULL_PAYMENT_TERM_OPTIONS = [
  { code: 'BEFORE_DELIVERY', label: 'บริษัทขอรับเงินค่าสินค้า 100% ก่อนส่งมอบสินค้า' },
  { code: 'ON_DELIVERY', label: 'บริษัทขอรับเงินค่าสินค้า 100% เมื่อส่งมอบสินค้า' },
  { code: 'ON_OR_BEFORE_DELIVERY', label: 'บริษัทขอรับเงินค่าสินค้า 100% เมื่อส่งมอบสินค้าหรือก่อนส่งมอบสินค้า' },
];

export function fullPaymentTermLabel(value) {
  return FULL_PAYMENT_TERM_OPTIONS.find((o) => o.code === value)?.label ?? value ?? '-';
}

// Short forms of FULL_PAYMENT_TERM_OPTIONS for one-line summaries (same three codes, same order).
const FULL_PAYMENT_TERM_SHORT = {
  BEFORE_DELIVERY: 'ชำระเต็มจำนวนก่อนส่งมอบสินค้า',
  ON_DELIVERY: 'ชำระเต็มจำนวนเมื่อส่งมอบสินค้า',
  ON_OR_BEFORE_DELIVERY: 'ชำระเต็มจำนวนเมื่อส่งมอบสินค้าหรือก่อนส่งมอบสินค้า',
};

/**
 * One line answering "is there a deposit, and when is the rest paid?" for a direct quotation, e.g.
 * "มัดจำ 50% · ส่วนที่เหลือก่อนหรือเมื่อส่งมอบสินค้า" or "ไม่รับมัดจำ · ชำระเต็มจำนวนเมื่อส่งมอบสินค้า".
 *
 * Follows DealQuotationRenderAdapter#depositLine branch for branch, so a list row never disagrees
 * with the printed remark 2: a null percent prints the adapter's default 30%; 100% is full payment
 * up front; 0% names its full-payment term, falling back to the legacy remainderMode text when the
 * row predates the term; any other percent names CREDIT days or the before/on-delivery remainder.
 */
export function depositTermsSummary({ depositPercent, fullPaymentTerm, remainderMode, creditDays } = {}) {
  const pct = depositPercent ?? 30;
  if (pct === 100) return 'ชำระเต็มจำนวน 100% เมื่อสั่งซื้อสินค้า';
  if (pct === 0) {
    const term = FULL_PAYMENT_TERM_SHORT[fullPaymentTerm]
      ?? (remainderMode === 'CREDIT'
        ? `ชำระเต็มจำนวนเป็นเครดิต ${creditDays ?? 0} วัน`
        : 'ชำระเต็มจำนวนก่อนส่งมอบสินค้าหรือเมื่อส่งมอบสินค้า');
    return `ไม่รับมัดจำ · ${term}`;
  }
  const remainder = remainderMode === 'CREDIT'
    ? `ส่วนที่เหลือเครดิต ${creditDays ?? 0} วัน`
    : 'ส่วนที่เหลือก่อนหรือเมื่อส่งมอบสินค้า';
  return `มัดจำ ${pct}% · ${remainder}`;
}

export const VALIDITY_DAYS_OPTIONS = [15, 30, 45, 60];

/** กำหนดยืนยันราคา (remark 7) as a day count from the document date, or a specific calendar date;
 * mirrors {@code WastageCalculator.VALIDITY_MODE_*} and the remainderMode toggle's shape. DATE is
 * offered only when {@link hasSpecialPricing} is true (see the toggle in QuotationEditorPage's
 * เงื่อนไข panel). */
export const VALIDITY_MODE_OPTIONS = [
  { code: 'DAYS', label: 'จำนวนวัน' },
  { code: 'DATE', label: 'ระบุวันที่' },
];

// ── Item row options (editor) ────────────────────────────────────────────────────────────────

export const QUANTITY_MODE_OPTIONS = [
  { code: 'AREA', label: 'ตร.ม.' },
  { code: 'PIECES', label: 'แผ่น' },
];

// เผื่อ (wastage) mode switch — mirrors QUANTITY_MODE_OPTIONS' own segmented-control shape exactly
// (QuotationItemRow renders both with the same styling). PERCENT/PIECES are WastageCalculator's
// own mode codes; this is a label mapping only, not a source of truth for validation.
export const WASTAGE_MODE_OPTIONS = [
  { code: 'PERCENT', label: '%' },
  { code: 'PIECES', label: 'แผ่น' },
];

export const WASTAGE_PERCENT_PRESETS = [0, 5, 10, 15, 20];

// ประเทศต้นทาง select + its default lead-time range (min/max days), editable per line. Mirrors the
// plan's "อิตาลี/สเปน/จีน/ไทย-สต็อก/อื่นๆ" list exactly, in that order.
//
// Mirrored (NOT shared) by backend/src/main/java/th/co/glr/hr/importrequest/LeadTimeDefaults.java,
// which autofills the same four ranges onto a per-factory ใบขอซื้อ DRAFT. Two independent copies of
// the same numbers, one per runtime, with no guard against drift (see that class's Javadoc).
export const ORIGIN_COUNTRY_OPTIONS = [
  { code: 'อิตาลี', label: 'อิตาลี', leadTimeMinDays: 75, leadTimeMaxDays: 90 },
  { code: 'สเปน', label: 'สเปน', leadTimeMinDays: 75, leadTimeMaxDays: 90 },
  // Owner-set defaults: จีน 30-45 วัน, ไทย มีในสต็อก 3-7 วัน; อิตาลี/สเปน keep 75-90.
  { code: 'จีน', label: 'จีน', leadTimeMinDays: 30, leadTimeMaxDays: 45 },
  { code: 'ไทย-สต็อก', label: 'ไทย-สต็อก', leadTimeMinDays: 3, leadTimeMaxDays: 7 },
  { code: 'อื่นๆ', label: 'อื่นๆ', leadTimeMinDays: null, leadTimeMaxDays: null },
];

export function defaultLeadTimeForOrigin(originCountry) {
  const option = ORIGIN_COUNTRY_OPTIONS.find((o) => o.code === originCountry);
  return { leadTimeMinDays: option?.leadTimeMinDays ?? null, leadTimeMaxDays: option?.leadTimeMaxDays ?? null };
}

// ISO-3166 alpha-2 -> the ORIGIN_COUNTRY_OPTIONS code above, to autofill as much as possible.
// ProductPriceDto.originCountryCode is `price_catalog.factories.country` joined through
// `product_prices.factory_id` (the same base table CatalogRepository#findPricingKeys reads, never
// sales.factory_config.country). A SHORT, explicit map rather than a country-name lookup: only
// these four codes have an agreed Thai ประเทศต้นทาง option and default lead-time range, and any
// other code (a European factory outside IT/ES, say) must leave the field BLANK for the rep to
// choose rather than be silently filed under "อื่นๆ", which carries no lead time and would look
// filled in while telling the customer nothing.
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

// ── List-page status tabs ───────────────────────────────────────────────────────────────────────
// ทั้งหมด, รออนุมัติ, ฉบับแก้, ยกเลิก, อนุมัติแล้ว. `key` is what travels in the URL
// (`/quotations?status=`), so these strings are a user-visible contract: a bookmarked or shared tab
// link must keep working.
//
// ฉบับแก้ is NOT a docStatus: it is DRAFT rows that were sent back with a reason (`approvalNote`) OR
// DRAFT revisions in progress (`parentQuotationId`), so it travels as its own server-side
// `needsRework=true` filter rather than `status=DRAFT` (see DealQuotationDtos.DealQuotationCountsDto's
// javadoc). A client-side post-filter would be wrong: the server may cap the list, and filtering
// after the cap filters a different set of rows (CLAUDE.md).
export const DEAL_QUOTATION_STATUS_TABS = [
  { key: 'all', label: 'ทั้งหมด', countKey: 'all', params: {} },
  { key: 'PENDING_APPROVAL', label: 'รออนุมัติ', countKey: 'pendingApproval', params: { status: 'PENDING_APPROVAL' } },
  // "ฉบับแก้" names the DOCUMENT, not the verb, and covers both cases this tab bundles: sales แก้
  // ฉบับที่ถูกอนุมัติแล้ว (a DRAFT revision of an APPROVED document) OR ceo ตีกลับแล้วต้องแก้ (a
  // rejected DRAFT). Same needsRework=true filter.
  { key: 'NEEDS_REWORK', label: 'ฉบับแก้', countKey: 'needsRework', params: { needsRework: true } },
  { key: 'CANCELLED', label: 'ยกเลิก', countKey: 'cancelled', params: { status: 'CANCELLED' } },
  // Approved-and-beyond: ISSUED/SENT/ACCEPTED are an approved document's later life, not another bucket.
  { key: 'APPROVED', label: 'อนุมัติแล้ว', countKey: 'approved', params: { status: ['APPROVED', 'ISSUED', 'SENT', 'ACCEPTED'] } },
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

// ── Global list scope ───────────────────────────────────────────────────────────────────────────
// Mirrors DealQuotationService.listOwnerScope / LIST_SEE_ALL_ROLES: on the GLOBAL LIST only,
// sales_manager/ceo see every deal's quotations; everyone else (sales, import, account, and any
// canCreateQuotation grant-holder) sees only quotations on deals they created. A PRESENTATION HINT
// ONLY: it drives whether the rep filter dropdown is shown; the server (and mockApi.js's
// scopedDealQuotationsFor) already scoped the rows, so a wrong answer could at most show/hide a
// filter control, never leak a row. Not an authz check.

/** Whether this user's global `/quotations` list already contains every deal (so a "filter by rep"
 * control makes sense). */
export function dealQuotationListSeesAll(user) {
  return user?.role === 'sales_manager' || user?.role === 'ceo';
}

/** Distinct `{ id, name }` options for the rep filter dropdown, derived from the ROWS actually on
 * the list (not a department/role listing) -- so a non-sales grant-holder who owns a visible
 * quotation is offered as an option too, and no option is ever offered for someone who owns
 * nothing on the current list. Skips rows with no `salesRepId`, dedupes by id, and sorts by name
 * with Thai collation. */
export function dealQuotationRepOptions(rows) {
  const byId = new Map();
  (rows ?? []).forEach((row) => {
    if (row?.salesRepId == null) return;
    const id = String(row.salesRepId);
    if (!byId.has(id)) byId.set(id, { id, name: row.salesRepName ?? id });
  });
  return Array.from(byId.values()).sort((a, b) => a.name.localeCompare(b.name, 'th'));
}

/** The ยอดรวม (grand total) filter boundary: a row is "ต่ำกว่าหนึ่งล้าน" when grandTotal < this, and
 * "มากกว่าหนึ่งล้านบาท" when >= this. `>=` owns the exact 1,000,000 boundary so no row is
 * unclassifiable between the two buckets. */
const DEAL_QUOTATION_AMOUNT_THRESHOLD = 1000000;

/** The two ยอดรวม filter options offered to see-all roles (sales_manager/ceo), in display order.
 * `''` ("ทั้งหมด") is added by the caller as the leading option. */
export const DEAL_QUOTATION_AMOUNT_OPTIONS = [
  { value: 'lt1m', label: 'ต่ำกว่าหนึ่งล้าน' },
  { value: 'gte1m', label: 'มากกว่าหนึ่งล้านบาท' },
];

/** The two อีเมลลูกค้า filter options, offered to every role, in display order. `''` ("ทั้งหมด")
 * is added by the caller as the leading option. */
export const DEAL_QUOTATION_EMAIL_OPTIONS = [
  { value: 'has', label: 'มีอีเมล' },
  { value: 'none', label: 'ไม่มีอีเมล' },
];

export const DEAL_QUOTATION_PERIOD_OPTIONS = [
  { value: 'today', label: 'วันนี้' },
  { value: '3d', label: '3 วันที่แล้ว' },
  { value: '7d', label: '7 วันที่แล้ว' },
  { value: '1m', label: '1 เดือน' },
  { value: '3m', label: '3 เดือน' },
  { value: '6m', label: '6 เดือน' },
  { value: '1y', label: '1 ปี' },
  { value: '', label: 'ทั้งหมด' },
];

// ISO start date of a period ending `today` (YYYY-MM-DD); months clamp to the target month's last day.
export function dealQuotationPeriodStart(period, today) {
  const match = /^(today|(\d+)([dmy]))$/.exec(period);
  if (!match) return '';
  const [y, mo, d] = today.split('-').map(Number);
  let n = match[1] === 'today' ? 0 : Number(match[2]);
  const unit = match[3] ?? 'd';
  if (unit === 'd') return new Date(Date.UTC(y, mo - 1, d - n)).toISOString().slice(0, 10);
  if (unit === 'y') n *= 12;
  const last = new Date(Date.UTC(y, mo - 1 - n + 1, 0)).getUTCDate();
  return new Date(Date.UTC(y, mo - 1 - n, Math.min(d, last))).toISOString().slice(0, 10);
}

/** Client-side, presentation-only narrowing of an already-server-scoped row list -- NOT a
 * security boundary (see the section comment above). `repId` filters on `row.salesRepId`
 * (string-compared, so `''`/`null`/`undefined` all mean "no filter"); `text` matches
 * case-insensitively against `${customerName} ${projectName}`; `amount` is `'lt1m'` (grandTotal
 * below {@link DEAL_QUOTATION_AMOUNT_THRESHOLD}) or `'gte1m'` (at/above it), anything else meaning
 * "no filter"; `email` is `'has'` / `'none'` (see below), anything else meaning "no filter";
 * `period` is a {@link DEAL_QUOTATION_PERIOD_OPTIONS} value keeping rows whose quotationDate is
 * on/after the period's start counted back from `today` (Bangkok). All five compose with AND, each is a no-op when empty/absent, and the function is null-safe
 * throughout. */
export function filterDealQuotationRows(rows, { repId = '', text = '', amount = '', email = '', period = '', today = bangkokTodayIso() } = {}) {
  let list = rows ?? [];
  if (repId) list = list.filter((row) => String(row?.salesRepId ?? '') === String(repId));
  const needle = (text ?? '').trim().toLowerCase();
  if (needle) {
    list = list.filter((row) => `${row?.customerName ?? ''} ${row?.projectName ?? ''}`.toLowerCase().includes(needle));
  }
  if (amount === 'lt1m') {
    list = list.filter((row) => Number(row?.grandTotal ?? 0) < DEAL_QUOTATION_AMOUNT_THRESHOLD);
  } else if (amount === 'gte1m') {
    list = list.filter((row) => Number(row?.grandTotal ?? 0) >= DEAL_QUOTATION_AMOUNT_THRESHOLD);
  }
  // The customer master (customers.customer) has NO email column, so "the customer has an email"
  // means the quotation's own two snapshots: contactEmail (the customer-master contact snapshot, no
  // longer printed) or orderedByEmail (the ผู้ติดต่อ email the rep typed, which prints). Either one
  // non-blank counts. Trimmed because the contactEmail backfill copied contact.email raw, so a
  // whitespace-only value is possible on old rows.
  if (email === 'has' || email === 'none') {
    const hasEmail = (row) => Boolean((row?.contactEmail ?? '').trim() || (row?.orderedByEmail ?? '').trim());
    list = list.filter((row) => hasEmail(row) === (email === 'has'));
  }
  const start = dealQuotationPeriodStart(period, today);
  if (start) list = list.filter((row) => {
    const date = String(row?.quotationDate ?? '').slice(0, 10);
    return date !== '' && date >= start;
  });
  return list;
}

// ── ตำแหน่งติดตั้ง location groups ─────────────────────────────────────────────────────────────
// One ตำแหน่งติดตั้ง can hold multiple รายการ.
//
// THE DATA MODEL IS UNCHANGED: `location_label` stays a per-ITEM column, the wire format stays a
// flat `items` array, and the renderer prints one heading per RUN of equal labels. Grouping is an
// editor-side view over that flat list: a group is "the maximal run of consecutive items carrying
// the same label", exactly what the document prints, which makes the round-trip lossless.
//
// The editor keeps ONE flat, ordered `items` array plus a parallel ordered `groups` list, and
// maintains the invariant that items sharing a groupId are CONTIGUOUS in that array, in group order.
// Save is then `items.map(...)` with no re-sorting, and load is the reverse walk below.

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

// ── The round trip is LOSSY, so the editor prevents the lossy states ─────────────────────────────
// Two deliberately separate groups carrying the SAME label collapse into one on reload, and a group
// with no items vanishes entirely.
//
// A stable per-item group key can't fix this: it would have to survive `items[] -> server ->
// items[]`, and the wire format carries one grouping field, `locationLabel` (a per-ITEM column on
// `sales.quotation_item`), so a key means a schema AND wire change. It wouldn't help anyway:
// QuotationRenderer prints one heading per RUN of equal labels, so two adjacent same-label groups
// are literally one heading on the printed page, and an editor showing two would lie about the
// output. An empty group contributes no item rows, so it can't exist on the document.
//
// So the editor makes the ambiguity UNREACHABLE: a duplicate label is a save-blocking error, an
// empty group is a visible warning, and neither is silently swallowed. Both helpers are pure
// functions of the editor's own state.

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

// ── The "ฉบับแก้" tab's predicate ───────────────────────────────────────────────────────────────
/**
 * A DRAFT that was sent back with a reason, OR a DRAFT revision in progress. Mirrors
 * DealQuotationRepository.NEEDS_REWORK_PREDICATE:
 *
 *   (q.doc_status = 'DRAFT' AND (q.approval_note IS NOT NULL OR q.parent_quotation_id IS NOT NULL))
 *
 * A SUPERSEDED or APPROVED row with an approvalNote is NOT included: the DRAFT status is part of the
 * definition.
 *
 * ⚠️ `!= null`, NOT `Boolean(...)`. SQL's IS NOT NULL is TRUE for the empty string, so a truthiness
 * test diverges on exactly that row: a DRAFT carrying `approvalNote: ''` is IN the tab on the real
 * backend and was OUT of it in the mock (the mock shows FEWER rows than production and nobody
 * notices). `!= null` catches null and undefined and nothing else, the JS spelling of IS NOT NULL.
 *
 * Lives here, not in mockApi.js, so it can be tested as a pure predicate; mockApi imports it, like
 * canTransitionDealQuotation.
 */
export function isDealQuotationNeedingRework(row) {
  return row?.docStatus === 'DRAFT' && (row.approvalNote != null || row.parentQuotationId != null);
}

// ── CEO-comparison marker: LOCAL, unsaved-window mirror ──────────────────────────────────────────
// QuotationItemRow's "เปลี่ยนจากราคา CEO" marker used to read ONLY `item.priceChangedFromCeo`, a
// server-computed flag frozen at the last successful save. A row with validation errors never
// autosaves (QuotationEditorPage gates autosave on !hasValidationErrors), so editing ส่วนลด on it
// left the badge reading "✓ ราคาจาก CEO" beside a number the rep had just changed. This is a
// client-side mirror of the same comparison against the CURRENT (possibly unsaved) local field
// values, so the marker updates the instant the field changes.

/** Null-safe numeric equality for a CEO-comparison field. Mirrors
 * `DealQuotationRepository#moneyEquals`'s BigDecimal `compareTo` (scale-insensitive: 10 and 10.00
 * are the same value) using a tiny epsilon instead of a decimal type, since these are plain typed
 * JS numbers, not persisted NUMERIC columns — there is no scale to normalize, only float
 * representation to tolerate. Treats `''`/`null`/`undefined` uniformly as "nothing typed yet". */
function localMoneyEquals(a, b) {
  const na = a === '' || a == null ? null : Number(a);
  const nb = b === '' || b == null ? null : Number(b);
  if (na == null || nb == null) return na === nb;
  if (Number.isNaN(na) || Number.isNaN(nb)) return false;
  return Math.abs(na - nb) < 1e-6;
}

/**
 * Client-side mirror of `DealQuotationRepository#priceChangedFromCeo`, evaluated against the item's
 * CURRENT local editor fields (`unitPrice`/`discountPct`/`specialPriceSqm`/`directNetPrice`, the
 * fields QuotationItemRow's inputs `patch`) instead of what is on disk. Reuses the SAME `ceo*` fields
 * the server joined onto the item (never re-derives them) and the SAME per-mode field selection,
 * zeroIfNull convention on `discountPct`, and "unrecognised mode always reads changed" default as the
 * backend (see that method's Javadoc). `priceMode` must be the DOCUMENT's CURRENT mode
 * (`docSettings.priceMode`, possibly an unsaved local switch), not the CEO's frozen `ceoPriceMode`,
 * just as the backend compares against its own currently-SAVED price_mode.
 *
 * Callers OR this with the server's `item.priceChangedFromCeo` (never replace it) so the server flag
 * stays authoritative once a save lands: after a save, `ceo*` and the local fields are in sync, so
 * both agree; OR-ing only changes anything during the unsaved window.
 */
export function isTilePriceChangedFromCeoLocally(item, priceMode) {
  if (item.ceoListUnitPrice == null && item.ceoDiscountPct == null
      && item.ceoSpecialPriceSqm == null && item.ceoDirectNetPrice == null) {
    // Unlinked row (no decision item) — nothing to compare against, mirrors the backend's own
    // "every ceo_* column came back null" short-circuit.
    return false;
  }
  if (priceMode === 'SPECIAL_SQM') {
    return !localMoneyEquals(item.specialPriceSqm, item.ceoSpecialPriceSqm);
  }
  if (priceMode === 'DIRECT_NET') {
    // V192.2: a net typed per ตร.ม. has no per-แผ่น figure of the rep's own — the one to hold against
    // the CEO's is the SERVER's derivation (netUnitPrice, from calculate-line). Until that lands
    // it is null, which reads as "changed": the rep did just change it.
    const net = hasTypedPrice(item.directNetPriceSqm) ? item.netUnitPrice : item.directNetPrice;
    return !localMoneyEquals(net, item.ceoDirectNetPrice);
  }
  if (priceMode === 'NET') {
    // unitPrice is per แผ่น either way: the rep's own on a row priced per แผ่น, the server's
    // derivation on one priced per ตร.ม. (see usesListPriceSqm).
    return !localMoneyEquals(item.unitPrice, item.ceoListUnitPrice)
      || !localMoneyEquals(item.discountPct ?? 0, item.ceoDiscountPct ?? 0);
  }
  return true;
}

// ── ราคา/ตร.ม. รวม VAT ──────────────────────────────────────────────────────────────────────────
// On a THAI quotation every tile price box is typed per ตร.ม. INCLUDING VAT: ราคาตั้ง in all three
// price modes (`listPriceSqm`) and ราคาสุทธิ (`directNetPriceSqm`). The SERVER works out the
// per-แผ่น figure the document prints (DealQuotationService#withPerPiecePricesFromPerSqm). The
// English form is untouched: USD, no VAT, per piece.
//
// A row carries its list price in ONE of two ways, decided by a single test: is `listPriceSqm`
// filled in?
//   - yes: that is the price. `unitPrice` is then only the server's last per-แผ่น ANSWER, kept to
//     show under the box and for the readers that compare per-แผ่น figures (the CEO marker,
//     documentDiscountLabel, hasSpecialPricing). It is never sent back.
//   - no:  `unitPrice` is the price, per แผ่น (rows saved earlier, rows a คำขอราคา created, a net
//     carried across by a price-mode switch). The box SHOWS it per ตร.ม.
//     (perSqmIncVatFromPerPiece) but the row keeps sending the per-แผ่น figure, so a price nobody
//     retyped cannot move by a satang.
// ราคาสุทธิ works the same way with `directNetPriceSqm` / `directNetPrice`.

/** "Has the rep typed something here?" — '' and null/undefined are both "no". 0 is "yes": it is a
 * typed value the validator then refuses. */
export function hasTypedPrice(value) {
  return value !== '' && value != null;
}

/** Whether this row's ราคาตั้ง is the per-ตร.ม. figure in `listPriceSqm`. Never on an English
 * document, whatever a row happens to be carrying — see the block comment above. */
export function usesListPriceSqm(item, documentLanguage = 'TH') {
  return documentLanguage !== 'EN' && hasTypedPrice(item?.listPriceSqm);
}

/** The same question for ราคาสุทธิ and `directNetPriceSqm`. */
export function usesDirectNetPriceSqm(item, documentLanguage = 'TH') {
  return documentLanguage !== 'EN' && hasTypedPrice(item?.directNetPriceSqm);
}

// ── แผ่น/ตร.ม. ──────────────────────────────────────────────────────────────────────────────────
// A rep thinks in "how many pieces make up one ตร.ม.", not "how much area one piece covers", so the
// editor shows/accepts the RECIPROCAL of what it stores. `sqmPerPiece` (ตร.ม./แผ่น) stays the wire/DB
// field verbatim (a sales contract change must be stated explicitly, and this one is avoidable), so
// the swap happens only here, at the UI edge.
//
// ⚠️ These two are a DISPLAY-ONLY mirror of `WastageCalculator#piecesPerSqm`'s formula
// (`round(1/x, 2, HALF_UP)`), never a replacement: SPECIAL_SQM's net-per-piece figure is always the
// SERVER's (see itemInputFromRow), and nothing here feeds money math. They exist so the input can
// render/accept "แผ่น/ตร.ม." while the value that travels to the server and back is ตร.ม./แผ่น.
export function round2(n) {
  return Math.round((Number(n) + Number.EPSILON) * 100) / 100;
}

/** Round a piece count UP like the server's BigDecimal `ceilToInt`; the epsilon absorbs float noise
 * (`100 * (7 / 100)` is 7.000000000000001, which a bare `Math.ceil` turns into 8). */
export function ceilPieces(n) {
  return Math.ceil(n - 1e-9);
}

/** ตร.ม./แผ่น (stored `item.sqmPerPiece`) → แผ่น/ตร.ม. (what the field shows). `null` when there
 * is nothing to invert yet, so an empty/zero field reads as empty rather than as `Infinity`. */
export function piecesPerSqmFromSqmPerPiece(sqmPerPiece) {
  const n = Number(sqmPerPiece);
  return n > 0 ? round2(1 / n) : null;
}

/**
 * แผ่น/ตร.ม. (what the rep typed) → ตร.ม./แผ่น (what is stored in `item.sqmPerPiece`). Rounded to 6dp,
 * matching the column's precision (`NUMERIC(10,6)`): that headroom below the 2dp the reverse
 * direction rounds to makes the round trip exact. Typing 16.39 stores 1/16.39 as 0.061013, and
 * `piecesPerSqmFromSqmPerPiece(0.061013)` reads back exactly 16.39, never 16.38/16.40 (see
 * quotationMeta.test.js).
 */
export function sqmPerPieceFromPiecesPerSqm(piecesPerSqm) {
  const n = Number(piecesPerSqm);
  if (!(n > 0)) return null;
  return Math.round((1 / n + Number.EPSILON) * 1e6) / 1e6;
}

/**
 * A price the row holds PER PIECE (per แผ่น, before VAT), as the per-ตร.ม. INCLUDING-VAT figure a
 * Thai document's price boxes are in: `round2(perPiecePrice × piecesPerSqm × 1.07)`, reusing
 * `piecesPerSqmFromSqmPerPiece` for the reciprocal (1/sqmPerPiece rounded 2dp HALF_UP).
 *
 * Every Thai price mode shows its boxes per ตร.ม., so QuotationItemRow calls this for ราคาตั้ง
 * (`unitPrice`) and ราคาสุทธิ (`directNetPrice`) alike, whenever the row still holds that price per
 * แผ่น.
 *
 * VAT is hardcoded at 1.07 rather than `vatRateForLanguage` because the result is only shown on a
 * Thai document (the English form has no VAT). DISPLAY-ONLY: never stored on the item, never sent in
 * the payload, never fed into the printed document (see QuotationItemRow's price-fields comment).
 * `null` when either input is missing or non-positive, so the caller shows an empty box.
 */
export function perSqmIncVatFromPerPiece(perPiecePrice, sqmPerPiece) {
  const price = Number(perPiecePrice);
  if (!(price > 0)) return null;
  const piecesPerSqm = piecesPerSqmFromSqmPerPiece(sqmPerPiece);
  if (piecesPerSqm == null) return null;
  return round2(price * piecesPerSqm * 1.07);
}

// ⚠️ SHARED GRAMMAR: this pattern and the backend's `DealQuotationLines#TWO_DIMENSIONS` MUST stay
// identical. If they drift, a size the rep typed can recompute แผ่น/ตร.ม. on screen (this parser
// accepts it) while the printed PDF still shows the linked catalogue's size (the backend's stricter
// parser rejects it and falls back to "keep the catalogue"). The vector table pinning both sides is
// in `quotationMeta.test.js` and `DealQuotationLinesTest` (same inputs, same {width, height, unit}).
//
// One number, one separator (x / X / × / * with optional surrounding spaces), a second number, an
// optional THIRD `separator number` (thickness, e.g. "60x60x0.9"; ignored, never a second dimension
// pair), then optional trailing free text in parentheses (e.g. "(หนา 9)"; ignored). Numbers accept
// a decimal POINT OR COMMA ("29,7" is 29.7; this column has no thousands separators). Each of the
// first two numbers may carry its OWN trailing unit token, OR a single one may follow the second
// number (group 4): cm/cm./mm/mm. (English, case-insensitive) or ซม/ซม./ซ.ม. (Thai centimetres) /
// มม/มม./ม.ม. (Thai millimetres); see `unitFamily` below. Anything else (a spelled-out shape, a
// comma-then-letters tail, letters before/between the numbers, only one number) fails the match and
// yields `null` rather than a guess: a bare "60" or a product-name string like "JOLLY 60x60" must
// never resolve.
//
// Group 1 = width digits, group 2 = width's own unit token (or undefined), group 3 = height digits,
// group 4 = height's own unit token (or undefined).
//
// ReDoS: each position is a SINGLE leading `\s*` with the unit token consuming its own trailing
// whitespace inside the optional group, `\s*(?:(unit)\s*)?`, so a whitespace run has exactly one way
// to be distributed. Four independent leading/trailing `\s*` around possibly-empty unit groups gave
// a ~degree-5 polynomial blow-up (measured 389ms / 3,357ms at 128 / 248 chars against
// `"30" + " "×n + "x60" + " "×n + "x1" + " "×n + "!"`). `MAX_SIZE_TEXT_LENGTH` below is the second,
// independent defence. See `quotationMeta.test.js`'s "F1" describe block for the timing vectors.
const UNIT_ALTERNATION = 'cm\\.?|mm\\.?|ซ\\.?ม\\.?|ม\\.?ม\\.?';
const SIZE_PATTERN = new RegExp(
  '^\\s*(\\d+(?:[.,]\\d+)?)\\s*(?:(' + UNIT_ALTERNATION + ')\\s*)?'
  + '[xX×*]\\s*(\\d+(?:[.,]\\d+)?)\\s*(?:(' + UNIT_ALTERNATION + ')\\s*)?'
  + '(?:[xX×*]\\s*\\d+(?:[.,]\\d+)?\\s*(?:(?:' + UNIT_ALTERNATION + ')\\s*)?)?'
  + '(?:\\([^)]*\\))?\\s*$',
  'i',
);

/** ReDoS guard: the maximum `sizeText` length `parseSizeText` will attempt to match against
 * `SIZE_PATTERN`. 64 covers every real "WxH[xT] [unit] (note)" free-text cell in this file's vector
 * table, so it never rejects a genuine size; it only keeps a pasted multi-hundred-character string
 * from reaching the matcher. Same number and reason as the backend's
 * `DealQuotationLines#MAX_SIZE_TEXT_LENGTH`. */
const MAX_SIZE_TEXT_LENGTH = 64;

/** Every Unicode space separator (general category `Z`: NBSP, thin space, ideographic space, narrow
 * no-break space, …), the BOM/ZWNBSP (U+FEFF, category `Cf` so not in `\p{Z}`) and the
 * line/paragraph separators. */
const SIZE_TEXT_WHITESPACE = /[\p{Z}\uFEFF\u2028\u2029]/gu;

/**
 * Folds every character `SIZE_TEXT_WHITESPACE` matches to a plain ASCII space, then trims ASCII
 * whitespace AND raw control characters from both ends. Mirrors `DealQuotationLines#normalize` on
 * the backend exactly.
 *
 * JS's `\s`/`.trim()` already treat most Unicode space separators, and the BOM, as whitespace, but
 * NOT a bare control byte like U+0001, which Java's ASCII-only `String.trim()` (`<= U+0020`) strips;
 * the explicit `[\x00-\x20]` trim closes that gap. The OTHER direction (NBSP, U+3000, U+2009,
 * U+202F, U+FEFF, U+2028) parsed here (JS `\s` is Unicode-aware) but returned `null` on the backend
 * (Java `\s` is ASCII-only): a pasted (often Excel/Word-sourced) size recomputed แผ่น/ตร.ม. on screen
 * while the printed PDF kept the catalogue size. Folding to ASCII BEFORE the shared grammar runs, on
 * both sides, keeps the two engines agreeing. See `quotationMeta.test.js`'s "F2" describe block,
 * pinned identically in `DealQuotationLinesTest`.
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
  // Reject BEFORE the regex runs (ReDoS guard), independent of the grammar above.
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
 * ขนาด (ซม.) free text → ตร.ม./แผ่น, for the auto-fill-from-size fallback. The column is labelled
 * ขนาด (ซม.), so the unit is DECLARED by the field (cm when unspecified), not guessed. Still only a
 * FALLBACK: a catalogue-resolved `sqmPerPiece` (`resolveTileSqmPerPiece` in QuotationItemRow.jsx) is
 * never replaced by a size-derived one, and the backend does not parse `sizeText` at all (it
 * accepts whatever `sqmPerPiece` the editor sends).
 *
 * Reads whatever `parseSizeText`/`SIZE_PATTERN` recognises. An UNSPECIFIED or explicit `cm` unit
 * computes cm² → m² (÷ 10000); an explicit `mm` unit computes mm² → m² (÷ 1,000,000), so
 * "300x600mm" is a 0.18 m²/piece tile, not the 18 m²/piece a cm reading implies. Rounds to 6dp like
 * `sqmPerPieceFromPiecesPerSqm` above (NUMERIC(10,6)).
 *
 * Returns `null` for a non-positive dimension or a result outside [0.001, 10] m² per piece, the
 * sanity bound `WastageCalculator` enforces server-side (MIN_SQM_PER_PIECE / MAX_SQM_PER_PIECE).
 * For an UNSPECIFIED unit this catches millimetres typed into a cm field: "600x1200" reads (as cm)
 * as 72 m²/piece and is rejected rather than handed to the server. An EXPLICIT `mm` unit is trusted
 * and converted.
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
 * <p>`catalogSizeText` is NOT always plain cm digits: `sizeTextFromCatalog` (QuotationItemRow.jsx)
 * has a third branch, for the ~49 prod rows with no `width_mm`/`height_mm`, that falls back to the
 * catalogue's raw `sizeRaw`/`size` string verbatim: dirty free text that can carry its own unit
 * token, or be junk. Reading a parsed unit as "cm digits anyway" would read a catalogue
 * "600x1200 mm" as 600cm x 1200cm (100x too big) and make a rep's correct "60x120" fail to match its
 * OWN catalogue row. So an explicit unit on the catalogue side is HONOURED (an explicit `mm` reading
 * is converted to its cm face size); `null` ("unspecified", the normal `size_cm` /
 * width_mm+height_mm-derived case) is unchanged.
 *
 * @return `[widthCm, heightCm]`, or `null` on anything `SIZE_PATTERN` does not recognise as a plain
 *     size pair (a junk `size_raw` fallback included: a parse failure, never a wrong reading).
 */
function parseSizeCmPair(sizeText) {
  const parsed = parseSizeText(sizeText);
  if (!parsed) return null;
  const { width, height, unit } = parsed;
  return unit === 'mm' ? [width / 10, height / 10] : [width, height];
}

/**
 * Compares `sizeText` (the rep's typed field, read via the full shared grammar, including an
 * explicit unit) against `catalogSizeText` (typically plain cm digits, but see {@link
 * parseSizeCmPair} for the sizeRaw-fallback case) order-insensitively. Mirrors
 * `DealQuotationLines#sizeLine`'s REFINEMENT rule and its unit-resolution fix.
 *
 * <p><b>Unit resolution:</b> `sizeText`'s parsed `unit` explicit (`'cm'` or `'mm'`) checks ONLY that
 * reading: "300x600mm" against a 60x60cm/600x600mm catalogue row is a DIFFERENT tile, never
 * re-checked against the cm reading. `null` ("unspecified") checks BOTH the centimetre reading and
 * the millimetre reading (a rep who typed the catalogue's millimetre figures straight into this
 * cm-labelled field, e.g. "600x600" against a 60x60cm/600x600mm row): a documented ambiguity this
 * grammar doesn't resolve (a typed "60x120" matches EITHER a 60x120 MILLIMETRE row or a 60x120
 * CENTIMETRE one; only an explicit unit token disambiguates).
 *
 * @return `true` (matches), `false` (both parsed and DIFFER), or `null` ("can't tell": either text
 *     failed to parse as a plain size pair). This differs in spirit from `sizeLine`'s FALLBACK
 *     branch on the backend, where an unparseable typed size keeps printing the catalogue dims
 *     (never "a different size"), which is why the two exported wrappers below read this three-way
 *     result asymmetrically rather than collapsing it to a boolean here.
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
 * Whether `sizeText` is CONFIRMED to state a DIFFERENT face size than `catalogSizeText`: the
 * predicate `QuotationItemRow`'s ขนาด (ซม.) `onChange` needs to decide whether a catalogue-resolved
 * แผ่น/ตร.ม. (`sqmPerPieceSource === 'catalog'`) should be recomputed.
 *
 * `!sizeTextMatchesCatalogFaceSize` reads "can't tell" (either side unparseable) the SAME as
 * "confirmed different", the opposite of `DealQuotationLines#sizeLine`'s backend rule where a blank
 * or unparseable typed size KEEPS the catalogue dims. That wiped a catalogue-resolved แผ่น/ตร.ม.
 * mid-typing (clearing the field, or a first keystroke like "3" or "30x") and, when `catalogSizeText`
 * itself was unparseable (a catalogue row with no width/height falls back to a dirty `sizeRaw`; see
 * `sizeTextFromCatalog`), on EVERY ขนาด edit including a plain typo fix.
 *
 * @return `true` ONLY when both texts parse to a size pair AND they differ. `false` both when they
 *     match AND when either fails to parse: "can't tell" must never trigger a recompute.
 */
export function sizeTextDiffersFromCatalogFaceSize(sizeText, catalogSizeText) {
  return compareToCatalogFaceSize(sizeText, catalogSizeText) === false;
}

// ── Item completeness ──────────────────────────────────────────────────────────────────────────
// Autofill as much as possible when the item is in the database; sales can also fill in their own
// item, but ALL info about the tile has to be completed. A catalog pick only autofills what
// QuotationItemRow.pickCatalog copies from ProductPriceDto and never guarantees completeness (a
// catalog gap, e.g. NO_THICKNESS, affects about a third of the prod catalog), so this validates the
// SAME fields whether the row was picked from the catalog or typed by hand: a catalog pick only
// pre-fills fields, it never exempts the row from carrying them.
//
// Pure function of one item, keyed by the EXACT `item` field name (see emptyQuotationItem in
// QuotationItemRow.jsx) so a caller can render the message under that field's FormField
// (`error={errors.thicknessMm}`) and build a per-row summary from the same result (QuotationEditorPage
// does both from one call, never two checks that could drift).
//
// Optional (never validated here): ยี่ห้อ (brand), รหัสสินค้า (productCode), ตำแหน่งติดตั้ง
// (locationLabel), ประเทศต้นทาง (originCountry) + its lead-time range, หมายเหตุ (itemNotes), ส่วนลด %
// (discountPct, defaults to 0).
//
// `priceMode` makes the PRICE half mode-aware: SPECIAL_SQM also needs the ราคาพิเศษ, DIRECT_NET needs
// the net per piece and treats the list price as optional (a blank one is sent as the net itself,
// which prints "Net"; see itemInputFromRow). A PLAIN row is validated by validatePlainItem; an
// ADJUSTMENT row never reaches here.
//
// `requireLeadTime` is OFF by default deliberately: a draft may be saved with no lead time
// (DealQuotationService#submit is the only backend gate), so the checklist that blocks
// บันทึกร่าง/ส่งขออนุมัติ alike (buildQuotationChecklist's `itemErrorsByRow`) must keep the default.
// Only a SUBMIT-specific caller passes `true` (QuotationEditorPage's `submitItemErrorsByRow`).
export function validateQuotationItem(item, priceMode = 'NET', documentLanguage = 'TH',
  { requireLeadTime = false, skipPricing = false, requireOriginCountry = false } = {}) {
  if (lineTypeOf(item) === LINE_TYPE_PLAIN) return validatePlainItem(item);
  // English per-sqm: the USD/ตร.ม. IS the unit price.
  const perSqm = isEnglishPerSqm(priceMode, documentLanguage);
  // ตร.ม./กล่อง is OPTIONAL for perSqm (mirrors DealQuotationService#requireItemComplete's hasBoxArea
  // branching). WITH a box area, แผ่น/กล่อง is still required (a partially-filled pair is refused);
  // WITHOUT one, it is optional too, like any other tile row with no pieces-per-box.
  const hasBoxArea = Number(item?.sqmPerBox) > 0;
  const errors = {};
  if (!item?.model?.trim()) errors.model = 'กรุณาระบุรุ่น';
  if (!item?.color?.trim()) errors.color = 'กรุณาระบุสี';
  if (!item?.texture?.trim()) errors.texture = 'กรุณาระบุผิว';
  if (!item?.sizeText?.trim()) errors.sizeText = 'กรุณาระบุขนาด';
  if (!(Number(item?.thicknessMm) > 0)) errors.thicknessMm = 'กรุณาระบุความหนา (มม.)';
  if (!(perSqm && !hasBoxArea) && !(Number(item?.piecesPerBox) >= 1)) {
    errors.piecesPerBox = 'กรุณาระบุแผ่น/กล่อง';
  }
  if (!(Number(item?.sqmPerPiece) > 0)) errors.sqmPerPiece = 'กรุณาระบุแผ่น/ตร.ม.';
  // skipPricing (PricingRequestCreateModal): the PCR item form has NO price/discount inputs (CEO
  // pricing is a later phase), so every price check below doesn't apply. Everything ABOVE (model
  // through sqmPerPiece/piecesPerBox) and BELOW (quantity, lead time) still does: the PCR form
  // requires the same non-price fields as the direct-deal form.
  if (!skipPricing) {
    // V192.2 (sales feedback #1): on a Thai document the price a row needs is a per-ตร.ม. one. The
    // error KEYS stay `unitPrice` (the ราคาตั้ง box, `price-N`) and `directNetPrice` (the ราคาสุทธิ
    // box, `direct-net-N`) whichever unit the box is in — they name a box, and the checklist
    // jumps to it by that key. A typed per-ตร.ม. price is judged on its own: the per-แผ่น figure
    // is the server's answer and may not have arrived, or may have been refused.
    const thai = documentLanguage !== 'EN';
    const listSqm = usesListPriceSqm(item, documentLanguage);
    const netSqm = usesDirectNetPriceSqm(item, documentLanguage);
    const sqmPriceError = (value, notPositive) => {
      if (!(Number(value) > 0)) return notPositive;
      // ItemInput's @Digits(fraction = 2) — a third decimal is a 400 from bean validation.
      return withinDecimals(value, 2) ? null : 'ทศนิยมได้ไม่เกิน 2 ตำแหน่ง';
    };
    if (priceMode === 'DIRECT_NET') {
      if (netSqm) {
        const error = sqmPriceError(item.directNetPriceSqm, 'ราคาสุทธิต้องมากกว่าศูนย์');
        if (error) errors.directNetPrice = error;
      } else if (!(Number(item?.directNetPrice) > 0)) {
        errors.directNetPrice = thai ? 'กรุณาระบุราคาสุทธิ (บาท/ตร.ม.)' : 'กรุณาระบุราคาสุทธิ/แผ่น';
      }
      // Optional here, but a typed one must still be positive — the server refuses a non-positive
      // list price on every TILE row whatever the mode.
      if (listSqm) {
        const error = sqmPriceError(item.listPriceSqm, 'ราคาตั้งต้องมากกว่าศูนย์');
        if (error) errors.unitPrice = error;
      } else if (item?.unitPrice !== '' && item?.unitPrice != null && !(Number(item.unitPrice) > 0)) {
        errors.unitPrice = 'ราคาตั้งต้องมากกว่าศูนย์';
      }
    } else if (!perSqm) {
      if (listSqm) {
        const error = sqmPriceError(item.listPriceSqm,
          priceMode === 'SPECIAL_SQM' ? 'ราคาตั้งต้องมากกว่าศูนย์' : 'ราคาต้องมากกว่าศูนย์');
        if (error) errors.unitPrice = error;
      } else if (!(Number(item?.unitPrice) > 0)) {
        // An English SPECIAL_SQM row is `perSqm` and never reaches here, so the only list price
        // ราคาพิเศษ mode ever asks for is the Thai, per-ตร.ม. one.
        errors.unitPrice = priceMode === 'SPECIAL_SQM' ? 'กรุณาระบุราคาตั้ง (บาท/ตร.ม.)'
          : thai ? 'กรุณาระบุราคา/ตร.ม.' : 'กรุณาระบุราคา/หน่วย';
      }
    }
    if (priceMode === 'SPECIAL_SQM') {
      if (!(Number(item?.specialPriceSqm) > 0)) {
        errors.specialPriceSqm = perSqm ? 'กรุณาระบุราคา (USD/ตร.ม.)' : 'กรุณาระบุราคาพิเศษ (บาท/ตร.ม.)';
      } else if (!withinDecimals(item.specialPriceSqm, 2)) errors.specialPriceSqm = 'ทศนิยมได้ไม่เกิน 2 ตำแหน่ง';
    }
  }
  // ตร.ม./กล่อง is OPTIONAL: only a decimal-places check on whatever IS typed. A blank value derives
  // the printed sqm quantity from pieces × ตร.ม./แผ่น (DealQuotationLines#tilePrint).
  if (perSqm && item?.sqmPerBox !== '' && item?.sqmPerBox != null && !withinDecimals(item.sqmPerBox, 6)) {
    errors.sqmPerBox = 'ทศนิยมได้ไม่เกิน 6 ตำแหน่ง';
  }
  if (item?.quantityMode === 'PIECES') {
    if (!(Number(item?.piecesInput) >= 1)) errors.piecesInput = 'กรุณาระบุจำนวนแผ่น';
  } else if (!(Number(item?.areaSqm) > 0)) {
    errors.areaSqm = 'กรุณาระบุพื้นที่ (ตร.ม.)';
  } else if (item?.piecesBeforeWastage === 0) {
    // Mirrors DealQuotationService's AREA-mode "computes to zero pieces" refusal.
    // `piecesBeforeWastage` arrives from the debounced calculate-line preview merge
    // (QuotationEditorPage), so it is null (unknown, not yet computed) until a preview has run, never
    // mistaken for a genuine zero from an unrelated row.
    errors.areaSqm = 'พื้นที่น้อยเกินไป คำนวณได้ 0 แผ่น';
  }
  // Mirrors DealQuotationService's PIECES-wastage whole-number refusal. PERCENT wastage is untouched
  // (a percentage can be fractional: 2.5%).
  if (item?.wastageMode === 'PIECES' && item?.wastageValue !== '' && item?.wastageValue != null
    && Number.isFinite(Number(item.wastageValue)) && !Number.isInteger(Number(item.wastageValue))) {
    errors.wastageValue = 'จำนวนแผ่นที่เผื่อต้องเป็นจำนวนเต็ม';
  }
  // Mirrors DealQuotationService#requireValidLeadTime: once a lead time is ENTERED (both fields
  // present), min must be >= 1 day and max >= min. Refused on SAVE (blocking, see
  // QUOTATION_CHECK.ITEMS below), unlike the missing-lead-time check right after it, which is
  // submit-only. A lone value (the other field blank) is an incompleteness question for the
  // `requireLeadTime` branch below.
  if (item?.leadTimeMinDays != null && item?.leadTimeMaxDays != null) {
    const leadMin = Number(item.leadTimeMinDays);
    const leadMax = Number(item.leadTimeMaxDays);
    if (!(leadMin >= 1) || !(leadMax >= leadMin)) {
      errors.leadTimeMinDays = 'ระยะเวลานำเข้าต้องไม่น้อยกว่า 1 วัน และค่าสูงสุดต้องไม่น้อยกว่าค่าต่ำสุด';
    }
  }
  // Mirrors DealQuotationService#requireEveryTileItemHasALeadTime. SUBMIT only (see this function's
  // Javadoc). In-Thailand stock lines skip import, so they have no import lead time; IN_TRANSIT
  // still needs one.
  if (requireLeadTime && item?.stockSource !== 'IN_THAILAND' && (item?.leadTimeMinDays == null || item?.leadTimeMaxDays == null)) {
    errors.leadTimeMinDays = 'กรุณาระบุระยะเวลานำเข้า (วัน)';
  }
  // ประเทศต้นทาง is required on the PCR form only (requireOriginCountry); direct-deal keeps it
  // optional (default false).
  if (requireOriginCountry) {
    if (!item?.originCountry?.trim()) {
      errors.originCountry = 'กรุณาระบุประเทศต้นทาง';
    } else if (item.originCountry === 'อื่นๆ' && !item?.originCountryOther?.trim()) {
      errors.originCountryOther = 'กรุณาระบุชื่อประเทศต้นทาง';
    }
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
  'wastageValue', 'adjustmentPct', 'adjustmentAmount', 'leadTimeMinDays',
];
const QUOTATION_ITEM_FIELD_LABELS = {
  model: 'รุ่น', color: 'สี', texture: 'ผิว', sizeText: 'ขนาด', thicknessMm: 'ความหนา',
  // 'ราคา' / 'ราคาสุทธิ', with no unit: the same key is a per-ตร.ม. box on a Thai tile row (V192.2),
  // a per-piece one on an English tile row and a per-หน่วย one on a สินค้า/บริการอื่น row, and this
  // summary is built from the error keys alone.
  sqmPerPiece: 'แผ่น/ตร.ม.', piecesPerBox: 'แผ่น/กล่อง', sqmPerBox: 'ตร.ม./กล่อง', unitPrice: 'ราคา',
  areaSqm: 'จำนวน (พื้นที่)', piecesInput: 'จำนวน (แผ่น)',
  // v3
  description: 'รายละเอียด', quantity: 'จำนวน', unit: 'หน่วย',
  specialPriceSqm: 'ราคาต่อ ตร.ม.', directNetPrice: 'ราคาสุทธิ',
  adjustmentPct: 'เปอร์เซ็นต์ส่วนลด', adjustmentAmount: 'จำนวนเงินส่วนลด',
  // Submit-only, see validateQuotationItem's `requireLeadTime`.
  leadTimeMinDays: 'ระยะเวลานำเข้า (วัน)',
  // The fractional-PIECES-wastage refusal.
  wastageValue: 'เผื่อ (จำนวนแผ่น)',
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

// ── Quotation v3 modes ──────────────────────────────────────────────────────────────────────────
// The standing rule: "sales should have manual type as less as possible". A rep picks a mode ONCE
// per quotation and the rows adapt, rather than choosing per row. Mirrors
// th.co.glr.hr.dealquotation.WastageCalculator's constants (LINE_TYPE_*, PRICE_MODE_*,
// DOCUMENT_LANGUAGE_*, CURRENCY_*) and DealQuotationService's language/mode rules; the SERVICE
// enforces every one, and the UI only avoids offering what it would refuse.

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

/** VAT applies to the Thai form only (the English F-SM-008 has no VAT row at all). */
export function vatRateForLanguage(documentLanguage) {
  return documentLanguage === 'EN' ? 0 : 0.07;
}

export const PRICE_MODE_OPTIONS = [
  { code: 'NET', label: 'ราคาตั้ง − ส่วนลด %', hint: 'กรอกราคาตั้งต่อแผ่นและส่วนลด % (แบบเดิม)' },
  { code: 'SPECIAL_SQM', label: 'ราคาพิเศษ บาท/ตร.ม.', hint: 'กรอกราคาพิเศษต่อ ตร.ม. รวม VAT แล้ว ระบบคำนวณราคาสุทธิต่อแผ่นให้' },
  { code: 'DIRECT_NET', label: 'ราคาสุทธิต่อแผ่น', hint: 'กรอกราคาสุทธิต่อแผ่นตรง ๆ' },
];

/**
 * What the three modes are called on a THAI quotation: every tile price there is typed per ตร.ม.
 * including VAT, so each mode says so.
 *
 * ⚠️ Deliberately NOT an edit to PRICE_MODE_OPTIONS above: that list is also the CEO's pricing
 * screen's (PricingRequestDetailPage), where prices ARE typed per แผ่น, and the editor's own "CEO
 * เลือก: …" marker, which quotes the CEO's choice in the CEO's words. Only the quotation editor's
 * own picker (availablePriceModes) reads these.
 */
const PRICE_MODE_OPTIONS_TH = [
  { code: 'NET', label: 'ราคาตั้ง − ส่วนลด %', hint: 'กรอกราคาตั้งต่อ ตร.ม. (รวม VAT) และส่วนลด % ระบบคำนวณราคาต่อแผ่นให้' },
  { code: 'SPECIAL_SQM', label: 'ราคาพิเศษ บาท/ตร.ม.', hint: 'กรอกราคาตั้งและราคาพิเศษต่อ ตร.ม. (รวม VAT) ระบบคำนวณราคาสุทธิต่อแผ่นให้' },
  { code: 'DIRECT_NET', label: 'ราคาสุทธิ บาท/ตร.ม.', hint: 'กรอกราคาสุทธิต่อ ตร.ม. (รวม VAT) ตรง ๆ ระบบคำนวณราคาสุทธิต่อแผ่นให้' },
];

/** On an ENGLISH document SPECIAL_SQM is a USD price per square metre with no VAT, and the printed
 * quantity is square metres = boxes × ตร.ม./กล่อง; the same mode code
 * (WastageCalculator#isEnglishPerSqm), so only its label and hint change with the language. */
const PRICE_MODE_OPTION_EN_PER_SQM = {
  code: 'SPECIAL_SQM', label: 'ราคา USD/ตร.ม.',
  hint: 'กรอกราคาต่อ ตร.ม. เป็น USD (ไม่มี VAT) · พิมพ์จำนวนเป็น ตร.ม. = จำนวนกล่อง × ตร.ม./กล่อง',
};

/** SPECIAL_SQM on an English document — DealQuotationService's per-sqm branch. */
export function isEnglishPerSqm(priceMode, documentLanguage) {
  return documentLanguage === 'EN' && priceMode === 'SPECIAL_SQM';
}

// ── "Sell loose pieces" ─────────────────────────────────────────────────────────────────────────

/** Mirrors {@code DealQuotationService#requireBoxDataForPerSqm}'s wording, verbatim: a per-sqm
 * quantity WITH a box area is `boxes × sqmPerBox` (WastageCalculator#sqmQuantityFromBoxes), which
 * has no "loose pieces" term, so the option is refused for that combination. Only WITH a box area:
 * see {@link roundToFullBoxDisabledReason}. */
export const ROUND_TO_FULL_BOX_DISABLED_PER_SQM_REASON =
  'ราคาต่อ ตร.ม. (เอกสารภาษาอังกฤษ) ที่ระบุ ตร.ม./กล่อง ต้องปัดขึ้นเต็มกล่องเสมอ ไม่รองรับการขายแผ่นไม่เต็มกล่อง';

/** Why the "ขายแผ่นไม่เต็มกล่อง" checkbox is disabled for this row right now, or `null` when it is
 * enabled: ONE place for both `QuotationItemRow`'s `disabled` attribute and its hint text, so the two
 * can't disagree. English per-sqm disables it only when a box area (ตร.ม./กล่อง) is present (mirrors
 * DealQuotationService#buildTileItem's hasBoxArea branching); without one, the printed sqm quantity
 * derives from pieces, which has no box-count restriction. */
export function roundToFullBoxDisabledReason(item, priceMode, documentLanguage) {
  if (!(Number(item?.piecesPerBox) >= 1)) return 'กรอกแผ่น/กล่องก่อน';
  if (isEnglishPerSqm(priceMode, documentLanguage) && Number(item?.sqmPerBox) > 0) {
    return ROUND_TO_FULL_BOX_DISABLED_PER_SQM_REASON;
  }
  return null;
}

/**
 * The "sell loose pieces" checkbox's live, plain-language summary, computed ONLY from values the row
 * already displays (`piecesPerBox`/`piecesFinal`/`boxes`, all server-computed by calculate-line and
 * stored on the row), never a second copy of the wastage/box-rounding arithmetic. A short companion
 * to `item.calculationLine` (the full printed sentence), shown under the checkbox for immediate
 * feedback as the rep toggles it.
 *
 * @return `null` before the server has computed anything for this row (a half-typed row, or no
 *     แผ่น/กล่อง entered): the caller shows nothing rather than a stale or invented number.
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
 * languages; on English, SPECIAL_SQM carries its USD/ตร.ม. label (see PRICE_MODE_OPTION_EN_PER_SQM)
 * and the other two keep their per-piece wording, while a Thai document's three all describe a
 * per-ตร.ม. price (PRICE_MODE_OPTIONS_TH).
 */
export function availablePriceModes(documentLanguage) {
  if (documentLanguage !== 'EN') return PRICE_MODE_OPTIONS_TH;
  return PRICE_MODE_OPTIONS.map((opt) => (opt.code === 'SPECIAL_SQM' ? PRICE_MODE_OPTION_EN_PER_SQM : opt));
}

// ── Mixed price modes ───────────────────────────────────────────────────────────────────────────
// Sales asked to choose the pricing method per row (e.g. some rows special per ตร.ม., some with step
// discounts), and the rep chooses between one mode for the whole document and a mix, with an
// explanation of mixing methods in one quotation.
//
// The model is the server's: the DOCUMENT's priceMode may be 'MIXED', and then every TILE row
// carries its own (`item.priceMode`, one of the three above). A row is always priced in exactly one
// of the three; MIXED is a fact about the document, never a fourth way to price a row, which is why
// it is NOT in availablePriceModes (that list is also what a row's own picker offers).

export const PRICE_MODE_MIXED = 'MIXED';

export const ROW_PRICE_MODES = ['NET', 'SPECIAL_SQM', 'DIRECT_NET'];

/** The fourth choice in the editor's "วิธีกรอกราคากระเบื้อง" picker. The explanation travels WITH the
 * option, so no caller can show the label and drop what it means. */
export const PRICE_MODE_MIXED_OPTION = {
  code: PRICE_MODE_MIXED,
  label: 'ผสมหลายวิธี',
  hint: 'กรอกราคาได้หลายวิธีในใบเสนอราคาเดียวกัน — แต่ละรายการเลือกวิธีของตัวเองได้ที่ช่อง “วิธีคิดราคา” ของรายการนั้น เช่น บางรายการใช้ราคาพิเศษ บาท/ตร.ม. บางรายการใช้ราคาตั้ง − ส่วนลด %',
};

export function isMixedPriceMode(priceMode) {
  return priceMode === PRICE_MODE_MIXED;
}

/**
 * The price mode ONE tile row is in: the document's, unless the document is MIXED, in which case the
 * row's own. Mirrors {@code WastageCalculator#rowPriceMode} exactly, including its two edges: on a
 * single-mode document a row's own mode is never consulted (so nothing left on a row can move a
 * document whose header names one mode), and a mixed document's row with no usable mode of its own
 * reads as NET rather than as nothing.
 *
 * Everything in the editor that asks "what mode is the document in" on behalf of a row (which boxes
 * it shows, what it sends, what its ส่วนลด cell prints, whether it counts as special pricing) asks
 * this instead.
 */
export function rowPriceMode(documentPriceMode, item) {
  if (documentPriceMode !== PRICE_MODE_MIXED) return documentPriceMode;
  return ROW_PRICE_MODES.includes(item?.priceMode) ? item.priceMode : 'NET';
}

/**
 * Whether the editor offers "ผสมหลายวิธี" at all. Mirrors
 * {@code DealQuotationService#requirePriceModeAllowed}: Thai documents only (on English, ราคา
 * USD/ตร.ม. changes a row's unit and quantity, not just its price), and never a quotation that came
 * from a คำขอราคา (its mode is the CEO's decision). A UI courtesy — the server is what refuses.
 */
export function mixedPriceModeAvailable(documentLanguage, origin) {
  return documentLanguage !== 'EN' && origin !== 'PRICING_REQUEST';
}

/** Whether a document in `documentLanguage` may be in `priceMode` — the three row modes per
 * {@link availablePriceModes}, and MIXED on a Thai document. */
export function isPriceModeAvailable(priceMode, documentLanguage) {
  if (priceMode === PRICE_MODE_MIXED) return documentLanguage !== 'EN';
  return availablePriceModes(documentLanguage).some((opt) => opt.code === priceMode);
}

/**
 * "ราคาตั้ง − ส่วนลด % 2 รายการ · ราคาพิเศษ บาท/ตร.ม. 1 รายการ" — how many tile rows of a mixed
 * document use each mode, in the order the modes are offered. The line under the mixed option, so
 * the rep can see the mix at a glance without scrolling the rows.
 */
export function summarizeRowPriceModes(rows, documentLanguage = 'TH') {
  const tiles = (rows ?? []).filter((row) => lineTypeOf(row) === LINE_TYPE_TILE);
  if (tiles.length === 0) return 'ยังไม่มีรายการกระเบื้อง';
  return availablePriceModes(documentLanguage)
    .map((opt) => ({ opt, count: tiles.filter((row) => rowPriceMode(PRICE_MODE_MIXED, row) === opt.code).length }))
    .filter(({ count }) => count > 0)
    .map(({ opt, count }) => `${opt.label} ${count} รายการ`)
    .join(' · ');
}

/**
 * "Clear all prices on switch." A TH↔EN switch changes the CURRENCY every typed amount is in (and,
 * for SPECIAL_SQM, its VAT meaning), and the editor has no exchange rate, so every currency amount
 * the rep typed is cleared to `null` (the null-on-clear convention, never '') for them to re-enter,
 * in EVERY price mode, together with the money derived from it:
 *   - TILE: unitPrice (ราคาตั้ง/แผ่น), directNetPrice, specialPriceSqm, and the two per-ตร.ม.
 *     prices a Thai row is typed in (listPriceSqm, directNetPriceSqm)
 *   - PLAIN: unitPrice
 *   - ADJUSTMENT entered as a FLAT amount: adjustmentAmount
 *   - every row's derived netUnitPrice / lineAmount / specialPriceLine (and an adjustment's echoed
 *     unitPrice, which is the same figure)
 * KEPT, as none of it is money: discountPct, a PERCENTAGE adjustment's adjustmentPct, quantities,
 * areas, sqmPerPiece / sqmPerBox / piecesPerBox, wastage, descriptions, units, deadlines.
 *
 * ⚠️ An EDITOR rule only. The server accepts whatever a PUT carries (it can't tell a re-typed price
 * from a stale one), so nothing here is server-enforced.
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
      listPriceSqm: null, directNetPriceSqm: null,
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
 *
 * V192.2: a Thai row's list price and net may each be the per-ตร.ม. figure instead — and THAT one
 * is a price only on a row with an area per piece. The server refuses to turn a per-ตร.ม. price
 * into a per-แผ่น one without it (DealQuotationService#withPerPiecePricesFromPerSqm), on this
 * preview path too, so there is nothing honest to ask for.
 */
export function rowHasPriceForPreview(row, priceMode = 'NET', documentLanguage = 'TH') {
  const type = lineTypeOf(row);
  if (type === LINE_TYPE_ADJUSTMENT) return true;
  if (type === LINE_TYPE_PLAIN) return hasTypedPrice(row?.unitPrice);
  const hasArea = Number(row?.sqmPerPiece) > 0;
  const hasList = usesListPriceSqm(row, documentLanguage) ? hasArea : hasTypedPrice(row?.unitPrice);
  if (priceMode === 'DIRECT_NET') {
    const hasNet = usesDirectNetPriceSqm(row, documentLanguage) ? hasArea : hasTypedPrice(row?.directNetPrice);
    // The list price is optional here — but a per-ตร.ม. one, when given, travels with the net and
    // needs the same area to mean anything.
    return hasNet && (!usesListPriceSqm(row, documentLanguage) || hasArea);
  }
  if (priceMode === 'SPECIAL_SQM') {
    return hasTypedPrice(row?.specialPriceSqm) && (isEnglishPerSqm(priceMode, documentLanguage) || hasList);
  }
  return hasList;
}

/**
 * What a language switch does to the price mode. No mode is unavailable in either language, so this
 * never moves one today; it stays the single place that would, should a pairing be withdrawn. A
 * document in SPECIAL_SQM that became English used to be MOVED to DIRECT_NET (the mode that still
 * lets the rep state a net per piece, which is what a ราคาพิเศษ document expressed), and `moved` is
 * true so the caller says so out loud rather than changing the mode silently.
 */
export function priceModeForLanguage(priceMode, documentLanguage) {
  if (isPriceModeAvailable(priceMode, documentLanguage)) return { priceMode, moved: false };
  // V192.5: a MIXED document becoming English has no single mode to fall back on — and its prices
  // are all being cleared by the switch anyway — so it lands on the default, ราคาตั้ง − ส่วนลด %,
  // for the whole document. `moved` is true so the caller says so.
  if (priceMode === PRICE_MODE_MIXED) return { priceMode: 'NET', moved: true };
  return { priceMode: 'DIRECT_NET', moved: true };
}

/**
 * The controlled หน่วย list for a PLAIN row. The backend accepts any string up to 30 characters
 * (ItemInput.unit is `@Size(max = 30)`, matching sales.quotation_item.raw_unit), so this list is a UI
 * choice, not a server rule: every unit the owner's documents print, split by the language of the
 * form they appear on (JOB/Bags/Barrels are from her English samples). The current document's
 * language is listed first; the other stays available because a Thai document can carry "SQM" and
 * vice versa.
 */
export const PLAIN_UNIT_OPTIONS = {
  TH: ['แผ่น', 'ตร.ม.', 'กล่อง', 'ชุด', 'ชิ้น', 'ถุง', 'ถัง', 'งาน'],
  EN: ['JOB', 'SQM', 'Bags', 'Barrels'],
};

/**
 * One-click starting points for the PLAIN rows the owner's documents carry, so the commonest
 * non-tile lines take a click and a price rather than a typed sentence. Only offered on a blank row
 * (the component decides that); each fills description + unit (+ quantity 1 for a one-off service)
 * and nothing the rep has not seen.
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

/** `$` for USD rather than `US$`: the column heading already says "Amount (USD)", the settings block
 * names the currency, and the เป็นเงิน column floor (QuotationDocumentView's ITEM_GRID) was measured
 * for a one-glyph symbol. A negative amount (an ADJUSTMENT row's เป็นเงิน) prints the sign BEFORE the
 * symbol ("-฿38,198.21"), not "฿-38,198.21". */
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
  // DealQuotationLines#adjustmentDescription's English form: "Special discount 3% for orders placed
  // by July 31, 2026" (month name, Gregorian year).
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
 * edits (once saved, the editor shows the server's own figure; see QuotationAdjustmentRow).
 *
 * `calculate-line` is a single-row preview with no quotation, so it has no "rows above" to take a
 * percentage OF (DealQuotationService#calculateLine passes a ZERO base) and would answer 0 for every
 * percentage adjustment. The estimate is `base × pct / 100` rounded half-up to satang
 * (WastageCalculator#adjustmentAmount's rule), the base being every NON-adjustment row's line
 * amount, because two adjustments do NOT compound on the server. It is labelled an estimate wherever
 * shown, and deliberately NOT in mockApi.js: a mock that mirrored it would make mock-driven tests
 * evidence of nothing about the server's figure.
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
 * The ส่วนลด column of the document view, for one row (DealQuotationRenderAdapter#discountLabel), so
 * the screen and the printed page say the same thing. ADJUSTMENT prints NOTHING (the cell is empty);
 * a TILE row reads พิเศษ in SPECIAL_SQM, and in DIRECT_NET when the typed net differs from the list
 * price; everything else is `Net` or `N%`. On an English document the word is "Special" (the English
 * form has no Thai text in the item table).
 */
export function documentDiscountLabel(item, documentPriceMode, documentLanguage = 'TH') {
  const type = lineTypeOf(item);
  if (type === LINE_TYPE_ADJUSTMENT) return '';
  const special = documentLanguage === 'EN' ? 'Special' : 'พิเศษ';
  // The mode THIS row was priced in: the document's, or on a mixed document the row's own.
  const priceMode = rowPriceMode(documentPriceMode, item);
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
 * Remark 7's ระบุวันที่ (DATE) validity variant, and the จำนวนวัน / ระบุวันที่ toggle offering it at
 * all, is gated on the document having special pricing. Mirrors
 * {@code th.co.glr.hr.dealquotation.DealQuotationRenderAdapter#hasSpecialPricing} EXACTLY: same five
 * rules, same TILE/PLAIN/ADJUSTMENT split (via {@link lineTypeOf}), decided from the DATA rather than
 * from {@link documentDiscountLabel}'s printed word (an English per-sqm SPECIAL_SQM row prints "Net"
 * but rule (a) still counts it). A quotation has special pricing when ANY row satisfies:
 *   a. priceMode SPECIAL_SQM and the row is a TILE (any TILE row counts, discount aside);
 *   b. priceMode DIRECT_NET and the row is a TILE whose netUnitPrice differs from its unitPrice
 *      (the row whose ส่วนลด cell reads พิเศษ; see documentDiscountLabel's DIRECT_NET branch);
 *   c. priceMode NET (neither of the above) and the row is a TILE with discountPct > 0;
 *   d. the row is PLAIN with discountPct > 0;
 *   e. the row is an ADJUSTMENT (ส่วนลดพิเศษ) row.
 */
export function hasSpecialPricing(documentPriceMode, rows) {
  const isPositive = (value) => value != null && value !== '' && Number(value) > 0;
  for (const row of rows ?? []) {
    const type = lineTypeOf(row);
    if (type === LINE_TYPE_ADJUSTMENT) return true; // (e)
    if (type === LINE_TYPE_PLAIN) {
      if (isPositive(row?.discountPct)) return true; // (d)
      continue;
    }
    // TILE (lineTypeOf's own default when lineType is null/blank). V192.5: rules (a)–(c) are asked
    // of the mode THIS row is priced in — DealQuotationRenderAdapter#hasSpecialPricingCore does
    // the same — which on a single-mode document is the document's mode on every pass, as before.
    const priceMode = rowPriceMode(documentPriceMode, row);
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
  // The SAME lead-time value rule as a TILE row (a PLAIN row's lead time is optional but, once
  // entered, must be a real range); mirrors DealQuotationService#requireValidLeadTime, called for
  // both line types.
  if (item?.leadTimeMinDays != null && item?.leadTimeMaxDays != null) {
    const leadMin = Number(item.leadTimeMinDays);
    const leadMax = Number(item.leadTimeMaxDays);
    if (!(leadMin >= 1) || !(leadMax >= leadMin)) {
      errors.leadTimeMinDays = 'ระยะเวลานำเข้าต้องไม่น้อยกว่า 1 วัน และค่าสูงสุดต้องไม่น้อยกว่าค่าต่ำสุด';
    }
  }
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

// ── "ข้อมูลที่ยังไม่ครบ" checklist ──────────────────────────────────────────────────────────────
// The fields are the ones the reference documents print in their HEADER (customer name, ที่อยู่,
// เลขที่ผู้เสียภาษี, โทร., โครงการ) plus each item's own completeness. ผู้สั่งซื้อ is not here: it is
// the signature slot the customer signs by hand, and the optional ผู้ติดต่อ (printed after "เรียน")
// is picked per quotation; nothing is inherited from the deal, so there is nothing to validate.
//
// ⚠️ Two tiers, and the split is not invented here. A check BLOCKS บันทึกร่าง / ส่งขออนุมัติ only
// when the backend ALREADY refuses the same state:
//   customer / project   → TicketService.create ("ต้องเลือกโครงการก่อนสร้างดีล"), inline path only
//   items                → #buildItem / #requireStoredItemComplete, and "at least one row"
//   locationLabels       → the editor's own location-group rule (an unsaveable-without-loss state)
//   priceModeLanguage    → #requirePriceModeAllowed (MIXED on EN → 400)
//   orderedByEmail       → #resolveOrderedByEmail (a typed-but-malformed ผู้ติดต่อ Email → 400)
// Everything else is a WARNING: shown, clickable, never blocking. A customer with no tax id must
// still be quotable, and there is no ruling on whether a missing ที่อยู่ or โทร. should block, so
// they don't.
//
// `dealProject` is a separate key from `project`: on the ?ticket= / existing-draft paths the project
// belongs to the DEAL and can't be chosen here, and neither the quotation create nor its submit
// refuses a deal without one (only TicketService.create does, for NEW deals).
export const QUOTATION_CHECK = Object.freeze({
  CUSTOMER: 'customer',
  PROJECT: 'project',
  DEAL_PROJECT: 'dealProject',
  // Inline-create only. A FRONTEND rule: TicketService.create accepts UNSPECIFIED (the stored
  // default), but a rep must state how the deal arrived before it is created, and UNSPECIFIED is no
  // longer a button, so "nothing picked" is what this blocks on.
  ENTRY_CHANNEL: 'entryChannel',
  // No CONTACT / CONTACT_PHONE / CONTACT_EMAIL checks: ผู้สั่งซื้อ is not a required contact.
  CUSTOMER_ADDRESS: 'customerAddress',
  CUSTOMER_TAX_ID: 'customerTaxId',
  CUSTOMER_PHONE: 'customerPhone',
  LOCATION_LABELS: 'locationLabels',
  PRICE_MODE_LANGUAGE: 'priceModeLanguage',
  ITEMS: 'items',
  FULL_PAYMENT_TERM: 'fullPaymentTerm',
  // CREDIT_DAYS is the blank-on-draft reminder (non-blocking, like FULL_PAYMENT_TERM above);
  // CREDIT_DAYS_INVALID is an EXPLICIT invalid value (0 or negative), blocking since the backend
  // refuses it on every save. Two check names so the severities never share one flag.
  CREDIT_DAYS: 'creditDays',
  CREDIT_DAYS_INVALID: 'creditDaysInvalid',
  // Submit-only, like FULL_PAYMENT_TERM: a draft still saves with no lead time; ส่งขออนุมัติ does not.
  LEAD_TIME: 'leadTime',
  // All three blocking, each mirroring a server refusal on create: no ticket to hang the quotation on
  // (DEAL), a 400 on a DEAL_DIRECT create with no recipient (RECIPIENT), and the N6 409 (LIVE_DIRECT).
  DEAL: 'deal',
  RECIPIENT: 'recipient',
  LIVE_DIRECT_QUOTATION: 'liveDirectQuotation',
  // One pricing route per deal: the picked deal has a live pricing request, which
  // DealQuotationService#create refuses (409).
  LIVE_PRICING_REQUEST: 'livePricingRequest',
  // A NEW or CHANGED recipient off the deal's route, which DealQuotationService#create/#update refuse (409,
  // DealRoute.refusalMessage). The message is that sentence, built from the served catalog
  // (stageCatalog#recipientRouteRefusal) and passed in, so this module needs no route table.
  RECIPIENT_OFF_ROUTE: 'recipientOffRoute',
  // ผู้ติดต่อ's Email is optional, so no "missing" check, only this one, for an address that is typed
  // but malformed, which DealQuotationService refuses on every save. Blocking (see
  // QUOTATION_BLOCKING_CHECKS).
  ORDERED_BY_EMAIL: 'orderedByEmail',
});

/** THE blocking set: the one place that decides which checklist entries disable บันทึกร่าง and
 * ส่งขออนุมัติ. Every other check is a warning, or submit-only (QUOTATION_SUBMIT_BLOCKING_CHECKS).
 * Change it only on an owner ruling. */
export const QUOTATION_BLOCKING_CHECKS = Object.freeze(new Set([
  QUOTATION_CHECK.DEAL,
  QUOTATION_CHECK.RECIPIENT,
  QUOTATION_CHECK.LIVE_DIRECT_QUOTATION,
  QUOTATION_CHECK.LIVE_PRICING_REQUEST,
  QUOTATION_CHECK.RECIPIENT_OFF_ROUTE,
  QUOTATION_CHECK.CUSTOMER,
  QUOTATION_CHECK.PROJECT,
  QUOTATION_CHECK.ENTRY_CHANNEL,
  QUOTATION_CHECK.LOCATION_LABELS,
  QUOTATION_CHECK.PRICE_MODE_LANGUAGE,
  QUOTATION_CHECK.ITEMS,
  QUOTATION_CHECK.CREDIT_DAYS_INVALID,
  // The server refuses a malformed ผู้ติดต่อ Email with a 400 on every save
  // (DealQuotationService#resolveOrderedByEmail), so the editor says so before the click. A BLANK one
  // is not in question: the field is optional.
  QUOTATION_CHECK.ORDERED_BY_EMAIL,
]));

/** Checks that keep บันทึกร่าง open but disable ส่งขออนุมัติ (not in the blocking set above) — mirrors
 * QuotationEditorPage's hasUnresolvedZeroDeposit / hasMissingCreditDays guards. */
export const QUOTATION_SUBMIT_BLOCKING_CHECKS = Object.freeze(new Set([
  QUOTATION_CHECK.FULL_PAYMENT_TERM,
  QUOTATION_CHECK.CREDIT_DAYS,
  QUOTATION_CHECK.LEAD_TIME,
]));

/** The sentence the editor's ส่งขออนุมัติ gate and the checklist both give for missing lead times
 * (the EXACT wording DealQuotationService#requireEveryTileItemHasALeadTime's 400 uses). */
export function missingLeadTimeMessage(seqs) {
  return `กรุณาระบุระยะเวลานำเข้า (วัน) ของรายการที่ ${seqs.join(', ')}`;
}

/** Splits checklist entries into the three groups the editor shows: ต้องกรอก (blocking),
 * ต้องกรอกก่อนส่งขออนุมัติ (submit-only) and ควรกรอก (warnings). Blocking wins over submit-only. */
export function splitQuotationChecklist(entries) {
  const blocking = entries.filter((e) => e.blocking);
  const submitBlocking = entries.filter((e) => !e.blocking && e.submitBlocking);
  const warnings = entries.filter((e) => !e.blocking && !e.submitBlocking);
  return { blocking, submitBlocking, warnings };
}

/** DOM id of each editor field the checklist can focus. The three customer-detail ids are the
 * shared CustomerDetailsFields' (the same ids on every entry path — only one path renders at a
 * time). */
export const QUOTATION_FIELD_IDS = Object.freeze({
  deal: 'deal-picker',
  // The FIRST radio of ผู้รับใบเสนอราคา — a radiogroup <div> is not focusable itself.
  recipient: 'quotation-recipient-DESIGNER',
  customer: 'deal-customer',
  project: 'deal-project',
  entryChannel: 'deal-entry-channel',
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
    // Matches QuotationItemRow's `waste-${index}` input id.
    wastageValue: 'waste',
  },
  PLAIN: {
    description: 'plain-desc', quantity: 'plain-qty', unit: 'plain-unit', unitPrice: 'plain-price',
    // Matches QuotationPlainItemRow's `plain-lead-${index}` id.
    leadTimeMinDays: 'plain-lead',
  },
  ADJUSTMENT: { adjustmentPct: 'adj-pct', adjustmentAmount: 'adj-amount' },
};

/** The DOM id of the FIRST missing field in a row's `errors` (validateQuotationItem /
 * validateAdjustment output), in the row's own left-to-right order — or null if none. */
function firstMissingItemFieldId(item, errors, index) {
  const prefixes = ITEM_FIELD_ID_PREFIX[lineTypeOf(item)] ?? ITEM_FIELD_ID_PREFIX.TILE;
  const first = QUOTATION_ITEM_FIELD_ORDER.find((key) => errors?.[key] && prefixes[key]);
  return first ? `${prefixes[first]}-${index}` : null;
}

function blankValue(value) {
  return value === null || value === undefined || String(value).trim() === '';
}

/**
 * Whether the depositPercent this editor will actually SUBMIT is 0, mirroring
 * {@code DealQuotationService#isZeroDeposit} exactly. depositPercent reaches 0 two ways: ticking
 * "ไม่รับมัดจำ" (`noDeposit`), or picking the custom "อื่นๆ" percent input
 * (`depositPercentCustom`) and typing "0" (`depositPercent`) WITHOUT ticking the box.
 * `buildUpsertPayload`'s ternary (`terms.noDeposit ? 0 : Number(terms.depositPercent)`) yields 0
 * either way, so both routes must be recognised identically. Exported so
 * {@link buildQuotationChecklist} (the visible checklist entry) and QuotationEditorPage's
 * submit-only guard (like its hasMissingLeadTimes pattern: a DRAFT still saves with this state; only
 * #submit refuses it) share the ONE computation.
 */
export function isEffectiveZeroDeposit({ noDeposit = false, depositPercentCustom = false, depositPercent = '' } = {}) {
  if (noDeposit) return true;
  return depositPercentCustom && depositPercent !== '' && Number(depositPercent) === 0;
}

// The longest ผู้ติดต่อ Phone and Email the server keeps (DealQuotationService's own
// ORDERED_BY_PHONE_MAX / ORDERED_BY_EMAIL_MAX, the widths of the two columns; a longer one is a
// 400). The editor's two boxes stop at these lengths and mockApi.js refuses past them, both from
// this one pair. Keep it in step with the server's.
export const ORDERED_BY_PHONE_MAX = 50;
export const ORDERED_BY_EMAIL_MAX = 254;

// ผู้ติดต่อ's Email (V192.3): one @, no whitespace, a dot in the domain — "somchai@example" is the
// ".com" nobody typed. The same shape DealQuotationService#ORDERED_BY_EMAIL_SHAPE refuses with a
// 400. Keep the two in step: quotationOrderedByContact.test.jsx and
// DealQuotationOrderedByContactIntegrationTest hold the same list of addresses for that reason,
// and mockApi.js checks with THIS function rather than a third copy.
const ORDERED_BY_EMAIL_SHAPE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

// Mirrors DealQuotationRenderAdapter#stripPhoneLabel (PHONE_LABEL_PREFIX): drops a LEADING
// โทร./Tel.-style label so the on-screen document shows what the PDF prints.
const PHONE_LABEL_PREFIX = /^(?:โทรศัพท์|เบอร์โทรศัพท์|เบอร์โทร|เบอร์|โทร|telephone|tel|phone)\s*[.:：]?\s*(?=[0-9+(]|$)/i;

/** The phone as the PDF prints it: trimmed, a leading label removed; '' when nothing is left. */
export function stripPhoneLabel(value) {
  return (value ?? '').trim().replace(PHONE_LABEL_PREFIX, '').trim();
}

/** What is wrong with ผู้ติดต่อ's Email as typed — the Thai sentence to show under the field — or
 * `undefined` when it is blank (the field is optional) or looks like an address. Checked on the
 * trimmed value, which is what is saved. Tells the rep before the save what the server would
 * otherwise tell them by refusing it. */
export function orderedByEmailError(value) {
  const email = String(value ?? '').trim();
  return !email || ORDERED_BY_EMAIL_SHAPE.test(email) ? undefined : 'รูปแบบอีเมลไม่ถูกต้อง';
}

/**
 * The checklist, as `{ check, message, targetId, blocking }` entries, in the order the editor
 * reads top to bottom. Pure: every input is editor state the caller already holds.
 *
 * `customer` carries the details the document prints. A field whose value is `undefined` is
 * UNKNOWN (still loading) and produces no warning — only a known-empty one (null / '') does, so
 * the list never flashes "missing" at a value that simply has not arrived yet.
 *
 * The blocking messages are the exact strings the editor showed before this checklist existed, so
 * a rep sees one sentence for one problem.
 */
export function buildQuotationChecklist({
  isInlineCreate = false,
  customer = null,
  hasProject = false,
  // Inline-create only; defaults to "satisfied" so every existing caller keeps behaving as before.
  hasEntryChannel = true,
  // undefined = not loaded yet (the deal is still in flight) → no warning; null/'' = no project.
  projectName = undefined,
  items = [],
  itemErrorsByRow = [],
  adjustments = [],
  adjustmentErrorsByRow = [],
  duplicateGroupIndex = null,
  priceModeLanguageConflict = false,
  // "ไม่รับมัดจำ" is NOT in QUOTATION_BLOCKING_CHECKS: DealQuotationService#create/#update accept a
  // zero-deposit DRAFT with no term chosen yet, and only #submit refuses it (this checklist blocks
  // ONLY when the backend already refuses the SAME state for both บันทึกร่าง AND ส่งขออนุมัติ). So
  // it is a visible, non-blocking reminder; QuotationEditorPage's SEPARATE submit-only guard is what
  // disables ส่งขออนุมัติ for this state, sharing #isEffectiveZeroDeposit below.
  noDeposit = false,
  // depositPercent reaches 0 TWO ways: the "ไม่รับมัดจำ" checkbox (`noDeposit` above) and typing "0"
  // into the custom "อื่นๆ" input while UNticked (`depositPercentCustom` + `depositPercent`), and
  // DealQuotationService#submit's isZeroDeposit() gate refuses both identically. Both new params
  // default to the "never triggers" shape so callers that only pass `noDeposit` behave as before.
  depositPercentCustom = false,
  depositPercent = '',
  fullPaymentTerm = '',
  // Mirrors DealQuotationService's credit-days rule; see QUOTATION_CHECK.CREDIT_DAYS /
  // CREDIT_DAYS_INVALID above for the two severities.
  remainderMode = '',
  creditDays = '',
  // `needsDeal`: the "เลือกดีลที่มีอยู่" branch with nothing picked yet. `recipientRequired`: a NEW
  // DEAL_DIRECT quotation (the server 400s one without a recipient); never set for an existing row or
  // a PRICING_REQUEST one. `liveDirectQuotation`: the picked deal's
  // TicketSummaryDto.liveDirectQuotation on a NEW quotation (N6), or an array of every live row. All default to "never triggers", so
  // existing callers keep their checklist.
  needsDeal = false,
  recipientRequired = false,
  recipientType = '',
  // The route sentence for a NEW or CHANGED recipient off the deal's route (null/'' = none). The caller passes it
  // only then: the server accepts a stored off-route recipient re-sent unchanged on a DRAFT.
  offRouteRecipientMessage = null,
  liveDirectQuotation = null,
  dealStatus = 'draft',
  // One pricing route per deal: the picked deal has a live pricing request (hasLivePricingRequest).
  livePricingRequest = false,
  // V192.3: ผู้ติดต่อ's Email as typed. Blank (the default) is fine — see #orderedByEmailError.
  orderedByEmail = '',
  // 1-based printed row numbers of TILE/PLAIN rows with no lead time (the editor's own
  // missingLeadTimeSeqs): submit-only, listed so the checklist never reads "complete" while ส่ง is blocked.
  missingLeadTimeSeqs = [],
} = {}) {
  const entries = [];
  const push = (check, message, targetId = null) => {
    entries.push({
      check, message, targetId,
      blocking: QUOTATION_BLOCKING_CHECKS.has(check),
      submitBlocking: QUOTATION_SUBMIT_BLOCKING_CHECKS.has(check),
    });
  };

  if (needsDeal) push(QUOTATION_CHECK.DEAL, 'ต้องเลือกดีลก่อนบันทึกร่าง', QUOTATION_FIELD_IDS.deal);
  const blockingLive = blockingDirectQuotation(liveDirectQuotation, recipientType, dealStatus);
  if (blockingLive) push(QUOTATION_CHECK.LIVE_DIRECT_QUOTATION, liveDirectQuotationBlockMessage(blockingLive));
  if (livePricingRequest) push(QUOTATION_CHECK.LIVE_PRICING_REQUEST, LIVE_PRICING_REQUEST_BLOCK_MESSAGE);
  if (offRouteRecipientMessage) push(QUOTATION_CHECK.RECIPIENT_OFF_ROUTE, offRouteRecipientMessage, QUOTATION_FIELD_IDS.recipient);
  if (recipientRequired && !quotationRecipientOption(recipientType)) {
    push(QUOTATION_CHECK.RECIPIENT, 'ต้องเลือกผู้รับใบเสนอราคา', QUOTATION_FIELD_IDS.recipient);
  }

  if (isInlineCreate) {
    // The customer/project/channel trio is the สร้างดีลใหม่ branch's own; with no deal picked yet on
    // the เลือกดีลที่มีอยู่ branch, DEAL above is the one thing missing.
    if (!needsDeal && !customer) push(QUOTATION_CHECK.CUSTOMER, 'ต้องเลือกลูกค้าก่อนบันทึกร่าง', QUOTATION_FIELD_IDS.customer);
    if (!needsDeal && !hasProject) push(QUOTATION_CHECK.PROJECT, 'ต้องเลือกโครงการก่อนบันทึกร่าง', QUOTATION_FIELD_IDS.project);
    if (!needsDeal && !hasEntryChannel) push(QUOTATION_CHECK.ENTRY_CHANNEL, 'ต้องเลือกช่องทางรับงานก่อนบันทึกร่าง', QUOTATION_FIELD_IDS.entryChannel);
  } else if (projectName !== undefined && blankValue(projectName)) {
    push(QUOTATION_CHECK.DEAL_PROJECT, 'ดีลนี้ยังไม่มีโครงการ (แก้ได้ที่หน้ารายละเอียดดีล)');
  }
  // ผู้สั่งซื้อ is not checked here: the signature slot is always left for the customer's pen, and
  // the optional ผู้ติดต่อ (`contactId`) can never be "missing" in a way worth flagging. ผู้ออกแบบ (unitCode) is deliberately NOT checked
  // either: it is optional including ฝ่าย, and the backend never required it.

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
  // The same sentence the server's 400 says, so a rep sees one sentence for one problem. The field
  // has a different id on each entry path (two forms, one page never renders both).
  const emailError = orderedByEmailError(orderedByEmail);
  if (emailError) {
    push(QUOTATION_CHECK.ORDERED_BY_EMAIL, `Email ของลูกค้า: ${emailError}`,
      isInlineCreate ? 'orderedByEmailInline' : 'orderedByEmailCard');
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
  // Mirrors DealQuotationService's credit-days rule. Skipped entirely at effective-zero-deposit,
  // where remainderMode is forced null server-side regardless of what is still displayed
  // (buildUpsertPayload's `terms.noDeposit ? null : ...` ternary).
  if (!isEffectiveZeroDeposit({ noDeposit, depositPercentCustom, depositPercent }) && remainderMode === 'CREDIT') {
    if (!blankValue(creditDays) && Number(creditDays) <= 0) {
      push(QUOTATION_CHECK.CREDIT_DAYS_INVALID, 'กรุณาระบุจำนวนวันเครดิต อย่างน้อย 1 วัน', 'creditDays');
    } else if (blankValue(creditDays)) {
      push(QUOTATION_CHECK.CREDIT_DAYS, 'กรุณาระบุจำนวนวันเครดิต อย่างน้อย 1 วัน', 'creditDays');
    }
  }

  if (missingLeadTimeSeqs.length) {
    const row = missingLeadTimeSeqs[0] - 1;
    const prefix = (ITEM_FIELD_ID_PREFIX[lineTypeOf(items[row])] ?? ITEM_FIELD_ID_PREFIX.TILE).leadTimeMinDays;
    push(QUOTATION_CHECK.LEAD_TIME, missingLeadTimeMessage(missingLeadTimeSeqs), `${prefix}-${row}`);
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

export function generateClientRequestId() {
  return crypto.randomUUID?.()
    ?? '00000000-0000-4000-8000-' + String(Date.now()).slice(-12).padStart(12, '0');
}

/** One idempotency key PER (quotation, outcome): the backend replays a reused key as the FIRST
 * outcome's success, so a failed ปฏิเสธ followed by ขอแก้ไข on a shared key would record nothing, and it
 * 409s a key already used on ANOTHER quotation. `map` is a useRef object; `scope` names the
 * quotation (include the engine when ids can collide). Drop the key after a successful record. */
export const outcomeKeyFor = (map, scope, outcome) => (map[`${scope}:${outcome}`] ??= generateClientRequestId());
export const dropOutcomeKey = (map, scope, outcome) => { delete map[`${scope}:${outcome}`]; };
