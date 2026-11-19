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
import {
  MemoryRouter, Route, Routes, useLocation, useNavigate,
} from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TicketDetailPage } from './TicketDetailPage.jsx';
import { TicketDetailRoute } from './TicketDetailRoute.jsx';
import { api } from '../../api/index.js';
import { addDaysIso, bangkokTodayIso } from '../../utils/format.js';
import { weekStartIso } from '../salesBoard/visitWeek.js';

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

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location-probe">{location.search}</div>;
}

function renderPage(user = salesOwner, { url = '/tickets/701', showToast = vi.fn() } = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[url]}>
        <LocationProbe />
        <TicketDetailPage user={user} ticketId={701} onBack={vi.fn()} showToast={showToast} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { ...utils, showToast, queryClient };
}

const pageReady = () => screen.findByTestId('deal-state-header');
const tabNames = () => screen.getAllByRole('tab').map((tab) => tab.textContent.trim());
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
  api.tickets.resume.mockResolvedValue({ ticket: buildTicket() });
  api.attachments.list.mockResolvedValue({ attachments: [] });
  api.pricingRequests.listForTicket.mockResolvedValue({ items: [] });
  api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [] });
  api.dealQuotations.listForTicket.mockResolvedValue({ items: [] });
  api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [] });
  api.storedImportRequests.listForTicket.mockResolvedValue({ importRequests: [] });
  api.fxRates.list.mockResolvedValue({ fxRates: [] });
  api.expectedPayments.list.mockResolvedValue([]);
  api.salesVisits.create.mockResolvedValue({ id: 5, ticketId: 701 });
  api.salesVisits.plannedOnDeal.mockResolvedValue([]);
  api.catalog.prices.mockResolvedValue({ items: [] });
});

describe('the five tabs', () => {
  it('are ภาพรวม · ใบเสนอราคา · เงิน · ส่งของ · ไฟล์, in that order, with ภาพรวม open by default', async () => {
    renderPage();
    await pageReady();
    expect(tabNames()).toEqual(['ภาพรวม', 'ใบเสนอราคา', 'เงิน', 'ส่งของ', 'ไฟล์']);
    expect(screen.getByRole('tab', { name: 'ภาพรวม' }).getAttribute('aria-selected')).toBe('true');
  });

  it.each([
    ['deal', 'ภาพรวม'],
    ['history', 'ภาพรวม'],
    ['items', 'ใบเสนอราคา'],
    ['documents', 'ไฟล์'],
    ['money', 'เงิน'],
    ['fulfilment', 'ส่งของ'],
  ])('an old link ?tab=%s still opens %s', async (oldKey, newLabel) => {
    renderPage(salesOwner, { url: `/tickets/701?tab=${oldKey}` });
    await pageReady();
    await waitFor(() => expect(screen.getByRole('tab', { name: newLabel }).getAttribute('aria-selected')).toBe('true'));
  });

  it('there is no separate ประวัติ tab any more', async () => {
    renderPage();
    await pageReady();
    expect(screen.queryByRole('tab', { name: /ประวัติ/ })).toBeNull();
  });
});

describe('header', () => {
  it('breadcrumb starts at งานทั้งหมด', async () => {
    renderPage();
    await pageReady();
    expect(screen.getByRole('button', { name: 'งานทั้งหมด' })).toBeTruthy();
  });

  it('names the deal by its project, with the customer, designer, value and chance under it', async () => {
    renderPage();
    const header = await pageReady();
    expect(within(header).getByRole('heading', { level: 1, name: 'โครงการบ้านสวน' })).toBeTruthy();
    const sub = within(header).getByTestId('deal-header-sub');
    expect(sub.textContent).toContain('บริษัท ทดสอบ จำกัด');
    expect(sub.textContent).toContain('เทพสตูดิโอ');
    expect(sub.textContent).toContain('฿912,300');
    expect(sub.textContent).toContain('50%');
  });
});

describe('the S stepper', () => {
  it('shows the eight S steps with the current one marked, and who moves each', async () => {
    setDeal({ salesStage: 'QUOTE_DESIGN_SIDE' });
    renderPage();
    await pageReady();
    const stepper = await screen.findByRole('list', { name: 'ขั้นของงาน' });
    const steps = within(stepper).getAllByRole('listitem');
    expect(steps.map((li) => li.getAttribute('data-step'))).toEqual(['S1', 'S2', 'S3', 'S5', 'S6', 'S7', 'S8', 'S9']);
    const current = steps.find((li) => li.getAttribute('aria-current') === 'step');
    expect(current.getAttribute('data-step')).toBe('S3');
    expect(current.textContent).toContain('ทำใบเสนอราคาแล้ว');
    expect(steps.filter((li) => li.getAttribute('aria-current') === 'step')).toHaveLength(1);
    expect(steps[0].textContent).toContain('เซลส์ย้ายเอง');
    expect(steps[1].textContent).toContain('เซลส์ย้ายเอง');
    // Fix round 1 (ruling 6): from DealStage's facts — S3 follows its documents, S5 / S9 are the rep's moves.
    expect(steps[2].textContent).toContain('ย้ายตามเอกสาร');
    expect(steps[3].textContent).toContain('เซลส์ย้ายเอง');
    expect(steps[7].textContent).toContain('เซลส์ย้ายเอง');
    expect(stepper.textContent).not.toContain('อัตโนมัติ');
    // Steps before the current one read as done.
    expect(steps[0].getAttribute('data-state')).toBe('done');
    expect(steps[3].getAttribute('data-state')).toBe('todo');
  });

  it('replaces the old "สถานะดีล (Pipeline)" panel and its numbered pip', async () => {
    renderPage();
    await pageReady();
    await settle();
    expect(screen.queryByText('สถานะดีล (Pipeline)')).toBeNull();
    expect(screen.queryByTestId('deal-stage-no')).toBeNull();
  });
});

describe('the ขั้นต่อไป card', () => {
  it('names the next step for the stage and holds the page’s one primary button', async () => {
    setDeal({ salesStage: 'LEAD_APPROACH' }, [{ action: 'ADVANCE_STAGE', targetStage: 'PRESENTATION' }]);
    api.tickets.actions.mockResolvedValue({
      currentState: { lifecycle: 'ACTIVE', salesStage: 'LEAD_APPROACH' },
      availableActions: [{ action: 'ADVANCE_STAGE', targetStage: 'PRESENTATION' }],
      stageDecisions: [{ stage: 'PRESENTATION', allowed: true, onRoute: true }],
    });
    renderPage();
    await pageReady();
    const card = await screen.findByTestId('next-step-card');
    expect(within(card).getByText('ขั้นต่อไป')).toBeTruthy();
    const [primary] = await within(card).findAllByTestId('ticket-primary-action');
    expect(within(card).getAllByTestId('ticket-primary-action')).toHaveLength(1);
    // Fix round 1 (L1): the title and the button name the same thing — the title IS the button's step.
    await waitFor(() => expect(within(card).getByTestId('next-step-title').textContent).toBe(primary.textContent.trim()));
  });

  // Fix round 1 (L1), wrong-way-round: the stage table's own title ("ติดตามผลใบเสนอราคา") must not stand over a button
  // that does something else ("ติดตามลูกค้า").
  it('never titles the card with a different step than its button', async () => {
    setDeal({ salesStage: 'QUOTE_DESIGN_SIDE', nextFollowUpAt: '2020-01-01' }, []);
    renderPage();
    await pageReady();
    const card = await screen.findByTestId('next-step-card');
    const primary = await within(card).findByTestId('ticket-primary-action');
    expect(within(card).getByTestId('next-step-title').textContent).toBe(primary.textContent.trim());
    expect(within(card).getByTestId('next-step-title').textContent).not.toBe('ติดตามผลใบเสนอราคา');
  });

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

  // Slice 6.4: the header's โทรแล้ว carries ตามครั้งถัดไป too; unused, the CALL is exactly the old request.
  it('โทรแล้ว with ตามครั้งถัดไป sends the deal’s next follow-up date; without it, exactly the old CALL', async () => {
    renderPage();
    await pageReady();
    fireEvent.click(await screen.findByRole('button', { name: 'โทรแล้ว' }));
    let dialog = await screen.findByRole('dialog', { name: 'โทรแล้ว' });
    fireEvent.click(within(dialog).getByRole('radio', { name: 'ไม่รับสาย' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));
    await waitFor(() => expect(api.tickets.addActivity).toHaveBeenCalledTimes(1));
    expect(api.tickets.addActivity.mock.calls[0]).toStrictEqual([701,
      { kind: 'CALL', activityDate: bangkokTodayIso(), note: 'ไม่รับสาย' }]);
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'โทรแล้ว' })).toBeNull());

    fireEvent.click(screen.getByRole('button', { name: 'โทรแล้ว' }));
    dialog = await screen.findByRole('dialog', { name: 'โทรแล้ว' });
    fireEvent.click(within(dialog).getByRole('radio', { name: 'คุยแล้ว ยังสนใจ' }));
    fireEvent.click(within(dialog).getByRole('radio', { name: '1 สัปดาห์' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));
    await waitFor(() => expect(api.tickets.addActivity).toHaveBeenCalledTimes(2));
    expect(api.tickets.addActivity.mock.calls[1]).toStrictEqual([701, {
      kind: 'CALL', activityDate: bangkokTodayIso(), note: 'คุยแล้ว ยังสนใจ',
      nextFollowUpAt: addDaysIso(bangkokTodayIso(), 7),
    }]);
  });

  it('นัดเข้าพบ opens the board’s plan-visit dialog with this deal chosen', async () => {
    renderPage();
    await pageReady();
    fireEvent.click(await screen.findByRole('button', { name: 'นัดเข้าพบ' }));
    const dialog = await screen.findByRole('dialog', { name: 'นัดเข้าพบ' });
    expect(within(dialog).getByRole('combobox').value).toBe('701');
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึกนัด' }));
    await waitFor(() => expect(api.salesVisits.create).toHaveBeenCalledWith(expect.objectContaining({
      ticketId: 701, contactName: 'เทพสตูดิโอ',
    })));
  });

  it.each([['sales_manager', salesManager], ['ceo', ceo]])('%s gets both (the deal’s writers)', async (_role, user) => {
    renderPage(user);
    await pageReady();
    expect(await screen.findByRole('button', { name: 'โทรแล้ว' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'นัดเข้าพบ' })).toBeTruthy();
  });

  it('wrong-way-round: a sales rep who does not own the deal gets neither', async () => {
    renderPage(otherSales);
    await pageReady();
    await settle();
    expect(screen.queryByRole('button', { name: 'โทรแล้ว' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'นัดเข้าพบ' })).toBeNull();
  });

  // Fix round 1 (M1): the server refuses a follow-up date on a non-ACTIVE deal, so the dialog does not offer one, and
  // the call on a paused deal is recorded exactly as before this slice.
  it('on a paused deal โทรแล้ว offers no ตามครั้งถัดไป and sends exactly the old CALL', async () => {
    setDeal({ lifecycle: 'ON_HOLD' }, []);
    renderPage();
    await pageReady();
    fireEvent.click(await screen.findByRole('button', { name: 'โทรแล้ว' }));
    const dialog = await screen.findByRole('dialog', { name: 'โทรแล้ว' });
    expect(within(dialog).queryByRole('radiogroup', { name: 'ตามครั้งถัดไป' })).toBeNull();
    fireEvent.click(within(dialog).getByRole('radio', { name: 'ไม่รับสาย' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));
    await waitFor(() => expect(api.tickets.addActivity).toHaveBeenCalledTimes(1));
    expect(api.tickets.addActivity.mock.calls[0]).toStrictEqual([701,
      { kind: 'CALL', activityDate: bangkokTodayIso(), note: 'ไม่รับสาย' }]);
  });

  it('wrong-way-round: a paused deal keeps โทรแล้ว but not นัดเข้าพบ (a visit needs an open deal)', async () => {
    setDeal({ lifecycle: 'ON_HOLD' }, []);
    renderPage();
    await pageReady();
    expect(await screen.findByRole('button', { name: 'โทรแล้ว' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'นัดเข้าพบ' })).toBeNull();
  });

  // Slice 6d: ส่งตัวอย่าง beside โทรแล้ว, under the same gate (TicketService#addActivity's requireDealOwnership), opening
  // the sheet with this งาน fixed. Mock-driven: the permission evidence is DealActivitySampleSentIntegrationTest.
  it('ส่งตัวอย่าง records a SAMPLE_SENT activity on this งาน, with what was sent and the picked ตามต่อ', async () => {
    renderPage();
    await pageReady();
    fireEvent.click(await screen.findByRole('button', { name: 'ส่งตัวอย่าง' }));
    const dialog = await screen.findByRole('dialog', { name: 'ส่งตัวอย่าง' });
    expect(within(dialog).queryByLabelText(/^งาน/)).toBeNull();
    fireEvent.change(within(dialog).getByLabelText(/^ส่งอะไร/), { target: { value: 'ตัวอย่าง Raku 3 สี' } });
    fireEvent.click(within(dialog).getByRole('radio', { name: '3 วัน' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));
    await waitFor(() => expect(api.tickets.addActivity).toHaveBeenCalledTimes(1));
    expect(api.tickets.addActivity.mock.calls[0]).toStrictEqual([701, {
      kind: 'SAMPLE_SENT', activityDate: bangkokTodayIso(), note: 'ตัวอย่าง Raku 3 สี',
      nextFollowUpAt: addDaysIso(bangkokTodayIso(), 3),
    }]);
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'ส่งตัวอย่าง' })).toBeNull());
  });

  it.each([['sales_manager', salesManager], ['ceo', ceo]])('%s gets ส่งตัวอย่าง too (the deal’s writers)', async (_role, user) => {
    renderPage(user);
    await pageReady();
    expect(await screen.findByRole('button', { name: 'ส่งตัวอย่าง' })).toBeTruthy();
  });

  it('wrong-way-round: a sales rep who does not own the งาน gets no ส่งตัวอย่าง', async () => {
    renderPage(otherSales);
    await pageReady();
    await settle();
    expect(screen.queryByRole('button', { name: 'ส่งตัวอย่าง' })).toBeNull();
  });

  it('on a paused งาน ส่งตัวอย่าง stays but offers no ตามต่อ (the server refuses a date there)', async () => {
    setDeal({ lifecycle: 'ON_HOLD' }, []);
    renderPage();
    await pageReady();
    fireEvent.click(await screen.findByRole('button', { name: 'ส่งตัวอย่าง' }));
    const dialog = await screen.findByRole('dialog', { name: 'ส่งตัวอย่าง' });
    expect(within(dialog).queryByRole('radiogroup', { name: 'ตามต่อ' })).toBeNull();
  });
});

describe('ภาพรวม: ข้อมูลงาน + one merged timeline', () => {
  const events = [
    { id: 1, kind: 'CREATED', actorName: 'สมชาย ใจดี', createdAt: '2026-07-01T09:00:00.000Z' },
    { id: 2, kind: 'QUOTATION_ISSUED', actorName: 'สมชาย ใจดี', message: 'QT-2026-0201', createdAt: '2026-07-05T09:00:00.000Z' },
  ];
  const activities = {
    items: [
      { id: 11, kind: 'SITE_VISIT', note: 'ส่งตัวอย่างกระเบื้อง', activityDate: '2026-07-06', createdByName: 'สมชาย ใจดี', createdAt: '2026-07-06T09:00:00.000Z' },
      { id: 12, kind: 'CALL', note: 'โทรถามราคา', activityDate: '2026-07-07', createdByName: 'สมชาย ใจดี', createdAt: '2026-07-07T09:00:00.000Z' },
    ],
  };

  it('shows ข้อมูลงาน and the ประวัติ timeline side by side, with no separate tracking or history panels', async () => {
    renderPage();
    await pageReady();
    expect(await screen.findByRole('heading', { name: 'ข้อมูลงาน' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'ประวัติ' })).toBeTruthy();
    expect(screen.queryByRole('heading', { name: 'การติดตามดีล' })).toBeNull();
    expect(screen.queryByRole('heading', { name: 'ประวัติดีล' })).toBeNull();
  });

  it('filters ทั้งหมด / เข้าพบ / เอกสาร narrow the one timeline', async () => {
    setDeal({}, [], { events });
    api.tickets.listActivities.mockResolvedValue(activities);
    renderPage();
    await pageReady();
    const timeline = await screen.findByTestId('deal-timeline');
    await within(timeline).findByText('โทรถามราคา');
    const filters = within(timeline).getByRole('group', { name: 'กรองประวัติ' });
    expect(within(filters).getAllByRole('button').map((b) => b.textContent)).toEqual(['ทั้งหมด', 'เข้าพบ', 'เอกสาร']);
    expect(within(filters).getByRole('button', { name: 'ทั้งหมด' }).getAttribute('aria-pressed')).toBe('true');

    fireEvent.click(within(filters).getByRole('button', { name: 'เข้าพบ' }));
    expect(within(timeline).getByText('ส่งตัวอย่างกระเบื้อง')).toBeTruthy();
    expect(within(timeline).queryByText('โทรถามราคา')).toBeNull();
    expect(within(timeline).queryByText('QT-2026-0201')).toBeNull();

    fireEvent.click(within(filters).getByRole('button', { name: 'เอกสาร' }));
    expect(within(timeline).getByText('QT-2026-0201')).toBeTruthy();
    expect(within(timeline).queryByText('ส่งตัวอย่างกระเบื้อง')).toBeNull();
    expect(within(timeline).queryByText('โทรถามราคา')).toBeNull();

    fireEvent.click(within(filters).getByRole('button', { name: 'ทั้งหมด' }));
    expect(within(timeline).getByText('โทรถามราคา')).toBeTruthy();
    expect(within(timeline).getByText('QT-2026-0201')).toBeTruthy();
  });

  // Slice 6.5: the deal's planned visits, as served to THIS viewer by GET /api/sales/visits/deal/{id}/planned (the
  // server narrows them; the page shows what it is given). Mock-driven: not evidence of the server's scope.
  it('planned visits lead the ประวัติ timeline, each linking to นัดเข้าพบ', async () => {
    const day = addDaysIso(bangkokTodayIso(), 2);
    api.salesVisits.plannedOnDeal.mockResolvedValue([{
      id: 41, ownerId: 1, ownerName: 'สมชาย ใจดี', ticketId: 701, ticketTitle: 'โครงการบ้านสวน',
      contactName: 'คุณอรุณ', plannedDate: day, plannedTime: '10:00:00', status: 'PLANNED', doneAt: null,
      note: 'นำตัวอย่างไปเทียบสี', dealMovedToName: null, dealMoved: false,
    }]);
    setDeal({}, [], { events });
    renderPage();
    await pageReady();
    const timeline = await screen.findByTestId('deal-timeline');
    const row = await within(timeline).findByTestId('timeline-planned-visit');

    expect(api.salesVisits.plannedOnDeal).toHaveBeenCalledWith(701);
    expect(within(timeline).getAllByRole('listitem')[0]).toBe(row);
    expect(within(row).getByText('นัดไว้')).toBeTruthy();
    expect(within(row).getByRole('link').getAttribute('href')).toBe(`/visits?week=${weekStartIso(day)}`);
  });

  // Slice 6d: a SAMPLE_SENT row has its own label and icon in ประวัติ; it is not a visit, so เข้าพบ leaves it out.
  it('a ส่งตัวอย่าง activity shows as ส่งตัวอย่าง with its icon, under ทั้งหมด but not เข้าพบ', async () => {
    setDeal({}, [], { events });
    api.tickets.listActivities.mockResolvedValue({
      items: [{ id: 13, kind: 'SAMPLE_SENT', note: 'ตัวอย่าง Raku สีเทา', activityDate: '2026-07-08', createdByName: 'สมชาย ใจดี', createdAt: '2026-07-08T09:00:00.000Z' }],
    });
    renderPage();
    await pageReady();
    const timeline = await screen.findByTestId('deal-timeline');
    const note = await within(timeline).findByText('ตัวอย่าง Raku สีเทา');
    const row = note.closest('li');
    const badge = within(row).getByText('ส่งตัวอย่าง');
    expect(badge.querySelector('svg') ?? badge.parentElement.querySelector('svg')).toBeTruthy();
    expect(row.textContent).not.toContain('SAMPLE_SENT');
    fireEvent.click(within(within(timeline).getByRole('group', { name: 'กรองประวัติ' })).getByRole('button', { name: 'เข้าพบ' }));
    expect(within(timeline).queryByText('ตัวอย่าง Raku สีเทา')).toBeNull();
  });

  it('"+ บันทึก" with ส่งตัวอย่าง asks what was sent and will not save it blank', async () => {
    renderPage();
    await pageReady();
    const timeline = await screen.findByTestId('deal-timeline');
    fireEvent.click(within(timeline).getByRole('button', { name: '+ บันทึก' }));
    const kind = within(timeline).getByLabelText(/^ประเภท/);
    fireEvent.change(kind, { target: { value: 'SAMPLE_SENT' } });
    const save = within(timeline).getByRole('button', { name: 'บันทึกกิจกรรม' });
    expect(within(timeline).getByLabelText(/ส่งอะไร/)).toBeTruthy();
    expect(save.disabled).toBe(true);
    fireEvent.change(within(timeline).getByLabelText(/ส่งอะไร/), { target: { value: 'แคตตาล็อก' } });
    expect(save.disabled).toBe(false);
    fireEvent.change(kind, { target: { value: 'CALL' } });
    fireEvent.change(within(timeline).getByLabelText(/บันทึก \(ถ้ามี\)/), { target: { value: '' } });
    expect(save.disabled).toBe(false);
  });

  it('the old always-open activity form is gone; "+ บันทึก" opens the note composer', async () => {
    renderPage();
    await pageReady();
    const timeline = await screen.findByTestId('deal-timeline');
    expect(within(timeline).queryByText('บันทึกกิจกรรมติดตาม')).toBeNull();
    expect(within(timeline).queryByPlaceholderText('เพิ่มความคิดเห็น…')).toBeNull();
    fireEvent.click(within(timeline).getByRole('button', { name: '+ บันทึก' }));
    expect(within(timeline).getByText('บันทึกกิจกรรมติดตาม')).toBeTruthy();
    expect(within(timeline).getByPlaceholderText('เพิ่มความคิดเห็น…')).toBeTruthy();
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

  // Dialog names below follow the rebuilt dialogs (drawings m-hold / m-lost / m-cancel, 2026-11-16); they used to
  // be the old dialogs' 'พักดีลไว้' / 'ทำเครื่องหมายเสียงาน' / 'ยกเลิกดีล' / 'พัก dormant'. The gates are unchanged.
  it('พักงาน (was พักดีลไว้ in ⋯): shown with PLACE_ON_HOLD on an active deal, opens the พักงาน dialog', async () => {
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

  it('ปิดงาน (เสียงาน) (was เสียงาน in the danger zone): shown with MARK_LOST, opens the existing mark-lost dialog', async () => {
    setDeal({}, ['MARK_LOST']);
    await overviewReady(salesOwner);
    fireEvent.click(await screen.findByRole('button', { name: 'ปิดงาน (เสียงาน)' }));
    expect(await screen.findByRole('dialog', { name: 'ปิดงาน · เสียงาน' })).toBeTruthy();
  });

  it('wrong-way-round: no ปิดงาน (เสียงาน) without MARK_LOST, nor at CLOSED_PAID', async () => {
    setDeal({}, []);
    const first = renderPage();
    await pageReady();
    await settle();
    expect(screen.queryByRole('button', { name: 'ปิดงาน (เสียงาน)' })).toBeNull();
    first.unmount();
    setDeal({ salesStage: 'CLOSED_PAID' }, ['MARK_LOST']);
    await overviewReady(salesOwner);
    expect(screen.queryByRole('button', { name: 'ปิดงาน (เสียงาน)' })).toBeNull();
  });

  it('ยกเลิกงาน (was ยกเลิก in the danger zone): the owner with CANCEL, opens the existing cancel dialog', async () => {
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

  it('no red "จัดการดีล" danger zone is rendered any more', async () => {
    setDeal({}, ['MARK_LOST', 'CANCEL', 'PLACE_ON_HOLD']);
    await overviewReady(salesOwner);
    expect(screen.queryByText('จัดการดีล')).toBeNull();
    expect(screen.queryByRole('heading', { name: /จัดการ/ })).toBeNull();
  });

  it('พัก dormant on a paused deal (was a button in the paused banner) is in ⋯ with MARK_DORMANT', async () => {
    setDeal({ lifecycle: 'ON_HOLD' }, ['MARK_DORMANT', 'RESUME']);
    await overviewReady(salesOwner);
    fireEvent.click(screen.getByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'พัก dormant' }));
    const pause = await screen.findByRole('dialog', { name: 'พักงาน' });
    expect(within(pause).getByRole('radio', { name: /ไม่เคลื่อนไหว/ }).getAttribute('aria-checked')).toBe('true');
  });

  // Fix round 1, ruling 5: a paused deal's return date is changed in place from the ขั้นต่อไป card, behind the same
  // gate as ดำเนินการต่อ (RESUME — listed exactly when the caller may hold / dormant the deal).
  it('เปลี่ยนวันที่จะกลับมาดู sits beside ดำเนินการต่อ on a paused deal with RESUME, and opens the date dialog', async () => {
    setDeal({ lifecycle: 'DORMANT', pauseReturnOn: '2099-03-01' }, ['RESUME']);
    await overviewReady(salesOwner);
    fireEvent.click(await screen.findByRole('button', { name: 'เปลี่ยนวันที่จะกลับมาดู' }));
    const modal = await screen.findByRole('dialog', { name: 'เปลี่ยนวันที่จะกลับมาดู' });
    expect(within(modal).getByRole('radio', { name: /ไม่เคลื่อนไหว/ }).getAttribute('aria-checked')).toBe('true');
  });

  it('wrong-way-round: no เปลี่ยนวันที่จะกลับมาดู without RESUME, nor on an active deal', async () => {
    setDeal({ lifecycle: 'ON_HOLD' }, []);
    const first = renderPage();
    await pageReady();
    await settle();
    expect(screen.queryByRole('button', { name: 'เปลี่ยนวันที่จะกลับมาดู' })).toBeNull();
    first.unmount();
    setDeal({}, ['RESUME', 'PLACE_ON_HOLD']);
    await overviewReady(salesOwner);
    expect(screen.queryByRole('button', { name: 'เปลี่ยนวันที่จะกลับมาดู' })).toBeNull();
  });

  it('wrong-way-round: no พัก dormant on a paused deal without MARK_DORMANT', async () => {
    setDeal({ lifecycle: 'ON_HOLD' }, ['RESUME']);
    await overviewReady(salesOwner);
    expect(screen.queryByRole('button', { name: 'การดำเนินการเพิ่มเติม' })).toBeNull();
  });

  it('logging any activity (was the always-open form in ประวัติ) is under + บันทึก for the deal’s writers', async () => {
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

  it('wrong-way-round: on a cancelled deal there is nothing to note, so no + บันทึก for a non-writer', async () => {
    setDeal({ lifecycle: 'CANCELLED', status: 'cancelled' }, []);
    await overviewReady(otherSales);
    expect(within(screen.getByTestId('deal-timeline')).queryByRole('button', { name: '+ บันทึก' })).toBeNull();
  });

  it('แก้ไข on ข้อมูลงาน (was แก้ไขข้อมูลติดตาม) for the owner, sales_manager and ceo', async () => {
    for (const user of [salesOwner, salesManager, ceo]) {
      const view = renderPage(user);
      await pageReady();
      const card = (await screen.findByRole('heading', { name: 'ข้อมูลงาน' })).closest('section');
      fireEvent.click(within(card).getByRole('button', { name: 'แก้ไข' }));
      expect(within(card).getByText('วันติดตามครั้งถัดไป')).toBeTruthy();
      view.unmount();
    }
  });

  it('wrong-way-round: another rep sees ข้อมูลงาน but cannot edit it', async () => {
    await overviewReady(otherSales);
    const card = (await screen.findByRole('heading', { name: 'ข้อมูลงาน' })).closest('section');
    expect(within(card).queryByRole('button', { name: 'แก้ไข' })).toBeNull();
  });

  it('file upload (was in ประวัติ) is on ไฟล์ for a participant on an open deal', async () => {
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

describe('ใบเสนอราคา tab', () => {
  const approved = {
    id: 201, number: 'QT-2026-0201', docStatus: 'APPROVED', origin: 'DEAL_DIRECT', recipientType: 'DESIGNER', grandTotal: 1240000,
    createdAt: '2026-11-03T09:00:00Z', approvedAt: '2026-11-03T09:00:00Z', revisionNo: 1,
    items: [
      { id: 1, seq: 1, descriptionLine: 'Equipe Raku 20×20 · Matt White', quantityMode: 'AREA', areaSqm: 180, piecesFinal: 4500, netUnitPrice: 2900, lineAmount: 522000 },
    ],
  };
  const draft = { ...approved, id: 214, number: 'QT-2026-0214', docStatus: 'DRAFT', items: [{ id: 2, seq: 1, descriptionLine: 'ร่างอย่าลืม', quantityMode: 'PIECES', piecesFinal: 10, netUnitPrice: 1, lineAmount: 10 }] };

  // Fix round 1 (ruling 1): no independent "+ สร้างใบเสนอราคา" link — the tab carries at most a second trigger for
  // the primary's route choice (TicketDetailPage.test.jsx pins its states).
  it('leads with ใบเสนอราคาของงานนี้ (no link of its own), then สินค้าในใบล่าสุด, then the items and คำขอราคา', async () => {
    api.dealQuotations.listForTicket.mockResolvedValue({ items: [draft, approved] });
    renderPage();
    await pageReady();
    // Adapted (slice 6.1): with two quotations in force the tab reads "ใบเสนอราคา 2" (its count is in-band), so the
    // tab is found by its leading label.
    await openTab(/^ใบเสนอราคา/);
    const quotations = await screen.findByTestId('deal-quotations-card');
    expect(within(quotations).getByRole('heading', { name: 'ใบเสนอราคาของงานนี้' })).toBeTruthy();
    expect(document.querySelector('a[href*="/quotations/new"]')).toBeNull();
    const latest = await screen.findByTestId('latest-quotation-items');
    expect(within(latest).getByText(/QT-2026-0201/)).toBeTruthy();
    expect(within(latest).getByText('Equipe Raku 20×20 · Matt White')).toBeTruthy();
    expect(within(latest).queryByText('ร่างอย่าลืม')).toBeNull();
    expect(screen.getByRole('heading', { name: /^รายการสินค้า/ })).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'คำขอราคา' })).toBeTruthy();
  });

});

describe('เงิน tab', () => {
  it('three KPI tiles, then รับแล้ว (with its source chip) beside เอกสาร, then เงินที่คาดว่าจะเข้า', async () => {
    api.tickets.listPayments.mockResolvedValue({
      items: [{ receiptId: 1, kind: 'DEPOSIT', amount: 456150, source: 'EXPRESS', receivedAt: '2026-10-30T09:00:00Z', recordedByName: 'บัญชี' }],
    });
    renderPage();
    await pageReady();
    await openTab('เงิน');
    const kpis = await screen.findByTestId('money-kpis');
    const tile = (label) => within(kpis).getByText(label).closest('[data-testid="money-kpi"]');
    expect(within(tile('มูลค่างาน')).getByText('฿912,300.00')).toBeTruthy();
    expect(within(tile('รับแล้ว')).getByText('฿456,150.00')).toBeTruthy();
    expect(within(tile('คงค้าง')).getByText('฿456,150.00')).toBeTruthy();
    const received = screen.getByTestId('money-received');
    expect(within(received).getByRole('heading', { name: 'รับแล้ว' })).toBeTruthy();
    expect(await within(received).findByText('Express')).toBeTruthy();
    expect(within(screen.getByTestId('money-documents')).getByRole('heading', { name: 'เอกสาร' })).toBeTruthy();
    expect(await screen.findByText('เงินที่คาดว่าจะเข้า')).toBeTruthy();
  });
});

describe('ส่งของ tab', () => {
  it('shows the delivery list as สั่ง / ส่งแล้ว / เหลือ / ที่มา', async () => {
    renderPage();
    await pageReady();
    await openTab('ส่งของ');
    const table = await screen.findByRole('table', { name: 'การส่งสินค้าให้ลูกค้า' });
    expect(within(table).getAllByRole('columnheader').map((th) => th.textContent)).toEqual(['รายการ', 'สั่ง', 'ส่งแล้ว', 'เหลือ', 'ที่มา']);
    const [, raku, splendours] = within(table).getAllByRole('row');
    expect(within(raku).getAllByRole('cell').map((td) => td.textContent)).toEqual(['Equipe Raku', '120', '120', '0', 'คลัง']);
    expect(within(splendours).getAllByRole('cell').map((td) => td.textContent)).toEqual(['Equipe Splendours', '64', '0', '64', 'นำเข้า']);
  });

  it('marks an issued factory tracker ดูอย่างเดียว, with the hint that import moves it on การขนส่ง', async () => {
    api.storedImportRequests.listForTicket.mockResolvedValue({
      importRequests: [{
        id: 41, ticketId: 701, factoryName: 'Equipe Cerámicas', status: 'ISSUED', version: 1, docNumber: 'IR-2026-0041',
        importStep: 'IN_TRANSIT', importStepAt: '2026-11-06T00:00:00Z', lines: [], items: [],
      }],
    });
    setDeal({ salesStage: 'PROCUREMENT', fulfillmentStatus: 'IR_ISSUED' }, []);
    // Fix round 1 (L5): the chip is for a viewer the card offers nothing to edit — sales_manager reads stored IRs
    // but writes none of them. (The owner may still issue a revision: wrong-way case below.)
    const view = renderPage(salesManager);
    await pageReady();
    await openTab('ส่งของ');
    const card = await screen.findByTestId('ir-factory-card-41');
    expect(within(card).getByText('ดูอย่างเดียว')).toBeTruthy();
    expect(within(card).getByTestId('ir-step-readonly-41').textContent).toContain('การขนส่ง');
    view.unmount();

    renderPage(salesOwner);
    await pageReady();
    await openTab('ส่งของ');
    const ownerCard = await screen.findByTestId('ir-factory-card-41');
    expect(within(ownerCard).queryByText('ดูอย่างเดียว')).toBeNull();
  });

  // Fix round 1 (ruling 8): before the procurement source is decided, nothing is known to come from import.
  it('ที่มา says the source is not decided yet while the procurement source is pending', async () => {
    api.tickets.getProcurementSource.mockResolvedValue({ legacy: false, pending: true, editable: true, decidedAt: null, quotationNumber: null, lines: [] });
    renderPage();
    await pageReady();
    await openTab('ส่งของ');
    const table = await screen.findByRole('table', { name: 'การส่งสินค้าให้ลูกค้า' });
    await waitFor(() => {
      const [, raku, splendours] = within(table).getAllByRole('row');
      expect(within(splendours).getAllByRole('cell')[4].textContent).toBe('ยังไม่กำหนด');
      // Reserved from stock is a fact either way.
      expect(within(raku).getAllByRole('cell')[4].textContent).toBe('คลัง');
    });
    expect(within(table).queryByText('นำเข้า')).toBeNull();
  });
});

// Fix round 1: the reviewer's findings, each pinned (behaviour where jsdom can show it, class presence where only a real
// browser can — each test says which).
describe('fix round 1', () => {
  const ON_HOLD_RESUME_ONLY = [{ lifecycle: 'ON_HOLD' }, ['RESUME']];
  function mobileViewport() {
    const original = window.matchMedia;
    window.matchMedia = (query) => ({
      matches: /max-width:\s*720px/.test(query), media: query, addEventListener() {}, removeEventListener() {},
      addListener() {}, removeListener() {}, onchange: null, dispatchEvent: () => false,
    });
    return () => { window.matchMedia = original; };
  }
  const pageRoot = () => screen.getByTestId('ticket-detail-sticky-chrome').parentElement;

  it('M0 (behaviour): on a phone the ⋯ menu opens downward from its place at the top', async () => {
    const restore = mobileViewport();
    try {
      setDeal({ status: 'approved' }, ['UPDATE_STAGE', 'MARK_DORMANT']);
      api.tickets.actions.mockResolvedValue({
        currentState: { lifecycle: 'ACTIVE', salesStage: 'SPEC_APPROVED' },
        availableActions: [{ action: 'UPDATE_STAGE' }, { action: 'MARK_DORMANT' }],
        stageDecisions: [{ stage: 'LEAD_APPROACH', allowed: true, onRoute: true }],
      });
      renderPage();
      await pageReady();
      fireEvent.click(await screen.findByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));
      const menu = await screen.findByRole('menu');
      expect(menu.style.top).not.toBe('');
      expect(menu.style.bottom).toBe('');
    } finally {
      restore();
    }
  });

  it.each([
    ['a paused deal (ดำเนินการต่อ)', ...ON_HOLD_RESUME_ONLY, salesOwner],
    ['a lost deal (เปิดงานอีกครั้ง)', { lifecycle: 'CLOSED_LOST', lostReason: 'PRICE' }, ['REOPEN'], salesOwner],
    ['a "ไปที่…" tab button (sales_manager at DELIVERED)', { salesStage: 'DELIVERED' }, [], salesManager],
  ])('M1 (class presence): the page keeps room for the fixed bottom bar whenever it shows — %s', async (_label, summary, actions, user) => {
    setDeal(summary, actions);
    renderPage(user);
    await pageReady();
    const card = await screen.findByTestId('next-step-card');
    await within(card).findByTestId('ticket-action-bar');
    expect(pageRoot().className).toContain('mobile:pb-36');
  });

  it('M1 wrong-way (class presence): no bottom room when there is no bar (a cancelled deal)', async () => {
    setDeal({ lifecycle: 'CANCELLED', status: 'cancelled' }, []);
    renderPage();
    await pageReady();
    await settle();
    expect(screen.queryByTestId('ticket-action-bar')).toBeNull();
    expect(pageRoot().className).not.toContain('mobile:pb-36');
  });

  it('M2 (behaviour): a lost deal says why and when it was lost, and the stage it was left at', async () => {
    setDeal({ lifecycle: 'CLOSED_LOST', salesStage: 'QUOTE_OWNER', lostReason: 'PRICE', lostAt: '2026-11-01T03:00:00Z' }, ['REOPEN']);
    renderPage();
    await pageReady();
    const card = await screen.findByTestId('next-step-card');
    await waitFor(() => expect(card.textContent).toContain('ราคา'));
    expect(card.textContent).toContain('1 พ.ย. 2569');
    expect(card.textContent).toContain('5. เสนอราคาเจ้าของโครงการ');
  });

  it('M2 (behaviour): a paused deal names the stage it was left at', async () => {
    setDeal({ lifecycle: 'DORMANT', salesStage: 'QUOTE_OWNER' }, ['RESUME']);
    renderPage();
    await pageReady();
    const card = await screen.findByTestId('next-step-card');
    await waitFor(() => expect(card.textContent).toContain('5. เสนอราคาเจ้าของโครงการ'));
    expect(card.textContent).toContain('พักยาว (dormant)');
  });

  it('M3 (behaviour): unsaved item edits survive a tab switch', async () => {
    setDeal({ status: 'draft' }, ['EDIT_ITEMS']);
    renderPage();
    await pageReady();
    await openTab('ใบเสนอราคา');
    fireEvent.click(await screen.findByRole('button', { name: /แก้ไขรายการสินค้า/ }));
    fireEvent.change(document.getElementById('edit-item-0-size'), { target: { value: '99x99 ทดสอบ' } });
    await openTab('ภาพรวม');
    await openTab('ใบเสนอราคา');
    expect((await screen.findByDisplayValue('99x99 ทดสอบ')).id).toBe('edit-item-0-size');
    expect(screen.getByRole('button', { name: 'บันทึกการแก้ไข' })).toBeTruthy();
  });

  // M12: the old paused banner offered พัก dormant on ON_HOLD only (DealStagePanel: `canDormant && lifecycle ===
  // 'ON_HOLD'`); the ⋯ copy keeps exactly that predicate even if MARK_DORMANT were listed on a DORMANT deal.
  it('M12 wrong-way (behaviour): no พัก dormant in ⋯ on a deal already DORMANT', async () => {
    setDeal({ lifecycle: 'DORMANT' }, ['RESUME', 'MARK_DORMANT']);
    renderPage();
    await pageReady();
    await settle();
    expect(screen.queryByRole('button', { name: 'การดำเนินการเพิ่มเติม' })).toBeNull();
  });

  it('L3 (behaviour): ONE "ถัดไป" statement, inside the ขั้นต่อไป card — none under the tab bar', async () => {
    setDeal({ salesStage: 'QUOTE_DESIGN_SIDE' }, []);
    renderPage();
    await pageReady();
    await waitFor(() => expect(screen.getAllByTestId('deal-stage-next')).toHaveLength(1));
    expect(screen.getByTestId('next-step-card').contains(screen.getByTestId('deal-stage-next'))).toBe(true);
    expect(within(screen.getByTestId('deal-stage-panel')).queryByText(/^ถัดไป/)).toBeNull();
  });

  it('L4 (behaviour): the h1 falls back to the deal code when the deal has no name', async () => {
    setDeal({ projectName: null, title: null, customerName: null }, []);
    renderPage();
    const header = await pageReady();
    expect(within(header).getByRole('heading', { level: 1 }).textContent).toBe('PR-2026-0701');
  });

  it('M10 (behaviour): "ไปที่<tab>" goes to a tab the viewer can see', async () => {
    setDeal({ salesStage: 'DELIVERED' }, []);
    renderPage(salesManager);
    await pageReady();
    fireEvent.click(await screen.findByTestId('next-step-tab'));
    await waitFor(() => expect(screen.getByRole('tab', { name: 'เงิน' }).getAttribute('aria-selected')).toBe('true'));
  });

  it('M10 wrong-way (behaviour): no "ไปที่<tab>" to a tab this role cannot see (import, money step)', async () => {
    setDeal({ salesStage: 'DELIVERED' }, []);
    renderPage({ id: 8, employeeId: 8, name: 'นำเข้า', role: 'import' });
    await pageReady();
    await settle();
    expect(screen.queryByRole('tab', { name: 'เงิน' })).toBeNull();
    expect(screen.queryByTestId('next-step-tab')).toBeNull();
  });

  // jsdom cannot evaluate prefers-reduced-motion or lay anything out; these pin the classes that do it in a browser.
  it('M32 (class presence): the header and its collapsing region stop animating under prefers-reduced-motion', async () => {
    const header = await (renderPage(), pageReady());
    expect(header.className).toContain('motion-reduce:transition-none');
    expect(within(header).getByTestId('deal-header-extras').className).toContain('motion-reduce:transition-none');
    expect(within(header).getByRole('heading', { level: 1 }).className).toContain('motion-reduce:transition-none');
  });

  it('M33/M34 (class presence): tab buttons and quiet links are 44px tap targets', async () => {
    setDeal({}, ['PLACE_ON_HOLD', 'MARK_LOST', 'CANCEL']);
    renderPage();
    await pageReady();
    expect(screen.getByRole('tablist').closest('[class*="min-h-[44px]"]')).not.toBeNull();
    const links = within(await screen.findByRole('navigation', { name: 'จัดการงาน' })).getAllByRole('button');
    expect(links).toHaveLength(3);
    links.forEach((link) => expect(link.className).toContain('min-h-[44px]'));
  });

  it('M35 (class presence): the stepper scrolls inside its own wrapper, never widening the page', async () => {
    renderPage();
    await pageReady();
    const list = screen.getByRole('list', { name: 'ขั้นของงาน' });
    expect(list.className).toMatch(/min-w-\[/);
    expect(list.parentElement.className).toContain('overflow-x-auto');
  });

  it('M42 (behaviour): while the header is condensed the ขั้นต่อไป card is not rendered; its button moves to the bar', async () => {
    const ios = [];
    const original = globalThis.IntersectionObserver;
    globalThis.IntersectionObserver = class {
      constructor(cb) { this.cb = cb; this.elements = []; ios.push(this); }
      observe(el) { this.elements.push(el); }
      unobserve() {}
      disconnect() {}
      takeRecords() { return []; }
    };
    try {
      setDeal({ salesStage: 'LEAD_APPROACH' }, [{ action: 'ADVANCE_STAGE', targetStage: 'PRESENTATION' }]);
      renderPage();
      const header = await pageReady();
      await within(header).findByTestId('ticket-primary-action');
      const io = ios.find((o) => o.elements.length >= 2);
      const condense = document.querySelector('[data-testid="deal-chrome-condense-sentinel"]');
      act(() => io.cb([{ target: condense, isIntersecting: false }]));
      expect(screen.getByTestId('deal-state-header').getAttribute('data-condensed')).toBe('true');
      expect(screen.queryByTestId('next-step-card')).toBeNull();
      expect(within(screen.getByTestId('ticket-action-bar')).getByTestId('ticket-primary-action')).toBeTruthy();
    } finally {
      globalThis.IntersectionObserver = original;
    }
  });

  it('wording (behaviour): the cancel toast says งาน', async () => {
    setDeal({}, ['CANCEL']);
    api.tickets.cancel.mockResolvedValue({ ticket: buildTicket() });
    const { showToast } = renderPage();
    await pageReady();
    fireEvent.click(await screen.findByRole('button', { name: 'ยกเลิกงาน' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getAllByRole('radio')[0]);
    fireEvent.click(within(dialog).getAllByRole('button').at(-1));
    await waitFor(() => expect(showToast).toHaveBeenCalledWith('success', 'ยกเลิกงานแล้ว'));
  });

  it('wording (behaviour): no ดีล behind ดูขั้นตอนทั้งหมด, including the route remedy', async () => {
    api.tickets.get.mockResolvedValue({ ticket: buildTicket({ summary: { salesStage: 'SPEC_APPROVED', entryChannel: 'OWNER_DIRECT' } }) });
    api.tickets.actions.mockResolvedValue({
      currentState: { lifecycle: 'ACTIVE', salesStage: 'SPEC_APPROVED' },
      availableActions: [{ action: 'SET_ENTRY_CHANNEL', requiredFields: ['value'] }],
      stageDecisions: [
        { stage: 'QUOTE_DESIGN_SIDE', allowed: false, onRoute: false, blockedReason: null },
        { stage: 'QUOTE_OWNER', allowed: true, onRoute: true },
      ],
    });
    renderPage();
    await pageReady();
    fireEvent.click(await screen.findByRole('button', { name: /ดูขั้นตอนทั้งหมด/ }));
    const panel = screen.getByTestId('deal-stage-panel');
    await waitFor(() => expect(panel.textContent).toContain('ไม่อยู่ในเส้นทาง'));
    // The entry-channel names are งาน too since 2026-11-17 ("ผู้ออกแบบนำงานมา"): nothing is allowed.
    const text = panel.textContent;
    expect([...text.matchAll(/ดีล/g)].map((m) => text.slice(Math.max(0, m.index - 20), m.index + 20))).toEqual([]);
  });
});

describe('ไฟล์ tab', () => {
  it('is today’s เอกสาร tab without the quotation list that moved to ใบเสนอราคา', async () => {
    renderPage();
    await pageReady();
    await openTab('ไฟล์');
    expect(await screen.findByTestId('deal-document-register')).toBeTruthy();
    expect(screen.queryByTestId('register-quotations')).toBeNull();
    expect(screen.getByTestId('register-deposit')).toBeTruthy();
    expect(screen.getByTestId('register-attachments')).toBeTruthy();
  });
});

describe('wording: งาน, not ดีล', () => {
  // Deal DATA (names, codes) and the dialogs (redesigned in a later task) are allowed; this page's own labels are not.
  // No enum label is allowed any more: the entry channel's name is "ผู้ออกแบบนำงานมา" since 2026-11-17.
  const ALLOWED = [];
  function visibleText() {
    const clone = document.body.cloneNode(true);
    clone.querySelectorAll('[role="dialog"], [data-testid="location-probe"]').forEach((node) => node.remove());
    return ALLOWED.reduce((text, label) => text.split(label).join('·'), clone.textContent);
  }

  it.each(['ภาพรวม', 'ใบเสนอราคา', 'เงิน', 'ส่งของ', 'ไฟล์'])('no ดีล on the %s tab', async (tab) => {
    setDeal({}, ['PLACE_ON_HOLD', 'MARK_LOST', 'CANCEL', 'UPDATE_STAGE', 'MARK_DORMANT']);
    renderPage();
    await pageReady();
    await openTab(tab);
    await settle();
    // Each hit with 25 characters either side, so a failure names the label to fix.
    const text = visibleText();
    const hits = [...text.matchAll(/ดีล/g)].map((m) => text.slice(Math.max(0, m.index - 25), m.index + 28));
    expect(hits).toEqual([]);
  });
});

// Fix round 2.
describe('fix round 2', () => {
  // Item 1: per-deal client state never crosses from one deal to another. TicketDetailRoute keys the page by the
  // route id, so moving from deal A to deal B mounts a fresh page — every piece of state (the unsaved item edit, the
  // tab, open dialogs, the timeline filter and "+ บันทึก" draft, the call / visit drafts) starts over.
  function Nav() {
    const navigate = useNavigate();
    return (
      <>
        <button type="button" onClick={() => navigate('/tickets/702')}>go-702</button>
        <button type="button" onClick={() => navigate('/tickets/701')}>go-701</button>
      </>
    );
  }
  function renderRoute() {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    return render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/tickets/701']}>
          <Nav />
          <Routes>
            <Route
              path="/tickets/:id"
              element={<React.Suspense fallback="loading"><TicketDetailRoute user={salesOwner} showToast={vi.fn()} /></React.Suspense>}
            />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
  }
  function twoDeals() {
    api.tickets.get.mockImplementation((id) => Promise.resolve({
      ticket: buildTicket({
        summary: String(id) === '702'
          ? { id: 702, code: 'PR-2026-0702', projectName: 'โครงการบี', title: 'โครงการบี', status: 'draft' }
          : { id: 701, status: 'draft' },
      }),
    }));
    api.tickets.actions.mockResolvedValue({
      currentState: { lifecycle: 'ACTIVE', salesStage: 'SPEC_APPROVED' },
      availableActions: [{ action: 'EDIT_ITEMS' }],
      stageDecisions: [],
    });
  }
  const heading = () => screen.findByRole('heading', { level: 1 });
  async function editItemOnFirstDeal() {
    await waitFor(async () => expect((await heading()).textContent).toBe('โครงการบ้านสวน'));
    fireEvent.click(await screen.findByRole('tab', { name: 'ใบเสนอราคา' }));
    fireEvent.click(await screen.findByRole('button', { name: /แก้ไขรายการสินค้า/ }));
    fireEvent.change(document.getElementById('edit-item-0-size'), { target: { value: 'ของดีล 701' } });
    fireEvent.click(screen.getByRole('tab', { name: 'ภาพรวม' }));
  }

  it('an unsaved item edit on deal A never appears on deal B', async () => {
    twoDeals();
    renderRoute();
    await editItemOnFirstDeal();
    fireEvent.click(screen.getByRole('button', { name: 'go-702' }));
    await waitFor(async () => expect((await heading()).textContent).toBe('โครงการบี'));
    fireEvent.click(await screen.findByRole('tab', { name: 'ใบเสนอราคา' }));
    await screen.findByRole('heading', { name: /^รายการสินค้า/ });
    expect(screen.queryByDisplayValue('ของดีล 701')).toBeNull();
    expect(screen.queryByRole('button', { name: 'บันทึกการแก้ไข' })).toBeNull();
  });

  it('A → B → A: the unsaved edit on A is gone, not half-restored (decided: gone)', async () => {
    twoDeals();
    renderRoute();
    await editItemOnFirstDeal();
    fireEvent.click(screen.getByRole('button', { name: 'go-702' }));
    await waitFor(async () => expect((await heading()).textContent).toBe('โครงการบี'));
    fireEvent.click(screen.getByRole('button', { name: 'go-701' }));
    await waitFor(async () => expect((await heading()).textContent).toBe('โครงการบ้านสวน'));
    fireEvent.click(await screen.findByRole('tab', { name: 'ใบเสนอราคา' }));
    await screen.findByRole('heading', { name: /^รายการสินค้า/ });
    expect(screen.queryByDisplayValue('ของดีล 701')).toBeNull();
    expect(screen.queryByRole('button', { name: 'บันทึกการแก้ไข' })).toBeNull();
  });

  it('within ONE deal the unsaved edit still survives a tab switch (round-1 ruling 5)', async () => {
    twoDeals();
    renderRoute();
    await editItemOnFirstDeal();
    fireEvent.click(screen.getByRole('tab', { name: 'ใบเสนอราคา' }));
    expect(await screen.findByDisplayValue('ของดีล 701')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'บันทึกการแก้ไข' })).toBeTruthy();
  });

  // Item 2: for the four server-gated buttons the card's title is the button's own label, and the explanatory sentence
  // appears once, beneath it.
  it.each([
    ['ลูกค้ายืนยัน', salesOwner, { status: 'quotation_issued', paymentStatus: null }, 'CONFIRM_CUSTOMER', 'ยืนยันว่าลูกค้าตกลงคำสั่งซื้อแล้ว'],
    ['ยืนยันชำระครบ (Final Payment)', { id: 5, employeeId: 5, name: 'บัญชี', role: 'account' }, { status: 'quotation_issued' }, 'FINAL_PAYMENT', 'ยืนยันว่าลูกค้าชำระส่วนที่เหลือครบแล้ว'],
    ['ยืนยันพร้อมปิดงาน', { id: 5, employeeId: 5, name: 'บัญชี', role: 'account' }, {}, 'CONFIRM_CLOSE', 'ส่งมอบและรับเงินครบแล้ว — ยืนยันเพื่อส่งให้ CEO ตรวจสอบปิดงาน'],
    ['ตรวจสอบและปิดงาน', ceo, {}, 'VERIFY_CLOSE', 'ฝ่ายบัญชียืนยันแล้ว — ตรวจสอบและปิดงานได้เลย'],
  ])('%s: the title is the button label and the sentence is said once', async (label, user, summary, action, sentence) => {
    setDeal(summary, [action]);
    renderPage(user);
    await pageReady();
    const card = await screen.findByTestId('next-step-card');
    const button = await within(card).findByRole('button', { name: label });
    expect(button.textContent.trim()).toBe(label);
    expect(within(card).getByTestId('next-step-title').textContent).toBe(label);
    expect(card.textContent.split(sentence)).toHaveLength(2);
  });

  // Item 3: like the old lost banner, a lost deal with no recorded reason (or date) still says so, with "-".
  it('a lost deal with no recorded reason or date shows both lines with "-"', async () => {
    setDeal({ lifecycle: 'CLOSED_LOST', salesStage: 'QUOTE_OWNER', lostReason: null, lostAt: null }, []);
    renderPage();
    await pageReady();
    const facts = await screen.findByTestId('next-step-facts');
    expect(facts.textContent).toContain('เหตุผล: -');
    expect(facts.textContent).toContain('ปิดเมื่อ -');
  });
});

// ── slice 6.1 / 6.3: drawn details on the deal page ─────────────────────────────────────────────────────────────
describe('drawn details (slice 6)', () => {
  it('the header says คาดปิด from the served expectedQuarter, in the board\'s style', async () => {
    setDeal({ expectedQuarter: '2026-Q4' });
    renderPage();
    const header = await pageReady();
    expect(within(header).getByText('คาดปิด Q4/69')).not.toBeNull();
  });

  it('no expectedQuarter, no คาดปิด in the header', async () => {
    setDeal({ expectedQuarter: null });
    renderPage();
    const header = await pageReady();
    expect(within(header).queryByText(/คาดปิด/)).toBeNull();
  });

  it('the ใบเสนอราคา tab counts only quotations still in force; ไฟล์ counts the files the page already loads', async () => {
    api.dealQuotations.listForTicket.mockResolvedValue({ items: [
      { id: 1, ticketId: 701, number: 'QT-1', docStatus: 'APPROVED' },
      { id: 2, ticketId: 701, number: 'QT-2', docStatus: 'DRAFT' },
      { id: 3, ticketId: 701, number: 'QT-3', docStatus: 'SUPERSEDED' },
      { id: 4, ticketId: 701, number: 'QT-4', docStatus: 'CANCELLED' },
    ] });
    api.attachments.list.mockResolvedValue({ attachments: [
      { id: 11, fileName: 'a.pdf' }, { id: 12, fileName: 'b.pdf' }, { id: 13, fileName: 'c.pdf' },
    ] });
    renderPage();
    await pageReady();

    const quote = screen.getByRole('tab', { name: /^ใบเสนอราคา/ });
    const files = screen.getByRole('tab', { name: /^ไฟล์/ });
    await waitFor(() => expect(within(quote).getByText('2')).not.toBeNull());
    await waitFor(() => expect(within(files).getByText('3')).not.toBeNull());
  });

  it('on a phone the counts step aside so ไฟล์ stays on screen (the badge hides under the mobile breakpoint)', async () => {
    api.dealQuotations.listForTicket.mockResolvedValue({ items: [{ id: 1, ticketId: 701, number: 'QT-1', docStatus: 'APPROVED' }] });
    api.attachments.list.mockResolvedValue({ attachments: [{ id: 11, fileName: 'a.pdf' }] });
    renderPage();
    await pageReady();

    const quote = screen.getByRole('tab', { name: /^ใบเสนอราคา/ });
    const badge = await within(quote).findByText('1');
    expect(badge.className).toContain('mobile:hidden');
    const filesBadge = await within(screen.getByRole('tab', { name: /^ไฟล์/ })).findByText('1');
    expect(filesBadge.className).toContain('mobile:hidden');
  });

  it('no quotations and no files: the tabs carry no count at all', async () => {
    renderPage();
    await pageReady();
    await settle();
    expect(tabNames()).toEqual(['ภาพรวม', 'ใบเสนอราคา', 'เงิน', 'ส่งของ', 'ไฟล์']);
  });
});
