import { describe, expect, it } from 'vitest';
import { api } from './mockApi.js';

// Slice 6c: the mock's coverage `splitPreview` — PLUMBING coverage only. The mock places lines with the SAME
// simplified cascade its own createDrafts uses (mockSplitFactoryCandidates, shared with mockResolveFactoryGroups),
// and its lead time is the country default alone because its createDrafts mirrors no quotation lead time either.
// So nothing here is evidence about ImportRequestService's split or about who may see the preview: the authority is
// ImportRequestSplitPreviewIntegrationTest (real Postgres) and CreateDraftsCharacterizationIntegrationTest.

let n = 0;
async function ownedDealWithMixedLines() {
  n += 1;
  await api.auth.login({ role: 'sales' });
  const { customer } = await api.customers.create({
    name: `บริษัท แยกโรงงาน ${n} จำกัด`, taxId: `030000000${n}`, address: `${n} ถนนทดสอบ`,
    branch: 'สาขาทดสอบ', phone: '02-111-1111',
  });
  const { project } = await api.customers.createProject(customer.id, { name: `โครงการแยกโรงงาน ${n}` });
  const { ticket } = await api.tickets.create({
    entryChannel: 'DESIGNER_LED',
    title: `งานแยกโรงงาน ${n}`,
    priority: 'NORMAL',
    customerName: customer.name,
    customerId: customer.id,
    projectId: project.id,
    items: [
      { brand: 'Panaria', model: 'Ivory', qty: 10, currency: 'THB', factory: 'Panaria SpA' },
      { brand: 'Panaria', model: 'Grey', qty: 4, currency: 'THB', factory: 'panaria  spa' },
      { brand: 'Nova', model: 'Nova One', qty: 3, currency: 'THB', factory: `Mock New Co ${n}` },
      { brand: 'ค่าติดตั้ง', model: 'Free text', qty: 1, currency: 'THB', factory: null },
    ],
  });
  return { ticketId: ticket.summary.id, freeLineId: ticket.items[3].id };
}

describe('mockApi storedImportRequests.coverage — splitPreview (slice 6c)', () => {
  it('the owning rep gets one entry per factory createDrafts would issue, and the line it cannot place', async () => {
    const { ticketId, freeLineId } = await ownedDealWithMixedLines();

    const { splitPreview } = await api.storedImportRequests.coverage(ticketId);

    expect(splitPreview.factories).toEqual([
      { factoryName: 'Panaria SpA', newFactory: false, itemCount: 2, leadTimeMinDays: 75, leadTimeMaxDays: 90, leadTimeSource: 'COUNTRY_DEFAULT' },
      { factoryName: `Mock New Co ${n}`, newFactory: true, itemCount: 1, leadTimeMinDays: null, leadTimeMaxDays: null, leadTimeSource: null },
    ]);
    expect(splitPreview.linesWithoutFactory).toEqual([{ ticketItemId: freeLineId, code: 'Free text', size: null }]);
  });

  it('applies an unsaved pick in memory only: the line joins the picked factory and the deal line keeps no factory', async () => {
    const { ticketId, freeLineId } = await ownedDealWithMixedLines();

    const { splitPreview } = await api.storedImportRequests.coverage(ticketId, [{ ticketItemId: freeLineId, factoryId: 11 }]);

    expect(splitPreview.linesWithoutFactory).toEqual([]);
    expect(splitPreview.factories.map((f) => [f.factoryName, f.itemCount, f.leadTimeSource]))
      .toEqual([['Panaria SpA', 2, 'COUNTRY_DEFAULT'], ['Cotto Industry', 1, 'COUNTRY_DEFAULT'], [`Mock New Co ${n}`, 1, null]]);
    const { ticket } = await api.tickets.get(ticketId);
    expect(ticket.items.find((i) => i.id === freeLineId).factory ?? null).toBeNull();
    const names = (await api.factoryConfigs.names()).factories.map((f) => f.factoryName);
    expect(names).not.toContain(`Mock New Co ${n}`);
  });

  it('import and sales_manager read the covered flag but get no preview (mirrors the Java gate; not evidence of it)', async () => {
    const { ticketId } = await ownedDealWithMixedLines();
    for (const role of ['import', 'sales_manager']) {
      await api.auth.login({ role });
      const coverage = await api.storedImportRequests.coverage(ticketId);
      expect(coverage.hasUncoveredFactories, role).toBe(true);
      expect(coverage.splitPreview, role).toBeNull();
    }
  });
});
