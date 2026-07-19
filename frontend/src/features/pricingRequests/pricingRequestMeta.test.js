import { describe, expect, it } from 'vitest';
import {
  activePricingRequestsSummary,
  canCancelPricingRequest,
  canCreatePricingRequest,
  canPickupPricingRequest,
  canRequestInformation,
  canRespondInformation,
  canSubmitPricingRequest,
  canTransition,
  canUpdatePricingRequest,
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
    expect(canTransition('IMPORT_REVIEWING', 'CANCELLED')).toBe(false);
    expect(canTransition('MORE_INFO_REQUIRED', 'IMPORT_REVIEWING')).toBe(true);
    expect(canTransition('MORE_INFO_REQUIRED', 'CANCELLED')).toBe(true);
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
  it('allows only the ASSIGNED import user while IMPORT_REVIEWING', () => {
    const reviewing = pr({ status: 'IMPORT_REVIEWING', assignedImportId: 5 });
    expect(canRequestInformation(importUser, reviewing)).toBe(true);
    expect(canRequestInformation(otherImport, reviewing)).toBe(false);
  });
  it('rejects an unassigned request', () => {
    expect(canRequestInformation(importUser, pr({ status: 'IMPORT_REVIEWING', assignedImportId: null }))).toBe(false);
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
  it('rejects IMPORT_REVIEWING (not a normal-flow cancel target)', () => {
    expect(canCancelPricingRequest(salesOwner, pr({ status: 'IMPORT_REVIEWING' }))).toBe(false);
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
