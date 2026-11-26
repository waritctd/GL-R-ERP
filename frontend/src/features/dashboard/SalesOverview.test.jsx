import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SalesOverview } from './SalesOverview.jsx';
import { api } from '../../api/index.js';
import { addDaysIso, bangkokTodayIso } from '../../utils/format.js';
import { expectFigure } from '../../test/fixtures/dashboardMoney.js';

globalThis.React = React;

// Spec 0003 slice 3: the rep landing is งานวันนี้. The worklist is still nextSalesAction / sortWorklist; this file pins
// which group each action reads under and the labels, plus the new report block and the right-hand column.

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  // Imported inside the factory: vi.mock is hoisted above every import.
  const { DEAL_STAGE_CATALOG } = await import('../../data/dealStageCatalog.js');
  return {
    ...actual,
    api: {
      tickets: {
        list: vi.fn(), getProcurementSource: vi.fn(), addActivity: vi.fn(), markLost: vi.fn(), create: vi.fn(),
      },
      // MyWork's read (visits this week, paused deals coming back) and the weekly report block.
      salesBoard: { myWork: vi.fn(), weeklyReport: vi.fn() },
      salesVisits: { list: vi.fn(), done: vi.fn(), create: vi.fn(), cancel: vi.fn() },
      pricingRequests: { queue: vi.fn() },
      commissions: { monthlySummary: vi.fn() },
      // Each quoted deal's quotation list (the same call the deal page makes) — none by default.
      dealQuotations: { listForTicket: vi.fn().mockResolvedValue({ items: [] }) },
      // GET /api/meta/deal-stages: each deal's route (catalog.routes by entryChannel) for the
      // worklist's route choice. The same canned payload mockApi serves, pinned to DealRoute.java by
      // stageCatalog.test.js.
      meta: { dealStages: vi.fn().mockResolvedValue(DEAL_STAGE_CATALOG) },
    },
  };
});

const salesUser = { employeeId: 1, name: 'Sales Test', role: 'sales' };
const employee = { nameTh: 'สมชาย ใจดี' };

// Dates are derived from the REAL `bangkokTodayIso` so the fixtures always agree with what the component
// itself computes at render time.
function addDays(isoDate, delta) {
  const date = new Date(`${isoDate}T00:00:00.000Z`);
  date.setUTCDate(date.getUTCDate() + delta);
  return date.toISOString().slice(0, 10);
}
const TODAY = bangkokTodayIso();
const OVERDUE_DATE = addDays(TODAY, -5);
const repRow = { ownerId: 1, name: 'Sales Test', stageMoves: 3, quotationsIssued: 4, ordersConfirmed: 1, visitsDone: 5, visitsDue: 6, visitsDueWithResult: 5, ordersOpen: 5, ordersWithDate: 4 };
const order = (over) => ({ ticketId: 901, code: 'PR-2026-0901', title: 'ออเดอร์', customerName: 'ร้านตัวอย่าง', projectName: null, value: 58000, received: 0, outstanding: 58000, nextPayment: null, ...over });
const visit = (over) => ({ id: 1, ownerId: 1, contactName: 'คุณตัวอย่าง', ticketId: null, ticketTitle: null, plannedDate: TODAY, plannedTime: null, status: 'PLANNED', note: null, ...over });
// One deal per next-action bucket (now including RECORD_DELIVERY — deal I — and RECORD_QUOTATION_OUTCOME —
// deal J), plus a "nothing to do" deal (G) and a non-ACTIVE deal (H) that must be excluded from both the pulse
// and the worklist entirely.
const deals = [
  { id: 601, code: 'PR-2026-0601', customerName: 'บริษัท เอ จำกัด', title: 'ดีลเอ', lifecycle: 'ACTIVE', amountPayable: 100000, stale: false, nextFollowUpAt: null, stageUpdatedAt: '2026-07-01T00:00:00.000Z' },
  { id: 602, code: 'PR-2026-0602', customerName: 'บริษัท บี จำกัด', title: 'ดีลบี', lifecycle: 'ACTIVE', amountPayable: 200000, stale: false, nextFollowUpAt: null, stageUpdatedAt: '2026-07-01T00:00:00.000Z' },
  { id: 603, code: 'PR-2026-0603', customerName: 'บริษัท ซี จำกัด', title: 'ดีลซี', lifecycle: 'ACTIVE', amountPayable: 150000, stale: false, nextFollowUpAt: null, stageUpdatedAt: '2026-07-01T00:00:00.000Z' },
  { id: 604, code: 'PR-2026-0604', customerName: 'บริษัท ดี จำกัด', title: 'ดีลดี', lifecycle: 'ACTIVE', amountPayable: 50000, stale: false, nextFollowUpAt: OVERDUE_DATE, stageUpdatedAt: '2026-07-01T00:00:00.000Z' },
  { id: 605, code: 'PR-2026-0605', customerName: 'บริษัท อี จำกัด', title: 'ดีลอี', lifecycle: 'ACTIVE', amountPayable: 75000, stale: false, nextFollowUpAt: TODAY, stageUpdatedAt: '2026-07-01T00:00:00.000Z' },
  { id: 606, code: 'PR-2026-0606', customerName: 'บริษัท เอฟ จำกัด', title: 'ดีลเอฟ', lifecycle: 'ACTIVE', amountPayable: 25000, stale: true, nextFollowUpAt: null, stageUpdatedAt: '2026-07-01T00:00:00.000Z' },
  { id: 607, code: 'PR-2026-0607', customerName: 'บริษัท จี จำกัด', title: 'ดีลจี', lifecycle: 'ACTIVE', amountPayable: 10000, stale: false, nextFollowUpAt: null, stageUpdatedAt: '2026-07-01T00:00:00.000Z' },
  { id: 608, code: 'PR-2026-0608', customerName: 'บริษัท เอช จำกัด', title: 'ดีลเอช (ปิดแล้ว)', lifecycle: 'CLOSED_LOST', amountPayable: 999999, stale: false, nextFollowUpAt: OVERDUE_DATE, stageUpdatedAt: '2026-07-01T00:00:00.000Z' },
  // Deal I: stages 13-14 handoff to sales, owner ruling 2026-08-17.
  { id: 609, code: 'PR-2026-0609', customerName: 'บริษัท ไอ จำกัด', title: 'ดีลไอ', lifecycle: 'ACTIVE', amountPayable: 300000, stale: false, nextFollowUpAt: null, stageUpdatedAt: '2026-07-01T00:00:00.000Z', status: 'quotation_issued', fulfillmentStatus: 'GOODS_RECEIVED', paymentStatus: 'AWAITING_FINAL_PAYMENT' },
  // Deal J: the quotation was issued to the customer but nobody has recorded the outcome yet (deal-page
  // discoverability fix — this used to fall through to follow-up/log-activity; see salesActions.js bucket 4's
  // own comment, and salesActions.test.js's dedicated cases).
  { id: 610, code: 'PR-2026-0610', customerName: 'บริษัท เจ จำกัด', title: 'ดีลเจ', lifecycle: 'ACTIVE', amountPayable: 120000, stale: false, nextFollowUpAt: null, stageUpdatedAt: '2026-07-01T00:00:00.000Z' },
];

const pricingRequests = [
  { id: 1, ticketId: 602, status: 'APPROVED_FOR_QUOTATION', recipientType: 'BUYER', orderConfirmedAt: null },
  { id: 2, ticketId: 603, status: 'QUOTATION_ACCEPTED', orderConfirmedAt: null },
  { id: 3, ticketId: 604, status: 'SUBMITTED', orderConfirmedAt: null },
  { id: 4, ticketId: 605, status: 'IMPORT_REVIEWING', orderConfirmedAt: null },
  // CEO_REVIEWING, not QUOTATION_ISSUED: deal F is the dedicated "stale, nothing else pending" LOG_ACTIVITY
  // fixture below, and QUOTATION_ISSUED now has its own bucket (see deal J's PR below) — a live-but-unmatched
  // status keeps deal F actually exercising the fall-through path.
  { id: 5, ticketId: 606, status: 'CEO_REVIEWING', orderConfirmedAt: null },
  { id: 6, ticketId: 607, status: 'IMPORT_REVIEWING', orderConfirmedAt: null },
  { id: 7, ticketId: 610, status: 'QUOTATION_ISSUED', orderConfirmedAt: null },
  // Deal 601 deliberately has no pricing request at all.
];

// Default monthlySummary fixture for tests that don't care about the commission KPI figures — see the two
// dedicated cases below for the ones that do.
const defaultCommissionSummary = {
  payrollMonth: null,
  salesRepId: 1,
  commissionableBase: 0,
  tierCommission: 0,
  incentiveAmount: 0,
  manualTotal: 0,
  totalCommission: 0,
  belowFloor: false,
  tiers: [],
};

function renderOverview() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<SalesOverview user={salesUser} employee={employee} />} />
          <Route path="*" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function LocationProbe() {
  const location = useLocation();
  return (
    <>
      <div data-testid="location-probe">{location.pathname}</div>
      <div data-testid="location-search">{location.search}</div>
    </>
  );
}

/** Row names of one group, in order. */
const groupNames = (key) => [...screen.getByTestId(`today-group-${key}`).querySelectorAll('[data-testid^="today-row-"]')]
  .map((row) => row.querySelector('a > span').textContent);
const rowOf = (id) => screen.getByTestId(`today-row-deal-${id}`);
const buttonOf = (id) => within(rowOf(id)).getByRole('button').textContent;

function setUp(over = {}) {
  vi.clearAllMocks();
  api.tickets.list.mockResolvedValue({ tickets: over.deals ?? deals });
  api.pricingRequests.queue.mockResolvedValue({ items: over.pricingRequests ?? pricingRequests });
  api.commissions.monthlySummary.mockResolvedValue({ summary: over.summary ?? defaultCommissionSummary });
  api.salesBoard.myWork.mockResolvedValue({ followUp: [], board: [], quietDays: 14, visitsThisWeek: over.visits ?? [] });
  api.salesBoard.weeklyReport.mockResolvedValue({ reps: [repRow], orders: over.orders ?? [], today: over.today ?? [] });
  api.salesVisits.list.mockResolvedValue(over.carried ?? []);
}

describe('SalesOverview', () => {
  beforeEach(() => setUp());

  it('leads with this month\'s commission estimate and base, straight from the server summary', async () => {
    setUp({ summary: { ...defaultCommissionSummary, commissionableBase: 100000, totalCommission: 250 } });
    renderOverview();
    await waitFor(() => expectFigure('ค่าคอมประมาณการ (เดือนนี้)', '฿250.00'));
    expectFigure('ค่าคอมประมาณการ (เดือนนี้)', '฿100,000.00');
  });

  it('groups each next action as the board does and keeps the worklist order inside a group', async () => {
    renderOverview();
    await screen.findByTestId('today-group-customer');
    const order = [...document.querySelectorAll('[data-testid^="today-group-"]')].map((el) => el.dataset.testid.replace('today-group-', ''));
    expect(order).toEqual(['customer', 'due', 'next']);
    // sortWorklist's order: overdue follow-up first, then the action cascade.
    expect(groupNames('due')).toEqual(['บริษัท ดี จำกัด', 'บริษัท อี จำกัด']);
    expect(groupNames('customer')).toEqual(['บริษัท ซี จำกัด', 'บริษัท บี จำกัด', 'บริษัท เจ จำกัด']);
    expect(groupNames('next')).toEqual(['บริษัท เอ จำกัด', 'บริษัท ไอ จำกัด']);
    expect(screen.getByText('7 รายการ · กดปุ่มท้ายแถวเพื่อดำเนินการได้ทันที')).toBeTruthy();
  });

  it('maps each pipeline state to its button label; nothing for a deal that needs nothing, a closed one, or only a quiet log', async () => {
    renderOverview();
    await screen.findByTestId('today-group-customer');
    expect(buttonOf(601)).toBe('สร้างคำขอราคา'); // no PR at all
    expect(buttonOf(602)).toBe('เสนอใบราคาผู้ซื้อ/ผู้รับเหมา'); // APPROVED_FOR_QUOTATION, named for its recipient
    expect(buttonOf(603)).toBe('ยืนยันคำสั่งซื้อ'); // QUOTATION_ACCEPTED, not confirmed
    expect(buttonOf(604)).toBe('บันทึกการติดตาม'); // follow-up overdue
    expect(buttonOf(605)).toBe('บันทึกการติดตาม'); // follow-up due today
    expect(buttonOf(609)).toBe('บันทึกส่งมอบ'); // GOODS_RECEIVED, priced outside the PCR chain
    expect(buttonOf(610)).toBe('บันทึกผลใบเสนอราคา'); // QUOTATION_ISSUED, outcome not recorded yet
    [606, 607, 608].forEach((id) => expect(screen.queryByTestId(`today-row-deal-${id}`)).toBeNull());
  });

  it('puts the follow-up date on the row, and the button opens the call dialog', async () => {
    renderOverview();
    await screen.findByTestId('today-group-due');
    expect(rowOf(604).textContent).toContain('เลยกำหนดติดตาม');
    expect(rowOf(605).textContent).toContain('กำหนดติดตามวันนี้');
    fireEvent.click(within(rowOf(604)).getByRole('button', { name: 'บันทึกการติดตาม' }));
    expect(await screen.findByRole('dialog', { name: 'โทรแล้ว' })).toBeTruthy();
  });

  it('shows the report block: completeness from the weekly report, this week\'s entries, and no quiet-job wording', async () => {
    setUp({ orders: [order()] });
    renderOverview();
    await screen.findByText('9 จาก 11 รายการ ครบถ้วน');
    expect(screen.getByText('4 จาก 5 งาน')).toBeTruthy();
    expect(screen.getByText('5 จาก 6 นัด')).toBeTruthy();
    expect(screen.getByText('ระบบสรุปรายงานสัปดาห์นี้ให้แก่ผู้จัดการฝ่ายขายจากข้อมูลที่คุณบันทึก อัปเดตทันทีทุกครั้งที่บันทึก')).toBeTruthy();
    expectFigure('ยังไม่นับในประมาณการ', '฿58,000.00');
    expect(screen.queryByText(/ควรตามต่อ|งานเงียบ|ดีล/)).toBeNull();
  });

  it('an ordered job with no payment date is a money row whose button opens the add-payment form on the money tab', async () => {
    setUp({ orders: [order()] });
    renderOverview();
    const row = await screen.findByTestId('today-row-undated-901');
    expect(screen.getByTestId('today-group-money').contains(row)).toBe(true);
    fireEvent.click(within(row).getByRole('button', { name: 'ระบุวันที่' }));
    await waitFor(() => expect(screen.getByTestId('location-probe').textContent).toBe('/tickets/901'));
    expect(screen.getByTestId('location-search').textContent).toBe('?tab=money&add=expected');
  });

  it('an order the system estimated is dated: no ระบุวันที่ row for it', async () => {
    setUp({ orders: [order({ estimate: { kind: 'ON_DELIVERY', amount: 58000, week: '2026-11-23' } })] });
    renderOverview();
    await screen.findByTestId('today-group-money').catch(() => null);
    expect(screen.queryByTestId('today-row-undated-901')).toBeNull();
  });

  it('a visit needs a result only after its day, or its time today; a time later today or none is not due yet', async () => {
    setUp({
      visits: [
        visit({ id: 1, contactName: 'เมื่อวาน', plannedDate: addDaysIso(TODAY, -1) }),
        visit({ id: 2, contactName: 'วันนี้ไม่มีเวลา' }),
        visit({ id: 3, contactName: 'วันนี้ 23:59', plannedTime: '23:59:00' }),
      ],
    });
    renderOverview();
    const row = await screen.findByTestId('today-row-visit-1');
    expect(screen.getByTestId('today-group-due').contains(row)).toBe(true);
    expect(screen.queryByTestId('today-row-visit-2')).toBeNull();
    expect(screen.queryByTestId('today-row-visit-3')).toBeNull();
    // Today's visits sit in นัดหมายวันนี้ with the time as HH:mm; the first one still planned can be ticked or moved.
    expect(screen.getByTestId('today-visit-3').textContent).toContain('23:59');
    expect(screen.getByTestId('today-visit-3').textContent).not.toContain('23:59:00');
  });

  it('รอฝ่ายอื่นดำเนินการ names who waits: import for SUBMITTED / IMPORT_REVIEWING, the CEO for its review', async () => {
    setUp({
      pricingRequests: [
        ...pricingRequests,
        { id: 8, ticketId: 607, status: 'READY_FOR_CEO_REVIEW', requestCode: 'PCR-8', updatedAt: new Date().toISOString() },
      ],
    });
    renderOverview();
    const panel = (await screen.findByRole('heading', { name: 'รอฝ่ายอื่นดำเนินการ' })).closest('section');
    await waitFor(() => expect(within(panel).getAllByText('ฝ่ายนำเข้า · ตอบราคา').length).toBe(3)); // 604, 605, 607
    expect(within(panel).getAllByText('CEO · พิจารณาราคา').length).toBe(2); // 606, 607
  });
});

// Slice 2 — flow A: the live-direct-quotation buckets. CONFIRM / SUBMIT are the rep's own rows; AWAIT waits on the
// sales manager, so it leaves the worklist for รอฝ่ายอื่นดำเนินการ.
describe('SalesOverview — live direct quotation (slice 2)', () => {
  const liveDeals = [
    { id: 701, code: 'DL-2026-0701', customerName: 'บริษัท ร่าง จำกัด', title: 'ร่าง', lifecycle: 'ACTIVE', amountPayable: 0, stale: false, nextFollowUpAt: null, stageUpdatedAt: '2026-07-01T00:00:00.000Z',
      liveDirectQuotation: { id: 71, number: 'QT-2026-0071-1', docStatus: 'DRAFT', recipientType: 'DESIGNER' } },
    { id: 702, code: 'DL-2026-0702', customerName: 'บริษัท รอ จำกัด', title: 'รอ', lifecycle: 'ACTIVE', amountPayable: 0, stale: false, nextFollowUpAt: null, stageUpdatedAt: '2026-07-01T00:00:00.000Z',
      liveDirectQuotation: { id: 72, number: 'QT-2026-0072-1', docStatus: 'PENDING_APPROVAL', recipientType: 'OWNER' } },
    { id: 703, code: 'DL-2026-0703', customerName: 'บริษัท อนุมัติ จำกัด', title: 'อนุมัติ', lifecycle: 'ACTIVE', amountPayable: 0, stale: false, nextFollowUpAt: null, stageUpdatedAt: '2026-07-01T00:00:00.000Z',
      liveDirectQuotation: { id: 73, number: 'QT-2026-0073-1', docStatus: 'APPROVED', recipientType: 'BUYER' } },
  ];

  it('CONFIRM is ลูกค้ารอคำตอบ, SUBMIT is ขั้นตอนถัดไป, AWAIT is รอฝ่ายอื่น and never told to open a คำขอราคา', async () => {
    setUp({ deals: liveDeals, pricingRequests: [] });
    renderOverview();
    await screen.findByTestId('today-group-customer');
    expect(groupNames('customer')).toEqual(['บริษัท อนุมัติ จำกัด']);
    expect(groupNames('next')).toEqual(['บริษัท ร่าง จำกัด']);
    expect(screen.queryByTestId('today-row-deal-702')).toBeNull();
    const panel = screen.getByRole('heading', { name: 'รอฝ่ายอื่นดำเนินการ' }).closest('section');
    expect(within(panel).getByText('ผู้จัดการฝ่ายขาย · อนุมัติใบเสนอราคา')).toBeTruthy();
    expect(screen.queryByText('สร้างคำขอราคา')).toBeNull();
  });
});

// Owner ruling 2026-10-27 (spec A7/H1): the worklist runs the deal page's resolver, so a designer quotation that went
// out offers the next party named from THAT deal's route.
describe('SalesOverview — the next party after a designer quotation is sent follows each deal\'s route', () => {
  const at = '2026-07-01T00:00:00.000Z';
  const routeDeals = [
    { id: 811, code: 'DL-2026-0811', customerName: 'บริษัท ผู้ออกแบบนำ จำกัด', title: 'ผู้ออกแบบนำ', lifecycle: 'ACTIVE', entryChannel: 'DESIGNER_LED', salesStage: 'QUOTE_DESIGN_SIDE', amountPayable: 0, stale: false, nextFollowUpAt: null, stageUpdatedAt: at },
    { id: 812, code: 'DL-2026-0812', customerName: 'บริษัท ผู้ซื้อติดต่อตรง จำกัด', title: 'ผู้ซื้อติดต่อตรง', lifecycle: 'ACTIVE', entryChannel: 'BUYER_DIRECT', salesStage: 'SPEC_APPROVED', amountPayable: 0, stale: false, nextFollowUpAt: null, stageUpdatedAt: at },
    { id: 813, code: 'DL-2026-0813', customerName: 'บริษัท ผู้ออกแบบขอแก้ จำกัด', title: 'ผู้ออกแบบขอแก้', lifecycle: 'ACTIVE', entryChannel: 'DESIGNER_LED', salesStage: 'QUOTE_DESIGN_SIDE', amountPayable: 0, stale: false, nextFollowUpAt: null, stageUpdatedAt: at },
  ];
  const sentRequests = [
    { id: 81, ticketId: 811, status: 'QUOTATION_ISSUED', recipientType: 'DESIGNER', orderConfirmedAt: null },
    { id: 82, ticketId: 812, status: 'QUOTATION_ISSUED', recipientType: 'DESIGNER', orderConfirmedAt: null },
    { id: 83, ticketId: 813, status: 'QUOTATION_ISSUED', recipientType: 'DESIGNER', orderConfirmedAt: null },
  ];
  const docStatusByRequest = { 81: 'ISSUED', 82: 'ISSUED', 83: 'REVISION_REQUESTED' };

  it('names the next party per route', async () => {
    setUp({ deals: routeDeals, pricingRequests: sentRequests });
    api.dealQuotations.listForTicket.mockImplementation((ticketId) => Promise.resolve({
      items: sentRequests.filter((pr) => pr.ticketId === ticketId).map((pr) => ({
        id: 9000 + pr.id, origin: 'PRICING_REQUEST', pricingRequestId: pr.id, docStatus: docStatusByRequest[pr.id],
      })),
    }));
    renderOverview();
    await screen.findByTestId('today-row-deal-811');
    await waitFor(() => {
      expect(buttonOf(811)).toBe('เสนอราคาเจ้าของโครงการ');
      expect(buttonOf(812)).toBe('เสนอราคาผู้ซื้อ/ผู้รับเหมา');
      expect(buttonOf(813)).toBe('เสนอใบราคาผู้ออกแบบ');
    });
  });
});

// Stage-10 "จัดซื้อสินค้า": undecided deals read จัดซื้อสินค้า, decided ones ออกใบขอซื้อ (IR); the page asks the server per deal.
describe('SalesOverview — the procurement-source decision shows in the worklist', () => {
  const at = '2026-07-01T00:00:00.000Z';
  const base = { lifecycle: 'ACTIVE', salesStage: 'ORDER_RECEIVED', paymentStatus: 'DEPOSIT_PAID', fulfillmentStatus: null, amountPayable: 0, stale: false, nextFollowUpAt: null, stageUpdatedAt: at };
  const procDeals = [
    { ...base, id: 821, code: 'DL-2026-0821', customerName: 'บริษัท ยังไม่เลือก จำกัด', title: 'ยังไม่เลือก' },
    { ...base, id: 822, code: 'DL-2026-0822', customerName: 'บริษัท เลือกแล้ว จำกัด', title: 'เลือกแล้ว' },
    { ...base, id: 823, code: 'DL-2026-0823', customerName: 'บริษัท ก่อนยืนยัน จำกัด', title: 'ก่อนยืนยัน', salesStage: 'QUOTE_OWNER' },
  ];

  it('undecided -> จัดซื้อสินค้า; decided -> ออกใบขอซื้อ (IR); a deal before ORDER_RECEIVED is never asked', async () => {
    setUp({ deals: procDeals, pricingRequests: [] });
    api.tickets.getProcurementSource.mockImplementation((id) => Promise.resolve({ pending: Number(id) === 821 }));
    renderOverview();
    await screen.findByTestId('today-row-deal-821');
    await waitFor(() => {
      expect(buttonOf(821)).toBe('จัดซื้อสินค้า');
      expect(buttonOf(822)).toBe('ออกใบขอซื้อ (IR)');
    });
    expect(api.tickets.getProcurementSource).toHaveBeenCalledTimes(2);
    expect(api.tickets.getProcurementSource).not.toHaveBeenCalledWith(823);
  });
});
