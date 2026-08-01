import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { PayrollPage } from './PayrollPage.jsx';
import { api } from '../../api/index.js';

// Daily-rate (pay_type = 'D') support (2026-07-30): a NEW test file (does not edit
// PayrollPage.test.jsx / PayrollPage.carryForward.test.jsx) proving the reachability chain the
// backend fix needs on the frontend: the days-worked field is rendered ONLY for a daily-rate
// employee (gated on the new `payType` field the backend now echoes onto each line), the value
// reaches the backend in the submitted `inputs` payload, and it survives a reload (hydrated back
// from a persisted line's own `daysWorked`, exactly like every other one-off field on this page).

globalThis.React = React;

// hasPermission(user?.role, 'canManagePayroll') reads false for an absent user (issue #390 --
// canManage is an allowlist off ROLE_PERMISSIONS), so every render below passes a real HR user
// explicitly rather than relying on an implicit default.
const hrUser = { role: 'hr', employeeId: 10 };

vi.mock('../../api/index.js', () => ({
  api: {
    payroll: {
      current: vi.fn(),
      preview: vi.fn(),
      process: vi.fn(),
      exportFile: vi.fn(),
      downloadPayslip: vi.fn(),
      distributePayslips: vi.fn(),
      suggestedInputs: vi.fn(),
      getComponentTaxTreatments: vi.fn(),
      saveComponentTaxTreatments: vi.fn(),
    },
  },
}));

const zeroSpecialPays = Array.from({ length: 8 }, (_, index) => ({
  key: `specialPay${index + 1}`,
  label: `เงินพิเศษ ${index + 1}`,
  amount: 0,
}));

const monthlyLine = {
  id: 55,
  employeeId: 1,
  employeeCode: 'GLR-001',
  employeeName: 'พนักงาน รายเดือน',
  departmentName: 'HR',
  baseSalary: 30000,
  dailyRate: 1000,
  payType: null,
  grossTaxableIncome: 30000,
  withholdingTax: 0,
  netPay: 29250,
  grossEarnings: 30000,
  specialPayTotal: 0,
  overtimePay: 0,
  commissionPay: 0,
  totalDeductions: 750,
  socialSecurity: 750,
  ssoWageBase: 15000,
  projectedAnnualIncome: 360000,
  taxAllowanceTotal: 100000,
  specialPays: zeroSpecialPays,
  unpaidLeaveDays: 0,
  studentLoanDeduction: 0,
  legalExecutionDeduction: 0,
  otherPostTaxDeductions: 0,
  bonusPay: 0,
  otherOneOffPay: 0,
  customerReturnDeduction: 0,
  garnishmentType: null,
  customerReturnAlreadyEarned: false,
  daysWorked: null,
};

function dailyRateLine(overrides = {}) {
  return {
    ...monthlyLine,
    id: 203,
    employeeId: 203,
    employeeCode: '10060',
    employeeName: 'พัฒวุฒิ สังฆวาส',
    baseSalary: 11250,
    dailyRate: 450,
    payType: 'D',
    grossTaxableIncome: 11250,
    netPay: 10687.5,
    grossEarnings: 11250,
    totalDeductions: 562.5,
    socialSecurity: 562.5,
    ssoWageBase: 11250,
    daysWorked: null,
    ...overrides,
  };
}

function previewPeriodWith(line) {
  return {
    id: null,
    payrollMonth: '2026-07-01',
    status: 'PREVIEW',
    lineCount: 1,
    totalGross: Number(line.grossEarnings),
    totalDeductions: Number(line.totalDeductions),
    totalNet: Number(line.netPay),
    totalSocialSecurity: Number(line.socialSecurity),
    totalWithholdingTax: 0,
    lines: [line],
  };
}

describe('PayrollPage daily-rate (pay_type = D) support', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.payroll.suggestedInputs.mockResolvedValue({ payrollMonth: '2026-07-01', suggestions: [] });
    api.payroll.getComponentTaxTreatments.mockResolvedValue({ taxYear: 2026, items: [] });
    api.payroll.saveComponentTaxTreatments.mockResolvedValue({ taxYear: 2026, items: [] });
  });

  it('does not render the days-worked field for a monthly (pay_type != D) employee', async () => {
    api.payroll.current.mockResolvedValue({ period: previewPeriodWith(monthlyLine) });

    render(<PayrollPage user={hrUser} showToast={vi.fn()} />);

    await waitFor(() => expect(screen.getAllByText('พนักงาน รายเดือน').length).toBeGreaterThan(0));
    expect(screen.queryByLabelText(/จำนวนวันทำงานในงวดนี้/, { selector: 'input' })).toBeNull();
  });

  it('renders the days-worked field for a daily-rate (pay_type = D) employee, reachable from payType', async () => {
    api.payroll.current.mockResolvedValue({ period: previewPeriodWith(dailyRateLine()) });

    render(<PayrollPage user={hrUser} showToast={vi.fn()} />);

    const daysWorked = await screen.findByLabelText(/จำนวนวันทำงานในงวดนี้/, { selector: 'input' });
    expect(daysWorked).not.toBeNull();
    expect(daysWorked.value).toBe('');
  });

  it('a typed days-worked value reaches the backend in the submitted inputs payload', async () => {
    api.payroll.current.mockResolvedValue({ period: previewPeriodWith(dailyRateLine()) });
    api.payroll.preview.mockResolvedValue({ period: previewPeriodWith(dailyRateLine()) });

    render(<PayrollPage user={hrUser} showToast={vi.fn()} />);

    const daysWorked = await screen.findByLabelText(/จำนวนวันทำงานในงวดนี้/, { selector: 'input' });
    fireEvent.change(daysWorked, { target: { value: '25' } });
    expect(daysWorked.value).toBe('25');

    fireEvent.click(screen.getByRole('button', { name: /คำนวณตัวอย่าง/i }));

    await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
    const submittedInput = api.payroll.preview.mock.calls[0][0].inputs.find((item) => item.employeeId === 203);
    expect(submittedInput).toBeDefined();
    expect(submittedInput.daysWorked).toBe(25);
  });

  it('a persisted daysWorked survives a reload -- the field pre-fills from the line, not blank', async () => {
    api.payroll.current.mockResolvedValue({
      period: previewPeriodWith(dailyRateLine({ daysWorked: 25 })),
    });

    render(<PayrollPage user={hrUser} showToast={vi.fn()} />);

    const daysWorked = await screen.findByLabelText(/จำนวนวันทำงานในงวดนี้/, { selector: 'input' });
    await waitFor(() => expect(daysWorked.value).toBe('25'));
  });

  // Finding 1/4 fixes (Opus review, 2026-07-30). The server-side guard
  // (PayrollService#requireEveryDailyRateEmployeeHasDaysWorked) is the real enforcement -- these two
  // only cover the cheap client-side pieces: the days-worked input carries the frontend max bound,
  // and a nudge warning is visible (both list row and detail panel) whenever a daily-rate employee
  // has no positive day count, and disappears the moment one is typed.

  it('the days-worked input is bounded to 31 (matching the backend @Max/DB CHECK)', async () => {
    api.payroll.current.mockResolvedValue({ period: previewPeriodWith(dailyRateLine()) });

    render(<PayrollPage user={hrUser} showToast={vi.fn()} />);

    const daysWorked = await screen.findByLabelText(/จำนวนวันทำงานในงวดนี้/, { selector: 'input' });
    expect(daysWorked.max).toBe('31');
  });

  it('warns (list row + detail panel) when a daily-rate employee has no days-worked entered yet', async () => {
    api.payroll.current.mockResolvedValue({ period: previewPeriodWith(dailyRateLine()) });

    render(<PayrollPage user={hrUser} showToast={vi.fn()} />);

    await screen.findByLabelText(/จำนวนวันทำงานในงวดนี้/, { selector: 'input' });
    expect(screen.getAllByText(/ยังไม่ได้ระบุจำนวนวันทำงาน/).length).toBeGreaterThan(0);
  });

  it('the detail-panel warning clears once a positive days-worked value is typed', async () => {
    // Scoped to the detail-panel-specific message (tied live to the in-progress draft,
    // selectedAdjustment.daysWorked) rather than the list-row one (tied to the last-loaded/computed
    // `period` snapshot, same as every other column in that row -- it only refreshes on
    // Preview/Process, by design, exactly like grossEarnings/netPay do).
    api.payroll.current.mockResolvedValue({ period: previewPeriodWith(dailyRateLine()) });

    render(<PayrollPage user={hrUser} showToast={vi.fn()} />);

    const daysWorked = await screen.findByLabelText(/จำนวนวันทำงานในงวดนี้/, { selector: 'input' });
    expect(screen.queryByText(/พนักงานรายวันจะได้รับเงินเดือน/)).not.toBeNull();

    fireEvent.change(daysWorked, { target: { value: '25' } });

    await waitFor(() => expect(screen.queryByText(/พนักงานรายวันจะได้รับเงินเดือน/)).toBeNull());
  });
});
