import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ManagerOverview } from './ManagerOverview.jsx';
import { api } from '../../api/index.js';
import {
  CASH_BY_REP, CASH_RECEIVED_THIS_MONTH, SERVER_TOTALS, figureRows,
} from '../../test/fixtures/dashboardMoney.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: { list: vi.fn() },
      commissions: { list: vi.fn(), pendingApproval: vi.fn() },
      salesBoard: { teamOverview: vi.fn() },
      salesAnalytics: { snapshot: vi.fn(), upload: vi.fn() },
    },
  };
});

const managerUser = { role: 'sales_manager', name: 'ผู้จัดการ ทดสอบ', employeeId: 9 };
const employee = { nickName: 'บอส', nameTh: 'ผู้จัดการ ทดสอบ' };
import { bangkokTodayIso, formatThaiDate } from '../../utils/format.js';

const linkTo = (row, href) => [...row.querySelectorAll('a')].find((a) => a.getAttribute('href') === href);
const TODAY = bangkokTodayIso();
function addDays(iso, delta) {
  const d = new Date(`${iso}T00:00:00.000Z`);
  d.setUTCDate(d.getUTCDate() + delta);
  return d.toISOString().slice(0, 10);
}

const summary = { cashReceivedThisMonth: CASH_RECEIVED_THIS_MONTH, cashReceivedByRep: CASH_BY_REP, ...SERVER_TOTALS };


const now = new Date();
const thisMonthIso = new Date(now.getFullYear(), now.getMonth(), 5).toISOString();

function deal(id, rep, extra = {}) {
  return {
    id, code: `DL-${id}`, title: `โครงการ ${id}`, customerName: `ลูกค้า ${id}`,
    createdById: rep === 'เซลส์ เอ' ? 11 : 12, createdByName: rep,
    status: 'quotation_issued', lifecycle: 'ACTIVE', salesStage: 'NEGOTIATION',
    stageUpdatedAt: '2026-06-01T00:00:00.000Z', updatedAt: '2026-06-01T00:00:00.000Z',
    amountPayable: 80000, amountOutstanding: 0, overdue: false, stale: false, nextFollowUpAt: null,
    ...extra,
  };
}

// Won and lost deals feed อัตราปิดงาน (1 won / 1 lost = 50%); the healthy one must never be flagged.
const wonTicket = deal(201, 'เซลส์ เอ', { status: 'closed', salesStage: 'CLOSED_PAID', stageUpdatedAt: thisMonthIso });
const lostTicket = deal(209, 'เซลส์ บี', { lifecycle: 'CLOSED_LOST' });
const healthyTicket = deal(203, 'เซลส์ เอ', { salesStage: 'ORDER_RECEIVED', stageUpdatedAt: thisMonthIso });
// Needing attention, deliberately listed stale-first so the test proves the sort, not the input order.
const staleB1 = deal(202, 'เซลส์ บี', { stale: true });
const staleB2 = deal(204, 'เซลส์ บี', { stale: true });
const staleA1 = deal(205, 'เซลส์ เอ', { stale: true });
const staleA2 = deal(206, 'เซลส์ เอ', { stale: true });
const followUp = deal(207, 'เซลส์ เอ', { nextFollowUpAt: '2026-01-01T00:00:00.000Z' });
const overdue = deal(208, 'เซลส์ เอ', { overdue: true, amountOutstanding: 15000 });
const TICKETS = [wonTicket, lostTicket, healthyTicket, staleB1, staleB2, staleA1, staleA2, followUp, overdue];

const TEAM = {
  quietDays: 14,
  reps: [{ employeeId: 11, name: 'เซลส์ เอ', openDeals: 2, openValue: 160000, quietDeals: 1, lastUpdateAt: '2026-11-14T00:00:00Z' }],
  stale: [],
};

function commission(id, base, extra = {}) {
  return {
    id, sourceTicketId: 201, salesRepId: 11, salesRepName: `เซลส์ ${id}`,
    kind: 'SALE', status: 'SUBMITTED', commissionableBase: base,
    invoiceDetails: { invoiceNumber: `INV-${id}` }, ...extra,
  };
}
const SUBMITTED = [1000, 2000, 3000, 4000, 5000, 6000].map((base, i) => commission(301 + i, base));
// GET /api/commissions/pending-approval: the manager's own queue (SUBMITTED SALE records, any month),
// exactly what /commissions?view=pending lists. The deal code comes with each entry.
const pendingOf = (records) => ({ commissions: records.map((c) => ({ commission: c, ticketCode: `DL-${c.id}` })) });

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location-probe">{location.pathname}{location.search}</div>;
}

function renderOverview(dashboardSummary = summary, entry = '/') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[entry]}>
        <Routes>
          <Route
            path="/"
            element={<ManagerOverview user={managerUser} employee={employee} dashboardSummary={dashboardSummary} showToast={vi.fn()} />}
          />
          <Route path="*" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const probeText = () => screen.getByTestId('location-probe').textContent;

describe('ManagerOverview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.tickets.list.mockResolvedValue({ tickets: TICKETS });
    api.commissions.pendingApproval.mockResolvedValue(pendingOf(SUBMITTED));
    api.salesBoard.teamOverview.mockResolvedValue(TEAM);
  });

  // Spec 2026-11-15 §7.4: the body is ภาพรวมทีมขาย (TeamOverview, pinned in salesBoard/TeamOverview.test.jsx); the
  // commission approval queue stays below it.
  it('is the team overview plus the manager\'s own approval queue, and reads neither the deal list nor history', async () => {
    renderOverview();

    expect(screen.getByRole('heading', { name: 'ภาพรวมทีมขาย' })).toBeTruthy();
    await screen.findByTestId('tile-held');
    await screen.findByTestId('commission-row-301');
    expect(api.salesBoard.teamOverview).toHaveBeenCalledTimes(1);
    expect(api.commissions.pendingApproval).toHaveBeenCalledTimes(1);
    expect(api.commissions.list).not.toHaveBeenCalled();
    // The page header already says ภาพรวมทีมขาย; TeamOverview does not repeat it.
    expect(screen.getAllByRole('heading', { name: 'ภาพรวมทีมขาย' })).toHaveLength(1);
  });

  it('drops the cash money band and the blanket ดีลที่ต้องดูแล flags', async () => {
    renderOverview();
    await screen.findByTestId('tile-held');

    ['เงินรับแล้ว (เดือนนี้)', 'ค้างรับ', 'เกินกำหนดชำระ', 'ยอดคาดการณ์'].forEach((label) => {
      expect(figureRows(label), label).toEqual([]);
    });
    expect(screen.queryByRole('heading', { name: 'ดีลที่ต้องดูแล' })).toBeNull();
    expect(screen.queryByText(/ไม่มีความเคลื่อนไหว 7 วัน/)).toBeNull();
    expect(screen.queryByText('฿0.00')).toBeNull();
  });

  describe('ค่าคอมรอคุณตรวจ', () => {
    it('lists the queue, at most five, with the deal code and the total base of all of them', async () => {
      renderOverview();
      const panel = (await screen.findByTestId('commission-row-301')).closest('section');

      expect(panel.querySelectorAll('[data-testid^="commission-row-"]')).toHaveLength(5);
      expect(within(panel).getByTestId('commission-row-301').textContent).toContain('INV-301 · DL-301');
      expect(screen.getByText('6 รายการ').className).toContain('sr-only');
      expect(within(panel).getByTestId('commission-row-301').textContent).toContain('฿1,000.00');
      expect(panel.textContent).toContain('อีก 1 รายการ');
      // 1,000 + … + 6,000: every SUBMITTED record, not just the five shown.
      expect(panel.textContent).toContain('฿21,000.00');
    });

    it('opens the manager\'s own approval queue', async () => {
      renderOverview();
      const panel = (await screen.findByTestId('commission-row-301')).closest('section');

      fireEvent.click(within(panel).getByRole('button', { name: 'เปิดหน้าอนุมัติ' }));
      await screen.findByTestId('location-probe');
      expect(probeText()).toBe('/commissions?view=pending');
    });

    it('says so when nothing waits', async () => {
      api.commissions.pendingApproval.mockResolvedValue(pendingOf([]));
      renderOverview();

      expect(await screen.findByText('ไม่มีค่าคอมรออนุมัติตอนนี้')).not.toBeNull();
    });
  });


  // Review H3: restored below TeamOverview, unchanged in behaviour (tests copied from the pre-slice-1 file).
  describe('ความเคลื่อนไหวของทีม panel', () => {
    const mv = (id, ownerId, ownerName, extra = {}) => deal(id, ownerName, {
      createdById: ownerId, lifecycle: 'ACTIVE', stageChangedInRange: false, activityInRange: false,
      nextFollowUpAt: null, ...extra,
    });
    const TEAM_DEALS = [
      mv(301, 11, 'เซลส์ เอ', { stageChangedInRange: true }),
      mv(302, 11, 'เซลส์ เอ', { nextFollowUpAt: '2020-01-01' }),
      mv(303, 11, 'เซลส์ เอ'),
      mv(304, 12, 'เซลส์ บี', { activityInRange: true }),
      mv(305, 12, 'เซลส์ บี', { lifecycle: 'CLOSED_LOST' }),
      mv(306, 13, 'เซลส์ ซี', { lifecycle: 'CLOSED_LOST' }),
      mv(307, 11, 'เซลส์ เอ', { lifecycle: 'ON_HOLD', stageChangedInRange: true, activityInRange: true }),
    ];
    const rowIds = () => [...document.querySelectorAll('[data-testid^="team-row-"]')]
      .map((el) => el.dataset.testid.replace('team-row-', ''));

    beforeEach(() => {
      api.tickets.list.mockResolvedValue({ tickets: TEAM_DEALS });
    });

    it('renders one row per owner with open deals, ไม่เคลื่อนไหว desc, with the default range in the header', async () => {
      renderOverview();
      const panel = (await screen.findByRole('heading', { name: 'ความเคลื่อนไหวของทีม' })).closest('section');
      await within(panel).findByTestId('team-row-11');
      expect(rowIds()).toEqual(['11', '12']);
      expect(panel.textContent).toContain(formatThaiDate(addDays(TODAY, -6)));
      expect(panel.textContent).toContain(formatThaiDate(TODAY));
      const rowA = within(panel).getByTestId('team-row-11');
      const count = (href) => linkTo(rowA, href)?.textContent.trim();
      expect(rowA.querySelectorAll('td')[0].textContent.trim()).toBe('3');
      expect(count(`/tickets?owner=11&week=moved&from=${addDays(TODAY, -6)}&to=${TODAY}`)).toBe('1');
      expect(count(`/tickets?owner=11&week=silent&from=${addDays(TODAY, -6)}&to=${TODAY}`)).toBe('2');
      expect(count(`/tickets?owner=11&week=overdue&from=${addDays(TODAY, -6)}&to=${TODAY}`)).toBe('1');
    });

    it('a count links to /tickets?owner=<id>&week=<key>', async () => {
      renderOverview();
      const row = await screen.findByTestId('team-row-12');
      fireEvent.click(linkTo(row, `/tickets?owner=12&week=activity&from=${addDays(TODAY, -6)}&to=${TODAY}`));
      expect(await screen.findByTestId('location-probe')).toBeTruthy();
      expect(probeText()).toBe(`/tickets?owner=12&week=activity&from=${addDays(TODAY, -6)}&to=${TODAY}`);
    });

    it('reads the deal list once, with the default 7-day range', async () => {
      renderOverview();
      await screen.findByTestId('team-row-11');
      expect(api.tickets.list).toHaveBeenCalledTimes(1);
      expect(api.tickets.list).toHaveBeenCalledWith({ movementFrom: addDays(TODAY, -6), movementTo: TODAY });
    });
  });

  it('shows อัตราปิดงาน as one line from won vs lost deals', async () => {
    renderOverview();
    const heading = await screen.findByRole('heading', { name: 'อัตราปิดงาน' });
    const panel = heading.closest('section');
    await waitFor(() => expect(panel.textContent).toContain('50%'));
    expect(panel.textContent).toContain('ปิดสำเร็จ 1 · เสีย 1');
  });
});

// Sales insights (spec 2026-11-16): same switch as the CEO's, state in ?view=insight; the body is pinned in
// SalesInsights.test.jsx. A sales_manager reads the snapshot but never gets the upload control.
describe('ManagerOverview: งานวันนี้ / วิเคราะห์ยอดขาย', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.tickets.list.mockResolvedValue({ tickets: TICKETS });
    api.commissions.pendingApproval.mockResolvedValue(pendingOf(SUBMITTED));
    api.salesBoard.teamOverview.mockResolvedValue(TEAM);
    api.salesAnalytics.snapshot.mockResolvedValue({ snapshot: null });
  });

  it('defaults to today\'s dashboard with the switch present', () => {
    renderOverview();

    expect(screen.getByRole('tab', { name: 'งานวันนี้' }).getAttribute('aria-selected')).toBe('true');
    expect(screen.getByRole('tab', { name: 'วิเคราะห์ยอดขาย' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'ภาพรวมทีมขาย' })).toBeTruthy();
    expect(screen.queryByTestId('sales-insights')).toBeNull();
  });

  it('?view=insight shows the insight view, and the empty state has no upload control for a manager', async () => {
    renderOverview(summary, '/?view=insight');

    expect(await screen.findByTestId('sales-insights')).toBeTruthy();
    expect(await screen.findByTestId('insight-empty')).toBeTruthy();
    expect(screen.queryByLabelText('อัปโหลดข้อมูลวิเคราะห์')).toBeNull();
    expect(screen.queryByRole('heading', { name: 'อัตราปิดงาน' })).toBeNull();
  });

  it('clicking the switch toggles the view', async () => {
    renderOverview();

    fireEvent.click(screen.getByRole('tab', { name: 'วิเคราะห์ยอดขาย' }));
    expect(await screen.findByTestId('sales-insights')).toBeTruthy();
    fireEvent.click(screen.getByRole('tab', { name: 'งานวันนี้' }));
    expect(screen.queryByTestId('sales-insights')).toBeNull();
  });
});
