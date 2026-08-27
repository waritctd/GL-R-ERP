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

  it('ignores non-SUBMITTED pricing requests and falls through to the fulfilment chain', () => {
    const ticket = { id: 5, status: 'quotation_issued', fulfillmentStatus: null };
    const action = nextImportAction(ticket, [{ status: 'IMPORT_REVIEWING' }]);
    expect(action).toEqual({ code: 'issueImportRequest', label: 'ออกคำขอนำเข้า', to: '/fulfilment' });
  });

  // Every CTA must land on a page that can PERFORM the action. All four import
  // steps are performed in place on /fulfilment, so none of them may deep-link to
  // a deal page any more — that was the round trip งานนำเข้า exists to remove.
  it('points all four import-chain actions at the /fulfilment workspace', () => {
    const cases = [
      [null, 'issueImportRequest', 'ออกคำขอนำเข้า'],
      ['IR_ISSUED', 'markIrSent', 'ส่งคำขอนำเข้าแล้ว'],
      ['IR_SENT', 'markShipping', 'บันทึกออกเดินทาง'],
      ['SHIPPING', 'markGoodsReceived', 'ยืนยันรับเข้าคลัง'],
    ];
    cases.forEach(([fulfillmentStatus, code, label]) => {
      const ticket = { id: 7, status: 'quotation_issued', fulfillmentStatus };
      expect(nextImportAction(ticket)).toEqual({ code, label, to: '/fulfilment' });
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
