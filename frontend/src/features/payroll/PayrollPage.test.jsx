import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { PayrollPage } from './PayrollPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    payroll: {
      current: vi.fn(),
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

const payrollLine = {
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

function previewPeriod(overrides = {}) {
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
    lines: [payrollLine],
    ...overrides,
  };
}

function renderPayrollPage() {
  return render(<PayrollPage showToast={vi.fn()} />);
}

describe('PayrollPage adjustment inputs', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    URL.createObjectURL = vi.fn(() => 'blob:payslip');
    URL.revokeObjectURL = vi.fn();
    api.payroll.current.mockResolvedValue({ period: previewPeriod() });
    api.payroll.preview.mockResolvedValue({ period: previewPeriod() });
    api.payroll.downloadPayslip.mockResolvedValue(new Blob(['pdf'], { type: 'application/pdf' }));
    api.payroll.distributePayslips.mockResolvedValue({ periodId: 7, totalLines: 1, alreadySent: 0, queued: 1 });
  });

  it('uses Excel-based UAT defaults and shows a Baht prefix on money fields', async () => {
    render(<PayrollPage showToast={vi.fn()} />);

    const costOfLiving = await screen.findByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/);
    const gprs = screen.getByLabelText(/พิเศษ 5 \(ค่า GPRS\)/);
    const allowance = screen.getByLabelText(/พิเศษ 2 \(เบี้ยเลี้ยงประจำ\)/);

    expect(costOfLiving.value).toBe('500');
    expect(gprs.value).toBe('500');
    expect(allowance.value).toBe('');
    expect(costOfLiving.parentElement.querySelector('.currency-input-symbol').textContent).toBe('฿');
  });

  it('allows clearing zero/default amounts and sends them as zeroes', async () => {
    render(<PayrollPage showToast={vi.fn()} />);

    const costOfLiving = await screen.findByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/);
    const gprs = screen.getByLabelText(/พิเศษ 5 \(ค่า GPRS\)/);

    fireEvent.change(costOfLiving, { target: { value: '' } });
    fireEvent.change(gprs, { target: { value: '' } });

    expect(costOfLiving.value).toBe('');
    expect(gprs.value).toBe('');

    fireEvent.click(screen.getByRole('button', { name: /Preview/i }));

    await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
    expect(api.payroll.preview.mock.calls[0][0].inputs).toEqual([]);
    expect(screen.getByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/).value).toBe('');
  });

  it('downloads a saved payslip for the selected payroll line', async () => {
    const processedPeriod = previewPeriod({ id: 7, status: 'PROCESSED' });
    api.payroll.current.mockResolvedValue({ period: processedPeriod });

    renderPayrollPage();

    fireEvent.click(await screen.findByRole('button', { name: /Download payslip/i }));

    await waitFor(() => expect(api.payroll.downloadPayslip).toHaveBeenCalledWith(7, 55));
  });

  it('starts payslip email distribution for a processed payroll period', async () => {
    api.payroll.current.mockResolvedValue({ period: previewPeriod({ id: 7, status: 'PROCESSED' }) });

    renderPayrollPage();

    fireEvent.click(await screen.findByRole('button', { name: /Email payslips/i }));

    await waitFor(() => expect(api.payroll.distributePayslips).toHaveBeenCalledWith(7));
  });
});
