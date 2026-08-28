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
      recalculatePricingDecisionCost: vi.fn(),
      overridePricingDecisionItemCost: vi.fn(),
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
      // CEO discount-approval workflow, Phase 2 (V155).
      listDiscountApprovalsForQuotation: vi.fn(),
      approveDiscountApproval: vi.fn(),
      rejectDiscountApproval: vi.fn(),
      // Step 6: Deposit, Payment, and Order Confirmation.
      confirmOrder: vi.fn(),
      createDepositNoticeFromQuotation: vi.fn(),
    },
    catalog: {
      prices: vi.fn(),
    },
    meta: {
      unitBases: vi.fn(),
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

// V141 ("CEO owns costing", PR #702) fixture. Mirrors PricingCostingItemDto's override/provenance
// fields — see PricingCostingDtos.java. Deliberately a DIFFERENT id (5001, not buildCosting's
// default 211) and a DIFFERENT landedCostPerUnitThb (55, not buildDecisionItem's
// frozenLandedCostPerRequestedUnitThb of 60) — a test pairing this with
// buildDecisionItem({ pricingCostingItemId: 5001 }) exercises the REAL join
// (costing.items.find(ci => ci.id === decisionItem.pricingCostingItemId)) rather than an
// accidental id/number collision with buildCosting's own default fixture.
function buildCostingItemWithOverride(overrides = {}) {
  return {
    id: 5001,
    factoryName: 'SCG Ceramics',
    factoryQuoteRevisionNo: 1,
    rawUnitPrice: 45,
    rawCurrency: 'THB',
    landedCostPerUnitThb: 55,
    normalizedQuantityPieces: 20,
    fxRate: 1,
    fxSource: 'THB',
    calculationConfigVersion: 1,
    manualLandedCostPerUnitThb: null,
    overrideReason: null,
    overriddenBy: null,
    overriddenAt: null,
    overrideFxRate: null,
    overrideCalcConfigVersion: null,
    overrideStale: false,
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
  // Nothing on the page calls these any more — submitToCeo stopped chaining them in PR #760, and
  // #747 deleted the last four controls that drove them. They stay mocked ONLY so the
  // "must not come back" assertions below have something that would record a call if one happened;
  // an unmocked vi.fn() would throw instead of recording, which is a worse failure to read.
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
  api.pricingRequests.recalculatePricingDecisionCost.mockResolvedValue({});
  api.pricingRequests.overridePricingDecisionItemCost.mockResolvedValue({});
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
  // Mirrors UnitBasisMetaController's GET /api/meta/unit-bases — the backend catalog the
  // factory-quote response unit select is built from at runtime (item 3 of this task's brief).
  api.meta.unitBases.mockResolvedValue({
    unitBases: [
      { code: 'PER_PIECE', label: 'แผ่น' },
      { code: 'PER_SQM', label: 'ตร.ม.' },
      { code: 'PER_BOX', label: 'กล่อง' },
      { code: 'PER_LINEAR_M', label: 'เมตร' },
    ],
  });
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

// CEO discount-approval workflow, Phase 2 (V155). Mirrors DiscountApprovalDtos.DiscountApprovalDto.
function buildDiscountApproval(overrides = {}) {
  return {
    id: 3001,
    quotationItemId: 9001,
    quotationId: 5501,
    pricingRequestId: 501,
    quotationNumber: 'QT-2026-0001',
    itemDescription: 'กระเบื้องพื้น SCG A1',
    status: 'PENDING',
    requestedFinalUnitPrice: 62,
    requestedBy: 1,
    requestedByName: 'พนักงานขาย',
    requestedAt: '2026-08-17T00:00:00Z',
    decidedBy: null,
    decidedByName: null,
    decidedAt: null,
    approvedFinalUnitPrice: null,
    rejectionReason: null,
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
    minimumSellingPricePerRequestedUnit: 65,
    decisionNote: null,
    // Phase 1 UI simplification ("ปรับราคาเอง") — no override by default.
    manualSellingPricePerRequestedUnit: null,
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
  // CEO discount-approval workflow, Phase 2: defaults to empty like every other list query here
  // (listFactoryQuotes/listCostings/listAttachments) so a test that doesn't care about this
  // feature never has to know it exists — only tests exercising it pass discountApprovals.
  discountApprovals = [],
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
  api.pricingRequests.listDiscountApprovalsForQuotation.mockResolvedValue({ items: discountApprovals });

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
    // By role and a prefix regex, not exact text: the section header is now
    // "รายการสินค้า (N รายการ)" (owner-supplied mockup, factory-price-import-ui redesign) with a
    // dynamic count, so a bare/exact text query can never match it. The assertion here is that the
    // SECTION is present.
    expect(await screen.findByRole('heading', { name: /^รายการสินค้า \(/ })).not.toBeNull();
    expect(screen.getByText('ต้นทุนนำเข้า')).not.toBeNull();
    expect(screen.getByText('SCG Ceramics')).not.toBeNull();
    expect(screen.getByText('COST-2026-0001')).not.toBeNull();

    // But every mutating control on the factory-quote panel is Import-only (isImport(user)) and
    // must be absent for CEO. The three costing ones (สร้างร่างต้นทุน / คำนวณใหม่ / ส่งให้ CEO ตรวจ)
    // are absent for a stronger reason since #747: they no longer exist for any role.
    expect(screen.queryByRole('button', { name: 'สร้างร่างอีเมล' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'สร้างร่างต้นทุน' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'ส่ง' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'ส่งอีกครั้ง' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'พร้อมคำนวณต้นทุน' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'เจรจา' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'คำนวณใหม่' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'ส่งให้ CEO ตรวจ' })).toBeNull();
    // The two new per-factory-group actions (factory-price-import-ui redesign) are Import-only too.
    expect(screen.queryByRole('button', { name: 'ร่างอีเมล' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'ยืนยันราคาเสนอ' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'ยกเลิก' })).toBeNull();
    // No editable email-draft or response-entry form fields either. Queried by
    // accessible name, not placeholder: these fields carry real labels now, so
    // a placeholder query would report "absent" for a field that is present and
    // simply has no placeholder — an assertion that passes for the wrong reason.
    expect(screen.queryByLabelText('อีเมลโรงงาน')).toBeNull();
    expect(screen.queryByLabelText(/^ราคาที่เสนอ/)).toBeNull();
    // The per-factory currency/unit controls are read-only text for CEO, not a live select.
    expect(screen.queryByLabelText('สกุลเงิน')).toBeNull();
    expect(screen.queryByLabelText('หน่วยราคา')).toBeNull();
  });
});

describe('PricingRequestDetailPage Import factory-quote workflow', () => {
  it('lets Import edit the factory email draft before sending, and saves it via updateFactoryQuote', async () => {
    const quote = buildFactoryQuote();
    renderDetailPage({ user: importUser, factoryQuotes: [quote] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    // The To/Subject/Body composer moved into a modal behind the factory group's own "ร่างอีเมล"
    // button (factory-price-import-ui redesign) — open it before looking for the fields.
    fireEvent.click(screen.getByRole('button', { name: 'ร่างอีเมล' }));
    const dialog = await screen.findByRole('dialog', { name: 'ร่างอีเมลถึงโรงงาน' });

    const toInput = within(dialog).getByLabelText('อีเมลโรงงาน');
    const subjectInput = within(dialog).getByLabelText('หัวข้ออีเมล');
    const bodyInput = within(dialog).getByLabelText('เนื้อหาอีเมล');

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
    // Several client-request ids are minted once on mount (useState(() => generateClientRequestId())),
    // all unrelated to the send flow under test — baseline off whatever mount produced rather than
    // asserting an absolute count, which would be a hostage to how many the page happens to hold.
    // (One of them, costingClientRequestId, went away with #747; this baseline absorbed that.)
    const callsBeforeAnySend = uuidSpy.mock.calls.length;

    // The To/Subject/Body composer + ส่งแล้ว now live behind the factory group's ร่างอีเมล modal.
    // Requesting send closes THAT modal before opening the shared ConfirmDialog (one focus-trapped
    // dialog at a time — see FactoryEmailDraftModal's own doc comment), so each attempt below
    // reopens ร่างอีเมล first. "ยกเลิก" alone would now also match this quote's own (unrelated)
    // discard-edits button in the item-price grid, so the ConfirmDialog's is scoped with `within`.

    // First open: mints and caches a clientRequestId for this quote.
    fireEvent.click(screen.getByRole('button', { name: 'ร่างอีเมล' }));
    await screen.findByRole('dialog', { name: 'ร่างอีเมลถึงโรงงาน' });
    fireEvent.click(screen.getByRole('button', { name: 'ส่งแล้ว' }));
    const firstConfirmDialog = await screen.findByRole('dialog', { name: 'ส่งอีเมลถึงโรงงาน' });
    expect(firstConfirmDialog).not.toBeNull();
    expect(uuidSpy).toHaveBeenCalledTimes(callsBeforeAnySend + 1);

    // Cancel without confirming, reopen ร่างอีเมล, request send again: must reuse the cached id.
    fireEvent.click(within(firstConfirmDialog).getByRole('button', { name: 'ยกเลิก' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'ส่งอีเมลถึงโรงงาน' })).toBeNull());
    fireEvent.click(screen.getByRole('button', { name: 'ร่างอีเมล' }));
    await screen.findByRole('dialog', { name: 'ร่างอีเมลถึงโรงงาน' });
    fireEvent.click(screen.getByRole('button', { name: 'ส่งแล้ว' }));
    const secondConfirmDialog = await screen.findByRole('dialog', { name: 'ส่งอีเมลถึงโรงงาน' });
    expect(secondConfirmDialog).not.toBeNull();
    // Still no new call — reused the cached id, not regenerated.
    expect(uuidSpy).toHaveBeenCalledTimes(callsBeforeAnySend + 1);

    fireEvent.click(within(secondConfirmDialog).getByRole('button', { name: 'ส่งอีเมล' }));

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
    const priceInput = screen.getByLabelText(/^ราคาที่เสนอ/);
    fireEvent.change(priceInput, { target: { value: '55.5' } });

    // ยืนยันราคาเสนอ (factory-price-import-ui redesign) replaces บันทึกคำตอบ/รอบแก้ไข: a REQUESTED
    // quote has no response on file yet, so confirming calls receiveFactoryQuote (this assertion)
    // and then markFactoryQuoteReady — see confirmFactoryQuote's own doc comment.
    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));

    await waitFor(() => expect(api.pricingRequests.receiveFactoryQuote).toHaveBeenCalledWith(
      quote.id,
      expect.objectContaining({
        supplierQuoteRef: null,
        clientRequestId: expect.stringMatching(/^[0-9a-f-]{36}$/i),
        items: [expect.objectContaining({ pricingRequestItemId: 1, rawUnitPrice: 55.5 })],
      }),
    ));
    // ...then marks the (in-place-updated, same-id) quote ready for the CEO — one click, both
    // calls, ending at READY_FOR_COSTING as the task brief specifies.
    await waitFor(() => expect(api.pricingRequests.markFactoryQuoteReady).toHaveBeenCalledWith(quote.id));
    // The removed fields must not be resurrected as inputs.
    expect(screen.queryByLabelText('เลขอ้างอิงใบเสนอราคา')).toBeNull();
    expect(screen.queryByLabelText('เงื่อนไขการชำระเงิน')).toBeNull();
    expect(screen.queryByLabelText('ระยะเวลาผลิต/ส่งมอบ')).toBeNull();
  });

  // Reported from UAT: two items of the same model in different sizes rendered identically, so
  // Import could not tell which price box belonged to which item.
  it('shows size and colour/texture in the row summary, not just brand + model', async () => {
    const baseItem = buildRequest().items[0];
    const request = buildRequest({ items: [{ ...baseItem, color: 'ขาว' }] });
    const quote = buildFactoryQuote({ status: 'REQUESTED' });
    renderDetailPage({ user: importUser, request, factoryQuotes: [quote] });
    await waitForLoaded(request);
    await screen.findByText('SCG Ceramics');

    // size (60x60) · color (ขาว) · texture (ด้าน) — compact, on their own line under brand/model.
    expect(screen.getByText('60x60 · ขาว · ด้าน')).toBeTruthy();
  });

  // THE SEED BUG. FactoryQuoteRepository.insertDraftItems seeds a fresh quote item's quotedUnit
  // from the REQUEST's own requested_unit (real text, e.g. ตร.ม.) and unitBasis from a basis guess
  // — the two are already different on a brand-new draft. The old defaultResponseItems used ONE
  // variable to seed both fields, so the real unit was thrown away in favour of the basis code
  // before Import ever saw the form. This is the mutation-checked test: reverting the fix (sharing
  // one variable again) must turn it red.
  it('seeds quotedUnit from the real unit Sales requested, not the basis code, when Import has not touched it', async () => {
    const quote = buildFactoryQuote({
      status: 'REQUESTED',
      items: [{
        id: 911,
        pricingRequestItemId: 1,
        supplierProductCode: '',
        supplierProductDescription: '',
        quotedQuantity: 20,
        quotedUnit: 'ตร.ม.',
        unitBasis: 'PER_SQM',
        rawUnitPrice: null,
        currency: 'THB',
        sqmPerUnit: null,
      }],
    });
    renderDetailPage({ user: importUser, factoryQuotes: [quote] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    fireEvent.change(screen.getByLabelText(/^ราคาที่เสนอ/), { target: { value: '55.5' } });
    // Ditto for PER_SQM/sqmPerUnit — this line's basis has always required the factor.
    fireEvent.change(screen.getByLabelText('ตร.ม./หน่วย รายการ #1'), { target: { value: '1.2' } });
    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));

    await waitFor(() => expect(api.pricingRequests.receiveFactoryQuote).toHaveBeenCalledWith(
      quote.id,
      expect.objectContaining({
        items: [expect.objectContaining({ quotedUnit: 'ตร.ม.', unitBasis: 'PER_SQM' })],
      }),
    ));
  });

  // Item 3 of this task's brief: the unit select is built from GET /api/meta/unit-bases at
  // runtime, not a hardcoded list, and changing it writes both unitBasis and quotedUnit together
  // (the same "one select, two fields" pattern PricingRequestCreateModal's updateUnitBasis uses).
  it('offers the unit select built from the backend catalog, and changing it updates both unitBasis and quotedUnit on save', async () => {
    const quote = buildFactoryQuote({ status: 'REQUESTED' }); // default item: unitBasis PER_PIECE
    renderDetailPage({ user: importUser, factoryQuotes: [quote] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    // หน่วยราคา (factory-price-import-ui redesign): one select per FACTORY GROUP now, not per line
    // — every line in the group shares it — but still built from the same backend catalog.
    const unitSelect = await screen.findByLabelText(/^หน่วย/);
    expect(within(unitSelect).getAllByRole('option').map((option) => option.value)).toEqual([
      'PER_PIECE', 'PER_SQM', 'PER_BOX', 'PER_LINEAR_M',
    ]);

    fireEvent.change(unitSelect, { target: { value: 'PER_BOX' } });
    fireEvent.change(screen.getByLabelText(/^ราคาที่เสนอ/), { target: { value: '10' } });
    // Switching to PER_BOX brings the ชิ้น/กล่อง input with it, and the save is now blocked until
    // it is filled — FactoryQuoteService has always 422'd a PER_BOX line with a null piecesPerBox,
    // so this test used to assert a payload production would have rejected.
    fireEvent.change(screen.getByLabelText('ชิ้น/กล่อง รายการ #1'), { target: { value: '6' } });
    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));

    await waitFor(() => expect(api.pricingRequests.receiveFactoryQuote).toHaveBeenCalledWith(
      quote.id,
      expect.objectContaining({
        items: [expect.objectContaining({ unitBasis: 'PER_BOX', quotedUnit: 'กล่อง' })],
      }),
    ));
  });

  // The ตร.ม./หน่วย input this task's brief listed as already shipped, but which was not present
  // on origin/main — FactoryQuoteService requires sqmPerUnit for any PER_SQM line
  // (validateAndNormalizeResponseItems:727) and there was no way for Import to supply it.
  it('shows the ตร.ม./หน่วย input only for a PER_SQM line, and includes it in the saved payload', async () => {
    const quote = buildFactoryQuote({
      status: 'REQUESTED',
      items: [{
        id: 911,
        pricingRequestItemId: 1,
        supplierProductCode: '',
        supplierProductDescription: '',
        quotedQuantity: 20,
        quotedUnit: 'ตร.ม.',
        unitBasis: 'PER_SQM',
        rawUnitPrice: null,
        currency: 'THB',
        sqmPerUnit: null,
      }],
    });
    renderDetailPage({ user: importUser, factoryQuotes: [quote] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    const sqmInput = screen.getByLabelText(/^ตร\.ม\.\/หน่วย/);
    fireEvent.change(sqmInput, { target: { value: '0.36' } });
    fireEvent.change(screen.getByLabelText(/^ราคาที่เสนอ/), { target: { value: '120' } });
    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));

    await waitFor(() => expect(api.pricingRequests.receiveFactoryQuote).toHaveBeenCalledWith(
      quote.id,
      expect.objectContaining({ items: [expect.objectContaining({ sqmPerUnit: 0.36 })] }),
    ));
  });

  it('does not show the ตร.ม./หน่วย input for a PER_PIECE line', async () => {
    const quote = buildFactoryQuote({ status: 'REQUESTED' }); // default item: unitBasis PER_PIECE
    renderDetailPage({ user: importUser, factoryQuotes: [quote] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    expect(screen.queryByLabelText(/^ตร\.ม\.\/หน่วย/)).toBeNull();
  });

  // ── The reported defect: an unsubmittable factory response ────────────────────────────────
  // sales.factory_quote_item.unit_basis is seeded PER LINE from that line's own
  // pricing_request_item.requested_unit_basis (FactoryQuoteRepository#insertDraftItems), so one
  // factory quote can mix bases. The form used to read ONE basis off draft.items[0] and gate the
  // conversion-factor input on it, so a PER_SQM line sitting behind a PER_PIECE line rendered no
  // ตร.ม./หน่วย input at all — while FactoryQuoteService kept 422ing the save for the sqmPerUnit
  // that line needs. Import could read the error and had no field to answer it with.
  //
  // Wrong-way-round on purpose: this asserts the input EXISTS for the line whose basis needs it,
  // which is exactly what the old first-line-wins gate could not do.
  const mixedBasisRequest = buildRequest({
    items: [
      {
        id: 1, brand: 'SCG', model: 'A1', productDescription: 'กระเบื้องพื้น SCG A1',
        requestedQty: 20, requestedUnit: 'แผ่น', requestedUnitBasis: 'PER_PIECE',
        resolvedFactoryName: 'SCG Ceramics', quantityType: 'CONFIRMED',
      },
      {
        id: 2, brand: 'SCG', model: 'SLAB9', productDescription: 'แผ่นใหญ่ SCG SLAB9',
        requestedQty: 30, requestedUnit: 'ตร.ม.', requestedUnitBasis: 'PER_SQM',
        resolvedFactoryName: 'SCG Ceramics', quantityType: 'CONFIRMED',
      },
    ],
  });

  function mixedBasisQuote() {
    return buildFactoryQuote({
      status: 'REQUESTED',
      items: [
        {
          id: 911, pricingRequestItemId: 1, supplierProductCode: '', supplierProductDescription: '',
          quotedQuantity: 20, quotedUnit: 'แผ่น', unitBasis: 'PER_PIECE',
          rawUnitPrice: null, currency: 'THB', sqmPerUnit: null,
        },
        {
          id: 912, pricingRequestItemId: 2, supplierProductCode: '', supplierProductDescription: '',
          quotedQuantity: 30, quotedUnit: 'ตร.ม.', unitBasis: 'PER_SQM',
          rawUnitPrice: null, currency: 'THB', sqmPerUnit: null,
        },
      ],
    });
  }

  it('shows the ตร.ม./หน่วย input for a PER_SQM line even when a PER_PIECE line comes first', async () => {
    const quote = mixedBasisQuote();
    renderDetailPage({ user: importUser, request: mixedBasisRequest, factoryQuotes: [quote] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    // Only the PER_SQM line gets one — the PER_PIECE line above it still needs no factor.
    expect(screen.getByLabelText('ตร.ม./หน่วย รายการ #2')).toBeTruthy();
    expect(screen.queryByLabelText('ตร.ม./หน่วย รายการ #1')).toBeNull();

    fireEvent.change(screen.getByLabelText('ตร.ม./หน่วย รายการ #2'), { target: { value: '1.44' } });
    fireEvent.change(screen.getByLabelText('ราคาที่เสนอ รายการ #1'), { target: { value: '50' } });
    fireEvent.change(screen.getByLabelText('ราคาที่เสนอ รายการ #2'), { target: { value: '900' } });
    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));

    await waitFor(() => expect(api.pricingRequests.receiveFactoryQuote).toHaveBeenCalledWith(
      quote.id,
      expect.objectContaining({
        items: [
          expect.objectContaining({ pricingRequestItemId: 1, sqmPerUnit: null }),
          expect.objectContaining({ pricingRequestItemId: 2, sqmPerUnit: 1.44 }),
        ],
      }),
    ));
  });

  it('reports the mixed unit basis instead of letting the first line speak for the factory', async () => {
    renderDetailPage({ user: importUser, request: mixedBasisRequest, factoryQuotes: [mixedBasisQuote()] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    expect(screen.getByLabelText('หน่วยราคา').value).toBe('');
    expect(screen.getByRole('option', { name: 'คละหน่วย' })).toBeTruthy();
  });

  // The guard has to name the LINE and the on-screen field: "รายการตอบกลับแบบ PER_SQM ต้องระบุ
  // sqmPerUnit" (what the server used to answer, and still answers if this is bypassed) names a
  // basis code and a Java field, neither of which appears on the screen that raised it.
  it('blocks the save and names the item when a line still has no conversion factor', async () => {
    const { showToast } = renderDetailPage({
      user: importUser, request: mixedBasisRequest, factoryQuotes: [mixedBasisQuote()],
    });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    fireEvent.change(screen.getByLabelText('ราคาที่เสนอ รายการ #2'), { target: { value: '900' } });
    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));

    await waitFor(() => expect(showToast).toHaveBeenCalledWith(
      'error', expect.stringContaining('ระบุ ตร.ม./หน่วย ของ SCG SLAB9'),
    ));
    expect(api.pricingRequests.receiveFactoryQuote).not.toHaveBeenCalled();

    // Zero is refused as well as blank — deliberately stricter than the backend's null check here,
    // because LandedCostCalculator#requireFactor rejects <= 0 at costing time anyway, and bouncing
    // it there would surface the same mistake to a different person days later.
    fireEvent.change(screen.getByLabelText('ตร.ม./หน่วย รายการ #2'), { target: { value: '0' } });
    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));
    expect(api.pricingRequests.receiveFactoryQuote).not.toHaveBeenCalled();
  });

  // ── Catalog prefill ───────────────────────────────────────────────────────────────────────
  // price_catalog.product_prices already stores the geometry Import was typing off the factory's
  // packing list (V153 derives sqm_per_piece from sqm_per_box/pcs_per_box, falling back to
  // width_mm x height_mm / 1e6). PricingRequestRepository#findItems LEFT JOINs it onto the item as
  // catalogSqmPerPiece/catalogPcsPerBox; this is the form using it.
  it('prefills ตร.ม./หน่วย from the catalog when the quote item has none', async () => {
    const request = buildRequest({
      items: [{
        id: 1, brand: 'SCG', model: 'A1', productDescription: 'กระเบื้องพื้น SCG A1',
        requestedQty: 30, requestedUnit: 'ตร.ม.', requestedUnitBasis: 'PER_SQM',
        resolvedFactoryName: 'SCG Ceramics', quantityType: 'CONFIRMED',
        catalogSqmPerPiece: 0.36, catalogPcsPerBox: 4,
      }],
    });
    const quote = buildFactoryQuote({
      status: 'REQUESTED',
      items: [{
        id: 911, pricingRequestItemId: 1, supplierProductCode: '', supplierProductDescription: '',
        quotedQuantity: 30, quotedUnit: 'ตร.ม.', unitBasis: 'PER_SQM',
        rawUnitPrice: null, currency: 'THB', sqmPerUnit: null,
      }],
    });
    renderDetailPage({ user: importUser, request, factoryQuotes: [quote] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    expect(screen.getByLabelText('ตร.ม./หน่วย รายการ #1').value).toBe('0.36');

    // Import types only the price now — the save carries the prefilled factor with no extra input.
    fireEvent.change(screen.getByLabelText(/^ราคาที่เสนอ/), { target: { value: '900' } });
    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));

    await waitFor(() => expect(api.pricingRequests.receiveFactoryQuote).toHaveBeenCalledWith(
      quote.id,
      expect.objectContaining({ items: [expect.objectContaining({ sqmPerUnit: 0.36 })] }),
    ));
  });

  it('keeps what Import already entered over the catalog value', async () => {
    const request = buildRequest({
      items: [{
        id: 1, brand: 'SCG', model: 'A1', requestedQty: 30, requestedUnit: 'ตร.ม.',
        requestedUnitBasis: 'PER_SQM', resolvedFactoryName: 'SCG Ceramics',
        quantityType: 'CONFIRMED', catalogSqmPerPiece: 0.36,
      }],
    });
    const quote = buildFactoryQuote({
      status: 'RESPONSE_RECEIVED',
      items: [{
        id: 911, pricingRequestItemId: 1, supplierProductCode: '', supplierProductDescription: '',
        quotedQuantity: 30, quotedUnit: 'ตร.ม.', unitBasis: 'PER_SQM',
        rawUnitPrice: 900, currency: 'THB', sqmPerUnit: 0.4,
      }],
    });
    renderDetailPage({ user: importUser, request, factoryQuotes: [quote] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    expect(screen.getByLabelText('ตร.ม./หน่วย รายการ #1').value).toBe('0.4');
  });

  // Scoped to the factor the line's basis requires: a PER_PIECE line prices correctly today with
  // no factor, and writing one onto it would change what LandedCostCalculator resolves for it.
  it('does not write a catalog factor onto a line whose basis needs none', async () => {
    const request = buildRequest({
      items: [{
        id: 1, brand: 'SCG', model: 'A1', requestedQty: 20, requestedUnit: 'แผ่น',
        requestedUnitBasis: 'PER_PIECE', resolvedFactoryName: 'SCG Ceramics',
        quantityType: 'CONFIRMED', catalogSqmPerPiece: 0.36, catalogPcsPerBox: 4,
      }],
    });
    const quote = buildFactoryQuote({ status: 'REQUESTED' }); // default item: PER_PIECE
    renderDetailPage({ user: importUser, request, factoryQuotes: [quote] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    fireEvent.change(screen.getByLabelText(/^ราคาที่เสนอ/), { target: { value: '50' } });
    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));

    await waitFor(() => expect(api.pricingRequests.receiveFactoryQuote).toHaveBeenCalledWith(
      quote.id,
      expect.objectContaining({
        items: [expect.objectContaining({ sqmPerUnit: null, piecesPerBox: null })],
      }),
    ));
  });

  it('brings the catalog factor in when Import switches หน่วยราคา to one that needs it', async () => {
    const request = buildRequest({
      items: [{
        id: 1, brand: 'SCG', model: 'A1', requestedQty: 20, requestedUnit: 'แผ่น',
        requestedUnitBasis: 'PER_PIECE', resolvedFactoryName: 'SCG Ceramics',
        quantityType: 'CONFIRMED', catalogSqmPerPiece: 0.36, catalogPcsPerBox: 4,
      }],
    });
    const quote = buildFactoryQuote({ status: 'REQUESTED' }); // default item: PER_PIECE
    renderDetailPage({ user: importUser, request, factoryQuotes: [quote] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    fireEvent.change(await screen.findByLabelText(/^หน่วย/), { target: { value: 'PER_BOX' } });
    expect(screen.getByLabelText('ชิ้น/กล่อง รายการ #1').value).toBe('4');

    fireEvent.change(screen.getByLabelText(/^ราคาที่เสนอ/), { target: { value: '480' } });
    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));

    await waitFor(() => expect(api.pricingRequests.receiveFactoryQuote).toHaveBeenCalledWith(
      quote.id,
      expect.objectContaining({ items: [expect.objectContaining({ piecesPerBox: 4 })] }),
    ));
  });

  // PER_BOX had piecesPerBox in the payload but no input; PER_LINEAR_M had neither, so its
  // linearMPerUnit key was never even sent. Both 422'd with no way for Import to answer.
  it.each([
    ['PER_BOX', 'ชิ้น/กล่อง', 'piecesPerBox', '12', 12],
    ['PER_LINEAR_M', 'เมตร/หน่วย', 'linearMPerUnit', '0.09', 0.09],
  ])('offers the conversion-factor input for a %s line and sends it', async (unitBasis, label, field, typed, sent) => {
    const quote = buildFactoryQuote({
      status: 'REQUESTED',
      items: [{
        id: 911, pricingRequestItemId: 1, supplierProductCode: '', supplierProductDescription: '',
        quotedQuantity: 20, quotedUnit: 'กล่อง', unitBasis,
        rawUnitPrice: null, currency: 'THB', sqmPerUnit: null,
      }],
    });
    renderDetailPage({ user: importUser, factoryQuotes: [quote] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    fireEvent.change(screen.getByLabelText(`${label} รายการ #1`), { target: { value: typed } });
    fireEvent.change(screen.getByLabelText(/^ราคาที่เสนอ/), { target: { value: '480' } });
    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));

    await waitFor(() => expect(api.pricingRequests.receiveFactoryQuote).toHaveBeenCalledWith(
      quote.id,
      expect.objectContaining({ items: [expect.objectContaining({ [field]: sent })] }),
    ));
  });
});

describe('PricingRequestDetailPage Import costing workflow', () => {
  // Owner ruling 2026-08-11: Import keys in the price and submits — it never touches the costing
  // aggregate. The old per-step buttons (คำนวณใหม่ / ส่งให้ CEO ตรวจ, and the สร้างร่างต้นทุน that
  // preceded them) are gone; ONE button does the hand-off.
  //
  // This test used to assert a four-call chain in invocation ORDER
  // (markReady -> createCosting -> recalculate -> submit). V141/PR #702 severed the last three —
  // PricingCostingService's createDraft/recalculate/submit are @Deprecated shells that throw
  // 409 COSTING_MOVED_TO_CEO — but this suite mocks `api`, so every severed call resolved happily
  // and the test stayed green over a button that always errored in production (issue #729). The
  // assertions are now wrong-way-round on purpose: the severed calls must NOT be made.
  it('sends the factory quote to the CEO with markFactoryQuoteReady alone, and touches no severed costing endpoint', async () => {
    const quote = buildFactoryQuote({ status: 'RESPONSE_RECEIVED' });
    renderDetailPage({ user: importUser, factoryQuotes: [quote] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    // ยืนยันราคาเสนอ (factory-price-import-ui redesign) replaces ส่งให้ CEO อนุมัติราคา. A response is
    // already on file (RESPONSE_RECEIVED) and nothing was edited this session, so confirmFactoryQuote
    // must skip receiveFactoryQuote entirely — see its own doc comment for why an unconditional
    // receive() call here would be wrong, not merely redundant (it would spuriously bump the
    // revision and notify the CEO of a "revision" that never happened).
    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));

    await waitFor(() => expect(api.pricingRequests.markFactoryQuoteReady).toHaveBeenCalledWith(quote.id));
    expect(api.pricingRequests.receiveFactoryQuote).not.toHaveBeenCalled();
    expect(api.pricingRequests.createCosting).not.toHaveBeenCalled();
    expect(api.pricingRequests.recalculateCosting).not.toHaveBeenCalled();
    expect(api.pricingRequests.submitCosting).not.toHaveBeenCalled();
  });

  // The regression the bug report called out as "clicking again is worse": on the old code a quote
  // already at READY_FOR_COSTING re-rendered the button, skipped step 1, and fired a pure 409.
  // markFactoryQuoteReady is not idempotent — markReady's UPDATE matches zero rows on an
  // already-ready quote and the service 409s — so the action must not be offered there at all
  // while nothing has been edited (an edit re-opens it — see the next test).
  it('does not offer ยืนยันราคาเสนอ on an untouched quote that is already READY_FOR_COSTING', async () => {
    renderDetailPage({ user: importUser, factoryQuotes: [buildFactoryQuote({ status: 'READY_FOR_COSTING' })] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    expect(screen.queryByRole('button', { name: 'ยืนยันราคาเสนอ' })).toBeNull();
  });

  // An edit on an already-READY_FOR_COSTING quote (Import revising an already-sent price) DOES
  // re-offer ยืนยันราคาเสนอ — confirming it must go through receiveFactoryQuote again (a genuine
  // revision, matching FactoryQuoteService.receive's own supersede-and-create-new-row branch for
  // this exact status) before re-marking ready.
  it('re-offers ยืนยันราคาเสนอ once Import edits an already-READY_FOR_COSTING quote, and revises through receiveFactoryQuote', async () => {
    const quote = buildFactoryQuote({
      status: 'READY_FOR_COSTING',
      items: [{
        id: 911, pricingRequestItemId: 1, supplierProductCode: '', supplierProductDescription: '',
        quotedQuantity: 20, quotedUnit: 'PER_PIECE', unitBasis: 'PER_PIECE', rawUnitPrice: 50, currency: 'THB', sqmPerUnit: null,
      }],
    });
    api.pricingRequests.receiveFactoryQuote.mockResolvedValue({ factoryQuote: { ...quote, id: 92, revisionNo: 2 } });
    renderDetailPage({ user: importUser, factoryQuotes: [quote] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    expect(screen.queryByRole('button', { name: 'ยืนยันราคาเสนอ' })).toBeNull();
    fireEvent.change(screen.getByLabelText(/^ราคาที่เสนอ/), { target: { value: '48' } });
    expect(await screen.findByRole('button', { name: 'ยืนยันราคาเสนอ' })).not.toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));

    await waitFor(() => expect(api.pricingRequests.receiveFactoryQuote).toHaveBeenCalledWith(
      quote.id,
      expect.objectContaining({ items: [expect.objectContaining({ rawUnitPrice: 48 })] }),
    ));
    // markReady is called on the NEW revision id receiveFactoryQuote resolved to, not the
    // now-superseded original — the exact bug the old two-button flow could not hit (it never
    // chained these two calls together at all).
    await waitFor(() => expect(api.pricingRequests.markFactoryQuoteReady).toHaveBeenCalledWith(92));
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

// ─────────────────────────────────────────────────────────────────────────────
// The shared ConfirmDialog, per action
// ─────────────────────────────────────────────────────────────────────────────
//
// ONE <ConfirmDialog> serves every confirmable action on this page, and its title, message and
// confirmLabel are each a separate `confirmAction?.type === ...` ternary CHAIN. Removing one
// action means deleting a branch from the middle of three chains at once, and the failure mode
// is silent: mis-nest the `message` chain and อนุมัติราคาขาย starts showing ตีกลับ's copy while
// every existing assertion — which only ever checked that the right mutation fired — stays green.
//
// #747 deleted the submitCosting branch (the four Import costing controls were unreachable for
// their whole existence: the panel is `canSeeRaw && !isImport`, every control inside required
// `isImport`). These cases pin the copy of the FOUR that remain, so that edit and any future one
// is falsifiable. Mutation-checked on 2026-08-14: swapping any single branch of any of the three
// chains turns exactly the matching case below red.
describe('PricingRequestDetailPage shared ConfirmDialog copy (the four surviving actions)', () => {
  it('sendQuote — the chain default: ส่งอีเมลถึงโรงงาน / ยืนยันการส่ง… / ส่งอีเมล', async () => {
    renderDetailPage({ user: importUser, factoryQuotes: [buildFactoryQuote()] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    // ส่งแล้ว lives inside the factory group's ร่างอีเมล modal now (factory-price-import-ui redesign).
    fireEvent.click(screen.getByRole('button', { name: 'ร่างอีเมล' }));
    await screen.findByRole('dialog', { name: 'ร่างอีเมลถึงโรงงาน' });
    fireEvent.click(screen.getByRole('button', { name: 'ส่งแล้ว' }));

    const dialog = await screen.findByRole('dialog', { name: 'ส่งอีเมลถึงโรงงาน' });
    expect(within(dialog).getByText('ยืนยันการส่งคำขอราคาให้โรงงานด้วยรายละเอียดอีเมลนี้')).not.toBeNull();
    expect(within(dialog).getByRole('button', { name: 'ส่งอีเมล' })).not.toBeNull();
    // No reason textarea: requireReason is returnDecision-only.
    expect(within(dialog).queryByLabelText('เหตุผลที่ตีกลับ')).toBeNull();
  });

  it('approveDecision — อนุมัติราคาขาย / เมื่ออนุมัติแล้ว… / อนุมัติ', async () => {
    const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
    api.pricingRequests.listPricingDecisions.mockResolvedValue({ items: [buildDecision()] });
    renderDetailPage({ user: ceoUser, request });
    await waitForLoaded(request);
    await screen.findByText('PCD-2026-0001');

    fireEvent.click(screen.getByRole('button', { name: 'อนุมัติราคาขาย' }));

    const dialog = await screen.findByRole('dialog', { name: 'อนุมัติราคาขาย' });
    expect(within(dialog).getByText('เมื่ออนุมัติแล้ว ราคาขายจะถูกส่งให้ฝ่ายขายและไม่สามารถแก้ไขราคานี้ได้อีก')).not.toBeNull();
    expect(within(dialog).getByRole('button', { name: 'อนุมัติ' })).not.toBeNull();
    expect(within(dialog).queryByLabelText('เหตุผลที่ตีกลับ')).toBeNull();
  });

  it('returnDecision — ตีกลับ…ต้นทุน / ระบุเหตุผล… / ตีกลับ, and it is the only one that requires a reason', async () => {
    const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
    api.pricingRequests.listPricingDecisions.mockResolvedValue({ items: [buildDecision()] });
    renderDetailPage({ user: ceoUser, request });
    await waitForLoaded(request);
    await screen.findByText('PCD-2026-0001');

    fireEvent.click(screen.getByRole('button', { name: 'ตีกลับให้ฝ่ายนำเข้าแก้ไข' }));

    const dialog = await screen.findByRole('dialog', { name: 'ตีกลับให้ฝ่ายนำเข้าแก้ไขต้นทุน' });
    expect(within(dialog).getByText('ระบุเหตุผลที่ตีกลับให้ฝ่ายนำเข้าคำนวณต้นทุนใหม่')).not.toBeNull();
    expect(within(dialog).getByRole('button', { name: 'ตีกลับ' })).not.toBeNull();
    // requireReason + reasonLabel, and tone="danger" on the confirm button.
    expect(within(dialog).getByLabelText('เหตุผลที่ตีกลับ')).not.toBeNull();
    expect(within(dialog).getByRole('button', { name: 'ตีกลับ' }).className).toMatch(/danger/);
  });

  it('issueQuotation — ออกใบเสนอราคาลูกค้า / เมื่อออกใบเสนอราคาแล้ว… / ออกใบเสนอราคา', async () => {
    const request = buildRequest({ summary: { status: 'APPROVED_FOR_QUOTATION' } });
    api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [buildCustomerQuotation()] });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);
    await screen.findByText('QT-2026-0001');

    fireEvent.click(screen.getByRole('button', { name: 'ออกใบเสนอราคา' }));

    const dialog = await screen.findByRole('dialog', { name: 'ออกใบเสนอราคาลูกค้า' });
    expect(within(dialog).getByText('เมื่อออกใบเสนอราคาแล้ว จะแก้ไขไม่ได้ — การแก้ไขภายหลังต้องสร้างรอบแก้ไขใหม่')).not.toBeNull();
    expect(within(dialog).getByRole('button', { name: 'ออกใบเสนอราคา' })).not.toBeNull();
    expect(within(dialog).queryByLabelText('เหตุผลที่ตีกลับ')).toBeNull();
  });

  // The deleted branch must not come back through the dialog either: no action on this page can
  // still produce a submitCosting confirmation, for any role.
  it('offers no submitCosting confirmation to the CEO, who is the only role the costing panel renders for', async () => {
    renderDetailPage({ user: ceoUser, costings: [buildCosting()] });
    await waitForLoaded();
    await screen.findByText('COST-2026-0001');

    expect(screen.queryByRole('button', { name: 'ส่งให้ CEO ตรวจ' })).toBeNull();
    expect(screen.queryByRole('dialog', { name: 'ส่งต้นทุนให้ CEO ตรวจ' })).toBeNull();
    expect(screen.queryByText('เมื่อส่งแล้ว เวอร์ชันต้นทุนนี้จะแก้ไขไม่ได้')).toBeNull();
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
  // Phase 1 UI simplification (owner ruling 2026-08-16): the cost breakdown, the formula
  // derivation, ปรับต้นทุนเอง, and ปรับราคาเอง all live inside a per-item "วิธีคำนวณราคานี้"
  // CollapsibleSection, collapsed by default (CollapsibleSection unmounts its body rather than
  // CSS-hiding it — see that component's own doc comment) — every test below that needs to reach
  // one of those controls must open it first.
  function expandDerivation() {
    fireEvent.click(screen.getByRole('button', { name: 'วิธีคำนวณราคานี้' }));
  }

  // "ต้นทุนโรงงาน (ฐาน): <code>฿60.00</code>" splits its label and value across an element
  // boundary (the <code> wraps the figure, matching every other computed-money display in this
  // panel) — the default getByText text matcher does not read across that boundary (it is a
  // known testing-library limitation, not a markup defect: the "ราคาขาย" line right next to it
  // has no such boundary and matches a plain regex fine). A function matcher reading the whole
  // element's combined textContent, restricted to the leaf that owns it, is the documented fix.
  function byCombinedText(regex) {
    return (_content, element) => {
      if (!regex.test(element.textContent)) return false;
      return Array.from(element.children).every((child) => !regex.test(child.textContent));
    };
  }

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

  it('shows the read-only base cost and the automatically computed selling price, asking for nothing, with no per-item input anywhere', async () => {
    const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
    api.pricingRequests.listPricingDecisions.mockResolvedValue({ items: [buildDecision()] });
    renderDetailPage({ user: ceoUser, request });
    await waitForLoaded(request);
    await screen.findByText('PCD-2026-0001');

    expect(screen.getByText(byCombinedText(/ต้นทุนโรงงาน.*฿60\.00/))).not.toBeNull();
    expect(screen.getByText(/ราคาขาย.*฿72\.00/)).not.toBeNull();
    // The old per-item margin/minimum/ceiling grid is gone entirely.
    expect(screen.queryByPlaceholderText('อัตรากำไร เช่น 0.20 = 20%')).toBeNull();
    expect(screen.queryByPlaceholderText('ราคาขั้นต่ำ')).toBeNull();
    expect(screen.queryByPlaceholderText('ส่วนลดสูงสุด เช่น 0.10 = 10%')).toBeNull();
    expect(screen.queryByRole('button', { name: 'บันทึกการเปลี่ยนแปลง' })).toBeNull();
    // The only two actions left — asserted by role name so a stray extra button would show up as
    // "found 2" against getByRole's own strictness, not silently pass.
    expect(screen.getByRole('button', { name: 'อนุมัติราคาขาย' })).not.toBeNull();
    expect(screen.getByRole('button', { name: 'ตีกลับให้ฝ่ายนำเข้าแก้ไข' })).not.toBeNull();
  });

  // V141 ("CEO owns costing", PR #702, commit 1).
  it('lets the CEO recalculate the decision cost, calling recalculatePricingDecisionCost with the decision id and nothing else', async () => {
    const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
    api.pricingRequests.listPricingDecisions.mockResolvedValue({ items: [buildDecision()] });
    renderDetailPage({ user: ceoUser, request });
    await waitForLoaded(request);
    await screen.findByText('PCD-2026-0001');

    fireEvent.click(screen.getByTestId('pcr-ceo-recalculate-cost'));

    await waitFor(() => expect(api.pricingRequests.recalculatePricingDecisionCost).toHaveBeenCalledWith(7001));
    expect(api.pricingRequests.recalculatePricingDecisionCost).toHaveBeenCalledTimes(1);
  });

  // Wrong-way-round: the whole CEO decision panel is import-excluded (canSeeRawPricingDecision(user)
  // && !isImport(user)), so this is not a narrower gate than the panel itself — it must be absent
  // for the same reason every other control in this panel is absent for Import.
  it('does not offer "คำนวณต้นทุนใหม่" to Import — the whole CEO decision panel is import-excluded', async () => {
    const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
    api.pricingRequests.listPricingDecisions.mockResolvedValue({ items: [buildDecision()] });
    renderDetailPage({ user: importUser, request });
    await waitForLoaded(request);

    expect(screen.queryByTestId('pcr-ceo-recalculate-cost')).toBeNull();
  });

  // Wrong-way-round: editable = isDraft && canActOnPricingDecision(...) — an APPROVED decision is
  // read-only even for the CEO who approved it.
  it('does not offer "คำนวณต้นทุนใหม่" to the CEO on an APPROVED decision (not DRAFT)', async () => {
    const request = buildRequest({ summary: { status: 'APPROVED_FOR_QUOTATION' } });
    api.pricingRequests.listPricingDecisions.mockResolvedValue({
      items: [buildDecision({ status: 'APPROVED', approvedBy: 4, approvedAt: '2026-07-21T00:00:00Z' })],
    });
    renderDetailPage({ user: ceoUser, request });
    await waitForLoaded(request);
    await screen.findByText('PCD-2026-0001');

    expect(screen.queryByTestId('pcr-ceo-recalculate-cost')).toBeNull();
  });

  // V141 ("CEO owns costing", PR #702, commit 2) — per-line cost override.
  describe('CEO per-line cost override', () => {
    function renderWithCostingItem(costingItemOverrides = {}, { user = ceoUser } = {}) {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      const costingItem = buildCostingItemWithOverride(costingItemOverrides);
      api.pricingRequests.listPricingDecisions.mockResolvedValue({
        items: [buildDecision({ items: [buildDecisionItem({ pricingCostingItemId: costingItem.id })] })],
      });
      // buildDecision()'s default pricingCostingId (601) does NOT match buildCosting()'s default
      // id (21) — id: 601 here is deliberate, not incidental, so the decision-to-costing join
      // (costings.find(c => c.id === currentDecision.pricingCostingId)) actually has to match
      // rather than silently landing on an empty decisionCostingItems map.
      return renderDetailPage({ user, request, costings: [buildCosting({ id: 601, items: [costingItem] })] });
    }

    it('shows the CEO the computed cost per piece, and on an overridden line the override value, "ปรับเอง" caption, and reason', async () => {
      renderWithCostingItem({
        manualLandedCostPerUnitThb: 75, overrideReason: 'ราคาต้นทุนจริงจากใบขนสินค้า',
      });
      await waitForLoaded(buildRequest({ summary: { status: 'CEO_REVIEWING' } }));
      await screen.findByText('PCD-2026-0001');
      expandDerivation();

      // ต้นทุนคำนวณ/ชิ้น (computed, info) — never destroyed by the override.
      expect(screen.getByText('฿55.00')).not.toBeNull();
      // ต้นทุนที่ปรับ/ชิ้น (override, purple) + caption + reason.
      expect(screen.getByText('฿75.00')).not.toBeNull();
      expect(screen.getByText('ปรับเอง')).not.toBeNull();
      expect(screen.getByText('(ราคาต้นทุนจริงจากใบขนสินค้า)')).not.toBeNull();
      // The per-requested-unit basis (a different number, 60) still renders in the main
      // (collapsed) view under its Phase-1-simplification label — a substring match against the
      // combined "label: value" text of that line.
      expect(screen.getByText(byCombinedText(/ต้นทุนโรงงาน.*฿60\.00/))).not.toBeNull();
    });

    it('refuses to SAVE an override with a blank reason, client-side, without calling the API', async () => {
      renderWithCostingItem();
      await waitForLoaded(buildRequest({ summary: { status: 'CEO_REVIEWING' } }));
      await screen.findByText('PCD-2026-0001');
      expandDerivation();

      fireEvent.click(screen.getByTestId('pcr-ceo-cost-override-8001'));
      const dialog = await screen.findByRole('dialog', { name: 'ปรับต้นทุนเอง' });
      fireEvent.change(within(dialog).getByLabelText('ต้นทุนที่ปรับ (บาท/ชิ้น)'), { target: { value: '80' } });
      fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึกต้นทุนที่ปรับ' }));

      expect(await within(dialog).findByText('กรุณาระบุเหตุผลในการปรับต้นทุน')).not.toBeNull();
      expect(api.pricingRequests.overridePricingDecisionItemCost).not.toHaveBeenCalled();
    });

    // The direction that is easy to miss (per CLAUDE.md's own note on this endpoint): clearing is
    // money-affecting too, so it needs the same reason gate as setting — not a lighter one.
    it('refuses to CLEAR an override with a blank reason, client-side, without calling the API', async () => {
      renderWithCostingItem({ manualLandedCostPerUnitThb: 75, overrideReason: 'เหตุผลเดิม' });
      await waitForLoaded(buildRequest({ summary: { status: 'CEO_REVIEWING' } }));
      await screen.findByText('PCD-2026-0001');
      expandDerivation();

      fireEvent.click(screen.getByTestId('pcr-ceo-cost-override-8001'));
      const dialog = await screen.findByRole('dialog', { name: 'แก้ไขต้นทุนที่ปรับ' });
      fireEvent.click(within(dialog).getByRole('button', { name: 'ล้างค่าที่ปรับ' }));

      expect(await within(dialog).findByText('กรุณาระบุเหตุผลในการปรับต้นทุน')).not.toBeNull();
      expect(api.pricingRequests.overridePricingDecisionItemCost).not.toHaveBeenCalled();
    });

    it('saves a new cost override — happy path SET', async () => {
      renderWithCostingItem();
      await waitForLoaded(buildRequest({ summary: { status: 'CEO_REVIEWING' } }));
      await screen.findByText('PCD-2026-0001');
      expandDerivation();

      fireEvent.click(screen.getByTestId('pcr-ceo-cost-override-8001'));
      const dialog = await screen.findByRole('dialog', { name: 'ปรับต้นทุนเอง' });
      fireEvent.change(within(dialog).getByLabelText('ต้นทุนที่ปรับ (บาท/ชิ้น)'), { target: { value: '80' } });
      fireEvent.change(within(dialog).getByLabelText(/^เหตุผล/), { target: { value: 'ราคาจริงจากใบขนสินค้า' } });
      fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึกต้นทุนที่ปรับ' }));

      await waitFor(() => expect(api.pricingRequests.overridePricingDecisionItemCost).toHaveBeenCalledWith(
        7001, 8001, { manualLandedCostPerUnitThb: 80, reason: 'ราคาจริงจากใบขนสินค้า' },
      ));
    });

    it('clears an existing cost override — happy path CLEAR', async () => {
      renderWithCostingItem({ manualLandedCostPerUnitThb: 75, overrideReason: 'เหตุผลเดิม' });
      await waitForLoaded(buildRequest({ summary: { status: 'CEO_REVIEWING' } }));
      await screen.findByText('PCD-2026-0001');
      expandDerivation();

      fireEvent.click(screen.getByTestId('pcr-ceo-cost-override-8001'));
      const dialog = await screen.findByRole('dialog', { name: 'แก้ไขต้นทุนที่ปรับ' });
      fireEvent.change(within(dialog).getByLabelText(/^เหตุผล/), { target: { value: 'คำนวณใหม่แล้วถูกต้อง' } });
      fireEvent.click(within(dialog).getByRole('button', { name: 'ล้างค่าที่ปรับ' }));

      await waitFor(() => expect(api.pricingRequests.overridePricingDecisionItemCost).toHaveBeenCalledWith(
        7001, 8001, { manualLandedCostPerUnitThb: null, reason: 'คำนวณใหม่แล้วถูกต้อง' },
      ));
    });

    // The stale-override badge is deliberately in the MAIN (collapsed) header row, not inside the
    // derivation disclosure — a CEO must see it without expanding anything.
    it('renders the stale-override warning badge (uncollapsed) and disables approval when a fixture has overrideStale: true', async () => {
      renderWithCostingItem({
        manualLandedCostPerUnitThb: 75, overrideReason: 'เหตุผลเดิม',
        overrideFxRate: 1, overrideCalcConfigVersion: 1, calculationConfigVersion: 2, overrideStale: true,
      });
      await waitForLoaded(buildRequest({ summary: { status: 'CEO_REVIEWING' } }));
      await screen.findByText('PCD-2026-0001');

      expect(screen.getByText('ต้นทุนที่ปรับล้าสมัย')).not.toBeNull();
      expect(screen.getByRole('button', { name: 'อนุมัติราคาขาย' }).disabled).toBe(true);
    });

    // Wrong-way-round: the whole CEO decision panel is import-excluded, same as commit 1's own
    // recalculate button — this is not a narrower gate than the panel itself.
    it('shows Import no cost-override button anywhere', async () => {
      renderWithCostingItem({}, { user: importUser });
      await waitForLoaded(buildRequest({ summary: { status: 'CEO_REVIEWING' } }));

      expect(screen.queryByTestId('pcr-ceo-cost-override-8001')).toBeNull();
    });
  });

  // Phase 1 UI simplification ("ปรับราคาเอง", owner ruling 2026-08-16) — a REAL behaviour change:
  // overrides the SELLING PRICE directly (not the cost), and the formula stops driving that line
  // entirely. Reuses PUT /pricing-decisions/{id} (updatePricingDecision) rather than a new
  // endpoint — see PricingDecisionRequests.UpdatePricingDecisionItemRequest's own doc comment for
  // why sellingPriceOverride/clearSellingPriceOverride need a tri-state that plain COALESCE can't
  // express, and PriceOverrideModal / the overrideSellingPrice mutation in the page itself.
  describe('CEO per-line selling-price override ("ปรับราคาเอง")', () => {
    it('shows the automatically computed price by default, opens the derivation, and offers ปรับราคาเอง', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({ items: [buildDecision()] });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');

      expect(screen.queryByText('ราคาปรับเอง')).toBeNull();
      expandDerivation();
      expect(screen.getByRole('button', { name: 'ปรับราคาเอง' })).not.toBeNull();
    });

    it('refuses to SAVE a price override with a blank reason, client-side, without calling the API', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({ items: [buildDecision()] });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');
      expandDerivation();

      fireEvent.click(screen.getByTestId('pcr-ceo-price-override-8001'));
      const dialog = await screen.findByRole('dialog', { name: 'ปรับราคาเอง' });
      fireEvent.change(within(dialog).getByLabelText(/^ราคาที่ปรับ/), { target: { value: '90' } });
      fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึกราคาที่ปรับ' }));

      expect(await within(dialog).findByText('กรุณาระบุเหตุผลในการปรับราคาขาย')).not.toBeNull();
      expect(api.pricingRequests.updatePricingDecision).not.toHaveBeenCalled();
    });

    it('refuses to CLEAR a price override with a blank reason, client-side, without calling the API', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({
        items: [buildDecision({ items: [buildDecisionItem({ manualSellingPricePerRequestedUnit: 90 })] })],
      });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');
      expandDerivation();

      fireEvent.click(screen.getByTestId('pcr-ceo-price-override-8001'));
      const dialog = await screen.findByRole('dialog', { name: 'แก้ไขราคาที่ปรับ' });
      fireEvent.click(within(dialog).getByRole('button', { name: 'ล้างค่าที่ปรับ' }));

      expect(await within(dialog).findByText('กรุณาระบุเหตุผลในการปรับราคาขาย')).not.toBeNull();
      expect(api.pricingRequests.updatePricingDecision).not.toHaveBeenCalled();
    });

    it('saves a new price override — happy path SET, calling updatePricingDecision with a single-item payload', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({ items: [buildDecision()] });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');
      expandDerivation();

      fireEvent.click(screen.getByTestId('pcr-ceo-price-override-8001'));
      const dialog = await screen.findByRole('dialog', { name: 'ปรับราคาเอง' });
      fireEvent.change(within(dialog).getByLabelText(/^ราคาที่ปรับ/), { target: { value: '90' } });
      fireEvent.change(within(dialog).getByLabelText(/^เหตุผล/), { target: { value: 'ลูกค้าต่อรองราคาสุดท้าย' } });
      fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึกราคาที่ปรับ' }));

      await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalledWith(
        7001,
        {
          items: [{
            pricingDecisionItemId: 8001,
            sellingPriceOverride: 90,
            clearSellingPriceOverride: false,
            decisionNote: 'ลูกค้าต่อรองราคาสุดท้าย',
          }],
        },
      ));
    });

    it('clears an existing price override — happy path CLEAR', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({
        items: [buildDecision({ items: [buildDecisionItem({ manualSellingPricePerRequestedUnit: 90 })] })],
      });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');
      expandDerivation();

      fireEvent.click(screen.getByTestId('pcr-ceo-price-override-8001'));
      const dialog = await screen.findByRole('dialog', { name: 'แก้ไขราคาที่ปรับ' });
      fireEvent.change(within(dialog).getByLabelText(/^เหตุผล/), { target: { value: 'กลับไปใช้ราคาอัตโนมัติ' } });
      fireEvent.click(within(dialog).getByRole('button', { name: 'ล้างค่าที่ปรับ' }));

      await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalledWith(
        7001,
        {
          items: [{
            pricingDecisionItemId: 8001,
            sellingPriceOverride: null,
            clearSellingPriceOverride: true,
            decisionNote: 'กลับไปใช้ราคาอัตโนมัติ',
          }],
        },
      ));
    });

    // Uncollapsed, same as the stale-override badge — a CEO must see AT A GLANCE that a price was
    // fixed manually, without expanding anything.
    it('shows a "ราคาปรับเอง" indicator in the main (collapsed) view and the overridden price, once active', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({
        items: [buildDecision({ items: [buildDecisionItem({ manualSellingPricePerRequestedUnit: 90 })] })],
      });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');

      expect(screen.getByText('ราคาปรับเอง')).not.toBeNull();
      expect(screen.getByText(/ราคาขาย.*฿90\.00/)).not.toBeNull();
      // The formula's own output (72) is superseded, not deleted — never shown as THE price.
      expect(screen.queryByText(/ราคาขาย.*฿72\.00/)).toBeNull();
    });

    // Mirrors PricingDecisionService#approve's own missingMargin exemption for an overridden
    // item: a "ปรับราคาเอง" line needs no margin at all to approve.
    it('does not require a margin on an overridden line to enable approval', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({
        items: [buildDecision({
          items: [buildDecisionItem({ proposedMarginPct: null, manualSellingPricePerRequestedUnit: 90 })],
        })],
      });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');

      expect(screen.getByRole('button', { name: 'อนุมัติราคาขาย' }).disabled).toBe(false);
    });

    it('shows Import no price-override button anywhere', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({ items: [buildDecision()] });
      renderDetailPage({ user: importUser, request });
      await waitForLoaded(request);

      expect(screen.queryByTestId('pcr-ceo-price-override-8001')).toBeNull();
    });
  });

  // ราคาขั้นต่ำ is no longer a CEO input (auto-populated server-side at approve() — see
  // PricingDecisionService#approve), so only a missing MARGIN can block approval now, and only on
  // a line with no active "ปรับราคาเอง" override.
  it('disables approval until every item has a margin (mirrors the server 422 gate)', async () => {
    const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
    api.pricingRequests.listPricingDecisions.mockResolvedValue({
      items: [buildDecision({ items: [buildDecisionItem({ proposedMarginPct: null })] })],
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
    expect(screen.queryByRole('button', { name: 'วิธีคำนวณราคานี้' })).toBeNull();
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
    // getAllByText, not getByText: the grouped-by-factory item row (ยี่ห้อ/รุ่น column) now echoes
    // the same product name back, alongside "รายการสินค้าและราคาตั้งต้น" above it, so this string
    // legitimately appears twice.
    expect(screen.getAllByText('SCG A1').length).toBeGreaterThan(0);
    // By role and a prefix regex, not exact text: the section header is "รายการสินค้า (N รายการ)"
    // with a dynamic count (factory-price-import-ui redesign). The assertion here is that the
    // SECTION is present.
    expect(await screen.findByRole('heading', { name: /^รายการสินค้า \(/ })).not.toBeNull();
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
      costings: [buildCosting({ status: 'CALCULATED' })],
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

  // CEO discount-approval workflow, Phase 2 (owner ruling 2026-08-16, V155).
  describe('CEO discount-approval workflow', () => {
    it('shows Sales the pending status badge, with no approve/reject buttons', async () => {
      const request = buildRequest({ summary: { status: 'APPROVED_FOR_QUOTATION' } });
      const item = buildCustomerQuotationItem({ salesDiscount: 10, finalUnitPrice: 62 });
      const quotation = buildCustomerQuotation({ items: [item] });
      const approval = buildDiscountApproval({ requestedFinalUnitPrice: 62 });
      api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [quotation] });
      renderDetailPage({ user: salesOwner, request, discountApprovals: [approval] });
      await waitForLoaded(request);
      await screen.findByText(quotation.number);

      expect(await screen.findByText('รอ CEO อนุมัติส่วนลด')).not.toBeNull();
      expect(screen.queryByRole('button', { name: 'อนุมัติส่วนลด' })).toBeNull();
      expect(screen.queryByRole('button', { name: 'ปฏิเสธส่วนลด' })).toBeNull();
    });

    it('lets the CEO approve a pending discount request via the confirm dialog', async () => {
      const item = buildCustomerQuotationItem({ salesDiscount: 10, finalUnitPrice: 62 });
      const quotation = buildCustomerQuotation({ items: [item] });
      const approval = buildDiscountApproval({ requestedFinalUnitPrice: 62 });
      api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [quotation] });
      api.pricingRequests.approveDiscountApproval.mockResolvedValue({
        approval: { ...approval, status: 'APPROVED', approvedFinalUnitPrice: 62 },
      });
      renderDetailPage({ user: ceoUser, discountApprovals: [approval] });
      await waitForLoaded();
      await screen.findByText(quotation.number);

      fireEvent.click(await screen.findByRole('button', { name: 'อนุมัติส่วนลด' }));
      const dialog = await screen.findByRole('dialog', { name: 'อนุมัติส่วนลด' });
      fireEvent.click(within(dialog).getByRole('button', { name: 'อนุมัติส่วนลด' }));

      await waitFor(() => expect(api.pricingRequests.approveDiscountApproval).toHaveBeenCalledWith(approval.id));
    });

    it('lets the CEO reject a pending discount request only with a mandatory reason', async () => {
      const item = buildCustomerQuotationItem({ salesDiscount: 10, finalUnitPrice: 62 });
      const quotation = buildCustomerQuotation({ items: [item] });
      const approval = buildDiscountApproval({ requestedFinalUnitPrice: 62 });
      api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [quotation] });
      api.pricingRequests.rejectDiscountApproval.mockResolvedValue({
        approval: { ...approval, status: 'REJECTED', rejectionReason: 'ส่วนลดสูงเกินไป' },
      });
      renderDetailPage({ user: ceoUser, discountApprovals: [approval] });
      await waitForLoaded();
      await screen.findByText(quotation.number);

      fireEvent.click(await screen.findByRole('button', { name: 'ปฏิเสธส่วนลด' }));
      const dialog = await screen.findByRole('dialog', { name: 'ปฏิเสธส่วนลด' });
      const confirmButton = within(dialog).getByRole('button', { name: 'ปฏิเสธส่วนลด' });
      // Mandatory reason: the confirm button stays disabled until one is typed.
      expect(confirmButton.disabled).toBe(true);

      fireEvent.change(within(dialog).getByLabelText('เหตุผลที่ปฏิเสธส่วนลด'), { target: { value: 'ส่วนลดสูงเกินไป' } });
      expect(confirmButton.disabled).toBe(false);
      fireEvent.click(confirmButton);

      await waitFor(() => expect(api.pricingRequests.rejectDiscountApproval).toHaveBeenCalledWith(
        approval.id,
        { reason: 'ส่วนลดสูงเกินไป' },
      ));
    });

    it('shows Sales the CEO rejection reason once a discount request is rejected', async () => {
      const request = buildRequest({ summary: { status: 'APPROVED_FOR_QUOTATION' } });
      const item = buildCustomerQuotationItem({ salesDiscount: 10, finalUnitPrice: 62 });
      const quotation = buildCustomerQuotation({ items: [item] });
      const approval = buildDiscountApproval({
        requestedFinalUnitPrice: 62,
        status: 'REJECTED',
        rejectionReason: 'ส่วนลดสูงเกินไปสำหรับลูกค้ารายนี้',
        decidedBy: 4,
        decidedByName: 'ซีอีโอ',
        decidedAt: '2026-08-17T01:00:00Z',
      });
      api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [quotation] });
      renderDetailPage({ user: salesOwner, request, discountApprovals: [approval] });
      await waitForLoaded(request);
      await screen.findByText(quotation.number);

      expect(await screen.findByText('CEO ปฏิเสธส่วนลด')).not.toBeNull();
      expect(screen.getByText(/ส่วนลดสูงเกินไปสำหรับลูกค้ารายนี้/)).not.toBeNull();
      // A rejected (not pending) request never shows approve/reject buttons, even to the CEO.
      expect(screen.queryByRole('button', { name: 'อนุมัติส่วนลด' })).toBeNull();
      expect(screen.queryByRole('button', { name: 'ปฏิเสธส่วนลด' })).toBeNull();
    });
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
