import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

/** fix/commission-figures-from-backend: this file used to be mockApi.commissionIncentiveStockBonus.test.js and
 * also asserted mockApi.js's own payrollReady() auto-computed INCENTIVE/STOCK_BONUS aggregation (issue #405) —
 * a reimplementation of CommissionService#computeRepPayrollCommissions in the mock. */

// The null-guard regression, kept separate from the (now-removed) incentive maths. A manual commission carries
// `invoiceDetails: null` (V84). It is why the four MANUAL_COMMISSION_KINDS were unseedable, and why the calls
// below are front-loaded relative to any manual-commission creation.
describe('duplicate-invoice check tolerates invoice-less manual commissions', () => {
  const REP = 990501;

  it('lets an invoice-backed commission be created while a manual row exists', async () => {
    await api.auth.login({ role: 'sales_manager' });
    await api.commissions.createManualCommission({
      salesRepId: REP, kind: 'ADJUSTMENT', amount: -1000,
      reason: 'ปรับปรุงยอดงวดก่อน', payrollMonth: '2026-08',
    });

    await expect(api.commissions.create({
      salesRepId: REP, invoiceNumber: 'INV-NULLGUARD-001', invoiceDate: '2026-08-09',
      grossAmount: 60000, payrollMonth: '2026-08', invoiceAttachment: { name: 'inv.pdf' },
    })).resolves.toBeTruthy();
  });

  // Checks missing invoice numbers do not collide with invoice-less manual commission rows.
  it('does not treat a missing invoice number as a duplicate of an invoice-less row', async () => {
    await api.auth.login({ role: 'sales_manager' });
    await api.commissions.createManualCommission({
      salesRepId: REP, kind: 'STOCK_BONUS', amount: 2500,
      reason: 'โบนัสระบายสต็อก', payrollMonth: '2026-08',
    });

    await expect(api.commissions.create({
      salesRepId: REP, invoiceDate: '2026-08-09', grossAmount: 45000,
      payrollMonth: '2026-08', invoiceAttachment: { name: 'inv.pdf' },
    })).resolves.toBeTruthy();
  });

  // A real duplicate must still be refused — the guard skips NULLs, it does not skip the check.
  it('still refuses a genuinely duplicated invoice number', async () => {
    await api.auth.login({ role: 'sales_manager' });
    await api.commissions.create({
      salesRepId: REP, invoiceNumber: 'INV-NULLGUARD-002', invoiceDate: '2026-08-09',
      grossAmount: 70000, payrollMonth: '2026-08', invoiceAttachment: { name: 'inv.pdf' },
    });

    await expect(api.commissions.create({
      salesRepId: REP, invoiceNumber: 'INV-NULLGUARD-002', invoiceDate: '2026-08-09',
      grossAmount: 70000, payrollMonth: '2026-08', invoiceAttachment: { name: 'inv.pdf' },
    })).rejects.toThrow();
  });
});
