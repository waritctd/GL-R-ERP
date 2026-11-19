import { describe, expect, it } from 'vitest';
import { resolveWorkState, secondaryWorkActions } from './workState.js';

// There is no waiting-on-department fallback (see workState.js). `catalog` is the same canned
// payload mockApi serves, pinned against DealStage.java by stageCatalog.test.js.
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
  // Sticky primary on TicketDetailPage for an import user: รับงาน must open the SUBMITTED request
  // itself (the queue's default tab cannot list a SUBMITTED row), oldest first.
  it('sends import\'s pickup action to the oldest SUBMITTED pricing request\'s detail page', () => {
    const deal = baseDeal({ status: 'draft', fulfillmentStatus: null });
    const result = resolveWorkState({ role: 'import' }, deal, [
      { id: 12, status: 'SUBMITTED' },
      { id: 8, status: 'SUBMITTED' },
    ]);
    expect(result.action.to).toBe('/pricing-requests/8');
  });

  it('returns nothing when the deal is not ACTIVE (ON_HOLD/DORMANT/lost already have their own banner)', () => {
    const deal = baseDeal({ lifecycle: 'ON_HOLD' });
    expect(resolveWorkState({ role: 'sales' }, deal, [])).toEqual({ action: null });
  });

  it('sales viewer on a sales-gated stage falls through to nextSalesAction (CREATE_PCR — no live PR)', () => {
    const deal = baseDeal();
    const result = resolveWorkState({ role: 'sales' }, deal, []);
    expect(result).not.toHaveProperty('waitingRoleLabel');
    expect(result.action).toMatchObject({ key: 'create_pcr' });
  });

  it('import viewer on an import-gated stage falls through to nextImportAction', () => {
    const deal = { id: 1, lifecycle: 'ACTIVE', salesStage: 'PROCUREMENT', status: 'quotation_issued', fulfillmentStatus: 'IR_SENT' };
    const result = resolveWorkState({ role: 'import' }, deal, []);
    expect(result).not.toHaveProperty('waitingRoleLabel');
    expect(result.action).toMatchObject({ code: 'markShipping' });
  });

  it('account viewer on an account-gated stage falls through to nextAccountAction', () => {
    const deal = {
      id: 1, lifecycle: 'ACTIVE', salesStage: 'DEPOSIT_RECEIVED',
      status: 'quotation_issued', paymentStatus: 'DEPOSIT_NOTICE_ISSUED',
    };
    const result = resolveWorkState({ role: 'account' }, deal, []);
    expect(result).not.toHaveProperty('waitingRoleLabel');
    expect(result.action).toMatchObject({ key: 'confirmDeposit' });
  });

  // The stage only advances to DEPOSIT_RECEIVED once account confirms the deposit, so while
  // paymentStatus is DEPOSIT_NOTICE_ISSUED the deal sits at ORDER_RECEIVED (gate: 'sales') though
  // confirming the deposit is account's own pending action: resolveWorkState must surface
  // nextAccountAction's real action instead of gating on the stage first.
  it('account viewer at ORDER_RECEIVED (gate: sales) with paymentStatus DEPOSIT_NOTICE_ISSUED still gets confirmDeposit, not a waiting banner', () => {
    const deal = {
      id: 1, lifecycle: 'ACTIVE', salesStage: 'ORDER_RECEIVED',
      status: 'quotation_issued', paymentStatus: 'DEPOSIT_NOTICE_ISSUED',
    };
    const result = resolveWorkState({ role: 'account' }, deal, []);
    expect(result).not.toHaveProperty('waitingRoleLabel');
    expect(result.action).toMatchObject({ key: 'confirmDeposit' });
  });

  // An overdue outstanding balance (chaseOverdue) is account's own pending action regardless of
  // which stage the deal sits in.
  it('account viewer on a sales-gated stage with an overdue outstanding balance gets chaseOverdue, not a waiting banner', () => {
    const deal = {
      id: 1, lifecycle: 'ACTIVE', salesStage: 'ORDER_RECEIVED',
      overdue: true, amountOutstanding: 5000,
    };
    const result = resolveWorkState({ role: 'account' }, deal, []);
    expect(result).not.toHaveProperty('waitingRoleLabel');
    expect(result.action).toMatchObject({ key: 'chaseOverdue' });
  });

  it('ceo always passes the stage-gate check, but has no resolver of its own (the two-signature close button lives elsewhere)', () => {
    const importStage = baseDeal({ salesStage: 'PROCUREMENT' });
    const result = resolveWorkState({ role: 'ceo' }, importStage, []);
    expect(result.action).toBeNull();
    expect(result).not.toHaveProperty('waitingRoleLabel');
  });
});

// nextSalesAction's bucket 1 (salesActions.js) must not fire unconditionally on "no live pricing
// request": that is true forever for a legacy/demo deal created before the PricingRequest chain
// (e.g. demoData ticket 12), parking it on "create a pricing request" even with a quotation, a
// deposit and an import request in flight. Tested directly (not through resolveWorkState) since
// the logic lives entirely in its bucket-1 gate.
describe('nextSalesAction — CREATE_PCR gate on legacy/no-PR deals (UAT regression)', () => {
  it('suppresses create_pcr on a paymentStatus alone (a price must exist for anything to be payable), even with the status still draft', () => {
    const deal = baseDeal({ status: 'draft', paymentStatus: 'CUSTOMER_CONFIRMED' });
    expect(nextSalesAction(deal, [])?.key).not.toBe('create_pcr');
  });
});

// The AWAIT_DIRECT_APPROVAL bucket is a WAITING state for
// the rep (the header's bannerText, never a button), while for sales_manager/ceo — who approve a
// direct quotation (canApproveDealQuotation) — the same live PENDING_APPROVAL quotation resolves to
// their OWN action "อนุมัติใบเสนอราคา" -> /quotations/:id. SUBMIT / CONFIRM stay sales actions.
describe('resolveWorkState — live direct quotation (slice 2)', () => {
  const pending = { id: 77, number: 'QT-2026-0077-1', docStatus: 'PENDING_APPROVAL', recipientType: 'OWNER' };

  it.each([['sales_manager'], ['ceo']])('%s + PENDING_APPROVAL -> their own action "อนุมัติใบเสนอราคา" to the quotation', (role) => {
    const result = resolveWorkState({ role }, baseDeal({ liveDirectQuotation: pending }), []);
    expect(result.action).toMatchObject({ key: 'approve_direct_quotation', label: 'อนุมัติใบเสนอราคา', to: '/quotations/77' });
    expect(result).not.toHaveProperty('bannerText');
  });

  it('sales + DRAFT -> the SUBMIT action with its /quotations/:id route (the sticky bar navigates there)', () => {
    const result = resolveWorkState({ role: 'sales' }, baseDeal({ liveDirectQuotation: { ...pending, docStatus: 'DRAFT' } }), []);
    expect(result.action).toMatchObject({ key: 'submit_direct_quotation', to: '/quotations/77' });
    expect(result).not.toHaveProperty('bannerText');
  });
});

// แก้ใบเสนอราคา is an ADDITIONAL (secondary) action at the owner/buyer quote stages: the
// primary stays บันทึกผลใบเสนอราคา (client accepts the original / asks for changes), because the
// client may be happy with the original quotation. The caller resolves `reviseTarget`
// (canReviseDealQuotation); resolveWorkState/secondaryWorkActions are sales-only.
describe('resolveWorkState / secondaryWorkActions — reviseTarget (PR A)', () => {
  const target = { quotationId: 90, number: 'QT-2026-0090-1' };
  const deal = (overrides = {}) => baseDeal({ salesStage: 'QUOTE_OWNER', ...overrides });
  const issuedPr = [{ id: 5, ticketId: 1, status: 'QUOTATION_ISSUED', orderConfirmedAt: null }];

  it('negative: no reviseTarget -> nothing', () => {
    expect(secondaryWorkActions({ role: 'sales' }, deal(), issuedPr)).toEqual([]);
    expect(secondaryWorkActions({ role: 'sales' }, deal(), issuedPr, { reviseTarget: null })).toEqual([]);
  });

  it.each(['ceo', 'sales_manager', 'account', 'import', undefined])('negative: role %s never gets it, even if handed a target', (role) => {
    expect(secondaryWorkActions({ role }, deal(), issuedPr, { reviseTarget: target })).toEqual([]);
  });
});

// The route choice follows a SENT designer/
// owner quotation, and the line beside it names that quotation: "ส่งแล้ว" while it stands, "ไม่ผ่าน"
// once the party said no (no em dash in either). A PRICING_REQUEST-origin quotation awaiting
// approval is the same waiting line / approver action a direct quotation gets.
// `quotationByRequest` is what the deal page hands in: a Map of pricingRequestId -> the newest
// PRICING_REQUEST-origin quotation it read for that request.
describe('resolveWorkState — the route-choice line and the approval wait (owner ruling 2026-10-27)', () => {
  const sales = { role: 'sales' };
  const ROUTE_KNOWN = new Set();
  const request = (id, recipientType, status) => ({ id, ticketId: 1, status, recipientType, orderConfirmedAt: null });
  const quotation = (id, docStatus, number = 'QT-2026-0101-1') => ({ id, number, docStatus });

  // Whenever a request's newest PRICING_REQUEST-origin quotation awaits
  // approval — its first quotation (request APPROVED_FOR_QUOTATION) or a revision of an issued one
  // (request QUOTATION_ISSUED).
  it.each(['APPROVED_FOR_QUOTATION', 'QUOTATION_ISSUED'])(
    'a quotation awaiting approval (request %s): no button for the rep, the line "รอ ผจก.ขาย/CEO อนุมัติใบเสนอราคา <number>"',
    (status) => {
      const result = resolveWorkState(sales, baseDeal(), [request(5, 'OWNER', status)], {
        offRouteParties: ROUTE_KNOWN, quotationByRequest: new Map([[5, quotation(9105, 'PENDING_APPROVAL')]]),
      });
      expect(result).toEqual({ action: null, bannerText: 'รอ ผจก.ขาย/CEO อนุมัติใบเสนอราคา QT-2026-0101-1' });
    },
  );
});
