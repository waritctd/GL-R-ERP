import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QuotationEditorPage } from './QuotationEditorPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// Each case here mounts the WHOLE editor and drives it through a debounced calculate-line and a save — 1–4 s
// alone, and measured at 6.6 s for one of them under the full parallel suite, past vitest's 5 s default.
vi.setConfig({ testTimeout: 15000 });

// Quotation v3 / v3b controls (customer address, repeat-customer autofill, the "ข้อมูลที่ยังไม่ครบ"
// checklist), driven through the WHOLE editor page. Separate from QuotationEditorPage.test.jsx only
// to keep that file's fixtures (pre-v3 DTOs, no address) as the regression net they are.
vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: { get: vi.fn(), create: vi.fn() },
      dealQuotations: {
        get: vi.fn(), create: vi.fn(), update: vi.fn(), calculateLine: vi.fn(), submit: vi.fn(),
      },
      catalog: { prices: vi.fn() },
      // Consumed by DesignerPicker.jsx. getByCode default-resolves to undefined so the picker's
      // resolved-name hint effect finds nothing rather than throwing when a test never sets a unitCode.
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
const CONTACTS = [
  { id: 6, customerId: 5, firstName: 'ณัฐพงศ์', lastName: 'ศรีวิไล', phone: '086-222-3333', email: 'nattapong@fashionisland.co.th' },
];

// A COMPLETE stored tile row (validateQuotationItem finds nothing missing) in the full v3 DTO shape.
// leadTimeMinDays/leadTimeMaxDays are SET because submit refuses a TILE row with neither; this
// fixture backs most ส่งขออนุมัติ-enabled assertions, which are unrelated to lead time. The dedicated
// describe block below exercises a row with NO lead time.
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
    // unitCode is non-blank in the SHARED fixture: designer is NOT required, but its checklist
    // warning renders from the first commit (unlike the address warning, it does not wait on the
    // ticket/quotation queries). Per-test overrides exercise the blank-designer path.
    projectName: 'โครงการ A', deptCode: null, unitCode: 'A001', offerDate: '2026-09-01', depositPercent: 30,
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
  // ── "Clear all prices on switch" ──
  const PLAIN_ITEM = {
    id: 102, seq: 2, lineType: 'PLAIN', locationLabel: null, descriptionLine: 'ค่าขนส่ง', quantity: 1, unit: 'งาน',
    unitPrice: 800, discountPct: 0, netUnitPrice: 800, lineAmount: 800, piecesFinal: 0,
  };
  const inputValues = () => [...document.querySelectorAll('input')].map((el) => el.value);
  const byId = (id) => document.getElementById(id);
  async function flushPreviewDebounce() {
    await new Promise((resolve) => { setTimeout(resolve, 450); });
  }

  it('a SAVED English per-sqm draft switched to Thai clears EVERY price — the USD/sqm 64 appears nowhere — and keeps %, quantities and box figures', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: draft({
        priceMode: 'SPECIAL_SQM', documentLanguage: 'EN', currency: 'USD',
        subtotalAmount: 4454.75, vatAmount: 0, grandTotal: 4454.75,
        items: [
          // What the server serves for an English per-sqm row: unitPrice IS the USD/sqm (64).
          { ...TILE_ITEM, unitPrice: 64, specialPriceSqm: 64, netUnitPrice: 64, lineAmount: 4608, quantity: 72, unit: 'SQM', sqmPerBox: 0.6, discountPct: null },
          { ...PLAIN_ITEM, unitPrice: 777, netUnitPrice: 777, lineAmount: 777, unit: 'JOB' },
          { id: 103, seq: 3, lineType: 'ADJUSTMENT', adjustmentPct: null, adjustmentAmount: 55, unitPrice: 55, netUnitPrice: 55, lineAmount: -55, quantity: -1, unit: null, descriptionLine: 'Rebate', piecesFinal: 0 },
          { id: 104, seq: 4, lineType: 'ADJUSTMENT', adjustmentPct: 3, adjustmentAmount: null, unitPrice: 139.29, netUnitPrice: 139.29, lineAmount: -139.29, quantity: -1, unit: null, descriptionLine: 'Special discount 3%', piecesFinal: 0 },
        ],
      }),
    });
    renderEditor('/quotations/5');
    await waitFor(() => expect(byId('special-0')?.value).toBe('64'));
    expect(inputValues()).toContain('777');
    expect(inputValues()).toContain('55');
    api.dealQuotations.calculateLine.mockClear();

    fireEvent.click(within(group('ภาษาเอกสาร')).getByRole('button', { name: /ไทย/ }));

    // Thai SPECIAL_SQM shows BOTH the list price and the ราคาพิเศษ — neither may inherit the USD 64.
    expect(byId('price-0').value).toBe('');
    expect(byId('special-0').value).toBe('');
    expect(byId('plain-price-1').value).toBe('');
    expect(screen.getByLabelText(/^จำนวนเงินส่วนลด/).value).toBe('');
    const values = inputValues();
    for (const stale of ['64', '777', '55', '4608', '139.29']) expect(values, `no input still holds ${stale}`).not.toContain(stale);
    // Non-money kept: the area, the pieces-per-box, the plain quantity and the PERCENT adjustment.
    expect(byId('plain-qty-1').value).toBe('1');
    expect(byId('ppb-0').value).toBe('3');
    expect(screen.getByLabelText(/^ส่วนลด %/, { selector: '[id^="adj-pct-"]' }).value).toBe('3');
    await flushPreviewDebounce();
    expect(document.body.textContent).not.toMatch(/4,608|4,454\.75|777\.00/);
    expect(api.dealQuotations.calculateLine).not.toHaveBeenCalled();
    expect(screen.getByText('เปลี่ยนภาษาเอกสาร: ล้างราคาทั้งหมดแล้ว — กรุณากรอกราคาใหม่เป็นบาท')).not.toBeNull();
    expect(screen.queryByText(/ตามตัวเลขเดิม/)).toBeNull();
    expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(true);
  }, 20000);

  it('ALWAYS sends priceMode and documentLanguage on update, even when untouched', async () => {
    renderEditor('/quotations/5');
    await screen.findByRole('group', { name: 'ภาษาเอกสาร' });
    fireEvent.change(screen.getByLabelText('หมายเหตุเพิ่มเติม'), { target: { value: 'x' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalled());
    expect(api.dealQuotations.update.mock.calls[0][1]).toMatchObject({ priceMode: 'NET', documentLanguage: 'TH', currency: 'THB' });
  });
});

// Submit requires a lead time on every TILE row (SUBMIT only; a draft still saves with none).
// Mirrors DealQuotationService#requireEveryTileItemHasALeadTime.
describe('lead time required to submit (owner feedback #7, 2026-09-14)', () => {
  const byId = (id) => document.getElementById(id);

  it('a TILE row with no lead time BLOCKS ส่งขออนุมัติ, naming the item, but บันทึกร่าง stays enabled', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: draft({ items: [{ ...TILE_ITEM, leadTimeMinDays: null, leadTimeMaxDays: null }] }),
    });
    renderEditor('/quotations/5');
    const submitBtn = await screen.findByRole('button', { name: 'ส่งขออนุมัติ' });

    expect(submitBtn.disabled).toBe(true);
    expect(submitBtn.title).toBe('กรุณาระบุระยะเวลานำเข้า (วัน) ของรายการที่ 1');
    // The checklist names the same gap — it must not read "ข้อมูลในเอกสารครบแล้ว" while submit is blocked.
    expect(within(await screen.findByTestId('checklist-submit-blocking'))
      .getByText('กรุณาระบุระยะเวลานำเข้า (วัน) ของรายการที่ 1')).not.toBeNull();
    expect(screen.queryByText('ข้อมูลในเอกสารครบแล้ว')).toBeNull();
    // The SAME rule the server enforces at submit is not a draft-completeness rule -- บันทึกร่าง must stay
    // clickable with no lead time typed anywhere.
    expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false);
  });

  it('typing both lead-time numbers clears the block', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: draft({ items: [{ ...TILE_ITEM, leadTimeMinDays: null, leadTimeMaxDays: null }] }),
    });
    renderEditor('/quotations/5');
    await screen.findByRole('button', { name: 'ส่งขออนุมัติ' });

    fireEvent.change(byId('lead-0'), { target: { value: '30' } });
    fireEvent.change(byId('lead-max-0'), { target: { value: '45' } });

    await waitFor(() => expect(screen.getByRole('button', { name: 'ส่งขออนุมัติ' }).disabled).toBe(false));
  });
});

// Typing 0 as a custom deposit % must block ส่งขออนุมัติ, not just show depositZeroError: depositPercent
// = 0 with no fullPaymentTerm is reachable two ways (the "ไม่รับมัดจำ" checkbox, OR typing "0" into the
// custom "อื่นๆ" input while unticked) and would otherwise only surface as the server's 400 at submit.
// Mirrors DealQuotationService#submit's gate (isZeroDeposit(depositPercent) && isBlank(fullPaymentTerm)).
// SUBMIT only, like "lead time required to submit" above: a draft with this shape still saves.
describe('0% deposit requires a payment term to submit (Opus review F2, 2026-09-16)', () => {
  it('typing "0" into the custom "อื่นๆ" input (checkbox left UNticked) BLOCKS ส่งขออนุมัติ, but บันทึกร่าง stays enabled', async () => {
    renderEditor('/quotations/5'); // draft() default: depositPercent 30, checkbox unticked
    await screen.findByRole('button', { name: '30%' });

    fireEvent.click(screen.getByRole('button', { name: 'อื่นๆ' }));
    fireEvent.change(screen.getByLabelText('มัดจำ %'), { target: { value: '0' } });

    const submitBtn = await screen.findByRole('button', { name: 'ส่งขออนุมัติ' });
    expect(submitBtn.disabled).toBe(true);
    expect(submitBtn.title).toBe('มัดจำ 0% ต้องเลือกเงื่อนไขการชำระเงินก่อนส่งขออนุมัติ');
    expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false);
  });

  it('choosing a term after ticking "ไม่รับมัดจำ" clears the block', async () => {
    renderEditor('/quotations/5');
    await screen.findByRole('button', { name: '30%' });

    fireEvent.click(screen.getByRole('checkbox', { name: /ไม่รับมัดจำ/ }));
    await screen.findByRole('button', { name: 'ส่งขออนุมัติ' });
    fireEvent.change(screen.getByLabelText('เงื่อนไขการชำระเงิน'), { target: { value: 'ON_DELIVERY' } });

    await waitFor(() => expect(screen.getByRole('button', { name: 'ส่งขออนุมัติ' }).disabled).toBe(false));
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
});

describe('customer address + repeat-customer autofill (owner, 2026-09-11)', () => {
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

describe('ยืนราคา — จำนวนวัน / ระบุวันที่ toggle (V178, owner ruling 2026-09-14)', () => {
  it('choosing ระบุวันที่ shows a date input and saves validityMode/validityUntil; validityDays is still sent', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: draft({ priceMode: 'NET', items: [{ ...TILE_ITEM, discountPct: 10 }] }),
    });
    renderEditor('/quotations/5');
    await screen.findByTestId('checklist-warnings');
    fireEvent.click(within(group('ยืนราคา')).getByRole('button', { name: 'ระบุวันที่' }));

    const dateInput = screen.getByLabelText('ยืนราคาถึงวันที่');
    expect(dateInput).not.toBeNull();
    // The days select is gone from the DOM while DATE mode shows, but its VALUE (30, from the draft) must still be
    // sent on save — see #buildUpsertPayload's own comment.
    expect(screen.queryByLabelText('validityDays')).toBeNull();
    // The rep types the date in พ.ศ.; the API still receives ค.ศ. ISO (PDF adds 543 itself). A พ.ศ.
    // year landing in a ค.ศ. field would turn 31/01/2570 into "31/01/3113".
    fireEvent.change(dateInput, { target: { value: '31/12/2569' } });
    fireEvent.blur(dateInput);
    expect(dateInput.value).toBe('31/12/2569');

    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalled());
    const payload = api.dealQuotations.update.mock.calls[0][1];
    expect(payload.validityMode).toBe('DATE');
    expect(payload.validityUntil).toBe('2026-12-31');
    expect(payload.validityDays).toBe(30);
  });
});

// What a typed per-ตร.ม. (incl. VAT) price asks the server, what comes back onto the row, and what a
// save carries, driven through the whole editor. quotationPerSqmPrice.test.jsx pins the row and the
// pure helpers.
describe('ราคา/ตร.ม. รวม VAT ทุกแบบ (sales feedback #1, 2026-10-06, V192.2)', () => {
  const byId = (id) => document.getElementById(id);
  const lastPreviewed = () => api.dealQuotations.calculateLine.mock.calls.at(-1)[0];

  async function addRowWithArea() {
    await screen.findByRole('group', { name: 'วิธีกรอกราคากระเบื้อง' });
    fireEvent.click(screen.getByRole('button', { name: /เพิ่มรายการในตำแหน่งนี้/ }));
    fireEvent.change(screen.getByLabelText(/^แผ่น\/ตร\.ม\./), { target: { value: '1.39' } });
  }

  it('typing ราคา/ตร.ม. asks the server with that figure and no per-แผ่น price, then shows the per-แผ่น answer', async () => {
    api.dealQuotations.calculateLine.mockResolvedValue({
      item: { unitPrice: 1210.25, netUnitPrice: 1210.25, lineAmount: 12102.5, calculationLine: '(server calc)' },
    });
    renderEditor('/quotations/new?ticket=18');
    await addRowWithArea();
    fireEvent.change(screen.getByLabelText(/^ราคา\/ตร\.ม\./), { target: { value: '1800' } });

    await waitFor(() => expect(screen.getByTestId('list-per-piece-0').textContent).toBe('= ฿1,210.25/แผ่น (ก่อน VAT)'));
    expect(lastPreviewed()).toMatchObject({ lineType: 'TILE', listPriceSqm: 1800, unitPrice: null, directNetPriceSqm: null });
    // The box keeps what the rep typed — the per-แผ่น answer goes UNDER it, never into it.
    expect(screen.getByLabelText(/^ราคา\/ตร\.ม\./).value).toBe('1800');
  });

  it('ราคาสุทธิ: the net is typed per ตร.ม., and a blank ราคาตั้ง is sent as that same net — without filling its own box in', async () => {
    api.dealQuotations.calculateLine.mockResolvedValue({
      item: { unitPrice: 531.16, netUnitPrice: 531.16, lineAmount: 5311.6, calculationLine: '(server calc)' },
    });
    renderEditor('/quotations/new?ticket=18');
    await screen.findByRole('group', { name: 'วิธีกรอกราคากระเบื้อง' });
    fireEvent.click(within(group('วิธีกรอกราคากระเบื้อง')).getByRole('button', { name: 'ราคาสุทธิ บาท/ตร.ม.' }));
    await addRowWithArea();
    fireEvent.change(screen.getByLabelText(/^ราคาสุทธิ\/ตร\.ม\./), { target: { value: '790' } });

    await waitFor(() => expect(screen.getByTestId('direct-net-per-piece-0').textContent).toBe('= สุทธิ ฿531.16/แผ่น (ก่อน VAT)'));
    expect(lastPreviewed()).toMatchObject({ directNetPriceSqm: 790, listPriceSqm: 790, unitPrice: null, directNetPrice: null });
    // "เว้นว่าง = ใช้ราคาสุทธิ" has to stay blank: the list price the server derived for THIS request was the
    // editor's own stand-in, not something the rep typed.
    expect(byId('price-0').value).toBe('');
  });
});
