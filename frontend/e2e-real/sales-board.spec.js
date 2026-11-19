import { spawnSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { test, expect } from '@playwright/test';
import { apiSessionsFor, disposeSessions } from './helpers/api.js';
import { loginAs } from './helpers/auth.js';
import { cancelDeal, createDeal } from './helpers/sales.js';

// ─────────────────────────────────────────────────────────────────────────────
// SALES BOARD (slice 1 of the sales redesign) — real Vite → Spring → Postgres, no mocks.
//
//   GET /api/sales/my-work        sales, sales_manager, ceo (the caller's own deals)
//   GET /api/sales/team-overview  sales_manager, ceo
//   PUT /api/tickets/{id}/tracking   expectedQuarter is @Pattern ^$|^\d{4}-Q[1-4]$
//
// UI: งานของฉัน (MyWork → FollowUpList + DealBoard) is the sales landing; ภาพรวมทีมขาย (TeamOverview) is the
// sales_manager / ceo landing.
//
// Deterministic by construction: no assertion depends on how many deals the seed holds. The one test that needs a
// "quiet" deal mints its own and BACKDATES it with psql (QuietRule: quiet = last touched > app.sales.quiet-days ago,
// default 14; no API can age a deal, and shrinking the window to 0 would make a deal permanently quiet, so a call
// could never clear it). If psql is unreachable that test skips with a stated reason. psql is found via E2E_PSQL
// (default `psql`); connection follows scripts/start-e2e-backend.mjs (PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD).
//
// Every deal a test creates is cancelled in afterAll. Set E2E_SHOT_DIR to also save screenshots.
// ─────────────────────────────────────────────────────────────────────────────

// helpers/sales.js stamps titles with this run id; global-setup only sets it for remote runs.
process.env.E2E_RUN_ID ||= `e2e-board-${Date.now().toString(36)}`;

const SHOT_DIR = process.env.E2E_SHOT_DIR || '';
async function shot(page, name) {
  if (!SHOT_DIR) return;
  mkdirSync(SHOT_DIR, { recursive: true });
  await page.screenshot({ path: join(SHOT_DIR, `${name}.png`), fullPage: true });
}

/** Ages a deal's created_at and its events/activities so QuietRule sees it as untouched for 30 days.
 * Returns an error string, or null on success. Touches only the given ticket. */
function backdateDeal(ticketId) {
  const sql = `UPDATE sales.ticket SET created_at = now() - interval '30 days' WHERE ticket_id = ${Number(ticketId)};`
    + ` UPDATE sales.ticket_event SET created_at = now() - interval '30 days' WHERE ticket_id = ${Number(ticketId)};`
    // Creation also writes a "สร้างดีล" deal_activity row, which QuietRule counts as a touch.
    + ` UPDATE sales.deal_activity SET created_at = now() - interval '30 days' WHERE ticket_id = ${Number(ticketId)};`;
  const result = spawnSync(process.env.E2E_PSQL || 'psql', [
    '-h', process.env.PGHOST || '127.0.0.1',
    '-p', process.env.PGPORT || '5432',
    '-U', process.env.PGUSER || 'postgres',
    '-d', process.env.PGDATABASE || 'hris',
    '-v', 'ON_ERROR_STOP=1', '-c', sql,
  ], { env: { ...process.env, PGPASSWORD: process.env.PGPASSWORD || 'postgres' }, encoding: 'utf8' });
  if (result.error) return `psql not runnable (${result.error.code}) — set E2E_PSQL`;
  if (result.status !== 0) return `psql failed: ${result.stderr}`;
  return null;
}

test.describe('sales board — happy paths @sales-board', () => {
  let sessions;
  const created = [];

  test.beforeAll(async () => {
    sessions = await apiSessionsFor(['sales']);
  });
  test.afterAll(async () => {
    for (const id of created) await cancelDeal(sessions, id);
    await disposeSessions(sessions);
  });

  test('โทรแล้ว with an outcome logs a CALL and the deal leaves ควรตามต่อ', async ({ page }) => {
    // Precondition: a fresh deal owned by sales (API), aged past the quiet window (psql — no API can do this).
    const deal = await createDeal(sessions, { caseName: 'board quiet-call', entryChannel: 'DESIGNER_LED' });
    created.push(deal.id);
    const backdateError = backdateDeal(deal.id);
    test.skip(backdateError !== null, `cannot age the deal: ${backdateError}`);
    const mine = await (await sessions.sales.get('/api/sales/my-work')).json();
    // followUp is capped at 5, ranked by days x value; a zero-value deal can be crowded out by older rows, so
    // work the deal the server actually lists first if ours is not there (still a real, quiet, owned deal).
    // Since the lifecycle dialogs (V220) followUp also LEADS with any returning paused deals, which offer only
    // ดำเนินการต่อ, not โทรแล้ว — so the fallback is the first QUIET row, never a returning one.
    const target = mine.followUp.find((d) => d.ticketId === deal.id) ?? mine.followUp.find((d) => !d.returning);
    expect(target, `followUp is empty after backdating deal #${deal.id} (quietDays=${mine.quietDays})`).toBeTruthy();

    await loginAs(page, 'sales');
    const row = page.getByTestId(`follow-up-${target.ticketId}`);
    await expect(row).toBeVisible();
    await shot(page, '03-follow-up-before-call');

    await row.getByRole('button', { name: 'โทรแล้ว' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    // An outcome is mandatory: the save button stays disabled until one is picked.
    await expect(dialog.getByRole('button', { name: 'บันทึก' })).toBeDisabled();
    await dialog.getByRole('radio', { name: 'คุยแล้ว ยังสนใจ' }).click();
    await dialog.getByRole('button', { name: 'บันทึก' }).click();

    await expect(page.getByTestId(`follow-up-${target.ticketId}`)).toHaveCount(0);

    // Server truth, not just the DOM: the activity row exists with the outcome as its note, and the deal is no
    // longer in followUp.
    const activities = await (await sessions.sales.get(`/api/tickets/${target.ticketId}/activities`)).json();
    expect(activities.items.some((a) => a.kind === 'CALL' && String(a.note).includes('คุยแล้ว ยังสนใจ'))).toBe(true);
    const after = await (await sessions.sales.get('/api/sales/my-work')).json();
    expect(after.followUp.some((d) => d.ticketId === target.ticketId)).toBe(false);
  });
});

for (const role of ['sales_manager', 'ceo']) {
  test.describe(`team overview as ${role} @sales-board`, () => {
    test(`${role} sees ภาพรวมทีมขาย with a per-rep table`, async ({ page }) => {
      // Precondition: at least one open deal exists so the table has a row (a manager with zero deals would
      // legitimately show the empty copy). Created by sales, cancelled after.
      const sessions = await apiSessionsFor(['sales']);
      let deal;
      try {
        deal = await createDeal(sessions, { caseName: `board team ${role}`, entryChannel: 'DESIGNER_LED' });
        await loginAs(page, role);
        await expect(page.getByRole('region', { name: 'ภาพรวมทีมขาย' })).toBeVisible();
        await expect(page.getByTestId('tile-held')).toBeVisible();
        await expect(page.getByTestId('tile-quiet')).toBeVisible();
        // By name: once any visit exists this week, the ตารางเข้าพบ week grid draws a second table in this region.
        const table = page.getByRole('region', { name: 'ภาพรวมทีมขาย' }).getByRole('table', { name: 'รายคน', exact: true });
        await expect(table).toBeVisible();
        for (const header of ['พนักงานขาย', 'งาน', 'มูลค่า', 'งานเงียบ', 'อัปเดตล่าสุด']) {
          await expect(table.getByRole('columnheader', { name: header, exact: true })).toBeVisible();
        }
        expect(await table.locator('tbody tr').count()).toBeGreaterThan(0);
        await expect(page.getByText('โหลดไม่สำเร็จ')).toHaveCount(0);
        if (role === 'sales_manager') await shot(page, '04-manager-team-overview');
        if (role === 'ceo') await shot(page, '05-ceo-team-overview');
      } finally {
        if (deal) await cancelDeal(sessions, deal.id);
        await disposeSessions(sessions);
      }
    });
  });
}

test.describe('sales board — failure paths @sales-board', () => {
  test('GET /api/sales/team-overview: only sales_manager and ceo; every other role is 403', async () => {
    const roles = ['sales', 'account', 'import', 'hr', 'employee', 'warehouse', 'qc', 'sales_manager', 'ceo'];
    const sessions = await apiSessionsFor(roles);
    try {
      for (const role of roles) {
        const res = await sessions[role].get('/api/sales/team-overview', { failOnStatusCode: false });
        const expected = role === 'sales_manager' || role === 'ceo' ? 200 : 403;
        expect(res.status(), `${role} GET /api/sales/team-overview`).toBe(expected);
      }
    } finally {
      await disposeSessions(sessions);
    }
  });

  test('GET /api/sales/my-work: sales, sales_manager, ceo only; account/import/hr (and the rest) are 403', async () => {
    const roles = ['sales', 'account', 'import', 'hr', 'employee', 'warehouse', 'qc', 'sales_manager', 'ceo'];
    const sessions = await apiSessionsFor(roles);
    try {
      for (const role of roles) {
        const res = await sessions[role].get('/api/sales/my-work', { failOnStatusCode: false });
        const expected = ['sales', 'sales_manager', 'ceo'].includes(role) ? 200 : 403;
        expect(res.status(), `${role} GET /api/sales/my-work`).toBe(expected);
      }
    } finally {
      await disposeSessions(sessions);
    }
  });
});
