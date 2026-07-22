import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ProcurementFulfilmentPage } from './ProcurementFulfilmentPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: { list: vi.fn() },
      procurement: { list: vi.fn() },
    },
  };
});

const user = { role: 'import', employeeId: 2, name: 'นำเข้า ทดสอบ' };

const TICKETS = [
  { id: 1, code: 'D-001', title: 'ดีล 1', customerName: 'บริษัท A', status: 'quotation_issued', fulfillmentStatus: null, overdue: false, updatedAt: '2026-07-01T00:00:00Z' },
  { id: 2, code: 'D-002', title: 'ดีล 2', customerName: 'บริษัท B', status: 'quotation_issued', fulfillmentStatus: 'GOODS_RECEIVED', overdue: true, updatedAt: '2026-07-02T00:00:00Z' },
  // No open fulfilment action (still pre-quotation) — must NOT appear in section 1.
  { id: 3, code: 'D-003', title: 'ดีล 3', customerName: 'บริษัท C', status: 'draft', fulfillmentStatus: null, overdue: false, updatedAt: '2026-07-03T00:00:00Z' },
];

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/procurement']}>
        <ProcurementFulfilmentPage user={user} showToast={vi.fn()} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ProcurementFulfilmentPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.tickets.list.mockResolvedValue({ tickets: TICKETS });
    api.procurement.list.mockResolvedValue({ factoryPurchaseOrders: [] });
  });

  it('renders section 1 (fulfilment worklist), overdue first, only for deals with an open fulfilment step', async () => {
    renderPage();
    await screen.findAllByText(/^บริษัท /);

    const section = screen.getByText('งานรับเข้าคลัง / ส่งมอบ').closest('section');
    const rows = within(section).getAllByText(/^บริษัท /).map((el) => el.textContent);
    expect(rows).toEqual(['บริษัท B', 'บริษัท A']); // B is overdue -> leads; C has no open action -> absent

    // D-002 already has fulfillmentStatus GOODS_RECEIVED, so its next action
    // is recordDelivery, not markGoodsReceived.
    expect(within(section).getByRole('button', { name: 'บันทึกส่งมอบ' })).toBeTruthy();
    expect(within(section).getByRole('button', { name: 'ออก IR' })).toBeTruthy();
  });

  it('reuses ProcurementListPage wholesale as section 2', async () => {
    renderPage();
    await screen.findByText('งานรับเข้าคลัง / ส่งมอบ');
    expect(await screen.findByText('ใบสั่งซื้อโรงงาน')).toBeTruthy();
    expect(api.procurement.list).toHaveBeenCalled();
  });

  it('shows an empty state when nothing is open in the fulfilment worklist', async () => {
    api.tickets.list.mockResolvedValue({ tickets: [TICKETS[2]] });
    renderPage();
    await screen.findByText('ไม่มีงานส่งมอบที่ต้องดำเนินการตอนนี้');
  });
});
