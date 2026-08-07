import { describe, it, expect, beforeEach } from 'vitest';
import { api } from './mockApi.js';

// Guards `payroll.getTaxAllowanceCaps()` against TaxAllowanceCapCatalog#capsFor.
//
// The cap table is a lookup, not a computation, so mirroring it in the mock is legitimate -- but it
// is the THIRD hand-maintained copy of the same numbers (PayrollCalculator's literals, the Java
// catalogue that mirrors them, and this). The two Java copies have a real anti-drift guard:
// TaxAllowanceCapCatalogTest drives the actual calculator, so editing one without the other goes
// red. This copy had NO guard at all -- `taxAllowanceCapsFor` is not exported, and every page test
// stubs the endpoint out (`getTaxAllowanceCaps.mockResolvedValue({ caps: [] })`), so a stale mock
// number was invisible to the whole suite. Found by review while adding the Thai ESG sunset below.
//
// This file cannot prove the mock matches the JAVA (nothing in vitest can reach the backend) -- what
// it CAN do is pin the mock's own year-dependent behaviour, so the two categories whose caps move
// with the tax year can't silently stop moving.

async function capsFor(year) {
  const { caps } = await api.payroll.getTaxAllowanceCaps({ year });
  return Object.fromEntries(caps.map((cap) => [cap.category, cap]));
}

describe('mock getTaxAllowanceCaps — the two year-dependent categories', () => {
  // `/caps` is isAuthenticated() on the real controller and requireSession() here — any role will
  // do, the catalogue is not scoped.
  beforeEach(async () => {
    await api.auth.login({ role: 'employee' });
  });

  // Thai ESG's enhanced ฿300,000 ceiling covers units bought 1 Jan 2024 - 31 Dec 2026; ฿100,000
  // outside that window on EITHER side. Mirrors THAI_ESG_ENHANCED_CAP_FIRST_TAX_YEAR/_LAST_TAX_YEAR.
  it.each([
    [2023, 100000, 'before the window opened'],
    [2024, 300000, 'first enhanced year'],
    [2026, 300000, 'last enhanced year'],
    [2027, 100000, 'window closed — reverts, does NOT sunset to zero'],
  ])('thai_esg ownCap for %i is %i (%s)', async (year, expected) => {
    const { thai_esg: thaiEsg } = await capsFor(year);
    expect(thaiEsg.ownCap).toBe(expected);
    // The rate is unchanged in both regimes — only the absolute ceiling steps down. Asserted on
    // every year so a future edit cannot fix a ceiling and move the rate at the same time.
    expect(thaiEsg.incomeRate).toBe(0.30);
    // Thai ESG has its own ceiling and is NOT part of the 500,000 retirement cluster.
    expect(thaiEsg.groupId).toBeNull();
  });

  // SSF's own boundary is a genuine sunset to zero, and a one-sided one (`< 2025`). Asserted here
  // alongside Thai ESG specifically so the two conditions cannot get conflated by someone editing
  // one and pattern-matching the other -- they are deliberately different shapes.
  it.each([
    [2024, 200000, 0.30],
    [2025, 0, 0],
    [2027, 0, 0],
  ])('ssf ownCap for %i is %i with rate %f', async (year, expectedCap, expectedRate) => {
    const { ssf } = await capsFor(year);
    expect(ssf.ownCap).toBe(expectedCap);
    expect(ssf.incomeRate).toBe(expectedRate);
    // Still inside the shared retirement cluster even at zero — the group ceiling is unrelated to
    // whether this particular category still earns anything.
    expect(ssf.groupId).toBe('retirement');
    expect(ssf.groupCap).toBe(500000);
  });

  it('leaves every other category unchanged across the same year boundaries', async () => {
    const [enhanced, reverted] = await Promise.all([capsFor(2026), capsFor(2027)]);
    const moved = Object.keys(enhanced).filter(
      (category) => JSON.stringify(enhanced[category]) !== JSON.stringify(reverted[category]),
    );
    // Exactly these two, and nothing else, may differ between an enhanced and a post-window year.
    expect(moved.sort()).toEqual(['thai_esg']);
  });
});
