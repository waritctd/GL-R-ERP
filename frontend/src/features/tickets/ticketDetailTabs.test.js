import { describe, expect, it } from 'vitest';
import {
  DEFAULT_TICKET_DETAIL_TAB_ID, LEGACY_TICKET_DETAIL_TAB_IDS, resolveTicketDetailTab, TICKET_DETAIL_TABS,
  visibleTicketDetailTabIds,
} from './ticketDetailTabs.js';

// The deal page's five tabs (drawing a-deal, 2026-11-16): ภาพรวม · ใบเสนอราคา · เงิน · ส่งของ · ไฟล์.
describe('TICKET_DETAIL_TABS', () => {
  it('is the five drawn tabs, in the drawn order, with the drawn labels', () => {
    expect(TICKET_DETAIL_TABS.map(({ id, label }) => [id, label])).toEqual([
      ['overview', 'ภาพรวม'],
      ['quote', 'ใบเสนอราคา'],
      ['money', 'เงิน'],
      ['ship', 'ส่งของ'],
      ['files', 'ไฟล์'],
    ]);
  });

  it('carries no English/secondary helper line (the drawn tab bar has none)', () => {
    expect(TICKET_DETAIL_TABS.every((tab) => tab.helper == null)).toBe(true);
  });

  it('the default tab is ภาพรวม', () => {
    expect(DEFAULT_TICKET_DETAIL_TAB_ID).toBe('overview');
  });
});

describe('visibleTicketDetailTabIds', () => {
  it('sales, sales_manager and ceo see all five tabs', () => {
    for (const role of ['sales', 'sales_manager', 'ceo']) {
      expect(visibleTicketDetailTabIds(role)).toEqual(['overview', 'quote', 'money', 'ship', 'files']);
    }
  });

  // The money and ship tabs keep the gates the old การเงิน / จัดซื้อ-ส่งมอบ tabs had (sections.payment ||
  // sections.depositNotice, and sections.delivery); the rest were role-unconditional before and still are.
  it('account (redirected off this page by the route) would still keep every tab', () => {
    expect(visibleTicketDetailTabIds('account')).toEqual(['overview', 'quote', 'money', 'ship', 'files']);
  });

  it('import (redirected off this page by the route) loses เงิน, exactly as it lost การเงิน', () => {
    expect(visibleTicketDetailTabIds('import')).toEqual(['overview', 'quote', 'ship', 'files']);
  });

  it('an unknown role only gets the ungated tabs (wrong-way-round: no money, no ship)', () => {
    expect(visibleTicketDetailTabIds('hr')).toEqual(['overview', 'quote', 'files']);
    expect(visibleTicketDetailTabIds('employee')).toEqual(['overview', 'quote', 'files']);
  });
});

describe('resolveTicketDetailTab', () => {
  it('keeps a new tab id the role may see', () => {
    expect(resolveTicketDetailTab('quote', 'sales')).toBe('quote');
    expect(resolveTicketDetailTab('money', 'ceo')).toBe('money');
    expect(resolveTicketDetailTab('files', 'sales_manager')).toBe('files');
  });

  // Old ?tab= keys keep working: bookmarks, shared links and the e2e suite still carry them.
  it.each([
    ['deal', 'overview'],
    ['history', 'overview'],
    ['items', 'quote'],
    ['documents', 'files'],
    ['money', 'money'],
    ['fulfilment', 'ship'],
  ])('old key ?tab=%s lands on %s', (oldKey, newKey) => {
    expect(LEGACY_TICKET_DETAIL_TAB_IDS[oldKey] ?? oldKey).toBe(newKey);
    expect(resolveTicketDetailTab(oldKey, 'sales')).toBe(newKey);
  });

  it('an old key whose new tab the role may NOT see still falls back to ภาพรวม', () => {
    expect(resolveTicketDetailTab('money', 'import')).toBe('overview');
    expect(resolveTicketDetailTab('fulfilment', 'hr')).toBe('overview');
  });

  it('falls back to ภาพรวม for an absent or unknown tab id', () => {
    expect(resolveTicketDetailTab(null, 'sales')).toBe(DEFAULT_TICKET_DETAIL_TAB_ID);
    expect(resolveTicketDetailTab(undefined, 'sales')).toBe(DEFAULT_TICKET_DETAIL_TAB_ID);
    expect(resolveTicketDetailTab('not-a-real-tab', 'sales')).toBe(DEFAULT_TICKET_DETAIL_TAB_ID);
  });
});
