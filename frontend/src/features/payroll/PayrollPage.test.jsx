import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { PayrollPage } from './PayrollPage.jsx';
import { api } from '../../api/index.js';
import { bangkokMonthStartIso } from '../../utils/format.js';

// PayrollPage reads/writes through react-query, so every render needs a real QueryClient;
// retry: false so a mocked rejection surfaces on the first attempt.
function renderWithClient(ui) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(<QueryClientProvider client={queryClient}><MemoryRouter>{ui}</MemoryRouter></QueryClientProvider>);
}

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    payroll: {
      current: vi.fn(),
      preview: vi.fn(),
      process: vi.fn(),
      exportFile: vi.fn(),
      exportPreviewFile: vi.fn(),
      downloadPayslip: vi.fn(),
      downloadPayslipsZip: vi.fn(),
      distributePayslips: vi.fn(),
      suggestedInputs: vi.fn(),
      getInputDraft: vi.fn(),
      saveInputDraft: vi.fn(),
      // The tax-treatment matrix section calls this on mount.
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
  // bonusPay/otherOneOffPay/garnishmentType/customerReturnAlreadyEarned inputs.
  bonusPay: 0,
  otherOneOffPay: 0,
  customerReturnDeduction: 0,
  garnishmentType: null,
  customerReturnAlreadyEarned: false,
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

// hasPermission(user?.role, 'canManagePayroll') is false for an absent user (canManage is an
// allowlist off ROLE_PERMISSIONS), so every render must pass a real user or these tests would
// exercise the read-only (CEO-shaped) page.
const hrUser = { role: 'hr', employeeId: 10 };

function renderPayrollPage() {
  return renderWithClient(<PayrollPage user={hrUser} showToast={vi.fn()} />);
}

// Computed the same way PayrollPage.jsx defaults its `month` state (the Bangkok month, via
// bangkokMonthStartIso -- a UTC month is one behind for the first 7 hours of each month), and
// `payload()` submits `` `${month}-01` ``. Used below to pin down that exact "-01" suffix (a
// mutation-testing survivor: changing it to "-02" left the suite green).
const thisMonth = bangkokMonthStartIso().slice(0, 7);
const originalMatchMedia = window.matchMedia;

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
    api.payroll.getInputDraft.mockResolvedValue({ payrollMonth: '2026-07-01', drafts: [] });
    api.payroll.saveInputDraft.mockResolvedValue({ payrollMonth: '2026-07-01', drafts: [] });
    api.payroll.getComponentTaxTreatments.mockResolvedValue({ taxYear: 2026, items: [] });
    api.payroll.saveComponentTaxTreatments.mockResolvedValue({ taxYear: 2026, items: [] });
  });

  afterEach(() => {
    if (originalMatchMedia) {
      window.matchMedia = originalMatchMedia;
    } else {
      delete window.matchMedia;
    }
  });

  // The two deduction pages left the main nav (AppShell launcher); their routes and guards are unchanged, so this
  // page is how they stay reachable. Same audience as the page itself (canViewPayroll: hr + ceo).
  it.each([
    ['hr', { role: 'hr', employeeId: 10 }],
    ['ceo (read-only view)', { role: 'ceo', employeeId: 1 }],
  ])('links to the deduction shortfalls and consents pages for %s', async (_label, user) => {
    renderWithClient(<PayrollPage user={user} showToast={vi.fn()} />);
    await screen.findByRole('button', { name: 'เอกสาร' });

    const hrefs = screen.getAllByRole('link').map((link) => link.getAttribute('href'));
    expect(hrefs).toContain('/payroll/deduction-shortfalls');
    expect(hrefs).toContain('/payroll/deduction-consents');
  });

  it('allows clearing zero/default amounts and sends them as zeroes', async () => {
    renderWithClient(<PayrollPage user={hrUser} showToast={vi.fn()} />);

    const costOfLiving = await screen.findByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/);
    const gprs = screen.getByLabelText(/พิเศษ 6 \(ค่า GPRS\)/);

    fireEvent.change(costOfLiving, { target: { value: '' } });
    fireEvent.change(gprs, { target: { value: '' } });

    expect(costOfLiving.value).toBe('');
    expect(gprs.value).toBe('');

    fireEvent.click(screen.getByRole('button', { name: /บันทึกร่าง/i }));

    await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
    expect(api.payroll.preview.mock.calls[0][0].inputs).toEqual([]);
    // Mutation-testing survivor: `payload().payrollMonth: \`${month}-01\`` had no assertion pinning down the
    // literal "-01" -- changing it to "-02" left the whole suite green. Assert the exact value, not just a shape
    // match.
    expect(api.payroll.preview.mock.calls[0][0].payrollMonth).toBe(`${thisMonth}-01`);
    expect(screen.getByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/).value).toBe('');
  });

  it('includes an input value of exactly 1 -- the boundary of hasPayrollInput\'s `> 0` check', async () => {
    // Mutation-testing survivor: `parsePayrollNumber(input[key]) > 0` could be weakened to `> 1` without failing
    // anything, because no existing test isolated a value of exactly 1 -- it would silently drop a real HR-entered
    // "1" (e.g. 1 special-pay baht, 1 unpaid-leave day) from the submitted payload.
    renderPayrollPage();

    const costOfLiving = await screen.findByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/);
    const gprs = screen.getByLabelText(/พิเศษ 6 \(ค่า GPRS\)/);
    const allowance = screen.getByLabelText(/พิเศษ 3 \(เบี้ยเลี้ยงประจำ\)/);
    fireEvent.change(costOfLiving, { target: { value: '' } });
    fireEvent.change(gprs, { target: { value: '' } });
    fireEvent.change(allowance, { target: { value: '1' } });
    fireEvent.click(screen.getByRole('button', { name: /บันทึกร่าง/i }));

    await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
    const submitted = api.payroll.preview.mock.calls[0][0].inputs.find((input) => input.employeeId === 1);
    expect(submitted).toBeDefined();
    // เบี้ยเลี้ยงประจำ is slot 3 after the realignment to the accountant's numbering.
    expect(submitted.specialPay3).toBe(1);
  });

  it('ties footer sums to the hero totals exactly, including satang', async () => {
    const lines = [
      {
        ...payrollLine,
        id: 101,
        employeeId: 1,
        employeeName: 'พนักงาน ก',
        grossEarnings: 100.10,
        totalDeductions: 0.05,
        netPay: 100.05,
      },
      {
        ...payrollLine,
        id: 102,
        employeeId: 2,
        employeeCode: 'GLR-002',
        employeeName: 'พนักงาน ข',
        grossEarnings: 200.20,
        totalDeductions: 0.10,
        netPay: 200.10,
      },
    ];
    api.payroll.current.mockResolvedValue({
      period: previewPeriod({
        lineCount: 2,
        totalGross: 300.30,
        totalDeductions: 0.15,
        totalNet: 300.15,
        totalSocialSecurity: 0.15,
        lines,
      }),
    });

    const { container } = renderPayrollPage();

    expect((await screen.findAllByText('พนักงาน ข')).length).toBeGreaterThan(0);
    expect(screen.queryByRole('alert')).toBeNull();

    const totalRow = container.querySelector('tfoot .payroll-total-row');
    expect(totalRow.textContent).toContain('฿300.30');
    expect(totalRow.textContent).toContain('฿0.15');
    expect(totalRow.textContent).toContain('฿300.15');
    expect(totalRow.textContent).toContain('ตรงกับรายได้รวม');
    expect(totalRow.textContent).toContain('ตรงกับเงินหักรวม');
    expect(totalRow.textContent).toContain('ตรงกับยอดโอนสุทธิ');

    const mobileSummary = container.querySelector('.payroll-mobile-summary-row');
    expect(mobileSummary.textContent).toContain('รายได้');
    expect(mobileSummary.textContent).toContain('หัก');
    expect(mobileSummary.textContent).toContain('สุทธิ');
    expect(mobileSummary.textContent).toContain('฿300.30');
  });

  it('raises a visible reconciliation alert when a line sum does not match the hero total', async () => {
    const lines = [
      {
        ...payrollLine,
        grossEarnings: 300.30,
        totalDeductions: 0.15,
        netPay: 300.15,
      },
    ];
    api.payroll.current.mockResolvedValue({
      period: previewPeriod({
        totalGross: 300.31,
        totalDeductions: 0.15,
        totalNet: 300.15,
        totalSocialSecurity: 0.15,
        lines,
      }),
    });

    const { container } = renderPayrollPage();

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('ยอดรวมไม่ตรงกับสรุปด้านบน');
    expect(alert.textContent).toContain('รายได้');
    expect(alert.textContent).toContain('฿300.30');
    expect(alert.textContent).toContain('฿300.31');

    const totalRow = container.querySelector('tfoot .payroll-total-row');
    expect(totalRow.textContent).toContain('ไม่ตรงกับรายได้รวม: ฿300.31');
  });

  it('Preview recomputes a processed month live, never committing', async () => {
    // A month already Processed loads from its saved snapshot (api.payroll.current), which freezes
    // commission/OT/etc. Preview must pull the latest via a live recompute (api.payroll.preview) and
    // must NOT process/commit the month.
    api.payroll.current.mockResolvedValue({ period: previewPeriod({ id: 7, status: 'PROCESSED' }) });

    renderPayrollPage();

    // Wait for real data: the บันทึกร่าง button is `disabled` while loading.
    await screen.findByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/);
    const previewButton = screen.getByRole('button', { name: /บันทึกร่าง/ });
    expect(api.payroll.preview).not.toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: /รีเฟรช/ })).toBeNull();

    fireEvent.click(previewButton);

    await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
    expect(api.payroll.process).not.toHaveBeenCalled();
  });

  // Leave -> payroll unpaid-day deduction. The unpaidLeaveDays field is inside the
  // "รายการหักรายบุคคล" CollapsibleSection, which unmounts its body while collapsed, so tests must
  // expand it first.
  async function expandUnpaidLeaveSection() {
    fireEvent.click(await screen.findByRole('button', { name: /รายการหักรายบุคคล/ }));
    // The `selector: 'input'` filter is needed because the field's InfoTip button carries the same accessible name
    // ("วันลาไม่รับค่าจ้าง") as an aria-label -- without it, getByLabelText matches both the input and the InfoTip
    // trigger and throws "Found multiple elements".
    return screen.findByLabelText(/วันลาไม่รับค่าจ้าง/, { selector: 'input' });
  }

  describe('leave-derived unpaidLeaveDays suggestion', () => {
    it('lets HR override the pre-filled unpaidLeaveDays suggestion before submitting', async () => {
      api.payroll.suggestedInputs.mockResolvedValue({
        payrollMonth: '2026-07-01',
        suggestions: [{ employeeId: 1, unpaidLeaveDays: 1.5, pendingUnpaidLeaveCorrectionDays: 0 }],
      });

      renderPayrollPage();

      const unpaidLeaveDays = await expandUnpaidLeaveSection();
      expect(unpaidLeaveDays.value).toBe('1.5');

      fireEvent.change(unpaidLeaveDays, { target: { value: '2' } });
      fireEvent.click(screen.getByRole('button', { name: /บันทึกร่าง/i }));

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
  });

  describe('per-run withholding-tax override (V88)', () => {
    async function openOverrideInput() {
      fireEvent.click(await screen.findByRole('button', { name: /รายการหักรายบุคคล/ }));
      // The field's InfoTip trigger shares the label's accessible name, so scope to the input.
      return screen.findByLabelText(/ภาษีหัก ณ ที่จ่าย \(กำหนดเอง\)/, { selector: 'input' });
    }

    it('submits an override of 0 (withhold nothing) rather than dropping the input', async () => {
      renderPayrollPage();
      const override = await openOverrideInput();

      fireEvent.change(override, { target: { value: '0' } });
      fireEvent.click(screen.getByRole('button', { name: /บันทึกร่าง/i }));

      await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
      const submitted = api.payroll.preview.mock.calls[0][0].inputs.find((input) => input.employeeId === 1);
      expect(submitted).toBeDefined();
      expect(submitted.withholdingTaxOverride).toBe(0);
    });
  });

  // Guards against HR typing a per-diem amount with no basis: Preview succeeds (never writes), then
  // Process would 500 on chk_payroll_line_per_diem_basis_present. The amount inputs plus a basis
  // selector (shown once an amount is entered) prevent it; the server also 400s
  // (PayrollService#calculateLine).
  describe('per-diem basis selector (V97 / F2)', () => {
    async function openPerDiemSection() {
      fireEvent.click(await screen.findByRole('button', { name: /ค่าอาหาร \/ เบี้ยเลี้ยง/ }));
      return screen.findByLabelText(/เบี้ยเลี้ยง — ส่วนเกิน \(เสียภาษี\)/, { selector: 'input' });
    }

    it('submits the chosen per-diem basis verbatim, matching the backend PerDiemBasis enum', async () => {
      renderPayrollPage();
      const taxable = await openPerDiemSection();
      fireEvent.change(taxable, { target: { value: '300' } });
      const basis = await screen.findByLabelText(/ฐานเบี้ยเลี้ยง \(มาตรา 42\)/, { selector: 'select' });
      fireEvent.change(basis, { target: { value: 'REIMBURSED_S42_1' } });

      fireEvent.click(screen.getByRole('button', { name: /บันทึกร่าง/i }));

      await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
      const submitted = api.payroll.preview.mock.calls[0][0].inputs.find((input) => input.employeeId === 1);
      expect(submitted.perDiemTaxable).toBe(300);
      expect(submitted.perDiemBasis).toBe('REIMBURSED_S42_1');
    });
  });

  // PayrollService#process (backend) doesn't need a period id -- it recomputes from findActiveEmployees() and
  // commits regardless. Opening the confirm dialog and processing while the header reads "พนักงาน 0 คน" would
  // silently zero out every HR-entered value and commit an irreversible PROCESSED status.
  describe('Process Payroll guard against an empty period (F1)', () => {
    it('disables Process Payroll when the loaded period has zero lines (lineCount: 0)', async () => {
      api.payroll.current.mockResolvedValue({ period: previewPeriod({ lineCount: 0, lines: [] }) });

      renderPayrollPage();

      const processButton = await screen.findByRole('button', { name: /ประมวลผลเงินเดือน/i });
      await waitFor(() => expect(processButton.disabled).toBe(true));
    });

    // Regression guard against "simplifying" the check to `!period?.id`: PayrollService#preview
    // returns id=null for every never-processed month, so id===null does not imply an empty period.
    // Guarding on id would make first-time processing impossible.
    it('enables Process Payroll for a fresh (never-processed) period with lineCount > 0 and id === null', async () => {
      api.payroll.current.mockResolvedValue({ period: previewPeriod({ id: null, lineCount: 1 }) });

      renderPayrollPage();

      const processButton = await screen.findByRole('button', { name: /ประมวลผลเงินเดือน/i });
      await waitFor(() => expect(processButton.disabled).toBe(false));
    });
  });

  // bonusPay/otherOneOffPay/garnishmentType/customerReturnAlreadyEarned inputs.
  describe('one-off pay, garnishment type, and customer-return-earned flag (P1)', () => {
    it('submits bonusPay and otherOneOffPay from the "เงินก้อนพิเศษ" section', async () => {
      renderPayrollPage();
      fireEvent.click(await screen.findByRole('button', { name: /เงินก้อนพิเศษ \(จ่ายครั้งเดียว\)/ }));
      const bonus = await screen.findByLabelText(/เงินโบนัส/, { selector: 'input' });
      const oneOff = await screen.findByLabelText(/เงินก้อนอื่นๆ \(ครั้งเดียว\)/, { selector: 'input' });

      fireEvent.change(bonus, { target: { value: '20000' } });
      fireEvent.change(oneOff, { target: { value: '1500' } });
      fireEvent.click(screen.getByRole('button', { name: /บันทึกร่าง/i }));

      await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
      const submitted = api.payroll.preview.mock.calls[0][0].inputs.find((input) => input.employeeId === 1);
      expect(submitted.bonusPay).toBe(20000);
      expect(submitted.otherOneOffPay).toBe(1500);
    });

    async function openIndividualDeductionsSection() {
      fireEvent.click(await screen.findByRole('button', { name: /รายการหักรายบุคคล/ }));
      return screen.findByLabelText(/หักอายัดกรมบังคับคดี/, { selector: 'input' });
    }

    it('shows the garnishment-type selector once an amount is entered, and submits it verbatim', async () => {
      renderPayrollPage();
      const legalExecution = await openIndividualDeductionsSection();

      fireEvent.change(legalExecution, { target: { value: '5000' } });
      const garnishmentType = await screen.findByLabelText(/ประเภทเงินที่ถูกอายัด/, { selector: 'select' });
      fireEvent.change(garnishmentType, { target: { value: 'OVERTIME_OR_DILIGENCE' } });

      fireEvent.click(screen.getByRole('button', { name: /บันทึกร่าง/i }));

      await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
      const submitted = api.payroll.preview.mock.calls[0][0].inputs.find((input) => input.employeeId === 1);
      expect(submitted.legalExecutionDeduction).toBe(5000);
      expect(submitted.garnishmentType).toBe('OVERTIME_OR_DILIGENCE');
    });

    it('submits customerReturnAlreadyEarned = true once HR ticks the box', async () => {
      renderPayrollPage();
      fireEvent.click(await screen.findByRole('button', { name: /รายการหักก่อนภาษี/, expanded: false }));
      const customerReturn = await screen.findByLabelText(/หักลูกค้าคืนสินค้า/, { selector: 'input' });
      fireEvent.change(customerReturn, { target: { value: '3000' } });
      const alreadyEarned = await screen.findByLabelText(/คอมมิชชันนี้รับไปแล้ว/, { selector: 'input' });

      fireEvent.click(alreadyEarned);
      fireEvent.click(screen.getByRole('button', { name: /บันทึกร่าง/i }));

      await waitFor(() => expect(api.payroll.preview).toHaveBeenCalledTimes(1));
      const submitted = api.payroll.preview.mock.calls[0][0].inputs.find((input) => input.employeeId === 1);
      expect(submitted.customerReturnAlreadyEarned).toBe(true);
    });
  });
});

// CEO has the same read access as HR (every GET, plus the non-persisting POST /preview and
// /preview/export/{kind}: hasAnyRole('HR','CEO')) but every write is hasRole('HR') only (process,
// PUT input-draft, PUT component-tax-treatments, POST distribute). `PayrollPage` derives
// `canManage = hasPermission(user?.role, 'canManagePayroll')`, an ALLOWLIST off ROLE_PERMISSIONS, so
// an absent/unknown user has NO management access. These tests pin the negative case (CEO cannot
// reach the mutating controls); the positive HR case is the suite above.
describe('PayrollPage read-only CEO view (issue #390)', () => {
  const ceoUser = { role: 'ceo', employeeId: 99 };

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
    api.payroll.getInputDraft.mockResolvedValue({ payrollMonth: '2026-07-01', drafts: [] });
    api.payroll.saveInputDraft.mockResolvedValue({ payrollMonth: '2026-07-01', drafts: [] });
    api.payroll.getComponentTaxTreatments.mockResolvedValue({ taxYear: 2026, items: [] });
    api.payroll.saveComponentTaxTreatments.mockResolvedValue({ taxYear: 2026, items: [] });
  });

  afterEach(() => {
    if (originalMatchMedia) {
      window.matchMedia = originalMatchMedia;
    } else {
      delete window.matchMedia;
    }
  });

  // The test that matters: CEO cannot reach ประมวลผลเงินเดือน (POST /process, hasRole('HR') only) at all -- not
  // present in the DOM, not merely disabled. HR keeps it, same as every pre-existing test in this file that
  // never passes a `user` prop.
  it('never renders the ประมวลผลเงินเดือน (process) button for CEO, while HR still has it', async () => {
    const { unmount } = renderWithClient(<PayrollPage user={ceoUser} showToast={vi.fn()} />);
    await screen.findByRole('button', { name: /คำนวณตัวอย่าง/i });
    expect(screen.queryByRole('button', { name: /ประมวลผลเงินเดือน/i })).toBeNull();
    // The read-only replacement still shows period status/headcount as plain text, scoped to its own
    // labelled region (the table's footer row also renders "N คน"). The period arrives via
    // react-query, so use `findByText` (not `getByText`) to wait for it.
    const statusRegion = screen.getByRole('region', { name: 'สถานะรอบเงินเดือน' });
    expect(await within(statusRegion).findByText(`${previewPeriod().lineCount} คน`)).toBeTruthy();
    unmount();

    renderWithClient(<PayrollPage user={hrUser} showToast={vi.fn()} />);
    expect(await screen.findByRole('button', { name: /ประมวลผลเงินเดือน/i })).toBeTruthy();
  });

  it('lets CEO still download a saved payslip -- exports and payslip reads stay hasAnyRole(HR,CEO)', async () => {
    api.payroll.current.mockResolvedValue({ period: previewPeriod({ id: 7, status: 'PROCESSED' }) });
    renderWithClient(<PayrollPage user={ceoUser} showToast={vi.fn()} />);

    fireEvent.click(await screen.findByRole('button', { name: /^ดาวน์โหลดสลิป$/i }));
    await waitFor(() => expect(api.payroll.downloadPayslip).toHaveBeenCalledWith(7, 55));
  });
});
