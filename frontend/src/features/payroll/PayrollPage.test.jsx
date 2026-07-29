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

// Computed the same way PayrollPage.jsx's own module-level `thisMonth` const is (`new
// Date().toISOString().slice(0, 7)`) -- the page defaults its `month` state to this value, and
// `payload()` submits `` `${month}-01` ``. Used below to pin down that exact "-01" suffix (a
// mutation-testing survivor: changing it to "-02" left the suite green).
const thisMonth = new Date().toISOString().slice(0, 7);

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
    const gprs = screen.getByLabelText(/พิเศษ 6 \(ค่า GPRS\)/);
    const allowance = screen.getByLabelText(/พิเศษ 3 \(เบี้ยเลี้ยงประจำ\)/);

    expect(costOfLiving.value).toBe('500');
    expect(gprs.value).toBe('500');
    expect(allowance.value).toBe('');
    expect(costOfLiving.parentElement.querySelector('.currency-input-symbol').textContent).toBe('฿');
  });

  it('allows clearing zero/default amounts and sends them as zeroes', async () => {
    render(<PayrollPage showToast={vi.fn()} />);

    const costOfLiving = await screen.findByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/);
    const gprs = screen.getByLabelText(/พิเศษ 6 \(ค่า GPRS\)/);

    fireEvent.change(costOfLiving, { target: { value: '' } });
    fireEvent.change(gprs, { target: { value: '' } });

    expect(costOfLiving.value).toBe('');
    expect(gprs.value).toBe('');

    fireEvent.click(screen.getByRole('button', { name: /คำนวณตัวอย่าง/i }));

    await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
    expect(api.payroll.preview.mock.calls[0][0].inputs).toEqual([]);
    // Mutation-testing survivor: `payload().payrollMonth: \`${month}-01\`` had no assertion pinning
    // down the literal "-01" -- changing it to "-02" left the whole suite green. Assert the exact
    // value, not just a shape match.
    expect(api.payroll.preview.mock.calls[0][0].payrollMonth).toBe(`${thisMonth}-01`);
    expect(screen.getByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/).value).toBe('');
  });

  it('includes an input value of exactly 1 -- the boundary of hasPayrollInput\'s `> 0` check', async () => {
    // Mutation-testing survivor: `parsePayrollNumber(input[key]) > 0` could be weakened to `> 1`
    // without failing anything, because no existing test isolated a value of exactly 1 -- it would
    // silently drop a real HR-entered "1" (e.g. 1 special-pay baht, 1 unpaid-leave day) from the
    // submitted payload. Must clear the two UAT-default fields (specialPay1/5, both 500) first --
    // otherwise they mask the mutation by satisfying `.some(...)` on their own regardless of what
    // the field under test is set to.
    renderPayrollPage();

    const costOfLiving = await screen.findByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/);
    const gprs = screen.getByLabelText(/พิเศษ 6 \(ค่า GPRS\)/);
    const allowance = screen.getByLabelText(/พิเศษ 3 \(เบี้ยเลี้ยงประจำ\)/);
    fireEvent.change(costOfLiving, { target: { value: '' } });
    fireEvent.change(gprs, { target: { value: '' } });
    fireEvent.change(allowance, { target: { value: '1' } });
    fireEvent.click(screen.getByRole('button', { name: /คำนวณตัวอย่าง/i }));

    await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
    const submitted = api.payroll.preview.mock.calls[0][0].inputs.find((input) => input.employeeId === 1);
    expect(submitted).toBeDefined();
    // เบี้ยเลี้ยงประจำ is slot 3 after the realignment to the accountant's numbering.
    expect(submitted.specialPay3).toBe(1);
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

    fireEvent.click(await screen.findByRole('button', { name: /ส่งอีเมลสลิปเงินเดือน/i }));

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
      fireEvent.click(screen.getByRole('button', { name: /คำนวณตัวอย่าง/i }));

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

  describe('per-run withholding-tax override (V88)', () => {
    async function openOverrideInput() {
      fireEvent.click(await screen.findByRole('button', { name: /รายการหักรายบุคคล/ }));
      // The field's InfoTip trigger shares the label's accessible name, so scope to the input.
      return screen.findByLabelText(/ภาษีหัก ณ ที่จ่าย \(กำหนดเอง\)/, { selector: 'input' });
    }

    it('submits a typed per-run override amount', async () => {
      renderPayrollPage();
      const override = await openOverrideInput();

      fireEvent.change(override, { target: { value: '250' } });
      fireEvent.click(screen.getByRole('button', { name: /คำนวณตัวอย่าง/i }));

      await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
      const submitted = api.payroll.preview.mock.calls[0][0].inputs.find((input) => input.employeeId === 1);
      expect(submitted.withholdingTaxOverride).toBe(250);
    });

    it('submits an override of 0 (withhold nothing) rather than dropping the input', async () => {
      renderPayrollPage();
      const override = await openOverrideInput();

      fireEvent.change(override, { target: { value: '0' } });
      fireEvent.click(screen.getByRole('button', { name: /คำนวณตัวอย่าง/i }));

      await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
      const submitted = api.payroll.preview.mock.calls[0][0].inputs.find((input) => input.employeeId === 1);
      expect(submitted).toBeDefined();
      expect(submitted.withholdingTaxOverride).toBe(0);
    });

    it('sends a blank override as null (compute/standing applies)', async () => {
      renderPayrollPage();
      const override = await openOverrideInput();
      expect(override.value).toBe('');

      fireEvent.click(screen.getByRole('button', { name: /คำนวณตัวอย่าง/i }));

      await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
      // The line is still submitted (it carries the UAT default special pays), but the untyped
      // override is null -- never 0 -- so the server computes/uses the standing value.
      const submitted = api.payroll.preview.mock.calls[0][0].inputs.find((input) => input.employeeId === 1);
      expect(submitted).toBeDefined();
      expect(submitted.withholdingTaxOverride).toBeNull();
    });

    it('pre-fills the per-run override carried from suggested-inputs, incl. a carried 0', async () => {
      api.payroll.suggestedInputs.mockResolvedValue({
        payrollMonth: '2026-07-01',
        suggestions: [{ employeeId: 1, withholdingTaxOverride: 0 }],
      });

      renderPayrollPage();
      const override = await openOverrideInput();
      // A carried 0 is a real per-run override and must pre-fill as "0", not blank.
      expect(override.value).toBe('0');
    });
  });

  // F2 (Opus review, 2026-07-30): HR could type a per-diem amount with no basis selector at all --
  // Preview always succeeded (it never writes a row), then Process 500'd on V97's
  // chk_payroll_line_per_diem_basis_present CHECK. Fixed by adding the missing amount inputs AND the
  // basis selector (shown only once an amount is entered), plus a server-side 400 as a second line of
  // defence (PayrollService#calculateLine).
  describe('per-diem basis selector (V97 / F2)', () => {
    async function openPerDiemSection() {
      fireEvent.click(await screen.findByRole('button', { name: /ค่าอาหาร \/ เบี้ยเลี้ยง/ }));
      return screen.findByLabelText(/เบี้ยเลี้ยง — ส่วนเกิน \(เสียภาษี\)/, { selector: 'input' });
    }

    it('hides the basis selector until a per-diem amount is entered', async () => {
      renderPayrollPage();
      await openPerDiemSection();

      expect(screen.queryByLabelText(/ฐานเบี้ยเลี้ยง \(มาตรา 42\)/)).toBeNull();
    });

    it('shows the basis selector once a taxable per-diem amount is entered, and hides it again when cleared', async () => {
      renderPayrollPage();
      const taxable = await openPerDiemSection();

      fireEvent.change(taxable, { target: { value: '300' } });
      const basis = await screen.findByLabelText(/ฐานเบี้ยเลี้ยง \(มาตรา 42\)/, { selector: 'select' });
      expect(basis).toBeTruthy();

      fireEvent.change(taxable, { target: { value: '' } });
      await waitFor(() => expect(screen.queryByLabelText(/ฐานเบี้ยเลี้ยง \(มาตรา 42\)/)).toBeNull());
    });

    it('shows the basis selector for the exempt amount too, not only the taxable one', async () => {
      renderPayrollPage();
      fireEvent.click(await screen.findByRole('button', { name: /ค่าอาหาร \/ เบี้ยเลี้ยง/ }));
      const exempt = await screen.findByLabelText(/เบี้ยเลี้ยง — ส่วนที่ยกเว้นภาษี \(ม\.42\)/, { selector: 'input' });

      fireEvent.change(exempt, { target: { value: '700' } });
      expect(await screen.findByLabelText(/ฐานเบี้ยเลี้ยง \(มาตรา 42\)/, { selector: 'select' })).toBeTruthy();
    });

    it('submits the chosen per-diem basis verbatim, matching the backend PerDiemBasis enum', async () => {
      renderPayrollPage();
      const taxable = await openPerDiemSection();
      fireEvent.change(taxable, { target: { value: '300' } });
      const basis = await screen.findByLabelText(/ฐานเบี้ยเลี้ยง \(มาตรา 42\)/, { selector: 'select' });
      fireEvent.change(basis, { target: { value: 'REIMBURSED_S42_1' } });

      fireEvent.click(screen.getByRole('button', { name: /คำนวณตัวอย่าง/i }));

      await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
      const submitted = api.payroll.preview.mock.calls[0][0].inputs.find((input) => input.employeeId === 1);
      expect(submitted.perDiemTaxable).toBe(300);
      expect(submitted.perDiemBasis).toBe('REIMBURSED_S42_1');
    });

    it('sends perDiemBasis as null, never a blank string, when no per-diem amount is entered', async () => {
      renderPayrollPage();
      await screen.findByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/);
      fireEvent.click(screen.getByRole('button', { name: /คำนวณตัวอย่าง/i }));

      await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
      const submitted = api.payroll.preview.mock.calls[0][0].inputs.find((input) => input.employeeId === 1);
      expect(submitted).toBeDefined();
      expect(submitted.perDiemBasis).toBeNull();
    });

    // The backend now rejects this before the INSERT (see PayrollService#calculateLine's F2 fix) with
    // a 400 naming the employee and the missing basis, instead of the DB CHECK constraint turning it
    // into a bare 500. This proves the page surfaces that rejection cleanly rather than swallowing it.
    it('surfaces the backend rejection cleanly when Process is attempted without a chosen basis', async () => {
      const showToast = vi.fn();
      api.payroll.process.mockRejectedValue(new Error(
        'พนักงาน GLR-001 พนักงาน ทดสอบ มีการจ่ายเบี้ยเลี้ยง (เบี้ยเลี้ยง ตจว/ตปท) แต่ไม่ได้ระบุฐานตามมาตรา 42'
        + ' (เหมาจ่ายตามอัตราราชการ มาตรา 42(2) หรือจ่ายจริงตามหน้าที่ มาตรา 42(1)) กรุณาเลือกฐานก่อนประมวลผลเงินเดือน',
      ));
      render(<PayrollPage showToast={showToast} />);
      const taxable = await openPerDiemSection();
      fireEvent.change(taxable, { target: { value: '300' } });

      fireEvent.click(screen.getByRole('button', { name: /ประมวลผลเงินเดือน/i }));
      fireEvent.click(await screen.findByRole('button', { name: /ยืนยันประมวลผล/i }));

      await waitFor(() => expect(api.payroll.process).toHaveBeenCalledTimes(1));
      await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', expect.stringContaining('กรุณาเลือกฐานก่อนประมวลผลเงินเดือน')));
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

  // F1: PayrollService#process (backend) doesn't need a period id -- it recomputes from
  // findActiveEmployees() and commits regardless. Opening the confirm dialog and processing while
  // the header reads "พนักงาน 0 คน" would silently zero out every HR-entered value and commit an
  // irreversible PROCESSED status. See the `emptyPeriod` comment in PayrollPage.jsx.
  describe('Process Payroll guard against an empty period (F1)', () => {
    it('disables Process Payroll when the loaded period has zero lines (lineCount: 0)', async () => {
      api.payroll.current.mockResolvedValue({ period: previewPeriod({ lineCount: 0, lines: [] }) });

      renderPayrollPage();

      const processButton = await screen.findByRole('button', { name: /ประมวลผลเงินเดือน/i });
      await waitFor(() => expect(processButton.disabled).toBe(true));
    });

    // Regression guard against "simplifying" the check to `!period?.id`: PayrollService#preview
    // returns id=null for every month that has never been processed, so id===null does NOT imply
    // an empty period -- it's the normal shape of a fresh, never-run month with real lines to
    // process. Guarding on id instead of lineCount would make first-time processing impossible.
    it('enables Process Payroll for a fresh (never-processed) period with lineCount > 0 and id === null', async () => {
      api.payroll.current.mockResolvedValue({ period: previewPeriod({ id: null, lineCount: 1 }) });

      renderPayrollPage();

      const processButton = await screen.findByRole('button', { name: /ประมวลผลเงินเดือน/i });
      await waitFor(() => expect(processButton.disabled).toBe(false));
    });

    it('shows an accessible block reason when disabled for an empty period, not the mobile-only message', async () => {
      api.payroll.current.mockResolvedValue({ period: previewPeriod({ lineCount: 0, lines: [] }) });

      renderPayrollPage();

      const reason = await screen.findByText(/ยังไม่มีพนักงานในรอบเงินเดือนนี้/);
      expect(reason.getAttribute('role')).toBe('note');
      const processButton = screen.getByRole('button', { name: /ประมวลผลเงินเดือน/i });
      expect(processButton.getAttribute('aria-describedby')).toBe(reason.id);
    });
  });
});
