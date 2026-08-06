import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import {
  fireEvent, render, screen, waitFor, within,
} from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TeamLeaveTab } from './TeamLeaveTab.jsx';
import { api } from '../../api/index.js';
import { downloadBlob } from '../../utils/download.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    leave: {
      employees: vi.fn(),
      list: vi.fn(),
      cancel: vi.fn(),
      downloadAttachment: vi.fn(),
    },
  },
}));

vi.mock('../../utils/download.js', () => ({
  downloadBlob: vi.fn(),
}));

// Tab visibility itself ("ลูกทีม" shows only for an actor with a direct report) is covered by
// leaveSurfaceTabs.test.js (the pure `isVisible`/`visibleLeaveSurfaceTabIds` rule) and
// LeaveSurfacePage.test.jsx (the rendered tab list) -- same split ReviewQueueTab's own "รอพิจารณา"
// visibility uses (ReviewQueueTab.test.jsx never tests its own tab's visibility either). This file
// covers only what TeamLeaveTab.jsx itself renders once mounted.

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

describe('TeamLeaveTab', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.leave.employees.mockResolvedValue({ employees: teamEmployeeOptions });
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

  it('the "ทุกคน" employee filter renders when the actor has more than one employee option, and narrows the query', async () => {
    api.leave.list.mockResolvedValue({
      requests: [ownRequestUnderManager, directReportRequest, otherDirectReportRequest],
    });
    renderTeamLeaveTab();

    await screen.findByText('ลาของหัวหน้างานเอง');
    const employeeSelect = screen.getByLabelText('พนักงาน');
    expect(within(employeeSelect).getByText('ทุกคน')).not.toBeNull();

    api.leave.list.mockResolvedValue({ requests: [directReportRequest] });
    fireEvent.change(employeeSelect, { target: { value: '6' } });
    fireEvent.click(screen.getByRole('button', { name: /ค้นหา/ }));

    await waitFor(() => expect(api.leave.list).toHaveBeenLastCalledWith(expect.objectContaining({ employeeId: '6' })));
  });

  it('the employee filter is absent when the actor has only themselves in api.leave.employees()', async () => {
    api.leave.employees.mockResolvedValue({
      employees: [{
        employeeId: 5, employeeName: 'หัวหน้างาน', employeeCode: 'GLR-005', self: true, directReport: false,
      }],
    });
    api.leave.list.mockResolvedValue({ requests: [ownRequestUnderManager] });
    renderTeamLeaveTab();

    await screen.findByText('ลาของหัวหน้างานเอง');
    expect(screen.queryByLabelText('พนักงาน')).toBeNull();
  });

  it('the team calendar ("ปฏิทินวันลา") lists SUBMITTED/APPROVED requests across the team, not just the actor\'s own', async () => {
    api.leave.list.mockResolvedValue({
      requests: [ownRequestUnderManager, directReportRequest],
    });
    renderTeamLeaveTab();

    const calendarHeading = await screen.findByRole('heading', { name: 'ปฏิทินวันลา' });
    // The heading's own immediate ancestor is only PanelHeader's wrapper `<div>` (the header
    // row) -- the calendar-list content is a SIBLING of that div, both inside the Panel's
    // `<section>`. Must walk up to `section` specifically, not the nearer `div`.
    const calendarPanel = calendarHeading.closest('section');
    await waitFor(() => expect(calendarPanel.textContent).toMatch(/ลูกทีม หนึ่ง/));
    expect(calendarPanel.textContent).toMatch(/หัวหน้างาน/);
  });

  it('a zero-row result shows team-oriented empty copy, not the "ของฉัน" personal teaching copy', async () => {
    api.leave.list.mockResolvedValue({ requests: [] });
    renderTeamLeaveTab();

    expect(await screen.findByText('ยังไม่มีคำขอลาของทีม')).not.toBeNull();
    expect(screen.queryByText('ยังไม่มีคำขอลา')).toBeNull();
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
    // Exactly one cancel action on screen (the actor's own SUBMITTED row) even though a second
    // SUBMITTED row (the direct report's) is also visible in the table.
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

  it('downloads a direct report\'s attachment via GET /api/leave/attachments/{id}, same as the reviewer path', async () => {
    const requestWithCertificate = {
      ...directReportRequest, attachmentId: 9101, attachmentFileName: 'ใบรับรองแพทย์.pdf',
    };
    api.leave.list.mockResolvedValue({ requests: [requestWithCertificate] });
    const blob = new Blob(['fake-pdf']);
    api.leave.downloadAttachment.mockResolvedValue(blob);
    renderTeamLeaveTab();

    fireEvent.click(await screen.findByRole('button', { name: /ดูรายละเอียด/ }));
    fireEvent.click(await screen.findByRole('button', { name: 'ดาวน์โหลด' }));

    await waitFor(() => expect(api.leave.downloadAttachment).toHaveBeenCalledWith(9101));
    await waitFor(() => expect(downloadBlob).toHaveBeenCalledWith(blob, 'leave-attachment-902', 'pdf'));
  });

  it('a 403 from the list endpoint renders the denied StatePanel', async () => {
    api.leave.list.mockRejectedValue(Object.assign(new Error('ไม่มีสิทธิ์เข้าถึงรายการนี้'), { status: 403 }));
    renderTeamLeaveTab();

    expect(await screen.findByText('ยังเปิดหน้านี้ไม่ได้')).not.toBeNull();
  });

  // Review fix (2026-08): api.leave.employees() genuinely returns the whole company for hr/ceo
  // (VIEW_ALL_ROLES), so this tab stays visible for them -- but its manager-flavoured copy
  // ("ลูกทีม" / "...ที่รายงานตรงต่อคุณ") is false when the viewer is hr/ceo looking at every
  // employee, not a set of direct reports. hasPermission(role, 'canViewAllLeave') is the same
  // check ROLE_PERMISSIONS.canViewAllLeave (['hr','ceo']) exposes elsewhere in this app.
  describe('role-aware copy for hr/ceo (canViewAllLeave) vs a real division manager', () => {
    const hr = { employeeId: 99, name: 'ฝ่ายบุคคล', role: 'hr' };

    it('Panel title reads "คำขอลาพนักงานทั้งหมด" for hr, not "คำขอลาของทีม"', async () => {
      api.leave.list.mockResolvedValue({ requests: [ownRequestUnderManager] });
      renderTeamLeaveTab(hr);

      expect(await screen.findByRole('heading', { name: 'คำขอลาพนักงานทั้งหมด' })).not.toBeNull();
      expect(screen.queryByRole('heading', { name: 'คำขอลาของทีม' })).toBeNull();
    });

    it('empty-state copy for hr drops the "...ที่รายงานตรงต่อคุณ" clause', async () => {
      api.leave.list.mockResolvedValue({ requests: [] });
      renderTeamLeaveTab(hr);

      expect(await screen.findByText('ยังไม่มีคำขอลาของพนักงาน')).not.toBeNull();
      expect(screen.getByText('คำขอลาของพนักงานทุกคนจะแสดงที่นี่เมื่อมีการยื่น')).not.toBeNull();
      expect(screen.queryByText(/ที่รายงานตรงต่อคุณ/)).toBeNull();
    });

    it('a real division manager (non-hr/ceo) keeps the original "คำขอลาของทีม" copy unchanged', async () => {
      api.leave.list.mockResolvedValue({ requests: [ownRequestUnderManager] });
      renderTeamLeaveTab(manager);

      expect(await screen.findByRole('heading', { name: 'คำขอลาของทีม' })).not.toBeNull();
      expect(screen.queryByRole('heading', { name: 'คำขอลาพนักงานทั้งหมด' })).toBeNull();
    });

    it('a real division manager still sees the original empty-state copy, including "...ที่รายงานตรงต่อคุณ"', async () => {
      api.leave.list.mockResolvedValue({ requests: [] });
      renderTeamLeaveTab(manager);

      expect(await screen.findByText('ยังไม่มีคำขอลาของทีม')).not.toBeNull();
      expect(screen.getByText('คำขอลาของคุณและลูกทีมที่รายงานตรงต่อคุณจะแสดงที่นี่เมื่อมีการยื่น')).not.toBeNull();
    });
  });
});
