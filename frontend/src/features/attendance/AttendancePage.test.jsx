import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AttendancePage, PunchDetail } from './AttendancePage.jsx';
import { api } from '../../api/index.js';

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
    // AttendanceCorrectionPanel.jsx now renders on this page for anyone CEO or with an employeeId -- both hrUser
    // and selfViewUser below qualify, so every existing test in this file mounts it too and needs this namespace
    // mocked or it throws on mount, not just the tests that exercise it directly.
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
// Not HR/CEO (no canViewAllAttendance) and not a manager -- attendanceMode() falls through to 'employee', i.e.
// isSelfView.
const selfViewUser = { role: 'employee', employeeId: 2, manager: false };
const ceoUser = { role: 'ceo', employeeId: 3 };

function renderWithClient(ui) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>);
}

describe('PunchDetail — first punch is clock-in, last is clock-out', () => {
  // Punches arrive oldest-first (the AttendanceRepository.findPunches contract), so the earliest time must
  // render with เข้า and the latest with ออก regardless of how many scans there are.
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

// fix/attendance-correction-on-attendance-page: "ขอแก้ไขเวลา" (open AttendanceCorrectionRequestModal) and the
// AttendanceCorrectionPanel review-queue section both moved onto this page from the old third tab on
// RequestsPage (/employee-requests) -- see that file's own header comment.
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

    // The modal is up once its own field is reachable by label -- this also proves the button opened
    // AttendanceCorrectionRequestModal specifically, not some other dialog.
    expect(await screen.findByLabelText(/วันที่ที่ต้องการแก้ไข/)).not.toBeNull();
  });

  it('does not render "ขอแก้ไขเวลา" for a CEO', async () => {
    renderWithClient(<AttendancePage user={ceoUser} showToast={vi.fn()} />);

    // Wait for the page to actually settle before asserting an absence, or a false negative (button not rendered
    // YET vs. never rendered) would pass for the wrong reason.
    await waitFor(() => expect(api.attendance.daily).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole('button', { name: 'ขอแก้ไขเวลา' })).toBeNull();
  });
});

// สรุปรายเดือน (monthly attendance summary export): the button's HR-vs-self-view gate. Mocks the api module
// per this file's own convention.
describe('AttendancePage monthly summary export', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.attendance.daily.mockResolvedValue({ days: [] });
    api.attendance.employees.mockResolvedValue({ employees: [] });
    api.attendance.devices.mockResolvedValue({ devices: [] });
    api.attendance.unmapped.mockResolvedValue({ badges: [] });
    api.attendanceCorrection.list.mockResolvedValue({ requests: [] });
    // jsdom has no real Blob-URL machinery -- same stub PayrollPage.test.jsx uses for its own downloadBlob-driven
    // downloads, needed here so a SUCCESSFUL monthlySummary() resolution can reach downloadBlob() without throwing
    // and masking the assertion as an unrelated error toast.
    URL.createObjectURL = vi.fn(() => 'blob:attendance-summary');
    URL.revokeObjectURL = vi.fn();
  });

  it('renders "สรุปรายเดือน" for HR', async () => {
    renderWithClient(<AttendancePage user={hrUser} showToast={vi.fn()} />);

    expect(await screen.findByRole('button', { name: 'สรุปรายเดือน' })).not.toBeNull();
  });

  it('does not render "สรุปรายเดือน" in self-view', async () => {
    renderWithClient(<AttendancePage user={selfViewUser} showToast={vi.fn()} />);

    // Same settle signal the correction-button tests above use -- unambiguous regardless of how many รีเฟรช
    // buttons the page ends up with.
    await waitFor(() => expect(api.attendance.daily).toHaveBeenCalledTimes(1));
    expect(screen.queryByRole('button', { name: 'สรุปรายเดือน' })).toBeNull();
  });
});
