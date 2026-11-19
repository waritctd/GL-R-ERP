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

// Payroll input draft: a NEW test file covering the actual bug the owner reported -- HR's in-progress,
// not-yet-processed payroll inputs had no persistent home, so a browser reload lost anything typed but not yet
// processed.

globalThis.React = React;

// hasPermission(user?.role, 'canManagePayroll') reads false for an absent user (issue #390 -- canManage is an
// allowlist off ROLE_PERMISSIONS), so every render below passes a real HR user explicitly rather than relying
// on an implicit default.
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

describe('PayrollPage input draft (survives a reload before processing)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.payroll.suggestedInputs.mockResolvedValue({ payrollMonth: '2026-07-01', suggestions: [] });
  });

  it('a typed value survives autosave followed by a real reload (full unmount/remount)', async () => {
    // Stateful mock: whatever saveInputDraft is called with becomes what getInputDraft returns next -- exactly how
    // a real backend round trip through hr.payroll_input_draft behaves, and the only way to prove "survives a
    // reload" rather than merely "the endpoint was called".
    let savedDrafts = [];
    api.payroll.current.mockResolvedValue({ period: periodWith(freshPayrollLine()) });
    api.payroll.getInputDraft.mockImplementation(async () => ({
      payrollMonth: '2026-07-01',
      drafts: savedDrafts,
    }));
    api.payroll.saveInputDraft.mockImplementation(async (payload) => {
      savedDrafts = payload.inputs;
      return { payrollMonth: payload.payrollMonth, drafts: savedDrafts };
    });

    const { unmount } = renderWithClient(<PayrollPage user={hrUser} showToast={vi.fn()} />);

    fireEvent.click(await screen.findByRole('button', { name: /ค่าอาหาร \/ เบี้ยเลี้ยง/ }));
    const mealAllowance = await screen.findByLabelText(/ค่าอาหาร/, { selector: 'input' });
    fireEvent.change(mealAllowance, { target: { value: '1234' } });
    expect(mealAllowance.value).toBe('1234');

    fireEvent.blur(mealAllowance);
    await waitFor(() => expect(api.payroll.saveInputDraft).toHaveBeenCalledTimes(1));
    const saved = api.payroll.saveInputDraft.mock.calls[0][0].inputs.find((item) => item.employeeId === 1);
    expect(saved.mealAllowance).toBe(1234);

    // Simulate an actual browser reload: unmount this instance entirely and mount a brand-new one (fresh React
    // state), exactly as a real page reload would produce.
    unmount();
    renderWithClient(<PayrollPage user={hrUser} showToast={vi.fn()} />);

    fireEvent.click(await screen.findByRole('button', { name: /ค่าอาหาร \/ เบี้ยเลี้ยง/ }));
    const reloadedMealAllowance = await screen.findByLabelText(/ค่าอาหาร/, { selector: 'input' });
    await waitFor(() => expect(reloadedMealAllowance.value).toBe('1234'));
  });

  it('a saved draft loses to a real value already on the line (draft never shadows a persisted/computed figure)', async () => {
    api.payroll.current.mockResolvedValue({
      period: periodWith(freshPayrollLine({ specialPays: [{ key: 'specialPay1', amount: 5000 }, ...zeroSpecialPays.slice(1)] })),
    });
    api.payroll.getInputDraft.mockResolvedValue({
      payrollMonth: '2026-07-01',
      drafts: [{ employeeId: 1, specialPay1: 999 }],
    });

    renderWithClient(<PayrollPage user={hrUser} showToast={vi.fn()} />);

    const costOfLiving = await screen.findByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/);
    await waitFor(() => expect(costOfLiving.value).toBe('5000'));
  });

  it('a saved draft beats a mere carry-forward suggestion (HR\'s own actual work outranks a guess)', async () => {
    api.payroll.current.mockResolvedValue({ period: periodWith(freshPayrollLine()) });
    api.payroll.suggestedInputs.mockResolvedValue({
      payrollMonth: '2026-07-01',
      suggestions: [{ employeeId: 1, specialPay1: 700 }],
    });
    api.payroll.getInputDraft.mockResolvedValue({
      payrollMonth: '2026-07-01',
      drafts: [{ employeeId: 1, specialPay1: 950 }],
    });

    renderWithClient(<PayrollPage user={hrUser} showToast={vi.fn()} />);

    const costOfLiving = await screen.findByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/);
    await waitFor(() => expect(costOfLiving.value).toBe('950'));
  });
});
