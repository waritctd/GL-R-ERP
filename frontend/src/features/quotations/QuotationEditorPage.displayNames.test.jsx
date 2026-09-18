import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QuotationEditorPage } from './QuotationEditorPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// V179 (owner feedback #4, 2026-09-14) — the ผู้พิมพ์/พนักงานขาย print-name override dropdowns.
// A separate file from QuotationEditorPage.v3.test.jsx, same mounting pattern, scoped to just
// this feature's own behaviour.
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

const DISPLAY_OPTIONS = [
  { id: 4, name: 'พนักงาน ได้สิทธิ์' },
  { id: 9, name: 'ผู้จัดการ ฝ่ายขาย' },
];

function draft(overrides = {}) {
  return {
    id: 5, number: 'QT-2026-0005', ticketId: 18, docStatus: 'DRAFT', revisionNo: 1, parentQuotationId: null,
    salesRepId: 6, salesRepName: 'คุณสมหมาย ขายดี', salesRepPhone: '081-000-0000', createdByName: 'คุณสมหมาย ขายดี',
    approvalNote: null, quotationDate: '2026-09-11',
    customerName: CUSTOMER.name, customerAddress: null, customerTaxId: CUSTOMER.taxId, customerPhone: CUSTOMER.phone,
    contactId: 6, contactName: 'ณัฐพงศ์ ศรีวิไล', contactPhone: '086-222-3333', contactEmail: 'nattapong@fashionisland.co.th',
    projectName: 'โครงการ A', deptCode: null, unitCode: 'A001', offerDate: '2026-09-01', depositPercent: 30,
    remainderMode: 'ON_DELIVERY', creditDays: null, validityDays: 30, validityDate: null, customerNotes: null,
    priceMode: 'NET', documentLanguage: 'TH', subtotalAmount: 48450, vatAmount: 3391.5, grandTotal: 51841.5,
    currency: 'THB', approverHasSignature: false, items: [TILE_ITEM],
    printedByDisplayId: null, printedByDisplayName: null, printedByDisplayNameEn: null,
    salesRepDisplayId: null, salesRepDisplayName: null, salesRepDisplayNameEn: null, salesRepDisplayPhone: null,
    createdAt: '2026-09-11T02:00:00Z', updatedAt: '2026-09-11T02:00:00Z',
    ...overrides,
  };
}

function renderEditor(path) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/quotations/new" element={<QuotationEditorPage user={salesUser} showToast={vi.fn()} />} />
          <Route path="/quotations/:id" element={<QuotationEditorPage user={salesUser} showToast={vi.fn()} />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const byId = (id) => document.getElementById(id);

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
  api.dealQuotations.displayNameOptions.mockResolvedValue({ items: DISPLAY_OPTIONS });
});

describe('QuotationEditorPage — V179 ผู้พิมพ์/พนักงานขาย print-name selectors', () => {
  it('fetches the options and renders both selects defaulted to "(ค่าเริ่มต้น)"', async () => {
    renderEditor('/quotations/5');
    await waitFor(() => expect(api.dealQuotations.displayNameOptions).toHaveBeenCalled());
    await waitFor(() => expect(byId('printedByDisplayId')).toBeTruthy());
    expect(byId('printedByDisplayId').value).toBe('');
    expect(byId('salesRepDisplayId').value).toBe('');
    // Both options populate both selects (they share the same eligible union).
    const printedOptionTexts = [...byId('printedByDisplayId').options].map((o) => o.textContent);
    expect(printedOptionTexts).toEqual(['(ค่าเริ่มต้น)', 'พนักงาน ได้สิทธิ์', 'ผู้จัดการ ฝ่ายขาย']);
  });

  it('a draft that already carries display ids pre-selects them', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: draft({ printedByDisplayId: 4, printedByDisplayName: 'พนักงาน ได้สิทธิ์', salesRepDisplayId: 9, salesRepDisplayName: 'ผู้จัดการ ฝ่ายขาย' }),
    });
    renderEditor('/quotations/5');
    await waitFor(() => expect(byId('printedByDisplayId')?.value).toBe('4'));
    expect(byId('salesRepDisplayId').value).toBe('9');
  });

  it('choosing an option and saving sends its id as a NUMBER in the payload', async () => {
    renderEditor('/quotations/5');
    await waitFor(() => expect(byId('printedByDisplayId')).toBeTruthy());
    fireEvent.change(byId('printedByDisplayId'), { target: { value: '4' } });
    fireEvent.change(byId('salesRepDisplayId'), { target: { value: '9' } });
    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalled());
    const [, payload] = api.dealQuotations.update.mock.calls[0];
    expect(payload.printedByDisplayId).toBe(4);
    expect(payload.salesRepDisplayId).toBe(9);
  });

  it('switching a set override back to "(ค่าเริ่มต้น)" sends null, not the string ""', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: draft({ printedByDisplayId: 4, printedByDisplayName: 'พนักงาน ได้สิทธิ์' }),
    });
    renderEditor('/quotations/5');
    await waitFor(() => expect(byId('printedByDisplayId')?.value).toBe('4'));
    fireEvent.change(byId('printedByDisplayId'), { target: { value: '' } });
    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalled());
    const [, payload] = api.dealQuotations.update.mock.calls[0];
    expect(payload.printedByDisplayId).toBeNull();
  });

  // Opus review nit N1 (2026-09-14): before this fix, the "ข้อมูลลูกค้าและผู้ขาย" read-only strip
  // showed the REAL rep (salesRepName/salesRepPhone) under พนักงานขาย even when salesRepDisplayId
  // was set, disagreeing with what QuotationDocumentView's preview and the printed PDF show for
  // the exact same document. That strip is now the editable card select itself (bb5de447), so
  // this test's own assertion changed with it -- see the corrected name and body below. Second
  // Opus follow-up nit (2026-09-14): renamed from "the context strip shows the salesRepDisplay
  // override, not the real rep, once one is set", which described the read-only strip's TEXT this
  // test no longer checks and had come to overlap the twin-select-sync test further down.
  it('the เงื่อนไข select and the card select both reflect a saved salesRepDisplay override', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: draft({
        salesRepDisplayId: 9, salesRepDisplayName: 'ผู้จัดการ ฝ่ายขาย', salesRepDisplayPhone: '089-999-9999',
      }),
    });
    renderEditor('/quotations/5');
    await waitFor(() => expect(byId('salesRepDisplayId')?.value).toBe('9'));
    // The override is what's SELECTED on the card's own twin select too -- not text-matched
    // (a collapsed <select>'s other <option> labels are still in the DOM and would make a plain
    // text search meaningless the moment the empty option ALSO carries text, per the fix just
    // below this test).
    expect(byId('salesRepDisplayIdCard').value).toBe('9');
  });

  // Opus review fix (2026-09-14): the card's own select's EMPTY option (value="") means "use the
  // real name" -- its label must show the REAL rep even when an override is already saved, not
  // the override itself (which would tell the rep the option that TURNS OFF the override is
  // somehow the override's own name).
  it('the card select\'s empty option always labels the REAL rep, even with an override saved', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: draft({
        salesRepDisplayId: 9, salesRepDisplayName: 'ผู้จัดการ ฝ่ายขาย', salesRepDisplayPhone: '089-999-9999',
      }),
    });
    renderEditor('/quotations/5');
    await waitFor(() => expect(byId('salesRepDisplayIdCard')?.value).toBe('9'));
    const emptyOption = [...byId('salesRepDisplayIdCard').options].find((o) => o.value === '');
    expect(emptyOption.textContent).toBe('คุณสมหมาย ขายดี · T.081-000-0000');
  });

  it('the context strip shows the real rep when no salesRepDisplay override is set', async () => {
    renderEditor('/quotations/5');
    await waitFor(() => expect(byId('salesRepDisplayId')?.value).toBe(''));
    expect(screen.getByText('คุณสมหมาย ขายดี · T.081-000-0000')).toBeTruthy();
  });

  // Owner feedback 2026-09-14 ("keep both, add inline edit here too"): the card's own
  // salesRepDisplayIdCard select is a SECOND control over the SAME terms.salesRepDisplayId the
  // เงื่อนไข panel's own salesRepDisplayId select drives -- picking a value in either one must move
  // the other.
  it('the card select and the เงื่อนไข select stay in sync (either one moves the other)', async () => {
    renderEditor('/quotations/5');
    await waitFor(() => expect(byId('salesRepDisplayIdCard')).toBeTruthy());
    expect(byId('salesRepDisplayIdCard').value).toBe('');
    expect(byId('salesRepDisplayId').value).toBe('');

    fireEvent.change(byId('salesRepDisplayIdCard'), { target: { value: '9' } });
    expect(byId('salesRepDisplayId').value).toBe('9'); // moved by the CARD's own select

    fireEvent.change(byId('salesRepDisplayId'), { target: { value: '4' } });
    expect(byId('salesRepDisplayIdCard').value).toBe('4'); // moved by the เงื่อนไข select
  });
});

// Owner feedback 2026-09-14 ("sometimes there's a typo in the ... project so they should be able
// to correct it") -- โครงการ was static text on this same card; now a real, saved field.
describe('QuotationEditorPage — โครงการ is editable on the summary card', () => {
  it('pre-fills from the quotation and sends an edit in the save payload', async () => {
    renderEditor('/quotations/5');
    await waitFor(() => expect(byId('projectNameCard')?.value).toBe('โครงการ A'));

    fireEvent.change(byId('projectNameCard'), { target: { value: 'Associates By Choice' } });
    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalled());
    const [, payload] = api.dealQuotations.update.mock.calls[0];
    expect(payload.projectName).toBe('Associates By Choice');
  });

  it('a blank projectName sends null, not the empty string', async () => {
    renderEditor('/quotations/5');
    await waitFor(() => expect(byId('projectNameCard')?.value).toBe('โครงการ A'));

    fireEvent.change(byId('projectNameCard'), { target: { value: '' } });
    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalled());
    const [, payload] = api.dealQuotations.update.mock.calls[0];
    expect(payload.projectName).toBeNull();
  });
});
