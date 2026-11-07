import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import {
  Link, MemoryRouter, Route, Routes, useLocation,
} from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TicketDetailPage } from './TicketDetailPage.jsx';
import { ITEM_FIELD_META, missingQtyMessage } from './ticketItemFields.jsx';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { formatThaiDate } from '../../utils/format.js';
import { DEAL_STAGE_CATALOG } from '../../data/dealStageCatalog.js';
import { STAGE_ADVANCE_GATE_MESSAGE } from './dealTrackingMeta.js';
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
        listDeliveries: vi.fn(),
        reserveStock: vi.fn(),
        recordDelivery: vi.fn(),
        completeDelivery: vi.fn(),
        actions: vi.fn(),
        comment: vi.fn(),
        confirmFinalPayment: vi.fn(),
        verifyClose: vi.fn(),
        // เลื่อนไป lives in the header overflow menu, whose onSelect calls this via DealStagePanel's
        // onUpdateStage prop (see the "stage-advance readiness travels with เลื่อนไป" describe).
        updateStage: vi.fn(),
        // แก้ช่องทางดีล (EntryChannelFix): the remedy for the route gate's "— แก้ช่องทางดีลก่อน" refusal.
        setEntryChannel: vi.fn(),
        revision: vi.fn(),
        editItems: vi.fn(),
        downloadQuotationXlsx: vi.fn(),
        downloadQuotationPdf: vi.fn(),
        remainingInvoiceOptions: vi.fn(),
        // Deal tracking.
        listActivities: vi.fn(),
        addActivity: vi.fn(),
        updateTracking: vi.fn(),
        // Deal parties (deal-party-model.md): the ผู้รับเหมาที่เสนอราคา panel and บันทึกผลประมูล.
        parties: vi.fn(),
        addParty: vi.fn(),
        updateParty: vi.fn(),
        removeParty: vi.fn(),
        recordBidOutcome: vi.fn(),
        // The revoke-close-confirm control lives in the "การเงิน" tab, outside the money section.
        revokeCloseConfirmation: vi.fn(),
        // Fulfilment: DealFulfilmentPanel's own mutations, same self-contained pattern.
        issueImportRequest: vi.fn(),
        markIrSent: vi.fn(),
        markShipping: vi.fn(),
        markGoodsReceived: vi.fn(),
      },
      attachments: {
        list: vi.fn(),
        // upload/delete: exercised by the "attachment upload reports back" guard below.
        upload: vi.fn(),
        delete: vi.fn(),
        fileUrl: (id) => `#mock-file-${id}`,
      },
      // PricingRequestPanel (below the items table), DealStagePanel's substep strip, and
      // DealStateHeader's PCR chip all read this list. DealQuotationPanel reads the customer-quotation
      // tail for whichever request reaches APPROVED_FOR_QUOTATION+.
      pricingRequests: {
        listForTicket: vi.fn(),
        get: vi.fn(),
        listCustomerQuotations: vi.fn(),
        createCustomerQuotation: vi.fn(),
        issueCustomerQuotation: vi.fn(),
        recordCustomerQuotationOutcome: vi.fn(),
        confirmOrder: vi.fn(),
        submit: vi.fn(),
        createDepositNoticeFromQuotation: vi.fn(),
        downloadCustomerQuotationPdf: vi.fn(),
        downloadCustomerQuotationXlsx: vi.fn(),
      },
      // PricingRequestCreateModal's header-terms section fetches the same eligible-display-name list
      // the direct-deal quotation editor uses.
      dealQuotations: {
        displayNameOptions: vi.fn().mockResolvedValue({ items: [] }),
        // DealDirectQuotationPanel's list, and the APPROVED direct quotation's ยืนยันคำสั่งซื้อ from the
        // sticky CTA. listForTicket is only given a resolved value inside the direct-quotation
        // describe block, so every other test renders that panel as before.
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
        // Direct-quotation deals: the sticky สร้างใบแจ้งยอดเงินรับมัดจำ creates the draft this way.
        createDraft: vi.fn(),
        issue: vi.fn(),
        preview: vi.fn(),
        downloadXlsx: vi.fn(),
        downloadPdf: vi.fn(),
        noteTemplates: vi.fn(),
      },
      // The STORED remaining invoice aggregate: RemainingInvoiceDialog always checks it first, so an
      // undefined namespace would throw before reaching the stateless preview
      // (tickets.remainingInvoiceOptions). Defaults to "no live document yet", matching every fixture.
      storedRemainingInvoices: {
        listForTicket: vi.fn().mockResolvedValue({ remainingInvoices: [] }),
        createDraft: vi.fn(),
        update: vi.fn(),
        issue: vi.fn(),
        revise: vi.fn(),
        deleteDraft: vi.fn(),
        download: vi.fn(),
      },
      // The items table converts a foreign-currency factory price to baht. Mocked because an
      // unmocked namespace makes every fxRates.list() call throw (api.fxRates is undefined),
      // which would silently leave the baht line absent rather than exercising it.
      fxRates: {
        list: vi.fn(),
      },
      // ใบขอซื้อ (F-SM-001). Mocked like fxRates: DealFulfilmentPanel's IR block queries brands for
      // every import/CEO viewer, and an undefined namespace would throw rather than render the block.
      importRequests: {
        brands: vi.fn(),
        pages: vi.fn(),
        download: vi.fn(),
      },
      // The STORED ใบขอซื้อรายโรงงาน section DealFulfilmentPanel queries for every canReadStoredIr
      // viewer (sales owner/CEO/import/sales_manager); like `importRequests` above, an undefined
      // namespace throws a TypeError, which the panel surfaces as a genuine error. Defaults to an
      // empty list (the "not IR-tracked" case every legacy-chain test assumes); tests override with
      // mockResolvedValueOnce where the per-factory aggregate is under test.
      storedImportRequests: {
        listForTicket: vi.fn().mockResolvedValue({ importRequests: [] }),
        createDrafts: vi.fn(),
        get: vi.fn(),
        update: vi.fn(),
        issue: vi.fn(),
        revise: vi.fn(),
        deleteDraft: vi.fn(),
        advanceStep: vi.fn(),
        setLeadTime: vi.fn(),
        updateEmailDraft: vi.fn(),
        markEmailSent: vi.fn(),
        download: vi.fn(),
        setRequiredByNote: vi.fn(),
      },
      priceImport: {
        countries: vi.fn().mockResolvedValue([]),
      },
      // Edit-items mode's ยี่ห้อ/รุ่น fields share TicketCreateModal's CatalogAutocompleteField, which
      // searches this on every keystroke (debounced).
      catalog: {
        prices: vi.fn(),
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
    // Read-only fallback-to-quotation-items flag (TicketDto#fromPricingChain). Defaults to false/absent.
    fromPricingChain: overrides.fromPricingChain ?? false,
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
      // GENERATE_QUOTATION/MARK_QUOTATION_* are retired; the real actions() endpoint no longer
      // advertises them either.
      availableActions: [],
    });
    api.attachments.list.mockResolvedValue({ attachments: [] });
    api.attachments.upload.mockResolvedValue({ attachment: { id: 9, fileName: 'po.pdf' } });
    api.attachments.delete.mockResolvedValue({});
    api.tickets.listPayments.mockResolvedValue({ items: [] });
    api.tickets.listDeliveries.mockResolvedValue({ items: [] });
    api.tickets.comment.mockResolvedValue({ ticket: buildTicket() });
    // H1: recordPayment/confirmFinalPayment/confirmCloseReady are re-pointed at the finance routes and
    // return the finance view `{ deal }`, NOT `{ ticket }` (see hrApi.js `tickets.recordPayment`).
    api.tickets.recordPayment.mockResolvedValue({ deal: { id: 701 } });
    api.tickets.reserveStock.mockResolvedValue({ ticket: buildTicket() });
    api.tickets.recordDelivery.mockResolvedValue({ ticket: buildTicket() });
    api.tickets.completeDelivery.mockResolvedValue({ ticket: buildTicket() });
    api.tickets.revision.mockResolvedValue({ ticket: buildTicket() });
    api.tickets.editItems.mockResolvedValue({ ticket: buildTicket() });
    // Default: no catalog matches. Tests that exercise the edit-items brand/model autocomplete
    // set their own — see the "edit-items required fields" describe block below.
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
    // DealDepositPanel mounts for every role that isn't 'import' (sections.depositNotice), so its list
    // query needs a default resolve or every test above would reject.
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

  // Ticket-native submit/pickup/propose-price/calculate-prices/override-item-price/approve/reject/
  // generate-quotation/mark-quotation-* have no route, hrApi method or render path. These assert the
  // dead controls are GONE even under a (deliberately unrealistic) actions() payload that still lists
  // the retired verbs, proving the page no longer reads them at all.
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

  // The state header sits above every other section and names the sales stage, PCR status,
  // payment/fulfilment status, and deal value at a glance.
  it('renders the DealStateHeader stat strip', async () => {
    api.tickets.get.mockResolvedValueOnce({
      ticket: buildTicket({ summary: { salesStage: 'QUOTE_DESIGN_SIDE', amountPayable: 50000 } }),
    });

    renderTicketDetailPage();

    expect(await screen.findByText('ขั้นตอนดีล')).not.toBeNull();
    // DealStagePanel below also names the current stage: assert presence, not uniqueness.
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
    // The items table lives in its own "สินค้าและราคา" tab, not the default "ดีล" tab.
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
    // Desktop header clearance for focus/jumps sits on the content wrapper (the chrome's next
    // sibling) so the header's own controls never get it.
    expect(stickyChrome.nextElementSibling.className).toContain(
      'min-[721px]:[&_*:not([role=dialog]_*)]:scroll-mt-[calc(var(--deal-header-h,18rem)_+_var(--space-4))]',
    );

    // No sticky context rail (and no `data-testid="ticket-context-rail"` element); `--deal-header-h`
    // stays defined (index.css) and consumed by the content wrapper's descendant
    // `scroll-mt-[...]` (see ticketWorkspaceLayout.test.js).
    expect(screen.queryByTestId('ticket-context-rail')).toBeNull();
  });

  it('marks the sticky chrome with data-sticky-chrome so the shell scroller opts out of scroll anchoring (condensing would otherwise pull scrollTop back up by the shrink)', async () => {
    renderTicketDetailPage();

    const chrome = await screen.findByTestId('ticket-detail-sticky-chrome');
    expect(chrome.hasAttribute('data-sticky-chrome')).toBe(true);
  });

  // Regression guard for the sticky-chrome jitter: the header must condense off the two sentinels'
  // IntersectionObserver visibility, NEVER off a scroller.scrollTop threshold (condense shrinks the
  // page, scrollTop re-crosses the threshold: the page bounced / snapped to the top), and with
  // HYSTERESIS so slow scrolling at the trigger can't flip-flop. jsdom has no layout and no real
  // IntersectionObserver, so this pins the WIRING (condense follows sentinel visibility + the
  // dead-band); no-oscillation is verified in a real browser.
  describe('sticky chrome condense is sentinel-driven with hysteresis (not scrollTop)', () => {
    // Restore ONLY IntersectionObserver — vi.unstubAllGlobals() would also revert globals the
    // shared setup stubs (e.g. URL.createObjectURL), breaking later files in the same worker.
    const originalIntersectionObserver = globalThis.IntersectionObserver;
    afterEach(() => {
      if (originalIntersectionObserver === undefined) delete globalThis.IntersectionObserver;
      else globalThis.IntersectionObserver = originalIntersectionObserver;
    });

    it('condenses past the lower sentinel, holds through the dead-band, expands at the top', async () => {
      const ioInstances = [];
      class MockIntersectionObserver {
        constructor(cb, opts) { this.cb = cb; this.opts = opts; this.elements = []; ioInstances.push(this); }
        observe(el) { this.elements.push(el); }
        unobserve() {}
        disconnect() {}
        takeRecords() { return []; }
      }
      vi.stubGlobal('IntersectionObserver', MockIntersectionObserver);
      // The owner's approved deal, so the header has a ⋯ menu (ขอแก้ไข) for the open-menu check below.
      api.tickets.get.mockResolvedValue({ ticket: buildTicket({ summary: { status: 'approved', createdById: 1 } }) });

      renderTicketDetailPage(salesOwnerUser);
      const header = await screen.findByTestId('deal-state-header');
      expect(header.getAttribute('data-condensed')).toBe('false'); // expanded at the top

      const expandSentinel = document.querySelector('[data-testid="deal-chrome-expand-sentinel"]');
      const condenseSentinel = document.querySelector('[data-testid="deal-chrome-condense-sentinel"]');
      expect(expandSentinel).toBeTruthy();
      expect(condenseSentinel).toBeTruthy();
      // The condense observer is the one watching BOTH sentinels — proving the trigger is
      // sentinel visibility (with a dead-band), not a scrollTop read.
      const condenseIo = ioInstances.find((io) => io.elements.length >= 2);
      expect(condenseIo).toBeTruthy();

      // Scrolled past the lower sentinel → condense.
      act(() => condenseIo.cb([{ target: condenseSentinel, isIntersecting: false }]));
      expect(screen.getByTestId('deal-state-header').getAttribute('data-condensed')).toBe('true');

      // Slow scroll back INTO the dead-band (lower sentinel re-enters) → must stay condensed.
      act(() => condenseIo.cb([{ target: condenseSentinel, isIntersecting: true }]));
      expect(screen.getByTestId('deal-state-header').getAttribute('data-condensed')).toBe('true');

      // All the way back to the very top (top sentinel visible) → expand.
      act(() => condenseIo.cb([{ target: expandSentinel, isIntersecting: true }]));
      expect(screen.getByTestId('deal-state-header').getAttribute('data-condensed')).toBe('false');

      // Review V10: condensing moves the header's buttons elsewhere, which remounts and closes an open
      // menu — so the header holds still while one is open.
      fireEvent.click(screen.getByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));
      act(() => condenseIo.cb([{ target: condenseSentinel, isIntersecting: false }]));
      expect(screen.getByTestId('deal-state-header').getAttribute('data-condensed')).toBe('false');
      expect(screen.getByRole('menu')).not.toBeNull();
      // Closing the menu applies what was held back.
      fireEvent.keyDown(document, { key: 'Escape' });
      expect(screen.getByTestId('deal-state-header').getAttribute('data-condensed')).toBe('true');
    });
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

  // A ตร.ม. line shows its area; the small "N แผ่น" line is for a count that is real: derived from a tile
  // factor, or typed above the new-row default 1. Deal 331 read "400.00 ตร.ม. / 1 แผ่น" from a placeholder 1.
  describe('ตร.ม. line quantity cell', () => {
    async function cellFor(qty, sqmPerPiece = null) {
      api.tickets.get.mockResolvedValueOnce({
        ticket: buildTicket({
          items: [
            { id: 70201, brand: 'Padana', model: 'Verde', size: '60x60', qty, qtySqm: 400, unitBasis: 'SQM', sqmPerPiece,
              qtyDelivered: 0, qtyFromStock: 0, approvedPrice: null, rawPrice: null, rawCurrency: null },
          ],
        }),
      });
      renderTicketDetailPage();
      await openTab(/สินค้าและราคา/);
      return (await screen.findByText('400.00 ตร.ม.')).closest('span');
    }

    it('hides the แผ่น line for the placeholder qty 1 with no tile factor', async () => {
      expect((await cellFor(1)).textContent).toBe('400.00 ตร.ม.');
    });

    it('hides the แผ่น line for qty 0 with no tile factor', async () => {
      expect((await cellFor(0)).textContent).toBe('400.00 ตร.ม.');
    });

    it('shows a count typed above 1 with no tile factor', async () => {
      expect((await cellFor(1113)).textContent).toBe('400.00 ตร.ม.1113 แผ่น');
    });

    it('shows the count whenever a tile factor derived it, even 1', async () => {
      expect((await cellFor(1, 0.72)).textContent).toBe('400.00 ตร.ม.1 แผ่น');
    });
  });

  // The factory price in ราคาโรงงาน carries a plain baht conversion beside it when quoted in a
  // foreign currency (the live catalog is EUR + USD, and sales.fx_rates covers both). There is no
  // ราคาตั้ง (ประมาณการ) estimate column.
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

  // sales.ticket_item stays empty until order confirmation, so a deal still in its
  // pricing-request/quotation chain would show "0 รายการ". The backend falls back to the quotation's
  // items (TicketDto#fromPricingChain); this describes the frontend's read-only rendering of that.
  describe('items grid: read-only fallback to pricing-chain quotation items (B5 fix)', () => {
    it('shows the fallback quotation items read-only, with the banner, and hides the edit button', async () => {
      api.tickets.get.mockResolvedValueOnce({
        ticket: buildTicket({
          summary: { status: 'submitted', createdById: 1 },
          items: [
            { id: 90001, brand: 'SCG', model: 'Elegance', color: 'ขาว', texture: 'ด้าน', size: '60x60', qty: 20, qtyDelivered: 0, qtyFromStock: 0, proposedPrice: null, approvedPrice: null },
          ],
          fromPricingChain: true,
        }),
      });
      api.tickets.actions.mockResolvedValueOnce({
        currentState: {
          lifecycle: 'ACTIVE', salesStage: 'LEAD', paymentStatus: null, fulfillmentStatus: null, status: 'submitted',
        },
        availableActions: [{ action: 'EDIT_ITEMS', kind: 'operational', label: 'แก้ไขรายการสินค้า' }],
      });

      renderTicketDetailPage(salesOwnerUser);
      await openTab(/สินค้าและราคา/);

      // The borrowed line renders, read-only, with the count reflecting it.
      expect(await screen.findByRole('heading', { level: 2, name: 'รายการสินค้า (1 รายการ)' })).not.toBeNull();
      expect(screen.getByText('SCG')).not.toBeNull();
      expect(screen.getByText('Elegance')).not.toBeNull();

      // The banner is present...
      expect(screen.getByTestId('items-pricing-chain-banner')).not.toBeNull();
      expect(screen.getByText(/รายการจากใบเสนอราคา/)).not.toBeNull();
      // ...and the edit affordance (the only entry point to edit mode / the add-item control)
      // is gone, even though the actions() response advertises EDIT_ITEMS and the role/status/
      // ownership gate would otherwise allow it — proves the fallback-specific guard, not just
      // the pre-existing role gate.
      expect(screen.queryByRole('button', { name: 'แก้ไขรายการสินค้า' })).toBeNull();
    });

    it('a deal WITH real ticket_item rows stays fully editable, with no banner', async () => {
      api.tickets.get.mockResolvedValueOnce({
        ticket: buildTicket({
          summary: { status: 'submitted', createdById: 1 },
          items: [
            { id: 70101, brand: 'SCG', model: 'A1', size: '60x60', qty: 10, qtyDelivered: 0, qtyFromStock: 0, proposedPrice: null, approvedPrice: null },
          ],
          fromPricingChain: false,
        }),
      });
      api.tickets.actions.mockResolvedValueOnce({
        currentState: {
          lifecycle: 'ACTIVE', salesStage: 'LEAD', paymentStatus: null, fulfillmentStatus: null, status: 'submitted',
        },
        availableActions: [{ action: 'EDIT_ITEMS', kind: 'operational', label: 'แก้ไขรายการสินค้า' }],
      });

      renderTicketDetailPage(salesOwnerUser);
      await openTab(/สินค้าและราคา/);

      expect(await screen.findByRole('button', { name: 'แก้ไขรายการสินค้า' })).not.toBeNull();
      expect(screen.queryByTestId('items-pricing-chain-banner')).toBeNull();
    });

    it('a deal with neither ticket_item nor a fallback keeps the existing empty state', async () => {
      api.tickets.get.mockResolvedValueOnce({
        ticket: buildTicket({ items: [], fromPricingChain: false }),
      });

      renderTicketDetailPage(salesOwnerUser);
      await openTab(/สินค้าและราคา/);

      expect(await screen.findByRole('heading', { level: 2, name: 'รายการสินค้า (0 รายการ)' })).not.toBeNull();
      expect(screen.getByText('ไม่มีรายการสินค้า')).not.toBeNull();
      expect(screen.queryByTestId('items-pricing-chain-banner')).toBeNull();
    });
  });

  // A quotation-only container ticket (V193) renders exactly like any other deal (IA §7, "Nothing
  // hidden"): no "not in the pipeline" banner, and the stage panel and primary CTA are present. Both
  // shapes are asserted to render the same controls.
  describe('quotation-first deal (GLA-136 hiding reverted, IA §7)', () => {
    it.each([[true], [false]])('quotationOnly=%s: stage panel and primary CTA present, no "not in the pipeline" banner', async (quotationOnly) => {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({ summary: { status: 'draft', salesStage: 'SPEC_APPROVED', lifecycle: 'ACTIVE', createdById: 1, quotationOnly } }),
      });
      renderTicketDetailPage(salesOwnerUser);

      expect(await screen.findByTestId('deal-stage-panel')).not.toBeNull();
      await waitFor(() => expect(screen.getByTestId('ticket-primary-action')).not.toBeNull());
      expect(screen.queryByTestId('ticket-quotation-only-banner')).toBeNull();
      expect(screen.queryByText(/ยังไม่เข้า pipeline/)).toBeNull();
    });
  });

  // วันสำคัญ / ผู้เกี่ยวข้อง sit at the top of ภาพรวม and render whenever this tab
  // is active (there is no collapse/accordion to drive).
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
        // A COMMENTED event, planted to prove the old read-only "3 most recent comments" roll-up is
        // gone (DealHistoryPanel renders the full stream on กิจกรรม): this message must NOT surface on
        // ภาพรวม.
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
    // วางบิล is out of scope: neither the billing date nor the due date has a row here, even when set.
    expect(within(keyDatesSection).queryByText('วันวางบิล')).toBeNull();
    expect(within(keyDatesSection).queryByText('ครบกำหนดชำระ')).toBeNull();
    expect(within(keyDatesSection).queryByText('21 ก.ค. 2569')).toBeNull();
    expect(within(keyDatesSection).queryByText('31 ก.ค. 2569')).toBeNull();

    const peopleHeading = screen.getByRole('heading', { level: 2, name: /ผู้เกี่ยวข้อง/ });
    const peopleSection = peopleHeading.closest('section');
    // สมชาย ใจดี is also named in DealStateHeader's "สร้างโดย" line (always on
    // screen) — assert presence in THIS section specifically, not page-wide
    // uniqueness.
    expect(within(peopleSection).getByText('สมชาย ใจดี')).not.toBeNull();
    expect(within(peopleSection).getByText('ยังไม่มีคำขอราคา')).not.toBeNull();
    // Wrong-way-round: the deal shows no ผู้ติดต่อ (the contact is picked per quotation). FieldRow
    // renders its label even when empty, so the missing label proves the row is gone; the fixture
    // still sets contactName, so the missing value proves it doesn't surface another way.
    expect(within(peopleSection).queryByText('ผู้ติดต่อ')).toBeNull();
    expect(within(peopleSection).queryByText('คุณอรุณ ติดต่อ')).toBeNull();

    // Wrong-way-round: the deleted roll-up's content must be absent from the whole page while ภาพรวม
    // is active.
    expect(screen.queryByText('บันทึกสำหรับบริบทดีล')).toBeNull();
  });

  // The backend only writes summary.lastFollowUpAt from the CEO billing form, so logging an
  // activity never moved "ติดตามล่าสุด". The card shows the later of that field and the newest
  // activity the page already fetched.
  it.each([
    ['no billing-form date: the newest activity date', null, '2026-10-25'],
    ['a billing-form date later than every activity: that date', '2026-11-02', '2026-11-02'],
    ['a billing-form date older than the newest activity: the activity date', '2026-10-22', '2026-10-25'],
  ])('วันสำคัญ ติดตามล่าสุด shows the latest follow-up — %s', async (_name, lastFollowUpAt, expectedIso) => {
    api.tickets.get.mockResolvedValueOnce({
      ticket: buildTicket({ summary: { lifecycle: 'ACTIVE', salesStage: 'QUOTE_DESIGN_SIDE', lastFollowUpAt } }),
    });
    api.tickets.listActivities.mockResolvedValue({
      items: [
        { id: 1, ticketId: 701, activityDate: '2026-10-20', kind: 'CALL', note: 'a', createdAt: '2026-10-20T09:00:00.000Z' },
        { id: 2, ticketId: 701, activityDate: '2026-10-25', kind: 'CALL', note: 'b', createdAt: '2026-10-25T09:00:00.000Z' },
      ],
    });

    renderTicketDetailPage(salesOwnerUser);

    const keyDates = (await screen.findByRole('heading', { level: 2, name: /วันสำคัญ/ })).closest('section');
    const row = within(keyDates).getByText('ติดตามล่าสุด').parentElement;
    await waitFor(() => expect(within(row).getByText(formatThaiDate(expectedIso))).not.toBeNull());
  });

  // One comment composer: DealHistoryPanel is the only one in the codebase, and since Tabs.jsx
  // unmounts every inactive TabPanel it is reachable from exactly the one tab it lives in. Checking
  // both tabs is what the mutation-check exercises: reintroducing a rail-shaped duplicate makes the
  // ภาพรวม assertion (0) go red.
  it('renders the comment composer exactly once, only inside the ประวัติ tab', async () => {
    renderTicketDetailPage(salesOwnerUser);
    await screen.findByRole('heading', { level: 1, name: 'บริษัท ทดสอบ จำกัด' });

    // ดีล is the default tab. Wrong-way-round: no composer anywhere on the page while it's active.
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

  // The "รอ<ฝ่าย>" waiting bar is gone from the page itself: workState.test.js pins the resolver,
  // this pins where it rendered.
  it('renders no "รอ…" waiting line in the sticky action bar', async () => {
    api.tickets.get.mockResolvedValueOnce({ ticket: buildTicket() });
    renderTicketDetailPage(salesOwnerUser);
    await screen.findByTestId('deal-stage-panel');
    const bar = screen.queryByTestId('ticket-action-bar');
    expect(bar?.textContent ?? '').not.toMatch(/รอ(ฝ่าย|CEO)/);
  });

  // The stage panel's "เอกสารของขั้นนี้" row is a link to a document, not an action, so it is not a
  // full-size bordered secondary button.
  it('offers the quotation PDF as a compact inline link, not a full-size bordered button', async () => {
    api.tickets.get.mockResolvedValueOnce({
      ticket: buildTicket({
        quotations: [{
          id: 9001, ticketId: 701, number: 'QT-2026-0901', issuedById: 1, issuedByName: 'สมชาย ใจดี',
          issuedAt: '2026-07-03T09:00:00.000Z', totalAmount: 1000, currency: 'THB', quotationVersion: 1,
          docStatus: 'ISSUED', recipientType: 'DESIGNER', recipientLabel: 'Design Studio',
        }],
      }),
    });
    renderTicketDetailPage(salesOwnerUser);
    const link = await screen.findByRole('button', { name: /^ใบเสนอราคา QT-2026-0901 \(PDF\)$/ });
    expect(link.className).not.toContain('border-border-input');
    expect(link.className).toContain('text-primary');
    expect(link.className).toContain('min-h-0');
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

    // Scoped to DealLegacyQuotations' own panel (not `screen` globally): DealDocumentRegister also
    // lists these legacy rows in the same tab ("a register is an index, not a replacement for the
    // panel it indexes"), so an unscoped query matches twice.
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
    // The billing control left this page: even with SET_BILLING offered there is no button for it.
    expect(screen.queryByRole('button', { name: 'ตั้งค่าการวางบิล' })).toBeNull();
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
    expect((await screen.findByText(/คุณบัญชี/)).closest('li').textContent).toContain('มัดจำ');
    // The receipt row itself really did render (not just the tile) — proves
    // the scoped assertion above didn't accidentally start passing vacuously.
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
    await openTab(/การเงิน/);

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
    await openTab(/การเงิน/);

    await screen.findByRole('heading', { level: 2, name: 'การชำระเงิน' });
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
      availableActions: [],
    });

    renderTicketDetailPage(accountUser);

    expect(await screen.findByRole('heading', { level: 1, name: 'บริษัท ทดสอบ จำกัด' })).not.toBeNull();
    // PricingRequestPanel is an INNER render condition inside "สินค้าและราคา"
    // (pricing_accountCannotReadAPricingRequest, see ticketDetailTabs.js). Account still gets the
    // tab (the items table lives there), just not this panel, and never the fetch behind it.
    await openTab(/สินค้าและราคา/);
    expect(screen.queryByRole('heading', { name: 'คำขอราคา' })).toBeNull();
    expect(api.pricingRequests.listForTicket).not.toHaveBeenCalled();

    await openTab(/การเงิน/);
    expect((await screen.findByText(/คุณบัญชี/)).closest('li').textContent).toContain('มัดจำ');
    expect(screen.getAllByText('฿400.00').length).toBeGreaterThan(0);
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

    // The dialog states the real outstanding amount, sourced from the same
    // summary.amountOutstanding the payment panel's "คงเหลือ" tile renders
    // (not a separately-computed figure).
    expect(await screen.findByText('ยืนยันการรับชำระครบถ้วน')).not.toBeNull();
    expect(screen.getAllByText('฿32,500.00').length).toBeGreaterThan(0);

    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันชำระครบ' }));

    await waitFor(() => expect(api.tickets.confirmFinalPayment).toHaveBeenCalledWith(701));
    // Dialog closes after a successful confirm.
    await waitFor(() => expect(screen.queryByText('ยืนยันการรับชำระครบถ้วน')).toBeNull());
    // A `{ deal }` response carries no ticket: the page re-reads the ticket instead of writing
    // `undefined` into the detail cache (which would blank the page).
    await waitFor(() => expect(api.tickets.get.mock.calls.length).toBeGreaterThanOrEqual(2));
    expect(screen.getAllByText(/PR-2026-0701/).length).toBeGreaterThan(0);
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

    // The delivery progress, delivery HISTORY rows (คลังสินค้า source) and the record-delivery button
    // are only inside DealFulfilmentPanel, in the "จัดซื้อ-ส่งมอบ" tab (DealStagePanel has no copy).
    await openTab(/จัดซื้อ-ส่งมอบ/);
    expect((await screen.findAllByText('40 / 100')).length).toBeGreaterThan(0);
    expect(screen.getAllByText('ส่งมอบบางส่วน').length).toBeGreaterThan(0);
    expect(await screen.findByText('คลังสินค้า')).not.toBeNull();
    expect(screen.getByText(/SCG A1: 40/)).not.toBeNull();
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
    // The finance deal view (account/ceo) is money state too: the ceo can open it, so it must go stale.
    queryClient.setQueryData(queryKeys.financeDeal(701), { deal: {} });

    await screen.findByRole('heading', { level: 1, name: 'บริษัท ทดสอบ จำกัด' });
    // The comment box lives in DealHistoryPanel, in the "ประวัติ" tab.
    await openTab(/ประวัติ/);

    const textarea = screen.getByPlaceholderText('เพิ่มความคิดเห็น…');
    fireEvent.change(textarea, { target: { value: 'ทดสอบความคิดเห็น' } });
    fireEvent.click(screen.getByRole('button', { name: 'ส่งความคิดเห็น' }));

    await waitFor(() => expect(api.tickets.comment).toHaveBeenCalledWith(701, { message: 'ทดสอบความคิดเห็น' }));

    await waitFor(() => {
      expect(queryClient.getQueryState(['tickets', 'list', ''])?.isInvalidated).toBe(true);
      expect(queryClient.getQueryState(queryKeys.dashboardSummary())?.isInvalidated).toBe(true);
      expect(queryClient.getQueryState(queryKeys.notifications())?.isInvalidated).toBe(true);
      expect(queryClient.getQueryState(queryKeys.financeDeal(701))?.isInvalidated).toBe(true);
    });
  });

  // ── Inline validation for the quotation/payment/delivery modals, mirroring the aria-invalid +
  // aria-describedby + role="alert" contract of TicketCreateModal.jsx / DepositNoticePage.jsx. ──

  // recordPayment is account-only, but renderTicketDetailPage()'s default actor is ceoUser: render as
  // accountUser so this test reaches the button.
  it('payment modal: submitting with an empty amount marks the amount field inline and does not call recordPayment', async () => {
    api.tickets.actions.mockResolvedValueOnce({
      currentState: {
        lifecycle: 'ACTIVE', salesStage: 'DEPOSIT_RECEIVED', paymentStatus: null, fulfillmentStatus: null, status: 'price_proposed',
      },
      availableActions: [{ action: 'RECORD_PAYMENT', kind: 'payment', label: 'บันทึกรับชำระเงิน' }],
    });

    renderTicketDetailPage(accountUser);
    // "บันทึกรับชำระเงิน" lives in the "การเงิน" tab's payment section; the Modal, once opened, stays
    // mounted regardless of the active tab (it's not inside any TabPanel).
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

  // The delivery modal (and its "at least 1 line qty > 0" guard) lives in DealFulfilmentPanel, which
  // reports it via a toast (no aria-invalid/fieldErrors). Full coverage is in the 'deal fulfilment
  // panel' describe block below; this is the "shows a toast, not a crash" case.
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
    // DealFulfilmentPanel lives in the "จัดซื้อ-ส่งมอบ" tab.
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

  // ── The remaining inline page-body validations (revise / edit-items qty). ──

  it('edit-items: multiple invalid rows each get their own inline qty error — not one shared message', async () => {
    api.tickets.get.mockResolvedValueOnce({
      ticket: buildTicket({
        summary: { status: 'submitted', createdById: 1 },
        // brand/model/size all filled, so this fixture stays valid on the required-field check (see
        // the required-field describe block below) and isolates per-row qty errors.
        items: [
          { id: 70101, brand: 'SCG', model: 'A1', size: '60x60', qty: 0, qtyDelivered: 0, qtyFromStock: 0, approvedPrice: null },
          { id: 70102, brand: 'Cotto', model: 'B2', size: '30x30', qty: 0, qtyDelivered: 0, qtyFromStock: 0, approvedPrice: null },
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

    // "แก้ไขรายการสินค้า" sits in the items table's panel header, inside the "สินค้าและราคา" tab.
    await openTab(/สินค้าและราคา/);
    fireEvent.click(await screen.findByRole('button', { name: 'แก้ไขรายการสินค้า' }));
    fireEvent.click(await screen.findByRole('button', { name: 'บันทึกการแก้ไข' }));

    // Each offending row gets its own inline error message and input (not one toast covering every
    // row). The message is the basis-aware missingQtyMessage create uses, row-numbered (see the save
    // handler's comment).
    expect(await screen.findByText('กรุณากรอกจำนวน (แผ่น) ในรายการที่ 1')).toBeTruthy();
    expect(await screen.findByText('กรุณากรอกจำนวน (แผ่น) ในรายการที่ 2')).toBeTruthy();

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
    expect(screen.queryByText('กรุณากรอกจำนวน (แผ่น) ในรายการที่ 1')).toBeNull();
    expect(screen.getByText('กรุณากรอกจำนวน (แผ่น) ในรายการที่ 2')).toBeTruthy();

    // Fixing row 2 too lets the save through with the unchanged payload shape.
    fireEvent.change(qtyInput1, { target: { value: '2' } });
    await waitFor(() => expect(qtyInput1.getAttribute('aria-invalid')).toBeNull());
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกการแก้ไข' }));
    await waitFor(() => expect(api.tickets.editItems).toHaveBeenCalledTimes(1));
    expect(api.tickets.editItems.mock.calls[0][0]).toBe(701);
    expect(api.tickets.editItems.mock.calls[0][1].items.map((it) => it.qty)).toEqual([3, 2]);
  });

  // "จากสต็อก" is just a flag on the deal line: the sales-typed ราคาขายจากสต็อก is gone (the deal shows
  // the CEO-approved price). The post-creation edit screen shows no price input, never blocks save
  // for want of one, and sends no stockSalePrice.
  it('edit-items: flagging a row "จากสต็อก" shows no price input and saves straight away with no stockSalePrice', async () => {
    api.tickets.get.mockResolvedValueOnce({
      ticket: buildTicket({
        summary: { status: 'submitted', createdById: 1 },
        items: [
          { id: 70101, brand: 'SCG', model: 'A1', size: '60x60', qty: 5, qtyDelivered: 0, qtyFromStock: 0, approvedPrice: null, sourcedFromStock: false, stockSalePrice: null },
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

    await openTab(/สินค้าและราคา/);
    fireEvent.click(await screen.findByRole('button', { name: 'แก้ไขรายการสินค้า' }));

    const checkbox = await screen.findByRole('checkbox', { name: 'จากสต็อก' });
    expect(checkbox.checked).toBe(false);
    fireEvent.click(checkbox);

    expect(checkbox.checked).toBe(true);
    expect(document.getElementById('edit-item-stock-sale-price-0')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'บันทึกการแก้ไข' }));

    await waitFor(() => expect(api.tickets.editItems).toHaveBeenCalledTimes(1));
    const sent = api.tickets.editItems.mock.calls[0][1].items[0];
    expect(sent).toMatchObject({ sourcedFromStock: true });
    expect(sent.stockSalePrice ?? null).toBeNull();
  });

  it('edit-items: a previously stock-sourced row (an old row that still carries a price) shows no price input, and unchecking sends sourcedFromStock:false', async () => {
    api.tickets.get.mockResolvedValueOnce({
      ticket: buildTicket({
        summary: { status: 'submitted', createdById: 1 },
        items: [
          { id: 70101, brand: 'SCG', model: 'A1', size: '60x60', qty: 5, qtyDelivered: 0, qtyFromStock: 0, approvedPrice: null, sourcedFromStock: true, stockSalePrice: 500 },
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

    await openTab(/สินค้าและราคา/);
    fireEvent.click(await screen.findByRole('button', { name: 'แก้ไขรายการสินค้า' }));

    const checkbox = await screen.findByRole('checkbox', { name: 'จากสต็อก' });
    expect(checkbox.checked).toBe(true);
    expect(document.getElementById('edit-item-stock-sale-price-0')).toBeNull();

    fireEvent.click(checkbox);
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกการแก้ไข' }));
    await waitFor(() => expect(api.tickets.editItems).toHaveBeenCalledTimes(1));
    const sent = api.tickets.editItems.mock.calls[0][1].items[0];
    expect(sent).toMatchObject({ sourcedFromStock: false });
    expect(sent.stockSalePrice ?? null).toBeNull();
  });

  // The read-only items table still flags a saved จากสต็อก line with the orange badge, but shows the
  // CEO-approved price, NEVER stock_sale_price. A legacy row that still carries one must not surface
  // it: not as the price, not as a secondary "ราคาสต็อก" line, in either price cell variant.
  it('read mode: a จากสต็อก line shows the badge but never its stockSalePrice; a normal line is unchanged', async () => {
    api.tickets.get.mockResolvedValueOnce({
      ticket: buildTicket({
        summary: { status: 'submitted', createdById: 1 },
        items: [
          { id: 70101, brand: 'SCG', model: 'A1', size: '60x60', qty: 5, qtyDelivered: 0, qtyFromStock: 0, approvedPrice: null, sourcedFromStock: true, stockSalePrice: 420.5 },
          { id: 70102, brand: 'Cotto', model: 'B2', size: '30x30', qty: 5, qtyDelivered: 0, qtyFromStock: 0, approvedPrice: 300, sourcedFromStock: false, stockSalePrice: null },
        ],
      }),
    });
    api.tickets.actions.mockResolvedValueOnce({
      currentState: {
        lifecycle: 'ACTIVE', salesStage: 'LEAD', paymentStatus: null, fulfillmentStatus: null, status: 'submitted',
      },
      availableActions: [],
    });

    renderTicketDetailPage(ceoUser);
    await openTab(/สินค้าและราคา/);

    expect(await screen.findByText('จากสต็อก')).toBeTruthy();
    expect(screen.queryByText('฿420.50')).toBeNull();
    expect(screen.queryByText(/ราคาสต็อก/)).toBeNull();
    // The non-stock row's own approved price still renders normally.
    expect(screen.getByText('฿300.00')).toBeTruthy();
  });

  it('read mode: a จากสต็อก line with approvedPrice set shows the approved price only - no secondary ราคาสต็อก line', async () => {
    api.tickets.get.mockResolvedValueOnce({
      ticket: buildTicket({
        summary: { status: 'submitted', createdById: 1 },
        items: [
          { id: 70101, brand: 'SCG', model: 'A1', size: '60x60', qty: 5, qtyDelivered: 0, qtyFromStock: 0, approvedPrice: 999, sourcedFromStock: true, stockSalePrice: 420.5 },
        ],
      }),
    });
    api.tickets.actions.mockResolvedValueOnce({
      currentState: {
        lifecycle: 'ACTIVE', salesStage: 'LEAD', paymentStatus: null, fulfillmentStatus: null, status: 'submitted',
      },
      availableActions: [],
    });

    renderTicketDetailPage(ceoUser);
    await openTab(/สินค้าและราคา/);

    expect(await screen.findByText('จากสต็อก')).toBeTruthy();
    expect(screen.getByText('฿999.00')).toBeTruthy();
    expect(screen.queryByText(/ราคาสต็อก/)).toBeNull();
    expect(screen.queryByText(/420\.50/)).toBeNull();
  });

  it('read mode: calc-breakdown variant (CEO, calcedCost set) does not fill the ราคาขาย cell with a stock price either', async () => {
    api.tickets.get.mockResolvedValueOnce({
      ticket: buildTicket({
        summary: { status: 'submitted', createdById: 1 },
        items: [
          {
            id: 70101, brand: 'SCG', model: 'A1', size: '60x60', qty: 5, qtyDelivered: 0, qtyFromStock: 0,
            approvedPrice: null, calcedCost: 100, calcedPrice: null, manualPrice: null,
            sourcedFromStock: true, stockSalePrice: 420.5,
          },
        ],
      }),
    });
    api.tickets.actions.mockResolvedValueOnce({
      currentState: {
        lifecycle: 'ACTIVE', salesStage: 'LEAD', paymentStatus: null, fulfillmentStatus: null, status: 'submitted',
      },
      availableActions: [],
    });

    renderTicketDetailPage(ceoUser);
    await openTab(/สินค้าและราคา/);

    // Calc-breakdown headers confirm the CEO variant rendered, not the plain ราคาที่อนุมัติ one.
    expect(await screen.findByText('ต้นทุน (THB/ชิ้น)')).toBeTruthy();
    expect(screen.getByText('ราคาขาย (THB/ชิ้น)')).toBeTruthy();
    expect(screen.getByText('จากสต็อก')).toBeTruthy();
    expect(screen.queryByText('฿420.50')).toBeNull();
    expect(screen.queryByText(/ราคาสต็อก/)).toBeNull();
  });

  // Edit-items must show the create modal's required-field asterisks/validation: a blank ยี่ห้อ/รุ่น/ขนาด
  // must fail inline before the API is called, not with a backend 400. These pin shared markers,
  // the shared catalog autocomplete, and that guard.
  describe('edit-items: required-field markers (ยี่ห้อ/รุ่น/ขนาด) match create, and catalog autocomplete', () => {
    function mockCatalogProduct(overrides = {}) {
      return {
        priceId: 501,
        productCode: 'BNFJ30126CA',
        factoryName: 'Bode',
        grade: null,
        collection: 'Stone gallary',
        productName: null,
        color: null,
        surface: 'MATT',
        sizeRaw: '600x1200',
        price: 8.8,
        currency: 'USD',
        priceUnit: 'per_sqm',
        sqmPerPiece: null,
        ...overrides,
      };
    }

    // item0Overrides seeds row 0 with fields the default fixture doesn't carry (e.g. a real
    // catalogPriceId/catalogProductCode, for the focus-does-not-mutate test below).
    async function openEditMode(item0Overrides = {}) {
      api.tickets.get.mockResolvedValueOnce({
        ticket: buildTicket({
          summary: { status: 'submitted', createdById: 1 },
          items: [
            {
              id: 70101, brand: 'SCG', model: 'A1', size: '60x60', factory: 'SCG Ceramics',
              color: 'ขาว', texture: 'ด้าน', qty: 10, qtyDelivered: 0, qtyFromStock: 0, approvedPrice: null,
              ...item0Overrides,
            },
            {
              id: 70102, brand: 'Cotto', model: 'B2', size: '30x30', factory: 'Cotto Industry',
              color: null, texture: null, qty: 5, qtyDelivered: 0, qtyFromStock: 0, approvedPrice: null,
            },
          ],
        }),
      });
      api.tickets.actions.mockResolvedValueOnce({
        currentState: { lifecycle: 'ACTIVE', salesStage: 'LEAD', paymentStatus: null, fulfillmentStatus: null, status: 'submitted' },
        availableActions: [{ action: 'EDIT_ITEMS', kind: 'operational', label: 'แก้ไขรายการสินค้า' }],
      });
      renderTicketDetailPage(salesOwnerUser);
      await openTab(/สินค้าและราคา/);
      fireEvent.click(await screen.findByRole('button', { name: 'แก้ไขรายการสินค้า' }));
      // Confirms edit mode has actually mounted before a test starts poking at row fields — the
      // save button is unique (unlike the per-row ยี่ห้อ label/placeholder, which repeats once per
      // row and would make findByLabelText/findByPlaceholderText throw on "multiple elements").
      await screen.findByRole('button', { name: 'บันทึกการแก้ไข' });
    }

    it('ยี่ห้อ/รุ่น/ขนาด carry the red required asterisk; สี/เนื้อผิว do not', async () => {
      await openEditMode();
      const brandLabel = document.querySelector('label[for="edit-item-0-brand"]');
      const modelLabel = document.querySelector('label[for="edit-item-0-model"]');
      const sizeLabel = document.querySelector('label[for="edit-item-0-size"]');
      const colorLabel = document.querySelector('label[for="edit-item-0-color"]');
      const textureLabel = document.querySelector('label[for="edit-item-0-texture"]');
      expect(brandLabel.textContent).toContain('*');
      expect(modelLabel.textContent).toContain('*');
      expect(sizeLabel.textContent).toContain('*');
      expect(colorLabel.textContent).not.toContain('*');
      expect(textureLabel.textContent).not.toContain('*');
    });

    // The source-text pin test alone (ticketItemFields.test.jsx) does not catch a hand-typed literal
    // passed into CatalogAutocompleteField's `label` prop (it renders whatever it's given). This
    // asserts the actual RENDERED text, which a hand-typed label cannot fake.
    it('ยี่ห้อ/รุ่น labels render the exact canonical text from ITEM_FIELD_META — not a hand-typed drift like ชื่อยี่ห้อ', async () => {
      await openEditMode();
      const brandLabel = document.querySelector('label[for="edit-item-0-brand"]');
      const modelLabel = document.querySelector('label[for="edit-item-0-model"]');
      expect(brandLabel.textContent).toBe(ITEM_FIELD_META.brand.label + ' *');
      expect(modelLabel.textContent).toBe(ITEM_FIELD_META.model.label + ' *');
      expect(screen.queryByText('ชื่อยี่ห้อ')).toBeNull();
      expect(screen.queryByText('ชื่อรุ่น')).toBeNull();
    });

    it('there is no separate โรงงาน input — ยี่ห้อ and โรงงาน are one field, same as create', async () => {
      await openEditMode();
      expect(document.getElementById('edit-item-0-factory')).toBeNull();
      // A standalone "โรงงาน" label (not "ยี่ห้อ / โรงงาน") would mean the old separate input came back.
      expect(screen.queryByText('โรงงาน')).toBeNull();
    });

    it('clearing ยี่ห้อ and saving blocks the save with an inline required-field error', async () => {
      await openEditMode();
      const brandInput = document.getElementById('edit-item-0-brand');
      fireEvent.change(brandInput, { target: { value: '' } });
      fireEvent.click(screen.getByRole('button', { name: 'บันทึกการแก้ไข' }));

      expect(await screen.findByText('กรุณากรอกยี่ห้อ / โรงงาน')).toBeTruthy();
      expect(brandInput.getAttribute('aria-invalid')).toBe('true');
      expect(api.tickets.editItems).not.toHaveBeenCalled();
    });

    it('typing into ยี่ห้อ then picking a catalog result fills model/size and carries the catalog link + factory into the payload; an untouched row keeps its original factory', async () => {
      api.catalog.prices.mockResolvedValue({ items: [mockCatalogProduct()] });
      await openEditMode();

      const brandInput = document.getElementById('edit-item-0-brand');
      fireEvent.change(brandInput, { target: { value: 'Bode' } });
      const leaf = await screen.findByText('Bode');
      fireEvent.mouseDown(leaf.parentElement);
      await waitFor(() => expect(brandInput.value).toBe('Bode'));

      fireEvent.click(screen.getByRole('button', { name: 'บันทึกการแก้ไข' }));
      await waitFor(() => expect(api.tickets.editItems).toHaveBeenCalledTimes(1));

      const payloadItems = api.tickets.editItems.mock.calls[0][1].items;
      expect(payloadItems[0]).toMatchObject({
        brand: 'Bode',
        factory: 'Bode',
        model: 'Stone gallary',
        size: '600x1200',
        catalogPriceId: 501,
        catalogProductCode: 'BNFJ30126CA',
      });
      // Row 1 was never touched: its factory must round-trip unchanged, not fall back to null or
      // pick up row 0's newly-picked factory.
      expect(payloadItems[1]).toMatchObject({ brand: 'Cotto', factory: 'Cotto Industry' });
    });

    // Re-focusing an already-filled ยี่ห้อ/รุ่น box (no retyping, value unchanged) must not run the
    // mutating keystroke path (onEditCatalogInput -> applyDescriptiveFieldEdit), which re-runs the
    // brand->factory lockstep and clears the catalog link. Row {factory: 'Bode Factory',
    // catalogPriceId: 501, catalogProductCode: 'BN1'}, focus brand then model, save: the payload
    // factory must not become 'Bode' and catalogPriceId must not go null. That would corrupt data
    // PricingRequestCreateModal.emptyItemFromTicketItem seeds its own catalog link from.
    it('re-focusing ยี่ห้อ/รุ่น on a row that already has a catalog link does NOT clear it (search-only focus, no row mutation)', async () => {
      await openEditMode({
        brand: 'Bode', model: 'Stone gallary', factory: 'Bode Factory',
        catalogPriceId: 501, catalogProductCode: 'BN1',
      });

      const brandInput = document.getElementById('edit-item-0-brand');
      const modelInput = document.getElementById('edit-item-0-model');
      // Re-focus both fields with no keystroke in between — simulates tabbing back through the row.
      fireEvent.focus(brandInput);
      fireEvent.focus(modelInput);

      fireEvent.click(screen.getByRole('button', { name: 'บันทึกการแก้ไข' }));
      await waitFor(() => expect(api.tickets.editItems).toHaveBeenCalledTimes(1));

      const payloadItems = api.tickets.editItems.mock.calls[0][1].items;
      expect(payloadItems[0]).toMatchObject({
        brand: 'Bode',
        factory: 'Bode Factory',
        catalogPriceId: 501,
        catalogProductCode: 'BN1',
      });
    });

    it('deleting a row re-indexes a required-field error to the row that shifted into its place', async () => {
      await openEditMode();
      const brandInput1 = document.getElementById('edit-item-1-brand');
      fireEvent.change(brandInput1, { target: { value: '' } });
      fireEvent.click(screen.getByRole('button', { name: 'บันทึกการแก้ไข' }));
      expect(await screen.findByText('กรุณากรอกยี่ห้อ / โรงงาน')).toBeTruthy();
      expect(document.getElementById('edit-item-1-brand').getAttribute('aria-invalid')).toBe('true');
      expect(api.tickets.editItems).not.toHaveBeenCalled();

      // Delete row 0 (the row WITHOUT the error): row 1 shifts down to index 0, and its error must
      // follow it, not stay pinned to the old index or vanish.
      fireEvent.click(screen.getByRole('button', { name: 'ลบรายการที่ 1' }));

      await waitFor(() => {
        expect(document.getElementById('edit-item-0-brand').getAttribute('aria-invalid')).toBe('true');
      });
      expect(screen.getByText('กรุณากรอกยี่ห้อ / โรงงาน')).toBeTruthy();
    });

    // The qty rule uses requiredQtyField/missingQtyMessage, as create does, instead of unconditionally
    // checking item.qty. A SQM-basis row with NO ตร.ม./แผ่น factor (qty is a read-only derived display)
    // must not be blocked on a qty=0 it can't fill, and must send exactly what the rep typed into
    // พื้นที่ (ตร.ม.).
    // The label also heads the table column, so pick the edit form's read-only box by its muted style.
    const readOnlyPieceBox = () => screen.getAllByText('จำนวน (แผ่น)')
      .map((el) => el.nextElementSibling)
      .find((n) => n?.tagName === 'DIV' && n.className.includes('bg-surface-muted'));

    // The read-only จำนวน (แผ่น) box under พื้นที่ must follow the same rule as the table cell: a saved
    // placeholder qty 1 is not shown as a tile count (deal 331: "1 แผ่น" beside 400 ตร.ม.).
    it('SQM-basis row with no factor and the placeholder qty 1: the read-only แผ่น box shows —', async () => {
      await openEditMode({ unitBasis: 'SQM', qty: 1, qtySqm: 400, sqmPerPiece: null });
      expect(readOnlyPieceBox().textContent).toBe('—');
    });

    it('SQM-basis row with a tile factor but no count yet: the read-only แผ่น box shows —, never a bare แผ่น', async () => {
      await openEditMode({ unitBasis: 'SQM', qty: '', qtySqm: '', sqmPerPiece: 0.72 });
      expect(readOnlyPieceBox().textContent).toBe('—');
    });

    it('SQM-basis row with no factor and a real typed count: the read-only แผ่น box shows it', async () => {
      await openEditMode({ unitBasis: 'SQM', qty: 1113, qtySqm: 400, sqmPerPiece: null });
      expect(readOnlyPieceBox().textContent).toBe('1113 แผ่น');
    });

    it('SQM-basis row with no ตร.ม./แผ่น factor: qty 0 is not required — save sends the row as typed', async () => {
      await openEditMode({ unitBasis: 'SQM', qty: 0, qtySqm: 12, sqmPerPiece: null });
      fireEvent.click(screen.getByRole('button', { name: 'บันทึกการแก้ไข' }));

      await waitFor(() => expect(api.tickets.editItems).toHaveBeenCalledTimes(1));
      const payloadItems = api.tickets.editItems.mock.calls[0][1].items;
      expect(payloadItems[0]).toMatchObject({ qty: 0, qtySqm: 12 });
    });

    it('SQM-basis row with no factor and a blank พื้นที่ (ตร.ม.) is blocked with the basis-aware message, not the old generic one', async () => {
      await openEditMode({ unitBasis: 'SQM', qty: 0, qtySqm: '', sqmPerPiece: null });
      fireEvent.click(screen.getByRole('button', { name: 'บันทึกการแก้ไข' }));

      // The real missingQtyMessage output, not a hand-typed copy of it — this is exactly the
      // string the save handler must produce for row 1 in SQM basis.
      expect(await screen.findByText(missingQtyMessage('SQM', 1))).toBeTruthy();
      expect(api.tickets.editItems).not.toHaveBeenCalled();
    });
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

    // The form is a real Modal (role="dialog", aria-modal), not an inline page section.
    const dialog = await screen.findByRole('dialog', { name: 'ขอแก้ไข' });
    const confirmButton = await within(dialog).findByRole('button', { name: 'ยืนยันขอแก้ไข' });

    // The button's disabled={actionLoading || !reviseReason.trim()} guard makes the inline-error
    // branch in its onClick unreachable through a real click while the reason is blank (jsdom
    // respects the native disabled attribute). This is the narrowest honest proof: the guard still
    // gates the button.
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

  // Once the customer's order is confirmed (ขั้น 10+) a deal-level ขอแก้ไข would open a new pricing
  // round the deal can no longer take (the backend refuses it too), so the ⋯ menu does not offer it.
  // Control: the same owner and status before the order is confirmed still gets it.
  it('the ⋯ menu offers ขอแก้ไข before the order is confirmed, and not once it is (ขั้น 10+)', async () => {
    const stage = (paymentStatus, salesStage) => {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({ summary: { status: 'quotation_issued', createdById: 1, paymentStatus, salesStage } }),
      });
      api.tickets.actions.mockResolvedValue({
        currentState: { lifecycle: 'ACTIVE', salesStage, paymentStatus, fulfillmentStatus: null, status: 'quotation_issued' },
        availableActions: [],
      });
    };
    const reviseOffered = async () => {
      await screen.findByTestId('deal-state-header');
      const more = screen.queryByRole('button', { name: 'การดำเนินการเพิ่มเติม' });
      if (!more) return false;
      fireEvent.click(more);
      return screen.queryByRole('menuitem', { name: /ขอแก้ไข/ }) != null;
    };

    stage(null, 'QUOTE_DESIGN_SIDE');
    const before = renderTicketDetailPage(salesOwnerUser);
    expect(await reviseOffered()).toBe(true);
    before.unmount();

    stage('CUSTOMER_CONFIRMED', 'ORDER_RECEIVED');
    renderTicketDetailPage(salesOwnerUser);
    expect(await reviseOffered()).toBe(false);
  });

  // Opening ขอแก้ไข from the header's "⋯" menu (outside every tab) must not switch tabs: it is a
  // modal, so handleOpenRevise doesn't touch the active tab. A test that only checks the modal
  // appears can't see that regression; it must assert the URL's ?tab= is unchanged.
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
    // Wrong-way-round: the tab this used to force a switch TO is not the one selected.
    expect(screen.getByRole('tab', { name: /^ดีล/ }).getAttribute('aria-selected')).not.toBe('true');
  });

  // CREATE_PCR: the sticky bar opens PricingRequestPanel's create modal via its forwardRef, and the
  // panel renders no "สร้างคำขอราคา" button of its own (the sticky bar never scrolls out of view, so
  // two same-label buttons would show at once).
  describe('sticky header primary CTA — CREATE_PCR owns "สร้างคำขอราคา" alone', () => {
    it('renders exactly one "สร้างคำขอราคา" control (the sticky primary), and clicking it opens the PCR panel\'s own create modal', async () => {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({ summary: { lifecycle: 'ACTIVE', salesStage: 'SPEC_APPROVED', createdById: 1 } }),
      });
      api.tickets.actions.mockResolvedValue({
        currentState: { lifecycle: 'ACTIVE', salesStage: 'SPEC_APPROVED', paymentStatus: null, fulfillmentStatus: null, status: 'price_proposed' },
        availableActions: [],
      });
      api.pricingRequests.listForTicket.mockResolvedValue({ items: [] });

      renderTicketDetailPage(salesOwnerUser);

      const stickyButtons = await screen.findAllByRole('button', { name: /สร้างคำขอราคา/ });
      expect(stickyButtons).toHaveLength(1);
      expect(screen.queryByRole('dialog')).toBeNull();
      // No leftover "ถึงคิวคุณ" banner text: the CTA button stands alone (see TicketDetailPage.jsx's
      // bannerText comment).
      expect(screen.queryByText(/ถึงคิวคุณ/)).toBeNull();
      // ...and the bannerless bar keeps its MOBILE chrome: it is `mobile:fixed inset-x-0 bottom-0`
      // over scrolling content, so losing the background/border there would leave an unreadable
      // transparent bar, which no other assertion here catches.
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

  // ขั้น 1 → 2 → 3 are moved by hand, and the price request starts at ขั้น 3. On ขั้น 1–2 the main
  // button is เลื่อนไป: <next stage>. When the rep is ready (a follow-up date and an activity since
  // the last move) it moves at once; when not, it opens the stage panel's form that collects both and
  // then moves. It is the only เลื่อนไป button on the page body, and สร้างคำขอราคา is not offered
  // anywhere, the สินค้าและราคา tab included.
  describe('ขั้น 1–2 — the main button is เลื่อนไป, and สร้างคำขอราคา is not offered', () => {
    const READY_ACTIVITIES = {
      items: [{
        id: 1, ticketId: 701, activityDate: '2026-07-03', kind: 'CALL', note: 'โทรติดตาม',
        createdById: 1, createdByName: 'พนักงานขาย', createdAt: '2026-07-03T09:00:00.000Z',
      }],
    };

    // `decision` overrides the server's verdict on the next stage, for the cannot-reach case.
    function earlyDeal(salesStage, nextStage, { ready = false, decision = {} } = {}) {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({
          summary: {
            lifecycle: 'ACTIVE', salesStage, status: 'draft', createdById: 1, nextFollowUpAt: ready ? '2026-07-15' : null,
          },
        }),
      });
      const nextDecision = {
        stage: nextStage, allowed: ready, requiresReason: false, onRoute: true,
        blockedReason: ready ? null : STAGE_ADVANCE_GATE_MESSAGE, ...decision,
      };
      api.tickets.actions.mockResolvedValue({
        currentState: { lifecycle: 'ACTIVE', salesStage, paymentStatus: null, fulfillmentStatus: null, status: 'draft' },
        availableActions: nextDecision.allowed ? [{ action: 'ADVANCE_STAGE', targetStage: nextStage }] : [],
        stageDecisions: [nextDecision],
      });
      api.tickets.listActivities.mockResolvedValue(ready ? READY_ACTIVITIES : { items: [] });
      api.pricingRequests.listForTicket.mockResolvedValue({ items: [] });
    }

    it('ขั้น 1, ready: the main button reads เลื่อนไป: 2. นำเสนอสินค้า and moves the deal in one click', async () => {
      earlyDeal('LEAD_APPROACH', 'PRESENTATION', { ready: true });
      api.tickets.updateStage.mockResolvedValue({});
      renderTicketDetailPage(salesOwnerUser);

      await waitFor(() => expect(screen.getByTestId('ticket-primary-action').getAttribute('data-action')).toBe('advance_stage'));
      const button = screen.getByTestId('ticket-primary-action');
      expect(button.textContent).toContain('เลื่อนไป: 2. นำเสนอสินค้า');

      fireEvent.click(button);
      await waitFor(() => expect(api.tickets.updateStage).toHaveBeenCalledWith(701, { stage: 'PRESENTATION' }));
      expect(screen.queryByTestId('guided-advance-modal')).toBeNull();
    });

    it('ขั้น 2, not ready: the main button reads เลื่อนไป: 3. … and opens the follow-up + activity form instead of moving', async () => {
      earlyDeal('PRESENTATION', 'SPEC_APPROVED');
      renderTicketDetailPage(salesOwnerUser);

      await waitFor(() => expect(screen.getByTestId('ticket-primary-action').getAttribute('data-action')).toBe('advance_stage'));
      expect(screen.getByTestId('ticket-primary-action').textContent).toMatch(/เลื่อนไป: 3\./);
      // One เลื่อนไป button on the page body: the stage panel's own opener steps aside for it.
      expect(screen.queryByTestId('guided-advance-open')).toBeNull();

      fireEvent.click(screen.getByTestId('ticket-primary-action'));
      expect(await screen.findByTestId('guided-advance-modal')).not.toBeNull();
      expect(api.tickets.updateStage).not.toHaveBeenCalled();
    });

    it.each([
      ['LEAD_APPROACH', 'PRESENTATION'],
      ['PRESENTATION', 'SPEC_APPROVED'],
    ])('%s: the owning rep sees no สร้างคำขอราคา button, not even in the สินค้าและราคา tab', async (salesStage, nextStage) => {
      earlyDeal(salesStage, nextStage);
      renderTicketDetailPage(salesOwnerUser);

      await waitFor(() => expect(screen.getByTestId('ticket-primary-action').getAttribute('data-action')).toBe('advance_stage'));
      await openTab(/สินค้าและราคา/);
      await screen.findByText('ยังไม่มีคำขอราคา');
      expect(screen.queryByRole('button', { name: /สร้างคำขอราคา/ })).toBeNull();
    });

    it('cannot reach: when the server refuses the move for another reason, no เลื่อนไป main button is offered', async () => {
      earlyDeal('LEAD_APPROACH', 'PRESENTATION', { decision: { blockedReason: 'เฉพาะผู้รับผิดชอบดีลเท่านั้น' } });
      renderTicketDetailPage({ id: 2, employeeId: 2, name: 'พนักงานขายอีกคน', role: 'sales' });

      // The stage panel's "who moves it" hint proves the server's verdict has loaded.
      await screen.findByText(/ขั้นถัดไปอัปเดตโดย/);
      expect(screen.queryByText(/เลื่อนไป: 2\./)).toBeNull();
      expect(screen.queryByTestId('ticket-primary-action')?.getAttribute('data-action')).not.toBe('advance_stage');
    });
  });

  // The counterpart to the rule above, and why bannerText is not simply nulled whenever a CTA exists:
  // the four `can.*`-gated primaries carry a DESCRIPTIVE sentence (NEXT_ACTION_STEPS) that their terse
  // button label does not repeat, and no other surface on the page says it.
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
    // The owner's request, priced and approved; `quotations` is the deal's quotation list
    // (DealQuotationDto rows). A POST here would mint quotation 9201.
    function approvedOwnerRequest({ quotations = [] } = {}) {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({ summary: { lifecycle: 'ACTIVE', salesStage: 'QUOTE_OWNER', createdById: 1 } }),
      });
      api.tickets.actions.mockResolvedValue({
        currentState: { lifecycle: 'ACTIVE', salesStage: 'QUOTE_OWNER', paymentStatus: null, fulfillmentStatus: null, status: 'price_proposed' },
        availableActions: [],
      });
      api.pricingRequests.listForTicket.mockResolvedValue({
        items: [{
          id: 501, requestCode: 'PCR-2026-0501', ticketId: 701, ticketCreatedById: 1,
          status: 'APPROVED_FOR_QUOTATION', recipientType: 'OWNER', recipientLabel: null, orderConfirmedAt: null,
        }],
      });
      api.dealQuotations.listForTicket.mockResolvedValue({ items: quotations });
      api.dealQuotations.createFromPricingRequest.mockResolvedValue({ quotation: { id: 9201, docStatus: 'DRAFT' } });
    }
    const ownerQuotation = (docStatus) => ({
      id: 9101, number: 'QT-2026-0101-1', origin: 'PRICING_REQUEST', pricingRequestId: 501, docStatus,
      recipientType: 'OWNER', salesRepId: 1, revisionNo: 1, parentQuotationId: null, grandTotal: 1070,
    });
    const primaryAction = () => screen.queryByTestId('ticket-primary-action')?.getAttribute('data-action') ?? null;
    const location = () => screen.getByTestId('location-probe').textContent;

    // Later describes in this file read listForTicket bare (a rejected query) — keep it that way.
    afterEach(() => {
      api.dealQuotations.listForTicket.mockReset();
      api.dealQuotations.createFromPricingRequest.mockReset();
    });

    it('เสนอใบราคาเจ้าของโครงการ writes the new-engine quotation, re-reads the deal (quotations, pricing requests, ticket, actions), then opens it', async () => {
      approvedOwnerRequest();
      renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser);
      await waitFor(() => expect(primaryAction()).toBe('issue_quotation'));
      await stickyInputsSettled();
      await waitFor(() => expect(api.tickets.actions).toHaveBeenCalled());
      const reads = () => ({
        quotations: api.dealQuotations.listForTicket.mock.calls.length,
        pricingRequests: api.pricingRequests.listForTicket.mock.calls.length,
        ticket: api.tickets.get.mock.calls.length,
        actions: api.tickets.actions.mock.calls.length,
      });
      const before = reads();

      const primary = screen.getByTestId('ticket-primary-action');
      expect(primary.textContent).toContain('เสนอใบราคาเจ้าของโครงการ');
      fireEvent.click(primary);

      await waitFor(() => expect(api.dealQuotations.createFromPricingRequest).toHaveBeenCalledWith(501));
      await waitFor(() => expect(location()).toBe('/quotations/9201'));
      await waitFor(() => {
        const after = reads();
        for (const list of Object.keys(before)) expect(after[list], `re-read ${list}`).toBeGreaterThan(before[list]);
      });
    });

    // A hook-level onSuccess runs after the page has unmounted; the navigation must belong to the
    // click (a per-call onSuccess), so it dies with the page.
    it('a เสนอใบราคา that finishes after the rep has left the deal does not pull them to the quotation', async () => {
      approvedOwnerRequest();
      let finishCreate;
      api.dealQuotations.createFromPricingRequest.mockReturnValueOnce(new Promise((resolve) => { finishCreate = resolve; }));
      const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
      render(
        <QueryClientProvider client={queryClient}>
          <MemoryRouter initialEntries={['/tickets/701']}>
            <LocationProbe />
            <Link to="/tickets">ไปหน้ารายการดีล</Link>
            <Routes>
              <Route path="/tickets/701" element={<TicketDetailPage user={salesOwnerUser} ticketId={701} onBack={vi.fn()} showToast={vi.fn()} />} />
              <Route path="*" element={null} />
            </Routes>
          </MemoryRouter>
        </QueryClientProvider>,
      );
      await waitFor(() => expect(primaryAction()).toBe('issue_quotation'));
      fireEvent.click(screen.getByTestId('ticket-primary-action'));
      await waitFor(() => expect(api.dealQuotations.createFromPricingRequest).toHaveBeenCalledWith(501));

      fireEvent.click(screen.getByRole('link', { name: 'ไปหน้ารายการดีล' }));
      expect(location()).toBe('/tickets');
      expect(screen.queryByTestId('ticket-primary-action')).toBeNull(); // the deal page is gone

      await act(async () => {
        finishCreate({ quotation: { id: 9201, docStatus: 'DRAFT' } });
        await new Promise((resolve) => { setTimeout(resolve, 50); });
      });
      expect(location()).toBe('/tickets');
    });

    it('its DRAFT quotation is already written: เสนอใบราคาเจ้าของโครงการ opens that draft, with no new write', async () => {
      approvedOwnerRequest({ quotations: [ownerQuotation('DRAFT')] });
      renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser);
      await waitFor(() => expect(primaryAction()).toBe('issue_quotation'));
      await stickyInputsSettled();

      const primary = screen.getByTestId('ticket-primary-action');
      expect(primary.textContent).toContain('เสนอใบราคาเจ้าของโครงการ');
      fireEvent.click(primary);

      await waitFor(() => expect(location()).toBe('/quotations/9101'));
      expect(api.dealQuotations.createFromPricingRequest).not.toHaveBeenCalled();
    });

    it('its quotation awaiting approval: no button for the rep, and the line says รอ ผจก.ขาย/CEO อนุมัติใบเสนอราคา <number>', async () => {
      approvedOwnerRequest({ quotations: [ownerQuotation('PENDING_APPROVAL')] });
      renderTicketDetailPage(salesOwnerUser);

      expect((await screen.findAllByText('รอ ผจก.ขาย/CEO อนุมัติใบเสนอราคา QT-2026-0101-1')).length).toBeGreaterThan(0);
      expect(screen.queryByTestId('ticket-primary-action')).toBeNull();
    });

    // Only a refusal (409) opens the request page; any other failure just reports. The refusal is
    // the way to the request page, not an error: it is asked again on every click (the server
    // decides, nothing is remembered).
    it.each([
      [409, 'คำขอราคานี้ยังใช้รูปแบบราคาเดิม (ก่อน CEO เลือกวิธีกรอกราคา) กรุณาสร้างใบเสนอราคาแบบเดิมจากหน้านี้', '/pricing-requests/501', 'info'],
      [500, 'ระบบขัดข้อง', '/tickets/701', 'error'],
    ])('a failed เสนอใบราคา (%s) shows the server\'s message; only a refusal also opens the pricing request', async (status, message, expectedLocation, tone) => {
      approvedOwnerRequest();
      api.dealQuotations.createFromPricingRequest.mockRejectedValue(Object.assign(new Error(message), { status }));
      const showToast = vi.fn();
      renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser, showToast);
      await waitFor(() => expect(primaryAction()).toBe('issue_quotation'));

      fireEvent.click(screen.getByTestId('ticket-primary-action'));

      await waitFor(() => expect(showToast).toHaveBeenCalledWith(tone, message));
      await waitFor(() => expect(location()).toBe(expectedLocation));
    });

    it('renders exactly one "ยืนยันคำสั่งซื้อ" control (the sticky primary); clicking it opens a confirm dialog first (SK-02), and only the dialog confirms', async () => {
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
      api.pricingRequests.confirmOrder.mockResolvedValue({});

      renderTicketDetailPage(salesOwnerUser);

      const stickyButtons = await screen.findAllByRole('button', { name: /ยืนยันคำสั่งซื้อ/ });
      expect(stickyButtons).toHaveLength(1);

      fireEvent.click(stickyButtons[0]);

      // Irreversible (the deal can no longer go below ขั้น 10), so a dialog first, nothing sent yet.
      const dialog = await screen.findByRole('dialog', { name: 'ยืนยันคำสั่งซื้อ' });
      expect(within(dialog).getByText(/ย้อนกลับไม่ได้/)).not.toBeNull();
      expect(api.pricingRequests.confirmOrder).not.toHaveBeenCalled();

      // Cancelling sends nothing.
      fireEvent.click(within(dialog).getByRole('button', { name: 'ยกเลิก' }));
      await waitFor(() => expect(screen.queryByRole('dialog', { name: 'ยืนยันคำสั่งซื้อ' })).toBeNull());
      expect(api.pricingRequests.confirmOrder).not.toHaveBeenCalled();

      // Confirming sends exactly once.
      fireEvent.click(screen.getAllByRole('button', { name: /ยืนยันคำสั่งซื้อ/ })[0]);
      const again = await screen.findByRole('dialog', { name: 'ยืนยันคำสั่งซื้อ' });
      fireEvent.click(within(again).getByRole('button', { name: 'ยืนยันคำสั่งซื้อ' }));
      await waitFor(() => expect(api.pricingRequests.confirmOrder).toHaveBeenCalledTimes(1));
      expect(api.pricingRequests.confirmOrder).toHaveBeenCalledWith(
        501, expect.objectContaining({ clientRequestId: expect.any(String) }),
      );
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
        await openTab(/เอกสาร/);
        const panel = within(await screen.findByTestId('deal-quotation-panel'));
        expect((await panel.findAllByText(oldEngineRows[0]?.number ?? 'QT-2026-0501-NEW')).length).toBeGreaterThan(0);
        const sticky = await screen.findAllByRole('button', { name: /ยืนยันคำสั่งซื้อ/ });
        fireEvent.click(sticky[0]);
        return screen.findByRole('dialog', { name: 'ยืนยันคำสั่งซื้อ' });
      }

      it('a new-engine deal (no old-engine row): the dialog reads the new-engine quotation number, not a blank', async () => {
        const dialog = await openConfirmOrderDialog();
        expect(dialog.textContent).toContain('ใบเสนอราคา QT-2026-0501-NEW');
        expect(dialog.textContent).not.toMatch(/ใบเสนอราคา\s{2,}/);
      });

      // The tail follows the quotation's deposit %. 0 -> no deposit wording at all.
      it('a quotation with no deposit (0%): the dialog says ไม่เก็บมัดจำ and never mentions ใบแจ้งรับมัดจำ', async () => {
        const dialog = await openConfirmOrderDialog({ depositPercent: 0 });
        expect(dialog.textContent).toContain('ไม่เก็บมัดจำ');
        expect(dialog.textContent).not.toContain('ใบแจ้งรับมัดจำ');
      });

      it('a quotation with a deposit (30%): the dialog still says จากนั้นออกใบแจ้งรับมัดจำ', async () => {
        const dialog = await openConfirmOrderDialog({ depositPercent: 30 });
        expect(dialog.textContent).toContain('ใบแจ้งรับมัดจำ');
        expect(dialog.textContent).not.toContain('ไม่เก็บมัดจำ');
      });

      it('a live old-engine quotation still wins: the dialog names its number, as the panel does', async () => {
        const dialog = await openConfirmOrderDialog({
          oldEngineRows: [{ id: 9301, number: 'QT-2026-0301-1', docStatus: 'ACCEPTED', quotationRevisionNo: 1, grandTotal: 1000 }],
        });
        expect(dialog.textContent).toContain('QT-2026-0301-1');
        expect(dialog.textContent).not.toContain('QT-2026-0501-NEW');
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
    const actionBarOf = (element) => element.closest('[data-testid="ticket-action-bar"]');

    // Later describes in this file read listForTicket bare (a rejected query) — keep it that way.
    afterEach(() => { api.dealQuotations.listForTicket.mockReset(); });

    // The menu's trigger is the neutral เสนอราคา ▾, not the first party.
    it('a sent designer quotation: the primary เสนอราคา is a menu (owner, then ข้ามไปเสนอราคาผู้ซื้อ) beside "ใบเสนอราคาผู้ออกแบบส่งแล้ว…"', async () => {
      deal();
      renderTicketDetailPage(salesOwnerUser);

      const primary = await primaryWithAction('quote_next_party');
      expect(primary.textContent).toBe('เสนอราคา');
      expect(primary.getAttribute('aria-label')).toBe('เสนอราคา');
      expect(primary.getAttribute('aria-haspopup')).toBe('menu');
      expect(actionBarOf(primary).textContent).toContain('ใบเสนอราคาผู้ออกแบบส่งแล้ว เลือกเสนอราคาเจ้าของโครงการ หรือข้ามไปผู้ซื้อ');

      fireEvent.click(primary);
      const items = await screen.findAllByRole('menuitem');
      expect(items.map((item) => item.textContent)).toEqual(['เสนอราคาเจ้าของโครงการ', 'ข้ามไปเสนอราคาผู้ซื้อ']);
    });

    it('picking ข้ามไปเสนอราคาผู้ซื้อ opens the pricing-request form with ผู้รับ = ผู้ซื้อ / ผู้รับเหมา and the buyer\'s name filled in', async () => {
      deal();
      renderTicketDetailPage(salesOwnerUser);

      fireEvent.click(await primaryWithAction('quote_next_party'));
      fireEvent.click(await screen.findByRole('menuitem', { name: 'ข้ามไปเสนอราคาผู้ซื้อ' }));

      const dialog = within(await screen.findByRole('dialog'));
      expect(dialog.getByRole('radio', { name: /ผู้ซื้อ \/ ผู้รับเหมา/ }).getAttribute('aria-checked')).toBe('true');
      expect(dialog.getByRole('radio', { name: /ผู้ออกแบบ/ }).getAttribute('aria-checked')).toBe('false');
      expect(dialog.getByPlaceholderText('เช่น ชื่อผู้ออกแบบ หรือชื่อบริษัทผู้ซื้อ').value).toBe('ผู้รับเหมา ข.');
    });

    // At the owner stage the ⋯ also holds แก้ใบเสนอราคา.
    it('the ⋯ beside the route choice offers บันทึกผลใบเสนอราคา for the sent owner quotation (opening its request page) and แก้ใบเสนอราคา', async () => {
      deal({ requests: [sentRequest({ recipientType: 'OWNER' })], quotations: [requestQuotation({ recipientType: 'OWNER' })], stage: 'QUOTE_OWNER' });
      renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser);
      await primaryWithAction('quote_next_party');

      fireEvent.click(screen.getByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));
      expect((await screen.findAllByRole('menuitem')).map((item) => item.textContent))
        .toEqual(expect.arrayContaining(['บันทึกผลใบเสนอราคา', 'แก้ใบเสนอราคา']));
      fireEvent.click(screen.getByRole('menuitem', { name: 'บันทึกผลใบเสนอราคา' }));

      await waitFor(() => expect(screen.getByTestId('location-probe').textContent).toBe('/pricing-requests/501'));
    });

    it('a designer quotation the customer rejected: the line reads "ใบเสนอราคาผู้ออกแบบไม่ผ่าน…", and nothing offers to record its outcome', async () => {
      deal({ quotations: [requestQuotation({ docStatus: 'REJECTED' })] });
      renderTicketDetailPage(salesOwnerUser);

      const primary = await primaryWithAction('quote_next_party');
      expect(actionBarOf(primary).textContent).toContain('ใบเสนอราคาผู้ออกแบบไม่ผ่าน เลือกเสนอราคาเจ้าของโครงการ หรือข้ามไปผู้ซื้อ');
      const more = screen.queryByRole('button', { name: 'การดำเนินการเพิ่มเติม' });
      if (more) fireEvent.click(more);
      expect(screen.queryByRole('menuitem', { name: 'บันทึกผลใบเสนอราคา' })).toBeNull();
      expect(screen.queryByRole('button', { name: 'บันทึกผลใบเสนอราคา' })).toBeNull();
    });

    // H1: the route is the served catalog.routes for the deal's entryChannel — not stageDecisions,
    // which are still missing while GET /actions is in flight (or after it failed).
    it('one party left: on a BUYER_DIRECT deal the served route drops the owner, even before /actions answers — a plain เสนอราคาผู้ซื้อ button that opens the form', async () => {
      deal({ entryChannel: 'BUYER_DIRECT', actionsAnswered: false });
      renderTicketDetailPage(salesOwnerUser);

      const primary = await primaryWithAction('quote_next_party');
      expect(primary.textContent).toBe('เสนอราคาผู้ซื้อ');
      expect(primary.getAttribute('aria-haspopup')).toBeNull();
      expect(actionBarOf(primary).textContent).toContain('ใบเสนอราคาผู้ออกแบบส่งแล้ว ถัดไปเสนอราคาผู้ซื้อ');

      fireEvent.click(primary);
      const dialog = within(await screen.findByRole('dialog'));
      expect(dialog.getByRole('radio', { name: /ผู้ซื้อ \/ ผู้รับเหมา/ }).getAttribute('aria-checked')).toBe('true');
    });

    // An unknown route is never "every party is on it", and a sent designer/owner quotation is never
    // prompted for its outcome, so nothing shows meanwhile.
    it('no primary and no route line while the stage catalog is loading; the menu appears once the served route arrives', async () => {
      let serveCatalog;
      api.meta.dealStages.mockReturnValueOnce(new Promise((resolve) => { serveCatalog = resolve; }));
      deal();
      renderTicketDetailPage(salesOwnerUser);
      await stickyInputsSettled();

      expect(screen.queryByTestId('ticket-primary-action')).toBeNull();
      expect(screen.queryByText(/ใบเสนอราคาผู้ออกแบบส่งแล้ว/)).toBeNull();
      // The sent quotation's outcome stays one click away in the ⋯ menu.
      fireEvent.click(screen.getByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));
      expect(await screen.findByRole('menuitem', { name: 'บันทึกผลใบเสนอราคา' })).not.toBeNull();
      fireEvent.keyDown(document, { key: 'Escape' });

      await act(async () => { serveCatalog(DEAL_STAGE_CATALOG); });
      await primaryWithAction('quote_next_party');
    });

    // The quotation map holds the NEWEST PRICING_REQUEST-origin row per request: a newer DRAFT
    // revision means the designer's quotation is being redone (open that draft).
    it('a designer quotation being redone (a newer DRAFT revision exists): เสนอใบราคาผู้ออกแบบ opens that draft, with no new write', async () => {
      deal({
        quotations: [
          requestQuotation(),
          requestQuotation({ id: 9102, number: 'QT-2026-0101-2', docStatus: 'DRAFT', revisionNo: 2, parentQuotationId: 9101 }),
        ],
      });
      renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser);

      const primary = await primaryWithAction('issue_quotation');
      expect(primary.textContent).toContain('เสนอใบราคาผู้ออกแบบ');
      fireEvent.click(primary);

      await waitFor(() => expect(screen.getByTestId('location-probe').textContent).toBe('/quotations/9102'));
      expect(api.dealQuotations.createFromPricingRequest).not.toHaveBeenCalled();
    });

    // The next party's request was saved as a private DRAFT (e.g. to attach files): the one step left
    // is sending it to import, from the items tab where that draft lives, through the panel's ref (a
    // scroll jump pulled the page back to the top). The second row: a draft the panel will not send
    // (here, the deal is another rep's) says so instead of only switching tab.
    it.each([
      ['the rep\'s own draft is sent', 1, true],
      ['a draft the panel refuses says why it was not sent', 2, false],
    ])('a later party\'s draft request: ส่งคำขอราคาถึงเจ้าของโครงการ on the สินค้าาและราคา tab — %s', async (_label, ticketCreatedById, sent) => {
      deal({
        requests: [sentRequest(), sentRequest({ id: 502, requestCode: 'PCR-2026-0502', status: 'DRAFT', recipientType: 'OWNER', ticketCreatedById })],
      });
      api.pricingRequests.submit.mockResolvedValue({ pricingRequest: {} });
      const showToast = vi.fn();
      renderTicketDetailPage(salesOwnerUser, showToast);

      await waitFor(() => expect(screen.getByTestId('ticket-primary-action').textContent).toContain('ส่งคำขอราคาถึงเจ้าของโครงการ'));
      fireEvent.click(screen.getByTestId('ticket-primary-action'));

      await waitFor(() => expect(screen.getByRole('tab', { name: /สินค้าและราคา/ }).getAttribute('aria-selected')).toBe('true'));
      if (sent) {
        await waitFor(() => expect(api.pricingRequests.submit).toHaveBeenCalledWith(502));
      } else {
        await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', expect.stringContaining('ส่งคำขอราคานี้ไม่ได้')));
        expect(api.pricingRequests.submit).not.toHaveBeenCalled();
      }
    });

    // A customer-change revision leaves {parent SUPERSEDED, child DRAFT}. The primary sends that
    // draft; it never opens a blank new-request form beside it (a duplicate).
    it('a revision draft as the deal\'s only open request: ส่งคำขอราคาถึงเจ้าของโครงการ sends it, and no new-request form opens', async () => {
      deal({
        requests: [
          sentRequest({ status: 'SUPERSEDED', recipientType: 'OWNER' }),
          sentRequest({ id: 502, requestCode: 'PCR-2026-0502', status: 'DRAFT', recipientType: 'OWNER' }),
        ],
        quotations: [], entryChannel: 'OWNER_DIRECT', stage: 'SPEC_APPROVED',
      });
      api.pricingRequests.submit.mockResolvedValue({ pricingRequest: {} });
      renderTicketDetailPage(salesOwnerUser);

      const primary = await primaryWithAction('submit_pricing_request');
      expect(primary.textContent).toContain('ส่งคำขอราคาถึงเจ้าของโครงการ');
      fireEvent.click(primary);

      await waitFor(() => expect(api.pricingRequests.submit).toHaveBeenCalledWith(502));
      expect(screen.queryByRole('dialog')).toBeNull();
    });

    // The deal's FIRST สร้างคำขอราคา pre-fills ผู้รับ with the first party on the deal's route. Sales
    // may still pick another one.
    it.each([
      ['DESIGNER_LED', /ผู้ออกแบบ/, /เจ้าของโครงการ/],
      ['OWNER_DIRECT', /เจ้าของโครงการ/, /ผู้ออกแบบ/],
      ['BUYER_DIRECT', /ผู้ซื้อ \/ ผู้รับเหมา/, /ผู้ออกแบบ/],
    ])('first สร้างคำขอราคา on a %s deal: ผู้รับ = the route\'s first party, still changeable', async (entryChannel, party, other) => {
      deal({ requests: [], quotations: [], entryChannel, stage: 'SPEC_APPROVED' });
      renderTicketDetailPage(salesOwnerUser);

      fireEvent.click(await primaryWithAction('create_pcr'));

      const recipients = within(within(await screen.findByRole('dialog')).getByRole('radiogroup', { name: 'ผู้รับคำขอราคา' }));
      const checked = () => recipients.getAllByRole('radio').filter((radio) => radio.getAttribute('aria-checked') === 'true');
      expect(checked()).toEqual([recipients.getByRole('radio', { name: party })]);
      fireEvent.click(recipients.getByRole('radio', { name: other }));
      expect(checked()).toEqual([recipients.getByRole('radio', { name: other })]);
    });

    // Opus review: the pre-fill follows the served route alone, not the deal's quotation list.
    it('first สร้างคำขอราคา on an OWNER_DIRECT deal pre-fills เจ้าของโครงการ while the deal\'s quotation list is still loading', async () => {
      deal({ requests: [], quotations: [], entryChannel: 'OWNER_DIRECT', stage: 'SPEC_APPROVED' });
      api.dealQuotations.listForTicket.mockReturnValue(new Promise(() => {}));
      renderTicketDetailPage(salesOwnerUser);

      fireEvent.click(await primaryWithAction('create_pcr'));

      const recipients = within(within(await screen.findByRole('dialog')).getByRole('radiogroup', { name: 'ผู้รับคำขอราคา' }));
      expect(recipients.getByRole('radio', { name: /เจ้าของโครงการ/ }).getAttribute('aria-checked')).toBe('true');
    });

    // The pre-fill follows the deal's channel, so it holds while the stage catalog is still loading.
    it('first สร้างคำขอราคา on an OWNER_DIRECT deal pre-fills เจ้าของโครงการ while the stage catalog is still loading', async () => {
      api.meta.dealStages.mockReturnValueOnce(new Promise(() => {}));
      deal({ requests: [], quotations: [], entryChannel: 'OWNER_DIRECT', stage: 'SPEC_APPROVED' });
      renderTicketDetailPage(salesOwnerUser);

      fireEvent.click(await primaryWithAction('create_pcr'));

      const recipients = within(within(await screen.findByRole('dialog')).getByRole('radiogroup', { name: 'ผู้รับคำขอราคา' }));
      expect(recipients.getByRole('radio', { name: /เจ้าของโครงการ/ }).getAttribute('aria-checked')).toBe('true');
    });

    // An unread request list is not "no request": a DRAFT may be waiting in it, so สร้างคำขอราคา is
    // never offered until the list is read.
    it.each([
      ['still loading', () => new Promise(() => {})],
      ['failed to load', () => Promise.reject(new Error('network'))],
    ])('no สร้างคำขอราคา while the deal\'s request list is %s', async (_label, list) => {
      deal({ requests: [], quotations: [], entryChannel: 'OWNER_DIRECT', stage: 'SPEC_APPROVED' });
      api.pricingRequests.listForTicket.mockImplementation(list);
      renderTicketDetailPage(salesOwnerUser);
      await stickyInputsSettled();

      expect(screen.queryByTestId('ticket-primary-action')?.getAttribute('data-action')).not.toBe('create_pcr');
      expect(screen.queryByRole('button', { name: /สร้างคำขอราคา/ })).toBeNull();
    });

    // Review V5: an unreadable list is not "no quotation" — the designer's may be being redone.
    it('while the deal\'s quotation list has never been read: no route choice, and ⋯ still offers บันทึกผลใบเสนอราคา; once read, a failed refetch keeps it', async () => {
      deal();
      api.dealQuotations.listForTicket.mockRejectedValue(new Error('network'));
      const { queryClient } = renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser);
      await stickyInputsSettled();

      expect(screen.queryByTestId('ticket-primary-action')).toBeNull();
      fireEvent.click(screen.getByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));
      fireEvent.click(await screen.findByRole('menuitem', { name: 'บันทึกผลใบเสนอราคา' }));
      await waitFor(() => expect(screen.getByTestId('location-probe').textContent).toBe('/pricing-requests/501'));

      api.dealQuotations.listForTicket.mockResolvedValue({ items: [requestQuotation()] });
      await act(async () => { await queryClient.invalidateQueries({ queryKey: ['dealQuotations'] }); });
      await primaryWithAction('quote_next_party');
      api.dealQuotations.listForTicket.mockRejectedValue(new Error('network'));
      await act(async () => { await queryClient.invalidateQueries({ queryKey: ['dealQuotations'] }); });
      await waitFor(() => expect(queryClient.getQueryCache().findAll({ queryKey: ['dealQuotations'] })
        .some((query) => query.state.status === 'error')).toBe(true));
      await stickyInputsSettled();
      expect(screen.getByTestId('ticket-primary-action').getAttribute('data-action')).toBe('quote_next_party');
    });


    // The owner's request approved after the designer accepted is not quoted, and ยืนยันคำสั่งซื้อ
    // confirms the ACCEPTED request, not the newest one.
    it('an accepted designer quotation, with the owner\'s request approved later: ยืนยันคำสั่งซื้อ is the primary (no เสนอราคา, no route line) and confirms the accepted request', async () => {
      deal({
        requests: [
          sentRequest({ id: 502, requestCode: 'PCR-2026-0502', recipientType: 'OWNER', status: 'APPROVED_FOR_QUOTATION' }),
          sentRequest({ status: 'QUOTATION_ACCEPTED' }),
        ],
        quotations: [requestQuotation({ docStatus: 'ACCEPTED' })],
      });
      api.pricingRequests.confirmOrder.mockResolvedValue({ result: { ticket: buildTicket() } });
      renderTicketDetailPage(salesOwnerUser);

      const primary = await primaryWithAction('confirm_order');
      expect(primary.getAttribute('aria-haspopup')).toBeNull();
      expect(actionBarOf(primary).textContent).not.toMatch(/เสนอ(ใบ)?ราคา(เจ้าของโครงการ|ผู้ซื้อ)/);
      // ถัดไป names the stage this main button leads to (ขั้น 10), not the next stage on the route.
      const nextLine = screen.getByTestId('deal-stage-next').textContent;
      expect(nextLine).toContain('10.');
      expect(nextLine).not.toContain('5.');

      fireEvent.click(primary);
      const dialog = await screen.findByRole('dialog', { name: 'ยืนยันคำสั่งซื้อ' });
      expect(api.pricingRequests.confirmOrder).not.toHaveBeenCalled(); // SK-02: dialog first
      fireEvent.click(within(dialog).getByRole('button', { name: 'ยืนยันคำสั่งซื้อ' }));
      await waitFor(() => expect(api.pricingRequests.confirmOrder).toHaveBeenCalledWith(501, expect.anything()));
    });

    // While ยืนยันคำสั่งซื้อ is the primary, the ⋯ menu must not also offer เลื่อนไป ขั้น 9 (the server
    // still advertises ADVANCE_STAGE, so only the page can hide it).
    it('an accepted buyer quotation at ขั้น 8: primary is ยืนยันคำสั่งซื้อ and ⋯ offers no เลื่อนไป', async () => {
      deal({
        stage: 'QUOTE_BUYER',
        requests: [sentRequest({ recipientType: 'BUYER', status: 'QUOTATION_ACCEPTED' })],
        quotations: [requestQuotation({ recipientType: 'BUYER', docStatus: 'ACCEPTED' })],
      });
      api.tickets.actions.mockResolvedValue({
        currentState: { lifecycle: 'ACTIVE', salesStage: 'QUOTE_BUYER', paymentStatus: null, fulfillmentStatus: null, status: 'draft' },
        availableActions: [{ action: 'ADVANCE_STAGE', targetStage: 'NEGOTIATION' }, { action: 'UPDATE_STAGE' }],
        stageDecisions: DEAL_STAGE_CATALOG.stages.map((s) => ({
          stage: s.code, no: s.no, allowed: s.code !== 'QUOTE_BUYER', requiresReason: false, blockedReason: null, onRoute: true,
        })),
      });
      renderTicketDetailPage(salesOwnerUser);

      await primaryWithAction('confirm_order');
      fireEvent.click(await screen.findByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));
      await screen.findByRole('menu');
      expect(screen.queryByTestId('deal-stage-advance')).toBeNull();
      expect(screen.queryByText(/เลื่อนไป:/)).toBeNull();
    });

    // After ข้ามไปเสนอราคาผู้ซื้อ the buyer's request is open in import, so ถัดไป names ITS stage
    // (ขั้น 8), not the next stage on the route (ขั้น 5). A dead request, or one not ahead, changes
    // nothing.
    it.each([
      ['a live buyer request', 'IMPORT_REVIEWING', 'QUOTE_DESIGN_SIDE', '8.', '5.'],
      ['a CANCELLED buyer request', 'CANCELLED', 'QUOTE_DESIGN_SIDE', '5.', '8.'],
      ['a SUPERSEDED buyer request', 'SUPERSEDED', 'QUOTE_DESIGN_SIDE', '5.', '8.'],
    ])('ถัดไป with %s on a deal at ขั้น 4', async (_label, buyerStatus, stage, shown, notShown) => {
      deal({
        stage,
        requests: [
          sentRequest(),
          sentRequest({ id: 503, requestCode: 'PCR-2026-0503', recipientType: 'BUYER', status: buyerStatus }),
        ],
      });
      renderTicketDetailPage(salesOwnerUser);

      await waitFor(() => expect(screen.getByTestId('deal-stage-next').textContent).toContain(shown));
      expect(screen.getByTestId('deal-stage-next').textContent).not.toContain(notShown);
    });

    // "Newest" = highest id among live requests: a newer live request that is not ahead wins over an older buyer one.
    it('ถัดไป follows the NEWEST live request (highest id), even when an older one is further ahead', async () => {
      deal({
        stage: 'QUOTE_DESIGN_SIDE',
        requests: [
          sentRequest(),
          sentRequest({ id: 503, requestCode: 'PCR-2026-0503', recipientType: 'BUYER', status: 'IMPORT_REVIEWING' }),
          sentRequest({ id: 504, requestCode: 'PCR-2026-0504', recipientType: 'DESIGNER', status: 'IMPORT_REVIEWING' }),
        ],
      });
      renderTicketDetailPage(salesOwnerUser);

      await waitFor(() => expect(screen.getByTestId('deal-stage-next').textContent).toContain('5.'));
      expect(screen.getByTestId('deal-stage-next').textContent).not.toContain('8.');
    });

    // F3: the request's stage must be on THIS deal's route (server stageDecisions), else the plain next stage.
    it('ถัดไป ignores a live DESIGNER request whose stage is off the route (OWNER_DIRECT deal at ขั้น 3)', async () => {
      deal({
        stage: 'SPEC_APPROVED',
        entryChannel: 'OWNER_DIRECT',
        requests: [sentRequest({ status: 'IMPORT_REVIEWING', recipientType: 'DESIGNER' })],
        quotations: [],
      });
      api.tickets.actions.mockResolvedValue({
        currentState: { lifecycle: 'ACTIVE', salesStage: 'SPEC_APPROVED', paymentStatus: null, fulfillmentStatus: null, status: 'draft' },
        availableActions: [],
        stageDecisions: DEAL_STAGE_CATALOG.stages.map((s) => ({
          stage: s.code, no: s.no, allowed: true, requiresReason: false, blockedReason: null,
          onRoute: s.code !== 'QUOTE_DESIGN_SIDE',
        })),
      });
      renderTicketDetailPage(salesOwnerUser);

      await waitFor(() => expect(screen.getByTestId('deal-stage-next').textContent).toContain('5.'));
      expect(screen.getByTestId('deal-stage-next').textContent).not.toContain('4.');
    });

    it('ถัดไป stays on the route when the live buyer request is not AHEAD of the deal (deal already at ขั้น 8)', async () => {
      deal({
        stage: 'QUOTE_BUYER',
        requests: [sentRequest({ id: 503, requestCode: 'PCR-2026-0503', recipientType: 'BUYER', status: 'IMPORT_REVIEWING' })],
      });
      renderTicketDetailPage(salesOwnerUser);

      await waitFor(() => expect(screen.getByTestId('deal-stage-next').textContent).toContain('9.'));
      expect(screen.getByTestId('deal-stage-next').textContent).not.toContain('8.');
    });

    // Direct path (no pricing request): an APPROVED designer direct quotation offers the same menu; a party
    // opens the quotation editor pre-addressed (?recipient=), not the pricing-request form.
    it('an APPROVED designer direct quotation: เสนอราคา menu is primary, ยืนยันคำสั่งซื้อ sits in ⋯, and picking the owner opens /quotations/new?ticket=701&recipient=OWNER', async () => {
      deal({ requests: [], quotations: [] });
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({
          summary: {
            lifecycle: 'ACTIVE', salesStage: 'QUOTE_DESIGN_SIDE', createdById: 1, status: 'draft', entryChannel: 'DESIGNER_LED',
            designerName: 'สมชาย ผู้ออกแบบ', ownerName: 'บริษัท เจ้าของ จำกัด', buyerName: 'ผู้รับเหมา ข.',
            liveDirectQuotation: { id: 77, number: 'QT-2026-0077-1', docStatus: 'APPROVED', recipientType: 'DESIGNER' },
          },
        }),
      });
      renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser);

      const primary = await primaryWithAction('quote_next_party');
      expect(primary.getAttribute('aria-haspopup')).toBe('menu');
      // A direct quotation is approved, not "sent".
      expect(actionBarOf(primary).textContent).toContain('ใบเสนอราคาผู้ออกแบบอนุมัติแล้ว เลือกเสนอราคาเจ้าของโครงการ หรือข้ามไปผู้ซื้อ');
      expect(actionBarOf(primary).textContent).not.toContain('ส่งแล้ว');

      fireEvent.click(primary);
      fireEvent.click(await screen.findByRole('menuitem', { name: 'เสนอราคาเจ้าของโครงการ' }));
      await waitFor(() => expect(screen.getByTestId('location-probe').textContent).toBe('/quotations/new?ticket=701&recipient=OWNER'));
    });

    it('the ⋯ ยืนยันคำสั่งซื้อ beside the direct route choice opens the confirm dialog and confirms quotation 77', async () => {
      deal({ requests: [], quotations: [{ id: 77, number: 'QT-2026-0077-1', origin: 'DEAL_DIRECT', docStatus: 'APPROVED', recipientType: 'DESIGNER', depositPercent: 0 }] });
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({
          summary: {
            lifecycle: 'ACTIVE', salesStage: 'QUOTE_DESIGN_SIDE', createdById: 1, status: 'draft', entryChannel: 'DESIGNER_LED',
            liveDirectQuotation: { id: 77, number: 'QT-2026-0077-1', docStatus: 'APPROVED', recipientType: 'DESIGNER' },
          },
        }),
      });
      api.dealQuotations.promoteToDeal.mockResolvedValue({ result: { ticketId: 701 } });
      renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser);
      await primaryWithAction('quote_next_party');

      fireEvent.click(screen.getByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));
      fireEvent.click(await screen.findByRole('menuitem', { name: 'ยืนยันคำสั่งซื้อ' }));
      const dialog = within(await screen.findByRole('dialog'));
      expect(screen.getByRole('dialog').textContent).not.toContain('อัตโนมัติ'); // only row is the confirmed one: copy unchanged
      fireEvent.click(dialog.getByRole('button', { name: 'ยืนยันคำสั่งซื้อ' }));
      await waitFor(() => expect(api.dealQuotations.promoteToDeal).toHaveBeenCalledWith(77));
    });

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

      it('owner DRAFT is the primary (submit): ⋯ -> ยืนยันคำสั่งซื้อ -> dialog names the APPROVED row -> promoteToDeal(77), never 78', async () => {
        dealWithOwnerQuotation('DRAFT');
        renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser);
        await primaryWithAction('submit_direct_quotation');

        await openMore();
        fireEvent.click(await screen.findByRole('menuitem', { name: 'ยืนยันคำสั่งซื้อ' }));
        const dialog = within(await screen.findByRole('dialog'));
        expect(dialog.getByText(/ใบเสนอราคา QT-2026-0077-1/)).not.toBeNull();
        // The confirm auto-cancels the deal's other open direct quotations: the dialog says which (here, only #78).
        expect(dialog.getByText(/จะถูกยกเลิกอัตโนมัติ/).textContent).toContain('QT-2026-0078-1');
        expect(dialog.getByText(/จะถูกยกเลิกอัตโนมัติ/).textContent).not.toContain('QT-2026-0077-1');
        fireEvent.click(dialog.getByRole('button', { name: 'ยืนยันคำสั่งซื้อ' }));
        await waitFor(() => expect(api.dealQuotations.promoteToDeal).toHaveBeenCalledWith(77));
        expect(api.dealQuotations.promoteToDeal).not.toHaveBeenCalledWith(78);
      });

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

      it('owner PENDING_APPROVAL (the page only waits): ⋯ still offers it', async () => {
        dealWithOwnerQuotation('PENDING_APPROVAL');
        renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser);
        await openMore();
        expect(await screen.findByRole('menuitem', { name: 'ยืนยันคำสั่งซื้อ' })).not.toBeNull();
      });

      it('not offered once the order is confirmed (deal past draft)', async () => {
        dealWithOwnerQuotation('DRAFT', { status: 'quotation_issued' });
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

  // A PR sitting at QUOTATION_ISSUED must not fall through salesActions.js's cascade to
  // follow-up/log-activity. The outcome controls (both engines) live on the pricing-request page,
  // so the CTA opens it; it never records an outcome itself. This prompt is for the BUYER's issued
  // quotation (the designer's/owner's sits in the ⋯ menu beside the route choice); the deal's
  // quotation list shows it ISSUED.
  describe('sticky header primary CTA — RECORD_QUOTATION_OUTCOME opens the pricing request', () => {
    afterEach(() => { api.dealQuotations.listForTicket.mockReset(); });

    it('an issued buyer quotation: exactly one "บันทึกผลใบเสนอราคา" sticky primary, and clicking it opens /pricing-requests/<id> without firing any mutation', async () => {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({ summary: { lifecycle: 'ACTIVE', salesStage: 'QUOTE_BUYER', createdById: 1 } }),
      });
      api.tickets.actions.mockResolvedValue({
        currentState: { lifecycle: 'ACTIVE', salesStage: 'QUOTE_BUYER', paymentStatus: null, fulfillmentStatus: null, status: 'quotation_issued' },
        availableActions: [],
      });
      api.pricingRequests.listForTicket.mockResolvedValue({
        items: [{
          id: 501, requestCode: 'PCR-2026-0501', ticketId: 701, ticketCreatedById: 1,
          status: 'QUOTATION_ISSUED', recipientType: 'BUYER', recipientLabel: null, orderConfirmedAt: null,
        }],
      });
      api.dealQuotations.listForTicket.mockResolvedValue({
        items: [{
          id: 9101, number: 'QT-2026-0101-1', origin: 'PRICING_REQUEST', pricingRequestId: 501, docStatus: 'ISSUED',
          recipientType: 'BUYER', salesRepId: 1, revisionNo: 1, parentQuotationId: null, grandTotal: 1070,
        }],
      });

      renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser);

      await waitFor(() => expect(screen.getByTestId('ticket-primary-action').getAttribute('data-action')).toBe('record_quotation_outcome'));
      expect(screen.getAllByRole('button', { name: /บันทึกผลใบเสนอราคา/ })).toHaveLength(1);
      fireEvent.click(screen.getByTestId('ticket-primary-action'));

      await waitFor(() => expect(screen.getByTestId('location-probe').textContent).toBe('/pricing-requests/501'));
      expect(api.pricingRequests.recordCustomerQuotationOutcome).not.toHaveBeenCalled();
    });
  });

  // The blocker line ("รอชำระมัดจำ" / "รอชำระส่วนที่เหลือ") must not show for account: account's OWN
  // action (confirmDeposit/confirmFinalPayment, nextAccountAction) clears each wait, so it must
  // never read as a blocker FOR them. Uses a legacy (status: 'document_issued') ticket so
  // nextAccountAction resolves to null (its gates require status: 'quotation_issued'), isolating the
  // blocker guard from the resolver-first primary, which would mask the same bug by giving account a
  // real primary action (see workState.test.js's account/ORDER_RECEIVED case).
  //
  // The "shows it to someone else" side uses sales_manager, not ceo: ROLE_PERMISSIONS
  // .canConfirmPayments (src/api/routes.js) is `['account', 'ceo']`, so an `isAccount` guard would
  // also suppress it for ceo. The page uses `isMoneyRecorder` (role === 'account' exactly); see the
  // ceo-specific case below.
  describe('blocker line respects !isMoneyRecorder (P3, tightened by GLA-118)', () => {
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

    // canConfirmPayments (and an `isAccount` guard) includes ceo, but recording/confirming a payment
    // is account-only (PAYMENT_RECORD_ROLES/DEPOSIT_CONFIRM_ROLES have no CEO fallback), so the CEO
    // does not clear this wait and must see the same read-only "รอชำระมัดจำ" status a sales_manager
    // does, not silence.
    it('shows "รอชำระมัดจำ" to ceo too — the CEO can no longer confirm the deposit either', async () => {
      api.tickets.get.mockResolvedValue({ ticket: legacyDepositWaitingTicket() });
      api.tickets.actions.mockResolvedValue({
        currentState: {
          lifecycle: 'ACTIVE', salesStage: 'QUOTE_DESIGN_SIDE', paymentStatus: 'DEPOSIT_NOTICE_ISSUED', fulfillmentStatus: null, status: 'document_issued',
        },
        availableActions: [],
      });

      renderTicketDetailPage(ceoUser);

      expect((await screen.findAllByText(/รอชำระมัดจำ/)).length).toBeGreaterThan(0);
    });
  });

  // "รอ CEO ตรวจสอบปิดงาน" is a WAIT, so it stops once the CEO has verified (lifecycle COMPLETED);
  // closeConfirmedAt stays set forever, so it cannot be the only test.
  describe('blocker line "รอ CEO ตรวจสอบปิดงาน" ends when the CEO has verified (QA-27)', () => {
    const salesManagerUser = { id: 11, employeeId: 11, name: 'ผจก.ขาย', role: 'sales_manager' };
    function closeConfirmedDeal(lifecycle) {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({
          summary: {
            lifecycle, salesStage: 'DELIVERED', status: 'quotation_issued', paymentStatus: 'FULLY_PAID',
            closeConfirmedAt: '2026-07-20T09:00:00.000Z',
          },
        }),
      });
      api.tickets.actions.mockResolvedValue({
        currentState: { lifecycle, salesStage: 'DELIVERED', paymentStatus: 'FULLY_PAID', fulfillmentStatus: 'FULLY_DELIVERED', status: 'quotation_issued' },
        availableActions: [],
      });
    }

    it('still shows to a non-CEO viewer while the deal is ACTIVE and awaiting verification', async () => {
      closeConfirmedDeal('ACTIVE');
      renderTicketDetailPage(salesManagerUser);
      expect((await screen.findAllByText('รอ CEO ตรวจสอบปิดงาน')).length).toBeGreaterThan(0);
    });

    it('does not show to the CEO once the deal is COMPLETED (the CEO has no verify action left)', async () => {
      closeConfirmedDeal('COMPLETED');
      renderTicketDetailPage(ceoUser);
      await screen.findByRole('heading', { level: 1, name: 'บริษัท ทดสอบ จำกัด' });
      expect(screen.queryByText('รอ CEO ตรวจสอบปิดงาน')).toBeNull();
    });

    it.each(['COMPLETED', 'CANCELLED', 'CLOSED_LOST'])(
      'does not show to a non-CEO viewer once the deal is %s (the CEO can only verify an ACTIVE deal)',
      async (lifecycle) => {
        closeConfirmedDeal(lifecycle);
        renderTicketDetailPage(salesManagerUser);
        await screen.findByRole('heading', { level: 1, name: 'บริษัท ทดสอบ จำกัด' });
        expect(screen.queryByText('รอ CEO ตรวจสอบปิดงาน')).toBeNull();
      },
    );
  });

  // Deal tracking: DealTrackingPanel is in "ดีล" (the default tab; see ticketDetailTabs.js), so
  // these tests don't call openTab.
  describe('deal tracking panel', () => {
    it('shows the section and the SERVER win%; the pre-emptive gate hint now sits next to the advance button, not here', async () => {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({
          // The panel renders TicketSummaryDto.effectiveWinProbability off the payload instead of
          // re-deriving it from a copied stage→% table. 37 is a value NO stage default holds: if it
          // reads 40 the panel is deriving from QUOTE_DESIGN_SIDE again, and if it reads 0 the field
          // stopped being read.
          summary: { salesStage: 'QUOTE_DESIGN_SIDE', effectiveWinProbability: 37 },
        }),
      });

      renderTicketDetailPage(ceoUser);

      expect(await screen.findByRole('heading', { level: 2, name: 'การติดตามดีล' })).not.toBeNull();
      expect(await screen.findByText('ยังไม่พร้อม')).not.toBeNull();
      expect(screen.getByText('37%')).not.toBeNull();
      expect(screen.queryByText('40%')).toBeNull();
      // The descriptive gate sentence renders next to DealStagePanel's "เลื่อนไป" button (see the
      // "stage-advance readiness sits next to the button" describe block), so it must NOT reappear
      // here beside the compact badge.
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

    // The add-activity form lives in DealHistoryPanel ("ประวัติ" tab), not DealTrackingPanel ("ดีล").
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

    // account GETS the "ประวัติ" tab (the audit trail + comment box are backed by requireViewAccess,
    // which account passes), but DealTrackingPanel ("การติดตามดีล") and the activities fetch stay
    // gated on requireDealOwnership (see ticketDetailTabs.js's doc comment on "history" and
    // TicketDetailPage.jsx's on the "deal"/"history" TabPanels). The two assertions are on different
    // tabs: DealTrackingPanel on the default "ดีล" (absent for account), the audit trail on "ประวัติ".
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
      // Same reason it's a no-op still applies (list, not just its own gate) — see
      // the DealStagePanel-side openAdvance() re-check tests for the pure gate.
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

    // The old inline "เลื่อนไป" button was `disabled={actionLoading}`; the overflow item's `disabled`
    // must carry that too: reopening "⋯" and clicking while the first updateStage is pending would
    // fire a second POST, landing as a 409 "Deal is already in stage X" toast
    // (TicketService.java:1143). Proves it end to end: the item is disabled while in flight, AND the
    // click is a genuine no-op (exactly one request total).
    it('a second click on เลื่อนไป while the first updateStage is still in flight does not fire a duplicate request', async () => {
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

  // "ราคาและใบเสนอราคา": the customer-quotation tail on the deal page.
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
      // DealQuotationPanel's home tab is still visible to sales (it also hosts the legacy quotation
      // docs): open it to prove the panel itself renders nothing, not merely that we never looked.
      await openTab(/เอกสาร/);
      expect(screen.queryByRole('heading', { level: 2, name: 'ราคาและใบเสนอราคา' })).toBeNull();
    });

    it('import (no business in the customer quotation) never sees the section — Slice D still widens the "เอกสาร" tab itself', async () => {
      api.pricingRequests.listForTicket.mockResolvedValue({ items: [approvedPr] });

      renderTicketDetailPage({ id: 7, employeeId: 7, name: 'ฝ่ายนำเข้า', role: 'import' });

      await screen.findByRole('heading', { level: 1, name: 'บริษัท ทดสอบ จำกัด' });
      // "เอกสาร" is role-unconditional (see ticketDetailTabs.js), so a non-assignee import rep sees the
      // TAB, but the "KNOWN GAP" in ticketDetailTabs.js is unchanged: salesViewScope.js hides BOTH
      // dealQuotation and quotation from import outright, so neither DealQuotationPanel's section nor
      // a quotation row inside DealDocumentRegister ever appears for it.
      expect(await screen.findByRole('tab', { name: /เอกสาร/ })).not.toBeNull();
      await openTab(/เอกสาร/);
      expect(screen.queryByRole('heading', { level: 2, name: 'ราคาและใบเสนอราคา' })).toBeNull();
      expect(screen.queryByTestId('register-quotations')).toBeNull();
      expect(api.pricingRequests.listCustomerQuotations).not.toHaveBeenCalled();
    });

    // An acceptance is the order whoever accepted, so the panel reads main's sentence again, never
    // the retired "เลือกผู้รับรายถัดไป" pointer.
    it('an accepted designer request reads the ยืนยันคำสั่งซื้อ sentence, never a next-party pointer', async () => {
      api.pricingRequests.listForTicket.mockResolvedValue({
        items: [{ ...approvedPr, ticketId: 701, status: 'QUOTATION_ACCEPTED', recipientType: 'DESIGNER' }],
      });

      renderTicketDetailPage(salesOwnerUser);
      await openTab(/เอกสาร/);

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
      await openTab(/เอกสาร/);
      fireEvent.click(await screen.findByTestId('deal-quotation-accept'));

      const dialog = within(await screen.findByRole('dialog', { name: 'ยืนยันว่าลูกค้ายอมรับใบเสนอราคานี้' }));
      expect(dialog.getByText('ใบเสนอราคานี้ส่งถึงผู้ออกแบบ ถ้าลูกค้ายอมรับ ดีลจะใช้ใบนี้ยืนยันคำสั่งซื้อและข้ามผู้รับรายถัดไป')).not.toBeNull();
      expect(api.pricingRequests.recordCustomerQuotationOutcome).not.toHaveBeenCalled();
      fireEvent.click(dialog.getByRole('button', { name: 'ยืนยัน' }));

      await waitFor(() => expect(api.pricingRequests.recordCustomerQuotationOutcome).toHaveBeenCalledWith(
        9301, expect.objectContaining({ outcome: 'ACCEPTED' }),
      ));
    });

    // The buyer's (last party's) accept asks too: the panel must not post it on the first click.
    it('the panel\'s ลูกค้ายอมรับ on a buyer request asks first (no "ข้ามผู้รับรายถัดไป"); records ACCEPTED only after ยืนยัน', async () => {
      api.pricingRequests.listForTicket.mockResolvedValue({
        items: [{ ...approvedPr, ticketId: 701, status: 'QUOTATION_ISSUED', recipientType: 'BUYER' }],
      });
      api.pricingRequests.listCustomerQuotations.mockResolvedValue({
        items: [{ id: 9301, number: 'QT-2026-0301-1', docStatus: 'ISSUED', quotationRevisionNo: 1, grandTotal: 1000 }],
      });
      api.pricingRequests.recordCustomerQuotationOutcome.mockResolvedValue({ quotation: { id: 9301, docStatus: 'ACCEPTED' } });

      renderTicketDetailPage(salesOwnerUser);
      await openTab(/เอกสาร/);
      fireEvent.click(await screen.findByTestId('deal-quotation-accept'));

      const dialog = within(await screen.findByRole('dialog', { name: 'ยืนยันว่าลูกค้ายอมรับใบเสนอราคานี้' }));
      expect(dialog.queryByText(/ข้ามผู้รับรายถัดไป/)).toBeNull();
      expect(api.pricingRequests.recordCustomerQuotationOutcome).not.toHaveBeenCalled();
      fireEvent.click(dialog.getByRole('button', { name: 'ยืนยัน' }));

      await waitFor(() => expect(api.pricingRequests.recordCustomerQuotationOutcome).toHaveBeenCalledWith(
        9301, expect.objectContaining({ outcome: 'ACCEPTED' }),
      ));
    });

    // The accept confirm records the quotation that was clicked, even if a refetch swaps the shown one.
    it('a refetch while the ลูกค้ายอมรับ dialog is open still records ACCEPTED on the clicked quotation', async () => {
      api.pricingRequests.listForTicket.mockResolvedValue({
        items: [{ ...approvedPr, ticketId: 701, status: 'QUOTATION_ISSUED', recipientType: 'BUYER' }],
      });
      const row = (id, number) => ({ id, number, docStatus: 'ISSUED', quotationRevisionNo: 1, grandTotal: 1000 });
      api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [row(9301, 'QT-2026-0301-1')] });
      api.pricingRequests.recordCustomerQuotationOutcome.mockResolvedValue({ quotation: { id: 9301, docStatus: 'ACCEPTED' } });

      const { queryClient } = renderTicketDetailPage(salesOwnerUser);
      await openTab(/เอกสาร/);
      fireEvent.click(await screen.findByTestId('deal-quotation-accept'));
      const dialog = within(await screen.findByRole('dialog', { name: 'ยืนยันว่าลูกค้ายอมรับใบเสนอราคานี้' }));

      api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [row(9302, 'QT-2026-0302-1')] });
      await act(async () => { await queryClient.invalidateQueries(); });
      await screen.findAllByText('QT-2026-0302-1');

      fireEvent.click(dialog.getByRole('button', { name: 'ยืนยัน' }));
      await waitFor(() => expect(api.pricingRequests.recordCustomerQuotationOutcome).toHaveBeenCalled());
      expect(api.pricingRequests.recordCustomerQuotationOutcome).toHaveBeenCalledWith(9301, expect.objectContaining({ outcome: 'ACCEPTED' }));
      api.pricingRequests.recordCustomerQuotationOutcome.mockReset();
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
        await openTab(/เอกสาร/);
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

      it.each([
        ['deal-quotation-reject', 'REJECTED'],
        ['deal-quotation-revision', 'REVISION_REQUESTED'],
      ])('%s opens a dialog; ยืนยัน needs a reason; it records %s with customerNote = the reason, then closes', async (testId, outcome) => {
        record().mockResolvedValue({ quotation: { id: 9301, docStatus: outcome } });
        await renderIssuedPanel();
        const [id, body] = await confirmWithReason(testId, 'ราคาสูงกว่างบ');
        expect(id).toBe(9301);
        expect(body).toEqual(expect.objectContaining({ outcome, customerNote: 'ราคาสูงกว่างบ', clientRequestId: expect.any(String) }));
        await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
      });

      it('ยกเลิก records nothing', async () => {
        await renderIssuedPanel();
        const dialog = await openDialog('deal-quotation-reject');
        fireEvent.change(dialog.getByRole('textbox'), { target: { value: 'เปลี่ยนใจ' } });
        await cancelDialog();
        expect(record()).not.toHaveBeenCalled();
      });

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

      it('after a FAILED ปฏิเสธ, ขอแก้ไข gets a different key', async () => {
        record().mockRejectedValueOnce(new Error('network down'));
        await renderIssuedPanel();
        const [, first] = await confirmWithReason('deal-quotation-reject', 'เหตุผล ก');
        await failureSettled();
        await cancelDialog();
        const [, revise] = await confirmWithReason('deal-quotation-revision', 'เหตุผล ข');
        expect(revise.outcome).toBe('REVISION_REQUESTED');
        expect(revise.clientRequestId).not.toBe(first.clientRequestId);
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

      it('records against the quotation shown when ปฏิเสธ was clicked, even if the panel refetches to another one before ยืนยัน', async () => {
        record().mockResolvedValue({ quotation: { id: 9301, docStatus: 'REJECTED' } });
        await renderIssuedPanel();
        const dialog = await openDialog('deal-quotation-reject');
        fireEvent.change(dialog.getByRole('textbox'), { target: { value: 'เหตุผล ก' } });

        await switchQuotationTo(9302, 'QT-2026-0302-1');
        fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'ยืนยัน' }));
        await waitFor(() => expect(record()).toHaveBeenCalledTimes(1));
        expect(record().mock.calls[0][0]).toBe(9301);
      });
    });

    // Spec F1: the panel sees the quotation the sticky เสนอใบราคา writes (the deal's quotation list,
    // PRICING_REQUEST origin) — number, status, a link to it — and says "no quotation yet" only when
    // neither engine has one.
    describe('with the request\'s new-engine quotation in the deal\'s quotation list', () => {
      afterEach(() => { api.dealQuotations.listForTicket.mockReset(); });

      // The third row: only a live old-engine quotation takes the panel; a dead one does not hide the
      // new-engine quotation.
      it.each([
        ['APPROVED_FOR_QUOTATION', 'DRAFT', []],
        ['QUOTATION_ISSUED', 'ISSUED', []],
        ['APPROVED_FOR_QUOTATION', 'DRAFT', [{ id: 77, quotationNo: 'QT-OLD-0001', quotationRevisionNo: 1, docStatus: 'CANCELLED' }]],
      ])('a %s request with its %s quotation (old-engine rows: %j): the panel shows its number, status and เปิดใบเสนอราคา — not "no quotation yet"', async (status, docStatus, oldEngineRows) => {
        api.pricingRequests.listForTicket.mockResolvedValue({ items: [{ ...approvedPr, ticketId: 701, status }] });
        api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: oldEngineRows });
        api.dealQuotations.listForTicket.mockResolvedValue({
          items: [{
            id: 9101, number: 'QT-2026-0101-1', origin: 'PRICING_REQUEST', pricingRequestId: 501, docStatus,
            recipientType: 'BUYER', salesRepId: 1, revisionNo: 1, parentQuotationId: null, grandTotal: 1070,
          }],
        });

        renderTicketDetailPage(salesOwnerUser);
        await openTab(/เอกสาร/);

        const panel = within(await screen.findByTestId('deal-quotation-panel'));
        expect(await panel.findByText('QT-2026-0101-1')).not.toBeNull();
        expect(panel.getByText(quotationMeta.dealQuotationStatusLabel(docStatus).label)).not.toBeNull();
        expect(panel.getByRole('link', { name: 'เปิดใบเสนอราคา' }).getAttribute('href')).toBe('/quotations/9101');
        expect(panel.queryByText(/ยังไม่มีใบเสนอราคาลูกค้าสำหรับคำขอราคานี้/)).toBeNull();
        expect(panel.queryByText(/กดปุ่ม “เสนอใบราคา”/)).toBeNull();
      });
    });
  });

  // The "มัดจำ" 3-step card (นโยบายมัดจำ → ใบแจ้งยอดมัดจำ → รับชำระมัดจำ) is gone from the การเงิน tab.
  // Its controls live elsewhere: the % comes from the quotation, the notice is created from the main
  // button / เอกสาร tab, and account confirms the money on /finance/deals/:id.
  describe('การเงิน tab — no มัดจำ card', () => {
    it.each([['the owning rep', salesOwnerUser], ['account', accountUser]])('%s sees no มัดจำ card and none of its controls', async (_label, user) => {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({ summary: { status: 'quotation_issued', paymentStatus: 'DEPOSIT_NOTICE_ISSUED', createdById: 1 } }),
      });
      // Advertise every action the old card keyed on, so its absence can't be a missing gate.
      api.tickets.actions.mockResolvedValue({
        currentState: { lifecycle: 'ACTIVE', salesStage: 'DEPOSIT_RECEIVED', paymentStatus: 'DEPOSIT_NOTICE_ISSUED', fulfillmentStatus: null, status: 'quotation_issued' },
        availableActions: [
          { action: 'DEPOSIT_PAID', kind: 'payment', label: 'รับมัดจำ' },
        ],
      });

      renderTicketDetailPage(user);
      await openTab(/การเงิน/);
      await screen.findByRole('heading', { level: 2, name: 'การชำระเงิน' });

      expect(screen.queryByRole('heading', { level: 2, name: 'มัดจำ' })).toBeNull();
      expect(screen.queryByTestId('deal-deposit-panel')).toBeNull();
      expect(document.getElementById('deal-deposit-panel')).toBeNull();
      expect(screen.queryByText('นโยบายมัดจำ')).toBeNull();
      expect(screen.queryByText('รับชำระมัดจำ')).toBeNull();
      expect(screen.queryByRole('button', { name: 'ยืนยันรับมัดจำ' })).toBeNull();
    });

    it('import (no business in the money tab) never gets a "การเงิน" tab at all', async () => {
      renderTicketDetailPage({ id: 7, employeeId: 7, name: 'ฝ่ายนำเข้า', role: 'import' });

      await screen.findByRole('heading', { level: 1, name: 'บริษัท ทดสอบ จำกัด' });
      // ledger_importCannotReadThePaymentLedger / depositNotice_import...Refused —
      // both money sub-reads deny import, so the whole tab is absent (see
      // ticketDetailTabs.js's "money" gate), not just this one section.
      expect(screen.queryByRole('tab', { name: /การเงิน/ })).toBeNull();
      expect(api.depositNotices.listByTicket).not.toHaveBeenCalled();
    });

    // Account is sent to the finance deal page (nextAccountAction's `to`), not to a missing element.
    it.each([
      ['confirmDeposit', { paymentStatus: 'DEPOSIT_NOTICE_ISSUED' }],
      ['chaseOverdue', { paymentStatus: 'DEPOSIT_NOTICE_ISSUED', overdue: true, amountOutstanding: 500 }],
    ])('account\'s %s primary navigates to /finance/deals/701 instead of scrolling to the removed card', async (key, extra) => {
      const state = { lifecycle: 'ACTIVE', salesStage: 'DEPOSIT_RECEIVED', paymentStatus: 'DEPOSIT_NOTICE_ISSUED', fulfillmentStatus: null, status: 'quotation_issued' };
      api.tickets.get.mockResolvedValue({ ticket: buildTicket({ summary: { ...state, createdById: 1, ...extra } }) });
      api.tickets.actions.mockResolvedValue({ currentState: state, availableActions: [] });
      renderTicketDetailPageAtRoute(['/tickets/701'], accountUser);

      await waitFor(() => expect(screen.queryByTestId('ticket-primary-action')?.getAttribute('data-action')).toBe(key));
      fireEvent.click(screen.getByTestId('ticket-primary-action'));

      await waitFor(() => expect(screen.getByTestId('location-probe').textContent).toBe('/finance/deals/701'));
    });
  });

  // "การส่งมอบ / นำเข้า": the deal-level IR/shipping/goods-received/delivery chain + optional
  // per-factory PO detail.
  describe('deal fulfilment panel', () => {
    const importUser = { id: 7, employeeId: 7, name: 'ฝ่ายนำเข้า', role: 'import' };

    // DealFulfilmentPanel lives in the "จัดซื้อ-ส่งมอบ" tab.
    async function fulfilmentSection() {
      await openTab(/จัดซื้อ-ส่งมอบ/);
      const heading = await screen.findByRole('heading', { level: 2, name: 'การส่งมอบ / นำเข้า' });
      return within(heading.closest('section'));
    }

    // Regression: the /fulfilment workspace gave the four fulfilment codes a `to` of '/fulfilment',
    // which made the sticky bar navigate away instead of scrolling to the panel already on this page.
    // The in-page target must win whenever the page has one.
    it('import’s sticky CTA scrolls to the fulfilment panel instead of navigating to /fulfilment', async () => {
      // Records WHICH element was scrolled, not merely that something was: a bare vi.fn() on the
      // prototype passes with the fix reverted (other things scroll on mount). Mutation-checked: with
      // `&& !jumpId` removed, the id assertion below goes red and the bare-call assertion didn't.
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
              lifecycle: 'ACTIVE', salesStage: 'PROCUREMENT', fulfillmentStatus: 'IR_SENT',
            },
          }),
        });
        api.tickets.actions.mockResolvedValue({
          currentState: { lifecycle: 'ACTIVE', salesStage: 'PROCUREMENT', paymentStatus: 'DEPOSIT_PAID', fulfillmentStatus: 'IR_SENT', status: 'quotation_issued' },
          availableActions: [{ action: 'SHIPPING', kind: 'fulfillment', label: 'สินค้าเดินทาง' }],
        });

        renderTicketDetailPage(importUser);
        await screen.findByRole('heading', { level: 1, name: 'บริษัท ทดสอบ จำกัด' });

        const sticky = await screen.findByTestId('ticket-primary-action');
        expect(sticky.getAttribute('data-action')).toBe('markShipping');

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

    // Same regression class, sales' side: RECORD_DELIVERY (stages 13-14 ส่งมอบสินค้า) must ALSO land the
    // sticky bar on this panel via an in-page scroll. nextSalesAction never sets `.to`, so the
    // `to && to !== ... && !jumpId` branch is never live for this key; this test proves
    // IN_PAGE_JUMP_TARGET.record_delivery is what routes it.
    it('sales’ sticky CTA scrolls to the fulfilment panel for a delivery-ready deal it owns', async () => {
      const scrolledIds = [];
      const original = Element.prototype.scrollIntoView;
      Element.prototype.scrollIntoView = function scrollIntoViewSpy() {
        scrolledIds.push(this.id);
      };
      try {
        // Zero pricing requests (api.pricingRequests.listForTicket keeps its beforeEach default,
        // { items: [] }) + a non-null paymentStatus is the "priced outside the PCR chain" shape
        // (salesActions.js bucket 1's guard), like demoData.js tickets 13/14 and the
        // salesActions.test.js / workState.test.js unit pins. The remaining invoice wins over
        // บันทึกส่งมอบ until issued, so seed it issued.
        api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [{ id: 901, ticketId: 701, status: 'ISSUED', docNumber: 'GLRI69001' }] });
        api.tickets.get.mockResolvedValue({
          ticket: buildTicket({
            summary: {
              status: 'quotation_issued', paymentStatus: 'AWAITING_FINAL_PAYMENT',
              lifecycle: 'ACTIVE', salesStage: 'DELIVERY_SCHEDULING', fulfillmentStatus: 'GOODS_RECEIVED',
            },
          }),
        });

        renderTicketDetailPage(salesOwnerUser);
        await screen.findByRole('heading', { level: 1, name: 'บริษัท ทดสอบ จำกัด' });

        const sticky = await screen.findByTestId('ticket-primary-action');
        expect(sticky.getAttribute('data-action')).toBe('record_delivery');

        fireEvent.click(sticky);

        await waitFor(() => expect(scrolledIds).toContain('deal-fulfilment-panel'));
      } finally {
        Element.prototype.scrollIntoView = original;
        api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [] });
      }
    });

    // ── ใบขอซื้อ (F-SM-001) download block ──────────────────────────────────────────────
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

    // The one-click IR is gone: import is offered no ออกคำขอนำเข้า (IR) button even when a stale
    // server still advertises ISSUE_IMPORT_REQUEST.
    it('import is offered no one-click Import Request button', async () => {
      api.tickets.get.mockResolvedValueOnce({
        ticket: buildTicket({ summary: { status: 'quotation_issued', paymentStatus: 'DEPOSIT_PAID' } }),
      });
      api.tickets.actions.mockResolvedValueOnce({
        currentState: { lifecycle: 'ACTIVE', salesStage: 'DEPOSIT_RECEIVED', paymentStatus: 'DEPOSIT_PAID', fulfillmentStatus: null, status: 'quotation_issued' },
        availableActions: [{ action: 'ISSUE_IMPORT_REQUEST', kind: 'fulfillment', label: 'ออกคำขอนำเข้า' }],
      });

      renderTicketDetailPage(importUser);
      const section = await fulfilmentSection();
      expect(section.queryByRole('button', { name: 'ออกคำขอนำเข้า (IR)' })).toBeNull();
      expect(api.tickets.issueImportRequest).not.toHaveBeenCalled();
    });

    it('account sees the section read-only — no fulfilment action buttons, no factory-PO detail', async () => {
      api.tickets.get.mockResolvedValueOnce({
        ticket: buildTicket({ summary: { status: 'quotation_issued', fulfillmentStatus: 'SHIPPING' } }),
      });
      api.tickets.actions.mockResolvedValueOnce({
        currentState: { lifecycle: 'ACTIVE', salesStage: 'PROCUREMENT', paymentStatus: 'FULLY_PAID', fulfillmentStatus: 'SHIPPING', status: 'quotation_issued' },
        // A deliberately unrealistic payload (mirrors the "retired verbs" test above) proving the
        // role gate, not just the absence of the action.
        //
        // RESERVE_STOCK, RECORD_PARTIAL_DELIVERY and COMPLETE_DELIVERY are NOT in this list: their
        // local role checks were removed on purpose, because TicketService.canDeclareStockCoverage /
        // canWriteDelivery grant them to the deal owner as well as import/ceo, and actions()
        // advertises them off that same predicate. ANDing a frontend role set would throw away the
        // server's answer, so feeding account actions the real service can't produce for it would
        // assert a gate that intentionally doesn't exist. The realistic case (account is offered
        // nothing, so sees nothing) is asserted below; the panel renders those buttons purely from
        // hasAction().
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

    // ── There is no reserve-from-stock feature ─────────
    //
    // A stock line ends when the CEO confirms the price and then waits for delivery; nobody reserves
    // anything. The จองสินค้าจากสต็อก control is gone for every role, even if an older server still
    // advertises RESERVE_STOCK to the deal owner (the endpoint stays for fixtures, the UI does not).
    it('never shows จองสินค้าจากสต็อก - not even to the sales deal owner when the server still advertises RESERVE_STOCK', async () => {
      api.tickets.get.mockResolvedValueOnce({
        ticket: buildTicket({ summary: { status: 'quotation_issued', salesStage: 'ORDER_RECEIVED', createdById: 1 } }),
      });
      api.tickets.actions.mockResolvedValueOnce({
        currentState: { lifecycle: 'ACTIVE', salesStage: 'ORDER_RECEIVED', paymentStatus: 'DEPOSIT_PAID', fulfillmentStatus: null, status: 'quotation_issued' },
        availableActions: [{ action: 'RESERVE_STOCK', kind: 'fulfillment', label: 'จองสินค้าจากสต็อก' }],
      });

      renderTicketDetailPage(salesOwnerUser);
      const section = await fulfilmentSection();

      expect(section.queryByRole('button', { name: 'จองสินค้าจากสต็อก' })).toBeNull();
    });

    // ── Stages 13-14 (ส่งมอบสินค้า) belong to Sales ─────────────
    //
    // TicketService.canWriteDelivery is FULFILMENT_ROLES ∪ (sales ∧ deal owner) and actions()
    // advertises RECORD_PARTIAL_DELIVERY/COMPLETE_DELIVERY off it, so the panel has no local
    // `isFulfilment` check on those two and the server's answer stands.
    //
    // ⚠️ RENDERING assertions over a mocked `api`: they pin that the frontend honours the server's
    // answer, and say NOTHING about who the real service grants. That claim is
    // DeliveryAuthzIntegrationTest's, against real Postgres (see CLAUDE.md).
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

    // ── A from-stock deal must not claim import milestones ────────
    //
    // SubstepChips once walked a FLAT list and marked every entry before the current one done.
    // FROM_STOCK sat at index 4, after the whole import sequence, so a from-stock deal rendered four
    // milestones it never performed (and, per issueImportRequest's guard, never could have).
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

    // The other half: the import journey must still render in full for a deal that is actually on it.
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

  // DealStateHeader's stat-chip set is role-aware (see DealStateHeader.jsx's
  // CAN_VIEW_PRICING_REQUESTS_ROLES doc comment). ขั้นตอนดีล and การนำเข้า are unconditional for every
  // role that reaches this page; คำขอราคา, การชำระเงิน, and มูลค่าดีล vary. Assertions are scoped to the
  // header (`deal-state-header`): some labels have similar Thai copy elsewhere (the "การเงิน" tab, the
  // money panel's "การชำระเงิน" <h2>), so an unscoped query would be weaker.
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

    // Correctness, not decluttering (see DealStateHeader.jsx's doc comment): canViewPricingRequests
    // excludes `account`, so `pricingRequests` is hard-coded to `[]` for that role whether or not
    // requests exist, and the chip would read "ยังไม่มี" even when requests are live.
    it('account never gets the "คำขอราคา" chip (wrong-way-round: it cannot see this even though it keeps การชำระเงิน/มูลค่าดีล)', async () => {
      renderTicketDetailPage(accountUser);
      const header = await screen.findByTestId('deal-state-header');
      expect(within(header).queryByText('คำขอราคา')).toBeNull();
      expect(within(header).getByText('ขั้นตอนดีล')).not.toBeNull();
      expect(within(header).getByText('การชำระเงิน')).not.toBeNull();
      expect(within(header).getByText('การนำเข้า')).not.toBeNull();
      expect(within(header).getByText('มูลค่าดีล')).not.toBeNull();
    });

    // Reduces what import is SHOWN here, NOT a security fix (see DealStateHeader.jsx's doc comment):
    // TicketService.projectForRole still sends summary.amountPayable to import over the wire.
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

  // The header's การนำเข้า chip follows the per-factory IR steps, so the page reads the stored IRs
  // (not only the fulfilment tab) for every role the backend lets read them, and passes them down.
  describe('การนำเข้า header chip follows the stored IRs (QA-22)', () => {
    function dealWithTwoFactories() {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({ summary: { lifecycle: 'ACTIVE', salesStage: 'PROCUREMENT', fulfillmentStatus: 'IR_ISSUED', createdById: 1 } }),
      });
      api.tickets.actions.mockResolvedValue({
        currentState: { lifecycle: 'ACTIVE', salesStage: 'PROCUREMENT', paymentStatus: null, fulfillmentStatus: 'IR_ISSUED', status: 'price_proposed' },
        availableActions: [],
      });
      api.storedImportRequests.listForTicket.mockResolvedValue({
        importRequests: [
          { id: 31, status: 'ISSUED', ticketId: 701, importStep: 'IN_TRANSIT' },
          { id: 32, status: 'ISSUED', ticketId: 701, importStep: 'RECEIVED' },
        ],
      });
    }
    afterEach(() => {
      api.storedImportRequests.listForTicket.mockResolvedValue({ importRequests: [] });
    });
    const chip = async () => {
      const header = await screen.findByTestId('deal-state-header');
      return within(within(header).getByText('การนำเข้า').parentElement);
    };

    it.each([
      ['the owning sales rep', salesOwnerUser],
      ['import (not the owner)', { id: 7, employeeId: 7, name: 'ฝ่ายนำเข้า', role: 'import' }],
      ['sales_manager (not the owner)', { id: 11, employeeId: 11, name: 'ผจก.ขาย', role: 'sales_manager' }],
    ])('shows the slowest factory step with the arrival count to %s', async (_name, viewer) => {
      dealWithTwoFactories();
      renderTicketDetailPage(viewer);
      expect(await (await chip()).findByText('กำลังเดินทาง · ถึงโกดัง 1/2')).not.toBeNull();
    });

    it('a non-owner sales rep cannot read stored IRs: the chip keeps the deal-level label and no request is made', async () => {
      dealWithTwoFactories();
      renderTicketDetailPage({ id: 2, employeeId: 2, name: 'พนักงานขายอีกคน', role: 'sales' });
      expect((await chip()).getByText('ออกใบขอซื้อแล้ว')).not.toBeNull();
      expect(api.storedImportRequests.listForTicket).not.toHaveBeenCalled();
    });
  });

  // Role-projected tab VISIBILITY (ticketDetailTabs.js has its own unit tests for the pure function;
  // these prove the page wires it in, per role, end to end).
  describe('tab visibility per role', () => {
    // "เอกสาร" is role-unconditional, like "ประวัติ": import sees the TAB (it may reach an attachments
    // roll-up once it is this deal's assignee), but the KNOWN GAP in this file's doc comment is
    // unchanged: salesViewScope.js hides both dealQuotation and quotation from import, so it reaches
    // zero quotation rows inside that tab (see DealDocumentRegister.test.jsx).
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

    // PricingRequestPanel is an inner condition inside the unconditionally-visible "สินค้าและราคา", so
    // account keeps this tab (the content-level test in the "role -> reachable-content projection"
    // describe proves it still cannot reach the panel inside it). "เอกสาร" is widened to account (it
    // confirms money against the deposit-notice/attachment rows the register lists) though it can't
    // reach a quotation row inside it (quotation_accountCannotListCustomerQuotations).
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

  // Every gate that used to decide a whole TAB's existence decides either the same tab
  // (money/fulfilment/documents) or an INNER render condition inside an unconditionally-visible tab
  // (pricing-request panel inside "สินค้าและราคา", attachments panel inside "ประวัติ"). These prove the
  // set of *content* each role can reach.
  describe('role -> reachable-content projection is unchanged by the 7->6 tab restructure', () => {
    it('account still cannot reach pricing-request content (formerly its own "ราคา" tab, now an inner condition inside "สินค้าและราคา")', async () => {
      renderTicketDetailPage(accountUser);
      await openTab(/สินค้าและราคา/);
      expect(screen.queryByRole('heading', { name: 'คำขอราคา' })).toBeNull();
      expect(api.pricingRequests.listForTicket).not.toHaveBeenCalled();
    });

    // account reaches the "เอกสาร" TAB but still cannot reach a single quotation ROW inside it
    // (quotation_accountCannotListCustomerQuotations).
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

  // The document-read gate (createdById/assignedToId participant OR
  // ROLE_PERMISSIONS.canViewTicketDocuments) decides DealAttachmentsPanel's presence as an inner
  // render condition inside "ประวัติ" (reachable regardless; see ticketDetailTabs.js); only the panel
  // comes and goes.
  describe('attachments panel inside "ประวัติ" follows the document-read gate, not the deal-read gate', () => {
    // ticketDetailTabs.js's role-level predicate for "ประวัติ" is coarse (`() => true`) because role
    // + sections can't express the identity half of the document gate (a deal's participants reach
    // its documents regardless of role); TicketDetailPage.jsx applies `canViewDocumentsTab` on top,
    // around DealAttachmentsPanel.
    //
    // Reading a deal's documents is the same question as reading the deal
    // (TicketAccessPolicy.canViewDocuments), so `account` and `import` DO see the panel: account
    // confirms deposit/final-payment receipts against these files, and hiding it would hide the
    // backend fix. A `sales` rep on someone else's deal is still refused: that one 403s for real.
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

      // A sales rep who is neither this deal's creator (buildTicket()'s default createdById is 1, i.e.
      // salesOwnerUser) nor its assignee: refused, as the backend refuses them. Wrong-way-round:
      // "ประวัติ" is still reachable (its gate is `() => true`); only the attachments panel vanishes.
      const otherSales = { id: 42, employeeId: 42, name: 'พนักงานขายอื่น', role: 'sales' };
      const { container: otherContainer, unmount: unmountOther } = renderTicketDetailPage(otherSales);
      expect(await screen.findByRole('tab', { name: /ประวัติ/ })).not.toBeNull();
      await openTab(/ประวัติ/);
      expect(await screen.findByRole('heading', { level: 2, name: 'ประวัติดีล' })).not.toBeNull();
      expect(screen.queryByRole('heading', { level: 2, name: 'ไฟล์แนบ (PO / ใบเซ็น)' })).toBeNull();
      expect(otherContainer.querySelector('#ticket-attachment-file')).toBeNull();
      unmountOther();

      // Presentation half of THE IMPORT PIN. import reads the deal and is still refused its
      // documents, because AttachType spans SIGNED_QUOTATION/INVOICE, i.e. the approved customer
      // price that salesViewScope hides from import. Backend pins:
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

    // Reading a document and writing one are different questions: account may open every deal document
    // but may NOT upload. The closing tax invoice has exactly one entry point
    // (CommissionService.createFromDeal); a second upload path would satisfy the close gate's
    // invoiceOnFile check while the rep silently loses their commission.
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
     * Anti-regression guard. The "แนบใบกำกับภาษี" control that used to live in this panel was gated
     * `isAccount` while AttachmentController's gate grants only participants OR {hr, sales_manager,
     * ceo}, so it 403'd for real and looked functional only because mockApi.js had no authz on
     * attachments. Account now READS every deal document (hr reads none) but the WRITE side stays
     * narrow (TicketAccessPolicy.canManageDocuments is participant OR sales_manager/ceo, never
     * account).
     *
     * The control was removed rather than re-gated: the closing tax invoice must come from
     * CommissionService.createFromDeal, which writes the INVOICE attachment AND the deal owner's
     * commission together. A second control would satisfy the close gate's invoiceOnFile while
     * skipping the commission. If this test goes red, someone reintroduced that path.
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
     * Regression guard: `uploadAttachmentMutation`'s onSuccess once called `queryKeys.ticket(ticketId)`,
     * which does not exist (queryKeys.js defines ticketDetail/ticketActions/ticketAttachments/…).
     * It was the THIRD statement, so the invalidations above it ran and the upload looked fine, then
     * `TypeError: queryKeys.ticket is not a function` aborted the handler before showToast.
     * react-query does not route an onSuccess throw to onError and handleUploadAttachment's `catch {}`
     * swallowed the rejection, so a SUCCESSFUL upload told the user nothing.
     *
     * Both assertions matter: the toast covers the silence, and the detail refetch covers the real
     * damage (ticket.summary drives the invoiceOnFile close gate, so ฝ่ายบัญชี's close confirmation
     * would stay stale).
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
      // "documents" is role-unconditional (account can see it), so this case uses "money": import
      // still cannot see it (ledger_importCannotReadThePaymentLedger / depositNotice_import...Refused),
      // and resolveTicketDetailTab must fall back rather than render nothing/crash.
      renderTicketDetailPageAtRoute(['/tickets/701?tab=money'], { id: 7, employeeId: 7, name: 'ฝ่ายนำเข้า', role: 'import' });

      const dealTab = await screen.findByRole('tab', { name: /^ดีล/ });
      await waitFor(() => expect(dealTab.getAttribute('aria-selected')).toBe('true'));
      expect(await screen.findByRole('heading', { level: 2, name: /วันสำคัญ/ })).not.toBeNull();
    });

    // There is no per-instance TAB-level gate: DealAttachmentsPanel is an inner render condition
    // inside "ประวัติ" (an unconditionally-visible tab). A per-instance-excluded viewer's
    // `?tab=history` deep link resolves normally (no fallback; the tab's gate really is `() => true`),
    // and only the attachments panel inside it is missing (see the "attachments panel inside ประวัติ"
    // describe block above for the same predicate without the URL entry point).
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

  // Finding 6 (both entry points must open the dialog, not download directly): this is one of
  // the two entry points — DealDocumentRegister.test.jsx pins the other.
  describe('remaining invoice download entry point (docActions button)', () => {
    afterEach(() => { api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [] }); });
    it('clicking "ดาวน์โหลดใบแจ้งหนี้ส่วนที่เหลือ" opens the RemainingInvoiceDialog rather than downloading directly', async () => {
      api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [{ id: 901, ticketId: 701, status: 'ISSUED', docNumber: 'GLRI69001' }] });
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({
          summary: { status: 'quotation_issued', fulfillmentStatus: 'GOODS_RECEIVED' },
        }),
      });
      api.tickets.remainingInvoiceOptions.mockResolvedValue({
        options: {
          docNumber: 'GLRI69001', defaultIssueDate: '2026-09-01', defaultReference: null,
          referenceOptions: [], defaultDepositReference: null, depositReferenceOptions: [],
          noteTemplates: [], itemCount: 1, itemsTotal: 100, depositAmount: 0,
          netAmount: 100, vatAmount: 7, totalPayable: 107,
          quotationOptions: [], defaultQuotationId: null, blockingReason: null,
        },
      });

      renderTicketDetailPage(salesOwnerUser);

      await waitFor(() => expect(api.storedRemainingInvoices.listForTicket).toHaveBeenCalled());
      const button = await screen.findByRole('button', { name: 'ดาวน์โหลดใบแจ้งหนี้ส่วนที่เหลือ' });
      // Compact inline link like the quotation PDF, not a full-size bordered button.
      expect(button.className).not.toContain('border-border-input');
      expect(button.className).toContain('text-primary');
      fireEvent.click(button);

      // The button only ever opens the dialog — it never downloads directly.
      expect(await screen.findByTestId('remaining-invoice-dialog')).not.toBeNull();
    });
  });

  // The การเงิน tab is ONE "การชำระเงิน" panel (balance + paid bar, the three document steps, the
  // timeline); there is no สถานะการเงิน, ใบวางบิล step or วางบิล control.
  describe('การเงิน tab — one การชำระเงิน panel', () => {
    const readyDeal = () => api.tickets.get.mockResolvedValue({
      ticket: buildTicket({
        summary: { status: 'quotation_issued', paymentStatus: 'DEPOSIT_PAID', fulfillmentStatus: 'GOODS_RECEIVED', createdById: 1 },
      }),
    });

    it.each([['the owning rep', salesOwnerUser], ['account', accountUser]])('%s: a single การชำระเงิน panel, no สถานะการเงิน and no วางบิล anywhere on the tab', async (_label, user) => {
      readyDeal();
      renderTicketDetailPage(user);
      await openTab(/^การเงิน/);

      await screen.findByRole('heading', { level: 2, name: 'การชำระเงิน' });
      await waitFor(() => expect(api.storedRemainingInvoices.listForTicket).toHaveBeenCalled());
      const panel = screen.getByRole('tabpanel');
      expect(within(panel).getAllByRole('heading', { level: 2 })).toHaveLength(1);
      expect(within(panel).getByTestId('payment-step-deposit')).toBeTruthy();
      expect(within(panel).getByTestId('payment-step-remaining')).toBeTruthy();
      expect(within(panel).getByTestId('payment-step-final')).toBeTruthy();
      expect(panel.textContent).not.toMatch(/สถานะการเงิน|วางบิล|ครบกำหนดชำระ|ใบวางบิล/);
    });

    it('the owning rep on a ready deal: สร้างใบแจ้งหนี้ส่วนที่เหลือ in the step opens the RemainingInvoiceDialog', async () => {
      readyDeal();
      renderTicketDetailPage(salesOwnerUser);
      await openTab(/^การเงิน/);

      const step = within(await screen.findByTestId('payment-step-remaining'));
      fireEvent.click(await step.findByRole('button', { name: 'สร้างใบแจ้งหนี้ส่วนที่เหลือ' }));
      expect(await screen.findByTestId('remaining-invoice-dialog')).not.toBeNull();
    });

    it('with an ISSUED remaining invoice the step offers PDF and จัดการเอกสาร (opens the same dialog)', async () => {
      readyDeal();
      api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [{ id: 901, ticketId: 701, status: 'ISSUED', docNumber: 'GLRI69001' }] });
      api.tickets.remainingInvoiceOptions.mockResolvedValue({ options: { docNumber: 'GLRI69001', noteTemplates: [], referenceOptions: [], depositReferenceOptions: [], quotationOptions: [] } });
      renderTicketDetailPage(salesOwnerUser);
      await openTab(/^การเงิน/);

      const step = within(await screen.findByTestId('payment-step-remaining'));
      expect(await step.findByRole('button', { name: 'PDF' })).toBeTruthy();
      fireEvent.click(step.getByRole('button', { name: 'จัดการเอกสาร' }));
      expect(await screen.findByTestId('remaining-invoice-dialog')).not.toBeNull();
    });

    it('account on a ready deal gets no remaining-invoice control (can.downloadRemainingInvoice is sales only)', async () => {
      readyDeal();
      renderTicketDetailPage(accountUser);
      await openTab(/^การเงิน/);

      const step = within(await screen.findByTestId('payment-step-remaining'));
      await waitFor(() => expect(api.storedRemainingInvoices.listForTicket).toHaveBeenCalled());
      expect(step.queryByRole('button', { name: /สร้างใบแจ้งหนี้ส่วนที่เหลือ|จัดการเอกสาร/ })).toBeNull();
    });
  });

  // ── Remaining-invoice download gate (stock-delivered deals) ─────────────────────────────────
  //
  // The gate is remainingInvoiceReadiness.js's rule, not `fulfillmentStatus === 'GOODS_RECEIVED'`
  // (only the import axis writes it, so a from-stock deal never got the button and an import deal
  // lost it at its first recorded delivery). UI readiness only: DepositNoticeService
  // #getRemainingInvoiceXlsx gates on ticket status alone.
  describe('remaining-invoice download gate', () => {
    const INVOICE_BUTTON = 'ดาวน์โหลดใบแจ้งหนี้ส่วนที่เหลือ';
    afterEach(() => { api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [] }); });

    it.each(['FROM_STOCK', 'PARTIALLY_DELIVERED', 'FULLY_DELIVERED', 'GOODS_RECEIVED'])(
      'offers the download to the sales owner once fulfilment is %s',
      async (fulfillmentStatus) => {
        api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: [{ id: 901, ticketId: 701, status: 'ISSUED', docNumber: 'GLRI69001' }] });
        api.tickets.get.mockResolvedValueOnce({
          ticket: buildTicket({ summary: { status: 'quotation_issued', fulfillmentStatus, createdById: 1 } }),
        });
        renderTicketDetailPage(salesOwnerUser);
        await waitFor(() => expect(api.storedRemainingInvoices.listForTicket).toHaveBeenCalled());
        expect(await screen.findByRole('button', { name: INVOICE_BUTTON })).not.toBeNull();
      },
    );

    // Wrong-way-round: widening must not make the button unconditional.
    it.each([null, 'IR_ISSUED', 'IR_SENT', 'SHIPPING'])(
      'withholds the download while fulfilment is %s',
      async (fulfillmentStatus) => {
        api.tickets.get.mockResolvedValueOnce({
          ticket: buildTicket({ summary: { status: 'quotation_issued', fulfillmentStatus, createdById: 1 } }),
        });
        renderTicketDetailPage(salesOwnerUser);
        expect(await screen.findByRole('heading', { level: 1 })).not.toBeNull();
        expect(screen.queryByRole('button', { name: INVOICE_BUTTON })).toBeNull();
      },
    );

    it('withholds the download from a non-sales role even on a delivered stock deal', async () => {
      api.tickets.get.mockResolvedValueOnce({
        ticket: buildTicket({ summary: { status: 'quotation_issued', fulfillmentStatus: 'FULLY_DELIVERED', createdById: 1 } }),
      });
      renderTicketDetailPage(accountUser);
      expect(await screen.findByRole('heading', { level: 1 })).not.toBeNull();
      expect(screen.queryByRole('button', { name: INVOICE_BUTTON })).toBeNull();
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
    function depositStageDeal({ prs = [acceptedPr], notices = [], availableActions = [], summary = {} } = {}) {
      const state = {
        lifecycle: 'ACTIVE', salesStage: 'ORDER_RECEIVED', paymentStatus: 'CUSTOMER_CONFIRMED', fulfillmentStatus: null, status: 'quotation_issued',
      };
      api.tickets.get.mockResolvedValue({ ticket: buildTicket({ summary: { ...state, createdById: 1, ...summary } }) });
      api.tickets.actions.mockResolvedValue({ currentState: state, availableActions });
      api.pricingRequests.listForTicket.mockResolvedValue({ items: prs });
      api.dealQuotations.listForTicket.mockResolvedValue({ items: [] });
      api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: notices });
      api.pricingRequests.createDepositNoticeFromQuotation.mockResolvedValue({ depositNotice: { id: 801, status: 'DRAFT' } });
      api.depositNotices.createDraft.mockResolvedValue({ depositNotice: { id: 801, status: 'DRAFT' } });
    }

    // A ขั้น 13 deal: goods delivered, deposit paid.
    function invoiceStageDeal({ invoices = [] } = {}) {
      const state = {
        lifecycle: 'ACTIVE', salesStage: 'DELIVERY_SCHEDULING', paymentStatus: 'DEPOSIT_PAID', fulfillmentStatus: 'FULLY_DELIVERED', status: 'quotation_issued',
      };
      api.tickets.get.mockResolvedValue({ ticket: buildTicket({ summary: { ...state, createdById: 1 } }) });
      api.tickets.actions.mockResolvedValue({ currentState: state, availableActions: [] });
      api.pricingRequests.listForTicket.mockResolvedValue({ items: [acceptedPr] });
      api.dealQuotations.listForTicket.mockResolvedValue({ items: [] });
      api.storedRemainingInvoices.listForTicket.mockResolvedValue({ remainingInvoices: invoices });
      api.tickets.remainingInvoiceOptions.mockResolvedValue({
        options: {
          docNumber: 'GLRI69001', defaultIssueDate: '2026-09-01', defaultReference: null,
          referenceOptions: [], defaultDepositReference: null, depositReferenceOptions: [],
          noteTemplates: [], itemCount: 1, maxItems: 22, itemsTotal: 100, depositAmount: 0,
          netAmount: 100, vatAmount: 7, totalPayable: 107,
          quotationOptions: [], defaultQuotationId: null, blockingReason: null,
        },
      });
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

    it('the legacy ลูกค้ายืนยัน re-confirm (CONFIRM_CUSTOMER advertised) does not take the slot at CUSTOMER_CONFIRMED', async () => {
      depositStageDeal({ availableActions: [{ action: 'CONFIRM_CUSTOMER', kind: 'operational', label: 'ลูกค้ายืนยัน' }] });
      renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser);
      // The advertised action must have loaded, or the absence below proves nothing.
      await waitFor(() => expect(api.tickets.actions).toHaveBeenCalled());
      await stickyInputsSettled();
      await waitFor(() => expect(primaryAction()).toBe('create_deposit_notice'));
      expect(screen.queryByTestId('ticket-detail-confirm-customer')).toBeNull();
    });

    it('a direct-quotation deal (no pricing requests): the click creates the draft by ticket (depositNotices.createDraft), not the PR endpoint, then opens /deposit', async () => {
      // Real post-confirm flow-A shape: the direct quotation stays APPROVED + live, and the rep may revise it.
      depositStageDeal({ prs: [], summary: { liveDirectQuotation: { id: 77, number: 'QT-2026-0077-1', docStatus: 'APPROVED', recipientType: 'DESIGNER' } } });
      api.dealQuotations.listForTicket.mockResolvedValue({
        items: [{ id: 77, number: 'QT-2026-0077-1', origin: 'DEAL_DIRECT', docStatus: 'APPROVED', recipientType: 'DESIGNER', salesRepId: 1, revisionNo: 1, parentQuotationId: null, pricingRequestId: null }],
      });
      renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser);
      await waitFor(() => expect(primaryAction()).toBe('create_deposit_notice'));

      fireEvent.click(primary());

      await waitFor(() => expect(api.depositNotices.createDraft).toHaveBeenCalledWith(701, expect.any(Object)));
      await waitFor(() => expect(location()).toBe('/tickets/701/deposit'));
      expect(api.pricingRequests.createDepositNoticeFromQuotation).not.toHaveBeenCalled();
    });

    it('a DRAFT notice already exists: the button reads ออกใบแจ้งยอดเงินรับมัดจำ and only opens /deposit — nothing is created', async () => {
      depositStageDeal({ notices: [{ id: 801, status: 'DRAFT', ticketId: 701, docNumber: null }] });
      renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser);
      await waitFor(() => expect(primaryAction()).toBe('create_deposit_notice'));
      await waitFor(() => expect(primary().textContent).toContain('ออกใบแจ้งยอดเงินรับมัดจำ'));

      fireEvent.click(primary());

      await waitFor(() => expect(location()).toBe('/tickets/701/deposit'));
      expect(api.pricingRequests.createDepositNoticeFromQuotation).not.toHaveBeenCalled();
      expect(api.depositNotices.createDraft).not.toHaveBeenCalled();
    });

    it('a DRAFT made elsewhere after the page loaded (stale cache): the click opens it — nothing is created', async () => {
      depositStageDeal();
      renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser);
      await waitFor(() => expect(primaryAction()).toBe('create_deposit_notice'));
      await waitFor(() => expect(api.depositNotices.listByTicket).toHaveBeenCalled());
      api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [{ id: 801, status: 'DRAFT', ticketId: 701, docNumber: null }] });

      fireEvent.click(primary());

      await waitFor(() => expect(location()).toBe('/tickets/701/deposit'));
      expect(api.pricingRequests.createDepositNoticeFromQuotation).not.toHaveBeenCalled();
      expect(api.depositNotices.createDraft).not.toHaveBeenCalled();
    });

    it('a sales rep who does NOT own the deal gets no create_deposit_notice main button (the backend refuses non-owners)', async () => {
      depositStageDeal();
      renderTicketDetailPageAtRoute(['/tickets/701'], nonOwnerSales);
      await stickyInputsSettled();
      await screen.findByRole('heading', { level: 1 });
      expect(primaryAction()).not.toBe('create_deposit_notice');
    });

    it('การเงิน tab: the main button is the only สร้างใบแจ้งยอดเงินรับมัดจำ button (the deposit step shows a hint), and from there it still creates the notice and opens /deposit', async () => {
      depositStageDeal();
      renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser);
      await waitFor(() => expect(primaryAction()).toBe('create_deposit_notice'));
      await openTab(/การเงิน/);
      await screen.findByRole('heading', { level: 2, name: 'การชำระเงิน' });
      await stickyInputsSettled();
      expect(screen.queryByRole('heading', { level: 2, name: 'มัดจำ' })).toBeNull();
      expect(screen.queryByTestId('deal-deposit-create-notice')).toBeNull();
      // The deposit step is there but carries a hint instead of a second create button.
      const step = within(screen.getByTestId('payment-step-deposit'));
      expect(step.getByText(/กดปุ่ม "สร้างใบแจ้งยอดเงินรับมัดจำ" ด้านบน/)).toBeTruthy();
      expect(step.queryByRole('button', { name: /สร้างใบแจ้งยอดเงินรับมัดจำ/ })).toBeNull();
      expect(screen.getAllByRole('button', { name: /สร้างใบแจ้งยอดเงินรับมัดจำ/ })).toHaveLength(1);

      fireEvent.click(primary());

      await waitFor(() => expect(api.pricingRequests.createDepositNoticeFromQuotation).toHaveBeenCalledWith(501, {}));
      await waitFor(() => expect(location()).toBe('/tickets/701/deposit'));
    });

    it('เอกสาร tab: after the order is confirmed the quotation panel points at the main button, not at the "มัดจำ" section below', async () => {
      depositStageDeal();
      renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser);
      await openTab(/เอกสาร/);
      const panel = within(await screen.findByTestId('deal-quotation-panel'));
      expect(await panel.findByText(/ยืนยันคำสั่งซื้อแล้วเมื่อ/)).not.toBeNull();
      expect(panel.queryByText(/ส่วน "มัดจำ"/)).toBeNull();
      expect(panel.getByText(/สร้างใบแจ้งยอดเงินรับมัดจำ/)).not.toBeNull();
    });

    it('เอกสาร tab: no notice yet and the main button does not offer it (ceo): the quotation panel points at the การเงิน tab, not the (empty) register', async () => {
      depositStageDeal();
      renderTicketDetailPageAtRoute(['/tickets/701'], ceoUser);
      await stickyInputsSettled();
      await openTab(/เอกสาร/);
      const panel = within(await screen.findByTestId('deal-quotation-panel'));
      expect(await panel.findByText(/ยืนยันคำสั่งซื้อแล้วเมื่อ/)).not.toBeNull();
      expect(panel.getByText(/แท็บ "การเงิน"/)).not.toBeNull();
      expect(panel.queryByText(/ทะเบียนเอกสาร/)).toBeNull();
    });

    it('เอกสาร tab: at a later stage (notice already out) the main button no longer offers it, so the quotation panel keeps the neutral copy pointing at the document register of this tab', async () => {
      depositStageDeal({ summary: { paymentStatus: 'DEPOSIT_NOTICE_ISSUED' }, notices: [{ id: 801, status: 'ISSUED', version: 1, docNumber: 'GLRD6900012' }] });
      renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser);
      await stickyInputsSettled();
      await openTab(/เอกสาร/);
      const panel = within(await screen.findByTestId('deal-quotation-panel'));
      expect(await panel.findByText(/ยืนยันคำสั่งซื้อแล้วเมื่อ/)).not.toBeNull();
      expect(panel.getByText(/ใบแจ้งยอดเงินรับมัดจำดูได้ในทะเบียนเอกสารของแท็บนี้/)).not.toBeNull();
      expect(panel.queryByText(/แท็บ "การเงิน"/)).toBeNull();
      expect(panel.queryByText(/ด้านบน/)).toBeNull();
    });

    it('ขั้น 13, ready deal, no remaining invoice: the main button is สร้างใบแจ้งหนี้ and opens the RemainingInvoiceDialog; the stage link reads สร้างใบแจ้งหนี้ส่วนที่เหลือ', async () => {
      invoiceStageDeal();
      renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser);
      await waitFor(() => expect(primaryAction()).toBe('create_remaining_invoice'));
      expect(primary().textContent).toContain('สร้างใบแจ้งหนี้');
      expect(screen.getByRole('button', { name: 'สร้างใบแจ้งหนี้ส่วนที่เหลือ' })).not.toBeNull();
      expect(screen.queryByRole('button', { name: 'ดาวน์โหลดใบแจ้งหนี้ส่วนที่เหลือ' })).toBeNull();

      fireEvent.click(primary());

      expect(await screen.findByTestId('remaining-invoice-dialog')).not.toBeNull();
    });

    it('ขั้น 13 with an ISSUED remaining invoice: no create_remaining_invoice main button, and the link reads ดาวน์โหลดใบแจ้งหนี้ส่วนที่เหลือ', async () => {
      invoiceStageDeal({ invoices: [{ id: 901, ticketId: 701, status: 'ISSUED', docNumber: 'GLRI69001' }] });
      renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser);
      expect(await screen.findByRole('button', { name: 'ดาวน์โหลดใบแจ้งหนี้ส่วนที่เหลือ' })).not.toBeNull();
      await waitFor(() => expect(api.storedRemainingInvoices.listForTicket).toHaveBeenCalled());
      await stickyInputsSettled();
      expect(primaryAction()).not.toBe('create_remaining_invoice');
      expect(screen.queryByRole('button', { name: 'สร้างใบแจ้งหนี้ส่วนที่เหลือ' })).toBeNull();
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
    const location = () => screen.getByTestId('location-probe').textContent;
    // jsdom has no scrollIntoView: record which element the page asked to scroll to.
    let scrolledIds = [];
    beforeEach(() => {
      scrolledIds = [];
      Element.prototype.scrollIntoView = function scrollIntoView() { scrolledIds.push(this.id); };
    });
    const scrolledToIrSection = () => scrolledIds.includes('deal-fulfilment-stored-ir');

    function irStageDeal({ rows = [] } = {}) {
      const state = {
        lifecycle: 'ACTIVE', salesStage: 'DEPOSIT_RECEIVED', paymentStatus: 'DEPOSIT_PAID', fulfillmentStatus: null, status: 'quotation_issued',
      };
      api.tickets.get.mockResolvedValue({ ticket: buildTicket({ summary: { ...state, createdById: 1 } }) });
      api.tickets.actions.mockResolvedValue({ currentState: state, availableActions: [] });
      api.pricingRequests.listForTicket.mockResolvedValue({ items: [acceptedPr] });
      api.dealQuotations.listForTicket.mockResolvedValue({ items: [] });
      api.storedImportRequests.listForTicket.mockResolvedValue({ importRequests: rows });
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
      // The confirm re-checks the factories first; it enables once that answers.
      const confirm = within(dialog).getByRole('button', { name: 'ออกใบขอซื้อ (IR)' });
      await waitFor(() => expect(confirm.disabled).toBe(false));
      fireEvent.click(confirm);

      await waitFor(() => expect(api.storedImportRequests.createDrafts).toHaveBeenCalledTimes(1));
      expect(api.storedImportRequests.createDrafts).toHaveBeenCalledWith(701, undefined);
      await waitFor(() => expect(location()).toContain('tab=fulfilment'));
      await waitFor(() => expect(scrolledToIrSection()).toBe(true));
    });

    it('a DRAFT IR is listed: the button reads ออกเลขใบขอซื้อ (IR) and only switches tab — createDrafts is not called', async () => {
      irStageDeal({ rows: [{ id: 31, status: 'DRAFT', ticketId: 701, docNumber: null, factoryName: 'Factory A', version: 1, items: [] }] });
      renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser);
      await waitFor(() => expect(primaryAction()).toBe('create_import_request'));
      await waitFor(() => expect(primary().textContent).toContain('ออกเลขใบขอซื้อ (IR)'));

      fireEvent.click(primary());

      await waitFor(() => expect(location()).toContain('tab=fulfilment'));
      expect(api.storedImportRequests.createDrafts).not.toHaveBeenCalled();
      await waitFor(() => expect(scrolledToIrSection()).toBe(true));
      // createIrDrafts() opens the issue confirm, so "not called" alone doesn't pin the branch.
      expect(screen.queryByRole('dialog', { name: 'ยืนยันการออกใบขอซื้อ (IR)' })).toBeNull();
    });

    it('a sales rep who does NOT own the deal gets no create_import_request main button', async () => {
      irStageDeal();
      renderTicketDetailPageAtRoute(['/tickets/701'], nonOwnerSales);
      await stickyInputsSettled();
      await screen.findByRole('heading', { level: 1 });
      expect(primaryAction()).not.toBe('create_import_request');
    });

    it('the button is disabled until the IR list is read (a click must not create beside an unseen DRAFT)', async () => {
      irStageDeal();
      api.storedImportRequests.listForTicket.mockReturnValue(new Promise(() => {}));
      renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser);
      await waitFor(() => expect(primaryAction()).toBe('create_import_request'));
      expect(primary().disabled).toBe(true);
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

    // The deal-page "ใบเสนอราคา" panel (DealDirectQuotationPanel) and its two header buttons
    // (สร้างคำขอราคา / ใบเสนอราคาตรง + helper note) are GONE from the เอกสาร tab. Quotations stay listed
    // (with PDF/Excel) in the document register at the top; the one way to start a pricing request is
    // the sticky CTA.
    it.each([
      ['the owning rep', salesOwnerUser],
      ['a role that may not create a quotation (account)', accountUser],
      ['the CEO', ceoUser],
    ])('the เอกสาร tab has no ใบเสนอราคา panel, route buttons or helper line for %s', async (_label, user) => {
      activeDeal();
      renderTicketDetailPage(user);
      await openTab(/เอกสาร/);
      await screen.findByTestId('deal-document-register');
      expect(screen.queryByRole('heading', { name: 'ใบเสนอราคา', level: 2 })).toBeNull();
      expect(screen.queryByRole('link', { name: 'ใบเสนอราคาตรง' })).toBeNull();
      expect(document.querySelector('a[href*="/quotations/new"]')).toBeNull();
      expect(screen.queryByText(/ใบเสนอราคาตรง = กรอกราคาเอง/)).toBeNull();
      expect(screen.queryByText('สร้างได้จากหน้า ใบเสนอราคา')).toBeNull();
    });

    it('สร้างคำขอราคา exists exactly once for the owning rep — the sticky CTA, not a second copy in the documents tab', async () => {
      activeDeal({ salesStage: 'SPEC_APPROVED' });
      renderTicketDetailPage(salesOwnerUser);
      await waitFor(() => expect(screen.getByTestId('ticket-primary-action').getAttribute('data-action')).toBe('create_pcr'));
      await openTab(/เอกสาร/);
      await screen.findByTestId('deal-document-register');
      expect(screen.getAllByRole('button', { name: 'สร้างคำขอราคา' })).toHaveLength(1);
    });

    it('the document register still lists the deal\'s direct quotations (the panel\'s listing is not lost)', async () => {
      activeDeal();
      api.dealQuotations.listForTicket.mockResolvedValue({
        items: [{ id: 77, number: 'QT-2026-0077-1', docStatus: 'APPROVED', recipientType: 'DESIGNER', salesRepId: 1, revisionNo: 1, grandTotal: 1070 }],
      });
      renderTicketDetailPage(salesOwnerUser);
      await openTab(/เอกสาร/);
      expect(await screen.findByTestId('document-version-direct-77')).not.toBeNull();
    });

    it('DRAFT: the sticky primary is "ส่งขออนุมัติใบเสนอราคา" and it opens the quotation', async () => {
      activeDeal({ liveDirectQuotation: DRAFT_LIVE });
      renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser);
      await waitFor(() => expect(screen.getByTestId('ticket-primary-action').getAttribute('data-action')).toBe('submit_direct_quotation'));
      const cta = screen.getByTestId('ticket-primary-action');
      expect(cta.textContent).toContain('ส่งขออนุมัติใบเสนอราคา');
      // Never "สร้างคำขอราคา" as the CTA while a quotation is being priced by hand.
      expect(cta.textContent).not.toContain('สร้างคำขอราคา');
      fireEvent.click(cta);
      await waitFor(() => expect(screen.getByTestId('location-probe').textContent).toBe('/quotations/77'));
    });

    it('PENDING_APPROVAL, as the rep: no button — the header banner says who it waits on', async () => {
      activeDeal({ liveDirectQuotation: { ...DRAFT_LIVE, docStatus: 'PENDING_APPROVAL' } });
      renderTicketDetailPage(salesOwnerUser);
      expect((await screen.findAllByText('รอ ผจก.ขาย/CEO อนุมัติใบเสนอราคา QT-2026-0077-1')).length).toBeGreaterThan(0);
      expect(screen.queryByTestId('ticket-primary-action')).toBeNull();
    });

    it('PENDING_APPROVAL, as the CEO: their own "อนุมัติใบเสนอราคา" action to the quotation', async () => {
      activeDeal({ liveDirectQuotation: { ...DRAFT_LIVE, docStatus: 'PENDING_APPROVAL' } });
      renderTicketDetailPageAtRoute(['/tickets/701'], ceoUser);
      await waitFor(() => expect(screen.getByTestId('ticket-primary-action').getAttribute('data-action')).toBe('approve_direct_quotation'));
      fireEvent.click(screen.getByTestId('ticket-primary-action'));
      await waitFor(() => expect(screen.getByTestId('location-probe').textContent).toBe('/quotations/77'));
    });

    it('APPROVED: "ยืนยันคำสั่งซื้อ" confirms first (same copy as the editor), then calls the direct-quotation confirm endpoint', async () => {
      activeDeal({ liveDirectQuotation: { ...DRAFT_LIVE, docStatus: 'APPROVED', recipientType: 'BUYER' } });
      // The dialog reads the % off the deal's quotation list (the live summary carries none).
      api.dealQuotations.listForTicket.mockResolvedValue({ items: [{ ...DRAFT_LIVE, docStatus: 'APPROVED', depositPercent: 0 }] });
      api.dealQuotations.promoteToDeal.mockResolvedValue({ result: { ticketId: 701 } });
      const showToast = vi.fn();
      renderTicketDetailPage(salesOwnerUser, showToast);
      await waitFor(() => expect(screen.getByTestId('ticket-primary-action').getAttribute('data-action')).toBe('confirm_order_direct'));
      // ถัดไป names ขั้น 10 (where this button leads), not the next stage on the route (ขั้น 3).
      const nextLine = screen.getByTestId('deal-stage-next').textContent;
      expect(nextLine).toContain('10.');
      expect(nextLine).not.toContain('3.');
      fireEvent.click(screen.getByTestId('ticket-primary-action'));
      expect(api.dealQuotations.promoteToDeal).not.toHaveBeenCalled();
      const dialog = within(screen.getByRole('dialog'));
      expect(dialog.getByText(/ได้รับคำสั่งซื้อ/)).not.toBeNull();
      expect(dialog.getByText(/QT-2026-0077-1/)).not.toBeNull();
      expect(await dialog.findByText(/ไม่เก็บมัดจำ/)).not.toBeNull(); // copy follows the quotation's 0%
      expect(screen.getByRole('dialog').textContent).not.toContain('อัตโนมัติ'); // no other open direct quotation: copy unchanged
      fireEvent.click(dialog.getByRole('button', { name: 'ยืนยันคำสั่งซื้อ' }));
      await waitFor(() => expect(api.dealQuotations.promoteToDeal).toHaveBeenCalledWith(77));
      await waitFor(() => expect(showToast).toHaveBeenCalledWith('success', 'ยืนยันคำสั่งซื้อแล้ว'));
    });

    // F9: until the deal's quotation row is read, the deposit wording is unknown (it may be 0%): no confirm yet.
    it('while the deal\'s quotation list is unread, the confirm dialog cannot be confirmed', async () => {
      activeDeal({ liveDirectQuotation: { ...DRAFT_LIVE, docStatus: 'APPROVED', recipientType: 'BUYER' } });
      api.dealQuotations.listForTicket.mockReturnValue(new Promise(() => {}));
      renderTicketDetailPage(salesOwnerUser);
      await waitFor(() => expect(screen.getByTestId('ticket-primary-action').getAttribute('data-action')).toBe('confirm_order_direct'));
      fireEvent.click(screen.getByTestId('ticket-primary-action'));
      // busy swaps the label to กำลังดำเนินการ
      const confirmBtn = within(screen.getByRole('dialog')).getByRole('button', { name: /ยืนยันคำสั่งซื้อ|กำลังดำเนินการ/ });
      expect(confirmBtn.disabled).toBe(true);
      fireEvent.click(confirmBtn);
      expect(api.dealQuotations.promoteToDeal).not.toHaveBeenCalled();
    });

    it('the list loading WITHOUT the quotation\'s row keeps the confirm disabled', async () => {
      activeDeal({ liveDirectQuotation: { ...DRAFT_LIVE, docStatus: 'APPROVED', recipientType: 'BUYER' } });
      api.dealQuotations.listForTicket.mockResolvedValue({ items: [] });
      renderTicketDetailPage(salesOwnerUser);
      await waitFor(() => expect(screen.getByTestId('ticket-primary-action').getAttribute('data-action')).toBe('confirm_order_direct'));
      await waitFor(() => expect(api.dealQuotations.listForTicket).toHaveBeenCalled());
      fireEvent.click(screen.getByTestId('ticket-primary-action'));
      const confirmBtn = within(screen.getByRole('dialog')).getByRole('button', { name: /ยืนยันคำสั่งซื้อ|กำลังดำเนินการ/ });
      expect(confirmBtn.disabled).toBe(true);
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
      api.dealQuotations.promoteToDeal.mockRejectedValue(Object.assign(new Error('ดีลไม่ได้อยู่ในสถานะ ACTIVE'), { status: 409 }));
      const showToast = vi.fn();
      renderTicketDetailPage(salesOwnerUser, showToast);
      await waitFor(() => expect(screen.getByTestId('ticket-primary-action').getAttribute('data-action')).toBe('confirm_order_direct'));
      fireEvent.click(screen.getByTestId('ticket-primary-action'));
      fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'ยืนยันคำสั่งซื้อ' }));
      await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', 'ดีลไม่ได้อยู่ในสถานะ ACTIVE'));
    });
  });

  // The sticky primary CTA "แก้ใบเสนอราคา" (SALES_ACTION.REVISE_QUOTATION). The page resolves the
  // revisable quotation from api.dealQuotations.listForTicket through
  // quotationMeta.canReviseDealQuotation (the real predicate) and hands it to the cascade. Clicking it
  // mints a revision (api.dealQuotations.createRevision) and opens it; an open revision draft is
  // opened instead ("ไปที่ฉบับแก้ไข"), never a second one.
  describe('PR A — แก้ใบเสนอราคา (sticky primary past draft; overflow secondary at owner/buyer stages)', () => {
    const salesManagerUser = { id: 11, employeeId: 11, name: 'ผจก.ขาย', role: 'sales_manager' };

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
    // or owner's issued quotation leads with the route choice instead (see the "once a designer/owner
    // quotation is sent" describe), so it cannot stand in here.
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
    function directApprovedDeal({ rows } = {}) {
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
      api.dealQuotations.listForTicket.mockResolvedValue({ items: rows ?? [approved] });
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

    // At the owner / buyer quote stages the client may want a revision OR be happy with the original,
    // so revise is an ADDITIONAL action in the header "⋯" overflow menu (the page's existing secondary
    // slot) and บันทึกผลใบเสนอราคา stays the sticky primary.
    async function reviseMenuItem() {
      const trigger = await screen.findByRole('button', { name: 'การดำเนินการเพิ่มเติม' }).catch(() => null);
      if (!trigger) return null;
      fireEvent.click(trigger);
      return screen.queryByTestId('deal-quotation-revise');
    }

    // The server refuses a revise on a dead request's quotation (409), so the page hides it.
    it.each(['SUPERSEDED', 'CANCELLED'])('PR-origin: an ISSUED quotation whose request is %s offers no แก้ใบเสนอราคา', async (deadStatus) => {
      prOriginDeal({ salesStage: 'QUOTE_BUYER' });
      api.pricingRequests.listForTicket.mockResolvedValue({ items: [{ ...issuedPr, status: deadStatus }] });
      renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser);
      await settle();
      expect(await reviseMenuItem()).toBeNull();
      expect(primaryAction()).not.toBe('revise_quotation');
    });

    it.each(['QUOTE_OWNER', 'QUOTE_BUYER'])(
      'PR-origin: an ISSUED quotation at %s keeps บันทึกผลใบเสนอราคา as primary AND offers แก้ใบเสนอราคา in the overflow menu; click revises it',
      async (salesStage) => {
        prOriginDeal({ salesStage });
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
      },
    );

    it('PR-origin: a REVISION_REQUESTED quotation at the owner stage is revisable too', async () => {
      prOriginDeal({ rows: [quotationRow({ docStatus: 'REVISION_REQUESTED' })] });
      renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser);
      await settle();
      expect(await reviseMenuItem()).not.toBeNull();
    });

    it('wrong-way-round: at the DESIGNER stage there is no revise item and the primary is unchanged', async () => {
      prOriginDeal({ salesStage: 'QUOTE_DESIGN_SIDE' });
      renderTicketDetailPage(salesOwnerUser);
      await settle();
      await waitFor(() => expect(primaryAction()).toBe('record_quotation_outcome'));
      expect(await reviseMenuItem()).toBeNull();
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

    it.each([
      ['the CEO', ceoUser],
      ['sales_manager', salesManagerUser],
      ['account', accountUser],
    ])('negative: %s never gets the revise action, even on a deal whose quotation is revisable', async (_label, user) => {
      // The predicate admits a sales_manager for a DEAL_DIRECT APPROVED quotation, so this is the
      // fixture on which that row reaches the page's own sales-only gate (secondaryWorkActions).
      prOriginDeal({ rows: [quotationRow({ origin: 'DEAL_DIRECT', docStatus: 'APPROVED' })] });
      renderTicketDetailPage(user);
      await settle();
      expect(primaryAction()).not.toBe('revise_quotation');
      expect(await reviseMenuItem()).toBeNull();
    });

    it('negative: no approved/issued quotation (only a DRAFT live one) -> no revise action, the submit CTA stays', async () => {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({ summary: { lifecycle: 'ACTIVE', salesStage: 'QUOTE_OWNER', status: 'draft', createdById: 1, liveDirectQuotation: { id: 77, number: 'QT-2026-0077-1', docStatus: 'DRAFT', recipientType: 'DESIGNER' } } }),
      });
      api.tickets.actions.mockResolvedValue({
        currentState: { lifecycle: 'ACTIVE', salesStage: 'QUOTE_OWNER', paymentStatus: null, fulfillmentStatus: null, status: 'draft' },
        availableActions: [],
      });
      api.dealQuotations.listForTicket.mockResolvedValue({ items: [quotationRow({ id: 77, origin: 'DEAL_DIRECT', docStatus: 'DRAFT' })] });
      renderTicketDetailPage(salesOwnerUser);
      await waitFor(() => expect(primaryAction()).toBe('submit_direct_quotation'));
      await settle();
      expect(await reviseMenuItem()).toBeNull();
    });

    it('cascade order unchanged: a still-draft deal with an APPROVED BUYER direct quotation keeps ยืนยันคำสั่งซื้อ as its primary', async () => {
      directApprovedDeal();
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({
          summary: {
            lifecycle: 'ACTIVE', salesStage: 'PRESENTATION', status: 'draft', createdById: 1,
            liveDirectQuotation: { id: 77, number: 'QT-2026-0077-1', docStatus: 'APPROVED', recipientType: 'BUYER' },
          },
        }),
      });
      renderTicketDetailPage(salesOwnerUser);
      await waitFor(() => expect(primaryAction()).toBe('confirm_order_direct'));
    });

    it('an open revision draft exists -> menu item reads "ไปที่ฉบับแก้ไข", opens that draft, and createRevision is NOT called', async () => {
      prOriginDeal({
        rows: [quotationRow(), quotationRow({ id: 81, number: 'QT-2026-0080-2', docStatus: 'DRAFT', revisionNo: 2, parentQuotationId: 80 })],
      });
      renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser);
      await settle();
      const item = await reviseMenuItem();
      expect(item).not.toBeNull();
      expect(item.textContent).toContain('ไปที่ฉบับแก้ไข');
      expect(item.textContent).not.toContain('แก้ใบเสนอราคา');
      fireEvent.click(item);
      await waitFor(() => expect(screen.getByTestId('location-probe').textContent).toBe('/quotations/81'));
      expect(api.dealQuotations.createRevision).not.toHaveBeenCalled();
    });

    // Approving a PR-origin revision issues it in the same step (DealQuotationService
    // #approveAndIssuePricingRequestOrigin), so the new head is ISSUED, never APPROVED.
    it('repeatable: once the revision is approved and issued the action is offered again, against the NEW head', async () => {
      prOriginDeal({
        rows: [
          quotationRow({ docStatus: 'SUPERSEDED' }),
          quotationRow({ id: 88, number: 'QT-2026-0080-2', docStatus: 'ISSUED', revisionNo: 2, parentQuotationId: 80 }),
        ],
      });
      api.dealQuotations.createRevision.mockResolvedValue({ quotation: quotationRow({ id: 95, docStatus: 'DRAFT', parentQuotationId: 88 }) });
      renderTicketDetailPageAtRoute(['/tickets/701'], salesOwnerUser);
      await settle();
      fireEvent.click(await reviseMenuItem());
      await waitFor(() => expect(api.dealQuotations.createRevision).toHaveBeenCalledWith(88));
      await waitFor(() => expect(screen.getByTestId('location-probe').textContent).toBe('/quotations/95'));
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
  });

  // A deal follows a ROUTE fixed by its entry channel. The overflow menu's "เลื่อนไป" target steps over
  // off-route stages via nextOnRoute, reading the server's per-stage `onRoute` verdict; a route-blind
  // nextStageIn named S4 on an owner-direct deal at S3, a stage the backend refuses (409, DealRoute).
  //
  // Two server shapes are pinned:
  //   * FAITHFUL: TicketService.addStageActions advertises ADVANCE_STAGE only for stages whose
  //     decision is `allowed`, and DealRoute makes an off-route stage `allowed:false`. Route-blind,
  //     the target (S4) is not advertised at all, so the item silently DISAPPEARS and the rep can't
  //     advance from S3.
  //   * LAX: a server/mock that advertises ADVANCE_STAGE for the off-route stage too. Route-blind,
  //     the item appears and names the refused stage ("invites a click it is about to reject").
  describe('overflow เลื่อนไป follows the deal route', () => {
    const OFF_ROUTE = {
      DESIGNER_LED: [],
      OWNER_DIRECT: ['QUOTE_DESIGN_SIDE'],
      BUYER_DIRECT: ['QUOTE_DESIGN_SIDE', 'QUOTE_OWNER', 'OWNER_SIGNOFF', 'AWAITING_BUYER'],
    };
    const ROUTE_NAME = {
      QUOTE_DESIGN_SIDE: 'เสนอราคาผู้ออกแบบ',
      QUOTE_OWNER: 'เสนอราคาเจ้าของโครงการ',
      QUOTE_BUYER: 'เสนอราคาผู้ซื้อ/ผู้รับเหมา',
    };

    function routeActions({ channel, salesStage, lax = false }) {
      const off = new Set(OFF_ROUTE[channel] ?? []);
      const stageDecisions = DEAL_STAGE_CATALOG.stages.map((stage) => {
        const onRoute = !off.has(stage.code);
        return {
          stage: stage.code,
          no: stage.no,
          allowed: stage.code !== salesStage && (onRoute || lax),
          requiresReason: false,
          blockedReason: onRoute || lax ? null : 'ขั้นตอนนี้ไม่อยู่ในเส้นทางของดีลนี้',
          onRoute,
        };
      });
      return {
        currentState: {
          lifecycle: 'ACTIVE', salesStage, paymentStatus: null, fulfillmentStatus: null, status: 'price_proposed',
        },
        availableActions: [
          ...stageDecisions.filter((d) => d.allowed).map((d) => ({ action: 'ADVANCE_STAGE', targetStage: d.stage })),
          { action: 'UPDATE_STAGE' },
        ],
        stageDecisions,
      };
    }

    async function openMenu({ channel, salesStage, lax }) {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({ summary: { salesStage, entryChannel: channel, nextFollowUpAt: '2026-07-15' } }),
      });
      api.tickets.actions.mockResolvedValue(routeActions({ channel, salesStage, lax }));
      api.tickets.listActivities.mockResolvedValue({
        items: [{
          id: 1, ticketId: 701, activityDate: '2026-07-03', kind: 'CALL', note: 'โทรติดตาม',
          createdById: 9, createdByName: 'CEO ทดสอบ', createdAt: '2026-07-03T09:00:00.000Z',
        }],
      });
      api.tickets.updateStage.mockResolvedValue({});
      renderTicketDetailPage(ceoUser);
      fireEvent.click(await screen.findByRole('button', { name: 'การดำเนินการเพิ่มเติม' }));
      // The panel itself, so a later `queryByTestId(...)).toBeNull()` is an absent ITEM rather
      // than a menu that never opened.
      await screen.findByRole('menu');
    }

    async function openMenuItem(args) {
      await openMenu(args);
      return screen.findByTestId('deal-stage-advance');
    }

    /**
     * Every route-SKIPPING pair in the pipeline lands on an `auto` stage:
     *
     *   OWNER_DIRECT  SPEC_APPROVED     → skips S4 → QUOTE_OWNER  (auto)
     *   BUYER_DIRECT  SPEC_APPROVED     → skips S4–S7 → QUOTE_BUYER (auto)
     *   BUYER_DIRECT  QUOTE_DESIGN_SIDE → QUOTE_BUYER (auto)
     *   BUYER_DIRECT  QUOTE_OWNER       → QUOTE_BUYER (auto)
     *   BUYER_DIRECT  OWNER_SIGNOFF     → QUOTE_BUYER (auto)
     *
     * The only stages a route may skip are the party stages S4–S8, and of those only S6/S7 are
     * non-auto, which no channel skips. So THIS surface can't demonstrate route skipping: the item
     * is absent either way, and a route-blind implementation would be indistinguishable here.
     *
     * Route-skipping coverage lives in `DealStagePanel.test.jsx`'s `ถัดไป follows the route`, which
     * reads `deal-stage-next` (renders the route's next stage regardless of `auto`). Copy that
     * mechanism if this menu ever has to prove route-awareness again.
     *
     * What this block still pins:
     *   1. the item is ABSENT when the route's next stage is a quote stage (below, all three
     *      channels, both server shapes);
     *   2. the item NAMES and SENDS `nextOnRoute`'s stage, channel-worded, on a non-auto target.
     */
    it.each([
      ['OWNER_DIRECT', false], ['OWNER_DIRECT', true],
      ['BUYER_DIRECT', false], ['BUYER_DIRECT', true],
      ['DESIGNER_LED', false],
      ['UNSPECIFIED', false],
    ])('%s at SPEC_APPROVED offers no เลื่อนไป — its route-next is a quote stage (lax server: %s)', async (channel, lax) => {
      await openMenu({ channel, salesStage: 'SPEC_APPROVED', lax });
      expect(screen.queryByTestId('deal-stage-advance')).toBeNull();
      // Not merely absent by testId — no เลื่อนไป naming ANY quote stage leaked in under another key.
      for (const name of Object.values(ROUTE_NAME)) {
        expect(screen.queryByText(`เลื่อนไป: ${name}`)).toBeNull();
      }
      expect(api.tickets.updateStage).not.toHaveBeenCalled();
    });

    /**
     * The positive control (S5→S6: OWNER_SIGNOFF is on the owner route and still a manual move). With
     * a `lax` server advertising every stage including the off-route S4, the item must still name and
     * send `nextOnRoute`'s answer. Without a positive case, a menu that offered เลื่อนไป for NOTHING
     * would satisfy the whole block.
     */
    it('names and sends nextOnRoute\'s stage on a non-auto target, even with a lax server', async () => {
      const item = await openMenuItem({ channel: 'OWNER_DIRECT', salesStage: 'QUOTE_OWNER', lax: true });
      expect(item.textContent).toContain('เลื่อนไป: เจ้าของอนุมัติสเปค');
      expect(item.textContent).not.toContain(ROUTE_NAME.QUOTE_DESIGN_SIDE);

      fireEvent.click(item);
      await waitFor(() => expect(api.tickets.updateStage).toHaveBeenCalledWith(701, { stage: 'OWNER_SIGNOFF' }));
      expect(api.tickets.updateStage).not.toHaveBeenCalledWith(701, { stage: 'QUOTE_DESIGN_SIDE' });
    });

    // PRESENTATION's next is SPEC_APPROVED (S3), not auto and on every route. Proves the menu label
    // runs through dealStageLabel(next.code, entryChannel), not a channel-blind string.
    it('the S3 wording in the menu follows the route too (owner-direct at PRESENTATION -> เจ้าของตกลงตามสเปคแล้ว)', async () => {
      const item = await openMenuItem({ channel: 'OWNER_DIRECT', salesStage: 'PRESENTATION' });
      expect(item.textContent).toContain('เลื่อนไป: เจ้าของตกลงตามสเปคแล้ว');
      expect(item.textContent).not.toContain('ผู้ออกแบบอนุมัติสเปค');
    });

    // ขั้น 12 advances by itself when the IR is issued; the manual move is a deliberate skip, so it
    // must not read like the normal next step. Other stages keep เลื่อนไป.
    it('at PROCUREMENT the manual move reads ข้ามไปขั้น 13 ด้วยตนเอง and still sends DELIVERY_SCHEDULING', async () => {
      const item = await openMenuItem({ channel: 'DESIGNER_LED', salesStage: 'PROCUREMENT' });
      expect(item.textContent).toContain('ข้ามไปขั้น 13 ด้วยตนเอง');
      expect(item.textContent).not.toContain('เลื่อนไป');

      // Moving before the goods arrive asks first.
      fireEvent.click(item);
      const dialog = await screen.findByRole('dialog');
      expect(api.tickets.updateStage).not.toHaveBeenCalled();
      expect(within(dialog).getByText(/สินค้าจากโรงงานยังมาไม่ครบ/)).not.toBeNull();
      fireEvent.click(within(dialog).getByRole('button', { name: 'ข้ามไปขั้น 13' }));
      await waitFor(() => expect(api.tickets.updateStage).toHaveBeenCalledWith(701, { stage: 'DELIVERY_SCHEDULING' }));
    });
  });

  // The page hands the deal's own events to the stage track, so an owner skip reads ข้าม.
  it('ดูขั้นตอนทั้งหมด marks the owner stages ข้าม on a deal that skipped from 4 to 8', async () => {
    api.tickets.get.mockResolvedValue({
      ticket: buildTicket({
        summary: { salesStage: 'QUOTE_BUYER', entryChannel: 'DESIGNER_LED', nextFollowUpAt: '2026-07-15' },
        events: [
          { id: 1, kind: 'STAGE_CHANGED', fromStatus: 'SPEC_APPROVED', toStatus: 'QUOTE_DESIGN_SIDE', createdAt: '2026-07-01T09:00:00.000Z' },
          { id: 2, kind: 'STAGE_CHANGED', fromStatus: 'QUOTE_DESIGN_SIDE', toStatus: 'QUOTE_BUYER', createdAt: '2026-07-02T09:00:00.000Z' },
        ],
      }),
    });
    api.tickets.actions.mockResolvedValue({
      currentState: { lifecycle: 'ACTIVE', salesStage: 'QUOTE_BUYER', paymentStatus: null, fulfillmentStatus: null, status: 'price_proposed' },
      availableActions: [{ action: 'UPDATE_STAGE' }],
      stageDecisions: DEAL_STAGE_CATALOG.stages.map((stage) => ({
        stage: stage.code, no: stage.no, allowed: true, requiresReason: false, blockedReason: null, onRoute: true,
      })),
    });
    api.tickets.listActivities.mockResolvedValue({ items: [] });
    renderTicketDetailPage(ceoUser);
    fireEvent.click(await screen.findByRole('button', { name: /ดูขั้นตอนทั้งหมด/ }));
    const pip = (await screen.findAllByTestId('route-pip')).find((el) => el.getAttribute('data-stage') === 'QUOTE_OWNER');
    expect(within(pip).getByText('ขั้นที่ 5 ข้าม')).not.toBeNull();
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
          ? 'ดีลนี้เป็นเจ้าของติดต่อโดยตรง — ขั้นที่ 4 ไม่อยู่ในเส้นทางของดีลนี้ — แก้ช่องทางดีลก่อน'
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

    it('shows nothing when the server did not advertise SET_ENTRY_CHANNEL', async () => {
      const group = await openSteps([]);
      expect(within(group).queryByRole('button', { name: /แก้ช่องทางดีล/ })).toBeNull();
      expect(api.tickets.setEntryChannel).not.toHaveBeenCalled();
    });

    it('a first correction needs no note: it calls the API, then the route refreshes without a reload', async () => {
      const group = await openSteps([FIRST]);
      // What the server answers once the channel is corrected: the new ticket, and — on the refetch —
      // a route with nothing off it.
      api.tickets.setEntryChannel.mockResolvedValue({
        ticket: buildTicket({ summary: { salesStage: 'SPEC_APPROVED', entryChannel: 'DESIGNER_LED', nextFollowUpAt: '2026-07-15' } }),
      });
      api.tickets.actions.mockResolvedValue(actionsFor(new Set(), [FIRST]));
      const before = api.tickets.actions.mock.calls.length;

      fireEvent.click(within(group).getByRole('button', { name: /แก้ช่องทางดีล/ }));
      expect(screen.queryByTestId('entry-channel-fix-reason')).toBeNull();
      fireEvent.click(screen.getByRole('radio', { name: /ผู้ออกแบบนำดีล/ }));
      fireEvent.click(screen.getByRole('button', { name: 'บันทึกช่องทางใหม่' }));

      await waitFor(() => expect(api.tickets.setEntryChannel).toHaveBeenCalledWith(701, { value: 'DESIGNER_LED', note: null }));
      await waitFor(() => expect(api.tickets.actions.mock.calls.length).toBeGreaterThan(before));
      await waitFor(() => expect(screen.queryByTestId('off-route-group')).toBeNull());
      expect(screen.getByTestId('deal-route-line').textContent).toContain('ผู้ออกแบบนำดีล');
    });

    it('changing a stated channel asks for the reason the server listed, and sends it', async () => {
      const group = await openSteps([STATED]);
      api.tickets.setEntryChannel.mockResolvedValue({
        ticket: buildTicket({ summary: { salesStage: 'SPEC_APPROVED', entryChannel: 'DESIGNER_LED', nextFollowUpAt: '2026-07-15' } }),
      });
      fireEvent.click(within(group).getByRole('button', { name: /แก้ช่องทางดีล/ }));
      fireEvent.click(screen.getByRole('radio', { name: /ผู้ออกแบบนำดีล/ }));
      expect(screen.getByRole('button', { name: 'บันทึกช่องทางใหม่' }).disabled).toBe(true);
      fireEvent.change(screen.getByTestId('entry-channel-fix-reason'), { target: { value: 'มีผู้ออกแบบเข้ามาร่วมจริง' } });
      fireEvent.click(screen.getByRole('button', { name: 'บันทึกช่องทางใหม่' }));
      await waitFor(() => expect(api.tickets.setEntryChannel).toHaveBeenCalledWith(701, {
        value: 'DESIGNER_LED', note: 'มีผู้ออกแบบเข้ามาร่วมจริง',
      }));
    });
  });

  // deal-party-model.md: one deal per project, each bidding contractor a BUYER party.
  describe('deal parties — ผู้รับเหมาที่เสนอราคา and บันทึกผลประมูล', () => {
    const party = (overrides) => ({
      id: 1, ticketId: 701, customerId: 5, customerName: 'บริษัท ก จำกัด', contactId: null, contactName: null,
      role: 'BUYER', status: 'QUOTED', notAwardedReason: null, headline: false, splitTicketId: null, splitTicketCode: null,
      latestQuotationId: 11, latestQuotationNumber: 'QT-2026-0011-1', latestQuotationStatus: 'APPROVED', latestQuotationAmount: 512900,
      ...overrides,
    });
    const threeBidders = () => [
      party({ id: 1, customerName: 'บริษัท ตัวอย่าง คอนสตรัคชั่น จำกัด', headline: true, contactName: 'คุณประมูล หนึ่ง' }),
      party({ id: 2, customerName: 'บจก.สมมติ ก่อสร้าง', latestQuotationId: 12, latestQuotationNumber: 'QT-2026-0012-2', latestQuotationAmount: 491400.03 }),
      party({ id: 3, customerName: 'บจก.เดโม เอ็นจิเนียริ่ง', status: 'NEGOTIATING', latestQuotationId: 13, latestQuotationNumber: 'QT-2026-0013-1', latestQuotationStatus: 'PENDING_APPROVAL' }),
      party({ id: 4, customerName: 'บริษัท ดีไซน์ สตูดิโอ ตัวอย่าง จำกัด', role: 'DESIGNER', status: 'ACTIVE', latestQuotationId: null, latestQuotationNumber: null, latestQuotationStatus: null, latestQuotationAmount: null }),
    ];
    function bidDeal(parties = threeBidders(), summary = {}) {
      api.tickets.get.mockResolvedValue({
        ticket: buildTicket({
          summary: {
            lifecycle: 'ACTIVE', salesStage: 'QUOTE_BUYER', status: 'draft', createdById: 1, entryChannel: 'BUYER_DIRECT',
            code: 'DL-2026-0280', projectName: 'GRAND RIVER HOTEL', ...summary,
          },
        }),
      });
      api.tickets.parties.mockResolvedValue({ parties });
      api.dealQuotations.listForTicket.mockResolvedValue({ items: [] });
    }

    function renderDealPage(user = salesOwnerUser) {
      return render(
        <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}>
          <MemoryRouter initialEntries={['/tickets/701?tab=deal']}>
            <TicketDetailPage user={user} ticketId={701} onBack={vi.fn()} showToast={vi.fn()} />
          </MemoryRouter>
        </QueryClientProvider>,
      );
    }

    it('lists the BUYER parties with their quotation, amount, ★ ยอดหลัก, outcome and a price link', async () => {
      bidDeal();
      renderDealPage();
      const panel = await screen.findByTestId('deal-parties-panel');
      const row = await within(panel).findByTestId('party-row-1');
      expect(row.textContent).toContain('บริษัท ตัวอย่าง คอนสตรัคชั่น จำกัด');
      expect(row.textContent).toContain('คุณประมูล หนึ่ง');
      expect(row.textContent).toContain('QT-2026-0011-1');
      expect(row.textContent).toContain('★ ยอดหลัก');
      expect(row.textContent).toContain('เสนอราคาแล้ว');
      expect(within(row).getByRole('link', { name: 'แก้ไขราคา' }).getAttribute('href')).toBe('/quotations/11');
      // The designer is not a bidder row; it is in ผู้เกี่ยวข้อง.
      expect(within(panel).queryByText('บริษัท ดีไซน์ สตูดิโอ ตัวอย่าง จำกัด')).toBeNull();
      expect(screen.getAllByText('บริษัท ดีไซน์ สตูดิโอ ตัวอย่าง จำกัด').length).toBeGreaterThan(0);
      expect(within(panel).getByTestId('add-party')).toBeTruthy();
    });

    it('a party with no quotation links to a new one for that party', async () => {
      bidDeal([party({ id: 7, status: 'ACTIVE', latestQuotationId: null, latestQuotationNumber: null, latestQuotationStatus: null, latestQuotationAmount: null })]);
      renderDealPage();
      // The table (desktop) and the stacked cards (phone) are both in the DOM; CSS shows one.
      const [link] = await screen.findAllByTestId('party-quote-7');
      expect(link.textContent).toBe('เสนอราคา');
      expect(link.getAttribute('href')).toBe('/quotations/new?ticket=701&party=7');
    });

    it('several bidders, none awarded: the header is the project, the bill-to waits on the bid, and บันทึกผลประมูล is the primary', async () => {
      bidDeal();
      renderDealPage();
      const primary = await screen.findByTestId('ticket-primary-action');
      await waitFor(() => expect(primary.getAttribute('data-action')).toBe('record_bid_outcome'));
      expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('GRAND RIVER HOTEL');
      expect(screen.getByText('รอผลประมูล · 3 ราย')).toBeTruthy();
    });

    it('a viewer who may not edit the deal gets no บันทึกผลประมูล and no add button', async () => {
      bidDeal();
      renderDealPage({ id: 77, employeeId: 77, name: 'อื่น', role: 'sales' });
      await screen.findByTestId('deal-parties-panel');
      await stickyInputsSettled();
      expect(screen.queryByTestId('add-party')).toBeNull();
      expect(document.querySelector('[data-action="record_bid_outcome"]')).toBeNull();
    });

    it('the dialog: un-approved bids are disabled, two winners show the split with -A, and submit records the outcome', async () => {
      bidDeal();
      api.tickets.recordBidOutcome.mockResolvedValue({ result: { primaryTicketId: 701, childTicketIds: [702], ticket: {} } });
      renderDealPage();
      fireEvent.click(await screen.findByRole('button', { name: /บันทึกผลประมูล/ }));
      const dialog = within(await screen.findByTestId('bid-outcome-modal'));
      expect(dialog.getByText(/DL-2026-0280 · GRAND RIVER HOTEL · เลือกผู้รับเหมาที่ได้งาน/)).toBeTruthy();
      const submit = dialog.getByTestId('bid-outcome-submit');
      expect(submit.disabled).toBe(true);
      expect(dialog.getByTestId('bid-outcome-note').textContent).toContain('เสียงาน');
      const pending = within(dialog.getByTestId('bid-winner-3'));
      expect(pending.getByRole('checkbox').disabled).toBe(true);
      expect(pending.getByText('ใบเสนอราคายังไม่อนุมัติ')).toBeTruthy();

      fireEvent.click(within(dialog.getByTestId('bid-winner-1')).getByRole('checkbox'));
      fireEvent.click(within(dialog.getByTestId('bid-winner-2')).getByRole('checkbox'));
      expect(dialog.getByText('ได้งานมากกว่า 1 ราย · แยกดีล')).toBeTruthy();
      expect(dialog.getAllByText('DL-2026-0280-A').length).toBeGreaterThan(0);
      // Make the second winner the primary: the first becomes -A.
      fireEvent.click(dialog.getAllByRole('radio', { name: /บจก.สมมติ ก่อสร้าง/ })[0]);
      expect(dialog.getByText('ผู้รับเหมาที่ไม่ได้เลือก')).toBeTruthy();
      fireEvent.click(dialog.getByRole('radio', { name: 'ราคา' }));
      fireEvent.click(submit);
      await waitFor(() => expect(api.tickets.recordBidOutcome).toHaveBeenCalledWith(701, {
        awardedPartyIds: [1, 2], primaryPartyId: 2, othersOutcome: 'NOT_AWARDED', notAwardedReason: 'PRICE',
      }));
    });

    it('ยังรอผล hides the reason and sends KEEP_WAITING; a 409 stays in the dialog', async () => {
      bidDeal();
      api.tickets.recordBidOutcome.mockRejectedValue(Object.assign(new Error('บจก.สมมติ ก่อสร้าง: บันทึกผลได้เฉพาะผู้รับเหมาที่ใบเสนอราคาล่าสุดอนุมัติแล้ว'), { status: 409 }));
      renderDealPage();
      fireEvent.click(await screen.findByRole('button', { name: /บันทึกผลประมูล/ }));
      const dialog = within(await screen.findByTestId('bid-outcome-modal'));
      fireEvent.click(within(dialog.getByTestId('bid-winner-2')).getByRole('checkbox'));
      fireEvent.click(dialog.getByRole('radio', { name: 'ยังรอผล' }));
      expect(dialog.queryByRole('radio', { name: 'ราคา' })).toBeNull();
      fireEvent.click(dialog.getByTestId('bid-outcome-submit'));
      expect(await dialog.findByRole('alert')).toBeTruthy();
      expect(dialog.getByRole('alert').textContent).toContain('บันทึกผลได้เฉพาะ');
      expect(api.tickets.recordBidOutcome).toHaveBeenCalledWith(701, {
        awardedPartyIds: [2], primaryPartyId: 2, othersOutcome: 'KEEP_WAITING',
      });
    });

    it('the money/close steps still outrank บันทึกผลประมูล', async () => {
      bidDeal(threeBidders(), { status: 'quotation_issued' });
      api.tickets.actions.mockResolvedValue({
        currentState: { lifecycle: 'ACTIVE', salesStage: 'QUOTE_BUYER', status: 'quotation_issued' },
        availableActions: [{ action: 'VERIFY_CLOSE' }],
      });
      renderDealPage(ceoUser);
      expect(await screen.findByTestId('ticket-detail-verify-close')).toBeTruthy();
      expect(document.querySelector('[data-action="record_bid_outcome"]')).toBeNull();
    });
  });
});
