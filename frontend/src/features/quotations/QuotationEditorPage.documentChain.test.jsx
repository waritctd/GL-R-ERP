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
  RemainingInvoiceDialog: ({ ticketId, quotationId, onClose }) => (
    <div data-testid="remaining-invoice-dialog-stub">
      ใบแจ้งหนี้ {ticketId} ของใบเสนอราคา {quotationId}
      <button type="button" onClick={onClose}>ปิดสตับ</button>
    </div>
  ),
}));

vi.mock('../tickets/DocumentDownloadSheet.jsx', () => ({
  DocumentDownloadSheet: ({ kind, documentId }) => <div data-testid="download-sheet-stub">{kind} {documentId}</div>,
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
        downloadPdf: vi.fn(), promoteToDeal: vi.fn(), undoConfirmOrder: vi.fn(),
        displayNameOptions: vi.fn().mockResolvedValue({ items: [] }),
        listForTicket: vi.fn(),
      },
      documentChain: { get: vi.fn() },
      depositNotices: { listByTicket: vi.fn(), createDraft: vi.fn(), issue: vi.fn(), downloadPdf: vi.fn() },
      pricingRequests: { listForTicket: vi.fn(), createDepositNoticeFromQuotation: vi.fn() },
      storedRemainingInvoices: { listForTicket: vi.fn(), download: vi.fn() },
      catalog: { prices: vi.fn() },
      designers: { search: vi.fn(), getByCode: vi.fn().mockRejectedValue(new Error('not found')) },
      customers: {
        search: vi.fn(), create: vi.fn(), projects: vi.fn(),
        contacts: vi.fn(),
      },
    },
  };
});

const owner = { id: 6, name: 'คุณสมหมาย ขายดี', role: 'sales', employeeId: null };
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

  describe('issued notice and invoice steps', () => {
    const issued = (viewer) => confirmed({
      deposit: { state: 'ISSUED', issuedNoticeId: 31, docNumber: 'GLRD69005', issueDate: '2026-11-18' },
      remaining: { state: 'ISSUED', blockedReason: null, invoiceId: 41, docNumber: 'GLR6900058-1', docDate: '2026-11-19' },
      viewer,
    });

    it('a writer gets แก้ไข (notice page in edit mode, back to this quotation; invoice dialog on this quotation) and download through the file sheet', async () => {
      api.documentChain.get.mockResolvedValue({ documentChain: issued({}) });
      renderPage();
      const c = await card();
      const edits = await c.findAllByRole('link', { name: /แก้ไข/ });
      expect(edits[0].getAttribute('href')).toBe('/tickets/18/deposit?edit=1&from=quotation&quotation=5');
      fireEvent.click(c.getAllByRole('button', { name: /แก้ไขใบแจ้งหนี้/ })[0]);
      expect(screen.getByTestId('remaining-invoice-dialog-stub').textContent).toContain('ของใบเสนอราคา 5');
      fireEvent.click(c.getAllByRole('button', { name: /ดาวน์โหลด PDF ใบแจ้งยอดเงินรับมัดจำ/ })[0]);
      expect(screen.getByTestId('download-sheet-stub').textContent).toBe('deposit 31');
      expect(api.depositNotices.downloadPdf).not.toHaveBeenCalled();
    });

    it('a viewer who may not write keeps only the downloads', async () => {
      api.documentChain.get.mockResolvedValue({ documentChain: issued({ mayWriteDepositNotice: false, mayWriteRemainingInvoice: false }) });
      renderPage();
      const c = await card();
      await c.findAllByRole('button', { name: /ดาวน์โหลด PDF ใบแจ้งหนี้ส่วนที่เหลือ/ });
      expect(c.queryAllByRole('link', { name: /แก้ไข/ })).toHaveLength(0);
      expect(c.queryAllByRole('button', { name: /แก้ไข/ })).toHaveLength(0);
    });
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

  // "ยกเลิกการยืนยันคำสั่งซื้อ": shown only when the server says the confirmation is undoable, the viewer may confirm,
  // and the confirmation came from THIS quotation. The server re-checks everything; these pin the card's gate.
  describe('ยกเลิกการยืนยันคำสั่งซื้อ', () => {
    const undoBtn = () => screen.queryByTestId('quotation-chain-undo-confirm');
    const undoable = (extra = {}) => confirmed({ ...extra, order: { undoable: true, confirmationEventId: 71, ...extra.order } });

    async function openDialog() {
      api.documentChain.get.mockResolvedValueOnce({ documentChain: undoable() })
        .mockResolvedValue({ documentChain: summary() });
      const view = renderPage();
      await (await card()).findByRole('button', { name: 'สร้างใบแจ้งยอดเงินรับมัดจำ' });
      fireEvent.click(undoBtn());
      const dialog = await screen.findByTestId('quotation-undo-confirm-dialog');
      return { ...view, dialog: within(dialog) };
    }

    it('opens a dialog first (no call), says what happens, and submits the trimmed reason; the chain re-reads', async () => {
      api.dealQuotations.undoConfirmOrder.mockResolvedValue({ result: { ticketId: 18, ticket: { id: 18 }, quotation: quotation() } });
      const { dialog, showToast } = await openDialog();
      expect(api.dealQuotations.undoConfirmOrder).not.toHaveBeenCalled();
      expect(dialog.getByText(/ใบเสนอราคา.*แก้ไข/)).toBeTruthy();
      expect(dialog.getByText(/ไม่มีการแจ้งเตือน/)).toBeTruthy();
      const reason = dialog.getByRole('textbox');
      expect(reason.getAttribute('maxlength')).toBe('500');
      fireEvent.change(reason, { target: { value: '  กดผิดดีล  ' } });
      fireEvent.click(screen.getByTestId('quotation-undo-confirm-submit'));
      await waitFor(() => expect(api.dealQuotations.undoConfirmOrder).toHaveBeenCalledWith('5', { reason: 'กดผิดดีล', confirmationEventId: 71 }));
      await waitFor(() => expect(screen.queryByTestId('quotation-undo-confirm-dialog')).toBeNull());
      expect(showToast).toHaveBeenCalledWith('success', 'ยกเลิกการยืนยันคำสั่งซื้อแล้ว');
      await waitFor(() => expect(undoBtn()).toBeNull());
      expect(await (await card()).findByRole('button', { name: 'ยืนยันคำสั่งซื้อ' })).toBeTruthy();
    });
  });
});
