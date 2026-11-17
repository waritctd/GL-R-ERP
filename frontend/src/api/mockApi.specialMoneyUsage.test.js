import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// Guards `specialMoney.usage()` against SpecialMoneyRepository#findUsage, whose three maps are counted over
// deliberately DIFFERENT status sets (see UsageSnapshot's javadoc): approvedAmountThisYearByType -> APPROVED
// only.

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

    // The new row is SUBMITTED (mock create() always lands there, mirroring the Java service), so it must move
    // both counts...
    expect(delta('approvedCountLifetimeByType', 'MEDICAL')).toBe(1);
    expect(delta('activeCountThisYearByType', 'MEDICAL')).toBe(1);
    // ...and must not move the money, which is APPROVED-only. Asserting both sides on the one fixture is what
    // separates "the status set is right" from "the row was never created".
    expect(delta('approvedAmountThisYearByType', 'MEDICAL')).toBe(0);
  });

  it('scopes the per-year count to requestedAt (submission time), not the employee-supplied eventDate', async () => {
    const { user } = await api.auth.login({ role: 'employee' });
    const before = await usageFor(user.employeeId);

    // eventDate two years back. The lifetime count was never year-scoped either way.
    await api.specialMoney.create({
      requestType: 'AID_ORDINATION',
      eventDate: `${new Date().getFullYear() - 2}-05-01`,
      requestedAmount: 5000,
      reason: 'บวช',
    });

    const after = await usageFor(user.employeeId);
    const delta = (map, key) => Number(after[map]?.[key] || 0) - Number(before[map]?.[key] || 0);

    expect(delta('approvedCountLifetimeByType', 'AID_ORDINATION')).toBe(1);
    expect(delta('activeCountThisYearByType', 'AID_ORDINATION')).toBe(1);
  });

  it('scopes the APPROVED amount sum to payrollMonth (assigned at approval), not the employee-supplied eventDate', async () => {
    const { user } = await api.auth.login({ role: 'employee' });
    const before = await usageFor(user.employeeId);

    // eventDate three years in the future -- the exact shape of the real P0 repro: an employee- supplied
    // event_date in a year nothing has been approved against yet.
    const created = await api.specialMoney.create({
      requestType: 'MEDICAL',
      eventDate: `${new Date().getFullYear() + 3}-06-01`,
      requestedAmount: 900,
      reason: 'ค่ารักษาพยาบาล',
    });
    await api.specialMoney.addAttachment(created.request.id, {
      name: 'receipt.pdf', type: 'application/pdf', size: 1024,
    });

    await api.auth.login({ role: 'ceo' });
    await api.specialMoney.approve(created.request.id, {});

    const after = await usageFor(user.employeeId);
    const delta = (map, key) => Number(after[map]?.[key] || 0) - Number(before[map]?.[key] || 0);

    // Counted against THIS year despite the eventDate claiming three years from now, because payrollMonth --
    // assigned "today" by approve() -- falls in this year.
    expect(delta('approvedAmountThisYearByType', 'MEDICAL')).toBe(900);
  });
});
