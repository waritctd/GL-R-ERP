import { describe, expect, it } from 'vitest';
import * as meta from './quotationMeta.js';
import {
  canApproveDealQuotation,
  canCancelDealQuotation,
  canChangeDealCustomer,
  canCreateDealQuotation,
  canCreateDealQuotationStandalone,
  canDecideDealQuotation,
  canEditDealQuotation,
  isDealQuotationEditable,
  parseSizeText,
  canPromoteDealQuotationToDeal,
  canReviseDealQuotation,
  canSubmitDealQuotation,
  canTransitionDealQuotation,
  canViewDealQuotation,
  hasSpecialPricing,
  LINE_TYPE_TILE,
  perSqmIncVatFromPerPiece,
  piecesPerSqmFromSqmPerPiece,
  sqmPerPieceFromSizeCm,
  sizeTextMatchesCatalogFaceSize,
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
// canCreateQuotation is a per-employee capability grant, not a role: a `qc` employee can hold it
// without being sales/sales_manager.
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
});

describe('canCreateDealQuotation', () => {
  it('lets the owning sales rep create on their own deal', () => {
    expect(canCreateDealQuotation(salesOwner, ownedTicket)).toBe(true);
  });

  // Wrong-way-round: the case that matters is what the caller CANNOT reach.
  it('refuses a sales rep on another rep\'s deal', () => {
    expect(canCreateDealQuotation(salesOwner, otherTicket)).toBe(false);
  });

  it('a qc user WITH the grant may create on ANY deal, same as sales_manager', () => {
    expect(canCreateDealQuotation(qcWithGrant, ownedTicket)).toBe(true);
    expect(canCreateDealQuotation(qcWithGrant, otherTicket)).toBe(true);
  });

  it('a qc user WITHOUT the grant is refused, even on their own imaginary deal', () => {
    expect(canCreateDealQuotation(qcUser, ownedTicket)).toBe(false);
    expect(canCreateDealQuotation(qcUser, otherTicket)).toBe(false);
  });
});

// Role-only half of canCreateDealQuotation for the moment BEFORE a ticket exists. Must grant exactly
// the same three audiences (sales/sales_manager/grant) and refuse everyone else, since the creator
// immediately becomes the new ticket's owner and would re-pass the real check.
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

  it('isDealQuotationEditable is DRAFT-only, regardless of who is asking', () => {
    expect(isDealQuotationEditable(quotation({ docStatus: 'DRAFT' }))).toBe(true);
    expect(isDealQuotationEditable(quotation({ docStatus: 'PENDING_APPROVAL' }))).toBe(false);
    expect(isDealQuotationEditable(quotation({ docStatus: 'APPROVED' }))).toBe(false);
    expect(isDealQuotationEditable(quotation({ docStatus: 'CANCELLED' }))).toBe(false);
  });
});

describe('canSubmitDealQuotation / canCancelDealQuotation / canReviseDealQuotation', () => {
  it('revise needs edit rights AND APPROVED only', () => {
    expect(canReviseDealQuotation(salesOwner, quotation({ docStatus: 'APPROVED' }))).toBe(true);
    expect(canReviseDealQuotation(salesOwner, quotation({ docStatus: 'DRAFT' }))).toBe(false);
    expect(canReviseDealQuotation(salesOwner, quotation({ docStatus: 'PENDING_APPROVAL' }))).toBe(false);
    expect(canReviseDealQuotation(otherSales, quotation({ docStatus: 'APPROVED' }))).toBe(false);
  });

  // Once the order is confirmed from a direct quotation there is no revise action (the server's
  // createRevision answers 409). The third argument is the deal's order-confirmation fact; null
  // means "unknown" (loading / errored) and hides the action.
  it('revise is hidden once the order is confirmed or while that is unknown, shown only when known-unconfirmed', () => {
    const approved = quotation({ docStatus: 'APPROVED' });
    expect(canReviseDealQuotation(salesOwner, approved, { confirmed: false })).toBe(true);
    expect(canReviseDealQuotation(salesOwner, approved, { confirmed: true })).toBe(false);
    expect(canReviseDealQuotation(salesManager, approved, { confirmed: true })).toBe(false);
    expect(canReviseDealQuotation(salesOwner, approved, null)).toBe(false);
  });

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
});

// Revise a PRICING_REQUEST-origin quotation. Mirrors DealQuotationService#createRevision's
// PRICING_REQUEST branch: ISSUED / REVISION_REQUESTED only, and ONLY the owning sales rep; the
// wider canEditDealQuotation audience must NOT see the button for this origin. Display-only; the
// authoritative proof is DealQuotationPricingRequestRevisionIntegrationTest.
describe('canReviseDealQuotation -- PRICING_REQUEST origin', () => {
  const prQ = (overrides = {}) => quotation({ origin: 'PRICING_REQUEST', docStatus: 'ISSUED', ...overrides });

  it('the owning rep may revise ISSUED and REVISION_REQUESTED', () => {
    expect(canReviseDealQuotation(salesOwner, prQ({ docStatus: 'ISSUED' }))).toBe(true);
    expect(canReviseDealQuotation(salesOwner, prQ({ docStatus: 'REVISION_REQUESTED' }))).toBe(true);
  });

  it('wrong-way-round: sales_manager, granted qc, non-owner sales and ceo are all refused', () => {
    expect(canReviseDealQuotation(salesManager, prQ())).toBe(false);
    expect(canReviseDealQuotation(qcWithGrant, prQ())).toBe(false);
    expect(canReviseDealQuotation(otherSales, prQ())).toBe(false);
    expect(canReviseDealQuotation(ceo, prQ())).toBe(false);
  });
});

// Display gate for "สร้างดีลจากใบเสนอราคา". Mirrors DealQuotationService#promoteToDeal's gate
// (requireEditAccess + DEAL_DIRECT + APPROVED); the authoritative proof is
// DealQuotationPromoteIntegrationTest.
describe('canPromoteDealQuotationToDeal', () => {
  it('owner (edit rights) on an APPROVED direct quotation', () => {
    expect(canPromoteDealQuotationToDeal(salesOwner, quotation({ docStatus: 'APPROVED' }))).toBe(true);
    // A stored-null/absent origin reads as DEAL_DIRECT, exactly like the server DTO.
    expect(canPromoteDealQuotationToDeal(salesOwner, quotation({ docStatus: 'APPROVED', origin: undefined }))).toBe(true);
  });

  it('never before APPROVED, and never after it was superseded/cancelled', () => {
    for (const docStatus of ['DRAFT', 'PENDING_APPROVAL', 'SUPERSEDED', 'CANCELLED']) {
      expect(canPromoteDealQuotationToDeal(salesOwner, quotation({ docStatus }))).toBe(false);
    }
  });

  it('never a user without edit rights (wrong-way-round)', () => {
    expect(canPromoteDealQuotationToDeal(otherSales, quotation({ docStatus: 'APPROVED' }))).toBe(false);
    expect(canPromoteDealQuotationToDeal({ id: 8, role: 'ceo' }, quotation({ docStatus: 'APPROVED' }))).toBe(false);
    expect(canPromoteDealQuotationToDeal({ id: 7, role: 'import' }, quotation({ docStatus: 'APPROVED' }))).toBe(false);
    expect(canPromoteDealQuotationToDeal(null, quotation({ docStatus: 'APPROVED' }))).toBe(false);
  });
});

// Mirrors TicketService.changeCustomer: requireDealOwnership (owning sales rep, sales_manager, ceo —
// and NOT a canCreateQuotation grant holder), plus the two state gates the page can see: the
// quotation is a DRAFT and it came from the deal itself rather than from a pricing request.
describe('canChangeDealCustomer', () => {
  const draftDirect = quotation({ origin: 'DEAL_DIRECT' });

  it('refuses the wrong people first: a grant holder, another rep, and every other role', () => {
    expect(canChangeDealCustomer(qcWithGrant, ownedTicket, draftDirect)).toBe(false);
    expect(canChangeDealCustomer({ ...salesOwner, canCreateQuotation: true }, otherTicket, draftDirect)).toBe(false);
    expect(canChangeDealCustomer(otherSales, ownedTicket, draftDirect)).toBe(false);
    for (const user of [importUser, accountUser, hrUser, qcUser, warehouseUser]) {
      expect(canChangeDealCustomer(user, ownedTicket, draftDirect), user.role).toBe(false);
    }
    expect(canChangeDealCustomer(null, ownedTicket, draftDirect)).toBe(false);
    expect(canChangeDealCustomer(salesOwner, null, draftDirect)).toBe(false);
    expect(canChangeDealCustomer(salesOwner, ownedTicket, null)).toBe(false);
  });

  it('allows the owning sales rep, the sales_manager and the ceo on a DRAFT direct quotation', () => {
    expect(canChangeDealCustomer(salesOwner, ownedTicket, draftDirect)).toBe(true);
    expect(canChangeDealCustomer(salesManager, ownedTicket, draftDirect)).toBe(true);
    expect(canChangeDealCustomer(salesManager, otherTicket, draftDirect)).toBe(true);
    expect(canChangeDealCustomer(ceo, otherTicket, draftDirect)).toBe(true);
  });
});

describe('canApproveDealQuotation / canDecideDealQuotation', () => {
  it('sales_manager and ceo may approve, role-only', () => {
    expect(canApproveDealQuotation(salesManager)).toBe(true);
    expect(canApproveDealQuotation(ceo)).toBe(true);
  });

  // Wrong-way-round: sales, even the deal's own owning rep, must never approve its own quotation.
  it('sales can NEVER approve, including the deal\'s own owning rep', () => {
    expect(canApproveDealQuotation(salesOwner)).toBe(false);
  });

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

describe('canViewDealQuotation', () => {
  it('grants the plan\'s exact view role set', () => {
    expect(canViewDealQuotation(salesOwner)).toBe(true);
    expect(canViewDealQuotation(salesManager)).toBe(true);
    expect(canViewDealQuotation(ceo)).toBe(true);
    expect(canViewDealQuotation(importUser)).toBe(true);
    expect(canViewDealQuotation(accountUser)).toBe(true);
  });

  it('refuses hr/qc/warehouse and no user', () => {
    expect(canViewDealQuotation(hrUser)).toBe(false);
    expect(canViewDealQuotation(qcUser)).toBe(false);
    expect(canViewDealQuotation(warehouseUser)).toBe(false);
    expect(canViewDealQuotation(null)).toBe(false);
  });
});

// Autofill when the item is in the database; sales may also fill in an item that is not, but ALL
// info about the tile must be completed. validateQuotationItem is the single completeness check,
// identical for a catalog-linked and a hand-typed row (a catalog pick only fills fields, it never
// exempts a row from carrying them).
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

  // One row per required field: blanking it (each listed bad value in turn) must produce exactly that field's
  // error and nothing else. Columns: test name, field, bad values, expected message.
  it.each([
    ['flags a missing รุ่น', 'model', ['', '   '], 'กรุณาระบุรุ่น'],
    ['flags a missing สี', 'color', [''], 'กรุณาระบุสี'],
    ['flags a missing ผิว', 'texture', [''], 'กรุณาระบุผิว'],
    ['flags a missing ขนาด', 'sizeText', [''], 'กรุณาระบุขนาด'],
    ['flags a missing or non-positive ความหนา', 'thicknessMm', [null, 0], 'กรุณาระบุความหนา (มม.)'],
    ['flags a missing or non-positive แผ่น/ตร.ม.', 'sqmPerPiece', [null, 0], 'กรุณาระบุแผ่น/ตร.ม.'],
    ['flags a missing or sub-1 แผ่น/กล่อง', 'piecesPerBox', [null, 0], 'กรุณาระบุแผ่น/กล่อง'],
    // The box this names on a Thai tile row is ราคา/ตร.ม.; the error KEY is still `unitPrice`
    // (it names the box, `price-N`). See quotationPerSqmPrice.test.jsx.
    ['flags a missing or non-positive price', 'unitPrice', [null, 0], 'กรุณาระบุราคา/ตร.ม.'],
  ])('%s', (_name, field, badValues, message) => {
    for (const value of badValues) {
      expect(validateQuotationItem(completeItem({ [field]: value }))).toEqual({ [field]: message });
    }
  });

  // Mirrors DealQuotationService#requireEveryTileItemHasALeadTime. SUBMIT-only: `requireLeadTime`
  // defaults to OFF so a draft may be saved with no lead time; only a caller that opts in
  // (QuotationEditorPage's `submitItemErrorsByRow`) sees it.
  describe('requireLeadTime option (owner feedback #7, 2026-09-14)', () => {
    it('flags a missing lead time only when explicitly required', () => {
      const item = completeItem({ leadTimeMinDays: null, leadTimeMaxDays: null });
      expect(validateQuotationItem(item, 'NET', 'TH', { requireLeadTime: true }))
        .toEqual({ leadTimeMinDays: 'กรุณาระบุระยะเวลานำเข้า (วัน)' });
    });

    // A lead time of 0 (or negative) is INVALID (min must be >= 1 day), refused with the same
    // message the backend uses, unconditionally (not gated on `requireLeadTime`).
    it('a zero lead time is refused as an invalid value, not accepted as an exact same-day range', () => {
      const item = completeItem({ leadTimeMinDays: 0, leadTimeMaxDays: 0 });
      expect(validateQuotationItem(item, 'NET', 'TH', { requireLeadTime: true }))
        .toEqual({ leadTimeMinDays: 'ระยะเวลานำเข้าต้องไม่น้อยกว่า 1 วัน และค่าสูงสุดต้องไม่น้อยกว่าค่าต่ำสุด' });
    });
  });
});

// The editor shows/accepts แผ่น/ตร.ม. (pieces per sqm) while the wire field stays ตร.ม./แผ่น
// (sqmPerPiece) verbatim; see quotationMeta.js's comment on these two. `WastageCalculator#piecesPerSqm`
// (round(1/x, 2, HALF_UP)) is mirrored for DISPLAY only; these tests pin that mirror and its round
// trip, never the money math itself.
describe('piecesPerSqmFromSqmPerPiece / sqmPerPieceFromPiecesPerSqm (แผ่น/ตร.ม. display, owner feedback 2026-09-12)', () => {
  it('mirrors WastageCalculator#piecesPerSqm — round(1/x, 2, HALF_UP)', () => {
    expect(piecesPerSqmFromSqmPerPiece(0.72)).toBe(1.39);
    expect(piecesPerSqmFromSqmPerPiece(0.36)).toBe(2.78);
    // The real disagreement this feature exists to route around: a 300x600 mesh sheet whose covered area is
    // genuinely 0.135 sqm/piece, not the 0.18 width x height geometry would say.
    expect(piecesPerSqmFromSqmPerPiece(0.135)).toBe(7.41);
  });
});

describe('perSqmIncVatFromPerPiece (Thai SPECIAL_SQM ราคาตั้ง shown per ตร.ม., owner feedback 2026-09-14)', () => {
  // Real-document figures, each round2(unitPrice x piecesPerSqm x 1.07) where piecesPerSqm is
  // piecesPerSqmFromSqmPerPiece(sqmPerPiece), the same reciprocal exercised above.
  it('reproduces all four owner figures exactly', () => {
    expect(perSqmIncVatFromPerPiece(1299.0, 0.72)).toBe(1932.0);
    expect(perSqmIncVatFromPerPiece(881.46, 0.36)).toBe(2621.99);
    expect(perSqmIncVatFromPerPiece(843.14, 0.36)).toBe(2508.0);
    expect(perSqmIncVatFromPerPiece(900.63, 0.36)).toBe(2679.01);
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

  it('longer than the 64-char length guard is rejected outright, even for an otherwise-genuine shape', () => {
    // The padding is INTERNAL (between the first number and the separator), so no trimming step could shrink it
    // away -- this pins the length guard itself, not just the regex fix.
    const genuineButLong = '30' + ' '.repeat(60) + 'x60';
    expect(genuineButLong.length).toBe(65);
    expect(parseSizeText(genuineButLong)).toBeNull();
  });
});

describe('sizeTextMatchesCatalogFaceSize (prod QT-2026-0034-1, 2026-09-15 — mirrors DealQuotationLines#sizeLine\'s REFINEMENT)', () => {
  it('matches when the typed size is the catalogue size in millimetres (the exact prod bug shape)', () => {
    // catalogue was picked as 60x60 cm (a 600x600mm tile); the rep typed the mm figures straight into this
    // cm-labelled field.
    expect(sizeTextMatchesCatalogFaceSize('600x600', '60x60')).toBe(true);
    expect(sizeTextMatchesCatalogFaceSize('600x1200', '60x120')).toBe(true);
    expect(sizeTextMatchesCatalogFaceSize('1200x600', '60x120')).toBe(true);
  });

  it('does NOT match a genuinely different size -- the exact prod bug\'s two lines', () => {
    expect(sizeTextMatchesCatalogFaceSize('30x60', '60x60')).toBe(false);
    expect(sizeTextMatchesCatalogFaceSize('3x60', '60x60')).toBe(false);
  });
});

// ── Global list scope. Mirrors DealQuotationService.listOwnerScope / LIST_SEE_ALL_ROLES; these are
// presentation hints only, never an authz boundary (see DealQuotationIntegrationTest). ──
describe('dealQuotationListSeesAll', () => {
  it('is true only for sales_manager and ceo', () => {
    expect(meta.dealQuotationListSeesAll({ role: 'sales_manager' })).toBe(true);
    expect(meta.dealQuotationListSeesAll({ role: 'ceo' })).toBe(true);
  });

  it('is false for sales, import, account, a grant-holder, and no user at all', () => {
    expect(meta.dealQuotationListSeesAll({ role: 'sales' })).toBe(false);
    expect(meta.dealQuotationListSeesAll({ role: 'import' })).toBe(false);
    expect(meta.dealQuotationListSeesAll({ role: 'account' })).toBe(false);
    expect(meta.dealQuotationListSeesAll({ role: 'qc', canCreateQuotation: true })).toBe(false);
    expect(meta.dealQuotationListSeesAll(null)).toBe(false);
    expect(meta.dealQuotationListSeesAll(undefined)).toBe(false);
  });
});

describe('filterDealQuotationRows', () => {
  const rows = [
    { id: 1, salesRepId: 6, customerName: 'บริษัท แฟชั่นไอส์แลนด์ จำกัด', projectName: 'Fashion Island' },
    { id: 2, salesRepId: 9, customerName: 'บริษัท เดโม จำกัด', projectName: null },
    { id: 3, salesRepId: 6, customerName: 'บริษัท อื่น จำกัด', projectName: 'Other Project' },
  ];

  it('filters by amount: lt1m is < threshold, gte1m owns the exact 1,000,000 boundary', () => {
    const amountRows = [
      { id: 1, grandTotal: 999999.99 },
      { id: 2, grandTotal: 1000000 },
      { id: 3, grandTotal: 2500000 },
      { id: 4, grandTotal: 0 },
    ];
    expect(meta.filterDealQuotationRows(amountRows, { amount: 'lt1m' }).map((r) => r.id)).toEqual([1, 4]);
    expect(meta.filterDealQuotationRows(amountRows, { amount: 'gte1m' }).map((r) => r.id)).toEqual([2, 3]);
    // Unknown amount value is a no-op, and amount composes with repId/text (AND).
    expect(meta.filterDealQuotationRows(amountRows, { amount: 'nonsense' }).map((r) => r.id)).toEqual([1, 2, 3, 4]);
    expect(meta.filterDealQuotationRows(rows, { repId: 6, amount: 'gte1m' })).toEqual([]);
  });
});

// ── Quotation v3 / v3b ──
describe('v3/v3b document settings', () => {
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

  it('derives the currency and the VAT rate from the language alone', () => {
    expect(meta.currencyForLanguage('TH')).toBe('THB');
    expect(meta.currencyForLanguage('EN')).toBe('USD');
    expect(meta.vatRateForLanguage('TH')).toBe(0.07);
    expect(meta.vatRateForLanguage('EN')).toBe(0);
  });
});

describe('v3 row labels and derivations', () => {
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

  it('English per-sqm needs the USD/ตร.ม. — and NOT a list price per piece; ตร.ม./กล่อง is OPTIONAL', () => {
    const perSqm = { ...tile, unitPrice: '', specialPriceSqm: 64, sqmPerBox: 0.6 };
    expect(meta.validateQuotationItem(perSqm, 'SPECIAL_SQM', 'EN')).toEqual({});
    expect(meta.validateQuotationItem({ ...perSqm, specialPriceSqm: '' }, 'SPECIAL_SQM', 'EN')).toEqual({ specialPriceSqm: 'กรุณาระบุราคา (USD/ตร.ม.)' });
    // ตร.ม./กล่อง PRESENT: แผ่น/กล่อง is still required (a partially-filled pair is refused).
    expect(meta.validateQuotationItem({ ...perSqm, piecesPerBox: '' }, 'SPECIAL_SQM', 'EN').piecesPerBox).toBeTruthy();
    expect(meta.validateQuotationItem({ ...perSqm, sqmPerBox: 0.1234567 }, 'SPECIAL_SQM', 'EN').sqmPerBox).toBe('ทศนิยมได้ไม่เกิน 6 ตำแหน่ง');
    // The same row in THAI ราคาพิเศษ still needs its list price, and never asks for ตร.ม./กล่อง.
    expect(meta.validateQuotationItem({ ...perSqm, sqmPerBox: null }, 'SPECIAL_SQM', 'TH')).toEqual({ unitPrice: 'กรุณาระบุราคาตั้ง (บาท/ตร.ม.)' });
    expect(meta.quotationItemMissingSummary({ sqmPerBox: 'x', specialPriceSqm: 'y' }, 0)).toBe('รายการที่ 1: ขาด ตร.ม./กล่อง, ราคาต่อ ตร.ม.');
  });

  it('SPECIAL_SQM needs the ราคาพิเศษ — and still the list price, which the server requires on every tile row', () => {
    expect(meta.validateQuotationItem({ ...tile, specialPriceSqm: '' }, 'SPECIAL_SQM')).toEqual({ specialPriceSqm: 'กรุณาระบุราคาพิเศษ (บาท/ตร.ม.)' });
    expect(meta.validateQuotationItem({ ...tile, specialPriceSqm: 1350 }, 'SPECIAL_SQM')).toEqual({});
    expect(meta.validateQuotationItem({ ...tile, unitPrice: '', specialPriceSqm: 1350 }, 'SPECIAL_SQM').unitPrice).toBeTruthy();
  });

  it('a PLAIN row needs description, quantity, unit and a positive price — and nothing tile-shaped', () => {
    expect(meta.validateQuotationItem({ lineType: 'PLAIN' })).toEqual({
      description: 'กรุณาระบุรายละเอียด', quantity: 'กรุณาระบุจำนวน', unit: 'กรุณาเลือกหน่วย', unitPrice: 'กรุณาระบุราคา/หน่วย',
    });
    expect(meta.validateQuotationItem({ lineType: 'PLAIN', description: 'ค่าขนส่ง', quantity: 1, unit: 'งาน', unitPrice: 3500 })).toEqual({});
    expect(meta.validatePlainItem({ description: 'x', quantity: 1.005, unit: 'JOB', unitPrice: 1 }).quantity).toBeTruthy();
  });
});

describe('roundToFullBoxSummary', () => {
  it('off (round up): "ปัดขึ้นเต็มกล่อง → N กล่อง (M แผ่น)"', () => {
    expect(meta.roundToFullBoxSummary({ piecesPerBox: 10, piecesFinal: 40, boxes: 4, roundToFullBox: true }))
      .toBe('ปัดขึ้นเต็มกล่อง → 4 กล่อง (40 แผ่น)');
    // Undefined reads the same as true (a legacy row, or a freshly loaded server row not yet round-
    // tripped); "not yet known" must never be treated as "loose pieces".
    expect(meta.roundToFullBoxSummary({ piecesPerBox: 10, piecesFinal: 40, boxes: 4 }))
      .toBe('ปัดขึ้นเต็มกล่อง → 4 กล่อง (40 แผ่น)');
  });

  it('on (loose pieces): "N กล่อง + M แผ่น (P แผ่น)"', () => {
    expect(meta.roundToFullBoxSummary({ piecesPerBox: 10, piecesFinal: 32, boxes: 3, roundToFullBox: false }))
      .toBe('3 กล่อง + 2 แผ่น (32 แผ่น)');
  });
});

// ── "ข้อมูลที่ยังไม่ครบ" checklist ──
describe('buildQuotationChecklist', () => {
  const customer = { id: 5, name: 'บริษัท ก จำกัด', address: '1 ถนนสุขุมวิท', taxId: '0105551234567', phone: '02-000-0000' };
  const complete = {
    customer, projectName: 'โครงการ A', items: [{ lineType: 'TILE' }], itemErrorsByRow: [{}],
  };

  it('pins the blocking set to what the backend already refuses — nothing the owner has not ruled on', () => {
    // creditDaysInvalid is blocking: the backend refuses an explicit invalid (<=0) creditDays on
    // every save, not just submit. creditDays itself (the BLANK-on-draft reminder) stays a warning,
    // same as fullPaymentTerm.
    //
    // 'contact' is NOT in this set: ผู้สั่งซื้อ is a single optional free-text field.
    //
    // 'entryChannel' is blocking on the INLINE-CREATE path: a rep must pick one of the three real
    // ช่องทางรับงาน before บันทึกร่าง creates the deal. The backend does NOT refuse this
    // (TicketService.create accepts UNSPECIFIED); it is a deliberate frontend-only product rule.
    //
    // 'deal', 'recipient' and 'liveDirectQuotation' each mirror a create refusal the server makes
    // (no ticket to hang the quotation on; 400 on a direct create without a recipient; 409 on a
    // live direct quotation).
    //
    // 'livePricingRequest' mirrors DealQuotationService#create's 409 on a deal holding a live
    // pricing request. 'recipientOffRoute' mirrors the 409 on a new or changed recipient off the deal's
    // route (owner ruling 2026-11-07).
    // orderedByEmail: the server refuses a malformed ผู้ติดต่อ Email with a 400 on every save, so
    // the editor says so before the click. (A blank one is fine: the field is optional.)
    expect([...meta.QUOTATION_BLOCKING_CHECKS].sort()).toEqual(
      ['creditDaysInvalid', 'customer', 'deal', 'entryChannel', 'items', 'liveDirectQuotation', 'livePricingRequest',
        'locationLabels', 'orderedByEmail', 'priceModeLanguage', 'project', 'recipient', 'recipientOffRoute'],
    );
    // The submit-only set: บันทึกร่าง still works, ส่งขออนุมัติ is disabled (QuotationEditorPage guards).
    expect([...meta.QUOTATION_SUBMIT_BLOCKING_CHECKS].sort()).toEqual(['creditDays', 'fullPaymentTerm', 'leadTime']);
    // Wrong-way-round: no header field a customer might simply not have is blocking, and the
    // blank-creditDays reminder is a warning, not a blocker.
    ['customerAddress', 'customerTaxId', 'customerPhone', 'dealProject', 'creditDays']
      .forEach((check) => expect(meta.QUOTATION_BLOCKING_CHECKS.has(check)).toBe(false));
    // The CONTACT/CONTACT_PHONE/CONTACT_EMAIL check names themselves no longer exist at all.
    expect(Object.values(meta.QUOTATION_CHECK)).not.toContain('contact');
    expect(Object.values(meta.QUOTATION_CHECK)).not.toContain('contactPhone');
    expect(Object.values(meta.QUOTATION_CHECK)).not.toContain('contactEmail');
  });

  it('is empty for a complete quotation', () => {
    expect(meta.buildQuotationChecklist(complete)).toEqual([]);
  });

  it('slice 2: a NEW direct quotation without a recipient blocks; any of the three recipients clears it', () => {
    const missing = meta.buildQuotationChecklist({ ...complete, recipientRequired: true, recipientType: '' });
    expect(missing).toEqual([expect.objectContaining({ check: 'recipient', blocking: true, message: 'ต้องเลือกผู้รับใบเสนอราคา' })]);
    for (const code of ['DESIGNER', 'OWNER', 'BUYER']) {
      expect(meta.buildQuotationChecklist({ ...complete, recipientRequired: true, recipientType: code })).toEqual([]);
    }
    // UNSPECIFIED is never a choice.
    expect(meta.buildQuotationChecklist({ ...complete, recipientRequired: true, recipientType: 'UNSPECIFIED' })
      .map((e) => e.check)).toEqual(['recipient']);
    // Not required (an existing row): never listed.
    expect(meta.buildQuotationChecklist({ ...complete, recipientType: '' })).toEqual([]);
  });

  it('slice 2: N6 — a live direct quotation on the deal blocks with the server\'s own sentence; a dead one does not', () => {
    const live = { id: 77, number: 'QT-2026-0077-1', docStatus: 'PENDING_APPROVAL' };
    expect(meta.buildQuotationChecklist({ ...complete, liveDirectQuotation: live })).toEqual([expect.objectContaining({
      check: 'liveDirectQuotation',
      blocking: true,
      message: 'งานนี้มีใบเสนอราคาที่ใช้งานอยู่ (QT-2026-0077-1) — แก้ไขฉบับนั้น หรือสร้างฉบับแก้ไขแทนการออกเลขใหม่',
    })]);
    expect(meta.buildQuotationChecklist({ ...complete, liveDirectQuotation: { ...live, docStatus: 'CANCELLED' } })).toEqual([]);
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
      [true, 'thickness-0', 'รายการที่ 1: ขาด ความหนา, แผ่น/ตร.ม., แผ่น/กล่อง, ราคา, จำนวน (พื้นที่)'],
      [true, 'plain-unit-1', 'รายการที่ 2: ขาด หน่วย'],
      [true, 'adj-pct-2', 'รายการที่ 3: ขาด เปอร์เซ็นต์ส่วนลด'],
    ]);
  });

  // ── CREDIT remainder needs at least 1 credit day ──
  it('BLOCKS an explicit invalid (0) creditDays under a CREDIT remainder', () => {
    const entries = meta.buildQuotationChecklist({ ...complete, remainderMode: 'CREDIT', creditDays: 0 });
    expect(entries).toEqual([{
      check: 'creditDaysInvalid', message: 'กรุณาระบุจำนวนวันเครดิต อย่างน้อย 1 วัน',
      targetId: 'creditDays', blocking: true, submitBlocking: false,
    }]);
  });
});

describe('isEffectiveZeroDeposit', () => {
  it('is true when the "ไม่รับมัดจำ" checkbox is ticked, regardless of the percent fields', () => {
    expect(meta.isEffectiveZeroDeposit({ noDeposit: true })).toBe(true);
    expect(meta.isEffectiveZeroDeposit({ noDeposit: true, depositPercentCustom: false, depositPercent: 30 })).toBe(true);
  });
});

// The SAME five rules as DealQuotationRenderAdapter#hasSpecialPricing's test class
// (DealQuotationRenderAdapterV3Test), mirrored so the editor's toggle and the server's save gate
// can never disagree about what counts as "special pricing".
describe('hasSpecialPricing (#V178)', () => {
  const tile = (overrides = {}) => ({
    lineType: LINE_TYPE_TILE, unitPrice: 100, netUnitPrice: 100, discountPct: null, ...overrides,
  });

  it('(a) SPECIAL_SQM with a TILE row is true, regardless of discount', () => {
    expect(hasSpecialPricing('SPECIAL_SQM', [tile()])).toBe(true);
  });

  it('the (b) counter-case: DIRECT_NET with net == list price is false', () => {
    expect(hasSpecialPricing('DIRECT_NET', [tile({ unitPrice: 100, netUnitPrice: 100 })])).toBe(false);
  });
});

// Mirrors DealQuotationRenderAdapter#depositLine branch for branch (the printed remark 2).
describe('depositTermsSummary', () => {
  it('100% is full payment up front, and a blank percent is the PDF default 30%', () => {
    expect(meta.depositTermsSummary({ depositPercent: 100 })).toBe('ชำระเต็มจำนวน 100% เมื่อสั่งซื้อสินค้า');
    expect(meta.depositTermsSummary({ depositPercent: null })).toBe('มัดจำ 30% · ส่วนที่เหลือก่อนหรือเมื่อส่งมอบสินค้า');
  });
});

describe('deal parties — the per-party live-quotation block (V204)', () => {
  it('picks the named party, else the deal customer\'s party preferring the recipient role', () => {
    const { quotationTargetParty } = meta;
    const parties = [
      { id: 1, customerId: 5, role: 'DESIGNER' },
      { id: 2, customerId: 5, role: 'BUYER' },
      { id: 3, customerId: 7, role: 'BUYER' },
    ];
    expect(quotationTargetParty(parties, { partyId: 3 })?.id).toBe(3);
    expect(quotationTargetParty(parties, { customerId: 5, recipientType: 'BUYER' })?.id).toBe(2);
    expect(quotationTargetParty(parties, { customerId: 5, recipientType: 'OWNER' })?.id).toBe(1);
    expect(quotationTargetParty(undefined, { customerId: 5 })).toBeNull();
  });
});
