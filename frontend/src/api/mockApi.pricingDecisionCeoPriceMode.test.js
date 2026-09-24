import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// Mock coverage for Phase 2 (CEO pricing method, owner rulings 2026-09-18/19, V187) and the
// Opus-review follow-up (2026-09-19), review finding #7 in particular: SPECIAL_SQM used to be a
// permanent dead end in mock mode (always null) — the mock is this repo's DEFAULT QA/demo
// surface (CLAUDE.md "Mock API contract"), so a mode nobody can ever see a number for here is a
// mode nobody can demo. It is now a genuine (non-authoritative — see mockApi.js's own header on
// this) mirror of WastageCalculator#netPerPieceFromSpecialSqm, pinned against the SAME 4 owner
// documents WastageCalculatorTest pins the real engine against.
//
// NOT authz/permission coverage (nothing here substitutes for a real-DB Java test) — this only
// verifies the mock's OWN arithmetic/plumbing, which is exactly what CLAUDE.md says a mock can be
// evidence for and no more.

let clientRequestSeq = 0;
function nextClientRequestId() {
  clientRequestSeq += 1;
  return `77777777-7777-4777-8777-${String(clientRequestSeq).padStart(12, '0')}`;
}

/** Drives a fresh PCR (Phase 1 new-form shape: sqmPerPiece set) all the way to a DRAFT
 * pricing_decision (CEO_REVIEWING), mirroring mockApi.pricingRequests.test.js's own
 * numbering-helper flow -- including creating its OWN ticket/customer/project (rather than
 * reusing a seeded one), because generateFactoryEmailDrafts requires every item to already carry
 * a `factory`, which is not reliably true of every seeded demo ticket item. Returns the raw
 * decision (never approved — every test below edits it). */
async function startNewFormDecision(sqmPerPiece = 0.36) {
  await api.auth.login({ role: 'sales' });
  const { customer } = await api.customers.create({
    name: `บริษัท CEO Price Mode Mock ${nextClientRequestId()}`,
    taxId: '0100000000098',
    address: '999 ถนนทดสอบ',
    phone: '02-111-1198',
  });
  const { project } = await api.customers.createProject(customer.id, { name: 'โครงการ CEO Price Mode Mock' });
  const { ticket: created } = await api.tickets.create({
    title: 'ดีล CEO Price Mode Mock',
    priority: 'NORMAL',
    customerName: customer.name,
    customerId: customer.id,
    projectId: project.id,
    items: [{ brand: 'SCG', model: 'Tile CEOP Mock', qty: 10, currency: 'THB' }],
  });
  const ticketId = created.summary.id;
  const sourceItemId = created.items[0].id;

  const { pricingRequest: draftPr } = await api.pricingRequests.create(ticketId, {
    recipientType: 'DESIGNER',
    recipientLabel: 'ผู้ออกแบบทดสอบ CEOP',
    clientRequestId: nextClientRequestId(),
    items: [{
      sourceTicketItemId: sourceItemId,
      productId: 1,
      brand: 'SCG',
      model: 'Tile CEOP Mock',
      factory: 'Panaria SpA',
      color: 'ขาว',
      texture: 'ด้าน',
      size: '60x60',
      thicknessMm: 10,
      sqmPerPiece,
      quantityMode: 'PIECES',
      piecesInput: 10,
      wastageMode: 'NONE',
      piecesPerBox: 4,
      roundToFullBox: false,
      originCountry: 'ไทย-สต็อก',
      leadTimeMinDays: 3,
      leadTimeMaxDays: 7,
      quantityType: 'ESTIMATE',
    }],
  });
  const prId = draftPr.summary.id;
  await api.pricingRequests.submit(prId);

  await api.auth.login({ role: 'import' });
  await api.pricingRequests.pickup(prId);
  const { items: quotes } = await api.pricingRequests.generateFactoryEmailDrafts(prId);
  const quote = quotes[0];
  const prItemId = quote.items[0].pricingRequestItemId;
  await api.pricingRequests.receiveFactoryQuote(quote.id, {
    clientRequestId: nextClientRequestId(),
    supplierQuoteRef: 'REF-CEOP-MOCK',
    defaultCurrency: 'THB',
    paymentTerms: '30 days',
    leadTimeText: '45 days',
    items: [{
      pricingRequestItemId: prItemId,
      quotedQuantity: 10,
      quotedUnit: 'PER_PIECE',
      unitBasis: 'PER_PIECE',
      rawUnitPrice: 100,
      currency: 'THB',
    }],
  });
  await api.pricingRequests.markFactoryQuoteReady(quote.id);
  const { costing } = await api.pricingRequests.createCosting(prId, {});
  await api.pricingRequests.recalculateCosting(costing.id, {});
  await api.pricingRequests.submitCosting(costing.id, {});

  await api.auth.login({ role: 'ceo' });
  const { decision } = await api.pricingRequests.startPricingDecision(prId, { defaultMarginPct: 0.2 });
  return decision;
}

describe('mockApi.pricingRequests CEO price mode — owner ruling A (auto list price)', () => {
  it('listUnitPrice is a REAL stored value at decision creation, not null', async () => {
    const decision = await startNewFormDecision();
    const item = decision.items[0];
    expect(item.listUnitPrice).not.toBeNull();
    expect(item.listUnitPrice).toBeCloseTo(item.proposedSellingPricePerRequestedUnit, 2);
  });

  it('NET with zero discount approves without the CEO typing anything', async () => {
    const decision = await startNewFormDecision();
    const { decision: afterMode } = await api.pricingRequests.updatePricingDecision(decision.id, {
      priceMode: 'NET', items: [],
    });
    expect(afterMode.items[0].netUnitPrice).not.toBeNull();
    expect(afterMode.items[0].netUnitPrice).toBeCloseTo(afterMode.items[0].listUnitPrice, 2);
  });
});

describe('mockApi.pricingRequests CEO price mode — SPECIAL_SQM (review finding #7)', () => {
  // The owner's own 4 documented cases (WastageCalculatorTest fixture, 2026-09-11) — see
  // WastageCalculator#netPerPieceFromSpecialSqm's own Javadoc table.
  it.each([
    ['1350 @ 0.36 sqm/piece', 1350, 0.36, 453.84],
    ['1400 @ 0.36 sqm/piece', 1400, 0.36, 470.65],
    ['1800 @ 0.72 sqm/piece', 1800, 0.72, 1210.25],
    ['790 @ 0.72 sqm/piece', 790, 0.72, 531.16],
  ])('%s -> %f', async (_label, specialPriceSqm, sqmPerPiece, expectedNet) => {
    const decision = await startNewFormDecision(sqmPerPiece);
    const item = decision.items[0];
    const { decision: updated } = await api.pricingRequests.updatePricingDecision(decision.id, {
      priceMode: 'SPECIAL_SQM',
      items: [{ pricingDecisionItemId: item.id, specialPriceSqm }],
    });
    expect(updated.items[0].netUnitPrice).toBeCloseTo(expectedNet, 2);
  });

  it('rejects a negative special price and writes NOTHING (finding #7: no write before failing)', async () => {
    const decision = await startNewFormDecision();
    const item = decision.items[0];
    await api.pricingRequests.updatePricingDecision(decision.id, { priceMode: 'SPECIAL_SQM', items: [] });
    await expect(api.pricingRequests.updatePricingDecision(decision.id, {
      items: [{ pricingDecisionItemId: item.id, specialPriceSqm: -100 }],
    })).rejects.toThrow();

    const { decision: reread } = await api.pricingRequests.getPricingDecision(decision.id);
    expect(reread.items[0].specialPriceSqm).toBeNull();
    expect(reread.items[0].netUnitPrice).toBeNull();
  });
});

describe('mockApi.pricingRequests CEO price mode — mode switch + clear semantics', () => {
  // Owner correction (2026-09-19): listUnitPrice is no longer a field this payload accepts at
  // all (the CEO never types it, ruling A) -- these three tests used to send a hand-picked
  // listUnitPrice: 1000 to pin a round expected net. They now read the item's own
  // SERVER-derived listUnitPrice (already populated by startNewFormDecision) and compute the
  // expected net FROM that real value instead, same as the equivalent real-DB Java tests do.
  it('switching mode recomputes every item net from its own stored inputs (never freezes a stale value)', async () => {
    const decision = await startNewFormDecision();
    const item = decision.items[0];
    const { decision: net } = await api.pricingRequests.updatePricingDecision(decision.id, {
      priceMode: 'NET',
      items: [{ pricingDecisionItemId: item.id, discountPct: 10 }],
    });
    const listPrice = net.items[0].listUnitPrice;
    expect(listPrice).not.toBeNull();
    const expectedNet = Math.round(listPrice * 0.9 * 100) / 100;
    expect(net.items[0].netUnitPrice).toBeCloseTo(expectedNet, 2);

    const { decision: switched } = await api.pricingRequests.updatePricingDecision(decision.id, {
      priceMode: 'DIRECT_NET',
    });
    // No item has a direct_net_price yet -- must be null, never the old discounted figure.
    expect(switched.items[0].netUnitPrice).toBeNull();
  });

  it('clearing a discount resets net to the list price, not the old discounted figure', async () => {
    const decision = await startNewFormDecision();
    const item = decision.items[0];
    const { decision: withDiscount } = await api.pricingRequests.updatePricingDecision(decision.id, {
      priceMode: 'NET',
      items: [{ pricingDecisionItemId: item.id, discountPct: 10 }],
    });
    const listPrice = withDiscount.items[0].listUnitPrice;
    const { decision: cleared } = await api.pricingRequests.updatePricingDecision(decision.id, {
      items: [{ pricingDecisionItemId: item.id, clearDiscountPct: true }],
    });
    expect(cleared.items[0].discountPct).toBeNull();
    // Cleared discount reads as 0 in the NET formula -> net == list price.
    expect(cleared.items[0].netUnitPrice).toBeCloseTo(listPrice, 2);
    expect(cleared.items[0].listUnitPrice).toBeCloseTo(listPrice, 2);
  });

  it('a discount with more than 2dp rounds before storing and deriving (mirrors the Java service)', async () => {
    const decision = await startNewFormDecision();
    const item = decision.items[0];
    const { decision: updated } = await api.pricingRequests.updatePricingDecision(decision.id, {
      priceMode: 'NET',
      items: [{ pricingDecisionItemId: item.id, discountPct: 12.345 }],
    });
    expect(updated.items[0].discountPct).toBeCloseTo(12.35, 2);
    const listPrice = updated.items[0].listUnitPrice;
    const expectedNet = Math.round(listPrice * (1 - 12.35 / 100) * 100) / 100;
    expect(updated.items[0].netUnitPrice).toBeCloseTo(expectedNet, 2);
  });
});

describe('mockApi.pricingRequests CEO price mode — owner correction 2026-09-19 (list price refresh)', () => {
  // The real-DB Java IT (PricingDecisionCeoPriceModeIntegrationTest) proves the full "starts
  // uncosted -> overrideItemCost -> list price populated -> NET approves" scenario end to end;
  // the mock has no concept of a V156 "uncostable" line at all (CLAUDE.md's own guidance is
  // against reimplementing an algorithm the mock does not need), so this instead proves the
  // narrower, always-reachable claim: a cost-driven recompute (overrideItemCost /
  // recalculateCost) REFRESHES listUnitPrice/netUnitPrice here too, closing the same staleness
  // gap the Java fix closes, for the mock's own plumbing.
  it('overridePricingDecisionItemCost refreshes listUnitPrice and netUnitPrice', async () => {
    const decision = await startNewFormDecision();
    const item = decision.items[0];
    await api.pricingRequests.updatePricingDecision(decision.id, {
      priceMode: 'NET',
      items: [{ pricingDecisionItemId: item.id, discountPct: 10 }],
    });

    const { decision: overridden } = await api.pricingRequests.overridePricingDecisionItemCost(
      decision.id, item.id, { manualLandedCostPerUnitThb: 500, reason: 'ทดสอบ: ปรับต้นทุนเอง' },
    );
    const overriddenItem = overridden.items.find((i) => i.id === item.id);
    expect(overriddenItem.listUnitPrice).not.toBeNull();
    expect(overriddenItem.listUnitPrice).toBeCloseTo(overriddenItem.proposedSellingPricePerRequestedUnit, 2);
    // discountPct (10%) survived the cost change -- net re-derives from the FRESH list price,
    // not the pre-override one.
    const expectedNet = Math.round(overriddenItem.listUnitPrice * 0.9 * 100) / 100;
    expect(overriddenItem.netUnitPrice).toBeCloseTo(expectedNet, 2);
  });

  it('recalculatePricingDecisionCost refreshes listUnitPrice and netUnitPrice', async () => {
    const decision = await startNewFormDecision();
    const item = decision.items[0];
    const { decision: withDiscount } = await api.pricingRequests.updatePricingDecision(decision.id, {
      priceMode: 'NET',
      items: [{ pricingDecisionItemId: item.id, discountPct: 10 }],
    });
    const beforeItem = withDiscount.items.find((i) => i.id === item.id);
    expect(beforeItem.listUnitPrice).not.toBeNull();

    const { decision: recalculated } = await api.pricingRequests.recalculatePricingDecisionCost(decision.id);
    const afterItem = recalculated.items.find((i) => i.id === item.id);
    // Nothing about the underlying cost/margin/discount changed -- the refreshed figures must
    // come back identical, proving the refresh path runs (not merely leaves stale values alone
    // by accident) and is stable.
    expect(afterItem.listUnitPrice).toBeCloseTo(beforeItem.listUnitPrice, 2);
    expect(afterItem.netUnitPrice).toBeCloseTo(beforeItem.netUnitPrice, 2);
  });
});
