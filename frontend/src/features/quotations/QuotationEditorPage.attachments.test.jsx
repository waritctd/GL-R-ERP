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
  it('a draft being edited: above ยอดรวม, which precedes the บันทึกร่าง bar, with the file and the picker', async () => {
    open('/quotations/5', salesUser);

    await waitFor(() => expect(panelHeading()).not.toBeNull());
    const total = screen.getByRole('heading', { name: 'ยอดรวม' });
    expect(follows(panelHeading(), total)).toBe(true);
    expect(follows(total, screen.getByRole('button', { name: 'บันทึกร่าง' }))).toBe(true);
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

  it('a quotation that has never been saved: above ยอดรวม, offers the picker, asks the server nothing', async () => {
    open('/quotations/new', salesUser);

    await waitFor(() => expect(panelHeading()).not.toBeNull());
    expect(follows(panelHeading(), screen.getByRole('heading', { name: 'ยอดรวม' }))).toBe(true);
    expect(attachInput()).not.toBeNull();
    expect(panel().textContent).toContain('บันทึกร่างครั้งแรก');
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

// The owner asked that the approver "จะเห็นต้นทุนประกอบการอนุมัติทันที". The panel is the last block of the
// page, so a reader would have to scroll to learn there is anything to read. In the read-only details,
// one link beside the status says how many files there are and goes to the panel. It is shown only
// when there is a file to go to.
describe('the link to the files, beside the status', () => {
  const EARLIER = {
    ...VENDOR_QUOTE, id: 55, quotationId: 4, quotationNumber: 'QT-2026-0004-1',
    fileName: 'vendor-quote-first-round.pdf', inherited: true,
  };
  const jumpLink = () => screen.queryByRole('link', { name: /^เอกสารแนบ \d+ ไฟล์$/ });
  const awaitingApproval = () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: quotation({ docStatus: 'PENDING_APPROVAL' }) });
  };

  it('tells the approver how many files there are, above the document, and goes to the panel', async () => {
    awaitingApproval();
    api.dealQuotations.listAttachments.mockResolvedValue({ items: [VENDOR_QUOTE, EARLIER] });
    open('/quotations/5', ceoUser);

    await waitFor(() => expect(jumpLink()).not.toBeNull());
    const link = jumpLink();
    // Inherited files are files to read too, so both are counted.
    expect(link.textContent).toContain('เอกสารแนบ 2 ไฟล์');
    // In the page's own header, with the title and the status, and above the document.
    const header = link.closest('header');
    expect(header).not.toBeNull();
    expect(header.contains(screen.getByRole('heading', { name: 'ใบเสนอราคา QT-2026-0005-1' }))).toBe(true);
    expect(follows(link, screen.getByRole('heading', { name: 'ข้อมูลลูกค้า' }))).toBe(true);
    // It goes to the panel. Its address is the panel's id, and following it puts focus there.
    expect(panel().id).toBeTruthy();
    expect(link.getAttribute('href')).toBe(`#${panel().id}`);
    fireEvent.click(link);
    expect(document.activeElement).toBe(panel());
    // The link and the panel read one request, not one each.
    expect(api.dealQuotations.listAttachments).toHaveBeenCalledTimes(1);
  });

  it('tells its own rep the same while the quotation awaits approval', async () => {
    awaitingApproval();
    open('/quotations/5', salesUser);

    await waitFor(() => expect(jumpLink()).not.toBeNull());
    expect(jumpLink().textContent).toContain('เอกสารแนบ 1 ไฟล์');
    expect(jumpLink().getAttribute('href')).toBe(`#${panel().id}`);
  });

  it('says nothing when there is no file', async () => {
    awaitingApproval();
    api.dealQuotations.listAttachments.mockResolvedValue({ items: [] });
    open('/quotations/5', ceoUser);

    // Control: the list did come back, and the panel says it is empty.
    await waitFor(() => expect(panel()?.textContent ?? '').toContain('ไม่มีเอกสารแนบ'));
    expect(jumpLink()).toBeNull();
  });

  it('says nothing while the list is loading, or when it could not be loaded', async () => {
    awaitingApproval();
    api.dealQuotations.listAttachments.mockReturnValue(new Promise(() => {}));
    const loading = open('/quotations/5', ceoUser);
    await waitFor(() => expect(panelHeading()).not.toBeNull());
    expect(jumpLink()).toBeNull();
    loading.unmount();

    api.dealQuotations.listAttachments.mockRejectedValue(new Error('เกิดข้อผิดพลาดภายในระบบ'));
    open('/quotations/5', ceoUser);
    await waitFor(() => expect(panel()?.textContent ?? '').toContain('โหลดเอกสารแนบไม่สำเร็จ'));
    expect(jumpLink()).toBeNull();
  });

  // Wrong way round, control first: this same quotation shows the CEO the link.
  it.each([
    ['account', accountUser], ['import', importUser],
  ])('%s is shown no link, and the list is never requested', async (_who, excluded) => {
    awaitingApproval();
    const approver = open('/quotations/5', ceoUser);
    await waitFor(() => expect(jumpLink()).not.toBeNull());
    approver.unmount();
    api.dealQuotations.listAttachments.mockClear();

    open('/quotations/5', excluded);

    await screen.findByRole('heading', { name: 'ผู้เกี่ยวข้อง' });
    await new Promise((resolve) => { setTimeout(resolve, 50); });
    expect(jumpLink()).toBeNull();
    expect(api.dealQuotations.listAttachments).not.toHaveBeenCalled();
  });

  it('is for the read-only details, so a draft being edited lists the file in the panel and shows no link', async () => {
    open('/quotations/5', salesUser);

    // Control: there is a file the link could have pointed at.
    await waitFor(() => expect(fileLink()).not.toBeNull());
    expect(jumpLink()).toBeNull();
  });
});

// A rep can pick the vendor's quotation before the first บันทึกร่าง. The page holds the files, and
// sends them one at a time to the quotation the save has just made, then moves to it.
describe('files picked before the first บันทึกร่าง', () => {
  const fileA = new File(['%PDF-A'], 'vendor-a.pdf', { type: 'application/pdf' });
  const fileB = new File(['%PDF-B'], 'vendor-b.pdf', { type: 'application/pdf' });
  const testCustomer = { id: 1, name: 'บริษัท ก้าวหน้า คอนสตรัคชั่น จำกัด', taxId: '0105565012345' };
  const testProject = { id: 1, customerId: 1, name: 'โครงการ Central Ladprao ชั้น B1' };

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

  async function selectCustomerAndProject(project = testProject) {
    api.customers.search.mockResolvedValue({ customers: [testCustomer] });
    api.customers.projects.mockResolvedValue({ projects: [project] });
    fireEvent.change(await screen.findByLabelText(/^ลูกค้า/), { target: { value: 'ก้าวหน้า' } });
    fireEvent.mouseDown(await screen.findByRole('option', { name: new RegExp(testCustomer.name) }));
    await waitFor(() => expect(api.customers.projects).toHaveBeenCalledWith(testCustomer.id));
    fireEvent.focus(await screen.findByLabelText(/^โครงการ/));
    fireEvent.mouseDown(await screen.findByRole('option', { name: new RegExp(project.name) }));
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

  it('are sent after the inline-create path has made the deal and the quotation', async () => {
    api.tickets.create.mockResolvedValue({ ticket: { summary: { id: 42 } } });
    openNew('/quotations/new');
    await selectCustomerAndProject();
    await fillOneValidItem();
    pick(fileA, fileB);

    await save();

    expect(await screen.findByText('navigated-to-99')).not.toBeNull();
    expect(api.tickets.create).toHaveBeenCalledTimes(1);
    expect(api.dealQuotations.create).toHaveBeenCalledWith(42, expect.anything());
    expect(api.dealQuotations.uploadAttachment.mock.calls).toEqual([[99, fileA], [99, fileB]]);
    expectSentAfterCreate();
  }, 20000);

  it('the page waits for the uploads before it moves on, with บันทึกร่าง busy meanwhile', async () => {
    let finish;
    api.dealQuotations.uploadAttachment.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    openNew('/quotations/new?ticket=18');
    await fillOneValidItem();
    pick(fileA);

    await save();

    await waitFor(() => expect(api.dealQuotations.uploadAttachment).toHaveBeenCalledTimes(1));
    // Created, upload still running: still on the form, and the save button is busy.
    expect(api.dealQuotations.create).toHaveBeenCalledTimes(1);
    expect(screen.queryByText('navigated-to-99')).toBeNull();
    expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).getAttribute('aria-busy')).toBe('true');

    finish({ attachment: { id: 1 } });

    expect(await screen.findByText('navigated-to-99')).not.toBeNull();
  }, 15000);

  // The editor is one component at both /quotations/new and /quotations/:id, so it is not
  // re-mounted by the navigate that follows the first save. What it was holding must not outlive
  // the save, or the next new quotation uploads it again.
  it('does not carry sent files over to the next new quotation, so a copy is not uploaded again', async () => {
    api.dealQuotations.get.mockImplementation(async (id) => ({
      quotation: quotation({ id: Number(id), number: `QT-2026-${String(id).padStart(4, '0')}-1` }),
    }));
    api.tickets.create.mockResolvedValue({ ticket: { summary: { id: 42 } } });
    api.dealQuotations.create
      .mockResolvedValueOnce({ quotation: { id: 99 } })
      .mockResolvedValueOnce({ quotation: { id: 100 } });
    open('/quotations/new?ticket=18', salesUser);
    await fillOneValidItem();
    pick(fileA);
    await save();
    await waitFor(() => expect(api.dealQuotations.uploadAttachment).toHaveBeenCalledTimes(1));

    fireEvent.click(await screen.findByRole('button', { name: 'คัดลอกเป็นใบใหม่' }));

    // The copy starts with nothing held, and says so.
    await waitFor(() => expect(attachInput()).not.toBeNull());
    await waitFor(() => expect(screen.getByRole('heading', { name: 'ยอดรวม' })).not.toBeNull());
    expect(screen.queryAllByTestId('quotation-attachment')).toHaveLength(0);
    expect(panel().textContent).toContain('ยังไม่มีเอกสารแนบ');
    expect(panel().textContent).not.toContain('vendor-a.pdf');

    // A copy keeps the source's project name to offer under the new customer.
    await selectCustomerAndProject({ ...testProject, name: 'โครงการ A' });
    await save();

    await waitFor(() => expect(api.dealQuotations.create).toHaveBeenCalledTimes(2));
    // Let the copy's own save finish and the page move to it, so a stray upload would be in by now.
    expect(await screen.findByText('ใบเสนอราคา QT-2026-0100-1')).not.toBeNull();
    expect(api.dealQuotations.uploadAttachment).toHaveBeenCalledTimes(1);
  }, 30000);

  // Held files are sent by the save that creates the quotation, so only that save closes the
  // picker. A saved draft's autosave must not.
  it('the picker is closed while the first save runs', async () => {
    api.dealQuotations.create.mockReturnValue(new Promise(() => {}));
    openNew('/quotations/new?ticket=18');
    await fillOneValidItem();
    expect(attachInput().disabled).toBe(false);

    await save();

    await waitFor(() => expect(api.dealQuotations.create).toHaveBeenCalledTimes(1));
    expect(attachInput().disabled).toBe(true);
  }, 15000);

  it('the picker of a saved draft stays open while an autosave runs', async () => {
    api.dealQuotations.update.mockReturnValue(new Promise(() => {}));
    open('/quotations/5', salesUser);
    await waitFor(() => expect(attachInput()).not.toBeNull());

    fireEvent.change(await screen.findByLabelText('หมายเหตุเพิ่มเติม'), { target: { value: 'แก้ไขหมายเหตุ' } });

    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalledTimes(1), { timeout: 10000 });
    expect(attachInput().disabled).toBe(false);
  }, 20000);

  it('control: with nothing picked the draft is created and the page moves on, with no upload', async () => {
    openNew('/quotations/new?ticket=18');
    await fillOneValidItem();

    await save();

    expect(await screen.findByText('navigated-to-99')).not.toBeNull();
    expect(api.dealQuotations.create).toHaveBeenCalledTimes(1);
    expect(api.dealQuotations.uploadAttachment).not.toHaveBeenCalled();
  }, 15000);
});
