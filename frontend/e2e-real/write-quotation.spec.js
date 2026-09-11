import { Buffer } from 'node:buffer';
import { test, expect } from '@playwright/test';
import { apiSessionsFor, apiWrite, disposeSessions } from './helpers/api.js';

/**
 * The DIRECT-DEAL ใบเสนอราคา journey, end to end against the real stack.
 *
 * Why this file exists: `e2e-real/README.md` records that exactly one business workflow (overtime)
 * was driven end to end here, and the sales journey specs that went with the mock suite were never
 * replaced. The quotation flow this release actually ships — deal → items → submit → approve →
 * download — had NO real-backend coverage at all. The two existing sales specs
 * (`uat-sales-journeys`, `uat-sales-order-flow`) drive the PricingRequest chain, which this
 * release deliberately BYPASSES, so they say nothing about this path.
 *
 * Written wrong-way-round where it counts. The assertions that matter are the ones where a caller
 * CANNOT do a thing: `mockApi.js`'s authorization is documented as non-authoritative and known to
 * diverge, so a refusal is only real once the Java service has issued it.
 *
 * ── What this does NOT assert, deliberately ──────────────────────────────────────────────────
 * The PDF. `?format=pdf` shells out to a converter (LibreOffice, or headless Chromium) that the
 * backend-CI job installs and this e2e job does not promise, so a PDF assertion here would be
 * testing the runner's package list. `?format=xlsx` is pure Apache POI and always available, and it
 * exercises the same controller, the same authorization and the same rendering model. Document
 * FIDELITY is covered where it belongs — `HtmlXlsFidelityTest` and `QuotationRendererTest` in the
 * backend suite, which compare against a pinned render.
 */

const ITEM = {
  brand: 'Pietre Di Sardegna',
  model: 'Pietre Di Sardegna',
  color: 'Punta Molara',
  texture: 'R11',
  sizeText: '60x120',
  // Mandatory by owner ruling (2026-09-09): "thickness is mandatory — sales must fill it in".
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

function draftBody(contactId, overrides = {}) {
  return {
    contactId,
    deptCode: 'P003',
    unitCode: 'D002',
    offerDate: '2026-09-10',
    depositPercent: 50,
    remainderMode: 'ON_DELIVERY',
    validityDays: 30,
    customerNotes: null,
    items: [ITEM],
    ...overrides,
  };
}

test.describe('direct-deal ใบเสนอราคา — the real service', () => {
  let sessions;
  let ticketId;
  let contactId;

  // Deliberately NOT using helpers/sales.js. Those helpers are for the `uat-*` specs, which this
  // config EXCLUDES from a local/CI run (`testIgnore: ['**/uat-*.spec.js']`), and `runId()` there
  // throws unless global-setup stamped E2E_RUN_ID for a remote run. A spec that only ran in the UAT
  // lane would not cover this flow in CI, which is the whole reason this file exists.
  const tag = `q3-${Date.now().toString(36)}`;

  test.beforeAll(async () => {
    sessions = await apiSessionsFor(['sales', 'sales_manager', 'ceo', 'import', 'employee']);

    const customer = await apiWrite(sessions.sales, 'post', '/api/customers', {
      name: `บริษัท อีทูอี ${tag} จำกัด`,
      taxId: '0105551234567',
      address: '201 ซอยสุขุมวิท 63 ถ.สุขุมวิท แขวงคลองตันเหนือ เขตวัฒนา กทม.10110',
      phone: '02-000-0000',
    });
    expect(customer.status(), 'POST /api/customers').toBe(200);
    const customerId = (await customer.json()).customer.id;

    const project = await apiWrite(sessions.sales, 'post', `/api/customers/${customerId}/projects`, {
      name: `E2E Project ${tag}`,
    });
    expect(project.status(), 'POST /api/customers/{id}/projects').toBe(200);
    const projectId = (await project.json()).project.id;

    const ticket = await apiWrite(sessions.sales, 'post', '/api/tickets', {
      title: `${tag} quotation journey`,
      priority: 'NORMAL',
      customerId,
      customerName: `บริษัท อีทูอี ${tag} จำกัด`,
      projectId,
      entryChannel: 'OWNER_DIRECT',
      items: [],
      nextFollowUpAt: '2026-12-31',
    });
    expect(ticket.status(), 'POST /api/tickets').toBe(200);
    ticketId = (await ticket.json()).ticket.summary.id;

    // ผู้สั่งซื้อ is MANDATORY since V167 (owner feedback F2) and a freshly created customer has no
    // contact at all, so the journey has to create one rather than assume the seed provides it.
    const created = await apiWrite(sessions.sales, 'post', `/api/customers/${customerId}/contacts`, {
      firstName: 'ธนพล', lastName: `ศรีวัฒนกุล ${tag}`, phone: '081-234-5678', email: `e2e.${tag}@demo.invalid`,
    });
    expect(created.status(), 'POST /api/customers/{id}/contacts').toBe(200);
    contactId = (await created.json()).contact.id;
  });

  test.afterAll(async () => { await disposeSessions(sessions); });

  test('sales creates a draft, and the ผู้สั่งซื้อ snapshot is frozen onto it', async () => {
    const response = await apiWrite(sessions.sales, 'post', `/api/tickets/${ticketId}/deal-quotations`, draftBody(contactId));
    // 201, not 200 — creating a quotation is the ONE endpoint in this journey that answers Created.
    // Everything else here (contacts, projects, tickets, submit, approve) answers 200. Pinned
    // because a status change is a contract change a caller can be broken by.
    expect(response.status(), 'POST /api/tickets/{id}/deal-quotations').toBe(201);

    const { quotation } = await response.json();
    expect(quotation.docStatus).toBe('DRAFT');
    // The frozen snapshot, not a live join: this is what the printed document prints, so a later
    // edit to the contact record must not rewrite an issued quotation.
    expect(quotation.contactName).toContain('ธนพล');
    // The wastage/box math is the service's, not the caller's: 120 ตร.ม. at 0.72 ตร.ม./แผ่น is
    // 1.39 แผ่น/ตร.ม. → 167 แผ่น, +10% → 184, rounded up to whole boxes of 2 → 184.
    expect(quotation.items).toHaveLength(1);
    expect(quotation.items[0].piecesFinal).toBeGreaterThan(0);
    expect(Number(quotation.grandTotal)).toBeGreaterThan(0);
  });

  test('a quotation cannot be created without a ผู้สั่งซื้อ', async () => {
    // The wrong-way-round half of F2. `contactId: null` with a deal whose ticket has no contact
    // must be refused by DealQuotationService, not merely hidden by a disabled button.
    const response = await apiWrite(
      sessions.sales, 'post', `/api/tickets/${ticketId}/deal-quotations`,
      { ...draftBody(contactId), contactId: null },
    );
    // 200 only if the TICKET itself carries a contact to fall back on; this deal does not.
    expect(response.status(), 'create with no contact must be refused').toBe(400);
    expect((await response.json()).message).toContain('ผู้สั่งซื้อ');
  });

  test('a non-sales role cannot create a quotation on the deal', async () => {
    for (const role of ['import', 'employee']) {
      const response = await apiWrite(sessions[role], 'post', `/api/tickets/${ticketId}/deal-quotations`, draftBody(contactId));
      expect(response.status(), `${role} must not create a quotation`).toBe(403);
    }
  });

  test('submit → approve, with every refusal asserted on the way', async () => {
    const created = await apiWrite(sessions.sales, 'post', `/api/tickets/${ticketId}/deal-quotations`, draftBody(contactId));
    expect(created.status(), 'POST create answers 201 Created').toBe(201);
    const id = (await created.json()).quotation.id;

    // A DRAFT cannot be approved — the submit step is not decorative.
    const early = await apiWrite(sessions.sales_manager, 'post', `/api/deal-quotations/${id}/approve`, { note: 'too soon' });
    expect(early.status(), 'approving a DRAFT must be refused').toBe(409);

    const submitted = await apiWrite(sessions.sales, 'post', `/api/deal-quotations/${id}/submit`, {});
    expect(submitted.status(), 'POST submit').toBe(200);
    expect((await submitted.json()).quotation.docStatus).toBe('PENDING_APPROVAL');

    // The rep who raised it is not an approver. Owner ruling 2026-09-09: ผึ้ง (sales_manager) OR
    // ราม (ceo) approve — and NOBODY else, whatever the amount. There is deliberately no
    // amount-based routing in the code; the <1M / >1M split is an out-of-system agreement.
    const self = await apiWrite(sessions.sales, 'post', `/api/deal-quotations/${id}/approve`, { note: 'mine' });
    expect(self.status(), 'the raising rep must not approve their own quotation').toBe(403);

    for (const role of ['import', 'employee']) {
      const response = await apiWrite(sessions[role], 'post', `/api/deal-quotations/${id}/approve`, { note: 'x' });
      expect(response.status(), `${role} must not approve`).toBe(403);
      // A refusal that still mutated would look safe and would not be.
      const after = await sessions.sales.get(`/api/deal-quotations/${id}`);
      expect((await after.json()).quotation.docStatus, 'a refused approval must not change status')
        .toBe('PENDING_APPROVAL');
    }

    const approved = await apiWrite(sessions.sales_manager, 'post', `/api/deal-quotations/${id}/approve`, { note: 'อนุมัติตามราคาที่เสนอ' });
    expect(approved.status(), 'sales_manager approves').toBe(200);
    const doc = (await approved.json()).quotation;
    expect(doc.docStatus).toBe('APPROVED');
    expect(doc.approvedByName, 'the approver is named on the document').toBeTruthy();

    // An approved document is frozen. This is the guard that makes a sent quotation trustworthy.
    const edit = await apiWrite(sessions.sales, 'put', `/api/deal-quotations/${id}`, draftBody(contactId));
    expect(edit.status(), 'an APPROVED quotation must not be editable').toBe(409);

    // The customer-facing download, through the real controller and the real authorization.
    const file = await sessions.sales.get(`/api/deal-quotations/${id}/file?format=xlsx`);
    expect(file.status(), 'GET the document').toBe(200);
    const body = await file.body();
    expect(body.length, 'a rendered workbook is not a stub').toBeGreaterThan(10_000);
    // BIFF8 (.xls) magic, NOT a zip — the download is deliberately an .xls despite `format=xlsx`
    // (see the sales-doc-downloads note; the parameter name is historical).
    expect([...body.subarray(0, 4)]).toEqual([0xd0, 0xcf, 0x11, 0xe0]);

    // And an unrelated role cannot download it.
    const denied = await sessions.employee.get(`/api/deal-quotations/${id}/file?format=xlsx`);
    expect(denied.status(), 'an unrelated employee must not download the document').toBe(403);
  });
});

// ── Quotation v3 / v3b, and the owner's 2026-09-11 requests, against the real service ─────────────
//
// v3 (owner feedback pass 3): per-quotation price modes (NET / SPECIAL_SQM / DIRECT_NET), PLAIN
// rows, and the ส่วนลดพิเศษ ADJUSTMENT row. v3b: the English (USD) document. 2026-09-11: the
// customer's ที่อยู่ is fillable from the quotation editor, a repeat customer's details come back on
// the next quotation, and the editor lists what is still empty.
//
// Every money figure asserted here is the SERVICE's. The frontend deliberately does not compute the
// ราคาพิเศษ net (its rounding order is the point — see WastageCalculator#netPerPieceFromSpecialSqm)
// and mockApi.js deliberately returns no figure for it at all, so THIS file is the only evidence
// that the number the editor shows is right.

/** BIFF8 stores a cell string as compressed 8-bit when every character fits in Latin-1, else as
 * UTF-16LE — so a Thai string is found only in the latter, an ASCII one usually in the former. */
function xlsHasText(buffer, text) {
  if (buffer.includes(Buffer.from(text, 'utf16le'))) return true;
  // eslint-disable-next-line no-control-regex
  return /^[\x00-\xff]*$/.test(text) && buffer.includes(Buffer.from(text, 'latin1'));
}

test.describe('quotation v3 / v3b + customer details — the real service', () => {
  test.describe.configure({ mode: 'serial' });

  let sessions;
  let ticketId;
  let customerId;
  let contactId;
  let thaiId;
  let englishId;
  const tag = `q3v3-${Date.now().toString(36)}`;
  // Filled in the address test, asserted on the NEXT quotation (repeat-customer autofill).
  const ADDRESS = `99/1 ถนนพระราม 9 แขวงห้วยขวาง เขตห้วยขวาง กทม. 10310 ${tag}`;
  const PHONE = '02-555-0199';
  const TAX_ID = '0105559876543';

  const tile = (overrides = {}) => ({ ...ITEM, ...overrides });
  const plain = {
    lineType: 'PLAIN', description: 'Mapei Adhesive (20kg/Bag)', quantity: 85, unit: 'Bags', unitPrice: 350, discountPct: 0,
  };
  const adjustment = (overrides = {}) => ({
    lineType: 'ADJUSTMENT', adjustmentPct: 3, adjustmentDeadline: '2026-12-31', ...overrides,
  });

  test.beforeAll(async () => {
    sessions = await apiSessionsFor(['sales', 'sales_manager']);
    // A BARE customer — name only — so the document's missing-field behaviour is observable.
    const customer = await apiWrite(sessions.sales, 'post', '/api/customers', { name: `บริษัท เปล่า ${tag} จำกัด` });
    expect(customer.status(), 'POST /api/customers').toBe(200);
    customerId = (await customer.json()).customer.id;
    const project = await apiWrite(sessions.sales, 'post', `/api/customers/${customerId}/projects`, { name: `V3 Project ${tag}` });
    const projectId = (await project.json()).project.id;
    const ticket = await apiWrite(sessions.sales, 'post', '/api/tickets', {
      title: `${tag} v3`, priority: 'NORMAL', customerId, customerName: `บริษัท เปล่า ${tag} จำกัด`, projectId,
      entryChannel: 'OWNER_DIRECT', items: [], nextFollowUpAt: '2026-12-31',
    });
    ticketId = (await ticket.json()).ticket.summary.id;
    // A ผู้สั่งซื้อ with NO phone and NO email.
    const contact = await apiWrite(sessions.sales, 'post', `/api/customers/${customerId}/contacts`, { firstName: 'สมศรี', lastName: tag });
    contactId = (await contact.json()).contact.id;
  });

  test.afterAll(async () => { await disposeSessions(sessions); });

  test('calculate-line derives the ราคาพิเศษ net per piece — the four figures from the owner\'s documents', async () => {
    // [ราคาพิเศษ บาท/ตร.ม. incl. VAT, ตร.ม./แผ่น, the printed คงเหลือ]
    for (const [special, sqm, expected] of [[1350, 0.36, 453.84], [1400, 0.36, 470.65], [1800, 0.72, 1210.25], [790, 0.72, 531.16]]) {
      const response = await apiWrite(sessions.sales, 'post', '/api/deal-quotations/calculate-line',
        tile({ sqmPerPiece: sqm, specialPriceSqm: special, discountPct: null }));
      expect(response.status(), 'POST calculate-line').toBe(200);
      const { item } = await response.json();
      expect(Number(item.netUnitPrice), `${special} บาท/ตร.ม. at ${sqm} ตร.ม./แผ่น`).toBe(expected);
      expect(item.specialPriceLine).toContain(special.toLocaleString('en-US'));
    }
  });

  test('a ราคาพิเศษ tile + a PLAIN row + a ส่วนลดพิเศษ sent FIRST: the adjustment prints LAST, as her form does', async () => {
    const response = await apiWrite(sessions.sales, 'post', `/api/tickets/${ticketId}/deal-quotations`, draftBody(contactId, {
      priceMode: 'SPECIAL_SQM', documentLanguage: 'TH', currency: 'THB',
      // unitPrice on an ADJUSTMENT is IGNORED (review fix F2), not refused.
      items: [adjustment({ unitPrice: 1 }), tile({ sqmPerPiece: 0.36, specialPriceSqm: 1350 }), plain],
    }));
    expect(response.status(), 'POST create answers 201').toBe(201);
    const { quotation } = await response.json();
    thaiId = quotation.id;

    expect(quotation.items.map((it) => it.lineType)).toEqual(['TILE', 'PLAIN', 'ADJUSTMENT']);
    const [tileRow, plainRow, adj] = quotation.items;
    expect(Number(tileRow.netUnitPrice)).toBe(453.84);
    expect(tileRow.discountPct).toBeNull();
    expect(tileRow.specialPriceLine).toContain('1,350');
    expect(Number(plainRow.quantity)).toBe(85);
    expect(plainRow.unit).toBe('Bags');
    expect(Number(plainRow.lineAmount)).toBe(29750);
    // จำนวน −1, blank unit, blank ส่วนลด, positive ราคา and คงเหลือ, negative เป็นเงิน.
    expect(Number(adj.quantity)).toBe(-1);
    expect(adj.unit).toBeNull();
    expect(adj.discountPct).toBeNull();
    expect(Number(adj.unitPrice)).toBeGreaterThan(0);
    expect(Number(adj.netUnitPrice)).toBe(Number(adj.unitPrice));
    expect(Number(adj.lineAmount)).toBe(-Number(adj.unitPrice));
    expect(adj.descriptionLine).toBe('ส่วนลดพิเศษ 3% สำหรับการสั่งซื้อภายใน 31/12/2569');
    // 3% of the rows ABOVE it — an independent oracle, not the service's own arithmetic.
    const base = Number(tileRow.lineAmount) + Number(plainRow.lineAmount);
    expect(Math.abs(Number(adj.unitPrice) - Math.round(base * 3) / 100)).toBeLessThan(0.005);
    expect(Number(quotation.vatAmount)).toBeGreaterThan(0);
  });

  test('refusals: every one the editor avoids offering is a real 400 from the service', async () => {
    const create = (overrides) => apiWrite(sessions.sales, 'post', `/api/tickets/${ticketId}/deal-quotations`, draftBody(contactId, overrides));
    const refusals = [
      ['ราคาพิเศษ on an English document', { priceMode: 'SPECIAL_SQM', documentLanguage: 'EN', items: [tile({ specialPriceSqm: 1350 })] }, 'ราคาพิเศษ'],
      ['a ส่วนลดพิเศษ larger than the rows above it', { items: [tile(), adjustment({ adjustmentPct: null, adjustmentAmount: 99999999 })] }, 'ติดลบ'],
      ['a ส่วนลดพิเศษ that is both a percent and an amount', { items: [tile(), adjustment({ adjustmentAmount: 100 })] }, 'อย่างใดอย่างหนึ่ง'],
      ['a quotation that is ONLY a ส่วนลดพิเศษ', { items: [adjustment()] }, 'ส่วนลดพิเศษ'],
      ['a USD Thai document', { documentLanguage: 'TH', currency: 'USD' }, 'USD'],
    ];
    for (const [what, overrides, fragment] of refusals) {
      const response = await create(overrides);
      expect(response.status(), `${what} must be refused`).toBe(400);
      expect((await response.json()).message, what).toContain(fragment);
    }

    // Moving a stored ราคาพิเศษ document to English WITHOUT also moving its mode (a PUT that omits
    // priceMode keeps the stored one) is refused too — which is why the editor moves it, and says so.
    const before = await (await sessions.sales.get(`/api/deal-quotations/${thaiId}`)).json();
    const move = await apiWrite(sessions.sales, 'put', `/api/deal-quotations/${thaiId}`, draftBody(contactId, {
      documentLanguage: 'EN', items: [tile({ sqmPerPiece: 0.36, specialPriceSqm: 1350 }), plain, adjustment()],
    }));
    expect(move.status(), 'EN on a SPECIAL_SQM document must be refused').toBe(400);
    const after = await (await sessions.sales.get(`/api/deal-quotations/${thaiId}`)).json();
    expect(after.quotation.documentLanguage, 'a refused PUT must change nothing').toBe('TH');
    expect(after.quotation.grandTotal).toBe(before.quotation.grandTotal);
  });

  test('English: USD, no VAT; an update that OMITS priceMode/documentLanguage keeps the stored ones', async () => {
    const created = await apiWrite(sessions.sales, 'post', `/api/tickets/${ticketId}/deal-quotations`, draftBody(contactId, {
      priceMode: 'DIRECT_NET', documentLanguage: 'EN', currency: 'USD',
      items: [tile({ unitPrice: 15, directNetPrice: 12.5, discountPct: null }), { ...plain, unit: 'JOB', quantity: 1, unitPrice: 800 }],
    }));
    expect(created.status(), 'POST create EN').toBe(201);
    const doc = (await created.json()).quotation;
    englishId = doc.id;
    expect(doc).toMatchObject({ documentLanguage: 'EN', currency: 'USD', priceMode: 'DIRECT_NET' });
    expect(Number(doc.vatAmount)).toBe(0);
    expect(Number(doc.grandTotal)).toBe(Number(doc.subtotalAmount));
    expect(Number(doc.items[0].netUnitPrice)).toBe(12.5);

    // No priceMode, no documentLanguage, no currency on the PUT.
    const bare = draftBody(contactId, { items: [tile({ unitPrice: 15, directNetPrice: 12.5, discountPct: null })] });
    delete bare.priceMode;
    delete bare.documentLanguage;
    delete bare.currency;
    expect(Object.keys(bare)).not.toContain('priceMode');
    const updated = await apiWrite(sessions.sales, 'put', `/api/deal-quotations/${englishId}`, bare);
    expect(updated.status(), 'PUT answers 200').toBe(200);
    const kept = (await updated.json()).quotation;
    expect(kept).toMatchObject({ documentLanguage: 'EN', currency: 'USD', priceMode: 'DIRECT_NET' });
    expect(Number(kept.vatAmount)).toBe(0);
    expect(Number(kept.items[0].netUnitPrice)).toBe(12.5);
  });

  test('the rendered document: a missing field leaves no dangling label; the filled ones appear after a re-save', async () => {
    const render = async (id) => {
      const file = await sessions.sales.get(`/api/deal-quotations/${id}/file?format=xlsx`);
      expect(file.status(), 'GET the document').toBe(200);
      return file.body();
    };
    // ⚠️ Asserted on the EXACT strings DealQuotationRenderAdapter composes, never on a bare label.
    // A bare-label byte search is not evidence of anything here: "E :" occurs inside the bank
    // block's "SWIFT CODE :", "Tel. " inside the company header, and the workbook's shared-string
    // table can hold template strings no cell uses any more. (Cell-level evidence — B5/B6 read back
    // through Apache POI — is recorded in the PR.)
    const contactAndCustomer = `สมศรี ${tag}   /   บริษัท เปล่า ${tag} จำกัด`;

    // Bare customer, contact with no phone/email.
    const bareEn = await render(englishId);
    expect(xlsHasText(bareEn, contactAndCustomer), 'EN B5: contact / customer').toBe(true);
    expect(xlsHasText(bareEn, 'Address : '), 'EN must not print an empty Address line').toBe(false);
    const bareTh = await render(thaiId);
    expect(xlsHasText(bareTh, `คุณ${contactAndCustomer}`), 'TH B5: contact / customer, nothing after it').toBe(true);
    expect(xlsHasText(bareTh, 'เลขที่ผู้เสียภาษี : '), 'TH must not print an empty tax-id label').toBe(false);
    expect(xlsHasText(bareTh, 'โทร. 0'), 'TH must not print a phone line').toBe(false);

    // Fill the customer master — what the editor's ที่อยู่ / เลขที่ผู้เสียภาษี / โทร. fields do on blur.
    for (const patch of [{ address: ADDRESS }, { phone: PHONE }, { taxId: TAX_ID }]) {
      const response = await apiWrite(sessions.sales, 'put', `/api/customers/${customerId}`, patch);
      expect(response.status(), `PUT /api/customers/{id} ${Object.keys(patch)[0]}`).toBe(200);
    }
    // A saved document is a SNAPSHOT: the correction is not on it until the draft is saved again —
    // which is why the editor marks the draft dirty after a customer-field save.
    const stale = (await (await sessions.sales.get(`/api/deal-quotations/${thaiId}`)).json()).quotation;
    expect(stale.customerAddress, 'not re-snapshotted before a save').toBeNull();

    const resave = async (id, body) => {
      const response = await apiWrite(sessions.sales, 'put', `/api/deal-quotations/${id}`, body);
      expect(response.status(), 'PUT re-save').toBe(200);
      return (await response.json()).quotation;
    };
    const th = await resave(thaiId, draftBody(contactId, {
      priceMode: 'SPECIAL_SQM', documentLanguage: 'TH', items: [tile({ sqmPerPiece: 0.36, specialPriceSqm: 1350 }), plain, adjustment()],
    }));
    expect(th).toMatchObject({ customerAddress: ADDRESS, customerPhone: PHONE, customerTaxId: TAX_ID });
    const en = await resave(englishId, draftBody(contactId, {
      priceMode: 'DIRECT_NET', documentLanguage: 'EN', items: [tile({ unitPrice: 15, directNetPrice: 12.5, discountPct: null })],
    }));
    expect(en.customerAddress).toBe(ADDRESS);

    const filledTh = await render(thaiId);
    expect(xlsHasText(filledTh, `คุณ${contactAndCustomer}   เลขที่ผู้เสียภาษี : ${TAX_ID}`), 'TH B5 carries the tax id').toBe(true);
    expect(xlsHasText(filledTh, `โทร. ${PHONE}`), 'TH B6 carries the phone').toBe(true);
    // NOTE: the Thai F-SM-002 has NO address line — DealQuotationRenderAdapter prints
    // customerAddress on the ENGLISH document only. Not asserted either way here: that is a backend
    // renderer gap reported in the PR, not behaviour to pin.
    const filledEn = await render(englishId);
    // ONE exact string: the address, then the phone, joined by the adapter's single separator — so
    // this also proves no "E :" is printed for the contact's missing email, and no doubled separator.
    expect(xlsHasText(filledEn, `Address : ${ADDRESS}   Tel. ${PHONE}`), 'EN B6: Address + Tel, nothing between').toBe(true);
  });

  test('a repeat customer: the NEXT quotation starts with the details already filled', async () => {
    const response = await apiWrite(sessions.sales, 'post', `/api/tickets/${ticketId}/deal-quotations`, draftBody(contactId));
    expect(response.status()).toBe(201);
    const { quotation } = await response.json();
    expect(quotation).toMatchObject({ customerAddress: ADDRESS, customerPhone: PHONE, customerTaxId: TAX_ID });
  });
});

// ── The editor itself, in a real browser, against the real service ────────────────────────────────
test.describe('quotation editor UI — v3 controls, ที่อยู่, and the ข้อมูลที่ยังไม่ครบ checklist', () => {
  let sessions;
  let quotationId;
  const tag = `q3ui-${Date.now().toString(36)}`;
  const ADDRESS = `12 ซอยลาดพร้าว 101 เขตวังทองหลาง กทม. ${tag}`;

  test.beforeAll(async () => {
    sessions = await apiSessionsFor(['sales']);
    const customer = await apiWrite(sessions.sales, 'post', '/api/customers', { name: `บริษัท หน้าจอ ${tag} จำกัด`, taxId: '0105550000001' });
    const customerId = (await customer.json()).customer.id;
    const project = await apiWrite(sessions.sales, 'post', `/api/customers/${customerId}/projects`, { name: `UI ${tag}` });
    const ticket = await apiWrite(sessions.sales, 'post', '/api/tickets', {
      title: `${tag} ui`, priority: 'NORMAL', customerId, customerName: `บริษัท หน้าจอ ${tag} จำกัด`,
      projectId: (await project.json()).project.id, entryChannel: 'OWNER_DIRECT', items: [], nextFollowUpAt: '2026-12-31',
    });
    const ticketId = (await ticket.json()).ticket.summary.id;
    const contact = await apiWrite(sessions.sales, 'post', `/api/customers/${customerId}/contacts`, {
      firstName: 'วิไล', lastName: tag, phone: '089-111-2222',
    });
    const created = await apiWrite(sessions.sales, 'post', `/api/tickets/${ticketId}/deal-quotations`,
      draftBody((await contact.json()).contact.id));
    expect(created.status()).toBe(201);
    quotationId = (await created.json()).quotation.id;
  });

  test.afterAll(async () => { await disposeSessions(sessions); });

  test('a sales rep fills the missing ที่อยู่ from the editor, sees the ราคาพิเศษ net, and English hides ราคาพิเศษ', async ({ page }) => {
    const { loginAs } = await import('./helpers/auth.js');
    await loginAs(page, 'sales');
    await page.goto(`/quotations/${quotationId}`);

    // The checklist: the address is a WARNING (listed, not blocking); ส่งขออนุมัติ stays enabled.
    const checklist = page.getByTestId('quotation-checklist');
    await expect(checklist.getByTestId('checklist-warnings')).toContainText('ยังไม่ได้กรอกที่อยู่ลูกค้า');
    await expect(checklist.getByTestId('checklist-warnings')).toContainText('ผู้สั่งซื้อยังไม่มีอีเมล');
    await expect(checklist.getByTestId('checklist-blocking')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'ส่งขออนุมัติ' })).toBeEnabled();
    // The ผู้สั่งซื้อ's own details, prefilled from the contact record.
    await expect(page.getByTestId('quotation-contact-details')).toHaveText('โทร. 089-111-2222 · อีเมล ยังไม่มี');

    // Clicking the entry lands on the field; typing + leaving it saves to the customer master…
    await checklist.getByRole('button', { name: 'ยังไม่ได้กรอกที่อยู่ลูกค้า' }).click();
    const address = page.locator('#deal-customer-address');
    await expect(address).toBeFocused();
    const customerSaved = page.waitForResponse((r) => r.url().includes('/api/customers/') && r.request().method() === 'PUT');
    await address.fill(ADDRESS);
    await address.blur();
    expect((await customerSaved).status()).toBe(200);
    await expect(checklist).not.toContainText('ยังไม่ได้กรอกที่อยู่ลูกค้า');
    // …and the draft is re-saved (autosave), which is what puts it on the DOCUMENT.
    await expect.poll(async () => (await (await sessions.sales.get(`/api/deal-quotations/${quotationId}`)).json()).quotation.customerAddress,
      { timeout: 15_000 }).toBe(ADDRESS);

    // ราคาพิเศษ: the per-piece net shown is calculate-line's (1,800 บาท/ตร.ม. at 0.72 ตร.ม./แผ่น).
    const modes = page.getByRole('group', { name: 'วิธีกรอกราคากระเบื้อง' });
    await modes.getByRole('button', { name: 'ราคาพิเศษ บาท/ตร.ม.' }).click();
    await page.locator('#special-0').fill('1800');
    await expect(page.getByTestId('special-net-0')).toHaveText('= สุทธิ ฿1,210.25/แผ่น (ก่อน VAT)');

    // English: ราคาพิเศษ is not offered, and the move off it is announced.
    await page.getByRole('group', { name: 'ภาษาเอกสาร' }).getByRole('button', { name: /English/ }).click();
    await expect(modes.getByRole('button', { name: 'ราคาพิเศษ บาท/ตร.ม.' })).toHaveCount(0);
    await expect(modes.getByRole('button', { name: 'ราคาสุทธิต่อแผ่น' })).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByText(/เอกสารภาษาอังกฤษใช้ "ราคาพิเศษ บาท\/ตร\.ม\." ไม่ได้/)).toBeVisible();
    // The net is not carried across the currency change: the row now blocks until it is stated.
    await expect(page.getByTestId('checklist-blocking')).toContainText('ขาด ราคาสุทธิ/แผ่น');
    await expect(page.getByRole('button', { name: 'ส่งขออนุมัติ' })).toBeDisabled();
  });
});
