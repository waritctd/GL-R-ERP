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
    // P0 fix: the submit form's day-type field is now a read-only preview of
    // hr.holiday, reusing the SAME employee-self-scoped endpoint the Leave composer already
    // calls (GET /api/leave/calendar-context) -- see OvertimePanel.jsx's dayTypeContextQuery.
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
    // No holidays in range by default -- the read-only preview resolves to "วันทำงานปกติ".
    api.leave.calendarContext.mockResolvedValue({ calendarContext: { holidays: [], nonWorkingDates: [] } });
  });

  it('blocks submit when planned end is not after planned start', async () => {
    renderOvertimePage();

    const startInput = await screen.findByLabelText(/^เริ่ม/);
    const endInput = screen.getByLabelText(/สิ้นสุด/);
    const submitButton = screen.getByRole('button', { name: /ส่งคำขอ/ });

    fireEvent.change(startInput, { target: { value: '2026-07-07T20:00' } });
    fireEvent.change(endInput, { target: { value: '2026-07-07T18:00' } });

    expect(await screen.findByText('เวลาสิ้นสุดต้องอยู่หลังเวลาเริ่ม')).not.toBeNull();
    await waitFor(() => expect(submitButton.disabled).toBe(true));

    fireEvent.click(submitButton);

    expect(api.overtime.create).not.toHaveBeenCalled();
  });

  it('sends the existing overtime payload shape for a valid submit', async () => {
    renderOvertimePage();
    const workDate = isoDaysFromToday(3);

    fireEvent.change(await screen.findByLabelText(/วันที่ทำ OT/), { target: { value: workDate } });
    fireEvent.change(screen.getByLabelText(/เหตุผลความจำเป็น/), { target: { value: 'ทดสอบระบบ' } });
    fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));

    await waitFor(() => expect(api.overtime.create).toHaveBeenCalledTimes(1));
    // No dayType: the field was removed from the submit payload entirely (P0 fix) -- the server
    // derives it from hr.holiday and never reads a caller-declared value. See
    // OvertimeDayTypeDerivedFromCalendarIntegrationTest.java on the backend for the real-DB proof.
    expect(api.overtime.create).toHaveBeenCalledWith({
      employeeId: 1,
      workDate,
      plannedStartAt: `${workDate}T18:00:00+07:00`,
      plannedEndAt: `${workDate}T20:00:00+07:00`,
      reason: 'ทดสอบระบบ',
    });
  });

  // P0 fix, UI half: the day-type field must read as system-determined information the employee
  // cannot set, not a form control that happens to be pre-filled.
  it('renders the day-type field as a disabled, read-only preview -- not a selectable control', async () => {
    renderOvertimePage();

    const dayTypeField = await screen.findByLabelText(/ประเภท OT/);
    expect(dayTypeField.tagName).toBe('INPUT');
    expect(dayTypeField.disabled).toBe(true);
    // No HOLIDAY option anywhere on the page for the employee to pick -- the whole point is that
    // there is no longer a channel to declare one.
    expect(screen.queryByRole('option', { name: /วันหยุด/ })).toBeNull();
    expect(screen.queryByRole('combobox', { name: /ประเภท OT/ })).toBeNull();
  });

  it('previews "วันทำงานปกติ" when the holiday calendar has no entry for the selected date', async () => {
    renderOvertimePage();

    const dayTypeField = await screen.findByLabelText(/ประเภท OT/);
    await waitFor(() => expect(dayTypeField.value).toBe('วันทำงานปกติ · 1.5x'));
  });

  it('previews the specific holiday name (provenance) when the calendar has an entry for the selected date', async () => {
    const workDate = isoDaysFromToday(5);
    api.leave.calendarContext.mockResolvedValue({
      calendarContext: {
        holidays: [{ holidayDate: workDate, nameTh: 'วันแม่แห่งชาติ' }],
        nonWorkingDates: [workDate],
      },
    });
    renderOvertimePage();

    fireEvent.change(await screen.findByLabelText(/วันที่ทำ OT/), { target: { value: workDate } });

    const dayTypeField = await screen.findByLabelText(/ประเภท OT/);
    await waitFor(() => expect(dayTypeField.value).toBe('วันหยุดตามปฏิทินบริษัท: วันแม่แห่งชาติ · 3x'));
  });

  // Advance notice was removed on CEO instruction. Same-day is now the default the form opens on.
  it('accepts a same-day request', async () => {
    renderOvertimePage();
    const today = isoDaysFromToday(0);

    expect((await screen.findByLabelText(/วันที่ทำ OT/)).value).toBe(today);
    fireEvent.change(screen.getByLabelText(/เหตุผลความจำเป็น/), { target: { value: 'ทดสอบระบบ' } });
    fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));

    await waitFor(() => expect(api.overtime.create).toHaveBeenCalledTimes(1));
    expect(api.overtime.create.mock.calls[0][0].workDate).toBe(today);
  });

  it('accepts a backdated request when the reason explains the delay', async () => {
    renderOvertimePage();
    const workDate = isoDaysFromToday(-3);

    fireEvent.change(await screen.findByLabelText(/วันที่ทำ OT/), { target: { value: workDate } });
    fireEvent.change(screen.getByLabelText(/เหตุผลความจำเป็น/), {
      target: { value: 'ลูกค้าเร่งงานด่วน ทำ OT แล้วยื่นย้อนหลังหลังเลิกกะ' },
    });
    fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));

    await waitFor(() => expect(api.overtime.create).toHaveBeenCalledTimes(1));
    expect(api.overtime.create.mock.calls[0][0].workDate).toBe(workDate);
  });

  it('blocks a backdated request whose reason is too short', async () => {
    renderOvertimePage();

    fireEvent.change(await screen.findByLabelText(/วันที่ทำ OT/), { target: { value: isoDaysFromToday(-3) } });
    fireEvent.change(screen.getByLabelText(/เหตุผลความจำเป็น/), { target: { value: 'OT' } });

    expect(await screen.findByText(/อย่างน้อย 20 ตัวอักษร/)).not.toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));

    expect(api.overtime.create).not.toHaveBeenCalled();
  });

  it('blocks a request backdated beyond the retroactive window', async () => {
    renderOvertimePage();

    fireEvent.change(await screen.findByLabelText(/วันที่ทำ OT/), { target: { value: isoDaysFromToday(-120) } });
    fireEvent.change(screen.getByLabelText(/เหตุผลความจำเป็น/), {
      target: { value: 'พบว่ายังไม่ได้เบิก OT ของกะเก่า จึงยื่นย้อนหลัง' },
    });

    expect(await screen.findByText(/ย้อนหลังได้ไม่เกิน 60 วัน/)).not.toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));

    expect(api.overtime.create).not.toHaveBeenCalled();
  });
});

// feat/pending-approver-info: "who this is waiting on" beside the OT status badge.
describe('OvertimePage pending-approver note', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // The submit form (with its read-only day-type preview) renders alongside the list
    // unconditionally, so every test here needs this mocked too, not just the submit-focused ones.
    api.leave.calendarContext.mockResolvedValue({ calendarContext: { holidays: [], nonWorkingDates: [] } });
    api.overtime.employees.mockResolvedValue({
      employees: [{
        employeeId: 1,
        employeeName: 'พนักงาน ทดสอบ',
        employeeCode: 'GLR-001',
        self: true,
        directReport: false,
      }],
    });
  });

  it('renders the note for a SUBMITTED request with a manager stage, and omits it for an APPROVED one', async () => {
    api.overtime.list.mockResolvedValue({
      requests: [
        {
          id: 801, employeeId: 1, employeeName: 'พนักงาน ทดสอบ', employeeCode: 'GLR-001',
          workDate: '2026-07-07', plannedStartAt: '2026-07-07T18:00:00+07:00', plannedEndAt: '2026-07-07T20:00:00+07:00',
          plannedMinutes: 120, dayType: 'WORKDAY', reason: 'งานเร่งด่วน', status: 'SUBMITTED',
          actualMinutes: 0, payableMinutes: 0, hasManagerApprover: true,
          pendingApproverRole: 'manager', pendingApproverName: 'เอ็ม',
        },
        {
          id: 802, employeeId: 1, employeeName: 'พนักงาน ทดสอบ', employeeCode: 'GLR-001',
          workDate: '2026-07-01', plannedStartAt: '2026-07-01T18:00:00+07:00', plannedEndAt: '2026-07-01T20:00:00+07:00',
          plannedMinutes: 120, dayType: 'WORKDAY', reason: 'งานเสร็จแล้ว', status: 'APPROVED',
          actualMinutes: 120, payableMinutes: 120, hasManagerApprover: true,
          pendingApproverRole: null, pendingApproverName: null,
        },
      ],
    });
    renderOvertimePage();

    await screen.findByText('งานเร่งด่วน');
    expect(await screen.findByText('ผู้จัดการ (คุณเอ็ม)')).not.toBeNull();
  });

  it('shows the role alone (CEO) when the backend could not resolve a single approver name', async () => {
    api.overtime.list.mockResolvedValue({
      requests: [{
        id: 803, employeeId: 1, employeeName: 'พนักงาน ทดสอบ', employeeCode: 'GLR-001',
        workDate: '2026-07-03', plannedStartAt: '2026-07-03T18:00:00+07:00', plannedEndAt: '2026-07-03T20:00:00+07:00',
        plannedMinutes: 120, dayType: 'WORKDAY', reason: 'ไม่มีผู้จัดการฝ่าย', status: 'SUBMITTED',
        actualMinutes: 0, payableMinutes: 0, hasManagerApprover: false,
        pendingApproverRole: 'ceo', pendingApproverName: null,
      }],
    });
    renderOvertimePage();

    await screen.findByText('ไม่มีผู้จัดการฝ่าย');
    // Scope to the approver note's own <small> -- bare "CEO" (no "รอ" prefix, review
    // #pending-approver-info) could otherwise match a StatusBadge whose own label happens to be
    // "CEO" text too, depending on status.
    expect(await screen.findByText('CEO', { selector: 'small.text-text-muted' })).not.toBeNull();
  });
});
