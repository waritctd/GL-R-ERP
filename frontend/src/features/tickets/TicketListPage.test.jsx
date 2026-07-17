import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TicketListPage } from './TicketListPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: {
        list: vi.fn(),
        create: vi.fn(),
      },
    },
  };
});

// TicketCreateModal owns its own multi-field customer/catalog search form
// (out of scope for this slice — see task note not to touch it). Stubbed here
// with a single button so this test can drive TicketListPage's create
// mutation (invalidate + refetch) without re-implementing that form.
vi.mock('./TicketCreateModal.jsx', () => ({
  TicketCreateModal: ({ onSubmit }) => (
    <button type="button" onClick={() => onSubmit({ title: 'โครงการใหม่' })}>
      ยืนยันสร้าง (stub)
    </button>
  ),
}));

const salesUser = {
  employeeId: 1,
  name: 'Sales ทดสอบ',
  role: 'sales',
};

function renderTicketListPage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/tickets']}>
        <TicketListPage user={salesUser} showToast={vi.fn()} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('TicketListPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.tickets.list.mockResolvedValue({
      tickets: [
        {
          id: 501,
          code: 'PR-2026-0501',
          title: 'โครงการทดสอบ',
          customerName: 'บริษัท ทดสอบ จำกัด',
          status: 'submitted',
          createdByName: 'สมชาย ใจดี',
          createdAt: '2026-07-01T09:00:00.000Z',
          salesStage: 'QUOTE_DESIGN_SIDE',
          lifecycle: 'ACTIVE',
          lostReason: null,
          overdue: false,
          fulfillmentStatus: null,
          stageUpdatedAt: '2026-07-01T09:00:00.000Z',
        },
        {
          id: 503,
          code: 'PR-2026-0503',
          title: 'โครงการเกินกำหนด',
          customerName: 'บริษัท เกินกำหนด จำกัด',
          status: 'quotation_issued',
          createdByName: 'สมชาย ใจดี',
          createdAt: '2026-07-02T09:00:00.000Z',
          salesStage: 'DELIVERY_SCHEDULING',
          lifecycle: 'ACTIVE',
          lostReason: null,
          overdue: true,
          fulfillmentStatus: 'PARTIALLY_DELIVERED',
          stageUpdatedAt: '2026-07-02T09:00:00.000Z',
        },
        {
          id: 504,
          code: 'PR-2026-0504',
          title: 'โครงการพักไว้',
          customerName: 'บริษัท พักไว้ จำกัด',
          status: 'in_review',
          createdByName: 'สมชาย ใจดี',
          createdAt: '2026-07-03T09:00:00.000Z',
          salesStage: 'NEGOTIATION',
          lifecycle: 'ON_HOLD',
          lostReason: null,
          overdue: false,
          fulfillmentStatus: null,
          stageUpdatedAt: '2026-07-03T09:00:00.000Z',
        },
        {
          id: 505,
          code: 'PR-2026-0505',
          title: 'โครงการเงียบ',
          customerName: 'บริษัท เงียบ จำกัด',
          status: 'in_review',
          createdByName: 'สมชาย ใจดี',
          createdAt: '2026-07-04T09:00:00.000Z',
          salesStage: 'NEGOTIATION',
          lifecycle: 'DORMANT',
          lostReason: null,
          overdue: false,
          fulfillmentStatus: null,
          stageUpdatedAt: '2026-07-04T09:00:00.000Z',
        },
      ],
    });
    api.tickets.create.mockResolvedValue({ ticket: { id: 502, code: 'PR-2026-0502' } });
  });

  it('renders rows from a mocked api.tickets.list', async () => {
    renderTicketListPage();

    expect(await screen.findByText('บริษัท ทดสอบ จำกัด')).not.toBeNull();
    expect(screen.getByText('PR-2026-0501')).not.toBeNull();
    expect(api.tickets.list).toHaveBeenCalledWith({});
  });

  it('invalidates and refetches the ticket list after creating a ticket', async () => {
    const showToast = vi.fn();
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/tickets']}>
          <TicketListPage user={salesUser} showToast={showToast} />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    await screen.findByText('บริษัท ทดสอบ จำกัด');
    expect(api.tickets.list).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole('button', { name: /สร้างดีลใหม่/ }));
    fireEvent.click(await screen.findByRole('button', { name: 'ยืนยันสร้าง (stub)' }));

    await waitFor(() => expect(api.tickets.create).toHaveBeenCalledWith({ title: 'โครงการใหม่' }));
    await waitFor(() => expect(api.tickets.list).toHaveBeenCalledTimes(2));
    expect(showToast).toHaveBeenCalledWith('success', 'สร้างดีลเรียบร้อย');
  });

  it('filters by lifecycle, overdue, and partial delivery chips', async () => {
    renderTicketListPage();

    expect(await screen.findByText('บริษัท ทดสอบ จำกัด')).not.toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /พักไว้ชั่วคราว/ }));
    expect(screen.getByText('บริษัท พักไว้ จำกัด')).not.toBeNull();
    expect(screen.queryByText('บริษัท ทดสอบ จำกัด')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /พักไว้ชั่วคราว/ }));
    fireEvent.click(screen.getByRole('button', { name: /เกินกำหนด/ }));
    expect(screen.getByText('บริษัท เกินกำหนด จำกัด')).not.toBeNull();
    expect(screen.queryByText('บริษัท พักไว้ จำกัด')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /เกินกำหนด/ }));
    fireEvent.click(screen.getByRole('button', { name: /ส่งมอบบางส่วน/ }));
    expect(screen.getByText('บริษัท เกินกำหนด จำกัด')).not.toBeNull();
    expect(screen.queryByText('บริษัท เงียบ จำกัด')).toBeNull();
  });
});
