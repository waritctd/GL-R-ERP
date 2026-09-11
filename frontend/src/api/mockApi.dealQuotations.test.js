import { describe, it, expect, afterEach, vi } from 'vitest';
import { api } from './mockApi.js';

// Mirrors th.co.glr.hr.dealquotation (Quotation v2, QUOTATION-V2-PLAN.md). See
// quotationMeta.test.js for the shared status/authz predicates this file imports rather than
// re-deriving; this file pins the parts that live only in the MOCK'S OWN persistence/number
// generation, which quotationMeta.js knows nothing about.

const salesUser = { role: 'sales' }; // id 6, owns ticket 18's rows (demoData.js)

// One complete tile row. UpsertDealQuotationRequest.items is `@NotEmpty`, so the real service 400s
// an empty list — and since quotation v3 the mock does too (buildDealQuotationItems). These fixtures
// used to create with `items: []`, which only ever worked because the mock was MORE permissive than
// the service; they now send the smallest body the service would actually accept.
const ONE_ITEM = {
  model: 'Trilogy', color: 'Ash', texture: 'Matt', sizeText: '60x60', thicknessMm: 10, sqmPerPiece: 0.36,
  quantityMode: 'AREA', areaSqm: 20, wastageMode: 'NONE', wastageValue: 0, piecesPerBox: 3, unitPrice: 850, discountPct: 0,
};

describe('mock dealQuotations -- items are @NotEmpty, as on the service', () => {
  it('refuses to create a quotation with no items (400), rather than storing an empty document', async () => {
    await api.auth.login(salesUser);
    await expect(api.dealQuotations.create(18, { items: [] })).rejects.toMatchObject({ status: 400 });
  });
});

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
      deptCode: 'P099', unitCode: 'D099', items: [ONE_ITEM],
    });
    expect(quotation.deptCode).toBe('P099');
    expect(quotation.unitCode).toBe('D099');
  });
});

describe('mock dealQuotations.update -- item id upsert (mirrors DealQuotationService#update, V170 pictures pairing)', () => {
  it('keeps a sent id that matches one of the quotation\'s CURRENT item ids; mints a fresh one for anything else', async () => {
    await api.auth.login(salesUser);
    const before = await api.dealQuotations.get(1);
    const existingId = before.quotation.items[0].id;

    const { quotation } = await api.dealQuotations.update(1, {
      items: [
        { ...ONE_ITEM, id: existingId }, // this quotation's own row -- kept
        { ...ONE_ITEM, id: 999999 }, // an id that belongs to nothing here -- treated as new
        { ...ONE_ITEM }, // no id at all -- also new
      ],
    });

    expect(quotation.items[0].id).toBe(existingId);
    expect(quotation.items[1].id).not.toBe(999999);
    expect(new Set(quotation.items.map((it) => it.id)).size).toBe(3);
  });

  it('a create never preserves a client-sent id -- there is nothing to match against yet', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await api.dealQuotations.create(18, { items: [{ ...ONE_ITEM, id: 4242 }] });
    expect(quotation.items[0].id).not.toBe(4242);
  });
});

describe('mock nextMockDealQuotationNumber -- #M10 number FORMAT', () => {
  // Owner feedback 2026-09-11 ("มีรันเลข -1 -2 ต่อท้ายตี้วแต่แรก" / "ใบแรกเป็น QT-2026-0014-1"): the
  // FIRST issued document now carries the revision suffix too.
  it('produces QT-{year}-{4-digit seq}-1, matching DealQuotationRepository.nextQuotationCode + revisionNumber(_, 1), not a bare QT-{year}-{seq} or QD{BE-year}', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await api.dealQuotations.create(18, { items: [ONE_ITEM] });
    expect(quotation.number).toMatch(/^QT-\d{4}-\d{4}-1$/);
    expect(quotation.revisionNo).toBe(1);
  });
});

describe('mock dealQuotations revision numbering -- #M10', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('a revision child is "{base}-{revisionNo}", and a grandchild strips the parent\'s own suffix rather than stacking it', async () => {
    await api.auth.login(salesUser);
    const created = await api.dealQuotations.create(18, { items: [ONE_ITEM] });
    const rootNumber = created.quotation.number;
    // rootNumber is now "{base}-1" (owner feedback 2026-09-11) -- recover the bare base the same
    // way DealQuotationRepository#baseNumber does, rather than assuming rootNumber IS the base.
    expect(rootNumber).toMatch(/-1$/);
    const baseNumber = rootNumber.slice(0, -'-1'.length);

    await api.dealQuotations.submit(created.quotation.id);
    await api.auth.login({ role: 'sales_manager' });
    const approved = await api.dealQuotations.approve(created.quotation.id, {});
    expect(approved.quotation.number).toBe(rootNumber);

    await api.auth.login(salesUser);
    const revision2 = await api.dealQuotations.createRevision(created.quotation.id, {});
    expect(revision2.quotation.number).toBe(`${baseNumber}-2`);
    expect(revision2.quotation.revisionNo).toBe(2);

    await api.dealQuotations.submit(revision2.quotation.id);
    await api.auth.login({ role: 'sales_manager' });
    await api.dealQuotations.approve(revision2.quotation.id, {});

    await api.auth.login(salesUser);
    const revision3 = await api.dealQuotations.createRevision(revision2.quotation.id, {});
    // The bug this guards: naively appending onto the SOURCE's own number (rather than
    // recovering the ORIGINAL base first) would produce "{root}-2-3" here.
    expect(revision3.quotation.number).toBe(`${baseNumber}-3`);
    expect(revision3.quotation.revisionNo).toBe(3);
  });

  // Legacy row: a quotation issued BEFORE this change (owner feedback 2026-09-11) carries a BARE
  // number at revisionNo 1 -- seed row id 1 ('QT-2026-0001', ticketId 18) stands in for one.
  // EXISTING ROWS MUST NOT BREAK (CLAUDE.md): its own number must never be rewritten, and revising
  // it must not collide with the "-1"-suffixed format new quotations now use.
  it('revising a pre-existing BARE-numbered quotation (issued before 2026-09-11) appends "-2", never rewrites the bare number, and never collides with the new "-1" format', async () => {
    await api.auth.login(salesUser);
    const before = await api.dealQuotations.get(1);
    expect(before.quotation.number).toBe('QT-2026-0001');
    expect(before.quotation.revisionNo).toBe(1);

    await api.dealQuotations.submit(1);
    await api.auth.login({ role: 'sales_manager' });
    const approved = await api.dealQuotations.approve(1, {});
    // The parent's own number is untouched -- still bare, exactly as issued.
    expect(approved.quotation.number).toBe('QT-2026-0001');

    await api.auth.login(salesUser);
    const revision = await api.dealQuotations.createRevision(1, {});
    expect(revision.quotation.number).toBe('QT-2026-0001-2');
    expect(revision.quotation.revisionNo).toBe(2);
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
    const { quotation } = await api.dealQuotations.create(18, { items: [ONE_ITEM] });
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
    await expect(api.dealQuotations.create(18, { items: [ONE_ITEM] })).rejects.toThrow('ไม่มีสิทธิ์เข้าถึงรายการนี้');
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

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Owner feedback pass 1, 2026-09-10 — F5 (สถานะ tabs) and F2 (ผู้สั่งซื้อ).
// ⚠️ AUTHZ CAVEAT: mock authz is NOT authoritative (CLAUDE.md "Mock API contract"). What these
// tests pin is that list() and counts() read the SAME scoped set as each other — a mock-internal
// consistency property — never that the scope itself matches DealQuotationService's.
// ─────────────────────────────────────────────────────────────────────────────────────────────

describe('mock dealQuotations counts / needsRework -- owner feedback F5', () => {
  // MED-3: the envelope. DealQuotationController#counts returns the DTO BARE, unlike every
  // wrapping neighbour; the mock wrapped it, hrApi's comment claimed the wrapper, and the page read
  // `r?.counts ?? r` so no one of the three could ever be caught disagreeing. Pinned here.
  it('counts() returns the BARE DealQuotationCountsDto -- no { counts: ... } envelope', async () => {
    await api.auth.login(salesUser);
    const res = await api.dealQuotations.counts();
    expect(res.counts).toBeUndefined();
    expect(Object.keys(res).sort())
      .toEqual(['all', 'approved', 'cancelled', 'needsRework', 'pendingApproval']);
    Object.values(res).forEach((n) => expect(typeof n).toBe('number'));
  });

  it('counts() reports the same five totals the corresponding list() filters return', async () => {
    await api.auth.login(salesUser);
    const counts = await api.dealQuotations.counts();

    const all = await api.dealQuotations.list();
    const pending = await api.dealQuotations.list({ status: 'PENDING_APPROVAL' });
    const cancelled = await api.dealQuotations.list({ status: 'CANCELLED' });
    const approved = await api.dealQuotations.list({ status: 'APPROVED' });
    const rework = await api.dealQuotations.list({ needsRework: true });

    // The failure this guards: a count computed over a LOOSER set than the list it labels, so a
    // tab promises rows the list then refuses to show.
    expect(counts.all).toBe(all.items.length);
    expect(counts.pendingApproval).toBe(pending.items.length);
    expect(counts.cancelled).toBe(cancelled.items.length);
    expect(counts.approved).toBe(approved.items.length);
    expect(counts.needsRework).toBe(rework.items.length);
  });

  it('needsRework=true selects a rejected DRAFT, and stops selecting it once resubmitted', async () => {
    await api.auth.login(salesUser);
    const created = await api.dealQuotations.create(18, { items: [ONE_ITEM] });
    const id = created.quotation.id;
    await api.dealQuotations.submit(id);

    // Not yet: PENDING_APPROVAL is not แก้.
    let rework = await api.dealQuotations.list({ needsRework: true });
    expect(rework.items.some((q) => q.id === id)).toBe(false);

    await api.auth.login({ role: 'sales_manager' });
    await api.dealQuotations.reject(id, { reason: 'ราคาสูงเกินไป' });

    await api.auth.login(salesUser);
    rework = await api.dealQuotations.list({ needsRework: true });
    expect(rework.items.some((q) => q.id === id)).toBe(true);

    // "cleared on the next submit" -- so the row leaves แก้ the moment it goes back for approval.
    await api.dealQuotations.submit(id);
    rework = await api.dealQuotations.list({ needsRework: true });
    expect(rework.items.some((q) => q.id === id)).toBe(false);
  });

  it('needsRework=true also selects a DRAFT revision in progress (the owner\'s second sense of แก้)', async () => {
    await api.auth.login(salesUser);
    const created = await api.dealQuotations.create(18, { items: [ONE_ITEM] });
    await api.dealQuotations.submit(created.quotation.id);
    await api.auth.login({ role: 'sales_manager' });
    await api.dealQuotations.approve(created.quotation.id, {});

    await api.auth.login(salesUser);
    const revision = await api.dealQuotations.createRevision(created.quotation.id, {});
    expect(revision.quotation.approvalNote).toBeNull(); // NOT the sent-back sense

    const { items } = await api.dealQuotations.list({ needsRework: true });
    expect(items.some((q) => q.id === revision.quotation.id)).toBe(true);
    // ...and the APPROVED parent is not dragged in with it.
    expect(items.some((q) => q.id === created.quotation.id)).toBe(false);
  });

  // MED-5: the mock's predicate used Boolean(approvalNote); the SQL is `approval_note IS NOT NULL`,
  // which is TRUE for the empty string. A DRAFT carrying approvalNote '' therefore belonged in แก้
  // on the real backend and was missing from it here -- a divergence in the direction where the
  // mock shows FEWER rows than production, so nobody would ever have noticed from the UI.
  it('counts() refuses a caller list() refuses, rather than leaking totals', async () => {
    await api.auth.login({ email: 'warehouse.manager@glr.co.th', password: 'demo1234' });
    await expect(api.dealQuotations.counts()).rejects.toThrow('ไม่มีสิทธิ์เข้าถึงรายการนี้');
  });
});

describe('mock dealQuotations ผู้สั่งซื้อ snapshot -- owner feedback F2', () => {
  it('defaults contactId to the deal\'s own contact and FREEZES name/phone/email onto the row', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await api.dealQuotations.create(18, { items: [ONE_ITEM] });

    expect(quotation.contactId).toBe(6); // ticket 18's own contact
    expect(quotation.contactName).toBe('ณัฐพงศ์ ศรีวิไล');
    expect(quotation.contactPhone).toBe('086-222-3333');
    expect(quotation.contactEmail).toBe('nattapong@fashionisland.co.th');
  });

  it('an explicit contactId on the request wins over the deal\'s default', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await api.dealQuotations.create(18, { contactId: 7, items: [ONE_ITEM] });
    expect(quotation.contactId).toBe(7);
    expect(quotation.contactName).toBe('พิมพ์ใจ บุญมาก');
  });

  // Wrong-way-round: a contact belonging to a DIFFERENT customer must be refused, not silently
  // snapshotted onto this customer's document.
  it('refuses a contact that does not belong to the deal\'s customer', async () => {
    await api.auth.login(salesUser);
    await expect(api.dealQuotations.create(18, { contactId: 1, items: [ONE_ITEM] }))
      .rejects.toThrow('ผู้สั่งซื้อไม่ได้อยู่ในสังกัดลูกค้ารายนี้');
  });

  it('refuses an unknown contactId with the same Thai message the UI shows', async () => {
    await api.auth.login(salesUser);
    await expect(api.dealQuotations.create(18, { contactId: 99999, items: [ONE_ITEM] }))
      .rejects.toThrow('กรุณาระบุผู้สั่งซื้อ');
  });

  it('update() re-snapshots when the rep changes ผู้สั่งซื้อ', async () => {
    await api.auth.login(salesUser);
    const created = await api.dealQuotations.create(18, { items: [ONE_ITEM] });
    const { quotation } = await api.dealQuotations.update(created.quotation.id, { contactId: 7, items: [ONE_ITEM] });
    expect(quotation.contactId).toBe(7);
    expect(quotation.contactName).toBe('พิมพ์ใจ บุญมาก');
  });
});

describe('mock catalog.prices originCountryCode -- owner feedback F1', () => {
  it('derives it from the factory, so the item editor can autofill ประเทศต้นทาง', async () => {
    await api.auth.login(salesUser);
    const { items } = await api.catalog.prices('Trilogy');
    expect(items.length).toBeGreaterThan(0);
    // Panaria SpA is factory 1, country IT (mockPriceImportFactories) -- one source of truth.
    expect(items[0].originCountryCode).toBe('IT');
  });

  it('reports TH for a domestic factory, not the importers\' default', async () => {
    await api.auth.login(salesUser);
    const { items } = await api.catalog.prices('Elegance');
    expect(items.every((row) => row.originCountryCode === 'TH')).toBe(true);
  });
});
