import { describe, it, expect, beforeEach } from 'vitest';
import { api } from './mockApi.js';

// Mirrors TicketService#reserveStock after the 2026-10-22 owner ruling: ANY declaration (total > 0)
// advances the deal to DELIVERY_SCHEDULING; FROM_STOCK still only on full coverage. Mock-only
// evidence of the MIRROR, never of production — the real rule is pinned by TicketServiceTest and
// SalesRouteWalkIntegrationTest#routeE_partialStock_splitsPerLineItem.
describe('mockApi tickets.reserveStock — stage advance mirrors TicketService', () => {
  beforeEach(async () => { await api.auth.login({ email: 'sales@glr.co.th', password: 'demo1234' }); });

  it('a partial declaration moves the deal to DELIVERY_SCHEDULING and leaves fulfillmentStatus null', async () => {
    const { ticket: { summary: before, items } } = await api.tickets.get(19);
    expect(before.salesStage).toBe('ORDER_RECEIVED');
    await api.tickets.reserveStock(19, { lines: [{ itemId: items[0].id, qtyFromStock: 100, note: null }] });
    const { ticket: { summary } } = await api.tickets.get(19);
    expect(summary.salesStage).toBe('DELIVERY_SCHEDULING');
    expect(summary.fulfillmentStatus).toBeNull();
  });

  it('an all-zero declaration moves nothing', async () => {
    const { ticket: { summary: before, items } } = await api.tickets.get(20);
    expect(before.salesStage).toBe('ORDER_RECEIVED');
    await api.tickets.reserveStock(20, { lines: [{ itemId: items[0].id, qtyFromStock: 0, note: null }] });
    const { ticket: { summary } } = await api.tickets.get(20);
    expect(summary.salesStage).toBe('ORDER_RECEIVED');
  });
});
