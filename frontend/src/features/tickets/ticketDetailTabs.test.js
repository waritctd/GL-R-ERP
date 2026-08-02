import { describe, expect, it } from 'vitest';
import {
  DEFAULT_TICKET_DETAIL_TAB_ID, resolveTicketDetailTab, TICKET_DETAIL_TABS, visibleTicketDetailTabIds,
} from './ticketDetailTabs.js';

describe('visibleTicketDetailTabIds', () => {
  it('sales (deal owner) sees every tab', () => {
    expect(visibleTicketDetailTabIds('sales')).toEqual([
      'deal', 'items', 'documents', 'money', 'fulfilment', 'history',
    ]);
  });

  it('ceo and sales_manager see every tab (oversight roles — unconditional pass-through)', () => {
    expect(visibleTicketDetailTabIds('ceo')).toEqual(visibleTicketDetailTabIds('sales'));
    expect(visibleTicketDetailTabIds('sales_manager')).toEqual(visibleTicketDetailTabIds('sales'));
  });

  // quotation_accountCannotListCustomerQuotations — account keeps the tabs
  // salesViewScope.js leaves it (deal/items/money/fulfilment/history) but
  // loses เอกสาร (formerly the ใบเสนอราคา tab; the predicate is unchanged,
  // only relocated). PricingRequestPanel's own gate is no longer a tab-level
  // one either — it is now an INNER condition inside `items` — so account
  // still cannot reach pricing-request content, just via a different
  // mechanism (see TicketDetailPage.test.jsx's role-projection-in-effect
  // test, which asserts the CONTENT set is unchanged even though the tab
  // count changed).
  it('account loses documents (ใบเสนอราคา content) — keeps deal/items/money/fulfilment/history', () => {
    const tabs = visibleTicketDetailTabIds('account');
    expect(tabs).toEqual(['deal', 'items', 'money', 'fulfilment', 'history']);
  });

  // ledger_importCannotReadThePaymentLedger / depositNotice_import...Refused —
  // import loses money. It also loses documents today (see
  // ticketDetailTabs.js's own "KNOWN GAP" doc comment) even though the real
  // CustomerQuotationService would allow it — pinned here as the CURRENT
  // (pre- and post-Slice-C2b unchanged) behaviour, not asserted as correct.
  it('import loses money and documents — keeps deal/items/fulfilment/history', () => {
    const tabs = visibleTicketDetailTabIds('import');
    expect(tabs).toEqual(['deal', 'items', 'fulfilment', 'history']);
  });

  // deliveries_hrCannotReadDeliveries / overview_hrCannotReadTheTicket — hr
  // never actually reaches this page (route-gated) but the function must not
  // leak a section-gated tab to an unknown role if it somehow did. `history`
  // joins `deal`/`items` as a third role-unconditional tab.
  it('an unknown/unreachable role (hr, employee) only gets the three ungated tabs', () => {
    expect(visibleTicketDetailTabIds('hr')).toEqual(['deal', 'items', 'history']);
    expect(visibleTicketDetailTabIds('employee')).toEqual(['deal', 'items', 'history']);
  });

  it('every tab id in the visibility list is a real TICKET_DETAIL_TABS id, in the table\'s declared order', () => {
    const allIds = TICKET_DETAIL_TABS.map((t) => t.id);
    for (const role of ['sales', 'ceo', 'sales_manager', 'import', 'account']) {
      const visible = visibleTicketDetailTabIds(role);
      expect(visible.every((id) => allIds.includes(id))).toBe(true);
      expect(visible).toEqual(allIds.filter((id) => visible.includes(id)));
    }
  });
});

describe('TICKET_DETAIL_TABS display copy', () => {
  it('uses concise Thai helper labels for compact tabs', () => {
    expect(TICKET_DETAIL_TABS.map(({ helper }) => helper)).toEqual([
      'ข้อมูลดีล',
      'รายการและราคา',
      'เอกสารลูกค้า',
      'ยอดชำระ',
      'นำเข้าและจัดส่ง',
      'กิจกรรมและไฟล์แนบ',
    ]);
  });
});

describe('resolveTicketDetailTab', () => {
  it('keeps a tab id the role may see', () => {
    expect(resolveTicketDetailTab('items', 'sales')).toBe('items');
    expect(resolveTicketDetailTab('money', 'account')).toBe('money');
    // "history" is role-unconditional (the follow-up-feed sub-gate moved
    // inside the tab), so account keeps it too.
    expect(resolveTicketDetailTab('history', 'account')).toBe('history');
  });

  it('falls back to deal for a tab the role may NOT see', () => {
    expect(resolveTicketDetailTab('money', 'import')).toBe(DEFAULT_TICKET_DETAIL_TAB_ID);
    expect(resolveTicketDetailTab('documents', 'account')).toBe(DEFAULT_TICKET_DETAIL_TAB_ID);
  });

  it('falls back to deal for an absent or unknown tab id', () => {
    expect(resolveTicketDetailTab(null, 'sales')).toBe(DEFAULT_TICKET_DETAIL_TAB_ID);
    expect(resolveTicketDetailTab(undefined, 'sales')).toBe(DEFAULT_TICKET_DETAIL_TAB_ID);
    expect(resolveTicketDetailTab('not-a-real-tab', 'sales')).toBe(DEFAULT_TICKET_DETAIL_TAB_ID);
  });
});
