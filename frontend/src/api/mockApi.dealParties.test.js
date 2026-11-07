import { beforeEach, describe, expect, it, vi } from 'vitest';

// Deal parties (docs/decisions/deal-party-model.md). Pins the MOCK's plumbing of the contract the
// backend's dealparty package serves; authz here is an approximation (CLAUDE.md) and the real rules
// are pinned by DealPartyIntegrationTest. Every test gets a fresh module (pristine seed).
let api;
let retireLivePricingRequestsForTests;
beforeEach(async () => {
  vi.resetModules();
  ({ api, retireLivePricingRequestsForTests } = await import('./mockApi.js'));
  await api.auth.login({ role: 'sales' }); // id 6 owns ticket 18
});

const ONE_ITEM = {
  model: 'Trilogy', color: 'Ash', texture: 'Matt', sizeText: '60x60', thicknessMm: 10, sqmPerPiece: 0.36,
  quantityMode: 'AREA', areaSqm: 20, wastageMode: 'NONE', wastageValue: 0, piecesPerBox: 3, unitPrice: 850, discountPct: 0,
  leadTimeMinDays: 30, leadTimeMaxDays: 45,
};

async function partiesOf(ticketId) {
  return (await api.tickets.parties(ticketId)).parties;
}
const byCustomer = (parties, customerId, role = 'BUYER') => parties.find((p) => p.customerId === customerId && p.role === role);

describe('mock deal parties — seed and backfill', () => {
  it('ticket 18 has three BUYER parties and one DESIGNER, its own customer the headline', async () => {
    const parties = await partiesOf(18);
    expect(parties.filter((p) => p.role === 'BUYER').map((p) => p.customerId).sort()).toEqual([5, 7, 8]);
    expect(parties.filter((p) => p.role === 'DESIGNER')).toHaveLength(1);
    expect(byCustomer(parties, 5)).toMatchObject({ status: 'QUOTED', headline: true });
    expect(byCustomer(parties, 7)).toMatchObject({ status: 'QUOTED', latestQuotationStatus: 'APPROVED', latestQuotationNumber: 'QT-2026-0003-1' });
    expect(byCustomer(parties, 8)).toMatchObject({ status: 'NEGOTIATING', latestQuotationStatus: 'APPROVED' });
    expect(byCustomer(parties, 7).latestQuotationAmount).toBeGreaterThan(0);
  });

  it('quotation rows carry partyId / partyStatus / partyHeadline / dealBuyerCount', async () => {
    const { items } = await api.dealQuotations.listForTicket(18);
    const seeded = items.find((q) => q.id === 1);
    expect(seeded).toMatchObject({ partyStatus: 'QUOTED', partyHeadline: true, dealBuyerCount: 3 });
    expect(seeded.partyId).toEqual(expect.any(Number));
    expect(items.find((q) => q.id === 801)).toMatchObject({ customerName: 'บริษัท สมมติ ก่อสร้าง จำกัด', partyHeadline: false });
  });

  it('a new deal gets one ACTIVE headline BUYER party from its customer', async () => {
    const { ticket } = await api.tickets.create({
      title: 'x', customerName: 'บริษัท ก้าวหน้า คอนสตรัคชั่น จำกัด', customerId: 1, projectId: 1, entryChannel: 'BUYER_DIRECT', items: [],
    });
    expect(await partiesOf(ticket.summary.id)).toEqual([
      expect.objectContaining({ customerId: 1, role: 'BUYER', status: 'ACTIVE', headline: true }),
    ]);
  });
});

describe('mock deal parties — add / update / remove', () => {
  it('adds a party, and 409s the same customer in the same role again', async () => {
    const { party } = await api.tickets.addParty(18, { customerId: 10, role: 'BUYER', contactId: 11 });
    expect(party).toMatchObject({ customerId: 10, role: 'BUYER', status: 'ACTIVE', contactName: 'ประมูล สาม' });
    await expect(api.tickets.addParty(18, { customerId: 10, role: 'BUYER' })).rejects.toMatchObject({ status: 409 });
  });

  it('refuses a contact of another customer', async () => {
    await expect(api.tickets.addParty(18, { customerId: 10, role: 'BUYER', contactId: 6 })).rejects.toMatchObject({ status: 400 });
  });

  it('another rep cannot add a party to a deal they do not own', async () => {
    await api.auth.login({ email: 'sales2@glr.co.th', password: 'demo1234' });
    await expect(api.tickets.addParty(18, { customerId: 10, role: 'BUYER' })).rejects.toMatchObject({ status: 403 });
  });

  it('headline moves to one party; status patch is limited to NEGOTIATING / QUOTED / WITHDRAWN', async () => {
    const bidder = byCustomer(await partiesOf(18), 7);
    await api.tickets.updateParty(18, bidder.id, { headline: true });
    const after = await partiesOf(18);
    expect(after.filter((p) => p.headline).map((p) => p.id)).toEqual([bidder.id]);
    await expect(api.tickets.updateParty(18, bidder.id, { status: 'AWARDED' })).rejects.toMatchObject({ status: 400 });
    expect((await api.tickets.updateParty(18, bidder.id, { status: 'NEGOTIATING' })).party.status).toBe('NEGOTIATING');
  });

  it('removes an ACTIVE party with no quotation; 409 for one with a quotation', async () => {
    const { party } = await api.tickets.addParty(18, { customerId: 10, role: 'BUYER' });
    await api.tickets.removeParty(18, party.id);
    expect((await partiesOf(18)).some((p) => p.id === party.id)).toBe(false);
    await expect(api.tickets.removeParty(18, byCustomer(await partiesOf(18), 7).id)).rejects.toMatchObject({ status: 409 });
  });
});

describe('mock deal parties — quotations per party', () => {
  it('a create with partyId snapshots that party\'s customer, and a live quotation of ANOTHER party does not block it', async () => {
    retireLivePricingRequestsForTests(18);
    const { party } = await api.tickets.addParty(18, { customerId: 10, role: 'BUYER' });
    const { quotation } = await api.dealQuotations.create(18, { recipientType: 'BUYER', partyId: party.id, contactId: 11, items: [ONE_ITEM] });
    expect(quotation).toMatchObject({ partyId: party.id, customerName: 'บริษัท ตัวอย่าง คอนสตรัคชั่น จำกัด', contactName: 'ประมูล สาม', dealBuyerCount: 4 });
    // Its own family number, not the deal's.
    expect(quotation.number).toMatch(/^QT-\d{4}-\d{4}-1$/);
    expect((await partiesOf(18)).find((p) => p.id === party.id).status).toBe('QUOTED');
    // The SAME party may not mint a second live one.
    await expect(api.dealQuotations.create(18, { recipientType: 'BUYER', partyId: party.id, items: [ONE_ITEM] }))
      .rejects.toMatchObject({ status: 409 });
  });

  it('a contact of the deal\'s customer is refused on another party\'s quotation', async () => {
    retireLivePricingRequestsForTests(18);
    const { party } = await api.tickets.addParty(18, { customerId: 10, role: 'BUYER' });
    await expect(api.dealQuotations.create(18, { recipientType: 'BUYER', partyId: party.id, contactId: 6, items: [ONE_ITEM] }))
      .rejects.toMatchObject({ status: 400 });
  });

  it('a create without partyId attaches to the deal customer\'s party (recipient role first) and marks it QUOTED', async () => {
    const { ticket } = await api.tickets.create({
      title: 'x', customerName: 'บริษัท ก้าวหน้า คอนสตรัคชั่น จำกัด', customerId: 1, projectId: 1, entryChannel: 'BUYER_DIRECT', items: [],
    });
    const id = ticket.summary.id;
    const { quotation } = await api.dealQuotations.create(id, { recipientType: 'BUYER', items: [ONE_ITEM] });
    const [party] = await partiesOf(id);
    expect(quotation.partyId).toBe(party.id);
    expect(party.status).toBe('QUOTED');
  });

  it('approval supersedes only within the same party', async () => {
    retireLivePricingRequestsForTests(18);
    const { party } = await api.tickets.addParty(18, { customerId: 10, role: 'BUYER' });
    const { quotation } = await api.dealQuotations.create(18, { recipientType: 'BUYER', partyId: party.id, items: [ONE_ITEM] });
    await api.dealQuotations.submit(quotation.id);
    await api.auth.login({ role: 'sales_manager' });
    await api.dealQuotations.approve(quotation.id, {});
    const { items } = await api.dealQuotations.listForTicket(18);
    expect(items.find((q) => q.id === 801).docStatus).toBe('APPROVED');
    expect(items.find((q) => q.id === 802).docStatus).toBe('APPROVED');
  });
});

describe('mock deal parties — bid outcome', () => {
  it('two winners: the primary becomes the bill-to at ORDER_RECEIVED, the other forks <code>-A, the rest NOT_AWARDED', async () => {
    const parties = await partiesOf(18);
    const a = byCustomer(parties, 7);
    const b = byCustomer(parties, 8);
    const { result } = await api.tickets.recordBidOutcome(18, {
      awardedPartyIds: [a.id, b.id], primaryPartyId: b.id, othersOutcome: 'NOT_AWARDED',
    });
    expect(result.primaryTicketId).toBe(18);
    expect(result.ticket).toMatchObject({ customerId: 8, salesStage: 'ORDER_RECEIVED' });
    expect(result.childTicketIds).toHaveLength(1);
    const { ticket: child } = await api.tickets.get(result.childTicketIds[0]);
    expect(child.summary).toMatchObject({ code: 'PR-2026-0018-A', customerId: 7, status: 'quotation_issued', salesStage: 'ORDER_RECEIVED' });
    const after = await partiesOf(18);
    expect(byCustomer(after, 8)).toMatchObject({ status: 'AWARDED', headline: true });
    expect(byCustomer(after, 7)).toMatchObject({ status: 'AWARDED_SPLIT', splitTicketCode: 'PR-2026-0018-A' });
    expect(byCustomer(after, 5)).toMatchObject({ status: 'NOT_AWARDED', notAwardedReason: 'OTHER_BIDDER' });
    // The winner's quotation chain moved with it.
    expect((await api.dealQuotations.get(801)).quotation.ticketId).toBe(child.summary.id);
    expect(await partiesOf(child.summary.id)).toEqual([expect.objectContaining({ customerId: 7, status: 'AWARDED' })]);
  });

  it('409 names a party whose latest quotation is not APPROVED', async () => {
    const own = byCustomer(await partiesOf(18), 5);
    await expect(api.tickets.recordBidOutcome(18, { awardedPartyIds: [own.id], othersOutcome: 'NOT_AWARDED' }))
      .rejects.toMatchObject({ status: 409, message: expect.stringContaining('บริษัท แฟชั่นไอส์แลนด์ จำกัด') });
  });

  it('400 with no winner; KEEP_WAITING leaves the others as they were; a later outcome forks with no second primary', async () => {
    await expect(api.tickets.recordBidOutcome(18, { awardedPartyIds: [], othersOutcome: 'NOT_AWARDED' })).rejects.toMatchObject({ status: 400 });
    const parties = await partiesOf(18);
    const a = byCustomer(parties, 7);
    const b = byCustomer(parties, 8);
    await api.tickets.recordBidOutcome(18, { awardedPartyIds: [a.id], othersOutcome: 'KEEP_WAITING' });
    expect(byCustomer(await partiesOf(18), 8).status).toBe('NEGOTIATING');
    // Awarding the primary again is refused.
    await expect(api.tickets.recordBidOutcome(18, { awardedPartyIds: [a.id], othersOutcome: 'KEEP_WAITING' })).rejects.toMatchObject({ status: 409 });
    const { result } = await api.tickets.recordBidOutcome(18, { awardedPartyIds: [b.id], primaryPartyId: b.id, othersOutcome: 'KEEP_WAITING' });
    expect(result.ticket.customerId).toBe(7);
    expect(result.childTicketIds).toHaveLength(1);
  });
});

describe('mock deal parties — one live offer per party after an order', () => {
  it('a party still waiting may revise, submit and approve its own quotation after another party\'s order was confirmed', async () => {
    retireLivePricingRequestsForTests(18);
    const parties = await partiesOf(18);
    await api.tickets.recordBidOutcome(18, { awardedPartyIds: [byCustomer(parties, 7).id], othersOutcome: 'KEEP_WAITING' });
    const { quotation: revision } = await api.dealQuotations.createRevision(802, {});
    await api.dealQuotations.submit(revision.id);
    await api.auth.login({ role: 'sales_manager' });
    const { quotation } = await api.dealQuotations.approve(revision.id, {});
    expect(quotation.docStatus).toBe('APPROVED');
    // Only its own earlier offer was superseded; the awarded party's stays.
    expect((await api.dealQuotations.get(802)).quotation.docStatus).toBe('SUPERSEDED');
    expect((await api.dealQuotations.get(801)).quotation.docStatus).toBe('APPROVED');
  });
});

describe('mock deal parties — open deals for a project', () => {
  it('lists the actor\'s own pre-outcome deal whose project name matches, case/whitespace-insensitively', async () => {
    const { deals } = await api.tickets.openForProject('  ศูนย์การค้า   FASHION island ');
    expect(deals).toEqual([expect.objectContaining({ id: 18, buyerCount: 3, projectName: 'ศูนย์การค้า Fashion Island' })]);
    expect(deals[0].parties.map((p) => p.customerName)).toContain('บริษัท สมมติ ก่อสร้าง จำกัด');
    expect((await api.tickets.openForProject('ไม่มีโครงการนี้')).deals).toEqual([]);
  });

  it('another rep sees none of it', async () => {
    await api.auth.login({ email: 'sales2@glr.co.th', password: 'demo1234' });
    expect((await api.tickets.openForProject('ศูนย์การค้า Fashion Island')).deals).toEqual([]);
  });
});
