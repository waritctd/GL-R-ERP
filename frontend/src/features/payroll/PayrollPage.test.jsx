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
      exportFile: vi.fn(),
      downloadPayslip: vi.fn(),
      distributePayslips: vi.fn(),
      suggestedInputs: vi.fn(),
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
    api.payroll.exportFile.mockResolvedValue(new Blob(['HPCT'], { type: 'application/octet-stream' }));
    api.payroll.distributePayslips.mockResolvedValue({ periodId: 7, totalLines: 1, alreadySent: 0, queued: 1 });
    api.payroll.suggestedInputs.mockResolvedValue({ payrollMonth: '2026-07-01', suggestions: [] });
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

  it('Refresh recomputes a processed month live (Preview), never committing', async () => {
    // A month that was already Processed loads from its saved snapshot (api.payroll.current) — which
    // freezes commission/OT/etc. from when it ran. Clicking รีเฟรช must pull the latest via a live
    // recompute (api.payroll.preview) and must NOT process/commit the month.
    api.payroll.current.mockResolvedValue({ period: previewPeriod({ id: 7, status: 'PROCESSED' }) });

    renderPayrollPage();

    const refreshButton = await screen.findByRole('button', { name: /รีเฟรช/ });
    expect(api.payroll.preview).not.toHaveBeenCalled();

    fireEvent.click(refreshButton);

    await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
    expect(api.payroll.process).not.toHaveBeenCalled();
  });

  // Leave -> payroll unpaid-day deduction (2026-07-23). The unpaidLeaveDays field lives inside the
  // "รายการหักรายบุคคล" CollapsibleSection, which defaults to collapsed -- and CollapsibleSection
  // unmounts its body entirely (not CSS-hidden) while collapsed, so every test here must expand it
  // before the field exists in the DOM.
  async function expandUnpaidLeaveSection() {
    fireEvent.click(await screen.findByRole('button', { name: /รายการหักรายบุคคล/ }));
    // The `selector: 'input'` filter is needed because the field's InfoTip button carries the same
    // accessible name ("วันลาไม่รับค่าจ้าง") as an aria-label -- without it, getByLabelText matches
    // both the input and the InfoTip trigger and throws "Found multiple elements".
    return screen.findByLabelText(/วันลาไม่รับค่าจ้าง/, { selector: 'input' });
  }

  describe('leave-derived unpaidLeaveDays suggestion', () => {
    it('pre-fills unpaidLeaveDays from the leave-derived suggestion on a fresh PREVIEW run', async () => {
      api.payroll.suggestedInputs.mockResolvedValue({
        payrollMonth: '2026-07-01',
        suggestions: [{ employeeId: 1, unpaidLeaveDays: 1.5, pendingUnpaidLeaveCorrectionDays: 0 }],
      });

      renderPayrollPage();

      const unpaidLeaveDays = await expandUnpaidLeaveSection();
      expect(unpaidLeaveDays.value).toBe('1.5');
    });

    it('lets HR override the pre-filled unpaidLeaveDays suggestion before submitting', async () => {
      api.payroll.suggestedInputs.mockResolvedValue({
        payrollMonth: '2026-07-01',
        suggestions: [{ employeeId: 1, unpaidLeaveDays: 1.5, pendingUnpaidLeaveCorrectionDays: 0 }],
      });

      renderPayrollPage();

      const unpaidLeaveDays = await expandUnpaidLeaveSection();
      expect(unpaidLeaveDays.value).toBe('1.5');

      fireEvent.change(unpaidLeaveDays, { target: { value: '2' } });
      fireEvent.click(screen.getByRole('button', { name: /Preview/i }));

      await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
      const submittedInput = api.payroll.preview.mock.calls[0][0].inputs.find((input) => input.employeeId === 1);
      expect(submittedInput.unpaidLeaveDays).toBe(2);
    });

    it('a real (already-persisted) line value on a PROCESSED period wins over any suggestion', async () => {
      api.payroll.current.mockResolvedValue({
        period: previewPeriod({ id: 7, status: 'PROCESSED', lines: [{ ...payrollLine, unpaidLeaveDays: 3 }] }),
      });
      api.payroll.suggestedInputs.mockResolvedValue({
        payrollMonth: '2026-07-01',
        suggestions: [{ employeeId: 1, unpaidLeaveDays: 1.5, pendingUnpaidLeaveCorrectionDays: 0 }],
      });

      renderPayrollPage();

      const unpaidLeaveDays = await expandUnpaidLeaveSection();
      expect(unpaidLeaveDays.value).toBe('3');
      // PROCESSED periods never fetch suggestions at all (see load()'s guard).
      expect(api.payroll.suggestedInputs).not.toHaveBeenCalled();
    });

    it('shows a hint for an unresolved cancel-after-close correction credit, without changing the field value', async () => {
      api.payroll.suggestedInputs.mockResolvedValue({
        payrollMonth: '2026-07-01',
        suggestions: [{ employeeId: 1, unpaidLeaveDays: 0, pendingUnpaidLeaveCorrectionDays: 1 }],
      });

      renderPayrollPage();

      const unpaidLeaveDays = await expandUnpaidLeaveSection();
      expect(unpaidLeaveDays.value).toBe('');
      // findByText throws (failing the test) if the hint isn't present -- no jest-dom matchers are
      // set up in this project's vitest config, so there's nothing to chain here.
      await screen.findByText(/เครดิตวันลาไม่รับค่าจ้างค้างคืน/);
    });

    // Cancel-after-close reversal, AUTO-REFUND (2026-07-23): the backend now applies the correction
    // itself (PayrollService#preview/#process) rather than only surfacing a "please adjust manually"
    // suggestion -- these two fields (leaveRefundDays/leaveDeductionRefund) live on the CALCULATED
    // line the API returns, not on the suggestion or the HR-editable adjustment form.
    it('shows the auto-applied refund on a line that already includes one, and drops the stale manual-entry hint', async () => {
      api.payroll.current.mockResolvedValue({
        period: previewPeriod({
          lines: [{ ...payrollLine, leaveRefundDays: 1, leaveDeductionRefund: 1000 }],
        }),
      });
      api.payroll.suggestedInputs.mockResolvedValue({
        payrollMonth: '2026-07-01',
        suggestions: [{ employeeId: 1, unpaidLeaveDays: 0, pendingUnpaidLeaveCorrectionDays: 1 }],
      });

      renderPayrollPage();
      await expandUnpaidLeaveSection();

      // The new auto-applied hint appears...
      await screen.findByText(/ระบบคืนเครดิตวันลาไม่รับค่าจ้างค้างคืน 1 วัน/);
      // ...and the old "please adjust manually, not automatic yet" wording is gone -- that claim is no
      // longer true and would risk HR double-entering the credit into unpaidLeaveDays by hand.
      expect(screen.queryByText(/กรุณาปรับตัวเลขด้านบนด้วยตนเอง/)).toBeNull();
      // The breakdown panel also shows the refund amount as its own line.
      await screen.findByText(/คืนเครดิตวันลาไม่รับค่าจ้าง \(1 วัน\)/);
    });

    it('does not show any refund hint when there is no refund on the line and no pending correction', async () => {
      renderPayrollPage();
      await expandUnpaidLeaveSection();

      expect(screen.queryByText(/เครดิตวันลาไม่รับค่าจ้างค้างคืน/)).toBeNull();
      expect(screen.queryByText(/คืนเครดิตวันลาไม่รับค่าจ้าง \(/)).toBeNull();
    });
  });

  it('generates the selected statutory export file with the chosen pay date', async () => {
    api.payroll.current.mockResolvedValue({ period: previewPeriod({ id: 7, status: 'PROCESSED' }) });

    renderPayrollPage();

    // Pick PND1 from the dropdown, then download.
    const kindSelect = await screen.findByLabelText('ประเภทไฟล์ที่จะสร้าง');
    fireEvent.change(kindSelect, { target: { value: 'pnd1' } });
    fireEvent.click(screen.getByRole('button', { name: /ดาวน์โหลดไฟล์/ }));

    // Pay date defaults to the 26th of the current payroll month (kept month-agnostic here).
    await waitFor(() => expect(api.payroll.exportFile)
      .toHaveBeenCalledWith(7, 'pnd1', expect.stringMatching(/^\d{4}-\d{2}-26$/)));
  });
});
