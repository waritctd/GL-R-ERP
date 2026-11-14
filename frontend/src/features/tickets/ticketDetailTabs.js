// Ticket-detail IA rebuild Phase 2 built the SEVEN tabs from the "Tabs (the deal's depth)" table in
// docs/ui-repair/02-information-architecture/TICKET_INFORMATION_ARCHITECTURE.md, grouped by SYSTEM OBJECT.

import { visibleSections } from './salesViewScope.js';

// PricingRequestService.VIEWER_ROLES and CustomerQuotationService.VIEW_ROLES are two different Java constants,
// but for this deal's viewer set they resolve to the same 4 roles.
export const TICKET_DETAIL_TABS = [
  {
    id: 'deal',
    label: 'ดีล',
    helper: 'ข้อมูลดีล',
    // Backed by TicketService.get/requireViewAccess — every role that can reach this page at all may see the ดีล
    // tab: customer/project/contact, key dates, and DealTrackingPanel, moved in from the old activity tab — that
    // panel's own `canViewDealTracking` inner gate travels with it, unchanged.
    isVisible: () => true,
  },
  {
    id: 'items',
    label: 'สินค้าและราคา',
    helper: 'รายการและราคา',
    // The items table carries the same unconditional gate as `deal` above. See that file's own comment at the
    // render site. pricing_accountCannotReadAPricingRequest / pricing_hrCannotReadAPricingRequest /
    // pricing_employeeCannotReadAPricingRequest / pricing_salesImportCeoSalesManagerCanAllReadIt
    isVisible: () => true,
  },
  {
    id: 'documents',
    label: 'เอกสาร',
    helper: 'เอกสารของดีล',
    // Slice D widens this tab from a single role+section gate to ` => true`. Neither gains a single quotation or
    // deposit-notice/remaining-invoice row they could not see before — see DealDocumentRegister.jsx's own header
    // comment for the exact per-row-family predicates and their backend citations.
    isVisible: () => true,
  },
  {
    id: 'money',
    label: 'การเงิน',
    helper: 'ยอดชำระ',
    // Show the payment tab only when the caller can read the payment ledger or deposit notices.
    isVisible: (role, sections) => Boolean(sections.payment || sections.depositNotice),
  },
  {
    id: 'fulfilment',
    label: 'จัดซื้อ-ส่งมอบ',
    helper: 'นำเข้าและจัดส่ง',
    // deliveries_hrCannotReadDeliveries / deliveries_employeeCannotReadDeliveries /
    // deliveries_everyOtherViewerRoleCanReadThem (sales-owner/import/ceo/account/ sales_manager all pass — matches
    // salesViewScope.js's `delivery`, true for every named role, false only for the allFalse() unknown-role case).
    isVisible: (role, sections) => Boolean(sections.delivery),
  },
  {
    id: 'history',
    label: 'ประวัติ',
    helper: 'กิจกรรมและไฟล์แนบ',
    // FIX 1 (Opus review, owner decision): previously gated on `sections.dealTracking`, which took down the WHOLE
    // tab — including the plain audit trail (`ticket.events`, IA region 18) and the comment box — for import and
    // account.
    isVisible: () => true,
  },
];

export const DEFAULT_TICKET_DETAIL_TAB_ID = 'deal';

/** The ordered list of tab ids `role` may see for this deal. */
export function visibleTicketDetailTabIds(role) {
  const sections = visibleSections(role);
  return TICKET_DETAIL_TABS.filter((tab) => tab.isVisible(role, sections)).map((tab) => tab.id);
}

/** `tabId` if it is one `role` may currently see, else `DEFAULT_TICKET_DETAIL_TAB_ID` — an absent, unknown, or
 * role-hidden `?tab=` value (a stale deep link after a role change, a typo, hand-edited URL) never renders a
 * blank/forbidden panel, it silently falls back to ดีล. */
export function resolveTicketDetailTab(tabId, role) {
  const visible = visibleTicketDetailTabIds(role);
  return visible.includes(tabId) ? tabId : DEFAULT_TICKET_DETAIL_TAB_ID;
}
