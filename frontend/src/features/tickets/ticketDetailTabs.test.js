import { describe, expect, it } from 'vitest';
import {
  resolveTicketDetailTab,
  visibleTicketDetailTabIds,
} from './ticketDetailTabs.js';

describe('visibleTicketDetailTabIds', () => {
  it('sales, sales_manager and ceo see all five tabs', () => {
    for (const role of ['sales', 'sales_manager', 'ceo']) {
      expect(visibleTicketDetailTabIds(role)).toEqual(['overview', 'quote', 'money', 'ship', 'files']);
    }
  });

  it('import (redirected off this page by the route) loses เงิน, exactly as it lost การเงิน', () => {
    expect(visibleTicketDetailTabIds('import')).toEqual(['overview', 'quote', 'ship', 'files']);
  });
});

describe('resolveTicketDetailTab', () => {
  it('an old key whose new tab the role may NOT see still falls back to ภาพรวม', () => {
    expect(resolveTicketDetailTab('money', 'import')).toBe('overview');
    expect(resolveTicketDetailTab('fulfilment', 'hr')).toBe('overview');
  });
});
