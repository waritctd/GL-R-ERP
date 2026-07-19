import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// Guards mockApi.js's pricingRequests.create/update item validation directly
// against the mock module (not just through the create modal's own client-side
// checks) — see CLAUDE.md "Mock API contract": a mock that is MORE permissive
// than production is the dangerous direction (issue #199). The real backend's
// PricingRequestRequests.PricingRequestItemRequest declares
// @NotNull @DecimalMin("0.0001") requestedQty and @NotBlank requestedUnit as
// Bean Validation, enforced before PricingRequestService ever runs — so even a
// caller that bypasses the UI (a script, a future component) must still be
// rejected by the mock the same way the real backend would 400 it.

async function ownedActiveTicketWithItems() {
  await api.auth.login({ role: 'sales' });
  const { tickets } = await api.tickets.list();
  const candidate = tickets.find((t) => t.itemCount > 0 && t.lifecycle === 'ACTIVE');
  expect(candidate).toBeTruthy();
  const { ticket } = await api.tickets.get(candidate.id);
  return ticket;
}

function validPayload(sourceItem) {
  return {
    recipientType: 'DESIGNER',
    recipientLabel: 'ผู้ออกแบบทดสอบ',
    items: [{
      sourceTicketItemId: sourceItem.id,
      brand: sourceItem.brand,
      model: sourceItem.model,
      requestedQty: 10,
      requestedUnit: 'แผ่น',
      quantityType: 'ESTIMATE',
    }],
  };
}

describe('mockApi.pricingRequests.create item validation', () => {
  it('rejects a blank requestedUnit, mirroring PricingRequestItemRequest\'s @NotBlank requestedUnit', async () => {
    const ticket = await ownedActiveTicketWithItems();
    const payload = validPayload(ticket.items[0]);
    payload.items[0].requestedUnit = '   '; // blank after trim
    await expect(api.pricingRequests.create(ticket.summary.id, payload)).rejects.toThrow();
  });

  it('rejects a zero/negative requestedQty, mirroring @DecimalMin("0.0001") requestedQty', async () => {
    const ticket = await ownedActiveTicketWithItems();
    const payload = validPayload(ticket.items[0]);
    payload.items[0].requestedQty = 0;
    await expect(api.pricingRequests.create(ticket.summary.id, payload)).rejects.toThrow();
  });

  it('accepts a valid item (sanity check the validation above is not over-rejecting)', async () => {
    const ticket = await ownedActiveTicketWithItems();
    const payload = validPayload(ticket.items[0]);
    const { pricingRequest } = await api.pricingRequests.create(ticket.summary.id, payload);
    expect(pricingRequest.summary.status).toBe('DRAFT');
    expect(pricingRequest.items[0].requestedUnit).toBe('แผ่น');
  });

  it('update() rejects a blank requestedUnit on an existing draft the same way create() does', async () => {
    const ticket = await ownedActiveTicketWithItems();
    const created = await api.pricingRequests.create(ticket.summary.id, validPayload(ticket.items[0]));
    const draftId = created.pricingRequest.summary.id;
    const badPayload = validPayload(ticket.items[0]);
    badPayload.items[0].requestedUnit = '';
    await expect(api.pricingRequests.update(draftId, badPayload)).rejects.toThrow();
  });

  // Mirrors PricingRequestService.validateItems (Part 1 of the review-remediation
  // plan): brand alone does not identify a product. Without this check, the
  // mock would be MORE permissive than the real backend (issue #199's failure
  // mode) — it would happily persist a line that says nothing more than
  // "1 แผ่น" and hand it to Import.
  it('rejects an item with no identity field (brand alone is not enough)', async () => {
    const ticket = await ownedActiveTicketWithItems();
    const payload = validPayload(ticket.items[0]);
    payload.items[0].sourceTicketItemId = null;
    payload.items[0].model = null; // brand alone remains — not sufficient
    await expect(api.pricingRequests.create(ticket.summary.id, payload)).rejects.toThrow();
  });

  it('accepts an item identified only by specialRequirement, with no sourceTicketItemId/model/brand', async () => {
    const ticket = await ownedActiveTicketWithItems();
    const payload = validPayload(ticket.items[0]);
    payload.items[0].sourceTicketItemId = null;
    payload.items[0].brand = null;
    payload.items[0].model = null;
    payload.items[0].specialRequirement = 'กระเบื้องลายไม้สีเข้ม ผิวด้าน';
    const { pricingRequest } = await api.pricingRequests.create(ticket.summary.id, payload);
    expect(pricingRequest.summary.status).toBe('DRAFT');
  });

  // submit()'s own re-check against the persisted items (mirroring
  // PricingRequestService.submit) is exercised on the backend
  // (PricingRequestServiceTest#submit_rejectsPreExistingDraftWithUnidentifiedItem)
  // — there is no way to reach that state through this mock's public API
  // surface, since create()/update() both already reject an unidentified item
  // and delay() structuredClone()s every response, so a returned item can't be
  // mutated back into the mock's own store. That asymmetry is expected: the
  // real-world scenario is a row that predates this rule in a persisted
  // database, which a fresh in-memory mock session has no equivalent of.
});
