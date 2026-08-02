import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { PayrollPage } from './PayrollPage.jsx';
import { api } from '../../api/index.js';

// Issue #422 B1: PayrollPage now reads through react-query, so every render below needs a real
// QueryClient in context.
function renderWithClient(ui) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>);
}

// Payroll draft optimistic concurrency (issue #422 follow-up, Opus review finding F3): a NEW test
// file (does not edit PayrollPage.test.jsx / PayrollPage.inputDraft.test.jsx / others), covering
// the three things the plan required and that had ZERO coverage before this file existed:
//   1. the ETag is threaded from load (GET) into the next save (PUT's If-Match)
//   2. a 409 surfaces a persistent, visible conflict message (not just a toast that auto-dismisses)
//   3. HR's typed values are NOT discarded on a 409 -- neither by saveDraft() itself, nor by the
//      บันทึกร่าง button's composed saveDraft-then-preview flow (Opus review finding F4)
// Also covers finding F8 (428 treated the same as 409, so the retry machinery stops hammering a
// doomed headerless request) and the explicit "โหลดข้อมูลใหม่" conflict-resolution action.
//
// Every OTHER existing PayrollPage test file mocks getInputDraft/saveInputDraft with NO `etag`
// field at all, so draftEtagRef.current stays undefined/null and hrApi's `headers: ifMatch ? ... :
// {}` sends no If-Match whatsoever -- the entire rest of the suite exercises the headerless path,
// not the threading. This file is the one place that actually sets an `etag` on every mock
// response, so it is the only coverage of the real, header-bearing round trip.

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

  it('a second save after the first succeeds threads the NEW ETag the server returned, not the original one', async () => {
    api.payroll.getInputDraft.mockResolvedValue({ payrollMonth: '2026-07-01', drafts: [], etag: 'ETAG-LOAD-1' });
    api.payroll.saveInputDraft.mockResolvedValue({ payrollMonth: '2026-07-01', drafts: [], etag: 'ETAG-SAVE-2' });

    renderWithClient(<PayrollPage user={hrUser} showToast={vi.fn()} />);

    const costOfLiving = await screen.findByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/);
    fireEvent.change(costOfLiving, { target: { value: '1234' } });
    fireEvent.blur(costOfLiving);
    await waitFor(() => expect(api.payroll.saveInputDraft).toHaveBeenCalledTimes(1));
    // Let the first save's resolution fully settle (draftDirtyRef/draftSaving reset) before
    // triggering a second one -- otherwise the second blur can land while the first save is still
    // between "mock resolved" and "component state updated," which autosaveDraft's own guards
    // correctly treat as still-saving and skip.
    await waitFor(() => expect(screen.getByRole('status').textContent).toContain('บันทึกแล้ว'));

    // NOTE: GPRS (specialPay6) carries a ฿500 UAT demo default (see "uses Excel-based UAT
    // defaults..." in PayrollPage.test.jsx), which is ALREADY the field's value on a fresh
    // PREVIEW period -- setting it to '500' again would be a no-op from React's controlled-input
    // value tracker's perspective (old === new), so no synthetic onChange would fire. Use a value
    // that is genuinely different from the current one.
    const gprs = screen.getByLabelText(/พิเศษ 6 \(ค่า GPRS\)/);
    fireEvent.change(gprs, { target: { value: '999' } });
    fireEvent.blur(gprs);
    await waitFor(() => expect(api.payroll.saveInputDraft).toHaveBeenCalledTimes(2));

    const [, secondOptions] = api.payroll.saveInputDraft.mock.calls[1];
    expect(secondOptions).toEqual({ ifMatch: 'ETAG-SAVE-2' });
  });

  it('a 409 on บันทึกร่าง shows a persistent conflict message, keeps HR\'s typed value in the field, and does not run preview()', async () => {
    api.payroll.getInputDraft.mockResolvedValue({ payrollMonth: '2026-07-01', drafts: [], etag: 'ETAG-LOAD-1' });
    api.payroll.saveInputDraft.mockRejectedValue(
      conflictError(409, 'มีผู้ใช้อื่นบันทึกร่างเงินเดือนของงวดนี้ไปแล้ว กรุณาโหลดข้อมูลใหม่ก่อนบันทึกอีกครั้ง'));

    renderWithClient(<PayrollPage user={hrUser} showToast={vi.fn()} />);

    const costOfLiving = await screen.findByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/);
    fireEvent.change(costOfLiving, { target: { value: '777' } });

    fireEvent.click(screen.getByRole('button', { name: /บันทึกร่าง/i }));

    // Persistent conflict banner (role="alert", stays on screen -- unlike the toast, which
    // auto-dismisses after 3.2s per useToast.js) with the server's own Thai message.
    const banner = await screen.findByRole('alert');
    expect(banner.textContent).toContain('มีผู้ใช้อื่นบันทึกร่างเงินเดือนของงวดนี้ไปแล้ว');

    // HR's typed value must still be sitting in the field -- not reverted, not blanked.
    expect(screen.getByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/).value).toBe('777');

    // Opus review finding F4: preview() must NOT run after a 409 -- it calls applyPeriod directly,
    // bypassing the dirty guard, and would repaint the form from a payload that (unlike
    // draftPayload()) filters out anything that doesn't already qualify as a submittable input.
    expect(api.payroll.preview).not.toHaveBeenCalled();
  });

  it('a 428 (missing If-Match) is treated the same as a 409: persistent message shown, and further autosaves stop firing', async () => {
    api.payroll.getInputDraft.mockResolvedValue({ payrollMonth: '2026-07-01', drafts: [], etag: 'ETAG-LOAD-1' });
    api.payroll.saveInputDraft.mockRejectedValue(
      conflictError(428, 'ต้องแนบ If-Match เพื่อบันทึกร่างเงินเดือน กรุณาโหลดหน้าใหม่แล้วลองอีกครั้ง'));

    renderWithClient(<PayrollPage user={hrUser} showToast={vi.fn()} />);

    const costOfLiving = await screen.findByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/);
    fireEvent.change(costOfLiving, { target: { value: '111' } });
    fireEvent.blur(costOfLiving);

    await waitFor(() => expect(api.payroll.saveInputDraft).toHaveBeenCalledTimes(1));
    await screen.findByRole('alert');

    // Opus review finding F8: without treating 428 like 409, autosaveDraft's guard never trips,
    // so every further blur re-fires the identical doomed headerless PUT. Type into ANOTHER field
    // and blur it -- if the guard is working, no second call happens.
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

  /**
   * Opus review finding NEW-1 (BLOCKER): a genuine sequence where a conflict is raised on a
   * CLEAN form (the บันทึกร่าง button does not check dirty before submitting), so draftDirtyRef
   * stays false throughout. The next ordinary background poll then takes the "data arrived"
   * effect's CLEAN branch, adopts a fresh good token, and clears the visible banner -- but if
   * draftConflictRef itself is not ALSO cleared there, autosaveDraft's `|| draftConflictRef.current`
   * guard keeps returning early forever afterward, with no banner and no toast left to explain why
   * nothing is saving. Exactly the silent-autosave-failure class issue #422/PR #426 exists to
   * eliminate. Uses fake timers to advance past the real 60s refetchInterval so the "next poll" is
   * genuine react-query behaviour, not a hand-simulated stand-in.
   */
  it('an ordinary background refetch after a conflict on a clean form clears draftConflictRef too, not just the banner', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      api.payroll.getInputDraft
        .mockResolvedValueOnce({ payrollMonth: '2026-07-01', drafts: [], etag: 'ETAG-LOAD-1' })
        .mockResolvedValue({ payrollMonth: '2026-07-01', drafts: [], etag: 'ETAG-LOAD-2' });
      api.payroll.saveInputDraft
        .mockRejectedValueOnce(conflictError(409, 'มีผู้ใช้อื่นบันทึกร่างเงินเดือนของงวดนี้ไปแล้ว'))
        .mockResolvedValue({ payrollMonth: '2026-07-01', drafts: [], etag: 'ETAG-SAVE-2' });

      renderWithClient(<PayrollPage user={hrUser} showToast={vi.fn()} />);

      // Click บันทึกร่าง WITHOUT typing anything first -- the button submits whatever is in the
      // form regardless of dirty state, so this reaches the conflict branch with the form clean.
      await screen.findByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/);
      fireEvent.click(screen.getByRole('button', { name: /บันทึกร่าง/i }));
      await screen.findByRole('alert');
      expect(screen.getByRole('status').textContent).toContain('มีการบันทึกที่ขัดแย้งกัน');

      // Advance past the real 60s refetchInterval so react-query's own background poll fires --
      // not a hand-simulated stand-in for one.
      await vi.advanceTimersByTimeAsync(61_000);
      await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
      await waitFor(() => expect(screen.getByRole('status').textContent).toContain('บันทึกแล้ว'));

      // The regression: type into a field and blur. If draftConflictRef was correctly cleared by
      // the poll's clean path, this autosave actually fires; if not, it silently does nothing.
      const costOfLiving = screen.getByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/);
      fireEvent.change(costOfLiving, { target: { value: '321' } });
      fireEvent.blur(costOfLiving);

      await vi.waitFor(() => expect(api.payroll.saveInputDraft).toHaveBeenCalledTimes(2));
    } finally {
      vi.useRealTimers();
    }
  });

  it('the conflict banner\'s "โหลดข้อมูลใหม่" action clears the conflict and reloads', async () => {
    api.payroll.getInputDraft
      .mockResolvedValueOnce({ payrollMonth: '2026-07-01', drafts: [], etag: 'ETAG-LOAD-1' })
      .mockResolvedValue({ payrollMonth: '2026-07-01', drafts: [{ employeeId: 1, specialPay1: 999 }], etag: 'ETAG-LOAD-2' });
    api.payroll.saveInputDraft.mockRejectedValue(conflictError(409, 'มีผู้ใช้อื่นบันทึกร่างเงินเดือนของงวดนี้ไปแล้ว'));

    renderWithClient(<PayrollPage user={hrUser} showToast={vi.fn()} />);

    const costOfLiving = await screen.findByLabelText(/พิเศษ 1 \(ค่าครองชีพ\)/);
    fireEvent.change(costOfLiving, { target: { value: '777' } });
    fireEvent.blur(costOfLiving);
    await screen.findByRole('alert');

    const callsBeforeReload = api.payroll.getInputDraft.mock.calls.length;
    fireEvent.click(screen.getByRole('button', { name: /โหลดข้อมูลใหม่/i }));

    await waitFor(() => expect(api.payroll.getInputDraft.mock.calls.length).toBeGreaterThan(callsBeforeReload));
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
  });
});
