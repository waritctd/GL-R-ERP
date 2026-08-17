import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TicketDetailPage } from './TicketDetailPage.jsx';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  // Imported inside the factory, not at the top of the file: vi.mock is hoisted above every import.
  const { DEAL_STAGE_CATALOG } = await import('../../data/dealStageCatalog.js');
  return {
    ...actual,
    api: {
      // The pipeline enumeration (GET /api/meta/deal-stages). Served here as the SAME canned
      // payload mockApi serves — data/dealStageCatalog.js, which stageCatalog.test.js pins against
      // DealStage.java — so this fixture cannot quietly describe a pipeline the backend does not
      // have. A fixture more populated (or differently shaped) than production is the failure mode
      // CLAUDE.md names; reusing the guarded one is how that is avoided.
      meta: {
        dealStages: vi.fn().mockResolvedValue(DEAL_STAGE_CATALOG),
      },
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
        // Slice C2b moved the revoke-close-confirm control into the "การเงิน"
        // tab (see TicketDetailPage.jsx's own comment on that section) —
        // Slice E's DealMoneyTimeline rebuild left it untouched, in place,
        // outside that new component.
        revokeCloseConfirmation: vi.fn(),
        // Fulfilment (Phase 3 Slice S4 — handoff 105): DealFulfilmentPanel's
        // own mutations, same self-contained pattern.
        issueImportRequest: vi.fn(),
        markIrSent: vi.fn(),
        markShipping: vi.fn(),
        markGoodsReceived: vi.fn(),
      },
      attachments: {
        list: vi.fn(),
        // upload/delete were previously unmocked because nothing exercised them.
        // The "attachment upload reports back" guard below does — see its own
        // comment for the TypeError it exists to catch.
        upload: vi.fn(),
        delete: vi.fn(),
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
      // The items table converts a foreign-currency factory price to baht. Mocked because an
      // unmocked namespace makes every fxRates.list() call throw (api.fxRates is undefined),
      // which would silently leave the baht line absent rather than exercising it.
      fxRates: {
        list: vi.fn(),
      },
      // ใบขอซื้อ (F-SM-001). Mocked for the same reason as fxRates: DealFulfilmentPanel's IR block
      // queries brands for every import/CEO viewer, and an undefined namespace would throw rather
      // than render the block.
      importRequests: {
        brands: vi.fn(),
        pages: vi.fn(),
        download: vi.fn(),
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

// Ticket-detail IA rebuild Phase 2: every section this file used to find
// straight off the page now lives inside a tab (Tabs.jsx keeps only the
// active one mounted — see TicketDetailPage.jsx's own "runOnTab" doc
// comment). `namePattern` matches the tab BUTTON's accessible name, which
// concatenates the Thai label and its English helper span with no inserted
// whitespace in jsdom (no real layout, so no implied word boundary) — a
// regex on the Thai label alone (e.g. `/ราคา/`) is enough and avoids
// depending on that concatenation's exact shape.
async function openTab(namePattern) {
  fireEvent.click(await screen.findByRole('tab', { name: namePattern }));
}

// Exposes the current router location's search string as text, so a test
// can assert `?tab=` was actually written (and with `replace: true`) without
// reaching into react-router's internals.
function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location-probe">{location.pathname}{location.search}</div>;
}

function renderTicketDetailPageAtRoute(initialEntries, user = ceoUser, showToast = vi.fn()) {
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

describe('TicketDetailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Default: no brands. Tests that care about the ใบขอซื้อ block set their own — a default with
    // brands would make the block appear in unrelated assertions.
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
      // GENERATE_QUOTATION/MARK_QUOTATION_* are retired (Phase 2 Slice S1/S2) — the
      // real actions() endpoint never advertises them any more either.
      availableActions: [],
    });
    api.attachments.list.mockResolvedValue({ attachments: [] });
    api.attachments.upload.mockResolvedValue({ attachment: { id: 9, fileName: 'po.pdf' } });
    api.attachments.delete.mockResolvedValue({});
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
    // FX rates back the baht companion beside a foreign-currency factory price. Defaults to an
    // empty table — no rate, so no conversion — which keeps every unrelated test's item rows
    // showing exactly one figure; the conversion test below supplies real rates itself.
    api.fxRates.list.mockResolvedValue({ fxRates: [] });
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
    // S4's Thai copy narrowed to the designer alone when V143 gave the owner their own stage (S5).
    expect(screen.getAllByText('เสนอราคาผู้ออกแบบ').length).toBeGreaterThan(0);
    expect(screen.getByText('มูลค่าดีล')).not.toBeNull();
    // The payment panel below also renders amountPayable — assert presence, not uniqueness.
    expect(screen.getAllByText('฿50,000.00').length).toBeGreaterThan(0);
  });

  it('folds updated-at into the header and does not re-render the duplicate overview summary panel', async () => {
    renderTicketDetailPage();

    const header = await screen.findByTestId('deal-state-header');
    expect(within(header).getByText('อัปเดตล่าสุด', { exact: false })).not.toBeNull();
    expect(within(header).getByText('2 ก.ค. 2569')).not.toBeNull();
    expect(screen.queryByRole('heading', { level: 2, name: 'ข้อมูลทั่วไป' })).toBeNull();
    // Slice C2b: the items table now lives in its own "สินค้าและราคา" tab,
    // not the default "ดีล" tab.
    await openTab(/สินค้าและราคา/);
    expect(await screen.findByRole('heading', { level: 2, name: /^รายการสินค้า/ })).not.toBeNull();
  });

  it('pins the refresh button out of the mobile flex-wrap flow', async () => {
    renderTicketDetailPage();

    const header = await screen.findByTestId('deal-state-header');
    const refresh = within(header).getByRole('button', { name: 'รีเฟรช' });
    expect(refresh.className).toContain('mobile:absolute');
    expect(refresh.className).toContain('mobile:right-0');
    expect(refresh.className).toContain('mobile:top-0');
    expect(header.querySelector('.mobile\\:pr-12')).not.toBeNull();
  });

  it('keeps the ticket header and tabs in one measured sticky chrome for focus-safe offsets', async () => {
    renderTicketDetailPage();

    const stickyChrome = await screen.findByTestId('ticket-detail-sticky-chrome');
    expect(stickyChrome.className).toContain('sticky');
    expect(stickyChrome.className).toContain('top-[calc(var(--deal-scroll-pad-y)*-1)]');
    expect(within(stickyChrome).getByRole('tablist', { name: 'รายละเอียดดีล' })).not.toBeNull();

    // Ticket-workspace IA rebuild Slice B: the sticky context rail (and its own
    // measured `--deal-header-h` sticky-offset classes) is retired — there is
    // no second column any more, so no `data-testid="ticket-context-rail"`
    // element exists at all. `--deal-header-h` itself stays defined (index.css)
    // and consumed by AppShell.jsx's `scroll-pt-[...]` (Tailwind port of the
    // old `.content-scroll` rule) for mobile focus scrolling — see
    // ticketWorkspaceLayout.test.js, unaffected by this.
    expect(screen.queryByTestId('ticket-context-rail')).toBeNull();
  });

  it('renders a dash for มูลค่าดีล until a price exists', async () => {
    api.tickets.get.mockResolvedValueOnce({
      ticket: buildTicket({ summary: { status: 'approved', amountPayable: 0 } }),
    });

    renderTicketDetailPage();

    const label = await screen.findByText('มูลค่าดีล');
    const chip = label.closest('div');
    expect(within(chip).getByText('—')).not.toBeNull();
    expect(within(chip).queryByText('฿0.00')).toBeNull();
  });

  // The ราคาตั้ง (ประมาณการ) column that used to sit here — catalog price × FX × a CEO-configured
  // markup — was removed on the owner's instruction after UAT, together with dealEstimatePricing.js.
  // What replaces it on this page is not another estimate: the factory price already shown in
  // ราคาโรงงาน now carries a plain baht conversion beside it when it is quoted in a foreign
  // currency. The live catalog is EUR + USD only, and sales.fx_rates covers both.
  describe('factory price in baht (ราคาตั้ง estimate removed)', () => {
    it('shows the factory price in its own currency with a baht conversion beside it', async () => {
      // The suite-wide default is an empty rate table (no conversion possible); this test is
      // specifically about the converted figure, so it supplies the real prod USD rate.
      api.fxRates.list.mockResolvedValue({
        fxRates: [{ currency: 'THB', rateToThb: 1 }, { currency: 'USD', rateToThb: 35.2 }],
      });
      api.tickets.get.mockResolvedValueOnce({
        ticket: buildTicket({
          items: [
            {
              id: 70101, brand: 'Bode', model: 'Stone gallary', qty: 10, qtySqm: 7.2,
              unitBasis: 'SQM', qtyDelivered: 0, qtyFromStock: 0,
              proposedPrice: null, approvedPrice: null,
              rawPrice: 8.8, rawCurrency: 'USD', rawUnit: 'sqm',
              // calcedCost is what makes the CEO's ราคาโรงงาน breakdown columns render at all.
              calcedCost: 320, calcedPrice: 450,
            },
          ],
        }),
      });

      renderTicketDetailPage();
      await openTab(/สินค้าและราคา/);

      // 8.80 USD at 35.20 THB/USD = 309.76 บาท — a pure conversion, with no markup applied.
      expect(await screen.findByText('8.80')).not.toBeNull();
      expect(await screen.findByText('≈ 309.76 บาท/ตร.ม.')).not.toBeNull();
    });

    it('never renders the ราคาตั้ง column', async () => {
      api.tickets.get.mockResolvedValueOnce({
        ticket: buildTicket({
          items: [
            { id: 70101, brand: 'Custom', model: 'Line', qty: 5, qtyDelivered: 0, qtyFromStock: 0,
              approvedPrice: null, rawPrice: null, rawCurrency: null },
          ],
        }),
      });

      renderTicketDetailPage();
      await openTab(/สินค้าและราคา/);

      await screen.findByText('Custom');
      expect(screen.queryByText('ราคาตั้ง')).toBeNull();
      expect(screen.queryByText(/ยังคำนวณไม่ได้/)).toBeNull();
    });
  });

  // Ticket-workspace IA rebuild Slice B ("retire the context rail, one comment
  // composer"): วันสำคัญ / ผู้เกี่ยวข้อง moved here verbatim from the deleted
  // TicketContextPanel.jsx sticky rail — same fields, same labels, same
  // assignedImport role-scoped readout — now at the top of ภาพรวม instead of
  // behind an always-visible sidebar (there is no more xl-collapse/mobile
  // accordion to drive: the fields simply render whenever this tab is active).
  it('renders วันสำคัญ and ผู้เกี่ยวข้อง fields at the top of the ภาพรวม tab', async () => {
    api.tickets.get.mockResolvedValueOnce({
      ticket: buildTicket({
        summary: {
          lifecycle: 'ACTIVE',
          salesStage: 'QUOTE_DESIGN_SIDE',
          nextFollowUpAt: '2026-07-20',
          billingDate: '2026-07-21',
          dueDate: '2026-07-31',
          contactName: 'คุณอรุณ ติดต่อ',
        },
        // A COMMENTED event on the ticket — planted specifically to prove the
        // rail's old read-only "3 most recent comments" roll-up is genuinely
        // gone (DealHistoryPanel already renders the full stream on กิจกรรม),
        // not merely relocated: this message must NOT surface on ภาพรวม.
        events: [
          {
            id: 3,
            kind: 'COMMENTED',
            actorName: 'สมชาย ใจดี',
            message: 'บันทึกสำหรับบริบทดีล',
            createdAt: '2026-07-18T09:00:00.000Z',
          },
        ],
      }),
    });

    renderTicketDetailPage(salesOwnerUser);

    const keyDatesHeading = await screen.findByRole('heading', { level: 2, name: /วันสำคัญ/ });
    const keyDatesSection = keyDatesHeading.closest('section');
    expect(within(keyDatesSection).getByText('20 ก.ค. 2569')).not.toBeNull();
    expect(within(keyDatesSection).getByText('31 ก.ค. 2569')).not.toBeNull();

    const peopleHeading = screen.getByRole('heading', { level: 2, name: /ผู้เกี่ยวข้อง/ });
    const peopleSection = peopleHeading.closest('section');
    // สมชาย ใจดี is also named in DealStateHeader's "สร้างโดย" line (always on
    // screen) — assert presence in THIS section specifically, not page-wide
    // uniqueness.
    expect(within(peopleSection).getByText('สมชาย ใจดี')).not.toBeNull();
    expect(within(peopleSection).getByText('ยังไม่มีคำขอราคา')).not.toBeNull();
    expect(within(peopleSection).getByText('คุณอรุณ ติดต่อ')).not.toBeNull();

    // Wrong-way-round: the deleted roll-up's content must be absent from the
    // whole page while ภาพรวม is active, not just out of these two sections.
    expect(screen.queryByText('บันทึกสำหรับบริบทดีล')).toBeNull();
  });

  // The core defect this slice fixes: a comment composer that rendered from
  // TWO different components depending on which tab was open (the context
  // rail's own copy everywhere except กิจกรรม, DealHistoryPanel's copy on
  // กิจกรรม). DealHistoryPanel is now the only composer in the codebase, and
  // — because Tabs.jsx unmounts every inactive TabPanel — it is reachable
  // from exactly the one tab it lives in. Checking both tabs (not just one)
  // is what the mutation-check below actually exercises: reintroducing the
  // old rail-shaped duplicate makes the ภาพรวม assertion (0) go red.
  it('renders the comment composer exactly once, only inside the ประวัติ tab', async () => {
    renderTicketDetailPage(salesOwnerUser);
    await screen.findByRole('heading', { level: 1, name: 'บริษัท ทดสอบ จำกัด' });

    // ดีล is the default tab — wrong-way-round: no composer anywhere on
    // the page while it's active (this is what used to fail: the rail's own
    // copy rendered here).
    expect(screen.queryAllByPlaceholderText('เพิ่มความคิดเห็น…')).toHaveLength(0);
    expect(screen.queryAllByRole('button', { name: 'ส่งความคิดเห็น' })).toHaveLength(0);

    await openTab(/ประวัติ/);
    expect(screen.getAllByPlaceholderText('เพิ่มความคิดเห็น…')).toHaveLength(1);
    expect(screen.getAllByRole('button', { name: 'ส่งความคิดเห็น' })).toHaveLength(1);
  });

  it('posts a ประวัติ-tab comment through the shared commentText/handleComment path', async () => {
    renderTicketDetailPage(salesOwnerUser);

    await openTab(/ประวัติ/);
    fireEvent.change(screen.getByPlaceholderText('เพิ่มความคิดเห็น…'), { target: { value: 'จดไว้จากแท็บกิจกรรม' } });
    fireEvent.click(screen.getByRole('button', { name: 'ส่งความคิดเห็น' }));

    await waitFor(() => expect(api.tickets.comment).toHaveBeenCalledWith(701, { message: 'จดไว้จากแท็บกิจกรรม' }));
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
    await openTab(/เอกสาร/);

    // Scoped to DealLegacyQuotations' own panel (not `screen` globally):
    // Slice D's DealDocumentRegister now ALSO lists these same legacy rows
    // in its own roll-up, in the same tab — see DealDocumentRegister's own
    // header comment ("a register is an index, not a replacement for the
    // panel it indexes") — so an unscoped query now matches twice.
    const legacyHeading = await screen.findByRole('heading', { level: 2, name: 'ใบเสนอราคา (เอกสารเดิม)' });
    const legacy = within(legacyHeading.closest('section'));
    expect(legacy.getByText('ผู้ออกแบบ')).not.toBeNull();
    expect(legacy.getByText('เจ้าของ')).not.toBeNull();
    expect(legacy.getByText('QT-2026-0901')).not.toBeNull();
    expect(legacy.getByText('QT-2026-0902')).not.toBeNull();
    expect(legacy.queryByRole('button', { name: 'ส่งแล้ว' })).toBeNull();
    expect(legacy.queryByRole('button', { name: 'รับแล้ว' })).toBeNull();
    expect(legacy.queryByRole('button', { name: 'ปฏิเสธ' })).toBeNull();
    expect(legacy.queryByRole('button', { name: /Revise/ })).toBeNull();
    // Download stays — legacy quotations remain reachable, just read-only.
    expect(legacy.getAllByRole('button', { name: /PDF/ }).length).toBeGreaterThan(0);
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
    await openTab(/การเงิน/);

    expect((await screen.findAllByText('ชำระบางส่วน')).length).toBeGreaterThan(0);
    expect(screen.getAllByText('เกินกำหนด').length).toBeGreaterThan(0);
    // DealStateHeader's "มูลค่าดีล" chip also renders amountPayable — assert presence, not uniqueness.
    expect(screen.getAllByText('฿1,000.00').length).toBeGreaterThan(0);
    // Scoped to the "ชำระแล้ว"/"คงเหลือ" summary tiles specifically: by the
    // time this async assertion runs, the receipt-history row below (also
    // ฿400.00, same receipt) has settled in too — dom-testing-library's default
    // text matcher only looks at an element's own direct text children, so
    // the receipt row's outer wrapper (a bare "฿400.00" text node next to a
    // sibling <small>) matches the same string as the summary tile. A bare
    // `getByText('฿400.00')` is therefore genuinely ambiguous on this page, not
    // a test bug to paper over with getAllByText/greaterThan(0).
    const paidTile = screen.getByText('ชำระแล้ว').parentElement;
    expect(within(paidTile).getByText('฿400.00')).not.toBeNull();
    const outstandingTile = screen.getByText('คงเหลือ').parentElement;
    expect(within(outstandingTile).getByText('฿600.00')).not.toBeNull();
    expect(await screen.findByText('DEPOSIT')).not.toBeNull();
    // The receipt row itself really did render (not just the tile) — proves
    // the scoped assertion above didn't accidentally start passing vacuously.
    expect(screen.getByText(/คุณบัญชี/)).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'บันทึกรับชำระเงิน' })).toBeNull();
  });

  // Slice C2b moved this control into the การเงิน tab (see
  // TicketDetailPage.jsx's own comment above the `can.revokeCloseConfirm`
  // section) — Slice E's DealMoneyTimeline rebuild left it in place, outside
  // that new component, and untouched. Not previously covered by a dedicated
  // test; adding one here per the "every existing action must still be
  // present and behave identically" requirement.
  it('renders "ยกเลิกการยืนยันปิดงาน" for account with REVOKE_CLOSE_CONFIRM, and calls revokeCloseConfirmation on click', async () => {
    api.tickets.actions.mockResolvedValueOnce({
      currentState: {
        lifecycle: 'ACTIVE', salesStage: 'CLOSED_PAID', paymentStatus: 'FULLY_PAID', fulfillmentStatus: 'FULLY_DELIVERED', status: 'quotation_issued',
      },
      availableActions: [{ action: 'REVOKE_CLOSE_CONFIRM', kind: 'operational', label: 'ยกเลิกการยืนยันปิดงาน' }],
    });
    api.tickets.revokeCloseConfirmation.mockResolvedValue({ ticket: buildTicket() });

    renderTicketDetailPage(accountUser);
    await openTab(/การเงิน/);

    const button = await screen.findByRole('button', { name: 'ยกเลิกการยืนยันปิดงาน' });
    fireEvent.click(button);

    await waitFor(() => expect(api.tickets.revokeCloseConfirmation).toHaveBeenCalledWith(701, {}));
  });

  // Wrong-way-round: without the REVOKE_CLOSE_CONFIRM action on offer, the
  // button must not render at all — even for a role (account) that CAN see
  // it once the action is actually available.
  it('wrong-way-round: hides "ยกเลิกการยืนยันปิดงาน" for account when REVOKE_CLOSE_CONFIRM is not an available action', async () => {
    api.tickets.actions.mockResolvedValueOnce({
      currentState: {
        lifecycle: 'ACTIVE', salesStage: 'DEPOSIT_RECEIVED', paymentStatus: 'DEPOSIT_PAID', fulfillmentStatus: null, status: 'quotation_issued',
      },
      availableActions: [{ action: 'SET_BILLING', kind: 'payment', label: 'ตั้งค่าการวางบิล' }],
    });

    renderTicketDetailPage(accountUser);
    await openTab(/การเงิน/);

    await screen.findByRole('button', { name: 'ตั้งค่าการวางบิล' });
    expect(screen.queryByRole('button', { name: 'ยกเลิกการยืนยันปิดงาน' })).toBeNull();
    expect(api.tickets.revokeCloseConfirmation).not.toHaveBeenCalled();
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
    // Slice C2b: PricingRequestPanel's whole-tab gate is gone — it merged
    // into "สินค้าและราคา" (unconditionally visible) as an INNER render
    // condition (pricing_accountCannotReadAPricingRequest — see
    // ticketDetailTabs.js's own comment on that tab). Account still gets
    // the tab (the items table lives there too), just not this panel inside
    // it, and never the fetch behind it.
    await openTab(/สินค้าและราคา/);
    expect(screen.queryByRole('heading', { name: 'คำขอราคา' })).toBeNull();
    expect(api.pricingRequests.listForTicket).not.toHaveBeenCalled();

    await openTab(/การเงิน/);
    expect(await screen.findByText('DEPOSIT')).not.toBeNull();
    expect(screen.getAllByText('฿400.00').length).toBeGreaterThan(0);
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
    expect(screen.getAllByText('฿32,500.00').length).toBeGreaterThan(0);

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

    // Slice A "chip diet": the deal pipeline panel (DealStagePanel, outside
    // every tab) used to also show this coarse progress — that copy was
    // removed as a straight duplicate of DealFulfilmentPanel's own aggregate
    // (see DealStagePanel.jsx's own doc comment), so it, the delivery HISTORY
    // rows (WAREHOUSE source), and the record-delivery button are all only
    // inside DealFulfilmentPanel now, in the "จัดซื้อ-ส่งมอบ" tab.
    await openTab(/จัดซื้อ-ส่งมอบ/);
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
    // The comment box now lives in DealHistoryPanel, in the "ประวัติ" tab.
    await openTab(/ประวัติ/);

    const textarea = screen.getByPlaceholderText('เพิ่มความคิดเห็น…');
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
    // "บันทึกรับชำระเงิน" lives in the "การเงิน" tab's payment section now —
    // the Modal itself, once opened, stays mounted regardless of the active
    // tab (it's not inside any TabPanel).
    await openTab(/การเงิน/);

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
    // DealFulfilmentPanel now lives in the "จัดซื้อ-ส่งมอบ" tab.
    await openTab(/จัดซื้อ-ส่งมอบ/);

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

    // Slice C2b: "แก้ไขรายการสินค้า" now sits in the items table's own panel
    // header, inside the "สินค้าและราคา" tab (not the default "ดีล" tab).
    await openTab(/สินค้าและราคา/);
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

  it('revise form: opens as a modal, the confirm button is disabled on a blank reason (pre-existing guard, unchanged), and submits once filled', async () => {
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

    // ขอแก้ไข collapsed into the header's "⋯" overflow menu
    // (ticket-detail IA rebuild Phase 1) — open the menu, then its item.
    fireEvent.click(await screen.findByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: /ขอแก้ไข/ }));

    // Slice C2a: the form is now a real Modal (role="dialog", aria-modal),
    // not an inline page section — this is the headline change for this test.
    const dialog = await screen.findByRole('dialog', { name: 'ขอแก้ไข' });
    const confirmButton = await within(dialog).findByRole('button', { name: 'ยืนยันขอแก้ไข' });

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
    // Success closes the modal (resetActionDrafts sets showReviseForm false),
    // same as every other action modal on this page.
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'ขอแก้ไข' })).toBeNull());
  });

  // The actual defect this slice fixes: opening ขอแก้ไข from the header's "⋯"
  // overflow menu (which sits outside every tab) used to force a tab switch
  // to "ภาพรวม" via runOnTab('overview', …) before scrolling to the inline
  // form — silently moving the viewer off whatever tab they were looking at.
  // Now that the form is a modal, handleOpenRevise no longer touches the
  // active tab at all. A test that only checks the form/modal appears cannot
  // see this regression — it must assert the URL's ?tab= is unchanged.
  it('opening ขอแก้ไข from the overflow menu does NOT change the active tab', async () => {
    api.tickets.get.mockResolvedValue({
      ticket: buildTicket({ summary: { status: 'approved', createdById: 1 } }),
    });
    api.tickets.actions.mockResolvedValue({
      currentState: {
        lifecycle: 'ACTIVE', salesStage: 'QUOTE_DESIGN_SIDE', paymentStatus: null, fulfillmentStatus: null, status: 'approved',
      },
      availableActions: [],
    });

    renderTicketDetailPageAtRoute(['/tickets/701?tab=money'], salesOwnerUser);

    const moneyTab = await screen.findByRole('tab', { name: /^การเงิน/ });
    await waitFor(() => expect(moneyTab.getAttribute('aria-selected')).toBe('true'));

    fireEvent.click(await screen.findByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: /ขอแก้ไข/ }));

    // The modal opened…
    await screen.findByRole('dialog', { name: 'ขอแก้ไข' });
    // …but the URL's ?tab= and the tab's aria-selected state are untouched.
    expect(screen.getByTestId('location-probe').textContent).toContain('tab=money');
    expect(moneyTab.getAttribute('aria-selected')).toBe('true');
    // Wrong-way-round: the tab this used to force a switch TO is not the one
    // now selected.
    expect(screen.getByRole('tab', { name: /^ดีล/ }).getAttribute('aria-selected')).not.toBe('true');
  });

  // Ticket-detail IA rebuild Phase 1 clutter follow-up (FIX 1): CREATE_PCR
  // used to render as a "scroll to PricingRequestPanel" sticky button while
  // that panel ALSO rendered its own "สร้างคำขอราคา" button — the same label,
  // visible twice at once (the sticky bar never scrolls out of view). The
  // sticky bar now opens PricingRequestPanel's create modal directly via its
  // forwardRef, and the panel renders no button of its own.
  describe('sticky header primary CTA — CREATE_PCR owns "สร้างคำขอราคา" alone', () => {
    it('renders exactly one "สร้างคำขอราคา" control (the sticky primary), and clicking it opens the PCR panel\'s own create modal', async () => {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({ summary: { lifecycle: 'ACTIVE', salesStage: 'LEAD_APPROACH', createdById: 1 } }),
      });
      api.tickets.actions.mockResolvedValue({
        currentState: { lifecycle: 'ACTIVE', salesStage: 'LEAD_APPROACH', paymentStatus: null, fulfillmentStatus: null, status: 'price_proposed' },
        availableActions: [],
      });
      api.pricingRequests.listForTicket.mockResolvedValue({ items: [] });

      renderTicketDetailPage(salesOwnerUser);

      const stickyButtons = await screen.findAllByRole('button', { name: /สร้างคำขอราคา/ });
      expect(stickyButtons).toHaveLength(1);
      expect(screen.queryByRole('dialog')).toBeNull();
      // No leftover "ถึงคิวคุณ" banner text anywhere — the CTA button above
      // stands alone now (see TicketDetailPage.jsx's bannerText comment).
      expect(screen.queryByText(/ถึงคิวคุณ/)).toBeNull();
      // …and the bannerless bar keeps its MOBILE chrome. It is
      // `mobile:fixed inset-x-0 bottom-0` over scrolling content, so losing
      // the background/border there would leave an unreadable transparent bar
      // — the one failure mode of shedding the desktop chrome, and invisible
      // to every other assertion in this file.
      const [actionBar] = screen.getAllByTestId('ticket-action-bar');
      expect(actionBar.className).toContain('mobile:bg-surface');
      expect(actionBar.className).toContain('mobile:border-t');
      expect(actionBar.className).not.toContain('bg-info-bg');

      fireEvent.click(stickyButtons[0]);

      expect(await screen.findByRole('dialog')).not.toBeNull();
      // Still exactly one "สร้างคำขอราคา" trigger even with the modal open —
      // the modal's own title text uses the same string, so this scopes to
      // buttons only (not headings) to keep proving "no duplicate button".
      expect(screen.getAllByRole('button', { name: /สร้างคำขอราคา/ })).toHaveLength(1);
    });
  });

  // The counterpart to the rule above, and the reason bannerText is not simply
  // nulled whenever a CTA exists: the four `can.*`-gated primaries carry a
  // DESCRIPTIVE sentence (NEXT_ACTION_STEPS) that their terse button label does
  // not repeat. Dropping those lines would delete the precondition and the
  // consequence from the page — and, since the ticket-workspace IA rebuild
  // Slice B retired the context rail that used to carry this line as a
  // fallback, there is no other surface left on the page to say it.
  describe('sticky header primary CTA — the can.* primaries KEEP their descriptive line (prefix-free)', () => {
    it('shows "ฝ่ายบัญชียืนยันแล้ว — ตรวจสอบและปิดงานได้เลย" next to the CEO\'s terse "ตรวจสอบและปิดงาน" button, with no ถึงคิวคุณ prefix', async () => {
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

      renderTicketDetailPage(ceoUser);

      expect(await screen.findByTestId('ticket-detail-verify-close')).not.toBeNull();
      expect(screen.getAllByText('ฝ่ายบัญชียืนยันแล้ว — ตรวจสอบและปิดงานได้เลย').length).toBeGreaterThan(0);
      expect(screen.queryByText(/ถึงคิวคุณ/)).toBeNull();
    });
  });

  // Ticket-detail IA rebuild Phase 1 clutter follow-up round 2 (FIX 2): the
  // previous pass only de-duplicated "สร้างคำขอราคา" — an independent review
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

      const stickyButtons = await screen.findAllByRole('button', { name: /ออกใบเสนอราคา/ });
      expect(stickyButtons).toHaveLength(1);

      // DealQuotationPanel now lives inside the "เอกสาร" tab, mounted
      // (and its own quotationsQuery fetching) only once that tab is
      // active — open it and wait for the query to settle BEFORE clicking
      // the sticky button, so the click's own runOnTab (a no-op here, we're
      // already on the right tab) exercises openIssueQuotation's real
      // "existing draft" branch rather than racing quotationsQuery.
      await openTab(/เอกสาร/);
      await screen.findByText(/พร้อมออกใบเสนอราคาแล้ว/);

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
      await waitFor(() => {
        expect(screen.getByTestId('ticket-primary-action').getAttribute('data-action')).toBe('issue_quotation');
      });
      const stickyButton = screen.getByTestId('ticket-primary-action');
      // DealQuotationPanel now lives inside the "เอกสาร" tab — open it
      // (mounting the panel, starting its own quotationsQuery) BEFORE
      // clicking the sticky button, so the click's own runOnTab (a no-op,
      // we're already there) exercises openIssueQuotation for real instead
      // of a cross-tab jump racing a query that hasn't even started yet.
      // Also wait for DealQuotationPanel's own quotationsQuery to settle
      // empty — this is the exact "quotationsQuery.isSuccess" state
      // openIssueQuotation's create branch requires; without this wait the
      // click could race the query and land on the "still loading" toast
      // branch instead.
      await openTab(/เอกสาร/);
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

      // Open the "เอกสาร" tab (DealQuotationPanel's home) first, then
      // wait for the "draft ready" hint under it — proves the quotations
      // query has actually settled before we click. (The hint is one <p>
      // whose full text also includes the rest of the sentence, so this
      // matches on a substring rather than requiring an exact match.)
      await openTab(/เอกสาร/);
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

      await waitFor(() => {
        expect(screen.getByTestId('ticket-primary-action').getAttribute('data-action')).toBe('issue_quotation');
      });
      const stickyButton = screen.getByTestId('ticket-primary-action');
      // Open the "เอกสาร" tab, then wait for the quotations query to
      // settle so the click below exercises the "current exists but isn't
      // editable" branch, not the still-loading branch (which would show a
      // different toast). The "บันทึกผลจากลูกค้า" outcome section only
      // renders once the ISSUED quotation has actually landed
      // (canRecordCustomerQuotationOutcome requires docStatus === 'ISSUED'),
      // so waiting for it is a faithful proxy for "the query settled".
      await openTab(/เอกสาร/);
      await screen.findByText('บันทึกผลจากลูกค้า');

      fireEvent.click(stickyButton);

      await waitFor(() => expect(showToast).toHaveBeenCalledWith(
        'error', 'ยังออกใบเสนอราคาไม่ได้ — ตรวจสอบสถานะคำขอราคาในส่วน "ราคาและใบเสนอราคา" ด้านล่าง',
      ));
      expect(api.pricingRequests.createCustomerQuotation).not.toHaveBeenCalled();
      expect(api.pricingRequests.issueCustomerQuotation).not.toHaveBeenCalled();
    });

    // FIX 3 (Opus review — "cross-tab issue_quotation first-click dead end"):
    // every test above deliberately opens the "เอกสาร" tab (and waits for
    // DealQuotationPanel's own quotationsQuery to settle) BEFORE clicking the
    // sticky button, which the review pointed out makes `runOnTab` a no-op in
    // every one of them — none of them exercise the actual cross-tab path.
    // This test drives the click from ภาพรวม (the real default tab on a
    // session's first visit to a deal), so `runOnTab('quotations', ...)`
    // genuinely switches tabs AND mounts DealQuotationPanel for the first
    // time in the same commit the click's queued action tries to run. Before
    // the fix, that raced quotationsQuery — `openIssueQuotation` reads
    // `quotationsQuery.isSuccess` before the panel's own fetch had a chance
    // to resolve, and landed on the "กำลังโหลดข้อมูลใบเสนอราคา — กรุณาลองอีกครั้ง"
    // toast, doing nothing (see DealQuotationPanel.jsx's own FIX 3 comment for
    // the fix: the queued intent now retries automatically once the query
    // settles, instead of dead-ending on the first commit after the switch).
    it('creates and issues the quotation when clicked directly from ภาพรวม — the cross-tab race (regression, no openTab pre-call)', async () => {
      approvedForQuotationTicket();
      api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [] });
      api.pricingRequests.createCustomerQuotation.mockResolvedValue({
        quotation: { id: 777, docStatus: 'DRAFT', quotationRevisionNo: 1 },
      });
      api.pricingRequests.issueCustomerQuotation.mockResolvedValue({
        quotation: { id: 777, docStatus: 'ISSUED', quotationRevisionNo: 1 },
      });
      const showToast = vi.fn();

      renderTicketDetailPage(salesOwnerUser, showToast);

      await waitFor(() => {
        expect(screen.getByTestId('ticket-primary-action').getAttribute('data-action')).toBe('issue_quotation');
      });
      // Deliberately NOT calling openTab(/เอกสาร/) here — the sticky
      // button is clicked while still on the default ภาพรวม tab, which is
      // the exact precondition the review's repro names ("on the default
      // ภาพรวม tab, first visit of a session").
      const stickyButton = screen.getByTestId('ticket-primary-action');

      fireEvent.click(stickyButton);

      // The click's own runOnTab actually switches tabs this time (unlike
      // every test above) — DealQuotationPanel mounts as a result.
      expect(await screen.findByTestId('deal-quotation-panel')).not.toBeNull();

      await waitFor(() => expect(api.pricingRequests.createCustomerQuotation).toHaveBeenCalledWith(
        501, expect.objectContaining({ clientRequestId: expect.any(String) }),
      ));
      await waitFor(() => expect(api.pricingRequests.issueCustomerQuotation).toHaveBeenCalledWith(
        777, expect.objectContaining({ clientRequestId: expect.any(String) }),
      ));
      await waitFor(() => expect(showToast).toHaveBeenCalledWith('success', 'สร้างร่างและออกใบเสนอราคาลูกค้าแล้ว'));
      // The dead-end symptom itself must never fire.
      expect(showToast).not.toHaveBeenCalledWith('error', 'กำลังโหลดข้อมูลใบเสนอราคา — กรุณาลองอีกครั้ง');
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

      expect((await screen.findAllByText(/รอชำระมัดจำ/)).length).toBeGreaterThan(0);
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
  // Slice C2b moved DealTrackingPanel from the old "activity" tab into "ดีล"
  // (the default tab — see ticketDetailTabs.js's own comment on that tab),
  // so these no longer call openTab at all.
  describe('deal tracking panel', () => {
    it('shows the section and the SERVER win%; the pre-emptive gate hint now sits next to the advance button, not here', async () => {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({
          // Issue #738: the panel now renders TicketSummaryDto.effectiveWinProbability off the
          // payload instead of re-deriving it from a copied stage→% table, so the fixture must
          // supply it. 37 is deliberately a value NO stage default holds — if this ever reads 40
          // again the panel has gone back to deriving from QUOTE_DESIGN_SIDE, and if it reads 0
          // the field stopped being read at all. Both are visible failures rather than a number
          // that happens to agree.
          summary: { salesStage: 'QUOTE_DESIGN_SIDE', effectiveWinProbability: 37 },
        }),
      });

      renderTicketDetailPage(ceoUser);

      expect(await screen.findByRole('heading', { level: 2, name: 'การติดตามดีล' })).not.toBeNull();
      expect(await screen.findByText('ยังไม่พร้อม')).not.toBeNull();
      expect(screen.getByText('37%')).not.toBeNull();
      expect(screen.queryByText('40%')).toBeNull();
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

    // The add-activity form lives in DealHistoryPanel, not DealTrackingPanel
    // (ticket-detail IA rebuild Phase 2's merged กิจกรรม tab). Slice C2b split
    // the two panels across DIFFERENT tabs (DealTrackingPanel -> "ดีล",
    // DealHistoryPanel -> "ประวัติ"), so this form is only reachable from
    // "ประวัติ" now.
    it('submits a new activity via api.tickets.addActivity', async () => {
      renderTicketDetailPage(ceoUser);
      await openTab(/ประวัติ/);
      await screen.findByRole('heading', { level: 2, name: 'ประวัติดีล' });

      fireEvent.change(screen.getByLabelText('บันทึก (ถ้ามี)'), { target: { value: 'โทรคุยเรื่องราคา' } });
      fireEvent.click(screen.getByRole('button', { name: 'บันทึกกิจกรรม' }));

      await waitFor(() => expect(api.tickets.addActivity).toHaveBeenCalledTimes(1));
      expect(api.tickets.addActivity.mock.calls[0][0]).toBe(701);
      expect(api.tickets.addActivity.mock.calls[0][1]).toMatchObject({ kind: 'CALL', note: 'โทรคุยเรื่องราคา' });
    });

    // FIX 1 (Opus review, owner decision — supersedes the old "no tab at
    // all" assertion): account now GETS the "ประวัติ" tab (the audit trail +
    // comment box are backed by requireViewAccess, which account passes),
    // but DealTrackingPanel ("การติดตามดีล") and the activities fetch stay
    // gated on requireDealOwnership — see ticketDetailTabs.js's own doc
    // comment on the "history" tab and TicketDetailPage.jsx's doc comment on
    // the "deal"/"history" TabPanels for the split. Slice C2b split these
    // two assertions across two DIFFERENT tabs: DealTrackingPanel now lives
    // on the default "ดีล" tab (still gated, still absent for account), and
    // the audit trail lives on "ประวัติ".
    it('account gets the "ประวัติ" tab (FIX 1) but not the deal-tracking panel or the activity feed fetch', async () => {
      renderTicketDetailPage(accountUser);

      await screen.findByRole('heading', { level: 1, name: 'บริษัท ทดสอบ จำกัด' });
      // Default "ดีล" tab: DealTrackingPanel stays gated.
      expect(screen.queryByRole('heading', { level: 2, name: 'การติดตามดีล' })).toBeNull();
      expect(api.tickets.listActivities).not.toHaveBeenCalled();

      // "ประวัติ" tab: the plain audit trail (this ticket's one seeded
      // SUBMITTED event) still renders — proves the tab isn't a shell with
      // nothing in it.
      expect(screen.queryByRole('tab', { name: /ประวัติ/ })).not.toBeNull();
      await openTab(/ประวัติ/);
      expect(await screen.findByRole('heading', { level: 2, name: 'ประวัติดีล' })).not.toBeNull();
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
        // QUOTE_OWNER, not OWNER_SIGNOFF: V143 inserted S5 between S4 and S6, so the stage after
        // QUOTE_DESIGN_SIDE moved. This fixture said OWNER_SIGNOFF and stayed green for as long as
        // the frontend carried its own 14-stage list — the same drift the catalog endpoint ends.
        availableActions: [{ action: 'ADVANCE_STAGE', targetStage: 'QUOTE_OWNER' }],
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
      await waitFor(() => expect(api.tickets.updateStage).toHaveBeenCalledWith(701, { stage: 'QUOTE_OWNER' }));
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
        items: [{ ...approvedPr, status: 'AWAITING_FACTORY_RESPONSE' }],
      });

      renderTicketDetailPage(salesOwnerUser);

      await screen.findByRole('heading', { level: 1, name: 'บริษัท ทดสอบ จำกัด' });
      // DealQuotationPanel's home tab is still visible to sales (it also
      // hosts the legacy quotation docs) — open it to prove the panel
      // itself renders nothing, not merely that we never looked.
      await openTab(/เอกสาร/);
      expect(screen.queryByRole('heading', { level: 2, name: 'ราคาและใบเสนอราคา' })).toBeNull();
    });

    it('the owning sales rep can create a customer-quotation draft once the PCR is APPROVED_FOR_QUOTATION', async () => {
      api.pricingRequests.listForTicket.mockResolvedValue({ items: [approvedPr] });
      api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [] });
      api.pricingRequests.createCustomerQuotation.mockResolvedValue({
        quotation: { id: 9101, docStatus: 'DRAFT', quotationRevisionNo: 1 },
      });

      renderTicketDetailPage(salesOwnerUser);
      await openTab(/เอกสาร/);

      expect(await screen.findByRole('heading', { level: 2, name: 'ราคาและใบเสนอราคา' })).not.toBeNull();
      fireEvent.click(await screen.findByRole('button', { name: 'สร้างร่างใบเสนอราคาลูกค้า' }));

      await waitFor(() => expect(api.pricingRequests.createCustomerQuotation).toHaveBeenCalledWith(
        501, expect.objectContaining({ clientRequestId: expect.any(String) }),
      ));
    });

    it('import (no business in the customer quotation) never sees the section — Slice D still widens the "เอกสาร" tab itself', async () => {
      api.pricingRequests.listForTicket.mockResolvedValue({ items: [approvedPr] });

      renderTicketDetailPage({ id: 7, employeeId: 7, name: 'ฝ่ายนำเข้า', role: 'import' });

      await screen.findByRole('heading', { level: 1, name: 'บริษัท ทดสอบ จำกัด' });
      // Slice D ("the เอกสาร document register") makes this tab
      // role-unconditional (see ticketDetailTabs.js's own comment on this
      // tab) so a non-assignee import rep now sees the TAB — but this
      // pre-existing gap is otherwise unchanged (see ticketDetailTabs.js's
      // "KNOWN GAP" doc comment): salesViewScope.js still hides BOTH
      // dealQuotation and quotation from import outright, so neither
      // DealQuotationPanel's own section nor a single quotation row inside
      // DealDocumentRegister ever appears for it.
      expect(await screen.findByRole('tab', { name: /เอกสาร/ })).not.toBeNull();
      await openTab(/เอกสาร/);
      expect(screen.queryByRole('heading', { level: 2, name: 'ราคาและใบเสนอราคา' })).toBeNull();
      expect(screen.queryByTestId('register-quotations')).toBeNull();
      expect(api.pricingRequests.listCustomerQuotations).not.toHaveBeenCalled();
    });
  });

  // "มัดจำ" (Phase 3 Slice S3 — docs/agent-handoffs/105): the unified
  // policy → notice → payment section, replacing the deposit-policy control
  // that used to live in DealStagePanel and the deposit doc/payment bits
  // that used to live directly on this page.
  describe('deal deposit panel', () => {
    // DealDepositPanel now lives inside the "การเงิน" tab.
    async function depositSection() {
      await openTab(/การเงิน/);
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

    it('import (no business in the deposit section) never gets a "การเงิน" tab at all', async () => {
      renderTicketDetailPage({ id: 7, employeeId: 7, name: 'ฝ่ายนำเข้า', role: 'import' });

      await screen.findByRole('heading', { level: 1, name: 'บริษัท ทดสอบ จำกัด' });
      // ledger_importCannotReadThePaymentLedger / depositNotice_import...Refused —
      // both money sub-reads deny import, so the whole tab is absent (see
      // ticketDetailTabs.js's "money" gate), not just this one section.
      expect(screen.queryByRole('tab', { name: /การเงิน/ })).toBeNull();
      expect(screen.queryByRole('heading', { level: 2, name: 'มัดจำ' })).toBeNull();
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

    // DealFulfilmentPanel now lives inside the "จัดซื้อ-ส่งมอบ" tab.
    async function fulfilmentSection() {
      await openTab(/จัดซื้อ-ส่งมอบ/);
      const heading = await screen.findByRole('heading', { level: 2, name: 'การส่งมอบ / นำเข้า' });
      return within(heading.closest('section'));
    }

    // Regression: the /fulfilment workspace gave the four fulfilment codes a `to` of
    // '/fulfilment', which made TicketDetailPage's sticky bar navigate away instead of scrolling
    // to the panel already on this page — ejecting an import user from the deal they were standing
    // on to go find it again in a list. The in-page target must win whenever the page has one.
    it('import’s sticky CTA scrolls to the fulfilment panel instead of navigating to /fulfilment', async () => {
      // Records WHICH element was scrolled, not merely that something was. A bare
      // vi.fn() on the prototype passes with the fix reverted — other things on this page
      // scroll on mount, so "scrollIntoView was called" is satisfied by them and the test
      // proves nothing. Verified by mutation-check: with `&& !jumpId` removed, the id
      // assertion below goes red and the bare-call assertion did not.
      const scrolledIds = [];
      const original = Element.prototype.scrollIntoView;
      Element.prototype.scrollIntoView = function scrollIntoViewSpy() {
        scrolledIds.push(this.id);
      };
      try {
        // lifecycle ACTIVE is required: resolveWorkState returns no action without it, so the
        // sticky bar would never render and this test would pass vacuously.
        api.tickets.get.mockResolvedValue({
          ticket: buildTicket({
            summary: {
              status: 'quotation_issued', paymentStatus: 'DEPOSIT_PAID',
              lifecycle: 'ACTIVE', salesStage: 'DEPOSIT_RECEIVED', fulfillmentStatus: null,
            },
          }),
        });
        api.tickets.actions.mockResolvedValue({
          currentState: { lifecycle: 'ACTIVE', salesStage: 'DEPOSIT_RECEIVED', paymentStatus: 'DEPOSIT_PAID', fulfillmentStatus: null, status: 'quotation_issued' },
          availableActions: [{ action: 'ISSUE_IMPORT_REQUEST', kind: 'fulfillment', label: 'ออกคำขอนำเข้า' }],
        });

        renderTicketDetailPage(importUser);
        await screen.findByRole('heading', { level: 1, name: 'บริษัท ทดสอบ จำกัด' });

        const sticky = await screen.findByTestId('ticket-primary-action');
        expect(sticky.getAttribute('data-action')).toBe('issueImportRequest');

        fireEvent.click(sticky);

        // The in-page branch ran and landed on the fulfilment panel specifically. Asserting the
        // scroll target rather than "did not navigate" because a MemoryRouter with no /fulfilment
        // route renders blank either way, so a negative navigation assertion would itself be
        // vacuous.
        await waitFor(() => expect(scrolledIds).toContain('deal-fulfilment-panel'));
      } finally {
        Element.prototype.scrollIntoView = original;
      }
    });

    // ── ใบขอซื้อ (F-SM-001) download block ──────────────────────────────────────────────────
    describe('the ใบขอซื้อ block', () => {
      it('offers one form per brand on the deal', async () => {
        api.importRequests.brands.mockResolvedValue({ brands: ['Padana', 'LEA'] });
        renderTicketDetailPage(importUser);
        const section = await fulfilmentSection();

        expect(await section.findByTestId('deal-fulfilment-ir-download-Padana')).toBeTruthy();
        expect(section.getByTestId('deal-fulfilment-ir-download-LEA')).toBeTruthy();
        expect(api.importRequests.brands).toHaveBeenCalledWith(701);
      });

      it('hands the typed ReF. No. and the chosen brand to the download', async () => {
        api.importRequests.brands.mockResolvedValue({ brands: ['Padana'] });
        api.importRequests.download.mockResolvedValue(new Blob(['%PDF-']));
        renderTicketDetailPage(importUser);
        const section = await fulfilmentSection();

        fireEvent.change(await section.findByTestId('deal-fulfilment-ir-ref'),
          { target: { value: 'IR69068' } });
        fireEvent.click(section.getByTestId('deal-fulfilment-ir-download-Padana'));

        // requiredBy is null on purpose: it is SALES's field and nothing stores it yet, so the form
        // prints it blank. If this ever starts passing a value, the panel has begun filling in
        // another department's field.
        await waitFor(() => expect(api.importRequests.download)
          .toHaveBeenCalledWith(701, 'Padana', 'IR69068', null));
      });

      it('flags a form that will run to a second sheet', async () => {
        api.importRequests.brands.mockResolvedValue({ brands: ['Padana'] });
        api.importRequests.pages.mockResolvedValue({ pageCount: 2 });
        renderTicketDetailPage(importUser);
        const section = await fulfilmentSection();

        expect(await section.findByText('2 แผ่น')).toBeTruthy();
      });

      /**
       * ImportRequestService.IR_ROLES is {import, ceo}, so rendering this for sales would offer a
       * control that 403s. Sales DOES see the rest of this panel (read-only), which is why the gate
       * has to be on the block rather than the tab.
       */
      it('is absent for sales, which may read this panel but not the document', async () => {
        api.importRequests.brands.mockResolvedValue({ brands: ['Padana'] });
        renderTicketDetailPage(salesOwnerUser);
        const section = await fulfilmentSection();

        expect(section.queryByTestId('deal-fulfilment-import-request')).toBeNull();
        // And the query is not even issued — a 403 in the console for every sales viewer of this
        // tab would be noise, so `enabled` carries the same gate as the markup.
        expect(api.importRequests.brands).not.toHaveBeenCalled();
      });
    });

    it('import issues an Import Request via api.tickets.issueImportRequest', async () => {
      api.tickets.get.mockResolvedValueOnce({
        ticket: buildTicket({ summary: { status: 'quotation_issued', paymentStatus: 'DEPOSIT_PAID' } }),
      });
      api.tickets.actions.mockResolvedValueOnce({
        currentState: { lifecycle: 'ACTIVE', salesStage: 'DEPOSIT_RECEIVED', paymentStatus: 'DEPOSIT_PAID', fulfillmentStatus: null, status: 'quotation_issued' },
        availableActions: [{ action: 'ISSUE_IMPORT_REQUEST', kind: 'fulfillment', label: 'ออกคำขอนำเข้า' }],
      });
      api.tickets.issueImportRequest.mockResolvedValue({
        ticket: buildTicket({ summary: { status: 'quotation_issued', fulfillmentStatus: 'IR_ISSUED' } }),
      });

      renderTicketDetailPage(importUser);
      const section = await fulfilmentSection();
      fireEvent.click(await section.findByRole('button', { name: 'ออกคำขอนำเข้า (IR)' }));

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
        //
        // RESERVE_STOCK is deliberately NOT in this list any more. Issue #732:
        // that action's local role check was removed on purpose, because
        // TicketService.canDeclareStockCoverage grants it to the deal owner as
        // well as to import/ceo, and actions() advertises it off that same
        // predicate — so ANDing it with a frontend role set threw away the
        // server's answer and hid the button from the one role PR #706 built
        // it for. Feeding account an action the real service cannot produce for
        // it would now assert a gate that intentionally does not exist. The
        // realistic case — account is offered nothing, so account sees nothing
        // — is asserted below instead.
        // RECORD_PARTIAL_DELIVERY / COMPLETE_DELIVERY left this list for the SAME reason
        // RESERVE_STOCK did, one ruling later: stages 13-14 (ส่งมอบสินค้า) are Sales's as of
        // 2026-08-17, TicketService.canWriteDelivery grants them to the deal owner as well as
        // import/ceo, and actions() advertises both off that predicate. Their local `isFulfilment`
        // check is therefore gone, so feeding account two actions the real service cannot produce
        // for it would assert a gate that intentionally no longer exists. The realistic case —
        // account is offered nothing, so account sees nothing — is what the assertions below check,
        // and it is still real evidence: the panel renders those buttons purely from hasAction().
        availableActions: [
          { action: 'ISSUE_IMPORT_REQUEST', kind: 'fulfillment', label: 'ออกคำขอนำเข้า' },
          { action: 'SHIPPING', kind: 'fulfillment', label: 'สินค้าเดินทาง' },
        ],
      });

      renderTicketDetailPage(accountUser);
      const section = await fulfilmentSection();

      expect(section.queryByRole('button', { name: 'สินค้าออกเดินทาง' })).toBeNull();
      expect(section.queryByRole('button', { name: 'จองสินค้าจากสต็อก' })).toBeNull();
      expect(section.queryByRole('button', { name: 'บันทึกการส่งสินค้า' })).toBeNull();
      expect(section.queryByRole('button', { name: 'ส่งมอบครบ' })).toBeNull();
    });

    // ── Issue #732: จองสินค้าจากสต็อก for the deal owner ──────────────────────
    //
    // PR #706 widened TicketService.canDeclareStockCoverage to FULFILMENT_ROLES ∪
    // (SALES_ROLES ∧ deal owner) and actions() advertises RESERVE_STOCK off the
    // same predicate — so the server already answers "may this viewer declare
    // stock coverage", carrying the ownership rule, the ORDER_RECEIVED stage
    // floor and the remaining-delivery check with it. The panel used to AND that
    // answer with the pre-#706 role set and hide the button from sales entirely.
    //
    // ⚠️ These are RENDERING assertions over a mocked `api`. They pin that the
    // frontend now honours the server's answer instead of overriding it; they say
    // nothing about who the real service actually grants. That claim needs a
    // real-DB test through the Java service (StockDeclarationAuthzIntegrationTest
    // covers the backend half) — see CLAUDE.md.
    it('shows จองสินค้าจากสต็อก to the sales deal owner when the server advertises RESERVE_STOCK', async () => {
      api.tickets.get.mockResolvedValueOnce({
        ticket: buildTicket({ summary: { status: 'quotation_issued', salesStage: 'ORDER_RECEIVED', createdById: 1 } }),
      });
      api.tickets.actions.mockResolvedValueOnce({
        currentState: { lifecycle: 'ACTIVE', salesStage: 'ORDER_RECEIVED', paymentStatus: 'DEPOSIT_PAID', fulfillmentStatus: null, status: 'quotation_issued' },
        availableActions: [{ action: 'RESERVE_STOCK', kind: 'fulfillment', label: 'จองสินค้าจากสต็อก' }],
      });

      renderTicketDetailPage(salesOwnerUser);
      const section = await fulfilmentSection();

      expect(await section.findByRole('button', { name: 'จองสินค้าจากสต็อก' })).not.toBeNull();
    });

    // Wrong-way-round, and the one that matters: dropping the local role check
    // must NOT mean the button is unconditional. When the server withholds
    // RESERVE_STOCK — below the stage floor, not the owner, nothing left to
    // deliver — the same sales user must still see nothing.
    it('hides จองสินค้าจากสต็อก from a sales user the server did not offer RESERVE_STOCK to', async () => {
      api.tickets.get.mockResolvedValueOnce({
        ticket: buildTicket({ summary: { status: 'quotation_issued', salesStage: 'PROCUREMENT', createdById: 1 } }),
      });
      api.tickets.actions.mockResolvedValueOnce({
        currentState: { lifecycle: 'ACTIVE', salesStage: 'PROCUREMENT', paymentStatus: 'DEPOSIT_PAID', fulfillmentStatus: null, status: 'quotation_issued' },
        availableActions: [],
      });

      renderTicketDetailPage(salesOwnerUser);
      const section = await fulfilmentSection();

      expect(section.queryByRole('button', { name: 'จองสินค้าจากสต็อก' })).toBeNull();
    });

    // ── Stages 13-14 (ส่งมอบสินค้า) belong to Sales — owner ruling 2026-08-17 ─────────────
    //
    // Same shape as the RESERVE_STOCK pair above, one ruling later. TicketService.canWriteDelivery
    // is FULFILMENT_ROLES ∪ (sales ∧ deal owner) and actions() advertises
    // RECORD_PARTIAL_DELIVERY/COMPLETE_DELIVERY off it, so the panel's own `isFulfilment` check on
    // those two is gone and the server's answer stands.
    //
    // ⚠️ RENDERING assertions over a mocked `api`: they pin that the frontend honours the server's
    // answer, and say NOTHING about who the real service grants. That claim is
    // DeliveryAuthzIntegrationTest's, against real Postgres — see CLAUDE.md.
    it('shows the delivery buttons to the sales deal owner when the server advertises them', async () => {
      api.tickets.get.mockResolvedValueOnce({
        ticket: buildTicket({ summary: { status: 'quotation_issued', salesStage: 'DELIVERY_SCHEDULING', fulfillmentStatus: 'GOODS_RECEIVED', createdById: 1 } }),
      });
      api.tickets.actions.mockResolvedValueOnce({
        currentState: { lifecycle: 'ACTIVE', salesStage: 'DELIVERY_SCHEDULING', paymentStatus: 'DEPOSIT_PAID', fulfillmentStatus: 'GOODS_RECEIVED', status: 'quotation_issued' },
        availableActions: [
          { action: 'RECORD_PARTIAL_DELIVERY', kind: 'fulfillment', label: 'บันทึกการส่งสินค้า' },
          { action: 'COMPLETE_DELIVERY', kind: 'fulfillment', label: 'ส่งมอบครบ' },
        ],
      });

      renderTicketDetailPage(salesOwnerUser);
      const section = await fulfilmentSection();

      expect(await section.findByRole('button', { name: 'บันทึกการส่งสินค้า' })).not.toBeNull();
      expect(section.getByRole('button', { name: 'ส่งมอบครบ' })).not.toBeNull();
    });

    // Wrong-way-round: dropping the local role check must not make the buttons unconditional.
    it('hides the delivery buttons from a sales user the server did not offer them to', async () => {
      api.tickets.get.mockResolvedValueOnce({
        ticket: buildTicket({ summary: { status: 'quotation_issued', salesStage: 'DELIVERY_SCHEDULING', fulfillmentStatus: 'GOODS_RECEIVED', createdById: 1 } }),
      });
      api.tickets.actions.mockResolvedValueOnce({
        currentState: { lifecycle: 'ACTIVE', salesStage: 'DELIVERY_SCHEDULING', paymentStatus: 'DEPOSIT_PAID', fulfillmentStatus: 'GOODS_RECEIVED', status: 'quotation_issued' },
        availableActions: [],
      });

      renderTicketDetailPage(salesOwnerUser);
      const section = await fulfilmentSection();

      expect(section.queryByRole('button', { name: 'บันทึกการส่งสินค้า' })).toBeNull();
      expect(section.queryByRole('button', { name: 'ส่งมอบครบ' })).toBeNull();
    });

    // ── Issue #730: a from-stock deal must not claim import milestones ────────
    //
    // SubstepChips walked a FLAT list and marked every entry before the current
    // one done. FROM_STOCK sat at index 4, after the whole import sequence, so a
    // from-stock deal rendered four milestones it never performed — and, per
    // issueImportRequest's own guard (fulfillmentStatus must be null), never
    // could have.
    it('renders no import milestones on a deal fulfilled FROM_STOCK', async () => {
      api.tickets.get.mockResolvedValueOnce({
        ticket: buildTicket({
          summary: { status: 'quotation_issued', salesStage: 'DELIVERY_SCHEDULING', fulfillmentStatus: 'FROM_STOCK' },
          items: [{ id: 70101, brand: 'SCG', model: 'A1', qty: 10, qtyDelivered: 0, qtyFromStock: 10, approvedPrice: 150 }],
        }),
      });
      api.tickets.actions.mockResolvedValueOnce({
        currentState: { lifecycle: 'ACTIVE', salesStage: 'DELIVERY_SCHEDULING', paymentStatus: 'DEPOSIT_PAID', fulfillmentStatus: 'FROM_STOCK', status: 'quotation_issued' },
        availableActions: [],
      });

      renderTicketDetailPage(ceoUser);
      const section = await fulfilmentSection();

      // findAllByText: the from-stock label appears twice on purpose — once as the
      // fulfilmentStatus badge, once as the single chip of the from-stock path.
      expect((await section.findAllByText('สินค้าจากสต็อก')).length).toBeGreaterThan(0);
      for (const label of ['ออกใบขอซื้อ (IR) แล้ว', 'สั่งซื้อไปยังผู้ผลิตแล้ว', 'สินค้าอยู่ระหว่างเดินทาง', 'สินค้าถึงโกดังแล้ว']) {
        expect(section.queryByText(label)).toBeNull();
      }
    });

    // The other half: the import journey must still render in full for a deal
    // that is actually on it — the fix must not have narrowed both paths.
    it('still renders the full import sequence on a deal that issued an import request', async () => {
      api.tickets.get.mockResolvedValueOnce({
        ticket: buildTicket({
          summary: { status: 'quotation_issued', salesStage: 'PROCUREMENT', fulfillmentStatus: 'SHIPPING' },
          items: [{ id: 70101, brand: 'SCG', model: 'A1', qty: 10, qtyDelivered: 0, qtyFromStock: 0, approvedPrice: 150 }],
        }),
      });
      api.tickets.actions.mockResolvedValueOnce({
        currentState: { lifecycle: 'ACTIVE', salesStage: 'PROCUREMENT', paymentStatus: 'DEPOSIT_PAID', fulfillmentStatus: 'SHIPPING', status: 'quotation_issued' },
        availableActions: [],
      });

      renderTicketDetailPage(ceoUser);
      const section = await fulfilmentSection();

      for (const label of ['ออกใบขอซื้อ (IR) แล้ว', 'สั่งซื้อไปยังผู้ผลิตแล้ว', 'สินค้าอยู่ระหว่างเดินทาง', 'สินค้าถึงโกดังแล้ว']) {
        expect(await section.findByText(label)).not.toBeNull();
      }
      expect(section.queryByText('สินค้าจากสต็อก')).toBeNull();
    });

  });

  // Slice A "chip diet": DealStateHeader's stat-chip set is now role-aware
  // (see DealStateHeader.jsx's own CAN_VIEW_PRICING_REQUESTS_ROLES doc
  // comment for the full table + reasoning). ขั้นตอนดีล and การนำเข้า are
  // unconditional for every role that reaches this page; คำขอราคา, การชำระเงิน,
  // and มูลค่าดีล vary. Assertions are scoped to the header
  // (`deal-state-header`) specifically — some of these labels have similar
  // but distinct Thai copy elsewhere on the page (the "การเงิน" tab, the money
  // panel's own "การชำระเงิน" <h2>), so an unscoped query would be a weaker
  // assertion, not a stronger one.
  describe('DealStateHeader chip visibility per role (Slice A "chip diet")', () => {
    it('sales, sales_manager, and ceo all get every one of the 5 header chips', async () => {
      for (const user of [salesOwnerUser, { id: 11, employeeId: 11, name: 'ผจก.ขาย', role: 'sales_manager' }, ceoUser]) {
        const { unmount } = renderTicketDetailPage(user);
        const header = await screen.findByTestId('deal-state-header');
        for (const label of ['ขั้นตอนดีล', 'คำขอราคา', 'การชำระเงิน', 'การนำเข้า', 'มูลค่าดีล']) {
          expect(within(header).getByText(label)).not.toBeNull();
        }
        unmount();
      }
    });

    // Correctness fix, not decluttering (see DealStateHeader.jsx's own doc
    // comment): TicketDetailPage's canViewPricingRequests excludes `account`,
    // so `pricingRequests` is hard-coded to `[]` for that role regardless of
    // whether requests actually exist on the deal — showing the chip would
    // have rendered "ยังไม่มี" (none yet) even when requests are live.
    it('account never gets the "คำขอราคา" chip (wrong-way-round: it cannot see this even though it keeps การชำระเงิน/มูลค่าดีล)', async () => {
      renderTicketDetailPage(accountUser);
      const header = await screen.findByTestId('deal-state-header');
      expect(within(header).queryByText('คำขอราคา')).toBeNull();
      expect(within(header).getByText('ขั้นตอนดีล')).not.toBeNull();
      expect(within(header).getByText('การชำระเงิน')).not.toBeNull();
      expect(within(header).getByText('การนำเข้า')).not.toBeNull();
      expect(within(header).getByText('มูลค่าดีล')).not.toBeNull();
    });

    // Reduces what import is SHOWN here — NOT a security fix (see
    // DealStateHeader.jsx's own doc comment): TicketService.projectForRole
    // still sends summary.amountPayable to import over the wire regardless of
    // this chip.
    it('import never gets "การชำระเงิน" or "มูลค่าดีล" (wrong-way-round: it cannot see either, even though it keeps ขั้นตอนดีล/คำขอราคา/การนำเข้า)', async () => {
      renderTicketDetailPage({ id: 7, employeeId: 7, name: 'ฝ่ายนำเข้า', role: 'import' });
      const header = await screen.findByTestId('deal-state-header');
      expect(within(header).queryByText('การชำระเงิน')).toBeNull();
      expect(within(header).queryByText('มูลค่าดีล')).toBeNull();
      expect(within(header).getByText('ขั้นตอนดีล')).not.toBeNull();
      expect(within(header).getByText('คำขอราคา')).not.toBeNull();
      expect(within(header).getByText('การนำเข้า')).not.toBeNull();
    });
  });

  // Ticket-detail IA rebuild Phase 2 built role-projected tab VISIBILITY;
  // Slice C2b regrouped the seven tabs into six (ticketDetailTabs.js has its
  // own unit tests for the pure function — these prove the page actually
  // wires it in, per-role, end to end).
  describe('tab visibility per role', () => {
    // Slice D ("the เอกสาร document register") makes "เอกสาร" role-
    // unconditional, same shape as "ประวัติ" — import now sees the TAB (it
    // may reach an attachments roll-up once it is this deal's assignee), but
    // the KNOWN GAP this file's own doc comment still documents is
    // unchanged: salesViewScope.js hides both dealQuotation and quotation
    // from import, so it reaches zero quotation rows inside that tab. See
    // DealDocumentRegister.test.jsx for that content-level proof.
    it('import never gets "การเงิน", but now gets "เอกสาร" too (Slice D) — keeps "สินค้าและราคา", "จัดซื้อ-ส่งมอบ", and "ประวัติ"', async () => {
      renderTicketDetailPage({ id: 7, employeeId: 7, name: 'ฝ่ายนำเข้า', role: 'import' });

      await screen.findByRole('heading', { level: 1, name: 'บริษัท ทดสอบ จำกัด' });
      // ledger_importCannotReadThePaymentLedger
      expect(screen.queryByRole('tab', { name: /การเงิน/ })).toBeNull();
      expect(await screen.findByRole('tab', { name: /เอกสาร/ })).not.toBeNull();
      expect(await screen.findByRole('tab', { name: /สินค้าและราคา/ })).not.toBeNull();
      expect(screen.getByRole('tab', { name: /จัดซื้อ-ส่งมอบ/ })).not.toBeNull();
      expect(screen.getByRole('tab', { name: /ประวัติ/ })).not.toBeNull();
    });

    // Slice C2b: PricingRequestPanel's whole-tab gate is gone — it merged
    // into "สินค้าและราคา" (unconditionally visible) as an inner condition,
    // so account keeps this tab too now (see the content-level test in the
    // "role -> reachable-content projection" describe block below for proof
    // it still cannot reach the panel inside it). Slice D additionally
    // widens "เอกสาร" itself to account — the intended, owner-authorised
    // change (account confirms money against the deposit-notice/attachment
    // rows the register now lists) — even though it still cannot reach a
    // single quotation row inside it (quotation_accountCannotListCustomerQuotations).
    it('account now gets "เอกสาร" too (Slice D) — keeps "สินค้าและราคา", "การเงิน", "จัดซื้อ-ส่งมอบ", and "ประวัติ"', async () => {
      renderTicketDetailPage(accountUser);

      await screen.findByRole('heading', { level: 1, name: 'บริษัท ทดสอบ จำกัด' });
      expect(await screen.findByRole('tab', { name: /เอกสาร/ })).not.toBeNull();
      expect(await screen.findByRole('tab', { name: /สินค้าและราคา/ })).not.toBeNull();
      expect(await screen.findByRole('tab', { name: /การเงิน/ })).not.toBeNull();
      expect(screen.getByRole('tab', { name: /จัดซื้อ-ส่งมอบ/ })).not.toBeNull();
      // Role-unconditional (see ticketDetailTabs.js).
      expect(screen.getByRole('tab', { name: /ประวัติ/ })).not.toBeNull();
    });

    it('sales (deal owner), sales_manager, and ceo all see every one of the 6 tabs', async () => {
      for (const user of [salesOwnerUser, { id: 11, employeeId: 11, name: 'ผจก.ขาย', role: 'sales_manager' }, ceoUser]) {
        const { unmount } = renderTicketDetailPage(user);
        await screen.findByRole('heading', { level: 1, name: 'บริษัท ทดสอบ จำกัด' });
        for (const namePattern of [/^ดีล/, /สินค้าและราคา/, /^เอกสาร/, /การเงิน/, /จัดซื้อ-ส่งมอบ/, /ประวัติ/]) {
          expect(screen.getByRole('tab', { name: namePattern })).not.toBeNull();
        }
        unmount();
      }
    });
  });

  // Slice C2b ("the 7->6 tab restructure"): every gate that used to decide a
  // whole TAB's existence now decides either the same tab (money/fulfilment/
  // documents, unchanged) or an INNER render condition inside an
  // unconditionally-visible tab (pricing-request panel inside "สินค้าและราคา",
  // attachments panel inside "ประวัติ") — never a widened or narrowed
  // predicate. These prove the set of *content* each role can reach is
  // unchanged in EFFECT, even though the tab count changed from 7 to 6.
  describe('role -> reachable-content projection is unchanged by the 7->6 tab restructure', () => {
    it('account still cannot reach pricing-request content (formerly its own "ราคา" tab, now an inner condition inside "สินค้าและราคา")', async () => {
      renderTicketDetailPage(accountUser);
      await openTab(/สินค้าและราคา/);
      expect(screen.queryByRole('heading', { name: 'คำขอราคา' })).toBeNull();
      expect(api.pricingRequests.listForTicket).not.toHaveBeenCalled();
    });

    // Slice D: account now reaches the "เอกสาร" TAB (see "tab visibility per
    // role" above) but this is the content-level proof it still cannot
    // reach a single quotation ROW inside it — quotation_accountCannot
    // ListCustomerQuotations, unchanged.
    it('account reaches "เอกสาร" but still zero quotation rows inside DealDocumentRegister', async () => {
      renderTicketDetailPage(accountUser);
      await openTab(/เอกสาร/);
      expect(await screen.findByTestId('deal-document-register')).not.toBeNull();
      expect(screen.queryByTestId('register-quotations')).toBeNull();
      expect(api.pricingRequests.listCustomerQuotations).not.toHaveBeenCalled();
    });

    it('import still cannot reach payment content (the "การเงิน" tab predicate is unchanged)', async () => {
      renderTicketDetailPage({ id: 7, employeeId: 7, name: 'ฝ่ายนำเข้า', role: 'import' });
      await screen.findByRole('heading', { level: 1, name: 'บริษัท ทดสอบ จำกัด' });
      expect(screen.queryByRole('tab', { name: /การเงิน/ })).toBeNull();
      expect(screen.queryByText('การชำระเงิน')).toBeNull();
      expect(api.depositNotices.listByTicket).not.toHaveBeenCalled();
    });
  });

  // Issue #389's document-read gate (createdById/assignedToId participant OR
  // ROLE_PERMISSIONS.canViewTicketDocuments) is unchanged by Slice C2b — only
  // WHERE it lives changed: it used to decide the old "documents" (attachments)
  // TAB's existence; it now decides DealAttachmentsPanel's presence as an
  // inner render condition inside "ประวัติ" (an unconditionally-visible tab —
  // see ticketDetailTabs.js's own comment on that tab). "ประวัติ" itself is
  // reachable regardless; only the panel comes and goes.
  describe('attachments panel inside "ประวัติ" follows the document-read gate, not the deal-read gate', () => {
    // ticketDetailTabs.js's own role-level predicate for "ประวัติ" is
    // deliberately coarse (`() => true`, same as "ดีล"/"สินค้าและราคา") because
    // role+sections alone cannot express the identity half of the document
    // gate — a deal's participants reach its documents regardless of role.
    // TicketDetailPage.jsx applies `canViewDocumentsTab` on top, as an inner
    // condition around DealAttachmentsPanel specifically.
    //
    // Issue #389 rewrote the ROLE half: reading a deal's documents is now the same question as
    // reading the deal (TicketAccessPolicy.canViewDocuments), so `account` and `import` DO see
    // the panel — account is the role asked to confirm deposit/final-payment receipts against
    // these very files, and hiding it would have left the backend fix invisible. A `sales`
    // rep on someone else's deal is still refused: that one 403s for real.
    it('the panel follows canViewDocumentsTab; the "ประวัติ" tab itself never disappears', async () => {
      // Presence of the panel's own heading is the READ signal — it renders
      // for all four roles below regardless of `canManageDocuments` (account
      // reads every document but may not upload one; see the dedicated
      // "offers NO upload control to account" test for that narrower WRITE
      // gate, which `#ticket-attachment-file` alone would conflate with here).
      for (const user of [ceoUser, salesOwnerUser, accountUser,
        { id: 11, employeeId: 11, name: 'ผจก.ขาย', role: 'sales_manager' }]) {
        const { unmount } = renderTicketDetailPage(user);
        await openTab(/ประวัติ/);
        expect(await screen.findByRole('heading', { level: 2, name: 'ประวัติดีล' })).not.toBeNull();
        expect(await screen.findByRole('heading', { level: 2, name: 'ไฟล์แนบ (PO / ใบเซ็น)' })).not.toBeNull();
        unmount();
      }

      // A sales rep who is neither this deal's creator (buildTicket()'s default createdById is
      // 1, i.e. salesOwnerUser) nor its assignee: refused, exactly as the backend refuses them.
      // Wrong-way-round: "ประวัติ" is still reachable (its own gate is
      // `() => true`) — only the attachments panel inside it vanishes.
      const otherSales = { id: 42, employeeId: 42, name: 'พนักงานขายอื่น', role: 'sales' };
      const { container: otherContainer, unmount: unmountOther } = renderTicketDetailPage(otherSales);
      expect(await screen.findByRole('tab', { name: /ประวัติ/ })).not.toBeNull();
      await openTab(/ประวัติ/);
      expect(await screen.findByRole('heading', { level: 2, name: 'ประวัติดีล' })).not.toBeNull();
      expect(screen.queryByRole('heading', { level: 2, name: 'ไฟล์แนบ (PO / ใบเซ็น)' })).toBeNull();
      expect(otherContainer.querySelector('#ticket-attachment-file')).toBeNull();
      unmountOther();

      // Presentation half of THE IMPORT PIN (#389 review). import reads the deal — it renders
      // this page — and is still refused its documents, because AttachType spans
      // SIGNED_QUOTATION/INVOICE, i.e. the approved customer price that salesViewScope already
      // hides from import. The backend pins are
      // TicketAccessPolicyTest.importIsRefusedDocumentsDespiteBeingAViewerRole and
      // AttachmentTicketAccessIntegrationTest.importIsRefusedDocumentsOnADealItHasNotPickedUp.
      const nonAssigneeImport = { id: 7, employeeId: 7, name: 'ฝ่ายนำเข้า', role: 'import' };
      const { container: importContainer, unmount: unmountImport } = renderTicketDetailPage(nonAssigneeImport);
      await openTab(/ประวัติ/);
      expect(await screen.findByRole('heading', { level: 2, name: 'ประวัติดีล' })).not.toBeNull();
      expect(importContainer.querySelector('#ticket-attachment-file')).toBeNull();
      unmountImport();

      // The participant grant is per-instance, for import and sales alike: whoever picked the
      // deal up (assignedToId) reaches its documents regardless of role.
      api.tickets.get.mockResolvedValue({ ticket: buildTicket({ summary: { assignedToId: 7 } }) });
      const { container: assigneeContainer, unmount: unmountAssignee } = renderTicketDetailPage(nonAssigneeImport);
      await openTab(/ประวัติ/);
      expect(assigneeContainer.querySelector('#ticket-attachment-file')).not.toBeNull();
      unmountAssignee();

      api.tickets.get.mockResolvedValue({ ticket: buildTicket({ summary: { assignedToId: 42 } }) });
      const { container: assigneeSalesContainer } = renderTicketDetailPage(otherSales);
      await openTab(/ประวัติ/);
      expect(assigneeSalesContainer.querySelector('#ticket-attachment-file')).not.toBeNull();
    });

    // #389: reading a document and writing one are two different questions. account may open
    // every deal document but may NOT upload — the closing tax invoice keeps exactly one entry
    // point (CommissionService.createFromDeal), and a second upload path would satisfy the close
    // gate's invoiceOnFile check while the rep silently loses their commission.
    it('offers NO upload control to account, which may read documents but not write them', async () => {
      const { container, unmount } = renderTicketDetailPage(accountUser);
      fireEvent.click(await screen.findByRole('tab', { name: /ประวัติ/ }));
      expect(await screen.findByRole('heading', { level: 2, name: 'ไฟล์แนบ (PO / ใบเซ็น)' })).not.toBeNull();
      expect(container.querySelector('#ticket-attachment-file')).toBeNull();
      unmount();

      // The deal's own rep keeps it — this is a targeted narrowing, not a gutting of the panel.
      const { container: ownerContainer } = renderTicketDetailPage(salesOwnerUser);
      fireEvent.click(await screen.findByRole('tab', { name: /ประวัติ/ }));
      expect(ownerContainer.querySelector('#ticket-attachment-file')).not.toBeNull();
    });

    /**
     * Anti-regression guard, not a coverage box-tick. The "แนบใบกำกับภาษี" control that
     * used to live in this panel was gated `isAccount` while AttachmentController's gate
     * granted only participants OR {hr, sales_manager, ceo} — so it 403'd for real, and only
     * looked functional because mockApi.js had no authz on attachments at all.
     *
     * Issue #389 rebuilt that gate (account now READS every deal document, hr reads none) but
     * kept the WRITE side narrow — TicketAccessPolicy.canManageDocuments is participant OR
     * sales_manager/ceo, never account — for exactly the reason below.
     *
     * It was removed rather than re-gated (2026-07-30 owner decision): the closing tax
     * invoice must come from CommissionService.createFromDeal, which writes the INVOICE
     * attachment AND the deal owner's commission together. A second control here would
     * satisfy the close gate's invoiceOnFile while silently skipping the commission.
     * If this test goes red, someone has reintroduced that path.
     */
    it('offers NO ใบกำกับภาษี upload control in "ประวัติ" — the invoice comes from createFromDeal', async () => {
      const { container } = renderTicketDetailPage(ceoUser);
      fireEvent.click(await screen.findByRole('tab', { name: /ประวัติ/ }));

      expect(container.querySelector('#ticket-invoice-file')).toBeNull();
      expect(screen.queryByText('แนบใบกำกับภาษี')).toBeNull();
      // The generic attachment control (PO / signed docs) must survive — this is a
      // targeted removal, not a gutting of the panel.
      expect(container.querySelector('#ticket-attachment-file')).not.toBeNull();
    });

    /**
     * Regression guard for a bug that shipped on main: `uploadAttachmentMutation`'s
     * onSuccess called `queryKeys.ticket(ticketId)`, which does not exist —
     * queryKeys.js defines ticketDetail/ticketActions/ticketAttachments/… but never a
     * bare `ticket`. It was the THIRD statement, so the attachments and actions
     * invalidations above it had already run and the upload looked like it worked,
     * then `TypeError: queryKeys.ticket is not a function` aborted the handler before
     * showToast. react-query does not route an onSuccess throw to onError, and
     * handleUploadAttachment's own `catch {}` swallowed the rejected mutateAsync — so
     * a SUCCESSFUL upload told the user nothing at all.
     *
     * Both assertions matter and neither alone is sufficient: the toast covers the
     * silence, and the detail refetch covers the real damage — ticket.summary is what
     * drives the invoiceOnFile close gate, so without it ฝ่ายบัญชี's close confirmation
     * stays stale until the user navigates away and back.
     */
    it('reports back after an attachment upload, and refetches the ticket detail', async () => {
      const showToast = vi.fn();
      const { container } = renderTicketDetailPage(ceoUser, showToast);
      fireEvent.click(await screen.findByRole('tab', { name: /ประวัติ/ }));

      const input = container.querySelector('#ticket-attachment-file');
      expect(input).not.toBeNull();
      await waitFor(() => expect(api.tickets.get).toHaveBeenCalledTimes(1));

      fireEvent.change(input, {
        target: { files: [new File(['x'], 'po.pdf', { type: 'application/pdf' })] },
      });

      await waitFor(() => expect(api.attachments.upload).toHaveBeenCalledTimes(1));
      // Silence on success is the user-visible half of the bug.
      await waitFor(() => expect(showToast).toHaveBeenCalledWith('success', 'แนบไฟล์ po.pdf แล้ว'));
      // …and the stale close gate is the half that actually costs money.
      await waitFor(() => expect(api.tickets.get.mock.calls.length).toBeGreaterThan(1));
    });
  });

  // Ticket-detail IA rebuild Phase 2: `?tab=` is the single source of truth
  // for which tab is open (TicketListPage.jsx's own filter-param convention).
  describe('?tab= URL state', () => {
    it('opens the tab named by ?tab= on first load, when this role may see it', async () => {
      renderTicketDetailPageAtRoute(['/tickets/701?tab=items'], salesOwnerUser);

      const itemsTab = await screen.findByRole('tab', { name: /สินค้าและราคา/ });
      await waitFor(() => expect(itemsTab.getAttribute('aria-selected')).toBe('true'));
      // "ดีล"-only content (วันสำคัญ) is absent — proves the panel actually
      // swapped, not just that the tab button LOOKS selected.
      expect(screen.queryByRole('heading', { level: 2, name: /วันสำคัญ/ })).toBeNull();
      expect(await screen.findByRole('heading', { level: 2, name: /^รายการสินค้า/ })).not.toBeNull();
    });

    it('falls back to ดีล when ?tab= names a tab this role cannot see', async () => {
      // Slice D widened "documents" to role-unconditional (account can see it
      // now — see ticketDetailTabs.js), so this case moves to "money": import
      // still cannot see it (ledger_importCannotReadThePaymentLedger /
      // depositNotice_import...Refused) — resolveTicketDetailTab must fall
      // back rather than render nothing/crash.
      renderTicketDetailPageAtRoute(['/tickets/701?tab=money'], { id: 7, employeeId: 7, name: 'ฝ่ายนำเข้า', role: 'import' });

      const dealTab = await screen.findByRole('tab', { name: /^ดีล/ });
      await waitFor(() => expect(dealTab.getAttribute('aria-selected')).toBe('true'));
      expect(await screen.findByRole('heading', { level: 2, name: /วันสำคัญ/ })).not.toBeNull();
    });

    // Slice C2b retired every per-instance TAB-level gate (the old "documents"
    // (attachments) tab's `canViewDocumentsTab` filter, formerly proven by a
    // test at this exact spot) — DealAttachmentsPanel is now an inner render
    // condition inside "ประวัติ", an unconditionally-visible tab with no
    // tab-level analogue left to fall back FROM. This proves the replacement
    // invariant: a per-instance-excluded viewer's `?tab=history` deep link
    // resolves normally (no fallback at all — the tab's own gate really is
    // `() => true`), and only the attachments panel inside it is missing —
    // see the "attachments panel inside ประวัติ" describe block above for the
    // same predicate proven without the URL-driven entry point.
    it('never falls back off "ประวัติ" for a per-instance-hidden attachments panel — the tab itself is unconditional', async () => {
      const otherSales = { id: 42, employeeId: 42, name: 'พนักงานขายอื่น', role: 'sales' };
      renderTicketDetailPageAtRoute(['/tickets/701?tab=history'], otherSales);

      const historyTab = await screen.findByRole('tab', { name: /^ประวัติ/ });
      await waitFor(() => expect(historyTab.getAttribute('aria-selected')).toBe('true'));
      expect(await screen.findByRole('heading', { level: 2, name: 'ประวัติดีล' })).not.toBeNull();
      expect(screen.queryByRole('heading', { level: 2, name: 'ไฟล์แนบ (PO / ใบเซ็น)' })).toBeNull();
    });

    it('falls back to ดีล for an absent or unknown ?tab= value', async () => {
      renderTicketDetailPageAtRoute(['/tickets/701?tab=not-a-real-tab'], ceoUser);

      const dealTab = await screen.findByRole('tab', { name: /^ดีล/ });
      await waitFor(() => expect(dealTab.getAttribute('aria-selected')).toBe('true'));
    });

    it('clicking a tab writes ?tab= to the URL', async () => {
      renderTicketDetailPageAtRoute(['/tickets/701'], ceoUser);
      await screen.findByRole('heading', { level: 1, name: 'บริษัท ทดสอบ จำกัด' });

      await openTab(/การเงิน/);

      await waitFor(() => expect(screen.getByTestId('location-probe').textContent).toContain('tab=money'));
    });
  });

  // Ticket-detail IA rebuild Phase 2: the merged กิจกรรม timeline
  // (DealHistoryPanel — its own test file covers the id-tiebreak sort in
  // isolation; this proves TicketDetailPage actually feeds it BOTH streams).
  describe('merged กิจกรรม history (events + activities)', () => {
    it('renders both the ticket-events audit trail and the rep activity log in one list, in the ประวัติ tab', async () => {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({
          events: [
            { id: 1, kind: 'CREATED', actorName: 'สมชาย ใจดี', createdAt: '2026-07-01T09:00:00.000Z' },
          ],
        }),
      });
      api.tickets.listActivities.mockResolvedValue({
        items: [{
          id: 501, ticketId: 701, activityDate: '2026-07-10', kind: 'CALL', note: 'โทรติดตามลูกค้า',
          createdById: 9, createdByName: 'CEO ทดสอบ', createdAt: '2026-07-10T09:00:00.000Z',
        }],
      });

      renderTicketDetailPage(ceoUser);
      await openTab(/ประวัติ/);

      // The audit event (from `events`) and the rep activity (from
      // `activities`) both show up, in the same "ประวัติดีล" panel — not two
      // separate histories any more.
      expect(await screen.findByText('สร้างดีล')).not.toBeNull();
      expect(screen.getByText('โทรติดตามลูกค้า')).not.toBeNull();
    });
  });
});
