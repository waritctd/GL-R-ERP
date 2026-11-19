import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { test, expect } from '@playwright/test';
import { personaFor } from './helpers/accounts.js';
import { apiSessionsFor, disposeSessions, writeHeaders } from './helpers/api.js';
import { loginAs } from './helpers/auth.js';
import { cancelDeal } from './helpers/sales.js';

// ─────────────────────────────────────────────────────────────────────────────
// ONE-TIME PIPELINE IMPORT (slice 4 of the sales redesign) — real Vite → Spring → Postgres, no mocks.
//
//   POST /api/sales/pipeline-imports?dryRun=   multipart `file`; sales_manager / ceo only (PipelineImportService)
//   UI: ภาพรวมทีมขาย → "นำเข้าจากชีท" (PipelineImportDialog): upload → ตรวจก่อนนำเข้า (dry run, writes nothing) → นำเข้า
//
// The CSV is built here, UTF-8 with a BOM and the reps' own Thai headers (aliases the backend accepts), so the test
// also pins that a workbook written the way the reps write it is read. The rep column carries the seeded sales
// persona's email (accounts.js), because that is the one stable identity a fresh database has.
//
// Every deal an import creates is cancelled in afterAll (the rep owns it, so the sales session may cancel).
// Titles carry a per-run tag, so two runs never see each other's rows and nothing depends on what the seed holds.
// Set E2E_SHOT_DIR to also save screenshots.
// ─────────────────────────────────────────────────────────────────────────────

// helpers/sales.js (cancelDeal) stamps teardown notes with this run id and THROWS without it; global-setup only sets
// it for remote runs, and cancelDeal swallows that throw — so without this line every cleanup silently no-ops.
process.env.E2E_RUN_ID ||= `e2e-pi-run-${Date.now().toString(36)}`;

const SHOT_DIR = process.env.E2E_SHOT_DIR || '';
async function shot(page, name) {
  if (!SHOT_DIR) return;
  mkdirSync(SHOT_DIR, { recursive: true });
  await page.screenshot({ path: join(SHOT_DIR, `${name}.png`), fullPage: true });
}

const TAG = `e2e-pi-${Date.now().toString(36)}`;
const REP = personaFor('sales').email;

const HEADERS = ['พนักงานขาย', 'โครงการ', 'ผู้ออกแบบ', 'ผู้ซื้อ', 'เลขที่อ้างอิง', 'มูลค่าใบเสนอราคา', 'สถานะงานขาย', 'จะขายได้ %', 'ช่วงเวลา (Q)'];

function csvCell(value) {
  const s = String(value ?? '');
  return /[",\n]/.test(s) ? `"${s.replaceAll('"', '""')}"` : s;
}

/** UTF-8 with BOM, the way Excel's "CSV UTF-8" writes it. */
function csvBuffer(rows, headers = HEADERS) {
  const lines = [headers, ...rows].map((row) => row.map(csvCell).join(','));
  return Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), Buffer.from(lines.join('\r\n'), 'utf8')]);
}

const GOOD_TITLE = `${TAG} โครงการนำเข้า ดี`;
const goodRow = [REP, GOOD_TITLE, `${TAG} สถาปนิกเอ`, `${TAG} ผู้รับเหมาบี`, `QT-${TAG}-1`, '125000', 'S2', '60', '2026-Q4'];
const s4Row = [REP, `${TAG} โครงการ S4`, 'ผู้ออกแบบ', `${TAG} ผู้รับเหมา`, '', '1000', 'S4', '50', '2026-Q4'];
const s9Row = [REP, `${TAG} โครงการ S9`, 'ผู้ออกแบบ', `${TAG} ผู้รับเหมา`, '', '1000', 'S9', '50', '2026-Q4'];
const unknownRepRow = ['nobody.here@demo.invalid', `${TAG} โครงการ ไม่มีเซลส์`, 'ผู้ออกแบบ', `${TAG} ผู้รับเหมา`, '', '1000', 'S2', '50', '2026-Q4'];
const badProbRow = [REP, `${TAG} โครงการ โอกาสผิด`, 'ผู้ออกแบบ', `${TAG} ผู้รับเหมา`, '', '1000', 'S2', 'abc', '2026-Q4'];

const MIXED = csvBuffer([goodRow, s4Row, s9Row, unknownRepRow, badProbRow]);
const SKIPPED_TITLES = [s4Row, s9Row, unknownRepRow, badProbRow].map((r) => r[1]);

/** Multipart upload to the real endpoint with the session's CSRF header. */
async function uploadImport(session, buffer, { dryRun, name = 'pipeline.csv' } = {}) {
  return session.fetch(`/api/sales/pipeline-imports?dryRun=${dryRun}`, {
    method: 'POST',
    headers: await writeHeaders(session),
    multipart: { file: { name, mimeType: 'text/csv', buffer } },
    failOnStatusCode: false,
  });
}

/** Every open deal on the rep's board, as `title → ticketId`. */
async function repBoard(sessions) {
  const mine = await (await sessions.sales.get('/api/sales/my-work')).json();
  return new Map(mine.board.map((d) => [d.title, d.ticketId]));
}

async function openDialog(page) {
  await expect(page.getByRole('region', { name: 'ภาพรวมทีมขาย' })).toBeVisible();
  await page.getByRole('button', { name: 'นำเข้าจากชีท' }).click();
  const dialog = page.getByTestId('pipeline-import-dialog');
  await expect(dialog).toBeVisible();
  return dialog;
}

async function chooseFile(dialog, buffer, name = 'pipeline.csv') {
  await dialog.locator('input[type="file"]').setInputFiles({ name, mimeType: 'text/csv', buffer });
}

test.describe.serial('pipeline import — happy path @pipeline-import', () => {
  let sessions;
  const created = new Set();

  test.beforeAll(async () => {
    sessions = await apiSessionsFor(['sales', 'sales_manager']);
  });
  test.afterAll(async () => {
    for (const id of created) await cancelDeal(sessions, id);
    await disposeSessions(sessions);
  });

  test('manager: dry run lists ✓ สร้าง and skips with reasons, writes nothing; นำเข้า commits; re-import creates 0', async ({ page }) => {
    await loginAs(page, 'sales_manager');
    const dialog = await openDialog(page);
    await chooseFile(dialog, MIXED);
    await dialog.getByRole('button', { name: 'ตรวจก่อนนำเข้า' }).click();

    // Dry-run table: the good row is ✓ สร้าง; each bad row is ! ข้าม with the server's own reason.
    const table = dialog.getByRole('table');
    await expect(table).toBeVisible();
    const goodTr = table.getByRole('row').filter({ hasText: GOOD_TITLE });
    await expect(goodTr).toContainText('✓ สร้าง');
    await expect(goodTr).toContainText('ลูกค้าใหม่');
    await expect(table.getByRole('row').filter({ hasText: 'โครงการ S4' })).toContainText('S4 ไม่มีในระบบ');
    await expect(table.getByRole('row').filter({ hasText: 'โครงการ S9' })).toContainText('ไม่นำเข้าจากชีท');
    await expect(table.getByRole('row').filter({ hasText: 'โครงการ ไม่มีเซลส์' })).toContainText('ไม่พบพนักงานขาย');
    await expect(table.getByRole('row').filter({ hasText: 'โอกาสผิด' })).toContainText('โอกาสขาย');
    for (const title of SKIPPED_TITLES) {
      await expect(table.getByRole('row').filter({ hasText: title })).toContainText('! ข้าม');
    }
    await expect(dialog.getByText('สร้าง 1', { exact: true })).toBeVisible();
    await expect(dialog.getByText('ข้าม 4', { exact: true })).toBeVisible();
    await expect(dialog.getByText('ยังไม่มีอะไรถูกบันทึกจนกว่าจะกดนำเข้า')).toBeVisible();
    await shot(page, 'pipeline-01-dry-run-desktop');

    // Server truth: a dry run wrote nothing.
    expect((await repBoard(sessions)).has(GOOD_TITLE), 'dry run must not create the deal').toBe(false);

    // Commit.
    const commitResponse = page.waitForResponse((r) => r.url().includes('/api/sales/pipeline-imports') && r.url().includes('dryRun=false'));
    await dialog.getByRole('button', { name: 'นำเข้า 1 แถว' }).click();
    const result = await (await commitResponse).json();
    expect(result).toMatchObject({ committed: true, toCreate: 1, toUpdate: 0, skipped: 4 });
    for (const p of result.plan) if (p.ticketId) created.add(p.ticketId);
    await expect(dialog.getByText('นำเข้าแล้ว')).toBeVisible();
    await expect(dialog.getByText('สร้างงานใหม่ 1')).toBeVisible();
    await shot(page, 'pipeline-02-committed-desktop');

    // The deal exists, owned by the rep, and only the good row became a deal.
    const board = await repBoard(sessions);
    expect(board.has(GOOD_TITLE), 'good row became a deal on the rep board').toBe(true);
    for (const title of SKIPPED_TITLES) expect(board.has(title), `skipped row "${title}" must not become a deal`).toBe(false);
  });

  test('re-uploading the same file creates 0 deals (idempotent)', async () => {
    const before = await repBoard(sessions);
    const res = await uploadImport(sessions.sales_manager, MIXED, { dryRun: false });
    expect(res.status()).toBe(200);
    const body = await res.json();
    expect(body.toCreate, 'second import must create nothing').toBe(0);
    for (const p of body.plan) if (p.ticketId) created.add(p.ticketId);
    const after = await repBoard(sessions);
    expect(after.size).toBe(before.size);
    expect([...after.keys()].filter((t) => t === GOOD_TITLE)).toHaveLength(1);
  });
});

test.describe('pipeline import — failure paths @pipeline-import', () => {
  test('POST /api/sales/pipeline-imports: sales, account, import, hr (and the rest) are 403; manager/ceo 200', async () => {
    const roles = ['sales', 'account', 'import', 'hr', 'employee', 'warehouse', 'qc', 'sales_manager', 'ceo'];
    const sessions = await apiSessionsFor(roles);
    try {
      for (const role of roles) {
        const res = await uploadImport(sessions[role], csvBuffer([s4Row]), { dryRun: true });
        const expected = role === 'sales_manager' || role === 'ceo' ? 200 : 403;
        expect(res.status(), `${role} POST pipeline-imports (dry run)`).toBe(expected);
      }
    } finally {
      await disposeSessions(sessions);
    }
  });

  test('a refused role writes nothing even on dryRun=false', async () => {
    const sessions = await apiSessionsFor(['sales', 'sales_manager']);
    const title = `${TAG} refused-commit`;
    try {
      const res = await uploadImport(sessions.sales, csvBuffer([[REP, title, 'ผู้ออกแบบ', `${TAG} ผู้รับเหมา`, '', '1000', 'S2', '50', '2026-Q4']]), { dryRun: false });
      expect(res.status()).toBe(403);
      expect((await repBoard(sessions)).has(title)).toBe(false);
    } finally {
      await disposeSessions(sessions);
    }
  });
});
