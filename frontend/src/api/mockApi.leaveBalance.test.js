import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// Guards mockApi's leave.balances() SHAPE directly against the mock module (not through a UI test) -- see
// CLAUDE.md "Mock API contract".
describe('mockApi.leave.balances shape parity (V161)', () => {
  it('every balance carries carriedInRemainingDays/ownQuotaRemainingDays/carriedInFromYear/carriedInExpiresOn', async () => {
    await api.auth.login({ role: 'employee' });
    const { balances } = await api.leave.balances({});

    expect(balances.length).toBeGreaterThan(0);
    balances.forEach((balance) => {
      expect(balance).toHaveProperty('carriedInRemainingDays');
      expect(balance).toHaveProperty('ownQuotaRemainingDays');
      expect(balance).toHaveProperty('carriedInFromYear');
      expect(balance).toHaveProperty('carriedInExpiresOn');
    });
  });

  it('reports the mock\'s deliberate "nothing carried in" reading for VACATION, never a fabricated grant', async () => {
    await api.auth.login({ role: 'employee' });
    const { balances } = await api.leave.balances({});
    const vacation = balances.find((balance) => balance.leaveTypeCode === 'VACATION');

    expect(vacation).toBeDefined();
    expect(vacation.carriedInDays).toBe(0);
    expect(vacation.carriedInFromYear).toBeNull();
    expect(vacation.carriedInExpiresOn).toBeNull();
    expect(vacation.carriedInRemainingDays).toBe(0);
    // Only one pool exists in mock mode, so ownQuotaRemainingDays must equal the same merged remainingDays figure
    // the rest of the app already reads -- never a second, divergent number.
    expect(vacation.ownQuotaRemainingDays).toBe(vacation.remainingDays);
  });
});
