import { describe, expect, it } from 'vitest';
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
  quotationItemMissingSummary,
  remainderModeLabel,
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
    expect(defaultLeadTimeForOrigin('จีน')).toEqual({ leadTimeMinDays: 60, leadTimeMaxDays: 75 });
    expect(defaultLeadTimeForOrigin('ไทย-สต็อก')).toEqual({ leadTimeMinDays: 30, leadTimeMaxDays: 45 });
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

  it('flags a missing or non-positive ตร.ม./แผ่น', () => {
    expect(validateQuotationItem(completeItem({ sqmPerPiece: null }))).toEqual({ sqmPerPiece: 'กรุณาระบุตร.ม./แผ่น' });
    expect(validateQuotationItem(completeItem({ sqmPerPiece: 0 }))).toEqual({ sqmPerPiece: 'กรุณาระบุตร.ม./แผ่น' });
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
