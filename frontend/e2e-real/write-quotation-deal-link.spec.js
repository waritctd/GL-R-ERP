import { randomUUID } from 'node:crypto';
import { test, expect } from '@playwright/test';
import { apiSessionsFor, apiWrite, disposeSessions } from './helpers/api.js';
import { directQuotationBody, inDays } from './helpers/sales.js';

/**
 * Quotation ↔ deal linking, SLICE 2 — the contract PR #1090's UI codes against, proven against the
 * REAL Spring service over HTTP (no mock in the path). `mockApi.js` mirrors every rule below, and a
 * mirror is exactly what CLAUDE.md says cannot be evidence about the rule it mirrors; this file is.
 *
 *   S2-B1  POST /api/tickets/{id}/deal-quotations needs `recipientType` (DESIGNER|OWNER|BUYER) — 400
 *          "ต้องระบุผู้รับใบเสนอราคา" without it; it moves no deal stage.
 *   S2-B4  GET /api/tickets/{id} and GET /api/tickets carry `summary.liveDirectQuotation`
 *          = { id, number, docStatus, recipientType } of the newest live direct quotation, else null.
 *   S2-B3  N6 — a second direct quotation on the same deal is 409, and the body names the live one
 *          in TOP-LEVEL `liveQuotationId` / `number` / `docStatus` (the UI reads them as
 *          `error.details.liveQuotationId`, `details` being the whole body — src/api/client.js).
 *          Cancelling the live one lifts it.
 *   One pricing route per deal (owner ruling 2026-09-30): a deal with a live คำขอราคา refuses a
 *          direct quotation, 409.
 *
 * Written wrong-way-round where it counts: every refusal is followed by a read-back proving nothing
 * was written. Each test creates its OWN deal (see README "How the write specs stay safe on a shared
 * database") and cancels the direct quotation it leaves live.
 *
 * The request bodies are the ones the backend's DealQuotationSlice2HttpIntegrationTest sends through
 * the real controllers, so the fixtures here are already known to be accepted by the service.
 */

const LIVE_PRICING_REQUEST_MESSAGE =
  'ดีลนี้มีคำขอราคาที่ยังดำเนินการอยู่ — ใช้ใบเสนอราคาจากคำขอราคา หรือยกเลิกคำขอราคาก่อน';

test.describe('quotation ↔ deal link (slice 2) — the real service', () => {
  let sessions;
  let customerId;
  let customerName;
  let projectId;
  const tag = `qdl-${Date.now().toString(36)}`;

  /** QuotationEditorPage#handleInlineCreate's own api.tickets.create body, with a real channel. */
  async function newDeal(entryChannel) {
    const response = await apiWrite(sessions.sales, 'post', '/api/tickets', {
      title: `${tag} ${entryChannel}`,
      customerName,
      customerId,
      projectId,
      contactId: null,
      entryChannel,
      priority: 'NORMAL',
      items: [],
      nextFollowUpAt: inDays(7),
      quotationOnly: true,
    });
    expect(response.status(), 'POST /api/tickets').toBe(200);
    const { summary } = (await response.json()).ticket;
    expect(summary.entryChannel).toBe(entryChannel);
    return summary.id;
  }

  const createQuotation = (dealId, recipientType) =>
    apiWrite(sessions.sales, 'post', `/api/tickets/${dealId}/deal-quotations`, directQuotationBody(recipientType));

  async function summaryOf(dealId) {
    const response = await sessions.sales.get(`/api/tickets/${dealId}`);
    expect(response.status(), 'GET /api/tickets/{id}').toBe(200);
    return (await response.json()).ticket.summary;
  }

  async function directQuotationsOn(dealId) {
    const response = await sessions.sales.get(`/api/tickets/${dealId}/deal-quotations`);
    expect(response.status(), 'GET /api/tickets/{id}/deal-quotations').toBe(200);
    return (await response.json()).items.filter((q) => q.origin === 'DEAL_DIRECT');
  }

  test.beforeAll(async () => {
    sessions = await apiSessionsFor(['sales']);
    customerName = `บริษัท ลิงก์ดีล ${tag} จำกัด`;
    const customer = await apiWrite(sessions.sales, 'post', '/api/customers', { name: customerName });
    expect(customer.status(), 'POST /api/customers').toBe(200);
    customerId = (await customer.json()).customer.id;
    const project = await apiWrite(sessions.sales, 'post', `/api/customers/${customerId}/projects`, {
      name: `Deal-link ${tag}`,
    });
    expect(project.status(), 'POST /api/customers/{id}/projects').toBe(200);
    projectId = (await project.json()).project.id;
  });

  test.afterAll(async () => { await disposeSessions(sessions); });

  test('a direct quotation with a recipient becomes the deal\'s liveDirectQuotation; a second one is refused 409 naming it', async () => {
    const dealId = await newDeal('DESIGNER_LED');
    const before = await summaryOf(dealId);
    expect(before.liveDirectQuotation, 'a deal with no quotation serves an explicit null').toBeNull();

    const created = await createQuotation(dealId, 'DESIGNER');
    expect(created.status(), 'POST create with recipientType').toBe(201);
    const { quotation } = await created.json();
    expect(quotation).toMatchObject({
      recipientType: 'DESIGNER', recipientLabel: 'ผู้ออกแบบ', origin: 'DEAL_DIRECT', docStatus: 'DRAFT', ticketId: dealId,
    });

    const live = { id: quotation.id, number: quotation.number, docStatus: 'DRAFT', recipientType: 'DESIGNER' };
    const after = await summaryOf(dealId);
    expect(after.liveDirectQuotation, 'GET /api/tickets/{id}').toEqual(live);
    // D-2 (.design/quote-stage-gates, 2026-10-16): quoting a recipient moves the deal onto that
    // recipient's stage at creation. DESIGNER_LED routes through QUOTE_DESIGN_SIDE, so it moves.
    expect(before.salesStage).toBe('LEAD_APPROACH');
    expect(after.salesStage).toBe('QUOTE_DESIGN_SIDE');

    const list = await sessions.sales.get('/api/tickets');
    expect(list.status(), 'GET /api/tickets').toBe(200);
    const row = (await list.json()).tickets.find((t) => t.id === dealId);
    expect(row?.liveDirectQuotation, 'the list row carries the same object').toEqual(live);

    // N6 — the second create is refused, and the body names the live quotation at TOP level.
    const second = await createQuotation(dealId, 'OWNER');
    expect(second.status(), 'a second direct quotation on the same deal').toBe(409);
    const error = await second.json();
    expect(error).toEqual({
      message: `ดีลนี้มีใบเสนอราคาตรงที่ใช้งานอยู่ (${quotation.number}) — แก้ไขฉบับนั้น หรือสร้างฉบับแก้ไขแทนการออกเลขใหม่`,
      status: 409,
      liveQuotationId: quotation.id,
      number: quotation.number,
      docStatus: 'DRAFT',
    });
    expect(await directQuotationsOn(dealId), 'the refusal wrote nothing').toHaveLength(1);

    // Cancelling the live one lifts N6 — and the deal stops advertising it.
    const cancelled = await apiWrite(sessions.sales, 'post', `/api/deal-quotations/${quotation.id}/cancel`, {});
    expect(cancelled.status(), 'POST cancel').toBe(200);
    expect((await summaryOf(dealId)).liveDirectQuotation).toBeNull();
    const again = await createQuotation(dealId, 'OWNER');
    expect(again.status(), 'N6 lifts once the live quotation is cancelled').toBe(201);
    const replacement = (await again.json()).quotation;
    expect((await summaryOf(dealId)).liveDirectQuotation).toMatchObject({ id: replacement.id, recipientType: 'OWNER' });
    // D-2 again: the OWNER quotation moves the deal forward from ขั้น 4 to ขั้น 5.
    expect((await summaryOf(dealId)).salesStage).toBe('QUOTE_OWNER');

    const cleanup = await apiWrite(sessions.sales, 'post', `/api/deal-quotations/${replacement.id}/cancel`, {});
    expect(cleanup.status(), 'cancel what this test created').toBe(200);
  });

  test('a direct quotation without a recipient is refused 400, and nothing is written', async () => {
    const dealId = await newDeal('OWNER_DIRECT');

    const refused = await createQuotation(dealId, null);
    expect(refused.status(), 'no recipientType').toBe(400);
    expect(await refused.json()).toEqual({ message: 'ต้องระบุผู้รับใบเสนอราคา', status: 400 });

    const unspecified = await createQuotation(dealId, 'UNSPECIFIED');
    expect(unspecified.status(), 'UNSPECIFIED is never accepted from a client').toBe(400);

    expect(await directQuotationsOn(dealId)).toHaveLength(0);
    expect((await summaryOf(dealId)).liveDirectQuotation).toBeNull();
  });

  test('one pricing route per deal: a live คำขอราคา refuses a direct quotation 409', async () => {
    const dealId = await newDeal('BUYER_DIRECT');

    const pricingRequest = await apiWrite(sessions.sales, 'post', `/api/tickets/${dealId}/pricing-requests`, {
      recipientType: 'DESIGNER',
      recipientLabel: 'คุณออกแบบ (e2e)',
      targetCurrency: 'THB',
      clientRequestId: randomUUID(),
      items: [{
        brand: 'E2E',
        model: `${tag} model`,
        productDescription: `${tag} one-route e2e`,
        color: 'White',
        texture: 'Matte',
        size: '60x60',
        thicknessMm: 9,
        sqmPerPiece: 0.36,
        piecesPerBox: 4,
        quantityMode: 'AREA',
        areaSqm: 20,
        quantityType: 'CONFIRMED',
        originCountry: 'อิตาลี',
        leadTimeMinDays: 30,
        leadTimeMaxDays: 45,
      }],
    });
    expect(pricingRequest.status(), 'POST /api/tickets/{id}/pricing-requests').toBe(201);

    // What the deal picker and the deal page read to decide the same thing client-side.
    const listed = await sessions.sales.get(`/api/tickets/${dealId}/pricing-requests`);
    expect(listed.status()).toBe(200);
    const [row] = (await listed.json()).items;
    expect(row).toMatchObject({ ticketId: dealId, status: 'DRAFT' });

    const refused = await createQuotation(dealId, 'BUYER');
    expect(refused.status(), 'a direct quotation on a deal with a live pricing request').toBe(409);
    expect(await refused.json()).toEqual({ message: LIVE_PRICING_REQUEST_MESSAGE, status: 409 });
    expect(await directQuotationsOn(dealId)).toHaveLength(0);
  });
});
