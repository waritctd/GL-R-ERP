import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import {
  MemoryRouter, useLocation,
} from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TicketDetailPage } from './TicketDetailPage.jsx';
import { api } from '../../api/index.js';
import { documentChainFromMocks } from '../../test/documentChainFromMocks.js';
import { DEAL_STAGE_CATALOG } from '../../data/dealStageCatalog.js';
import * as quotationMeta from '../quotations/quotationMeta.js';

globalThis.React = React;

// quotationMeta.canReviseDealQuotation is wrapped in a spy so the "แก้ใบเสนอราคา" tests can check the
// page consults it and can make it refuse; the default implementation is the real one.
vi.mock('../quotations/quotationMeta.js', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, canReviseDealQuotation: vi.fn(actual.canReviseDealQuotation) };
});

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  // Imported inside the factory, not at the top of the file: vi.mock is hoisted above every import.
  const { DEAL_STAGE_CATALOG } = await import('../../data/dealStageCatalog.js');
  return {
    ...actual,
    api: {
      // The pipeline enumeration (GET /api/meta/deal-stages). Served here as the SAME canned payload mockApi serves
      // — data/dealStageCatalog.js, which stageCatalog.test.js pins against DealStage.java — so this fixture cannot
      // quietly describe a pipeline the backend does not have.
      meta: {
        dealStages: vi.fn().mockResolvedValue(DEAL_STAGE_CATALOG),
      },
      tickets: {
        get: vi.fn(),
        listPayments: vi.fn(),
        recordPayment: vi.fn(),
        listDeliveries: vi.fn(),
        reserveStock: vi.fn(),
        // Default: the deal's source is already decided, so tests that predate the stage-10 dialog keep their IR button.
        getProcurementSource: vi.fn().mockResolvedValue({ legacy: false, editable: false, decidedAt: '2026-11-10T00:00:00Z', quotationNumber: null, lines: [] }),
        recordDelivery: vi.fn(),
        completeDelivery: vi.fn(),
        actions: vi.fn(),
        comment: vi.fn(),
        confirmFinalPayment: vi.fn(),
        verifyClose: vi.fn(),
        // เลื่อนไป lives in the header overflow menu, whose onSelect calls this via DealStagePanel's onUpdateStage prop.
        updateStage: vi.fn(),
        // แก้ช่องทางดีล (EntryChannelFix): the remedy for the route gate's "— แก้ช่องทางดีลก่อน" refusal.
        setEntryChannel: vi.fn(),
        revision: vi.fn(),
        editItems: vi.fn(),
        // Deal tracking.
        listActivities: vi.fn(),
        addActivity: vi.fn(),
        updateTracking: vi.fn(),
        // Deal parties (deal-party-model.md): the ผู้รับเหมาที่เสนอราคา panel and บันทึกผลประมูล.
        parties: vi.fn(),
        orders: vi.fn(),
        // สั่งเพิ่ม: opens an add-on order (POST /api/tickets/{id}/add-on-orders).
        createAddOnOrder: vi.fn(),
        // The revoke-close-confirm control lives in the "การเงิน" tab, outside the money section.
        revokeCloseConfirmation: vi.fn(),
      },
      deliveryRequests: { list: vi.fn() },
      attachments: {
        list: vi.fn(),
        upload: vi.fn(),
        delete: vi.fn(),
        fileUrl: (id) => `#mock-file-${id}`,
      },
      // PricingRequestPanel (below the items table), DealStagePanel's substep strip, and
      // DealStateHeader's PCR chip all read this list. DealQuotationPanel reads the customer-quotation
      // tail for whichever request reaches APPROVED_FOR_QUOTATION+.
      pricingRequests: {
        listForTicket: vi.fn(),
        listCustomerQuotations: vi.fn(),
        recordCustomerQuotationOutcome: vi.fn(),
        createDepositNoticeFromQuotation: vi.fn(),
      },
      // PricingRequestCreateModal's header-terms section fetches the same eligible-display-name list
      // the direct-deal quotation editor uses.
      dealQuotations: {
        displayNameOptions: vi.fn().mockResolvedValue({ items: [] }),
        // DealDirectQuotationPanel's list, and the APPROVED direct quotation's ยืนยันคำสั่งซื้อ from the
        // sticky CTA. listForTicket is only given a resolved value by the direct-quotation tests.
        listForTicket: vi.fn(),
        promoteToDeal: vi.fn(),
        // The sticky "เสนอใบราคา<party>" CTA writes the new-engine quotation from the approved PR.
        createFromPricingRequest: vi.fn(),
        // The sticky "แก้ใบเสนอราคา" CTA mints the revision.
        createRevision: vi.fn(),
      },
      // Deposit: DealDepositPanel reads/writes this namespace directly, like pricingRequests above.
      depositNotices: {
        listByTicket: vi.fn(),
        createDraft: vi.fn(),
      },
      // Defaults to "no live document yet", matching every fixture.
      storedRemainingInvoices: {
        listForTicket: vi.fn().mockResolvedValue({ remainingInvoices: [] }),
      },
      // The items table converts a foreign-currency factory price to baht. Mocked because an unmocked namespace
      // makes every fxRates.list() call throw (api.fxRates is undefined), which would silently leave the baht line
      // absent rather than exercising it.
      fxRates: {
        list: vi.fn(),
      },
      // The document chain summary (2026-11-17): the เงิน tab's step states. beforeEach answers it from the
      // ticket / notice / invoice fixtures (documentChainFromMocks), the way the server computes it.
      documentChain: {
        get: vi.fn(),
      },
      // ใบขอซื้อ (F-SM-001). Mocked like fxRates: DealFulfilmentPanel's IR block queries brands for
      // every import/CEO viewer, and an undefined namespace would throw rather than render the block.
      importRequests: {
        brands: vi.fn(),
        pages: vi.fn(),
      },
      // The STORED ใบขอซื้อรายโรงงาน section DealFulfilmentPanel queries for every canReadStoredIr
      // viewer (sales owner/CEO/import/sales_manager); like `importRequests` above, an undefined
      // namespace throws a TypeError, which the panel surfaces as a genuine error. Defaults to an
      // empty list (the "not IR-tracked" case every legacy-chain test assumes); tests override with
      // mockResolvedValueOnce where the per-factory aggregate is under test.
      storedImportRequests: {
        listForTicket: vi.fn().mockResolvedValue({ importRequests: [] }),
        createDrafts: vi.fn(),
      },
      priceImport: {
        countries: vi.fn().mockResolvedValue([]),
      },
      catalog: {
        prices: vi.fn(),
      },
    },
  };
});

const ceoUser = { id: 9, employeeId: 9, name: 'CEO ทดสอบ', role: 'ceo' };
// Quotation issuing (can.generateQuotation) requires role 'sales' AND isOwner (user.id ===
// summary.createdById) — buildTicket()'s default summary.createdById is 1, so this user matches it out of the
// box.
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
    // Read-only fallback-to-quotation-items flag (TicketDto#fromPricingChain). Defaults to false/absent.
    fromPricingChain: overrides.fromPricingChain ?? false,
  };
}

// The viewer api.documentChain.get answers for (its "may act" flags follow the Java gates for this user).
let chainViewer = null;

function renderTicketDetailPage(user = ceoUser, showToast = vi.fn()) {
  chainViewer = user;
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  const utils = render(
    <QueryClientProvider client={queryClient}>
      {/* DealQuotationPanel links out to /pricing-requests/:id and uses useNavigate: needs Router
          context. */}
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

// Every section lives inside a tab (Tabs.jsx keeps only the active one mounted; see
// TicketDetailPage.jsx's "runOnTab" doc comment). `namePattern` matches the tab BUTTON's accessible
// name, which concatenates the Thai label and its English helper span with no whitespace in jsdom,
// so a regex on the Thai label alone (e.g. `/ราคา/`) is enough.
async function openTab(namePattern) {
  fireEvent.click(await screen.findByRole('tab', { name: namePattern }));
}

// Exposes the router location's search string as text, so a test can assert `?tab=` was written
// (and with `replace: true`) without reaching into react-router's internals.
function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location-probe">{location.pathname}{location.search}</div>;
}

function renderTicketDetailPageAtRoute(initialEntries, user = ceoUser, showToast = vi.fn()) {
  chainViewer = user;
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={initialEntries}>
        <LocationProbe />
        <TicketDetailPage user={user} ticketId={701} onBack={vi.fn()} showToast={showToast} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { ...utils, queryClient };
}

// The lists the sticky bar reads (the deal's pricing requests and its quotations) have answered,
// plus a beat for the render that follows — for assertions about what is NOT offered.
async function stickyInputsSettled() {
  await waitFor(() => {
    expect(api.pricingRequests.listForTicket).toHaveBeenCalled();
    expect(api.dealQuotations.listForTicket).toHaveBeenCalled();
  });
  await act(async () => { await new Promise((resolve) => { setTimeout(resolve, 50); }); });
}

const NO_ROUNDS = { order: { orderNo: 1, parentCode: null }, approvedQuotations: [], rounds: [], canStart: false };

describe('TicketDetailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Default: no brands. Tests that care about the ใบขอซื้อ block set their own — a default with brands would
    // make the block appear in unrelated assertions.
    api.importRequests.brands.mockResolvedValue({ brands: [] });
    api.importRequests.pages.mockResolvedValue({ pageCount: 1 });
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
      // GENERATE_QUOTATION/MARK_QUOTATION_* are retired; the real actions() endpoint no longer
      // advertises them either.
      availableActions: [],
    });
    api.attachments.list.mockResolvedValue({ attachments: [] });
    api.deliveryRequests.list.mockResolvedValue(NO_ROUNDS);
    api.attachments.upload.mockResolvedValue({ attachment: { id: 9, fileName: 'po.pdf' } });
    api.attachments.delete.mockResolvedValue({});
    api.tickets.listPayments.mockResolvedValue({ items: [] });
    api.tickets.listDeliveries.mockResolvedValue({ items: [] });
    api.tickets.comment.mockResolvedValue({ ticket: buildTicket() });
    // recordPayment/confirmFinalPayment/confirmCloseReady are re-pointed at the finance routes and return the
    // finance view `{ deal }`, NOT `{ ticket }` (see hrApi.js `tickets.recordPayment`).
    api.tickets.recordPayment.mockResolvedValue({ deal: { id: 701 } });
    api.tickets.reserveStock.mockResolvedValue({ ticket: buildTicket() });
    api.tickets.recordDelivery.mockResolvedValue({ ticket: buildTicket() });
    api.tickets.completeDelivery.mockResolvedValue({ ticket: buildTicket() });
    api.tickets.revision.mockResolvedValue({ ticket: buildTicket() });
    api.tickets.editItems.mockResolvedValue({ ticket: buildTicket() });
    // Default: no catalog matches. Tests that exercise the edit-items brand/model autocomplete set their own.
    api.catalog.prices.mockResolvedValue({ items: [] });
    api.pricingRequests.listForTicket.mockResolvedValue({ items: [] });
    api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [] });
    api.tickets.listActivities.mockResolvedValue({ items: [] });
    api.tickets.addActivity.mockResolvedValue({
      id: 1, ticketId: 701, activityDate: '2026-07-10', kind: 'CALL', note: null,
      createdById: 1, createdByName: 'สมชาย ใจดี', createdAt: '2026-07-10T09:00:00.000Z',
    });
    api.tickets.updateTracking.mockResolvedValue({ ticket: buildTicket() });
    api.tickets.parties.mockResolvedValue({ parties: [] });
    api.tickets.orders.mockResolvedValue({ rootTicketId: 701, rootCode: 'PR-2026-0701', orders: [], totals: null });
    // DealDepositPanel mounts for every role that isn't 'import' (sections.depositNotice), so its list
    // query needs a default resolve or every test above would reject.
    api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [] });
    api.documentChain.get.mockImplementation((id) => documentChainFromMocks(api, id, chainViewer));
    // FX rates back the baht companion beside a foreign-currency factory price; an empty table means no conversion.
    api.fxRates.list.mockResolvedValue({ fxRates: [] });
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
    await openTab(/^เงิน/);

    expect((await screen.findAllByText('ชำระบางส่วน')).length).toBeGreaterThan(0);
    // The billing control left this page: even with SET_BILLING offered there is no button for it.
    expect(screen.queryByRole('button', { name: 'ตั้งค่าการวางบิล' })).toBeNull();
    expect(screen.getAllByText('เกินกำหนด').length).toBeGreaterThan(0);
    // DealStateHeader's "มูลค่าดีล" chip also renders amountPayable — assert presence, not uniqueness.
    expect(screen.getAllByText('฿1,000.00').length).toBeGreaterThan(0);
    // Scoped to the "รับแล้ว"/"คงค้าง" KPI tiles specifically (were the ชำระแล้ว / คงเหลือ tiles before the
    // redesign): by the time this async assertion runs, the receipt-history row has settled in too. A bare
    // `getByText('฿400.00')` is therefore genuinely ambiguous on this page, not a test bug to paper over.
    const kpis = await screen.findByTestId('money-kpis');
    const paidTile = within(kpis).getByText('รับแล้ว').closest('[data-testid="money-kpi"]');
    expect(within(paidTile).getByText('฿400.00')).not.toBeNull();
    const outstandingTile = within(kpis).getByText('คงค้าง').closest('[data-testid="money-kpi"]');
    expect(within(outstandingTile).getByText('฿600.00')).not.toBeNull();
    expect((await screen.findByText(/คุณบัญชี/)).closest('li').textContent).toContain('มัดจำ');
    // The receipt row itself really did render (not just the tile) — proves the scoped assertion above didn't
    // accidentally start passing vacuously.
    expect(screen.getByText(/คุณบัญชี/)).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'บันทึกรับชำระเงิน' })).toBeNull();
  });

  // The revoke-close-confirm control lives in the การเงิน tab, outside the money section (see
  // TicketDetailPage.jsx's comment above the `can.revokeCloseConfirm` section).
  it('renders "ยกเลิกการยืนยันปิดงาน" for account with REVOKE_CLOSE_CONFIRM, and calls revokeCloseConfirmation on click', async () => {
    api.tickets.actions.mockResolvedValueOnce({
      currentState: {
        lifecycle: 'ACTIVE', salesStage: 'CLOSED_PAID', paymentStatus: 'FULLY_PAID', fulfillmentStatus: 'FULLY_DELIVERED', status: 'quotation_issued',
      },
      availableActions: [{ action: 'REVOKE_CLOSE_CONFIRM', kind: 'operational', label: 'ยกเลิกการยืนยันปิดงาน' }],
    });
    api.tickets.revokeCloseConfirmation.mockResolvedValue({ ticket: buildTicket() });

    renderTicketDetailPage(accountUser);
    await openTab(/^เงิน/);

    const button = await screen.findByRole('button', { name: 'ยกเลิกการยืนยันปิดงาน' });
    fireEvent.click(button);

    await waitFor(() => expect(api.tickets.revokeCloseConfirmation).toHaveBeenCalledWith(701, {}));
  });

  // Wrong-way-round: without the REVOKE_CLOSE_CONFIRM action on offer, the button must not render,
  // even for a role (account) that CAN see it once the action is available.
  it('wrong-way-round: hides "ยกเลิกการยืนยันปิดงาน" for account when REVOKE_CLOSE_CONFIRM is not an available action', async () => {
    api.tickets.actions.mockResolvedValueOnce({
      currentState: {
        lifecycle: 'ACTIVE', salesStage: 'DEPOSIT_RECEIVED', paymentStatus: 'DEPOSIT_PAID', fulfillmentStatus: null, status: 'quotation_issued',
      },
      availableActions: [],
    });

    renderTicketDetailPage(accountUser);
    await openTab(/^เงิน/);

    // The tab really rendered (was the "การชำระเงิน" panel heading; the redesign made it KPI tiles + two cards).
    await screen.findByTestId('money-kpis');
    expect(screen.queryByRole('button', { name: 'ยกเลิกการยืนยันปิดงาน' })).toBeNull();
    expect(api.tickets.revokeCloseConfirmation).not.toHaveBeenCalled();
  });

  // Final payment is account-only (PAYMENT_RECORD_ROLES has no CEO fallback), but
  // renderTicketDetailPage()'s default actor is ceoUser: render as accountUser so this test reaches
  // the button.
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
    // Finance route response shape: `{ deal }`. The page must refetch the ticket, not crash on
    // `applyTicketUpdate(undefined)` (react-query rejects undefined query data).
    api.tickets.confirmFinalPayment.mockResolvedValue({ deal: { id: 701 } });

    renderTicketDetailPage(accountUser);

    const finalPaymentButton = await screen.findByRole('button', { name: 'ยืนยันชำระครบ (Final Payment)' });
    fireEvent.click(finalPaymentButton);

    // The single click must NOT call the mutation directly: it only opens the confirm dialog.
    expect(api.tickets.confirmFinalPayment).not.toHaveBeenCalled();

    // The dialog states the real outstanding amount, sourced from the same summary.amountOutstanding the payment
    // panel's "คงเหลือ" tile renders (not a separately-computed figure).
    expect(await screen.findByText('ยืนยันการรับชำระครบถ้วน')).not.toBeNull();
    expect(screen.getAllByText('฿32,500.00').length).toBeGreaterThan(0);

    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันชำระครบ' }));

    await waitFor(() => expect(api.tickets.confirmFinalPayment).toHaveBeenCalledWith(701));
    // Dialog closes after a successful confirm.
    await waitFor(() => expect(screen.queryByText('ยืนยันการรับชำระครบถ้วน')).toBeNull());
    // A `{ deal }` response carries no ticket: the page re-reads the ticket instead of writing `undefined` into
    // the detail cache (which would blank the page).
    await waitFor(() => expect(api.tickets.get.mock.calls.length).toBeGreaterThanOrEqual(2));
    expect(screen.getAllByText(/PR-2026-0701/).length).toBeGreaterThan(0);
  });

  describe('sticky header primary CTA — verify-close', () => {
    // The CEO's close is irreversible, so the click only opens a confirm; verifyClose runs once, on
    // confirm. (The account's ticket-detail-confirm-close stays one click, not covered here.)
    describe('W4-10 CEO ตรวจสอบและปิดงาน asks first', () => {
      async function renderVerifyClose() {
        api.tickets.get.mockResolvedValue({
          ticket: buildTicket({
            summary: {
              lifecycle: 'ACTIVE', salesStage: 'DELIVERED', status: 'quotation_issued',
              closeConfirmedAt: '2026-07-20T09:00:00.000Z',
            },
          }),
        });
        api.tickets.actions.mockResolvedValue({
          currentState: { lifecycle: 'ACTIVE', salesStage: 'DELIVERED', paymentStatus: 'FULLY_PAID', fulfillmentStatus: 'FULLY_DELIVERED', status: 'quotation_issued' },
          availableActions: [{ action: 'VERIFY_CLOSE' }],
        });
        api.tickets.verifyClose.mockResolvedValue({ ticket: buildTicket() });
        renderTicketDetailPage(ceoUser);
        fireEvent.click(await screen.findByTestId('ticket-detail-verify-close'));
        return screen.findByRole('dialog', { name: 'ยืนยันตรวจสอบและปิดงาน' });
      }

      it('click opens a dialog warning it cannot be undone and calls verifyClose only on confirm, once', async () => {
        const dialog = await renderVerifyClose();
        expect(dialog.textContent).toContain('ย้อนกลับไม่ได้');
        expect(api.tickets.verifyClose).not.toHaveBeenCalled();

        const confirm = within(dialog).getByRole('button', { name: 'ตรวจสอบและปิดงาน' });
        fireEvent.click(confirm);
        fireEvent.click(confirm);

        await waitFor(() => expect(api.tickets.verifyClose).toHaveBeenCalledTimes(1));
        expect(api.tickets.verifyClose).toHaveBeenCalledWith(701);
      });

      it('cancel closes the dialog and never calls verifyClose', async () => {
        const dialog = await renderVerifyClose();
        fireEvent.click(within(dialog).getByRole('button', { name: 'ยกเลิก' }));

        await waitFor(() => expect(screen.queryByRole('dialog', { name: 'ยืนยันตรวจสอบและปิดงาน' })).toBeNull());
        expect(api.tickets.verifyClose).not.toHaveBeenCalled();
      });
    });
  });

  // ISSUE_QUOTATION ("ออกใบเสนอราคา") and CONFIRM_ORDER ("ยืนยันคำสั่งซื้อ") each render ONCE: the
  // sticky bar performs the mutation and DealQuotationPanel has no button of its own (both would be
  // visible at once, since the sticky bar never scrolls away).
  //
  // "เสนอใบราคา<party>" reads the deal's quotation list: a DRAFT already written is opened, one
  // awaiting approval is a waiting line; it refreshes the deal after writing, never pulls a rep who
  // already left, and a refusal opens the request page (where both engines' controls are).
  describe('sticky header primary CTA — ISSUE_QUOTATION/CONFIRM_ORDER own their labels alone', () => {
    // Later describes in this file read listForTicket bare (a rejected query) — keep it that way.
    afterEach(() => {
      api.dealQuotations.listForTicket.mockReset();
      api.dealQuotations.createFromPricingRequest.mockReset();
    });

    // The dialog names the quotation the panel SHOWS. A new-engine deal has no old-engine row, so it
    // must not read "ใบเสนอราคา " + a blank.
    describe('VW-04 the confirm-order dialog names the quotation the panel shows', () => {
      async function openConfirmOrderDialog({ oldEngineRows = [], depositPercent } = {}) {
        api.tickets.get.mockResolvedValue({
          ticket: buildTicket({ summary: { lifecycle: 'ACTIVE', salesStage: 'QUOTE_BUYER', createdById: 1, status: 'draft' } }),
        });
        api.tickets.actions.mockResolvedValue({
          currentState: { lifecycle: 'ACTIVE', salesStage: 'QUOTE_BUYER', paymentStatus: null, fulfillmentStatus: null, status: 'draft' },
          availableActions: [],
        });
        api.pricingRequests.listForTicket.mockResolvedValue({
          items: [{
            id: 501, requestCode: 'PCR-2026-0501', ticketId: 701, ticketCreatedById: 1,
            status: 'QUOTATION_ACCEPTED', recipientType: 'BUYER', recipientLabel: null, orderConfirmedAt: null,
          }],
        });
        api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: oldEngineRows });
        api.dealQuotations.listForTicket.mockResolvedValue({
          items: [{
            id: 9501, number: 'QT-2026-0501-NEW', origin: 'PRICING_REQUEST', pricingRequestId: 501, docStatus: 'ACCEPTED',
            recipientType: 'BUYER', salesRepId: 1, revisionNo: 1, parentQuotationId: null, grandTotal: 1070,
            ...(depositPercent === undefined ? {} : { depositPercent }),
          }],
        });
        renderTicketDetailPage(salesOwnerUser);
        // Wait until the panel itself shows the quotation it will name, so the click cannot race the loads.
        await openTab(/ใบเสนอราคา/);
        const panel = within(await screen.findByTestId('deal-quotation-panel'));
        expect((await panel.findAllByText(oldEngineRows[0]?.number ?? 'QT-2026-0501-NEW')).length).toBeGreaterThan(0);
        const sticky = await screen.findAllByRole('button', { name: /ยืนยันคำสั่งซื้อ/ });
        fireEvent.click(sticky[0]);
        return screen.findByRole('dialog', { name: 'ยืนยันคำสั่งซื้อ' });
      }

      // The tail follows the quotation's deposit %. 0 -> no deposit wording at all.
      it('a quotation with no deposit (0%): the dialog says ไม่เก็บมัดจำ and never mentions ใบแจ้งรับมัดจำ', async () => {
        const dialog = await openConfirmOrderDialog({ depositPercent: 0 });
        expect(dialog.textContent).toContain('ไม่เก็บมัดจำ');
        expect(dialog.textContent).not.toContain('ใบแจ้งรับมัดจำ');
      });
    });
  });

  // The rep picks the next party when the designer's or owner's quotation is SENT. The primary is ONE
  // "เสนอราคา<next party>": a menu when two parties remain (the later one reads
  // "ข้ามไปเสนอราคา<party>"), a plain button for one, beside a line naming the sent quotation, with
  // บันทึกผลใบเสนอราคา for it in the ⋯ menu while it is ISSUED. Picking a party opens the price-request
  // form with ผู้รับ pre-filled. The parties come from the deal's served route (catalog.routes for its
  // entryChannel). An ACCEPTED quotation is the order, whoever it went to: ยืนยันคำสั่งซื้อ.
  describe('sticky header — once a designer/owner quotation is sent: เสนอราคา<next party> (owner ruling 2026-10-27)', () => {
    const sentRequest = (overrides = {}) => ({
      id: 501, requestCode: 'PCR-2026-0501', ticketId: 701, ticketCreatedById: 1,
      status: 'QUOTATION_ISSUED', recipientType: 'DESIGNER', recipientLabel: null, orderConfirmedAt: null, ...overrides,
    });
    // The request's quotation as the deal's quotation list serves it (DealQuotationDto).
    const requestQuotation = (overrides = {}) => ({
      id: 9101, number: 'QT-2026-0101-1', origin: 'PRICING_REQUEST', pricingRequestId: 501, docStatus: 'ISSUED',
      recipientType: 'DESIGNER', salesRepId: 1, revisionNo: 1, parentQuotationId: null, grandTotal: 1070, ...overrides,
    });

    // `actionsAnswered: false` leaves GET /actions pending, so no stageDecisions ever arrive.
    function deal({
      requests = [sentRequest()], quotations = [requestQuotation()], entryChannel = 'DESIGNER_LED', actionsAnswered = true,
      stage = 'QUOTE_DESIGN_SIDE',
    } = {}) {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({
          summary: {
            lifecycle: 'ACTIVE', salesStage: stage, createdById: 1, status: 'draft', entryChannel,
            designerName: 'สมชาย ผู้ออกแบบ', ownerName: 'บริษัท เจ้าของ จำกัด', buyerName: 'ผู้รับเหมา ข.',
          },
        }),
      });
      if (actionsAnswered) {
        api.tickets.actions.mockResolvedValue({
          currentState: { lifecycle: 'ACTIVE', salesStage: stage, paymentStatus: null, fulfillmentStatus: null, status: 'draft' },
          availableActions: [],
        });
      } else {
        api.tickets.actions.mockReturnValue(new Promise(() => {}));
      }
      api.pricingRequests.listForTicket.mockResolvedValue({ items: requests });
      api.dealQuotations.listForTicket.mockResolvedValue({ items: quotations });
    }

    async function primaryWithAction(action) {
      await waitFor(() => expect(screen.getByTestId('ticket-primary-action').getAttribute('data-action')).toBe(action));
      return screen.getByTestId('ticket-primary-action');
    }

    // Later describes in this file read listForTicket bare (a rejected query) — keep it that way.
    afterEach(() => { api.dealQuotations.listForTicket.mockReset(); });

    // The summary serves only the NEWEST live direct quotation. With a later party's DRAFT/PENDING beside an
    // APPROVED designer quotation, the deal page reads the APPROVED row off its quotation list and ⋯ still
    // offers ยืนยันคำสั่งซื้อ for that row (before, only the quotation's own editor could).
    describe('APPROVED designer quotation beside a newer owner quotation: ⋯ ยืนยันคำสั่งซื้อ', () => {
      const approvedDesigner = { id: 77, number: 'QT-2026-0077-1', origin: 'DEAL_DIRECT', docStatus: 'APPROVED', recipientType: 'DESIGNER', depositPercent: 0, salesRepId: 1 };
      const ownerRow = (docStatus) => ({ id: 78, number: 'QT-2026-0078-1', origin: 'DEAL_DIRECT', docStatus, recipientType: 'OWNER', depositPercent: 0, salesRepId: 1 });
      function dealWithOwnerQuotation(docStatus, { status = 'draft', createdById = 1, rows = [approvedDesigner], ownerId = 78 } = {}) {
        deal({ requests: [], quotations: [...rows, { ...ownerRow(docStatus), id: ownerId, number: `QT-2026-00${ownerId}-1` }] });
        api.tickets.get.mockResolvedValue({
          ticket: buildTicket({
            summary: {
              lifecycle: 'ACTIVE', salesStage: 'QUOTE_OWNER', createdById, status, entryChannel: 'DESIGNER_LED',
              liveDirectQuotation: { id: ownerId, number: `QT-2026-00${ownerId}-1`, docStatus, recipientType: 'OWNER' },
            },
          }),
        });
        api.dealQuotations.promoteToDeal.mockResolvedValue({ result: { ticketId: 701 } });
      }
      const openMore = async () => {
        fireEvent.click(await screen.findByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));
      };

      it('two APPROVED direct rows (77, 79) beside an owner DRAFT 80: confirms the NEWEST approved one (79); only the open DRAFT (80) is named as auto-cancelled, never the approved 77', async () => {
        dealWithOwnerQuotation('DRAFT', { ownerId: 80, rows: [approvedDesigner, { ...approvedDesigner, id: 79, number: 'QT-2026-0079-1' }] });
        renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser);
        await primaryWithAction('submit_direct_quotation');
        await openMore();
        fireEvent.click(await screen.findByRole('menuitem', { name: 'ยืนยันคำสั่งซื้อ' }));
        const dialog = within(await screen.findByRole('dialog'));
        expect(dialog.getByText(/ใบเสนอราคา QT-2026-0079-1/)).not.toBeNull();
        const cancels = dialog.getByText(/จะถูกยกเลิกอัตโนมัติ/).textContent;
        expect(cancels).toContain('QT-2026-0080-1');
        expect(cancels).not.toContain('QT-2026-0077-1');
        fireEvent.click(dialog.getByRole('button', { name: 'ยืนยันคำสั่งซื้อ' }));
        await waitFor(() => expect(api.dealQuotations.promoteToDeal).toHaveBeenCalledWith(79));
        expect(api.dealQuotations.promoteToDeal).not.toHaveBeenCalledWith(77);
      });

      it('an APPROVED PRICING_REQUEST-origin row on a draft deal is not a direct quotation: no ยืนยันคำสั่งซื้อ in ⋯', async () => {
        deal({
          requests: [],
          quotations: [{ id: 9101, number: 'QT-2026-0101-1', origin: 'PRICING_REQUEST', pricingRequestId: 501, docStatus: 'APPROVED', recipientType: 'DESIGNER', salesRepId: 1, depositPercent: 30 }],
        });
        renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser);
        await stickyInputsSettled();
        const more = screen.queryByRole('button', { name: 'การดำเนินการเพิ่มเติม' });
        if (more) fireEvent.click(more);
        expect(screen.queryByRole('menuitem', { name: 'ยืนยันคำสั่งซื้อ' })).toBeNull();
      });

      it('not offered to a sales rep who does not own the deal', async () => {
        dealWithOwnerQuotation('DRAFT', { createdById: 2 });
        renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser);
        await stickyInputsSettled();
        const more = screen.queryByRole('button', { name: 'การดำเนินการเพิ่มเติม' });
        if (more) fireEvent.click(more);
        expect(screen.queryByRole('menuitem', { name: 'ยืนยันคำสั่งซื้อ' })).toBeNull();
      });
    });
  });

  // The stage-advance readiness gate renders present-but-disabled-with-reason in the header "⋯"
  // overflow menu (where "เลื่อนไป" lives), rather than just not being there.
  describe('stage-advance readiness travels with เลื่อนไป into the overflow menu', () => {
    // Based on S5→S6: this describe's subject is the readiness GATE travelling into the menu, which
    // needs a next stage the menu item can exist for, and all three quote stages are `auto`
    // (`canAdvance` drops the item entirely rather than rendering it disabled). QUOTE_OWNER (S5) →
    // OWNER_SIGNOFF (S6) is the nearest pair that is still a manual sales move. That a quote stage
    // never gets this item is pinned by the sibling test at the end of this block, not folded in
    // here: a fixture testing both wouldn't tell us which one broke.
    function actionsWithAdvance(overrides = {}) {
      return {
        currentState: {
          lifecycle: 'ACTIVE', salesStage: 'QUOTE_OWNER', paymentStatus: null, fulfillmentStatus: null, status: 'price_proposed',
        },
        // The target must be `next.code` EXACTLY: hasAdvanceStageAction matches on it, so a stale
        // target silently drops the item.
        availableActions: [{ action: 'ADVANCE_STAGE', targetStage: 'OWNER_SIGNOFF' }],
        ...overrides,
      };
    }

    it('lists เลื่อนไป present-but-disabled with the gate hint readable when not ready', async () => {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({ summary: { salesStage: 'QUOTE_OWNER', nextFollowUpAt: null } }),
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
    });

    it('lists เลื่อนไป enabled with no disabled reason once ready, and it actually advances the stage', async () => {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({ summary: { salesStage: 'QUOTE_OWNER', nextFollowUpAt: '2026-07-15' } }),
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

    /**
     * A quote stage is reached by addressing a quotation to that party, so the ⋯ menu must not offer
     * เลื่อนไป into one, not even disabled-with-a-reason (how a merely-ungated stage appears). The
     * item is ABSENT, because "satisfy the readiness gate" is the wrong instruction: no follow-up
     * date or logged activity gets this deal to ขั้น 5.
     *
     * Deliberately handed the server's own `ADVANCE_STAGE` for the quote stage, so the only thing
     * refusing is `canAdvance`'s `!next.auto`. That makes this the test that goes red if `auto` is
     * dropped from the quote stages while the backend keeps answering 409.
     */
    it('does NOT list เลื่อนไป at all when the next stage is a quote stage', async () => {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({ summary: { salesStage: 'QUOTE_DESIGN_SIDE', nextFollowUpAt: '2026-07-15' } }),
      });
      api.tickets.actions.mockResolvedValue(actionsWithAdvance({
        currentState: {
          lifecycle: 'ACTIVE', salesStage: 'QUOTE_DESIGN_SIDE', paymentStatus: null, fulfillmentStatus: null, status: 'price_proposed',
        },
        // UPDATE_STAGE (+ one allowed decision, which canEditStage also requires) so the menu has
        // แก้ไขสถานะ… in it: without a second item the ⋯ trigger does not render at all, and the
        // test would "pass" on a missing menu instead of a withdrawn item.
        availableActions: [{ action: 'ADVANCE_STAGE', targetStage: 'QUOTE_OWNER' }, { action: 'UPDATE_STAGE' }],
        stageDecisions: [{
          stage: 'NEGOTIATION', no: 9, allowed: true, requiresReason: false, blockedReason: null, onRoute: true,
        }],
      }));
      api.tickets.listActivities.mockResolvedValue({
        items: [{
          id: 1, ticketId: 701, activityDate: '2026-07-03', kind: 'CALL', note: 'โทรติดตาม',
          createdById: 9, createdByName: 'CEO ทดสอบ', createdAt: '2026-07-03T09:00:00.000Z',
        }],
      });

      renderTicketDetailPage(ceoUser);

      fireEvent.click(await screen.findByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));
      // The menu itself opened (OverflowMenu's role="menu" panel), so an absent item is the
      // finding rather than a render that never happened — the failure mode that would make this
      // test pass for the wrong reason.
      expect(await screen.findByRole('menu')).not.toBeNull();
      expect(screen.getByText('แก้ไขสถานะ…')).not.toBeNull();
      expect(screen.queryByTestId('deal-stage-advance')).toBeNull();
      expect(screen.queryByText(/เลื่อนไป:/)).toBeNull();
      expect(api.tickets.updateStage).not.toHaveBeenCalled();
    });
  });

  // "ราคาและใบเสนอราคา": the customer-quotation tail on the deal page.
  describe('deal quotation panel', () => {
    const approvedPr = {
      id: 501, requestCode: 'PCR-2026-0501', status: 'APPROVED_FOR_QUOTATION',
      ticketCreatedById: 1, recipientType: 'BUYER', recipientLabel: null,
      orderConfirmedAt: null,
    };

    // An acceptance is the order whoever accepted, so the panel reads main's sentence again, never
    // the retired "เลือกผู้รับรายถัดไป" pointer.
    it('an accepted designer request reads the ยืนยันคำสั่งซื้อ sentence, never a next-party pointer', async () => {
      api.pricingRequests.listForTicket.mockResolvedValue({
        items: [{ ...approvedPr, ticketId: 701, status: 'QUOTATION_ACCEPTED', recipientType: 'DESIGNER' }],
      });

      renderTicketDetailPage(salesOwnerUser);
      await openTab(/ใบเสนอราคา/);

      const panel = within(await screen.findByTestId('deal-quotation-panel'));
      expect(panel.getByText('ลูกค้ายอมรับใบเสนอราคาแล้ว — กดปุ่ม “ยืนยันคำสั่งซื้อ” บนแถบด้านบนของหน้าเพื่อเริ่มขั้นตอนรับมัดจำและนำเข้าสินค้า')).not.toBeNull();
      expect(panel.queryByText(/ผู้รับรายถัดไป/)).toBeNull();
    });

    // The panel's own (old-engine) ลูกค้ายอมรับ asks the B2 question too.
    it('the panel\'s ลูกค้ายอมรับ on a designer request asks "ยืนยันว่าลูกค้ายอมรับใบเสนอราคานี้" first; confirming records ACCEPTED', async () => {
      api.pricingRequests.listForTicket.mockResolvedValue({
        items: [{ ...approvedPr, ticketId: 701, status: 'QUOTATION_ISSUED', recipientType: 'DESIGNER' }],
      });
      api.pricingRequests.listCustomerQuotations.mockResolvedValue({
        items: [{ id: 9301, number: 'QT-2026-0301-1', docStatus: 'ISSUED', quotationRevisionNo: 1, grandTotal: 1000 }],
      });
      api.pricingRequests.recordCustomerQuotationOutcome.mockResolvedValue({ quotation: { id: 9301, docStatus: 'ACCEPTED' } });

      renderTicketDetailPage(salesOwnerUser);
      await openTab(/ใบเสนอราคา/);
      fireEvent.click(await screen.findByTestId('deal-quotation-accept'));

      const dialog = within(await screen.findByRole('dialog', { name: 'ยืนยันว่าลูกค้ายอมรับใบเสนอราคานี้' }));
      expect(dialog.getByText('ใบเสนอราคานี้ส่งถึงผู้ออกแบบ ถ้าลูกค้ายอมรับ งานจะใช้ใบนี้ยืนยันคำสั่งซื้อและข้ามผู้รับรายถัดไป')).not.toBeNull();
      expect(api.pricingRequests.recordCustomerQuotationOutcome).not.toHaveBeenCalled();
      fireEvent.click(dialog.getByRole('button', { name: 'ยืนยัน' }));

      await waitFor(() => expect(api.pricingRequests.recordCustomerQuotationOutcome).toHaveBeenCalledWith(
        9301, expect.objectContaining({ outcome: 'ACCEPTED' }),
      ));
    });

    // The panel's ลูกค้าปฏิเสธ / ลูกค้าขอแก้ไข ask first with a required reason (saved as customerNote),
    // and each outcome keeps its own clientRequestId until it succeeds: the backend replays a reused
    // key as the FIRST outcome's success.
    describe('W3-06: the panel\'s ปฏิเสธ / ขอแก้ไข confirm with a reason', () => {
      const record = () => api.pricingRequests.recordCustomerQuotationOutcome;
      const issuedQuotation = (id, number) => ({ id, number, docStatus: 'ISSUED', quotationRevisionNo: 1, grandTotal: 1000 });
      let ctx;
      let showToast;
      async function renderIssuedPanel() {
        showToast = vi.fn();
        api.pricingRequests.listForTicket.mockResolvedValue({
          items: [{ ...approvedPr, ticketId: 701, status: 'QUOTATION_ISSUED', recipientType: 'BUYER' }],
        });
        api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [issuedQuotation(9301, 'QT-2026-0301-1')] });
        ctx = renderTicketDetailPage(salesOwnerUser, showToast);
        await openTab(/ใบเสนอราคา/);
      }
      // The panel's current quotation becomes a different ISSUED one (a refetch brings it in).
      async function switchQuotationTo(id, number) {
        api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [issuedQuotation(id, number)] });
        await act(async () => { await ctx.queryClient.invalidateQueries(); });
        await screen.findAllByText(number);
      }
      async function openDialog(testId) {
        fireEvent.click(await screen.findByTestId(testId));
        return within(await screen.findByRole('dialog'));
      }
      // Does NOT assume the dialog closes: on failure it stays open (see the retry test).
      async function confirmWithReason(testId, reason) {
        const callsBefore = record().mock.calls.length;
        const dialog = await openDialog(testId);
        expect(record()).toHaveBeenCalledTimes(callsBefore);
        const confirm = dialog.getByRole('button', { name: 'ยืนยัน' });
        expect(confirm.disabled).toBe(true);
        fireEvent.change(dialog.getByRole('textbox'), { target: { value: reason } });
        fireEvent.click(confirm);
        await waitFor(() => expect(record()).toHaveBeenCalledTimes(callsBefore + 1));
        return record().mock.calls[callsBefore];
      }
      // Back out if the failed call left the dialog open (it should; the key tests don't depend on that).
      async function cancelDialog() {
        const open = screen.queryByRole('dialog');
        if (open) fireEvent.click(within(open).getByRole('button', { name: 'ยกเลิก' }));
        await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
      }
      const failureSettled = () => waitFor(() => expect(showToast).toHaveBeenCalledWith('error', 'network down'));
      afterEach(() => { record().mockReset(); });

      it('a FAILED outcome keeps the dialog open with the typed reason; ยืนยัน again retries with the SAME key and then closes it', async () => {
        record().mockRejectedValueOnce(new Error('network down')).mockResolvedValue({ quotation: { id: 9301, docStatus: 'REJECTED' } });
        await renderIssuedPanel();
        const [, first] = await confirmWithReason('deal-quotation-reject', 'เหตุผล ก');
        await failureSettled();

        const still = screen.queryByRole('dialog');
        expect(still, 'dialog must stay open after a failed record').not.toBeNull();
        expect(within(still).getByRole('textbox').value).toBe('เหตุผล ก');

        fireEvent.click(within(still).getByRole('button', { name: 'ยืนยัน' }));
        await waitFor(() => expect(record()).toHaveBeenCalledTimes(2));
        const [, retry] = record().mock.calls[1];
        expect(retry.clientRequestId).toBe(first.clientRequestId);
        expect(retry.customerNote).toBe('เหตุผล ก');
        await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
      });

      it('the key belongs to the quotation: a failed ปฏิเสธ on 9301, then a different ISSUED quotation 9302, sends 9302 a fresh key', async () => {
        record().mockRejectedValueOnce(new Error('network down'));
        await renderIssuedPanel();
        const [, onA] = await confirmWithReason('deal-quotation-reject', 'เหตุผล ก');
        await failureSettled();
        await cancelDialog();

        await switchQuotationTo(9302, 'QT-2026-0302-1');
        const [idB, onB] = await confirmWithReason('deal-quotation-reject', 'เหตุผล ก');
        expect(idB).toBe(9302);
        expect(onB.clientRequestId).not.toBe(onA.clientRequestId);
      });
    });
  });

  // Role-projected tab VISIBILITY (ticketDetailTabs.js has its own unit tests for the pure function;
  // these prove the page wires it in, per role, end to end).
  describe('tab visibility per role', () => {
    // PricingRequestPanel is an inner condition inside the unconditionally-visible "สินค้าและราคา", so
    // account keeps this tab (the content-level test in the "role -> reachable-content projection"
    // describe proves it still cannot reach the panel inside it). "เอกสาร" is widened to account (it
    // confirms money against the deposit-notice/attachment rows the register lists) though it can't
    // reach a quotation row inside it (quotation_accountCannotListCustomerQuotations).
    it('account keeps every one of the 5 tabs ("ไฟล์" included)', async () => {
      renderTicketDetailPage(accountUser);

      await screen.findByRole('heading', { level: 1, name: 'โครงการทดสอบ' });
      expect(await screen.findByRole('tab', { name: /^ไฟล์/ })).not.toBeNull();
      expect(await screen.findByRole('tab', { name: /ใบเสนอราคา/ })).not.toBeNull();
      expect(await screen.findByRole('tab', { name: /^เงิน/ })).not.toBeNull();
      expect(screen.getByRole('tab', { name: /ส่งของ/ })).not.toBeNull();
      // Role-unconditional (see ticketDetailTabs.js).
      expect(screen.getByRole('tab', { name: /^ภาพรวม/ })).not.toBeNull();
    });
  });

  // Every gate that used to decide a whole TAB's existence decides either the same tab
  // (money/fulfilment/documents) or an INNER render condition inside an unconditionally-visible tab
  // (pricing-request panel inside "สินค้าและราคา", attachments panel inside "ประวัติ"). These prove the
  // set of *content* each role can reach.
  describe('role -> reachable-content projection is unchanged by the 7->6 tab restructure', () => {
    it('account still cannot reach pricing-request content (formerly its own "ราคา" tab, now an inner condition inside "สินค้าและราคา")', async () => {
      renderTicketDetailPage(accountUser);
      await openTab(/ใบเสนอราคา/);
      expect(screen.queryByRole('heading', { name: 'คำขอราคา' })).toBeNull();
      expect(api.pricingRequests.listForTicket).not.toHaveBeenCalled();
    });

    it('the CEO reaches the pricing-request panel inside the same tab', async () => {
      renderTicketDetailPage(ceoUser);
      await openTab(/ใบเสนอราคา/);
      expect(await screen.findByRole('heading', { name: 'คำขอราคา' })).toBeTruthy();
    });

    it('import still cannot reach payment content (the "การเงิน" tab predicate is unchanged)', async () => {
      renderTicketDetailPage({ id: 7, employeeId: 7, name: 'ฝ่ายนำเข้า', role: 'import' });
      await screen.findByRole('heading', { level: 1, name: 'โครงการทดสอบ' });
      expect(screen.queryByRole('tab', { name: /^เงิน/ })).toBeNull();
      expect(screen.queryByText('การชำระเงิน')).toBeNull();
      expect(api.depositNotices.listByTicket).not.toHaveBeenCalled();
    });
  });

  // The document-read gate (createdById/assignedToId participant OR
  // ROLE_PERMISSIONS.canViewTicketDocuments) decides DealAttachmentsPanel's presence as an inner
  // render condition inside "ประวัติ" (reachable regardless; see ticketDetailTabs.js); only the panel
  // comes and goes.
  describe('attachments panel inside "ไฟล์" follows the document-read gate, not the deal-read gate', () => {
    // Reading a document and writing one are different questions: account may open every deal document
    // but may NOT upload. The closing tax invoice has exactly one entry point
    // (CommissionService.createFromDeal); a second upload path would satisfy the close gate's
    // invoiceOnFile check while the rep silently loses their commission.
    it('offers NO upload control to account, which may read documents but not write them', async () => {
      const { container, unmount } = renderTicketDetailPage(accountUser);
      fireEvent.click(await screen.findByRole('tab', { name: /^ไฟล์/ }));
      expect(await screen.findByRole('heading', { level: 2, name: 'ไฟล์อื่นของงาน (PO / ใบเซ็น)' })).not.toBeNull();
      expect(container.querySelector('#ticket-attachment-file')).toBeNull();
      unmount();

      // The deal's own rep keeps it — this is a targeted narrowing, not a gutting of the panel.
      const { container: ownerContainer } = renderTicketDetailPage(salesOwnerUser);
      fireEvent.click(await screen.findByRole('tab', { name: /^ไฟล์/ }));
      await screen.findByRole('heading', { level: 2, name: 'ไฟล์อื่นของงาน (PO / ใบเซ็น)' });
      expect(ownerContainer.querySelector('#ticket-attachment-file')).not.toBeNull();
    });
  });

  // The deal page's ONE main button creates the deposit notice (ขั้น 10) and the remaining invoice
  // (ขั้น 13); the panels carry no second copy of either label.
  describe('sticky primary — สร้างใบแจ้งยอดเงินรับมัดจำ (ขั้น 10) and สร้างใบแจ้งหนี้ (ขั้น 13), owner rulings 2026-10-28', () => {
    const acceptedPr = {
      id: 501, requestCode: 'PCR-2026-0501', ticketId: 701, ticketCreatedById: 1,
      status: 'QUOTATION_ACCEPTED', recipientType: 'BUYER', recipientLabel: null, orderConfirmedAt: '2026-10-20T09:00:00.000Z',
    };
    const nonOwnerSales = { id: 2, employeeId: 2, name: 'เซลส์อื่น', role: 'sales' };
    const primary = () => screen.queryByTestId('ticket-primary-action');
    const primaryAction = () => primary()?.getAttribute('data-action') ?? null;
    const location = () => screen.getByTestId('location-probe').textContent;

    // A deal whose customer has confirmed the order (CUSTOMER_CONFIRMED) and has no notice yet.
    function depositStageDeal() {
      const state = {
        lifecycle: 'ACTIVE', salesStage: 'ORDER_RECEIVED', paymentStatus: 'CUSTOMER_CONFIRMED', fulfillmentStatus: null, status: 'quotation_issued',
      };
      api.tickets.get.mockResolvedValue({ ticket: buildTicket({ summary: { ...state, createdById: 1 } }) });
      api.tickets.actions.mockResolvedValue({ currentState: state, availableActions: [] });
      api.pricingRequests.listForTicket.mockResolvedValue({ items: [acceptedPr] });
      api.dealQuotations.listForTicket.mockResolvedValue({ items: [] });
            api.pricingRequests.createDepositNoticeFromQuotation.mockResolvedValue({ depositNotice: { id: 801, status: 'DRAFT' } });
      api.depositNotices.createDraft.mockResolvedValue({ depositNotice: { id: 801, status: 'DRAFT' } });
    }

    // Later describes read these bare (a rejected query) — keep it that way.
    afterEach(() => {
      api.dealQuotations.listForTicket.mockReset();
      api.storedRemainingInvoices.listForTicket.mockReset();
      api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [] });
    });

    it('ขั้น 10, order-confirmed accepted PR, no notice: the main button is สร้างใบแจ้งยอดเงินรับมัดจำ; clicking creates it from the quotation and opens /deposit', async () => {
      depositStageDeal();
      renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser);
      await waitFor(() => expect(primaryAction()).toBe('create_deposit_notice'));
      expect(primary().textContent).toContain('สร้างใบแจ้งยอดเงินรับมัดจำ');

      fireEvent.click(primary());

      await waitFor(() => expect(api.pricingRequests.createDepositNoticeFromQuotation).toHaveBeenCalledWith(501, {}));
      await waitFor(() => expect(location()).toBe('/tickets/701/deposit'));
      expect(api.depositNotices.createDraft).not.toHaveBeenCalled();
    });

    it('a sales rep who does NOT own the deal gets no create_deposit_notice main button (the backend refuses non-owners)', async () => {
      depositStageDeal();
      renderTicketDetailPageAtRoute(['/tickets/701'], nonOwnerSales);
      await stickyInputsSettled();
      await screen.findByRole('heading', { level: 1 });
      expect(primaryAction()).not.toBe('create_deposit_notice');
    });
  });

  // At ขั้น 11 (DEPOSIT_RECEIVED, deposit paid, nothing ordered yet) the owning rep's ONE main button
  // creates the import request through DealFulfilmentPanel's own flow.
  describe('sticky primary — สร้างใบขอซื้อ (IR) (ขั้น 11), QA-09', () => {
    const acceptedPr = {
      id: 501, requestCode: 'PCR-2026-0501', ticketId: 701, ticketCreatedById: 1,
      status: 'QUOTATION_ACCEPTED', recipientType: 'BUYER', recipientLabel: null, orderConfirmedAt: '2026-10-20T09:00:00.000Z',
    };
    const nonOwnerSales = { id: 2, employeeId: 2, name: 'เซลส์อื่น', role: 'sales' };
    const primary = () => screen.queryByTestId('ticket-primary-action');
    const primaryAction = () => primary()?.getAttribute('data-action') ?? null;
    // jsdom has no scrollIntoView: record which element the page asked to scroll to.
    let scrolledIds = [];
    const location = () => screen.getByTestId('location-probe').textContent;
    const scrolledToIrSection = () => scrolledIds.includes('deal-fulfilment-stored-ir');
    beforeEach(() => {
      scrolledIds = [];
      Element.prototype.scrollIntoView = function scrollIntoView() { scrolledIds.push(this.id); };
      // clearAllMocks keeps implementations: put the "already decided" default back after the tests that undecide it.
      api.tickets.getProcurementSource.mockResolvedValue({ legacy: false, editable: false, decidedAt: '2026-11-10T00:00:00Z', quotationNumber: null, lines: [] });
    });

    function irStageDeal() {
      const state = {
        lifecycle: 'ACTIVE', salesStage: 'DEPOSIT_RECEIVED', paymentStatus: 'DEPOSIT_PAID', fulfillmentStatus: null, status: 'quotation_issued',
      };
      api.tickets.get.mockResolvedValue({ ticket: buildTicket({ summary: { ...state, createdById: 1 } }) });
      api.tickets.actions.mockResolvedValue({ currentState: state, availableActions: [] });
      api.pricingRequests.listForTicket.mockResolvedValue({ items: [acceptedPr] });
      api.dealQuotations.listForTicket.mockResolvedValue({ items: [] });
      api.storedImportRequests.listForTicket.mockResolvedValue({ importRequests: [] });
      api.storedImportRequests.createDrafts.mockResolvedValue({ importRequests: [{ id: 31, status: 'DRAFT', ticketId: 701 }] });
    }

    afterEach(() => {
      api.dealQuotations.listForTicket.mockReset();
      api.storedImportRequests.listForTicket.mockResolvedValue({ importRequests: [] });
      delete Element.prototype.scrollIntoView;
    });

    it('owner rep at DEPOSIT_PAID: the main button is สร้างใบขอซื้อ (IR); clicking asks first (W3-11), confirming creates the drafts once and lands on the จัดซื้อ-ส่งมอบ tab', async () => {
      irStageDeal();
      renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser);
      await waitFor(() => expect(primaryAction()).toBe('create_import_request'));
      expect(primary().textContent).toContain('ออกใบขอซื้อ (IR)');
      expect(primary().textContent).not.toContain('สร้างใบขอซื้อ');
      await waitFor(() => expect(primary().disabled).toBe(false));

      fireEvent.click(primary());

      // ออกใบขอซื้อ (IR) is irreversible -> the handle opens the panel's confirm; nothing is sent yet.
      const dialog = await screen.findByRole('dialog', { name: 'ยืนยันการออกใบขอซื้อ (IR)' });
      expect(api.storedImportRequests.createDrafts).not.toHaveBeenCalled();
      const confirm = within(dialog).getByRole('button', { name: 'ออกใบขอซื้อ' });
      await waitFor(() => expect(confirm.disabled).toBe(false));
      fireEvent.click(confirm);

      await waitFor(() => expect(api.storedImportRequests.createDrafts).toHaveBeenCalledTimes(1));
      expect(api.storedImportRequests.createDrafts).toHaveBeenCalledWith(701, undefined);
      await waitFor(() => expect(location()).toContain('tab=ship'));
      await waitFor(() => expect(scrolledToIrSection()).toBe(true));
    });

    it('a sales rep who does NOT own the deal gets no create_import_request main button', async () => {
      irStageDeal();
      renderTicketDetailPageAtRoute(['/tickets/701'], nonOwnerSales);
      await stickyInputsSettled();
      await screen.findByRole('heading', { level: 1 });
      expect(primaryAction()).not.toBe('create_import_request');
    });
  });

  // Direct-quotation CTAs. The ใบเสนอราคา panel offers the two routes as sibling buttons (never a
  // dialog, DESIGN.md §16) and the sticky CTA follows a live direct quotation
  // (TicketSummaryDto.liveDirectQuotation).
  describe('slice 2 — ใบเสนอราคา route choice and the live direct quotation CTA', () => {
    const DRAFT_LIVE = { id: 77, number: 'QT-2026-0077-1', docStatus: 'DRAFT', recipientType: 'DESIGNER' };

    function activeDeal(summary = {}) {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({ summary: { lifecycle: 'ACTIVE', salesStage: 'PRESENTATION', status: 'draft', createdById: 1, ...summary } }),
      });
      api.tickets.actions.mockResolvedValue({
        currentState: { lifecycle: 'ACTIVE', salesStage: summary.salesStage ?? 'PRESENTATION', paymentStatus: null, fulfillmentStatus: null, status: 'draft' },
        availableActions: [],
      });
    }

    beforeEach(() => {
      api.dealQuotations.listForTicket.mockResolvedValue({ items: [] });
    });

    it('a double-click on the dialog\'s confirm sends ONE confirm request (VW-05), same as the pricing-request door', async () => {
      activeDeal({ liveDirectQuotation: { ...DRAFT_LIVE, docStatus: 'APPROVED', recipientType: 'BUYER' } });
      api.dealQuotations.listForTicket.mockResolvedValue({ items: [{ ...DRAFT_LIVE, docStatus: 'APPROVED' }] }); // the confirm waits for the row
      api.dealQuotations.promoteToDeal.mockReturnValue(new Promise(() => {})); // in flight: never settles
      renderTicketDetailPage(salesOwnerUser);
      await waitFor(() => expect(screen.getByTestId('ticket-primary-action').getAttribute('data-action')).toBe('confirm_order_direct'));
      fireEvent.click(screen.getByTestId('ticket-primary-action'));
      const confirmBtn = within(screen.getByRole('dialog')).getByRole('button', { name: 'ยืนยันคำสั่งซื้อ' });
      fireEvent.click(confirmBtn);
      fireEvent.click(confirmBtn);
      await waitFor(() => expect(api.dealQuotations.promoteToDeal).toHaveBeenCalled());
      await new Promise((r) => setTimeout(r, 50)); // let a second request surface
      expect(api.dealQuotations.promoteToDeal).toHaveBeenCalledTimes(1);
    });

    it('a refused confirm surfaces the server\'s own Thai message', async () => {
      activeDeal({ liveDirectQuotation: { ...DRAFT_LIVE, docStatus: 'APPROVED', recipientType: 'BUYER' } });
      api.dealQuotations.listForTicket.mockResolvedValue({ items: [{ ...DRAFT_LIVE, docStatus: 'APPROVED' }] }); // the confirm waits for the row
      api.dealQuotations.promoteToDeal.mockRejectedValue(Object.assign(new Error('งานไม่ได้อยู่ในสถานะ ACTIVE'), { status: 409 }));
      const showToast = vi.fn();
      renderTicketDetailPage(salesOwnerUser, showToast);
      await waitFor(() => expect(screen.getByTestId('ticket-primary-action').getAttribute('data-action')).toBe('confirm_order_direct'));
      fireEvent.click(screen.getByTestId('ticket-primary-action'));
      fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'ยืนยันคำสั่งซื้อ' }));
      await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', 'งานไม่ได้อยู่ในสถานะ ACTIVE'));
    });
  });

  // The sticky primary CTA "แก้ใบเสนอราคา" (SALES_ACTION.REVISE_QUOTATION). The page resolves the
  // revisable quotation from api.dealQuotations.listForTicket through
  // quotationMeta.canReviseDealQuotation (the real predicate) and hands it to the cascade. Clicking it
  // mints a revision (api.dealQuotations.createRevision) and opens it; an open revision draft is
  // opened instead ("ไปที่ฉบับแก้ไข"), never a second one.
  describe('PR A — แก้ใบเสนอราคา (sticky primary past draft; overflow secondary at owner/buyer stages)', () => {
    // A PRICING_REQUEST-origin row names its request (pricingRequestId, as DealQuotationDto does); a
    // DEAL_DIRECT row has none.
    const quotationRow = (overrides = {}) => {
      const row = {
        id: 80, number: 'QT-2026-0080-1', origin: 'PRICING_REQUEST', docStatus: 'ISSUED', recipientType: 'BUYER',
        salesRepId: 1, revisionNo: 1, parentQuotationId: null, grandTotal: 1070, ...overrides,
      };
      return { pricingRequestId: row.origin === 'PRICING_REQUEST' ? 501 : null, ...row };
    };
    // The BUYER's request: its issued quotation keeps บันทึกผลใบเสนอราคา as the primary. A designer's
    // or owner's issued quotation leads with the route choice instead, so it cannot stand in here.
    const issuedPr = {
      id: 501, requestCode: 'PCR-2026-0501', ticketId: 701, ticketCreatedById: 1,
      status: 'QUOTATION_ISSUED', recipientType: 'BUYER', recipientLabel: null, orderConfirmedAt: null,
    };

    // PR-origin deal at `salesStage` with one issued quotation (and the PR that produced it).
    function prOriginDeal({ salesStage = 'QUOTE_OWNER', rows = [quotationRow()] } = {}) {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({ summary: { lifecycle: 'ACTIVE', salesStage, status: 'quotation_issued', createdById: 1 } }),
      });
      api.tickets.actions.mockResolvedValue({
        currentState: { lifecycle: 'ACTIVE', salesStage, paymentStatus: null, fulfillmentStatus: null, status: 'quotation_issued' },
        availableActions: [],
      });
      api.pricingRequests.listForTicket.mockResolvedValue({ items: [issuedPr] });
      api.dealQuotations.listForTicket.mockResolvedValue({ items: rows });
    }

    // DEAL_DIRECT deal whose only quotation is APPROVED and whose order was already confirmed.
    function directApprovedDeal() {
      const approved = quotationRow({ id: 77, number: 'QT-2026-0077-1', origin: 'DEAL_DIRECT', docStatus: 'APPROVED', recipientType: 'DESIGNER' });
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({
          summary: {
            lifecycle: 'ACTIVE', salesStage: 'PRESENTATION', status: 'quotation_issued', createdById: 1,
            liveDirectQuotation: { id: 77, number: 'QT-2026-0077-1', docStatus: 'APPROVED', recipientType: 'DESIGNER' },
          },
        }),
      });
      api.tickets.actions.mockResolvedValue({
        currentState: { lifecycle: 'ACTIVE', salesStage: 'PRESENTATION', paymentStatus: null, fulfillmentStatus: null, status: 'quotation_issued' },
        availableActions: [],
      });
      api.dealQuotations.listForTicket.mockResolvedValue({ items: [approved] });
    }

    const primary = () => screen.getByTestId('ticket-primary-action');
    const primaryAction = () => screen.queryByTestId('ticket-primary-action')?.getAttribute('data-action') ?? null;
    async function settle() {
      await waitFor(() => expect(api.dealQuotations.listForTicket).toHaveBeenCalled());
      // (account never reads the pricing-request list, so only the quotation list is awaited.)
      await new Promise((resolve) => { setTimeout(resolve, 50); });
    }

    beforeEach(() => {
      api.dealQuotations.createRevision.mockResolvedValue({ quotation: quotationRow({ id: 88, docStatus: 'DRAFT', parentQuotationId: 80 }) });
    });
    // mockReset puts back the implementation the vi.mock factory gave the spy (the real predicate),
    // so the swap in "consumes the predicate" ends with that test.
    afterEach(() => { quotationMeta.canReviseDealQuotation.mockReset(); });

    // At the owner / buyer quote stages the client may want a revision OR be happy with the original,
    // so revise is an ADDITIONAL action in the header "⋯" overflow menu (the page's existing secondary
    // slot) and บันทึกผลใบเสนอราคา stays the sticky primary.
    async function reviseMenuItem() {
      const trigger = await screen.findByRole('button', { name: 'การดำเนินการเพิ่มเติม' }).catch(() => null);
      if (!trigger) return null;
      fireEvent.click(trigger);
      return screen.queryByTestId('deal-quotation-revise');
    }

    it('DEAL_DIRECT: an APPROVED quotation on an order-confirmed deal -> CTA "แก้ใบเสนอราคา"; click mints a revision and opens it', async () => {
      directApprovedDeal();
      renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser);
      await waitFor(() => expect(primaryAction()).toBe('revise_quotation'));
      expect(primary().textContent).toContain('แก้ใบเสนอราคา');
      expect(api.dealQuotations.createRevision).not.toHaveBeenCalled();
      fireEvent.click(primary());
      await waitFor(() => expect(api.dealQuotations.createRevision).toHaveBeenCalledWith(77));
      await waitFor(() => expect(screen.getByTestId('location-probe').textContent).toBe('/quotations/88'));
    });

    it('PR-origin: an ISSUED quotation at QUOTE_OWNER keeps บันทึกผลใบเสนอราคา as primary AND offers แก้ใบเสนอราคา in the overflow menu; click revises it', async () => {
      prOriginDeal({ salesStage: 'QUOTE_OWNER' });
      renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser);
      await waitFor(() => expect(primaryAction()).toBe('record_quotation_outcome'));
      await settle();
      const item = await reviseMenuItem();
      expect(item).not.toBeNull();
      expect(item.textContent).toContain('แก้ใบเสนอราคา');
      expect(primaryAction()).toBe('record_quotation_outcome');
      fireEvent.click(item);
      await waitFor(() => expect(api.dealQuotations.createRevision).toHaveBeenCalledWith(80));
      await waitFor(() => expect(screen.getByTestId('location-probe').textContent).toBe('/quotations/88'));
    });

    it('error path: a refused revision (409) toasts the server\'s Thai message and stays on the deal', async () => {
      api.dealQuotations.createRevision.mockRejectedValue(Object.assign(new Error('มีฉบับแก้ไขของใบเสนอราคานี้อยู่แล้ว'), { status: 409 }));
      prOriginDeal();
      const showToast = vi.fn();
      renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser, showToast);
      await settle();
      fireEvent.click(await reviseMenuItem());
      await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', 'มีฉบับแก้ไขของใบเสนอราคานี้อยู่แล้ว'));
      expect(screen.getByTestId('location-probe').textContent).toBe('/tickets/701');
    });

    it('consumes the predicate: when canReviseDealQuotation refuses the quotation there is no revise item', async () => {
      quotationMeta.canReviseDealQuotation.mockImplementation(() => false);
      prOriginDeal();
      renderTicketDetailPage(salesOwnerUser);
      await settle();
      await waitFor(() => expect(primaryAction()).toBe('record_quotation_outcome'));
      expect(await reviseMenuItem()).toBeNull();
      expect(quotationMeta.canReviseDealQuotation).toHaveBeenCalled();
    });

    it('negative: a sales rep who does NOT own the quotation never sees แก้ใบเสนอราคา', async () => {
      prOriginDeal({ rows: [quotationRow({ salesRepId: 99 })] });
      renderTicketDetailPage(salesOwnerUser);
      await settle();
      expect(await reviseMenuItem()).toBeNull();
      expect(api.dealQuotations.createRevision).not.toHaveBeenCalled();
    });
  });

  // The gate refuses an off-route stage with "… — แก้ช่องทางดีลก่อน", and EntryChannelFix puts the
  // control that answers it back where the refusal is read. Driven end to end so the WIRING is
  // pinned: the advertisement gates it, the click reaches api.tickets.setEntryChannel, and the
  // ticket + actions the rep is looking at refresh WITHOUT a reload (the route, the off-route group
  // and the ribbon follow).
  describe('แก้ช่องทางดีล — the remedy for an off-route refusal', () => {
    const OWNER_OFF = new Set(['QUOTE_DESIGN_SIDE']);
    const FIRST = { action: 'SET_ENTRY_CHANNEL', kind: 'policy', label: 'ตั้งค่า entry channel', requiredFields: ['value'] };
    const STATED = { action: 'SET_ENTRY_CHANNEL', kind: 'policy', label: 'ตั้งค่า entry channel', requiredFields: ['value', 'note'] };

    function actionsFor(off, extra) {
      const stageDecisions = DEAL_STAGE_CATALOG.stages.map((stage) => ({
        stage: stage.code,
        no: stage.no,
        allowed: stage.code !== 'SPEC_APPROVED' && !off.has(stage.code),
        requiresReason: false,
        blockedReason: off.has(stage.code)
          ? 'ช่องทางรับงานของงานนี้คือเจ้าของติดต่อโดยตรง — ขั้นที่ 4 ไม่อยู่ในเส้นทางนี้ — แก้ช่องทางรับงานก่อน'
          : null,
        onRoute: !off.has(stage.code),
      }));
      return {
        currentState: {
          lifecycle: 'ACTIVE', salesStage: 'SPEC_APPROVED', paymentStatus: null, fulfillmentStatus: null, status: 'price_proposed',
        },
        availableActions: [{ action: 'UPDATE_STAGE' }, ...extra],
        stageDecisions,
      };
    }

    async function openSteps(extra) {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({ summary: { salesStage: 'SPEC_APPROVED', entryChannel: 'OWNER_DIRECT', nextFollowUpAt: '2026-07-15' } }),
      });
      api.tickets.actions.mockResolvedValue(actionsFor(OWNER_OFF, extra));
      api.tickets.listActivities.mockResolvedValue({ items: [] });
      renderTicketDetailPage(ceoUser);
      fireEvent.click(await screen.findByRole('button', { name: /ดูขั้นตอนทั้งหมด/ }));
      return screen.findByTestId('off-route-group');
    }

    it('a first correction needs no note: it calls the API, then the route refreshes without a reload', async () => {
      const group = await openSteps([FIRST]);
      // What the server answers once the channel is corrected: the new ticket, and — on the refetch — a route with
      // nothing off it.
      api.tickets.setEntryChannel.mockResolvedValue({
        ticket: buildTicket({ summary: { salesStage: 'SPEC_APPROVED', entryChannel: 'DESIGNER_LED', nextFollowUpAt: '2026-07-15' } }),
      });
      api.tickets.actions.mockResolvedValue(actionsFor(new Set(), [FIRST]));
      const before = api.tickets.actions.mock.calls.length;

      fireEvent.click(within(group).getByRole('button', { name: /แก้ช่องทาง/ }));
      expect(screen.queryByTestId('entry-channel-fix-reason')).toBeNull();
      fireEvent.click(screen.getByRole('radio', { name: /ผู้ออกแบบนำงานมา/ }));
      fireEvent.click(screen.getByRole('button', { name: 'บันทึกช่องทางใหม่' }));

      await waitFor(() => expect(api.tickets.setEntryChannel).toHaveBeenCalledWith(701, { value: 'DESIGNER_LED', note: null }));
      await waitFor(() => expect(api.tickets.actions.mock.calls.length).toBeGreaterThan(before));
      await waitFor(() => expect(screen.queryByTestId('off-route-group')).toBeNull());
      expect(screen.getByTestId('deal-route-line').textContent).toContain('ผู้ออกแบบนำงานมา');
    });

    it('changing a stated channel asks for the reason the server listed, and sends it', async () => {
      const group = await openSteps([STATED]);
      api.tickets.setEntryChannel.mockResolvedValue({
        ticket: buildTicket({ summary: { salesStage: 'SPEC_APPROVED', entryChannel: 'DESIGNER_LED', nextFollowUpAt: '2026-07-15' } }),
      });
      fireEvent.click(within(group).getByRole('button', { name: /แก้ช่องทาง/ }));
      fireEvent.click(screen.getByRole('radio', { name: /ผู้ออกแบบนำงานมา/ }));
      expect(screen.getByRole('button', { name: 'บันทึกช่องทางใหม่' }).disabled).toBe(true);
      fireEvent.change(screen.getByTestId('entry-channel-fix-reason'), { target: { value: 'มีผู้ออกแบบเข้ามาร่วมจริง' } });
      fireEvent.click(screen.getByRole('button', { name: 'บันทึกช่องทางใหม่' }));
      await waitFor(() => expect(api.tickets.setEntryChannel).toHaveBeenCalledWith(701, {
        value: 'DESIGNER_LED', note: 'มีผู้ออกแบบเข้ามาร่วมจริง',
      }));
    });
  });

  // สั่งเพิ่ม (add-on orders): the entry on the ใบเสนอราคา tab, the confirm sheet, and where confirming lands.
  describe('add-on order (สั่งเพิ่ม)', () => {
    function addOnDeal(summary = {}) {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({
          summary: {
            lifecycle: 'ACTIVE', salesStage: 'ORDER_RECEIVED', status: 'quotation_issued', createdById: 1,
            code: 'DL-2026-0300', projectName: 'โครงการตัวอย่าง', addOnCount: 1, ...summary,
          },
        }),
      });
      api.tickets.parties.mockResolvedValue({ parties: [] });
      api.dealQuotations.listForTicket.mockResolvedValue({ items: [] });
    }

    it('the owner sees the button; the sheet names the next code, and confirming opens the new order in the editor', async () => {
      addOnDeal();
      api.tickets.createAddOnOrder.mockResolvedValue({ addOnOrder: { ticketId: 902, code: 'DL-2026-0300-3', parentTicketId: 701 } });
      renderTicketDetailPageAtRoute(['/tickets/701?tab=quote'], salesOwnerUser);
      fireEvent.click(await screen.findByTestId('add-on-order-open'));
      const sheet = await screen.findByTestId('add-on-order-sheet');
      expect(within(sheet).getByText('เปิดออเดอร์เพิ่มในงานนี้?')).toBeTruthy();
      expect(sheet.textContent).toContain('DL-2026-0300-3');
      expect(sheet.textContent).toContain('ออเดอร์เดิม (DL-2026-0300-1) ไม่ถูกแก้ไข');
      fireEvent.click(within(sheet).getByTestId('add-on-order-confirm'));
      await waitFor(() => expect(screen.getByTestId('location-probe').textContent).toBe('/quotations/new?ticket=902'));
      expect(api.tickets.createAddOnOrder).toHaveBeenCalledWith(701);
    });

    it('is not offered to account, nor to a rep who does not own the deal', async () => {
      addOnDeal();
      const { unmount } = renderTicketDetailPageAtRoute(['/tickets/701?tab=quote'], { id: 77, employeeId: 77, name: 'อื่น', role: 'sales' });
      await screen.findByText('ใบเสนอราคาของงานนี้');
      await stickyInputsSettled();
      expect(screen.queryByTestId('add-on-order-open')).toBeNull();
      unmount();
      renderTicketDetailPageAtRoute(['/tickets/701?tab=quote'], accountUser);
      await screen.findByText('ใบเสนอราคาของงานนี้');
      await stickyInputsSettled();
      expect(screen.queryByTestId('add-on-order-open')).toBeNull();
    });
  });

  // QA-22's stored-IR read: every role the backend lets read them does, and never a role it would refuse.
  describe('stored IR read gate (QA-22)', () => {
    it.each([
      ['the owning sales rep', salesOwnerUser],
      ['import (not the owner)', { id: 7, employeeId: 7, name: 'ฝ่ายนำเข้า', role: 'import' }],
      ['sales_manager (not the owner)', { id: 11, employeeId: 11, name: 'ผจก.ขาย', role: 'sales_manager' }],
    ])('reads the stored IRs for %s', async (_name, viewer) => {
      renderTicketDetailPage(viewer);
      await waitFor(() => expect(api.storedImportRequests.listForTicket).toHaveBeenCalledWith(701));
    });

    it('a non-owner sales rep cannot read stored IRs: no request is made', async () => {
      renderTicketDetailPage({ id: 2, employeeId: 2, name: 'พนักงานขายอีกคน', role: 'sales' });
      await screen.findByTestId('deal-state-header');
      await stickyInputsSettled().catch(() => {});
      expect(api.storedImportRequests.listForTicket).not.toHaveBeenCalled();
    });
  });

  // v2.3.0 read-all grant: a flagged warehouse reads the full page like a sales manager, keeps the round review buttons
  // (D9), and gets no write control: no comment box, no activity form, no expected-payment editor.
  describe('oversight reader (read-all grant)', () => {
    const reader = { id: 31, employeeId: 31, name: 'คลัง ดูทุกดีล', role: 'warehouse', readAllSales: true };

    it('reads the deal tabs, the stored IRs and the activities, but gets no comment box, activity form or expected-payment editor', async () => {
      renderTicketDetailPage(reader);
      await screen.findByTestId('deal-state-header');
      await waitFor(() => expect(api.storedImportRequests.listForTicket).toHaveBeenCalledWith(701));
      await waitFor(() => expect(api.tickets.listActivities).toHaveBeenCalled());
      expect(await screen.findByRole('tab', { name: /ส่งของ/ })).not.toBeNull();
      expect(screen.queryByRole('button', { name: /\+ บันทึก/ })).toBeNull();
      fireEvent.click(screen.getByRole('tab', { name: /เงิน/ }));
      await act(async () => { await new Promise((resolve) => { setTimeout(resolve, 50); }); });
      expect(screen.queryByRole('button', { name: /เพิ่มเงินที่คาดว่าจะเข้า/ })).toBeNull();
    });

    it('a sales manager keeps the note composer (positive control)', async () => {
      renderTicketDetailPage({ id: 11, employeeId: 11, name: 'ผจก.ขาย', role: 'sales_manager' });
      expect(await screen.findByRole('button', { name: /\+ บันทึก/ })).not.toBeNull();
    });

    it('keeps the round review buttons on the ship tab, from the server\'s round.viewer', async () => {
      const sent = {
        id: 5, roundNo: 1, status: 'SENT', quotationId: 11, quotationNumber: 'QT-2026-0001-1', note: null, returnReasonCodes: [],
        returnReasonText: null, requestedByName: 'คุณขาย', sentAt: '2026-11-19T03:42:00Z', forwardedByName: null, forwardedAt: null,
        invoice: null, lines: [{ itemId: 1, description: 'กระเบื้อง', unit: 'แผ่น', coveredQty: 300, deliveredQty: 0 }], files: [],
        viewer: { canEdit: false, canSend: false, canReview: true, canIssue: false, canRecordDelivery: false },
      };
      api.deliveryRequests.list.mockResolvedValue({ order: { ticketId: 701, code: 'PR-2026-0701', displayCode: 'PR-2026-0701-1', orderNo: 1, customerName: 'ลูกค้า', projectName: null, parentCode: null }, rounds: [sent], approvedQuotations: [], canStart: false, startBlockedReason: 'x' });
      renderTicketDetailPageAtRoute(['/tickets/701?tab=ship'], reader);
      expect(await screen.findByTestId('forward-button')).not.toBeNull();
    });
  });

  // The download gate is remainingInvoiceReadiness.js's rule (stock-delivered and imported deals), sales owner only.
  describe('remaining-invoice download gate', () => {
    const INVOICE_BUTTON = 'ดาวน์โหลดใบแจ้งหนี้ส่วนที่เหลือ';
    afterEach(() => { api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [] }); });

    it.each(['FROM_STOCK', 'GOODS_RECEIVED'])('offers the download to the sales owner once fulfilment is %s', async (fulfillmentStatus) => {
      api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [{ id: 901, ticketId: 701, status: 'ISSUED', docNumber: 'GLRI69001' }] });
      api.tickets.get.mockResolvedValueOnce({
        ticket: buildTicket({ summary: { status: 'quotation_issued', fulfillmentStatus, createdById: 1 } }),
      });
      renderTicketDetailPage(salesOwnerUser);
      await waitFor(() => expect(api.storedRemainingInvoices.listForTicket).toHaveBeenCalled());
      expect(await screen.findByRole('button', { name: INVOICE_BUTTON })).not.toBeNull();
    });

    it('withholds the download from a non-sales role even on a delivered stock deal', async () => {
      api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [{ id: 901, ticketId: 701, status: 'ISSUED', docNumber: 'GLRI69001' }] });
      api.tickets.get.mockResolvedValueOnce({
        ticket: buildTicket({ summary: { status: 'quotation_issued', fulfillmentStatus: 'FULLY_DELIVERED', createdById: 1 } }),
      });
      renderTicketDetailPage(accountUser);
      expect(await screen.findByRole('heading', { level: 1 })).not.toBeNull();
      await act(async () => { await new Promise((resolve) => { setTimeout(resolve, 50); }); });
      expect(screen.queryByRole('button', { name: INVOICE_BUTTON })).toBeNull();
    });
  });

  // The blocker line must not show "รอชำระมัดจำ" for account (its own action clears the wait); sales_manager, not ceo,
  // is the positive because the page guards on role === 'account' exactly. A legacy status keeps the resolver primary out.
  describe('blocker line respects !isMoneyRecorder (GLA-118)', () => {
    const waiting = () => {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({
          summary: {
            lifecycle: 'ACTIVE', salesStage: 'QUOTE_DESIGN_SIDE', status: 'document_issued', paymentStatus: 'DEPOSIT_NOTICE_ISSUED', createdById: 1,
          },
        }),
      });
      api.tickets.actions.mockResolvedValue({
        currentState: {
          lifecycle: 'ACTIVE', salesStage: 'QUOTE_DESIGN_SIDE', paymentStatus: 'DEPOSIT_NOTICE_ISSUED', fulfillmentStatus: null, status: 'document_issued',
        },
        availableActions: [],
      });
    };

    it('shows "รอชำระมัดจำ" to a non-account role (sales_manager)', async () => {
      waiting();
      renderTicketDetailPage({ id: 11, employeeId: 11, name: 'ผจก.ขาย', role: 'sales_manager' });
      expect((await screen.findAllByText(/รอชำระมัดจำ/)).length).toBeGreaterThan(0);
    });

    it('never shows "รอชำระมัดจำ" to account — account is the role that clears it', async () => {
      waiting();
      renderTicketDetailPage(accountUser);
      await screen.findByRole('heading', { level: 1, name: 'โครงการทดสอบ' });
      expect(screen.queryByText(/รอชำระมัดจำ/)).toBeNull();
    });
  });

  // deal-party-model.md: a bidding contractor is a BUYER party; บันทึกผลประมูล is the primary only for the deal's editors.
  describe('deal parties — บันทึกผลประมูล gating', () => {
    const party = (overrides) => ({
      id: 1, ticketId: 701, customerId: 5, customerName: 'บริษัท ก จำกัด', contactId: null, contactName: null,
      role: 'BUYER', status: 'QUOTED', notAwardedReason: null, headline: false, splitTicketId: null, splitTicketCode: null,
      latestQuotationId: 11, latestQuotationNumber: 'QT-2026-0011-1', latestQuotationStatus: 'APPROVED', latestQuotationAmount: 512900,
      ...overrides,
    });
    function bidDeal() {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({
          summary: {
            lifecycle: 'ACTIVE', salesStage: 'QUOTE_BUYER', status: 'draft', createdById: 1, entryChannel: 'BUYER_DIRECT',
            code: 'DL-2026-0280', projectName: 'GRAND RIVER HOTEL',
          },
        }),
      });
      api.tickets.parties.mockResolvedValue({
        parties: [
          party({ id: 1, headline: true }),
          party({ id: 2, customerName: 'บจก.สมมติ ก่อสร้าง', latestQuotationId: 12, latestQuotationNumber: 'QT-2026-0012-2' }),
        ],
      });
      api.dealQuotations.listForTicket.mockResolvedValue({ items: [] });
    }

    it('several bidders, none awarded: the owner gets บันทึกผลประมูล as the primary', async () => {
      bidDeal();
      renderTicketDetailPageAtRoute(['/tickets/701?tab=deal'], salesOwnerUser);
      const primary = await screen.findByTestId('ticket-primary-action');
      await waitFor(() => expect(primary.getAttribute('data-action')).toBe('record_bid_outcome'));
    });

    it('a viewer who may not edit the deal gets no บันทึกผลประมูล and no add button', async () => {
      bidDeal();
      renderTicketDetailPageAtRoute(['/tickets/701?tab=deal'], { id: 77, employeeId: 77, name: 'อื่น', role: 'sales' });
      await screen.findByTestId('deal-parties-panel');
      await stickyInputsSettled();
      expect(screen.queryByTestId('add-party')).toBeNull();
      expect(document.querySelector('[data-action="record_bid_outcome"]')).toBeNull();
    });
  });
});
