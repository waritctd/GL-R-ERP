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
// UI: งานวันนี้ (SalesOverview, hosting MyWork's call dialog) is the sales landing; ภาพรวมทีมขาย (TeamOverview) is
// the ceo landing and the sales_manager's งานวันนี้ is ManagerOverview (spec 0003).
//
// Deterministic by construction: no assertion depends on how many deals the seed holds.
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

  test('โทรแล้ว with an outcome logs a CALL on the chosen deal', async ({ page }) => {
    // Precondition: a fresh deal owned by sales (API); the header's บันทึกการโทร lists every open deal of the rep.
    const deal = await createDeal(sessions, { caseName: 'board call', entryChannel: 'DESIGNER_LED' });
    created.push(deal.id);

    await loginAs(page, 'sales');
    await page.getByRole('button', { name: 'บันทึกการโทร' }).first().click();
    const picker = page.getByRole('dialog', { name: 'โทรหางานไหน' });
    await expect(picker).toBeVisible();
    await picker.getByRole('button', { name: /board call/ }).click();
    const dialog = page.getByRole('dialog', { name: 'โทรแล้ว' });
    await expect(dialog).toBeVisible();
    await shot(page, '03-call-dialog');
    // An outcome is mandatory: the save button stays disabled until one is picked.
    await expect(dialog.getByRole('button', { name: 'บันทึก' })).toBeDisabled();
    await dialog.getByRole('radio', { name: 'คุยแล้ว ยังสนใจ' }).click();
    await dialog.getByRole('button', { name: 'บันทึก' }).click();
    await expect(dialog).toHaveCount(0);

    // Server truth, not just the DOM: the activity row exists with the outcome as its note.
    const activities = await (await sessions.sales.get(`/api/tickets/${deal.id}/activities`)).json();
    expect(activities.items.some((a) => a.kind === 'CALL' && String(a.note).includes('คุยแล้ว ยังสนใจ'))).toBe(true);
  });
});

test.describe('manager งานวันนี้ @sales-board', () => {
  test('sales_manager sees งานวันนี้ with the team report and a per-rep table', async ({ page }) => {
    await loginAs(page, 'sales_manager');
    await expect(page.getByRole('heading', { name: 'งานวันนี้', level: 1 })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'รายงานสัปดาห์ของทีม' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'รายการที่รอคุณดำเนินการ' })).toBeVisible();
    const reps = page.getByRole('region', { name: 'รายบุคคล' });
    for (const header of ['พนักงานขาย', 'ข้อมูลรายงานครบถ้วน', 'บันทึกวันนี้', 'เลื่อนขั้นสัปดาห์นี้', 'ใบเสนอราคา', 'เข้าพบ', 'รอคุณอนุมัติ']) {
      await expect(reps.getByText(header, { exact: true }).first()).toBeVisible();
    }
    await expect(page.getByText('โหลดไม่สำเร็จ')).toHaveCount(0);
    await shot(page, '04-manager-today');
  });
});

for (const role of ['ceo']) {
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
