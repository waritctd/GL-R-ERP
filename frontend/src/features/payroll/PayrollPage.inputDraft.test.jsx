import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { PayrollPage } from './PayrollPage.jsx';
import { api } from '../../api/index.js';

// Payroll input draft (2026-07-30): a NEW test file (does not edit PayrollPage.test.jsx /
// PayrollPage.carryForward.test.jsx / PayrollPage.dailyRate.test.jsx) covering the actual bug the
// owner reported -- HR's in-progress, not-yet-processed payroll inputs had no persistent home, so
// a browser reload (Ctrl+R / a fresh login) lost anything typed but not yet processed. This file
// proves the fix end to end: a typed value survives autosave-on-blur + a real reload (a full
// unmount/remount of PayrollPage, driven by a stateful mock so the "saved" draft is actually read
// back), and that the precedence rule (real persisted line > draft > carry-forward suggestion >
// blank) does not disturb the two paths that already worked -- July's pre-loaded VOID period, and
// the in-app preview round trip, whose values the owner separately confirmed DO survive.

globalThis.React = React;

// hasPermission(user?.role, 'canManagePayroll') reads false for an absent user (issue #390 --
// canManage is an allowlist off ROLE_PERMISSIONS), so every render below passes a real HR user
// explicitly rather than relying on an implicit default.
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
    // Stateful mock: whatever saveInputDraft is called with becomes what getInputDraft returns
    // next -- exactly how a real backend round trip through hr.payroll_input_draft behaves, and
    // the only way to prove "survives a reload" rather than merely "the endpoint was called".
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

    const { unmount } = render(<PayrollPage user={hrUser} showToast={vi.fn()} />);

    fireEvent.click(await screen.findByRole('button', { name: /ค่าอาหาร \/ เบี้ยเลี้ยง/ }));
    const mealAllowance = await screen.findByLabelText(/ค่าอาหาร/, { selector: 'input' });
    fireEvent.change(mealAllowance, { target: { value: '1234' } });
    expect(mealAllowance.value).toBe('1234');

    fireEvent.blur(mealAllowance);
    await waitFor(() => expect(api.payroll.saveInputDraft).toHaveBeenCalledTimes(1));
    const saved = api.payroll.saveInputDraft.mock.calls[0][0].inputs.find((item) => item.employeeId === 1);
    expect(saved.mealAllowance).toBe(1234);

    // Simulate an actual browser reload: unmount this instance entirely and mount a brand-new one
    // (fresh React state), exactly as a real page reload would produce.
    unmount();
    render(<PayrollPage user={hrUser} showToast={vi.fn()} />);

    fireEvent.click(await screen.findByRole('button', { name: /ค่าอาหาร \/ เบี้ยเลี้ยง/ }));
    const reloadedMealAllowance = await screen.findByLabelText(/ค่าอาหาร/, { selector: 'input' });
    await waitFor(() => expect(reloadedMealAllowance.value).toBe('1234'));
  });

  /**
   * The exact defect an Opus review caught before merge: a carry-forward suggestion exists for a
   * field, HR deliberately clears it to blank/zero (e.g. a เบี้ยขยันประจำ that carried forward but
   * must not repeat this month), autosaves the draft, then reloads. The suggestion must NOT resurrect
   * over the saved zero -- draftFallback used to collapse a drafted 0 to null (indistinguishable
   * from "never drafted"), which fell through to the suggestion and silently un-did HR's correction.
   */
  it('a field HR clears to blank and saves stays blank after a reload, even though a carry-forward suggestion exists for it', async () => {
    let savedDrafts = [];
    api.payroll.current.mockResolvedValue({ period: periodWith(freshPayrollLine()) });
    api.payroll.suggestedInputs.mockResolvedValue({
      payrollMonth: '2026-07-01',
      suggestions: [{ employeeId: 1, specialPay1: 3000 }],
    });
    api.payroll.getInputDraft.mockImplementation(async () => ({
      payrollMonth: '2026-07-01',
      drafts: savedDrafts,
    }));
    api.payroll.saveInputDraft.mockImplementation(async (payload) => {
      savedDrafts = payload.inputs;
      return { payrollMonth: payload.payrollMonth, drafts: savedDrafts };
    });

    const { unmount } = render(<PayrollPage user={hrUser} showToast={vi.fn()} />);

    // Suggestion pre-fills the field to 3000 first.
    const costOfLiving = await screen.findByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/);
    await waitFor(() => expect(costOfLiving.value).toBe('3000'));

    // HR deliberately clears it -- this month it should be zero, not the carried figure.
    fireEvent.change(costOfLiving, { target: { value: '' } });
    expect(costOfLiving.value).toBe('');

    fireEvent.blur(costOfLiving);
    await waitFor(() => expect(api.payroll.saveInputDraft).toHaveBeenCalledTimes(1));
    const saved = api.payroll.saveInputDraft.mock.calls[0][0].inputs.find((item) => item.employeeId === 1);
    expect(saved.specialPay1).toBe(0);

    // A real reload: fresh component instance, suggestion still resolves to 3000 (nothing about
    // the carry-forward config changed) -- only the saved draft should determine the outcome now.
    unmount();
    render(<PayrollPage user={hrUser} showToast={vi.fn()} />);

    const reloadedCostOfLiving = await screen.findByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/);
    await waitFor(() => expect(reloadedCostOfLiving.value).toBe(''));
  });

  it('shows an autosave status indicator after typing, which clears once save succeeds', async () => {
    api.payroll.current.mockResolvedValue({ period: periodWith(freshPayrollLine()) });
    api.payroll.getInputDraft.mockResolvedValue({ payrollMonth: '2026-07-01', drafts: [] });
    api.payroll.saveInputDraft.mockResolvedValue({ payrollMonth: '2026-07-01', drafts: [] });

    render(<PayrollPage user={hrUser} showToast={vi.fn()} />);

    const costOfLiving = await screen.findByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/);
    expect(screen.getByRole('status').textContent).toContain('บันทึกแล้ว');
    expect(screen.queryByRole('button', { name: /บันทึกร่าง/ })).toBeNull();

    fireEvent.change(costOfLiving, { target: { value: '321' } });
    expect(screen.getByRole('status').textContent).toContain('รอบันทึกอัตโนมัติ');

    fireEvent.blur(costOfLiving);
    await waitFor(() => expect(screen.getByRole('status').textContent).toContain('บันทึกแล้ว'));
  });

  it('queues a second autosave when HR edits again while the first autosave is still running', async () => {
    api.payroll.current.mockResolvedValue({ period: periodWith(freshPayrollLine()) });
    api.payroll.getInputDraft.mockResolvedValue({ payrollMonth: '2026-07-01', drafts: [] });
    const pendingSaves = [];
    const savedPayloads = [];
    api.payroll.saveInputDraft.mockImplementation((payload) => {
      savedPayloads.push(payload);
      return new Promise((resolve) => pendingSaves.push(resolve));
    });

    render(<PayrollPage user={hrUser} showToast={vi.fn()} />);

    const costOfLiving = await screen.findByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/);
    fireEvent.change(costOfLiving, { target: { value: '111' } });
    fireEvent.blur(costOfLiving);

    await waitFor(() => expect(api.payroll.saveInputDraft).toHaveBeenCalledTimes(1));
    expect(savedPayloads[0].inputs.find((item) => item.employeeId === 1).specialPay1).toBe(111);

    // HR tabs on and changes the same draft before the first network round trip returns. The
    // explicit Save button is gone, so the blur autosave itself must queue the follow-up write.
    fireEvent.change(costOfLiving, { target: { value: '222' } });
    fireEvent.blur(costOfLiving);
    expect(screen.getByRole('status').textContent).toMatch(/กำลังบันทึก|รอบันทึกอัตโนมัติ/);

    pendingSaves.shift()({ payrollMonth: '2026-07-01', drafts: savedPayloads[0].inputs });
    await waitFor(() => expect(api.payroll.saveInputDraft).toHaveBeenCalledTimes(2));
    expect(savedPayloads[1].inputs.find((item) => item.employeeId === 1).specialPay1).toBe(222);

    pendingSaves.shift()({ payrollMonth: '2026-07-01', drafts: savedPayloads[1].inputs });
    await waitFor(() => expect(screen.getByRole('status').textContent).toContain('บันทึกแล้ว'));
  });

  it('a saved draft loses to a real value already on the line (draft never shadows a persisted/computed figure)', async () => {
    api.payroll.current.mockResolvedValue({
      period: periodWith(freshPayrollLine({ specialPays: [{ key: 'specialPay1', amount: 5000 }, ...zeroSpecialPays.slice(1)] })),
    });
    api.payroll.getInputDraft.mockResolvedValue({
      payrollMonth: '2026-07-01',
      drafts: [{ employeeId: 1, specialPay1: 999 }],
    });

    render(<PayrollPage user={hrUser} showToast={vi.fn()} />);

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

    render(<PayrollPage user={hrUser} showToast={vi.fn()} />);

    const costOfLiving = await screen.findByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/);
    await waitFor(() => expect(costOfLiving.value).toBe('950'));
  });

  /**
   * Pins the hydration path the owner flagged as currently-live and must not regress: July's
   * period_id=1 is VOID, not PREVIEW, and already has real hr.payroll_line rows (17 employees'
   * allowances, mealAllowance on two of them, employee 203's pay_type='D'/daysWorked=25). This
   * must hydrate from the line exactly as before, with no draft fetch at all -- a VOID period
   * already reflects real submitted values, and a stale draft must never be consulted for it.
   */
  it('a VOID period with real pre-loaded lines hydrates the form when no draft exists, and never fetches a draft', async () => {
    api.payroll.current.mockResolvedValue({
      period: periodWith(
        freshPayrollLine({
          id: 900,
          employeeId: 203,
          employeeCode: '10060',
          employeeName: 'พัฒวุฒิ สังฆวาส',
          payType: 'D',
          daysWorked: 25,
          mealAllowance: 3360,
          specialPays: [{ key: 'specialPay1', amount: 4500 }, ...zeroSpecialPays.slice(1)],
        }),
        { id: 1, status: 'VOID' },
      ),
    });

    render(<PayrollPage user={hrUser} showToast={vi.fn()} />);

    const daysWorked = await screen.findByLabelText(/จำนวนวันทำงานในงวดนี้/, { selector: 'input' });
    await waitFor(() => expect(daysWorked.value).toBe('25'));
    fireEvent.click(screen.getByRole('button', { name: /ค่าอาหาร \/ เบี้ยเลี้ยง/ }));
    expect(screen.getByLabelText(/ค่าอาหาร/, { selector: 'input' }).value).toBe('3360');
    const costOfLiving = screen.getByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/);
    expect(costOfLiving.value).toBe('4500');

    expect(api.payroll.getInputDraft).not.toHaveBeenCalled();
    expect(api.payroll.suggestedInputs).not.toHaveBeenCalled();
    // A VOID/already-touched period offers no autosave draft status either -- there is nothing useful a
    // draft save would do for it (see canSaveDraft's own comment in PayrollPage.jsx).
    expect(screen.queryByRole('button', { name: /บันทึกร่าง/ })).toBeNull();
  });
});
