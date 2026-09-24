import { describe, expect, it } from 'vitest';
import { isRemainingInvoiceReady, REMAINING_INVOICE_READY_FULFILMENT_STATUSES } from './remainingInvoiceReadiness.js';

describe('isRemainingInvoiceReady', () => {
  it('is ready once the goods are in hand on either path, and stays ready through delivery', () => {
    for (const fulfillmentStatus of ['GOODS_RECEIVED', 'FROM_STOCK', 'PARTIALLY_DELIVERED', 'FULLY_DELIVERED']) {
      expect(isRemainingInvoiceReady({ status: 'quotation_issued', fulfillmentStatus })).toBe(true);
    }
  });

  it('is NOT ready before fulfilment starts or while import goods are still on the way', () => {
    for (const fulfillmentStatus of [null, undefined, 'IR_ISSUED', 'IR_SENT', 'SHIPPING', 'SOMETHING_NEW']) {
      expect(isRemainingInvoiceReady({ status: 'quotation_issued', fulfillmentStatus })).toBe(false);
    }
  });

  it('is NOT ready off quotation_issued even when the goods are delivered', () => {
    for (const status of ['approved', 'price_proposed', 'document_issued', 'closed', 'cancelled', undefined]) {
      expect(isRemainingInvoiceReady({ status, fulfillmentStatus: 'FULLY_DELIVERED' })).toBe(false);
      expect(isRemainingInvoiceReady({ status, fulfillmentStatus: 'FROM_STOCK' })).toBe(false);
    }
    expect(isRemainingInvoiceReady(null)).toBe(false);
    expect(isRemainingInvoiceReady(undefined)).toBe(false);
  });

  it('covers every non-import-transit value FulfilmentStatus.java publishes', () => {
    // FulfilmentStatus.java's seven codes; the three import-transit ones must stay out.
    const all = ['IR_ISSUED', 'IR_SENT', 'SHIPPING', 'GOODS_RECEIVED', 'FROM_STOCK', 'PARTIALLY_DELIVERED', 'FULLY_DELIVERED'];
    const transit = ['IR_ISSUED', 'IR_SENT', 'SHIPPING'];
    expect([...REMAINING_INVOICE_READY_FULFILMENT_STATUSES].sort()).toEqual(all.filter((s) => !transit.includes(s)).sort());
  });
});
