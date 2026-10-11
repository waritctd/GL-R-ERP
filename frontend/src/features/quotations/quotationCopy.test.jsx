import { describe, expect, it, vi } from 'vitest';
import { copyFromQuotation } from './quotationCopy.js';
import { canCopyDealQuotation, rowPriceMode } from './quotationMeta.js';
import { adjustmentInputFromRow, itemInputFromRow, rowFromServerItem } from './QuotationItemRow.jsx';
import { bangkokTodayIso } from '../../utils/format.js';

vi.mock('../../api/index.js', () => ({ api: { catalog: { prices: vi.fn() } } }));

// "คัดลอกเป็นใบใหม่" (owner: "a duplicate button for the quotation (but its a new one) in case they
// want the exactly same details just change the customer"). Several contractors bid on one project;
// today the rep retypes the whole quotation to change the customer and the person to call.
//
// copyFromQuotation is the ONE place that decides what the new draft starts with. This file is that
// decision written as a table, three ways:
//   - COPIED: the rows and everything about how the document is priced and termed;
//   - LEFT FOR THE REP: the customer, and ผู้ติดต่อ with its Phone and Email;
//   - NEVER: anything that identifies the SOURCE — its id, its rows' ids, its number, its status,
//     its approval, its customer, its pictures.
//
// The save itself (a new deal, then a new quotation on it) is the editor's, and is pinned in
// QuotationEditorPage.copyFrom.test.jsx.

function deepFreeze(value) {
  if (value && typeof value === 'object') {
    Object.values(value).forEach(deepFreeze);
    Object.freeze(value);
  }
  return value;
}

// A COMPLETE stored tile row (the owner's 60×120), priced per ตร.ม. including VAT.
const TILE = {
  id: 5550001, seq: 1, lineType: 'TILE', locationLabel: 'ชั้น 5 โซน A', catalogPriceId: 321, productCode: 'PN-TRI-60120',
  brand: 'Panaria', model: 'Trilogy', color: 'Ivory', texture: 'Lappato', sizeText: '60x120', thicknessMm: 10,
  sqmPerPiece: 0.72, quantityMode: 'PIECES', areaSqm: null, piecesInput: 10, wastageMode: 'NONE',
  wastageValue: null, piecesPerBox: 2, unitPrice: 1210.25, discountPct: 20, originCountry: 'อิตาลี',
  leadTimeMinDays: 75, leadTimeMaxDays: 90, itemNotes: 'ตัดขอบ 4 ด้าน',
  piecesPerSqm: 1.39, piecesBeforeWastage: 10, piecesAfterWastage: 10, piecesFinal: 10, boxes: 5,
  netUnitPrice: 968.2, lineAmount: 9682, descriptionLine: 'Trilogy Ivory', sizeLine: '60x120', calculationLine: '(calc)',
  quantity: 10, unit: 'แผ่น', specialPriceSqm: null, adjustmentPct: null, adjustmentDeadline: null,
  specialPriceLine: null, adjustmentAmount: null, listPriceSqm: 1800, directNetPriceSqm: null, priceMode: 'NET',
  hasPicture: true, picturePlacement: 'BELOW', pictureUrl: '/api/deal-quotations/918273/items/5550001/picture',
  sqmPerBox: null, roundToFullBox: true,
};
const SPECIAL_TILE = {
  ...TILE, id: 5550002, seq: 2, model: 'Urbanature', discountPct: null, specialPriceSqm: 1350, itemNotes: null,
  netUnitPrice: 907.66, lineAmount: 9076.6, specialPriceLine: 'ราคาพิเศษ 1,350 บาท/ตร.ม.', priceMode: 'SPECIAL_SQM',
  hasPicture: false, picturePlacement: null, pictureUrl: null,
};
const FREIGHT = {
  id: 5550003, seq: 3, lineType: 'PLAIN', locationLabel: 'ชั้น 5 โซน B', descriptionLine: 'ค่าขนส่ง', quantity: 2,
  unit: 'งาน', unitPrice: 500, discountPct: 0, netUnitPrice: 500, lineAmount: 1000, piecesFinal: 0,
  leadTimeMinDays: null, leadTimeMaxDays: null, itemNotes: 'ส่งหน้างาน', priceMode: null,
  hasPicture: false, picturePlacement: null, pictureUrl: null,
};
const EARLY_ORDER_DISCOUNT = {
  id: 5550004, seq: 4, lineType: 'ADJUSTMENT', adjustmentPct: 3, adjustmentAmount: null, adjustmentDeadline: '2026-11-30',
  unitPrice: 592.76, netUnitPrice: 592.76, lineAmount: -592.76, quantity: -1, unit: null,
  descriptionLine: 'ส่วนลดพิเศษ 3% เมื่อสั่งซื้อภายใน 30 พ.ย. 2569', piecesFinal: 0, priceMode: null,
};

// Every identifying value below is distinctive ON PURPOSE: the "nothing of the source survives"
// test looks for each of them in the copied state, so none may collide with a price or a quantity.
const SOURCE = deepFreeze({
  id: 918273, number: 'QT-2026-0777-1', ticketId: 827364, docStatus: 'APPROVED', revisionNo: 1,
  parentQuotationId: null, derivedFromQuotationId: null, derivedFromQuotationNumber: null,
  origin: 'DEAL_DIRECT', pricingRequestId: null,
  createdById: 31, createdByName: 'แอดมิน ผู้พิมพ์', salesRepId: 6, salesRepName: 'คุณสมหมาย ขายดี', salesRepPhone: '081-000-0000',
  submittedAt: '2026-09-21T03:00:00Z', approvedById: 9, approvedByName: 'ผู้จัดการ อนุมัติเอง',
  approvedAt: '2026-09-22T03:00:00Z', approvalNote: 'อนุมัติตามเสนอ', approverHasSignature: true,
  quotationDate: '2026-09-20',
  customerName: 'บริษัท ผู้รับเหมา เอ จำกัด', customerAddress: '1 ถนนเดิม เขตเดิม', customerTaxId: '0105550000001',
  customerPhone: '02-111-1111',
  contactId: 6660001, contactName: 'สมชาย ลูกค้าเดิม', contactPhone: '081-999-0000', contactEmail: 'old.contact@contractor-a.example',
  orderedByName: 'คุณวิชัย มั่นคง', orderedByPhone: '089-765-4321', orderedByEmail: 'wichai@contractor-a.example',
  projectName: 'คอนโด ริเวอร์ไซด์ ชั้น 5', hideProjectName: true,
  deptCode: 'P003', unitCode: 'A001', offerDate: '2026-09-18',
  depositPercent: 45, remainderMode: 'CREDIT', creditDays: 30, fullPaymentTerm: null,
  validityDays: 30, validityDate: '2026-10-20', validityMode: 'DATE', validityUntil: '2026-11-30',
  customerNotes: 'ส่งของภายใน 30 วันหลังยืนยัน',
  priceMode: 'MIXED', documentLanguage: 'TH', currency: 'THB',
  subtotalAmount: 19165.84, vatAmount: 1341.61, grandTotal: 20507.45,
  printedByDisplayId: 4, printedByDisplayName: 'พนักงาน ได้สิทธิ์', printedByDisplayNameEn: null,
  salesRepDisplayId: 9, salesRepDisplayName: 'ผู้จัดการ ฝ่ายขาย', salesRepDisplayNameEn: null, salesRepDisplayPhone: '081-222-3333',
  omitContactHonorific: false,
  items: [TILE, SPECIAL_TILE, FREIGHT, EARLY_ORDER_DISCOUNT],
  createdAt: '2026-09-20T02:00:00Z', updatedAt: '2026-09-22T03:00:00Z',
});

const source = (overrides = {}) => deepFreeze({ ...SOURCE, ...overrides });

describe('copyFromQuotation — what the new draft starts with', () => {
  it('copies how the document is written: its language and its price mode (the currency follows the language)', () => {
    expect(copyFromQuotation(SOURCE).docSettings).toEqual({ priceMode: 'MIXED', documentLanguage: 'TH' });
    expect(copyFromQuotation(source({ priceMode: 'DIRECT_NET', documentLanguage: 'EN', currency: 'USD' })).docSettings)
      .toEqual({ priceMode: 'DIRECT_NET', documentLanguage: 'EN' });
  });

  // THE TABLE. One row per thing the editor's เงื่อนไข / header state holds, so a field added to
  // the editor later cannot be copied (or dropped) by accident: this equality names every key.
  it('copies the terms, the display names, the designer and the project tick — and clears ผู้ติดต่อ with its Phone and Email', () => {
    expect(copyFromQuotation(SOURCE).terms).toEqual({
      // ── copied ──
      unitCode: 'A001',                       // หน่วยงาน / ผู้ออกแบบ — the project's designer, same job
      depositPercent: 45,
      depositPercentCustom: true,             // 45 is not one of the preset chips
      noDeposit: false,
      fullPaymentTerm: '',
      remainderMode: 'CREDIT',
      creditDays: 30,
      validityDays: 30,
      validityMode: 'DATE',
      validityUntil: '2026-11-30',
      customerNotes: 'ส่งของภายใน 30 วันหลังยืนยัน',
      printedByDisplayId: 4,                  // "the display names"
      salesRepDisplayId: 9,
      hideProjectName: true,
      // ── as for any new quotation ──
      offerDate: bangkokTodayIso(),           // not the source's 2026-09-18
      projectName: '',                        // see suggestedProjectName below
      // ── left for the rep ──
      orderedByName: '',
      orderedByPhone: '',
      orderedByEmail: '',
    });
  });

  // The deal a copy is saved on is NEW, and a project belongs to a customer — so the name cannot
  // select anything until the rep has chosen who the new customer is. It travels as the name the
  // โครงการ box offers, and the document then prints whichever project the rep confirms there.
  it('carries the project NAME, as the name to offer once a customer is chosen (same job, different bidder)', () => {
    expect(copyFromQuotation(SOURCE).suggestedProjectName).toBe('คอนโด ริเวอร์ไซด์ ชั้น 5');
    expect(copyFromQuotation(source({ projectName: null })).suggestedProjectName).toBe('');
  });

  it('a "ไม่รับมัดจำ" source stays "ไม่รับมัดจำ", with the payment term it chose', () => {
    const { terms } = copyFromQuotation(source({
      depositPercent: 0, remainderMode: null, creditDays: null, fullPaymentTerm: 'ON_DELIVERY',
    }));
    expect(terms).toMatchObject({ noDeposit: true, depositPercent: 0, fullPaymentTerm: 'ON_DELIVERY' });
  });

  it('a source with nothing in an optional field copies a blank, never the text "null"', () => {
    const { terms } = copyFromQuotation(source({
      unitCode: null, depositPercent: 30, remainderMode: 'ON_DELIVERY', creditDays: null, validityMode: 'DAYS',
      validityUntil: null, customerNotes: null, printedByDisplayId: null, salesRepDisplayId: null,
      hideProjectName: false,
    }));
    expect(terms).toMatchObject({
      unitCode: '', depositPercent: 30, depositPercentCustom: false, creditDays: '', validityMode: 'DAYS',
      validityUntil: '', customerNotes: '', printedByDisplayId: '', salesRepDisplayId: '', hideProjectName: false,
    });
  });
});

describe('copyFromQuotation — the rows', () => {
  it('copies every row, in document order: tiles and สินค้า/บริการอื่น in the list, ส่วนลดพิเศษ after them', () => {
    const { items, adjustments } = copyFromQuotation(SOURCE);
    expect(items.map((row) => [row.lineType, row.model ?? row.description])).toEqual([
      ['TILE', 'Trilogy'], ['TILE', 'Urbanature'], ['PLAIN', 'ค่าขนส่ง'],
    ]);
    expect(adjustments.map((row) => [row.lineType, row.adjustmentKind, row.adjustmentPct, row.adjustmentDeadline]))
      .toEqual([['ADJUSTMENT', 'PERCENT', 3, '2026-11-30']]);
  });

  it('keeps the ตำแหน่งติดตั้ง of every row', () => {
    const { groups, items } = copyFromQuotation(SOURCE);
    expect(groups.map((group) => group.label)).toEqual(['ชั้น 5 โซน A', 'ชั้น 5 โซน B']);
    const labelOf = new Map(groups.map((group) => [group.groupId, group.label]));
    expect(items.map((row) => labelOf.get(row.groupId))).toEqual(['ชั้น 5 โซน A', 'ชั้น 5 โซน A', 'ชั้น 5 โซน B']);
  });

  it('keeps what the rep typed on each row: prices, discounts, quantities, notes — and, on a mixed document, each row\'s own price mode', () => {
    const [tile, special, freight] = copyFromQuotation(SOURCE).items;
    expect(tile).toMatchObject({
      priceMode: 'NET', listPriceSqm: 1800, discountPct: 20, piecesInput: 10, quantityMode: 'PIECES',
      itemNotes: 'ตัดขอบ 4 ด้าน', leadTimeMinDays: 75, leadTimeMaxDays: 90, catalogPriceId: 321, originCountry: 'อิตาลี',
    });
    expect(special).toMatchObject({ priceMode: 'SPECIAL_SQM', specialPriceSqm: 1350 });
    expect(freight).toMatchObject({ description: 'ค่าขนส่ง', quantity: 2, unit: 'งาน', unitPrice: 500, itemNotes: 'ส่งหน้างาน' });
  });

  // The relation that matters, stated without re-listing forty fields: saving the copy untouched
  // sends, for every row, exactly what re-saving the SOURCE would send — minus the row's id. The
  // right-hand side is the editor's existing load→save round trip (rowFromServerItem →
  // itemInputFromRow), which has its own tests; a copy that mapped rows any other way would differ
  // from it here.
  it('saved untouched, each copied row is the source row\'s own save input without its id', () => {
    const { items, adjustments } = copyFromQuotation(SOURCE);
    const saved = (row) => itemInputFromRow(row, rowPriceMode('MIXED', row), 'TH', { mixed: true });
    const fromSource = (dto) => {
      const row = rowFromServerItem(dto, rowPriceMode('MIXED', dto));
      return { ...saved(row), id: null };
    };

    expect(items.map(saved)).toEqual([TILE, SPECIAL_TILE, FREIGHT].map(fromSource));
    expect(adjustments.map(adjustmentInputFromRow))
      .toEqual([{ ...adjustmentInputFromRow(rowFromServerItem(EARLY_ORDER_DISCOUNT)), id: null }]);
  });

  // A copied draft opens showing its totals: without the rows' own amounts the estimate would read
  // 0.00 until the rep touched every row.
  it('brings each row\'s computed amount along, so the draft opens with its totals', () => {
    const { items } = copyFromQuotation(SOURCE);
    expect(items.map((row) => row.lineAmount)).toEqual([9682, 9076.6, 1000]);
  });

  it('gives every row a key of its own', () => {
    const { items, adjustments } = copyFromQuotation(SOURCE);
    const keys = [...items, ...adjustments].map((row) => row.clientId);
    expect(keys).toHaveLength(4);
    expect(keys.every((key) => key != null && key !== '')).toBe(true);
    expect(new Set(keys).size).toBe(4);
  });
});

describe('copyFromQuotation — nothing of the source survives (wrong way round)', () => {
  it('no row carries a server id, and none carries the source row\'s picture', () => {
    const { items, adjustments } = copyFromQuotation(SOURCE);
    const rows = [...items, ...adjustments];
    expect(rows).toHaveLength(4); // …so the two checks below are about real rows
    expect(rows.map((row) => row.id ?? null)).toEqual([null, null, null, null]);
    expect(rows.map((row) => [Boolean(row.hasPicture), row.pictureUrl ?? null]))
      .toEqual([[false, null], [false, null], [false, null], [false, null]]);
  });

  // The broad one. Whatever the copied state is made of, the source's identity is in none of it —
  // not in a field this test knows about, and not in one added to the DTO later and spread through
  // by accident. (`lineAmount` and friends are numbers that belong to the rows, not to the source.)
  it('the source\'s id, number, status, approval, customer, contact and row ids appear nowhere in the copied state', () => {
    const state = copyFromQuotation(SOURCE);
    expect(state.items).toHaveLength(3); // the state is a real copy, not an empty one
    const everything = JSON.stringify(state);

    for (const leaked of [
      '918273', 'QT-2026-0777', '827364',                                       // quotation id, number, deal
      '5550001', '5550002', '5550003', '5550004', '/api/deal-quotations/',      // row ids, picture urls
      'APPROVED', 'อนุมัติตามเสนอ', 'ผู้จัดการ อนุมัติเอง', '2026-09-22',             // status and approval
      'บริษัท ผู้รับเหมา เอ จำกัด', '1 ถนนเดิม', '0105550000001', '02-111-1111',     // the customer
      '6660001', 'สมชาย ลูกค้าเดิม', '081-999-0000', 'old.contact@contractor-a.example', // the contact snapshot
      'คุณวิชัย มั่นคง', '089-765-4321', 'wichai@contractor-a.example',            // ผู้ติดต่อ
      'P003',                                                                   // ฝ่าย, a field that no longer exists
      '2026-09-18', '2026-09-20',                                               // the source's own dates
    ]) {
      expect(everything, `"${leaked}" leaked into the copied state`).not.toContain(leaked);
    }
  });
});

// Who may copy, and what. The button and /quotations/new?copyFrom= both ask this one question.
describe('canCopyDealQuotation', () => {
  const direct = { id: 918273, origin: 'DEAL_DIRECT', docStatus: 'APPROVED', salesRepId: 6 };
  const fromPricingRequest = { ...direct, origin: 'PRICING_REQUEST', pricingRequestId: 12 };

  it('is the create-a-quotation gate, on a direct-deal quotation only — in every status', () => {
    const cases = [
      ['the owning rep', { id: 6, role: 'sales' }, direct, true],
      ['a sales manager', { id: 9, role: 'sales_manager' }, direct, true],
      ['a quotation-grant holder of another role', { id: 44, role: 'qc', canCreateQuotation: true }, direct, true],
      ['on a draft', { id: 6, role: 'sales' }, { ...direct, docStatus: 'DRAFT' }, true],
      ['on a superseded one', { id: 6, role: 'sales' }, { ...direct, docStatus: 'SUPERSEDED' }, true],
      ['on a cancelled one', { id: 6, role: 'sales' }, { ...direct, docStatus: 'CANCELLED' }, true],
      // ── never ──
      // Its prices are the CEO's decision for THAT request; they are not the rep's to re-issue.
      ['a rep, from a pricing request', { id: 6, role: 'sales' }, fromPricingRequest, false],
      ['a sales manager, from a pricing request', { id: 9, role: 'sales_manager' }, fromPricingRequest, false],
      ['a grant holder, from a pricing request', { id: 44, role: 'qc', canCreateQuotation: true }, fromPricingRequest, false],
      // May view, may not create.
      ['the CEO', { id: 8, role: 'ceo' }, direct, false],
      ['import', { id: 7, role: 'import' }, direct, false],
      ['account', { id: 5, role: 'account' }, direct, false],
      ['a role with no grant', { id: 44, role: 'qc' }, direct, false],
      // Fails closed on anything it cannot classify.
      ['an origin it does not know', { id: 6, role: 'sales' }, { ...direct, origin: 'SOMETHING_NEW' }, false],
      ['a quotation with no origin', { id: 6, role: 'sales' }, { ...direct, origin: undefined }, false],
      ['no quotation', { id: 6, role: 'sales' }, null, false],
      ['no user', null, direct, false],
    ];
    const verdicts = (pick) => Object.fromEntries(cases.map((row) => [row[0], pick(row)]));

    expect(verdicts(([, user, quotation]) => canCopyDealQuotation(user, quotation)))
      .toEqual(verdicts(([, , , expected]) => expected));
  });
});
