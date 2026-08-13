import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

/**
 * fix/commission-figures-from-backend: this file used to be
 * mockApi.commissionIncentiveStockBonus.test.js and also asserted mockApi.js's own
 * payrollReady() auto-computed INCENTIVE/STOCK_BONUS aggregation (issue #405) — a
 * reimplementation of CommissionService#computeRepPayrollCommissions in the mock. That
 * computation has been REMOVED from the mock as part of this branch: per CLAUDE.md's "Mock API
 * contract" ("mock-driven tests are not independent evidence about a computation the mock
 * mirrors" / "prefer NOT reimplementing payroll/tax/commission math in the mock at all"),
 * payrollReady() now returns a canned tier/incentive/stock-bonus fixture
 * (MOCK_PAYROLL_READY_TIER_FIXTURE in mockApi.js) instead of recomputing the ladder — the same
 * class of drift risk this whole branch exists to close (the V81 tier-13 rate correction is the
 * case on record). The real incentive/stock-bonus suppression rule is proven against real
 * Postgres by CommissionIncentiveStockBonusIntegrationTest (backend).
 *
 * What SURVIVES here is unrelated to that computation: a null-guard regression in
 * commissions.create()'s duplicate-invoice-number check, which is still real mock logic worth
 * covering directly (not itemised in CommissionPage's own tests, which mock api.commissions
 * wholesale and never exercise this code path).
 */

// The null-guard regression, kept separate from the (now-removed) incentive maths.
//
// A manual commission carries `invoiceDetails: null` (V84). Both commissions.create() and
// commissions.createFromDeal() compared `item.invoiceDetails.invoiceNumber` unguarded, so ONE
// manual row anywhere in the shared mock db made every later create() throw — in the live mock
// app, not only here. It is why the four MANUAL_COMMISSION_KINDS were unseedable, and why the
// calls below are front-loaded relative to any manual-commission creation.
//
// Asserts the create SUCCEEDS with an invoice-less row present, and — the half that a bare
// optional-chain would have got wrong — that two invoice-less rows are NOT treated as duplicates
// of each other. `undefined === undefined` is true, so `?.` alone would have turned the crash
// into a spurious 409.
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

  // The half-fix case. A bare `?.` stops the crash and introduces a quieter bug: create() does
  // not require an invoice number (only the attachment), so a call that omits one compares
  // `undefined === undefined` against the manual row and is refused as a duplicate of an invoice
  // that does not exist. This is the assertion that separates `?.` from `?. &&`.
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
