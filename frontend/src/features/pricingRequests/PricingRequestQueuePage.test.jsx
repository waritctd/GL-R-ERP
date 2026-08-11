import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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

// The chip bar and the table both contain buttons/badges with the same Thai
// wording now that IMPORT_REVIEWING reads "รับเรื่อง" (the chip) exactly like the
// per-row pickup action, so every assertion below scopes to one or the other
// instead of querying the whole document.
function chips() {
  // "ทั้งหมด" is unique to the chip bar; its parent is the chip container.
  return within(screen.getByRole('button', { name: 'ทั้งหมด' }).parentElement)
    .getAllByRole('button')
    .map((button) => button.textContent);
}

function tableRow(requestCode) {
  const found = screen.getAllByRole('row').find((r) => r.textContent.includes(requestCode));
  if (!found) throw new Error(`no rendered row for ${requestCode}`);
  return found;
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

  it('offers only the three Import stages plus ทั้งหมด/รอฝ่ายนำเข้ารับเรื่อง/ยกเลิกแล้ว as chips', async () => {
    renderQueuePage();
    await screen.findByText('PCR-2026-0001');

    expect(chips()).toEqual([
      'ทั้งหมด',
      'รอฝ่ายนำเข้ารับเรื่อง',
      'รับเรื่อง',
      'เจรจาราคากับโรงงาน',
      'รอ CEO อนุมัติราคา',
      'ยกเลิกแล้ว',
    ]);
  });

  it('no longer offers the ขอข้อมูลเพิ่มเติม or แบบร่าง chips', async () => {
    renderQueuePage();
    await screen.findByText('PCR-2026-0001');

    // MORE_INFO_REQUIRED's feature was deleted from the product, and DRAFT is the
    // sales rep's private scratchpad — neither is Import queue work.
    expect(chips()).not.toContain('รอข้อมูลเพิ่มเติม');
    expect(chips()).not.toContain('แบบร่าง');
    expect(screen.queryByRole('button', { name: 'รอข้อมูลเพิ่มเติม' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'แบบร่าง' })).toBeNull();
  });

  it('renders both AWAITING_FACTORY_RESPONSE and COSTING_IN_PROGRESS as the merged เจรจาราคากับโรงงาน stage', async () => {
    api.pricingRequests.queue.mockResolvedValue({
      items: [
        row({ id: 11, requestCode: 'PCR-2026-0011', status: 'AWAITING_FACTORY_RESPONSE' }),
        row({ id: 12, requestCode: 'PCR-2026-0012', status: 'COSTING_IN_PROGRESS' }),
      ],
    });
    renderQueuePage();
    // ทั้งหมด so the two rows are shown by the API's own result, not by the
    // client-side narrowing the merged chip does (covered separately below).
    fireEvent.click(screen.getByRole('button', { name: 'ทั้งหมด' }));
    await screen.findByText('PCR-2026-0011');

    expect(tableRow('PCR-2026-0011').textContent).toContain('เจรจาราคากับโรงงาน');
    expect(tableRow('PCR-2026-0012').textContent).toContain('เจรจาราคากับโรงงาน');
    // The two statuses these replaced must not survive anywhere on the page.
    expect(screen.queryByText('รอราคาโรงงาน')).toBeNull();
    expect(screen.queryByText('กำลังร่างต้นทุน')).toBeNull();
  });

  it('narrows the merged เจรจาราคากับโรงงาน chip client-side, because the API takes a single status', async () => {
    api.pricingRequests.queue.mockResolvedValue({
      items: [
        row({ id: 11, requestCode: 'PCR-2026-0011', status: 'AWAITING_FACTORY_RESPONSE' }),
        row({ id: 12, requestCode: 'PCR-2026-0012', status: 'COSTING_IN_PROGRESS' }),
        row({ id: 13, requestCode: 'PCR-2026-0013', status: 'IMPORT_REVIEWING' }),
      ],
    });
    renderQueuePage();
    await screen.findByText('PCR-2026-0011');

    fireEvent.click(screen.getByRole('button', { name: 'เจรจาราคากับโรงงาน' }));

    // Fetches everything: neither of the two merged statuses may be pushed down as
    // the single `status` the endpoint accepts, or the other one's rows vanish.
    await waitFor(() => expect(api.pricingRequests.queue).toHaveBeenCalledWith({ status: undefined, activeOnly: true }));
    expect(api.pricingRequests.queue).not.toHaveBeenCalledWith(
      expect.objectContaining({ status: 'AWAITING_FACTORY_RESPONSE' }),
    );
    expect(api.pricingRequests.queue).not.toHaveBeenCalledWith(
      expect.objectContaining({ status: 'COSTING_IN_PROGRESS' }),
    );

    // ...and the rows that are not part of the merged stage are dropped locally.
    // Wait for the refetched page to actually render first: clicking the chip puts
    // the table into its loading state, where "row 13 is absent" would otherwise
    // pass for the wrong reason (nothing is rendered yet at all).
    expect(await screen.findByText('PCR-2026-0011')).not.toBeNull();
    expect(screen.getByText('PCR-2026-0012')).not.toBeNull();
    expect(screen.queryByText('PCR-2026-0013')).toBeNull();
  });

  it('still pushes the single-status รอ CEO อนุมัติราคา chip down to the API', async () => {
    renderQueuePage();
    await screen.findByText('PCR-2026-0001');

    fireEvent.click(screen.getByRole('button', { name: 'รอ CEO อนุมัติราคา' }));

    await waitFor(() => expect(api.pricingRequests.queue)
      .toHaveBeenCalledWith({ status: 'READY_FOR_CEO_REVIEW', activeOnly: true }));
  });

  it('lets an import user pick up a submitted request', async () => {
    api.pricingRequests.pickup.mockResolvedValue({ pricingRequest: { summary: row({ status: 'IMPORT_REVIEWING' }), items: [], events: [] } });
    renderQueuePage();

    // By testid, not by name: the IMPORT_REVIEWING chip is now also called รับเรื่อง.
    const pickupButton = await screen.findByTestId('pcr-queue-pickup');
    fireEvent.click(pickupButton);

    await waitFor(() => expect(api.pricingRequests.pickup).toHaveBeenCalledWith(1));
  });

  it('does not offer a pickup action to a sales viewer', async () => {
    renderQueuePage({ id: 1, name: 'พนักงานขาย', role: 'sales' });

    await screen.findByText('PCR-2026-0001');
    expect(screen.queryByTestId('pcr-queue-pickup')).toBeNull();
    // Scoped to the table so the same-wording IMPORT_REVIEWING chip does not
    // masquerade as a pickup control and make this pass for the wrong reason.
    expect(within(screen.getByRole('table')).queryByRole('button', { name: 'รับเรื่อง' })).toBeNull();
  });
});
