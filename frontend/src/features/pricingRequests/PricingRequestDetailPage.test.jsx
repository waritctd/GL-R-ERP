import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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
      setItemFactory: vi.fn(),
      pickup: vi.fn(),
      generateFactoryEmailDrafts: vi.fn(),
      updateFactoryQuote: vi.fn(),
      markFactoryQuoteContacted: vi.fn(),
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
    // CR-1 (GLA-167): lead-time change requests (R2 / R10).
    leadTimeChanges: {
      listForPricingRequest: vi.fn(),
      create: vi.fn(),
      update: vi.fn(),
      withdraw: vi.fn(),
      approve: vi.fn(),
      reject: vi.fn(),
    },
    // CR-1 (R9): the factory card lists the deal's stored IR for that factory, read-only.
    storedImportRequests: {
      listForTicket: vi.fn(),
      download: vi.fn(),
    },
    catalog: {
      prices: vi.fn(),
    },
    // B6 (GLA-135): the import factory picker's master list + country roster + in-flow
    // add-factory form (ImportFactoryPicker, reusing catalog PriceImportPage's FactoryFormModal).
    priceImport: {
      factories: vi.fn(),
      countries: vi.fn(),
      createFactory: vi.fn(),
    },
    meta: {
      unitBases: vi.fn(),
    },
    // GLA-125: PricingRequestCreateModal's header-terms section (opened here in mode="revision")
    // fetches the same eligible-display-name list the direct-deal quotation editor uses.
    dealQuotations: {
      displayNameOptions: vi.fn().mockResolvedValue({ items: [] }),
      // GLA-123 slice S1 M1 fix (Opus review, 2026-09-20) — reset to "none yet" in the shared
      // beforeEach below (mirrors getPricingDecisionSalesView's own per-test reset there), so
      // every existing test in this file keeps seeing the SAME legacy-only panel state as before
      // this fix, rather than a leaked override from an earlier test or a thrown TypeError.
      findForPricingRequest: vi.fn(),
      createFromPricingRequest: vi.fn(),
      // GLA-123 slice S3 — the new engine's own outcome-recording endpoint, mirrors
      // pricingRequests.recordCustomerQuotationOutcome above.
      recordOutcome: vi.fn(),
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
        color: 'ขาว',
        texture: 'ด้าน',
        size: '60x60',
        // V185: color/texture/size/thicknessMm/sqmPerPiece/piecesPerBox/a quantity are now
        // required on every item PricingRequestCreateModal can save — see that component's own
        // validateItemFields (mirrors PricingRequestService#requireItemFieldsComplete).
        thicknessMm: 10,
        sqmPerPiece: 0.36,
        quantityMode: 'PIECES',
        piecesInput: 20,
        piecesPerBox: 4,
        // GLA-125: required on this form too.
        originCountry: 'ไทย-สต็อก',
        leadTimeMinDays: 3,
        leadTimeMaxDays: 7,
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
  api.pricingRequests.setItemFactory.mockResolvedValue({});
  api.pricingRequests.generateFactoryEmailDrafts.mockResolvedValue({});
  api.pricingRequests.updateFactoryQuote.mockResolvedValue({});
  api.pricingRequests.markFactoryQuoteContacted.mockResolvedValue({});
  api.storedImportRequests.listForTicket.mockResolvedValue({ importRequests: [] });
  api.storedImportRequests.download.mockResolvedValue(new Blob(['pdf'], { type: 'application/pdf' }));
  api.leadTimeChanges.listForPricingRequest.mockResolvedValue({ items: [] });
  api.leadTimeChanges.create.mockResolvedValue({});
  api.leadTimeChanges.update.mockResolvedValue({});
  api.leadTimeChanges.withdraw.mockResolvedValue({});
  api.leadTimeChanges.approve.mockResolvedValue({});
  api.leadTimeChanges.reject.mockResolvedValue({});
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
  // GLA-123 slice S1 M1 fix (Opus review, 2026-09-20): reset every test back to "no new-engine
  // quotation yet" — mockResolvedValue (not Once) leaks into later tests otherwise, exactly like
  // getPricingDecisionSalesView above would without this same per-test reset.
  api.dealQuotations.findForPricingRequest.mockResolvedValue({ quotation: null });
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
  // B6 (GLA-135): a small real-looking factory roster — enough for the picker + duplicate-name
  // tests below without pulling in the full catalog fixture this file doesn't otherwise need.
  api.priceImport.factories.mockResolvedValue([
    { factoryId: 601, name: 'SCG Ceramics', country: 'TH', countryOther: null, defaultCurrency: 'THB', email: null, unit: 'piece' },
    { factoryId: 602, name: 'Cotto Industry', country: 'TH', countryOther: null, defaultCurrency: 'THB', email: null, unit: 'piece' },
  ]);
  api.priceImport.countries.mockResolvedValue([
    { countryCode: 'TH', nameEn: 'Thailand', nameTh: 'ไทย' },
    { countryCode: 'IT', nameEn: 'Italy', nameTh: 'อิตาลี' },
  ]);
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
    // Phase 2 (owner rulings 2026-09-18/19, V187) — sqmPerPiece is null by default, which makes
    // this fixture LEGACY-shaped under isNewFormEligibleDecision despite requestedUnitBasis
    // already being PER_PIECE above (matching the real backend's own eligibility rule: PER_PIECE
    // AND sqmPerPiece both required). Tests exercising the new mode picker pass sqmPerPiece.
    sqmPerPiece: null,
    listUnitPrice: null,
    discountPct: null,
    specialPriceSqm: null,
    directNetPrice: null,
    netUnitPrice: null,
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
    // Phase 2 (V187) — null until the CEO picks a mode; CEO-only (stripped for import server-side).
    priceMode: null,
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
  // CR-1: lead-time change requests (LeadTimeChangeDto[]).
  leadTimeChanges = [],
  // CR-1 (R9): the deal's stored ใบขอซื้อ rows (ImportRequestDto[]).
  importRequests = [],
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
  api.leadTimeChanges.listForPricingRequest.mockResolvedValue({ items: leadTimeChanges });
  api.storedImportRequests.listForTicket.mockResolvedValue({ importRequests });

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
    expect(screen.queryByLabelText(/อีเมลโรงงาน/)).toBeNull();
    expect(screen.queryByLabelText(/^ราคาที่เสนอ/)).toBeNull();
    // The per-factory currency/unit controls are read-only text for CEO, not a live select.
    expect(screen.queryByLabelText('สกุลเงิน')).toBeNull();
    expect(screen.queryByLabelText('หน่วยราคา')).toBeNull();
  });
});

describe('PricingRequestDetailPage Import factory-quote workflow', () => {
  it('lets Import edit the mail draft via สร้างเมล and saves it with บันทึกร่าง (updateFactoryQuote)', async () => {
    const quote = buildFactoryQuote();
    renderDetailPage({ user: importUser, factoryQuotes: [quote] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    // CR-1: the composer lives in a modal behind each factory card's สร้างเมล button (the old
    // ร่างอีเมล / ดูอีเมล labels are gone — see IA naming table).
    fireEvent.click(screen.getByRole('button', { name: 'สร้างเมล' }));
    const dialog = await screen.findByRole('dialog', { name: 'สร้างเมล' });

    const toInput = within(dialog).getByLabelText('ถึง');
    const subjectInput = within(dialog).getByLabelText('หัวข้อ');
    const bodyInput = within(dialog).getByLabelText('เนื้อหา');

    fireEvent.change(toInput, { target: { value: 'purchasing@scg-factory.example' } });
    fireEvent.change(subjectInput, { target: { value: 'ขอราคาใหม่ SCG A1' } });
    fireEvent.change(bodyInput, { target: { value: 'เรียน โรงงาน กรุณาเสนอราคาใหม่' } });

    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(api.pricingRequests.updateFactoryQuote).toHaveBeenCalledWith(
      quote.id,
      expect.objectContaining({
        emailTo: 'purchasing@scg-factory.example',
        emailSubject: 'ขอราคาใหม่ SCG A1',
        emailBody: 'เรียน โรงงาน กรุณาเสนอราคาใหม่',
      }),
    ));
  });

  // Owner UX ask 2026-09-24 (kept) + CR-1 R3/B-R2: the price grid must not invite a quoted price
  // before the factory has been marked ติดต่อโรงงานแล้ว (a DRAFT quote = not contacted yet).
  it('locks the price grid with a hint while the factory is not yet contacted (DRAFT)', async () => {
    renderDetailPage({ user: importUser, factoryQuotes: [buildFactoryQuote()] }); // default status DRAFT
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    expect(screen.getByTestId('pcr-await-contact-91').textContent).toContain('กด ติดต่อโรงงานแล้ว ก่อนกรอกราคา');
    expect(screen.getByLabelText(/^ราคาที่เสนอ/).disabled).toBe(true);
    expect(screen.queryByRole('button', { name: 'ยืนยันราคาเสนอ' })).toBeNull();
  });

  it('unlocks the price grid once the factory is contacted (REQUESTED)', async () => {
    renderDetailPage({ user: importUser, factoryQuotes: [buildFactoryQuote({ status: 'REQUESTED' })] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    expect(screen.queryByTestId('pcr-await-contact-91')).toBeNull();
    expect(screen.getByLabelText(/^ราคาที่เสนอ/).disabled).toBe(false);
    expect(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' })).not.toBeNull();
  });

  // Opus review of #1062 (2026-09-28) used to pin that a DRAFT quote outside send()'s window
  // UNLOCKED the grid because receive() accepted a DRAFT quote there. CR-1 B-R2 reverses that:
  // FactoryQuoteService#receive now 409s on EVERY DRAFT quote ("ต้องกด ติดต่อโรงงานแล้ว ก่อนกรอกราคา"),
  // so the grid stays locked past the window too, and — since markContacted is itself guarded by
  // DRAFT_STATUSES — the ติดต่อโรงงานแล้ว button is withheld there rather than offered to 409.
  it('keeps a DRAFT quote locked once the request is past the contact window (READY_FOR_CEO_REVIEW), with no contact button', async () => {
    renderDetailPage({
      user: importUser,
      request: buildRequest({ summary: { status: 'READY_FOR_CEO_REVIEW' } }),
      factoryQuotes: [buildFactoryQuote()], // still DRAFT
    });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    expect(screen.getByLabelText(/^ราคาที่เสนอ/).disabled).toBe(true);
    expect(screen.queryByRole('button', { name: 'ยืนยันราคาเสนอ' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'ติดต่อโรงงานแล้ว' })).toBeNull();
  });

  // #2 (owner ask 2026-09-24): before any mail is generated the price section used to be a
  // bare "ยังไม่มีราคาโรงงาน" — now it previews what needs pricing, grouped by the routed factory,
  // read-only (no editable price input yet).
  it('previews what needs pricing (grouped by factory) before any mail is generated', async () => {
    renderDetailPage({ user: importUser }); // no factoryQuotes → nothing generated yet
    await waitForLoaded();

    const preview = await screen.findByTestId('pcr-price-preview');
    expect(screen.getAllByTestId('pcr-price-preview-group').length).toBeGreaterThan(0);
    expect(within(preview).getByText('SCG Ceramics')).not.toBeNull();   // the routed factory group
    expect(screen.queryByLabelText(/^ราคาที่เสนอ/)).toBeNull();          // no editable price yet
  });

  // Opus review of #1062 (2026-09-28): the preview used to be gated on `factoryGroups.length ===
  // 0`, so it vanished entirely the instant ANY factory quote existed anywhere on the request. An
  // item whose factory was blank until Import gap-filled it (canSetItemFactory, still within
  // FACTORY_ROUTING_STATUSES — never a re-route of an already-set line, per setItemFactory's own
  // Javadoc) AFTER drafts were already generated for other items has no factory-quote row of its
  // own, so it appeared in neither the (suppressed) preview nor the quote grid — invisible. The
  // preview must now cover exactly the items no CURRENT factory quote already lists, independent of
  // whether other items already have one — and its banner copy must not claim "no draft generated
  // at all" in this mixed case, and the panel's item count must include these previewed items too.
  it('previews an item with no factory-quote row yet even once other items already have one', async () => {
    const base = buildRequest().items[0];
    const items = [
      { ...base, id: 1, resolvedFactoryName: 'SCG Ceramics' }, // A — quoted below
      { ...base, id: 2, resolvedFactoryName: 'SCG Ceramics', model: 'A2' }, // B — quoted below
      { ...base, id: 3, resolvedFactoryName: 'Cotto', model: 'C1' }, // C — gap-filled, not quoted yet
    ];
    const quote = buildFactoryQuote({
      items: [
        { ...buildFactoryQuote().items[0], id: 911, pricingRequestItemId: 1 },
        { ...buildFactoryQuote().items[0], id: 912, pricingRequestItemId: 2 },
      ],
    });
    const request = buildRequest({ items });
    renderDetailPage({ user: importUser, request, factoryQuotes: [quote] });
    await waitForLoaded(request);

    const preview = await screen.findByTestId('pcr-price-preview');
    expect(within(preview).getByText('Cotto')).not.toBeNull();          // C's factory shows
    expect(within(preview).queryByText('SCG Ceramics')).toBeNull();     // A/B's factory does not
    // Mixed case (A/B already quoted, C only just previewed): the banner must not claim no draft
    // was ever generated — that would be false for A/B's already-drafted factory.
    expect(within(preview).getByText(/รายการที่ยังไม่ได้ขอราคาจากโรงงาน/)).not.toBeNull();
    expect(within(preview).queryByText(/ยังไม่ได้สร้างเมลขอราคา/)).toBeNull();

    // A and B still render as their own quote-grid rows (locked, since request stays
    // IMPORT_REVIEWING and the quote stays DRAFT) rather than being hidden by C's preview.
    expect(screen.getAllByLabelText(/^ราคาที่เสนอ/).length).toBe(2);

    // Panel title counts all three items (2 quoted + 1 previewed), not just the quoted ones.
    expect(screen.getByText('รายการสินค้า (3 รายการ)')).not.toBeNull();
  });

  // #1 (owner ask 2026-09-24): a SUBMITTED request opened here (e.g. from a link) had no รับเรื่อง
  // affordance on the page itself — only in the คิวขอราคา queue.
  it('lets an import user รับเรื่อง a SUBMITTED request from the request-page header', async () => {
    api.pricingRequests.pickup.mockResolvedValue({ pricingRequest: buildRequest({ summary: { status: 'IMPORT_REVIEWING' } }) });
    renderDetailPage({ user: importUser, request: buildRequest({ summary: { status: 'SUBMITTED' } }) });
    await waitForLoaded();

    fireEvent.click(screen.getByTestId('pcr-detail-pickup'));
    await waitFor(() => expect(api.pricingRequests.pickup).toHaveBeenCalledWith(501));
  });

  it('shows no รับเรื่อง on the request page once it is already picked up (IMPORT_REVIEWING)', async () => {
    renderDetailPage({ user: importUser, request: buildRequest() }); // default IMPORT_REVIEWING
    await waitForLoaded();
    expect(screen.queryByTestId('pcr-detail-pickup')).toBeNull();
  });

  // owner ask 2026-09-24 (kept): once contacted, Import can still reopen the mail — read-only
  // (view + copy). CR-1 naming: the button stays สร้างเมล, there is no separate ดูอีเมล label.
  it('reopens the mail of a CONTACTED factory read-only (copy kept, no บันทึกร่าง)', async () => {
    renderDetailPage({ user: importUser, factoryQuotes: [buildFactoryQuote({ status: 'REQUESTED' })] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    const openBtn = screen.getByTestId('pcr-open-email-draft-91');
    expect(openBtn.textContent).toContain('สร้างเมล');
    fireEvent.click(openBtn);

    const dialog = await screen.findByTestId('factory-email-draft-modal');
    expect(within(dialog).getByRole('button', { name: /คัดลอกเมล/ })).not.toBeNull();
    expect(within(dialog).queryByRole('button', { name: 'บันทึกร่าง' })).toBeNull();
    expect(within(dialog).getByLabelText('ถึง').disabled).toBe(true);
  });

  // Opus review of #1062 second pass (kept in spirit): outside the contact window the modal is
  // read-only too — updateDraft is guarded by the identical DRAFT_STATUSES (409).
  it('reopens the mail modal read-only for a DRAFT quote once the request is past the contact window', async () => {
    renderDetailPage({
      user: importUser,
      request: buildRequest({ summary: { status: 'READY_FOR_CEO_REVIEW' } }),
      factoryQuotes: [buildFactoryQuote()], // still DRAFT
    });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    fireEvent.click(screen.getByTestId('pcr-open-email-draft-91'));
    const dialog = await screen.findByTestId('factory-email-draft-modal');
    expect(within(dialog).queryByRole('button', { name: 'บันทึกร่าง' })).toBeNull();
    expect(within(dialog).getByRole('button', { name: /คัดลอกเมล/ })).not.toBeNull();
    expect(within(dialog).getByLabelText('ถึง').disabled).toBe(true);
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
describe('PricingRequestDetailPage shared ConfirmDialog copy (the three surviving actions)', () => {
  it('approveDecision — อนุมัติราคาขาย / เมื่ออนุมัติแล้ว… / อนุมัติ', async () => {
    const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
    api.pricingRequests.listPricingDecisions.mockResolvedValue({ items: [buildDecision()] });
    renderDetailPage({ user: ceoUser, request });
    await waitForLoaded(request);
    await screen.findByText('PCD-2026-0001');

    fireEvent.click(screen.getByRole('button', { name: 'อนุมัติราคาขาย' }));

    const dialog = await screen.findByRole('dialog', { name: 'อนุมัติราคาขาย' });
    // P2 fix (2026-09): now also states the approved price is ex-VAT — see the "selling price is
    // stated as ก่อน VAT" describe block below for the dedicated coverage of that copy change.
    expect(within(dialog).getByText(
      'เมื่ออนุมัติแล้ว ราคาขายจะถูกส่งให้ฝ่ายขายและไม่สามารถแก้ไขราคานี้ได้อีก (ราคานี้เป็นราคาก่อน VAT — ยังไม่รวมภาษีมูลค่าเพิ่ม 7%)',
    )).not.toBeNull();
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
    // CR-1: the line carries the currency/unit sales fixed (a legacy line without them must be
    // completed before a revision can be saved — covered in PricingRequestCreateModal.test.jsx).
    const request = buildRequest({
      summary: { status: 'READY_FOR_CEO_REVIEW' },
      items: [{ ...buildRequest().items[0], requestedCurrency: 'THB', requestedPriceUnitBasis: 'PER_PIECE' }],
    });
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
        // V185: the client no longer sends requestedQty at all (the server derives it via
        // WastageCalculator) — piecesInput is the wire field the edited "จำนวน" input feeds.
        items: [expect.objectContaining({ piecesInput: 30 })],
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

  // P0/P1a fix (2026-09): LandedCostCalculator now aggregates every problem across every item
  // into ONE 422 whose message is a heading line + one bullet per problem (see
  // LandedCostCalculator#aggregateProblems), and FxResolver's own staleness/missing-rate messages
  // are similarly meant to be read in full, not truncated. Both startCeoReview and
  // recalculateDecisionCost render that error inline in this panel instead of the toast — see
  // ceoCostingError's own comment in the page for why the toast is wrong for this (collapses '\n'
  // to a space in Toast.jsx, auto-dismisses after 3200ms in useToast.js).
  describe('CEO costing error surfaced inline, not the toast (P0/P1a fix)', () => {
    const multiLineError = [
      'ไม่สามารถคำนวณต้นทุนได้ เนื่องจากพบปัญหาดังนี้:',
      '- รายการที่ 8001 (SCG A1) ในคำขอราคายังไม่ได้ระบุโรงงาน',
      '- อัตราแลกเปลี่ยน USD มีผล ณ วันที่ 2026-01-01 ซึ่งเก่าเกิน 7 วัน — กรุณาปรับปรุงที่ ตั้งค่า CEO → อัตราแลกเปลี่ยน ก่อนคำนวณต้นทุน',
    ].join('\n');

    async function renderReadyForRecalculate() {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({ items: [buildDecision()] });
      const utils = renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');
      return utils;
    }

    it("renders recalculateDecisionCost's multi-line 422 inline with every line intact, and never calls showToast for it", async () => {
      api.pricingRequests.recalculatePricingDecisionCost.mockRejectedValueOnce(new Error(multiLineError));
      const { showToast } = await renderReadyForRecalculate();

      fireEvent.click(screen.getByTestId('pcr-ceo-recalculate-cost'));

      const alert = await screen.findByTestId('pcr-ceo-costing-error');
      expect(alert.getAttribute('role')).toBe('alert');
      // All three lines survive as distinct lines, not one run-on sentence — the '\n' in the
      // server's message is still literally '\n' in the DOM (only CSS decides how it wraps).
      expect(alert.textContent.split('\n')).toEqual(multiLineError.split('\n'));
      // The element that actually renders it preserves line breaks via the Tailwind utility the
      // task calls for — not Toast.jsx's plain <span>, which has no such class and collapses '\n'
      // to a space.
      const messageEl = alert.querySelector('p');
      expect(messageEl.className).toContain('whitespace-pre-line');
      // The toast-regression guard: every OTHER useActionMutation call site still reports its
      // error via showToast('error', ...) — these two must not, or this flips true.
      expect(showToast).not.toHaveBeenCalledWith('error', expect.anything());
    });

    it('does not auto-dismiss — it outlives the toast\'s own 3.2s window (real timers, deliberately)', async () => {
      api.pricingRequests.recalculatePricingDecisionCost.mockRejectedValueOnce(new Error(multiLineError));
      await renderReadyForRecalculate();

      fireEvent.click(screen.getByTestId('pcr-ceo-recalculate-cost'));
      await screen.findByTestId('pcr-ceo-costing-error');

      // Real timers on purpose (fake timers stall @testing-library's own setTimeout-based
      // polling — see the repo's other date/timer test comments) — long enough that
      // useToast.js's 3200ms auto-dismiss would already have fired had this gone through the
      // toast instead.
      await new Promise((resolve) => { setTimeout(resolve, 3300); });

      expect(screen.getByTestId('pcr-ceo-costing-error')).not.toBeNull();
    }, 10000);

    it('lets the CEO dismiss the inline costing error manually', async () => {
      api.pricingRequests.recalculatePricingDecisionCost.mockRejectedValueOnce(new Error(multiLineError));
      await renderReadyForRecalculate();

      fireEvent.click(screen.getByTestId('pcr-ceo-recalculate-cost'));
      const alert = await screen.findByTestId('pcr-ceo-costing-error');

      fireEvent.click(within(alert).getByRole('button', { name: 'ปิดข้อความนี้' }));

      expect(screen.queryByTestId('pcr-ceo-costing-error')).toBeNull();
    });

    it('clears the inline error once a retry succeeds', async () => {
      api.pricingRequests.recalculatePricingDecisionCost.mockRejectedValueOnce(new Error(multiLineError));
      await renderReadyForRecalculate();

      fireEvent.click(screen.getByTestId('pcr-ceo-recalculate-cost'));
      await screen.findByTestId('pcr-ceo-costing-error');

      api.pricingRequests.recalculatePricingDecisionCost.mockResolvedValueOnce({});
      fireEvent.click(screen.getByTestId('pcr-ceo-recalculate-cost'));

      await waitFor(() => expect(screen.queryByTestId('pcr-ceo-costing-error')).toBeNull());
    });

    it("renders startPricingDecision's error inline the same way, before any decision exists", async () => {
      const request = buildRequest({ summary: { status: 'READY_FOR_CEO_REVIEW' } });
      api.pricingRequests.startPricingDecision.mockRejectedValueOnce(new Error(multiLineError));
      const { showToast } = renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);

      fireEvent.click(await screen.findByRole('button', { name: 'เริ่มพิจารณาราคาขาย' }));

      const alert = await screen.findByTestId('pcr-ceo-costing-error');
      expect(alert.textContent.split('\n')).toEqual(multiLineError.split('\n'));
      expect(showToast).not.toHaveBeenCalledWith('error', expect.anything());
    });
  });

  // P1b fix (2026-09): the backend batching means costing is faster than before, but it is still
  // a multi-second round trip — these two controls must SHOW that, not just go `disabled` with no
  // other signal (issue: "รีเฟรชใช้เวลานานนิดนึง" read as broken, not slow).
  describe('costing pending state (P1b fix)', () => {
    it('busies the recalculate icon button and reflects it in the accessible name while in flight', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({ items: [buildDecision()] });
      let resolveRecalc;
      api.pricingRequests.recalculatePricingDecisionCost.mockReturnValue(
        new Promise((resolve) => { resolveRecalc = resolve; }),
      );
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');

      const button = screen.getByTestId('pcr-ceo-recalculate-cost');
      fireEvent.click(button);

      await waitFor(() => expect(button.getAttribute('aria-busy')).toBe('true'));
      expect(button.disabled).toBe(true);
      expect(button.getAttribute('aria-label')).toBe('กำลังคำนวณต้นทุนใหม่…');

      resolveRecalc({});
      await waitFor(() => expect(button.getAttribute('aria-busy')).toBeNull());
    });

    it('busies the start-review button and shows "กำลังคำนวณ…" while in flight', async () => {
      const request = buildRequest({ summary: { status: 'READY_FOR_CEO_REVIEW' } });
      let resolveStart;
      api.pricingRequests.startPricingDecision.mockReturnValue(
        new Promise((resolve) => { resolveStart = resolve; }),
      );
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);

      const button = await screen.findByTestId('pcr-ceo-start-review');
      fireEvent.click(button);

      await waitFor(() => expect(button.getAttribute('aria-busy')).toBe('true'));
      expect(button.disabled).toBe(true);
      // Button's `loading` hides the children visually (opacity), not from the accessibility
      // tree, so the accessible name reflects the in-progress state too.
      expect(button.textContent).toContain('กำลังคำนวณ…');

      resolveStart({});
      await waitFor(() => expect(button.getAttribute('aria-busy')).toBeNull());
    });
  });

  // P2 fix (2026-09): PricingDecisionService.computeSellingPrice is cost x (1 + margin) x
  // sellingBuffer, rounded HALF_UP to 2dp (owner ruling 2026-09-19, Phase 2 CEO pricing —
  // formerly rounded UP to the nearest ฿10) with NO VAT term — VAT 7% is only ever added later,
  // on the customer quotation. Every place a CEO or sales rep reads a selling price on this page
  // must say so.
  describe('selling price is stated as ก่อน VAT (P2 fix)', () => {
    it('shows a persistent ก่อน VAT note in the CEO panel without expanding anything, and labels the per-item line', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({ items: [buildDecision()] });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');

      // Visible without expanding "วิธีคำนวณราคานี้" — this assertion runs before any
      // expandDerivation() call in this test, unlike the collapsible-only mention of the
      // multiplier elsewhere in this panel.
      expect(screen.getByText(/ราคาขายทุกรายการในหน้านี้เป็นราคาก่อน VAT/)).not.toBeNull();
      expect(screen.getByText(/ราคาขาย \(ก่อน VAT\).*฿72\.00/)).not.toBeNull();
    });

    it('labels the sales-facing approved price ก่อน VAT — the figure the rep quotes to the customer', async () => {
      const request = buildRequest({ summary: { status: 'APPROVED_FOR_QUOTATION' } });
      api.pricingRequests.getPricingDecisionSalesView.mockResolvedValue({ decision: buildSalesView() });
      renderDetailPage({ user: salesOwner, request });
      await waitForLoaded(request);

      expect(await screen.findByText(/ราคาขายทุกรายการในหน้านี้เป็นราคาก่อน VAT/)).not.toBeNull();
      expect(screen.getByText(/ราคาขาย \(ก่อน VAT\).*฿72\.00/)).not.toBeNull();
      // Design correction 2 still holds — no cost/margin field leaks into this sales-facing view.
      expect(screen.queryByText(/ต้นทุน/)).toBeNull();
      expect(screen.queryByText(/อัตรากำไร/)).toBeNull();
    });
  });

  // ── Phase 2, CEO pricing method (owner rulings 2026-09-18/19, V187) ────────────────────────
  describe('CEO price mode (Phase 2)', () => {
    function newFormItem(overrides = {}) {
      return buildDecisionItem({ sqmPerPiece: 0.36, ...overrides });
    }

    it('shows no mode picker for a legacy decision (default fixture has no sqmPerPiece)', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({ items: [buildDecision()] });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');

      expect(screen.queryByRole('group', { name: 'วิธีกรอกราคา' })).toBeNull();
      // Legacy UI (ปรับราคาเอง, formula derivation) still renders exactly as before.
      expandDerivation();
      expect(screen.getByTestId('pcr-ceo-price-override-8001')).not.toBeNull();
    });

    it('shows the mode picker for a new-form-eligible decision, and none of the legacy margin UI', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({
        items: [buildDecision({ items: [newFormItem()] })],
      });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');

      expect(screen.getByRole('group', { name: 'วิธีกรอกราคา' })).not.toBeNull();
      expect(screen.getByRole('button', { name: 'ราคาตั้ง − ส่วนลด %' })).not.toBeNull();
      expect(screen.getByRole('button', { name: 'ราคาพิเศษ บาท/ตร.ม.' })).not.toBeNull();
      expect(screen.getByRole('button', { name: 'ราคาสุทธิต่อแผ่น' })).not.toBeNull();
      // Opus review finding #1 (2026-09-19), and review minors #6/#8 (2026-09-19): the cost
      // machinery (ปรับต้นทุนเอง, ประเภทสินค้า) is KEPT for a new-form item too, so the CEO can
      // still correct cost/duty (e.g. mosaic at 10%) — it no longer disappears the way the first
      // Phase 2 pass wrongly removed it, AND now renders in the card's MAIN BODY (no longer inside
      // a collapsed "วิธีคำนวณราคานี้" section — no expanding needed). "ปรับราคาเอง" (now
      // "ปรับราคาตั้งเอง", owner ruling B) is also kept, reusing the SAME testid — it replaces
      // the auto list price, not the final price. Default fixture's priceMode is null (no mode
      // chosen yet), and the override control shows for null-or-NET (review minor #1) — only
      // DIRECT_NET/SPECIAL_SQM hide it, see the "hides ปรับราคาตั้งเอง" tests below.
      expect(screen.getByTestId('pcr-ceo-price-override-8001')).not.toBeNull();
    });

    it('keeps the cost override AND duty/product-type override reachable for a new-form item too (Opus review finding #1), visible in the main body without expanding anything (review minor #8)', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      const costingItem = buildCostingItemWithOverride({ id: 1 });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({
        items: [buildDecision({ items: [newFormItem({ pricingCostingItemId: costingItem.id })] })],
      });
      // buildDecision()'s default pricingCostingId (601) does NOT match buildCosting()'s default
      // id (21) -- same deliberate id: 601 override renderWithCostingItem's own comment explains.
      renderDetailPage({ user: ceoUser, request, costings: [buildCosting({ id: 601, items: [costingItem] })] });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');

      expect(screen.getByTestId('pcr-ceo-cost-override-8001')).not.toBeNull();
      expect(screen.getByTestId('pcr-ceo-product-type-override-8001')).not.toBeNull();
    });

    // Reproduced against a genuine new-form decision, per the coordinator's screenshots of the
    // mock demo (2026-09-19): a seeded decision item forced to PER_PIECE with sqmPerPiece 0.72 and
    // listUnitPrice = proposed (83), NET mode with 10% typed but NOT yet saved.
    it('shows the auto formula price (ราคาตั้ง (สูตร)) in the main body without expanding anything (review minor #6)', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({
        items: [buildDecision({
          priceMode: 'NET',
          items: [newFormItem({
            sqmPerPiece: 0.72, proposedSellingPricePerRequestedUnit: 83, listUnitPrice: 83, netUnitPrice: 83,
          })],
        })],
      });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');

      // Never inside CollapsibleSection any more -- no click/expand call in this test at all.
      // getByText's regex match is a substring test against the whole matched element's
      // textContent, so this returns the row itself (label + both <code> figures) -- checked
      // this way rather than a second getByText(/฿83\.00/) because netUnitPrice is ALSO 83 here
      // (ruling A's zero-discount auto-fill), which renders its own separate "฿83.00" elsewhere
      // on the card.
      const formulaPriceRow = screen.getByText(/ราคาตั้ง \(สูตร\)/);
      expect(formulaPriceRow.textContent).toContain('83.00');
      // 83 / 0.72 = 115.2777... -> round2 -> 115.28.
      expect(formulaPriceRow.textContent).toContain('115.28');
    });

    it('the live preview reflects a typed discount even when a net price was already saved (review minor #7)', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({
        items: [buildDecision({
          priceMode: 'NET',
          // Mirrors the repro exactly: listUnitPrice = proposed = 83, already saved at
          // netUnitPrice = 83 (ruling A's zero-discount auto-fill), 1000 requested pieces so the
          // line-total figures match the coordinator's own worked numbers.
          items: [newFormItem({
            sqmPerPiece: 0.72, listUnitPrice: 83, discountPct: null, netUnitPrice: 83, requestedQuantity: 1000,
          })],
        })],
      });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');

      // Before typing: shows the SAVED net, no "ตัวอย่าง" caveat.
      expect(screen.getByText(/฿83\.00/)).not.toBeNull();
      expect(screen.queryByText(/ตัวอย่าง ยังไม่บันทึก/)).toBeNull();

      fireEvent.change(screen.getByTestId('pcr-ceo-discount-8001'), { target: { value: '10' } });

      // round2(83 x (1 - 10/100)) = 74.70 -- NOT the stale saved 83.00 any more.
      expect(await screen.findByText(/74\.70/)).not.toBeNull();
      expect(screen.getByText(/ตัวอย่าง ยังไม่บันทึก/)).not.toBeNull();
      // รวมเป็นเงิน follows the live preview too: round2(1000 x 74.70) = 74,700.00.
      expect(screen.getByText(/74,700\.00/)).not.toBeNull();
    });

    it('aligns "รวมเป็นเงิน" with the net-price figure, not detached near the top of the card (review minor #9)', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({
        items: [buildDecision({ priceMode: 'NET', items: [newFormItem({ listUnitPrice: 83, netUnitPrice: 83 })] })],
      });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');

      const netLabel = screen.getByText(/ราคาสุทธิ\/แผ่น/);
      const totalLabel = screen.getByText(/รวมเป็นเงิน/);
      expect(netLabel.closest('div')).toBe(totalLabel.closest('div'));
    });

    it('shows ปรับต้นทุนเอง/ปรับราคาตั้งเอง in the main body next to the prices (review minor #8), without expanding anything', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({
        items: [buildDecision({ priceMode: 'NET', items: [newFormItem()] })],
      });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');

      const costButton = screen.getByTestId('pcr-ceo-cost-override-8001');
      const priceButton = screen.getByTestId('pcr-ceo-price-override-8001');
      const priceLabel = screen.getByText(/ราคาตั้ง \(สูตร\)/);
      // Same rendered row/section as the prices, not inside a collapsed element -- offsetParent
      // is null only for display:none/detached nodes in jsdom, which CollapsibleSection's
      // collapsed body actually unmounts entirely (see this file's own header comment on it), so
      // a plain presence check already proves "not hidden inside a collapsed section".
      expect(costButton).not.toBeNull();
      expect(priceButton).not.toBeNull();
      expect(priceLabel).not.toBeNull();
    });

    // Opus review minor #1 (2026-09-19): "ปรับราคาเอง" only ever affects the price under NET
    // (or before a mode is chosen) -- DIRECT_NET/SPECIAL_SQM never consult it, so offering it
    // there let the CEO believe an override priced a line it never touched.
    it('hides ปรับราคาตั้งเอง under DIRECT_NET (review minor #1)', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({
        items: [buildDecision({
          priceMode: 'DIRECT_NET', items: [newFormItem({ directNetPrice: 100, netUnitPrice: 100 })],
        })],
      });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');

      expect(screen.queryByTestId('pcr-ceo-price-override-8001')).toBeNull();
      // ปรับต้นทุนเอง stays reachable -- that IS the correct way to clear the uncosted gate here.
      expect(screen.getByTestId('pcr-ceo-cost-override-8001')).not.toBeNull();
    });

    it('hides ปรับราคาตั้งเอง under SPECIAL_SQM (review minor #1)', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({
        items: [buildDecision({
          priceMode: 'SPECIAL_SQM', items: [newFormItem({ specialPriceSqm: 1350, netUnitPrice: 453.84 })],
        })],
      });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');

      expect(screen.queryByTestId('pcr-ceo-price-override-8001')).toBeNull();
    });

    it('switching an already-chosen mode asks for confirmation before calling updatePricingDecision', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({
        items: [buildDecision({ priceMode: 'NET', items: [newFormItem({ netUnitPrice: 900 })] })],
      });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');

      fireEvent.click(screen.getByTestId('pcr-ceo-price-mode-DIRECT_NET'));
      expect(api.pricingRequests.updatePricingDecision).not.toHaveBeenCalled();

      const dialog = await screen.findByRole('dialog', { name: 'เปลี่ยนวิธีกรอกราคา' });
      expect(within(dialog).getByText(/ค่าที่กรอกไว้ของวิธีเดิมจะไม่ถูกใช้/)).not.toBeNull();
      fireEvent.click(within(dialog).getByRole('button', { name: 'เปลี่ยนวิธีกรอกราคา' }));

      await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalledWith(
        7001, { priceMode: 'DIRECT_NET' },
      ));
    });

    it('picking a mode for the FIRST time (no prior mode) needs no confirmation', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({
        items: [buildDecision({ items: [newFormItem()] })],
      });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');

      fireEvent.click(screen.getByTestId('pcr-ceo-price-mode-NET'));
      expect(screen.queryByRole('dialog', { name: 'เปลี่ยนวิธีกรอกราคา' })).toBeNull();
      await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalledWith(
        7001, { priceMode: 'NET' },
      ));
    });

    it('disables approve when an item has no cost and no ปรับราคาเอง — mirrors the server\'s uncosted gate', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({
        items: [buildDecision({
          priceMode: 'NET',
          items: [newFormItem({
            frozenLandedCostPerRequestedUnitThb: null, listUnitPrice: null, netUnitPrice: null,
            manualSellingPricePerRequestedUnit: null,
          })],
        })],
      });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');

      expect(screen.getByTestId('pcr-ceo-approve').disabled).toBe(true);
      expect(screen.getByText(/ทุกรายการต้องมีต้นทุนก่อนอนุมัติ/)).not.toBeNull();
    });

    it('picking NET calls updatePricingDecision with priceMode alone', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({
        items: [buildDecision({ items: [newFormItem()] })],
      });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');

      fireEvent.click(screen.getByTestId('pcr-ceo-price-mode-NET'));

      await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalledWith(
        7001, { priceMode: 'NET' },
      ));
    });

    it('NET mode: no list-price input exists; typing a discount previews against the SERVER list price and saves only the discount', async () => {
      // Owner correction (2026-09-19): list_unit_price is never CEO-typed (ruling A) — it is
      // always the server-computed formula price, so this decision item's fixture supplies it
      // directly (mirroring what startReview/overrideItemCost would have populated), and the
      // page must render it read-only rather than as an editable input.
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({
        items: [buildDecision({ priceMode: 'NET', items: [newFormItem({ listUnitPrice: 1000 })] })],
      });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');

      expect(screen.queryByTestId('pcr-ceo-list-price-8001')).toBeNull();
      fireEvent.change(screen.getByTestId('pcr-ceo-discount-8001'), { target: { value: '10' } });

      // Client PREVIEW only (round2(1000 * (1 - 10/100)) = 900.00), computed against the
      // server-provided item.listUnitPrice — never sent to the server as a stored value; the
      // server recomputes and returns its own netUnitPrice on save.
      expect(await screen.findByText(/900\.00/)).not.toBeNull();
      expect(screen.getByText(/ตัวอย่าง ยังไม่บันทึก/)).not.toBeNull();

      fireEvent.click(screen.getByTestId('pcr-ceo-save-price-8001'));

      await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalledWith(
        7001,
        {
          items: [{
            pricingDecisionItemId: 8001,
            discountPct: 10,
            clearDiscountPct: false,
            specialPriceSqm: null,
            clearSpecialPriceSqm: false,
            directNetPrice: null,
            clearDirectNetPrice: false,
            clearSellingPriceOverride: false,
          }],
        },
      ));
    });

    it('SPECIAL_SQM mode: shows "คำนวณเมื่อบันทึก" instead of a client-computed preview', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({
        items: [buildDecision({ priceMode: 'SPECIAL_SQM', items: [newFormItem()] })],
      });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');

      fireEvent.change(screen.getByTestId('pcr-ceo-special-sqm-8001'), { target: { value: '1350' } });

      // Never a client-side number for this mode — WastageCalculator#netPerPieceFromSpecialSqm's
      // rounding order is the algorithm; only the real service may compute it (see
      // previewCeoNetUnitPrice's own header comment).
      expect(screen.getByText('คำนวณเมื่อบันทึก')).not.toBeNull();

      fireEvent.click(screen.getByTestId('pcr-ceo-save-price-8001'));
      await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalledWith(
        7001,
        {
          items: [{
            pricingDecisionItemId: 8001,
            discountPct: null,
            clearDiscountPct: false,
            specialPriceSqm: 1350,
            clearSpecialPriceSqm: false,
            directNetPrice: null,
            clearDirectNetPrice: false,
            clearSellingPriceOverride: false,
          }],
        },
      ));
    });

    it('DIRECT_NET mode: preview IS the typed value; shows the server-saved netUnitPrice once present', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({
        items: [buildDecision({
          priceMode: 'DIRECT_NET',
          items: [newFormItem({ directNetPrice: 777, netUnitPrice: 777 })],
        })],
      });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');

      // Already saved (netUnitPrice present) — shown as the real figure, no "ตัวอย่าง" caveat.
      expect(screen.getByText(/฿777\.00/)).not.toBeNull();
      expect(screen.queryByText(/ตัวอย่าง ยังไม่บันทึก/)).toBeNull();
    });

    it('disables approve until every new-form item has a netUnitPrice, and shows the Thai warning', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({
        items: [buildDecision({
          priceMode: 'NET',
          items: [newFormItem({ id: 8001, netUnitPrice: null }), newFormItem({ id: 8002, netUnitPrice: 900 })],
        })],
      });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');

      expect(screen.getByTestId('pcr-ceo-approve').disabled).toBe(true);
      expect(screen.getByText('ทุกรายการต้องมีราคาตามวิธีกรอกราคาที่เลือกก่อนอนุมัติ')).not.toBeNull();
    });

    it('CEO-only: import never sees the mode picker or price-mode inputs (server already strips the fields; UI only ever renders this panel for ceo)', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({
        items: [buildDecision({ priceMode: 'NET', items: [newFormItem({ listUnitPrice: 1000 })] })],
      });
      renderDetailPage({ user: importUser, request });
      await waitForLoaded(request);

      // Import never even reaches the CEO panel (canSeeRawPricingDecision(user) && !isImport(user)
      // gates it out entirely) — mirrors the existing "Import sees no CEO decision UI" coverage
      // for the legacy panel.
      expect(screen.queryByText('การพิจารณาราคาขายของ CEO')).toBeNull();
      expect(screen.queryByRole('group', { name: 'วิธีกรอกราคา' })).toBeNull();
    });
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

// V185 (direct-deal-form parity) + owner ruling 2026-09-18 (label reversed back to โรงงาน, not
// ยี่ห้อ — see PricingRequestDetailPage.jsx's own comment on brandDisplay): the "รายการสินค้าและ
// ราคาตั้งต้น" item card shows every sales-entered tile field read-only, and hides เผื่อ
// (wastage)/the pre-wastage quantity from Import specifically — everyone else (sales, CEO) still
// sees the full breakdown. UI scoping only, no backend authz change (buildRequest's base fixture
// already carries color/thicknessMm/sqmPerPiece/quantityMode/piecesInput/piecesPerBox — see that
// fixture's own V185 comment).
describe('PricingRequestDetailPage item card — V185 sales-entered fields', () => {
  it('shows the sales-entered tile fields read-only to every viewer', async () => {
    renderDetailPage({ user: salesOwner });
    await waitForLoaded();

    expect(screen.getByText('สี: ขาว')).not.toBeNull();
    expect(screen.getByText('ผิว: ด้าน')).not.toBeNull();
    expect(screen.getByText('ขนาด: 60x60')).not.toBeNull();
    expect(screen.getByText('ความหนา: 10 มม.')).not.toBeNull();
    expect(screen.getByText('แผ่น/กล่อง: 4')).not.toBeNull();
  });

  it('shows เผื่อ (wastage) and the pre-wastage quantity to Sales', async () => {
    renderDetailPage({ user: salesOwner });
    await waitForLoaded();

    expect(screen.getByText(/^เผื่อ \(wastage\):/)).not.toBeNull();
    expect(screen.getByText(/^จำนวนที่กรอก:/)).not.toBeNull();
  });

  it('shows เผื่อ (wastage) and the pre-wastage quantity to the CEO', async () => {
    renderDetailPage({ user: ceoUser });
    await waitForLoaded();

    expect(screen.getByText(/^เผื่อ \(wastage\):/)).not.toBeNull();
    expect(screen.getByText(/^จำนวนที่กรอก:/)).not.toBeNull();
  });

  it('hides เผื่อ (wastage) and the pre-wastage quantity from Import — final order quantity only', async () => {
    renderDetailPage({ user: importUser });
    await waitForLoaded();

    expect(screen.queryByText(/^เผื่อ \(wastage\):/)).toBeNull();
    expect(screen.queryByText(/^จำนวนที่กรอก:/)).toBeNull();
    // The final order quantity (what must actually be ordered) stays visible to Import.
    expect(screen.getByText(/^จำนวนสั่งซื้อ:/)).not.toBeNull();
  });

  it('shows the final order quantity (จำนวนสั่งซื้อ) to every viewer, including Import', async () => {
    for (const user of [salesOwner, importUser, ceoUser]) {
      const { unmount } = renderDetailPage({ user });
      await waitForLoaded();
      expect(screen.getByText(/^จำนวนสั่งซื้อ:/)).not.toBeNull();
      unmount();
    }
  });

  it('renders "—" for a legacy item with none of the new fields', async () => {
    const request = buildRequest({
      items: [{
        id: 1, sourceTicketItemId: null, productId: null, brand: 'SCG', model: 'A1',
        catalogBrand: null, catalogModel: null, productDescription: 'กระเบื้องพื้น SCG A1',
        texture: null, size: null, color: null,
        thicknessMm: null, sqmPerPiece: null, quantityMode: null, piecesInput: null,
        piecesPerBox: null, roundToFullBox: true,
        quantityType: 'CONFIRMED', requestedQty: 20, requestedUnit: 'แผ่น',
        requestedUnitBasis: 'PER_PIECE', resolvedFactoryName: 'SCG Ceramics', factory: null,
        catalogProductCode: 'SCG-A1', catalogBasePrice: 120, catalogCurrency: 'THB',
        targetDeliveryDate: null, deliveryLocation: null, specialRequirement: null,
      }],
    });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);

    expect(screen.getByText('สี: —')).not.toBeNull();
    expect(screen.getByText('ผิว: —')).not.toBeNull();
    expect(screen.getByText('ความหนา: —')).not.toBeNull();
    expect(screen.getByText('แผ่น/กล่อง: —')).not.toBeNull();
    // The legacy row's OWN requestedQty/requestedUnit (client-typed under the old form) still
    // renders in the final-order-quantity line — this never depended on the new columns.
    expect(screen.getByText('จำนวนสั่งซื้อ: 20 แผ่น')).not.toBeNull();
  });

  // Owner ruling 2026-09-18: the label is โรงงาน, not ยี่ห้อ — but the VALUE shown is still the
  // sales-entered brand, captioned to distinguish it from Import's own factory-assignment control
  // just below (which also says โรงงาน — see PricingRequestDetailPage.jsx's own comment).
  it('shows the sales-entered value captioned "โรงงาน (ที่ฝ่ายขายกรอก)", never the old bare "Factory:" label', async () => {
    renderDetailPage({ user: importUser });
    await waitForLoaded();

    expect(screen.getByText(/^โรงงาน \(ที่ฝ่ายขายกรอก\):/)).not.toBeNull();
    expect(screen.queryByText(/^Factory:/)).toBeNull();
    expect(screen.queryByText(/^ยี่ห้อ:/)).toBeNull();
  });

  // Opus review finding #6 (2026-09-18): before this fix, a card whose sales rep left the
  // โรงงาน-labelled brand field blank fell back to showing Import's ROUTING resolution
  // (resolvedFactoryName/factory) under the "(ที่ฝ่ายขายกรอก)" caption — misattributing an
  // Import/catalog value to Sales. The two must render as separate lines instead.
  it('shows the sales value and Import\'s resolved factory as two SEPARATE lines, never one falling back to the other', async () => {
    renderDetailPage({ user: importUser });
    await waitForLoaded();

    // The default fixture's item has a real `brand` ("SCG") AND a real `resolvedFactoryName`
    // ("SCG Ceramics") that DIFFER — proving neither line is standing in for the other.
    expect(screen.getByText('โรงงาน (ที่ฝ่ายขายกรอก): SCG')).not.toBeNull();
    expect(screen.getByText('โรงงานที่กำหนด (Import): SCG Ceramics')).not.toBeNull();
  });

  it('shows the sales value as em-dash (never Import\'s routing resolution) when Sales left the brand blank, plus an amber "ยังไม่ได้ระบุ" on the routing line when nothing has resolved one either', async () => {
    const request = buildRequest({
      items: [{ ...buildRequest().items[0], brand: null, resolvedFactoryName: null, factory: null }],
    });
    renderDetailPage({ user: importUser, request });
    await waitForLoaded();

    expect(screen.getByText('โรงงาน (ที่ฝ่ายขายกรอก): —')).not.toBeNull();
    const routingLine = screen.getByText(/^โรงงานที่กำหนด \(Import\):/);
    expect(routingLine.textContent).toBe('โรงงานที่กำหนด (Import): ยังไม่ได้ระบุ');
    expect(routingLine.className).toContain('text-warning-dark');
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

  // GLA-123 slice S1 M2 fix (Opus review, 2026-09-20): mutual exclusivity means offering BOTH
  // create buttons on a new-form (CEO price-mode) request just invites a wasted click on the
  // now-redundant OLD path — server-side, starting it would either 409 (if the NEW quotation
  // already exists) or itself block the NEW path from ever being started. A legacy decision
  // (newFormPricing false) has no such redundancy, so it keeps offering both, exactly as before.
  //
  // MINOR-1 fix (owner ruling, confirmed 2026-09-20, second re-review): the fixture below used to
  // pass `priceMode` — the field PricingDecisionSalesViewDto has SINCE replaced with the plain
  // `newFormPricing` boolean (never leaks which pricing method the CEO chose, since this endpoint
  // is also legitimately callable by import). Updated so this test still exercises the REAL
  // signal the component reads, not a stale field name it would now silently ignore.
  it('hides the old "สร้างร่างใบเสนอราคาลูกค้า" button once the decision is new-form, but keeps the new-engine button', async () => {
    const request = buildRequest({ summary: { status: 'APPROVED_FOR_QUOTATION' } });
    api.pricingRequests.getPricingDecisionSalesView.mockResolvedValue({
      decision: buildSalesView({ newFormPricing: true }),
    });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);

    await screen.findByRole('button', { name: 'เขียนใบเสนอราคาจากคำขอราคา' });
    expect(screen.queryByRole('button', { name: 'สร้างร่างใบเสนอราคาลูกค้า' })).toBeNull();
  });

  it('keeps offering BOTH create buttons for a legacy (pre-V187) decision that is not new-form', async () => {
    const request = buildRequest({ summary: { status: 'APPROVED_FOR_QUOTATION' } });
    api.pricingRequests.getPricingDecisionSalesView.mockResolvedValue({
      decision: buildSalesView({ newFormPricing: false }),
    });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);

    await screen.findByRole('button', { name: 'สร้างร่างใบเสนอราคาลูกค้า' });
    expect(screen.queryByRole('button', { name: 'เขียนใบเสนอราคาจากคำขอราคา' })).not.toBeNull();
  });

  // Coordinator repro (2026-09-20): clicking "เขียนใบเสนอราคาจากคำขอราคา" on a request whose
  // ticket/PR carries no ผู้สั่งซื้อ (recipientContactId) 409s server-side with a clear Thai
  // message, but nothing on screen showed it — no toast, no inline error, no navigation. This
  // pins that the click surfaces the server's message via showToast('error', ...).
  it('shows the server\'s error as a toast when create-from-PCR fails (e.g. missing ผู้สั่งซื้อ), instead of failing silently', async () => {
    const request = buildRequest({ summary: { status: 'APPROVED_FOR_QUOTATION' } });
    const error = new Error('กรุณาระบุผู้สั่งซื้อ');
    error.status = 400;
    api.dealQuotations.createFromPricingRequest.mockRejectedValue(error);
    const { showToast } = renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);

    const button = await screen.findByRole('button', { name: 'เขียนใบเสนอราคาจากคำขอราคา' });
    fireEvent.click(button);

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', 'กรุณาระบุผู้สั่งซื้อ'));
  });

  // GLA-123 slice S1 M1 fix (Opus review, 2026-09-20): the NEW engine's quotation is displayed
  // in this SAME "ใบเสนอราคาลูกค้า" panel (number/status/link), and — since one already exists —
  // neither create button is offered (there is nothing left to create).
  it('shows the new-engine quotation (number, status, link) once one exists, and hides both create buttons', async () => {
    const request = buildRequest({ summary: { status: 'APPROVED_FOR_QUOTATION' } });
    api.dealQuotations.findForPricingRequest.mockResolvedValue({
      quotation: { id: 9001, number: 'QT-2026-0099-1', docStatus: 'DRAFT' },
    });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);

    await screen.findByText('QT-2026-0099-1');
    const link = screen.getByRole('link', { name: 'เปิดใบเสนอราคา' });
    expect(link.getAttribute('href')).toBe('/quotations/9001');
    expect(screen.queryByRole('button', { name: 'สร้างร่างใบเสนอราคาลูกค้า' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'เขียนใบเสนอราคาจากคำขอราคา' })).toBeNull();
  });

  // MAJOR-3 fix (owner ruling via coordinator, 2026-09-20 — "the expiry escape hatch"): once the
  // linked quotation EXPIRES, the owning sales rep gets a plain note + a button to write a fresh
  // one from the same approved decision — DealQuotationService#createFromPricingRequest now
  // tolerates this specific case (PR status QUOTATION_ISSUED, no live quotation) server-side.
  it('offers "เขียนใบเสนอราคาใหม่" once the linked quotation has EXPIRED, calling the same createFromPricingRequest endpoint', async () => {
    // The PR itself sits at QUOTATION_ISSUED once its quotation issued — canManageCustomerQuotation
    // (not canCreateCustomerQuotation) is the gate this button uses precisely because
    // APPROVED_FOR_QUOTATION no longer holds at this point in the lifecycle.
    const request = buildRequest({ summary: { status: 'QUOTATION_ISSUED' } });
    api.dealQuotations.findForPricingRequest.mockResolvedValue({
      quotation: { id: 9002, number: 'QT-2026-0099-1', docStatus: 'EXPIRED' },
    });
    api.dealQuotations.createFromPricingRequest.mockResolvedValue({
      quotation: { id: 9003, number: 'QT-2026-0099-2', docStatus: 'DRAFT' },
    });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);

    expect(await screen.findByText('ใบเสนอราคาหมดอายุแล้ว — เขียนใบใหม่ได้')).not.toBeNull();
    const button = screen.getByRole('button', { name: 'เขียนใบเสนอราคาใหม่จากคำขอราคา' });
    fireEvent.click(button);

    await waitFor(() => expect(api.dealQuotations.createFromPricingRequest)
      .toHaveBeenCalledWith(request.summary.id));
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

// GLA-123 slice S3 BLOCKER 1 fix (Opus review against real Postgres, 2026-09-23):
// DealQuotationService#findForPricingRequest used to call the DRAFT-only
// findOpenDraftForPricingRequest, so dealQuotationForPr was null for every status past DRAFT and
// this whole panel (outcome-recording, the EXPIRED escape hatch) was unreachable in production —
// even though these mock-driven tests already exercised the UI logic correctly, since they mock
// the API response directly and never went through the real (buggy) backend query. These tests
// pin the SAME UI behaviour per status the legacy "Step 5" describe block above pins for the old
// engine, now for the new one.
//
// CORRECTION (Opus MAJOR-A re-review, 2026-09-23): the sentence this replaced claimed these tests
// also catch a regression in the BACKEND query — false. They stub api.dealQuotations
// .findForPricingRequest's RESOLVED VALUE directly (see newEngineQuotation()/mockResolvedValue
// below), so reverting DealQuotationService#findForPricingRequest to the old DRAFT-only reader
// leaves every one of these 4 tests green — proven: 73/73 still passed under that mutation. The
// real backend-query regression coverage is
// DealQuotationOutcomeIntegrationTest#findForPricingRequest_returnsTheQuotationPastDraft (real
// Postgres, added for MAJOR-A) — THAT is what a future regression on the repository method is
// caught by, not this file.
describe('PricingRequestDetailPage GLA-123 slice S3: new-engine (dealQuotationForPr) outcome + expiry', () => {
  function newEngineQuotation(overrides = {}) {
    return { id: 9101, number: 'QT-2026-0101-1', docStatus: 'ISSUED', ...overrides };
  }

  it('ISSUED: offers the outcome-recording controls to the owning sales rep, and records ACCEPTED via dealQuotations.recordOutcome', async () => {
    const issued = newEngineQuotation({ docStatus: 'ISSUED' });
    api.dealQuotations.findForPricingRequest.mockResolvedValue({ quotation: issued });
    renderDetailPage({ user: salesOwner });
    await waitForLoaded();
    await screen.findByText(issued.number);

    const acceptButton = await screen.findByRole('button', { name: 'ลูกค้ายอมรับ' });
    fireEvent.click(acceptButton);

    await waitFor(() => expect(api.dealQuotations.recordOutcome).toHaveBeenCalledWith(
      issued.id,
      expect.objectContaining({ outcome: 'ACCEPTED', clientRequestId: expect.any(String) }),
    ));
  });

  it('ISSUED: never shows the outcome-recording controls to CEO or Import — read-only', async () => {
    const issued = newEngineQuotation({ docStatus: 'ISSUED' });
    api.dealQuotations.findForPricingRequest.mockResolvedValue({ quotation: issued });
    renderDetailPage({ user: ceoUser });
    await waitForLoaded();
    await screen.findByText(issued.number);

    expect(screen.queryByRole('button', { name: 'ลูกค้ายอมรับ' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'ลูกค้าปฏิเสธ' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'ลูกค้าขอแก้ไข' })).toBeNull();
  });

  it('ACCEPTED: hides the outcome-recording controls and shows the read-only outcome summary', async () => {
    const accepted = newEngineQuotation({ docStatus: 'ACCEPTED' });
    api.dealQuotations.findForPricingRequest.mockResolvedValue({ quotation: accepted });
    renderDetailPage({ user: salesOwner });
    await waitForLoaded();
    await screen.findByText(accepted.number);

    expect(screen.queryByRole('button', { name: 'ลูกค้ายอมรับ' })).toBeNull();
    expect(screen.getByText(/ผลใบเสนอราคา/)).not.toBeNull();
  });

  it('EXPIRED: the outcome panel is gone and the expiry escape hatch renders instead', async () => {
    const expired = newEngineQuotation({ docStatus: 'EXPIRED' });
    const request = buildRequest({ summary: { status: 'QUOTATION_ISSUED' } });
    api.dealQuotations.findForPricingRequest.mockResolvedValue({ quotation: expired });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);
    await screen.findByText(expired.number);

    expect(screen.queryByRole('button', { name: 'ลูกค้ายอมรับ' })).toBeNull();
    expect(screen.getByText('ใบเสนอราคาหมดอายุแล้ว — เขียนใบใหม่ได้')).not.toBeNull();
    expect(screen.getByRole('button', { name: 'เขียนใบเสนอราคาใหม่จากคำขอราคา' })).not.toBeNull();
  });

  // S3 round-3 review fix (NEW-1, MAJOR, 2026-09-23): BLOCKER-B's service-layer fix let a
  // REVISION_REQUESTED quotation be recreated (DealQuotationService#createFromPricingRequest /
  // #hasLivePricingRequestQuotation both treat it like EXPIRED), but the frontend's own recreate
  // gate only ever checked docStatus === 'EXPIRED' — so the owning rep hit a dead end: a
  // read-only "ผลใบเสนอราคา" line with no way to write the replacement the backend already
  // supports. Pins that the recreate button is now reachable for REVISION_REQUESTED too.
  it('REVISION_REQUESTED: the outcome panel is gone and the recreate escape hatch renders instead', async () => {
    const revisionRequested = newEngineQuotation({ docStatus: 'REVISION_REQUESTED' });
    const request = buildRequest({ summary: { status: 'QUOTATION_ISSUED' } });
    api.dealQuotations.findForPricingRequest.mockResolvedValue({ quotation: revisionRequested });
    api.dealQuotations.createFromPricingRequest.mockResolvedValue({
      quotation: { id: 9103, number: 'QT-2026-0101-2', docStatus: 'DRAFT' },
    });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);
    await screen.findByText(revisionRequested.number);

    expect(screen.queryByRole('button', { name: 'ลูกค้ายอมรับ' })).toBeNull();
    expect(screen.getByText('ลูกค้าขอแก้ไขใบเสนอราคา — เขียนใบใหม่ได้')).not.toBeNull();
    const button = screen.getByRole('button', { name: 'เขียนใบเสนอราคาใหม่จากคำขอราคา' });
    fireEvent.click(button);

    await waitFor(() => expect(api.dealQuotations.createFromPricingRequest)
      .toHaveBeenCalledWith(request.summary.id));
  });

  // Same S3 round-3 review fix (NEW-1) — REJECTED is the other status the backend already
  // supports a recreate from (same #hasLivePricingRequestQuotation non-live set) but the old
  // frontend gate never offered.
  it('REJECTED: the outcome panel is gone and the recreate escape hatch renders instead', async () => {
    const rejected = newEngineQuotation({ docStatus: 'REJECTED' });
    const request = buildRequest({ summary: { status: 'QUOTATION_ISSUED' } });
    api.dealQuotations.findForPricingRequest.mockResolvedValue({ quotation: rejected });
    api.dealQuotations.createFromPricingRequest.mockResolvedValue({
      quotation: { id: 9104, number: 'QT-2026-0101-2', docStatus: 'DRAFT' },
    });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);
    await screen.findByText(rejected.number);

    expect(screen.queryByRole('button', { name: 'ลูกค้ายอมรับ' })).toBeNull();
    expect(screen.getByText('ลูกค้าปฏิเสธใบเสนอราคา — เขียนใบใหม่ได้')).not.toBeNull();
    const button = screen.getByRole('button', { name: 'เขียนใบเสนอราคาใหม่จากคำขอราคา' });
    fireEvent.click(button);

    await waitFor(() => expect(api.dealQuotations.createFromPricingRequest)
      .toHaveBeenCalledWith(request.summary.id));
  });

  // S3 round-5 review fix (NEW-A, MAJOR, 2026-09-23): invalidate() (this file's source, around
  // line 815) invalidated seven query keys after recordDealQuotationOutcome's onSuccess but
  // OMITTED queryKeys.dealQuotationForPricingRequest — the key backing dealQuotationForPrQuery,
  // which both the outcome panel above AND the recreate gate just below it read. Because
  // ['pricingRequests','detail',id] does not prefix-match
  // ['pricingRequests','dealQuotationForPricingRequest',id], a rep recording an outcome got the
  // success toast but the screen kept showing the stale ISSUED DTO — and with it, the OLD
  // outcome-recording buttons instead of the new recreate affordance — until a manual reload or
  // the query's 30s staleTime lapsed (api/queryClient.js). This test proves the refetch actually
  // happens and the UI actually re-renders, WITHOUT a remount: it stubs
  // findForPricingRequest to first resolve ISSUED, then REVISION_REQUESTED on any later call, and
  // watches (a) the call count rise after the outcome is recorded, and (b) the recreate button
  // become reachable while the old outcome buttons disappear — proving invalidate() actually
  // reached this key and not just the six others already covered above.
  it('invalidates dealQuotationForPr after recording an outcome, so the recreate button appears without a reload', async () => {
    const issued = newEngineQuotation({ docStatus: 'ISSUED' });
    const revisionRequested = newEngineQuotation({ docStatus: 'REVISION_REQUESTED' });
    const request = buildRequest({ summary: { status: 'QUOTATION_ISSUED' } });
    api.dealQuotations.findForPricingRequest
      .mockResolvedValueOnce({ quotation: issued })
      .mockResolvedValue({ quotation: revisionRequested });
    api.dealQuotations.recordOutcome.mockResolvedValue({ quotation: revisionRequested });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);
    await screen.findByText(issued.number);

    const callsBeforeOutcome = api.dealQuotations.findForPricingRequest.mock.calls.length;
    const reviseButton = screen.getByRole('button', { name: 'ลูกค้าขอแก้ไข' });
    fireEvent.click(reviseButton);

    await waitFor(() => expect(api.dealQuotations.recordOutcome).toHaveBeenCalledWith(
      issued.id,
      expect.objectContaining({ outcome: 'REVISION_REQUESTED', clientRequestId: expect.any(String) }),
    ));

    // The regression this pins: without invalidating dealQuotationForPricingRequest, this second
    // call never fires and the assertions below would time out against the stale ISSUED DTO.
    await waitFor(() => expect(api.dealQuotations.findForPricingRequest.mock.calls.length)
      .toBeGreaterThan(callsBeforeOutcome));

    expect(await screen.findByRole('button', { name: 'เขียนใบเสนอราคาใหม่จากคำขอราคา' })).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'ลูกค้ายอมรับ' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'ลูกค้าขอแก้ไข' })).toBeNull();
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

// ── Import fills in a blank factory ────────────────────────────────────────────────────────────
// The bug: a line submitted with no factory (valid since 2026-08-11) blocked สร้างร่างอีเมล with a
// 422 that named the row's PRIMARY KEY — "รายการที่ 35" on a two-line request — and this page
// offered no way to fix it. UI-LEVEL ONLY, per this file's header: who may actually write the
// field is proved by PricingFactoryQuoteCostingIntegrationTest#setItemFactory_* against real
// Postgres, not by anything here.
function buildRequestWithBlankFactoryLine(overrides = {}) {
  const base = buildRequest();
  return buildRequest({
    ...overrides,
    items: [
      base.items[0],
      {
        ...base.items[0],
        id: 2,
        brand: null,
        model: null,
        productDescription: 'กระเบื้องนำเข้าพิเศษ',
        resolvedFactoryName: null,
        factory: null,
        catalogProductCode: null,
        catalogBasePrice: null,
      },
    ],
  });
}

describe('PricingRequestDetailPage blank-factory lines', () => {
  it('names the blocking line by ROW POSITION and product name before สร้างร่างอีเมล is ever pressed', async () => {
    const request = buildRequestWithBlankFactoryLine();
    renderDetailPage({ user: importUser, request });
    await waitForLoaded(request);

    const warning = screen.getByText(/ยังไม่ได้ระบุโรงงาน 1 รายการ/);
    // Position 2, not the row id — the number the reader can count on screen.
    expect(warning.textContent).toContain('รายการที่ 2 (กระเบื้องนำเข้าพิเศษ)');
    expect(screen.getByText('รายการที่ 2')).toBeTruthy();
  });

  // B6 (GLA-135): the field is now a picker over the real factory master list (SearchableCombobox),
  // not a free-text <input> — selecting an option sends the master row's factoryId, never a typed
  // name. Opening the combobox (focus) reveals every option because the query starts empty.
  it('lets Import PICK the factory on the blank line from the master list and sends its id to the per-item endpoint', async () => {
    const request = buildRequestWithBlankFactoryLine();
    renderDetailPage({ user: importUser, request });
    await waitForLoaded(request);

    fireEvent.focus(screen.getByLabelText('ระบุโรงงาน'));
    fireEvent.click(await screen.findByRole('option', { name: 'Cotto Industry' }));
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกโรงงาน' }));

    await waitFor(() => expect(api.pricingRequests.setItemFactory).toHaveBeenCalledWith(
      501, 2, { factoryId: 602 },
    ));
  });

  // QA BUG-20: pressing บันทึกโรงงาน with nothing picked used to be a silent no-op (the button was
  // disabled — no click, no hint why). Now it stays clickable and says what to do, and still does
  // NOT fire a NaN factoryId at the endpoint.
  it('warns instead of calling the endpoint when บันทึกโรงงาน is pressed with no factory selected', async () => {
    const request = buildRequestWithBlankFactoryLine();
    renderDetailPage({ user: importUser, request });
    await waitForLoaded(request);

    fireEvent.click(screen.getByRole('button', { name: 'บันทึกโรงงาน' }));

    expect(screen.getByText('เลือกโรงงานจากรายการก่อนกดบันทึก')).toBeTruthy();
    expect(api.pricingRequests.setItemFactory).not.toHaveBeenCalled();
  });

  // QA BUG-20: the double-submit lock. Both clicks land inside ONE act(), so React cannot re-render
  // between them — `saving` (isPending) is still false and the button still enabled for the second
  // click, exactly the fast-double-click window. Only ImportFactoryPicker's inFlight ref can stop the
  // repeat; separate fireEvent.click calls would each flush a render and pass without the ref.
  it('fires setItemFactory once when บันทึกโรงงาน is clicked twice before the first save settles', async () => {
    api.pricingRequests.setItemFactory.mockReturnValue(new Promise(() => {}));
    const request = buildRequestWithBlankFactoryLine();
    renderDetailPage({ user: importUser, request });
    await waitForLoaded(request);

    fireEvent.focus(screen.getByLabelText('ระบุโรงงาน'));
    fireEvent.click(await screen.findByRole('option', { name: 'Cotto Industry' }));
    const save = screen.getByRole('button', { name: 'บันทึกโรงงาน' });
    act(() => {
      save.click();
      save.click();
    });

    await waitFor(() => expect(api.pricingRequests.setItemFactory).toHaveBeenCalledTimes(1));
    expect(await screen.findByRole('button', { name: 'กำลังบันทึก…' })).toBeTruthy();
    expect(api.pricingRequests.setItemFactory).toHaveBeenCalledTimes(1);
  });

  // QA BUG-20: a line that already has a factory is a silent dead-end no more — it explains that
  // changing means a new revision (the backend refuses an in-place re-route), instead of leaving
  // Import to guess why the picker vanished.
  it('tells Import how to change a factory once one is set, without offering a re-route', async () => {
    const request = buildRequestWithBlankFactoryLine();
    renderDetailPage({ user: importUser, request });
    await waitForLoaded(request);

    expect(screen.getByText(/ระบบล็อกไว้กันใบขอราคาที่จัดกลุ่มตามโรงงานเพี้ยน/)).toBeTruthy();
  });

  it('offers no input on a line that already names a factory — the backend refuses a re-route', async () => {
    const request = buildRequestWithBlankFactoryLine();
    renderDetailPage({ user: importUser, request });
    await waitForLoaded(request);

    // Two lines, one blank: exactly one picker, and it belongs to the blank one.
    expect(screen.getAllByLabelText('ระบุโรงงาน')).toHaveLength(1);
    expect(screen.getByLabelText('ระบุโรงงาน').id).toBe('pcr-item-factory-2');
  });

  it('shows Sales the same blocking lines but no picker and no add-factory affordance — Import owns the field', async () => {
    const request = buildRequestWithBlankFactoryLine();
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);

    expect(screen.getByText(/ฝ่ายนำเข้าเป็นผู้ระบุโรงงานให้ในขั้นตอนนี้/)).toBeTruthy();
    expect(screen.queryByLabelText('ระบุโรงงาน')).toBeNull();
    // B6: the inline "add a brand-new factory" affordance is part of the same import-only
    // control — Sales must not see it either, not merely the picker's input.
    expect(screen.queryByRole('button', { name: /เพิ่มโรงงานใหม่/ })).toBeNull();
  });

  // B6 (GLA-135): Import may add a factory that genuinely doesn't exist yet, in-flow, rather than
  // being stuck typing a name the backend can never resolve. Reuses PriceImportPage's own
  // FactoryFormModal (name/country/currency/unit/email) — this proves the picker's own wiring:
  // create -> refetch the master list -> auto-select the new row for this line.
  it('lets Import add a brand-new factory in-flow and auto-selects it for the blank line', async () => {
    const request = buildRequestWithBlankFactoryLine();
    renderDetailPage({ user: importUser, request });
    await waitForLoaded(request);

    api.priceImport.createFactory.mockResolvedValue({
      factoryId: 603, name: 'New Factory Co', country: 'TH', countryOther: null,
      defaultCurrency: 'THB', email: null, unit: 'piece',
    });
    // The picker's initial fetch already ran (and resolved with setApiDefaults' 601/602 pair)
    // before this point — this ONE queued value is for the invalidate-triggered REFETCH that
    // follows onFactoryAdded, so the picker's options genuinely come from a fresh server read,
    // not a client-side splice of the create response into stale cache data.
    api.priceImport.factories.mockResolvedValueOnce([
      { factoryId: 601, name: 'SCG Ceramics', country: 'TH', countryOther: null, defaultCurrency: 'THB', email: null, unit: 'piece' },
      { factoryId: 602, name: 'Cotto Industry', country: 'TH', countryOther: null, defaultCurrency: 'THB', email: null, unit: 'piece' },
      { factoryId: 603, name: 'New Factory Co', country: 'TH', countryOther: null, defaultCurrency: 'THB', email: null, unit: 'piece' },
    ]);

    fireEvent.click(screen.getByRole('button', { name: /เพิ่มโรงงานใหม่/ }));
    const dialog = await screen.findByRole('dialog', { name: 'เพิ่มโรงงานใหม่' });
    fireEvent.change(within(dialog).getByLabelText(/ชื่อโรงงาน/), { target: { value: 'New Factory Co' } });
    fireEvent.change(within(dialog).getByLabelText(/ประเทศ/), { target: { value: 'TH' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));

    await waitFor(() => expect(api.priceImport.createFactory).toHaveBeenCalledWith(
      'New Factory Co', 'TH', null, 'EUR', '', 'piece',
    ));
    // The dialog closes and the line's picker now shows the newly-created, auto-selected factory.
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'เพิ่มโรงงานใหม่' })).toBeNull());
    expect(await screen.findByDisplayValue('New Factory Co')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'บันทึกโรงงาน' }));
    await waitFor(() => expect(api.pricingRequests.setItemFactory).toHaveBeenCalledWith(
      501, 2, { factoryId: 603 },
    ));
  });

  it('offers nothing once the request has left Import\'s hands', async () => {
    const request = buildRequestWithBlankFactoryLine({ summary: { status: 'READY_FOR_CEO_REVIEW' } });
    renderDetailPage({ user: importUser, request });
    await waitForLoaded(request);

    expect(screen.queryByLabelText('ระบุโรงงาน')).toBeNull();
  });

  it('says nothing at all when every line already names a factory', async () => {
    renderDetailPage({ user: importUser });
    await waitForLoaded();

    expect(screen.queryByText(/ยังไม่ได้ระบุโรงงาน/)).toBeNull();
    expect(screen.queryByLabelText('ระบุโรงงาน')).toBeNull();
  });
});

describe('PricingRequestDetailPage deal link', () => {
  // Import cannot open /tickets/:id any more (the whole-deal GET 403s it) — its deal link must
  // land on its OWN per-deal page instead. Sales/CEO keep the whole-deal page.
  it('links import to /import/deals/:id, not the whole-deal page', async () => {
    renderDetailPage({ user: importUser });
    await waitForLoaded();
    const link = screen.getByRole('link', { name: 'PR-2026-0701' });
    expect(link.getAttribute('href')).toBe('/import/deals/701');
  });

  it('keeps the whole-deal link for sales', async () => {
    renderDetailPage({ user: salesOwner });
    await waitForLoaded();
    expect(screen.getByRole('link', { name: 'PR-2026-0701' }).getAttribute('href')).toBe('/tickets/701');
  });
});

// ══════════════════════════════════════════════════════════════════════════════════════════════
// CR-1 (GLA-167) — factory contact flow, locked terms, lead-time change (rulings R1–R10).
// UI-LEVEL ONLY, per this file's header: every role assertion proves conditional rendering, not
// server enforcement (FactoryQuoteService / LeadTimeChangeService integration tests do that).
// ══════════════════════════════════════════════════════════════════════════════════════════════
const bangkokDay = (offsetDays = 0) => new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Bangkok' })
  .format(new Date(Date.now() + offsetDays * 24 * 3600 * 1000));

const salesOtherRep = { id: 9, employeeId: 9, name: 'พนักงานขายคนอื่น', role: 'sales' };

function crItem(over = {}) {
  return {
    ...buildRequest().items[0],
    requestedCurrency: 'EUR',
    requestedPriceUnitBasis: 'PER_SQM',
    leadTimeMinDays: 75,
    leadTimeMaxDays: 90,
    ...over,
  };
}

function crQuote(over = {}) {
  const base = buildFactoryQuote();
  return buildFactoryQuote({
    defaultCurrency: 'EUR',
    items: [{ ...base.items[0], currency: 'EUR', unitBasis: 'PER_SQM', quotedUnit: 'ตร.ม.' }],
    ...over,
  });
}

function contactedQuote(over = {}) {
  return crQuote({
    status: 'REQUESTED',
    contactedOn: '2026-10-01',
    contactedNote: 'โทรคุณ Marco',
    contactedBy: 3,
    contactedAt: '2026-10-01T03:00:00Z',
    ...over,
  });
}

function crRequest(over = {}) {
  return {
    ...buildRequest({ items: [crItem()], ...over }),
    events: [{
      id: 1, actorId: 3, actorName: 'ฝ่ายนำเข้า', eventKind: 'FACTORY_CONTACTED',
      message: 'Factory contacted: SCG Ceramics on 2026-10-01', createdAt: '2026-10-01T03:00:00Z',
    }],
  };
}

function ltChange(over = {}) {
  return {
    id: 7001,
    factoryQuoteId: 91,
    pricingRequestId: 501,
    status: 'PENDING',
    reason: 'โรงงานเลื่อนกำหนดผลิต',
    requestedBy: 3,
    requestedAt: '2026-10-01T03:00:00Z',
    decidedBy: null,
    decidedAt: null,
    decisionReason: null,
    version: 3,
    lines: [{ pricingRequestItemId: 1, oldMinDays: 75, oldMaxDays: 90, newMinDays: 120, newMaxDays: 150 }],
    ...over,
  };
}

describe('CR-1 factory card — status, terms, and the locked price grid (F1)', () => {
  it('a not-yet-contacted factory shows the chip ยังไม่ติดต่อ with สร้างเมล + a primary ติดต่อโรงงานแล้ว, and none of the retired labels', async () => {
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [crQuote()] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    expect(screen.getByTestId('pcr-factory-status-91').textContent).toContain('ยังไม่ติดต่อ');
    expect(screen.getByRole('button', { name: 'สร้างเมล' })).not.toBeNull();
    expect(screen.getByRole('button', { name: 'ติดต่อโรงงานแล้ว' })).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'ร่างอีเมล' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'ดูอีเมล' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'ส่งแล้ว' })).toBeNull();
  });

  it('shows the locked terms strip "EUR · ต่อ ตร.ม. · ตามคำขอของฝ่ายขาย" and REMOVES the currency/unit selects for lines that carry terms (R1)', async () => {
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [contactedQuote()] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    const strip = screen.getByTestId('pcr-factory-terms-91');
    expect(strip.textContent).toContain('EUR');
    expect(strip.textContent).toContain('ต่อ ตร.ม.');
    expect(strip.textContent).toContain('ตามคำขอของฝ่ายขาย');
    // regex, not the bare string: the label carries an InfoTip, so an exact-string query can never match
    expect(screen.queryByRole('combobox', { name: /^สกุลเงิน/ })).toBeNull();
    expect(screen.queryByRole('combobox', { name: /^หน่วยราคา/ })).toBeNull();
  });

  it('a legacy line with no requested terms keeps today\'s currency/unit selects and shows no lock strip', async () => {
    renderDetailPage({ user: importUser, request: buildRequest(), factoryQuotes: [buildFactoryQuote({ status: 'REQUESTED' })] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    expect(screen.getByRole('combobox', { name: /^สกุลเงิน/ })).not.toBeNull();
    expect(screen.getByRole('combobox', { name: /^หน่วยราคา/ })).not.toBeNull();
    expect(screen.queryByTestId('pcr-factory-terms-91')).toBeNull();
  });

  it('a not-yet-contacted factory disables every price input and says why', async () => {
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [crQuote()] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    expect(screen.getByLabelText(/^ราคาที่เสนอ/).disabled).toBe(true);
    expect(screen.getByTestId('pcr-await-contact-91').textContent).toContain('กด ติดต่อโรงงานแล้ว ก่อนกรอกราคา');
    expect(screen.queryByRole('button', { name: 'ยืนยันราคาเสนอ' })).toBeNull();
  });

  it('a contacted factory shows ติดต่อแล้ว <date> · <name>, the note, unlocked prices — and NO ติดต่อโรงงานแล้ว button (no undo, R7)', async () => {
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [contactedQuote()] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    const chip = screen.getByTestId('pcr-factory-status-91').textContent;
    expect(chip).toContain('ติดต่อแล้ว');
    expect(chip).toContain('1 ต.ค.');
    expect(chip).toContain('ฝ่ายนำเข้า');
    expect(screen.getByText(/โทรคุณ Marco/)).not.toBeNull();
    expect(screen.getByLabelText(/^ราคาที่เสนอ/).disabled).toBe(false);
    expect(screen.queryByRole('button', { name: 'ติดต่อโรงงานแล้ว' })).toBeNull();
    expect(screen.queryByRole('button', { name: /ยกเลิกการติดต่อ|แก้ไขการติดต่อ/ })).toBeNull();
  });

  it('a READY_FOR_COSTING factory reads ยืนยันราคาแล้ว', async () => {
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [contactedQuote({ status: 'READY_FOR_COSTING' })] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');
    expect(screen.getByTestId('pcr-factory-status-91').textContent).toContain('ยืนยันราคาแล้ว');
  });

  it('the price payload carries the sales terms (EUR / PER_SQM) — import cannot change them', async () => {
    const quote = contactedQuote();
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [quote] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    fireEvent.change(screen.getByLabelText(/^ราคาที่เสนอ/), { target: { value: '12.5' } });
    fireEvent.change(screen.getByLabelText(/^ตร.ม.\/หน่วย/), { target: { value: '0.36' } });
    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));

    await waitFor(() => expect(api.pricingRequests.receiveFactoryQuote).toHaveBeenCalledWith(
      quote.id,
      expect.objectContaining({
        defaultCurrency: 'EUR',
        items: [expect.objectContaining({ currency: 'EUR', unitBasis: 'PER_SQM', rawUnitPrice: 12.5 })],
      }),
    ));
  });

  it('CEO sees the card and the locked terms, but no price inputs, no confirm, no lead-time change', async () => {
    renderDetailPage({ user: ceoUser, request: crRequest(), factoryQuotes: [contactedQuote()] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    expect(screen.getByTestId('pcr-factory-terms-91')).not.toBeNull();
    expect(screen.queryByLabelText(/^ราคาที่เสนอ/)).toBeNull();
    expect(screen.queryByRole('button', { name: 'ยืนยันราคาเสนอ' })).toBeNull();
    expect(screen.queryByTestId('pcr-lt-open-91')).toBeNull();
  });
});

describe('CR-1 ติดต่อโรงงานแล้ว dialog (R3, R5, R7)', () => {
  it('opens a small dialog with the date defaulting to today (Bangkok), capped at today, and an optional note', async () => {
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [crQuote()] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    fireEvent.click(screen.getByRole('button', { name: 'ติดต่อโรงงานแล้ว' }));
    const dialog = await screen.findByRole('dialog', { name: 'ติดต่อโรงงานแล้ว' });
    const date = within(dialog).getByLabelText('วันที่ติดต่อ');
    expect(date.value).toBe(bangkokDay());
    expect(date.max).toBe(bangkokDay());
    expect(within(dialog).getByLabelText(/หมายเหตุ/)).not.toBeNull();
  });

  it('ยืนยัน calls markFactoryQuoteContacted(id, {contactedOn, note}) and refreshes the factory quotes + request (F)', async () => {
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [crQuote()] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');
    const detailCalls = api.pricingRequests.get.mock.calls.length;
    const quoteCalls = api.pricingRequests.listFactoryQuotes.mock.calls.length;

    fireEvent.click(screen.getByRole('button', { name: 'ติดต่อโรงงานแล้ว' }));
    const dialog = await screen.findByRole('dialog', { name: 'ติดต่อโรงงานแล้ว' });
    fireEvent.change(within(dialog).getByLabelText(/หมายเหตุ/), { target: { value: 'โทรคุณ Marco' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'ยืนยัน' }));

    await waitFor(() => expect(api.pricingRequests.markFactoryQuoteContacted).toHaveBeenCalledWith(
      91, { contactedOn: bangkokDay(), note: 'โทรคุณ Marco' },
    ));
    await waitFor(() => expect(api.pricingRequests.listFactoryQuotes.mock.calls.length).toBeGreaterThan(quoteCalls));
    expect(api.pricingRequests.get.mock.calls.length).toBeGreaterThan(detailCalls);
  });

  it('omits the note entirely when it is left blank', async () => {
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [crQuote()] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    fireEvent.click(screen.getByRole('button', { name: 'ติดต่อโรงงานแล้ว' }));
    const dialog = await screen.findByRole('dialog', { name: 'ติดต่อโรงงานแล้ว' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'ยืนยัน' }));

    await waitFor(() => expect(api.pricingRequests.markFactoryQuoteContacted).toHaveBeenCalledWith(
      91, { contactedOn: bangkokDay() },
    ));
  });

  it('refuses a future date: ยืนยัน is disabled and nothing is sent', async () => {
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [crQuote()] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    fireEvent.click(screen.getByRole('button', { name: 'ติดต่อโรงงานแล้ว' }));
    const dialog = await screen.findByRole('dialog', { name: 'ติดต่อโรงงานแล้ว' });
    fireEvent.change(within(dialog).getByLabelText('วันที่ติดต่อ'), { target: { value: bangkokDay(1) } });
    const confirm = within(dialog).getByRole('button', { name: 'ยืนยัน' });
    expect(confirm.disabled).toBe(true);
    fireEvent.click(confirm);
    expect(api.pricingRequests.markFactoryQuoteContacted).not.toHaveBeenCalled();
  });

  it('cancelling the dialog changes nothing', async () => {
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [crQuote()] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    fireEvent.click(screen.getByRole('button', { name: 'ติดต่อโรงงานแล้ว' }));
    const dialog = await screen.findByRole('dialog', { name: 'ติดต่อโรงงานแล้ว' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'ยกเลิก' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'ติดต่อโรงงานแล้ว' })).toBeNull());
    expect(api.pricingRequests.markFactoryQuoteContacted).not.toHaveBeenCalled();
  });

  it('surfaces a 409 (already contacted) as an error toast', async () => {
    api.pricingRequests.markFactoryQuoteContacted.mockRejectedValue(new Error('ติดต่อโรงงานแล้วหรือไม่อยู่ในสถานะร่าง'));
    const { showToast } = renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [crQuote()] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    fireEvent.click(screen.getByRole('button', { name: 'ติดต่อโรงงานแล้ว' }));
    const dialog = await screen.findByRole('dialog', { name: 'ติดต่อโรงงานแล้ว' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'ยืนยัน' }));
    await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', 'ติดต่อโรงงานแล้วหรือไม่อยู่ในสถานะร่าง'));
  });

  it('the CEO can use สร้างเมล and ติดต่อโรงงานแล้ว too (R5, B-R1)', async () => {
    renderDetailPage({ user: ceoUser, request: crRequest(), factoryQuotes: [crQuote()] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    expect(screen.getByRole('button', { name: 'สร้างเมล' })).not.toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'ติดต่อโรงงานแล้ว' }));
    const dialog = await screen.findByRole('dialog', { name: 'ติดต่อโรงงานแล้ว' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'ยืนยัน' }));
    await waitFor(() => expect(api.pricingRequests.markFactoryQuoteContacted).toHaveBeenCalledWith(
      91, { contactedOn: bangkokDay() },
    ));
  });
});

describe('CR-1 สร้างเมล modal (R3, R8)', () => {
  it('has ถึง pre-filled from the factory email, หัวข้อ, เนื้อหา — and ONLY บันทึกร่าง / คัดลอกเมล (+ ปิด); no ส่งแล้ว, no hand-off steps', async () => {
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [crQuote()] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    fireEvent.click(screen.getByRole('button', { name: 'สร้างเมล' }));
    const dialog = await screen.findByRole('dialog', { name: 'สร้างเมล' });
    expect(within(dialog).getByLabelText('ถึง').value).toBe('sales@scg-factory.example');
    expect(within(dialog).getByLabelText('หัวข้อ').value).toBe('ขอราคา SCG A1');
    expect(within(dialog).getByLabelText('เนื้อหา').value).toBe('เรียน โรงงาน...');
    const buttons = within(dialog).getAllByRole('button').map((b) => b.textContent.trim());
    expect(buttons.filter((t) => t && t !== 'ปิด' && !/ปิด|close/i.test(t))).toEqual(['บันทึกร่าง', 'คัดลอกเมล']);
    expect(within(dialog).queryByText('คัดลอกข้อความอีเมลด้านล่าง')).toBeNull();
  });

  it('คัดลอกเมล copies the mail and changes NO status (no contact call, no save)', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(globalThis.navigator, 'clipboard', { value: { writeText }, configurable: true });
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [crQuote()] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    fireEvent.click(screen.getByRole('button', { name: 'สร้างเมล' }));
    const dialog = await screen.findByRole('dialog', { name: 'สร้างเมล' });
    fireEvent.click(within(dialog).getByRole('button', { name: /คัดลอกเมล/ }));

    await waitFor(() => expect(writeText).toHaveBeenCalledTimes(1));
    expect(writeText.mock.calls[0][0]).toContain('Subject: ขอราคา SCG A1');
    expect(api.pricingRequests.markFactoryQuoteContacted).not.toHaveBeenCalled();
    expect(api.pricingRequests.updateFactoryQuote).not.toHaveBeenCalled();
  });

  it('closing the modal changes no status', async () => {
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [crQuote()] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    fireEvent.click(screen.getByRole('button', { name: 'สร้างเมล' }));
    const dialog = await screen.findByRole('dialog', { name: 'สร้างเมล' });
    // header X and the footer ปิด share the accessible name; either closes
    fireEvent.click(within(dialog).getAllByRole('button', { name: 'ปิด' }).at(-1));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'สร้างเมล' })).toBeNull());
    expect(api.pricingRequests.markFactoryQuoteContacted).not.toHaveBeenCalled();
    expect(api.pricingRequests.updateFactoryQuote).not.toHaveBeenCalled();
    expect(screen.getByTestId('pcr-factory-status-91').textContent).toContain('ยังไม่ติดต่อ');
  });

  it('lists the request attachments that are ticked to go with the mail', async () => {
    renderDetailPage({
      user: importUser,
      request: crRequest(),
      factoryQuotes: [crQuote()],
      attachments: [
        { id: 31, fileName: 'spec-sheet.pdf', includeInFactoryEmail: true },
        { id: 32, fileName: 'internal-note.pdf', includeInFactoryEmail: false },
      ],
    });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    fireEvent.click(screen.getByRole('button', { name: 'สร้างเมล' }));
    const dialog = await screen.findByRole('dialog', { name: 'สร้างเมล' });
    expect(within(dialog).getByText('spec-sheet.pdf')).not.toBeNull();
    expect(within(dialog).queryByText('internal-note.pdf')).toBeNull();
  });
});

describe('CR-1 lead-time change — import raises it (R2, R10 option C)', () => {
  function twoLineRequest() {
    return crRequest({ items: [crItem(), crItem({ id: 2, model: 'A2' }), crItem({ id: 3, model: 'S1', stockSource: 'IN_THAILAND' })] });
  }
  function twoLineQuote(over = {}) {
    const it0 = crQuote().items[0];
    return contactedQuote({
      items: [
        it0,
        { ...it0, id: 912, pricingRequestItemId: 2 },
        { ...it0, id: 913, pricingRequestItemId: 3 },
      ],
      ...over,
    });
  }

  it('shows each line\'s ระยะเวลานำเข้า and offers ขอเปลี่ยนระยะเวลานำเข้า to import only', async () => {
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [contactedQuote()] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');
    expect(screen.getByTestId('pcr-lt-value-91-1').textContent).toContain('75–90 วัน');
    expect(screen.getByTestId('pcr-lt-open-91')).not.toBeNull();
  });

  it('opens a panel with new min/max, a required reason, and every non-stock line pre-ticked', async () => {
    renderDetailPage({ user: importUser, request: twoLineRequest(), factoryQuotes: [twoLineQuote()] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    fireEvent.click(screen.getByTestId('pcr-lt-open-91'));
    const dialog = await screen.findByRole('dialog', { name: 'ขอเปลี่ยนระยะเวลานำเข้า' });
    expect(within(dialog).getByLabelText('ต่ำสุด (วัน)')).not.toBeNull();
    expect(within(dialog).getByLabelText('สูงสุด (วัน)')).not.toBeNull();
    expect(within(dialog).getByLabelText('เหตุผล')).not.toBeNull();
    expect(within(dialog).getByLabelText('เลือกรายการ #1').checked).toBe(true);
    expect(within(dialog).getByLabelText('เลือกรายการ #2').checked).toBe(true);
    // the stock line is never offered (backend refuses it)
    expect(within(dialog).queryByLabelText('เลือกรายการ #3')).toBeNull();
    // submit stays disabled until a reason and a valid range exist
    expect(within(dialog).getByRole('button', { name: 'ส่งคำขอ' }).disabled).toBe(true);
  });

  it('submits every ticked line with the new value, the reason, and refreshes the lead-time changes', async () => {
    renderDetailPage({ user: importUser, request: twoLineRequest(), factoryQuotes: [twoLineQuote()] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');
    const before = api.leadTimeChanges.listForPricingRequest.mock.calls.length;

    fireEvent.click(screen.getByTestId('pcr-lt-open-91'));
    const dialog = await screen.findByRole('dialog', { name: 'ขอเปลี่ยนระยะเวลานำเข้า' });
    fireEvent.change(within(dialog).getByLabelText('ต่ำสุด (วัน)'), { target: { value: '120' } });
    fireEvent.change(within(dialog).getByLabelText('สูงสุด (วัน)'), { target: { value: '150' } });
    fireEvent.change(within(dialog).getByLabelText('เหตุผล'), { target: { value: 'โรงงานเลื่อนผลิต' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'ส่งคำขอ' }));

    await waitFor(() => expect(api.leadTimeChanges.create).toHaveBeenCalledWith(91, {
      reason: 'โรงงานเลื่อนผลิต',
      lines: [
        { pricingRequestItemId: 1, newMinDays: 120, newMaxDays: 150 },
        { pricingRequestItemId: 2, newMinDays: 120, newMaxDays: 150 },
      ],
    }));
    await waitFor(() => expect(api.leadTimeChanges.listForPricingRequest.mock.calls.length).toBeGreaterThan(before));
  });

  it('an unticked line is left out; a per-line override keeps its own value', async () => {
    renderDetailPage({ user: importUser, request: twoLineRequest(), factoryQuotes: [twoLineQuote()] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    fireEvent.click(screen.getByTestId('pcr-lt-open-91'));
    const dialog = await screen.findByRole('dialog', { name: 'ขอเปลี่ยนระยะเวลานำเข้า' });
    fireEvent.change(within(dialog).getByLabelText('ต่ำสุด (วัน)'), { target: { value: '120' } });
    fireEvent.change(within(dialog).getByLabelText('สูงสุด (วัน)'), { target: { value: '150' } });
    fireEvent.change(within(dialog).getByLabelText('เหตุผล'), { target: { value: 'เหตุผล' } });
    // line 2: own value
    fireEvent.change(within(dialog).getByLabelText('ต่ำสุด รายการ #2'), { target: { value: '100' } });
    fireEvent.change(within(dialog).getByLabelText('สูงสุด รายการ #2'), { target: { value: '110' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'ส่งคำขอ' }));
    await waitFor(() => expect(api.leadTimeChanges.create).toHaveBeenCalledWith(91, expect.objectContaining({
      lines: [
        { pricingRequestItemId: 1, newMinDays: 120, newMaxDays: 150 },
        { pricingRequestItemId: 2, newMinDays: 100, newMaxDays: 110 },
      ],
    })));
  });

  it('unticking a line removes it from the request', async () => {
    renderDetailPage({ user: importUser, request: twoLineRequest(), factoryQuotes: [twoLineQuote()] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    fireEvent.click(screen.getByTestId('pcr-lt-open-91'));
    const dialog = await screen.findByRole('dialog', { name: 'ขอเปลี่ยนระยะเวลานำเข้า' });
    fireEvent.change(within(dialog).getByLabelText('ต่ำสุด (วัน)'), { target: { value: '120' } });
    fireEvent.change(within(dialog).getByLabelText('สูงสุด (วัน)'), { target: { value: '150' } });
    fireEvent.change(within(dialog).getByLabelText('เหตุผล'), { target: { value: 'เหตุผล' } });
    fireEvent.click(within(dialog).getByLabelText('เลือกรายการ #2'));
    fireEvent.click(within(dialog).getByRole('button', { name: 'ส่งคำขอ' }));
    await waitFor(() => expect(api.leadTimeChanges.create).toHaveBeenCalledWith(91, expect.objectContaining({
      lines: [{ pricingRequestItemId: 1, newMinDays: 120, newMaxDays: 150 }],
    })));
  });

  it('refuses min > max and an empty reason client-side (nothing sent)', async () => {
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [contactedQuote()] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    fireEvent.click(screen.getByTestId('pcr-lt-open-91'));
    const dialog = await screen.findByRole('dialog', { name: 'ขอเปลี่ยนระยะเวลานำเข้า' });
    fireEvent.change(within(dialog).getByLabelText('ต่ำสุด (วัน)'), { target: { value: '150' } });
    fireEvent.change(within(dialog).getByLabelText('สูงสุด (วัน)'), { target: { value: '120' } });
    fireEvent.change(within(dialog).getByLabelText('เหตุผล'), { target: { value: 'เหตุผล' } });
    expect(within(dialog).getByRole('button', { name: 'ส่งคำขอ' }).disabled).toBe(true);
    expect(api.leadTimeChanges.create).not.toHaveBeenCalled();
  });

  it('with a PENDING change: lines read "old → new · รอฝ่ายขายอนุมัติ", import can แก้ไข / ถอนคำขอ, and cannot raise a second one', async () => {
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [contactedQuote()], leadTimeChanges: [ltChange()] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    const cell = await screen.findByTestId('pcr-lt-value-91-1');
    expect(cell.textContent).toContain('75–90 → 120–150 วัน');
    expect(cell.textContent).toContain('รอฝ่ายขายอนุมัติ');
    const strip = screen.getByTestId('pcr-lt-pending-91');
    expect(within(strip).getByRole('button', { name: 'แก้ไข' })).not.toBeNull();
    expect(within(strip).getByRole('button', { name: 'ถอนคำขอ' })).not.toBeNull();
    expect(screen.queryByTestId('pcr-lt-open-91')).toBeNull();
  });

  it('ถอนคำขอ withdraws the pending request', async () => {
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [contactedQuote()], leadTimeChanges: [ltChange()] });
    await waitForLoaded();
    const strip = await screen.findByTestId('pcr-lt-pending-91');
    fireEvent.click(within(strip).getByRole('button', { name: 'ถอนคำขอ' }));
    await waitFor(() => expect(api.leadTimeChanges.withdraw).toHaveBeenCalledWith(7001));
  });

  it('แก้ไข opens the panel prefilled and saves with update(id, body)', async () => {
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [contactedQuote()], leadTimeChanges: [ltChange()] });
    await waitForLoaded();
    const strip = await screen.findByTestId('pcr-lt-pending-91');
    fireEvent.click(within(strip).getByRole('button', { name: 'แก้ไข' }));
    const dialog = await screen.findByRole('dialog', { name: 'ขอเปลี่ยนระยะเวลานำเข้า' });
    expect(within(dialog).getByLabelText('เหตุผล').value).toBe('โรงงานเลื่อนกำหนดผลิต');
    expect(within(dialog).getByLabelText('ต่ำสุด รายการ #1').value).toBe('120');
    fireEvent.change(within(dialog).getByLabelText('สูงสุด รายการ #1'), { target: { value: '160' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึกการแก้ไข' }));
    await waitFor(() => expect(api.leadTimeChanges.update).toHaveBeenCalledWith(7001, {
      reason: 'โรงงานเลื่อนกำหนดผลิต',
      lines: [{ pricingRequestItemId: 1, newMinDays: 120, newMaxDays: 160 }],
    }));
  });

  it('shows approved / rejected history compactly on the card, with the rejection reason', async () => {
    const history = [
      ltChange({ id: 6001, status: 'APPROVED', decidedBy: 1, decidedAt: '2026-09-20T03:00:00Z' }),
      ltChange({ id: 6002, status: 'REJECTED', decisionReason: 'ลูกค้ารอไม่ได้', decidedBy: 1, decidedAt: '2026-09-25T03:00:00Z',
        lines: [{ pricingRequestItemId: 1, oldMinDays: 120, oldMaxDays: 150, newMinDays: 200, newMaxDays: 210 }] }),
    ];
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [contactedQuote()], leadTimeChanges: history });
    await waitForLoaded();
    const box = await screen.findByTestId('pcr-lt-history-91');
    expect(box.textContent).toContain('อนุมัติแล้ว');
    expect(box.textContent).toContain('75–90 → 120–150');
    expect(box.textContent).toContain('ไม่อนุมัติ');
    expect(box.textContent).toContain('ลูกค้ารอไม่ได้');
    // history is not a pending request: no actions, the raise button is available again
    expect(screen.queryByTestId('pcr-lt-pending-91')).toBeNull();
    expect(screen.getByTestId('pcr-lt-open-91')).not.toBeNull();
  });

  it('the CEO sees the pending request as a read-only amber badge — no อนุมัติ / ถอนคำขอ / แก้ไข (B-R3)', async () => {
    renderDetailPage({ user: ceoUser, request: crRequest(), factoryQuotes: [contactedQuote()], leadTimeChanges: [ltChange()] });
    await waitForLoaded();
    const strip = await screen.findByTestId('pcr-lt-pending-91');
    expect(strip.textContent).toContain('รอฝ่ายขายอนุมัติ');
    expect(within(strip).queryByRole('button')).toBeNull();
    expect(screen.queryByRole('button', { name: 'อนุมัติ' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'ไม่อนุมัติ' })).toBeNull();
  });
});

describe('CR-1 lead-time change — owning rep / sales manager decide it as a whole (R2, R10, B-R3)', () => {
  it('the owning rep sees ONE banner listing the request (factory, reason, old → new) with อนุมัติ / ไม่อนุมัติ', async () => {
    renderDetailPage({ user: salesOwner, request: crRequest(), leadTimeChanges: [ltChange()] });
    await waitForLoaded();
    const banner = await screen.findByTestId('pcr-lt-banner');
    expect(banner.textContent).toContain('SCG Ceramics');
    expect(banner.textContent).toContain('โรงงานเลื่อนกำหนดผลิต');
    expect(banner.textContent).toContain('75–90 → 120–150');
    expect(within(banner).getByRole('button', { name: 'อนุมัติ' })).not.toBeNull();
    expect(within(banner).getByRole('button', { name: 'ไม่อนุมัติ' })).not.toBeNull();
  });

  it('อนุมัติ sends the version the approver loaded (expectedVersion) and refreshes', async () => {
    renderDetailPage({ user: salesOwner, request: crRequest(), leadTimeChanges: [ltChange({ version: 3 })] });
    await waitForLoaded();
    const banner = await screen.findByTestId('pcr-lt-banner');
    const detailCalls = api.pricingRequests.get.mock.calls.length;
    fireEvent.click(within(banner).getByRole('button', { name: 'อนุมัติ' }));
    await waitFor(() => expect(api.leadTimeChanges.approve).toHaveBeenCalledWith(7001, { expectedVersion: 3 }));
    // approval rewrites the pricing-request lines, so the request itself must be re-read too
    await waitFor(() => expect(api.pricingRequests.get.mock.calls.length).toBeGreaterThan(detailCalls));
  });

  it('ไม่อนุมัติ needs a reason before it can be confirmed, then sends reason + expectedVersion', async () => {
    renderDetailPage({ user: salesManager, request: crRequest(), leadTimeChanges: [ltChange({ version: 3 })] });
    await waitForLoaded();
    const banner = await screen.findByTestId('pcr-lt-banner');
    fireEvent.click(within(banner).getByRole('button', { name: 'ไม่อนุมัติ' }));
    const confirm = within(banner).getByRole('button', { name: 'ยืนยันไม่อนุมัติ' });
    expect(confirm.disabled).toBe(true);
    fireEvent.change(within(banner).getByLabelText('เหตุผลที่ไม่อนุมัติ'), { target: { value: 'ลูกค้ารอไม่ได้' } });
    expect(confirm.disabled).toBe(false);
    fireEvent.click(confirm);
    await waitFor(() => expect(api.leadTimeChanges.reject).toHaveBeenCalledWith(7001, { reason: 'ลูกค้ารอไม่ได้', expectedVersion: 3 }));
  });

  it('on a 409 (import edited it in between) shows "คำขอถูกแก้ไขแล้ว กรุณาตรวจสอบอีกครั้ง" and refetches', async () => {
    const err = Object.assign(new Error('Conflict'), { status: 409 });
    api.leadTimeChanges.approve.mockRejectedValue(err);
    const { showToast } = renderDetailPage({ user: salesOwner, request: crRequest(), leadTimeChanges: [ltChange()] });
    await waitForLoaded();
    const banner = await screen.findByTestId('pcr-lt-banner');
    const before = api.leadTimeChanges.listForPricingRequest.mock.calls.length;
    fireEvent.click(within(banner).getByRole('button', { name: 'อนุมัติ' }));
    await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', 'คำขอถูกแก้ไขแล้ว กรุณาตรวจสอบอีกครั้ง'));
    await waitFor(() => expect(api.leadTimeChanges.listForPricingRequest.mock.calls.length).toBeGreaterThan(before));
  });

  it('shows nothing to a sales rep who does not own the deal, nor to the CEO or import (no decide controls)', async () => {
    for (const user of [salesOtherRep, ceoUser, importUser]) {
      const { unmount } = renderDetailPage({ user, request: crRequest(), factoryQuotes: [contactedQuote()], leadTimeChanges: [ltChange()] });
      await waitForLoaded();
      await screen.findByText('PCR-2026-0001');
      expect(screen.queryByTestId('pcr-lt-banner')).toBeNull();
      expect(screen.queryByRole('button', { name: 'อนุมัติ' })).toBeNull();
      unmount();
    }
  });

  it('does not show a banner when nothing is pending (history only)', async () => {
    renderDetailPage({ user: salesOwner, request: crRequest(), leadTimeChanges: [ltChange({ status: 'APPROVED' })] });
    await waitForLoaded();
    await screen.findByText('PCR-2026-0001');
    expect(screen.queryByTestId('pcr-lt-banner')).toBeNull();
  });
});

describe('CR-1 factory card — the factory\'s IR row, read-only for import (R9)', () => {
  const ir = (over = {}) => ({ id: 11, ticketId: 701, factoryId: null, factoryName: 'SCG Ceramics', version: 1, status: 'ISSUED', docNumber: 'IR26001', ...over });

  beforeEach(() => {
    globalThis.URL.createObjectURL = vi.fn(() => 'blob:ir');
    globalThis.URL.revokeObjectURL = vi.fn();
  });

  it('shows "<number> · <status> · PDF" under the matching factory card, and downloads it', async () => {
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [contactedQuote()], importRequests: [ir()] });
    await waitForLoaded();
    const row = await screen.findByTestId('pcr-ir-91');
    expect(row.textContent).toContain('IR26001');
    fireEvent.click(within(row).getByRole('button', { name: /PDF/ }));
    await waitFor(() => expect(api.storedImportRequests.download).toHaveBeenCalledWith(11, undefined));
    expect(api.storedImportRequests.listForTicket).toHaveBeenCalledWith(701);
  });

  it('a DRAFT IR reads ฉบับร่าง; import gets no create / issue / revise control on this page', async () => {
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [contactedQuote()], importRequests: [ir({ status: 'DRAFT', docNumber: null })] });
    await waitForLoaded();
    const row = await screen.findByTestId('pcr-ir-91');
    expect(row.textContent).toContain('ฉบับร่าง');
    expect(screen.queryByRole('button', { name: /สร้างใบ IR|ออกเลข|ออกฉบับแก้ไข/ })).toBeNull();
  });

  it('matches the factory by id when both carry one, and ignores SUPERSEDED rows and other factories', async () => {
    const quote = contactedQuote({ factoryId: 601 });
    renderDetailPage({
      user: importUser,
      request: crRequest(),
      factoryQuotes: [quote],
      importRequests: [
        ir({ id: 12, factoryId: 999, factoryName: 'Someone Else' }),
        ir({ id: 13, factoryId: 601, status: 'SUPERSEDED', docNumber: 'IR25999' }),
        ir({ id: 14, factoryId: 601, docNumber: 'IR26014' }),
      ],
    });
    await waitForLoaded();
    const row = await screen.findByTestId('pcr-ir-91');
    expect(row.textContent).toContain('IR26014');
    expect(row.textContent).not.toContain('IR25999');
    expect(row.textContent).not.toContain('Someone Else');
  });

  it('shows no IR row when the factory has none yet', async () => {
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [contactedQuote()] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');
    expect(screen.queryByTestId('pcr-ir-91')).toBeNull();
  });
});
