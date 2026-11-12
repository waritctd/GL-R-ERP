import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation, useParams } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QuotationEditorPage } from './QuotationEditorPage.jsx';
import { api } from '../../api/index.js';
import { REVISION_RECIPIENT_LOCKED_MESSAGE } from './quotationMeta.js';
import { queryKeys } from '../../api/queryKeys.js';

globalThis.React = React;

// The quotation is linked to its deal from the first screen. Step 1 is "ดีล" (pick an existing
// deal, or create one), ผู้รับใบเสนอราคา* is required for a DEAL_DIRECT quotation and travels in
// the create/update payload (persisted only; no stage move), the editor carries a deal header
// strip, and a PRICING_REQUEST row shows its recipient read-only. A separate file because
// QuotationEditorPage.test.jsx is already ~1,500 lines.
vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  // Imported inside the factory, not at the top of the file: vi.mock is hoisted above every import.
  const { DEAL_STAGE_CATALOG } = await import('../../data/dealStageCatalog.js');
  return {
    ...actual,
    api: {
      // The served stage catalog (with `routes`) — the same guarded fixture mockApi serves.
      meta: { dealStages: vi.fn().mockResolvedValue(DEAL_STAGE_CATALOG) },
      tickets: { get: vi.fn(), create: vi.fn(), list: vi.fn() },
      // One pricing route per deal: the picked deal's pricing requests.
      pricingRequests: { listForTicket: vi.fn() },
      dealQuotations: {
        get: vi.fn(), create: vi.fn(), update: vi.fn(), calculateLine: vi.fn(), submit: vi.fn(),
        approve: vi.fn(), reject: vi.fn(), createRevision: vi.fn(), createReorder: vi.fn(), cancel: vi.fn(),
        downloadPdf: vi.fn(), downloadXlsx: vi.fn(), promoteToDeal: vi.fn(),
        // The deal's quotation rows (the deal page's own list query) — N6's pre-check reads every live one.
        listForTicket: vi.fn(),
        displayNameOptions: vi.fn().mockResolvedValue({ items: [] }),
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

const salesUser = { id: 6, name: 'คุณสมหมาย ขายดี', role: 'sales', employeeId: null };
// ภิญญดา-style canCreateQuotation grant-holder (role qc): may write quotations, may NOT open /tickets/:id.
const qcGrantUser = { id: 44, name: 'ภิญญดา', role: 'qc', employeeId: 144, canCreateQuotation: true };

const CUSTOMER = { id: 5, name: 'บริษัท แฟชั่นไอส์แลนด์ จำกัด', taxId: '0105551234567', address: '1 ถนนสุขุมวิท', phone: '02-000-0000' };

const TILE_ITEM = {
  id: 101, seq: 1, lineType: 'TILE', locationLabel: null, catalogPriceId: null, productCode: null,
  brand: 'Marazzi', model: 'Trilogy', color: 'Ash', texture: 'Matt', sizeText: '60x60', thicknessMm: 10,
  sqmPerPiece: 0.36, quantityMode: 'AREA', areaSqm: 20, piecesInput: null, wastageMode: 'PERCENT',
  wastageValue: 0, piecesPerBox: 3, unitPrice: 850, discountPct: 0, originCountry: null,
  leadTimeMinDays: 30, leadTimeMaxDays: 45, itemNotes: null,
  piecesPerSqm: 2.78, piecesBeforeWastage: 56, piecesAfterWastage: 56, piecesFinal: 57, boxes: 19,
  netUnitPrice: 850, lineAmount: 48450, descriptionLine: 'Trilogy Ash', sizeLine: '60x60', calculationLine: '(calc)',
  quantity: 57, unit: 'แผ่น', specialPriceSqm: null, adjustmentPct: null, adjustmentDeadline: null,
  specialPriceLine: null, adjustmentAmount: null,
};

function ticketSummary(overrides = {}) {
  return {
    id: 18, code: 'DL-2026-0018', createdById: 6, createdByName: 'คุณสมหมาย ขายดี',
    customerName: CUSTOMER.name, customerId: 5, projectId: 3, projectName: 'โครงการ A',
    contactId: null, contactName: null, lifecycle: 'ACTIVE', salesStage: 'PRESENTATION',
    liveDirectQuotation: null,
    ...overrides,
  };
}

function draft(overrides = {}) {
  return {
    id: 5, number: 'QT-2026-0005-1', ticketId: 18, docStatus: 'DRAFT', origin: 'DEAL_DIRECT', revisionNo: 1,
    parentQuotationId: null, salesRepId: 6, salesRepName: 'คุณสมหมาย ขายดี', salesRepPhone: null,
    createdByName: 'คุณสมหมาย ขายดี', approvalNote: null, quotationDate: '2026-09-11',
    customerName: CUSTOMER.name, customerAddress: CUSTOMER.address, customerTaxId: CUSTOMER.taxId, customerPhone: CUSTOMER.phone,
    projectName: 'โครงการ A', deptCode: null, unitCode: null, offerDate: '2026-09-01', depositPercent: 30,
    remainderMode: 'ON_DELIVERY', creditDays: null, validityDays: 30, validityDate: null, customerNotes: null,
    priceMode: 'NET', documentLanguage: 'TH', subtotalAmount: 48450, vatAmount: 3391.5, grandTotal: 51841.5,
    currency: 'THB', approverHasSignature: false, items: [TILE_ITEM],
    printedByDisplayId: null, salesRepDisplayId: null, orderedByName: null,
    recipientType: 'OWNER', recipientLabel: 'เจ้าของโครงการ',
    createdAt: '2026-09-11T02:00:00Z', updatedAt: '2026-09-11T02:00:00Z',
    ...overrides,
  };
}

let lastQueryClient = null;

function LocationProbe() {
  const location = useLocation();
  return <p data-testid="location">{location.pathname}{location.search}</p>;
}

function IdProbe() {
  const { id } = useParams();
  return <p>navigated-to-{id}</p>;
}

function renderEditor(path, { user = salesUser, idRoute = 'editor' } = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  lastQueryClient = queryClient;
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <LocationProbe />
        <Routes>
          <Route path="/quotations/new" element={<QuotationEditorPage user={user} showToast={vi.fn()} />} />
          <Route
            path="/quotations/:id"
            element={idRoute === 'editor' ? <QuotationEditorPage user={user} showToast={vi.fn()} /> : <IdProbe />}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

async function fillCompleteQuotationItem() {
  fireEvent.click(screen.getByRole('button', { name: /เพิ่มรายการ/ }));
  fireEvent.change(screen.getByLabelText(/^รุ่น \/ ค้นหาแคตตาล็อก/), { target: { value: 'Trilogy' } });
  fireEvent.change(screen.getByLabelText(/^สี/), { target: { value: 'Ivory' } });
  fireEvent.change(screen.getByLabelText(/^ผิว/), { target: { value: 'Lappato' } });
  fireEvent.change(screen.getByLabelText(/^ขนาด/), { target: { value: '60x120' } });
  fireEvent.change(screen.getByLabelText(/^ความหนา/), { target: { value: '10' } });
  fireEvent.change(screen.getByLabelText(/^แผ่น\/ตร\.ม\./), { target: { value: '1.39' } });
  fireEvent.change(screen.getByLabelText(/^แผ่น\/กล่อง/), { target: { value: '3' } });
  // Per ตร.ม. including VAT on a Thai document.
  fireEvent.change(screen.getByLabelText(/^ราคา\/ตร\.ม\./), { target: { value: '850' } });
  fireEvent.change(screen.getByLabelText(/^จำนวน/), { target: { value: '36' } });
}

const saveButton = () => screen.getByRole('button', { name: 'บันทึกร่าง' });
const recipientGroup = () => screen.getByRole('radiogroup', { name: 'ผู้รับใบเสนอราคา' });

beforeEach(() => {
  vi.clearAllMocks();
  api.tickets.list.mockResolvedValue({ tickets: [ticketSummary()] });
  api.tickets.get.mockResolvedValue({ ticket: { summary: ticketSummary() } });
  api.customers.search.mockResolvedValue({ customers: [CUSTOMER] });
  api.customers.contacts.mockResolvedValue({ contacts: [] });
  api.customers.projects.mockResolvedValue({ projects: [] });
  api.dealQuotations.calculateLine.mockResolvedValue({ item: {} });
  api.pricingRequests.listForTicket.mockResolvedValue({ items: [] });
  api.dealQuotations.listForTicket.mockResolvedValue({ items: [] });
});

describe('/quotations/new — step 1 is "ดีล" (slice 2 §A)', () => {
  it('opens on เลือกดีลที่มีอยู่, and บันทึกร่าง says a deal must be picked first', async () => {
    renderEditor('/quotations/new');
    expect(await screen.findByRole('heading', { name: 'ดีล' })).not.toBeNull();
    expect(screen.getByRole('button', { name: 'เลือกดีลที่มีอยู่' }).getAttribute('aria-pressed')).toBe('true');
    expect(await screen.findByRole('combobox', { name: /ดีล/ })).not.toBeNull();
    expect(saveButton().disabled).toBe(true);
    expect(saveButton().title).toContain('ต้องเลือกดีลก่อนบันทึกร่าง');
  });

  // A ผู้ติดต่อ belongs to ONE customer and the server refuses another customer's contactId
  // (DealQuotationService#resolveContact → 400), so switching to a deal of another customer drops
  // the contact picked for the first deal.
  it('picking a deal turns the page into the ?ticket= path (no second code path); switching deals drops the ผู้ติดต่อ picked for the first', async () => {
    const OTHER = ticketSummary({
      id: 19, code: 'DL-2026-0019', customerName: 'บริษัท อีกราย จำกัด', customerId: 9, salesStage: 'QUOTE_OWNER',
    });
    api.tickets.list.mockResolvedValue({ tickets: [ticketSummary(), OTHER] });
    api.tickets.get.mockImplementation(async (ticketId) => ({ ticket: { summary: ticketId === 19 ? OTHER : ticketSummary() } }));
    api.customers.contacts.mockImplementation(async (customerId) => ({
      contacts: customerId === CUSTOMER.id
        ? [{ id: 61, customerId: CUSTOMER.id, firstName: 'สมศรี', lastName: 'ลูกค้าแรก', phone: null, email: null }]
        : [],
    }));
    api.dealQuotations.create.mockResolvedValue({ quotation: { id: 99 } });
    renderEditor('/quotations/new', { idRoute: 'probe' });
    fireEvent.focus(await screen.findByRole('combobox', { name: /ดีล/ }));
    fireEvent.mouseDown(await screen.findByRole('option', { name: /DL-2026-0018/ }));
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/quotations/new?ticket=18'));
    await waitFor(() => expect(api.tickets.get).toHaveBeenCalledWith(18));

    const trigger = await screen.findByLabelText(/^ผู้ติดต่อ/);
    await waitFor(() => expect(trigger.disabled).toBe(false));
    fireEvent.click(trigger);
    fireEvent.click(await screen.findByRole('option', { name: /สมศรี/ }));
    expect(screen.getByText('สมศรี ลูกค้าแรก')).not.toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'เปลี่ยนดีล' }));
    fireEvent.focus(await screen.findByRole('combobox', { name: /ดีล/ }));
    fireEvent.mouseDown(await screen.findByRole('option', { name: /DL-2026-0019/ }));
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/quotations/new?ticket=19'));
    // Settled on deal 19 (its stage preselects ผู้รับ) before looking, so a loading frame cannot pass for it.
    await waitFor(() => expect(screen.getByRole('radio', { name: 'เจ้าของโครงการ' }).getAttribute('aria-checked')).toBe('true'));
    await waitFor(() => expect(api.customers.contacts).toHaveBeenCalledWith(9));
    expect(screen.queryByText('สมศรี ลูกค้าแรก')).toBeNull();
    expect(screen.getByLabelText(/^ผู้ติดต่อ/).textContent).toContain('- เลือกผู้ติดต่อ -');

    await fillCompleteQuotationItem();
    await waitFor(() => expect(saveButton().disabled).toBe(false));
    fireEvent.click(saveButton());
    await waitFor(() => expect(api.dealQuotations.create).toHaveBeenCalledTimes(1));
    expect(api.dealQuotations.create).toHaveBeenCalledWith(19, expect.objectContaining({ contactId: null }));
  }, 15000);

  it('สร้างดีลใหม่ brings back the customer/project card; ผู้รับใบเสนอราคา is required there too', async () => {
    renderEditor('/quotations/new');
    fireEvent.click(await screen.findByRole('button', { name: 'สร้างดีลใหม่' }));
    expect(await screen.findByLabelText(/^ลูกค้า/)).not.toBeNull();
    expect(recipientGroup()).not.toBeNull();
    expect(saveButton().title).toContain('ต้องเลือกผู้รับใบเสนอราคา');
  });

  it('inline create sends the chosen recipient on dealQuotations.create', async () => {
    api.customers.search.mockResolvedValue({ customers: [{ id: 1, name: 'บริษัท ก้าวหน้า จำกัด', taxId: null }] });
    api.customers.projects.mockResolvedValue({ projects: [{ id: 1, customerId: 1, name: 'โครงการ B1' }] });
    api.tickets.create.mockResolvedValue({ ticket: { summary: { id: 42, createdById: 6 } } });
    api.dealQuotations.create.mockResolvedValue({ quotation: { id: 99 } });
    renderEditor('/quotations/new', { idRoute: 'probe' });

    fireEvent.click(await screen.findByRole('button', { name: 'สร้างดีลใหม่' }));
    fireEvent.change(await screen.findByLabelText(/^ลูกค้า/), { target: { value: 'ก้าวหน้า' } });
    fireEvent.mouseDown(await screen.findByRole('option', { name: /ก้าวหน้า/ }));
    await waitFor(() => expect(api.customers.projects).toHaveBeenCalledWith(1));
    fireEvent.focus(await screen.findByLabelText(/^โครงการ/));
    fireEvent.mouseDown(await screen.findByRole('option', { name: /โครงการ B1/ }));
    // A NEW deal needs a real ช่องทางรับงาน too.
    fireEvent.click(screen.getByRole('button', { name: 'ผู้ออกแบบนำดีล' }));
    fireEvent.click(screen.getByRole('radio', { name: 'ผู้ซื้อ / ผู้รับเหมา' }));
    await fillCompleteQuotationItem();

    await waitFor(() => expect(saveButton().disabled).toBe(false));
    fireEvent.click(saveButton());

    await waitFor(() => expect(api.dealQuotations.create).toHaveBeenCalledWith(42, expect.objectContaining({ recipientType: 'BUYER' })));
    expect(await screen.findByText('navigated-to-99')).not.toBeNull();
  }, 15000);
});

describe('/quotations/new?ticket= — recipient preselected from the deal stage (slice 2 §A / flow B)', () => {
  // Reading the deal's stage to PRESELECT is kept; promising a stage move is not (no "ดีลจะอยู่ที่ขั้น …"
  // helper line).
  it.each([
    ['QUOTE_DESIGN_SIDE', 'ผู้ออกแบบ'],
    ['QUOTE_OWNER', 'เจ้าของโครงการ'],
    ['QUOTE_BUYER', 'ผู้ซื้อ / ผู้รับเหมา'],
  ])('a deal at %s preselects %s', async (salesStage, radioName) => {
    api.tickets.get.mockResolvedValue({ ticket: { summary: ticketSummary({ salesStage }) } });
    renderEditor('/quotations/new?ticket=18');
    await waitFor(() => expect(screen.getByRole('radio', { name: radioName }).getAttribute('aria-checked')).toBe('true'));
    expect(screen.queryByText(/ดีลจะอยู่ที่ขั้น/)).toBeNull();
  });

  it('a deal at any other stage leaves it empty — the rep must choose before saving', async () => {
    api.tickets.get.mockResolvedValue({ ticket: { summary: ticketSummary({ salesStage: 'PRESENTATION' }) } });
    renderEditor('/quotations/new?ticket=18');
    await waitFor(() => expect(recipientGroup()).not.toBeNull());
    await fillCompleteQuotationItem();
    expect(within(recipientGroup()).getAllByRole('radio').map((r) => r.getAttribute('aria-checked'))).toEqual(['false', 'false', 'false']);
    expect(saveButton().disabled).toBe(true);
    expect(saveButton().title).toContain('ต้องเลือกผู้รับใบเสนอราคา');
  }, 10000);

  it('the ?ticket= create carries the preselected recipient', async () => {
    api.tickets.get.mockResolvedValue({ ticket: { summary: ticketSummary({ salesStage: 'QUOTE_DESIGN_SIDE' }) } });
    api.dealQuotations.create.mockResolvedValue({ quotation: { id: 99 } });
    renderEditor('/quotations/new?ticket=18', { idRoute: 'probe' });
    await waitFor(() => expect(screen.getByRole('radio', { name: 'ผู้ออกแบบ' }).getAttribute('aria-checked')).toBe('true'));
    await fillCompleteQuotationItem();
    await waitFor(() => expect(saveButton().disabled).toBe(false));
    fireEvent.click(saveButton());
    await waitFor(() => expect(api.dealQuotations.create).toHaveBeenCalledWith(18, expect.objectContaining({ recipientType: 'DESIGNER' })));
  }, 10000);

  // เสนอราคา<next party> on the deal page lands here: ?recipient= wins over the stage, and N6's relaxation
  // (APPROVED quotation to an EARLIER party) leaves บันทึกร่าง enabled.
  it('?recipient=OWNER pre-fills ผู้รับ over the stage, and an APPROVED designer quotation does not block saving it', async () => {
    api.tickets.get.mockResolvedValue({
      ticket: { summary: ticketSummary({ salesStage: 'QUOTE_DESIGN_SIDE', liveDirectQuotation: { id: 77, number: 'QT-2026-0077-1', docStatus: 'APPROVED', recipientType: 'DESIGNER' } }) },
    });
    api.dealQuotations.create.mockResolvedValue({ quotation: { id: 99 } });
    renderEditor('/quotations/new?ticket=18&recipient=OWNER', { idRoute: 'probe' });
    await waitFor(() => expect(screen.getByRole('radio', { name: 'เจ้าของโครงการ' }).getAttribute('aria-checked')).toBe('true'));
    expect(screen.queryByTestId('deal-picker-live-quotation')).toBeNull();
    await fillCompleteQuotationItem();
    await waitFor(() => expect(saveButton().disabled).toBe(false));
    fireEvent.click(saveButton());
    await waitFor(() => expect(api.dealQuotations.create).toHaveBeenCalledWith(18, expect.objectContaining({ recipientType: 'OWNER' })));
  }, 10000);

  it('?recipient=OWNER on a deal past draft (order confirmed): the APPROVED designer quotation still blocks saving, WITH the reason', async () => {
    api.tickets.get.mockResolvedValue({
      ticket: { summary: ticketSummary({ status: 'quotation_issued', salesStage: 'QUOTE_DESIGN_SIDE', liveDirectQuotation: { id: 77, number: 'QT-2026-0077-1', docStatus: 'APPROVED', recipientType: 'DESIGNER' } }) },
    });
    renderEditor('/quotations/new?ticket=18&recipient=OWNER');
    await waitFor(() => expect(screen.getByRole('radio', { name: 'เจ้าของโครงการ' }).getAttribute('aria-checked')).toBe('true'));
    await fillCompleteQuotationItem();
    await waitFor(() => expect(saveButton().disabled).toBe(true));
    expect(saveButton().title).toContain('ดีลนี้มีใบเสนอราคาที่ใช้งานอยู่ (QT-2026-0077-1)');
  }, 10000);

  // The summary serves only the NEWEST live direct quotation; the server (DirectQuotationLocks) checks every live
  // row. Here the newest (#3, a revision) is an APPROVED designer quotation while an older OWNER one (#2) is still
  // PENDING_APPROVAL: BUYER is refused, naming #2 — so the editor must say so BEFORE the save.
  const LIVE_ROWS = [
    { id: 3, number: 'QT-2026-0003-1', origin: 'DEAL_DIRECT', ticketId: 18, docStatus: 'APPROVED', recipientType: 'DESIGNER' },
    { id: 2, number: 'QT-2026-0002-1', origin: 'DEAL_DIRECT', ticketId: 18, docStatus: 'PENDING_APPROVAL', recipientType: 'OWNER' },
    { id: 1, number: 'QT-2026-0001-1', origin: 'DEAL_DIRECT', ticketId: 18, docStatus: 'CANCELLED', recipientType: 'DESIGNER' },
  ];
  const newestLive = { id: 3, number: 'QT-2026-0003-1', docStatus: 'APPROVED', recipientType: 'DESIGNER' };

  it('?recipient=BUYER: an older live OWNER row blocks บันทึกร่าง WITH its number, though the newest live row (APPROVED designer) alone would allow', async () => {
    api.tickets.get.mockResolvedValue({ ticket: { summary: ticketSummary({ salesStage: 'QUOTE_DESIGN_SIDE', liveDirectQuotation: newestLive }) } });
    api.dealQuotations.listForTicket.mockResolvedValue({ items: LIVE_ROWS });
    renderEditor('/quotations/new?ticket=18&recipient=BUYER');
    await waitFor(() => expect(screen.getByRole('radio', { name: 'ผู้ซื้อ / ผู้รับเหมา' }).getAttribute('aria-checked')).toBe('true'));
    await fillCompleteQuotationItem();
    await waitFor(() => expect(api.dealQuotations.listForTicket).toHaveBeenCalledWith(18));
    await waitFor(() => expect(saveButton().disabled).toBe(true));
    expect(saveButton().title).toContain('ดีลนี้มีใบเสนอราคาที่ใช้งานอยู่ (QT-2026-0002-1)');
  }, 10000);

  it('?recipient=BUYER: only an APPROVED designer row (plus a cancelled one) -> บันทึกร่าง stays enabled and the create goes out', async () => {
    api.tickets.get.mockResolvedValue({ ticket: { summary: ticketSummary({ salesStage: 'QUOTE_DESIGN_SIDE', liveDirectQuotation: newestLive }) } });
    api.dealQuotations.listForTicket.mockResolvedValue({ items: [LIVE_ROWS[0], LIVE_ROWS[2]] });
    api.dealQuotations.create.mockResolvedValue({ quotation: { id: 99 } });
    renderEditor('/quotations/new?ticket=18&recipient=BUYER', { idRoute: 'probe' });
    await waitFor(() => expect(screen.getByRole('radio', { name: 'ผู้ซื้อ / ผู้รับเหมา' }).getAttribute('aria-checked')).toBe('true'));
    await fillCompleteQuotationItem();
    await waitFor(() => expect(api.dealQuotations.listForTicket).toHaveBeenCalledWith(18));
    await waitFor(() => expect(saveButton().disabled).toBe(false));
    fireEvent.click(saveButton());
    await waitFor(() => expect(api.dealQuotations.create).toHaveBeenCalledWith(18, expect.objectContaining({ recipientType: 'BUYER' })));
  }, 10000);

  it('?recipient=BUYER: a live PRICING_REQUEST-origin row is not a direct quotation (N6 ignores it), so บันทึกร่าง stays enabled', async () => {
    api.tickets.get.mockResolvedValue({ ticket: { summary: ticketSummary({ salesStage: 'QUOTE_DESIGN_SIDE', liveDirectQuotation: newestLive }) } });
    api.dealQuotations.listForTicket.mockResolvedValue({
      items: [LIVE_ROWS[0], { ...LIVE_ROWS[1], id: 4, number: 'QT-2026-0004-1', origin: 'PRICING_REQUEST' }],
    });
    renderEditor('/quotations/new?ticket=18&recipient=BUYER');
    await waitFor(() => expect(screen.getByRole('radio', { name: 'ผู้ซื้อ / ผู้รับเหมา' }).getAttribute('aria-checked')).toBe('true'));
    await fillCompleteQuotationItem();
    await waitFor(() => expect(api.dealQuotations.listForTicket).toHaveBeenCalledWith(18));
    await waitFor(() => expect(saveButton().disabled).toBe(false));
  }, 10000);

  it('N6: a deal with a live direct quotation disables บันทึกร่าง WITH the reason, beside the inline notice', async () => {
    api.tickets.get.mockResolvedValue({
      ticket: { summary: ticketSummary({ salesStage: 'QUOTE_OWNER', liveDirectQuotation: { id: 77, number: 'QT-2026-0077-1', docStatus: 'DRAFT', recipientType: 'OWNER' } }) },
    });
    renderEditor('/quotations/new?ticket=18');
    const notice = await screen.findByTestId('deal-picker-live-quotation');
    expect(within(notice).getByRole('link', { name: 'เปิดใบเสนอราคา' }).getAttribute('href')).toBe('/quotations/77');
    await fillCompleteQuotationItem();
    expect(saveButton().disabled).toBe(true);
    expect(saveButton().title).toContain('ดีลนี้มีใบเสนอราคาที่ใช้งานอยู่ (QT-2026-0077-1)');
  }, 10000);
});

describe('editor header strip — ดีล <code> · customer · stage · เปิดดีล (slice 2 §B)', () => {
  it('on the ?ticket= path: code, customer, the Thai stage badge, and เปิดดีล to the deal', async () => {
    api.tickets.get.mockResolvedValue({ ticket: { summary: ticketSummary({ salesStage: 'QUOTE_OWNER' }) } });
    renderEditor('/quotations/new?ticket=18');
    const strip = await screen.findByTestId('quotation-deal-strip');
    expect(strip.textContent).toContain('DL-2026-0018');
    expect(strip.textContent).toContain(CUSTOMER.name);
    expect(strip.textContent).toContain('เสนอราคาเจ้าของโครงการ');
    expect(within(strip).getByRole('link', { name: 'เปิดดีล' }).getAttribute('href')).toBe('/tickets/18');
  });

  it('on an existing DRAFT: the same strip, from the deal summary', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: draft() });
    api.tickets.get.mockResolvedValue({ ticket: { summary: ticketSummary({ salesStage: 'QUOTE_OWNER' }) } });
    renderEditor('/quotations/5');
    const strip = await screen.findByTestId('quotation-deal-strip');
    expect(strip.textContent).toContain('DL-2026-0018');
    expect(strip.textContent).toContain('เสนอราคาเจ้าของโครงการ');
  });

  it('prefers the DTO\'s own ticketCode/dealStage (slice 1) — a non-DRAFT quotation shows the strip without fetching the deal', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: draft({ docStatus: 'PENDING_APPROVAL', ticketCode: 'DL-2026-0018', dealStage: 'QUOTE_BUYER', readOnly: false }),
    });
    renderEditor('/quotations/5');
    const strip = await screen.findByTestId('quotation-deal-strip');
    expect(strip.textContent).toContain('DL-2026-0018');
    expect(strip.textContent).toContain('เสนอราคาผู้ซื้อ/ผู้รับเหมา');
    expect(within(strip).getByRole('link', { name: 'เปิดดีล' }).getAttribute('href')).toBe('/tickets/18');
    expect(api.tickets.get).not.toHaveBeenCalled();
  });

  // Regression: following เปิดดีล from the editor crashed the deal page. This page cached the bare
  // ticket SUMMARY under queryKeys.ticketDetail(id), the key where TicketDetailPage expects (and
  // renders) the whole TicketDto. The editor must never write there.
  it('never caches its deal summary under the bare ticketDetail key the deal page renders from', async () => {
    renderEditor('/quotations/new?ticket=18');
    await screen.findByTestId('quotation-deal-strip');
    expect(lastQueryClient.getQueryData(queryKeys.ticketDetail(18))).toBeUndefined();
  });

  it('is hidden while inline-creating — there is no deal yet', async () => {
    renderEditor('/quotations/new');
    await screen.findByRole('heading', { name: 'ดีล' });
    expect(screen.queryByTestId('quotation-deal-strip')).toBeNull();
  });

  it('เปิดดีล is hidden for a role that cannot open the deal page (quotation grant-holder)', async () => {
    api.tickets.get.mockResolvedValue({ ticket: { summary: ticketSummary({ createdById: 6 }) } });
    renderEditor('/quotations/new?ticket=18', { user: qcGrantUser });
    const strip = await screen.findByTestId('quotation-deal-strip');
    expect(within(strip).queryByRole('link', { name: 'เปิดดีล' })).toBeNull();
  });
});

describe('recipient on an existing DRAFT (slice 2 §C)', () => {
  it('DEAL_DIRECT: the stored recipient is checked, editable, and a change is saved through update', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: draft({ recipientType: 'OWNER' }) });
    api.dealQuotations.update.mockImplementation(async () => ({ quotation: draft({ recipientType: 'BUYER' }) }));
    renderEditor('/quotations/5');
    await waitFor(() => expect(screen.getByRole('radio', { name: 'เจ้าของโครงการ' }).getAttribute('aria-checked')).toBe('true'));

    fireEvent.click(screen.getByRole('radio', { name: 'ผู้ซื้อ / ผู้รับเหมา' }));
    await waitFor(() => expect(saveButton().disabled).toBe(false));
    fireEvent.click(saveButton());
    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalled());
    const [, payload] = api.dealQuotations.update.mock.calls[0];
    expect(payload.recipientType).toBe('BUYER');
  });

  it('DEAL_DIRECT revision (parentQuotationId set): the recipient is locked, with the hint, and no radios', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: draft({ recipientType: 'OWNER', parentQuotationId: 4, revisionNo: 2 }) });
    renderEditor('/quotations/5');
    const locked = await screen.findByTestId('quotation-recipient-revision-locked');
    expect(within(locked).getByText('เจ้าของโครงการ')).toBeTruthy();
    expect(within(locked).getByText(REVISION_RECIPIENT_LOCKED_MESSAGE)).toBeTruthy();
    expect(screen.queryByRole('radio', { name: 'ผู้ซื้อ / ผู้รับเหมา' })).toBeNull();
  });

  it('PRICING_REQUEST: read-only "จากคำขอราคา", no radios, and the update never sends a recipientType', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: draft({ origin: 'PRICING_REQUEST', pricingRequestId: 3, pricingRequestCode: 'PR-2026-0003', recipientType: 'DESIGNER', recipientLabel: 'ผู้ออกแบบ' }),
    });
    api.dealQuotations.update.mockImplementation(async () => ({ quotation: draft({ origin: 'PRICING_REQUEST' }) }));
    renderEditor('/quotations/5');
    const readOnly = await screen.findByTestId('quotation-recipient-readonly');
    expect(readOnly.textContent).toContain('ผู้ออกแบบ');
    expect(readOnly.textContent).toContain('จากคำขอราคา');
    expect(screen.queryByRole('radiogroup', { name: 'ผู้รับใบเสนอราคา' })).toBeNull();

    fireEvent.change(screen.getByLabelText(/^โครงการ/), { target: { value: 'โครงการ A แก้' } });
    await waitFor(() => expect(saveButton().disabled).toBe(false));
    fireEvent.click(saveButton());
    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalled());
    const [, payload] = api.dealQuotations.update.mock.calls[0];
    expect('recipientType' in payload).toBe(false);
  });

  it('a non-DRAFT DEAL_DIRECT quotation shows no recipient control at all (the document view is read-only)', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: draft({ docStatus: 'PENDING_APPROVAL' }) });
    renderEditor('/quotations/5');
    await screen.findByText(/QT-2026-0005-1/);
    expect(screen.queryByRole('radiogroup', { name: 'ผู้รับใบเสนอราคา' })).toBeNull();
  });

  it('owner ruling 2026-11-07: an existing DRAFT on a buyer-direct deal keeps its stored OWNER checked, but ผู้ออกแบบ/เจ้าของ are disabled with the route sentence and ผู้ซื้อ stays pickable', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: draft({ recipientType: 'OWNER' }) });
    api.tickets.get.mockResolvedValue({ ticket: { summary: ticketSummary({ entryChannel: 'BUYER_DIRECT' }) } });
    renderEditor('/quotations/5');
    expect((await screen.findAllByText(routeSentence('ผู้ซื้อ/ผู้รับเหมาติดต่อโดยตรง', 4))).length).toBeGreaterThan(0);
    expect(screen.getByRole('radio', { name: 'เจ้าของโครงการ' }).getAttribute('aria-checked')).toBe('true');
    expect(['ผู้ออกแบบ', 'เจ้าของโครงการ', 'ผู้ซื้อ / ผู้รับเหมา'].map((name) => isDisabled(screen.getByRole('radio', { name }))))
      .toEqual([true, true, false]);
  });
});

// The deal page's sticky CTA and the worklist key on TicketSummaryDto.liveDirectQuotation, so every
// docStatus change of a direct quotation changes the DEAL's data too. The editor invalidated only
// ['dealQuotations'] after submit/approve/…, so the deal page kept showing "ส่งขออนุมัติใบเสนอราคา"
// after the rep had already submitted. Each such action must invalidate the deal caches
// (['tickets', …]) as well.
describe('a direct-quotation status change refreshes the deal (liveDirectQuotation, S2-B4)', () => {
  const ceoUser = { id: 1, name: 'CEO', role: 'ceo', employeeId: null };

  function seedDealCache() {
    lastQueryClient.setQueryData(queryKeys.ticketDetail(18), { summary: ticketSummary() });
    lastQueryClient.setQueryData(['tickets', 'list', {}], { tickets: [ticketSummary()] });
  }
  const dealCachesInvalidated = () => [queryKeys.ticketDetail(18), ['tickets', 'list', {}]]
    .map((key) => lastQueryClient.getQueryState(key)?.isInvalidated);

  it('ส่งขออนุมัติ', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: draft() });
    api.dealQuotations.update.mockImplementation(async () => ({ quotation: draft() }));
    api.dealQuotations.submit.mockResolvedValue({ quotation: draft({ docStatus: 'PENDING_APPROVAL' }) });
    renderEditor('/quotations/5');
    await screen.findByRole('button', { name: 'ส่งขออนุมัติ' });
    seedDealCache();
    fireEvent.click(screen.getByRole('button', { name: 'ส่งขออนุมัติ' }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'ส่งขออนุมัติ' }));
    await waitFor(() => expect(api.dealQuotations.submit).toHaveBeenCalled());
    await waitFor(() => expect(dealCachesInvalidated()).toEqual([true, true]));
  });

  it('อนุมัติ', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: draft({ docStatus: 'PENDING_APPROVAL' }) });
    api.dealQuotations.approve.mockResolvedValue({ quotation: draft({ docStatus: 'APPROVED' }) });
    renderEditor('/quotations/5', { user: ceoUser });
    await screen.findByRole('button', { name: 'อนุมัติ' });
    seedDealCache();
    fireEvent.click(screen.getByRole('button', { name: 'อนุมัติ' }));
    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันอนุมัติ' }));
    await waitFor(() => expect(api.dealQuotations.approve).toHaveBeenCalled());
    await waitFor(() => expect(dealCachesInvalidated()).toEqual([true, true]));
  });

  it('ยกเลิกร่าง', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: draft() });
    api.dealQuotations.cancel.mockResolvedValue({ quotation: draft({ docStatus: 'CANCELLED' }) });
    renderEditor('/quotations/5');
    await screen.findByRole('button', { name: 'ยกเลิกร่าง' });
    seedDealCache();
    fireEvent.click(screen.getByRole('button', { name: 'ยกเลิกร่าง' }));
    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันยกเลิก' }));
    await waitFor(() => expect(api.dealQuotations.cancel).toHaveBeenCalled());
    await waitFor(() => expect(dealCachesInvalidated()).toEqual([true, true]));
  });
});

// ONE PRICING ROUTE PER DEAL, both directions: a deal with a LIVE pricing request (any status but
// CANCELLED / SUPERSEDED) may not start a direct quotation. Picking one shows the same
// inline-notice pattern as a live direct quotation, and บันทึกร่าง is disabled with the reason
// (DESIGN.md §14).
describe('/quotations/new?ticket= — a deal with a live pricing request (one pricing route per deal)', () => {
  const LIVE_PR_MESSAGE = 'ดีลนี้มีคำขอราคาที่ยังดำเนินการอยู่ — ใช้ใบเสนอราคาจากคำขอราคา หรือยกเลิกคำขอราคาก่อน';
  const pr = (status) => ({ id: 501, requestCode: 'PCR-2026-0501', ticketId: 18, status });

  it.each([['DRAFT'], ['SUBMITTED'], ['QUOTATION_ACCEPTED']])('%s: inline notice + บันทึกร่าง disabled with the reason', async (status) => {
    api.tickets.get.mockResolvedValue({ ticket: { summary: ticketSummary({ salesStage: 'QUOTE_DESIGN_SIDE' }) } });
    api.pricingRequests.listForTicket.mockResolvedValue({ items: [pr(status)] });
    renderEditor('/quotations/new?ticket=18');
    const notice = await screen.findByTestId('deal-picker-live-pricing-request');
    expect(notice.getAttribute('role')).toBe('status');
    expect(notice.textContent).toContain(LIVE_PR_MESSAGE);
    await fillCompleteQuotationItem();
    expect(saveButton().disabled).toBe(true);
    expect(saveButton().title).toContain(LIVE_PR_MESSAGE);
  }, 10000);

  it.each([['CANCELLED'], ['SUPERSEDED']])('wrong-way-round: a %s pricing request does not block', async (status) => {
    api.tickets.get.mockResolvedValue({ ticket: { summary: ticketSummary({ salesStage: 'QUOTE_DESIGN_SIDE' }) } });
    api.pricingRequests.listForTicket.mockResolvedValue({ items: [pr(status)] });
    renderEditor('/quotations/new?ticket=18');
    await waitFor(() => expect(api.pricingRequests.listForTicket).toHaveBeenCalledWith(18));
    await screen.findByTestId('quotation-deal-strip');
    await fillCompleteQuotationItem();
    await waitFor(() => expect(saveButton().disabled).toBe(false));
    expect(screen.queryByTestId('deal-picker-live-pricing-request')).toBeNull();
  }, 10000);
});

// Owner ruling 2026-11-07: DealRoute.refusalMessage's sentence (the server's 409), shown beside a disabled off-route pill.
const routeSentence = (channelPhrase, no) => `ดีลนี้เป็น${channelPhrase} — ขั้นที่ ${no} ไม่อยู่ในเส้นทางของดีลนี้ — แก้ช่องทางดีลก่อน`;
const isDisabled = (el) => el.disabled || el.getAttribute('aria-disabled') === 'true';
const checkedRecipients = () => within(recipientGroup()).getAllByRole('radio').map((r) => r.getAttribute('aria-checked'));

// On the NEW-deal card the channel pre-fills the recipient
// (DESIGNER_LED→ผู้ออกแบบ, OWNER_DIRECT→เจ้าของโครงการ, BUYER_DIRECT→ผู้ซื้อ / ผู้รับเหมา), editable. A later
// channel change re-fills it ONLY while the rep hasn't picked a recipient themselves. Two fields stay.
describe('new-deal card — ช่องทางรับงาน pre-fills ผู้รับใบเสนอราคา (owner ruling #2)', () => {
  async function openCreateCard() {
    renderEditor('/quotations/new');
    fireEvent.click(await screen.findByRole('button', { name: 'สร้างดีลใหม่' }));
    await screen.findByLabelText(/^ลูกค้า/);
  }

  it.each([
    ['ผู้ออกแบบนำดีล', ['true', 'false', 'false']],
    ['เจ้าของติดต่อโดยตรง', ['false', 'true', 'false']],
    ['ผู้ซื้อ/ผู้รับเหมาติดต่อโดยตรง', ['false', 'false', 'true']],
  ])('choosing %s pre-fills the matching recipient', async (channel, expected) => {
    await openCreateCard();
    expect(checkedRecipients()).toEqual(['false', 'false', 'false']);
    fireEvent.click(screen.getByRole('button', { name: channel }));
    await waitFor(() => expect(checkedRecipients()).toEqual(expected));
    // Two separate fields remain: the channel keeps its own pressed state.
    expect(screen.getByRole('button', { name: channel }).getAttribute('aria-pressed')).toBe('true');
  });

  it('while the rep has not picked a recipient, changing the channel re-fills it', async () => {
    await openCreateCard();
    fireEvent.click(screen.getByRole('button', { name: 'ผู้ออกแบบนำดีล' }));
    await waitFor(() => expect(checkedRecipients()).toEqual(['true', 'false', 'false']));
    fireEvent.click(screen.getByRole('button', { name: 'เจ้าของติดต่อโดยตรง' }));
    await waitFor(() => expect(checkedRecipients()).toEqual(['false', 'true', 'false']));
  });

  it('doesn\'t overwrite a deliberate pick: a later channel change leaves the rep\'s recipient — now off-route, so the route sentence says so', async () => {
    await openCreateCard();
    fireEvent.click(screen.getByRole('button', { name: 'ผู้ออกแบบนำดีล' }));
    await waitFor(() => expect(checkedRecipients()).toEqual(['true', 'false', 'false']));
    // The rep deliberately picks เจ้าของโครงการ…
    fireEvent.click(screen.getByRole('radio', { name: 'เจ้าของโครงการ' }));
    // …then corrects the channel to buyer-direct: the pick stands, but the owner is now off the route.
    fireEvent.click(screen.getByRole('button', { name: 'ผู้ซื้อ/ผู้รับเหมาติดต่อโดยตรง' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'ผู้ซื้อ/ผู้รับเหมาติดต่อโดยตรง' }).getAttribute('aria-pressed')).toBe('true'));
    expect(checkedRecipients()).toEqual(['false', 'true', 'false']);
    expect((await screen.findAllByText(routeSentence('ผู้ซื้อ/ผู้รับเหมาติดต่อโดยตรง', 5))).length).toBeGreaterThan(0);
    expect(isDisabled(screen.getByRole('radio', { name: 'เจ้าของโครงการ' }))).toBe(true);
    // …and the stale pick BLOCKS the save, with the same sentence as a checklist entry (not only the pill's hint).
    expect(within(screen.getByTestId('checklist-blocking')).getByText(routeSentence('ผู้ซื้อ/ผู้รับเหมาติดต่อโดยตรง', 5))).toBeTruthy();
  });
});

describe('/quotations/new?ticket= — channel fallback + off-route note (owner rulings #1, #2)', () => {
  it.each([
    ['OWNER_DIRECT', 'เจ้าของโครงการ'],
    ['BUYER_DIRECT', 'ผู้ซื้อ / ผู้รับเหมา'],
    ['DESIGNER_LED', 'ผู้ออกแบบ'],
  ])('a deal at a non-quote stage falls back to its channel: %s preselects %s', async (entryChannel, radioName) => {
    api.tickets.get.mockResolvedValue({ ticket: { summary: ticketSummary({ salesStage: 'PRESENTATION', entryChannel }) } });
    renderEditor('/quotations/new?ticket=18');
    await waitFor(() => expect(screen.getByRole('radio', { name: radioName }).getAttribute('aria-checked')).toBe('true'));
  });

  it('the deal\'s stage still wins over its channel when it is a quote stage', async () => {
    api.tickets.get.mockResolvedValue({ ticket: { summary: ticketSummary({ salesStage: 'QUOTE_BUYER', entryChannel: 'DESIGNER_LED' }) } });
    renderEditor('/quotations/new?ticket=18');
    await waitFor(() => expect(screen.getByRole('radio', { name: 'ผู้ซื้อ / ผู้รับเหมา' }).getAttribute('aria-checked')).toBe('true'));
  });

  it('an unstated channel at a non-quote stage still leaves the choice empty', async () => {
    api.tickets.get.mockResolvedValue({ ticket: { summary: ticketSummary({ salesStage: 'PRESENTATION', entryChannel: 'UNSPECIFIED' }) } });
    renderEditor('/quotations/new?ticket=18');
    await screen.findByTestId('quotation-deal-strip');
    await waitFor(() => expect(api.tickets.get).toHaveBeenCalled());
    expect(checkedRecipients()).toEqual(['false', 'false', 'false']);
  });

  it('an owner-direct deal: the ผู้ออกแบบ pill is disabled with the route sentence and cannot be picked (no dialog)', async () => {
    api.tickets.get.mockResolvedValue({ ticket: { summary: ticketSummary({ salesStage: 'PRESENTATION', entryChannel: 'OWNER_DIRECT' }) } });
    renderEditor('/quotations/new?ticket=18');
    await waitFor(() => expect(screen.getByRole('radio', { name: 'เจ้าของโครงการ' }).getAttribute('aria-checked')).toBe('true'));
    expect((await screen.findAllByText(routeSentence('เจ้าของติดต่อโดยตรง', 4))).length).toBeGreaterThan(0);
    expect(isDisabled(screen.getByRole('radio', { name: 'ผู้ออกแบบ' }))).toBe(true);
    expect(isDisabled(screen.getByRole('radio', { name: 'เจ้าของโครงการ' }))).toBe(false);
    expect(isDisabled(screen.getByRole('radio', { name: 'ผู้ซื้อ / ผู้รับเหมา' }))).toBe(false);
    fireEvent.click(screen.getByRole('radio', { name: 'ผู้ออกแบบ' }));
    expect(screen.getByRole('radio', { name: 'ผู้ออกแบบ' }).getAttribute('aria-checked')).toBe('false');
    expect(screen.getByRole('radio', { name: 'เจ้าของโครงการ' }).getAttribute('aria-checked')).toBe('true');
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  // The pre-select never lands on an off-route party: `?recipient=` and the deal's quote stage are both hints, and an
  // off-route one falls through to the next hint (stage -> channel), which is on-route by construction.
  it('?recipient=DESIGNER on an owner-direct deal is not pre-selected; the channel default (เจ้าของโครงการ) is', async () => {
    api.tickets.get.mockResolvedValue({ ticket: { summary: ticketSummary({ salesStage: 'PRESENTATION', entryChannel: 'OWNER_DIRECT' }) } });
    renderEditor('/quotations/new?ticket=18&recipient=DESIGNER');
    await waitFor(() => expect(screen.getByRole('radio', { name: 'เจ้าของโครงการ' }).getAttribute('aria-checked')).toBe('true'));
    expect(screen.getByRole('radio', { name: 'ผู้ออกแบบ' }).getAttribute('aria-checked')).toBe('false');
  });

  // The route arrives on its own request: a pre-select made before it lands would take ?recipient=DESIGNER at
  // face value and never be revisited.
  it('?recipient=DESIGNER is still not pre-selected when the route arrives AFTER the deal', async () => {
    const { DEAL_STAGE_CATALOG } = await import('../../data/dealStageCatalog.js');
    let release;
    api.meta.dealStages.mockImplementationOnce(() => new Promise((resolve) => { release = () => resolve(DEAL_STAGE_CATALOG); }));
    api.tickets.get.mockResolvedValue({ ticket: { summary: ticketSummary({ salesStage: 'PRESENTATION', entryChannel: 'OWNER_DIRECT' }) } });
    renderEditor('/quotations/new?ticket=18&recipient=DESIGNER');
    await screen.findByTestId('quotation-deal-strip');
    await waitFor(() => expect(api.tickets.get).toHaveBeenCalled());
    await waitFor(() => expect(release).toBeTypeOf('function'));
    release();
    await waitFor(() => expect(screen.getByRole('radio', { name: 'เจ้าของโครงการ' }).getAttribute('aria-checked')).toBe('true'));
    expect(screen.getByRole('radio', { name: 'ผู้ออกแบบ' }).getAttribute('aria-checked')).toBe('false');
  });

  it('an owner-direct deal sitting on the (off-route) designer quote stage still pre-selects an on-route party', async () => {
    api.tickets.get.mockResolvedValue({ ticket: { summary: ticketSummary({ salesStage: 'QUOTE_DESIGN_SIDE', entryChannel: 'OWNER_DIRECT' }) } });
    renderEditor('/quotations/new?ticket=18');
    await waitFor(() => expect(screen.getByRole('radio', { name: 'เจ้าของโครงการ' }).getAttribute('aria-checked')).toBe('true'));
    expect(screen.getByRole('radio', { name: 'ผู้ออกแบบ' }).getAttribute('aria-checked')).toBe('false');
  });

  it('?recipient=OWNER on a buyer-direct deal falls through to the buyer', async () => {
    api.tickets.get.mockResolvedValue({ ticket: { summary: ticketSummary({ salesStage: 'PRESENTATION', entryChannel: 'BUYER_DIRECT' }) } });
    renderEditor('/quotations/new?ticket=18&recipient=OWNER');
    await waitFor(() => expect(screen.getByRole('radio', { name: 'ผู้ซื้อ / ผู้รับเหมา' }).getAttribute('aria-checked')).toBe('true'));
    expect(screen.getByRole('radio', { name: 'เจ้าของโครงการ' }).getAttribute('aria-checked')).toBe('false');
  });

  it('wrong-way-round: an UNSPECIFIED-channel deal honours ?recipient=DESIGNER (no route, nothing off it)', async () => {
    api.tickets.get.mockResolvedValue({ ticket: { summary: ticketSummary({ salesStage: 'PRESENTATION', entryChannel: 'UNSPECIFIED' }) } });
    renderEditor('/quotations/new?ticket=18&recipient=DESIGNER');
    await waitFor(() => expect(screen.getByRole('radio', { name: 'ผู้ออกแบบ' }).getAttribute('aria-checked')).toBe('true'));
    expect(isDisabled(screen.getByRole('radio', { name: 'ผู้ออกแบบ' }))).toBe(false);
  });

  // The server only refuses a CHANGE to an off-route party: a draft addressed before the channel was corrected keeps
  // saving with its stored recipient, so the editor must not lock it out. Its stored pill stays checked.
  it('an existing DRAFT addressed to a now-off-route party still saves with that stored recipient', async () => {
    api.tickets.get.mockResolvedValue({ ticket: { summary: ticketSummary({ salesStage: 'QUOTE_DESIGN_SIDE', entryChannel: 'OWNER_DIRECT' }) } });
    api.dealQuotations.get.mockResolvedValue({ quotation: draft({ recipientType: 'DESIGNER', recipientLabel: 'ผู้ออกแบบ' }) });
    api.dealQuotations.update.mockResolvedValue({ quotation: draft({ recipientType: 'DESIGNER', recipientLabel: 'ผู้ออกแบบ' }) });
    renderEditor('/quotations/5');
    await waitFor(() => expect(screen.getByRole('radio', { name: 'ผู้ออกแบบ' }).getAttribute('aria-checked')).toBe('true'));
    fireEvent.change(screen.getByLabelText(/^หมายเหตุเพิ่มเติม/), { target: { value: 'แก้ไข' } });
    await waitFor(() => expect(saveButton().disabled).toBe(false));
    fireEvent.click(saveButton());
    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalled());
    expect(api.dealQuotations.update.mock.calls[0][1].recipientType).toBe('DESIGNER');
  }, 10000);
});
