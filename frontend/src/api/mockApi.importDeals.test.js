import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// Mirrors th.co.glr.hr.importdeal.ImportDealService / ImportDealDtos -- GET /api/import/deals/{id},
// the import-only per-deal projection. What this pins is the MOCK's own fidelity to that DTO
// shape (fields present, price/cost fields absent, comment thread only) and the two authz edges
// the mock can approximate cheaply. NOT authz evidence about production: the real role/row-scope
// enforcement is ImportDealAuthzIntegrationTest against the Java service (CLAUDE.md -- the mock's
// authz is never authoritative).

// Every price/cost/margin/commission/weighting field TicketItemDto carries that the import view
// must never expose. Deliberately a superset of what the mock's ticket items happen to hold.
const FORBIDDEN_ITEM_FIELDS = [
  'rawPrice', 'rawCurrency', 'rawUnit', 'proposedPrice', 'approvedPrice', 'currency',
  'calcedCost', 'calcedPrice', 'calcConfigVersion', 'manualPrice', 'manualOverrideReason',
  'catalogPrice', 'catalogCurrency', 'catalogPriceUnit', 'stockSalePrice', 'weightMultiplier',
  'factory', 'sqmPerPiece',
];
const ITEM_FIELDS = ['id', 'brand', 'model', 'color', 'texture', 'size', 'code', 'qty', 'qtySqm', 'unit', 'qtyDelivered'];
const DEAL_FIELDS = [
  'id', 'code', 'title', 'status', 'lifecycle', 'salesStage', 'customerName', 'projectName',
  'createdByName', 'fulfillmentStatus', 'items', 'importRequests', 'comments',
];

async function createDealWithCommentAsSales() {
  await api.auth.login({ role: 'sales' });
  const { customers } = await api.customers.search('');
  const customer = customers[0];
  let { projects } = await api.customers.projects(customer.id);
  if (!projects?.length) {
    const { project } = await api.customers.createProject(customer.id, { name: 'โครงการทดสอบ' });
    projects = [project];
  }
  const { ticket } = await api.tickets.create({
    title: 'ดีลนำเข้าทดสอบ', customerName: customer.name, customerId: customer.id,
    projectId: projects[0].id, entryChannel: 'DESIGNER_LED', priority: 'NORMAL',
    items: [{ brand: 'Panaria', model: 'Ivory Lappato', qty: 10, currency: 'THB', rawPrice: 500, factory: 'Panaria SpA' }],
  });
  const id = ticket.summary.id;
  await api.tickets.comment(id, { message: 'ฝากเช็คเวลาส่งของด้วยนะคะ' });
  return id;
}

describe('mock importDeals.get -- import-only projection of one deal', () => {
  it('is wired on the mock at all', () => {
    expect(api.importDeals, 'api.importDeals missing').toBeDefined();
    expect(typeof api.importDeals.get).toBe('function');
  });

  it('returns the ImportDealDto envelope with exactly the DTO fields, for the CEO', async () => {
    const id = await createDealWithCommentAsSales();
    await api.auth.login({ role: 'ceo' });
    const { deal } = await api.importDeals.get(id);
    expect(Object.keys(deal).sort()).toEqual([...DEAL_FIELDS].sort());
    expect(deal.id).toBe(id);
    expect(deal.items).toHaveLength(1);
    expect(Object.keys(deal.items[0]).sort()).toEqual([...ITEM_FIELDS].sort());
    expect(deal.items[0]).toMatchObject({ brand: 'Panaria', model: 'Ivory Lappato' });
    expect(Number(deal.items[0].qty)).toBe(10);
    expect(Array.isArray(deal.importRequests)).toBe(true);
  });

  it('never carries a price/cost field on an item, even though the source item has them', async () => {
    const id = await createDealWithCommentAsSales();
    await api.auth.login({ role: 'ceo' });
    // Guard against a vacuous pass: the whole-deal read DOES carry prices on this same item.
    const { ticket } = await api.tickets.get(id);
    expect(Object.keys(ticket.items[0])).toEqual(expect.arrayContaining(['proposedPrice', 'approvedPrice', 'currency', 'stockSalePrice']));
    const { deal } = await api.importDeals.get(id);
    for (const field of FORBIDDEN_ITEM_FIELDS) {
      expect(deal.items[0], `item leaks ${field}`).not.toHaveProperty(field);
    }
    expect(JSON.stringify(deal)).not.toMatch(/proposedPrice|approvedPrice|rawPrice|calcedCost/);
  });

  it('exposes only the COMMENTED thread -- not the raw event feed', async () => {
    const id = await createDealWithCommentAsSales();
    await api.auth.login({ role: 'ceo' });
    const { ticket } = await api.tickets.get(id);
    expect(ticket.events.some((e) => e.kind !== 'COMMENTED')).toBe(true);
    const { deal } = await api.importDeals.get(id);
    expect(deal.comments).toHaveLength(1);
    expect(deal.comments[0]).toEqual({
      id: expect.any(Number), actorName: expect.any(String),
      message: 'ฝากเช็คเวลาส่งของด้วยนะคะ', createdAt: expect.any(String),
    });
  });

  it('refuses a role outside import/ceo (sales, even the owner) with 403', async () => {
    const id = await createDealWithCommentAsSales();
    await expect(api.importDeals.get(id)).rejects.toMatchObject({ status: 403 });
  });

  it('refuses import a deal outside the import worklist scope with 403 (no existence oracle)', async () => {
    const id = await createDealWithCommentAsSales(); // no pricing request, pre-PROCUREMENT
    await api.auth.login({ role: 'import' });
    await expect(api.importDeals.get(id)).rejects.toMatchObject({ status: 403 });
    await expect(api.importDeals.get(99999999)).rejects.toMatchObject({ status: 403 });
  });

  it('answers 404 for a missing deal to the CEO', async () => {
    await api.auth.login({ role: 'ceo' });
    await expect(api.importDeals.get(99999999)).rejects.toMatchObject({ status: 404 });
  });
});

// The backend now refuses import the whole-deal read (TicketService.get) and points it at
// GET /api/import/deals/{id}. The mock must be no MORE permissive than that, or a mock-driven run
// (and the frontend's own guard) never exercises the refusal. Authz here is an approximation only.
describe('mock tickets.get -- import is refused the whole-deal read', () => {
  it('403s import on tickets.get even for a deal inside its import scope', async () => {
    await api.auth.login({ role: 'ceo' });
    // Any seeded deal is fine: the refusal is role-based and must come BEFORE the row lookup.
    const { tickets } = await api.tickets.list({});
    expect(tickets.length).toBeGreaterThan(0);
    await api.auth.login({ role: 'import' });
    await expect(api.tickets.get(tickets[0].id)).rejects.toMatchObject({ status: 403 });
    // No existence oracle: a missing id 403s too, not 404.
    await expect(api.tickets.get(99999999)).rejects.toMatchObject({ status: 403 });
  });

  it('still serves the whole deal to the CEO and sales (unchanged)', async () => {
    await api.auth.login({ role: 'ceo' });
    const { tickets } = await api.tickets.list({});
    await expect(api.tickets.get(tickets[0].id)).resolves.toHaveProperty('ticket');
    await expect(api.tickets.get(99999999)).rejects.toMatchObject({ status: 404 });
  });
});

// TicketAccessPolicy.canViewDocuments: import READS an in-scope deal's documents (all four
// AttachTypes) and is refused an out-of-scope one; it never writes. The import deal page lists
// attachments through this call.
describe('mock attachments -- import reads in-scope deal documents only', () => {
  it('lets import list attachments on a deal in its import scope, and refuses one outside it', async () => {
    const outOfScopeId = await createDealWithCommentAsSales(); // pre-PROCUREMENT: outside import scope
    const login = await api.auth.login({ role: 'import' });
    const importUserId = (login.user ?? login).id;
    await expect(api.attachments.list(outOfScopeId)).rejects.toMatchObject({ status: 403 });

    // A deal import is NOT a participant of: the read must come from the import-scope rule, not
    // from having picked the deal up (which the participant check already grants).
    const { tickets } = await api.tickets.list({});
    const nonParticipant = tickets.find((t) => t.assignedToId !== importUserId && t.createdById !== importUserId);
    expect(nonParticipant, 'seed must expose an in-scope deal import did not pick up').toBeDefined();
    await expect(api.attachments.list(nonParticipant.id)).resolves.toHaveProperty('attachments');
  });

  // Reading is the ONLY thing the import-scope rule grants: a deal import did not pick up stays
  // read-only (TicketAccessPolicy.canManageDocuments is participant OR sales_manager/ceo).
  it('does not let import upload to an in-scope deal it did not pick up', async () => {
    const login = await api.auth.login({ role: 'import' });
    const importUserId = (login.user ?? login).id;
    const { tickets } = await api.tickets.list({});
    const nonParticipant = tickets.find((t) => t.assignedToId !== importUserId && t.createdById !== importUserId);
    expect(nonParticipant, 'seed must expose an in-scope deal import did not pick up').toBeDefined();
    await expect(api.attachments.upload(nonParticipant.id, new File(['x'], 'x.pdf'), 'OTHER'))
      .rejects.toMatchObject({ status: 403 });
  });
});

// TicketService.comment (Leak B): import's comment path is row-scoped to its import scope.
describe('mock tickets.comment -- import is row-scoped', () => {
  it('refuses import a comment on a deal outside its import scope, and on a missing deal', async () => {
    const outOfScopeId = await createDealWithCommentAsSales();
    await api.auth.login({ role: 'import' });
    await expect(api.tickets.comment(outOfScopeId, { message: 'x' })).rejects.toMatchObject({ status: 403 });
    await expect(api.tickets.comment(99999999, { message: 'x' })).rejects.toMatchObject({ status: 403 });
  });

  it('lets import comment on an in-scope deal', async () => {
    await api.auth.login({ role: 'import' });
    const { tickets } = await api.tickets.list({});
    await expect(api.tickets.comment(tickets[0].id, { message: 'รับทราบ' })).resolves.toHaveProperty('ticket');
  });
});
