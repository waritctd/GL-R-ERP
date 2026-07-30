import { describe, expect, it } from 'vitest';
import {
  DEFAULT_TICKET_DETAIL_TAB_ID, resolveTicketDetailTab, TICKET_DETAIL_TABS, visibleTicketDetailTabIds,
} from './ticketDetailTabs.js';

describe('visibleTicketDetailTabIds', () => {
  it('sales (deal owner) sees every tab', () => {
    expect(visibleTicketDetailTabIds('sales')).toEqual([
      'overview', 'pricing', 'quotations', 'money', 'fulfilment', 'documents', 'activity',
    ]);
  });

  it('ceo and sales_manager see every tab (oversight roles — unconditional pass-through)', () => {
    expect(visibleTicketDetailTabIds('ceo')).toEqual(visibleTicketDetailTabIds('sales'));
    expect(visibleTicketDetailTabIds('sales_manager')).toEqual(visibleTicketDetailTabIds('sales'));
  });

  // pricing_accountCannotReadAPricingRequest / quotation_accountCannotListCustomerQuotations —
  // account keeps the tabs salesViewScope.js leaves it (money/fulfilment/
  // documents/overview) but loses pricing/quotations. FIX 1 (Opus review):
  // "activity" is now visible to every viewer role — see ticketDetailTabs.js's
  // own doc comment on that tab — so account no longer loses it either (the
  // narrower follow-up-feed gate moved INSIDE the tab).
  it('account loses pricing and quotations — keeps overview/money/fulfilment/documents/activity', () => {
    const tabs = visibleTicketDetailTabIds('account');
    expect(tabs).toEqual(['overview', 'money', 'fulfilment', 'documents', 'activity']);
  });

  // ledger_importCannotReadThePaymentLedger / depositNotice_import...Refused —
  // import loses money. It also loses quotations today (see
  // ticketDetailTabs.js's own "KNOWN GAP" doc comment) even though the real
  // CustomerQuotationService would allow it — pinned here as the CURRENT
  // (pre- and post-Phase-2 unchanged) behaviour, not asserted as correct.
  // FIX 1: "activity" is now visible to every viewer role, so import no
  // longer loses it (the follow-up-feed sub-gate moved inside the tab).
  it('import loses money and quotations — keeps overview/pricing/fulfilment/documents/activity', () => {
    const tabs = visibleTicketDetailTabIds('import');
    expect(tabs).toEqual(['overview', 'pricing', 'fulfilment', 'documents', 'activity']);
  });

  // deliveries_hrCannotReadDeliveries / overview_hrCannotReadTheTicket — hr
  // never actually reaches this page (route-gated) but the function must not
  // leak a section-gated tab to an unknown role if it somehow did. "activity"
  // joins "overview"/"documents" as a third role-unconditional tab (FIX 1).
  it('an unknown/unreachable role (hr, employee) only gets the three ungated tabs', () => {
    expect(visibleTicketDetailTabIds('hr')).toEqual(['overview', 'documents', 'activity']);
    expect(visibleTicketDetailTabIds('employee')).toEqual(['overview', 'documents', 'activity']);
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

describe('resolveTicketDetailTab', () => {
  it('keeps a tab id the role may see', () => {
    expect(resolveTicketDetailTab('pricing', 'sales')).toBe('pricing');
    expect(resolveTicketDetailTab('money', 'account')).toBe('money');
    // FIX 1: "activity" is role-unconditional now (the follow-up-feed
    // sub-gate moved inside the tab), so account keeps it too.
    expect(resolveTicketDetailTab('activity', 'account')).toBe('activity');
  });

  it('falls back to overview for a tab the role may NOT see', () => {
    expect(resolveTicketDetailTab('money', 'import')).toBe(DEFAULT_TICKET_DETAIL_TAB_ID);
    expect(resolveTicketDetailTab('quotations', 'account')).toBe(DEFAULT_TICKET_DETAIL_TAB_ID);
  });

  it('falls back to overview for an absent or unknown tab id', () => {
    expect(resolveTicketDetailTab(null, 'sales')).toBe(DEFAULT_TICKET_DETAIL_TAB_ID);
    expect(resolveTicketDetailTab(undefined, 'sales')).toBe(DEFAULT_TICKET_DETAIL_TAB_ID);
    expect(resolveTicketDetailTab('not-a-real-tab', 'sales')).toBe(DEFAULT_TICKET_DETAIL_TAB_ID);
  });
});
