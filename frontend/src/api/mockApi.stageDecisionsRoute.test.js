/**
 * The mock's `onRoute` mirror of TicketService#stageDecisions / DealRoute.
 *
 * ⚠️ Why this file exists. Until the mock emitted `onRoute`, every stage came back on-route under
 * `VITE_USE_MOCKS=true` — so the whole route feature was INVISIBLE in mock mode, and any
 * mock-driven test of it passed while proving nothing. That is the third failure shape in
 * CLAUDE.md's table ("mock OMITS a field the feature keys on"), and the one that only surfaces in
 * prod. These tests pin the field so it cannot silently regress.
 *
 * This is plumbing evidence only. Authorization and route ENFORCEMENT are proved against the real
 * Java service (DealRouteGateIntegrationTest), never here.
 */
import { describe, it, expect } from 'vitest';
import { api } from './mockApi.js';

/**
 * A DISTINCT deal per case. `setEntryChannel` mutates the shared mock store, so reusing one deal
 * leaks the previous test's channel into the next — that is how the UNSPECIFIED case first "failed".
 */
async function candidates() {
  await api.auth.login({ role: 'ceo' });
  const { tickets } = await api.tickets.list();
  const usable = tickets.filter((t) => (t.lifecycle ?? 'ACTIVE') === 'ACTIVE' && !t.quotationOnly && t.salesStage);
  expect(usable.length).toBeGreaterThanOrEqual(4);
  return usable;
}

async function decisionsWithChannel(entryChannel, slot = 0) {
  const deal = (await candidates())[slot];
  if (entryChannel !== 'UNSPECIFIED') {
    await api.tickets.setEntryChannel(deal.id, { value: entryChannel, note: 'ตั้งค่าสำหรับทดสอบ' });
  }
  const { stageDecisions } = await api.tickets.actions(deal.id);
  return { decisions: stageDecisions, salesStage: deal.salesStage };
}

const offRouteCodes = (decisions) => decisions.filter((d) => d.onRoute === false).map((d) => d.stage);

describe('mockStageDecisions — onRoute mirrors DealRoute', () => {
  it('emits onRoute on every decision, so the field is never simply absent', async () => {
    const { decisions } = await decisionsWithChannel('DESIGNER_LED', 0);
    expect(decisions.length).toBeGreaterThan(0);
    decisions.forEach((d) => expect(typeof d.onRoute).toBe('boolean'));
  });

  it('marks nothing off-route for DESIGNER_LED — the majority route is ungated', async () => {
    const { decisions } = await decisionsWithChannel('DESIGNER_LED', 0);
    expect(offRouteCodes(decisions)).toEqual([]);
  });

  it('marks exactly QUOTE_DESIGN_SIDE off-route for OWNER_DIRECT, with a reason', async () => {
    const { decisions, salesStage } = await decisionsWithChannel('OWNER_DIRECT', 1);
    const off = decisions.filter((d) => d.onRoute === false);
    expect(off.map((d) => d.stage)).toEqual(['QUOTE_DESIGN_SIDE']);
    expect(off[0].allowed).toBe(false);
    // The route reason only surfaces when an EARLIER rung hasn't already claimed it: rung 4
    // (target === current stage) fires first, exactly as in TicketService's ladder.
    if (salesStage !== 'QUOTE_DESIGN_SIDE') {
      expect(off[0].blockedReason).toContain('แก้ช่องทางดีล');
      expect(off[0].blockedReason).toContain('ขั้นที่ 4');
    }
  });

  it('marks the four party stages off-route for BUYER_DIRECT', async () => {
    const { decisions } = await decisionsWithChannel('BUYER_DIRECT', 2);
    expect(offRouteCodes(decisions))
      .toEqual(['QUOTE_DESIGN_SIDE', 'QUOTE_OWNER', 'OWNER_SIGNOFF', 'AWAITING_BUYER']);
  });

  it('gates NOTHING for UNSPECIFIED — legacy deals must never brick (design §13.1c)', async () => {
    const { decisions } = await decisionsWithChannel('UNSPECIFIED', 3);
    expect(offRouteCodes(decisions)).toEqual([]);
  });

  it('never marks an operational stage off-route, whatever the channel', async () => {
    // Mirrors DealRoute.ROUTE_VARIABLE: only S4–S8 may ever vary by route. If an auto-advanced
    // stage could go off-route, a confirmed order would silently fail to move the deal.
    const channels = ['DESIGNER_LED', 'OWNER_DIRECT', 'BUYER_DIRECT', 'UNSPECIFIED'];
    for (let slot = 0; slot < channels.length; slot += 1) {
      const { decisions } = await decisionsWithChannel(channels[slot], slot);
      const offCodes = offRouteCodes(decisions);
      ['NEGOTIATION', 'ORDER_RECEIVED', 'DEPOSIT_RECEIVED', 'PROCUREMENT',
        'DELIVERY_SCHEDULING', 'DELIVERED', 'CLOSED_PAID'].forEach((stage) => {
        expect(offCodes).not.toContain(stage);
      });
    }
  });
});
