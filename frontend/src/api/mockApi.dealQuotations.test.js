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
// leadTimeMinDays/leadTimeMaxDays are SET (owner feedback #7, 2026-09-14: submit now refuses a
// TILE row with neither) -- this fixture backs revision-numbering/needsRework tests that call
// submit() and have nothing to do with lead time.
const ONE_ITEM = {
  model: 'Trilogy', color: 'Ash', texture: 'Matt', sizeText: '60x60', thicknessMm: 10, sqmPerPiece: 0.36,
  quantityMode: 'AREA', areaSqm: 20, wastageMode: 'NONE', wastageValue: 0, piecesPerBox: 3, unitPrice: 850, discountPct: 0,
  leadTimeMinDays: 30, leadTimeMaxDays: 45,
};

describe('mock dealQuotations -- items are @NotEmpty, as on the service', () => {
  it('refuses to create a quotation with no items (400), rather than storing an empty document', async () => {
    await api.auth.login(salesUser);
    await expect(api.dealQuotations.create(18, { items: [] })).rejects.toMatchObject({ status: 400 });
  });
});

describe('mock dealQuotations -- item lines follow the document language (owner ruling 2026-09-13)', () => {
  const THAI = /[\u0E00-\u0E7F]/;
  const ADJUSTMENT = { lineType: 'ADJUSTMENT', adjustmentPct: 3, adjustmentDeadline: '2026-07-31' };

  it('an ENGLISH quotation\'s tile lines, tile unit and discount text carry no Thai', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await api.dealQuotations.create(18, {
      documentLanguage: 'EN', currency: 'USD', items: [{ ...ONE_ITEM, productCode: 'APGBBK15' }, ADJUSTMENT],
    });
    const [tile, adjustment] = quotation.items;
    expect(tile.descriptionLine).toBe('Tile Model Trilogy Color Ash Finish Matt No.APGBBK15');
    expect(tile.calculationLine).toMatch(/^\(Area 20 sqm @ .+ pcs\/sqm = .+ pcs, rounded up to full boxes = .+ pcs\) \(3 pcs\/box\)$/);
    expect(tile.unit).toBe('PCS');
    expect(adjustment.descriptionLine).toBe('Special discount 3% for orders placed by July 31, 2026');
    for (const text of [tile.descriptionLine, tile.sizeLine, tile.calculationLine, tile.unit, adjustment.descriptionLine]) {
      expect(text).not.toMatch(THAI);
    }
  });

  it('a THAI quotation keeps its Thai lines and แผ่น (wrong-way-round)', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await api.dealQuotations.create(18, { items: [ONE_ITEM, ADJUSTMENT] });
    expect(quotation.items[0].descriptionLine).toBe('กระเบื้อง รุ่น Trilogy สี Ash ผิว Matt');
    expect(quotation.items[0].unit).toBe('แผ่น');
    expect(quotation.items[1].descriptionLine).toBe('ส่วนลดพิเศษ 3% สำหรับการสั่งซื้อภายใน 31/07/2569');
  });
});

describe('mock dealQuotations -- English per-sqm (owner decision 2026-09-13) mirrors the RULES', () => {
  it('accepts SPECIAL_SQM on English with box data, printing SQM and the box line; the numbers stay the server\'s', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await api.dealQuotations.create(18, {
      priceMode: 'SPECIAL_SQM', documentLanguage: 'EN', currency: 'USD',
      items: [{ ...ONE_ITEM, piecesPerBox: 28, sqmPerBox: 0.6, unitPrice: 64, specialPriceSqm: 64 }],
    });
    const [tile] = quotation.items;
    expect(tile.unit).toBe('SQM');
    expect(tile.specialPriceLine).toBe('(1 box = 28 pcs = 0.6 sqm)');
    expect(tile.quantity).toBeNull();
    expect(tile.lineAmount).toBeNull();
  });

  it('refuses an English per-sqm row with no ตร.ม./กล่อง (400), on create and on the preview', async () => {
    await api.auth.login(salesUser);
    await expect(api.dealQuotations.create(18, {
      priceMode: 'SPECIAL_SQM', documentLanguage: 'EN', items: [{ ...ONE_ITEM, specialPriceSqm: 64 }],
    })).rejects.toMatchObject({ status: 400, message: expect.stringContaining('ตร.ม./กล่อง') });
    await expect(api.dealQuotations.calculateLine({ ...ONE_ITEM, specialPriceSqm: 64 }, 'EN'))
      .rejects.toMatchObject({ status: 400 });
    // The same row previewed in Thai is an ordinary ราคาพิเศษ — no box rule.
    await expect(api.dealQuotations.calculateLine({ ...ONE_ITEM, specialPriceSqm: 64 })).resolves.toBeTruthy();
  });

  it('calculateLine takes the document language: English lines on EN', async () => {
    await api.auth.login(salesUser);
    const { item } = await api.dealQuotations.calculateLine(ONE_ITEM, 'EN');
    expect(item.descriptionLine).toBe('Tile Model Trilogy Color Ash Finish Matt');
    expect(item.unit).toBe('PCS');
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

  it('needsRework=true selects a rejected DRAFT permanently -- resubmitting mints a NEW revision, it does not leave แก้', async () => {
    // Owner clarification (2026-09-15): submit() on a rejected draft now mints a revision of
    // ITSELF (DealQuotationService#submit's own status-machine comment) instead of resubmitting
    // the SAME row -- so the rejected row's own approvalNote is no longer cleared "on the next
    // submit"; it stays a permanent record of why THAT number was retired, and the row stays แก้
    // until it is finally SUPERSEDED (once its new revision reaches APPROVED, not before). This
    // test used to pin "cleared on the next submit, leaves แก้ immediately" -- that behaviour is
    // exactly what this owner clarification supersedes; see `mintDealQuotationRevision` in
    // mockApi.js.
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

    // Resubmitting mints a NEW revision (a different id) and leaves the REJECTED row's own note
    // untouched -- it stays in แก้, the new revision does not join it (PENDING_APPROVAL is not แก้).
    const { quotation: revision } = await api.dealQuotations.submit(id);
    expect(revision.id).not.toBe(id);
    expect(revision.docStatus).toBe('PENDING_APPROVAL');

    rework = await api.dealQuotations.list({ needsRework: true });
    // The rejected row itself stays แก้ until superseded.
    expect(rework.items.some((q) => q.id === id)).toBe(true);
    expect(rework.items.some((q) => q.id === revision.id)).toBe(false);
  });

  /** Mirrors DealQuotationRepository#supersede's own widened WHERE clause -- a DRAFT (rejected)
   * parent becomes SUPERSEDED the SAME way and at the SAME time an APPROVED parent already does:
   * only once its OWN revision reaches APPROVED, not before. */
  it('submit after rejection: the rejected row becomes SUPERSEDED only once the revision is approved', async () => {
    await api.auth.login(salesUser);
    const created = await api.dealQuotations.create(18, { items: [ONE_ITEM] });
    const id = created.quotation.id;
    await api.dealQuotations.submit(id);
    await api.auth.login({ role: 'sales_manager' });
    await api.dealQuotations.reject(id, { reason: 'แก้ราคา' });

    await api.auth.login(salesUser);
    const { quotation: revision } = await api.dealQuotations.submit(id);

    // Not yet -- the revision itself hasn't been approved.
    let rejected = (await api.dealQuotations.get(id)).quotation;
    expect(rejected.docStatus).toBe('DRAFT');

    await api.auth.login({ role: 'sales_manager' });
    await api.dealQuotations.approve(revision.id, {});

    rejected = (await api.dealQuotations.get(id)).quotation;
    expect(rejected.docStatus).toBe('SUPERSEDED');
  });

  /** Mirrors DealQuotationRepository#hasOpenRevision's own guard -- a double-tap on "ส่งใหม่" must
   * not silently mint two revisions of the same rejected draft. */
  it('submit after rejection: resubmitting the SAME rejected row twice is a 409, not a second revision', async () => {
    await api.auth.login(salesUser);
    const created = await api.dealQuotations.create(18, { items: [ONE_ITEM] });
    const id = created.quotation.id;
    await api.dealQuotations.submit(id);
    await api.auth.login({ role: 'sales_manager' });
    await api.dealQuotations.reject(id, { reason: 'แก้ราคา' });

    await api.auth.login(salesUser);
    await api.dealQuotations.submit(id);

    await expect(api.dealQuotations.submit(id)).rejects.toMatchObject({ status: 409 });
  });

  /** Numbering must chain off the ORIGINAL base through multiple reject/resubmit cycles -- never
   * "{base}-1-2-3" -- the mock's own mirror of
   * DealQuotationIntegrationTest#submit_afterRejection_numberingChainsThroughMultipleRejectCycles. */
  it('submit after rejection: numbering chains through multiple reject cycles', async () => {
    await api.auth.login(salesUser);
    const created = await api.dealQuotations.create(18, { items: [ONE_ITEM] });
    const base = created.quotation.number.replace(/-\d+$/, '');

    await api.dealQuotations.submit(created.quotation.id);
    await api.auth.login({ role: 'sales_manager' });
    await api.dealQuotations.reject(created.quotation.id, { reason: 'รอบ 1' });

    await api.auth.login(salesUser);
    const { quotation: revision2 } = await api.dealQuotations.submit(created.quotation.id);
    expect(revision2.number).toBe(`${base}-2`);

    await api.auth.login({ role: 'sales_manager' });
    await api.dealQuotations.reject(revision2.id, { reason: 'รอบ 2' });

    await api.auth.login(salesUser);
    const { quotation: revision3 } = await api.dealQuotations.submit(revision2.id);
    expect(revision3.number).toBe(`${base}-3`);
    expect(revision3.parentQuotationId).toBe(revision2.id);
  });

  /** Opus review (2026-09-15), REQUIRED, wrong-way-round: mirrors
   * DealQuotationIntegrationTest#cancel_refusesADraftWithAnOpenChild_closingTheDoubleApproveHole.
   * The ancestry a rejected-and-resubmitted row can grow is a TREE, not a straight line --
   * cancelling a DRAFT row that itself has an open child used to make that child's GRANDPARENT
   * look "free" again, letting it mint a SECOND, sibling branch while the first branch was still
   * alive -- two independently-APPROVED quotations on the same ticket. */
  it('cancel refuses a draft with an open child, closing the double-approve hole', async () => {
    await api.auth.login(salesUser);
    const created = await api.dealQuotations.create(18, { items: [ONE_ITEM] });
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

    // THE FIX: B cannot be cancelled out from under its own open child.
    await expect(api.dealQuotations.cancel(revisionB.id, {})).rejects.toMatchObject({ status: 409 });
  });

  it('cancel still works on a plain draft with no open child (regression guard)', async () => {
    await api.auth.login(salesUser);
    const created = await api.dealQuotations.create(18, { items: [ONE_ITEM] });
    const { quotation: cancelled } = await api.dealQuotations.cancel(created.quotation.id, {});
    expect(cancelled.docStatus).toBe('CANCELLED');
  });

  /** Opus review nit (2026-09-15): mintDealQuotationRevision used to compute
   * `parent.revisionNo + 1` directly -- only correct while `parent` is the HIGHEST revision
   * minted off this base. Once the middle revision is CANCELLED and the original resubmitted a
   * SECOND time, that formula recomputes the SAME "-2" the cancelled row already used, instead
   * of the "-3" DealQuotationRepository#nextRevisionNo's real MAX-based query would mint. */
  it('submit after rejection: a cancelled sibling revision still counts toward the next number', async () => {
    await api.auth.login(salesUser);
    const created = await api.dealQuotations.create(18, { items: [ONE_ITEM] });
    const base = created.quotation.number.replace(/-\d+$/, '');
    const originalId = created.quotation.id;

    await api.dealQuotations.submit(originalId);
    await api.auth.login({ role: 'sales_manager' });
    await api.dealQuotations.reject(originalId, { reason: 'รอบ 1' });

    await api.auth.login(salesUser);
    const { quotation: revision2 } = await api.dealQuotations.submit(originalId);
    expect(revision2.number).toBe(`${base}-2`);

    await api.auth.login({ role: 'sales_manager' });
    await api.dealQuotations.reject(revision2.id, { reason: 'รอบ 2' });

    // revision2 is DRAFT again -- cancel it instead of resubmitting it, freeing the original
    // (originalId) to be resubmitted a SECOND time (hasOpenRevision no longer sees an open child).
    await api.auth.login(salesUser);
    await api.dealQuotations.cancel(revision2.id, {});

    // Must not collide with revision2's already-used -2.
    const { quotation: revision3 } = await api.dealQuotations.submit(originalId);
    expect(revision3.number).toBe(`${base}-3`);
    expect(revision3.number).not.toBe(revision2.number);
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

// ─────────────────────────────────────────────────────────────────────────────────────────────
// V178 (owner ruling 2026-09-14) — remark 7's second กำหนดยืนยันราคา variant (validityMode DATE).
// Mirrors DealQuotationService's create/update/submit/approve rules exactly, and reuses the SAME
// hasSpecialPricing (quotationMeta.js) the editor's own toggle hides/shows against.
// ─────────────────────────────────────────────────────────────────────────────────────────────

describe('mock dealQuotations -- V178 validityMode DATE', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  // net (700) < list (850) -- DIRECT_NET special pricing, rule (b).
  const DISCOUNTED_DIRECT_NET_ITEM = { ...ONE_ITEM, directNetPrice: 700 };

  function pinToday(iso) {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.setSystemTime(new Date(`${iso}T09:00:00+07:00`));
  }

  it('DATE mode without special pricing (net == list, no discount) is refused on create', async () => {
    await api.auth.login(salesUser);
    await expect(api.dealQuotations.create(18, {
      priceMode: 'DIRECT_NET', validityMode: 'DATE', validityUntil: '2099-01-01',
      items: [{ ...ONE_ITEM, directNetPrice: 850 }], // net == list -- no special pricing
    })).rejects.toMatchObject({ status: 400, message: expect.stringContaining('ระบุวันที่ยืนราคาได้เฉพาะ') });
  });

  it('DATE mode with special pricing but no date is refused', async () => {
    await api.auth.login(salesUser);
    await expect(api.dealQuotations.create(18, {
      priceMode: 'DIRECT_NET', validityMode: 'DATE', items: [DISCOUNTED_DIRECT_NET_ITEM],
    })).rejects.toMatchObject({ status: 400, message: expect.stringContaining('กรุณาระบุวันที่ยืนราคา') });
  });

  it('DATE before the quotation\'s own date is refused', async () => {
    pinToday('2026-09-14');
    await api.auth.login(salesUser);
    await expect(api.dealQuotations.create(18, {
      priceMode: 'DIRECT_NET', validityMode: 'DATE', validityUntil: '2026-09-13',
      items: [DISCOUNTED_DIRECT_NET_ITEM],
    })).rejects.toMatchObject({ status: 400, message: expect.stringContaining('ต้องไม่ก่อนวันที่ใบเสนอราคา') });
  });

  it('round-trips validityMode/validityUntil through create -> get', async () => {
    pinToday('2026-09-14');
    await api.auth.login(salesUser);
    const { quotation: created } = await api.dealQuotations.create(18, {
      priceMode: 'DIRECT_NET', validityMode: 'DATE', validityUntil: '2026-10-31',
      items: [DISCOUNTED_DIRECT_NET_ITEM],
    });
    expect(created.validityMode).toBe('DATE');
    expect(created.validityUntil).toBe('2026-10-31');

    const { quotation: reread } = await api.dealQuotations.get(created.id);
    expect(reread.validityMode).toBe('DATE');
    expect(reread.validityUntil).toBe('2026-10-31');
  });

  it('an update that clears the discount while still requesting DATE mode is refused', async () => {
    pinToday('2026-09-14');
    await api.auth.login(salesUser);
    const { quotation: created } = await api.dealQuotations.create(18, {
      priceMode: 'DIRECT_NET', validityMode: 'DATE', validityUntil: '2026-10-31',
      items: [DISCOUNTED_DIRECT_NET_ITEM],
    });
    await expect(api.dealQuotations.update(created.id, {
      priceMode: 'DIRECT_NET', validityMode: 'DATE', validityUntil: '2026-10-31',
      items: [{ ...ONE_ITEM, directNetPrice: 850 }], // net back to list -- no more special pricing
    })).rejects.toMatchObject({ status: 400, message: expect.stringContaining('ระบุวันที่ยืนราคาได้เฉพาะ') });
  });

  it('submit refuses a validity date that has already passed', async () => {
    pinToday('2026-09-14');
    await api.auth.login(salesUser);
    const { quotation: created } = await api.dealQuotations.create(18, {
      priceMode: 'DIRECT_NET', validityMode: 'DATE', validityUntil: '2026-09-14', // == today, allowed
      items: [DISCOUNTED_DIRECT_NET_ITEM],
    });
    pinToday('2026-09-15'); // time moves on after the draft is saved
    await expect(api.dealQuotations.submit(created.id)).rejects
      .toMatchObject({ status: 400, message: expect.stringContaining('วันที่ยืนราคาผ่านไปแล้ว') });
  });

  it('approve sets validityDate to validityUntil, NOT approvalDate + validityDays', async () => {
    pinToday('2026-09-14');
    await api.auth.login(salesUser);
    const { quotation: created } = await api.dealQuotations.create(18, {
      priceMode: 'DIRECT_NET', validityMode: 'DATE', validityUntil: '2026-12-25', validityDays: 45,
      items: [DISCOUNTED_DIRECT_NET_ITEM],
    });
    await api.dealQuotations.submit(created.id);
    await api.auth.login({ role: 'sales_manager' });
    const { quotation: approved } = await api.dealQuotations.approve(created.id, {});
    expect(approved.validityDate).toBe('2026-12-25');
    expect(approved.validityDate).not.toBe('2026-10-29'); // 2026-09-14 + 45 days -- the DAYS answer
  });

  it('createRevision copies validityMode and validityUntil verbatim', async () => {
    pinToday('2026-09-14');
    await api.auth.login(salesUser);
    const { quotation: created } = await api.dealQuotations.create(18, {
      priceMode: 'DIRECT_NET', validityMode: 'DATE', validityUntil: '2026-12-25',
      items: [DISCOUNTED_DIRECT_NET_ITEM],
    });
    await api.dealQuotations.submit(created.id);
    await api.auth.login({ role: 'sales_manager' });
    const { quotation: approved } = await api.dealQuotations.approve(created.id, {});
    await api.auth.login(salesUser);
    const { quotation: revision } = await api.dealQuotations.createRevision(approved.id);
    expect(revision.validityMode).toBe('DATE');
    expect(revision.validityUntil).toBe('2026-12-25');
  });

  it('DAYS mode (the default) behaves exactly as before this change', async () => {
    pinToday('2026-09-14');
    await api.auth.login(salesUser);
    const { quotation: created } = await api.dealQuotations.create(18, { validityDays: 30, items: [ONE_ITEM] });
    expect(created.validityMode).toBe('DAYS');
    expect(created.validityUntil).toBeNull();
    await api.dealQuotations.submit(created.id);
    await api.auth.login({ role: 'sales_manager' });
    const { quotation: approved } = await api.dealQuotations.approve(created.id, {});
    expect(approved.validityMode).toBe('DAYS');
    expect(approved.validityDate).toBe('2026-10-14'); // 2026-09-14 + validityDays default 30
  });
});

// ─────────────────────────────────────────────────────────────────────────────────────────────
// V179 (owner feedback #4, 2026-09-14) — ผู้พิมพ์/พนักงานขาย print-name override.
// demoData.js ids used below: 6 = sales@glr.co.th (ticket 18's owner), 9 = sales.manager@glr.co.th,
// 4 = employee@glr.co.th (canCreateQuotation grant, role `employee`), 7 = import@glr.co.th
// (neither sales-shaped role nor grant).
// ⚠️ AUTHZ CAVEAT (CLAUDE.md "Mock API contract"): mock authz is NOT authoritative — see
// DealQuotationDisplayNameIntegrationTest for the real-DB evidence.
// ─────────────────────────────────────────────────────────────────────────────────────────────
describe('mock dealQuotations.displayNameOptions -- V179 eligible union', () => {
  it('lists sales, sales_manager and the canCreateQuotation grant holder; excludes import', async () => {
    await api.auth.login(salesUser);
    const { items } = await api.dealQuotations.displayNameOptions();
    const ids = items.map((o) => o.id);
    expect(ids).toEqual(expect.arrayContaining([6, 9, 4]));
    expect(ids).not.toContain(7);
  });

  it('a sales_manager may also list the options', async () => {
    await api.auth.login({ role: 'sales_manager' });
    const { items } = await api.dealQuotations.displayNameOptions();
    expect(items.length).toBeGreaterThan(0);
  });

  it('a role with neither a sales-shaped role nor the grant is refused (403)', async () => {
    await api.auth.login({ role: 'import' });
    await expect(api.dealQuotations.displayNameOptions())
      .rejects.toMatchObject({ status: 403 });
  });
});

describe('mock dealQuotations.create/update -- V179 printedByDisplayId/salesRepDisplayId', () => {
  it('a valid printedByDisplayId/salesRepDisplayId round-trips on create, with names resolved', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await api.dealQuotations.create(18, {
      printedByDisplayId: 4, salesRepDisplayId: 9, items: [ONE_ITEM],
    });
    expect(quotation.printedByDisplayId).toBe(4);
    expect(quotation.salesRepDisplayId).toBe(9);
    expect(quotation.printedByDisplayName).toBeTruthy();
    expect(quotation.salesRepDisplayName).toBeTruthy();
  });

  it('an id outside the eligible union is refused (400) on create', async () => {
    await api.auth.login(salesUser);
    await expect(api.dealQuotations.create(18, { printedByDisplayId: 7, items: [ONE_ITEM] }))
      .rejects.toMatchObject({ status: 400 });
  });

  it('update accepts null to clear a previously-set display override', async () => {
    await api.auth.login(salesUser);
    const { quotation: created } = await api.dealQuotations.create(18, {
      printedByDisplayId: 4, items: [ONE_ITEM],
    });
    expect(created.printedByDisplayId).toBe(4);
    const { quotation: cleared } = await api.dealQuotations.update(created.id, { items: [ONE_ITEM] });
    expect(cleared.printedByDisplayId).toBeNull();
  });

  it('update refuses an ineligible id too (400)', async () => {
    await api.auth.login(salesUser);
    const { quotation: created } = await api.dealQuotations.create(18, { items: [ONE_ITEM] });
    await expect(api.dealQuotations.update(created.id, { salesRepDisplayId: 7, items: [ONE_ITEM] }))
      .rejects.toMatchObject({ status: 400 });
  });

  // Wrong-way-round: the display override must never touch the real ownership fields.
  it('setting salesRepDisplayId does not change the real salesRepId/createdById', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await api.dealQuotations.create(18, {
      salesRepDisplayId: 9, items: [ONE_ITEM],
    });
    expect(quotation.salesRepId).toBe(6); // ticket 18's real owner, unchanged
    expect(quotation.salesRepDisplayId).toBe(9);
  });

  it('createRevision copies both display ids verbatim', async () => {
    await api.auth.login(salesUser);
    const { quotation: created } = await api.dealQuotations.create(18, {
      printedByDisplayId: 4, salesRepDisplayId: 9, items: [ONE_ITEM],
    });
    await api.dealQuotations.submit(created.id);
    await api.auth.login({ role: 'sales_manager' });
    const { quotation: approved } = await api.dealQuotations.approve(created.id, {});
    await api.auth.login(salesUser);
    const { quotation: revision } = await api.dealQuotations.createRevision(approved.id);
    expect(revision.printedByDisplayId).toBe(4);
    expect(revision.salesRepDisplayId).toBe(9);
  });
});

// Opus review fix (2026-09-14): create() ignored payload.projectName entirely (always derived
// from the deal's own project) and update() omitted the field from its Object.assign altogether,
// so editing โครงการ silently never persisted under VITE_USE_MOCKS=true -- exactly CLAUDE.md's
// "mock omits a field the feature keys on" shape. Mirrors DealQuotationService#create/#update.
describe('mock dealQuotations.create/update -- projectName (owner feedback 2026-09-14)', () => {
  it('an explicit projectName on create wins over the deal\'s own project', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await api.dealQuotations.create(18, {
      projectName: 'โครงการทดสอบ A', items: [ONE_ITEM],
    });
    expect(quotation.projectName).toBe('โครงการทดสอบ A');
  });

  it('update corrects a typo in the project name, and it round-trips on read', async () => {
    await api.auth.login(salesUser);
    const { quotation: created } = await api.dealQuotations.create(18, {
      projectName: 'ABC', items: [ONE_ITEM],
    });
    const { quotation: corrected } = await api.dealQuotations.update(created.id, {
      projectName: 'Associates By Choice', items: [ONE_ITEM],
    });
    expect(corrected.projectName).toBe('Associates By Choice');
    const { quotation: reread } = await api.dealQuotations.get(created.id);
    expect(reread.projectName).toBe('Associates By Choice');
  });

  it('a blank projectName on update clears it -- no "missing keeps stored"', async () => {
    await api.auth.login(salesUser);
    const { quotation: created } = await api.dealQuotations.create(18, {
      projectName: 'โครงการเดิม', items: [ONE_ITEM],
    });
    const { quotation: cleared } = await api.dealQuotations.update(created.id, {
      projectName: null, items: [ONE_ITEM],
    });
    expect(cleared.projectName).toBeNull();
  });

  // Second Opus follow-up nit (2026-09-14): the first pass's update() wrote
  // `payload.projectName ?? null`, which stores an explicit ""/whitespace-only string AS-IS
  // instead of clearing it -- diverging from the real DealQuotationService#update's
  // `blankToNull(request.projectName())` and from this file's own create() two tests above (which
  // already trims+blanks). An explicitly-blank string reaches update() from
  // buildUpsertPayload's `terms.projectName || null` only when the field is whitespace-only (a
  // plain "" is already caught by `||`), so this exercises exactly that gap.
  it('a whitespace-only projectName on update clears it too, not stored literally', async () => {
    await api.auth.login(salesUser);
    const { quotation: created } = await api.dealQuotations.create(18, {
      projectName: 'โครงการเดิม', items: [ONE_ITEM],
    });
    const { quotation: cleared } = await api.dealQuotations.update(created.id, {
      projectName: '   ', items: [ONE_ITEM],
    });
    expect(cleared.projectName).toBeNull();
  });
});

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Item 2 (V180, "ไม่เติม “คุณ” หน้าชื่อผู้สั่งซื้อ") + Item 4 (V181, "ไม่รับมัดจำ") —
// owner ruling 2026-09-16. Mirrors DealQuotationService#resolveOmitContactHonorific/
// #isZeroDeposit/#resolveFullPaymentTerm exactly.
// ─────────────────────────────────────────────────────────────────────────────────────────────
describe('mock dealQuotations.create/update -- omitContactHonorific (V180)', () => {
  it('defaults false when the request omits it', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await api.dealQuotations.create(18, { items: [ONE_ITEM] });
    expect(quotation.omitContactHonorific).toBe(false);
  });

  it('true round-trips through create -> get', async () => {
    await api.auth.login(salesUser);
    const { quotation: created } = await api.dealQuotations.create(18, {
      omitContactHonorific: true, items: [ONE_ITEM],
    });
    expect(created.omitContactHonorific).toBe(true);
    const { quotation: reread } = await api.dealQuotations.get(created.id);
    expect(reread.omitContactHonorific).toBe(true);
  });

  // Same "no missing-keeps-stored" discipline as projectName/printedByDisplayId above -- the
  // editor always sends its CURRENT value, so an update that omits it clears a previously-set true.
  it('an update that omits it clears a previously-set true back to false', async () => {
    await api.auth.login(salesUser);
    const { quotation: created } = await api.dealQuotations.create(18, {
      omitContactHonorific: true, items: [ONE_ITEM],
    });
    const { quotation: updated } = await api.dealQuotations.update(created.id, { items: [ONE_ITEM] });
    expect(updated.omitContactHonorific).toBe(false);
  });

  it('createRevision copies it verbatim', async () => {
    await api.auth.login(salesUser);
    const { quotation: created } = await api.dealQuotations.create(18, {
      omitContactHonorific: true, items: [ONE_ITEM],
    });
    await api.dealQuotations.submit(created.id);
    await api.auth.login({ role: 'sales_manager' });
    const { quotation: approved } = await api.dealQuotations.approve(created.id, {});
    await api.auth.login(salesUser);
    const { quotation: revision } = await api.dealQuotations.createRevision(approved.id);
    expect(revision.omitContactHonorific).toBe(true);
  });
});

describe('mock dealQuotations.create/update -- fullPaymentTerm (V181, "ไม่รับมัดจำ")', () => {
  it('depositPercent 0 clears remainderMode/creditDays and stores the chosen term', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await api.dealQuotations.create(18, {
      depositPercent: 0, remainderMode: 'CREDIT', creditDays: 45,
      fullPaymentTerm: 'ON_DELIVERY', items: [ONE_ITEM],
    });
    expect(quotation.depositPercent).toBe(0);
    expect(quotation.remainderMode).toBeNull();
    expect(quotation.creditDays).toBeNull();
    expect(quotation.fullPaymentTerm).toBe('ON_DELIVERY');
  });

  // Wrong-way-round: a rep who unticks "ไม่รับมัดจำ" (an ordinary percentage) can never leave a
  // stale term attached, EVEN IF the request still sends one.
  it('a non-zero depositPercent forces fullPaymentTerm null even if the request sends one', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await api.dealQuotations.create(18, {
      depositPercent: 30, remainderMode: 'CREDIT', creditDays: 30,
      fullPaymentTerm: 'ON_DELIVERY', items: [ONE_ITEM],
    });
    expect(quotation.depositPercent).toBe(30);
    expect(quotation.fullPaymentTerm).toBeNull();
    expect(quotation.remainderMode).toBe('CREDIT');
    expect(quotation.creditDays).toBe(30);
  });

  // A null depositPercent is NOT "no deposit" -- it defaults to 30% at render time (see
  // DealQuotationRenderAdapter#depositLine) -- same refusal as an explicit non-zero percentage.
  it('a null depositPercent also forces fullPaymentTerm null', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await api.dealQuotations.create(18, {
      fullPaymentTerm: 'ON_DELIVERY', items: [ONE_ITEM],
    });
    expect(quotation.depositPercent).toBeNull();
    expect(quotation.fullPaymentTerm).toBeNull();
  });

  it('an unrecognised code is refused (400)', async () => {
    await api.auth.login(salesUser);
    await expect(api.dealQuotations.create(18, {
      depositPercent: 0, fullPaymentTerm: 'SOMETHING_ELSE', items: [ONE_ITEM],
    })).rejects.toMatchObject({ status: 400 });
  });

  it('update switching TO depositPercent 0 clears remainderMode/creditDays and stores the term', async () => {
    await api.auth.login(salesUser);
    const { quotation: created } = await api.dealQuotations.create(18, {
      depositPercent: 30, remainderMode: 'CREDIT', creditDays: 30, items: [ONE_ITEM],
    });
    const { quotation: updated } = await api.dealQuotations.update(created.id, {
      depositPercent: 0, fullPaymentTerm: 'BEFORE_DELIVERY', items: [ONE_ITEM],
    });
    expect(updated.depositPercent).toBe(0);
    expect(updated.remainderMode).toBeNull();
    expect(updated.creditDays).toBeNull();
    expect(updated.fullPaymentTerm).toBe('BEFORE_DELIVERY');
  });

  // Wrong-way-round: a zero-deposit DRAFT is saveable with no term chosen yet (create/update
  // above never refuse it) -- submit() is the one gate.
  it('submit refuses a zero-deposit document with no term chosen', async () => {
    await api.auth.login(salesUser);
    const { quotation: created } = await api.dealQuotations.create(18, {
      depositPercent: 0, items: [ONE_ITEM],
    });
    await expect(api.dealQuotations.submit(created.id)).rejects
      .toMatchObject({ status: 400, message: expect.stringContaining('เงื่อนไขการชำระเงิน') });
  });

  it('submit succeeds once a term is chosen', async () => {
    await api.auth.login(salesUser);
    const { quotation: created } = await api.dealQuotations.create(18, {
      depositPercent: 0, fullPaymentTerm: 'ON_OR_BEFORE_DELIVERY', items: [ONE_ITEM],
    });
    const { quotation: submitted } = await api.dealQuotations.submit(created.id);
    expect(submitted.docStatus).toBe('PENDING_APPROVAL');
    expect(submitted.fullPaymentTerm).toBe('ON_OR_BEFORE_DELIVERY');
  });

  // A non-zero-deposit document is never blocked by the new submit gate.
  it('submit is unaffected by the new gate on an ordinary non-zero deposit', async () => {
    await api.auth.login(salesUser);
    const { quotation: created } = await api.dealQuotations.create(18, { items: [ONE_ITEM] });
    const { quotation: submitted } = await api.dealQuotations.submit(created.id);
    expect(submitted.docStatus).toBe('PENDING_APPROVAL');
  });

  it('createRevision copies fullPaymentTerm verbatim', async () => {
    await api.auth.login(salesUser);
    const { quotation: created } = await api.dealQuotations.create(18, {
      depositPercent: 0, fullPaymentTerm: 'ON_DELIVERY', items: [ONE_ITEM],
    });
    await api.dealQuotations.submit(created.id);
    await api.auth.login({ role: 'sales_manager' });
    const { quotation: approved } = await api.dealQuotations.approve(created.id, {});
    await api.auth.login(salesUser);
    const { quotation: revision } = await api.dealQuotations.createRevision(approved.id);
    expect(revision.depositPercent).toBe(0);
    expect(revision.fullPaymentTerm).toBe('ON_DELIVERY');
  });
});
