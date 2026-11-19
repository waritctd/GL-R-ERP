import React from 'react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { PayrollPage } from './PayrollPage.jsx';
import { api } from '../../api/index.js';

// Issue #422 B1: PayrollPage now reads through react-query, so every render below needs a real QueryClient in
// context.
function renderWithClient(ui) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(<QueryClientProvider client={queryClient}><MemoryRouter>{ui}</MemoryRouter></QueryClientProvider>);
}

// Payroll draft optimistic concurrency (issue #422 follow-up, Opus review finding F3): a NEW test file (does
// not edit PayrollPage.test.jsx / PayrollPage.inputDraft.test.jsx / others), covering the three things the
// plan required and that had ZERO coverage before this file existed: 1.

globalThis.React = React;

const hrUser = { role: 'hr', employeeId: 10 };

vi.mock('../../api/index.js', () => ({
  api: {
    payroll: {
      current: vi.fn(),
      suggestedInputs: vi.fn(),
      getInputDraft: vi.fn(),
      saveInputDraft: vi.fn(),
      preview: vi.fn(),
      process: vi.fn(),
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

function freshPayrollLine(overrides = {}) {
  return {
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
    mealAllowance: 0,
    perDiemExempt: 0,
    perDiemTaxable: 0,
    perDiemBasis: null,
    bonusPay: 0,
    otherOneOffPay: 0,
    customerReturnDeduction: 0,
    garnishmentType: null,
    customerReturnAlreadyEarned: false,
    daysWorked: null,
    payType: null,
    ...overrides,
  };
}

function periodWith(line, overrides = {}) {
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
    lines: [line],
    ...overrides,
  };
}

function conflictError(status, message) {
  const error = new Error(message);
  error.status = status;
  return error;
}

describe('PayrollPage optimistic concurrency (issue #422 follow-up)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.payroll.current.mockResolvedValue({ period: periodWith(freshPayrollLine()) });
    api.payroll.suggestedInputs.mockResolvedValue({ payrollMonth: '2026-07-01', suggestions: [] });
  });

  it('threads the ETag from the GET into the next saveInputDraft as If-Match', async () => {
    api.payroll.getInputDraft.mockResolvedValue({ payrollMonth: '2026-07-01', drafts: [], etag: 'ETAG-LOAD-1' });
    api.payroll.saveInputDraft.mockResolvedValue({ payrollMonth: '2026-07-01', drafts: [], etag: 'ETAG-SAVE-1' });

    renderWithClient(<PayrollPage user={hrUser} showToast={vi.fn()} />);

    const costOfLiving = await screen.findByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/);
    fireEvent.change(costOfLiving, { target: { value: '1234' } });
    fireEvent.blur(costOfLiving);

    await waitFor(() => expect(api.payroll.saveInputDraft).toHaveBeenCalledTimes(1));
    const [, options] = api.payroll.saveInputDraft.mock.calls[0];
    expect(options).toEqual({ ifMatch: 'ETAG-LOAD-1' });
  });

  it('a 409 on บันทึกร่าง shows a persistent conflict message, keeps HR\'s typed value in the field, and does not run preview()', async () => {
    api.payroll.getInputDraft.mockResolvedValue({ payrollMonth: '2026-07-01', drafts: [], etag: 'ETAG-LOAD-1' });
    api.payroll.saveInputDraft.mockRejectedValue(
      conflictError(409, 'มีผู้ใช้อื่นบันทึกร่างเงินเดือนของงวดนี้ไปแล้ว กรุณาโหลดข้อมูลใหม่ก่อนบันทึกอีกครั้ง'));

    renderWithClient(<PayrollPage user={hrUser} showToast={vi.fn()} />);

    const costOfLiving = await screen.findByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/);
    fireEvent.change(costOfLiving, { target: { value: '777' } });

    fireEvent.click(screen.getByRole('button', { name: /บันทึกร่าง/i }));

    // Persistent conflict banner (role="alert", stays on screen -- unlike the toast, which auto-dismisses after
    // 3.2s per useToast.js) with the server's own Thai message.
    const banner = await screen.findByRole('alert');
    expect(banner.textContent).toContain('มีผู้ใช้อื่นบันทึกร่างเงินเดือนของงวดนี้ไปแล้ว');

    // HR's typed value must still be sitting in the field -- not reverted, not blanked.
    expect(screen.getByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/).value).toBe('777');

    // Opus review finding F4: preview() must NOT run after a 409 -- it calls applyPeriod directly, bypassing the
    // dirty guard, and would repaint the form from a payload that (unlike draftPayload()) filters out anything
    // that doesn't already qualify as a submittable input.
    expect(api.payroll.preview).not.toHaveBeenCalled();
  });

  it('a 428 (missing If-Match) is treated the same as a 409: persistent message shown, and further autosaves stop firing', async () => {
    api.payroll.getInputDraft.mockResolvedValue({ payrollMonth: '2026-07-01', drafts: [], etag: 'ETAG-LOAD-1' });
    api.payroll.saveInputDraft.mockRejectedValue(
      conflictError(428, 'ไม่พบข้อมูลอ้างอิงสำหรับบันทึกร่างเงินเดือน กรุณาโหลดหน้าใหม่แล้วลองอีกครั้ง'));

    renderWithClient(<PayrollPage user={hrUser} showToast={vi.fn()} />);

    const costOfLiving = await screen.findByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/);
    fireEvent.change(costOfLiving, { target: { value: '111' } });
    fireEvent.blur(costOfLiving);

    await waitFor(() => expect(api.payroll.saveInputDraft).toHaveBeenCalledTimes(1));
    await screen.findByRole('alert');

    // Opus review finding F8: without treating 428 like 409, autosaveDraft's guard never trips, so every further
    // blur re-fires the identical doomed headerless PUT. Type into ANOTHER field and blur it -- if the guard is
    // working, no second call happens.
    const gprs = screen.getByLabelText(/พิเศษ 6 \(ค่า GPRS\)/);
    fireEvent.change(gprs, { target: { value: '222' } });
    fireEvent.blur(gprs);

    // Give any (incorrect) second call a chance to fire before asserting it didn't.
    await new Promise((resolve) => { setTimeout(resolve, 50); });
    expect(api.payroll.saveInputDraft).toHaveBeenCalledTimes(1);

    // Both typed values must still be present -- the second field's edit was never discarded either.
    expect(screen.getByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/).value).toBe('111');
    expect(gprs.value).toBe('222');
  });
});
