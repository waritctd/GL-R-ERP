import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import {
  fireEvent, render, screen, waitFor,
} from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import {
  afterEach, beforeEach, describe, expect, it, vi,
} from 'vitest';
import { TeamLeaveTab } from './TeamLeaveTab.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    leave: {
      employees: vi.fn(),
      list: vi.fn(),
      cancel: vi.fn(),
      downloadAttachment: vi.fn(),
      teamBalances: vi.fn(),
    },
  },
}));

vi.mock('../../utils/download.js', () => ({
  downloadBlob: vi.fn(),
}));

// Tab visibility itself is covered by leaveSurfaceTabs.test.js and LeaveSurfacePage.test.jsx -- same split
// ReviewQueueTab's own "รอพิจารณา" visibility uses. This file covers only what TeamLeaveTab.jsx itself renders
// once mounted.

const manager = { employeeId: 5, name: 'หัวหน้างาน', role: 'employee', manager: true };

const ownRequestUnderManager = {
  id: 901,
  employeeId: 5,
  employeeName: 'หัวหน้างาน',
  employeeCode: 'GLR-005',
  managerEmployeeId: null,
  leaveTypeCode: 'VACATION',
  leaveTypeNameTh: 'ลาพักร้อน',
  startDate: '2026-08-01',
  endDate: '2026-08-01',
  totalDays: 1,
  quotaRemainingAfter: 5,
  status: 'APPROVED',
  reason: 'ลาของหัวหน้างานเอง',
};

const directReportRequest = {
  id: 902,
  employeeId: 6,
  employeeName: 'ลูกทีม หนึ่ง',
  employeeCode: 'GLR-006',
  managerEmployeeId: 5,
  leaveTypeCode: 'SICK',
  leaveTypeNameTh: 'ลาป่วย',
  startDate: '2026-08-02',
  endDate: '2026-08-02',
  totalDays: 1,
  quotaRemainingAfter: 29,
  status: 'SUBMITTED',
  reason: 'ลาป่วยของลูกทีม',
};

const otherDirectReportRequest = {
  id: 903,
  employeeId: 7,
  employeeName: 'ลูกทีม สอง',
  employeeCode: 'GLR-007',
  managerEmployeeId: 5,
  leaveTypeCode: 'PERSONAL',
  leaveTypeNameTh: 'ลากิจ',
  startDate: '2026-08-03',
  endDate: '2026-08-03',
  totalDays: 1,
  quotaRemainingAfter: 2,
  status: 'SUBMITTED',
  reason: 'ธุระของลูกทีมอีกคน',
};

const teamEmployeeOptions = [
  {
    employeeId: 5, employeeName: 'หัวหน้างาน', employeeCode: 'GLR-005', self: true, directReport: false,
  },
  {
    employeeId: 6, employeeName: 'ลูกทีม หนึ่ง', employeeCode: 'GLR-006', self: false, directReport: true,
  },
  {
    employeeId: 7, employeeName: 'ลูกทีม สอง', employeeCode: 'GLR-007', self: false, directReport: true,
  },
];

function renderTeamLeaveTab(user = manager, showToast = vi.fn()) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <TeamLeaveTab user={user} showToast={showToast} />
      </QueryClientProvider>
    </MemoryRouter>,
  );
  return queryClient;
}

// DataTable only renders `mobileCard` when useIsMobile() is true, and that hook reads window.matchMedia --
// which jsdom does not implement, so it returns false and the DESKTOP table renders instead. Observed exactly
// that before this cleanup existed.
const originalMatchMedia = window.matchMedia;

describe('TeamLeaveTab', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.leave.employees.mockResolvedValue({ employees: teamEmployeeOptions });
    api.leave.teamBalances.mockResolvedValue({ team: [] });
  });

  afterEach(() => {
    if (originalMatchMedia) window.matchMedia = originalMatchMedia;
    else delete window.matchMedia;
  });

  it('shows both the actor\'s own request AND their direct reports\' requests -- this is the "ลูกทีม" table, unlike "ของฉัน"', async () => {
    api.leave.list.mockResolvedValue({
      requests: [ownRequestUnderManager, directReportRequest, otherDirectReportRequest],
    });
    renderTeamLeaveTab();

    expect(await screen.findByText('ลาของหัวหน้างานเอง')).not.toBeNull();
    expect(await screen.findByText('ลาป่วยของลูกทีม')).not.toBeNull();
    expect(await screen.findByText('ธุระของลูกทีมอีกคน')).not.toBeNull();
  });

  it('only the actor\'s OWN row within the team table offers cancel -- a direct report\'s row does not', async () => {
    api.leave.list.mockResolvedValue({
      requests: [
        { ...ownRequestUnderManager, status: 'SUBMITTED' },
        directReportRequest,
      ],
    });
    renderTeamLeaveTab();

    await screen.findByText('ลาของหัวหน้างานเอง');
    // Exactly one cancel action on screen (the actor's own SUBMITTED row) even though a second SUBMITTED row (the
    // direct report's) is also visible in the table.
    expect(screen.getAllByRole('button', { name: 'ยกเลิก' })).toHaveLength(1);
  });

  it('cancelling the actor\'s own row calls api.leave.cancel with that row\'s id', async () => {
    api.leave.list.mockResolvedValue({ requests: [{ ...ownRequestUnderManager, status: 'SUBMITTED' }] });
    api.leave.cancel.mockResolvedValue({ request: { ...ownRequestUnderManager, status: 'CANCELLED' } });
    renderTeamLeaveTab();

    await screen.findByText('ลาของหัวหน้างานเอง');
    fireEvent.click(screen.getByRole('button', { name: 'ยกเลิก' }));
    fireEvent.click(await screen.findByRole('button', { name: 'ยกเลิกคำขอ' }));

    await waitFor(() => expect(api.leave.cancel).toHaveBeenCalledWith(901, { reviewerNote: null }));
  });
});
