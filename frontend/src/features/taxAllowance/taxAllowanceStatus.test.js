import { describe, expect, it } from 'vitest';
import {
  applicablePayrollMonth, hasAllowanceDisagreement, payrollVerificationInfo, resolvePayrollAllowance,
  taxAllowanceStatusInfo, taxAllowanceStatusShortLabel,
} from './taxAllowanceStatus.js';

// Guards `resolvePayrollAllowance`/`applicablePayrollMonth` against
// `PayrollRepository#findTaxAllowancesByEmployee` (backend/.../payroll/PayrollRepository.java,
// ~lines 407-427):
//
//   SELECT DISTINCT ON (employee_id) ...
//     FROM hr.employee_tax_allowance
//    WHERE tax_year = :taxYear AND effective_month <= :payrollMonthValue
//      AND verification_status <> 'EXPIRED_UNVERIFIED'
//    ORDER BY employee_id, effective_month DESC
//
// This file did not exist before the review that produced it (CLAUDE.md F2): every OTHER fixture in
// this codebase that exercises this mirror (TaxAllowanceReviewPage.test.jsx, demoPayroll.js) happens
// to give each employee exactly one row at effective_month 1, so the interesting behaviour --
// multi-row resolution, the expired-fallback, the `<=` boundary, and the past/current/future `today`
// threading -- was never actually exercised. Run alone, each of these left every existing test green:
// killing the expired-fallback (returning `null` instead of `{ expired }`), inverting all three
// `applicablePayrollMonth` branches, and deleting the `effective_month <=` filter entirely.
//
// Mutation-checked during development (2026-08) directly against this file plus
// TaxAllowanceReviewPage.test.jsx, one mutation at a time, restored from a golden copy and
// re-verified via `diff` back to byte-identical before moving to the next -- see this branch's PR
// body for the exact commands run:
//   - Deleting the `effective_month <=` filter (`resolvePayrollAllowance`'s `candidates` predicate
//     reduced to just the employeeId match) turned exactly 3 tests red: this file's "excludes a row
//     dated AFTER the resolved month", and both FUTURE-tax-year cases under "tax-year threading
//     (F3)" ("never resolves any row" / "also resolves to null, not the expired fallback") -- a
//     future year's month-0 threshold is where dropping the filter first stops mattering to admit a
//     bogus candidate. All 50 other tests in both files stayed green, including "includes a row
//     dated EXACTLY on the resolved month", which this mutation cannot break (dropping a filter only
//     ever ADDS false candidates, never removes a true one).
//   - Removing the expired-fallback (`return { expired: latestOf(candidates) };` replaced with
//     `return null;`) turned exactly 4 tests red: this file's "falls back to EXPIRED when every
//     qualifying candidate is EXPIRED_UNVERIFIED", plus three TaxAllowanceReviewPage.test.jsx
//     integration tests that depend on the `{ expired }` shape actually reaching the UI --
//     "distinguishes an EXPIRED_UNVERIFIED payroll row from both the applying and the empty state",
//     the F4 cross-surface regression test, and the F6 GRANDFATHERED_EXPIRED chip test. 49 other
//     tests stayed green.
// `today` is passed explicitly throughout so none of this depends on the real clock.

function row({ employeeId = 1, effectiveMonth, verificationStatus = 'VERIFIED', allowances = {} }) {
  return { employeeId, effectiveMonth, verificationStatus, allowances };
}

describe('applicablePayrollMonth', () => {
  const today = new Date(2026, 7, 7); // 7 Aug 2026 -- Date months are 0-indexed, so index 7 = August

  it('uses the real calendar month for the CURRENT tax year', () => {
    expect(applicablePayrollMonth(2026, today)).toBe(8);
  });

  it('resolves a PAST tax year to December (F3) -- the year already ran its full course', () => {
    expect(applicablePayrollMonth(2025, today)).toBe(12);
    expect(applicablePayrollMonth(2020, today)).toBe(12);
  });

  it('resolves a FUTURE tax year to 0 (F3) -- no effective_month (1-12) can ever be <=, so nothing applies yet', () => {
    expect(applicablePayrollMonth(2027, today)).toBe(0);
    expect(applicablePayrollMonth(2030, today)).toBe(0);
  });
});

describe('resolvePayrollAllowance', () => {
  const today = new Date(2026, 7, 7); // August 2026 -> applicablePayrollMonth(2026, today) === 8

  it('picks the LATEST qualifying effective_month among several rows for the same employee', () => {
    const items = [
      row({ employeeId: 1, effectiveMonth: 6 }),
      row({ employeeId: 1, effectiveMonth: 1 }),
      row({ employeeId: 1, effectiveMonth: 3 }),
    ];
    // Deliberately not array-order 0 -- proves this resolves by effectiveMonth, not "last seen".
    expect(resolvePayrollAllowance(items, 1, 2026, today)).toEqual({ applying: items[0] });
  });

  it('ignores rows belonging to OTHER employees entirely', () => {
    const items = [
      row({ employeeId: 2, effectiveMonth: 8 }),
      row({ employeeId: 1, effectiveMonth: 3 }),
    ];
    expect(resolvePayrollAllowance(items, 1, 2026, today)).toEqual({ applying: items[1] });
  });

  it('excludes a row dated AFTER the resolved month, even though it exists (the effective_month <= filter)', () => {
    const items = [row({ employeeId: 1, effectiveMonth: 9 })]; // 9 > applicablePayrollMonth's 8
    expect(resolvePayrollAllowance(items, 1, 2026, today)).toBeNull();
  });

  it('includes a row dated EXACTLY on the resolved month (<=, not <)', () => {
    const items = [row({ employeeId: 1, effectiveMonth: 8 })];
    expect(resolvePayrollAllowance(items, 1, 2026, today)).toEqual({ applying: items[0] });
  });

  it('falls back to EXPIRED when every qualifying candidate is EXPIRED_UNVERIFIED', () => {
    const items = [
      row({ employeeId: 1, effectiveMonth: 1, verificationStatus: 'EXPIRED_UNVERIFIED' }),
      row({ employeeId: 1, effectiveMonth: 5, verificationStatus: 'EXPIRED_UNVERIFIED' }),
    ];
    // The LATEST expired row, not the first one in the array.
    expect(resolvePayrollAllowance(items, 1, 2026, today)).toEqual({ expired: items[1] });
  });

  it('falls BACK to an earlier qualifying row when only the LATEST-dated row is expired -- never to "nothing"', () => {
    // Mirrors the SQL exactly: the WHERE clause excludes EXPIRED_UNVERIFIED rows before DISTINCT ON
    // ever picks a winner, so an employee whose latest row lapsed still resolves to their earlier,
    // still-qualifying row -- not to "no row at all" the way a naive "just take the latest row and
    // check its status" implementation would.
    const items = [
      row({ employeeId: 1, effectiveMonth: 3, verificationStatus: 'VERIFIED' }),
      row({ employeeId: 1, effectiveMonth: 7, verificationStatus: 'EXPIRED_UNVERIFIED' }),
    ];
    expect(resolvePayrollAllowance(items, 1, 2026, today)).toEqual({ applying: items[0] });
  });

  it('returns null when no row exists for the employee at all', () => {
    const items = [row({ employeeId: 2, effectiveMonth: 1 })];
    expect(resolvePayrollAllowance(items, 1, 2026, today)).toBeNull();
  });

  describe('tax-year threading (F3)', () => {
    it('a PAST tax year resolves against the year-end state (month 12), not the current calendar month', () => {
      const items = [
        row({ employeeId: 1, effectiveMonth: 6 }),
        row({ employeeId: 1, effectiveMonth: 11 }),
      ];
      // `today` is August 2026 -- if month resolution ignored `taxYear` and just used the real
      // calendar month, effectiveMonth 11 (November) would be wrongly excluded (11 > 8).
      expect(resolvePayrollAllowance(items, 1, 2025, today)).toEqual({ applying: items[1] });
    });

    it('a FUTURE tax year never resolves any row, even one dated at effective_month 1', () => {
      const items = [row({ employeeId: 1, effectiveMonth: 1 })];
      expect(resolvePayrollAllowance(items, 1, 2027, today)).toBeNull();
    });

    it('a FUTURE tax year with an EXPIRED_UNVERIFIED-only row also resolves to null, not the expired fallback', () => {
      // The expired fallback only exists among CANDIDATES (effective_month <= month); month 0 for a
      // future year admits no candidates at all, so there is nothing to fall back to.
      const items = [row({ employeeId: 1, effectiveMonth: 1, verificationStatus: 'EXPIRED_UNVERIFIED' })];
      expect(resolvePayrollAllowance(items, 1, 2027, today)).toBeNull();
    });
  });
});

describe('hasAllowanceDisagreement (F4) — the one predicate both register surfaces now share', () => {
  const declaration = { allowances: { spouseAllowance: 50000 } };

  it('true when an APPLYING payroll row differs from the declared total', () => {
    const applying = { verificationStatus: 'VERIFIED', allowances: { spouseAllowance: 60000 } };
    expect(hasAllowanceDisagreement(declaration, applying)).toBe(true);
  });

  it('false when an APPLYING payroll row agrees with the declared total exactly', () => {
    const applying = { verificationStatus: 'GRANDFATHERED_UNVERIFIED', allowances: { spouseAllowance: 50000 } };
    expect(hasAllowanceDisagreement(declaration, applying)).toBe(false);
  });

  // The F4 fix itself: TaxAllowanceReviewPage's summary badge already excluded an EXPIRED_UNVERIFIED
  // row from this flag; TaxAllowanceBreakdown's expanded panel did not, and nothing caught the two
  // surfaces disagreeing with EACH OTHER. Both now call this same function, so this one case pins
  // the correct answer for both at once.
  it('false against an EXPIRED_UNVERIFIED row, even when the totals plainly differ', () => {
    const expired = { verificationStatus: 'EXPIRED_UNVERIFIED', allowances: { spouseAllowance: 999999 } };
    expect(hasAllowanceDisagreement(declaration, expired)).toBe(false);
  });

  it('false with no declaration, regardless of the payroll figure', () => {
    const applying = { verificationStatus: 'VERIFIED', allowances: { spouseAllowance: 60000 } };
    expect(hasAllowanceDisagreement(null, applying)).toBe(false);
  });

  it('false with no payroll allowance, regardless of the declaration', () => {
    expect(hasAllowanceDisagreement(declaration, null)).toBe(false);
  });
});

describe('taxAllowanceStatusInfo — no-declaration payroll states', () => {
  it('GRANDFATHERED_APPLIED when no declaration exists but a payroll row is applying', () => {
    const info = taxAllowanceStatusInfo(null, { applying: { verificationStatus: 'GRANDFATHERED_UNVERIFIED' } });
    expect(info.key).toBe('GRANDFATHERED_APPLIED');
    expect(info.tone).toBe('warning');
  });

  it('GRANDFATHERED_EXPIRED when no declaration exists and the payroll row is the expired fallback', () => {
    const info = taxAllowanceStatusInfo(null, { expired: { verificationStatus: 'EXPIRED_UNVERIFIED' } });
    expect(info.key).toBe('GRANDFATHERED_EXPIRED');
    expect(info.tone).toBe('danger');
  });

  it('NONE when there is neither a declaration nor any payroll resolution', () => {
    expect(taxAllowanceStatusInfo(null, null).key).toBe('NONE');
  });

  it('a declaration is always authoritative over a payroll resolution, even a conflicting one', () => {
    const declaration = { status: 'PENDING' };
    const info = taxAllowanceStatusInfo(declaration, { applying: { verificationStatus: 'VERIFIED' } });
    expect(info.key).toBe('PENDING');
  });
});

describe('GRANDFATHERED_EXPIRED short label + verification copy (F6)', () => {
  it('has its own short chip label, distinct from GRANDFATHERED_APPLIED and from the raw key', () => {
    const expiredLabel = taxAllowanceStatusShortLabel('GRANDFATHERED_EXPIRED');
    expect(expiredLabel).not.toBe('GRANDFATHERED_EXPIRED'); // not just the unrecognised-key fallback
    expect(expiredLabel).not.toBe(taxAllowanceStatusShortLabel('GRANDFATHERED_APPLIED'));
  });

  it('payrollVerificationInfo covers all three verification_status values with distinct tones', () => {
    expect(payrollVerificationInfo('VERIFIED').tone).toBe('success');
    expect(payrollVerificationInfo('GRANDFATHERED_UNVERIFIED').tone).toBe('warning');
    expect(payrollVerificationInfo('EXPIRED_UNVERIFIED').tone).toBe('danger');
  });
});

describe('taxAllowanceStatusInfo — the APPLIED badge after the whole-year ruling', () => {
  // Owner ruling 2026-08-31: approval promotes the declaration for its whole tax year, always at
  // effective month 1. Naming that month would put a meaningless "ตั้งแต่เดือน 1" on every current
  // declaration, so month 1 reads as ทั้งปีภาษี instead. Pre-ruling rows dated 2–12 must keep
  // naming their month — they genuinely only apply from there on, and collapsing them into
  // "ทั้งปีภาษี" would claim a coverage the payroll SQL does not give them.
  const applied = (appliedEffectiveMonth) => taxAllowanceStatusInfo(
    { status: 'APPROVED', appliedAt: '2026-08-31T00:00:00.000Z', appliedEffectiveMonth }, null,
  );

  it('reads ทั้งปีภาษี for a whole-year row, with no month named', () => {
    const info = applied(1);
    expect(info.key).toBe('APPLIED');
    expect(info.label).toBe('ใช้กับเงินเดือนแล้ว ทั้งปีภาษี');
    expect(info.label).not.toMatch(/เดือน 1/);
  });

  it('still names the month of a pre-ruling mid-year row', () => {
    expect(applied(7).label).toBe('ใช้กับเงินเดือนแล้ว ตั้งแต่เดือน 7');
  });
});
