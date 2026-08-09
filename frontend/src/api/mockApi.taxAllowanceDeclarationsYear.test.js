import { describe, it, expect, beforeEach } from 'vitest';
import { api } from './mockApi.js';

/**
 * Guards that the mock HONOURS its `year` argument.
 *
 * It did not until 2026-08-10: the mock's signature was `(params = {})` reading `params.year`,
 * while hrApi's is `getMyTaxAllowanceDeclarations(year)` and both production callers
 * (TaxAllowancePage, useHrData) pass a plain number. A number has no `.year`, so every request
 * silently fell back to the current year — the tax-year selector on /tax-allowance did nothing
 * under mocks, and past years rendered the current year's data.
 *
 * ⚠️ `contract.test.js` cannot catch this class of defect. Its arity check compares parameter
 * COUNTS, and `(params)` and `(year)` are both 1. CLAUDE.md names this exact shape — "mock drops an
 * argument the real API honours" — as one of three ways a green suite is evidence of nothing; the
 * `limit` defect behind #434 was the same mechanism. Argument HANDLING needs a behavioural test,
 * which is what this file is.
 */
describe('mock getMyTaxAllowanceDeclarations — the year argument', () => {
  beforeEach(async () => {
    await api.auth.login({ role: 'employee' });
  });

  it('echoes back the year it was asked for, not the current one', async () => {
    const pastYear = new Date().getFullYear() - 2;

    const response = await api.payroll.getMyTaxAllowanceDeclarations(pastYear);

    expect(response.taxYear).toBe(pastYear);
  });

  it('filters the items to that year rather than returning the current year\'s', async () => {
    const currentYear = new Date().getFullYear();
    const pastYear = currentYear - 2;

    const past = await api.payroll.getMyTaxAllowanceDeclarations(pastYear);
    const now = await api.payroll.getMyTaxAllowanceDeclarations(currentYear);

    // Every returned row must belong to the year asked for — the assertion the old params-bag
    // signature could never fail, because it always resolved to the current year.
    expect(past.items.every((row) => row.taxYear === pastYear)).toBe(true);
    expect(now.items.every((row) => row.taxYear === currentYear)).toBe(true);
  });

  it('still defaults to the current year when given nothing', async () => {
    const response = await api.payroll.getMyTaxAllowanceDeclarations();

    expect(response.taxYear).toBe(new Date().getFullYear());
  });
});
