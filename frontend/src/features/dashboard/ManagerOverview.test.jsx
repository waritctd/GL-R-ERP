import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ManagerOverview } from './ManagerOverview.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      commissions: { pendingApproval: vi.fn() },
      dealQuotations: { list: vi.fn() },
      pricingRequests: { queue: vi.fn() },
      salesBoard: { weeklyReport: vi.fn() },
      salesVisits: { team: vi.fn() },
      salesAnalytics: { snapshot: vi.fn() },
    },
  };
});

const managerUser = { role: 'sales_manager', name: 'ผู้จัดการ ทดสอบ', employeeId: 9 };
const employee = { nickName: 'บอส', nameTh: 'ผู้จัดการ ทดสอบ' };

const rep = (ownerId, name, extra = {}) => ({
  ownerId, name, newDeals: 0, stageMoves: 2, quotationsIssued: 1, ordersConfirmed: 0, lost: 0, visitsDone: 1, visitsPlanned: 2,
  visitsDue: 2, visitsDueWithResult: 1, ordersOpen: 3, ordersWithDate: 2, entriesToday: 1, lastEntryAt: '2026-11-25T03:00:00Z', ...extra,
});
const REPORT = {
  scope: 'TEAM',
  ledger: { month: '2026-11', weeks: [{ weekStart: '2026-11-23', received: 270000, deliveredAwaiting: 100000, deliveriesScheduled: 0, depositsExpected: 50000, isCurrent: true }] },
  orders: [
    { ticketId: 70, code: 'PR-1', projectName: 'โครงการ เลื่อน', ownerName: 'เซลส์ เอ', outstanding: 244000, nextPayment: { kind: 'BALANCE', amount: 244000, week: '2026-11-16' }, slipped: true },
    { ticketId: 71, code: 'PR-2', projectName: 'โครงการ ไม่ระบุวัน', ownerName: 'เซลส์ เอ', outstanding: 5000, nextPayment: null, slipped: false },
  ],
  reps: [
    rep(11, 'เซลส์ เอ'),
    rep(12, 'เซลส์ บี', { stageMoves: 0, quotationsIssued: 0, visitsDone: 0, entriesToday: 0, lastEntryAt: '2026-11-12T03:00:00Z' }),
  ],
};
const QUOTATIONS = [
  { id: 501, number: 'QT-2026-0501-1', projectName: 'โครงการ รออนุมัติ', salesRepId: 11, salesRepName: 'เซลส์ เอ', grandTotal: 412000, submittedAt: new Date().toISOString() },
];
const VISITS = [
  { id: 1, ownerName: 'เซลส์ เอ', contactName: 'คุณตัวอย่าง', ticketTitle: 'โครงการ ก', plannedTime: '10:00', status: 'DONE' },
  { id: 2, ownerName: 'เซลส์ บี', contactName: 'คุณทดสอบ', plannedTime: '13:00', status: 'PLANNED' },
];
const PRICING = [
  { id: 1, status: 'READY_FOR_CEO_REVIEW', projectName: 'โครงการ ซี', updatedAt: new Date().toISOString() },
  { id: 2, status: 'IMPORT_REVIEWING', projectName: 'โครงการ ดี', updatedAt: new Date().toISOString() },
  { id: 3, status: 'DRAFT', projectName: 'ไม่แสดง' },
];

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

function renderOverview() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<ManagerOverview user={managerUser} employee={employee} showToast={vi.fn()} />} />
          <Route path="*" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ManagerOverview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.commissions.pendingApproval.mockResolvedValue(pendingOf(SUBMITTED));
    api.dealQuotations.list.mockResolvedValue({ items: QUOTATIONS });
    api.pricingRequests.queue.mockResolvedValue({ items: PRICING });
    api.salesBoard.weeklyReport.mockResolvedValue(REPORT);
    api.salesVisits.team.mockResolvedValue({ visits: VISITS });
  });

  describe('ค่าคอมรอตรวจ', () => {
    it('lists the queue, at most five, with the deal code and the total base of all of them', async () => {
      renderOverview();
      const panel = (await screen.findByTestId('commission-row-301')).closest('section');

      expect(panel.querySelectorAll('[data-testid^="commission-row-"]')).toHaveLength(5);
      expect(within(panel).getByTestId('commission-row-301').textContent).toContain('INV-301 · DL-301');
      expect(within(panel).getByText('6 รายการ').className).toContain('sr-only');
      expect(within(panel).getByTestId('commission-row-301').textContent).toContain('฿1,000.00');
      expect(panel.textContent).toContain('อีก 1 รายการ');
      // 1,000 + … + 6,000: every SUBMITTED record, not just the five shown.
      expect(panel.textContent).toContain('฿21,000.00');
    });

    it('opens the commission approval page from a row', async () => {
      renderOverview();
      const row = await screen.findByTestId('commission-row-301');
      fireEvent.click(within(row).getByRole('button', { name: 'ตรวจค่าคอม' }));
      expect((await screen.findByTestId('location-probe')).textContent).toBe('/commissions?view=pending');
    });
  });

  it('opens a pending quotation for approval and shows the header shortcut', async () => {
    renderOverview();
    const row = await screen.findByTestId('today-row-qt-501');
    expect(row.textContent).toContain('QT-2026-0501-1');
    expect(row.textContent).toContain('ส่งขออนุมัติวันนี้');
    expect(await screen.findByRole('button', { name: 'อนุมัติใบเสนอราคา 1 รายการ' })).toBeTruthy();
    fireEvent.click(within(row).getByRole('button', { name: 'ตรวจและอนุมัติ' }));
    expect((await screen.findByTestId('location-probe')).textContent).toBe('/quotations/501');
  });

  it('flags a slipped payment and a rep with no entries this week, never a quiet-job count', async () => {
    renderOverview();
    expect(await screen.findByTestId('today-row-slip-70')).toBeTruthy();
    const quiet = screen.getByTestId('today-row-quiet-12');
    expect(quiet.textContent).toContain('ยังไม่มีการบันทึกในสัปดาห์นี้');
    expect(screen.queryByTestId('today-row-quiet-11')).toBeNull();
    expect(document.body.textContent).not.toContain('งานเงียบ');
  });

  it('sums the weekly report for the team and groups pricing requests by who they wait on', async () => {
    renderOverview();
    expect(await screen.findByText('สัปดาห์นี้ทีมบันทึกแล้ว')).toBeTruthy();
    // 270,000 received + 150,000 still due this week.
    expect(screen.getByText('฿270,000.00')).toBeTruthy();
    expect(screen.getByText('จากประมาณการสัปดาห์ ฿420,000.00')).toBeTruthy();
    expect(screen.getByText('1 งานยังไม่ระบุวันรับเงิน')).toBeTruthy();
    const waiting = screen.getByRole('heading', { name: 'งานของทีมที่รอฝ่ายอื่น' }).closest('section');
    expect(within(waiting).getByText('CEO · พิจารณาราคา')).toBeTruthy();
    expect(within(waiting).getByText('ฝ่ายนำเข้า · ตอบราคา')).toBeTruthy();
    expect(waiting.textContent).not.toContain('ไม่แสดง');
  });

  it('lists the team visits today and one row per rep that opens the rep report', async () => {
    renderOverview();
    expect(await screen.findByTestId('team-visit-2')).toBeTruthy();
    expect(screen.getByTestId('team-visit-1').textContent).toContain('เข้าพบแล้ว');
    const link = (await screen.findAllByRole('link', { name: 'เซลส์ บี' }))[0];
    expect(link.getAttribute('href')).toContain('?view=report&rep=12');
  });
});
