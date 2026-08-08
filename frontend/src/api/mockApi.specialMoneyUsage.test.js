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
//
// P0 fix (fix/welfare-cap-year-bypass): the two year-scoped maps ALSO no longer key on `eventDate`
// -- an employee-supplied field with no bound (SubmitSpecialMoneyHttpRequest marks it @NotNull
// only), which let the real backend's annual cap be defeated by filing against a year nothing had
// been approved against yet. They now key on the SAME two server-stamped columns the real
// SpecialMoneyRepository#findUsage does post-fix: `requestedAt` (stamped at create(), never from
// the payload) for the counts, `payrollMonth` (assigned by approve(), never from the payload) for
// the APPROVED amount sum. The tests below were written when `eventDate` was still the key and
// therefore encoded the OLD, vulnerable behaviour as their expectation -- see the second test's
// comment for why its assertion is now the opposite of what it used to check.

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

  it('scopes the per-year count to requestedAt (submission time), not the employee-supplied eventDate', async () => {
    const { user } = await api.auth.login({ role: 'employee' });
    const before = await usageFor(user.employeeId);

    // eventDate two years back -- under the OLD, vulnerable mock (and the OLD, vulnerable real
    // backend) this kept a row OUT of "this year"'s count, which is exactly the hole the P0 fix
    // closes: `create()` always stamps `requestedAt` to the real "now" (there is no payload field
    // that can override it, mirroring the server-side DEFAULT now() column the real fix relies on),
    // so a request filed TODAY belongs to THIS year's once-per-lifetime/once-per-year accounting
    // regardless of what eventDate claims. The lifetime count was never year-scoped either way.
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

    // eventDate three years in the future -- the exact shape of the real P0 repro: an employee-
    // supplied event_date in a year nothing has been approved against yet. Under the fixed
    // semantics this must NOT matter: the amount is bucketed by payrollMonth, which approve()
    // assigns from the mock's own clock and which the caller cannot influence via the payload.
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

    // Counted against THIS year despite the eventDate claiming three years from now, because
    // payrollMonth -- assigned "today" by approve() -- falls in this year.
    expect(delta('approvedAmountThisYearByType', 'MEDICAL')).toBe(900);
  });
});
