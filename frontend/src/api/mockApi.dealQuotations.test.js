import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest';

// Every create targets demo ticket 18. A deal holds at most ONE live direct quotation, so each test
// gets a FRESH mock module and createOn18 first retires whatever is live there; recipientType is
// required on a DEAL_DIRECT create, so it defaults to BUYER.
let api;
let retireLiveDirectQuotationsForTests;
let retireLivePricingRequestsForTests;
beforeEach(async () => {
  vi.resetModules();
  ({ api, retireLiveDirectQuotationsForTests, retireLivePricingRequestsForTests } = await import('./mockApi.js'));
});

async function createOn18(payload) {
  retireLiveDirectQuotationsForTests(18);
  retireLivePricingRequestsForTests(18);
  return api.dealQuotations.create(18, { recipientType: 'BUYER', ...payload });
}

const salesUser = { role: 'sales' }; // id 6, owns ticket 18's rows (demoData.js)

// One complete tile row; lead times are set because submit refuses a TILE row with neither.
const ONE_ITEM = {
  model: 'Trilogy', color: 'Ash', texture: 'Matt', sizeText: '60x60', thicknessMm: 10, sqmPerPiece: 0.36,
  quantityMode: 'AREA', areaSqm: 20, wastageMode: 'NONE', wastageValue: 0, piecesPerBox: 3, unitPrice: 850, discountPct: 0,
  leadTimeMinDays: 30, leadTimeMaxDays: 45,
};

// Any fraction of a piece rounds UP (WastageCalculator#ceilToInt), but the server does it on
// BigDecimal and the mock on floats, where 100 * (7 / 100) is 7.000000000000001 and a bare
// Math.ceil turns an exact 7 into 8.
describe('mock dealQuotations -- rounding up is not fooled by float noise', () => {
  const TILE = {
    model: 'Trilogy', color: 'Ash', texture: 'Matt', sizeText: '40x40', thicknessMm: 10,
    wastageMode: 'NONE', wastageValue: 0, piecesPerBox: 1, unitPrice: 100, discountPct: 0,
    leadTimeMinDays: 30, leadTimeMaxDays: 45,
  };
  const line = async (fields) => {
    await api.auth.login(salesUser);
    return (await api.dealQuotations.calculateLine({ ...TILE, ...fields }, 'TH')).item;
  };

  it('100 pieces + 7% is exactly 107, not 108', async () => {
    const item = await line({ sqmPerPiece: 0.16, quantityMode: 'PIECES', piecesInput: 100,
      wastageMode: 'PERCENT', wastageValue: 7 });
    expect(item.piecesAfterWastage).toBe(107);
  });

  it('1.12 sqm at 0.16 sqm/piece (6.25 pcs/sqm) is exactly 7 pieces, not 8', async () => {
    const item = await line({ sqmPerPiece: 0.16, quantityMode: 'AREA', areaSqm: 1.12 });
    expect(item.piecesBeforeWastage).toBe(7);
  });
});

// GLA-74: a deal holds one live quotation; approving a clone/independent first-issue supersedes the other.
describe('mock dealQuotations.createReorder -- GLA-74 part 1', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  /** Two wholly independent first-issue quotations on the SAME deal (no revision/clone lineage at
   * all), a case an ancestor walk could never reach since neither carries the other's id.
   * Approving the second must supersede the first.
   *
   * `create` can no longer PRODUCE this pair (a second first-issue create on a deal with a live
   * direct quotation is a 409; mockApi.quotationDealLink.test.js). It still exists as LEGACY data:
   * the demo seed carries ids 1 (DRAFT) and 2 (PENDING_APPROVAL) on ticket 18. The sweep must hold
   * for it, so this drives the seed rows instead of two creates. */
  it('two independent first-issue quotations on one deal: approving the second supersedes the first', async () => {
    await api.auth.login({ role: 'sales_manager' });
    const approvedFirst = await api.dealQuotations.approve(2, {});
    expect(approvedFirst.quotation.docStatus).toBe('APPROVED');
    expect(approvedFirst.quotation.parentQuotationId).toBeNull();

    await api.auth.login(salesUser);
    await api.dealQuotations.submit(1);
    await api.auth.login({ role: 'sales_manager' });
    const approvedSecond = await api.dealQuotations.approve(1, {});
    expect(approvedSecond.quotation.docStatus).toBe('APPROVED');
    expect(approvedSecond.quotation.parentQuotationId).toBeNull();

    const reloadedFirst = await api.dealQuotations.get(2);
    expect(reloadedFirst.quotation.docStatus).toBe('SUPERSEDED');
  });

  it('refuses (409) to clone a non-APPROVED source', async () => {
    await api.auth.login(salesUser);
    const created = await createOn18({ items: [ONE_ITEM] });
    await expect(api.dealQuotations.createReorder(created.quotation.id, {}))
      .rejects.toMatchObject({ status: 409 });
  });

});

describe('mock dealQuotations authz -- #H4 canCreateQuotation grant', () => {
  // employee@glr.co.th (id 4, demoData.js) carries canCreateQuotation: true on a plain `employee` role -- not
  // sales/sales_manager, and not in DEAL_QUOTATION_VIEWER_ROLES at all.
  const grantedEmployee = { role: 'employee' };

  it('a canCreateQuotation-granted employee may create on a deal they do not own', async () => {
    await api.auth.login(grantedEmployee);
    const { quotation } = await createOn18({ items: [ONE_ITEM] });
    expect(quotation.ticketId).toBe(18);
  });

  // Wrong-way-round: the employee WITHOUT the grant must be refused create access too, not just list access --
  // the same deal (18) the granted employee reaches above.
  it('a plain employee with NO grant cannot create on a deal they do not own either', async () => {
    await api.auth.login({ email: 'warehouse.manager@glr.co.th', password: 'demo1234' });
    await expect(createOn18({ items: [ONE_ITEM] })).rejects.toThrow('ไม่มีสิทธิ์เข้าถึงรายการนี้');
  });

  it('the grant does NOT let that employee approve -- approve stays role-only', async () => {
    await api.auth.login(grantedEmployee);
    await expect(api.dealQuotations.approve(2, {})).rejects.toThrow();
  });
});

describe('mock dealQuotations.approve -- #M3 Bangkok local quotationDate', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('records the Bangkok calendar day, not the UTC one, when they disagree', async () => {
    // 2026-01-01T20:00:00Z is 2026-01-02 03:00 Bangkok (UTC+7): UTC and Bangkok disagree on the
    // calendar day, which is the case `now.slice(0, 10)` gets wrong. `shouldAdvanceTime` keeps the
    // mock's internal `delay()` (a real setTimeout) firing on wall-clock time while `Date` stays
    // pinned.
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.setSystemTime(new Date('2026-01-01T20:00:00Z'));

    await api.auth.login({ role: 'sales_manager' });
    const { quotation } = await api.dealQuotations.approve(2, {});

    expect(quotation.quotationDate).toBe('2026-01-02');
  });
});

describe('mock dealQuotations.cancel -- open-child guard', () => {
  /** Wrong-way-round: mirrors
   * DealQuotationIntegrationTest#cancel_refusesADraftWithAnOpenChild_closingTheDoubleApproveHole.
   * The ancestry a rejected-and-resubmitted row can grow is a TREE, not a straight line: cancelling
   * a DRAFT row that itself has an open child would make that child's GRANDPARENT look "free",
   * letting it mint a SECOND, sibling branch while the first was alive (two independently-APPROVED
   * quotations on the same ticket). */
  it('cancel refuses a draft with an open child, closing the double-approve hole', async () => {
    await api.auth.login(salesUser);
    const created = await createOn18({ items: [ONE_ITEM] });
    const originalId = created.quotation.id;

    await api.dealQuotations.submit(originalId);
    await api.auth.login({ role: 'sales_manager' });
    await api.dealQuotations.reject(originalId, { reason: 'รอบ 1' });

    await api.auth.login(salesUser);
    const { quotation: revisionB } = await api.dealQuotations.submit(originalId);
    await api.auth.login({ role: 'sales_manager' });
    await api.dealQuotations.reject(revisionB.id, { reason: 'รอบ 2' });

    await api.auth.login(salesUser);
    await api.dealQuotations.submit(revisionB.id); // mints C -- B now has an open child.

    // B cannot be cancelled out from under its own open child.
    await expect(api.dealQuotations.cancel(revisionB.id, {})).rejects.toMatchObject({ status: 409 });
  });

  it('cancel still works on a plain draft with no open child (regression guard)', async () => {
    await api.auth.login(salesUser);
    const created = await createOn18({ items: [ONE_ITEM] });
    const { quotation: cancelled } = await api.dealQuotations.cancel(created.quotation.id, {});
    expect(cancelled.docStatus).toBe('CANCELLED');
  });
});
