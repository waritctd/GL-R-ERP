import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AttendancePage, PunchDetail, punchRole } from './AttendancePage.jsx';
import { api } from '../../api/index.js';
import { useToast } from '../../hooks/useToast.js';
import { Toast } from '../../components/common/Toast.jsx';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    attendance: {
      daily: vi.fn(),
      employees: vi.fn(),
      devices: vi.fn(),
      unmapped: vi.fn(),
      list: vi.fn(),
      importDat: vi.fn(),
      recalculate: vi.fn(),
      markPresent: vi.fn(),
      monthlySummary: vi.fn(),
    },
    // AttendanceCorrectionPanel.jsx now renders on this page (fix/attendance-correction-on-attendance-page)
    // for anyone CEO or with an employeeId -- both hrUser and selfViewUser below qualify, so every
    // existing test in this file mounts it too and needs this namespace mocked or it throws on
    // mount, not just the tests that exercise it directly.
    attendanceCorrection: {
      list: vi.fn(),
      create: vi.fn(),
      approve: vi.fn(),
      reject: vi.fn(),
      cancel: vi.fn(),
    },
  },
}));

const hrUser = { role: 'hr', employeeId: 1 };
// Not HR/CEO (no canViewAllAttendance) and not a manager -- attendanceMode() falls through to
// 'employee', i.e. isSelfView.
const selfViewUser = { role: 'employee', employeeId: 2, manager: false };
const ceoUser = { role: 'ceo', employeeId: 3 };
// F3 (adversarial review): a session not yet linked to an employee record -- e.g. a brand-new
// account. No fixture in this file exercised a null employeeId before, which is exactly how
// canRequestCorrection's `!!user.employeeId` conjunct and the panel's own `isCeo ||
// !!user.employeeId` gate both went untested on that half of the check.
const noEmployeeIdUser = { role: 'employee', employeeId: null, manager: false };

function renderWithClient(ui) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>);
}

// Mirrors App.jsx's real wiring (App.jsx:99): useToast() hands back a `showToast` function that is
// re-created (a new identity) on every render of whatever owns it -- unlike a test's `vi.fn()`,
// which never changes identity and so cannot reproduce the infinite-render-loop hazard this
// harness exists to catch (issue #422 adversarial review, BLOCKING 1). `onShowToast` is called
// once per actual invocation, independent of the (unstable) function identity itself, so a test
// can count how many times the real callback fired without that count being confused by identity
// changes.
function AppShapedHarness({ children, onShowToast }) {
  const { toast, showToast, dismissToast } = useToast();
  function wrappedShowToast(kind, message) {
    onShowToast?.(kind, message);
    showToast(kind, message);
  }
  return (
    <>
      {children(wrappedShowToast)}
      <Toast toast={toast} onDismiss={dismissToast} />
    </>
  );
}

describe('punchRole', () => {
  it('labels the first scan as clock-in (เข้า) and the last as clock-out (ออก)', () => {
    expect(punchRole(0, 4).label).toBe('เข้า');
    expect(punchRole(3, 4).label).toBe('ออก');
    expect(punchRole(1, 4).label).toBe('ระหว่างวัน');
    expect(punchRole(2, 4).label).toBe('ระหว่างวัน');
  });

  it('leaves a lone scan unlabelled — its direction is not asserted', () => {
    expect(punchRole(0, 1).label).toBe('');
  });
});

describe('PunchDetail — first punch is clock-in, last is clock-out', () => {
  // Punches arrive oldest-first (the AttendanceRepository.findPunches contract), so the earliest
  // time must render with เข้า and the latest with ออก regardless of how many scans there are.
  const punches = [
    { punch_id: 1, punch_time: '2024-03-04T08:20:00+07:00', site_code: 'SHOWROOM' },
    { punch_id: 2, punch_time: '2024-03-04T12:03:00+07:00', site_code: 'SHOWROOM' },
    { punch_id: 3, punch_time: '2024-03-04T13:10:00+07:00', site_code: 'SHOWROOM' },
    { punch_id: 4, punch_time: '2024-03-04T17:40:00+07:00', site_code: 'SHOWROOM' },
  ];

  it('binds เข้า to the earliest time and ออก to the latest', () => {
    render(<PunchDetail punches={punches} />);
    const rows = screen.getAllByRole('listitem');

    // getByText throws when the text is absent, so a successful lookup is the assertion.
    const first = within(rows[0]);
    expect(first.getByText('เข้า')).toBeTruthy();
    expect(first.getByText('08:20')).toBeTruthy();

    const last = within(rows[rows.length - 1]);
    expect(last.getByText('ออก')).toBeTruthy();
    expect(last.getByText('17:40')).toBeTruthy();

    // Mid-day scans are neither clock-in nor clock-out.
    expect(within(rows[1]).getByText('ระหว่างวัน')).toBeTruthy();
    expect(within(rows[2]).getByText('ระหว่างวัน')).toBeTruthy();
  });
});

// Issue #422 BLOCKING 4 (adversarial review): the whole B2 slice (useQuery, the four refetch()
// conversions, the scoped key, the relocated setExpandedKey(null) effect) previously shipped with
// NO rendering test at all -- this file only ever exercised the pure PunchDetail/punchRole
// helpers above. That gap is exactly how BLOCKING 1's infinite-loop regression in this same file
// went undetected.
describe('AttendancePage rendering (issue #422 B2)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.attendance.daily.mockResolvedValue({ days: [] });
    api.attendance.employees.mockResolvedValue({ employees: [] });
    api.attendance.devices.mockResolvedValue({ devices: [] });
    api.attendance.unmapped.mockResolvedValue({ badges: [] });
    api.attendanceCorrection.list.mockResolvedValue({ requests: [] });
  });

  it('loads and renders the daily attendance table on mount', async () => {
    api.attendance.daily.mockResolvedValue({
      days: [{
        employee_id: 1,
        work_date: '2026-08-01',
        employee_name: 'พนักงาน ทดสอบ',
        employee_code: 'GLR-001',
        check_in: '2026-08-01T08:00:00+07:00',
        check_out: '2026-08-01T17:00:00+07:00',
        total_minutes: 480,
        status: 'ON_TIME',
        punch_count: 2,
      }],
    });

    renderWithClient(<AttendancePage user={hrUser} showToast={vi.fn()} />);

    await screen.findByText('พนักงาน ทดสอบ');
    expect(api.attendance.daily).toHaveBeenCalledTimes(1);
  });

  it('the รีเฟรช button refetches the daily attendance query', async () => {
    renderWithClient(<AttendancePage user={hrUser} showToast={vi.fn()} />);

    // Scoped to `.page-actions` (PageHeader.jsx's own actions row): AttendanceCorrectionPanel's
    // own section (fix/attendance-correction-on-attendance-page) now ALSO renders a "รีเฟรช"
    // button lower on the page (it refetches the correction list, not the daily table), so an
    // unscoped query is ambiguous. Same disambiguation PageHeader.jsx's own comment cites
    // e2e/hr.spec.js already using `.page-actions` for -- an identically-named EmptyState action.
    const pageActions = document.querySelector('.page-actions');
    const refreshButton = await within(pageActions).findByRole('button', { name: /รีเฟรช/ });
    // Wait for the FIRST fetch to actually resolve (the button is disabled while `isLoading`,
    // issue #422 P2 fix) before clicking, or the click is a no-op on a disabled button.
    await waitFor(() => expect(refreshButton.disabled).toBe(false));
    await waitFor(() => expect(api.attendance.daily).toHaveBeenCalledTimes(1));

    fireEvent.click(refreshButton);

    await waitFor(() => expect(api.attendance.daily).toHaveBeenCalledTimes(2));
  });

  // BLOCKING 1 (P0, adversarial review): a query-error toast effect that puts an unstable
  // `showToast` in its dependency array re-fires every time `showToast` is called -- calling it
  // re-renders whatever owns the toast state, which hands back a NEW `showToast` identity, which
  // re-fires the effect for as long as the query's error stays non-null. Every other test in this
  // file (and the one this replaces before the fix) missed this because `vi.fn()` never changes
  // identity; `AppShapedHarness` above reproduces App.jsx's actual (unstable) wiring.
  //
  // Counting actual `showToast` invocations (via `onShowToast`) rather than asserting "render()
  // doesn't throw or hang": empirically, the pre-fix code neither throws nor settles cleanly here
  // -- React/RTL's `act` keeps flushing the cascade for as long as anything gives it more real
  // wall-clock time (confirmed: adding an extra `await new Promise(setTimeout, 50)` after the
  // initial render made this exact test HANG rather than fail, which is worse for CI than a red
  // test). `onShowToast` throwing once it is called more than once is the trip wire: it aborts
  // the cascade deterministically and quickly (within the SAME flush `findByRole` below already
  // triggers -- instrumenting the pre-fix effect directly showed 19 calls within that single
  // wait, no extra delay needed) instead of letting it run long enough to hang the test process.
  it('does not call showToast more than once when api.attendance.daily fails, driven by an App-shaped (unstable) showToast', async () => {
    api.attendance.daily.mockRejectedValue(new Error('เครือข่ายขัดข้อง'));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const showToastCalls = [];

    render(
      <QueryClientProvider client={queryClient}>
        <AppShapedHarness
          onShowToast={(kind, message) => {
            showToastCalls.push({ kind, message });
            if (showToastCalls.length > 1) {
              throw new Error(`showToast called ${showToastCalls.length} times -- infinite render loop`);
            }
          }}
        >
          {(showToast) => <AttendancePage user={hrUser} showToast={showToast} />}
        </AppShapedHarness>
      </QueryClientProvider>,
    );

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('เครือข่ายขัดข้อง');
    expect(showToastCalls).toHaveLength(1);
  });
});

// Regression coverage for the date stepper bug: `stepDay` used to round-trip through
// `new Date(...).toISOString()`, which reads the UTC calendar day off a Bangkok-midnight instant
// -- always one day behind the Bangkok date intended, netting a 2-day back-step and a stuck
// forward-step. System time is pinned mid-month (not near either window edge) so neither button
// is disabled by the min/max clamp, isolating the stepping arithmetic itself.
describe('AttendancePage date stepper', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.attendance.daily.mockResolvedValue({ days: [] });
    api.attendance.employees.mockResolvedValue({ employees: [] });
    api.attendance.devices.mockResolvedValue({ devices: [] });
    api.attendance.unmapped.mockResolvedValue({ badges: [] });
    api.attendanceCorrection.list.mockResolvedValue({ requests: [] });
  });

  it('previous then next round-trips to the original date, one calendar day at a time', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    try {
      vi.setSystemTime(new Date('2026-08-15T05:00:00Z')); // 2026-08-15 12:00 Bangkok
      renderWithClient(<AttendancePage user={hrUser} showToast={vi.fn()} />);

      const dateInput = await screen.findByLabelText('วันที่');
      await waitFor(() => expect(dateInput.value).toBe('2026-08-15'));

      const prevButton = screen.getByRole('button', { name: 'วันก่อนหน้า' });
      const nextButton = screen.getByRole('button', { name: 'วันถัดไป' });
      // Both buttons are disabled while the scoped daily-attendance query for the CURRENT
      // selectedDate is loading (issue #422 P2) -- wait for that to clear before every click, or
      // a click on a still-disabled button is a silent no-op that this test would misread as the
      // stepper doing nothing.
      await waitFor(() => expect(prevButton.disabled).toBe(false));

      fireEvent.click(prevButton);
      await waitFor(() => expect(dateInput.value).toBe('2026-08-14'));
      await waitFor(() => expect(nextButton.disabled).toBe(false));

      fireEvent.click(nextButton);
      await waitFor(() => expect(dateInput.value).toBe('2026-08-15'));
    } finally {
      vi.useRealTimers();
    }
  });
});

// Regression coverage: monthBounds() widened the picker's browsable window to 3 months back
// (BROWSABLE_MONTHS_BACK), but the self-view fetch range must NOT widen along with it -- it has
// no date control to reach further back with (the whole filter bar is `!isSelfView`-gated), and
// AttendanceDailyService.MAX_RANGE_DAYS caps any query at <92 days. A 3-month-back `from` combined
// with a late-month `today` (e.g. Aug 30 -> 122-day span) would exceed that and 400. This pins the
// self-view range to the CURRENT month regardless of BROWSABLE_MONTHS_BACK, decoupled from the
// picker's own min bound.
describe('AttendancePage self-view fetch range', () => {
  it("does not widen the self-view's own attendance query past the current month", async () => {
    vi.clearAllMocks();
    api.attendance.daily.mockResolvedValue({ days: [] });
    api.attendance.employees.mockResolvedValue({ employees: [] });
    api.attendance.devices.mockResolvedValue({ devices: [] });
    api.attendance.unmapped.mockResolvedValue({ badges: [] });
    api.attendanceCorrection.list.mockResolvedValue({ requests: [] });

    vi.useFakeTimers({ toFake: ['Date'] });
    try {
      // Late in the month -- if the self-view range had inherited the 3-month-back picker window,
      // `from` would be 2026-05-01 and the span would be 96 days, over the backend's 92-day cap.
      vi.setSystemTime(new Date('2026-08-30T05:00:00Z')); // 2026-08-30 12:00 Bangkok
      renderWithClient(<AttendancePage user={selfViewUser} showToast={vi.fn()} />);

      await waitFor(() => expect(api.attendance.daily).toHaveBeenCalledTimes(1));
      expect(api.attendance.daily).toHaveBeenCalledWith(
        expect.objectContaining({ from: '2026-08-01', to: '2026-08-30' }),
      );
    } finally {
      vi.useRealTimers();
    }
  });
});

// fix/attendance-correction-on-attendance-page: "ขอแก้ไขเวลา" (open AttendanceCorrectionRequestModal)
// and the AttendanceCorrectionPanel review-queue section both moved onto this page from the old
// third tab on RequestsPage (/employee-requests) -- see that file's own header comment.
describe('AttendancePage attendance-correction button and section', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.attendance.daily.mockResolvedValue({ days: [] });
    api.attendance.employees.mockResolvedValue({ employees: [] });
    api.attendance.devices.mockResolvedValue({ devices: [] });
    api.attendance.unmapped.mockResolvedValue({ badges: [] });
    api.attendanceCorrection.list.mockResolvedValue({ requests: [] });
  });

  it('renders "ขอแก้ไขเวลา" for an employee and opens the request modal on click', async () => {
    renderWithClient(<AttendancePage user={selfViewUser} showToast={vi.fn()} />);

    const openButton = await screen.findByRole('button', { name: 'ขอแก้ไขเวลา' });
    fireEvent.click(openButton);

    // The modal is up once its own field is reachable by label -- this also proves the button
    // opened AttendanceCorrectionRequestModal specifically, not some other dialog.
    expect(await screen.findByLabelText(/วันที่ที่ต้องการแก้ไข/)).not.toBeNull();
  });

  it('does not render "ขอแก้ไขเวลา" for a CEO', async () => {
    renderWithClient(<AttendancePage user={ceoUser} showToast={vi.fn()} />);

    // Wait for the page to actually settle before asserting an absence, or a false negative
    // (button not rendered YET vs. never rendered) would pass for the wrong reason. Not a
    // `findByRole('button', { name: /รีเฟรช/ })` here -- there are now two (this page's own, and
    // AttendanceCorrectionPanel's), which is ambiguous; the daily-attendance fetch is a settle
    // signal that stays unambiguous regardless of how many รีเฟรช buttons the page has.
    await waitFor(() => expect(api.attendance.daily).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole('button', { name: 'ขอแก้ไขเวลา' })).toBeNull();
  });

  it('renders the attendance-correction review section below the daily table', async () => {
    renderWithClient(<AttendancePage user={selfViewUser} showToast={vi.fn()} />);

    // Substring, not an exact name: the section is collapsible now, and CollapsibleSection puts
    // the title AND the subtitle inside the disclosure button that its <h2> wraps (ARIA APG
    // accordion pattern), so the heading's accessible name is "<title> <subtitle>". That is the
    // right announcement — a screen-reader user gets the context with the heading — but it means
    // an exact match no longer identifies it.
    expect(await screen.findByRole('heading', { name: /คำขอแก้ไขเวลาเข้า-ออกงาน/ })).not.toBeNull();
  });

  // F3 (adversarial review): neither gate's `employeeId` conjunct had a fixture that could ever
  // fail it -- every existing fixture is either CEO or already carries an employeeId. A session
  // with neither should get neither the submit button (nothing to attach the request to) nor the
  // review section (no history of its own, and canViewAll is role-only so this session gains
  // nothing from viewing it either).
  it('renders neither "ขอแก้ไขเวลา" nor the review section for a session with no employeeId', async () => {
    renderWithClient(<AttendancePage user={noEmployeeIdUser} showToast={vi.fn()} />);

    // Same settle signal as the CEO case above -- unambiguous regardless of how many รีเฟรช
    // buttons the page ends up with.
    await waitFor(() => expect(api.attendance.daily).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole('button', { name: 'ขอแก้ไขเวลา' })).toBeNull();
    expect(screen.queryByRole('heading', { name: /คำขอแก้ไขเวลาเข้า-ออกงาน/ })).toBeNull();
  });
});

// สรุปรายเดือน (monthly attendance summary export): the button's HR-vs-self-view gate, the modal's
// month + read-only filter recap, and the download call/failure path. Mocks the api module per this
// file's own convention -- no assertion on real blob bytes anywhere below.
describe('AttendancePage monthly summary export', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.attendance.daily.mockResolvedValue({ days: [] });
    api.attendance.employees.mockResolvedValue({ employees: [] });
    api.attendance.devices.mockResolvedValue({ devices: [] });
    api.attendance.unmapped.mockResolvedValue({ badges: [] });
    api.attendanceCorrection.list.mockResolvedValue({ requests: [] });
    // jsdom has no real Blob-URL machinery -- same stub PayrollPage.test.jsx uses for its own
    // downloadBlob-driven downloads, needed here so a SUCCESSFUL monthlySummary() resolution can
    // reach downloadBlob() without throwing and masking the assertion as an unrelated error toast.
    URL.createObjectURL = vi.fn(() => 'blob:attendance-summary');
    URL.revokeObjectURL = vi.fn();
  });

  it('renders "สรุปรายเดือน" for HR', async () => {
    renderWithClient(<AttendancePage user={hrUser} showToast={vi.fn()} />);

    expect(await screen.findByRole('button', { name: 'สรุปรายเดือน' })).not.toBeNull();
  });

  it('does not render "สรุปรายเดือน" in self-view', async () => {
    renderWithClient(<AttendancePage user={selfViewUser} showToast={vi.fn()} />);

    // Same settle signal the correction-button tests above use -- unambiguous regardless of how
    // many รีเฟรช buttons the page ends up with.
    await waitFor(() => expect(api.attendance.daily).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole('button', { name: 'สรุปรายเดือน' })).toBeNull();
  });

  it("opening the modal and confirming calls api.attendance.monthlySummary with the month and the page's current ฝ่าย/พนักงาน filter", async () => {
    api.attendance.employees.mockResolvedValue({
      employees: [
        {
          employee_id: 10, employee_code: 'E010', employee_name: 'พนักงาน เอ',
          nick_name: 'เอ', department_name: 'ขาย', division_id: 1, division_name: 'ฝ่ายขาย',
        },
        {
          employee_id: 11, employee_code: 'E011', employee_name: 'พนักงาน บี',
          nick_name: 'บี', department_name: 'ขาย', division_id: 1, division_name: 'ฝ่ายขาย',
        },
      ],
    });
    api.attendance.monthlySummary.mockResolvedValue(new Blob(['xlsx'], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    }));

    vi.useFakeTimers({ toFake: ['Date'] });
    try {
      vi.setSystemTime(new Date('2026-08-15T05:00:00Z')); // 2026-08-15 12:00 Bangkok
      renderWithClient(<AttendancePage user={hrUser} showToast={vi.fn()} />);

      // Pick a specific employee from the page's OWN filter bar BEFORE opening the modal, so the
      // download call is proven to carry the SAME filter this picker set, not a value the modal
      // invented independently.
      const employeeSelect = await screen.findByLabelText('พนักงาน');
      fireEvent.change(employeeSelect, { target: { value: '10' } });

      fireEvent.click(screen.getByRole('button', { name: 'สรุปรายเดือน' }));

      const monthInput = await screen.findByLabelText('เดือน');
      // Defaults to the CURRENT month (2026-08-15's month), not selectedDate/monthStart -- see the
      // modal's own comment for why it must not inherit the day-view stepper's clamp.
      expect(monthInput.value).toBe('2026-08');

      fireEvent.click(screen.getByRole('button', { name: /ดาวน์โหลด/ }));

      await waitFor(() => expect(api.attendance.monthlySummary).toHaveBeenCalledTimes(1));
      expect(api.attendance.monthlySummary).toHaveBeenCalledWith({ month: '2026-08', employeeId: '10' });
    } finally {
      vi.useRealTimers();
    }
  });

  it('a failed download surfaces a toast rather than throwing', async () => {
    api.attendance.monthlySummary.mockRejectedValue(new Error('เครือข่ายขัดข้อง'));
    const showToast = vi.fn();

    renderWithClient(<AttendancePage user={hrUser} showToast={showToast} />);

    fireEvent.click(await screen.findByRole('button', { name: 'สรุปรายเดือน' }));
    fireEvent.click(await screen.findByRole('button', { name: /ดาวน์โหลด/ }));

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', 'เครือข่ายขัดข้อง'));
  });
});
