import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { render, screen, fireEvent, within, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AccountFinancePage } from './AccountFinancePage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  const { DEAL_STAGE_CATALOG } = await import('../../data/dealStageCatalog.js');
  return {
    ...actual,
    api: {
      tickets: { list: vi.fn() },
      meta: { dealStages: vi.fn().mockResolvedValue(DEAL_STAGE_CATALOG) },
      dashboard: { summary: vi.fn() },
      receiptImports: { latest: vi.fn() },
    },
  };
});

const accountUser = { role: 'account', name: 'บัญชี ทดสอบ', employeeId: 3 };
const ceoUser = { role: 'ceo', name: 'ซีอีโอ ทดสอบ', employeeId: 1 };

const depositTicket = {
  id: 201, code: 'PR-2026-0201', title: 'โครงการดี', customerName: 'บริษัท ดี จำกัด',
  status: 'quotation_issued', paymentStatus: 'DEPOSIT_NOTICE_ISSUED', fulfillmentStatus: null,
  lifecycle: 'ACTIVE', salesStage: 'NEGOTIATION', depositPolicy: 'REQUIRED',
  closeConfirmedAt: null, invoiceOnFile: false,
  amountPayable: 80000, amountPaid: 0, amountOutstanding: 80000,
  overdue: false, paymentDueDate: null, updatedAt: '2026-07-10T00:00:00.000Z',
};

const finalPaymentTicket = {
  id: 202, code: 'PR-2026-0202', title: 'โครงการอี', customerName: 'บริษัท อี จำกัด',
  status: 'quotation_issued', paymentStatus: 'AWAITING_FINAL_PAYMENT', fulfillmentStatus: null,
  lifecycle: 'ACTIVE', salesStage: 'DELIVERY_SCHEDULING', depositPolicy: 'REQUIRED',
  closeConfirmedAt: null, invoiceOnFile: false,
  amountPayable: 120000, amountPaid: 60000, amountOutstanding: 60000,
  overdue: false, paymentDueDate: null, updatedAt: '2026-07-11T00:00:00.000Z',
};

const closedPaidTicket = {
  id: 203, code: 'PR-2026-0203', title: 'โครงการเอฟ', customerName: 'บริษัท เอฟ จำกัด',
  status: 'quotation_issued', paymentStatus: 'FULLY_PAID', fulfillmentStatus: 'FULLY_DELIVERED',
  lifecycle: 'ACTIVE', salesStage: 'CLOSED_PAID', depositPolicy: 'REQUIRED',
  closeConfirmedAt: '2026-07-01T00:00:00.000Z', invoiceOnFile: true,
  amountPayable: 90000, amountPaid: 90000, amountOutstanding: 0,
  overdue: false, paymentDueDate: null, updatedAt: '2026-07-01T00:00:00.000Z',
};


const idleTicket = {
  id: 204, code: 'PR-2026-0204', title: 'โครงการไอเดิล', customerName: 'บริษัท ไอเดิล จำกัด',
  status: 'quotation_issued', paymentStatus: 'CUSTOMER_CONFIRMED', fulfillmentStatus: null,
  lifecycle: 'ACTIVE', salesStage: 'PROCUREMENT', depositPolicy: 'REQUIRED',
  closeConfirmedAt: null, invoiceOnFile: false,
  amountPayable: 50000, amountPaid: 0, amountOutstanding: 50000,
  overdue: false, paymentDueDate: null, updatedAt: '2026-07-05T00:00:00.000Z',
};

const overdueTicket = {
  id: 205, code: 'PR-2026-0205', title: 'โครงการโอเวอร์', customerName: 'บริษัท โอเวอร์ จำกัด',
  status: 'quotation_issued', paymentStatus: 'AWAITING_FINAL_PAYMENT', fulfillmentStatus: null,
  lifecycle: 'ACTIVE', salesStage: 'DELIVERED', depositPolicy: 'REQUIRED',
  closeConfirmedAt: null, invoiceOnFile: false,
  amountPayable: 30000, amountPaid: 0, amountOutstanding: 30000,
  overdue: true, paymentDueDate: '2026-09-01', updatedAt: '2026-07-12T00:00:00.000Z',
};

const closeReadyTicket = {
  id: 206, code: 'PR-2026-0206', title: 'โครงการปิด', customerName: 'บริษัท ปิดงาน จำกัด',
  status: 'quotation_issued', paymentStatus: 'FULLY_PAID', fulfillmentStatus: 'FULLY_DELIVERED',
  lifecycle: 'ACTIVE', salesStage: 'DELIVERED', depositPolicy: 'REQUIRED',
  closeConfirmedAt: null, invoiceOnFile: true,
  amountPayable: 596000, amountPaid: 596000, amountOutstanding: 0,
  overdue: false, paymentDueDate: null, updatedAt: '2026-07-13T00:00:00.000Z',
};

function LocationProbe() {
  const location = useLocation();
  return <p data-testid="location">{location.pathname + location.search}</p>;
}

function renderPage(initial = '/finance', user = accountUser) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initial]}>
        <Routes>
          <Route path="/finance" element={<AccountFinancePage user={user} showToast={vi.fn()} />} />
          <Route path="/finance/deals/:id" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const listReturns = (open, closedPaid = []) => api.tickets.list.mockImplementation((params = {}) => (
  params.salesStage === 'CLOSED_PAID' ? Promise.resolve({ tickets: closedPaid }) : Promise.resolve({ tickets: open })));

const rowLink = (name) => screen.getByRole('link', { name: new RegExp(name) });
const group = (label) => screen.getByRole('region', { name: label });
const groupRow = (id) => screen.getByTestId(`fin-work-row-${id}`);

describe('AccountFinancePage — grouped worklist (design a-fin-land)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.dashboard.summary.mockResolvedValue({ summary: { financeOutstanding: 424242.42 } });
    api.receiptImports.latest.mockResolvedValue({ uploadEnabled: false, latest: null, openUnmatched: 0 });
    listReturns([depositTicket, finalPaymentTicket, idleTicket, closeReadyTicket, overdueTicket], [closedPaidTicket]);
  });

  it('shows the drawn subtitle under the title', async () => {
    renderPage();
    expect(await screen.findByText('รับเงินมาจากไฟล์ Express · ที่เหลือคือสิ่งที่ต้องทำต่อ')).not.toBeNull();
    expect(screen.getByRole('heading', { name: 'งานการเงิน' })).not.toBeNull();
  });

  it('renders the three drawn groups in order, each with its hint and its row count', async () => {
    renderPage();
    await screen.findByText('บริษัท ดี จำกัด');
    const sections = screen.getAllByRole('region').filter((r) => r.dataset.testid?.startsWith('fin-group-'));
    expect(sections.map((s) => s.getAttribute('aria-label'))).toEqual(['ยืนยันพร้อมปิดงาน', 'ติดตามชำระ', 'บันทึกใบกำกับ']);
    expect(within(group('ยืนยันพร้อมปิดงาน')).getByText('ส่งของครบและรับเงินครบแล้ว')).not.toBeNull();
    expect(within(group('ติดตามชำระ')).getByText('ออกใบแจ้งหนี้แล้ว ยังไม่พบเงินในไฟล์ล่าสุด')).not.toBeNull();
    expect(within(group('บันทึกใบกำกับ')).getByText('ใช้คำนวณคำขอค่าคอม (เหมือนเดิม)')).not.toBeNull();
    expect(within(group('ยืนยันพร้อมปิดงาน')).getByTestId('group-count').textContent).toBe('1');
    expect(within(group('ติดตามชำระ')).getByTestId('group-count').textContent).toBe('3');
    expect(within(group('บันทึกใบกำกับ')).getByTestId('group-count').textContent).toBe('1');
  });

  it('each deal appears in exactly one group; a deal with no finance step appears in none', async () => {
    renderPage();
    await screen.findByText('บริษัท ดี จำกัด');
    expect(within(group('ยืนยันพร้อมปิดงาน')).getByText('บริษัท ปิดงาน จำกัด')).not.toBeNull();
    ['บริษัท โอเวอร์ จำกัด', 'บริษัท ดี จำกัด', 'บริษัท อี จำกัด'].forEach((n) => expect(within(group('ติดตามชำระ')).getByText(n)).not.toBeNull());
    expect(within(group('บันทึกใบกำกับ')).getByText('บริษัท เอฟ จำกัด')).not.toBeNull();
    ['บริษัท ปิดงาน จำกัด', 'บริษัท โอเวอร์ จำกัด', 'บริษัท ดี จำกัด', 'บริษัท อี จำกัด', 'บริษัท เอฟ จำกัด']
      .forEach((n) => expect(screen.getAllByText(n)).toHaveLength(1));
    expect(screen.queryByText('บริษัท ไอเดิล จำกัด')).toBeNull();
  });

  it('inside ติดตามชำระ the existing order holds: overdue first, then due date, then outstanding descending', async () => {
    renderPage();
    await screen.findByText('บริษัท ดี จำกัด');
    const rows = within(group('ติดตามชำระ')).getAllByTestId(/^fin-work-row-/);
    expect(rows.map((r) => r.dataset.testid)).toEqual(['fin-work-row-205', 'fin-work-row-201', 'fin-work-row-202']);
  });

  it('each row carries its group\'s ONE button, which links to the deal\'s finance page; no other control in the row', async () => {
    renderPage();
    await screen.findByText('บริษัท ดี จำกัด');
    const expectations = [
      [206, 'ยืนยันพร้อมปิดงาน'], [205, 'เปิด'], [201, 'เปิด'], [202, 'เปิด'], [203, 'บันทึกใบกำกับ'],
    ];
    expectations.forEach(([id, label]) => {
      const links = within(groupRow(id)).getAllByRole('link');
      expect(links).toHaveLength(1);
      expect(links[0].textContent).toBe(label);
      expect(links[0].getAttribute('href')).toBe(`/finance/deals/${id}`);
      expect(within(groupRow(id)).queryByRole('button')).toBeNull();
    });
  });

  it('clicking a row button goes to the existing deal finance page (where the modal lives)', async () => {
    renderPage();
    await screen.findByText('บริษัท ปิดงาน จำกัด');
    fireEvent.click(within(groupRow(206)).getByRole('link'));
    expect((await screen.findByTestId('location')).textContent).toBe('/finance/deals/206');
  });

  it('rows show the figures the server sent, unchanged: received for close/invoice, outstanding for chase', async () => {
    renderPage();
    await screen.findByText('บริษัท ดี จำกัด');
    expect(within(groupRow(206)).getByText('รับครบ ฿596,000.00')).not.toBeNull();
    expect(within(groupRow(203)).getByText('ยอดรับจริง ฿90,000.00')).not.toBeNull();
    expect(within(groupRow(201)).getByText('คงค้าง ฿80,000.00')).not.toBeNull();
    expect(within(groupRow(202)).getByText('คงค้าง ฿60,000.00')).not.toBeNull();
    expect(within(groupRow(205)).getByText('คงค้าง ฿30,000.00')).not.toBeNull();
    // the deal code is the row's id column
    expect(within(groupRow(201)).getByText('PR-2026-0201')).not.toBeNull();
  });

  it('a deposit row shows the server\'s depositDue as มัดจำ (the drawn label), not the whole outstanding', async () => {
    listReturns([{ ...depositTicket, depositDue: 37450 }, { ...depositTicket, id: 207, code: 'PR-2026-0207', customerName: 'บริษัท ไม่มีมัดจำ จำกัด', depositDue: null }]);
    renderPage();
    await screen.findByText('บริษัท ดี จำกัด');
    expect(within(groupRow(201)).getByText('มัดจำ ฿37,450.00')).not.toBeNull();
    expect(within(groupRow(201)).queryByText(/คงค้าง/)).toBeNull();
    // no depositDue on the row (older payload): fall back to the outstanding the page always showed, never a guess
    expect(within(groupRow(207)).getByText('คงค้าง ฿80,000.00')).not.toBeNull();
  });

  it('a chase row says which step waits on the deal page, and flags overdue / shows the due date', async () => {
    listReturns([depositTicket, { ...finalPaymentTicket, paymentDueDate: '2026-11-20' }, overdueTicket]);
    renderPage();
    await screen.findByText('บริษัท ดี จำกัด');
    expect(within(groupRow(201)).getByText('ยืนยันรับมัดจำ')).not.toBeNull();
    expect(within(groupRow(202)).getByText('รับชำระส่วนที่เหลือ')).not.toBeNull();
    expect(within(groupRow(202)).getByText('ครบกำหนด 20 พ.ย. 2569')).not.toBeNull();
    expect(within(groupRow(205)).getByText('เกินกำหนดชำระ 1 ก.ย. 2569')).not.toBeNull();
    // the overdue row does not repeat its group's own name as a step
    expect(within(groupRow(205)).queryByText('ติดตามชำระ')).toBeNull();
  });

  it('CEO (wrong way round): deposit / final-payment deals — account-only steps today — get no row and no button', async () => {
    renderPage('/finance', ceoUser);
    await screen.findByText('บริษัท โอเวอร์ จำกัด');
    expect(screen.queryByTestId('fin-work-row-201')).toBeNull();
    expect(screen.queryByTestId('fin-work-row-202')).toBeNull();
    expect(screen.queryByText('บริษัท ดี จำกัด')).toBeNull();
    expect(screen.queryByText('บริษัท อี จำกัด')).toBeNull();
    expect(within(group('ติดตามชำระ')).getByTestId('group-count').textContent).toBe('1');
    // what the CEO is offered today is unchanged: overdue chase, close-ready, invoice
    expect(within(groupRow(205)).getByRole('link').textContent).toBe('เปิด');
    expect(within(groupRow(206)).getByRole('link').textContent).toBe('ยืนยันพร้อมปิดงาน');
    expect(within(groupRow(203)).getByRole('link').textContent).toBe('บันทึกใบกำกับ');
  });

  it('CEO still reaches the deposit / final-payment deals read-only in ทั้งหมด, with no next step shown', async () => {
    renderPage('/finance?view=all', ceoUser);
    const link = await screen.findByRole('link', { name: /บริษัท ดี จำกัด/ });
    expect(within(link).queryByText('ยืนยันรับมัดจำ')).toBeNull();
    expect(within(rowLink('บริษัท อี จำกัด')).queryByText('รับชำระส่วนที่เหลือ')).toBeNull();
  });

  it('an empty group stays visible with its count 0 and says ไม่มีรายการ', async () => {
    listReturns([depositTicket]);
    renderPage();
    await screen.findByText('บริษัท ดี จำกัด');
    expect(within(group('ยืนยันพร้อมปิดงาน')).getByTestId('group-count').textContent).toBe('0');
    expect(within(group('ยืนยันพร้อมปิดงาน')).getByText('ไม่มีรายการ')).not.toBeNull();
    expect(within(group('บันทึกใบกำกับ')).getByText('ไม่มีรายการ')).not.toBeNull();
    expect(within(group('ติดตามชำระ')).queryByText('ไม่มีรายการ')).toBeNull();
  });

  it('when no group has a row, one calm line replaces the groups and points to ทั้งหมด', async () => {
    listReturns([idleTicket]);
    renderPage();
    expect(await screen.findByText('ไม่มีงานการเงินที่ต้องทำตอนนี้')).not.toBeNull();
    expect(screen.queryByRole('region', { name: 'ติดตามชำระ' })).toBeNull();
  });

  it('the ค้างรับทั้งหมด block is not on the grouped view (the drawing drops it) but stays in ทั้งหมด', async () => {
    renderPage();
    await screen.findByText('บริษัท ดี จำกัด');
    expect(screen.queryByText('ค้างรับทั้งหมด')).toBeNull();
    fireEvent.click(screen.getByRole('tab', { name: /ทั้งหมด/ }));
    expect(await screen.findByText('ค้างรับทั้งหมด')).not.toBeNull();
    expect(screen.getByText('฿424,242.42')).not.toBeNull();
  });

  it('no filter selects on the grouped view (they live in ทั้งหมด)', async () => {
    renderPage();
    await screen.findByText('บริษัท ดี จำกัด');
    expect(screen.queryByLabelText('ขั้นการเงิน')).toBeNull();
    expect(screen.queryByLabelText('ขั้นตอนที่ต้องทำ')).toBeNull();
  });

  it('labels on this page say งาน, never ดีล — grouped view, ทั้งหมด view and the empty states', async () => {
    const { container, unmount } = renderPage();
    await screen.findByText('บริษัท ดี จำกัด');
    expect(container.textContent).not.toContain('ดีล');
    fireEvent.click(screen.getByRole('tab', { name: /ทั้งหมด/ }));
    await screen.findByText('บริษัท ไอเดิล จำกัด');
    expect(container.textContent).not.toContain('ดีล');
    expect(container.innerHTML).not.toContain('ดีล'); // aria-labels and sr-only text too
    unmount();
    listReturns([idleTicket]);
    const empty = renderPage('/finance?milestone=1');
    await screen.findByText(/ในขั้นนี้/);
    expect(empty.container.innerHTML).not.toContain('ดีล');
  });

  describe('Express upload card (unchanged, still gated by the server flag)', () => {
    it('flag on: the card renders above the worklist', async () => {
      api.receiptImports.latest.mockResolvedValue({ uploadEnabled: true, latest: null, openUnmatched: 0 });
      renderPage();
      expect(await screen.findByRole('heading', { name: 'อัปโหลดไฟล์รับเงินจาก Express' })).not.toBeNull();
      const card = screen.getByRole('region', { name: 'อัปโหลดไฟล์รับเงินจาก Express' });
      await screen.findByText('บริษัท ดี จำกัด');
      expect(card.compareDocumentPosition(group('ยืนยันพร้อมปิดงาน')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    it('flag off: no card, and the worklist still renders', async () => {
      renderPage();
      await screen.findByText('บริษัท ดี จำกัด');
      await waitFor(() => expect(api.receiptImports.latest).toHaveBeenCalled());
      expect(screen.queryByRole('heading', { name: 'อัปโหลดไฟล์รับเงินจาก Express' })).toBeNull();
    });
  });
});

// The pre-existing flat table, now the "ทั้งหมด" view (and the target of filter deep links). Tests below are the
// page's original suite; where one changed, the comment says why.
describe('AccountFinancePage — ทั้งหมด table and filter deep links', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.dashboard.summary.mockResolvedValue({ summary: { financeOutstanding: 424242.42 } });
    api.receiptImports.latest.mockResolvedValue({ uploadEnabled: false, latest: null, openUnmatched: 0 });
    listReturns([depositTicket, finalPaymentTicket, idleTicket], [closedPaidTicket]);
  });

  it('defaults to "ต้องดำเนินการ": only rows with a next account action, in the urgent/due/outstanding order', async () => {
    renderPage();
    await screen.findByText('บริษัท ดี จำกัด');
    expect(screen.getByText('บริษัท อี จำกัด')).not.toBeNull();
    expect(screen.getByText('บริษัท เอฟ จำกัด')).not.toBeNull();
    // idle: quotation-stage deal, no account action -> history only
    expect(screen.queryByText('บริษัท ไอเดิล จำกัด')).toBeNull();
  });

  it('the CLOSED_PAID row action reads บันทึกใบกำกับ and the old ออกค่าคอม wording renders nowhere', async () => {
    const { container } = renderPage();
    await screen.findByText('บริษัท เอฟ จำกัด');
    expect(screen.getAllByText('บันทึกใบกำกับ').length).toBeGreaterThan(0);
    expect(container.textContent).not.toContain('ออกค่าคอม');
    expect(screen.queryByText('บันทึกใบกำกับ + ออกค่าคอม')).toBeNull();
  });

  // Adapted: the sort is now asserted on the ทั้งหมด table (?view=all), where rows are still one link each; the
  // grouped view's in-group order is pinned by its own test above.
  it('sorts urgent (overdue) first, then by due date, then outstanding descending', async () => {
    listReturns([depositTicket, finalPaymentTicket, overdueTicket]);
    renderPage('/finance?view=all');
    await screen.findByText('บริษัท โอเวอร์ จำกัด');
    const names = screen.getAllByRole('link').map((l) => l.textContent);
    expect(names[0]).toContain('บริษัท โอเวอร์ จำกัด');
    // no due dates on the other two -> larger outstanding (120000-60000=60000 vs 80000) first
    expect(names[1]).toContain('บริษัท ดี จำกัด');
    expect(names[2]).toContain('บริษัท อี จำกัด');
  });

  it('"ทั้งหมด" shows every row the account list returns, including history with no next action', async () => {
    renderPage();
    await screen.findByText('บริษัท ดี จำกัด');
    fireEvent.click(screen.getByRole('tab', { name: /ทั้งหมด/ }));
    expect(await screen.findByText('บริษัท ไอเดิล จำกัด')).not.toBeNull();
    expect(screen.getByText('บริษัท ดี จำกัด')).not.toBeNull();
  });

  // Adapted: the badge's accessible count says "N งาน" (owner wording ruling), was "N ดีล".
  it('the view switch is the shared Tabs: todo is selected by default and each tab carries an accessible "N งาน" count', async () => {
    renderPage();
    await screen.findByText('บริษัท ดี จำกัด');
    const todo = screen.getByRole('tab', { name: /ต้องดำเนินการ.*3 งาน/ });
    expect(todo.getAttribute('aria-selected')).toBe('true');
    const all = screen.getByRole('tab', { name: /ทั้งหมด.*4 งาน/ });
    expect(all.getAttribute('aria-selected')).toBe('false');
    fireEvent.click(all);
    expect(all.getAttribute('aria-selected')).toBe('true');
  });

  // Adapted: the subgrid table is the ทั้งหมด view now (?view=all), so it holds all 4 rows, not the 3 actionable.
  it('column tracks are defined once on the list; the header and every row are subgrids of it (so figures share one right edge)', async () => {
    renderPage('/finance?view=all');
    await screen.findByText('บริษัท ดี จำกัด');
    const list = screen.getByRole('list');
    expect(list.className).toMatch(/grid-cols-\[/);
    const items = list.querySelectorAll(':scope > li');
    expect(items.length).toBe(5); // aria-hidden header + 4 rows
    items.forEach((li) => expect(li.className).toContain('grid-cols-subgrid'));
    screen.getAllByRole('link').forEach((a) => expect(a.className).toContain('grid-cols-subgrid'));
  });

  // Adapted: ?view=all (the table moved there).
  it('in the compact layout an empty due-date slot is omitted (kept as a dash only in the table layout)', async () => {
    renderPage('/finance?view=all');
    const link = await screen.findByRole('link', { name: /บริษัท ดี จำกัด/ });
    const dash = within(link).getAllByText('—').find((el) => el.closest('span.order-5'));
    expect(dash.closest('span.order-5').className).toContain('@max-[60rem]:hidden');
  });

  // Adapted: ?view=all, so 4 rows (incl. the idle one) and 4 links.
  it('every row is one link to /finance/deals/:id (a single focusable element per row)', async () => {
    renderPage('/finance?view=all');
    await screen.findByText('บริษัท ดี จำกัด');
    expect(rowLink('บริษัท ดี จำกัด').getAttribute('href')).toBe('/finance/deals/201');
    expect(rowLink('บริษัท อี จำกัด').getAttribute('href')).toBe('/finance/deals/202');
    expect(rowLink('บริษัท ไอเดิล จำกัด').getAttribute('href')).toBe('/finance/deals/204');
    const links = screen.getAllByRole('link');
    expect(links).toHaveLength(4);
    links.forEach((l) => expect(within(l).queryByRole('button')).toBeNull());
  });

  // Adapted: ?view=all.
  it('a row shows the milestone chip (index · label · stage code), the next action, the outstanding and the due date', async () => {
    renderPage('/finance?view=all');
    await screen.findByText('บริษัท อี จำกัด');
    const link = rowLink('บริษัท อี จำกัด');
    expect(within(link).getByText(/4 · ส่งมอบ/)).not.toBeNull();
    expect(within(link).getByText(/S18/)).not.toBeNull();
    expect(within(link).getByText('รับชำระส่วนที่เหลือ')).not.toBeNull();
    expect(within(link).getByText('฿60,000.00')).not.toBeNull();
  });

  // Adapted: ?view=all.
  it('the due-date column shows the derived paymentDueDate (not the billing column)', async () => {
    listReturns([{ ...finalPaymentTicket, paymentDueDate: '2026-10-15', dueDate: '2030-01-01' }]);
    renderPage('/finance?view=all');
    const link = await screen.findByRole('link', { name: /บริษัท อี จำกัด/ });
    expect(within(link).getByText('15 ต.ค. 2569')).not.toBeNull();
    expect(within(link).queryByText(/2573/)).toBeNull();
  });

  // Adapted: ?view=all.
  it('an overdue row shows the overdue text with an icon and the chase action', async () => {
    listReturns([overdueTicket]);
    renderPage('/finance?view=all');
    const link = await screen.findByRole('link', { name: /บริษัท โอเวอร์ จำกัด/ });
    const flag = within(link).getByText('เกินกำหนดชำระ');
    expect(flag.parentElement.querySelector('svg')).not.toBeNull();
    expect(within(link).getByText('ติดตามชำระ')).not.toBeNull();
  });

  // Adapted: the block is on ?view=all now, and its counts say "งาน" (wording ruling), was "ดีล".
  it('the summary line counts the actionable rows but takes ค้างรับทั้งหมด from the server total', async () => {
    renderPage('/finance?view=all');
    // 3 actionable rows; their outstanding adds up to 140,000, but the figure is the summary's (rows are capped at 500)
    expect(await screen.findByText(/ต้องดำเนินการ 3 งาน/)).not.toBeNull();
    expect(await screen.findByText('ต้องดำเนินการ 3 งาน · ค้างรับทั้งหมด ฿424,242.42')).not.toBeNull();
    // the hero is labelled as the whole receivable, not as the listed deals' sum
    expect(screen.getByText('ค้างรับทั้งหมด')).not.toBeNull();
    expect(screen.queryByText('ยอดคงค้างของดีลที่ต้องดำเนินการ')).toBeNull();
    expect(screen.queryByText(/฿140,000.00/)).toBeNull();
  });

  // Adapted: ?view=all, and "0 งาน" (was "0 ดีล").
  it('without a server total the summary shows an em dash, not a fabricated sum or zero', async () => {
    api.dashboard.summary.mockResolvedValue({ summary: {} });
    api.tickets.list.mockResolvedValue({ tickets: [idleTicket] });
    renderPage('/finance?view=all');
    expect(await screen.findByText(/ต้องดำเนินการ 0 งาน/)).not.toBeNull();
    expect(screen.getByText(/ค้างรับทั้งหมด —/)).not.toBeNull();
    expect(screen.queryByText(/฿0.00/)).toBeNull();
  });

  // Adapted: the selects are on the ทั้งหมด view (?view=all); the idle row (milestone 3) is now in the list too.
  it('filters by money milestone', async () => {
    renderPage('/finance?view=all');
    await screen.findByText('บริษัท ดี จำกัด');
    fireEvent.change(screen.getByLabelText('ขั้นการเงิน'), { target: { value: '4' } });
    expect(screen.queryByText('บริษัท เอฟ จำกัด')).toBeNull();
    expect(screen.queryByText('บริษัท ไอเดิล จำกัด')).toBeNull();
    expect(screen.getByText('บริษัท อี จำกัด')).not.toBeNull();
  });

  // Adapted: ?view=all (selects moved there).
  it('filters by next action (chip keys stay nextAccountAction keys)', async () => {
    renderPage('/finance?view=all');
    await screen.findByText('บริษัท ดี จำกัด');
    fireEvent.change(screen.getByLabelText('ขั้นตอนที่ต้องทำ'), { target: { value: 'confirmFinalPayment' } });
    expect(screen.queryByText('บริษัท ดี จำกัด')).toBeNull();
    expect(screen.getByText('บริษัท อี จำกัด')).not.toBeNull();
  });

  it('the next-action filter is read from ?action= (and the legacy ?stage= still works)', async () => {
    const first = renderPage('/finance?action=confirmFinalPayment');
    await screen.findByText('บริษัท อี จำกัด');
    expect(screen.queryByText('บริษัท ดี จำกัด')).toBeNull();
    first.unmount();
    renderPage('/finance?stage=confirmDeposit');
    await screen.findByText('บริษัท ดี จำกัด');
    expect(screen.queryByText('บริษัท อี จำกัด')).toBeNull();
  });

  it('a filter deep link (AccountOverview\'s /finance?action=…) shows the filtered list with its select, not the groups', async () => {
    renderPage('/finance?action=confirmDeposit');
    await screen.findByText('บริษัท ดี จำกัด');
    expect(screen.getByLabelText('ขั้นตอนที่ต้องทำ').value).toBe('confirmDeposit');
    expect(screen.queryByRole('region', { name: 'ติดตามชำระ' })).toBeNull();
    expect(rowLink('บริษัท ดี จำกัด').getAttribute('href')).toBe('/finance/deals/201');
  });

  // Adapted: the empty-state copy it asserts is absent now reads "งาน" (wording ruling); the old string is still
  // asserted absent too.
  it('a load error shows the server message and a retry, never the calm "no deals" empty state', async () => {
    api.tickets.list.mockRejectedValue(new Error('เชื่อมต่อเซิร์ฟเวอร์ไม่ได้'));
    renderPage();
    expect((await screen.findByRole('alert')).textContent).toContain('เชื่อมต่อเซิร์ฟเวอร์ไม่ได้');
    expect(screen.queryByText('ไม่มีงานการเงินที่ต้องทำตอนนี้')).toBeNull();
    expect(screen.queryByText('ไม่มีดีลที่ต้องดำเนินการตอนนี้')).toBeNull();
    api.tickets.list.mockImplementation((params = {}) => Promise.resolve({ tickets: params.salesStage === 'CLOSED_PAID' ? [] : [depositTicket] }));
    fireEvent.click(screen.getByRole('button', { name: 'ลองอีกครั้ง' }));
    expect(await screen.findByText('บริษัท ดี จำกัด')).not.toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('filter and view state live in the URL search params', async () => {
    renderPage('/finance?view=all&milestone=4');
    await screen.findByText('บริษัท อี จำกัด');
    expect(screen.queryByText('บริษัท ดี จำกัด')).toBeNull();
    expect(screen.queryByText('บริษัท ไอเดิล จำกัด')).toBeNull();
  });

  // Adapted: copy reads "ไม่มีงานในขั้นนี้" (was "ไม่มีดีลในขั้นนี้", wording ruling).
  it('an empty filtered result explains why and offers "ล้างตัวกรอง", which restores the rows', async () => {
    renderPage('/finance?milestone=1');
    expect(await screen.findByText(/ไม่มีงานในขั้นนี้/)).not.toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'ล้างตัวกรอง' }));
    await waitFor(() => expect(screen.getByText('บริษัท ดี จำกัด')).not.toBeNull());
  });

  it('shows row skeletons while loading', () => {
    api.tickets.list.mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(screen.getByLabelText('กำลังโหลดงานการเงิน')).not.toBeNull();
  });
});
