import { describe, expect, it } from 'vitest';
import { DEAL_STAGE_CATALOG } from '../../data/dealStageCatalog.js';
import { resolveWorkState } from './workState.js';

// The stage's owning department (`waitingRoleLabel`) is read off the backend catalog now — see
// stageCatalog.js. This is the same canned payload mockApi serves, pinned against DealStage.java
// by stageCatalog.test.js.
const catalog = DEAL_STAGE_CATALOG;
import { nextSalesAction } from './salesActions.js';

function baseDeal(overrides = {}) {
  return {
    id: 1,
    lifecycle: 'ACTIVE',
    salesStage: 'QUOTE_DESIGN_SIDE', // gate: 'sales', not auto
    nextFollowUpAt: null,
    stale: false,
    createdById: 1,
    ...overrides,
  };
}

describe('resolveWorkState', () => {
  it('returns nothing when the deal is not ACTIVE (ON_HOLD/DORMANT/lost already have their own banner)', () => {
    const deal = baseDeal({ lifecycle: 'ON_HOLD' });
    expect(resolveWorkState({ role: 'sales' }, deal, [], catalog)).toEqual({ action: null, waitingRoleLabel: null });
  });

  it('returns nothing when there is no deal at all', () => {
    expect(resolveWorkState({ role: 'sales' }, null, [], catalog)).toEqual({ action: null, waitingRoleLabel: null });
  });

  it('sales viewer on a sales-gated stage falls through to nextSalesAction (CREATE_PCR — no live PR)', () => {
    const deal = baseDeal();
    const result = resolveWorkState({ role: 'sales' }, deal, [], catalog);
    expect(result.waitingRoleLabel).toBeNull();
    expect(result.action).toMatchObject({ key: 'create_pcr' });
  });

  // Stages 13-14 (ส่งมอบสินค้า) handoff to sales, owner ruling 2026-08-17: proves the new
  // RECORD_DELIVERY bucket reaches the sticky bar through the SAME resolveWorkState -> nextSalesAction
  // path this whole describe block exercises, not just in salesActions.test.js's own isolated unit
  // tests. Zero pricing requests + a non-null paymentStatus is the "priced outside the PCR chain"
  // shape (bucket 1's own guard) — the same shape demoData.js tickets 13/14 already have.
  it('sales viewer on a delivery-ready deal falls through to nextSalesAction (RECORD_DELIVERY)', () => {
    const deal = baseDeal({
      status: 'quotation_issued', fulfillmentStatus: 'GOODS_RECEIVED', paymentStatus: 'AWAITING_FINAL_PAYMENT',
    });
    const result = resolveWorkState({ role: 'sales' }, deal, [], catalog);
    expect(result.waitingRoleLabel).toBeNull();
    expect(result.action).toMatchObject({ key: 'record_delivery' });
  });

  it('sales viewer on an import-gated stage with genuinely nothing pending (a live, in-review PR; no stale/follow-up) falls through to the stage-gate banner — รอฝ่ายนำเข้า', () => {
    // FIX 1 rewrite: resolveWorkState now calls nextSalesAction FIRST,
    // unconditionally — it no longer skips the resolver just because the
    // CURRENT stage (PROCUREMENT: gate 'import', auto: true) isn't sales'.
    // The realistic reason sales sees the waiting banner here isn't "the
    // resolver was never asked" any more — it's that nextSalesAction
    // genuinely has nothing in its 5-bucket cascade: a live PR already past
    // DRAFT (bucket 1 skipped), not APPROVED_FOR_QUOTATION/QUOTATION_ACCEPTED
    // (buckets 2/3 skipped), no follow-up due (bucket 4 skipped), and
    // deal.stale is false (bucket 5 skipped). Only then does the fallback
    // read the current stage's gate.
    const deal = baseDeal({
      salesStage: 'PROCUREMENT',
      stale: false,
    });
    const pricingRequests = [{ id: 1, ticketId: deal.id, status: 'IMPORT_REVIEWING' }];
    const result = resolveWorkState({ role: 'sales' }, deal, pricingRequests, catalog);
    expect(result.action).toBeNull();
    expect(result.waitingRoleLabel).toBe('ฝ่ายนำเข้า');
  });

  it('import viewer on an import-gated stage falls through to nextImportAction', () => {
    const deal = { id: 1, lifecycle: 'ACTIVE', salesStage: 'PROCUREMENT', status: 'quotation_issued', fulfillmentStatus: null };
    const result = resolveWorkState({ role: 'import' }, deal, [], catalog);
    expect(result.waitingRoleLabel).toBeNull();
    expect(result.action).toMatchObject({ code: 'issueImportRequest' });
  });

  // FIX 1 rewrite (backend-verified scenario 2, TicketService.java:702): the
  // stage only advances to PROCUREMENT once import issues the IR, so while
  // fulfillmentStatus is still null, the deal sits at DEPOSIT_RECEIVED
  // (gate: 'account') even though issuing the IR is squarely import's own
  // pending action. Before FIX 1 this read as "รอฝ่ายบัญชี" with no primary —
  // resolveWorkState must now surface nextImportAction's real action instead
  // of gating on the stage first.
  it('import viewer at DEPOSIT_RECEIVED (gate: account) with fulfillmentStatus null still gets issueImportRequest, not a waiting banner', () => {
    const deal = {
      id: 1, lifecycle: 'ACTIVE', salesStage: 'DEPOSIT_RECEIVED',
      status: 'quotation_issued', fulfillmentStatus: null,
    };
    const result = resolveWorkState({ role: 'import' }, deal, [], catalog);
    expect(result.waitingRoleLabel).toBeNull();
    expect(result.action).toMatchObject({ code: 'issueImportRequest' });
  });

  it('import viewer on a sales-gated stage reads as รอฝ่ายขาย (nextImportAction runs first, correctly finds nothing — fulfilment hasn\'t started)', () => {
    const deal = baseDeal(); // QUOTE_DESIGN_SIDE, gate: sales
    const result = resolveWorkState({ role: 'import' }, deal, [], catalog);
    expect(result.action).toBeNull();
    expect(result.waitingRoleLabel).toBe('ฝ่ายขาย');
  });

  it('account viewer on an account-gated stage falls through to nextAccountAction', () => {
    const deal = {
      id: 1, lifecycle: 'ACTIVE', salesStage: 'DEPOSIT_RECEIVED',
      status: 'quotation_issued', paymentStatus: 'DEPOSIT_NOTICE_ISSUED',
    };
    const result = resolveWorkState({ role: 'account' }, deal, [], catalog);
    expect(result.waitingRoleLabel).toBeNull();
    expect(result.action).toMatchObject({ key: 'confirmDeposit' });
  });

  // FIX 1 rewrite (backend-verified scenario 1, TicketService.java:1029): the
  // stage only advances to DEPOSIT_RECEIVED once account confirms the
  // deposit, so while paymentStatus is still DEPOSIT_NOTICE_ISSUED, the deal
  // sits at ORDER_RECEIVED (gate: 'sales') even though confirming the
  // deposit is squarely account's own pending action. Before FIX 1 this read
  // as "รอฝ่ายขาย" with no primary — resolveWorkState must now surface
  // nextAccountAction's real action instead of gating on the stage first.
  it('account viewer at ORDER_RECEIVED (gate: sales) with paymentStatus DEPOSIT_NOTICE_ISSUED still gets confirmDeposit, not a waiting banner', () => {
    const deal = {
      id: 1, lifecycle: 'ACTIVE', salesStage: 'ORDER_RECEIVED',
      status: 'quotation_issued', paymentStatus: 'DEPOSIT_NOTICE_ISSUED',
    };
    const result = resolveWorkState({ role: 'account' }, deal, [], catalog);
    expect(result.waitingRoleLabel).toBeNull();
    expect(result.action).toMatchObject({ key: 'confirmDeposit' });
  });

  // Bug report's third example: account's chaseOverdue was "likewise lost on
  // any sales-gated stage" pre-fix — an overdue outstanding balance is
  // account's own pending action regardless of which stage the deal
  // currently sits in.
  it('account viewer on a sales-gated stage with an overdue outstanding balance gets chaseOverdue, not a waiting banner', () => {
    const deal = {
      id: 1, lifecycle: 'ACTIVE', salesStage: 'ORDER_RECEIVED',
      overdue: true, amountOutstanding: 5000,
    };
    const result = resolveWorkState({ role: 'account' }, deal, [], catalog);
    expect(result.waitingRoleLabel).toBeNull();
    expect(result.action).toMatchObject({ key: 'chaseOverdue' });
  });

  it('account viewer on a sales-gated stage with nothing pending falls through to the stage-gate banner — รอฝ่ายขาย (nextAccountAction runs first, correctly finds nothing)', () => {
    const deal = baseDeal();
    const result = resolveWorkState({ role: 'account' }, deal, [], catalog);
    expect(result.action).toBeNull();
    expect(result.waitingRoleLabel).toBe('ฝ่ายขาย');
  });

  it('ceo always passes the stage-gate check, but has no resolver of its own (the two-signature close button lives elsewhere)', () => {
    const importStage = baseDeal({ salesStage: 'PROCUREMENT' });
    const result = resolveWorkState({ role: 'ceo' }, importStage, [], catalog);
    expect(result.action).toBeNull();
    expect(result.waitingRoleLabel).toBeNull();
  });

  it('sales_manager passes a sales-gated stage but has no resolver of its own either', () => {
    const deal = baseDeal();
    const result = resolveWorkState({ role: 'sales_manager' }, deal, [], catalog);
    expect(result.action).toBeNull();
    expect(result.waitingRoleLabel).toBeNull();
  });

  it('an unrecognised role (e.g. hr, which should never reach this page) is treated as never its turn', () => {
    const deal = baseDeal();
    const result = resolveWorkState({ role: 'hr' }, deal, [], catalog);
    expect(result.action).toBeNull();
    expect(result.waitingRoleLabel).toBe('ฝ่ายขาย');
  });
});

// UAT bug: nextSalesAction's bucket 1 (salesActions.js) used to fire
// unconditionally on "no live pricing request" — true forever for any
// legacy/demo deal created before the PricingRequest chain existed (e.g.
// demoData ticket 12, PR-2026-0012), permanently parking it on "create a
// pricing request" even once it had a quotation, a deposit, and an import
// request already in flight. These test nextSalesAction directly (rather
// than through resolveWorkState) since the bug and the fix both live
// entirely in its bucket-1 gate.
describe('nextSalesAction — CREATE_PCR gate on legacy/no-PR deals (UAT regression)', () => {
  it('does NOT offer create_pcr for the reported deal — legacy quotation issued, deposit paid, IR issued, zero pricing requests', () => {
    const deal = baseDeal({
      status: 'quotation_issued',
      salesStage: 'PROCUREMENT',
      paymentStatus: 'DEPOSIT_PAID',
      fulfillmentStatus: 'IR_ISSUED',
    });
    const action = nextSalesAction(deal, []);
    expect(action?.key).not.toBe('create_pcr');
    // Nothing else in the cascade applies either (no follow-up due, not
    // stale) — the fixed bucket 1 must fall through to "nothing pending",
    // not invent a different bogus action.
    expect(action).toBeNull();
  });

  it('suppresses create_pcr on a paymentStatus alone (a price must exist for anything to be payable), even with the status still draft', () => {
    const deal = baseDeal({ status: 'draft', paymentStatus: 'CUSTOMER_CONFIRMED' });
    expect(nextSalesAction(deal, [])?.key).not.toBe('create_pcr');
  });

  it('suppresses create_pcr on a quoted status alone — legacy quotation out, customer has not confirmed, so paymentStatus is still null', () => {
    const deal = baseDeal({ status: 'quotation_issued', paymentStatus: null });
    expect(nextSalesAction(deal, [])?.key).not.toBe('create_pcr');
  });

  // The guard is (no PR rows AT ALL) AND (a price went out) — not just the
  // latter. OrderConfirmationService.confirmOrder sets 'quotation_issued' and
  // confirmCustomer sets paymentStatus under the CURRENT flow too, so a deal
  // that IS in the chain can carry both signals.
  it('still offers create_pcr after a customer-change revision leaves {parent SUPERSEDED, child DRAFT} on an order-confirmed deal — no live PR, but the deal is in the chain', () => {
    const deal = baseDeal({
      status: 'quotation_issued',
      salesStage: 'ORDER_RECEIVED',
      paymentStatus: 'CUSTOMER_CONFIRMED',
    });
    const prs = [
      { id: 11, ticketId: deal.id, status: 'SUPERSEDED' },
      { id: 12, ticketId: deal.id, status: 'DRAFT' },
    ];
    expect(nextSalesAction(deal, prs)).toMatchObject({ key: 'create_pcr' });
  });

  it('still offers create_pcr for an early-stage deal with no payment status and zero pricing requests (the real case the gate must not break)', () => {
    const deal = baseDeal(); // QUOTE_DESIGN_SIDE, status/paymentStatus absent
    const action = nextSalesAction(deal, []);
    expect(action).toMatchObject({ key: 'create_pcr' });
  });

  // The three cases below are why the guard reads `status`/`paymentStatus`
  // rather than salesStage/fulfillmentStatus: each is a deal that has
  // genuinely never been priced, and suppressing the CTA on any of them would
  // strand the rep with no button and a "รอฝ่ายขาย" banner naming themselves.
  it('still offers create_pcr after TicketService.reserveStock sets FROM_STOCK and auto-advances the stage — that path has no pricing precondition', () => {
    const deal = baseDeal({ salesStage: 'DELIVERY_SCHEDULING', fulfillmentStatus: 'FROM_STOCK' });
    expect(nextSalesAction(deal, [])).toMatchObject({ key: 'create_pcr' });
  });

  it('still offers create_pcr when a rep manually set an auto stage (allowedTargetStages does not filter `auto`, so ORDER_RECEIVED is reachable by hand)', () => {
    const deal = baseDeal({ salesStage: 'ORDER_RECEIVED' });
    expect(nextSalesAction(deal, [])).toMatchObject({ key: 'create_pcr' });
  });

  it('still offers create_pcr for a legacy deal stranded mid-flight in the RETIRED engine (in_review) — that price can never arrive now', () => {
    const deal = baseDeal({ status: 'in_review' });
    expect(nextSalesAction(deal, [])).toMatchObject({ key: 'create_pcr' });
  });
});
