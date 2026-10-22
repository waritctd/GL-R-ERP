import { describe, expect, it } from 'vitest';
import { dealHref } from './dealHref.js';

describe('dealHref', () => {
  it('account is sent to the finance deal page; every other role keeps the ticket page', () => {
    expect(dealHref('account', 12)).toBe('/finance/deals/12');
    expect(dealHref('ceo', 12)).toBe('/tickets/12');
    expect(dealHref('sales', 12)).toBe('/tickets/12');
    expect(dealHref(undefined, 12)).toBe('/tickets/12');
  });
});
