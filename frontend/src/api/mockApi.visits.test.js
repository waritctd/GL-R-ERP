import { describe, it, expect } from 'vitest';
import { api, patchTicketForTests } from './mockApi.js';
import { bangkokTodayIso } from '../utils/format.js';

// Mirrors VisitService / VisitRepository (backend VisitScopeIntegrationTest). The mock's authz is NOT evidence about
// the Java service — the owner scope, the team gate and the deal-ownership check are proven only by the real-DB
// VisitScopeIntegrationTest. This file pins the mock's SHAPE, its ORDER BY and range limit, and that a done visit on
// a deal clears the quiet clock the way SITE_VISIT does on the server.

const today = bangkokTodayIso();
const plusDays = (iso, n) => {
  const d = new Date(`${iso}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
};

async function expectStatus(promise, status) {
  await expect(promise).rejects.toMatchObject({ status });
}

describe('mockApi.salesVisits', () => {
  it('create returns a VisitDto owned by the caller', async () => {
    const { user } = await api.auth.login({ role: 'sales' });
    const visit = await api.salesVisits.create({ contactName: ' คุณอรุณ ', plannedDate: today, plannedTime: '10:00' });

    expect(visit).toMatchObject({
      ownerId: user.id, ticketId: null, ticketTitle: null, contactName: 'คุณอรุณ', plannedDate: today,
      plannedTime: '10:00', status: 'PLANNED', doneAt: null,
    });
    expect(typeof visit.id).toBe('number');
    expect(visit.ownerName).toBeTruthy();
  });

  it('list is own visits in [from, to], ordered by date, then time with untimed last, then id', async () => {
    const { user } = await api.auth.login({ role: 'sales' });
    const later = await api.salesVisits.create({ contactName: 'บ่าย', plannedDate: today, plannedTime: '14:00' });
    const untimed = await api.salesVisits.create({ contactName: 'ไม่ระบุเวลา', plannedDate: today });
    const earlier = await api.salesVisits.create({ contactName: 'เช้า', plannedDate: today, plannedTime: '09:30' });
    const far = await api.salesVisits.create({ contactName: 'เดือนหน้า', plannedDate: plusDays(today, 40) });

    const listed = await api.salesVisits.list({ from: today, to: today });
    const ids = listed.map((v) => v.id);

    expect(ids).not.toContain(far.id);
    expect(listed.every((v) => v.ownerId === user.id)).toBe(true);
    expect(ids.indexOf(earlier.id)).toBeLessThan(ids.indexOf(later.id));
    expect(ids.indexOf(later.id)).toBeLessThan(ids.indexOf(untimed.id));
  });

  it('a range over 31 days, or reversed, is 400; 31 days inclusive is fine', async () => {
    await api.auth.login({ role: 'sales' });
    await api.salesVisits.list({ from: today, to: plusDays(today, 30) });
    await expectStatus(api.salesVisits.list({ from: today, to: plusDays(today, 31) }), 400);
    await expectStatus(api.salesVisits.list({ from: today, to: plusDays(today, -1) }), 400);
  });

  it('team is sales_manager / ceo only and includes every rep', async () => {
    const { user: rep } = await api.auth.login({ role: 'sales' });
    const mine = await api.salesVisits.create({ contactName: 'ลูกค้าเซลส์', plannedDate: today });
    await expectStatus(api.salesVisits.team({ from: today, to: today }), 403);

    await api.auth.login({ role: 'sales_manager' });
    const team = await api.salesVisits.team({ from: today, to: today });
    expect(team.find((v) => v.id === mine.id)?.ownerId).toBe(rep.id);
  });

  it('account is refused everything', async () => {
    await api.auth.login({ role: 'sales' });
    const visit = await api.salesVisits.create({ contactName: 'ลูกค้า', plannedDate: today });
    await api.auth.login({ role: 'account' });
    await expectStatus(api.salesVisits.list({ from: today, to: today }), 403);
    await expectStatus(api.salesVisits.team({ from: today, to: today }), 403);
    await expectStatus(api.salesVisits.create({ contactName: 'x', plannedDate: today }), 403);
    await expectStatus(api.salesVisits.done(visit.id, {}), 403);
    await expectStatus(api.salesVisits.cancel(visit.id), 403);
  });

  it('done twice is 409; a cancelled visit cannot be done', async () => {
    await api.auth.login({ role: 'sales' });
    const a = await api.salesVisits.create({ contactName: 'ก', plannedDate: today });
    expect((await api.salesVisits.done(a.id, { note: 'ไปแล้ว' })).status).toBe('DONE');
    await expectStatus(api.salesVisits.done(a.id, {}), 409);
    await expectStatus(api.salesVisits.cancel(a.id), 409);

    const b = await api.salesVisits.create({ contactName: 'ข', plannedDate: today });
    expect((await api.salesVisits.cancel(b.id)).status).toBe('CANCELLED');
    await expectStatus(api.salesVisits.done(b.id, {}), 409);
  });

  it('done=true at create needs today', async () => {
    await api.auth.login({ role: 'sales' });
    const logged = await api.salesVisits.create({ contactName: 'Walk-in', plannedDate: today, done: true });
    expect(logged.status).toBe('DONE');
    expect(logged.doneAt).toBeTruthy();
    await expectStatus(api.salesVisits.create({ contactName: 'x', plannedDate: plusDays(today, 1), done: true }), 400);
  });

  it("a visit on another rep's deal is 403", async () => {
    await api.auth.login({ role: 'ceo' });
    const { user: rep } = await api.auth.login({ role: 'sales' });
    await api.auth.login({ role: 'ceo' });
    const theirs = (await api.tickets.list()).tickets.find((t) => t.createdById !== rep.id && t.keyedById !== rep.id);
    await api.auth.login({ role: 'sales' });
    await expectStatus(api.salesVisits.create({ ticketId: theirs.id, contactName: 'x', plannedDate: today }), 403);
  });

  it('done on a linked deal logs SITE_VISIT and clears the quiet clock', async () => {
    await api.auth.login({ role: 'sales' });
    const [quiet] = (await api.salesBoard.myWork()).followUp;
    // Yesterday's unticked visit does not hold the deal (H2), so the done is what clears it.
    const visit = await api.salesVisits.create({
      ticketId: quiet.ticketId, contactName: 'คุณอรุณ', plannedDate: plusDays(today, -1),
    });
    expect(visit.ticketTitle).toBeTruthy();
    expect((await api.salesBoard.myWork()).followUp.map((d) => d.ticketId)).toContain(quiet.ticketId);

    await api.salesVisits.done(visit.id, { note: 'ชอบ Raku สีเทา' });

    const after = await api.salesBoard.myWork();
    expect(after.followUp.map((d) => d.ticketId)).not.toContain(quiet.ticketId);
    const deal = after.board.find((d) => d.ticketId === quiet.ticketId);
    expect(deal.lastActivityKind).toBe('SITE_VISIT');
  });

  it('myWork carries this week\'s own visits and teamOverview the team\'s, with per-rep counts', async () => {
    const { user } = await api.auth.login({ role: 'sales' });
    const v = await api.salesVisits.create({ contactName: 'สัปดาห์นี้', plannedDate: today });
    const work = await api.salesBoard.myWork();
    expect(work.visitsThisWeek.map((x) => x.id)).toContain(v.id);
    expect(work.visitsThisWeek.every((x) => x.ownerId === user.id)).toBe(true);

    await api.auth.login({ role: 'sales_manager' });
    const team = await api.salesBoard.teamOverview();
    expect(team.teamVisitsThisWeek.map((x) => x.id)).toContain(v.id);
    const row = team.reps.find((r) => r.employeeId === user.id);
    const own = team.teamVisitsThisWeek.filter((x) => x.ownerId === user.id);
    expect(row.visitsDone).toBe(own.filter((x) => x.status === 'DONE').length);
    expect(row.visitsPlanned).toBe(own.filter((x) => x.status !== 'CANCELLED').length);
  });

  it('a booked visit (today or later) takes the deal out of followUp; cancelled or past-dated does not (H2)', async () => {
    await api.auth.login({ role: 'sales' });
    const [quiet, second] = (await api.salesBoard.myWork()).followUp;
    const booked = await api.salesVisits.create({ ticketId: quiet.ticketId, contactName: 'ก', plannedDate: plusDays(today, 3) });
    await api.salesVisits.create({ ticketId: second.ticketId, contactName: 'ข', plannedDate: plusDays(today, -1) });

    let work = await api.salesBoard.myWork();
    expect(work.followUp.map((d) => d.ticketId)).not.toContain(quiet.ticketId);
    expect(work.board.find((d) => d.ticketId === quiet.ticketId).quiet).toBe(false);
    expect(work.followUp.map((d) => d.ticketId)).toContain(second.ticketId);
    await api.auth.login({ role: 'ceo' });
    expect((await api.tickets.get(quiet.ticketId)).ticket.summary.stale).toBe(false);

    await api.auth.login({ role: 'sales' });
    await api.salesVisits.cancel(booked.id);
    work = await api.salesBoard.myWork();
    expect(work.followUp.map((d) => d.ticketId)).toContain(quiet.ticketId);
  });

  it('a future visit cannot be ticked done; an overdue one can (M2)', async () => {
    await api.auth.login({ role: 'sales' });
    const future = await api.salesVisits.create({ contactName: 'พรุ่งนี้', plannedDate: plusDays(today, 1) });
    await expectStatus(api.salesVisits.done(future.id, {}), 409);
    const overdue = await api.salesVisits.create({ contactName: 'เมื่อวาน', plannedDate: plusDays(today, -1) });
    expect((await api.salesVisits.done(overdue.id, {})).status).toBe('DONE');
  });

  it("a manager's tick is logged as the rep's, noting who recorded it (M3)", async () => {
    const { user: rep } = await api.auth.login({ role: 'sales' });
    const [deal] = (await api.salesBoard.myWork()).board;
    const visit = await api.salesVisits.create({ ticketId: deal.ticketId, contactName: 'ก', plannedDate: today });
    const { user: manager } = await api.auth.login({ role: 'sales_manager' });
    await api.salesVisits.done(visit.id, { note: 'ส่งตัวอย่าง' });

    const activities = (await api.tickets.listActivities(deal.ticketId)).items;
    const logged = activities.filter((a) => a.kind === 'SITE_VISIT').at(-1);
    expect(logged.createdById).toBe(rep.id);
    expect(logged.note).toBe(`ส่งตัวอย่าง · บันทึกโดย ${manager.name}`);
  });

  it('the owner can still tick after the deal moved to another rep; the new owner cannot (M1)', async () => {
    await api.auth.login({ role: 'sales' });
    const [deal] = (await api.salesBoard.myWork()).board;
    const ticked = await api.salesVisits.create({ ticketId: deal.ticketId, contactName: 'ก', plannedDate: today });
    const kept = await api.salesVisits.create({ ticketId: deal.ticketId, contactName: 'ข', plannedDate: today });
    // The production reassignment (moveOwnerToSalesRep): the ex-owner becomes the keyer.
    patchTicketForTests(deal.ticketId, { createdById: 12, keyedById: deal.ownerId });
    try {
      expect((await api.salesVisits.done(ticked.id, {})).status).toBe('DONE');
      await api.auth.login({ email: 'sales2@glr.co.th', password: 'demo1234' });
      await expectStatus(api.salesVisits.done(kept.id, {}), 403);
      await expectStatus(api.salesVisits.cancel(kept.id), 403);
    } finally {
      patchTicketForTests(deal.ticketId, { createdById: deal.ownerId });
    }
  });

  it('refuses a new visit on a non-ACTIVE deal, an over-long note and a malformed date (LOW)', async () => {
    await api.auth.login({ role: 'sales' });
    const [deal] = (await api.salesBoard.myWork()).board;
    patchTicketForTests(deal.ticketId, { lifecycle: 'CLOSED_LOST' });
    await expectStatus(api.salesVisits.create({ ticketId: deal.ticketId, contactName: 'x', plannedDate: today }), 400);
    patchTicketForTests(deal.ticketId, { lifecycle: 'ACTIVE' });
    await expectStatus(api.salesVisits.create({ contactName: 'x', plannedDate: today, note: 'ก'.repeat(2001) }), 400);
    await expectStatus(api.salesVisits.create({ contactName: 'x', plannedDate: '13/11/2569' }), 400);
  });

  it('an identical one-step log is the same visit (LOW)', async () => {
    await api.auth.login({ role: 'sales' });
    const a = await api.salesVisits.create({ contactName: 'Walk-in ซ้ำ', plannedDate: today, done: true });
    const b = await api.salesVisits.create({ contactName: 'Walk-in ซ้ำ', plannedDate: today, done: true });
    expect(b.id).toBe(a.id);
  });

  it('only a visit within 14 days holds the deal; more than 180 days ahead is refused (NEW-1)', async () => {
    await api.auth.login({ role: 'sales' });
    const [a, b] = (await api.salesBoard.myWork()).followUp;
    await api.salesVisits.create({ ticketId: a.ticketId, contactName: 'ก', plannedDate: plusDays(today, 14) });
    await api.salesVisits.create({ ticketId: b.ticketId, contactName: 'ข', plannedDate: plusDays(today, 15) });

    const followUp = (await api.salesBoard.myWork()).followUp.map((d) => d.ticketId);
    expect(followUp).not.toContain(a.ticketId);
    expect(followUp).toContain(b.ticketId);
    await expectStatus(api.salesVisits.create({ contactName: 'x', plannedDate: plusDays(today, 181) }), 400);
    await expectStatus(api.salesVisits.create({ contactName: 'x', plannedDate: '2099-01-01' }), 400);
    await api.salesVisits.create({ contactName: 'x', plannedDate: plusDays(today, 180) });
  });

  it("an ex-owner's visit neither holds nor touches the new owner's deal (NEW-2)", async () => {
    await api.auth.login({ role: 'sales' });
    const [quiet] = (await api.salesBoard.myWork()).followUp;
    const ahead = await api.salesVisits.create({ ticketId: quiet.ticketId, contactName: 'ก', plannedDate: plusDays(today, 2) });
    const due = await api.salesVisits.create({ ticketId: quiet.ticketId, contactName: 'ข', plannedDate: today });
    // The production reassignment (DealQuotationService#moveOwnerToSalesRep): the ex-owner becomes the keyer, and
    // the deal's timeline records the move (an EDITED event the visit reads as "the deal moved").
    const raw = (await api.tickets.get(quiet.ticketId)).ticket;
    patchTicketForTests(quiet.ticketId, { createdById: 12, keyedById: quiet.ownerId, events: [...(raw.events ?? []),
      { kind: 'EDITED', message: 'เปลี่ยนเจ้าของงาน: ก → ข (ตามพนักงานขายในใบเสนอราคา)', createdAt: new Date().toISOString() }] });
    try {
      await api.auth.login({ role: 'ceo' });
      expect((await api.tickets.get(quiet.ticketId)).ticket.summary.stale).toBe(true);
      const before = (await api.tickets.listActivities(quiet.ticketId)).items.length;

      await api.auth.login({ role: 'sales' });
      const done = await api.salesVisits.done(due.id, { note: 'ไปมาแล้ว' });
      expect(done.status).toBe('DONE');
      expect(done.dealMovedToName).toBeTruthy();
      expect(done.dealMoved).toBe(true);
      expect(ahead.dealMovedToName).toBeNull();
      expect(ahead.dealMoved).toBe(false);

      await api.auth.login({ role: 'ceo' });
      expect((await api.tickets.listActivities(quiet.ticketId)).items.length).toBe(before);
      expect((await api.tickets.get(quiet.ticketId)).ticket.summary.stale).toBe(true);
    } finally {
      patchTicketForTests(quiet.ticketId, { createdById: quiet.ownerId, events: raw.events });
    }
  });

  it("a keyer who is not the owner neither holds the deal nor writes onto it (NEW-A)", async () => {
    const { user: keyer } = await api.auth.login({ role: 'sales' });
    const [quiet] = (await api.salesBoard.myWork()).followUp;
    patchTicketForTests(quiet.ticketId, { createdById: 12, keyedById: keyer.id });
    try {
      await api.salesVisits.create({ ticketId: quiet.ticketId, contactName: 'ก', plannedDate: plusDays(today, 2) });
      const due = await api.salesVisits.create({ ticketId: quiet.ticketId, contactName: 'ข', plannedDate: today });
      await api.auth.login({ role: 'ceo' });
      expect((await api.tickets.get(quiet.ticketId)).ticket.summary.stale).toBe(true);
      const before = (await api.tickets.listActivities(quiet.ticketId)).items.length;

      await api.auth.login({ role: 'sales' });
      const ticked = await api.salesVisits.done(due.id, {});
      expect(ticked.dealMovedToName).toBeTruthy();
      expect(ticked.dealMoved).toBe(false);
      await api.salesVisits.create({ ticketId: quiet.ticketId, contactName: 'ค', plannedDate: today, done: true });

      await api.auth.login({ role: 'ceo' });
      expect((await api.tickets.listActivities(quiet.ticketId)).items.length).toBe(before);
    } finally {
      patchTicketForTests(quiet.ticketId, { createdById: quiet.ownerId, keyedById: quiet.ownerId });
    }
  });
});
