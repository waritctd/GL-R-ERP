import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PricingRequestQueuePage } from './PricingRequestQueuePage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// Same stub used by TicketListPage.test.jsx to force DataTable's `useIsMobile` down the mobileCard path — per
// repo memory, a mobileCard test is vacuous without this: jsdom has no layout engine, so without a real
// `matchMedia` stub the hook always reports desktop and QueueCard never renders.
const realMatchMedia = window.matchMedia;

afterEach(() => {
  window.matchMedia = realMatchMedia;
});

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
const ceoUser = { id: 9, name: 'ซีอีโอ', role: 'ceo' };
const salesManagerUser = { id: 3, name: 'ผู้จัดการฝ่ายขาย', role: 'sales_manager' };

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
    // PricingRequestDtos.java's actual FIFO fields (~36-39) — default null so byOldestFirst's `?? 0` fallback is
    // exercised unless a test explicitly cares about ordering.
    submittedAt: null,
    createdAt: null,
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

function tabByName(name) {
  return screen.getByRole('tab', { name: new RegExp(`^${name}`) });
}

describe('PricingRequestQueuePage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.pricingRequests.queue.mockResolvedValue({ items: [row()] });
  });

  describe('import role — task tabs (GLA-110)', () => {
    it('lands on งานของฉัน by default and fetches it with assignedImportId', async () => {
      api.pricingRequests.queue.mockResolvedValue({
        items: [row({ status: 'IMPORT_REVIEWING', assignedImportId: 5, assignedImportName: 'ฝ่ายนำเข้า' })],
      });
      renderQueuePage(importUser);

      expect(await screen.findByText('PCR-2026-0001')).not.toBeNull();
      expect(api.pricingRequests.queue).toHaveBeenCalledWith({ assignedImportId: 5, activeOnly: true });
      // No chip bar on the default tab — it is already status-scoped.
      expect(screen.queryByRole('button', { name: 'ทั้งหมด' })).toBeNull();
      expect(tabByName('งานของฉัน').getAttribute('aria-selected')).toBe('true');
    });

    it('shows งานของฉัน rows across IMPORT_REVIEWING / AWAITING_FACTORY_RESPONSE / legacy COSTING_IN_PROGRESS, all assigned to me', async () => {
      api.pricingRequests.queue.mockResolvedValue({
        items: [
          row({ id: 11, requestCode: 'PCR-2026-0011', status: 'IMPORT_REVIEWING', assignedImportId: 5 }),
          row({ id: 12, requestCode: 'PCR-2026-0012', status: 'AWAITING_FACTORY_RESPONSE', assignedImportId: 5 }),
          row({ id: 13, requestCode: 'PCR-2026-0013', status: 'COSTING_IN_PROGRESS', assignedImportId: 5 }),
        ],
      });
      renderQueuePage(importUser);

      expect(await screen.findByText('PCR-2026-0011')).not.toBeNull();
      expect(screen.getByText('PCR-2026-0012')).not.toBeNull();
      expect(screen.getByText('PCR-2026-0013')).not.toBeNull();
    });

    it('excludes a READY_FOR_CEO_REVIEW row assigned to me — out of import\'s hands', async () => {
      api.pricingRequests.queue.mockResolvedValue({
        items: [
          row({ id: 11, requestCode: 'PCR-2026-0011', status: 'IMPORT_REVIEWING', assignedImportId: 5 }),
          row({ id: 14, requestCode: 'PCR-2026-0014', status: 'READY_FOR_CEO_REVIEW', assignedImportId: 5 }),
        ],
      });
      renderQueuePage(importUser);

      expect(await screen.findByText('PCR-2026-0011')).not.toBeNull();
      expect(screen.queryByText('PCR-2026-0014')).toBeNull();
    });

    it('รอรับเรื่อง calls the API with status SUBMITTED and offers the pickup button', async () => {
      api.pricingRequests.queue.mockImplementation((params) => {
        if (params?.status === 'SUBMITTED') {
          return Promise.resolve({ items: [row({ status: 'SUBMITTED' })] });
        }
        return Promise.resolve({ items: [] });
      });
      renderQueuePage(importUser);

      fireEvent.click(tabByName('รอรับเรื่อง'));

      await waitFor(() => expect(api.pricingRequests.queue).toHaveBeenCalledWith({ status: 'SUBMITTED', activeOnly: true }));
      expect(await screen.findByTestId('pcr-queue-pickup')).not.toBeNull();
    });
  });

  describe('ceo role — task tabs (GLA-110)', () => {
    it('lands on รอฉันพิจารณา by default, fetching everything and narrowing client-side', async () => {
      api.pricingRequests.queue.mockResolvedValue({
        items: [
          row({ id: 21, requestCode: 'PCR-2026-0021', status: 'READY_FOR_CEO_REVIEW' }),
          row({ id: 22, requestCode: 'PCR-2026-0022', status: 'CEO_REVIEWING' }),
          row({ id: 23, requestCode: 'PCR-2026-0023', status: 'IMPORT_REVIEWING' }),
        ],
      });
      renderQueuePage(ceoUser);

      expect(await screen.findByText('PCR-2026-0021')).not.toBeNull();
      expect(screen.getByText('PCR-2026-0022')).not.toBeNull();
      expect(screen.queryByText('PCR-2026-0023')).toBeNull();
      await waitFor(() => expect(api.pricingRequests.queue).toHaveBeenCalledWith({ activeOnly: true }));
      expect(tabByName('รอฉันพิจารณา').getAttribute('aria-selected')).toBe('true');
      expect(within(tabByName('รอฉันพิจารณา')).getByText('2')).not.toBeNull();
    });
  });

  describe('sales_manager role — no task tabs, defaults to ทั้งหมด (owner ruling, 2026-09-19)', () => {
    it('does not offer a pickup action to a sales_manager viewer', async () => {
      renderQueuePage(salesManagerUser);

      await screen.findByText('PCR-2026-0001');
      expect(screen.queryByTestId('pcr-queue-pickup')).toBeNull();
      // Scoped to the table so the same-wording IMPORT_REVIEWING chip does not masquerade as a pickup control and
      // make this pass for the wrong reason.
      expect(within(screen.getByRole('table')).queryByRole('button', { name: 'รับเรื่อง' })).toBeNull();
    });
  });
});
