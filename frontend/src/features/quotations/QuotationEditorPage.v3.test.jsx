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
// leadTimeMinDays/leadTimeMaxDays are SET (owner feedback #7, 2026-09-14: submit now refuses a
// TILE row with neither) -- this shared fixture backs most of this file's ส่งขออนุมัติ-enabled
// assertions, which have nothing to do with lead time; the dedicated describe block below is
// where a row with NO lead time is exercised on purpose.
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
    // unitCode non-blank in the SHARED fixture (owner ruling 2026-09-14: designer is NOT
    // required, but its checklist warning renders from the very first commit -- unlike the
    // address warning, it does not wait on the ticket/quotation queries to resolve). Per-test
    // overrides still exercise the blank-designer path where that matters.
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
  // ── owner ruling 2026-09-13: "Clear all prices on switch" ───────────────────────────────────
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

  it.each([
    ['NET', { discountPct: 5 }, (i) => { fireEvent.change(byId(`price-${i}`), { target: { value: '12' } }); }, { unitPrice: 12, discountPct: 5, directNetPrice: null, specialPriceSqm: null }],
    ['DIRECT_NET', { unitPrice: 900, netUnitPrice: 850 }, (i) => { fireEvent.change(byId(`direct-net-${i}`), { target: { value: '11' } }); }, { directNetPrice: 11, unitPrice: 11, specialPriceSqm: null }],
    ['SPECIAL_SQM', { specialPriceSqm: 1350, netUnitPrice: 453.84 }, (i) => {
      fireEvent.change(byId(`special-${i}`), { target: { value: '64' } });
      fireEvent.change(byId(`sqm-box-${i}`), { target: { value: '0.6' } });
    }, { specialPriceSqm: 64, unitPrice: 64, sqmPerBox: 0.6, directNetPrice: null }],
  ])('a THAI %s draft switched to English clears every price, keeps percentages and quantities, blocks save, then saves the NEW numbers', async (mode, tileOverrides, reenterTile, expectedTile) => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: draft({ priceMode: mode, items: [{ ...TILE_ITEM, ...tileOverrides }, PLAIN_ITEM] }),
    });
    renderEditor('/quotations/5');
    await waitFor(() => expect(byId('plain-price-1')?.value).toBe('800'));
    api.dealQuotations.calculateLine.mockClear();

    fireEvent.click(within(group('ภาษาเอกสาร')).getByRole('button', { name: /English/ }));

    for (const id of ['price-0', 'direct-net-0', 'special-0', 'plain-price-1']) {
      if (byId(id)) expect(byId(id).value, id).toBe('');
    }
    for (const stale of ['800', '850', '900', '1350']) expect(inputValues(), `no input still holds ${stale}`).not.toContain(stale);
    if (mode === 'NET') expect(byId('disc-0').value).toBe('5');
    expect(byId('plain-qty-1').value).toBe('1');
    expect(screen.getByText('เปลี่ยนภาษาเอกสาร: ล้างราคาทั้งหมดแล้ว — กรุณากรอกราคาใหม่เป็น USD (ไม่มี VAT)')).not.toBeNull();
    await flushPreviewDebounce();
    expect(api.dealQuotations.calculateLine).not.toHaveBeenCalled();
    expect(document.body.textContent).not.toMatch(/48,450|51,841\.50|800\.00/);
    expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(true);

    reenterTile(0);
    fireEvent.change(byId('plain-price-1'), { target: { value: '700' } });
    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalled());
    const [, payload] = api.dealQuotations.update.mock.calls[0];
    expect(payload).toMatchObject({ priceMode: mode, documentLanguage: 'EN', currency: 'USD' });
    expect(payload.items[0]).toMatchObject(expectedTile);
    expect(payload.items[1]).toMatchObject({ lineType: 'PLAIN', unitPrice: 700, quantity: 1 });
  }, 25000);

  it('switching back does NOT restore any cleared price', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: draft({ priceMode: 'SPECIAL_SQM', items: [{ ...TILE_ITEM, specialPriceSqm: 1350, netUnitPrice: 453.84 }, PLAIN_ITEM] }),
    });
    renderEditor('/quotations/5');
    await waitFor(() => expect(byId('special-0')?.value).toBe('1350'));
    fireEvent.click(within(group('ภาษาเอกสาร')).getByRole('button', { name: /English/ }));
    fireEvent.click(within(group('ภาษาเอกสาร')).getByRole('button', { name: /ไทย/ }));
    expect(byId('special-0').value).toBe('');
    expect(byId('price-0').value).toBe('');
    expect(byId('plain-price-1').value).toBe('');
    for (const stale of ['1350', '850', '800']) expect(inputValues()).not.toContain(stale);
  }, 20000);

  it('the live preview of a Thai draft still calls calculate-line with TH', async () => {
    renderEditor('/quotations/new?ticket=18');
    await screen.findByRole('group', { name: 'วิธีกรอกราคากระเบื้อง' });
    fireEvent.click(screen.getByRole('button', { name: /เพิ่มรายการในตำแหน่งนี้/ }));
    fireEvent.change(screen.getByLabelText(/^ราคา\/หน่วย/), { target: { value: '350' } });
    await waitFor(() => expect(api.dealQuotations.calculateLine).toHaveBeenCalled(), { timeout: 1000 });
    expect(api.dealQuotations.calculateLine.mock.calls.at(-1)[1]).toBe('TH');
  });

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
    // The Thai ราคาพิเศษ needs its list price as well (DealQuotationService#requirePriceValidForType
    // refuses a TILE row without one); until both are typed the editor previews nothing at all —
    // quotationMeta#rowHasPriceForPreview — rather than a figure priced under another mode.
    fireEvent.change(screen.getByLabelText(/^ราคาตั้ง \(บาท\/แผ่น\)/), { target: { value: '2000' } });
    fireEvent.change(screen.getByLabelText(/^ราคาพิเศษ \(บาท\/ตร\.ม\./), { target: { value: '1350' } });

    await waitFor(() => expect(screen.getByTestId('special-net-0').textContent).toBe('= สุทธิ ฿453.84/แผ่น (ก่อน VAT)'));
    const sent = api.dealQuotations.calculateLine.mock.calls.at(-1)[0];
    expect(sent).toMatchObject({ lineType: 'TILE', specialPriceSqm: 1350, directNetPrice: null, discountPct: null });
  });
});

// Owner feedback #7 (2026-09-14): submit now requires a lead time on every TILE row — a deliberate
// sales-workflow rule change, SUBMIT only (a draft still saves with none). Mirrors
// DealQuotationService#requireEveryTileItemHasALeadTime.
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
    // The SAME rule the server enforces at submit is not a draft-completeness rule -- บันทึกร่าง
    // must stay clickable with no lead time typed anywhere.
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

  it('a HALF-filled lead time (only min, or only max) still blocks', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: draft({ items: [{ ...TILE_ITEM, leadTimeMinDays: 30, leadTimeMaxDays: null }] }),
    });
    renderEditor('/quotations/5');
    const submitBtn = await screen.findByRole('button', { name: 'ส่งขออนุมัติ' });
    expect(submitBtn.disabled).toBe(true);
  });

  it('multiple rows missing a lead time are ALL named, comma-separated, by their own item number', async () => {
    const PLAIN_ITEM = {
      id: 102, seq: 2, lineType: 'PLAIN', locationLabel: null, descriptionLine: 'ค่าขนส่ง', quantity: 1,
      unit: 'งาน', unitPrice: 800, discountPct: 0, netUnitPrice: 800, lineAmount: 800, piecesFinal: 0,
    };
    api.dealQuotations.get.mockResolvedValue({
      quotation: draft({
        items: [
          { ...TILE_ITEM, seq: 1, leadTimeMinDays: null, leadTimeMaxDays: null },
          PLAIN_ITEM, // #7: PLAIN is exempt -- never named, and never counted as item 3 itself.
          { ...TILE_ITEM, id: 105, seq: 3, leadTimeMinDays: null, leadTimeMaxDays: null },
        ],
      }),
    });
    renderEditor('/quotations/5');
    const submitBtn = await screen.findByRole('button', { name: 'ส่งขออนุมัติ' });
    expect(submitBtn.title).toBe('กรุณาระบุระยะเวลานำเข้า (วัน) ของรายการที่ 1, 3');
  });

  it('a PLAIN-only document with no TILE row at all never blocks on lead time', async () => {
    const PLAIN_ITEM = {
      id: 102, seq: 1, lineType: 'PLAIN', locationLabel: null, descriptionLine: 'ค่าขนส่ง', quantity: 1,
      unit: 'งาน', unitPrice: 800, discountPct: 0, netUnitPrice: 800, lineAmount: 800, piecesFinal: 0,
    };
    api.dealQuotations.get.mockResolvedValue({ quotation: draft({ items: [PLAIN_ITEM] }) });
    renderEditor('/quotations/5');
    const submitBtn = await screen.findByRole('button', { name: 'ส่งขออนุมัติ' });
    expect(submitBtn.disabled).toBe(false);
  });

  it('the row itself shows an inline hint on an existing draft (already "touched" on load)', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: draft({ items: [{ ...TILE_ITEM, leadTimeMinDays: null, leadTimeMaxDays: null }] }),
    });
    renderEditor('/quotations/5');
    await screen.findByRole('button', { name: 'ส่งขออนุมัติ' });

    expect(screen.getByText('กรุณาระบุระยะเวลานำเข้า (วัน)')).not.toBeNull();
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

describe('ยืนราคา — จำนวนวัน / ระบุวันที่ toggle (V178, owner ruling 2026-09-14)', () => {
  it('ระบุวันที่ is hidden on a document with no special pricing (TILE_ITEM has discountPct 0)', async () => {
    renderEditor('/quotations/5');
    await screen.findByTestId('checklist-warnings');
    const toggle = group('ยืนราคา');
    expect(within(toggle).getByRole('button', { name: 'จำนวนวัน' })).not.toBeNull();
    expect(within(toggle).queryByRole('button', { name: 'ระบุวันที่' })).toBeNull();
    // The days select still renders (จำนวนวัน is always available).
    expect(document.getElementById('validityDays')).not.toBeNull();
  });

  it('ระบุวันที่ is offered on a document WITH special pricing, in DIRECT_NET (2c)', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: draft({ priceMode: 'DIRECT_NET', items: [{ ...TILE_ITEM, unitPrice: 850, netUnitPrice: 700 }] }),
    });
    renderEditor('/quotations/5');
    await screen.findByTestId('checklist-warnings');
    const toggle = group('ยืนราคา');
    expect(within(toggle).getByRole('button', { name: 'ระบุวันที่' })).not.toBeNull();
  });

  it('choosing ระบุวันที่ shows a date input and saves validityMode/validityUntil; validityDays is still sent', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: draft({ priceMode: 'NET', items: [{ ...TILE_ITEM, discountPct: 10 }] }),
    });
    renderEditor('/quotations/5');
    await screen.findByTestId('checklist-warnings');
    fireEvent.click(within(group('ยืนราคา')).getByRole('button', { name: 'ระบุวันที่' }));

    const dateInput = screen.getByLabelText('ยืนราคาถึงวันที่');
    expect(dateInput).not.toBeNull();
    // The days select is gone from the DOM while DATE mode shows, but its VALUE (30, from the
    // draft) must still be sent on save — see #buildUpsertPayload's own comment.
    expect(screen.queryByLabelText('validityDays')).toBeNull();
    fireEvent.change(dateInput, { target: { value: '2026-12-31' } });

    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalled());
    const payload = api.dealQuotations.update.mock.calls[0][1];
    expect(payload.validityMode).toBe('DATE');
    expect(payload.validityUntil).toBe('2026-12-31');
    expect(payload.validityDays).toBe(30);
  });

  it('losing special pricing while in ระบุวันที่ mode auto-switches back to จำนวนวัน', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: draft({ priceMode: 'NET', items: [{ ...TILE_ITEM, discountPct: 10 }] }),
    });
    renderEditor('/quotations/5');
    await screen.findByTestId('checklist-warnings');
    fireEvent.click(within(group('ยืนราคา')).getByRole('button', { name: 'ระบุวันที่' }));
    expect(screen.getByLabelText('ยืนราคาถึงวันที่')).not.toBeNull();

    // Clear the discount on the only tile row -- the document no longer has special pricing.
    fireEvent.change(screen.getByLabelText('ส่วนลด %'), { target: { value: '0' } });

    await waitFor(() => expect(screen.queryByLabelText('ยืนราคาถึงวันที่')).toBeNull());
    expect(document.getElementById('validityDays')).not.toBeNull();
    expect(within(group('ยืนราคา')).queryByRole('button', { name: 'ระบุวันที่' })).toBeNull();
  });
});
