import { spawnSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { test, expect } from '@playwright/test';
import { anonApi, apiSessionsFor, apiWrite, disposeSessions, writeHeaders } from './helpers/api.js';
import { loginAs } from './helpers/auth.js';
import {
  advanceImportStep,
  cancelDeal,
  createDeal,
  createImportRequestDrafts,
  resolveFactory,
  runPricingChainToOrder,
  waiveDeposit,
} from './helpers/sales.js';

// ─────────────────────────────────────────────────────────────────────────────
// CASH + OPS SCREENS (sales redesign slices 3 and 5) — real Vite → Spring → Postgres, no mocks.
//
//   /api/tickets/{id}/expected-payments    owner / sales_manager / ceo write; account reads inside its scope
//   POST /api/finance/deals/{id}/payments  account only; 409 POSSIBLE_DUPLICATE unless ?confirmPossibleDuplicate=true
//   /api/sales/receipt-imports/**          account only (CEO deliberately excluded); the upload is OFF by default
//   POST /api/import-requests/{id}/advance-step[/undo]   import / ceo; undo = the same actor, within 30 s
//
// Preconditions the suite builds itself, never borrows: two deals driven through the real pricing chain to
// ORDER_RECEIVED (one for the cash journeys, one for the import journeys), minted per run and cancelled in afterAll.
//
// What psql is used for, and only this: (1) planting an EXPRESS-source receipt, because the upload that would
// normally create one is off by default; (2) backdating an import step's `step_advanced_at`, because the 30-second
// undo window cannot be waited out. Both skip with a stated reason when psql cannot be run (E2E_PSQL, PGHOST,
// PGPORT, PGDATABASE, PGUSER, PGPASSWORD — same variables sales-board.spec.js reads).
//
// Express upload ON: run again with APP_RECEIPTS_EXPRESS_UPLOAD_ENABLED=true on the backend; the describe tagged
// @express-on skips itself when the flag is off. Product defaults are
// not touched.
//
// Set E2E_SHOT_DIR to also save screenshots.
// ─────────────────────────────────────────────────────────────────────────────

process.env.E2E_RUN_ID ||= `e2e-cash-${Date.now().toString(36)}`;
const TAG = process.env.E2E_RUN_ID;

const SHOT_DIR = process.env.E2E_SHOT_DIR || '';
async function shot(page, name) {
  if (!SHOT_DIR) return;
  mkdirSync(SHOT_DIR, { recursive: true });
  await page.screenshot({ path: join(SHOT_DIR, `${name}.png`), fullPage: true });
}

function psql(sql) {
  const result = spawnSync(process.env.E2E_PSQL || 'psql', [
    '-h', process.env.PGHOST || '127.0.0.1',
    '-p', process.env.PGPORT || '5432',
    '-U', process.env.PGUSER || 'postgres',
    '-d', process.env.PGDATABASE || 'hris',
    '-v', 'ON_ERROR_STOP=1', '-At', '-c', sql,
  ], { env: { ...process.env, PGPASSWORD: process.env.PGPASSWORD || 'postgres' }, encoding: 'utf8' });
  if (result.error) return { error: `psql not runnable (${result.error.code}) — set E2E_PSQL` };
  if (result.status !== 0) return { error: `psql failed: ${result.stderr}` };
  return { out: result.stdout.trim() };
}

/** Monday of the current ISO week, Asia/Bangkok — the one day an expected payment's week may be. */
function thisMonday() {
  const iso = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Bangkok' }).format(new Date());
  const d = new Date(`${iso}T00:00:00Z`);
  const dow = (d.getUTCDay() + 6) % 7;
  d.setUTCDate(d.getUTCDate() - dow);
  return d.toISOString().slice(0, 10);
}
const todayIso = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Bangkok' }).format(new Date());

/** An even whole-baht amount no other row on the database is likely to share (the matcher and the duplicate check both key on it); even so its half is still a valid 2 dp amount. */
const uniqueSatang = () => 2 * (500 + (Date.now() % 3000));

async function financeDeal(session, ticketId) {
  const response = await session.get(`/api/finance/deals/${ticketId}`, { failOnStatusCode: false });
  expect(response.status(), `GET /api/finance/deals/${ticketId}`).toBe(200);
  return (await response.json()).deal;
}

async function expectedRows(session, ticketId) {
  const response = await session.get(`/api/tickets/${ticketId}/expected-payments`, { failOnStatusCode: false });
  expect(response.status(), `GET expected-payments of #${ticketId}`).toBe(200);
  return response.json();
}

async function postExpected(session, ticketId, body) {
  return apiWrite(session, 'post', `/api/tickets/${ticketId}/expected-payments`, body);
}

async function uploadReceipts(session, buffer, name = 'receipts.csv') {
  return session.fetch('/api/sales/receipt-imports', {
    method: 'POST',
    headers: await writeHeaders(session),
    multipart: { file: { name, mimeType: 'text/csv', buffer } },
    failOnStatusCode: false,
  });
}

async function expressEnabled(session) {
  const response = await session.get('/api/sales/receipt-imports/latest', { failOnStatusCode: false });
  expect(response.status(), 'account GET /api/sales/receipt-imports/latest').toBe(200);
  return (await response.json()).uploadEnabled === true;
}

async function summaryOf(session, ticketId) {
  const full = await (await session.get(`/api/tickets/${ticketId}`)).json();
  return (full.ticket ?? full).summary;
}

test.describe.serial('cash + ops screens — real pricing-chain deals @cash-ops', () => {
  /** @type {Record<string, import('@playwright/test').APIRequestContext>} */
  let sessions;
  const created = [];
  let cashDeal; // ORDER_RECEIVED, deposit required — the cash journeys
  let importDeal; // same, deposit waived and its ใบขอซื้อ issued — the import journeys
  let payable;

  test.beforeAll(async () => {
    test.setTimeout(300_000);
    sessions = await apiSessionsFor(['sales', 'sales_manager', 'import', 'ceo', 'account', 'employee', 'hr']);
    const factory = await resolveFactory(sessions);
    for (const caseName of ['cash journeys', 'import journeys']) {
      const deal = await createDeal(sessions, { caseName, entryChannel: 'DESIGNER_LED' });
      created.push(deal.id);
      await runPricingChainToOrder(sessions, deal.id, { factoryName: factory.factoryName, recipientType: 'DESIGNER' });
      deal.customerName = (await summaryOf(sessions.sales, deal.id)).customerName;
      if (caseName === 'cash journeys') cashDeal = deal; else importDeal = deal;
    }
    payable = Number((await financeDeal(sessions.account, cashDeal.id)).money.amountPayable);
    expect(payable, 'the pricing chain must leave a payable amount').toBeGreaterThan(0);
  });

  test.afterAll(async () => {
    test.setTimeout(120_000);
    for (const id of created) await cancelDeal(sessions, id);
    await disposeSessions(sessions);
  });

  // ── expected payments → the rep's เงินที่จะเข้า card ───────────────────────────────────────────

  test('rep adds an expected DEPOSIT for this week on the money tab; it shows in the เงินที่จะเข้า card', async ({ page }) => {
    const amount = Math.round(payable * 0.2 * 100) / 100;
    const before = await (await sessions.sales.get('/api/sales/my-work')).json();
    const depositsBefore = Number(before.cash?.depositsExpected ?? 0);

    await loginAs(page, 'sales');
    await page.goto(`/tickets/${cashDeal.id}?tab=money`);
    await expect(page.getByText('เงินที่คาดว่าจะเข้า').first()).toBeVisible();
    await page.getByRole('button', { name: '+ เพิ่ม' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    // Kind defaults to มัดจำ — the default the rep relies on.
    await expect(dialog.getByRole('radio', { name: 'มัดจำ' })).toHaveAttribute('aria-checked', 'true');
    await dialog.getByLabel('จำนวนเงิน').fill(String(amount));
    await shot(page, 'cash-01-expected-dialog');
    await dialog.getByRole('button', { name: 'บันทึก', exact: true }).click();
    await expect(dialog).toBeHidden();

    const table = page.getByRole('table', { name: 'เงินที่คาดว่าจะเข้า' });
    await expect(table).toContainText('มัดจำ');
    await expect(table).toContainText('รอ');
    await shot(page, 'cash-02-expected-row');

    const rows = await expectedRows(sessions.sales, cashDeal.id);
    const row = rows.find((r) => Number(r.amount) === amount);
    expect(row, 'the expected row is on the server').toBeTruthy();
    expect(row).toMatchObject({ kind: 'DEPOSIT', status: 'OPEN', expectedWeek: thisMonday() });

    // The rep's own card (งานของฉัน) now carries it.
    await page.goto('/');
    await expect(page.getByRole('heading', { name: /เงินที่จะเข้า/ })).toBeVisible();
    const after = await (await sessions.sales.get('/api/sales/my-work')).json();
    expect(Number(after.cash.depositsExpected)).toBeCloseTo(depositsBefore + amount, 2);
    await page.getByText('ดูเป็นตาราง').click();
    await expect(page.getByRole('table', { name: 'เงินที่จะเข้า รายสัปดาห์' })).toBeVisible();
    await shot(page, 'cash-03-rep-card');
  });

  test('account records a hand-typed payment from the บันทึกรับชำระ modal; the expected row turns รับแล้ว and received rises', async ({ page }) => {
    const rows = await expectedRows(sessions.sales, cashDeal.id);
    const open = rows.find((r) => r.status === 'OPEN' && r.kind === 'DEPOSIT');
    expect(open, 'the previous test left an open DEPOSIT row').toBeTruthy();
    const amount = Number(open.amount);
    const receivedBefore = Number((await (await sessions.sales.get('/api/sales/my-work')).json()).cash.received);

    await loginAs(page, 'account');
    await page.goto(`/finance/deals/${cashDeal.id}`);
    await page.getByRole('button', { name: 'บันทึกรับชำระ' }).first().click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await shot(page, 'cash-04-record-payment-modal');
    await dialog.getByLabel('ประเภท').selectOption('DEPOSIT');
    await dialog.getByLabel('จำนวนเงิน').fill(String(amount));
    await dialog.getByLabel('เลขอ้างอิง').fill(`${TAG}-hand-1`);
    await dialog.getByRole('button', { name: 'บันทึกรับชำระ' }).click();
    await expect(dialog).toBeHidden();
    await shot(page, 'cash-05-after-record');

    await expect.poll(async () => {
      const list = await expectedRows(sessions.sales, cashDeal.id);
      return list.find((r) => r.id === open.id)?.status;
    }, { message: 'the expected DEPOSIT row is completed by the hand-typed receipt' }).toBe('RECEIVED');
    const done = (await expectedRows(sessions.sales, cashDeal.id)).find((r) => r.id === open.id);
    expect(Number(done.receivedAmount)).toBeCloseTo(amount, 2);
    expect(done.receiptRef).toBe(`${TAG}-hand-1`);
    const receivedAfter = Number((await (await sessions.sales.get('/api/sales/my-work')).json()).cash.received);
    expect(receivedAfter).toBeCloseTo(receivedBefore + amount, 2);

    // The rep sees the row as รับแล้ว on the money tab.
    await page.context().clearCookies();
    await page.goto('/');
    await loginAs(page, 'sales');
    await page.goto(`/tickets/${cashDeal.id}?tab=money`);
    await expect(page.getByRole('table', { name: 'เงินที่คาดว่าจะเข้า' })).toContainText('รับแล้ว');
    await shot(page, 'cash-06-rep-sees-received');
  });

  test('editing an expected row below what already came in is refused with a 409 the rep can read', async ({ page }) => {
    // A BALANCE row of 2x, half paid by account: the row stays OPEN with receivedAmount = x.
    const x = Math.round(uniqueSatang() * 100) / 100;
    const made = await postExpected(sessions.sales, cashDeal.id, { kind: 'BALANCE', amount: x * 2, expectedWeek: thisMonday() });
    expect(made.status()).toBe(200);
    const row = await made.json();
    const paid = await apiWrite(sessions.account, 'post', `/api/finance/deals/${cashDeal.id}/payments`, {
      kind: 'BALANCE', amount: x, receiptRef: `${TAG}-half`, allowOverpayment: false,
    });
    expect(paid.status(), await paid.text()).toBe(200);
    const partial = (await expectedRows(sessions.sales, cashDeal.id)).find((r) => r.id === row.id);
    expect(partial.status).toBe('OPEN');
    expect(Number(partial.receivedAmount)).toBeCloseTo(x, 2);

    // API: below received → 409; the wrong-way-round case.
    const low = await apiWrite(sessions.sales, 'put', `/api/tickets/${cashDeal.id}/expected-payments/${row.id}`, {
      kind: 'BALANCE', amount: x / 2, expectedWeek: thisMonday(),
    });
    expect(low.status()).toBe(409);
    expect(await low.text()).toContain('รับเงินรายการนี้มาแล้ว');
    // …and a change of kind on a part-paid row is refused too.
    const kind = await apiWrite(sessions.sales, 'put', `/api/tickets/${cashDeal.id}/expected-payments/${row.id}`, {
      kind: 'ON_DELIVERY', amount: x * 2, expectedWeek: thisMonday(),
    });
    expect(kind.status()).toBe(409);

    // UI: the same edit shows the server's message to the rep.
    await loginAs(page, 'sales');
    await page.goto(`/tickets/${cashDeal.id}?tab=money`);
    const tr = page.getByRole('table', { name: 'เงินที่คาดว่าจะเข้า' }).getByRole('row').filter({ hasText: 'ส่วนที่เหลือ' });
    await tr.getByRole('button', { name: 'แก้ไข' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('จำนวนเงิน').fill(String(x / 2));
    await dialog.getByRole('button', { name: 'บันทึก', exact: true }).click();
    await expect(page.getByText(/รับเงินรายการนี้มาแล้ว/).first()).toBeVisible();
    await shot(page, 'cash-07-edit-below-received-409');
    await dialog.getByRole('button', { name: 'ยกเลิก' }).click();
    // The row is unchanged on the server.
    const still = (await expectedRows(sessions.sales, cashDeal.id)).find((r) => r.id === row.id);
    expect(Number(still.amount)).toBeCloseTo(x * 2, 2);
  });

  // ── possible duplicate of an Express receipt ──────────────────────────────────────────────────

  test('a hand-typed receipt that twins an Express one is held with 409 POSSIBLE_DUPLICATE; บันทึกต่อ records it', async ({ page }) => {
    const amount = Math.round(uniqueSatang() * 100) / 100 + 0.01;
    // The upload is off by default, so the Express receipt is planted: the same row the upload would write
    // (TicketRepository#insertExpressReceipt) — source EXPRESS, the Express document number as receipt_ref.
    const planted = psql(`INSERT INTO sales.payment_receipt (ticket_id, kind, amount, currency, received_at, recorded_by, receipt_ref, source, customer_name)
      SELECT ${Number(cashDeal.id)}, 'BALANCE', ${amount}, 'THB', now(), employee_id, 'RE-${TAG}-twin', 'EXPRESS', 'e2e'
        FROM hr.employee WHERE employee_code = 'DEMO-ACC01' RETURNING receipt_id`);
    test.skip(Boolean(planted.error), `cannot plant the Express receipt: ${planted.error}`);

    // API: no confirm flag → 409 carrying the code and the twin's number.
    const refused = await apiWrite(sessions.account, 'post', `/api/finance/deals/${cashDeal.id}/payments`, {
      kind: 'BALANCE', amount, receiptRef: `${TAG}-hand-dup-api`, allowOverpayment: false,
    });
    expect(refused.status()).toBe(409);
    const body = await refused.text();
    expect(body).toContain('POSSIBLE_DUPLICATE');
    expect(body).toContain(`RE-${TAG}-twin`);

    // UI: the same amount typed in the modal is held; the dialog names the twin; บันทึกต่อ records it.
    await loginAs(page, 'account');
    await page.goto(`/finance/deals/${cashDeal.id}`);
    await page.getByRole('button', { name: 'บันทึกรับชำระ' }).first().click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('ประเภท').selectOption('BALANCE');
    await dialog.getByLabel('จำนวนเงิน').fill(String(amount));
    await dialog.getByLabel('เลขอ้างอิง').fill(`${TAG}-hand-dup-ui`);
    await dialog.getByRole('button', { name: 'บันทึกรับชำระ' }).click();
    const warning = page.getByRole('dialog').filter({ hasText: 'อาจซ้ำกับรายการที่อัปโหลดแล้ว' });
    await expect(warning).toBeVisible();
    await expect(warning).toContainText(`RE-${TAG}-twin`);
    await shot(page, 'cash-08-possible-duplicate');
    // Nothing recorded yet.
    const before = (await financeDeal(sessions.account, cashDeal.id)).money.payments
      .filter((p) => p.receiptRef === `${TAG}-hand-dup-ui`);
    expect(before).toHaveLength(0);

    await warning.getByRole('button', { name: 'เป็นคนละรายการ บันทึกต่อ' }).click();
    await expect.poll(async () => (await financeDeal(sessions.account, cashDeal.id)).money.payments
      .filter((p) => p.receiptRef === `${TAG}-hand-dup-ui`).length).toBe(1);
    await shot(page, 'cash-09-duplicate-confirmed');
  });

  // ── authz at the HTTP boundary (real service) ─────────────────────────────────────────────────

  test('expected payments: roles that do not own the deal cannot write; account may read but not write', async () => {
    const body = { kind: 'DEPOSIT', amount: 1234.56, expectedWeek: thisMonday() };
    for (const role of ['import', 'employee', 'hr', 'account']) {
      const response = await postExpected(sessions[role], cashDeal.id, body);
      expect(response.status(), `${role} POST expected-payments`).toBe(403);
    }
    const accountRead = await sessions.account.get(`/api/tickets/${cashDeal.id}/expected-payments`, { failOnStatusCode: false });
    expect(accountRead.status(), 'account reads inside its finance scope').toBe(200);
    for (const role of ['import', 'employee', 'hr']) {
      const response = await sessions[role].get(`/api/tickets/${cashDeal.id}/expected-payments`, { failOnStatusCode: false });
      expect(response.status(), `${role} GET expected-payments`).toBe(403);
    }
    // Nothing leaked in.
    const rows = await expectedRows(sessions.sales, cashDeal.id);
    expect(rows.some((r) => Number(r.amount) === 1234.56)).toBe(false);
  });

  // ── receipt-imports: account only ───────────────────────────────────────────────────
  test('receipt-imports: CEO, sales and import are refused (account only); the CEO route guard hides the page', async ({ page }) => {
    const csv = Buffer.from('\uFEFFเลขที่เอกสาร,วันที่,จำนวนเงิน\r\nRE-X,2026-11-16,100\r\n', 'utf8');
    for (const role of ['ceo', 'sales', 'import']) {
      const upload = await uploadReceipts(sessions[role], csv);
      expect(upload.status(), `${role} POST receipt-imports`).toBe(403);
      for (const path of ['unmatched', 'ignored', 'latest', 'template']) {
        const response = await sessions[role].get(`/api/sales/receipt-imports/${path}`, { failOnStatusCode: false });
        expect(response.status(), `${role} GET receipt-imports/${path}`).toBe(403);
      }
    }
    const anon = await anonApi();
    expect((await anon.post('/api/sales/receipt-imports', { failOnStatusCode: false })).status()).toBe(401);
    await anon.dispose();

    await loginAs(page, 'ceo');
    await page.goto('/finance/receipts-import');
    await expect(page.getByRole('heading', { name: /Express/ })).toHaveCount(0);
    await shot(page, 'cash-12-ceo-route-guard');
  });

  // ── import: the grouped worklist and the 30-second undo ───────────────────────────────────────

  test('import sees the grouped งานนำเข้า worklist; advancing a step on /fulfilment shows the undo snackbar and เลิกทำ reverts it', async ({ page }) => {
    await waiveDeposit(sessions, importDeal.id, { reason: `${TAG} e2e: no deposit needed for the import journey` });
    const drafts = await createImportRequestDrafts(sessions, importDeal.id);
    expect(drafts.length).toBeGreaterThan(0);
    const row = drafts[0];
    const startStep = row.importStep;

    await loginAs(page, 'import');
    await expect(page.getByRole('heading', { name: 'งานนำเข้า' })).toBeVisible();
    await expect(page.getByText('จัดกลุ่มตามขั้นต่อไป').first()).toBeVisible();
    await shot(page, 'cash-13-import-worklist');

    await page.goto('/fulfilment');
    const deal = page.locator(`[data-testid="fulfilment-deal"][data-ticket-id="${importDeal.id}"]`);
    await expect(deal).toBeVisible();
    await deal.getByTestId(`advance-${row.id}`).click();
    const snackbar = page.getByTestId('step-undo-snackbar');
    await expect(snackbar).toBeVisible();
    await expect(snackbar).toContainText('เลื่อนเป็น');
    await shot(page, 'cash-14-undo-snackbar');
    const advanced = await (await sessions.import.get(`/api/import-requests/${row.id}`)).json();
    expect((advanced.importRequest ?? advanced).importStep).not.toBe(startStep);

    await snackbar.getByRole('button', { name: 'เลิกทำ' }).click();
    await expect(page.getByText('เลิกทำการเลื่อนขั้นตอนแล้ว')).toBeVisible();
    await expect(snackbar).toBeHidden();
    const reverted = await (await sessions.import.get(`/api/import-requests/${row.id}`)).json();
    expect((reverted.importRequest ?? reverted).importStep).toBe(startStep);
    await shot(page, 'cash-15-after-undo');
  });

  test('undo is refused for a different actor (403) and after the 30 s window (409)', async () => {
    const rows = (await (await sessions.import.get(`/api/tickets/${importDeal.id}/import-requests`)).json()).importRequests;
    const row = rows[0];
    const step = (await advanceImportStep(sessions, row.id, 'ORDERED')).importStep;
    expect(step).toBe('ORDERED');

    // Another import-capable user (the CEO) did not advance it: refused, step unchanged.
    const other = await apiWrite(sessions.ceo, 'post', `/api/import-requests/${row.id}/advance-step/undo`, { expectedStep: 'ORDERED' });
    expect(other.status(), await other.text()).toBe(403);
    const still = await (await sessions.import.get(`/api/import-requests/${row.id}`)).json();
    expect((still.importRequest ?? still).importStep).toBe('ORDERED');

    // Past the window: backdate the advance (the window is server-clock; it cannot be waited out in an e2e).
    const aged = psql(`UPDATE sales.import_request SET step_advanced_at = now() - interval '2 minutes' WHERE import_request_id = ${Number(row.id)}`);
    test.skip(Boolean(aged.error), `cannot age the step: ${aged.error}`);
    const late = await apiWrite(sessions.import, 'post', `/api/import-requests/${row.id}/advance-step/undo`, { expectedStep: 'ORDERED' });
    expect(late.status()).toBe(409);
    expect(await late.text()).toContain('เลยเวลาเลิกทำ');
    const final = await (await sessions.import.get(`/api/import-requests/${row.id}`)).json();
    expect((final.importRequest ?? final).importStep).toBe('ORDERED');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// Express upload ON (APP_RECEIPTS_EXPRESS_UPLOAD_ENABLED=true on the backend). Skips itself otherwise.
// ─────────────────────────────────────────────────────────────────────────────
test.describe.serial('Express receipts upload, flag ON @cash-ops @express-on', () => {
  let sessions;
  let deal;
  let enabled = false;

  test.beforeAll(async () => {
    test.setTimeout(300_000);
    sessions = await apiSessionsFor(['sales', 'import', 'ceo', 'account']);
    enabled = await expressEnabled(sessions.account);
    if (!enabled) return;
    const factory = await resolveFactory(sessions);
    deal = await createDeal(sessions, { caseName: 'express upload', entryChannel: 'DESIGNER_LED' });
    await runPricingChainToOrder(sessions, deal.id, { factoryName: factory.factoryName, recipientType: 'DESIGNER' });
    deal.customerName = (await summaryOf(sessions.sales, deal.id)).customerName;
  });
  test.afterAll(async () => {
    if (deal) await cancelDeal(sessions, deal.id);
    await disposeSessions(sessions);
  });

  test('account uploads an RE-only CSV: the row matches the open expected payment and it turns รับแล้ว; a re-upload is a duplicate', async ({ page }) => {
    test.skip(!enabled, 'Express upload is OFF on this backend (the product default)');
    const amount = Math.round(uniqueSatang() * 100) / 100;
    const made = await postExpected(sessions.sales, deal.id, { kind: 'BALANCE', amount, expectedWeek: thisMonday() });
    expect(made.status()).toBe(200);
    const expected = await made.json();

    const template = await (await sessions.account.get('/api/sales/receipt-imports/template')).text();
    const header = template.replace(/^\uFEFF/, '').split(/\r?\n/)[0];
    const columns = header.split(',');
    // Fill by header name so a changed alias table cannot silently shift the columns.
    const cell = { 'เลขที่เอกสาร': `RE-${TAG}-1`, 'ประเภทเอกสาร': 'RE', 'วันที่': todayIso(), 'ลูกค้า': deal.customerName, 'จำนวนเงิน': String(amount), 'อ้างอิง': '' };
    const line = columns.map((c) => (/[",]/.test(cell[c] ?? '') ? `"${(cell[c] ?? '').replaceAll('"', '""')}"` : (cell[c] ?? ''))).join(',');
    const csv = Buffer.from(`\uFEFF${header}\r\n${line}\r\n`, 'utf8');

    const upload = await uploadReceipts(sessions.account, csv);
    expect(upload.status(), await upload.text()).toBe(200);
    const summary = await upload.json();
    expect(summary).toMatchObject({ matched: 1 });

    const row = (await expectedRows(sessions.sales, deal.id)).find((r) => r.id === expected.id);
    expect(row.status).toBe('RECEIVED');
    expect(row.receiptRef).toBe(`RE-${TAG}-1`);

    // The same file again: the document number is already in; nothing new is placed.
    const again = await (await uploadReceipts(sessions.account, csv)).json();
    expect(again.matched).toBe(0);
    expect(again.duplicates).toBe(1);

    await loginAs(page, 'account');
    await page.goto('/finance');
    await expect(page.getByText('อัปโหลดไฟล์รับเงินจาก Express')).toBeVisible();
    await shot(page, 'cash-16-express-on-account-landing');
    await page.goto('/finance/receipts-import');
    await shot(page, 'cash-17-express-on-page');
  });
});
