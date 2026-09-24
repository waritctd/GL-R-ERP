import { describe, expect, it } from 'vitest';
import { nextFulfilmentActionCode, nextImportAction } from './importActions.js';

// Single source of truth for "what does Import need to do next on a deal" —
// shared by DealFulfilmentPanel's `can.*` gates, ImportOverview's worklist,
// and ImportFulfilmentPage (งานนำเข้า, which selects its rows with it). A drift
// here silently disagrees with the surfaces that perform the mutation (see
// importActions.js header). It named ProcurementFulfilmentPage until ebaf6888
// deleted that page.
describe('nextFulfilmentActionCode', () => {
  it('walks the linear fulfilment chain in order', () => {
    expect(nextFulfilmentActionCode({ status: 'quotation_issued', fulfillmentStatus: null })).toBe('issueImportRequest');
    expect(nextFulfilmentActionCode({ status: 'quotation_issued', fulfillmentStatus: 'IR_ISSUED' })).toBe('markIrSent');
    expect(nextFulfilmentActionCode({ status: 'quotation_issued', fulfillmentStatus: 'IR_SENT' })).toBe('markShipping');
    expect(nextFulfilmentActionCode({ status: 'quotation_issued', fulfillmentStatus: 'SHIPPING' })).toBe('markGoodsReceived');
  });

  it('returns recordDelivery for every delivery-ready fulfillmentStatus', () => {
    expect(nextFulfilmentActionCode({ status: 'quotation_issued', fulfillmentStatus: 'GOODS_RECEIVED' })).toBe('recordDelivery');
    expect(nextFulfilmentActionCode({ status: 'quotation_issued', fulfillmentStatus: 'FROM_STOCK' })).toBe('recordDelivery');
    expect(nextFulfilmentActionCode({ status: 'quotation_issued', fulfillmentStatus: 'PARTIALLY_DELIVERED' })).toBe('recordDelivery');
  });

  it('returns null before quotation and once delivery is complete', () => {
    expect(nextFulfilmentActionCode({ status: 'draft', fulfillmentStatus: null })).toBeNull();
    expect(nextFulfilmentActionCode({ status: 'quotation_issued', fulfillmentStatus: 'FULLY_DELIVERED' })).toBeNull();
    expect(nextFulfilmentActionCode({ status: 'closed', fulfillmentStatus: 'FULLY_DELIVERED' })).toBeNull();
  });
});

describe('nextImportAction', () => {
  it('prioritizes an unpicked (SUBMITTED) pricing request over any fulfilment-chain action', () => {
    const ticket = { id: 5, status: 'quotation_issued', fulfillmentStatus: 'IR_ISSUED' };
    const action = nextImportAction(ticket, [{ status: 'SUBMITTED' }]);
    expect(action).toEqual({ code: 'pickupPricingRequest', label: 'รับงาน · ขอราคา', to: '/pricing-requests' });
  });

  // PR-B REVIEW ROUND 1, S8: IR_ISSUED is the status every IR-TRACKED deal (V184 per-factory)
  // sits at for its whole tracking period — the old "ส่งคำขอนำเข้าแล้ว" label implied a single
  // legacy click /fulfilment no longer performs (the real control is a per-factory tracker), so
  // the label must be the neutral one regardless of what triggered nextImportAction to reach it.
  it('uses the neutral IR-tracking label for markIrSent, not the legacy "mark sent" wording', () => {
    const ticket = { id: 5, status: 'quotation_issued', fulfillmentStatus: 'IR_ISSUED' };
    expect(nextImportAction(ticket)).toEqual({ code: 'markIrSent', label: 'อัปเดตสถานะนำเข้า', to: '/fulfilment' });
  });

  it('ignores non-SUBMITTED pricing requests and falls through to the fulfilment chain', () => {
    const ticket = { id: 5, status: 'quotation_issued', fulfillmentStatus: null };
    const action = nextImportAction(ticket, [{ status: 'IMPORT_REVIEWING' }]);
    // PR-B REVIEW ROUND 2, X1: issueImportRequest now routes to the deal page — the rewritten
    // /fulfilment (per-factory tracker) can't act on it and doesn't even list a
    // null-fulfillmentStatus deal. See importActions.js's `to` table doc comment.
    expect(action).toEqual({ code: 'issueImportRequest', label: 'ออกคำขอนำเข้า', to: '/tickets/5' });
  });

  // PR-B REVIEW ROUND 2, X1: only markIrSent still routes to /fulfilment (its legacy section
  // lists a non-tracked IR_ISSUED deal with a link out). The rewritten per-factory
  // ImportFulfilmentPage cannot perform issueImportRequest/markShipping/markGoodsReceived at
  // all — those three now route to the deal page, where DealFulfilmentPanel still performs
  // them. This test used to assert all four landed on /fulfilment, back when that page
  // performed each as a single deal-level click.
  it('routes markIrSent to /fulfilment and the other three legacy codes to the deal page', () => {
    const cases = [
      [null, 'issueImportRequest', 'ออกคำขอนำเข้า', '/tickets/7'],
      ['IR_ISSUED', 'markIrSent', 'อัปเดตสถานะนำเข้า', '/fulfilment'],
      ['IR_SENT', 'markShipping', 'บันทึกออกเดินทาง', '/tickets/7'],
      ['SHIPPING', 'markGoodsReceived', 'ยืนยันรับเข้าคลัง', '/tickets/7'],
    ];
    cases.forEach(([fulfillmentStatus, code, label, to]) => {
      const ticket = { id: 7, status: 'quotation_issued', fulfillmentStatus };
      expect(nextImportAction(ticket)).toEqual({ code, label, to });
    });
  });

  // Owner ruling 2026-08-17: stages 13-14 (ส่งมอบสินค้า) are Sales's now. This test used to pin an
  // intermediate state — delivery out of งานนำเข้า's scope but still deep-linked from Import's own
  // worklist CTA (a '/tickets/:id' route) — superseded by this case: nextFulfilmentActionCode
  // (tested above) still IDENTIFIES a delivery-ready deal, but nextImportAction must no longer turn
  // that into a worklist PROMPT for Import at all. Import keeps the write CAPABILITY (additive,
  // #818) — DealFulfilmentPanel's own hasAction gate is untouched — it just stops being asked.
  // salesActions.js's own RECORD_DELIVERY bucket is what now surfaces this, reusing
  // nextFulfilmentActionCode directly rather than a second copy of the status list.
  it('returns null for every delivery-ready fulfillmentStatus — Import is no longer prompted to record delivery', () => {
    expect(nextImportAction({ id: 9, status: 'quotation_issued', fulfillmentStatus: 'GOODS_RECEIVED' })).toBeNull();
    expect(nextImportAction({ id: 9, status: 'quotation_issued', fulfillmentStatus: 'FROM_STOCK' })).toBeNull();
    expect(nextImportAction({ id: 9, status: 'quotation_issued', fulfillmentStatus: 'PARTIALLY_DELIVERED' })).toBeNull();
  });

  it('returns null when there is nothing for Import to do', () => {
    const ticket = { id: 9, status: 'draft', fulfillmentStatus: null };
    expect(nextImportAction(ticket, [])).toBeNull();
  });
});
