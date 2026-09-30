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

describe('nextSalesAction — RECORD_QUOTATION_OUTCOME (bucket 4, deal-page CTA discoverability fix)', () => {
  it('returns RECORD_QUOTATION_OUTCOME for a live PR at QUOTATION_ISSUED (previously fell through to follow-up/activity)', () => {
    const prs = [{ id: 1, ticketId: 1, status: 'QUOTATION_ISSUED' }];
    expect(nextSalesAction(baseDeal(), prs)).toEqual({
      key: SALES_ACTION.RECORD_QUOTATION_OUTCOME,
      label: 'บันทึกผลใบเสนอราคา',
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
