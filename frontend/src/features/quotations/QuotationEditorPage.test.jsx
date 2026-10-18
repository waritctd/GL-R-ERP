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
      // One pricing route per deal (owner ruling 2026-09-30): a NEW quotation on a picked deal reads
      // the deal's pricing requests. None here — every test in this file is about the editor itself.
      pricingRequests: { listForTicket: vi.fn().mockResolvedValue({ items: [] }) },
      dealQuotations: {
        get: vi.fn(),
        create: vi.fn(),
        update: vi.fn(),
        calculateLine: vi.fn(),
        submit: vi.fn(),
        approve: vi.fn(),
        reject: vi.fn(),
        createRevision: vi.fn(), createReorder: vi.fn(),
        cancel: vi.fn(),
        downloadPdf: vi.fn(),
        downloadXlsx: vi.fn(),
      },
      catalog: { prices: vi.fn() },
      // Consumed by DesignerPicker.jsx (owner ask 2026-09-12, "D.Co. auto-fill from a
      // ผู้ออกแบบ pick") -- getByCode default-resolves to undefined so the picker's own
      // resolved-name hint effect just finds nothing rather than throwing when a test
      // never sets a unitCode.
      designers: { search: vi.fn(), getByCode: vi.fn().mockRejectedValue(new Error('not found')) },
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
          id: 18, createdById: 6, createdByName: 'คุณสมหมาย ขายดี', salesStage: 'QUOTE_DESIGN_SIDE',
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
    // A Thai tile price is typed per ตร.ม. (sales feedback #1, 2026-10-06), and a per-ตร.ม. price
    // is only previewed on a row with an area per piece — so that goes in first.
    fireEvent.change(screen.getByLabelText(/^แผ่น\/ตร\.ม\./), { target: { value: '1.39' } });
    const priceInput = screen.getByLabelText(/^ราคา\/ตร\.ม\./);
    fireEvent.change(priceInput, { target: { value: '350' } });

    // Not called synchronously -- the whole point of the debounce.
    expect(api.dealQuotations.calculateLine).not.toHaveBeenCalled();

    await waitFor(() => expect(api.dealQuotations.calculateLine).toHaveBeenCalledTimes(1), { timeout: 1000 });
    expect(api.dealQuotations.calculateLine).toHaveBeenCalledWith(expect.objectContaining({ listPriceSqm: 350 }), 'TH');

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
    fireEvent.change(screen.getByLabelText(/^แผ่น\/ตร\.ม\./), { target: { value: '1.39' } });
    const priceInput = screen.getByLabelText(/^ราคา\/ตร\.ม\./);
    fireEvent.change(priceInput, { target: { value: '3' } });
    fireEvent.change(priceInput, { target: { value: '35' } });
    fireEvent.change(priceInput, { target: { value: '350' } });

    await waitFor(() => expect(api.dealQuotations.calculateLine).toHaveBeenCalledTimes(1), { timeout: 1000 });
    // The LAST value wins -- an intermediate keystroke's debounced call was cancelled, not queued.
    expect(api.dealQuotations.calculateLine).toHaveBeenCalledWith(expect.objectContaining({ listPriceSqm: 350 }), 'TH');
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
    fireEvent.change(screen.getByLabelText(/^แผ่น\/ตร\.ม\./), { target: { value: '1.39' } });
    const priceInput = screen.getByLabelText(/^ราคา\/ตร\.ม\./);

    fireEvent.change(priceInput, { target: { value: '100' } });
    await waitFor(() => expect(api.dealQuotations.calculateLine).toHaveBeenCalledTimes(1), { timeout: 1000 });
    expect(api.dealQuotations.calculateLine).toHaveBeenNthCalledWith(1, expect.objectContaining({ listPriceSqm: 100 }), 'TH');

    fireEvent.change(priceInput, { target: { value: '200' } });
    await waitFor(() => expect(api.dealQuotations.calculateLine).toHaveBeenCalledTimes(2), { timeout: 1000 });
    expect(api.dealQuotations.calculateLine).toHaveBeenNthCalledWith(2, expect.objectContaining({ listPriceSqm: 200 }), 'TH');

    // The NEWER request settles first (a faster response for the latest edit).
    resolveSecond({ item: { lineAmount: 2000, calculationLine: 'newer', unitPrice: 134.47 } });
    expect(await screen.findByText('newer')).not.toBeNull();

    // The OLDER request settles LAST -- it must be discarded outright, including its own
    // (irrelevant) echoed input fields, not merged over what the newer response already set.
    resolveFirst({ item: { lineAmount: 1000, calculationLine: 'older', unitPrice: 67.24, listPriceSqm: 100 } });
    await new Promise((resolve) => { setTimeout(resolve, 50); }); // let any wrongly-queued update flush

    expect(screen.getByText('newer')).not.toBeNull();
    expect(screen.queryByText('older')).toBeNull();
    expect(priceInput.value).toBe('200');
    // …and that includes the one input-named field a response may now write (V192.2): the per-แผ่น
    // figure under the box is the NEWER request's, not the stale one's.
    expect(screen.getByTestId('list-per-piece-0').textContent).toBe('= ฿134.47/แผ่น (ก่อน VAT)');
  });

  // Opus review fix (2026-09-14): โครงการ became an editable card field in the same change that
  // introduced this regression -- the seeding effect used to try prefilling it from
  // `ticket.projectName` on the SAME pass that marks the visit "initialized" (before ticketQuery
  // has resolved), so it always seeded from `undefined` and the guard then made the dependency
  // inert forever. Mirrors `contactSeededForTicket`'s own dedicated, ticket-id-keyed effect, which
  // this pins the same way for โครงการ.
  it('prefills โครงการ from the deal once the ticket resolves, on a brand-new quotation', async () => {
    api.tickets.get.mockResolvedValue({
      ticket: {
        summary: {
          id: 18, createdById: 6, createdByName: 'คุณสมหมาย ขายดี', salesStage: 'QUOTE_DESIGN_SIDE',
          customerName: 'บริษัท แฟชั่นไอส์แลนด์ จำกัด', projectName: 'โครงการ A',
          customerId: 5, contactId: 6, contactName: 'ณัฐพงศ์ ศรีวิไล',
        },
      },
    });
    renderEditor('/quotations/new?ticket=18');
    await waitFor(() => expect(document.getElementById('projectNameCard')?.value).toBe('โครงการ A'));
  });

  // Second Opus follow-up nit (2026-09-14): the reviewer asked whether a rep typing โครงการ
  // during the ticketQuery window could have this effect clobber it afterward, unlike
  // contactSeededForTicket (whose own candidate list has nothing to pick from until the ticket
  // resolves, so it cannot be raced the same way). Tried to build exactly that scenario --
  // mock ticketQuery to stay pending, assert the field IS rendered and typeable, THEN resolve it --
  // and hit a wall: this effect only ever runs when `ticket?.id` is set and `!id` (a brand-new
  // `?ticket=` visit), and on that exact path `isEditable` gates on
  // `canCreateDealQuotation(user, ticket)` -- the SAME `ticket` this effect waits on. While the
  // ticket query is pending, the page renders "ไม่มีสิทธิ์สร้างใบเสนอราคา" instead of the form at
  // all, so โครงการ never exists to type into before the effect's own precondition is satisfied.
  // No reachable window today -- confirmed by trying, not assumed, the same way R1's fallback
  // branch was. The defensive `t.projectName ? t : {...}` guard in the effect itself is kept
  // anyway (harmless, and correct if this page's access gating ever decouples from `ticket` in
  // the future), but there is nothing here to test without faking that decoupling.
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
          id: 18, createdById: 6, createdByName: 'คุณสมหมาย ขายดี', salesStage: 'QUOTE_DESIGN_SIDE',
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
  // แผ่น/ตร.ม. (owner feedback 2026-09-12) -- the field now shows/accepts the RECIPROCAL of
  // `item.sqmPerPiece`; typed value only needs to be positive for completeness, so it is not
  // required to be the exact reciprocal of any other fixture's figure.
  fireEvent.change(screen.getByLabelText(/^แผ่น\/ตร\.ม\./), { target: { value: '1.39' } });
  fireEvent.change(screen.getByLabelText(/^แผ่น\/กล่อง/), { target: { value: '3' } });
  // Per ตร.ม. including VAT on a Thai document (sales feedback #1, 2026-10-06).
  fireEvent.change(screen.getByLabelText(/^ราคา\/ตร\.ม\./), { target: { value: '850' } });
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
          id: 18, createdById: 6, createdByName: 'คุณสมหมาย ขายดี', salesStage: 'QUOTE_DESIGN_SIDE',
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
    fireEvent.change(screen.getByLabelText(/^ราคา\/ตร\.ม\./), { target: { value: '100' } });
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

// Owner 2026-10-12: "remove สร้างจากใบเดิม". The action is offered on no quotation any more. The
// provenance note on a clone that already exists (the cases below) still renders.
describe('QuotationEditorPage reorder / สร้างจากใบเดิม (removed 2026-10-12)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('is offered on no quotation, not even an APPROVED one', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: baseQuotation({ docStatus: 'APPROVED', number: 'QT-2026-0005-1' }),
    });
    renderEditor('/quotations/5');
    // The control: the sibling action that shared its old gate (canReviseDealQuotation) is there.
    await screen.findByRole('button', { name: 'สร้างฉบับแก้ไข' });
    expect(screen.queryByRole('button', { name: /สร้างจากใบเดิม|สั่งเหมือนเดิม/ })).toBeNull();
    expect(screen.queryByRole('menuitem', { name: /สร้างจากใบเดิม|สั่งเหมือนเดิม/ })).toBeNull();
  });

    // Opus review (2026-09-19): the note must NOT claim "stays approved" when the source has
    // ALREADY been superseded by something else (a sibling's approval, not necessarily this
    // clone's) -- at minimum, neutral wording only.
    it('a DRAFT clone whose source was already superseded by a sibling shows neutral wording, not "stays approved"',
      async () => {
        api.dealQuotations.get.mockResolvedValue({
          quotation: baseQuotation({
            id: 43, docStatus: 'DRAFT', number: 'QT-2026-0005-3',
            derivedFromQuotationId: 5, derivedFromQuotationNumber: 'QT-2026-0005-1',
            derivedFromQuotationStatus: 'SUPERSEDED',
          }),
        });
        renderEditor('/quotations/43');

        expect(await screen.findByText(/สั่งเหมือนเดิมจากใบเสนอราคา/)).not.toBeNull();
        expect(screen.queryByText(/ยังคงสถานะอนุมัติแล้ว/)).toBeNull();
        expect(screen.queryByText(/เข้ามาแทนที่ใบเดิมแล้ว/)).toBeNull();
      });

  // Owner ruling 2026-09-19: once the CLONE itself is APPROVED, the backend's one-APPROVED-per-
  // deal sweep has already superseded the source -- the provenance note must say so instead of
  // repeating the now-false "the original stays approved" claim.
  it('once the clone itself is APPROVED, the provenance note says it replaced the original (not "stays approved")',
    async () => {
      api.dealQuotations.get.mockResolvedValue({
        quotation: baseQuotation({
          id: 42, docStatus: 'APPROVED', number: 'QT-2026-0005-2',
          derivedFromQuotationId: 5, derivedFromQuotationNumber: 'QT-2026-0005-1',
        }),
      });
      renderEditor('/quotations/42');

      expect(await screen.findByText(/สั่งเหมือนเดิมจากใบเสนอราคา/)).not.toBeNull();
      expect(screen.getByText(/เข้ามาแทนที่ใบเดิมแล้ว/)).not.toBeNull();
      expect(screen.queryByText(/ยังคงสถานะอนุมัติแล้วจนกว่า/)).toBeNull();
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
    fireEvent.change(screen.getByLabelText(/^แผ่น\/ตร\.ม\./), { target: { value: '1.39' } });
    fireEvent.change(screen.getByLabelText(/^ราคา\/ตร\.ม\./), { target: { value: '500' } });
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
    // Slice 2 (SLICE-2-FLOW-A.md §A): /quotations/new now opens on "เลือกดีลที่มีอยู่" — this whole
    // block is about the OTHER branch, so every case starts by choosing สร้างดีลใหม่, the same click
    // a rep makes. (QuotationEditorPage.recipient.test.jsx covers the pick branch.)
    fireEvent.click(screen.getByRole('button', { name: 'สร้างดีลใหม่' }));
    return { ...result, showToast, queryClient };
  }

  // ช่องทางรับงาน is required to save (UNSPECIFIED is never a choice), so the helper picks one unless
  // a test is specifically about the gate.
  function pickEntryChannel() {
    fireEvent.click(screen.getByRole('button', { name: /ผู้ออกแบบนำดีล/ }));
  }

  async function selectCustomerAndProject({ channel = true } = {}) {
    api.customers.search.mockResolvedValue({ customers: [testCustomer] });
    api.customers.projects.mockResolvedValue({ projects: [testProject] });
    // Slice 2 (S2-B1): a direct quotation needs its ผู้รับใบเสนอราคา before it can be saved.
    // Incidental to every case here, so it is chosen alongside the customer and project.
    fireEvent.click(screen.getByRole('radio', { name: 'ผู้ออกแบบ' }));

    fireEvent.change(await screen.findByLabelText(/^ลูกค้า/), { target: { value: 'ก้าวหน้า' } });
    // role="option" since V4 (2026-09-10) — the typeahead popup is a listbox.
    fireEvent.mouseDown(await screen.findByRole('option', { name: new RegExp(testCustomer.name) }));

    await waitFor(() => expect(screen.getAllByText(testCustomer.name).length).toBeGreaterThan(0)); // the chip that replaces the search input
    await waitFor(() => expect(api.customers.projects).toHaveBeenCalledWith(testCustomer.id));

    // โครงการ is a type-ahead combobox (owner testing feedback, 2026-09-11), not a <select> --
    // open it and pick the option, same "role=option" listbox shape as ลูกค้า above.
    fireEvent.focus(await screen.findByLabelText(/^โครงการ/));
    fireEvent.mouseDown(await screen.findByRole('option', { name: new RegExp(testProject.name) }));
    if (channel) pickEntryChannel();
  }

  // #M4 (owner ruling 2026-09-10): "ALL info about the tile has to be completed" -- reuses the
  // SAME complete-item helper every other editor visit's tests fill with, so this describe block
  // isn't gating บันทึกร่าง on a looser bar than the rest of the suite.
  async function fillOneValidItem() {
    fireEvent.click(screen.getByRole('button', { name: /เพิ่มรายการ/ }));
    await fillCompleteQuotationItem();
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

    await selectCustomerAndProject({ channel: false });
    expect(screen.queryByText('ต้องเลือกลูกค้าก่อนบันทึกร่าง')).toBeNull();
    expect(screen.queryByText('ต้องเลือกโครงการก่อนบันทึกร่าง')).toBeNull();
    // Customer+project are satisfied now, but there is still no item -- บันทึกร่าง stays gated
    // on the SAME item-completeness checks every other editor visit already enforces (#M4).
    expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(true);
    expect(screen.getByText('ต้องมีรายการสินค้าอย่างน้อย 1 รายการ')).not.toBeNull();

    // Owner-directed reversal of F2/V167 (2026-09-26): ผู้สั่งซื้อ used to be its own gate here,
    // on top of ลูกค้า/โครงการ and item completeness -- filling the item alone now unblocks
    // บันทึกร่าง, with no separate ผู้สั่งซื้อ requirement left to satisfy.
    await fillOneValidItem();
    // ช่องทางรับงาน is the last gate: everything else is complete but no channel is picked, and
    // ยังไม่ระบุช่องทาง is not offered as a choice, so saving stays blocked until one of the three is.
    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).title)
      .toContain('ต้องเลือกช่องทางรับงานก่อนบันทึกร่าง'));
    expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(true);
    // The editor hands the picker its inline hint, not just the disabled button's tooltip.
    expect(screen.getByText('ต้องเลือกช่องทางรับงานก่อนบันทึกร่าง', { selector: 'p' })).not.toBeNull();
    pickEntryChannel();
    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));
    expect(screen.queryByText('ต้องเลือกช่องทางรับงานก่อนบันทึกร่าง', { selector: 'p' })).toBeNull();
    expect(screen.queryByText('กรุณาระบุผู้สั่งซื้อ')).toBeNull();
  }, 10000);

  it('บันทึกร่าง creates the ticket, then the quotation on it, then navigates to /quotations/{id}', async () => {
    api.tickets.create.mockResolvedValue({
      ticket: { summary: { id: 42, createdById: 6, customerName: testCustomer.name, projectName: testProject.name } },
    });
    api.dealQuotations.create.mockResolvedValue({ quotation: { id: 99 } });

    renderInlineCreate();
    await selectCustomerAndProject();
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
      // The deal never carries a ผู้ติดต่อ (ruling 2026-10-18): it is picked per quotation and
      // travels on dealQuotations.create (quotationOrderedByContact.test.jsx pins that).
      contactId: null,
      entryChannel: 'DESIGNER_LED',
      priority: 'NORMAL',
      items: [],
      nextFollowUpAt: expectedFollowUp,
      // GLA-136 (owner ruling 2026-09-30): the inline-created deal is a quotation-only container,
      // not a pipeline deal, until an APPROVED quotation on it is promoted.
      quotationOnly: true,
    }));

    // The ticket envelope's id lives at `.summary.id` -- the SAME unwrap this test suite's own
    // #M2 fixtures were fixed to use above, not `.ticket.id`.
    // The price travels as it was typed — per ตร.ม. including VAT (sales feedback #1, 2026-10-06) —
    // with no per-แผ่น price beside it: the server derives that one.
    await waitFor(() => expect(api.dealQuotations.create).toHaveBeenCalledWith(42, expect.objectContaining({
      items: expect.arrayContaining([expect.objectContaining({ listPriceSqm: 850, unitPrice: null, model: 'Trilogy' })]),
    })));

    expect(await screen.findByText('navigated-to-99')).not.toBeNull();
  });

  it('a tickets.create failure leaves the form intact and toasts the backend\'s Thai message, without ever calling dealQuotations.create', async () => {
    const backendError = new Error('สร้างดีลไม่สำเร็จ: ชื่อลูกค้าซ้ำในระบบ');
    api.tickets.create.mockRejectedValueOnce(backendError);

    const { showToast } = renderInlineCreate();
    await selectCustomerAndProject();
    await fillOneValidItem();
    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));

    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', backendError.message));
    expect(api.dealQuotations.create).not.toHaveBeenCalled();
    // The form is untouched -- the selected customer chip and the item's typed values are still
    // exactly what the rep entered, so they don't have to redo any of it before retrying.
    expect(screen.getAllByText(testCustomer.name).length).toBeGreaterThan(0);
    expect(screen.getByLabelText(/^ราคา\/ตร\.ม\./).value).toBe('850');
    expect(screen.getByLabelText(/^รุ่น \/ ค้นหาแคตตาล็อก/).value).toBe('Trilogy');
  });

  it('a dealQuotations.create failure after tickets.create succeeded retries WITHOUT creating a second ticket', async () => {
    api.tickets.create.mockResolvedValue({ ticket: { summary: { id: 42 } } });
    api.dealQuotations.create.mockRejectedValueOnce(new Error('บันทึกไม่สำเร็จ (ทดสอบ)'));

    const { showToast } = renderInlineCreate();
    await selectCustomerAndProject();
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

  // The retry above reuses the deal it already made. That deal was made for ONE customer and ONE
  // project, so it may be reused only while the form still says those two. A rep whose first save
  // was refused can change either before trying again, and a quotation saved onto the earlier
  // choice's deal would sit in another customer's records.
  describe('the deal a retry reuses is the one made for the customer and project on the form', () => {
    const otherCustomer = { id: 2, name: 'บริษัท รุ่งเรือง ดีเวลลอปเมนท์ จำกัด', taxId: '0105565067890' };
    // The same id as testProject, on purpose: an id is whatever the server gave, and a different
    // customer has to be enough on its own.
    const otherCustomersProject = { id: 1, customerId: 2, name: 'โครงการ The Mall บางกะปิ' };
    const secondProjectOfTheSameCustomer = { id: 8, customerId: 1, name: 'โครงการ Central Ladprao ชั้น 3' };

    const saveButton = () => screen.getByRole('button', { name: 'บันทึกร่าง' });
    const dealsQuotedOn = () => api.dealQuotations.create.mock.calls.map(([dealId]) => dealId);

    // A filled row is previewed 300ms later, and these cases are still on the form by then.
    beforeEach(() => {
      api.dealQuotations.calculateLine.mockResolvedValue({ item: {} });
    });

    // As selectCustomerAndProject above, in two steps and for any customer. `onFile` is every
    // project that customer has.
    async function chooseCustomer(customer, onFile) {
      api.customers.search.mockResolvedValue({ customers: [customer] });
      api.customers.projects.mockResolvedValue({ projects: onFile });
      fireEvent.change(await screen.findByLabelText(/^ลูกค้า/), { target: { value: customer.name } });
      fireEvent.mouseDown(await screen.findByRole('option', { name: new RegExp(customer.name) }));
      await waitFor(() => expect(api.customers.projects).toHaveBeenCalledWith(customer.id));
    }
    async function chooseProject(project) {
      fireEvent.focus(await screen.findByLabelText(/^โครงการ/));
      fireEvent.mouseDown(await screen.findByRole('option', { name: new RegExp(project.name) }));
    }

    /** The first save: deal 42 is made for testCustomer and testProject, and the quotation on it
     * is refused. Every deal made after it gets the next id, as a server would give it. */
    async function saveAndBeRefused(projectsOnFile = [testProject]) {
      let nextDealId = 42;
      api.tickets.create.mockImplementation(async () => {
        const id = nextDealId;
        nextDealId += 1;
        return { ticket: { summary: { id } } };
      });
      api.dealQuotations.create.mockResolvedValue({ quotation: { id: 99 } });
      api.dealQuotations.create.mockRejectedValueOnce(new Error('บันทึกไม่สำเร็จ (ทดสอบ)'));

      const { showToast } = renderInlineCreate();
      await chooseCustomer(testCustomer, projectsOnFile);
      await chooseProject(testProject);
      // Slice 2 (S2-B1) + #1085: a new deal's recipient and a real ช่องทางรับงาน gate บันทึกร่าง.
      fireEvent.click(screen.getByRole('radio', { name: 'ผู้ออกแบบ' }));
      pickEntryChannel();
      await fillOneValidItem();
      await waitFor(() => expect(saveButton().disabled).toBe(false));
      fireEvent.click(saveButton());
      await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', 'บันทึกไม่สำเร็จ (ทดสอบ)'));
      expect(dealsQuotedOn()).toEqual([42]);
    }

    async function saveAgain() {
      await waitFor(() => expect(saveButton().disabled).toBe(false));
      fireEvent.click(saveButton());
      await waitFor(() => expect(api.dealQuotations.create).toHaveBeenCalledTimes(2));
    }

    // Wrong way round: the second quotation is NOT on deal 42.
    it('a DIFFERENT customer gets a deal of its own — the quotation never lands on the first customer\'s deal', async () => {
      await saveAndBeRefused();

      fireEvent.click(screen.getByRole('button', { name: 'ล้างลูกค้าที่เลือก' }));
      await chooseCustomer(otherCustomer, [otherCustomersProject]);
      await chooseProject(otherCustomersProject);
      await saveAgain();

      expect(dealsQuotedOn()).toEqual([42, 43]);
      expect(api.tickets.create).toHaveBeenCalledTimes(2);
      expect(api.tickets.create.mock.calls[1][0]).toMatchObject({
        customerId: otherCustomer.id, customerName: otherCustomer.name, projectId: otherCustomersProject.id,
      });
      expect(await screen.findByText('navigated-to-99')).not.toBeNull();
    }, 10000);

    it('a DIFFERENT project of the same customer gets a deal of its own too', async () => {
      await saveAndBeRefused([testProject, secondProjectOfTheSameCustomer]);

      fireEvent.click(screen.getByRole('button', { name: 'ล้างโครงการที่เลือก' }));
      await chooseProject(secondProjectOfTheSameCustomer);
      await saveAgain();

      expect(dealsQuotedOn()).toEqual([42, 43]);
      expect(api.tickets.create).toHaveBeenCalledTimes(2);
      expect(api.tickets.create.mock.calls[1][0]).toMatchObject({
        customerId: testCustomer.id, projectId: secondProjectOfTheSameCustomer.id,
      });
      expect(await screen.findByText('navigated-to-99')).not.toBeNull();
    }, 10000);

    // REGRESSION GUARD: green before the two cases above were, and it has to stay green. The
    // question is what the form SAYS when it is saved, not whether it was touched in between:
    // the customer cleared and chosen again, with the same project, is still the customer and
    // project deal 42 was made for. Left untouched and retried is the test before this block.
    it('the SAME customer and project, chosen again, still reuse the first deal — no second one', async () => {
      await saveAndBeRefused();

      fireEvent.click(screen.getByRole('button', { name: 'ล้างลูกค้าที่เลือก' }));
      await chooseCustomer(testCustomer, [testProject]);
      await chooseProject(testProject);
      await saveAgain();

      expect(dealsQuotedOn()).toEqual([42, 42]);
      expect(api.tickets.create).toHaveBeenCalledTimes(1);
      expect(await screen.findByText('navigated-to-99')).not.toBeNull();
    }, 10000);
  });

  // "?ticket= present" is the OLD behaviour, unchanged (inline-deal-spec.md: "as today") -- the
  // deal renders read-only instead of the customer/project fields, with a link back to it. Slice 2
  // moved that link into the header strip as "เปิดดีล" (IA §8: one deal verb, everywhere); the old
  // in-panel "ดูรายละเอียดดีลนี้" is gone, so there is exactly one.
  it('with ?ticket= present, the deal renders read-only with a link to /tickets/{id} -- no customer/project fields', async () => {
    api.customers.contacts.mockResolvedValue({
      contacts: [
        { id: 6, customerId: 5, firstName: 'ณัฐพงศ์', lastName: 'ศรีวิไล', phone: '086-222-3333', email: 'nattapong@fashionisland.co.th' },
        { id: 7, customerId: 5, firstName: 'พิมพ์ใจ', lastName: 'บุญมาก', phone: '086-444-5555', email: 'pimjai@fashionisland.co.th' },
      ],
    });
    api.tickets.get.mockResolvedValue({
      ticket: {
        summary: {
          id: 18, createdById: 6, createdByName: 'คุณสมหมาย ขายดี', salesStage: 'QUOTE_DESIGN_SIDE',
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
    expect(screen.queryByLabelText(/^ลูกค้า/)).toBeNull(); // the สร้างดีลใหม่ branch's own field, not this mode's
    const link = screen.getByRole('link', { name: /เปิดดีล/ });
    expect(link.getAttribute('href')).toBe('/tickets/18');
    expect(screen.queryByRole('link', { name: 'ดูรายละเอียดดีลนี้' })).toBeNull();
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
        id: 18, createdById: 6, createdByName: 'คุณสมหมาย ขายดี', salesStage: 'QUOTE_DESIGN_SIDE',
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

  // Sales feedback 2026-10-06 (แจ้งปัญหาการใช้งาน เสนอราคา, Sales #3): "เวลาพิมพ์เสร็จจะพิมพ์
  // รายการต่อไป ต้องคอยเลื่อนกลับขึ้นไปด้านบน" — every "เพิ่ม…" action used to sit ABOVE the rows
  // it appends to, so a long quotation meant scrolling up to add and back down to type. They now
  // sit at the END of the list they append to, and adding takes the rep straight to what was added.
  describe('add actions at the bottom of what they append to (sales feedback #3, 2026-10-06)', () => {
    // `b` comes after `a` in document order — i.e. renders below it in this single-column list.
    const follows = (a, b) => Boolean(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING);
    const addTileButtons = () => screen.getAllByRole('button', { name: /เพิ่มรายการในตำแหน่งนี้/ });
    const addPlainButtons = () => screen.getAllByRole('button', { name: 'เพิ่มสินค้าหรือบริการอื่นในตำแหน่งนี้' });

    let scrollIntoView;
    let originalScrollIntoView;
    beforeEach(() => {
      // jsdom does not implement scrollIntoView at all; a spy on the prototype is what lets these
      // tests see WHICH element the editor scrolled to (`mock.contexts`).
      originalScrollIntoView = Element.prototype.scrollIntoView;
      scrollIntoView = vi.fn();
      Element.prototype.scrollIntoView = scrollIntoView;
    });
    afterEach(() => {
      Element.prototype.scrollIntoView = originalScrollIntoView;
    });

    async function twoGroupDraft() {
      draftWithItems([groupedItem(0, 'ชั้น 1', 'Trilogy'), groupedItem(1, 'ชั้น 2', 'Frame')]);
      renderEditor('/quotations/5');
      await waitFor(() => expect(screen.getByLabelText(/^ตำแหน่งติดตั้งที่ 2/).value).toBe('ชั้น 2'));
    }

    it('puts each position\'s two add buttons below that position\'s own items, inside its box', async () => {
      await twoGroupDraft();
      const firstLabel = document.getElementById('group-label-0');
      const secondLabel = document.getElementById('group-label-1');

      for (const buttons of [addTileButtons(), addPlainButtons()]) {
        expect(buttons).toHaveLength(2);
        // Position 1's button: under its item (flat row 0), still above position 2 — the bottom
        // of ITS box, not the bottom of the whole list.
        expect(follows(document.getElementById('notes-0'), buttons[0])).toBe(true);
        expect(follows(buttons[0], secondLabel)).toBe(true);
        expect(buttons[0].closest('section')).toBe(firstLabel.closest('section'));
        // Position 2's button: under its own item (flat row 1).
        expect(follows(document.getElementById('notes-1'), buttons[1])).toBe(true);
        expect(buttons[1].closest('section')).toBe(secondLabel.closest('section'));
      }
    });

    it('an empty position still offers its add buttons, under the "no items" warning', async () => {
      draftWithItems([groupedItem(0, 'ชั้น 1', 'Trilogy')]);
      renderEditor('/quotations/5');
      await waitFor(() => expect(screen.getByLabelText(/^ตำแหน่งติดตั้งที่ 1/).value).toBe('ชั้น 1'));
      fireEvent.click(screen.getByRole('button', { name: /เพิ่มตำแหน่ง/ }));

      const warning = screen.getByText(/^ยังไม่มีรายการในตำแหน่งนี้/);
      const emptySection = document.getElementById('group-label-1').closest('section');
      expect(warning.closest('section')).toBe(emptySection);
      expect(addTileButtons()[1].closest('section')).toBe(emptySection);
      expect(follows(warning, addTileButtons()[1])).toBe(true);
      expect(follows(warning, addPlainButtons()[1])).toBe(true);
    });

    it('offers "เพิ่มตำแหน่ง" once — after the last position, before ส่วนลดพิเศษ', async () => {
      await twoGroupDraft();
      // ONE button: the header copy is gone rather than duplicated, so there is a single place to
      // look for it and it is always next to where the new position will appear.
      const addGroupButtons = screen.getAllByRole('button', { name: /เพิ่มตำแหน่ง/ });
      expect(addGroupButtons).toHaveLength(1);
      expect(follows(addTileButtons()[1], addGroupButtons[0])).toBe(true);
      expect(follows(addPlainButtons()[1], addGroupButtons[0])).toBe(true);
      expect(follows(addGroupButtons[0], screen.getByRole('button', { name: /เพิ่มส่วนลดพิเศษ/ }))).toBe(true);
      // …and it is NOT inside any one position's box: it adds a sibling of them.
      expect(addGroupButtons[0].closest('section')).not.toBe(document.getElementById('group-label-1').closest('section'));
    });

    it('adding a tile row focuses and scrolls to THAT row\'s รุ่น field, wherever it landed', async () => {
      await twoGroupDraft();
      // Into position 1 — the new row lands at flat index 1, in the MIDDLE of the list (position
      // 2's row moves to index 2). Focusing "the last row" would be the wrong row here.
      fireEvent.click(addTileButtons()[0]);

      await waitFor(() => expect(document.activeElement?.id).toBe('model-1'));
      expect(document.getElementById('model-1').value).toBe('');
      expect(document.getElementById('model-2').value).toBe('Frame');
      expect(scrollIntoView.mock.contexts).toContain(document.getElementById('model-1'));
    });

    it('adding สินค้า/บริการอื่น focuses and scrolls to the new row\'s รายละเอียด field', async () => {
      await twoGroupDraft();
      fireEvent.click(addPlainButtons()[0]);

      await waitFor(() => expect(document.activeElement?.id).toBe('plain-desc-1'));
      expect(scrollIntoView.mock.contexts).toContain(document.getElementById('plain-desc-1'));
    });

    it('adding a position focuses and scrolls to its name field', async () => {
      await twoGroupDraft();
      fireEvent.click(screen.getByRole('button', { name: /เพิ่มตำแหน่ง/ }));

      await waitFor(() => expect(document.activeElement?.id).toBe('group-label-2'));
      expect(scrollIntoView.mock.contexts).toContain(document.getElementById('group-label-2'));
    });

    // The button is now at the BOTTOM, so the unnamed position that blocks it can be a full screen
    // above. A toast alone ("กรุณาตั้งชื่อตำแหน่งติดตั้งที่ยังว่างก่อน") would leave the rep hunting
    // for it; take them to the one field that fixes it.
    it('a refused "เพิ่มตำแหน่ง" takes the rep to the unnamed position instead of adding one', async () => {
      draftWithItems([groupedItem(0, 'ชั้น 1', 'Trilogy'), groupedItem(1, null, 'Frame')]);
      renderEditor('/quotations/5');
      await waitFor(() => expect(screen.getByLabelText(/^ตำแหน่งติดตั้งที่ 1/).value).toBe('ชั้น 1'));
      expect(screen.getByLabelText(/^ตำแหน่งติดตั้งที่ 2/).value).toBe('');

      fireEvent.click(screen.getByRole('button', { name: /เพิ่มตำแหน่ง/ }));

      await waitFor(() => expect(document.activeElement?.id).toBe('group-label-1'));
      expect(screen.queryByLabelText(/^ตำแหน่งติดตั้งที่ 3/)).toBeNull();
      expect(scrollIntoView.mock.contexts).toContain(document.getElementById('group-label-1'));
    });

    it('opening a draft does not move focus or scroll on its own', async () => {
      await twoGroupDraft();
      // Only an explicit add may take the viewport; loading rows must not.
      expect(scrollIntoView).not.toHaveBeenCalled();
      expect(document.activeElement).toBe(document.body);
    });
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

// Owner rulings 2026-10-18 (.design/quotation-contact/DESIGN.md): ผู้ติดต่อ is a picker over the
// deal customer's contacts, replacing the free-text name box on both entry paths. Optional (never
// a blocker), empty on every new quotation (no pre-fill, not even from the deal's own contact),
// sent as `contactId`; the server writes the ผู้สั่งซื้อ signature name from it, so the editor sends
// no `orderedByName`. What the picker itself does (list, search, add modal) is
// QuotationContactPicker.test.jsx's; this block is how the editor mounts and feeds it.
async function pickContact(name) {
  const trigger = await screen.findByLabelText(/^ผู้ติดต่อ/);
  await waitFor(() => expect(trigger.disabled).toBe(false));
  fireEvent.click(trigger);
  fireEvent.click(await screen.findByRole('option', { name: new RegExp(name) }));
}

describe('QuotationEditorPage ผู้ติดต่อ picker (owner rulings 2026-10-18)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.customers.contacts.mockResolvedValue({ contacts: CONTACT_OPTIONS });
    api.dealQuotations.calculateLine.mockResolvedValue({ item: {} });
  });

  it('is the contact picker, empty on a new quotation even though the deal has a contact; untouched, it sends contactId: null and no orderedByName', async () => {
    api.tickets.get.mockResolvedValue(ticketFixture()); // the deal itself carries contact 6
    api.dealQuotations.create.mockResolvedValue({ quotation: { id: 9 } });
    renderEditor('/quotations/new?ticket=18');

    const field = await screen.findByLabelText(/^ผู้ติดต่อ/);
    expect(field.tagName).toBe('BUTTON'); // the picker's trigger, not a text box
    await waitFor(() => expect(field.disabled).toBe(false));
    expect(field.textContent).toContain('- เลือกผู้ติดต่อ -'); // no pre-fill (ruling 2026-10-18)

    fireEvent.click(screen.getByRole('button', { name: /เพิ่มรายการในตำแหน่งนี้/ }));
    await fillCompleteQuotationItem();
    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(api.dealQuotations.create).toHaveBeenCalledTimes(1));
    const sent = api.dealQuotations.create.mock.calls[0][1];
    expect(sent.contactId).toBeNull();
    expect(sent).not.toHaveProperty('orderedByName');
  });

  // A contact belongs to ONE customer, and the server refuses a contactId of another customer's
  // (DealQuotationService#resolveContact → 400). So on a new deal, letting go of the customer lets
  // go of the contact picked for them.
  it('on a new deal, clearing the chosen customer drops the ผู้ติดต่อ picked for them', async () => {
    api.customers.search.mockResolvedValue({ customers: [{ id: 5, name: 'บริษัท แฟชั่นไอส์แลนด์ จำกัด' }] });
    api.customers.projects.mockResolvedValue({ projects: [] });
    renderEditor('/quotations/new');
    fireEvent.click(await screen.findByRole('button', { name: 'สร้างดีลใหม่' }));
    fireEvent.change(await screen.findByLabelText(/^ลูกค้า/), { target: { value: 'แฟชั่น' } });
    fireEvent.mouseDown(await screen.findByRole('option', { name: /แฟชั่นไอส์แลนด์/ }));

    await pickContact('พิมพ์ใจ');
    expect(screen.getByText('พิมพ์ใจ บุญมาก')).not.toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'ล้างลูกค้าที่เลือก' }));
    await waitFor(() => expect(screen.queryByText('พิมพ์ใจ บุญมาก')).toBeNull());
    expect(screen.getByLabelText(/^ผู้ติดต่อ/).textContent).toContain('เลือกลูกค้าก่อน');
  });

  // The gate itself is GONE, wrong-way-round: a deal with no ticket-level contact at all, and no
  // ผู้สั่งซื้อ typed either, must still be saveable with no "กรุณาระบุผู้สั่งซื้อ" anywhere.
  it('never blocks บันทึกร่าง on a missing ผู้สั่งซื้อ, and never shows "กรุณาระบุผู้สั่งซื้อ"', async () => {
    api.tickets.get.mockResolvedValue(ticketFixture({ contactId: null, contactName: null }));
    api.dealQuotations.create.mockResolvedValue({ quotation: { id: 9 } });
    renderEditor('/quotations/new?ticket=18');

    fireEvent.click(await screen.findByRole('button', { name: /เพิ่มรายการในตำแหน่งนี้/ }));
    await fillCompleteQuotationItem();

    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));
    expect(screen.queryByText('กรุณาระบุผู้สั่งซื้อ')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(api.dealQuotations.create).toHaveBeenCalledTimes(1));
    expect(api.dealQuotations.create.mock.calls[0][1].contactId).toBeNull();
  });

  // An existing draft opens on its OWN contact (its contactId/contactName snapshot) — not on the
  // deal's contact, and not on a name an older draft stored as free text.
  it('an existing draft opens with its own contact picked', async () => {
    api.tickets.get.mockResolvedValue(ticketFixture()); // the deal's contact is 6, ณัฐพงศ์
    api.dealQuotations.get.mockResolvedValue({
      quotation: baseQuotation({
        contactId: 7, contactName: 'พิมพ์ใจ บุญมาก', contactPhone: '086-444-5555', contactEmail: null,
        orderedByName: 'คุณวิชัย มั่นคง',
      }),
    });
    renderEditor('/quotations/5');

    expect(await screen.findByText('พิมพ์ใจ บุญมาก')).not.toBeNull();
    expect(screen.queryByText(/ณัฐพงศ์/)).toBeNull();
    expect(screen.queryByDisplayValue('คุณวิชัย มั่นคง')).toBeNull();
  });

  it('an inline-create quotation (no deal yet) renders the picker too, waiting for a customer', async () => {
    renderEditor('/quotations/new');
    // Slice 2: the inline (new-deal) branch is behind the ดีล step's "สร้างดีลใหม่" now.
    fireEvent.click(await screen.findByRole('button', { name: 'สร้างดีลใหม่' }));

    // DealCustomerCard itself renders no ผู้ติดต่อ control -- the picker lives on the editor,
    // right next to the card, same as the ticket/existing-draft path.
    await screen.findByLabelText(/^ลูกค้า/);
    const field = screen.getByLabelText(/^ผู้ติดต่อ/);
    expect(field.tagName).toBe('BUTTON');
    expect(field.disabled).toBe(true);
    expect(field.textContent).toContain('เลือกลูกค้าก่อน');
  });

  // Review Focus 4: a quotation-grant holder whose role cannot read the deal gets no ticket, so no
  // customer id for the picker — the editor must still render, with the picker in its own
  // "pick a customer first" state rather than a control that fails on use.
  it('a draft whose deal could not be loaded still renders, with the picker disabled', async () => {
    api.tickets.get.mockRejectedValue(new Error('ไม่มีสิทธิ์เข้าถึงดีลนี้'));
    api.dealQuotations.get.mockResolvedValue({ quotation: baseQuotation() });
    renderEditor('/quotations/5');

    const field = await screen.findByLabelText(/^ผู้ติดต่อ/);
    await waitFor(() => expect(api.tickets.get).toHaveBeenCalled());
    expect(field.disabled).toBe(true);
    expect(field.textContent).toContain('เลือกลูกค้าก่อน');
    expect(screen.getByRole('button', { name: 'บันทึกร่าง' })).not.toBeNull();
    expect(api.customers.contacts).not.toHaveBeenCalled();
  });
});

// Owner, 2026-10-06: "the field ผู้สั่งซื้อ make it name ผู้ติดต่อ instead but the pdf still render as
// ผู้สั่งซื้อ" — kept by the 2026-10-18 picker. So two things are pinned here, and the second is the
// one that keeps the first honest: the field answers to ผู้ติดต่อ (and no longer to ผู้สั่งซื้อ), and
// its one hint says the picked name prints as the document's ผู้สั่งซื้อ.
//
// What the DOCUMENT prints is not this file's to prove: QuotationDocumentView.test.jsx pins the
// on-screen document's heading, and DealQuotationEnglishFormTest / QuotationHtmlDocumentTest pin
// the printed form's (Java; no backend line changed for this).
describe('QuotationEditorPage — the ผู้สั่งซื้อ field is labelled ผู้ติดต่อ (owner, 2026-10-06 and 2026-10-18)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.customers.contacts.mockResolvedValue({ contacts: CONTACT_OPTIONS });
    api.dealQuotations.calculateLine.mockResolvedValue({ item: {} });
  });

  /** The text of whatever the control names as its description (FormField's hint line). */
  const hintOf = (field) => (field.getAttribute('aria-describedby') ?? '')
    .split(/\s+/).filter(Boolean)
    .map((id) => document.getElementById(id)?.textContent ?? '')
    .join(' ');

  it('on a quotation for an existing deal the field answers to ผู้ติดต่อ, and to ผู้สั่งซื้อ no longer', async () => {
    api.tickets.get.mockResolvedValue(ticketFixture());
    renderEditor('/quotations/new?ticket=18');

    const field = await screen.findByLabelText(/^ผู้ติดต่อ/);
    expect(field.id).toBe('quotation-contact');
    expect(screen.queryByLabelText(/^ผู้สั่งซื้อ/)).toBeNull();
  });

  it('the same on a quotation that creates its deal inline', async () => {
    renderEditor('/quotations/new');
    // Slice 2 flow A: /quotations/new opens on เลือกดีลที่มีอยู่; the inline card is the other branch.
    fireEvent.click(await screen.findByRole('button', { name: 'สร้างดีลใหม่' }));

    await screen.findByLabelText(/^ลูกค้า/);
    expect(screen.getByLabelText(/^ผู้ติดต่อ/).id).toBe('quotation-contact');
    expect(screen.queryByLabelText(/^ผู้สั่งซื้อ/)).toBeNull();
  });

  // The field's name and the document's heading differ, so the hint has to carry the link.
  const HINT = 'ชื่อจะพิมพ์เป็นผู้สั่งซื้อบนใบเสนอราคา';

  it('says, under the field, that the name prints as the document\'s ผู้สั่งซื้อ', async () => {
    api.tickets.get.mockResolvedValue(ticketFixture());
    renderEditor('/quotations/new?ticket=18');

    expect(hintOf(await screen.findByLabelText(/^ผู้ติดต่อ/))).toBe(HINT);
  });

  it('the inline-create field carries the same hint', async () => {
    renderEditor('/quotations/new');
    // Slice 2 flow A: /quotations/new opens on เลือกดีลที่มีอยู่; the inline card is the other branch.
    fireEvent.click(await screen.findByRole('button', { name: 'สร้างดีลใหม่' }));

    await screen.findByLabelText(/^ลูกค้า/);
    expect(hintOf(screen.getByLabelText(/^ผู้ติดต่อ/))).toBe(HINT);
  });
});

// Sales feedback, Admin 1d (แจ้งปัญหาการใช้งาน เสนอราคา) — the ฝ่าย box on the quotation: "ไม่มีดีกว่า",
// reps filled it wrongly and some typed their own name into it. Owner, 2026-10-06: "remove ฝ่าย
// field completely". So the editor has no such field on either entry path, and — the part that is
// easy to get half right — a value an OLD draft still carries is neither shown nor quietly sent
// back: the editor says `deptCode: null` on every save, which is what clears it. (The printed
// form losing its ฝ่าย line is the renderer's: QuotationRendererTest.)
describe('QuotationEditorPage — ฝ่าย is gone (sales feedback Admin 1d, owner 2026-10-06)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.customers.contacts.mockResolvedValue({ contacts: CONTACT_OPTIONS });
    api.tickets.get.mockResolvedValue(ticketFixture());
    api.dealQuotations.calculateLine.mockResolvedValue({ item: {} });
  });

  it('a quotation for an existing deal has no ฝ่าย field — and still has หน่วยงาน / ผู้ออกแบบ', async () => {
    renderEditor('/quotations/new?ticket=18');

    await screen.findByLabelText(/^ผู้ติดต่อ/);
    expect(screen.queryByLabelText('ฝ่าย')).toBeNull();
    expect(document.getElementById('quotation-dept-code')).toBeNull();
    // Its neighbour in the same card is NOT part of this: sales still pick the designer here.
    expect(screen.getByLabelText(/^หน่วยงาน \/ ผู้ออกแบบ/)).not.toBeNull();
  });

  it('nor does one that creates its deal inline', async () => {
    renderEditor('/quotations/new');

    await screen.findByLabelText(/^ผู้ติดต่อ/);
    expect(screen.queryByLabelText('ฝ่าย')).toBeNull();
    expect(document.getElementById('inline-quotation-dept-code')).toBeNull();
    expect(screen.getByLabelText(/^หน่วยงาน \/ ผู้ออกแบบ/)).not.toBeNull();
  });

  it('a new quotation is saved with deptCode: null', async () => {
    api.dealQuotations.create.mockResolvedValue({ quotation: { id: 9 } });
    renderEditor('/quotations/new?ticket=18');

    fireEvent.click(await screen.findByRole('button', { name: /เพิ่มรายการในตำแหน่งนี้/ }));
    await fillCompleteQuotationItem();
    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(api.dealQuotations.create).toHaveBeenCalledTimes(1));
    const sent = api.dealQuotations.create.mock.calls[0][1];
    // Stated, not omitted: a full save says every field, so the server is never left to decide
    // what a missing key means.
    expect(sent).toHaveProperty('deptCode', null);
  });

  // Wrong-way-round: the draft below was saved while the field existed and still has "P003" in
  // it. Nothing on the screen may show that value, and the next save must not carry it back —
  // otherwise a field nobody can see keeps a value nobody can correct.
  it('a draft that still carries a ฝ่าย from before neither shows it nor sends it back', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: baseQuotation({
        items: [groupedItem(0, 'ชั้น 1', 'Trilogy')], contactId: 6, contactName: 'ณัฐพงศ์ ศรีวิไล',
        deptCode: 'P003', unitCode: 'D002',
      }),
    });
    api.dealQuotations.update.mockResolvedValue({ quotation: baseQuotation({ contactId: 6 }) });
    renderEditor('/quotations/5');

    await screen.findByLabelText(/^ผู้ติดต่อ/);
    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));
    expect(screen.queryByLabelText('ฝ่าย')).toBeNull();
    expect(screen.queryByDisplayValue('P003')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalledTimes(1));
    const sent = api.dealQuotations.update.mock.calls[0][1];
    expect(sent).toHaveProperty('deptCode', null);
    // …while the designer code beside it, which is NOT being removed, still round-trips.
    expect(sent.unitCode).toBe('D002');
  });
});

// Sales feedback 2026-10-06 (แจ้งปัญหาการใช้งาน เสนอราคา, Sales #5). Sales asked for โครงการ to be
// optional; the ruling was that it stays REQUIRED on the deal ("กรณียังไม่ทราบชัดเจน เป็นชื่อกลางๆ
// ได้ เช่น บ้านเอกมัย ซอย 5") and the rep chooses per document whether it PRINTS ("สร้างทางเลือก
// แสดง หรือไม่แสดง"). One tick box in the same voice as ไม่รับมัดจำ — unticked is the standard
// document (project printed), ticked leaves the "Project : …" line off the customer's copy while
// the name itself stays on the deal and in this editor.
describe('QuotationEditorPage ไม่แสดงชื่อโครงการ (sales feedback #5, 2026-10-06, V192.1)', () => {
  const TOGGLE = 'ไม่แสดงชื่อโครงการในใบเสนอราคา';
  const follows = (a, b) => Boolean(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING);

  beforeEach(() => {
    vi.clearAllMocks();
    api.customers.contacts.mockResolvedValue({ contacts: CONTACT_OPTIONS });
    api.tickets.get.mockResolvedValue(ticketFixture({ projectName: 'บ้านเอกมัย ซอย 5' }));
    api.dealQuotations.calculateLine.mockResolvedValue({ item: {} });
  });

  function draft(overrides = {}) {
    api.dealQuotations.get.mockResolvedValue({
      quotation: baseQuotation({
        items: [groupedItem(0, 'ชั้น 1', 'Trilogy')], contactId: 6, contactName: 'ณัฐพงศ์ ศรีวิไล',
        projectName: 'บ้านเอกมัย ซอย 5', ...overrides,
      }),
    });
    api.dealQuotations.update.mockResolvedValue({ quotation: baseQuotation({ contactId: 6 }) });
  }

  async function saveDraft() {
    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalledTimes(1));
    return api.dealQuotations.update.mock.calls[0][1];
  }

  async function saveNewQuotationOnDeal() {
    fireEvent.click(screen.getByRole('button', { name: /เพิ่มรายการในตำแหน่งนี้/ }));
    await fillCompleteQuotationItem();
    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
    await waitFor(() => expect(api.dealQuotations.create).toHaveBeenCalledTimes(1));
    return api.dealQuotations.create.mock.calls[0][1];
  }

  it('is offered UNTICKED on a new quotation, and an untouched one still says hideProjectName: false', async () => {
    api.dealQuotations.create.mockResolvedValue({ quotation: { id: 9 } });
    renderEditor('/quotations/new?ticket=18');

    const box = await screen.findByRole('checkbox', { name: new RegExp(TOGGLE) });
    expect(box.checked).toBe(false);
    // Always sent, never omitted — the same "a full save states every field" discipline as
    // projectName/orderedByName, so the server is never left guessing what a missing key means.
    expect((await saveNewQuotationOnDeal()).hideProjectName).toBe(false);
  });

  it('ticking it on a new quotation creates the document with hideProjectName: true', async () => {
    api.dealQuotations.create.mockResolvedValue({ quotation: { id: 9 } });
    renderEditor('/quotations/new?ticket=18');

    fireEvent.click(await screen.findByRole('checkbox', { name: new RegExp(TOGGLE) }));
    expect((await saveNewQuotationOnDeal()).hideProjectName).toBe(true);
  });

  it('is offered on an inline-created quotation too (no deal exists yet)', async () => {
    renderEditor('/quotations/new');
    // Slice 2 flow A: /quotations/new opens on เลือกดีลที่มีอยู่; the inline card is the other branch.
    fireEvent.click(await screen.findByRole('button', { name: 'สร้างดีลใหม่' }));
    await screen.findByLabelText(/^ลูกค้า/);
    expect(screen.getByRole('checkbox', { name: new RegExp(TOGGLE) }).checked).toBe(false);
  });

  // Owner, 2026-10-12: "ไม่แสดงชื่อโครงการในใบเสนอราคา should be under the โครงการ input itself".
  // On an inline create that input is the โครงการ box of the ลูกค้าและโครงการ card; the tick box
  // used to sit at the end of the card below it, after ผู้ติดต่อ and หน่วยงาน / ผู้ออกแบบ.
  const nextFormControlAfter = (field) => {
    const controls = [...document.querySelectorAll('input, select, textarea')];
    return controls[controls.indexOf(field) + 1];
  };

  it('sits directly under the โครงการ box on an inline-created quotation', async () => {
    renderEditor('/quotations/new');
    // Slice 2 flow A: /quotations/new opens on เลือกดีลที่มีอยู่; the inline card is the other branch.
    fireEvent.click(await screen.findByRole('button', { name: 'สร้างดีลใหม่' }));
    const project = await screen.findByLabelText(/^โครงการ/);

    expect(nextFormControlAfter(project)).toBe(screen.getByRole('checkbox', { name: new RegExp(TOGGLE) }));
  });

  // Regression guard: already true before the inline form was moved.
  it('sits directly under the โครงการ field of an existing draft, and says what ticking it does', async () => {
    draft();
    renderEditor('/quotations/5');
    const box = await screen.findByRole('checkbox', { name: new RegExp(TOGGLE) });

    expect(nextFormControlAfter(screen.getByLabelText(/^โครงการ/))).toBe(box);
    expect(follows(box, document.getElementById('salesRepDisplayIdCard'))).toBe(true);
    // The consequence is on screen, not left to be discovered on the PDF.
    expect(screen.getByText(/ชื่อโครงการยังอยู่ในดีล/)).not.toBeNull();
  });

  // The wrong-way-round case: a document that already hides its project must not start printing
  // it again just because the rep saved something else.
  it('a draft that hides its project loads TICKED, and a save that never touches the box keeps it', async () => {
    draft({ hideProjectName: true });
    renderEditor('/quotations/5');

    const box = await screen.findByRole('checkbox', { name: new RegExp(TOGGLE) });
    await waitFor(() => expect(box.checked).toBe(true));
    fireEvent.change(screen.getByLabelText('โทรศัพท์ลูกค้า'), { target: { value: '02-123-4567' } });

    expect((await saveDraft()).hideProjectName).toBe(true);
  });

  it('unticking it on a draft sends an explicit false', async () => {
    draft({ hideProjectName: true });
    renderEditor('/quotations/5');

    const box = await screen.findByRole('checkbox', { name: new RegExp(TOGGLE) });
    await waitFor(() => expect(box.checked).toBe(true));
    fireEvent.click(box);

    expect((await saveDraft()).hideProjectName).toBe(false);
  });

  // "Hide" must never mean "delete": the name is still what the deal and the register show.
  it('ticking it leaves the project name itself untouched', async () => {
    draft({ hideProjectName: false });
    renderEditor('/quotations/5');

    const box = await screen.findByRole('checkbox', { name: new RegExp(TOGGLE) });
    await waitFor(() => expect(screen.getByLabelText(/^โครงการ/).value).toBe('บ้านเอกมัย ซอย 5'));
    fireEvent.click(box);

    const payload = await saveDraft();
    expect(payload.hideProjectName).toBe(true);
    expect(payload.projectName).toBe('บ้านเอกมัย ซอย 5');
    expect(screen.getByLabelText(/^โครงการ/).value).toBe('บ้านเอกมัย ซอย 5');
  });

  it('a draft from before this feature (no hideProjectName on the DTO) loads unticked', async () => {
    draft();
    renderEditor('/quotations/5');

    const box = await screen.findByRole('checkbox', { name: new RegExp(TOGGLE) });
    await waitFor(() => expect(screen.getByLabelText(/^โครงการ/).value).toBe('บ้านเอกมัย ซอย 5'));
    expect(box.checked).toBe(false);
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
    // V178: ยืนราคา is now a จำนวนวัน/ระบุวันที่ toggle group, not one labelled control — the
    // days select itself keeps id="validityDays" (targeted directly, same as elsewhere in this
    // suite) since the group's own aria-label also matches /^ยืนราคา/ and getByLabelText would
    // return the group `<div>` (no value setter) rather than the select.
    fireEvent.change(document.getElementById('validityDays'), { target: { value: '45' } });

    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
    await waitFor(() => expect(api.dealQuotations.create).toHaveBeenCalledTimes(1));
    first.unmount();

    renderEditor('/quotations/new?ticket=18');

    await screen.findByRole('button', { name: /เพิ่มรายการในตำแหน่งนี้/ });
    expect(screen.getByRole('button', { name: '50%' }).getAttribute('aria-pressed')).toBe('true');
    expect(screen.getByRole('button', { name: 'ชำระเมื่อส่งมอบ' }).getAttribute('aria-pressed')).toBe('true');
    expect(document.getElementById('validityDays')).toHaveProperty('value', '45');
  });

  // Item 4 ("ไม่รับมัดจำ", V181, owner ruling 2026-09-16, REVERSED same day): wrong-way-round case.
  // Ticking "ไม่รับมัดจำ" on one quotation must NOT make the NEXT new quotation start that way —
  // that would be a rep sending a quotation with no deposit by accident. Contrast with the test
  // just above: depositPercent/validityMode DO carry over, noDeposit deliberately never does.
  it('a new quotation does NOT start with no-deposit even after the previous quotation used it', async () => {
    api.dealQuotations.create.mockResolvedValue({ quotation: { id: 9 } });
    const first = renderEditor('/quotations/new?ticket=18');

    fireEvent.click(await screen.findByRole('button', { name: /เพิ่มรายการในตำแหน่งนี้/ }));
    await fillCompleteQuotationItem();
    fireEvent.click(screen.getByRole('checkbox', { name: /ไม่รับมัดจำ/ }));
    fireEvent.change(screen.getByLabelText('เงื่อนไขการชำระเงิน'), { target: { value: 'ON_DELIVERY' } });

    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
    await waitFor(() => expect(api.dealQuotations.create).toHaveBeenCalledTimes(1));
    first.unmount();

    renderEditor('/quotations/new?ticket=18');

    await screen.findByRole('button', { name: /เพิ่มรายการในตำแหน่งนี้/ });
    // The normal deposit controls are back: unticked checkbox, % chips visible, no payment-term
    // dropdown.
    expect(screen.getByRole('checkbox', { name: /ไม่รับมัดจำ/ }).checked).toBe(false);
    expect(screen.getByRole('button', { name: '30%' })).not.toBeNull();
    expect(screen.queryByLabelText('เงื่อนไขการชำระเงิน')).toBeNull();
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

// ── Item 2 ("ไม่เติม “คุณ”", V180) + Item 4 ("ไม่รับมัดจำ", V181) — owner ruling 2026-09-16 ──────
describe('QuotationEditorPage terms card — item 2 + item 4 (owner ruling 2026-09-16)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.customers.contacts.mockResolvedValue({ contacts: CONTACT_OPTIONS });
    api.dealQuotations.calculateLine.mockResolvedValue({ item: {} });
  });

  function existingDraftWithOneItem(overrides = {}) {
    return baseQuotation({
      contactId: 6, contactName: 'ณัฐพงศ์ ศรีวิไล',
      items: [groupedItem(0, 'ชั้น 1', 'Trilogy')],
      ...overrides,
    });
  }

  // Ruling 2026-10-18 (call 3): the "ไม่เติม “คุณ”" checkbox is back, under the ผู้ติดต่อ picker. It
  // opens as the draft stored it, and the save sends what it shows — no longer a hard-coded false.
  it('renders the "ไม่เติม “คุณ”" checkbox as the draft stored it, and the save follows it', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: existingDraftWithOneItem({ omitContactHonorific: true }) });
    const stored = renderEditor('/quotations/5');
    const storedBox = await screen.findByRole('checkbox', { name: /ไม่เติม/ });
    await waitFor(() => expect(storedBox.checked).toBe(true));
    stored.unmount();

    api.dealQuotations.get.mockResolvedValue({ quotation: existingDraftWithOneItem() });
    api.dealQuotations.update.mockResolvedValue({ quotation: existingDraftWithOneItem() });
    renderEditor('/quotations/5');

    await waitFor(() => expect(screen.getByLabelText(/^ตำแหน่งติดตั้งที่ 1/).value).toBe('ชั้น 1'));
    const box = screen.getByRole('checkbox', { name: /ไม่เติม/ });
    expect(box.checked).toBe(false);
    fireEvent.click(box);
    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalledTimes(1));
    const payload = api.dealQuotations.update.mock.calls[0][1];
    expect(payload.omitContactHonorific).toBe(true);
    // Wrong-way-round for ล้าง's clearContact: an untouched picker on a draft WITH a contact keeps
    // it — the save re-sends its id and never asks the server to blank it.
    expect(payload).toMatchObject({ contactId: 6 });
    expect(payload).not.toHaveProperty('clearContact');
  });

  it('ticking "ไม่รับมัดจำ" hides the % chips and ส่วนที่เหลือ, and shows the three payment terms', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: existingDraftWithOneItem() });
    renderEditor('/quotations/5');

    await screen.findByRole('button', { name: '30%' });
    expect(screen.getByRole('group', { name: 'ส่วนที่เหลือ' })).not.toBeNull();
    expect(screen.queryByLabelText('เงื่อนไขการชำระเงิน')).toBeNull();

    fireEvent.click(screen.getByRole('checkbox', { name: /ไม่รับมัดจำ/ }));

    expect(screen.queryByRole('button', { name: '30%' })).toBeNull();
    expect(screen.queryByRole('button', { name: '50%' })).toBeNull();
    expect(screen.queryByRole('group', { name: 'ส่วนที่เหลือ' })).toBeNull();
    const select = screen.getByLabelText('เงื่อนไขการชำระเงิน');
    expect(within(select).getAllByRole('option')).toHaveLength(4); // placeholder + 3 terms
  });

  it('saving with "ไม่รับมัดจำ" ticked sends depositPercent 0, clears remainderMode/creditDays, and sends the chosen term', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: existingDraftWithOneItem() });
    api.dealQuotations.update.mockResolvedValue({ quotation: existingDraftWithOneItem() });
    renderEditor('/quotations/5');

    await screen.findByRole('button', { name: '30%' });
    fireEvent.click(screen.getByRole('checkbox', { name: /ไม่รับมัดจำ/ }));
    fireEvent.change(screen.getByLabelText('เงื่อนไขการชำระเงิน'), { target: { value: 'ON_DELIVERY' } });

    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(api.dealQuotations.update).toHaveBeenCalledTimes(1));
    const payload = api.dealQuotations.update.mock.calls[0][1];
    expect(payload.depositPercent).toBe(0);
    expect(payload.remainderMode).toBeNull();
    expect(payload.creditDays).toBeNull();
    expect(payload.fullPaymentTerm).toBe('ON_DELIVERY');
  });

  // This is the ONLY thing this change leaves untouched (task scope: prefs, not editing). An
  // existing quotation always loads its own server-stored terms regardless of what is (or is not)
  // remembered for a NEW quotation — noDeposit here comes from `quotation.depositPercent === 0`,
  // never from quotationPrefs.js.
  it('loading an existing quotation saved with depositPercent 0 restores the ticked checkbox and its chosen term', async () => {
    api.dealQuotations.get.mockResolvedValue({
      quotation: existingDraftWithOneItem({ depositPercent: 0, remainderMode: null, creditDays: null, fullPaymentTerm: 'ON_DELIVERY' }),
    });
    renderEditor('/quotations/5');

    const checkbox = await screen.findByRole('checkbox', { name: /ไม่รับมัดจำ/ });
    expect(checkbox.checked).toBe(true);
    expect(screen.getByLabelText('เงื่อนไขการชำระเงิน')).toHaveProperty('value', 'ON_DELIVERY');
    expect(screen.queryByRole('button', { name: '30%' })).toBeNull();
  });

  it('typing 0 into the custom "อื่นๆ" มัดจำ input is rejected with a message pointing at the checkbox', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: existingDraftWithOneItem() });
    renderEditor('/quotations/5');

    await screen.findByRole('button', { name: '30%' });
    fireEvent.click(screen.getByRole('button', { name: 'อื่นๆ' }));
    fireEvent.change(screen.getByLabelText('มัดจำ %'), { target: { value: '0' } });

    expect(screen.getByText('ถ้าไม่รับมัดจำ ให้ติ๊ก “ไม่รับมัดจำ” แทนการพิมพ์ 0')).not.toBeNull();
  });

  it('a positive custom มัดจำ value (not 0) shows no rejection message', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: existingDraftWithOneItem() });
    renderEditor('/quotations/5');

    await screen.findByRole('button', { name: '30%' });
    fireEvent.click(screen.getByRole('button', { name: 'อื่นๆ' }));
    fireEvent.change(screen.getByLabelText('มัดจำ %'), { target: { value: '15' } });

    expect(screen.queryByText('ถ้าไม่รับมัดจำ ให้ติ๊ก “ไม่รับมัดจำ” แทนการพิมพ์ 0')).toBeNull();
  });
});
