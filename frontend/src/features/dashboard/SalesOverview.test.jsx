import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SalesOverview } from './SalesOverview.jsx';
import { api } from '../../api/index.js';
import { bangkokTodayIso, formatThaiDate } from '../../utils/format.js';
import { expectFigure } from '../../test/fixtures/dashboardMoney.js';

globalThis.React = React;

// Review H3: the rep landing is งานของฉัน (MyWork) with what this page already did put back above it — the money
// band and the next-action worklist. The worklist's quiet group is gone (ควรตามต่อ, the 14-day QuietRule, covers
// a deal whose only action is "log an activity"), its follow group reads ถึงกำหนดตาม, and the rep's 7-day tile
// panel was not restored (the manager/CEO landings keep theirs).

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
      // งานของฉัน's own read (MyWork): ควรตามต่อ + the board, pinned in salesBoard/MyWork.test.jsx.
      salesBoard: { myWork: vi.fn() },
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

function worklistSection() {
  return screen.getByRole('heading', { name: 'สิ่งที่ต้องทำ' }).closest('section');
}

const groupNames = (key) => [...screen.getByTestId(`sales-group-${key}`).querySelectorAll('[data-testid^="sales-row-"]')]
  .map((row) => row.querySelector('strong').textContent);

describe('SalesOverview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.tickets.list.mockResolvedValue({ tickets: deals });
    api.pricingRequests.queue.mockResolvedValue({ items: pricingRequests });
    api.commissions.monthlySummary.mockResolvedValue({ summary: defaultCommissionSummary });
    api.salesBoard.myWork.mockResolvedValue({ followUp: [], board: [], quietDays: 14 });
  });

  describe('money band', () => {
    it('leads with this month\'s commission estimate and base, straight from the server summary', async () => {
      api.commissions.monthlySummary.mockResolvedValue({
        summary: { ...defaultCommissionSummary, commissionableBase: 100000, totalCommission: 250 },
      });
      renderOverview();

      await waitFor(() => expectFigure('ค่าคอมประมาณการ (เดือนนี้)', '฿250.00'));
      // the base now sits in the commission figure's caption
      expectFigure('ค่าคอมประมาณการ (เดือนนี้)', '฿100,000.00');
    });

    it('sums ยอดคาดหวัง over ACTIVE deals only (nothing here is at ORDER_RECEIVED yet)', async () => {
      renderOverview();
      // 610,000 + deal I's 300,000 + deal J's 120,000; deal H (CLOSED_LOST, 999,999) is excluded.
      await waitFor(() => expectFigure('ยอดคาดหวัง', '฿1,030,000.00'));
      expectFigure('ยอดที่สั่งซื้อแล้ว', '฿0.00');
    });
  });

  describe('สิ่งที่ต้องทำ', () => {
    it('maps each pipeline state to the correct next-action label', async () => {
      renderOverview();
      const worklist = within(worklistSection());
      await worklist.findByText('บริษัท เอ จำกัด');
      const badgeOf = (name) => worklist.getByText(name).closest('button').querySelector('.status-badge').textContent;

      expect(badgeOf('บริษัท เอ จำกัด')).toBe('สร้างคำขอราคา'); // no PR at all
      expect(badgeOf('บริษัท บี จำกัด')).toBe('เสนอใบราคาผู้ซื้อ/ผู้รับเหมา'); // APPROVED_FOR_QUOTATION, named for its recipient
      expect(badgeOf('บริษัท ซี จำกัด')).toBe('ยืนยันคำสั่งซื้อ'); // QUOTATION_ACCEPTED, not confirmed
      expect(badgeOf('บริษัท ดี จำกัด')).toBe('ติดตามลูกค้า'); // follow-up overdue
      expect(badgeOf('บริษัท อี จำกัด')).toBe('ติดตามลูกค้า'); // follow-up due today
      // F (stale, nothing else to do) is ควรตามต่อ's, not a worklist row any more.
      expect(worklist.queryByText('บริษัท เอฟ จำกัด')).toBeNull();
      expect(badgeOf('บริษัท ไอ จำกัด')).toBe('บันทึกส่งมอบ'); // GOODS_RECEIVED, priced outside the PCR chain
      expect(badgeOf('บริษัท เจ จำกัด')).toBe('บันทึกผลใบเสนอราคา'); // QUOTATION_ISSUED, outcome not recorded yet

      // G needs nothing from the rep; H is not ACTIVE.
      expect(worklist.queryByText('บริษัท จี จำกัด')).toBeNull();
      expect(worklist.queryByText('บริษัท เอช จำกัด')).toBeNull();
    });

    it('groups ถึงกำหนดตาม → ต้องทำต่อ, keeping the worklist order inside each group', async () => {
      renderOverview();
      await screen.findByTestId('sales-group-follow');

      const order = [...document.querySelectorAll('[data-testid^="sales-group-"]')].map((el) => el.dataset.testid.replace('sales-group-', ''));
      expect(order).toEqual(['follow', 'next']);
      // Overdue first within ติดตามลูกค้า; the action cascade within ต้องทำต่อ (same order sortWorklist gives).
      expect(groupNames('follow')).toEqual(['บริษัท ดี จำกัด', 'บริษัท อี จำกัด']);
      expect(groupNames('next')).toEqual(['บริษัท ซี จำกัด', 'บริษัท บี จำกัด', 'บริษัท เจ จำกัด', 'บริษัท เอ จำกัด', 'บริษัท ไอ จำกัด']);
      expect(screen.getByTestId('sales-group-follow').textContent).toContain('2 งาน');
      expect(screen.getByTestId('worklist-total').textContent).toBe('7');
      expect(screen.getByText('รวม 7 งาน').className).toContain('sr-only');
    });

    it('puts the follow-up date on the row: เลยนัด for overdue, นัดวันนี้ for today', async () => {
      renderOverview();
      await screen.findByTestId('sales-group-follow');

      expect(screen.getByTestId('sales-row-604').textContent).toContain(`เลยนัด ${formatThaiDate(OVERDUE_DATE)}`);
      expect(screen.getByTestId('sales-row-605').textContent).toContain('นัดวันนี้');
      expect(screen.getByTestId('sales-row-601').textContent).not.toMatch(/เลยนัด|นัดวันนี้/);
    });
  });
});

// Slice 2 — flow A (SLICE-2-FLOW-A.md §E, DESIGN.md §15): the three live-direct-quotation buckets reach the
// worklist, and their badge tone says whose move it is — SUBMIT / CONFIRM are the rep's own (warning), AWAIT
// is waiting on ผจก.ขาย/CEO (neutral), never the same tone as a task.
describe('SalesOverview — live direct quotation worklist tones (slice 2)', () => {
  const liveDeals = [
    { id: 701, code: 'DL-2026-0701', customerName: 'บริษัท ร่าง จำกัด', title: 'ร่าง', lifecycle: 'ACTIVE', amountPayable: 0, stale: false, nextFollowUpAt: null, stageUpdatedAt: '2026-07-01T00:00:00.000Z',
      liveDirectQuotation: { id: 71, number: 'QT-2026-0071-1', docStatus: 'DRAFT', recipientType: 'DESIGNER' } },
    { id: 702, code: 'DL-2026-0702', customerName: 'บริษัท รอ จำกัด', title: 'รอ', lifecycle: 'ACTIVE', amountPayable: 0, stale: false, nextFollowUpAt: null, stageUpdatedAt: '2026-07-01T00:00:00.000Z',
      liveDirectQuotation: { id: 72, number: 'QT-2026-0072-1', docStatus: 'PENDING_APPROVAL', recipientType: 'OWNER' } },
    { id: 703, code: 'DL-2026-0703', customerName: 'บริษัท อนุมัติ จำกัด', title: 'อนุมัติ', lifecycle: 'ACTIVE', amountPayable: 0, stale: false, nextFollowUpAt: null, stageUpdatedAt: '2026-07-01T00:00:00.000Z',
      liveDirectQuotation: { id: 73, number: 'QT-2026-0073-1', docStatus: 'APPROVED', recipientType: 'BUYER' } },
  ];

  beforeEach(() => {
    vi.clearAllMocks();
    api.tickets.list.mockResolvedValue({ tickets: liveDeals });
    api.pricingRequests.queue.mockResolvedValue({ items: [] });
    api.commissions.monthlySummary.mockResolvedValue({ summary: defaultCommissionSummary });
  });

  it('lists all three: CONFIRM then SUBMIT under ต้องทำต่อ, AWAIT under รอผู้อื่น', async () => {
    renderOverview();
    const worklist = within(worklistSection());
    await worklist.findByText('บริษัท ร่าง จำกัด');
    expect(groupNames('next')).toEqual(['บริษัท อนุมัติ จำกัด', 'บริษัท ร่าง จำกัด']);
    expect(groupNames('wait')).toEqual(['บริษัท รอ จำกัด']);
    // None of them is told to open a คำขอราคา.
    expect(worklist.queryByText('สร้างคำขอราคา')).toBeNull();
  });
});

// Owner ruling 2026-10-27 (spec A7/H1): the worklist runs the deal page's resolver, so a designer
// quotation that went out offers the next party — named from THAT deal's route (the served
// catalog.routes for its entryChannel), not from a route every deal is assumed to share. There is
// no quotation list here, so a request at QUOTATION_ISSUED counts as sent on its status alone.
describe('SalesOverview — the next party after a designer quotation is sent follows each deal\'s route', () => {
  const at = '2026-07-01T00:00:00.000Z';
  const routeDeals = [
    { id: 811, code: 'DL-2026-0811', customerName: 'บริษัท ผู้ออกแบบนำ จำกัด', title: 'ผู้ออกแบบนำ', lifecycle: 'ACTIVE', entryChannel: 'DESIGNER_LED', salesStage: 'QUOTE_DESIGN_SIDE', amountPayable: 0, stale: false, nextFollowUpAt: null, stageUpdatedAt: at },
    // A designer quotation on a buyer-direct deal is off its route (allowed); the owner is off it too.
    { id: 812, code: 'DL-2026-0812', customerName: 'บริษัท ผู้ซื้อติดต่อตรง จำกัด', title: 'ผู้ซื้อติดต่อตรง', lifecycle: 'ACTIVE', entryChannel: 'BUYER_DIRECT', salesStage: 'SPEC_APPROVED', amountPayable: 0, stale: false, nextFollowUpAt: null, stageUpdatedAt: at },
    { id: 813, code: 'DL-2026-0813', customerName: 'บริษัท ผู้ออกแบบขอแก้ จำกัด', title: 'ผู้ออกแบบขอแก้', lifecycle: 'ACTIVE', entryChannel: 'DESIGNER_LED', salesStage: 'QUOTE_DESIGN_SIDE', amountPayable: 0, stale: false, nextFollowUpAt: null, stageUpdatedAt: at },
  ];
  const sentRequests = [
    { id: 81, ticketId: 811, status: 'QUOTATION_ISSUED', recipientType: 'DESIGNER', orderConfirmedAt: null },
    { id: 82, ticketId: 812, status: 'QUOTATION_ISSUED', recipientType: 'DESIGNER', orderConfirmedAt: null },
    { id: 83, ticketId: 813, status: 'QUOTATION_ISSUED', recipientType: 'DESIGNER', orderConfirmedAt: null },
  ];
  // Each request's newest quotation, read from its deal's quotation list as the deal page reads it
  // (review V11): the designer on 813 asked for changes.
  const docStatusByRequest = { 81: 'ISSUED', 82: 'ISSUED', 83: 'REVISION_REQUESTED' };

  beforeEach(() => {
    vi.clearAllMocks();
    api.tickets.list.mockResolvedValue({ tickets: routeDeals });
    api.pricingRequests.queue.mockResolvedValue({ items: sentRequests });
    api.commissions.monthlySummary.mockResolvedValue({ summary: defaultCommissionSummary });
    api.dealQuotations.listForTicket.mockImplementation((ticketId) => Promise.resolve({
      items: sentRequests.filter((pr) => pr.ticketId === ticketId).map((pr) => ({
        id: 9000 + pr.id, origin: 'PRICING_REQUEST', pricingRequestId: pr.id, docStatus: docStatusByRequest[pr.id],
      })),
    }));
  });

  it('designer-led -> เสนอราคาเจ้าของโครงการ; buyer-direct, whose route skips the owner -> เสนอราคาผู้ซื้อ/ผู้รับเหมา; a designer quotation being redone -> เสนอใบราคาผู้ออกแบบ', async () => {
    renderOverview();
    const worklist = within(worklistSection());
    await worklist.findByText('บริษัท ผู้ออกแบบนำ จำกัด');
    const badgeOf = (customerName) => worklist.getByText(customerName).closest('button').querySelector('.status-badge');

    // waitFor: the label follows the stage catalog, which may land after the deal list.
    await waitFor(() => {
      expect(badgeOf('บริษัท ผู้ออกแบบนำ จำกัด').textContent).toBe('เสนอราคาเจ้าของโครงการ');
      expect(badgeOf('บริษัท ผู้ซื้อติดต่อตรง จำกัด').textContent).toBe('เสนอราคาผู้ซื้อ/ผู้รับเหมา');
      expect(badgeOf('บริษัท ผู้ออกแบบขอแก้ จำกัด').textContent).toBe('เสนอใบราคาผู้ออกแบบ');
    });
  });
});

// Stage-10 "จัดซื้อสินค้า": a deal past order confirmation with no import/stock decision yet reads
// จัดซื้อสินค้า in the worklist (not ออกใบขอซื้อ (IR)); the cockpit asks the server per such deal.
describe('SalesOverview — the procurement-source decision shows in the worklist', () => {
  const at = '2026-07-01T00:00:00.000Z';
  const base = { lifecycle: 'ACTIVE', salesStage: 'ORDER_RECEIVED', paymentStatus: 'DEPOSIT_PAID', fulfillmentStatus: null, amountPayable: 0, stale: false, nextFollowUpAt: null, stageUpdatedAt: at };
  const procDeals = [
    { ...base, id: 821, code: 'DL-2026-0821', customerName: 'บริษัท ยังไม่เลือก จำกัด', title: 'ยังไม่เลือก' },
    { ...base, id: 822, code: 'DL-2026-0822', customerName: 'บริษัท เลือกแล้ว จำกัด', title: 'เลือกแล้ว' },
    { ...base, id: 823, code: 'DL-2026-0823', customerName: 'บริษัท ก่อนยืนยัน จำกัด', title: 'ก่อนยืนยัน', salesStage: 'QUOTE_OWNER' },
  ];

  beforeEach(() => {
    vi.clearAllMocks();
    api.tickets.list.mockResolvedValue({ tickets: procDeals });
    api.pricingRequests.queue.mockResolvedValue({ items: [] });
    api.commissions.monthlySummary.mockResolvedValue({ summary: defaultCommissionSummary });
    api.tickets.getProcurementSource.mockImplementation((id) => Promise.resolve({ pending: Number(id) === 821 }));
  });

  it('undecided -> จัดซื้อสินค้า; decided -> ออกใบขอซื้อ (IR); a deal before ORDER_RECEIVED is never asked', async () => {
    renderOverview();
    const worklist = within(worklistSection());
    await worklist.findByText('บริษัท ยังไม่เลือก จำกัด');
    const badgeOf = (name) => worklist.getByText(name).closest('button').querySelector('.status-badge');
    await waitFor(() => {
      expect(badgeOf('บริษัท ยังไม่เลือก จำกัด').textContent).toBe('จัดซื้อสินค้า');
      expect(badgeOf('บริษัท เลือกแล้ว จำกัด').textContent).toBe('ออกใบขอซื้อ (IR)');
    });
    expect(api.tickets.getProcurementSource).toHaveBeenCalledTimes(2);
    expect(api.tickets.getProcurementSource).not.toHaveBeenCalledWith(823);
  });

  describe('money band and worklist amounts', () => {
    const mv = (id, extra) => ({
      id, code: `PR-2026-09${id}`, customerName: `ลูกค้า ${id}`, title: `ดีล ${id}`, lifecycle: 'ACTIVE',
      salesStage: 'NEGOTIATION', amountPayable: 100, stale: false, nextFollowUpAt: null,
      stageUpdatedAt: '2026-07-01T00:00:00.000Z', stageChangedInRange: false, activityInRange: false, ...extra,
    });
    const movementDeals = [
      mv(1, { stageChangedInRange: true, amountPayable: 100 }),
      mv(2, { activityInRange: true, amountPayable: 200 }),
      mv(3, { nextFollowUpAt: TODAY, amountPayable: 300 }),
      mv(4, { nextFollowUpAt: OVERDUE_DATE, amountPayable: 400 }),
      mv(5, { lifecycle: 'CLOSED_LOST', amountPayable: 5000 }),
      mv(6, { salesStage: 'ORDER_RECEIVED', stageChangedInRange: true, amountPayable: 4000 }),
      // a paused deal is flagged on every signal but counts on none of the tiles
      mv(7, { lifecycle: 'ON_HOLD', stageChangedInRange: true, activityInRange: true, nextFollowUpAt: OVERDUE_DATE }),
    ];

    beforeEach(() => {
      api.tickets.list.mockResolvedValue({ tickets: movementDeals });
    });

    it('money band: ยอดที่สั่งซื้อแล้ว / ยอดคาดหวัง / ค่าคอมประมาณการ (เดือนนี้)', async () => {
      api.commissions.monthlySummary.mockResolvedValue({
        summary: { ...defaultCommissionSummary, totalCommission: 250 },
      });
      renderOverview();
      await waitFor(() => expectFigure('ยอดที่สั่งซื้อแล้ว', '฿4,000.00'));
      expectFigure('ยอดคาดหวัง', '฿1,000.00');
      expectFigure('ค่าคอมประมาณการ (เดือนนี้)', '฿250.00');
    });
  });
});
