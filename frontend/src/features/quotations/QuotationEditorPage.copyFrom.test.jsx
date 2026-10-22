import React from 'react';
import { onlineManager, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation, useNavigate, useParams } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QuotationEditorPage } from './QuotationEditorPage.jsx';
import { api } from '../../api/index.js';
import { rowPriceMode } from './quotationMeta.js';
import { adjustmentInputFromRow, itemInputFromRow, rowFromServerItem } from './QuotationItemRow.jsx';
import { writeQuotationDefaults } from './quotationPrefs.js';
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

const COPY = 'คัดลอกเป็นใบใหม่';

// Today, for every case in this file (Asia/Bangkok). The source's ยืนราคา date, 2026-11-30, is
// still ahead of it; a case about a date that has passed moves the source's date, never the clock.
// Only `Date` is pinned. The debounced searches and the waits below run on real timers.
const TODAY = '2026-11-02';
const YESTERDAY = '2026-11-01';
beforeEach(() => { vi.setSystemTime(new Date(`${TODAY}T09:00:00+07:00`)); });
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

/** A Storage that stores. This repo's Node ships a built-in `localStorage` that keeps nothing (see
 * quotationPrefs.test.js), so a case about the terms a rep's quotations start with brings its own. */
function memoryStorage() {
  const map = new Map();
  return {
    getItem: (key) => (map.has(key) ? map.get(key) : null),
    setItem: (key, value) => { map.set(key, String(value)); },
    removeItem: (key) => { map.delete(key); },
  };
}

const salesUser = { id: 6, name: 'คุณสมหมาย ขายดี', role: 'sales', employeeId: null };
const ceoUser = { id: 8, name: 'ราม', role: 'ceo', employeeId: 136 };
const importUser = { id: 7, name: 'คุณนำเข้า พานิช', role: 'import', employeeId: null };
const accountUser = { id: 5, name: 'คุณบัญชี รอบคอบ', role: 'account', employeeId: null };

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
const group = (name) => screen.getByRole('group', { name });
const pressed = (button) => button.getAttribute('aria-pressed') === 'true';

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
  fireEvent.click(screen.getByRole('button', { name: /ผู้ออกแบบนำดีล/ }));
}
async function chooseItsProject() {
  fireEvent.focus(screen.getByLabelText(/^โครงการ/));
  fireEvent.mouseDown(await screen.findByRole('option', { name: new RegExp(NEW_CUSTOMERS_PROJECT.name) }));
}

/** Everything a person could read on the page: its text, and what every control holds. */
function everythingOnThePage() {
  const controls = [...document.querySelectorAll('input, textarea, select')].map((el) => el.value);
  return [document.body.textContent, ...controls].join('\n');
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

  // Owner 2026-10-12: "a small button to duplicate beside the quotation line instead of ... inside,
  // its too much cta". So it is an icon button next to the title, named for the reader by its
  // aria-label, and it is neither part of the heading's text nor one of the action-bar CTAs.
  it('is a small icon button beside the title, outside the heading and outside the action bar', async () => {
    renderSaved(source());

    const button = await screen.findByRole('button', { name: COPY });
    const heading = screen.getByRole('heading', { level: 1 });
    expect(button.textContent).toBe('');
    expect(heading.textContent).toBe('ใบเสนอราคา QT-2026-0777-1');
    expect(heading.contains(button)).toBe(false);
    expect(heading.parentElement.contains(button)).toBe(true);
    expect(button.closest('.page-actions')).toBeNull();
  });

  // The copy is made from what is SAVED. A draft with edits still on the page would be copied
  // without them — and leaving the page would lose them from the source too — so the button waits
  // for the save rather than quietly doing either.
  it('on a draft, waits for unsaved edits to be saved first', async () => {
    renderSaved(source({ docStatus: 'DRAFT', submittedAt: null, approvedById: null, approvedByName: null, approvedAt: null }));

    const button = await screen.findByRole('button', { name: COPY });
    expect(button.disabled).toBe(false);

    fireEvent.change(byId('customerNotes'), { target: { value: 'แก้ไขหมายเหตุ ยังไม่ได้บันทึก' } });

    expect(screen.getByRole('button', { name: COPY }).disabled).toBe(true);
    expect(screen.getByRole('button', { name: COPY }).title).toContain('บันทึก');
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

  // Wrong way round. Being able to READ a quotation is not being able to write one.
  it('is never offered to someone who may view quotations but not create them', async () => {
    const asTheRep = renderSaved(source());
    await screen.findByRole('button', { name: COPY }); // the control
    asTheRep.unmount();

    for (const viewer of [ceoUser, importUser, accountUser]) {
      const page = renderSaved(source(), viewer);
      await screen.findByRole('button', { name: 'ดาวน์โหลด' });
      expect(screen.queryByRole('button', { name: COPY }), `offered to ${viewer.role}`).toBeNull();
      page.unmount();
    }
  });
});

describe('/quotations/new?copyFrom= — the draft it opens', () => {
  // A blank form shown while the source is on its way would be typed into, and then replaced.
  it('shows no form to type into until the source has arrived', async () => {
    api.dealQuotations.get.mockReturnValue(new Promise(() => {}));
    renderCopy();

    await waitFor(() => expect(api.dealQuotations.get).toHaveBeenCalledTimes(1));
    expect(screen.queryByLabelText(/^ลูกค้า/)).toBeNull();
    expect(screen.queryByRole('button', { name: 'บันทึกร่าง' })).toBeNull();
  });

  // The request can also be HELD BACK before it settles: offline, or between retries while the
  // tab is hidden. That is neither loading nor an answer, and a form shown then would be typed
  // into and then replaced the moment the source arrives.
  it('shows no form while the source request is held back, either', async () => {
    onlineManager.setOnline(false);
    try {
      renderCopy();
      await new Promise((resolve) => { setTimeout(resolve, 50); });

      // Control: the request really is paused, not sent.
      expect(api.dealQuotations.get).not.toHaveBeenCalled();
      expect(screen.queryByLabelText(/^ลูกค้า/)).toBeNull();
      expect(screen.queryByRole('button', { name: 'บันทึกร่าง' })).toBeNull();
    } finally {
      onlineManager.setOnline(true);
    }
  });

  it('is the new-quotation editor, already filled in from the source and not yet saved', async () => {
    await openCopy();

    expect(String(api.dealQuotations.get.mock.calls[0][0])).toBe('918273');
    // Rows, in the source's order and under the source's ตำแหน่งติดตั้ง.
    expect(byId('group-label-0').value).toBe('ชั้น 5 โซน A');
    expect(byId('group-label-1').value).toBe('ชั้น 5 โซน B');
    expect(byId('model-1').value).toBe('Urbanature');
    expect(byId('plain-desc-2').value).toBe('ค่าขนส่ง');
    expect(byId('adj-pct-3').value).toBe('3');
    expect(byId('adj-deadline-3').value).toBe('2026-11-30');
    // Each row in the price mode it was typed in, with the price it was typed with.
    expect(pressed(within(group('วิธีกรอกราคากระเบื้อง')).getByRole('button', { name: 'ผสมหลายวิธี' }))).toBe(true);
    expect(byId('price-mode-0').value).toBe('NET');
    expect(byId('price-0').value).toBe('1800');
    expect(byId('price-mode-1').value).toBe('SPECIAL_SQM');
    expect(byId('special-1').value).toBe('1350');
    // The terms.
    expect(pressed(within(group('ภาษาเอกสาร')).getByRole('button', { name: /ไทย/ }))).toBe(true);
    expect(byId('depositPercent').value).toBe('45');
    expect(byId('creditDays').value).toBe('30');
    expect(byId('validityUntil').value).toBe('2026-11-30');
    expect(byId('customerNotes').value).toBe('ส่งของภายใน 30 วันหลังยืนยัน');
    expect(byId('hideProjectNameInline').checked).toBe(true);
    expect(byId('inline-quotation-designer-picker').value).toContain('A001');
    await waitFor(() => expect(byId('printedByDisplayId').value).toBe('4'));
    expect(byId('salesRepDisplayId').value).toBe('9');

    // It is a NEW quotation: the page is not the source's, and nothing has been written.
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('สร้างใบเสนอราคา');
    expect(screen.queryByRole('button', { name: 'ดาวน์โหลด' })).toBeNull();
    expect(api.tickets.create).not.toHaveBeenCalled();
    expect(api.dealQuotations.create).not.toHaveBeenCalled();
    expect(api.dealQuotations.update).not.toHaveBeenCalled();
  });

  // Wrong way round: what the rep must fill in again is EMPTY, and the source's customer, contact
  // and ผู้ติดต่อ are nowhere on the page — not in a field, not in a line of text.
  it('leaves the customer, the ผู้ติดต่อ and the customer phone/email for the rep', async () => {
    await openCopy();

    expect(screen.getByLabelText(/^ลูกค้า/).value).toBe('');
    expect(screen.getByLabelText(/^โครงการ/).disabled).toBe(true); // a project belongs to a customer
    // The picker holds no contact, and waits for the new customer (a contact belongs to one).
    expect(screen.getByLabelText(/^ผู้ติดต่อ/).textContent).toContain('เลือกลูกค้าก่อน');
    expect(byId('orderedByPhoneInline')?.value).toBe('');
    expect(byId('orderedByEmailInline')?.value).toBe('');

    const page = everythingOnThePage();
    for (const leaked of SOURCE_IDENTITY) {
      expect(page, `"${leaked}" is on the opened copy`).not.toContain(leaked);
    }
  });

  it('says which quotation it was copied from and what still has to be filled in', async () => {
    await openCopy();

    const notice = screen.getByTestId('copy-notice');
    expect(notice.textContent).toContain('QT-2026-0777-1');
    expect(notice.textContent).toContain('ลูกค้า');
    expect(notice.textContent).toContain('เลือกผู้ติดต่อ และกรอกโทรศัพท์/อีเมลลูกค้า');
    // None of the source's rows has a picture, so the notice has nothing to say about pictures.
    expect(notice.textContent).not.toContain('รูปภาพ');
  });

  // Item pictures are stored against the source's rows and this editor has no way to attach one
  // to a row, so a copy cannot bring them. It says so — once, and only when there was one to lose.
  it('says so when the source had item pictures, which do not come along', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: source({
        items: [
          { ...TILE, hasPicture: true, picturePlacement: 'BELOW', pictureUrl: '/api/deal-quotations/918273/items/5550001/picture' },
          SPECIAL_TILE, FREIGHT, EARLY_ORDER_DISCOUNT,
        ],
      }),
    });
    await openCopy();

    expect(screen.getByTestId('copy-notice').textContent).toContain('รูปภาพ');
    expect(document.querySelector('img[src*="/api/deal-quotations/"]')).toBeNull();
  });

  // A ยืนราคา date that has gone by is not carried: the server refuses it on the first save, which
  // is after the new deal has been made. The draft says the validity has to be set again, and
  // says nothing about ยืนราคา when the date did come along.
  it('says so when the source\'s ยืนราคา date had passed — and only then', async () => {
    const stillAhead = await openCopy(); // the control: the same source, its date still ahead
    expect(byId('validityUntil').value).toBe('2026-11-30');
    expect(screen.getByTestId('copy-notice').textContent).not.toContain('ยืนราคา');
    stillAhead.unmount();

    api.dealQuotations.get.mockResolvedValue({ quotation: source({ validityUntil: YESTERDAY }) });
    await openCopy();

    expect(screen.getByTestId('copy-notice').textContent).toContain('ยืนราคา');
    expect(everythingOnThePage()).not.toContain(YESTERDAY);
  });

  // Same job, different bidder. A project is a record under a customer, so the name cannot pick
  // one until the rep has said who the customer is — and then it is already in the box.
  it('offers the source\'s project name as soon as the new customer is chosen', async () => {
    await openCopy();

    await chooseTheNewCustomer();

    expect(screen.getByLabelText(/^โครงการ/).value).toBe('คอนโด ริเวอร์ไซด์ ชั้น 5');
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
      omitContactHonorific: false,
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

  // Wrong way round for the save: what was not carried is not sent. The validity that IS sent is
  // the one a new quotation of this rep's starts with, their own remembered 60 days, and not the
  // source's 30 days on a fixed date.
  it('saves a copy whose source\'s ยืนราคา date had passed with a new quotation\'s validity, and never sends that date', async () => {
    vi.stubGlobal('localStorage', memoryStorage());
    writeQuotationDefaults(salesUser.id, { validityDays: 60 });
    api.dealQuotations.get.mockResolvedValue({ quotation: source({ validityUntil: YESTERDAY }) });
    await openCopy();
    await chooseTheNewCustomer();
    await chooseItsProject();
    chooseRecipientAndChannel();
    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));

    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(api.dealQuotations.create).toHaveBeenCalledTimes(1));
    const [, payload] = api.dealQuotations.create.mock.calls[0];
    expect({ validityMode: payload.validityMode, validityDays: payload.validityDays, validityUntil: payload.validityUntil })
      .toEqual({ validityMode: 'DAYS', validityDays: 60, validityUntil: null });
    expect(JSON.stringify([api.tickets.create.mock.calls, api.dealQuotations.create.mock.calls])).not.toContain(YESTERDAY);
    // Only ยืนราคา started over: the other terms are still the source's.
    expect(payload).toMatchObject({ unitCode: 'A001', depositPercent: 45, remainderMode: 'CREDIT', creditDays: 30 });
  });
});

describe('/quotations/new?copyFrom= — when it must not copy', () => {
  // Wrong way round. The link can be typed, bookmarked or shared; the button being hidden is not
  // what stops a pricing-request quotation being copied.
  it('refuses a source that came from a pricing request: nothing is filled in, and it says why', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: source({ origin: 'PRICING_REQUEST', pricingRequestId: 12, pricingRequestCode: 'PCR-2026-0012' }),
    });
    renderCopy();

    const refusal = await screen.findByTestId('copy-refused');
    expect(refusal.textContent).toContain('QT-2026-0777-1');
    expect(refusal.textContent).toContain('คำขอราคา');
    // An ordinary blank new quotation, which the rep can still fill in by hand.
    expect(byId('model-0')).toBeNull();
    expect(byId('customerNotes').value).toBe('');
    expect(screen.getByLabelText(/^ลูกค้า/).value).toBe('');
    expect(screen.queryByTestId('copy-notice')).toBeNull();
    const page = everythingOnThePage();
    for (const leaked of SOURCE_IDENTITY) {
      expect(page, `"${leaked}" is on the refused copy`).not.toContain(leaked);
    }
  });

  it('refuses a source the viewer cannot read, with the server\'s own reason', async () => {
    api.dealQuotations.get.mockRejectedValue(Object.assign(new Error('ไม่มีสิทธิ์เข้าถึงรายการนี้'), { status: 403 }));
    renderCopy();

    const refusal = await screen.findByTestId('copy-refused');
    expect(refusal.textContent).toContain('ไม่มีสิทธิ์เข้าถึงรายการนี้');
    expect(byId('model-0')).toBeNull();
    expect(screen.getByLabelText(/^ลูกค้า/).value).toBe('');
    expect(screen.queryByTestId('copy-notice')).toBeNull();
  });

  // REGRESSION GUARD — green before the feature exists: /quotations/new already refuses someone
  // who may not create a quotation. It is here so that opening a copy can never become a way
  // around that refusal.
  it('gives someone who may not create quotations no editor at all, copy or not', async () => {
    renderCopy(importUser);

    expect(await screen.findByText(/มีสิทธิ์ดูใบเสนอราคาเท่านั้น/)).not.toBeNull();
    expect(byId('model-0')).toBeNull();
    expect(screen.queryByRole('button', { name: 'บันทึกร่าง' })).toBeNull();
    expect(screen.queryByTestId('copy-notice')).toBeNull();
  });
});


// A page instance outlives a route change (/quotations/:id and /quotations/new render the same
// component at the same place), so these cases mount BOTH routes with the real editor.
function renderBothRoutes(path, user = salesUser) {
  const showToast = vi.fn();
  const result = render(
    <Providers path={path}>
      <Route path="/quotations/new" element={<QuotationEditorPage user={user} showToast={showToast} />} />
      <Route path="/quotations/:id" element={<QuotationEditorPage user={user} showToast={showToast} />} />
    </Providers>,
  );
  return { ...result, showToast };
}

describe('/quotations/new?copyFrom= — then picking an existing deal', () => {
  it('keeps the copied rows, and the save lands on the picked deal — no second deal is made', async () => {
    api.tickets.list.mockResolvedValue({
      tickets: [{
        id: 18, code: 'DL-2026-0018', createdById: 6, customerName: 'บริษัท ผู้รับเหมา บี จำกัด', customerId: 2,
        projectName: 'คอนโด ริเวอร์ไซด์ ชั้น 5', lifecycle: 'ACTIVE', salesStage: 'QUOTE_DESIGN_SIDE', liveDirectQuotation: null,
      }],
    });
    api.tickets.get.mockResolvedValue({
      ticket: {
        summary: {
          id: 18, code: 'DL-2026-0018', createdById: 6, createdByName: 'คุณสมหมาย ขายดี',
          customerName: 'บริษัท ผู้รับเหมา บี จำกัด', customerId: 2, projectId: 77, projectName: 'คอนโด ริเวอร์ไซด์ ชั้น 5',
          contactId: null, contactName: null, salesStage: 'QUOTE_DESIGN_SIDE', entryChannel: 'DESIGNER_LED',
        },
      },
    });
    renderBothRoutes('/quotations/new?copyFrom=918273');
    await waitFor(() => expect(byId('model-0')?.value).toBe('Trilogy'));

    fireEvent.click(screen.getByRole('button', { name: 'เลือกดีลที่มีอยู่' }));
    fireEvent.focus(await screen.findByRole('combobox', { name: /ดีล/ }));
    fireEvent.mouseDown(await screen.findByRole('option', { name: /DL-2026-0018/ }));
    await waitFor(() => expect(api.tickets.get).toHaveBeenCalledWith(18));

    // The half-built form is not wiped by choosing a deal.
    await waitFor(() => expect(screen.getByRole('radio', { name: 'ผู้ออกแบบ' }).getAttribute('aria-checked')).toBe('true'));
    expect(byId('model-0')?.value).toBe('Trilogy');
    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(api.dealQuotations.create).toHaveBeenCalledTimes(1));
    expect(api.dealQuotations.create.mock.calls[0][0]).toBe(18);
    expect(api.dealQuotations.create.mock.calls[0][1].items).toHaveLength(4);
    expect(api.tickets.create).not.toHaveBeenCalled();
  });
});

// The page instance survives /quotations/:id -> /quotations/new?copyFrom=, so what the earlier visit
// left in its state must not reach the copy.
describe('a copy opened from a page that was already showing something else', () => {
  const GO_COPY = 'go-copy';
  function GoCopy() {
    const navigate = useNavigate();
    return <button type="button" onClick={() => navigate('/quotations/new?copyFrom=918273')}>{GO_COPY}</button>;
  }
  function renderWithGoCopy(path) {
    const showToast = vi.fn();
    return render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}>
        <MemoryRouter initialEntries={[path]}>
          <GoCopy />
          <Routes>
            <Route path="/quotations/new" element={<QuotationEditorPage user={salesUser} showToast={showToast} />} />
            <Route path="/quotations/:id" element={<QuotationEditorPage user={salesUser} showToast={showToast} />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
  }
  const recipientRadios = () => within(screen.getByRole('radiogroup', { name: 'ผู้รับใบเสนอราคา' })).getAllByRole('radio');

  it('preselects no recipient, and blocks บันทึกร่าง until one is chosen, even if the source had one', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: source({ recipientType: 'DESIGNER', recipientLabel: 'ผู้ออกแบบ' }) });
    renderBothRoutes('/quotations/918273');
    fireEvent.click(await screen.findByRole('button', { name: COPY }));

    await waitFor(() => expect(byId('model-0')?.value).toBe('Trilogy'));
    expect(recipientRadios().map((r) => r.getAttribute('aria-checked'))).toEqual(['false', 'false', 'false']);
    expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(true);
    expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).title).toContain('ต้องเลือกผู้รับใบเสนอราคา');
  });

  it('opens on สร้างดีลใหม่ even after the rep chose เลือกดีลที่มีอยู่ on an earlier new-quotation visit', async () => {
    renderWithGoCopy('/quotations/new');
    fireEvent.click(await screen.findByRole('button', { name: 'สร้างดีลใหม่' }));
    fireEvent.click(screen.getByRole('button', { name: 'เลือกดีลที่มีอยู่' }));
    expect(screen.getByRole('button', { name: 'เลือกดีลที่มีอยู่' }).getAttribute('aria-pressed')).toBe('true');

    fireEvent.click(screen.getByRole('button', { name: GO_COPY }));

    await waitFor(() => expect(byId('model-0')?.value).toBe('Trilogy'));
    expect(screen.getByRole('button', { name: 'สร้างดีลใหม่' }).getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByLabelText(/^ลูกค้า/)).not.toBeNull();
  });
});
