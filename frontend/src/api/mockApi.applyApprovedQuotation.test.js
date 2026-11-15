import { describe, it, expect, beforeEach, vi } from 'vitest';

// Round-2 N2: the mock's approval fill must mirror TicketService#applyApprovedQuotation exactly — the stage advance
// (UNSPECIFIED advances like BUYER) and the party names (designer from the designer directory by the quotation's
// D.Co./unitCode for ANY recipient, buyer from the quotation's BUYER party, owner never, never overwrite). The real
// behaviour is proven by DealDirectApprovalFillsDealIntegrationTest against Postgres; this pins the mirror only.

let api;
let retireLiveDirectQuotationsForTests;
let retireLivePricingRequestsForTests;
let patchTicketForTests;
let patchDealQuotationForTests;
beforeEach(async () => {
  vi.resetModules();
  ({
    api, retireLiveDirectQuotationsForTests, retireLivePricingRequestsForTests, patchTicketForTests,
    patchDealQuotationForTests,
  } = await import('./mockApi.js'));
});

const ONE_ITEM = {
  model: 'Trilogy', color: 'Ash', texture: 'Matt', sizeText: '60x60', thicknessMm: 10, sqmPerPiece: 0.36,
  quantityMode: 'AREA', areaSqm: 20, wastageMode: 'NONE', wastageValue: 0, piecesPerBox: 3, unitPrice: 850, discountPct: 0,
  leadTimeMinDays: 30, leadTimeMaxDays: 45,
};
const DIRECTORY_NAME = 'DEVELOPMENT DESIGN STUDIO'; // mockDesigners D002

/** Ticket 18 (owned by the sales demo user), reset to a blank DESIGNER_LED deal at LEAD_APPROACH. */
function freshDeal(patch = {}) {
  retireLiveDirectQuotationsForTests(18);
  retireLivePricingRequestsForTests(18);
  patchTicketForTests(18, {
    salesStage: 'LEAD_APPROACH', lifecycle: 'ACTIVE', entryChannel: 'DESIGNER_LED',
    designerName: null, ownerName: null, buyerName: null, ...patch,
  });
}

async function approveOn18(recipientType, { unitCode = 'D002', storedRecipient } = {}) {
  await api.auth.login({ role: 'sales' });
  const { quotation } = await api.dealQuotations.create(18, { recipientType, unitCode, items: [ONE_ITEM] });
  if (storedRecipient) patchDealQuotationForTests(quotation.id, { recipientType: storedRecipient });
  await api.dealQuotations.submit(quotation.id);
  await api.auth.login({ role: 'sales_manager' });
  await api.dealQuotations.approve(quotation.id, {});
  await api.auth.login({ role: 'sales' });
  return (await api.tickets.get(18)).ticket;
}

const summaryOf = (ticket) => ticket.summary ?? ticket;

describe('mock applyApprovedQuotation mirrors TicketService#applyApprovedQuotation', () => {
  it('a DESIGNER quotation takes designerName from the directory entry, never the bill-to customer', async () => {
    freshDeal();
    const s = summaryOf(await approveOn18('DESIGNER'));
    expect(s.salesStage).toBe('QUOTE_DESIGN_SIDE');
    expect(s.designerName).toBe(DIRECTORY_NAME);
    expect(s.designerName).not.toBe(s.customerName);
    expect(s.ownerName ?? null).toBeNull();
  });

  it('an OWNER quotation still fills designerName from its D.Co. (N3) and never fills ownerName', async () => {
    freshDeal();
    const s = summaryOf(await approveOn18('OWNER'));
    expect(s.designerName).toBe(DIRECTORY_NAME);
    expect(s.ownerName ?? null).toBeNull();
    expect(s.buyerName ?? null).toBeNull();
  });

  it('a BUYER quotation takes buyerName from its BUYER party and designerName from its D.Co.', async () => {
    freshDeal();
    const s = summaryOf(await approveOn18('BUYER'));
    expect(s.salesStage).toBe('QUOTE_BUYER');
    expect(s.buyerName).toBe(s.customerName); // the default party is the deal's own customer, role BUYER
    expect(s.designerName).toBe(DIRECTORY_NAME);
    expect(s.ownerName ?? null).toBeNull();
  });

  it('an UNSPECIFIED quotation advances like BUYER — QUOTE_BUYER, not the designer stage', async () => {
    freshDeal();
    const s = summaryOf(await approveOn18('DESIGNER', { storedRecipient: 'UNSPECIFIED' }));
    expect(s.salesStage).toBe('QUOTE_BUYER');
  });

  it('an UNSPECIFIED quotation never regresses a deal already past QUOTE_BUYER', async () => {
    freshDeal();
    await api.auth.login({ role: 'sales' });
    const { quotation } = await api.dealQuotations.create(18, { recipientType: 'DESIGNER', items: [ONE_ITEM] });
    patchDealQuotationForTests(quotation.id, { recipientType: 'UNSPECIFIED' });
    await api.dealQuotations.submit(quotation.id);
    patchTicketForTests(18, { salesStage: 'NEGOTIATION' });
    await api.auth.login({ role: 'sales_manager' });
    await api.dealQuotations.approve(quotation.id, {});
    expect(summaryOf((await api.tickets.get(18)).ticket).salesStage).toBe('NEGOTIATION');
  });

  it('never overwrites a typed designerName, and skips a D.Co. not in the directory', async () => {
    freshDeal({ designerName: 'คุณบี' });
    expect(summaryOf(await approveOn18('DESIGNER')).designerName).toBe('คุณบี');

    freshDeal();
    expect(summaryOf(await approveOn18('DESIGNER', { unitCode: 'NOPE99' })).designerName ?? null).toBeNull();
  });
});
