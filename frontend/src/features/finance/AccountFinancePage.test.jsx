import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
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
    },
  };
});

const accountUser = { role: 'account', name: 'บัญชี ทดสอบ', employeeId: 3 };

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

function renderPage(initial = '/finance') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initial]}>
        <AccountFinancePage user={accountUser} showToast={vi.fn()} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const rowLink = (name) => screen.getByRole('link', { name: new RegExp(name) });

describe('AccountFinancePage (index-first worklist)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.tickets.list.mockImplementation((params = {}) => {
      if (params.salesStage === 'CLOSED_PAID') return Promise.resolve({ tickets: [closedPaidTicket] });
      return Promise.resolve({ tickets: [depositTicket, finalPaymentTicket, idleTicket] });
    });
  });

  it('defaults to "ต้องดำเนินการ": only rows with a next account action, in the urgent/due/outstanding order', async () => {
    renderPage();
    await screen.findByText('บริษัท ดี จำกัด');
    expect(screen.getByText('บริษัท อี จำกัด')).not.toBeNull();
    expect(screen.getByText('บริษัท เอฟ จำกัด')).not.toBeNull();
    // idle: quotation-stage deal, no account action -> history only
    expect(screen.queryByText('บริษัท ไอเดิล จำกัด')).toBeNull();
  });

  it('sorts urgent (overdue) first, then by due date, then outstanding descending', async () => {
    api.tickets.list.mockImplementation((params = {}) => (params.salesStage === 'CLOSED_PAID'
      ? Promise.resolve({ tickets: [] })
      : Promise.resolve({ tickets: [depositTicket, finalPaymentTicket, overdueTicket] })));
    renderPage();
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

  it('the view switch is the shared Tabs: todo is selected by default and each tab carries an accessible "N ดีล" count', async () => {
    renderPage();
    await screen.findByText('บริษัท ดี จำกัด');
    const todo = screen.getByRole('tab', { name: /ต้องดำเนินการ.*3 ดีล/ });
    expect(todo.getAttribute('aria-selected')).toBe('true');
    const all = screen.getByRole('tab', { name: /ทั้งหมด.*4 ดีล/ });
    expect(all.getAttribute('aria-selected')).toBe('false');
    fireEvent.click(all);
    expect(all.getAttribute('aria-selected')).toBe('true');
  });

  it('column tracks are defined once on the list; the header and every row are subgrids of it (so figures share one right edge)', async () => {
    renderPage();
    await screen.findByText('บริษัท ดี จำกัด');
    const list = screen.getByRole('list');
    expect(list.className).toMatch(/grid-cols-\[/);
    const items = list.querySelectorAll(':scope > li');
    expect(items.length).toBe(4); // aria-hidden header + 3 rows
    items.forEach((li) => expect(li.className).toContain('grid-cols-subgrid'));
    screen.getAllByRole('link').forEach((a) => expect(a.className).toContain('grid-cols-subgrid'));
  });

  it('in the compact layout an empty due-date slot is omitted (kept as a dash only in the table layout)', async () => {
    renderPage();
    const link = await screen.findByRole('link', { name: /บริษัท ดี จำกัด/ });
    const dash = within(link).getAllByText('—').find((el) => el.closest('span.order-5'));
    expect(dash.closest('span.order-5').className).toContain('@max-[60rem]:hidden');
  });

  it('every row is one link to /finance/deals/:id (a single focusable element per row)', async () => {
    renderPage();
    await screen.findByText('บริษัท ดี จำกัด');
    expect(rowLink('บริษัท ดี จำกัด').getAttribute('href')).toBe('/finance/deals/201');
    expect(rowLink('บริษัท อี จำกัด').getAttribute('href')).toBe('/finance/deals/202');
    // 3 actionable rows, 3 links; no nested buttons inside a row link
    const links = screen.getAllByRole('link');
    expect(links).toHaveLength(3);
    links.forEach((l) => expect(within(l).queryByRole('button')).toBeNull());
  });

  it('a row shows the milestone chip (index · label · stage code), the next action, the outstanding and the due date', async () => {
    renderPage();
    await screen.findByText('บริษัท อี จำกัด');
    const link = rowLink('บริษัท อี จำกัด');
    expect(within(link).getByText(/4 · ส่งมอบ/)).not.toBeNull();
    expect(within(link).getByText(/S18/)).not.toBeNull();
    expect(within(link).getByText('รับชำระส่วนที่เหลือ')).not.toBeNull();
    expect(within(link).getByText('฿60,000.00')).not.toBeNull();
  });

  it('the due-date column shows the derived paymentDueDate (not the billing column)', async () => {
    api.tickets.list.mockImplementation((params = {}) => (params.salesStage === 'CLOSED_PAID'
      ? Promise.resolve({ tickets: [] })
      : Promise.resolve({ tickets: [{ ...finalPaymentTicket, paymentDueDate: '2026-10-15', dueDate: '2030-01-01' }] })));
    renderPage();
    const link = await screen.findByRole('link', { name: /บริษัท อี จำกัด/ });
    expect(within(link).getByText('15 ต.ค. 2569')).not.toBeNull();
    expect(within(link).queryByText(/2573/)).toBeNull();
  });

  it('an overdue row shows the overdue text with an icon and the chase action', async () => {
    api.tickets.list.mockImplementation((params = {}) => (params.salesStage === 'CLOSED_PAID'
      ? Promise.resolve({ tickets: [] })
      : Promise.resolve({ tickets: [overdueTicket] })));
    renderPage();
    const link = await screen.findByRole('link', { name: /บริษัท โอเวอร์ จำกัด/ });
    const flag = within(link).getByText('เกินกำหนดชำระ');
    expect(flag.parentElement.querySelector('svg')).not.toBeNull();
    expect(within(link).getByText('ติดตามชำระ')).not.toBeNull();
  });

  it('the summary line is computed from the data: count of actionable deals and total outstanding', async () => {
    renderPage();
    // deposit 80,000 + final 60,000 + closed-paid 0 = 140,000 across 3 actionable deals
    expect(await screen.findByText(/ต้องดำเนินการ 3 ดีล/)).not.toBeNull();
    expect(screen.getByText(/คงค้างรวม ฿140,000.00/)).not.toBeNull();
  });

  it('with nothing actionable the summary says so and shows an em dash, not a fabricated zero', async () => {
    api.tickets.list.mockResolvedValue({ tickets: [idleTicket] });
    renderPage();
    expect(await screen.findByText(/ต้องดำเนินการ 0 ดีล/)).not.toBeNull();
    expect(screen.getByText(/คงค้างรวม —/)).not.toBeNull();
    expect(screen.queryByText(/฿0.00/)).toBeNull();
  });

  it('filters by money milestone', async () => {
    renderPage();
    await screen.findByText('บริษัท ดี จำกัด');
    fireEvent.change(screen.getByLabelText('ขั้นการเงิน'), { target: { value: '4' } });
    expect(screen.queryByText('บริษัท เอฟ จำกัด')).toBeNull();
    expect(screen.getByText('บริษัท อี จำกัด')).not.toBeNull();
  });

  it('filters by next action (chip keys stay nextAccountAction keys)', async () => {
    renderPage();
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

  it('a load error shows the server message and a retry, never the calm "no deals" empty state', async () => {
    api.tickets.list.mockRejectedValue(new Error('เชื่อมต่อเซิร์ฟเวอร์ไม่ได้'));
    renderPage();
    expect((await screen.findByRole('alert')).textContent).toContain('เชื่อมต่อเซิร์ฟเวอร์ไม่ได้');
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

  it('an empty filtered result explains why and offers "ล้างตัวกรอง", which restores the rows', async () => {
    renderPage('/finance?milestone=1');
    expect(await screen.findByText(/ไม่มีดีลในขั้นนี้/)).not.toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'ล้างตัวกรอง' }));
    await waitFor(() => expect(screen.getByText('บริษัท ดี จำกัด')).not.toBeNull());
  });

  it('shows row skeletons while loading', () => {
    api.tickets.list.mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(screen.getByLabelText('กำลังโหลดงานการเงิน')).not.toBeNull();
  });
});
