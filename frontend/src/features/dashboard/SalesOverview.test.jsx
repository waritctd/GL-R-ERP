import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SalesOverview } from './SalesOverview.jsx';
import { api } from '../../api/index.js';
import { bangkokTodayIso } from '../../utils/format.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: { list: vi.fn() },
      pricingRequests: { queue: vi.fn() },
      commissions: { monthlySummary: vi.fn() },
    },
  };
});

const salesUser = { employeeId: 1, name: 'Sales Test', role: 'sales' };
const employee = { nameTh: 'สมชาย ใจดี' };

// Dates are derived from the REAL `bangkokTodayIso()` (never faked/frozen) so
// the fixtures always agree with what the component itself computes at
// render time — see the repo's Bangkok-timezone memory note: a bare
// `new Date()`/fake-timer comparison can disagree with the Asia/Bangkok
// calendar day, and faking the clock here would also stall
// @testing-library's setTimeout-based `waitFor`/`findBy*` polling.
function addDays(isoDate, delta) {
  const date = new Date(`${isoDate}T00:00:00.000Z`);
  date.setUTCDate(date.getUTCDate() + delta);
  return date.toISOString().slice(0, 10);
}
const TODAY = bangkokTodayIso();
const OVERDUE_DATE = addDays(TODAY, -5);
const THIS_MONTH = `${TODAY.slice(0, 7)}-01`;

// One deal per next-action bucket (now including RECORD_DELIVERY — deal I —
// and RECORD_QUOTATION_OUTCOME — deal J), plus a "nothing to do" deal (G)
// and a non-ACTIVE deal (H) that must be excluded from both the pulse and
// the worklist entirely.
const deals = [
  { id: 601, code: 'PR-2026-0601', customerName: 'บริษัท เอ จำกัด', title: 'ดีลเอ', lifecycle: 'ACTIVE', amountPayable: 100000, stale: false, nextFollowUpAt: null, stageUpdatedAt: '2026-07-01T00:00:00.000Z' },
  { id: 602, code: 'PR-2026-0602', customerName: 'บริษัท บี จำกัด', title: 'ดีลบี', lifecycle: 'ACTIVE', amountPayable: 200000, stale: false, nextFollowUpAt: null, stageUpdatedAt: '2026-07-01T00:00:00.000Z' },
  { id: 603, code: 'PR-2026-0603', customerName: 'บริษัท ซี จำกัด', title: 'ดีลซี', lifecycle: 'ACTIVE', amountPayable: 150000, stale: false, nextFollowUpAt: null, stageUpdatedAt: '2026-07-01T00:00:00.000Z' },
  { id: 604, code: 'PR-2026-0604', customerName: 'บริษัท ดี จำกัด', title: 'ดีลดี', lifecycle: 'ACTIVE', amountPayable: 50000, stale: false, nextFollowUpAt: OVERDUE_DATE, stageUpdatedAt: '2026-07-01T00:00:00.000Z' },
  { id: 605, code: 'PR-2026-0605', customerName: 'บริษัท อี จำกัด', title: 'ดีลอี', lifecycle: 'ACTIVE', amountPayable: 75000, stale: false, nextFollowUpAt: TODAY, stageUpdatedAt: '2026-07-01T00:00:00.000Z' },
  { id: 606, code: 'PR-2026-0606', customerName: 'บริษัท เอฟ จำกัด', title: 'ดีลเอฟ', lifecycle: 'ACTIVE', amountPayable: 25000, stale: true, nextFollowUpAt: null, stageUpdatedAt: '2026-07-01T00:00:00.000Z' },
  { id: 607, code: 'PR-2026-0607', customerName: 'บริษัท จี จำกัด', title: 'ดีลจี', lifecycle: 'ACTIVE', amountPayable: 10000, stale: false, nextFollowUpAt: null, stageUpdatedAt: '2026-07-01T00:00:00.000Z' },
  { id: 608, code: 'PR-2026-0608', customerName: 'บริษัท เอช จำกัด', title: 'ดีลเอช (ปิดแล้ว)', lifecycle: 'CLOSED_LOST', amountPayable: 999999, stale: false, nextFollowUpAt: OVERDUE_DATE, stageUpdatedAt: '2026-07-01T00:00:00.000Z' },
  // Deal I: stages 13-14 (ส่งมอบสินค้า) handoff to sales, owner ruling 2026-08-17 — delivery-ready
  // (GOODS_RECEIVED) with ZERO pricing requests but a non-null paymentStatus, the exact "priced
  // outside the PCR chain" shape of demoData.js tickets 13/14 (Mega Bangna Retail / IconSiam
  // Riverside) — bucket 1's pricedOutsidePcrChain guard must let RECORD_DELIVERY through rather
  // than parking this on CREATE_PCR (see salesActions.js bucket 1's own comment, and
  // salesActions.test.js's dedicated bucket-1-interaction cases).
  { id: 609, code: 'PR-2026-0609', customerName: 'บริษัท ไอ จำกัด', title: 'ดีลไอ', lifecycle: 'ACTIVE', amountPayable: 300000, stale: false, nextFollowUpAt: null, stageUpdatedAt: '2026-07-01T00:00:00.000Z', status: 'quotation_issued', fulfillmentStatus: 'GOODS_RECEIVED', paymentStatus: 'AWAITING_FINAL_PAYMENT' },
  // Deal J: the quotation was issued to the customer but nobody has recorded the outcome yet
  // (deal-page discoverability fix — this used to fall through to follow-up/log-activity; see
  // salesActions.js bucket 4's own comment, and salesActions.test.js's dedicated cases).
  { id: 610, code: 'PR-2026-0610', customerName: 'บริษัท เจ จำกัด', title: 'ดีลเจ', lifecycle: 'ACTIVE', amountPayable: 120000, stale: false, nextFollowUpAt: null, stageUpdatedAt: '2026-07-01T00:00:00.000Z' },
];

const pricingRequests = [
  { id: 1, ticketId: 602, status: 'APPROVED_FOR_QUOTATION', orderConfirmedAt: null },
  { id: 2, ticketId: 603, status: 'QUOTATION_ACCEPTED', orderConfirmedAt: null },
  { id: 3, ticketId: 604, status: 'SUBMITTED', orderConfirmedAt: null },
  { id: 4, ticketId: 605, status: 'IMPORT_REVIEWING', orderConfirmedAt: null },
  // CEO_REVIEWING, not QUOTATION_ISSUED: deal F is the dedicated "stale, nothing else pending"
  // LOG_ACTIVITY fixture below, and QUOTATION_ISSUED now has its own bucket (see deal J's PR
  // below) — a live-but-unmatched status keeps deal F actually exercising the fall-through path.
  { id: 5, ticketId: 606, status: 'CEO_REVIEWING', orderConfirmedAt: null },
  { id: 6, ticketId: 607, status: 'IMPORT_REVIEWING', orderConfirmedAt: null },
  { id: 7, ticketId: 610, status: 'QUOTATION_ISSUED', orderConfirmedAt: null },
  // Deal 601 deliberately has no pricing request at all.
];

// Default monthlySummary fixture for tests that don't care about the commission KPI figures —
// see the two dedicated cases below for the ones that do.
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
        <SalesOverview user={salesUser} employee={employee} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function worklistSection() {
  return screen.getByText('สิ่งที่ต้องทำ').closest('section');
}

function followUpSection() {
  return screen.getByText('ติดตามที่ครบกำหนด').closest('section');
}

describe('SalesOverview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.tickets.list.mockResolvedValue({ tickets: deals });
    api.pricingRequests.queue.mockResolvedValue({ items: pricingRequests });
    api.commissions.monthlySummary.mockResolvedValue({ summary: defaultCommissionSummary });
  });

  it('greets the rep and requests an own-scoped deal list', async () => {
    renderOverview();
    expect(await screen.findByText(/สวัสดี คุณสมชาย ใจดี/)).not.toBeNull();
    expect(api.tickets.list).toHaveBeenCalledWith({});
  });

  it('computes pulse counts from the mocked own-scoped list', async () => {
    renderOverview();

    // Pipeline value depends on the tickets query alone; waiting for it to
    // settle away from its zero/loading state also guarantees the other
    // pulse cards (which additionally depend on the pricing-request queue)
    // have finished their own render pass by the time the assertions below run.
    await waitFor(() => {
      const pipelineValue = screen.getByText('มูลค่า pipeline').parentElement.querySelector('.stat-value');
      // Sum of amountPayable across ACTIVE deals only (excludes deal H,
      // CLOSED_LOST, despite its huge amountPayable) — includes deal I's
      // 300,000 and deal J's 120,000 (610,000 + 300,000 + 120,000).
      expect(pipelineValue.textContent).toBe('฿1,030,000.00');
    });

    // Overdue follow-up: only deal D (604, follow-up date before today).
    const overdueValue = screen.getByText('เกินกำหนดติดตาม').parentElement.querySelector('.stat-value');
    expect(overdueValue.textContent).toBe('1');

    // Due today: only deal E (605, follow-up date === today).
    const todayValue = screen.getByText('ติดตามวันนี้').parentElement.querySelector('.stat-value');
    expect(todayValue.textContent).toBe('1');

    // Ready-to-quote: only deal B's pricing request (APPROVED_FOR_QUOTATION).
    const quotationValue = screen.getByText('รอออกใบเสนอราคา').parentElement.querySelector('.stat-value');
    expect(quotationValue.textContent).toBe('1');
  });

  it('maps each pipeline state to the correct next-action CTA', async () => {
    renderOverview();
    const worklist = within(worklistSection());
    await within(worklistSection()).findByText('บริษัท เอ จำกัด');

    function ctaFor(customerName) {
      return worklist.getByText(customerName).closest('button');
    }

    expect(within(ctaFor('บริษัท เอ จำกัด')).getByText('สร้างคำขอราคา')).not.toBeNull(); // no PR at all
    expect(within(ctaFor('บริษัท บี จำกัด')).getByText('ออกใบเสนอราคา')).not.toBeNull(); // APPROVED_FOR_QUOTATION
    expect(within(ctaFor('บริษัท ซี จำกัด')).getByText('ยืนยันคำสั่งซื้อ')).not.toBeNull(); // QUOTATION_ACCEPTED, not confirmed
    expect(within(ctaFor('บริษัท ดี จำกัด')).getByText('ติดตามลูกค้า')).not.toBeNull(); // follow-up overdue
    expect(within(ctaFor('บริษัท อี จำกัด')).getByText('ติดตามลูกค้า')).not.toBeNull(); // follow-up due today
    expect(within(ctaFor('บริษัท เอฟ จำกัด')).getByText('บันทึกกิจกรรม')).not.toBeNull(); // stale, no follow-up
    expect(within(ctaFor('บริษัท ไอ จำกัด')).getByText('บันทึกส่งมอบ')).not.toBeNull(); // GOODS_RECEIVED, priced outside the PCR chain
    expect(within(ctaFor('บริษัท เจ จำกัด')).getByText('บันทึกผลใบเสนอราคา')).not.toBeNull(); // QUOTATION_ISSUED, outcome not recorded yet

    // Deal G has a pricing request sitting with import and nothing else
    // pending — it needs nothing from the rep right now, so it must not
    // appear in the worklist at all. Deal H is excluded for being non-ACTIVE.
    expect(worklist.queryByText('บริษัท จี จำกัด')).toBeNull();
    expect(worklist.queryByText('บริษัท เอช จำกัด')).toBeNull();
  });

  it('sorts the worklist overdue-first, ahead of the pipeline-order cascade', async () => {
    renderOverview();
    const worklist = within(worklistSection());
    await worklist.findByText('บริษัท เอ จำกัด');

    const names = worklist.getAllByText(/^บริษัท .+ จำกัด$/).map((el) => el.textContent);
    // D (overdue follow-up) leads despite CONFIRM_ORDER/ISSUE_QUOTATION/
    // RECORD_QUOTATION_OUTCOME/CREATE_PCR/RECORD_DELIVERY normally outranking
    // a bare follow-up in the action cascade — "overdue" is a cross-cutting
    // urgency signal that always sorts first. J (RECORD_QUOTATION_OUTCOME,
    // rank 3) sits between B (ISSUE_QUOTATION, rank 2) and A (CREATE_PCR,
    // rank 4); I (RECORD_DELIVERY, rank 5) sits between A and E (FOLLOW_UP
    // due today, rank 6) — the same relative ordering salesActions.test.js's
    // own sortWorklist cases pin directly against synthetic fixtures.
    expect(names).toEqual([
      'บริษัท ดี จำกัด',
      'บริษัท ซี จำกัด',
      'บริษัท บี จำกัด',
      'บริษัท เจ จำกัด',
      'บริษัท เอ จำกัด',
      'บริษัท ไอ จำกัด',
      'บริษัท อี จำกัด',
      'บริษัท เอฟ จำกัด',
    ]);
  });

  it('renders the read-only commission KPI from the server-computed monthly summary', async () => {
    api.commissions.monthlySummary.mockResolvedValue({
      summary: { ...defaultCommissionSummary, commissionableBase: 100000, totalCommission: 250 },
    });

    renderOverview();

    // findByText (not getByText) because the commissions query resolves async.
    expect(await screen.findByText('฿250.00')).not.toBeNull();
    expect(screen.getByText('฿100,000.00')).not.toBeNull();
    expect(api.commissions.monthlySummary).toHaveBeenCalledWith({ payrollMonth: THIS_MONTH });
  });

  // fix/commission-figures-from-backend (#548-style V81 regression guard): the commission KPI
  // above is now driven entirely by the SERVER-computed monthly summary (CommissionService
  // #monthlySummary), replacing a former client-side re-implementation of the tier math that
  // could silently desynchronise from a DB tier-config change (the V81 tier-13 rate correction is
  // the case on record — see CLAUDE.md). This case stubs a figure deliberately unreachable by any
  // tier table (no combination of the seeded 0.25%-3.25% bands on any base yields exactly
  // 99,999.99) and asserts the panel renders that exact server figure — not a client-recomputed
  // one. On unmodified (pre-refactor) code this failed: the panel rendered a client-recomputed
  // number derived from api.commissions.list() instead — see the C1 baseline evidence in the PR.
  it('renders the server-computed monthly summary, not a client-recomputed figure', async () => {
    api.commissions.monthlySummary.mockResolvedValue({
      summary: {
        payrollMonth: THIS_MONTH,
        salesRepId: 1,
        commissionableBase: 1200000,
        tierCommission: 99999.99,
        incentiveAmount: 0,
        manualTotal: 0,
        totalCommission: 99999.99,
        belowFloor: false,
        tiers: [],
      },
    });

    renderOverview();

    expect(await screen.findByText('฿99,999.99')).not.toBeNull();
    expect(screen.getByText('฿1,200,000.00')).not.toBeNull();
  });

  it('follows the server figure when it changes -- proves the render is wired to the DTO, not a frozen snapshot', async () => {
    api.commissions.monthlySummary.mockResolvedValue({
      summary: {
        payrollMonth: THIS_MONTH,
        salesRepId: 1,
        commissionableBase: 654321.09,
        tierCommission: 4567.89,
        incentiveAmount: 0,
        manualTotal: 0,
        totalCommission: 4567.89,
        belowFloor: false,
        tiers: [],
      },
    });

    renderOverview();

    expect(await screen.findByText('฿4,567.89')).not.toBeNull();
    expect(screen.getByText('฿654,321.09')).not.toBeNull();
  });

  it('renders the follow-up-due list, sorted soonest first', async () => {
    renderOverview();
    const followUps = within(followUpSection());
    await followUps.findByText('บริษัท ดี จำกัด');

    const rows = followUps.getAllByRole('button').map((btn) => btn.textContent);
    expect(rows[0]).toContain('บริษัท ดี จำกัด');
    expect(rows[1]).toContain('บริษัท อี จำกัด');
    // The non-ACTIVE deal never appears here even though its stored
    // nextFollowUpAt would otherwise read as overdue.
    expect(followUps.queryByText('บริษัท เอช จำกัด')).toBeNull();
  });
});

// Slice 2 — flow A (SLICE-2-FLOW-A.md §E, DESIGN.md §15): the three live-direct-quotation buckets
// reach the worklist, and their badge tone says whose move it is — SUBMIT / CONFIRM are the rep's
// own (warning), AWAIT is waiting on ผจก.ขาย/CEO (neutral), never the same tone as a task.
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

  it('lists all three, CONFIRM first (rank 1), then SUBMIT (2), then AWAIT (5)', async () => {
    renderOverview();
    const worklist = within(worklistSection());
    await worklist.findByText('บริษัท ร่าง จำกัด');
    const rows = worklist.getAllByRole('button').map((btn) => btn.textContent);
    expect(rows[0]).toContain('บริษัท อนุมัติ จำกัด');
    expect(rows[1]).toContain('บริษัท ร่าง จำกัด');
    expect(rows[2]).toContain('บริษัท รอ จำกัด');
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
