import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ManagerOverview } from './ManagerOverview.jsx';
import { api } from '../../api/index.js';

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

const now = new Date();
const thisMonthIso = new Date(now.getFullYear(), now.getMonth(), 5).toISOString();

// A won deal, closed this month — feeds the "ยอดทีมเดือนนี้" pulse and the
// leaderboard (rep A).
const wonTicket = {
  id: 201, code: 'PR-2026-0201', title: 'โครงการวัน', customerName: 'บริษัท วัน จำกัด',
  createdById: 11, createdByName: 'เซลส์ เอ',
  status: 'closed', lifecycle: 'ACTIVE', salesStage: 'CLOSED_PAID',
  stageUpdatedAt: thisMonthIso, updatedAt: thisMonthIso,
  amountPayable: 300000, overdue: false, stale: false, nextFollowUpAt: null,
};

// An open deal, stale (no activity 7 days) — should surface in "ดีลทีมที่ต้องดูแล".
const staleTicket = {
  id: 202, code: 'PR-2026-0202', title: 'โครงการทู', customerName: 'บริษัท ทู จำกัด',
  createdById: 12, createdByName: 'เซลส์ บี',
  status: 'quotation_issued', lifecycle: 'ACTIVE', salesStage: 'NEGOTIATION',
  stageUpdatedAt: '2026-06-01T00:00:00.000Z', updatedAt: '2026-06-01T00:00:00.000Z',
  amountPayable: 80000, overdue: false, stale: true, nextFollowUpAt: null,
};

// An open deal with nothing pending — must NOT appear in the attention list.
const healthyTicket = {
  id: 203, code: 'PR-2026-0203', title: 'โครงการทรี', customerName: 'บริษัท ทรี จำกัด',
  createdById: 11, createdByName: 'เซลส์ เอ',
  status: 'quotation_issued', lifecycle: 'ACTIVE', salesStage: 'ORDER_RECEIVED',
  stageUpdatedAt: thisMonthIso, updatedAt: thisMonthIso,
  amountPayable: 50000, overdue: false, stale: false, nextFollowUpAt: null,
};

const pendingCommission = {
  id: 301, sourceTicketId: 201, salesRepId: 11, salesRepName: 'เซลส์ เอ',
  kind: 'SALE', status: 'SUBMITTED', commissionableBase: 9000, actualReceived: 9500,
  invoiceDetails: { invoiceNumber: 'INV-2026-0301' },
};

const managerApprovedCommission = {
  id: 302, sourceTicketId: 201, salesRepId: 11, salesRepName: 'เซลส์ เอ',
  kind: 'SALE', status: 'MANAGER_APPROVED', commissionableBase: 5000, actualReceived: 5200,
  invoiceDetails: { invoiceNumber: 'INV-2026-0302' },
};

function renderOverview() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/']}>
        <ManagerOverview user={managerUser} employee={employee} showToast={vi.fn()} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ManagerOverview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.tickets.list.mockResolvedValue({ tickets: [wonTicket, staleTicket, healthyTicket] });
    api.commissions.list.mockResolvedValue({ commissions: [pendingCommission, managerApprovedCommission] });
  });

  it('renders the team pulse derived from the team ticket list', async () => {
    renderOverview();
    await screen.findByText('ยอดทีมเดือนนี้');
    // ฿300,000 also appears in the leaderboard row for the same rep/deal —
    // both are correct, so assert at least one, not exactly one.
    expect(screen.getAllByText('฿300,000').length).toBeGreaterThan(0);
    expect(screen.getByText('pipeline ทีม')).not.toBeNull();
    expect(screen.getByText('ดีลต้องดูแล')).not.toBeNull();
  });

  it('renders the ค่าคอมรออนุมัติ worklist with a review/approve CTA, SUBMITTED only', async () => {
    renderOverview();
    // "ค่าคอมรออนุมัติ" also labels the pulse stat card, so target the panel
    // heading specifically, not any text match.
    const heading = await screen.findByRole('heading', { name: 'ค่าคอมรออนุมัติ' });
    const panel = heading.closest('section');
    expect(within(panel).getByText('เซลส์ เอ')).not.toBeNull();
    expect(within(panel).getByText(/INV-2026-0301/)).not.toBeNull();
    expect(within(panel).getByText('ตรวจ · อนุมัติ')).not.toBeNull();
    // MANAGER_APPROVED is not the manager's own action anymore (that's CEO's) —
    // must not appear in this worklist.
    expect(within(panel).queryByText(/INV-2026-0302/)).toBeNull();
  });

  it('renders the leaderboard and close-rate in the right rail', async () => {
    renderOverview();
    const heading = await screen.findByRole('heading', { name: 'อันดับทีม (เดือนนี้)' });
    const panel = heading.closest('section');
    expect(within(panel).getByText(/เซลส์ เอ/)).not.toBeNull();
    expect(screen.getByText('อัตราปิดงาน')).not.toBeNull();
  });

  it('renders the deals-needing-attention list, stale deals only, healthy deals excluded', async () => {
    renderOverview();
    const panel = (await screen.findByText('ดีลทีมที่ต้องดูแล')).closest('section');
    expect(within(panel).getByText('บริษัท ทู จำกัด')).not.toBeNull();
    expect(within(panel).getByText('ไม่มีความเคลื่อนไหว 7 วัน')).not.toBeNull();
    expect(within(panel).queryByText('บริษัท ทรี จำกัด')).toBeNull();
  });
});
