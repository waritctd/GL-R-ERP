import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// Commission page redesign: the real monthly-summary / payroll-ready DTOs grew new fields
// (rawCommissionableBase, weightUpliftBase, stockBonusAmount, teamOverrideAmount,
// companyCommissionableBase, teamOverrideThresholdBase, teamOverrideRatePercent; payroll-ready:
// per-rep teamOverrideAmount, totalTeamOverrideAmount, companyCommissionableBase). The mock mirrors
// the SHAPE so CommissionPage renders under VITE_USE_MOCKS=true, and deliberately invents nothing:
// it has no tier config, no stock uplift and no override-recipient list, so a mock rep is never a
// team-override recipient (company base null, amount 0) and no uplift/stock bonus is fabricated.
// This proves the shape only -- the maths is CommissionService's, proven against real Postgres.
describe('mock commission DTOs carry the new team-override / weighting fields honestly', () => {
  it('monthlySummary: new fields present; a non-recipient gets company base null and team override 0', async () => {
    await api.auth.login({ role: 'sales' });
    const { summary } = await api.commissions.monthlySummary({ payrollMonth: '2026-08' });

    expect(summary).toHaveProperty('rawCommissionableBase');
    expect(summary).toHaveProperty('weightUpliftBase', 0);
    expect(summary).toHaveProperty('stockBonusAmount', 0);
    expect(summary).toHaveProperty('teamOverrideAmount', 0);
    expect(summary.companyCommissionableBase).toBeNull();
    expect(summary.teamOverrideThresholdBase).toBeNull();
    expect(summary.teamOverrideRatePercent).toBeNull();
    // With no mock uplift, the raw (pre-weighting) base IS the weighted base.
    expect(summary.rawCommissionableBase).toBe(summary.commissionableBase);
    // totalCommission now includes the two new limbs; with both 0 it is still tier + incentive + manual.
    expect(summary.totalCommission).toBeCloseTo(summary.tierCommission + summary.incentiveAmount + summary.manualTotal, 2);
  });

  it('payrollReady: every rep carries teamOverrideAmount, and the top level carries the total and the company base', async () => {
    await api.auth.login({ role: 'hr' });
    const { summary } = await api.commissions.payrollReady({ payrollMonth: '2026-08' });

    expect(summary).toHaveProperty('totalTeamOverrideAmount', 0);
    // Mirrors the backend: null when no override applies in the month (a mock rep is never a recipient).
    expect(summary.companyCommissionableBase).toBeNull();
    summary.salesReps.forEach((rep) => expect(rep).toHaveProperty('teamOverrideAmount', 0));
  });
});
