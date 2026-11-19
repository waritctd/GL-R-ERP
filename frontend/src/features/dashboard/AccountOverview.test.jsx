import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AccountOverview } from './AccountOverview.jsx';
import { api } from '../../api/index.js';
import { DEAL_STAGE_CATALOG } from '../../data/dealStageCatalog.js';
import { expectFigure, figureRows } from '../../test/fixtures/dashboardMoney.js';

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
// overdueOutstanding is deliberately not what the loaded rows add up to (200,000.00): one definition per figure.
const SUMMARY = { financeOutstanding: 777000.5, cashReceivedThisMonth: 321000.5, overdueOutstanding: 555000.25 };

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
// Resolves once the group has settled (rows or failed), not merely once its loading skeleton is mounted.
const findGroup = (key) => waitFor(() => {
  const el = screen.getByTestId(`worklist-group-${key}`);
  expect(el.getAttribute('aria-busy')).toBeNull();
  return el;
});
describe('AccountOverview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.meta.dealStages.mockResolvedValue(DEAL_STAGE_CATALOG);
    serve();
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

    it('เกินกำหนดชำระ is the summary\'s overdueOutstanding (as on the CEO page), never a sum of the loaded rows', async () => {
      renderOverview(); // server 555,000.25 vs 200,000.00 in the list
      await findGroup('chaseOverdue');
      expectFigure('เกินกำหนดชำระ', '฿555,000.25');
      expect(figureRows('เกินกำหนดชำระ')[0]).not.toContain('200,000.00');
    });

    it('รอรับมัดจำ sums depositDue (not the deal outstanding) and counts the issued notices', async () => {
      renderOverview();
      await findGroup('confirmDeposit');
      expectFigure('รอรับมัดจำ', '฿95,418.10');
      expectFigure('รอรับมัดจำ', 'ใบแจ้งมัดจำที่ออกแล้ว 2 ใบ');
      expect(figureRows('รอรับมัดจำ')[0]).not.toContain('263,545.25');
    });
  });

  describe('สิ่งที่ต้องทำ worklist', () => {
    it('each group header carries its deal count and its own total (outstanding / depositDue / deal value)', async () => {
      renderOverview();
      await findGroup('recordInvoiceCommission');
      expect(group('chaseOverdue').textContent).toContain('ติดตามชำระ');
      expect(group('chaseOverdue').textContent).toContain('4 งาน · ค้าง ฿200,000.00');
      expect(group('confirmDeposit').textContent).toContain('2 งาน · มัดจำ ฿95,418.10');
      expect(group('confirmFinalPayment').textContent).toContain('1 งาน · ค้าง ฿40,000.00');
      expect(group('confirmCloseReady').textContent).toContain('1 งาน · มูลค่างาน ฿648,000.00');
      expect(group('recordInvoiceCommission').textContent).toContain('1 งาน · มูลค่างาน ฿792,000.00');
    });
  });

  describe('รอสินค้าเข้า (final payment held by the stage gate)', () => {
    const panel = () => screen.getByTestId('awaiting-goods-panel');

    it('lists the deals owing a balance that the gate holds, with a count and outstanding total, and no button', async () => {
      renderOverview();
      await screen.findByTestId('awaiting-goods-panel');
      expect(within(panel()).getByText('รอสินค้าเข้า')).not.toBeNull();
      expect(panel().textContent).toContain('2 งาน · ฿105,000.00');
      const a = within(panel()).getByTestId('awaiting-goods-row-601');
      expect(a.textContent).toContain('ลูกค้ารอสินค้า 601');
      expect(a.textContent).toContain('HD-601 · ขั้น 3 รอสินค้า / นำเข้า');
      expect(a.textContent).toContain('฿80,000.00');
      expect(within(panel()).getByTestId('awaiting-goods-row-602').textContent).toContain('฿25,000.00'); // bypass-confirmed
      expect(within(panel()).queryAllByRole('button')).toHaveLength(0);
      expect(panel().textContent).toContain('รับชำระส่วนที่เหลือได้เมื่องานถึงขั้น 4 ส่งมอบ');
    });

    it('leaves out deals that are already offered the step, overdue, or not owing at all', async () => {
      serve([...SCOPED, HELD_OVERDUE]);
      renderOverview();
      await screen.findByTestId('awaiting-goods-panel');
      [301, 701, 702, 101, 201].forEach((id) => expect(within(panel()).queryByTestId(`awaiting-goods-row-${id}`), String(id)).toBeNull());
      // …and none of the held deals appears as a worklist row
      expect(screen.queryByTestId('worklist-row-confirmFinalPayment-601')).toBeNull();
    });
  });
});
