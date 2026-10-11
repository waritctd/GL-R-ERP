import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QuotationEditorPage } from './QuotationEditorPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// เอกสารแนบ ON THE PAGE: where the panel sits, who gets it, and that it does not disable
// บันทึกร่าง or ส่งขออนุมัติ ("this can be optional"). What the panel itself shows and does is
// QuotationAttachmentsPanel.test.jsx. This file is only its place in /quotations/new and
// /quotations/:id.
//
// The structure pinned here is the information-architecture decision. There is one panel, and it
// is the LAST block of the page in both modes. In the edit form it follows the บันทึกร่าง bar,
// because an upload is stored at once and is not part of what that button saves. In the details
// view it follows the last panel, because the details reproduce the printed form and these files
// are never printed.
//
// Evidence note: every server answer is a vi.fn(). That account and import are REFUSED the files
// is the server's doing, proven in DealQuotationAttachmentAuthzIntegrationTest; here it is only
// that the page does not draw the panel for them, and does not ask.
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
        downloadPdf: vi.fn(), downloadXlsx: vi.fn(), displayNameOptions: vi.fn(),
        listAttachments: vi.fn(), uploadAttachment: vi.fn(), deleteAttachment: vi.fn(), attachmentUrl: vi.fn(),
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

const TITLE = 'เอกสารแนบ';

const salesUser = { id: 6, name: 'คุณสมหมาย ขายดี', role: 'sales', employeeId: null };
const ceoUser = { id: 8, name: 'ราม', role: 'ceo', employeeId: 136 };
const importUser = { id: 7, name: 'คุณนำเข้า พานิช', role: 'import', employeeId: null };
const accountUser = { id: 5, name: 'คุณบัญชี รอบคอบ', role: 'account', employeeId: null };

const CUSTOMER = {
  id: 5, name: 'บริษัท แฟชั่นไอส์แลนด์ จำกัด', taxId: '0105551234567', address: null, branch: 'สำนักงานใหญ่', phone: '02-000-0000',
};

// A complete stored row: nothing is missing, so nothing but the panel could stand in a button's way.
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

function quotation(overrides = {}) {
  return {
    id: 5, number: 'QT-2026-0005-1', ticketId: 18, docStatus: 'DRAFT', revisionNo: 1, parentQuotationId: null,
    origin: 'DEAL_DIRECT', salesRepId: 6, salesRepName: 'คุณสมหมาย ขายดี', salesRepPhone: '081-000-0000',
    createdByName: 'คุณสมหมาย ขายดี', approvalNote: null, quotationDate: '2026-09-11',
    customerName: CUSTOMER.name, customerAddress: '1 ถนนทดสอบ', customerTaxId: CUSTOMER.taxId, customerPhone: CUSTOMER.phone,
    contactId: null, contactName: null, contactPhone: null, contactEmail: null,
    projectName: 'โครงการ A', deptCode: null, unitCode: null, offerDate: '2026-09-01', depositPercent: 30,
    remainderMode: 'ON_DELIVERY', creditDays: null, validityDays: 30, validityDate: null, customerNotes: null,
    priceMode: 'NET', documentLanguage: 'TH', subtotalAmount: 48450, vatAmount: 3391.5, grandTotal: 51841.5,
    currency: 'THB', approverHasSignature: false, items: [TILE_ITEM],
    createdAt: '2026-09-11T02:00:00Z', updatedAt: '2026-09-11T02:00:00Z',
    ...overrides,
  };
}

const VENDOR_QUOTE = {
  id: 71, quotationId: 5, quotationNumber: 'QT-2026-0005-1', fileName: 'ใบเสนอราคา ผู้ขาย เอ.pdf',
  mimeType: 'application/pdf', fileSize: 250880, uploadedById: 6, uploadedByName: 'คุณสมหมาย ขายดี',
  uploadedAt: '2026-10-12T05:00:00Z', inherited: false,
};

function open(path, user) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/quotations/new" element={<QuotationEditorPage user={user} showToast={vi.fn()} />} />
          <Route path="/quotations/:id" element={<QuotationEditorPage user={user} showToast={vi.fn()} />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const panelHeading = () => screen.queryByRole('heading', { name: TITLE });
const panel = () => panelHeading()?.closest('section') ?? null;
const follows = (earlier, later) => Boolean(earlier.compareDocumentPosition(later) & Node.DOCUMENT_POSITION_FOLLOWING);
const fileLink = () => screen.queryByRole('link', { name: /ใบเสนอราคา ผู้ขาย เอ\.pdf/ });
const attachInput = () => screen.queryByLabelText(/แนบเอกสาร/);

beforeEach(() => {
  vi.clearAllMocks();
  api.tickets.get.mockResolvedValue({
    ticket: {
      summary: {
        id: 18, createdById: 6, createdByName: 'คุณสมหมาย ขายดี', customerName: CUSTOMER.name, customerId: 5,
        projectId: 3, projectName: 'โครงการ A', contactId: null, contactName: null,
      },
    },
  });
  api.customers.search.mockResolvedValue({ customers: [{ ...CUSTOMER, address: '1 ถนนทดสอบ' }] });
  api.customers.contacts.mockResolvedValue({ contacts: [] });
  api.dealQuotations.displayNameOptions.mockResolvedValue({ items: [] });
  api.dealQuotations.get.mockResolvedValue({ quotation: quotation() });
  api.dealQuotations.update.mockImplementation(async () => ({ quotation: quotation() }));
  api.dealQuotations.submit.mockResolvedValue({ quotation: quotation({ docStatus: 'PENDING_APPROVAL' }) });
  api.dealQuotations.calculateLine.mockResolvedValue({
    item: { netUnitPrice: 850, lineAmount: 48450, calculationLine: '(server calc)' },
  });
  api.dealQuotations.listAttachments.mockResolvedValue({ items: [VENDOR_QUOTE] });
  api.dealQuotations.attachmentUrl.mockImplementation((id) => `/api/deal-quotation-attachments/${id}/file`);
});

describe('where the panel sits', () => {
  it('a draft being edited: last on the page, after the บันทึกร่าง bar, with the file and the picker', async () => {
    open('/quotations/5', salesUser);

    await waitFor(() => expect(panelHeading()).not.toBeNull());
    const save = screen.getByRole('button', { name: 'บันทึกร่าง' });
    expect(follows(save, panelHeading())).toBe(true);
    await waitFor(() => expect(fileLink()).not.toBeNull());
    expect(panel().contains(fileLink())).toBe(true);
    expect(fileLink().getAttribute('href')).toBe('/api/deal-quotation-attachments/71/file');
    expect(attachInput()).not.toBeNull();
    expect(api.dealQuotations.listAttachments).toHaveBeenCalledWith(5);
  });

  it('the details an approver reads: after the document\'s last panel, with the file to open and nothing to change', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: quotation({ docStatus: 'PENDING_APPROVAL' }) });
    open('/quotations/5', ceoUser);

    await waitFor(() => expect(panelHeading()).not.toBeNull());
    expect(follows(screen.getByRole('heading', { name: 'ผู้เกี่ยวข้อง' }), panelHeading())).toBe(true);
    await waitFor(() => expect(fileLink()).not.toBeNull());
    expect(panel().contains(fileLink())).toBe(true);
    expect(attachInput()).toBeNull();
    // The decision the files are evidence for is still the approver's to make.
    expect(screen.getByRole('button', { name: 'อนุมัติ' }).disabled).toBe(false);
    expect(screen.getByRole('button', { name: 'ไม่อนุมัติ' }).disabled).toBe(false);
  });

  it('the details its own rep reads while it awaits approval: the picker is still offered', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: quotation({ docStatus: 'PENDING_APPROVAL' }) });
    open('/quotations/5', salesUser);

    await waitFor(() => expect(panelHeading()).not.toBeNull());
    expect(follows(screen.getByRole('heading', { name: 'ผู้เกี่ยวข้อง' }), panelHeading())).toBe(true);
    await waitFor(() => expect(fileLink()).not.toBeNull());
    expect(attachInput()).not.toBeNull();
  });

  it('a quotation that has never been saved: says to save first, offers no picker, asks the server nothing', async () => {
    open('/quotations/new', salesUser);

    await waitFor(() => expect(panelHeading()).not.toBeNull());
    expect(follows(screen.getByRole('button', { name: 'บันทึกร่าง' }), panelHeading())).toBe(true);
    expect(panel().textContent).toContain('บันทึกร่าง');
    expect(panel().querySelector('input[type="file"]')).toBeNull();
    expect(api.dealQuotations.listAttachments).not.toHaveBeenCalled();
  });
});

describe('who is not shown it', () => {
  // Wrong way round, control first: this same quotation shows the CEO the panel with a file in it.
  it.each([
    ['account', accountUser], ['import', importUser],
  ])('%s opens the quotation and is shown no panel; the list is never requested', async (_who, excluded) => {
    api.dealQuotations.get.mockResolvedValue({ quotation: quotation({ docStatus: 'PENDING_APPROVAL' }) });
    const approver = open('/quotations/5', ceoUser);
    await waitFor(() => expect(fileLink()).not.toBeNull());
    approver.unmount();
    api.dealQuotations.listAttachments.mockClear();

    open('/quotations/5', excluded);

    // Control: the quotation itself is on the page for this viewer.
    await screen.findByRole('heading', { name: 'ผู้เกี่ยวข้อง' });
    expect(screen.queryByText('ใบเสนอราคา QT-2026-0005-1')).not.toBeNull();
    await new Promise((resolve) => { setTimeout(resolve, 50); });
    expect(panelHeading()).toBeNull();
    expect(fileLink()).toBeNull();
    expect(api.dealQuotations.listAttachments).not.toHaveBeenCalled();
  });
});

describe('the panel never stands in the way of บันทึกร่าง or ส่งขออนุมัติ', () => {
  async function saveThenSubmit() {
    await waitFor(() => expect(panelHeading()).not.toBeNull());
    const notes = await screen.findByLabelText('หมายเหตุเพิ่มเติม');
    fireEvent.change(notes, { target: { value: 'ราคาตามใบเสนอราคาผู้ขาย' } });
    const save = screen.getByRole('button', { name: 'บันทึกร่าง' });
    expect(save.disabled).toBe(false);
    fireEvent.click(save);
    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalledTimes(1));

    const headerSubmit = screen.getByRole('button', { name: 'ส่งขออนุมัติ' });
    await waitFor(() => expect(headerSubmit.disabled).toBe(false));
    fireEvent.click(headerSubmit);
    fireEvent.click(screen.getAllByRole('button', { name: 'ส่งขออนุมัติ' }).at(-1));
    await waitFor(() => expect(api.dealQuotations.submit).toHaveBeenCalledWith('5'));
  }

  it('with no file attached, the draft saves and submits as before', async () => {
    api.dealQuotations.listAttachments.mockResolvedValue({ items: [] });
    open('/quotations/5', salesUser);

    await saveThenSubmit();
    expect(api.dealQuotations.uploadAttachment).not.toHaveBeenCalled();
  });

  it('while the list is still loading', async () => {
    api.dealQuotations.listAttachments.mockReturnValue(new Promise(() => {}));
    open('/quotations/5', salesUser);

    await saveThenSubmit();
  });

  it('when the list cannot be loaded at all', async () => {
    api.dealQuotations.listAttachments.mockRejectedValue(new Error('เกิดข้อผิดพลาดภายในระบบ'));
    open('/quotations/5', salesUser);

    await saveThenSubmit();
  });
});
