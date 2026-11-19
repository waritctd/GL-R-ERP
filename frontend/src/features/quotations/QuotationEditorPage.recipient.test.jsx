import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation, useParams } from 'react-router-dom';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QuotationEditorPage } from './QuotationEditorPage.jsx';
import { api } from '../../api/index.js';

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
        get: vi.fn(), create: vi.fn(), update: vi.fn(), calculateLine: vi.fn(),
        // The deal's quotation rows (the deal page's own list query) — N6's pre-check reads every live one.
        listForTicket: vi.fn(),
        displayNameOptions: vi.fn().mockResolvedValue({ items: [] }),
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

const salesUser = { id: 6, name: 'คุณสมหมาย ขายดี', role: 'sales', employeeId: null };

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
});

// ONE PRICING ROUTE PER DEAL, both directions: a deal with a LIVE pricing request (any status but
// CANCELLED / SUPERSEDED) may not start a direct quotation. Picking one shows the same
// inline-notice pattern as a live direct quotation, and บันทึกร่าง is disabled with the reason
// (DESIGN.md §14).
describe('/quotations/new?ticket= — a deal with a live pricing request (one pricing route per deal)', () => {
  const LIVE_PR_MESSAGE = 'งานนี้มีคำขอราคาที่ยังดำเนินการอยู่ — ใช้ใบเสนอราคาจากคำขอราคา หรือยกเลิกคำขอราคาก่อน';
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
