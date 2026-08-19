import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

// Pins mockApi's RECORD_PARTIAL_DELIVERY/COMPLETE_DELIVERY advertisement to the SAME predicate
// the real service uses: TicketService#canRecordDelivery -> canWriteDelivery ->
// isFulfilmentOrOwningRep = FULFILMENT_ROLES {import, ceo} ∪ (SALES_ROLES {"sales"} ∧ deal owner).
//
// Why this file exists at all: stages 13-14 moved to Sales in #818 (backend) but the mock kept
// gating the ADVERTISEMENT to ['import','ceo'], so a sales rep following the new "บันทึกส่งมอบ"
// CTA reached the fulfilment panel and found no button — the mutation endpoints would have
// accepted them. That is the "mock omits what the feature keys on" shape CLAUDE.md warns about:
// every existing delivery test stubs `availableActions` directly (see TicketDetailPage.test.jsx),
// so nothing drove the mock's own gate and the drift was invisible.
//
// NOT authz evidence about production — CLAUDE.md is explicit that mock authz is never
// authoritative. The real gate is proven by DeliveryAuthzIntegrationTest against real Postgres.
// These cases only prove the MIRROR has not drifted, which is the thing that actually broke.
//
// Seed facts this leans on (frontend/src/data/demoData.js): tickets 13 + 14 are both
// createdById 6 (sales@glr.co.th) at fulfillmentStatus GOODS_RECEIVED; sales2@glr.co.th (id 12)
// is a SECOND sales rep, which is what makes the non-owner case real rather than hypothetical.
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

  it('still advertises them to import — #818 was additive, not a transfer', async () => {
    expect(await deliveryActionsFor({ role: 'import' })).toEqual(DELIVERY_ACTIONS);
  });

  // The two that matter (wrong-way-round): a green suite is worthless if it only ever asserts
  // that someone CAN reach what they own.
  it('does NOT advertise them to a sales rep who does not own the deal', async () => {
    // Stronger than "no delivery action": a non-owning rep cannot READ the deal at all —
    // requireTicketViewer 403s before any action list is built, so the advertisement gate is
    // never even reached. Asserted as the rejection it actually is rather than as an empty
    // array, which would have passed for the wrong reason.
    await api.auth.login({ email: 'sales2@glr.co.th', password: 'demo1234' });
    await expect(api.tickets.actions(OWNED_DELIVERY_READY_TICKET))
      .rejects.toThrow('ไม่มีสิทธิ์เข้าถึงรายการนี้');
  });

  it('does NOT advertise them to sales_manager — read+comment oversight only', async () => {
    // ROLE_PERMISSIONS says sales_manager "must never be added to" write permissions, and the
    // Java SALES_ROLES is Set.of("sales"). The mock's `dealOwner` helper DOES admit
    // sales_manager, so using it here instead of `owner` would make the mock MORE permissive
    // than production — the one direction CLAUDE.md calls dangerous.
    expect(await deliveryActionsFor({ role: 'sales_manager' })).toEqual([]);
  });
});
