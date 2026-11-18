import { test, expect } from '@playwright/test';
import { apiSessionsFor, apiWrite, disposeSessions } from './helpers/api.js';
import { cancelDeal, createDeal, readDeal, setTracking } from './helpers/sales.js';

// ─────────────────────────────────────────────────────────────────────────────
// SLICE 6b — real Spring services, real Postgres, no mocks. Proves the NEW backend, not only regressions:
//
//   GET  /api/sales/visits/deal/{ticketId}/planned   a deal's PLANNED visits, own for sales, all for sales_manager/ceo
//   POST /api/tickets/{id}/activities  + nextFollowUpAt   writes ONLY sales.ticket.next_follow_up_at
//   POST /api/sales/visits (done)      + nextFollowUpAt   same, when the log lands on the deal
//
// Every one of these assertions FAILS against a backend from before slice 6b: the read route 404s, and the date is
// ignored (Spring drops unknown JSON fields), so the deal's date would not move. The scope and "nothing written"
// directions are proven by the real-DB integration tests (DealPlannedVisitsScopeIntegrationTest,
// DealActivityFollowUpIntegrationTest, VisitFollowUpIntegrationTest, VisitFollowUpTransactionIntegrationTest).
//
// Writes: one deal of this run (cancelled in afterAll) and its visits (PLANNED ones cancelled in afterAll). The one-step
// log's DONE visit stays, on the cancelled deal: the API has no delete, and cancel is PLANNED-only (409 on DONE), as
// visits.spec.js already notes for its own DONE visits.
// ─────────────────────────────────────────────────────────────────────────────

process.env.E2E_RUN_ID ||= `e2e-6b-${Date.now().toString(36)}`;

const bangkokFmt = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Bangkok' });
/** `YYYY-MM-DD` in Asia/Bangkok, `days` from today. */
function isoIn(days = 0) {
  return bangkokFmt.format(new Date(Date.now() + days * 86_400_000));
}

test.describe('slice 6b — follow-up date and planned visits on a deal @visits', () => {
  let sessions;
  let deal;
  const planned = [];

  test.beforeAll(async () => {
    sessions = await apiSessionsFor(['sales', 'sales_manager']);
    deal = await createDeal(sessions, { caseName: '6b follow-up', entryChannel: 'DESIGNER_LED' });
    await setTracking(sessions, deal.id, { nextFollowUpAt: isoIn(30), designerName: 'สตูดิโอ e2e 6b' });
  });

  test.afterAll(async () => {
    for (const { session, id } of planned) {
      try { await apiWrite(session, 'post', `/api/sales/visits/${id}/cancel`); } catch { /* teardown */ }
    }
    await cancelDeal(sessions, deal.id);
    await disposeSessions(sessions);
  });

  async function plan(role, contactName, days) {
    const res = await apiWrite(sessions[role], 'post', '/api/sales/visits', {
      ticketId: deal.id, contactName, plannedDate: isoIn(days),
    });
    expect(res.status(), `${role} plans a visit on deal ${deal.id}`).toBe(200);
    const visit = await res.json();
    planned.push({ session: sessions[role], id: visit.id });
    return visit;
  }

  async function plannedIds(role) {
    const res = await sessions[role].get(`/api/sales/visits/deal/${deal.id}/planned`);
    expect(res.status(), `${role} GET planned visits of deal ${deal.id}`).toBe(200);
    return (await res.json()).map((v) => v.id);
  }

  test('planned visits: the rep sees their own, not the manager\'s; the manager sees both', async () => {
    const own = await plan('sales', 'e2e 6b · คุณอรุณ', 5);
    const managers = await plan('sales_manager', 'e2e 6b · ผู้จัดการไปเอง', 6);

    const repSees = await plannedIds('sales');
    expect(repSees).toContain(own.id);
    expect(repSees, 'a rep never receives a visit the visit lists would not show them').not.toContain(managers.id);
    expect(await plannedIds('sales_manager')).toEqual(expect.arrayContaining([own.id, managers.id]));
  });

  test('a call carrying nextFollowUpAt moves the deal\'s date and nothing else', async () => {
    const date = isoIn(11);
    const res = await apiWrite(sessions.sales, 'post', `/api/tickets/${deal.id}/activities`, {
      kind: 'CALL', activityDate: isoIn(0), note: 'e2e 6b · คุยแล้ว ยังสนใจ', nextFollowUpAt: date,
    });
    expect(res.status(), 'POST activities with nextFollowUpAt').toBe(200);

    const { summary } = await readDeal(sessions, 'sales', deal.id);
    expect(summary.nextFollowUpAt).toBe(date);
    expect(summary.designerName, 'the tracking PUT would have wiped this; the new path must not').toBe('สตูดิโอ e2e 6b');
  });

  test('a one-step visit log carrying nextFollowUpAt moves the deal\'s date', async () => {
    const date = isoIn(12);
    const res = await apiWrite(sessions.sales, 'post', '/api/sales/visits', {
      ticketId: deal.id, contactName: 'e2e 6b · Walk-in', plannedDate: isoIn(0), done: true, nextFollowUpAt: date,
    });
    expect(res.status(), 'POST /api/sales/visits done with nextFollowUpAt').toBe(200);
    expect((await res.json()).dealFollowUpOpen, 'VisitDto.dealFollowUpOpen is served').toBe(true);

    expect((await readDeal(sessions, 'sales', deal.id)).summary.nextFollowUpAt).toBe(date);
  });

  test('a past date is refused 400 and leaves the deal\'s date alone', async () => {
    const before = (await readDeal(sessions, 'sales', deal.id)).summary.nextFollowUpAt;
    const res = await apiWrite(sessions.sales, 'post', `/api/tickets/${deal.id}/activities`, {
      kind: 'CALL', activityDate: isoIn(0), note: 'e2e 6b · refused', nextFollowUpAt: isoIn(-1),
    });
    expect(res.status()).toBe(400);
    expect((await readDeal(sessions, 'sales', deal.id)).summary.nextFollowUpAt).toBe(before);
  });
});
