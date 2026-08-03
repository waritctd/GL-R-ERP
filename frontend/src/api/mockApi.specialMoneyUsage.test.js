import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// Guards `specialMoney.usage()` against SpecialMoneyRepository#findUsage, whose three maps are
// counted over deliberately DIFFERENT status sets (see UsageSnapshot's javadoc):
//
//   approvedAmountThisYearByType -> APPROVED only. Money: an undecided request has consumed none
//                                   of the annual balance.
//   approvedCountLifetimeByType  -> SUBMITTED + MANAGER_APPROVED + APPROVED, whole tenure.
//   activeCountThisYearByType    -> the same in-flight-inclusive set, scoped to the calendar year.
//
// The counts include in-flight rows on purpose: they back the once-per-lifetime and once-per-year
// guards, which must see a request that has been filed but not yet decided, or the same claim can
// be filed twice before either is reviewed and both then become approvable.
//
// The mock used to filter `status === 'APPROVED'` for BOTH maps. That is the dangerous direction
// CLAUDE.md names: it UNDER-reports usage, so mock-mode UI reads "you may still claim" for a type
// the real backend will refuse, and nothing on the page contradicts it. `approvedCountLifetimeByType`
// is also a misnomer on the DTO itself -- it has always carried the in-flight-inclusive count. Do
// not "fix" the counting to match the name; fix the name (separately, it is a wire contract).

async function usageFor(employeeId) {
  const { usage } = await api.specialMoney.usage({ employeeId, year: new Date().getFullYear() });
  return usage;
}

describe('mockApi specialMoney.usage — status sets per map', () => {
  it('counts a still-undecided request against both quota guards but not against the money', async () => {
    const { user } = await api.auth.login({ role: 'employee' });
    const before = await usageFor(user.employeeId);

    const eventDate = `${new Date().getFullYear()}-03-04`;
    await api.specialMoney.create({
      requestType: 'MEDICAL',
      eventDate,
      requestedAmount: 900,
      reason: 'ค่ารักษาพยาบาล',
    });

    const after = await usageFor(user.employeeId);
    const delta = (map, key) => Number(after[map]?.[key] || 0) - Number(before[map]?.[key] || 0);

    // The new row is SUBMITTED (mock create() always lands there, mirroring the Java service), so
    // it must move both counts...
    expect(delta('approvedCountLifetimeByType', 'MEDICAL')).toBe(1);
    expect(delta('activeCountThisYearByType', 'MEDICAL')).toBe(1);
    // ...and must not move the money, which is APPROVED-only. Asserting both sides on the one
    // fixture is what separates "the status set is right" from "the row was never created".
    expect(delta('approvedAmountThisYearByType', 'MEDICAL')).toBe(0);
  });

  it('scopes the per-year count to the year, unlike the lifetime count', async () => {
    const { user } = await api.auth.login({ role: 'employee' });
    const before = await usageFor(user.employeeId);

    // An event two years back: lifetime is not year-scoped by design (it backs the once-per-
    // lifetime AID gate and must see every prior claim), so only the lifetime count may move.
    await api.specialMoney.create({
      requestType: 'AID_ORDINATION',
      eventDate: `${new Date().getFullYear() - 2}-05-01`,
      requestedAmount: 5000,
      reason: 'บวช',
    });

    const after = await usageFor(user.employeeId);
    const delta = (map, key) => Number(after[map]?.[key] || 0) - Number(before[map]?.[key] || 0);

    expect(delta('approvedCountLifetimeByType', 'AID_ORDINATION')).toBe(1);
    expect(delta('activeCountThisYearByType', 'AID_ORDINATION')).toBe(0);
  });
});
