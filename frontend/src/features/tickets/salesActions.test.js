import { describe, expect, it } from 'vitest';
import { SALES_ACTION, nextSalesAction, sortWorklist } from './salesActions.js';
import { bangkokTodayIso } from '../../utils/format.js';

// First direct unit-test file for this module (previously only exercised indirectly through
// workState.test.js's resolveWorkState cases — see that file's own "nextSalesAction — CREATE_PCR
// gate" describe block, which predates and still covers the bucket-1 UAT-bug guard on its own).
// Focus here is the newest bucket, RECORD_DELIVERY (stages 13-14 ส่งมอบสินค้า, owner ruling
// 2026-08-17), and its interaction with the rest of the cascade — plus a baseline pass over every
// other bucket so this file stands on its own as the module's primary test.

function baseDeal(overrides = {}) {
  return {
    id: 1,
    lifecycle: 'ACTIVE',
    stageUpdatedAt: '2026-07-01T00:00:00.000Z',
    ...overrides,
  };
}

// A live, order-confirmed pricing request — the shape that clears buckets 1-3 without itself
// matching any of them, so bucket 4 (or whatever fires after it) is being tested in isolation.
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

  it('returns RECORD_DELIVERY for FROM_STOCK and PARTIALLY_DELIVERED too — every status nextFulfilmentActionCode calls delivery-ready', () => {
    const prs = [orderConfirmedPr()];
    expect(nextSalesAction(baseDeal({ status: 'quotation_issued', fulfillmentStatus: 'FROM_STOCK' }), prs))
      .toMatchObject({ key: SALES_ACTION.RECORD_DELIVERY });
    expect(nextSalesAction(baseDeal({ status: 'quotation_issued', fulfillmentStatus: 'PARTIALLY_DELIVERED' }), prs))
      .toMatchObject({ key: SALES_ACTION.RECORD_DELIVERY });
  });

  it('does NOT return RECORD_DELIVERY once delivery is complete (FULLY_DELIVERED) or still mid-fulfilment (SHIPPING)', () => {
    const prs = [orderConfirmedPr()];
    // .toBeNull(), not just ".key !== RECORD_DELIVERY": with orderConfirmedAt already set, bucket 3
    // is also closed, so a passing implementation must fall all the way through the cascade to
    // nothing, not quietly land on some OTHER bucket.
    expect(nextSalesAction(baseDeal({ status: 'quotation_issued', fulfillmentStatus: 'FULLY_DELIVERED' }), prs)).toBeNull();
    expect(nextSalesAction(baseDeal({ status: 'quotation_issued', fulfillmentStatus: 'SHIPPING' }), prs)).toBeNull();
  });

  it('outranks a due follow-up on the same deal — bucket 4 returns before bucket 5 (follow-up) is ever checked', () => {
    const deal = baseDeal({
      status: 'quotation_issued', fulfillmentStatus: 'GOODS_RECEIVED',
      nextFollowUpAt: '2020-01-01', // deeply overdue — would win bucket 5 outright on its own
    });
    const action = nextSalesAction(deal, [orderConfirmedPr()]);
    expect(action.key).toBe(SALES_ACTION.RECORD_DELIVERY);
    // `followUp` is only ever set by bucket 5's own return — its absence here confirms this really
    // is bucket 4 firing, not a follow-up action that happens to carry the delivery label.
    expect(action.followUp).toBeUndefined();
  });

  // ── Bucket-1 interaction — checked deliberately, per this branch's plan ("do not silently change
  // bucket 1"), not left to whatever the code happened to do. ─────────────────────────────────────
  //
  // Decision: bucket 1 KEEPS priority over bucket 4. A delivery-ready deal with ZERO pricing
  // requests and no price evidence at all (no quoted status, no paymentStatus) still asks for a
  // pricing request rather than offering to record a delivery. This is arguably correct, not a gap:
  // the only realistic way to reach a delivery-ready fulfillmentStatus with NO pricing evidence
  // whatsoever is TicketService.reserveStock's FROM_STOCK auto-advance, which bucket 1's own
  // comment in salesActions.js already documents as having "no pricing precondition at all" — i.e.
  // exactly the genuinely-never-priced deal the CREATE_PCR UAT-bug guard exists to catch, not the
  // "already had a price and got asked again" case the guard was built to fix. Changing bucket 1's
  // priority over bucket 4 is out of scope for this change.
  it('bucket 1 (CREATE_PCR) wins over RECORD_DELIVERY when a delivery-ready deal has zero pricing requests and no price evidence', () => {
    const deal = baseDeal({ salesStage: 'DELIVERY_SCHEDULING', fulfillmentStatus: 'FROM_STOCK' });
    // No status/paymentStatus at all — genuinely never priced. Same shape as
    // workState.test.js's pre-existing 'still offers create_pcr after ... FROM_STOCK' case, which
    // already exercised this exact interaction before bucket 4 existed; this test pins it again
    // directly against nextSalesAction, from this bucket's own side.
    expect(nextSalesAction(deal, [])).toMatchObject({ key: SALES_ACTION.CREATE_PCR });
  });

  // The counterpart, so the guard above reads as a real decision and not an accident of always
  // losing: once there IS price evidence — even with zero live (or zero total) pricing requests,
  // the pricedOutsidePcrChain legacy-deal case — bucket 1 steps aside and RECORD_DELIVERY fires.
  // This is the exact shape of the two sales-owned demo fixtures already in demoData.js (tickets 13
  // "Mega Bangna Retail" and 14 "IconSiam Riverside": createdById 6, zero PricingRequest rows,
  // status quotation_issued, fulfillmentStatus GOODS_RECEIVED, a non-null paymentStatus) — see this
  // branch's own report for how that was confirmed against the mock seed.
  it('RECORD_DELIVERY fires once there is price evidence, even with zero pricing requests (legacy pre-PCR-chain deal)', () => {
    const deal = baseDeal({
      status: 'quotation_issued', fulfillmentStatus: 'GOODS_RECEIVED', paymentStatus: 'AWAITING_FINAL_PAYMENT',
    });
    expect(nextSalesAction(deal, [])).toEqual({ key: SALES_ACTION.RECORD_DELIVERY, label: 'บันทึกส่งมอบ' });
  });
});

describe('nextSalesAction — RECORD_QUOTATION_OUTCOME (bucket 4, deal-page CTA discoverability fix)', () => {
  it('returns RECORD_QUOTATION_OUTCOME for a live PR at QUOTATION_ISSUED (previously fell through to follow-up/activity)', () => {
    const prs = [{ id: 4, ticketId: 1, status: 'QUOTATION_ISSUED' }];
    expect(nextSalesAction(baseDeal(), prs)).toEqual({
      key: SALES_ACTION.RECORD_QUOTATION_OUTCOME,
      label: 'บันทึกผลใบเสนอราคา',
      pricingRequestId: 4,
    });
  });

  it('CONFIRM_ORDER still wins when one PR is QUOTATION_ACCEPTED even alongside a stale sibling PR still at QUOTATION_ISSUED', () => {
    const prs = [
      { id: 1, ticketId: 1, status: 'QUOTATION_ISSUED' },
      { id: 2, ticketId: 1, status: 'QUOTATION_ACCEPTED', orderConfirmedAt: null },
    ];
    expect(nextSalesAction(baseDeal(), prs)).toMatchObject({ key: SALES_ACTION.CONFIRM_ORDER });
  });

  it('RECORD_QUOTATION_OUTCOME outranks RECORD_DELIVERY when both could apply (bucket 4 fires before bucket 5 is ever checked)', () => {
    const deal = baseDeal({ status: 'quotation_issued', fulfillmentStatus: 'GOODS_RECEIVED' });
    const prs = [{ id: 1, ticketId: 1, status: 'QUOTATION_ISSUED' }];
    expect(nextSalesAction(deal, prs)).toMatchObject({ key: SALES_ACTION.RECORD_QUOTATION_OUTCOME });
  });
});

// Baseline pass over the rest of the cascade — establishes that inserting buckets 4/5 did not
// disturb buckets 1-3/6-7, each exercised through a deal/PR shape that clears every earlier bucket
// without matching it.
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

  // Spec D2 (2026-10-27): the deal page hands in its quotation list (`quotationByRequest`, a Map of
  // pricingRequestId -> the newest PRICING_REQUEST-origin quotation). A DRAFT already written for the
  // approved request is opened, never written a second time.
  it('ISSUE_QUOTATION — the approved request\'s DRAFT quotation is already written: "เสนอใบราคา<party>" points at that draft', () => {
    const prs = [{ id: 7, ticketId: 1, status: 'APPROVED_FOR_QUOTATION', recipientType: 'OWNER' }];
    const quotationByRequest = new Map([[7, { id: 9107, number: 'QT-2026-0107-1', docStatus: 'DRAFT' }]]);
    expect(nextSalesAction(baseDeal(), prs, { quotationByRequest })).toMatchObject({
      key: SALES_ACTION.ISSUE_QUOTATION, label: 'เสนอใบราคาเจ้าของโครงการ', to: '/quotations/9107',
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

  it('CONFIRM_ORDER — still offered for a quotation_issued ticket (a later accepted revision)', () => {
    const prs = [{ id: 1, ticketId: 1, status: 'QUOTATION_ACCEPTED', orderConfirmedAt: null }];
    expect(nextSalesAction(baseDeal({ status: 'quotation_issued' }), prs)).toMatchObject({ key: SALES_ACTION.CONFIRM_ORDER });
  });

  it('FOLLOW_UP — order confirmed, not delivery-ready, follow-up overdue', () => {
    const deal = baseDeal({ status: 'quotation_issued', nextFollowUpAt: '2020-01-01' });
    expect(nextSalesAction(deal, [orderConfirmedPr()])).toMatchObject({ key: SALES_ACTION.FOLLOW_UP, followUp: 'overdue' });
  });

  it('LOG_ACTIVITY — nothing else pending, deal is stale', () => {
    const deal = baseDeal({ status: 'quotation_issued', stale: true });
    expect(nextSalesAction(deal, [orderConfirmedPr()])).toMatchObject({ key: SALES_ACTION.LOG_ACTIVITY });
  });

  it('null — nothing pending anywhere in the cascade (a live PR sitting with import, nothing else due)', () => {
    const prs = [{ id: 1, ticketId: 1, status: 'IMPORT_REVIEWING' }];
    expect(nextSalesAction(baseDeal({ status: 'quotation_issued' }), prs)).toBeNull();
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

  it('places RECORD_QUOTATION_OUTCOME between ISSUE_QUOTATION and CREATE_PCR, ahead of RECORD_DELIVERY', () => {
    const items = [
      { deal: { id: 1, stageUpdatedAt: '2026-07-01T00:00:00.000Z' }, action: { key: SALES_ACTION.RECORD_DELIVERY, label: 'x' } },
      { deal: { id: 2, stageUpdatedAt: '2026-07-01T00:00:00.000Z' }, action: { key: SALES_ACTION.CREATE_PCR, label: 'x' } },
      { deal: { id: 3, stageUpdatedAt: '2026-07-01T00:00:00.000Z' }, action: { key: SALES_ACTION.RECORD_QUOTATION_OUTCOME, label: 'x' } },
      { deal: { id: 4, stageUpdatedAt: '2026-07-01T00:00:00.000Z' }, action: { key: SALES_ACTION.ISSUE_QUOTATION, label: 'x' } },
    ];
    expect(sortWorklist(items).map((item) => item.deal.id)).toEqual([4, 3, 2, 1]);
  });

  it('an OVERDUE follow-up still leads ahead of a delivery row, despite RECORD_DELIVERY outranking FOLLOW_UP under equal urgency', () => {
    const items = [
      { deal: { id: 1, stageUpdatedAt: '2026-07-01T00:00:00.000Z' }, action: { key: SALES_ACTION.RECORD_DELIVERY, label: 'x' } },
      { deal: { id: 2, stageUpdatedAt: '2026-07-01T00:00:00.000Z' }, action: { key: SALES_ACTION.FOLLOW_UP, label: 'x', followUp: 'overdue' } },
    ];
    expect(sortWorklist(items).map((item) => item.deal.id)).toEqual([2, 1]);
  });

  it('two delivery rows tie-break on the OLDER stageUpdatedAt first', () => {
    const items = [
      { deal: { id: 1, stageUpdatedAt: '2026-07-10T00:00:00.000Z' }, action: { key: SALES_ACTION.RECORD_DELIVERY, label: 'x' } },
      { deal: { id: 2, stageUpdatedAt: '2026-07-01T00:00:00.000Z' }, action: { key: SALES_ACTION.RECORD_DELIVERY, label: 'x' } },
    ];
    expect(sortWorklist(items).map((item) => item.deal.id)).toEqual([2, 1]);
  });

  it('does not mutate the input array', () => {
    const items = [
      { deal: { id: 1, stageUpdatedAt: '2026-07-01T00:00:00.000Z' }, action: { key: SALES_ACTION.FOLLOW_UP, label: 'x' } },
      { deal: { id: 2, stageUpdatedAt: '2026-07-01T00:00:00.000Z' }, action: { key: SALES_ACTION.RECORD_DELIVERY, label: 'x' } },
    ];
    const original = [...items];
    sortWorklist(items);
    expect(items).toEqual(original);
  });
});

// Slice 2 — flow A (SLICE-2-FLOW-A.md §E, IA §4): a deal whose direct quotation (DEAL_DIRECT) is
// live is being priced BY HAND. Its CTA follows that quotation — ส่งขออนุมัติ / รออนุมัติ / ยืนยัน
// คำสั่งซื้อ — and must NEVER be "สร้างคำขอราคา": the three buckets sit BEFORE CREATE_PCR. Keyed on
// `deal.liveDirectQuotation` (TicketSummaryDto S2-B4 — the newest live DEAL_DIRECT row, or null).
describe('nextSalesAction — live direct quotation buckets (slice 2, flow A)', () => {
  function liveDirect(docStatus, overrides = {}) {
    return { id: 77, number: 'QT-2026-0077-1', docStatus, recipientType: 'DESIGNER', ...overrides };
  }

  it('DRAFT -> SUBMIT_DIRECT_QUOTATION "ส่งขออนุมัติใบเสนอราคา", pointing at the quotation', () => {
    const deal = baseDeal({ liveDirectQuotation: liveDirect('DRAFT') });
    expect(nextSalesAction(deal, [])).toEqual({
      key: SALES_ACTION.SUBMIT_DIRECT_QUOTATION,
      label: 'ส่งขออนุมัติใบเสนอราคา',
      quotationId: 77,
      quotationNumber: 'QT-2026-0077-1',
      to: '/quotations/77',
    });
  });

  it('PENDING_APPROVAL -> AWAIT_DIRECT_APPROVAL "รออนุมัติใบเสนอราคา" (a waiting state, carries the number for the banner)', () => {
    const deal = baseDeal({ liveDirectQuotation: liveDirect('PENDING_APPROVAL') });
    expect(nextSalesAction(deal, [])).toMatchObject({
      key: SALES_ACTION.AWAIT_DIRECT_APPROVAL,
      label: 'รออนุมัติใบเสนอราคา',
      quotationId: 77,
      quotationNumber: 'QT-2026-0077-1',
    });
  });

  it('APPROVED -> CONFIRM_ORDER_DIRECT "ยืนยันคำสั่งซื้อ"', () => {
    const deal = baseDeal({ liveDirectQuotation: liveDirect('APPROVED') });
    expect(nextSalesAction(deal, [])).toMatchObject({
      key: SALES_ACTION.CONFIRM_ORDER_DIRECT,
      label: 'ยืนยันคำสั่งซื้อ',
      quotationId: 77,
    });
  });

  it('wins over CREATE_PCR — a zero-pricing-request deal with a live direct quotation is never told to open a คำขอราคา', () => {
    // Exactly bucket 1's shape (no PR at all, no price evidence) — this is the ordering test.
    const keys = ['DRAFT', 'PENDING_APPROVAL', 'APPROVED']
      .map((docStatus) => nextSalesAction(baseDeal({ liveDirectQuotation: liveDirect(docStatus) }), [])?.key);
    expect(keys).toEqual([
      SALES_ACTION.SUBMIT_DIRECT_QUOTATION, SALES_ACTION.AWAIT_DIRECT_APPROVAL, SALES_ACTION.CONFIRM_ORDER_DIRECT,
    ]);
    expect(keys).not.toContain(SALES_ACTION.CREATE_PCR);
  });

  // Found in the browser: confirmOrderFromDirectQuotation 409s unless the deal's status is still
  // 'draft' (slice 1, 2ed3468e). A legacy deal already past it (e.g. demo deal 1: status 'approved',
  // payment under way) must NOT be offered a ยืนยันคำสั่งซื้อ the server refuses — it falls through
  // to the ordinary cascade instead. A list row always carries `status`; absent reads as 'draft'.
  it('APPROVED on a deal already PAST draft is not offered CONFIRM_ORDER_DIRECT (the server would 409)', () => {
    for (const status of ['approved', 'quotation_issued', 'document_issued']) {
      const action = nextSalesAction(baseDeal({ status, liveDirectQuotation: liveDirect('APPROVED') }), []);
      expect(action?.key).not.toBe(SALES_ACTION.CONFIRM_ORDER_DIRECT);
    }
    expect(nextSalesAction(baseDeal({ status: 'draft', liveDirectQuotation: liveDirect('APPROVED') }), []))
      .toMatchObject({ key: SALES_ACTION.CONFIRM_ORDER_DIRECT });
  });

  it('null / absent liveDirectQuotation leaves the cascade exactly as before (CREATE_PCR)', () => {
    expect(nextSalesAction(baseDeal({ liveDirectQuotation: null }), [])).toMatchObject({ key: SALES_ACTION.CREATE_PCR });
    expect(nextSalesAction(baseDeal(), [])).toMatchObject({ key: SALES_ACTION.CREATE_PCR });
  });

  it('a non-live status (CANCELLED / SUPERSEDED / REJECTED) is ignored — the server never sends one, but the client must not act on it either', () => {
    for (const docStatus of ['CANCELLED', 'SUPERSEDED', 'REJECTED']) {
      expect(nextSalesAction(baseDeal({ liveDirectQuotation: liveDirect(docStatus) }), []))
        .toMatchObject({ key: SALES_ACTION.CREATE_PCR });
    }
  });

  it('a non-ACTIVE deal gets nothing, live quotation or not (same lifecycle guard as every bucket)', () => {
    expect(nextSalesAction(baseDeal({ lifecycle: 'ON_HOLD', liveDirectQuotation: liveDirect('DRAFT') }), [])).toBeNull();
  });

  it('ranks: CONFIRM_ORDER_DIRECT with CONFIRM_ORDER (1), SUBMIT_DIRECT_QUOTATION with ISSUE_QUOTATION (2), AWAIT_DIRECT_APPROVAL with the waiting rank (5)', () => {
    const at = '2026-07-01T00:00:00.000Z';
    const row = (id, key) => ({ deal: { id, stageUpdatedAt: at }, action: { key } });
    const sorted = sortWorklist([
      row(1, SALES_ACTION.AWAIT_DIRECT_APPROVAL),
      row(2, SALES_ACTION.FOLLOW_UP),
      row(3, SALES_ACTION.CREATE_PCR),
      row(4, SALES_ACTION.SUBMIT_DIRECT_QUOTATION),
      row(5, SALES_ACTION.CONFIRM_ORDER_DIRECT),
    ]).map((item) => item.deal.id);
    // CONFIRM_ORDER_DIRECT(1) < SUBMIT(2) < CREATE_PCR(4) < AWAIT(5) < FOLLOW_UP(6)
    expect(sorted).toEqual([5, 4, 3, 1, 2]);
  });

  it('shares a rank with its sibling: inside rank 2 the longest-waiting deal leads, whichever bucket it is', () => {
    const sorted = sortWorklist([
      { deal: { id: 1, stageUpdatedAt: '2026-07-05T00:00:00.000Z' }, action: { key: SALES_ACTION.SUBMIT_DIRECT_QUOTATION } },
      { deal: { id: 2, stageUpdatedAt: '2026-07-01T00:00:00.000Z' }, action: { key: SALES_ACTION.ISSUE_QUOTATION } },
      { deal: { id: 3, stageUpdatedAt: '2026-06-01T00:00:00.000Z' }, action: { key: SALES_ACTION.SUBMIT_DIRECT_QUOTATION } },
    ]).map((item) => item.deal.id);
    expect(sorted).toEqual([3, 2, 1]);
  });
});

// PR A — แก้ใบเสนอราคา (REVISE_QUOTATION). nextSalesAction is list-row-only and cannot see
// quotation rows or who owns them, so the CALLER resolves the revisable quotation (via
// canReviseDealQuotation, which PR B widens) and hands it in as `{ reviseTarget }`:
//   { quotationId, number, openDraftId? }   — openDraftId = an open revision child already exists.
// Two places the cascade consumes it: (1) a live DEAL_DIRECT quotation already APPROVED on a deal
// past 'draft' (the confirm-order bucket refuses it) and (2) the owner/buyer quote stages
// (QUOTE_OWNER / QUOTE_BUYER) with an issued PR-origin quotation, which used to dead-end on
// RECORD_QUOTATION_OUTCOME. Everything else in the cascade is unchanged.
describe('nextSalesAction — REVISE_QUOTATION (PR A)', () => {
  const REVISE = 'revise_quotation'; // literal on purpose: SALES_ACTION.REVISE_QUOTATION is undefined until implemented
  const target = { quotationId: 90, number: 'QT-2026-0090-1' };
  const issuedPr = (overrides = {}) => ({ id: 5, ticketId: 1, status: 'QUOTATION_ISSUED', orderConfirmedAt: null, ...overrides });
  const approvedDirect = { id: 90, number: 'QT-2026-0090-1', docStatus: 'APPROVED', recipientType: 'DESIGNER' };

  it('exposes the new action key', () => {
    expect(SALES_ACTION.REVISE_QUOTATION).toBe('revise_quotation');
  });

  it('APPROVED direct quotation on a deal past draft -> REVISE_QUOTATION instead of the old CREATE_PCR fall-through', () => {
    const deal = baseDeal({ status: 'quotation_issued', liveDirectQuotation: approvedDirect });
    expect(nextSalesAction(deal, [], { reviseTarget: target })).toEqual({
      key: REVISE, label: 'แก้ใบเสนอราคา', quotationId: 90, quotationNumber: 'QT-2026-0090-1',
    });
  });

  it('cascade order unchanged: an APPROVED direct quotation on a still-draft deal keeps CONFIRM_ORDER_DIRECT', () => {
    const deal = baseDeal({ status: 'draft', liveDirectQuotation: approvedDirect });
    expect(nextSalesAction(deal, [], { reviseTarget: target })).toMatchObject({ key: SALES_ACTION.CONFIRM_ORDER_DIRECT });
  });

  it.each(['QUOTE_OWNER', 'QUOTE_BUYER'])(
    'an issued PR-origin quotation at %s keeps RECORD_QUOTATION_OUTCOME as the primary even with a reviseTarget (revise is a SECONDARY action — see workState secondaryWorkActions)',
    (salesStage) => {
      const deal = baseDeal({ salesStage });
      expect(nextSalesAction(deal, [issuedPr()], { reviseTarget: target })).toMatchObject({ key: SALES_ACTION.RECORD_QUOTATION_OUTCOME });
    },
  );

  it('wrong-way-round: the same issued quotation at the DESIGNER stage still gets RECORD_QUOTATION_OUTCOME', () => {
    const deal = baseDeal({ salesStage: 'QUOTE_DESIGN_SIDE' });
    expect(nextSalesAction(deal, [issuedPr()], { reviseTarget: target })).toMatchObject({ key: SALES_ACTION.RECORD_QUOTATION_OUTCOME });
  });

  it('wrong-way-round: NO reviseTarget (non-owner / other role / predicate refused) -> cascade exactly as before', () => {
    const deal = baseDeal({ salesStage: 'QUOTE_OWNER' });
    expect(nextSalesAction(deal, [issuedPr()])).toMatchObject({ key: SALES_ACTION.RECORD_QUOTATION_OUTCOME });
    expect(nextSalesAction(deal, [issuedPr()], { reviseTarget: null })).toMatchObject({ key: SALES_ACTION.RECORD_QUOTATION_OUTCOME });
    const past = baseDeal({ status: 'quotation_issued', liveDirectQuotation: approvedDirect });
    expect(nextSalesAction(past, [], {})?.key).not.toBe(REVISE);
  });

  it('an accepted PR (CONFIRM_ORDER) still wins at the owner stage', () => {
    const deal = baseDeal({ salesStage: 'QUOTE_OWNER' });
    const prs = [issuedPr(), { id: 6, ticketId: 1, status: 'QUOTATION_ACCEPTED', orderConfirmedAt: null }];
    expect(nextSalesAction(deal, prs, { reviseTarget: target })).toMatchObject({ key: SALES_ACTION.CONFIRM_ORDER });
  });

  it('APPROVED-direct-past-draft with an open revision draft -> label "ไปที่ฉบับแก้ไข", pointing at the draft (never mints a second one)', () => {
    const deal = baseDeal({ status: 'quotation_issued', liveDirectQuotation: approvedDirect });
    expect(nextSalesAction(deal, [], { reviseTarget: { ...target, openDraftId: 91 } })).toMatchObject({
      key: REVISE, label: 'ไปที่ฉบับแก้ไข', quotationId: 90, to: '/quotations/91',
    });
  });

  it('a non-ACTIVE deal gets nothing even with a reviseTarget', () => {
    expect(nextSalesAction(baseDeal({ lifecycle: 'ON_HOLD', salesStage: 'QUOTE_OWNER' }), [issuedPr()], { reviseTarget: target })).toBeNull();
  });

  it('sortWorklist gives REVISE_QUOTATION a defined rank (no NaN ordering): ahead of FOLLOW_UP, behind CONFIRM_ORDER', () => {
    const at = '2026-07-01T00:00:00.000Z';
    const row = (id, key) => ({ deal: { id, stageUpdatedAt: at }, action: { key } });
    const sorted = sortWorklist([
      row(1, SALES_ACTION.FOLLOW_UP), row(2, REVISE), row(3, SALES_ACTION.CONFIRM_ORDER),
    ]).map((item) => item.deal.id);
    expect(sorted).toEqual([3, 2, 1]);
  });
});

// Owner ruling 2026-10-15: ขั้น 1 → 2 → 3 are moved by hand, and the price request starts at ขั้น 3.
// On LEAD_APPROACH and PRESENTATION the main action is always เลื่อนขั้น — a due follow-up, a stale
// deal or an older price request does not take it over. Only a live direct quotation (bucket 0)
// still comes first. From SPEC_APPROVED onwards nothing changes.
describe('ขั้น 1–2: the main action is always เลื่อนขั้น', () => {
  it.each(['LEAD_APPROACH', 'PRESENTATION'])('%s with no price request -> ADVANCE_STAGE', (salesStage) => {
    expect(nextSalesAction(baseDeal({ salesStage }), [])).toEqual({ key: SALES_ACTION.ADVANCE_STAGE, label: 'เลื่อนขั้น' });
  });

  it.each([
    ['overdue', '2020-01-01'],
    ['due today', bangkokTodayIso()],
  ])('a follow-up %s does not take over: still ADVANCE_STAGE', (_label, nextFollowUpAt) => {
    expect(nextSalesAction(baseDeal({ salesStage: 'LEAD_APPROACH', nextFollowUpAt }), [])).toMatchObject({ key: SALES_ACTION.ADVANCE_STAGE });
  });

  it('a stale deal does not take over: still ADVANCE_STAGE', () => {
    expect(nextSalesAction(baseDeal({ salesStage: 'PRESENTATION', stale: true }), [])).toMatchObject({ key: SALES_ACTION.ADVANCE_STAGE });
  });

  it.each(['DRAFT', 'SUBMITTED', 'APPROVED_FOR_QUOTATION'])('an older price request (%s) at ขั้น 2 does not take over: still ADVANCE_STAGE', (status) => {
    const prs = [{ id: 1, ticketId: 1, status }];
    expect(nextSalesAction(baseDeal({ salesStage: 'PRESENTATION' }), prs)).toMatchObject({ key: SALES_ACTION.ADVANCE_STAGE });
  });

  it.each(['SPEC_APPROVED', 'QUOTE_DESIGN_SIDE', 'QUOTE_OWNER', 'OWNER_SIGNOFF', 'AWAITING_BUYER', 'QUOTE_BUYER', 'NEGOTIATION'])(
    'boundary: %s with no price request -> CREATE_PCR, as before',
    (salesStage) => {
      expect(nextSalesAction(baseDeal({ salesStage }), [])).toEqual({ key: SALES_ACTION.CREATE_PCR, label: 'สร้างคำขอราคา' });
    },
  );

  it('a deal whose stage is missing keeps CREATE_PCR (an unknown stage must never strand the rep)', () => {
    expect(nextSalesAction(baseDeal({ salesStage: undefined }), [])).toMatchObject({ key: SALES_ACTION.CREATE_PCR });
  });

  it('a live direct quotation at ขั้น 1 still comes first (bucket 0)', () => {
    const deal = baseDeal({ salesStage: 'LEAD_APPROACH', liveDirectQuotation: { id: 77, number: 'QT-2026-0077-1', docStatus: 'DRAFT' } });
    expect(nextSalesAction(deal, [])).toMatchObject({ key: SALES_ACTION.SUBMIT_DIRECT_QUOTATION });
  });

  it('a non-ACTIVE deal at ขั้น 1 gets nothing', () => {
    expect(nextSalesAction(baseDeal({ salesStage: 'LEAD_APPROACH', lifecycle: 'ON_HOLD' }), [])).toBeNull();
  });

  it('sortWorklist gives ADVANCE_STAGE a defined rank (no NaN ordering): ahead of FOLLOW_UP', () => {
    const at = '2026-07-01T00:00:00.000Z';
    const sorted = sortWorklist([
      { deal: { id: 1, stageUpdatedAt: at }, action: { key: SALES_ACTION.FOLLOW_UP, followUp: 'today' } },
      { deal: { id: 2, stageUpdatedAt: at }, action: { key: SALES_ACTION.ADVANCE_STAGE } },
    ]);
    expect(sorted.map((row) => row.deal.id)).toEqual([2, 1]);
  });
});

// Owner ruling 2026-10-27 (PR #1168 made safe to ship): the rep picks the next party when the
// designer's or owner's quotation is SENT, not after an acceptance. An acceptance is the order:
// ยืนยันคำสั่งซื้อ, exactly as on main, whoever accepted (the server refuses a second acceptance on a
// deal, R8, so a route that waited for one could never finish).
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

  it('exposes the action key', () => {
    expect(SALES_ACTION.QUOTE_NEXT_PARTY).toBe(QUOTE_NEXT);
  });

  it.each(['ISSUED', 'SUPERSEDED', 'REJECTED', 'EXPIRED'])(
    'a designer quotation that went out and is not awaiting a revision (%s) -> "เสนอราคาเจ้าของโครงการ" listing owner then buyer',
    (docStatus) => {
      expect(nextSalesAction(deal(), [designerSent], known([1, docStatus]))).toMatchObject({
        key: QUOTE_NEXT, label: 'เสนอราคาเจ้าของโครงการ', parties: ['OWNER', 'BUYER'],
      });
    },
  );

  // Ruling 2026-10-27 (redo): a quotation being redone is written again for the same party — a
  // fresh one from the request after a change request, or the redo draft already open. (One
  // awaiting approval is a waiting line instead — see workState.test.js.)
  it.each([
    ['REVISION_REQUESTED', {}],
    ['DRAFT', { to: '/quotations/9101' }],
  ])('a designer quotation being redone (newest row %s) -> "เสนอใบราคาผู้ออกแบบ", not the route choice', (docStatus, extra) => {
    expect(nextSalesAction(deal(), [designerSent], known([1, docStatus]))).toEqual({
      key: SALES_ACTION.ISSUE_QUOTATION, label: 'เสนอใบราคาผู้ออกแบบ', pricingRequestId: 1, ...extra,
    });
  });

  // Ruling 2026-10-27 (a): no row for the request means its status alone decides, as on main.
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

  it('the party furthest along the route anchors the choice: the owner\'s quotation went out after the designer\'s -> "เสนอราคาผู้ซื้อ", the buyer only', () => {
    const prs = [designerSent, request(2, 'OWNER', 'QUOTATION_ISSUED')];
    expect(nextSalesAction(deal(), prs, known([1, 'SUPERSEDED'], [2, 'ISSUED']))).toMatchObject({
      key: QUOTE_NEXT, label: 'เสนอราคาผู้ซื้อ', parties: ['BUYER'],
    });
  });

  it.each(['CANCELLED', 'SUPERSEDED'])('a later party\'s dead request (%s) does not hold the route choice back', (status) => {
    const prs = [designerSent, request(2, 'OWNER', status)];
    expect(nextSalesAction(deal(), prs, known([1, 'ISSUED']))).toMatchObject({ key: QUOTE_NEXT, parties: ['OWNER', 'BUYER'] });
  });

  it('another deal\'s request does not hold it back (only this deal\'s requests count)', () => {
    const prs = [designerSent, { ...request(2, 'OWNER', 'SUBMITTED'), ticketId: 999 }];
    expect(nextSalesAction(deal(), prs, known([1, 'ISSUED']))).toMatchObject({ key: QUOTE_NEXT, parties: ['OWNER', 'BUYER'] });
  });

  it('a party off this deal\'s route is skipped: the owner off the route -> "เสนอราคาผู้ซื้อ", the buyer only', () => {
    const action = nextSalesAction(deal(), [designerSent], {
      offRouteParties: new Set(['OWNER']), quotationByRequest: quotations([1, 'ISSUED']),
    });
    expect(action).toMatchObject({ key: QUOTE_NEXT, label: 'เสนอราคาผู้ซื้อ', parties: ['BUYER'] });
  });

  // Ruling 2026-10-27 (b): a designer/owner outcome is never prompted, route known or not.
  it('the route has to be known: without offRouteParties a sent designer quotation gets nothing (no route choice, no outcome prompt); an empty set means every party is on it', () => {
    const quotationByRequest = quotations([1, 'ISSUED']);
    expect(nextSalesAction(deal(), [designerSent], { quotationByRequest })).toBeNull();
    expect(nextSalesAction(deal(), [designerSent], { quotationByRequest, offRouteParties: new Set() }))
      .toMatchObject({ key: QUOTE_NEXT, parties: ['OWNER', 'BUYER'] });
  });

  // Ruling 2026-10-27 (e): with two later drafts, the first in route order (not list order).
  it('a later party\'s request still a private DRAFT -> "ส่งคำขอราคาถึงเจ้าของโครงการ" (the first draft in route order), ranked ahead of a follow-up in the worklist', () => {
    const prs = [designerSent, request(3, 'BUYER', 'DRAFT'), request(2, 'OWNER', 'DRAFT')];
    const action = nextSalesAction(deal(), prs, known([1, 'ISSUED']));
    expect(action).toMatchObject({ label: 'ส่งคำขอราคาถึงเจ้าของโครงการ' });
    const at = '2026-07-01T00:00:00.000Z';
    const sorted = sortWorklist([
      { deal: { id: 9, stageUpdatedAt: at }, action: { key: SALES_ACTION.FOLLOW_UP, followUp: 'today' } },
      { deal: { id: 1, stageUpdatedAt: at }, action },
    ]);
    expect(sorted.map((row) => row.deal.id)).toEqual([1, 9]);
  });

  // A designer/owner who said no is never quoted again: the route moved on (ruling 2026-10-27).
  it.each([
    ['SUBMITTED', 'ISSUED'],
    ['CEO_REVIEWING', 'REJECTED'],
  ])('a later party\'s request with import/CEO (%s) -> nothing for the rep: no route choice, outcome prompt or redo for the designer\'s %s quotation', (status, designerDoc) => {
    expect(nextSalesAction(deal(), [designerSent, request(2, 'OWNER', status)], known([1, designerDoc]))).toBeNull();
  });

  // Ruling 2026-10-27 (redo): the buyer said no, let it expire or asked for changes -> a fresh
  // quotation from the same request (DealQuotationService#createFromPricingRequest's re-quote).
  it.each(['REJECTED', 'EXPIRED', 'REVISION_REQUESTED'])(
    'a buyer quotation %s -> "เสนอใบราคาผู้ซื้อ" writes a fresh one from the same request, not บันทึกผลใบเสนอราคา',
    (docStatus) => {
      expect(nextSalesAction(deal(), [request(1, 'BUYER', 'QUOTATION_ISSUED')], known([1, docStatus]))).toEqual({
        key: SALES_ACTION.ISSUE_QUOTATION, label: 'เสนอใบราคาผู้ซื้อ', pricingRequestId: 1,
      });
    },
  );

  it('a buyer quotation superseded by a newer one on the deal prompts nothing (the server refuses that re-quote)', () => {
    expect(nextSalesAction(deal(), [request(1, 'BUYER', 'QUOTATION_ISSUED')], known([1, 'SUPERSEDED']))).toBeNull();
  });

  // The owner's went out after the designer's: the owner being redone holds the route choice back
  // and is redone itself.
  it.each([
    ['REVISION_REQUESTED', {}],
    ['DRAFT', { to: '/quotations/9102' }],
  ])('the owner\'s quotation being redone (%s) after the designer\'s went out -> "เสนอใบราคาเจ้าของโครงการ"', (docStatus, extra) => {
    const prs = [designerSent, request(2, 'OWNER', 'QUOTATION_ISSUED')];
    expect(nextSalesAction(deal(), prs, known([1, 'SUPERSEDED'], [2, docStatus]))).toEqual({
      key: SALES_ACTION.ISSUE_QUOTATION, label: 'เสนอใบราคาเจ้าของโครงการ', pricingRequestId: 2, ...extra,
    });
  });

  it('an earlier party\'s open redo draft never takes over the buyer\'s quotation: a live one still asks for its outcome, a rejected one is redone for the buyer', () => {
    const prs = [designerSent, request(3, 'BUYER', 'QUOTATION_ISSUED')];
    expect(nextSalesAction(deal(), prs, known([1, 'DRAFT'], [3, 'ISSUED'])))
      .toMatchObject({ key: SALES_ACTION.RECORD_QUOTATION_OUTCOME, pricingRequestId: 3 });
    expect(nextSalesAction(deal(), prs, known([1, 'DRAFT'], [3, 'REJECTED']))).toEqual({
      key: SALES_ACTION.ISSUE_QUOTATION, label: 'เสนอใบราคาผู้ซื้อ', pricingRequestId: 3,
    });
  });

  it.each(['DESIGNER', 'OWNER', 'BUYER', null, 'UNSPECIFIED'])(
    'an accepted, unconfirmed %s request -> ยืนยันคำสั่งซื้อ, as on main (an acceptance is the order, whoever accepted)',
    (recipientType) => {
      expect(nextSalesAction(deal(), [request(1, recipientType, 'QUOTATION_ACCEPTED')], known([1, 'ACCEPTED']))).toEqual({
        key: SALES_ACTION.CONFIRM_ORDER, label: 'ยืนยันคำสั่งซื้อ',
      });
    },
  );

  it('an accepted designer request -> ยืนยันคำสั่งซื้อ even while a later party\'s request is in flight (nothing holds the confirm back)', () => {
    const prs = [request(1, 'DESIGNER', 'QUOTATION_ACCEPTED'), request(2, 'OWNER', 'SUBMITTED')];
    expect(nextSalesAction(deal(), prs, known([1, 'ACCEPTED']))).toMatchObject({ key: SALES_ACTION.CONFIRM_ORDER });
  });

  it('an accepted designer request on a ticket the order bridge cannot advance (\'submitted\') -> neither ยืนยันคำสั่งซื้อ nor a route choice', () => {
    const action = nextSalesAction(deal({ status: 'submitted' }), [request(1, 'DESIGNER', 'QUOTATION_ACCEPTED')], known([1, 'ACCEPTED']));
    expect([QUOTE_NEXT, SALES_ACTION.CONFIRM_ORDER]).not.toContain(action?.key);
  });

  it('sortWorklist ranks QUOTE_NEXT_PARTY with CONFIRM_ORDER (1): ahead of ISSUE_QUOTATION, ties broken by the older stageUpdatedAt', () => {
    const row = (id, key, stageUpdatedAt) => ({ deal: { id, stageUpdatedAt }, action: { key } });
    const sorted = sortWorklist([
      row(1, SALES_ACTION.ISSUE_QUOTATION, '2026-06-01T00:00:00.000Z'),
      row(2, SALES_ACTION.FOLLOW_UP, '2026-05-01T00:00:00.000Z'),
      row(3, SALES_ACTION.CONFIRM_ORDER, '2026-07-10T00:00:00.000Z'),
      row(4, QUOTE_NEXT, '2026-07-01T00:00:00.000Z'),
    ]).map((item) => item.deal.id);
    expect(sorted).toEqual([4, 3, 1, 2]);
  });
});
