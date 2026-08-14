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
  //
  // ⚠️ Must stay the FIRST test in this file. mockApi's `db` is module-level and shared by every
  // test here, and there is no reset hook — once any test below records a row, the store is no
  // longer pristine. Every later assertion is written to tolerate that residue; this one cannot be.
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
    await api.payroll.upsertDeductionConsent({ employeeId: 3, deductionKind: 'WARNING_LETTER', consentOnFile: true });
    await api.payroll.upsertDeductionConsent({ employeeId: 20, deductionKind: 'CUSTOMER_RETURN', consentOnFile: false });

    expect((await api.payroll.getDeductionConsents({ employeeId: 3 })).items.map((r) => r.employeeId)).toEqual([3]);
    expect((await api.payroll.getDeductionConsents({ kind: 'CUSTOMER_RETURN' })).items.map((r) => r.employeeId))
      .toEqual([20]);
  });

  it('orders by employeeCode then deductionKind, mirroring the repository exactly', async () => {
    await api.auth.login({ role: 'hr' });
    // Written out of order on purpose — pre-sorted input would pass whatever the sort did.
    await api.payroll.upsertDeductionConsent({ employeeId: 20, deductionKind: 'OTHER_PRETAX', consentOnFile: false });
    await api.payroll.upsertDeductionConsent({ employeeId: 3, deductionKind: 'WARNING_LETTER', consentOnFile: true });
    await api.payroll.upsertDeductionConsent({ employeeId: 3, deductionKind: 'CUSTOMER_RETURN', consentOnFile: false });

    const { items } = await api.payroll.getDeductionConsents();
    // The mock's `db` is module-level and shared by every test in this file, so the register also
    // holds whatever earlier tests wrote. Narrowing to the three pairs under test — while KEEPING
    // the server's returned order — pins the sort exactly without depending on a clean store.
    const written = ['GLR-1003 CUSTOMER_RETURN', 'GLR-1003 WARNING_LETTER', 'GLR-1020 OTHER_PRETAX'];
    const observed = items
      .map((row) => `${row.employeeCode} ${row.deductionKind}`)
      .filter((key) => written.includes(key));
    // GLR-1003 before GLR-1020, and CUSTOMER_RETURN before WARNING_LETTER within the same employee.
    expect(observed).toEqual(written);
  });
});

describe('mock upsertDeductionConsent', () => {
  // The asymmetry that matters: CEO READS this register and may write NONE of it
  // (VIEW_ROLES = {hr, ceo}, EDIT_ROLES = {hr}). A mock more permissive than production is the
  // dangerous direction — issue #199 was exactly that — so CEO is asserted to be refused here even
  // though CEO is allowed on the GET above.
  it('refuses the CEO, who may read the same rows', async () => {
    await api.auth.login({ role: 'ceo' });
    await expect(api.payroll.getDeductionConsents()).resolves.toBeTruthy();
    await expect(
      api.payroll.upsertDeductionConsent({ employeeId: 3, deductionKind: 'WARNING_LETTER', consentOnFile: true }),
    ).rejects.toThrow('ไม่มีสิทธิ์เข้าถึงรายการนี้');
  });

  it('refuses every other role too', async () => {
    for (const role of ['employee', 'sales', 'sales_manager', 'import', 'account']) {
      await api.auth.login({ role });
      await expect(
        api.payroll.upsertDeductionConsent({ employeeId: 3, deductionKind: 'WARNING_LETTER', consentOnFile: true }),
      ).rejects.toThrow('ไม่มีสิทธิ์เข้าถึงรายการนี้');
    }
  });

  // V107's CHECK constraint / CONSENT_APPLICABLE_KINDS. The mock must refuse what the database
  // would refuse, or a form could look like it saved something the real backend 400s on.
  it('rejects a deduction kind where consent is not a question', async () => {
    await api.auth.login({ role: 'hr' });
    for (const kind of ['WITHHOLDING_TAX', 'SOCIAL_SECURITY', 'STUDENT_LOAN', 'LEGAL_EXECUTION_GARNISHMENT']) {
      await expect(
        api.payroll.upsertDeductionConsent({ employeeId: 3, deductionKind: kind, consentOnFile: true }),
      ).rejects.toThrow(/ไม่ใช่ประเภทที่ต้องมีหนังสือยินยอม/);
    }
  });

  it('404s on an unknown employee', async () => {
    await api.auth.login({ role: 'hr' });
    await expect(
      api.payroll.upsertDeductionConsent({ employeeId: 999999, deductionKind: 'WARNING_LETTER', consentOnFile: true }),
    ).rejects.toThrow('ไม่พบข้อมูลพนักงาน');
  });

  // UNIQUE (employee_id, deduction_kind) + ON CONFLICT DO UPDATE: the same pair overwrites, never
  // appends. A mock that appended would show two rows where the database holds one.
  it('overwrites the same (employee, kind) pair instead of appending a second row', async () => {
    await api.auth.login({ role: 'hr' });
    await api.payroll.upsertDeductionConsent({
      employeeId: 3, deductionKind: 'WARNING_LETTER', consentOnFile: false, notes: 'รอเซ็นกลับ',
    });
    await api.payroll.upsertDeductionConsent({
      employeeId: 3, deductionKind: 'WARNING_LETTER', consentOnFile: true, notes: 'ได้รับแล้ว',
    });

    // Both filters, so this is exactly the one pair regardless of what other tests left behind.
    const { items } = await api.payroll.getDeductionConsents({ employeeId: 3, kind: 'WARNING_LETTER' });
    expect(items.length).toBe(1);
    expect(items[0].consentOnFile).toBe(true);
    expect(items[0].notes).toBe('ได้รับแล้ว');
  });

  // ⚠️ The response is the ONE row written, not the register — mirrored from
  // DeductionWrittenConsentService#upsert's `return findAll(employeeId, deductionKind)`. A caller
  // that assigned this over its list state would blank the table, so the shape is pinned.
  it('returns only the row just written, not the whole register', async () => {
    await api.auth.login({ role: 'hr' });
    await api.payroll.upsertDeductionConsent({ employeeId: 20, deductionKind: 'CUSTOMER_RETURN', consentOnFile: true });
    const response = await api.payroll.upsertDeductionConsent({
      employeeId: 3, deductionKind: 'WARNING_LETTER', consentOnFile: true,
    });

    expect(response.items.length).toBe(1);
    expect(response.items[0].employeeId).toBe(3);
    // ...while the register itself holds strictly more than the write returned, including the other
    // pair. Compared relatively rather than to a fixed count: `db` is shared across this file.
    const register = (await api.payroll.getDeductionConsents()).items;
    expect(register.length).toBeGreaterThan(response.items.length);
    expect(register.some((row) => row.employeeId === 20 && row.deductionKind === 'CUSTOMER_RETURN')).toBe(true);
  });
});
