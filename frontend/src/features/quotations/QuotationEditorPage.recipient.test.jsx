import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation, useParams } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QuotationEditorPage } from './QuotationEditorPage.jsx';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';

globalThis.React = React;

// Slice 2 — flow A (SLICE-2-FLOW-A.md §A-§C): the quotation is linked to its deal from the first
// screen. Step 1 is "ดีล" (pick an existing deal, or create one), ผู้รับใบเสนอราคา* is required for a
// DEAL_DIRECT quotation and travels in the create/update payload (persisted only — owner ruling
// 2026-09-30 took S2-B2's stage move out of this slice), the editor carries a deal header strip, and
// a PRICING_REQUEST row shows its recipient read-only. A separate file because
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
      dealQuotations: {
        get: vi.fn(), create: vi.fn(), update: vi.fn(), calculateLine: vi.fn(), submit: vi.fn(),
        approve: vi.fn(), reject: vi.fn(), createRevision: vi.fn(), createReorder: vi.fn(), cancel: vi.fn(),
        downloadPdf: vi.fn(), downloadXlsx: vi.fn(), promoteToDeal: vi.fn(),
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
  fireEvent.change(screen.getByLabelText(/^ราคา\/หน่วย/), { target: { value: '850' } });
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

  it('picking a deal turns the page into the ?ticket= path (no second code path)', async () => {
    renderEditor('/quotations/new');
    fireEvent.focus(await screen.findByRole('combobox', { name: /ดีล/ }));
    fireEvent.mouseDown(await screen.findByRole('option', { name: /DL-2026-0018/ }));
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('/quotations/new?ticket=18'));
    await waitFor(() => expect(api.tickets.get).toHaveBeenCalledWith(18));
  });

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
    // #1085: a NEW deal needs a real ช่องทางรับงาน too.
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
  // Reading the deal's stage to PRESELECT is kept; promising a stage move is not (owner ruling
  // 2026-09-30 dropped the "ดีลจะอยู่ที่ขั้น …" helper line with S2-B2).
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

  it('N6: a deal with a live direct quotation disables บันทึกร่าง WITH the reason, beside the inline notice', async () => {
    api.tickets.get.mockResolvedValue({
      ticket: { summary: ticketSummary({ salesStage: 'QUOTE_OWNER', liveDirectQuotation: { id: 77, number: 'QT-2026-0077-1', docStatus: 'DRAFT', recipientType: 'OWNER' } }) },
    });
    renderEditor('/quotations/new?ticket=18');
    const notice = await screen.findByTestId('deal-picker-live-quotation');
    expect(within(notice).getByRole('link', { name: 'เปิดใบเสนอราคา' }).getAttribute('href')).toBe('/quotations/77');
    await fillCompleteQuotationItem();
    expect(saveButton().disabled).toBe(true);
    expect(saveButton().title).toContain('ดีลนี้มีใบเสนอราคาตรงที่ใช้งานอยู่ (QT-2026-0077-1)');
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

  // Regression (found in the browser, slice 2): following เปิดดีล from the editor crashed the deal
  // page — this page cached the bare ticket SUMMARY under queryKeys.ticketDetail(id), the key where
  // TicketDetailPage expects (and renders) the whole TicketDto. The editor must never write there.
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

  it('owner ruling #1: an existing DRAFT on a buyer-direct deal with recipient OWNER shows the off-route note (and stays editable)', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: draft({ recipientType: 'OWNER' }) });
    api.tickets.get.mockResolvedValue({ ticket: { summary: ticketSummary({ entryChannel: 'BUYER_DIRECT' }) } });
    renderEditor('/quotations/5');
    expect(await screen.findByText(OFF_ROUTE_NOTE)).not.toBeNull();
    expect(screen.getByRole('radio', { name: 'ผู้ออกแบบ' }).disabled).toBe(false);
  });
});

// Found in the browser while capturing the slice-2 screenshots: the deal page's sticky CTA and the
// worklist key on TicketSummaryDto.liveDirectQuotation (S2-B4), so every docStatus change of a
// direct quotation changes the DEAL's data too. The editor invalidated only ['dealQuotations'] after
// submit/approve/…, so the deal page kept showing "ส่งขออนุมัติใบเสนอราคา" after the rep had already
// submitted. Each such action must invalidate the deal caches (['tickets', …]) as well.
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

const OFF_ROUTE_NOTE = 'ผู้รับนี้ไม่อยู่ในเส้นทางของดีล — ขั้นของดีลจะไม่ขยับ';
const checkedRecipients = () => within(recipientGroup()).getAllByRole('radio').map((r) => r.getAttribute('aria-checked'));

// Owner ruling 2026-09-30 #2 — on the NEW-deal card the channel pre-fills the recipient
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

  it('doesn\'t overwrite a deliberate pick: a later channel change leaves the rep\'s recipient (and the off-route note says so)', async () => {
    await openCreateCard();
    fireEvent.click(screen.getByRole('button', { name: 'ผู้ออกแบบนำดีล' }));
    await waitFor(() => expect(checkedRecipients()).toEqual(['true', 'false', 'false']));
    // The rep deliberately picks เจ้าของโครงการ…
    fireEvent.click(screen.getByRole('radio', { name: 'เจ้าของโครงการ' }));
    // …then corrects the channel to buyer-direct: the pick stands.
    fireEvent.click(screen.getByRole('button', { name: 'ผู้ซื้อ/ผู้รับเหมาติดต่อโดยตรง' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'ผู้ซื้อ/ผู้รับเหมาติดต่อโดยตรง' }).getAttribute('aria-pressed')).toBe('true'));
    expect(checkedRecipients()).toEqual(['false', 'true', 'false']);
    // OWNER's stage is off the buyer-direct route: allowed, with the inline note.
    expect(await screen.findByText(OFF_ROUTE_NOTE)).not.toBeNull();
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

  it('picking ผู้ออกแบบ on an owner-direct deal is allowed, with the inline note (no block, no dialog)', async () => {
    api.tickets.get.mockResolvedValue({ ticket: { summary: ticketSummary({ salesStage: 'PRESENTATION', entryChannel: 'OWNER_DIRECT' }) } });
    renderEditor('/quotations/new?ticket=18');
    await waitFor(() => expect(screen.getByRole('radio', { name: 'เจ้าของโครงการ' }).getAttribute('aria-checked')).toBe('true'));
    expect(screen.queryByText(OFF_ROUTE_NOTE)).toBeNull();
    fireEvent.click(screen.getByRole('radio', { name: 'ผู้ออกแบบ' }));
    expect(screen.getByRole('radio', { name: 'ผู้ออกแบบ' }).getAttribute('aria-checked')).toBe('true');
    expect(await screen.findByText(OFF_ROUTE_NOTE)).not.toBeNull();
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});
