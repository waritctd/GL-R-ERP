import { describe, expect, it } from 'vitest';
import { nextFulfilmentActionCode, nextImportAction } from './importActions.js';

// Single source of truth for "what does Import need to do next on a deal" —
// shared by DealFulfilmentPanel's `can.*` gates, ImportOverview's worklist,
// and ProcurementFulfilmentPage. A drift here silently disagrees with the
// panel that actually performs the mutation (see importActions.js header).
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
    expect(action).toEqual({ code: 'issueImportRequest', label: 'ออก IR', to: '/tickets/5' });
  });

  it('deep-links fulfilment-chain actions to the ticket detail page', () => {
    const ticket = { id: 7, status: 'quotation_issued', fulfillmentStatus: 'SHIPPING' };
    expect(nextImportAction(ticket)).toEqual({ code: 'markGoodsReceived', label: 'ยืนยันรับเข้าคลัง', to: '/tickets/7' });
  });

  it('returns null when there is nothing for Import to do', () => {
    const ticket = { id: 9, status: 'draft', fulfillmentStatus: null };
    expect(nextImportAction(ticket, [])).toBeNull();
  });
});
