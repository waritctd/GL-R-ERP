import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
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
        comment: vi.fn(),
        confirmFinalPayment: vi.fn(),
        // Ticket-detail IA rebuild Phase 1 clutter follow-up (FIX 2): เลื่อนไป
        // moved into the header overflow menu, whose onSelect calls this via
        // DealStagePanel's own onUpdateStage prop — see the "stage-advance
        // readiness travels with เลื่อนไป" describe block.
        updateStage: vi.fn(),
        revision: vi.fn(),
        editItems: vi.fn(),
        downloadQuotationXlsx: vi.fn(),
        downloadQuotationPdf: vi.fn(),
        // Deal tracking (V83, Slice B1/B2 "kill the weekly report" — handoff 103).
        listActivities: vi.fn(),
        addActivity: vi.fn(),
        updateTracking: vi.fn(),
        // Deposit (Phase 3 Slice S3 — handoff 105): DealDepositPanel's own
        // mutations, self-contained like DealQuotationPanel's.
        setDepositPolicy: vi.fn(),
        confirmDepositPaid: vi.fn(),
        // Fulfilment (Phase 3 Slice S4 — handoff 105): DealFulfilmentPanel's
        // own mutations, same self-contained pattern.
        issueImportRequest: vi.fn(),
        markIrSent: vi.fn(),
        markShipping: vi.fn(),
        markGoodsReceived: vi.fn(),
      },
      attachments: {
        list: vi.fn(),
        fileUrl: (id) => `#mock-file-${id}`,
      },
      // Commit 6: PricingRequestPanel (mounted below the items table),
      // DealStagePanel's substep strip, and DealStateHeader's PCR chip all
      // read this list. DealQuotationPanel (Phase 2 Slice S2) reads the
      // customer-quotation tail for whichever request reaches
      // APPROVED_FOR_QUOTATION+ — see its own tests below.
      pricingRequests: {
        listForTicket: vi.fn(),
        get: vi.fn(),
        listCustomerQuotations: vi.fn(),
        createCustomerQuotation: vi.fn(),
        issueCustomerQuotation: vi.fn(),
        recordCustomerQuotationOutcome: vi.fn(),
        confirmOrder: vi.fn(),
        createDepositNoticeFromQuotation: vi.fn(),
        downloadCustomerQuotationPdf: vi.fn(),
        downloadCustomerQuotationXlsx: vi.fn(),
      },
      // Deposit (Phase 3 Slice S3 — handoff 105): DealDepositPanel reads/writes
      // this namespace directly, same pattern as pricingRequests above.
      depositNotices: {
        listByTicket: vi.fn(),
        issue: vi.fn(),
        preview: vi.fn(),
        downloadXlsx: vi.fn(),
        downloadPdf: vi.fn(),
      },
      // Fulfilment (Phase 3 Slice S4 — handoff 105): DealFulfilmentPanel's
      // optional per-factory PO detail — import/CEO only, see its own tests
      // below.
      procurement: {
        listForPricingRequest: vi.fn(),
      },
    },
  };
});

const ceoUser = { id: 9, employeeId: 9, name: 'CEO ทดสอบ', role: 'ceo' };
// Quotation issuing (can.generateQuotation) requires role 'sales' AND
// isOwner (user.id === summary.createdById) — buildTicket()'s default
// summary.createdById is 1, so this user matches it out of the box.
const salesOwnerUser = { id: 1, employeeId: 1, name: 'พนักงานขาย', role: 'sales' };
const accountUser = { id: 5, employeeId: 5, name: 'ฝ่ายบัญชี', role: 'account' };

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
      {/* DealQuotationPanel (Phase 2 Slice S2) links out to
          /pricing-requests/:id and uses useNavigate — needs Router context. */}
      <MemoryRouter>
        <TicketDetailPage
          user={user}
          ticketId={701}
          onBack={vi.fn()}
          showToast={showToast}
        />
      </MemoryRouter>
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
      // PICKUP/PROPOSE_PRICE/CALCULATE_PRICES/OVERRIDE_ITEM_PRICE/APPROVE/REJECT/
      // GENERATE_QUOTATION/MARK_QUOTATION_* are retired (Phase 2 Slice S1/S2) — the
      // real actions() endpoint never advertises them any more either.
      availableActions: [],
    });
    api.attachments.list.mockResolvedValue({ attachments: [] });
    api.tickets.listPayments.mockResolvedValue({ items: [] });
    api.tickets.listDeliveries.mockResolvedValue({ items: [] });
    api.tickets.comment.mockResolvedValue({ ticket: buildTicket() });
    api.tickets.recordPayment.mockResolvedValue({ ticket: buildTicket() });
    api.tickets.setBilling.mockResolvedValue({ ticket: buildTicket() });
    api.tickets.reserveStock.mockResolvedValue({ ticket: buildTicket() });
    api.tickets.recordDelivery.mockResolvedValue({ ticket: buildTicket() });
    api.tickets.completeDelivery.mockResolvedValue({ ticket: buildTicket() });
    api.tickets.revision.mockResolvedValue({ ticket: buildTicket() });
    api.tickets.editItems.mockResolvedValue({ ticket: buildTicket() });
    api.pricingRequests.listForTicket.mockResolvedValue({ items: [] });
    api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [] });
    api.tickets.listActivities.mockResolvedValue({ items: [] });
    api.tickets.addActivity.mockResolvedValue({
      id: 1, ticketId: 701, activityDate: '2026-07-10', kind: 'CALL', note: null,
      createdById: 1, createdByName: 'สมชาย ใจดี', createdAt: '2026-07-10T09:00:00.000Z',
    });
    api.tickets.updateTracking.mockResolvedValue({ ticket: buildTicket() });
    // Deposit (Phase 3 Slice S3 — handoff 105): DealDepositPanel mounts for
    // every role that isn't 'import' (sections.depositNotice), so its list
    // query needs a default resolve or every existing test above would hit
    // "api.depositNotices.listByTicket is not a function"-shaped rejections.
    api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [] });
    // Fulfilment (Phase 3 Slice S4 — handoff 105): DealFulfilmentPanel mounts
    // for every role that isn't 'hr' (sections.delivery — import/account/
    // sales/sales_manager/ceo all see it now), so a default resolve is
    // needed the same way api.depositNotices.listByTicket needed one above.
    api.procurement.listForPricingRequest.mockResolvedValue({ factoryPurchaseOrders: [] });
  });

  it('renders a ticket from a mocked api.tickets.get', async () => {
    renderTicketDetailPage();

    expect(await screen.findByRole('heading', { level: 1, name: 'บริษัท ทดสอบ จำกัด' })).not.toBeNull();
    expect(screen.getAllByText('PR-2026-0701').length).toBeGreaterThan(0);
    expect(api.tickets.get).toHaveBeenCalledWith(701);
  });

  // Phase 2 Slice S1/S2 "engine collapse" (docs/agent-handoffs/104): ticket-native
  // submit/pickup/propose-price/calculate-prices/override-item-price/approve/reject/
  // generate-quotation/mark-quotation-* have no route, no hrApi method, and no render
  // path any more. These assert the dead controls are actually GONE — even under a
  // (deliberately unrealistic) actions() payload that still lists the retired verbs,
  // proving the page no longer reads them at all rather than merely not being handed
  // them today.
  it('never renders the retired price-approval/quotation-generate controls, even if actions() lists the retired verbs', async () => {
    api.tickets.actions.mockResolvedValueOnce({
      currentState: {
        lifecycle: 'ACTIVE', salesStage: 'QUOTE_DESIGN_SIDE', paymentStatus: null, fulfillmentStatus: null, status: 'price_proposed',
      },
      availableActions: [
        { action: 'APPROVE', kind: 'operational', label: 'อนุมัติราคา' },
        { action: 'REJECT', kind: 'operational', label: 'ตีกลับราคา' },
        { action: 'PICKUP', kind: 'operational', label: 'รับเรื่อง' },
        { action: 'PROPOSE_PRICE', kind: 'operational', label: 'เสนอราคา' },
        { action: 'CALCULATE_PRICES', kind: 'operational', label: 'คำนวณราคา' },
        { action: 'GENERATE_QUOTATION', kind: 'operational', label: 'ออกใบเสนอราคา' },
      ],
    });

    renderTicketDetailPage();

    expect(await screen.findByRole('heading', { level: 1, name: 'บริษัท ทดสอบ จำกัด' })).not.toBeNull();
    await waitFor(() => expect(api.tickets.actions).toHaveBeenCalledWith(701));

    expect(screen.queryByRole('button', { name: /^อนุมัติ$/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /^ไม่อนุมัติ$/ })).toBeNull();
    expect(screen.queryByRole('button', { name: 'รับเรื่อง' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'เสนอราคาสินค้า' })).toBeNull();
    expect(screen.queryByRole('button', { name: /คำนวณราคา/ })).toBeNull();
    expect(screen.queryByRole('button', { name: 'ออกใบเสนอราคา' })).toBeNull();
    expect(screen.queryByRole('heading', { level: 2, name: 'การอนุมัติราคา' })).toBeNull();
  });

  // Phase 2 Slice S2: the state header sits above every other section and
  // names the sales stage, PCR status, payment/fulfilment status, and deal
  // value at a glance.
  it('renders the DealStateHeader stat strip', async () => {
    api.tickets.get.mockResolvedValueOnce({
      ticket: buildTicket({ summary: { salesStage: 'QUOTE_DESIGN_SIDE', amountPayable: 50000 } }),
    });

    renderTicketDetailPage();

    expect(await screen.findByText('ขั้นตอนดีล')).not.toBeNull();
    // DealStagePanel below also names the current stage — assert presence, not uniqueness.
    expect(screen.getAllByText('เสนอราคาผู้ออกแบบ/เจ้าของ').length).toBeGreaterThan(0);
    expect(screen.getByText('มูลค่าดีล')).not.toBeNull();
    // The payment panel below also renders amountPayable — assert presence, not uniqueness.
    expect(screen.getAllByText('฿50,000').length).toBeGreaterThan(0);
  });

  it('renders legacy quotation revisions read-only — no revise/mark-sent/mark-decision buttons', async () => {
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
    expect(screen.queryByRole('button', { name: 'ปฏิเสธ' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Revise/ })).toBeNull();
    // Download stays — legacy quotations remain reachable, just read-only.
    expect(screen.getAllByRole('button', { name: /PDF/ }).length).toBeGreaterThan(0);
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
    // DealStateHeader's "มูลค่าดีล" chip also renders amountPayable — assert presence, not uniqueness.
    expect(screen.getAllByText('฿1,000').length).toBeGreaterThan(0);
    expect(screen.getByText('฿400')).not.toBeNull();
    expect(screen.getByText('฿600')).not.toBeNull();
    expect(await screen.findByText('DEPOSIT')).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'บันทึกรับชำระเงิน' })).toBeNull();
  });

  it('lets Accounting open Ticket Detail without calling or rendering Pricing Requests', async () => {
    api.tickets.get.mockResolvedValueOnce({
      ticket: buildTicket({
        summary: {
          status: 'quotation_issued',
          paymentStage: 'PARTIALLY_PAID',
          amountPayable: 1000,
          amountPaid: 400,
          amountOutstanding: 600,
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

    renderTicketDetailPage(accountUser);

    expect(await screen.findByRole('heading', { level: 1, name: 'บริษัท ทดสอบ จำกัด' })).not.toBeNull();
    expect(await screen.findByText('DEPOSIT')).not.toBeNull();
    expect(screen.getAllByText('฿400').length).toBeGreaterThan(0);
    expect(api.pricingRequests.listForTicket).not.toHaveBeenCalled();
    expect(screen.queryByRole('heading', { name: 'ใบขอราคา (Pricing Request)' })).toBeNull();
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

  // The delivery modal (and its "at least 1 line qty > 0" group-level guard)
  // moved into DealFulfilmentPanel (Phase 3 Slice S4 — see
  // docs/agent-handoffs/105_feat-deal-deposit-fulfilment-unify.md), which
  // does not carry TicketDetailPage's aria-invalid/fieldErrors apparatus —
  // it reports the same guard via a toast, matching DealDepositPanel/
  // DealQuotationPanel's simpler mutation-level error convention. Full
  // coverage of this modal lives in the 'deal fulfilment panel' describe
  // block below; this is now just the "shows a toast, not a crash" case.
  it('delivery modal: submitting with no line quantities shows a toast and does not call recordDelivery', async () => {
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

    const showToast = vi.fn();
    renderTicketDetailPage(ceoUser, showToast);

    fireEvent.click(await screen.findByRole('button', { name: 'บันทึกการส่งสินค้า' }));
    const dialog = await screen.findByRole('dialog', { name: 'บันทึกการส่งสินค้า' });

    // openDeliveryModal() prefills each line with its remaining qty (here
    // 10), so the "no line has qty > 0" rule only fires once every line is
    // explicitly zeroed out — this is the single-item case.
    const qtyInput = within(dialog).getByLabelText('จำนวนส่งมอบ SCG A1');
    fireEvent.change(qtyInput, { target: { value: '0' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));

    expect(showToast).toHaveBeenCalledWith('error', 'กรุณาระบุจำนวนส่งมอบอย่างน้อย 1 รายการ');
    expect(api.tickets.recordDelivery).not.toHaveBeenCalled();

    // Fixing the line (qty > 0) lets the save through.
    fireEvent.change(qtyInput, { target: { value: '3' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));
    await waitFor(() => expect(api.tickets.recordDelivery).toHaveBeenCalledWith(
      701, { source: 'WAREHOUSE', note: null, lines: [{ itemId: 70101, qty: 3 }] },
    ));
  });

  // ── UX-03 (slice 5b — final slice): the remaining inline page-body
  // validations (revise / edit-items qty). Reject-form and CEO-price-override
  // were retired along with ticket-native pricing (Phase 2 Slice S1/S2). ──

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

    // ขอแก้ไข (Revise) collapsed into the header's "⋯" overflow menu
    // (ticket-detail IA rebuild Phase 1) — open the menu, then its item.
    fireEvent.click(await screen.findByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: /ขอแก้ไข \(Revise\)/ }));
    const confirmButton = await screen.findByRole('button', { name: 'ยืนยันขอแก้ไข' });

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

  // Ticket-detail IA rebuild Phase 1 clutter follow-up (FIX 1): CREATE_PCR
  // used to render as a "scroll to PricingRequestPanel" sticky button while
  // that panel ALSO rendered its own "สร้างใบขอราคา" button — the same label,
  // visible twice at once (the sticky bar never scrolls out of view). The
  // sticky bar now opens PricingRequestPanel's create modal directly via its
  // forwardRef, and the panel renders no button of its own.
  describe('sticky header primary CTA — CREATE_PCR owns "สร้างใบขอราคา" alone', () => {
    it('renders exactly one "สร้างใบขอราคา" control (the sticky primary), and clicking it opens the PCR panel\'s own create modal', async () => {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({ summary: { lifecycle: 'ACTIVE', salesStage: 'LEAD_APPROACH', createdById: 1 } }),
      });
      api.tickets.actions.mockResolvedValue({
        currentState: { lifecycle: 'ACTIVE', salesStage: 'LEAD_APPROACH', paymentStatus: null, fulfillmentStatus: null, status: 'price_proposed' },
        availableActions: [],
      });
      api.pricingRequests.listForTicket.mockResolvedValue({ items: [] });

      renderTicketDetailPage(salesOwnerUser);

      expect(await screen.findByText('ถึงคิวคุณ: สร้างใบขอราคา')).not.toBeNull();
      const stickyButtons = await screen.findAllByRole('button', { name: /สร้างใบขอราคา/ });
      expect(stickyButtons).toHaveLength(1);
      expect(screen.queryByRole('dialog')).toBeNull();

      fireEvent.click(stickyButtons[0]);

      expect(await screen.findByRole('dialog')).not.toBeNull();
      // Still exactly one "สร้างใบขอราคา" trigger even with the modal open —
      // the modal's own title text uses the same string, so this scopes to
      // buttons only (not headings) to keep proving "no duplicate button".
      expect(screen.getAllByRole('button', { name: /สร้างใบขอราคา/ })).toHaveLength(1);
    });
  });

  // Ticket-detail IA rebuild Phase 1 clutter follow-up round 2 (FIX 2): the
  // previous pass only de-duplicated "สร้างใบขอราคา" — an independent review
  // caught that "ออกใบเสนอราคา" (DealQuotationPanel.jsx, salesActions.js's
  // ISSUE_QUOTATION label) and "ยืนยันคำสั่งซื้อ" (CONFIRM_ORDER) were STILL
  // rendering twice: once as the sticky bar's own scroll-to-DealQuotationPanel
  // button, once as DealQuotationPanel's own button underneath — both visible
  // at once since the sticky bar never scrolls away. Same "ref-opener, one
  // copy on the page, and it actually performs the mutation" fix as CREATE_PCR.
  describe('sticky header primary CTA — ISSUE_QUOTATION/CONFIRM_ORDER own their labels alone', () => {
    it('renders exactly one "ออกใบเสนอราคา" control (the sticky primary), and clicking it issues the quotation directly', async () => {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({ summary: { lifecycle: 'ACTIVE', salesStage: 'QUOTE_BUYER', createdById: 1 } }),
      });
      api.tickets.actions.mockResolvedValue({
        currentState: { lifecycle: 'ACTIVE', salesStage: 'QUOTE_BUYER', paymentStatus: null, fulfillmentStatus: null, status: 'price_proposed' },
        availableActions: [],
      });
      api.pricingRequests.listForTicket.mockResolvedValue({
        items: [{
          id: 501, requestCode: 'PCR-2026-0501', ticketId: 701, ticketCreatedById: 1,
          status: 'APPROVED_FOR_QUOTATION', recipientType: 'BUYER', recipientLabel: null, orderConfirmedAt: null,
        }],
      });
      api.pricingRequests.listCustomerQuotations.mockResolvedValue({
        items: [{ id: 9101, docStatus: 'DRAFT', quotationRevisionNo: 1, grandTotal: 1000 }],
      });
      api.pricingRequests.issueCustomerQuotation.mockResolvedValue({
        quotation: { id: 9101, docStatus: 'ISSUED', quotationRevisionNo: 1 },
      });

      renderTicketDetailPage(salesOwnerUser);

      expect(await screen.findByText('ถึงคิวคุณ: ออกใบเสนอราคา')).not.toBeNull();
      const stickyButtons = await screen.findAllByRole('button', { name: /ออกใบเสนอราคา/ });
      expect(stickyButtons).toHaveLength(1);

      fireEvent.click(stickyButtons[0]);

      await waitFor(() => expect(api.pricingRequests.issueCustomerQuotation).toHaveBeenCalledWith(
        9101, expect.objectContaining({ clientRequestId: expect.any(String) }),
      ));
      // Still exactly one after the mutation resolves and the panel re-renders.
      expect(screen.getAllByRole('button', { name: /ออกใบเสนอราคา/ })).toHaveLength(1);
    });

    it('renders exactly one "ยืนยันคำสั่งซื้อ" control (the sticky primary), and clicking it confirms the order directly', async () => {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({ summary: { lifecycle: 'ACTIVE', salesStage: 'QUOTE_BUYER', createdById: 1 } }),
      });
      api.tickets.actions.mockResolvedValue({
        currentState: { lifecycle: 'ACTIVE', salesStage: 'QUOTE_BUYER', paymentStatus: null, fulfillmentStatus: null, status: 'price_proposed' },
        availableActions: [],
      });
      api.pricingRequests.listForTicket.mockResolvedValue({
        items: [{
          id: 501, requestCode: 'PCR-2026-0501', ticketId: 701, ticketCreatedById: 1,
          status: 'QUOTATION_ACCEPTED', recipientType: 'BUYER', recipientLabel: null, orderConfirmedAt: null,
        }],
      });
      api.pricingRequests.confirmOrder.mockResolvedValue({});

      renderTicketDetailPage(salesOwnerUser);

      expect(await screen.findByText('ถึงคิวคุณ: ยืนยันคำสั่งซื้อ')).not.toBeNull();
      const stickyButtons = await screen.findAllByRole('button', { name: /ยืนยันคำสั่งซื้อ/ });
      expect(stickyButtons).toHaveLength(1);

      fireEvent.click(stickyButtons[0]);

      await waitFor(() => expect(api.pricingRequests.confirmOrder).toHaveBeenCalledWith(
        501, expect.objectContaining({ clientRequestId: expect.any(String) }),
      ));
      expect(screen.getAllByRole('button', { name: /ยืนยันคำสั่งซื้อ/ })).toHaveLength(1);
    });
  });

  // Handoff 117 follow-up ("A second, real bug found while making the specs
  // actually pass"): the describe block above proves the STEADY-STATE happy
  // path — a draft customer quotation already exists when the sticky
  // "ออกใบเสนอราคา" button is clicked. `nextSalesAction`'s ISSUE_QUOTATION
  // bucket (salesActions.js) fires the instant `pr.status ===
  // 'APPROVED_FOR_QUOTATION'`, which is BEFORE anyone has necessarily created
  // that draft (the rep does so separately via "สร้างร่างใบเสนอราคาลูกค้า" in
  // DealQuotationPanel). Before this fix, clicking the sticky button in that
  // window was a silent no-op — no toast, no mutation, nothing.
  describe('sticky primary "ออกใบเสนอราคา" before any draft quotation exists yet (handoff 117 fix)', () => {
    function approvedForQuotationTicket() {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({ summary: { lifecycle: 'ACTIVE', salesStage: 'QUOTE_BUYER', createdById: 1 } }),
      });
      api.tickets.actions.mockResolvedValue({
        currentState: { lifecycle: 'ACTIVE', salesStage: 'QUOTE_BUYER', paymentStatus: null, fulfillmentStatus: null, status: 'price_proposed' },
        availableActions: [],
      });
      api.pricingRequests.listForTicket.mockResolvedValue({
        items: [{
          id: 501, requestCode: 'PCR-2026-0501', ticketId: 701, ticketCreatedById: 1,
          status: 'APPROVED_FOR_QUOTATION', recipientType: 'BUYER', recipientLabel: null, orderConfirmedAt: null,
        }],
      });
    }

    it('creates the draft AND issues it in one click when no draft exists yet (the regression itself)', async () => {
      approvedForQuotationTicket();
      api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [] });
      api.pricingRequests.createCustomerQuotation.mockResolvedValue({
        quotation: { id: 555, docStatus: 'DRAFT', quotationRevisionNo: 1 },
      });
      api.pricingRequests.issueCustomerQuotation.mockResolvedValue({
        quotation: { id: 555, docStatus: 'ISSUED', quotationRevisionNo: 1 },
      });
      const showToast = vi.fn();

      renderTicketDetailPage(salesOwnerUser, showToast);

      // Wait for the resolver to settle on ISSUE_QUOTATION (it renders
      // CREATE_PCR first, before api.pricingRequests.listForTicket resolves).
      await screen.findByText('ถึงคิวคุณ: ออกใบเสนอราคา');
      const stickyButton = screen.getByTestId('ticket-primary-action');
      expect(stickyButton.getAttribute('data-action')).toBe('issue_quotation');
      // Also wait for DealQuotationPanel's own quotationsQuery to settle
      // empty — this is the exact "quotationsQuery.isSuccess" state
      // openIssueQuotation's create branch requires; without this wait the
      // click could race the query and land on the "still loading" toast
      // branch instead.
      await screen.findByRole('button', { name: 'สร้างร่างใบเสนอราคาลูกค้า' });

      fireEvent.click(stickyButton);

      await waitFor(() => expect(api.pricingRequests.createCustomerQuotation).toHaveBeenCalledWith(
        501, expect.objectContaining({ clientRequestId: expect.any(String) }),
      ));
      // The created draft's id (555), not the PR id, must be what gets issued.
      await waitFor(() => expect(api.pricingRequests.issueCustomerQuotation).toHaveBeenCalledWith(
        555, expect.objectContaining({ clientRequestId: expect.any(String) }),
      ));
      await waitFor(() => expect(showToast).toHaveBeenCalledWith('success', 'สร้างร่างและออกใบเสนอราคาลูกค้าแล้ว'));
    });

    it('issues the existing draft directly, without creating a second one, when a draft already exists (unchanged happy path)', async () => {
      approvedForQuotationTicket();
      api.pricingRequests.listCustomerQuotations.mockResolvedValue({
        items: [{ id: 9101, docStatus: 'DRAFT', quotationRevisionNo: 1, grandTotal: 1000 }],
      });
      api.pricingRequests.issueCustomerQuotation.mockResolvedValue({
        quotation: { id: 9101, docStatus: 'ISSUED', quotationRevisionNo: 1 },
      });

      renderTicketDetailPage(salesOwnerUser);

      // Wait for the "draft ready" hint under DealQuotationPanel — proves the
      // quotations query has actually settled before we click. (The hint is
      // one <p> whose full text also includes the rest of the sentence, so
      // this matches on a substring rather than requiring an exact match.)
      await screen.findByText(/พร้อมออกใบเสนอราคาแล้ว/);
      const stickyButton = screen.getByTestId('ticket-primary-action');
      expect(stickyButton.getAttribute('data-action')).toBe('issue_quotation');

      fireEvent.click(stickyButton);

      await waitFor(() => expect(api.pricingRequests.issueCustomerQuotation).toHaveBeenCalledWith(
        9101, expect.objectContaining({ clientRequestId: expect.any(String) }),
      ));
      expect(api.pricingRequests.createCustomerQuotation).not.toHaveBeenCalled();
    });

    it('shows an error toast and fires neither mutation when the click genuinely cannot do anything (defense in depth)', async () => {
      approvedForQuotationTicket();
      // The only quotation on file is already ISSUED (not editable) — `current`
      // is non-null but isCustomerQuotationEditable(current) is false, so
      // neither the "issue existing draft" nor the "create+issue" branch applies.
      api.pricingRequests.listCustomerQuotations.mockResolvedValue({
        items: [{ id: 9101, docStatus: 'ISSUED', quotationRevisionNo: 1, grandTotal: 1000 }],
      });
      const showToast = vi.fn();

      renderTicketDetailPage(salesOwnerUser, showToast);

      await screen.findByText('ถึงคิวคุณ: ออกใบเสนอราคา');
      const stickyButton = screen.getByTestId('ticket-primary-action');
      // Wait for the quotations query to settle so the click below exercises
      // the "current exists but isn't editable" branch, not the
      // still-loading branch (which would show a different toast). The
      // "บันทึกผลจากลูกค้า" outcome section only renders once the ISSUED
      // quotation has actually landed (canRecordCustomerQuotationOutcome
      // requires docStatus === 'ISSUED'), so waiting for it is a faithful
      // proxy for "the query settled".
      await screen.findByText('บันทึกผลจากลูกค้า');

      fireEvent.click(stickyButton);

      await waitFor(() => expect(showToast).toHaveBeenCalledWith(
        'error', 'ยังออกใบเสนอราคาไม่ได้ — ตรวจสอบสถานะใบขอราคาในส่วน "ราคาและใบเสนอราคา" ด้านล่าง',
      ));
      expect(api.pricingRequests.createCustomerQuotation).not.toHaveBeenCalled();
      expect(api.pricingRequests.issueCustomerQuotation).not.toHaveBeenCalled();
    });
  });

  // P3 (review round 2): the blocker line ("รอชำระมัดจำ" / "รอชำระส่วนที่เหลือ")
  // lost its `!isAccount` guard — account is the role whose OWN action
  // (confirmDeposit/confirmFinalPayment, nextAccountAction) clears each wait,
  // so it must never read as a blocker FOR them too. Uses a legacy
  // (pre-dual-track, status: 'document_issued') ticket so nextAccountAction
  // itself resolves to null (its own gates require status: 'quotation_issued')
  // — isolating the blocker guard from FIX 1's resolver-first primary, which
  // would otherwise mask the same bug by giving account a real primary action
  // instead (see workState.test.js's own account/ORDER_RECEIVED case).
  //
  // The "shows it to someone else" side uses sales_manager, not ceo — ROLE_
  // PERMISSIONS.canConfirmPayments (src/api/routes.js) is `['account', 'ceo']`,
  // so `isAccount` is ALSO true for ceo (they can confirm payments too); using
  // ceo here would have silently exercised the exact same guard as the
  // account case instead of a genuine "someone who isn't account" control.
  describe('blocker line respects !isAccount (P3)', () => {
    function legacyDepositWaitingTicket() {
      return buildTicket({
        summary: {
          lifecycle: 'ACTIVE', salesStage: 'QUOTE_DESIGN_SIDE',
          status: 'document_issued', paymentStatus: 'DEPOSIT_NOTICE_ISSUED', createdById: 1,
        },
      });
    }
    const salesManagerUser = { id: 11, employeeId: 11, name: 'ผจก.ขาย', role: 'sales_manager' };

    it('shows "รอชำระมัดจำ" to a non-account role (sales_manager)', async () => {
      api.tickets.get.mockResolvedValue({ ticket: legacyDepositWaitingTicket() });
      api.tickets.actions.mockResolvedValue({
        currentState: {
          lifecycle: 'ACTIVE', salesStage: 'QUOTE_DESIGN_SIDE', paymentStatus: 'DEPOSIT_NOTICE_ISSUED', fulfillmentStatus: null, status: 'document_issued',
        },
        availableActions: [],
      });

      renderTicketDetailPage(salesManagerUser);

      expect(await screen.findByText(/รอชำระมัดจำ/)).not.toBeNull();
    });

    it('never shows "รอชำระมัดจำ" to account — account is the role that clears it', async () => {
      api.tickets.get.mockResolvedValue({ ticket: legacyDepositWaitingTicket() });
      api.tickets.actions.mockResolvedValue({
        currentState: {
          lifecycle: 'ACTIVE', salesStage: 'QUOTE_DESIGN_SIDE', paymentStatus: 'DEPOSIT_NOTICE_ISSUED', fulfillmentStatus: null, status: 'document_issued',
        },
        availableActions: [],
      });

      renderTicketDetailPage(accountUser);

      await screen.findByRole('heading', { level: 1, name: 'บริษัท ทดสอบ จำกัด' });
      expect(screen.queryByText(/รอชำระมัดจำ/)).toBeNull();
    });
  });

  // Deal tracking (V83, Slice B1/B2 "kill the weekly report" — handoff 103).
  describe('deal tracking panel', () => {
    it('shows the section and the win% default; the pre-emptive gate hint now sits next to the advance button, not here', async () => {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({ summary: { salesStage: 'QUOTE_DESIGN_SIDE' } }),
      });

      renderTicketDetailPage(ceoUser);

      expect(await screen.findByRole('heading', { level: 2, name: 'การติดตามดีล' })).not.toBeNull();
      expect(await screen.findByText('ยังไม่พร้อม')).not.toBeNull();
      // QUOTE_DESIGN_SIDE's stage default (WIN_PROBABILITY_DEFAULTS) — no override set.
      expect(screen.getByText('40%')).not.toBeNull();
      // Ticket-detail IA rebuild Phase 1 (Phase-1 audit finding #3, "y=870"):
      // the descriptive gate sentence moved out of this panel entirely — it
      // now renders next to DealStagePanel's "เลื่อนไป" button instead (see
      // the "stage-advance readiness sits next to the button" describe block
      // below), so it must NOT reappear here alongside the compact badge.
      expect(screen.queryByText(/ต้องระบุวันติดตามครั้งถัดไป/)).toBeNull();
    });

    it('is ready to advance once nextFollowUpAt is set and an activity was logged since the last stage change', async () => {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({ summary: { nextFollowUpAt: '2026-07-15' } }),
      });
      api.tickets.listActivities.mockResolvedValue({
        items: [{
          id: 1, ticketId: 701, activityDate: '2026-07-03', kind: 'CALL', note: 'โทรติดตาม',
          createdById: 9, createdByName: 'CEO ทดสอบ', createdAt: '2026-07-03T09:00:00.000Z',
        }],
      });

      renderTicketDetailPage(ceoUser);

      expect(await screen.findByText('พร้อมเลื่อนสถานะ')).not.toBeNull();
      expect(screen.queryByText(/ต้องระบุวันติดตามครั้งถัดไป/)).toBeNull();
    });

    it('submits a new activity via api.tickets.addActivity', async () => {
      renderTicketDetailPage(ceoUser);
      await screen.findByRole('heading', { level: 2, name: 'การติดตามดีล' });

      fireEvent.change(screen.getByLabelText('บันทึก (ถ้ามี)'), { target: { value: 'โทรคุยเรื่องราคา' } });
      fireEvent.click(screen.getByRole('button', { name: 'บันทึกกิจกรรม' }));

      await waitFor(() => expect(api.tickets.addActivity).toHaveBeenCalledTimes(1));
      expect(api.tickets.addActivity.mock.calls[0][0]).toBe(701);
      expect(api.tickets.addActivity.mock.calls[0][1]).toMatchObject({ kind: 'CALL', note: 'โทรคุยเรื่องราคา' });
    });

    it('account does not see the deal-tracking panel, only a one-line peek', async () => {
      renderTicketDetailPage(accountUser);

      await screen.findByRole('heading', { level: 1, name: 'บริษัท ทดสอบ จำกัด' });
      expect(screen.queryByRole('heading', { level: 2, name: 'การติดตามดีล' })).toBeNull();
      expect(screen.getByText('การติดตามดีล')).not.toBeNull(); // SectionPeek's title span
      expect(api.tickets.listActivities).not.toHaveBeenCalled();
    });
  });

  // Ticket-detail IA rebuild Phase 1 (Phase-1 audit finding #3, "y=870"), then
  // the Phase-1 clutter follow-up (FIX 2): the stage-advance readiness gate
  // used to sit directly beside DealStagePanel's own "เลื่อนไป" button; that
  // button then moved into the header "⋯" overflow menu (a second,
  // filled-indigo "primary" competing with the sticky header's own CTA was
  // exactly the clutter this follow-up exists to remove) — its gate had to
  // travel with it, so it renders present-but-disabled-with-reason in the
  // menu rather than just not being there.
  describe('stage-advance readiness travels with เลื่อนไป into the overflow menu', () => {
    function actionsWithAdvance(overrides = {}) {
      return {
        currentState: {
          lifecycle: 'ACTIVE', salesStage: 'QUOTE_DESIGN_SIDE', paymentStatus: null, fulfillmentStatus: null, status: 'price_proposed',
        },
        availableActions: [{ action: 'ADVANCE_STAGE', targetStage: 'OWNER_SIGNOFF' }],
        ...overrides,
      };
    }

    it('lists เลื่อนไป present-but-disabled with the gate hint readable when not ready', async () => {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({ summary: { salesStage: 'QUOTE_DESIGN_SIDE', nextFollowUpAt: null } }),
      });
      api.tickets.actions.mockResolvedValue(actionsWithAdvance());

      renderTicketDetailPage(ceoUser);

      fireEvent.click(await screen.findByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));
      const advanceItem = await screen.findByTestId('deal-stage-advance');
      expect(advanceItem).not.toBeNull();
      expect(advanceItem.getAttribute('aria-disabled')).toBe('true');
      expect(within(advanceItem).getByText(/ต้องระบุวันติดตามครั้งถัดไป และบันทึกกิจกรรมอย่างน้อย 1 รายการก่อนเลื่อนสถานะ/)).not.toBeNull();

      // Present-but-disabled, not reachable: clicking it must not fire the mutation.
      fireEvent.click(advanceItem);
      expect(api.tickets.updateStage).not.toHaveBeenCalled();
      // Same reason it's a no-op still applies (list, not just its own gate) — see
      // the DealStagePanel-side openAdvance() re-check tests for the pure gate.
    });

    it('lists เลื่อนไป enabled with no disabled reason once ready, and it actually advances the stage', async () => {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({ summary: { salesStage: 'QUOTE_DESIGN_SIDE', nextFollowUpAt: '2026-07-15' } }),
      });
      api.tickets.actions.mockResolvedValue(actionsWithAdvance());
      api.tickets.listActivities.mockResolvedValue({
        items: [{
          id: 1, ticketId: 701, activityDate: '2026-07-03', kind: 'CALL', note: 'โทรติดตาม',
          createdById: 9, createdByName: 'CEO ทดสอบ', createdAt: '2026-07-03T09:00:00.000Z',
        }],
      });
      api.tickets.updateStage.mockResolvedValue({});

      renderTicketDetailPage(ceoUser);

      fireEvent.click(await screen.findByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));
      const advanceItem = await screen.findByTestId('deal-stage-advance');
      expect(advanceItem.getAttribute('aria-disabled')).toBeNull();
      expect(screen.queryByText(/ต้องระบุวันติดตามครั้งถัดไป/)).toBeNull();

      fireEvent.click(advanceItem);
      await waitFor(() => expect(api.tickets.updateStage).toHaveBeenCalledWith(701, { stage: 'OWNER_SIGNOFF' }));
    });

    // FIX 3 (P2, clutter-follow-up review round 2): the old inline "เลื่อนไป"
    // button was `disabled={actionLoading}`, so a mutation already in flight
    // blocked a second click. The overflow item's own `disabled` used to be
    // `!readyToAdvance` ONLY — reopening "⋯" and clicking again while the
    // first updateStage was still pending fired a second POST, the second
    // one landing as a 409 "Deal is already in stage X" red toast
    // (TicketService.java:1143). Proves the fix end-to-end: item disabled
    // while in flight, AND the click is a genuine no-op (not just visually
    // disabled) — exactly one request total.
    it('a second click on เลื่อนไป while the first updateStage is still in flight does not fire a duplicate request', async () => {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({ summary: { salesStage: 'QUOTE_DESIGN_SIDE', nextFollowUpAt: '2026-07-15' } }),
      });
      api.tickets.actions.mockResolvedValue(actionsWithAdvance());
      api.tickets.listActivities.mockResolvedValue({
        items: [{
          id: 1, ticketId: 701, activityDate: '2026-07-03', kind: 'CALL', note: 'โทรติดตาม',
          createdById: 9, createdByName: 'CEO ทดสอบ', createdAt: '2026-07-03T09:00:00.000Z',
        }],
      });
      let resolveUpdateStage;
      api.tickets.updateStage.mockImplementation(() => new Promise((resolve) => { resolveUpdateStage = resolve; }));

      renderTicketDetailPage(ceoUser);

      fireEvent.click(await screen.findByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));
      fireEvent.click(await screen.findByTestId('deal-stage-advance'));
      await waitFor(() => expect(api.tickets.updateStage).toHaveBeenCalledTimes(1));

      // Reopen the menu while the first request is still pending (actionLoading
      // is true) and click "เลื่อนไป" again.
      fireEvent.click(await screen.findByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));
      const advanceItemAgain = await screen.findByTestId('deal-stage-advance');
      expect(advanceItemAgain.getAttribute('aria-disabled')).toBe('true');
      fireEvent.click(advanceItemAgain);

      // Still exactly one request — both the item's own disabled state and
      // openAdvance()'s actionLoading re-check block the second click.
      expect(api.tickets.updateStage).toHaveBeenCalledTimes(1);

      resolveUpdateStage({ ticket: buildTicket() });
    });
  });

  // Ticket-detail IA rebuild Phase 1: the header overflow menu recomputes
  // canEditStage/canHoldDeal/canDormantDeal/canLostDeal itself (it can't
  // read them off DealStagePanel's ref during the parent's own render), so
  // it must mirror DealStagePanel's full gate — including the IMPLICIT
  // "only while lifecycle is plain ACTIVE" the panel enforced via which JSX
  // branch rendered those buttons, not just the bare `hasAction(...)` calls.
  describe('header overflow menu / danger zone (ON_HOLD lifecycle regression)', () => {
    it('does not duplicate "พัก dormant" when the deal is ON_HOLD (DealStagePanel already offers it in its own banner)', async () => {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({ summary: { lifecycle: 'ON_HOLD', salesStage: 'QUOTE_DESIGN_SIDE' } }),
      });
      api.tickets.actions.mockResolvedValue({
        currentState: { lifecycle: 'ON_HOLD', salesStage: 'QUOTE_DESIGN_SIDE', paymentStatus: null, fulfillmentStatus: null, status: 'price_proposed' },
        // Mirrors the real service: an ON_HOLD deal's availableActions only
        // ever include RESUME/MARK_DORMANT (see DealStagePanel's own
        // ON_HOLD banner) — MARK_DORMANT being present here is exactly what
        // makes the header menu's naive `hasAction('MARK_DORMANT')` check
        // insufficient on its own.
        availableActions: [{ action: 'RESUME' }, { action: 'MARK_DORMANT' }],
      });

      renderTicketDetailPage(salesOwnerUser);

      // DealStagePanel's own ON_HOLD banner renders one "พัก dormant" button.
      expect(await screen.findAllByText('พัก dormant')).toHaveLength(1);

      // The header overflow trigger must not even offer a second copy —
      // either it doesn't render at all (no other items available either),
      // or its menu doesn't contain "พัก dormant".
      const trigger = screen.queryByRole('button', { name: 'การดำเนินการเพิ่มเติม' });
      if (trigger) {
        fireEvent.click(trigger);
        expect(screen.queryByRole('menuitem', { name: 'พัก dormant' })).toBeNull();
      }
    });
  });

  // "ราคาและใบเสนอราคา" (Phase 2 Slice S2 — docs/agent-handoffs/104): the
  // customer-quotation tail pulled onto the deal page.
  describe('deal quotation panel', () => {
    const approvedPr = {
      id: 501, requestCode: 'PCR-2026-0501', status: 'APPROVED_FOR_QUOTATION',
      ticketCreatedById: 1, recipientType: 'BUYER', recipientLabel: null,
      orderConfirmedAt: null,
    };

    it('renders nothing when no pricing request has reached APPROVED_FOR_QUOTATION', async () => {
      api.pricingRequests.listForTicket.mockResolvedValue({
        items: [{ ...approvedPr, status: 'COSTING_IN_PROGRESS' }],
      });

      renderTicketDetailPage(salesOwnerUser);

      await screen.findByRole('heading', { level: 1, name: 'บริษัท ทดสอบ จำกัด' });
      expect(screen.queryByRole('heading', { level: 2, name: 'ราคาและใบเสนอราคา' })).toBeNull();
    });

    it('the owning sales rep can create a customer-quotation draft once the PCR is APPROVED_FOR_QUOTATION', async () => {
      api.pricingRequests.listForTicket.mockResolvedValue({ items: [approvedPr] });
      api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [] });
      api.pricingRequests.createCustomerQuotation.mockResolvedValue({
        quotation: { id: 9101, docStatus: 'DRAFT', quotationRevisionNo: 1 },
      });

      renderTicketDetailPage(salesOwnerUser);

      expect(await screen.findByRole('heading', { level: 2, name: 'ราคาและใบเสนอราคา' })).not.toBeNull();
      fireEvent.click(await screen.findByRole('button', { name: 'สร้างร่างใบเสนอราคาลูกค้า' }));

      await waitFor(() => expect(api.pricingRequests.createCustomerQuotation).toHaveBeenCalledWith(
        501, expect.objectContaining({ clientRequestId: expect.any(String) }),
      ));
    });

    it('import (no business in the customer quotation) never sees the section', async () => {
      api.pricingRequests.listForTicket.mockResolvedValue({ items: [approvedPr] });

      renderTicketDetailPage({ id: 7, employeeId: 7, name: 'ฝ่ายนำเข้า', role: 'import' });

      await screen.findByRole('heading', { level: 1, name: 'บริษัท ทดสอบ จำกัด' });
      expect(screen.queryByRole('heading', { level: 2, name: 'ราคาและใบเสนอราคา' })).toBeNull();
    });
  });

  // "มัดจำ" (Phase 3 Slice S3 — docs/agent-handoffs/105): the unified
  // policy → notice → payment section, replacing the deposit-policy control
  // that used to live in DealStagePanel and the deposit doc/payment bits
  // that used to live directly on this page.
  describe('deal deposit panel', () => {
    async function depositSection() {
      const heading = await screen.findByRole('heading', { level: 2, name: 'มัดจำ' });
      return within(heading.closest('section'));
    }

    it('renders the มัดจำ section with the current policy for the default REQUIRED policy', async () => {
      renderTicketDetailPage(accountUser);

      const section = await depositSection();
      expect(section.getByText('นโยบายมัดจำ')).not.toBeNull();
      expect(section.getByText('ต้องเก็บมัดจำ')).not.toBeNull();
      expect(section.getByText('ใบแจ้งยอดมัดจำ')).not.toBeNull();
      expect(section.getByText('รับชำระมัดจำ')).not.toBeNull();
    });

    it('account can change the deposit policy via api.tickets.setDepositPolicy', async () => {
      api.tickets.actions.mockResolvedValueOnce({
        currentState: { lifecycle: 'ACTIVE', salesStage: 'QUOTE_DESIGN_SIDE', paymentStatus: null, fulfillmentStatus: null, status: 'price_proposed' },
        availableActions: [{ action: 'WAIVE_DEPOSIT', kind: 'policy', label: 'นโยบายมัดจำ' }],
      });
      api.tickets.setDepositPolicy.mockResolvedValue({ ticket: buildTicket({ summary: { depositPolicy: 'WAIVED', depositPolicyReason: 'ลูกค้าประจำ' } }) });

      renderTicketDetailPage(accountUser);
      const section = await depositSection();
      fireEvent.click(await section.findByRole('button', { name: 'เปลี่ยนนโยบายมัดจำ…' }));

      fireEvent.change(screen.getByLabelText('เหตุผล *'), { target: { value: 'ลูกค้าประจำ' } });
      fireEvent.click(screen.getByRole('button', { name: 'บันทึก' }));

      await waitFor(() => expect(api.tickets.setDepositPolicy).toHaveBeenCalledWith(
        701, { policy: 'WAIVED', reason: 'ลูกค้าประจำ' },
      ));
    });

    it('a waived deposit policy renders the notice/payment steps as skipped, with the reason', async () => {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({ summary: { depositPolicy: 'WAIVED', depositPolicyReason: 'ลูกค้าประจำตามข้อตกลง' } }),
      });

      renderTicketDetailPage(accountUser);
      const section = await depositSection();
      await section.findByText('ยกเว้นมัดจำ');

      expect(section.getAllByText(/ข้ามขั้นตอนนี้/).length).toBe(2);
      expect(section.getAllByText(/ลูกค้าประจำตามข้อตกลง/).length).toBeGreaterThan(0);
      expect(section.queryByRole('button', { name: 'สร้างใบแจ้งยอดเงินรับมัดจำ' })).toBeNull();
      expect(section.queryByRole('button', { name: 'ยืนยันรับมัดจำ' })).toBeNull();
    });

    it('account confirms deposit paid via api.tickets.confirmDepositPaid once the notice is issued', async () => {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({ summary: { status: 'quotation_issued', paymentStatus: 'DEPOSIT_NOTICE_ISSUED' } }),
      });
      api.tickets.actions.mockResolvedValue({
        currentState: { lifecycle: 'ACTIVE', salesStage: 'DEPOSIT_RECEIVED', paymentStatus: 'DEPOSIT_NOTICE_ISSUED', fulfillmentStatus: null, status: 'quotation_issued' },
        availableActions: [{ action: 'DEPOSIT_PAID', kind: 'payment', label: 'รับมัดจำ' }],
      });
      api.tickets.confirmDepositPaid.mockResolvedValue({ ticket: buildTicket({ summary: { status: 'quotation_issued', paymentStatus: 'DEPOSIT_PAID' } }) });

      renderTicketDetailPage(accountUser);
      const section = await depositSection();
      fireEvent.click(await section.findByRole('button', { name: 'ยืนยันรับมัดจำ' }));

      await waitFor(() => expect(api.tickets.confirmDepositPaid).toHaveBeenCalledWith(701));
    });

    it('import (no business in the deposit section) never sees it, only a one-line peek', async () => {
      renderTicketDetailPage({ id: 7, employeeId: 7, name: 'ฝ่ายนำเข้า', role: 'import' });

      await screen.findByRole('heading', { level: 1, name: 'บริษัท ทดสอบ จำกัด' });
      expect(screen.queryByRole('heading', { level: 2, name: 'มัดจำ' })).toBeNull();
      expect(screen.getByText('มัดจำ')).not.toBeNull(); // SectionPeek's title span
      expect(api.depositNotices.listByTicket).not.toHaveBeenCalled();
    });
  });

  // "การส่งมอบ / นำเข้า" (Phase 3 Slice S4 — docs/agent-handoffs/105): the
  // deal-level IR/shipping/goods-received/delivery chain + optional
  // per-factory PO detail, replacing the "การส่งมอบสินค้า" panel and the
  // docActions IR button/delivery/stock modals that used to live directly
  // on this page.
  describe('deal fulfilment panel', () => {
    const importUser = { id: 7, employeeId: 7, name: 'ฝ่ายนำเข้า', role: 'import' };

    async function fulfilmentSection() {
      const heading = await screen.findByRole('heading', { level: 2, name: 'การส่งมอบ / นำเข้า' });
      return within(heading.closest('section'));
    }

    it('import issues an Import Request via api.tickets.issueImportRequest', async () => {
      api.tickets.get.mockResolvedValueOnce({
        ticket: buildTicket({ summary: { status: 'quotation_issued', paymentStatus: 'DEPOSIT_PAID' } }),
      });
      api.tickets.actions.mockResolvedValueOnce({
        currentState: { lifecycle: 'ACTIVE', salesStage: 'DEPOSIT_RECEIVED', paymentStatus: 'DEPOSIT_PAID', fulfillmentStatus: null, status: 'quotation_issued' },
        availableActions: [{ action: 'ISSUE_IMPORT_REQUEST', kind: 'fulfillment', label: 'ออก IR' }],
      });
      api.tickets.issueImportRequest.mockResolvedValue({
        ticket: buildTicket({ summary: { status: 'quotation_issued', fulfillmentStatus: 'IR_ISSUED' } }),
      });

      renderTicketDetailPage(importUser);
      const section = await fulfilmentSection();
      fireEvent.click(await section.findByRole('button', { name: 'ออก Import Request (IR)' }));

      await waitFor(() => expect(api.tickets.issueImportRequest).toHaveBeenCalledWith(701));
    });

    it('account sees the section read-only — no fulfilment action buttons, no factory-PO detail', async () => {
      api.tickets.get.mockResolvedValueOnce({
        ticket: buildTicket({ summary: { status: 'quotation_issued', fulfillmentStatus: 'SHIPPING' } }),
      });
      api.tickets.actions.mockResolvedValueOnce({
        currentState: { lifecycle: 'ACTIVE', salesStage: 'PROCUREMENT', paymentStatus: 'FULLY_PAID', fulfillmentStatus: 'SHIPPING', status: 'quotation_issued' },
        // A deliberately unrealistic payload (mirrors the "retired verbs" test
        // above) proving the role gate, not just the absence of the action.
        availableActions: [
          { action: 'ISSUE_IMPORT_REQUEST', kind: 'fulfillment', label: 'ออก IR' },
          { action: 'SHIPPING', kind: 'fulfillment', label: 'สินค้าเดินทาง' },
          { action: 'RESERVE_STOCK', kind: 'fulfillment', label: 'จองสต็อก' },
          { action: 'RECORD_PARTIAL_DELIVERY', kind: 'fulfillment', label: 'บันทึกส่งมอบ' },
          { action: 'COMPLETE_DELIVERY', kind: 'fulfillment', label: 'ส่งมอบครบ' },
        ],
      });

      renderTicketDetailPage(accountUser);
      const section = await fulfilmentSection();

      expect(section.queryByRole('button', { name: 'สินค้าออกเดินทาง (Shipping)' })).toBeNull();
      expect(section.queryByRole('button', { name: 'จองสินค้าจากสต็อก' })).toBeNull();
      expect(section.queryByRole('button', { name: 'บันทึกการส่งสินค้า' })).toBeNull();
      expect(section.queryByRole('button', { name: 'ส่งมอบครบ' })).toBeNull();
      expect(section.queryByText('ใบสั่งซื้อโรงงาน (Factory PO)')).toBeNull();
      expect(api.procurement.listForPricingRequest).not.toHaveBeenCalled();
    });

    it('import sees an empty factory-PO state (production has none yet) once the deal has an order-confirmed pricing request', async () => {
      api.pricingRequests.listForTicket.mockResolvedValue({
        items: [{ id: 601, requestCode: 'PCR-2026-0601', status: 'QUOTATION_ACCEPTED', recipientType: 'OWNER' }],
      });

      renderTicketDetailPage(importUser);
      const section = await fulfilmentSection();

      expect(await section.findByText('ใบสั่งซื้อโรงงาน (Factory PO)')).not.toBeNull();
      expect(await section.findByText('ยังไม่มีใบสั่งซื้อโรงงาน')).not.toBeNull();
      await waitFor(() => expect(api.procurement.listForPricingRequest).toHaveBeenCalledWith(601));
    });

    it('import sees each factory PO once created, with a link to its detail page', async () => {
      api.pricingRequests.listForTicket.mockResolvedValue({
        items: [{ id: 601, requestCode: 'PCR-2026-0601', status: 'QUOTATION_ACCEPTED', recipientType: 'OWNER' }],
      });
      api.procurement.listForPricingRequest.mockResolvedValue({
        factoryPurchaseOrders: [{
          id: 3001, poNumber: 'FPO-2026-0001', factoryName: 'SCG Ceramics', status: 'OPEN',
          totalAmount: 50000, currency: 'THB', supplierProformaRef: null,
          containerRef: null, etd: null, eta: null, actualLandedCostThb: null,
        }],
      });

      renderTicketDetailPage(importUser);
      const section = await fulfilmentSection();

      expect(await section.findByText('FPO-2026-0001')).not.toBeNull();
      const link = section.getByRole('link', { name: /รายละเอียด/ });
      expect(link.getAttribute('href')).toBe('/factory-purchase-orders/3001');
    });
  });
});
