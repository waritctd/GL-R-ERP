import { describe, expect, it } from 'vitest';
import * as meta from './quotationMeta.js';
import {
  canApproveDealQuotation,
  canCancelDealQuotation,
  canCreateDealQuotation,
  canCreateDealQuotationStandalone,
  canDecideDealQuotation,
  canEditDealQuotation,
  canReviseDealQuotation,
  canSubmitDealQuotation,
  canTransitionDealQuotation,
  canViewDealQuotation,
  dealQuotationStatusLabel,
  defaultLeadTimeForOrigin,
  DEAL_QUOTATION_TRANSITIONS,
  hasDealQuotationGrant,
  isDealQuotationEditable,
  isDealQuotationReadOnlyViewer,
  piecesPerSqmFromSqmPerPiece,
  quotationItemMissingSummary,
  remainderModeLabel,
  sqmPerPieceFromPiecesPerSqm,
  validateQuotationItem,
} from './quotationMeta.js';

const salesOwner = { id: 6, role: 'sales' };
const otherSales = { id: 12, role: 'sales' };
const salesManager = { id: 9, role: 'sales_manager' };
const ceo = { id: 8, role: 'ceo' };
const importUser = { id: 7, role: 'import' };
const accountUser = { id: 11, role: 'account' };
const hrUser = { id: 2, role: 'hr' };
const qcUser = { id: 20, role: 'qc' };
const warehouseUser = { id: 21, role: 'warehouse' };
// #H4: the canCreateQuotation capability grant (owner ruling 2026-09-09) is a per-employee
// capability, not a role -- ภิญญดา (employee 144, role `qc`) is the plan's own example of who
// gets it without being sales/sales_manager.
const qcWithGrant = { id: 20, role: 'qc', canCreateQuotation: true };

const ownedTicket = { id: 18, createdById: 6 };
const otherTicket = { id: 19, createdById: 12 };

function quotation(overrides = {}) {
  return { id: 1, ticketId: 18, docStatus: 'DRAFT', salesRepId: 6, approvalNote: null, ...overrides };
}

describe('canTransitionDealQuotation', () => {
  it('mirrors the DRAFT -> PENDING_APPROVAL -> APPROVED | reject-to-DRAFT machine', () => {
    expect(canTransitionDealQuotation('DRAFT', 'PENDING_APPROVAL')).toBe(true);
    expect(canTransitionDealQuotation('DRAFT', 'CANCELLED')).toBe(true);
    expect(canTransitionDealQuotation('PENDING_APPROVAL', 'APPROVED')).toBe(true);
    expect(canTransitionDealQuotation('PENDING_APPROVAL', 'DRAFT')).toBe(true);
    expect(canTransitionDealQuotation('APPROVED', 'SUPERSEDED')).toBe(true);
  });

  it('refuses every terminal/backward edge the plan does not describe', () => {
    expect(canTransitionDealQuotation('DRAFT', 'APPROVED')).toBe(false);
    expect(canTransitionDealQuotation('APPROVED', 'DRAFT')).toBe(false);
    expect(canTransitionDealQuotation('APPROVED', 'PENDING_APPROVAL')).toBe(false);
    expect(canTransitionDealQuotation('CANCELLED', 'DRAFT')).toBe(false);
    expect(canTransitionDealQuotation('SUPERSEDED', 'APPROVED')).toBe(false);
    expect(canTransitionDealQuotation(null, 'DRAFT')).toBe(false);
    expect(canTransitionDealQuotation('DRAFT', undefined)).toBe(false);
  });

  it('DEAL_QUOTATION_TRANSITIONS covers exactly the five statuses the plan defines', () => {
    expect(Object.keys(DEAL_QUOTATION_TRANSITIONS).sort()).toEqual(
      ['APPROVED', 'CANCELLED', 'DRAFT', 'PENDING_APPROVAL', 'SUPERSEDED'].sort(),
    );
  });
});

describe('dealQuotationStatusLabel', () => {
  it('has a Thai label + tone for every status', () => {
    expect(dealQuotationStatusLabel('DRAFT')).toEqual({ label: 'ร่าง', tone: 'neutral' });
    expect(dealQuotationStatusLabel('PENDING_APPROVAL')).toEqual({ label: 'รออนุมัติ', tone: 'warning' });
    expect(dealQuotationStatusLabel('APPROVED')).toEqual({ label: 'อนุมัติแล้ว', tone: 'success' });
    expect(dealQuotationStatusLabel('SUPERSEDED')).toEqual({ label: 'ถูกแทนที่', tone: 'neutral' });
    expect(dealQuotationStatusLabel('CANCELLED')).toEqual({ label: 'ยกเลิก', tone: 'danger' });
  });

  it('falls back gracefully for an unknown status', () => {
    expect(dealQuotationStatusLabel('WEIRD')).toEqual({ label: 'WEIRD', tone: 'neutral' });
  });
});

describe('canCreateDealQuotation', () => {
  it('lets the owning sales rep create on their own deal', () => {
    expect(canCreateDealQuotation(salesOwner, ownedTicket)).toBe(true);
  });

  it('lets sales_manager create on ANY deal, owned or not', () => {
    expect(canCreateDealQuotation(salesManager, ownedTicket)).toBe(true);
    expect(canCreateDealQuotation(salesManager, otherTicket)).toBe(true);
  });

  // Wrong-way-round: the case that matters is what the caller CANNOT reach.
  it('refuses a sales rep on another rep\'s deal', () => {
    expect(canCreateDealQuotation(salesOwner, otherTicket)).toBe(false);
  });

  it('refuses ceo/import/account/hr -- none of them create a deal quotation', () => {
    expect(canCreateDealQuotation(ceo, ownedTicket)).toBe(false);
    expect(canCreateDealQuotation(importUser, ownedTicket)).toBe(false);
    expect(canCreateDealQuotation(accountUser, ownedTicket)).toBe(false);
    expect(canCreateDealQuotation(hrUser, ownedTicket)).toBe(false);
  });

  it('refuses with no user or no ticket', () => {
    expect(canCreateDealQuotation(null, ownedTicket)).toBe(false);
    expect(canCreateDealQuotation(salesOwner, null)).toBe(false);
  });

  // #H4: the canCreateQuotation grant.
  it('a qc user WITHOUT the grant is refused, even on their own imaginary deal', () => {
    expect(canCreateDealQuotation(qcUser, ownedTicket)).toBe(false);
    expect(canCreateDealQuotation(qcUser, otherTicket)).toBe(false);
  });

  it('a qc user WITH the grant may create on ANY deal, same as sales_manager', () => {
    expect(canCreateDealQuotation(qcWithGrant, ownedTicket)).toBe(true);
    expect(canCreateDealQuotation(qcWithGrant, otherTicket)).toBe(true);
  });
});

// Owner ask 2026-09-10 ("inline deal creation"): the role-only half of canCreateDealQuotation
// for the moment BEFORE a ticket exists. Must grant exactly the same three audiences
// canCreateDealQuotation already grants "own deal" access to -- sales/sales_manager/grant -- and
// refuse everyone canCreateDealQuotation refuses, since the creator immediately becomes the new
// ticket's owner and would re-pass the real check the instant it exists.
describe('canCreateDealQuotationStandalone', () => {
  it('grants sales, sales_manager, and a canCreateQuotation-granted employee', () => {
    expect(canCreateDealQuotationStandalone(salesOwner)).toBe(true);
    expect(canCreateDealQuotationStandalone(otherSales)).toBe(true); // ANY sales rep -- there is no deal to own yet
    expect(canCreateDealQuotationStandalone(salesManager)).toBe(true);
    expect(canCreateDealQuotationStandalone(qcWithGrant)).toBe(true);
  });

  it('refuses ceo/import/account/hr/qc-without-grant, same audience canCreateDealQuotation refuses', () => {
    expect(canCreateDealQuotationStandalone(ceo)).toBe(false);
    expect(canCreateDealQuotationStandalone(importUser)).toBe(false);
    expect(canCreateDealQuotationStandalone(accountUser)).toBe(false);
    expect(canCreateDealQuotationStandalone(hrUser)).toBe(false);
    expect(canCreateDealQuotationStandalone(qcUser)).toBe(false);
  });

  it('refuses with no user', () => {
    expect(canCreateDealQuotationStandalone(null)).toBe(false);
    expect(canCreateDealQuotationStandalone(undefined)).toBe(false);
  });
});

describe('canEditDealQuotation / isDealQuotationEditable', () => {
  it('lets the owning rep (via salesRepId) and sales_manager edit', () => {
    expect(canEditDealQuotation(salesOwner, quotation())).toBe(true);
    expect(canEditDealQuotation(salesManager, quotation({ salesRepId: 12 }))).toBe(true);
  });

  it('refuses a sales rep on a quotation whose salesRepId is someone else\'s', () => {
    expect(canEditDealQuotation(otherSales, quotation({ salesRepId: 6 }))).toBe(false);
  });

  it('refuses ceo/import/account -- oversight/read-only roles, never edit', () => {
    expect(canEditDealQuotation(ceo, quotation())).toBe(false);
    expect(canEditDealQuotation(importUser, quotation())).toBe(false);
    expect(canEditDealQuotation(accountUser, quotation())).toBe(false);
  });

  // #H4: the canCreateQuotation grant.
  it('a qc user WITHOUT the grant is refused, even on a quotation someone else owns', () => {
    expect(canEditDealQuotation(qcUser, quotation({ salesRepId: 6 }))).toBe(false);
  });

  it('a qc user WITH the grant may edit ANY quotation, same as sales_manager', () => {
    expect(canEditDealQuotation(qcWithGrant, quotation({ salesRepId: 6 }))).toBe(true);
    expect(canEditDealQuotation(qcWithGrant, quotation({ salesRepId: 999 }))).toBe(true);
  });

  it('isDealQuotationEditable is DRAFT-only, regardless of who is asking', () => {
    expect(isDealQuotationEditable(quotation({ docStatus: 'DRAFT' }))).toBe(true);
    expect(isDealQuotationEditable(quotation({ docStatus: 'PENDING_APPROVAL' }))).toBe(false);
    expect(isDealQuotationEditable(quotation({ docStatus: 'APPROVED' }))).toBe(false);
    expect(isDealQuotationEditable(quotation({ docStatus: 'CANCELLED' }))).toBe(false);
  });
});

describe('canSubmitDealQuotation / canCancelDealQuotation / canReviseDealQuotation', () => {
  it('submit needs edit rights AND DRAFT', () => {
    expect(canSubmitDealQuotation(salesOwner, quotation({ docStatus: 'DRAFT' }))).toBe(true);
    expect(canSubmitDealQuotation(salesOwner, quotation({ docStatus: 'PENDING_APPROVAL' }))).toBe(false);
    expect(canSubmitDealQuotation(otherSales, quotation({ docStatus: 'DRAFT' }))).toBe(false);
  });

  it('cancel needs edit rights AND DRAFT (mirrors "DRAFT -> (cancel) -> CANCELLED")', () => {
    expect(canCancelDealQuotation(salesOwner, quotation({ docStatus: 'DRAFT' }))).toBe(true);
    expect(canCancelDealQuotation(salesOwner, quotation({ docStatus: 'APPROVED' }))).toBe(false);
    expect(canCancelDealQuotation(otherSales, quotation({ docStatus: 'DRAFT' }))).toBe(false);
  });

  it('revise needs edit rights AND APPROVED only', () => {
    expect(canReviseDealQuotation(salesOwner, quotation({ docStatus: 'APPROVED' }))).toBe(true);
    expect(canReviseDealQuotation(salesOwner, quotation({ docStatus: 'DRAFT' }))).toBe(false);
    expect(canReviseDealQuotation(salesOwner, quotation({ docStatus: 'PENDING_APPROVAL' }))).toBe(false);
    expect(canReviseDealQuotation(otherSales, quotation({ docStatus: 'APPROVED' }))).toBe(false);
  });
});

describe('canApproveDealQuotation / canDecideDealQuotation', () => {
  it('sales_manager and ceo may approve, role-only', () => {
    expect(canApproveDealQuotation(salesManager)).toBe(true);
    expect(canApproveDealQuotation(ceo)).toBe(true);
  });

  // Wrong-way-round: sales -- even the deal's own owning rep -- must never approve its own
  // quotation. This is the case CLAUDE.md calls out by name ("manager sees their own division"
  // is not the test that matters).
  it('sales can NEVER approve, including the deal\'s own owning rep', () => {
    expect(canApproveDealQuotation(salesOwner)).toBe(false);
  });

  it('import/account/hr cannot approve', () => {
    expect(canApproveDealQuotation(importUser)).toBe(false);
    expect(canApproveDealQuotation(accountUser)).toBe(false);
    expect(canApproveDealQuotation(hrUser)).toBe(false);
  });

  // #H4: approve/reject stay sales_manager/ceo ONLY -- the canCreateQuotation grant does NOT
  // extend to them (unlike create/edit/view, which treat the grant as "any deal").
  it('the canCreateQuotation grant does NOT make a qc user an approver', () => {
    expect(canApproveDealQuotation(qcWithGrant)).toBe(false);
    expect(canDecideDealQuotation(qcWithGrant, quotation({ docStatus: 'PENDING_APPROVAL' }))).toBe(false);
  });

  it('canDecideDealQuotation additionally requires PENDING_APPROVAL', () => {
    expect(canDecideDealQuotation(ceo, quotation({ docStatus: 'PENDING_APPROVAL' }))).toBe(true);
    expect(canDecideDealQuotation(ceo, quotation({ docStatus: 'DRAFT' }))).toBe(false);
    expect(canDecideDealQuotation(ceo, quotation({ docStatus: 'APPROVED' }))).toBe(false);
    expect(canDecideDealQuotation(salesOwner, quotation({ docStatus: 'PENDING_APPROVAL' }))).toBe(false);
  });
});

describe('canViewDealQuotation / isDealQuotationReadOnlyViewer', () => {
  it('grants the plan\'s exact view role set', () => {
    expect(canViewDealQuotation(salesOwner)).toBe(true);
    expect(canViewDealQuotation(salesManager)).toBe(true);
    expect(canViewDealQuotation(ceo)).toBe(true);
    expect(canViewDealQuotation(importUser)).toBe(true);
    expect(canViewDealQuotation(accountUser)).toBe(true);
  });

  // Wrong-way-round: roles the plan never grants a read to.
  it('refuses hr/qc/warehouse and no user', () => {
    expect(canViewDealQuotation(hrUser)).toBe(false);
    expect(canViewDealQuotation(qcUser)).toBe(false);
    expect(canViewDealQuotation(warehouseUser)).toBe(false);
    expect(canViewDealQuotation(null)).toBe(false);
  });

  it('import/account are read-only viewers; sales/sales_manager/ceo are not', () => {
    expect(isDealQuotationReadOnlyViewer(importUser)).toBe(true);
    expect(isDealQuotationReadOnlyViewer(accountUser)).toBe(true);
    expect(isDealQuotationReadOnlyViewer(salesOwner)).toBe(false);
    expect(isDealQuotationReadOnlyViewer(salesManager)).toBe(false);
    expect(isDealQuotationReadOnlyViewer(ceo)).toBe(false);
  });

  // #H4: the canCreateQuotation grant.
  it('a qc user WITHOUT the grant cannot view; WITH the grant can view any deal', () => {
    expect(canViewDealQuotation(qcUser)).toBe(false);
    expect(canViewDealQuotation(qcWithGrant)).toBe(true);
  });
});

describe('hasDealQuotationGrant (#H4)', () => {
  it('reads the canCreateQuotation field off the user, defaulting falsy to false', () => {
    expect(hasDealQuotationGrant(qcWithGrant)).toBe(true);
    expect(hasDealQuotationGrant(qcUser)).toBe(false);
    expect(hasDealQuotationGrant({ role: 'qc', canCreateQuotation: false })).toBe(false);
    expect(hasDealQuotationGrant(null)).toBe(false);
  });
});

describe('remainderModeLabel / defaultLeadTimeForOrigin', () => {
  it('labels both remainder modes and falls back to the raw value', () => {
    expect(remainderModeLabel('CREDIT')).toBe('เครดิต');
    expect(remainderModeLabel('ON_DELIVERY')).toBe('ชำระเมื่อส่งมอบ');
    expect(remainderModeLabel('WEIRD')).toBe('WEIRD');
  });

  it('gives each origin country its documented default lead-time range', () => {
    expect(defaultLeadTimeForOrigin('อิตาลี')).toEqual({ leadTimeMinDays: 75, leadTimeMaxDays: 90 });
    expect(defaultLeadTimeForOrigin('สเปน')).toEqual({ leadTimeMinDays: 75, leadTimeMaxDays: 90 });
    expect(defaultLeadTimeForOrigin('จีน')).toEqual({ leadTimeMinDays: 30, leadTimeMaxDays: 45 });
    expect(defaultLeadTimeForOrigin('ไทย-สต็อก')).toEqual({ leadTimeMinDays: 3, leadTimeMaxDays: 7 });
    expect(defaultLeadTimeForOrigin('อื่นๆ')).toEqual({ leadTimeMinDays: null, leadTimeMaxDays: null });
  });

  it('returns nulls for an unknown origin rather than throwing', () => {
    expect(defaultLeadTimeForOrigin('ดาวอังคาร')).toEqual({ leadTimeMinDays: null, leadTimeMaxDays: null });
  });
});

// Frontend pass 4 (owner ruling 2026-09-10): "Autofill as much as possible when the item is in
// the database; sales can also fill in their own item if it is not in the database, but ALL info
// about the tile has to be completed." validateQuotationItem is the single completeness check --
// deliberately identical for a catalog-linked row and a hand-typed one (a catalog pick only ever
// fills fields, it never exempts a row from carrying them).
function completeItem(overrides = {}) {
  return {
    model: 'Trilogy', color: 'Ivory', texture: 'Lappato', sizeText: '60x120',
    thicknessMm: 10, sqmPerPiece: 0.72, piecesPerBox: 3, unitPrice: 850,
    quantityMode: 'AREA', areaSqm: 36, piecesInput: '',
    ...overrides,
  };
}

describe('validateQuotationItem (#M4, owner ruling 2026-09-10)', () => {
  it('a fully-filled AREA-mode item has no errors', () => {
    expect(validateQuotationItem(completeItem())).toEqual({});
  });

  it('a fully-filled PIECES-mode item has no errors (areaSqm irrelevant in this mode)', () => {
    expect(validateQuotationItem(completeItem({ quantityMode: 'PIECES', areaSqm: '', piecesInput: 50 }))).toEqual({});
  });

  it('flags a missing รุ่น', () => {
    expect(validateQuotationItem(completeItem({ model: '' }))).toEqual({ model: 'กรุณาระบุรุ่น' });
    expect(validateQuotationItem(completeItem({ model: '   ' }))).toEqual({ model: 'กรุณาระบุรุ่น' });
  });

  it('flags a missing สี', () => {
    expect(validateQuotationItem(completeItem({ color: '' }))).toEqual({ color: 'กรุณาระบุสี' });
  });

  it('flags a missing ผิว', () => {
    expect(validateQuotationItem(completeItem({ texture: '' }))).toEqual({ texture: 'กรุณาระบุผิว' });
  });

  it('flags a missing ขนาด', () => {
    expect(validateQuotationItem(completeItem({ sizeText: '' }))).toEqual({ sizeText: 'กรุณาระบุขนาด' });
  });

  it('flags a missing or non-positive ความหนา', () => {
    expect(validateQuotationItem(completeItem({ thicknessMm: null }))).toEqual({ thicknessMm: 'กรุณาระบุความหนา (มม.)' });
    expect(validateQuotationItem(completeItem({ thicknessMm: 0 }))).toEqual({ thicknessMm: 'กรุณาระบุความหนา (มม.)' });
  });

  it('flags a missing or non-positive แผ่น/ตร.ม.', () => {
    expect(validateQuotationItem(completeItem({ sqmPerPiece: null }))).toEqual({ sqmPerPiece: 'กรุณาระบุแผ่น/ตร.ม.' });
    expect(validateQuotationItem(completeItem({ sqmPerPiece: 0 }))).toEqual({ sqmPerPiece: 'กรุณาระบุแผ่น/ตร.ม.' });
  });

  it('flags a missing or sub-1 แผ่น/กล่อง', () => {
    expect(validateQuotationItem(completeItem({ piecesPerBox: null }))).toEqual({ piecesPerBox: 'กรุณาระบุแผ่น/กล่อง' });
    expect(validateQuotationItem(completeItem({ piecesPerBox: 0 }))).toEqual({ piecesPerBox: 'กรุณาระบุแผ่น/กล่อง' });
  });

  it('flags a missing or non-positive ราคา/หน่วย', () => {
    expect(validateQuotationItem(completeItem({ unitPrice: null }))).toEqual({ unitPrice: 'กรุณาระบุราคา/หน่วย' });
    expect(validateQuotationItem(completeItem({ unitPrice: 0 }))).toEqual({ unitPrice: 'กรุณาระบุราคา/หน่วย' });
  });

  it('AREA mode requires a positive พื้นที่, ignoring piecesInput entirely', () => {
    expect(validateQuotationItem(completeItem({ areaSqm: 0 }))).toEqual({ areaSqm: 'กรุณาระบุพื้นที่ (ตร.ม.)' });
    expect(validateQuotationItem(completeItem({ areaSqm: '' }))).toEqual({ areaSqm: 'กรุณาระบุพื้นที่ (ตร.ม.)' });
  });

  it('PIECES mode requires a piecesInput of at least 1, ignoring areaSqm entirely', () => {
    expect(validateQuotationItem(completeItem({ quantityMode: 'PIECES', areaSqm: '', piecesInput: 0 })))
      .toEqual({ piecesInput: 'กรุณาระบุจำนวนแผ่น' });
    expect(validateQuotationItem(completeItem({ quantityMode: 'PIECES', areaSqm: '', piecesInput: '' })))
      .toEqual({ piecesInput: 'กรุณาระบุจำนวนแผ่น' });
  });

  // The owner's own point: a catalog pick that left a gap (e.g. NO_THICKNESS, roughly a third of
  // the prod catalog) must still be flagged -- catalogPriceId being set is never treated as "this
  // row is exempt".
  it('a catalog-linked item with a null thickness (a catalog gap) is still invalid', () => {
    const item = completeItem({ catalogPriceId: 42, productCode: 'PAN-T600-IVO', thicknessMm: null });
    expect(validateQuotationItem(item)).toEqual({ thicknessMm: 'กรุณาระบุความหนา (มม.)' });
  });

  it('optional fields (brand, productCode, locationLabel, originCountry, lead times, notes, discount) being absent never fails validation', () => {
    const item = completeItem({
      brand: '', productCode: '', locationLabel: '', originCountry: '',
      leadTimeMinDays: null, leadTimeMaxDays: null, itemNotes: '', discountPct: 0,
    });
    expect(validateQuotationItem(item)).toEqual({});
  });

  it('accumulates every missing field on one item, not just the first', () => {
    const errors = validateQuotationItem({ quantityMode: 'AREA' });
    expect(Object.keys(errors).sort()).toEqual(
      ['areaSqm', 'color', 'model', 'piecesPerBox', 'sizeText', 'sqmPerPiece', 'texture', 'thicknessMm', 'unitPrice'].sort(),
    );
  });
});

// Owner feedback 2026-09-12: the editor shows/accepts แผ่น/ตร.ม. (pieces per sqm) while the wire
// field stays ตร.ม./แผ่น (sqmPerPiece) verbatim -- see quotationMeta.js's own comment on these two
// for the full contract. `WastageCalculator#piecesPerSqm`'s formula (round(1/x, 2, HALF_UP)) is
// mirrored for DISPLAY only; these tests pin that mirror and the round trip through it, never the
// money math itself.
describe('piecesPerSqmFromSqmPerPiece / sqmPerPieceFromPiecesPerSqm (แผ่น/ตร.ม. display, owner feedback 2026-09-12)', () => {
  it('mirrors WastageCalculator#piecesPerSqm — round(1/x, 2, HALF_UP)', () => {
    expect(piecesPerSqmFromSqmPerPiece(0.72)).toBe(1.39);
    expect(piecesPerSqmFromSqmPerPiece(0.36)).toBe(2.78);
    // The real disagreement this feature exists to route around: a 300x600 mesh sheet whose
    // covered area is genuinely 0.135 sqm/piece, not the 0.18 width x height geometry would say.
    expect(piecesPerSqmFromSqmPerPiece(0.135)).toBe(7.41);
  });

  it('returns null rather than Infinity for nothing to invert', () => {
    expect(piecesPerSqmFromSqmPerPiece(null)).toBeNull();
    expect(piecesPerSqmFromSqmPerPiece(0)).toBeNull();
    expect(piecesPerSqmFromSqmPerPiece('')).toBeNull();
  });

  it('CLAUDE.md acceptance check — enter 16.39, store 1/16.39, redisplay exactly 16.39', () => {
    const stored = sqmPerPieceFromPiecesPerSqm(16.39);
    // NUMERIC(10,6) precision -- matches sqm_per_piece's own column scale everywhere it is
    // persisted (V24, V165), which is what leaves enough headroom below the 2dp the reverse
    // direction rounds to for the round trip to land on the exact original figure.
    expect(stored).toBeCloseTo(1 / 16.39, 6);
    expect(piecesPerSqmFromSqmPerPiece(stored)).toBe(16.39);
  });

  it('round-trips a spread of real figures without drift', () => {
    for (const piecesPerSqm of [1.39, 2.78, 7.41, 16.39, 1, 100, 0.5]) {
      const stored = sqmPerPieceFromPiecesPerSqm(piecesPerSqm);
      expect(piecesPerSqmFromSqmPerPiece(stored)).toBe(piecesPerSqm);
    }
  });

  it('returns null rather than a bogus reciprocal for a non-positive แผ่น/ตร.ม.', () => {
    expect(sqmPerPieceFromPiecesPerSqm(0)).toBeNull();
    expect(sqmPerPieceFromPiecesPerSqm(-1)).toBeNull();
    expect(sqmPerPieceFromPiecesPerSqm(null)).toBeNull();
  });
});

describe('quotationItemMissingSummary', () => {
  it('returns null once an item has no errors', () => {
    expect(quotationItemMissingSummary({}, 0)).toBeNull();
  });

  // The plan's own worked example.
  it('renders "รายการที่ N: ขาด field1, field2" in row-layout order, 1-indexed', () => {
    const errors = validateQuotationItem(completeItem({ color: '', thicknessMm: null }));
    expect(quotationItemMissingSummary(errors, 1)).toBe('รายการที่ 2: ขาด สี, ความหนา');
  });
});

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Owner feedback pass 1, 2026-09-10 — F1 grouping + origin mapping, F5 tab metadata.
// ─────────────────────────────────────────────────────────────────────────────────────────────

describe('originCountryFromCode (F1 catalog autofill)', () => {
  it('maps the four codes this app has ประเทศต้นทาง options for', () => {
    expect(meta.originCountryFromCode('IT')).toBe('อิตาลี');
    expect(meta.originCountryFromCode('ES')).toBe('สเปน');
    expect(meta.originCountryFromCode('CN')).toBe('จีน');
    expect(meta.originCountryFromCode('TH')).toBe('ไทย-สต็อก');
  });

  it('is case- and whitespace-insensitive about the ISO code', () => {
    expect(meta.originCountryFromCode(' it ')).toBe('อิตาลี');
  });

  // Blank, not 'อื่นๆ': that option carries no lead time, so guessing it would look filled in
  // while telling the customer nothing.
  it('returns "" for an unmapped, empty or absent code', () => {
    expect(meta.originCountryFromCode('PT')).toBe('');
    expect(meta.originCountryFromCode('')).toBe('');
    expect(meta.originCountryFromCode(null)).toBe('');
    expect(meta.originCountryFromCode(undefined)).toBe('');
  });

  it('every mapped value is a real ORIGIN_COUNTRY_OPTIONS code with a lead-time range', () => {
    for (const code of ['IT', 'ES', 'CN', 'TH']) {
      const label = meta.originCountryFromCode(code);
      const option = meta.ORIGIN_COUNTRY_OPTIONS.find((o) => o.code === label);
      expect(option, `${code} -> ${label}`).toBeDefined();
      expect(option.leadTimeMinDays).toBeGreaterThan(0);
    }
  });
});

// ── MED-5: the แก้ predicate must match the SQL, not JS truthiness ──────────────────────────────
describe('isDealQuotationNeedingRework — mirrors NEEDS_REWORK_PREDICATE', () => {
  const draft = (over = {}) => ({ docStatus: 'DRAFT', approvalNote: null, parentQuotationId: null, ...over });

  it('an EMPTY-STRING approvalNote counts as present, exactly as `IS NOT NULL` does', () => {
    // The whole finding: `Boolean('')` is false, `'' IS NOT NULL` is TRUE. The mock used the
    // former, so this row was in the แก้ tab on the real backend and missing from it in the mock —
    // fewer rows than production, which is the direction nobody notices from the UI.
    expect(meta.isDealQuotationNeedingRework(draft({ approvalNote: '' }))).toBe(true);
    expect(meta.isDealQuotationNeedingRework(draft({ approvalNote: 'ราคาสูงเกินไป' }))).toBe(true);
  });

  it('null / undefined / absent approvalNote with no parent is NOT rework', () => {
    expect(meta.isDealQuotationNeedingRework(draft())).toBe(false);
    expect(meta.isDealQuotationNeedingRework(draft({ approvalNote: undefined }))).toBe(false);
  });

  it('a DRAFT revision in progress counts, via parentQuotationId (the second sense of แก้)', () => {
    expect(meta.isDealQuotationNeedingRework(draft({ parentQuotationId: 7 }))).toBe(true);
  });

  it('DRAFT is part of the definition — an APPROVED/SUPERSEDED row with a note is never rework', () => {
    expect(meta.isDealQuotationNeedingRework({ docStatus: 'APPROVED', approvalNote: '', parentQuotationId: null })).toBe(false);
    expect(meta.isDealQuotationNeedingRework({ docStatus: 'SUPERSEDED', approvalNote: 'x', parentQuotationId: 3 })).toBe(false);
    expect(meta.isDealQuotationNeedingRework({ docStatus: 'PENDING_APPROVAL', approvalNote: 'x', parentQuotationId: null })).toBe(false);
  });
});

describe('locationGroupsFromItems (F1)', () => {
  const item = (locationLabel, model) => ({ locationLabel, model });

  it('starts a new group at every change of label, and tags each item with its group', () => {
    const { groups, items } = meta.locationGroupsFromItems([
      item('ชั้น 1', 'A'), item('ชั้น 1', 'B'), item('ชั้น 2', 'C'),
    ]);

    expect(groups.map((g) => g.label)).toEqual(['ชั้น 1', 'ชั้น 2']);
    expect(items.map((it) => it.groupId)).toEqual([groups[0].groupId, groups[0].groupId, groups[1].groupId]);
  });

  // Two SEPARATE runs of the same label are two groups — collecting them together would reorder
  // items the rep arranged deliberately, and the document does not do that either.
  it('does not merge non-adjacent runs of the same label', () => {
    const { groups } = meta.locationGroupsFromItems([item('ชั้น 1', 'A'), item('ชั้น 2', 'B'), item('ชั้น 1', 'C')]);
    expect(groups).toHaveLength(3);
    expect(groups.map((g) => g.label)).toEqual(['ชั้น 1', 'ชั้น 2', 'ชั้น 1']);
  });

  it('treats null / undefined / "" as one and the same blank label', () => {
    const { groups } = meta.locationGroupsFromItems([item(null, 'A'), item(undefined, 'B'), item('', 'C')]);
    expect(groups).toHaveLength(1);
    expect(groups[0].label).toBe('');
  });

  it('always yields at least one group, so a brand-new quotation has somewhere to add an item', () => {
    expect(meta.locationGroupsFromItems([]).groups).toHaveLength(1);
    expect(meta.locationGroupsFromItems(undefined).groups).toHaveLength(1);
  });

  it('mints distinct group ids across calls', () => {
    const a = meta.locationGroupsFromItems([item('x', 'A')]).groups[0].groupId;
    const b = meta.locationGroupsFromItems([item('x', 'A')]).groups[0].groupId;
    expect(a).not.toBe(b);
    expect(meta.newLocationGroupId()).not.toBe(meta.newLocationGroupId());
  });

  it('preserves item order exactly, so the saved document order is the loaded one', () => {
    const input = [item('ชั้น 2', 'A'), item('ชั้น 1', 'B'), item('ชั้น 2', 'C')];
    const { items } = meta.locationGroupsFromItems(input);
    expect(items.map((it) => it.model)).toEqual(['A', 'B', 'C']);
  });
});

describe('DEAL_QUOTATION_STATUS_TABS (F5)', () => {
  it('is exactly the five tabs the owner asked for, in order', () => {
    expect(meta.DEAL_QUOTATION_STATUS_TABS.map((t) => t.label))
      .toEqual(['ทั้งหมด', 'รออนุมัติ', 'แก้', 'ยกเลิก', 'อนุมัติแล้ว']);
  });

  it('drives แก้ off needsRework, not a docStatus', () => {
    const rework = meta.dealQuotationStatusTab('NEEDS_REWORK');
    expect(rework.params).toEqual({ needsRework: true });
    expect(rework.params.status).toBeUndefined();
  });

  it('sends no params at all for ทั้งหมด', () => {
    expect(meta.dealQuotationStatusTab('all').params).toEqual({});
  });

  it('returns null for an unknown key so the caller can fall back to the role default', () => {
    expect(meta.dealQuotationStatusTab('SUPERSEDED')).toBeNull();
    expect(meta.dealQuotationStatusTab(null)).toBeNull();
  });

  it('sends approvers to รออนุมัติ and everyone else to ทั้งหมด', () => {
    expect(meta.defaultDealQuotationStatusTab({ role: 'sales_manager' })).toBe('PENDING_APPROVAL');
    expect(meta.defaultDealQuotationStatusTab({ role: 'ceo' })).toBe('PENDING_APPROVAL');
    expect(meta.defaultDealQuotationStatusTab({ role: 'sales' })).toBe('all');
    expect(meta.defaultDealQuotationStatusTab({ role: 'import' })).toBe('all');
    expect(meta.defaultDealQuotationStatusTab(null)).toBe('all');
  });

  it('every tab names a countKey the counts DTO carries', () => {
    expect(meta.DEAL_QUOTATION_STATUS_TABS.map((t) => t.countKey))
      .toEqual(['all', 'pendingApproval', 'needsRework', 'cancelled', 'approved']);
  });
});

// ── Quotation v3 / v3b (owner feedback pass 3, 2026-09-11) ─────────────────────────────────────
describe('v3/v3b document settings', () => {
  it('offers all three tile price modes on a Thai document', () => {
    expect(meta.availablePriceModes('TH').map((o) => o.code)).toEqual(['NET', 'SPECIAL_SQM', 'DIRECT_NET']);
  });

  it('does NOT offer SPECIAL_SQM on an English document (the server 400s it)', () => {
    expect(meta.availablePriceModes('EN').map((o) => o.code)).toEqual(['NET', 'DIRECT_NET']);
  });

  it('moves a SPECIAL_SQM document to DIRECT_NET on English, and reports that it moved', () => {
    expect(meta.priceModeForLanguage('SPECIAL_SQM', 'EN')).toEqual({ priceMode: 'DIRECT_NET', moved: true });
    expect(meta.priceModeForLanguage('NET', 'EN')).toEqual({ priceMode: 'NET', moved: false });
    expect(meta.priceModeForLanguage('SPECIAL_SQM', 'TH')).toEqual({ priceMode: 'SPECIAL_SQM', moved: false });
  });

  it('derives the currency and the VAT rate from the language alone', () => {
    expect(meta.currencyForLanguage('TH')).toBe('THB');
    expect(meta.currencyForLanguage('EN')).toBe('USD');
    expect(meta.vatRateForLanguage('TH')).toBe(0.07);
    expect(meta.vatRateForLanguage('EN')).toBe(0);
  });

  it('formats USD with $, and puts a negative sign BEFORE the symbol', () => {
    expect(meta.formatQuotationMoney(1234.5, 'USD')).toBe('$1,234.50');
    expect(meta.formatQuotationMoney(-38198.21, 'THB')).toBe('-฿38,198.21');
    expect(meta.formatQuotationMoney(null, 'THB')).toBe('-');
  });
});

describe('v3 row labels and derivations', () => {
  it('prints an EMPTY ส่วนลด cell for a ส่วนลดพิเศษ row, whatever it carries', () => {
    expect(meta.documentDiscountLabel({ lineType: 'ADJUSTMENT', discountPct: 5 }, 'NET')).toBe('');
  });

  it('prints พิเศษ for a SPECIAL_SQM tile, and Special on English', () => {
    expect(meta.documentDiscountLabel({ lineType: 'TILE' }, 'SPECIAL_SQM', 'TH')).toBe('พิเศษ');
    expect(meta.documentDiscountLabel({ lineType: 'TILE' }, 'SPECIAL_SQM', 'EN')).toBe('Special');
  });

  it('prints Net for a DIRECT_NET tile whose net equals its list price, พิเศษ when it differs', () => {
    expect(meta.documentDiscountLabel({ lineType: 'TILE', unitPrice: 500, netUnitPrice: 500 }, 'DIRECT_NET')).toBe('Net');
    expect(meta.documentDiscountLabel({ lineType: 'TILE', unitPrice: 600, netUnitPrice: 500 }, 'DIRECT_NET')).toBe('พิเศษ');
  });

  it('prints N% or Net for a PLAIN row from its own discount', () => {
    expect(meta.documentDiscountLabel({ lineType: 'PLAIN', discountPct: 0 }, 'SPECIAL_SQM')).toBe('Net');
    expect(meta.documentDiscountLabel({ lineType: 'PLAIN', discountPct: 5 }, 'NET')).toBe('5%');
  });

  it('composes the ส่วนลดพิเศษ preview with a zero-padded BE date, as the owner\'s QN6900704-2 prints it', () => {
    expect(meta.adjustmentDescriptionPreview({ adjustmentKind: 'PERCENT', adjustmentPct: 3, adjustmentDeadline: '2026-07-31' }))
      .toBe('ส่วนลดพิเศษ 3% สำหรับการสั่งซื้อภายใน 31/07/2569');
    expect(meta.adjustmentDescriptionPreview({ adjustmentKind: 'PERCENT', adjustmentPct: 3, adjustmentDeadline: '' }))
      .toBe('ส่วนลดพิเศษ 3%');
  });

  it('keeps the rep\'s own wording on a FLAT adjustment, and never prints a percent there', () => {
    expect(meta.adjustmentDescriptionPreview({ adjustmentKind: 'AMOUNT', description: 'ส่วนลดท้ายบิล', adjustmentPct: 9 }))
      .toBe('ส่วนลดท้ายบิล');
    expect(meta.adjustmentDescriptionPreview({ adjustmentKind: 'AMOUNT', adjustmentPct: 9 })).toBe('ส่วนลดพิเศษ');
  });

  it('estimates a percentage adjustment as base × pct, rounded to satang — labelled an estimate by the caller', () => {
    // The owner's QN6900704-2: 1,273,273.56 × 3% = 38,198.21 (the printed figure).
    expect(meta.estimateAdjustmentAmount({ adjustmentKind: 'PERCENT', adjustmentPct: 3 }, 1273273.56)).toBe(38198.21);
    expect(meta.estimateAdjustmentAmount({ adjustmentKind: 'AMOUNT', adjustmentAmount: 500 }, 0)).toBe(500);
    expect(meta.estimateAdjustmentAmount({ adjustmentKind: 'PERCENT', adjustmentPct: '' }, 1000)).toBeNull();
  });
});

describe('v3 row validation', () => {
  const tile = {
    model: 'Trilogy', color: 'Ash', texture: 'Matt', sizeText: '60x60', thicknessMm: 10,
    piecesPerBox: 3, sqmPerPiece: 0.36, unitPrice: 850, quantityMode: 'AREA', areaSqm: 20,
  };

  it('SPECIAL_SQM needs the ราคาพิเศษ — and still the list price, which the server requires on every tile row', () => {
    expect(meta.validateQuotationItem({ ...tile, specialPriceSqm: '' }, 'SPECIAL_SQM')).toEqual({ specialPriceSqm: 'กรุณาระบุราคาพิเศษ (บาท/ตร.ม.)' });
    expect(meta.validateQuotationItem({ ...tile, specialPriceSqm: 1350 }, 'SPECIAL_SQM')).toEqual({});
    expect(meta.validateQuotationItem({ ...tile, unitPrice: '', specialPriceSqm: 1350 }, 'SPECIAL_SQM').unitPrice).toBeTruthy();
  });

  it('refuses a ราคาพิเศษ with more than 2 decimals (the column is NUMERIC(12,2))', () => {
    expect(meta.validateQuotationItem({ ...tile, specialPriceSqm: 1350.125 }, 'SPECIAL_SQM').specialPriceSqm).toBe('ทศนิยมได้ไม่เกิน 2 ตำแหน่ง');
  });

  it('DIRECT_NET needs the net per piece and lets the list price stay blank', () => {
    expect(meta.validateQuotationItem({ ...tile, unitPrice: '', directNetPrice: '' }, 'DIRECT_NET')).toEqual({ directNetPrice: 'กรุณาระบุราคาสุทธิ/แผ่น' });
    expect(meta.validateQuotationItem({ ...tile, unitPrice: '', directNetPrice: 500 }, 'DIRECT_NET')).toEqual({});
  });

  it('a PLAIN row needs description, quantity, unit and a positive price — and nothing tile-shaped', () => {
    expect(meta.validateQuotationItem({ lineType: 'PLAIN' })).toEqual({
      description: 'กรุณาระบุรายละเอียด', quantity: 'กรุณาระบุจำนวน', unit: 'กรุณาเลือกหน่วย', unitPrice: 'กรุณาระบุราคา/หน่วย',
    });
    expect(meta.validateQuotationItem({ lineType: 'PLAIN', description: 'ค่าขนส่ง', quantity: 1, unit: 'งาน', unitPrice: 3500 })).toEqual({});
    expect(meta.validatePlainItem({ description: 'x', quantity: 1.005, unit: 'JOB', unitPrice: 1 }).quantity).toBeTruthy();
  });

  it('a ส่วนลดพิเศษ needs a positive percent (≤ 100, ≤ 3dp) or a positive amount', () => {
    expect(meta.validateAdjustment({ adjustmentKind: 'PERCENT', adjustmentPct: '' })).toEqual({ adjustmentPct: 'กรุณาระบุเปอร์เซ็นต์ส่วนลด' });
    expect(meta.validateAdjustment({ adjustmentKind: 'PERCENT', adjustmentPct: 101 }).adjustmentPct).toBe('ส่วนลดต้องไม่เกิน 100%');
    expect(meta.validateAdjustment({ adjustmentKind: 'PERCENT', adjustmentPct: 3 })).toEqual({});
    expect(meta.validateAdjustment({ adjustmentKind: 'AMOUNT', adjustmentAmount: 0 })).toEqual({ adjustmentAmount: 'กรุณาระบุจำนวนเงินส่วนลด' });
  });
});

// ── "ข้อมูลที่ยังไม่ครบ" checklist (owner, 2026-09-11) ─────────────────────────────────────────
describe('buildQuotationChecklist', () => {
  const customer = { id: 5, name: 'บริษัท ก จำกัด', address: '1 ถนนสุขุมวิท', taxId: '0105551234567', phone: '02-000-0000' };
  const contact = { id: 6, firstName: 'ธนพล', phone: '081-234-5678', email: 'a@b.co' };
  const complete = {
    customer, projectName: 'โครงการ A', contact, items: [{ lineType: 'TILE' }], itemErrorsByRow: [{}],
  };
  const blocking = (entries) => entries.filter((e) => e.blocking).map((e) => e.check);
  const warnings = (entries) => entries.filter((e) => !e.blocking).map((e) => e.check);

  it('pins the blocking set to what the backend already refuses — nothing the owner has not ruled on', () => {
    expect([...meta.QUOTATION_BLOCKING_CHECKS].sort()).toEqual(
      ['contact', 'customer', 'items', 'locationLabels', 'priceModeLanguage', 'project'],
    );
    // Wrong-way-round: none of the header fields a customer might simply not have is blocking.
    ['customerAddress', 'customerTaxId', 'customerPhone', 'contactPhone', 'contactEmail', 'dealProject']
      .forEach((check) => expect(meta.QUOTATION_BLOCKING_CHECKS.has(check)).toBe(false));
  });

  it('is empty for a complete quotation', () => {
    expect(meta.buildQuotationChecklist(complete)).toEqual([]);
  });

  it('BLOCKS on a missing ผู้สั่งซื้อ, with the backend\'s own wording, and targets the picker', () => {
    const entries = meta.buildQuotationChecklist({ ...complete, contact: null });
    expect(blocking(entries)).toEqual(['contact']);
    expect(entries[0]).toMatchObject({ message: 'กรุณาระบุผู้สั่งซื้อ', targetId: 'quotation-contact' });
  });

  it('does NOT block on a missing ที่อยู่ — it is a warning that targets the address field', () => {
    const entries = meta.buildQuotationChecklist({ ...complete, customer: { ...customer, address: '' } });
    expect(blocking(entries)).toEqual([]);
    expect(entries).toEqual([{ check: 'customerAddress', message: 'ยังไม่ได้กรอกที่อยู่ลูกค้า', targetId: 'deal-customer-address', blocking: false }]);
  });

  it('does NOT block on a missing เลขที่ผู้เสียภาษี (F7: a customer without one stays quotable), nor on any phone/email', () => {
    const entries = meta.buildQuotationChecklist({
      ...complete,
      customer: { ...customer, taxId: null, phone: '  ' },
      contact: { ...contact, phone: null, email: '' },
    });
    expect(blocking(entries)).toEqual([]);
    expect(warnings(entries)).toEqual(['customerTaxId', 'customerPhone', 'contactPhone', 'contactEmail']);
  });

  it('raises nothing for a value that is merely UNKNOWN yet (undefined), only for a known-empty one', () => {
    const entries = meta.buildQuotationChecklist({
      ...complete, customer: { id: 5, name: 'x' }, contact: { id: 6, firstName: 'y' }, projectName: undefined,
    });
    expect(entries).toEqual([]);
  });

  it('on the inline path BLOCKS on ลูกค้า and โครงการ, targeting their controls', () => {
    const entries = meta.buildQuotationChecklist({ ...complete, isInlineCreate: true, customer: null, hasProject: false, contactFieldId: 'deal-contact' });
    expect(entries.slice(0, 2)).toEqual([
      { check: 'customer', message: 'ต้องเลือกลูกค้าก่อนบันทึกร่าง', targetId: 'deal-customer', blocking: true },
      { check: 'project', message: 'ต้องเลือกโครงการก่อนบันทึกร่าง', targetId: 'deal-project', blocking: true },
    ]);
  });

  it('on a deal with no โครงการ only WARNS — the quotation service does not refuse it, and it is not editable here', () => {
    const entries = meta.buildQuotationChecklist({ ...complete, projectName: null });
    expect(entries).toEqual([{ check: 'dealProject', message: 'ดีลนี้ยังไม่มีโครงการ (แก้ได้ที่หน้ารายละเอียดดีล)', targetId: null, blocking: false }]);
  });

  it('BLOCKS on an incomplete row and targets its FIRST missing field', () => {
    const tileErrors = meta.validateQuotationItem({ model: 'x', color: 'y', texture: 'z', sizeText: '60x60' });
    const entries = meta.buildQuotationChecklist({
      ...complete,
      items: [{ lineType: 'TILE' }, { lineType: 'PLAIN' }],
      itemErrorsByRow: [tileErrors, { unit: 'กรุณาเลือกหน่วย' }],
      adjustments: [{ lineType: 'ADJUSTMENT', adjustmentKind: 'PERCENT' }],
      adjustmentErrorsByRow: [{ adjustmentPct: 'กรุณาระบุเปอร์เซ็นต์ส่วนลด' }],
    });
    expect(entries.map((e) => [e.blocking, e.targetId, e.message])).toEqual([
      [true, 'thickness-0', 'รายการที่ 1: ขาด ความหนา, แผ่น/ตร.ม., แผ่น/กล่อง, ราคา/หน่วย, จำนวน (พื้นที่)'],
      [true, 'plain-unit-1', 'รายการที่ 2: ขาด หน่วย'],
      [true, 'adj-pct-2', 'รายการที่ 3: ขาด เปอร์เซ็นต์ส่วนลด'],
    ]);
  });

  it('BLOCKS a quotation with no product rows, and says why when only a ส่วนลดพิเศษ is left', () => {
    expect(meta.buildQuotationChecklist({ ...complete, items: [], itemErrorsByRow: [] }).map((e) => e.message))
      .toEqual(['ต้องมีรายการสินค้าอย่างน้อย 1 รายการ']);
    expect(meta.buildQuotationChecklist({ ...complete, items: [], adjustments: [{}] }).map((e) => e.message))
      .toEqual(['ส่วนลดพิเศษต้องมีรายการสินค้าอย่างน้อย 1 รายการอยู่ด้านบน']);
  });

  it('BLOCKS the English + ราคาพิเศษ pairing the server 400s', () => {
    expect(blocking(meta.buildQuotationChecklist({ ...complete, priceModeLanguageConflict: true }))).toEqual(['priceModeLanguage']);
  });
});

describe('joinPresent', () => {
  it('joins only the present parts, so a missing one leaves no dangling separator', () => {
    expect(meta.joinPresent(['คุณธนพล', null, 'a@b.co'])).toBe('คุณธนพล · a@b.co');
    expect(meta.joinPresent(['คุณธนพล', '', '  '])).toBe('คุณธนพล');
    expect(meta.joinPresent([null, undefined])).toBe('');
  });
});
