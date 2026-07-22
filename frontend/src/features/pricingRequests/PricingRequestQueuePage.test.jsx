import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PricingRequestQueuePage } from './PricingRequestQueuePage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      pricingRequests: {
        queue: vi.fn(),
        pickup: vi.fn(),
      },
    },
  };
});

const importUser = { id: 5, name: 'ฝ่ายนำเข้า', role: 'import' };

function row(overrides = {}) {
  return {
    id: 1,
    requestCode: 'PCR-2026-0001',
    ticketId: 701,
    ticketCode: 'PR-2026-0701',
    customerName: 'บริษัท ทดสอบ จำกัด',
    projectName: 'โครงการทดสอบ',
    recipientType: 'DESIGNER',
    recipientLabel: null,
    status: 'SUBMITTED',
    itemCount: 3,
    requiredDate: null,
    assignedImportId: null,
    assignedImportName: null,
    ...overrides,
  };
}

function renderQueuePage(user = importUser) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/pricing-requests']}>
        <PricingRequestQueuePage user={user} showToast={vi.fn()} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('PricingRequestQueuePage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.pricingRequests.queue.mockResolvedValue({ items: [row()] });
  });

  it('renders rows from a mocked api.pricingRequests.queue, defaulting to the SUBMITTED filter', async () => {
    renderQueuePage();

    expect(await screen.findByText('PCR-2026-0001')).not.toBeNull();
    expect(screen.getByText('บริษัท ทดสอบ จำกัด')).not.toBeNull();
    await waitFor(() => expect(api.pricingRequests.queue).toHaveBeenCalledWith({ status: 'SUBMITTED', activeOnly: true }));
  });

  it('refetches with the new status when a filter pill is clicked', async () => {
    renderQueuePage();
    await screen.findByText('PCR-2026-0001');

    fireEvent.click(screen.getByRole('button', { name: 'ทั้งหมด' }));

    await waitFor(() => expect(api.pricingRequests.queue).toHaveBeenCalledWith({ status: undefined, activeOnly: true }));
  });

  it('lets an import user pick up a submitted request', async () => {
    api.pricingRequests.pickup.mockResolvedValue({ pricingRequest: { summary: row({ status: 'IMPORT_REVIEWING' }), items: [], events: [] } });
    renderQueuePage();

    const pickupButton = await screen.findByRole('button', { name: 'รับเรื่อง' });
    fireEvent.click(pickupButton);

    await waitFor(() => expect(api.pricingRequests.pickup).toHaveBeenCalledWith(1));
  });

  it('does not offer a pickup action to a sales viewer', async () => {
    renderQueuePage({ id: 1, name: 'พนักงานขาย', role: 'sales' });

    await screen.findByText('PCR-2026-0001');
    expect(screen.queryByRole('button', { name: 'รับเรื่อง' })).toBeNull();
  });
});
