import { randomUUID } from 'node:crypto';
import { test, expect } from '@playwright/test';
import { apiSessionsFor, apiWrite, disposeSessions } from './helpers/api.js';
import { loginAs } from './helpers/auth.js';
import { inDays } from './helpers/sales.js';

/**
 * บันทึกโรงงาน must not reload the page. SafeForm used to preventDefault only when it BLOCKED a
 * submit, so an allowed one was a native GET submission: full reload, every unsaved draft lost.
 * jsdom cannot navigate, so this is the only layer that proves it. Run green against a real
 * stack on 2026-10-18 and mutation-checked (SafeForm reverted -> red on the marker assertion).
 * Proof of "no reload" is the `globalThis.__noReloadMarker` set on the live document before the
 * click: a full reload replaces the document and the marker vanishes. Selectors follow
 * ImportFactoryPicker (`#pcr-item-factory-<itemId>`, button "บันทึกโรงงาน").
 */
test('บันทึกโรงงาน saves without a page navigation and keeps another line\'s unsaved draft', async ({ page }) => {
  test.setTimeout(90_000);
  const sessions = await apiSessionsFor(['sales', 'import']);
  try {
    // Same roster the picker lists (hrApi priceImport.factories -> GET /api/price-import/factories);
    // no country/price-config needed, saving a line's factory only needs an id the picker offers.
    const roster = await sessions.import.get('/api/price-import/factories');
    expect(roster.ok()).toBe(true);
    const factory = (await roster.json())[0];
    expect(factory, 'factory roster is empty').toBeTruthy();
    // Inline setup (helpers/sales.js's createDeal/runId are UAT-only: they need E2E_RUN_ID).
    const tag = `ifs-${Date.now().toString(36)}`;
    const c = await apiWrite(sessions.sales, 'post', '/api/customers', { name: `บริษัท ${tag} จำกัด` });
    expect(c.status(), 'POST /api/customers').toBe(200);
    const customer = (await c.json()).customer;
    const pj = await apiWrite(sessions.sales, 'post', `/api/customers/${customer.id}/projects`, { name: `โครงการ ${tag}` });
    expect(pj.status(), 'POST project').toBe(200);
    const d = await apiWrite(sessions.sales, 'post', '/api/tickets', {
      title: `${tag} deal`, customerName: customer.name, customerId: customer.id,
      projectId: (await pj.json()).project.id, contactId: null, entryChannel: 'DESIGNER_LED', priority: 'NORMAL', items: [], nextFollowUpAt: inDays(7),
    });
    expect(d.status(), `POST /api/tickets ${await d.text()}`).toBe(200);
    const deal = { id: (await d.json()).ticket.summary.id };
    const blank = (n) => ({
      brand: 'E2E', model: `${tag} m${n}`, productDescription: `${tag} blank-factory line ${n}`,
      color: 'ขาว', texture: 'ด้าน', size: '60x60', thicknessMm: 10, sqmPerPiece: 0.36, piecesPerBox: 4,
      quantityMode: 'PIECES', piecesInput: 20, originCountry: 'ไทย-สต็อก', leadTimeMinDays: 3, leadTimeMaxDays: 7,
      requestedQty: 20, requestedQtySqm: 7.2, requestedUnit: 'PCS', requestedUnitBasis: 'PER_PIECE', quantityType: 'CONFIRMED',
    });
    const created = await apiWrite(sessions.sales, 'post', `/api/tickets/${deal.id}/pricing-requests`, {
      recipientType: 'DESIGNER', recipientLabel: `${tag} recipient`, targetCurrency: 'THB',
      note: `${tag} e2e`, clientRequestId: randomUUID(), items: [blank(1), blank(2)],
    });
    expect(created.status(), await created.text()).toBe(201);
    const pcr = (await created.json()).pricingRequest;
    for (const [who, step] of [['sales', 'submit'], ['import', 'pickup']]) {
      const r = await apiWrite(sessions[who], 'post', `/api/pricing-requests/${pcr.summary.id}/${step}`);
      expect(r.status(), step).toBe(200);
    }
    const [first, second] = pcr.items.map((i) => i.id);

    await page.goto('/login');
    await loginAs(page, 'import');
    await page.goto(`/pricing-requests/${pcr.summary.id}`);
    const url = page.url();

    await page.evaluate(() => { globalThis.__noReloadMarker = true; });
    const pick = async (itemId) => {
      await page.locator(`#pcr-item-factory-${itemId}`).click();
      await page.getByRole('option', { name: factory.name }).first().click();
    };

    await pick(second); // unsaved draft on the OTHER line
    await pick(first);
    const saved = page.waitForResponse((r) => r.url().includes(`/items/${first}/factory`) && r.request().method() !== 'GET');
    await page.getByRole('button', { name: 'บันทึกโรงงาน' }).first().click();
    expect((await saved).ok()).toBe(true);

    expect(await page.evaluate(() => globalThis.__noReloadMarker), 'document was replaced').toBe(true);
    expect(page.url()).toBe(url);
    await expect(page.locator(`#pcr-item-factory-${second}`)).toHaveValue(new RegExp(factory.name));
  } finally {
    await disposeSessions(sessions);
  }
});
