import { describe, expect, it } from 'vitest';
import { nextFulfilmentActionCode, nextImportAction } from './importActions.js';

// Single source of truth for "what does Import need to do next on a deal" —
// shared by DealFulfilmentPanel's `can.*` gates, ImportOverview's worklist,
// and ImportFulfilmentPage (งานนำเข้า, which selects its rows with it). A drift
// here silently disagrees with the surfaces that perform the mutation (see
// importActions.js header). It named ProcurementFulfilmentPage until ebaf6888
// deleted that page.
describe('nextFulfilmentActionCode', () => {
  // W3-01 (owner ruling 2026-10-29): there is no deal-level "issue the IR" step any more — a deal with
  // no fulfilment status yields no code at all, whatever its status or deposit.
  it('never offers issueImportRequest on a deal with no fulfilment status, deposit or not', () => {
    for (const paymentStatus of ['DEPOSIT_NOTICE_ISSUED', 'DEPOSIT_PAID', null]) {
      expect(nextFulfilmentActionCode({ status: 'draft', fulfillmentStatus: null, paymentStatus })).toBeNull();
      expect(nextFulfilmentActionCode({ status: 'quotation_issued', fulfillmentStatus: null, paymentStatus })).toBeNull();
    }
  });
  // The backend's markIrSent/markShipping/markGoodsReceived gate on fulfillmentStatus alone (never
  // on ticket.status), and the redesigned pricing chain does not always reach quotation_issued — a
  // deal whose IR is already out must still be advanceable (the buttons used to vanish).
  it.each([
    ['IR_ISSUED', 'markIrSent'],
    ['IR_SENT', 'markShipping'],
    ['SHIPPING', 'markGoodsReceived'],
  ])('offers the %s step (%s) whatever the ticket status — the IR is already out', (fulfillmentStatus, code) => {
    for (const status of ['draft', 'approved', 'quotation_issued', 'document_issued']) {
      expect(nextFulfilmentActionCode({ status, fulfillmentStatus })).toBe(code);
    }
  });
  it('walks the linear fulfilment chain in order', () => {
    expect(nextFulfilmentActionCode({ status: 'quotation_issued', fulfillmentStatus: null })).toBeNull();
    expect(nextFulfilmentActionCode({ status: 'quotation_issued', fulfillmentStatus: 'IR_ISSUED' })).toBe('markIrSent');
    expect(nextFulfilmentActionCode({ status: 'quotation_issued', fulfillmentStatus: 'IR_SENT' })).toBe('markShipping');
    expect(nextFulfilmentActionCode({ status: 'quotation_issued', fulfillmentStatus: 'SHIPPING' })).toBe('markGoodsReceived');
  });

  it('returns recordDelivery for every delivery-ready fulfillmentStatus', () => {
    expect(nextFulfilmentActionCode({ status: 'quotation_issued', fulfillmentStatus: 'GOODS_RECEIVED' })).toBe('recordDelivery');
    expect(nextFulfilmentActionCode({ status: 'quotation_issued', fulfillmentStatus: 'FROM_STOCK', paymentStatus: 'DEPOSIT_PAID' })).toBe('recordDelivery');
    expect(nextFulfilmentActionCode({ status: 'quotation_issued', fulfillmentStatus: 'PARTIALLY_DELIVERED' })).toBe('recordDelivery');
  });

  // Walk 4: stock goods leave only once the deposit is settled - paid, or a no-deposit policy at CUSTOMER_CONFIRMED.
  it('FROM_STOCK waits for the deposit: REQUIRED and unpaid -> null; paid, or a no-deposit policy -> recordDelivery', () => {
    const base = { status: 'quotation_issued', fulfillmentStatus: 'FROM_STOCK' };
    expect(nextFulfilmentActionCode({ ...base, paymentStatus: 'CUSTOMER_CONFIRMED', depositPolicy: 'REQUIRED' })).toBeNull();
    expect(nextFulfilmentActionCode({ ...base, paymentStatus: 'DEPOSIT_NOTICE_ISSUED', depositPolicy: 'REQUIRED' })).toBeNull();
    expect(nextFulfilmentActionCode({ ...base, paymentStatus: 'DEPOSIT_PAID', depositPolicy: 'REQUIRED' })).toBe('recordDelivery');
    expect(nextFulfilmentActionCode({ ...base, paymentStatus: 'CUSTOMER_CONFIRMED', depositPolicy: 'NOT_REQUIRED' })).toBe('recordDelivery');
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
    const action = nextImportAction(ticket, [{ id: 33, status: 'SUBMITTED' }]);
    expect(action).toEqual({ code: 'pickupPricingRequest', label: 'รับงาน · ขอราคา', to: '/pricing-requests/33' });
  });

  // BUG B: the queue's default import tab cannot show a SUBMITTED row, so รับเรื่อง landing on
  // /pricing-requests dead-ends. It must open the SUBMITTED request itself.
  it('sends pickupPricingRequest to the SUBMITTED request\'s own detail page, not the queue', () => {
    const ticket = { id: 5, status: 'quotation_issued', fulfillmentStatus: 'IR_ISSUED' };
    const action = nextImportAction(ticket, [
      { id: 40, status: 'IMPORT_REVIEWING' },
      { id: 41, status: 'SUBMITTED' },
    ]);
    expect(action.code).toBe('pickupPricingRequest');
    expect(action.to).toBe('/pricing-requests/41');
  });

  // Deterministic rule: with several SUBMITTED, the OLDEST (lowest id) is opened, regardless of
  // the order the list happens to arrive in.
  it('with two SUBMITTED requests opens the oldest (lowest id), independent of list order', () => {
    const ticket = { id: 5, status: 'draft', fulfillmentStatus: null };
    const action = nextImportAction(ticket, [
      { id: 9, status: 'SUBMITTED' },
      { id: 4, status: 'SUBMITTED' },
      { id: 2, status: 'IMPORT_REVIEWING' },
    ]);
    expect(action.to).toBe('/pricing-requests/4');
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
    // W3-01 (owner ruling 2026-10-29): a confirmed deal with no IR yet is NOT an import to-do — the
    // worklist lists what /fulfilment lists (an ISSUED IR onward); sales raises the per-factory IR.
    expect(action).toBeNull();
  });

  // PR-B REVIEW ROUND 2, X1: only markIrSent still routes to /fulfilment (its legacy section
  // lists a non-tracked IR_ISSUED deal with a link out). The rewritten per-factory
  // ImportFulfilmentPage cannot perform issueImportRequest/markShipping/markGoodsReceived at
  // all — those three now route to the deal page, where DealFulfilmentPanel still performs
  // them. This test used to assert all four landed on /fulfilment, back when that page
  // performed each as a single deal-level click.
  it('routes markIrSent to /fulfilment and the other three legacy codes to import\'s own deal page (never /tickets/:id, which import can no longer open)', () => {
    const cases = [
      ['IR_ISSUED', 'markIrSent', 'อัปเดตสถานะนำเข้า', '/fulfilment'],
      ['IR_SENT', 'markShipping', 'บันทึกออกเดินทาง', '/import/deals/7'],
      ['SHIPPING', 'markGoodsReceived', 'ยืนยันรับเข้าคลัง', '/import/deals/7'],
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

describe('nextImportAction — W3-01 no IR yet', () => {
  it.each([
    [{ id: 3, status: 'quotation_issued', fulfillmentStatus: null }],
    [{ id: 3, status: 'draft', fulfillmentStatus: null, paymentStatus: 'DEPOSIT_PAID' }],
  ])('returns null (no ออกคำขอนำเข้า CTA) for %j', (ticket) => {
    expect(nextImportAction(ticket, [])).toBeNull();
  });
});
