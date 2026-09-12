import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QuotationEditorPage } from './QuotationEditorPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// Each case here mounts the WHOLE editor and drives it through a debounced calculate-line and a
// save — 1–4 s alone, and measured at 6.6 s for one of them under the full parallel suite, past
// vitest's 5 s default. The budget is raised for this file only; nothing here relies on real time
// beyond the page's own 300 ms / 2 s debounces.
vi.setConfig({ testTimeout: 15000 });

// Quotation v3 / v3b controls and the owner's 2026-09-11 requests (customer address, repeat-customer
// autofill, the "ข้อมูลที่ยังไม่ครบ" checklist), driven through the WHOLE editor page. A separate file
// from QuotationEditorPage.test.jsx only to keep that one's fixtures (pre-v3 DTOs, no address) as
// the regression net they already are.
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
      // Consumed by DesignerPicker.jsx (owner ask 2026-09-12, "D.Co. auto-fill from a
      // ผู้ออกแบบ pick") -- getByCode default-resolves to undefined so the picker's own
      // resolved-name hint effect just finds nothing rather than throwing when a test
      // never sets a unitCode.
      designers: { search: vi.fn(), getByCode: vi.fn().mockRejectedValue(new Error('not found')) },
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

// A COMPLETE stored tile row (validateQuotationItem finds nothing missing) in the full v3 DTO shape.
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

function group(name) {
  return screen.getByRole('group', { name });
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

describe('v3/v3b document settings', () => {
  it('English does not offer ราคาพิเศษ; a ราคาพิเศษ draft switched to English moves to ราคาสุทธิต่อแผ่น and SAYS so', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: draft({ priceMode: 'SPECIAL_SQM', items: [{ ...TILE_ITEM, specialPriceSqm: 1350, netUnitPrice: 453.84 }] }),
    });
    renderEditor('/quotations/5');
    await screen.findByRole('group', { name: 'วิธีกรอกราคากระเบื้อง' });
    // waitFor, not a bare expect: the group renders one frame BEFORE the seeding effect applies the
    // stored mode, so under a loaded full-suite run the first read still sees the NET default.
    await waitFor(() => expect(within(group('วิธีกรอกราคากระเบื้อง')).getByRole('button', { name: 'ราคาพิเศษ บาท/ตร.ม.' }).getAttribute('aria-pressed')).toBe('true'));

    fireEvent.click(within(group('ภาษาเอกสาร')).getByRole('button', { name: /English/ }));

    expect(within(group('วิธีกรอกราคากระเบื้อง')).queryByRole('button', { name: 'ราคาพิเศษ บาท/ตร.ม.' })).toBeNull();
    expect(within(group('วิธีกรอกราคากระเบื้อง')).getByRole('button', { name: 'ราคาสุทธิต่อแผ่น' }).getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByText(/เอกสารภาษาอังกฤษใช้ "ราคาพิเศษ บาท\/ตร\.ม\." ไม่ได้/)).not.toBeNull();

    // The net is NOT silently carried across a currency change — the rep has to state it.
    const save = screen.getByRole('button', { name: 'บันทึกร่าง' });
    expect(save.disabled).toBe(true);
    expect(within(screen.getByTestId('checklist-blocking')).getByText('รายการที่ 1: ขาด ราคาสุทธิ/แผ่น')).not.toBeNull();

    fireEvent.change(screen.getByLabelText(/^ราคาสุทธิ\/แผ่น/), { target: { value: '12.5' } });
    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalled());
    const [, payload] = api.dealQuotations.update.mock.calls[0];
    expect(payload).toMatchObject({ priceMode: 'DIRECT_NET', documentLanguage: 'EN', currency: 'USD' });
    expect(payload.items[0]).toMatchObject({ directNetPrice: 12.5, specialPriceSqm: null });
  }, 15000);

  it('ALWAYS sends priceMode and documentLanguage on update, even when untouched', async () => {
    renderEditor('/quotations/5');
    await screen.findByRole('group', { name: 'ภาษาเอกสาร' });
    fireEvent.change(screen.getByLabelText('หมายเหตุเพิ่มเติม'), { target: { value: 'x' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalled());
    expect(api.dealQuotations.update.mock.calls[0][1]).toMatchObject({ priceMode: 'NET', documentLanguage: 'TH', currency: 'THB' });
  });

  it('ราคาพิเศษ shows the per-piece net the SERVER derived (calculate-line), sent without any stale net', async () => {
    renderEditor('/quotations/new?ticket=18');
    await screen.findByRole('group', { name: 'วิธีกรอกราคากระเบื้อง' });
    fireEvent.click(within(group('วิธีกรอกราคากระเบื้อง')).getByRole('button', { name: 'ราคาพิเศษ บาท/ตร.ม.' }));
    fireEvent.click(screen.getByRole('button', { name: /เพิ่มรายการในตำแหน่งนี้/ }));
    fireEvent.change(screen.getByLabelText(/^ราคาพิเศษ \(บาท\/ตร\.ม\./), { target: { value: '1350' } });

    await waitFor(() => expect(screen.getByTestId('special-net-0').textContent).toBe('= สุทธิ ฿453.84/แผ่น (ก่อน VAT)'));
    const sent = api.dealQuotations.calculateLine.mock.calls.at(-1)[0];
    expect(sent).toMatchObject({ lineType: 'TILE', specialPriceSqm: 1350, directNetPrice: null, discountPct: null });
  });
});

describe('PLAIN and ส่วนลดพิเศษ rows', () => {
  it('both travel in the payload, the ส่วนลดพิเศษ LAST and with no unitPrice', async () => {
    renderEditor('/quotations/5');
    await screen.findByRole('button', { name: 'เพิ่มส่วนลดพิเศษ' });

    fireEvent.click(screen.getByRole('button', { name: 'เพิ่มสินค้าหรือบริการอื่นในตำแหน่งนี้' }));
    fireEvent.click(screen.getByRole('button', { name: 'ค่าขนส่ง' })); // one-click preset: description + unit + qty 1
    fireEvent.change(document.getElementById('plain-price-1'), { target: { value: '3500' } });

    fireEvent.click(screen.getByRole('button', { name: 'เพิ่มส่วนลดพิเศษ' }));
    fireEvent.change(document.getElementById('adj-pct-2'), { target: { value: '3' } });

    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalled());

    const { items } = api.dealQuotations.update.mock.calls[0][1];
    expect(items.map((it) => it.lineType)).toEqual(['TILE', 'PLAIN', 'ADJUSTMENT']);
    expect(items[1]).toMatchObject({ description: 'ค่าขนส่ง', quantity: 1, unit: 'งาน', unitPrice: 3500 });
    expect(items[2]).toMatchObject({ adjustmentPct: 3, adjustmentAmount: null });
    expect('unitPrice' in items[2]).toBe(false);
  });

  it('an incomplete ส่วนลดพิเศษ blocks the save and is named in the checklist', async () => {
    renderEditor('/quotations/5');
    await screen.findByRole('button', { name: 'เพิ่มส่วนลดพิเศษ' });
    fireEvent.click(screen.getByRole('button', { name: 'เพิ่มส่วนลดพิเศษ' }));
    expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(true);
    expect(within(screen.getByTestId('checklist-blocking')).getByText('รายการที่ 2: ขาด เปอร์เซ็นต์ส่วนลด')).not.toBeNull();
  });
});

describe('"ข้อมูลที่ยังไม่ครบ" checklist (owner, 2026-09-11)', () => {
  it('a missing ที่อยู่ is a WARNING — ส่งขออนุมัติ stays enabled', async () => {
    renderEditor('/quotations/5');
    const warnings = await screen.findByTestId('checklist-warnings');
    expect(within(warnings).getByText('ยังไม่ได้กรอกที่อยู่ลูกค้า')).not.toBeNull();
    expect(screen.queryByTestId('checklist-blocking')).toBeNull();
    expect(screen.getByRole('button', { name: 'ส่งขออนุมัติ' }).disabled).toBe(false);
  });

  it('a missing ผู้สั่งซื้อ BLOCKS ส่งขออนุมัติ', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: draft({ contactId: null, contactName: null, contactPhone: null, contactEmail: null }) });
    api.tickets.get.mockResolvedValue({
      ticket: { summary: { id: 18, createdById: 6, customerName: CUSTOMER.name, customerId: 5, projectName: 'โครงการ A', contactId: null } },
    });
    renderEditor('/quotations/5');
    const blocking = await screen.findByTestId('checklist-blocking');
    expect(within(blocking).getByText('กรุณาระบุผู้สั่งซื้อ')).not.toBeNull();
    expect(screen.getByRole('button', { name: 'ส่งขออนุมัติ' }).disabled).toBe(true);
  });

  it('clicking an entry focuses its field', async () => {
    renderEditor('/quotations/5');
    fireEvent.click(await screen.findByRole('button', { name: 'ยังไม่ได้กรอกที่อยู่ลูกค้า' }));
    expect(document.activeElement?.id).toBe('deal-customer-address');
  });

  it('the ส่งขออนุมัติ dialog restates the optional gaps', async () => {
    renderEditor('/quotations/5');
    await screen.findByTestId('checklist-warnings');
    fireEvent.click(screen.getByRole('button', { name: 'ส่งขออนุมัติ' }));
    expect(within(screen.getByTestId('submit-warnings')).getByText('ยังไม่ได้กรอกที่อยู่ลูกค้า')).not.toBeNull();
  });
});

describe('customer address + repeat-customer autofill (owner, 2026-09-11)', () => {
  it('a repeat customer on the ?ticket= path arrives with its saved details; the ผู้สั่งซื้อ\'s โทร./อีเมล too', async () => {
    api.customers.search.mockResolvedValue({ customers: [{ ...CUSTOMER, address: '201 ซอยสุขุมวิท 63' }] });
    renderEditor('/quotations/new?ticket=18');
    await waitFor(() => expect(document.getElementById('deal-customer-address')?.value).toBe('201 ซอยสุขุมวิท 63'));
    expect(document.getElementById('deal-customer-tax-id').value).toBe('0105551234567');
    expect(document.getElementById('deal-customer-phone').value).toBe('02-000-0000');
    await waitFor(() => expect(screen.getByTestId('quotation-contact-details').textContent)
      .toBe('โทร. 086-222-3333 · อีเมล nattapong@fashionisland.co.th'));
    // Resolving the seeded ผู้สั่งซื้อ is not an edit: the pristine page stays quiet.
    expect(screen.queryByTestId('quotation-checklist')).toBeNull();
  });

  it('ที่อยู่ is editable on an existing draft: saved on blur, and the draft is re-saved so the document carries it', async () => {
    api.customers.update.mockResolvedValue({ customer: { ...CUSTOMER, address: '1 ถนนพระราม 9' } });
    renderEditor('/quotations/5');
    await screen.findByTestId('checklist-warnings');
    const address = document.getElementById('deal-customer-address');
    fireEvent.change(address, { target: { value: '1 ถนนพระราม 9' } });
    fireEvent.blur(address);

    await waitFor(() => expect(api.customers.update).toHaveBeenCalledWith(5, { address: '1 ถนนพระราม 9' }));
    await waitFor(() => expect(screen.queryByText('ยังไม่ได้กรอกที่อยู่ลูกค้า')).toBeNull());
    // The document snapshots the customer on a DRAFT save, so the address edit must trigger one —
    // here the 2-second autosave.
    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalled(), { timeout: 4000 });
  });

  it('ส่งขออนุมัติ SAVES unsaved edits first, then submits', async () => {
    renderEditor('/quotations/5');
    await screen.findByTestId('checklist-warnings');
    fireEvent.change(screen.getByLabelText('หมายเหตุเพิ่มเติม'), { target: { value: 'ส่งภายใน 60 วัน' } });
    fireEvent.click(screen.getByRole('button', { name: 'ส่งขออนุมัติ' }));
    expect(screen.getByText('การแก้ไขที่ยังไม่บันทึกจะถูกบันทึกก่อนส่ง')).not.toBeNull();
    fireEvent.click(screen.getAllByRole('button', { name: 'ส่งขออนุมัติ' }).at(-1));

    await waitFor(() => expect(api.dealQuotations.submit).toHaveBeenCalledWith('5'));
    expect(api.dealQuotations.update).toHaveBeenCalledTimes(1);
    expect(api.dealQuotations.update.mock.invocationCallOrder[0]).toBeLessThan(api.dealQuotations.submit.mock.invocationCallOrder[0]);
    expect(api.dealQuotations.update.mock.calls[0][1].customerNotes).toBe('ส่งภายใน 60 วัน');
  });
});
