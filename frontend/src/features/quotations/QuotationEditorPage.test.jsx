import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QuotationEditorPage } from './QuotationEditorPage.jsx';
import { api } from '../../api/index.js';
import { addDaysIso, bangkokTodayIso } from '../../utils/format.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      // parties / addParty / openForProject: deal parties (deal-party-model.md). Default to "none"
      // (see the top-level beforeEach below) so every older test sees today's single-party deal.
      tickets: { get: vi.fn(), create: vi.fn(), parties: vi.fn(), addParty: vi.fn(), removeParty: vi.fn(), openForProject: vi.fn() },
      // A NEW quotation on a picked deal reads the deal's pricing requests (one pricing route per
      // deal). None here: every test in this file is about the editor itself.
      pricingRequests: { listForTicket: vi.fn().mockResolvedValue({ items: [] }) },
      dealQuotations: {
        displayNameOptions: vi.fn(),
        get: vi.fn(),
        create: vi.fn(),
        update: vi.fn(),
        calculateLine: vi.fn(),
        approve: vi.fn(),
      },
      catalog: { prices: vi.fn() },
      // Consumed by DesignerPicker.jsx. getByCode default-resolves to undefined so the picker's
      // resolved-name hint effect finds nothing rather than throwing when a test never sets a unitCode.
      designers: { search: vi.fn(), getByCode: vi.fn().mockRejectedValue(new Error('not found')) },
      // Consumed by DealCustomerCard.jsx, which QuotationEditorPage renders instead of the read-only
      // deal summary whenever /quotations/new has no ?ticket= (see "inline deal creation" below).
      customers: {
        search: vi.fn(),
        create: vi.fn(),
        projects: vi.fn(),
        contacts: vi.fn(),
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

// Full DealQuotationDto shape, so the tests below don't each hand-roll the whole DTO.
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

beforeEach(() => {
  api.tickets.parties.mockResolvedValue({ parties: [] });
  api.tickets.openForProject.mockResolvedValue({ deals: [] });
});

describe('QuotationEditorPage item calc wiring', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // GET /api/tickets/{id} returns `{ ticket: TicketDto }` and TicketDto is ITSELF an envelope
    // (`{summary, items, events, quotation, quotations}`): two levels of wrapping. A flat fixture
    // would make canCreateDealQuotation read the wrong fields and every "owning sales rep" test pass
    // for the wrong reason. The page reads `.then((r) => r.ticket?.summary)` off this shape.
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
          // ผู้สั่งซื้อ is required and prefills from the deal's own contact; without these two
          // fields บันทึกร่าง would stay disabled and every assertion would test the wrong thing.
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
    // A Thai tile price is typed per ตร.ม., and a per-ตร.ม. price is only previewed on a row with an
    // area per piece, so that goes in first.
    fireEvent.change(screen.getByLabelText(/^แผ่น\/ตร\.ม\./), { target: { value: '1.39' } });
    const priceInput = screen.getByLabelText(/^ราคา\/ตร\.ม\./);
    fireEvent.change(priceInput, { target: { value: '350' } });

    // Not called synchronously -- the whole point of the debounce.
    expect(api.dealQuotations.calculateLine).not.toHaveBeenCalled();

    await waitFor(() => expect(api.dealQuotations.calculateLine).toHaveBeenCalledTimes(1), { timeout: 1000 });
    expect(api.dealQuotations.calculateLine).toHaveBeenCalledWith(expect.objectContaining({ listPriceSqm: 350 }), 'TH');

    expect(await screen.findByText('(พื้นที่ 36 ตร.ม. รวม 100 แผ่น)')).not.toBeNull();
    // getAllByText, not getByText: with only one item, its own line amount and the M6 provisional "รวมเป็นเงิน"
    // subtotal are the SAME number (before VAT) and both render -- this asserts the calculated amount reached the
    // screen at all, not which element shows it.
    expect(screen.getAllByText('฿35,000.00').length).toBeGreaterThanOrEqual(1);
  });

  // A per-row request sequence discards a stale response instead of letting it clobber a newer edit's
  // result: two real in-flight calculate-line calls (one per edit, each past its 300ms debounce)
  // settling OUT OF ORDER.
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

    // The OLDER request settles LAST -- it must be discarded outright, including its own (irrelevant) echoed input
    // fields, not merged over what the newer response already set.
    resolveFirst({ item: { lineAmount: 1000, calculationLine: 'older', unitPrice: 67.24, listPriceSqm: 100 } });
    await new Promise((resolve) => { setTimeout(resolve, 50); }); // let any wrongly-queued update flush

    expect(screen.getByText('newer')).not.toBeNull();
    expect(screen.queryByText('older')).toBeNull();
    expect(priceInput.value).toBe('200');
    // ...including the one input-named field a response may write: the per-แผ่น figure under the box
    // is the NEWER request's, not the stale one's.
    expect(screen.getByTestId('list-per-piece-0').textContent).toBe('= ฿134.47/แผ่น (ก่อน VAT)');
  });

  // Not tested: a rep typing โครงการ during the ticketQuery window being clobbered by this effect.
  // That window is unreachable: the effect only runs when `ticket?.id` is set and `!id`, and on that
  // path `isEditable` gates on `canCreateDealQuotation(user, ticket)` using the SAME pending ticket,
  // so the page shows "ไม่มีสิทธิ์สร้างใบเสนอราคา" instead of the form and โครงการ does not exist to
  // type into. The defensive `t.projectName ? t : {...}` guard in the effect is kept anyway.
});

describe('QuotationEditorPage new-quotation authorization (#M2)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // GET /api/tickets/{id} returns the two-level envelope `{ ticket: { summary, items, ... } }`;
    // see the first beforeEach in this file.
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
          // ผู้สั่งซื้อ is required and prefills from the deal's own contact; without these two
          // fields บันทึกร่าง would stay disabled and every assertion would test the wrong thing.
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

  // A plain `sales` user on their OWN deal must reach the editor. The ticketQuery envelope bug broke
  // exactly this: `ticket.createdById` read `undefined` off a flat `{ ticket: {...} }` fixture, so
  // canCreateDealQuotation was false even for the owner. Reverting the `r.summary` unwrap turns it red.
  it('the owning sales rep on their own deal can create -- sees the editor form', async () => {
    renderEditor('/quotations/new?ticket=18', salesUser);

    // findBy, not getBy after the heading: the heading renders before the ticket query resolves (it doesn't depend
    // on `ticket`), so asserting straight off it races the canCreateDealQuotation(user, ticket) check, which DOES
    // need the ticket loaded.
    expect(await screen.findByRole('button', { name: /เพิ่มรายการ/ })).not.toBeNull();
    // Header renders real ticket-derived values, not "-" -- proves `ticket.customerName` is no longer reading off
    // an envelope key (`r.ticket`) the backend never sends. It appears twice (the page subtitle AND the
    // ข้อมูลลูกค้าและผู้ขาย panel), so this asserts presence, not singularity.
    expect(screen.getAllByText(/บริษัท แฟชั่นไอส์แลนด์ จำกัด/).length).toBeGreaterThan(0);
  });
});

// Every field validateQuotationItem requires (quotationMeta.js), filled by label. Shared by every
// test that needs a genuinely complete row, so a change to what "complete" means has one call site.
async function fillCompleteQuotationItem() {
  fireEvent.change(screen.getByLabelText(/^รุ่น \/ ค้นหาแคตตาล็อก/), { target: { value: 'Trilogy' } });
  fireEvent.change(screen.getByLabelText(/^สี/), { target: { value: 'Ivory' } });
  fireEvent.change(screen.getByLabelText(/^ผิว/), { target: { value: 'Lappato' } });
  fireEvent.change(screen.getByLabelText(/^ขนาด/), { target: { value: '60x120' } });
  fireEvent.change(screen.getByLabelText(/^ความหนา/), { target: { value: '10' } });
  // แผ่น/ตร.ม. shows/accepts the RECIPROCAL of `item.sqmPerPiece`; the typed value only needs to be
  // positive for completeness, not the exact reciprocal of any other fixture's figure.
  fireEvent.change(screen.getByLabelText(/^แผ่น\/ตร\.ม\./), { target: { value: '1.39' } });
  fireEvent.change(screen.getByLabelText(/^แผ่น\/กล่อง/), { target: { value: '3' } });
  // Per ตร.ม. including VAT on a Thai document.
  fireEvent.change(screen.getByLabelText(/^ราคา\/ตร\.ม\./), { target: { value: '850' } });
  fireEvent.change(screen.getByLabelText(/^จำนวน/), { target: { value: '36' } });
}

describe('QuotationEditorPage client-side validation (#M4, item completeness owner ruling 2026-09-10)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // GET /api/tickets/{id} returns the two-level envelope `{ ticket: { summary, items, ... } }`;
    // see the first beforeEach in this file.
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
          // ผู้สั่งซื้อ is required and prefills from the deal's own contact; without these two
          // fields บันทึกร่าง would stay disabled and every assertion would test the wrong thing.
          customerId: 5, contactId: 6, contactName: 'ณัฐพงศ์ ศรีวิไล',
        },
      },
    });
  });

  it('disables บันทึกร่าง and lists a per-row "ขาด ..." summary until every item field is complete', async () => {
    renderEditor('/quotations/new?ticket=18');
    // findBy: waits out the ticket query so canCreateDealQuotation has what it needs to render the editable form
    // (and its บันทึกร่าง button) at all.
    await screen.findByRole('button', { name: 'บันทึกร่าง' });

    // 0 items yet. .disabled, not toBeDisabled: this project does not wire up jest-dom's matchers
    // (see LoginPage.test.jsx).
    //
    // The gate knows why it is closed (the button's title says so) but the red checklist does not
    // greet an untouched page. Asserted both ways round: "reason computed" and "reason not shouted
    // yet" are different claims, and only the pair rules out having deleted the validation.
    expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(true);
    expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).title)
      .toContain('ต้องมีรายการสินค้าอย่างน้อย 1 รายการ');
    expect(screen.queryByText('ต้องมีรายการสินค้าอย่างน้อย 1 รายการ')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /เพิ่มรายการ/ }));
    // An item exists now, but every required field is still blank -- the per-row summary names ALL of them
    // (quotationMeta.test.js pins the exact wording; this only checks it renders).
    expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(true);
    expect(screen.getByText(/รายการที่ 1: ขาด/)).not.toBeNull();

    await fillCompleteQuotationItem();

    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));
    expect(screen.queryByText('ต้องมีรายการสินค้าอย่างน้อย 1 รายการ')).toBeNull();
    expect(screen.queryByText(/รายการที่ 1: ขาด/)).toBeNull();
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
    // The modal names the quotation and its grand total -- scoped to the dialog since the number also appears in
    // the page's own .
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

// /quotations/new with NO ?ticket= renders DealCustomerCard instead of the read-only ticket-derived
// summary, and the first บันทึกร่าง has to mint the ticket itself (api.tickets.create) before it can
// create the quotation on top of it (api.dealQuotations.create).
describe('QuotationEditorPage inline deal creation', () => {
  const testCustomer = { id: 1, name: 'บริษัท ก้าวหน้า คอนสตรัคชั่น จำกัด', taxId: '0105565012345' };
  const testProject = { id: 1, customerId: 1, name: 'โครงการ Central Ladprao ชั้น B1' };

  // Renders the real editor at /quotations/new with no ticket query param, plus a stand-in for /quotations/:id
  // that just names the id it landed on -- avoids needing api.dealQuotations.get to resolve just to observe that
  // navigate() fired to the right place.
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
    // /quotations/new opens on "เลือกดีลที่มีอยู่"; this block is about the OTHER branch, so every
    // case starts by choosing สร้างดีลใหม่. (QuotationEditorPage.recipient.test.jsx covers the pick branch.)
    fireEvent.click(screen.getByRole('button', { name: 'สร้างงานใหม่' }));
    return { ...result, showToast, queryClient };
  }

  // ช่องทางรับงาน is required to save (UNSPECIFIED is never a choice), so the helper picks one unless a test is
  // specifically about the gate.
  function pickEntryChannel() {
    fireEvent.click(screen.getByRole('button', { name: /ผู้ออกแบบนำงานมา/ }));
  }

  async function selectCustomerAndProject({ channel = true } = {}) {
    api.customers.search.mockResolvedValue({ customers: [testCustomer] });
    api.customers.projects.mockResolvedValue({ projects: [testProject] });
    // A direct quotation needs its ผู้รับใบเสนอราคา before it can be saved; incidental to every
    // case here, so it is chosen alongside the customer and project.
    fireEvent.click(screen.getByRole('radio', { name: 'ผู้ออกแบบ' }));

    fireEvent.change(await screen.findByLabelText(/^ลูกค้า/), { target: { value: 'ก้าวหน้า' } });
    // The typeahead popup is a listbox: role="option".
    fireEvent.mouseDown(await screen.findByRole('option', { name: new RegExp(testCustomer.name) }));

    await waitFor(() => expect(screen.getAllByText(testCustomer.name).length).toBeGreaterThan(0)); // the chip that replaces the search input
    await waitFor(() => expect(api.customers.projects).toHaveBeenCalledWith(testCustomer.id));

    // โครงการ is a type-ahead combobox, not a <select>: open it and pick the option, same
    // role="option" listbox shape as ลูกค้า above.
    fireEvent.focus(await screen.findByLabelText(/^โครงการ/));
    fireEvent.mouseDown(await screen.findByRole('option', { name: new RegExp(testProject.name) }));
    if (channel) pickEntryChannel();
  }

  // Reuses the SAME complete-item helper every other editor visit fills with, so this block does not
  // gate บันทึกร่าง on a looser bar than the rest of the suite.
  async function fillOneValidItem() {
    fireEvent.click(screen.getByRole('button', { name: /เพิ่มรายการ/ }));
    await fillCompleteQuotationItem();
  }

  beforeEach(() => {
    vi.clearAllMocks();
  });

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
      // The deal never carries a ผู้ติดต่อ: it is picked per quotation and travels on
      // dealQuotations.create (quotationOrderedByContact.test.jsx pins that).
      contactId: null,
      entryChannel: 'DESIGNER_LED',
      priority: 'NORMAL',
      items: [],
      nextFollowUpAt: expectedFollowUp,
      // The inline-created deal is a quotation-only container, not a pipeline deal, until an
      // APPROVED quotation on it is promoted.
      quotationOnly: true,
    }));

    // The ticket envelope's id lives at `.summary.id`, not `.ticket.id`.
    // The price travels as typed (per ตร.ม. including VAT) with no per-แผ่น price beside it: the
    // server derives that one.
    await waitFor(() => expect(api.dealQuotations.create).toHaveBeenCalledWith(42, expect.objectContaining({
      items: expect.arrayContaining([expect.objectContaining({ listPriceSqm: 850, unitPrice: null, model: 'Trilogy' })]),
    })));

    expect(await screen.findByText('navigated-to-99')).not.toBeNull();
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

    // Retry: the SAME ticket id is reused (createdTicketId state), so this must NOT call tickets.create a second
    // time -- only dealQuotations.create, again, on ticket 42.
    api.dealQuotations.create.mockResolvedValueOnce({ quotation: { id: 99 } });
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(api.dealQuotations.create).toHaveBeenCalledTimes(2));
    expect(api.dealQuotations.create).toHaveBeenNthCalledWith(2, 42, expect.anything());
    expect(api.tickets.create).toHaveBeenCalledTimes(1); // still just the one ticket
    expect(await screen.findByText('navigated-to-99')).not.toBeNull();
  });

  describe('the deal a retry reuses is the one made for the customer and project on the form', () => {
    const otherCustomer = { id: 2, name: 'บริษัท รุ่งเรือง ดีเวลลอปเมนท์ จำกัด', taxId: '0105565067890' };
    // The same id as testProject, on purpose: an id is whatever the server gave, and a different
    // customer has to be enough on its own.
    const otherCustomersProject = { id: 1, customerId: 2, name: 'โครงการ The Mall บางกะปิ' };

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
      // A new deal's recipient and a real ช่องทางรับงาน gate บันทึกร่าง.
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
  });
});

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

// A saved item already placed under a ตำแหน่งติดตั้ง group.
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
});

// ผู้ติดต่อ is a picker over the deal customer's contacts, on both entry paths. Optional (never a
// blocker), empty on every new quotation (no pre-fill, not even from the deal's own contact), sent
// as `contactId`; the server writes the ผู้สั่งซื้อ signature name from it, so the editor sends no
// `orderedByName`. What the picker does (list, search, add modal) is QuotationContactPicker.test.jsx's;
// this block is how the editor mounts and feeds it.

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
    expect(field.textContent).toContain('- เลือกผู้ติดต่อ -'); // no pre-fill

    fireEvent.click(screen.getByRole('button', { name: /เพิ่มรายการในตำแหน่งนี้/ }));
    await fillCompleteQuotationItem();
    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));

    await waitFor(() => expect(api.dealQuotations.create).toHaveBeenCalledTimes(1));
    const sent = api.dealQuotations.create.mock.calls[0][1];
    expect(sent.contactId).toBeNull();
    expect(sent).not.toHaveProperty('orderedByName');
  });
});

// โครงการ stays REQUIRED on the deal (a neutral name is fine, e.g. "บ้านเอกมัย ซอย 5") and the rep
// chooses per document whether it PRINTS. One tick box in the same voice as ไม่รับมัดจำ: unticked is
// the standard document, ticked leaves the "Project : …" line off the customer's copy while the name
// stays on the deal and in this editor.
describe('QuotationEditorPage ไม่แสดงชื่อโครงการ (sales feedback #5, 2026-10-06, V192.1)', () => {
  const TOGGLE = 'ไม่แสดงชื่อโครงการในใบเสนอราคา';

  beforeEach(() => {
    vi.clearAllMocks();
    api.customers.contacts.mockResolvedValue({ contacts: CONTACT_OPTIONS });
    api.tickets.get.mockResolvedValue(ticketFixture({ projectName: 'บ้านเอกมัย ซอย 5' }));
    api.dealQuotations.calculateLine.mockResolvedValue({ item: {} });
  });

  async function saveNewQuotationOnDeal() {
    fireEvent.click(screen.getByRole('button', { name: /เพิ่มรายการในตำแหน่งนี้/ }));
    await fillCompleteQuotationItem();
    await waitFor(() => expect(screen.getByRole('button', { name: 'บันทึกร่าง' }).disabled).toBe(false));
    fireEvent.click(screen.getByRole('button', { name: 'บันทึกร่าง' }));
    await waitFor(() => expect(api.dealQuotations.create).toHaveBeenCalledTimes(1));
    return api.dealQuotations.create.mock.calls[0][1];
  }

  it('ticking it on a new quotation creates the document with hideProjectName: true', async () => {
    api.dealQuotations.create.mockResolvedValue({ quotation: { id: 9 } });
    renderEditor('/quotations/new?ticket=18');

    fireEvent.click(await screen.findByRole('checkbox', { name: new RegExp(TOGGLE) }));
    expect((await saveNewQuotationOnDeal()).hideProjectName).toBe(true);
  });
});

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

  it('typing 0 into the custom "อื่นๆ" มัดจำ input is rejected with a message pointing at the checkbox', async () => {
    api.dealQuotations.get.mockResolvedValue({ quotation: existingDraftWithOneItem() });
    renderEditor('/quotations/5');

    await screen.findByRole('button', { name: '30%' });
    fireEvent.click(screen.getByRole('button', { name: 'อื่นๆ' }));
    fireEvent.change(screen.getByLabelText('มัดจำ %'), { target: { value: '0' } });

    expect(screen.getByText('ถ้าไม่รับมัดจำ ให้ติ๊ก “ไม่รับมัดจำ” แทนการพิมพ์ 0')).not.toBeNull();
  });
});
