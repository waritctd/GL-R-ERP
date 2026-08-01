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
// Test-ordering note: every api.commissions.create() call below runs in a beforeAll, BEFORE any
// manual commission is created anywhere in this file. mockApi.js's create() has a pre-existing,
// unrelated bug (not introduced by issue #405, not one of this branch's four review fixes): its
// duplicate-invoice-number check does `db.commissions.some(item => item.invoiceDetails
// .invoiceNumber === ...)` with no null guard, and a manual-kind record's invoiceDetails is
// always null (V84) -- so calling create() again after ANY manual commission exists anywhere in
// the shared mock db throws "Cannot read properties of null". Front-loading every create() call
// here sidesteps it; this is a real latent defect worth its own fix, flagged in the PR body, not
// something this branch touches.

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
