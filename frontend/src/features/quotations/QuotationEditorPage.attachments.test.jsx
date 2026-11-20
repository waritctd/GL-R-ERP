import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom';
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
// The structure pinned here is the information-architecture decision. There is one panel. In the
// edit form it sits just above ยอดรวม, so a rep attaches while still filling the document in, and
// before the first บันทึกร่าง too: the files are held by the page and sent once the quotation
// exists. In the details view it is the last block, after the last panel, because the details
// reproduce the printed form and these files are never printed.
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
        displayNameOptions: vi.fn(),
        listAttachments: vi.fn(), uploadAttachment: vi.fn(), attachmentUrl: vi.fn(),
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

const attachInput = () => screen.queryByLabelText(/แนบเอกสาร/);

beforeEach(() => {
  vi.clearAllMocks();
  api.tickets.get.mockResolvedValue({
    ticket: {
      summary: {
        id: 18, createdById: 6, createdByName: 'คุณสมหมาย ขายดี', customerName: CUSTOMER.name, customerId: 5,
        projectId: 3, projectName: 'โครงการ A', contactId: null, contactName: null,
        // A deal at a quote stage preselects its recipient (slice 2 flow B), so saving is not held back.
        salesStage: 'QUOTE_DESIGN_SIDE',
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

// A rep can pick the vendor's quotation before the first บันทึกร่าง. The page holds the files, and
// sends them one at a time to the quotation the save has just made, then moves to it.
describe('files picked before the first บันทึกร่าง', () => {
  const fileA = new File(['%PDF-A'], 'vendor-a.pdf', { type: 'application/pdf' });
  const fileB = new File(['%PDF-B'], 'vendor-b.pdf', { type: 'application/pdf' });
  function IdProbe() {
    const { id } = useParams();
    return <p>navigated-to-{id}</p>;
  }
  function openNew(path) {
    const showToast = vi.fn();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/quotations/new" element={<QuotationEditorPage user={salesUser} showToast={showToast} />} />
            <Route path="/quotations/:id" element={<IdProbe />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    return { showToast };
  }

  // Same complete row the other editor tests fill, so บันทึกร่าง is not gated on anything looser.
  async function fillOneValidItem() {
    fireEvent.click(await screen.findByRole('button', { name: /เพิ่มรายการ/ }));
    fireEvent.change(screen.getByLabelText(/^รุ่น \/ ค้นหาแคตตาล็อก/), { target: { value: 'Trilogy' } });
    fireEvent.change(screen.getByLabelText(/^สี/), { target: { value: 'Ivory' } });
    fireEvent.change(screen.getByLabelText(/^ผิว/), { target: { value: 'Lappato' } });
    fireEvent.change(screen.getByLabelText(/^ขนาด/), { target: { value: '60x120' } });
    fireEvent.change(screen.getByLabelText(/^ความหนา/), { target: { value: '10' } });
    fireEvent.change(screen.getByLabelText(/^แผ่น\/ตร\.ม\./), { target: { value: '1.39' } });
    fireEvent.change(screen.getByLabelText(/^แผ่น\/กล่อง/), { target: { value: '3' } });
    fireEvent.change(screen.getByLabelText(/^ราคา\/ตร\.ม\./), { target: { value: '850' } });
    fireEvent.change(screen.getByLabelText(/^จำนวน/), { target: { value: '36' } });
  }

  const pick = (...files) => files.forEach((file) => fireEvent.change(attachInput(), { target: { files: [file] } }));
  async function save() {
    const button = screen.getByRole('button', { name: 'บันทึกร่าง' });
    await waitFor(() => expect(button.disabled).toBe(false));
    fireEvent.click(button);
  }
  function expectSentAfterCreate() {
    const [created] = api.dealQuotations.create.mock.invocationCallOrder;
    for (const sent of api.dealQuotations.uploadAttachment.mock.invocationCallOrder) {
      expect(sent).toBeGreaterThan(created);
    }
  }

  beforeEach(() => {
    api.dealQuotations.create.mockResolvedValue({ quotation: { id: 99 } });
    api.dealQuotations.uploadAttachment.mockResolvedValue({ attachment: { id: 1 } });
  });

  it('are sent to the new quotation, in the order picked and after it exists, then the page moves to it', async () => {
    openNew('/quotations/new?ticket=18');
    await fillOneValidItem();
    pick(fileA, fileB);
    // Held on the page, not sent: there is no quotation to send them to yet.
    expect(api.dealQuotations.uploadAttachment).not.toHaveBeenCalled();

    await save();

    expect(await screen.findByText('navigated-to-99')).not.toBeNull();
    expect(api.dealQuotations.create).toHaveBeenCalledTimes(1);
    expect(api.dealQuotations.uploadAttachment.mock.calls).toEqual([[99, fileA], [99, fileB]]);
    expectSentAfterCreate();
  }, 15000);

  it('a refused file is named in a toast, the rest still go up, and the page still moves on', async () => {
    api.dealQuotations.uploadAttachment
      .mockRejectedValueOnce(new Error('รองรับเฉพาะไฟล์ PDF, JPG หรือ PNG'))
      .mockResolvedValue({ attachment: { id: 2 } });
    const { showToast } = openNew('/quotations/new?ticket=18');
    await fillOneValidItem();
    pick(fileA, fileB);

    await save();

    expect(await screen.findByText('navigated-to-99')).not.toBeNull();
    expect(api.dealQuotations.uploadAttachment.mock.calls).toEqual([[99, fileA], [99, fileB]]);
    const refusal = showToast.mock.calls.find(([kind]) => kind === 'error');
    expect(refusal).toBeDefined();
    expect(refusal[1]).toContain('vendor-a.pdf');
    expect(refusal[1]).toContain('รองรับเฉพาะไฟล์ PDF, JPG หรือ PNG');
    // Only the refused file is named.
    expect(refusal[1]).not.toContain('vendor-b.pdf');
  }, 15000);
});
