import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QuotationEditorPage } from './QuotationEditorPage.jsx';
import { api } from '../../api/index.js';
import { QUOTATION_FIELD_IDS } from './quotationMeta.js';
import { queryKeys } from '../../api/queryKeys.js';

globalThis.React = React;

// Whole-editor cases, each of which picks a customer through a debounced search. Same budget, for
// the same reason, as QuotationEditorPage.copyFrom.test.jsx.
vi.setConfig({ testTimeout: 15000 });

// "เปลี่ยนลูกค้า" — owner, 2026-10-12: "ลูกค้า must also be editable when still not sent for approval,
// in case of a typo". A direct-deal quotation's customer belongs to its DEAL (sales.ticket), and
// today nothing can change a deal's customer: ชื่อลูกค้า on the summary renames the customer RECORD
// (PUT /api/customers/{id}), which is a different thing. So a saved DRAFT direct quotation gains a
// button that re-points the deal at another customer + project through
// PUT /api/tickets/{id}/customer (api.tickets.changeCustomer).
//
// Contract these tests pin on the (not yet written) UI, so the implementation can be held to it:
//   * a button whose accessible name is exactly "เปลี่ยนลูกค้า", on the ลูกค้า summary;
//   * it opens the SAME DealCustomerCard pickers the inline-create path uses (the typeahead input
//     keeps its id `deal-customer`, the project input `deal-project`), in a dialog (role="dialog")
//     or inline — both are handled below;
//   * a confirm button whose accessible name starts with "ยืนยัน";
//   * on success the quotation is left DIRTY (observed the way copyFrom.test.jsx observes it: the
//     "คัดลอกเป็นใบใหม่" button disables while unsaved edits are on the page), because only the next
//     draft save re-snapshots the customer onto the document.
//
// Evidence note: every server response here is a vi.fn(). Who may change a deal's customer, and the
// 409 once a quotation has been submitted, are the SERVER's decisions —
// TicketChangeCustomerIntegrationTest proves them against real Postgres. This file shows only
// where the editor offers the control and what it does with the server's answer.
vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: { get: vi.fn(), create: vi.fn(), changeCustomer: vi.fn() },
      dealQuotations: {
        get: vi.fn(), create: vi.fn(), update: vi.fn(), calculateLine: vi.fn(), submit: vi.fn(),
        approve: vi.fn(), reject: vi.fn(), createRevision: vi.fn(), createReorder: vi.fn(), cancel: vi.fn(),
        downloadPdf: vi.fn(), downloadXlsx: vi.fn(), displayNameOptions: vi.fn(),
      },
      catalog: { prices: vi.fn() },
      designers: { search: vi.fn(), getByCode: vi.fn().mockRejectedValue(new Error('not found')) },
      customers: {
        search: vi.fn(), create: vi.fn(), update: vi.fn(), projects: vi.fn(), createProject: vi.fn(),
        contacts: vi.fn(), createContact: vi.fn(),
      },
    },
  };
});

const CHANGE = 'เปลี่ยนลูกค้า';
const COPY = 'คัดลอกเป็นใบใหม่';
const TICKET_ID = 827364;
const CONFLICT = 'เปลี่ยนลูกค้าไม่ได้ เนื่องจากมีใบเสนอราคาที่ส่งขออนุมัติแล้ว';

const salesUser = { id: 6, name: 'คุณสมหมาย ขายดี', role: 'sales', employeeId: null };
const ceoUser = { id: 8, name: 'ราม', role: 'ceo', employeeId: 136 };
const importUser = { id: 7, name: 'คุณนำเข้า พานิช', role: 'import', employeeId: null };
const accountUser = { id: 5, name: 'คุณบัญชี รอบคอบ', role: 'account', employeeId: null };

// The customer the deal is on today, and the one the typo is being corrected TO. Every identifying
// value is distinctive so "the old customer leaked back" and "the new one arrived" are unmistakable.
const OLD_CUSTOMER = {
  id: 1, name: 'บริษัท ผู้รับเหมา เอ จำกัด', taxId: '0105550000001', address: '1 ถนนเดิม เขตเดิม', phone: '02-111-1111',
};
const OLD_PROJECT = { id: 3, name: 'คอนโด ริเวอร์ไซด์ ชั้น 5' };
const NEW_CUSTOMER = {
  id: 2, name: 'บริษัท ผู้รับเหมา บี จำกัด', taxId: '0105550000002', address: '2 ถนนใหม่ เขตใหม่', phone: '02-222-2222',
};
const NEW_CUSTOMERS_PROJECT = { id: 77, customerId: 2, name: 'ทาวน์โฮม พระราม 9' };

const TILE = {
  id: 5550001, seq: 1, lineType: 'TILE', locationLabel: 'ชั้น 5 โซน A', catalogPriceId: null, productCode: null,
  brand: 'Panaria', model: 'Trilogy', color: 'Ivory', texture: 'Lappato', sizeText: '60x120', thicknessMm: 10,
  sqmPerPiece: 0.72, quantityMode: 'PIECES', areaSqm: null, piecesInput: 10, wastageMode: 'NONE',
  wastageValue: null, piecesPerBox: 2, unitPrice: 1210.25, discountPct: 20, originCountry: null,
  leadTimeMinDays: 75, leadTimeMaxDays: 90, itemNotes: null,
  piecesPerSqm: 1.39, piecesBeforeWastage: 10, piecesAfterWastage: 10, piecesFinal: 10, boxes: 5,
  netUnitPrice: 968.2, lineAmount: 9682, descriptionLine: 'Trilogy Ivory', sizeLine: '60x120', calculationLine: '(calc)',
  quantity: 10, unit: 'แผ่น', specialPriceSqm: null, adjustmentPct: null, adjustmentDeadline: null,
  specialPriceLine: null, adjustmentAmount: null, listPriceSqm: 1800, directNetPriceSqm: null, priceMode: 'NET',
  hasPicture: false, picturePlacement: null, pictureUrl: null,
};

/** A saved DIRECT-deal DRAFT on OLD_CUSTOMER — the one state the control belongs to. */
function draft(overrides = {}) {
  return {
    id: 918273, number: 'QT-2026-0777-1', ticketId: TICKET_ID, docStatus: 'DRAFT', revisionNo: 1,
    parentQuotationId: null, derivedFromQuotationId: null, origin: 'DEAL_DIRECT', pricingRequestId: null,
    createdById: 6, createdByName: 'คุณสมหมาย ขายดี', salesRepId: 6, salesRepName: 'คุณสมหมาย ขายดี', salesRepPhone: '081-000-0000',
    submittedAt: null, approvedById: null, approvedByName: null, approvedAt: null, approvalNote: null,
    approverHasSignature: false,
    quotationDate: '2026-09-20',
    customerName: OLD_CUSTOMER.name, customerAddress: OLD_CUSTOMER.address, customerTaxId: OLD_CUSTOMER.taxId,
    customerPhone: OLD_CUSTOMER.phone,
    contactId: null, contactName: null, contactPhone: null, contactEmail: null,
    orderedByName: null, orderedByPhone: null, orderedByEmail: null,
    projectName: OLD_PROJECT.name, hideProjectName: false,
    deptCode: null, unitCode: 'A001', offerDate: '2026-09-18',
    depositPercent: 45, remainderMode: 'CREDIT', creditDays: 30, fullPaymentTerm: null,
    validityDays: 30, validityDate: '2026-10-20', validityMode: 'DATE', validityUntil: '2026-11-30',
    customerNotes: null,
    priceMode: 'NET', documentLanguage: 'TH', currency: 'THB',
    subtotalAmount: 9682, vatAmount: 677.74, grandTotal: 10359.74,
    printedByDisplayId: null, printedByDisplayName: null, printedByDisplayNameEn: null,
    salesRepDisplayId: null, salesRepDisplayName: null, salesRepDisplayNameEn: null, salesRepDisplayPhone: null,
    omitContactHonorific: false,
    items: [TILE],
    createdAt: '2026-09-20T02:00:00Z', updatedAt: '2026-09-22T03:00:00Z',
    ...overrides,
  };
}

const byId = (id) => document.getElementById(id);

// The deal's customer as the SERVER holds it. changeCustomer moves it, and every later
// api.tickets.get answers from it — which is how "refresh the ticket" is observed.
let dealCustomer;
let dealProject;

function ticketSummary() {
  return {
    id: TICKET_ID, createdById: 6, createdByName: 'คุณสมหมาย ขายดี',
    customerName: dealCustomer.name, customerId: dealCustomer.id,
    projectId: dealProject.id, projectName: dealProject.name, contactId: null, contactName: null,
  };
}

function Providers({ path, children, client }) {
  const queryClient = client ?? new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>{children}</Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

/** The saved quotation's own page, as `user`. Returns the toast spy so a refusal can be read. */
function renderSaved(quotation, user = salesUser) {
  api.dealQuotations.get.mockResolvedValue({ quotation });
  const showToast = vi.fn();
  const result = render(
    <Providers path={`/quotations/${quotation.id}`}>
      <Route path="/quotations/:id" element={<QuotationEditorPage user={user} showToast={showToast} />} />
    </Providers>,
  );
  return { ...result, showToast };
}

/** The page is up and has had its chance to render every control it is going to render. */
async function pageIsUp() {
  await screen.findByRole('heading', { level: 1 });
  await waitFor(() => expect(api.tickets.get).toHaveBeenCalled());
  await new Promise((resolve) => { setTimeout(resolve, 60); });
}

/** Opens the pickers and returns the part of the page they live in (the dialog if there is one). */
async function openThePickers() {
  fireEvent.click(await screen.findByRole('button', { name: CHANGE }));
  return screen.queryByRole('dialog') ?? document.body;
}

async function pickTheOtherCustomerAndProject(scope) {
  // If the pickers open pre-filled with the current customer, take that choice off first.
  const clear = within(scope).queryByRole('button', { name: 'ล้างลูกค้าที่เลือก' });
  if (clear) fireEvent.click(clear);

  fireEvent.change(await waitFor(() => {
    const input = byId(QUOTATION_FIELD_IDS.customer);
    expect(input).not.toBeNull();
    return input;
  }), { target: { value: 'ผู้รับเหมา บี' } });
  fireEvent.mouseDown(await screen.findByRole('option', { name: new RegExp(NEW_CUSTOMER.name) }));
  await waitFor(() => expect(api.customers.projects).toHaveBeenCalledWith(NEW_CUSTOMER.id));

  fireEvent.focus(byId(QUOTATION_FIELD_IDS.project));
  fireEvent.mouseDown(await screen.findByRole('option', { name: new RegExp(NEW_CUSTOMERS_PROJECT.name) }));
}

function confirmTheChange(scope) {
  fireEvent.click(within(scope).getByRole('button', { name: /^ยืนยัน/ }));
}

const fieldValue = (id) => byId(id)?.value;

/** The copy button is disabled while unsaved edits are on the page (copyFrom.test.jsx's own tell). */
const isDirty = () => screen.getByRole('button', { name: COPY }).disabled;

beforeEach(() => {
  vi.clearAllMocks();
  dealCustomer = OLD_CUSTOMER;
  dealProject = OLD_PROJECT;
  api.dealQuotations.get.mockResolvedValue({ quotation: draft() });
  api.dealQuotations.displayNameOptions.mockResolvedValue({ items: [] });
  api.dealQuotations.calculateLine.mockResolvedValue({ item: {} });
  // What the real server does on a DRAFT save: re-snapshot the DEAL's customer onto the document.
  api.dealQuotations.update.mockImplementation(async () => ({
    quotation: draft({
      customerName: dealCustomer.name, customerAddress: dealCustomer.address,
      customerTaxId: dealCustomer.taxId, customerPhone: dealCustomer.phone, projectName: dealProject.name,
    }),
  }));
  api.tickets.get.mockImplementation(async () => ({ ticket: { summary: ticketSummary() } }));
  api.tickets.changeCustomer.mockImplementation(async (_ticketId, request) => {
    expect(request.customerId).toBe(NEW_CUSTOMER.id);
    dealCustomer = NEW_CUSTOMER;
    dealProject = NEW_CUSTOMERS_PROJECT;
    return { ticket: { summary: ticketSummary() } };
  });
  api.customers.search.mockResolvedValue({ customers: [OLD_CUSTOMER, NEW_CUSTOMER] });
  api.customers.projects.mockImplementation(async (customerId) => ({
    projects: customerId === NEW_CUSTOMER.id ? [NEW_CUSTOMERS_PROJECT] : [{ ...OLD_PROJECT, customerId: 1 }],
  }));
  api.customers.contacts.mockResolvedValue({ contacts: [] });
});

describe('"เปลี่ยนลูกค้า" on a saved DRAFT direct quotation', () => {
  it('is offered to the owning rep, and confirming moves the deal and fills the customer fields in', async () => {
    renderSaved(draft());

    // The control: the summary shows today's customer, and nothing is unsaved yet.
    await waitFor(() => expect(fieldValue(QUOTATION_FIELD_IDS.customerName)).toBe(OLD_CUSTOMER.name));
    expect(isDirty()).toBe(false);

    const scope = await openThePickers();
    await pickTheOtherCustomerAndProject(scope);
    // Picking is not saving: nothing has gone to the server yet.
    expect(api.tickets.changeCustomer).not.toHaveBeenCalled();
    confirmTheChange(scope);

    await waitFor(() => expect(api.tickets.changeCustomer).toHaveBeenCalledTimes(1));
    expect(api.tickets.changeCustomer).toHaveBeenCalledWith(
      TICKET_ID, { customerId: NEW_CUSTOMER.id, projectId: NEW_CUSTOMERS_PROJECT.id, contactId: null },
    );

    // The editor's own customer fields now show the NEW customer's record …
    await waitFor(() => expect(fieldValue(QUOTATION_FIELD_IDS.customerName)).toBe(NEW_CUSTOMER.name));
    expect(fieldValue(QUOTATION_FIELD_IDS.customerTaxId)).toBe(NEW_CUSTOMER.taxId);
    expect(fieldValue(QUOTATION_FIELD_IDS.customerAddress)).toBe(NEW_CUSTOMER.address);
    expect(fieldValue(QUOTATION_FIELD_IDS.customerPhone)).toBe(NEW_CUSTOMER.phone);
    // … and none of the old one's.
    const everything = [document.body.textContent, ...[...document.querySelectorAll('input, textarea')].map((el) => el.value)].join('\n');
    expect(everything).not.toContain(OLD_CUSTOMER.taxId);
    expect(everything).not.toContain(OLD_CUSTOMER.address);
    // The deal was re-read, so the header (which is the ticket's customer) agrees with the fields.
    await waitFor(() => expect(screen.getByRole('heading', { level: 1 }).closest('header').textContent).toContain(NEW_CUSTOMER.name));
    // Left dirty: the document still carries the OLD snapshot until the next save.
    expect(isDirty()).toBe(true);
  });

  it('the next บันทึกร่าง is the ordinary draft save, with nothing of the old customer in it, and it settles the form', async () => {
    renderSaved(draft());
    await waitFor(() => expect(fieldValue(QUOTATION_FIELD_IDS.customerName)).toBe(OLD_CUSTOMER.name));
    const scope = await openThePickers();
    await pickTheOtherCustomerAndProject(scope);
    confirmTheChange(scope);
    await waitFor(() => expect(fieldValue(QUOTATION_FIELD_IDS.customerName)).toBe(NEW_CUSTOMER.name));
    expect(isDirty()).toBe(true);

    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalledTimes(1));
    const [quotationId, payload] = api.dealQuotations.update.mock.calls[0];
    expect(Number(quotationId)).toBe(918273); // the editor passes the route param as it came, a string
    // The customer on the printed document is re-snapshotted by the SERVER from the deal
    // (DealQuotationService#customerSnapshot), so the request never carries customer columns — what
    // matters is that no stale copy of the old customer rides along.
    const sent = JSON.stringify(payload);
    for (const stale of [OLD_CUSTOMER.name, OLD_CUSTOMER.taxId, OLD_CUSTOMER.address, OLD_CUSTOMER.phone]) {
      expect(sent).not.toContain(stale);
    }
    // The save landed, so the form is clean again and shows the customer the server now prints.
    await waitFor(() => expect(isDirty()).toBe(false));
    expect(fieldValue(QUOTATION_FIELD_IDS.customerName)).toBe(NEW_CUSTOMER.name);
  });

  // Wrong way round: a refusal must not look like a success. The 409 is the real answer once a
  // quotation of the deal has been submitted from another tab or by the approver.
  it('shows the server\'s refusal in a toast and leaves the customer fields and the dirty state alone', async () => {
    api.tickets.changeCustomer.mockRejectedValue(new Error(CONFLICT));
    const { showToast } = renderSaved(draft());
    await waitFor(() => expect(fieldValue(QUOTATION_FIELD_IDS.customerName)).toBe(OLD_CUSTOMER.name));

    const scope = await openThePickers();
    await pickTheOtherCustomerAndProject(scope);
    confirmTheChange(scope);

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', CONFLICT));
    expect(api.tickets.changeCustomer).toHaveBeenCalledTimes(1);
    expect(fieldValue(QUOTATION_FIELD_IDS.customerName)).toBe(OLD_CUSTOMER.name);
    expect(fieldValue(QUOTATION_FIELD_IDS.customerTaxId)).toBe(OLD_CUSTOMER.taxId);
    expect(fieldValue(QUOTATION_FIELD_IDS.customerAddress)).toBe(OLD_CUSTOMER.address);
    expect(fieldValue(QUOTATION_FIELD_IDS.customerPhone)).toBe(OLD_CUSTOMER.phone);
    expect(isDirty()).toBe(false);
  });
});

describe('"เปลี่ยนลูกค้า" is not offered where the customer is frozen or not the viewer\'s to change', () => {
  // Wrong way round. Each case first proves the control exists on the same page for the same rep in
  // the one editable state, so a missing button means "withheld", not "never rendered".
  async function theControlIsThere() {
    const control = renderSaved(draft());
    await screen.findByRole('button', { name: CHANGE });
    control.unmount();
  }

  it('not once the quotation has been sent for approval, or approved', async () => {
    await theControlIsThere();

    for (const docStatus of ['PENDING_APPROVAL', 'APPROVED']) {
      const page = renderSaved(draft({
        docStatus, submittedAt: '2026-09-21T03:00:00Z',
        ...(docStatus === 'APPROVED' ? { approvedById: 9, approvedByName: 'ผู้จัดการ อนุมัติเอง', approvedAt: '2026-09-22T03:00:00Z' } : {}),
      }));
      await pageIsUp();
      expect(screen.queryByRole('button', { name: CHANGE }), `offered on ${docStatus}`).toBeNull();
      page.unmount();
    }
  });

  it('not on a quotation that came from a pricing request', async () => {
    await theControlIsThere();

    renderSaved(draft({
      origin: 'PRICING_REQUEST', pricingRequestId: 12, pricingRequestCode: 'PCR-2026-0012',
      items: [{ ...TILE, priceMode: null }],
    }));
    await pageIsUp();

    expect(screen.queryByRole('button', { name: CHANGE })).toBeNull();
  });

  it('not to someone who may view quotations but not write them (ceo, import, account)', async () => {
    await theControlIsThere();

    for (const viewer of [ceoUser, importUser, accountUser]) {
      const page = renderSaved(draft(), viewer);
      await pageIsUp();
      expect(screen.queryByRole('button', { name: CHANGE }), `offered to ${viewer.role}`).toBeNull();
      page.unmount();
    }
  });

  // There is no deal yet, so there is nothing to re-point: the customer is simply picked, in the
  // inline-create card that is already on the page.
  it('not on /quotations/new, where the customer is still being chosen', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: null });
    render(
      <Providers path="/quotations/new">
        <Route path="/quotations/new" element={<QuotationEditorPage user={salesUser} showToast={vi.fn()} />} />
      </Providers>,
    );

    await waitFor(() => expect(byId(QUOTATION_FIELD_IDS.customer)).not.toBeNull());
    await new Promise((resolve) => { setTimeout(resolve, 60); });

    expect(screen.queryByRole('button', { name: CHANGE })).toBeNull();
    expect(api.tickets.changeCustomer).not.toHaveBeenCalled();
  });
});

describe('the header names the FROZEN customer once the quotation is no longer a draft', () => {
  // react-query keeps the deal's live customer record cached after a draft is submitted (the query
  // is only disabled, not evicted), so a customer renamed since then must not reach the header of
  // an approved document, whose printed customer is the snapshot taken at submit.
  it('shows the snapshot name, not a renamed live customer record, on a PENDING_APPROVAL quotation', async () => {
    const RENAMED = 'บริษัท ชื่อใหม่หลังส่งอนุมัติ จำกัด';
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    // Both queries are disabled off DRAFT, but data cached while it was one is still served.
    client.setQueryData(queryKeys.ticketDetail(TICKET_ID), ticketSummary());
    client.setQueryData(queryKeys.customerRecord(OLD_CUSTOMER.id), { ...OLD_CUSTOMER, name: RENAMED });
    const quotation = draft({ docStatus: 'PENDING_APPROVAL', submittedAt: '2026-09-21T03:00:00Z' });
    api.dealQuotations.get.mockResolvedValue({ quotation });
    render(
      <Providers path={`/quotations/${quotation.id}`} client={client}>
        <Route path="/quotations/:id" element={<QuotationEditorPage user={salesUser} showToast={vi.fn()} />} />
      </Providers>,
    );
    await screen.findByRole('heading', { level: 1 });
    await new Promise((resolve) => { setTimeout(resolve, 60); });

    const header = screen.getByRole('heading', { level: 1 }).closest('header');
    expect(header.textContent).toContain(OLD_CUSTOMER.name);
    expect(header.textContent).not.toContain(RENAMED);
  });
});
