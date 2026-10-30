import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest';

// Slice 2 (SLICE-2-FLOW-A.md Part 1) changed two things every create below depends on:
//   S2-B1 — recipientType is REQUIRED on a DEAL_DIRECT create (400 otherwise). None of this file's
//           cases is about the recipient (mockApi.quotationDealLink.test.js pins that), so each
//           defaults it to DESIGNER; a payload's own recipientType still wins.
//   S2-B3 — N6: a deal holds at most ONE live direct quotation. This file predates that rule and
//           creates on demo ticket 18 again and again — and the seed itself keeps two live rows
//           there (id 1 DRAFT, id 2 PENDING_APPROVAL) that other cases below work on directly. So:
//           every test now gets a FRESH mock module (pristine seed, no state leaking between cases),
//           and a create on 18 first retires whatever is live there — the state a rep reaches by
//           cancelling it. A test that needs two live quotations on one deal builds the second
//           through createReorder/createRevision, the only routes N6 leaves open.
//   One pricing route per deal (owner ruling 2026-09-30) — the reverse lock: a deal with a LIVE
//           pricing request refuses a direct create (409). Ticket 18's seed carries live คำขอราคา
//           too, so createOn18 also retires those first (mockApi.quotationDealLink.test.js pins the
//           rule itself, wrong-way-round included).
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
  return api.dealQuotations.create(18, { recipientType: 'DESIGNER', ...payload });
}

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

// Sales feedback #1 (2026-10-06, V192.2) — a Thai tile row may state its prices per ตร.ม. including
// VAT. Mirrors DealQuotationService#withPerPiecePricesFromPerSqm's RULES (what is refused, what is
// kept, which mode reads what).
//
// ⚠️ The per-แผ่น figures below come from mockNetPerPieceFromSpecialSqm — a labelled, non-
// authoritative MIRROR of WastageCalculator. These cases prove the mock's plumbing and nothing
// about the algorithm (CLAUDE.md, "where the mock MIRRORS a backend computation"); the conversion
// itself is pinned on the real service by WastageCalculatorPerSqmPriceTest and
// DealQuotationPerSqmPriceIntegrationTest.
describe('mock dealQuotations -- prices typed per ตร.ม. รวม VAT (V192.2)', () => {
  const TILE_60X120 = {
    model: 'Trilogy', color: 'Ivory', texture: 'Lappato', sizeText: '60x120', thicknessMm: 10, sqmPerPiece: 0.72,
    quantityMode: 'PIECES', piecesInput: 10, wastageMode: 'NONE', wastageValue: 0, piecesPerBox: 2,
    leadTimeMinDays: 30, leadTimeMaxDays: 45,
  };
  const perSqm = (fields) => ({ ...TILE_60X120, unitPrice: null, discountPct: 0, ...fields });

  it('NET: derives the per-แผ่น list price, keeps the typed figure, and prices the line off the derived one', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await createOn18({ items: [perSqm({ listPriceSqm: 1800, discountPct: 20 })] });
    expect(quotation.items[0]).toMatchObject({
      listPriceSqm: 1800, unitPrice: 1210.25, netUnitPrice: 968.2, lineAmount: 9682, directNetPriceSqm: null,
    });
  });

  it('a per-แผ่น price sent beside the per-ตร.ม. one is not consulted', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await createOn18({ items: [perSqm({ listPriceSqm: 1800, unitPrice: 1 })] });
    expect(quotation.items[0].unitPrice).toBe(1210.25);
  });

  it('DIRECT_NET: both the net and the list are read per ตร.ม.', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await createOn18({
      priceMode: 'DIRECT_NET', items: [perSqm({ listPriceSqm: 1800, directNetPriceSqm: 790, discountPct: null })],
    });
    expect(quotation.items[0]).toMatchObject({
      listPriceSqm: 1800, directNetPriceSqm: 790, unitPrice: 1210.25, netUnitPrice: 531.16, lineAmount: 5311.6,
    });
  });

  it('a net per ตร.ม. outside DIRECT_NET prices nothing and is not kept', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await createOn18({ items: [perSqm({ listPriceSqm: 1800, directNetPriceSqm: 790 })] });
    expect(quotation.items[0]).toMatchObject({ unitPrice: 1210.25, netUnitPrice: 1210.25, directNetPriceSqm: null });
  });

  it('a request that still states its price per แผ่น is priced exactly as before, with no per-ตร.ม. figure', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await createOn18({ items: [{ ...TILE_60X120, unitPrice: 1748.13, discountPct: 0 }] });
    expect(quotation.items[0]).toMatchObject({ unitPrice: 1748.13, netUnitPrice: 1748.13, listPriceSqm: null, directNetPriceSqm: null });
  });

  it('repricing a saved row per แผ่น drops the per-ตร.ม. figure it carried', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await createOn18({ items: [perSqm({ listPriceSqm: 1800 })] });
    const { quotation: saved } = await api.dealQuotations.update(quotation.id, {
      items: [{ ...TILE_60X120, id: quotation.items[0].id, unitPrice: 999, discountPct: 0 }],
    });
    expect(saved.items[0]).toMatchObject({ id: quotation.items[0].id, unitPrice: 999, listPriceSqm: null });
  });

  // The refusals — a mock MORE permissive than the service is the dangerous direction.
  it('refuses a VAT-inclusive per-ตร.ม. price on an English document (400)', async () => {
    await api.auth.login(salesUser);
    await expect(createOn18({
      documentLanguage: 'EN', currency: 'USD', items: [perSqm({ listPriceSqm: 1800, unitPrice: 15 })],
    })).rejects.toMatchObject({ status: 400, message: expect.stringContaining('ใช้ได้กับใบเสนอราคาภาษาไทยเท่านั้น') });
  });

  it('refuses a per-ตร.ม. price on a row with no area per piece (400) — never read as a price per แผ่น', async () => {
    await api.auth.login(salesUser);
    await expect(createOn18({ items: [perSqm({ listPriceSqm: 1800, unitPrice: 1800, sqmPerPiece: null })] }))
      .rejects.toMatchObject({ status: 400, message: expect.stringContaining('ขาด แผ่น/ตร.ม.') });
    await expect(api.dealQuotations.calculateLine(perSqm({ listPriceSqm: 1800, unitPrice: 1800, sqmPerPiece: null }), 'TH'))
      .rejects.toMatchObject({ status: 400, message: expect.stringContaining('ขาด แผ่น/ตร.ม.') });
  });

  it('refuses a zero or negative per-ตร.ม. price (400), even beside a valid per-แผ่น one', async () => {
    await api.auth.login(salesUser);
    for (const bad of [0, -1800]) {
      await expect(createOn18({ items: [perSqm({ listPriceSqm: bad, unitPrice: 1210.25 })] }))
        .rejects.toMatchObject({ status: 400, message: expect.stringContaining('ราคาตั้งต่อ ตร.ม. ต้องมากกว่าศูนย์') });
      await expect(createOn18({
        priceMode: 'DIRECT_NET', items: [perSqm({ listPriceSqm: 1800, directNetPriceSqm: bad, discountPct: null })],
      })).rejects.toMatchObject({ status: 400, message: expect.stringContaining('ราคาสุทธิต่อ ตร.ม. ต้องมากกว่าศูนย์') });
    }
  });

  it('calculate-line previews a per-ตร.ม. price, and reads a net per ตร.ม. as ราคาสุทธิ mode', async () => {
    await api.auth.login(salesUser);
    const net = await api.dealQuotations.calculateLine(perSqm({ listPriceSqm: 1800, discountPct: 20 }), 'TH');
    expect(net.item).toMatchObject({ listPriceSqm: 1800, unitPrice: 1210.25, netUnitPrice: 968.2, lineAmount: 9682 });
    const direct = await api.dealQuotations.calculateLine(
      perSqm({ listPriceSqm: 1800, directNetPriceSqm: 790, discountPct: null }), 'TH');
    expect(direct.item).toMatchObject({ unitPrice: 1210.25, netUnitPrice: 531.16, lineAmount: 5311.6 });
  });

  it('a revision carries the typed per-ตร.ม. prices', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await createOn18({
      priceMode: 'DIRECT_NET', items: [perSqm({ listPriceSqm: 1800, directNetPriceSqm: 790, discountPct: null })],
    });
    await api.dealQuotations.submit(quotation.id);
    await api.auth.login({ role: 'sales_manager' });
    await api.dealQuotations.approve(quotation.id, {});
    await api.auth.login(salesUser);
    const { quotation: revision } = await api.dealQuotations.createRevision(quotation.id);
    expect(revision.items[0]).toMatchObject({ listPriceSqm: 1800, directNetPriceSqm: 790, unitPrice: 1210.25, netUnitPrice: 531.16 });
  });
});

// Mixed price modes (V192.5; sales feedback #14, owner 2026-10-06). Mirrors the RULES of
// DealQuotationService#buildItems / #requirePriceModeAllowed and WastageCalculator#rowPriceMode:
// which document may be MIXED, that each tile row must then state its own mode, that the row is
// priced in it, and that none of it touches a single-mode document.
//
// ⚠️ Evidence note (CLAUDE.md, "Mock API contract"): these prove the MOCK's plumbing. That the
// real service prices a mixed document correctly is DealQuotationMixedPriceModeIntegrationTest's;
// a Thai ราคาพิเศษ row's net is "not computed in mock mode" here, exactly as on a single-mode
// ราคาพิเศษ document, and that is asserted rather than papered over.
describe('mock dealQuotations -- mixed price modes (V192.5)', () => {
  const TILE_60X120 = {
    model: 'Trilogy', color: 'Ivory', texture: 'Lappato', sizeText: '60x120', thicknessMm: 10, sqmPerPiece: 0.72,
    quantityMode: 'PIECES', piecesInput: 10, wastageMode: 'NONE', wastageValue: 0, piecesPerBox: 2,
    leadTimeMinDays: 30, leadTimeMaxDays: 45, unitPrice: null,
  };
  const netRow = (discountPct = 20) => ({ ...TILE_60X120, priceMode: 'NET', listPriceSqm: 1800, discountPct });
  const specialRow = () => ({ ...TILE_60X120, priceMode: 'SPECIAL_SQM', listPriceSqm: 1800, specialPriceSqm: 790, discountPct: null });
  const directRow = () => ({ ...TILE_60X120, priceMode: 'DIRECT_NET', listPriceSqm: 1800, directNetPriceSqm: 790, discountPct: null });
  const freight = () => ({ lineType: 'PLAIN', description: 'ค่าขนส่ง', quantity: 2, unit: 'JOB', unitPrice: 500, discountPct: 0 });

  it('stores the document as MIXED and prices each tile row in its own mode', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await createOn18({ priceMode: 'MIXED', items: [netRow(), specialRow(), directRow(), freight()] });

    expect(quotation.priceMode).toBe('MIXED');
    expect(quotation.items.map((it) => it.priceMode)).toEqual(['NET', 'SPECIAL_SQM', 'DIRECT_NET', null]);
    expect(quotation.items[0]).toMatchObject({ unitPrice: 1210.25, discountPct: 20, netUnitPrice: 968.2, lineAmount: 9682 });
    // ราคาพิเศษ: the rule is mirrored (the row keeps its ราคาพิเศษ and its sub-line, no percent);
    // its net is the server's to compute and is null here, as on any mock ราคาพิเศษ document.
    expect(quotation.items[1]).toMatchObject({ specialPriceSqm: 790, discountPct: null, netUnitPrice: null });
    expect(quotation.items[1].specialPriceLine).toContain('ราคาพิเศษ 790');
    expect(quotation.items[2]).toMatchObject({ directNetPriceSqm: 790, netUnitPrice: 531.16, lineAmount: 5311.6, discountPct: null });
    expect(quotation.items[0].specialPriceLine).toBeNull();
  });

  it('a read serves the row modes back', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await createOn18({ priceMode: 'MIXED', items: [netRow(), specialRow()] });
    const { quotation: reread } = await api.dealQuotations.get(quotation.id);
    expect(reread.priceMode).toBe('MIXED');
    expect(reread.items.map((it) => it.priceMode)).toEqual(['NET', 'SPECIAL_SQM']);
  });

  // Wrong-way-round, and the guard for every quotation that is not mixed.
  it('on a single-mode document a row\'s own mode changes nothing and is not stored', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await createOn18({ priceMode: 'NET', items: [{ ...specialRow(), discountPct: 0 }] });
    expect(quotation.priceMode).toBe('NET');
    expect(quotation.items[0]).toMatchObject({ priceMode: null, netUnitPrice: 1210.25, specialPriceSqm: null });
  });

  // The refusals — a mock MORE permissive than the service is the dangerous direction.
  it('refuses a tile row that states no mode on a mixed document (400), naming the row', async () => {
    await api.auth.login(salesUser);
    const noMode = { ...specialRow() };
    delete noMode.priceMode;
    await expect(createOn18({ priceMode: 'MIXED', items: [netRow(), noMode] }))
      .rejects.toMatchObject({ status: 400, message: expect.stringMatching(/รายการที่ 2.*วิธีคิดราคา/) });
  });

  it('refuses a row mode the engine does not know, and MIXED as a ROW mode (400)', async () => {
    await api.auth.login(salesUser);
    for (const bad of ['MIXED', 'net', 'PER_ITEM']) {
      await expect(createOn18({ priceMode: 'MIXED', items: [{ ...netRow(), priceMode: bad }] }))
        .rejects.toMatchObject({ status: 400 });
    }
  });

  it('refuses a mixed English document (400)', async () => {
    await api.auth.login(salesUser);
    await expect(createOn18({
      priceMode: 'MIXED', documentLanguage: 'EN', currency: 'USD',
      items: [{ ...TILE_60X120, priceMode: 'NET', unitPrice: 100, discountPct: 0 }],
    })).rejects.toMatchObject({ status: 400, message: expect.stringContaining('ภาษาไทย') });
  });

  it('refuses an unknown document mode as before (400)', async () => {
    await api.auth.login(salesUser);
    await expect(createOn18({ priceMode: 'MIX', items: [netRow()] })).rejects.toMatchObject({ status: 400 });
  });

  it('changing one row\'s mode on a saved draft re-prices that row and keeps its id', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await createOn18({ priceMode: 'MIXED', items: [netRow(), netRow(10)] });
    const [first, second] = quotation.items;
    const { quotation: saved } = await api.dealQuotations.update(quotation.id, {
      priceMode: 'MIXED', items: [{ ...netRow(), id: first.id }, { ...directRow(), id: second.id }],
    });
    expect(saved.items.map((it) => it.id)).toEqual([first.id, second.id]);
    expect(saved.items.map((it) => it.priceMode)).toEqual(['NET', 'DIRECT_NET']);
    expect(saved.items[1]).toMatchObject({ netUnitPrice: 531.16, discountPct: null });
  });

  it('back to one mode: every row is priced in it and the row modes are cleared', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await createOn18({ priceMode: 'MIXED', items: [netRow(), directRow()] });
    const { quotation: saved } = await api.dealQuotations.update(quotation.id, {
      priceMode: 'NET', items: quotation.items.map((it) => ({ ...netRow(10), id: it.id })),
    });
    expect(saved.priceMode).toBe('NET');
    expect(saved.items.map((it) => it.priceMode)).toEqual([null, null]);
  });

  it('an update that omits the document mode keeps it mixed', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await createOn18({ priceMode: 'MIXED', items: [netRow(), directRow()] });
    const { quotation: saved } = await api.dealQuotations.update(quotation.id, { items: [netRow(10), directRow()] });
    expect(saved.priceMode).toBe('MIXED');
    expect(saved.items.map((it) => it.priceMode)).toEqual(['NET', 'DIRECT_NET']);
  });

  it('calculate-line uses the mode the row states, over what its fields suggest', async () => {
    await api.auth.login(salesUser);
    // A row just switched to ราคาตั้ง − ส่วนลด % that still carries its old ราคาพิเศษ.
    const { item } = await api.dealQuotations.calculateLine({ ...netRow(), specialPriceSqm: 790 }, 'TH');
    expect(item).toMatchObject({ netUnitPrice: 968.2, lineAmount: 9682, specialPriceLine: null });
    // …and one that states none is still inferred from its fields, as before.
    const direct = await api.dealQuotations.calculateLine(
      { ...TILE_60X120, listPriceSqm: 1800, directNetPriceSqm: 790, discountPct: null }, 'TH');
    expect(direct.item).toMatchObject({ netUnitPrice: 531.16 });
  });

  it('ยืนราคาแบบระบุวันที่ is accepted when one row has special pricing, refused when none has', async () => {
    await api.auth.login(salesUser);
    const until = '2099-12-31';
    const { quotation } = await createOn18({
      priceMode: 'MIXED', validityMode: 'DATE', validityUntil: until, items: [netRow(0), specialRow()],
    });
    expect(quotation.validityMode).toBe('DATE');
    await expect(createOn18({
      priceMode: 'MIXED', validityMode: 'DATE', validityUntil: until,
      items: [netRow(0), { ...TILE_60X120, priceMode: 'DIRECT_NET', listPriceSqm: 1800, directNetPriceSqm: 1800, discountPct: null }],
    })).rejects.toMatchObject({ status: 400, message: expect.stringContaining('ระบุวันที่ยืนราคาได้เฉพาะ') });
  });

  it('a revision and a reorder carry the mix and every row\'s mode', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await createOn18({ priceMode: 'MIXED', items: [netRow(), directRow(), freight()] });
    await api.dealQuotations.submit(quotation.id);
    await api.auth.login({ role: 'sales_manager' });
    await api.dealQuotations.approve(quotation.id, {});
    await api.auth.login(salesUser);
    const { quotation: reorder } = await api.dealQuotations.createReorder(quotation.id);
    const { quotation: revision } = await api.dealQuotations.createRevision(quotation.id);
    for (const copy of [reorder, revision]) {
      expect(copy.priceMode).toBe('MIXED');
      expect(copy.items.map((it) => it.priceMode)).toEqual(['NET', 'DIRECT_NET', null]);
    }
  });
});

// 2026-10-12: any fraction of a piece rounds UP (WastageCalculator#ceilToInt), but the
// server does it on BigDecimal and the mock on floats, where 100 * (7 / 100) is 7.000000000000001
// and a bare Math.ceil turns an exact 7 into 8.
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

  it('a genuine fraction still rounds up: 2,793 sqm at 0.72 sqm/piece (1.39 pcs/sqm) is 3,883', async () => {
    const item = await line({ sqmPerPiece: 0.72, quantityMode: 'AREA', areaSqm: 2793 });
    expect(item.piecesBeforeWastage).toBe(3883);
  });
});

describe('mock dealQuotations -- items are @NotEmpty, as on the service', () => {
  it('refuses to create a quotation with no items (400), rather than storing an empty document', async () => {
    await api.auth.login(salesUser);
    await expect(createOn18({ items: [] })).rejects.toMatchObject({ status: 400 });
  });
});

describe('mock dealQuotations -- recipient (Round 8)', () => {
  // Slice 2 (S2-B1) made recipientType REQUIRED on a new direct create, so UNSPECIFIED can no longer
  // be CREATED — it survives only on a row made before slice 2. Seed row 1 is exactly such a row.
  // (mockApi.quotationDealLink.test.js pins the new create/update behaviour.)
  it('a pre-slice-2 DEAL_DIRECT row serves recipientType UNSPECIFIED, as the repository stored it', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await api.dealQuotations.get(1);
    expect(quotation.recipientType).toBe('UNSPECIFIED');
    expect(quotation.recipientLabel ?? null).toBeNull();
  });
});

describe('mock dealQuotations -- item lines follow the document language (owner ruling 2026-09-13)', () => {
  const THAI = /[\u0E00-\u0E7F]/;
  const ADJUSTMENT = { lineType: 'ADJUSTMENT', adjustmentPct: 3, adjustmentDeadline: '2026-07-31' };

  it('an ENGLISH quotation\'s tile lines, tile unit and discount text carry no Thai', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await createOn18({
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
    const { quotation } = await createOn18({ items: [ONE_ITEM, ADJUSTMENT] });
    expect(quotation.items[0].descriptionLine).toBe('กระเบื้อง รุ่น Trilogy สี Ash ผิว Matt');
    expect(quotation.items[0].unit).toBe('แผ่น');
    expect(quotation.items[1].descriptionLine).toBe('ส่วนลดพิเศษ 3% สำหรับการสั่งซื้อภายใน 31/07/2569');
  });
});

// F1 (2026-09-16 review) — the mock's own calc-line builder (computeDealQuotationLine) mirrors
// DealQuotationLines#calculationLine's phrasing; this pins the same duplicate-suppression fix on
// the mock side. Wrong-way-round: reverting the mock's default branch to the old unconditional
// rounding-phrase-plus-echo would turn these red.
describe('mock dealQuotations -- calculationLine never echoes a piece count with no box/wastage to justify it (F1)', () => {
  it('a row with NO แผ่น/กล่อง and no wastage prints no trailing "=" clause and no rounding wording', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await createOn18({
      items: [{ ...ONE_ITEM, piecesPerBox: null }],
    });
    expect(quotation.items[0].calculationLine).toBe('(พื้นที่ 20 ตร.ม.ๆละ 2.78 แผ่น รวม 56 แผ่น)');
    expect(quotation.items[0].calculationLine).not.toContain('และปัดขึ้นเต็มกล่อง');
  });

  it('the English mirror: no pcs/box and no wastage prints no trailing "=" clause and no rounding wording', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await createOn18({
      documentLanguage: 'EN', currency: 'USD',
      items: [{ ...ONE_ITEM, piecesPerBox: null }],
    });
    expect(quotation.items[0].calculationLine).toBe('(Area 20 sqm @ 2.78 pcs/sqm = 56 pcs)');
    expect(quotation.items[0].calculationLine).not.toContain('rounded up to full boxes');
  });
});

describe('mock dealQuotations -- English per-sqm (owner decision 2026-09-13) mirrors the RULES', () => {
  it('accepts SPECIAL_SQM on English with box data, printing SQM and the box line; the numbers stay the server\'s', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await createOn18({
      priceMode: 'SPECIAL_SQM', documentLanguage: 'EN', currency: 'USD',
      items: [{ ...ONE_ITEM, piecesPerBox: 28, sqmPerBox: 0.6, unitPrice: 64, specialPriceSqm: 64 }],
    });
    const [tile] = quotation.items;
    expect(tile.unit).toBe('SQM');
    expect(tile.specialPriceLine).toBe('(1 box = 28 pcs = 0.6 sqm)');
    expect(tile.quantity).toBeNull();
    expect(tile.lineAmount).toBeNull();
  });

  // Option B (owner decision, 2026-09-16): ตร.ม./กล่อง is now OPTIONAL — a blank box area is
  // accepted (with or without แผ่น/กล่อง); only a box area PRESENT with แผ่น/กล่อง BLANK (the
  // partially-filled pair) is still refused.
  it('accepts an English per-sqm row with NO ตร.ม./กล่อง, with or without แผ่น/กล่อง', async () => {
    await api.auth.login(salesUser);
    // ONE_ITEM already carries piecesPerBox: 3 and no sqmPerBox key at all.
    const { quotation } = await createOn18({
      priceMode: 'SPECIAL_SQM', documentLanguage: 'EN', items: [{ ...ONE_ITEM, specialPriceSqm: 64 }],
    });
    expect(quotation.items[0].unit).toBe('SQM');
    expect(quotation.items[0].specialPriceLine).toBeNull(); // no box sub-line without a box area

    // Neither box field at all — also accepted.
    const { quotation: neither } = await createOn18({
      priceMode: 'SPECIAL_SQM', documentLanguage: 'EN',
      items: [{ ...ONE_ITEM, piecesPerBox: null, specialPriceSqm: 64 }],
    });
    expect(neither.items[0].unit).toBe('SQM');

    await expect(api.dealQuotations.calculateLine({ ...ONE_ITEM, specialPriceSqm: 64 }, 'EN')).resolves.toBeTruthy();
  });

  it('still refuses a box area PRESENT with แผ่น/กล่อง BLANK (the partially-filled pair), on create and on the preview', async () => {
    await api.auth.login(salesUser);
    const partial = { ...ONE_ITEM, piecesPerBox: null, sqmPerBox: 0.6, specialPriceSqm: 64 };
    await expect(createOn18({
      priceMode: 'SPECIAL_SQM', documentLanguage: 'EN', items: [partial],
    })).rejects.toMatchObject({ status: 400, message: expect.stringContaining('แผ่น/กล่อง') });
    await expect(api.dealQuotations.calculateLine(partial, 'EN')).rejects.toMatchObject({ status: 400 });
    // The same row previewed in Thai is an ordinary ราคาพิเศษ — no box rule.
    await expect(api.dealQuotations.calculateLine(partial)).resolves.toBeTruthy();
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
    const { quotation } = await createOn18({ items: [{ ...ONE_ITEM, id: 4242 }] });
    expect(quotation.items[0].id).not.toBe(4242);
  });
});

describe('mock nextMockDealQuotationNumber -- #M10 number FORMAT', () => {
  // Owner feedback 2026-09-11 ("มีรันเลข -1 -2 ต่อท้ายตี้วแต่แรก" / "ใบแรกเป็น QT-2026-0014-1"): the
  // FIRST issued document carries the revision suffix too.
  //
  // The suffix is no longer pinned to -1, and that is the owner's 2026-10-17 ruling rather than a
  // loosened assertion: the base number belongs to the DEAL, so a create on a deal that already
  // holds documents continues the family. Demo ticket 18 carries seed quotations, AND this module's
  // mockDealQuotations is shared state that grows as the suite runs — so the exact suffix here is
  // both correct-as-N and order-dependent. The FORMAT is the contract this test names; the
  // continuation rule itself is pinned in mockApi.quotationDealLink.test.js against a fresh deal.
  it('produces QT-{year}-{4-digit seq}-{n}, matching nextNumberOnDeal, not a bare QT-{year}-{seq} or QD{BE-year}', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await createOn18({ items: [ONE_ITEM] });
    expect(quotation.number).toMatch(/^QT-\d{4}-\d{4}-\d+$/);
    expect(quotation.revisionNo).toBeGreaterThanOrEqual(1);
    // The number and the suffix agree — a row whose number says -3 while revisionNo says 1 is the
    // defect this pairing exists to catch.
    expect(quotation.number).toBe(`QT-${new Date().getFullYear()}-${
      quotation.number.split('-')[2]}-${quotation.revisionNo}`);
  });
});

// Suffixes in these tests are relative to the created row, not fixed at 1: per-deal families mean a
// create on seeded demo ticket 18 continues the family (owner ruling 2026-10-17).
describe('mock dealQuotations revision numbering -- #M10', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('a revision child is "{base}-{revisionNo}", and a grandchild strips the parent\'s own suffix rather than stacking it', async () => {
    await api.auth.login(salesUser);
    const created = await createOn18({ items: [ONE_ITEM] });
    const rootNumber = created.quotation.number;
    // Recover the bare base the same way DealQuotationRepository#baseNumber does — by stripping the
    // row's OWN suffix, not a hardcoded "-1". Since the 2026-10-17 ruling the base belongs to the
    // deal, so a create on demo ticket 18 (which carries seed quotations) continues the family and
    // the root here is "{base}-N" for some N. Deriving the base from revisionNo is what makes this
    // test about SUFFIX STACKING rather than about which number the root happened to get.
    const rootRevisionNo = created.quotation.revisionNo;
    const baseNumber = rootNumber.slice(0, -`-${rootRevisionNo}`.length);
    expect(rootNumber).toBe(`${baseNumber}-${rootRevisionNo}`);

    await api.dealQuotations.submit(created.quotation.id);
    await api.auth.login({ role: 'sales_manager' });
    const approved = await api.dealQuotations.approve(created.quotation.id, {});
    expect(approved.quotation.number).toBe(rootNumber);

    await api.auth.login(salesUser);
    const revision2 = await api.dealQuotations.createRevision(created.quotation.id, {});
    expect(revision2.quotation.number).toBe(`${baseNumber}-${rootRevisionNo + 1}`);
    expect(revision2.quotation.revisionNo).toBe(rootRevisionNo + 1);

    await api.dealQuotations.submit(revision2.quotation.id);
    await api.auth.login({ role: 'sales_manager' });
    await api.dealQuotations.approve(revision2.quotation.id, {});

    await api.auth.login(salesUser);
    const revision3 = await api.dealQuotations.createRevision(revision2.quotation.id, {});
    // The bug this guards: naively appending onto the SOURCE's own number (rather than
    // recovering the ORIGINAL base first) would produce "{root}-2-3" here.
    expect(revision3.quotation.number).toBe(`${baseNumber}-${rootRevisionNo + 2}`);
    expect(revision3.quotation.revisionNo).toBe(rootRevisionNo + 2);
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

// GLA-74 part 1 ("สร้างจากใบเดิม" / สั่งเหมือนเดิม) -- mirrors DealQuotationService#createReorder.
//
// ⚠️ Owner ruling 2026-09-19 (INVERTS this describe block's own former header comment, which said
// "the SOURCE stays APPROVED forever, unlike a revision's parent" -- that is now FALSE): a deal
// may hold only ONE live quotation at a time (origin-agnostic sweep since 2026-10-17). Clone CREATION itself still leaves
// the source untouched (still APPROVED) -- that half is unchanged -- but once the CLONE ITSELF is
// approved, DealQuotationService#approve's same-ticket sweep (mirrored here via
// DealQuotationRepository#supersedeOtherLiveOnTicket) supersedes the source, exactly like
// approving a revision or a second, wholly independent first-issue quotation would.
describe('mock dealQuotations.createReorder -- GLA-74 part 1', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('clones an APPROVED quotation into a new DRAFT, same numbering family, no parent link; source stays APPROVED while the clone is only DRAFT/PENDING_APPROVAL, then SUPERSEDED once the clone is approved', async () => {
    await api.auth.login(salesUser);
    const created = await createOn18({ items: [ONE_ITEM] });
    const rootNumber = created.quotation.number;
    const baseNumber = rootNumber.slice(0, -'-1'.length);
    await api.dealQuotations.submit(created.quotation.id);
    await api.auth.login({ role: 'sales_manager' });
    const approved = await api.dealQuotations.approve(created.quotation.id, {});

    await api.auth.login(salesUser);
    const { quotation: reorder } = await api.dealQuotations.createReorder(approved.quotation.id, {});

    expect(reorder.docStatus).toBe('DRAFT');
    // Next suffix after the SOURCE, derived rather than hardcoded — per-deal families mean the
    // source is not necessarily -1 (owner ruling 2026-10-17).
    expect(reorder.number).toBe(`${baseNumber}-${created.quotation.revisionNo + 1}`);
    expect(reorder.derivedFromQuotationId).toBe(approved.quotation.id);
    expect(reorder.parentQuotationId).toBeNull();
    expect(reorder.items).toHaveLength(approved.quotation.items.length);
    // Clone creation itself: source untouched, still APPROVED.
    expect((await api.dealQuotations.get(approved.quotation.id)).quotation.docStatus).toBe('APPROVED');

    await api.dealQuotations.submit(reorder.id);
    // Still APPROVED while the clone is only PENDING_APPROVAL -- not superseded until the clone
    // itself actually reaches APPROVED.
    expect((await api.dealQuotations.get(approved.quotation.id)).quotation.docStatus).toBe('APPROVED');

    // The moment of truth: approve the CLONE and confirm the source is NOW superseded.
    await api.auth.login({ role: 'sales_manager' });
    await api.dealQuotations.approve(reorder.id, {});
    const reloadedSource = await api.dealQuotations.get(approved.quotation.id);
    expect(reloadedSource.quotation.docStatus).toBe('SUPERSEDED');
  });

  /** Two wholly independent first-issue quotations on the SAME deal (no revision/clone lineage
   * at all) -- the case the pre-existing ancestor walk could never reach, since neither carries
   * the other's id anywhere. Approving the second must supersede the first.
   *
   * Slice 2's N6 (S2-B3) means `create` can no longer PRODUCE this pair — a second first-issue
   * create on a deal with a live direct quotation is a 409 now (mockApi.quotationDealLink.test.js).
   * The pair still exists as LEGACY data, and the demo seed carries exactly one: ids 1 (DRAFT) and 2
   * (PENDING_APPROVAL) on ticket 18. The sweep must still hold for it, so this now drives the seed
   * rows instead of two creates. */
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

  /** A revision of the ORIGINAL source is approved while a CLONE of that same source is still
   * DRAFT -- then the clone is approved too. Exactly one APPROVED row must remain (the clone,
   * being the LAST one approved); the revision must be SUPERSEDED. Pins that the sweep applies
   * uniformly regardless of WHICH lineage (ancestor-chain revision vs. derivedFrom clone) a
   * sibling belongs to. */
  it('a revision of the source approved while a clone is DRAFT, then the clone approved: exactly one APPROVED remains (the clone), the revision is SUPERSEDED', async () => {
    await api.auth.login(salesUser);
    const created = await createOn18({ items: [ONE_ITEM] });
    await api.dealQuotations.submit(created.quotation.id);
    await api.auth.login({ role: 'sales_manager' });
    const source = await api.dealQuotations.approve(created.quotation.id, {});

    await api.auth.login(salesUser);
    const { quotation: clone } = await api.dealQuotations.createReorder(source.quotation.id, {});
    const { quotation: revision } = await api.dealQuotations.createRevision(source.quotation.id, {});
    // Clone stays DRAFT throughout this test -- never submitted/approved until the very end.
    expect(clone.docStatus).toBe('DRAFT');

    await api.dealQuotations.submit(revision.id);
    await api.auth.login({ role: 'sales_manager' });
    const approvedRevision = await api.dealQuotations.approve(revision.id, {});
    expect(approvedRevision.quotation.docStatus).toBe('APPROVED');
    // The revision's approval supersedes the ORIGINAL source (its own parent)...
    expect((await api.dealQuotations.get(source.quotation.id)).quotation.docStatus).toBe('SUPERSEDED');
    // ...but the clone (unrelated lineage, still DRAFT) is untouched.
    expect((await api.dealQuotations.get(clone.id)).quotation.docStatus).toBe('DRAFT');

    await api.auth.login(salesUser);
    await api.dealQuotations.submit(clone.id);
    await api.auth.login({ role: 'sales_manager' });
    const approvedClone = await api.dealQuotations.approve(clone.id, {});
    expect(approvedClone.quotation.docStatus).toBe('APPROVED');

    // Exactly ONE APPROVED row remains on this ticket -- the clone, the LAST one approved.
    const all = (await api.dealQuotations.listForTicket(18)).items
      .filter((q) => [created.quotation.id, source.quotation.id, revision.id, clone.id].includes(q.id));
    const approvedCount = all.filter((q) => q.docStatus === 'APPROVED').length;
    expect(approvedCount).toBe(1);
    expect((await api.dealQuotations.get(clone.id)).quotation.docStatus).toBe('APPROVED');
    expect((await api.dealQuotations.get(revision.id)).quotation.docStatus).toBe('SUPERSEDED');
  });

  /** A quotation on a DIFFERENT ticket must never be touched by this ticket's sweep -- the sweep
   * is scoped to `ticketId`, not global. */
  it('a quotation on a DIFFERENT deal is untouched by this deal\'s approval sweep', async () => {
    await api.auth.login(salesUser);
    const { customer } = await api.customers.create({
      name: 'บริษัท ดีลอื่น ทดสอบ จำกัด', taxId: '0100000099', address: '99 ถนนทดสอบ',
      branch: 'สาขาทดสอบ', phone: '02-999-9999',
    });
    const { project } = await api.customers.createProject(customer.id, { name: 'โครงการดีลอื่น' });
    const { contact } = await api.customers.createContact(customer.id, { name: 'ผู้สั่งซื้อ ทดสอบดีลอื่น' });
    const { ticket: otherTicket } = await api.tickets.create({
      entryChannel: 'DESIGNER_LED',
      title: 'ดีลอื่น ทดสอบ GLA-74', priority: 'NORMAL', customerName: customer.name,
      customerId: customer.id, projectId: project.id, contactId: contact.id,
      items: [{ brand: 'SCG', model: 'Tile Mock', qty: 10, currency: 'THB' }],
    });
    const otherTicketId = otherTicket.summary.id;

    const onOtherDeal = await api.dealQuotations.create(otherTicketId, { recipientType: 'DESIGNER', items: [ONE_ITEM] });
    await api.dealQuotations.submit(onOtherDeal.quotation.id);
    await api.auth.login({ role: 'sales_manager' });
    const approvedOnOtherDeal = await api.dealQuotations.approve(onOtherDeal.quotation.id, {});
    expect(approvedOnOtherDeal.quotation.docStatus).toBe('APPROVED');

    // Now approve an UNRELATED quotation on ticket 18 -- must not touch the other deal at all.
    await api.auth.login(salesUser);
    const onTicket18 = await createOn18({ items: [ONE_ITEM] });
    await api.dealQuotations.submit(onTicket18.quotation.id);
    await api.auth.login({ role: 'sales_manager' });
    await api.dealQuotations.approve(onTicket18.quotation.id, {});

    expect((await api.dealQuotations.get(onOtherDeal.quotation.id)).quotation.docStatus).toBe('APPROVED');
  });

  it('refuses (409) to clone a non-APPROVED source', async () => {
    await api.auth.login(salesUser);
    const created = await createOn18({ items: [ONE_ITEM] });
    await expect(api.dealQuotations.createReorder(created.quotation.id, {}))
      .rejects.toMatchObject({ status: 409 });
  });

  it('allows multiple clones of the same APPROVED source (unlike createRevision, no open-child guard)', async () => {
    await api.auth.login(salesUser);
    const created = await createOn18({ items: [ONE_ITEM] });
    await api.dealQuotations.submit(created.quotation.id);
    await api.auth.login({ role: 'sales_manager' });
    const approved = await api.dealQuotations.approve(created.quotation.id, {});

    await api.auth.login(salesUser);
    const first = await api.dealQuotations.createReorder(approved.quotation.id, {});
    const second = await api.dealQuotations.createReorder(approved.quotation.id, {});
    expect(first.quotation.number).not.toBe(second.quotation.number);
    expect(first.quotation.derivedFromQuotationId).toBe(approved.quotation.id);
    expect(second.quotation.derivedFromQuotationId).toBe(approved.quotation.id);
  });

  // Regression (Opus review): mintDealQuotationRevision used to spread `...parent` and never
  // clear derivedFromQuotationId, so a REVISION of a reorder CLONE kept the clone's own
  // derivedFromQuotationId -- both ids set on the same row, contradicting the mutual-exclusivity
  // buildDealQuotationDto's own comment asserts. Mirrors DealQuotationService#insertRevisionCopyOf
  // -> #insertCopyOf(source, actor, source.id(), null): a revision NEVER carries derivedFrom,
  // even when its own source is itself a clone.
  it('a revision of an APPROVED reorder clone links via parentQuotationId only, never derivedFromQuotationId', async () => {
    await api.auth.login(salesUser);
    const created = await createOn18({ items: [ONE_ITEM] });
    await api.dealQuotations.submit(created.quotation.id);
    await api.auth.login({ role: 'sales_manager' });
    const approved = await api.dealQuotations.approve(created.quotation.id, {});

    await api.auth.login(salesUser);
    const { quotation: clone } = await api.dealQuotations.createReorder(approved.quotation.id, {});
    await api.dealQuotations.submit(clone.id);
    await api.auth.login({ role: 'sales_manager' });
    const approvedClone = await api.dealQuotations.approve(clone.id, {});

    await api.auth.login(salesUser);
    const { quotation: revisionOfClone } = await api.dealQuotations.createRevision(approvedClone.quotation.id, {});

    expect(revisionOfClone.parentQuotationId).toBe(approvedClone.quotation.id);
    expect(revisionOfClone.derivedFromQuotationId).toBeNull();
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

  it('a canCreateQuotation-granted employee sees ONLY quotations on deals they created on the global list (owner ruling 2026-09-24: the grant no longer widens LIST scope)', async () => {
    await api.auth.login(grantedEmployee);
    const { items } = await api.dealQuotations.list();
    // The grant-holder created none of the seeded ticket-18 rows, so the global list is empty
    // for them -- the grant still lets them create on / get an individual deal (next two tests),
    // it just no longer shows EVERY deal here. Mirrors DealQuotationService.listOwnerScope and
    // DealQuotationIntegrationTest#grantHolder_search_seesOnlyOwnDeals_butCanStillGetAnotherRepsQuotationIndividually.
    expect(items).toHaveLength(0);
  });

  it('a canCreateQuotation-granted employee may create on a deal they do not own', async () => {
    await api.auth.login(grantedEmployee);
    const { quotation } = await createOn18({ items: [ONE_ITEM] });
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
    // The seed holds no APPROVED row, so without one the อนุมัติแล้ว pair below would agree on 0.
    const { quotation: toApprove } = await createOn18({ items: [ONE_ITEM] });
    await api.dealQuotations.submit(toApprove.id);
    await api.auth.login({ role: 'sales_manager' });
    await api.dealQuotations.approve(toApprove.id, {});
    await api.auth.login(salesUser);
    const counts = await api.dealQuotations.counts();

    const all = await api.dealQuotations.list();
    const pending = await api.dealQuotations.list({ status: 'PENDING_APPROVAL' });
    const cancelled = await api.dealQuotations.list({ status: 'CANCELLED' });
    // QA-37: the อนุมัติแล้ว tab is approved-and-beyond, sent as a status ARRAY.
    const approved = await api.dealQuotations.list({ status: ['APPROVED', 'ISSUED', 'SENT', 'ACCEPTED'] });
    const rework = await api.dealQuotations.list({ needsRework: true });

    // The failure this guards: a count computed over a LOOSER set than the list it labels, so a
    // tab promises rows the list then refuses to show.
    expect(counts.all).toBe(all.items.length);
    expect(counts.pendingApproval).toBe(pending.items.length);
    expect(counts.cancelled).toBe(cancelled.items.length);
    expect(counts.approved).toBeGreaterThan(0);
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
    const created = await createOn18({ items: [ONE_ITEM] });
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
    const created = await createOn18({ items: [ONE_ITEM] });
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
    const created = await createOn18({ items: [ONE_ITEM] });
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
    const created = await createOn18({ items: [ONE_ITEM] });
    const base = created.quotation.number.replace(/-\d+$/, '');
    const n = created.quotation.revisionNo;

    await api.dealQuotations.submit(created.quotation.id);
    await api.auth.login({ role: 'sales_manager' });
    await api.dealQuotations.reject(created.quotation.id, { reason: 'รอบ 1' });

    await api.auth.login(salesUser);
    const { quotation: revision2 } = await api.dealQuotations.submit(created.quotation.id);
    expect(revision2.number).toBe(`${base}-${n + 1}`);

    await api.auth.login({ role: 'sales_manager' });
    await api.dealQuotations.reject(revision2.id, { reason: 'รอบ 2' });

    await api.auth.login(salesUser);
    const { quotation: revision3 } = await api.dealQuotations.submit(revision2.id);
    expect(revision3.number).toBe(`${base}-${n + 2}`);
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

    // THE FIX: B cannot be cancelled out from under its own open child.
    await expect(api.dealQuotations.cancel(revisionB.id, {})).rejects.toMatchObject({ status: 409 });
  });

  it('cancel still works on a plain draft with no open child (regression guard)', async () => {
    await api.auth.login(salesUser);
    const created = await createOn18({ items: [ONE_ITEM] });
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
    const created = await createOn18({ items: [ONE_ITEM] });
    const base = created.quotation.number.replace(/-\d+$/, '');
    const n = created.quotation.revisionNo;
    const originalId = created.quotation.id;

    await api.dealQuotations.submit(originalId);
    await api.auth.login({ role: 'sales_manager' });
    await api.dealQuotations.reject(originalId, { reason: 'รอบ 1' });

    await api.auth.login(salesUser);
    const { quotation: revision2 } = await api.dealQuotations.submit(originalId);
    expect(revision2.number).toBe(`${base}-${n + 1}`);

    await api.auth.login({ role: 'sales_manager' });
    await api.dealQuotations.reject(revision2.id, { reason: 'รอบ 2' });

    // revision2 is DRAFT again -- cancel it instead of resubmitting it, freeing the original
    // (originalId) to be resubmitted a SECOND time (hasOpenRevision no longer sees an open child).
    await api.auth.login(salesUser);
    await api.dealQuotations.cancel(revision2.id, {});

    // Must not collide with revision2's already-used -2.
    const { quotation: revision3 } = await api.dealQuotations.submit(originalId);
    expect(revision3.number).toBe(`${base}-${n + 2}`);
    expect(revision3.number).not.toBe(revision2.number);
  });

  it('needsRework=true also selects a DRAFT revision in progress (the owner\'s second sense of แก้)', async () => {
    await api.auth.login(salesUser);
    const created = await createOn18({ items: [ONE_ITEM] });
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
  // Ruling 2026-10-18: the ผู้ติดต่อ is picked per quotation -- request contactId, else the row's
  // own, else none; the deal's contact is NOT a fallback -- and the ผู้สั่งซื้อ signature name
  // (orderedByName) is that contact's name, written on every DRAFT save; a name typed into the
  // request is ignored. Mirrors DealQuotationService#resolveContact and its callers.
  const TYPED = 'ชื่อที่พิมพ์เอง';

  // Mirrors DealQuotationIntegrationTest#create_withNoContactId_doesNotInheritTheDealsContact.
  it('does NOT inherit the deal\'s own contact: no contactId sent = a blank snapshot and no signature name', async () => {
    await api.auth.login(salesUser);
    const { ticket } = await api.tickets.get(18);
    expect(ticket.summary.contactId).toBe(6); // the deal HAS a contact that could be inherited

    const { quotation } = await createOn18({ orderedByName: TYPED, items: [ONE_ITEM] });
    expect(quotation).toMatchObject({
      contactId: null, contactName: null, contactPhone: null, contactEmail: null, orderedByName: null,
    });
  });

  // Mirrors DealQuotationIntegrationTest#create_withExplicitContact_usesIt_andRefusesAnotherCustomersContact.
  it('a picked contactId FREEZES name/phone/email onto the row, and its name is the signature name', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await createOn18({ contactId: 7, orderedByName: TYPED, items: [ONE_ITEM] });
    expect(quotation).toMatchObject({
      contactId: 7, contactName: 'พิมพ์ใจ บุญมาก', contactPhone: '086-444-5555',
      contactEmail: 'pimjai@fashionisland.co.th', orderedByName: 'พิมพ์ใจ บุญมาก',
    });
  });

  // Wrong-way-round: a contact belonging to a DIFFERENT customer must be refused, not silently
  // snapshotted onto this customer's document.
  it('refuses a contact that does not belong to the deal\'s customer', async () => {
    await api.auth.login(salesUser);
    await expect(createOn18({ contactId: 1, items: [ONE_ITEM] }))
      .rejects.toThrow('ผู้สั่งซื้อไม่ได้อยู่ในสังกัดลูกค้ารายนี้');
  });

  it('refuses an unknown contactId with the same Thai message the UI shows', async () => {
    await api.auth.login(salesUser);
    await expect(createOn18({ contactId: 99999, items: [ONE_ITEM] }))
      .rejects.toThrow('กรุณาระบุผู้สั่งซื้อ');
  });

  // Mirrors DealQuotationIntegrationTest#update_keepsTheDraftsContactWhenOmitted_andSwitchesOnAnExplicitId.
  // ล้าง (ruling 2026-10-18): a picked ผู้ติดต่อ may be cleared per quotation -- clearContact on
  // update blanks the snapshot whatever contactId says; create ignores the flag.
  it('update() re-snapshots when the rep changes ผู้ติดต่อ, the signature name follows, and clearContact blanks both', async () => {
    await api.auth.login(salesUser);
    const created = await createOn18({ contactId: 6, clearContact: true, items: [ONE_ITEM] });
    expect(created.quotation).toMatchObject({ contactId: 6, orderedByName: 'ณัฐพงศ์ ศรีวิไล' });

    const { quotation } = await api.dealQuotations.update(created.quotation.id, {
      contactId: 7, orderedByName: TYPED, items: [ONE_ITEM],
    });
    expect(quotation).toMatchObject({ contactId: 7, contactName: 'พิมพ์ใจ บุญมาก', orderedByName: 'พิมพ์ใจ บุญมาก' });

    const { quotation: cleared } = await api.dealQuotations.update(created.quotation.id, {
      contactId: null, clearContact: true, orderedByName: TYPED, items: [ONE_ITEM],
    });
    expect(cleared).toMatchObject({
      contactId: null, contactName: null, contactPhone: null, contactEmail: null, orderedByName: null,
    });
  });

  // The signature name is written by a DRAFT save, never derived on read, so a row nobody re-saves
  // keeps serving what it stores: the seed's PENDING_APPROVAL row 2 carries contact 6 and no stored
  // signature name. The seed's DRAFT row 1 (same contact, same empty name) takes the contact's name
  // on its next save, which sends no contactId -- the row's own contact is kept.
  it('a non-DRAFT row serves the signature name it stores; a DRAFT\'s next save writes its contact\'s name', async () => {
    await api.auth.login(salesUser);
    const { quotation: pending } = await api.dealQuotations.get(2);
    expect(pending).toMatchObject({ docStatus: 'PENDING_APPROVAL', contactName: 'ณัฐพงศ์ ศรีวิไล', orderedByName: null });

    const { quotation: draft } = await api.dealQuotations.get(1);
    expect(draft).toMatchObject({ docStatus: 'DRAFT', contactId: 6, orderedByName: null });
    const { quotation: saved } = await api.dealQuotations.update(1, { orderedByName: TYPED, items: [ONE_ITEM] });
    expect(saved).toMatchObject({ contactId: 6, orderedByName: 'ณัฐพงศ์ ศรีวิไล' });
  });

  // ⚠️ Owner-directed reversal of V167/F2 (2026-09-26): create/update/submit used to REFUSE a deal
  // with no resolvable contact at all ("กรุณาระบุผู้สั่งซื้อ"). A contact is optional, so this
  // proves the OPPOSITE: a deal ticket with no contact of its own, and no contactId ever sent on
  // the wire, creates/updates/submits successfully with a blank contact snapshot throughout -- and
  // with no signature name, a typed one ignored. Mirrors
  // DealQuotationIntegrationTest#submit_succeedsOnARowWithNoContactSnapshot and
  // DealQuotationOrderedByContactIntegrationTest#update_replacesBoth on the Java side.
  it('create/update/submit all succeed with a blank contact snapshot when the deal has no ผู้สั่งซื้อ at all', async () => {
    await api.auth.login(salesUser);
    const { customer } = await api.customers.create({
      name: 'บริษัท ไม่มีผู้สั่งซื้อ ทดสอบ จำกัด', taxId: '0100000098', address: '1 ถนนทดสอบ',
      branch: 'สาขาทดสอบ', phone: '02-888-8888',
    });
    const { project } = await api.customers.createProject(customer.id, { name: 'โครงการไม่มีผู้สั่งซื้อ' });
    const { ticket: noContactTicket } = await api.tickets.create({
      entryChannel: 'DESIGNER_LED',
      title: 'ดีลไม่มีผู้สั่งซื้อ ทดสอบ', priority: 'NORMAL', customerName: customer.name,
      customerId: customer.id, projectId: project.id,
      items: [{ brand: 'SCG', model: 'Tile Mock', qty: 10, currency: 'THB' }],
    });
    const noContactTicketId = noContactTicket.summary.id;

    const created = await api.dealQuotations.create(noContactTicketId, { recipientType: 'DESIGNER', items: [ONE_ITEM] });
    expect(created.quotation.contactId).toBeNull();
    expect(created.quotation.contactName).toBeNull();

    const updated = await api.dealQuotations.update(created.quotation.id, { orderedByName: TYPED, items: [ONE_ITEM] });
    expect(updated.quotation.contactId).toBeNull();
    expect(updated.quotation.orderedByName).toBeNull();

    const submitted = await api.dealQuotations.submit(created.quotation.id);
    expect(submitted.quotation.contactId).toBeNull();
    expect(submitted.quotation.docStatus).toBe('PENDING_APPROVAL');
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
    await expect(createOn18({
      priceMode: 'DIRECT_NET', validityMode: 'DATE', validityUntil: '2099-01-01',
      items: [{ ...ONE_ITEM, directNetPrice: 850 }], // net == list -- no special pricing
    })).rejects.toMatchObject({ status: 400, message: expect.stringContaining('ระบุวันที่ยืนราคาได้เฉพาะ') });
  });

  it('DATE mode with special pricing but no date is refused', async () => {
    await api.auth.login(salesUser);
    await expect(createOn18({
      priceMode: 'DIRECT_NET', validityMode: 'DATE', items: [DISCOUNTED_DIRECT_NET_ITEM],
    })).rejects.toMatchObject({ status: 400, message: expect.stringContaining('กรุณาระบุวันที่ยืนราคา') });
  });

  it('DATE before the quotation\'s own date is refused', async () => {
    pinToday('2026-09-14');
    await api.auth.login(salesUser);
    await expect(createOn18({
      priceMode: 'DIRECT_NET', validityMode: 'DATE', validityUntil: '2026-09-13',
      items: [DISCOUNTED_DIRECT_NET_ITEM],
    })).rejects.toMatchObject({ status: 400, message: expect.stringContaining('ต้องไม่ก่อนวันที่ใบเสนอราคา') });
  });

  it('round-trips validityMode/validityUntil through create -> get', async () => {
    pinToday('2026-09-14');
    await api.auth.login(salesUser);
    const { quotation: created } = await createOn18({
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
    const { quotation: created } = await createOn18({
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
    const { quotation: created } = await createOn18({
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
    const { quotation: created } = await createOn18({
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
    const { quotation: created } = await createOn18({
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
    const { quotation: created } = await createOn18({ validityDays: 30, items: [ONE_ITEM] });
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
    const { quotation } = await createOn18({
      printedByDisplayId: 4, salesRepDisplayId: 9, items: [ONE_ITEM],
    });
    expect(quotation.printedByDisplayId).toBe(4);
    expect(quotation.salesRepDisplayId).toBe(9);
    expect(quotation.printedByDisplayName).toBeTruthy();
    expect(quotation.salesRepDisplayName).toBeTruthy();
  });

  it('an id outside the eligible union is refused (400) on create', async () => {
    await api.auth.login(salesUser);
    await expect(createOn18({ printedByDisplayId: 7, items: [ONE_ITEM] }))
      .rejects.toMatchObject({ status: 400 });
  });

  it('update accepts null to clear a previously-set display override', async () => {
    await api.auth.login(salesUser);
    const { quotation: created } = await createOn18({
      printedByDisplayId: 4, items: [ONE_ITEM],
    });
    expect(created.printedByDisplayId).toBe(4);
    const { quotation: cleared } = await api.dealQuotations.update(created.id, { items: [ONE_ITEM] });
    expect(cleared.printedByDisplayId).toBeNull();
  });

  it('update refuses an ineligible id too (400)', async () => {
    await api.auth.login(salesUser);
    const { quotation: created } = await createOn18({ items: [ONE_ITEM] });
    await expect(api.dealQuotations.update(created.id, { salesRepDisplayId: 7, items: [ONE_ITEM] }))
      .rejects.toMatchObject({ status: 400 });
  });

  // Wrong-way-round: the display override must never touch the real ownership fields.
  it('setting salesRepDisplayId does not change the real salesRepId/createdById', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await createOn18({
      salesRepDisplayId: 9, items: [ONE_ITEM],
    });
    expect(quotation.salesRepId).toBe(6); // ticket 18's real owner, unchanged
    expect(quotation.salesRepDisplayId).toBe(9);
  });

  it('createRevision copies both display ids verbatim', async () => {
    await api.auth.login(salesUser);
    const { quotation: created } = await createOn18({
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
    const { quotation } = await createOn18({
      projectName: 'โครงการทดสอบ A', items: [ONE_ITEM],
    });
    expect(quotation.projectName).toBe('โครงการทดสอบ A');
  });

  it('update corrects a typo in the project name, and it round-trips on read', async () => {
    await api.auth.login(salesUser);
    const { quotation: created } = await createOn18({
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
    const { quotation: created } = await createOn18({
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
    const { quotation: created } = await createOn18({
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
    const { quotation } = await createOn18({ items: [ONE_ITEM] });
    expect(quotation.omitContactHonorific).toBe(false);
  });

  it('true round-trips through create -> get', async () => {
    await api.auth.login(salesUser);
    const { quotation: created } = await createOn18({
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
    const { quotation: created } = await createOn18({
      omitContactHonorific: true, items: [ONE_ITEM],
    });
    const { quotation: updated } = await api.dealQuotations.update(created.id, { items: [ONE_ITEM] });
    expect(updated.omitContactHonorific).toBe(false);
  });

  it('createRevision copies it verbatim', async () => {
    await api.auth.login(salesUser);
    const { quotation: created } = await createOn18({
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

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Sales feedback #5 (2026-10-06, V192.1) — "ไม่แสดงชื่อโครงการในใบเสนอราคา". Mirrors
// DealQuotationService#create/#update/#insertCopyOf's handling of hideProjectName. This is
// PLUMBING evidence only (the field survives the mock's own create/update/clone paths); that the
// printed document really drops the line is proven against the Java renderer, not here.
// ─────────────────────────────────────────────────────────────────────────────────────────────
describe('mock dealQuotations.create/update -- hideProjectName (V192.1)', () => {
  it('defaults false when the request omits it', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await createOn18({ items: [ONE_ITEM] });
    expect(quotation.hideProjectName).toBe(false);
  });

  it('true round-trips through create -> get, and the project name itself is kept', async () => {
    await api.auth.login(salesUser);
    const { quotation: created } = await createOn18({
      projectName: 'บ้านเอกมัย ซอย 5', hideProjectName: true, items: [ONE_ITEM],
    });
    expect(created.hideProjectName).toBe(true);
    const { quotation: reread } = await api.dealQuotations.get(created.id);
    expect(reread.hideProjectName).toBe(true);
    expect(reread.projectName).toBe('บ้านเอกมัย ซอย 5');
  });

  // DELIBERATELY the opposite of omitContactHonorific just above ("an update that omits it clears
  // it"). A browser tab still running the bundle from before this field existed sends no
  // hideProjectName at all; treating that as false would silently start PRINTING a project name a
  // rep had chosen to hide. Missing means "say nothing", so the stored choice stands.
  it('an update that omits it KEEPS a previously-set true', async () => {
    await api.auth.login(salesUser);
    const { quotation: created } = await createOn18({ hideProjectName: true, items: [ONE_ITEM] });
    const { quotation: updated } = await api.dealQuotations.update(created.id, { items: [ONE_ITEM] });
    expect(updated.hideProjectName).toBe(true);
  });

  it('an explicit false on update clears it', async () => {
    await api.auth.login(salesUser);
    const { quotation: created } = await createOn18({ hideProjectName: true, items: [ONE_ITEM] });
    const { quotation: updated } = await api.dealQuotations.update(created.id, {
      hideProjectName: false, items: [ONE_ITEM],
    });
    expect(updated.hideProjectName).toBe(false);
  });

  it('an explicit true on update sets it on a document that printed its project before', async () => {
    await api.auth.login(salesUser);
    const { quotation: created } = await createOn18({ items: [ONE_ITEM] });
    const { quotation: updated } = await api.dealQuotations.update(created.id, {
      hideProjectName: true, items: [ONE_ITEM],
    });
    expect(updated.hideProjectName).toBe(true);
  });

  it('createRevision and createReorder both copy it — a re-issue must not un-hide the project', async () => {
    await api.auth.login(salesUser);
    const { quotation: created } = await createOn18({ hideProjectName: true, items: [ONE_ITEM] });
    await api.dealQuotations.submit(created.id);
    await api.auth.login({ role: 'sales_manager' });
    const { quotation: approved } = await api.dealQuotations.approve(created.id, {});
    await api.auth.login(salesUser);
    const { quotation: reorder } = await api.dealQuotations.createReorder(approved.id, {});
    expect(reorder.hideProjectName).toBe(true);
    const { quotation: revision } = await api.dealQuotations.createRevision(approved.id);
    expect(revision.hideProjectName).toBe(true);
  });
});

describe('mock dealQuotations.create/update -- fullPaymentTerm (V181, "ไม่รับมัดจำ")', () => {
  it('depositPercent 0 clears remainderMode/creditDays and stores the chosen term', async () => {
    await api.auth.login(salesUser);
    const { quotation } = await createOn18({
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
    const { quotation } = await createOn18({
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
    const { quotation } = await createOn18({
      fullPaymentTerm: 'ON_DELIVERY', items: [ONE_ITEM],
    });
    expect(quotation.depositPercent).toBeNull();
    expect(quotation.fullPaymentTerm).toBeNull();
  });

  it('an unrecognised code is refused (400)', async () => {
    await api.auth.login(salesUser);
    await expect(createOn18({
      depositPercent: 0, fullPaymentTerm: 'SOMETHING_ELSE', items: [ONE_ITEM],
    })).rejects.toMatchObject({ status: 400 });
  });

  it('update switching TO depositPercent 0 clears remainderMode/creditDays and stores the term', async () => {
    await api.auth.login(salesUser);
    const { quotation: created } = await createOn18({
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
    const { quotation: created } = await createOn18({
      depositPercent: 0, items: [ONE_ITEM],
    });
    await expect(api.dealQuotations.submit(created.id)).rejects
      .toMatchObject({ status: 400, message: expect.stringContaining('เงื่อนไขการชำระเงิน') });
  });

  it('submit succeeds once a term is chosen', async () => {
    await api.auth.login(salesUser);
    const { quotation: created } = await createOn18({
      depositPercent: 0, fullPaymentTerm: 'ON_OR_BEFORE_DELIVERY', items: [ONE_ITEM],
    });
    const { quotation: submitted } = await api.dealQuotations.submit(created.id);
    expect(submitted.docStatus).toBe('PENDING_APPROVAL');
    expect(submitted.fullPaymentTerm).toBe('ON_OR_BEFORE_DELIVERY');
  });

  // A non-zero-deposit document is never blocked by the new submit gate.
  it('submit is unaffected by the new gate on an ordinary non-zero deposit', async () => {
    await api.auth.login(salesUser);
    const { quotation: created } = await createOn18({ items: [ONE_ITEM] });
    const { quotation: submitted } = await api.dealQuotations.submit(created.id);
    expect(submitted.docStatus).toBe('PENDING_APPROVAL');
  });

  it('createRevision copies fullPaymentTerm verbatim', async () => {
    await api.auth.login(salesUser);
    const { quotation: created } = await createOn18({
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
