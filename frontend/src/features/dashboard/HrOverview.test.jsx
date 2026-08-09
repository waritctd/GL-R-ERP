import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { HrOverview } from './HrOverview.jsx';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      payroll: {
        current: vi.fn().mockResolvedValue({ period: null }),
        getTaxAllowanceDeclarations: vi.fn().mockResolvedValue({
          items: [
            { declarationId: 1, employeeId: 9, status: 'PENDING' },
            { declarationId: 2, employeeId: 10, status: 'PENDING' },
            { declarationId: 3, employeeId: 11, status: 'APPROVED' },
          ],
        }),
      },
    },
  };
});

const employee = { nameTh: 'ฝ่ายบุคคล ทดสอบ', positionTh: 'เจ้าหน้าที่บุคคล', divisionTh: 'HR-ฝ่ายบุคคล' };
const employees = [
  { id: 1, active: true, divisionId: '17', divisionTh: 'HR-ฝ่ายบุคคล' },
  { id: 2, active: true, divisionId: '9', divisionTh: 'SA-ฝ่ายขาย' },
];

const dashboardSummary = {
  headcount: {
    scope: 'all',
    total: 28,
    active: 26,
    byDivision: [
      { divisionId: '17', divisionCode: 'HR', divisionName: 'HR-ฝ่ายบุคคล', total: 4, active: 4 },
      { divisionId: '9', divisionCode: 'SA', divisionName: 'SA-ฝ่ายขาย', total: 12, active: 11 },
    ],
  },
  pendingApprovals: { total: 6, profileRequests: 3, overtime: 2, leave: 1, commissions: 0 },
  attendance: { todayPresent: 20, lateToday: 2, missingCheckout: 1 },
  notifications: { unread: 5 },
};

function renderOverview(summary = dashboardSummary) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/']}>
        <HrOverview employee={employee} employees={employees} dashboardSummary={summary} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('HrOverview', () => {
  it('renders the HR greeting', () => {
    renderOverview();
    expect(screen.getByText('สวัสดี ฝ่ายบุคคล')).not.toBeNull();
    expect(screen.getByText('งานบุคคลวันนี้ · การลงเวลา · รอบเงินเดือน')).not.toBeNull();
  });

  it('shows the profile-request review CTA wired to /requests', () => {
    renderOverview();
    expect(screen.getByText('คำขอแก้ไขข้อมูลพนักงาน')).not.toBeNull();
    expect(screen.getByText('3 รายการรอตรวจ')).not.toBeNull();
    expect(screen.getByText('ตรวจคำขอ').closest('button')).not.toBeNull();
  });

  it('shows payroll period status with a CTA to /payroll', () => {
    renderOverview();
    expect(screen.getByText('รอบเงินเดือน')).not.toBeNull();
    expect(screen.getByText('ยังไม่เริ่มรอบเงินเดือนเดือนนี้')).not.toBeNull();
    expect(screen.getByText('ไปที่หน้าเงินเดือน').closest('button')).not.toBeNull();
  });

  // Regression guard for a defect only the REAL backend could show. PayrollController
  // #currentOrPreview declares `payrollMonth` as a bare @RequestParam, so Spring rejects a call
  // without it with 400 before parseMonth runs; this component's `.catch(() => null)` then rendered
  // that as "ยังไม่เริ่มรอบเงินเดือนเดือนนี้" — HR's landing page claiming no payroll run existed,
  // every month, whether or not one did. Measured on the UAT seed: the same request WITH the param
  // returns a PREVIEW period of 91 lines.
  //
  // Nothing in the mock-driven suite could catch it. mockApi.payroll.current defaults the month
  // when the caller omits it, and contract.test.js compares parameter COUNTS — one `params` bag
  // either way. So the assertion has to be on the argument itself, which is what this does: revert
  // to `api.payroll.current()` and this test goes red while every other test here stays green.
  it('asks the backend for a specific payroll month — the real @RequestParam is mandatory', async () => {
    const { api } = await import('../../api/index.js');
    api.payroll.current.mockClear();
    renderOverview();
    await screen.findByText('ยังไม่เริ่มรอบเงินเดือนเดือนนี้');
    expect(api.payroll.current).toHaveBeenCalledWith(
      expect.objectContaining({ payrollMonth: expect.stringMatching(/^\d{4}-\d{2}$/) }),
    );
  });

  it('shows today attendance counts', () => {
    renderOverview();
    const panel = screen.getByText('การลงเวลาวันนี้').closest('section');
    const rows = within(panel);
    expect(rows.getByText('มาแล้ว')).not.toBeNull();
    expect(rows.getByText('มาสาย')).not.toBeNull();
    expect(rows.getByText('ยังไม่ออก')).not.toBeNull();
    expect(rows.getByText('20')).not.toBeNull();
    expect(rows.getByText('2')).not.toBeNull();
    expect(rows.getByText('1')).not.toBeNull();
  });

  it('renders leave/OT as monitoring-only, with no approve button anywhere on the page', () => {
    renderOverview();
    const monitorPanel = screen.getByText('ภาพรวมลา / OT (ดูอย่างเดียว)').closest('section');
    expect(within(monitorPanel).getByText('อนุมัติโดยหัวหน้าฝ่าย/CEO — ฝ่ายบุคคลติดตามสถานะเท่านั้น')).not.toBeNull();
    expect(within(monitorPanel).getByText('คำขอลารออนุมัติ')).not.toBeNull();
    expect(within(monitorPanel).getByText('OT รออนุมัติ')).not.toBeNull();

    // No button anywhere on the page is an approval action (e.g. "อนุมัติ") —
    // HR approves nothing here; it only monitors leave/OT. This deliberately
    // does not match "รออนุมัติ" ("awaiting approval") labels, which are
    // informational counts, not approve buttons.
    const approveButtons = screen.queryAllByRole('button')
      .filter((button) => /^อนุมัติ/.test(button.textContent.trim()));
    expect(approveButtons).toHaveLength(0);
  });

  it('links the leave/OT monitor rows to the read-only /leave and /overtime pages', () => {
    renderOverview();
    const monitorPanel = screen.getByText('ภาพรวมลา / OT (ดูอย่างเดียว)').closest('section');
    const leaveRow = within(monitorPanel).getByText('คำขอลารออนุมัติ').closest('button');
    const otRow = within(monitorPanel).getByText('OT รออนุมัติ').closest('button');
    expect(leaveRow).not.toBeNull();
    expect(otRow).not.toBeNull();
  });

  it('renders the headcount-by-division list from the dashboard summary', () => {
    renderOverview();
    const panel = screen.getByText('จำนวนพนักงานตามฝ่าย').closest('section');
    const rows = within(panel);
    expect(rows.getByText('HR-ฝ่ายบุคคล')).not.toBeNull();
    expect(rows.getByText('SA-ฝ่ายขาย')).not.toBeNull();
  });

  it('surfaces the ล.ย.01 review queue as HR work, counting only PENDING rows', async () => {
    renderOverview();
    const panel = screen.getByText('งานของฝ่ายบุคคล').closest('section');
    expect(within(panel).getByText('แบบแจ้ง ล.ย.01 (ค่าลดหย่อนภาษี)')).not.toBeNull();
    // Two PENDING out of three declarations — the APPROVED row must not be counted. Awaited: the
    // label renders synchronously, the count only once the register query resolves.
    expect(await within(panel).findByText('2 รายการรอตรวจ')).not.toBeNull();
  });

  it('does not render a leave/OT queue row in the ต้องดำเนินการ action queue', () => {
    renderOverview();
    const queue = screen.getByText('ต้องดำเนินการ').closest('section');
    const rows = within(queue);
    expect(rows.getByText('คำขอแก้ไขข้อมูลรออนุมัติ')).not.toBeNull();
    expect(rows.queryByText('OT รออนุมัติ')).toBeNull();
    expect(rows.queryByText('ลารออนุมัติ')).toBeNull();
  });
});
