import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ManagerOverview } from './ManagerOverview.jsx';
import { api } from '../../api/index.js';
import { bangkokTodayIso, formatThaiDate } from '../../utils/format.js';
import {
  CASH_BY_REP, CASH_RECEIVED_THIS_MONTH, EXPECTED, SERVER_TOTALS, expectFigure, figureRows,
} from '../../test/fixtures/dashboardMoney.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: { list: vi.fn() },
      commissions: { list: vi.fn(), pendingApproval: vi.fn() },
    },
  };
});

const managerUser = { role: 'sales_manager', name: 'ผู้จัดการ ทดสอบ', employeeId: 9 };
const employee = { nickName: 'บอส', nameTh: 'ผู้จัดการ ทดสอบ' };
const summary = { cashReceivedThisMonth: CASH_RECEIVED_THIS_MONTH, cashReceivedByRep: CASH_BY_REP, ...SERVER_TOTALS };

const linkTo = (row, href) => [...row.querySelectorAll('a')].find((a) => a.getAttribute('href') === href);
const TODAY = bangkokTodayIso();
function addDays(iso, delta) {
  const d = new Date(`${iso}T00:00:00.000Z`);
  d.setUTCDate(d.getUTCDate() + delta);
  return d.toISOString().slice(0, 10);
}

const now = new Date();
const thisMonthIso = new Date(now.getFullYear(), now.getMonth(), 5).toISOString();
const never = () => new Promise(() => {});

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

let lastClient;

function renderOverview(dashboardSummary = summary) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  lastClient = queryClient;
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/']}>
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
const panelOf = (title) => screen.getByRole('heading', { name: title }).closest('section');
const dealRowIds = () => [...document.querySelectorAll('[data-testid^="deal-row-"]')]
  .map((el) => el.dataset.testid.replace('deal-row-', ''));

describe('ManagerOverview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.tickets.list.mockResolvedValue({ tickets: TICKETS });
    api.commissions.pendingApproval.mockResolvedValue(pendingOf(SUBMITTED));
  });

  it('reads the deal list and the manager\'s own approval queue, never the whole commission history', async () => {
    renderOverview();
    await screen.findByTestId('deal-row-208');

    // one list call, now carrying the default 7-day range for the team-movement panel
    expect(api.tickets.list).toHaveBeenCalledTimes(1);
    expect(api.tickets.list).toHaveBeenCalledWith({ movementFrom: addDays(TODAY, -6), movementTo: TODAY });
    expect(api.commissions.pendingApproval).toHaveBeenCalledTimes(1);
    expect(api.commissions.list).not.toHaveBeenCalled();
  });

  describe('ความเคลื่อนไหวของทีม panel', () => {
    const mv = (id, ownerId, ownerName, extra = {}) => deal(id, ownerName, {
      createdById: ownerId, lifecycle: 'ACTIVE', stageChangedInRange: false, activityInRange: false,
      nextFollowUpAt: null, ...extra,
    });
    const TEAM = [
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
      api.tickets.list.mockResolvedValue({ tickets: TEAM });
    });

    it('renders one row per owner with open deals, ไม่เคลื่อนไหว desc, with the default range in the header', async () => {
      renderOverview();
      const panel = (await screen.findByRole('heading', { name: 'ความเคลื่อนไหวของทีม' })).closest('section');
      await within(panel).findByTestId('team-row-11');
      expect(rowIds()).toEqual(['11', '12']); // เอ has 2 silent deals, บี 0; เซลส์ ซี has no open deal
      for (const h of ['ผู้ดูแล', 'ดีลที่เปิดอยู่', 'ขยับขั้น', 'มีกิจกรรม', 'เลยนัด', 'ไม่เคลื่อนไหว', 'ยอดที่สั่งซื้อแล้ว', 'ยอดคาดหวัง']) {
        expect(panel.textContent).toContain(h);
      }
      expect(panel.textContent).toContain(formatThaiDate(addDays(TODAY, -6)));
      expect(panel.textContent).toContain(formatThaiDate(TODAY));
      const rowA = within(panel).getByTestId('team-row-11');
      expect(rowA.textContent).toContain('เซลส์ เอ');
      const count = (href) => linkTo(rowA, href)?.textContent.trim();
      // ดีลที่เปิดอยู่ is a plain number, not a link; the paused deal below counts nowhere
      expect(linkTo(rowA, '/tickets?owner=11')).toBeUndefined();
      expect(rowA.querySelectorAll('td')[0].textContent.trim()).toBe('3');
      expect(count('/tickets?owner=11&week=moved')).toBe('1');
      expect(count('/tickets?owner=11&week=silent')).toBe('2');
      // เลยนัด links to its own filter, so the list shows exactly the counted deals
      expect(count('/tickets?owner=11&week=overdue')).toBe('1');
    });

    it('a count links to /tickets?owner=<id>&week=<key>', async () => {
      renderOverview();
      const row = await screen.findByTestId('team-row-12');
      fireEvent.click(linkTo(row, '/tickets?owner=12&week=activity'));
      expect(await screen.findByTestId('location-probe')).toBeTruthy();
      expect(probeText()).toBe('/tickets?owner=12&week=activity');
    });
  });

  describe('loading (progressive)', () => {
    it('shows the money band from the summary while both queries are still loading', () => {
      api.tickets.list.mockImplementation(never);
      api.commissions.pendingApproval.mockImplementation(never);
      renderOverview();

      expectFigure('เงินรับแล้ว (เดือนนี้)', EXPECTED.cash);
      expectFigure('ค้างรับ', EXPECTED.outstanding);
      expectFigure('ยอดคาดการณ์', EXPECTED.forecast);
      expectFigure('เกินกำหนดชำระ', EXPECTED.overdue);
    });

    it('renders the commission queue while the deal list is still loading', async () => {
      api.tickets.list.mockImplementation(never);
      renderOverview();

      expect(await screen.findByTestId('commission-row-301')).not.toBeNull();
      expect(panelOf('ดีลที่ต้องดูแล').querySelector('[aria-busy="true"]')).not.toBeNull();
    });

    it('keeps what it already shows when a background refetch fails', async () => {
      renderOverview();
      await screen.findByTestId('deal-row-208');
      api.tickets.list.mockRejectedValue(new Error('offline'));
      api.commissions.pendingApproval.mockRejectedValue(new Error('offline'));
      await act(async () => {
        await lastClient.refetchQueries();
        await new Promise((resolve) => { setTimeout(resolve, 20); }); // let the error notification render
      });
      // The refetch really failed (React Query keeps the last data alongside the error).
      expect(lastClient.getQueryCache().findAll().some((q) => q.state.status === 'error')).toBe(true);

      expect(screen.getByTestId('deal-row-208')).not.toBeNull();
      expect(screen.getByTestId('commission-row-301')).not.toBeNull();
      expect(screen.queryByText('โหลดไม่สำเร็จ')).toBeNull();
    });

    it('says a failed source failed instead of loading forever', async () => {
      api.tickets.list.mockRejectedValue(new Error('boom'));
      renderOverview();

      expect(await within(panelOf('ดีลที่ต้องดูแล')).findByText('โหลดไม่สำเร็จ')).not.toBeNull();
      // The overdue figure is a server total: a failed deal list does not touch it.
      expectFigure('เกินกำหนดชำระ', EXPECTED.overdue);
      expect(screen.getByTestId('commission-row-301')).not.toBeNull();
    });
  });

  describe('money band', () => {
    it('shows the same three money figures as /finance and the CEO dashboard', () => {
      renderOverview();

      expectFigure('เงินรับแล้ว (เดือนนี้)', EXPECTED.cash);
      expectFigure('ค้างรับ', EXPECTED.outstanding);
      expectFigure('ยอดคาดการณ์', EXPECTED.forecast);
    });

    it('shows the server\'s overdue total, never a sum of the 500-row deal list', async () => {
      renderOverview();
      await screen.findByTestId('deal-row-208');

      expectFigure('เกินกำหนดชำระ', EXPECTED.overdue);
      // A deal-row badge reads exactly "เกินกำหนดชำระ" too; the helper reads only the money band.
      expect(within(screen.getByTestId('deal-row-208')).getByText('เกินกำหนดชำระ')).not.toBeNull();
    });

    it('shows an em dash for ค้างรับ and ยอดคาดการณ์ when the summary has no totals, never a sum of rows', () => {
      renderOverview({ cashReceivedThisMonth: CASH_RECEIVED_THIS_MONTH });

      expectFigure('ค้างรับ', '—');
      expectFigure('ยอดคาดการณ์', '—');
      expectFigure('เกินกำหนดชำระ', '—');
    });

    it('drops the stat tiles, the team ranking and the old team-sales / pipeline figures', async () => {
      renderOverview();
      await screen.findByTestId('deal-row-208');

      expect(document.querySelector('.stat-card')).toBeNull();
      expect(screen.queryByText(/อันดับทีม/)).toBeNull();
      ['ยอดทีมเดือนนี้', 'pipeline ทีม'].forEach((label) => expect(figureRows(label), label).toEqual([]));
    });
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

  describe('ดีลที่ต้องดูแล', () => {
    it('puts overdue first, then due follow-ups, then stale deals, five at a time, healthy deals never', async () => {
      renderOverview();
      await screen.findByTestId('deal-row-208');

      expect(dealRowIds()).toEqual(['208', '207', '202', '204', '205']);
      expect(screen.queryByTestId('deal-row-203')).toBeNull();
      const panel = panelOf('ดีลที่ต้องดูแล');
      expect(within(panel).getByTestId('deal-row-208').textContent).toContain('เกินกำหนดชำระ');
      expect(within(panel).getByTestId('deal-row-208').textContent).toContain('เซลส์ เอ');
    });

    it('filters by reason with a count on each chip', async () => {
      renderOverview();
      await screen.findByTestId('deal-row-208');
      const chips = within(screen.getByRole('group', { name: 'กรองตามเหตุผล' }));

      expect(chips.getByRole('button', { name: /ทั้งหมด\s*6/ }).getAttribute('aria-pressed')).toBe('true');
      expect(chips.getByRole('button', { name: /เกินกำหนดชำระ\s*1/ })).not.toBeNull();
      expect(chips.getByRole('button', { name: /ถึงกำหนดติดตาม\s*1/ })).not.toBeNull();
      fireEvent.click(chips.getByRole('button', { name: /ไม่มีความเคลื่อนไหว 7 วัน\s*4/ }));

      expect(dealRowIds()).toEqual(['202', '204', '205', '206']);
    });

    it('offers only chips that have deals, and falls back to ทั้งหมด when a chosen reason empties', async () => {
      api.tickets.list.mockResolvedValue({ tickets: [overdue, staleA1] });
      renderOverview();
      await screen.findByTestId('deal-row-208');
      const chips = () => within(screen.getByRole('group', { name: 'กรองตามเหตุผล' }));

      expect(chips().queryByRole('button', { name: /ถึงกำหนดติดตาม/ })).toBeNull();
      fireEvent.click(chips().getByRole('button', { name: /เกินกำหนดชำระ\s*1/ }));
      expect(dealRowIds()).toEqual(['208']);

      // The overdue deal gets paid: its chip goes, and the list does not strand on an empty filter.
      api.tickets.list.mockResolvedValue({ tickets: [staleA1] });
      await act(async () => {
        await lastClient.refetchQueries();
        await new Promise((resolve) => { setTimeout(resolve, 20); });
      });
      expect(dealRowIds()).toEqual(['205']);
      expect(chips().getByRole('button', { name: /ทั้งหมด/ }).getAttribute('aria-pressed')).toBe('true');
    });

    it('opens a deal from its row', async () => {
      renderOverview();
      fireEvent.click(await screen.findByTestId('deal-row-208'));
      await screen.findByTestId('location-probe');
      expect(probeText()).toBe('/tickets/208');
    });

    it('links the deals beyond the first five to the deal list', async () => {
      renderOverview();
      await screen.findByTestId('deal-row-208');

      fireEvent.click(screen.getByRole('button', { name: /ดูอีก 1 ดีล/ }));
      await screen.findByTestId('location-probe');
      expect(probeText()).toBe('/tickets');
    });

    it('counts deals needing attention per rep, most first', async () => {
      renderOverview();
      await screen.findByTestId('deal-row-208');

      const rows = [...panelOf('ดีลที่ต้องดูแล ตามพนักงาน').querySelectorAll('[data-testid="attention-rep"]')]
        .map((el) => el.textContent);
      expect(rows).toHaveLength(2);
      expect(rows[0]).toContain('เซลส์ เอ');
      expect(rows[0]).toContain('4 ดีล');
      expect(rows[1]).toContain('เซลส์ บี');
      expect(rows[1]).toContain('2 ดีล');
    });

    it('says so when no deal needs attention', async () => {
      api.tickets.list.mockResolvedValue({ tickets: [wonTicket, healthyTicket] });
      renderOverview();

      expect(await screen.findByText('ไม่มีดีลที่ต้องติดตามตอนนี้')).not.toBeNull();
    });
  });

  it('shows อัตราปิดงาน as one line from won vs lost deals', async () => {
    renderOverview();
    await screen.findByTestId('deal-row-208');

    const panel = panelOf('อัตราปิดงาน');
    expect(panel.textContent).toContain('50%');
    expect(panel.textContent).toContain('ปิดสำเร็จ 1 · เสีย 1');
  });
});
