import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TicketDashboard } from './TicketDashboard.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      dashboard: {
        summary: vi.fn(),
      },
      tickets: {
        list: vi.fn(),
      },
      // Commit 6: the "รอรับเรื่อง (Import)" queue count now reads from the
      // PricingRequest queue instead of the (now permanently-0) tickets summary.
      pricingRequests: {
        queue: vi.fn(),
      },
    },
  };
});

const importUser = {
  employeeId: 9,
  name: 'Import ทดสอบ',
  role: 'import',
};

const employee = { nameTh: 'พนักงาน นำเข้า' };

function renderDashboard() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/dashboard']}>
        <TicketDashboard user={importUser} employee={employee} showToast={vi.fn()} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('TicketDashboard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.dashboard.summary.mockResolvedValue({
      summary: {
        tickets: {
          totalOpen: 12,
          submitted: 3,
          inReview: 2, // legacy field — no longer read; see Commit D on กำลังดำเนินการ
          priceProposed: 1,
          approved: 4,
          quotationIssued: 2,
          closedThisMonth: 5,
          cancelledThisMonth: 0,
          onHold: 2,
          dormant: 1,
          paymentOverdue: 3,
          partiallyDelivered: 4,
        },
        notifications: { unread: 2 },
      },
    });
    api.pricingRequests.queue.mockResolvedValue({ items: [] });
    api.tickets.list.mockResolvedValue({
      tickets: [
        {
          id: 601,
          code: 'PR-2026-0601',
          title: 'โครงการล่าสุด',
          customerName: 'บริษัท ล่าสุด จำกัด',
          status: 'submitted',
          createdByName: 'วิชัย ขยัน',
          createdAt: '2026-07-10T08:00:00.000Z',
        },
      ],
    });
  });

  it('renders counts from a mocked dashboard summary', async () => {
    renderDashboard();

    expect(await screen.findByText('12')).not.toBeNull();
    expect(screen.getByText('เปิดอยู่ทั้งหมด')).not.toBeNull();
    expect(screen.getByText('พักไว้ชั่วคราว')).not.toBeNull();
    expect(screen.getByText('เกินกำหนดชำระ')).not.toBeNull();
    expect(screen.getByText('ส่งมอบบางส่วน')).not.toBeNull();
    expect(api.dashboard.summary).toHaveBeenCalledTimes(1);
  });

  it('renders the recent-tickets strip from the shared tickets.list query', async () => {
    renderDashboard();

    expect(await screen.findByText('บริษัท ล่าสุด จำกัด')).not.toBeNull();
    expect(api.tickets.list).toHaveBeenCalledWith({});
  });

  // Review-remediation plan Commit D: "กำลังดำเนินการ" used to read
  // summary.inReview, a legacy ticket-status count new deals never reach any
  // more (03b5ba9 retired ticket-level submit/in_review). It now counts
  // pricing requests Import has picked up or sent back for more info
  // (IMPORT_REVIEWING / MORE_INFO_REQUIRED), reusing the same unfiltered
  // pricing-request queue query that already backs "รอรับเรื่อง" (SUBMITTED)
  // rather than adding a third query.
  it('splits the pricing-request queue into รอรับเรื่อง (SUBMITTED) and กำลังดำเนินการ (IMPORT_REVIEWING/MORE_INFO_REQUIRED) counts', async () => {
    api.pricingRequests.queue.mockResolvedValue({
      items: [
        { id: 1, status: 'SUBMITTED' },
        { id: 2, status: 'SUBMITTED' },
        { id: 3, status: 'IMPORT_REVIEWING' },
        { id: 4, status: 'MORE_INFO_REQUIRED' },
        { id: 5, status: 'DRAFT' },
      ],
    });

    renderDashboard();
    await screen.findByText('เปิดอยู่ทั้งหมด');

    function statCardValue(label) {
      const card = screen.getByText(label).closest('.stat-card');
      return card.querySelector('.stat-value').textContent;
    }

    expect(statCardValue('รอรับเรื่อง')).toBe('2');
    expect(statCardValue('กำลังดำเนินการ')).toBe('2');
    // Deliberately unfiltered (no `status`) — one query backs both tiles.
    expect(api.pricingRequests.queue).toHaveBeenCalledWith({ activeOnly: true });
  });
});
