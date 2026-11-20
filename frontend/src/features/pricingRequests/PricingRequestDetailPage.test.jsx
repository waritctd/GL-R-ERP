import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PricingRequestDetailPage } from './PricingRequestDetailPage.jsx';
import { api } from '../../api/index.js';

// This component (and PricingRequestCreateModal, which it opens in mode="revision" for the
// customer-change-revision flow) is exercised here against a hand-rolled api mock, not the real Java backend
// and not even mockApi.js.

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
      repointFactoryQuote: vi.fn(),
      setItemOriginCountry: vi.fn(),
      renameItem: vi.fn(),
      setItemFreight: vi.fn(),
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
  // Mirrors UnitBasisMetaController's GET /api/meta/unit-bases — the backend catalog the factory-quote response
  // unit select is built from at runtime (item 3 of this task's brief).
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
// CustomerQuotationDtos.CustomerQuotationDto/CustomerQuotationItemDto — deliberately has no cost/margin/FX
// field anywhere (design correction 2's own precedent, carried into Step 4).
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
  // (listFactoryQuotes/listCostings/listAttachments) so a test that doesn't care about this feature never has to
  // know it exists — only tests exercising it pass discountApprovals.
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

const ceoTable = () => within(screen.getByTestId('pcr-ceo-table'));
const ceoRow = (id) => within(screen.getByTestId(`pcr-ceo-row-${id}`));
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

beforeEach(() => {
  vi.clearAllMocks();
  setApiDefaults();
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
    // The raw-data queries are gated (`enabled: canSeeRaw(user)`), not just hidden in the DOM — sales never even
    // fetches factory-quote/costing detail.
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
});

describe('PricingRequestDetailPage Import factory-quote workflow', () => {
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

  it('records a factory response revision entry via receiveFactoryQuote with a fresh clientRequestId', async () => {
    const quote = buildFactoryQuote({ status: 'REQUESTED' });
    renderDetailPage({ user: importUser, factoryQuotes: [quote] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    // Import types ONE thing: the price. เลขอ้างอิงใบเสนอราคา / เงื่อนไขการชำระเงิน /
    // ระยะเวลาผลิต-ส่งมอบ are optional in ReceiveFactoryQuoteRequest, so the payload carries null.
    const priceInput = screen.getByLabelText(/^ราคาที่เสนอ/);
    fireEvent.change(priceInput, { target: { value: '55.5' } });

    // ยืนยันราคาเสนอ (factory-price-import-ui redesign) replaces บันทึกคำตอบ/รอบแก้ไข: a REQUESTED quote has no
    // response on file yet, so confirming calls receiveFactoryQuote (this assertion) and then
    // markFactoryQuoteReady — see confirmFactoryQuote's own doc comment.
    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));

    await waitFor(() => expect(api.pricingRequests.receiveFactoryQuote).toHaveBeenCalledWith(
      quote.id,
      expect.objectContaining({
        supplierQuoteRef: null,
        clientRequestId: expect.stringMatching(/^[0-9a-f-]{36}$/i),
        items: [expect.objectContaining({ pricingRequestItemId: 1, rawUnitPrice: 55.5 })],
      }),
    ));
    // ...then marks the (in-place-updated, same-id) quote ready for the CEO — one click, both calls, ending at
    // READY_FOR_COSTING as the task brief specifies.
    await waitFor(() => expect(api.pricingRequests.markFactoryQuoteReady).toHaveBeenCalledWith(quote.id));
    // The removed fields must not be resurrected as inputs.
    expect(screen.queryByLabelText('เลขอ้างอิงใบเสนอราคา')).toBeNull();
    expect(screen.queryByLabelText('เงื่อนไขการชำระเงิน')).toBeNull();
    expect(screen.queryByLabelText('ระยะเวลาผลิต/ส่งมอบ')).toBeNull();
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
    // The only two actions left — asserted by role name so a stray extra button would show up as "found 2" against
    // getByRole's own strictness, not silently pass.
    expect(screen.getByRole('button', { name: 'อนุมัติราคาขาย' })).not.toBeNull();
    expect(screen.getByRole('button', { name: 'ตีกลับให้ฝ่ายนำเข้าแก้ไข' })).not.toBeNull();
  });

  // Per-line cost override.
  describe('CEO per-line cost override', () => {
    function renderWithCostingItem(costingItemOverrides = {}, { user = ceoUser } = {}) {
      const request = buildRequest({ summary: { status: 'CEO_REVIEWING' } });
      const costingItem = buildCostingItemWithOverride(costingItemOverrides);
      api.pricingRequests.listPricingDecisions.mockResolvedValue({
        items: [buildDecision({ items: [buildDecisionItem({ pricingCostingItemId: costingItem.id })] })],
      });
      // buildDecision's default pricingCostingId does NOT match buildCosting's default id — id: 601 here is
      // deliberate, not incidental, so the decision-to-costing join (costings.find) actually has to match rather
      // than silently landing on an empty decisionCostingItems map.
      return renderDetailPage({ user, request, costings: [buildCosting({ id: 601, items: [costingItem] })] });
    }

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
  });

  // "ปรับราคาเอง": a REAL behaviour change. It overrides the SELLING PRICE directly (not the cost),
  // and the formula stops driving that line. Reuses PUT /pricing-decisions/{id}
  // (updatePricingDecision); see PricingDecisionRequests.UpdatePricingDecisionItemRequest for why
  // sellingPriceOverride/clearSellingPriceOverride need a tri-state plain COALESCE can't express,
  // and PriceOverrideModal / the overrideSellingPrice mutation in the page.
  describe('CEO per-line selling-price override ("ปรับราคาเอง")', () => {
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

  it('shows Sales the approved selling price via the sales-view projection, with no cost/margin figure anywhere on the page', async () => {
    const request = buildRequest({ summary: { status: 'APPROVED_FOR_QUOTATION' } });
    api.pricingRequests.getPricingDecisionSalesView.mockResolvedValue({ decision: buildSalesView() });
    renderDetailPage({ user: salesOwner, request });
    await waitForLoaded(request);

    expect(await screen.findByText('ราคาขายที่อนุมัติ')).not.toBeNull();
    // The approved selling price (72 THB) is shown...
    expect(screen.getByText(/72/)).not.toBeNull();
    // ...but the underlying frozen cost (60 THB) never appears anywhere — the sales-view DTO this page renders
    // structurally has no cost/margin field at all (design correction 2).
    expect(screen.queryByText(/ต้นทุน/)).toBeNull();
    expect(screen.queryByText(/อัตรากำไร/)).toBeNull();
    // "20 แผ่น", not the raw basis enum (the request-items list renders one already; the
    // sales-view line is the second).
    expect(screen.getAllByText('20 แผ่น')).toHaveLength(2);
    expect(screen.queryByText(/PER_PIECE/)).toBeNull();
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
    it('new-form SPECIAL_SQM: กำไร % comes first and derives ราคาพิเศษ = ทุน/ตร.ม. × (1 + กำไร) × 1.07, net and กำไร show before save (owner 2026-11-06)', async () => {
      api.pricingFormulaConfig = { get: vi.fn().mockResolvedValue({ formulaConfig: { sellingBuffer: 1 } }) };
      try {
        await renderCeoNewForm({ priceMode: 'SPECIAL_SQM', items: [nfItem()] });
        await waitFor(() => expect(api.pricingFormulaConfig.get).toHaveBeenCalled());
        // A costed line is never mislabelled as missing its cost in the row.
        expect(ceoRow(8001).getByTestId('pcr-ceo-cost-both-8001').textContent).toContain('60.00');

        // cost 60/แผ่น at 0.36 ตร.ม. = 166.67/ตร.ม.; × 1.30 × 1.07 = 231.83; net = 231.83 / 1.07 / 2.78 = 77.94
        fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-margin-input-8001'), { target: { value: '30' } });
        await waitFor(() => expect(ceoRow(8001).getByTestId('pcr-ceo-special-sqm-8001').value).toBe('231.83'));
        expect(ceoRow(8001).getByTestId('pcr-ceo-special-formula-8001').textContent).toBe('= 166.67 × 1.30 × VAT 1.07');
        expect(within(ceoPricingPanel()).getByTestId('pcr-ceo-special-steps').textContent).toContain('กำไร %');
        // a hand-typed ราคาพิเศษ is not labelled as the formula's
        fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-special-sqm-8001'), { target: { value: '240' } });
        expect(ceoRow(8001).queryByTestId('pcr-ceo-special-formula-8001')).toBeNull();
        // a new กำไร % re-derives it again (166.67 × 1.32 × 1.07 = 235.40), then back to 30
        fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-margin-input-8001'), { target: { value: '32' } });
        await waitFor(() => expect(ceoRow(8001).getByTestId('pcr-ceo-special-sqm-8001').value).toBe('235.4'));
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

    describe('new-form SPECIAL_SQM: a typed ราคาพิเศษ live-derives กำไร % (cost 60 at 0.36 ตร.ม. = 166.67/ตร.ม., buffer 1)', () => {
      const withSpecial = async (fn) => {
        api.pricingFormulaConfig = { get: vi.fn().mockResolvedValue({ formulaConfig: { sellingBuffer: 1 } }) };
        try {
          await renderCeoNewForm({ priceMode: 'SPECIAL_SQM', items: [nfItem()] });
          await waitFor(() => expect(api.pricingFormulaConfig.get).toHaveBeenCalled());
          await fn();
        } finally {
          delete api.pricingFormulaConfig;
        }
      };
      const typeSpecial = (v) => fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-special-sqm-8001'), { target: { value: v } });

      it('typing ราคาพิเศษ 240 shows the implied กำไร % 34.58 and its note; typing กำไร % afterwards re-derives ราคาพิเศษ', () => withSpecial(async () => {
        typeSpecial('240');
        await waitFor(() => expect(ceoRow(8001).getByTestId('pcr-ceo-margin-input-8001').value).toBe('34.58'));
        expect(ceoRow(8001).getByTestId('pcr-ceo-margin-formula-8001').textContent).toBe('= 240.00 ÷ (166.67 × 1.07) − 1');
        // กำไร % typed last leads again: 166.67 × 1.30 × 1.07 = 231.83, and the implied note is gone
        fireEvent.change(ceoRow(8001).getByTestId('pcr-ceo-margin-input-8001'), { target: { value: '30' } });
        await waitFor(() => expect(ceoRow(8001).getByTestId('pcr-ceo-special-sqm-8001').value).toBe('231.83'));
        expect(ceoRow(8001).queryByTestId('pcr-ceo-margin-formula-8001')).toBeNull();
      }));

      it('saving after typing ราคาพิเศษ sends BOTH the typed specialPriceSqm and the implied marginPct fraction', () => withSpecial(async () => {
        typeSpecial('240');
        await waitFor(() => expect(ceoRow(8001).getByTestId('pcr-ceo-margin-input-8001').value).toBe('34.58'));
        fireEvent.click(saveAllButton());
        await waitFor(() => expect(api.pricingRequests.updatePricingDecision).toHaveBeenCalled());
        const sent = api.pricingRequests.updatePricingDecision.mock.calls.at(-1)[1].items[0];
        expect(sent.specialPriceSqm).toBe(240);
        expect(sent.marginPct).toBeCloseTo(240 / ((60 / 0.36) * 1.07) - 1, 6);
      }));
    });
  });

  // ── CEO pricing method ────────────────────────────────────────────────────────────────────
  describe('CEO price mode (Phase 2)', () => {
    function newFormItem(overrides = {}) {
      return buildDecisionItem({ sqmPerPiece: 0.36, ...overrides });
    }

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

      // Import never reaches the CEO panel (canSeeRawPricingDecision(user) && !isImport(user)).
      expect(screen.queryByText('การพิจารณาราคาขายของ CEO')).toBeNull();
      expect(screen.queryByRole('group', { name: 'วิธีกรอกราคากระเบื้อง' })).toBeNull();
    });
  });
});

// Step 4 (Customer Quotation Generation and Issuance). UI-LEVEL ONLY, same caveat as this file's own header:
// proves this component's conditional rendering/wiring, not server-side enforcement.
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
    expect(screen.getByText('ยังไม่มีใบเสนอราคาลูกค้า — ต้องรออนุมัติราคาขายก่อนจึงจะสร้างได้')).not.toBeNull();
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
});

// These stub api.dealQuotations.findForPricingRequest's RESOLVED VALUE directly (see
// newEngineQuotation()/mockResolvedValue below), so they do NOT catch a regression in the BACKEND
// query: reverting DealQuotationService#findForPricingRequest to a DRAFT-only reader leaves them
// green. That is covered by
// DealQuotationOutcomeIntegrationTest#findForPricingRequest_returnsTheQuotationPastDraft (real
// Postgres). These tests pin, per status, the outcome/expiry UI behaviour of the new engine.
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
  },
])('PricingRequestDetailPage W3-06/QA-35: ปฏิเสธ / ขอแก้ไข confirm with a reason — $engine', ({ recordMock, setup }) => {
  async function renderIssued() {
    const issued = setup();
    renderDetailPage({ user: salesOwner });
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
});

describe('PricingRequestDetailPage Step 6: Deposit, Payment, and Order Confirmation', () => {
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

  it('never shows either Step 6 button to CEO or Import — read-only', async () => {
    const request = buildRequest({ summary: { status: 'QUOTATION_ACCEPTED', orderConfirmedAt: null } });
    renderDetailPage({ user: ceoUser, request });
    await waitForLoaded(request);

    expect(screen.queryByRole('button', { name: 'ยืนยันคำสั่งซื้อ' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'สร้างใบแจ้งยอดเงินรับมัดจำ' })).toBeNull();
    expect(screen.getByText('ยืนยันคำสั่งซื้อได้เฉพาะเจ้าของงาน (sales)')).not.toBeNull();
  });
});

const salesOtherRep = { id: 9, employeeId: 9, name: 'พนักงานขายคนอื่น', role: 'sales' };

// Spec §7.11 item 2: a Sales-set currency / unit renders as a chip; these tests exercise the control behind its แก้.
async function revealSalesCurrency() {
  fireEvent.click(await screen.findByRole('button', { name: 'แก้สกุลเงิน' }));
}
function revealSalesUnit(n = 1) {
  fireEvent.click(screen.getByRole('button', { name: new RegExp(`^แก้หน่วยราคา รายการ #${n}`) }));
}

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
  it('updates baht live when Import changes currency and sends that currency on confirmation', async () => {
    api.fxRates = { list: vi.fn().mockResolvedValue({ fxRates: [{ currency: 'EUR', rateToThb: 40 }, { currency: 'USD', rateToThb: 35 }] }) };
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [contactedQuote()] });
    await waitForLoaded();
    await revealSalesCurrency();
    const currency = await screen.findByRole('combobox', { name: /^สกุลเงิน/ });
    fireEvent.change(screen.getByLabelText(/^ราคาที่เสนอ/), { target: { value: '10' } });
    await waitFor(() => expect(screen.getByTestId('pcr-quote-thb-91-1').textContent).toContain('฿400.00/ตร.ม.'));
    fireEvent.change(currency, { target: { value: 'USD' } });
    expect(screen.getByLabelText(/^ราคาที่เสนอ/).value).toBe('10');
    expect(screen.getByTestId('pcr-quote-thb-91-1').textContent).toContain('฿350.00/ตร.ม.');
    expect(screen.getByTestId('pcr-factory-currency-91').textContent).toContain('ฝ่ายขายขอ: EUR');
    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));
    await waitFor(() => expect(api.pricingRequests.receiveFactoryQuote).toHaveBeenCalledWith(91,
      expect.objectContaining({ defaultCurrency: 'USD', items: [expect.objectContaining({ currency: 'USD', rawUnitPrice: 10 })] })));
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
    revealSalesUnit();
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

    revealSalesUnit();
    fireEvent.click(within(screen.getByRole('group', { name: /^หน่วยราคา รายการ/ })).getByRole('button', { name: '/ แผ่น' }));
    fireEvent.change(screen.getByLabelText(/^ราคาที่เสนอ/), { target: { value: '36' } });
    expect(screen.getByTestId('pcr-quote-equiv-91-1').textContent).toContain('100 EUR/ตร.ม.');
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
});

// The CEO's pricing lives INSIDE "รายการสินค้าและราคาตั้งต้น" (one panel, the same "วิธีกรอกราคากระเบื้อง"
// picker as the direct-quotation editor), and NET mode has a typed ราคาตั้ง/แผ่น input beside
// ส่วนลด %. UI-level only: no authorization rule changes here, and
// PricingDecisionService#applyItemUpdates (which these payloads feed) is not exercised by this file.
describe('CEO pricing inside รายการสินค้าและราคาตั้งต้น (2026-10-01 redesign)', () => {

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
  });

  describe('summary bar', () => {
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
  });

  describe('pricing panel and table', () => {
    it.each([
      ['NET', ['#', 'สินค้า', 'จำนวน', 'ต้นทุน/แผ่น', 'กำไร %', 'ราคาตั้ง/แผ่น', 'ส่วนลด', 'ราคาสุทธิ/แผ่น', 'รวม (ก่อน VAT)', 'กำไร', 'รายละเอียด']],
      // SPECIAL_SQM numbers its three steps (① กำไร % → ② ราคาพิเศษ → ③ กำไร) in the header
      ['SPECIAL_SQM', ['#', 'สินค้า', 'จำนวน', 'ต้นทุน/แผ่น', '1กำไร %', 'ราคาตั้ง (สูตร)', '2ราคาพิเศษ/ตร.ม.', 'ราคาสุทธิ/แผ่น', 'รวม (ก่อน VAT)', '3กำไร', 'รายละเอียด']],
      ['DIRECT_NET', ['#', 'สินค้า', 'จำนวน', 'ต้นทุน/แผ่น', 'ราคาสุทธิ/แผ่น', 'ราคาสุทธิ/แผ่น', 'รวม (ก่อน VAT)', 'กำไร', 'รายละเอียด']],
    ])('%s: the table columns depend on the mode (always-columns + mode columns), one row per item', async (priceMode, headers) => {
      await renderCeoNewForm({ priceMode, items: [nfItem({ netUnitPrice: 100, listUnitPrice: 100, directNetPrice: 100 })] });
      const names = ceoTable().getAllByRole('columnheader').map((th) => th.textContent.trim()).filter(Boolean);
      expect(names).toEqual(headers);
      expect(ceoTable().getAllByTestId(/^pcr-ceo-row-/)).toHaveLength(1);
    });
  });

  describe('details row', () => {
    // CEO ruling 2026-11-06: capacity by thickness; thicknesses combine in one container, shares by container space.
    const calcSnapshotRows = async (snapshotExtra) => {
      const costingItem = {
        id: 1, factoryName: 'Equipe', factoryQuoteRevisionNo: 1, landedCostPerUnitThb: 14.1544,
        rawUnitPrice: 14.283, rawCurrency: 'EUR', rawUnit: 'ตร.ม.', fxRate: 37.9944, sqmPerUnit: 0.014706,
        goodsCostThb: 7.9806, normalizedQuantityPieces: 265880, calculationConfigVersion: 2,
        calculationSnapshot: JSON.stringify({
          formula: 'V109', itemQtySqm: '300.0000000000', dutyPct: '0.300000', costBuffer: '1.070000',
          insuranceValueFactor: '1.150000', insuranceRate: '0.004500', insuranceBuffer: '1.070000',
          freightAmountThbForGroup: '160000.0000', freightGroupQtySqm: '600.0000000000', freightShareThb: '102400.0000',
          clearanceAmountThbForShipment: '28000.0000', shipmentQtySqm: '600.0000000000', clearanceShareThb: '17920.0000',
          ...snapshotExtra,
        }),
      };
      await renderCeoNewForm({
        priceMode: 'SPECIAL_SQM',
        items: [nfItem({ pricingCostingItemId: 1, proposedMarginPct: 0.3, frozenLandedCostPerRequestedUnitThb: 14.1544 })],
        costings: [buildCosting({ id: 601, items: [costingItem] })],
      });
      await waitFor(() => expect(api.pricingFormulaConfig.get).toHaveBeenCalled());
      fireEvent.click(ceoTable().getByRole('button', { name: 'รายละเอียด SCG A1' }));
      return ceoTable().findByTestId('pcr-ceo-calc-8001');
    };

    it('F/S share lines read by container space when the snapshot has the container-unit keys', async () => {
      api.pricingFormulaConfig = { get: vi.fn().mockResolvedValue({ formulaConfig: { sellingBuffer: 1 } }) };
      try {
        // 300 ตร.ม. @19 mm + 300 ตร.ม. @10 mm: units 0.666667 / 1.041667 → 64%; 1 ตู้ + leftover 33.33 ตร.ม.
        const calc = await calcSnapshotRows({
          containerSqm: '450', itemContainerUnits: '0.666667', freightGroupContainerUnits: '1.041667',
          shipmentContainerUnits: '1.041667', freightRoundUpAboveSqm: '600', freightFullContainers: '1', freightRemainderSqm: '33.33',
          clearanceFullContainers: '1', clearanceRemainderSqm: '33.33',
        });
        await waitFor(() => expect(calc.textContent).toContain('160,000.00 × 0.666667 ÷ 1.041667 ตู้ (64%)'));
        const text = calc.textContent;
        expect(text).toContain('28,000.00 × 0.666667 ÷ 1.041667 ตู้ (64%)');
        expect(text).toContain('1 ตู้ + 33.33 ตร.ม. · ความจุ 450 ตร.ม./ตู้ · เศษเกิน 600 ตร.ม. ปัดเป็นตู้เต็ม · แบ่งตามพื้นที่ในตู้');
        expect(text).not.toContain('แบ่งตาม ตร.ม. กับรายการอื่นในตู้เดียวกัน');
      } finally {
        delete api.pricingFormulaConfig;
      }
    });

    it('the F sub-line takes capacity and round-up threshold from the snapshot (thick-only: 450 / 250)', async () => {
      api.pricingFormulaConfig = { get: vi.fn().mockResolvedValue({ formulaConfig: { sellingBuffer: 1 } }) };
      try {
        const calc = await calcSnapshotRows({
          containerSqm: '450', itemContainerUnits: '1', freightGroupContainerUnits: '2', shipmentContainerUnits: '2',
          freightRoundUpAboveSqm: '250', freightFullContainers: '2', freightRemainderSqm: '0',
          clearanceFullContainers: '2', clearanceRemainderSqm: '0',
        });
        await waitFor(() => expect(calc.textContent).toContain('2 ตู้ · ความจุ 450 ตร.ม./ตู้ · เศษเกิน 250 ตร.ม. ปัดเป็นตู้เต็ม · แบ่งตามพื้นที่ในตู้'));
        expect(calc.textContent).not.toContain('หนา 17–21 มม.');
      } finally {
        delete api.pricingFormulaConfig;
      }
    });

    it('an old snapshot without the container-unit keys keeps today\'s ÷ 800 text unchanged', async () => {
      api.pricingFormulaConfig = { get: vi.fn().mockResolvedValue({ formulaConfig: { sellingBuffer: 1 } }) };
      try {
        const calc = await calcSnapshotRows({
          containerSqm: '800', freightFullContainers: '1', freightRemainderSqm: '0', clearanceFullContainers: '1', clearanceRemainderSqm: '0',
        });
        await waitFor(() => expect(calc.textContent).toContain('160,000.00 × 300.00 ÷ 600.00 ตร.ม. (50%)'));
        const text = calc.textContent;
        expect(text).toContain('28,000.00 × 300.00 ÷ 600.00 ตร.ม. (50%)');
        expect(text).toContain('1 ตู้ = 600.00 ตร.ม. ÷ 800 ตร.ม./ตู้ (เศษเกิน 75% ปัดเป็นตู้เต็ม) · แบ่งตาม ตร.ม. กับรายการอื่นในตู้เดียวกัน');
        expect(text).not.toContain('แบ่งตามพื้นที่ในตู้');
      } finally {
        delete api.pricingFormulaConfig;
      }
    });
  });

  describe('margin: typed and shown as a PERCENT, stored as a fraction', () => {
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
});

// ── CEO workspace redesign: Opus review fixes ────────────────────────────────────────────────────
describe('CEO pricing workspace redesign — review fixes', () => {

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

// Spec 2026-11-15 §7.11 item 2: the currency and price unit Sales already set are shown as read-only chips, not re-asked.
describe('PricingRequestDetailPage import workspace — terms Sales already set', () => {
  it('shows the Sales-set currency and unit as chips, with no currency select and no unit switch', async () => {
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [contactedQuote()] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    const currencyChip = screen.getByTestId('pcr-sales-currency-91');
    expect(currencyChip.textContent).toContain('EUR');
    expect(currencyChip.textContent).toContain('ตามที่ฝ่ายขายระบุ');
    expect(screen.queryByRole('combobox', { name: 'สกุลเงินที่โรงงานเสนอ' })).toBeNull();
    expect(screen.getByTestId('pcr-factory-currency-91').textContent).toContain('ฝ่ายขายขอ: EUR');
    const unitChip = screen.getByTestId('pcr-sales-unit-91-1');
    expect(unitChip.textContent).toContain('/ ตร.ม.');
    expect(unitChip.textContent).toContain('ตามที่ฝ่ายขายระบุ');
    expect(screen.queryByRole('group', { name: /^หน่วยราคา รายการ/ })).toBeNull();
  });

  it('confirming with the chips sends the same payload as before: the line keeps Sales’ currency and unit', async () => {
    const quote = contactedQuote();
    renderDetailPage({ user: importUser, request: crRequest(), factoryQuotes: [quote] });
    await waitForLoaded();
    await screen.findByText('SCG Ceramics');

    fireEvent.change(screen.getByLabelText(/^ราคาที่เสนอ/), { target: { value: '32.5' } });
    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันราคาเสนอ' }));
    await waitFor(() => expect(api.pricingRequests.receiveFactoryQuote).toHaveBeenCalledWith(
      quote.id,
      expect.objectContaining({
        items: [expect.objectContaining({ currency: 'EUR', unitBasis: 'PER_SQM', quotedUnit: 'ตร.ม.', rawUnitPrice: 32.5 })],
      }),
    ));
  });
});
