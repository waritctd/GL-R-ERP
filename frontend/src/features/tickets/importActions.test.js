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

  // Wrong-way-round: delivery is OUT of งานนำเข้า's scope (moving to Sales), so
  // its CTA must keep the deal deep link. Sending it to /fulfilment would land the
  // user on a page with no delivery control and no row for their deal.
  it('keeps recordDelivery on the deal page, never the fulfilment workspace', () => {
    const ticket = { id: 9, status: 'quotation_issued', fulfillmentStatus: 'GOODS_RECEIVED' };
    expect(nextImportAction(ticket)).toEqual({ code: 'recordDelivery', label: 'บันทึกส่งมอบ', to: '/tickets/9' });
    expect(nextImportAction({ id: 9, status: 'quotation_issued', fulfillmentStatus: 'FROM_STOCK' }).to).toBe('/tickets/9');
    expect(nextImportAction({ id: 9, status: 'quotation_issued', fulfillmentStatus: 'PARTIALLY_DELIVERED' }).to).toBe('/tickets/9');
  });

  it('returns null when there is nothing for Import to do', () => {
    const ticket = { id: 9, status: 'draft', fulfillmentStatus: null };
    expect(nextImportAction(ticket, [])).toBeNull();
  });
});
