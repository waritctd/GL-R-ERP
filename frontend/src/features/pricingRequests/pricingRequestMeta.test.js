import { describe, expect, it } from 'vitest';
import {
  activePricingRequestsSummary,
  canActOnPricingDecision,
  canCancelPricingRequest,
  canCreateCustomerQuotation,
  canCreatePricingRequest,
  canManageCustomerQuotation,
  canPickupPricingRequest,
  canRequestInformation,
  canRespondInformation,
  canSeePricingDecisionSalesView,
  canSeeRawPricingDecision,
  canStartCeoReview,
  canSubmitPricingRequest,
  canTransition,
  canUpdatePricingRequest,
  canViewCustomerQuotation,
  isCustomerQuotationEditable,
  pricingRequestRecipientLabel,
  quantityTypeLabel,
} from './pricingRequestMeta.js';

const salesOwner = { id: 1, role: 'sales' };
const otherSales = { id: 2, role: 'sales' };
const ceo = { id: 9, role: 'ceo' };
const importUser = { id: 5, role: 'import' };
const otherImport = { id: 6, role: 'import' };

const activeDeal = { createdById: 1, lifecycle: 'ACTIVE' };
const closedDeal = { createdById: 1, lifecycle: 'CLOSED_LOST' };

function pr(overrides = {}) {
  return {
    id: 1,
    ticketCreatedById: 1,
    status: 'DRAFT',
    assignedImportId: null,
    ...overrides,
  };
}

describe('canTransition', () => {
  it('mirrors PricingRequestStatus.ALLOWED', () => {
    expect(canTransition('DRAFT', 'SUBMITTED')).toBe(true);
    expect(canTransition('DRAFT', 'CANCELLED')).toBe(true);
    expect(canTransition('DRAFT', 'DRAFT')).toBe(false);
    expect(canTransition('SUBMITTED', 'IMPORT_REVIEWING')).toBe(true);
    expect(canTransition('IMPORT_REVIEWING', 'MORE_INFO_REQUIRED')).toBe(true);
    expect(canTransition('IMPORT_REVIEWING', 'AWAITING_FACTORY_RESPONSE')).toBe(true);
    expect(canTransition('AWAITING_FACTORY_RESPONSE', 'COSTING_IN_PROGRESS')).toBe(true);
    expect(canTransition('COSTING_IN_PROGRESS', 'READY_FOR_CEO_REVIEW')).toBe(true);
    expect(canTransition('IMPORT_REVIEWING', 'CANCELLED')).toBe(true);
    expect(canTransition('MORE_INFO_REQUIRED', 'IMPORT_REVIEWING')).toBe(true);
    expect(canTransition('MORE_INFO_REQUIRED', 'CANCELLED')).toBe(true);
    expect(canTransition('READY_FOR_CEO_REVIEW', 'SUPERSEDED')).toBe(true);
    // Step 3 (CEO Selling Price Decision, "one return-to-Import path"): the old direct
    // READY_FOR_CEO_REVIEW -> COSTING_IN_PROGRESS entry (Costing v2 path, commit 5) is removed —
    // it let Import silently reopen a SUBMITTED costing with no CEO action, which made
    // "submitted costing is immutable" false. The CEO must now explicitly start review.
    expect(canTransition('READY_FOR_CEO_REVIEW', 'COSTING_IN_PROGRESS')).toBe(false);
    expect(canTransition('READY_FOR_CEO_REVIEW', 'CEO_REVIEWING')).toBe(true);
    expect(canTransition('CEO_REVIEWING', 'APPROVED_FOR_QUOTATION')).toBe(true);
    expect(canTransition('CEO_REVIEWING', 'COSTING_REVISION_REQUIRED')).toBe(true);
    // The single named return-to-Import state — Import reopening costing goes through here.
    expect(canTransition('COSTING_REVISION_REQUIRED', 'COSTING_IN_PROGRESS')).toBe(true);
    expect(canTransition('APPROVED_FOR_QUOTATION', 'COSTING_IN_PROGRESS')).toBe(false);
    expect(canTransition('READY_FOR_CEO_REVIEW', 'CANCELLED')).toBe(false);
    expect(canTransition('CANCELLED', 'DRAFT')).toBe(false);
  });
});

describe('canCreatePricingRequest', () => {
  it('allows the deal owner (sales) on an ACTIVE deal', () => {
    expect(canCreatePricingRequest(salesOwner, activeDeal)).toBe(true);
  });
  it('rejects a non-owner sales rep', () => {
    expect(canCreatePricingRequest(otherSales, activeDeal)).toBe(false);
  });
  it('rejects a non-ACTIVE deal even for the owner', () => {
    expect(canCreatePricingRequest(salesOwner, closedDeal)).toBe(false);
  });
  it('rejects non-sales roles', () => {
    expect(canCreatePricingRequest(ceo, activeDeal)).toBe(false);
  });
});

describe('canUpdatePricingRequest / canSubmitPricingRequest', () => {
  it('allow the owner only while DRAFT', () => {
    const draft = pr({ status: 'DRAFT' });
    expect(canUpdatePricingRequest(salesOwner, draft)).toBe(true);
    expect(canSubmitPricingRequest(salesOwner, draft)).toBe(true);
  });
  it('reject once submitted', () => {
    const submitted = pr({ status: 'SUBMITTED' });
    expect(canUpdatePricingRequest(salesOwner, submitted)).toBe(false);
    expect(canSubmitPricingRequest(salesOwner, submitted)).toBe(false);
  });
  it('reject a non-owner sales rep', () => {
    expect(canSubmitPricingRequest(otherSales, pr({ status: 'DRAFT' }))).toBe(false);
  });
});

describe('canPickupPricingRequest', () => {
  it('allows any import user when SUBMITTED', () => {
    expect(canPickupPricingRequest(importUser, pr({ status: 'SUBMITTED' }))).toBe(true);
  });
  it('rejects a non-import role', () => {
    expect(canPickupPricingRequest(salesOwner, pr({ status: 'SUBMITTED' }))).toBe(false);
  });
  it('rejects a request not in SUBMITTED', () => {
    expect(canPickupPricingRequest(importUser, pr({ status: 'DRAFT' }))).toBe(false);
  });
});

describe('canRequestInformation', () => {
  it('allows any import user while IMPORT_REVIEWING', () => {
    const reviewing = pr({ status: 'IMPORT_REVIEWING', assignedImportId: 5 });
    expect(canRequestInformation(importUser, reviewing)).toBe(true);
    expect(canRequestInformation(otherImport, reviewing)).toBe(true);
  });
  it('allows an unassigned request', () => {
    expect(canRequestInformation(importUser, pr({ status: 'IMPORT_REVIEWING', assignedImportId: null }))).toBe(true);
  });
  it('allows Step 2 in-progress statuses', () => {
    expect(canRequestInformation(importUser, pr({ status: 'AWAITING_FACTORY_RESPONSE' }))).toBe(true);
    expect(canRequestInformation(importUser, pr({ status: 'COSTING_IN_PROGRESS' }))).toBe(true);
  });
});

describe('canRespondInformation', () => {
  it('allows the owner while MORE_INFO_REQUIRED', () => {
    expect(canRespondInformation(salesOwner, pr({ status: 'MORE_INFO_REQUIRED' }))).toBe(true);
  });
  it('rejects any other status', () => {
    expect(canRespondInformation(salesOwner, pr({ status: 'IMPORT_REVIEWING' }))).toBe(false);
  });
});

describe('canStartCeoReview', () => {
  it('allows the CEO on a READY_FOR_CEO_REVIEW request', () => {
    expect(canStartCeoReview(ceo, pr({ status: 'READY_FOR_CEO_REVIEW' }))).toBe(true);
  });
  it('rejects a non-ceo role', () => {
    expect(canStartCeoReview(importUser, pr({ status: 'READY_FOR_CEO_REVIEW' }))).toBe(false);
  });
  it('rejects any other status', () => {
    expect(canStartCeoReview(ceo, pr({ status: 'COSTING_IN_PROGRESS' }))).toBe(false);
  });
});

describe('canActOnPricingDecision', () => {
  it('allows the CEO while CEO_REVIEWING', () => {
    expect(canActOnPricingDecision(ceo, pr({ status: 'CEO_REVIEWING' }))).toBe(true);
  });
  it('rejects a non-ceo role even while CEO_REVIEWING', () => {
    expect(canActOnPricingDecision(importUser, pr({ status: 'CEO_REVIEWING' }))).toBe(false);
  });
  it('rejects the ceo outside CEO_REVIEWING', () => {
    expect(canActOnPricingDecision(ceo, pr({ status: 'READY_FOR_CEO_REVIEW' }))).toBe(false);
  });
});

describe('canSeeRawPricingDecision', () => {
  it('allows import and ceo', () => {
    expect(canSeeRawPricingDecision(importUser)).toBe(true);
    expect(canSeeRawPricingDecision(ceo)).toBe(true);
  });
  it('rejects sales and sales_manager — design correction 2, never leak cost to Sales', () => {
    expect(canSeeRawPricingDecision(salesOwner)).toBe(false);
    expect(canSeeRawPricingDecision({ id: 9, role: 'sales_manager' })).toBe(false);
  });
});

describe('canSeePricingDecisionSalesView', () => {
  it('allows the owning sales rep', () => {
    expect(canSeePricingDecisionSalesView(salesOwner, pr())).toBe(true);
  });
  it('rejects a non-owning sales rep — wrong-way-round', () => {
    expect(canSeePricingDecisionSalesView(otherSales, pr())).toBe(false);
  });
  it('allows sales_manager/ceo/import regardless of ownership', () => {
    expect(canSeePricingDecisionSalesView({ id: 9, role: 'sales_manager' }, pr())).toBe(true);
    expect(canSeePricingDecisionSalesView(ceo, pr())).toBe(true);
    expect(canSeePricingDecisionSalesView(importUser, pr())).toBe(true);
  });
  it('rejects account — no access to pricing decisions at all', () => {
    expect(canSeePricingDecisionSalesView({ id: 9, role: 'account' }, pr())).toBe(false);
  });
});

// ── Step 4: Customer Quotation Generation and Issuance ───────────────────────────────────
describe('canCreateCustomerQuotation', () => {
  it('allows the owning sales rep once APPROVED_FOR_QUOTATION', () => {
    expect(canCreateCustomerQuotation(salesOwner, pr({ status: 'APPROVED_FOR_QUOTATION' }))).toBe(true);
  });
  it('rejects a non-owning sales rep even at the right status (wrong-way-round)', () => {
    expect(canCreateCustomerQuotation(otherSales, pr({ status: 'APPROVED_FOR_QUOTATION' }))).toBe(false);
  });
  it('rejects the owner before APPROVED_FOR_QUOTATION', () => {
    expect(canCreateCustomerQuotation(salesOwner, pr({ status: 'CEO_REVIEWING' }))).toBe(false);
  });
  it('rejects ceo/import entirely — quotation creation is sales-only', () => {
    expect(canCreateCustomerQuotation(ceo, pr({ status: 'APPROVED_FOR_QUOTATION' }))).toBe(false);
    expect(canCreateCustomerQuotation(importUser, pr({ status: 'APPROVED_FOR_QUOTATION' }))).toBe(false);
  });
});

describe('canManageCustomerQuotation', () => {
  it('allows only the owning sales rep, regardless of status (the service re-checks docStatus itself)', () => {
    expect(canManageCustomerQuotation(salesOwner, pr())).toBe(true);
    expect(canManageCustomerQuotation(otherSales, pr())).toBe(false);
  });
  it('rejects ceo/import/sales_manager — always read-only on this aggregate', () => {
    expect(canManageCustomerQuotation(ceo, pr())).toBe(false);
    expect(canManageCustomerQuotation(importUser, pr())).toBe(false);
    expect(canManageCustomerQuotation({ id: 9, role: 'sales_manager' }, pr())).toBe(false);
  });
});

describe('canViewCustomerQuotation', () => {
  it('scopes sales to their own deal', () => {
    expect(canViewCustomerQuotation(salesOwner, pr())).toBe(true);
    expect(canViewCustomerQuotation(otherSales, pr())).toBe(false);
  });
  it('allows sales_manager/ceo/import unscoped', () => {
    expect(canViewCustomerQuotation({ id: 9, role: 'sales_manager' }, pr())).toBe(true);
    expect(canViewCustomerQuotation(ceo, pr())).toBe(true);
    expect(canViewCustomerQuotation(importUser, pr())).toBe(true);
  });
  it('rejects account — no positive grant anywhere on this aggregate', () => {
    expect(canViewCustomerQuotation({ id: 9, role: 'account' }, pr())).toBe(false);
  });
});

describe('isCustomerQuotationEditable', () => {
  it('is true for DRAFT and READY_TO_ISSUE, false for every other status', () => {
    expect(isCustomerQuotationEditable({ docStatus: 'DRAFT' })).toBe(true);
    expect(isCustomerQuotationEditable({ docStatus: 'READY_TO_ISSUE' })).toBe(true);
    expect(isCustomerQuotationEditable({ docStatus: 'ISSUED' })).toBe(false);
    expect(isCustomerQuotationEditable({ docStatus: 'CANCELLED' })).toBe(false);
    expect(isCustomerQuotationEditable({ docStatus: 'SUPERSEDED' })).toBe(false);
  });
});

describe('canCancelPricingRequest', () => {
  it('allows the owner sales rep on a cancellable status', () => {
    expect(canCancelPricingRequest(salesOwner, pr({ status: 'DRAFT' }))).toBe(true);
    expect(canCancelPricingRequest(salesOwner, pr({ status: 'SUBMITTED' }))).toBe(true);
  });
  it('allows the CEO regardless of ownership', () => {
    expect(canCancelPricingRequest(ceo, pr({ status: 'MORE_INFO_REQUIRED' }))).toBe(true);
  });
  it('rejects a non-owner, non-ceo actor', () => {
    expect(canCancelPricingRequest(otherSales, pr({ status: 'DRAFT' }))).toBe(false);
  });
  it('allows active Import workflow statuses to cancel', () => {
    expect(canCancelPricingRequest(salesOwner, pr({ status: 'IMPORT_REVIEWING' }))).toBe(true);
  });
  it('rejects an already-cancelled request', () => {
    expect(canCancelPricingRequest(ceo, pr({ status: 'CANCELLED' }))).toBe(false);
  });
});

// Fix 3 (review-remediation plan): replaces the old latestPricingRequest
// (highest-id-wins) reduction, which hid concurrent requests from the deal
// summary strip — see DealStagePanel.test.jsx for the UI-level regression
// cases this was written to prevent.
describe('activePricingRequestsSummary', () => {
  it('returns null for an empty list', () => {
    expect(activePricingRequestsSummary([])).toBeNull();
  });

  it('returns null when every request is cancelled', () => {
    expect(activePricingRequestsSummary([
      pr({ id: 1, status: 'CANCELLED' }),
      pr({ id: 2, status: 'CANCELLED' }),
    ])).toBeNull();
  });

  it('excludes cancelled requests but keeps every other status, oldest first', () => {
    const summary = activePricingRequestsSummary([
      pr({ id: 3, status: 'DRAFT' }),
      pr({ id: 1, status: 'IMPORT_REVIEWING' }),
      pr({ id: 2, status: 'CANCELLED' }),
    ]);
    expect(summary.total).toBe(2);
    expect(summary.requests.map((r) => r.id)).toEqual([1, 3]);
  });

  it('counts requests per status', () => {
    const summary = activePricingRequestsSummary([
      pr({ id: 1, status: 'DRAFT' }),
      pr({ id: 2, status: 'DRAFT' }),
      pr({ id: 3, status: 'SUBMITTED' }),
    ]);
    expect(summary.counts).toEqual({ DRAFT: 2, SUBMITTED: 1 });
  });
});

describe('label helpers', () => {
  it('pricingRequestRecipientLabel falls back to the raw value', () => {
    expect(pricingRequestRecipientLabel('DESIGNER')).toContain('ผู้ออกแบบ');
    expect(pricingRequestRecipientLabel('UNKNOWN_X')).toBe('UNKNOWN_X');
  });
  it('quantityTypeLabel falls back to the raw value', () => {
    expect(quantityTypeLabel('CONFIRMED')).toContain('ยืนยันแล้ว');
    expect(quantityTypeLabel('UNKNOWN_X')).toBe('UNKNOWN_X');
  });
});
