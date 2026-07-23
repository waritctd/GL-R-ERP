import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { PayrollPage } from './PayrollPage.jsx';
import { api } from '../../api/index.js';

// Special-pay carry-forward (2026-07-23): a NEW test file (does not edit PayrollPage.test.jsx,
// which a concurrent branch, feat/payroll-statutory-export-files, also modifies) covering the
// pre-fill from GET /api/payroll/suggested-inputs and that an HR edit overrides the pre-filled value.

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    payroll: {
      current: vi.fn(),
      suggestedInputs: vi.fn(),
      preview: vi.fn(),
      process: vi.fn(),
      bankExport: vi.fn(),
      downloadPayslip: vi.fn(),
      distributePayslips: vi.fn(),
    },
  },
}));

const zeroSpecialPays = Array.from({ length: 8 }, (_, index) => ({
  key: `specialPay${index + 1}`,
  label: `เงินพิเศษ ${index + 1}`,
  amount: 0,
}));

const freshPayrollLine = {
  id: 55,
  employeeId: 1,
  employeeCode: 'GLR-001',
  employeeName: 'พนักงาน ทดสอบ',
  departmentName: 'HR',
  baseSalary: 30000,
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
};

function freshPreviewPeriod(overrides = {}) {
  return {
    id: null,
    payrollMonth: '2026-07-01',
    status: 'PREVIEW',
    lineCount: 1,
    totalGross: 30000,
    totalDeductions: 750,
    totalNet: 29250,
    totalSocialSecurity: 750,
    totalWithholdingTax: 0,
    lines: [freshPayrollLine],
    ...overrides,
  };
}

describe('PayrollPage special-pay carry-forward', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.payroll.current.mockResolvedValue({ period: freshPreviewPeriod() });
    api.payroll.preview.mockResolvedValue({ period: freshPreviewPeriod() });
  });

  it('pre-fills carried fields from suggested-inputs on a fresh run, without touching non-carried fields', async () => {
    api.payroll.suggestedInputs.mockResolvedValue({
      payrollMonth: '2026-07-01',
      suggestions: [
        {
          employeeId: 1,
          specialPay1: 700,
          specialPay2: 300,
          specialPay3: 0,
          specialPay4: 0,
          specialPay5: 650,
          nonTaxableIncome: 1200,
          studentLoanDeduction: 900,
          legalExecutionDeduction: 0,
        },
      ],
    });

    render(<PayrollPage showToast={vi.fn()} />);

    // Deliberately not asserting the exact month string (it derives from "today", so hardcoding it
    // would make the test flake across a real month boundary) — only that a payrollMonth was passed.
    await waitFor(() => expect(api.payroll.suggestedInputs).toHaveBeenCalledWith(
      expect.objectContaining({ payrollMonth: expect.any(String) }),
    ));

    // Carried special-pay fields reflect last month's real figures (overriding the 500 UAT demo
    // default for specialPay1/5, since a real carried value is more accurate than the placeholder).
    const costOfLiving = await screen.findByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/);
    await waitFor(() => expect(costOfLiving.value).toBe('700'));
    expect(screen.getByLabelText(/พิเศษ 2 \(เบี้ยเลี้ยงประจำ\)/).value).toBe('300');
    expect(screen.getByLabelText(/พิเศษ 5 \(ค่า GPRS\)/).value).toBe('650');

    // The non-taxable-income and per-employee-deduction fields live inside collapsed sections
    // (CollapsibleSection unmounts its body while closed), so expand them first. The `selector:
    // 'input'` option is needed because each field's InfoTip trigger button also carries an
    // aria-label starting with the same field label text, which would otherwise match twice.
    fireEvent.click(screen.getByRole('button', { name: /รายได้ไม่คิดภาษี/ }));
    fireEvent.click(screen.getByRole('button', { name: /รายการหักรายบุคคล/ }));
    expect(screen.getByLabelText(/รายได้อื่นๆ \(ไม่คิดภาษี\)/, { selector: 'input' }).value).toBe('1200');
    expect(screen.getByLabelText(/หัก กยศ\./, { selector: 'input' }).value).toBe('900');

    // specialPay6/7/8 (commission/KPI/bonus) and event-driven fields must never be pre-filled, even
    // though the mock only omits them from the suggestion payload here (they aren't carried at all).
    expect(screen.getByLabelText(/พิเศษ 6 \(คอมมิชชั่น\)/).value).toBe('');
    expect(screen.getByLabelText(/พิเศษ 7 \(ทำได้ตาม KPI\)/).value).toBe('');
    expect(screen.getByLabelText(/พิเศษ 8/).value).toBe('');
  });

  it('lets HR override a pre-filled carried value, and the edited value — not the suggestion — is what gets submitted', async () => {
    api.payroll.suggestedInputs.mockResolvedValue({
      payrollMonth: '2026-07-01',
      suggestions: [
        { employeeId: 1, specialPay1: 700, specialPay2: 0, specialPay3: 0, specialPay4: 0, specialPay5: 0, nonTaxableIncome: 0, studentLoanDeduction: 0, legalExecutionDeduction: 0 },
      ],
    });

    render(<PayrollPage showToast={vi.fn()} />);

    const costOfLiving = await screen.findByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/);
    await waitFor(() => expect(costOfLiving.value).toBe('700'));

    // HR overrides the pre-filled 700 with 950.
    fireEvent.change(costOfLiving, { target: { value: '950' } });
    expect(costOfLiving.value).toBe('950');

    fireEvent.click(screen.getByRole('button', { name: /Preview/i }));

    await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
    const submittedInput = api.payroll.preview.mock.calls[0][0].inputs.find((item) => item.employeeId === 1);
    expect(submittedInput.specialPay1).toBe(950);
  });

  it('never fetches suggestions once a period is already processed for the month', async () => {
    api.payroll.current.mockResolvedValue({
      period: freshPreviewPeriod({ id: 7, status: 'PROCESSED' }),
    });

    render(<PayrollPage showToast={vi.fn()} />);

    await screen.findByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/);
    expect(api.payroll.suggestedInputs).not.toHaveBeenCalled();
  });
});
