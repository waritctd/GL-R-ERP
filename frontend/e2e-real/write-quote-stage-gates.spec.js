import { mkdirSync } from 'node:fs';
import { test, expect } from '@playwright/test';
import { apiSessionsFor, apiWrite, disposeSessions } from './helpers/api.js';
import { loginAs } from './helpers/auth.js';
import { directQuotationBody, inDays } from './helpers/sales.js';

/**
 * UAT walk — quote stage gates + versioning (owner rulings 2026-10-16/17, .design/quote-stage-gates).
 * Every case drives the REAL UI in a real browser and asserts both what the screen shows and what
 * GET /api/tickets/{id} says. Screenshots land in E2E_SHOTS_DIR or the gitignored test-results-real/, one per numbered step.
 * Named write-* so a local run executes it (uat-* is testIgnored locally).
 */
// Screenshots: E2E_SHOTS_DIR when set (a reviewer's own folder), else the gitignored
// frontend/test-results-real/quote-stage-gates next to Playwright's own output. Created lazily on
// the first shot so merely loading this file never writes anywhere (CI runs it from a clean checkout).
const SHOTS = process.env.E2E_SHOTS_DIR
  || new URL('../test-results-real/quote-stage-gates/', import.meta.url).pathname;

const STAGE_MESSAGE = 'อัตโนมัติจากขั้นตอนของดีล';
const REFUSED = /เลื่อนไปขั้นตอน .*ไม่ได้: ยังไม่มีใบเสนอราคาถึง/;
const REVISION_HINT = 'ฉบับแก้ไขต้องส่งถึงผู้รับเดิม — หากจะเสนอราคาให้ผู้รับรายอื่น ให้สร้างใบเสนอราคาใหม่บนดีลนี้';
const LABEL = { DESIGNER: 'ผู้ออกแบบ', OWNER: 'เจ้าของโครงการ', BUYER: 'ผู้ซื้อ / ผู้รับเหมา' };

test.describe.serial('quote stage gates + versioning — real browser walk', () => {
  test.setTimeout(120_000);
  test.use({ actionTimeout: 15_000 });
  let sessions;
  let salesPage;
  let mgrPage;
  let customer;
  const created = [];
  const tag = `qsg-${Date.now().toString(36)}`;
  let shotNo = 0;

  const shot = (page, name) => {
    mkdirSync(SHOTS, { recursive: true });
    return page.screenshot({
      path: `${SHOTS}/${String(++shotNo).padStart(2, '0')}-${name}.png`, fullPage: true,
    });
  };

  async function newCustomer(label) {
    const name = `บริษัท ${tag} ${label} จำกัด`;
    const c = await apiWrite(sessions.sales, 'post', '/api/customers', { name });
    expect(c.status(), 'POST /api/customers').toBe(200);
    const customerId = (await c.json()).customer.id;
    const p = await apiWrite(sessions.sales, 'post', `/api/customers/${customerId}/projects`, { name: `โครงการ ${tag} ${label}` });
    expect(p.status(), 'POST project').toBe(200);
    return { name, customerId, projectId: (await p.json()).project.id };
  }

  async function newDeal(label, entryChannel) {
    const r = await apiWrite(sessions.sales, 'post', '/api/tickets', {
      title: `${tag} ${label}`,
      customerName: customer.name,
      customerId: customer.customerId,
      projectId: customer.projectId,
      contactId: null,
      entryChannel,
      priority: 'NORMAL',
      items: [],
      nextFollowUpAt: inDays(7),
      quotationOnly: true,
    });
    expect(r.status(), 'POST /api/tickets').toBe(200);
    const id = (await r.json()).ticket.summary.id;
    created.push(id);
    return id;
  }

  async function ticketOf(id) {
    const r = await sessions.sales.get(`/api/tickets/${id}`);
    expect(r.status(), 'GET /api/tickets/{id}').toBe(200);
    return (await r.json()).ticket;
  }

  const postStage = (id, stage) => apiWrite(sessions.sales, 'post', `/api/tickets/${id}/stage`, { stage, note: null });

  async function login(browser, role) {
    const ctx = await browser.newContext({ viewport: { width: 1280, height: 1500 } });
    const page = await ctx.newPage();
    await page.goto('/login');
    await loginAs(page, role);
    return page;
  }

  /** The editor's one TILE line (same values as helpers/sales.js DIRECT_QUOTATION_ITEM). */
  async function fillTileLine(page) {
    await page.getByRole('button', { name: 'เพิ่มรายการในตำแหน่งนี้' }).click();
    await page.locator('#model-0').fill('Pietre Di Sardegna');
    await page.locator('#color-0').fill('Punta Molara');
    await page.locator('#texture-0').fill('R11');
    await page.locator('#size-0').fill('60x120');
    await page.locator('#thickness-0').fill('2');
    await page.locator('#sqm-0').fill('0.72');
    await page.locator('#ppb-0').fill('2');
    await page.locator('#qty-0').fill('120');
    await page.locator('#price-0').fill('1748.13');
    await page.locator('#origin-0').selectOption({ label: 'อิตาลี' });
    await page.locator('#lead-0').fill('75');
    await page.locator('#lead-max-0').fill('90');
  }

  /** /quotations/new?ticket=<deal> → pick recipient → line → บันทึกร่าง. Returns the saved quotation. */
  async function uiCreateDirect(page, dealId, recipient, { shotPrefix } = {}) {
    await page.goto(`/quotations/new?ticket=${dealId}`);
    await page.locator(`#quotation-recipient-${recipient}`).click();
    await expect(page.locator(`#quotation-recipient-${recipient}`)).toHaveAttribute('aria-checked', 'true');
    await fillTileLine(page);
    if (shotPrefix) await shot(page, `${shotPrefix}-editor`);
    await page.getByRole('button', { name: 'บันทึกร่าง', exact: true }).last().click();
    await page.waitForURL(/\/quotations\/\d+$/, { timeout: 30_000 });
    const id = Number(page.url().match(/\/quotations\/(\d+)/)[1]);
    const r = await sessions.sales.get(`/api/deal-quotations/${id}`);
    expect(r.status(), 'GET /api/deal-quotations/{id}').toBe(200);
    return (await r.json()).quotation;
  }

  const stageIs = (page, n) => expect(page.locator('main')).toContainText(new RegExp(`ขั้นที่ ${n} จาก`));

  test.beforeAll(async ({ browser }) => {
    sessions = await apiSessionsFor(['sales', 'sales_manager']);
    customer = await newCustomer('main');
    salesPage = await login(browser, 'sales');
    mgrPage = await login(browser, 'sales_manager');
  });

  test.afterAll(async () => {
    for (const id of created) {
      try {
        await apiWrite(sessions.sales, 'post', `/api/tickets/${id}/cancel`, { reason: 'OTHER', note: `${tag} teardown` });
      } catch { /* best effort */ }
    }
    await disposeSessions(sessions);
  });

  let dealA; // cases 1–7a
  let qtDraft;

  test('1. ขั้น 3 has no shortcut into ขั้น 4/5/8', async () => {
    dealA = await newDeal('A', 'DESIGNER_LED');
    const page = salesPage;
    await page.goto(`/tickets/${dealA}`);
    for (const n of [2, 3]) {
      await page.getByRole('button', { name: new RegExp(`^เลื่อนไป: ${n}\\.`) }).first().click();
      // Ready (gate met) → the main button moves at once; otherwise it opens the follow-up + activity form.
      const modal = page.getByTestId('guided-advance-modal');
      if (await modal.waitFor({ state: 'visible', timeout: 3000 }).then(() => true, () => false)) {
        await page.getByTestId('guided-advance-followup').fill(inDays(7));
        await page.getByTestId('guided-advance-note').fill(`${tag} โทรติดตามก่อนเลื่อนขั้น ${n}`);
        await page.getByTestId('guided-advance-submit').click();
        await expect(modal).toHaveCount(0);
      }
      await stageIs(page, n);
    }
    await shot(page, '01-stage3-deal');

    // no primary CTA into 4
    await expect(page.getByRole('button', { name: /เลื่อนไป: 4/ })).toHaveCount(0);
    
    // ⋯ menus: no เลื่อนไป
    const triggers = page.locator('main [aria-haspopup="menu"]');
    const n = await triggers.count();
    expect(n, 'at least one ⋯ menu').toBeGreaterThan(0);
    for (let i = 0; i < n; i++) {
      await triggers.nth(i).click();
      await expect(page.getByRole('menuitem', { name: /เลื่อนไป/ })).toHaveCount(0);
      if (i === 0) await shot(page, '01-stage3-overflow-menu');
      await page.keyboard.press('Escape');
    }
    // stage picker
    await triggers.first().click();
    await page.getByRole('menuitem', { name: /แก้ไขสถานะ/ }).click();
    const modal = page.getByTestId('update-stage-modal');
    await expect(modal).toBeVisible();
    const values = await modal.locator('select option').evaluateAll((os) => os.map((o) => o.value));
    for (const s of ['QUOTE_DESIGN_SIDE', 'QUOTE_OWNER', 'QUOTE_BUYER']) {
      expect(values, `picker must not offer ${s}`).not.toContain(s);
    }
    await shot(page, '01-stage3-stage-picker');
    await page.keyboard.press('Escape');
    await page.getByRole('button', { name: 'ยกเลิก' }).first().click({ trial: false }).catch(() => {});
    // auto hint
    await expect(page.getByText('อัปเดตอัตโนมัติเมื่อสร้างใบเสนอราคาถึงผู้ออกแบบของดีลนี้')).toBeVisible();
    await shot(page, '01-stage3-auto-hint');
  });

  test('2. a hand move into ขั้น 4 is refused 409', async () => {
    const r = await postStage(dealA, 'QUOTE_DESIGN_SIDE');
    const body = await r.json();
    expect(r.status(), JSON.stringify(body)).toBe(409);
    expect(body.message).toMatch(REFUSED);
    expect((await ticketOf(dealA)).summary.salesStage).not.toBe('QUOTE_DESIGN_SIDE');
  });

  test('3. quoting the designer moves the deal to ขั้น 4', async () => {
    qtDraft = await uiCreateDirect(salesPage, dealA, 'DESIGNER', { shotPrefix: '03-designer' });
    expect(qtDraft.recipientType).toBe('DESIGNER');
    const t = await ticketOf(dealA);
    expect(t.summary.salesStage).toBe('QUOTE_DESIGN_SIDE');
    expect(t.events.some((e) => e.kind === 'STAGE_CHANGED' && e.toStatus === 'QUOTE_DESIGN_SIDE' && e.message === STAGE_MESSAGE)).toBe(true);
    await salesPage.goto(`/tickets/${dealA}`);
    await stageIs(salesPage, 4);
    await shot(salesPage, '03-designer-deal-stage4');
    await salesPage.getByRole('tab', { name: /ประวัติ/ }).click().catch(() => {});
    await expect(salesPage.getByText(STAGE_MESSAGE).first()).toBeVisible();
    await shot(salesPage, '03-designer-deal-history');
  });

  test('4. re-addressing the DRAFT moves the deal forward to ขั้น 5', async () => {
    const page = salesPage;
    await page.goto(`/quotations/${qtDraft.id}`);
    await page.locator('#quotation-recipient-edit-OWNER').click();
    await expect(page.locator('#quotation-recipient-edit-OWNER')).toHaveAttribute('aria-checked', 'true');
    await page.getByRole('button', { name: 'บันทึกร่าง', exact: true }).last().click();
    await expect(page.getByText('บันทึกร่างแล้ว').first()).toBeVisible();
    const q = (await (await sessions.sales.get(`/api/deal-quotations/${qtDraft.id}`)).json()).quotation;
    expect(q.recipientType).toBe('OWNER');
    expect((await ticketOf(dealA)).summary.salesStage).toBe('QUOTE_OWNER');
    await page.goto(`/tickets/${dealA}`);
    await stageIs(page, 5);
    await shot(page, '04-readdress-deal-stage5');
    const r = await postStage(dealA, 'QUOTE_DESIGN_SIDE');
    expect(r.status(), 'designer fact no longer holds').toBe(409);
    expect((await r.json()).message).toMatch(REFUSED);
  });

  let family;
  let qt2;
  test('5. cancel then create continues the numbering family (-2), stage stays ขั้น 5', async () => {
    const page = salesPage;
    // The base belongs to the deal; the first quotation is observed to carry suffix -1.
    family = qtDraft.number.replace(/-\d+$/, '');
    expect(qtDraft.number).toBe(`${family}-1`);
    await page.goto(`/quotations/${qtDraft.id}`);
    await page.getByRole('button', { name: 'ยกเลิกร่าง' }).click();
    await page.getByRole('button', { name: 'ยืนยันยกเลิก' }).click();
    await expect(page.getByText('ยกเลิกร่างแล้ว').first()).toBeVisible();
    const cancelled = (await (await sessions.sales.get(`/api/deal-quotations/${qtDraft.id}`)).json()).quotation;
    expect(cancelled.docStatus).toBe('CANCELLED');
    qt2 = await uiCreateDirect(page, dealA, 'OWNER', { shotPrefix: '05-after-cancel' });
    expect(qt2.number, `family base ${family}`).toBe(`${family}-2`);
    expect((await ticketOf(dealA)).summary.salesStage).toBe('QUOTE_OWNER');
    await expect(page.locator('main')).toContainText(qt2.number);
    await shot(page, '05-after-cancel-number');
    await page.goto(`/tickets/${dealA}`);
    await stageIs(page, 5);
    await shot(page, '05-after-cancel-deal-register');
  });

  let qt3;
  test('6. submit → approve → revision (-3): locked recipient, API 409, supersede', async () => {
    const sp = salesPage;
    await sp.goto(`/quotations/${qt2.id}`);
    await sp.getByRole('button', { name: 'ส่งขออนุมัติ', exact: true }).first().click();
    await sp.getByRole('dialog').getByRole('button', { name: 'ส่งขออนุมัติ' }).click();
    await expect(sp.getByText('ส่งขออนุมัติแล้ว').first()).toBeVisible();
    await mgrPage.goto(`/quotations/${qt2.id}`);
    await mgrPage.getByRole('button', { name: 'อนุมัติ', exact: true }).click();
    await mgrPage.getByRole('button', { name: 'ยืนยันอนุมัติ' }).click();
    await expect(async () => {
      const q = (await (await sessions.sales.get(`/api/deal-quotations/${qt2.id}`)).json()).quotation;
      expect(q.docStatus).toBe('APPROVED');
    }).toPass({ timeout: 15_000 });
    await shot(mgrPage, '06-approved-qt2');

    await sp.goto(`/quotations/${qt2.id}`);
    await sp.getByRole('button', { name: 'สร้างฉบับแก้ไข' }).click();
    await sp.waitForURL((u) => /\/quotations\/\d+$/.test(u.pathname) && !u.pathname.endsWith(`/${qt2.id}`), { timeout: 30_000 });
    qt3 = (await (await sessions.sales.get(`/api/deal-quotations/${Number(sp.url().match(/quotations\/(\d+)/)[1])}`)).json()).quotation;
    expect(qt3.number).toBe(`${family}-3`);
    const locked = sp.getByTestId('quotation-recipient-revision-locked');
    await expect(locked).toBeVisible();
    await expect(locked).toContainText(LABEL.OWNER);
    await expect(locked).toContainText(REVISION_HINT);
    await expect(sp.locator('#quotation-recipient-edit-OWNER')).toHaveCount(0);
    await shot(sp, '06-revision-locked-recipient');

    const put = await apiWrite(sessions.sales, 'put', `/api/deal-quotations/${qt3.id}`, directQuotationBody('BUYER'));
    const putBody = await put.json();
    expect(put.status(), JSON.stringify(putBody)).toBe(409);
    expect(putBody.message).toContain('ฉบับแก้ไขต้องส่งถึงผู้รับเดิม');
    expect((await (await sessions.sales.get(`/api/deal-quotations/${qt3.id}`)).json()).quotation.recipientType).toBe('OWNER');

    await sp.getByRole('button', { name: 'ส่งขออนุมัติ', exact: true }).first().click();
    await sp.getByRole('dialog').getByRole('button', { name: 'ส่งขออนุมัติ' }).click();
    await expect(sp.getByText('ส่งขออนุมัติแล้ว').first()).toBeVisible();
    await mgrPage.goto(`/quotations/${qt3.id}`);
    await mgrPage.getByRole('button', { name: 'อนุมัติ', exact: true }).click();
    await mgrPage.getByRole('button', { name: 'ยืนยันอนุมัติ' }).click();
    await expect(async () => {
      expect((await (await sessions.sales.get(`/api/deal-quotations/${qt3.id}`)).json()).quotation.docStatus).toBe('APPROVED');
    }).toPass({ timeout: 15_000 });
    const parent = (await (await sessions.sales.get(`/api/deal-quotations/${qt2.id}`)).json()).quotation;
    expect(parent.docStatus).toBe('SUPERSEDED');
    const t = await ticketOf(dealA);
    const ev = t.events.find((e) => e.kind === 'DEAL_QUOTATION_SUPERSEDED');
    expect(ev, JSON.stringify(t.events.map((e) => [e.kind, e.message]))).toBeTruthy();
    expect(ev.message).toContain(`${qt2.number} ถูกแทนที่ด้วย ${qt3.number}`);

    await sp.goto(`/quotations/${qt2.id}`);
    await expect(sp.locator('main')).toContainText('ฉบับที่ไม่ได้ใช้แล้ว');
    await shot(sp, '06-parent-superseded');
    await sp.goto(`/tickets/${dealA}`);
    await sp.getByRole('tab', { name: /เอกสาร/ }).click().catch(() => {});
    await expect(sp.locator('main')).toContainText(qt3.number);
    await shot(sp, '06-deal-register');
  });

  test('7. no buyer quotation → ขั้น 8 refused; a buyer quotation → ขั้น 8', async () => {
    const r = await postStage(dealA, 'QUOTE_BUYER');
    const body = await r.json();
    expect(r.status(), JSON.stringify(body)).toBe(409);
    expect(body.message).toMatch(REFUSED);
    expect(body.message).toContain('ผู้ซื้อ');
    expect((await ticketOf(dealA)).summary.salesStage).toBe('QUOTE_OWNER');
    const deal = await newDeal('B-buyer', 'BUYER_DIRECT');
    const q = await uiCreateDirect(salesPage, deal, 'BUYER', { shotPrefix: '07-buyer' });
    expect(q.recipientType).toBe('BUYER');
    expect((await ticketOf(deal)).summary.salesStage).toBe('QUOTE_BUYER');
    await salesPage.goto(`/tickets/${deal}`);
    await stageIs(salesPage, 8);
    await shot(salesPage, '07-buyer-deal-stage8');
  });

  test('8. a deal born from a quotation starts on the recipient stage', async () => {
    const c2 = await newCustomer('born');
    const page = salesPage;
    await page.goto('/quotations/new');
    await page.getByRole('button', { name: 'สร้างดีลใหม่' }).click();
    await page.locator('#deal-customer').fill(c2.name);
    await page.getByRole('option', { name: new RegExp(c2.name) }).first().click();
    await page.locator('#deal-project').click();
    await page.getByRole('option', { name: new RegExp(`โครงการ ${tag} born`) }).first().click();
    await page.getByRole('button', { name: 'ผู้ออกแบบนำดีล' }).click();
    await page.locator('#quotation-recipient-OWNER').click();
    await fillTileLine(page);
    await shot(page, '08-born-editor');
    await page.getByRole('button', { name: 'บันทึกร่าง', exact: true }).last().click();
    await page.waitForURL(/\/quotations\/\d+$/, { timeout: 30_000 });
    const q = (await (await sessions.sales.get(`/api/deal-quotations/${page.url().match(/quotations\/(\d+)/)[1]}`)).json()).quotation;
    created.push(q.ticketId);
    const t = await ticketOf(q.ticketId);
    expect(t.summary.salesStage).toBe('QUOTE_OWNER');
    await page.goto(`/tickets/${q.ticketId}`);
    await stageIs(page, 5);
    await shot(page, '08-born-deal-stage5');
  });

  test('9. off-route recipient is a silent no-op', async () => {
    const deal = await newDeal('C-offroute', 'OWNER_DIRECT');
    const page = salesPage;
    await page.goto(`/quotations/new?ticket=${deal}`);
    await page.locator('#quotation-recipient-DESIGNER').click();
    await expect(page.getByText('ผู้รับนี้ไม่อยู่ในเส้นทางของดีล — ขั้นของดีลจะไม่ขยับ')).toBeVisible();
    await fillTileLine(page);
    await shot(page, '09-offroute-editor');
    await page.getByRole('button', { name: 'บันทึกร่าง', exact: true }).last().click();
    await page.waitForURL(/\/quotations\/\d+$/, { timeout: 30_000 });
    await expect(page.locator('[role="alert"]')).toHaveCount(0);
    const q = (await (await sessions.sales.get(`/api/deal-quotations/${page.url().match(/quotations\/(\d+)/)[1]}`)).json()).quotation;
    expect(q.recipientType).toBe('DESIGNER');
    const t = await ticketOf(deal);
    expect(t.summary.salesStage).toBe('LEAD_APPROACH');
    await page.goto(`/tickets/${deal}`);
    await stageIs(page, 1);
    await shot(page, '09-offroute-deal-stage1');
  });
});
