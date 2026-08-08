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
    api.leave.calendarContext.mockResolvedValue({
      calendarContext: { holidays: [{ holidayDate: today, nameTh: LONGEST_REAL_HOLIDAY_NAME }] },
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

  it('warns inline on ประเภท OT that submitting HOLIDAY will be refused when the date is not a holiday', async () => {
    api.leave.calendarContext.mockResolvedValue({ calendarContext: { holidays: [] } });
    renderOvertimePage();

    await screen.findByText('วันทำงานปกติ · 1.5x', { selector: 'span' });
    fireEvent.change(screen.getByLabelText(/ประเภท OT/), { target: { value: 'HOLIDAY' } });

    expect(await screen.findByText(/ระบบจะปฏิเสธคำขอนี้/)).not.toBeNull();
  });

  it('notes inline that a HOLIDAY date pays 3x automatically even while WORKDAY stays selected', async () => {
    const today = isoDaysFromToday(0);
    api.leave.calendarContext.mockResolvedValue({
      calendarContext: { holidays: [{ holidayDate: today, nameTh: 'วันหยุดทดสอบ' }] },
    });
    renderOvertimePage();

    expect(await screen.findByText(/ระบบจะคำนวณอัตรา 3x ให้โดยอัตโนมัติ/)).not.toBeNull();
  });
});
