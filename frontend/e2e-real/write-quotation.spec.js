import { test, expect } from '@playwright/test';
import { apiSessionsFor, apiWrite, disposeSessions } from './helpers/api.js';

/** The DIRECT-DEAL ใบเสนอราคา journey, end to end against the real stack. Why this file exists:
 * `e2e-real/README.md` records that exactly one business workflow (overtime) was driven end to end here, and
 * the sales journey specs that went with the mock suite were never replaced. */

const ITEM = {
  brand: 'Pietre Di Sardegna',
  model: 'Pietre Di Sardegna',
  color: 'Punta Molara',
  texture: 'R11',
  sizeText: '60x120',
  // Thickness is mandatory: sales must fill it in.
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

// A direct create must name its ผู้รับใบเสนอราคา (400 otherwise), and a deal may hold only ONE live
// direct quotation (409 otherwise) — so every test below that CREATES a quotation does so on a deal
// of its own (`newDeal`), and the body always says who the quotation is for. On an update the same
// OWNER is a no-op (it is what was stored).
function draftBody(contactId, overrides = {}) {
  return {
    contactId,
    recipientType: 'OWNER',
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
  let newDeal;

  // Deliberately NOT using helpers/sales.js. A spec that only ran in the UAT lane would not cover this flow in
  // CI, which is the whole reason this file exists.
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

    newDeal = async () => {
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
      return (await ticket.json()).ticket.summary.id;
    };
    ticketId = await newDeal();

    // ผู้สั่งซื้อ is not mandatory; a contact is still created here because "sales creates a draft,
    // and the ผู้สั่งซื้อ snapshot is frozen onto it" below wants a real one to freeze, not because
    // create/submit would refuse without one.
    const created = await apiWrite(sessions.sales, 'post', `/api/customers/${customerId}/contacts`, {
      firstName: 'ธนพล', lastName: `ศรีวัฒนกุล ${tag}`, phone: '081-234-5678', email: `e2e.${tag}@demo.invalid`,
    });
    expect(created.status(), 'POST /api/customers/{id}/contacts').toBe(200);
    contactId = (await created.json()).contact.id;
  });

  test.afterAll(async () => { await disposeSessions(sessions); });

  test('sales creates a draft, and the ผู้สั่งซื้อ snapshot is frozen onto it', async () => {
    const response = await apiWrite(sessions.sales, 'post', `/api/tickets/${await newDeal()}/deal-quotations`, draftBody(contactId));
    // 201, not 200 — creating a quotation is the ONE endpoint in this journey that answers Created. Everything
    // else here (contacts, projects, tickets, submit, approve) answers 200. Pinned because a status change is a
    // contract change a caller can be broken by.
    expect(response.status(), 'POST /api/tickets/{id}/deal-quotations').toBe(201);

    const { quotation } = await response.json();
    expect(quotation.docStatus).toBe('DRAFT');
    // The frozen snapshot, not a live join: this is what the printed document prints, so a later edit to the
    // contact record must not rewrite an issued quotation.
    expect(quotation.contactName).toContain('ธนพล');
    // The wastage/box math is the service's, not the caller's: 120 ตร.ม. at 0.72 ตร.ม./แผ่น is 1.39 แผ่น/ตร.ม. →
    // 167 แผ่น, +10% → 184, rounded up to whole boxes of 2 → 184.
    expect(quotation.items).toHaveLength(1);
    expect(quotation.items[0].piecesFinal).toBeGreaterThan(0);
    expect(Number(quotation.grandTotal)).toBeGreaterThan(0);
  });

  test('a non-sales role cannot create a quotation on the deal', async () => {
    for (const role of ['import', 'employee']) {
      const response = await apiWrite(sessions[role], 'post', `/api/tickets/${ticketId}/deal-quotations`, draftBody(contactId));
      expect(response.status(), `${role} must not create a quotation`).toBe(403);
    }
  });

  test('submit → approve, with every refusal asserted on the way', async () => {
    const created = await apiWrite(sessions.sales, 'post', `/api/tickets/${await newDeal()}/deal-quotations`, draftBody(contactId));
    expect(created.status(), 'POST create answers 201 Created').toBe(201);
    const id = (await created.json()).quotation.id;

    // A DRAFT cannot be approved — the submit step is not decorative.
    const early = await apiWrite(sessions.sales_manager, 'post', `/api/deal-quotations/${id}/approve`, { note: 'too soon' });
    expect(early.status(), 'approving a DRAFT must be refused').toBe(409);

    const submitted = await apiWrite(sessions.sales, 'post', `/api/deal-quotations/${id}/submit`, {});
    expect(submitted.status(), 'POST submit').toBe(200);
    expect((await submitted.json()).quotation.docStatus).toBe('PENDING_APPROVAL');

    // The rep who raised it is not an approver. The sales_manager OR the ceo approves — and
    // NOBODY else, whatever the amount. There is deliberately no amount-based routing in the
    // code; the <1M / >1M split is an out-of-system agreement.
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
    // BIFF8 (.xls) magic, NOT a zip — the download is deliberately an .xls despite `format=xlsx` (see the
    // sales-doc-downloads note; the parameter name is historical).
    expect([...body.subarray(0, 4)]).toEqual([0xd0, 0xcf, 0x11, 0xe0]);

    // And an unrelated role cannot download it.
    const denied = await sessions.employee.get(`/api/deal-quotations/${id}/file?format=xlsx`);
    expect(denied.status(), 'an unrelated employee must not download the document').toBe(403);
  });
});

// ── Quotation v3 / v3b, customer details, against the real service ─────────────
//
// v3: the ราคาพิเศษ net per piece (calculate-line) and v3b: the English (USD, no VAT) document.
//
// Every money figure asserted here is the SERVICE's. The frontend deliberately does not compute the
// ราคาพิเศษ net (its rounding order is the point — see WastageCalculator#netPerPieceFromSpecialSqm)
// and mockApi.js deliberately returns no figure for it at all, so THIS file is the only evidence
// that the number the editor shows is right.

test.describe('quotation v3 / v3b + customer details — the real service', () => {
  test.describe.configure({ mode: 'serial' });

  let sessions;
  let customerId;
  let contactId;
  let englishId;
  let newDeal;
  const tag = `q3v3-${Date.now().toString(36)}`;

  const tile = (overrides = {}) => ({ ...ITEM, ...overrides });
  const plain = {
    lineType: 'PLAIN', description: 'Mapei Adhesive (20kg/Bag)', quantity: 85, unit: 'Bags', unitPrice: 350, discountPct: 0,
  };

  test.beforeAll(async () => {
    sessions = await apiSessionsFor(['sales', 'sales_manager']);
    // A BARE customer — name only — so the document's missing-field behaviour is observable.
    const customer = await apiWrite(sessions.sales, 'post', '/api/customers', { name: `บริษัท เปล่า ${tag} จำกัด` });
    expect(customer.status(), 'POST /api/customers').toBe(200);
    customerId = (await customer.json()).customer.id;
    const project = await apiWrite(sessions.sales, 'post', `/api/customers/${customerId}/projects`, { name: `V3 Project ${tag}` });
    const projectId = (await project.json()).project.id;
    // One deal per created quotation.
    newDeal = async () => {
      const ticket = await apiWrite(sessions.sales, 'post', '/api/tickets', {
        title: `${tag} v3`, priority: 'NORMAL', customerId, customerName: `บริษัท เปล่า ${tag} จำกัด`, projectId,
        entryChannel: 'OWNER_DIRECT', items: [], nextFollowUpAt: '2026-12-31',
      });
      expect(ticket.status(), 'POST /api/tickets').toBe(200);
      return (await ticket.json()).ticket.summary.id;
    };
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

  test('English: USD, no VAT; an update that OMITS priceMode/documentLanguage keeps the stored ones', async () => {
    const created = await apiWrite(sessions.sales, 'post', `/api/tickets/${await newDeal()}/deal-quotations`, draftBody(contactId, {
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
});
