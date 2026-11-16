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
import { MemoryRouter, useLocation } from 'react-router-dom';
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
      salesVisits: { create: vi.fn() },
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
    expect(steps[2].textContent).toContain('อัตโนมัติ');
    expect(steps[7].textContent).toContain('อัตโนมัติ');
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
    expect(within(card).getByTestId('next-step-title').textContent).toBe('นำเสนอสินค้าจนผู้ออกแบบตกลงสเปค');
    expect(await within(card).findAllByTestId('ticket-primary-action')).toHaveLength(1);
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

  it('wrong-way-round: a paused deal keeps โทรแล้ว but not นัดเข้าพบ (a visit needs an open deal)', async () => {
    setDeal({ lifecycle: 'ON_HOLD' }, []);
    renderPage();
    await pageReady();
    expect(await screen.findByRole('button', { name: 'โทรแล้ว' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'นัดเข้าพบ' })).toBeNull();
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

  it('พักงาน (was พักดีลไว้ in ⋯): shown with PLACE_ON_HOLD on an active deal, opens the existing dialog', async () => {
    setDeal({}, ['PLACE_ON_HOLD']);
    await overviewReady(salesOwner);
    fireEvent.click(await screen.findByRole('button', { name: 'พักงาน' }));
    expect(await screen.findByRole('dialog', { name: 'พักดีลไว้' })).toBeTruthy();
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
    expect(await screen.findByRole('dialog', { name: 'ทำเครื่องหมายเสียงาน' })).toBeTruthy();
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
    expect(await screen.findByRole('dialog', { name: 'ยกเลิกดีล' })).toBeTruthy();
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
    expect(await screen.findByRole('dialog', { name: 'พัก dormant' })).toBeTruthy();
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

  it('leads with ใบเสนอราคาของงานนี้ and + สร้างใบเสนอราคา, then สินค้าในใบล่าสุด, then the items and คำขอราคา', async () => {
    api.dealQuotations.listForTicket.mockResolvedValue({ items: [draft, approved] });
    renderPage();
    await pageReady();
    await openTab('ใบเสนอราคา');
    const quotations = await screen.findByTestId('deal-quotations-card');
    expect(within(quotations).getByRole('heading', { name: 'ใบเสนอราคาของงานนี้' })).toBeTruthy();
    expect(within(quotations).getByRole('link', { name: '+ สร้างใบเสนอราคา' }).getAttribute('href')).toBe('/quotations/new?ticket=701');
    const latest = await screen.findByTestId('latest-quotation-items');
    expect(within(latest).getByText(/QT-2026-0201/)).toBeTruthy();
    expect(within(latest).getByText('Equipe Raku 20×20 · Matt White')).toBeTruthy();
    expect(within(latest).queryByText('ร่างอย่าลืม')).toBeNull();
    expect(screen.getByRole('heading', { name: /^รายการสินค้า/ })).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'คำขอราคา' })).toBeTruthy();
  });

  it('wrong-way-round: + สร้างใบเสนอราคา is not offered to another rep, nor while a live คำขอราคา exists', async () => {
    const view = renderPage(otherSales);
    await pageReady();
    await openTab('ใบเสนอราคา');
    await settle();
    expect(screen.queryByRole('link', { name: '+ สร้างใบเสนอราคา' })).toBeNull();
    view.unmount();

    api.pricingRequests.listForTicket.mockResolvedValue({ items: [{ id: 3, status: 'DRAFT', recipientType: 'DESIGNER', createdById: 1 }] });
    renderPage(salesOwner);
    await pageReady();
    await openTab('ใบเสนอราคา');
    await settle();
    expect(screen.queryByRole('link', { name: '+ สร้างใบเสนอราคา' })).toBeNull();
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
    renderPage();
    await pageReady();
    await openTab('ส่งของ');
    const card = await screen.findByTestId('ir-factory-card-41');
    expect(within(card).getByText('ดูอย่างเดียว')).toBeTruthy();
    expect(within(card).getByTestId('ir-step-readonly-41').textContent).toContain('การขนส่ง');
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
  // One enum display label is allowed too: the entry channel's name "ผู้ออกแบบนำดีล" (format.js entryChannelLabel,
  // DealRoute's DESIGNER_LED) is the app-wide name of a route, shown on the list, the create dialog and here alike —
  // renaming it is not this page's call.
  const ALLOWED = ['ผู้ออกแบบนำดีล'];
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
