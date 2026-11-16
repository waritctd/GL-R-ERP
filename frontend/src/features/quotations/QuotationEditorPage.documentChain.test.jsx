import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QuotationEditorPage } from './QuotationEditorPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// The document chain card on the quotation page ("เอกสารต่อจากใบเสนอราคานี้", owner-approved 2026-11-17). Every state
// comes from the server's summary (api.documentChain.get, mocked here); these tests pin what the page DOES with it:
// one primary button, the existing confirm / issue confirms, the existing dialogs, the header's own slots, and the
// one-line situations. Mock-driven: says nothing about who may act — DocumentChainIntegrationTest does.

vi.mock('../tickets/RemainingInvoiceDialog.jsx', () => ({
  RemainingInvoiceDialog: ({ ticketId, onClose }) => (
    <div data-testid="remaining-invoice-dialog-stub">
      ใบแจ้งหนี้ {ticketId}
      <button type="button" onClick={onClose}>ปิดสตับ</button>
    </div>
  ),
}));

vi.mock('../../utils/download.js', () => ({ downloadBlob: vi.fn() }));

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
      documentChain: { get: vi.fn() },
      depositNotices: { listByTicket: vi.fn(), createDraft: vi.fn(), issue: vi.fn(), downloadPdf: vi.fn() },
      pricingRequests: { listForTicket: vi.fn(), createDepositNoticeFromQuotation: vi.fn() },
      storedRemainingInvoices: { listForTicket: vi.fn(), download: vi.fn() },
      catalog: { prices: vi.fn() },
      designers: { search: vi.fn(), getByCode: vi.fn().mockRejectedValue(new Error('not found')) },
      customers: {
        search: vi.fn(), create: vi.fn(), projects: vi.fn(), createProject: vi.fn(),
        contacts: vi.fn(), createContact: vi.fn(),
      },
    },
  };
});

const owner = { id: 6, name: 'คุณสมหมาย ขายดี', role: 'sales', employeeId: null };
const manager = { id: 11, name: 'ผึ้ง', role: 'sales_manager', employeeId: 11 };

function quotation(overrides = {}) {
  return {
    id: 5, number: 'QT-2026-0005-1', ticketId: 18, docStatus: 'APPROVED', origin: 'DEAL_DIRECT',
    salesRepId: 6, salesRepName: 'คุณสมหมาย ขายดี', salesRepPhone: null, createdByName: 'คุณสมหมาย ขายดี',
    approvalNote: null, approvedAt: '2026-11-17T03:00:00Z',
    customerName: 'บริษัท ตัวอย่าง จำกัด', projectName: null, deptCode: null, unitCode: null, offerDate: '2026-11-17',
    depositPercent: 30, remainderMode: 'CREDIT', creditDays: 30, validityDays: 30, validityDate: null,
    customerNotes: null, subtotalAmount: 10000, vatAmount: 700, grandTotal: 10700, currency: 'THB',
    approverHasSignature: false, items: [], createdAt: '2026-11-17T00:00:00Z', updatedAt: '2026-11-17T00:00:00Z',
    ...overrides,
  };
}

function summary(overrides = {}) {
  const base = {
    ticketId: 18, dealCode: 'DL-2026-0026', dealStatus: 'draft', lifecycle: 'ACTIVE',
    order: { confirmed: false, confirmable: true, confirmedFromQuotationId: null, confirmedFromQuotationNumber: null, confirmedAt: null },
    deposit: { policy: 'REQUIRED', required: true, percent: null, state: 'NOT_YET', blockedReason: null, noticeId: null, issuedNoticeId: null, docNumber: null, issueDate: null, draftExists: false, received: false },
    remaining: { state: 'WAITING', blockedReason: 'ORDER_NOT_CONFIRMED', canCreate: false, invoiceId: null, docNumber: null, docDate: null, draftExists: false },
    viewer: { mayConfirmOrder: true, mayWriteDepositNotice: true, mayWriteRemainingInvoice: true },
  };
  return {
    ...base, ...overrides,
    order: { ...base.order, ...overrides.order },
    deposit: { ...base.deposit, ...overrides.deposit },
    remaining: { ...base.remaining, ...overrides.remaining },
    viewer: { ...base.viewer, ...overrides.viewer },
  };
}
const confirmed = (overrides = {}) => summary({
  dealStatus: 'quotation_issued', ...overrides,
  order: { confirmed: true, confirmable: false, confirmedFromQuotationId: 5, confirmedFromQuotationNumber: 'QT-2026-0005-1', confirmedAt: '2026-11-17T04:00:00Z', ...overrides.order },
  deposit: { state: 'TO_CREATE', percent: 30, ...overrides.deposit },
  remaining: { state: 'WAITING', blockedReason: 'DEPOSIT_NOT_RECEIVED', ...overrides.remaining },
});

const draftNotice = {
  id: 31, ticketId: 18, status: 'DRAFT', docNumber: null, depositPercent: 0.3, subtotal: 10000, depositAmount: 3000,
  vatPercent: 0.07, vatAmount: 210, totalPayable: 3210, reference: 'QT-2026-0005-1', customerName: 'บริษัท ตัวอย่าง จำกัด',
  updatedAt: '2026-11-17T05:00:00Z', version: 1,
};

function DealRoute() {
  const { id } = useParams();
  return <p>deal page {id}</p>;
}

function renderPage(user = owner, showToast = vi.fn()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/quotations/5']}>
        <Routes>
          <Route path="/quotations/:id" element={<QuotationEditorPage user={user} showToast={showToast} />} />
          <Route path="/tickets/:id" element={<DealRoute />} />
          <Route path="/tickets/:id/deposit" element={<p>notice page</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { queryClient, showToast };
}

const card = async () => within(await screen.findByTestId('quotation-document-chain'));
const primaryButtons = (el) => el.querySelectorAll('[data-chain-primary="true"]');

describe('QuotationEditorPage — the document chain card', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.tickets.get.mockResolvedValue({ ticket: { summary: { id: 18, code: 'DL-2026-0026', createdById: 6, status: 'draft', salesStage: 'QUOTE_OWNER' }, events: [] } });
    api.dealQuotations.get.mockResolvedValue({ quotation: quotation() });
    api.pricingRequests.listForTicket.mockResolvedValue({ items: [] });
    api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [] });
    api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [] });
  });

  it('approved, order not confirmed: four steps, ONE primary (ยืนยันคำสั่งซื้อ), and the header keeps only its download', async () => {
    api.documentChain.get.mockResolvedValue({ documentChain: summary() });
    renderPage();
    const c = await card();
    expect(c.getByRole('heading', { name: 'เอกสารต่อจากใบเสนอราคานี้' })).toBeTruthy();
    const steps = (await screen.findByTestId('quotation-document-chain')).querySelectorAll('li[data-step]');
    expect([...steps].map((s) => s.getAttribute('data-state'))).toEqual(['done', 'current', 'todo', 'todo']);
    expect(primaryButtons(screen.getByTestId('quotation-document-chain'))).toHaveLength(1);
    expect(c.getByRole('button', { name: 'ยืนยันคำสั่งซื้อ' })).toBeTruthy();
    // the quotation's download appears ONCE, in the header — step 1 carries none
    expect(within(steps[0]).queryByRole('button')).toBeNull();
    expect(screen.getByRole('button', { name: /ดาวน์โหลด/ })).toBeTruthy();
    // the header's own confirm / go-to slot is gone while the card carries the chain
    expect(screen.queryByTestId('quotation-promote-to-deal')).toBeNull();
    expect(screen.queryByTestId('quotation-go-to-deal')).toBeNull();
  });

  it('ยืนยันคำสั่งซื้อ from the card opens the existing confirm first; confirming keeps the rep on the page and re-reads the chain', async () => {
    api.documentChain.get.mockResolvedValueOnce({ documentChain: summary() })
      .mockResolvedValue({ documentChain: confirmed() });
    api.dealQuotations.promoteToDeal.mockResolvedValue({ result: { ticketId: 18, ticket: { id: 18 }, quotation: quotation() } });
    const { showToast } = renderPage();
    fireEvent.click((await card()).getByRole('button', { name: 'ยืนยันคำสั่งซื้อ' }));
    expect(api.dealQuotations.promoteToDeal).not.toHaveBeenCalled();
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'ยืนยันคำสั่งซื้อ' }));
    await waitFor(() => expect(api.dealQuotations.promoteToDeal).toHaveBeenCalledTimes(1));
    expect(await (await card()).findByRole('button', { name: 'สร้างใบแจ้งยอดเงินรับมัดจำ' })).toBeTruthy();
    expect(screen.queryByText(/deal page/)).toBeNull();
    expect(showToast).toHaveBeenCalledWith('success', 'ยืนยันคำสั่งซื้อแล้ว');
  });

  it('สร้างใบแจ้งยอดเงินรับมัดจำ makes the server draft, then shows THE DRAFT\'s figures, warning first; nothing is issued until ออกเอกสาร', async () => {
    api.documentChain.get.mockResolvedValue({ documentChain: confirmed() });
    api.depositNotices.listByTicket.mockResolvedValueOnce({ depositNotices: [] })
      .mockResolvedValue({ depositNotices: [draftNotice] });
    api.depositNotices.createDraft.mockResolvedValue({ depositNotice: draftNotice });
    api.depositNotices.issue.mockResolvedValue({ depositNotice: { ...draftNotice, status: 'ISSUED', docNumber: 'GLRD69005' } });
    renderPage();
    fireEvent.click((await card()).getByRole('button', { name: 'สร้างใบแจ้งยอดเงินรับมัดจำ' }));
    await waitFor(() => expect(api.depositNotices.createDraft).toHaveBeenCalledWith(18, {}));
    const dialog = within(await screen.findByTestId('quotation-chain-deposit-dialog'));
    expect(await dialog.findByText(/มัดจำ 30% · 3,210\.00 บาท รวม VAT/)).toBeTruthy();
    const warning = dialog.getByTestId('deposit-issue-warning');
    expect(warning.compareDocumentPosition(dialog.getByTestId('deposit-issue-summary')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(dialog.getByRole('link', { name: /แก้รายละเอียด/ }).getAttribute('href')).toBe('/tickets/18/deposit');
    expect(api.depositNotices.issue).not.toHaveBeenCalled();
    fireEvent.click(dialog.getByRole('button', { name: 'ออกเอกสาร' }));
    await waitFor(() => expect(api.depositNotices.issue).toHaveBeenCalledWith(31, { expectedUpdatedAt: '2026-11-17T05:00:00Z' }));
    await waitFor(() => expect(api.documentChain.get.mock.calls.length).toBeGreaterThanOrEqual(2));
  });

  it('an existing DRAFT is opened, never doubled: the button issues it and no second draft is created', async () => {
    api.documentChain.get.mockResolvedValue({ documentChain: confirmed({ deposit: { state: 'DRAFT', draftExists: true, noticeId: 31 } }) });
    api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [draftNotice] });
    renderPage();
    fireEvent.click((await card()).getByRole('button', { name: 'ออกใบแจ้งยอดเงินรับมัดจำ' }));
    expect(await screen.findByTestId('quotation-chain-deposit-dialog')).toBeTruthy();
    expect(api.depositNotices.createDraft).not.toHaveBeenCalled();
  });

  it('notice issued, waiting on ฝ่ายบัญชี: no button, no "your turn" ring — step 4 says who it waits on; the notice downloads', async () => {
    api.documentChain.get.mockResolvedValue({ documentChain: confirmed({
      deposit: { state: 'ISSUED', docNumber: 'GLRD69005', issueDate: '2026-11-17', issuedNoticeId: 31, noticeId: 31 },
    }) });
    api.depositNotices.downloadPdf.mockResolvedValue(new Blob(['%PDF']));
    renderPage();
    const el = await screen.findByTestId('quotation-document-chain');
    await within(el).findByText('GLRD69005');
    expect(primaryButtons(el)).toHaveLength(0);
    expect(el.querySelector('[aria-current="step"]')).toBeNull();
    const step4 = el.querySelector('li[data-step="4"]');
    expect(step4.getAttribute('data-state')).toBe('waiting');
    expect(within(step4).getByText(/รอฝ่ายบัญชี/)).toBeTruthy();
    fireEvent.click(within(el.querySelector('li[data-step="3"]')).getAllByRole('button', { name: /ดาวน์โหลด PDF ใบแจ้งยอดเงินรับมัดจำ/ })[0]);
    await waitFor(() => expect(api.depositNotices.downloadPdf).toHaveBeenCalledWith(31));
  });

  it('the remaining invoice opens the EXISTING RemainingInvoiceDialog on this page', async () => {
    api.documentChain.get.mockResolvedValue({ documentChain: confirmed({
      deposit: { state: 'PAID', received: true, docNumber: 'GLRD69005', issuedNoticeId: 31 },
      remaining: { state: 'TO_CREATE', blockedReason: null, canCreate: true },
    }) });
    renderPage();
    fireEvent.click((await card()).getByRole('button', { name: 'สร้างใบแจ้งหนี้ส่วนที่เหลือ' }));
    expect(await screen.findByTestId('remaining-invoice-dialog-stub')).toBeTruthy();
    expect(screen.getByTestId('remaining-invoice-dialog-stub').textContent).toContain('18');
  });

  it('a manager (may confirm, may not write the notice) sees step 3 waiting on ฝ่ายขาย, no button', async () => {
    api.documentChain.get.mockResolvedValue({ documentChain: confirmed({ viewer: { mayWriteDepositNotice: false, mayWriteRemainingInvoice: false } }) });
    renderPage(manager);
    const el = await screen.findByTestId('quotation-document-chain');
    expect(el.querySelector('li[data-step="3"]').getAttribute('data-state')).toBe('waiting');
    expect(primaryButtons(el)).toHaveLength(0);
    expect(within(el).getByTestId('quotation-chain-next').textContent).toContain('ฝ่ายขายเจ้าของงาน');
  });

  it('confirmed from ANOTHER quotation: one line with a link to it — no chain, no header confirm', async () => {
    api.documentChain.get.mockResolvedValue({ documentChain: confirmed({ order: { confirmedFromQuotationId: 9, confirmedFromQuotationNumber: 'QT-2026-0009-1' } }) });
    renderPage();
    const line = await screen.findByTestId('quotation-chain-notice');
    expect(line.textContent).toContain('QT-2026-0009-1');
    expect(within(line).getByRole('link', { name: 'เปิด QT-2026-0009-1' }).getAttribute('href')).toBe('/quotations/9');
    expect(screen.queryByTestId('quotation-document-chain')).toBeNull();
    expect(screen.queryByTestId('quotation-promote-to-deal')).toBeNull();
  });

  it.each([
    ['lost', { lifecycle: 'CLOSED_LOST', order: { confirmable: false } }, 'งานนี้เสียแล้ว'],
    ['paused', { lifecycle: 'ON_HOLD', order: { confirmable: false } }, 'งานนี้พักไว้'],
    ['cancelled', { lifecycle: 'CANCELLED', dealStatus: 'cancelled', order: { confirmable: false } }, 'งานนี้ยกเลิกแล้ว'],
  ])('a %s deal: one line with เปิดงาน, never a chain with buttons', async (_label, overrides, text) => {
    api.documentChain.get.mockResolvedValue({ documentChain: summary(overrides) });
    renderPage();
    const line = await screen.findByTestId('quotation-chain-notice');
    expect(line.textContent).toContain(text);
    expect(within(line).getByRole('link', { name: 'เปิดงาน' }).getAttribute('href')).toBe('/tickets/18');
    expect(screen.queryByTestId('quotation-document-chain')).toBeNull();
    expect(screen.queryByRole('button', { name: 'ยืนยันคำสั่งซื้อ' })).toBeNull();
  });

  it('a viewer the document gate refuses (403 on the chain) keeps the page exactly as before: the header confirm', async () => {
    api.documentChain.get.mockRejectedValue(Object.assign(new Error('ไม่มีสิทธิ์เข้าถึงรายการนี้'), { status: 403 }));
    renderPage();
    expect(await screen.findByTestId('quotation-promote-to-deal')).toBeTruthy();
    expect(screen.queryByTestId('quotation-document-chain')).toBeNull();
    expect(screen.queryByTestId('quotation-chain-notice')).toBeNull();
  });

  it('a 0% quotation shows step 3 as ไม่รับมัดจำ, skipped', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: quotation({ depositPercent: 0 }) });
    api.documentChain.get.mockResolvedValue({ documentChain: summary() });
    renderPage();
    const el = await screen.findByTestId('quotation-document-chain');
    const step3 = el.querySelector('li[data-step="3"]');
    expect(step3.getAttribute('data-state')).toBe('skipped');
    expect(within(step3).getAllByText(/ไม่รับมัดจำ/).length).toBeGreaterThan(0);
  });
});
