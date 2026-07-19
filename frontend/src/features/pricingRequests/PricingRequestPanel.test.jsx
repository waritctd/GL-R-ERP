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
        update: vi.fn(),
        submit: vi.fn(),
        cancel: vi.fn(),
        respondInformation: vi.fn(),
        requestInformation: vi.fn(),
      },
    },
  };
});

const salesOwner = { id: 1, name: 'พนักงานขาย', role: 'sales' };
const importUser = { id: 5, name: 'ฝ่ายนำเข้า', role: 'import' };
const otherImportUser = { id: 6, name: 'ฝ่ายนำเข้าอีกคน', role: 'import' };
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

  // Fix 2 (review-remediation plan): a saved draft had no path to fix a wrong
  // quantity/recipient/date before this — only submit or cancel. The edit
  // modal reuses PricingRequestCreateModal in mode="edit", seeded from
  // api.pricingRequests.get, and calls update() with the full editable
  // representation (full-replacement PUT, not a sparse patch).
  it('lets the owner edit an existing DRAFT via "แก้ไขร่าง", seeding the modal from the request detail', async () => {
    api.pricingRequests.listForTicket.mockResolvedValue({ items: [summary({ status: 'DRAFT' })] });
    api.pricingRequests.get.mockResolvedValue({
      pricingRequest: {
        summary: summary({ status: 'DRAFT', recipientLabel: 'ผู้ออกแบบ ก.' }),
        items: [{ id: 11, brand: 'SCG', model: 'A1', color: 'ขาว', texture: 'ด้าน', size: '60x60', requestedQty: 10, requestedUnit: 'แผ่น', quantityType: 'ESTIMATE' }],
        events: [],
      },
    });
    api.pricingRequests.update.mockResolvedValue({
      pricingRequest: { summary: summary({ status: 'DRAFT' }), items: [], events: [] },
    });
    renderPanel();

    const editButton = await screen.findByRole('button', { name: 'แก้ไขร่าง' });
    fireEvent.click(editButton);

    await waitFor(() => expect(api.pricingRequests.get).toHaveBeenCalledWith(1));
    expect(await screen.findByDisplayValue('ผู้ออกแบบ ก.')).not.toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'บันทึกการแก้ไข' }));

    await waitFor(() => expect(api.pricingRequests.update).toHaveBeenCalledWith(1, expect.objectContaining({
      recipientLabel: 'ผู้ออกแบบ ก.',
    })));
  });

  // canUpdatePricingRequest is DRAFT-only (mirrors PricingRequestService.updateDraft)
  // — once submitted, editing must disappear the same way "ส่งให้ Import" does.
  it('does not offer "แก้ไขร่าง" once a request is past DRAFT', async () => {
    api.pricingRequests.listForTicket.mockResolvedValue({ items: [summary({ status: 'SUBMITTED' })] });
    renderPanel();

    await screen.findByText('PCR-2026-0001');
    expect(screen.queryByRole('button', { name: 'แก้ไขร่าง' })).toBeNull();
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

  it('lets the ASSIGNED import user request more information while IMPORT_REVIEWING', async () => {
    api.pricingRequests.listForTicket.mockResolvedValue({
      items: [summary({ status: 'IMPORT_REVIEWING', assignedImportId: 5, assignedImportName: importUser.name })],
    });
    api.pricingRequests.requestInformation.mockResolvedValue({
      pricingRequest: { summary: summary({ status: 'MORE_INFO_REQUIRED' }), items: [], events: [] },
    });
    renderPanel({ user: importUser });

    const requestInfoButton = await screen.findByRole('button', { name: 'ขอข้อมูลเพิ่มเติม' });
    fireEvent.click(requestInfoButton);

    const saveButton = await screen.findByRole('button', { name: 'ส่งคำขอ' });
    // Required message — the save action stays disabled until something is typed.
    expect(saveButton.disabled).toBe(true);

    const textarea = screen.getByRole('dialog').querySelector('textarea');
    fireEvent.change(textarea, { target: { value: 'ขอแบบ CAD เพิ่มเติม' } });
    expect(saveButton.disabled).toBe(false);

    fireEvent.click(saveButton);

    await waitFor(() => expect(api.pricingRequests.requestInformation).toHaveBeenCalledWith(
      1,
      { message: 'ขอแบบ CAD เพิ่มเติม', dueDate: null },
    ));
  });

  it('does not offer "ขอข้อมูลเพิ่มเติม" to an unassigned import user', async () => {
    api.pricingRequests.listForTicket.mockResolvedValue({
      items: [summary({ status: 'IMPORT_REVIEWING', assignedImportId: 5, assignedImportName: importUser.name })],
    });
    renderPanel({ user: otherImportUser });

    await screen.findByText('PCR-2026-0001');
    expect(screen.queryByRole('button', { name: 'ขอข้อมูลเพิ่มเติม' })).toBeNull();
  });

  it('does not offer "ขอข้อมูลเพิ่มเติม" to sales, even the deal owner', async () => {
    api.pricingRequests.listForTicket.mockResolvedValue({
      items: [summary({ status: 'IMPORT_REVIEWING', assignedImportId: 5, assignedImportName: importUser.name })],
    });
    renderPanel();

    await screen.findByText('PCR-2026-0001');
    expect(screen.queryByRole('button', { name: 'ขอข้อมูลเพิ่มเติม' })).toBeNull();
  });
});
