import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation, useParams } from 'react-router-dom';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QuotationEditorPage } from './QuotationEditorPage.jsx';
import { api } from '../../api/index.js';
import { rowPriceMode } from './quotationMeta.js';
import { adjustmentInputFromRow, itemInputFromRow, rowFromServerItem } from './QuotationItemRow.jsx';
import { bangkokTodayIso } from '../../utils/format.js';

globalThis.React = React;

// Whole-editor cases, several of which pick a customer through a debounced search and then save.
// Same budget, for the same reason, as QuotationEditorPage.mixedPriceMode.test.jsx.
vi.setConfig({ testTimeout: 15000 });

// "คัดลอกเป็นใบใหม่" — owner: "have a duplicate button for the quotation (but its a new one) in case
// they want the exactly same details just change the customer". The case behind it: several
// contractors bid on one project, and the rep retypes the whole quotation to change the customer
// and the person to call. A revision is wrong for that (it keeps the number, -1 → -2) and a reorder
// is wrong for that (it stays on the same customer and the same deal).
//
// So: a button on a saved quotation opens /quotations/new?copyFrom=<id>, which is the ordinary
// new-quotation editor already filled in. Nothing is written until the rep saves, and the save is
// the ordinary inline create — a NEW deal for the NEW customer, then a NEW quotation on it. No
// endpoint is added and the server never learns the two documents are related.
//
// What is copied and what is not is quotationCopy.test.jsx's table. This file is the flow around
// it: who is offered the button, what the opened draft shows and withholds, what it says, what the
// save sends — and the three ways it refuses.
//
// Evidence note: every server response here is a vi.fn(). "The viewer cannot read the source" is
// the server's decision (DealQuotationService#requireViewAccess); this file only shows what the
// editor does when the read is refused.
vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: { get: vi.fn(), create: vi.fn(), list: vi.fn() },
      pricingRequests: { listForTicket: vi.fn() },
      dealQuotations: {
        get: vi.fn(), create: vi.fn(), update: vi.fn(), calculateLine: vi.fn(), submit: vi.fn(),
        createRevision: vi.fn(), createReorder: vi.fn(),
        displayNameOptions: vi.fn(),
      },
      catalog: { prices: vi.fn() },
      designers: { search: vi.fn(), getByCode: vi.fn().mockRejectedValue(new Error('not found')) },
      customers: {
        search: vi.fn(), create: vi.fn(), update: vi.fn(), projects: vi.fn(),
        contacts: vi.fn(),
      },
    },
  };
});

const COPY = 'คัดลอกเป็นใบใหม่';

// Today, for every case in this file (Asia/Bangkok). The source's ยืนราคา date, 2026-11-30, is
// still ahead of it; a case about a date that has passed moves the source's date, never the clock.
// Only `Date` is pinned. The debounced searches and the waits below run on real timers.
const TODAY = '2026-11-02';
beforeEach(() => { vi.setSystemTime(new Date(`${TODAY}T09:00:00+07:00`)); });
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

const salesUser = { id: 6, name: 'คุณสมหมาย ขายดี', role: 'sales', employeeId: null };
// The contractor the copy is FOR — and the project that contractor already has on file.
const NEW_CUSTOMER = { id: 2, name: 'บริษัท ผู้รับเหมา บี จำกัด', taxId: '0105550000002', address: '2 ถนนใหม่', phone: '02-222-2222' };
const NEW_CUSTOMERS_PROJECT = { id: 77, customerId: 2, name: 'คอนโด ริเวอร์ไซด์ ชั้น 5' };

const DISPLAY_OPTIONS = [
  { id: 4, name: 'พนักงาน ได้สิทธิ์' },
  { id: 9, name: 'ผู้จัดการ ฝ่ายขาย' },
];

// Complete stored rows — validateQuotationItem finds nothing missing on any of them, so the only
// thing between the opened copy and a save is the customer.
const TILE = {
  id: 5550001, seq: 1, lineType: 'TILE', locationLabel: 'ชั้น 5 โซน A', catalogPriceId: null, productCode: null,
  brand: 'Panaria', model: 'Trilogy', color: 'Ivory', texture: 'Lappato', sizeText: '60x120', thicknessMm: 10,
  sqmPerPiece: 0.72, quantityMode: 'PIECES', areaSqm: null, piecesInput: 10, wastageMode: 'NONE',
  wastageValue: null, piecesPerBox: 2, unitPrice: 1210.25, discountPct: 20, originCountry: null,
  leadTimeMinDays: 75, leadTimeMaxDays: 90, itemNotes: 'ตัดขอบ 4 ด้าน',
  piecesPerSqm: 1.39, piecesBeforeWastage: 10, piecesAfterWastage: 10, piecesFinal: 10, boxes: 5,
  netUnitPrice: 968.2, lineAmount: 9682, descriptionLine: 'Trilogy Ivory', sizeLine: '60x120', calculationLine: '(calc)',
  quantity: 10, unit: 'แผ่น', specialPriceSqm: null, adjustmentPct: null, adjustmentDeadline: null,
  specialPriceLine: null, adjustmentAmount: null, listPriceSqm: 1800, directNetPriceSqm: null, priceMode: 'NET',
  hasPicture: false, picturePlacement: null, pictureUrl: null,
};
const SPECIAL_TILE = {
  ...TILE, id: 5550002, seq: 2, model: 'Urbanature', discountPct: null, specialPriceSqm: 1350, itemNotes: null,
  netUnitPrice: 907.66, lineAmount: 9076.6, specialPriceLine: 'ราคาพิเศษ 1,350 บาท/ตร.ม.', priceMode: 'SPECIAL_SQM',
};
const FREIGHT = {
  id: 5550003, seq: 3, lineType: 'PLAIN', locationLabel: 'ชั้น 5 โซน B', descriptionLine: 'ค่าขนส่ง', quantity: 2,
  unit: 'งาน', unitPrice: 500, discountPct: 0, netUnitPrice: 500, lineAmount: 1000, piecesFinal: 0,
  leadTimeMinDays: null, leadTimeMaxDays: null, itemNotes: null, priceMode: null,
};
const EARLY_ORDER_DISCOUNT = {
  id: 5550004, seq: 4, lineType: 'ADJUSTMENT', adjustmentPct: 3, adjustmentAmount: null, adjustmentDeadline: '2026-11-30',
  unitPrice: 592.76, netUnitPrice: 592.76, lineAmount: -592.76, quantity: -1, unit: null,
  descriptionLine: 'ส่วนลดพิเศษ 3%', piecesFinal: 0, priceMode: null,
};

// Every identifying value is distinctive on purpose — see SOURCE_IDENTITY below.
function source(overrides = {}) {
  return {
    id: 918273, number: 'QT-2026-0777-1', ticketId: 827364, docStatus: 'APPROVED', revisionNo: 1,
    parentQuotationId: null, derivedFromQuotationId: null, origin: 'DEAL_DIRECT', pricingRequestId: null,
    createdById: 31, createdByName: 'แอดมิน ผู้พิมพ์', salesRepId: 6, salesRepName: 'คุณสมหมาย ขายดี', salesRepPhone: '081-000-0000',
    submittedAt: '2026-09-21T03:00:00Z', approvedById: 9, approvedByName: 'ผู้จัดการ อนุมัติเอง',
    approvedAt: '2026-09-22T03:00:00Z', approvalNote: null, approverHasSignature: false,
    quotationDate: '2026-09-20',
    customerName: 'บริษัท ผู้รับเหมา เอ จำกัด', customerAddress: '1 ถนนเดิม เขตเดิม', customerTaxId: '0105550000001',
    customerPhone: '02-111-1111',
    contactId: 6660001, contactName: 'สมชาย ลูกค้าเดิม', contactPhone: '081-999-0000', contactEmail: 'old.contact@contractor-a.example',
    orderedByName: 'คุณวิชัย มั่นคง', orderedByPhone: '089-765-4321', orderedByEmail: 'wichai@contractor-a.example',
    projectName: 'คอนโด ริเวอร์ไซด์ ชั้น 5', hideProjectName: true,
    deptCode: null, unitCode: 'A001', offerDate: '2026-09-18',
    depositPercent: 45, remainderMode: 'CREDIT', creditDays: 30, fullPaymentTerm: null,
    validityDays: 30, validityDate: '2026-10-20', validityMode: 'DATE', validityUntil: '2026-11-30',
    customerNotes: 'ส่งของภายใน 30 วันหลังยืนยัน',
    priceMode: 'MIXED', documentLanguage: 'TH', currency: 'THB',
    subtotalAmount: 19165.84, vatAmount: 1341.61, grandTotal: 20507.45,
    printedByDisplayId: 4, printedByDisplayName: 'พนักงาน ได้สิทธิ์', printedByDisplayNameEn: null,
    salesRepDisplayId: 9, salesRepDisplayName: 'ผู้จัดการ ฝ่ายขาย', salesRepDisplayNameEn: null, salesRepDisplayPhone: '081-222-3333',
    omitContactHonorific: false,
    items: [TILE, SPECIAL_TILE, FREIGHT, EARLY_ORDER_DISCOUNT],
    createdAt: '2026-09-20T02:00:00Z', updatedAt: '2026-09-22T03:00:00Z',
    ...overrides,
  };
}

/** What must reach neither the screen of the opened copy nor anything it saves. */
const SOURCE_IDENTITY = [
  '918273', '827364', '5550001', '5550002', '5550003', '5550004', '6660001',
  'บริษัท ผู้รับเหมา เอ จำกัด', '1 ถนนเดิม', '0105550000001', '02-111-1111',
  'สมชาย ลูกค้าเดิม', '081-999-0000', 'old.contact@contractor-a.example',
  'คุณวิชัย มั่นคง', '089-765-4321', 'wichai@contractor-a.example',
];

const byId = (id) => document.getElementById(id);
function Providers({ path, children }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>{children}</Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function OpenedNew() {
  const { search } = useLocation();
  return <p>opened-new{search}</p>;
}

function SavedAs() {
  const { id } = useParams();
  return <p>navigated-to-{id}</p>;
}

/** The saved quotation's own page; /quotations/new is a stand-in that names the query it got. */
function renderSaved(quotation, user = salesUser) {
  api.dealQuotations.get.mockResolvedValue({ quotation });
  return render(
    <Providers path={`/quotations/${quotation.id}`}>
      <Route path="/quotations/new" element={<OpenedNew />} />
      <Route path="/quotations/:id" element={<QuotationEditorPage user={user} showToast={vi.fn()} />} />
    </Providers>,
  );
}

/** /quotations/new?copyFrom=918273; /quotations/:id is a stand-in that names where a save landed. */
function renderCopy(user = salesUser) {
  const showToast = vi.fn();
  const result = render(
    <Providers path="/quotations/new?copyFrom=918273">
      <Route path="/quotations/new" element={<QuotationEditorPage user={user} showToast={showToast} />} />
      <Route path="/quotations/:id" element={<SavedAs />} />
    </Providers>,
  );
  return { ...result, showToast };
}

/** Renders the copy and waits until the source's first row is on the form. */
async function openCopy() {
  const result = renderCopy();
  await waitFor(() => expect(byId('model-0')?.value).toBe('Trilogy'));
  return result;
}

async function chooseTheNewCustomer() {
  fireEvent.change(screen.getByLabelText(/^ลูกค้า/), { target: { value: 'ผู้รับเหมา บี' } });
  fireEvent.mouseDown(await screen.findByRole('option', { name: new RegExp(NEW_CUSTOMER.name) }));
  await waitFor(() => expect(api.customers.projects).toHaveBeenCalledWith(NEW_CUSTOMER.id));
}

// Slice 2 (S2-B1) + #1085: a NEW deal needs its ผู้รับใบเสนอราคา and a real ช่องทางรับงาน before
// บันทึกร่าง is enabled. A copy is a new quotation on a new deal, so both are the rep's to choose.
function chooseRecipientAndChannel() {
  fireEvent.click(screen.getByRole('radio', { name: 'ผู้ออกแบบ' }));
  fireEvent.click(screen.getByRole('button', { name: /ผู้ออกแบบนำงานมา/ }));
}
async function chooseItsProject() {
  fireEvent.focus(screen.getByLabelText(/^โครงการ/));
  fireEvent.mouseDown(await screen.findByRole('option', { name: new RegExp(NEW_CUSTOMERS_PROJECT.name) }));
}

beforeEach(() => {
  vi.clearAllMocks();
  api.dealQuotations.get.mockResolvedValue({ quotation: source() });
  api.dealQuotations.displayNameOptions.mockResolvedValue({ items: DISPLAY_OPTIONS });
  api.dealQuotations.calculateLine.mockResolvedValue({ item: {} });
  api.dealQuotations.update.mockImplementation(async () => ({ quotation: source({ docStatus: 'DRAFT' }) }));
  api.dealQuotations.create.mockResolvedValue({ quotation: { id: 31337 } });
  api.tickets.get.mockResolvedValue({
    ticket: {
      summary: {
        id: 827364, createdById: 6, createdByName: 'คุณสมหมาย ขายดี', customerName: 'บริษัท ผู้รับเหมา เอ จำกัด',
        customerId: 1, projectId: 3, projectName: 'คอนโด ริเวอร์ไซด์ ชั้น 5', contactId: null, contactName: null,
      },
    },
  });
  api.tickets.create.mockResolvedValue({ ticket: { summary: { id: 4242 } } });
  api.tickets.list.mockResolvedValue({ tickets: [] });
  api.pricingRequests.listForTicket.mockResolvedValue({ items: [] });
  api.customers.search.mockResolvedValue({ customers: [NEW_CUSTOMER] });
  api.customers.projects.mockResolvedValue({ projects: [NEW_CUSTOMERS_PROJECT] });
  api.customers.contacts.mockResolvedValue({ contacts: [] });
});

describe('the "คัดลอกเป็นใบใหม่" button on a saved quotation', () => {
  it('opens /quotations/new?copyFrom=<this quotation>', async () => {
    renderSaved(source());

    fireEvent.click(await screen.findByRole('button', { name: COPY }));

    expect(await screen.findByText('opened-new?copyFrom=918273')).not.toBeNull();
    // A navigation and nothing else: the source is not touched and nothing is created yet.
    expect(api.dealQuotations.createRevision).not.toHaveBeenCalled();
    expect(api.dealQuotations.createReorder).not.toHaveBeenCalled();
    expect(api.dealQuotations.create).not.toHaveBeenCalled();
    expect(api.tickets.create).not.toHaveBeenCalled();
  });

  // Wrong way round. A quotation written from a คำขอราคา carries the CEO's prices for THAT request;
  // they are not the rep's to re-issue to another customer.
  it('is never offered on a quotation that came from a pricing request', async () => {
    const direct = renderSaved(source());
    await screen.findByRole('button', { name: COPY }); // the control: same rep, same page, direct deal
    direct.unmount();

    renderSaved(source({
      origin: 'PRICING_REQUEST', pricingRequestId: 12, pricingRequestCode: 'PCR-2026-0012', docStatus: 'ISSUED',
      priceMode: 'NET', items: [{ ...TILE, priceMode: null }],
    }));

    await screen.findByRole('button', { name: 'ดาวน์โหลด' }); // the page is up
    expect(screen.queryByRole('button', { name: COPY })).toBeNull();
  });
});

describe('/quotations/new?copyFrom= — saving it', () => {
  it('creates a NEW deal for the new customer and a NEW quotation on it, carrying nothing of the source', async () => {
    await openCopy();
    await chooseTheNewCustomer();
    await chooseItsProject();
    chooseRecipientAndChannel();
    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));

    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(api.dealQuotations.create).toHaveBeenCalledTimes(1));
    // A new deal, for the customer and project the rep just chose.
    expect(api.tickets.create).toHaveBeenCalledTimes(1);
    const dealPayload = api.tickets.create.mock.calls[0][0];
    expect(dealPayload).toMatchObject({
      customerId: NEW_CUSTOMER.id, customerName: NEW_CUSTOMER.name, projectId: NEW_CUSTOMERS_PROJECT.id, contactId: null,
    });
    // A new quotation, on THAT deal — never an update, a revision or a reorder of the source.
    const [dealId, payload] = api.dealQuotations.create.mock.calls[0];
    expect(dealId).toBe(4242);
    expect(api.dealQuotations.update).not.toHaveBeenCalled();
    expect(api.dealQuotations.createRevision).not.toHaveBeenCalled();
    expect(api.dealQuotations.createReorder).not.toHaveBeenCalled();

    // The whole request, key for key. Each row is what re-saving the source's own row would send
    // (the editor's existing load → save round trip), with no id.
    const savedRow = (dto) => {
      const mode = rowPriceMode('MIXED', dto);
      return { ...itemInputFromRow(rowFromServerItem(dto, mode), mode, 'TH', { mixed: true }), id: null };
    };
    expect(payload).toEqual({
      // ── left for the rep, and left alone here ──
      contactId: null,
      orderedByPhone: null,
      orderedByEmail: null,
      // The deal's own project — the one just chosen — is what the document names.
      projectName: null,
      // ── copied ──
      unitCode: 'A001',
      depositPercent: 45,
      remainderMode: 'CREDIT',
      creditDays: 30,
      fullPaymentTerm: null,
      validityDays: 30,
      validityMode: 'DATE',
      validityUntil: '2026-11-30',
      customerNotes: 'ส่งของภายใน 30 วันหลังยืนยัน',
      priceMode: 'MIXED',
      documentLanguage: 'TH',
      currency: 'THB',
      printedByDisplayId: 4,
      salesRepDisplayId: 9,
      hideProjectName: true,
      // ── as on any new quotation ──
      offerDate: bangkokTodayIso(),
      deptCode: null,
      omitContactHonorific: true,
      // Slice 2 (S2-B1): a direct quotation always states its recipient — the one chosen above.
      recipientType: 'DESIGNER',
      items: [
        savedRow(TILE), savedRow(SPECIAL_TILE), savedRow(FREIGHT),
        { ...adjustmentInputFromRow(rowFromServerItem(EARLY_ORDER_DISCOUNT)), id: null },
      ],
    });
    // The signature name is the server's to write from the contact (ruling 2026-10-18): not sent.
    expect(payload).not.toHaveProperty('orderedByName');
    expect(payload.items.map((row) => row.id)).toEqual([null, null, null, null]);

    const sent = JSON.stringify([dealPayload, payload]);
    for (const leaked of [...SOURCE_IDENTITY, 'QT-2026-0777', 'APPROVED']) {
      expect(sent, `"${leaked}" was sent with the copy`).not.toContain(leaked);
    }

    expect(await screen.findByText('navigated-to-31337')).not.toBeNull();
  });
});


