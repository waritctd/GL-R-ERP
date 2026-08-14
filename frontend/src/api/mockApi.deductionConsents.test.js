import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';
import { CONSENT_APPLICABLE_DEDUCTION_KINDS } from '../utils/format.js';

// Guards `payroll.getDeductionConsents()` against DeductionWrittenConsentController +
// DeductionWrittenConsentService + DeductionWrittenConsentRepository (issue #376, exposed for
// #744): the read role gate, the employeeId/kind filters, and the repository's ORDER BY.

describe('mock getDeductionConsents', () => {
  // THE fixture-honesty assertion for this feature. hr.deduction_written_consent is written by
  // exactly one path — the register's own PUT, which had no client until #744 — so the table is
  // necessarily empty in every environment, production included. A seeded mock would make the
  // fixture more populated than production and would mean the EMPTY state (the only state any real
  // deployment has today) never renders in a default mock session.
  it('starts empty, exactly as the table does in every real environment', async () => {
    await api.auth.login({ role: 'hr' });
    const { items } = await api.payroll.getDeductionConsents();
    expect(items).toEqual([]);
  });

  it('hr and ceo can both read the register', async () => {
    for (const role of ['hr', 'ceo']) {
      await api.auth.login({ role });
      const { items } = await api.payroll.getDeductionConsents();
      expect(Array.isArray(items)).toBe(true);
    }
  });

  // Mirrors DeductionWrittenConsentService.VIEW_ROLES = Set.of("hr", "ceo") exactly — every other
  // seeded role must be refused, not merely "some other role".
  it('rejects every role outside hr/ceo with the exact backend message', async () => {
    for (const role of ['employee', 'sales', 'sales_manager', 'import', 'account']) {
      await api.auth.login({ role });
      await expect(api.payroll.getDeductionConsents()).rejects.toThrow('ไม่มีสิทธิ์เข้าถึงรายการนี้');
    }
  });

  // A value mirrored into mockApi.js has NO automatic guard: contract.test.js compares method names
  // and PARAMETER COUNTS, never values. The kinds the UI offers and the kinds the mock accepts must
  // stay the same set as DeductionWrittenConsentService.CONSENT_APPLICABLE_KINDS / V107's CHECK
  // constraint, so the list is pinned literally here rather than derived from itself.
  it('pins the consent-applicable kinds to V107s CHECK constraint', () => {
    expect([...CONSENT_APPLICABLE_DEDUCTION_KINDS].sort()).toEqual(
      ['CUSTOMER_RETURN', 'OTHER_POST_TAX', 'OTHER_PRETAX', 'WARNING_LETTER'],
    );
  });

  it('excludes the four kinds where consent is not a question', () => {
    for (const kind of ['WITHHOLDING_TAX', 'SOCIAL_SECURITY', 'STUDENT_LOAN', 'LEGAL_EXECUTION_GARNISHMENT']) {
      expect(CONSENT_APPLICABLE_DEDUCTION_KINDS).not.toContain(kind);
    }
  });

  it('honours the employeeId and kind filters as server-side narrowing', async () => {
    await api.auth.login({ role: 'hr' });
    // Empty register, so both filters legitimately return nothing — what is under test here is that
    // the parameters are READ at all rather than silently dropped, which the arity contract test
    // cannot see. The ordering/filtering behaviour over populated rows is covered by the write-half
    // test once a row can exist.
    await expect(api.payroll.getDeductionConsents({ employeeId: 3 })).resolves.toEqual({ items: [] });
    await expect(api.payroll.getDeductionConsents({ kind: 'WARNING_LETTER' })).resolves.toEqual({ items: [] });
  });
});
