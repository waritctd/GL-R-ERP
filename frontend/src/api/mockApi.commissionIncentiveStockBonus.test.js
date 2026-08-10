import { beforeAll, describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// Issue #405 — guards mockApi.js's payrollReady incentive/stock-bonus aggregation directly
// against the mock module (not just through CommissionPage, which mocks api.commissions
// wholesale and so never actually exercises this logic). Per CLAUDE.md's "Mock API contract",
// mockApi is this repo's default verification surface, so its own math must be proven, not just
// the real CommissionCalculator's.
//
// Review fix regression coverage: an earlier version of payrollReady's double-count guard
// treated "an approved manual INCENTIVE/STOCK_BONUS entry exists" as suppression, which zeroed
// the whole auto-computed limb even for a negative (correction) manual entry — e.g. a -5,000
// correction on a real 15,000 auto incentive paid -5,000 total instead of the correct 10,000.
// The tests below prove the fixed rule: POSITIVE manual amount replaces (suppresses) the auto
// limb; ZERO/NEGATIVE is a correction layered on top of it.
//
// Test-ordering note (HISTORICAL): every api.commissions.create() call below runs in a
// beforeAll, BEFORE any manual commission is created anywhere in this file. That used to be
// load-bearing — create()'s duplicate-invoice-number check did `db.commissions.some(item =>
// item.invoiceDetails.invoiceNumber === ...)` with no null guard, and a manual-kind record's
// invoiceDetails is always null (V84), so calling create() after ANY manual commission existed
// in the shared mock db threw "Cannot read properties of null".
//
// That defect is FIXED (both call sites null-guard now, matching the real backend: the Java
// check is SQL, where a NULL invoice number is never equal to anything). The ordering is left
// as it is because it costs nothing, but it is no longer a workaround — and the four
// MANUAL_COMMISSION_KINDS are seeded in demoPayroll.js as a result. `createSucceedsAlongside
// ManualRows` below is the regression guard.

// 3,210,000.00 / 1.07 = 3,000,000.00 exactly -- lands precisely on the first INCENTIVE threshold.
const ACTUAL_RECEIVED_AT_FIRST_THRESHOLD = 3210000;

// Arbitrary, mutually-distinct rep ids well outside the seeded demo user id range, so each
// scenario's rep/month is fully isolated from the others and from seed data.
const REP_AUTO_AUGUST = 900101;
const REP_AUTO_JULY = 900102;
const REP_POSITIVE_MANUAL = 900103;
const REP_NEGATIVE_MANUAL = 900104;
const REP_ZERO_MANUAL = 900105;

/**
 * Creates one real, fully APPROVED (manager + CEO) unlinked SALE commission through the actual
 * mock create()/approve() chain (not a direct db.commissions push) -- mirrors what a real
 * account/sales_manager/ceo submission + review would produce.
 */
async function seedApprovedSaleCommission(salesRepId, actualReceived, invoiceDateIso) {
  await api.auth.login({ role: 'sales_manager' });
  const { commission } = await api.commissions.create({
    salesRepId,
    invoiceNumber: `INV-405-MOCK-${salesRepId}-${Math.random().toString(36).slice(2)}`,
    invoiceDate: invoiceDateIso,
    grossAmount: actualReceived,
    invoiceAttachment: { name: 'test-invoice.pdf', type: 'application/pdf', size: 100 },
  });
  await api.commissions.approve(commission.id); // still sales_manager: SUBMITTED -> MANAGER_APPROVED
  await api.auth.login({ role: 'ceo' });
  await api.commissions.approve(commission.id); // MANAGER_APPROVED -> APPROVED
  return commission;
}

function repRowFor(summary, salesRepId) {
  return summary.salesReps.find((rep) => rep.salesRepId === salesRepId);
}

beforeAll(async () => {
  await seedApprovedSaleCommission(REP_AUTO_AUGUST, ACTUAL_RECEIVED_AT_FIRST_THRESHOLD, '2026-08-01');
  await seedApprovedSaleCommission(REP_AUTO_JULY, ACTUAL_RECEIVED_AT_FIRST_THRESHOLD, '2026-07-01');
  await seedApprovedSaleCommission(REP_POSITIVE_MANUAL, ACTUAL_RECEIVED_AT_FIRST_THRESHOLD, '2026-08-01');
  await seedApprovedSaleCommission(REP_NEGATIVE_MANUAL, ACTUAL_RECEIVED_AT_FIRST_THRESHOLD, '2026-08-01');
  await seedApprovedSaleCommission(REP_ZERO_MANUAL, ACTUAL_RECEIVED_AT_FIRST_THRESHOLD, '2026-08-01');
});

describe('mockApi payrollReady — auto-computed INCENTIVE (issue #405)', () => {
  it('computes the auto incentive for an August 2026 month, and stays zero for July (fix-forward)', async () => {
    await api.auth.login({ role: 'hr' });
    const { summary } = await api.commissions.payrollReady({ payrollMonth: '2026-08-01' });
    const row = repRowFor(summary, REP_AUTO_AUGUST);

    expect(row).toBeTruthy();
    expect(row.commissionableBase).toBeCloseTo(3000000, 2);
    expect(row.incentiveAmount).toBe(15000);
    expect(row.stockBonusAmount).toBe(0); // config-gated OFF by default
  });

  it('pays zero incentive for the same receipt shape in a pre-effective-date month', async () => {
    await api.auth.login({ role: 'hr' });
    const { summary } = await api.commissions.payrollReady({ payrollMonth: '2026-07-01' });
    const row = repRowFor(summary, REP_AUTO_JULY);

    expect(row).toBeTruthy();
    expect(row.incentiveAmount).toBe(0);
  });
});

describe('mockApi payrollReady — manual INCENTIVE double-count guard (issue #405 review fix)', () => {
  it('a POSITIVE manual INCENTIVE replaces (suppresses) the auto-computed limb', async () => {
    await api.auth.login({ role: 'ceo' });
    await api.commissions.createManualCommission({
      salesRepId: REP_POSITIVE_MANUAL,
      kind: 'INCENTIVE',
      amount: 15000,
      reason: 'hand-entered before auto-compute shipped',
      payrollMonth: '2026-08-01',
    });

    await api.auth.login({ role: 'hr' });
    const { summary } = await api.commissions.payrollReady({ payrollMonth: '2026-08-01' });
    const row = repRowFor(summary, REP_POSITIVE_MANUAL);

    expect(row.incentiveAmount).toBe(0);
    expect(row.manualAdjustmentAmount).toBe(15000);
  });

  it('a NEGATIVE manual INCENTIVE is a correction: the auto limb still computes and the correction adds', async () => {
    await api.auth.login({ role: 'ceo' });
    await api.commissions.createManualCommission({
      salesRepId: REP_NEGATIVE_MANUAL,
      kind: 'INCENTIVE',
      amount: -5000,
      reason: 'correction: prior INCENTIVE overstated',
      payrollMonth: '2026-08-01',
    });

    await api.auth.login({ role: 'hr' });
    const { summary } = await api.commissions.payrollReady({ payrollMonth: '2026-08-01' });
    const row = repRowFor(summary, REP_NEGATIVE_MANUAL);

    // The auto limb is NOT suppressed -- it still pays the full 15,000, with the -5,000
    // correction added on top via manualAdjustmentAmount (not replacing it).
    expect(row.incentiveAmount).toBe(15000);
    expect(row.manualAdjustmentAmount).toBe(-5000);
  });

  it('a ZERO manual INCENTIVE note is not a replacement: the auto limb still computes in full', async () => {
    await api.auth.login({ role: 'ceo' });
    await api.commissions.createManualCommission({
      salesRepId: REP_ZERO_MANUAL,
      kind: 'INCENTIVE',
      amount: 0,
      reason: 'note only, not a replacement',
      payrollMonth: '2026-08-01',
    });

    await api.auth.login({ role: 'hr' });
    const { summary } = await api.commissions.payrollReady({ payrollMonth: '2026-08-01' });
    const row = repRowFor(summary, REP_ZERO_MANUAL);

    expect(row.incentiveAmount).toBe(15000);
  });
});

/**
 * The null-guard regression, kept separate from the incentive maths above.
 *
 * A manual commission carries `invoiceDetails: null` (V84). Both commissions.create() and
 * commissions.createFromDeal() compared `item.invoiceDetails.invoiceNumber` unguarded, so ONE
 * manual row anywhere in the shared mock db made every later create() throw — in the live mock
 * app, not only here. It is why the four MANUAL_COMMISSION_KINDS were unseedable, and why the
 * calls above are front-loaded.
 *
 * Asserts the create SUCCEEDS with an invoice-less row present, and — the half that a bare
 * optional-chain would have got wrong — that two invoice-less rows are NOT treated as duplicates
 * of each other. `undefined === undefined` is true, so `?.` alone would have turned the crash
 * into a spurious 409.
 */
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
