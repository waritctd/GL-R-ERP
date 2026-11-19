import { describe, expect, it } from 'vitest';
import { deliveryDateBounds, orderConfirmedOn } from './deliveryDate.js';

const TODAY = '2026-11-17';
const ev = (kind, createdAt) => ({ id: Math.random(), kind, createdAt });

describe('orderConfirmedOn', () => {
  it('is the Asia/Bangkok day of the event, not the UTC one', () => {
    // 00:30 Bangkok on 14 Nov is 17:30 UTC on 13 Nov.
    expect(orderConfirmedOn([ev('CUSTOMER_CONFIRMED', '2026-11-13T17:30:00Z')])).toBe('2026-11-14');
  });
});

describe('deliveryDateBounds', () => {
  it('no confirmation: today − 7 to today, and says the 7-day rule is why', () => {
    expect(deliveryDateBounds(TODAY, [])).toEqual({ min: '2026-11-10', max: TODAY, confirmedOn: null });
  });

  it('a confirmation inside the window raises the minimum to that day', () => {
    expect(deliveryDateBounds(TODAY, [ev('CUSTOMER_CONFIRMED', '2026-11-13T17:30:00Z')]))
      .toEqual({ min: '2026-11-14', max: TODAY, confirmedOn: '2026-11-14' });
  });
});
