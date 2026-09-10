import { describe, it, expect, afterEach, vi } from 'vitest';
import { api } from './mockApi.js';

// Mirrors th.co.glr.hr.dealquotation (Quotation v2, QUOTATION-V2-PLAN.md). See
// quotationMeta.test.js for the shared status/authz predicates this file imports rather than
// re-deriving; this file pins the parts that live only in the MOCK'S OWN persistence/number
// generation, which quotationMeta.js knows nothing about.

const salesUser = { role: 'sales' }; // id 6, owns ticket 18's rows (demoData.js)

describe('mock dealQuotations.update -- #M7 direct assignment, null clears', () => {
  it('an explicit null in the request CLEARS the field, not "keep the old value"', async () => {
    await api.auth.login(salesUser);
    // Seed row 1: DRAFT, deptCode 'P003', unitCode 'D002', remainderMode 'CREDIT'.
    const before = await api.dealQuotations.get(1);
    expect(before.quotation.deptCode).toBe('P003');

    const { quotation } = await api.dealQuotations.update(1, {
      deptCode: null, unitCode: null, offerDate: null, depositPercent: null,
      remainderMode: null, creditDays: null, validityDays: null, customerNotes: null,
      items: before.quotation.items,
    });

    expect(quotation.deptCode).toBeNull();
    expect(quotation.unitCode).toBeNull();
    expect(quotation.remainderMode).toBeNull();
    expect(quotation.depositPercent).toBeNull();
    expect(quotation.validityDays).toBeNull();
  });

  it('a field present in the request still overwrites, same as before', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await api.dealQuotations.update(1, {
      deptCode: 'P099', unitCode: 'D099', items: [],
    });
    expect(quotation.deptCode).toBe('P099');
    expect(quotation.unitCode).toBe('D099');
  });
});

describe('mock nextMockDealQuotationNumber -- #M10 number FORMAT', () => {
  it('produces QT-{year}-{4-digit seq}, matching DealQuotationRepository.nextQuotationCode, not QD{BE-year}', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await api.dealQuotations.create(18, { items: [] });
    expect(quotation.number).toMatch(/^QT-\d{4}-\d{4}$/);
  });
});

describe('mock dealQuotations revision numbering -- #M10', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('a revision child is "{base}-{revisionNo}", and a grandchild strips the parent\'s own suffix rather than stacking it', async () => {
    await api.auth.login(salesUser);
    const created = await api.dealQuotations.create(18, { items: [] });
    const rootNumber = created.quotation.number;

    await api.dealQuotations.submit(created.quotation.id);
    await api.auth.login({ role: 'sales_manager' });
    const approved = await api.dealQuotations.approve(created.quotation.id, {});
    expect(approved.quotation.number).toBe(rootNumber);

    await api.auth.login(salesUser);
    const revision2 = await api.dealQuotations.createRevision(created.quotation.id, {});
    expect(revision2.quotation.number).toBe(`${rootNumber}-2`);
    expect(revision2.quotation.revisionNo).toBe(2);

    await api.dealQuotations.submit(revision2.quotation.id);
    await api.auth.login({ role: 'sales_manager' });
    await api.dealQuotations.approve(revision2.quotation.id, {});

    await api.auth.login(salesUser);
    const revision3 = await api.dealQuotations.createRevision(revision2.quotation.id, {});
    // The bug this guards: naively appending onto the SOURCE's own number (rather than
    // recovering the ORIGINAL base first) would produce "{root}-2-3" here.
    expect(revision3.quotation.number).toBe(`${rootNumber}-3`);
    expect(revision3.quotation.revisionNo).toBe(3);
  });
});

describe('mock dealQuotations authz -- #H4 canCreateQuotation grant', () => {
  // employee@glr.co.th (id 4, demoData.js) carries canCreateQuotation: true on a plain
  // `employee` role -- not sales/sales_manager, and not in DEAL_QUOTATION_VIEWER_ROLES at all.
  const grantedEmployee = { role: 'employee' };

  it('list() 403s a plain employee with NO grant', async () => {
    // id 10 (warehouse.manager@glr.co.th) is also role `employee` but carries no
    // canCreateQuotation field at all -- logged in by email, not role, since `login({role})`
    // always resolves the FIRST matching row (id 4, the granted one).
    await api.auth.login({ email: 'warehouse.manager@glr.co.th', password: 'demo1234' });
    await expect(api.dealQuotations.list()).rejects.toThrow('ไม่มีสิทธิ์เข้าถึงรายการนี้');
  });

  it('a canCreateQuotation-granted employee sees EVERY deal\'s quotations, not just their own (there is no "own")', async () => {
    await api.auth.login(grantedEmployee);
    const { items } = await api.dealQuotations.list();
    expect(items.length).toBeGreaterThanOrEqual(2); // the two seeded rows on ticket 18
  });

  it('a canCreateQuotation-granted employee may create on a deal they do not own', async () => {
    await api.auth.login(grantedEmployee);
    const { quotation } = await api.dealQuotations.create(18, { items: [] });
    expect(quotation.ticketId).toBe(18);
  });

  it('a canCreateQuotation-granted employee may view a quotation on a deal they do not own', async () => {
    await api.auth.login(grantedEmployee);
    await expect(api.dealQuotations.get(1)).resolves.toBeDefined();
  });

  // Wrong-way-round: the employee WITHOUT the grant must be refused create access too, not just
  // list access -- the same deal (18) the granted employee reaches above.
  it('a plain employee with NO grant cannot create on a deal they do not own either', async () => {
    await api.auth.login({ email: 'warehouse.manager@glr.co.th', password: 'demo1234' });
    await expect(api.dealQuotations.create(18, { items: [] })).rejects.toThrow('ไม่มีสิทธิ์เข้าถึงรายการนี้');
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
    // 2026-01-01T20:00:00Z is 2026-01-02 03:00 Bangkok (UTC+7) -- UTC and Bangkok disagree on
    // the calendar day, which is exactly the case `now.slice(0, 10)` gets wrong.
    // `shouldAdvanceTime` keeps the mock's own internal `delay()` (a real setTimeout) firing on
    // real wall-clock time while `Date` itself stays pinned.
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.setSystemTime(new Date('2026-01-01T20:00:00Z'));

    await api.auth.login({ role: 'sales_manager' });
    const { quotation } = await api.dealQuotations.approve(2, {});

    expect(quotation.quotationDate).toBe('2026-01-02');
  });
});
