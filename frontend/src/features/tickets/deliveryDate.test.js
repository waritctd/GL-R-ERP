import { describe, expect, it } from 'vitest';
import { deliveryDateBounds, orderConfirmedOn } from './deliveryDate.js';

// Slice 6d fix round 1 (M2, L1): the วันที่ส่ง window both delivery controls offer — the server's rule
// (TicketService#backDatedDeliveryInstant): from the later of today − 7 and the Bangkok day the order was confirmed,
// up to today. "Order confirmed" mirrors TicketRepository.ORDER_CONFIRMED_AT_SQL: the latest DEAL_PROMOTED_FROM_QUOTATION
// event, else the latest CUSTOMER_CONFIRMED. The server enforces it; this only keeps the control honest.

const TODAY = '2026-11-17';
const ev = (kind, createdAt) => ({ id: Math.random(), kind, createdAt });

describe('orderConfirmedOn', () => {
  it('is null with no confirmation event', () => {
    expect(orderConfirmedOn([ev('CREATED', '2026-11-01T03:00:00Z')])).toBeNull();
    expect(orderConfirmedOn(undefined)).toBeNull();
  });

  it('is the Asia/Bangkok day of the event, not the UTC one', () => {
    // 00:30 Bangkok on 14 Nov is 17:30 UTC on 13 Nov.
    expect(orderConfirmedOn([ev('CUSTOMER_CONFIRMED', '2026-11-13T17:30:00Z')])).toBe('2026-11-14');
  });

  it('takes the latest confirm-from-quotation event first, whatever the CUSTOMER_CONFIRMED dates', () => {
    expect(orderConfirmedOn([
      ev('DEAL_PROMOTED_FROM_QUOTATION', '2026-11-10T03:00:00Z'),
      ev('CUSTOMER_CONFIRMED', '2026-11-16T03:00:00Z'),
      ev('DEAL_PROMOTED_FROM_QUOTATION', '2026-11-12T03:00:00Z'),
    ])).toBe('2026-11-12');
  });

  it('else the latest CUSTOMER_CONFIRMED', () => {
    expect(orderConfirmedOn([
      ev('CUSTOMER_CONFIRMED', '2026-11-09T03:00:00Z'), ev('CUSTOMER_CONFIRMED', '2026-11-11T03:00:00Z'),
    ])).toBe('2026-11-11');
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

  it('a confirmation older than the window leaves today − 7', () => {
    expect(deliveryDateBounds(TODAY, [ev('CUSTOMER_CONFIRMED', '2026-10-01T03:00:00Z')]))
      .toEqual({ min: '2026-11-10', max: TODAY, confirmedOn: null });
  });
});
