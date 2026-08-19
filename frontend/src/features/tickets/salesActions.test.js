import { describe, expect, it } from 'vitest';
import { SALES_ACTION, nextSalesAction, sortWorklist } from './salesActions.js';

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

// Baseline pass over the rest of the cascade — establishes that inserting bucket 4 did not disturb
// buckets 1-3/5-6, each exercised through a deal/PR shape that clears every earlier bucket without
// matching it.
describe('nextSalesAction — full cascade baseline (buckets 1-3, 5-6)', () => {
  it('CREATE_PCR — no live pricing request, no price evidence', () => {
    expect(nextSalesAction(baseDeal(), [])).toMatchObject({ key: SALES_ACTION.CREATE_PCR });
  });

  it('ISSUE_QUOTATION — a live PR at APPROVED_FOR_QUOTATION', () => {
    const prs = [{ id: 1, ticketId: 1, status: 'APPROVED_FOR_QUOTATION' }];
    expect(nextSalesAction(baseDeal(), prs)).toMatchObject({ key: SALES_ACTION.ISSUE_QUOTATION });
  });

  it('CONFIRM_ORDER — QUOTATION_ACCEPTED but not yet order-confirmed', () => {
    const prs = [{ id: 1, ticketId: 1, status: 'QUOTATION_ACCEPTED', orderConfirmedAt: null }];
    expect(nextSalesAction(baseDeal(), prs)).toMatchObject({ key: SALES_ACTION.CONFIRM_ORDER });
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

describe('sortWorklist — RECORD_DELIVERY rank (4) relative to CONFIRM_ORDER (1) and FOLLOW_UP (5)', () => {
  it('places a delivery row after a confirm-order row but before a non-overdue follow-up row', () => {
    const items = [
      { deal: { id: 3, stageUpdatedAt: '2026-07-01T00:00:00.000Z' }, action: { key: SALES_ACTION.FOLLOW_UP, label: 'x', followUp: 'today' } },
      { deal: { id: 1, stageUpdatedAt: '2026-07-01T00:00:00.000Z' }, action: { key: SALES_ACTION.RECORD_DELIVERY, label: 'x' } },
      { deal: { id: 2, stageUpdatedAt: '2026-07-01T00:00:00.000Z' }, action: { key: SALES_ACTION.CONFIRM_ORDER, label: 'x' } },
    ];
    expect(sortWorklist(items).map((item) => item.deal.id)).toEqual([2, 1, 3]);
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
