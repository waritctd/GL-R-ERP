import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QuotationEditorPage } from './QuotationEditorPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// GLA-136 → slice 1/2 (IA §7/§8, decision D3): "ยืนยันคำสั่งซื้อ" on an APPROVED direct quotation
// (was "สร้างดีลจากใบเสนอราคา"). The gate here is DISPLAY-only (quotationMeta.
// canPromoteDealQuotationToDeal); who may actually confirm is enforced and proven server-side in
// DealQuotationPromoteIntegrationTest / DealQuotationConfirmOrderIntegrationTest, never here.
//
// Whether the order is still confirmable is the deal's `status === 'draft'` — the backend's own
// precondition (DealQuotationService#confirmOrderFromDirectQuotation 409s otherwise; confirming
// flips it to 'quotation_issued'). `quotationOnly` is provenance only since #1086 and must NOT
// decide it: every deal created on the deal page (flow B) carries quotationOnly=false.
vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: { get: vi.fn(), create: vi.fn() },
      dealQuotations: {
        get: vi.fn(), create: vi.fn(), update: vi.fn(), calculateLine: vi.fn(), submit: vi.fn(),
        approve: vi.fn(), reject: vi.fn(), createRevision: vi.fn(), createReorder: vi.fn(),
        cancel: vi.fn(), downloadPdf: vi.fn(), downloadXlsx: vi.fn(), promoteToDeal: vi.fn(),
        displayNameOptions: vi.fn().mockResolvedValue({ items: [] }),
      },
      catalog: { prices: vi.fn() },
      designers: { search: vi.fn(), getByCode: vi.fn().mockRejectedValue(new Error('not found')) },
      customers: {
        search: vi.fn(), create: vi.fn(), projects: vi.fn(), createProject: vi.fn(),
        contacts: vi.fn(), createContact: vi.fn(),
      },
    },
  };
});

const salesUser = { id: 6, name: 'คุณสมหมาย ขายดี', role: 'sales', employeeId: null };
const otherSalesUser = { id: 999, name: 'คุณอื่น ขายดี', role: 'sales', employeeId: null };
const salesManagerUser = { id: 11, name: 'ผึ้ง', role: 'sales_manager', employeeId: 11 };
const ceoUser = { id: 8, name: 'ราม', role: 'ceo', employeeId: 136 };
const importUser = { id: 7, name: 'คุณนำเข้า พานิช', role: 'import', employeeId: null };

function quotation(overrides = {}) {
  return {
    id: 5, number: 'QT-2026-0005-1', ticketId: 18, docStatus: 'APPROVED', origin: 'DEAL_DIRECT',
    salesRepId: 6, salesRepName: 'คุณสมหมาย ขายดี', salesRepPhone: null,
    createdByName: 'คุณสมหมาย ขายดี', approvalNote: null,
    customerName: 'บริษัท แฟชั่นไอส์แลนด์ จำกัด', projectName: null,
    deptCode: null, unitCode: null, offerDate: '2026-09-01',
    depositPercent: 30, remainderMode: 'CREDIT', creditDays: 30, validityDays: 30, validityDate: null,
    customerNotes: null, subtotalAmount: 1000, vatAmount: 70, grandTotal: 1070, currency: 'THB',
    approverHasSignature: false, items: [], createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-01T00:00:00Z',
    ...overrides,
  };
}

function ticketSummary(overrides = {}) {
  return { ticket: { summary: { id: 18, createdById: 6, quotationOnly: true, status: 'draft', ...overrides } } };
}

function DealRoute() {
  const { id } = useParams();
  return <p>deal page {id}</p>;
}

function renderEditor(user = salesUser, showToast = vi.fn()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/quotations/5']}>
        <Routes>
          <Route path="/quotations/:id" element={<QuotationEditorPage user={user} showToast={showToast} />} />
          <Route path="/tickets/:id" element={<DealRoute />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { showToast, queryClient };
}

const PROMOTE = 'ยืนยันคำสั่งซื้อ';

describe('QuotationEditorPage — ยืนยันคำสั่งซื้อ on a direct quotation (GLA-136, IA §7/§8)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.tickets.get.mockResolvedValue(ticketSummary());
  });

  it('(c) is offered to the owning rep on an APPROVED direct quotation whose quotation-first deal is still draft', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: quotation() });
    renderEditor();
    expect(await screen.findByRole('button', { name: PROMOTE })).not.toBeNull();
    expect(screen.queryByRole('link', { name: /ไปที่ดีล/ })).toBeNull();
  });

  it.each([
    ['DRAFT'], ['PENDING_APPROVAL'], ['CANCELLED'], ['SUPERSEDED'],
  ])('is NOT offered on a %s quotation', async (docStatus) => {
    api.dealQuotations.get.mockResolvedValue({ quotation: quotation({ docStatus }) });
    renderEditor();
    await screen.findByText(/QT-2026-0005-1/);
    expect(screen.queryByRole('button', { name: PROMOTE })).toBeNull();
  });

  it('is NOT offered on a PRICING_REQUEST-origin quotation (that chain has its own confirm order)', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: quotation({ origin: 'PRICING_REQUEST', docStatus: 'APPROVED' }) });
    renderEditor();
    await screen.findByText(/QT-2026-0005-1/);
    expect(screen.queryByRole('button', { name: PROMOTE })).toBeNull();
  });

  it.each([
    ['a non-owning sales rep', otherSalesUser],
    ['ceo (approves, does not write)', ceoUser],
    ['import', importUser],
  ])('is NOT offered to %s', async (_label, user) => {
    api.dealQuotations.get.mockResolvedValue({ quotation: quotation() });
    renderEditor(user);
    await screen.findByText(/QT-2026-0005-1/);
    expect(screen.queryByRole('button', { name: PROMOTE })).toBeNull();
    expect(api.tickets.get).not.toHaveBeenCalled();
  });

  it('is offered to sales_manager on any rep\'s quotation', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: quotation() });
    renderEditor(salesManagerUser);
    expect(await screen.findByRole('button', { name: PROMOTE })).not.toBeNull();
  });

  it('(a) an ORDINARY deal (quotationOnly=false, created on the deal page) still at draft: the confirm is offered, not ไปที่ดีล', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: quotation() });
    api.tickets.get.mockResolvedValue(ticketSummary({ quotationOnly: false, status: 'draft' }));
    renderEditor();
    expect(await screen.findByRole('button', { name: PROMOTE })).not.toBeNull();
    expect(screen.queryByRole('link', { name: /ไปที่ดีล/ })).toBeNull();
  });

  it.each([[true], [false]])('(b) once the order is confirmed (status quotation_issued, quotationOnly=%s): "ไปที่ดีล", no confirm', async (quotationOnly) => {
    api.dealQuotations.get.mockResolvedValue({ quotation: quotation() });
    api.tickets.get.mockResolvedValue(ticketSummary({ quotationOnly, status: 'quotation_issued' }));
    renderEditor();
    const link = await screen.findByRole('link', { name: /ไปที่ดีล/ });
    expect(link.getAttribute('href')).toBe('/tickets/18');
    expect(screen.queryByRole('button', { name: PROMOTE })).toBeNull();
  });

  it('while the deal\'s status is unknown (a 403 on the deal read) the confirm stays offered — the server answers idempotently', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: quotation() });
    api.tickets.get.mockRejectedValue(Object.assign(new Error('ไม่มีสิทธิ์เข้าถึงรายการนี้'), { status: 403 }));
    renderEditor();
    expect(await screen.findByRole('button', { name: PROMOTE })).not.toBeNull();
    expect(screen.queryByRole('link', { name: /ไปที่ดีล/ })).toBeNull();
  });

  it('(d) confirms first (dialog titled ยืนยันคำสั่งซื้อ), then confirms the order, toasts "ยืนยันคำสั่งซื้อแล้ว" and navigates to the deal', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: quotation() });
    api.dealQuotations.promoteToDeal.mockResolvedValue({
      result: { ticketId: 18, ticket: { id: 18, quotationOnly: false }, quotation: quotation() },
    });
    const { showToast } = renderEditor();

    fireEvent.click(await screen.findByRole('button', { name: PROMOTE }));
    expect(api.dealQuotations.promoteToDeal).not.toHaveBeenCalled();
    const dialogEl = screen.getByRole('dialog');
    const dialog = within(dialogEl);
    expect(dialog.getByRole('heading', { name: 'ยืนยันคำสั่งซื้อ' })).not.toBeNull();
    expect(dialogEl.textContent).not.toMatch(/สร้างดีล/);
    expect(dialog.getByText(/ได้รับคำสั่งซื้อ/)).not.toBeNull();
    expect(dialog.getByText(/ย้อนกลับไม่ได้/)).not.toBeNull();

    fireEvent.click(dialog.getByRole('button', { name: PROMOTE }));
    await waitFor(() => expect(api.dealQuotations.promoteToDeal).toHaveBeenCalledWith('5'));
    expect(await screen.findByText('deal page 18')).not.toBeNull();
    expect(showToast).toHaveBeenCalledWith('success', 'ยืนยันคำสั่งซื้อแล้ว');
  });

  it.each([
    [409, 'สร้างดีลได้เฉพาะใบเสนอราคาที่อนุมัติแล้วเท่านั้น (ปัจจุบัน: SUPERSEDED)'],
    [403, 'ไม่มีสิทธิ์เข้าถึงรายการนี้'],
  ])('a %s surfaces the server\'s own Thai message and stays on the quotation', async (status, message) => {
    api.dealQuotations.get.mockResolvedValue({ quotation: quotation() });
    api.dealQuotations.promoteToDeal.mockRejectedValue(Object.assign(new Error(message), { status }));
    const { showToast } = renderEditor();

    fireEvent.click(await screen.findByRole('button', { name: PROMOTE }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: PROMOTE }));

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', message));
    expect(screen.queryByText(/deal page/)).toBeNull();
  });

  it('an error with no message falls back to "ยืนยันคำสั่งซื้อไม่สำเร็จ"', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: quotation() });
    api.dealQuotations.promoteToDeal.mockRejectedValue(Object.assign(new Error(''), { status: 500 }));
    const { showToast } = renderEditor();

    fireEvent.click(await screen.findByRole('button', { name: PROMOTE }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: PROMOTE }));

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', 'ยืนยันคำสั่งซื้อไม่สำเร็จ'));
  });
});
