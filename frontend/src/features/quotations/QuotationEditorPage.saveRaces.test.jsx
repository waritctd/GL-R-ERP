import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QuotationEditorPage, AUTOSAVE_DELAY_MS } from './QuotationEditorPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// Regression coverage for the three save-race defects PR #932 shipped in
// QuotationEditorPage.jsx (lost edits on save, submit-vs-autosave, dropped item ids) plus the
// autosave error backoff. Fixtures/mocking pattern copied from QuotationEditorPage.v3.test.jsx
// (that file owns the v3/v3b behaviour; this one owns the save-sequencing behaviour) rather than
// imported, since that file exports nothing -- see its own header comment for why a separate file.
vi.setConfig({ testTimeout: 15000 });

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: { get: vi.fn(), create: vi.fn() },
      dealQuotations: {
        get: vi.fn(), create: vi.fn(), update: vi.fn(), calculateLine: vi.fn(), submit: vi.fn(),
        approve: vi.fn(), reject: vi.fn(), createRevision: vi.fn(), cancel: vi.fn(),
        downloadPdf: vi.fn(), downloadXlsx: vi.fn(),
      },
      catalog: { prices: vi.fn() },
      customers: {
        search: vi.fn(), create: vi.fn(), update: vi.fn(), projects: vi.fn(), createProject: vi.fn(),
        contacts: vi.fn(), createContact: vi.fn(),
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

// A COMPLETE stored tile row (validateQuotationItem finds nothing missing), server id 101.
const TILE_ITEM = {
  id: 101, seq: 1, lineType: 'TILE', locationLabel: null, catalogPriceId: null, productCode: null,
  brand: 'Marazzi', model: 'Trilogy', color: 'Ash', texture: 'Matt', sizeText: '60x60', thicknessMm: 10,
  sqmPerPiece: 0.36, quantityMode: 'AREA', areaSqm: 20, piecesInput: null, wastageMode: 'PERCENT',
  wastageValue: 0, piecesPerBox: 3, unitPrice: 850, discountPct: 0, originCountry: null,
  leadTimeMinDays: null, leadTimeMaxDays: null, itemNotes: null,
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

/** A promise this test resolves/rejects by hand, to hold one PUT "in flight" across several
 * assertions -- the shape every case below needs to force the race it is checking. */
function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
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
  api.dealQuotations.get.mockResolvedValue({ quotation: draft() });
  api.dealQuotations.update.mockImplementation(async () => ({ quotation: draft() }));
  api.dealQuotations.submit.mockResolvedValue({ quotation: draft({ docStatus: 'PENDING_APPROVAL' }) });
  api.dealQuotations.calculateLine.mockResolvedValue({
    item: { netUnitPrice: 453.84, lineAmount: 25869.12, calculationLine: '(server calc)' },
  });
});

describe('lost edit on save (#932 defect 1)', () => {
  it('an edit typed while the autosave PUT is still in flight is NOT lost -- a second PUT carries it', async () => {
    renderEditor('/quotations/5');
    const notes = await screen.findByLabelText('หมายเหตุเพิ่มเติม');

    const first = deferred();
    api.dealQuotations.update.mockImplementationOnce(() => first.promise);

    fireEvent.change(notes, { target: { value: 'first edit' } });
    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalledTimes(1), { timeout: 4000 });
    expect(api.dealQuotations.update.mock.calls[0][1].customerNotes).toBe('first edit');

    // A second edit lands while PUT #1 is still on the wire.
    fireEvent.change(notes, { target: { value: 'second edit' } });
    first.resolve({ quotation: draft({ customerNotes: 'first edit' }) });

    // dirty must have stayed true, so the next debounce tick fires a SECOND PUT carrying it.
    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalledTimes(2), { timeout: 4000 });
    expect(api.dealQuotations.update.mock.calls[1][1].customerNotes).toBe('second edit');
  });

  it('...variant through submit: ส่งขออนุมัติ still saves the newer edit before submitting', async () => {
    renderEditor('/quotations/5');
    const notes = await screen.findByLabelText('หมายเหตุเพิ่มเติม');

    const first = deferred();
    api.dealQuotations.update.mockImplementationOnce(() => first.promise);
    fireEvent.change(notes, { target: { value: 'first edit' } });
    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalledTimes(1), { timeout: 4000 });

    fireEvent.change(notes, { target: { value: 'second edit' } });
    first.resolve({ quotation: draft({ customerNotes: 'first edit' }) });

    const headerSubmit = screen.getByRole('button', { name: 'ส่งขออนุมัติ' });
    await waitFor(() => expect(headerSubmit.disabled).toBe(false));
    fireEvent.click(headerSubmit);
    await screen.findByText('การแก้ไขที่ยังไม่บันทึกจะถูกบันทึกก่อนส่ง');
    fireEvent.click(screen.getAllByRole('button', { name: 'ส่งขออนุมัติ' }).at(-1));

    await waitFor(() => expect(api.dealQuotations.submit).toHaveBeenCalledWith('5'));
    expect(api.dealQuotations.update).toHaveBeenCalledTimes(2);
    expect(api.dealQuotations.update.mock.calls[1][1].customerNotes).toBe('second edit');
    expect(api.dealQuotations.update.mock.invocationCallOrder[1])
      .toBeLessThan(api.dealQuotations.submit.mock.invocationCallOrder[0]);
  });
});

describe('submit vs autosave (#932 defect 2)', () => {
  it('editing then confirming ส่งขออนุมัติ within the debounce sends exactly ONE update, before submit, and nothing races in after', async () => {
    const showToast = renderEditor('/quotations/5');
    const notes = await screen.findByLabelText('หมายเหตุเพิ่มเติม');

    const pending = deferred();
    api.dealQuotations.update.mockImplementationOnce(() => pending.promise);

    fireEvent.change(notes, { target: { value: 'edited' } });
    // Open + confirm WITHIN the 2s debounce window -- no autosave tick has fired yet.
    fireEvent.click(screen.getByRole('button', { name: 'ส่งขออนุมัติ' }));
    fireEvent.click(screen.getAllByRole('button', { name: 'ส่งขออนุมัติ' }).at(-1));

    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalledTimes(1));

    // Hold submit's own pre-save PUT in flight PAST where the 2s autosave debounce would have
    // fired -- a still-scheduled (uncancelled) timer would show up as a second call here.
    await new Promise((r) => { setTimeout(r, AUTOSAVE_DELAY_MS + 500); });
    expect(api.dealQuotations.update).toHaveBeenCalledTimes(1);
    expect(api.dealQuotations.submit).not.toHaveBeenCalled();

    pending.resolve({ quotation: draft({ customerNotes: 'edited' }) });
    await waitFor(() => expect(api.dealQuotations.submit).toHaveBeenCalledWith('5'));

    // No update call after submit either.
    expect(api.dealQuotations.update).toHaveBeenCalledTimes(1);
    expect(api.dealQuotations.update.mock.invocationCallOrder[0])
      .toBeLessThan(api.dealQuotations.submit.mock.invocationCallOrder[0]);
    expect(showToast).not.toHaveBeenCalledWith('error', expect.anything());
  });
});

describe('pending save disables submit (#932 defect 2, UI half)', () => {
  it('the header ส่งขออนุมัติ is disabled while an autosave is in flight, and re-enables once it resolves', async () => {
    renderEditor('/quotations/5');
    const notes = await screen.findByLabelText('หมายเหตุเพิ่มเติม');

    const pending = deferred();
    api.dealQuotations.update.mockImplementationOnce(() => pending.promise);

    fireEvent.change(notes, { target: { value: 'x' } });
    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalledTimes(1), { timeout: 4000 });

    const submitBtn = screen.getByRole('button', { name: 'ส่งขออนุมัติ' });
    expect(submitBtn.disabled).toBe(true);

    pending.resolve({ quotation: draft({ customerNotes: 'x' }) });
    await waitFor(() => expect(submitBtn.disabled).toBe(false));
  });
});

describe('item ids travel and get adopted (#932 defect 3)', () => {
  it('sends the loaded row\'s server id, adopts a NEW row\'s minted id after save, and sends it back next time', async () => {
    renderEditor('/quotations/5');
    await screen.findByRole('button', { name: 'เพิ่มสินค้าหรือบริการอื่นในตำแหน่งนี้' });

    // Save #1: only the loaded TILE row -- its payload already carries its server id.
    fireEvent.change(screen.getByLabelText('หมายเหตุเพิ่มเติม'), { target: { value: 'v1' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalledTimes(1));
    expect(api.dealQuotations.update.mock.calls[0][1].items[0]).toMatchObject({ id: 101 });

    // Add a PLAIN row -- no id yet -- and save. The server mints it id 202, same order sent.
    fireEvent.click(screen.getByRole('button', { name: 'เพิ่มสินค้าหรือบริการอื่นในตำแหน่งนี้' }));
    fireEvent.click(screen.getByRole('button', { name: 'ค่าขนส่ง' }));
    fireEvent.change(document.getElementById('plain-price-1'), { target: { value: '100' } });

    api.dealQuotations.update.mockImplementationOnce(async () => ({
      quotation: draft({
        items: [
          { ...TILE_ITEM, id: 101 },
          {
            id: 202, seq: 2, lineType: 'PLAIN', descriptionLine: 'ค่าขนส่ง', quantity: 1, unit: 'งาน',
            unitPrice: 100, discountPct: 0, netUnitPrice: 100, lineAmount: 100,
          },
        ],
      }),
    }));
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalledTimes(2));
    expect(api.dealQuotations.update.mock.calls[1][1].items.map((it) => it.id)).toEqual([101, null]);

    // Edit again -- the NEXT payload must carry the id the previous save adopted.
    fireEvent.change(screen.getByLabelText('หมายเหตุเพิ่มเติม'), { target: { value: 'v2' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalledTimes(3));
    expect(api.dealQuotations.update.mock.calls[2][1].items.map((it) => it.id)).toEqual([101, 202]);
  });
});

describe('autosave error backoff (#932 defect 4, optional)', () => {
  it('a repeatedly-refused payload backs off until an edit actually changes it', async () => {
    const showToast = renderEditor('/quotations/5');
    const notes = await screen.findByLabelText('หมายเหตุเพิ่มเติม');

    const err = Object.assign(new Error('ยอดรวมหลังหักส่วนลดพิเศษติดลบ'), { status: 400 });
    api.dealQuotations.update.mockRejectedValue(err);

    fireEvent.change(notes, { target: { value: 'edit 1' } });
    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalledTimes(1), { timeout: 4000 });
    await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', 'ยอดรวมหลังหักส่วนลดพิเศษติดลบ'));

    // Wait past TWO more debounce periods with NO further edit -- the backoff must suppress both,
    // and the error must not re-toast.
    await new Promise((r) => { setTimeout(r, AUTOSAVE_DELAY_MS * 2 + 500); });
    expect(api.dealQuotations.update).toHaveBeenCalledTimes(1);
    expect(showToast.mock.calls.filter((c) => c[0] === 'error')).toHaveLength(1);

    // A further edit changes the payload -- the backoff lifts on its own.
    fireEvent.change(notes, { target: { value: 'edit 2' } });
    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalledTimes(2), { timeout: 4000 });
  });
});
