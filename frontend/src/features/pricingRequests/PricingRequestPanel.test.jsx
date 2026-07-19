import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PricingRequestPanel } from './PricingRequestPanel.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      pricingRequests: {
        listForTicket: vi.fn(),
        get: vi.fn(),
        create: vi.fn(),
        submit: vi.fn(),
        cancel: vi.fn(),
        respondInformation: vi.fn(),
      },
    },
  };
});

const salesOwner = { id: 1, name: 'พนักงานขาย', role: 'sales' };
const deal = { createdById: 1, lifecycle: 'ACTIVE' };

function renderPanel(overrides = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <PricingRequestPanel
        ticketId={701}
        deal={deal}
        ticketItems={[]}
        user={salesOwner}
        {...overrides}
      />
    </QueryClientProvider>,
  );
}

function summary(overrides = {}) {
  return {
    id: 1,
    requestCode: 'PCR-2026-0001',
    ticketId: 701,
    ticketCreatedById: 1,
    recipientType: 'DESIGNER',
    recipientLabel: null,
    status: 'DRAFT',
    requestedById: 1,
    requestedByName: 'พนักงานขาย',
    assignedImportId: null,
    assignedImportName: null,
    requiredDate: null,
    itemCount: 1,
    ...overrides,
  };
}

describe('PricingRequestPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows an empty state and a create button for the deal owner when there are no requests', async () => {
    api.pricingRequests.listForTicket.mockResolvedValue({ items: [] });
    renderPanel();

    expect(await screen.findByText('ยังไม่มีใบขอราคา')).not.toBeNull();
    expect(screen.getByRole('button', { name: /สร้างใบขอราคา/ })).not.toBeNull();
  });

  it('does not show the create button for a non-owner', async () => {
    api.pricingRequests.listForTicket.mockResolvedValue({ items: [] });
    renderPanel({ user: { id: 2, name: 'อื่น', role: 'sales' } });

    await screen.findByText('ยังไม่มีใบขอราคา');
    expect(screen.queryByRole('button', { name: /สร้างใบขอราคา/ })).toBeNull();
  });

  it('renders a request row with its status badge and an expand toggle that loads items/events', async () => {
    api.pricingRequests.listForTicket.mockResolvedValue({ items: [summary()] });
    api.pricingRequests.get.mockResolvedValue({
      pricingRequest: {
        summary: summary(),
        items: [{ id: 11, brand: 'SCG', model: 'A1', color: 'ขาว', texture: 'ด้าน', size: '60x60', requestedQty: 10, requestedUnit: 'แผ่น', quantityType: 'ESTIMATE' }],
        events: [{ id: 21, eventKind: 'PRICING_REQUEST_CREATED', actorName: 'พนักงานขาย', createdAt: '2026-07-01T09:00:00.000Z' }],
      },
    });
    renderPanel();

    expect(await screen.findByText('PCR-2026-0001')).not.toBeNull();
    expect(screen.getByText('แบบร่าง')).not.toBeNull();

    fireEvent.click(screen.getByText('PCR-2026-0001').closest('button'));

    await waitFor(() => expect(api.pricingRequests.get).toHaveBeenCalledWith(1));
    expect(await screen.findByText('SCG A1')).not.toBeNull();
    expect(screen.getByText('สร้างคำขอราคา (ร่าง)')).not.toBeNull();
  });

  it('lets the owner submit an existing DRAFT request to Import', async () => {
    api.pricingRequests.listForTicket.mockResolvedValue({ items: [summary({ status: 'DRAFT' })] });
    api.pricingRequests.submit.mockResolvedValue({
      pricingRequest: { summary: summary({ status: 'SUBMITTED' }), items: [], events: [] },
    });
    renderPanel();

    const submitButton = await screen.findByRole('button', { name: 'ส่งให้ Import' });
    fireEvent.click(submitButton);

    await waitFor(() => expect(api.pricingRequests.submit).toHaveBeenCalledWith(1));
  });

  it('never offers "ส่งให้ Import" once a request is past DRAFT, even for the CEO (cancel is still allowed)', async () => {
    api.pricingRequests.listForTicket.mockResolvedValue({ items: [summary({ status: 'SUBMITTED' })] });
    renderPanel({ user: { id: 9, name: 'CEO', role: 'ceo' } });

    await screen.findByText('PCR-2026-0001');
    // Submit is DRAFT-only and owner-sales-only (PricingRequestService.submit) —
    // the CEO never gets it, regardless of status.
    expect(screen.queryByRole('button', { name: 'ส่งให้ Import' })).toBeNull();
    // Cancel is the one action the CEO gets on ANY cancellable status, as an
    // explicit override (PricingRequestService.cancel).
    expect(screen.getByRole('button', { name: 'ยกเลิก' })).not.toBeNull();
  });
});
