import { describe, expect, it } from 'vitest';
import { api, patchTicketForTests } from './mockApi.js';
import { addDaysIso, bangkokTodayIso } from '../utils/format.js';

// Slice 6d in the mock: ส่งตัวอย่าง (SAMPLE_SENT, TicketService#addActivity) and วันที่ส่ง (RecordDeliveryRequest
// .deliveredOn, TicketService#backDatedDeliveryInstant). The mock's authz is NOT evidence about the Java service —
// DealActivitySampleSentIntegrationTest and DeliveryBackDateIntegrationTest are (real Postgres). This file pins the
// mock's SHAPE and that it drops nothing the real API honours: the note rule, the 7-day window and the stored time.

const today = bangkokTodayIso();
const DELIVERABLE = 13; // owned by sales@glr.co.th, goods received: a WAREHOUSE delivery is possible

async function expectStatus(promise, status) {
  await expect(promise).rejects.toMatchObject({ status });
}

async function ownDeal() {
  await api.auth.login({ role: 'sales' });
  const [deal] = (await api.salesBoard.myWork()).board;
  return deal.ticketId;
}

describe('mockApi — ส่งตัวอย่าง (SAMPLE_SENT)', () => {
  it('records like a call: the kind, what was sent, and the optional follow-up date', async () => {
    const ticketId = await ownDeal();
    const logged = await api.tickets.addActivity(ticketId, {
      kind: 'SAMPLE_SENT', activityDate: today, note: 'ตัวอย่าง Raku 3 สี', nextFollowUpAt: addDaysIso(today, 7),
    });
    expect(logged).toMatchObject({ kind: 'SAMPLE_SENT', note: 'ตัวอย่าง Raku 3 สี', ticketId });
    expect((await api.tickets.get(ticketId)).ticket.summary.nextFollowUpAt).toBe(addDaysIso(today, 7));
  });

  it('what was sent is required: blank or missing is 400 and nothing is written', async () => {
    const ticketId = await ownDeal();
    const before = (await api.tickets.listActivities(ticketId)).items.length;
    for (const note of [undefined, '', '   ']) {
      await expectStatus(api.tickets.addActivity(ticketId, { kind: 'SAMPLE_SENT', activityDate: today, note }), 400);
    }
    expect((await api.tickets.listActivities(ticketId)).items.length).toBe(before);
  });

  it('becomes the follow-up row\'s last touch (BoardDeal.lastActivityKind / lastActivityNote)', async () => {
    const ticketId = await ownDeal();
    await api.tickets.addActivity(ticketId, { kind: 'SAMPLE_SENT', activityDate: today, note: 'แคตตาล็อก Mutina' });
    const row = (await api.salesBoard.myWork()).board.find((d) => d.ticketId === ticketId);
    expect([row.lastActivityKind, row.lastActivityNote]).toEqual(['SAMPLE_SENT', 'แคตตาล็อก Mutina']);
  });

  // Fix round 1 (review L3): Java checks the kind and the note BEFORE it reads the deal (TicketService#addActivity),
  // so an outsider with a blank note gets the 400, not the 403. The mock now checks in the same order.
  it('checks in the Java order: a blank ส่งตัวอย่าง note is 400 even from someone who could not write the deal', async () => {
    const ticketId = await ownDeal();
    await api.auth.login({ email: 'sales2@glr.co.th', password: 'demo1234' });
    await expectStatus(api.tickets.addActivity(ticketId, { kind: 'SAMPLE_SENT', activityDate: today, note: '  ' }), 400);
    await expectStatus(api.tickets.addActivity(ticketId, { kind: 'NOT_A_KIND', activityDate: today, note: 'x' }), 400);
  });

  it('a non-owning rep is refused (not MORE permissive than requireDealOwnership)', async () => {
    const ticketId = await ownDeal();
    await api.auth.login({ email: 'sales2@glr.co.th', password: 'demo1234' });
    await expectStatus(api.tickets.addActivity(ticketId, { kind: 'SAMPLE_SENT', activityDate: today, note: 'x' }), 403);
  });
});

describe('mockApi — วันที่ส่ง (deliveredOn)', () => {
  async function deliver(deliveredOn) {
    await api.auth.login({ email: 'sales@glr.co.th', password: 'demo1234' });
    const item = (await api.tickets.get(DELIVERABLE)).ticket.items[0];
    return api.tickets.recordDelivery(DELIVERABLE, {
      source: 'WAREHOUSE', lines: [{ itemId: item.id, qty: 1 }], ...(deliveredOn === undefined ? {} : { deliveredOn }),
    });
  }
  const records = async () => {
    await api.auth.login({ email: 'sales@glr.co.th', password: 'demo1234' });
    return (await api.tickets.listDeliveries(DELIVERABLE)).items;
  };

  it('a past day up to 7 back is stored at 12:00 Bangkok on that day, and the history says the day', async () => {
    const before = (await records()).length;
    await deliver(addDaysIso(today, -7));
    const added = (await records()).filter((r) => r.deliveredAt === `${addDaysIso(today, -7)}T05:00:00.000Z`);
    expect(added).toHaveLength(1);
    expect((await records()).length).toBe(before + 1);
    const event = [...(await api.tickets.get(DELIVERABLE)).ticket.events].reverse().find((e) => e.kind === 'DELIVERY_RECORDED');
    expect(event.message).toContain('วันที่ส่ง ');
  });

  it('8 days back and tomorrow are 400 and write nothing', async () => {
    const before = (await records()).length;
    await expectStatus(deliver(addDaysIso(today, -8)), 400);
    await expectStatus(deliver(addDaysIso(today, 1)), 400);
    expect((await records()).length).toBe(before);
  });

  it('no date, or today, stamps the moment it was recorded (deliveredAt === createdAt)', async () => {
    for (const deliveredOn of [undefined, today]) {
      await deliver(deliveredOn);
      const last = (await records()).reduce((a, b) => (b.deliveryId > a.deliveryId ? b : a));
      expect(last.deliveredAt).toBe(last.createdAt);
    }
  });

  it('lists records in delivery-date order, as findDeliveriesByTicket does (ORDER BY delivered_at, delivery_id)', async () => {
    await deliver(addDaysIso(today, -2));
    const listed = (await records()).map((r) => r.deliveredAt);
    expect(listed).toEqual([...listed].sort());
  });

  it('checks the date before the source, as Java does', async () => {
    await api.auth.login({ email: 'sales@glr.co.th', password: 'demo1234' });
    const item = (await api.tickets.get(DELIVERABLE)).ticket.items[0];
    await expect(api.tickets.recordDelivery(DELIVERABLE, {
      source: 'NOWHERE', lines: [{ itemId: item.id, qty: 1 }], deliveredOn: addDaysIso(today, 1),
    })).rejects.toMatchObject({ status: 400, message: expect.stringContaining('อนาคต') });
  });

  // Fix round 1 (M2): the one-click ส่งมอบครบ takes the same date (CompleteDeliveryRequest.deliveredOn).
  it('completeDelivery takes deliveredOn too: a past day is stored at 12:00 Bangkok, 8 back is 400', async () => {
    await api.auth.login({ email: 'sales@glr.co.th', password: 'demo1234' });
    await expectStatus(api.tickets.completeDelivery(DELIVERABLE, { deliveredOn: addDaysIso(today, -8) }), 400);
    await api.tickets.completeDelivery(DELIVERABLE, { deliveredOn: addDaysIso(today, -1) });
    const last = (await records()).reduce((a, b) => (b.deliveryId > a.deliveryId ? b : a));
    expect(last.deliveredAt).toBe(`${addDaysIso(today, -1)}T05:00:00.000Z`);
  });

  it('a rep who does not own the deal is refused before the date is looked at', async () => {
    await api.auth.login({ email: 'sales2@glr.co.th', password: 'demo1234' });
    const item = { id: 1 };
    await expectStatus(api.tickets.recordDelivery(DELIVERABLE, {
      source: 'WAREHOUSE', lines: [{ itemId: item.id, qty: 1 }], deliveredOn: addDaysIso(today, -30),
    }), 403);
  });
});

// Fix round 1 (L1): never before the Bangkok day the order was confirmed (TicketRepository.ORDER_CONFIRMED_AT_SQL).
describe('mockApi — วันที่ส่ง is not before the order confirmation', () => {
  it('the day before is 400 naming the day; the day itself is accepted', async () => {
    await api.auth.login({ email: 'sales@glr.co.th', password: 'demo1234' });
    const { ticket } = await api.tickets.get(DELIVERABLE);
    const confirmedOn = addDaysIso(today, -3);
    // 00:30 Bangkok on that day = 17:30 UTC the day before.
    // Earlier cases delivered this deal in full: give it its quantities back so a delivery is possible again.
    patchTicketForTests(DELIVERABLE, {
      fulfillmentStatus: 'GOODS_RECEIVED', items: ticket.items.map((i) => ({ ...i, qtyDelivered: 0 })),
    });
    patchTicketForTests(DELIVERABLE, { events: [...ticket.events,
      { id: 99001, kind: 'CUSTOMER_CONFIRMED', createdAt: `${addDaysIso(confirmedOn, -1)}T17:30:00.000Z` }] });
    const item = ticket.items[0];
    const send = (deliveredOn) => api.tickets.recordDelivery(DELIVERABLE, {
      source: 'WAREHOUSE', lines: [{ itemId: item.id, qty: 1 }], deliveredOn,
    });
    await expect(send(addDaysIso(confirmedOn, -1))).rejects.toMatchObject({
      status: 400, message: expect.stringContaining('ยืนยันคำสั่งซื้อ'),
    });
    await send(confirmedOn);
  });
});
