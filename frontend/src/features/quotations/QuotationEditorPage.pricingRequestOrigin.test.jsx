import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QuotationEditorPage } from './QuotationEditorPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// The origin === 'PRICING_REQUEST' UI: the "ราคาจาก CEO" badge / "เปลี่ยนจากราคา CEO" marker per
// line, the header price-mode-changed marker, the "สร้างจากคำขอราคา" link, extra-row actions hidden,
// and the submit button. Same mounting pattern as QuotationEditorPage.displayNames.test.jsx.
vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: { get: vi.fn(), create: vi.fn() },
      dealQuotations: {
        get: vi.fn(), create: vi.fn(), update: vi.fn(), calculateLine: vi.fn(), submit: vi.fn(),
        approve: vi.fn(), reject: vi.fn(),
        displayNameOptions: vi.fn(),
      },
      catalog: { prices: vi.fn() },
      designers: { search: vi.fn(), getByCode: vi.fn().mockRejectedValue(new Error('not found')) },
      customers: {
        search: vi.fn(), create: vi.fn(), update: vi.fn(), projects: vi.fn(),
        contacts: vi.fn(),
      },
    },
  };
});

const salesUser = { id: 6, name: 'คุณสมหมาย ขายดี', role: 'sales', employeeId: null };

const CUSTOMER = { id: 5, name: 'บริษัท แฟชั่นไอส์แลนด์ จำกัด', taxId: '0105551234567', address: null, branch: 'สำนักงานใหญ่', phone: '02-000-0000' };
const CONTACTS = [{ id: 6, customerId: 5, firstName: 'ณัฐพงศ์', lastName: 'ศรีวิไล', phone: '086-222-3333', email: 'nattapong@fashionisland.co.th' }];

/** A plain TILE item as a DEAL_DIRECT row would carry it: NO ceo* fields (all null, as
 * DealQuotationRepository#mapItemColumns reads them for an unlinked row). Base for BOTH the
 * DEAL_DIRECT fixture and (via `linkedNetTileItem`) the linked one, so a DEAL_DIRECT row cannot pick
 * up a stray ceo* field from a shared builder with CEO defaults. */
function netTileItem(overrides = {}) {
  return {
    id: 101, seq: 1, lineType: 'TILE', locationLabel: null, catalogPriceId: null, productCode: null,
    brand: 'Marazzi', model: 'Trilogy', color: 'Ash', texture: 'Matt', sizeText: '60x60', thicknessMm: 10,
    sqmPerPiece: 0.36, quantityMode: 'AREA', areaSqm: 20, piecesInput: null, wastageMode: 'PERCENT',
    wastageValue: 0, piecesPerBox: 3, unitPrice: 83, discountPct: 10, originCountry: null,
    leadTimeMinDays: 30, leadTimeMaxDays: 45, itemNotes: null,
    piecesPerSqm: 2.78, piecesBeforeWastage: 56, piecesAfterWastage: 56, piecesFinal: 57, boxes: 19,
    netUnitPrice: 74.7, lineAmount: 4257.9, descriptionLine: 'Trilogy Ash', sizeLine: '60x60', calculationLine: '(calc)',
    quantity: 57, unit: 'แผ่น', specialPriceSqm: null, adjustmentPct: null, adjustmentDeadline: null,
    specialPriceLine: null, adjustmentAmount: null, sqmPerBox: null, roundToFullBox: true,
    // ── CEO-comparison fields: null for every DEAL_DIRECT/unlinked row ──
    priceChangedFromCeo: false,
    ceoListUnitPrice: null, ceoDiscountPct: null, ceoSpecialPriceSqm: null, ceoDirectNetPrice: null,
    ceoNetUnitPrice: null,
    ...overrides,
  };
}

/** `netTileItem` LINKED to a CEO decision — `ceoNetUnitPrice` non-null is the one signal the editor reads to
 * decide whether to show the badge/marker at all (see QuotationItemRow's own comment). `changed` (via
 * overrides) toggles the badge vs the amber marker. */
function linkedNetTileItem(overrides = {}) {
  return netTileItem({
    ceoListUnitPrice: 83, ceoDiscountPct: 10, ceoSpecialPriceSqm: null, ceoDirectNetPrice: null,
    ceoNetUnitPrice: 74.7,
    ...overrides,
  });
}

function draft(overrides = {}) {
  return {
    id: 5, number: 'QT-2026-0005-1', ticketId: 18, docStatus: 'DRAFT', revisionNo: 1, parentQuotationId: null,
    salesRepId: 6, salesRepName: 'คุณสมหมาย ขายดี', salesRepPhone: '081-000-0000', createdByName: 'คุณสมหมาย ขายดี',
    approvalNote: null, quotationDate: '2026-09-20',
    customerName: CUSTOMER.name, customerAddress: null, customerTaxId: CUSTOMER.taxId, customerPhone: CUSTOMER.phone,
    contactId: 6, contactName: 'ณัฐพงศ์ ศรีวิไล', contactPhone: '086-222-3333', contactEmail: 'nattapong@fashionisland.co.th',
    projectName: 'โครงการ A', deptCode: null, unitCode: 'A001', offerDate: '2026-09-01', depositPercent: null,
    remainderMode: null, creditDays: null, validityDays: 30, validityDate: null, customerNotes: null,
    priceMode: 'NET', documentLanguage: 'TH', subtotalAmount: 4257.9, vatAmount: 298.05, grandTotal: 4555.95,
    currency: 'THB', approverHasSignature: false, items: [netTileItem()],
    printedByDisplayId: null, printedByDisplayName: null, printedByDisplayNameEn: null,
    salesRepDisplayId: null, salesRepDisplayName: null, salesRepDisplayNameEn: null, salesRepDisplayPhone: null,
    createdAt: '2026-09-20T02:00:00Z', updatedAt: '2026-09-20T02:00:00Z',
    // ── origin fields ──
    origin: 'DEAL_DIRECT', pricingRequestId: null, pricingRequestCode: null,
    priceModeChangedFromCeo: false, ceoPriceMode: null,
    ...overrides,
  };
}

/** A PRICING_REQUEST-origin draft, NET mode, one LINKED line (unchanged from the CEO's price). */
function pricingRequestDraft(overrides = {}) {
  return draft({
    origin: 'PRICING_REQUEST', pricingRequestId: 42, pricingRequestCode: 'PCR-2026-0042',
    ceoPriceMode: 'NET', items: [linkedNetTileItem()],
    ...overrides,
  });
}

function renderEditor(path, user = salesUser) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/quotations/:id" element={<QuotationEditorPage user={user} showToast={vi.fn()} />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

// The two roles who may approve.
const salesManagerUser = { id: 7, name: 'คุณผึ้ง ผู้จัดการฝ่ายขาย', role: 'sales_manager', employeeId: 7 };
const ceoUser = { id: 8, name: 'คุณราม ผู้บริหาร', role: 'ceo', employeeId: 8 };

/** A PRICING_REQUEST-origin quotation already PENDING_APPROVAL: the approval tests' shared starting
 * point. */
function pendingApprovalQuotation(overrides = {}) {
  return pricingRequestDraft({
    docStatus: 'PENDING_APPROVAL', submittedAt: '2026-09-20T03:00:00Z',
    ...overrides,
  });
}

beforeEach(() => {
  vi.clearAllMocks();
  api.tickets.get.mockResolvedValue({
    ticket: {
      summary: {
        id: 18, createdById: 6, createdByName: 'คุณสมหมาย ขายดี', customerName: CUSTOMER.name, customerId: 5,
        projectId: 3, projectName: 'โครงการ A', contactId: 6, contactName: 'ณัฐพงศ์ ศรีวิไล',
      },
    },
  });
  api.customers.search.mockResolvedValue({ customers: [CUSTOMER] });
  api.customers.contacts.mockResolvedValue({ contacts: CONTACTS });
  api.dealQuotations.displayNameOptions.mockResolvedValue({ items: [] });
  // Editing a row schedules the auto-calc-preview debounce (QuotationEditorPage's updateItem effect),
  // which fires AFTER the test has completed. Without a resolved value here that timer's `.then()`
  // throws on `undefined`, an unhandled rejection vitest reports as a process-level error even though
  // every assertion passes. Mirrors QuotationEditorPage.test.jsx's default mock.
  api.dealQuotations.calculateLine.mockResolvedValue({ item: {} });
});

describe('QuotationEditorPage — GLA-123 slice S1 origin=PRICING_REQUEST', () => {
  it('NET: a changed linked line shows the amber marker with "ราคา CEO: ฿83.00 · ส่วนลด 10%"', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: pricingRequestDraft({ items: [linkedNetTileItem({ priceChangedFromCeo: true, discountPct: 20 })] }),
    });
    renderEditor('/quotations/5');
    await waitFor(() => expect(screen.getByText('เปลี่ยนจากราคา CEO — ต้องให้ CEO อนุมัติ')).toBeTruthy());
    expect(screen.getByText('ราคา CEO: ฿83.00 · ส่วนลด 10%')).toBeTruthy();
    expect(screen.queryByText('ราคาจาก CEO')).toBeNull();
  });
});

// ONE approval, by sales_manager OR ceo, issues the document, unless the quotation no longer matches
// the CEO's decision, in which case only the ceo may act. A DEAL_DIRECT quotation's own single-shot
// approve/reject (QuotationEditorPage.test.jsx) is untouched.
describe('QuotationEditorPage — GLA-123 slice S2 approval (reworked)', () => {
  it('sales_manager sees a plain อนุมัติ button on an UNCHANGED quotation', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: pendingApprovalQuotation() });
    renderEditor('/quotations/5', salesManagerUser);
    await screen.findByRole('button', { name: 'อนุมัติ' });
    expect(screen.getByRole('button', { name: 'ไม่อนุมัติ' })).toBeTruthy();
  });

  it('sales_manager sees NO อนุมัติ button, and an inline explanation, on a CHANGED quotation', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: pendingApprovalQuotation({
        items: [linkedNetTileItem({ unitPrice: 90, discountPct: 5, priceChangedFromCeo: true })],
      }),
    });
    renderEditor('/quotations/5', salesManagerUser);
    await screen.findByRole('button', { name: 'ไม่อนุมัติ' });
    expect(screen.queryByRole('button', { name: 'อนุมัติ' })).toBeNull();
    await waitFor(() => expect(screen.getByText('มีรายการที่เปลี่ยนจากราคา CEO — ต้องให้ CEO อนุมัติ')).toBeTruthy());
  });

  it('ceo still sees อนุมัติ on a CHANGED quotation, and can confirm it', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: pendingApprovalQuotation({
        items: [linkedNetTileItem({ unitPrice: 90, discountPct: 5, priceChangedFromCeo: true })],
      }),
    });
    api.dealQuotations.approve.mockResolvedValue({ quotation: pendingApprovalQuotation({
      docStatus: 'ISSUED', approvedById: 8, approvedByName: 'คุณราม ผู้บริหาร',
    }) });
    renderEditor('/quotations/5', ceoUser);
    fireEvent.click(await screen.findByRole('button', { name: 'อนุมัติ' }));
    fireEvent.click(await screen.findByRole('button', { name: 'ยืนยันอนุมัติ' }));
    await waitFor(() => expect(api.dealQuotations.approve).toHaveBeenCalledWith('5', {}));
  });

  it('reject requires a reason and calls reject with it', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: pendingApprovalQuotation() });
    api.dealQuotations.reject.mockResolvedValue({ quotation: pricingRequestDraft({ docStatus: 'DRAFT', approvalNote: 'ราคาไม่เหมาะสม' }) });
    renderEditor('/quotations/5', ceoUser);
    fireEvent.click(await screen.findByRole('button', { name: 'ไม่อนุมัติ' }));
    const confirmReject = await screen.findByRole('button', { name: 'ยืนยันไม่อนุมัติ' });
    expect(confirmReject.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText(/^เหตุผลที่ไม่อนุมัติ/), { target: { value: 'ราคาไม่เหมาะสม' } });
    expect(confirmReject.disabled).toBe(false);
    fireEvent.click(confirmReject);
    await waitFor(() => expect(api.dealQuotations.reject).toHaveBeenCalledWith('5', { reason: 'ราคาไม่เหมาะสม' }));
  });
});
