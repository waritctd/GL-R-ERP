import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// Guards `payroll.getDeductionShortfalls()` against PayrollDeductionShortfallController +
// PayrollDeductionShortfallService (issue #376): the role gate, the employeeId/kind filters, and
// the repository's ORDER BY.
//
// Employee codes below follow the demo dataset's own scheme (demoData.js: `code = GLR-${1001 +
// index}`) and were verified directly against `createDemoDatabase()` rather than assumed, since
// the ORDER BY assertion depends on their exact lexicographic order:
//   employees[2]  -> id 3,  GLR-1003
//   employees[19] -> id 20, GLR-1020
//   employees[24] -> id 25, GLR-1025

describe('mock getDeductionShortfalls', () => {
  it('hr and ceo can both read the ledger', async () => {
    for (const role of ['hr', 'ceo']) {
      await api.auth.login({ role });
      const { items } = await api.payroll.getDeductionShortfalls();
      expect(items.length).toBeGreaterThan(0);
    }
  });

  // Mirrors PayrollDeductionShortfallService.VIEW_ROLES = Set.of("hr", "ceo") exactly — every
  // other seeded role must be refused, not just "some other role".
  it('rejects every role outside hr/ceo with the exact backend message', async () => {
    for (const role of ['employee', 'sales', 'sales_manager', 'import', 'account']) {
      await api.auth.login({ role });
      await expect(api.payroll.getDeductionShortfalls()).rejects.toThrow('ไม่มีสิทธิ์เข้าถึงรายการนี้');
    }
  });

  it('orders by payrollMonth DESC then employeeCode ASC, mirroring the repository exactly', async () => {
    await api.auth.login({ role: 'hr' });
    const { items } = await api.payroll.getDeductionShortfalls();

    // Pins the exact sequence, not just "sorted" against itself — a fixture edit that keeps the
    // same SET of rows but changes their order would otherwise slip through unnoticed.
    expect(items.map((row) => `${row.payrollMonth} ${row.employeeCode}`)).toEqual([
      '2026-07-01 GLR-1003',
      '2026-07-01 GLR-1020',
      '2026-06-01 GLR-1003',
      '2026-06-01 GLR-1025',
      '2026-05-01 GLR-1020',
    ]);
  });

  it('never contains a kind other than LEGAL_EXECUTION_GARNISHMENT — recordGarnishmentShortfalls writes no other', async () => {
    await api.auth.login({ role: 'hr' });
    const { items } = await api.payroll.getDeductionShortfalls();
    expect(items.length).toBeGreaterThan(0);
    expect(items.every((row) => row.deductionKind === 'LEGAL_EXECUTION_GARNISHMENT')).toBe(true);
  });

  it('every row carries a positive shortfall equal to requested minus actual', async () => {
    await api.auth.login({ role: 'hr' });
    const { items } = await api.payroll.getDeductionShortfalls();
    for (const row of items) {
      expect(row.shortfallAmount).toBeGreaterThan(0);
      expect(row.shortfallAmount).toBeCloseTo(row.requestedAmount - row.actualAmount, 2);
    }
  });

  it('the employeeId filter narrows to just that employee, using the repeat-employee case in the fixture', async () => {
    await api.auth.login({ role: 'hr' });
    const { items: all } = await api.payroll.getDeductionShortfalls();
    const { items: filtered } = await api.payroll.getDeductionShortfalls({ employeeId: 3 });

    expect(filtered.length).toBe(2);
    expect(filtered.every((row) => row.employeeId === 3)).toBe(true);
    expect(filtered.length).toBeLessThan(all.length);
  });

  it('the kind filter round-trips — every seeded row already matches LEGAL_EXECUTION_GARNISHMENT', async () => {
    await api.auth.login({ role: 'hr' });
    const { items: all } = await api.payroll.getDeductionShortfalls();
    const { items: filtered } = await api.payroll.getDeductionShortfalls({ kind: 'LEGAL_EXECUTION_GARNISHMENT' });
    expect(filtered.length).toBe(all.length);
  });
});
