import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PricingRequestDetailPage } from './PricingRequestDetailPage.jsx';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { formatThaiDate, pricingDecisionStatusLabel, pricingRequestStatusLabel } from '../../utils/format.js';

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
      submit: vi.fn(),
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
    // Step 6: the deal's deposit notices, so the create action is not offered once one is ISSUED.
    depositNotices: {
      listByTicket: vi.fn(),
    },
    // Lead-time change requests.
    leadTimeChanges: {
      listForPricingRequest: vi.fn(),
      create: vi.fn(),
      update: vi.fn(),
      withdraw: vi.fn(),
      approve: vi.fn(),
      reject: vi.fn(),
    },
    // The factory card lists the deal's stored IR for that factory, read-only.
    storedImportRequests: {
      listForTicket: vi.fn(),
      download: vi.fn(),
    },
    catalog: {
      prices: vi.fn(),
    },
    // The import factory picker's master list + country roster + in-flow add-factory form
    // (ImportFactoryPicker, reusing catalog PriceImportPage's FactoryFormModal).
    priceImport: {
      factories: vi.fn(),
      countries: vi.fn(),
      createFactory: vi.fn(),
    },
    meta: {
      unitBases: vi.fn(),
    },
    // PricingRequestCreateModal's header-terms section (opened here in mode="revision") fetches the
    // same eligible-display-name list the direct-deal quotation editor uses.
    dealQuotations: {
      displayNameOptions: vi.fn().mockResolvedValue({ items: [] }),
      // Reset to "none yet" in the shared beforeEach below (like getPricingDecisionSalesView), so
      // tests see the legacy-only panel state and no override leaks from an earlier test.
      findForPricingRequest: vi.fn(),
      listForTicket: vi.fn(),
      createFromPricingRequest: vi.fn(),
      // The new engine's own outcome-recording endpoint, like
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
        // color/texture/size/thicknessMm/sqmPerPiece/piecesPerBox/a quantity are required on every
        // item PricingRequestCreateModal can save (see its validateItemFields, which mirrors
        // PricingRequestService#requireItemFieldsComplete).
        thicknessMm: 10,
        sqmPerPiece: 0.36,
        quantityMode: 'PIECES',
        piecesInput: 20,
        piecesPerBox: 4,
        // Required on this form too.
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

// Fixture mirroring PricingCostingItemDto's override/provenance fields (see PricingCostingDtos.java).
// Deliberately a DIFFERENT id (5001, not buildCosting's default 211) and a DIFFERENT
// landedCostPerUnitThb (55, not buildDecisionItem's 60): a test pairing this with
// buildDecisionItem({ pricingCostingItemId: 5001 }) exercises the REAL join
// (costing.items.find(ci => ci.id === decisionItem.pricingCostingItemId)) rather than an
// accidental id/number collision with buildCosting's default fixture.
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
  // Nothing on the page calls these any more (submitToCeo no longer chains them; the controls that
  // drove them are deleted). They stay mocked ONLY so the "must not come back" assertions below have
  // something that records a call; an unmocked vi.fn() would throw, a worse failure to read.
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
  // Reset every test to "no new-engine quotation yet": mockResolvedValue (not Once) would leak into
  // later tests otherwise, like getPricingDecisionSalesView above.
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
  api.depositNotices.listByTicket.mockResolvedValue({ depositNotices: [] });
  api.catalog.prices.mockResolvedValue({ items: [] });
  // A small real-looking factory roster: enough for the picker + duplicate-name tests without the
  // full catalog fixture.
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
    // sqmPerPiece is null by default, which makes this fixture LEGACY-shaped under
    // isNewFormEligibleDecision despite requestedUnitBasis already being PER_PIECE (the real
    // backend rule needs PER_PIECE AND sqmPerPiece). Tests exercising the new mode picker pass sqmPerPiece.
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
  // Lead-time change requests (LeadTimeChangeDto[]).
  leadTimeChanges = [],
  // The deal's stored ใบขอซื้อ rows (ImportRequestDto[]).
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

// ── CEO pricing workspace: shared helpers (new-form decision, CEO view) ───────────────────────────
// jsdom has no media queries, so the page renders BOTH the desktop table and the mobile cards and
// hides one with Tailwind classes. The card inputs/figures reuse the table's data-testids, so
// EVERY query on the workspace is SCOPED: within the table / a row (ceoTable, ceoRow) or within a
// card (ceoCard). An unscoped getByTestId('pcr-ceo-margin-input-8001') is ambiguous by design. The
// unique, unscoped ids are the summary bar's (pcr-ceo-save-all / -approve / -return) and the
// mobile bar's (pcr-ceo-mobile-*).
// The 3-step indicator of a factory card (ติดต่อโรงงาน -> กรอกราคา -> ยืนยันราคาเสนอ)
// -> ['done' | 'now' | 'todo', ...] read off each step's data-state.
const stepStates = (quoteId) => within(screen.getByTestId(`pcr-factory-steps-${quoteId}`))
  .getAllByTestId('pcr-factory-step').map((el) => el.getAttribute('data-state'));
// Import PCR redesign: a spec row is a <dl> of dt/dd pairs; read a value by its label.
const specValue = (spec, label) => spec.getByText(label).nextElementSibling?.textContent.trim();
const ceoTable = () => within(screen.getByTestId('pcr-ceo-table'));
const ceoRow = (id) => within(screen.getByTestId(`pcr-ceo-row-${id}`));
const ceoCard = (id) => within(screen.getByTestId(`pcr-ceo-card-${id}`));
const ceoSummary = () => within(screen.getByRole('region', { name: 'สรุปราคาคำขอนี้' }));
const ceoPricingPanel = () => screen.getByRole('region', { name: 'ตั้งราคารายการ' });
const saveAllButton = () => screen.getByTestId('pcr-ceo-save-all');
const nfItem = (overrides = {}) => buildDecisionItem({ sqmPerPiece: 0.36, ...overrides });
const nfStock = (overrides = {}) => nfItem({
  stockSource: 'IN_THAILAND', frozenLandedCostPerRequestedUnitThb: null, listUnitPrice: null, netUnitPrice: null, ...overrides,
});
// N request items named "SCG A1", "SCG A2", … (itemDisplayName = brand + model), ids 1..N.
function requestItemsN(n) {
  const base = buildRequest().items[0];
  return Array.from({ length: n }, (_, i) => ({
    ...base, id: i + 1, model: `A${i + 1}`, productDescription: `กระเบื้องพื้น SCG A${i + 1}`,
  }));
}
function lastSavedDecisionItem() {
  const calls = api.pricingRequests.updatePricingDecision.mock.calls;
  return calls[calls.length - 1][1].items[0];
}
async function renderCeoNewForm({
  items, priceMode = 'NET', requestItems, costings, factoryQuotes, user = ceoUser, showToast, decisions,
} = {}) {
  const request = buildRequest({ summary: { status: 'CEO_REVIEWING' }, ...(requestItems ? { items: requestItems } : {}) });
  api.pricingRequests.listPricingDecisions.mockResolvedValue({
    items: decisions ?? [buildDecision({ priceMode, items: items ?? [nfItem({ listUnitPrice: 100, netUnitPrice: 100 })] })],
  });
  const utils = renderDetailPage({ user, request, costings, factoryQuotes, showToast });
  await waitForLoaded(request);
  await screen.findByText('PCD-2026-0001');
  return { request, ...utils };
}
function openRowDetails(name = 'SCG A1') {
  fireEvent.click(ceoTable().getByRole('button', { name: `รายละเอียด ${name}` }));
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

  it('gives the CEO no factory-quote or ต้นทุนนำเข้า panel any more (2026-10-01: the CEO prices inside รายการสินค้าและราคาตั้งต้น), and no action controls anywhere', async () => {
    renderDetailPage({
      user: ceoUser,
      factoryQuotes: [buildFactoryQuote({ status: 'RESPONSE_RECEIVED' })],
      costings: [buildCosting()],
    });
    await waitForLoaded();

    // The CEO's pricing workspace is the single "รายการสินค้าและราคาตั้งต้น" panel. The
    // factory-quote panel "รายการสินค้า (N รายการ)" is Import-only and "ต้นทุนนำเข้า" is gone for the
    // CEO, so neither the panels nor the raw costing code may render for this role. Intent:
    // nothing mutating is reachable for the CEO on the quote/costing side (asserted below).
    expect(await screen.findByRole('heading', { level: 2, name: 'รายการสินค้าและราคาตั้งต้น' })).not.toBeNull();
    expect(screen.queryByRole('heading', { name: /^รายการสินค้า \(/ })).toBeNull();
    expect(screen.queryByText('ต้นทุนนำเข้า')).toBeNull();
    expect(screen.queryByText('COST-2026-0001')).toBeNull();

    // Every mutating control on the factory-quote panel is Import-only (isImport(user)) and must be
    // absent for CEO. The three costing ones (สร้างร่างต้นทุน / คำนวณใหม่ / ส่งให้ CEO ตรวจ) no longer
    // exist for any role.
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
    // No editable email-draft or response-entry form fields either. Queried by accessible name, not
    // placeholder: the fields carry real labels, so a placeholder query would report "absent" for a
    // present field that has no placeholder (an assertion passing for the wrong reason).
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

    // The composer lives in a modal behind each factory card's สร้างเมล button (the ร่างอีเมล /
    // ดูอีเมล labels are gone, see IA naming table).
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

  // The price grid must not invite a quoted price before the factory has been marked
  // ติดต่อโรงงานแล้ว (a DRAFT quote = not contacted yet).
  it('locks the price grid with a hint while the factory is not yet contacted (DRAFT)', async () => {
    renderDetailPage({ user: importUser, factoryQuotes: [buildFactoryQuote()] }); // default status DRAFT
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    // Import PCR redesign: the lock reason lives ONCE in the terms row, not under each input.
    expect(screen.getByTestId('pcr-await-contact-91').textContent).toContain('ช่องราคาจะเปิดหลังกด "ติดต่อโรงงานแล้ว"');
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

  // A DRAFT quote outside send()'s window does NOT unlock the grid: FactoryQuoteService#receive
  // 409s on EVERY DRAFT quote ("ต้องกด ติดต่อโรงงานแล้ว ก่อนกรอกราคา"), so the grid stays locked past
  // the window too, and since markContacted is itself guarded by DRAFT_STATUSES, the
  // ติดต่อโรงงานแล้ว button is withheld there rather than offered to 409.
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

  // Before any mail is generated the price section previews what needs pricing, grouped by the
  // routed factory, read-only (no editable price input yet).
  it('previews what needs pricing (grouped by factory) before any mail is generated', async () => {
    renderDetailPage({ user: importUser }); // no factoryQuotes → nothing generated yet
    await waitForLoaded();

    const preview = await screen.findByTestId('pcr-price-preview');
    expect(screen.getAllByTestId('pcr-price-preview-group').length).toBeGreaterThan(0);
    expect(within(preview).getByText('SCG Ceramics')).not.toBeNull();   // the routed factory group
    expect(screen.queryByLabelText(/^ราคาที่เสนอ/)).toBeNull();          // no editable price yet
  });

  // The preview covers exactly the items no CURRENT factory quote already lists, independent of
  // whether other items have one. An item whose factory was blank until Import gap-filled it
  // (canSetItemFactory, within FACTORY_ROUTING_STATUSES; never a re-route, per setItemFactory's
  // Javadoc) AFTER drafts were generated for other items has no factory-quote row, so it would
  // appear in neither the preview nor the quote grid. The banner copy must not claim "no draft
  // generated at all" in this mixed case, and the item count must include these previewed items.
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
    // (The lock reason sits in each factory card's terms row; there is no amber pre-draft banner.)

    // A and B still render as their own quote-grid rows (locked, since request stays
    // IMPORT_REVIEWING and the quote stays DRAFT) rather than being hidden by C's preview.
    expect(screen.getAllByLabelText(/^ราคาที่เสนอ/).length).toBe(2);

    // The progress strip's สินค้า figure counts all three items (2 quoted + 1 previewed), not just
    // the quoted ones.
    expect(within(screen.getByRole('region', { name: 'ความคืบหน้าการขอราคา' })).getByTestId('pcr-import-fig-items').textContent.trim()).toBe('3');
  });

  // A SUBMITTED request opened here (e.g. from a link) needs a รับเรื่อง affordance on the page
  // itself, not only in the คิวขอราคา queue.
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

  // Once contacted, Import can still reopen the mail, read-only (view + copy); the button reads
  // ดูเมลที่ส่ง (same testid, same modal).
  it('reopens the mail of a CONTACTED factory read-only (copy kept, no บันทึกร่าง)', async () => {
    renderDetailPage({ user: importUser, factoryQuotes: [buildFactoryQuote({ status: 'REQUESTED' })] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    const openBtn = screen.getByTestId('pcr-open-email-draft-91');
    expect(openBtn.textContent).toContain('ดูเมลที่ส่ง');
    expect(screen.queryByRole('button', { name: 'สร้างเมล' })).toBeNull();
    fireEvent.click(openBtn);

    const dialog = await screen.findByTestId('factory-email-draft-modal');
    expect(within(dialog).getByRole('button', { name: /คัดลอกเมล/ })).not.toBeNull();
    expect(within(dialog).queryByRole('button', { name: 'บันทึกร่าง' })).toBeNull();
    expect(within(dialog).getByLabelText('ถึง').disabled).toBe(true);
  });

  // Outside the contact window the modal is read-only too: updateDraft is guarded by the identical
  // DRAFT_STATUSES (409).
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
    // ระยะเวลาผลิต-ส่งมอบ are optional in ReceiveFactoryQuoteRequest, so the payload carries null.
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

  // Seed bug guard. FactoryQuoteRepository.insertDraftItems seeds a fresh quote item's quotedUnit
  // from the REQUEST's requested_unit (real text, e.g. ตร.ม.) and unitBasis from a basis guess, so
  // the two already differ on a brand-new draft. Seeding both from ONE variable threw the real unit
  // away in favour of the basis code. Mutation-checked: sharing one variable again must turn it red.
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

  // Price-terms change: the group-level หน่วยราคา select is gone. Every line has its own แผ่น / ตร.ม.
  // switch (a button group), and changing it still writes unitBasis AND quotedUnit together.
  it('switching a line\'s unit (/ แผ่น → / กล่อง needs a PER_BOX request, so here / ตร.ม.) updates both unitBasis and quotedUnit on save', async () => {
    const quote = buildFactoryQuote({ status: 'REQUESTED' }); // default item: unitBasis PER_PIECE
    renderDetailPage({ user: importUser, factoryQuotes: [quote] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    const group = await screen.findByRole('group', { name: /^หน่วยราคา รายการ #1/ });
    expect(screen.queryByRole('combobox', { name: /^หน่วย/ })).toBeNull();
    fireEvent.click(within(group).getByRole('button', { name: '/ ตร.ม.' }));
    fireEvent.change(screen.getByLabelText(/^ราคาที่เสนอ/), { target: { value: '10' } });
    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));

    await waitFor(() => expect(api.pricingRequests.receiveFactoryQuote).toHaveBeenCalledWith(
      quote.id,
      expect.objectContaining({
        items: [expect.objectContaining({ unitBasis: 'PER_SQM', quotedUnit: 'ตร.ม.' })],
      }),
    ));
  });

  // The ตร.ม./หน่วย input: FactoryQuoteService requires sqmPerUnit for any PER_SQM line
  // (validateAndNormalizeResponseItems) and Import needs a way to supply it.
  const perSqmItem = (overrides = {}) => ({
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
    ...overrides,
  });
  const requestWithSqmPerPiece = (sqmPerPiece) => {
    const base = buildRequest();
    return buildRequest({ items: [{ ...base.items[0], sqmPerPiece }] });
  };

  it('asks for ตร.ม./หน่วย on a PER_SQM line only when the request item has no sqmPerPiece, and sends what was typed', async () => {
    const quote = buildFactoryQuote({ status: 'REQUESTED', items: [perSqmItem()] });
    renderDetailPage({ user: importUser, request: requestWithSqmPerPiece(null), factoryQuotes: [quote] });
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

  it('auto-fills ตร.ม./หน่วย from the request item sqmPerPiece, hides the box, and sends it', async () => {
    const quote = buildFactoryQuote({ status: 'REQUESTED', items: [perSqmItem()] });
    renderDetailPage({ user: importUser, request: requestWithSqmPerPiece(0.36), factoryQuotes: [quote] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    expect(screen.queryByLabelText(/^ตร\.ม\.\/หน่วย/)).toBeNull();
    fireEvent.change(screen.getByLabelText(/^ราคาที่เสนอ/), { target: { value: '120' } });
    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));

    await waitFor(() => expect(api.pricingRequests.receiveFactoryQuote).toHaveBeenCalledWith(
      quote.id,
      expect.objectContaining({ items: [expect.objectContaining({ sqmPerUnit: 0.36 })] }),
    ));
  });

  it('never overwrites a ตร.ม./หน่วย already saved on the quote with the request item value', async () => {
    const quote = buildFactoryQuote({ status: 'REQUESTED', items: [perSqmItem({ sqmPerUnit: 0.5 })] });
    renderDetailPage({ user: importUser, request: requestWithSqmPerPiece(0.36), factoryQuotes: [quote] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    fireEvent.change(screen.getByLabelText(/^ราคาที่เสนอ/), { target: { value: '120' } });
    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));

    await waitFor(() => expect(api.pricingRequests.receiveFactoryQuote).toHaveBeenCalledWith(
      quote.id,
      expect.objectContaining({ items: [expect.objectContaining({ sqmPerUnit: 0.5 })] }),
    ));
  });

  it('labels the price box ราคา/<unit> — แผ่น for a per-piece line, ตร.ม. for a per-sqm line', async () => {
    const piece = buildFactoryQuote({ status: 'REQUESTED' }); // default item: PER_PIECE
    const { unmount } = renderDetailPage({ user: importUser, factoryQuotes: [piece] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');
    expect(screen.getByLabelText(/^ราคาที่เสนอ/).getAttribute('placeholder')).toBe('ราคา/แผ่น');
    unmount();

    const sqm = buildFactoryQuote({ status: 'REQUESTED', items: [perSqmItem()] });
    renderDetailPage({ user: importUser, factoryQuotes: [sqm] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');
    expect(screen.getByLabelText(/^ราคาที่เสนอ/).getAttribute('placeholder')).toBe('ราคา/ตร.ม.');
  });

  it('uses plain text boxes (no number spinner) for price and ตร.ม./หน่วย, keeping only digits and one decimal point', async () => {
    const quote = buildFactoryQuote({ status: 'REQUESTED', items: [perSqmItem()] });
    renderDetailPage({ user: importUser, request: requestWithSqmPerPiece(null), factoryQuotes: [quote] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    const price = screen.getByLabelText(/^ราคาที่เสนอ/);
    const sqm = screen.getByLabelText(/^ตร\.ม\.\/หน่วย/);
    expect(price.getAttribute('type')).toBe('text');
    expect(sqm.getAttribute('type')).toBe('text');

    fireEvent.change(price, { target: { value: '1,200.5x' } });
    fireEvent.change(sqm, { target: { value: '0.36' } });
    expect(price.value).toBe('1200.5');
    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));

    await waitFor(() => expect(api.pricingRequests.receiveFactoryQuote).toHaveBeenCalledWith(
      quote.id,
      expect.objectContaining({ items: [expect.objectContaining({ rawUnitPrice: 1200.5, sqmPerUnit: 0.36 })] }),
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
  // Import keys in the price and submits; it never touches the costing aggregate. ONE button does
  // the hand-off.
  //
  // The severed costing calls (createDraft/recalculate/submit) are @Deprecated shells that throw
  // 409 COSTING_MOVED_TO_CEO. This suite mocks `api`, so a test asserting the old call chain stayed
  // green over a button that always errored in production. The assertions are wrong-way-round on
  // purpose: the severed calls must NOT be made.
  it('sends the factory quote to the CEO with markFactoryQuoteReady alone, and touches no severed costing endpoint', async () => {
    const quote = buildFactoryQuote({ status: 'RESPONSE_RECEIVED' });
    renderDetailPage({ user: importUser, factoryQuotes: [quote] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    // ยืนยันราคาเสนอ replaces ส่งให้ CEO อนุมัติราคา. A response is already on file
    // (RESPONSE_RECEIVED) and nothing was edited this session, so confirmFactoryQuote must skip
    // receiveFactoryQuote (see its doc comment): an unconditional receive() would bump the
    // revision and notify the CEO of a "revision" that never happened.
    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));

    await waitFor(() => expect(api.pricingRequests.markFactoryQuoteReady).toHaveBeenCalledWith(quote.id));
    expect(api.pricingRequests.receiveFactoryQuote).not.toHaveBeenCalled();
    expect(api.pricingRequests.createCosting).not.toHaveBeenCalled();
    expect(api.pricingRequests.recalculateCosting).not.toHaveBeenCalled();
    expect(api.pricingRequests.submitCosting).not.toHaveBeenCalled();
  });

  // Clicking again must not fire a pure 409: a quote already at READY_FOR_COSTING must not offer
  // the action while nothing has been edited. markFactoryQuoteReady is not idempotent (its UPDATE
  // matches zero rows on an already-ready quote and the service 409s); an edit re-opens it (see
  // the next test).
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
    // superseded original.
    await waitFor(() => expect(api.pricingRequests.markFactoryQuoteReady).toHaveBeenCalledWith(92));
  });

  // Wrong-way-round: these surfaces must be ABSENT for Import, not merely different.
  it('shows Import no costing, CEO-decision, customer-quotation or ask-Sales surface', async () => {
    const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
    api.pricingRequests.listPricingDecisions.mockResolvedValue({ items: [buildDecision()] });
    renderDetailPage({ user: importUser, request, factoryQuotes: [buildFactoryQuote()], costings: [buildCosting()] });
    await waitForLoaded(request);
    await screen.findByText('SCG Ceramics');

    expect(screen.queryByText('ต้นทุนนำเข้า')).toBeNull();
    expect(screen.queryByText('การพิจารณาราคาขายของ CEO')).toBeNull();
    expect(screen.queryByText('ใบเสนอราคาลูกค้า')).toBeNull();
    // The ขอข้อมูลเพิ่มเติม feature no longer exists.
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
// confirmLabel are each a separate `confirmAction?.type === ...` ternary CHAIN. Removing an action
// means deleting a branch from the middle of three chains, and the failure is silent: mis-nesting
// the `message` chain would show ตีกลับ's copy on อนุมัติราคาขาย while every assertion that only
// checked the right mutation fired stays green. These cases pin the copy of the four remaining
// actions. Mutation-checked: swapping any single branch of any chain turns exactly the matching
// case red.
describe('PricingRequestDetailPage shared ConfirmDialog copy (the three surviving actions)', () => {
  it('approveDecision — อนุมัติราคาขาย / เมื่ออนุมัติแล้ว… / อนุมัติ', async () => {
    const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
    api.pricingRequests.listPricingDecisions.mockResolvedValue({ items: [buildDecision()] });
    renderDetailPage({ user: ceoUser, request });
    await waitForLoaded(request);
    await screen.findByText('PCD-2026-0001');

    fireEvent.click(screen.getByRole('button', { name: 'อนุมัติราคาขาย' }));

    const dialog = await screen.findByRole('dialog', { name: 'อนุมัติราคาขาย' });
    // Also states the approved price is ex-VAT (see the "selling price is stated as ก่อน VAT"
    // describe block below).
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
    // (Waits on the pricing workspace, not a costing panel, which the CEO no longer has.)
    await screen.findByRole('heading', { level: 2, name: 'รายการสินค้าและราคาตั้งต้น' });

    expect(screen.queryByRole('button', { name: 'ส่งให้ CEO ตรวจ' })).toBeNull();
    expect(screen.queryByRole('dialog', { name: 'ส่งต้นทุนให้ CEO ตรวจ' })).toBeNull();
    expect(screen.queryByText('เมื่อส่งแล้ว เวอร์ชันต้นทุนนี้จะแก้ไขไม่ได้')).toBeNull();
  });
});

describe('PricingRequestDetailPage customer-change revision editing', () => {
  it('lets the owning sales rep open the revision modal (seeded from the current request) and create a revision via createCustomerChangeRevision', async () => {
    // The line carries the currency/unit sales fixed (a legacy line without them must be completed
    // before a revision can be saved; covered in PricingRequestCreateModal.test.jsx).
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

// A DRAFT (e.g. a customer-change revision) can be sent from its own page.
describe('PricingRequestDetailPage DRAFT — ส่งให้ฝ่ายนำเข้า', () => {
  it('the owning rep sends their DRAFT to import from its own page', async () => {
    const request = buildRequest({ summary: { status: 'DRAFT' } });
    api.pricingRequests.submit.mockResolvedValue({ pricingRequest: request });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);

    fireEvent.click(screen.getByRole('button', { name: 'ส่งให้ฝ่ายนำเข้า' }));

    await waitFor(() => expect(api.pricingRequests.submit).toHaveBeenCalledWith(501));
  });

  it.each([
    ['another sales rep, on a DRAFT', salesOwner, { status: 'DRAFT', ticketCreatedById: 999 }],
    ['the sales manager, on a DRAFT', salesManager, { status: 'DRAFT' }],
    ['import, on a DRAFT', importUser, { status: 'DRAFT' }],
    ['the owning rep, once SUBMITTED', salesOwner, { status: 'SUBMITTED' }],
    ['the owning rep, once the deal order is confirmed', salesOwner, { status: 'DRAFT', ticketPaymentStatus: 'CUSTOMER_CONFIRMED' }],
  ])('no ส่งให้ฝ่ายนำเข้า for %s', async (_label, user, summary) => {
    const request = buildRequest({ summary });
    renderDetailPage({ user, request });
    await waitForLoaded(request);

    expect(screen.queryByRole('button', { name: 'ส่งให้ฝ่ายนำเข้า' })).toBeNull();
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
  // The cost breakdown, the formula derivation, ปรับต้นทุนเอง, and ปรับราคาเอง all live inside a
  // per-item "วิธีคำนวณราคานี้" CollapsibleSection, collapsed by default (it unmounts its body
  // rather than CSS-hiding it), so every test that needs one of those controls must open it first.
  function expandDerivation() {
    fireEvent.click(screen.getByRole('button', { name: 'วิธีคำนวณราคานี้' }));
  }

  // "ต้นทุนรวมนำเข้า (รวมค่าขนส่งและภาษี): <code>฿60.00</code>" splits its label and value across an
  // element boundary (the <code> wraps the figure), which the default getByText matcher does not
  // read across (a testing-library limitation, not a markup defect). A function matcher reading the
  // combined textContent of the leaf that owns it is the documented fix.
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
      expect.objectContaining({ defaultMarginPct: 0.3, clientRequestId: expect.any(String) }),
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

    expect(screen.getByText(byCombinedText(/ต้นทุนรวมนำเข้า.*฿60\.00/))).not.toBeNull();
    expect(screen.getByText(/ราคาขาย.*฿72\.00/)).not.toBeNull();
    // The requested quantity reads "20 แผ่น", never the raw basis enum "20 (PER_PIECE)". The
    // request-items list already renders one "20 แผ่น"; the decision line is the second.
    expect(screen.getAllByText('20 แผ่น')).toHaveLength(2);
    expect(screen.queryByText(/PER_PIECE/)).toBeNull();
    // The per-item margin/minimum/ceiling grid is gone.
    expect(screen.queryByPlaceholderText('อัตรากำไร เช่น 0.20 = 20%')).toBeNull();
    expect(screen.queryByPlaceholderText('ราคาขั้นต่ำ')).toBeNull();
    expect(screen.queryByPlaceholderText('ส่วนลดสูงสุด เช่น 0.10 = 10%')).toBeNull();
    expect(screen.queryByRole('button', { name: 'บันทึกการเปลี่ยนแปลง' })).toBeNull();
    // The only two actions left — asserted by role name so a stray extra button would show up as
    // "found 2" against getByRole's own strictness, not silently pass.
    expect(screen.getByRole('button', { name: 'อนุมัติราคาขาย' })).not.toBeNull();
    expect(screen.getByRole('button', { name: 'ตีกลับให้ฝ่ายนำเข้าแก้ไข' })).not.toBeNull();
  });

  // The decision's FX line reads a Thai source and a Thai date, not the raw source enum and ISO
  // date ("อัตราแลกเปลี่ยน 1 (MANUAL, 2026-10-30)").
  it.each(['MANUAL', 'BOT'])('the CEO decision FX line shows a Thai source label and a Thai date, never %s or the ISO date', async (fxSource) => {
    const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
    api.pricingRequests.listPricingDecisions.mockResolvedValue({
      items: [buildDecision({ currency: 'USD', fxRateUsed: 35.5, fxSource, fxEffectiveDate: '2026-10-30' })],
    });
    renderDetailPage({ user: ceoUser, request });
    await waitForLoaded(request);
    await screen.findByText('PCD-2026-0001');

    const fxLines = screen.getAllByText(/อัตราแลกเปลี่ยน 35\.5/);
    expect(fxLines.length).toBeGreaterThan(0);
    fxLines.forEach((line) => {
      expect(line.textContent).not.toContain(fxSource);
      expect(line.textContent).not.toContain('2026-10-30');
      expect(line.textContent).toContain(formatThaiDate('2026-10-30'));
      expect(line.textContent).toMatch(/\([^)]*[\u0E00-\u0E7F][^)]*\)/); // the source inside the brackets is Thai
    });
  });

  // CEO owns costing: the CEO's per-line cost override and recalculate controls.
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
  // && !isImport(user)), so this is not a narrower gate than the panel itself; it must be absent
  // for the same reason every other control in this panel is absent for Import.
  it('does not offer "คำนวณต้นทุนใหม่" to Import — the whole CEO decision panel is import-excluded', async () => {
    const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
    api.pricingRequests.listPricingDecisions.mockResolvedValue({ items: [buildDecision()] });
    renderDetailPage({ user: importUser, request });
    await waitForLoaded(request);

    expect(screen.queryByTestId('pcr-ceo-recalculate-cost')).toBeNull();
  });

  // Wrong-way-round: editable = isDraft && canActOnPricingDecision(...), so an APPROVED decision is
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

  // Per-line cost override.
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
      expect(screen.getByText(byCombinedText(/ต้นทุนรวมนำเข้า.*฿60\.00/))).not.toBeNull();
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

    // Wrong-way-round: the whole CEO decision panel is import-excluded, like the recalculate
    // button; this is not a narrower gate than the panel itself.
    it('shows Import no cost-override button anywhere', async () => {
      renderWithCostingItem({}, { user: importUser });
      await waitForLoaded(buildRequest({ summary: { status: 'CEO_REVIEWING' } }));

      expect(screen.queryByTestId('pcr-ceo-cost-override-8001')).toBeNull();
    });
  });

  // "ปรับราคาเอง": a REAL behaviour change. It overrides the SELLING PRICE directly (not the cost),
  // and the formula stops driving that line. Reuses PUT /pricing-decisions/{id}
  // (updatePricingDecision); see PricingDecisionRequests.UpdatePricingDecisionItemRequest for why
  // sellingPriceOverride/clearSellingPriceOverride need a tri-state plain COALESCE can't express,
  // and PriceOverrideModal / the overrideSellingPrice mutation in the page.
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
            clearDiscountPct: false,
            clearSpecialPriceSqm: false,
            clearDirectNetPrice: false,
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
            clearDiscountPct: false,
            clearSpecialPriceSqm: false,
            clearDirectNetPrice: false,
            decisionNote: 'กลับไปใช้ราคาอัตโนมัติ',
          }],
        },
      ));
    });

    // Uncollapsed, like the stale-override badge: a CEO must see AT A GLANCE that a price was fixed
    // manually, without expanding anything.
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

  // ราคาขั้นต่ำ is auto-populated server-side at approve() (PricingDecisionService#approve), so only
  // a missing MARGIN can block approval, and only on a line with no active "ปรับราคาเอง" override.
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

  // Import's job ends at ส่งให้ CEO อนุมัติราคา, so it is shown NO decision surface at all.
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
    // "20 แผ่น", not the raw basis enum (the request-items list renders one already; the
    // sales-view line is the second).
    expect(screen.getAllByText('20 แผ่น')).toHaveLength(2);
    expect(screen.queryByText(/PER_PIECE/)).toBeNull();
  });

  // No approved decision to show yet (or any more), so no 404 on every load.
  it.each(['READY_FOR_CEO_REVIEW', 'SUPERSEDED'])('does not fetch the sales-view projection on a %s request', async (status) => {
    const request = buildRequest({ summary: { status } });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);

    await waitFor(() => expect(api.dealQuotations.findForPricingRequest).toHaveBeenCalled());
    expect(api.pricingRequests.getPricingDecisionSalesView).not.toHaveBeenCalled();
  });

  it('does not fetch the sales-view projection for a non-owning sales rep', async () => {
    const request = buildRequest({ summary: { status: 'APPROVED_FOR_QUOTATION', ticketCreatedById: 999 } });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);

    await waitFor(() => expect(api.pricingRequests.listFactoryQuotes).not.toHaveBeenCalled());
    expect(api.pricingRequests.getPricingDecisionSalesView).not.toHaveBeenCalled();
  });

  // Per-line margin: the CEO sets อัตรากำไร% PER product line, not one combined default; each
  // line's price = cost × (1 + its own margin). Reuses PUT /pricing-decisions/{id}
  // (updatePricingDecision) with a single-item marginPct payload, the same endpoint
  // applyItemUpdates handles for the selling-price override above. "อัตรากำไรเริ่มต้น" stays the
  // creation-time seed; this is a per-line OVERRIDE of it, on the legacy formula path only (the
  // new-form path prices through the mode picker, not margin).
  describe('CEO per-line margin ("อัตรากำไรรายรายการ")', () => {
    it('seeds the per-line margin input from the line proposedMarginPct and saves a single-item marginPct payload', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({ items: [buildDecision()] });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');

      const input = screen.getByTestId('pcr-ceo-margin-input-8001');
      expect(input.value).toBe('0.2');

      fireEvent.change(input, { target: { value: '0.35' } });
      fireEvent.click(screen.getByTestId('pcr-ceo-margin-save-8001'));

      await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalledWith(
        7001,
        { items: [{ pricingDecisionItemId: 8001, marginPct: 0.35 }] },
      ));
    });

    it('lets two lines carry DIFFERENT margins — each save targets only its own line', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({
        items: [buildDecision({ items: [
          buildDecisionItem(),
          buildDecisionItem({ id: 8002, pricingRequestItemId: 2, pricingCostingItemId: 2, model: 'A2', proposedMarginPct: 0.1 }),
        ] })],
      });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');

      expect(screen.getByTestId('pcr-ceo-margin-input-8001').value).toBe('0.2');
      expect(screen.getByTestId('pcr-ceo-margin-input-8002').value).toBe('0.1');

      fireEvent.change(screen.getByTestId('pcr-ceo-margin-input-8002'), { target: { value: '0.5' } });
      fireEvent.click(screen.getByTestId('pcr-ceo-margin-save-8002'));

      await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalledWith(
        7001, { items: [{ pricingDecisionItemId: 8002, marginPct: 0.5 }] },
      ));
      // Only one line's save fired, and it carried ONLY its own id.
      expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalledTimes(1);
    });

    it('does not call the API when the margin is unchanged (save stays disabled until edited)', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({ items: [buildDecision()] });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');

      fireEvent.click(screen.getByTestId('pcr-ceo-margin-save-8001'));
      expect(api.pricingRequests.updatePricingDecision).not.toHaveBeenCalled();
    });

    it('offers no per-line margin input on a price-overridden line (the formula no longer drives it)', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({
        items: [buildDecision({ items: [buildDecisionItem({ manualSellingPricePerRequestedUnit: 90 })] })],
      });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');

      expect(screen.queryByTestId('pcr-ceo-margin-input-8001')).toBeNull();
    });

    it('new-form NET: the per-line margin is a LIVE PERCENT input (no own button) that previews and saves via the one บันทึกราคา as the FRACTION marginPct', async () => {
      // The live preview needs the selling buffer; with it = 1: cost 60 → margin 50% → list 90, net 90.
      api.pricingFormulaConfig = { get: vi.fn().mockResolvedValue({ formulaConfig: { sellingBuffer: 1 } }) };
      try {
        const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
        api.pricingRequests.listPricingDecisions.mockResolvedValue({
          items: [buildDecision({ priceMode: 'NET', items: [buildDecisionItem({ sqmPerPiece: 0.36 })] })],
        });
        renderDetailPage({ user: ceoUser, request });
        await waitForLoaded(request);
        await screen.findByText('PCD-2026-0001');
        await waitFor(() => expect(api.pricingFormulaConfig.get).toHaveBeenCalled());

        const row = () => within(screen.getByTestId('pcr-ceo-row-8001'));
        const input = row().getByTestId('pcr-ceo-margin-input-8001');
        // stored fraction 0.2 is DISPLAYED as the percent 20
        expect(input.value).toBe('20');
        // no per-row margin save and no per-row บันทึกราคา: the realtime model has only the one save
        expect(screen.queryByTestId('pcr-ceo-margin-save-8001')).toBeNull();
        expect(screen.queryByTestId('pcr-ceo-save-price-8001')).toBeNull();

        // Typing 50 (percent) updates the row LIVE, before any save: margin 0.50 → list 90, net 90,
        // profit 30/unit × 20 = ฿600.00 = 50.0% of cost. (Read as a fraction 50 it would be ฿3,060.)
        fireEvent.change(input, { target: { value: '50' } });
        await waitFor(() => expect(row().getByTestId('pcr-ceo-profit-8001').textContent).toMatch(/฿600\.00.*50\.0%/));
        expect(row().getByTestId('pcr-ceo-formula-list-8001').textContent).toContain('90.00');

        fireEvent.click(saveAllButton());
        await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalledWith(
          7001, { items: [expect.objectContaining({ pricingDecisionItemId: 8001, marginPct: 0.5 })] }));
      } finally {
        delete api.pricingFormulaConfig;
      }
    });

    it('new-form SPECIAL_SQM: กำไร % comes first and derives ราคาพิเศษ = ทุน/ตร.ม. × (1 + กำไร) × 1.07, net and กำไร show before save (owner 2026-11-06)', async () => {
      api.pricingFormulaConfig = { get: vi.fn().mockResolvedValue({ formulaConfig: { sellingBuffer: 1.07 } }) };
      try {
        await renderCeoNewForm({ priceMode: 'SPECIAL_SQM', items: [nfItem()] });
        await waitFor(() => expect(api.pricingFormulaConfig.get).toHaveBeenCalled());
        // A costed line is never mislabelled as missing its cost in the row.
        expect(ceoRow(8001).getByTestId('pcr-ceo-cost-both-8001').textContent).toContain('60.00');

        // cost 60/แผ่น at 0.36 ตร.ม. = 166.67/ตร.ม.; × 1.30 × 1.07 = 231.83; net = 231.83 / 1.07 / 2.78 = 77.94
        fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-margin-input-8001'), { target: { value: '30' } });
        await waitFor(() => expect(ceoRow(8001).getByTestId('pcr-ceo-special-sqm-8001').value).toBe('231.83'));
        expect(ceoRow(8001).getByTestId('pcr-ceo-net-8001').textContent).toContain('77.94');
        // (77.94 − 60) × 20 แผ่น = 358.80, 29.9% of cost
        expect(ceoRow(8001).getByTestId('pcr-ceo-profit-8001').textContent).toMatch(/฿358\.80.*29\.9%/);

        fireEvent.click(saveAllButton());
        await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalledWith(
          7001, { items: [expect.objectContaining({ pricingDecisionItemId: 8001, marginPct: 0.3, specialPriceSqm: 231.83 })] }));
      } finally {
        delete api.pricingFormulaConfig;
      }
    });

    it('margin input rejects an out-of-range PERCENT (server bound is the fraction 999.999999 = 99,999.9999%) and keeps the one save disabled', async () => {
      await renderCeoNewForm({ priceMode: 'NET', items: [nfItem()] });

      fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-margin-input-8001'), { target: { value: '100000' } });
      expect(saveAllButton().disabled).toBe(true);
      fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-margin-input-8001'), { target: { value: '-100' } });
      expect(saveAllButton().disabled).toBe(true);
      // an in-range percent frees it again (-50% is a legal markup > -100%)
      fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-margin-input-8001'), { target: { value: '-50' } });
      expect(saveAllButton().disabled).toBe(false);
    });

    // The กำไร column: ฿ for the whole line + % of cost.
    it('shows the realized profit (฿ for the line + % of cost) in the กำไร column of a NEW-FORM NET row', async () => {
      // cost 60 THB, net 72 THB (fx 1), qty 20 → profit 12/unit, 20% of cost, ฿240 for the line.
      await renderCeoNewForm({ priceMode: 'NET', items: [nfItem({ netUnitPrice: 72, listUnitPrice: 72 })] });

      const profit = ceoRow(8001).getByTestId('pcr-ceo-profit-8001').textContent;
      expect(profit).toMatch(/฿240\.00/);
      expect(profit).toMatch(/20\.0%/);
    });

    it('shows the realized margin on a LEGACY formula line, and dashes it out when the line has no cost', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({
        items: [buildDecision({ items: [buildDecisionItem()] })], // legacy: cost 60, price 72 → 12/20%
      });
      const { unmount } = renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');
      expect(screen.getByTestId('pcr-ceo-margin-readout-8001').textContent).toMatch(/฿12\.00.*20\.0%/);
      unmount();

      api.pricingRequests.listPricingDecisions.mockResolvedValue({
        items: [buildDecision({ items: [buildDecisionItem({ frozenLandedCostPerRequestedUnitThb: null })] })],
      });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');
      expect(screen.getByTestId('pcr-ceo-margin-readout-8001').textContent).toMatch(/ยังไม่มีต้นทุน/);
    });
  });

  // LandedCostCalculator aggregates every problem across every item into ONE 422 whose message is
  // a heading line + one bullet per problem (LandedCostCalculator#aggregateProblems), and
  // FxResolver's staleness/missing-rate messages are meant to be read in full. Both startCeoReview
  // and recalculateDecisionCost render that error inline instead of the toast (see ceoCostingError
  // in the page: Toast.jsx collapses '\n' to a space and useToast.js auto-dismisses after 3200ms).
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
      // Toast-regression guard: every OTHER useActionMutation call site still reports its error via
      // showToast('error', ...); these two must not, or this flips true.
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

  // Costing is a multi-second round trip: these two controls must SHOW that, not just go
  // `disabled` with no other signal (otherwise "รีเฟรชใช้เวลานานนิดนึง" reads as broken, not slow).
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

  // PricingDecisionService.computeSellingPrice is cost x (1 + margin) x sellingBuffer, rounded
  // HALF_UP to 2dp, with NO VAT term: VAT 7% is only added later, on the customer quotation. Every
  // place a CEO or sales rep reads a selling price on this page must say so.
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

  // ── CEO pricing method ────────────────────────────────────────────────────────────────────
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

      expect(screen.queryByRole('group', { name: 'วิธีกรอกราคากระเบื้อง' })).toBeNull();
      // Legacy UI (ปรับราคาเอง, formula derivation) still renders as before.
      expandDerivation();
      expect(screen.getByTestId('pcr-ceo-price-override-8001')).not.toBeNull();
    });

    it('shows the "วิธีกรอกราคา" picker for a new-form-eligible decision, none of the legacy margin UI, and — with no mode chosen — no price input, only the blocker prompt', async () => {
      await renderCeoNewForm({ priceMode: null, items: [newFormItem()] });

      const group = within(within(ceoPricingPanel()).getByRole('group', { name: 'วิธีกรอกราคา' }));
      for (const name of ['ราคาตั้ง − ส่วนลด %', 'ราคาพิเศษ/ตร.ม.', 'ราคาสุทธิ/แผ่น']) {
        expect(group.getByRole('button', { name }).getAttribute('aria-pressed')).toBe('false');
      }
      expect(screen.queryByRole('group', { name: 'วิธีกรอกราคากระเบื้อง' })).toBeNull();
      // ปรับราคาตั้งเอง is gone; with no mode chosen there is no price input at all.
      expect(screen.queryByTestId('pcr-ceo-price-override-8001')).toBeNull();
      for (const id of ['pcr-ceo-margin-input-8001', 'pcr-ceo-list-price-8001', 'pcr-ceo-discount-8001',
        'pcr-ceo-special-sqm-8001', 'pcr-ceo-direct-net-8001']) {
        expect(ceoRow(8001).queryByTestId(id)).toBeNull();
      }
      expect(screen.queryByText('เลือกวิธีกรอกราคากระเบื้องด้านบนก่อน')).toBeNull();
      expect(screen.getByRole('button', { name: 'เลือกวิธีกรอกราคาก่อน' })).not.toBeNull();
    });

    it.each([
      ['NET', 'ใส่กำไร % แล้วระบบคำนวณราคาตั้งจากต้นทุน · ส่วนลด % หักจากราคาตั้ง'],
      ['SPECIAL_SQM', 'ตั้งกำไร % แล้วระบบคำนวณราคาพิเศษต่อ ตร.ม. (รวม VAT) = ทุน/ตร.ม. × (1 + กำไร) × 1.07 ให้ หรือใส่ราคาพิเศษเอง'],
      ['DIRECT_NET', 'ใส่ราคาสุทธิต่อแผ่น (ก่อน VAT) ตรง ๆ'],
    ])('%s: the picker carries its one-line mode hint', async (priceMode, hint) => {
      await renderCeoNewForm({ priceMode, items: [newFormItem({ netUnitPrice: 100 })] });
      expect(within(ceoPricingPanel()).getByText(hint)).not.toBeNull();
    });

    it('keeps the cost override reachable for a new-form item, inside the row\'s collapsed รายละเอียด (closed unless blocked)', async () => {
      const costingItem = buildCostingItemWithOverride({ id: 1 });
      await renderCeoNewForm({
        items: [newFormItem({ pricingCostingItemId: costingItem.id, listUnitPrice: 100, netUnitPrice: 100 })],
        // buildDecision()'s default pricingCostingId (601) does NOT match buildCosting()'s default id (21)
        costings: [buildCosting({ id: 601, items: [costingItem] })],
      });

      // Collapsed by default when nothing blocks approval (costable, not stale).
      const toggle = ceoTable().getByRole('button', { name: 'รายละเอียด SCG A1' });
      expect(toggle.getAttribute('aria-expanded')).toBe('false');
      expect(ceoTable().queryByTestId('pcr-ceo-cost-override-8001')).toBeNull();
      fireEvent.click(toggle);
      expect(toggle.getAttribute('aria-expanded')).toBe('true');
      expect(ceoTable().getByTestId('pcr-ceo-cost-override-8001')).not.toBeNull();
      // The duty product-type select is gone (see the dedicated test in the CEO-workspace describe).
      expect(ceoTable().queryByTestId('pcr-ceo-product-type-override-8001')).toBeNull();
    });

    it('opens the รายละเอียด by default when a line has a cost BLOCKER (uncostable), so the blocker is never hidden', async () => {
      const costingItem = buildCostingItemWithOverride({ id: 1, uncostableReason: 'ไม่พบอัตราค่าขนส่ง' });
      await renderCeoNewForm({
        items: [newFormItem({ pricingCostingItemId: costingItem.id })],
        costings: [buildCosting({ id: 601, items: [costingItem] })],
      });

      // No click: open from the start.
      expect(ceoTable().getByRole('button', { name: 'รายละเอียด SCG A1' }).getAttribute('aria-expanded')).toBe('true');
      expect(await ceoTable().findByTestId('pcr-ceo-cost-override-8001')).not.toBeNull();
      expect(ceoTable().getByText('ไม่พบอัตราค่าขนส่ง')).not.toBeNull();
    });

    // Reproduced against a genuine new-form decision: a seeded item forced to PER_PIECE with
    // sqmPerPiece 0.72 and listUnitPrice = proposed (83), NET mode with 10% typed but NOT yet saved.
    it('shows the auto formula price per แผ่น and per ตร.ม. in the ราคาตั้ง cell without opening anything (review minor #6)', async () => {
      await renderCeoNewForm({
        priceMode: 'NET',
        items: [newFormItem({
          sqmPerPiece: 0.72, proposedSellingPricePerRequestedUnit: 83, listUnitPrice: 83, netUnitPrice: 83,
        })],
      });

      const cell = ceoRow(8001).getByTestId('pcr-ceo-formula-list-8001').textContent;
      expect(cell).toContain('83.00');
      // 83 / 0.72 = 115.2777... -> round2 -> 115.28 (a plain division, not the SPECIAL_SQM algorithm).
      expect(cell).toContain('115.28');
    });

    it('the live preview reflects a typed discount even when a net price was already saved (review minor #7)', async () => {
      await renderCeoNewForm({
        priceMode: 'NET',
        // Mirrors the repro exactly: listUnitPrice = proposed = 83, already saved at netUnitPrice = 83,
        // 1000 requested pieces so the line-total figures match the coordinator's own worked numbers.
        items: [newFormItem({
          sqmPerPiece: 0.72, listUnitPrice: 83, discountPct: null, netUnitPrice: 83, requestedQuantity: 1000,
        })],
      });

      // Before typing: shows the SAVED net, and the row is not flagged unsaved.
      expect(ceoRow(8001).getByTestId('pcr-ceo-net-8001').textContent).toContain('83.00');
      expect(ceoRow(8001).queryByText('ยังไม่บันทึก')).toBeNull();

      fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-discount-8001'), { target: { value: '10' } });

      // round2(83 x (1 - 10/100)) = 74.70, NOT the stale saved 83.00.
      expect(ceoRow(8001).getByTestId('pcr-ceo-net-8001').textContent).toContain('74.70');
      expect(ceoRow(8001).getByText('ยังไม่บันทึก')).not.toBeNull();
      // รวม follows the live preview too: round2(1000 x 74.70) = 74,700.00.
      expect(ceoRow(8001).getByTestId('pcr-ceo-total-8001').textContent).toContain('74,700.00');
    });

    // ราคาตั้ง is a typed input on a STOCK line only (an import line's is the formula/override
    // price, margin % is its input); ปรับต้นทุนเอง lives in the collapsed รายละเอียด; there is no
    // ปรับราคาตั้งเอง button.
    it('NET: a stock line types its ราคาตั้ง, an import line shows the formula price + margin %, ปรับต้นทุนเอง is one click away, no ปรับราคาตั้งเอง', async () => {
      const costingItem = buildCostingItemWithOverride({ id: 1 });
      await renderCeoNewForm({
        requestItems: requestItemsN(2),
        items: [
          newFormItem({ id: 8001, pricingRequestItemId: 1, pricingCostingItemId: costingItem.id }),
          nfStock({ id: 8002, pricingRequestItemId: 2, listUnitPrice: 150, netUnitPrice: 150 }),
        ],
        costings: [buildCosting({ id: 601, items: [costingItem] })],
      });

      expect(ceoRow(8001).getByTestId('pcr-ceo-margin-input-8001')).not.toBeNull();
      expect(ceoRow(8001).getByTestId('pcr-ceo-list-price-8001')).not.toBeNull();
      expect(ceoRow(8001).getByTestId('pcr-ceo-formula-list-8001')).not.toBeNull();
      expect(ceoRow(8002).getByTestId('pcr-ceo-list-price-8002').value).toBe('150');
      expect(ceoRow(8002).queryByTestId('pcr-ceo-margin-input-8002')).toBeNull();
      expect(screen.queryByTestId('pcr-ceo-price-override-8001')).toBeNull();
      expect(ceoTable().queryByTestId('pcr-ceo-cost-override-8001')).toBeNull();
      openRowDetails('SCG A1');
      expect(ceoTable().getByTestId('pcr-ceo-cost-override-8001')).not.toBeNull();
    });

    // "ปรับราคาเอง" only affects the price under NET (or before a mode is chosen); DIRECT_NET/
    // SPECIAL_SQM never consult it, so offering it there let the CEO believe an override priced a
    // line it never touched.
    it('hides ปรับราคาตั้งเอง under DIRECT_NET (review minor #1), ปรับต้นทุนเอง stays reachable in รายละเอียด', async () => {
      await renderCeoNewForm({
        priceMode: 'DIRECT_NET',
        items: [newFormItem({ directNetPrice: 100, netUnitPrice: 100 })],
        costings: [buildCosting({ id: 601, items: [buildCostingItemWithOverride({ id: 1 })] })],
      });

      expect(screen.queryByTestId('pcr-ceo-price-override-8001')).toBeNull();
      // ปรับต้นทุนเอง IS the correct way to clear the uncosted gate here.
      openRowDetails('SCG A1');
      expect(ceoTable().getByTestId('pcr-ceo-cost-override-8001')).not.toBeNull();
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

    it('disables approve when an item has no cost and no ปรับราคาเอง — mirrors the server\'s uncosted gate, and the blocker line names it', async () => {
      await renderCeoNewForm({
        priceMode: 'NET',
        items: [newFormItem({
          frozenLandedCostPerRequestedUnitThb: null, listUnitPrice: null, netUnitPrice: null,
          manualSellingPricePerRequestedUnit: null,
        })],
      });

      expect(screen.getByTestId('pcr-ceo-approve').disabled).toBe(true);
      expect(within(screen.getByTestId('pcr-ceo-blockers')).getByRole('button', {
        name: 'รายการที่ 1 SCG A1 — ต้องระบุต้นทุนเอง',
      })).not.toBeNull();
      expect(screen.queryByText(/ทุกรายการต้องมีต้นทุนก่อนอนุมัติ/)).toBeNull();
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

    // The discount edit previews in the row and saves through the ONE บันทึกราคา.
    it('NET mode: typing a discount previews against the list price in the row and, with ราคาตั้ง untouched, saves only the discount (no override/list keys)', async () => {
      await renderCeoNewForm({ priceMode: 'NET', items: [newFormItem({ listUnitPrice: 1000 })] });

      expect(ceoRow(8001).getByTestId('pcr-ceo-list-price-8001').value).toBe('1000');
      fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-discount-8001'), { target: { value: '10' } });

      // Client PREVIEW only (round2(1000 * (1 - 10/100)) = 900.00), never sent as a stored value;
      // the server recomputes and returns its own netUnitPrice on save.
      expect(ceoRow(8001).getByTestId('pcr-ceo-net-8001').textContent).toContain('900.00');
      expect(ceoRow(8001).getByText('ยังไม่บันทึก')).not.toBeNull();

      fireEvent.click(saveAllButton());

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

    it('SPECIAL_SQM mode: previews the net per แผ่น with WastageCalculator\'s rounding order before save', async () => {
      await renderCeoNewForm({ priceMode: 'SPECIAL_SQM', items: [newFormItem()] });

      fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-special-sqm-8001'), { target: { value: '1350' } });

      // QN6900704-2 #1: 1,350 / 1.07 / round2(1 / 0.36 = 2.78) = 453.84 (the owner's printed figure).
      expect(ceoRow(8001).getByTestId('pcr-ceo-net-8001').textContent).toContain('453.84');
      expect(ceoRow(8001).queryByText('คำนวณเมื่อบันทึก')).toBeNull();

      fireEvent.click(saveAllButton());
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
      await renderCeoNewForm({
        priceMode: 'DIRECT_NET',
        items: [newFormItem({ directNetPrice: 777, netUnitPrice: 777 })],
      });

      // Already saved (netUnitPrice present) — shown as the real figure, the row is not unsaved.
      expect(ceoRow(8001).getByTestId('pcr-ceo-net-8001').textContent).toContain('777.00');
      expect(ceoRow(8001).getByTestId('pcr-ceo-direct-net-8001').value).toBe('777');
      expect(ceoRow(8001).queryByText('ยังไม่บันทึก')).toBeNull();
    });

    it('disables approve until every new-form item has a netUnitPrice, and the blocker line names the one without', async () => {
      await renderCeoNewForm({
        priceMode: 'NET',
        requestItems: requestItemsN(2),
        items: [
          newFormItem({ id: 8001, pricingRequestItemId: 1, netUnitPrice: null }),
          newFormItem({ id: 8002, pricingRequestItemId: 2, netUnitPrice: 900 }),
        ],
      });

      expect(screen.getByTestId('pcr-ceo-approve').disabled).toBe(true);
      const blockers = within(screen.getByTestId('pcr-ceo-blockers'));
      expect(blockers.getByRole('button', { name: 'รายการที่ 1 SCG A1 — ยังไม่มีราคา' })).not.toBeNull();
      expect(blockers.queryByRole('button', { name: /รายการที่ 2/ })).toBeNull();
    });

    it('CEO-only: import never sees the mode picker or price-mode inputs (server already strips the fields; UI only ever renders this panel for ceo)', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({
        items: [buildDecision({ priceMode: 'NET', items: [newFormItem({ listUnitPrice: 1000 })] })],
      });
      renderDetailPage({ user: importUser, request });
      await waitForLoaded(request);

      // Import never reaches the CEO panel (canSeeRawPricingDecision(user) && !isImport(user)),
      // like the "Import sees no CEO decision UI" coverage for the legacy panel.
      expect(screen.queryByText('การพิจารณาราคาขายของ CEO')).toBeNull();
      expect(screen.queryByRole('group', { name: 'วิธีกรอกราคากระเบื้อง' })).toBeNull();
    });
  });
});

describe('PricingRequestDetailPage mobile layout', () => {
  // This page has no JS-driven responsive branching (no useIsMobile() call): every breakpoint is a
  // Tailwind utility class evaluated by CSS media queries, which jsdom does not apply. So there is
  // no separate "mobile DOM"; what a mobile-viewport test CAN prove is that the page still renders
  // its full content tree (nothing crashes, nothing conditionally dropped).
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
    // Import PCR redesign: the header (with the meta line) replaces ภาพรวม, the progress strip leads,
    // and the read-only item-card panel is not rendered for import at all.
    expect(screen.getByTestId('pcr-import-header')).not.toBeNull();
    expect(screen.queryByText('ภาพรวม')).toBeNull();
    expect(screen.queryByText('รายการสินค้าและราคาตั้งต้น')).toBeNull();
    expect(screen.getByRole('region', { name: 'ความคืบหน้าการขอราคา' })).not.toBeNull();
    expect(screen.getAllByText('SCG A1').length).toBeGreaterThan(0);
    // The factory SECTION is present (by role: the heading is "ขอราคาจากโรงงาน").
    expect(await screen.findByRole('heading', { name: 'ขอราคาจากโรงงาน' })).not.toBeNull();
    // ต้นทุนนำเข้า is deliberately absent for Import — see the hiding test above.
  });
});

// The read-only header terms must not repeat the removed ฝ่าย field ("ฝ่าย: —") beside the
// designer code and the validity. It is asserted absent on a request that DOES carry one, so the
// line cannot survive by being conditional on a value; the other header terms are asserted
// present, so the test cannot pass by the whole block having disappeared.
describe('PricingRequestDetailPage header terms — ฝ่าย is not shown (owner, 2026-10-06)', () => {
  it('shows the designer code and the validity, and no ฝ่าย, even when the request stores one', async () => {
    const request = buildRequest({ summary: { deptCode: 'P003', unitCode: 'D002', validityDays: 30 } });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);

    expect(screen.getByText('หน่วยงาน / รหัสผู้ออกแบบ: D002')).not.toBeNull();
    expect(screen.getByText('ยืนราคา: 30 วัน')).not.toBeNull();
    expect(screen.queryByText(/^ฝ่าย:/)).toBeNull();
    expect(screen.queryByText(/P003/)).toBeNull();
  });

  // A request whose ONLY header term is a stored ฝ่าย has nothing left to say there — the block
  // must not open just to show an empty set of dashes.
  it('a request whose only stored header term is a ฝ่าย opens no header-terms block at all', async () => {
    const request = buildRequest({ summary: { deptCode: 'P003' } });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);

    expect(screen.queryByText(/^ฝ่าย:/)).toBeNull();
    expect(screen.queryByText(/^หน่วยงาน \/ รหัสผู้ออกแบบ:/)).toBeNull();
    expect(screen.queryByText(/^ยืนราคา:/)).toBeNull();
  });
});

// The "รายการสินค้าและราคาตั้งต้น" item card shows every sales-entered tile field read-only (label
// โรงงาน, see PricingRequestDetailPage.jsx's comment on brandDisplay), and hides เผื่อ
// (wastage)/the pre-wastage quantity from Import specifically; sales and CEO still see the full
// breakdown. UI scoping only, no backend authz change (buildRequest's base fixture already
// carries color/thicknessMm/sqmPerPiece/quantityMode/piecesInput/piecesPerBox).
describe('PricingRequestDetailPage import price in baht', () => {
  const fxRates = [{ currency: 'EUR', rateToThb: 40 }, { currency: 'THB', rateToThb: 1 }];
  // 5 แผ่น of a 1.44 ตร.ม. slab = 7.2 ตร.ม.
  const request = () => {
    const base = buildRequest();
    return buildRequest({
      items: [{ ...base.items[0], sqmPerPiece: 1.44, requestedQtySqm: 7.2, piecesAfterWastage: 5, catalogBasePrice: 999, catalogCurrency: 'USD' }],
    });
  };
  const quoteWith = (itemOver = {}) => buildFactoryQuote({
    status: 'RESPONSE_RECEIVED',
    items: [{
      id: 911, pricingRequestItemId: 1, supplierProductCode: '', supplierProductDescription: '',
      quotedQuantity: 5, quotedUnit: 'ตร.ม.', unitBasis: 'PER_SQM', rawUnitPrice: 115, currency: 'EUR', sqmPerUnit: null,
      ...itemOver,
    }],
  });
  const renderWithFx = async (user, factoryQuotes) => {
    api.fxRates = { list: vi.fn().mockResolvedValue({ fxRates }) };
    const req = request();
    renderDetailPage({ user, request: req, factoryQuotes });
    await waitForLoaded(req);
  };
  afterEach(() => { delete api.fxRates; });

  // The read-only item card (and its "ราคาจากฝ่ายนำเข้า" line) is gone for import; the baht
  // conversion sits under the price input in the factory table. The CEO still reads it on the item
  // card (second test).
  it('import: the price cell shows ≈ baht per ตร.ม. and the line total under the input, reusing quotedPriceInThb — no item card', async () => {
    await renderWithFx(importUser, [quoteWith()]);
    const thb = await screen.findByTestId('pcr-quote-thb-91-1');
    expect(thb.textContent).toContain('4,600');
    expect(thb.textContent).toContain('33,120');
    expect(screen.getByLabelText(/^ราคาที่เสนอ/).value).toBe('115');
    expect(screen.queryByTestId('pcr-import-price-1')).toBeNull();
    expect(screen.queryByTestId('pcr-import-thb-1')).toBeNull();
    expect(document.body.textContent).not.toContain('999');
    expect(document.body.textContent).not.toContain('preliminary');
  });

  it('ceo: the item card still shows the baht per ตร.ม., per แผ่น and in total for the requested quantity (unchanged)', async () => {
    await renderWithFx(ceoUser, [quoteWith()]);
    const thb = await screen.findByTestId('pcr-import-thb-1');
    expect(thb.textContent).toContain('4,600');
    expect(thb.textContent).toContain('6,624');
    expect(thb.textContent).toContain('33,120');
    expect(thb.textContent).toContain('5 แผ่น = 7.2 ตร.ม.');
    expect((await screen.findByTestId('pcr-import-price-1')).textContent).toContain('ราคาจากฝ่ายนำเข้า: 115 EUR/ตร.ม.');
  });

  it('import: no baht until a price is typed — the cell says ยังไม่กรอก and has no baht figure', async () => {
    await renderWithFx(importUser, [quoteWith({ rawUnitPrice: null })]);
    const cell = await screen.findByTestId('pcr-quote-price-cell-91-1');
    expect(cell.textContent).toContain('ยังไม่กรอก');
    expect(screen.queryByTestId('pcr-quote-thb-91-1')).toBeNull();
  });

  it('import: no baht for a per-box price; ceo item card keeps its per-box text; Sales has no FX access', async () => {
    await renderWithFx(importUser, [quoteWith({ unitBasis: 'PER_BOX' })]);
    expect(await screen.findByTestId('pcr-quote-price-cell-91-1')).not.toBeNull(); // positive control: the redesigned price cell
    expect((await screen.findByLabelText(/^ราคาที่เสนอ/)).value).toBe('115');
    expect(screen.queryByTestId('pcr-quote-thb-91-1')).toBeNull();
    cleanup();
    await renderWithFx(ceoUser, [quoteWith({ unitBasis: 'PER_BOX' })]);
    expect((await screen.findByTestId('pcr-import-price-1')).textContent).toContain('115 EUR/กล่อง');
    expect(screen.queryByTestId('pcr-import-thb-1')).toBeNull();
    cleanup();
    await renderWithFx(salesOwner, [quoteWith()]);
    expect(screen.queryByTestId('pcr-import-thb-1')).toBeNull();
    expect(screen.queryByTestId('pcr-quote-thb-91-1')).toBeNull();
  });
});

describe('PricingRequestDetailPage item card — V185 sales-entered fields', () => {
  it('shows the sales-entered tile fields read-only to every viewer', async () => {
    renderDetailPage({ user: salesOwner });
    await waitForLoaded();

    expect(screen.getByText('สี: ขาว')).not.toBeNull();
    expect(screen.getByText('ผิว: ด้าน')).not.toBeNull();
    expect(screen.getByText('ขนาด: 60x60')).not.toBeNull();
    expect(screen.getByText('ความหนา: 10 มม.')).not.toBeNull();
    // แผ่น/ตร.ม. and แผ่น/กล่อง are not on the Sales form, so they are not repeated here.
    expect(screen.queryByText(/^แผ่น\/ตร\.ม\.:/)).toBeNull();
    expect(screen.queryByText(/^แผ่น\/กล่อง:/)).toBeNull();
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

  // The item card is not rendered for import; its fields live in the factory table's spec row
  // (opened by the "สเปกจากฝ่ายขาย" toggle) and the จำนวน cell. Wastage stays hidden from import
  // there. The positive control (spec row opened, holding a sales-entered field) keeps the absence
  // assertions from passing on an empty page.
  it('hides เผื่อ (wastage) and the pre-wastage quantity from Import — in the opened spec row too; final order quantity stays in the จำนวน cell', async () => {
    renderDetailPage({ user: importUser });
    await waitForLoaded();

    fireEvent.click(await screen.findByRole('button', { name: /สเปกจากฝ่ายขาย/ }));
    const spec = within(screen.getByTestId('pcr-spec-1'));
    expect(specValue(spec, 'สี')).toBe('ขาว');
    expect(screen.queryByText(/เผื่อ \(wastage\)/)).toBeNull();
    expect(screen.queryByText(/จำนวนที่กรอก/)).toBeNull();
    expect(screen.getByTestId('pcr-factory-row-1').querySelector('[data-label="จำนวน"]').textContent).toContain('20 แผ่น');
  });

  it('shows the final order quantity (จำนวนสั่งซื้อ) on the item card to Sales and the CEO; Import reads it in the table (above)', async () => {
    for (const user of [salesOwner, ceoUser]) {
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
    // The legacy row's OWN requestedQty/requestedUnit (client-typed under the old form) still
    // renders in the final-order-quantity line; it never depended on the new columns.
    expect(screen.getByText('จำนวนสั่งซื้อ: 20 แผ่น')).not.toBeNull();
  });

  // The label is โรงงาน, but the VALUE shown is the sales-entered brand, captioned to distinguish
  // it from Import's own factory-assignment control just below (which also says โรงงาน; see
  // PricingRequestDetailPage.jsx).
  it('import: the spec row captions the sales-entered value "โรงงาน (ที่ฝ่ายขายกรอก)", never the old bare "Factory:" label', async () => {
    renderDetailPage({ user: importUser });
    await waitForLoaded();

    fireEvent.click(await screen.findByRole('button', { name: /สเปกจากฝ่ายขาย/ }));
    const spec = within(screen.getByTestId('pcr-spec-1'));
    expect(spec.getByText('โรงงาน (ที่ฝ่ายขายกรอก)')).not.toBeNull();
    expect(spec.queryByText(/^Factory/)).toBeNull();
    expect(spec.queryByText(/^ยี่ห้อ/)).toBeNull();
  });

  // A card whose sales rep left the โรงงาน-labelled brand blank must not fall back to Import's
  // ROUTING resolution (resolvedFactoryName/factory) under the "(ที่ฝ่ายขายกรอก)" caption, which
  // would misattribute an Import/catalog value to Sales. The two render as separate lines.
  it('import: the spec row shows the sales value; Import\'s resolved factory is the CARD heading — two separate places, never one falling back to the other', async () => {
    renderDetailPage({ user: importUser });
    await waitForLoaded();

    // The default fixture's item has a real `brand` ("SCG") AND a real `resolvedFactoryName`
    // ("SCG Ceramics") that DIFFER — proving neither is standing in for the other.
    fireEvent.click(await screen.findByRole('button', { name: /สเปกจากฝ่ายขาย/ }));
    expect(specValue(within(screen.getByTestId('pcr-spec-1')), 'โรงงาน (ที่ฝ่ายขายกรอก)')).toBe('SCG');
    expect(screen.getByText('SCG Ceramics')).not.toBeNull();
    expect(screen.queryByText(/^โรงงานที่กำหนด \(Import\)/)).toBeNull();
  });

  // Sales / CEO keep the item card's two separate lines exactly as before.
  it('sales and CEO still see the sales value and Import\'s resolved factory as two separate item-card lines', async () => {
    for (const user of [salesOwner, ceoUser]) {
      const { unmount } = renderDetailPage({ user });
      await waitForLoaded();
      expect(screen.getByText('โรงงาน (ที่ฝ่ายขายกรอก): SCG')).not.toBeNull();
      expect(screen.getByText('โรงงานที่กำหนด (Import): SCG Ceramics')).not.toBeNull();
      unmount();
    }
  });

  it('import: the spec row shows an em-dash (never Import\'s routing resolution) when Sales left the brand blank', async () => {
    const request = buildRequest({
      items: [{ ...buildRequest().items[0], brand: null }],
    });
    renderDetailPage({ user: importUser, request });
    await waitForLoaded();

    fireEvent.click(await screen.findByRole('button', { name: /สเปกจากฝ่ายขาย/ }));
    expect(specValue(within(screen.getByTestId('pcr-spec-1')), 'โรงงาน (ที่ฝ่ายขายกรอก)')).toBe('—');
  });

  // The amber "ยังไม่ได้ระบุ" routing line (Sales/CEO item card) is unchanged.
  it('sales: the item card still shows the sales value as em-dash and an amber "ยังไม่ได้ระบุ" routing line when nothing resolved a factory', async () => {
    const request = buildRequest({
      items: [{ ...buildRequest().items[0], brand: null, resolvedFactoryName: null, factory: null }],
    });
    renderDetailPage({ user: salesOwner, request });
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

  // Mutual exclusivity means offering BOTH create buttons on a new-form (CEO price-mode) request
  // invites a wasted click on the redundant OLD path: server-side it would either 409 (NEW
  // quotation exists) or block the NEW path from ever starting. A legacy decision
  // (newFormPricing false) has no such redundancy and keeps offering both.
  //
  // The fixture passes `newFormPricing` (the plain boolean PricingDecisionSalesViewDto exposes, so
  // the endpoint, also callable by import, never leaks which pricing method the CEO chose), the
  // REAL signal the component reads.
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

  // Clicking "เขียนใบเสนอราคาจากคำขอราคา" on a request whose ticket/PR carries no ผู้สั่งซื้อ
  // (recipientContactId) 409s server-side with a clear Thai message; the click must surface it via
  // showToast('error', ...) instead of showing no toast, no inline error, no navigation.
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

  // The NEW engine's quotation is displayed in this SAME "ใบเสนอราคาลูกค้า" panel (number/status/
  // link), and since one already exists neither create button is offered.
  it('shows the new-engine quotation (number, status, link) once one exists, and hides both create buttons', async () => {
    const request = buildRequest({ summary: { status: 'APPROVED_FOR_QUOTATION' } });
    api.dealQuotations.findForPricingRequest.mockResolvedValue({
      quotation: { id: 9001, number: 'QT-2026-0099-1', docStatus: 'PENDING_APPROVAL' },
    });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);

    await screen.findByText('QT-2026-0099-1');
    // The deal engine's own status words (PENDING_APPROVAL is not a customer-quotation status).
    expect(screen.getByText('รออนุมัติ')).not.toBeNull();
    expect(screen.queryByText(/PENDING_APPROVAL/)).toBeNull();
    const link = screen.getByRole('link', { name: 'เปิดใบเสนอราคา' });
    expect(link.getAttribute('href')).toBe('/quotations/9001');
    expect(screen.queryByRole('button', { name: 'สร้างร่างใบเสนอราคาลูกค้า' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'เขียนใบเสนอราคาจากคำขอราคา' })).toBeNull();
  });

  // Once the linked quotation EXPIRES, the owning sales rep gets a plain note + a button to write a
  // fresh one from the same approved decision (DealQuotationService#createFromPricingRequest
  // tolerates this: PR status QUOTATION_ISSUED, no live quotation).
  it('offers "เขียนใบเสนอราคาใหม่" once the linked quotation has EXPIRED, calling the same createFromPricingRequest endpoint', async () => {
    // The PR sits at QUOTATION_ISSUED once its quotation issued; canManageCustomerQuotation (not
    // canCreateCustomerQuotation) gates this button because APPROVED_FOR_QUOTATION no longer holds.
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
    expect(screen.getByText('ยังไม่มีใบเสนอราคาลูกค้า — ต้องรออนุมัติราคาขายก่อนจึงจะสร้างได้')).not.toBeNull();
    expect(screen.queryByText(/APPROVED_FOR_QUOTATION/)).toBeNull();
  });

  // A viewer who cannot create the quotation (the CEO) must not read "wait for
  // APPROVED_FOR_QUOTATION" forever (stale after approval, and a raw enum).
  it.each([
    ['CEO_REVIEWING', 'ยังไม่มีใบเสนอราคาลูกค้า — ต้องรออนุมัติราคาขายก่อนจึงจะสร้างได้'],
    ['APPROVED_FOR_QUOTATION', 'อนุมัติราคาขายแล้ว — รอฝ่ายขายสร้างใบเสนอราคาลูกค้า'],
    // A request that will never get a quotation says why, instead of "wait for approval".
    ['CANCELLED', 'ยังไม่มีใบเสนอราคาลูกค้า — คำขอราคานี้ยกเลิกแล้ว'],
    ['SUPERSEDED', 'ยังไม่มีใบเสนอราคาลูกค้า — คำขอราคานี้ถูกแทนที่แล้ว'],
  ])('tells the CEO the no-quotation state in words for %s, with no raw enum', async (status, text) => {
    const request = buildRequest({ summary: { status } });
    renderDetailPage({ user: ceoUser, request });
    await waitForLoaded(request);

    expect(await screen.findByText(text)).not.toBeNull();
    expect(screen.queryByText(/APPROVED_FOR_QUOTATION/)).toBeNull();
  });

  it('lets the owning sales rep edit an item discount, warns below the CEO-approved minimum, and issues via the confirm dialog', async () => {
    const request = buildRequest({ summary: { status: 'APPROVED_FOR_QUOTATION' } });
    const quotation = buildCustomerQuotation();
    api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [quotation] });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);
    await screen.findByText(quotation.number);

    // The quotation line reads "20 แผ่น", not the raw basis enum (the request-items list renders
    // one already; the quotation line is the second).
    expect(screen.getAllByText('20 แผ่น')).toHaveLength(2);
    expect(screen.queryByText(/PER_PIECE/)).toBeNull();

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

  // CEO discount-approval workflow.
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

// These stub api.dealQuotations.findForPricingRequest's RESOLVED VALUE directly (see
// newEngineQuotation()/mockResolvedValue below), so they do NOT catch a regression in the BACKEND
// query: reverting DealQuotationService#findForPricingRequest to a DRAFT-only reader leaves them
// green. That is covered by
// DealQuotationOutcomeIntegrationTest#findForPricingRequest_returnsTheQuotationPastDraft (real
// Postgres). These tests pin, per status, the same UI behaviour the legacy "Step 5" describe block
// above pins for the old engine, now for the new one.
describe('PricingRequestDetailPage GLA-123 slice S3: new-engine (dealQuotationForPr) outcome + expiry', () => {
  function newEngineQuotation(overrides = {}) {
    return { id: 9101, number: 'QT-2026-0101-1', docStatus: 'ISSUED', ...overrides };
  }

  // Every recipient asks first. The buyer's is the last party, so the question never mentions
  // skipping. A revision draft cancelled after แก้ใบเสนอราคา is the server's newest row; the page
  // still records on the quotation it revised.
  it('ISSUED, a buyer request: ลูกค้ายอมรับ asks first (no "ข้ามผู้รับรายถัดไป"), then records ACCEPTED via dealQuotations.recordOutcome, even behind a cancelled revision draft', async () => {
    const issued = newEngineQuotation({ docStatus: 'ISSUED', origin: 'PRICING_REQUEST', pricingRequestId: 501 });
    const cancelledRevision = newEngineQuotation({
      id: 9102, number: 'QT-2026-0101-2', docStatus: 'CANCELLED', origin: 'PRICING_REQUEST', pricingRequestId: 501, parentQuotationId: 9101,
    });
    const request = buildRequest({ summary: { recipientType: 'BUYER', recipientLabel: 'ผู้รับเหมา ก.' } });
    api.dealQuotations.findForPricingRequest.mockResolvedValue({ quotation: cancelledRevision });
    api.dealQuotations.listForTicket.mockResolvedValue({ items: [issued, cancelledRevision] });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);
    await screen.findByText(issued.number);

    // ปฏิเสธ/ขอแก้ไข take their reason from a dialog; ลูกค้ายอมรับ still sends the inline note.
    fireEvent.change(screen.getByPlaceholderText('หมายเหตุจากลูกค้า (ถ้ามี)'), { target: { value: 'ส่งของต้นเดือน' } });
    const acceptButton = await screen.findByRole('button', { name: 'ลูกค้ายอมรับ' });
    fireEvent.click(acceptButton);

    const dialog = within(await screen.findByRole('dialog', { name: 'ยืนยันว่าลูกค้ายอมรับใบเสนอราคานี้' }));
    expect(dialog.queryByText(/ข้ามผู้รับรายถัดไป/)).toBeNull();
    expect(dialog.getByText(/ยืนยันคำสั่งซื้อ/)).not.toBeNull();
    expect(api.dealQuotations.recordOutcome).not.toHaveBeenCalled();
    fireEvent.click(dialog.getByRole('button', { name: 'ยืนยัน' }));

    await waitFor(() => expect(api.dealQuotations.recordOutcome).toHaveBeenCalledWith(
      issued.id,
      expect.objectContaining({ outcome: 'ACCEPTED', customerNote: 'ส่งของต้นเดือน', clientRequestId: expect.any(String) }),
    ));
  });

  // Accepting a DESIGNER/OWNER quotation makes it the order and skips the later parties, so the
  // click asks first (the page's shared ConfirmDialog). The confirm button keeps ConfirmDialog's
  // default label, ยืนยัน (the spec names the title and body only).
  it('ISSUED, a designer request: ลูกค้ายอมรับ first asks "ยืนยันว่าลูกค้ายอมรับใบเสนอราคานี้" (ส่งถึงผู้ออกแบบ…); confirming records ACCEPTED', async () => {
    const issued = newEngineQuotation({ docStatus: 'ISSUED' });
    api.dealQuotations.findForPricingRequest.mockResolvedValue({ quotation: issued });
    renderDetailPage({ user: salesOwner }); // buildRequest(): recipientType DESIGNER
    await waitForLoaded();
    await screen.findByText(issued.number);

    fireEvent.click(await screen.findByRole('button', { name: 'ลูกค้ายอมรับ' }));

    const dialog = within(await screen.findByRole('dialog', { name: 'ยืนยันว่าลูกค้ายอมรับใบเสนอราคานี้' }));
    expect(dialog.getByText('ใบเสนอราคานี้ส่งถึงผู้ออกแบบ ถ้าลูกค้ายอมรับ ดีลจะใช้ใบนี้ยืนยันคำสั่งซื้อและข้ามผู้รับรายถัดไป')).not.toBeNull();
    expect(api.dealQuotations.recordOutcome).not.toHaveBeenCalled();
    fireEvent.click(dialog.getByRole('button', { name: 'ยืนยัน' }));

    await waitFor(() => expect(api.dealQuotations.recordOutcome).toHaveBeenCalledWith(
      issued.id,
      expect.objectContaining({ outcome: 'ACCEPTED', clientRequestId: expect.any(String) }),
    ));
  });

  it('ISSUED, an owner request: the same question names เจ้าของโครงการ, and ยกเลิก records nothing', async () => {
    const issued = newEngineQuotation({ docStatus: 'ISSUED' });
    const request = buildRequest({ summary: { recipientType: 'OWNER', recipientLabel: 'เจ้าของ ก.' } });
    api.dealQuotations.findForPricingRequest.mockResolvedValue({ quotation: issued });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);
    await screen.findByText(issued.number);

    fireEvent.click(await screen.findByRole('button', { name: 'ลูกค้ายอมรับ' }));

    const dialog = within(await screen.findByRole('dialog', { name: 'ยืนยันว่าลูกค้ายอมรับใบเสนอราคานี้' }));
    expect(dialog.getByText('ใบเสนอราคานี้ส่งถึงเจ้าของโครงการ ถ้าลูกค้ายอมรับ ดีลจะใช้ใบนี้ยืนยันคำสั่งซื้อและข้ามผู้รับรายถัดไป')).not.toBeNull();
    fireEvent.click(dialog.getByRole('button', { name: 'ยกเลิก' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    // A beat for any mutation the cancel might (wrongly) have started.
    await act(async () => { await new Promise((resolve) => { setTimeout(resolve, 30); }); });
    expect(api.dealQuotations.recordOutcome).not.toHaveBeenCalled();
  });

  // Spec E2: an outcome changes the deal page's sticky bar (route choice / confirm), so the request
  // page also marks the deal page's own lists stale. Seeded the way the deal page keys them: its
  // ticketId is the /tickets/:id route param (a string), and its quotation list key takes
  // Number(ticketId).
  it('recording an outcome also refreshes the deal page\'s pricing-request and quotation lists', async () => {
    const issued = newEngineQuotation({ docStatus: 'ISSUED' });
    api.dealQuotations.findForPricingRequest.mockResolvedValue({ quotation: issued });
    api.dealQuotations.recordOutcome.mockResolvedValue({ quotation: newEngineQuotation({ docStatus: 'REJECTED' }) });
    const { queryClient } = renderDetailPage({ user: salesOwner });
    const dealRequestsKey = queryKeys.pricingRequestsByTicket('701');
    const dealQuotationsKey = queryKeys.dealQuotationsByTicket(701);
    queryClient.setQueryData(dealRequestsKey, []);
    queryClient.setQueryData(dealQuotationsKey, []);
    await waitForLoaded();
    await screen.findByText(issued.number);

    // ปฏิเสธ asks for a reason first.
    fireEvent.click(await screen.findByRole('button', { name: 'ลูกค้าปฏิเสธ' }));
    const dialog = within(await screen.findByRole('dialog'));
    fireEvent.change(dialog.getByRole('textbox'), { target: { value: 'ลูกค้าไม่เอาแล้ว' } });
    fireEvent.click(dialog.getByRole('button', { name: 'ยืนยัน' }));
    await waitFor(() => expect(api.dealQuotations.recordOutcome).toHaveBeenCalled());

    await waitFor(() => {
      expect(queryClient.getQueryState(dealRequestsKey)?.isInvalidated).toBe(true);
      expect(queryClient.getQueryState(dealQuotationsKey)?.isInvalidated).toBe(true);
    });
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

  // The recreate gate must cover REVISION_REQUESTED too, not only docStatus === 'EXPIRED':
  // DealQuotationService#createFromPricingRequest / #hasLivePricingRequestQuotation treat it like
  // EXPIRED, and without it the owning rep hits a dead end (a read-only "ผลใบเสนอราคา" line with no
  // way to write the replacement the backend supports).
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

  // REJECTED is the other status the backend supports a recreate from (same
  // #hasLivePricingRequestQuotation non-live set).
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

  // A revision superseded this request. Its quotation may still read ISSUED or carry an earlier
  // outcome, but nothing on it is actionable any more.
  it('SUPERSEDED request: no outcome buttons on its ISSUED quotation, no recreate on a REJECTED one', async () => {
    const request = buildRequest({ summary: { status: 'SUPERSEDED' } });
    const issued = newEngineQuotation({ docStatus: 'ISSUED' });
    api.dealQuotations.findForPricingRequest.mockResolvedValue({ quotation: issued });
    const { unmount } = renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);
    await screen.findByText(issued.number);
    expect(screen.queryByRole('button', { name: 'ลูกค้ายอมรับ' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'ลูกค้าปฏิเสธ' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'ลูกค้าขอแก้ไข' })).toBeNull();
    unmount();

    const rejected = newEngineQuotation({ docStatus: 'REJECTED' });
    api.dealQuotations.findForPricingRequest.mockResolvedValue({ quotation: rejected });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);
    await screen.findByText(rejected.number);
    expect(screen.queryByRole('button', { name: 'เขียนใบเสนอราคาใหม่จากคำขอราคา' })).toBeNull();
  });

  // invalidate() must also invalidate queryKeys.dealQuotationForPricingRequest, the key backing
  // dealQuotationForPrQuery, which both the outcome panel and the recreate gate read.
  // ['pricingRequests','detail',id] does not prefix-match
  // ['pricingRequests','dealQuotationForPricingRequest',id], so otherwise a rep recording an
  // outcome got the success toast but the screen kept the stale ISSUED DTO (and the old outcome
  // buttons) until a reload or the 30s staleTime (api/queryClient.js). This proves the refetch
  // happens and the UI re-renders WITHOUT a remount: findForPricingRequest first resolves ISSUED,
  // then REVISION_REQUESTED on any later call; it watches (a) the call count rise after the
  // outcome is recorded, and (b) the recreate button become reachable while the old outcome
  // buttons disappear.
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
    // ขอแก้ไข asks for a reason first.
    const dialog = within(await screen.findByRole('dialog'));
    fireEvent.change(dialog.getByRole('textbox'), { target: { value: 'ลูกค้าขอเปลี่ยนราคา' } });
    fireEvent.click(dialog.getByRole('button', { name: 'ยืนยัน' }));

    await waitFor(() => expect(api.dealQuotations.recordOutcome).toHaveBeenCalledWith(
      issued.id,
      expect.objectContaining({ outcome: 'REVISION_REQUESTED', clientRequestId: expect.any(String) }),
    ));

    // Without invalidating dealQuotationForPricingRequest, this second call never fires and the
    // assertions below would time out against the stale ISSUED DTO.
    await waitFor(() => expect(api.dealQuotations.findForPricingRequest.mock.calls.length)
      .toBeGreaterThan(callsBeforeOutcome));

    expect(await screen.findByRole('button', { name: 'เขียนใบเสนอราคาใหม่จากคำขอราคา' })).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'ลูกค้ายอมรับ' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'ลูกค้าขอแก้ไข' })).toBeNull();
  });
});

describe('PricingRequestDetailPage Step 5: Customer Decision and Commercial Revisions', () => {
  // The old engine's ลูกค้ายอมรับ on a DESIGNER request (buildRequest's default) asks the same
  // question before recording.
  it('ISSUED (old engine), a designer request: ลูกค้ายอมรับ asks "ยืนยันว่าลูกค้ายอมรับใบเสนอราคานี้" first; confirming records ACCEPTED', async () => {
    const issued = buildCustomerQuotation({ docStatus: 'ISSUED' });
    api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [issued] });
    renderDetailPage({ user: salesOwner });
    await waitForLoaded();
    await screen.findByText(issued.number);

    fireEvent.click(await screen.findByRole('button', { name: 'ลูกค้ายอมรับ' }));

    const dialog = within(await screen.findByRole('dialog', { name: 'ยืนยันว่าลูกค้ายอมรับใบเสนอราคานี้' }));
    expect(dialog.getByText('ใบเสนอราคานี้ส่งถึงผู้ออกแบบ ถ้าลูกค้ายอมรับ ดีลจะใช้ใบนี้ยืนยันคำสั่งซื้อและข้ามผู้รับรายถัดไป')).not.toBeNull();
    fireEvent.click(dialog.getByRole('button', { name: 'ยืนยัน' }));

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

    // Commercial-only: reuses createRevision, reachable from REVISION_REQUESTED too.
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

// A request that was cancelled or superseded takes no more revisions (the server 409s them), so
// the legacy revision buttons are not offered (the new-engine recreate gate agrees).
describe('PricingRequestDetailPage legacy revision buttons on a dead request', () => {
  it.each(['SUPERSEDED', 'CANCELLED'])('%s request: no สร้างรอบแก้ไข* button on an ISSUED or REVISION_REQUESTED quotation', async (status) => {
    const request = buildRequest({ summary: { status } });
    const issued = buildCustomerQuotation({ docStatus: 'ISSUED' });
    api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [issued] });
    const first = renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);
    await screen.findByText(issued.number);
    expect(screen.queryByRole('button', { name: 'สร้างรอบแก้ไขใหม่' })).toBeNull();
    first.unmount();

    const revisionRequested = buildCustomerQuotation({ docStatus: 'REVISION_REQUESTED' });
    api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [revisionRequested] });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);
    await screen.findByText(revisionRequested.number);
    expect(screen.queryByRole('button', { name: 'สร้างรอบแก้ไขราคา/เงื่อนไข' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'สร้างรอบแก้ไขสินค้า/จำนวน/โรงงาน' })).toBeNull();
  });
});

// ลูกค้าปฏิเสธ / ลูกค้าขอแก้ไข are irreversible (the quotation leaves ISSUED), so both engines ask for
// a reason in the shared ConfirmDialog before recording. Each outcome owns its own clientRequestId:
// the backend replays a reused key as the FIRST outcome's success, so one shared key would let a
// failed ปฏิเสธ followed by ขอแก้ไข silently record nothing/the wrong thing.
describe.each([
  {
    engine: 'new engine (dealQuotations.recordOutcome)',
    recordMock: () => api.dealQuotations.recordOutcome,
    setup: () => {
      const issued = { id: 9101, number: 'QT-2026-0101-1', docStatus: 'ISSUED' };
      api.dealQuotations.findForPricingRequest.mockResolvedValue({ quotation: issued });
      api.dealQuotations.recordOutcome.mockResolvedValue({ quotation: { ...issued, docStatus: 'REJECTED' } });
      return issued;
    },
    // A different ISSUED quotation (B) becomes the request's current one.
    later: () => {
      const other = { id: 9102, number: 'QT-2026-0102-1', docStatus: 'ISSUED' };
      api.dealQuotations.findForPricingRequest.mockResolvedValue({ quotation: other });
      return other;
    },
  },
  {
    engine: 'legacy engine (pricingRequests.recordCustomerQuotationOutcome)',
    recordMock: () => api.pricingRequests.recordCustomerQuotationOutcome,
    setup: () => {
      const issued = buildCustomerQuotation({ docStatus: 'ISSUED' });
      api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [issued] });
      api.pricingRequests.recordCustomerQuotationOutcome.mockResolvedValue({ quotation: { ...issued, docStatus: 'REJECTED' } });
      return issued;
    },
    later: () => {
      const other = buildCustomerQuotation({ id: 5502, number: 'QT-2026-0002', docStatus: 'ISSUED' });
      api.pricingRequests.listCustomerQuotations.mockResolvedValue({ items: [other] });
      return other;
    },
  },
])('PricingRequestDetailPage W3-06/QA-35: ปฏิเสธ / ขอแก้ไข confirm with a reason — $engine', ({ recordMock, setup, later }) => {
  let page;
  async function renderIssued() {
    const issued = setup();
    page = renderDetailPage({ user: salesOwner });
    await waitForLoaded();
    await screen.findByText(issued.number);
    return issued;
  }

  // Click the outcome button; the API must not be touched until the dialog is confirmed.
  async function openDialog(buttonName) {
    const callsBefore = recordMock().mock.calls.length;
    fireEvent.click(await screen.findByRole('button', { name: buttonName }));
    expect(recordMock()).toHaveBeenCalledTimes(callsBefore);
    return within(await screen.findByRole('dialog'));
  }

  // Back out of the dialog if the failed call left it open (it should; the key tests don't depend on that).
  async function cancelDialog() {
    const open = screen.queryByRole('dialog');
    if (open) fireEvent.click(within(open).getByRole('button', { name: 'ยกเลิก' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  }

  // The failed call has settled (its error toast fired) -- only then is "the dialog is still open" meaningful.
  async function failureSettled() {
    await waitFor(() => expect(page.showToast).toHaveBeenCalledWith('error', 'network down'));
  }

  async function submit(dialog, reason) {
    fireEvent.change(dialog.getByRole('textbox'), { target: { value: reason } });
    fireEvent.click(dialog.getByRole('button', { name: 'ยืนยัน' }));
  }

  it.each([
    ['ลูกค้าปฏิเสธ', 'REJECTED'],
    ['ลูกค้าขอแก้ไข', 'REVISION_REQUESTED'],
  ])('%s opens a dialog, keeps ยืนยัน disabled until a non-blank reason, then records %s with customerNote = the reason', async (buttonName, outcome) => {
    const issued = await renderIssued();
    const dialog = await openDialog(buttonName);

    const confirm = dialog.getByRole('button', { name: 'ยืนยัน' });
    expect(confirm.disabled).toBe(true);
    fireEvent.change(dialog.getByRole('textbox'), { target: { value: '   ' } });
    expect(confirm.disabled).toBe(true);
    fireEvent.change(dialog.getByRole('textbox'), { target: { value: 'ราคาสูงกว่างบ' } });
    expect(confirm.disabled).toBe(false);
    fireEvent.click(confirm);

    await waitFor(() => expect(recordMock()).toHaveBeenCalledTimes(1));
    expect(recordMock()).toHaveBeenCalledWith(
      issued.id,
      expect.objectContaining({ outcome, customerNote: 'ราคาสูงกว่างบ', clientRequestId: expect.any(String) }),
    );
  });

  it.each(['ลูกค้าปฏิเสธ', 'ลูกค้าขอแก้ไข'])('%s: ยกเลิก in the dialog records nothing', async (buttonName) => {
    await renderIssued();
    const dialog = await openDialog(buttonName);
    fireEvent.change(dialog.getByRole('textbox'), { target: { value: 'พิมพ์แล้วแต่เปลี่ยนใจ' } });
    fireEvent.click(dialog.getByRole('button', { name: 'ยกเลิก' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await act(async () => { await new Promise((resolve) => { setTimeout(resolve, 30); }); });
    expect(recordMock()).not.toHaveBeenCalled();
  });

  it('each outcome has its own clientRequestId: after a FAILED ปฏิเสธ, ขอแก้ไข does not reuse its key', async () => {
    await renderIssued();
    recordMock().mockRejectedValueOnce(new Error('network down'));

    let dialog = await openDialog('ลูกค้าปฏิเสธ');
    await submit(dialog, 'เหตุผล ก');
    await waitFor(() => expect(recordMock()).toHaveBeenCalledTimes(1));
    const rejectKey = recordMock().mock.calls[0][1].clientRequestId;
    await failureSettled();
    await cancelDialog();

    dialog = await openDialog('ลูกค้าขอแก้ไข');
    await submit(dialog, 'เหตุผล ข');
    await waitFor(() => expect(recordMock()).toHaveBeenCalledTimes(2));
    const [, reviseBody] = recordMock().mock.calls[1];
    expect(reviseBody.outcome).toBe('REVISION_REQUESTED');
    expect(reviseBody.clientRequestId).not.toBe(rejectKey);
  });

  it('a FAILED outcome keeps the dialog open with the typed reason; ยืนยัน again retries with the SAME key and then closes it', async () => {
    await renderIssued();
    recordMock().mockRejectedValueOnce(new Error('network down'));

    const dialog = await openDialog('ลูกค้าปฏิเสธ');
    await submit(dialog, 'เหตุผล ก');
    await waitFor(() => expect(recordMock()).toHaveBeenCalledTimes(1));
    await failureSettled();

    const still = screen.queryByRole('dialog');
    expect(still, 'dialog must stay open after a failed record').not.toBeNull();
    expect(within(still).getByRole('textbox').value).toBe('เหตุผล ก');

    fireEvent.click(within(still).getByRole('button', { name: 'ยืนยัน' }));
    await waitFor(() => expect(recordMock()).toHaveBeenCalledTimes(2));
    const [first, second] = recordMock().mock.calls.map(([, body]) => body.clientRequestId);
    expect(second).toBe(first);
    expect(recordMock().mock.calls[1][1].customerNote).toBe('เหตุผล ก');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('a SUCCESSFUL outcome rotates the key, so the next recording is never replayed as the old one', async () => {
    await renderIssued();

    let dialog = await openDialog('ลูกค้าปฏิเสธ');
    await submit(dialog, 'เหตุผล ก');
    await waitFor(() => expect(recordMock()).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());

    dialog = await openDialog('ลูกค้าปฏิเสธ');
    await submit(dialog, 'เหตุผล ข');
    await waitFor(() => expect(recordMock()).toHaveBeenCalledTimes(2));
    const [first, second] = recordMock().mock.calls.map(([, body]) => body.clientRequestId);
    expect(second).not.toBe(first);
  });

  it('the key belongs to the quotation: a failed ปฏิเสธ on A, then a different ISSUED quotation B, sends B a fresh key (never A\'s)', async () => {
    const quotationA = await renderIssued();
    recordMock().mockRejectedValueOnce(new Error('network down'));

    let dialog = await openDialog('ลูกค้าปฏิเสธ');
    await submit(dialog, 'เหตุผล ก');
    await waitFor(() => expect(recordMock()).toHaveBeenCalledTimes(1));
    await failureSettled();
    await cancelDialog();

    const quotationB = later();
    await act(async () => { await page.queryClient.invalidateQueries(); });
    await screen.findByText(quotationB.number);

    dialog = await openDialog('ลูกค้าปฏิเสธ');
    await submit(dialog, 'เหตุผล ก');
    await waitFor(() => expect(recordMock()).toHaveBeenCalledTimes(2));
    const [[idA, bodyA], [idB, bodyB]] = recordMock().mock.calls;
    expect([idA, idB]).toEqual([quotationA.id, quotationB.id]);
    expect(bodyB.clientRequestId).not.toBe(bodyA.clientRequestId);
  });
});

describe('PricingRequestDetailPage Step 6: Deposit, Payment, and Order Confirmation', () => {
  // One deposit notice per deal. Once one is ISSUED the create action is gone (the backend 409s it)
  // and the block says which notice exists and where to open it.
  it('replaces "สร้างใบแจ้งยอดเงินรับมัดจำ" with a pointer to the deal\'s ISSUED notice', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({
      depositNotices: [{ id: 77, status: 'ISSUED', docNumber: 'GLRD69001', version: 1 }],
    });
    const request = buildRequest({ summary: { status: 'QUOTATION_ACCEPTED', orderConfirmedAt: '2026-07-21T00:00:00Z' } });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);

    const link = await screen.findByRole('link', { name: /GLRD69001/ });
    expect(link.getAttribute('href')).toBe(`/tickets/${request.summary.ticketId}/deposit`);
    expect(screen.getByText(/ออกใบแจ้งยอดเงินรับมัดจำแล้ว/)).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'สร้างใบแจ้งยอดเงินรับมัดจำ' })).toBeNull();
    expect(api.depositNotices.listByTicket).toHaveBeenCalledWith(request.summary.ticketId);
  });

  it('offers "ยืนยันคำสั่งซื้อ" to the owning sales rep once QUOTATION_ACCEPTED, before the bridge has run', async () => {
    const request = buildRequest({ summary: { status: 'QUOTATION_ACCEPTED', orderConfirmedAt: null } });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);

    const button = await screen.findByRole('button', { name: 'ยืนยันคำสั่งซื้อ' });
    expect(screen.queryByRole('button', { name: 'สร้างใบแจ้งยอดเงินรับมัดจำ' })).toBeNull();
    fireEvent.click(button);

    // The confirm is irreversible -> a dialog first; cancel sends nothing, confirm sends once.
    const dialog = await screen.findByRole('dialog', { name: 'ยืนยันคำสั่งซื้อ' });
    expect(api.pricingRequests.confirmOrder).not.toHaveBeenCalled();
    fireEvent.click(within(dialog).getByRole('button', { name: 'ยกเลิก' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'ยืนยันคำสั่งซื้อ' })).toBeNull());
    expect(api.pricingRequests.confirmOrder).not.toHaveBeenCalled();
    fireEvent.click(await screen.findByRole('button', { name: 'ยืนยันคำสั่งซื้อ' }));
    fireEvent.click(within(await screen.findByRole('dialog', { name: 'ยืนยันคำสั่งซื้อ' })).getByRole('button', { name: 'ยืนยันคำสั่งซื้อ' }));
    await waitFor(() => expect(api.pricingRequests.confirmOrder).toHaveBeenCalledTimes(1));
    expect(api.pricingRequests.confirmOrder).toHaveBeenCalledWith(
      request.summary.id,
      expect.objectContaining({ clientRequestId: expect.any(String) }),
    );
  });

  // The dialog names the quotation the page shows, not the PCR code ("ใบเสนอราคา PCR-…"), which is
  // not a quotation number.
  it('VW-04: the confirm dialog names the new-engine quotation number, never the PCR code', async () => {
    const request = buildRequest({ summary: { status: 'QUOTATION_ACCEPTED', orderConfirmedAt: null } });
    api.dealQuotations.findForPricingRequest.mockResolvedValue({
      quotation: { id: 9001, number: 'QT-2026-0099-1', docStatus: 'ACCEPTED', depositPercent: 0 },
    });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);
    await screen.findByText('QT-2026-0099-1');

    fireEvent.click(await screen.findByRole('button', { name: 'ยืนยันคำสั่งซื้อ' }));
    const dialog = await screen.findByRole('dialog', { name: 'ยืนยันคำสั่งซื้อ' });
    expect(dialog.textContent).toContain('ใบเสนอราคา QT-2026-0099-1');
    expect(dialog.textContent).toContain('ไม่เก็บมัดจำ'); // copy follows the quotation's 0%
    expect(dialog.textContent).not.toContain(request.summary.requestCode);
  });

  // The server confirms the PR's ACCEPTED new-engine quotation if any, else the legacy one; the deal page
  // shows a dead new-engine row's request through the live legacy quotation, and so must this dialog.
  it('VW-04: a dead new-engine row beside a live old-engine quotation names the old-engine number, never the PCR code', async () => {
    const request = buildRequest({ summary: { status: 'QUOTATION_ACCEPTED', orderConfirmedAt: null } });
    api.dealQuotations.findForPricingRequest.mockResolvedValue({
      quotation: { id: 9001, number: 'QT-2026-0099-1', docStatus: 'REJECTED' },
    });
    api.pricingRequests.listCustomerQuotations.mockResolvedValue({
      items: [buildCustomerQuotation({ number: 'QT-2026-0042', docStatus: 'ACCEPTED' })],
    });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);
    await screen.findByText('QT-2026-0099-1');

    fireEvent.click(await screen.findByRole('button', { name: 'ยืนยันคำสั่งซื้อ' }));
    const dialog = await screen.findByRole('dialog', { name: 'ยืนยันคำสั่งซื้อ' });
    await waitFor(() => expect(dialog.textContent).toContain('ใบเสนอราคา QT-2026-0042'));
    expect(dialog.textContent).not.toContain('QT-2026-0099-1');
    expect(dialog.textContent).not.toContain(request.summary.requestCode);
  });

  it('offers "สร้างใบแจ้งยอดเงินรับมัดจำ" (not the confirm button) once the bridge has already run', async () => {
    const request = buildRequest({ summary: { status: 'QUOTATION_ACCEPTED', orderConfirmedAt: '2026-07-21T00:00:00Z' } });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);

    expect(screen.queryByRole('button', { name: 'ยืนยันคำสั่งซื้อ' })).toBeNull();
    const button = await screen.findByRole('button', { name: 'สร้างใบแจ้งยอดเงินรับมัดจำ' });
    // No "% มัดจำ" input: the backend bills the accepted quotation's own percent and ignores a typed one.
    expect(screen.queryByLabelText('% มัดจำ')).toBeNull();
    fireEvent.click(button);

    await waitFor(() => expect(api.pricingRequests.createDepositNoticeFromQuotation).toHaveBeenCalledWith(
      request.summary.id, {},
    ));
  });

  // A ไม่รับมัดจำ (0%) accepted quotation has no deposit, and the server 409s the draft, so the
  // button is not offered. Waits for the quotation itself to render first, so the absence below is
  // not just "the query has not resolved yet".
  it('hides "สร้างใบแจ้งยอดเงินรับมัดจำ" when the accepted quotation says ไม่รับมัดจำ (0%)', async () => {
    const accepted = { id: 9102, number: 'QT-2026-0102-1', docStatus: 'ACCEPTED', depositPercent: 0 };
    api.dealQuotations.findForPricingRequest.mockResolvedValue({ quotation: accepted });
    const request = buildRequest({ summary: { status: 'QUOTATION_ACCEPTED', orderConfirmedAt: '2026-07-21T00:00:00Z' } });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);
    await screen.findByText(accepted.number);

    expect(screen.queryByRole('button', { name: 'สร้างใบแจ้งยอดเงินรับมัดจำ' })).toBeNull();
  });

  // One deposit notice per deal. Once one is ISSUED the create action is gone (the backend 409s it)
  // and the block says which notice exists and where to open it.
  it('replaces "สร้างใบแจ้งยอดเงินรับมัดจำ" with a pointer to the deal\'s ISSUED notice', async () => {
    api.depositNotices.listByTicket.mockResolvedValue({
      depositNotices: [{ id: 77, status: 'ISSUED', docNumber: 'GLRD69001', version: 1 }],
    });
    const request = buildRequest({ summary: { status: 'QUOTATION_ACCEPTED', orderConfirmedAt: '2026-07-21T00:00:00Z' } });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);

    const link = await screen.findByRole('link', { name: /GLRD69001/ });
    expect(link.getAttribute('href')).toBe(`/tickets/${request.summary.ticketId}/deposit`);
    expect(screen.getByText(/ออกใบแจ้งยอดเงินรับมัดจำแล้ว/)).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'สร้างใบแจ้งยอดเงินรับมัดจำ' })).toBeNull();
    expect(api.depositNotices.listByTicket).toHaveBeenCalledWith(request.summary.ticketId);
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
// A line submitted with no factory (valid) must not block สร้างร่างอีเมล with a 422 naming the
// row's PRIMARY KEY ("รายการที่ 35" on a two-line request) with no way to fix it. UI-LEVEL ONLY, per
// this file's header: who may write the field is proved by
// PricingFactoryQuoteCostingIntegrationTest#setItemFactory_* against real Postgres.
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

    // The warning is the callout at the TOP of the page.
    const warning = screen.getByTestId('pcr-import-unassigned');
    expect(warning.textContent).toContain('ยังไม่ได้ระบุโรงงาน 1 รายการ');
    // Position 2, not the row id — the number the reader can count on screen.
    expect(warning.textContent).toContain('รายการที่ 2 (กระเบื้องนำเข้าพิเศษ)');
    expect(warning.textContent).not.toContain('รายการที่ 1');
  });

  // The field is a picker over the real factory master list (SearchableCombobox), not a free-text
  // <input>: selecting an option sends the master row's factoryId, never a typed name. Opening the
  // combobox (focus) reveals every option because the query starts empty.
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

  // Pressing บันทึกโรงงาน with nothing picked must not be a silent no-op: the button stays
  // clickable and says what to do, and still does NOT fire a NaN factoryId at the endpoint.
  it('warns instead of calling the endpoint when บันทึกโรงงาน is pressed with no factory selected', async () => {
    const request = buildRequestWithBlankFactoryLine();
    renderDetailPage({ user: importUser, request });
    await waitForLoaded(request);

    fireEvent.click(screen.getByRole('button', { name: 'บันทึกโรงงาน' }));

    expect(screen.getByText('เลือกโรงงานจากรายการก่อนกดบันทึก')).toBeTruthy();
    expect(api.pricingRequests.setItemFactory).not.toHaveBeenCalled();
  });

  // Double-submit lock. Both clicks land inside ONE act(), so React cannot re-render between them:
  // `saving` (isPending) is still false and the button still enabled for the second click, exactly
  // the fast-double-click window. Only ImportFactoryPicker's inFlight ref can stop the repeat;
  // separate fireEvent.click calls would each flush a render and pass without the ref.
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

  // A line that already has a factory explains that changing means a new revision (the backend
  // refuses an in-place re-route), instead of leaving Import to guess why the picker vanished.
  it('tells Import ONCE (a section subtitle, not on every card) how to change a factory once one is set, without offering a re-route', async () => {
    const base = buildRequest();
    // two lines that already name a factory + one blank: a per-card note would print twice
    const request = buildRequest({ items: [
      base.items[0],
      { ...base.items[0], id: 3, model: 'A3', resolvedFactoryName: 'Cotto Industry' },
      { ...base.items[0], id: 2, brand: null, model: null, productDescription: 'กระเบื้องนำเข้าพิเศษ', resolvedFactoryName: null, factory: null },
    ] });
    renderDetailPage({ user: importUser, request });
    await waitForLoaded(request);

    expect(screen.getAllByText('เปลี่ยนโรงงานของรายการที่ระบุแล้วต้องสร้างคำขอราคารอบใหม่')).toHaveLength(1);
    expect(screen.queryByText(/ระบบล็อกไว้กันใบขอราคาที่จัดกลุ่มตามโรงงานเพี้ยน/)).toBeNull();
    expect(screen.queryByText(/^ต้องการเปลี่ยนโรงงาน\?/)).toBeNull();
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

  // Import may add a factory that genuinely doesn't exist yet, in-flow, rather than being stuck
  // typing a name the backend can never resolve. Reuses PriceImportPage's FactoryFormModal; this
  // proves the picker's wiring: create -> refetch the master list -> auto-select the new row for
  // this line.
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
    // The dialog closes and the line's picker shows the newly-created, auto-selected factory.
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
// Factory contact flow, locked terms, lead-time change.
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

// An item with no sqmPerPiece, so the ตร.ม./หน่วย box is asked of import (it is auto-filled otherwise).
function crRequestWithoutSqm() {
  const req = crRequest();
  return { ...req, items: req.items.map((item) => ({ ...item, sqmPerPiece: null })) };
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
  it('a not-yet-contacted factory shows step 1 as current with สร้างเมล + a primary ติดต่อโรงงานแล้ว, and none of the retired labels', async () => {
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [crQuote()] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    // Import PCR redesign: the chip is replaced by the 3-step indicator (ติดต่อโรงงาน is the current step).
    expect(stepStates(91)).toEqual(['now', 'todo', 'todo']);
    expect(screen.queryByTestId('pcr-factory-status-91')).toBeNull();
    expect(screen.getByRole('button', { name: 'สร้างเมล' })).not.toBeNull();
    expect(screen.getByRole('button', { name: 'ติดต่อโรงงานแล้ว' })).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'ร่างอีเมล' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'ดูอีเมล' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'ส่งแล้ว' })).toBeNull();
  });

  it('shows the read-only currency line "สกุลเงิน 🔒 EUR € · ตามคำขอราคา" and no currency / unit select for lines that carry terms (R1)', async () => {
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [contactedQuote()] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    const line = screen.getByTestId('pcr-factory-currency-91').textContent;
    expect(line).toContain('สกุลเงิน');
    expect(line).toContain('EUR €');
    expect(line).toContain('ตามคำขอราคา');
    expect(screen.queryByTestId('pcr-factory-terms-91')).toBeNull(); // the "ต่อ ตร.ม." strip is gone: the unit is per line now
    expect(screen.queryByRole('combobox', { name: /^สกุลเงิน/ })).toBeNull();
    expect(screen.queryByRole('combobox', { name: /^หน่วยราคา/ })).toBeNull();
  });

  it('a legacy line with no requested terms ALSO gets the read-only currency line — no currency or unit select', async () => {
    renderDetailPage({ user: importUser, request: buildRequest(), factoryQuotes: [buildFactoryQuote({ status: 'REQUESTED' })] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    expect(screen.queryByRole('combobox', { name: /^สกุลเงิน/ })).toBeNull();
    expect(screen.queryByRole('combobox', { name: /^หน่วยราคา/ })).toBeNull();
    expect(screen.getByTestId('pcr-factory-currency-91').textContent).toContain('THB ฿');
  });

  it('a not-yet-contacted factory disables every price input and says why', async () => {
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [crQuote()] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    expect(screen.getByLabelText(/^ราคาที่เสนอ/).disabled).toBe(true);
    // Import PCR redesign: the lock reason lives ONCE in the terms row, not under each input.
    expect(screen.getByTestId('pcr-await-contact-91').textContent).toContain('ช่องราคาจะเปิดหลังกด "ติดต่อโรงงานแล้ว"');
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

  // The factory card's ยกเลิก only discards unsaved price edits; with none it did nothing.
  it('QA-40: ยกเลิก appears only once a price has been edited', async () => {
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [contactedQuote({ status: 'READY_FOR_COSTING' })] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    expect(screen.queryByRole('button', { name: 'ยกเลิก' })).toBeNull();
    fireEvent.change(screen.getByLabelText(/^ราคาที่เสนอ/), { target: { value: '12.5' } });
    expect(screen.getByRole('button', { name: 'ยกเลิก' })).not.toBeNull();
  });

  // FactoryQuoteService#receive only takes prices while the REQUEST is IMPORT_REVIEWING /
  // AWAITING_FACTORY_RESPONSE / READY_FOR_CEO_REVIEW, so past that the grid is read-only: the
  // formatted price as text (like every other read-only cell), not a greyed input under an
  // "(แก้ไข)" caption.
  it.each(['QUOTATION_ISSUED', 'CANCELLED'])('QA-40: a %s request shows the factory prices as text, with no ยกเลิก or ยืนยันราคาเสนอ', async (status) => {
    renderDetailPage({
      user: importUser,
      request: crRequest({ summary: { status } }),
      factoryQuotes: [contactedQuote({ status: 'READY_FOR_COSTING', items: [{ ...crQuote().items[0], rawUnitPrice: 12.5 }] })],
    });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    expect(screen.queryByLabelText(/^ราคาที่เสนอ/)).toBeNull();
    // once as the offered price (now text), once in ราคาที่ยืนยันแล้ว
    expect(screen.getAllByText('12.5 EUR')).toHaveLength(2);
    expect(screen.queryByText('ราคาที่เสนอ (แก้ไข)', { selector: 'span.md\\:hidden' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'ยกเลิก' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'ยืนยันราคาเสนอ' })).toBeNull();
  });

  // An unsaved price typed before the request left the window must not show in the read-only grid
  // as if it were saved (nor linger with no ยกเลิก to discard it).
  it('QA-40: once the request leaves the response window, the read-only grid shows the saved price, not an unsaved edit', async () => {
    const quote = contactedQuote({ status: 'READY_FOR_COSTING', items: [{ ...crQuote().items[0], rawUnitPrice: 12.5 }] });
    const { queryClient } = renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [quote] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');
    fireEvent.change(screen.getByLabelText(/^ราคาที่เสนอ/), { target: { value: '99999' } });

    api.pricingRequests.get.mockResolvedValue({ pricingRequest: crRequest({ summary: { status: 'QUOTATION_ISSUED' } }) });
    await act(async () => { await queryClient.invalidateQueries(); });

    await waitFor(() => expect(screen.queryByLabelText(/^ราคาที่เสนอ/)).toBeNull());
    // once as the offered price (now text), once in ราคาที่ยืนยันแล้ว
    expect(screen.getAllByText('12.5 EUR')).toHaveLength(2);
    expect(screen.queryByText(/99,?999/)).toBeNull();
  });

  // After the deal is lost the request is CANCELLED: no mail to build or re-read.
  it('QA-40: a CANCELLED request offers no สร้างเมล', async () => {
    renderDetailPage({
      user: importUser,
      request: crRequest({ summary: { status: 'CANCELLED' } }),
      factoryQuotes: [contactedQuote()],
    });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    expect(screen.queryByTestId('pcr-open-email-draft-91')).toBeNull();
    expect(screen.queryByRole('button', { name: 'สร้างเมล' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'ดูเมลที่ส่ง' })).toBeNull();
  });

  it('a READY_FOR_COSTING factory reads as confirmed: steps done + the success line', async () => {
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [contactedQuote({ status: 'READY_FOR_COSTING' })] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');
    expect(stepStates(91)).toEqual(['done', 'done', 'done']);
    expect(screen.getByTestId('pcr-factory-confirmed-91').textContent).toContain('ยืนยันราคาเสนอแล้ว · 1 รายการ');
    // QA-29c: Import's confirmed price is not yet the CEO-approved one, so the column says
    // ราคาที่ยืนยันแล้ว, never ราคาที่อนุมัติ. (Redesign: one table header; the mobile row label is a
    // data-label attribute, not a second text node.)
    const table = within(screen.getByTestId('pcr-factory-card-91')).getByRole('table');
    expect(within(table).getByRole('columnheader', { name: 'ราคาที่ยืนยันแล้ว' })).not.toBeNull();
    expect(screen.getByTestId('pcr-factory-row-1').querySelector('[data-label="ราคาที่ยืนยันแล้ว"]')).not.toBeNull();
    expect(screen.queryAllByText('ราคาที่อนุมัติ')).toHaveLength(0);
  });

  it('the price payload carries the sales terms (EUR / PER_SQM) — import cannot change them', async () => {
    const quote = contactedQuote();
    renderDetailPage({ user: importUser, request: crRequestWithoutSqm(), factoryQuotes: [quote] });
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

  // Factories sell on different terms, so import may quote a per-ตร.ม. line per แผ่น and vice
  // versa; only that pair, and the currency stays Sales'.
  it('lets import switch a per-ตร.ม. line to per-แผ่น: placeholder follows, and unitBasis/quotedUnit are saved', async () => {
    const quote = contactedQuote();
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [quote] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    const price = screen.getByLabelText(/^ราคาที่เสนอ/);
    expect(price.getAttribute('placeholder')).toBe('ราคา/ตร.ม.');
    fireEvent.click(within(screen.getByRole('group', { name: /^หน่วยราคา รายการ/ })).getByRole('button', { name: '/ แผ่น' }));
    expect(screen.getByLabelText(/^ราคาที่เสนอ/).getAttribute('placeholder')).toBe('ราคา/แผ่น');
    expect(screen.queryByLabelText(/^ตร.ม.\/หน่วย/)).toBeNull();

    fireEvent.change(screen.getByLabelText(/^ราคาที่เสนอ/), { target: { value: '40' } });
    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));

    await waitFor(() => expect(api.pricingRequests.receiveFactoryQuote).toHaveBeenCalledWith(
      quote.id,
      expect.objectContaining({
        items: [expect.objectContaining({ currency: 'EUR', unitBasis: 'PER_PIECE', quotedUnit: 'แผ่น', rawUnitPrice: 40 })],
      }),
    ));
  });

  it('a LEGACY per-box / per-metre line adds that unit as a THIRD option; a per-ตร.ม. line offers exactly แผ่น / ตร.ม.', async () => {
    const buttons = () => within(screen.getByRole('group', { name: /^หน่วยราคา รายการ/ })).getAllByRole('button').map((b) => b.textContent.trim());
    for (const [basis, label] of [['PER_BOX', 'กล่อง'], ['PER_LINEAR_M', 'เมตร']]) {
      // legacy = no requestedPriceUnitBasis (the backend only locks the unit when Sales set one)
      const quote = buildFactoryQuote({ status: 'REQUESTED', items: [{ ...buildFactoryQuote().items[0], unitBasis: basis, quotedUnit: label }] });
      const request = buildRequest({ items: [{ ...buildRequest().items[0], requestedUnitBasis: basis }] });
      const { unmount } = renderDetailPage({ user: importUser, request, factoryQuotes: [quote] });
      await waitForLoaded();
      await screen.findByText('SCG Ceramics');
      expect(buttons()).toEqual(['/ แผ่น', '/ ตร.ม.', `/ ${label}`]);
      unmount();
    }
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [contactedQuote()] }); // per-ตร.ม. request
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');
    expect(buttons()).toEqual(['/ แผ่น', '/ ตร.ม.']);
  });

  // FactoryQuoteService only lets a Sales-LOCKED line switch ตร.ม.<->แผ่น; any other locked unit 409s.
  it.each([['PER_BOX', 'กล่อง'], ['PER_LINEAR_M', 'เมตร']])('a Sales-LOCKED %s line has NO unit switch: the unit is shown read-only and confirm keeps it', async (basis, label) => {
    const quote = contactedQuote({ items: [{ ...crQuote().items[0], unitBasis: basis, quotedUnit: label }] });
    renderDetailPage({ user: importUser, request: buildRequest({ items: [crItem({ requestedPriceUnitBasis: basis })] }), factoryQuotes: [quote] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    expect(screen.queryByRole('group', { name: /^หน่วยราคา รายการ/ })).toBeNull();
    expect(screen.getByTestId('pcr-quote-unit-91-1').textContent).toBe(`/ ${label}`);
    fireEvent.change(screen.getByLabelText(/^ราคาที่เสนอ/), { target: { value: '9' } });
    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));
    await waitFor(() => expect(api.pricingRequests.receiveFactoryQuote).toHaveBeenCalledWith(
      quote.id, expect.objectContaining({ items: [expect.objectContaining({ unitBasis: basis })] }),
    ));
  });

  it('auto-calculates the price in the other unit as import types: 100 EUR/ตร.ม. x 0.36 = 36 EUR/แผ่น, and back', async () => {
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [contactedQuote()] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    expect(screen.queryByTestId('pcr-quote-equiv-91-1')).toBeNull(); // nothing typed yet
    fireEvent.change(screen.getByLabelText(/^ราคาที่เสนอ/), { target: { value: '100' } });
    expect(screen.getByTestId('pcr-quote-equiv-91-1').textContent).toContain('36 EUR/แผ่น');

    fireEvent.click(within(screen.getByRole('group', { name: /^หน่วยราคา รายการ/ })).getByRole('button', { name: '/ แผ่น' }));
    fireEvent.change(screen.getByLabelText(/^ราคาที่เสนอ/), { target: { value: '36' } });
    expect(screen.getByTestId('pcr-quote-equiv-91-1').textContent).toContain('100 EUR/ตร.ม.');
  });

  // Factory workspace is import-only; the backend still authorises ceo on drafts and /contacted but no CEO screen uses it.
  it('CEO does NOT see the factory card, its locked terms, price inputs, confirm or lead-time change (it loads the quotes only to show Import\'s price on the item card)', async () => {
    renderDetailPage({ user: ceoUser, request: crRequest(), factoryQuotes: [contactedQuote()] });
    await waitForLoaded();

    expect(screen.queryByText('SCG Ceramics')).toBeNull();
    expect(screen.queryByTestId('pcr-factory-terms-91')).toBeNull();
    expect(screen.queryByLabelText(/^ราคาที่เสนอ/)).toBeNull();
    expect(screen.queryByRole('button', { name: 'ยืนยันราคาเสนอ' })).toBeNull();
    expect(screen.queryByTestId('pcr-lt-open-91')).toBeNull();
  });
});

describe('CR-1 ติดต่อโรงงานแล้ว dialog (R3, R5, R7)', () => {
  it('opens a small dialog with the date defaulting to today (Bangkok) and an optional note', async () => {
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [crQuote()] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    fireEvent.click(screen.getByRole('button', { name: 'ติดต่อโรงงานแล้ว' }));
    const dialog = await screen.findByRole('dialog', { name: 'ติดต่อโรงงานแล้ว' });
    const date = within(dialog).getByLabelText('วันที่ติดต่อ');
    const [y, m, d] = bangkokDay().split('-');
    expect(date.value).toBe(`${d}/${m}/${Number(y) + 543}`);
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

  it('refuses a future date: the field reverts to today with an error and the future date is never sent', async () => {
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [crQuote()] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    fireEvent.click(screen.getByRole('button', { name: 'ติดต่อโรงงานแล้ว' }));
    const dialog = await screen.findByRole('dialog', { name: 'ติดต่อโรงงานแล้ว' });
    fireEvent.change(within(dialog).getByLabelText('วันที่ติดต่อ'), { target: { value: bangkokDay(1) } });
    fireEvent.blur(within(dialog).getByLabelText('วันที่ติดต่อ'));
    const date = within(dialog).getByLabelText(/วันที่ติดต่อ/);
    const [y, m, d] = bangkokDay().split('-');
    expect(date.value).toBe(`${d}/${m}/${Number(y) + 543}`);
    expect(date.getAttribute('aria-invalid')).toBe('true');
    fireEvent.click(within(dialog).getByRole('button', { name: 'ยืนยัน' }));
    await waitFor(() => expect(api.pricingRequests.markFactoryQuoteContacted).toHaveBeenCalledWith(
      91, { contactedOn: bangkokDay() },
    ));
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

  // Factory workspace is import-only; the backend still authorises ceo on drafts and /contacted but no CEO screen uses it.
  it('the CEO gets neither สร้างเมล nor ติดต่อโรงงานแล้ว, and nothing is called', async () => {
    renderDetailPage({ user: ceoUser, request: crRequest(), factoryQuotes: [crQuote()] });
    await waitForLoaded();

    expect(screen.queryByText('SCG Ceramics')).toBeNull();
    expect(screen.queryByRole('button', { name: 'สร้างเมล' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'ติดต่อโรงงานแล้ว' })).toBeNull();
    expect(api.pricingRequests.markFactoryQuoteContacted).not.toHaveBeenCalled();
    expect(api.pricingRequests.generateFactoryEmailDrafts).not.toHaveBeenCalled();
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
    expect(stepStates(91)).toEqual(['now', 'todo', 'todo']);
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
    expect(within(dialog).getByLabelText('เลือกรายการ SCG A1').checked).toBe(true);
    expect(within(dialog).getByLabelText('เลือกรายการ SCG A2').checked).toBe(true);
    // the stock line is never offered (backend refuses it)
    expect(within(dialog).queryByLabelText('เลือกรายการ SCG S1')).toBeNull();
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
    fireEvent.change(within(dialog).getByLabelText('ต่ำสุด SCG A2'), { target: { value: '100' } });
    fireEvent.change(within(dialog).getByLabelText('สูงสุด SCG A2'), { target: { value: '110' } });
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
    fireEvent.click(within(dialog).getByLabelText('เลือกรายการ SCG A2'));
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
    expect(within(dialog).getByLabelText('ต่ำสุด SCG A1').value).toBe('120');
    fireEvent.change(within(dialog).getByLabelText('สูงสุด SCG A1'), { target: { value: '160' } });
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

  // Factory workspace is import-only; the backend still authorises ceo on drafts and /contacted but no CEO screen uses it.
  it('the CEO does NOT see the pending lead-time request (no badge, no decide controls)', async () => {
    renderDetailPage({ user: ceoUser, request: crRequest(), factoryQuotes: [contactedQuote()], leadTimeChanges: [ltChange()] });
    await waitForLoaded();

    expect(screen.queryByTestId('pcr-lt-pending-91')).toBeNull();
    expect(screen.queryByText(/รอฝ่ายขายอนุมัติ/)).toBeNull();
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
    expect(screen.queryByRole('button', { name: /สร้างใบ IR|ออกใบขอซื้อ|ออกเลข|ออกฉบับแก้ไข/ })).toBeNull();
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

// ── Browser-verification follow-ups ──────────────────────────────────────────────────────────
describe('CR-1 pre-draft preview card — contact / mail without the bulk สร้างเมลให้ทุกโรงงาน first (R3: สร้างเมล is optional)', () => {
  function renderPreview(user = importUser) {
    let generated = false;
    api.pricingRequests.generateFactoryEmailDrafts.mockImplementation(async () => {
      generated = true;
      return { items: [crQuote()] };
    });
    const utils = renderDetailPage({ user, request: crRequest() });
    api.pricingRequests.listFactoryQuotes.mockImplementation(async () => ({ items: generated ? [crQuote()] : [] }));
    return utils;
  }

  it('shows สร้างเมล and ติดต่อโรงงานแล้ว on the preview group of a named factory', async () => {
    renderPreview();
    await waitForLoaded();
    const group = await screen.findByTestId('pcr-price-preview-group');
    expect(within(group).getByRole('button', { name: 'สร้างเมล' })).not.toBeNull();
    expect(within(group).getByRole('button', { name: 'ติดต่อโรงงานแล้ว' })).not.toBeNull();
  });

  it('ติดต่อโรงงานแล้ว on the preview: generates the draft first, then marks THAT factory\'s new quote contacted', async () => {
    renderPreview();
    await waitForLoaded();
    const group = await screen.findByTestId('pcr-price-preview-group');
    fireEvent.click(within(group).getByRole('button', { name: 'ติดต่อโรงงานแล้ว' }));
    const dialog = await screen.findByRole('dialog', { name: 'ติดต่อโรงงานแล้ว' });
    fireEvent.change(within(dialog).getByLabelText(/หมายเหตุ/), { target: { value: 'โทรแล้ว' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'ยืนยัน' }));

    await waitFor(() => expect(api.pricingRequests.markFactoryQuoteContacted).toHaveBeenCalledWith(
      91, { contactedOn: bangkokDay(), note: 'โทรแล้ว' },
    ));
    expect(api.pricingRequests.generateFactoryEmailDrafts).toHaveBeenCalledTimes(1);
    expect(api.pricingRequests.generateFactoryEmailDrafts.mock.invocationCallOrder[0])
      .toBeLessThan(api.pricingRequests.markFactoryQuoteContacted.mock.invocationCallOrder[0]);
  });

  it('สร้างเมล on the preview: generates the draft, then opens that factory\'s mail modal', async () => {
    renderPreview();
    await waitForLoaded();
    const group = await screen.findByTestId('pcr-price-preview-group');
    fireEvent.click(within(group).getByRole('button', { name: 'สร้างเมล' }));
    expect(await screen.findByRole('dialog', { name: 'สร้างเมล' })).not.toBeNull();
    expect(api.pricingRequests.generateFactoryEmailDrafts).toHaveBeenCalledTimes(1);
    expect(api.pricingRequests.markFactoryQuoteContacted).not.toHaveBeenCalled();
  });

  // Factory workspace is import-only; the backend still authorises ceo on drafts and /contacted but no CEO screen uses it.
  it('the CEO gets no pre-draft preview card and no contact / mail buttons', async () => {
    renderPreview(ceoUser);
    await waitForLoaded();

    expect(screen.queryByTestId('pcr-price-preview-group')).toBeNull();
    expect(screen.queryByRole('button', { name: 'ติดต่อโรงงานแล้ว' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'สร้างเมล' })).toBeNull();
    expect(api.pricingRequests.generateFactoryEmailDrafts).not.toHaveBeenCalled();
  });

  it('no preview buttons outside the contact window (the server would 409)', async () => {
    renderDetailPage({ user: importUser, request: crRequest({ summary: { status: 'READY_FOR_CEO_REVIEW' } }) });
    await waitForLoaded();
    const group = await screen.findByTestId('pcr-price-preview-group');
    expect(within(group).queryByRole('button', { name: 'ติดต่อโรงงานแล้ว' })).toBeNull();
  });
});

describe('CR-1 unknown lead time reads ยังไม่ระบุ, never "-" / "- วัน"', () => {
  const unknownOld = (over = {}) => ltChange({
    lines: [{ pricingRequestItemId: 1, oldMinDays: null, oldMaxDays: null, newMinDays: 120, newMaxDays: 150 }], ...over,
  });
  const noLeadRequest = () => crRequest({ items: [crItem({ leadTimeMinDays: null, leadTimeMaxDays: null })] });

  it('banner, pending line and dialog all say ยังไม่ระบุ', async () => {
    renderDetailPage({ user: salesOwner, request: noLeadRequest(), leadTimeChanges: [unknownOld()] });
    await waitForLoaded();
    expect((await screen.findByTestId('pcr-lt-banner')).textContent).toContain('ยังไม่ระบุ → 120–150 วัน');
  });

  it('import: the line cell, pending cell and dialog', async () => {
    const { unmount } = renderDetailPage({ user: importUser, request: noLeadRequest(), factoryQuotes: [contactedQuote()], leadTimeChanges: [unknownOld()] });
    await waitForLoaded();
    expect((await screen.findByTestId('pcr-lt-value-91-1')).textContent).toContain('ยังไม่ระบุ → 120–150 วัน');
    unmount();
    renderDetailPage({ user: importUser, request: noLeadRequest(), factoryQuotes: [contactedQuote()] });
    await waitForLoaded();
    // Import PCR redesign: the column caption is a data-label attribute, no longer a text node inside the value.
    expect((await screen.findByTestId('pcr-lt-value-91-1')).textContent).toBe('ยังไม่ระบุ');
    fireEvent.click(screen.getByTestId('pcr-lt-open-91'));
    const dialog = await screen.findByRole('dialog', { name: 'ขอเปลี่ยนระยะเวลานำเข้า' });
    expect(dialog.textContent).toContain('ปัจจุบัน ยังไม่ระบุ');
    expect(dialog.textContent).not.toMatch(/ปัจจุบัน -/);
  });
});

describe('CR-1 factory section layout — nothing forces a horizontal scroll', () => {
  it('the item grid has no fixed minimum width (a min-w-[…] on it is what scrolled the whole section at 1100px)', async () => {
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [contactedQuote()] });
    await waitForLoaded();
    const card = await screen.findByTestId('pcr-factory-card-91');
    expect(card.querySelector('[class*="min-w-["]')).toBeNull();
    expect(card.parentElement.querySelector('[class*="md:min-w-["]')).toBeNull();
  });
});

// ── Opus review follow-ups ────────────────────────────────────────────────────────────────────
describe('CR-1 R1 payload precedence — the line\'s requested terms win over what the draft carries', () => {
  it('sends EUR / PER_SQM even when the draft items were seeded PER_PIECE / no currency (pre-fix backend seeding)', async () => {
    const base = crQuote();
    const quote = contactedQuote({
      defaultCurrency: 'THB',
      items: [{ ...base.items[0], unitBasis: 'PER_PIECE', quotedUnit: 'PER_PIECE', currency: null }],
    });
    renderDetailPage({ user: importUser, request: crRequestWithoutSqm(), factoryQuotes: [quote] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    fireEvent.change(screen.getByLabelText(/^ราคาที่เสนอ/), { target: { value: '12.5' } });
    fireEvent.change(screen.getByLabelText(/^ตร.ม.\/หน่วย/), { target: { value: '0.36' } });
    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));

    await waitFor(() => expect(api.pricingRequests.receiveFactoryQuote).toHaveBeenCalled());
    const payload = api.pricingRequests.receiveFactoryQuote.mock.calls[0][1];
    expect(payload.items[0]).toMatchObject({ currency: 'EUR', unitBasis: 'PER_SQM' });
    expect(payload.defaultCurrency).toBe('EUR');
  });
});

describe('CR-1 review copy / gating / a11y / provenance', () => {
  it('สร้างเมลให้ทุกโรงงาน is offered only inside the contact window', async () => {
    renderDetailPage({ user: importUser, request: crRequest() });
    await waitForLoaded();
    expect(await screen.findByTestId('pcr-generate-drafts')).not.toBeNull();
  });

  it('…and is hidden once the request is past it (generate would 409)', async () => {
    renderDetailPage({ user: importUser, request: crRequest({ summary: { status: 'READY_FOR_CEO_REVIEW' } }) });
    await waitForLoaded();
    await screen.findByTestId('pcr-price-preview');
    expect(screen.queryByTestId('pcr-generate-drafts')).toBeNull();
  });

  it('names the contactor from the legacy FACTORY_EMAIL_SENT event on a backfilled quote', async () => {
    const request = {
      ...crRequest(),
      events: [{ id: 2, actorId: 3, actorName: 'ฝ้าย', eventKind: 'FACTORY_EMAIL_SENT', message: null, createdAt: '2026-09-20T03:00:00Z' }],
    };
    renderDetailPage({ user: importUser, request, factoryQuotes: [contactedQuote()] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');
    expect(screen.getByTestId('pcr-factory-status-91').textContent).toContain('โดย ฝ้าย');
  });
});

// The CEO's pricing lives INSIDE "รายการสินค้าและราคาตั้งต้น" (one panel, the same "วิธีกรอกราคากระเบื้อง"
// picker as the direct-quotation editor), and NET mode has a typed ราคาตั้ง/แผ่น input beside
// ส่วนลด %. UI-level only: no authorization rule changes here, and
// PricingDecisionService#applyItemUpdates (which these payloads feed) is not exercised by this file.
describe('CEO pricing inside รายการสินค้าและราคาตั้งต้น (2026-10-01 redesign)', () => {
  const PANEL_TITLE = 'รายการสินค้าและราคาตั้งต้น';

  function newFormItem(overrides = {}) {
    return buildDecisionItem({ sqmPerPiece: 0.36, ...overrides });
  }

  async function renderCeo({ items, priceMode = 'NET', user = ceoUser } = {}) {
    const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
    api.pricingRequests.listPricingDecisions.mockResolvedValue({
      items: [buildDecision({ priceMode, items: items ?? [newFormItem({ listUnitPrice: 100, netUnitPrice: 100 })] })],
    });
    renderDetailPage({ user, request });
    await waitForLoaded(request);
    await screen.findByText('PCD-2026-0001');
  }

  function getPanel() {
    return screen.getByRole('heading', { level: 2, name: PANEL_TITLE }).closest('section');
  }

  function lastSavedItem() {
    const calls = api.pricingRequests.updatePricingDecision.mock.calls;
    return calls[calls.length - 1][1].items[0];
  }

  it('puts the "วิธีกรอกราคา" picker (aria-pressed) with all three modes inside the ตั้งราคา panel', async () => {
    await renderCeo({ priceMode: 'SPECIAL_SQM', items: [newFormItem()] });
    const panel = within(ceoPricingPanel());
    // the panel heading carries the decision code, version and the ก่อน VAT note
    const heading = panel.getByRole('heading', { level: 2, name: /^ตั้งราคา/ });
    expect(heading.textContent).toContain('PCD-2026-0001');
    expect(heading.textContent).toContain('เวอร์ชัน 1');
    expect(heading.textContent).toContain('ราคาก่อน VAT (ใบเสนอราคาบวก VAT 7%)');
    const group = within(panel.getByRole('group', { name: 'วิธีกรอกราคา' }));
    expect(group.getByRole('button', { name: 'ราคาตั้ง − ส่วนลด %' }).getAttribute('aria-pressed')).toBe('false');
    expect(group.getByRole('button', { name: 'ราคาพิเศษ/ตร.ม.' }).getAttribute('aria-pressed')).toBe('true');
    expect(group.getByRole('button', { name: 'ราคาสุทธิ/แผ่น' }).getAttribute('aria-pressed')).toBe('false');
    // the refresh / recalculate icon button stays in the panel
    expect(panel.getByTestId('pcr-ceo-recalculate-cost')).not.toBeNull();
  });

  it('does NOT render the factory-quote, ต้นทุนนำเข้า, CEO-decision, ภาพรวม or old รายการสินค้าและราคาตั้งต้น panels for the CEO (wrong-way-round)', async () => {
    await renderCeo();
    expect(screen.queryByRole('heading', { level: 2, name: /^รายการสินค้า \(\d+ รายการ\)$/ })).toBeNull();
    expect(screen.queryByRole('heading', { level: 2, name: 'ต้นทุนนำเข้า' })).toBeNull();
    expect(screen.queryByRole('heading', { level: 2, name: 'การพิจารณาราคาขายของ CEO' })).toBeNull();
    expect(screen.queryByRole('group', { name: 'วิธีกรอกราคากระเบื้อง' })).toBeNull();
    // The CEO header replaces a separate ภาพรวม panel; the pricing panel is ตั้งราคา
    expect(screen.queryByText('ภาพรวม')).toBeNull();
    expect(screen.queryByRole('heading', { level: 2, name: PANEL_TITLE })).toBeNull();
  });

  it('regression guard: Import still gets the factory section (ขอราคาจากโรงงาน)', async () => {
    const request = buildRequest({ summary: { status: 'IMPORT_REVIEWING' } });
    renderDetailPage({ user: importUser, request, factoryQuotes: [buildFactoryQuote()] });
    await waitForLoaded(request);
    // The section is headed "ขอราคาจากโรงงาน".
    expect(await screen.findByRole('heading', { level: 2, name: 'ขอราคาจากโรงงาน' })).not.toBeNull();
    expect(screen.queryByRole('heading', { level: 2, name: /^รายการสินค้า \(\d+ รายการ\)$/ })).toBeNull();
  });

  it('NET mode, STOCK line: ราคาตั้ง/แผ่น is a typed input prefilled with the saved list price, beside ส่วนลด %', async () => {
    await renderCeo({ items: [nfStock({ listUnitPrice: 100, netUnitPrice: 100 })] });
    const row = ceoRow(8001);
    expect(row.getByTestId('pcr-ceo-list-price-8001').value).toBe('100');
    expect(row.getByLabelText(/ราคาตั้ง\/แผ่น/)).not.toBeNull();
    expect(row.getByTestId('pcr-ceo-discount-8001')).not.toBeNull();
    // a stock line has no cost, so no margin % input
    expect(row.queryByTestId('pcr-ceo-margin-input-8001')).toBeNull();
  });

  it('NET mode, import line: the ราคาตั้ง input holds the override when there is one, else the list price (the formula stays shown under it)', async () => {
    await renderCeo({
      items: [newFormItem({ listUnitPrice: 100, manualSellingPricePerRequestedUnit: 150, netUnitPrice: 150 })],
    });
    expect(ceoRow(8001).getByTestId('pcr-ceo-list-price-8001').value).toBe('150');
    expect(ceoRow(8001).getByTestId('pcr-ceo-formula-list-8001').textContent).toContain('สูตร');
  });

  it('NET mode: typing discount 10 on list 100 previews ฿90.00 in the row (unsaved)', async () => {
    await renderCeo();
    fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-discount-8001'), { target: { value: '10' } });
    expect(ceoRow(8001).getByTestId('pcr-ceo-net-8001').textContent).toContain('90.00');
    expect(ceoRow(8001).getByText('ยังไม่บันทึก')).not.toBeNull();
  });

  it('NET mode, STOCK line: typing a new ราคาตั้ง 200 with discount 10 previews ฿180.00', async () => {
    await renderCeo({ items: [nfStock({ listUnitPrice: 100, netUnitPrice: 100 })] });
    fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-list-price-8001'), { target: { value: '200' } });
    fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-discount-8001'), { target: { value: '10' } });
    expect(ceoRow(8001).getByTestId('pcr-ceo-net-8001').textContent).toContain('180.00');
  });

  it('IMPORT line: saving a changed ราคาตั้ง sends sellingPriceOverride + a non-blank decisionNote, never listUnitPrice', async () => {
    await renderCeo({ items: [newFormItem({ stockSource: null, listUnitPrice: 100, netUnitPrice: 100 })] });
    fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-list-price-8001'), { target: { value: '200' } });
    fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-discount-8001'), { target: { value: '10' } });
    fireEvent.click(saveAllButton());

    await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalled());
    const sent = lastSavedItem();
    expect(sent.sellingPriceOverride).toBe(200);
    expect(sent.decisionNote).toMatch(/\S/);
    expect(sent.discountPct).toBe(10);
    expect(sent).not.toHaveProperty('listUnitPrice');
  });

  it('IMPORT line with an override: blanking ราคาตั้ง and saving clears the override', async () => {
    await renderCeo({
      items: [newFormItem({ manualSellingPricePerRequestedUnit: 150, listUnitPrice: 100, netUnitPrice: 150 })],
    });
    fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-list-price-8001'), { target: { value: '' } });
    fireEvent.click(saveAllButton());

    await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalled());
    expect(lastSavedItem().clearSellingPriceOverride).toBe(true);
    expect(lastSavedItem().decisionNote).toMatch(/\S/);
  });

  it('saving after changing ONLY the discount sends no sellingPriceOverride', async () => {
    await renderCeo();
    fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-discount-8001'), { target: { value: '10' } });
    fireEvent.click(saveAllButton());

    await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalled());
    const sent = lastSavedItem();
    expect(sent.discountPct).toBe(10);
    expect(sent.sellingPriceOverride == null).toBe(true);
    expect(sent.clearSellingPriceOverride).toBe(false);
  });

  it('STOCK line: ราคาตั้ง starts blank and saving 150 sends listUnitPrice 150, no sellingPriceOverride', async () => {
    // Realistic stock line: no frozen landed cost (stock lines have none).
    await renderCeo({ items: [nfStock()] });
    const input = ceoRow(8001).getByTestId('pcr-ceo-list-price-8001');
    expect(input.value).toBe('');
    fireEvent.change(input, { target: { value: '150' } });
    fireEvent.click(saveAllButton());

    await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalled());
    const sent = lastSavedItem();
    expect(sent.listUnitPrice).toBe(150);
    expect(sent.sellingPriceOverride == null).toBe(true);
  });

  it('SPECIAL_SQM mode: only the ราคาพิเศษ บาท/ตร.ม. (รวม VAT) input in the row; no ราคาตั้ง/ส่วนลด', async () => {
    await renderCeo({ priceMode: 'SPECIAL_SQM', items: [newFormItem()] });
    const row = ceoRow(8001);
    expect(row.getByTestId('pcr-ceo-special-sqm-8001')).not.toBeNull();
    expect(row.getByLabelText(/ราคาพิเศษ.*รวม VAT/)).not.toBeNull();
    expect(row.queryByTestId('pcr-ceo-list-price-8001')).toBeNull();
    expect(row.queryByTestId('pcr-ceo-discount-8001')).toBeNull();
    // The hint is the แผ่น/ตร.ม. figure the net is divided by (round2(1 / 0.36) = 2.78), the same
    // one the quotation editor shows, never the raw ตร.ม./แผ่น 0.36.
    expect(row.getByText(/2\.78 แผ่น\/ตร\.ม\./)).not.toBeNull();
    expect(row.queryByText(/ตร\.ม\.\/แผ่น 0\.36/)).toBeNull();
  });

  // Under ราคาพิเศษ/ตร.ม. and ราคาสุทธิ the quotation prints the net as its ราคา, so a stock line
  // needs no ราคาตั้ง there: no field, and approve is not blocked on it.
  it.each([
    ['SPECIAL_SQM', { specialPriceSqm: 7000 }],
    ['DIRECT_NET', { directNetPrice: 399.15 }],
  ])('W5-03: %s — a priced STOCK line has no ราคาตั้ง field and does not block อนุมัติราคาขาย', async (priceMode, price) => {
    await renderCeo({ priceMode, items: [nfStock({ netUnitPrice: 399.15, ...price })] });
    expect(ceoRow(8001).queryByTestId('pcr-ceo-list-price-8001')).toBeNull();
    expect(screen.queryByText(/ต้องใส่ราคาตั้ง/)).toBeNull();
    expect(screen.queryByTestId('pcr-ceo-blockers')).toBeNull();
    expect(screen.getByTestId('pcr-ceo-approve').disabled).toBe(false);
  });

  it('W5-03: NET — a STOCK line without ราคาตั้ง still blocks อนุมัติราคาขาย, and the blocker line says why', async () => {
    await renderCeo({ priceMode: 'NET', items: [nfStock()] });
    expect(ceoRow(8001).getByTestId('pcr-ceo-list-price-8001')).not.toBeNull();
    expect(screen.getByTestId('pcr-ceo-approve').disabled).toBe(true);
    expect(within(screen.getByTestId('pcr-ceo-blockers')).getByRole('button', {
      name: 'รายการที่ 1 SCG A1 — ต้องใส่ราคาตั้ง (สต็อก)',
    })).not.toBeNull();
  });

  // The formula price (CEO config: cost x (1 + margin) x buffer) pre-fills ราคาพิเศษ, and ต้นทุน
  // reads per แผ่น AND per ตร.ม., so the CEO need not work these out.
  describe('auto-calculated prices and ต้นทุน in both units', () => {
    it('pre-fills ราคาพิเศษ บาท/ตร.ม. (รวม VAT) from the formula price: 72/แผ่น at 0.36 ตร.ม. -> 72 x 2.78 = 200.16 (the formula already carries the 1.07 — never applied twice), and saves it', async () => {
      await renderCeo({ priceMode: 'SPECIAL_SQM', items: [newFormItem()] }); // formula 72, sqmPerPiece 0.36
      expect(ceoRow(8001).getByTestId('pcr-ceo-special-sqm-8001').value).toBe('200.16');
      // the pre-fill is an unsaved draft: the row says so (with its own tag) and the one save offers it
      expect(ceoRow(8001).getByText('ใช้ราคาตามสูตร — ยังไม่บันทึก')).not.toBeNull();
      expect(saveAllButton().textContent).toContain('บันทึกราคา (1 รายการ)');

      fireEvent.click(saveAllButton());
      await waitFor(() => expect(lastSavedItem()).toEqual(expect.objectContaining({ specialPriceSqm: 200.16 })));
    });

    it('lets the CEO type over the pre-fill, and never replaces a saved ราคาพิเศษ', async () => {
      await renderCeo({ priceMode: 'SPECIAL_SQM', items: [newFormItem()] });
      fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-special-sqm-8001'), { target: { value: '1350' } });
      expect(ceoRow(8001).getByTestId('pcr-ceo-special-sqm-8001').value).toBe('1350');
      cleanup();
      await renderCeo({ priceMode: 'SPECIAL_SQM', items: [newFormItem({ specialPriceSqm: 1350, netUnitPrice: 453.84 })] });
      expect(ceoRow(8001).getByTestId('pcr-ceo-special-sqm-8001').value).toBe('1350');
      // a saved value is not an unsaved draft: no row flag, no save button
      expect(ceoRow(8001).queryByText('ยังไม่บันทึก')).toBeNull();
      expect(screen.queryByTestId('pcr-ceo-save-all')).toBeNull();
    });

    it('pre-fills nothing without a formula price or without ตร.ม./แผ่น', async () => {
      await renderCeo({ priceMode: 'SPECIAL_SQM', items: [newFormItem({ proposedSellingPricePerRequestedUnit: null })] });
      expect(ceoRow(8001).getByTestId('pcr-ceo-special-sqm-8001').value).toBe('');
      expect(screen.queryByTestId('pcr-ceo-save-all')).toBeNull();
    });

    it('shows the formula ราคาตั้ง/แผ่น under ราคาพิเศษ too (the ราคาตั้ง (สูตร) column, not only under NET)', async () => {
      await renderCeo({ priceMode: 'SPECIAL_SQM', items: [newFormItem()] });
      expect(ceoRow(8001).getByTestId('pcr-ceo-formula-list-8001').textContent).toContain('72');
    });

    it('shows ต้นทุน per แผ่น and per ตร.ม. up front in the row (60 / 0.36 = 166.67), without opening รายละเอียด; "ไม่มีต้นทุน (สต็อก)" for a stock line', async () => {
      await renderCeo({ priceMode: 'NET', items: [newFormItem({ listUnitPrice: 72, netUnitPrice: 72 })] });
      const cost = ceoRow(8001).getByTestId('pcr-ceo-cost-both-8001').textContent;
      expect(cost).toContain('฿60.00');
      expect(cost).toContain('฿166.67/ตร.ม.');
      cleanup();
      await renderCeo({ priceMode: 'NET', items: [nfStock({ listUnitPrice: 150, netUnitPrice: 150 })] });
      const stockCost = ceoRow(8001).getByTestId('pcr-ceo-cost-both-8001').textContent;
      expect(stockCost).toContain('ไม่มีต้นทุน (สต็อก)');
      expect(stockCost).not.toContain('ตร.ม.');
    });
  });

  it('DIRECT_NET mode: only the ราคาสุทธิ/แผ่น input in the row; no ราคาตั้ง/ส่วนลด', async () => {
    await renderCeo({ priceMode: 'DIRECT_NET', items: [newFormItem({ directNetPrice: 100, netUnitPrice: 100 })] });
    const row = ceoRow(8001);
    expect(row.getByTestId('pcr-ceo-direct-net-8001')).not.toBeNull();
    expect(row.queryByTestId('pcr-ceo-list-price-8001')).toBeNull();
    expect(row.queryByTestId('pcr-ceo-discount-8001')).toBeNull();
  });

  it('NET mode: the separate ปรับราคาตั้งเอง button is gone (replaced by the input)', async () => {
    await renderCeo();
    expect(screen.queryByTestId('pcr-ceo-price-override-8001')).toBeNull();
  });

  it('อนุมัติราคาขาย and ตีกลับฝ่ายนำเข้า live in the summary bar (not in the pricing panel)', async () => {
    await renderCeo();
    const summary = ceoSummary();
    expect(summary.getByTestId('pcr-ceo-approve')).not.toBeNull();
    expect(summary.getByRole('button', { name: 'ตีกลับฝ่ายนำเข้า' })).not.toBeNull();
    expect(within(ceoPricingPanel()).queryByTestId('pcr-ceo-approve')).toBeNull();
  });

  it('approve stays disabled while any item has netUnitPrice null (summary bar)', async () => {
    await renderCeo({
      items: [newFormItem({ id: 8001, listUnitPrice: 100, netUnitPrice: null }), newFormItem({ id: 8002, netUnitPrice: 900 })],
    });
    expect(ceoSummary().getByTestId('pcr-ceo-approve').disabled).toBe(true);
  });

  it('IMPORT line with an active override: ปรับเอง + "ใช้ราคาตามสูตร" clears it with clearSellingPriceOverride and the same note', async () => {
    await renderCeo({
      items: [newFormItem({ manualSellingPricePerRequestedUnit: 150, listUnitPrice: 100, netUnitPrice: 150 })],
    });
    const row = ceoRow(8001);
    expect(row.getAllByText('ปรับเอง').length).toBeGreaterThan(0);
    fireEvent.click(row.getByTestId('pcr-ceo-use-formula-price-8001'));

    await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalled());
    const sent = lastSavedItem();
    expect(sent.clearSellingPriceOverride).toBe(true);
    expect(sent.decisionNote).toMatch(/\S/);
    expect(sent.sellingPriceOverride == null).toBe(true);
  });

  it('lists every decision item as a row keyed by pricingRequestItemId (named from its request item), and renders a decision item that matches no request item after the rows', async () => {
    await renderCeo({
      items: [
        newFormItem({ id: 8002, pricingRequestItemId: 999, listUnitPrice: 222, netUnitPrice: 222 }),
        newFormItem({ id: 8001, pricingRequestItemId: 1, listUnitPrice: 100, netUnitPrice: 100 }),
      ],
    });
    // rows follow the REQUEST's item order; the unmatched decision item comes after, never dropped
    const rowIds = ceoTable().getAllByTestId(/^pcr-ceo-row-/).map((r) => r.getAttribute('data-testid'));
    expect(rowIds).toEqual(['pcr-ceo-row-8001', 'pcr-ceo-row-8002']);
    expect(ceoRow(8001).getByTestId('pcr-ceo-list-price-8001').value).toBe('100');
    expect(ceoRow(8002).getByTestId('pcr-ceo-list-price-8002').value).toBe('222');
    expect(ceoRow(8001).queryByTestId('pcr-ceo-list-price-8002')).toBeNull();
  });

  describe('review fix round (Opus nits, 2026-10-01)', () => {
    async function renderCeoWithCosting({ items, priceMode = 'NET' }) {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      const costingItem = buildCostingItemWithOverride({ id: 1 });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({ items: [buildDecision({ priceMode, items })] });
      renderDetailPage({ user: ceoUser, request, costings: [buildCosting({ id: 601, items: [costingItem] })] });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');
    }

    it('M1: a realistic stock line (no frozen cost) does not block approve and offers no cost controls anywhere in its row or รายละเอียด', async () => {
      await renderCeoWithCosting({
        items: [newFormItem({
          stockSource: 'IN_THAILAND', pricingCostingItemId: 1, frozenLandedCostPerRequestedUnitThb: null,
          listUnitPrice: 150, netUnitPrice: 150,
        })],
      });
      // Java exempts stock lines from the cost gate (PricingDecisionService#approve) ...
      expect(screen.getByTestId('pcr-ceo-approve').disabled).toBe(false);
      expect(screen.queryByText(/ทุกรายการต้องมีต้นทุนก่อนอนุมัติ/)).toBeNull();
      expect(screen.queryByTestId('pcr-ceo-blockers')).toBeNull();
      // ... and 400s a cost override on one, so no control may render, even with รายละเอียด open.
      openRowDetails('SCG A1');
      expect(ceoTable().queryByTestId('pcr-ceo-cost-override-8001')).toBeNull();
      expect(ceoTable().queryByRole('button', { name: 'ปรับต้นทุนเอง' })).toBeNull();
      expect(ceoTable().queryByTestId('pcr-ceo-product-type-override-8001')).toBeNull();
    });

    it('L1: an uncosted import line with an override still blocks approve outside NET (DIRECT_NET)', async () => {
      await renderCeo({
        priceMode: 'DIRECT_NET',
        items: [newFormItem({
          frozenLandedCostPerRequestedUnitThb: null, manualSellingPricePerRequestedUnit: 300,
          directNetPrice: 100, netUnitPrice: 100,
        })],
      });
      // Java: the override clears the uncosted gate only for priceMode null/NET.
      expect(ceoSummary().getByTestId('pcr-ceo-approve').disabled).toBe(true);
      expect(within(screen.getByTestId('pcr-ceo-blockers')).getByRole('button', {
        name: 'รายการที่ 1 SCG A1 — ต้องระบุต้นทุนเอง',
      })).not.toBeNull();
    });

    it('L1 control: the same uncosted line with an override under NET is allowed through', async () => {
      await renderCeo({
        priceMode: 'NET',
        items: [newFormItem({
          frozenLandedCostPerRequestedUnitThb: null, manualSellingPricePerRequestedUnit: 300, netUnitPrice: 300,
        })],
      });
      expect(ceoSummary().getByTestId('pcr-ceo-approve').disabled).toBe(false);
      expect(screen.queryByTestId('pcr-ceo-blockers')).toBeNull();
    });

    it('L2a: ล้างส่วนลด sends ONLY the discount clear — a pending ราคาตั้ง draft is not committed and stays in the input', async () => {
      // ราคาตั้ง is a typed draft on a STOCK line only
      await renderCeo({ items: [nfStock({ listUnitPrice: 100, discountPct: 5, netUnitPrice: 95 })] });
      fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-list-price-8001'), { target: { value: '200' } });
      fireEvent.click(ceoRow(8001).getByTestId('pcr-ceo-clear-discount-8001'));

      await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalled());
      const sent = lastSavedItem();
      expect(sent.clearDiscountPct).toBe(true);
      expect(sent.sellingPriceOverride == null).toBe(true);
      expect(sent.listUnitPrice == null).toBe(true);
      expect(sent.decisionNote == null).toBe(true);
      // The unsent ราคาตั้ง draft is kept locally.
      await waitFor(() => expect(api.pricingRequests.listPricingDecisions.mock.calls.length).toBeGreaterThan(1));
      expect(ceoRow(8001).getByTestId('pcr-ceo-list-price-8001').value).toBe('200');
    });

    it('L2b: a confirmed price-mode switch discards the per-item price drafts', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      let mode = 'NET';
      api.pricingRequests.listPricingDecisions.mockImplementation(async () => ({
        items: [buildDecision({ priceMode: mode, items: [newFormItem({ listUnitPrice: 100, netUnitPrice: 100 })] })],
      }));
      api.pricingRequests.updatePricingDecision.mockImplementation(async (_id, payload) => {
        if (payload.priceMode) mode = payload.priceMode;
        return {};
      });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');

      fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-discount-8001'), { target: { value: '10' } });
      expect(saveAllButton().disabled).toBe(false);

      fireEvent.click(screen.getByTestId('pcr-ceo-price-mode-DIRECT_NET'));
      const dialog = await screen.findByRole('dialog', { name: 'เปลี่ยนวิธีกรอกราคา' });
      fireEvent.click(within(dialog).getByRole('button', { name: 'เปลี่ยนวิธีกรอกราคา' }));

      await waitFor(() => expect(ceoRow(8001).getByTestId('pcr-ceo-direct-net-8001')).not.toBeNull());
      // The NET discount draft must not linger as a phantom pending edit under the new mode.
      expect(screen.queryByTestId('pcr-ceo-save-all')).toBeNull();
      expect(ceoRow(8001).queryByText('ยังไม่บันทึก')).toBeNull();
    });

    it('M2: a ราคาตั้ง of 0 or below on a stock line shows an inline error and keeps the one save disabled; blank leaves nothing to save', async () => {
      await renderCeo({ items: [nfStock({ listUnitPrice: 100, netUnitPrice: 100 })] });
      const input = () => ceoRow(8001).getByTestId('pcr-ceo-list-price-8001');
      fireEvent.change(input(), { target: { value: '0' } });
      expect(ceoRow(8001).getByText('ราคาตั้งต้องมากกว่า 0')).not.toBeNull();
      expect(saveAllButton().disabled).toBe(true);
      fireEvent.change(input(), { target: { value: '-5' } });
      expect(ceoRow(8001).getByText('ราคาตั้งต้องมากกว่า 0')).not.toBeNull();
      expect(saveAllButton().disabled).toBe(true);

      // blank on a stock line: Java needs > 0, so there is nothing to save and no error either
      fireEvent.change(input(), { target: { value: '' } });
      expect(ceoRow(8001).queryByText('ราคาตั้งต้องมากกว่า 0')).toBeNull();
      expect(screen.queryByTestId('pcr-ceo-save-all')).toBeNull();
    });

    it('L3: a new-form missing-cost line is named by the blocker line (ต้องระบุต้นทุนเอง), not by the old long "ดู ต้นทุน" paragraph', async () => {
      await renderCeo({
        items: [newFormItem({ frozenLandedCostPerRequestedUnitThb: null, netUnitPrice: null })],
      });
      expect(within(screen.getByTestId('pcr-ceo-blockers')).getByRole('button', {
        name: 'รายการที่ 1 SCG A1 — ต้องระบุต้นทุนเอง',
      })).not.toBeNull();
      expect(screen.queryByText(/ทุกรายการต้องมีต้นทุนก่อนอนุมัติ/)).toBeNull();
    });

    it('L3: a legacy decision keeps the "วิธีคำนวณราคานี้" wording', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({
        items: [buildDecision({ items: [buildDecisionItem({ frozenLandedCostPerRequestedUnitThb: null })] })],
      });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');
      expect(within(getPanel()).getByText(/ทุกรายการต้องมีต้นทุนก่อนอนุมัติ/).textContent).toContain('วิธีคำนวณราคานี้');
    });

    it('the CEO fetches the factory quotes (server allows ceo) to show Import\'s price on the item card; so does Import', async () => {
      await renderCeo();
      expect(api.pricingRequests.listFactoryQuotes).toHaveBeenCalled();

      vi.clearAllMocks();
      setApiDefaults();
      cleanup();
      const request = buildRequest({ summary: { status: 'IMPORT_REVIEWING' } });
      renderDetailPage({ user: importUser, request });
      await waitForLoaded(request);
      await waitFor(() => expect(api.pricingRequests.listFactoryQuotes).toHaveBeenCalled());
    });
  });

  describe('fix round 2 (2026-10-01)', () => {
    it('"ใช้ราคาตามสูตร" sends an EMPTY draft (no pending discount), clears the override, and drops no other draft', async () => {
      await renderCeo({
        items: [newFormItem({ manualSellingPricePerRequestedUnit: 150, listUnitPrice: 100, netUnitPrice: 150 })],
      });
      fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-discount-8001'), { target: { value: '10' } });
      fireEvent.click(ceoRow(8001).getByTestId('pcr-ceo-use-formula-price-8001'));
      await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalled());
      const sent = lastSavedItem();
      expect(sent.clearSellingPriceOverride).toBe(true);
      expect(sent.decisionNote).toMatch(/\S/);
      expect(sent.discountPct).toBeNull();
      // scoped action: it did not run the batch save, so the discount draft is still pending
      expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalledTimes(1);
    });

    it('nit: the one save is offered only for a real change — an untouched/identical or blank-stock ราคาตั้ง draft saves nothing', async () => {
      await renderCeo({ items: [nfStock({ listUnitPrice: 100, netUnitPrice: 100 })] });
      const input = () => ceoRow(8001).getByTestId('pcr-ceo-list-price-8001');
      fireEvent.change(input(), { target: { value: '100' } });
      expect(screen.queryByTestId('pcr-ceo-save-all')).toBeNull();
      fireEvent.change(input(), { target: { value: '120' } });
      expect(saveAllButton().disabled).toBe(false);
      fireEvent.change(input(), { target: { value: '100' } });
      expect(screen.queryByTestId('pcr-ceo-save-all')).toBeNull();
      // Blank on a stock line: nothing to clear.
      fireEvent.change(input(), { target: { value: '' } });
      expect(screen.queryByTestId('pcr-ceo-save-all')).toBeNull();
    });

    it('the CEO fetches fxRates, for the baht conversion of the catalogue price (GET /api/fx-rates is ceo/import)', async () => {
      api.fxRates = { list: vi.fn().mockResolvedValue({ fxRates: [] }) };
      try {
        await renderCeo();
        expect(api.fxRates.list).toHaveBeenCalled();
      } finally {
        delete api.fxRates;
      }
    });

    it('GLA-152: no ประเภทสินค้า select or line on a CEO row or its รายละเอียด (import line and stock line)', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      const c1 = buildCostingItemWithOverride({ id: 1, productType: 'GLASS_MOSAIC' });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({
        items: [buildDecision({
          priceMode: 'NET',
          items: [
            newFormItem({ pricingCostingItemId: 1, listUnitPrice: 100, netUnitPrice: 100 }),
            newFormItem({ id: 8002, pricingRequestItemId: 99, stockSource: 'IN_THAILAND', frozenLandedCostPerRequestedUnitThb: null, listUnitPrice: 150, netUnitPrice: 150 }),
          ],
        })],
      });
      renderDetailPage({ user: ceoUser, request, costings: [buildCosting({ id: 601, items: [c1] })] });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');
      for (const toggle of ceoTable().getAllByRole('button', { name: /^รายละเอียด / })) fireEvent.click(toggle);
      expect(ceoTable().getByTestId('pcr-ceo-details-8001')).not.toBeNull();
      expect(screen.queryByTestId('pcr-ceo-product-type-override-8001')).toBeNull();
      expect(screen.queryByTestId('pcr-ceo-product-type-override-8002')).toBeNull();
      expect(screen.queryByText(/ประเภทสินค้า/)).toBeNull();
    });

    it('GLA-152: legacy decision cards have no ประเภทสินค้า select either', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      const c1 = buildCostingItemWithOverride({ id: 1 });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({
        items: [buildDecision({ items: [buildDecisionItem({ pricingCostingItemId: 1 })] })],
      });
      renderDetailPage({ user: ceoUser, request, costings: [buildCosting({ id: 601, items: [c1] })] });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');
      fireEvent.click(screen.getByRole('button', { name: 'วิธีคำนวณราคานี้' }));
      expect(screen.queryByTestId('pcr-ceo-product-type-override-8001')).toBeNull();
      expect(screen.queryByText(/ประเภทสินค้า/)).toBeNull();
    });

    describe('อัตรากำไรเริ่มต้น default (owner 2026-10-01: read the formula config, fall back to 0.30)', () => {
      it('LOW-2: เริ่มพิจารณาราคาขาย waits for the config, so an early click cannot send the fallback', async () => {
        let resolveConfig;
        api.pricingFormulaConfig = { get: vi.fn(() => new Promise((r) => { resolveConfig = r; })) };
        await startReview();
        expect(screen.getByTestId('pcr-ceo-start-review').disabled).toBe(true);
        resolveConfig({ formulaConfig: { defaultMarginPct: 0.24 } });
        await waitFor(() => expect(screen.getByTestId('pcr-ceo-start-review').disabled).toBe(false));
      });

      afterEach(() => { delete api.pricingFormulaConfig; });
      const startReview = async () => {
        const request = buildRequest({ summary: { status: 'READY_FOR_CEO_REVIEW' } });
        renderDetailPage({ user: ceoUser, request });
        await waitForLoaded(request);
        // the input is a PERCENT now (30), converted to the stored fraction (0.30) when sent
        return screen.findByPlaceholderText('30');
      };

      it('(a) shows the config defaultMarginPct as a PERCENT and startPricingDecision sends the fraction (0.29 -> "29" -> 0.29)', async () => {
        // 0.29 * 100 = 28.999999999999996 in floating point: the display must not leak that.
        api.pricingFormulaConfig = { get: vi.fn().mockResolvedValue({ formulaConfig: { defaultMarginPct: 0.29, sellingBuffer: 1 } }) };
        const input = await startReview();
        await waitFor(() => expect(input.value).toBe('29'));
        fireEvent.click(screen.getByTestId('pcr-ceo-start-review'));
        await waitFor(() => expect(api.pricingRequests.startPricingDecision).toHaveBeenCalledWith(
          501, expect.objectContaining({ defaultMarginPct: 0.29 }),
        ));
      });

      it('(b) falls back to 30 (%) when the config read fails, and sends the fraction 0.3', async () => {
        api.pricingFormulaConfig = { get: vi.fn().mockRejectedValue(new Error('boom')) };
        const input = await startReview();
        expect(input.value).toBe('30');
        fireEvent.click(screen.getByTestId('pcr-ceo-start-review'));
        await waitFor(() => expect(api.pricingRequests.startPricingDecision).toHaveBeenCalledWith(
          501, expect.objectContaining({ defaultMarginPct: 0.3 }),
        ));
      });

      it('(c) keeps what the CEO typed (35 %), even after the config arrives, and sends exactly 0.35', async () => {
        api.pricingFormulaConfig = { get: vi.fn().mockResolvedValue({ formulaConfig: { defaultMarginPct: 0.24 } }) };
        const input = await startReview();
        fireEvent.change(input, { target: { value: '35' } });
        await waitFor(() => expect(api.pricingFormulaConfig.get).toHaveBeenCalled());
        expect(input.value).toBe('35');
        fireEvent.click(screen.getByTestId('pcr-ceo-start-review'));
        await waitFor(() => expect(api.pricingRequests.startPricingDecision).toHaveBeenCalledWith(
          501, expect.objectContaining({ defaultMarginPct: 0.35 }),
        ));
      });

      it('the config query is enabled for the CEO before any decision exists', async () => {
        api.pricingFormulaConfig = { get: vi.fn().mockResolvedValue({ formulaConfig: { defaultMarginPct: 0.24 } }) };
        await startReview();
        await waitFor(() => expect(api.pricingFormulaConfig.get).toHaveBeenCalledTimes(1));
      });
    });
  });

  it('per-item cost controls (ปรับต้นทุนเอง) stay reachable for the CEO inside the row\'s รายละเอียด', async () => {
    const costingItem = buildCostingItemWithOverride({ id: 1 });
    // collapsed by default: costable, not stale
    await renderCeoNewForm({
      items: [newFormItem({ pricingCostingItemId: costingItem.id, listUnitPrice: 100, netUnitPrice: 100 })],
      costings: [buildCosting({ id: 601, items: [costingItem] })],
    });
    expect(ceoTable().queryByTestId('pcr-ceo-cost-override-8001')).toBeNull();
    openRowDetails('SCG A1');
    expect(ceoTable().getByTestId('pcr-ceo-cost-override-8001')).not.toBeNull();
  });
});

// ── บันทึกโรงงาน must never fall through to the browser's native form submission ────────────────
// SafeForm only prevented the default when it BLOCKED a submit, and neither ImportFactoryPicker's
// handleSubmit nor the page's onSave called preventDefault, so an allowed submit was a real GET
// navigation (full reload under BrowserRouter: every in-memory draft lost, in-flight mutation
// possibly cancelled). jsdom never navigates, so the observable is the submit event's
// `defaultPrevented`, recorded by a capture-phase document listener (runs before React's handler)
// and inspected after dispatch completes.
describe('PricingRequestDetailPage บันทึกโรงงาน does not trigger native form submission', () => {
  let submitEvents;
  const record = (e) => { submitEvents.push(e); };
  beforeEach(() => {
    submitEvents = [];
    document.addEventListener('submit', record, true);
  });
  afterEach(() => document.removeEventListener('submit', record, true));

  it('prevents the default on a real save (factory picked)', async () => {
    const request = buildRequestWithBlankFactoryLine();
    renderDetailPage({ user: importUser, request });
    await waitForLoaded(request);

    fireEvent.focus(screen.getByLabelText('ระบุโรงงาน'));
    fireEvent.click(await screen.findByRole('option', { name: 'Cotto Industry' }));
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกโรงงาน' }));

    await waitFor(() => expect(api.pricingRequests.setItemFactory).toHaveBeenCalledTimes(1));
    expect(submitEvents).toHaveLength(1);
    expect(submitEvents[0].defaultPrevented).toBe(true);
  });

  it('prevents the default on the "nothing picked" early return (BUG-20 warning path)', async () => {
    const request = buildRequestWithBlankFactoryLine();
    renderDetailPage({ user: importUser, request });
    await waitForLoaded(request);

    fireEvent.click(screen.getByRole('button', { name: 'บันทึกโรงงาน' }));

    expect(screen.getByText('เลือกโรงงานจากรายการก่อนกดบันทึก')).toBeTruthy();
    expect(api.pricingRequests.setItemFactory).not.toHaveBeenCalled();
    expect(submitEvents).toHaveLength(1);
    expect(submitEvents[0].defaultPrevented).toBe(true);
  });

  // The "already saving" early return IS reachable: two clicks inside one act() (see the
  // double-click test above) -> the second submit hits the inFlight ref and returns early.
  it('prevents the default on BOTH submits of a fast double click (second one hits the inFlight early return)', async () => {
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
    expect(submitEvents).toHaveLength(2);
    expect(submitEvents.map((e) => e.defaultPrevented)).toEqual([true, true]);
  });
});

// ── BUG C: ยืนยันราคาเสนอ must say what still blocks the CEO gate ──────────────────────────────
// FactoryQuoteService.markReadyForCosting only advances the request to READY_FOR_CEO_REVIEW when
// every IMPORT line (stockSource null; stock lines are exempt, IN_TRANSIT ones need an ETA) has a
// factory AND a READY_FOR_COSTING quote covering it. Otherwise the click
// "succeeded" with a green toast and the request silently stayed at AWAITING_FACTORY_RESPONSE.
// UI-LEVEL ONLY: the status transition itself is the Java service's (mocked here by flipping what
// api.pricingRequests.get / listFactoryQuotes return once markFactoryQuoteReady has been called).
describe('PricingRequestDetailPage ยืนยันราคาเสนอ reports what still blocks the CEO gate', () => {

  function twoFactoryRequest(statusOverride) {
    const base = buildRequest();
    return buildRequest({
      summary: statusOverride,
      items: [
        { ...base.items[0], id: 1, resolvedFactoryName: 'SCG Ceramics' },
        { ...base.items[0], id: 2, brand: 'COTTO', model: 'B2', productDescription: 'กระเบื้องผนัง COTTO B2', resolvedFactoryName: 'Cotto Industry' },
      ],
    });
  }

  const quoteFor = (id, factoryId, factoryName, itemId, status) => buildFactoryQuote({
    id,
    factoryId,
    factoryName,
    status,
    items: [{ ...buildFactoryQuote().items[0], id: id * 10, pricingRequestItemId: itemId, rawUnitPrice: 50 }],
  });

  // Before the click the server holds `before`; once markFactoryQuoteReady has been called the
  // refetches see `after`. Returns the showToast spy.
  function renderWithServerTransition({ before, after, quotesBefore, quotesAfter }) {
    const showToast = vi.fn();
    const ready = () => api.pricingRequests.markFactoryQuoteReady.mock.calls.length > 0;
    renderDetailPage({ user: importUser, request: before, factoryQuotes: quotesBefore, showToast });
    api.pricingRequests.get.mockImplementation(async () => ({ pricingRequest: ready() ? after : before }));
    api.pricingRequests.listFactoryQuotes.mockImplementation(async () => ({ items: ready() ? quotesAfter : quotesBefore }));
    return showToast;
  }

  const notice = () => screen.queryByTestId('pcr-ceo-gate-notice');

  it('(a) gate passed (refetch shows READY_FOR_CEO_REVIEW): success toast, and NO blocker warning', async () => {
    const before = buildRequest({ summary: { status: 'AWAITING_FACTORY_RESPONSE' } });
    const after = buildRequest({ summary: { status: 'READY_FOR_CEO_REVIEW' } });
    const q = (status) => [quoteFor(91, 601, 'SCG Ceramics', 1, status)];
    const showToast = renderWithServerTransition({
      before, after, quotesBefore: q('RESPONSE_RECEIVED'), quotesAfter: q('READY_FOR_COSTING'),
    });
    await waitForLoaded(before);
    await screen.findByText('SCG Ceramics');

    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('success', 'ยืนยันราคาเสนอแล้ว'));
    // let the post-mark-ready refetch settle before asserting an ABSENCE
    await waitFor(() => expect(api.pricingRequests.get.mock.calls.length).toBeGreaterThan(1));
    expect(notice()).toBeNull();
  });

  it('(b) gate NOT passed, one line has no factory: a warning names that line by position and product', async () => {
    const before = buildRequestWithBlankFactoryLine({ summary: { status: 'AWAITING_FACTORY_RESPONSE' } });
    const q = (status) => [quoteFor(91, 601, 'SCG Ceramics', 1, status)];
    renderWithServerTransition({
      before, after: before, quotesBefore: q('RESPONSE_RECEIVED'), quotesAfter: q('READY_FOR_COSTING'),
    });
    await waitForLoaded(before);
    await screen.findByText('SCG Ceramics');

    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));

    await waitFor(() => expect(api.pricingRequests.markFactoryQuoteReady).toHaveBeenCalledWith(91));
    // A persistent notice (a toast's 3.2 s cannot carry a blocker list) naming the unrouted line.
    const text = (await screen.findByTestId('pcr-ceo-gate-notice')).textContent;
    expect(text).toContain('ยืนยันราคาเสนอแล้ว แต่ยังไม่ส่งถึง CEO');
    expect(text).toContain('ยังไม่ได้ระบุโรงงาน');
    expect(text).toContain('รายการที่ 2');
    expect(text).toContain('กระเบื้องนำเข้าพิเศษ');
    // the line that DOES have a routed + confirmed factory is not blamed
    expect(text).not.toContain('รายการที่ 1');
  });

  it('(c) gate NOT passed, a second factory group is not yet confirmed: the warning names THAT factory, not the confirmed one', async () => {
    const before = twoFactoryRequest({ status: 'AWAITING_FACTORY_RESPONSE' });
    const quotesBefore = [
      quoteFor(91, 601, 'SCG Ceramics', 1, 'RESPONSE_RECEIVED'),
      quoteFor(92, 602, 'Cotto Industry', 2, 'REQUESTED'),
    ];
    const quotesAfter = [
      quoteFor(91, 601, 'SCG Ceramics', 1, 'READY_FOR_COSTING'),
      quoteFor(92, 602, 'Cotto Industry', 2, 'REQUESTED'),
    ];
    renderWithServerTransition({ before, after: before, quotesBefore, quotesAfter });
    await waitForLoaded(before);
    await screen.findByText('Cotto Industry');

    // first button = the SCG group (groups render in quote order); confirm only that one
    fireEvent.click(screen.getAllByRole('button', { name: 'ยืนยันราคาเสนอ' })[0]);

    await waitFor(() => expect(api.pricingRequests.markFactoryQuoteReady).toHaveBeenCalledWith(91));
    const text = (await screen.findByTestId('pcr-ceo-gate-notice')).textContent;
    expect(text).toContain('รอยืนยันราคาโรงงาน');
    expect(text).toContain('Cotto Industry');
    expect(text).not.toContain('SCG Ceramics');
    // no line is missing a factory here, so it must not claim that
    expect(text).not.toContain('ยังไม่ได้ระบุโรงงาน');
  });

  // The notice is computed from the re-read; until that lands (or if it fails) the cache is stale
  // and must not be presented as "what still blocks".
  function renderAwaitingBlankLine() {
    const before = buildRequestWithBlankFactoryLine({ summary: { status: 'AWAITING_FACTORY_RESPONSE' } });
    const quotes = [quoteFor(91, 601, 'SCG Ceramics', 1, 'RESPONSE_RECEIVED')];
    renderDetailPage({ user: importUser, request: before, factoryQuotes: quotes });
    return { before, quotes, markedReady: () => api.pricingRequests.markFactoryQuoteReady.mock.calls.length > 0 };
  }

  it('shows no notice while the post-confirm factory-quote re-read is still pending, then shows it once it lands', async () => {
    const { before, quotes, markedReady } = renderAwaitingBlankLine();
    await waitForLoaded(before);
    await screen.findByText('SCG Ceramics');
    let release;
    const deferred = new Promise((resolve) => { release = resolve; });
    api.pricingRequests.listFactoryQuotes.mockImplementation(
      () => (markedReady() ? deferred : Promise.resolve({ items: quotes })),
    );

    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));
    await waitFor(() => expect(api.pricingRequests.listFactoryQuotes).toHaveBeenCalledTimes(2));
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(notice()).toBeNull();

    await act(async () => { release({ items: [{ ...quotes[0], status: 'READY_FOR_COSTING' }] }); });
    expect((await screen.findByTestId('pcr-ceo-gate-notice')).textContent).toContain('รายการที่ 2');
  });

  // Two factories, A (quote 91) confirmed then B (quote 92) while A's refetch is held.
  // Returns controls to release A's refetch (finishA) and B's mark-ready (finishB). With
  // holdFirstMarkReady, A's mark-ready itself is held instead and finishFirstMarkReady releases it,
  // so B can run to completion while A is still in flight.
  async function overlapConfirms({ holdFirstMarkReady = false } = {}) {
    const before = twoFactoryRequest({ status: 'AWAITING_FACTORY_RESPONSE' });
    const quotesBefore = [
      quoteFor(91, 601, 'SCG Ceramics', 1, 'RESPONSE_RECEIVED'),
      quoteFor(92, 602, 'Cotto Industry', 2, 'RESPONSE_RECEIVED'),
    ];
    const quotesAfterA = [
      quoteFor(91, 601, 'SCG Ceramics', 1, 'READY_FOR_COSTING'),
      quoteFor(92, 602, 'Cotto Industry', 2, 'RESPONSE_RECEIVED'),
    ];
    renderDetailPage({ user: importUser, request: before, factoryQuotes: quotesBefore });
    await waitForLoaded(before);
    await screen.findByText('Cotto Industry');
    let releaseA; let releaseB; let releaseMarkA;
    const heldA = new Promise((resolve) => { releaseA = resolve; });
    const heldMarkA = new Promise((resolve) => { releaseMarkA = resolve; });
    const heldB = new Promise((resolve) => { releaseB = resolve; });
    let aReleased = holdFirstMarkReady;
    api.pricingRequests.markFactoryQuoteReady.mockImplementation((id) => {
      if (holdFirstMarkReady) return id === 91 ? heldMarkA : Promise.resolve({});
      return id === 92 ? heldB : Promise.resolve({});
    });
    api.pricingRequests.listFactoryQuotes.mockImplementation(() => {
      if (api.pricingRequests.markFactoryQuoteReady.mock.calls.length === 0) return Promise.resolve({ items: quotesBefore });
      if (holdFirstMarkReady) return Promise.resolve({ items: quotesBefore }); // server unchanged: A's button stays
      return aReleased ? Promise.resolve({ items: quotesAfterA }) : heldA;
    });

    fireEvent.click(screen.getAllByRole('button', { name: 'ยืนยันราคาเสนอ' })[0]);
    if (holdFirstMarkReady) {
      await waitFor(() => expect(api.pricingRequests.markFactoryQuoteReady).toHaveBeenCalledWith(91));
    } else {
      await waitFor(() => expect(api.pricingRequests.listFactoryQuotes).toHaveBeenCalledTimes(2));
    }
    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));
    await waitFor(() => expect(api.pricingRequests.markFactoryQuoteReady).toHaveBeenCalledWith(92));
    return {
      finishFirstMarkReady: async () => { await act(async () => { releaseMarkA({}); }); },
      finishA: async () => {
        aReleased = true;
        await act(async () => { releaseA({ items: quotesAfterA }); });
        // After A's refetch its quote is READY_FOR_COSTING, so A's button is gone; only B's busy
        // button remains. (The single-slot mutation fails inside this wait, so A's finally has run.)
        await waitFor(() => expect(screen.getAllByRole('button', { name: 'กำลังยืนยัน…' })).toHaveLength(1));
      },
      finishB: async () => { await act(async () => { releaseB({}); }); },
    };
  }

  // Catches: tracking in-flight confirms in a single slot that the first confirm's finally nulls.
  it("a second factory's ยืนยันราคาเสนอ stays busy after the first confirm's refetch resolves", async () => {
    const { finishA, finishB } = await overlapConfirms();
    await finishA();

    const busy = screen.getAllByRole('button', { name: 'กำลังยืนยัน…' });
    expect(busy).toHaveLength(1);
    expect(busy[0].disabled).toBe(true);

    await finishB();
    await waitFor(() => expect(screen.queryByRole('button', { name: 'กำลังยืนยัน…' })).toBeNull());
  });

  // Catches: the notice rendering while a later-started confirm is still in flight (no "set is empty" gate).
  it('the notice waits for the LATEST confirm, not the first', async () => {
    const { finishA, finishB } = await overlapConfirms();
    await finishA();
    expect(notice()).toBeNull();

    await finishB();
    expect((await screen.findByTestId('pcr-ceo-gate-notice')).textContent).toContain('Cotto Industry');
  });

  // Catches: ordering the notice by confirm START only, so a later-started confirm finishing first raises it while an earlier one is in flight.
  it('the notice waits until NO confirm is in flight (second finishes first)', async () => {
    const { finishFirstMarkReady } = await overlapConfirms({ holdFirstMarkReady: true });
    // B ran to completion (its refetch landed); A is still waiting on markFactoryQuoteReady
    await waitFor(() => expect(api.pricingRequests.listFactoryQuotes.mock.calls.length).toBeGreaterThan(1));
    await waitFor(() => expect(screen.getAllByRole('button', { name: 'กำลังยืนยัน…' })).toHaveLength(1));
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(notice()).toBeNull();

    await finishFirstMarkReady();
    expect((await screen.findByTestId('pcr-ceo-gate-notice')).textContent).toContain('Cotto Industry');
  });

  it('shows no notice when the post-confirm re-read FAILED (stale cache is not evidence)', async () => {
    const { before, quotes, markedReady } = renderAwaitingBlankLine();
    await waitForLoaded(before);
    await screen.findByText('SCG Ceramics');
    api.pricingRequests.listFactoryQuotes.mockImplementation(
      () => (markedReady() ? Promise.reject(new Error('boom')) : Promise.resolve({ items: quotes })),
    );

    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));
    await waitFor(() => expect(api.pricingRequests.listFactoryQuotes).toHaveBeenCalledTimes(2));
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(notice()).toBeNull();
  });

  it('the notice disappears once a later refetch shows the request past the gate', async () => {
    const before = buildRequestWithBlankFactoryLine({ summary: { status: 'AWAITING_FACTORY_RESPONSE' } });
    const quote = (status) => [quoteFor(91, 601, 'SCG Ceramics', 1, status)];
    const { queryClient } = renderDetailPage({ user: importUser, request: before, factoryQuotes: quote('RESPONSE_RECEIVED') });
    await waitForLoaded(before);
    await screen.findByText('SCG Ceramics');

    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));
    await screen.findByTestId('pcr-ceo-gate-notice');

    api.pricingRequests.get.mockResolvedValue({ pricingRequest: buildRequest({ summary: { status: 'READY_FOR_CEO_REVIEW' } }) });
    await act(async () => { await queryClient.invalidateQueries(); });

    await waitFor(() => expect(notice()).toBeNull());
  });
});

// A stock line (stockSource IN_THAILAND) is priced by the CEO and then waits for delivery: it is
// never ordered from a factory, so import is not asked to contact one for it; the request page
// says which lines are stock; and once the deal's order is confirmed no new pricing round starts
// from it.
describe('PricingRequestDetailPage - stock lines and the confirmed order (Walk 4)', () => {
  const baseItem = buildRequest().items[0];
  const stockRequestItem = (overrides = {}) => ({
    ...baseItem, id: 2, model: 'S1', productDescription: 'กระเบื้อง S1 จากสต็อก', stockSource: 'IN_THAILAND',
    originCountry: null, leadTimeMinDays: null, leadTimeMaxDays: null, resolvedFactoryName: null, ...overrides,
  });
  const stockDecisionItem = (overrides = {}) => buildDecisionItem({
    id: 8002, pricingRequestItemId: 2, pricingCostingItemId: null, stockSource: 'IN_THAILAND',
    frozenLandedCostPerRequestedUnitThb: null, sqmPerPiece: 0.36, listUnitPrice: 150, netUnitPrice: 150, ...overrides,
  });

  // A return-to-import leaves a RETURNED decision behind; when the request comes back READY, the
  // CEO starts a fresh review, so the page must not show the RETURNED decision and no start button.
  it('W4-03: a READY_FOR_CEO_REVIEW request whose only decision was RETURNED offers เริ่มพิจารณาราคาขาย', async () => {
    const request = buildRequest({ summary: { status: 'READY_FOR_CEO_REVIEW' } });
    api.pricingRequests.listPricingDecisions.mockResolvedValue({ items: [buildDecision({ status: 'RETURNED' })] });
    renderDetailPage({ user: ceoUser, request });
    await waitForLoaded(request);
    await waitFor(() => expect(api.pricingRequests.listPricingDecisions).toHaveBeenCalled());

    expect(await screen.findByTestId('pcr-ceo-start-review')).not.toBeNull();
  });

  // With no import line there is no import cost to fix, so the CEO's ตีกลับให้ฝ่ายนำเข้าแก้ไข is
  // hidden (the backend 409 stays); with any import line it is still there.
  async function renderCeoReviewing(requestItems, decisionItems) {
    const request = buildRequest({ summary: { status: 'CEO_REVIEWING' }, items: requestItems });
    api.pricingRequests.listPricingDecisions.mockResolvedValue({
      items: [buildDecision({ priceMode: 'NET', items: decisionItems })],
    });
    renderDetailPage({ user: ceoUser, request });
    await waitForLoaded(request);
    await screen.findByText('PCD-2026-0001');
    await screen.findByTestId('pcr-ceo-approve');
  }

  it('W4-06: on a request whose every line is a stock line the CEO is not offered ตีกลับฝ่ายนำเข้า (summary bar and mobile bar)', async () => {
    await renderCeoReviewing([stockRequestItem({ id: 1 })], [stockDecisionItem({ pricingRequestItemId: 1 })]);
    // positive control: the redesigned summary bar IS there, only the return control is not
    expect(screen.getByRole('region', { name: 'สรุปราคาคำขอนี้' })).not.toBeNull();
    expect(screen.queryByTestId('pcr-ceo-return')).toBeNull();
    expect(screen.queryByTestId('pcr-ceo-mobile-return')).toBeNull();
    expect(screen.queryByRole('button', { name: /ตีกลับ/ })).toBeNull();
  });

  it('W4-06: on a mixed request the CEO still has ตีกลับฝ่ายนำเข้า (wrong-way-round)', async () => {
    await renderCeoReviewing([baseItem, stockRequestItem()],
      [buildDecisionItem({ sqmPerPiece: 0.36, listUnitPrice: 100, netUnitPrice: 100 }), stockDecisionItem()]);
    expect(screen.getByTestId('pcr-ceo-return').textContent).toContain('ตีกลับฝ่ายนำเข้า');
    expect(screen.getByTestId('pcr-ceo-mobile-return')).not.toBeNull();
  });

  // The CEO's line rows say which lines are stock (the deal page already has the badge).
  it('W4-01: the CEO pricing table marks a stock line จากสต็อก and an import line not', async () => {
    await renderCeoReviewing([baseItem, stockRequestItem()],
      [buildDecisionItem({ sqmPerPiece: 0.36, listUnitPrice: 100, netUnitPrice: 100 }), stockDecisionItem()]);
    expect(ceoTable().getAllByText('จากสต็อก')).toHaveLength(1);
    expect(ceoRow(8002).getByText('จากสต็อก')).not.toBeNull();
    expect(ceoRow(8001).queryByText('จากสต็อก')).toBeNull();
  });

  describe('import view', () => {
    const importRequest = (items) => buildRequest({ summary: { status: 'IMPORT_REVIEWING' }, items });
    const importLine = { ...baseItem, resolvedFactoryName: 'Factory A', factory: null };

    it('W4-01: import sees a จากสต็อก badge on a stock line, and no factory mail/contact group for a stock-only factory', async () => {
      // The stock line resolves to Factory B through its catalog product: it must not put a
      // "Factory B - สร้างเมล / ติดต่อโรงงานแล้ว" group on screen for goods nobody orders from B.
      const request = importRequest([importLine, stockRequestItem({ resolvedFactoryName: 'Factory B' })]);
      renderDetailPage({ user: importUser, request });
      await waitForLoaded(request);

      const groups = await screen.findAllByTestId('pcr-price-preview-group');
      expect(groups).toHaveLength(1);
      expect(groups[0].textContent).toContain('Factory A');
      expect(groups[0].textContent).not.toContain('Factory B');
      expect(screen.getAllByText('จากสต็อก').length).toBeGreaterThanOrEqual(1);
    });

    it('W4-01: a stock line with no factory is not reported as "ยังไม่ได้ระบุโรงงาน"', async () => {
      const request = importRequest([importLine, stockRequestItem()]);
      renderDetailPage({ user: importUser, request });
      await waitForLoaded(request);
      await screen.findAllByTestId('pcr-price-preview-group');

      expect(screen.queryByText(/ยังไม่ได้ระบุโรงงาน \d+ รายการ/)).toBeNull();
    });
  });

  // The PCR summary carries the deal's payment status (null until the order is confirmed); the
  // backend refuses a new round once it is set, so the page does not offer สร้างรอบแก้ไข.
  describe('สร้างรอบแก้ไข once the deal order is confirmed', () => {
    it('is offered to the owner before the order is confirmed', async () => {
      const request = buildRequest({ summary: { status: 'QUOTATION_ISSUED', ticketPaymentStatus: null } });
      renderDetailPage({ user: salesOwner, request });
      await waitForLoaded(request);
      expect(screen.getByRole('button', { name: 'สร้างรอบแก้ไข' })).not.toBeNull();
    });

    it('is NOT offered once the deal order is confirmed (ticketPaymentStatus set)', async () => {
      const request = buildRequest({ summary: { status: 'QUOTATION_ISSUED', ticketPaymentStatus: 'CUSTOMER_CONFIRMED' } });
      renderDetailPage({ user: salesOwner, request });
      await waitForLoaded(request);
      await screen.findByText('PCR-2026-0001');
      expect(screen.queryByRole('button', { name: 'สร้างรอบแก้ไข' })).toBeNull();
    });
  });
});

// ══════════════════════════════════════════════════════════════════════════════════════════════
// CEO pricing workspace redesign (new-form decision, CEO view) — Phase A tests.
// UI-LEVEL ONLY, per this file's header. Design: one header, a sticky summary bar, a pricing table
// (desktop) / cards (mobile), a details row, ONE save. The table AND the cards are both rendered
// (jsdom has no media queries) and hidden by Tailwind classes, so queries here are SCOPED (see the
// helpers near the top of this file). The server stays the authority on every gate.
// ══════════════════════════════════════════════════════════════════════════════════════════════
describe('CEO pricing workspace redesign — new-form decision, CEO view', () => {
  describe('header, scope and the unchanged paths', () => {
    it('header: PCR code, status badge, customer and the meta row replace the separate ภาพรวม panel', async () => {
      const { request } = await renderCeoNewForm();
      const header = within(screen.getByTestId('pcr-ceo-header'));
      // the h1's accessible name stays EXACTLY the code (waitForLoaded depends on it): the badge sits beside it
      expect(header.getByRole('heading', { level: 1, name: request.summary.requestCode })).not.toBeNull();
      expect(header.getByText(pricingRequestStatusLabel('CEO_REVIEWING').label)).not.toBeNull();
      expect(header.getByText('บริษัท ทดสอบ จำกัด')).not.toBeNull();
      expect(header.getByRole('link', { name: 'PR-2026-0701' }).getAttribute('href')).toBe('/tickets/701');
      expect(header.getByText(/^ผู้รับ:/).textContent).toContain('ผู้ออกแบบ ก.');
      expect(header.getByText(/ต้องการภายใน/).textContent).toContain(formatThaiDate('2026-08-01'));
      expect(header.getByText(/ฝ่ายนำเข้า/)).not.toBeNull();
      expect(screen.queryByText('ภาพรวม')).toBeNull();
    });

    // Positive control first (the CEO DOES get the workspace), then the "cannot reach" cases.
    it.each([['import', importUser], ['sales', salesOwner], ['sales_manager', salesManager]])(
      '%s never sees the summary bar, the pricing table, the cards or the mobile bar (sales/manager keep the ภาพรวม panel; import has its own header instead)',
      async (_name, user) => {
        const { request } = await renderCeoNewForm();
        expect(screen.getByRole('region', { name: 'สรุปราคาคำขอนี้' })).not.toBeNull();
        expect(screen.getByTestId('pcr-ceo-table')).not.toBeNull();
        cleanup();

        renderDetailPage({ user, request });
        await waitForLoaded(request);
        await act(async () => { await new Promise((resolve) => setTimeout(resolve, 40)); });

        expect(screen.queryByRole('region', { name: 'สรุปราคาคำขอนี้' })).toBeNull();
        expect(screen.queryByRole('region', { name: 'ตั้งราคารายการ' })).toBeNull();
        for (const id of ['pcr-ceo-table', 'pcr-ceo-cards', 'pcr-ceo-card-8001', 'pcr-ceo-mobile-bar',
          'pcr-ceo-save-all', 'pcr-ceo-approve', 'pcr-ceo-return', 'pcr-ceo-header']) {
          expect(screen.queryByTestId(id)).toBeNull();
        }
        expect(screen.queryByRole('group', { name: 'วิธีกรอกราคา' })).toBeNull();
        if (user === importUser) {
          // Import PCR redesign: import's header replaces the ภาพรวม panel.
          expect(screen.getByTestId('pcr-import-header')).not.toBeNull();
          expect(screen.queryByText('ภาพรวม')).toBeNull();
        } else {
          expect(screen.getByText('ภาพรวม')).not.toBeNull();
          expect(screen.queryByTestId('pcr-import-header')).toBeNull();
        }
      },
    );

    // Guard (green before and after by design): the LEGACY path keeps its markup for the CEO.
    it('a LEGACY decision (no sqmPerPiece) still renders the old markup: no summary bar/table/mobile bar, ภาพรวม kept, per-row margin save and the old ตีกลับ label', async () => {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({ items: [buildDecision()] });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');

      for (const id of ['pcr-ceo-table', 'pcr-ceo-cards', 'pcr-ceo-mobile-bar', 'pcr-ceo-save-all', 'pcr-ceo-header', 'pcr-ceo-summary']) {
        expect(screen.queryByTestId(id)).toBeNull();
      }
      expect(screen.queryByRole('region', { name: 'สรุปราคาคำขอนี้' })).toBeNull();
      expect(screen.queryByRole('group', { name: 'วิธีกรอกราคา' })).toBeNull();
      expect(screen.getByText('ภาพรวม')).not.toBeNull();
      expect(screen.getByRole('heading', { level: 2, name: 'รายการสินค้าและราคาตั้งต้น' })).not.toBeNull();
      expect(screen.getByTestId('pcr-ceo-margin-save-8001')).not.toBeNull();
      expect(screen.getByRole('button', { name: 'ตีกลับให้ฝ่ายนำเข้าแก้ไข' })).not.toBeNull();
      expect(screen.getByRole('button', { name: 'อนุมัติราคาขาย' }).disabled).toBe(false);
    });
  });

  describe('summary bar', () => {
    it('is the section "สรุปราคาคำขอนี้", sticky on desktop, and shows ตั้งราคาแล้ว n/N, ราคาขายรวม, ต้นทุนรวม (lines with cost AND net) and กำไรรวม ฿ + % of cost', async () => {
      await renderCeoNewForm({
        requestItems: requestItemsN(4),
        items: [
          nfItem({ id: 8001, pricingRequestItemId: 1, frozenLandedCostPerRequestedUnitThb: 60, listUnitPrice: 72, netUnitPrice: 72, requestedQuantity: 20 }),
          nfItem({ id: 8002, pricingRequestItemId: 2, frozenLandedCostPerRequestedUnitThb: 100, listUnitPrice: 150, netUnitPrice: 150, requestedQuantity: 10 }),
          nfStock({ id: 8003, pricingRequestItemId: 3, listUnitPrice: 50, netUnitPrice: 50, requestedQuantity: 4 }),
          nfItem({ id: 8004, pricingRequestItemId: 4, frozenLandedCostPerRequestedUnitThb: null, listUnitPrice: null, netUnitPrice: null }),
        ],
      });

      const region = screen.getByRole('region', { name: 'สรุปราคาคำขอนี้' });
      expect(region.className).toMatch(/sticky/);
      const summary = ceoSummary();
      // priced = server net present and not blocked: 8001, 8002, 8003 (8004 has no cost and no net)
      expect(summary.getByTestId('pcr-ceo-sum-priced').textContent).toMatch(/ตั้งราคาแล้ว.*3\/4.*รายการ/);
      // 72x20 + 150x10 + 50x4 = 3,140 (every line with a saved net — the existing ceoGrandTotal semantics)
      expect(summary.getByTestId('pcr-ceo-sum-total').textContent).toMatch(/ราคาขายรวม \(ก่อน VAT\).*3,140/);
      // cost only over lines that have BOTH a cost and a net: 60x20 + 100x10 = 2,200 (stock 8003 has none)
      expect(summary.getByTestId('pcr-ceo-sum-cost').textContent).toMatch(/ต้นทุนรวม.*2,200/);
      // profit = 240 + 500 = 740 = 33.6% of 2,200
      expect(summary.getByTestId('pcr-ceo-sum-profit').textContent).toMatch(/กำไรรวม.*740.*33\.6%/);
    });

    it('buttons: บันทึกราคา (n รายการ) only when a row is dirty; ตีกลับฝ่ายนำเข้า; อนุมัติราคาขาย — and a single per-row-free save', async () => {
      await renderCeoNewForm({ items: [nfItem({ listUnitPrice: 100, netUnitPrice: 100 })] });
      expect(screen.queryByTestId('pcr-ceo-save-all')).toBeNull();
      expect(ceoSummary().getByRole('button', { name: 'ตีกลับฝ่ายนำเข้า' })).not.toBeNull();
      expect(ceoSummary().getByRole('button', { name: 'อนุมัติราคาขาย' })).not.toBeNull();

      fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-discount-8001'), { target: { value: '10' } });
      expect(saveAllButton().textContent).toContain('บันทึกราคา (1 รายการ)');
      // the per-row บันทึกราคา buttons are gone: exactly one button on the page starts with บันทึกราคา
      expect(screen.getAllByRole('button', { name: /^บันทึกราคา/ })).toHaveLength(1);
    });

    it('approve stays disabled while any row has unsaved drafts, says why, and re-enables once the save lands (cannot approve past a draft)', async () => {
      await renderCeoNewForm({ items: [nfItem({ listUnitPrice: 100, netUnitPrice: 100 })] });
      const approve = () => screen.getByTestId('pcr-ceo-approve');
      expect(approve().disabled).toBe(false);
      expect(screen.queryByTestId('pcr-ceo-blockers')).toBeNull();
      expect(screen.queryByTestId('pcr-ceo-dirty-note')).toBeNull();

      fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-discount-8001'), { target: { value: '10' } });
      expect(approve().disabled).toBe(true);
      expect(screen.getByTestId('pcr-ceo-mobile-approve').disabled).toBe(true);
      expect(screen.getByTestId('pcr-ceo-dirty-note').textContent).toContain('มีราคาที่แก้แล้วยังไม่บันทึก — กดบันทึกก่อนอนุมัติ');
      // no blocker at all here: only the unsaved note
      expect(screen.queryByTestId('pcr-ceo-blockers')).toBeNull();
      fireEvent.click(approve());
      expect(screen.queryByRole('dialog')).toBeNull();
      expect(api.pricingRequests.approvePricingDecision).not.toHaveBeenCalled();

      fireEvent.click(saveAllButton());
      await waitFor(() => expect(approve().disabled).toBe(false));
      expect(screen.queryByTestId('pcr-ceo-dirty-note')).toBeNull();
      expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalledTimes(1);

      // same confirm as before, same call
      fireEvent.click(approve());
      fireEvent.click(await screen.findByRole('button', { name: 'อนุมัติ' }));
      await waitFor(() => expect(api.pricingRequests.approvePricingDecision).toHaveBeenCalledWith(
        7001, expect.objectContaining({ clientRequestId: expect.any(String) }),
      ));
    });

    it('ตีกลับฝ่ายนำเข้า keeps the same confirm (reason required) and call', async () => {
      await renderCeoNewForm();
      fireEvent.click(ceoSummary().getByRole('button', { name: 'ตีกลับฝ่ายนำเข้า' }));
      const dialog = await screen.findByRole('dialog');
      fireEvent.change(within(dialog).getByLabelText('เหตุผลที่ตีกลับ'), { target: { value: 'ราคาต้นทุนคลาดเคลื่อน' } });
      fireEvent.click(within(dialog).getByRole('button', { name: 'ตีกลับ' }));
      await waitFor(() => expect(api.pricingRequests.returnPricingDecisionToImport).toHaveBeenCalledWith(
        7001, { returnReason: 'ราคาต้นทุนคลาดเคลื่อน' },
      ));
    });
  });

  describe('blocker line "ยังอนุมัติไม่ได้:"', () => {
    it.each([
      ['ต้องระบุต้นทุนเอง', 'NET', () => nfItem({ frozenLandedCostPerRequestedUnitThb: null, listUnitPrice: null, netUnitPrice: null })],
      ['ต้องใส่ราคาตั้ง (สต็อก)', 'NET', () => nfStock()],
      ['ยังไม่มีราคา', 'NET', () => nfItem({ netUnitPrice: null })],
      ['ยังไม่มีราคา', 'DIRECT_NET', () => nfItem({ netUnitPrice: null })],
    ])('names the blocking item and the reason "%s" (mode %s) and keeps approve disabled', async (reason, priceMode, makeItem) => {
      await renderCeoNewForm({ priceMode, items: [makeItem()] });
      const blockers = screen.getByTestId('pcr-ceo-blockers');
      expect(blockers.textContent).toContain('ยังอนุมัติไม่ได้:');
      expect(within(blockers).getByRole('button', { name: `รายการที่ 1 SCG A1 — ${reason}` })).not.toBeNull();
      expect(screen.getByTestId('pcr-ceo-approve').disabled).toBe(true);
    });

    it('a STALE cost override blocks with "ต้นทุนที่ปรับล้าสมัย", tags the row and opens its รายละเอียด', async () => {
      const costingItem = buildCostingItemWithOverride({
        id: 1, manualLandedCostPerUnitThb: 75, overrideReason: 'เหตุผลเดิม',
        overrideFxRate: 1, overrideCalcConfigVersion: 1, calculationConfigVersion: 2, overrideStale: true,
      });
      await renderCeoNewForm({
        items: [nfItem({ pricingCostingItemId: 1, listUnitPrice: 72, netUnitPrice: 72 })],
        costings: [buildCosting({ id: 601, items: [costingItem] })],
      });
      expect(within(screen.getByTestId('pcr-ceo-blockers')).getByRole('button', {
        name: 'รายการที่ 1 SCG A1 — ต้นทุนที่ปรับล้าสมัย',
      })).not.toBeNull();
      expect(ceoRow(8001).getByText('ต้นทุนที่ปรับล้าสมัย')).not.toBeNull();
      expect(ceoTable().getByRole('button', { name: 'รายละเอียด SCG A1' }).getAttribute('aria-expanded')).toBe('true');
      expect(screen.getByTestId('pcr-ceo-approve').disabled).toBe(true);
    });

    it('one reason per item, cost first: an uncostable line with no price reads ต้องระบุต้นทุนเอง, not ยังไม่มีราคา', async () => {
      await renderCeoNewForm({
        items: [nfItem({ frozenLandedCostPerRequestedUnitThb: null, listUnitPrice: null, netUnitPrice: null })],
      });
      const blockers = within(screen.getByTestId('pcr-ceo-blockers'));
      expect(blockers.getAllByRole('button')).toHaveLength(1);
      expect(blockers.queryByRole('button', { name: /ยังไม่มีราคา/ })).toBeNull();
    });

    it('clicking a blocker scrolls to that row and opens its รายละเอียด (the other rows stay closed)', async () => {
      const scroll = vi.fn();
      const original = Element.prototype.scrollIntoView;
      Element.prototype.scrollIntoView = scroll;
      try {
        await renderCeoNewForm({
          requestItems: requestItemsN(2),
          items: [
            nfItem({ id: 8001, pricingRequestItemId: 1, listUnitPrice: 100, netUnitPrice: 100 }),
            nfItem({ id: 8002, pricingRequestItemId: 2, netUnitPrice: null }),
          ],
        });
        expect(ceoTable().getByRole('button', { name: 'รายละเอียด SCG A2' }).getAttribute('aria-expanded')).toBe('false');

        fireEvent.click(within(screen.getByTestId('pcr-ceo-blockers')).getByRole('button', { name: 'รายการที่ 2 SCG A2 — ยังไม่มีราคา' }));

        expect(ceoTable().getByRole('button', { name: 'รายละเอียด SCG A2' }).getAttribute('aria-expanded')).toBe('true');
        expect(ceoTable().getByTestId('pcr-ceo-details-8002')).not.toBeNull();
        expect(ceoTable().getByRole('button', { name: 'รายละเอียด SCG A1' }).getAttribute('aria-expanded')).toBe('false');
        expect(scroll.mock.contexts.some((el) => ['pcr-ceo-row-8002', 'pcr-ceo-card-8002'].includes(el?.dataset?.testid))).toBe(true);
      } finally {
        if (original) Element.prototype.scrollIntoView = original; else delete Element.prototype.scrollIntoView;
      }
    });
  });

  describe('pricing panel and table', () => {
    it('the costing-error alert stays in the panel after a failed recalculate', async () => {
      api.pricingRequests.recalculatePricingDecisionCost.mockRejectedValue(new Error('คำนวณต้นทุนไม่สำเร็จ ทดสอบ'));
      await renderCeoNewForm();
      fireEvent.click(within(ceoPricingPanel()).getByTestId('pcr-ceo-recalculate-cost'));
      const alert = await within(ceoPricingPanel()).findByTestId('pcr-ceo-costing-error');
      expect(alert.textContent).toContain('คำนวณต้นทุนไม่สำเร็จ ทดสอบ');
    });

    it.each([
      ['NET', ['#', 'สินค้า', 'จำนวน', 'ต้นทุน/แผ่น', 'กำไร %', 'ราคาตั้ง/แผ่น', 'ส่วนลด', 'ราคาสุทธิ/แผ่น', 'รวม (ก่อน VAT)', 'กำไร', 'รายละเอียด']],
      ['SPECIAL_SQM', ['#', 'สินค้า', 'จำนวน', 'ต้นทุน/แผ่น', 'กำไร %', 'ราคาตั้ง (สูตร)', 'ราคาพิเศษ', 'ราคาสุทธิ/แผ่น', 'รวม (ก่อน VAT)', 'กำไร', 'รายละเอียด']],
      ['DIRECT_NET', ['#', 'สินค้า', 'จำนวน', 'ต้นทุน/แผ่น', 'ราคาสุทธิ/แผ่น', 'ราคาสุทธิ/แผ่น', 'รวม (ก่อน VAT)', 'กำไร', 'รายละเอียด']],
    ])('%s: the table columns depend on the mode (always-columns + mode columns), one row per item', async (priceMode, headers) => {
      await renderCeoNewForm({ priceMode, items: [nfItem({ netUnitPrice: 100, listUnitPrice: 100, directNetPrice: 100 })] });
      const names = ceoTable().getAllByRole('columnheader').map((th) => th.textContent.trim()).filter(Boolean);
      expect(names).toEqual(headers);
      expect(ceoTable().getAllByTestId(/^pcr-ceo-row-/)).toHaveLength(1);
    });

    it('row cells: product (name, factory, size), จำนวน (แผ่น + ตร.ม.), ต้นทุน, net/แผ่น (+ /ตร.ม.), รวม, กำไร (฿ + %), margin shown as a percent', async () => {
      await renderCeoNewForm({
        priceMode: 'NET',
        items: [nfItem({ listUnitPrice: 72, netUnitPrice: 72, proposedMarginPct: 0.3 })],
      });
      const row = ceoRow(8001);
      expect(row.getByText('SCG A1')).not.toBeNull();
      expect(row.getByText(/SCG Ceramics/)).not.toBeNull();
      expect(row.getByText(/60x60/)).not.toBeNull();
      expect(row.getByText('20 แผ่น')).not.toBeNull();
      expect(row.getByText('7.2 ตร.ม.')).not.toBeNull();
      expect(row.getByTestId('pcr-ceo-cost-both-8001').textContent).toMatch(/฿60\.00.*฿166\.67\/ตร\.ม\./);
      expect(row.getByTestId('pcr-ceo-net-8001').textContent).toMatch(/฿72\.00.*฿200\.00\/ตร\.ม\./);
      expect(row.getByTestId('pcr-ceo-total-8001').textContent).toContain('฿1,440.00');
      expect(row.getByTestId('pcr-ceo-profit-8001').textContent).toMatch(/฿240\.00.*20\.0%/);
      expect(row.getByTestId('pcr-ceo-margin-input-8001').value).toBe('30');
    });

    it('tags and row state: จากสต็อก / ต้องระบุต้นทุนเอง / ปรับเอง / ยังไม่บันทึก / มีราคาแล้ว, with data-state ok | dirty | blocked', async () => {
      const uncostable = buildCostingItemWithOverride({ id: 4, uncostableReason: 'ไม่พบอัตราค่าขนส่ง' });
      await renderCeoNewForm({
        requestItems: requestItemsN(4),
        costings: [buildCosting({ id: 601, items: [uncostable] })],
        items: [
          nfItem({ id: 8001, pricingRequestItemId: 1, pricingCostingItemId: 77, listUnitPrice: 100, netUnitPrice: 100 }),
          nfStock({ id: 8002, pricingRequestItemId: 2, listUnitPrice: 50, netUnitPrice: 50 }),
          nfItem({ id: 8003, pricingRequestItemId: 3, pricingCostingItemId: 78, manualSellingPricePerRequestedUnit: 150, listUnitPrice: 100, netUnitPrice: 150 }),
          nfItem({ id: 8004, pricingRequestItemId: 4, pricingCostingItemId: 4, frozenLandedCostPerRequestedUnitThb: null, netUnitPrice: null }),
        ],
      });
      const state = (id) => screen.getByTestId(`pcr-ceo-row-${id}`).getAttribute('data-state');
      expect(ceoRow(8001).getByText('มีราคาแล้ว')).not.toBeNull();
      expect(state(8001)).toBe('ok');
      expect(ceoRow(8002).getByText('จากสต็อก')).not.toBeNull();
      expect(ceoRow(8003).getAllByText('ปรับเอง').length).toBeGreaterThan(0);
      expect(ceoRow(8004).getByText('ต้องระบุต้นทุนเอง')).not.toBeNull();
      expect(ceoRow(8004).queryByText('มีราคาแล้ว')).toBeNull();
      expect(state(8004)).toBe('blocked');
      // no cost at all: a red "–" and a quick ปรับต้นทุนเอง button in the cost cell
      expect(ceoRow(8004).getByTestId('pcr-ceo-cost-both-8004').textContent).toContain('–');
      expect(ceoRow(8004).getByRole('button', { name: 'ปรับต้นทุนเอง' })).not.toBeNull();

      fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-discount-8001'), { target: { value: '10' } });
      expect(ceoRow(8001).getByText('ยังไม่บันทึก')).not.toBeNull();
      expect(ceoRow(8001).queryByText('มีราคาแล้ว')).toBeNull();
      expect(state(8001)).toBe('dirty');
    });

    it('the quick ปรับต้นทุนเอง button in a no-cost row opens the same cost-override dialog', async () => {
      const uncostable = buildCostingItemWithOverride({ id: 1, uncostableReason: 'ไม่พบอัตราค่าขนส่ง' });
      await renderCeoNewForm({
        items: [nfItem({ pricingCostingItemId: 1, frozenLandedCostPerRequestedUnitThb: null, netUnitPrice: null })],
        costings: [buildCosting({ id: 601, items: [uncostable] })],
      });
      fireEvent.click(ceoRow(8001).getByRole('button', { name: 'ปรับต้นทุนเอง' }));
      expect(await screen.findByRole('dialog', { name: 'ปรับต้นทุนเอง' })).not.toBeNull();
    });

    it('history stays in the panel footer, collapsed until opened', async () => {
      const returned = buildDecision({ id: 7000, decisionVersionNo: 1, status: 'RETURNED', items: [nfItem({ id: 8000 })] });
      const current = buildDecision({ id: 7001, decisionVersionNo: 2, priceMode: 'NET', items: [nfItem({ listUnitPrice: 100, netUnitPrice: 100 })] });
      await renderCeoNewForm({ decisions: [returned, current] });
      const toggle = within(ceoPricingPanel()).getByRole('button', { name: /^ประวัติการพิจารณา \(2\)/ });
      expect(toggle.getAttribute('aria-expanded')).toBe('false');
      expect(screen.queryByText(/เวอร์ชัน 1(?!\d)/)).toBeNull();
      fireEvent.click(toggle);
      expect(toggle.getAttribute('aria-expanded')).toBe('true');
      expect(screen.getByText(/เวอร์ชัน 1(?!\d)/)).not.toBeNull();
    });
  });

  describe('details row', () => {
    it('the toggle (aria-label "รายละเอียด <name>", aria-expanded) opens สินค้า / ต้นทุน / ราคาที่ได้; the spec and Import\'s price moved here from the card', async () => {
      const quote = buildFactoryQuote();
      const costingItem = buildCostingItemWithOverride({ id: 1 });
      await renderCeoNewForm({
        items: [nfItem({ pricingCostingItemId: 1, listUnitPrice: 72, netUnitPrice: 72 })],
        factoryQuotes: [{ ...quote, items: [{ ...quote.items[0], rawUnitPrice: 148 }] }],
        costings: [buildCosting({ id: 601, items: [costingItem] })],
      });
      // closed by default (nothing blocks): neither the spec nor Import's price is on the row
      expect(screen.queryByTestId('pcr-import-price-1')).toBeNull();
      expect(screen.queryByText(/ขาว/)).toBeNull();
      const toggle = ceoTable().getByRole('button', { name: 'รายละเอียด SCG A1' });
      expect(toggle.getAttribute('aria-expanded')).toBe('false');

      fireEvent.click(toggle);
      expect(toggle.getAttribute('aria-expanded')).toBe('true');
      const details = within(ceoTable().getByTestId('pcr-ceo-details-8001'));
      const product = within(details.getByRole('region', { name: 'สินค้า' }));
      expect(product.getByText(/ขาว/)).not.toBeNull();
      expect(product.getByText(/SCG Ceramics/)).not.toBeNull();
      expect(product.getByTestId('pcr-import-price-1').textContent).toContain('148');
      expect(within(details.getByRole('region', { name: 'ต้นทุน' })).getByTestId('pcr-ceo-cost-override-8001')).not.toBeNull();
      const result = details.getByRole('region', { name: 'ราคาที่ได้' }).textContent;
      expect(result).toContain('72.00'); // net / แผ่น
      expect(result).toContain('200.00'); // per ตร.ม.
      expect(result).toContain('1,440.00'); // line total
      expect(result).toContain('240.00'); // profit

      fireEvent.click(toggle);
      expect(toggle.getAttribute('aria-expanded')).toBe('false');
      expect(ceoTable().queryByTestId('pcr-ceo-details-8001')).toBeNull();
    });

    it('รายละเอียด shows the full cost calculation, step by step (owner request 2026-11-06)', async () => {
      api.pricingFormulaConfig = { get: vi.fn().mockResolvedValue({ formulaConfig: { sellingBuffer: 1.07 } }) };
      try {
        // DL-2026-0325 row 1: 3,910.03 of a 7,820.04 ตร.ม. Equipe shipment = 10 containers, half each.
        const costingItem = {
          id: 1, factoryName: 'Equipe', factoryQuoteRevisionNo: 1, landedCostPerUnitThb: 14.1544,
          rawUnitPrice: 14.283, rawCurrency: 'EUR', rawUnit: 'ตร.ม.', fxRate: 37.9944, sqmPerUnit: 0.014706,
          goodsCostThb: 7.9806, normalizedQuantityPieces: 265880, calculationConfigVersion: 2,
          calculationSnapshot: JSON.stringify({
            formula: 'V109', itemQtySqm: '3910.0312800000', dutyPct: '0.300000', costBuffer: '1.070000',
            insuranceValueFactor: '1.150000', insuranceRate: '0.004500', insuranceBuffer: '1.070000',
            freightAmountThbForGroup: '1000000.0000', freightGroupQtySqm: '7820.0362800000', freightShareThb: '500001.6803',
            clearanceAmountThbForShipment: '200000.0000', shipmentQtySqm: '7820.0362800000', clearanceShareThb: '100000.3361',
            containerSqm: '800', freightFullContainers: '10', freightRemainderSqm: '0', clearanceFullContainers: '10', clearanceRemainderSqm: '0',
          }),
        };
        await renderCeoNewForm({
          priceMode: 'SPECIAL_SQM',
          items: [nfItem({ pricingCostingItemId: 1, proposedMarginPct: 0.3, frozenLandedCostPerRequestedUnitThb: 14.1544 })],
          costings: [buildCosting({ id: 601, items: [costingItem] })],
        });
        await waitFor(() => expect(api.pricingFormulaConfig.get).toHaveBeenCalled());
        fireEvent.click(ceoTable().getByRole('button', { name: 'รายละเอียด SCG A1' }));

        const calc = await ceoTable().findByTestId('pcr-ceo-calc-8001');
        await waitFor(() => expect(calc.textContent).toContain('1,338.83'));
        const text = calc.textContent;
        expect(text).toContain('7.9806 บาท/แผ่น × 265,880 แผ่น');
        expect(text).toContain('2,121,881.93'); // C
        expect(text).toContain('11,749.39'); // i
        expect(text).toContain('100,000.00 × 10 ตู้'); // F per container
        expect(text).toContain('500,001.68');
        expect(text).toContain('100,000.34'); // S
        expect(text).toContain('3,763,383.84'); // TC
        expect(text).toContain('962.49'); // ทุน/ตร.ม.
        expect(text).toContain('962.49 × กำไร 1.3 × VAT 1.07');
      } finally {
        delete api.pricingFormulaConfig;
      }
    });
  });

  describe('margin: typed and shown as a PERCENT, stored as a fraction', () => {
    it('stored fractions display as percents without float noise (0.3 -> 30, 0.29 -> 29)', async () => {
      await renderCeoNewForm({
        requestItems: requestItemsN(2),
        items: [
          nfItem({ id: 8001, pricingRequestItemId: 1, proposedMarginPct: 0.3 }),
          nfItem({ id: 8002, pricingRequestItemId: 2, proposedMarginPct: 0.29 }),
        ],
      });
      expect(ceoRow(8001).getByTestId('pcr-ceo-margin-input-8001').value).toBe('30');
      expect(ceoRow(8002).getByTestId('pcr-ceo-margin-input-8002').value).toBe('29');
      // displaying a stored value is not an edit
      expect(screen.queryByTestId('pcr-ceo-save-all')).toBeNull();
    });

    it('typing 35 drives the LIVE formula with 0.35 and saves exactly marginPct 0.35 (not 35, not 0.35000000000000003)', async () => {
      api.pricingFormulaConfig = { get: vi.fn().mockResolvedValue({ formulaConfig: { sellingBuffer: 1 } }) };
      try {
        await renderCeoNewForm();
        await waitFor(() => expect(api.pricingFormulaConfig.get).toHaveBeenCalled());
        fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-margin-input-8001'), { target: { value: '35' } });
        // cost 60 x (1 + 0.35) x 1 = 81.00; as a fraction 35 it would read 2,160
        await waitFor(() => expect(ceoRow(8001).getByTestId('pcr-ceo-formula-list-8001').textContent).toContain('81.00'));
        fireEvent.click(saveAllButton());
        await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalled());
        expect(lastSavedDecisionItem().marginPct).toBe(0.35);
      } finally {
        delete api.pricingFormulaConfig;
      }
    });
  });

  describe('one save', () => {
    it('saves every dirty row through the existing per-item save, ONE call per row, sequentially; a failing row stays dirty with its error toasted, the others clear', async () => {
      const showToast = vi.fn();
      await renderCeoNewForm({
        showToast,
        requestItems: requestItemsN(2),
        items: [
          nfItem({ id: 8001, pricingRequestItemId: 1, listUnitPrice: 100, netUnitPrice: 100 }),
          nfItem({ id: 8002, pricingRequestItemId: 2, listUnitPrice: 100, netUnitPrice: 100 }),
        ],
      });
      let releaseFirst;
      const first = new Promise((resolve) => { releaseFirst = resolve; });
      api.pricingRequests.updatePricingDecision
        .mockImplementationOnce(() => first)
        .mockRejectedValueOnce(new Error('บันทึกราคาไม่สำเร็จ ทดสอบ'));

      fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-discount-8001'), { target: { value: '10' } });
      fireEvent.change(ceoRow(8002).getByTestId('pcr-ceo-discount-8002'), { target: { value: '15' } });
      expect(saveAllButton().textContent).toContain('บันทึกราคา (2 รายการ)');

      fireEvent.click(saveAllButton());
      await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalledTimes(1));
      // sequential: the second row is NOT sent while the first is still in flight
      await act(async () => { await new Promise((resolve) => setTimeout(resolve, 40)); });
      expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalledTimes(1);
      await act(async () => { releaseFirst({}); });
      await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalledTimes(2));

      const [firstCall, secondCall] = api.pricingRequests.updatePricingDecision.mock.calls;
      expect(firstCall[0]).toBe(7001);
      expect(firstCall[1].items).toHaveLength(1);
      expect(firstCall[1].items[0]).toEqual(expect.objectContaining({ pricingDecisionItemId: 8001, discountPct: 10 }));
      expect(secondCall[1].items).toHaveLength(1);
      expect(secondCall[1].items[0]).toEqual(expect.objectContaining({ pricingDecisionItemId: 8002, discountPct: 15 }));

      await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', 'บันทึกราคาไม่สำเร็จ ทดสอบ'));
      // row 1 saved -> draft cleared back to the stored value; row 2 failed -> still dirty with its draft
      await waitFor(() => expect(ceoRow(8001).queryByText('ยังไม่บันทึก')).toBeNull());
      expect(ceoRow(8001).getByTestId('pcr-ceo-discount-8001').value).toBe('');
      expect(ceoRow(8002).getByText('ยังไม่บันทึก')).not.toBeNull();
      expect(ceoRow(8002).getByTestId('pcr-ceo-discount-8002').value).toBe('15');
      expect(saveAllButton().textContent).toContain('บันทึกราคา (1 รายการ)');
      expect(screen.getByTestId('pcr-ceo-approve').disabled).toBe(true);
    });

    it('the scoped actions ล้างส่วนลด and ใช้ราคาตามสูตร stay per row and do not run the batch save', async () => {
      await renderCeoNewForm({
        requestItems: requestItemsN(2),
        items: [
          nfItem({ id: 8001, pricingRequestItemId: 1, listUnitPrice: 100, discountPct: 5, netUnitPrice: 95 }),
          nfItem({ id: 8002, pricingRequestItemId: 2, listUnitPrice: 100, netUnitPrice: 100 }),
        ],
      });
      fireEvent.change(ceoRow(8002).getByTestId('pcr-ceo-discount-8002'), { target: { value: '20' } });
      fireEvent.click(ceoRow(8001).getByTestId('pcr-ceo-clear-discount-8001'));
      await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalledTimes(1));
      expect(lastSavedDecisionItem()).toEqual(expect.objectContaining({ pricingDecisionItemId: 8001, clearDiscountPct: true }));
      // row 2's pending discount was not committed by it
      expect(ceoRow(8002).getByTestId('pcr-ceo-discount-8002').value).toBe('20');
    });
  });

  describe('mobile (< 720px): cards instead of the table', () => {
    it('renders the table, one card per item and the bottom bar together, each hidden by a Tailwind class (jsdom has no media queries)', async () => {
      await renderCeoNewForm({ requestItems: requestItemsN(2), items: [
        nfItem({ id: 8001, pricingRequestItemId: 1, listUnitPrice: 100, netUnitPrice: 100 }),
        nfItem({ id: 8002, pricingRequestItemId: 2, listUnitPrice: 100, netUnitPrice: 100 }),
      ] });
      expect(screen.getByTestId('pcr-ceo-table-wrap').className).toMatch(/hidden/);
      expect(screen.getByTestId('pcr-ceo-cards').className).toMatch(/hidden/);
      expect(screen.getByTestId('pcr-ceo-mobile-bar').className).toMatch(/hidden/);
      expect(screen.getByTestId('pcr-ceo-cards').querySelectorAll('[data-testid^="pcr-ceo-card-"]')).toHaveLength(2);
    });

    it('a card has labelled 44px inputs and the 3-figure strip (สุทธิ/แผ่น, รวม, กำไร %); typing there feeds the same draft, and the bottom bar saves it', async () => {
      await renderCeoNewForm();
      const bar = within(screen.getByTestId('pcr-ceo-mobile-bar'));
      // nothing dirty: the bar offers ตีกลับ + อนุมัติราคาขาย, not บันทึก
      expect(bar.getByTestId('pcr-ceo-mobile-return')).not.toBeNull();
      expect(bar.queryByTestId('pcr-ceo-mobile-save')).toBeNull();
      expect(bar.getByTestId('pcr-ceo-mobile-approve')).not.toBeNull();

      const card = ceoCard(8001);
      const margin = card.getByLabelText(/^กำไร %/);
      expect(margin.value).toBe('20');
      expect(margin.className).toMatch(/44px|h-11/);
      expect(card.getByLabelText(/^ส่วนลด %/).className).toMatch(/44px|h-11/);
      expect(card.getByText('สุทธิ/แผ่น')).not.toBeNull();
      expect(card.getByText('รวม')).not.toBeNull();
      expect(card.getByText('กำไร')).not.toBeNull();

      fireEvent.change(margin, { target: { value: '30' } });
      // the table's input is the same draft
      expect(ceoRow(8001).getByTestId('pcr-ceo-margin-input-8001').value).toBe('30');
      expect(bar.getByTestId('pcr-ceo-mobile-save').textContent).toContain('บันทึก (1)');
      expect(bar.queryByTestId('pcr-ceo-mobile-return')).toBeNull();
      expect(bar.getByTestId('pcr-ceo-mobile-approve').disabled).toBe(true);

      fireEvent.click(bar.getByTestId('pcr-ceo-mobile-save'));
      await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalledWith(
        7001, { items: [expect.objectContaining({ pricingDecisionItemId: 8001, marginPct: 0.3 })] }));
    });
  });
});

// ── CEO workspace redesign: Opus review fixes ────────────────────────────────────────────────────
describe('CEO pricing workspace redesign — review fixes', () => {
  const heldUpdate = () => {
    let release;
    const held = new Promise((resolve) => { release = resolve; });
    api.pricingRequests.updatePricingDecision.mockImplementationOnce(() => held);
    return () => act(async () => { release({}); });
  };

  describe('1. start review inside the workspace (no dead end after a RETURNED new-form decision)', () => {
    it('a RETURNED new-form decision on a READY_FOR_CEO_REVIEW request still offers เริ่มพิจารณาราคาขาย, sending the default margin as a fraction', async () => {
      const request = buildRequest({ summary: { status: 'READY_FOR_CEO_REVIEW' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({
        items: [buildDecision({ status: 'RETURNED', priceMode: 'NET', items: [nfItem({ listUnitPrice: 100, netUnitPrice: 100 })] })],
      });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');
      // the redesigned workspace is on screen ...
      expect(screen.getByTestId('pcr-ceo-table')).not.toBeNull();
      // ... and the start control is inside the pricing panel
      const start = within(ceoPricingPanel()).getByTestId('pcr-ceo-start-review');
      fireEvent.click(start);
      await waitFor(() => expect(api.pricingRequests.startPricingDecision).toHaveBeenCalledWith(
        501, expect.objectContaining({ defaultMarginPct: 0.3 }),
      ));
    });
  });

  describe('2. save-all cannot send or wipe a stale value', () => {
    async function twoRowsDirty() {
      await renderCeoNewForm({
        requestItems: requestItemsN(1),
        items: [nfItem({ id: 8001, pricingRequestItemId: 1, listUnitPrice: 100, discountPct: 5, netUnitPrice: 95, manualSellingPricePerRequestedUnit: 100 })],
      });
      fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-discount-8001'), { target: { value: '10' } });
    }

    it('while save-all is in flight every price input, the mode buttons, ล้างส่วนลด / ใช้ราคาตามสูตร and save-all are disabled', async () => {
      await twoRowsDirty();
      const release = heldUpdate();
      fireEvent.click(saveAllButton());
      await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalledTimes(1));
      expect(ceoRow(8001).getByTestId('pcr-ceo-discount-8001').disabled).toBe(true);
      expect(ceoRow(8001).getByTestId('pcr-ceo-list-price-8001').disabled).toBe(true);
      expect(screen.getByTestId('pcr-ceo-price-mode-DIRECT_NET').disabled).toBe(true);
      expect(ceoRow(8001).getByTestId('pcr-ceo-clear-discount-8001').disabled).toBe(true);
      expect(ceoRow(8001).getByTestId('pcr-ceo-use-formula-price-8001').disabled).toBe(true);
      expect(saveAllButton().disabled).toBe(true);
      await release();
    });

    it('a double click sends each row once', async () => {
      await twoRowsDirty();
      const release = heldUpdate();
      fireEvent.click(saveAllButton());
      fireEvent.click(saveAllButton());
      await release();
      await act(async () => { await new Promise((resolve) => setTimeout(resolve, 40)); });
      expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalledTimes(1);
    });

    it('a draft changed after the send survives the success and stays dirty (only the sent value is dropped)', async () => {
      await twoRowsDirty();
      const release = heldUpdate();
      fireEvent.click(saveAllButton());
      await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalledTimes(1));
      expect(api.pricingRequests.updatePricingDecision.mock.calls[0][1].items[0].discountPct).toBe(10);
      fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-discount-8001'), { target: { value: '20' } });
      await release();
      await waitFor(() => expect(screen.queryByTestId('pcr-ceo-mobile-save')).not.toBeNull());
      expect(ceoRow(8001).getByTestId('pcr-ceo-discount-8001').value).toBe('20');
      expect(ceoRow(8001).getByText('ยังไม่บันทึก')).not.toBeNull();
    });
  });

  describe('3. start-review margin is unmistakably a percent', () => {
    const startReview = async () => {
      const request = buildRequest({ summary: { status: 'READY_FOR_CEO_REVIEW' } });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      return screen.findByLabelText('อัตรากำไรเริ่มต้น (%)');
    };

    it('is labelled (%), has a % suffix and the hint "เช่น 30 = 30%"', async () => {
      await startReview();
      expect(screen.getByText('เช่น 30 = 30%')).not.toBeNull();
      expect(screen.getByTestId('pcr-ceo-start-margin-suffix').textContent).toBe('%');
    });

    it('refuses a fraction typed by habit (0.3): inline alert, start disabled, nothing sent', async () => {
      const input = await startReview();
      fireEvent.change(input, { target: { value: '0.3' } });
      expect(screen.getByRole('alert').textContent).toContain('กรอกเป็น % เช่น 30');
      const start = screen.getByTestId('pcr-ceo-start-review');
      expect(start.disabled).toBe(true);
      fireEvent.click(start);
      expect(api.pricingRequests.startPricingDecision).not.toHaveBeenCalled();
    });

    it('30 sends the fraction 0.3', async () => {
      const input = await startReview();
      fireEvent.change(input, { target: { value: '30' } });
      expect(screen.queryByRole('alert')).toBeNull();
      fireEvent.click(screen.getByTestId('pcr-ceo-start-review'));
      await waitFor(() => expect(api.pricingRequests.startPricingDecision).toHaveBeenCalledWith(
        501, expect.objectContaining({ defaultMarginPct: 0.3 }),
      ));
    });
  });

  describe('4. an invalid margin is explained, in the row and in the card', () => {
    it('-150 % shows the range alert under the margin input (table and mobile card)', async () => {
      await renderCeoNewForm({ items: [nfItem()] });
      fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-margin-input-8001'), { target: { value: '-150' } });
      expect(ceoRow(8001).getByRole('alert').textContent).toContain('อัตรากำไรต้องอยู่ระหว่าง');
      expect(ceoCard(8001).getByRole('alert').textContent).toContain('อัตรากำไรต้องอยู่ระหว่าง');
    });
  });

  describe('5. information that must not be lost from the new-form CEO view', () => {
    it('the header carries the GLA-125 terms the old ภาพรวม panel showed', async () => {
      const request = buildRequest({ summary: {
        status: 'CEO_REVIEWING', paymentTermMode: 'CREDIT', creditDays: 30, validityDays: 15, unitCode: 'U-9', omitContactHonorific: true,
      } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({ items: [buildDecision({ priceMode: 'NET', items: [nfItem()] })] });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');
      const header = screen.getByTestId('pcr-ceo-header').textContent;
      expect(header).toContain('เงื่อนไขการชำระเงิน: เครดิต 30 วัน');
      expect(header).toContain('ยืนราคา: 15 วัน');
      expect(header).toContain('หน่วยงาน / รหัสผู้ออกแบบ: U-9');
      expect(header).toContain('หน้าชื่อผู้รับ: ใช่');
    });

    it('the decision status badge sits beside the decision code in the panel heading', async () => {
      await renderCeoNewForm();
      const heading = within(ceoPricingPanel()).getByRole('heading', { level: 2 });
      expect(heading.textContent).toContain(pricingDecisionStatusLabel('DRAFT').label);
    });

    it('details สินค้า shows รหัสสินค้า, the sales-entered โรงงาน, ขายแผ่นไม่เต็มกล่อง and เทียบ ตร.ม.', async () => {
      const base = buildRequest().items[0];
      await renderCeoNewForm({
        requestItems: [{ ...base, roundToFullBox: false, requestedQtySqm: 7.2 }],
        items: [nfItem({ listUnitPrice: 72, netUnitPrice: 72 })],
      });
      openRowDetails('SCG A1');
      const product = within(ceoTable().getByTestId('pcr-ceo-details-8001')).getByRole('region', { name: 'สินค้า' }).textContent;
      expect(product).toContain('รหัสสินค้า: SCG-A1');
      expect(product).toContain('โรงงาน (ที่ฝ่ายขายกรอก): SCG');
      expect(product).toContain('ขายแผ่นไม่เต็มกล่อง: ใช่');
      expect(product).toContain('เทียบ ตร.ม.: 7.2 ตร.ม.');
    });

    it('the missing-factory warning still shows for the CEO', async () => {
      const request = buildRequestWithBlankFactoryLine({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({ items: [buildDecision({ priceMode: 'NET', items: [nfItem({ listUnitPrice: 100, netUnitPrice: 100 })] })] });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');
      expect(screen.getByText(/ยังไม่ได้ระบุโรงงาน 1 รายการ/)).not.toBeNull();
    });

    it('a request item with no decision item still appears as a row: name, qty, "ไม่อยู่ในมติราคานี้", no inputs', async () => {
      await renderCeoNewForm({
        requestItems: requestItemsN(2),
        items: [nfItem({ id: 8001, pricingRequestItemId: 1, listUnitPrice: 100, netUnitPrice: 100 })],
      });
      const row = within(screen.getByTestId('pcr-ceo-row-req-2'));
      expect(row.getByText('SCG A2')).not.toBeNull();
      expect(row.getByText(/20 แผ่น/)).not.toBeNull();
      expect(row.getByText('ไม่อยู่ในมติราคานี้')).not.toBeNull();
      expect(screen.getByTestId('pcr-ceo-row-req-2').querySelectorAll('input')).toHaveLength(0);
    });
  });

  describe('6. minor', () => {
    it('negative profit is red, not green (summary and row)', async () => {
      await renderCeoNewForm({ items: [nfItem({ listUnitPrice: 50, netUnitPrice: 50 })] }); // cost 60, net 50
      expect(ceoSummary().getByTestId('pcr-ceo-sum-profit').querySelector('b').className).toMatch(/danger/);
      expect(ceoSummary().getByTestId('pcr-ceo-sum-profit').querySelector('b').className).not.toMatch(/success/);
      expect(ceoRow(8001).getByTestId('pcr-ceo-profit-8001').querySelector('b').className).toMatch(/danger/);
    });

    it('save-all shows ONE success toast with the count, not one per row', async () => {
      const showToast = vi.fn();
      await renderCeoNewForm({
        showToast,
        requestItems: requestItemsN(2),
        items: [
          nfItem({ id: 8001, pricingRequestItemId: 1, listUnitPrice: 100, netUnitPrice: 100 }),
          nfItem({ id: 8002, pricingRequestItemId: 2, listUnitPrice: 100, netUnitPrice: 100 }),
        ],
      });
      fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-discount-8001'), { target: { value: '10' } });
      fireEvent.change(ceoRow(8002).getByTestId('pcr-ceo-discount-8002'), { target: { value: '15' } });
      fireEvent.click(saveAllButton());
      await waitFor(() => expect(showToast).toHaveBeenCalledWith('success', 'บันทึกราคาแล้ว 2 รายการ'));
      expect(showToast.mock.calls.filter((c) => c[0] === 'success')).toHaveLength(1);
    });

    it('the details column header has the sr-only name "รายละเอียด"', async () => {
      await renderCeoNewForm();
      expect(ceoTable().getByRole('columnheader', { name: 'รายละเอียด' })).not.toBeNull();
    });
  });

  describe('7. non-THB decision', () => {
    it('summary cost and profit convert the net with the decision fx rate (cost 60 THB, net 2 USD at 35 → ฿10/unit × 20 = ฿200, 16.7%)', async () => {
      await renderCeoNewForm({
        decisions: [buildDecision({
          priceMode: 'NET', currency: 'USD', fxRateUsed: 35,
          items: [nfItem({ listUnitPrice: 2, netUnitPrice: 2 })],
        })],
      });
      expect(ceoSummary().getByTestId('pcr-ceo-sum-cost').textContent).toContain('1,200.00');
      const profit = ceoSummary().getByTestId('pcr-ceo-sum-profit').textContent;
      expect(profit).toContain('200.00');
      expect(profit).toContain('16.7%');
    });
  });
});

describe('CEO margin % input: a fraction typed by habit is warned about, never blocked', () => {
  const WARNING = '= 0.3% — ถ้าตั้งใจ 30% ให้กรอก 30';

  it('0.3 shows the non-blocking warning in the table row and the mobile card; save-all stays enabled and saves marginPct 0.003', async () => {
    await renderCeoNewForm({ items: [nfItem()] });
    fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-margin-input-8001'), { target: { value: '0.3' } });
    const rowWarning = ceoRow(8001).getByText(WARNING);
    expect(rowWarning.getAttribute('role')).toBeNull();
    expect(rowWarning.className).toMatch(/warning-dark/);
    expect(ceoCard(8001).getByText(WARNING)).not.toBeNull();
    expect(ceoRow(8001).queryByRole('alert')).toBeNull();
    expect(saveAllButton().disabled).toBe(false);
    fireEvent.click(saveAllButton());
    await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalled());
    expect(lastSavedDecisionItem().marginPct).toBe(0.003);
  });

  it.each(['30', '1'])('%s shows no warning', async (value) => {
    await renderCeoNewForm({ items: [nfItem()] });
    fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-margin-input-8001'), { target: { value } });
    expect(ceoRow(8001).queryByText(/ถ้าตั้งใจ/)).toBeNull();
    expect(ceoCard(8001).queryByText(/ถ้าตั้งใจ/)).toBeNull();
  });
});

// ── CEO pricing workspace: second review round ───────────────────────────────────────────────────
describe('CEO pricing workspace redesign — review round 2', () => {
  const heldUpdate = () => {
    let release;
    const held = new Promise((resolve) => { release = resolve; });
    api.pricingRequests.updatePricingDecision.mockImplementationOnce(() => held);
    return () => act(async () => { release({}); });
  };
  const settle = () => act(async () => { await new Promise((resolve) => setTimeout(resolve, 40)); });
  const twoItems = (a = {}, b = {}) => [
    nfItem({ id: 8001, pricingRequestItemId: 1, listUnitPrice: 100, netUnitPrice: 100, ...a }),
    nfItem({ id: 8002, pricingRequestItemId: 2, listUnitPrice: 100, netUnitPrice: 100, ...b }),
  ];

  describe('1. a draft equal to the saved value is not dirty', () => {
    it('typing a margin away and back to the saved 20 leaves no ยังไม่บันทึก, no save button, approve enabled', async () => {
      await renderCeoNewForm({ items: [nfItem({ listUnitPrice: 100, netUnitPrice: 100 })] });
      const margin = () => ceoRow(8001).getByTestId('pcr-ceo-margin-input-8001');
      fireEvent.change(margin(), { target: { value: '30' } });
      expect(ceoRow(8001).getByText('ยังไม่บันทึก')).not.toBeNull();
      expect(screen.getByTestId('pcr-ceo-approve').disabled).toBe(true);
      fireEvent.change(margin(), { target: { value: '20' } });
      expect(ceoRow(8001).queryByText('ยังไม่บันทึก')).toBeNull();
      expect(screen.queryByTestId('pcr-ceo-save-all')).toBeNull();
      expect(screen.getByTestId('pcr-ceo-approve').disabled).toBe(false);
    });

    it('typing a different margin is dirty and blocks approve', async () => {
      await renderCeoNewForm({ items: [nfItem({ listUnitPrice: 100, netUnitPrice: 100 })] });
      fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-margin-input-8001'), { target: { value: '25' } });
      expect(ceoRow(8001).getByText('ยังไม่บันทึก')).not.toBeNull();
      expect(saveAllButton().textContent).toContain('บันทึกราคา (1 รายการ)');
      expect(screen.getByTestId('pcr-ceo-approve').disabled).toBe(true);
    });

    it('a SPECIAL_SQM formula prefill is still pending (it must be saved to price the line) but carries its own tag', async () => {
      await renderCeoNewForm({ priceMode: 'SPECIAL_SQM', items: [nfItem()] });
      expect(ceoRow(8001).getByText('ใช้ราคาตามสูตร — ยังไม่บันทึก')).not.toBeNull();
      expect(ceoRow(8001).queryByText('ยังไม่บันทึก')).toBeNull();
      expect(saveAllButton().textContent).toContain('บันทึกราคา (1 รายการ)');
      fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-special-sqm-8001'), { target: { value: '1350' } });
      expect(ceoRow(8001).getByText('ยังไม่บันทึก')).not.toBeNull();
    });
  });

  describe('2. save-all refetches once', () => {
    it('two dirty rows refetch the decision query once after the loop, not once per row', async () => {
      await renderCeoNewForm({ requestItems: requestItemsN(2), items: twoItems() });
      fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-discount-8001'), { target: { value: '10' } });
      fireEvent.change(ceoRow(8002).getByTestId('pcr-ceo-discount-8002'), { target: { value: '15' } });
      await settle();
      const before = api.pricingRequests.listPricingDecisions.mock.calls.length;
      fireEvent.click(saveAllButton());
      await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalledTimes(2));
      await settle();
      expect(api.pricingRequests.listPricingDecisions.mock.calls.length - before).toBe(1);
    });

    it('still refetches once when one row fails', async () => {
      await renderCeoNewForm({ requestItems: requestItemsN(2), items: twoItems() });
      fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-discount-8001'), { target: { value: '10' } });
      fireEvent.change(ceoRow(8002).getByTestId('pcr-ceo-discount-8002'), { target: { value: '15' } });
      await settle();
      const before = api.pricingRequests.listPricingDecisions.mock.calls.length;
      api.pricingRequests.updatePricingDecision.mockRejectedValue(new Error('boom'));
      fireEvent.click(saveAllButton());
      await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalledTimes(2));
      await settle();
      expect(api.pricingRequests.listPricingDecisions.mock.calls.length - before).toBe(1);
    });
  });

  describe('3. the summary equals what the rows show', () => {
    it('typing a discount moves the summary total, cost-line profit and profit % before saving (list 100, cost 60, qty 20)', async () => {
      await renderCeoNewForm({ items: [nfItem({ listUnitPrice: 100, netUnitPrice: 100 })] });
      expect(ceoSummary().getByTestId('pcr-ceo-sum-total').textContent).toContain('2,000.00');
      fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-discount-8001'), { target: { value: '10' } });
      // net 90 -> total 1,800; profit (90 - 60) x 20 = 600 = 50 % of cost 1,200
      expect(ceoSummary().getByTestId('pcr-ceo-sum-total').textContent).toContain('1,800.00');
      expect(ceoSummary().getByTestId('pcr-ceo-sum-cost').textContent).toContain('1,200.00');
      const profit = ceoSummary().getByTestId('pcr-ceo-sum-profit').textContent;
      expect(profit).toContain('600.00');
      expect(profit).toContain('50.0%');
    });
  });

  describe('4. a configured default under 1 % is not a typed fraction', () => {
    afterEach(() => { delete api.pricingFormulaConfig; });
    it('formula config defaultMarginPct 0.005 shows "0.5", enables start, shows no alert and sends 0.005', async () => {
      api.pricingFormulaConfig = { get: vi.fn().mockResolvedValue({ formulaConfig: { defaultMarginPct: 0.005 } }) };
      const request = buildRequest({ summary: { status: 'READY_FOR_CEO_REVIEW' } });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      const input = await screen.findByLabelText('อัตรากำไรเริ่มต้น (%)');
      await waitFor(() => expect(input.value).toBe('0.5'));
      expect(screen.queryByRole('alert')).toBeNull();
      expect(screen.getByTestId('pcr-ceo-start-review').disabled).toBe(false);
      fireEvent.click(screen.getByTestId('pcr-ceo-start-review'));
      await waitFor(() => expect(api.pricingRequests.startPricingDecision).toHaveBeenCalledWith(
        501, expect.objectContaining({ defaultMarginPct: 0.005 }),
      ));
    });
  });

  describe('5. ล้างส่วนลด cannot double-fire', () => {
    const rowWithBoth = () => nfItem({
      listUnitPrice: 100, discountPct: 5, netUnitPrice: 142.5, manualSellingPricePerRequestedUnit: 150,
    });
    it('while its own clear is pending the button is disabled and a second click sends nothing', async () => {
      await renderCeoNewForm({ items: [rowWithBoth()] });
      const release = heldUpdate();
      fireEvent.click(ceoRow(8001).getByTestId('pcr-ceo-clear-discount-8001'));
      await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalledTimes(1));
      expect(ceoRow(8001).getByTestId('pcr-ceo-clear-discount-8001').disabled).toBe(true);
      fireEvent.click(ceoRow(8001).getByTestId('pcr-ceo-clear-discount-8001'));
      expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalledTimes(1);
      await release();
    });

    it('while ใช้ราคาตามสูตร is pending on the same row, ล้างส่วนลด is disabled too', async () => {
      await renderCeoNewForm({ items: [rowWithBoth()] });
      const release = heldUpdate();
      fireEvent.click(ceoRow(8001).getByTestId('pcr-ceo-use-formula-price-8001'));
      await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalledTimes(1));
      expect(ceoRow(8001).getByTestId('pcr-ceo-clear-discount-8001').disabled).toBe(true);
      fireEvent.click(ceoRow(8001).getByTestId('pcr-ceo-clear-discount-8001'));
      expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalledTimes(1);
      await release();
    });
  });

  describe('6. mode not chosen: ONE request-level blocker', () => {
    it('a 3-row decision with no mode shows exactly one blocker button, that scrolls to the mode picker, and no per-row mode blocker', async () => {
      await renderCeoNewForm({
        priceMode: null,
        requestItems: requestItemsN(3),
        items: [1, 2, 3].map((n) => nfItem({ id: 8000 + n, pricingRequestItemId: n, listUnitPrice: 100, netUnitPrice: 100 })),
      });
      const blockers = within(screen.getByTestId('pcr-ceo-blockers'));
      expect(blockers.getAllByRole('button')).toHaveLength(1);
      expect(blockers.getByRole('button', { name: 'เลือกวิธีกรอกราคาก่อน' })).not.toBeNull();
      expect(screen.queryByRole('button', { name: /รายการที่ \d SCG A\d — เลือกวิธีกรอกราคาก่อน/ })).toBeNull();
      expect(screen.getByTestId('pcr-ceo-approve').disabled).toBe(true);
      const group = within(ceoPricingPanel()).getByRole('group', { name: 'วิธีกรอกราคา' });
      group.scrollIntoView = vi.fn();
      fireEvent.click(blockers.getByRole('button', { name: 'เลือกวิธีกรอกราคาก่อน' }));
      expect(group.scrollIntoView).toHaveBeenCalled();
    });
  });

  describe('7 + 8. during save-all: cost changes are locked and the saving row shows progress', () => {
    async function savingAll() {
      await renderCeoNewForm({
        requestItems: requestItemsN(2),
        items: twoItems({}, { frozenLandedCostPerRequestedUnitThb: null, listUnitPrice: null, netUnitPrice: null }),
      });
      fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-discount-8001'), { target: { value: '10' } });
      fireEvent.change(ceoRow(8002).getByTestId('pcr-ceo-discount-8002'), { target: { value: '10' } });
      openRowDetails('SCG A1');
      const release = heldUpdate();
      return release;
    }

    it('recalculate cost and every cost-override button are enabled normally, disabled while save-all is in flight', async () => {
      const release = await savingAll();
      expect(screen.getByTestId('pcr-ceo-recalculate-cost').disabled).toBe(false);
      expect(ceoTable().getByTestId('pcr-ceo-cost-override-8001').disabled).toBe(false);
      expect(ceoTable().getByTestId('pcr-ceo-cost-override-quick-8002').disabled).toBe(false);
      fireEvent.click(saveAllButton());
      await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalledTimes(1));
      expect(screen.getByTestId('pcr-ceo-recalculate-cost').disabled).toBe(true);
      expect(ceoTable().getByTestId('pcr-ceo-cost-override-8001').disabled).toBe(true);
      expect(ceoTable().getByTestId('pcr-ceo-cost-override-8002').disabled).toBe(true);
      expect(ceoTable().getByTestId('pcr-ceo-cost-override-quick-8002').disabled).toBe(true);
      await release();
    });

    it('the row being saved shows the saving state; the other row does not', async () => {
      const release = await savingAll();
      expect(ceoRow(8001).queryByText('กำลังบันทึก…')).toBeNull();
      fireEvent.click(saveAllButton());
      await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalledTimes(1));
      expect(ceoRow(8001).getByText('กำลังบันทึก…')).not.toBeNull();
      expect(ceoRow(8002).queryByText('กำลังบันทึก…')).toBeNull();
      await release();
    });
  });

  describe('9. one approve gate for the new-form and the legacy path', () => {
    it('a line with no cost and no override disables approve on both paths', async () => {
      await renderCeoNewForm({ items: [nfItem({ frozenLandedCostPerRequestedUnitThb: null, listUnitPrice: 72, netUnitPrice: 72 })] });
      expect(screen.getByTestId('pcr-ceo-approve').disabled).toBe(true);
      cleanup();

      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      api.pricingRequests.listPricingDecisions.mockResolvedValue({
        items: [buildDecision({ items: [buildDecisionItem({ frozenLandedCostPerRequestedUnitThb: null })] })],
      });
      renderDetailPage({ user: ceoUser, request });
      await waitForLoaded(request);
      await screen.findByText('PCD-2026-0001');
      expect(screen.getByRole('button', { name: 'อนุมัติราคาขาย' }).disabled).toBe(true);
    });
  });
});

// ══════════════════════════════════════════════════════════════════════════════════════════════
// Import PCR redesign (import role, /pricing-requests/:id) — Phase A tests. UI-LEVEL ONLY.
// Target: header + progress strip + unassigned-factory callout + ONE factory section whose cards are
// header / terms row / table (spec toggle) / footer. The read-only item-card panel and the ภาพรวม
// panel are not rendered for import. EVERY backend call, payload and gate is unchanged (canConfirm,
// editable, contacted, canMarkContacted, windows, the dirty/receive-skip rule, locked terms): the
// payload/gate tests above stay as they were, and the cases here pin only the new surface plus the
// "cannot reach" cases (CEO/Sales unchanged; locked before contact; confirm absent where
// canConfirm is false; เจรจา → the same endpoint).
// ══════════════════════════════════════════════════════════════════════════════════════════════
describe('Import PCR redesign — factory-first page (import role)', () => {
  const SCG = 'SCG Ceramics';
  const COTTO = 'Cotto Industry';
  const stripOf = () => within(screen.getByRole('region', { name: 'ความคืบหน้าการขอราคา' }));
  const fig = (key) => stripOf().getByTestId(`pcr-import-fig-${key}`).textContent.trim();
  const nextStep = () => stripOf().getByTestId('pcr-import-next-step').textContent.trim();
  const nextSub = () => stripOf().getByTestId('pcr-import-next-sub').textContent.trim();
  const segs = () => stripOf().getAllByTestId('pcr-import-progress-seg').map((el) => el.getAttribute('data-state'));
  const card = (id) => within(screen.getByTestId(`pcr-factory-card-${id}`));

  const item = (over = {}) => crItem({ boxes: 5, requestedQtySqm: 7.2, ...over });
  const twoFactoryRequest = (summary = {}) => ({
    ...buildRequest({
      summary: { requestedByName: 'คุณขาย ตัวอย่าง', assignedImportName: 'คุณนำเข้า ตัวอย่าง', ...summary },
      items: [
        item({ id: 1, resolvedFactoryName: SCG }),
        item({ id: 2, brand: 'COTTO', model: 'B2', productDescription: 'กระเบื้องผนัง COTTO B2', resolvedFactoryName: COTTO }),
      ],
    }),
    events: crRequest().events,
  });
  const line = (id, itemId, over = {}) => ({ ...crQuote().items[0], id: id * 10 + itemId, pricingRequestItemId: itemId, ...over });
  const fq = (id, name, itemId, over = {}) => crQuote({ id, factoryName: name, factoryId: 500 + id, items: [line(id, itemId)], ...over });
  const contacted = (id, name, itemId, over = {}) => fq(id, name, itemId, { status: 'REQUESTED', contactedOn: '2026-10-01', contactedBy: 3, ...over });
  const confirmed = (id, name, itemId, over = {}) => contacted(id, name, itemId, {
    status: 'READY_FOR_COSTING', items: [line(id, itemId, { rawUnitPrice: 12.5 })], ...over,
  });

  async function renderTwo({ q1 = fq(91, SCG, 1), q2 = fq(92, COTTO, 2), request = twoFactoryRequest(), user = importUser } = {}) {
    renderDetailPage({ user, request, factoryQuotes: [q1, q2] });
    await waitForLoaded(request);
    await screen.findByTestId('pcr-factory-card-92');
    return request;
  }

  afterEach(() => { delete api.fxRates; });

  describe('header (replaces ภาพรวม)', () => {
    it('shows the PCR code, status badge, customer and ONE meta line: ดีล · ผู้รับ · ต้องการภายใน · ฝ่ายขาย · ผู้รับเรื่อง', async () => {
      const request = await renderTwo();
      const header = within(screen.getByTestId('pcr-import-header'));
      // the h1's accessible name stays EXACTLY the code (waitForLoaded depends on it): the badge is a sibling
      expect(header.getByRole('heading', { level: 1, name: request.summary.requestCode })).not.toBeNull();
      expect(header.getByText(pricingRequestStatusLabel('IMPORT_REVIEWING').label)).not.toBeNull();
      expect(header.getByText('บริษัท ทดสอบ จำกัด')).not.toBeNull();
      expect(header.getByRole('link', { name: 'PR-2026-0701' }).getAttribute('href')).toBe('/import/deals/701');
      expect(header.getByText(/^ผู้รับ:/).textContent).toContain('ผู้ออกแบบ ก.');
      expect(header.getByText(/ต้องการภายใน/).textContent).toContain(formatThaiDate('2026-08-01'));
      expect(header.getByText(/^ฝ่ายขาย:/).textContent).toContain('คุณขาย ตัวอย่าง');
      expect(header.getByText(/^ผู้รับเรื่อง:/).textContent).toContain('คุณนำเข้า ตัวอย่าง');
      // the role description is NOT a person, and the ภาพรวม panel is gone
      expect(screen.queryByText(/ผู้รับเรื่องและประสานราคาโรงงาน/)).toBeNull();
      expect(screen.queryByText('ภาพรวม')).toBeNull();
    });

    it('omits ฝ่ายขาย / ผู้รับเรื่อง when the data does not carry them (never the role description in their place)', async () => {
      await renderTwo({ request: twoFactoryRequest({ requestedByName: null, assignedImportName: null }) });
      const header = within(screen.getByTestId('pcr-import-header'));
      expect(header.getByRole('link', { name: 'PR-2026-0701' })).not.toBeNull(); // positive control
      expect(header.queryByText(/^ฝ่ายขาย:/)).toBeNull();
      expect(header.queryByText(/^ผู้รับเรื่อง:/)).toBeNull();
      expect(screen.queryByText(/ผู้รับเรื่องและประสานราคาโรงงาน/)).toBeNull();
    });
  });

  describe('progress strip (section "ความคืบหน้าการขอราคา")', () => {
    it('figures: โรงงาน N · ติดต่อแล้ว n/N · ยืนยันราคาแล้ว n/N · สินค้า count', async () => {
      await renderTwo({ q1: contacted(91, SCG, 1), q2: fq(92, COTTO, 2) }); // SCG contacted, Cotto still DRAFT
      for (const label of ['โรงงาน', 'ติดต่อแล้ว', 'ยืนยันราคาแล้ว', 'สินค้า']) {
        expect(stripOf().getByText(label)).not.toBeNull();
      }
      expect(fig('factories')).toBe('2');
      expect(fig('contacted')).toBe('1/2');
      expect(fig('confirmed')).toBe('0/2');
      expect(fig('items')).toBe('2');
    });

    it('confirmed = READY_FOR_COSTING and NOT dirty: editing a confirmed factory\'s price takes it out of the count', async () => {
      await renderTwo({ q1: confirmed(91, SCG, 1), q2: contacted(92, COTTO, 2) });
      expect(fig('contacted')).toBe('2/2');
      expect(fig('confirmed')).toBe('1/2');
      expect(segs()).toEqual(['confirmed', 'contacted']);

      fireEvent.change(card(91).getByLabelText(/^ราคาที่เสนอ/), { target: { value: '99' } });
      expect(fig('confirmed')).toBe('0/2');
      expect(segs()).toEqual(['contacted', 'contacted']);
    });

    it('the segmented bar has one segment per factory: none / contacted / confirmed', async () => {
      await renderTwo({ q1: fq(91, SCG, 1), q2: confirmed(92, COTTO, 2) });
      expect(segs()).toEqual(['none', 'confirmed']);
    });

    it('the สินค้า figure counts unassigned lines but the โรงงาน figure counts only named factories', async () => {
      const request = buildRequestWithBlankFactoryLine();
      renderDetailPage({ user: importUser, request, factoryQuotes: [buildFactoryQuote({ status: 'REQUESTED' })] });
      await waitForLoaded(request);
      expect(fig('factories')).toBe('1');
      expect(fig('items')).toBe('2');
    });

    // "ขั้นต่อไป" — ONE plain sentence, in this precedence.
    it('ขั้นต่อไป 1/4: an unassigned item comes first, even before a factory that is not contacted', async () => {
      const request = buildRequestWithBlankFactoryLine();
      renderDetailPage({ user: importUser, request, factoryQuotes: [buildFactoryQuote()] }); // DRAFT = not contacted
      await waitForLoaded(request);
      expect(nextStep()).toBe('ระบุโรงงานให้ "กระเบื้องนำเข้าพิเศษ" ก่อน');
    });

    it('ขั้นต่อไป 2/4: the first factory not contacted — and the sub-line says the request goes to the CEO automatically once every factory is confirmed', async () => {
      await renderTwo({ q1: contacted(91, SCG, 1), q2: fq(92, COTTO, 2) });
      expect(nextStep()).toBe(`ติดต่อ ${COTTO}`);
      expect(nextSub()).toContain('ส่งถึง CEO อัตโนมัติ');
      expect(nextSub()).not.toBe('ยืนยันโรงงานนี้แล้วคำขอนี้จะส่งถึง CEO อัตโนมัติ'); // two are left
    });

    it('ขั้นต่อไป 3/4: all contacted → the first not confirmed; with exactly ONE left the sub-line is the single-factory sentence', async () => {
      await renderTwo({ q1: contacted(91, SCG, 1), q2: contacted(92, COTTO, 2) });
      expect(nextStep()).toBe(`กรอกราคาและยืนยัน ${SCG}`);
      expect(nextSub()).not.toBe('ยืนยันโรงงานนี้แล้วคำขอนี้จะส่งถึง CEO อัตโนมัติ');
      cleanup();

      await renderTwo({ q1: confirmed(91, SCG, 1), q2: contacted(92, COTTO, 2) });
      expect(nextStep()).toBe(`กรอกราคาและยืนยัน ${COTTO}`);
      expect(nextSub()).toBe('ยืนยันโรงงานนี้แล้วคำขอนี้จะส่งถึง CEO อัตโนมัติ');
    });

    // "ส่งถึง CEO แล้ว" describes the REQUEST (the server decides, on more than the factories), so it
    // follows the request's own status, not "all factories confirmed".
    it('ขั้นต่อไป 4/4 (a): every factory confirmed but the request still AWAITING_FACTORY_RESPONSE → NOT ส่งถึง CEO แล้ว', async () => {
      await renderTwo({
        q1: confirmed(91, SCG, 1), q2: confirmed(92, COTTO, 2),
        request: twoFactoryRequest({ status: 'AWAITING_FACTORY_RESPONSE' }),
      });
      expect(nextStep()).toBe('ยืนยันราคาครบแล้ว แต่ยังไม่ส่งถึง CEO');
      expect(nextSub()).toBe('ระบบจะส่งถึง CEO เมื่อครบเงื่อนไข');
    });

    it('ขั้นต่อไป 4/4 (b): the request itself is past import (READY_FOR_CEO_REVIEW) → ส่งถึง CEO แล้ว', async () => {
      await renderTwo({
        q1: confirmed(91, SCG, 1), q2: confirmed(92, COTTO, 2),
        request: twoFactoryRequest({ status: 'READY_FOR_CEO_REVIEW' }),
      });
      expect(nextStep()).toBe('ส่งถึง CEO แล้ว');
    });
  });

  describe('unassigned-factory callout, section order, item cards gone', () => {
    const blankRequest = () => buildRequestWithBlankFactoryLine();

    it('a warning callout at the TOP holds the existing ImportFactoryPicker for the unassigned item', async () => {
      const request = blankRequest();
      renderDetailPage({ user: importUser, request, factoryQuotes: [buildFactoryQuote({ status: 'REQUESTED' })] });
      await waitForLoaded(request);

      const callout = screen.getByTestId('pcr-import-unassigned');
      expect(callout.getAttribute('role')).toBe('status'); // persistent, not an alert
      expect(within(callout).getByLabelText('ระบุโรงงาน').id).toBe('pcr-item-factory-2');
      expect(within(callout).getByRole('button', { name: 'บันทึกโรงงาน' })).not.toBeNull();
      // the picker is not duplicated anywhere else on the page
      expect(screen.getAllByLabelText('ระบุโรงงาน')).toHaveLength(1);
    });

    it('shows no callout when every line names a factory, and none once the request has left Import\'s hands (canSetItemFactory)', async () => {
      await renderTwo();
      expect(screen.getByTestId('pcr-import-header')).not.toBeNull(); // positive control: the redesigned page is up
      expect(screen.queryByTestId('pcr-import-unassigned')).toBeNull();
      cleanup();

      const late = buildRequestWithBlankFactoryLine({ summary: { status: 'READY_FOR_CEO_REVIEW' } });
      renderDetailPage({ user: importUser, request: late });
      await waitForLoaded(late);
      expect(screen.getByTestId('pcr-import-header')).not.toBeNull();
      expect(screen.queryByTestId('pcr-import-unassigned')).toBeNull();
    });

    it('page order: header → progress strip → callout → "ขอราคาจากโรงงาน" section → attachments; สร้างเมลให้ทุกโรงงาน sits in the section', async () => {
      const request = blankRequest();
      renderDetailPage({ user: importUser, request }); // no quotes: preview cards + the bulk button
      await waitForLoaded(request);

      const section = await screen.findByRole('region', { name: 'ขอราคาจากโรงงาน' });
      expect(within(section).getByRole('heading', { level: 2, name: 'ขอราคาจากโรงงาน' })).not.toBeNull();
      expect(within(section).getByRole('button', { name: 'สร้างเมลให้ทุกโรงงาน' })).not.toBeNull();
      const order = [
        screen.getByTestId('pcr-import-header'),
        screen.getByRole('region', { name: 'ความคืบหน้าการขอราคา' }),
        screen.getByTestId('pcr-import-unassigned'),
        section,
        screen.getByRole('heading', { name: 'ไฟล์แนบประกอบคำขอราคา' }),
      ];
      for (let i = 0; i < order.length - 1; i += 1) {
        expect(order[i].compareDocumentPosition(order[i + 1]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      }
    });

    it('the read-only item-card panel is NOT rendered for import (none of its headings or lines), and the old "รายการสินค้า (N รายการ)" panel title is gone', async () => {
      await renderTwo();
      expect(screen.getByRole('region', { name: 'ขอราคาจากโรงงาน' })).not.toBeNull(); // positive control
      expect(screen.queryByRole('heading', { name: 'รายการสินค้าและราคาตั้งต้น' })).toBeNull();
      expect(screen.queryByRole('heading', { name: /^รายการสินค้า \(/ })).toBeNull();
      expect(screen.queryByText(/^จำนวนสั่งซื้อ:/)).toBeNull();
      expect(screen.queryByText(/^โรงงาน \(ที่ฝ่ายขายกรอก\):/)).toBeNull();
      expect(screen.queryByTestId('pcr-import-price-1')).toBeNull();
    });
  });

  describe('factory card — header, steps, terms row', () => {
    it('header: factory name, origin · n รายการ, and the 3-step indicator (ติดต่อโรงงาน → กรอกราคา → ยืนยันราคาเสนอ) with an aria-label', async () => {
      renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [crQuote()] });
      await waitForLoaded();
      await screen.findByTestId('pcr-factory-card-91');

      const head = within(screen.getByTestId('pcr-factory-head-91'));
      expect(head.getByText(SCG)).not.toBeNull();
      expect(screen.getByTestId('pcr-factory-head-91').textContent).toContain('ไทย-สต็อก');
      expect(screen.getByTestId('pcr-factory-head-91').textContent).toContain('1 รายการ');
      const steps = screen.getByTestId('pcr-factory-steps-91');
      expect(steps.getAttribute('aria-label')).toMatch(/^ขั้นตอนโรงงานนี้/);
      expect(steps.textContent).toMatch(/ติดต่อโรงงาน.*กรอกราคา.*ยืนยันราคาเสนอ/);
    });

    it('steps follow the gate state: not contacted [now,todo,todo] → contacted [done,now,todo] → confirmed [done,done,done]', async () => {
      const { unmount } = renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [crQuote()] });
      await waitForLoaded();
      await screen.findByTestId('pcr-factory-card-91');
      expect(stepStates(91)).toEqual(['now', 'todo', 'todo']);
      unmount();

      const second = renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [contactedQuote()] });
      await waitForLoaded();
      await screen.findByTestId('pcr-factory-card-91');
      expect(stepStates(91)).toEqual(['done', 'now', 'todo']);
      second.unmount();

      renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [contactedQuote({ status: 'READY_FOR_COSTING' })] });
      await waitForLoaded();
      await screen.findByTestId('pcr-factory-card-91');
      expect(stepStates(91)).toEqual(['done', 'done', 'done']);
    });

    it('a contacted factory\'s header reads ติดต่อแล้ว <date> and keeps the contact note visible; the mail button reads ดูเมลที่ส่ง and ติดต่อโรงงานแล้ว is gone', async () => {
      renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [contactedQuote()] });
      await waitForLoaded();
      await screen.findByTestId('pcr-factory-card-91');

      const head = within(screen.getByTestId('pcr-factory-head-91'));
      expect(screen.getByTestId('pcr-factory-status-91').textContent).toContain('ติดต่อแล้ว 1 ต.ค.');
      expect(head.getByRole('button', { name: 'ดูเมลที่ส่ง' })).not.toBeNull();
      expect(head.queryByRole('button', { name: 'ติดต่อโรงงานแล้ว' })).toBeNull();
      expect(screen.getByText(/โทรคุณ Marco/)).not.toBeNull();
    });

    it('a factory not yet contacted has สร้างเมล + ติดต่อโรงงานแล้ว IN its header and no contacted line', async () => {
      renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [crQuote()] });
      await waitForLoaded();
      await screen.findByTestId('pcr-factory-card-91');
      const head = within(screen.getByTestId('pcr-factory-head-91'));
      expect(head.getByRole('button', { name: 'สร้างเมล' })).not.toBeNull();
      expect(head.getByRole('button', { name: 'ติดต่อโรงงานแล้ว' })).not.toBeNull();
      expect(screen.queryByTestId('pcr-factory-status-91')).toBeNull();
    });

    it('terms row: the lock reason appears ONCE per card (not under each of the two inputs), and every price input is still disabled', async () => {
      const request = crRequest({ items: [crItem({ id: 1 }), crItem({ id: 2, model: 'A2' })] });
      const quote = crQuote({ items: [line(91, 1), line(91, 2)] }); // DRAFT = not contacted
      renderDetailPage({ user: importUser, request, factoryQuotes: [quote] });
      await waitForLoaded(request);
      await screen.findByTestId('pcr-factory-card-91');

      const reason = 'ช่องราคาจะเปิดหลังกด "ติดต่อโรงงานแล้ว"';
      expect(within(screen.getByTestId('pcr-factory-terms-row-91')).getByTestId('pcr-await-contact-91').textContent).toContain(reason);
      expect(card(91).getAllByText(/ช่องราคาจะเปิดหลังกด/)).toHaveLength(1);
      expect(card(91).queryAllByText(/กรอกได้หลังติดต่อโรงงาน/)).toHaveLength(0);
      const inputs = card(91).getAllByLabelText(/^ราคาที่เสนอ/);
      expect(inputs).toHaveLength(2);
      inputs.forEach((input) => expect(input.disabled).toBe(true));
    });

    it('terms row: once contacted there is no lock reason, and the prices unlock', async () => {
      renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [contactedQuote()] });
      await waitForLoaded();
      await screen.findByTestId('pcr-factory-card-91');
      expect(screen.getByTestId('pcr-factory-terms-row-91')).not.toBeNull();
      expect(screen.queryByText(/ช่องราคาจะเปิดหลังกด/)).toBeNull();
      expect(card(91).getByLabelText(/^ราคาที่เสนอ/).disabled).toBe(false);
    });

    it('terms row holds the read-only currency line and, for EUR, the FX note "อัตรา 40.00 บาท (ธปท.)" when a rate exists', async () => {
      api.fxRates = { list: vi.fn().mockResolvedValue({ fxRates: [{ currency: 'EUR', rateToThb: 40 }, { currency: 'THB', rateToThb: 1 }] }) };
      renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [contactedQuote()] });
      await waitForLoaded();
      const row = within(await screen.findByTestId('pcr-factory-terms-row-91'));
      expect(row.getByTestId('pcr-factory-currency-91').textContent).toContain('ตามคำขอราคา');
      expect((await row.findByTestId('pcr-factory-fx-91')).textContent).toMatch(/อัตรา 40\.00 บาท \(ธปท\.\)/);
    });

    it('terms row: a THB / legacy factory has no FX note and no currency / unit select', async () => {
      api.fxRates = { list: vi.fn().mockResolvedValue({ fxRates: [{ currency: 'EUR', rateToThb: 40 }] }) };
      renderDetailPage({ user: importUser, request: buildRequest(), factoryQuotes: [buildFactoryQuote({ status: 'REQUESTED' })] });
      await waitForLoaded();
      const row = within(await screen.findByTestId('pcr-factory-terms-row-91'));
      expect(row.queryByRole('combobox')).toBeNull();
      expect(row.getByTestId('pcr-factory-currency-91')).not.toBeNull();
      expect(row.queryByTestId('pcr-factory-fx-91')).toBeNull();
    });
  });

  describe('factory card — table, spec row, price cell', () => {
    const richRequest = () => crRequest({ items: [crItem({ boxes: 5, requestedQtySqm: 7.2 })] });

    it('table columns: สินค้า · จำนวน · ราคาที่โรงงานเสนอ · ระยะเวลานำเข้า · ราคาที่ยืนยันแล้ว', async () => {
      renderDetailPage({ user: importUser, request: richRequest(), factoryQuotes: [contactedQuote()] });
      await waitForLoaded();
      const table = within(await screen.findByTestId('pcr-factory-card-91')).getByRole('table');
      expect(within(table).getAllByRole('columnheader').map((th) => th.textContent.trim())).toEqual(
        ['สินค้า', 'จำนวน', 'ราคาที่โรงงานเสนอ', 'ระยะเวลานำเข้า', 'ราคาที่ยืนยันแล้ว'],
      );
    });

    it('a row shows name · variant · รหัส, the quantity as แผ่น + ตร.ม. + กล่อง, the lead time, and labelled cells (mobile stacked block)', async () => {
      renderDetailPage({ user: importUser, request: richRequest(), factoryQuotes: [contactedQuote()] });
      await waitForLoaded();
      await screen.findByTestId('pcr-factory-card-91');

      const row = screen.getByTestId('pcr-factory-row-1');
      expect(row.textContent).toContain('SCG A1');
      expect(row.textContent).toContain('60x60 · ขาว · ด้าน');
      expect(row.textContent).toContain('รหัส SCG-A1');
      const cell = (label) => row.querySelector(`[data-label="${label}"]`);
      expect(cell('จำนวน').textContent).toContain('20 แผ่น');
      expect(cell('จำนวน').textContent).toContain('7.2 ตร.ม.');
      expect(cell('จำนวน').textContent).toContain('5 กล่อง');
      expect(cell('ราคาที่โรงงานเสนอ').querySelector('input')).not.toBeNull();
      expect(cell('ระยะเวลานำเข้า').textContent).toContain('75–90 วัน');
      expect(cell('ราคาที่ยืนยันแล้ว')).not.toBeNull();
      // the lead-time change action is still ONE per card, inside the card
      expect(card(91).getByTestId('pcr-lt-open-91').textContent).toContain('ขอเปลี่ยน');
    });

    it('"สเปกจากฝ่ายขาย" toggles an inline spec row with the old item card\'s fields (closed by default, aria-expanded)', async () => {
      renderDetailPage({ user: importUser, request: richRequest(), factoryQuotes: [contactedQuote()] });
      await waitForLoaded();
      await screen.findByTestId('pcr-factory-card-91');

      const toggle = screen.getByRole('button', { name: /สเปกจากฝ่ายขาย/ });
      expect(toggle.getAttribute('aria-expanded')).toBe('false');
      expect(screen.queryByTestId('pcr-spec-1')).toBeNull();

      fireEvent.click(toggle);
      expect(toggle.getAttribute('aria-expanded')).toBe('true');
      const spec = within(screen.getByTestId('pcr-spec-1'));
      expect(specValue(spec, 'สี')).toBe('ขาว');
      expect(specValue(spec, 'ผิว')).toBe('ด้าน');
      expect(specValue(spec, 'ขนาด')).toBe('60x60');
      expect(specValue(spec, 'ความหนา')).toBe('10 มม.');
      expect(specValue(spec, 'โรงงาน (ที่ฝ่ายขายกรอก)')).toBe('SCG');
      expect(specValue(spec, 'ประเทศต้นทาง')).toBe('ไทย-สต็อก');

      fireEvent.click(toggle);
      expect(screen.queryByTestId('pcr-spec-1')).toBeNull();
    });

    it('the price input is still the existing one (aria-label ราคาที่เสนอ …, typing sanitised) and on mobile is full width and 44px', async () => {
      renderDetailPage({ user: importUser, request: richRequest(), factoryQuotes: [contactedQuote()] });
      await waitForLoaded();
      await screen.findByTestId('pcr-factory-card-91');
      const price = card(91).getByLabelText(/^ราคาที่เสนอ/);
      expect(price.getAttribute('type')).toBe('text');
      expect(price.className).toMatch(/44px|h-11/);
      expect(price.className).toMatch(/w-full/);
    });

    it('a locked (not yet contacted) price input stays disabled and a click on the table cannot unlock it', async () => {
      renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [crQuote()] });
      await waitForLoaded();
      await screen.findByTestId('pcr-factory-card-91');
      expect(within(card(91).getByRole('table')).getByRole('columnheader', { name: 'ราคาที่โรงงานเสนอ' })).not.toBeNull(); // positive control: the new table
      const price = card(91).getByLabelText(/^ราคาที่เสนอ/);
      expect(price.disabled).toBe(true);
      fireEvent.change(price, { target: { value: '55' } });
      expect(price.value).toBe('');
      expect(card(91).queryByRole('button', { name: 'ยืนยันราคาเสนอ' })).toBeNull();
    });
  });

  describe('factory card — footer', () => {
    it('helper under the buttons, not contacted: "ติดต่อโรงงานก่อน จึงกรอกราคาและยืนยันได้" — and no confirm / เจรจา button (nothing disabled-in-place either)', async () => {
      renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [crQuote()] });
      await waitForLoaded();
      await screen.findByTestId('pcr-factory-card-91');
      expect(screen.getByTestId('pcr-factory-helper-91').textContent).toContain('ติดต่อโรงงานก่อน จึงกรอกราคาและยืนยันได้');
      expect(screen.queryByRole('button', { name: 'ยืนยันราคาเสนอ' })).toBeNull();
      expect(screen.queryByRole('button', { name: 'บันทึกว่ากำลังเจรจา' })).toBeNull();
    });

    it('helper when confirm is offered: "ยืนยันแล้วราคานี้ใช้คำนวณต้นทุน · …กำลังเจรจา… ใช้เมื่อยังต่อรองอยู่"; the buttons are 44px', async () => {
      renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [contactedQuote({ status: 'RESPONSE_RECEIVED' })] });
      await waitForLoaded();
      await screen.findByTestId('pcr-factory-card-91');
      const helper = screen.getByTestId('pcr-factory-helper-91').textContent;
      expect(helper).toContain('ยืนยันแล้วราคานี้ใช้คำนวณต้นทุน');
      expect(helper).toContain('"กำลังเจรจา" ใช้เมื่อยังต่อรองอยู่');
      expect(card(91).getByRole('button', { name: 'ยืนยันราคาเสนอ' }).className).toMatch(/44px|h-11/);
      expect(card(91).getByRole('button', { name: 'บันทึกว่ากำลังเจรจา' }).className).toMatch(/44px|h-11/);
    });

    // NO NEW GATE: canConfirm never required every price. The prototype's disabled-until-all-priced
    // button and "กรอกราคาให้ครบอีก n รายการจึงยืนยันได้" helper are therefore NOT built.
    it('no new gate: with an empty price a contacted factory still offers ยืนยันราคาเสนอ, shows no "กรอกราคาให้ครบ" helper, and confirming still sends the (null) price', async () => {
      const quote = contactedQuote({ items: [line(91, 1, { rawUnitPrice: null })] });
      renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [quote] });
      await waitForLoaded();
      await screen.findByTestId('pcr-factory-card-91');
      const confirm = card(91).getByRole('button', { name: 'ยืนยันราคาเสนอ' });
      expect(confirm.disabled).toBe(false);
      expect(screen.getByTestId('pcr-factory-helper-91').textContent).toContain('ยืนยันแล้วราคานี้ใช้คำนวณต้นทุน');
      // a NON-blocking count of what is still empty: it disables nothing
      expect(screen.getByTestId('pcr-factory-helper-91').textContent).toContain('ยังไม่กรอกราคา 1 รายการ');
      expect(screen.queryByText(/กรอกราคาให้ครบ/)).toBeNull();

      fireEvent.click(confirm);
      await waitFor(() => expect(api.pricingRequests.receiveFactoryQuote).toHaveBeenCalledWith(
        quote.id, expect.objectContaining({ items: [expect.objectContaining({ pricingRequestItemId: 1, rawUnitPrice: null })] }),
      ));
      await waitFor(() => expect(api.pricingRequests.markFactoryQuoteReady).toHaveBeenCalledWith(quote.id));
    });

    it('the ยังไม่กรอกราคา n รายการ helper counts the EMPTY drafts and goes away once every price is typed (never disables anything)', async () => {
      const request = crRequest({ items: [crItem({ id: 1 }), crItem({ id: 2, model: 'A2' })] });
      const quote = contactedQuote({ items: [line(91, 1, { rawUnitPrice: 12 }), line(91, 2, { rawUnitPrice: null })] });
      renderDetailPage({ user: importUser, request, factoryQuotes: [quote] });
      await waitForLoaded(request);
      await screen.findByTestId('pcr-factory-card-91');
      expect(screen.getByTestId('pcr-factory-helper-91').textContent).toContain('ยังไม่กรอกราคา 1 รายการ');
      fireEvent.change(card(91).getAllByLabelText(/^ราคาที่เสนอ/)[1], { target: { value: '13' } });
      expect(screen.getByTestId('pcr-factory-helper-91').textContent).not.toContain('ยังไม่กรอกราคา');
      expect(card(91).getByRole('button', { name: 'ยืนยันราคาเสนอ' }).disabled).toBe(false);
    });

    it('บันทึกว่ากำลังเจรจา (renamed เจรจา) calls the SAME endpoint as before — startFactoryNegotiation(quote.id, {note}) — and the old name is gone', async () => {
      const quote = contactedQuote({ status: 'RESPONSE_RECEIVED' });
      renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [quote] });
      await waitForLoaded();
      await screen.findByTestId('pcr-factory-card-91');
      expect(screen.queryByRole('button', { name: 'เจรจา' })).toBeNull();

      fireEvent.click(card(91).getByRole('button', { name: 'บันทึกว่ากำลังเจรจา' }));
      await waitFor(() => expect(api.pricingRequests.startFactoryNegotiation).toHaveBeenCalledWith(
        quote.id, { note: 'Negotiation in progress' },
      ));
      expect(api.pricingRequests.receiveFactoryQuote).not.toHaveBeenCalled();
      expect(api.pricingRequests.markFactoryQuoteReady).not.toHaveBeenCalled();
    });

    it('บันทึกว่ากำลังเจรจา is offered only where canNegotiate is (RESPONSE_RECEIVED): not on REQUESTED, NEGOTIATING or READY_FOR_COSTING', async () => {
      // positive control first, so the absences below cannot pass on a page that never had the button
      const first = renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [contactedQuote({ status: 'RESPONSE_RECEIVED' })] });
      await waitForLoaded();
      expect(await screen.findByRole('button', { name: 'บันทึกว่ากำลังเจรจา' })).not.toBeNull();
      first.unmount();
      for (const status of ['REQUESTED', 'NEGOTIATING', 'READY_FOR_COSTING']) {
        const { unmount } = renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [contactedQuote({ status })] });
        await waitForLoaded();
        await screen.findByTestId('pcr-factory-card-91');
        expect(screen.queryByRole('button', { name: 'บันทึกว่ากำลังเจรจา' }), status).toBeNull();
        unmount();
      }
    });

    it('after confirm the footer shows the success line "ยืนยันราคาเสนอแล้ว · n รายการ" and no confirm button; editing a price brings the button back and drops the line', async () => {
      renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [contactedQuote({ status: 'READY_FOR_COSTING' })] });
      await waitForLoaded();
      await screen.findByTestId('pcr-factory-card-91');
      expect(screen.getByTestId('pcr-factory-confirmed-91').textContent).toContain('ยืนยันราคาเสนอแล้ว · 1 รายการ');
      expect(screen.queryByRole('button', { name: 'ยืนยันราคาเสนอ' })).toBeNull();

      fireEvent.change(card(91).getByLabelText(/^ราคาที่เสนอ/), { target: { value: '48' } });
      expect(await screen.findByRole('button', { name: 'ยืนยันราคาเสนอ' })).not.toBeNull();
      expect(screen.queryByTestId('pcr-factory-confirmed-91')).toBeNull();
    });

    it('the footer keeps หมายเหตุราคา and the factory file attachment (existing controls)', async () => {
      renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [contactedQuote()] });
      await waitForLoaded();
      await screen.findByTestId('pcr-factory-card-91');
      const footer = within(screen.getByTestId('pcr-factory-footer-91'));
      expect(footer.getByLabelText('หมายเหตุราคา')).not.toBeNull();
      expect(footer.getByText('แนบไฟล์')).not.toBeNull();
    });
  });

  describe('preview cards (factory with no quote yet) are the same card', () => {
    it('a preview group renders as a card with the steps (contact = now), the table, its spec toggle and สร้างเมล / ติดต่อโรงงานแล้ว in the header', async () => {
      renderDetailPage({ user: importUser, request: crRequest() });
      await waitForLoaded();
      const group = within(await screen.findByTestId('pcr-price-preview-group'));
      expect(group.getByRole('table')).not.toBeNull();
      expect(group.getByRole('button', { name: /สเปกจากฝ่ายขาย/ })).not.toBeNull();
      expect(group.getByRole('button', { name: 'สร้างเมล' })).not.toBeNull();
      expect(group.getByRole('button', { name: 'ติดต่อโรงงานแล้ว' })).not.toBeNull();
      expect(within(screen.getByTestId('pcr-price-preview-group')).getAllByTestId('pcr-factory-step').map((el) => el.getAttribute('data-state')))
        .toEqual(['now', 'todo', 'todo']);
      expect(group.queryByRole('button', { name: 'ยืนยันราคาเสนอ' })).toBeNull();
    });
  });

  describe('stock lines stay visible to import', () => {
    it('a small "จากสต็อก · ไม่ต้องขอราคา (n รายการ)" card after the factory cards lists each stock line with the badge, and is not counted as a โรงงาน', async () => {
      const base = item({ id: 1, resolvedFactoryName: SCG });
      const request = buildRequest({ items: [base, { ...base, id: 2, model: 'S1', productDescription: 'กระเบื้อง S1 จากสต็อก', stockSource: 'IN_THAILAND', resolvedFactoryName: null }] });
      renderDetailPage({ user: importUser, request, factoryQuotes: [fq(91, SCG, 1, { status: 'REQUESTED' })] });
      await waitForLoaded(request);
      const stock = within(await screen.findByTestId('pcr-stock-card'));
      expect(stock.getByText(/จากสต็อก · ไม่ต้องขอราคา \(1 รายการ\)/)).not.toBeNull();
      expect(stock.getByText('SCG S1')).not.toBeNull();
      expect(stock.getByText('จากสต็อก')).not.toBeNull();
      expect(fig('factories')).toBe('1');
      const section = screen.getByRole('region', { name: 'ขอราคาจากโรงงาน' });
      expect(section.contains(screen.getByTestId('pcr-stock-card'))).toBe(true);
    });

    it('no stock card when there are no stock lines', async () => {
      await renderTwo();
      expect(screen.getByTestId('pcr-import-header')).not.toBeNull();
      expect(screen.queryByTestId('pcr-stock-card')).toBeNull();
    });
  });

  describe('price terms: locked currency, per-line unit switch, symbol prefix', () => {
    const legacyEur = (status = 'REQUESTED') => {
      const base = buildFactoryQuote();
      return buildFactoryQuote({ status, defaultCurrency: 'EUR', items: [{ ...base.items[0], currency: 'EUR' }] });
    };
    const render = async (quote, request = buildRequest()) => {
      renderDetailPage({ user: importUser, request, factoryQuotes: [quote] });
      await waitForLoaded(request);
      await screen.findByTestId('pcr-factory-card-91');
    };

    it('a legacy line has no currency select and shows the read-only code', async () => {
      await render(legacyEur());
      expect(screen.queryByRole('combobox', { name: /^สกุลเงิน/ })).toBeNull();
      expect(screen.getByTestId('pcr-factory-currency-91').textContent).toContain('EUR €');
    });

    it('a legacy line\'s unit switch offers exactly / แผ่น and / ตร.ม., with the current one pressed', async () => {
      await render(legacyEur());
      const group = screen.getByRole('group', { name: /^หน่วยราคา รายการ #1/ });
      const buttons = within(group).getAllByRole('button');
      expect(buttons.map((b) => b.textContent.trim())).toEqual(['/ แผ่น', '/ ตร.ม.']);
      expect(buttons[0].getAttribute('aria-pressed')).toBe('true'); // default item: PER_PIECE
      expect(buttons[1].getAttribute('aria-pressed')).toBe('false');
    });

    it('confirm sends the SAME currency as before for an untouched legacy draft (defaultCurrency + the line currency)', async () => {
      const quote = legacyEur();
      await render(quote);
      fireEvent.change(screen.getByLabelText(/^ราคาที่เสนอ/), { target: { value: '10' } });
      fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));
      await waitFor(() => expect(api.pricingRequests.receiveFactoryQuote).toHaveBeenCalledWith(
        quote.id,
        expect.objectContaining({ defaultCurrency: 'EUR', items: [expect.objectContaining({ currency: 'EUR', unitBasis: 'PER_PIECE' })] }),
      ));
    });

    it('currency parity: when defaultCurrency differs from a line\'s currency, confirm sends exactly the seeded values', async () => {
      const base = buildFactoryQuote();
      const quote = buildFactoryQuote({ status: 'REQUESTED', defaultCurrency: 'USD', items: [{ ...base.items[0], currency: 'EUR' }] });
      await render(quote);
      fireEvent.change(screen.getByLabelText(/^ราคาที่เสนอ/), { target: { value: '10' } });
      fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));
      await waitFor(() => expect(api.pricingRequests.receiveFactoryQuote).toHaveBeenCalled());
      const payload = api.pricingRequests.receiveFactoryQuote.mock.calls[0][1];
      expect(payload.defaultCurrency).toBe('USD');
      expect(payload.items[0].currency).toBe('EUR');
    });

    it('the price input carries a currency symbol prefix (€ for EUR, ฿ for THB, the code for an unknown one) and an aria-label with currency and unit', async () => {
      await render(legacyEur());
      expect(screen.getByTestId('pcr-quote-symbol-91-1').textContent).toBe('€');
      const price = screen.getByLabelText(/^ราคาที่เสนอ รายการ #1/);
      expect(price.getAttribute('aria-label')).toContain('EUR');
      expect(price.getAttribute('aria-label')).toContain('แผ่น');
      expect(price.className).toMatch(/text-right/);
      expect(price.className).toMatch(/44px|h-11/);
      expect(price.className).toMatch(/text-base/);
      cleanup();

      await render(buildFactoryQuote({ status: 'REQUESTED' })); // THB
      expect(screen.getByTestId('pcr-quote-symbol-91-1').textContent).toBe('฿');
      cleanup();

      const base = buildFactoryQuote();
      await render(buildFactoryQuote({ status: 'REQUESTED', defaultCurrency: 'XYZ', items: [{ ...base.items[0], currency: 'XYZ' }] }));
      expect(screen.getByTestId('pcr-quote-symbol-91-1').textContent).toBe('XYZ');
    });

    it('a line with no currency of its own shows the fallback code in the equivalent line (not a blank)', async () => {
      const base = buildFactoryQuote();
      const quote = buildFactoryQuote({ status: 'REQUESTED', defaultCurrency: 'EUR', items: [{ ...base.items[0], currency: '', unitBasis: 'PER_SQM', quotedUnit: 'ตร.ม.' }] });
      await render(quote);
      fireEvent.change(screen.getByLabelText(/^ราคาที่เสนอ/), { target: { value: '100' } });
      expect(screen.getByTestId('pcr-quote-equiv-91-1').textContent).toBe('= 36 EUR/แผ่น');
    });

    it('mixed currencies: the locked pill says so instead of showing only a lock icon', async () => {
      const request = crRequest({ items: [crItem({ id: 1 }), crItem({ id: 2, model: 'A2', requestedCurrency: 'USD' })] });
      await render(contactedQuote({ items: [line(91, 1), line(91, 2, { currency: 'USD' })] }), request);
      expect(screen.getByTestId('pcr-factory-currency-91').textContent).toContain('หลายสกุลเงิน — ดูในแต่ละรายการ');
    });

    it('header terms: only printedByDisplayId / salesRepDisplayId set → no terms row (it never shows them)', async () => {
      const request = buildRequest({ summary: { printedByDisplayId: 5, salesRepDisplayId: 6 } });
      await render(buildFactoryQuote({ status: 'REQUESTED' }), request);
      expect(screen.getByTestId('pcr-import-header')).not.toBeNull();
      expect(screen.queryByText(/เงื่อนไขการชำระเงิน/)).toBeNull();
    });

    it('inputs stay locked before contact: disabled, no unit switch, no symbol prefix to type into', async () => {
      await render(legacyEur('DRAFT'));
      expect(screen.getByLabelText(/^ราคาที่เสนอ/).disabled).toBe(true);
      expect(screen.queryByRole('group', { name: /^หน่วยราคา รายการ/ })).toBeNull();
      expect(screen.queryByRole('button', { name: '/ ตร.ม.' })).toBeNull();
    });

    it('lines of one factory with DIFFERENT locked currencies: the header line stays generic and each row shows its own code', async () => {
      const request = crRequest({ items: [crItem({ id: 1 }), crItem({ id: 2, model: 'A2', requestedCurrency: 'USD' })] });
      const quote = contactedQuote({ items: [line(91, 1), line(91, 2, { currency: 'USD' })] });
      await render(quote, request);
      const header = screen.getByTestId('pcr-factory-currency-91').textContent;
      expect(header).toContain('ตามคำขอราคา');
      expect(header).not.toMatch(/EUR|USD/);
      expect(screen.getByTestId('pcr-quote-symbol-91-1').textContent).toBe('€');
      expect(screen.getByTestId('pcr-quote-symbol-91-2').textContent).toBe('$');
      expect(screen.getByTestId('pcr-quote-currency-91-2').textContent).toContain('USD');
    });
  });

  describe('review fixes: strip states, a11y, stock detail', () => {
    it('a CANCELLED / SUPERSEDED request never says ส่งถึง CEO แล้ว — it says the request is dead, with no action sub-line', async () => {
      await renderTwo({ q1: confirmed(91, SCG, 1), q2: confirmed(92, COTTO, 2), request: twoFactoryRequest({ status: 'CANCELLED' }) });
      expect(nextStep()).toBe('คำขอนี้ถูกยกเลิกแล้ว');
      expect(nextSub()).toBe('');
      cleanup();
      await renderTwo({ q1: confirmed(91, SCG, 1), q2: confirmed(92, COTTO, 2), request: twoFactoryRequest({ status: 'SUPERSEDED' }) });
      expect(nextStep()).toBe('คำขอนี้ถูกแทนด้วยรอบใหม่');
      expect(nextSub()).toBe('');
    });

    it('a factory that declined (NOT_AVAILABLE) is not an actionable step: ขั้นต่อไป names it, and its steps show a distinct declined state with text', async () => {
      await renderTwo({ q1: contacted(91, SCG, 1), q2: contacted(92, COTTO, 2, { status: 'NOT_AVAILABLE' }) });
      expect(nextStep()).toBe(`${COTTO} แจ้งไม่พร้อมเสนอราคา`);
      expect(nextStep()).not.toMatch(/กรอกราคาและยืนยัน/);
      expect(nextSub()).not.toBe('');
      expect(stepStates(92)).toEqual(['done', 'declined', 'todo']);
      expect(screen.getByTestId('pcr-factory-steps-92').textContent).toContain('แจ้งไม่พร้อมเสนอราคา');
    });

    it('missing factory but canSetItemFactory is false (no picker rendered): ขั้นต่อไป states the read-only fact, never "ระบุโรงงานให้ … ก่อน"', async () => {
      const request = buildRequestWithBlankFactoryLine({ summary: { status: 'READY_FOR_CEO_REVIEW' } });
      renderDetailPage({ user: importUser, request, factoryQuotes: [buildFactoryQuote({ status: 'REQUESTED' })] });
      await waitForLoaded(request);
      expect(screen.queryByLabelText('ระบุโรงงาน')).toBeNull();
      expect(nextStep()).toBe('ยังไม่ได้ระบุโรงงาน 1 รายการ — ฝ่ายนำเข้าระบุไม่ได้ในขั้นนี้');
    });

    it('a11y: the steps are a labelled group, and the table keeps explicit table semantics', async () => {
      renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [contactedQuote()] });
      await waitForLoaded();
      const c = await screen.findByTestId('pcr-factory-card-91');
      expect(within(c).getByRole('group', { name: /^ขั้นตอนโรงงานนี้/ })).not.toBeNull();
      const table = c.querySelector('table');
      expect(table.getAttribute('role')).toBe('table');
      expect(table.querySelector('thead').getAttribute('role')).toBe('rowgroup');
      expect(table.querySelector('tbody').getAttribute('role')).toBe('rowgroup');
      table.querySelectorAll('tr').forEach((tr) => expect(tr.getAttribute('role')).toBe('row'));
      table.querySelectorAll('th').forEach((th) => expect(th.getAttribute('role')).toBe('columnheader'));
      table.querySelectorAll('td').forEach((td) => expect(td.getAttribute('role')).toBe('cell'));
    });

    it('stock card rows show boxes and ตร.ม. when available', async () => {
      const base = item({ id: 1, resolvedFactoryName: SCG });
      const request = buildRequest({ items: [base, { ...base, id: 2, model: 'S1', stockSource: 'IN_THAILAND', resolvedFactoryName: null, boxes: 3, requestedQtySqm: 4.32 }] });
      renderDetailPage({ user: importUser, request, factoryQuotes: [fq(91, SCG, 1, { status: 'REQUESTED' })] });
      await waitForLoaded(request);
      const stock = (await screen.findByTestId('pcr-stock-card')).textContent;
      expect(stock).toContain('3 กล่อง');
      expect(stock).toContain('4.32 ตร.ม.');
    });
  });

  describe('review round 3', () => {
    const unitGroup = (n) => within(screen.getByRole('group', { name: new RegExp(`^หน่วยราคา รายการ #${n}`) }));
    const renderOne = async (quote, request = buildRequest()) => {
      renderDetailPage({ user: importUser, request, factoryQuotes: [quote] });
      await waitForLoaded(request);
      await screen.findByTestId('pcr-factory-card-91');
    };

    it('1. a legacy line saved per กล่อง (request per แผ่น) keeps / กล่อง as a pressed option of the unit switch', async () => {
      const base = buildFactoryQuote();
      await renderOne(buildFactoryQuote({ status: 'REQUESTED', items: [{ ...base.items[0], unitBasis: 'PER_BOX', quotedUnit: 'กล่อง' }] }));
      const buttons = unitGroup(1).getAllByRole('button');
      expect(buttons.map((b) => b.textContent.trim())).toEqual(['/ แผ่น', '/ ตร.ม.', '/ กล่อง']);
      expect(buttons[2].getAttribute('aria-pressed')).toBe('true');
    });

    it('2. read-only price text of a line with an empty currency shows the fallback code, not a blank', async () => {
      const base = buildFactoryQuote();
      const quote = buildFactoryQuote({ status: 'REQUESTED', defaultCurrency: 'EUR', items: [{ ...base.items[0], currency: '', rawUnitPrice: 12.5 }] });
      await renderOne(quote, buildRequest({ summary: { status: 'CANCELLED' } }));
      const cell = screen.getByTestId('pcr-quote-price-cell-91-1').textContent;
      expect(cell).toContain('12.5 EUR');
    });

    it('2b. the approved price of a confirmed line with an empty currency shows the fallback code', async () => {
      const base = buildFactoryQuote();
      const quote = buildFactoryQuote({ status: 'READY_FOR_COSTING', defaultCurrency: 'EUR', items: [{ ...base.items[0], currency: '', rawUnitPrice: 12.5 }] });
      await renderOne(quote);
      expect(screen.getByTestId('pcr-factory-row-1').textContent).toContain('12.5 EUR');
    });

    it('3. two unit switches fired in one batch both persist (no stale-closure overwrite)', async () => {
      const request = crRequest({ items: [crItem({ id: 1, requestedPriceUnitBasis: undefined }), crItem({ id: 2, model: 'A2', requestedPriceUnitBasis: undefined })] });
      await renderOne(contactedQuote({ items: [line(91, 1, { unitBasis: 'PER_PIECE', quotedUnit: 'แผ่น' }), line(91, 2, { unitBasis: 'PER_PIECE', quotedUnit: 'แผ่น' })] }), request);
      fireEvent.change(screen.getByLabelText(/^ราคาที่เสนอ รายการ #1/), { target: { value: '10' } });
      fireEvent.change(screen.getByLabelText(/^ราคาที่เสนอ รายการ #2/), { target: { value: '11' } });
      const sqm1 = unitGroup(1).getByRole('button', { name: '/ ตร.ม.' });
      const sqm2 = unitGroup(2).getByRole('button', { name: '/ ตร.ม.' });
      act(() => { fireEvent.click(sqm1); fireEvent.click(sqm2); });
      fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));
      await waitFor(() => expect(api.pricingRequests.receiveFactoryQuote).toHaveBeenCalled());
      const { items } = api.pricingRequests.receiveFactoryQuote.mock.calls[0][1];
      expect(items.map((i) => i.unitBasis)).toEqual(['PER_SQM', 'PER_SQM']);
    });

    it('4. สินค้า counts the stock lines too (1 factory line + 1 stock line = 2)', async () => {
      const base = item({ id: 1, resolvedFactoryName: SCG });
      const request = buildRequest({ items: [base, { ...base, id: 2, model: 'S1', stockSource: 'IN_THAILAND', resolvedFactoryName: null }] });
      renderDetailPage({ user: importUser, request, factoryQuotes: [fq(91, SCG, 1, { status: 'REQUESTED' })] });
      await waitForLoaded(request);
      await screen.findByTestId('pcr-stock-card');
      expect(fig('items')).toBe('2');
    });

    it('5. the ตร.ม. under a factory row is formatted (4.319999999999999 -> 4.32)', async () => {
      const request = buildRequest({ items: [item({ id: 1, resolvedFactoryName: SCG, requestedQtySqm: 4.319999999999999 })] });
      renderDetailPage({ user: importUser, request, factoryQuotes: [fq(91, SCG, 1, { status: 'REQUESTED' })] });
      await waitForLoaded(request);
      const row = (await screen.findByTestId('pcr-factory-row-1')).textContent;
      expect(row).toContain('4.32 ตร.ม.');
      expect(row).not.toContain('4.319999');
    });

    it('6. the card meta names the typed country for "อื่นๆ"', async () => {
      const request = buildRequest({ items: [item({ id: 1, resolvedFactoryName: SCG, originCountry: 'อื่นๆ', originCountryOther: 'เวียดนาม' })] });
      renderDetailPage({ user: importUser, request, factoryQuotes: [fq(91, SCG, 1, { status: 'REQUESTED' })] });
      await waitForLoaded(request);
      const head = (await screen.findByTestId('pcr-factory-head-91')).textContent;
      expect(head).toContain('อื่นๆ (เวียดนาม)');
    });
  });

  describe('the CEO and Sales see the page as before (cannot reach)', () => {
    it.each([['ceo', ceoUser], ['sales', salesOwner], ['sales_manager', salesManager]])(
      '%s: no progress strip, no factory section, no import header — item cards and ภาพรวม are still there (positive control: import gets the new page)',
      async (_name, user) => {
        await renderTwo(); // import: the new surface exists
        expect(screen.getByRole('region', { name: 'ความคืบหน้าการขอราคา' })).not.toBeNull();
        cleanup();

        const request = twoFactoryRequest();
        renderDetailPage({ user, request, factoryQuotes: [fq(91, SCG, 1), fq(92, COTTO, 2)] });
        await waitForLoaded(request);
        await act(async () => { await new Promise((resolve) => setTimeout(resolve, 40)); });

        expect(screen.queryByRole('region', { name: 'ความคืบหน้าการขอราคา' })).toBeNull();
        expect(screen.queryByRole('region', { name: 'ขอราคาจากโรงงาน' })).toBeNull();
        expect(screen.queryByTestId('pcr-import-header')).toBeNull();
        expect(screen.queryByTestId('pcr-import-unassigned')).toBeNull();
        expect(screen.queryByRole('button', { name: /สเปกจากฝ่ายขาย/ })).toBeNull();
        expect(screen.queryByRole('button', { name: 'บันทึกว่ากำลังเจรจา' })).toBeNull();
        expect(screen.queryByRole('button', { name: 'ยืนยันราคาเสนอ' })).toBeNull();
        expect(screen.getByRole('heading', { level: 2, name: 'รายการสินค้าและราคาตั้งต้น' })).not.toBeNull();
        expect(screen.getAllByText(/^จำนวนสั่งซื้อ:/).length).toBe(2);
        expect(screen.getByText('ภาพรวม')).not.toBeNull();
      },
    );
  });
});
