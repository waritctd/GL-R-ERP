import { describe, it, expect } from 'vitest';
import { api, patchTicketForTests } from './mockApi.js';
import { bangkokTodayIso } from '../utils/format.js';

// Slices 6.4 and 6.5 in the mock. These mirror TicketService#addActivity / #requireVisitFollowUpWritable and
// VisitService#plannedOnDeal / VisitRepository#findPlannedOnDeal(OwnedBy). The mock's authz is NOT evidence about the
// Java service — DealActivityFollowUpIntegrationTest, VisitFollowUpIntegrationTest and
// DealPlannedVisitsScopeIntegrationTest are. This file pins the mock's SHAPE, its ORDER BY, that it drops nothing
// the real API honours, and that its gates are not MORE permissive than the Java ones on the cases those tests pin.

const today = bangkokTodayIso();
const plusDays = (iso, n) => {
  const d = new Date(`${iso}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
};

async function expectStatus(promise, status) {
  await expect(promise).rejects.toMatchObject({ status });
}

async function ownDeal() {
  const { user } = await api.auth.login({ role: 'sales' });
  const [deal] = (await api.salesBoard.myWork()).board;
  return { user, ticketId: deal.ticketId };
}

async function summaryOf(ticketId) {
  await api.auth.login({ role: 'ceo' });
  return (await api.tickets.get(ticketId)).ticket.summary;
}

describe('mockApi — slice 6.4 follow-up date from a call', () => {
  it('a call with nextFollowUpAt writes only the follow-up date; the tracking names stay', async () => {
    const { ticketId } = await ownDeal();
    patchTicketForTests(ticketId, { designerName: 'สตูดิโอเส้นตรง', ownerName: 'คุณเจ้าของ', buyerName: 'บริษัท ผู้ซื้อ' });
    await api.auth.login({ role: 'sales' });

    await api.tickets.addActivity(ticketId, { kind: 'CALL', activityDate: today, note: 'คุยแล้ว', nextFollowUpAt: plusDays(today, 7) });

    const s = await summaryOf(ticketId);
    expect(s.nextFollowUpAt).toBe(plusDays(today, 7));
    expect([s.designerName, s.ownerName, s.buyerName]).toEqual(['สตูดิโอเส้นตรง', 'คุณเจ้าของ', 'บริษัท ผู้ซื้อ']);
  });

  it('a call without it leaves the date as it was', async () => {
    const { ticketId } = await ownDeal();
    patchTicketForTests(ticketId, { nextFollowUpAt: plusDays(today, 20) });
    await api.auth.login({ role: 'sales' });
    await api.tickets.addActivity(ticketId, { kind: 'CALL', activityDate: today, note: 'ไม่รับสาย' });
    expect((await summaryOf(ticketId)).nextFollowUpAt).toBe(plusDays(today, 20));
  });

  it('a past date is 400 and a non-ACTIVE deal is 409 — and neither writes the activity', async () => {
    const { ticketId } = await ownDeal();
    const before = (await api.tickets.listActivities(ticketId)).items.length;
    await expectStatus(api.tickets.addActivity(ticketId,
      { kind: 'CALL', activityDate: today, nextFollowUpAt: plusDays(today, -1) }), 400);
    patchTicketForTests(ticketId, { lifecycle: 'ON_HOLD' });
    try {
      await expectStatus(api.tickets.addActivity(ticketId,
        { kind: 'CALL', activityDate: today, nextFollowUpAt: plusDays(today, 3) }), 409);
    } finally {
      patchTicketForTests(ticketId, { lifecycle: 'ACTIVE' });
    }
    expect((await api.tickets.listActivities(ticketId)).items.length).toBe(before);
  });
});

describe('mockApi — slice 6.4 follow-up date from a visit', () => {
  it('a done visit on the rep\'s own deal sets it, on both the tick and the one-step log', async () => {
    const { ticketId } = await ownDeal();
    const visit = await api.salesVisits.create({ ticketId, contactName: 'ก', plannedDate: today });
    await api.salesVisits.done(visit.id, { nextFollowUpAt: plusDays(today, 3) });
    expect((await summaryOf(ticketId)).nextFollowUpAt).toBe(plusDays(today, 3));

    await api.auth.login({ role: 'sales' });
    await api.salesVisits.create({ ticketId, contactName: 'Walk-in 6.4', plannedDate: today, done: true,
      nextFollowUpAt: plusDays(today, 14) });
    expect((await summaryOf(ticketId)).nextFollowUpAt).toBe(plusDays(today, 14));
  });

  it('refuses it on a visit with no deal (400) and on a plan that is not done (400), writing nothing', async () => {
    const { ticketId } = await ownDeal();
    const loose = await api.salesVisits.create({ contactName: 'ไม่มีงาน', plannedDate: today });
    await expectStatus(api.salesVisits.done(loose.id, { nextFollowUpAt: plusDays(today, 3) }), 400);
    await expectStatus(api.salesVisits.create({ contactName: 'x', plannedDate: today, done: true,
      nextFollowUpAt: plusDays(today, 3) }), 400);
    await expectStatus(api.salesVisits.create({ ticketId, contactName: 'x', plannedDate: plusDays(today, 1),
      nextFollowUpAt: plusDays(today, 3) }), 400);
    const listed = await api.salesVisits.list({ from: today, to: plusDays(today, 1) });
    expect(listed.find((v) => v.id === loose.id).status).toBe('PLANNED');
    expect(listed.some((v) => v.contactName === 'x')).toBe(false);
  });

  it('refuses it (403) where the tick stays on the visit — a manager\'s own visit on a rep\'s deal', async () => {
    const { ticketId } = await ownDeal();
    const original = (await summaryOf(ticketId)).nextFollowUpAt;
    await api.auth.login({ role: 'sales_manager' });
    const own = await api.salesVisits.create({ ticketId, contactName: 'ผู้จัดการไปเอง', plannedDate: today });
    await expectStatus(api.salesVisits.done(own.id, { nextFollowUpAt: plusDays(today, 3) }), 403);
    expect((await api.salesVisits.team({ from: today, to: today })).find((v) => v.id === own.id).status).toBe('PLANNED');
    expect((await summaryOf(ticketId)).nextFollowUpAt).toBe(original);
  });
});

describe('mockApi — slice 6b fix round 1', () => {
  it('a one-step log repeated within a minute with a date applies the date, without a second visit', async () => {
    const { ticketId } = await ownDeal();
    const first = await api.salesVisits.create({ ticketId, contactName: 'ซ้ำ 6b', plannedDate: today, done: true });
    const again = await api.salesVisits.create({ ticketId, contactName: 'ซ้ำ 6b', plannedDate: today, done: true,
      nextFollowUpAt: plusDays(today, 9) });
    expect(again.id).toBe(first.id);
    expect((await summaryOf(ticketId)).nextFollowUpAt).toBe(plusDays(today, 9));
  });

  it('VisitDto.dealFollowUpOpen: own visit on an ACTIVE own deal only', async () => {
    const { ticketId } = await ownDeal();
    expect((await api.salesVisits.create({ ticketId, contactName: 'ก', plannedDate: today })).dealFollowUpOpen).toBe(true);
    expect((await api.salesVisits.create({ contactName: 'ข', plannedDate: today })).dealFollowUpOpen).toBe(false);
    await api.auth.login({ role: 'sales_manager' });
    expect((await api.salesVisits.create({ ticketId, contactName: 'ค', plannedDate: today })).dealFollowUpOpen).toBe(false);
  });

  it('a date refused on a paused deal says so in Thai', async () => {
    const { ticketId } = await ownDeal();
    patchTicketForTests(ticketId, { lifecycle: 'ON_HOLD' });
    try {
      await expect(api.tickets.addActivity(ticketId, { kind: 'CALL', activityDate: today, nextFollowUpAt: plusDays(today, 3) }))
        .rejects.toMatchObject({ status: 409, message: expect.stringContaining('ตั้งวันตามครั้งถัดไปไม่ได้') });
    } finally {
      patchTicketForTests(ticketId, { lifecycle: 'ACTIVE' });
    }
  });
});

describe('mockApi.salesVisits.plannedOnDeal — slice 6.5', () => {
  it('a rep sees only their own PLANNED visits on their deal, in the visit lists\' order', async () => {
    const { user, ticketId } = await ownDeal();
    const later = await api.salesVisits.create({ ticketId, contactName: 'มะรืน', plannedDate: plusDays(today, 2) });
    const untimed = await api.salesVisits.create({ ticketId, contactName: 'พรุ่งนี้', plannedDate: plusDays(today, 1) });
    const morning = await api.salesVisits.create({ ticketId, contactName: 'พรุ่งนี้เช้า', plannedDate: plusDays(today, 1),
      plannedTime: '09:00' });
    const cancelled = await api.salesVisits.create({ ticketId, contactName: 'ยกเลิก', plannedDate: plusDays(today, 1) });
    await api.salesVisits.cancel(cancelled.id);
    await api.auth.login({ role: 'sales_manager' });
    const managers = await api.salesVisits.create({ ticketId, contactName: 'ผู้จัดการ', plannedDate: plusDays(today, 3) });

    await api.auth.login({ role: 'sales' });
    const seen = await api.salesVisits.plannedOnDeal(ticketId);
    const ids = seen.map((v) => v.id);
    expect(ids).not.toContain(managers.id);
    expect(ids).not.toContain(cancelled.id);
    expect(seen.every((v) => v.ownerId === user.id && v.status === 'PLANNED' && v.ticketId === ticketId)).toBe(true);
    expect(ids.indexOf(morning.id)).toBeLessThan(ids.indexOf(untimed.id));
    expect(ids.indexOf(untimed.id)).toBeLessThan(ids.indexOf(later.id));

    await api.auth.login({ role: 'sales_manager' });
    expect((await api.salesVisits.plannedOnDeal(ticketId)).map((v) => v.id)).toEqual(expect.arrayContaining([managers.id, later.id]));
  });

  it('is refused to whoever cannot open the deal: another rep, account, import', async () => {
    const { ticketId } = await ownDeal();
    await api.auth.login({ email: 'sales2@glr.co.th', password: 'demo1234' });
    await expectStatus(api.salesVisits.plannedOnDeal(ticketId), 403);
    for (const role of ['account', 'import']) {
      await api.auth.login({ role });
      await expectStatus(api.salesVisits.plannedOnDeal(ticketId), 403);
    }
  });
});
