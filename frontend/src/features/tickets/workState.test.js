import { describe, expect, it } from 'vitest';
import { DEAL_STAGE_CATALOG } from '../../data/dealStageCatalog.js';
import { resolveWorkState, secondaryWorkActions } from './workState.js';

// There is no waiting-on-department fallback (see workState.js). `catalog` is the same canned
// payload mockApi serves, pinned against DealStage.java by stageCatalog.test.js.
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

  it('returns nothing when there is no deal at all', () => {
    expect(resolveWorkState({ role: 'sales' }, null, [])).toEqual({ action: null });
  });

  it('sales viewer on a sales-gated stage falls through to nextSalesAction (CREATE_PCR — no live PR)', () => {
    const deal = baseDeal();
    const result = resolveWorkState({ role: 'sales' }, deal, []);
    expect(result).not.toHaveProperty('waitingRoleLabel');
    expect(result.action).toMatchObject({ key: 'create_pcr' });
  });

  // Stages 13-14 (ส่งมอบสินค้า) handoff to sales: proves the RECORD_DELIVERY bucket reaches the
  // sticky bar through the same resolveWorkState -> nextSalesAction path this describe block
  // exercises. Zero pricing requests + a non-null paymentStatus is the "priced outside the PCR
  // chain" shape (bucket 1's own guard), like demoData.js tickets 13/14.
  it('sales viewer on a delivery-ready deal falls through to nextSalesAction (RECORD_DELIVERY)', () => {
    const deal = baseDeal({
      status: 'quotation_issued', fulfillmentStatus: 'GOODS_RECEIVED', paymentStatus: 'AWAITING_FINAL_PAYMENT',
    });
    const result = resolveWorkState({ role: 'sales' }, deal, []);
    expect(result).not.toHaveProperty('waitingRoleLabel');
    expect(result.action).toMatchObject({ key: 'record_delivery' });
  });

  it('sales viewer on an import-gated stage with genuinely nothing pending (a live, in-review PR; no stale/follow-up) has nothing to say (no waiting banner any more)', () => {
    // resolveWorkState calls nextSalesAction FIRST, unconditionally, so sales sees the waiting
    // banner here only because nextSalesAction has nothing in its 5-bucket cascade: a live PR
    // already past DRAFT (bucket 1 skipped), not APPROVED_FOR_QUOTATION/QUOTATION_ACCEPTED (2/3
    // skipped), no follow-up due (4 skipped), deal.stale false (5 skipped). Only then does the
    // fallback read the current stage's gate.
    const deal = baseDeal({
      salesStage: 'PROCUREMENT',
      stale: false,
    });
    const pricingRequests = [{ id: 1, ticketId: deal.id, status: 'IMPORT_REVIEWING' }];
    const result = resolveWorkState({ role: 'sales' }, deal, pricingRequests, catalog);
    expect(result.action).toBeNull();
    expect(result).not.toHaveProperty('waitingRoleLabel');
  });

  it('import viewer on an import-gated stage falls through to nextImportAction', () => {
    const deal = { id: 1, lifecycle: 'ACTIVE', salesStage: 'PROCUREMENT', status: 'quotation_issued', fulfillmentStatus: 'IR_SENT' };
    const result = resolveWorkState({ role: 'import' }, deal, []);
    expect(result).not.toHaveProperty('waitingRoleLabel');
    expect(result.action).toMatchObject({ code: 'markShipping' });
  });

  // A confirmed deal with no IR yet is not an import to-do (sales raises the per-factory IR), so
  // import gets no primary action, and still no waiting banner.
  it('import viewer at DEPOSIT_RECEIVED with fulfillmentStatus null has no action and no waiting banner', () => {
    const deal = {
      id: 1, lifecycle: 'ACTIVE', salesStage: 'DEPOSIT_RECEIVED',
      status: 'quotation_issued', fulfillmentStatus: null,
    };
    const result = resolveWorkState({ role: 'import' }, deal, []);
    expect(result).not.toHaveProperty('waitingRoleLabel');
    expect(result.action).toBeNull();
  });

  it('import viewer on a sales-gated stage has no action and no waiting banner (nextImportAction runs first, correctly finds nothing — fulfilment hasn\'t started)', () => {
    const deal = baseDeal(); // QUOTE_DESIGN_SIDE, gate: sales
    const result = resolveWorkState({ role: 'import' }, deal, []);
    expect(result.action).toBeNull();
    expect(result).not.toHaveProperty('waitingRoleLabel');
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

  it('account viewer on a sales-gated stage with nothing pending has no action and no waiting banner (nextAccountAction runs first, correctly finds nothing)', () => {
    const deal = baseDeal();
    const result = resolveWorkState({ role: 'account' }, deal, []);
    expect(result.action).toBeNull();
    expect(result).not.toHaveProperty('waitingRoleLabel');
  });

  it('ceo always passes the stage-gate check, but has no resolver of its own (the two-signature close button lives elsewhere)', () => {
    const importStage = baseDeal({ salesStage: 'PROCUREMENT' });
    const result = resolveWorkState({ role: 'ceo' }, importStage, []);
    expect(result.action).toBeNull();
    expect(result).not.toHaveProperty('waitingRoleLabel');
  });

  it('sales_manager passes a sales-gated stage but has no resolver of its own either', () => {
    const deal = baseDeal();
    const result = resolveWorkState({ role: 'sales_manager' }, deal, []);
    expect(result.action).toBeNull();
    expect(result).not.toHaveProperty('waitingRoleLabel');
  });

  it('an unrecognised role (e.g. hr, which should never reach this page) is treated as never its turn', () => {
    const deal = baseDeal();
    const result = resolveWorkState({ role: 'hr' }, deal, []);
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
  // That draft is sent, not a second request created beside it.
  it('offers to send the draft after a customer-change revision leaves {parent SUPERSEDED, child DRAFT} on an order-confirmed deal — no live PR, but the deal is in the chain', () => {
    const deal = baseDeal({
      status: 'quotation_issued',
      salesStage: 'ORDER_RECEIVED',
      paymentStatus: 'CUSTOMER_CONFIRMED',
    });
    const prs = [
      { id: 11, ticketId: deal.id, status: 'SUPERSEDED' },
      { id: 12, ticketId: deal.id, status: 'DRAFT', recipientType: 'OWNER' },
    ];
    expect(nextSalesAction(deal, prs)).toEqual({
      key: 'submit_pricing_request', label: 'ส่งคำขอราคาถึงเจ้าของโครงการ', pricingRequestId: 12,
    });
  });

  // An in-chain deal whose requests are all dead still needs a new request.
  it('still offers create_pcr on an order-confirmed deal whose requests are all dead ({SUPERSEDED, CANCELLED})', () => {
    const deal = baseDeal({ status: 'quotation_issued', salesStage: 'ORDER_RECEIVED', paymentStatus: 'CUSTOMER_CONFIRMED' });
    const prs = [
      { id: 11, ticketId: deal.id, status: 'SUPERSEDED', recipientType: 'OWNER' },
      { id: 12, ticketId: deal.id, status: 'CANCELLED', recipientType: 'OWNER' },
    ];
    expect(nextSalesAction(deal, prs)).toMatchObject({ key: 'create_pcr' });
  });

  // With a revision draft and a newer blank one beside it (made by the old สร้างคำขอราคา), the
  // revision (the customer's change) is the one sent.
  it('sends the customer-change revision draft, not a newer blank draft beside it', () => {
    const deal = baseDeal({ salesStage: 'SPEC_APPROVED' });
    const prs = [
      { id: 11, ticketId: deal.id, status: 'SUPERSEDED', recipientType: 'OWNER' },
      { id: 12, ticketId: deal.id, status: 'DRAFT', recipientType: 'OWNER', parentPricingRequestId: 11 },
      { id: 13, ticketId: deal.id, status: 'DRAFT', recipientType: 'OWNER', parentPricingRequestId: null },
    ];
    expect(nextSalesAction(deal, prs)).toMatchObject({ key: 'submit_pricing_request', pricingRequestId: 12 });
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

// The AWAIT_DIRECT_APPROVAL bucket is a WAITING state for
// the rep (the header's bannerText, never a button), while for sales_manager/ceo — who approve a
// direct quotation (canApproveDealQuotation) — the same live PENDING_APPROVAL quotation resolves to
// their OWN action "อนุมัติใบเสนอราคา" -> /quotations/:id. SUBMIT / CONFIRM stay sales actions.
describe('resolveWorkState — live direct quotation (slice 2)', () => {
  const pending = { id: 77, number: 'QT-2026-0077-1', docStatus: 'PENDING_APPROVAL', recipientType: 'OWNER' };

  // Shapes: `{ action }` only, no waitingRoleLabel; an optional `bannerText` for the rep's
  // waiting state and nothing else.
  it('sales + PENDING_APPROVAL -> no action, a banner naming who it waits on and the number', () => {
    const result = resolveWorkState({ role: 'sales' }, baseDeal({ liveDirectQuotation: pending }), []);
    expect(result).toEqual({ action: null, bannerText: 'รอ ผจก.ขาย/CEO อนุมัติใบเสนอราคา QT-2026-0077-1' });
    expect(result).not.toHaveProperty('waitingRoleLabel');
  });

  it.each([['sales_manager'], ['ceo']])('%s + PENDING_APPROVAL -> their own action "อนุมัติใบเสนอราคา" to the quotation', (role) => {
    const result = resolveWorkState({ role }, baseDeal({ liveDirectQuotation: pending }), []);
    expect(result.action).toMatchObject({ key: 'approve_direct_quotation', label: 'อนุมัติใบเสนอราคา', to: '/quotations/77' });
    expect(result).not.toHaveProperty('bannerText');
  });

  it.each([['sales_manager'], ['ceo']])('%s gets nothing new for a DRAFT/APPROVED direct quotation (not theirs to act on)', (role) => {
    for (const docStatus of ['DRAFT', 'APPROVED']) {
      const result = resolveWorkState({ role }, baseDeal({ liveDirectQuotation: { ...pending, docStatus } }), []);
      expect(result).toEqual({ action: null });
    }
  });

  it('sales + DRAFT -> the SUBMIT action with its /quotations/:id route (the sticky bar navigates there)', () => {
    const result = resolveWorkState({ role: 'sales' }, baseDeal({ liveDirectQuotation: { ...pending, docStatus: 'DRAFT' } }), []);
    expect(result.action).toMatchObject({ key: 'submit_direct_quotation', to: '/quotations/77' });
    expect(result).not.toHaveProperty('bannerText');
  });

  it('sales + APPROVED -> the CONFIRM_ORDER_DIRECT action', () => {
    const result = resolveWorkState({ role: 'sales' }, baseDeal({ liveDirectQuotation: { ...pending, docStatus: 'APPROVED' } }), []);
    expect(result.action).toMatchObject({ key: 'confirm_order_direct', label: 'ยืนยันคำสั่งซื้อ', quotationId: 77 });
  });

  // Direct route choice (flow A): the เสนอราคา menu is primary, ยืนยันคำสั่งซื้อ drops to the ⋯ menu.
  it('sales + APPROVED designer direct quotation (route known) -> primary quote_next_party, ⋯ keeps confirm_order_direct', () => {
    const deal = baseDeal({ status: 'draft', liveDirectQuotation: { ...pending, docStatus: 'APPROVED', recipientType: 'DESIGNER' } });
    const opts = { offRouteParties: new Set() };
    expect(resolveWorkState({ role: 'sales' }, deal, [], opts).action).toMatchObject({ key: 'quote_next_party', direct: true });
    expect(secondaryWorkActions({ role: 'sales' }, deal, [], opts))
      .toEqual([expect.objectContaining({ key: 'confirm_order_direct', label: 'ยืนยันคำสั่งซื้อ', quotationId: 77 })]);
  });

  it('no confirm_order_direct secondary when it is already the primary (BUYER, or route unknown) or the role is not sales', () => {
    const approved = (recipientType) => baseDeal({ status: 'draft', liveDirectQuotation: { ...pending, docStatus: 'APPROVED', recipientType } });
    expect(secondaryWorkActions({ role: 'sales' }, approved('BUYER'), [], { offRouteParties: new Set() })).toEqual([]);
    expect(secondaryWorkActions({ role: 'sales' }, approved('DESIGNER'), [], { offRouteParties: null })).toEqual([]);
    expect(secondaryWorkActions({ role: 'ceo' }, approved('DESIGNER'), [], { offRouteParties: new Set() })).toEqual([]);
  });

  // The summary serves only the NEWEST live direct quotation. When that is a later party's DRAFT/PENDING
  // beside an APPROVED designer quotation, the caller hands the APPROVED row in (`approvedDirect`, read
  // off the deal's quotation list) and ⋯ still offers ยืนยันคำสั่งซื้อ for THAT row.
  describe('secondaryWorkActions — approvedDirect (APPROVED designer row beside a newer owner quotation)', () => {
    const approvedDesigner = { id: 77, number: 'QT-2026-0077-1' };
    const ownerLive = (docStatus) => ({ id: 78, number: 'QT-2026-0078-1', docStatus, recipientType: 'OWNER' });
    const sales = { role: 'sales' };
    const dealWith = (live, overrides = {}) => baseDeal({ status: 'draft', liveDirectQuotation: live, ...overrides });
    const confirmFor77 = expect.objectContaining({ key: 'confirm_order_direct', label: 'ยืนยันคำสั่งซื้อ', quotationId: 77, quotationNumber: 'QT-2026-0077-1' });

    it('owner DRAFT is the primary (submit) -> ⋯ confirms the APPROVED designer row, by ITS id and number', () => {
      const deal = dealWith(ownerLive('DRAFT'));
      expect(resolveWorkState(sales, deal, [], { offRouteParties: new Set() }).action).toMatchObject({ key: 'submit_direct_quotation', quotationId: 78 });
      expect(secondaryWorkActions(sales, deal, [], { offRouteParties: new Set(), approvedDirect: approvedDesigner })).toEqual([confirmFor77]);
    });

    it('owner PENDING_APPROVAL is a waiting banner (no primary) -> ⋯ still confirms the APPROVED designer row', () => {
      const deal = dealWith(ownerLive('PENDING_APPROVAL'));
      expect(resolveWorkState(sales, deal, [], { offRouteParties: new Set() }).action).toBeNull();
      expect(secondaryWorkActions(sales, deal, [], { offRouteParties: new Set(), approvedDirect: approvedDesigner })).toEqual([confirmFor77]);
    });

    it('route still unknown (offRouteParties null) does not hide it', () => {
      expect(secondaryWorkActions(sales, dealWith(ownerLive('DRAFT')), [], { approvedDirect: approvedDesigner })).toEqual([confirmFor77]);
    });

    it('no duplicate when the primary already IS confirm_order_direct (APPROVED buyer row) or the direct route choice (APPROVED designer row)', () => {
      const buyer = { id: 77, number: 'QT-2026-0077-1', docStatus: 'APPROVED', recipientType: 'BUYER' };
      expect(resolveWorkState(sales, dealWith(buyer), [], { offRouteParties: new Set() }).action).toMatchObject({ key: 'confirm_order_direct' });
      expect(secondaryWorkActions(sales, dealWith(buyer), [], { offRouteParties: new Set(), approvedDirect: approvedDesigner })).toEqual([]);
      const designer = { ...buyer, recipientType: 'DESIGNER' };
      const keys = secondaryWorkActions(sales, dealWith(designer), [], { offRouteParties: new Set(), approvedDirect: approvedDesigner })
        .filter((a) => a.key === 'confirm_order_direct');
      expect(keys).toEqual([confirmFor77]);
    });

    it('absent past draft (the confirm 409s once an order is confirmed), for non-sales roles, and when nothing is passed', () => {
      const opts = { offRouteParties: new Set(), approvedDirect: approvedDesigner };
      expect(secondaryWorkActions(sales, dealWith(ownerLive('DRAFT'), { status: 'quotation_issued' }), [], opts)).toEqual([]);
      for (const role of ['ceo', 'sales_manager', 'import', 'account']) {
        expect(secondaryWorkActions({ role }, dealWith(ownerLive('DRAFT')), [], opts)).toEqual([]);
      }
      expect(secondaryWorkActions(sales, dealWith(ownerLive('DRAFT')), [], { offRouteParties: new Set() })).toEqual([]);
      expect(secondaryWorkActions(sales, dealWith(ownerLive('DRAFT'), { lifecycle: 'ON_HOLD' }), [], opts)).toEqual([]);
    });
  });

  it('an ON_HOLD deal still returns nothing — the lifecycle banner owns that state', () => {
    const result = resolveWorkState({ role: 'ceo' }, baseDeal({ lifecycle: 'ON_HOLD', liveDirectQuotation: pending }), []);
    expect(result).toEqual({ action: null });
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

  it.each(['QUOTE_OWNER', 'QUOTE_BUYER'])('%s: primary stays record_quotation_outcome AND revise is offered as a secondary action', (salesStage) => {
    const d = deal({ salesStage });
    expect(resolveWorkState({ role: 'sales' }, d, issuedPr, { reviseTarget: target }).action).toMatchObject({ key: 'record_quotation_outcome' });
    expect(secondaryWorkActions({ role: 'sales' }, d, issuedPr, { reviseTarget: target })).toEqual([
      { key: 'revise_quotation', label: 'แก้ใบเสนอราคา', quotationId: 90, quotationNumber: 'QT-2026-0090-1' },
    ]);
  });

  it('an open revision draft -> the secondary reads "ไปที่ฉบับแก้ไข" and points at the draft', () => {
    const result = secondaryWorkActions({ role: 'sales' }, deal(), issuedPr, { reviseTarget: { ...target, openDraftId: 91 } });
    expect(result).toEqual([
      { key: 'revise_quotation', label: 'ไปที่ฉบับแก้ไข', quotationId: 90, quotationNumber: 'QT-2026-0090-1', to: '/quotations/91' },
    ]);
  });

  it('negative: no reviseTarget -> nothing', () => {
    expect(secondaryWorkActions({ role: 'sales' }, deal(), issuedPr)).toEqual([]);
    expect(secondaryWorkActions({ role: 'sales' }, deal(), issuedPr, { reviseTarget: null })).toEqual([]);
  });

  it('negative: the designer stage and other stages get no secondary revise', () => {
    for (const salesStage of ['QUOTE_DESIGN_SIDE', 'PRESENTATION', 'NEGOTIATION']) {
      expect(secondaryWorkActions({ role: 'sales' }, deal({ salesStage }), issuedPr, { reviseTarget: target })).toEqual([]);
    }
  });

  it.each(['ceo', 'sales_manager', 'account', 'import', undefined])('negative: role %s never gets it, even if handed a target', (role) => {
    expect(secondaryWorkActions({ role }, deal(), issuedPr, { reviseTarget: target })).toEqual([]);
  });

  it('negative: a non-ACTIVE deal gets nothing', () => {
    expect(secondaryWorkActions({ role: 'sales' }, deal({ lifecycle: 'ON_HOLD' }), issuedPr, { reviseTarget: target })).toEqual([]);
  });

  it('no duplicate: when revise is already the PRIMARY (approved direct past draft) there is no secondary copy', () => {
    const d = deal({ status: 'quotation_issued', liveDirectQuotation: { id: 90, number: 'QT-2026-0090-1', docStatus: 'APPROVED' } });
    expect(resolveWorkState({ role: 'sales' }, d, [], { reviseTarget: target }).action).toMatchObject({ key: 'revise_quotation' });
    expect(secondaryWorkActions({ role: 'sales' }, d, [], { reviseTarget: target })).toEqual([]);
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

  it.each(['ISSUED', 'SUPERSEDED'])(
    'a designer quotation that went out (%s): เสนอราคา, beside "ใบเสนอราคาผู้ออกแบบส่งแล้ว เลือกเสนอราคาเจ้าของโครงการ หรือข้ามไปผู้ซื้อ/ผู้รับเหมา"',
    (docStatus) => {
      const result = resolveWorkState(sales, baseDeal({ status: 'draft' }), [request(5, 'DESIGNER', 'QUOTATION_ISSUED')], {
        offRouteParties: ROUTE_KNOWN, quotationByRequest: new Map([[5, quotation(9105, docStatus)]]),
      });
      expect(result.action).toMatchObject({ key: 'quote_next_party', parties: ['OWNER', 'BUYER'] });
      expect(result.bannerText).toBe('ใบเสนอราคาผู้ออกแบบส่งแล้ว เลือกเสนอราคาเจ้าของโครงการ หรือข้ามไปผู้ซื้อ/ผู้รับเหมา');
    },
  );

  it('one party left (the owner off the route): "ใบเสนอราคาผู้ออกแบบส่งแล้ว ถัดไปเสนอราคาผู้ซื้อ/ผู้รับเหมา"', () => {
    const result = resolveWorkState(sales, baseDeal({ status: 'draft' }), [request(5, 'DESIGNER', 'QUOTATION_ISSUED')], {
      offRouteParties: new Set(['OWNER']), quotationByRequest: new Map([[5, quotation(9105, 'ISSUED')]]),
    });
    expect(result.action).toMatchObject({ key: 'quote_next_party', parties: ['BUYER'] });
    expect(result.bannerText).toBe('ใบเสนอราคาผู้ออกแบบส่งแล้ว ถัดไปเสนอราคาผู้ซื้อ/ผู้รับเหมา');
  });

  it('a sent OWNER quotation: the owner may buy directly, so the line adds "หรือไม่มีผู้รับเหมา"', () => {
    const result = resolveWorkState(sales, baseDeal({ status: 'draft' }), [request(6, 'OWNER', 'QUOTATION_ISSUED')], {
      offRouteParties: ROUTE_KNOWN, quotationByRequest: new Map([[6, quotation(9106, 'ISSUED')]]),
    });
    expect(result.action).toMatchObject({ key: 'quote_next_party', anchorParty: 'OWNER', parties: ['BUYER'] });
    expect(result.bannerText).toBe('ใบเสนอราคาเจ้าของโครงการส่งแล้ว เลือกเสนอราคาผู้ซื้อ/ผู้รับเหมา หรือไม่มีผู้รับเหมา');
  });

  it('a sent OWNER request with no quotation row yet still offers "หรือไม่มีผู้รับเหมา" (its outcome is open)', () => {
    const result = resolveWorkState(sales, baseDeal({ status: 'draft' }), [request(6, 'OWNER', 'QUOTATION_ISSUED')], {
      offRouteParties: ROUTE_KNOWN, quotationByRequest: new Map(),
    });
    expect(result.action).toMatchObject({ key: 'quote_next_party', anchorParty: 'OWNER', skipBuyer: true });
    expect(result.bannerText).toBe('ใบเสนอราคาเจ้าของโครงการส่งแล้ว เลือกเสนอราคาผู้ซื้อ/ผู้รับเหมา หรือไม่มีผู้รับเหมา');
  });

  it('a SUPERSEDED owner quotation has no open outcome to record: no skip, plain "ถัดไป" line', () => {
    const result = resolveWorkState(sales, baseDeal({ status: 'draft' }), [request(6, 'OWNER', 'QUOTATION_ISSUED')], {
      offRouteParties: ROUTE_KNOWN, quotationByRequest: new Map([[6, quotation(9106, 'SUPERSEDED')]]),
    });
    expect(result.action).toMatchObject({ key: 'quote_next_party', skipBuyer: false });
    expect(result.bannerText).toBe('ใบเสนอราคาเจ้าของโครงการส่งแล้ว ถัดไปเสนอราคาผู้ซื้อ/ผู้รับเหมา');
  });

  it.each([
    [
      'the designer rejected it, two parties left',
      [request(5, 'DESIGNER', 'QUOTATION_ISSUED')],
      [[5, quotation(9105, 'REJECTED')]],
      'ใบเสนอราคาผู้ออกแบบไม่ผ่าน เลือกเสนอราคาเจ้าของโครงการ หรือข้ามไปผู้ซื้อ/ผู้รับเหมา',
    ],
    [
      'the owner\'s expired after it superseded the designer\'s, one party left',
      [request(5, 'DESIGNER', 'QUOTATION_ISSUED'), request(6, 'OWNER', 'QUOTATION_ISSUED')],
      [[5, quotation(9105, 'SUPERSEDED')], [6, quotation(9106, 'EXPIRED', 'QT-2026-0101-2')]],
      'ใบเสนอราคาเจ้าของโครงการไม่ผ่าน ถัดไปเสนอราคาผู้ซื้อ/ผู้รับเหมา',
    ],
  ])('a quotation the party said no to reads ไม่ผ่าน: %s', (_label, prs, entries, bannerText) => {
    const result = resolveWorkState(sales, baseDeal({ status: 'draft' }), prs, {
      offRouteParties: ROUTE_KNOWN, quotationByRequest: new Map(entries),
    });
    expect(result.action).toMatchObject({ key: 'quote_next_party' });
    expect(result.bannerText).toBe(bannerText);
  });

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

  // While a sent designer/owner quotation has no outcome,
  // the ⋯ menu offers บันทึกผลใบเสนอราคา on its request page, whatever the primary is.
  it.each([
    ['beside the route choice', [], 'ISSUED', true],
    ['while the owner\'s request is with import/CEO', [request(6, 'OWNER', 'SUBMITTED')], 'ISSUED', true],
    ['not once the designer said no', [], 'REJECTED', false],
  ])('a sent designer quotation, ⋯ บันทึกผลใบเสนอราคา %s', (_label, later, docStatus, offered) => {
    const prs = [request(5, 'DESIGNER', 'QUOTATION_ISSUED'), ...later];
    const keys = secondaryWorkActions(sales, baseDeal({ status: 'draft' }), prs, {
      offRouteParties: ROUTE_KNOWN, quotationByRequest: new Map([[5, quotation(9105, docStatus)]]),
    });
    const outcome = keys.find((action) => action.key === 'record_quotation_outcome');
    expect(outcome ? { label: outcome.label, pricingRequestId: outcome.pricingRequestId } : null)
      .toEqual(offered ? { label: 'บันทึกผลใบเสนอราคา', pricingRequestId: 5 } : null);
  });

  // Cancelling a request leaves its quotation PENDING_APPROVAL on the server; that
  // quotation is not the deal's to wait on.
  it('a cancelled request\'s quotation still awaiting approval is ignored: the rep gets the route choice', () => {
    const prs = [request(5, 'DESIGNER', 'QUOTATION_ISSUED'), request(6, 'OWNER', 'CANCELLED')];
    const opts = {
      offRouteParties: ROUTE_KNOWN,
      quotationByRequest: new Map([[5, quotation(9105, 'ISSUED')], [6, quotation(9106, 'PENDING_APPROVAL', 'QT-2026-0101-2')]]),
    };
    expect(resolveWorkState(sales, baseDeal({ status: 'draft' }), prs, opts).action).toMatchObject({ key: 'quote_next_party' });
  });

  // ผจก.ขาย's อนุมัติ only for a quotation they can approve on a deal without its order (a price
  // moved from the CEO's needs the CEO; nothing is approved after an acceptance).
  it.each([
    ['is on a cancelled request', [request(6, 'OWNER', 'CANCELLED')], {}],
    ['needs the CEO (its price mode changed from the CEO\'s)', [request(6, 'OWNER', 'APPROVED_FOR_QUOTATION')], { priceModeChangedFromCeo: true }],
    ['belongs to a later request after the designer accepted', [request(5, 'DESIGNER', 'QUOTATION_ACCEPTED'), request(6, 'OWNER', 'APPROVED_FOR_QUOTATION')], {}],
  ])('ผจก.ขาย gets no อนุมัติใบเสนอราคา for a quotation awaiting approval that %s', (_label, prs, extra) => {
    const pending = { ...quotation(9106, 'PENDING_APPROVAL'), origin: 'PRICING_REQUEST', ...extra };
    const result = resolveWorkState({ role: 'sales_manager' }, baseDeal(), prs, {
      offRouteParties: ROUTE_KNOWN, quotationByRequest: new Map([[6, pending]]),
    });
    expect(result.action).toBeNull();
  });

  it.each([
    ['sales_manager', 'APPROVED_FOR_QUOTATION', {}],
    ['ceo', 'QUOTATION_ISSUED', { origin: 'PRICING_REQUEST', priceModeChangedFromCeo: true }],
  ])('%s opening that deal (request %s) gets their own อนุมัติใบเสนอราคา, to the quotation (the CEO also for one that needs the CEO)', (role, status, extra) => {
    const result = resolveWorkState({ role }, baseDeal(), [request(5, 'OWNER', status)], {
      offRouteParties: ROUTE_KNOWN, quotationByRequest: new Map([[5, { ...quotation(9105, 'PENDING_APPROVAL'), ...extra }]]),
    });
    expect(result.action).toMatchObject({ label: 'อนุมัติใบเสนอราคา', to: '/quotations/9105' });
  });
});


describe('resolveWorkState — forwards depositNotice / remainingInvoice to nextSalesAction (owner rulings 2026-10-28)', () => {
  const deal = baseDeal({ status: 'quotation_issued', paymentStatus: 'CUSTOMER_CONFIRMED' });
  const prs = [{ id: 1, ticketId: 1, status: 'QUOTATION_ACCEPTED', orderConfirmedAt: '2026-10-01T00:00:00Z' }];

  it('sales: a DRAFT depositNotice reaches nextSalesAction (ออกใบแจ้งยอดเงินรับมัดจำ); without it, สร้าง...', () => {
    expect(resolveWorkState({ role: 'sales' }, deal, prs).action).toMatchObject({ key: 'create_deposit_notice', label: 'สร้างใบแจ้งยอดเงินรับมัดจำ' });
    expect(resolveWorkState({ role: 'sales' }, deal, prs, { depositNotice: { status: 'DRAFT' } }).action)
      .toMatchObject({ key: 'create_deposit_notice', label: 'ออกใบแจ้งยอดเงินรับมัดจำ' });
  });

  it('sales: remainingInvoice reaches nextSalesAction (null -> สร้างใบแจ้งหนี้, ISSUED -> not offered)', () => {
    const ready = baseDeal({ status: 'quotation_issued', fulfillmentStatus: 'FULLY_DELIVERED', paymentStatus: 'DEPOSIT_PAID' });
    expect(resolveWorkState({ role: 'sales' }, ready, prs, { remainingInvoice: null }).action)
      .toMatchObject({ key: 'create_remaining_invoice', label: 'สร้างใบแจ้งหนี้' });
    expect(resolveWorkState({ role: 'sales' }, ready, prs, { remainingInvoice: { status: 'ISSUED' } }).action?.key)
      .not.toBe('create_remaining_invoice');
  });
});

describe('resolveWorkState — forwards importRequests to nextSalesAction (QA-09)', () => {
  const deal = baseDeal({ status: 'quotation_issued', paymentStatus: 'DEPOSIT_PAID', fulfillmentStatus: null });
  const prs = [{ id: 1, ticketId: 1, status: 'QUOTATION_ACCEPTED', orderConfirmedAt: '2026-10-01T00:00:00Z' }];

  const decided = { procurementSource: { pending: false, decidedAt: '2026-11-10T00:00:00Z' } };

  it('sales: a DRAFT importRequests row reaches nextSalesAction (ออกเลขใบขอซื้อ (IR)); without it, ออกใบขอซื้อ (IR)', () => {
    expect(resolveWorkState({ role: 'sales' }, deal, prs, decided).action)
      .toMatchObject({ key: 'create_import_request', label: 'ออกใบขอซื้อ (IR)' });
    expect(resolveWorkState({ role: 'sales' }, deal, prs, { ...decided, importRequests: [{ id: 1, status: 'DRAFT' }] }).action)
      .toMatchObject({ key: 'create_import_request', label: 'ออกเลขใบขอซื้อ (IR)' });
  });

  it('sales: procurementSource reaches nextSalesAction - undecided gives จัดซื้อสินค้า, with the banner for the deal kind', () => {
    expect(resolveWorkState({ role: 'sales' }, deal, prs, { procurementSource: { legacy: false, pending: true, decidedAt: null } }))
      .toMatchObject({ action: { key: 'choose_procurement_source', label: 'จัดซื้อสินค้า' }, bannerText: 'ตรวจที่มาสินค้าตามใบเสนอราคา' });
    expect(resolveWorkState({ role: 'sales' }, deal, prs, { procurementSource: { legacy: true, pending: true, decidedAt: null } }).bannerText)
      .toBe('เลือกว่าสินค้าสั่งนำเข้าหรือมาจากสต็อก');
    expect(resolveWorkState({ role: 'sales' }, deal, prs, decided).bannerText).toBeUndefined();
  });
});
