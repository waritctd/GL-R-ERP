import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AccountOverview } from './AccountOverview.jsx';
import { api } from '../../api/index.js';
import { AccountFinancePage } from '../finance/AccountFinancePage.jsx';
import { DEAL_STAGE_CATALOG } from '../../data/dealStageCatalog.js';
import { formatThaiDate, greetingName } from '../../utils/format.js';
import { closedDemoDeal, expectFigure, figureRows } from '../../test/fixtures/dashboardMoney.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      tickets: { list: vi.fn() },
      meta: { dealStages: vi.fn() },
      dashboard: { summary: vi.fn() },
    },
  };
});

const accountUser = { role: 'account', name: 'บัญชี ทดสอบ', employeeId: 3 };
const employee = { nickName: 'ปุ้ย', nameTh: 'บัญชี ทดสอบ' };
const never = () => new Promise(() => {});

const base = {
  status: 'quotation_issued', paymentStatus: null, fulfillmentStatus: null, lifecycle: 'ACTIVE',
  salesStage: 'NEGOTIATION', depositPolicy: 'REQUIRED', closeConfirmedAt: null, invoiceOnFile: false,
  amountPayable: 0, amountPaid: 0, amountOutstanding: 0, depositDue: null, overdue: false, dueDate: null,
  updatedAt: '2026-07-01T00:00:00.000Z',
};
// Four overdue chases (one more than the 3-row cap), balances 100k/50k/30k/20k = 200,000.00.
const overdue = (id, outstanding, dueDate) => ({
  ...base, id, code: `OD-${id}`, title: `โครงการ ${id}`, customerName: `ลูกค้าเกินกำหนด ${id}`,
  paymentStatus: 'AWAITING_FINAL_PAYMENT', salesStage: 'DELIVERY_SCHEDULING',
  amountPayable: outstanding * 2, amountOutstanding: outstanding, overdue: true, dueDate,
});
const OVERDUE = [
  overdue(101, 100000, '2026-06-01'), overdue(102, 50000, '2026-06-10'),
  overdue(103, 30000, '2026-06-20'), overdue(104, 20000, '2026-07-01'),
];
const deposit = (id, due, outstanding) => ({
  ...base, id, code: `DP-${id}`, title: `มัดจำ ${id}`, customerName: `ลูกค้ามัดจำ ${id}`,
  paymentStatus: 'DEPOSIT_NOTICE_ISSUED', salesStage: 'ORDER_RECEIVED',
  amountPayable: 213545.25, amountOutstanding: outstanding, depositDue: due,
});
const DEPOSITS = [deposit(201, 85418.1, 213545.25), deposit(202, 10000, 50000)];
// At DELIVERY_SCHEDULING, so the final-payment gate is open.
const FINAL = {
  ...base, id: 301, code: 'FP-301', title: 'ส่วนที่เหลือ', customerName: 'ลูกค้าส่วนที่เหลือ',
  paymentStatus: 'DEPOSIT_PAID', salesStage: 'DELIVERY_SCHEDULING', amountPayable: 120000, amountOutstanding: 40000,
};
const CLOSE = {
  ...base, id: 401, code: 'CR-401', title: 'พร้อมปิด', customerName: 'ลูกค้าพร้อมปิด',
  paymentStatus: 'FULLY_PAID', fulfillmentStatus: 'FULLY_DELIVERED', invoiceOnFile: true,
  salesStage: 'DELIVERED', amountPayable: 648000, amountPaid: 648000,
};
const INVOICE = {
  ...base, id: 501, code: 'IV-501', title: 'บันทึกใบกำกับ', customerName: 'ลูกค้าใบกำกับ',
  paymentStatus: 'FULLY_PAID', salesStage: 'CLOSED_PAID', commissionRecorded: false,
  amountPayable: 792000, amountPaid: 792000,
};
// Gate holds these: goods not yet delivered, not overdue. No button; shown under รอสินค้าเข้า.
const held = (id, extra) => ({
  ...base, id, code: `HD-${id}`, title: `รอสินค้า ${id}`, customerName: `ลูกค้ารอสินค้า ${id}`, ...extra,
});
const HOLD_A = held(601, { paymentStatus: 'DEPOSIT_PAID', salesStage: 'PROCUREMENT', amountPayable: 200000, amountOutstanding: 80000 });
const HOLD_B = held(602, { depositPolicy: 'WAIVED', paymentStatus: 'CUSTOMER_CONFIRMED', salesStage: 'PROCUREMENT', amountPayable: 25000, amountOutstanding: 25000 });
// Not account's yet at all, and a held-by-gate deal that IS overdue (belongs to chaseOverdue, not the hold panel).
const NO_ACTION = held(701, { paymentStatus: 'CUSTOMER_CONFIRMED', salesStage: 'ORDER_RECEIVED', amountOutstanding: 10000 });
const HELD_OVERDUE = held(702, { paymentStatus: 'DEPOSIT_PAID', salesStage: 'PROCUREMENT', overdue: true, dueDate: '2026-05-01', amountOutstanding: 7000 });

const SCOPED = [...OVERDUE, ...DEPOSITS, FINAL, CLOSE, HOLD_A, HOLD_B, NO_ACTION];
const SUMMARY = { financeOutstanding: 777000.5, cashReceivedThisMonth: 321000.5 };

function serve(scoped = SCOPED, closedPaid = [INVOICE]) {
  api.tickets.list.mockImplementation((params = {}) => Promise.resolve(
    { tickets: params.salesStage === 'CLOSED_PAID' ? closedPaid : scoped },
  ));
}

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location-probe">{location.pathname}{location.search}</div>;
}

function renderOverview(dashboardSummary = SUMMARY) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route
            path="/"
            element={<AccountOverview user={accountUser} employee={employee} dashboardSummary={dashboardSummary} showToast={vi.fn()} />}
          />
          <Route path="*" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const group = (key) => screen.getByTestId(`worklist-group-${key}`);
const findGroup = (key) => screen.findByTestId(`worklist-group-${key}`);
const probe = () => screen.getByTestId('location-probe').textContent;
const GROUP_ORDER = ['chaseOverdue', 'confirmDeposit', 'confirmFinalPayment', 'confirmCloseReady', 'recordInvoiceCommission'];

describe('AccountOverview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.meta.dealStages.mockResolvedValue(DEAL_STAGE_CATALOG);
    serve();
  });

  it('titles the page ภาพรวมการเงิน with a greeting and today\'s date, and drops the old tiles and side cards', async () => {
    renderOverview();
    await findGroup('chaseOverdue');
    expect(screen.getByRole('heading', { name: 'ภาพรวมการเงิน' })).not.toBeNull();
    expect(screen.getByText(`สวัสดี, ${greetingName('ปุ้ย')} · ${formatThaiDate(new Date().toISOString())}`)).not.toBeNull();
    // bento tiles, สรุปเดือนนี้ and the คิวของฉัน card are gone
    ['สรุปเดือนนี้', 'คิวของฉัน', 'รอชำระส่วนที่เหลือ', 'รอปิดงาน'].forEach((t) => expect(screen.queryByText(t), t).toBeNull());
    expect(document.querySelector('.rounded-md.bg-surface.p-4')).toBeNull();
    // the per-row quotation badge is gone
    expect(screen.queryByText('ออกใบเสนอราคาแล้ว')).toBeNull();
  });

  describe('loading and failure (per source)', () => {
    it('shows the summary-backed figures at once while the deal lists are still loading', () => {
      api.tickets.list.mockImplementation(never);
      renderOverview();
      // Synchronous: the summary is already in hand.
      expectFigure('ค้างรับทั้งหมด', '฿777,000.50');
      expectFigure('รับแล้ว (เดือนนี้)', '฿321,000.50');
      // Deal-derived figures must not read as a confident zero before their list lands.
      expect(figureRows('เกินกำหนดชำระ')[0]).not.toContain('฿0.00');
      expect(figureRows('รอรับมัดจำ')[0]).not.toContain('฿0.00');
    });

    it('renders the scoped-list groups while the CLOSED_PAID list is still loading; only บันทึกใบกำกับ waits', async () => {
      api.tickets.list.mockImplementation((params = {}) => (params.salesStage === 'CLOSED_PAID' ? never() : Promise.resolve({ tickets: SCOPED })));
      renderOverview();
      await findGroup('chaseOverdue');
      expect(group('recordInvoiceCommission').getAttribute('aria-busy')).toBe('true');
      expect(group('chaseOverdue').getAttribute('aria-busy')).toBeNull();
      expect(screen.queryByTestId('worklist-total')).toBeNull(); // would be a partial sum
    });

    it('shows a failed CLOSED_PAID list as โหลดไม่สำเร็จ on its group while the rest still render', async () => {
      api.tickets.list.mockImplementation((params = {}) => (params.salesStage === 'CLOSED_PAID'
        ? Promise.reject(new Error('boom')) : Promise.resolve({ tickets: SCOPED })));
      renderOverview();
      await findGroup('chaseOverdue');
      const failedGroup = (await screen.findByText('โหลดไม่สำเร็จ')).closest('[data-testid^="worklist-group-"]');
      expect(failedGroup.dataset.testid).toBe('worklist-group-recordInvoiceCommission');
      expect(failedGroup.getAttribute('aria-busy')).toBeNull();
      expectFigure('ค้างรับทั้งหมด', '฿777,000.50');
    });
  });

  describe('figures band', () => {
    it('leads with ค้างรับทั้งหมด = the summary\'s financeOutstanding (2 decimals), then the three others in order', async () => {
      renderOverview();
      await findGroup('chaseOverdue');
      const labels = [...document.querySelectorAll('dl dt')].map((el) => el.textContent);
      expect(labels).toEqual(['ค้างรับทั้งหมด', 'เกินกำหนดชำระ', 'รอรับมัดจำ', 'รับแล้ว (เดือนนี้)']);
      expectFigure('ค้างรับทั้งหมด', '฿777,000.50');
      expect(figureRows('ค้างรับทั้งหมด')[0]).toContain('ยอดคงค้างรวม (หน้าการเงิน)');
    });

    it('เกินกำหนดชำระ sums the overdue deals\' outstanding and names the count and the earliest due date', async () => {
      // HELD_OVERDUE is overdue with a balance: it counts here (and is the earliest) even though the gate holds its final payment.
      serve([...SCOPED, HELD_OVERDUE]);
      renderOverview();
      await findGroup('chaseOverdue');
      expectFigure('เกินกำหนดชำระ', '฿207,000.00');
      expectFigure('เกินกำหนดชำระ', '5 ดีล');
      expectFigure('เกินกำหนดชำระ', formatThaiDate('2026-05-01'));
    });

    it('รอรับมัดจำ sums depositDue (not the deal outstanding) and counts the issued notices', async () => {
      renderOverview();
      await findGroup('confirmDeposit');
      expectFigure('รอรับมัดจำ', '฿95,418.10');
      expectFigure('รอรับมัดจำ', 'ใบแจ้งมัดจำที่ออกแล้ว 2 ใบ');
      expect(figureRows('รอรับมัดจำ')[0]).not.toContain('263,545.25');
    });

    it('รับแล้ว (เดือนนี้) is cashReceivedThisMonth and ค้างรับทั้งหมด is never a sum of the loaded rows', async () => {
      renderOverview({ financeOutstanding: 1234.5, cashReceivedThisMonth: 99.25 });
      await findGroup('chaseOverdue');
      expectFigure('รับแล้ว (เดือนนี้)', '฿99.25');
      expectFigure('ค้างรับทั้งหมด', '฿1,234.50');
    });

    it('shows an em dash for the two summary figures when the summary has no totals', async () => {
      renderOverview({});
      await findGroup('chaseOverdue');
      expectFigure('ค้างรับทั้งหมด', '—');
      expectFigure('รับแล้ว (เดือนนี้)', '—');
    });

    it('ค้างรับทั้งหมด equals the figure AccountFinancePage prints: both read the summary\'s total', async () => {
      api.dashboard.summary.mockResolvedValue({ summary: { financeOutstanding: 777000.5 } });
      renderOverview();
      await findGroup('chaseOverdue');
      const value = figureRows('ค้างรับทั้งหมด')[0];
      expect(value).toContain('฿777,000.50');
      cleanup();

      serve([OVERDUE[0], DEPOSITS[0], NO_ACTION], [closedDemoDeal]);
      const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
      render(
        <QueryClientProvider client={queryClient}>
          <MemoryRouter initialEntries={['/finance']}>
            <AccountFinancePage user={accountUser} showToast={vi.fn()} />
          </MemoryRouter>
        </QueryClientProvider>,
      );
      const subtitle = await screen.findByText(/ค้างรับทั้งหมด ฿/);
      expect(subtitle.textContent).toContain('ค้างรับทั้งหมด ฿777,000.50');
    });
  });

  describe('สิ่งที่ต้องทำ worklist', () => {
    it('groups in the fixed order with a total badge counting every row', async () => {
      renderOverview();
      await findGroup('recordInvoiceCommission');
      const order = [...document.querySelectorAll('[data-testid^="worklist-group-"]')]
        .map((el) => el.dataset.testid.replace('worklist-group-', ''));
      expect(order).toEqual(GROUP_ORDER);
      // 4 overdue + 2 deposit + 1 final + 1 close + 1 invoice; the held and no-action deals add nothing.
      expect(screen.getByTestId('worklist-total').textContent).toBe('9');
    });

    it('each group header carries its deal count and its own total (outstanding / depositDue / deal value)', async () => {
      renderOverview();
      await findGroup('recordInvoiceCommission');
      expect(group('chaseOverdue').textContent).toContain('ติดตามชำระ');
      expect(group('chaseOverdue').textContent).toContain('4 ดีล · ค้าง ฿200,000.00');
      expect(group('confirmDeposit').textContent).toContain('2 ดีล · มัดจำ ฿95,418.10');
      expect(group('confirmFinalPayment').textContent).toContain('1 ดีล · ค้าง ฿40,000.00');
      expect(group('confirmCloseReady').textContent).toContain('1 ดีล · มูลค่าดีล ฿648,000.00');
      expect(group('recordInvoiceCommission').textContent).toContain('1 ดีล · มูลค่าดีล ฿792,000.00');
    });

    it('shows at most 3 rows per group, earliest due first, with อีก N รายการ for the rest', async () => {
      renderOverview();
      await findGroup('chaseOverdue');
      const g = group('chaseOverdue');
      expect(within(g).getByTestId('worklist-row-chaseOverdue-101')).not.toBeNull();
      expect(within(g).getByTestId('worklist-row-chaseOverdue-102')).not.toBeNull();
      expect(within(g).getByTestId('worklist-row-chaseOverdue-103')).not.toBeNull();
      expect(within(g).queryByTestId('worklist-row-chaseOverdue-104')).toBeNull();
      expect(within(g).getByText('อีก 1 รายการ')).not.toBeNull();
      // a group at or under the cap has no อีก link
      expect(within(group('confirmDeposit')).queryByText(/^อีก /)).toBeNull();
    });

    it('overdue rows show customer, code · เกินกำหนด · ครบ <date>, the balance and a danger-styled ติดตามชำระ button', async () => {
      renderOverview();
      await findGroup('chaseOverdue');
      const row = screen.getByTestId('worklist-row-chaseOverdue-101');
      expect(row.textContent).toContain('ลูกค้าเกินกำหนด 101');
      expect(row.textContent).toContain(`OD-101 · เกินกำหนด · ครบ ${formatThaiDate('2026-06-01')}`);
      expect(row.textContent).toContain('฿100,000.00');
      expect(within(row).getByRole('button').textContent).toBe('ติดตามชำระ');
    });

    it('non-overdue rows show their money milestone (ขั้น N <label>) and the right amount; no quotation badge', async () => {
      renderOverview();
      await findGroup('confirmDeposit');
      const dep = screen.getByTestId('worklist-row-confirmDeposit-201');
      expect(dep.textContent).toContain('DP-201 · ขั้น 1 ได้รับคำสั่งซื้อ');
      expect(dep.textContent).toContain('฿85,418.10'); // depositDue, not the 213,545.25 deal outstanding
      expect(dep.textContent).not.toContain('213,545.25');
      expect(dep.textContent).not.toContain('ออกใบเสนอราคาแล้ว');
      const fin = screen.getByTestId('worklist-row-confirmFinalPayment-301');
      expect(fin.textContent).toContain('FP-301 · ขั้น 4 ส่งมอบ — รอชำระส่วนที่เหลือ');
      expect(fin.textContent).toContain('฿40,000.00');
      // close / invoice rows print the deal value
      expect(screen.getByTestId('worklist-row-confirmCloseReady-401').textContent).toContain('฿648,000.00');
      expect(screen.getByTestId('worklist-row-recordInvoiceCommission-501').textContent).toContain('฿792,000.00');
    });

    it('CTA labels are the existing action labels and open the finance deal page', async () => {
      renderOverview();
      await findGroup('recordInvoiceCommission');
      const cta = (key, id) => within(screen.getByTestId(`worklist-row-${key}-${id}`)).getByRole('button');
      expect(cta('confirmDeposit', 201).textContent).toBe('ยืนยันรับมัดจำ');
      expect(cta('confirmFinalPayment', 301).textContent).toBe('รับชำระส่วนที่เหลือ');
      expect(cta('confirmCloseReady', 401).textContent).toBe('ยืนยันพร้อมปิดงาน');
      expect(cta('recordInvoiceCommission', 501).textContent).toBe('บันทึกใบกำกับ');
      fireEvent.click(cta('confirmDeposit', 201));
      expect(probe()).toBe('/finance/deals/201');
    });

    it('ดูทั้งหมด → and อีก N รายการ open /finance filtered to that group\'s action', async () => {
      renderOverview();
      await findGroup('chaseOverdue');
      fireEvent.click(within(group('confirmDeposit')).getByText('ดูทั้งหมด →'));
      expect(probe()).toBe('/finance?action=confirmDeposit');
      cleanup();
      renderOverview();
      await findGroup('chaseOverdue');
      fireEvent.click(within(group('chaseOverdue')).getByText('อีก 1 รายการ'));
      expect(probe()).toBe('/finance?action=chaseOverdue');
    });

    it('hides empty groups, and shows the existing empty line when nothing is left to do', async () => {
      serve([NO_ACTION, HOLD_A], []);
      renderOverview();
      await screen.findByText('ไม่มีรายการที่ต้องดำเนินการตอนนี้');
      GROUP_ORDER.forEach((k) => expect(screen.queryByTestId(`worklist-group-${k}`), k).toBeNull());
    });

    it('a deal outside every step (nothing for account to do) never gets a row', async () => {
      renderOverview();
      await findGroup('chaseOverdue');
      expect(screen.queryByText('ลูกค้ารอสินค้า 701')).toBeNull();
    });
  });

  describe('รอสินค้าเข้า (final payment held by the stage gate)', () => {
    const panel = () => screen.getByTestId('awaiting-goods-panel');

    it('lists the deals owing a balance that the gate holds, with a count and outstanding total, and no button', async () => {
      renderOverview();
      await screen.findByTestId('awaiting-goods-panel');
      expect(within(panel()).getByText('รอสินค้าเข้า')).not.toBeNull();
      expect(panel().textContent).toContain('2 ดีล · ฿105,000.00');
      const a = within(panel()).getByTestId('awaiting-goods-row-601');
      expect(a.textContent).toContain('ลูกค้ารอสินค้า 601');
      expect(a.textContent).toContain('HD-601 · ขั้น 3 รอสินค้า / นำเข้า');
      expect(a.textContent).toContain('฿80,000.00');
      expect(within(panel()).getByTestId('awaiting-goods-row-602').textContent).toContain('฿25,000.00'); // bypass-confirmed
      expect(within(panel()).queryAllByRole('button')).toHaveLength(0);
      expect(panel().textContent).toContain('รับชำระส่วนที่เหลือได้เมื่อดีลถึงขั้น 4 ส่งมอบ');
    });

    it('leaves out deals that are already offered the step, overdue, or not owing at all', async () => {
      serve([...SCOPED, HELD_OVERDUE]);
      renderOverview();
      await screen.findByTestId('awaiting-goods-panel');
      [301, 701, 702, 101, 201].forEach((id) => expect(within(panel()).queryByTestId(`awaiting-goods-row-${id}`), String(id)).toBeNull());
      // …and none of the held deals appears as a worklist row
      expect(screen.queryByTestId('worklist-row-confirmFinalPayment-601')).toBeNull();
    });

    it('is hidden entirely when no deal is held', async () => {
      serve([OVERDUE[0], FINAL], []);
      renderOverview();
      await findGroup('chaseOverdue');
      expect(screen.queryByTestId('awaiting-goods-panel')).toBeNull();
      expect(screen.queryByText('รอสินค้าเข้า')).toBeNull();
    });
  });
});
