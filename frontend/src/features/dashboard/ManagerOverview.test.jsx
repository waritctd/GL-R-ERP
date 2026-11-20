import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ManagerOverview } from './ManagerOverview.jsx';
import { api } from '../../api/index.js';
import { CASH_BY_REP, CASH_RECEIVED_THIS_MONTH, SERVER_TOTALS } from '../../test/fixtures/dashboardMoney.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: { list: vi.fn() },
      commissions: { list: vi.fn(), pendingApproval: vi.fn() },
      salesBoard: { teamOverview: vi.fn() },
      salesAnalytics: { snapshot: vi.fn() },
    },
  };
});

const managerUser = { role: 'sales_manager', name: 'ผู้จัดการ ทดสอบ', employeeId: 9 };
const employee = { nickName: 'บอส', nameTh: 'ผู้จัดการ ทดสอบ' };

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

describe('ManagerOverview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.tickets.list.mockResolvedValue({ tickets: TICKETS });
    api.commissions.pendingApproval.mockResolvedValue(pendingOf(SUBMITTED));
    api.salesBoard.teamOverview.mockResolvedValue(TEAM);
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
  });

  it('shows อัตราปิดงาน as one line from won vs lost deals', async () => {
    renderOverview();
    const heading = await screen.findByRole('heading', { name: 'อัตราปิดงาน' });
    const panel = heading.closest('section');
    await waitFor(() => expect(panel.textContent).toContain('50%'));
    expect(panel.textContent).toContain('ปิดสำเร็จ 1 · เสีย 1');
  });
});
