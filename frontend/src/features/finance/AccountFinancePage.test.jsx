import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { render, screen, fireEvent, within, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
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

// Money fixtures keep every field distinct (payable ≠ paid ≠ outstanding ≠ depositDue, paid net of an adjustment
// where outstanding is 0), so a row that shows the wrong field shows a figure no assertion expects.

const depositTicket = {
  id: 201, code: 'PR-2026-0201', title: 'โครงการดี', customerName: 'บริษัท ดี จำกัด',
  status: 'quotation_issued', paymentStatus: 'DEPOSIT_NOTICE_ISSUED', fulfillmentStatus: null,
  lifecycle: 'ACTIVE', salesStage: 'NEGOTIATION', depositPolicy: 'REQUIRED',
  closeConfirmedAt: null, invoiceOnFile: false,
  amountPayable: 85000, amountPaid: 5000, amountOutstanding: 80000, depositDue: 40000,
  overdue: false, paymentDueDate: null, updatedAt: '2026-07-10T00:00:00.000Z',
};

const finalPaymentTicket = {
  id: 202, code: 'PR-2026-0202', title: 'โครงการอี', customerName: 'บริษัท อี จำกัด',
  status: 'quotation_issued', paymentStatus: 'AWAITING_FINAL_PAYMENT', fulfillmentStatus: null,
  lifecycle: 'ACTIVE', salesStage: 'DELIVERY_SCHEDULING', depositPolicy: 'REQUIRED',
  closeConfirmedAt: null, invoiceOnFile: false,
  amountPayable: 125000, amountPaid: 65000, amountOutstanding: 60000,
  overdue: false, paymentDueDate: null, updatedAt: '2026-07-11T00:00:00.000Z',
};

const closedPaidTicket = {
  id: 203, code: 'PR-2026-0203', title: 'โครงการเอฟ', customerName: 'บริษัท เอฟ จำกัด',
  status: 'quotation_issued', paymentStatus: 'FULLY_PAID', fulfillmentStatus: 'FULLY_DELIVERED',
  lifecycle: 'ACTIVE', salesStage: 'CLOSED_PAID', depositPolicy: 'REQUIRED',
  closeConfirmedAt: '2026-07-01T00:00:00.000Z', invoiceOnFile: true,
  amountPayable: 90000, amountPaid: 89500, amountOutstanding: 0,
  overdue: false, paymentDueDate: null, updatedAt: '2026-07-01T00:00:00.000Z',
};


const idleTicket = {
  id: 204, code: 'PR-2026-0204', title: 'โครงการไอเดิล', customerName: 'บริษัท ไอเดิล จำกัด',
  status: 'quotation_issued', paymentStatus: 'CUSTOMER_CONFIRMED', fulfillmentStatus: null,
  lifecycle: 'ACTIVE', salesStage: 'PROCUREMENT', depositPolicy: 'REQUIRED',
  closeConfirmedAt: null, invoiceOnFile: false,
  amountPayable: 52000, amountPaid: 2000, amountOutstanding: 50000,
  overdue: false, paymentDueDate: null, updatedAt: '2026-07-05T00:00:00.000Z',
};

const overdueTicket = {
  id: 205, code: 'PR-2026-0205', title: 'โครงการโอเวอร์', customerName: 'บริษัท โอเวอร์ จำกัด',
  status: 'quotation_issued', paymentStatus: 'AWAITING_FINAL_PAYMENT', fulfillmentStatus: null,
  lifecycle: 'ACTIVE', salesStage: 'DELIVERED', depositPolicy: 'REQUIRED',
  closeConfirmedAt: null, invoiceOnFile: false,
  amountPayable: 32000, amountPaid: 2000, amountOutstanding: 30000,
  overdue: true, paymentDueDate: '2026-09-01', updatedAt: '2026-07-12T00:00:00.000Z',
};

const closeReadyTicket = {
  id: 206, code: 'PR-2026-0206', title: 'โครงการปิด', customerName: 'บริษัท ปิดงาน จำกัด',
  status: 'quotation_issued', paymentStatus: 'FULLY_PAID', fulfillmentStatus: 'FULLY_DELIVERED',
  lifecycle: 'ACTIVE', salesStage: 'DELIVERED', depositPolicy: 'REQUIRED',
  closeConfirmedAt: null, invoiceOnFile: true,
  amountPayable: 596000, amountPaid: 595500, amountOutstanding: 0,
  overdue: false, paymentDueDate: null, updatedAt: '2026-07-13T00:00:00.000Z',
};

// Legacy (pre-dual-track) close-ready deal: document issued, no payment status, no receipt rows. closeReady()
// accepts it (accountActions.js closePrerequisitesMet legacyOk); nothing here may claim it was paid in full.
const legacyCloseReadyTicket = {
  id: 208, code: 'PR-2026-0208', title: 'โครงการเก่า', customerName: 'บริษัท เก่า จำกัด',
  status: 'document_issued', paymentStatus: null, fulfillmentStatus: null,
  lifecycle: 'ACTIVE', salesStage: 'DELIVERED', depositPolicy: 'REQUIRED',
  closeConfirmedAt: null, invoiceOnFile: false,
  amountPayable: 70000, amountPaid: 0, amountOutstanding: null,
  overdue: false, paymentDueDate: null, updatedAt: '2026-07-14T00:00:00.000Z',
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
    expect(within(group('ยืนยันพร้อมปิดงาน')).getByText('พร้อมปิดงาน รอฝ่ายบัญชียืนยัน')).not.toBeNull();
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

  // Ruling F6/F2: each row shows exactly what the old table showed for that deal — one figure, labelled คงค้าง,
  // from amountOutstanding — and none of the other money fields (payable, paid, depositDue).
  it('every row shows exactly the old table\'s figure: คงค้าง = amountOutstanding, and no other money field', async () => {
    renderPage();
    await screen.findByText('บริษัท ดี จำกัด');
    const expected = { 206: '฿0.00', 203: '฿0.00', 201: '฿80,000.00', 202: '฿60,000.00', 205: '฿30,000.00' };
    const others = ['฿596,000.00', '฿595,500.00', '฿90,000.00', '฿89,500.00', '฿85,000.00', '฿5,000.00', '฿40,000.00',
      '฿125,000.00', '฿65,000.00', '฿32,000.00', '฿2,000.00'];
    Object.entries(expected).forEach(([id, figure]) => {
      const row = groupRow(id);
      expect(within(row).getByText(`คงค้าง ${figure}`)).not.toBeNull();
      expect(row.textContent.match(/฿[\d,]+\.\d\d/g)).toEqual([figure]);
      others.forEach((o) => expect(row.textContent).not.toContain(o));
    });
    // the deal code is the row's id column
    expect(within(groupRow(201)).getByText('PR-2026-0201')).not.toBeNull();
  });

  it('no money label the old table did not have: no รับครบ, ยอดรับจริง or มัดจำ figure anywhere on the grouped view', async () => {
    const { container } = renderPage();
    await screen.findByText('บริษัท ดี จำกัด');
    expect(container.textContent).not.toMatch(/รับครบ|ยอดรับจริง|มัดจำ ฿/);
  });

  it('a legacy close-ready deal with no receipts is never described as paid in full; it shows the old table\'s dash', async () => {
    listReturns([legacyCloseReadyTicket]);
    renderPage();
    await screen.findByText('บริษัท เก่า จำกัด');
    const row = groupRow(208);
    expect(within(group('ยืนยันพร้อมปิดงาน')).getByText('บริษัท เก่า จำกัด')).not.toBeNull();
    expect(within(row).getByText('คงค้าง —')).not.toBeNull();
    expect(row.textContent).not.toMatch(/ครบ ฿|รับครบ|ชำระครบ|฿0\.00|฿70,000\.00/);
  });

  it('a chase row says which step waits on the deal page, and flags overdue / shows the due date', async () => {
    listReturns([depositTicket, { ...finalPaymentTicket, paymentDueDate: '2026-11-20' }, overdueTicket]);
    renderPage();
    await screen.findByText('บริษัท ดี จำกัด');
    expect(within(groupRow(201)).getByText('ยืนยันรับมัดจำ')).not.toBeNull();
    expect(within(groupRow(202)).getByText('รับชำระส่วนที่เหลือ')).not.toBeNull();
    expect(within(groupRow(202)).getByText('ครบกำหนด 20 พ.ย. 2569')).not.toBeNull();
    // Adapted (slice 6.1): the overdue chip counts the days ("เกินกำหนด n วัน", n pinned under a fixed clock in the
    // slice-6.1 block below) and the due date moved to the line beneath it — still flagged, still dated.
    expect(within(groupRow(205)).getByText(/^เกินกำหนด \d+ วัน$/)).not.toBeNull();
    expect(within(groupRow(205)).getByText('ครบกำหนด 1 ก.ย. 2569')).not.toBeNull();
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
  });

  // Ruling F1: the server offers CONFIRM_CLOSE only to CLOSE_CONFIRM_ROLES = {account} and RECORD_INVOICE only to
  // account, so the CEO's button never carries those verbs: it reads เปิด and the step is plain status text.
  it('CEO (wrong way round): never offered an action button it cannot perform — every button reads เปิด, the step is status text', async () => {
    renderPage('/finance', ceoUser);
    await screen.findByText('บริษัท โอเวอร์ จำกัด');
    [205, 206, 203].forEach((id) => {
      const link = within(groupRow(id)).getByRole('link');
      expect(link.textContent).toBe('เปิด');
      expect(link.getAttribute('href')).toBe(`/finance/deals/${id}`);
      expect(link.getAttribute('aria-label')).not.toMatch(/ยืนยันพร้อมปิดงาน|บันทึกใบกำกับ/);
    });
    expect(within(groupRow(206)).getByText('ยืนยันพร้อมปิดงาน').tagName).not.toBe('A');
    expect(within(groupRow(203)).getByText('บันทึกใบกำกับ').tagName).not.toBe('A');
    // no link on the grouped view carries an action verb for the CEO
    screen.getAllByRole('link').filter((l) => l.closest('[data-testid^="fin-work-row-"]'))
      .forEach((l) => expect(l.textContent).toBe('เปิด'));
  });

  it('account (the performer) gets the verb on the button and no duplicate status text', async () => {
    renderPage();
    await screen.findByText('บริษัท ปิดงาน จำกัด');
    expect(within(groupRow(206)).getByRole('link').textContent).toBe('ยืนยันพร้อมปิดงาน');
    expect(within(groupRow(206)).getAllByText('ยืนยันพร้อมปิดงาน')).toHaveLength(1);
    expect(within(groupRow(203)).getByRole('link').textContent).toBe('บันทึกใบกำกับ');
    expect(within(groupRow(203)).getAllByText('บันทึกใบกำกับ')).toHaveLength(1);
  });

  it('the ต้องดำเนินการ badge equals the sum of the group counts', async () => {
    renderPage();
    await screen.findByText('บริษัท ดี จำกัด');
    const sum = screen.getAllByTestId('group-count').reduce((n, el) => n + Number(el.textContent), 0);
    expect(sum).toBe(5);
    expect(screen.getByRole('tab', { name: new RegExp(`ต้องดำเนินการ.*${sum} งาน`) })).not.toBeNull();
  });

  // Ruling F4: inside every group, the old table's default order — overdue first, then paymentDueDate ascending
  // (none last), then rowAmount descending (outstanding when > 0, else payable).
  it('inside ยืนยันพร้อมปิดงาน and บันทึกใบกำกับ the old table order holds (ties on due date → larger amount first)', async () => {
    const closeSmall = { ...closeReadyTicket, id: 211, code: 'PR-2026-0211', customerName: 'ปิดเล็ก', amountPayable: 100000, amountPaid: 99000 };
    const closeBig = { ...closeReadyTicket, id: 212, code: 'PR-2026-0212', customerName: 'ปิดใหญ่', amountPayable: 300000, amountPaid: 299000 };
    const invSmall = { ...closedPaidTicket, id: 213, code: 'PR-2026-0213', customerName: 'ใบกำกับเล็ก', amountPayable: 20000, amountPaid: 19000 };
    const invBig = { ...closedPaidTicket, id: 214, code: 'PR-2026-0214', customerName: 'ใบกำกับใหญ่', amountPayable: 400000, amountPaid: 399000 };
    listReturns([closeSmall, closeBig], [invSmall, invBig]);
    renderPage();
    await screen.findByText('ปิดใหญ่');
    const ids = (label) => within(group(label)).getAllByTestId(/^fin-work-row-/).map((r) => r.dataset.testid);
    expect(ids('ยืนยันพร้อมปิดงาน')).toEqual(['fin-work-row-212', 'fin-work-row-211']);
    expect(ids('บันทึกใบกำกับ')).toEqual(['fin-work-row-214', 'fin-work-row-213']);
  });

  // Ruling F7: the due chip's track is sized to its content, so a long chip widens its column (the fr tracks give
  // way and wrap) instead of running under the button. jsdom cannot measure; the class is the pin, the on-screen
  // check is the evidence.
  it('the chip column is content-sized so the chip cannot run under the button', async () => {
    renderPage();
    await screen.findByText('บริษัท โอเวอร์ จำกัด');
    expect(groupRow(205).className).toMatch(/minmax\(max-content,/);
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

  // ── slice 6.1 / 6.3: the drawn row details ───────────────────────────────────────────────────────────────────
  describe('drawn row details (slice 6)', () => {
    afterEach(() => { vi.useRealTimers(); });

    it('every row names the deal\'s rep — createdByName, the board\'s own owner', async () => {
      listReturns([{ ...overdueTicket, createdByName: 'สมหมาย ขายดี' }, { ...closeReadyTicket, createdByName: 'อรุณี ขายเก่ง' }]);
      renderPage();
      await screen.findByText('บริษัท โอเวอร์ จำกัด');
      expect(within(groupRow(205)).getByText('ฝ่ายขาย สมหมาย ขายดี')).not.toBeNull();
      expect(within(groupRow(206)).getByText('ฝ่ายขาย อรุณี ขายเก่ง')).not.toBeNull();
    });

    it('a row with no rep name says nothing rather than a dangling label', async () => {
      listReturns([{ ...overdueTicket, createdByName: null }]);
      renderPage();
      await screen.findByText('บริษัท โอเวอร์ จำกัด');
      expect(within(groupRow(205)).queryByText(/ฝ่ายขาย/)).toBeNull();
    });

    it('an overdue row counts whole Bangkok days from the served due date to today', async () => {
      // 10:00 Bangkok on 7 Sep 2026 (03:00 UTC); due 1 Sep → 6 days. Only Date is faked: react-query keeps real timers.
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(new Date('2026-09-07T03:00:00Z'));
      listReturns([overdueTicket]);
      renderPage();
      await screen.findByText('บริษัท โอเวอร์ จำกัด');
      expect(within(groupRow(205)).getByText('เกินกำหนด 6 วัน')).not.toBeNull();
      expect(within(groupRow(205)).getByText('ครบกำหนด 1 ก.ย. 2569')).not.toBeNull();
    });

    it('the day count follows the Bangkok calendar, not UTC: 23:30 UTC on 6 Sep is already 7 Sep in Bangkok', async () => {
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(new Date('2026-09-06T23:30:00Z'));
      listReturns([overdueTicket]);
      renderPage();
      await screen.findByText('บริษัท โอเวอร์ จำกัด');
      expect(within(groupRow(205)).getByText('เกินกำหนด 6 วัน')).not.toBeNull();
    });

    it('"overdue or not" stays the server\'s flag: a past due date the server does not flag is not counted', async () => {
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(new Date('2026-09-07T03:00:00Z'));
      listReturns([{ ...finalPaymentTicket, paymentDueDate: '2026-09-01', overdue: false }]);
      renderPage();
      await screen.findByText('บริษัท อี จำกัด');
      expect(within(groupRow(202)).queryByText(/เกินกำหนด/)).toBeNull();
      expect(within(groupRow(202)).getByText('ครบกำหนด 1 ก.ย. 2569')).not.toBeNull();
    });

    it('a server-overdue row whose due date is not yet behind today (clock skew) still says overdue, without a count', async () => {
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(new Date('2026-09-01T03:00:00Z'));
      listReturns([overdueTicket]);
      renderPage();
      await screen.findByText('บริษัท โอเวอร์ จำกัด');
      expect(within(groupRow(205)).getByText('เกินกำหนดชำระ')).not.toBeNull();
      expect(within(groupRow(205)).queryByText(/วัน$/)).toBeNull();
    });

    it.each([
      ['EXPRESS', ['Express']],
      ['MANUAL', ['บันทึกเอง']],
      ['MIXED', ['Express', 'บันทึกเอง']],
    ])('a ยืนยันพร้อมปิดงาน row says where its money came from — %s', async (receiptSource, chips) => {
      listReturns([{ ...closeReadyTicket, receiptSource }]);
      renderPage();
      await screen.findByText('บริษัท ปิดงาน จำกัด');
      chips.forEach((text) => expect(within(groupRow(206)).getByText(text)).not.toBeNull());
      ['Express', 'บันทึกเอง'].filter((t) => !chips.includes(t))
        .forEach((text) => expect(within(groupRow(206)).queryByText(text)).toBeNull());
    });

    it('no receipt source (no receipts, or a role the server withholds it from) shows no source chip', async () => {
      listReturns([{ ...closeReadyTicket, receiptSource: null }]);
      renderPage();
      await screen.findByText('บริษัท ปิดงาน จำกัด');
      expect(within(groupRow(206)).queryByText(/Express|บันทึกเอง/)).toBeNull();
    });

    it('the receipt source chip is a ยืนยันพร้อมปิดงาน thing: a chase row keeps its due chip only', async () => {
      listReturns([{ ...overdueTicket, receiptSource: 'EXPRESS' }]);
      renderPage();
      await screen.findByText('บริษัท โอเวอร์ จำกัด');
      expect(within(groupRow(205)).queryByText('Express')).toBeNull();
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
    // Adapted (review F8): asserted on the CLOSED_PAID deal's own row, not on the group heading of the same name.
    expect(within(screen.getByTestId('fin-work-row-203')).getByRole('link').textContent).toBe('บันทึกใบกำกับ');
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
