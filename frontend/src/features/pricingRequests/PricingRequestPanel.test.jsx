import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
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
        update: vi.fn(),
        submit: vi.fn(),
        cancel: vi.fn(),
        pickup: vi.fn(),
        // PricingRequestCreateModal (V69, review remediation COMMIT 4) fetches attachments whenever it has a persisted
        // id — including edit mode, which this file's "seed the modal from request detail" test exercises.
        listAttachments: vi.fn().mockResolvedValue({ items: [] }),
      },
      // GLA-125: the header-terms section fetches the same eligible-display-name list the direct-deal quotation
      // editor uses (not under test here).
      dealQuotations: {
        displayNameOptions: vi.fn().mockResolvedValue({ items: [] }),
      },
    },
  };
});

const salesOwner = { id: 1, name: 'พนักงานขาย', role: 'sales' };
const importUser = { id: 5, name: 'ฝ่ายนำเข้า', role: 'import' };
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

  // #1 (owner ask 2026-09-24): a SUBMITTED request waiting for import had no รับเรื่อง affordance on the deal
  // itself — only in the คิวขอราคา queue. Same gate/endpoint the queue uses.
  it('lets an import user รับเรื่อง a SUBMITTED request straight from the deal', async () => {
    api.pricingRequests.listForTicket.mockResolvedValue({ items: [summary({ status: 'SUBMITTED' })] });
    api.pricingRequests.pickup.mockResolvedValue({
      pricingRequest: { summary: summary({ status: 'PICKED_UP' }), items: [], events: [] },
    });
    renderPanel({ user: importUser });

    fireEvent.click(await screen.findByTestId('pcr-pickup-1'));
    await waitFor(() => expect(api.pricingRequests.pickup).toHaveBeenCalledWith(1));
  });

  it('shows no รับเรื่อง button to the sales owner (pickup is import-only)', async () => {
    api.pricingRequests.listForTicket.mockResolvedValue({ items: [summary({ status: 'SUBMITTED' })] });
    renderPanel({ user: salesOwner });
    await screen.findByText('PCR-2026-0001');
    expect(screen.queryByTestId('pcr-pickup-1')).toBeNull();
  });

  describe('imperative handle (sticky header CTA trigger)', () => {
    it('openCreate() is a no-op for a non-owner', async () => {
      api.pricingRequests.listForTicket.mockResolvedValue({ items: [] });
      const ref = React.createRef();
      render(
        <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
          <PricingRequestPanel
            ref={ref}
            ticketId={701}
            deal={deal}
            ticketItems={[]}
            user={{ id: 2, name: 'อื่น', role: 'sales' }}
          />
        </QueryClientProvider>,
      );
      await screen.findByText('ยังไม่มีคำขอราคา');

      act(() => ref.current.openCreate());

      expect(screen.queryByRole('dialog')).toBeNull();
    });

    it('openCreate() opens the create modal for the owning sales rep', async () => {
      api.pricingRequests.listForTicket.mockResolvedValue({ items: [] });
      const ref = React.createRef();
      render(
        <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
          <PricingRequestPanel ref={ref} ticketId={701} deal={deal} ticketItems={[]} user={salesOwner} />
        </QueryClientProvider>,
      );
      await screen.findByText('ยังไม่มีคำขอราคา');

      act(() => ref.current.openCreate());

      expect(await screen.findByRole('dialog')).not.toBeNull();
    });
  });

  it('lets the owner submit an existing DRAFT request to Import', async () => {
    api.pricingRequests.listForTicket.mockResolvedValue({ items: [summary({ status: 'DRAFT' })] });
    api.pricingRequests.submit.mockResolvedValue({
      pricingRequest: { summary: summary({ status: 'SUBMITTED' }), items: [], events: [] },
    });
    renderPanel();

    const submitButton = await screen.findByRole('button', { name: 'ส่งให้ฝ่ายนำเข้า' });
    fireEvent.click(submitButton);

    await waitFor(() => expect(api.pricingRequests.submit).toHaveBeenCalledWith(1));
  });

  it('offers no "ส่งให้ฝ่ายนำเข้า" on a DRAFT once the deal order is confirmed', async () => {
    api.pricingRequests.listForTicket.mockResolvedValue({
      items: [summary({ status: 'DRAFT', ticketPaymentStatus: 'CUSTOMER_CONFIRMED' })],
    });
    renderPanel();

    await screen.findByText('PCR-2026-0001');
    expect(screen.queryByRole('button', { name: 'ส่งให้ฝ่ายนำเข้า' })).toBeNull();
  });
});
