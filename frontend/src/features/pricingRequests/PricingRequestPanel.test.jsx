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
        // PricingRequestCreateModal (V69, review remediation COMMIT 4) fetches attachments
        // whenever it has a persisted id — including edit mode, which this file's "seed the
        // modal from request detail" test exercises.
        listAttachments: vi.fn().mockResolvedValue({ items: [] }),
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

  // Ticket-detail IA rebuild Phase 1 clutter follow-up (FIX 1): this panel no
  // longer renders its own "สร้างคำขอราคา" button — TicketDetailPage's sticky
  // header CTA owns that action now (opening this panel's create modal via
  // its forwardRef, see the "imperative handle" describe block below), so
  // the same label no longer appears twice on one page. The empty state
  // still explains what the section is for, just without a duplicate CTA.
  it('shows an empty state for the deal owner when there are no requests, with no create button of its own', async () => {
    api.pricingRequests.listForTicket.mockResolvedValue({ items: [] });
    renderPanel();

    expect(await screen.findByText('ยังไม่มีคำขอราคา')).not.toBeNull();
    expect(screen.getByText(/สร้างได้จากปุ่ม/)).not.toBeNull();
    expect(screen.queryByRole('button', { name: /สร้างคำขอราคา/ })).toBeNull();
  });

  it('shows a plainer empty-state description for a non-owner (also no create button)', async () => {
    api.pricingRequests.listForTicket.mockResolvedValue({ items: [] });
    renderPanel({ user: { id: 2, name: 'อื่น', role: 'sales' } });

    await screen.findByText('ยังไม่มีคำขอราคา');
    expect(screen.getByText('ยังไม่มีคำขอราคาสำหรับดีลนี้')).not.toBeNull();
    expect(screen.queryByRole('button', { name: /สร้างคำขอราคา/ })).toBeNull();
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

  it('shows productDescription as the item identity when brand and model are blank', async () => {
    api.pricingRequests.listForTicket.mockResolvedValue({ items: [summary()] });
    api.pricingRequests.get.mockResolvedValue({
      pricingRequest: {
        summary: summary(),
        items: [{
          id: 11,
          brand: null,
          model: null,
          productDescription: 'Porcelain anti-slip tile 60x60 cm',
          color: null,
          texture: null,
          size: null,
          requestedQty: 12,
          requestedUnit: 'กล่อง',
          quantityType: 'ESTIMATE',
        }],
        events: [],
      },
    });
    renderPanel({ user: { id: 3, name: 'Import', role: 'import' } });

    fireEvent.click(await screen.findByText('PCR-2026-0001'));

    expect(await screen.findByText('Porcelain anti-slip tile 60x60 cm')).not.toBeNull();
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

  // Regression, reported from UAT 2026-08-11: submit() 422'd and the user saw NOTHING — the
  // button appeared to do nothing and the reason existed only in the browser console, because
  // submitMutation had no onError and the component rendered `.error` nowhere. The service always
  // returns a Thai, user-facing message; this asserts it actually reaches the screen.
  it('shows the server error message when submitting to Import fails, instead of failing silently', async () => {
    api.pricingRequests.listForTicket.mockResolvedValue({ items: [summary({ status: 'DRAFT' })] });
    api.pricingRequests.submit.mockRejectedValue(new Error('รายการที่ 1: ไม่ผ่านเงื่อนไขการส่ง'));
    renderPanel();

    fireEvent.click(await screen.findByRole('button', { name: 'ส่งให้ฝ่ายนำเข้า' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('รายการที่ 1: ไม่ผ่านเงื่อนไขการส่ง');
  });

  // The mutation instance is shared by every row, so an unscoped render would print one row's
  // failure under all of them. Two DRAFTs, submit the second, assert exactly one alert.
  it('reports a submit failure only under the row it was fired from', async () => {
    api.pricingRequests.listForTicket.mockResolvedValue({
      items: [
        summary({ id: 1, requestCode: 'PCR-2026-0001', status: 'DRAFT' }),
        summary({ id: 2, requestCode: 'PCR-2026-0002', status: 'DRAFT' }),
      ],
    });
    api.pricingRequests.submit.mockRejectedValue(new Error('ส่งไม่สำเร็จ'));
    renderPanel();

    const submitButtons = await screen.findAllByRole('button', { name: 'ส่งให้ฝ่ายนำเข้า' });
    fireEvent.click(submitButtons[1]);

    await screen.findByRole('alert');
    expect(screen.getAllByRole('alert')).toHaveLength(1);
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
  // — once submitted, editing must disappear the same way "ส่งให้ฝ่ายนำเข้า" does.
  it('does not offer "แก้ไขร่าง" once a request is past DRAFT', async () => {
    api.pricingRequests.listForTicket.mockResolvedValue({ items: [summary({ status: 'SUBMITTED' })] });
    renderPanel();

    await screen.findByText('PCR-2026-0001');
    expect(screen.queryByRole('button', { name: 'แก้ไขร่าง' })).toBeNull();
  });

  it('never offers "ส่งให้ฝ่ายนำเข้า" once a request is past DRAFT, even for the CEO (cancel is still allowed)', async () => {
    api.pricingRequests.listForTicket.mockResolvedValue({ items: [summary({ status: 'SUBMITTED' })] });
    renderPanel({ user: { id: 9, name: 'CEO', role: 'ceo' } });

    await screen.findByText('PCR-2026-0001');
    // Submit is DRAFT-only and owner-sales-only (PricingRequestService.submit) —
    // the CEO never gets it, regardless of status.
    expect(screen.queryByRole('button', { name: 'ส่งให้ฝ่ายนำเข้า' })).toBeNull();
    // Cancel is the one action the CEO gets on ANY cancellable status, as an
    // explicit override (PricingRequestService.cancel).
    expect(screen.getByRole('button', { name: 'ยกเลิก' })).not.toBeNull();
  });


  // V139 deleted the ขอข้อมูลเพิ่มเติม round-trip from the product — the service methods,
  // the API surface, the canRequestInformation/canRespondInformation predicates and both
  // modals are gone.
  //
  // This REPLACES three earlier tests ("does not offer ขอข้อมูลเพิ่มเติม to sales, even the
  // deal owner", and two ตอบข้อมูลเพิ่มเติม ones) that pinned WHO got each button. Those
  // discriminated on role and ownership; with the buttons gone for everyone they passed no
  // matter which user was rendered, so they had stopped testing their own subject. Two of
  // them also drove a MORE_INFO_REQUIRED fixture, a status V139 removed from the DB
  // constraint entirely.
  //
  // What replaces them is deliberately a removal guard, not a permission test: it asserts
  // the surface is ABSENT for every role that could ever have reached it, and it still goes
  // red if anyone re-adds either button. IMPORT_REVIEWING is the status the request-side
  // button used to appear in, so the fixture is the one most likely to bring it back.
  it('offers no ขอข้อมูลเพิ่มเติม surface to any role — the whole round-trip was removed', async () => {
    const roles = [
      salesOwner,
      { id: 2, name: 'อื่น', role: 'sales' },
      importUser,
      { id: 9, name: 'CEO', role: 'ceo' },
    ];

    for (const user of roles) {
      api.pricingRequests.listForTicket.mockResolvedValue({
        items: [summary({ status: 'IMPORT_REVIEWING', assignedImportId: 5, assignedImportName: importUser.name })],
      });
      const { unmount } = renderPanel({ user });

      await screen.findByText('PCR-2026-0001');
      expect(screen.queryByRole('button', { name: 'ขอข้อมูลเพิ่มเติม' })).toBeNull();
      expect(screen.queryByRole('button', { name: 'ตอบข้อมูลเพิ่มเติม' })).toBeNull();
      unmount();
    }
  });

});
