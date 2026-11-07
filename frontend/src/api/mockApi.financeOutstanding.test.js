import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// ค้างรับ (DashboardRepository#financeOutstanding) must NOT shrink when final payment is gated until
// DELIVERY_SCHEDULING (owner ruling 2026-11-07): a balance still owed while goods are in transit
// (e.g. deals 12/13/21 at PROCUREMENT) stays counted. Pins the seeded mock figure.
describe('mockApi dashboard.summary -- financeOutstanding', () => {
  it('keeps counting balances owed on deals still in transit (seed total 3,597,250)', async () => {
    await api.auth.login({ role: 'ceo' });
    const { summary } = await api.dashboard.summary();
    expect(summary.financeOutstanding).toBe(3597250);
  });
});
