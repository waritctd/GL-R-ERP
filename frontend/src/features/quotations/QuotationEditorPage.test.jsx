import React from 'react';
import { focusManager, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QuotationEditorPage } from './QuotationEditorPage.jsx';
import { api } from '../../api/index.js';
import { addDaysIso, bangkokTodayIso } from '../../utils/format.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: { get: vi.fn(), create: vi.fn() },
      dealQuotations: {
        get: vi.fn(),
        create: vi.fn(),
        update: vi.fn(),
        calculateLine: vi.fn(),
        submit: vi.fn(),
        approve: vi.fn(),
        reject: vi.fn(),
        createRevision: vi.fn(),
        cancel: vi.fn(),
        downloadPdf: vi.fn(),
        downloadXlsx: vi.fn(),
      },
      catalog: { prices: vi.fn() },
      // Consumed by DealCustomerCard.jsx, which QuotationEditorPage renders instead of the
      // read-only deal summary whenever /quotations/new has no ?ticket= (inline deal creation,
      // owner ask 2026-09-10) -- see the "inline deal creation" describe block below.
      customers: {
        search: vi.fn(),
        create: vi.fn(),
        projects: vi.fn(),
        createProject: vi.fn(),
        contacts: vi.fn(),
        createContact: vi.fn(),
      },
    },
  };
});

const salesUser = { id: 6, name: 'คุณสมหมาย ขายดี', role: 'sales', employeeId: null };
const ceoUser = { id: 8, name: 'ราม', role: 'ceo', employeeId: 136 };
const importUser = { id: 7, name: 'คุณนำเข้า พานิช', role: 'import', employeeId: null };
const otherSalesUser = { id: 999, name: 'คุณอื่น ขายดี', role: 'sales', employeeId: null };

function renderEditor(initialPath, user = salesUser) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const result = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route path="/quotations/new" element={<QuotationEditorPage user={user} showToast={vi.fn()} />} />
          <Route path="/quotations/:id" element={<QuotationEditorPage user={user} showToast={vi.fn()} />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { ...result, queryClient };
}

// Full DealQuotationDto shape, matching the fixture in the "shows ยังไม่บันทึก" test above --
// factored out so the new tests below don't each hand-roll the whole DTO.
function baseQuotation(overrides = {}) {
  return {
    id: 5, number: 'QT-2026-0005', ticketId: 18, docStatus: 'DRAFT',
    salesRepId: 6, salesRepName: 'คุณสมหมาย ขายดี', salesRepPhone: null,
    createdByName: 'คุณสมหมาย ขายดี', approvalNote: null,
    customerName: 'บริษัท แฟชั่นไอส์แลนด์ จำกัด', projectName: null,
    deptCode: null, unitCode: null, offerDate: '2026-09-01',
    depositPercent: 30, remainderMode: 'CREDIT', creditDays: 30, validityDays: 30, validityDate: null,
    customerNotes: null, subtotalAmount: 1000, vatAmount: 70, grandTotal: 1070, currency: 'THB',
    approverHasSignature: false, items: [], createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-01T00:00:00Z',
    ...overrides,
  };
}

describe('QuotationEditorPage item calc wiring', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Blocker fix: GET /api/tickets/{id} returns `{ ticket: TicketDto }`
    // (TicketDetailResponse / TicketResponses.java) and TicketDto is ITSELF an envelope
    // (`{summary, items, events, quotation, quotations}` -- TicketDto.java) -- two levels of
    // wrapping. This fixture used to hand-roll `{ ticket: {...flat fields...} }` (one level),
    // matching the OLD (buggy) `.then((r) => r.ticket)` unwrap -- both wrong in the same way, so
    // canCreateDealQuotation read real fields and every "owning sales rep" test passed for the
    // wrong reason (Opus re-check finding). This is the real two-level shape;
    // `.then((r) => r.ticket?.summary)` is what the page now reads off it.
    api.customers.contacts.mockResolvedValue({
      contacts: [
        { id: 6, customerId: 5, firstName: 'ณัฐพงศ์', lastName: 'ศรีวิไล', phone: '086-222-3333', email: 'nattapong@fashionisland.co.th' },
        { id: 7, customerId: 5, firstName: 'พิมพ์ใจ', lastName: 'บุญมาก', phone: '086-444-5555', email: 'pimjai@fashionisland.co.th' },
      ],
    });
    api.tickets.get.mockResolvedValue({
      ticket: {
        summary: {
          id: 18, createdById: 6, createdByName: 'คุณสมหมาย ขายดี',
          customerName: 'บริษัท แฟชั่นไอส์แลนด์ จำกัด', projectName: null,
          // ผู้สั่งซื้อ is REQUIRED since owner feedback F2 (2026-09-10) and prefills from the
          // deal's own contact, so a ticket fixture without these two fields would leave
          // บันทึกร่าง permanently disabled and every assertion below testing the wrong thing.
          customerId: 5, contactId: 6, contactName: 'ณัฐพงศ์ ศรีวิไล',
        },
      },
    });
  });

  it('debounces a calculate-line call ~300ms after an item field changes, and renders the response', async () => {
    api.dealQuotations.calculateLine.mockResolvedValue({
      item: { piecesFinal: 100, lineAmount: 35000, calculationLine: '(พื้นที่ 36 ตร.ม. รวม 100 แผ่น)' },
    });
    renderEditor('/quotations/new?ticket=18');

    fireEvent.click(await screen.findByRole('button', { name: /เพิ่มรายการ/ }));
    const priceInput = screen.getByLabelText(/^ราคา\/หน่วย/);
    fireEvent.change(priceInput, { target: { value: '350' } });

    // Not called synchronously -- the whole point of the debounce.
    expect(api.dealQuotations.calculateLine).not.toHaveBeenCalled();

    await waitFor(() => expect(api.dealQuotations.calculateLine).toHaveBeenCalledTimes(1), { timeout: 1000 });
    expect(api.dealQuotations.calculateLine).toHaveBeenCalledWith(expect.objectContaining({ unitPrice: 350 }));

    expect(await screen.findByText('(พื้นที่ 36 ตร.ม. รวม 100 แผ่น)')).not.toBeNull();
    // getAllByText, not getByText: with only one item, its own line amount and the M6
    // provisional "รวมเป็นเงิน" subtotal are the SAME number (before VAT) and both render --
    // this asserts the calculated amount reached the screen at all, not which element shows it.
    expect(screen.getAllByText('฿35,000.00').length).toBeGreaterThanOrEqual(1);
  });

  it('re-debounces on every keystroke rather than firing once per stroke', async () => {
    api.dealQuotations.calculateLine.mockResolvedValue({ item: { lineAmount: 1, calculationLine: 'x' } });
    renderEditor('/quotations/new?ticket=18');

    fireEvent.click(await screen.findByRole('button', { name: /เพิ่มรายการ/ }));
    const priceInput = screen.getByLabelText(/^ราคา\/หน่วย/);
    fireEvent.change(priceInput, { target: { value: '3' } });
    fireEvent.change(priceInput, { target: { value: '35' } });
    fireEvent.change(priceInput, { target: { value: '350' } });

    await waitFor(() => expect(api.dealQuotations.calculateLine).toHaveBeenCalledTimes(1), { timeout: 1000 });
    // The LAST value wins -- an intermediate keystroke's debounced call was cancelled, not queued.
    expect(api.dealQuotations.calculateLine).toHaveBeenCalledWith(expect.objectContaining({ unitPrice: 350 }));
  });

  it('never calls calculate-line before any item row exists', async () => {
    renderEditor('/quotations/new?ticket=18');
    await screen.findByRole('heading', { name: 'สร้างใบเสนอราคา' });

    await new Promise((resolve) => { setTimeout(resolve, 350); });
    expect(api.dealQuotations.calculateLine).not.toHaveBeenCalled();
  });

  it('shows "ยังไม่บันทึก" instead of a stale total once the form is dirty', async () => {
    api.dealQuotations.get.mockResolvedValue({
      item: undefined,
      quotation: {
        id: 5, number: 'QD69-0005', ticketId: 18, docStatus: 'DRAFT',
        salesRepId: 6, salesRepName: 'คุณสมหมาย ขายดี', salesRepPhone: null,
        createdByName: 'คุณสมหมาย ขายดี', approvalNote: null,
        customerName: 'บริษัท แฟชั่นไอส์แลนด์ จำกัด', projectName: null,
        deptCode: null, unitCode: null, offerDate: '2026-09-01',
        depositPercent: 30, remainderMode: 'CREDIT', creditDays: 30, validityDays: 30, validityDate: null,
        customerNotes: null, subtotalAmount: 1000, vatAmount: 70, grandTotal: 1070, currency: 'THB',
        approverHasSignature: false, items: [], createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-01T00:00:00Z',
      },
    });
    renderEditor('/quotations/5');

    expect(await screen.findByText('฿1,070.00')).not.toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /เพิ่มรายการ/ }));

    expect(await screen.findByText(/ยังไม่บันทึก/)).not.toBeNull();
    expect(screen.queryByText('฿1,070.00')).toBeNull();
  });

  // #H2: a per-row request sequence discards a stale response instead of letting it clobber a
  // newer edit's result -- the concrete failure mode was TWO real in-flight calculate-line calls
  // (one per edit, each past its own 300ms debounce) settling OUT OF ORDER.
  it('an older, slower calculate-line response cannot clobber a newer edit\'s result', async () => {
    let resolveFirst;
    let resolveSecond;
    const firstPromise = new Promise((resolve) => { resolveFirst = resolve; });
    const secondPromise = new Promise((resolve) => { resolveSecond = resolve; });
    api.dealQuotations.calculateLine
      .mockReturnValueOnce(firstPromise)
      .mockReturnValueOnce(secondPromise);

    renderEditor('/quotations/new?ticket=18');
    fireEvent.click(await screen.findByRole('button', { name: /เพิ่มรายการ/ }));
    const priceInput = screen.getByLabelText(/^ราคา\/หน่วย/);

    fireEvent.change(priceInput, { target: { value: '100' } });
    await waitFor(() => expect(api.dealQuotations.calculateLine).toHaveBeenCalledTimes(1), { timeout: 1000 });
    expect(api.dealQuotations.calculateLine).toHaveBeenNthCalledWith(1, expect.objectContaining({ unitPrice: 100 }));

    fireEvent.change(priceInput, { target: { value: '200' } });
    await waitFor(() => expect(api.dealQuotations.calculateLine).toHaveBeenCalledTimes(2), { timeout: 1000 });
    expect(api.dealQuotations.calculateLine).toHaveBeenNthCalledWith(2, expect.objectContaining({ unitPrice: 200 }));

    // The NEWER request settles first (a faster response for the latest edit).
    resolveSecond({ item: { lineAmount: 2000, calculationLine: 'newer' } });
    expect(await screen.findByText('newer')).not.toBeNull();

    // The OLDER request settles LAST -- it must be discarded outright, including its own
    // (irrelevant) echoed input fields, not merged over what the newer response already set.
    resolveFirst({ item: { lineAmount: 1000, calculationLine: 'older', unitPrice: 100 } });
    await new Promise((resolve) => { setTimeout(resolve, 50); }); // let any wrongly-queued update flush

    expect(screen.getByText('newer')).not.toBeNull();
    expect(screen.queryByText('older')).toBeNull();
    expect(priceInput.value).toBe('200');
  });
});

describe('QuotationEditorPage new-quotation authorization (#M2)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Blocker fix: GET /api/tickets/{id} returns `{ ticket: TicketDto }`
    // (TicketDetailResponse / TicketResponses.java) and TicketDto is ITSELF an envelope
    // (`{summary, items, events, quotation, quotations}` -- TicketDto.java) -- two levels of
    // wrapping. This fixture used to hand-roll `{ ticket: {...flat fields...} }` (one level),
    // matching the OLD (buggy) `.then((r) => r.ticket)` unwrap -- both wrong in the same way, so
    // canCreateDealQuotation read real fields and every "owning sales rep" test passed for the
    // wrong reason (Opus re-check finding). This is the real two-level shape;
    // `.then((r) => r.ticket?.summary)` is what the page now reads off it.
    api.customers.contacts.mockResolvedValue({
      contacts: [
        { id: 6, customerId: 5, firstName: 'ณัฐพงศ์', lastName: 'ศรีวิไล', phone: '086-222-3333', email: 'nattapong@fashionisland.co.th' },
        { id: 7, customerId: 5, firstName: 'พิมพ์ใจ', lastName: 'บุญมาก', phone: '086-444-5555', email: 'pimjai@fashionisland.co.th' },
      ],
    });
    api.tickets.get.mockResolvedValue({
      ticket: {
        summary: {
          id: 18, createdById: 6, createdByName: 'คุณสมหมาย ขายดี',
          customerName: 'บริษัท แฟชั่นไอส์แลนด์ จำกัด', projectName: null,
          // ผู้สั่งซื้อ is REQUIRED since owner feedback F2 (2026-09-10) and prefills from the
          // deal's own contact, so a ticket fixture without these two fields would leave
          // บันทึกร่าง permanently disabled and every assertion below testing the wrong thing.
          customerId: 5, contactId: 6, contactName: 'ณัฐพงศ์ ศรีวิไล',
        },
      },
    });
  });

  it('a read-only viewer (import) visiting /quotations/new is refused the editor form', async () => {
    renderEditor('/quotations/new?ticket=18', importUser);
    await screen.findByRole('heading', { name: 'สร้างใบเสนอราคา' });

    expect(screen.queryByRole('button', { name: /เพิ่มรายการ/ })).toBeNull();
    expect(screen.getByText(/สิทธิ์ดูใบเสนอราคาเท่านั้น/)).not.toBeNull();
  });

  it('a sales rep who does NOT own the deal is also refused, not shown the editor', async () => {
    renderEditor('/quotations/new?ticket=18', otherSalesUser);
    await screen.findByRole('heading', { name: 'สร้างใบเสนอราคา' });

    expect(screen.queryByRole('button', { name: /เพิ่มรายการ/ })).toBeNull();
    expect(screen.getByText(/ไม่มีสิทธิ์สร้างใบเสนอราคา/)).not.toBeNull();
  });

  // Explicit regression test for the Opus re-check finding: a plain `sales` user on their OWN
  // deal must reach the editor. This is the exact case the ticketQuery envelope bug broke --
  // `ticket.createdById` read `undefined` off a fixture shaped `{ ticket: {...} }`, so
  // canCreateDealQuotation(user, ticket) was false even for the deal's own owner, and the old,
  // equally-wrong fixture shape hid it (see the beforeEach comment above). Failing this test
  // first (by reverting the `r.summary` fix alone) and watching it go red is how the fix was
  // confirmed to matter, not just the shape correction.
  it('the owning sales rep on their own deal can create -- sees the editor form', async () => {
    renderEditor('/quotations/new?ticket=18', salesUser);

    // findBy, not getBy after the heading: the heading renders before the ticket query
    // resolves (it doesn't depend on `ticket`), so asserting straight off it races the
    // canCreateDealQuotation(user, ticket) check, which DOES need the ticket loaded.
    expect(await screen.findByRole('button', { name: /เพิ่มรายการ/ })).not.toBeNull();
    // Header renders real ticket-derived values, not "-" -- proves `ticket.customerName` is no
    // longer reading off an envelope key (`r.ticket`) the backend never sends. It appears twice
    // (the page subtitle AND the ข้อมูลลูกค้าและผู้ขาย panel), so this asserts presence, not
    // singularity.
    expect(screen.getAllByText(/บริษัท แฟชั่นไอส์แลนด์ จำกัด/).length).toBeGreaterThan(0);
  });
});

// Owner ruling 2026-09-10: "ALL info about the tile has to be completed" -- every field
// validateQuotationItem requires (quotationMeta.js), filled by label. Shared by every test below
// that needs a genuinely complete row, so a change to what "complete" means only has one call
// site to update.
async function fillCompleteQuotationItem() {
  fireEvent.change(screen.getByLabelText(/^รุ่น \/ ค้นหาแคตตาล็อก/), { target: { value: 'Trilogy' } });
  fireEvent.change(screen.getByLabelText(/^สี/), { target: { value: 'Ivory' } });
  fireEvent.change(screen.getByLabelText(/^ผิว/), { target: { value: 'Lappato' } });
  fireEvent.change(screen.getByLabelText(/^ขนาด/), { target: { value: '60x120' } });
  fireEvent.change(screen.getByLabelText(/^ความหนา/), { target: { value: '10' } });
  fireEvent.change(screen.getByLabelText(/^ตร\.ม\./), { target: { value: '0.72' } });
  fireEvent.change(screen.getByLabelText(/^แผ่น/), { target: { value: '3' } });
  fireEvent.change(screen.getByLabelText(/^ราคา\/หน่วย/), { target: { value: '850' } });
  fireEvent.change(screen.getByLabelText(/^จำนวน/), { target: { value: '36' } });
}

describe('QuotationEditorPage client-side validation (#M4, item completeness owner ruling 2026-09-10)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Blocker fix: GET /api/tickets/{id} returns `{ ticket: TicketDto }`
    // (TicketDetailResponse / TicketResponses.java) and TicketDto is ITSELF an envelope
    // (`{summary, items, events, quotation, quotations}` -- TicketDto.java) -- two levels of
    // wrapping. This fixture used to hand-roll `{ ticket: {...flat fields...} }` (one level),
    // matching the OLD (buggy) `.then((r) => r.ticket)` unwrap -- both wrong in the same way, so
    // canCreateDealQuotation read real fields and every "owning sales rep" test passed for the
    // wrong reason (Opus re-check finding). This is the real two-level shape;
    // `.then((r) => r.ticket?.summary)` is what the page now reads off it.
    api.customers.contacts.mockResolvedValue({
      contacts: [
        { id: 6, customerId: 5, firstName: 'ณัฐพงศ์', lastName: 'ศรีวิไล', phone: '086-222-3333', email: 'nattapong@fashionisland.co.th' },
        { id: 7, customerId: 5, firstName: 'พิมพ์ใจ', lastName: 'บุญมาก', phone: '086-444-5555', email: 'pimjai@fashionisland.co.th' },
      ],
    });
    api.tickets.get.mockResolvedValue({
      ticket: {
        summary: {
          id: 18, createdById: 6, createdByName: 'คุณสมหมาย ขายดี',
          customerName: 'บริษัท แฟชั่นไอส์แลนด์ จำกัด', projectName: null,
          // ผู้สั่งซื้อ is REQUIRED since owner feedback F2 (2026-09-10) and prefills from the
          // deal's own contact, so a ticket fixture without these two fields would leave
          // บันทึกร่าง permanently disabled and every assertion below testing the wrong thing.
          customerId: 5, contactId: 6, contactName: 'ณัฐพงศ์ ศรีวิไล',
        },
      },
    });
  });

  it('disables บันทึกร่าง and lists a per-row "ขาด ..." summary until every item field is complete', async () => {
    renderEditor('/quotations/new?ticket=18');
    // findBy: waits out the ticket query so canCreateDealQuotation has what it needs to render
    // the editable form (and its บันทึกร่าง button) at all.
    await screen.findByRole('button', { name: 'บันทึกร่าง' });

    // 0 items yet. .disabled, not toBeDisabled -- this project does not wire up jest-dom's
    // matchers (see LoginPage.test.jsx's own note).
    //
    // V7 LOW (owner review 2026-09-10): the GATE is unchanged and still knows exactly why it is
    // closed -- it says so in the button's own title -- but the red checklist no longer greets an
    // untouched page. Asserted both ways round, because "the reason is still computed" and "the
    // reason is not shouted yet" are two different claims and only the pair rules out having
    // simply deleted the validation.
    expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(true);
    expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).title)
      .toContain('ต้องมีรายการสินค้าอย่างน้อย 1 รายการ');
    expect(screen.queryByText('ต้องมีรายการสินค้าอย่างน้อย 1 รายการ')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /เพิ่มรายการ/ }));
    // An item exists now, but every required field is still blank -- the per-row summary names
    // ALL of them (quotationMeta.test.js pins the exact wording; this only checks it renders).
    expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(true);
    expect(screen.getByText(/รายการที่ 1: ขาด/)).not.toBeNull();

    await fillCompleteQuotationItem();

    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));
    expect(screen.queryByText('ต้องมีรายการสินค้าอย่างน้อย 1 รายการ')).toBeNull();
    expect(screen.queryByText(/รายการที่ 1: ขาด/)).toBeNull();
  });

  // The owner's own point, not the old (now removed) "note stands in for a model" carve-out: a
  // written หมายเหตุรายการ is NOT a substitute for the required fields any more -- every one of
  // them has to be filled regardless of what else the row carries.
  it('a written item note no longer substitutes for the required fields', async () => {
    renderEditor('/quotations/new?ticket=18');
    await screen.findByRole('button', { name: /เพิ่มรายการ/ });

    fireEvent.click(screen.getByRole('button', { name: /เพิ่มรายการ/ }));
    fireEvent.change(screen.getByLabelText(/^ราคา\/หน่วย/), { target: { value: '100' } });
    fireEvent.change(screen.getByLabelText('หมายเหตุรายการ'), { target: { value: 'สินค้าตามตัวอย่างที่ลูกค้าส่งมา' } });

    expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(true);
    expect(screen.getByText(/รายการที่ 1: ขาด/)).not.toBeNull();
  });

  it('clearing ความหนา on an otherwise-complete row re-disables บันทึกร่าง and names it in the row summary; retyping it re-enables', async () => {
    renderEditor('/quotations/new?ticket=18');
    await screen.findByRole('button', { name: /เพิ่มรายการ/ });
    fireEvent.click(screen.getByRole('button', { name: /เพิ่มรายการ/ }));
    await fillCompleteQuotationItem();
    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));

    fireEvent.change(screen.getByLabelText(/^ความหนา/), { target: { value: '' } });

    expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(true);
    expect(screen.getByText('รายการที่ 1: ขาด ความหนา')).not.toBeNull();

    fireEvent.change(screen.getByLabelText(/^ความหนา/), { target: { value: '10' } });
    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));
  });

  // "not on first paint": a freshly-added blank row is missing almost everything, but nobody has
  // touched it yet -- the inline per-field red hint stays quiet (the top-of-form summary already
  // said the row is incomplete) until the rep actually edits that row, at which point its hints
  // switch on.
  it('a freshly-added blank row shows no inline field hints until the rep edits it', async () => {
    renderEditor('/quotations/new?ticket=18');
    await screen.findByRole('button', { name: /เพิ่มรายการ/ });
    fireEvent.click(screen.getByRole('button', { name: /เพิ่มรายการ/ }));

    // Untouched: the row-level summary above already says it's incomplete, but this specific
    // field-level hint has not switched on yet.
    expect(screen.queryByText('กรุณาระบุสี')).toBeNull();

    fireEvent.change(screen.getByLabelText(/^รุ่น \/ ค้นหาแคตตาล็อก/), { target: { value: 'Trilogy' } });

    // Touched now (any edit on the row) -- every still-missing field's hint is visible, สี
    // included even though สี itself was never the field edited.
    expect(screen.getByText('กรุณาระบุสี')).not.toBeNull();
  });
});

describe('QuotationEditorPage approval flow (#M5, #M8)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('อนุมัติ opens a confirm modal (number + grand total) instead of mutating immediately', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: baseQuotation({ docStatus: 'PENDING_APPROVAL', number: 'QT-2026-0005', grandTotal: 5350 }),
    });
    renderEditor('/quotations/5', ceoUser);
    await screen.findByRole('button', { name: 'อนุมัติ' });

    fireEvent.click(screen.getByRole('button', { name: 'อนุมัติ' }));
    expect(api.dealQuotations.approve).not.toHaveBeenCalled();
    // The modal names the quotation and its grand total -- scoped to the dialog since the
    // number also appears in the page's own <h1>.
    const dialog = within(screen.getByRole('dialog'));
    expect(dialog.getByText(/QT-2026-0005/)).not.toBeNull();
    expect(dialog.getByText(/5,350/)).not.toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันอนุมัติ' }));
    await waitFor(() => expect(api.dealQuotations.approve).toHaveBeenCalledWith('5', {}));
  });

  it('a 409 from approve refetches the quotation and surfaces the backend\'s own Thai message', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: baseQuotation({ docStatus: 'PENDING_APPROVAL', number: 'QT-2026-0005' }),
    });
    const conflict = new Error('อนุมัติไม่ได้ในสถานะ \'APPROVED\'');
    conflict.status = 409;
    api.dealQuotations.approve.mockRejectedValueOnce(conflict);
    const showToast = vi.fn();

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/quotations/5']}>
          <Routes>
            <Route path="/quotations/:id" element={<QuotationEditorPage user={ceoUser} showToast={showToast} />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await screen.findByRole('button', { name: 'อนุมัติ' });
    fireEvent.click(screen.getByRole('button', { name: 'อนุมัติ' }));
    fireEvent.click(screen.getByRole('button', { name: 'ยืนยันอนุมัติ' }));

    await waitFor(() => expect(api.dealQuotations.approve).toHaveBeenCalledTimes(1));
    // Refetched -- the SAME endpoint the initial load used, called again after the 409.
    await waitFor(() => expect(api.dealQuotations.get).toHaveBeenCalledTimes(2));
    expect(showToast).toHaveBeenCalledWith('error', 'อนุมัติไม่ได้ในสถานะ \'APPROVED\'');
  });

  // De-flaked (Opus re-check finding, "#M8 focus-refetch test"): this test used to (1) leave the
  // ceoUser tree MOUNTED while rendering a second, salesUser tree on top of it -- RTL's `render`
  // does not unmount a prior render mid-test, only `afterEach(cleanup)` between tests (see
  // src/test/setup.js) -- so the still-subscribed ceo query observer (refetchOnWindowFocus: true)
  // could ALSO react to the second focus toggle and increment the shared `api.dealQuotations.get`
  // mock's call count out from under the "no refetch for a non-approver" assertion; and (2) proved
  // that negative with a raw 100ms wall-clock sleep, which is exactly the kind of timing race
  // CLAUDE.md's "vitest reports FAKE failures under Maven contention" note warns about -- slower
  // CI/parallel runs get less real time per virtual millisecond, not more. Fixed by explicitly
  // unmounting the first tree before the second renders, and by replacing the real sleep with a
  // fake-timer advance: the assertion no longer depends on any wall-clock margin at all.
  it('refetchOnWindowFocus is wired for an approver but not for a non-approving viewer (#M8)', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: baseQuotation({ docStatus: 'PENDING_APPROVAL', number: 'QT-2026-0005' }),
    });
    const { queryClient, unmount } = renderEditor('/quotations/5', ceoUser);
    await waitFor(() => expect(api.dealQuotations.get).toHaveBeenCalledTimes(1));

    focusManager.setFocused(false);
    focusManager.setFocused(true);
    await waitFor(() => expect(api.dealQuotations.get).toHaveBeenCalledTimes(2));

    // Tear this tree down completely -- its query observer must stop reacting to focusManager
    // before the next scenario toggles focus again, or it double-counts into the shared mock.
    unmount();
    queryClient.clear();

    vi.clearAllMocks();
    api.dealQuotations.get.mockResolvedValue({
      quotation: baseQuotation({ docStatus: 'PENDING_APPROVAL', number: 'QT-2026-0005' }),
    });
    // salesUser owns this deal (salesRepId: 6 in baseQuotation) but is never an approver --
    // canApproveDealQuotation is role-only (sales_manager/ceo), so this must stay false for them
    // regardless of ownership.
    renderEditor('/quotations/5', salesUser);
    await waitFor(() => expect(api.dealQuotations.get).toHaveBeenCalledTimes(1));

    vi.useFakeTimers();
    try {
      focusManager.setFocused(false);
      focusManager.setFocused(true);
      // A virtual clock advance, not a real sleep -- deterministically drains any timer a
      // (wrongly) scheduled refetch would use, immune to how much real CPU time this process
      // actually gets under contention.
      await act(async () => { await vi.advanceTimersByTimeAsync(200); });
    } finally {
      vi.useRealTimers();
    }
    expect(api.dealQuotations.get).toHaveBeenCalledTimes(1); // no refetch-on-focus for a non-approver
  });
});

describe('QuotationEditorPage live provisional totals (#M6)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows a client-side estimate while dirty and the server total once clean', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: baseQuotation() }); // subtotal 1000 / vat 70 / grand 1070
    renderEditor('/quotations/5');

    expect(await screen.findByText('฿1,070.00')).not.toBeNull();
    expect(screen.queryByText(/ยอดโดยประมาณ/)).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /เพิ่มรายการ/ })); // now dirty
    expect(await screen.findByText(/ยอดโดยประมาณ \(ยังไม่บันทึก\)/)).not.toBeNull();
    expect(screen.queryByText('฿1,070.00')).toBeNull();

    api.dealQuotations.calculateLine.mockResolvedValue({ item: { lineAmount: 500, calculationLine: 'x' } });
    fireEvent.change(screen.getByLabelText(/^ราคา\/หน่วย/), { target: { value: '500' } });
    await waitFor(() => expect(api.dealQuotations.calculateLine).toHaveBeenCalled(), { timeout: 1000 });

    // 500 subtotal -> 35 VAT -> 535 grand, computed client-side from the one item's lineAmount.
    expect(await screen.findByText('฿535.00')).not.toBeNull();
  });
});

// Owner ask 2026-09-10 ("inline deal creation", inline-deal-spec.md): /quotations/new with NO
// ?ticket= renders DealCustomerCard instead of the read-only ticket-derived summary, and the
// first บันทึกร่าง has to mint the ticket itself (api.tickets.create) before it can create the
// quotation on top of it (api.dealQuotations.create).
describe('QuotationEditorPage inline deal creation', () => {
  const testCustomer = { id: 1, name: 'บริษัท ก้าวหน้า คอนสตรัคชั่น จำกัด', taxId: '0105565012345' };
  const testProject = { id: 1, customerId: 1, name: 'โครงการ Central Ladprao ชั้น B1' };

  // Renders the real editor at /quotations/new with no ticket query param, plus a stand-in for
  // /quotations/:id that just names the id it landed on -- avoids needing api.dealQuotations.get
  // to resolve just to observe that navigate() fired to the right place.
  function IdProbe() {
    const { id } = useParams();
    return <p>navigated-to-{id}</p>;
  }
  function renderInlineCreate(user = salesUser) {
    const showToast = vi.fn();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    const result = render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/quotations/new']}>
          <Routes>
            <Route path="/quotations/new" element={<QuotationEditorPage user={user} showToast={showToast} />} />
            <Route path="/quotations/:id" element={<IdProbe />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    return { ...result, showToast, queryClient };
  }

  const testContact = { id: 3, customerId: 1, firstName: 'ปรีชา', lastName: 'วงศ์สกุล', phone: '083-555-6666', email: 'preecha@tld.co.th' };

  async function selectCustomerAndProject() {
    api.customers.search.mockResolvedValue({ customers: [testCustomer] });
    api.customers.projects.mockResolvedValue({ projects: [testProject] });
    api.customers.contacts.mockResolvedValue({ contacts: [testContact] });

    fireEvent.change(await screen.findByLabelText(/^ลูกค้า/), { target: { value: 'ก้าวหน้า' } });
    // role="option" since V4 (2026-09-10) — the typeahead popup is a listbox.
    fireEvent.mouseDown(await screen.findByRole('option', { name: new RegExp(testCustomer.name) }));

    await waitFor(() => expect(screen.getAllByText(testCustomer.name).length).toBeGreaterThan(0)); // the chip that replaces the search input
    await waitFor(() => expect(api.customers.projects).toHaveBeenCalledWith(testCustomer.id));

    const projectSelect = await screen.findByLabelText(/^โครงการ/);
    fireEvent.change(projectSelect, { target: { value: String(testProject.id) } });
  }

  // #M4 (owner ruling 2026-09-10): "ALL info about the tile has to be completed" -- reuses the
  // SAME complete-item helper every other editor visit's tests fill with, so this describe block
  // isn't gating บันทึกร่าง on a looser bar than the rest of the suite.
  async function fillOneValidItem() {
    fireEvent.click(screen.getByRole('button', { name: /เพิ่มรายการ/ }));
    await fillCompleteQuotationItem();
  }

  // ผู้สั่งซื้อ, REQUIRED since owner feedback F2 (2026-09-10). Separate from
  // selectCustomerAndProject so the gating test can observe the state BETWEEN the two.
  async function selectContact() {
    fireEvent.change(await screen.findByLabelText(/^ผู้สั่งซื้อ/), { target: { value: String(testContact.id) } });
  }

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('gates บันทึกร่าง on ลูกค้า + โครงการ, with Thai inline hints, in addition to the existing item checks', async () => {
    renderInlineCreate();

    await screen.findByLabelText(/^ลูกค้า/); // card rendered instead of the read-only ticket summary
    // Same V7 split as the item-completeness test above: gated from the first paint, but a
    // pristine /quotations/new does not open with ลูกค้า and โครงการ already flagged red.
    expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(true);
    expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).title)
      .toContain('ต้องเลือกลูกค้าก่อนบันทึกร่าง');
    expect(screen.queryByText('ต้องเลือกลูกค้าก่อนบันทึกร่าง')).toBeNull();
    expect(screen.queryByText('ต้องเลือกโครงการก่อนบันทึกร่าง')).toBeNull();

    await selectCustomerAndProject();
    expect(screen.queryByText('ต้องเลือกลูกค้าก่อนบันทึกร่าง')).toBeNull();
    expect(screen.queryByText('ต้องเลือกโครงการก่อนบันทึกร่าง')).toBeNull();
    // Customer+project are satisfied now, but there is still no item -- บันทึกร่าง stays gated
    // on the SAME item-completeness checks every other editor visit already enforces (#M4).
    expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(true);
    expect(screen.getByText('ต้องมีรายการสินค้าอย่างน้อย 1 รายการ')).not.toBeNull();

    // F2: ผู้สั่งซื้อ is its own gate on top of ลูกค้า/โครงการ and item completeness.
    await fillOneValidItem();
    expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(true);
    expect(screen.getAllByText('กรุณาระบุผู้สั่งซื้อ').length).toBeGreaterThan(0);

    await selectContact();
    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));
  });

  it('บันทึกร่าง creates the ticket, then the quotation on it, then navigates to /quotations/{id}', async () => {
    api.tickets.create.mockResolvedValue({
      ticket: { summary: { id: 42, createdById: 6, customerName: testCustomer.name, projectName: testProject.name } },
    });
    api.dealQuotations.create.mockResolvedValue({ quotation: { id: 99 } });

    renderInlineCreate();
    await selectCustomerAndProject();
    await selectContact();
    await fillOneValidItem();
    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));

    const expectedFollowUp = addDaysIso(bangkokTodayIso(), 7);
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(api.tickets.create).toHaveBeenCalledTimes(1));
    expect(api.tickets.create).toHaveBeenCalledWith(expect.objectContaining({
      title: testCustomer.name,
      customerName: testCustomer.name,
      customerId: testCustomer.id,
      projectId: testProject.id,
      contactId: testContact.id,
      entryChannel: 'UNSPECIFIED',
      priority: 'NORMAL',
      items: [],
      nextFollowUpAt: expectedFollowUp,
    }));

    // The ticket envelope's id lives at `.summary.id` -- the SAME unwrap this test suite's own
    // #M2 fixtures were fixed to use above, not `.ticket.id`.
    await waitFor(() => expect(api.dealQuotations.create).toHaveBeenCalledWith(42, expect.objectContaining({
      items: expect.arrayContaining([expect.objectContaining({ unitPrice: 850, model: 'Trilogy' })]),
    })));

    expect(await screen.findByText('navigated-to-99')).not.toBeNull();
  });

  it('a tickets.create failure leaves the form intact and toasts the backend\'s Thai message, without ever calling dealQuotations.create', async () => {
    const backendError = new Error('สร้างดีลไม่สำเร็จ: ชื่อลูกค้าซ้ำในระบบ');
    api.tickets.create.mockRejectedValueOnce(backendError);

    const { showToast } = renderInlineCreate();
    await selectCustomerAndProject();
    await selectContact();
    await fillOneValidItem();
    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));

    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', backendError.message));
    expect(api.dealQuotations.create).not.toHaveBeenCalled();
    // The form is untouched -- the selected customer chip and the item's typed values are still
    // exactly what the rep entered, so they don't have to redo any of it before retrying.
    expect(screen.getAllByText(testCustomer.name).length).toBeGreaterThan(0);
    expect(screen.getByLabelText(/^ราคา\/หน่วย/).value).toBe('850');
    expect(screen.getByLabelText(/^รุ่น \/ ค้นหาแคตตาล็อก/).value).toBe('Trilogy');
  });

  it('a dealQuotations.create failure after tickets.create succeeded retries WITHOUT creating a second ticket', async () => {
    api.tickets.create.mockResolvedValue({ ticket: { summary: { id: 42 } } });
    api.dealQuotations.create.mockRejectedValueOnce(new Error('บันทึกไม่สำเร็จ (ทดสอบ)'));

    const { showToast } = renderInlineCreate();
    await selectCustomerAndProject();
    await selectContact();
    await fillOneValidItem();
    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));

    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
    await waitFor(() => expect(api.dealQuotations.create).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', 'บันทึกไม่สำเร็จ (ทดสอบ)'));
    expect(api.tickets.create).toHaveBeenCalledTimes(1);

    // Retry: the SAME ticket id is reused (createdTicketId state), so this must NOT call
    // tickets.create a second time -- only dealQuotations.create, again, on ticket 42.
    api.dealQuotations.create.mockResolvedValueOnce({ quotation: { id: 99 } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(api.dealQuotations.create).toHaveBeenCalledTimes(2));
    expect(api.dealQuotations.create).toHaveBeenNthCalledWith(2, 42, expect.anything());
    expect(api.tickets.create).toHaveBeenCalledTimes(1); // still just the one ticket
    expect(await screen.findByText('navigated-to-99')).not.toBeNull();
  });

  // "?ticket= present" is the OLD behaviour, unchanged (inline-deal-spec.md: "as today") -- the
  // card renders the deal read-only instead of DealCustomerCard, now with a link back to it.
  it('with ?ticket= present, the deal renders read-only with a link to /tickets/{id} -- DealCustomerCard does not render', async () => {
    api.customers.contacts.mockResolvedValue({
      contacts: [
        { id: 6, customerId: 5, firstName: 'ณัฐพงศ์', lastName: 'ศรีวิไล', phone: '086-222-3333', email: 'nattapong@fashionisland.co.th' },
        { id: 7, customerId: 5, firstName: 'พิมพ์ใจ', lastName: 'บุญมาก', phone: '086-444-5555', email: 'pimjai@fashionisland.co.th' },
      ],
    });
    api.tickets.get.mockResolvedValue({
      ticket: {
        summary: {
          id: 18, createdById: 6, createdByName: 'คุณสมหมาย ขายดี',
          customerName: 'บริษัท แฟชั่นไอส์แลนด์ จำกัด', projectName: null,
          // ผู้สั่งซื้อ is REQUIRED since owner feedback F2 (2026-09-10) and prefills from the
          // deal's own contact, so a ticket fixture without these two fields would leave
          // บันทึกร่าง permanently disabled and every assertion below testing the wrong thing.
          customerId: 5, contactId: 6, contactName: 'ณัฐพงศ์ ศรีวิไล',
        },
      },
    });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/quotations/new?ticket=18']}>
          <Routes>
            <Route path="/quotations/new" element={<QuotationEditorPage user={salesUser} showToast={vi.fn()} />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    await screen.findByRole('button', { name: /เพิ่มรายการ/ });
    expect(screen.queryByLabelText(/^ลูกค้า/)).toBeNull(); // DealCustomerCard's own field, not this mode's
    const link = screen.getByRole('link', { name: 'ดูรายละเอียดดีลนี้' });
    expect(link.getAttribute('href')).toBe('/tickets/18');
  });
});

// ─────────────────────────────────────────────────────────────────────────────────────────────
// Owner feedback pass 1, 2026-09-10 — F1 (ตำแหน่งติดตั้ง groups), F2 (ผู้สั่งซื้อ), and the
// "as little typing as possible" conveniences (remembered terms, ใช้ล่าสุด, autosave).
// ─────────────────────────────────────────────────────────────────────────────────────────────

const CONTACT_OPTIONS = [
  { id: 6, customerId: 5, firstName: 'ณัฐพงศ์', lastName: 'ศรีวิไล', phone: '086-222-3333', email: 'nattapong@fashionisland.co.th' },
  { id: 7, customerId: 5, firstName: 'พิมพ์ใจ', lastName: 'บุญมาก', phone: '086-444-5555', email: 'pimjai@fashionisland.co.th' },
];

function ticketFixture(overrides = {}) {
  return {
    ticket: {
      summary: {
        id: 18, createdById: 6, createdByName: 'คุณสมหมาย ขายดี',
        customerName: 'บริษัท แฟชั่นไอส์แลนด์ จำกัด', projectName: null,
        customerId: 5, contactId: 6, contactName: 'ณัฐพงศ์ ศรีวิไล',
        ...overrides,
      },
    },
  };
}

// A saved DRAFT whose items are already grouped by ตำแหน่งติดตั้ง: TWO items under "ชั้น 1", then
// one under "ชั้น 2". Deliberately non-adjacent labels are covered by the "two runs of the same
// label" case in its own test below.
function groupedItem(seq, locationLabel, model) {
  return {
    id: 100 + seq, seq, locationLabel, model, color: 'Ivory', texture: 'Lappato', sizeText: '60x120',
    thicknessMm: 10, sqmPerPiece: 0.72, piecesPerBox: 3, quantityMode: 'AREA', areaSqm: 36,
    piecesInput: null, wastageMode: 'PERCENT', wastageValue: 0, unitPrice: 850, discountPct: 0,
    originCountry: '', leadTimeMinDays: null, leadTimeMaxDays: null, itemNotes: '',
    catalogPriceId: null, productCode: null, brand: null,
    piecesFinal: 51, boxes: 17, netUnitPrice: 850, lineAmount: 43350,
    descriptionLine: `กระเบื้อง รุ่น ${model}`, sizeLine: '', calculationLine: '',
  };
}

describe('QuotationEditorPage ตำแหน่งติดตั้ง groups (owner feedback F1, 2026-09-10)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.customers.contacts.mockResolvedValue({ contacts: CONTACT_OPTIONS });
    api.tickets.get.mockResolvedValue(ticketFixture());
    api.dealQuotations.calculateLine.mockResolvedValue({ item: {} });
  });

  function draftWithItems(items) {
    api.dealQuotations.get.mockResolvedValue({
      quotation: baseQuotation({ items, contactId: 6, contactName: 'ณัฐพงศ์ ศรีวิไล' }),
    });
  }

  it('rebuilds one group per RUN of equal locationLabel on load, with the items nested inside', async () => {
    draftWithItems([
      groupedItem(0, 'ชั้น 1', 'Trilogy'),
      groupedItem(1, 'ชั้น 1', 'Frame'),
      groupedItem(2, 'ชั้น 2', 'Balneo'),
    ]);
    renderEditor('/quotations/5');

    // Two groups, labelled and numbered in document order — not three, and not one per item.
    // waitFor, not a bare find: the group input exists from first paint (one empty group is the
    // initial state) and only takes its label once the seeding effect has run.
    await waitFor(() => expect(screen.getByLabelText(/^ตำแหน่งติดตั้งที่ 1/).value).toBe('ชั้น 1'));
    expect(screen.getByLabelText(/^ตำแหน่งติดตั้งที่ 2/).value).toBe('ชั้น 2');
    expect(screen.queryByLabelText(/^ตำแหน่งติดตั้งที่ 3/)).toBeNull();
    expect(screen.getByText('2 รายการ')).not.toBeNull();
    expect(screen.getByText('1 รายการ')).not.toBeNull();
  });

  // Two SEPARATE runs of the same label are two groups, because that is what the document prints.
  // Collecting all "ชั้น 1" rows together would reorder the items the rep deliberately arranged.
  it('does not merge two non-adjacent runs of the same label into one group', async () => {
    draftWithItems([
      groupedItem(0, 'ชั้น 1', 'Trilogy'),
      groupedItem(1, 'ชั้น 2', 'Frame'),
      groupedItem(2, 'ชั้น 1', 'Balneo'),
    ]);
    renderEditor('/quotations/5');

    await waitFor(() => expect(screen.getByLabelText(/^ตำแหน่งติดตั้งที่ 3/).value).toBe('ชั้น 1'));
    expect(screen.getAllByText('1 รายการ')).toHaveLength(3);
  });

  it('saves the flat items array in group order, stamping each row with ITS group label', async () => {
    draftWithItems([groupedItem(0, 'ชั้น 1', 'Trilogy'), groupedItem(1, 'ชั้น 2', 'Frame')]);
    api.dealQuotations.update.mockResolvedValue({ quotation: baseQuotation({ contactId: 6 }) });
    renderEditor('/quotations/5');

    // Rename group 2 — every row inside it must follow, with no per-row field to edit.
    await waitFor(() => expect(screen.getByLabelText(/^ตำแหน่งติดตั้งที่ 2/).value).toBe('ชั้น 2'));
    fireEvent.change(screen.getByLabelText(/^ตำแหน่งติดตั้งที่ 2/), { target: { value: 'ชั้น 2 - โซน B' } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalledTimes(1));
    const payload = api.dealQuotations.update.mock.calls[0][1];
    expect(payload.items.map((it) => [it.model, it.locationLabel])).toEqual([
      ['Trilogy', 'ชั้น 1'],
      ['Frame', 'ชั้น 2 - โซน B'],
    ]);
  });

  it('sends locationLabel null for a blank group so the document prints no heading', async () => {
    draftWithItems([groupedItem(0, null, 'Trilogy')]);
    api.dealQuotations.update.mockResolvedValue({ quotation: baseQuotation({ contactId: 6 }) });
    renderEditor('/quotations/5');

    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalledTimes(1));
    expect(api.dealQuotations.update.mock.calls[0][1].items).toHaveLength(1);
    expect(api.dealQuotations.update.mock.calls[0][1].items[0].locationLabel).toBeNull();
  });

  it('"เพิ่มตำแหน่ง" adds an empty group and "เพิ่มรายการในตำแหน่งนี้" adds into THAT group', async () => {
    draftWithItems([groupedItem(0, 'ชั้น 1', 'Trilogy')]);
    renderEditor('/quotations/5');

    await waitFor(() => expect(screen.getByLabelText(/^ตำแหน่งติดตั้งที่ 1/).value).toBe('ชั้น 1'));
    fireEvent.click(screen.getByRole('button', { name: /เพิ่มตำแหน่ง/ }));
    fireEvent.change(screen.getByLabelText(/^ตำแหน่งติดตั้งที่ 2/), { target: { value: 'ชั้น 2' } });
    // A REGEX, not the bare string: getByText's default string matcher is a full-text equality
    // check, and the warning continues "— ตำแหน่งที่ไม่มีรายการจะไม่ถูกบันทึกและไม่ปรากฏในเอกสาร"
    // (MED-4 spells out the consequence). Anchored at the start so it still pins the sentence.
    expect(screen.getByText(/^ยังไม่มีรายการในตำแหน่งนี้/)).not.toBeNull();

    // The SECOND group's own add button.
    const addButtons = screen.getAllByRole('button', { name: /เพิ่มรายการในตำแหน่งนี้/ });
    fireEvent.click(addButtons[1]);

    await waitFor(() => expect(screen.queryByText(/^ยังไม่มีรายการในตำแหน่งนี้/)).toBeNull());
    expect(screen.getAllByText('1 รายการ')).toHaveLength(2);
  });

  it('moves an item into another group and saves it under that group\'s label, in group order', async () => {
    draftWithItems([groupedItem(0, 'ชั้น 1', 'Trilogy'), groupedItem(1, 'ชั้น 2', 'Frame')]);
    api.dealQuotations.update.mockResolvedValue({ quotation: baseQuotation({ contactId: 6 }) });
    renderEditor('/quotations/5');

    // Item 1 (Trilogy, in ชั้น 1) -> the ชั้น 2 group.
    await waitFor(() => expect(screen.getAllByLabelText('ย้ายไปตำแหน่ง')).toHaveLength(2));
    const moveSelect = screen.getAllByLabelText('ย้ายไปตำแหน่ง')[0];
    const target = [...moveSelect.options].find((o) => o.textContent === 'ชั้น 2');
    fireEvent.change(moveSelect, { target: { value: target.value } });

    await waitFor(() => expect(screen.getByText('0 รายการ')).not.toBeNull());
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalledTimes(1));
    // Frame was already in ชั้น 2 and stays first; the moved Trilogy lands at that group's end.
    expect(api.dealQuotations.update.mock.calls[0][1].items.map((it) => [it.model, it.locationLabel])).toEqual([
      ['Frame', 'ชั้น 2'],
      ['Trilogy', 'ชั้น 2'],
    ]);
  });

  it('duplicates an item into another group, leaving the original where it was', async () => {
    draftWithItems([groupedItem(0, 'ชั้น 1', 'Trilogy'), groupedItem(1, 'ชั้น 2', 'Frame')]);
    api.dealQuotations.update.mockResolvedValue({ quotation: baseQuotation({ contactId: 6 }) });
    renderEditor('/quotations/5');

    await waitFor(() => expect(screen.getAllByLabelText('ทำซ้ำรายการ')).toHaveLength(2));
    const dupSelect = screen.getAllByLabelText('ทำซ้ำรายการ')[0];
    const target = [...dupSelect.options].find((o) => o.textContent === 'ไปยัง ชั้น 2');
    fireEvent.change(dupSelect, { target: { value: target.value } });

    await waitFor(() => expect(screen.getAllByText('1 รายการ')).toHaveLength(1));
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalledTimes(1));
    const saved = api.dealQuotations.update.mock.calls[0][1].items;
    expect(saved.map((it) => [it.model, it.locationLabel])).toEqual([
      ['Trilogy', 'ชั้น 1'],
      ['Frame', 'ชั้น 2'],
      ['Trilogy', 'ชั้น 2'],
    ]);
  });

  it('duplicating within the same group lands the copy directly after the original', async () => {
    draftWithItems([groupedItem(0, 'ชั้น 1', 'Trilogy'), groupedItem(1, 'ชั้น 1', 'Frame')]);
    api.dealQuotations.update.mockResolvedValue({ quotation: baseQuotation({ contactId: 6 }) });
    renderEditor('/quotations/5');

    // One group only, so ทำซ้ำรายการ is a plain button.
    await waitFor(() => expect(screen.getAllByRole('button', { name: 'ทำซ้ำรายการ' })).toHaveLength(2));
    fireEvent.click(screen.getAllByRole('button', { name: 'ทำซ้ำรายการ' })[0]);

    await waitFor(() => expect(screen.getByText('3 รายการ')).not.toBeNull());
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalledTimes(1));
    expect(api.dealQuotations.update.mock.calls[0][1].items.map((it) => it.model))
      .toEqual(['Trilogy', 'Trilogy', 'Frame']);
  });
});

describe('QuotationEditorPage ผู้สั่งซื้อ (owner feedback F2, 2026-09-10)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.customers.contacts.mockResolvedValue({ contacts: CONTACT_OPTIONS });
    api.dealQuotations.calculateLine.mockResolvedValue({ item: {} });
  });

  it('prefills from the deal\'s contact on the ?ticket= path and sends contactId on create', async () => {
    api.tickets.get.mockResolvedValue(ticketFixture());
    api.dealQuotations.create.mockResolvedValue({ quotation: { id: 9 } });
    renderEditor('/quotations/new?ticket=18');

    const picker = await screen.findByLabelText(/^ผู้สั่งซื้อ/);
    await waitFor(() => expect(picker.value).toBe('6'));

    fireEvent.click(screen.getByRole('button', { name: /เพิ่มรายการในตำแหน่งนี้/ }));
    await fillCompleteQuotationItem();
    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(api.dealQuotations.create).toHaveBeenCalledTimes(1));
    expect(api.dealQuotations.create.mock.calls[0][1].contactId).toBe(6);
  });

  it('is changeable, and the chosen contact is what gets sent', async () => {
    api.tickets.get.mockResolvedValue(ticketFixture());
    api.dealQuotations.create.mockResolvedValue({ quotation: { id: 9 } });
    renderEditor('/quotations/new?ticket=18');

    const picker = await screen.findByLabelText(/^ผู้สั่งซื้อ/);
    await waitFor(() => expect(picker.value).toBe('6'));
    fireEvent.change(picker, { target: { value: '7' } });

    fireEvent.click(screen.getByRole('button', { name: /เพิ่มรายการในตำแหน่งนี้/ }));
    await fillCompleteQuotationItem();
    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(api.dealQuotations.create).toHaveBeenCalledTimes(1));
    expect(api.dealQuotations.create.mock.calls[0][1].contactId).toBe(7);
  });

  // The gate itself, wrong-way-round: a deal with NO contact must not be saveable, and must say
  // why in the same Thai the backend uses.
  it('blocks บันทึกร่าง with "กรุณาระบุผู้สั่งซื้อ" when the deal has no contact', async () => {
    api.tickets.get.mockResolvedValue(ticketFixture({ contactId: null, contactName: null }));
    renderEditor('/quotations/new?ticket=18');

    fireEvent.click(await screen.findByRole('button', { name: /เพิ่มรายการในตำแหน่งนี้/ }));
    await fillCompleteQuotationItem();

    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(true));
    expect(screen.getAllByText('กรุณาระบุผู้สั่งซื้อ').length).toBeGreaterThan(0);
    expect(api.dealQuotations.create).not.toHaveBeenCalled();
  });

  // A saved quotation carries only contactId/contactName (no customerId), so the picker must show
  // the frozen snapshot rather than falling back to a blank "nobody chosen".
  it('shows an existing draft\'s frozen contact snapshot even before the option list loads', async () => {
    api.tickets.get.mockResolvedValue(ticketFixture());
    api.customers.contacts.mockReturnValue(new Promise(() => {})); // never resolves
    api.dealQuotations.get.mockResolvedValue({
      quotation: baseQuotation({ contactId: 7, contactName: 'พิมพ์ใจ บุญมาก' }),
    });
    renderEditor('/quotations/5');

    const picker = await screen.findByLabelText(/^ผู้สั่งซื้อ/);
    await waitFor(() => expect(picker.value).toBe('7'));
    expect(screen.getByRole('option', { name: 'พิมพ์ใจ บุญมาก' })).not.toBeNull();
  });
});

// This repo's Node ships a BROKEN built-in global `localStorage` (it needs --localstorage-file,
// and it shadows jsdom's) -- `clear` is not even a function on it. See TicketCreateModal.test.jsx's
// own note. So these tests stub the whole global rather than clearing it: an in-memory Storage for
// the happy path, and a THROWING one for the private-window path, which is the case quotationPrefs
// exists to survive.
function stubLocalStorage(impl) {
  vi.stubGlobal('localStorage', impl);
}

function memoryStorage() {
  const map = new Map();
  return {
    getItem: (k) => (map.has(k) ? map.get(k) : null),
    setItem: (k, v) => { map.set(k, String(v)); },
    removeItem: (k) => { map.delete(k); },
    clear: () => map.clear(),
  };
}

function throwingStorage() {
  const boom = () => { throw new DOMException('The operation is insecure.', 'SecurityError'); };
  return { getItem: boom, setItem: boom, removeItem: boom, clear: boom };
}

describe('QuotationEditorPage sales conveniences (owner ask 2026-09-10)', () => {
  afterEach(() => vi.unstubAllGlobals());

  beforeEach(() => {
    vi.clearAllMocks();
    stubLocalStorage(memoryStorage());
    api.customers.contacts.mockResolvedValue({ contacts: CONTACT_OPTIONS });
    api.tickets.get.mockResolvedValue(ticketFixture());
    api.dealQuotations.calculateLine.mockResolvedValue({ item: {} });
  });

  it('remembers the rep\'s terms after a save and seeds the NEXT new quotation with them', async () => {
    api.dealQuotations.create.mockResolvedValue({ quotation: { id: 9 } });
    const first = renderEditor('/quotations/new?ticket=18');

    fireEvent.click(await screen.findByRole('button', { name: /เพิ่มรายการในตำแหน่งนี้/ }));
    await fillCompleteQuotationItem();
    fireEvent.click(screen.getByRole('button', { name: '50%' }));
    fireEvent.click(screen.getByRole('button', { name: 'ชำระเมื่อส่งมอบ' }));
    fireEvent.change(screen.getByLabelText(/^ยืนราคา/), { target: { value: '45' } });

    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
    await waitFor(() => expect(api.dealQuotations.create).toHaveBeenCalledTimes(1));
    first.unmount();

    renderEditor('/quotations/new?ticket=18');

    await screen.findByRole('button', { name: /เพิ่มรายการในตำแหน่งนี้/ });
    expect(screen.getByRole('button', { name: '50%' }).getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByRole('button', { name: 'ชำระเมื่อส่งมอบ' }).getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByLabelText(/^ยืนราคา/)).toHaveProperty('value', '45');
  });

  // The whole prefs layer must degrade silently: the ACCESSOR itself throws in a private window,
  // so a bare read would take the editor down rather than starting from the app defaults.
  it('renders normally when localStorage throws on every access', async () => {
    stubLocalStorage(throwingStorage());
    api.dealQuotations.create.mockResolvedValue({ quotation: { id: 9 } });

    renderEditor('/quotations/new?ticket=18');

    // Terms fall back to the app defaults (nothing pressed), not to a crash — asserted BEFORE the
    // save, because a successful create navigates to /quotations/{id} and this form unmounts.
    fireEvent.click(await screen.findByRole('button', { name: /เพิ่มรายการในตำแหน่งนี้/ }));
    expect(screen.getByRole('button', { name: '50%' }).getAttribute('aria-pressed')).toBe('false');
    expect(screen.getByRole('button', { name: '30%' }).getAttribute('aria-pressed')).toBe('false');
    // And the ใช้ล่าสุด row simply does not exist rather than half-rendering.
    expect(screen.queryByText('ใช้ล่าสุด')).toBeNull();

    await fillCompleteQuotationItem();
    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));

    // The save itself (which WRITES the defaults) must not throw either.
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
    await waitFor(() => expect(api.dealQuotations.create).toHaveBeenCalledTimes(1));
  });

  it('records a catalog pick and offers it as a ใช้ล่าสุด chip on the next blank row', async () => {
    api.catalog.prices.mockResolvedValue({
      items: [{
        priceId: 42, factoryName: 'Panaria SpA', productCode: 'PAN-T600-IVO', collection: 'Trilogy',
        color: 'Ivory', surface: 'Lappato', sizeRaw: '60x120', thicknessMm: 10, sqmPerPiece: 0.72,
        pcsPerBox: 3, originCountryCode: 'IT',
      }],
    });
    renderEditor('/quotations/new?ticket=18');

    fireEvent.click(await screen.findByRole('button', { name: /เพิ่มรายการในตำแหน่งนี้/ }));
    fireEvent.change(screen.getByLabelText(/^รุ่น \/ ค้นหาแคตตาล็อก/), { target: { value: 'Trilogy' } });
    // The catalog typeahead result is an OPTION (V4); the ใช้ล่าสุด chip asserted just below
    // is a plain button, which is exactly the distinction this pair now pins.
    fireEvent.mouseDown(await waitFor(() => screen.getByRole('option', { name: /Panaria SpA/ }), { timeout: 1000 }));

    // A second, still-blank row now offers that product as a one-click chip.
    fireEvent.click(screen.getByRole('button', { name: /เพิ่มรายการในตำแหน่งนี้/ }));
    expect(await screen.findByText('ใช้ล่าสุด')).not.toBeNull();
    expect(screen.getByRole('button', { name: 'Trilogy' })).not.toBeNull();
  });

  it('autosaves ~2s after the last edit once the quotation has an id, and says so', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: baseQuotation({ contactId: 6, contactName: 'ณัฐพงศ์ ศรีวิไล', items: [groupedItem(0, 'ชั้น 1', 'Trilogy')] }),
    });
    api.dealQuotations.update.mockResolvedValue({ quotation: baseQuotation({ contactId: 6 }) });
    renderEditor('/quotations/5');

    await waitFor(() => expect(screen.getByLabelText(/^ตำแหน่งติดตั้งที่ 1/).value).toBe('ชั้น 1'));
    // Precondition, stated out loud: autosave only ever runs on a form that would SAVE. If this
    // is disabled the test below would pass for the wrong reason (nothing fired because nothing
    // could fire), which is the vacuous shape this repo keeps getting bitten by.
    expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false);
    fireEvent.change(screen.getByLabelText(/^ตำแหน่งติดตั้งที่ 1/), { target: { value: 'ชั้น 1 - โซน A' } });

    // Not immediately: the whole point of the debounce.
    expect(api.dealQuotations.update).not.toHaveBeenCalled();

    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalledTimes(1), { timeout: 4000 });
    expect(api.dealQuotations.update.mock.calls[0][1].items[0].locationLabel).toBe('ชั้น 1 - โซน A');
    expect(await screen.findByText(/^บันทึกอัตโนมัติแล้ว \d{2}:\d{2}$/)).not.toBeNull();
  }, 10000);

  it('never autosaves a brand-new quotation that has no id yet', async () => {
    renderEditor('/quotations/new?ticket=18');

    fireEvent.click(await screen.findByRole('button', { name: /เพิ่มรายการในตำแหน่งนี้/ }));
    await fillCompleteQuotationItem();

    await new Promise((resolve) => { setTimeout(resolve, 2600); });
    expect(api.dealQuotations.create).not.toHaveBeenCalled();
    expect(api.tickets.create).not.toHaveBeenCalled();
  }, 10000);

  it('never autosaves while the form is still incomplete', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: baseQuotation({ contactId: 6, contactName: 'ณัฐพงศ์ ศรีวิไล', items: [] }),
    });
    renderEditor('/quotations/5');

    // An item with nothing filled in — validateQuotationItem rejects it, so the server would too.
    fireEvent.click(await screen.findByRole('button', { name: /เพิ่มรายการในตำแหน่งนี้/ }));

    await new Promise((resolve) => { setTimeout(resolve, 2600); });
    expect(api.dealQuotations.update).not.toHaveBeenCalled();
  }, 10000);
});
