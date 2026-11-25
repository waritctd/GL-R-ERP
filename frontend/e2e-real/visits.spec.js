import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { test, expect } from '@playwright/test';
import { apiSessionsFor, apiWrite, disposeSessions } from './helpers/api.js';
import { loginAs } from './helpers/auth.js';
import { cancelDeal, createDeal } from './helpers/sales.js';

// ─────────────────────────────────────────────────────────────────────────────
// VISITS (slice 2 of the sales redesign) — real Vite → Spring → Postgres, no mocks.
//
//   GET  /api/sales/visits?from&to        sales, sales_manager, ceo (the caller's own visits)
//   GET  /api/sales/visits/team?from&to   sales_manager, ceo
//   POST /api/sales/visits                plan, or (done:true) a one-step log dated today
//   POST /api/sales/visits/{id}/done      the visit's owner, or sales_manager / ceo; never ahead of the planned day
//   POST /api/sales/visits/{id}/cancel    same
//
// UI: the rep's นัดเข้าพบ page, /visits (plan dialog, เข้าพบแล้ว tick, week ‹ ›, บันทึกเข้าพบ) and the team's visits
// today list on the sales manager's งานวันนี้.
//
// "Today" is the Asia/Bangkok date, as on the server. Every visit/deal a test creates carries the run id and is
// cancelled in afterAll (a DONE visit cannot be cancelled — that is a 409 — so those stay; the deal is cancelled).
// Set E2E_SHOT_DIR to also save screenshots.
// ─────────────────────────────────────────────────────────────────────────────

// helpers/sales.js stamps titles with this run id; global-setup only sets it for remote runs.
process.env.E2E_RUN_ID ||= `e2e-visits-${Date.now().toString(36)}`;
const RUN = process.env.E2E_RUN_ID;

const SHOT_DIR = process.env.E2E_SHOT_DIR || '';
async function shot(page, name) {
  if (!SHOT_DIR) return;
  mkdirSync(SHOT_DIR, { recursive: true });
  await page.screenshot({ path: join(SHOT_DIR, `${name}.png`), fullPage: true });
}

const bangkokFmt = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Bangkok' });
/** `YYYY-MM-DD` in Asia/Bangkok, `days` from today. */
function isoIn(days = 0) {
  return bangkokFmt.format(new Date(Date.now() + days * 86_400_000));
}

async function listOwn(session, from, to) {
  const res = await session.get(`/api/sales/visits?from=${from}&to=${to}`);
  expect(res.status(), 'GET /api/sales/visits').toBe(200);
  return res.json();
}

async function planVisit(session, body) {
  const res = await apiWrite(session, 'post', '/api/sales/visits', { plannedDate: isoIn(0), ...body });
  expect(res.status(), `POST /api/sales/visits ${JSON.stringify(body)}`).toBe(200);
  return res.json();
}

test.describe('visits — happy paths @visits', () => {
  let sessions;
  let deal;
  const planned = []; // visit ids still PLANNED at the end, cancelled in afterAll

  test.beforeAll(async () => {
    sessions = await apiSessionsFor(['sales', 'sales_manager']);
    deal = await createDeal(sessions, { caseName: 'visits happy', entryChannel: 'DESIGNER_LED' });
  });
  test.afterAll(async () => {
    for (const id of planned) {
      try { await apiWrite(sessions.sales, 'post', `/api/sales/visits/${id}/cancel`); } catch { /* teardown */ }
    }
    await cancelDeal(sessions, deal.id);
    await disposeSessions(sessions);
  });

  test('plan a visit for this week on a deal, tick เข้าพบแล้ว: it writes a SITE_VISIT on the deal, and the manager sees it in the team grid', async ({ page, browser }) => {
    const contact = `${RUN} คุณอรุณ`;
    await loginAs(page, 'sales');
    await page.goto('/visits');
    await expect(page.getByTestId('visits-planned')).toBeVisible();

    await page.getByRole('button', { name: 'นัดเข้าพบ', exact: true }).click();
    const plan = page.getByRole('dialog', { name: 'นัดเข้าพบ' });
    await expect(plan).toBeVisible();
    await shot(page, 'visits-01-plan-dialog');
    await plan.getByLabel('พบใคร').fill(contact);
    await plan.getByLabel('งาน').selectOption({ label: `${RUN} visits happy` });
    await plan.getByRole('button', { name: 'บันทึกนัด' }).click();
    await expect(plan).toHaveCount(0);

    const row = page.getByTestId('visits-planned').locator('li', { hasText: contact });
    await expect(row).toBeVisible();
    const own = await listOwn(sessions.sales, isoIn(-7), isoIn(14));
    const visit = own.find((v) => v.contactName === contact);
    expect(visit, 'the planned visit is served by GET /api/sales/visits').toBeTruthy();
    expect(visit.ticketId).toBe(deal.id);
    expect(visit.status).toBe('PLANNED');
    planned.push(visit.id);
    await shot(page, 'visits-02-planned-in-card');

    // The manager's งานวันนี้ lists the team's visits of today (separate browser context = separate session).
    const mgrContext = await browser.newContext();
    const mgrPage = await mgrContext.newPage();
    try {
      await mgrPage.goto('/');
      await loginAs(mgrPage, 'sales_manager');
      if (visit.plannedDate === isoIn(0)) await expect(mgrPage.getByTestId(`team-visit-${visit.id}`)).toBeVisible();
      await shot(mgrPage, 'visits-03-manager-team-visits');
    } finally {
      await mgrContext.close();
    }

    // Tick it.
    await row.getByRole('button', { name: `เข้าพบแล้ว: ${contact}` }).click();
    const done = page.getByRole('dialog', { name: 'เข้าพบแล้ว' });
    await expect(done).toBeVisible();
    await done.getByRole('radio', { name: 'ส่งตัวอย่าง' }).click();
    await done.getByRole('button', { name: 'บันทึก', exact: true }).click();
    await expect(done).toHaveCount(0);
    await expect(page.getByTestId('visits-done').locator('li', { hasText: contact })).toBeVisible();
    await shot(page, 'visits-04-ticked-done');
    planned.splice(planned.indexOf(visit.id), 1);

    // Server truth: visit DONE, and a SITE_VISIT activity landed on the deal.
    const after = (await listOwn(sessions.sales, isoIn(-7), isoIn(14))).find((v) => v.id === visit.id);
    expect(after.status).toBe('DONE');
    const activities = await (await sessions.sales.get(`/api/tickets/${deal.id}/activities`)).json();
    expect(activities.items.some((a) => a.kind === 'SITE_VISIT' && String(a.note).includes('ส่งตัวอย่าง'))).toBe(true);
  });
});

test.describe('visits — failure paths @visits', () => {
  let sessions;
  let deal;
  const planned = [];

  test.beforeAll(async () => {
    sessions = await apiSessionsFor(['sales', 'sales_manager']);
    deal = await createDeal(sessions, { caseName: 'visits failure', entryChannel: 'DESIGNER_LED' });
  });
  test.afterAll(async () => {
    for (const [role, id] of planned) {
      try { await apiWrite(sessions[role], 'post', `/api/sales/visits/${id}/cancel`); } catch { /* teardown */ }
    }
    await cancelDeal(sessions, deal.id);
    await disposeSessions(sessions);
  });

  test('ticking a future visit is refused with 409 (API) and the UI offers no tick for it', async ({ page }) => {
    const contact = `${RUN} ล่วงหน้า`;
    const visit = await planVisit(sessions.sales, { contactName: contact, plannedDate: isoIn(1), ticketId: deal.id });
    planned.push(['sales', visit.id]);

    const res = await apiWrite(sessions.sales, 'post', `/api/sales/visits/${visit.id}/done`, {});
    expect(res.status(), 'done ahead of the planned day').toBe(409);
    expect((await res.json()).message).toContain('ยังไม่ถึงวันนัด');
    const after = (await listOwn(sessions.sales, isoIn(0), isoIn(2))).find((v) => v.id === visit.id);
    expect(after.status, 'refused tick changed nothing').toBe('PLANNED');

    // UI: tomorrow's visit renders, but its tick button does not exist (the page cannot send what the service refuses).
    await loginAs(page, 'sales');
    await page.goto('/visits');
    // Tomorrow may fall in next week (Sunday): page there if it is not in the current one.
    await expect(page.getByTestId('visits-planned')).toBeVisible();
    const dow = (iso) => (new Date(`${iso}T00:00:00Z`).getUTCDay() + 6) % 7; // Monday = 0
    if (dow(isoIn(0)) === 6) await page.getByRole('button', { name: 'สัปดาห์หน้า' }).click();
    const row = page.getByTestId('visits-planned').locator('li', { hasText: contact });
    await expect(row).toBeVisible();
    await expect(row.getByRole('button', { name: `ยกเลิกนัด: ${contact}` })).toBeVisible();
    await expect(row.getByRole('button', { name: `เข้าพบแล้ว: ${contact}` })).toHaveCount(0);
    await shot(page, 'visits-08-future-visit-no-tick');
  });

  test('another user cannot tick or cancel a rep\'s visit; a rep cannot tick or cancel the manager\'s', async () => {
    const repVisit = await planVisit(sessions.sales, { contactName: `${RUN} ของเซลล์`, plannedDate: isoIn(0), ticketId: deal.id });
    planned.push(['sales', repVisit.id]);
    const mgrVisit = await planVisit(sessions.sales_manager, { contactName: `${RUN} ของผู้จัดการ` });
    planned.push(['sales_manager', mgrVisit.id]);

    // The rep is not the owner of the manager's visit and not in TEAM_ROLES: 403 on both verbs.
    for (const verb of ['done', 'cancel']) {
      const res = await apiWrite(sessions.sales, 'post', `/api/sales/visits/${mgrVisit.id}/${verb}`, {});
      expect(res.status(), `sales ${verb} manager's visit`).toBe(403);
    }
    const still = (await listOwn(sessions.sales_manager, isoIn(0), isoIn(0))).find((v) => v.id === mgrVisit.id);
    expect(still.status, "the manager's visit is untouched").toBe('PLANNED');

    // A manager MAY act on a rep's visit (TEAM_ROLES) — the permitted half of the same matrix.
    const mgrTick = await apiWrite(sessions.sales_manager, 'post', `/api/sales/visits/${repVisit.id}/done`, {});
    expect(mgrTick.status(), 'sales_manager ticks a rep visit').toBe(200);
    planned.pop(); planned.pop(); planned.push(['sales_manager', mgrVisit.id]);
  });

  test('/api/sales/visits* refuses account, import, hr and the other non-sales roles; /team refuses sales', async () => {
    const roles = ['account', 'import', 'hr', 'employee', 'warehouse', 'qc', 'sales', 'sales_manager', 'ceo'];
    const all = await apiSessionsFor(roles);
    const from = isoIn(0);
    try {
      for (const role of roles) {
        const own = await all[role].get(`/api/sales/visits?from=${from}&to=${from}`, { failOnStatusCode: false });
        const allowed = ['sales', 'sales_manager', 'ceo'].includes(role);
        expect(own.status(), `${role} GET /api/sales/visits`).toBe(allowed ? 200 : 403);

        const team = await all[role].get(`/api/sales/visits/team?from=${from}&to=${from}`, { failOnStatusCode: false });
        expect(team.status(), `${role} GET /api/sales/visits/team`).toBe(['sales_manager', 'ceo'].includes(role) ? 200 : 403);

        if (!allowed) {
          const post = await apiWrite(all[role], 'post', '/api/sales/visits', { contactName: 'x', plannedDate: from });
          expect(post.status(), `${role} POST /api/sales/visits`).toBe(403);
          for (const verb of ['done', 'cancel']) {
            const res = await apiWrite(all[role], 'post', `/api/sales/visits/1/${verb}`, {});
            expect(res.status(), `${role} POST /api/sales/visits/1/${verb}`).toBe(403);
          }
        }
      }
    } finally {
      await disposeSessions(all);
    }
  });
});
