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
  hasSpecialPricing,
  isDealQuotationEditable,
  isDealQuotationReadOnlyViewer,
  LINE_TYPE_ADJUSTMENT,
  LINE_TYPE_PLAIN,
  LINE_TYPE_TILE,
  listPricePerSqmIncVat,
  parseSizeText,
  piecesPerSqmFromSqmPerPiece,
  quotationItemMissingSummary,
  remainderModeLabel,
  sqmPerPieceFromPiecesPerSqm,
  sqmPerPieceFromSizeCm,
  sizeTextMatchesCatalogFaceSize,
  sizeTextDiffersFromCatalogFaceSize,
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
    expect(dealQuotationStatusLabel('SUPERSEDED')).toEqual({ label: 'ฉบับที่ไม่ได้ใช้แล้ว', tone: 'neutral' });
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

  // Owner feedback #7 (2026-09-14): mirrors DealQuotationService#requireEveryTileItemHasALeadTime
  // -- SUBMIT-only. `requireLeadTime` defaults to OFF specifically so a draft may still be saved
  // with no lead time; only a caller that explicitly opts in (QuotationEditorPage's
  // `submitItemErrorsByRow`) sees it.
  describe('requireLeadTime option (owner feedback #7, 2026-09-14)', () => {
    it('is never flagged by default, even with no lead time at all', () => {
      const item = completeItem({ leadTimeMinDays: null, leadTimeMaxDays: null });
      expect(validateQuotationItem(item)).toEqual({});
    });

    it('flags a missing lead time only when explicitly required', () => {
      const item = completeItem({ leadTimeMinDays: null, leadTimeMaxDays: null });
      expect(validateQuotationItem(item, 'NET', 'TH', { requireLeadTime: true }))
        .toEqual({ leadTimeMinDays: 'กรุณาระบุระยะเวลานำเข้า (วัน)' });
    });

    it('a partially-filled lead time (only one of the two numbers) is still flagged', () => {
      const item = completeItem({ leadTimeMinDays: 30, leadTimeMaxDays: null });
      expect(validateQuotationItem(item, 'NET', 'TH', { requireLeadTime: true }))
        .toEqual({ leadTimeMinDays: 'กรุณาระบุระยะเวลานำเข้า (วัน)' });
    });

    it('a fully-filled lead time passes even when required', () => {
      const item = completeItem({ leadTimeMinDays: 30, leadTimeMaxDays: 45 });
      expect(validateQuotationItem(item, 'NET', 'TH', { requireLeadTime: true })).toEqual({});
    });

    it('a zero lead time (an exact same-day range) is a valid value, not a missing one', () => {
      const item = completeItem({ leadTimeMinDays: 0, leadTimeMaxDays: 0 });
      expect(validateQuotationItem(item, 'NET', 'TH', { requireLeadTime: true })).toEqual({});
    });
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

describe('listPricePerSqmIncVat (Thai SPECIAL_SQM ราคาตั้ง shown per ตร.ม., owner feedback 2026-09-14)', () => {
  // The four real-document figures the owner gave, each round2(unitPrice x piecesPerSqm x 1.07)
  // where piecesPerSqm is piecesPerSqmFromSqmPerPiece(sqmPerPiece) -- the SAME reciprocal exercised
  // above, never a second one.
  it('reproduces all four owner figures exactly', () => {
    expect(listPricePerSqmIncVat(1299.0, 0.72)).toBe(1932.0);
    expect(listPricePerSqmIncVat(881.46, 0.36)).toBe(2621.99);
    expect(listPricePerSqmIncVat(843.14, 0.36)).toBe(2508.0);
    expect(listPricePerSqmIncVat(900.63, 0.36)).toBe(2679.01);
  });

  it('returns null when unitPrice is missing, zero or negative', () => {
    expect(listPricePerSqmIncVat(null, 0.36)).toBeNull();
    expect(listPricePerSqmIncVat('', 0.36)).toBeNull();
    expect(listPricePerSqmIncVat(0, 0.36)).toBeNull();
    expect(listPricePerSqmIncVat(-1, 0.36)).toBeNull();
  });

  it('returns null when sqmPerPiece is missing, zero or negative', () => {
    expect(listPricePerSqmIncVat(1299.0, null)).toBeNull();
    expect(listPricePerSqmIncVat(1299.0, '')).toBeNull();
    expect(listPricePerSqmIncVat(1299.0, 0)).toBeNull();
    expect(listPricePerSqmIncVat(1299.0, -0.36)).toBeNull();
  });
});

describe('sqmPerPieceFromSizeCm (ขนาด (ซม.) → ตร.ม./แผ่น fallback, owner decision 2026-09-14)', () => {
  it('parses widthXheight (optional whitespace/case/unit) into cm² / 10000, rounded 6dp', () => {
    expect(sqmPerPieceFromSizeCm('60x120')).toBe(0.72);
    expect(sqmPerPieceFromSizeCm('60 x 60')).toBe(0.36);
    expect(sqmPerPieceFromSizeCm('60×120 cm')).toBe(0.72);
    expect(sqmPerPieceFromSizeCm('60x120 ซม.')).toBe(0.72);
    expect(sqmPerPieceFromSizeCm('7.5x30')).toBe(0.0225);
    expect(sqmPerPieceFromSizeCm('30*60')).toBe(0.18);
  });

  it('ignores an optional third `x thickness` segment', () => {
    expect(sqmPerPieceFromSizeCm('60X60x0.9')).toBe(0.36);
  });

  it('returns null for anything that is not a clean widthXheight[Xthickness] pair', () => {
    expect(sqmPerPieceFromSizeCm('')).toBeNull();
    expect(sqmPerPieceFromSizeCm(null)).toBeNull();
    expect(sqmPerPieceFromSizeCm('60')).toBeNull();
    expect(sqmPerPieceFromSizeCm('1,2X20 JOLLY COCO')).toBeNull();
    expect(sqmPerPieceFromSizeCm('JOLLY 60x60')).toBeNull();
    expect(sqmPerPieceFromSizeCm('60x')).toBeNull();
    expect(sqmPerPieceFromSizeCm('abc')).toBeNull();
  });

  it('returns null for a non-positive dimension', () => {
    expect(sqmPerPieceFromSizeCm('0x60')).toBeNull();
  });

  it('returns null outside the WastageCalculator MIN/MAX_SQM_PER_PIECE bound (0.001-10 m² per piece) -- catches millimetres typed into the cm field', () => {
    // "600x1200" parses as a clean number pair but reads as 72 m²/piece, ~100x a real tile --
    // exactly the millimetres-in-a-cm-field mistake WastageCalculator.java:87-88 guards against.
    expect(sqmPerPieceFromSizeCm('600x1200')).toBeNull();
  });
});

// ── SHARED GRAMMAR vector table (owner complaint re-reported 2026-09-16, "แก้ขนาด/รหัสสินค้าเอง แต่
// PDF ยังใช้ค่าเดิม") -- this table's inputs and expected {width, height, unit} results are ALSO
// asserted, verbatim, in backend/src/test/java/.../DealQuotationLinesTest.java's own
// "SHARED GRAMMAR vector table" section, against `DealQuotationLines#parseTwoDimensions`. The two
// must never drift apart again without both going red.
describe('parseSizeText (shared size grammar, 2026-09-16)', () => {
  it('basic separators and case', () => {
    expect(parseSizeText('30x60')).toEqual({ width: 30, height: 60, unit: null });
    expect(parseSizeText('30*60')).toEqual({ width: 30, height: 60, unit: null });
    expect(parseSizeText('30 X 60')).toEqual({ width: 30, height: 60, unit: null });
    expect(parseSizeText('30×60')).toEqual({ width: 30, height: 60, unit: null });
  });

  it('english units, per-number or trailing', () => {
    expect(parseSizeText('30x60cm')).toEqual({ width: 30, height: 60, unit: 'cm' });
    expect(parseSizeText('30 cm x 60 cm')).toEqual({ width: 30, height: 60, unit: 'cm' });
    expect(parseSizeText('300x600mm')).toEqual({ width: 300, height: 600, unit: 'mm' });
    expect(parseSizeText('600x600mm')).toEqual({ width: 600, height: 600, unit: 'mm' });
  });

  it('thai units (ซม / ซม. / ซ.ม.)', () => {
    expect(parseSizeText('30x60 ซม.')).toEqual({ width: 30, height: 60, unit: 'cm' });
    expect(parseSizeText('30ซม.x60ซม.')).toEqual({ width: 30, height: 60, unit: 'cm' });
    expect(parseSizeText('30x60 ซ.ม.')).toEqual({ width: 30, height: 60, unit: 'cm' });
  });

  it('decimal comma, ignored third dimension, ignored trailing parenthetical', () => {
    // "29,7" is 29.7 -- decimal comma, not a thousands separator.
    expect(parseSizeText('29,7x59,7')).toEqual({ width: 29.7, height: 59.7, unit: null });
    // Third dimension (thickness) ignored, never a second dimension pair.
    expect(parseSizeText('30x60x1')).toEqual({ width: 30, height: 60, unit: null });
    expect(parseSizeText('60X60x0.9')).toEqual({ width: 60, height: 60, unit: null });
    // Trailing free text in parentheses ignored.
    expect(parseSizeText('30x60 (หนา 9)')).toEqual({ width: 30, height: 60, unit: null });
  });

  it('unparseable', () => {
    expect(parseSizeText('รูปทรงอิสระ')).toBeNull();
    expect(parseSizeText('60x')).toBeNull();
    expect(parseSizeText('JOLLY 60x60')).toBeNull();
    expect(parseSizeText('1,2X20 JOLLY COCO')).toBeNull();
    expect(parseSizeText('0x60')).toBeNull();
    expect(parseSizeText('')).toBeNull();
    expect(parseSizeText(null)).toBeNull();
  });

  // ── F2 (HIGH, 2026-09-16 review): Unicode whitespace normalisation. JS's `\s` is Unicode-aware
  // and its own `.trim()`/`\s` already treat NBSP etc. as whitespace, while Java's `\s` is
  // ASCII-only and `String.trim()` strips only <= U+0020 -- so the two engines disagreed on every
  // character in this class (in one direction or the other). Both must now agree, via a shared
  // pre-fold rather than trying to reconcile two different `\s` definitions inside the pattern
  // itself. The SAME vectors are pinned in DealQuotationLinesTest.java's own "F2" section, against
  // `DealQuotationLines#parseTwoDimensions` -- same inputs, same result, on both sides. ───────────
  it('agrees with the backend on every measured whitespace divergence', () => {
    expect(parseSizeText('30 x 60')).toEqual({ width: 30, height: 60, unit: null }); // NBSP
    expect(parseSizeText('30x60 cm')).toEqual({ width: 30, height: 60, unit: 'cm' });
    expect(parseSizeText('30x60　cm')).toEqual({ width: 30, height: 60, unit: 'cm' }); // ideographic space
    expect(parseSizeText('30 x 60')).toEqual({ width: 30, height: 60, unit: null }); // thin space
    expect(parseSizeText('30 x 60')).toEqual({ width: 30, height: 60, unit: null }); // narrow NBSP
    expect(parseSizeText('﻿30x60')).toEqual({ width: 30, height: 60, unit: null }); // BOM/ZWNBSP
    expect(parseSizeText('30x60\u2028')).toEqual({ width: 30, height: 60, unit: null }); // line separator
    // The OTHER direction: a bare control byte, which JS's own `\s`/`.trim()` never treated as
    // whitespace (Java's `String.trim()` always stripped it -- this closes the gap from the JS side).
    expect(parseSizeText('30x60')).toEqual({ width: 30, height: 60, unit: null });
    expect(parseSizeText('30x60')).toEqual({ width: 30, height: 60, unit: null });
  });
});

// ── F1 (BLOCKER, 2026-09-16 review): catastrophic regex backtracking (ReDoS). Both timing vectors
// below must stay well under 50ms; a regression in either the grammar fix or the length guard alone
// would blow one of them up (the first is short enough to bypass the guard entirely and exercises
// the grammar fix in isolation; the second is the reviewer's own reported shape, which also
// exercises MAX_SIZE_TEXT_LENGTH). Measured on this exact (pre-fix) code: 389ms / 3,357ms at
// 128 / 248 chars (the reviewer's own run measured 130ms / 4,092ms). Same vectors pinned in
// DealQuotationLinesTest.java's own "F1" section. ──────────────────────────────────────────────
describe('parseSizeText ReDoS guard (F1, 2026-09-16 review)', () => {
  it('pathological whitespace under the 64-char length guard does not catastrophically backtrack', () => {
    const attack = '30' + ' '.repeat(18) + 'x60' + ' '.repeat(18) + 'x1' + ' '.repeat(18) + '!';
    expect(attack.length).toBeLessThanOrEqual(64);
    const start = performance.now();
    const result = parseSizeText(attack);
    const elapsedMs = performance.now() - start;
    expect(result).toBeNull();
    expect(elapsedMs).toBeLessThan(50);
  });

  it("the reviewer's own 248-char pathological shape does not catastrophically backtrack", () => {
    const attack = '30' + ' '.repeat(80) + 'x60' + ' '.repeat(80) + 'x1' + ' '.repeat(80) + '!';
    expect(attack.length).toBe(248);
    const start = performance.now();
    const result = parseSizeText(attack);
    const elapsedMs = performance.now() - start;
    expect(result).toBeNull();
    expect(elapsedMs).toBeLessThan(50);
  });

  it('longer than the 64-char length guard is rejected outright, even for an otherwise-genuine shape', () => {
    // The padding is INTERNAL (between the first number and the separator), so no trimming step
    // could shrink it away -- this pins the length guard itself, not just the regex fix.
    const genuineButLong = '30' + ' '.repeat(60) + 'x60';
    expect(genuineButLong.length).toBe(65);
    expect(parseSizeText(genuineButLong)).toBeNull();
  });
});

// ── The owner's exact 2026-09-16 bug: typed "30x60x1" against a catalog-linked 60x60 row must
// recompute แผ่น/ตร.ม. from the TYPED size (0.18), not silently keep the catalogue's 0.36 -- the old
// SIZE_CM_PATTERN already tolerated a third dimension, so this specific vector was never broken on
// the frontend; it is pinned here anyway because it is the exact pairing that exposed the
// backend/frontend disagreement (backend printed the catalogue while this recomputed from the typed
// text -- see the matching backend test for the PDF-side half of the bug). ─────────────────────────
describe('sqmPerPieceFromSizeCm / sizeTextDiffersFromCatalogFaceSize agree on the 2026-09-16 bug pairing', () => {
  it('"30x60x1" against a catalogSizeText of "60x60" recomputes 0.18 and is confirmed different', () => {
    expect(sqmPerPieceFromSizeCm('30x60x1')).toBe(0.18);
    expect(sizeTextDiffersFromCatalogFaceSize('30x60x1', '60x60')).toBe(true);
  });

  it('an explicit mm unit is trusted for area, not compared against the cm sanity bound', () => {
    // 300mm x 600mm = 0.18 sqm/piece -- a real, small tile; the cm reading (300x600) would be 18
    // sqm/piece and get rejected by the sanity bound, which is exactly the bug this unit-aware
    // conversion avoids.
    expect(sqmPerPieceFromSizeCm('300x600mm')).toBe(0.18);
  });

  it('explicit unit wins: "300x600mm" is a different tile than a 60x60cm catalogue row', () => {
    expect(sizeTextDiffersFromCatalogFaceSize('300x600mm', '60x60')).toBe(true);
  });

  it('explicit unit wins the other way too: "600x600mm" still matches a 60x60cm catalogue row', () => {
    expect(sizeTextDiffersFromCatalogFaceSize('600x600mm', '60x60')).toBe(false);
  });

  /**
   * F5-style bonus (2026-09-16 review): the two vectors above never distinguish an explicit unit
   * from "no unit, check both readings" -- both happen to land on the same reading regardless (the
   * backend's own equivalent test had this exact vacuity, see DealQuotationLinesTest's F5 section).
   * This one does: catalogue 30cm x 60cm, typed "60x30mm" -- an explicit MM reading that matches
   * NEITHER the catalogue's mm figures (300,600) NOR its cm figures directly (30,60), but DOES match
   * the catalogue's cm figures order-swapped (60==60, 30==30) if the explicit unit were ignored and
   * both readings checked anyway. Mutation-checked the same way as the backend test: forcing the
   * typed `unit` to `null` in `compareToCatalogFaceSize` turns this red (it wrongly reads as
   * "matches"); the real unit resolution turns it green.
   */
  it('pins unit resolution -- mutation-discriminating (F5-style), unlike the two vectors above', () => {
    expect(sizeTextDiffersFromCatalogFaceSize('60x30mm', '30x60')).toBe(true);
  });
});

describe('sizeTextMatchesCatalogFaceSize (prod QT-2026-0034-1, 2026-09-15 — mirrors DealQuotationLines#sizeLine\'s REFINEMENT)', () => {
  it('matches when the typed size equals the catalogue size exactly', () => {
    expect(sizeTextMatchesCatalogFaceSize('60x60', '60x60')).toBe(true);
  });

  it('matches order-insensitively', () => {
    expect(sizeTextMatchesCatalogFaceSize('120x60', '60x120')).toBe(true);
  });

  it('matches when the typed size is the catalogue size in millimetres (the exact prod bug shape)', () => {
    // catalogue was picked as 60x60 cm (a 600x600mm tile); the rep typed the mm figures straight
    // into this cm-labelled field.
    expect(sizeTextMatchesCatalogFaceSize('600x600', '60x60')).toBe(true);
    expect(sizeTextMatchesCatalogFaceSize('600x1200', '60x120')).toBe(true);
    expect(sizeTextMatchesCatalogFaceSize('1200x600', '60x120')).toBe(true);
  });

  it('does NOT match a genuinely different size -- the exact prod bug\'s two lines', () => {
    expect(sizeTextMatchesCatalogFaceSize('30x60', '60x60')).toBe(false);
    expect(sizeTextMatchesCatalogFaceSize('3x60', '60x60')).toBe(false);
  });

  it('tolerates spacing, separators and a trailing unit', () => {
    expect(sizeTextMatchesCatalogFaceSize('60 x 60 cm', '60x60')).toBe(true);
    expect(sizeTextMatchesCatalogFaceSize('60×60', '60x60')).toBe(true);
  });

  it('is false whenever either side fails to parse as a plain size pair', () => {
    expect(sizeTextMatchesCatalogFaceSize('รูปทรงอิสระ', '60x60')).toBe(false);
    expect(sizeTextMatchesCatalogFaceSize('60x60', '')).toBe(false);
    expect(sizeTextMatchesCatalogFaceSize('', '60x60')).toBe(false);
    expect(sizeTextMatchesCatalogFaceSize(null, null)).toBe(false);
  });
});

// Review fix (2026-09-15): sizeTextMatchesCatalogFaceSize's negation is NOT the right predicate
// for "should the editor recompute แผ่น/ตร.ม.?" -- it reads "can't tell" (unparseable) the same as
// "confirmed different". sizeTextDiffersFromCatalogFaceSize is the three-way-aware predicate that
// only fires on a CONFIRMED difference, mirroring DealQuotationLines#sizeLine's own FALLBACK rule
// (a blank/unparseable typed size keeps the catalogue dims, never treated as "different").
describe('sizeTextDiffersFromCatalogFaceSize (review fix, 2026-09-15 -- "can\'t tell" must never read as "different")', () => {
  it('is true only when both sides parse AND genuinely differ -- the exact prod bug\'s two lines', () => {
    expect(sizeTextDiffersFromCatalogFaceSize('30x60', '60x60')).toBe(true);
    expect(sizeTextDiffersFromCatalogFaceSize('3x60', '60x60')).toBe(true);
  });

  it('is false when the sizes match, in cm or mm, order-insensitive', () => {
    expect(sizeTextDiffersFromCatalogFaceSize('60x60', '60x60')).toBe(false);
    expect(sizeTextDiffersFromCatalogFaceSize('600x600', '60x60')).toBe(false);
    expect(sizeTextDiffersFromCatalogFaceSize('120x60', '60x120')).toBe(false);
  });

  it('is false ("not confirmed different") whenever either side fails to parse -- the exact defect this fixes', () => {
    // A clearing edit, or any unparseable intermediate mid-retype ("3", "30x").
    expect(sizeTextDiffersFromCatalogFaceSize('', '60x60')).toBe(false);
    expect(sizeTextDiffersFromCatalogFaceSize('3', '60x60')).toBe(false);
    expect(sizeTextDiffersFromCatalogFaceSize('30x', '60x60')).toBe(false);
    // catalogSizeText itself unparseable (a catalogue row with no width/height, sizeTextFromCatalog's
    // sizeRaw fallback) -- even a genuinely different-looking typed size must not read as "different"
    // when there is nothing reliable to compare it against.
    expect(sizeTextDiffersFromCatalogFaceSize('30x60', 'JOLLY COCO 60x120')).toBe(false);
    expect(sizeTextDiffersFromCatalogFaceSize(null, null)).toBe(false);
  });
});

// ── F3 (MEDIUM-LOW, 2026-09-16 review): the catalogue side of the comparison can carry its own
// unit token (sizeTextFromCatalog's sizeRaw/size fallback, ~49 prod rows with no width_mm/
// height_mm) and must honour it rather than reading the digits as bare cm -- see parseSizeCmPair's
// own doc for the false claim this replaces and the "600x1200 mm read as 600cm x 1200cm" bug. ────
describe('parseSizeCmPair / catalogue-side unit honouring (F3, 2026-09-16 review)', () => {
  it('an explicit mm unit on the CATALOGUE side is converted to its actual cm face size, not read as bare cm digits', () => {
    // Pre-fix bug: reading "600x1200 mm" as literal cm digits (600,1200) made a rep's correctly
    // typed "60x120" (the tile's REAL cm size) fail to match its own catalogue row.
    expect(sizeTextMatchesCatalogFaceSize('60x120', '600x1200 mm')).toBe(true);
    // A genuinely different tile must still read as different.
    expect(sizeTextMatchesCatalogFaceSize('30x60', '600x1200 mm')).toBe(false);
  });

  it('a junk size_raw fallback (not a size at all) is a parse failure, never a wrong reading', () => {
    expect(sizeTextMatchesCatalogFaceSize('60x120', 'JOLLY COCO 60x120')).toBe(false);
    expect(sizeTextDiffersFromCatalogFaceSize('60x120', 'JOLLY COCO 60x120')).toBe(false);
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
      .toEqual(['ทั้งหมด', 'รออนุมัติ', 'ฉบับแก้', 'ยกเลิก', 'อนุมัติแล้ว']);
  });

  it('drives ฉบับแก้ off needsRework, not a docStatus', () => {
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

  it('offers SPECIAL_SQM on an English document as a USD/ตร.ม. price (owner decision 2026-09-13)', () => {
    const en = meta.availablePriceModes('EN');
    expect(en.map((o) => o.code)).toEqual(['NET', 'SPECIAL_SQM', 'DIRECT_NET']);
    expect(en.find((o) => o.code === 'SPECIAL_SQM').label).toBe('ราคา USD/ตร.ม.');
    expect(en.find((o) => o.code === 'SPECIAL_SQM').hint).toMatch(/USD.*ไม่มี VAT.*กล่อง × ตร\.ม\.\/กล่อง/);
    // Thai keeps its own label, untouched.
    expect(meta.availablePriceModes('TH').find((o) => o.code === 'SPECIAL_SQM').label).toBe('ราคาพิเศษ บาท/ตร.ม.');
  });

  it('rowsWithPricesCleared clears EVERY currency amount to null (not \'\') — and keeps every non-money value', () => {
    const tileRow = {
      lineType: 'TILE', unitPrice: 64, directNetPrice: 500, specialPriceSqm: 64, discountPct: 5, sqmPerBox: 0.6,
      piecesPerBox: 28, areaSqm: 20, piecesInput: 3360, wastageValue: 5, sqmPerPiece: 0.36,
      netUnitPrice: 64, lineAmount: 4608, specialPriceLine: '(1 box = 28 pcs = 0.6 sqm)',
    };
    const plain = { lineType: 'PLAIN', unitPrice: 800, quantity: 1, unit: 'JOB', discountPct: 10, netUnitPrice: 720, lineAmount: 720 };
    const flat = { lineType: 'ADJUSTMENT', adjustmentKind: 'AMOUNT', adjustmentAmount: 55, adjustmentPct: null, unitPrice: 55, netUnitPrice: 55, lineAmount: -55, description: 'Rebate' };
    const pct = { lineType: 'ADJUSTMENT', adjustmentKind: 'PERCENT', adjustmentPct: 3, adjustmentAmount: '', adjustmentDeadline: '2026-07-31', unitPrice: 139.29, netUnitPrice: 139.29, lineAmount: -139.29 };
    const [t, p, f, a] = meta.rowsWithPricesCleared([tileRow, plain, flat, pct]);
    for (const key of ['unitPrice', 'directNetPrice', 'specialPriceSqm', 'netUnitPrice', 'lineAmount', 'specialPriceLine']) {
      expect(t[key], `tile ${key}`).toBeNull();
    }
    expect(t).toMatchObject({ discountPct: 5, sqmPerBox: 0.6, piecesPerBox: 28, areaSqm: 20, piecesInput: 3360, wastageValue: 5, sqmPerPiece: 0.36 });
    expect(p.unitPrice).toBeNull();
    expect(p.netUnitPrice).toBeNull();
    expect(p.lineAmount).toBeNull();
    expect(p).toMatchObject({ quantity: 1, unit: 'JOB', discountPct: 10 });
    expect(f.adjustmentAmount).toBeNull();
    expect(f.lineAmount).toBeNull();
    expect(f.description).toBe('Rebate');
    // A PERCENTAGE adjustment keeps its percent (not money) and loses only the derived figures.
    expect(a).toMatchObject({ adjustmentPct: 3, adjustmentAmount: '', adjustmentDeadline: '2026-07-31' });
    expect(a.lineAmount).toBeNull();
    // A pre-V168 row with no lineType is a TILE.
    expect(meta.rowsWithPricesCleared([{ unitPrice: 790 }])[0].unitPrice).toBeNull();
  });

  it('rowHasPriceForPreview asks for the price each mode actually needs', () => {
    expect(meta.rowHasPriceForPreview({ unitPrice: 850 }, 'NET')).toBe(true);
    expect(meta.rowHasPriceForPreview({ unitPrice: null }, 'NET')).toBe(false);
    expect(meta.rowHasPriceForPreview({ unitPrice: '' }, 'NET')).toBe(false);
    expect(meta.rowHasPriceForPreview({ unitPrice: null, directNetPrice: 500 }, 'DIRECT_NET')).toBe(true);
    expect(meta.rowHasPriceForPreview({ unitPrice: 850, directNetPrice: null }, 'DIRECT_NET')).toBe(false);
    expect(meta.rowHasPriceForPreview({ unitPrice: 850, specialPriceSqm: 1350 }, 'SPECIAL_SQM', 'TH')).toBe(true);
    expect(meta.rowHasPriceForPreview({ unitPrice: null, specialPriceSqm: 1350 }, 'SPECIAL_SQM', 'TH')).toBe(false);
    expect(meta.rowHasPriceForPreview({ unitPrice: 850, specialPriceSqm: null }, 'SPECIAL_SQM', 'TH')).toBe(false);
    expect(meta.rowHasPriceForPreview({ unitPrice: null, specialPriceSqm: 64 }, 'SPECIAL_SQM', 'EN')).toBe(true);
    expect(meta.rowHasPriceForPreview({ lineType: 'PLAIN', unitPrice: null }, 'SPECIAL_SQM')).toBe(false);
    expect(meta.rowHasPriceForPreview({ lineType: 'PLAIN', unitPrice: 3500 }, 'NET')).toBe(true);
  });

  it('a language switch no longer moves any price mode', () => {
    expect(meta.priceModeForLanguage('SPECIAL_SQM', 'EN')).toEqual({ priceMode: 'SPECIAL_SQM', moved: false });
    expect(meta.priceModeForLanguage('NET', 'EN')).toEqual({ priceMode: 'NET', moved: false });
    expect(meta.priceModeForLanguage('SPECIAL_SQM', 'TH')).toEqual({ priceMode: 'SPECIAL_SQM', moved: false });
    expect(meta.priceModeForLanguage('BOGUS', 'EN')).toEqual({ priceMode: 'DIRECT_NET', moved: true });
  });

  it('isEnglishPerSqm is exactly SPECIAL_SQM on English', () => {
    expect(meta.isEnglishPerSqm('SPECIAL_SQM', 'EN')).toBe(true);
    expect(meta.isEnglishPerSqm('SPECIAL_SQM', 'TH')).toBe(false);
    expect(meta.isEnglishPerSqm('NET', 'EN')).toBe(false);
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

  it('prints พิเศษ for a SPECIAL_SQM tile, and Net for the English per-sqm row (the USD/sqm IS the net)', () => {
    expect(meta.documentDiscountLabel({ lineType: 'TILE' }, 'SPECIAL_SQM', 'TH')).toBe('พิเศษ');
    expect(meta.documentDiscountLabel({ lineType: 'TILE' }, 'SPECIAL_SQM', 'EN')).toBe('Net');
    expect(meta.documentDiscountLabel({ lineType: 'TILE', unitPrice: 600, netUnitPrice: 500 }, 'DIRECT_NET', 'EN')).toBe('Special');
  });

  it('prints Net for a DIRECT_NET tile whose net equals its list price, พิเศษ when it differs', () => {
    expect(meta.documentDiscountLabel({ lineType: 'TILE', unitPrice: 500, netUnitPrice: 500 }, 'DIRECT_NET')).toBe('Net');
    expect(meta.documentDiscountLabel({ lineType: 'TILE', unitPrice: 600, netUnitPrice: 500 }, 'DIRECT_NET')).toBe('พิเศษ');
  });

  it('prints N% or Net for a PLAIN row from its own discount', () => {
    expect(meta.documentDiscountLabel({ lineType: 'PLAIN', discountPct: 0 }, 'SPECIAL_SQM')).toBe('Net');
    expect(meta.documentDiscountLabel({ lineType: 'PLAIN', discountPct: 5 }, 'NET')).toBe('5%');
  });

  it('composes the ENGLISH discount preview with a month name and a Gregorian year (owner ruling 2026-09-13)', () => {
    expect(meta.adjustmentDescriptionPreview({ adjustmentKind: 'PERCENT', adjustmentPct: 3, adjustmentDeadline: '2026-07-31' }, 'EN'))
      .toBe('Special discount 3% for orders placed by July 31, 2026');
    expect(meta.adjustmentDescriptionPreview({ adjustmentKind: 'PERCENT', adjustmentPct: 2.5, adjustmentDeadline: '2026-01-05' }, 'EN'))
      .toBe('Special discount 2.5% for orders placed by January 5, 2026');
    expect(meta.adjustmentDescriptionPreview({ adjustmentKind: 'PERCENT', adjustmentPct: 3, adjustmentDeadline: '' }, 'EN'))
      .toBe('Special discount 3%');
    expect(meta.adjustmentDescriptionPreview({ adjustmentKind: 'AMOUNT', adjustmentPct: 9 }, 'EN')).toBe('Special discount');
    // A rep's own wording on a flat adjustment is kept, in either language.
    expect(meta.adjustmentDescriptionPreview({ adjustmentKind: 'AMOUNT', description: 'Loyalty rebate' }, 'EN'))
      .toBe('Loyalty rebate');
  });

  it('keeps the Thai preview when the language is TH or omitted', () => {
    const adjustment = { adjustmentKind: 'PERCENT', adjustmentPct: 3, adjustmentDeadline: '2026-07-31' };
    expect(meta.adjustmentDescriptionPreview(adjustment, 'TH')).toBe('ส่วนลดพิเศษ 3% สำหรับการสั่งซื้อภายใน 31/07/2569');
    expect(meta.adjustmentDescriptionPreview(adjustment)).toBe('ส่วนลดพิเศษ 3% สำหรับการสั่งซื้อภายใน 31/07/2569');
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

  it('English per-sqm needs the USD/ตร.ม. and ตร.ม./กล่อง — and NOT a list price per piece', () => {
    const perSqm = { ...tile, unitPrice: '', specialPriceSqm: 64, sqmPerBox: 0.6 };
    expect(meta.validateQuotationItem(perSqm, 'SPECIAL_SQM', 'EN')).toEqual({});
    expect(meta.validateQuotationItem({ ...perSqm, sqmPerBox: null }, 'SPECIAL_SQM', 'EN')).toEqual({ sqmPerBox: 'กรุณาระบุ ตร.ม./กล่อง' });
    expect(meta.validateQuotationItem({ ...perSqm, specialPriceSqm: '' }, 'SPECIAL_SQM', 'EN')).toEqual({ specialPriceSqm: 'กรุณาระบุราคา (USD/ตร.ม.)' });
    expect(meta.validateQuotationItem({ ...perSqm, piecesPerBox: '' }, 'SPECIAL_SQM', 'EN').piecesPerBox).toBeTruthy();
    expect(meta.validateQuotationItem({ ...perSqm, sqmPerBox: 0.1234567 }, 'SPECIAL_SQM', 'EN').sqmPerBox).toBe('ทศนิยมได้ไม่เกิน 6 ตำแหน่ง');
    // The same row in THAI ราคาพิเศษ still needs its list price, and never asks for ตร.ม./กล่อง.
    expect(meta.validateQuotationItem({ ...perSqm, sqmPerBox: null }, 'SPECIAL_SQM', 'TH')).toEqual({ unitPrice: 'กรุณาระบุราคาตั้ง (บาท/แผ่น)' });
    expect(meta.quotationItemMissingSummary({ sqmPerBox: 'x', specialPriceSqm: 'y' }, 0)).toBe('รายการที่ 1: ขาด ตร.ม./กล่อง, ราคาต่อ ตร.ม.');
  });

  // Review fix F1 (2026-09-16): validateQuotationItem used to flag `roundToFullBox === false` here
  // as "defence in depth" against DealQuotationService#requireBoxDataForPerSqm's 400. That claim
  // was false (itemInputFromRow already forces `roundToFullBox: true` onto the wire in this mode
  // regardless of the row's own state — see quotationItemInput.test.jsx), and the branch was
  // actively harmful: it permanently blocked บันทึกร่าง/ส่งขออนุมัติ for a row ticked under NET/TH
  // and then switched to English per-sqm, with no on-screen control left to un-tick it (the
  // checkbox is disabled AND unchecked in this mode). Fixed at the source instead —
  // QuotationEditorPage's `applyPriceMode` now resets the stored flag to `true` the moment the
  // document reaches this mode — so the branch was dropped rather than kept pointing at a state
  // that can no longer occur. The SERVER-side rejection of roundToFullBox=false under English
  // per-sqm is unchanged; only this frontend checklist branch was removed.
  it('English per-sqm no longer flags roundToFullBox=false in the checklist — that state is now unreachable, not merely re-guarded', () => {
    const perSqm = { ...tile, unitPrice: '', specialPriceSqm: 64, sqmPerBox: 0.6 };
    expect(meta.validateQuotationItem({ ...perSqm, roundToFullBox: false }, 'SPECIAL_SQM', 'EN')).toEqual({});
    // Unaffected everywhere else, same as before: Thai ราคาพิเศษ, and NET in any language.
    expect(meta.validateQuotationItem({ ...tile, roundToFullBox: false }, 'SPECIAL_SQM', 'TH').roundToFullBox).toBeUndefined();
    expect(meta.validateQuotationItem({ ...tile, roundToFullBox: false }, 'NET', 'EN').roundToFullBox).toBeUndefined();
  });

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

// ── Owner-approved "sell loose pieces" (2026-09-16, V182) ───────────────────────────────────────
describe('roundToFullBoxDisabledReason', () => {
  it('is disabled until แผ่น/กล่อง is filled', () => {
    expect(meta.roundToFullBoxDisabledReason({ piecesPerBox: '' }, 'NET', 'TH')).toBe('กรอกแผ่น/กล่องก่อน');
    expect(meta.roundToFullBoxDisabledReason({ piecesPerBox: null }, 'NET', 'TH')).toBe('กรอกแผ่น/กล่องก่อน');
    expect(meta.roundToFullBoxDisabledReason({ piecesPerBox: 0 }, 'NET', 'TH')).toBe('กรอกแผ่น/กล่องก่อน');
  });

  it('is disabled in English per-sqm mode, once แผ่น/กล่อง is filled', () => {
    expect(meta.roundToFullBoxDisabledReason({ piecesPerBox: 10 }, 'SPECIAL_SQM', 'EN'))
      .toBe(meta.ROUND_TO_FULL_BOX_DISABLED_PER_SQM_REASON);
    // The ppb-missing reason takes priority when BOTH apply — one reason at a time.
    expect(meta.roundToFullBoxDisabledReason({ piecesPerBox: '' }, 'SPECIAL_SQM', 'EN')).toBe('กรอกแผ่น/กล่องก่อน');
  });

  it('is enabled (null) once แผ่น/กล่อง is filled, outside English per-sqm', () => {
    expect(meta.roundToFullBoxDisabledReason({ piecesPerBox: 10 }, 'NET', 'TH')).toBeNull();
    expect(meta.roundToFullBoxDisabledReason({ piecesPerBox: 10 }, 'SPECIAL_SQM', 'TH')).toBeNull();
    expect(meta.roundToFullBoxDisabledReason({ piecesPerBox: 10 }, 'DIRECT_NET', 'EN')).toBeNull();
  });
});

describe('roundToFullBoxSummary', () => {
  it('is null before the server has computed anything (no แผ่น/กล่อง, or no calc yet)', () => {
    expect(meta.roundToFullBoxSummary({ piecesPerBox: '' })).toBeNull();
    expect(meta.roundToFullBoxSummary({ piecesPerBox: 10, piecesFinal: null, boxes: null })).toBeNull();
  });

  it('off (round up): "ปัดขึ้นเต็มกล่อง → N กล่อง (M แผ่น)"', () => {
    expect(meta.roundToFullBoxSummary({ piecesPerBox: 10, piecesFinal: 40, boxes: 4, roundToFullBox: true }))
      .toBe('ปัดขึ้นเต็มกล่อง → 4 กล่อง (40 แผ่น)');
    // Undefined reads the same as true (a pre-V182 row, or a freshly loaded server row that has
    // not round-tripped yet) -- this helper must never treat "not yet known" as "loose pieces".
    expect(meta.roundToFullBoxSummary({ piecesPerBox: 10, piecesFinal: 40, boxes: 4 }))
      .toBe('ปัดขึ้นเต็มกล่อง → 4 กล่อง (40 แผ่น)');
  });

  it('on (loose pieces): "N กล่อง + M แผ่น (P แผ่น)"', () => {
    expect(meta.roundToFullBoxSummary({ piecesPerBox: 10, piecesFinal: 32, boxes: 3, roundToFullBox: false }))
      .toBe('3 กล่อง + 2 แผ่น (32 แผ่น)');
  });

  it('loose = 0: no "+ N แผ่น" tail', () => {
    expect(meta.roundToFullBoxSummary({ piecesPerBox: 10, piecesFinal: 30, boxes: 3, roundToFullBox: false }))
      .toBe('3 กล่อง (30 แผ่น)');
  });

  it('full boxes = 0: no "กล่อง" wording at all', () => {
    expect(meta.roundToFullBoxSummary({ piecesPerBox: 10, piecesFinal: 7, boxes: 0, roundToFullBox: false }))
      .toBe('7 แผ่น (ไม่ครบ 1 กล่อง)');
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

  // Owner ruling (2026-09-16): "make ผู้ออกแบบ optional including ฝ่าย". Both were already optional on
  // the backend and never blocking; the checklist still listed "ยังไม่ได้เลือกผู้ออกแบบ" as missing
  // information, which read as required. Wrong-way-round: neither a blank designer (unitCode) nor a
  // blank ฝ่าย (deptCode) may produce ANY checklist entry, warning or blocking.
  it('never lists ผู้ออกแบบ or ฝ่าย as missing — both are optional', () => {
    const entries = meta.buildQuotationChecklist({ ...complete, terms: { unitCode: '', deptCode: '' } });
    expect(entries).toEqual([]);
    expect(Object.values(meta.QUOTATION_CHECK)).not.toContain('designer');
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

  // ── Opus review fix (2026-09-16, F2): FULL_PAYMENT_TERM now recognises BOTH routes to
  // depositPercent = 0 (the "ไม่รับมัดจำ" checkbox AND a custom-typed "0"), not just the checkbox —
  // see #isEffectiveZeroDeposit's own tests below for the shared computation. The entry itself
  // stays NON-blocking (DealQuotationService#create/#update accept a zero-deposit DRAFT with no
  // term yet — only #submit refuses it; QuotationEditorPage's own separate hasUnresolvedZeroDeposit
  // guard is what disables ส่งขออนุมัติ for this state).
  it('lists (but does not block) a missing เงื่อนไขการชำระเงิน on the checkbox route', () => {
    const entries = meta.buildQuotationChecklist({ ...complete, noDeposit: true, fullPaymentTerm: '' });
    expect(entries).toEqual([{
      check: 'fullPaymentTerm', message: 'มัดจำ 0% กรุณาเลือกเงื่อนไขการชำระเงิน',
      targetId: 'fullPaymentTerm', blocking: false,
    }]);
  });

  it('ALSO lists it for a custom-typed 0% deposit, even with the checkbox unticked', () => {
    const entries = meta.buildQuotationChecklist({
      ...complete, noDeposit: false, depositPercentCustom: true, depositPercent: '0', fullPaymentTerm: '',
    });
    expect(blocking(entries)).toEqual([]);
    expect(entries.map((e) => e.check)).toEqual(['fullPaymentTerm']);
  });

  it('does NOT list it for an ordinary 30% deposit', () => {
    const entries = meta.buildQuotationChecklist({
      ...complete, noDeposit: false, depositPercentCustom: false, depositPercent: 30, fullPaymentTerm: '',
    });
    expect(entries).toEqual([]);
  });

  it('does NOT list it once a term is chosen', () => {
    expect(meta.buildQuotationChecklist({ ...complete, noDeposit: true, fullPaymentTerm: 'CREDIT_30' })).toEqual([]);
    expect(meta.buildQuotationChecklist({
      ...complete, noDeposit: false, depositPercentCustom: true, depositPercent: '0', fullPaymentTerm: 'CREDIT_30',
    })).toEqual([]);
  });
});

describe('isEffectiveZeroDeposit', () => {
  it('is true when the "ไม่รับมัดจำ" checkbox is ticked, regardless of the percent fields', () => {
    expect(meta.isEffectiveZeroDeposit({ noDeposit: true })).toBe(true);
    expect(meta.isEffectiveZeroDeposit({ noDeposit: true, depositPercentCustom: false, depositPercent: 30 })).toBe(true);
  });

  // The Opus review finding (F2): typing "0" into the custom "อื่นๆ" input while UNticked must be
  // recognised identically to the checkbox — this is the exact gap that let a rep save/attempt to
  // submit depositPercent = 0 with no fullPaymentTerm without ever ticking "ไม่รับมัดจำ".
  it('is true for a custom-typed "0" even with the checkbox unticked', () => {
    expect(meta.isEffectiveZeroDeposit({ noDeposit: false, depositPercentCustom: true, depositPercent: '0' })).toBe(true);
    expect(meta.isEffectiveZeroDeposit({ noDeposit: false, depositPercentCustom: true, depositPercent: 0 })).toBe(true);
  });

  it('is false for a non-zero custom percent, a preset percent, or an empty field', () => {
    expect(meta.isEffectiveZeroDeposit({ noDeposit: false, depositPercentCustom: true, depositPercent: '10' })).toBe(false);
    expect(meta.isEffectiveZeroDeposit({ noDeposit: false, depositPercentCustom: false, depositPercent: 30 })).toBe(false);
    expect(meta.isEffectiveZeroDeposit({ noDeposit: false, depositPercentCustom: true, depositPercent: '' })).toBe(false);
  });

  it('defaults to false when called with no arguments', () => {
    expect(meta.isEffectiveZeroDeposit()).toBe(false);
  });
});

describe('joinPresent', () => {
  it('joins only the present parts, so a missing one leaves no dangling separator', () => {
    expect(meta.joinPresent(['คุณธนพล', null, 'a@b.co'])).toBe('คุณธนพล · a@b.co');
    expect(meta.joinPresent(['คุณธนพล', '', '  '])).toBe('คุณธนพล');
    expect(meta.joinPresent([null, undefined])).toBe('');
  });
});

// V178 (owner ruling 2026-09-14) — the SAME five rules as
// th.co.glr.hr.dealquotation.DealQuotationRenderAdapter#hasSpecialPricing's own test class
// (DealQuotationRenderAdapterV3Test), mirrored here so the editor's toggle and the server's save
// gate can never disagree about what counts as "special pricing".
describe('hasSpecialPricing (#V178)', () => {
  const tile = (overrides = {}) => ({
    lineType: LINE_TYPE_TILE, unitPrice: 100, netUnitPrice: 100, discountPct: null, ...overrides,
  });
  const plain = (overrides = {}) => ({ lineType: LINE_TYPE_PLAIN, discountPct: null, ...overrides });
  const adjustment = (overrides = {}) => ({ lineType: LINE_TYPE_ADJUSTMENT, ...overrides });

  it('(a) SPECIAL_SQM with a TILE row is true, regardless of discount', () => {
    expect(hasSpecialPricing('SPECIAL_SQM', [tile()])).toBe(true);
  });

  it('(b) DIRECT_NET with net below list price is true', () => {
    expect(hasSpecialPricing('DIRECT_NET', [tile({ unitPrice: 100, netUnitPrice: 85 })])).toBe(true);
  });

  it('the (b) counter-case: DIRECT_NET with net == list price is false', () => {
    expect(hasSpecialPricing('DIRECT_NET', [tile({ unitPrice: 100, netUnitPrice: 100 })])).toBe(false);
  });

  it('(c) NET with a TILE row discountPct > 0 is true', () => {
    expect(hasSpecialPricing('NET', [tile({ discountPct: 5 })])).toBe(true);
  });

  it('the (c) counter-case, both shapes: discountPct null, and explicitly zero', () => {
    expect(hasSpecialPricing('NET', [tile()])).toBe(false);
    expect(hasSpecialPricing('NET', [tile({ discountPct: 0 })])).toBe(false);
  });

  it('(d) a PLAIN row with discountPct > 0 is true, regardless of priceMode', () => {
    expect(hasSpecialPricing('NET', [plain({ discountPct: 10 })])).toBe(true);
  });

  it('a PLAIN row with no discount is false', () => {
    expect(hasSpecialPricing('NET', [plain()])).toBe(false);
  });

  it('(e) an ADJUSTMENT row is true, regardless of price mode or the other rows', () => {
    expect(hasSpecialPricing('NET', [tile(), adjustment()])).toBe(true);
  });

  it('no discount anywhere is false — the negative baseline every case above contrasts against', () => {
    expect(hasSpecialPricing('NET', [tile(), plain()])).toBe(false);
  });

  it('a null/blank lineType reads as TILE, same as lineTypeOf', () => {
    expect(hasSpecialPricing('NET', [{ unitPrice: 100, netUnitPrice: 100, discountPct: 7 }])).toBe(true);
  });

  it('an empty or missing row list is false', () => {
    expect(hasSpecialPricing('NET', [])).toBe(false);
    expect(hasSpecialPricing('NET', null)).toBe(false);
    expect(hasSpecialPricing('NET', undefined)).toBe(false);
  });
});
