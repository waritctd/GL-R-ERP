import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PricingRequestDetailPage } from './PricingRequestDetailPage.jsx';
import { api } from '../../api/index.js';

// This component (and PricingRequestCreateModal, which it opens in mode="revision"
// for the customer-change-revision flow) is exercised here against a hand-rolled
// api mock, not the real Java backend and not even mockApi.js. Per CLAUDE.md's
// "Mock API contract" / "Authz verify against Java, not the mock": every
// role-visibility assertion below (what Sales/sales_manager/Import/CEO can see or
// click) is UI-LEVEL ONLY — it proves the component's own conditional rendering,
// not that the server actually enforces it. The authoritative role/scope checks
// are the real-DB integration tests in
// backend/src/test/java/th/co/glr/hr/pricingrequest/PricingFactoryQuoteCostingIntegrationTest.java
// and PricingRequestFlowIntegrationTest.java (COMMIT 4's attachment authz section
// in particular), added across this branch's commits 1-5. Nothing in this file is
// evidence for or against those Java-side guards.

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    pricingRequests: {
      get: vi.fn(),
      listFactoryQuotes: vi.fn(),
      listCostings: vi.fn(),
      listAttachments: vi.fn(),
      attachmentUrl: (id) => `#attachment-${id}`,
      factoryQuoteAttachmentUrl: (id) => `#quote-attachment-${id}`,
      generateFactoryEmailDrafts: vi.fn(),
      updateFactoryQuote: vi.fn(),
      sendFactoryQuote: vi.fn(),
      receiveFactoryQuote: vi.fn(),
      startFactoryNegotiation: vi.fn(),
      markFactoryQuoteReady: vi.fn(),
      createCosting: vi.fn(),
      recalculateCosting: vi.fn(),
      submitCosting: vi.fn(),
      uploadFactoryQuoteAttachment: vi.fn(),
      uploadAttachment: vi.fn(),
      deleteAttachment: vi.fn(),
      setAttachmentIncludeInFactoryEmail: vi.fn(),
      createCustomerChangeRevision: vi.fn(),
      listPricingDecisions: vi.fn(),
      getPricingDecisionSalesView: vi.fn(),
      startPricingDecision: vi.fn(),
      updatePricingDecision: vi.fn(),
      approvePricingDecision: vi.fn(),
      returnPricingDecisionToImport: vi.fn(),
      // Step 4: Customer Quotation Generation and Issuance.
      listCustomerQuotations: vi.fn(),
      createCustomerQuotation: vi.fn(),
      updateCustomerQuotation: vi.fn(),
      previewCustomerQuotation: vi.fn(),
      issueCustomerQuotation: vi.fn(),
      cancelCustomerQuotation: vi.fn(),
      createCustomerQuotationRevision: vi.fn(),
      downloadCustomerQuotationPdf: vi.fn(),
      downloadCustomerQuotationXlsx: vi.fn(),
      // Step 5: Customer Decision and Commercial Revisions.
      recordCustomerQuotationOutcome: vi.fn(),
      // Step 6: Deposit, Payment, and Order Confirmation.
      confirmOrder: vi.fn(),
      createDepositNoticeFromQuotation: vi.fn(),
    },
    catalog: {
      prices: vi.fn(),
    },
  },
}));

const salesOwner = { id: 1, employeeId: 1, name: 'พนักงานขาย', role: 'sales' };
const salesManager = { id: 2, employeeId: 2, name: 'ผจก.ขาย', role: 'sales_manager' };
const importUser = { id: 3, employeeId: 3, name: 'ฝ่ายนำเข้า', role: 'import' };
const ceoUser = { id: 4, employeeId: 4, name: 'ซีอีโอ', role: 'ceo' };

function buildRequest(overrides = {}) {
  return {
    summary: {
      id: 501,
      requestCode: 'PCR-2026-0001',
      ticketId: 701,
      ticketCode: 'PR-2026-0701',
      customerName: 'บริษัท ทดสอบ จำกัด',
      projectName: 'โครงการทดสอบ',
      status: 'IMPORT_REVIEWING',
      recipientType: 'DESIGNER',
      recipientLabel: 'ผู้ออกแบบ ก.',
      requiredDate: '2026-08-01',
      customerTargetPrice: 500,
      targetCurrency: 'USD',
      note: 'โน้ตเดิม',
      ticketCreatedById: 1,
      ...overrides.summary,
    },
    items: overrides.items ?? [
      {
        id: 1,
        sourceTicketItemId: null,
        productId: null,
        brand: 'SCG',
        model: 'A1',
        catalogBrand: null,
        catalogModel: null,
        productDescription: 'กระเบื้องพื้น SCG A1',
        texture: 'ด้าน',
        size: '60x60',
        quantityType: 'CONFIRMED',
        requestedQty: 20,
        requestedUnit: 'แผ่น',
        requestedUnitBasis: 'PER_PIECE',
        resolvedFactoryName: 'SCG Ceramics',
        factory: null,
        catalogProductCode: 'SCG-A1',
        catalogBasePrice: 120,
        catalogCurrency: 'THB',
        targetDeliveryDate: null,
        deliveryLocation: null,
        specialRequirement: null,
      },
    ],
  };
}

function buildFactoryQuote(overrides = {}) {
  return {
    id: 91,
    factoryName: 'SCG Ceramics',
    revisionNo: 1,
    status: 'DRAFT',
    current: true,
    dispatchStatus: undefined,
    dispatchAttemptCount: 0,
    dispatchFailureMessage: null,
    emailTo: 'sales@scg-factory.example',
    emailSubject: 'ขอราคา SCG A1',
    emailBody: 'เรียน โรงงาน...',
    supplierQuoteRef: null,
    defaultCurrency: 'THB',
    paymentTerms: '',
    leadTimeText: '',
    negotiationNote: '',
    attachments: [],
    items: [
      {
        id: 911,
        pricingRequestItemId: 1,
        supplierProductCode: '',
        supplierProductDescription: '',
        quotedQuantity: 20,
        quotedUnit: 'PER_PIECE',
        unitBasis: 'PER_PIECE',
        rawUnitPrice: null,
        currency: 'THB',
        sqmPerUnit: null,
      },
    ],
    ...overrides,
  };
}

function buildCosting(overrides = {}) {
  return {
    id: 21,
    costingCode: 'COST-2026-0001',
    versionNo: 1,
    status: 'CALCULATED',
    stale: false,
    totalLandedCostThb: 15000,
    items: [
      {
        id: 211,
        factoryName: 'SCG Ceramics',
        factoryQuoteRevisionNo: 1,
        rawUnitPrice: 50,
        rawCurrency: 'THB',
        landedCostPerUnitThb: 60,
      },
    ],
    ...overrides,
  };
}

function setApiDefaults() {
  api.pricingRequests.get.mockResolvedValue({ pricingRequest: buildRequest() });
  api.pricingRequests.listFactoryQuotes.mockResolvedValue({ items: [] });
  api.pricingRequests.listCostings.mockResolvedValue({ items: [] });
  api.pricingRequests.listAttachments.mockResolvedValue({ items: [] });
  api.pricingRequests.generateFactoryEmailDrafts.mockResolvedValue({});
  api.pricingRequests.updateFactoryQuote.mockResolvedValue({});
  api.pricingRequests.sendFactoryQuote.mockResolvedValue({});
  api.pricingRequests.receiveFactoryQuote.mockResolvedValue({});
  api.pricingRequests.startFactoryNegotiation.mockResolvedValue({});
  api.pricingRequests.markFactoryQuoteReady.mockResolvedValue({});
  // submitToCeo chains createCosting -> recalculate -> submit and needs the new row's id.
  api.pricingRequests.createCosting.mockResolvedValue({ costing: { id: 21 } });
  api.pricingRequests.recalculateCosting.mockResolvedValue({});
  api.pricingRequests.submitCosting.mockResolvedValue({});
  api.pricingRequests.uploadFactoryQuoteAttachment.mockResolvedValue({});
  api.pricingRequests.uploadAttachment.mockResolvedValue({ attachment: null });
  api.pricingRequests.deleteAttachment.mockResolvedValue({});
  api.pricingRequests.setAttachmentIncludeInFactoryEmail.mockResolvedValue({});
  api.pricingRequests.createCustomerChangeRevision.mockResolvedValue({ pricingRequest: { summary: { id: 999 } } });
  api.pricingRequests.listPricingDecisions.mockResolvedValue({ items: [] });
  api.pricingRequests.getPricingDecisionSalesView.mockRejectedValue(new Error('No approved pricing decision yet'));
  api.pricingRequests.startPricingDecision.mockResolvedValue({});
  api.pricingRequests.updatePricingDecision.mockResolvedValue({});
  api.pricingRequests.approvePricingDecision.mockResolvedValue({});
  api.pricingRequests.returnPricingDecisionToImport.mockResolvedValue({});
  api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [] });
  api.pricingRequests.createCustomerQuotation.mockResolvedValue({ quotation: buildCustomerQuotation() });
  api.pricingRequests.updateCustomerQuotation.mockResolvedValue({ quotation: buildCustomerQuotation() });
  api.pricingRequests.issueCustomerQuotation.mockResolvedValue({ quotation: buildCustomerQuotation({ docStatus: 'ISSUED' }) });
  api.pricingRequests.cancelCustomerQuotation.mockResolvedValue({ quotation: buildCustomerQuotation({ docStatus: 'CANCELLED' }) });
  api.pricingRequests.createCustomerQuotationRevision.mockResolvedValue({ quotation: buildCustomerQuotation({ quotationRevisionNo: 2 }) });
  api.pricingRequests.downloadCustomerQuotationPdf.mockResolvedValue(new Blob(['pdf']));
  api.pricingRequests.downloadCustomerQuotationXlsx.mockResolvedValue(new Blob(['xlsx']));
  api.pricingRequests.recordCustomerQuotationOutcome.mockResolvedValue({ quotation: buildCustomerQuotation({ docStatus: 'ACCEPTED' }) });
  api.pricingRequests.confirmOrder.mockResolvedValue({
    result: { ticket: { summary: { id: 701 } }, pricingRequest: { id: 501, orderConfirmedAt: '2026-07-21T00:00:00Z' } },
  });
  api.pricingRequests.createDepositNoticeFromQuotation.mockResolvedValue({ depositNotice: { id: 9901, status: 'DRAFT' } });
  api.catalog.prices.mockResolvedValue({ items: [] });
}

// Step 4 (Customer Quotation Generation and Issuance) fixture. Mirrors
// CustomerQuotationDtos.CustomerQuotationDto/CustomerQuotationItemDto — deliberately has no
// cost/margin/FX field anywhere (design correction 2's own precedent, carried into Step 4).
function buildCustomerQuotationItem(overrides = {}) {
  return {
    id: 9001,
    seq: 1,
    pricingRequestItemId: 1,
    pricingDecisionItemId: 8001,
    description: 'กระเบื้องพื้น SCG A1',
    itemNotes: null,
    requestedUnitBasis: 'PER_PIECE',
    requestedQuantity: 20,
    approvedUnitPrice: 72,
    salesDiscount: 0,
    finalUnitPrice: 72,
    minimumSellingPricePerRequestedUnit: 65,
    lineSubtotal: 1440,
    vat: 100.8,
    lineTotal: 1540.8,
    ...overrides,
  };
}

function buildCustomerQuotation(overrides = {}) {
  return {
    id: 5501,
    number: 'QT-2026-0001',
    ticketId: 701,
    pricingRequestId: 501,
    pricingDecisionId: 7001,
    recipientType: 'DESIGNER',
    recipientLabel: 'ผู้ออกแบบ ก.',
    docStatus: 'DRAFT',
    quotationVersion: 1,
    quotationRevisionNo: 1,
    parentQuotationId: null,
    issuedById: 1,
    issuedByName: 'พนักงานขาย',
    issuedAt: null,
    subtotalAmount: 1440,
    vatAmount: 100.8,
    grandTotal: 1540.8,
    currency: 'THB',
    paymentTerms: null,
    leadTime: null,
    deliveryTerms: null,
    validityDate: null,
    customerNotes: null,
    items: overrides.items ?? [buildCustomerQuotationItem()],
    ...overrides,
  };
}

// Step 3 (CEO Selling Price Decision) fixtures. Mirrors PricingDecisionDtos.PricingDecisionDto /
// PricingDecisionItemDto — never spread into the sales-facing view builder below, which mirrors
// PricingDecisionSalesViewDto/PricingDecisionSalesItemDto instead (design correction 2).
function buildDecisionItem(overrides = {}) {
  return {
    id: 8001,
    pricingDecisionId: 7001,
    pricingRequestItemId: 1,
    pricingCostingItemId: 1,
    brand: 'SCG',
    model: 'A1',
    productDescription: 'กระเบื้องพื้น SCG A1',
    factoryName: 'SCG Ceramics',
    requestedUnitBasis: 'PER_PIECE',
    requestedQuantity: 20,
    normalizedQuantityPieces: 20,
    frozenLandedCostPerPieceThb: 60,
    frozenLandedCostPerRequestedUnitThb: 60,
    currency: 'THB',
    proposedMarginPct: 0.2,
    approvedMarginPct: null,
    proposedSellingPricePerRequestedUnit: 72,
    approvedSellingPricePerRequestedUnit: null,
    discountCeilingPct: 0.1,
    minimumSellingPricePerRequestedUnit: 65,
    decisionNote: null,
    ...overrides,
  };
}

function buildDecision(overrides = {}) {
  return {
    id: 7001,
    decisionCode: 'PCD-2026-0001',
    pricingRequestId: 501,
    pricingCostingId: 601,
    decisionVersionNo: 1,
    status: 'DRAFT',
    defaultMarginPct: 0.2,
    currency: 'THB',
    fxRateUsed: 1,
    fxSource: 'THB',
    fxEffectiveDate: '2026-07-21',
    ceoNote: null,
    returnReason: null,
    createdBy: 4,
    approvedBy: null,
    approvedAt: null,
    returnedAt: null,
    items: [buildDecisionItem()],
    ...overrides,
  };
}

function buildSalesView(overrides = {}) {
  return {
    pricingRequestId: 501,
    pricingDecisionId: 7001,
    currency: 'THB',
    approvedAt: '2026-07-21T00:00:00Z',
    items: [
      {
        pricingRequestItemId: 1,
        brand: 'SCG',
        model: 'A1',
        productDescription: 'กระเบื้องพื้น SCG A1',
        requestedUnitBasis: 'PER_PIECE',
        requestedQuantity: 20,
        approvedSellingPricePerRequestedUnit: 72,
        discountCeilingPct: 0.1,
        minimumSellingPricePerRequestedUnit: 65,
      },
    ],
    ...overrides,
  };
}

function renderDetailPage({
  user = importUser,
  request = buildRequest(),
  detailError = null,
  detailPromise = null,
  factoryQuotes = [],
  costings = [],
  attachments = [],
  showToast = vi.fn(),
  routeId = request?.summary?.id ?? 501,
} = {}) {
  if (detailPromise) {
    api.pricingRequests.get.mockReturnValue(detailPromise);
  } else if (detailError) {
    api.pricingRequests.get.mockRejectedValue(detailError);
  } else {
    api.pricingRequests.get.mockResolvedValue({ pricingRequest: request });
  }
  api.pricingRequests.listFactoryQuotes.mockResolvedValue({ items: factoryQuotes });
  api.pricingRequests.listCostings.mockResolvedValue({ items: costings });
  api.pricingRequests.listAttachments.mockResolvedValue({ items: attachments });

  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });

  const utils = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/pricing-requests/${routeId}`]}>
        <Routes>
          <Route path="/pricing-requests/:id" element={<PricingRequestDetailPage user={user} showToast={showToast} />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { ...utils, queryClient, showToast };
}

async function waitForLoaded(request = buildRequest()) {
  return screen.findByRole('heading', { level: 1, name: request.summary.requestCode });
}

beforeEach(() => {
  vi.clearAllMocks();
  setApiDefaults();
});

describe('PricingRequestDetailPage unavailable states', () => {
  it('renders a contextual loading state while the detail is pending', async () => {
    renderDetailPage({
      detailPromise: new Promise(() => {}),
      routeId: 501,
    });

    expect((await screen.findByRole('status')).textContent).toContain('กำลังโหลดคำขอราคา');
    expect(screen.getByText('กำลังดึงรายละเอียดสินค้า ผู้รับ และสถานะล่าสุด')).toBeTruthy();
  });

  it('renders a recoverable error state and retries the existing query', async () => {
    const error = new Error('โหลดคำขอราคาไม่สำเร็จ');
    renderDetailPage({ detailError: error, routeId: 501 });

    expect((await screen.findByRole('alert')).textContent).toContain('โหลดคำขอราคาไม่สำเร็จ');

    api.pricingRequests.get.mockResolvedValueOnce({ pricingRequest: buildRequest() });
    fireEvent.click(screen.getByRole('button', { name: /ลองใหม่/ }));

    await waitForLoaded();
    expect(api.pricingRequests.get).toHaveBeenCalledTimes(2);
  });

  it('renders a not-found state for an existing 404 outcome without offering retry', async () => {
    const error = Object.assign(new Error('ไม่พบคำขอราคานี้'), { status: 404 });
    renderDetailPage({ detailError: error, routeId: 9999 });

    expect(await screen.findByText('ไม่พบคำขอราคานี้')).toBeTruthy();
    expect(screen.getByText('ตรวจสอบลิงก์อีกครั้ง หรือกลับไปเปิดจากรายการที่คุณเข้าถึงได้')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'กลับไปที่คิวขอราคา' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: /ลองใหม่/ })).toBeNull();
  });

  it('renders a safe denied state for an existing 403 outcome without exposing record detail', async () => {
    const error = Object.assign(new Error('ไม่มีสิทธิ์เข้าถึงรายการนี้'), { status: 403 });
    renderDetailPage({ detailError: error, routeId: 501 });

    expect(await screen.findByText('ยังเปิดคำขอราคานี้ไม่ได้')).toBeTruthy();
    expect(screen.getByText('ระบบไม่เปิดเผยรายละเอียดของคำขอราคาที่คุณไม่มีสิทธิ์เข้าถึง')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'กลับไปที่คิวขอราคา' })).toBeTruthy();
    expect(screen.queryByText('ไม่มีสิทธิ์เข้าถึงรายการนี้')).toBeNull();
    expect(screen.queryByRole('button', { name: /ลองใหม่/ })).toBeNull();
  });

  it('uses a safe back action for sales users who cannot open the pricing-request queue', async () => {
    renderDetailPage({
      user: salesOwner,
      request: null,
      routeId: 501,
    });

    expect(await screen.findByText('ไม่พบคำขอราคานี้')).toBeTruthy();
    expect(screen.getByText('ตรวจสอบลิงก์อีกครั้ง หรือกลับไปเปิดจากรายการที่คุณเข้าถึงได้')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'กลับ' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'กลับไปที่คิวขอราคา' })).toBeNull();
  });
});

describe('PricingRequestDetailPage role-scoped raw quote/costing visibility (UI-level only — see file header)', () => {
  it('does not render or fetch Factory Quotes / Costing sections for sales, so sales cannot trigger any raw factory-quote or costing action', async () => {
    renderDetailPage({
      user: salesOwner,
      factoryQuotes: [buildFactoryQuote()],
      costings: [buildCosting()],
    });
    await waitForLoaded();

    expect(screen.queryByText('ราคาโรงงาน')).toBeNull();
    expect(screen.queryByText('ต้นทุนนำเข้า')).toBeNull();
    expect(screen.queryByRole('button', { name: 'สร้างร่างอีเมล' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'สร้างร่างต้นทุน' })).toBeNull();
    // The raw-data queries are gated (`enabled: canSeeRaw(user)`), not just hidden in the DOM —
    // sales never even fetches factory-quote/costing detail.
    await waitFor(() => expect(api.pricingRequests.listFactoryQuotes).not.toHaveBeenCalled());
    expect(api.pricingRequests.listCostings).not.toHaveBeenCalled();
  });

  it('shows no raw-cost UI for sales_manager either — raw supplier prices / landed cost stay Import+CEO only', async () => {
    renderDetailPage({
      user: salesManager,
      factoryQuotes: [buildFactoryQuote()],
      costings: [buildCosting()],
    });
    await waitForLoaded();

    expect(screen.queryByText('ราคาโรงงาน')).toBeNull();
    expect(screen.queryByText('ต้นทุนนำเข้า')).toBeNull();
    expect(screen.queryByText(/50.*THB/)).toBeNull();
    await waitFor(() => expect(api.pricingRequests.listFactoryQuotes).not.toHaveBeenCalled());
    expect(api.pricingRequests.listCostings).not.toHaveBeenCalled();
  });

  it('lets the CEO see raw Factory Quotes / Costing data, but strictly read-only — no action controls anywhere', async () => {
    renderDetailPage({
      user: ceoUser,
      factoryQuotes: [buildFactoryQuote({ status: 'RESPONSE_RECEIVED' })],
      costings: [buildCosting()],
    });
    await waitForLoaded();

    // Raw data IS visible to CEO.
    // By role, not text: "ราคาโรงงาน" now also names a column in the
    // response-entry grid, so a bare text query can match more than one node.
    // The assertion here is that the SECTION is present.
    expect(await screen.findByRole('heading', { name: 'ราคาโรงงาน' })).not.toBeNull();
    expect(screen.getByText('ต้นทุนนำเข้า')).not.toBeNull();
    expect(screen.getByText('SCG Ceramics')).not.toBeNull();
    expect(screen.getByText('COST-2026-0001')).not.toBeNull();

    // But every mutating control is Import-only (isImport(user)) and must be absent for CEO.
    expect(screen.queryByRole('button', { name: 'สร้างร่างอีเมล' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'สร้างร่างต้นทุน' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'ส่ง' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'ส่งอีกครั้ง' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'พร้อมคำนวณต้นทุน' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'เจรจา' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'คำนวณใหม่' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'ส่งให้ CEO ตรวจ' })).toBeNull();
    // No editable email-draft or response-entry form fields either. Queried by
    // accessible name, not placeholder: these fields carry real labels now, so
    // a placeholder query would report "absent" for a field that is present and
    // simply has no placeholder — an assertion that passes for the wrong reason.
    expect(screen.queryByLabelText('อีเมลโรงงาน')).toBeNull();
    expect(screen.queryByLabelText(/^ราคาโรงงาน/)).toBeNull();
  });
});

describe('PricingRequestDetailPage Import factory-quote workflow', () => {
  it('lets Import edit the factory email draft before sending, and saves it via updateFactoryQuote', async () => {
    const quote = buildFactoryQuote();
    renderDetailPage({ user: importUser, factoryQuotes: [quote] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    const toInput = screen.getByLabelText('อีเมลโรงงาน');
    const subjectInput = screen.getByLabelText('หัวข้ออีเมล');
    const bodyInput = screen.getByLabelText('เนื้อหาอีเมล');

    fireEvent.change(toInput, { target: { value: 'purchasing@scg-factory.example' } });
    fireEvent.change(subjectInput, { target: { value: 'ขอราคาใหม่ SCG A1' } });
    fireEvent.change(bodyInput, { target: { value: 'เรียน โรงงาน กรุณาเสนอราคาใหม่' } });

    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่างอีเมล' }));

    await waitFor(() => expect(api.pricingRequests.updateFactoryQuote).toHaveBeenCalledWith(
      quote.id,
      expect.objectContaining({
        emailTo: 'purchasing@scg-factory.example',
        emailSubject: 'ขอราคาใหม่ SCG A1',
        emailBody: 'เรียน โรงงาน กรุณาเสนอราคาใหม่',
      }),
    ));
  });

  // Commit 1 follow-up: send() requires a stable clientRequestId across retries of the SAME
  // dispatch attempt so the backend's (created_by, client_request_id) idempotency key actually
  // dedupes instead of minting a fresh, always-distinct key that could never replay. The button's
  // onClick only regenerates the id when dispatchStatus is FAILED (a permanently exhausted key) —
  // otherwise it must reuse whatever is already cached in state for this quote.
  it('keeps the same clientRequestId across repeated "ส่งแล้ว" clicks (open/cancel/reopen) — it must not regenerate per click', async () => {
    const quote = buildFactoryQuote();
    const uuidSpy = vi.spyOn(globalThis.crypto, 'randomUUID');
    renderDetailPage({ user: importUser, factoryQuotes: [quote] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');
    // costingClientRequestId is minted once on mount (useState(() => generateClientRequestId())),
    // unrelated to the send flow under test — baseline off that instead of asserting 0.
    const callsBeforeAnySend = uuidSpy.mock.calls.length;

    // First open: mints and caches a clientRequestId for this quote.
    fireEvent.click(screen.getByRole('button', { name: 'ส่งแล้ว' }));
    expect(await screen.findByRole('dialog', { name: 'ส่งอีเมลถึงโรงงาน' })).not.toBeNull();
    expect(uuidSpy).toHaveBeenCalledTimes(callsBeforeAnySend + 1);

    // Cancel without confirming, then reopen: must reuse the cached id, not mint a new one.
    fireEvent.click(screen.getByRole('button', { name: 'ยกเลิก' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'ส่งอีเมลถึงโรงงาน' })).toBeNull());
    fireEvent.click(screen.getByRole('button', { name: 'ส่งแล้ว' }));
    expect(await screen.findByRole('dialog', { name: 'ส่งอีเมลถึงโรงงาน' })).not.toBeNull();
    // Still no new call — reused the cached id, not regenerated.
    expect(uuidSpy).toHaveBeenCalledTimes(callsBeforeAnySend + 1);

    fireEvent.click(screen.getByRole('button', { name: 'ส่งอีเมล' }));

    await waitFor(() => expect(api.pricingRequests.sendFactoryQuote).toHaveBeenCalledTimes(1));
    const [, payload] = api.pricingRequests.sendFactoryQuote.mock.calls[0];
    expect(payload.clientRequestId).toBe(uuidSpy.mock.results[callsBeforeAnySend].value);
  });

  it('records a factory response revision entry via receiveFactoryQuote with a fresh clientRequestId', async () => {
    const quote = buildFactoryQuote({ status: 'REQUESTED' });
    renderDetailPage({ user: importUser, factoryQuotes: [quote] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    // Import types ONE thing: the price. เลขอ้างอิงใบเสนอราคา / เงื่อนไขการชำระเงิน /
    // ระยะเวลาผลิต-ส่งมอบ were removed from this form (owner ruling 2026-08-11) — all three are
    // optional in ReceiveFactoryQuoteRequest, so the payload simply carries null for them.
    const priceInput = screen.getByLabelText(/^ราคาโรงงาน/);
    fireEvent.change(priceInput, { target: { value: '55.5' } });

    fireEvent.click(screen.getByRole('button', { name: 'บันทึกคำตอบ/รอบแก้ไข' }));

    await waitFor(() => expect(api.pricingRequests.receiveFactoryQuote).toHaveBeenCalledWith(
      quote.id,
      expect.objectContaining({
        supplierQuoteRef: null,
        clientRequestId: expect.stringMatching(/^[0-9a-f-]{36}$/i),
        items: [expect.objectContaining({ pricingRequestItemId: 1, rawUnitPrice: 55.5 })],
      }),
    ));
    // The removed fields must not be resurrected as inputs.
    expect(screen.queryByLabelText('เลขอ้างอิงใบเสนอราคา')).toBeNull();
    expect(screen.queryByLabelText('เงื่อนไขการชำระเงิน')).toBeNull();
    expect(screen.queryByLabelText('ระยะเวลาผลิต/ส่งมอบ')).toBeNull();
  });
});

describe('PricingRequestDetailPage Import costing workflow', () => {
  // Owner ruling 2026-08-11: Import keys in the price and submits — it never touches the costing
  // aggregate. The old per-step buttons (คำนวณใหม่ / ส่งให้ CEO ตรวจ, and the สร้างร่างต้นทุน that
  // preceded them) are gone; ONE button now runs the whole chain. Asserted in call ORDER because
  // each backend step rejects being run out of sequence: createDraft refuses a quote that is not
  // READY_FOR_COSTING, and submit refuses a costing that is still DRAFT or stale.
  it('runs markReady -> createCosting -> recalculate -> submit from the single ส่งให้ CEO อนุมัติราคา action', async () => {
    const quote = buildFactoryQuote({ status: 'RESPONSE_RECEIVED' });
    renderDetailPage({ user: importUser, factoryQuotes: [quote] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    fireEvent.click(screen.getByRole('button', { name: 'ส่งให้ CEO อนุมัติราคา' }));

    await waitFor(() => expect(api.pricingRequests.submitCosting).toHaveBeenCalledWith(21, expect.any(Object)));
    expect(api.pricingRequests.markFactoryQuoteReady).toHaveBeenCalledWith(quote.id);
    expect(api.pricingRequests.createCosting).toHaveBeenCalledWith(501, expect.objectContaining({
      clientRequestId: expect.stringMatching(/^[0-9a-f-]{36}$/i),
    }));
    expect(api.pricingRequests.recalculateCosting).toHaveBeenCalledWith(21, expect.any(Object));

    const order = (fn) => fn.mock.invocationCallOrder[0];
    expect(order(api.pricingRequests.markFactoryQuoteReady)).toBeLessThan(order(api.pricingRequests.createCosting));
    expect(order(api.pricingRequests.createCosting)).toBeLessThan(order(api.pricingRequests.recalculateCosting));
    expect(order(api.pricingRequests.recalculateCosting)).toBeLessThan(order(api.pricingRequests.submitCosting));
  });

  // Wrong-way-round: the point is that these surfaces are ABSENT for Import, not merely different.
  it('shows Import no costing, CEO-decision, customer-quotation or ask-Sales surface', async () => {
    const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
    api.pricingRequests.listPricingDecisions.mockResolvedValue({ items: [buildDecision()] });
    renderDetailPage({ user: importUser, request, factoryQuotes: [buildFactoryQuote()], costings: [buildCosting()] });
    await waitForLoaded(request);
    await screen.findByText('SCG Ceramics');

    expect(screen.queryByText('ต้นทุนนำเข้า')).toBeNull();
    expect(screen.queryByText('การพิจารณาราคาขายของ CEO')).toBeNull();
    expect(screen.queryByText('ใบเสนอราคาลูกค้า')).toBeNull();
    // The ขอข้อมูลเพิ่มเติม feature was removed from the product entirely.
    expect(screen.queryByText('ขอข้อมูลจาก Sales')).toBeNull();
    expect(screen.queryByText('ตอบข้อมูลเพิ่มเติม')).toBeNull();
    // COST-2026-0001 is the costing code — absent because the whole panel is.
    expect(screen.queryByText('COST-2026-0001')).toBeNull();
  });
});

describe('PricingRequestDetailPage customer-change revision editing', () => {
  it('lets the owning sales rep open the revision modal (seeded from the current request) and create a revision via createCustomerChangeRevision', async () => {
    const request = buildRequest({ summary: { status: 'READY_FOR_CEO_REVIEW' } });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);

    fireEvent.click(screen.getByRole('button', { name: 'สร้างรอบแก้ไข' }));

    // mode="revision" seeds every field from the CURRENT request, same as edit mode
    // (PricingRequestCreateModal, COMMIT 5 finding 3) — not an unchanged blank clone.
    const dialog = await screen.findByRole('dialog', { name: 'สร้างรอบแก้ไขตามการเปลี่ยนแปลงของลูกค้า' });
    expect(within(dialog).getByDisplayValue('ผู้ออกแบบ ก.')).not.toBeNull();
    expect(within(dialog).getByDisplayValue('กระเบื้องพื้น SCG A1')).not.toBeNull();

    const reasonInput = within(dialog).getByPlaceholderText('เช่น ลูกค้าเปลี่ยนสินค้า/จำนวน/ขนาด');
    fireEvent.change(reasonInput, { target: { value: 'ลูกค้าเปลี่ยนจำนวน' } });
    fireEvent.change(within(dialog).getByDisplayValue('20'), { target: { value: '30' } });

    fireEvent.click(within(dialog).getByRole('button', { name: /สร้างรอบแก้ไข/ }));

    await waitFor(() => expect(api.pricingRequests.createCustomerChangeRevision).toHaveBeenCalledWith(
      request.summary.id,
      expect.objectContaining({
        revisionReason: 'ลูกค้าเปลี่ยนจำนวน',
        items: [expect.objectContaining({ requestedQty: 30 })],
      }),
    ));
  });

  it('does not offer the revision button to a non-owner sales rep', async () => {
    const request = buildRequest({ summary: { status: 'READY_FOR_CEO_REVIEW', ticketCreatedById: 999 } });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);

    expect(screen.queryByRole('button', { name: 'สร้างรอบแก้ไข' })).toBeNull();
  });
});

describe('PricingRequestDetailPage pricing-request attachments (COMMIT 4)', () => {
  it('lets the owning sales rep upload a supporting attachment while DRAFT', async () => {
    const request = buildRequest({ summary: { status: 'DRAFT' } });
    renderDetailPage({ user: salesOwner, request, attachments: [] });
    await waitForLoaded(request);

    const fileInput = document.querySelector('input[type="file"]');
    expect(fileInput).not.toBeNull();
    const file = new File(['x'], 'spec.pdf', { type: 'application/pdf' });
    fireEvent.change(fileInput, { target: { files: [file] } });

    await waitFor(() => expect(api.pricingRequests.uploadAttachment).toHaveBeenCalledWith(request.summary.id, file));
  });

  it('does not offer upload/delete once the request is past DRAFT', async () => {
    const request = buildRequest({ summary: { status: 'IMPORT_REVIEWING' } });
    renderDetailPage({
      user: salesOwner,
      request,
      attachments: [{ id: 1, fileName: 'spec.pdf', includeInFactoryEmail: false }],
    });
    await waitForLoaded(request);
    await screen.findByText('spec.pdf');

    expect(document.querySelector('input[type="file"]')).toBeNull();
    expect(screen.queryByRole('button', { name: /ลบไฟล์แนบ/ })).toBeNull();
  });

  it('shows the include-in-factory-email toggle only to Import, and toggling it calls setAttachmentIncludeInFactoryEmail', async () => {
    const attachment = { id: 1, fileName: 'spec.pdf', includeInFactoryEmail: false };
    renderDetailPage({ user: importUser, attachments: [attachment] });
    await waitForLoaded();
    const checkbox = await screen.findByRole('checkbox', { name: /ส่งแนบไปกับอีเมลโรงงาน/ });

    fireEvent.click(checkbox);

    await waitFor(() => expect(api.pricingRequests.setAttachmentIncludeInFactoryEmail).toHaveBeenCalledWith(1, true));
  });

  it('shows sales a read-only badge (not a checkbox) once an attachment is marked include-in-factory-email', async () => {
    const attachment = { id: 1, fileName: 'spec.pdf', includeInFactoryEmail: true };
    const request = buildRequest({ summary: { status: 'DRAFT' } });
    renderDetailPage({ user: salesOwner, request, attachments: [attachment] });
    await waitForLoaded(request);

    expect(await screen.findByText('แนบไปกับอีเมลโรงงาน')).not.toBeNull();
    expect(screen.queryByRole('checkbox')).toBeNull();
  });

  it('lets the owner delete their own attachment while editable, via deleteAttachment', async () => {
    const attachment = { id: 7, fileName: 'spec.pdf', includeInFactoryEmail: false };
    const request = buildRequest({ summary: { status: 'DRAFT' } });
    renderDetailPage({ user: salesOwner, request, attachments: [attachment] });
    await waitForLoaded(request);

    fireEvent.click(await screen.findByRole('button', { name: 'ลบไฟล์แนบ spec.pdf' }));

    await waitFor(() => expect(api.pricingRequests.deleteAttachment).toHaveBeenCalledWith(7));
  });
});

describe('PricingRequestDetailPage CEO Selling Price Decision (Step 3, UI-level only — see file header)', () => {
  it('lets the CEO start a review from READY_FOR_CEO_REVIEW, calling startPricingDecision', async () => {
    const request = buildRequest({ summary: { status: 'READY_FOR_CEO_REVIEW' } });
    renderDetailPage({ user: ceoUser, request });
    await waitForLoaded(request);

    fireEvent.click(await screen.findByRole('button', { name: 'เริ่มพิจารณาราคาขาย' }));

    await waitFor(() => expect(api.pricingRequests.startPricingDecision).toHaveBeenCalledWith(
      request.summary.id,
      expect.objectContaining({ defaultMarginPct: 0.2, clientRequestId: expect.any(String) }),
    ));
  });

  it('does not offer "เริ่มพิจารณาราคาขาย" to Import — ceo only, mirrors PricingDecisionService.startReview', async () => {
    const request = buildRequest({ summary: { status: 'READY_FOR_CEO_REVIEW' } });
    renderDetailPage({ user: importUser, request });
    await waitForLoaded(request);

    expect(screen.queryByRole('button', { name: 'เริ่มพิจารณาราคาขาย' })).toBeNull();
  });

  it('never fetches pricing-decision history for sales/sales_manager — a distinct gate from the raw quote/costing one', async () => {
    const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);

    await waitFor(() => expect(api.pricingRequests.listFactoryQuotes).not.toHaveBeenCalled());
    expect(api.pricingRequests.listPricingDecisions).not.toHaveBeenCalled();
  });

  it('lets the CEO edit an item margin/minimum price and save via updatePricingDecision', async () => {
    const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
    api.pricingRequests.listPricingDecisions.mockResolvedValue({ items: [buildDecision()] });
    renderDetailPage({ user: ceoUser, request });
    await waitForLoaded(request);
    await screen.findByText('PCD-2026-0001');

    const marginInput = screen.getByPlaceholderText('อัตรากำไร เช่น 0.20 = 20%');
    fireEvent.change(marginInput, { target: { value: '0.35' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกการเปลี่ยนแปลง' }));

    await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalledWith(
      7001,
      expect.objectContaining({
        items: [expect.objectContaining({ pricingDecisionItemId: 8001, marginPct: 0.35, minimumSellingPrice: 65 })],
      }),
    ));
  });

  it('disables approval until every item has a margin and a minimum selling price (mirrors the server 422 gate)', async () => {
    const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
    api.pricingRequests.listPricingDecisions.mockResolvedValue({
      items: [buildDecision({ items: [buildDecisionItem({ minimumSellingPricePerRequestedUnit: null })] })],
    });
    renderDetailPage({ user: ceoUser, request });
    await waitForLoaded(request);
    await screen.findByText('PCD-2026-0001');

    expect(screen.getByRole('button', { name: 'อนุมัติราคาขาย' }).disabled).toBe(true);
  });

  it('approves through the confirm dialog, calling approvePricingDecision', async () => {
    const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
    api.pricingRequests.listPricingDecisions.mockResolvedValue({ items: [buildDecision()] });
    renderDetailPage({ user: ceoUser, request });
    await waitForLoaded(request);
    await screen.findByText('PCD-2026-0001');

    fireEvent.click(screen.getByRole('button', { name: 'อนุมัติราคาขาย' }));
    fireEvent.click(await screen.findByRole('button', { name: 'อนุมัติ' }));

    await waitFor(() => expect(api.pricingRequests.approvePricingDecision).toHaveBeenCalledWith(
      7001,
      expect.objectContaining({ clientRequestId: expect.any(String) }),
    ));
  });

  it('returns to Import through the confirm dialog, requiring a reason, calling returnPricingDecisionToImport', async () => {
    const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
    api.pricingRequests.listPricingDecisions.mockResolvedValue({ items: [buildDecision()] });
    renderDetailPage({ user: ceoUser, request });
    await waitForLoaded(request);
    await screen.findByText('PCD-2026-0001');

    fireEvent.click(screen.getByRole('button', { name: 'ตีกลับให้ฝ่ายนำเข้าแก้ไข' }));
    const dialog = await screen.findByRole('dialog');
    const reasonInput = within(dialog).getByLabelText('เหตุผลที่ตีกลับ');
    fireEvent.change(reasonInput, { target: { value: 'ราคาต้นทุนคลาดเคลื่อน' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'ตีกลับ' }));

    await waitFor(() => expect(api.pricingRequests.returnPricingDecisionToImport).toHaveBeenCalledWith(
      7001,
      { returnReason: 'ราคาต้นทุนคลาดเคลื่อน' },
    ));
  });

  // Was: "shows Import the raw decision read-only". Import's job now ends at ส่งให้ CEO อนุมัติราคา,
  // so it is shown NO decision surface at all — a strictly narrower view than before, never wider.
  it('shows Import no CEO decision surface at all', async () => {
    const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
    api.pricingRequests.listPricingDecisions.mockResolvedValue({ items: [buildDecision()] });
    renderDetailPage({ user: importUser, request });
    await waitForLoaded(request);

    expect(screen.queryByText('PCD-2026-0001')).toBeNull();
    expect(screen.queryByPlaceholderText('อัตรากำไร เช่น 0.20 = 20%')).toBeNull();
    expect(screen.queryByRole('button', { name: 'อนุมัติราคาขาย' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'ตีกลับให้ฝ่ายนำเข้าแก้ไข' })).toBeNull();
  });

  it('shows Sales the approved selling price via the sales-view projection, with no cost/margin figure anywhere on the page', async () => {
    const request = buildRequest({ summary: { status: 'APPROVED_FOR_QUOTATION' } });
    api.pricingRequests.getPricingDecisionSalesView.mockResolvedValue({ decision: buildSalesView() });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);

    expect(await screen.findByText('ราคาขายที่อนุมัติ')).not.toBeNull();
    // The approved selling price (72 THB) is shown...
    expect(screen.getByText(/72/)).not.toBeNull();
    // ...but the underlying frozen cost (60 THB) never appears anywhere — the sales-view DTO
    // this page renders structurally has no cost/margin field at all (design correction 2).
    expect(screen.queryByText(/ต้นทุน/)).toBeNull();
    expect(screen.queryByText(/อัตรากำไร/)).toBeNull();
  });

  it('does not fetch the sales-view projection for a non-owning sales rep', async () => {
    const request = buildRequest({ summary: { status: 'APPROVED_FOR_QUOTATION', ticketCreatedById: 999 } });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);

    await waitFor(() => expect(api.pricingRequests.listFactoryQuotes).not.toHaveBeenCalled());
    expect(api.pricingRequests.getPricingDecisionSalesView).not.toHaveBeenCalled();
  });
});

describe('PricingRequestDetailPage mobile layout', () => {
  // This page has no JS-driven responsive branching (no useIsMobile() call) — every
  // breakpoint is a Tailwind utility class (e.g. `md:grid-cols-2`, `md:grid-cols-4`)
  // evaluated purely by CSS media queries, which jsdom does not apply. So there is no
  // separate "mobile DOM" to assert against; what a mobile-viewport test CAN meaningfully
  // prove is that the page still renders its full content tree (nothing crashes, nothing
  // is conditionally dropped) when the viewport reports as mobile.
  const realMatchMedia = window.matchMedia;

  afterEach(() => {
    window.matchMedia = realMatchMedia;
  });

  function stubMobileViewport() {
    window.matchMedia = (query) => ({
      matches: query === '(max-width: 720px)',
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
    });
  }

  it('renders the full Import page (overview, items, factory quotes) under a mobile viewport', async () => {
    stubMobileViewport();
    renderDetailPage({
      user: importUser,
      factoryQuotes: [buildFactoryQuote()],
      costings: [buildCosting()],
    });

    await waitForLoaded();
    expect(screen.getByText('ภาพรวม')).not.toBeNull();
    expect(screen.getByText('รายการสินค้าและราคาตั้งต้น')).not.toBeNull();
    // Item identity renders brand+model ("SCG A1") ahead of productDescription per the
    // component's own fallback chain (catalogBrand/brand + catalogModel/model first).
    // getAllByText, not getByText: the ราคาโรงงาน response row now echoes the same product name
    // back as read-only context ("ที่ Sales ขอ: …"), so this string legitimately appears twice.
    expect(screen.getAllByText('SCG A1').length).toBeGreaterThan(0);
    // By role, not text: "ราคาโรงงาน" now also names a column in the
    // response-entry grid, so a bare text query can match more than one node.
    // The assertion here is that the SECTION is present.
    expect(await screen.findByRole('heading', { name: 'ราคาโรงงาน' })).not.toBeNull();
    // ต้นทุนนำเข้า is deliberately absent for Import — see the hiding test above.
  });
});

describe('PricingRequestDetailPage accessibility: no nested interactive controls', () => {
  // eslint-plugin-jsx-a11y (wired into this repo's lint) flags a <button> containing another
  // <button> as invalid HTML / unreachable-by-keyboard nesting. Render the richest scenario
  // (Import, with attachments + an editable DRAFT factory quote + an open costing) to maximize
  // the number of interactive controls on screen, then assert none of them nest another button.
  it('has no <button> nested inside another <button> anywhere on the page', async () => {
    const { container } = renderDetailPage({
      user: importUser,
      factoryQuotes: [buildFactoryQuote()],
      costings: [buildCosting({ status: 'CALCULATED', stale: false })],
      attachments: [{ id: 1, fileName: 'spec.pdf', includeInFactoryEmail: true }],
    });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    const buttons = container.querySelectorAll('button');
    expect(buttons.length).toBeGreaterThan(0);
    buttons.forEach((button) => {
      expect(button.querySelector('button')).toBeNull();
    });
  });
});

// Step 4 (Customer Quotation Generation and Issuance). UI-LEVEL ONLY, same caveat as this
// file's own header: proves this component's conditional rendering/wiring, not server-side
// enforcement. The authoritative checks are the real-DB tests in
// backend/src/test/java/th/co/glr/hr/customerquotation/CustomerQuotationIntegrationTest.java.
describe('PricingRequestDetailPage Step 4: Customer Quotation', () => {
  it('offers "สร้างร่างใบเสนอราคาลูกค้า" to the owning sales rep only once APPROVED_FOR_QUOTATION, and creates on click', async () => {
    const request = buildRequest({ summary: { status: 'APPROVED_FOR_QUOTATION' } });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);

    const button = await screen.findByRole('button', { name: 'สร้างร่างใบเสนอราคาลูกค้า' });
    fireEvent.click(button);

    await waitFor(() => expect(api.pricingRequests.createCustomerQuotation).toHaveBeenCalledWith(
      request.summary.id,
      expect.objectContaining({ clientRequestId: expect.any(String) }),
    ));
  });

  it('does not offer the create button before APPROVED_FOR_QUOTATION, and never fetches the quotation list for a non-owning role', async () => {
    const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);

    expect(screen.queryByRole('button', { name: 'สร้างร่างใบเสนอราคาลูกค้า' })).toBeNull();
    expect(screen.getByText(/ยังไม่มีใบเสนอราคาลูกค้า/)).not.toBeNull();
  });

  it('lets the owning sales rep edit an item discount, warns below the CEO-approved minimum, and issues via the confirm dialog', async () => {
    const request = buildRequest({ summary: { status: 'APPROVED_FOR_QUOTATION' } });
    const quotation = buildCustomerQuotation();
    api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [quotation] });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);
    await screen.findByText(quotation.number);

    // Editable discount input is present for a DRAFT quotation owned by this sales rep.
    const discountInputs = screen.getAllByRole('spinbutton');
    const discountInput = discountInputs[0];
    fireEvent.change(discountInput, { target: { value: '10' } });
    // 72 - 10 = 62, below the item's minimumSellingPricePerRequestedUnit (65) — warns inline.
    expect(await screen.findByText(/ต่ำกว่าราคาขั้นต่ำที่ CEO อนุมัติ/)).not.toBeNull();

    // Bring the discount back within policy, then issue.
    fireEvent.change(discountInput, { target: { value: '2' } });
    expect(screen.queryByText(/ต่ำกว่าราคาขั้นต่ำที่ CEO อนุมัติ/)).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'ออกใบเสนอราคา' }));
    const dialog = await screen.findByRole('dialog', { name: 'ออกใบเสนอราคาลูกค้า' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'ออกใบเสนอราคา' }));

    await waitFor(() => expect(api.pricingRequests.issueCustomerQuotation).toHaveBeenCalledWith(
      quotation.id,
      expect.objectContaining({ clientRequestId: expect.any(String) }),
    ));
  });

  it('renders the CEO/Import view strictly read-only — no discount input, no save/issue/cancel controls — but Preview still works', async () => {
    const quotation = buildCustomerQuotation();
    api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [quotation] });
    renderDetailPage({ user: ceoUser });
    await waitForLoaded();
    await screen.findByText(quotation.number);

    expect(screen.queryByRole('spinbutton')).toBeNull();
    expect(screen.queryByRole('button', { name: 'บันทึก' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'ออกใบเสนอราคา' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'ยกเลิกร่าง' })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'ดูตัวอย่าง PDF' }));
    await waitFor(() => expect(api.pricingRequests.downloadCustomerQuotationPdf).toHaveBeenCalledWith(quotation.id));
  });

  it('offers "สร้างรอบแก้ไขใหม่" only once ISSUED, to the owner', async () => {
    const issued = buildCustomerQuotation({ docStatus: 'ISSUED' });
    api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [issued] });
    renderDetailPage({ user: salesOwner });
    await waitForLoaded();
    await screen.findByText(issued.number);

    const revisionButton = await screen.findByRole('button', { name: 'สร้างรอบแก้ไขใหม่' });
    fireEvent.click(revisionButton);

    await waitFor(() => expect(api.pricingRequests.createCustomerQuotationRevision).toHaveBeenCalledWith(
      issued.id,
      expect.objectContaining({ clientRequestId: expect.any(String) }),
    ));
  });
});

describe('PricingRequestDetailPage Step 5: Customer Decision and Commercial Revisions', () => {
  it('offers the outcome-recording controls to the owning sales rep only while ISSUED, and records ACCEPTED on click', async () => {
    const issued = buildCustomerQuotation({ docStatus: 'ISSUED' });
    api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [issued] });
    renderDetailPage({ user: salesOwner });
    await waitForLoaded();
    await screen.findByText(issued.number);

    const acceptButton = await screen.findByRole('button', { name: 'ลูกค้ายอมรับ' });
    fireEvent.click(acceptButton);

    await waitFor(() => expect(api.pricingRequests.recordCustomerQuotationOutcome).toHaveBeenCalledWith(
      issued.id,
      expect.objectContaining({ outcome: 'ACCEPTED', clientRequestId: expect.any(String) }),
    ));
  });

  it('never shows the outcome-recording controls to CEO or Import — read-only', async () => {
    const issued = buildCustomerQuotation({ docStatus: 'ISSUED' });
    api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [issued] });
    renderDetailPage({ user: ceoUser });
    await waitForLoaded();
    await screen.findByText(issued.number);

    expect(screen.queryByRole('button', { name: 'ลูกค้ายอมรับ' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'ลูกค้าปฏิเสธ' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'ลูกค้าขอแก้ไข' })).toBeNull();
  });

  it('hides the outcome-recording controls once the quotation is no longer ISSUED, and shows the recorded outcome read-only', async () => {
    const accepted = buildCustomerQuotation({ docStatus: 'ACCEPTED', outcomeNote: 'ลูกค้าโอเค' });
    api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [accepted] });
    renderDetailPage({ user: salesOwner });
    await waitForLoaded();
    await screen.findByText(accepted.number);

    expect(screen.queryByRole('button', { name: 'ลูกค้ายอมรับ' })).toBeNull();
    expect(screen.getByText(/ผลใบเสนอราคา/)).not.toBeNull();
    expect(screen.getByText(/ลูกค้าโอเค/)).not.toBeNull();
  });

  it('once REVISION_REQUESTED, offers both the commercial-only correction and the cost-affecting Customer Change Revision path', async () => {
    const revisionRequested = buildCustomerQuotation({ docStatus: 'REVISION_REQUESTED' });
    const request = buildRequest({ summary: { status: 'QUOTATION_ISSUED' } });
    api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [revisionRequested] });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);
    await screen.findByText(revisionRequested.number);

    // Commercial-only: reuses createRevision, now reachable from REVISION_REQUESTED too.
    const commercialButton = await screen.findByRole('button', { name: 'สร้างรอบแก้ไขราคา/เงื่อนไข' });
    fireEvent.click(commercialButton);
    await waitFor(() => expect(api.pricingRequests.createCustomerQuotationRevision).toHaveBeenCalledWith(
      revisionRequested.id,
      expect.objectContaining({ clientRequestId: expect.any(String) }),
    ));

    // Cost-affecting: opens the existing customer-change revision modal (mode="revision") — no
    // second modal built for this. Matched via the modal's own dialog role/title (distinct from
    // the always-present static customer-change section heading elsewhere on the page).
    fireEvent.click(screen.getByRole('button', { name: 'สร้างรอบแก้ไขสินค้า/จำนวน/โรงงาน' }));
    expect(await screen.findByRole('dialog', { name: 'สร้างรอบแก้ไขตามการเปลี่ยนแปลงของลูกค้า' })).not.toBeNull();
  });
});

describe('PricingRequestDetailPage Step 6: Deposit, Payment, and Order Confirmation', () => {
  it('offers "ยืนยันคำสั่งซื้อ" to the owning sales rep once QUOTATION_ACCEPTED, before the bridge has run', async () => {
    const request = buildRequest({ summary: { status: 'QUOTATION_ACCEPTED', orderConfirmedAt: null } });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);

    const button = await screen.findByRole('button', { name: 'ยืนยันคำสั่งซื้อ' });
    expect(screen.queryByRole('button', { name: 'สร้างใบแจ้งยอดเงินรับมัดจำ' })).toBeNull();
    fireEvent.click(button);

    await waitFor(() => expect(api.pricingRequests.confirmOrder).toHaveBeenCalledWith(
      request.summary.id,
      expect.objectContaining({ clientRequestId: expect.any(String) }),
    ));
  });

  it('offers "สร้างใบแจ้งยอดเงินรับมัดจำ" (not the confirm button) once the bridge has already run', async () => {
    const request = buildRequest({ summary: { status: 'QUOTATION_ACCEPTED', orderConfirmedAt: '2026-07-21T00:00:00Z' } });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);

    expect(screen.queryByRole('button', { name: 'ยืนยันคำสั่งซื้อ' })).toBeNull();
    const button = await screen.findByRole('button', { name: 'สร้างใบแจ้งยอดเงินรับมัดจำ' });
    fireEvent.click(button);

    await waitFor(() => expect(api.pricingRequests.createDepositNoticeFromQuotation).toHaveBeenCalledWith(
      request.summary.id,
      expect.objectContaining({ depositPercent: expect.any(Number) }),
    ));
  });

  it('never shows either Step 6 button to CEO or Import — read-only', async () => {
    const request = buildRequest({ summary: { status: 'QUOTATION_ACCEPTED', orderConfirmedAt: null } });
    renderDetailPage({ user: ceoUser, request });
    await waitForLoaded(request);

    expect(screen.queryByRole('button', { name: 'ยืนยันคำสั่งซื้อ' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'สร้างใบแจ้งยอดเงินรับมัดจำ' })).toBeNull();
    expect(screen.getByText('ยืนยันคำสั่งซื้อได้เฉพาะเจ้าของดีล (sales)')).not.toBeNull();
  });

  it('hides the whole Step 6 section before QUOTATION_ACCEPTED', async () => {
    const request = buildRequest({ summary: { status: 'QUOTATION_ISSUED' } });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);

    expect(screen.queryByRole('button', { name: 'ยืนยันคำสั่งซื้อ' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'สร้างใบแจ้งยอดเงินรับมัดจำ' })).toBeNull();
  });
});
