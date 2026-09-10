import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import {
  fireEvent, render, screen, waitFor, within,
} from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import {
  afterEach, beforeEach, describe, expect, it, vi,
} from 'vitest';
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
      teamBalances: vi.fn(),
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

// DataTable only renders `mobileCard` when useIsMobile() is true, and that hook reads
// window.matchMedia -- which jsdom does not implement, so it returns false and the DESKTOP table
// renders instead. A "mobile card" assertion written without this stub therefore passes against
// leaveRequestTable.jsx's shared desktop status column (which has carried the unpaid badge since
// before this branch) and proves NOTHING about the card. Verified by mutation: with the gate in
// mobileCard forced permanently true, an unstubbed test stayed green.
// Restored in afterEach: assigning window.matchMedia is a GLOBAL mutation that otherwise leaks
// into every later test in this file, silently flipping them to the mobile card and breaking
// assertions that look for desktop-table controls. Observed exactly that before this cleanup existed.
const originalMatchMedia = window.matchMedia;

function stubMobileViewport() {
  const listeners = new Set();
  window.matchMedia = (query) => ({
    matches: true,
    media: query,
    addEventListener: (_e, cb) => listeners.add(cb),
    removeEventListener: (_e, cb) => listeners.delete(cb),
    addListener: (cb) => listeners.add(cb),
    removeListener: (cb) => listeners.delete(cb),
    dispatchEvent: () => false,
    onchange: null,
  });
}

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

  // V164 follow-up: this tab's DESKTOP table already showed the unpaid badge (it uses
  // leaveRequestTable.jsx's shared status column), while its own mobileCard did not -- so a manager
  // scanning their team on a phone saw no unpaid signal at all for rows their desktop flagged.
  // Both badges render in the DOM here regardless of viewport; jsdom cannot evaluate the
  // `flex-col` that stacks them, so this asserts PRESENCE only, never visual placement.
  it("the MOBILE CARD shows the ไม่รับค่าจ้าง badge beside its status badge", async () => {
    stubMobileViewport();
    api.leave.list.mockResolvedValue({
      requests: [{ ...directReportRequest, unpaidDays: 2, paidDays: 0 }],
    });
    renderTeamLeaveTab();

    expect(await screen.findByText('ลาป่วยของลูกทีม')).not.toBeNull();
    expect(await screen.findByText(/ไม่รับค่าจ้าง/)).not.toBeNull();
  });

  it("the MOBILE CARD's badge is GATED on unpaidDays -- a paid row shows none", async () => {
    stubMobileViewport();
    api.leave.list.mockResolvedValue({ requests: [directReportRequest] });
    renderTeamLeaveTab();

    expect(await screen.findByText('ลาป่วยของลูกทีม')).not.toBeNull();
    expect(screen.queryByText(/ไม่รับค่าจ้าง/)).toBeNull();
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

  // Renamed from "ปฏิทินวันลา" and re-scoped to a forward window by the 2026-08-10 IA restructure
  // -- see UpcomingLeaveList.jsx (defect D1). Unlike the old test, the fixtures here are dated
  // AFTER today and the mock honours the requested window, so the panel's forward query is
  // actually exercised rather than being handed rows it would never have received.
  it('"วันลาที่กำลังจะถึงของทีม" lists upcoming SUBMITTED/APPROVED requests across the team, not just the actor\'s own', async () => {
    const daysFromToday = (offset) => {
      const date = new Date();
      date.setDate(date.getDate() + offset);
      return date.toISOString().slice(0, 10);
    };
    const upcomingOwn = { ...ownRequestUnderManager, startDate: daysFromToday(10), endDate: daysFromToday(10) };
    const upcomingReport = { ...directReportRequest, startDate: daysFromToday(11), endDate: daysFromToday(11) };
    api.leave.list.mockImplementation((params = {}) => Promise.resolve({
      requests: [upcomingOwn, upcomingReport].filter((request) => {
        if (params.from && request.endDate < params.from) return false;
        if (params.to && request.startDate > params.to) return false;
        return true;
      }),
    }));
    renderTeamLeaveTab();

    const calendarHeading = await screen.findByRole('heading', { name: 'วันลาที่กำลังจะถึงของทีม' });
    // The heading's own immediate ancestor is only PanelHeader's wrapper `<div>` (the header
    // row) -- the list content is a SIBLING of that div, both inside the Panel's `<section>`.
    // Must walk up to `section` specifically, not the nearer `div`.
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

    it('Panel title reads "ประวัติการลาพนักงานทั้งหมด" for hr, not "ประวัติการลาของทีม"', async () => {
      api.leave.list.mockResolvedValue({ requests: [ownRequestUnderManager] });
      renderTeamLeaveTab(hr);

      expect(await screen.findByRole('heading', { name: 'ประวัติการลาพนักงานทั้งหมด' })).not.toBeNull();
      expect(screen.queryByRole('heading', { name: 'ประวัติการลาของทีม' })).toBeNull();
    });

    it('empty-state copy for hr drops the "...ที่รายงานตรงต่อคุณ" clause', async () => {
      api.leave.list.mockResolvedValue({ requests: [] });
      renderTeamLeaveTab(hr);

      expect(await screen.findByText('ยังไม่มีคำขอลาของพนักงาน')).not.toBeNull();
      expect(screen.getByText('คำขอลาของพนักงานทุกคนจะแสดงที่นี่เมื่อมีการยื่น')).not.toBeNull();
      expect(screen.queryByText(/ที่รายงานตรงต่อคุณ/)).toBeNull();
    });

    it('a real division manager (non-hr/ceo) keeps the team-scoped "ประวัติการลาของทีม" copy', async () => {
      api.leave.list.mockResolvedValue({ requests: [ownRequestUnderManager] });
      renderTeamLeaveTab(manager);

      expect(await screen.findByRole('heading', { name: 'ประวัติการลาของทีม' })).not.toBeNull();
      expect(screen.queryByRole('heading', { name: 'ประวัติการลาพนักงานทั้งหมด' })).toBeNull();
    });

    it('a real division manager still sees the original empty-state copy, including "...ที่รายงานตรงต่อคุณ"', async () => {
      api.leave.list.mockResolvedValue({ requests: [] });
      renderTeamLeaveTab(manager);

      expect(await screen.findByText('ยังไม่มีคำขอลาของทีม')).not.toBeNull();
      expect(screen.getByText('คำขอลาของคุณและลูกทีมที่รายงานตรงต่อคุณจะแสดงที่นี่เมื่อมีการยื่น')).not.toBeNull();
    });
  });

  // Manager team-quota summary (2026-09): TeamQuotaSummary.jsx, reading GET
  // /api/leave/balances/team (LeaveService#teamBalances). Backend scope enforcement -- direct
  // reports only, never a colleague's -- is proven server-side by
  // LeaveTeamBalancesIntegrationTest against real Postgres (CLAUDE.md: never claim a permission
  // fact from mock-driven rendering). These tests cover only what this component RENDERS from
  // whatever api.leave.teamBalances() returns.
  describe('team quota summary panel', () => {
    const teamBalancesResponse = {
      team: [
        {
          employeeId: 6,
          employeeCode: 'GLR-006',
          employeeName: 'ลูกทีม หนึ่ง',
          departmentName: 'ฝ่ายขาย',
          balances: [
            {
              leaveTypeCode: 'SICK', leaveTypeNameTh: 'ลาป่วย', leaveTypeNameEn: 'Sick',
              annualQuotaDays: 30, approvedDays: 1, pendingDays: 0, remainingDays: 29,
              requiresAttachment: false, carriedInDays: 0, carriedInFromYear: null,
              carriedInExpiresOn: null, carriedInRemainingDays: 0, ownQuotaRemainingDays: 29,
            },
            {
              leaveTypeCode: 'VACATION', leaveTypeNameTh: 'ลาพักร้อน', leaveTypeNameEn: 'Vacation',
              annualQuotaDays: 6, approvedDays: 6, pendingDays: 0, remainingDays: 0,
              requiresAttachment: false, carriedInDays: 0, carriedInFromYear: null,
              carriedInExpiresOn: null, carriedInRemainingDays: 0, ownQuotaRemainingDays: 0,
            },
          ],
        },
      ],
    };

    it("renders each direct report's remaining-days chips, per leave type", async () => {
      api.leave.list.mockResolvedValue({ requests: [] });
      api.leave.teamBalances.mockResolvedValue(teamBalancesResponse);
      renderTeamLeaveTab();

      expect(await screen.findByText('ลูกทีม หนึ่ง')).not.toBeNull();
      expect(screen.getByText('GLR-006 · ฝ่ายขาย')).not.toBeNull();
      expect(screen.getByText('29 วัน')).not.toBeNull();
    });

    it('a leave type at zero remaining still renders (not hidden) -- the point is spotting exactly this', async () => {
      api.leave.list.mockResolvedValue({ requests: [] });
      api.leave.teamBalances.mockResolvedValue(teamBalancesResponse);
      renderTeamLeaveTab();

      await screen.findByText('ลูกทีม หนึ่ง');
      expect(screen.getByText('0 วัน')).not.toBeNull();
    });

    it('an empty team renders the "ยังไม่มีลูกทีม" empty state, not an error', async () => {
      api.leave.list.mockResolvedValue({ requests: [] });
      api.leave.teamBalances.mockResolvedValue({ team: [] });
      renderTeamLeaveTab();

      expect(await screen.findByText('ยังไม่มีลูกทีม')).not.toBeNull();
    });

    it('the quota-summary panel title follows the same hr/ceo-vs-manager split as the request table', async () => {
      const hr = { employeeId: 99, name: 'ฝ่ายบุคคล', role: 'hr' };
      api.leave.list.mockResolvedValue({ requests: [] });
      api.leave.teamBalances.mockResolvedValue({ team: [] });
      renderTeamLeaveTab(hr);

      expect(await screen.findByRole('heading', { name: 'โควตาวันลาคงเหลือของพนักงานทั้งหมด' })).not.toBeNull();
      expect(screen.queryByRole('heading', { name: 'โควตาวันลาคงเหลือของทีม' })).toBeNull();
    });
  });
});
