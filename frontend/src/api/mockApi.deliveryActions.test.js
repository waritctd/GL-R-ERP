import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// Pins mockApi's RECORD_PARTIAL_DELIVERY/COMPLETE_DELIVERY advertisement to the SAME predicate the real
// service uses: TicketService#canRecordDelivery -> canWriteDelivery = CEO ∪ (SALES_ROLES {"sales"} ∧ deal
// owner).
const DELIVERY_ACTIONS = ['RECORD_PARTIAL_DELIVERY', 'COMPLETE_DELIVERY'];
const OWNED_DELIVERY_READY_TICKET = 13;

async function deliveryActionsFor(loginPayload, ticketId = OWNED_DELIVERY_READY_TICKET) {
  await api.auth.login(loginPayload);
  const { availableActions } = await api.tickets.actions(ticketId);
  return availableActions.map((a) => a.action).filter((a) => DELIVERY_ACTIONS.includes(a));
}

describe('mockApi tickets.actions — delivery advertisement mirrors canWriteDelivery', () => {
  it('advertises both delivery actions to the OWNING sales rep', async () => {
    expect(await deliveryActionsFor({ email: 'sales@glr.co.th', password: 'demo1234' }))
      .toEqual(DELIVERY_ACTIONS);
  });

  it('does NOT advertise them to import — the 2026-08-17/V184 ruling was a TRANSFER to Sales, not an addition', async () => {
    expect(await deliveryActionsFor({ role: 'import' })).toEqual([]);
  });

  // The two that matter (wrong-way-round): a green suite is worthless if it only ever asserts that someone CAN
  // reach what they own.
  it('does NOT advertise them to a sales rep who does not own the deal', async () => {
    // Stronger than "no delivery action": a non-owning rep cannot READ the deal at all — requireTicketViewer 403s
    // before any action list is built, so the advertisement gate is never even reached.
    await api.auth.login({ email: 'sales2@glr.co.th', password: 'demo1234' });
    await expect(api.tickets.actions(OWNED_DELIVERY_READY_TICKET))
      .rejects.toThrow('ไม่มีสิทธิ์เข้าถึงรายการนี้');
  });

  it('does NOT advertise them to sales_manager — read+comment oversight only', async () => {
    // ROLE_PERMISSIONS says sales_manager "must never be added to" write permissions, and the Java SALES_ROLES is
    // Set.of("sales").
    expect(await deliveryActionsFor({ role: 'sales_manager' })).toEqual([]);
  });
});

// Mirrors TicketService#deliveryMessage: the DELIVERY_RECORDED event names each line by the item's
// brand + model + size (not its row id) so the history reads for a person.
describe('mockApi tickets.recordDelivery — event message names the line', () => {
  it('writes "brand model size: delivered/ordered", not "<itemId>: …"', async () => {
    await api.auth.login({ email: 'sales@glr.co.th', password: 'demo1234' });
    const before = (await api.tickets.get(OWNED_DELIVERY_READY_TICKET)).ticket;
    const item = before.items[0];
    await api.tickets.recordDelivery(OWNED_DELIVERY_READY_TICKET, {
      source: 'WAREHOUSE', lines: [{ itemId: item.id, qty: 1 }],
    });

    const after = (await api.tickets.get(OWNED_DELIVERY_READY_TICKET)).ticket;
    const event = [...after.events].reverse().find((e) => e.kind === 'DELIVERY_RECORDED');
    const name = [item.brand, item.model, item.size].filter(Boolean).join(' ');
    expect(event.message).toContain(`${name}: `);
    expect(event.message.startsWith(`${item.id}: `)).toBe(false);
  });
});

