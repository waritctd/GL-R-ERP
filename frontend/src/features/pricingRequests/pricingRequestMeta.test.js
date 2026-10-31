import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  ALLOWED_TRANSITIONS,
  activePricingRequestsSummary,
  ceoGateBlockerMessage,
  canActOnPricingDecision,
  canCancelPricingRequest,
  canConfirmOrder,
  confirmOrderBlockedReason,
  canCreateCommercialOnlyRevision,
  canCreateCustomerQuotation,
  canCreateDepositNoticeFromQuotation,
  canCreatePricingRequest,
  canManageCustomerQuotation,
  canPickupPricingRequest,
  canRecordCustomerQuotationOutcome,
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

/**
 * THE GUARD THIS FILE EXISTS TO BE — issue #743.
 *
 * What was here before was a hand-written list of expectations under the heading
 * `it('mirrors PricingRequestStatus.ALLOWED')`, and the heading was false. It asserted
 * `canTransition('CEO_REVIEWING','COSTING_REVISION_REQUIRED') === true` for a status V141 DELETED
 * from both chk_pricing_request_status and PricingRequestStatus.VALUES, and
 * `canTransition('READY_FOR_CEO_REVIEW','CANCELLED') === false` months after #718 made it true.
 *
 * That is worse than having no test. Anyone who correctly re-synced ALLOWED_TRANSITIONS to the
 * backend turned this suite red and was told, by the test's own name, that they had broken the
 * mirror — so the stale table was actively HELD in place. It is why the missing pricing-request
 * cancel button and the dead COSTING_REVISION_REQUIRED status both survived PRs #703-#726 with a
 * fully green 1562-test suite.
 *
 * Re-syncing the expectations by hand would only restart that clock. So this now does what
 * `features/tickets/stageCatalog.test.js` and `features/tickets/dealTrackingMeta.test.js` already
 * do, and what nothing on the pricing-request side did: it PARSES PricingRequestStatus.java out of
 * the backend source tree and asserts the frontend table against it, key for key and value for
 * value, in both directions. A future backend change to ALLOWED now fails here instead of going
 * unnoticed.
 *
 * The durable fix remains serving the verdict on the DTO the way `stageDecisions` does for deal
 * stages, at which point there is no mirror left to test. Until that exists there is no served
 * answer to read, and a parse is the strongest guard available.
 */

const JAVA_RELATIVE_PATH = 'backend/src/main/java/th/co/glr/hr/pricingrequest/PricingRequestStatus.java';

/**
 * Walk up from the working directory to the repo root and read the Java. Deliberately THROWS when
 * the file cannot be found rather than skipping — a guard that quietly disables itself when the
 * layout moves is the same silent failure it exists to prevent. (Same shape as
 * stageCatalog.test.js's reader; `import.meta.url` is not a file: URL under Vite's transform.)
 */
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

/**
 * Parse `ALLOWED = Map.ofEntries(Map.entry(FROM, Set.of(TO, TO)), ...)` into `{ FROM: [TO, ...] }`.
 *
 * Every constant in that block is a bare identifier (`DRAFT`, `SUBMITTED`) whose string value is
 * identical to its name — asserted below rather than assumed, because the whole guard rests on it.
 * Comments are stripped first so a commented-out edge is not read as live; `Set.<String>of()` (the
 * terminal statuses) is matched as an empty list.
 */
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
    // Without this, a regex that stopped matching would make every assertion below trivially true
    // against an empty object. This is the failure mode that lets a "guard" pass forever.
    // ELEVEN, not twelve: the twelfth was COSTING_REVISION_REQUIRED, which V141 deleted. The
    // source file's own comment still said "twelve statuses" — a stale count in the prose above a
    // stale table, which is the shape of this whole issue.
    expect(Object.keys(javaAllowed).length).toBeGreaterThanOrEqual(11);
    expect(javaAllowed.DRAFT).toContain('SUBMITTED');
    expect(javaAllowed.QUOTATION_ACCEPTED).toEqual([]);
    expect(Object.keys(javaConstants).length).toBeGreaterThanOrEqual(11);
  });

  it('every status constant\'s Java NAME equals its string VALUE', () => {
    // The parse reads identifiers out of the ALLOWED block and compares them to the frontend's
    // string keys. That is only sound while name and value coincide — so assert it rather than
    // rely on it. A renamed-but-not-revalued constant would otherwise make this whole file lie.
    for (const [name, value] of Object.entries(javaConstants)) {
      expect(value, `PricingRequestStatus.${name}`).toBe(name);
    }
  });

  it('declares exactly the backend\'s statuses — no extra key, no missing key', () => {
    expect(Object.keys(ALLOWED_TRANSITIONS).sort()).toEqual(Object.keys(javaAllowed).sort());
    // Named explicitly as well as covered above: this is the status V141 deleted and the frontend
    // kept, and the reason the mock and production disagreed on the CEO return path (#741).
    expect(ALLOWED_TRANSITIONS.COSTING_REVISION_REQUIRED).toBeUndefined();
  });

  it('declares exactly the backend\'s edges for every status', () => {
    for (const [from, targets] of Object.entries(javaAllowed)) {
      expect([...(ALLOWED_TRANSITIONS[from] ?? [])].sort(), `edges out of ${from}`)
        .toEqual([...targets].sort());
    }
  });

  it('canTransition answers the parsed machine in BOTH directions', () => {
    // Not just "the declared edges are allowed" — every non-edge between two real statuses must be
    // refused. The old suite only ever spot-checked a handful of negatives, hand-picked, which is
    // how three wrong answers sat in it.
    const statuses = Object.keys(javaAllowed);
    for (const from of statuses) {
      for (const to of statuses) {
        expect(canTransition(from, to), `${from} -> ${to}`).toBe(javaAllowed[from].includes(to));
      }
    }
  });

  it('refuses retired statuses and null-ish input', () => {
    // COSTING_IN_PROGRESS (V140) and MORE_INFO_REQUIRED (V140) are gone from the product; the
    // loop above cannot see them because they are no longer in the Java at all.
    for (const dead of ['COSTING_IN_PROGRESS', 'MORE_INFO_REQUIRED', 'COSTING_REVISION_REQUIRED']) {
      expect(javaAllowed[dead], `${dead} must be absent from the Java`).toBeUndefined();
      expect(canTransition('CEO_REVIEWING', dead)).toBe(false);
      expect(canTransition(dead, 'AWAITING_FACTORY_RESPONSE')).toBe(false);
    }
    expect(canTransition('DRAFT', 'DRAFT')).toBe(false);
    expect(canTransition(null, 'SUBMITTED')).toBe(false);
    expect(canTransition('DRAFT', null)).toBe(false);
    expect(canTransition(undefined, undefined)).toBe(false);
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
  // Walk 4 (W4-04): once the deal's order is confirmed (payment track started) no new request may be created.
  it('rejects the owner once the deal order is confirmed (paymentStatus set)', () => {
    expect(canCreatePricingRequest(salesOwner, { ...activeDeal, paymentStatus: 'CUSTOMER_CONFIRMED' })).toBe(false);
    expect(canCreatePricingRequest(salesOwner, { ...activeDeal, paymentStatus: null })).toBe(true);
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
  it('reject a DRAFT once the deal order is confirmed (ticketPaymentStatus set)', () => {
    expect(canSubmitPricingRequest(salesOwner, pr({ status: 'DRAFT', ticketPaymentStatus: 'CUSTOMER_CONFIRMED' }))).toBe(false);
    expect(canSubmitPricingRequest(salesOwner, pr({ status: 'DRAFT', ticketPaymentStatus: null }))).toBe(true);
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
  const approved = (overrides = {}) => pr({ status: 'APPROVED_FOR_QUOTATION', ...overrides });
  it('allows the owning sales rep', () => {
    expect(canSeePricingDecisionSalesView(salesOwner, approved())).toBe(true);
  });
  it('rejects a non-owning sales rep — wrong-way-round', () => {
    expect(canSeePricingDecisionSalesView(otherSales, approved())).toBe(false);
  });
  it('allows sales_manager/ceo/import regardless of ownership', () => {
    expect(canSeePricingDecisionSalesView({ id: 9, role: 'sales_manager' }, approved())).toBe(true);
    expect(canSeePricingDecisionSalesView(ceo, approved())).toBe(true);
    expect(canSeePricingDecisionSalesView(importUser, approved())).toBe(true);
  });
  it('rejects account — no access to pricing decisions at all', () => {
    expect(canSeePricingDecisionSalesView({ id: 9, role: 'account' }, approved())).toBe(false);
  });
  // W6-04: the server answers 404 until the CEO approves, and again once the request is dead.
  it('only while an APPROVED decision exists: approved → quoted → accepted', () => {
    for (const status of ['APPROVED_FOR_QUOTATION', 'QUOTATION_ISSUED', 'QUOTATION_ACCEPTED']) {
      expect(canSeePricingDecisionSalesView(salesOwner, pr({ status }))).toBe(true);
    }
    for (const status of ['DRAFT', 'SUBMITTED', 'IMPORT_REVIEWING', 'READY_FOR_CEO_REVIEW', 'CEO_REVIEWING', 'CANCELLED', 'SUPERSEDED']) {
      expect(canSeePricingDecisionSalesView(salesOwner, pr({ status }))).toBe(false);
    }
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

describe('canRecordCustomerQuotationOutcome', () => {
  it('allows the owner sales rep only while the quotation is ISSUED', () => {
    expect(canRecordCustomerQuotationOutcome(salesOwner, pr(), { docStatus: 'ISSUED' })).toBe(true);
  });
  it('rejects every other docStatus, even for the owner', () => {
    for (const docStatus of ['DRAFT', 'READY_TO_ISSUE', 'ACCEPTED', 'REJECTED', 'REVISION_REQUESTED', 'EXPIRED', 'CANCELLED', 'SUPERSEDED']) {
      expect(canRecordCustomerQuotationOutcome(salesOwner, pr(), { docStatus })).toBe(false);
    }
  });
  // QA-13: a revision supersedes the request but its quotation stays ISSUED (owner ruling 2026-08-13).
  it('rejects an ISSUED quotation on a dead (SUPERSEDED / CANCELLED) request', () => {
    for (const status of ['SUPERSEDED', 'CANCELLED']) {
      expect(canRecordCustomerQuotationOutcome(salesOwner, pr({ status }), { docStatus: 'ISSUED' })).toBe(false);
    }
  });
  it('rejects a non-owning sales rep, ceo, and import', () => {
    expect(canRecordCustomerQuotationOutcome(otherSales, pr(), { docStatus: 'ISSUED' })).toBe(false);
    expect(canRecordCustomerQuotationOutcome(ceo, pr(), { docStatus: 'ISSUED' })).toBe(false);
    expect(canRecordCustomerQuotationOutcome(importUser, pr(), { docStatus: 'ISSUED' })).toBe(false);
  });
});

describe('canCreateCommercialOnlyRevision', () => {
  it('allows the owner sales rep from ISSUED or REVISION_REQUESTED', () => {
    expect(canCreateCommercialOnlyRevision(salesOwner, pr(), { docStatus: 'ISSUED' })).toBe(true);
    expect(canCreateCommercialOnlyRevision(salesOwner, pr(), { docStatus: 'REVISION_REQUESTED' })).toBe(true);
  });
  it('rejects every other docStatus', () => {
    for (const docStatus of ['DRAFT', 'READY_TO_ISSUE', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'CANCELLED', 'SUPERSEDED']) {
      expect(canCreateCommercialOnlyRevision(salesOwner, pr(), { docStatus })).toBe(false);
    }
  });
  it('rejects a non-owning sales rep', () => {
    expect(canCreateCommercialOnlyRevision(otherSales, pr(), { docStatus: 'ISSUED' })).toBe(false);
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
  it('rejects once orderConfirmedAt is already set (the bridge already ran)', () => {
    expect(canConfirmOrder(salesOwner, pr({ status: 'QUOTATION_ACCEPTED', orderConfirmedAt: '2026-07-21T00:00:00Z' })))
      .toBe(false);
  });
  it('rejects when the deal ticket sits in a status the bridge cannot advance (ticket-status gate)', () => {
    for (const ticketStatus of ['approved', 'price_proposed', 'in_review', 'submitted', 'document_issued', 'closed', 'cancelled']) {
      expect(canConfirmOrder(salesOwner, pr({ status: 'QUOTATION_ACCEPTED', ticketStatus }))).toBe(false);
    }
  });
  it('allows the two ticket statuses the bridge handles, and an absent ticketStatus (server still guards)', () => {
    for (const ticketStatus of ['draft', 'quotation_issued', null, undefined]) {
      expect(canConfirmOrder(salesOwner, pr({ status: 'QUOTATION_ACCEPTED', ticketStatus }))).toBe(true);
    }
  });
});

describe('confirmOrderBlockedReason', () => {
  it('names the ticket status when it is the blocker', () => {
    expect(confirmOrderBlockedReason(pr({ status: 'QUOTATION_ACCEPTED', ticketStatus: 'approved' })))
      .toContain('approved');
  });
  it('is null when the ticket status is bridgeable or unknown, or the PR is not accepted yet', () => {
    expect(confirmOrderBlockedReason(pr({ status: 'QUOTATION_ACCEPTED', ticketStatus: 'draft' }))).toBeNull();
    expect(confirmOrderBlockedReason(pr({ status: 'QUOTATION_ACCEPTED' }))).toBeNull();
    expect(confirmOrderBlockedReason(pr({ status: 'DRAFT', ticketStatus: 'approved' }))).toBeNull();
  });
});

describe('canConfirmOrder (roles)', () => {
  it('rejects a non-owning sales rep, ceo, and import', () => {
    const accepted = pr({ status: 'QUOTATION_ACCEPTED' });
    expect(canConfirmOrder(otherSales, accepted)).toBe(false);
    expect(canConfirmOrder(ceo, accepted)).toBe(false);
    expect(canConfirmOrder(importUser, accepted)).toBe(false);
  });
});

describe('canCreateDepositNoticeFromQuotation', () => {
  it('allows the owner sales rep only AFTER the bridge has run (orderConfirmedAt set)', () => {
    expect(canCreateDepositNoticeFromQuotation(salesOwner,
      pr({ status: 'QUOTATION_ACCEPTED', orderConfirmedAt: '2026-07-21T00:00:00Z' }))).toBe(true);
  });
  it('rejects QUOTATION_ACCEPTED before the bridge has run', () => {
    expect(canCreateDepositNoticeFromQuotation(salesOwner, pr({ status: 'QUOTATION_ACCEPTED' }))).toBe(false);
  });
  it('rejects a non-owning sales rep', () => {
    expect(canCreateDepositNoticeFromQuotation(otherSales,
      pr({ status: 'QUOTATION_ACCEPTED', orderConfirmedAt: '2026-07-21T00:00:00Z' }))).toBe(false);
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
  it('allows the CEO regardless of ownership', () => {
    expect(canCancelPricingRequest(ceo, pr({ status: 'AWAITING_FACTORY_RESPONSE' }))).toBe(true);
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

describe('ceoGateBlockerMessage', () => {
  const AWAITING = { status: 'AWAITING_FACTORY_RESPONSE' };
  const routed = { id: 1, resolvedFactoryName: 'A' };
  const readyQuote = { factoryName: 'A', status: 'READY_FOR_COSTING', current: true, revisionNo: 1, items: [{ pricingRequestItemId: 1 }] };
  const GENERIC = 'ระบบยังไม่พบว่าครบเงื่อนไขส่งถึง CEO — ตรวจสอบรายการและราคาโรงงานอีกครั้ง';

  it('is null once the request reached the CEO', () => {
    expect(ceoGateBlockerMessage({ summary: { status: 'READY_FOR_CEO_REVIEW' }, items: [routed] }, [readyQuote])).toBeNull();
  });

  it('falls back to a neutral line (no factory blame, no repeated heading) when nothing is derivable', () => {
    expect(ceoGateBlockerMessage({ summary: AWAITING, items: [routed] }, [readyQuote])).toBe(GENERIC);
  });

  it('names a routed line that no quote covers', () => {
    expect(ceoGateBlockerMessage({ summary: AWAITING, items: [routed, { id: 2, resolvedFactoryName: 'B' }] }, [readyQuote]))
      .toBe('รอยืนยันราคาโรงงาน B');
  });

  // The server exempts stock lines from the factory + quote requirement (and setItemFactory 409s
  // on them), so the UI must never tell Import to fix something it cannot.
  it('never blames a stock line with no factory', () => {
    const stock = { id: 2, stockSource: 'IN_THAILAND', productDescription: 'สต็อก' };
    expect(ceoGateBlockerMessage({ summary: AWAITING, items: [routed, stock] }, [readyQuote])).toBe(GENERIC);
  });

  it('never waits on a factory quote for a stock line that carries a factory name', () => {
    const stock = { id: 2, stockSource: 'IN_THAILAND', resolvedFactoryName: 'B' };
    expect(ceoGateBlockerMessage({ summary: AWAITING, items: [routed, stock] }, [readyQuote])).toBe(GENERIC);
  });

  it('asks for the arrival date on an IN_TRANSIT stock line that has none', () => {
    const transit = { id: 2, stockSource: 'IN_TRANSIT', productDescription: 'ของกำลังมา', expectedArrivalDate: null };
    expect(ceoGateBlockerMessage({ summary: AWAITING, items: [routed, transit] }, [readyQuote]))
      .toBe('รายการที่ 2 (ของกำลังมา) ยังไม่ได้ระบุวันที่คาดว่าจะถึง');
  });

  it('does not ask for an arrival date once it is set, or on an IN_THAILAND line', () => {
    const transit = { id: 2, stockSource: 'IN_TRANSIT', expectedArrivalDate: '2026-11-01' };
    const inThailand = { id: 3, stockSource: 'IN_THAILAND' };
    expect(ceoGateBlockerMessage({ summary: AWAITING, items: [routed, transit, inThailand] }, [readyQuote])).toBe(GENERIC);
  });

  it('says a factory declined (NOT_AVAILABLE) instead of "waiting for confirmation"', () => {
    const declined = { factoryName: 'B', status: 'NOT_AVAILABLE', current: true, revisionNo: 1, items: [{ pricingRequestItemId: 2 }] };
    expect(ceoGateBlockerMessage({ summary: AWAITING, items: [routed, { id: 2, resolvedFactoryName: 'B' }] }, [readyQuote, declined]))
      .toBe('โรงงาน B แจ้งไม่พร้อมเสนอราคา');
  });
});
