import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QuotationEditorPage } from './QuotationEditorPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// Owner re-report 2026-09-16: "แก้หรือเพิ่ม Email ผู้สั่งซื้อภายหลังไม่ได้" / "แก้ขนาด/รหัสสินค้าเอง
// แต่ PDF ยังใช้ค่าเดิม" — the PDF/XLSX are rendered fresh from the DB on every download
// (DealQuotationService), but the download button used to fetch them with NO save first, so an
// edit sitting only in the editor's own state (or an autosave still on the wire) never reached the
// document it downloaded. Fixtures copied from QuotationEditorPage.saveRaces.test.jsx (that file's
// own header explains why copied rather than imported — it exports nothing) and trimmed to only
// what these cases need.
vi.setConfig({ testTimeout: 15000 });

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: { get: vi.fn(), create: vi.fn() },
      dealQuotations: {
        get: vi.fn(), create: vi.fn(), update: vi.fn(), calculateLine: vi.fn(), submit: vi.fn(),
        approve: vi.fn(), reject: vi.fn(), createRevision: vi.fn(), createReorder: vi.fn(), cancel: vi.fn(),
        downloadPdf: vi.fn(), downloadXlsx: vi.fn(),
      },
      catalog: { prices: vi.fn() },
      designers: { search: vi.fn(), getByCode: vi.fn().mockRejectedValue(new Error('not found')) },
      customers: {
        search: vi.fn(), create: vi.fn(), update: vi.fn(), projects: vi.fn(), createProject: vi.fn(),
        contacts: vi.fn(), createContact: vi.fn(), updateContact: vi.fn(),
      },
    },
  };
});

const salesUser = { id: 6, name: 'คุณสมหมาย ขายดี', role: 'sales', employeeId: null };

const CUSTOMER = {
  id: 5, name: 'บริษัท แฟชั่นไอส์แลนด์ จำกัด', taxId: '0105551234567', address: null, branch: 'สำนักงานใหญ่', phone: '02-000-0000',
};
const CONTACTS = [
  { id: 6, customerId: 5, firstName: 'ณัฐพงศ์', lastName: 'ศรีวิไล', phone: '086-222-3333', email: 'nattapong@fashionisland.co.th' },
];

const TILE_ITEM = {
  id: 101, seq: 1, lineType: 'TILE', locationLabel: null, catalogPriceId: null, productCode: null,
  brand: 'Marazzi', model: 'Trilogy', color: 'Ash', texture: 'Matt', sizeText: '60x60', thicknessMm: 10,
  sqmPerPiece: 0.36, quantityMode: 'AREA', areaSqm: 20, piecesInput: null, wastageMode: 'PERCENT',
  wastageValue: 0, piecesPerBox: 3, unitPrice: 850, discountPct: 0, originCountry: null,
  leadTimeMinDays: 30, leadTimeMaxDays: 45, itemNotes: null,
  piecesPerSqm: 2.78, piecesBeforeWastage: 56, piecesAfterWastage: 56, piecesFinal: 57, boxes: 19,
  netUnitPrice: 850, lineAmount: 48450, descriptionLine: 'Trilogy Ash', sizeLine: '60x60', calculationLine: '(calc)',
  quantity: 57, unit: 'แผ่น', specialPriceSqm: null, adjustmentPct: null, adjustmentDeadline: null,
  specialPriceLine: null, adjustmentAmount: null,
};

function draft(overrides = {}) {
  return {
    id: 5, number: 'QT-2026-0005', ticketId: 18, docStatus: 'DRAFT', revisionNo: 1, parentQuotationId: null,
    salesRepId: 6, salesRepName: 'คุณสมหมาย ขายดี', salesRepPhone: '081-000-0000', createdByName: 'คุณสมหมาย ขายดี',
    approvalNote: null, quotationDate: '2026-09-11',
    customerName: CUSTOMER.name, customerAddress: null, customerTaxId: CUSTOMER.taxId, customerPhone: CUSTOMER.phone,
    contactId: 6, contactName: 'ณัฐพงศ์ ศรีวิไล', contactPhone: '086-222-3333', contactEmail: 'nattapong@fashionisland.co.th',
    projectName: 'โครงการ A', deptCode: null, unitCode: null, offerDate: '2026-09-01', depositPercent: 30,
    remainderMode: 'ON_DELIVERY', creditDays: null, validityDays: 30, validityDate: null, customerNotes: null,
    priceMode: 'NET', documentLanguage: 'TH', subtotalAmount: 48450, vatAmount: 3391.5, grandTotal: 51841.5,
    currency: 'THB', approverHasSignature: false, items: [TILE_ITEM],
    createdAt: '2026-09-11T02:00:00Z', updatedAt: '2026-09-11T02:00:00Z',
    ...overrides,
  };
}

function renderEditor(path, showToast = vi.fn()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/quotations/new" element={<QuotationEditorPage user={salesUser} showToast={showToast} />} />
          <Route path="/quotations/:id" element={<QuotationEditorPage user={salesUser} showToast={showToast} />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return showToast;
}

beforeEach(() => {
  vi.clearAllMocks();
  // jsdom implements neither -- the real handler calls both unconditionally on a successful
  // download. Not asserted on directly; just needs to not throw.
  global.URL.createObjectURL = vi.fn(() => 'blob:mock');
  global.URL.revokeObjectURL = vi.fn();
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});

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
  api.dealQuotations.get.mockResolvedValue({ quotation: draft() });
  api.dealQuotations.update.mockImplementation(async () => ({ quotation: draft() }));
  api.dealQuotations.calculateLine.mockResolvedValue({
    item: { netUnitPrice: 453.84, lineAmount: 25869.12, calculationLine: '(server calc)' },
  });
  api.dealQuotations.downloadPdf.mockResolvedValue({ type: 'application/pdf' });
  api.dealQuotations.downloadXlsx.mockResolvedValue({
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  });
});

describe('download flushes unsaved edits before rendering the document (2026-09-16 fix)', () => {
  it('an edit typed just before clicking ดาวน์โหลด PDF is saved FIRST, then the PDF is fetched -- order asserted', async () => {
    renderEditor('/quotations/5');
    const notes = await screen.findByLabelText('หมายเหตุเพิ่มเติม');
    fireEvent.change(notes, { target: { value: 'พิกัดใหม่' } });

    // Click well within the 2s autosave debounce -- nothing has saved yet on its own.
    fireEvent.click(screen.getByRole('button', { name: 'ดาวน์โหลด PDF' }));

    await waitFor(() => expect(api.dealQuotations.downloadPdf).toHaveBeenCalledWith('5'));
    expect(api.dealQuotations.update).toHaveBeenCalledTimes(1);
    expect(api.dealQuotations.update.mock.calls[0][1].customerNotes).toBe('พิกัดใหม่');
    expect(api.dealQuotations.update.mock.invocationCallOrder[0])
      .toBeLessThan(api.dealQuotations.downloadPdf.mock.invocationCallOrder[0]);
  });

  // D1 fix (Opus review 2026-09-16): the original version of this fix refused a download for ANY
  // blocking validation error, even on a quotation the rep never touched -- an existing incomplete
  // DRAFT (sent back for correction, or a row missing ความหนา, ~41% of the prod catalogue) has
  // nothing this flush needs to save, so it must still download the stored document exactly as it
  // could before this whole fix landed. Refusal is now gated on there being unsaved work the flush
  // actually cannot save (dirty AND invalid) -- see the next test for that case.
  it('a CLEAN, untouched quotation with a blocking validation error (no ผู้สั่งซื้อ) still downloads the stored document -- nothing to flush (D1 fix)', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: draft({ contactId: null, contactName: null, contactPhone: null, contactEmail: null }),
    });
    const showToast = renderEditor('/quotations/5');
    await screen.findByText('ข้อมูลที่ยังไม่ครบ');

    fireEvent.click(screen.getByRole('button', { name: 'ดาวน์โหลด PDF' }));

    await waitFor(() => expect(api.dealQuotations.downloadPdf).toHaveBeenCalledWith('5'));
    expect(api.dealQuotations.update).not.toHaveBeenCalled();
    expect(showToast).not.toHaveBeenCalledWith('error', expect.anything());
  });

  it('a DIRTY quotation with a blocking validation error (no ผู้สั่งซื้อ) still refuses the download outright -- no PDF call, a Thai toast instead', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: draft({ contactId: null, contactName: null, contactPhone: null, contactEmail: null }),
    });
    const showToast = renderEditor('/quotations/5');
    await screen.findByText('ข้อมูลที่ยังไม่ครบ');
    // Touch the form -- there is now unsaved work this flush cannot save because it is invalid.
    fireEvent.change(screen.getByLabelText('หมายเหตุเพิ่มเติม'), { target: { value: 'พิกัดใหม่' } });

    fireEvent.click(screen.getByRole('button', { name: 'ดาวน์โหลด PDF' }));

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', 'ยังดาวน์โหลดไม่ได้ — กรุณากรอกข้อมูลให้ครบก่อน'));
    expect(api.dealQuotations.update).not.toHaveBeenCalled();
    expect(api.dealQuotations.downloadPdf).not.toHaveBeenCalled();
  });

  it('a non-editable (APPROVED) quotation downloads directly -- no save attempted', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: draft({ docStatus: 'APPROVED' }) });
    renderEditor('/quotations/5');

    fireEvent.click(await screen.findByRole('button', { name: 'ดาวน์โหลด Excel' }));

    await waitFor(() => expect(api.dealQuotations.downloadXlsx).toHaveBeenCalledWith('5'));
    expect(api.dealQuotations.update).not.toHaveBeenCalled();
  });

  // D2 fix (Opus review 2026-09-16, proven with a probe asserting the toast count -- "expected 2
  // to be 1"): updateMutation's own onError already toasts a save failure; handleDownload's catch
  // used to re-toast the SAME message, stacking two identical red toasts for one failure. The
  // original test here only asserted `toHaveBeenCalledWith`, which cannot see a duplicate -- it
  // passes whether the toast fired once or twice, which is exactly how the bug shipped unnoticed.
  it('a save failure during the flush shows the error EXACTLY ONCE and never downloads a stale PDF (D2 fix)', async () => {
    const err = Object.assign(new Error('ยอดรวมหลังหักส่วนลดพิเศษติดลบ'), { status: 400 });
    api.dealQuotations.update.mockRejectedValue(err);
    const showToast = renderEditor('/quotations/5');
    const notes = await screen.findByLabelText('หมายเหตุเพิ่มเติม');
    fireEvent.change(notes, { target: { value: 'แก้ไข' } });

    fireEvent.click(screen.getByRole('button', { name: 'ดาวน์โหลด PDF' }));

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', 'ยอดรวมหลังหักส่วนลดพิเศษติดลบ'));
    expect(api.dealQuotations.downloadPdf).not.toHaveBeenCalled();
    // The count, not just the call -- a duplicate toast passes `toHaveBeenCalledWith` too.
    expect(showToast.mock.calls.filter(
      (c) => c[0] === 'error' && c[1] === 'ยอดรวมหลังหักส่วนลดพิเศษติดลบ',
    )).toHaveLength(1);
  });

  it('clicking ดาวน์โหลด PDF twice fast only flushes+downloads once', async () => {
    renderEditor('/quotations/5');
    const notes = await screen.findByLabelText('หมายเหตุเพิ่มเติม');
    fireEvent.change(notes, { target: { value: 'x' } });

    const pdfBtn = screen.getByRole('button', { name: 'ดาวน์โหลด PDF' });
    fireEvent.click(pdfBtn);
    fireEvent.click(pdfBtn);

    await waitFor(() => expect(api.dealQuotations.downloadPdf).toHaveBeenCalledTimes(1));
    expect(api.dealQuotations.update).toHaveBeenCalledTimes(1);
  });

  // D4 fix (Opus review 2026-09-16): handleDownload used to read `dirty`/`hasValidationErrors`
  // from the render closure captured at CLICK time, and stays suspended across several `await`s.
  // Here the quotation is clean (not dirty) at the moment of the click, so the OLD code's frozen
  // `dirty: false` would skip the save entirely -- and would also have just cancelled the fresh
  // autosave debounce the edit below schedules, with nothing left to reschedule it, losing the
  // edit until the next keystroke. `fireEvent.change` right after `fireEvent.click` (with no
  // `await` in between) lands synchronously, before the microtask that resumes handleDownload past
  // its first `await` ever gets a turn -- so this reliably reproduces "an edit lands while the
  // flush is already suspended", regardless of how fast the contact-picker flush resolves.
  it('an edit made DURING the flush (after the click, before it decided whether to save) is still saved -- no further typing needed (D4 fix)', async () => {
    renderEditor('/quotations/5');
    await screen.findByLabelText('หมายเหตุเพิ่มเติม'); // freshly loaded: clean, not dirty.

    fireEvent.click(screen.getByRole('button', { name: 'ดาวน์โหลด PDF' }));
    fireEvent.change(screen.getByLabelText('หมายเหตุเพิ่มเติม'), { target: { value: 'edit during flush' } });

    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalledTimes(1));
    expect(api.dealQuotations.update.mock.calls[0][1].customerNotes).toBe('edit during flush');
    await waitFor(() => expect(api.dealQuotations.downloadPdf).toHaveBeenCalledWith('5'));
  });
});
