import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { WeeklyReport } from './WeeklyReport.jsx';
import { api } from '../../api/index.js';
import { bangkokTodayIso } from '../../utils/format.js';
import { weekStartIso } from '../salesBoard/visitWeek.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      meta: { dealStages: vi.fn() },
      salesBoard: { weeklyReport: vi.fn(), myWork: vi.fn(), teamBoard: vi.fn() },
      salesVisits: { list: vi.fn(), team: vi.fn(), done: vi.fn() },
    },
  };
});

const monday = weekStartIso(bangkokTodayIso());
const month = monday.slice(0, 7);
const week = (over = {}) => ({
  weekStart: monday, received: 0, deliveredAwaiting: 0, deliveriesScheduled: 0, depositsExpected: 0, slipped: 0, isCurrent: true, isPast: false, ...over,
});
const order = (over = {}) => ({
  ticketId: 1, code: 'PR-0001', title: 'โครงการตัวอย่าง', customerName: 'ลูกค้าตัวอย่าง', projectName: 'โครงการตัวอย่าง', ownerId: 6, ownerName: 'เซลส์ เอ',
  salesStage: 'ORDER_RECEIVED', orderConfirmedAt: `${monday}T03:00:00Z`, value: 100000, received: 0, outstanding: 100000,
  nextPayment: { id: 1, kind: 'DEPOSIT', amount: 30000, week: monday }, slipped: false, ...over,
});
const rep = (ownerId, name) => ({
  ownerId, name, newDeals: 0, stageMoves: 0, quotationsIssued: 0, ordersConfirmed: 0, lost: 0, visitsDone: 0, visitsPlanned: 0, visitsDue: 0,
  visitsDueWithResult: 0, ordersOpen: 1, ordersWithDate: 1, entriesToday: 1, lastEntryAt: `${monday}T03:00:00Z`,
});
const report = (over = {}) => ({
  weekStart: monday, weekEnd: monday, moneyFrom: null, scope: 'OWN',
  ledger: {
    month, weeks: [week({ received: 50000, deliveredAwaiting: 10000, deliveriesScheduled: 20000, depositsExpected: 30000, slipped: 30000 })],
    received: 50000, deliveredAwaiting: 10000, deliveriesScheduled: 20000, depositsExpected: 30000, total: 110000,
  },
  orders: [order({ slipped: true, nextPayment: { id: 1, kind: 'DEPOSIT', amount: 30000, week: '2020-01-06' } })],
  reps: [rep(6, 'เซลส์ เอ')], today: [], ...over,
});

function renderReport(user) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter><WeeklyReport user={user} /></MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('WeeklyReport', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    api.meta.dealStages.mockResolvedValue({ stages: [{ code: 'ORDER_RECEIVED', no: 10 }], phases: [], lostReasons: [] });
    api.salesBoard.myWork.mockResolvedValue({ board: [] });
    api.salesBoard.teamBoard.mockResolvedValue({ board: [] });
    api.salesVisits.list.mockResolvedValue([]);
    api.salesVisits.team.mockResolvedValue([]);
  });

  it('shows a rep the five money lines and tags a slipped payment; a ฿0 order is not "paid in full"', async () => {
    const zeroAddOn = order({ ticketId: 2, code: 'PR-0001-2', value: 0, outstanding: 0, nextPayment: null });
    api.salesBoard.weeklyReport.mockResolvedValue(report({ orders: [...report().orders, zeroAddOn] }));
    renderReport({ role: 'sales', id: 6 });
    expect(await screen.findByText('2 · รับชำระแล้ว', { selector: 'th' })).toBeTruthy();
    for (const line of ['3 · ส่งมอบแล้ว รอรับชำระ', '4 · นัดส่งมอบแล้ว รอรับชำระ', '5 · เงินมัดจำที่คาดว่าจะได้รับ']) {
      expect(screen.getByText(line, { selector: 'th' })).toBeTruthy();
    }
    expect(screen.getByText('1 · ประมาณการยอดรับเงินรวม')).toBeTruthy();
    expect(screen.getAllByText('เลื่อนกำหนด').length).toBeGreaterThan(0);
    expect(api.salesBoard.weeklyReport).toHaveBeenCalledWith({ week: monday, ownerId: undefined });
    expect(screen.getAllByText(/^ยังไม่มียอด/).length).toBeGreaterThan(0);
    expect(screen.queryByText('รับชำระครบ')).toBeNull();
  });

  it('lists each rep of the team as a link to that rep’s report', async () => {
    api.salesBoard.weeklyReport.mockResolvedValue(report({ scope: 'TEAM', reps: [rep(6, 'เซลส์ เอ'), rep(7, 'เซลส์ บี')], orders: [order()] }));
    renderReport({ role: 'sales_manager', id: 9 });
    const link = await screen.findByRole('link', { name: 'เซลส์ บี' });
    expect(link.getAttribute('href')).toContain('rep=7');
    expect(screen.getByText('รายบุคคล')).toBeTruthy();
  });

  it('says so when loading fails', async () => {
    api.salesBoard.weeklyReport.mockRejectedValue(new Error('x'));
    renderReport({ role: 'sales', id: 6 });
    expect(await screen.findByText('โหลดไม่สำเร็จ')).toBeTruthy();
  });
});
