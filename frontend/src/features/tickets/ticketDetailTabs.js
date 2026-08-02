// Ticket-detail IA rebuild Phase 2 built the SEVEN tabs from the "Tabs (the
// deal's depth)" table in
// docs/ui-repair/02-information-architecture/TICKET_INFORMATION_ARCHITECTURE.md,
// grouped by SYSTEM OBJECT. Slice C2b regroups those into SIX tabs grouped by
// JOB instead — ดีล · สินค้าและราคา · เอกสาร · การเงิน · จัดซื้อ-ส่งมอบ · ประวัติ,
// the owner-approved target. Slice C2b itself was a pure relocation: every
// predicate was byte-identical to its pre-Slice-C2b counterpart, only moved
// to wherever its content now lived (a tab-level `isVisible`, or — where a
// section merged into an unconditional tab — an inner render condition
// inside TicketDetailPage.jsx). Slice D ("the เอกสาร document register") is
// the first change here that DOES widen who can see a tab BUTTON: `documents`
// moves from a role+section gate to `() => true` (see that entry below) —
// an owner-authorised change, stated and evidenced in this branch's PR body,
// not a side effect. It never widens who can see any given ROW inside that
// tab; see DealDocumentRegister.jsx's own header comment for the three
// per-row-family gates that now do that job instead.
//
// Each tab is ROLE-projection ONLY: `isVisible(role, sections)` decides
// whether the tab BUTTON appears at all, never whether its content is dimmed
// or half-shown. Never reintroduce a stage check here — a tab is either
// visible to this role or it is not; there is no third "visible but dimmed"
// state (see Tabs.jsx — there is no `muted` concept any more either).
//
// Every gate below is checked against
// backend/src/test/java/th/co/glr/hr/ticket/TicketIaAuthzMatrixIntegrationTest.java
// (real Postgres, real services) — not the mock, not inference from the doc —
// per that test's own citation in each tab's comment.
//
// This module reads `visibleSections(role)` (salesViewScope.js) rather than
// duplicating its per-role section map — that module's role logic is an
// authorization-adjacent surface this branch does not touch. One predicate
// needs an EXTRA role filter on top of `visibleSections` because that
// module's map, by its own design, is coarser than these tabs need:
//
//   - ราคา / เอกสาร (formerly ใบเสนอราคา): `visibleSections('account')` leaves
//     `pricingRequest`/`dealQuotation`/`quotation` on for `account` (that
//     role only loses `pricingRequest`+`dealTracking`... see salesViewScope's
//     own account branch) — but `PricingRequestService.VIEWER_ROLES` and
//     `CustomerQuotationService.VIEW_ROLES` both exclude `account` for real
//     (`pricing_accountCannotReadAPricingRequest`,
//     `quotation_accountCannotListCustomerQuotations`). TicketDetailPage
//     already carried this exact extra filter before Phase 2 (its own
//     `canViewPricingRequests` local).
//
//     Post-Slice-D, NEITHER half of this filter is a TAB-level gate any
//     more. The ราคา half moved to an INNER condition inside `items` back in
//     Slice C2b (unchanged, see below); Slice D moves the ใบเสนอราคา half the
//     same way — `documents` itself is now `() => true` (see that entry
//     below), and this exact 4-role filter lives on as `canViewQuotations`
//     inside DealDocumentRegister.jsx (and, unchanged, around
//     DealQuotationPanel/DealLegacyQuotations at their own TicketDetailPage.jsx
//     render sites) — combining the same `canViewPricingRequests` local with
//     `sections.dealQuotation`/`sections.quotation`.
//
// One KNOWN GAP, preserved rather than silently patched (per this branch's
// brief: "tell me in your report rather than inventing one"): `import` is
// shown ZERO quotation rows inside the เอกสาร tab's register (formerly: not
// shown the whole tab at all — Slice D only widened the TAB, this specific
// row-level predicate is unchanged), because `visibleSections('import')`
// already sets both `dealQuotation` and `quotation` to `false` — but
// `quotation_salesManagerCeoImportCanAllList` proves the real
// `CustomerQuotationService` grants `import` VIEW access, and the IA spec's
// own Quotations-tab row lists "import(view)". Fixing this would mean either
// editing salesViewScope.js's role logic (out of bounds for this branch —
// see CLAUDE.md's authorization-change rule) or widening
// DealDocumentRegister.jsx's own quotation-row gate to contradict the
// content gate the rest of the page already uses for the exact same
// sections, which would hand an import assignee the approved customer
// price — worse than today. So `import` continues to see no quotation rows,
// UNCHANGED from its pre-Phase-2, pre-Slice-C2b, pre-Slice-D behaviour (not
// a new regression) — flagged here as a follow-up for whoever next touches
// salesViewScope.js's import branch. It DOES now reach the เอกสาร tab itself
// once it is this deal's assignee, for the attachments roll-up only — see
// DealDocumentRegister.jsx's own header comment.

import { visibleSections } from './salesViewScope.js';

// PricingRequestService.VIEWER_ROLES and CustomerQuotationService.VIEW_ROLES
// are two different Java constants, but for this deal's viewer set they
// resolve to the same 4 roles (sales/import/ceo/sales_manager) — both
// exclude `account` — see TicketIaAuthzMatrixIntegrationTest:
// pricing_salesImportCeoSalesManagerCanAllReadIt /
// quotation_salesManagerCeoImportCanAllList (and their sibling
// account/hr/employee/non-owner-sales refusal tests). No tab-level gate in
// THIS module needs that literal role set any more (Slice D moved the last
// one, `documents`, to an unconditional `() => true` — see below), so it is
// not declared here; TicketDetailPage.jsx's own `canViewPricingRequests`
// local remains the one place that names it, passed down as a prop to
// DealDocumentRegister.jsx and used directly around
// DealQuotationPanel/DealLegacyQuotations.
export const TICKET_DETAIL_TABS = [
  {
    id: 'deal',
    label: 'ดีล',
    helper: 'ข้อมูลดีล',
    // Backed by TicketService.get()/requireViewAccess — every role that can
    // reach this page at all (the ticket query itself already 403'd hr/
    // employee/non-owner-sales upstream, so this component never renders for
    // them) may see the ดีล tab: customer/project/contact, key dates, and
    // (Slice C2b) DealTrackingPanel, moved in from the old activity tab —
    // that panel's own `canViewDealTracking` inner gate travels with it,
    // unchanged (see TicketDetailPage.jsx's own comment at that render
    // site).
    // overview_hrCannotReadTheTicket / overview_employeeCannotReadTheTicket /
    // overview_nonOwnerSalesRepCannotReadAnotherRepsTicket /
    // overview_ownerImportCeoAccountSalesManagerCanAllReadIt
    isVisible: () => true,
  },
  {
    id: 'items',
    label: 'สินค้าและราคา',
    helper: 'รายการและราคา',
    // The items table (+ "แก้ไขรายการสินค้า") carries the same unconditional
    // gate as `deal` above — every viewer who reaches this page at all may
    // see its own item list:
    // overview_hrCannotReadTheTicket / overview_employeeCannotReadTheTicket /
    // overview_nonOwnerSalesRepCannotReadAnotherRepsTicket /
    // overview_ownerImportCeoAccountSalesManagerCanAllReadIt
    //
    // PricingRequestPanel (formerly its own whole-tab ราคา gate) merges in
    // here behind an INNER render condition instead — `isVisible` below
    // stays `() => true` because the items table itself is never hidden,
    // but TicketDetailPage.jsx reproduces the exact old predicate
    // (`Boolean(sections.pricingRequest) && canViewPricingRequests`, the
    // same 4-role list named in this file's header) around just the panel.
    // See that file's own comment at the render site.
    // pricing_accountCannotReadAPricingRequest / pricing_hrCannotReadAPricingRequest /
    // pricing_employeeCannotReadAPricingRequest /
    // pricing_salesImportCeoSalesManagerCanAllReadIt
    isVisible: () => true,
  },
  {
    id: 'documents',
    label: 'เอกสาร',
    helper: 'เอกสารของดีล',
    // Slice D ("the เอกสาร document register") widens this tab from a single
    // role+section gate to `() => true` — the same unconditional shape as
    // `deal`/`items`/`history` above — because its content is no longer just
    // the quotation chain: DealDocumentRegister.jsx now also rolls up the
    // deposit notice, the remaining invoice, and formal attachments here,
    // and those three families are governed by THREE DIFFERENT real backend
    // gates that do not coincide with the old quotation-only predicate
    // (`Boolean(sections.dealQuotation || sections.quotation) &&
    // PRICING_AND_QUOTATION_ROLES.has(role)`, still enforced, just moved).
    // A single tab-level gate can express at most one of those three
    // questions — per this branch's brief, "gate PER ROW TYPE, not with one
    // tab gate" — so, exactly like `history`'s own `canViewDocumentsTab`
    // split, ALL real gating now lives INSIDE DealDocumentRegister.jsx
    // (TicketDetailPage.jsx's own render site) and inside the still-present
    // `sections.dealQuotation && canViewPricingRequests` condition around
    // DealQuotationPanel/DealLegacyQuotations, both unchanged verbatim.
    //
    // Concretely, this tab now appears for every role that reaches this
    // page at all (hr/employee/non-owner-sales already 403 upstream at the
    // ticket fetch, so they never reach this component regardless) — most
    // visibly for `account` (the intended, owner-authorised widening: it is
    // asked to confirm money against exactly the deposit-notice/attachment
    // rows this register lists — ROLE_PERMISSIONS.canViewTicketDocuments
    // already includes it) and for `import` (which previously never saw
    // this tab at all — salesViewScope.js hides both `dealQuotation` and
    // `quotation` from it — but as this deal's assignee now legitimately
    // reaches the attachments roll-up, per TicketAccessPolicy.canViewDocuments's
    // participant-regardless-of-role grant). Neither gains a single
    // quotation or deposit-notice/remaining-invoice row they could not see
    // before — see DealDocumentRegister.jsx's own header comment for the
    // exact per-row-family predicates and their backend citations.
    isVisible: () => true,
  },
  {
    id: 'money',
    label: 'การเงิน',
    helper: 'ยอดชำระ',
    // ledger_importCannotReadThePaymentLedger / ledger_hrCannotReadThePaymentLedger /
    // ledger_employeeCannotReadThePaymentLedger / ledger_ownerCeoAccountSalesManagerCanAllReadIt
    // depositNotice_importCannotListDepositNoticesEitherDespiteBeingInTheViewerRolesConstant /
    // depositNotice_hrCannotListDepositNotices / depositNotice_employeeCannotListDepositNotices /
    // depositNotice_ownerCeoAccountSalesManagerCanAllListIt
    //
    // salesViewScope.js already sets both `payment` and `depositNotice` false
    // for `import` and leaves them true for sales/sales_manager/ceo/account —
    // exactly matching the real gates above, so no extra role filter is
    // needed here (unlike `documents` above).
    //
    // Slice C2b also relocates "ยกเลิกการยืนยันปิดงาน" here from the old
    // การดำเนินการอื่น ๆ grab-bag (dissolved along with the tab it sat in) —
    // gated on `can.revokeCloseConfirm`, unchanged. That action's real
    // server gate (`canRevokeCloseConfirmation`) is account/ceo-only, and
    // both roles pass this tab's own `payment`/`depositNotice` gate, so the
    // relocation can never strand the button behind a hidden tab.
    isVisible: (role, sections) => Boolean(sections.payment || sections.depositNotice),
  },
  {
    id: 'fulfilment',
    label: 'จัดซื้อ-ส่งมอบ',
    helper: 'นำเข้าและจัดส่ง',
    // deliveries_hrCannotReadDeliveries / deliveries_employeeCannotReadDeliveries /
    // deliveries_everyOtherViewerRoleCanReadThem (sales-owner/import/ceo/account/
    // sales_manager all pass — matches salesViewScope.js's `delivery`, true
    // for every named role, false only for the allFalse() unknown-role case).
    //
    // The narrower ProcurementService.RAW_PO_ROLES={import,ceo} sub-view
    // (rawPo_* tests) is enforced INSIDE DealFulfilmentPanel already — never
    // re-gated at the tab level, same as the items tab's pricing sub-section.
    isVisible: (role, sections) => Boolean(sections.delivery),
  },
  {
    id: 'history',
    label: 'ประวัติ',
    helper: 'กิจกรรมและไฟล์แนบ',
    // FIX 1 (Opus review, owner decision): previously gated on
    // `sections.dealTracking`, which took down the WHOLE tab — including the
    // plain audit trail (`ticket.events`, IA region 18) and the comment box —
    // for import and account. But `TicketService.comment` only requires view
    // access (`requireViewAccess`'s VIEWER_ROLES = sales-own/import/ceo/
    // account/sales_manager), not deal ownership, and
    // `TicketService.projectForRole`/`events()` pass ticket.events through
    // unchanged for every viewer role — only `TicketService.listActivities`
    // (the follow-up feed, region 17) is genuinely gated on
    // `requireDealOwnership`. The owner decided: show this tab to every
    // viewer of the deal (same `() => true` as ดีล/สินค้าและราคา), and move
    // the narrower gate INSIDE it — see DealHistoryPanel's
    // `canViewActivityFeed`/`canAddActivity` props and TicketDetailPage.jsx's
    // own doc comment on this tab's JSX for exactly what stays restricted.
    // activities_importCannotReadTheActivityFeed / activities_accountCannotReadTheActivityFeed /
    // activities_hrCannotReadTheActivityFeed / activities_employeeCannotReadTheActivityFeed /
    // activities_nonOwnerSalesRepCannotReadAnotherRepsActivityFeed /
    // activities_ownerSalesManagerCeoCanAllReadIt (still refused — just no
    // longer taking the audit trail + comment box down with them)
    //
    // Slice C2b also folds DealAttachmentsPanel in here from the old
    // `documents` (attachments) tab — that tab's own `() => true` role gate
    // travels with it (identity, not role, decides who reaches attachments,
    // same reasoning as this tab's own gate), but its per-instance
    // `canViewDocumentsTab` check — formerly a TAB-level filter in
    // TicketDetailPage.jsx (`visibleTabItems`/`visibleActiveTab`'s old
    // 'documents' special-casing) — is now an INNER render condition around
    // just the attachments panel, since this tab is unconditional and has
    // no tab-level analogue left to filter. See TicketDetailPage.jsx's own
    // comment at that render site; the underlying predicate
    // (createdById/assignedToId participant OR
    // ROLE_PERMISSIONS.canViewTicketDocuments) is untouched. Issue #389's
    // attachments_*/AttachmentTicketAccessIntegrationTest citations still
    // apply to that inner condition, unchanged.
    isVisible: () => true,
  },
];

export const DEFAULT_TICKET_DETAIL_TAB_ID = 'deal';

/** The ordered list of tab ids `role` may see for this deal. */
export function visibleTicketDetailTabIds(role) {
  const sections = visibleSections(role);
  return TICKET_DETAIL_TABS.filter((tab) => tab.isVisible(role, sections)).map((tab) => tab.id);
}

/**
 * `tabId` if it is one `role` may currently see, else
 * `DEFAULT_TICKET_DETAIL_TAB_ID` — an absent, unknown, or role-hidden `?tab=`
 * value (a stale deep link after a role change, a typo, hand-edited URL)
 * never renders a blank/forbidden panel, it silently falls back to ดีล.
 */
export function resolveTicketDetailTab(tabId, role) {
  const visible = visibleTicketDetailTabIds(role);
  return visible.includes(tabId) ? tabId : DEFAULT_TICKET_DETAIL_TAB_ID;
}
