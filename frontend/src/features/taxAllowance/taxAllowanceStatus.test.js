import { describe, expect, it } from 'vitest';
import {
  applicablePayrollMonth, hasAllowanceDisagreement, resolvePayrollAllowance,
} from './taxAllowanceStatus.js';

// Guards `resolvePayrollAllowance`/`applicablePayrollMonth` against
// `PayrollRepository#findTaxAllowancesByEmployee` (backend/.../payroll/PayrollRepository.java, ~lines
// 407-427): SELECT DISTINCT ON (employee_id) ...

function row({ employeeId = 1, effectiveMonth, verificationStatus = 'VERIFIED', allowances = {} }) {
  return { employeeId, effectiveMonth, verificationStatus, allowances };
}

describe('applicablePayrollMonth', () => {
  const today = new Date(2026, 7, 7); // 7 Aug 2026 -- Date months are 0-indexed, so index 7 = August

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

  it('excludes a row dated AFTER the resolved month, even though it exists (the effective_month <= filter)', () => {
    const items = [row({ employeeId: 1, effectiveMonth: 9 })]; // 9 > applicablePayrollMonth's 8
    expect(resolvePayrollAllowance(items, 1, 2026, today)).toBeNull();
  });

  it('falls back to EXPIRED when every qualifying candidate is EXPIRED_UNVERIFIED', () => {
    const items = [
      row({ employeeId: 1, effectiveMonth: 1, verificationStatus: 'EXPIRED_UNVERIFIED' }),
      row({ employeeId: 1, effectiveMonth: 5, verificationStatus: 'EXPIRED_UNVERIFIED' }),
    ];
    // The LATEST expired row, not the first one in the array.
    expect(resolvePayrollAllowance(items, 1, 2026, today)).toEqual({ expired: items[1] });
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
});

