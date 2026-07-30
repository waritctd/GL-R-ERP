// Ticket-detail IA rebuild Phase 2: the SEVEN tabs from the "Tabs (the deal's
// depth)" table in
// docs/ui-repair/02-information-architecture/TICKET_INFORMATION_ARCHITECTURE.md.
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
// authorization-adjacent surface this branch does not touch. Two tabs need
// an EXTRA role filter on top of `visibleSections` because that module's
// map, by its own design, is coarser than these tabs need:
//
//   - ราคา / ใบเสนอราคา: `visibleSections('account')` leaves `pricingRequest`/
//     `dealQuotation`/`quotation` on for `account` (that role only loses
//     `pricingRequest`+`dealTracking`... see salesViewScope's own account
//     branch) — but `PricingRequestService.VIEWER_ROLES` and
//     `CustomerQuotationService.VIEW_ROLES` both exclude `account` for real
//     (`pricing_accountCannotReadAPricingRequest`,
//     `quotation_accountCannotListCustomerQuotations`). TicketDetailPage
//     already carried this exact extra filter before Phase 2 (its own
//     `canViewPricingRequests` local) — this module names the same 4 roles
//     so the tab model doesn't drift from what the page already enforces.
//
// One KNOWN GAP, reported rather than silently patched (per this branch's
// brief: "tell me in your report rather than inventing one"): `import` is
// NOT shown the ใบเสนอราคา tab here, because `visibleSections('import')`
// already sets both `dealQuotation` and `quotation` to `false` — but
// `quotation_salesManagerCeoImportCanAllList` proves the real
// `CustomerQuotationService` grants `import` VIEW access, and the IA spec's
// own Quotations-tab row lists "import(view)". Fixing this would mean either
// editing salesViewScope.js's role logic (out of bounds for this branch — see
// CLAUDE.md's authorization-change rule) or rendering a tab with a role
// filter that contradicts the content gate the rest of the page already uses
// for the exact same sections, which would produce a visible-but-empty tab —
// worse than today. So `import` continues to not see this tab, UNCHANGED
// from its pre-Phase-2 behaviour (not a new regression) — flagged here as a
// follow-up for whoever next touches salesViewScope.js's import branch.

import { visibleSections } from './salesViewScope.js';

// PricingRequestService.VIEWER_ROLES and CustomerQuotationService.VIEW_ROLES
// are two different Java constants, but for this deal's viewer set they
// resolve to the same 4 roles — both exclude `account` — see
// TicketIaAuthzMatrixIntegrationTest: pricing_salesImportCeoSalesManagerCanAllReadIt
// / quotation_salesManagerCeoImportCanAllList (and their sibling
// account/hr/employee/non-owner-sales refusal tests). Same literal role list
// as TicketDetailPage's own (unchanged) `canViewPricingRequests`.
const PRICING_AND_QUOTATION_ROLES = new Set(['sales', 'import', 'ceo', 'sales_manager']);

export const TICKET_DETAIL_TABS = [
  {
    id: 'overview',
    label: 'ภาพรวม',
    helper: 'Overview',
    // Backed by TicketService.get()/requireViewAccess — every role that can
    // reach this page at all (the ticket query itself already 403'd hr/
    // employee/non-owner-sales upstream, so this component never renders for
    // them) may see the overview tab: customer/project/contact, items, notes.
    // overview_hrCannotReadTheTicket / overview_employeeCannotReadTheTicket /
    // overview_nonOwnerSalesRepCannotReadAnotherRepsTicket /
    // overview_ownerImportCeoAccountSalesManagerCanAllReadIt
    isVisible: () => true,
  },
  {
    id: 'pricing',
    label: 'ราคา',
    helper: 'Pricing',
    // pricing_accountCannotReadAPricingRequest / pricing_hrCannotReadAPricingRequest /
    // pricing_employeeCannotReadAPricingRequest /
    // pricing_salesImportCeoSalesManagerCanAllReadIt
    isVisible: (role, sections) => Boolean(sections.pricingRequest) && PRICING_AND_QUOTATION_ROLES.has(role),
  },
  {
    id: 'quotations',
    label: 'ใบเสนอราคา',
    helper: 'Quotations',
    // quotation_accountCannotListCustomerQuotations / quotation_hrCannotListCustomerQuotations /
    // quotation_employeeCannotListCustomerQuotations /
    // quotation_nonOwnerSalesRepCannotListAnotherRepsCustomerQuotations /
    // quotation_salesManagerCeoImportCanAllList
    //
    // The role filter is load-bearing for `account` here (see this file's
    // header) — `sections.dealQuotation`/`sections.quotation` alone are NOT
    // enough, since salesViewScope.js keeps both `true` for that role.
    isVisible: (role, sections) => (
      Boolean(sections.dealQuotation || sections.quotation) && PRICING_AND_QUOTATION_ROLES.has(role)
    ),
  },
  {
    id: 'money',
    label: 'การเงิน',
    helper: 'Money',
    // ledger_importCannotReadThePaymentLedger / ledger_hrCannotReadThePaymentLedger /
    // ledger_employeeCannotReadThePaymentLedger / ledger_ownerCeoAccountSalesManagerCanAllReadIt
    // depositNotice_importCannotListDepositNoticesEitherDespiteBeingInTheViewerRolesConstant /
    // depositNotice_hrCannotListDepositNotices / depositNotice_employeeCannotListDepositNotices /
    // depositNotice_ownerCeoAccountSalesManagerCanAllListIt
    //
    // salesViewScope.js already sets both `payment` and `depositNotice` false
    // for `import` and leaves them true for sales/sales_manager/ceo/account —
    // exactly matching the real gates above, so no extra role filter is
    // needed here (unlike the pricing/quotations tabs).
    isVisible: (role, sections) => Boolean(sections.payment || sections.depositNotice),
  },
  {
    id: 'fulfilment',
    label: 'จัดซื้อ-ส่งมอบ',
    helper: 'Fulfilment',
    // deliveries_hrCannotReadDeliveries / deliveries_employeeCannotReadDeliveries /
    // deliveries_everyOtherViewerRoleCanReadThem (sales-owner/import/ceo/account/
    // sales_manager all pass — matches salesViewScope.js's `delivery`, true
    // for every named role, false only for the allFalse() unknown-role case).
    //
    // The narrower ProcurementService.RAW_PO_ROLES={import,ceo} sub-view
    // (rawPo_* tests) is enforced INSIDE DealFulfilmentPanel already — never
    // re-gated at the tab level, same as the pricing tab's cost/margin
    // sub-sections.
    isVisible: (role, sections) => Boolean(sections.delivery),
  },
  {
    id: 'documents',
    label: 'เอกสาร',
    helper: 'Documents',
    // Maps onto today's attachments section as-is (per this branch's brief:
    // "map onto what exists" — SALES_VIEW_SECTION_IDS has no `attachments`
    // id). This role-level predicate is deliberately coarse (same `() =>
    // true` as ภาพรวม) because AttachmentController.requireTicketAccess is a
    // genuinely DIFFERENT, wider, IDENTITY-based model (ticket participant —
    // createdById/assignedToId — OR role in {hr, sales_manager, ceo}) than
    // every other tab's role-only gate, and role+sections alone can't express
    // it — it needs THIS ticket's createdById/assignedToId, not just the
    // viewer's role. FIX 2 (Opus review): TicketDetailPage.jsx applies that
    // real per-instance gate on top (`canViewDocumentsTab` — role===ceo||
    // sales_manager||isOwner||user.id===summary.assignedToId), filtering the
    // tab out of the rendered list and out of `visibleActiveTab` for anyone
    // it excludes — so `account` (never a participant) and a non-assignee
    // `import` no longer get a rendered tab, a swallowed 403, and a lying
    // "ยังไม่มีไฟล์แนบ" empty state. See attachments_* in
    // TicketIaAuthzMatrixIntegrationTest for the refusals this mirrors.
    isVisible: () => true,
  },
  {
    id: 'activity',
    label: 'กิจกรรม',
    helper: 'Activity',
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
    // viewer of the deal (same `() => true` as ภาพรวม/เอกสาร), and move the
    // narrower gate INSIDE it — see DealHistoryPanel's `canViewActivityFeed`/
    // `canAddActivity` props and TicketDetailPage.jsx's own doc comment on
    // this tab's JSX for exactly what stays restricted.
    // activities_importCannotReadTheActivityFeed / activities_accountCannotReadTheActivityFeed /
    // activities_hrCannotReadTheActivityFeed / activities_employeeCannotReadTheActivityFeed /
    // activities_nonOwnerSalesRepCannotReadAnotherRepsActivityFeed /
    // activities_ownerSalesManagerCeoCanAllReadIt (still refused — just no
    // longer taking the audit trail + comment box down with them)
    isVisible: () => true,
  },
];

export const DEFAULT_TICKET_DETAIL_TAB_ID = 'overview';

/** The ordered list of tab ids `role` may see for this deal. */
export function visibleTicketDetailTabIds(role) {
  const sections = visibleSections(role);
  return TICKET_DETAIL_TABS.filter((tab) => tab.isVisible(role, sections)).map((tab) => tab.id);
}

/**
 * `tabId` if it is one `role` may currently see, else
 * `DEFAULT_TICKET_DETAIL_TAB_ID` — an absent, unknown, or role-hidden `?tab=`
 * value (a stale deep link after a role change, a typo, hand-edited URL)
 * never renders a blank/forbidden panel, it silently falls back to Overview.
 */
export function resolveTicketDetailTab(tabId, role) {
  const visible = visibleTicketDetailTabIds(role);
  return visible.includes(tabId) ? tabId : DEFAULT_TICKET_DETAIL_TAB_ID;
}
