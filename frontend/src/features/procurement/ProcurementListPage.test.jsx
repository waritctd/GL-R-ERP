import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ProcurementListPage } from './ProcurementListPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// UI-level only — proves this component's own rendering/navigation against a hand-rolled mock,
// NOT server-side authorization. The authoritative Import/CEO-only role checks are
// ProcurementService.RAW_PO_ROLES, covered by the real-DB integration test
// (ProcurementServiceIntegrationTest, backend).
vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      procurement: {
        list: vi.fn(),
      },
    },
  };
});

function LocationDisplay() {
  const location = useLocation();
  return <div data-testid="location-display">{location.pathname}{location.search}</div>;
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/factory-purchase-orders']}>
        <ProcurementListPage showToast={vi.fn()} />
        <LocationDisplay />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const sampleOrders = [
  {
    id: 1, poNumber: 'FPO-2026-0001', pricingRequestId: 10, pricingRequestCode: 'PCR-2026-0010',
    ticketId: 20, ticketCode: 'TKT-0020', factoryName: 'Factory A', status: 'OPEN',
    currency: 'THB', totalAmount: 1000, updatedAt: '2026-07-21T00:00:00Z',
  },
  {
    id: 2, poNumber: 'FPO-2026-0002', pricingRequestId: 10, pricingRequestCode: 'PCR-2026-0010',
    ticketId: 20, ticketCode: 'TKT-0020', factoryName: 'Factory B', status: 'RECEIVED',
    currency: 'THB', totalAmount: 2000, updatedAt: '2026-07-20T00:00:00Z',
  },
];

describe('ProcurementListPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.procurement.list.mockResolvedValue({ factoryPurchaseOrders: sampleOrders });
  });

  it('renders every factory purchase order returned by the list endpoint', async () => {
    renderPage();
    await screen.findByText('Factory A');
    expect(screen.getByText('Factory B')).not.toBeNull();
    expect(screen.getByText('FPO-2026-0001')).not.toBeNull();
    expect(screen.getByText('FPO-2026-0002')).not.toBeNull();
  });

  it('navigates to the PO detail page when a row is clicked', async () => {
    renderPage();
    const row = await screen.findByText('Factory A');
    fireEvent.click(row.closest('[role="row"]'));
    await waitFor(() => expect(screen.getByTestId('location-display').textContent)
      .toBe('/factory-purchase-orders/1'));
  });

  it('re-queries with the selected status when a status filter chip is clicked', async () => {
    renderPage();
    await screen.findByText('Factory A');
    fireEvent.click(screen.getByRole('button', { name: 'รับสินค้าแล้ว' }));
    await waitFor(() => expect(api.procurement.list).toHaveBeenCalledWith('RECEIVED'));
  });
});
