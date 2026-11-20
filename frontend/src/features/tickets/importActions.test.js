import { describe, expect, it } from 'vitest';
import { nextFulfilmentActionCode, nextImportAction } from './importActions.js';

// Single source of truth for "what does Import need to do next on a deal" — shared by DealFulfilmentPanel's
// `can.*` gates, ImportOverview's worklist, and ImportFulfilmentPage (งานนำเข้า, which selects its rows with
// it).
describe('nextFulfilmentActionCode', () => {
  // W3-01 (owner ruling 2026-10-29): there is no deal-level "issue the IR" step any more — a deal with
  // no fulfilment status yields no code at all, whatever its status or deposit.
  it('never offers issueImportRequest on a deal with no fulfilment status, deposit or not', () => {
    for (const paymentStatus of ['DEPOSIT_NOTICE_ISSUED', 'DEPOSIT_PAID', null]) {
      expect(nextFulfilmentActionCode({ status: 'draft', fulfillmentStatus: null, paymentStatus })).toBeNull();
      expect(nextFulfilmentActionCode({ status: 'quotation_issued', fulfillmentStatus: null, paymentStatus })).toBeNull();
    }
  });
  // The backend's markIrSent/markShipping/markGoodsReceived gate on fulfillmentStatus alone (never on
  // ticket.status), and the redesigned pricing chain does not always reach quotation_issued — a deal whose IR is
  // already out must still be advanceable (the buttons used to vanish).
  it('walks the linear fulfilment chain in order', () => {
    expect(nextFulfilmentActionCode({ status: 'quotation_issued', fulfillmentStatus: null })).toBeNull();
    expect(nextFulfilmentActionCode({ status: 'quotation_issued', fulfillmentStatus: 'IR_ISSUED' })).toBe('markIrSent');
    expect(nextFulfilmentActionCode({ status: 'quotation_issued', fulfillmentStatus: 'IR_SENT' })).toBe('markShipping');
    expect(nextFulfilmentActionCode({ status: 'quotation_issued', fulfillmentStatus: 'SHIPPING' })).toBe('markGoodsReceived');
  });
});

describe('nextImportAction', () => {
  it('prioritizes an unpicked (SUBMITTED) pricing request over any fulfilment-chain action', () => {
    const ticket = { id: 5, status: 'quotation_issued', fulfillmentStatus: 'IR_ISSUED' };
    const action = nextImportAction(ticket, [{ id: 33, status: 'SUBMITTED' }]);
    expect(action).toEqual({ code: 'pickupPricingRequest', label: 'รับงาน · ขอราคา', to: '/pricing-requests/33' });
  });

  // Owner ruling 2026-08-17: stages 13-14 (ส่งมอบสินค้า) are Sales's now. Import keeps the write CAPABILITY
  // (additive, #818) — DealFulfilmentPanel's own hasAction gate is untouched — it just stops being asked.
  it('returns null for every delivery-ready fulfillmentStatus — Import is no longer prompted to record delivery', () => {
    expect(nextImportAction({ id: 9, status: 'quotation_issued', fulfillmentStatus: 'GOODS_RECEIVED' })).toBeNull();
    expect(nextImportAction({ id: 9, status: 'quotation_issued', fulfillmentStatus: 'FROM_STOCK' })).toBeNull();
    expect(nextImportAction({ id: 9, status: 'quotation_issued', fulfillmentStatus: 'PARTIALLY_DELIVERED' })).toBeNull();
  });
});
