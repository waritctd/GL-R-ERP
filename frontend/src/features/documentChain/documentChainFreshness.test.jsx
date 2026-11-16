import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QuotationEditorPage } from '../quotations/QuotationEditorPage.jsx';
import { DealPaymentSection } from '../tickets/DealPaymentSection.jsx';
import { invalidateDealDocuments } from './documentChain.js';
import { queryKeys } from '../../api/queryKeys.js';
import { api } from '../../api/index.js';
import {
  chainDepositBlockedReason, chainDepositState, chainRemainingBlockedReason, chainRemainingState,
} from '../../api/mockDocumentChainRules.js';

globalThis.React = React;

// "Always up to date with the งาน tab": the quotation page's chain card and the deal page's เงิน tab are mounted on
// ONE query client, an action is taken on one, and the other is asserted to show the new state — with no manual
// refetch, no remount. What makes it pass is each mutation's invalidateDealDocuments (documentChain.js).

vi.mock('../../utils/download.js', () => ({ downloadBlob: vi.fn() }));
vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: { get: vi.fn() },
      dealQuotations: {
        get: vi.fn(), promoteToDeal: vi.fn(), displayNameOptions: vi.fn().mockResolvedValue({ items: [] }),
        calculateLine: vi.fn(), downloadPdf: vi.fn(), downloadXlsx: vi.fn(),
      },
      documentChain: { get: vi.fn() },
      depositNotices: { listByTicket: vi.fn(), createDraft: vi.fn(), issue: vi.fn(), preview: vi.fn(), downloadPdf: vi.fn(), downloadXlsx: vi.fn() },
      pricingRequests: { listForTicket: vi.fn() },
      storedRemainingInvoices: { listForTicket: vi.fn(), download: vi.fn() },
      catalog: { prices: vi.fn() },
      designers: { search: vi.fn(), getByCode: vi.fn().mockRejectedValue(new Error('not found')) },
      customers: { search: vi.fn().mockResolvedValue({ customers: [] }), projects: vi.fn(), contacts: vi.fn() },
    },
  };
});

const owner = { id: 6, name: 'คุณสมหมาย ขายดี', role: 'sales', employeeId: null };

// A tiny in-memory server: the writes move it, the reads (and the summary) are computed from it.
let server;
function resetServer() {
  server = {
    ticket: { id: 18, code: 'DL-2026-0026', createdById: 6, status: 'draft', lifecycle: 'ACTIVE', depositPolicy: 'REQUIRED', paymentStatus: null, fulfillmentStatus: null, salesStage: 'QUOTE_OWNER', amountPayable: 10700, amountPaid: 0, amountOutstanding: 10700, paymentStage: 'NOT_STARTED' },
    promoted: false,
    notices: [],
  };
}
function chainNow() {
  const t = server.ticket;
  const f = {
    status: t.status, lifecycle: t.lifecycle, depositPolicy: t.depositPolicy, paymentStatus: t.paymentStatus,
    fulfillmentStatus: t.fulfillmentStatus, noticeDraft: server.notices.some((d) => d.status === 'DRAFT'),
    noticeIssued: server.notices.some((d) => d.status === 'ISSUED'), invoiceDraft: false, invoiceIssued: false,
  };
  const issued = server.notices.find((d) => d.status === 'ISSUED') ?? null;
  const remainingState = chainRemainingState(f);
  return {
    ticketId: 18, dealCode: t.code, dealStatus: t.status, lifecycle: t.lifecycle,
    order: { confirmed: t.paymentStatus != null, confirmable: t.status === 'draft' && !server.promoted, confirmedFromQuotationId: server.promoted ? 5 : null, confirmedFromQuotationNumber: server.promoted ? 'QT-2026-0005-1' : null, confirmedAt: null },
    deposit: { policy: t.depositPolicy, required: true, percent: 30, state: chainDepositState(f), blockedReason: chainDepositBlockedReason(f), noticeId: server.notices[0]?.id ?? null, issuedNoticeId: issued?.id ?? null, docNumber: issued?.docNumber ?? null, issueDate: issued?.issueDate ?? null, draftExists: f.noticeDraft, received: false },
    remaining: { state: remainingState, blockedReason: chainRemainingBlockedReason(f), canCreate: false, invoiceId: null, docNumber: null, docDate: null, draftExists: false },
    viewer: { mayConfirmOrder: true, mayWriteDepositNotice: true, mayWriteRemainingInvoice: true },
  };
}
const draft = () => ({ id: 31, ticketId: 18, status: 'DRAFT', docNumber: null, version: 1, depositPercent: 0.3, subtotal: 10000, depositAmount: 3000, vatPercent: 0.07, vatAmount: 210, totalPayable: 3210, customerName: 'บริษัท ตัวอย่าง จำกัด', reference: 'QT-2026-0005-1', updatedAt: 'u1' });

function wireServer() {
  api.tickets.get.mockImplementation(async () => ({ ticket: { summary: { ...server.ticket }, events: [] } }));
  api.dealQuotations.get.mockResolvedValue({ quotation: {
    id: 5, number: 'QT-2026-0005-1', ticketId: 18, ticketCode: 'DL-2026-0026', docStatus: 'APPROVED', origin: 'DEAL_DIRECT', salesRepId: 6,
    salesRepName: 'คุณสมหมาย ขายดี', createdByName: 'คุณสมหมาย ขายดี', approvedAt: '2026-11-17T03:00:00Z', depositPercent: 30,
    customerName: 'บริษัท ตัวอย่าง จำกัด', items: [], subtotalAmount: 10000, vatAmount: 700, grandTotal: 10700, currency: 'THB',
    offerDate: '2026-11-17', createdAt: '2026-11-17T00:00:00Z', updatedAt: '2026-11-17T00:00:00Z',
  } });
  api.documentChain.get.mockImplementation(async () => ({ documentChain: chainNow() }));
  api.depositNotices.listByTicket.mockImplementation(async () => ({ depositNotices: structuredClone(server.notices) }));
  api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [] });
  api.pricingRequests.listForTicket.mockResolvedValue({ items: [] });
  api.dealQuotations.promoteToDeal.mockImplementation(async () => {
    Object.assign(server.ticket, { status: 'quotation_issued', paymentStatus: 'CUSTOMER_CONFIRMED', salesStage: 'ORDER_RECEIVED' });
    server.promoted = true;
    return { result: { ticketId: 18, ticket: { ...server.ticket } } };
  });
  api.depositNotices.createDraft.mockImplementation(async () => {
    server.notices = [draft()];
    return { depositNotice: draft() };
  });
  api.depositNotices.issue.mockImplementation(async (id) => {
    server.notices = server.notices.map((d) => (d.id === id ? { ...d, status: 'ISSUED', docNumber: 'GLRD69005', issueDate: '2026-11-17' } : d));
    server.ticket.paymentStatus = 'DEPOSIT_NOTICE_ISSUED';
    return { depositNotice: server.notices.find((d) => d.id === id) };
  });
}

/** The quotation page and the deal page's เงิน tab, side by side on ONE client. */
function renderBothPages() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/quotations/5']}>
        <Routes>
          <Route path="/quotations/:id" element={<QuotationEditorPage user={owner} showToast={vi.fn()} />} />
        </Routes>
        <div data-testid="deal-page-money-tab">
          <DealPaymentSection ticketId={18} user={owner} summary={server.ticket} showToast={vi.fn()} />
        </div>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return queryClient;
}

const dealDeposit = () => within(within(screen.getByTestId('deal-page-money-tab')).getByTestId('payment-step-deposit'));

describe('the quotation card and the deal page read one source and stay in step', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetServer();
    wireServer();
  });

  it('ยืนยันคำสั่งซื้อ on the quotation page: the deal page\'s deposit step moves to ยังไม่สร้าง', async () => {
    renderBothPages();
    await waitFor(() => expect(dealDeposit().getByText('ยังไม่ถึงขั้นตอน')).toBeTruthy());
    const card = within(await screen.findByTestId('quotation-document-chain'));
    fireEvent.click(card.getByRole('button', { name: 'ยืนยันคำสั่งซื้อ' }));
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'ยืนยันคำสั่งซื้อ' }));
    await waitFor(() => expect(dealDeposit().getByText('ยังไม่สร้าง')).toBeTruthy());
  });

  it('issuing the deposit notice from the quotation page: the deal page shows it issued, with its number', async () => {
    Object.assign(server.ticket, { status: 'quotation_issued', paymentStatus: 'CUSTOMER_CONFIRMED', salesStage: 'ORDER_RECEIVED' });
    server.promoted = true;
    server.notices = [draft()];
    renderBothPages();
    await waitFor(() => expect(dealDeposit().getByText('ร่าง')).toBeTruthy());
    const card = within(await screen.findByTestId('quotation-document-chain'));
    fireEvent.click(card.getByRole('button', { name: 'ออกใบแจ้งยอดเงินรับมัดจำ' }));
    const dialog = within(await screen.findByTestId('quotation-chain-deposit-dialog'));
    fireEvent.click(await dialog.findByRole('button', { name: 'ออกเอกสาร' }));
    await waitFor(() => expect(dealDeposit().getByText('รอชำระ')).toBeTruthy());
    expect(dealDeposit().getByText('GLRD69005')).toBeTruthy();
  });

  it('issuing it on the deal page: the quotation card shows step 3 done with the number', async () => {
    Object.assign(server.ticket, { status: 'quotation_issued', paymentStatus: 'CUSTOMER_CONFIRMED', salesStage: 'ORDER_RECEIVED' });
    server.promoted = true;
    server.notices = [draft()];
    renderBothPages();
    const cardEl = await screen.findByTestId('quotation-document-chain');
    await waitFor(() => expect(cardEl.querySelector('li[data-step="3"]').getAttribute('data-state')).toBe('current'));
    fireEvent.click(await dealDeposit().findByRole('button', { name: 'ออกเอกสาร' }));
    const confirm = await screen.findByRole('dialog');
    fireEvent.click(within(confirm).getByRole('button', { name: 'ออกเอกสาร' }));
    await waitFor(() => expect(cardEl.querySelector('li[data-step="3"]').getAttribute('data-state')).toBe('done'));
    expect(within(cardEl).getByText('GLRD69005')).toBeTruthy();
  });
});

describe('invalidateDealDocuments — the keys every chain mutation invalidates', () => {
  it('marks every key both pages read as stale, for a string or a numeric ticket id', async () => {
    const queryClient = new QueryClient();
    const keys = [
      queryKeys.documentChain(18), queryKeys.depositNotices(18), queryKeys.depositNotices('18'),
      queryKeys.storedRemainingInvoices(18), queryKeys.storedRemainingInvoices('18'), queryKeys.ticketDetail(18),
      [...queryKeys.ticketDetail(18), 'promotionSummary'], queryKeys.ticketActions(18), ['tickets', 'list', {}],
      queryKeys.dealQuotationsList({}), queryKeys.dealQuotationDetail(5),
    ];
    for (const key of keys) queryClient.setQueryData(key, { seeded: true });
    await invalidateDealDocuments(queryClient, '18');
    for (const key of keys) expect(queryClient.getQueryState(key).isInvalidated, JSON.stringify(key)).toBe(true);
    const untouched = ['payroll', 'x'];
    queryClient.setQueryData(untouched, 1);
    await invalidateDealDocuments(queryClient, 18);
    expect(queryClient.getQueryState(untouched).isInvalidated).toBe(false);
  });
});
