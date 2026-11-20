import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { ALLOWED_TRANSITIONS, canActOnPricingDecision, canCancelPricingRequest, canConfirmOrder, canCreateCustomerQuotation, canCreateDepositNoticeFromQuotation, canCreatePricingRequest, canPickupPricingRequest, canRecordCustomerQuotationOutcome, canSeePricingDecisionSalesView, canSeeRawPricingDecision, canStartCeoReview, canSubmitPricingRequest, canTransition, canUpdatePricingRequest } from './pricingRequestMeta.js';

const salesOwner = { id: 1, role: 'sales' };
const otherSales = { id: 2, role: 'sales' };
const ceo = { id: 9, role: 'ceo' };
const importUser = { id: 5, role: 'import' };

const activeDeal = { createdById: 1, lifecycle: 'ACTIVE' };
function pr(overrides = {}) {
  return {
    id: 1,
    ticketCreatedById: 1,
    status: 'DRAFT',
    assignedImportId: null,
    ...overrides,
  };
}

/** THE GUARD THIS FILE EXISTS TO BE — issue #743. Re-syncing the expectations by hand would only restart that
 * clock. What was here before was a hand-written list of expectations under the heading `it('mirrors
 * PricingRequestStatus.ALLOWED')`, and the heading was false. */

const JAVA_RELATIVE_PATH = 'backend/src/main/java/th/co/glr/hr/pricingrequest/PricingRequestStatus.java';

/** Walk up from the working directory to the repo root and read the Java. */
function readPricingRequestStatusJava() {
  let dir = process.cwd();
  for (let depth = 0; depth < 8; depth += 1) {
    const candidate = resolve(dir, JAVA_RELATIVE_PATH);
    if (existsSync(candidate)) return readFileSync(candidate, 'utf8');
    const parent = dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  throw new Error(
    `could not find ${JAVA_RELATIVE_PATH} above ${process.cwd()} — this guard compares `
    + 'ALLOWED_TRANSITIONS against PricingRequestStatus.ALLOWED and must not silently skip',
  );
}

const javaSource = readPricingRequestStatusJava();

/** Parse `ALLOWED = Map.ofEntries(Map.entry(FROM, Set.of(TO, TO)), ...)` into `{ FROM: [TO, ...] }`. Every
 * constant in that block is a bare identifier (`DRAFT`, `SUBMITTED`) whose string value is identical to its
 * name — asserted below rather than assumed, because the whole guard rests on it. */
function parseJavaAllowedTransitions() {
  const block = javaSource.match(/ALLOWED\s*=\s*Map\.ofEntries\(([\s\S]*?)\);/);
  if (!block) throw new Error('could not find ALLOWED = Map.ofEntries(...) in PricingRequestStatus.java');
  const withoutComments = block[1]
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/\/\/[^\n]*/g, '');
  const entries = {};
  const entryPattern = /Map\.entry\(\s*([A-Z_]+)\s*,\s*Set\.(?:<String>)?of\(([^)]*)\)\s*\)/g;
  let match = entryPattern.exec(withoutComments);
  while (match !== null) {
    entries[match[1]] = match[2].split(',').map((token) => token.trim()).filter(Boolean);
    match = entryPattern.exec(withoutComments);
  }
  return entries;
}

/** Every `public static final String NAME = "VALUE";` in the class, as NAME -> VALUE. */
function parseJavaStatusConstants() {
  const constants = {};
  const pattern = /public\s+static\s+final\s+String\s+([A-Z_]+)\s*=\s*"([^"]+)"\s*;/g;
  let match = pattern.exec(javaSource);
  while (match !== null) {
    constants[match[1]] = match[2];
    match = pattern.exec(javaSource);
  }
  return constants;
}

const javaAllowed = parseJavaAllowedTransitions();
const javaConstants = parseJavaStatusConstants();

describe('PricingRequestStatus.ALLOWED is the state machine, and this side must not disagree', () => {
  it('parsed the Java at all — a vacuous parse must fail, not pass', () => {
    // Without this, a regex that stopped matching would make every assertion below trivially true against an empty
    // object. This is the failure mode that lets a "guard" pass forever. ELEVEN, not twelve: the twelfth was
    // COSTING_REVISION_REQUIRED, which V141 deleted.
    expect(Object.keys(javaAllowed).length).toBeGreaterThanOrEqual(11);
    expect(javaAllowed.DRAFT).toContain('SUBMITTED');
    expect(javaAllowed.QUOTATION_ACCEPTED).toEqual([]);
    expect(Object.keys(javaConstants).length).toBeGreaterThanOrEqual(11);
  });

  it('declares exactly the backend\'s statuses — no extra key, no missing key', () => {
    expect(Object.keys(ALLOWED_TRANSITIONS).sort()).toEqual(Object.keys(javaAllowed).sort());
    // Named explicitly as well as covered above: this is the status V141 deleted and the frontend kept, and the
    // reason the mock and production disagreed on the CEO return path (#741).
    expect(ALLOWED_TRANSITIONS.COSTING_REVISION_REQUIRED).toBeUndefined();
  });

  it('declares exactly the backend\'s edges for every status', () => {
    for (const [from, targets] of Object.entries(javaAllowed)) {
      expect([...(ALLOWED_TRANSITIONS[from] ?? [])].sort(), `edges out of ${from}`)
        .toEqual([...targets].sort());
    }
  });

  it('canTransition answers the parsed machine in BOTH directions', () => {
    // Not just "the declared edges are allowed" — every non-edge between two real statuses must be refused. The
    // old suite only ever spot-checked a handful of negatives, hand-picked, which is how three wrong answers sat
    // in it.
    const statuses = Object.keys(javaAllowed);
    for (const from of statuses) {
      for (const to of statuses) {
        expect(canTransition(from, to), `${from} -> ${to}`).toBe(javaAllowed[from].includes(to));
      }
    }
  });
});

describe('canCreatePricingRequest', () => {
  it('allows the deal owner (sales) on an ACTIVE deal', () => {
    expect(canCreatePricingRequest(salesOwner, activeDeal)).toBe(true);
  });
  it('rejects a non-owner sales rep', () => {
    expect(canCreatePricingRequest(otherSales, activeDeal)).toBe(false);
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
});

describe('canStartCeoReview', () => {
  it('allows the CEO on a READY_FOR_CEO_REVIEW request', () => {
    expect(canStartCeoReview(ceo, pr({ status: 'READY_FOR_CEO_REVIEW' }))).toBe(true);
  });
  it('rejects a non-ceo role', () => {
    expect(canStartCeoReview(importUser, pr({ status: 'READY_FOR_CEO_REVIEW' }))).toBe(false);
  });
});

describe('canActOnPricingDecision', () => {
  it('allows the CEO while CEO_REVIEWING', () => {
    expect(canActOnPricingDecision(ceo, pr({ status: 'CEO_REVIEWING' }))).toBe(true);
  });
  it('rejects a non-ceo role even while CEO_REVIEWING', () => {
    expect(canActOnPricingDecision(importUser, pr({ status: 'CEO_REVIEWING' }))).toBe(false);
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
  const approved = (overrides = {}) => pr({ status: 'APPROVED_FOR_QUOTATION', ...overrides });
  it('allows the owning sales rep', () => {
    expect(canSeePricingDecisionSalesView(salesOwner, approved())).toBe(true);
  });
  it('rejects a non-owning sales rep — wrong-way-round', () => {
    expect(canSeePricingDecisionSalesView(otherSales, approved())).toBe(false);
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
});

describe('canRecordCustomerQuotationOutcome', () => {
  it('allows the owner sales rep only while the quotation is ISSUED', () => {
    expect(canRecordCustomerQuotationOutcome(salesOwner, pr(), { docStatus: 'ISSUED' })).toBe(true);
  });
  it('rejects a non-owning sales rep, ceo, and import', () => {
    expect(canRecordCustomerQuotationOutcome(otherSales, pr(), { docStatus: 'ISSUED' })).toBe(false);
    expect(canRecordCustomerQuotationOutcome(ceo, pr(), { docStatus: 'ISSUED' })).toBe(false);
    expect(canRecordCustomerQuotationOutcome(importUser, pr(), { docStatus: 'ISSUED' })).toBe(false);
  });
});

describe('canConfirmOrder', () => {
  it('allows the owner sales rep only from QUOTATION_ACCEPTED, before the bridge has run', () => {
    expect(canConfirmOrder(salesOwner, pr({ status: 'QUOTATION_ACCEPTED' }))).toBe(true);
  });
  it('rejects every other pricing-request status, even for the owner', () => {
    for (const status of ['DRAFT', 'SUBMITTED', 'QUOTATION_ISSUED', 'CANCELLED', 'SUPERSEDED']) {
      expect(canConfirmOrder(salesOwner, pr({ status }))).toBe(false);
    }
  });
});

describe('canCreateDepositNoticeFromQuotation', () => {
  it('allows the owner sales rep only AFTER the bridge has run (orderConfirmedAt set)', () => {
    expect(canCreateDepositNoticeFromQuotation(salesOwner,
      pr({ status: 'QUOTATION_ACCEPTED', orderConfirmedAt: '2026-07-21T00:00:00Z' }))).toBe(true);
  });
  // PR #1173 review F4: the server 409s a deposit notice for a ไม่รับมัดจำ quotation (and for the
  // bypass policy the confirmation now sets), so the button must not be offered.
  it('rejects an accepted new-engine quotation that says ไม่รับมัดจำ (0%)', () => {
    const confirmed = pr({ status: 'QUOTATION_ACCEPTED', orderConfirmedAt: '2026-07-21T00:00:00Z' });
    expect(canCreateDepositNoticeFromQuotation(salesOwner, confirmed,
      { docStatus: 'ACCEPTED', depositPercent: 0 })).toBe(false);
    expect(canCreateDepositNoticeFromQuotation(salesOwner, confirmed,
      { docStatus: 'ACCEPTED', depositPercent: 30 })).toBe(true);
    // A blank percent is NOT ไม่รับมัดจำ: the PDF prints it as 30% (#1170), so the button stays.
    expect(canCreateDepositNoticeFromQuotation(salesOwner, confirmed,
      { docStatus: 'ACCEPTED', depositPercent: null })).toBe(true);
    expect(canCreateDepositNoticeFromQuotation(salesOwner, confirmed, null)).toBe(true); // legacy chain
  });

  // Owner ruling 2026-10-23 (DepositNoticeService.createDraftFromQuotation): one notice per deal.
  it('rejects once the deal has an ISSUED deposit notice; a DRAFT or SUPERSEDED one does not block', () => {
    const confirmed = pr({ status: 'QUOTATION_ACCEPTED', orderConfirmedAt: '2026-07-21T00:00:00Z' });
    expect(canCreateDepositNoticeFromQuotation(salesOwner, confirmed, null,
      [{ id: 1, status: 'SUPERSEDED' }, { id: 2, status: 'ISSUED', docNumber: 'GLRD69001' }])).toBe(false);
    expect(canCreateDepositNoticeFromQuotation(salesOwner, confirmed, null,
      [{ id: 1, status: 'SUPERSEDED' }, { id: 3, status: 'DRAFT' }])).toBe(true);
  });
});

describe('canCancelPricingRequest', () => {
  it('allows the owner sales rep on a cancellable status', () => {
    expect(canCancelPricingRequest(salesOwner, pr({ status: 'DRAFT' }))).toBe(true);
    expect(canCancelPricingRequest(salesOwner, pr({ status: 'SUBMITTED' }))).toBe(true);
  });
  it('rejects a non-owner, non-ceo actor', () => {
    expect(canCancelPricingRequest(otherSales, pr({ status: 'DRAFT' }))).toBe(false);
  });
});
