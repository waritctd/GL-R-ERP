import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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
    // #ot-holiday-visibility: OvertimePanel now reads this unconditionally (UpcomingHolidays'
    // ~90-day panel AND the single-day verdict-badge lookup both call it) -- every describe block
    // below that renders OvertimePage needs its own default resolved value, or the verdict/panel
    // queries error out under every pre-existing test too.
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
    // Default: no holidays anywhere -- keeps every test below on the WORKDAY verdict/empty
    // UpcomingHolidays panel, uninvolved with what each test actually asserts on. The verdict
    // states themselves are covered by their own describe block further down.
    api.leave.calendarContext.mockResolvedValue({ calendarContext: { holidays: [] } });
  });

  it('blocks submit when planned end is not after planned start', async () => {
    renderOvertimePage();

    // A2: two dates + two times now, not two datetime-local inputs -- both same-day here (วันที่
    // ทำ OT / วันที่สิ้นสุด default to the same day), so this exercises the time-only ordering rule
    // in isolation. Anchored regex, not an exact string: FormField appends a `required` asterisk
    // to the label's actual text content, and `^เวลาสิ้นสุด` cannot also match "วันที่สิ้นสุด".
    const startTimeInput = await screen.findByLabelText(/^เวลาเริ่ม/);
    const endTimeInput = screen.getByLabelText(/^เวลาสิ้นสุด/);
    const submitButton = screen.getByRole('button', { name: /ส่งคำขอ/ });

    fireEvent.change(startTimeInput, { target: { value: '20:00' } });
    fireEvent.change(endTimeInput, { target: { value: '18:00' } });

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
    expect(api.overtime.create).toHaveBeenCalledWith({
      employeeId: 1,
      workDate,
      plannedStartAt: `${workDate}T18:00:00+07:00`,
      plannedEndAt: `${workDate}T20:00:00+07:00`,
      dayType: 'WORKDAY',
      reason: 'ทดสอบระบบ',
    });
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

  // A2 (OT UAT defect #3): mirrors OvertimeService#validatePlannedWindow's ≤24h guard client-side
  // -- the two backend boundary tests (plannedWindowLongerThanOneDayIsRejected /
  // ...ExactlyOneDayAfterWorkDateIsAccepted) have no frontend counterpart until now. Anchored
  // regex for วันที่สิ้นสุด, not a bare substring match: "เวลาสิ้นสุด" also contains "สิ้นสุด", and
  // FormField's required asterisk sits outside the anchor either way.
  it('blocks submit when วันที่สิ้นสุด is more than one day after วันที่ทำ OT', async () => {
    renderOvertimePage();
    const workDate = isoDaysFromToday(3);
    const tooFarEndDate = isoDaysFromToday(5); // workDate + 2 days -- one day past the allowed cap.

    fireEvent.change(await screen.findByLabelText(/วันที่ทำ OT/), { target: { value: workDate } });
    fireEvent.change(screen.getByLabelText(/^วันที่สิ้นสุด/), { target: { value: tooFarEndDate } });
    fireEvent.change(screen.getByLabelText(/เหตุผลความจำเป็น/), { target: { value: 'ทดสอบระบบ' } });

    expect(await screen.findByText('วันที่สิ้นสุดต้องเป็นวันที่ทำ OT หรือวันถัดไปเท่านั้น')).not.toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));

    expect(api.overtime.create).not.toHaveBeenCalled();
  });

  // The boundary the guard above must still ACCEPT -- the overnight window the two-date redesign
  // exists to support. เวลาเริ่ม/เวลาสิ้นสุด are left at their form defaults (18:00/20:00): the point
  // of this case is วันที่สิ้นสุด = วันที่ทำ OT + 1 day exactly, not any particular time pair.
  it('accepts a request whose วันที่สิ้นสุด is exactly one day after วันที่ทำ OT', async () => {
    renderOvertimePage();
    const workDate = isoDaysFromToday(3);
    const nextDay = isoDaysFromToday(4);

    fireEvent.change(await screen.findByLabelText(/วันที่ทำ OT/), { target: { value: workDate } });
    fireEvent.change(screen.getByLabelText(/^วันที่สิ้นสุด/), { target: { value: nextDay } });
    fireEvent.change(screen.getByLabelText(/เหตุผลความจำเป็น/), { target: { value: 'ทดสอบระบบ' } });
    fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));

    await waitFor(() => expect(api.overtime.create).toHaveBeenCalledTimes(1));
    expect(api.overtime.create).toHaveBeenCalledWith({
      employeeId: 1,
      workDate,
      plannedStartAt: `${workDate}T18:00:00+07:00`,
      plannedEndAt: `${nextDay}T20:00:00+07:00`,
      dayType: 'WORKDAY',
      reason: 'ทดสอบระบบ',
    });
  });
});

// feat/pending-approver-info: "who this is waiting on" beside the OT status badge.
describe('OvertimePage pending-approver note', () => {
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
    api.leave.calendarContext.mockResolvedValue({ calendarContext: { holidays: [] } });
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

    const row = (await screen.findByText('ไม่มีผู้จัดการฝ่าย')).closest('.data-row');
    // Scoped to the "ขั้นอนุมัติ" column specifically, NOT the whole row -- the row's own "เหตุผล"
    // column legitimately contains the substring "ผู้จัดการ" in this fixture's reason text
    // ("ไม่มีผู้จัดการฝ่าย"), which would otherwise make a row-wide /ผู้จัดการ/ query match for the
    // wrong reason.
    const approvalColumn = row.querySelector('[data-label="ขั้นอนุมัติ"]');

    // Scope to the approver note's own <small> -- bare "CEO" (no "รอ" prefix, review
    // #pending-approver-info) could otherwise match a StatusBadge whose own label happens to be
    // "CEO" text too, depending on status.
    expect(await screen.findByText('CEO', { selector: 'small.text-text-muted' })).not.toBeNull();

    // A1 (OT UAT defect #1): the StatusBadge itself must read "รอ CEO", not the "รอผู้จัดการ" a
    // status-only map would give every SUBMITTED row regardless of route -- and the ผู้จัดการ:
    // audit line, which could never fill on this manager-less route, must not render at all. This
    // is the exact fixture the header comment on this describe block warned had locked in the
    // buggy badge.
    expect(within(approvalColumn).getByText('รอ CEO')).not.toBeNull();
    expect(within(approvalColumn).queryByText(/ผู้จัดการ/)).toBeNull();
  });
});

// A2 (OT UAT defect #3): the "แผน OT" column must show " (+1 วัน)" only when plannedEndAt genuinely
// lands on a different Bangkok-local calendar day than workDate (OvertimePanel.jsx's
// isOvernightWindow) -- this is the one behaviour the whole two-date-picker redesign exists to
// support, and until this block it had no permanent regression test (only a throwaway one written
// and discarded during manual verification). Both a same-day and an overnight request render in
// ONE list so the negative (same-day) assertion is not vacuous for an unrelated reason -- e.g. both
// rows happening to share a status/route that coincidentally never shows the suffix.
describe('OvertimePage แผน OT column (A2 overnight display)', () => {
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
    api.leave.calendarContext.mockResolvedValue({ calendarContext: { holidays: [] } });
    api.overtime.list.mockResolvedValue({
      requests: [
        {
          id: 901, employeeId: 1, employeeName: 'พนักงาน ทดสอบ', employeeCode: 'GLR-001',
          workDate: '2026-07-07',
          plannedStartAt: '2026-07-07T18:00:00+07:00', plannedEndAt: '2026-07-07T20:00:00+07:00',
          plannedMinutes: 120, dayType: 'WORKDAY', reason: 'same-day shift', status: 'SUBMITTED',
          actualMinutes: 0, payableMinutes: 0, hasManagerApprover: true,
          pendingApproverRole: 'manager', pendingApproverName: null,
        },
        {
          id: 902, employeeId: 1, employeeName: 'พนักงาน ทดสอบ', employeeCode: 'GLR-001',
          workDate: '2026-07-07',
          // 22:00 -> 02:00 the NEXT Bangkok day. Real +07:00 instants, not bare local strings --
          // isOvernightWindow compares dateIso(new Date(plannedEndAt)) (parsed in Asia/Bangkok)
          // against workDate, so an offset-less string would instead be parsed in whatever zone
          // the test happens to run in and could pass or fail for the wrong reason depending on
          // where the suite executes.
          plannedStartAt: '2026-07-07T22:00:00+07:00', plannedEndAt: '2026-07-08T02:00:00+07:00',
          plannedMinutes: 240, dayType: 'WORKDAY', reason: 'overnight shift', status: 'SUBMITTED',
          actualMinutes: 0, payableMinutes: 0, hasManagerApprover: true,
          pendingApproverRole: 'manager', pendingApproverName: null,
        },
      ],
    });
  });

  it('shows (+1 วัน) only for the request whose plannedEndAt lands on the next Bangkok day', async () => {
    renderOvertimePage();

    const sameDayRow = (await screen.findByText('same-day shift')).closest('.data-row');
    const overnightRow = (await screen.findByText('overnight shift')).closest('.data-row');
    const sameDayPlan = sameDayRow.querySelector('[data-label="แผน OT"] strong');
    const overnightPlan = overnightRow.querySelector('[data-label="แผน OT"] strong');

    // Negative case: a same-day request shows the time range with NO (+1 วัน) suffix. Exact-match,
    // not a substring check -- วันที่ / พนักงาน (the first column) already prints วันที่ทำ OT once,
    // so pinning the WHOLE string here also proves this column never repeats it: there is no room
    // for a stray date to sneak into an exact match.
    expect(sameDayPlan.textContent).toBe('18:00–20:00 น.');

    // Positive case: the overnight request appends the suffix, same exact-match reasoning.
    expect(overnightPlan.textContent).toBe('22:00–02:00 น. (+1 วัน)');
  });
});

// Mirrors MyLeaveTab.test.jsx's "Phase A4: self-cancel confirmation" case -- OT's own-request
// cancel used to skip the ConfirmDialog entirely (falling through to doCancel(id, '') for the
// canCancel path while only canManagerCancel opened it), the one self-service cancel in the app
// with no confirmation step before an irreversible mutation.
describe('OvertimePage self-cancel confirmation', () => {
  const ownSubmittedRow = {
    id: 2002,
    employeeId: 1,
    employeeName: 'พนักงาน ทดสอบ',
    employeeCode: 'GLR-001',
    workDate: isoDaysFromToday(0),
    plannedStartAt: `${isoDaysFromToday(0)}T18:00:00+07:00`,
    plannedEndAt: `${isoDaysFromToday(0)}T20:00:00+07:00`,
    plannedMinutes: 120,
    actualMinutes: 0,
    payableMinutes: 0,
    dayType: 'WORKDAY',
    status: 'SUBMITTED',
    reason: 'ทดสอบระบบ',
  };

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
    api.overtime.list.mockResolvedValue({ requests: [ownSubmittedRow] });
    api.leave.calendarContext.mockResolvedValue({ calendarContext: { holidays: [] } });
  });

  it('clicking ยกเลิก on a SUBMITTED own request opens a ConfirmDialog and does NOT cancel until confirmed', async () => {
    renderOvertimePage();

    await screen.findByText('ทดสอบระบบ');
    fireEvent.click(screen.getByRole('button', { name: 'ยกเลิก' }));

    // Same-tick sanity check, not the real proof -- mutate() doesn't invoke the mutationFn
    // synchronously, so this alone would still pass under the old bug. The dialog actually
    // appearing (next line) is what proves the row action didn't cancel by itself.
    expect(api.overtime.cancel).not.toHaveBeenCalled();
    const dialogConfirmButton = await screen.findByRole('button', { name: 'ยกเลิกคำขอ' });
    // Optional reason -- an employee cancelling their own request is not held to the mandatory
    // reason an approver's reject requires.
    expect(dialogConfirmButton.disabled).toBe(false);

    fireEvent.click(dialogConfirmButton);
    await waitFor(() => expect(api.overtime.cancel).toHaveBeenCalledWith(2002, { reviewerNote: null }));
  });
});

// #ot-holiday-visibility (PR 2): the verdict badge and its pre-flight mismatch note, both driven
// by api.leave.calendarContext -- see OvertimePanel.jsx's own comments on dayTypeVerdictQuery/
// dayTypeMismatch for the decision table this predicts (OvertimeService#validateDayTypeClaim).
// Mock-mode only: this exercises the FRONTEND's reading of calendarContext's response shape, not
// whether the real backend actually derives/validates day types correctly (see PR 1 for that).
describe('OvertimePage holiday verdict', () => {
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
  });

  // Uses a REAL production holiday name (2026-12-07, 149 chars -- the longest row in prod's
  // hr.holiday, and the reason V129 widened name_th from VARCHAR(120) to TEXT), not a tidy
  // 12-char stand-in. The verdict pill is `rounded-full`, a shape that asserts "one short line";
  // interpolating a name this long into it wrapped inside the pill and read as broken. Pinning a
  // realistic worst-case label here is what keeps the pill and the name structurally separate.
  const LONGEST_REAL_HOLIDAY_NAME =
    'ชดเชยวันคล้ายวันพระบรมราชสมภพ พระบาทสมเด็จพระบรมชนกาธิเบศร มหาภูมิพลอดุลยเดชมหาราช บรมนาถบพิตร วันชาติ และวันพ่อแห่งชาติ (วันเสาร์ที่ 5 ธันวาคม 2569)';

  it('shows the HOLIDAY verdict (3x) and the holiday name separately, so a long real name cannot break the pill', async () => {
    const today = isoDaysFromToday(0);
    // feat/ot-nonworkday-rate-suggestion: the verdict now reads nonWorkingDates (the
    // schedule-aware answer), not holidays[0] -- a realistic fixture for "today is a genuine
    // holiday" carries both, matching the real LeaveCalendarContextService#get contract (a
    // recorded holiday is always folded into nonWorkingDates too).
    api.leave.calendarContext.mockResolvedValue({
      calendarContext: {
        holidays: [{ holidayDate: today, nameTh: LONGEST_REAL_HOLIDAY_NAME }],
        nonWorkingDates: [today],
      },
    });
    renderOvertimePage();

    // The pill carries ONLY the fixed verdict + rate -- an exact-match query, so if the
    // variable-length name is ever interpolated back into it this fails immediately.
    expect(await screen.findByText('วันหยุดบริษัท · 3x')).not.toBeNull();

    // ...and the name is still shown, as its own wrappable element. TWO matches is correct, not a
    // duplicate-render bug: the same holiday is legitimately both an entry in the วันหยุดที่จะถึง
    // panel (it is upcoming) and the verdict for the currently selected date. Asserting the count
    // rather than using findAllByText loosely, so a genuine double-render inside one region would
    // still fail.
    expect(screen.getAllByText(LONGEST_REAL_HOLIDAY_NAME)).toHaveLength(2);
  });

  it('shows the WORKDAY verdict (1.5x) when the calendar loaded and the selected date has no holiday', async () => {
    api.leave.calendarContext.mockResolvedValue({ calendarContext: { holidays: [] } });
    renderOvertimePage();

    // Disambiguated from the identically-worded ประเภท OT <option> (Testing Library sees option
    // text too) -- same technique the pending-approver-note tests above already use.
    expect(await screen.findByText('วันทำงานปกติ · 1.5x', { selector: 'span' })).not.toBeNull();
  });

  it('shows the "calendar not loaded" verdict when the holiday lookup fails', async () => {
    api.leave.calendarContext.mockRejectedValue(new Error('เครือข่ายขัดข้อง'));
    renderOvertimePage();

    expect(await screen.findByText('ปฏิทินยังไม่ได้โหลด')).not.toBeNull();
  });

  // feat/ot-nonworkday-rate-suggestion: owner ruling 2026-08-08 removed the outright refusal --
  // an over-claim is now accepted and flagged for the approver, never refused. Renamed from
  // "...will be refused when the date is not a holiday" and its assertion updated accordingly.
  it('notes inline that an over-claim will be flagged for the approver, not refused', async () => {
    api.leave.calendarContext.mockResolvedValue({ calendarContext: { holidays: [], nonWorkingDates: [] } });
    renderOvertimePage();

    await screen.findByText('วันทำงานปกติ · 1.5x', { selector: 'span' });
    fireEvent.change(screen.getByLabelText(/ประเภท OT/), { target: { value: 'HOLIDAY' } });

    expect(await screen.findByText(/ระบบจะแนะนำผู้อนุมัติให้ใช้อัตรา 1\.5x/)).not.toBeNull();
  });

  // Renamed from "...pays 3x automatically..." -- the approver, not an automatic recalculation,
  // now decides the final rate (owner ruling 2026-08-08).
  it('notes inline that a suggested-HOLIDAY date will be flagged for the approver even while WORKDAY stays selected', async () => {
    const today = isoDaysFromToday(0);
    api.leave.calendarContext.mockResolvedValue({
      calendarContext: {
        holidays: [{ holidayDate: today, nameTh: 'วันหยุดทดสอบ' }],
        nonWorkingDates: [today],
      },
    });
    renderOvertimePage();

    expect(await screen.findByText(/ระบบจะแนะนำผู้อนุมัติให้ใช้อัตรา 3x/)).not.toBeNull();
  });
});

// feat/ot-nonworkday-rate-suggestion: the approve dialog's day-type selector. Shown only when the
// request is FLAGGED (a non-workday suggestion, or an explicit calculation_note flag) AND on the
// stage that can still set the rate (SUBMITTED) -- the freeze point does not move, so the
// second-stage CEO sign-off (MANAGER_APPROVED) never shows it, matching
// OvertimeService#ceoApprove never reading dayType. Defaults to the suggestion; the approver's
// selection, not the request's stored claim, is what gets sent.
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

  it('shows no selector for an unflagged ordinary request, and approves with no override at all', async () => {
    api.overtime.list.mockResolvedValue({
      requests: [{
        id: 901, employeeId: 10, employeeName: 'ลูกทีม ทดสอบ', employeeCode: 'EMP-010',
        workDate: '2026-08-04', plannedStartAt: '2026-08-04T18:00:00+07:00', plannedEndAt: '2026-08-04T20:00:00+07:00',
        plannedMinutes: 120, dayType: 'WORKDAY', suggestedDayType: 'WORKDAY', payableMinutes: 0, actualMinutes: 0,
        reason: 'งานเร่งด่วน', status: 'SUBMITTED', hasManagerApprover: true,
        pendingApproverRole: 'manager', pendingApproverName: null, calculationNote: null,
      }],
    });
    renderAsReviewer(managerUser);

    fireEvent.click(await screen.findByRole('button', { name: 'ผู้จัดการอนุมัติ' }));
    await screen.findByText('ยืนยันการอนุมัติ OT');

    expect(screen.queryByRole('combobox', { name: 'อัตราที่จะอนุมัติ' })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'อนุมัติ' }));

    // No dayType key at all -- the server falls back to its own fresh suggestion.
    await waitFor(() => expect(api.overtime.approve).toHaveBeenCalledWith(901, {}));
  });

  it('shows the selector, defaulted to the SUGGESTION not the claim, for a flagged claim-disagreement even when the suggestion is WORKDAY', async () => {
    api.overtime.list.mockResolvedValue({
      requests: [{
        id: 902, employeeId: 10, employeeName: 'ลูกทีม ทดสอบ', employeeCode: 'EMP-010',
        workDate: '2026-08-04', plannedStartAt: '2026-08-04T18:00:00+07:00', plannedEndAt: '2026-08-04T20:00:00+07:00',
        plannedMinutes: 120, dayType: 'WORKDAY', suggestedDayType: 'WORKDAY', payableMinutes: 0, actualMinutes: 0,
        reason: 'งานเร่งด่วน', status: 'SUBMITTED', hasManagerApprover: true,
        pendingApproverRole: 'manager', pendingApproverName: null,
        calculationNote: '[ไม่ตรงกับที่ระบบแนะนำ] พนักงานระบุ วันหยุด (3x) แต่ระบบแนะนำ วันทำงานปกติ (1.5x) โปรดตรวจสอบก่อนอนุมัติ',
      }],
    });
    renderAsReviewer(managerUser);

    fireEvent.click(await screen.findByRole('button', { name: 'ผู้จัดการอนุมัติ' }));

    const selector = await screen.findByRole('combobox', { name: 'อัตราที่จะอนุมัติ' });
    expect(selector.value, 'defaults to the SUGGESTION (WORKDAY), never the employee\'s claimed HOLIDAY').toBe('WORKDAY');
    // Two matches is correct, not a duplicate-render bug: the same flag note legitimately shows
    // both in the list row's เหตุผล column AND inside the dialog (beside the selector).
    expect(screen.getAllByText(/ไม่ตรงกับที่ระบบแนะนำ/).length).toBeGreaterThan(0);

    fireEvent.click(screen.getByRole('button', { name: 'อนุมัติ' }));

    await waitFor(() => expect(api.overtime.approve).toHaveBeenCalledWith(902, { dayType: 'WORKDAY' }));
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

// Whose task is this page for? The two roles open the OT tab to do opposite things, and the page
// order should follow that rather than one order serving both.
//
// This asserts DOM ORDER, which jsdom genuinely can do (unlike geometry — every rect here is 0×0).
// The measurement that motivated it can only come from a browser: at 390px the "ยื่นคำขอ OT"
// heading sat 1,180px down a 2,301px page, i.e. off-screen on arrival, behind a five-tile strip
// reading 0/0/0/0 and a four-field date-range filter for a table the user had not reached. After:
// y=594, on-screen.
describe('OvertimePanel section order follows the role', () => {
  function orderOf(...nodes) {
    // DOCUMENT_POSITION_FOLLOWING (4) === b comes after a.
    return nodes.every((node, i) => i === 0
      || (nodes[i - 1].compareDocumentPosition(node) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0);
  }

  beforeEach(() => {
    vi.clearAllMocks();
    api.overtime.employees.mockResolvedValue({ employees: [] });
    api.overtime.list.mockResolvedValue({ requests: [] });
    // Same default every other describe here sets — see the #ot-holiday-visibility note on the
    // api mock above: UpcomingHolidays and the verdict badge both read leave.calendarContext.
    api.leave.calendarContext.mockResolvedValue({ holidays: [], workingDays: [] });
  });

  it('puts the submit form ABOVE the history stats/filter for a self-service employee', async () => {
    renderOvertimePage();

    const form = await screen.findByRole('heading', { name: 'ยื่นคำขอ OT' });
    const holidays = screen.getByRole('heading', { name: 'วันหยุดที่จะถึง' });
    const searchButton = screen.getByRole('button', { name: /ค้นหา/ });

    // Holidays still precede the form — #ot-holiday-visibility requires the holiday answer to
    // arrive before the claim is made, and this reorder must not quietly undo that.
    expect(orderOf(holidays, form)).toBe(true);
    // ...and the range filter now follows the form instead of burying it.
    expect(orderOf(form, searchButton)).toBe(true);
  });

  it('keeps the history stats/filter ABOVE the form for a manager, whose task is triage', async () => {
    // `canSubmitForTeam` is derived from the EMPLOYEES payload — `submitEmployeeOptions.some(e =>
    // e.directReport)` (OvertimePanel.jsx) — not from `user.manager`. Mocking an empty list here
    // renders the self-service order and the test passes for the wrong reason; a direct report is
    // what actually constructs the manager state this case is about.
    api.overtime.employees.mockResolvedValue({
      employees: [
        { employeeId: 99, employeeName: 'ผู้จัดการ ทดสอบ', employeeCode: 'MGR-001', self: true, directReport: false },
        { employeeId: 10, employeeName: 'ลูกทีม ทดสอบ', employeeCode: 'EMP-010', self: false, directReport: true },
      ],
    });
    const managerUser = { employeeId: 99, name: 'ผู้จัดการ ทดสอบ', role: 'employee', manager: true, divisionId: 5 };
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    render(
      <QueryClientProvider client={queryClient}>
        <OvertimePage user={managerUser} currentEmployee={{ id: 99, nameTh: managerUser.name }} showToast={vi.fn()} />
      </QueryClientProvider>,
    );

    // Await the TEAM sub-header first: it is rendered from the same  flag the
    // ordering keys off, so this fails loudly if the fixture did not actually construct the
    // manager state, instead of silently asserting the self-service order.
    await screen.findByText(/ยื่นคำขอแทนทีม/);
    const searchButton = screen.getByRole('button', { name: /ค้นหา/ });
    const form = screen.getByRole('heading', { name: 'ยื่นคำขอ OT' });

    // Wrong-way-round on purpose: the risk of this change is that it reorders BOTH views, which
    // would push a manager's queue controls below a form they rarely use.
    expect(orderOf(searchButton, form)).toBe(true);
  });
});

// The panel arranges itself around `canSubmitForTeam`, which is derived from the employees query —
// so the very first render always says "false" and a manager/HR briefly gets the EMPLOYEE layout.
// When the data lands, the stats/filter block jumps from the bottom of the page to the top and
// shoves the holidays panel and the submit form down.
//
// Measured in a real browser at 390px against the UAT roster: CLS 0.434 for HR and for a division
// manager, in a single shift ~139ms in, against a 0.1 "good" threshold. A plain employee measured
// 0.000, because their initial guess happens to be correct — which is exactly why testing as an
// employee never showed it.
//
// jsdom has no layout and cannot measure CLS, so these assert the cause instead: nothing
// order-dependent may render until the answer is known. Deferring only the stats block would not
// have fixed it (dropping it in at the top later still pushes everything below it down), so the
// assertion is that the FORM is absent too, not just the stats.
describe('OvertimePanel does not render a layout it is about to rearrange', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.overtime.list.mockResolvedValue({ requests: [] });
    api.leave.calendarContext.mockResolvedValue({ calendarContext: { holidays: [] } });
  });

  it('shows a loading state instead of guessing an order, while the roster is in flight', async () => {
    let releaseRoster;
    api.overtime.employees.mockReturnValue(new Promise((resolve) => { releaseRoster = resolve; }));

    renderOvertimePage();

    // Pending: neither layout. Not just "the stats block is absent" — the submit form, the thing
    // the reorder MOVES, must be absent too.
    // The panel's own placeholder, deliberately worded apart from the request table's
    // "กำลังโหลดคำขอ OT" EmptyState — asserting on that shared string would match either element
    // and could not tell "no layout chosen yet" from "the list is still loading".
    expect(await screen.findByText('กำลังเตรียมหน้าคำขอ OT')).not.toBeNull();
    expect(screen.queryByRole('heading', { name: 'ยื่นคำขอ OT' })).toBeNull();
    expect(screen.queryByRole('button', { name: /ส่งคำขอ/ })).toBeNull();
    // The subtitle names the role as well ("…แทนทีม" vs "…ประวัติของคุณ"), so it is held back too
    // rather than guessed and corrected.
    expect(screen.queryByText(/ยื่นคำขอแทนทีม/)).toBeNull();
    expect(screen.queryByText(/ประวัติของคุณ/)).toBeNull();

    releaseRoster({
      employees: [{
        employeeId: 1,
        employeeName: 'พนักงาน ทดสอบ',
        employeeCode: 'GLR-001',
        self: true,
        directReport: false,
      }],
    });

    // Settled: the real panel arrives, in one piece.
    expect(await screen.findByRole('heading', { name: 'ยื่นคำขอ OT' })).not.toBeNull();
    expect(screen.queryByText('กำลังเตรียมหน้าคำขอ OT')).toBeNull();
  });

  // A failed roster fetch must not strand the panel on the skeleton forever. `isPending` goes
  // false on an error too, which resolves to the employee layout — the right degradation, since
  // an empty roster is exactly "no direct reports".
  it('falls through to the employee layout when the roster fetch fails', async () => {
    api.overtime.employees.mockRejectedValue(new Error('boom'));

    renderOvertimePage();

    expect(await screen.findByRole('heading', { name: 'ยื่นคำขอ OT' })).not.toBeNull();
    expect(screen.queryByText('กำลังเตรียมหน้าคำขอ OT')).toBeNull();
  });
});
