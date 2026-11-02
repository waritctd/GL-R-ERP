import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ManagerOverview } from './ManagerOverview.jsx';
import { api } from '../../api/index.js';
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
      commissions: { list: vi.fn() },
    },
  };
});

const managerUser = { role: 'sales_manager', name: 'ผู้จัดการ ทดสอบ', employeeId: 9 };
const employee = { nickName: 'บอส', nameTh: 'ผู้จัดการ ทดสอบ' };
const summary = { cashReceivedThisMonth: CASH_RECEIVED_THIS_MONTH, cashReceivedByRep: CASH_BY_REP, ...SERVER_TOTALS };

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
// MANAGER_APPROVED is the CEO's step — never on the manager's queue.
const managerApproved = commission(399, 99999, { status: 'MANAGER_APPROVED' });

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
    api.commissions.list.mockResolvedValue({ commissions: [...SUBMITTED, managerApproved] });
  });

  it('fetches the same two sources with the same params as before', async () => {
    renderOverview();
    await screen.findByTestId('deal-row-208');

    expect(api.tickets.list).toHaveBeenCalledWith({});
    expect(api.commissions.list).toHaveBeenCalledWith({ status: 'SUBMITTED' });
  });

  describe('loading (progressive)', () => {
    it('shows the money band from the summary while both queries are still loading', () => {
      api.tickets.list.mockImplementation(never);
      api.commissions.list.mockImplementation(never);
      renderOverview();

      expectFigure('เงินรับแล้ว (เดือนนี้)', EXPECTED.cash);
      expectFigure('ค้างรับ', EXPECTED.outstanding);
      expectFigure('ยอดคาดการณ์', EXPECTED.forecast);
      expect(figureRows('เกินกำหนดชำระ')[0]).not.toContain('฿');
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
      api.commissions.list.mockRejectedValue(new Error('offline'));
      await act(async () => {
        await lastClient.refetchQueries();
        await new Promise((resolve) => { setTimeout(resolve, 20); }); // let the error notification render
      });
      // The refetch really failed (React Query keeps the last data alongside the error).
      expect(lastClient.getQueryCache().findAll().some((q) => q.state.status === 'error')).toBe(true);

      expect(screen.getByTestId('deal-row-208')).not.toBeNull();
      expect(screen.getByTestId('commission-row-301')).not.toBeNull();
      expectFigure('เกินกำหนดชำระ', '฿15,000.00');
      expect(screen.queryByText('โหลดไม่สำเร็จ')).toBeNull();
    });

    it('says a failed source failed instead of loading forever', async () => {
      api.tickets.list.mockRejectedValue(new Error('boom'));
      renderOverview();

      expect(await within(panelOf('ดีลที่ต้องดูแล')).findByText('โหลดไม่สำเร็จ')).not.toBeNull();
      expectFigure('เกินกำหนดชำระ', '—');
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

    it('shows the team\'s overdue receivables from the overdue deals only', async () => {
      renderOverview();
      await screen.findByTestId('deal-row-208');

      expectFigure('เกินกำหนดชำระ', '฿15,000.00');
    });

    it('shows an em dash for ค้างรับ and ยอดคาดการณ์ when the summary has no totals, never a sum of rows', () => {
      renderOverview({ cashReceivedThisMonth: CASH_RECEIVED_THIS_MONTH });

      expectFigure('ค้างรับ', '—');
      expectFigure('ยอดคาดการณ์', '—');
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
    it('lists SUBMITTED records only, at most five, with the total base of all of them', async () => {
      renderOverview();
      const panel = (await screen.findByTestId('commission-row-301')).closest('section');

      expect(panel.querySelectorAll('[data-testid^="commission-row-"]')).toHaveLength(5);
      expect(within(panel).queryByTestId('commission-row-399')).toBeNull();
      expect(within(panel).getByTestId('commission-row-301').textContent).toContain('INV-301');
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
      api.commissions.list.mockResolvedValue({ commissions: [managerApproved] });
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
