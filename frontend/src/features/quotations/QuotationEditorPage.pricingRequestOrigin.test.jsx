import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QuotationEditorPage } from './QuotationEditorPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// GLA-123 slice S1 (Phase 3, coordinator follow-up 2026-09-20) — the origin === 'PRICING_REQUEST'
// UI: the "ราคาจาก CEO" badge / "เปลี่ยนจากราคา CEO" marker per line, the header price-mode-changed
// marker, the "สร้างจากคำขอราคา" link, extra-row actions hidden, and the submit button disabled.
// Same mounting pattern as QuotationEditorPage.displayNames.test.jsx, scoped to just this feature.
vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: { get: vi.fn(), create: vi.fn() },
      dealQuotations: {
        get: vi.fn(), create: vi.fn(), update: vi.fn(), calculateLine: vi.fn(), submit: vi.fn(),
        approve: vi.fn(), reject: vi.fn(), createRevision: vi.fn(), createReorder: vi.fn(), cancel: vi.fn(),
        downloadPdf: vi.fn(), downloadXlsx: vi.fn(), displayNameOptions: vi.fn(),
      },
      catalog: { prices: vi.fn() },
      designers: { search: vi.fn(), getByCode: vi.fn().mockRejectedValue(new Error('not found')) },
      customers: {
        search: vi.fn(), create: vi.fn(), update: vi.fn(), projects: vi.fn(), createProject: vi.fn(),
        contacts: vi.fn(), createContact: vi.fn(),
      },
    },
  };
});

const salesUser = { id: 6, name: 'คุณสมหมาย ขายดี', role: 'sales', employeeId: null };

const CUSTOMER = { id: 5, name: 'บริษัท แฟชั่นไอส์แลนด์ จำกัด', taxId: '0105551234567', address: null, branch: 'สำนักงานใหญ่', phone: '02-000-0000' };
const CONTACTS = [{ id: 6, customerId: 5, firstName: 'ณัฐพงศ์', lastName: 'ศรีวิไล', phone: '086-222-3333', email: 'nattapong@fashionisland.co.th' }];

/** A plain TILE item as a DEAL_DIRECT row would carry it — NO ceo* fields (every one of them
 * null, exactly as DealQuotationRepository#mapItemColumns reads them for an unlinked row). Used
 * as the base for BOTH the DEAL_DIRECT fixture and (via `linkedNetTileItem`) the linked one, so a
 * test cannot accidentally leave a stray ceo* field on a DEAL_DIRECT row the way a single shared
 * builder with CEO fields baked into its defaults did (caught by this file's own DEAL_DIRECT
 * "unaffected" test on first write — see the PR body). */
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
    // ── GLA-123 CEO-comparison fields — null for every DEAL_DIRECT/unlinked row ─────────────
    priceChangedFromCeo: false,
    ceoListUnitPrice: null, ceoDiscountPct: null, ceoSpecialPriceSqm: null, ceoDirectNetPrice: null,
    ceoNetUnitPrice: null,
    ...overrides,
  };
}

/** `netTileItem` LINKED to a CEO decision — `ceoNetUnitPrice` non-null is the one signal the
 * editor reads to decide whether to show the badge/marker at all (see QuotationItemRow's own
 * comment). `changed` (via overrides) toggles the badge vs the amber marker. */
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
    // ── GLA-123 origin fields ──────────────────────────────────────────────────────────────
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

function renderEditor(path) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/quotations/:id" element={<QuotationEditorPage user={salesUser} showToast={vi.fn()} />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
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
  // Test-hygiene fix (found running the M5 tests below): editing a row schedules the
  // auto-calc-preview debounce (QuotationEditorPage's own updateItem effect), which fires AFTER
  // the test that triggered it has already completed and moved on. Without a resolved value here
  // that timer's `.then()` throws on `undefined`, an unhandled rejection vitest reports as a
  // process-level error even though every assertion in every test still passes — exactly what
  // happened before this line existed. Mirrors QuotationEditorPage.test.jsx's own default mock.
  api.dealQuotations.calculateLine.mockResolvedValue({ item: {} });
});

describe('QuotationEditorPage — GLA-123 slice S1 origin=PRICING_REQUEST', () => {
  it('an unchanged linked line shows the "ราคาจาก CEO" badge, not the amber marker', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: pricingRequestDraft() });
    renderEditor('/quotations/5');
    await waitFor(() => expect(screen.getByText('ราคาจาก CEO')).toBeTruthy());
    expect(screen.queryByText('เปลี่ยนจากราคา CEO — ต้องให้ CEO อนุมัติ')).toBeNull();
  });

  it('NET: a changed linked line shows the amber marker with "ราคา CEO: ฿83.00 · ส่วนลด 10%"', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: pricingRequestDraft({ items: [linkedNetTileItem({ priceChangedFromCeo: true, discountPct: 20 })] }),
    });
    renderEditor('/quotations/5');
    await waitFor(() => expect(screen.getByText('เปลี่ยนจากราคา CEO — ต้องให้ CEO อนุมัติ')).toBeTruthy());
    expect(screen.getByText('ราคา CEO: ฿83.00 · ส่วนลด 10%')).toBeTruthy();
    expect(screen.queryByText('ราคาจาก CEO')).toBeNull();
  });

  it('SPECIAL_SQM: a changed linked line shows "ราคาพิเศษ CEO: ฿1,350.00/ตร.ม."', async () => {
    const item = linkedNetTileItem({
      priceChangedFromCeo: true, unitPrice: null, discountPct: null, specialPriceSqm: 1500,
      ceoListUnitPrice: null, ceoDiscountPct: null, ceoSpecialPriceSqm: 1350, ceoDirectNetPrice: null,
    });
    api.dealQuotations.get.mockResolvedValue({
      quotation: pricingRequestDraft({ priceMode: 'SPECIAL_SQM', ceoPriceMode: 'SPECIAL_SQM', items: [item] }),
    });
    renderEditor('/quotations/5');
    await waitFor(() => expect(screen.getByText('เปลี่ยนจากราคา CEO — ต้องให้ CEO อนุมัติ')).toBeTruthy());
    expect(screen.getByText('ราคาพิเศษ CEO: ฿1,350.00/ตร.ม.')).toBeTruthy();
  });

  it('DIRECT_NET: a changed linked line shows "ราคาสุทธิ CEO: ฿74.70"', async () => {
    const item = linkedNetTileItem({
      priceChangedFromCeo: true, unitPrice: 999, discountPct: null, netUnitPrice: 999,
      ceoListUnitPrice: null, ceoDiscountPct: null, ceoSpecialPriceSqm: null, ceoDirectNetPrice: 74.7,
    });
    api.dealQuotations.get.mockResolvedValue({
      quotation: pricingRequestDraft({ priceMode: 'DIRECT_NET', ceoPriceMode: 'DIRECT_NET', items: [item] }),
    });
    renderEditor('/quotations/5');
    await waitFor(() => expect(screen.getByText('เปลี่ยนจากราคา CEO — ต้องให้ CEO อนุมัติ')).toBeTruthy());
    expect(screen.getByText('ราคาสุทธิ CEO: ฿74.70')).toBeTruthy();
  });

  // MINOR fix (Opus review, 2026-09-20): every test above happens to keep the document's CURRENT
  // priceMode equal to the CEO's ORIGINAL ceoPriceMode, which can never exercise the bug — the
  // marker used to format against the CURRENT mode, not the CEO's original one. This is the one
  // scenario that tells them apart: the rep switched the document to NET, but the CEO's own
  // decision was SPECIAL_SQM, so the marker must still read ceoSpecialPriceSqm (ราคาพิเศษ CEO),
  // not fall through to the NET-shaped "ราคา CEO: ... ส่วนลด ...%" text.
  it('mode mismatch: CURRENT priceMode=NET but ceoPriceMode=SPECIAL_SQM still formats against SPECIAL_SQM', async () => {
    const item = linkedNetTileItem({
      priceChangedFromCeo: true, unitPrice: 999, discountPct: 5,
      ceoListUnitPrice: null, ceoDiscountPct: null, ceoSpecialPriceSqm: 1350, ceoDirectNetPrice: null,
    });
    api.dealQuotations.get.mockResolvedValue({
      quotation: pricingRequestDraft({
        priceMode: 'NET', ceoPriceMode: 'SPECIAL_SQM', priceModeChangedFromCeo: true, items: [item],
      }),
    });
    renderEditor('/quotations/5');
    await waitFor(() => expect(screen.getByText('เปลี่ยนจากราคา CEO — ต้องให้ CEO อนุมัติ')).toBeTruthy());
    expect(screen.getByText('ราคาพิเศษ CEO: ฿1,350.00/ตร.ม.')).toBeTruthy();
    expect(screen.queryByText(/^ราคา CEO:/)).toBeNull();
  });

  it('header marker appears when priceModeChangedFromCeo is true, naming the CEO mode', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: pricingRequestDraft({ priceMode: 'DIRECT_NET', ceoPriceMode: 'NET', priceModeChangedFromCeo: true }),
    });
    renderEditor('/quotations/5');
    await waitFor(() => expect(screen.getByText('เปลี่ยนวิธีกรอกราคาจาก CEO — ต้องให้ CEO อนุมัติ')).toBeTruthy());
    expect(screen.getByText(/CEO เลือก: ราคาตั้ง/)).toBeTruthy();
  });

  it('no header marker when priceModeChangedFromCeo is false', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: pricingRequestDraft() });
    renderEditor('/quotations/5');
    await waitFor(() => expect(screen.getByText('ราคาจาก CEO')).toBeTruthy());
    expect(screen.queryByText('เปลี่ยนวิธีกรอกราคาจาก CEO — ต้องให้ CEO อนุมัติ')).toBeNull();
  });

  it('shows the "สร้างจากคำขอราคา" link to the source pricing request', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: pricingRequestDraft() });
    renderEditor('/quotations/5');
    await waitFor(() => expect(screen.getByText(/สร้างจากคำขอราคา PCR-2026-0042/)).toBeTruthy());
    const link = screen.getByText(/สร้างจากคำขอราคา PCR-2026-0042/).closest('a');
    expect(link.getAttribute('href')).toBe('/pricing-requests/42');
  });

  it('extra-row actions (เพิ่มตำแหน่ง / เพิ่มรายการในตำแหน่งนี้ / สินค้า-บริการอื่น / เพิ่มส่วนลดพิเศษ) are hidden, with the redirect note', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: pricingRequestDraft() });
    renderEditor('/quotations/5');
    await waitFor(() => expect(screen.getByText('ราคาจาก CEO')).toBeTruthy());
    expect(screen.getByText('เพิ่มรายการอื่นได้ที่คำขอราคา')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /เพิ่มตำแหน่ง/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /เพิ่มรายการในตำแหน่งนี้/ })).toBeNull();
    expect(screen.queryByRole('button', { name: 'เพิ่มสินค้าหรือบริการอื่นในตำแหน่งนี้' })).toBeNull();
    expect(screen.queryByRole('button', { name: /เพิ่มส่วนลดพิเศษ/ })).toBeNull();
  });

  it('submit is disabled with the S2 note, not the normal ส่งขออนุมัติ button', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: pricingRequestDraft() });
    renderEditor('/quotations/5');
    await waitFor(() => expect(screen.getByRole('button', { name: 'ส่งขออนุมัติ' })).toBeTruthy());
    const button = screen.getByRole('button', { name: 'ส่งขออนุมัติ' });
    expect(button.disabled).toBe(true);
    expect(button.getAttribute('title')).toBe('ส่งอนุมัติจะเปิดใช้ในขั้นถัดไป');
  });

  it('a DEAL_DIRECT quotation is completely unaffected: no CEO badge/marker, no PR link, normal submit and add-item actions', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: draft() });
    renderEditor('/quotations/5');
    await waitFor(() => expect(screen.getByRole('button', { name: /เพิ่มรายการในตำแหน่งนี้/ })).toBeTruthy());
    expect(screen.queryByText('ราคาจาก CEO')).toBeNull();
    expect(screen.queryByText('เปลี่ยนจากราคา CEO — ต้องให้ CEO อนุมัติ')).toBeNull();
    expect(screen.queryByText(/สร้างจากคำขอราคา/)).toBeNull();
    expect(screen.queryByText('เพิ่มรายการอื่นได้ที่คำขอราคา')).toBeNull();
    expect(screen.getByRole('button', { name: /เพิ่มตำแหน่ง/ })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'เพิ่มสินค้าหรือบริการอื่นในตำแหน่งนี้' })).toBeTruthy();
    expect(screen.getByRole('button', { name: /เพิ่มส่วนลดพิเศษ/ })).toBeTruthy();
    const submitButton = screen.getByRole('button', { name: 'ส่งขออนุมัติ' });
    expect(submitButton.getAttribute('title')).not.toBe('ส่งอนุมัติจะเปิดใช้ในขั้นถัดไป');
  });
});

// M5 fix (Opus review, 2026-09-20): before this fix, ONLY the item's `id` was adopted from a
// save response (QuotationEditorPage#applySavedQuotation, née #adoptServerIds) — every other
// server-computed field, including this exact marker, stayed at whatever the INITIAL load left
// it at until a full page reload re-ran the seeding effect. A rep who edited a linked line's
// price then saved would keep seeing the OLD (unchanged) badge even though the save just made it
// CEO-changed server-side, and reverting + saving again would leave the amber marker stuck.
describe('QuotationEditorPage — GLA-123 M5 fix: per-line CEO marker refreshes after save, no reload', () => {
  it('edit -> save -> the marker flips to "changed" from the SAVE response, without a reload', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: pricingRequestDraft() });
    api.dealQuotations.update.mockResolvedValue({
      quotation: pricingRequestDraft({
        priceModeChangedFromCeo: false,
        items: [linkedNetTileItem({ priceChangedFromCeo: true, discountPct: 20 })],
      }),
    });
    renderEditor('/quotations/5');
    await waitFor(() => expect(screen.getByText('ราคาจาก CEO')).toBeTruthy());
    expect(screen.queryByText('เปลี่ยนจากราคา CEO — ต้องให้ CEO อนุมัติ')).toBeNull();

    fireEvent.change(screen.getByLabelText('ส่วนลด %'), { target: { value: '20' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalledTimes(1));
    // The marker below comes from the SAVE RESPONSE's own priceChangedFromCeo/ceo* fields — this
    // test never re-renders from a fresh api.dealQuotations.get, so a pass here is only possible
    // if the save response was actually merged into local state.
    await waitFor(() => expect(screen.getByText('เปลี่ยนจากราคา CEO — ต้องให้ CEO อนุมัติ')).toBeTruthy());
    expect(screen.getByText('ราคา CEO: ฿83.00 · ส่วนลด 10%')).toBeTruthy();
    expect(screen.queryByText('ราคาจาก CEO')).toBeNull();
  });

  it('edit -> save (changed) -> revert -> save again -> the marker clears back to the badge', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: pricingRequestDraft() });
    api.dealQuotations.update
      .mockResolvedValueOnce({
        quotation: pricingRequestDraft({
          items: [linkedNetTileItem({ priceChangedFromCeo: true, discountPct: 20 })],
        }),
      })
      .mockResolvedValueOnce({
        quotation: pricingRequestDraft({
          items: [linkedNetTileItem({ priceChangedFromCeo: false, discountPct: 10 })],
        }),
      });
    renderEditor('/quotations/5');
    await waitFor(() => expect(screen.getByText('ราคาจาก CEO')).toBeTruthy());

    fireEvent.change(screen.getByLabelText('ส่วนลด %'), { target: { value: '20' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.getByText('เปลี่ยนจากราคา CEO — ต้องให้ CEO อนุมัติ')).toBeTruthy());

    fireEvent.change(screen.getByLabelText('ส่วนลด %'), { target: { value: '10' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalledTimes(2));

    await waitFor(() => expect(screen.queryByText('เปลี่ยนจากราคา CEO — ต้องให้ CEO อนุมัติ')).toBeNull());
    expect(screen.getByText('ราคาจาก CEO')).toBeTruthy();
  });
});
