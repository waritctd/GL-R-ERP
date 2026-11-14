import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SalesOverview } from './SalesOverview.jsx';
import { api } from '../../api/index.js';
import { bangkokTodayIso, formatThaiDate } from '../../utils/format.js';
import { expectFigure, figureRows } from '../../test/fixtures/dashboardMoney.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  // Imported inside the factory: vi.mock is hoisted above every import.
  const { DEAL_STAGE_CATALOG } = await import('../../data/dealStageCatalog.js');
  return {
    ...actual,
    api: {
      tickets: { list: vi.fn(), getProcurementSource: vi.fn() },
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
const THIS_MONTH = `${TODAY.slice(0, 7)}-01`;

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

let lastClient;

function renderOverview() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  lastClient = queryClient;
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
  return <div data-testid="location-probe">{location.pathname}</div>;
}

function worklistSection() {
  return screen.getByRole('heading', { name: 'สิ่งที่ต้องทำ' }).closest('section');
}

const never = () => new Promise(() => {});
const groupNames = (key) => [...screen.getByTestId(`sales-group-${key}`).querySelectorAll('[data-testid^="sales-row-"]')]
  .map((row) => row.querySelector('strong').textContent);

describe('SalesOverview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.tickets.list.mockResolvedValue({ tickets: deals });
    api.pricingRequests.queue.mockResolvedValue({ items: pricingRequests });
    api.commissions.monthlySummary.mockResolvedValue({ summary: defaultCommissionSummary });
  });

  it('greets the rep and requests an own-scoped deal list', async () => {
    renderOverview();
    expect(await screen.findByText(/สวัสดี, คุณสมชาย ใจดี/)).not.toBeNull();
    expect(api.tickets.list).toHaveBeenCalledWith({});
    expect(api.commissions.monthlySummary).toHaveBeenCalledWith({ payrollMonth: THIS_MONTH });
  });

  describe('money band', () => {
    it('leads with this month\'s commission estimate and base, straight from the server summary', async () => {
      api.commissions.monthlySummary.mockResolvedValue({
        summary: { ...defaultCommissionSummary, commissionableBase: 100000, totalCommission: 250 },
      });
      renderOverview();

      await waitFor(() => expectFigure('ค่าคอมประมาณการ (เดือนนี้)', '฿250.00'));
      expectFigure('ฐานคิดค่าคอม', '฿100,000.00');
    });

    // fix/commission-figures-from-backend (#548-style V81 regression guard): the commission figures
    // are the SERVER-computed monthly summary (CommissionService#monthlySummary), never a client
    // re-implementation of the tier math that could drift from a DB tier-config change (the V81
    // tier-13 rate correction is the case on record). 99,999.99 is unreachable by any seeded tier
    // band on this base, so only a render wired to the DTO shows it.
    it('renders the server-computed figure, not a client-recomputed one', async () => {
      api.commissions.monthlySummary.mockResolvedValue({
        summary: { ...defaultCommissionSummary, payrollMonth: THIS_MONTH, commissionableBase: 1200000, tierCommission: 99999.99, totalCommission: 99999.99 },
      });
      renderOverview();

      await waitFor(() => expectFigure('ค่าคอมประมาณการ (เดือนนี้)', '฿99,999.99'));
      expectFigure('ฐานคิดค่าคอม', '฿1,200,000.00');
    });

    it('sums the open-deal value over ACTIVE deals only', async () => {
      renderOverview();
      // 610,000 + deal I's 300,000 + deal J's 120,000; deal H (CLOSED_LOST, 999,999) is excluded.
      await waitFor(() => expectFigure('มูลค่าดีลที่เปิดอยู่', '฿1,030,000.00'));
    });

    it('shows the commission while the deal list is still loading, and the deal value waits', async () => {
      api.tickets.list.mockImplementation(never);
      api.commissions.monthlySummary.mockResolvedValue({
        summary: { ...defaultCommissionSummary, commissionableBase: 100000, totalCommission: 250 },
      });
      renderOverview();

      await waitFor(() => expectFigure('ค่าคอมประมาณการ (เดือนนี้)', '฿250.00'));
      expect(figureRows('มูลค่าดีลที่เปิดอยู่')[0]).not.toContain('฿');
      expect(worklistSection().querySelector('[aria-busy="true"]')).not.toBeNull();
    });

    it('drops the count tiles, their English helpers and the separate follow-up panel', async () => {
      renderOverview();
      await screen.findByTestId('sales-group-follow');

      expect(document.querySelector('.stat-card')).toBeNull();
      expect(screen.queryByText(/Overdue follow-up|Due today|Ready to quote|Open deals/)).toBeNull();
      expect(screen.queryByText('ติดตามที่ครบกำหนด')).toBeNull();
      expect(screen.queryByText('มูลค่า pipeline')).toBeNull();
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
      expect(badgeOf('บริษัท เอฟ จำกัด')).toBe('บันทึกกิจกรรม'); // stale, no follow-up
      expect(badgeOf('บริษัท ไอ จำกัด')).toBe('บันทึกส่งมอบ'); // GOODS_RECEIVED, priced outside the PCR chain
      expect(badgeOf('บริษัท เจ จำกัด')).toBe('บันทึกผลใบเสนอราคา'); // QUOTATION_ISSUED, outcome not recorded yet

      // G needs nothing from the rep; H is not ACTIVE.
      expect(worklist.queryByText('บริษัท จี จำกัด')).toBeNull();
      expect(worklist.queryByText('บริษัท เอช จำกัด')).toBeNull();
    });

    it('groups ติดตามลูกค้า → ต้องทำต่อ → ไม่มีความเคลื่อนไหว, keeping the worklist order inside each group', async () => {
      renderOverview();
      await screen.findByTestId('sales-group-follow');

      const order = [...document.querySelectorAll('[data-testid^="sales-group-"]')].map((el) => el.dataset.testid.replace('sales-group-', ''));
      expect(order).toEqual(['follow', 'next', 'quiet']);
      // Overdue first within ติดตามลูกค้า; the action cascade within ต้องทำต่อ (same order sortWorklist gives).
      expect(groupNames('follow')).toEqual(['บริษัท ดี จำกัด', 'บริษัท อี จำกัด']);
      expect(groupNames('next')).toEqual(['บริษัท ซี จำกัด', 'บริษัท บี จำกัด', 'บริษัท เจ จำกัด', 'บริษัท เอ จำกัด', 'บริษัท ไอ จำกัด']);
      expect(groupNames('quiet')).toEqual(['บริษัท เอฟ จำกัด']);
      expect(screen.getByTestId('sales-group-follow').textContent).toContain('2 ดีล');
      expect(screen.getByTestId('worklist-total').textContent).toBe('8');
      expect(screen.getByText('รวม 8 ดีล').className).toContain('sr-only');
    });

    it('puts the follow-up date on the row: เลยนัด for overdue, นัดวันนี้ for today', async () => {
      renderOverview();
      await screen.findByTestId('sales-group-follow');

      expect(screen.getByTestId('sales-row-604').textContent).toContain(`เลยนัด ${formatThaiDate(OVERDUE_DATE)}`);
      expect(screen.getByTestId('sales-row-605').textContent).toContain('นัดวันนี้');
      expect(screen.getByTestId('sales-row-601').textContent).not.toMatch(/เลยนัด|นัดวันนี้/);
    });

    it('shows three quiet deals and expands the rest in place', async () => {
      const quiet = Array.from({ length: 5 }, (_, i) => ({
        ...deals[5], id: 900 + i, code: `PR-2026-09${i}`, customerName: `บริษัท เงียบ ${i}`,
      }));
      api.tickets.list.mockResolvedValue({ tickets: quiet });
      // Each sits with the CEO, like deal F: stale with nothing for the rep to do -> บันทึกกิจกรรม.
      api.pricingRequests.queue.mockResolvedValue({
        items: quiet.map((d, i) => ({ id: 50 + i, ticketId: d.id, status: 'CEO_REVIEWING', orderConfirmedAt: null })),
      });
      renderOverview();
      await screen.findByTestId('sales-group-quiet');

      expect(groupNames('quiet')).toHaveLength(3);
      const toggle = screen.getByRole('button', { name: 'แสดงอีก 2 ดีล' });
      fireEvent.click(toggle);
      expect(groupNames('quiet')).toHaveLength(5);
      // The same button stays (keyboard focus is not dropped) and folds the group back.
      expect(toggle.textContent).toBe('แสดงน้อยลง');
      expect(toggle.getAttribute('aria-expanded')).toBe('true');
      fireEvent.click(toggle);
      expect(groupNames('quiet')).toHaveLength(3);
    });

    it('opens the deal from its row', async () => {
      renderOverview();
      fireEvent.click(await screen.findByTestId('sales-row-604'));
      expect((await screen.findByTestId('location-probe')).textContent).toBe('/tickets/604');
    });

    it('says so when nothing needs the rep', async () => {
      api.tickets.list.mockResolvedValue({ tickets: [deals[6]] });
      renderOverview();
      expect(await screen.findByText('ไม่มีสิ่งที่ต้องดำเนินการตอนนี้')).not.toBeNull();
    });

    it('says a failed deal list failed, and keeps what it shows when a background refetch fails', async () => {
      renderOverview();
      await screen.findByTestId('sales-row-604');
      api.tickets.list.mockRejectedValue(new Error('offline'));
      await act(async () => {
        await lastClient.refetchQueries();
        await new Promise((resolve) => { setTimeout(resolve, 20); }); // let the error notification render
      });
      expect(lastClient.getQueryCache().findAll().some((q) => q.state.status === 'error')).toBe(true);
      expect(screen.getByTestId('sales-row-604')).not.toBeNull();
      expect(screen.queryByText('โหลดไม่สำเร็จ')).toBeNull();
    });

    it('says the list failed when the pricing-request queue fails, since every action reads it', async () => {
      api.pricingRequests.queue.mockRejectedValue(new Error('offline'));
      renderOverview();
      expect(await within(worklistSection()).findByText('โหลดไม่สำเร็จ')).not.toBeNull();
    });

    it('shows an em dash for the commission figures when the summary fails', async () => {
      api.commissions.monthlySummary.mockRejectedValue(new Error('offline'));
      renderOverview();
      await waitFor(() => expectFigure('ค่าคอมประมาณการ (เดือนนี้)', '—'));
      expectFigure('ฐานคิดค่าคอม', '—');
    });

    it('says the list failed when the first load fails', async () => {
      api.tickets.list.mockRejectedValue(new Error('offline'));
      renderOverview();
      expect(await within(worklistSection()).findByText('โหลดไม่สำเร็จ')).not.toBeNull();
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

  function badgeFor(customerName) {
    const row = within(worklistSection()).getByText(customerName).closest('button');
    return row.querySelector('.status-badge');
  }

  it('lists all three: CONFIRM then SUBMIT under ต้องทำต่อ, AWAIT under รอผู้อื่น', async () => {
    renderOverview();
    const worklist = within(worklistSection());
    await worklist.findByText('บริษัท ร่าง จำกัด');
    expect(groupNames('next')).toEqual(['บริษัท อนุมัติ จำกัด', 'บริษัท ร่าง จำกัด']);
    expect(groupNames('wait')).toEqual(['บริษัท รอ จำกัด']);
    // None of them is told to open a คำขอราคา.
    expect(worklist.queryByText('สร้างคำขอราคา')).toBeNull();
  });

  it('SUBMIT and CONFIRM read as warning (mine to act); AWAIT reads neutral (waiting)', async () => {
    renderOverview();
    await within(worklistSection()).findByText('บริษัท ร่าง จำกัด');
    expect(badgeFor('บริษัท ร่าง จำกัด').textContent).toBe('ส่งขออนุมัติใบเสนอราคา');
    expect(badgeFor('บริษัท ร่าง จำกัด').className).toContain('status-warning');
    expect(badgeFor('บริษัท อนุมัติ จำกัด').textContent).toBe('ยืนยันคำสั่งซื้อ');
    expect(badgeFor('บริษัท อนุมัติ จำกัด').className).toContain('status-warning');
    expect(badgeFor('บริษัท รอ จำกัด').textContent).toBe('รออนุมัติใบเสนอราคา');
    expect(badgeFor('บริษัท รอ จำกัด').className).toContain('status-neutral');
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
});
