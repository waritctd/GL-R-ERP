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

// ── What the picker is offered follows the owner rules of 2026-10-05 (stage movement, M1–M5) ───────────
//
//   M1  a hand move exists only inside ขั้น 1–9, only onto ขั้น 1, 2, 3, 6, 7 and 9 — and only onto the
//       stages on the deal's route;
//   M2  ขั้น 4, 5 and 8 are never set by hand: a quotation created for that party is what takes the deal there;
//   M4  from ขั้น 10 the stage is the system's, for every role — and a deal SITTING at ขั้น 10 or later cannot
//       be moved by hand at all, not even backward;
//   M5  what the screen is told follows that: nothing forbidden is `allowed`, nothing forbidden is advertised
//       as ADVANCE_STAGE, and a deal with nothing left to offer advertises no UPDATE_STAGE either.
//
// Plumbing evidence only (CLAUDE.md "Mock API contract"): the Java service's own evidence is
// StageDecisionIntegrationTest / StageFactGateIntegrationTest / DealStageQuoteOwnerAndRouteIntegrationTest.
// Today the mock still offers the owning rep ขั้น 4, 5, 8 and 13, and offers any stage at all on a deal that
// already sits at ขั้น 10 or later — the tests below are red until it stops.
const HAND_SETTABLE_FROM_THE_START = {
  // From ขั้น 1, so ขั้น 1 itself is not on offer. ขั้น 6 and 7 are off the BUYER_DIRECT route.
  DESIGNER_LED: ['AWAITING_BUYER', 'NEGOTIATION', 'OWNER_SIGNOFF', 'PRESENTATION', 'SPEC_APPROVED'],
  OWNER_DIRECT: ['AWAITING_BUYER', 'NEGOTIATION', 'OWNER_SIGNOFF', 'PRESENTATION', 'SPEC_APPROVED'],
  BUYER_DIRECT: ['NEGOTIATION', 'PRESENTATION', 'SPEC_APPROVED'],
};

/**
 * A fresh deal owned by the mock `sales` user, at ขั้น 1, with the tracking gate satisfied (a next follow-up date and
 * one logged activity) — so the tracking gate is never what refuses a move, and a refusal below can only be the
 * rules'. A DISTINCT deal per case: the mock store is shared across tests.
 */
async function readyDeal(entryChannel) {
  await api.auth.login({ role: 'sales' });
  const { ticket } = await api.tickets.create({
    entryChannel, title: 'ดีลทดสอบการเลือกขั้นตอน', customerName: 'ลูกค้า', projectId: 1, contactId: null,
    priority: 'NORMAL', items: [], nextFollowUpAt: '2026-10-07',
  });
  await api.tickets.addActivity(ticket.summary.id, { activityDate: '2026-10-05', kind: 'CALL' });
  return ticket.summary.id;
}

const stageOf = async (id) => (await api.tickets.get(id)).ticket.summary.salesStage;
const stageChangedCount = async (id) => (await api.tickets.get(id)).ticket.events.filter((e) => e.kind === 'STAGE_CHANGED').length;
const allowedStages = (decisions) => decisions.filter((d) => d.allowed).map((d) => d.stage);

/** Settles to 'ACCEPTED' or to the refusal itself, so a failure reads "must be refused" instead of dumping the ticket. */
const outcomeOf = (promise) => promise.then(() => 'ACCEPTED', (error) => error);

describe('mock stage decisions — what the picker is offered follows the owner rules (M1–M5)', () => {
  // Green first: the fixtures below really are movable, so a refusal further down is the rules' and nothing else's.
  it('a hand move onto AWAITING_BUYER (ขั้น 7) is accepted for the owning rep', async () => {
    const id = await readyDeal('DESIGNER_LED');
    await api.tickets.updateStage(id, { stage: 'AWAITING_BUYER', note: 'ข้ามไปรอผู้ซื้อ' });
    expect(await stageOf(id)).toBe('AWAITING_BUYER');
  });

  it.each(Object.entries(HAND_SETTABLE_FROM_THE_START))(
    'on a %s deal at ขั้น 1 the owning rep is offered exactly the hand-settable stages on its route, and only those are advertised',
    async (channel, expected) => {
      const id = await readyDeal(channel);
      const { stageDecisions, availableActions } = await api.tickets.actions(id);
      const offered = allowedStages(stageDecisions).sort();
      const advertised = availableActions.filter((a) => a.action === 'ADVANCE_STAGE').map((a) => a.targetStage).sort();

      // Holds today: every hand-settable stage is on offer, so what is red below is only the EXTRA stages.
      expect(offered, `the hand-settable stages are on offer on a ${channel} deal`).toEqual(expect.arrayContaining(expected));
      expect.soft(offered, `stages offered on a ${channel} deal`).toEqual(expected);
      expect.soft(advertised, `ADVANCE_STAGE targets advertised on a ${channel} deal`).toEqual(expected);
    },
  );

  it.each([
    ['QUOTE_OWNER', 'ขั้น 5'],
    ['DELIVERY_SCHEDULING', 'ขั้น 13'],
  ])('a hand move onto %s (%s) is refused for the owning rep, and the stage does not change', async (stage) => {
    const id = await readyDeal('DESIGNER_LED');
    expect(await stageOf(id), 'a fresh deal starts at ขั้น 1').toBe('LEAD_APPROACH');
    const eventsBefore = await stageChangedCount(id);

    const outcome = await outcomeOf(api.tickets.updateStage(id, { stage, note: 'ย้ายขั้นตอนด้วยมือ' }));

    expect.soft(outcome, `a hand move onto ${stage} must be refused`).not.toBe('ACCEPTED');
    expect.soft(await stageOf(id), 'the stage must not have moved').toBe('LEAD_APPROACH');
    expect.soft(await stageChangedCount(id), 'no STAGE_CHANGED may be written').toBe(eventsBefore);
  });

  // One seed deal per band (first, middle, last of ขั้น 10–15) — the lowest id, so a deal an earlier test left at
  // ขั้น 10+ by hand (the mock accepts that today) is never the one picked. Each `actions` call costs ~150 ms in
  // the mock, which is why this is three cases and not every deal in the seed.
  it.each(['ORDER_RECEIVED', 'PROCUREMENT', 'CLOSED_PAID'])(
    'a deal sitting at %s (ขั้น 10 or later) is offered no stage — not even a backward one — and advertises no stage verb',
    async (sittingAt) => {
      await api.auth.login({ role: 'sales' });
      const { tickets } = await api.tickets.list();
      const deal = tickets
        .filter((t) => (t.lifecycle ?? 'ACTIVE') === 'ACTIVE' && t.salesStage === sittingAt)
        .sort((a, b) => a.id - b.id)[0];
      expect(deal, `the owning rep's seed has a live deal at ${sittingAt}`).toBeTruthy();
      const where = `deal ${deal.id} sitting at ${sittingAt}`;

      const { stageDecisions, availableActions } = await api.tickets.actions(deal.id);

      // Holds today: the answer is real and the owning rep is still offered its other verbs, so a missing stage
      // verb below is not just an empty response.
      expect(availableActions.length, `verbs advertised on ${where}`).toBeGreaterThan(0);
      expect.soft(allowedStages(stageDecisions), `stages offered on ${where}`).toEqual([]);
      expect.soft(availableActions.map((a) => a.action), `verbs advertised on ${where}`).not.toContain('UPDATE_STAGE');
    },
  );
});
