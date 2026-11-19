import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { OvertimePage } from './OvertimePage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    overtime: {
      employees: vi.fn(),
      list: vi.fn(),
      create: vi.fn(),
      approve: vi.fn(),
      reject: vi.fn(),
      cancel: vi.fn(),
    },
    // #ot-holiday-visibility: OvertimePanel now reads this unconditionally -- every describe block below that
    // renders OvertimePage needs its own default resolved value, or the verdict/panel queries error out under
    // every pre-existing test too.
    leave: {
      calendarContext: vi.fn(),
    },
  },
}));

const user = {
  employeeId: 1,
  name: 'พนักงาน ทดสอบ',
  role: 'employee',
  manager: false,
};

const currentEmployee = {
  id: 1,
  nameTh: 'พนักงาน ทดสอบ',
};

// Dates are relative to "now" so these cases keep testing the rule rather than rotting into the past.
function isoDaysFromToday(days) {
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Bangkok',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(new Date()).map((part) => [part.type, part.value]));
  const date = new Date(`${parts.year}-${parts.month}-${parts.day}T00:00:00+07:00`);
  date.setUTCDate(date.getUTCDate() + days);
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Bangkok',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(date);
}

function renderOvertimePage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <OvertimePage user={user} currentEmployee={currentEmployee} showToast={vi.fn()} />
    </QueryClientProvider>,
  );
}

describe('OvertimePage form validation', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.overtime.employees.mockResolvedValue({
      employees: [{
        employeeId: 1,
        employeeName: 'พนักงาน ทดสอบ',
        employeeCode: 'GLR-001',
        self: true,
        directReport: false,
      }],
    });
    api.overtime.list.mockResolvedValue({ requests: [] });
    api.overtime.create.mockResolvedValue({ request: { id: 1001 } });
    // Default: no holidays anywhere -- keeps every test below on the WORKDAY verdict/empty UpcomingHolidays panel,
    // uninvolved with what each test actually asserts on. The verdict states themselves are covered by their own
    // describe block further down.
    api.leave.calendarContext.mockResolvedValue({ calendarContext: { holidays: [] } });
  });

  it('sends the existing overtime payload shape for a valid submit', async () => {
    renderOvertimePage();
    const workDate = isoDaysFromToday(3);

    fireEvent.change(await screen.findByLabelText(/วันที่ทำ OT/), { target: { value: workDate } });
    fireEvent.blur(screen.getByLabelText(/วันที่ทำ OT/));
    fireEvent.change(screen.getByLabelText(/เหตุผลความจำเป็น/), { target: { value: 'ทดสอบระบบ' } });
    fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));

    await waitFor(() => expect(api.overtime.create).toHaveBeenCalledTimes(1));
    expect(api.overtime.create).toHaveBeenCalledWith({
      employeeId: 1,
      workDate,
      plannedStartAt: `${workDate}T18:00:00+07:00`,
      plannedEndAt: `${workDate}T20:00:00+07:00`,
      dayType: 'WORKDAY',
      reason: 'ทดสอบระบบ',
    });
  });

  it('accepts a backdated request when the reason explains the delay', async () => {
    renderOvertimePage();
    const workDate = isoDaysFromToday(-3);

    fireEvent.change(await screen.findByLabelText(/วันที่ทำ OT/), { target: { value: workDate } });
    fireEvent.blur(screen.getByLabelText(/วันที่ทำ OT/));
    fireEvent.change(screen.getByLabelText(/เหตุผลความจำเป็น/), {
      target: { value: 'ลูกค้าเร่งงานด่วน ทำ OT แล้วยื่นย้อนหลังหลังเลิกกะ' },
    });
    fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));

    await waitFor(() => expect(api.overtime.create).toHaveBeenCalledTimes(1));
    expect(api.overtime.create.mock.calls[0][0].workDate).toBe(workDate);
  });

  it('blocks a request backdated beyond the retroactive window', async () => {
    renderOvertimePage();

    fireEvent.change(await screen.findByLabelText(/วันที่ทำ OT/), { target: { value: isoDaysFromToday(-120) } });
    fireEvent.blur(screen.getByLabelText(/วันที่ทำ OT/));
    fireEvent.change(screen.getByLabelText(/เหตุผลความจำเป็น/), {
      target: { value: 'พบว่ายังไม่ได้เบิก OT ของกะเก่า จึงยื่นย้อนหลัง' },
    });

    // The field itself refuses the date (its min is the 60-day window): it reverts with a range error.
    expect(await screen.findByText('วันที่อยู่นอกช่วงที่เลือกได้')).not.toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));

    expect(api.overtime.create).not.toHaveBeenCalled();
  });

  // mirrors OvertimeService#validatePlannedWindow's ≤24h guard client-side -- the two backend boundary tests
  // (plannedWindowLongerThanOneDayIsRejected / ...ExactlyOneDayAfterWorkDateIsAccepted) have no frontend
  // counterpart until now.
  it('blocks submit when วันที่สิ้นสุด is more than one day after วันที่ทำ OT', async () => {
    renderOvertimePage();
    const workDate = isoDaysFromToday(3);
    const tooFarEndDate = isoDaysFromToday(5); // workDate + 2 days -- one day past the allowed cap.

    fireEvent.change(await screen.findByLabelText(/วันที่ทำ OT/), { target: { value: workDate } });
    fireEvent.blur(screen.getByLabelText(/วันที่ทำ OT/));
    fireEvent.change(screen.getByLabelText(/^วันที่สิ้นสุด/), { target: { value: tooFarEndDate } });
    fireEvent.blur(screen.getByLabelText(/^วันที่สิ้นสุด/));
    fireEvent.change(screen.getByLabelText(/เหตุผลความจำเป็น/), { target: { value: 'ทดสอบระบบ' } });

    // The field itself refuses the date (its max is workDate + 1): it reverts with a range error.
    expect(await screen.findByText('วันที่อยู่นอกช่วงที่เลือกได้')).not.toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));

    expect(api.overtime.create).not.toHaveBeenCalled();
  });
});

// feat/ot-nonworkday-rate-suggestion: the approve dialog's day-type selector. Defaults to the suggestion; the
// approver's selection, not the request's stored claim, is what gets sent.
describe('OvertimePage approve dialog day-type override', () => {
  const managerUser = {
    employeeId: 99,
    name: 'ผู้จัดการ ทดสอบ',
    role: 'employee',
    manager: true,
    divisionId: 5,
  };

  const ceoUser = {
    employeeId: 500,
    name: 'ซีอีโอ ทดสอบ',
    role: 'ceo',
    manager: false,
  };

  function renderAsReviewer(reviewerUser) {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    return render(
      <QueryClientProvider client={queryClient}>
        <OvertimePage
          user={reviewerUser}
          currentEmployee={{ id: reviewerUser.employeeId, nameTh: reviewerUser.name }}
          showToast={vi.fn()}
        />
      </QueryClientProvider>,
    );
  }

  beforeEach(() => {
    vi.clearAllMocks();
    api.overtime.employees.mockResolvedValue({
      employees: [
        { employeeId: 99, employeeName: 'ผู้จัดการ ทดสอบ', employeeCode: 'MGR-001', self: true, directReport: false },
        { employeeId: 10, employeeName: 'ลูกทีม ทดสอบ', employeeCode: 'EMP-010', self: false, directReport: true },
      ],
    });
    api.leave.calendarContext.mockResolvedValue({ calendarContext: { holidays: [], nonWorkingDates: [] } });
    api.overtime.approve.mockResolvedValue({ request: { id: 900, status: 'MANAGER_APPROVED' } });
  });

  it('shows a selector defaulted to the suggestion for a flagged non-workday request, and sends the approver\'s override', async () => {
    api.overtime.list.mockResolvedValue({
      requests: [{
        id: 900, employeeId: 10, employeeName: 'ลูกทีม ทดสอบ', employeeCode: 'EMP-010',
        workDate: '2026-08-08', plannedStartAt: '2026-08-08T18:00:00+07:00', plannedEndAt: '2026-08-08T20:00:00+07:00',
        plannedMinutes: 120, dayType: 'HOLIDAY', suggestedDayType: 'HOLIDAY', payableMinutes: 0, actualMinutes: 0,
        reason: 'วันหยุดสุดสัปดาห์', status: 'SUBMITTED', hasManagerApprover: true,
        pendingApproverRole: 'manager', pendingApproverName: null, calculationNote: null,
      }],
    });
    renderAsReviewer(managerUser);

    fireEvent.click(await screen.findByRole('button', { name: 'ผู้จัดการอนุมัติ' }));

    const selector = await screen.findByRole('combobox', { name: 'อัตราที่จะอนุมัติ' });
    expect(selector.value).toBe('HOLIDAY');

    // The approver downgrades to WORKDAY -- the data looked wrong to them.
    fireEvent.change(selector, { target: { value: 'WORKDAY' } });
    fireEvent.click(screen.getByRole('button', { name: 'อนุมัติ' }));

    await waitFor(() => expect(api.overtime.approve).toHaveBeenCalledWith(900, { dayType: 'WORKDAY' }));
  });

  it('never shows the selector on the final CEO sign-off, even for a request already frozen at HOLIDAY/3x', async () => {
    api.overtime.list.mockResolvedValue({
      requests: [{
        id: 903, employeeId: 10, employeeName: 'ลูกทีม ทดสอบ', employeeCode: 'EMP-010',
        workDate: '2026-08-08', plannedStartAt: '2026-08-08T18:00:00+07:00', plannedEndAt: '2026-08-08T20:00:00+07:00',
        plannedMinutes: 120, dayType: 'HOLIDAY', suggestedDayType: 'HOLIDAY', payableMinutes: 180, actualMinutes: 120,
        reason: 'วันหยุดสุดสัปดาห์', status: 'MANAGER_APPROVED', hasManagerApprover: true,
        pendingApproverRole: 'ceo', pendingApproverName: null, calculationNote: 'Calculated from the overlap...',
      }],
    });
    renderAsReviewer(ceoUser);

    fireEvent.click(await screen.findByRole('button', { name: 'CEO อนุมัติ' }));
    await screen.findByText('ยืนยันการอนุมัติ OT');

    expect(screen.queryByRole('combobox', { name: 'อัตราที่จะอนุมัติ' })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'อนุมัติ' }));

    await waitFor(() => expect(api.overtime.approve).toHaveBeenCalledWith(903, {}));
  });
});

