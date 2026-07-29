import { describe, expect, it } from 'vitest';
import { resolveWorkState } from './workState.js';

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
    expect(resolveWorkState({ role: 'sales' }, deal, [])).toEqual({ action: null, waitingRoleLabel: null });
  });

  it('returns nothing when there is no deal at all', () => {
    expect(resolveWorkState({ role: 'sales' }, null, [])).toEqual({ action: null, waitingRoleLabel: null });
  });

  it('sales viewer on a sales-gated stage falls through to nextSalesAction (CREATE_PCR — no live PR)', () => {
    const deal = baseDeal();
    const result = resolveWorkState({ role: 'sales' }, deal, []);
    expect(result.waitingRoleLabel).toBeNull();
    expect(result.action).toMatchObject({ key: 'create_pcr' });
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
    const result = resolveWorkState({ role: 'sales' }, deal, pricingRequests);
    expect(result.action).toBeNull();
    expect(result.waitingRoleLabel).toBe('ฝ่ายนำเข้า');
  });

  it('import viewer on an import-gated stage falls through to nextImportAction', () => {
    const deal = { id: 1, lifecycle: 'ACTIVE', salesStage: 'PROCUREMENT', status: 'quotation_issued', fulfillmentStatus: null };
    const result = resolveWorkState({ role: 'import' }, deal, []);
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
    const result = resolveWorkState({ role: 'import' }, deal, []);
    expect(result.waitingRoleLabel).toBeNull();
    expect(result.action).toMatchObject({ code: 'issueImportRequest' });
  });

  it('import viewer on a sales-gated stage reads as รอฝ่ายขาย (nextImportAction runs first, correctly finds nothing — fulfilment hasn\'t started)', () => {
    const deal = baseDeal(); // QUOTE_DESIGN_SIDE, gate: sales
    const result = resolveWorkState({ role: 'import' }, deal, []);
    expect(result.action).toBeNull();
    expect(result.waitingRoleLabel).toBe('ฝ่ายขาย');
  });

  it('account viewer on an account-gated stage falls through to nextAccountAction', () => {
    const deal = {
      id: 1, lifecycle: 'ACTIVE', salesStage: 'DEPOSIT_RECEIVED',
      status: 'quotation_issued', paymentStatus: 'DEPOSIT_NOTICE_ISSUED',
    };
    const result = resolveWorkState({ role: 'account' }, deal, []);
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
    const result = resolveWorkState({ role: 'account' }, deal, []);
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
    const result = resolveWorkState({ role: 'account' }, deal, []);
    expect(result.waitingRoleLabel).toBeNull();
    expect(result.action).toMatchObject({ key: 'chaseOverdue' });
  });

  it('account viewer on a sales-gated stage with nothing pending falls through to the stage-gate banner — รอฝ่ายขาย (nextAccountAction runs first, correctly finds nothing)', () => {
    const deal = baseDeal();
    const result = resolveWorkState({ role: 'account' }, deal, []);
    expect(result.action).toBeNull();
    expect(result.waitingRoleLabel).toBe('ฝ่ายขาย');
  });

  it('ceo always passes the stage-gate check, but has no resolver of its own (the two-signature close button lives elsewhere)', () => {
    const importStage = baseDeal({ salesStage: 'PROCUREMENT' });
    const result = resolveWorkState({ role: 'ceo' }, importStage, []);
    expect(result.action).toBeNull();
    expect(result.waitingRoleLabel).toBeNull();
  });

  it('sales_manager passes a sales-gated stage but has no resolver of its own either', () => {
    const deal = baseDeal();
    const result = resolveWorkState({ role: 'sales_manager' }, deal, []);
    expect(result.action).toBeNull();
    expect(result.waitingRoleLabel).toBeNull();
  });

  it('an unrecognised role (e.g. hr, which should never reach this page) is treated as never its turn', () => {
    const deal = baseDeal();
    const result = resolveWorkState({ role: 'hr' }, deal, []);
    expect(result.action).toBeNull();
    expect(result.waitingRoleLabel).toBe('ฝ่ายขาย');
  });
});
