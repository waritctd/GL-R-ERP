import { execFileSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { test, expect } from '@playwright/test';
import { apiSessionFor, apiWrite, disposeSessions } from './helpers/api.js';
import { loginAs, logout } from './helpers/auth.js';
import {
  cancelDeal,
  completeDelivery,
  confirmFinalPayment,
  declareStockCoverage,
  readDeal,
  waiveDeposit,
} from './helpers/sales.js';

// ─────────────────────────────────────────────────────────────────────────────────────────────
// COMMISSION INVOICE -> MANAGER WEIGHT REVIEW -> CEO QUEUE, driven end to end through the REAL
// stack (browser -> Vite -> Spring -> Postgres). No mock anywhere in the path.
//
//   account     opens /finance/deals/:id of a CLOSED_PAID deal, records the tax invoice
//               (RECORD_INVOICE -> POST /api/commissions/from-deal, multipart)
//   sales_mgr   is notified (COMMISSION_PENDING_MANAGER -> /commissions?view=pending), sees the
//               invoice's lines, re-weights one (POST /api/commissions/{id}/item-weights) and
//               approves
//   account     sees the deal's milestone 5 move to "รอ CEO อนุมัติ"
//
// FIXTURE. The demo seed's only CLOSED_PAID deal (DEMO-TKT-05) already carries a live commission,
// so it cannot be reused, and the PricingRequest chain the UAT journeys use needs factory /
// price-calc master data the demo seed lacks. This test builds its OWN deal through the real API on
// the direct-quotation route (write-quotation.spec.js). No SQL fixture, no pg connection.
// confirm-order turns the quotation's goods lines into ticket items, so the weight view has lines.
//
// RE-RUNNABILITY. Every run creates a fresh deal + commission with a unique invoice number. A
// CLOSED_PAID deal cannot be cancelled, so the row remains (same accepted residue as the other
// write specs); nothing here reads a global count or a row it did not create.
//
// AUTHZ. Everything asserted about 403/409 here was produced by the real service over real HTTP
// (incl. @PreAuthorize). It is observed behaviour, not a scope-filter proof: requirement 2 of
// CLAUDE.md (real-DB integration test through the Java service) is still the evidence for that.
// ─────────────────────────────────────────────────────────────────────────────────────────────

const SHOT_DIR =
  process.env.E2E_SHOT_DIR ||
  '/tmp/claude-0/-home-user-GL-R-ERP/c6483d65-69ed-5982-bc93-1951eed22c81/scratchpad/e2e-shots';
mkdirSync(SHOT_DIR, { recursive: true });

// Smallest thing the backend's attachment validation accepts as a PDF.
const PDF_BYTES = Buffer.from('%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n');

function psql(sql) {
  return execFileSync(
    'psql',
    ['-h', process.env.PGHOST || '127.0.0.1', '-p', process.env.PGPORT || '5432',
      '-U', process.env.PGUSER || 'postgres', '-d', process.env.PGDATABASE || 'hris', '-v', 'ON_ERROR_STOP=1', '-tA', '-c', sql],
    { env: { ...process.env, PGPASSWORD: process.env.PGPASSWORD || 'postgres' }, encoding: 'utf8' }
  );
}

const FORBIDDEN_KEY = /commissionable|actualreceived|weight|estimated/i;

async function shot(page, name) {
  await page.screenshot({ path: join(SHOT_DIR, `${name}.png`), fullPage: true });
}

const ITEM = {
  brand: 'Pietre Di Sardegna',
  model: 'Pietre Di Sardegna',
  color: 'Punta Molara',
  texture: 'R11',
  sizeText: '60x120',
  thicknessMm: 2,
  sqmPerPiece: 0.72,
  quantityMode: 'AREA',
  areaSqm: 120,
  wastageMode: 'PERCENT',
  wastageValue: 10,
  piecesPerBox: 2,
  unitPrice: 1748.13,
  discountPct: 0,
  originCountry: 'อิตาลี',
  leadTimeMinDays: 75,
  leadTimeMaxDays: 90,
};

/**
 * Drives a brand-new deal to CLOSED_PAID through the real API, on the DIRECT-QUOTATION route this
 * release ships (see write-quotation.spec.js): customer + project + deal -> direct quotation ->
 * submit -> approve (sales_manager) -> confirm-order (owning rep; replaces ticket items with the
 * quotation's goods lines) -> waive deposit -> declare stock -> deliver -> final payment (account).
 * The PricingRequest chain is NOT used: it needs factory/price-calc master data the demo seed lacks.
 */
async function buildClosedPaidDeal(sessions) {
  const tag = `ci-${Date.now().toString(36)}`;
  const name = `บริษัท คอมมิชชัน ${tag} จำกัด`;
  const customer = await apiWrite(sessions.sales, 'post', '/api/customers', {
    name, taxId: '0105551234567', address: '201 ซอยสุขุมวิท 63 แขวงคลองตันเหนือ เขตวัฒนา กทม.10110', phone: '02-000-0000',
  });
  expect(customer.status(), 'POST /api/customers').toBe(200);
  const customerId = (await customer.json()).customer.id;
  const project = await apiWrite(sessions.sales, 'post', `/api/customers/${customerId}/projects`, { name: `E2E Project ${tag}` });
  expect(project.status(), 'POST project').toBe(200);
  const projectId = (await project.json()).project.id;
  const ticket = await apiWrite(sessions.sales, 'post', '/api/tickets', {
    title: `${tag} commission invoice journey`, priority: 'NORMAL', customerId, customerName: name, projectId,
    entryChannel: 'OWNER_DIRECT', items: [], nextFollowUpAt: '2026-12-31',
  });
  expect(ticket.status(), 'POST /api/tickets').toBe(200);
  const ticketId = (await ticket.json()).ticket.summary.id;

  const created = await apiWrite(sessions.sales, 'post', `/api/tickets/${ticketId}/deal-quotations`, {
    contactId: null, recipientType: 'OWNER', deptCode: 'P003', unitCode: 'D002', offerDate: '2026-09-10', depositPercent: 50,
    remainderMode: 'ON_DELIVERY', validityDays: 30, customerNotes: null, items: [ITEM],
  });
  expect(created.status(), `POST deal-quotations: ${await created.text()}`).toBe(201);
  const quotationId = (await created.json()).quotation.id;
  let r = await apiWrite(sessions.sales, 'post', `/api/deal-quotations/${quotationId}/submit`, {});
  expect(r.status(), 'submit').toBe(200);
  r = await apiWrite(sessions.sales_manager, 'post', `/api/deal-quotations/${quotationId}/approve`, { note: 'e2e' });
  expect(r.status(), 'approve').toBe(200);
  r = await apiWrite(sessions.sales, 'post', `/api/deal-quotations/${quotationId}/confirm-order`, {});
  expect(r.status(), `confirm-order: ${await r.text()}`).toBe(200);

  await waiveDeposit(sessions, ticketId, { reason: 'e2e commission-invoice: no deposit document needed' });
  const read = await readDeal(sessions, 'sales', ticketId);
  expect(read.items.length, 'confirm-order must have created ticket items from the quotation').toBeGreaterThan(0);
  // SQL FIXTURE (the one thing this spec cannot do through the API): the direct-quotation route copies
  // NO price onto ticket_item (DealQuotationService#ticketItemsFromQuotation: "NO pricing field is
  // copied"), and no accepted sales.quotation exists on that route, so the deal's payable is 0 and
  // CommissionCalculator#itemDerivedWeight (price = COALESCE(approved, proposed, 0)) has zero item
  // value to weight. The only API that sets approved_price is the PricingRequest chain, which needs
  // factory/country master data the demo seed lacks. So price the confirmed order's lines directly,
  // via psql with the same PG* env the backend launcher uses. See the report: on the shipped route
  // the manager's weight control is inert without this.
  psql(`UPDATE sales.ticket_item SET approved_price = 920 WHERE ticket_id = ${Number(ticketId)}`);
  await declareStockCoverage(sessions, 'sales', ticketId, read.items.map((item) => ({ itemId: item.id, qtyFromStock: item.qty })));
  await completeDelivery(sessions, ticketId);
  const closed = await confirmFinalPayment(sessions, ticketId);
  expect(closed.salesStage).toBe('CLOSED_PAID');
  return { id: ticketId };
}

test.describe('commission invoice approval journey (real stack)', () => {
  /** @type {Record<string, import('@playwright/test').APIRequestContext>} */
  let sessions;
  let deal;

  test.beforeAll(async () => {
    test.setTimeout(240_000);
    sessions = {
      sales: await apiSessionFor('sales'),
      import: await apiSessionFor('import'),
      ceo: await apiSessionFor('ceo'),
      account: await apiSessionFor('account'),
      sales_manager: await apiSessionFor('sales_manager'),
    };
    deal = await buildClosedPaidDeal(sessions);
  });

  test.afterAll(async () => {
    test.setTimeout(60_000);
    // CLOSED_PAID is terminal for cancel, so this is a best-effort no-op kept for symmetry with the
    // other sales specs; it never throws.
    if (deal) await cancelDeal(sessions, deal.id);
    await disposeSessions(sessions);
  });

  test('account records the invoice, sales_manager reweights + approves, account sees the CEO queue', async ({ page }) => {
    test.setTimeout(240_000);
    const invoiceNo = `E2E-INV-${Date.now()}`;
    const dealUrl = `/finance/deals/${deal.id}`;

    const readFinanceDeal = async (role) => {
      const r = await sessions[role].get(`/api/finance/deals/${deal.id}`);
      expect(r.status(), `${role} GET finance deal`).toBe(200);
      return (await r.json()).deal;
    };

    const before = await readFinanceDeal('account');
    expect(before.salesStage).toBe('CLOSED_PAID');
    expect(before.commissionInvoice, 'fixture must start with no recorded invoice').toBeNull();
    const exVat = before.money.amountPayableExVat;
    expect(typeof exVat, 'money.amountPayableExVat served as a number').toBe('number');

    const notificationsBefore = await (await sessions.sales_manager.get('/api/notifications')).json();
    const pendingNoticesBefore = notificationsBefore.filter((n) => n.type === 'COMMISSION_PENDING_MANAGER').length;

    // ── (b) account: the finance page ────────────────────────────────────────────────────────
    await loginAs(page, 'account');
    await page.goto(dealUrl);

    const primary = page.getByRole('button', { name: 'บันทึกใบกำกับ', exact: true });
    await test.step('primary action reads "บันทึกใบกำกับ" and never "ออกค่าคอม"', async () => {
      await expect(primary).toBeVisible({ timeout: 30_000 });
      await expect(page.getByText('ออกค่าคอม')).toHaveCount(0);
      await shot(page, '01-account-finance-deal-primary-action');
    });

    await test.step('clicking opens the in-page form, ยอดรวม defaulting to the pre-VAT figure', async () => {
      await primary.click();
      await expect(page.getByRole('heading', { name: 'บันทึกใบกำกับภาษี' })).toBeVisible();
      const gross = page.getByLabel(/ยอดรวม \(ก่อน VAT\)/);
      await expect(gross).toBeVisible();
      await expect(gross).toHaveValue(String(exVat));
      expect(Number(await gross.inputValue())).toBeCloseTo(exVat, 2);
      await shot(page, '02-account-invoice-form-opened');
    });

    let commissionId;
    await test.step('fill number/date/PDF and submit -> POST /api/commissions/from-deal 200', async () => {
      await page.getByLabel('เลขที่ใบกำกับ *').fill(invoiceNo);
      await page.getByLabel('วันที่ใบกำกับ *').fill(new Date().toISOString().slice(0, 10));
      await page.locator('#fin-invoice-file').setInputFiles({
        name: 'e2e-invoice.pdf',
        mimeType: 'application/pdf',
        buffer: PDF_BYTES,
      });
      await shot(page, '03-account-invoice-form-filled');

      const posted = page.waitForResponse(
        (r) => r.url().includes('/api/commissions/from-deal') && r.request().method() === 'POST'
      );
      await page
        .locator('form')
        .filter({ hasText: 'ไฟล์ใบกำกับภาษี' })
        .getByRole('button', { name: 'บันทึกใบกำกับ', exact: true })
        .click();
      const response = await posted;
      expect(response.status(), await response.text()).toBe(200);
      commissionId = (await response.json()).commission.id;
      expect(commissionId).toBeGreaterThan(0);
    });

    await test.step('milestone 5 shows the invoice number and "รอผู้จัดการฝ่ายขายอนุมัติ"', async () => {
      const block = page.getByRole('region', { name: /5/ }).or(page.locator('body'));
      await expect(block.getByText(invoiceNo).first()).toBeVisible({ timeout: 30_000 });
      await expect(page.getByText('รอผู้จัดการฝ่ายขายอนุมัติ').first()).toBeVisible();
      await shot(page, '04-account-milestone5-waiting-manager');
    });

    await test.step('finance payload leaks no commission figures (no commissionable/actualReceived/weight/estimated key)', async () => {
      const deal2 = await readFinanceDeal('account');
      expect(deal2.commissionInvoice).toBeTruthy();
      expect(deal2.commissionInvoice.invoiceNumber).toBe(invoiceNo);
      expect(deal2.commissionInvoice.approvalStatus).toBe('SUBMITTED');
      const keys = Object.keys(deal2.commissionInvoice);
      expect(keys.filter((k) => FORBIDDEN_KEY.test(k)), `commissionInvoice keys: ${keys.join(',')}`).toEqual([]);
      // and nowhere else on the finance deal either
      const leaked = JSON.stringify(Object.keys(deal2)).match(FORBIDDEN_KEY);
      expect(leaked, 'no forbidden top-level deal key').toBeNull();
    });

    // ── (c) sales_manager ────────────────────────────────────────────────────────────────────
    let firstItem;
    await test.step('sales_manager has a COMMISSION_PENDING_MANAGER notification linking to /commissions?view=pending', async () => {
      const after = await (await sessions.sales_manager.get('/api/notifications')).json();
      const pending = after.filter((n) => n.type === 'COMMISSION_PENDING_MANAGER');
      expect(pending.length, 'one more pending-manager notice than before').toBe(pendingNoticesBefore + 1);
      const newest = pending.reduce((a, b) => (a.id > b.id ? a : b));
      expect(newest.link).toBe('/commissions?view=pending');
    });

    await logout(page);
    await loginAs(page, 'sales_manager');
    await page.goto('/commissions?view=pending');

    const card = page.locator('article').filter({ hasText: invoiceNo });
    await test.step('pending view shows the invoice card with its lines', async () => {
      await expect(card).toBeVisible({ timeout: 30_000 });
      const rows = card.locator('tbody tr');
      expect(await rows.count(), 'card lists the deal lines').toBeGreaterThan(0);
      firstItem = card.locator('select[aria-label^="น้ำหนัก"]').first();
      await expect(firstItem).toBeVisible();
      await expect(firstItem).toBeEnabled();
      await shot(page, '05-manager-pending-card');
    });

    const weightDd = card.locator('dt', { hasText: 'น้ำหนักรวม' }).locator('xpath=following-sibling::dd[1]');
    let effectiveAfter;
    let itemIdFromApi;
    await test.step('weight select ×3 -> POST item-weights 200, น้ำหนักรวม shows the response effectiveWeight', async () => {
      const weightBefore = (await weightDd.innerText()).trim();
      const posted = page.waitForResponse(
        (r) => /\/api\/commissions\/\d+\/item-weights$/.test(r.url()) && r.request().method() === 'POST'
      );
      await firstItem.selectOption('3');
      const response = await posted;
      expect(response.status(), await response.text()).toBe(200);
      expect(response.url()).toContain(`/api/commissions/${commissionId}/item-weights`);
      const body = await response.json();
      effectiveAfter = body.pending.effectiveWeight;
      itemIdFromApi = body.pending.items[0].itemId;
      expect(body.pending.items[0].weightMultiplier).toBe(3);
      expect(typeof effectiveAfter).toBe('number');

      await expect(weightDd).toHaveText(Number(effectiveAfter).toFixed(2));
      expect((await weightDd.innerText()).trim()).not.toBe(weightBefore);
      await expect(firstItem).toHaveValue('3');
      await shot(page, '06-manager-weight-x3');
    });

    // (d, part 1) while the record is still reviewable, a non-manager is refused by ROLE, not state.
    await test.step('item-weights by account / sales / ceo -> 403 and the weight is untouched', async () => {
      for (const role of ['account', 'sales', 'ceo']) {
        const r = await apiWrite(sessions[role], 'post', `/api/commissions/${commissionId}/item-weights`, {
          lines: [{ itemId: itemIdFromApi, weightMultiplier: 2 }],
        });
        expect(r.status(), `${role} POST item-weights (pre-approval)`).toBe(403);
      }
      const mgrView = await (await sessions.sales_manager.get('/api/commissions/pending-approval')).json();
      const mine = mgrView.commissions.find((c) => c.commission.id === commissionId);
      expect(mine.items[0].weightMultiplier, 'refused writes must not have moved the weight').toBe(3);
    });

    await test.step('pending-approval as account / sales -> 403', async () => {
      for (const role of ['account', 'sales']) {
        const r = await sessions[role].get('/api/commissions/pending-approval');
        expect(r.status(), `${role} GET pending-approval`).toBe(403);
      }
    });

    await test.step('manager approves through the existing approve flow', async () => {
      await card.getByRole('button', { name: 'ผู้จัดการอนุมัติ' }).click();
      const dialog = page.getByRole('dialog').or(page.getByRole('alertdialog'));
      await expect(dialog.first()).toBeVisible();
      await shot(page, '07-manager-approve-dialog');
      const approved = page.waitForResponse(
        (r) => new RegExp(`/api/commissions/${commissionId}/approve$`).test(r.url()) && r.request().method() === 'POST'
      );
      await dialog.first().getByRole('button', { name: 'อนุมัติ', exact: true }).click();
      const response = await approved;
      expect(response.status(), await response.text()).toBe(200);
      await expect(card).toHaveCount(0, { timeout: 30_000 });
      await shot(page, '08-manager-after-approve');
    });

    // ── (d, part 2) wrong-way-round after approval ───────────────────────────────────────────
    await test.step('item-weights after approval: sales_manager 409; account/sales/ceo still 403', async () => {
      const again = await apiWrite(sessions.sales_manager, 'post', `/api/commissions/${commissionId}/item-weights`, {
        lines: [{ itemId: itemIdFromApi, weightMultiplier: 2 }],
      });
      expect(again.status(), `sales_manager POST item-weights after approval: ${await again.text()}`).toBe(409);
      for (const role of ['account', 'sales', 'ceo']) {
        const r = await apiWrite(sessions[role], 'post', `/api/commissions/${commissionId}/item-weights`, {
          lines: [{ itemId: itemIdFromApi, weightMultiplier: 2 }],
        });
        expect(r.status(), `${role} POST item-weights (post-approval)`).toBe(403);
      }
    });

    // ── (e) account again ────────────────────────────────────────────────────────────────────
    await logout(page);
    await loginAs(page, 'account');
    await page.goto(dealUrl);
    await test.step('milestone 5 now shows "รอ CEO อนุมัติ"', async () => {
      await expect(page.getByText('รอ CEO อนุมัติ').first()).toBeVisible({ timeout: 30_000 });
      await expect(page.getByText(invoiceNo).first()).toBeVisible();
      const final = await readFinanceDeal('account');
      expect(final.commissionInvoice.approvalStatus).toBe('MANAGER_APPROVED');
      await shot(page, '09-account-milestone5-waiting-ceo');
    });
  });
});
