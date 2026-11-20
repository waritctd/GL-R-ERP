// The deal page rebuilt to the owner-approved drawing (a-deal, a-deal-quote, a-deal-money, a-deal-ship; 2026-11-16):
// header + S stepper + ขั้นต่อไป card, quick actions โทรแล้ว / นัดเข้าพบ, five tabs, a merged timeline, quiet links
// instead of the red danger zone, and งาน instead of ดีล in this page's own labels.
//
// Permission note: every gate below is the page's EXISTING condition, re-asserted after its control moved. These
// tests drive mocked api responses, so they prove the page renders each control under its old condition; they are
// not evidence about what the Java services allow.
import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import {
  act, fireEvent, render, screen, waitFor, within,
} from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TicketDetailPage } from './TicketDetailPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  const { DEAL_STAGE_CATALOG } = await import('../../data/dealStageCatalog.js');
  return {
    ...actual,
    api: {
      meta: { dealStages: vi.fn().mockResolvedValue(DEAL_STAGE_CATALOG) },
      tickets: {
        get: vi.fn(),
        actions: vi.fn(),
        listPayments: vi.fn(),
        listDeliveries: vi.fn(),
        getProcurementSource: vi.fn().mockResolvedValue({ legacy: false, editable: false, decidedAt: '2026-11-10T00:00:00Z', quotationNumber: null, lines: [] }),
        listActivities: vi.fn(),
        addActivity: vi.fn(),
        updateTracking: vi.fn(),
        parties: vi.fn(),
        orders: vi.fn(),
        comment: vi.fn(),
        hold: vi.fn(),
        dormant: vi.fn(),
        resume: vi.fn(),
        reopen: vi.fn(),
        markLost: vi.fn(),
        cancel: vi.fn(),
        updateStage: vi.fn(),
        setTenderRequirement: vi.fn(),
        downloadQuotationPdf: vi.fn(),
        downloadQuotationXlsx: vi.fn(),
      },
      deliveryRequests: { list: vi.fn() },
      attachments: {
        list: vi.fn(),
        upload: vi.fn(),
        delete: vi.fn(),
        fileUrl: (id) => `#mock-file-${id}`,
      },
      pricingRequests: {
        listForTicket: vi.fn(),
        listCustomerQuotations: vi.fn(),
      },
      dealQuotations: {
        listForTicket: vi.fn(),
        displayNameOptions: vi.fn().mockResolvedValue({ items: [] }),
      },
      depositNotices: { listByTicket: vi.fn() },
      storedRemainingInvoices: { listForTicket: vi.fn().mockResolvedValue({ remainingInvoices: [] }) },
      storedImportRequests: { listForTicket: vi.fn() },
      importRequests: { brands: vi.fn().mockResolvedValue({ brands: [] }), pages: vi.fn() },
      fxRates: { list: vi.fn() },
      expectedPayments: { list: vi.fn() },
      salesVisits: { create: vi.fn(), plannedOnDeal: vi.fn() },
      priceImport: { countries: vi.fn().mockResolvedValue([]) },
      catalog: { prices: vi.fn() },
    },
  };
});

const salesOwner = { id: 1, employeeId: 1, name: 'พนักงานขาย', role: 'sales' };
const otherSales = { id: 2, employeeId: 2, name: 'เซลส์อีกคน', role: 'sales' };
const salesManager = { id: 7, employeeId: 7, name: 'ผู้จัดการขาย', role: 'sales_manager' };
const ceo = { id: 9, employeeId: 9, name: 'CEO', role: 'ceo' };

function buildTicket({ summary = {}, items, events } = {}) {
  return {
    summary: {
      id: 701,
      code: 'PR-2026-0701',
      title: 'โครงการบ้านสวน',
      projectName: 'โครงการบ้านสวน',
      status: 'draft',
      customerName: 'บริษัท ทดสอบ จำกัด',
      designerName: 'เทพสตูดิโอ',
      createdById: 1,
      createdByName: 'สมชาย ใจดี',
      createdAt: '2026-07-01T09:00:00.000Z',
      updatedAt: '2026-07-02T09:00:00.000Z',
      lifecycle: 'ACTIVE',
      salesStage: 'SPEC_APPROVED',
      entryChannel: 'DESIGNER_LED',
      paymentStage: 'NOT_REQUIRED',
      amountPayable: 912300,
      amountPaid: 456150,
      amountOutstanding: 456150,
      effectiveWinProbability: 50,
      overdue: false,
      ...summary,
    },
    items: items ?? [
      { id: 70101, brand: 'Equipe', model: 'Raku', size: '20x20', qty: 120, qtyDelivered: 120, qtyFromStock: 120, proposedPrice: 150, approvedPrice: null },
      { id: 70102, brand: 'Equipe', model: 'Splendours', size: '7.5x15', qty: 64, qtyDelivered: 0, qtyFromStock: 0, proposedPrice: 150, approvedPrice: null },
    ],
    events: events ?? [
      { id: 1, kind: 'CREATED', actorName: 'สมชาย ใจดี', createdAt: '2026-07-01T09:00:00.000Z' },
    ],
    quotations: [],
    fromPricingChain: false,
  };
}

function setDeal(summary = {}, availableActions = [], extra = {}) {
  api.tickets.get.mockResolvedValue({ ticket: buildTicket({ summary, ...extra }) });
  api.tickets.actions.mockResolvedValue({
    currentState: { lifecycle: summary.lifecycle ?? 'ACTIVE', salesStage: summary.salesStage ?? 'SPEC_APPROVED' },
    availableActions: availableActions.map((action) => (typeof action === 'string' ? { action } : action)),
    stageDecisions: [],
  });
}

function renderPage(user = salesOwner, { url = '/tickets/701', showToast = vi.fn() } = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[url]}>
        <TicketDetailPage user={user} ticketId={701} onBack={vi.fn()} showToast={showToast} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { ...utils, showToast, queryClient };
}

const pageReady = () => screen.findByTestId('deal-state-header');
async function openTab(name) {
  fireEvent.click(await screen.findByRole('tab', { name }));
}
async function settle() {
  await act(async () => { await new Promise((resolve) => { setTimeout(resolve, 30); }); });
}

beforeEach(() => {
  vi.clearAllMocks();
  setDeal();
  api.tickets.listPayments.mockResolvedValue({ items: [] });
  api.tickets.listDeliveries.mockResolvedValue({ items: [] });
  api.tickets.listActivities.mockResolvedValue({ items: [] });
  api.tickets.parties.mockResolvedValue({ parties: [] });
  api.tickets.orders.mockResolvedValue({ rootTicketId: 701, rootCode: 'PR-2026-0701', orders: [], totals: null });
  api.tickets.addActivity.mockResolvedValue({ id: 1, kind: 'CALL' });
  api.tickets.reopen.mockResolvedValue({ ticket: buildTicket() });
  api.attachments.list.mockResolvedValue({ attachments: [] });
  api.pricingRequests.listForTicket.mockResolvedValue({ items: [] });
  api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [] });
  api.dealQuotations.listForTicket.mockResolvedValue({ items: [] });
  api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [] });
  api.storedImportRequests.listForTicket.mockResolvedValue({ importRequests: [] });
  api.fxRates.list.mockResolvedValue({ fxRates: [] });
  api.expectedPayments.list.mockResolvedValue([]);
  api.salesVisits.plannedOnDeal.mockResolvedValue([]);
  api.catalog.prices.mockResolvedValue({ items: [] });
});

describe('the ขั้นต่อไป card', () => {
  it('a paused deal offers ดำเนินการต่อ, which opens the existing resume dialog', async () => {
    setDeal({ lifecycle: 'ON_HOLD', salesStage: 'QUOTE_OWNER' }, ['RESUME']);
    renderPage();
    await pageReady();
    const card = await screen.findByTestId('next-step-card');
    expect(within(card).getByTestId('next-step-title').textContent).toBe('งานนี้พักไว้ชั่วคราว');
    fireEvent.click(await within(card).findByRole('button', { name: 'ดำเนินการต่อ' }));
    expect(await screen.findByRole('dialog', { name: 'ดำเนินการต่อ' })).toBeTruthy();
  });

  it('wrong-way-round: a paused deal without RESUME offers no button', async () => {
    setDeal({ lifecycle: 'ON_HOLD', salesStage: 'QUOTE_OWNER' }, []);
    renderPage(salesManager);
    await pageReady();
    await settle();
    const card = screen.getByTestId('next-step-card');
    expect(within(card).queryByRole('button', { name: 'ดำเนินการต่อ' })).toBeNull();
  });

  it('a lost deal offers เปิดงานอีกครั้ง when REOPEN is allowed, and says งาน in its toast', async () => {
    setDeal({ lifecycle: 'CLOSED_LOST', salesStage: 'QUOTE_OWNER', lostReason: 'PRICE' }, ['REOPEN']);
    const { showToast } = renderPage(salesOwner);
    await pageReady();
    const card = await screen.findByTestId('next-step-card');
    await waitFor(() => expect(within(card).getByTestId('next-step-title').textContent).toBe('เสียงานแล้ว'));
    fireEvent.click(await within(card).findByRole('button', { name: 'เปิดงานอีกครั้ง' }));
    await waitFor(() => expect(api.tickets.reopen).toHaveBeenCalledWith(701, {}));
    await waitFor(() => expect(showToast).toHaveBeenCalledWith('success', 'เปิดงานอีกครั้งแล้ว'));
  });

  it('wrong-way-round: a lost deal without REOPEN offers no button; a cancelled deal offers none', async () => {
    setDeal({ lifecycle: 'CLOSED_LOST', salesStage: 'QUOTE_OWNER' }, []);
    const first = renderPage();
    await pageReady();
    await settle();
    expect(within(screen.getByTestId('next-step-card')).queryByRole('button')).toBeNull();
    first.unmount();

    setDeal({ lifecycle: 'CANCELLED', salesStage: 'QUOTE_OWNER', status: 'cancelled' }, []);
    renderPage();
    await pageReady();
    await settle();
    const card = screen.getByTestId('next-step-card');
    expect(within(card).getByTestId('next-step-title').textContent).toBe('ยกเลิกงานแล้ว');
    expect(within(card).queryByRole('button')).toBeNull();
  });
});

describe('header quick actions โทรแล้ว / นัดเข้าพบ', () => {
  it('โทรแล้ว opens the board’s call dialog and logs a CALL activity on this deal', async () => {
    renderPage();
    await pageReady();
    fireEvent.click(await screen.findByRole('button', { name: 'โทรแล้ว' }));
    const dialog = await screen.findByRole('dialog', { name: 'โทรแล้ว' });
    fireEvent.click(within(dialog).getByRole('radio', { name: 'คุยแล้ว ยังสนใจ' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));
    await waitFor(() => expect(api.tickets.addActivity).toHaveBeenCalledWith(701, expect.objectContaining({
      kind: 'CALL', note: 'คุยแล้ว ยังสนใจ',
    })));
  });

  it('wrong-way-round: a sales rep who does not own the deal gets neither', async () => {
    renderPage(otherSales);
    await pageReady();
    await settle();
    expect(screen.queryByRole('button', { name: 'โทรแล้ว' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'นัดเข้าพบ' })).toBeNull();
  });
});

// One case per control that MOVED, under its old condition, and the wrong-way-round case: a viewer that could not
// use it before still cannot.
describe('relocated actions keep their old permission condition', () => {
  async function overviewReady(user) {
    renderPage(user);
    await pageReady();
    await settle();
  }

  it('พักงาน: shown with PLACE_ON_HOLD on an active deal, opens the พักงาน dialog', async () => {
    setDeal({}, ['PLACE_ON_HOLD']);
    await overviewReady(salesOwner);
    fireEvent.click(await screen.findByRole('button', { name: 'พักงาน' }));
    expect(await screen.findByRole('dialog', { name: 'พักงาน' })).toBeTruthy();
  });

  it('wrong-way-round: no พักงาน without PLACE_ON_HOLD, nor on a paused deal', async () => {
    setDeal({}, []);
    const first = renderPage();
    await pageReady();
    await settle();
    expect(screen.queryByRole('button', { name: 'พักงาน' })).toBeNull();
    first.unmount();
    setDeal({ lifecycle: 'ON_HOLD' }, ['PLACE_ON_HOLD']);
    await overviewReady(salesOwner);
    expect(screen.queryByRole('button', { name: 'พักงาน' })).toBeNull();
  });

  it('ยกเลิกงาน: the owner with CANCEL, opens the existing cancel dialog', async () => {
    setDeal({}, ['CANCEL']);
    await overviewReady(salesOwner);
    fireEvent.click(await screen.findByRole('button', { name: 'ยกเลิกงาน' }));
    expect(await screen.findByRole('dialog', { name: 'ยกเลิกงาน' })).toBeTruthy();
  });

  it('wrong-way-round: ยกเลิกงาน stays owner-only even when CANCEL is listed (ceo, sales_manager, another rep)', async () => {
    for (const user of [ceo, salesManager, otherSales]) {
      setDeal({}, ['CANCEL']);
      const view = renderPage(user);
      await pageReady();
      await settle();
      expect(screen.queryByRole('button', { name: 'ยกเลิกงาน' })).toBeNull();
      view.unmount();
    }
  });

  it('logging any activity is under + บันทึก for the deal’s writers', async () => {
    await overviewReady(salesOwner);
    const timeline = screen.getByTestId('deal-timeline');
    fireEvent.click(within(timeline).getByRole('button', { name: '+ บันทึก' }));
    fireEvent.change(within(timeline).getByDisplayValue('โทรศัพท์'), { target: { value: 'EMAIL' } });
    fireEvent.click(within(timeline).getByRole('button', { name: 'บันทึกกิจกรรม' }));
    await waitFor(() => expect(api.tickets.addActivity).toHaveBeenCalledWith(701, expect.objectContaining({ kind: 'EMAIL' })));
  });

  it('wrong-way-round: another rep may comment but not log an activity', async () => {
    await overviewReady(otherSales);
    const timeline = screen.getByTestId('deal-timeline');
    fireEvent.click(within(timeline).getByRole('button', { name: '+ บันทึก' }));
    expect(within(timeline).getByPlaceholderText('เพิ่มความคิดเห็น…')).toBeTruthy();
    expect(within(timeline).queryByText('บันทึกกิจกรรมติดตาม')).toBeNull();
  });

  it('file upload is on ไฟล์ for a participant on an open deal', async () => {
    await overviewReady(salesOwner);
    await openTab('ไฟล์');
    expect(await screen.findByText(/^แนบไฟล์ \(PDF/)).toBeTruthy();
  });

  it('wrong-way-round: no upload for a rep who is not a participant', async () => {
    await overviewReady(otherSales);
    await openTab('ไฟล์');
    await settle();
    expect(screen.queryByText(/^แนบไฟล์ \(PDF/)).toBeNull();
  });
});

  describe('ส่งของ tab primary action', () => {
    const ROUNDS = (canStart) => ({
      order: { ticketId: 701, displayCode: 'PR-2026-0701-1', orderNo: 1 }, rounds: [], approvedQuotations: [],
      canStart, startBlockedReason: canStart ? null : 'ยังไม่มีใบเสนอราคาที่อนุมัติแล้ว',
    });

    it('the delivery request is the one sticky primary when the viewer has it; without it the page keeps its own', async () => {
      api.deliveryRequests.list.mockResolvedValue(ROUNDS(true));
      renderPage(salesOwner, { url: '/tickets/701?tab=ship' });
      await pageReady();
      await waitFor(() => expect(screen.getByTestId('ticket-primary-action').getAttribute('data-action')).toBe('request_delivery'));
      expect(screen.getAllByTestId('ticket-primary-action')).toHaveLength(1);
      expect(screen.queryByTestId('request-delivery-button')).toBeNull();
    });

    it('a blocked viewer sees no request action, so the page action is not replaced', async () => {
      api.deliveryRequests.list.mockResolvedValue(ROUNDS(false));
      renderPage(salesOwner, { url: '/tickets/701?tab=ship' });
      await pageReady();
      await screen.findByTestId('request-blocked-reason');
      expect(screen.queryByText('ขอออกใบกำกับ / ส่งของ')).toBeNull();
    });
  });

