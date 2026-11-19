import { describe, expect, it } from 'vitest';
import { SALES_ACTION, nextSalesAction, sortWorklist } from './salesActions.js';

function baseDeal(overrides = {}) {
  return {
    id: 1,
    lifecycle: 'ACTIVE',
    stageUpdatedAt: '2026-07-01T00:00:00.000Z',
    ...overrides,
  };
}

// A live, order-confirmed pricing request — the shape that clears buckets 1-3 without itself matching any of
// them, so bucket 4 (or whatever fires after it) is being tested in isolation.
function orderConfirmedPr(overrides = {}) {
  return { id: 1, ticketId: 1, status: 'QUOTATION_ACCEPTED', orderConfirmedAt: '2026-07-01T00:00:00.000Z', ...overrides };
}

describe('nextSalesAction — RECORD_DELIVERY (stages 13-14, owner ruling 2026-08-17)', () => {
  it('returns RECORD_DELIVERY for a GOODS_RECEIVED deal with an order-confirmed PR', () => {
    const deal = baseDeal({ status: 'quotation_issued', fulfillmentStatus: 'GOODS_RECEIVED' });
    expect(nextSalesAction(deal, [orderConfirmedPr()])).toEqual({
      key: SALES_ACTION.RECORD_DELIVERY,
      label: 'บันทึกส่งมอบ',
    });
  });

  it('does NOT return RECORD_DELIVERY once delivery is complete (FULLY_DELIVERED) or still mid-fulfilment (SHIPPING)', () => {
    const prs = [orderConfirmedPr()];
    // .toBeNull(), not just ".key !== RECORD_DELIVERY": with orderConfirmedAt already set, bucket 3 is also
    // closed, so a passing implementation must fall all the way through the cascade to nothing, not quietly land
    // on some OTHER bucket.
    expect(nextSalesAction(baseDeal({ status: 'quotation_issued', fulfillmentStatus: 'FULLY_DELIVERED' }), prs)).toBeNull();
    expect(nextSalesAction(baseDeal({ status: 'quotation_issued', fulfillmentStatus: 'SHIPPING' }), prs)).toBeNull();
  });

  // ── Bucket-1 interaction ───────────────────────────────────────────────────
  //
  // Bucket 1 KEEPS priority over the delivery bucket: a delivery-ready deal with ZERO pricing
  // requests and no price evidence (no quoted status, no paymentStatus) still asks for a pricing
  // request. That is correct: the only realistic way to reach a delivery-ready fulfillmentStatus
  // with NO pricing evidence is TicketService.reserveStock's FROM_STOCK auto-advance (no pricing
  // precondition at all), i.e. a never-priced deal, which bucket 1 exists to catch.
  it('bucket 1 (CREATE_PCR) wins over RECORD_DELIVERY when a delivery-ready deal has zero pricing requests and no price evidence', () => {
    const deal = baseDeal({ salesStage: 'DELIVERY_SCHEDULING', fulfillmentStatus: 'FROM_STOCK' });
    // No status/paymentStatus at all — genuinely never priced. Same shape as workState.test.js's pre-existing
    // 'still offers create_pcr after ...
    expect(nextSalesAction(deal, [])).toMatchObject({ key: SALES_ACTION.CREATE_PCR });
  });
});

describe('nextSalesAction — RECORD_QUOTATION_OUTCOME (bucket 4, deal-page CTA discoverability fix)', () => {
  it('CONFIRM_ORDER still wins when one PR is QUOTATION_ACCEPTED even alongside a stale sibling PR still at QUOTATION_ISSUED', () => {
    const prs = [
      { id: 1, ticketId: 1, status: 'QUOTATION_ISSUED' },
      { id: 2, ticketId: 1, status: 'QUOTATION_ACCEPTED', orderConfirmedAt: null },
    ];
    expect(nextSalesAction(baseDeal(), prs)).toMatchObject({ key: SALES_ACTION.CONFIRM_ORDER });
  });
});

// Baseline pass over the rest of the cascade: each bucket exercised through a deal/PR shape that
// clears every earlier bucket without matching it.
describe('nextSalesAction — full cascade baseline (buckets 1-3, 6-7)', () => {
  it('CREATE_PCR — no live pricing request, no price evidence', () => {
    expect(nextSalesAction(baseDeal(), [])).toMatchObject({ key: SALES_ACTION.CREATE_PCR });
  });

  it('ISSUE_QUOTATION — a live PR at APPROVED_FOR_QUOTATION, named for its recipient and carrying the PR id (new engine)', () => {
    const prs = [{ id: 7, ticketId: 1, status: 'APPROVED_FOR_QUOTATION', recipientType: 'OWNER' }];
    expect(nextSalesAction(baseDeal(), prs)).toEqual({
      key: SALES_ACTION.ISSUE_QUOTATION, label: 'เสนอใบราคาเจ้าของโครงการ', pricingRequestId: 7,
    });
  });

  it('CONFIRM_ORDER — QUOTATION_ACCEPTED but not yet order-confirmed', () => {
    const prs = [{ id: 1, ticketId: 1, status: 'QUOTATION_ACCEPTED', orderConfirmedAt: null }];
    expect(nextSalesAction(baseDeal(), prs)).toMatchObject({ key: SALES_ACTION.CONFIRM_ORDER });
  });

  it('CONFIRM_ORDER — NOT offered when the deal ticket is in a status the bridge cannot advance (ticket-status gate)', () => {
    const prs = [{ id: 1, ticketId: 1, status: 'QUOTATION_ACCEPTED', orderConfirmedAt: null }];
    for (const status of ['approved', 'price_proposed', 'in_review', 'submitted', 'document_issued']) {
      expect(nextSalesAction(baseDeal({ status }), prs)?.key).not.toBe(SALES_ACTION.CONFIRM_ORDER);
    }
  });

  it('FOLLOW_UP — order confirmed, not delivery-ready, follow-up overdue', () => {
    const deal = baseDeal({ status: 'quotation_issued', nextFollowUpAt: '2020-01-01' });
    expect(nextSalesAction(deal, [orderConfirmedPr()])).toMatchObject({ key: SALES_ACTION.FOLLOW_UP, followUp: 'overdue' });
  });

  it('LOG_ACTIVITY — nothing else pending, deal is stale', () => {
    const deal = baseDeal({ status: 'quotation_issued', stale: true });
    expect(nextSalesAction(deal, [orderConfirmedPr()])).toMatchObject({ key: SALES_ACTION.LOG_ACTIVITY });
  });

  it('null for a non-ACTIVE deal, and null for no deal at all', () => {
    expect(nextSalesAction(baseDeal({ lifecycle: 'ON_HOLD' }), [orderConfirmedPr()])).toBeNull();
    expect(nextSalesAction(null, [])).toBeNull();
  });
});

describe('sortWorklist — RECORD_DELIVERY rank relative to CONFIRM_ORDER and FOLLOW_UP', () => {
  it('places a delivery row after a confirm-order row but before a non-overdue follow-up row', () => {
    const items = [
      { deal: { id: 3, stageUpdatedAt: '2026-07-01T00:00:00.000Z' }, action: { key: SALES_ACTION.FOLLOW_UP, label: 'x', followUp: 'today' } },
      { deal: { id: 1, stageUpdatedAt: '2026-07-01T00:00:00.000Z' }, action: { key: SALES_ACTION.RECORD_DELIVERY, label: 'x' } },
      { deal: { id: 2, stageUpdatedAt: '2026-07-01T00:00:00.000Z' }, action: { key: SALES_ACTION.CONFIRM_ORDER, label: 'x' } },
    ];
    expect(sortWorklist(items).map((item) => item.deal.id)).toEqual([2, 1, 3]);
  });
});

// A deal whose direct quotation (DEAL_DIRECT) is live is being priced BY HAND. Its CTA follows that
// quotation (ส่งขออนุมัติ / รออนุมัติ / ยืนยันคำสั่งซื้อ) and must NEVER be "สร้างคำขอราคา": the three
// buckets sit BEFORE CREATE_PCR. Keyed on `deal.liveDirectQuotation` (TicketSummaryDto: the newest
// live DEAL_DIRECT row, or null).
describe('nextSalesAction — live direct quotation buckets (slice 2, flow A)', () => {
  function liveDirect(docStatus, overrides = {}) {
    return { id: 77, number: 'QT-2026-0077-1', docStatus, recipientType: 'DESIGNER', ...overrides };
  }

  it('APPROVED -> CONFIRM_ORDER_DIRECT "ยืนยันคำสั่งซื้อ"', () => {
    const deal = baseDeal({ liveDirectQuotation: liveDirect('APPROVED') });
    expect(nextSalesAction(deal, [])).toMatchObject({
      key: SALES_ACTION.CONFIRM_ORDER_DIRECT,
      label: 'ยืนยันคำสั่งซื้อ',
      quotationId: 77,
    });
  });

  // confirmOrderFromDirectQuotation 409s unless the deal's status is still 'draft'. A legacy deal
  // already past it (e.g. demo deal 1: status 'approved', payment under way) must NOT be offered a
  // ยืนยันคำสั่งซื้อ the server refuses: it falls through to the ordinary cascade. A list row always
  // carries `status`; absent reads as 'draft'.
  it('APPROVED on a deal already PAST draft is not offered CONFIRM_ORDER_DIRECT (the server would 409)', () => {
    for (const status of ['approved', 'quotation_issued', 'document_issued']) {
      const action = nextSalesAction(baseDeal({ status, liveDirectQuotation: liveDirect('APPROVED') }), []);
      expect(action?.key).not.toBe(SALES_ACTION.CONFIRM_ORDER_DIRECT);
    }
    expect(nextSalesAction(baseDeal({ status: 'draft', liveDirectQuotation: liveDirect('APPROVED') }), []))
      .toMatchObject({ key: SALES_ACTION.CONFIRM_ORDER_DIRECT });
  });

  // Same design as the pricing-request route choice: an APPROVED designer/owner direct quotation lets
  // the rep quote the NEXT party (เสนอราคา menu is primary; ยืนยันคำสั่งซื้อ moves to the ⋯ menu).
  describe('APPROVED designer/owner direct quotation -> QUOTE_NEXT_PARTY (direct)', () => {
    const KNOWN = new Set(); // every party on the route
    const next = (liveOverrides, { dealOverrides = {}, offRouteParties = KNOWN } = {}) =>
      nextSalesAction(baseDeal({ status: 'draft', liveDirectQuotation: liveDirect('APPROVED', liveOverrides), ...dealOverrides }), [],
        { offRouteParties });

    it('stays CONFIRM_ORDER_DIRECT for BUYER / UNSPECIFIED, when no later party is on the route, and when the route is unknown', () => {
      for (const recipientType of ['BUYER', 'UNSPECIFIED']) {
        expect(next({ recipientType }).key).toBe(SALES_ACTION.CONFIRM_ORDER_DIRECT);
      }
      expect(next({ recipientType: 'OWNER' }, { offRouteParties: new Set(['BUYER']) }).key).toBe(SALES_ACTION.CONFIRM_ORDER_DIRECT);
      expect(next({ recipientType: 'DESIGNER' }, { offRouteParties: new Set(['OWNER', 'BUYER']) }).key).toBe(SALES_ACTION.CONFIRM_ORDER_DIRECT);
      expect(next({ recipientType: 'DESIGNER' }, { offRouteParties: null }).key).toBe(SALES_ACTION.CONFIRM_ORDER_DIRECT);
    });
  });
});

// แก้ใบเสนอราคา (REVISE_QUOTATION). nextSalesAction is list-row-only and cannot see quotation rows
// or who owns them, so the CALLER resolves the revisable quotation (via canReviseDealQuotation) and
// hands it in as `{ reviseTarget }`:
//   { quotationId, number, openDraftId? }   — openDraftId = an open revision child already exists.
// The cascade consumes it in two places: (1) a live DEAL_DIRECT quotation already APPROVED on a deal
// past 'draft' (the confirm-order bucket refuses it) and (2) the owner/buyer quote stages
// (QUOTE_OWNER / QUOTE_BUYER) with an issued PR-origin quotation, which would dead-end on
// RECORD_QUOTATION_OUTCOME.
describe('nextSalesAction — REVISE_QUOTATION (PR A)', () => {
  const REVISE = 'revise_quotation'; // literal on purpose: SALES_ACTION.REVISE_QUOTATION is undefined until implemented
  const target = { quotationId: 90, number: 'QT-2026-0090-1' };
  const issuedPr = (overrides = {}) => ({ id: 5, ticketId: 1, status: 'QUOTATION_ISSUED', orderConfirmedAt: null, ...overrides });
  const approvedDirect = { id: 90, number: 'QT-2026-0090-1', docStatus: 'APPROVED', recipientType: 'DESIGNER' };

  it('APPROVED direct quotation on a deal past draft -> REVISE_QUOTATION instead of the old CREATE_PCR fall-through', () => {
    const deal = baseDeal({ status: 'quotation_issued', liveDirectQuotation: approvedDirect });
    expect(nextSalesAction(deal, [], { reviseTarget: target })).toEqual({
      key: REVISE, label: 'แก้ใบเสนอราคา', quotationId: 90, quotationNumber: 'QT-2026-0090-1',
    });
  });

  it('wrong-way-round: NO reviseTarget (non-owner / other role / predicate refused) -> cascade exactly as before', () => {
    const deal = baseDeal({ salesStage: 'QUOTE_OWNER' });
    expect(nextSalesAction(deal, [issuedPr()])).toMatchObject({ key: SALES_ACTION.RECORD_QUOTATION_OUTCOME });
    expect(nextSalesAction(deal, [issuedPr()], { reviseTarget: null })).toMatchObject({ key: SALES_ACTION.RECORD_QUOTATION_OUTCOME });
    const past = baseDeal({ status: 'quotation_issued', liveDirectQuotation: approvedDirect });
    expect(nextSalesAction(past, [], {})?.key).not.toBe(REVISE);
  });
});

// ขั้น 1 → 2 → 3 are moved by hand, and the price request starts at ขั้น 3. On LEAD_APPROACH and
// PRESENTATION the main action is always เลื่อนขั้น: a due follow-up, a stale deal or an older price
// request does not take it over. Only a live direct quotation (bucket 0) still comes first. From
// SPEC_APPROVED onwards nothing changes.
describe('ขั้น 1–2: the main action is always เลื่อนขั้น', () => {
  it.each(['LEAD_APPROACH', 'PRESENTATION'])('%s with no price request -> ADVANCE_STAGE', (salesStage) => {
    expect(nextSalesAction(baseDeal({ salesStage }), [])).toEqual({ key: SALES_ACTION.ADVANCE_STAGE, label: 'เลื่อนขั้น' });
  });

  it.each(['SPEC_APPROVED', 'QUOTE_DESIGN_SIDE', 'QUOTE_OWNER', 'OWNER_SIGNOFF', 'AWAITING_BUYER', 'QUOTE_BUYER', 'NEGOTIATION'])(
    'boundary: %s with no price request -> CREATE_PCR, as before',
    (salesStage) => {
      expect(nextSalesAction(baseDeal({ salesStage }), [])).toEqual({ key: SALES_ACTION.CREATE_PCR, label: 'สร้างคำขอราคา' });
    },
  );
});

// The rep picks the next party when the designer's or owner's quotation is SENT, not after an
// acceptance. An acceptance is the order (ยืนยันคำสั่งซื้อ), whoever accepted: the server refuses a
// second acceptance on a deal, so a route that waited for one could never finish.
//
// Passed the way the deal page and the dashboard pass them:
//   offRouteParties    — a Set of the parties the deal's served route skips (an empty Set = every
//                        party is on it); absent = the route is not known yet.
//   quotationByRequest — a Map, pricingRequestId -> the newest PRICING_REQUEST-origin quotation the
//                        deal page read for that request ({ id, number, docStatus }); absent on the
//                        dashboard, which has no quotation list.
describe('nextSalesAction — the route choice once a designer/owner quotation is sent (owner ruling 2026-10-27)', () => {
  const QUOTE_NEXT = 'quote_next_party'; // literal on purpose: the page and e2e key on data-action="quote_next_party"
  const ROUTE_KNOWN = new Set();
  const request = (id, recipientType, status) => ({ id, ticketId: 1, status, recipientType, orderConfirmedAt: null });
  const designerSent = request(1, 'DESIGNER', 'QUOTATION_ISSUED');
  const quotations = (...entries) => new Map(entries.map(([pricingRequestId, docStatus]) => [
    pricingRequestId, { id: 9100 + pricingRequestId, number: `QT-2026-010${pricingRequestId}-1`, docStatus },
  ]));
  const deal = (overrides) => baseDeal({ status: 'draft', ...overrides });
  const known = (...entries) => ({ offRouteParties: ROUTE_KNOWN, quotationByRequest: quotations(...entries) });

  // No row for the request means its status alone decides.
  it.each([
    ['the dashboard, which has no quotation list', undefined],
    ['a list with no row for it (a legacy old-engine quotation, or the list not loaded)', new Map()],
  ])('no quotation row for the request (%s): its status alone decides — a designer request sent, a buyer one to record', (_label, quotationByRequest) => {
    expect(nextSalesAction(deal(), [designerSent], { offRouteParties: ROUTE_KNOWN, quotationByRequest })).toMatchObject({
      key: QUOTE_NEXT, label: 'เสนอราคาเจ้าของโครงการ', parties: ['OWNER', 'BUYER'],
    });
    expect(nextSalesAction(deal(), [request(1, 'BUYER', 'QUOTATION_ISSUED')], { offRouteParties: ROUTE_KNOWN, quotationByRequest }))
      .toMatchObject({ key: SALES_ACTION.RECORD_QUOTATION_OUTCOME, pricingRequestId: 1 });
  });

  // Review V1: an approved later request (bucket 2) does not hold it back either.
  it.each([
    ['SUBMITTED', []],
    ['APPROVED_FOR_QUOTATION', []],
    ['APPROVED_FOR_QUOTATION', [[2, 'PENDING_APPROVAL']]],
  ])('an accepted designer request -> ยืนยันคำสั่งซื้อ even while the owner\'s request is %s %j (nothing holds the confirm back)', (status, ownerEntries) => {
    const prs = [request(1, 'DESIGNER', 'QUOTATION_ACCEPTED'), request(2, 'OWNER', status)];
    expect(nextSalesAction(deal(), prs, known([1, 'ACCEPTED'], ...ownerEntries))).toMatchObject({ key: SALES_ACTION.CONFIRM_ORDER });
  });
});

// The deal page's main button creates the deposit notice (ขั้น 10) and the remaining invoice
// (ขั้น 13). `depositNotice` / `remainingInvoice` are page-only options; an absent `remainingInvoice`
// (the dashboard worklist) never offers the invoice.
describe('nextSalesAction — สร้างใบแจ้งยอดเงินรับมัดจำ / สร้างใบแจ้งหนี้ (owner rulings 2026-10-28)', () => {
  const confirmedDeal = (overrides = {}) => baseDeal({ status: 'quotation_issued', paymentStatus: 'CUSTOMER_CONFIRMED', ...overrides });
  const CREATE_DEPOSIT = { key: 'create_deposit_notice', label: 'สร้างใบแจ้งยอดเงินรับมัดจำ' };

  it('ขั้น 10, pricing-request flow (CUSTOMER_CONFIRMED, order-confirmed accepted PR) -> สร้างใบแจ้งยอดเงินรับมัดจำ', () => {
    expect(nextSalesAction(confirmedDeal(), [orderConfirmedPr()])).toEqual(CREATE_DEPOSIT);
    expect(nextSalesAction(confirmedDeal({ depositPolicy: 'REQUIRED' }), [orderConfirmedPr()])).toEqual(CREATE_DEPOSIT);
  });

  it.each(['NOT_REQUIRED', 'WAIVED', 'CREDIT_CUSTOMER'])('depositPolicy %s -> no deposit-notice action', (depositPolicy) => {
    expect(nextSalesAction(confirmedDeal({ depositPolicy }), [orderConfirmedPr()])?.key).not.toBe('create_deposit_notice');
  });

  // ขั้น 13
  const readyDeal = (overrides = {}) => baseDeal({ status: 'quotation_issued', fulfillmentStatus: 'FULLY_DELIVERED', paymentStatus: 'DEPOSIT_PAID', ...overrides });

  it('ขั้น 13, ready deal with no remaining invoice -> สร้างใบแจ้งหนี้; a DRAFT one -> ออกใบแจ้งหนี้', () => {
    expect(nextSalesAction(readyDeal(), [orderConfirmedPr()], { remainingInvoice: null }))
      .toEqual({ key: 'create_remaining_invoice', label: 'สร้างใบแจ้งหนี้' });
    expect(nextSalesAction(readyDeal(), [orderConfirmedPr()], { remainingInvoice: { status: 'DRAFT' } }))
      .toEqual({ key: 'create_remaining_invoice', label: 'ออกใบแจ้งหนี้' });
  });
});

// ขั้น 11 (DEPOSIT_RECEIVED): the owning rep's one main button creates the import request.
describe('nextSalesAction — ออกใบขอซื้อ (IR) at ขั้น 11 (owner rulings QA-09)', () => {
  const paidDeal = (overrides = {}) => baseDeal({ status: 'quotation_issued', paymentStatus: 'DEPOSIT_PAID', fulfillmentStatus: null, ...overrides });
  const CREATE_IR = { key: 'create_import_request', label: 'ออกใบขอซื้อ (IR)' };

  // The stage-10 "จัดซื้อสินค้า" decision comes first; the cases below run with it already made.
  const DECIDED = { procurementSource: { decidedAt: '2026-11-10T00:00:00Z' } };

  it('IR action returns once decided', () => {
    expect(nextSalesAction(paidDeal(), [orderConfirmedPr()], { ...DECIDED, importRequests: [] })).toEqual(CREATE_IR);
  });
});
