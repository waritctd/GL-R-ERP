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
