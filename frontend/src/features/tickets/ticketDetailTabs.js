// The deal page's five tabs (owner-approved drawing a-deal, 2026-11-16): ภาพรวม · ใบเสนอราคา · เงิน · ส่งของ · ไฟล์.
// They replace the six of the ticket-detail IA rebuild (ดีล · สินค้าและราคา · เอกสาร · การเงิน · จัดซื้อ-ส่งมอบ ·
// ประวัติ); each old tab's content moved whole, so each new tab keeps the visibility rule of what it holds.

import { visibleSections } from './salesViewScope.js';

export const TICKET_DETAIL_TABS = [
  {
    id: 'overview',
    label: 'ภาพรวม',
    // The old ดีล and ประวัติ tabs, both role-unconditional (TicketService.get / requireViewAccess); their inner
    // gates (canViewDealTracking for the follow-up feed and tracking fields) travel with the panels.
    isVisible: () => true,
  },
  {
    id: 'quote',
    label: 'ใบเสนอราคา',
    // The old สินค้าและราคา tab plus the quotation list of the old เอกสาร tab; both were role-unconditional at the
    // tab level, with per-panel gates (sections.pricingRequest, sections.dealQuotation, the register's row gates)
    // inside.
    isVisible: () => true,
  },
  {
    id: 'money',
    label: 'เงิน',
    // The old การเงิน tab's gate, unchanged: the payment ledger or the deposit notices.
    isVisible: (role, sections) => Boolean(sections.payment || sections.depositNotice),
  },
  {
    id: 'ship',
    label: 'ส่งของ',
    // The old จัดซื้อ-ส่งมอบ tab's gate, unchanged (deliveries_* in TicketIaAuthzMatrixIntegrationTest).
    isVisible: (role, sections) => Boolean(sections.delivery),
  },
  {
    id: 'files',
    label: 'ไฟล์',
    // The old เอกสาร tab minus its quotation list, plus the attachments panel from ประวัติ — both were
    // role-unconditional at the tab level, gated per row family / by canViewDocumentsTab inside.
    isVisible: () => true,
  },
];

export const DEFAULT_TICKET_DETAIL_TAB_ID = 'overview';

/** Old `?tab=` keys → the tab their content moved to. Bookmarks, shared links and the e2e suite still carry them. */
export const LEGACY_TICKET_DETAIL_TAB_IDS = Object.freeze({
  deal: 'overview',
  history: 'overview',
  items: 'quote',
  documents: 'files',
  fulfilment: 'ship',
});

/** The ordered list of tab ids `role` may see for this deal. */
export function visibleTicketDetailTabIds(role, reader = false) {
  const sections = visibleSections(role, reader);
  return TICKET_DETAIL_TABS.filter((tab) => tab.isVisible(role, sections)).map((tab) => tab.id);
}

/** `tabId` (an old key mapped to its new tab first) if `role` may see it, else `DEFAULT_TICKET_DETAIL_TAB_ID` — an
 * absent, unknown, or role-hidden `?tab=` never renders a blank or forbidden panel. */
export function resolveTicketDetailTab(tabId, role, reader = false) {
  const mapped = LEGACY_TICKET_DETAIL_TAB_IDS[tabId] ?? tabId;
  return visibleTicketDetailTabIds(role, reader).includes(mapped) ? mapped : DEFAULT_TICKET_DETAIL_TAB_ID;
}
