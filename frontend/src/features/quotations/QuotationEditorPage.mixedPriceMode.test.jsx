import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QuotationEditorPage } from './QuotationEditorPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// Whole-editor cases: each mounts the page and drives it through a debounced calculate-line and
// often a save. Same budget, for the same reason, as QuotationEditorPage.v3.test.jsx.
vi.setConfig({ testTimeout: 15000 });

// Mixed price modes (V192.5), driven through the WHOLE quotation editor.
//
// Sales feedback #14 (แจ้งปัญหาการใช้งาน เสนอราคา): "อยากให้เลือกการคิดราคาแต่ละข้อไม่เหมือนกันได้ เช่น
// บางข้อราคาพิเศษต่อตรม บางข้อลด% ตามสเต็ป". Owner, 2026-10-06: "make sure the ux/ui flow is clear …
// for the mode you can add an option to apply this mode for all or mix mode (ต้องอธิบายด้วยว่ากรอก
// วิธีหลายแบบในใบเสนอราคาเดียวกัน)".
//
// The flow these tests pin, in the order a rep meets it:
//   1. "วิธีกรอกราคากระเบื้อง" still offers the three modes for the WHOLE document — and, beside
//      them, "ผสมหลายวิธี", which explains itself once chosen.
//   2. Choosing it changes nothing on any row: each row simply gains its own "วิธีคิดราคา" picker,
//      already set to the mode the document was in.
//   3. Changing one row's picker re-asks THAT row for its new mode's prices and re-previews it.
//   4. Going back to one mode for the whole document asks first, when it would re-price rows.
//
// Evidence note: every server response here is a vi.fn(). These tests show what the editor sends
// and shows; that the server prices a mixed document correctly is DealQuotationMixedPriceMode
// IntegrationTest's (Java, real Postgres), and nothing here could stand in for it.
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
        contacts: vi.fn(), createContact: vi.fn(),
      },
    },
  };
});

const salesUser = { id: 6, name: 'คุณสมหมาย ขายดี', role: 'sales', employeeId: null };
const CUSTOMER = {
  id: 5, name: 'บริษัท แฟชั่นไอส์แลนด์ จำกัด', taxId: '0105551234567', address: null, branch: 'สำนักงานใหญ่', phone: '02-000-0000',
};

// A COMPLETE stored tile row, the owner's 60×120 (0.72 ตร.ม./แผ่น → 1.39 แผ่น/ตร.ม.), priced per
// ตร.ม. including VAT as the editor has since V192.2: 1,800 → 1,210.25 a piece.
const TILE = {
  id: 101, seq: 1, lineType: 'TILE', locationLabel: null, catalogPriceId: null, productCode: null,
  brand: 'Panaria', model: 'Trilogy', color: 'Ivory', texture: 'Lappato', sizeText: '60x120', thicknessMm: 10,
  sqmPerPiece: 0.72, quantityMode: 'PIECES', areaSqm: null, piecesInput: 10, wastageMode: 'NONE',
  wastageValue: null, piecesPerBox: 2, unitPrice: 1210.25, discountPct: 20, originCountry: null,
  leadTimeMinDays: 75, leadTimeMaxDays: 90, itemNotes: null,
  piecesPerSqm: 1.39, piecesBeforeWastage: 10, piecesAfterWastage: 10, piecesFinal: 10, boxes: 5,
  netUnitPrice: 968.2, lineAmount: 9682, descriptionLine: 'Trilogy Ivory', sizeLine: '60x120', calculationLine: '(calc)',
  quantity: 10, unit: 'แผ่น', specialPriceSqm: null, adjustmentPct: null, adjustmentDeadline: null,
  specialPriceLine: null, adjustmentAmount: null, listPriceSqm: 1800, directNetPriceSqm: null, priceMode: null,
};
const NET_ROW = { ...TILE, priceMode: 'NET' };
const SPECIAL_ROW = {
  ...TILE, id: 102, seq: 2, model: 'Urbanature', discountPct: null, specialPriceSqm: 790,
  netUnitPrice: 531.16, lineAmount: 5311.6, specialPriceLine: 'ราคาพิเศษ 790 บาท/ตร.ม.', priceMode: 'SPECIAL_SQM',
};

function draft(overrides = {}) {
  return {
    id: 5, number: 'QT-2026-0005', ticketId: 18, docStatus: 'DRAFT', revisionNo: 1, parentQuotationId: null,
    origin: 'DEAL_DIRECT',
    salesRepId: 6, salesRepName: 'คุณสมหมาย ขายดี', salesRepPhone: '081-000-0000', createdByName: 'คุณสมหมาย ขายดี',
    approvalNote: null, quotationDate: '2026-10-06',
    customerName: CUSTOMER.name, customerAddress: 'กรุงเทพฯ', customerTaxId: CUSTOMER.taxId, customerPhone: CUSTOMER.phone,
    contactId: null, contactName: null, contactPhone: null, contactEmail: null,
    projectName: 'โครงการ A', deptCode: null, unitCode: 'A001', offerDate: '2026-10-06', depositPercent: 30,
    remainderMode: 'ON_DELIVERY', creditDays: null, validityDays: 30, validityDate: null, customerNotes: null,
    priceMode: 'NET', documentLanguage: 'TH', subtotalAmount: 9682, vatAmount: 677.74, grandTotal: 10359.74,
    currency: 'THB', approverHasSignature: false, items: [TILE],
    createdAt: '2026-10-06T02:00:00Z', updatedAt: '2026-10-06T02:00:00Z',
    ...overrides,
  };
}
const mixedDraft = (overrides = {}) => draft({ priceMode: 'MIXED', items: [NET_ROW, SPECIAL_ROW], ...overrides });

function renderEditor(path = '/quotations/5') {
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

const MODES = 'วิธีกรอกราคากระเบื้อง';
const modes = () => screen.getByRole('group', { name: MODES });
const modeButton = (name) => within(modes()).getByRole('button', { name });
const mixedButton = () => within(modes()).queryByRole('button', { name: 'ผสมหลายวิธี' });
const pressed = (button) => button.getAttribute('aria-pressed') === 'true';
const byId = (id) => document.getElementById(id);
const rowPicker = (index) => byId(`price-mode-${index}`);
const rowPickers = () => [...document.querySelectorAll('select[id^="price-mode-"]')];

async function openDraft(quotation) {
  api.dealQuotations.get.mockResolvedValue({ quotation });
  renderEditor();
  await screen.findByRole('group', { name: MODES });
  await waitFor(() => expect(byId('price-0')).not.toBeNull());
}

async function saveDraft() {
  await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));
  fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
  await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalled());
  return api.dealQuotations.update.mock.calls.at(-1)[1];
}

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
  api.customers.search.mockResolvedValue({ customers: [CUSTOMER] });
  api.customers.contacts.mockResolvedValue({ contacts: [] });
  api.dealQuotations.update.mockImplementation(async () => ({ quotation: draft() }));
  api.dealQuotations.calculateLine.mockResolvedValue({
    item: { unitPrice: 1210.25, netUnitPrice: 531.16, lineAmount: 5311.6, calculationLine: '(server calc)' },
  });
});

describe('the "วิธีกรอกราคากระเบื้อง" choice — one mode for the whole document, or a mix', () => {
  it('still offers the three whole-document modes, and "ผสมหลายวิธี" beside them', async () => {
    await openDraft(draft());

    expect(pressed(modeButton('ราคาตั้ง − ส่วนลด %'))).toBe(true);
    expect(modeButton('ราคาพิเศษ บาท/ตร.ม.')).not.toBeNull();
    expect(modeButton('ราคาสุทธิ บาท/ตร.ม.')).not.toBeNull();
    expect(mixedButton()).not.toBeNull();
    expect(pressed(mixedButton())).toBe(false);
    // The two kinds of choice are told apart in words, not just by position.
    expect(within(modes()).getByText('ใช้วิธีเดียวทั้งใบ')).not.toBeNull();
    expect(within(modes()).getByText('หรือให้แต่ละรายการเลือกเอง')).not.toBeNull();
    // A single-mode document has no per-row choice.
    expect(rowPickers()).toHaveLength(0);
  });

  // The owner's requirement in so many words: "ต้องอธิบายด้วยว่ากรอกวิธีหลายแบบในใบเสนอราคาเดียวกัน".
  it('choosing ผสมหลายวิธี says what it means: several methods in one quotation, picked per row', async () => {
    await openDraft(draft());

    fireEvent.click(mixedButton());

    expect(pressed(mixedButton())).toBe(true);
    for (const single of ['ราคาตั้ง − ส่วนลด %', 'ราคาพิเศษ บาท/ตร.ม.', 'ราคาสุทธิ บาท/ตร.ม.']) {
      expect(pressed(modeButton(single)), single).toBe(false);
    }
    const explanation = screen.getByText(/กรอกราคาได้หลายวิธีในใบเสนอราคาเดียวกัน/);
    expect(explanation.textContent).toContain('แต่ละรายการ');
    expect(explanation.textContent).toContain('วิธีคิดราคา');
  });

  // Step 2 of the flow, and the property that makes the switch safe to try: nothing is re-priced.
  it('switching to ผสมหลายวิธี changes no row — each just gains a picker set to the mode it was in', async () => {
    await openDraft(draft({ items: [TILE, { ...TILE, id: 102, seq: 2, model: 'Urbanature' }] }));
    const before = { price: byId('price-0').value, discount: byId('disc-0').value };
    api.dealQuotations.calculateLine.mockClear();

    fireEvent.click(mixedButton());

    expect(rowPickers().map((el) => el.value)).toEqual(['NET', 'NET']);
    expect(byId('price-0').value).toBe(before.price);
    expect(byId('disc-0').value).toBe(before.discount);
    expect(byId('disc-1')).not.toBeNull();
    // Nothing was re-previewed: the rows are in the same mode they were in a moment ago.
    await new Promise((resolve) => { setTimeout(resolve, 450); });
    expect(api.dealQuotations.calculateLine).not.toHaveBeenCalled();
    expect(document.body.textContent).toContain('9,682.00');
  });

  it('a saved mixed draft opens with each row in its own mode, and says how many use which', async () => {
    await openDraft(mixedDraft());

    expect(pressed(mixedButton())).toBe(true);
    expect(rowPickers().map((el) => el.value)).toEqual(['NET', 'SPECIAL_SQM']);
    // Row 1 asks for a discount; row 2 for a ราคาพิเศษ — on the same document.
    expect(byId('disc-0').value).toBe('20');
    expect(byId('special-0')).toBeNull();
    expect(byId('special-1').value).toBe('790');
    expect(byId('disc-1')).toBeNull();
    expect(screen.getByTestId('mixed-price-mode-summary').textContent)
      .toBe('ตอนนี้: ราคาตั้ง − ส่วนลด % 1 รายการ · ราคาพิเศษ บาท/ตร.ม. 1 รายการ');
  });
});

describe('one row at a time', () => {
  it('changing a row\'s วิธีคิดราคา re-asks THAT row only, and previews it stating its mode', async () => {
    await openDraft(mixedDraft({ items: [NET_ROW, { ...NET_ROW, id: 102, seq: 2, model: 'Urbanature' }] }));
    api.dealQuotations.calculateLine.mockClear();

    fireEvent.change(rowPicker(1), { target: { value: 'SPECIAL_SQM' } });

    // Row 2 now asks for a ราคาพิเศษ; row 1 is exactly as it was.
    expect(byId('special-1')).not.toBeNull();
    expect(byId('disc-1')).toBeNull();
    expect(byId('disc-0').value).toBe('20');
    expect(byId('special-0')).toBeNull();
    expect(rowPickers().map((el) => el.value)).toEqual(['NET', 'SPECIAL_SQM']);

    fireEvent.change(byId('special-1'), { target: { value: '790' } });
    await waitFor(() => expect(api.dealQuotations.calculateLine).toHaveBeenCalled(), { timeout: 1500 });
    const sent = api.dealQuotations.calculateLine.mock.calls.at(-1)[0];
    // The mode is STATED (calculate-line has no document to read one off) and only this mode's
    // price travels with it.
    expect(sent).toMatchObject({
      priceMode: 'SPECIAL_SQM', specialPriceSqm: 790, listPriceSqm: 1800, discountPct: null, directNetPriceSqm: null,
    });
  });

  it('the document is saved as MIXED with every row\'s own mode and only that mode\'s prices', async () => {
    await openDraft(mixedDraft());
    fireEvent.change(screen.getByLabelText('หมายเหตุเพิ่มเติม'), { target: { value: 'x' } });

    const payload = await saveDraft();

    expect(payload).toMatchObject({ priceMode: 'MIXED', documentLanguage: 'TH', currency: 'THB' });
    expect(payload.items[0]).toMatchObject({
      id: 101, priceMode: 'NET', listPriceSqm: 1800, discountPct: 20, specialPriceSqm: null, directNetPriceSqm: null,
    });
    expect(payload.items[1]).toMatchObject({
      id: 102, priceMode: 'SPECIAL_SQM', listPriceSqm: 1800, specialPriceSqm: 790, discountPct: null,
    });
  });

  // People enter several rows the same way in a run, so a new row starts where the last one was.
  it('a new row on a mixed document starts in the mode of the row before it', async () => {
    await openDraft(mixedDraft());

    fireEvent.click(screen.getByRole('button', { name: /เพิ่มรายการในตำแหน่งนี้/ }));

    await waitFor(() => expect(rowPickers()).toHaveLength(3));
    expect(rowPicker(2).value).toBe('SPECIAL_SQM');
    expect(byId('special-2')).not.toBeNull();
  });

  it('ยืนราคาแบบ "ระบุวันที่" is offered as soon as ONE row has special pricing', async () => {
    await openDraft(mixedDraft({ items: [{ ...NET_ROW, discountPct: 0, netUnitPrice: 1210.25 }, SPECIAL_ROW] }));
    expect(within(screen.getByRole('group', { name: 'ยืนราคา' })).getByRole('button', { name: 'ระบุวันที่' })).not.toBeNull();
  });

  it('…and not when no row has any', async () => {
    await openDraft(mixedDraft({
      items: [{ ...NET_ROW, discountPct: 0, netUnitPrice: 1210.25, lineAmount: 12102.5 }],
    }));
    expect(within(screen.getByRole('group', { name: 'ยืนราคา' })).queryByRole('button', { name: 'ระบุวันที่' })).toBeNull();
  });
});

describe('back to one mode for the whole document', () => {
  it('asks first when it would re-price rows that use another mode — and cancelling changes nothing', async () => {
    await openDraft(mixedDraft());

    fireEvent.click(modeButton('ราคาตั้ง − ส่วนลด %'));

    // Named: which mode, and how many rows it will re-price.
    expect(screen.getByText('ใช้ “ราคาตั้ง − ส่วนลด %” ทั้งใบ?')).not.toBeNull();
    expect(screen.getByText(/มี 1 รายการที่กรอกราคาด้วยวิธีอื่นอยู่/)).not.toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'ยกเลิก' }));

    expect(pressed(mixedButton())).toBe(true);
    expect(rowPickers().map((el) => el.value)).toEqual(['NET', 'SPECIAL_SQM']);
    expect(byId('special-1').value).toBe('790');
    expect(screen.queryByText('ใช้ “ราคาตั้ง − ส่วนลด %” ทั้งใบ?')).toBeNull();
  });

  it('confirming puts every row in that mode: no per-row pickers, and nothing about a row mode is saved', async () => {
    await openDraft(mixedDraft());

    fireEvent.click(modeButton('ราคาตั้ง − ส่วนลด %'));
    fireEvent.click(screen.getByRole('button', { name: 'เปลี่ยนทั้งใบ' }));

    expect(pressed(modeButton('ราคาตั้ง − ส่วนลด %'))).toBe(true);
    expect(pressed(mixedButton())).toBe(false);
    expect(rowPickers()).toHaveLength(0);
    // Row 2 is now asked the NET way.
    expect(byId('disc-1')).not.toBeNull();
    expect(byId('special-1')).toBeNull();

    const payload = await saveDraft();
    expect(payload.priceMode).toBe('NET');
    for (const item of payload.items) expect(item).not.toHaveProperty('priceMode');
    expect(payload.items[1]).toMatchObject({ specialPriceSqm: null, discountPct: 0, listPriceSqm: 1800 });
  });

  // No question when there is nothing to lose: every row is already priced that way.
  it('does not ask when every row is already in that mode', async () => {
    await openDraft(mixedDraft({ items: [NET_ROW, { ...NET_ROW, id: 102, seq: 2 }] }));

    fireEvent.click(modeButton('ราคาตั้ง − ส่วนลด %'));

    expect(screen.queryByText(/ทั้งใบ\?/)).toBeNull();
    expect(pressed(modeButton('ราคาตั้ง − ส่วนลด %'))).toBe(true);
    expect(rowPickers()).toHaveLength(0);
  });
});

describe('where mixing is not offered', () => {
  it('an English document has no ผสมหลายวิธี', async () => {
    await openDraft(draft({
      documentLanguage: 'EN', currency: 'USD', vatAmount: 0,
      items: [{ ...TILE, listPriceSqm: null, unitPrice: 100, netUnitPrice: 80, unit: 'PCS' }],
    }));
    expect(mixedButton()).toBeNull();
    expect(screen.queryByText('หรือให้แต่ละรายการเลือกเอง')).toBeNull();
  });

  it('switching a mixed document to English moves it to one mode for the whole document, and says so', async () => {
    await openDraft(mixedDraft());

    fireEvent.click(within(screen.getByRole('group', { name: 'ภาษาเอกสาร' })).getByRole('button', { name: /English/ }));

    expect(mixedButton()).toBeNull();
    expect(pressed(modeButton('ราคาตั้ง − ส่วนลด %'))).toBe(true);
    expect(rowPickers()).toHaveLength(0);
    // Said in the same notice that announces the cleared prices — never a silent change of mode.
    const notice = screen.getByText(/เปลี่ยนภาษาเอกสาร/);
    expect(notice.textContent).toContain('ผสมหลายวิธี');
    expect(notice.textContent).toContain('ราคาตั้ง − ส่วนลด %');
  });

  // Its mode is the CEO's decision; the server refuses a mix there (DealQuotationService
  // #requirePriceModeAllowed), so the editor does not offer one it would then have to explain away.
  it('a quotation that came from a คำขอราคา has no ผสมหลายวิธี', async () => {
    await openDraft(draft({
      origin: 'PRICING_REQUEST', pricingRequestId: 9, pricingRequestCode: 'PCR-2026-0009', ceoPriceMode: 'NET',
    }));
    expect(mixedButton()).toBeNull();
    expect(modeButton('ราคาพิเศษ บาท/ตร.ม.')).not.toBeNull();
  });
});

describe('a single-mode quotation is exactly as it was', () => {
  it('saves no row mode and the document\'s own mode, untouched', async () => {
    await openDraft(draft());
    fireEvent.change(screen.getByLabelText('หมายเหตุเพิ่มเติม'), { target: { value: 'x' } });

    const payload = await saveDraft();

    expect(payload.priceMode).toBe('NET');
    expect(payload.items[0]).not.toHaveProperty('priceMode');
    expect(payload.items[0]).toMatchObject({ listPriceSqm: 1800, discountPct: 20 });
  });
});
