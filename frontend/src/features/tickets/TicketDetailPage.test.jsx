import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TicketDetailPage } from './TicketDetailPage.jsx';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: {
        get: vi.fn(),
        listPayments: vi.fn(),
        recordPayment: vi.fn(),
        setBilling: vi.fn(),
        listDeliveries: vi.fn(),
        reserveStock: vi.fn(),
        recordDelivery: vi.fn(),
        completeDelivery: vi.fn(),
        actions: vi.fn(),
        approve: vi.fn(),
        comment: vi.fn(),
        confirmFinalPayment: vi.fn(),
        quotation: vi.fn(),
        reject: vi.fn(),
        revision: vi.fn(),
        overrideItemPrice: vi.fn(),
        editItems: vi.fn(),
      },
      attachments: {
        list: vi.fn(),
        fileUrl: (id) => `#mock-file-${id}`,
      },
      factoryConfigs: {
        list: vi.fn(),
      },
    },
  };
});

const ceoUser = { id: 9, employeeId: 9, name: 'CEO ทดสอบ', role: 'ceo' };
// Quotation issuing (can.generateQuotation) requires role 'sales' AND
// isOwner (user.id === summary.createdById) — buildTicket()'s default
// summary.createdById is 1, so this user matches it out of the box.
const salesOwnerUser = { id: 1, employeeId: 1, name: 'พนักงานขาย', role: 'sales' };

function buildTicket(overrides = {}) {
  return {
    summary: {
      id: 701,
      code: 'PR-2026-0701',
      title: 'โครงการทดสอบ',
      status: 'price_proposed',
      customerName: 'บริษัท ทดสอบ จำกัด',
      createdById: 1,
      createdByName: 'สมชาย ใจดี',
      assignedToName: 'สมหญิง นำเข้า',
      createdAt: '2026-07-01T09:00:00.000Z',
      updatedAt: '2026-07-02T09:00:00.000Z',
      hasEdits: false,
      billingDate: null,
      dueDate: null,
      creditTermDays: null,
      lastFollowUpAt: null,
      nextFollowUpAt: null,
      paymentStage: 'NOT_REQUIRED',
      amountPayable: 0,
      amountPaid: 0,
      amountOutstanding: 0,
      overdue: false,
      ...overrides.summary,
    },
    items: overrides.items ?? [
      { id: 70101, brand: 'SCG', model: 'A1', color: 'ขาว', texture: 'ด้าน', size: '60x60', qty: 10, qtyDelivered: 0, qtyFromStock: 0, proposedPrice: 150, approvedPrice: null },
    ],
    events: overrides.events ?? [
      { id: 1, kind: 'SUBMITTED', actorName: 'สมชาย ใจดี', createdAt: '2026-07-01T09:00:00.000Z' },
    ],
    quotations: overrides.quotations ?? [],
  };
}

function renderTicketDetailPage(user = ceoUser, showToast = vi.fn()) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  const utils = render(
    <QueryClientProvider client={queryClient}>
      <TicketDetailPage
        user={user}
        ticketId={701}
        onBack={vi.fn()}
        onOpenDocument={vi.fn()}
        showToast={showToast}
      />
    </QueryClientProvider>,
  );
  return { ...utils, queryClient };
}

describe('TicketDetailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.tickets.get.mockResolvedValue({ ticket: buildTicket() });
    api.tickets.actions.mockResolvedValue({
      currentState: {
        lifecycle: 'ACTIVE',
        salesStage: 'QUOTE_DESIGN_SIDE',
        paymentStatus: null,
        fulfillmentStatus: null,
        status: 'price_proposed',
      },
      availableActions: [
        { action: 'APPROVE', kind: 'operational', label: 'อนุมัติราคา' },
        { action: 'REJECT', kind: 'operational', label: 'ตีกลับราคา', requiredFields: ['reason'] },
      ],
    });
    api.attachments.list.mockResolvedValue({ attachments: [] });
    api.tickets.listPayments.mockResolvedValue({ items: [] });
    api.tickets.listDeliveries.mockResolvedValue({ items: [] });
    api.factoryConfigs.list.mockResolvedValue({ factories: [] });
    api.tickets.approve.mockResolvedValue({
      ticket: buildTicket({ summary: { status: 'approved' } }),
    });
    api.tickets.comment.mockResolvedValue({ ticket: buildTicket() });
    api.tickets.recordPayment.mockResolvedValue({ ticket: buildTicket() });
    api.tickets.setBilling.mockResolvedValue({ ticket: buildTicket() });
    api.tickets.reserveStock.mockResolvedValue({ ticket: buildTicket() });
    api.tickets.recordDelivery.mockResolvedValue({ ticket: buildTicket() });
    api.tickets.completeDelivery.mockResolvedValue({ ticket: buildTicket() });
    api.tickets.quotation.mockResolvedValue({ ticket: buildTicket() });
    api.tickets.reject.mockResolvedValue({ ticket: buildTicket({ summary: { status: 'in_review' } }) });
    api.tickets.revision.mockResolvedValue({ ticket: buildTicket() });
    api.tickets.overrideItemPrice.mockResolvedValue({ ticket: buildTicket() });
    api.tickets.editItems.mockResolvedValue({ ticket: buildTicket() });
  });

  it('renders a ticket from a mocked api.tickets.get', async () => {
    renderTicketDetailPage();

    expect(await screen.findByRole('heading', { level: 1, name: 'บริษัท ทดสอบ จำกัด' })).not.toBeNull();
    expect(screen.getAllByText('PR-2026-0701').length).toBeGreaterThan(0);
    expect(api.tickets.get).toHaveBeenCalledWith(701);
  });

  it('approve calls the api and updates the displayed status via setQueryData (no extra ticket refetch)', async () => {
    renderTicketDetailPage();

    await screen.findByRole('heading', { level: 1, name: 'บริษัท ทดสอบ จำกัด' });
    // Status starts at "รอการอนุมัติ" (price_proposed)
    expect(screen.getByText('รอการอนุมัติ')).not.toBeNull();
    expect(api.tickets.get).toHaveBeenCalledTimes(1);

    fireEvent.click(await screen.findByRole('button', { name: /^อนุมัติ$/ }));

    await waitFor(() => expect(api.tickets.approve).toHaveBeenCalledWith(701));
    // New status renders from the mutation's setQueryData fast path.
    expect(await screen.findByText('อนุมัติแล้ว')).not.toBeNull();
    // The ticket-detail query itself was never re-fetched — the mutation
    // wrote the response straight into the cache instead.
    expect(api.tickets.get).toHaveBeenCalledTimes(1);
  });

  it('hides cockpit actions that are absent from api.tickets.actions', async () => {
    api.tickets.actions.mockResolvedValueOnce({
      currentState: {
        lifecycle: 'ACTIVE',
        salesStage: 'QUOTE_DESIGN_SIDE',
        paymentStatus: null,
        fulfillmentStatus: null,
        status: 'price_proposed',
      },
      availableActions: [],
    });

    renderTicketDetailPage();

    expect(await screen.findByRole('heading', { level: 1, name: 'บริษัท ทดสอบ จำกัด' })).not.toBeNull();
    await waitFor(() => expect(api.tickets.actions).toHaveBeenCalledWith(701));

    expect(screen.queryByRole('button', { name: /^อนุมัติ$/ })).toBeNull();
  });

  it('renders quotation recipient groups and hides mark actions absent from api.tickets.actions', async () => {
    api.tickets.get.mockResolvedValueOnce({
      ticket: buildTicket({
        quotations: [
          {
            id: 9001,
            ticketId: 701,
            number: 'QT-2026-0901',
            issuedById: 1,
            issuedByName: 'สมชาย ใจดี',
            issuedAt: '2026-07-03T09:00:00.000Z',
            totalAmount: 1000,
            currency: 'THB',
            quotationVersion: 1,
            docStatus: 'ISSUED',
            recipientType: 'DESIGNER',
            recipientLabel: 'Design Studio',
          },
          {
            id: 9002,
            ticketId: 701,
            number: 'QT-2026-0902',
            issuedById: 1,
            issuedByName: 'สมชาย ใจดี',
            issuedAt: '2026-07-04T09:00:00.000Z',
            totalAmount: 1200,
            currency: 'THB',
            quotationVersion: 1,
            docStatus: 'SENT',
            recipientType: 'OWNER',
            recipientLabel: 'Owner Co.',
            sentAt: '2026-07-04T10:00:00.000Z',
          },
        ],
      }),
    });
    api.tickets.actions.mockResolvedValueOnce({
      currentState: {
        lifecycle: 'ACTIVE',
        salesStage: 'QUOTE_DESIGN_SIDE',
        paymentStatus: null,
        fulfillmentStatus: null,
        status: 'price_proposed',
      },
      availableActions: [],
    });

    renderTicketDetailPage();

    expect(await screen.findByText('ผู้ออกแบบ')).not.toBeNull();
    expect(screen.getByText('เจ้าของ')).not.toBeNull();
    expect(screen.getByText('QT-2026-0901')).not.toBeNull();
    expect(screen.getByText('QT-2026-0902')).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'ส่งแล้ว' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'รับแล้ว' })).toBeNull();
  });

  it('renders payment totals, overdue badge, and hides record payment without the action', async () => {
    api.tickets.get.mockResolvedValueOnce({
      ticket: buildTicket({
        summary: {
          status: 'quotation_issued',
          paymentStage: 'PARTIALLY_PAID',
          amountPayable: 1000,
          amountPaid: 400,
          amountOutstanding: 600,
          dueDate: '2026-07-01',
          overdue: true,
        },
      }),
    });
    api.tickets.listPayments.mockResolvedValueOnce({
      items: [
        {
          receiptId: 1,
          kind: 'DEPOSIT',
          amount: 400,
          receivedAt: '2026-06-20T09:00:00.000Z',
          recordedByName: 'คุณบัญชี',
          note: 'โอนแล้ว',
        },
      ],
    });
    api.tickets.actions.mockResolvedValueOnce({
      currentState: {
        lifecycle: 'ACTIVE',
        salesStage: 'DEPOSIT_RECEIVED',
        paymentStatus: 'DEPOSIT_PAID',
        fulfillmentStatus: null,
        status: 'quotation_issued',
      },
      availableActions: [{ action: 'SET_BILLING', kind: 'payment', label: 'ตั้งค่าการวางบิล' }],
    });

    renderTicketDetailPage();

    expect((await screen.findAllByText('ชำระบางส่วน')).length).toBeGreaterThan(0);
    expect(screen.getAllByText('เกินกำหนด').length).toBeGreaterThan(0);
    expect(screen.getByText('฿1,000')).not.toBeNull();
    expect(screen.getByText('฿400')).not.toBeNull();
    expect(screen.getByText('฿600')).not.toBeNull();
    expect(await screen.findByText('DEPOSIT')).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'บันทึกรับชำระเงิน' })).toBeNull();
  });

  it('UX-34: Final Payment opens a confirm dialog with the real outstanding amount instead of firing the mutation on click', async () => {
    api.tickets.get.mockResolvedValueOnce({
      ticket: buildTicket({
        summary: {
          status: 'quotation_issued',
          paymentStage: 'AWAITING_FINAL_PAYMENT',
          amountPayable: 132500,
          amountPaid: 100000,
          amountOutstanding: 32500,
        },
      }),
    });
    api.tickets.actions.mockResolvedValueOnce({
      currentState: {
        lifecycle: 'ACTIVE',
        salesStage: 'DELIVERY_SCHEDULING',
        paymentStatus: 'AWAITING_FINAL_PAYMENT',
        fulfillmentStatus: null,
        status: 'quotation_issued',
      },
      availableActions: [{ action: 'FINAL_PAYMENT', kind: 'payment', label: 'ยืนยันชำระครบ' }],
    });
    api.tickets.confirmFinalPayment.mockResolvedValue({
      ticket: buildTicket({
        summary: {
          status: 'quotation_issued',
          paymentStage: 'FULLY_PAID',
          amountPayable: 132500,
          amountPaid: 132500,
          amountOutstanding: 0,
        },
      }),
    });

    renderTicketDetailPage();

    const finalPaymentButton = await screen.findByRole('button', { name: 'ยืนยันชำระครบ (Final Payment)' });
    fireEvent.click(finalPaymentButton);

    // The single click must NOT call the mutation directly — it only opens
    // the confirm dialog (this is the exact defect UX-34 flags: previously
    // this click called confirmFinalPayment straight away).
    expect(api.tickets.confirmFinalPayment).not.toHaveBeenCalled();

    // The dialog states the real outstanding amount, sourced from the same
    // summary.amountOutstanding the payment panel's "คงเหลือ" tile renders
    // (not a separately-computed figure).
    expect(await screen.findByText('ยืนยันการรับชำระครบถ้วน')).not.toBeNull();
    expect(screen.getAllByText('฿32,500').length).toBeGreaterThan(0);

    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันชำระครบ' }));

    await waitFor(() => expect(api.tickets.confirmFinalPayment).toHaveBeenCalledWith(701));
    // Dialog closes after a successful confirm.
    await waitFor(() => expect(screen.queryByText('ยืนยันการรับชำระครบถ้วน')).toBeNull());
  });

  it('renders delivery progress and hides record delivery without the action', async () => {
    api.tickets.get.mockResolvedValueOnce({
      ticket: buildTicket({
        summary: {
          status: 'quotation_issued',
          salesStage: 'PROCUREMENT',
          fulfillmentStatus: 'PARTIALLY_DELIVERED',
        },
        items: [
          { id: 70101, brand: 'SCG', model: 'A1', qty: 100, qtyDelivered: 40, qtyFromStock: 0, approvedPrice: 150 },
        ],
      }),
    });
    api.tickets.listDeliveries.mockResolvedValueOnce({
      items: [
        {
          deliveryId: 1,
          source: 'WAREHOUSE',
          deliveredAt: '2026-07-10T09:00:00.000Z',
          deliveredByName: 'คุณนำเข้า',
          note: 'ส่งบางส่วน',
          items: [{ deliveryItemId: 1, itemId: 70101, qty: 40 }],
        },
      ],
    });
    api.tickets.actions.mockResolvedValueOnce({
      currentState: {
        lifecycle: 'ACTIVE',
        salesStage: 'PROCUREMENT',
        paymentStatus: 'FULLY_PAID',
        fulfillmentStatus: 'PARTIALLY_DELIVERED',
        status: 'quotation_issued',
      },
      availableActions: [],
    });

    renderTicketDetailPage();

    expect((await screen.findAllByText('40 / 100')).length).toBeGreaterThan(0);
    expect(screen.getAllByText('ส่งมอบบางส่วน').length).toBeGreaterThan(0);
    expect(await screen.findByText('WAREHOUSE')).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'บันทึกการส่งสินค้า' })).toBeNull();
  });

  it('comment posts and invalidates the tickets-list/dashboard/notifications caches', async () => {
    const { queryClient } = renderTicketDetailPage();
    // Seed cache entries so invalidateQueries has something to mark stale —
    // an invalidate against a key with no existing entry is a no-op we can't
    // observe, so this mirrors a real session where those queries are (or
    // were) mounted elsewhere in the app.
    queryClient.setQueryData(['tickets', 'list', ''], []);
    queryClient.setQueryData(queryKeys.dashboardSummary(), {});
    queryClient.setQueryData(queryKeys.notifications(), []);

    await screen.findByRole('heading', { level: 1, name: 'บริษัท ทดสอบ จำกัด' });

    const textarea = screen.getByPlaceholderText('เพิ่มความคิดเห็น...');
    fireEvent.change(textarea, { target: { value: 'ทดสอบความคิดเห็น' } });
    fireEvent.click(screen.getByRole('button', { name: 'ส่งความคิดเห็น' }));

    await waitFor(() => expect(api.tickets.comment).toHaveBeenCalledWith(701, { message: 'ทดสอบความคิดเห็น' }));

    await waitFor(() => {
      expect(queryClient.getQueryState(['tickets', 'list', ''])?.isInvalidated).toBe(true);
      expect(queryClient.getQueryState(queryKeys.dashboardSummary())?.isInvalidated).toBe(true);
      expect(queryClient.getQueryState(queryKeys.notifications())?.isInvalidated).toBe(true);
    });
  });

  // ── UX-03 (slice 5a): inline validation for the quotation/payment/delivery
  // modals — see TicketCreateModal.jsx / DepositNoticePage.jsx for the same
  // aria-invalid + aria-describedby + role="alert" contract this mirrors. ──

  it('payment modal: submitting with an empty amount marks the amount field inline and does not call recordPayment', async () => {
    api.tickets.actions.mockResolvedValueOnce({
      currentState: {
        lifecycle: 'ACTIVE', salesStage: 'DEPOSIT_RECEIVED', paymentStatus: null, fulfillmentStatus: null, status: 'price_proposed',
      },
      availableActions: [{ action: 'RECORD_PAYMENT', kind: 'payment', label: 'บันทึกรับชำระเงิน' }],
    });

    renderTicketDetailPage();

    fireEvent.click(await screen.findByRole('button', { name: 'บันทึกรับชำระเงิน' }));
    const dialog = await screen.findByRole('dialog', { name: 'บันทึกรับชำระเงิน' });

    // buildTicket()'s default amountOutstanding is 0, so openPaymentModal()
    // leaves the amount field blank (its own suggested-amount logic only
    // fills in a value when amountOutstanding > 0) — submitting right away
    // exercises the "empty amount" branch of the guard.
    const amountInput = within(dialog).getByLabelText('จำนวนเงิน');
    expect(amountInput.value).toBe('');
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));

    const error = await within(dialog).findByText('กรุณากรอกยอดรับชำระ');
    expect(error.getAttribute('role')).toBe('alert');
    expect(amountInput.getAttribute('aria-invalid')).toBe('true');
    expect(amountInput.getAttribute('aria-describedby')).toBe(error.id);
    expect(api.tickets.recordPayment).not.toHaveBeenCalled();

    // Fixing the field clears its inline error (and only that error).
    fireEvent.change(amountInput, { target: { value: '500' } });
    await waitFor(() => expect(within(dialog).queryByText('กรุณากรอกยอดรับชำระ')).toBeNull());
    expect(amountInput.getAttribute('aria-invalid')).toBeNull();
  });

  it('quotation modal: submitting with no recipient marks the recipient control inline and does not call the quotation API', async () => {
    api.tickets.get.mockResolvedValueOnce({
      ticket: buildTicket({ summary: { status: 'approved', createdById: 1 } }),
    });
    api.tickets.actions.mockResolvedValueOnce({
      currentState: {
        lifecycle: 'ACTIVE', salesStage: 'QUOTE_DESIGN_SIDE', paymentStatus: null, fulfillmentStatus: null, status: 'approved',
      },
      availableActions: [{ action: 'GENERATE_QUOTATION', kind: 'operational', label: 'ออกใบเสนอราคา' }],
    });

    renderTicketDetailPage(salesOwnerUser);

    fireEvent.click(await screen.findByRole('button', { name: 'ออกใบเสนอราคา' }));
    const dialog = await screen.findByRole('dialog', { name: 'ออกใบเสนอราคา' });

    // No blank option exists on the select (DESIGNER/OWNER/BUYER only) — this
    // simulates the recipientType being cleared out from under the control,
    // which is exactly what `!quotationDraft.recipientType` guards against.
    const recipientSelect = within(dialog).getByLabelText('ผู้รับใบเสนอราคา');
    fireEvent.change(recipientSelect, { target: { value: '' } });

    fireEvent.click(within(dialog).getByRole('button', { name: 'ออกใบเสนอราคา' }));

    const error = await within(dialog).findByText('กรุณาเลือกผู้รับใบเสนอราคา');
    expect(error.getAttribute('role')).toBe('alert');
    expect(recipientSelect.getAttribute('aria-invalid')).toBe('true');
    expect(recipientSelect.getAttribute('aria-describedby')).toBe(error.id);
    expect(api.tickets.quotation).not.toHaveBeenCalled();
  });

  it('quotation modal: the amendment-reason rule only fires when amendmentRequired is true', async () => {
    // amendmentRequired = chainAccepted || summary.paymentStatus != null —
    // a non-null paymentStatus is the simplest way to force it true.
    api.tickets.get.mockResolvedValueOnce({
      ticket: buildTicket({ summary: { status: 'approved', createdById: 1, paymentStatus: 'DEPOSIT_PAID' } }),
    });
    api.tickets.actions.mockResolvedValueOnce({
      currentState: {
        lifecycle: 'ACTIVE', salesStage: 'QUOTE_DESIGN_SIDE', paymentStatus: 'DEPOSIT_PAID', fulfillmentStatus: null, status: 'approved',
      },
      availableActions: [{ action: 'GENERATE_QUOTATION', kind: 'operational', label: 'ออกใบเสนอราคา' }],
    });

    renderTicketDetailPage(salesOwnerUser);

    // buildTicket()'s default quotations is [] (no prior quotation), so the
    // open-trigger button reads plain 'ออกใบเสนอราคา' (the '...ใหม่ (Rev)'
    // label only appears once quotations.length > 0) — amendmentRequired is
    // still forced true here via summary.paymentStatus, independent of that.
    fireEvent.click(await screen.findByRole('button', { name: 'ออกใบเสนอราคา' }));
    const dialog = await screen.findByRole('dialog', { name: 'ออกใบเสนอราคา' });

    // recipientType already defaults to a valid value (DESIGNER) — only the
    // amendment reason is missing, isolating this one rule. Grab the field
    // reference before submitting: once its inline error renders, the error
    // <p> is a descendant of the same <label> (same convention as
    // TicketCreateModal.jsx), which would make the label's accessible name
    // ambiguous for a later getByLabelText lookup.
    const reasonField = within(dialog).getByLabelText('เหตุผลการแก้ไข');
    fireEvent.click(within(dialog).getByRole('button', { name: 'ออกใบเสนอราคา' }));

    const error = await within(dialog).findByText('กรุณาระบุเหตุผลการแก้ไขใบเสนอราคา');
    expect(error.getAttribute('role')).toBe('alert');
    expect(reasonField.getAttribute('aria-invalid')).toBe('true');
    expect(api.tickets.quotation).not.toHaveBeenCalled();

    // Fixing it clears the error and lets the submit through.
    fireEvent.change(reasonField, { target: { value: 'ลูกค้าขอปรับราคาใหม่' } });
    await waitFor(() => expect(within(dialog).queryByText('กรุณาระบุเหตุผลการแก้ไขใบเสนอราคา')).toBeNull());
    fireEvent.click(within(dialog).getByRole('button', { name: 'ออกใบเสนอราคา' }));
    await waitFor(() => expect(api.tickets.quotation).toHaveBeenCalledTimes(1));
  });

  it('quotation modal: amendment reason is not required when amendmentRequired is false', async () => {
    // Default fixture: no accepted quotation in the chain, no paymentStatus
    // — amendmentRequired stays false, so the field never even renders and
    // submit must go straight through without any inline error.
    api.tickets.get.mockResolvedValueOnce({
      ticket: buildTicket({ summary: { status: 'approved', createdById: 1 } }),
    });
    api.tickets.actions.mockResolvedValueOnce({
      currentState: {
        lifecycle: 'ACTIVE', salesStage: 'QUOTE_DESIGN_SIDE', paymentStatus: null, fulfillmentStatus: null, status: 'approved',
      },
      availableActions: [{ action: 'GENERATE_QUOTATION', kind: 'operational', label: 'ออกใบเสนอราคา' }],
    });

    renderTicketDetailPage(salesOwnerUser);

    fireEvent.click(await screen.findByRole('button', { name: 'ออกใบเสนอราคา' }));
    const dialog = await screen.findByRole('dialog', { name: 'ออกใบเสนอราคา' });

    expect(within(dialog).queryByLabelText('เหตุผลการแก้ไข')).toBeNull();
    fireEvent.click(within(dialog).getByRole('button', { name: 'ออกใบเสนอราคา' }));

    await waitFor(() => expect(api.tickets.quotation).toHaveBeenCalledTimes(1));
    expect(within(dialog).queryByRole('alert')).toBeNull();
  });

  it('delivery modal: submitting with no line quantities shows the group-level error and does not call recordDelivery', async () => {
    api.tickets.get.mockResolvedValueOnce({
      ticket: buildTicket({
        summary: { status: 'quotation_issued', salesStage: 'PROCUREMENT', fulfillmentStatus: null },
        items: [{ id: 70101, brand: 'SCG', model: 'A1', qty: 10, qtyDelivered: 0, qtyFromStock: 0, approvedPrice: 150 }],
      }),
    });
    api.tickets.actions.mockResolvedValueOnce({
      currentState: {
        lifecycle: 'ACTIVE', salesStage: 'PROCUREMENT', paymentStatus: 'FULLY_PAID', fulfillmentStatus: null, status: 'quotation_issued',
      },
      availableActions: [{ action: 'RECORD_PARTIAL_DELIVERY', kind: 'fulfillment', label: 'บันทึกการส่งสินค้า' }],
    });

    renderTicketDetailPage();

    fireEvent.click(await screen.findByRole('button', { name: 'บันทึกการส่งสินค้า' }));
    const dialog = await screen.findByRole('dialog', { name: 'บันทึกการส่งสินค้า' });

    // openDeliveryModal() prefills each line with its remaining qty (here
    // 10), so the "no line has qty > 0" rule only fires once every line is
    // explicitly zeroed out — this is the single-item case.
    const qtyInput = within(dialog).getByLabelText('จำนวนส่งมอบ SCG A1');
    fireEvent.change(qtyInput, { target: { value: '0' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));

    const error = await within(dialog).findByText('กรุณาระบุจำนวนส่งมอบอย่างน้อย 1 รายการ');
    expect(error.getAttribute('role')).toBe('alert');
    const panel = document.getElementById('delivery-lines-panel');
    expect(panel.getAttribute('aria-invalid')).toBe('true');
    expect(panel.getAttribute('aria-describedby')).toBe(error.id);
    expect(api.tickets.recordDelivery).not.toHaveBeenCalled();

    // Fixing one line (qty > 0) clears the group-level error.
    fireEvent.change(qtyInput, { target: { value: '3' } });
    await waitFor(() => expect(within(dialog).queryByText('กรุณาระบุจำนวนส่งมอบอย่างน้อย 1 รายการ')).toBeNull());
    expect(panel.getAttribute('aria-invalid')).toBeNull();
  });

  // ── UX-03 (slice 5b — final slice): the 4 remaining inline page-body
  // validations (reject / revise / per-item override / edit-items qty). ──

  it('reject form: a blank reason marks the reason textarea inline, does not call api.tickets.reject, and clears once fixed', async () => {
    renderTicketDetailPage();

    fireEvent.click(await screen.findByRole('button', { name: 'ไม่อนุมัติ' }));
    const reasonField = await screen.findByLabelText('เหตุผลในการตีกลับ *');

    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันไม่อนุมัติ' }));

    const error = await screen.findByText('กรุณาระบุเหตุผลในการตีกลับ');
    expect(error.getAttribute('role')).toBe('alert');
    expect(reasonField.getAttribute('aria-invalid')).toBe('true');
    expect(reasonField.getAttribute('aria-describedby')).toBe(error.id);
    expect(api.tickets.reject).not.toHaveBeenCalled();

    fireEvent.change(reasonField, { target: { value: 'ราคาสูงเกินไป' } });
    await waitFor(() => expect(screen.queryByText('กรุณาระบุเหตุผลในการตีกลับ')).toBeNull());
    expect(reasonField.getAttribute('aria-invalid')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันไม่อนุมัติ' }));
    await waitFor(() => expect(api.tickets.reject).toHaveBeenCalledWith(701, { reason: 'ราคาสูงเกินไป' }));
  });

  it('CEO price override: an invalid price on one item marks only that item\'s input, never the other item\'s', async () => {
    api.tickets.get.mockResolvedValueOnce({
      ticket: buildTicket({
        items: [
          { id: 70101, brand: 'SCG', model: 'A1', qty: 10, qtyDelivered: 0, qtyFromStock: 0, calcedCost: 100, calcedPrice: 150, approvedPrice: null },
          { id: 70102, brand: 'Cotto', model: 'B2', qty: 5, qtyDelivered: 0, qtyFromStock: 0, calcedCost: 80, calcedPrice: 120, approvedPrice: null },
        ],
      }),
    });
    api.tickets.actions.mockResolvedValueOnce({
      currentState: {
        lifecycle: 'ACTIVE', salesStage: 'QUOTE_DESIGN_SIDE', paymentStatus: null, fulfillmentStatus: null, status: 'price_proposed',
      },
      availableActions: [{ action: 'OVERRIDE_ITEM_PRICE', kind: 'operational', label: 'ตั้งราคาเอง' }],
    });

    renderTicketDetailPage();

    // showCalcBreakdown (role ceo + some item.calcedCost != null) renders an
    // "override" trigger per row — open only item 1's editor.
    const overrideButtons = await screen.findAllByRole('button', { name: 'override' });
    expect(overrideButtons).toHaveLength(2);
    fireEvent.click(overrideButtons[0]);

    const priceInput = document.getElementById('override-price-70101');
    expect(priceInput).not.toBeNull();
    // Item 2's editor was never opened, so it has no override input at all —
    // there is nothing for the error to leak onto.
    expect(document.getElementById('override-price-70102')).toBeNull();

    // The editor opens pre-filled with item.calcedPrice (150, a valid
    // price) — clear it to an invalid value to actually exercise the guard.
    fireEvent.change(priceInput, { target: { value: '0' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));

    const error = await screen.findByText('กรุณากรอกราคา override ที่ถูกต้อง');
    expect(error.getAttribute('role')).toBe('alert');
    expect(priceInput.getAttribute('aria-invalid')).toBe('true');
    expect(priceInput.getAttribute('aria-describedby')).toBe(error.id);
    expect(api.tickets.overrideItemPrice).not.toHaveBeenCalled();

    // Fixing item 1's price clears its own error and lets the save through
    // with the exact same payload shape as before this slice.
    fireEvent.change(priceInput, { target: { value: '135' } });
    await waitFor(() => expect(screen.queryByText('กรุณากรอกราคา override ที่ถูกต้อง')).toBeNull());
    expect(priceInput.getAttribute('aria-invalid')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));
    await waitFor(() => expect(api.tickets.overrideItemPrice).toHaveBeenCalledWith(701, 70101, { manualPrice: 135, reason: null }));
  });

  it('edit-items: multiple invalid rows each get their own inline qty error — not one shared message', async () => {
    api.tickets.get.mockResolvedValueOnce({
      ticket: buildTicket({
        summary: { status: 'submitted', createdById: 1 },
        items: [
          { id: 70101, brand: 'SCG', model: 'A1', qty: 0, qtyDelivered: 0, qtyFromStock: 0, approvedPrice: null },
          { id: 70102, brand: 'Cotto', model: 'B2', qty: 0, qtyDelivered: 0, qtyFromStock: 0, approvedPrice: null },
        ],
      }),
    });
    api.tickets.actions.mockResolvedValueOnce({
      currentState: {
        lifecycle: 'ACTIVE', salesStage: 'LEAD', paymentStatus: null, fulfillmentStatus: null, status: 'submitted',
      },
      availableActions: [{ action: 'EDIT_ITEMS', kind: 'operational', label: 'แก้ไขรายการสินค้า' }],
    });

    renderTicketDetailPage(salesOwnerUser);

    fireEvent.click(await screen.findByRole('button', { name: 'แก้ไขรายการสินค้า' }));
    fireEvent.click(await screen.findByRole('button', { name: 'บันทึกการแก้ไข' }));

    // This is the headline assertion for this slice: the old code showed ONE
    // toast covering every row ("กรุณากรอกจำนวนสินค้าให้ครบทุกรายการ"); now
    // each offending row gets its own inline error message and input.
    const errors = await screen.findAllByText('กรุณากรอกจำนวนสินค้าของรายการนี้ให้ถูกต้อง');
    expect(errors).toHaveLength(2);

    const qtyInput0 = document.getElementById('edit-item-qty-0');
    const qtyInput1 = document.getElementById('edit-item-qty-1');
    expect(qtyInput0.getAttribute('aria-invalid')).toBe('true');
    expect(qtyInput1.getAttribute('aria-invalid')).toBe('true');
    // Distinct describedby ids — row 1's error text is not row 2's.
    expect(qtyInput0.getAttribute('aria-describedby')).not.toBe(qtyInput1.getAttribute('aria-describedby'));
    expect(api.tickets.editItems).not.toHaveBeenCalled();

    // Fixing only row 1 clears row 1's error and leaves row 2's in place.
    fireEvent.change(qtyInput0, { target: { value: '3' } });
    await waitFor(() => expect(qtyInput0.getAttribute('aria-invalid')).toBeNull());
    expect(qtyInput1.getAttribute('aria-invalid')).toBe('true');
    expect(screen.getAllByText('กรุณากรอกจำนวนสินค้าของรายการนี้ให้ถูกต้อง')).toHaveLength(1);

    // Fixing row 2 too lets the save through with the unchanged payload shape.
    fireEvent.change(qtyInput1, { target: { value: '2' } });
    await waitFor(() => expect(qtyInput1.getAttribute('aria-invalid')).toBeNull());
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกการแก้ไข' }));
    await waitFor(() => expect(api.tickets.editItems).toHaveBeenCalledTimes(1));
    expect(api.tickets.editItems.mock.calls[0][0]).toBe(701);
    expect(api.tickets.editItems.mock.calls[0][1].items.map((it) => it.qty)).toEqual([3, 2]);
  });

  it('revise form: the confirm button is disabled on a blank reason (pre-existing guard, unchanged) and submits once filled', async () => {
    // can.revise has no hasAction() gate — only status + role + isOwner — so
    // no availableActions entry is needed for this button to appear.
    api.tickets.get.mockResolvedValueOnce({
      ticket: buildTicket({ summary: { status: 'approved', createdById: 1 } }),
    });
    api.tickets.actions.mockResolvedValueOnce({
      currentState: {
        lifecycle: 'ACTIVE', salesStage: 'QUOTE_DESIGN_SIDE', paymentStatus: null, fulfillmentStatus: null, status: 'approved',
      },
      availableActions: [],
    });

    renderTicketDetailPage(salesOwnerUser);

    fireEvent.click(await screen.findByRole('button', { name: /ขอแก้ไข \(Revise\)/ }));
    const confirmButton = screen.getByRole('button', { name: 'ยืนยันขอแก้ไข' });

    // The button's disabled={actionLoading || !reviseReason.trim()} guard
    // (unchanged by this slice) makes the inline-error branch in its onClick
    // unreachable through a real click while the reason is blank — jsdom
    // respects the native disabled attribute, so fireEvent.click on it does
    // not invoke the handler. This is the narrowest honest proof available:
    // the guard still gates the button exactly as before.
    expect(confirmButton.disabled).toBe(true);
    fireEvent.click(confirmButton);
    expect(api.tickets.revision).not.toHaveBeenCalled();

    const reasonField = screen.getByLabelText('เหตุผลการแก้ไข *');
    fireEvent.change(reasonField, { target: { value: 'ลูกค้าขอเปลี่ยนจำนวน' } });
    expect(confirmButton.disabled).toBe(false);

    fireEvent.click(confirmButton);
    await waitFor(() => expect(api.tickets.revision).toHaveBeenCalledWith(701, { scope: 'QTY_OR_NOTE', reason: 'ลูกค้าขอเปลี่ยนจำนวน' }));
    expect(screen.queryByRole('alert')).toBeNull();
  });
});
