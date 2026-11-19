import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MyLeaveTab } from './MyLeaveTab.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    leave: {
      employees: vi.fn(),
      types: vi.fn(),
      list: vi.fn(),
      balances: vi.fn(),
      contactDefaults: vi.fn(),
      create: vi.fn(),
      cancel: vi.fn(),
      downloadAttachment: vi.fn(),
      // MyLeaveTab renders UpcomingHolidays unconditionally, so every describe block that renders it
      // needs a default resolved value or the panel's query errors.
      calendarContext: vi.fn(),
    },
  },
}));

vi.mock('../../utils/download.js', () => ({
  downloadBlob: vi.fn(),
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

const emptyContactDefaults = {
  contactDefaults: {
    employeeId: 1,
    positionTh: null,
    departmentTh: null,
    divisionTh: null,
    contactHouseNo: null,
    contactSubdistrict: null,
    contactDistrict: null,
    contactProvince: null,
    contactPhone: null,
  },
};

function renderMyLeaveTab(renderUser = user) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <MyLeaveTab user={renderUser} currentEmployee={currentEmployee} showToast={vi.fn()} />
      </QueryClientProvider>
    </MemoryRouter>,
  );
  return queryClient;
}

// The request-submission form lives in the /leave/new composer (LeaveRequestPage.test.jsx owns
// that coverage). MyLeaveTab is read-only: balances, request history, cancel.

describe('MyLeaveTab balances: one primary card + a single disclosure (owner feedback, 2026-08)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // The balance-preview select (Phase A2: decoupled from the removed submission form) seeds its default from
    // THIS list, not from the balances fixture -- every test below needs at least VACATION here so that seeding
    // effect has something to default to.
    api.leave.types.mockResolvedValue({ leaveTypes: [{ code: 'VACATION', nameTh: 'ลาพักร้อน', nameEn: 'Vacation' }] });
    api.leave.list.mockResolvedValue({ requests: [] });
    api.leave.contactDefaults.mockResolvedValue(emptyContactDefaults);
    // #leave-holiday-visibility: UpcomingHolidays renders unconditionally alongside "ปฏิทินวันลา" -- default to no
    // holidays so this describe block's own assertions stay uninvolved with it.
    api.leave.calendarContext.mockResolvedValue({ calendarContext: { holidays: [], nonWorkingDates: [] } });
  });

  it('DEFECT: the "โควตาคงเหลือ" stat sums ONLY the everyday three, never the rare types', async () => {
    // Regression: the stat once summed EVERY balance (7 + 30 + 3 + MATERNITY 98 + MILITARY 366 +
    // ORDINATION 60 = "โควตาคงเหลือ 564 วัน"). A bare `queryByText(/366/)` is null check
    // could not detect it (the buggy sum renders 372), so assert the STAT's own value.
    api.leave.balances.mockResolvedValue({
      balances: [
        { leaveTypeCode: 'VACATION', leaveTypeNameTh: 'ลาพักร้อน', remainingDays: 6, approvedDays: 0, pendingDays: 0, annualQuotaDays: 6 },
        { leaveTypeCode: 'MILITARY', leaveTypeNameTh: 'ลารับราชการทหาร', remainingDays: 366, approvedDays: 0, pendingDays: 0, annualQuotaDays: 366 },
        { leaveTypeCode: 'MATERNITY', leaveTypeNameTh: 'ลาคลอดบุตร', remainingDays: 98, approvedDays: 0, pendingDays: 0, annualQuotaDays: 98 },
      ],
    });

    renderMyLeaveTab();

    await screen.findByText('ลาพักร้อน');
    const statLabel = await screen.findByText('โควตาคงเหลือ');
    const statValue = statLabel.closest('li, div')?.textContent ?? '';

    expect(statValue).toMatch(/\b6\b/);      // VACATION alone
    expect(statValue).not.toMatch(/470/);    // 6 + 366 + 98, the shape of the old bug
    expect(statValue).not.toMatch(/366/);
    expect(statValue).not.toMatch(/98/);
  });

  // §5.3.5 carry-forward reconciliation: the card must agree with the "ดูโควตา" select option
  // (e.g. "สิทธิ์ 6" against "· เหลือ 8"), and QuotaBar's `used` (7, drawing on the carry-in pool)
  // must not exceed `cap` and fire a false "เกินโควตาประจำปีแล้ว" warning.
  it('reconciles สิทธิ์/เหลือ and the QuotaBar cap when a carry-over is present, instead of a false "over quota" warning', async () => {
    api.leave.balances.mockResolvedValue({
      balances: [
        {
          leaveTypeCode: 'VACATION', leaveTypeNameTh: 'ลาพักร้อน', annualQuotaDays: 6,
          approvedDays: 7, pendingDays: 0, remainingDays: 1, carriedInDays: 2,
        },
      ],
    });

    renderMyLeaveTab();

    const card = await screen.findByTestId('primary-balance-card');
    await waitFor(() => expect(card.textContent).toMatch(/ลาพักร้อน/));

    // The reconciled figures: annual quota (6), carry-in (2) and merged remaining (1); 6 + 2 - 7 = 1,
    // matching the select option's "· เหลือ 1 วัน".
    expect(card.textContent).toMatch(/สิทธิ์ 6\u00A0วัน/);
    expect(card.textContent).toMatch(/สะสมจากปีก่อน 2\u00A0วัน/);
    expect(card.textContent).toMatch(/เหลือ 1\u00A0วัน/);

    // The QuotaBar itself: cap is annualQuotaDays + carriedInDays (8), not the bare 6 -- used (7) no longer
    // exceeds it, so this must NOT read as an over-quota bar.
    expect(card.textContent).toMatch(/7\u00A0วัน \/ 8\u00A0วัน/);
    expect(screen.queryByText(/เกินโควตาประจำปีแล้ว/)).toBeNull();
    const progressBar = card.querySelector('[role="progressbar"]');
    expect(progressBar).not.toBeNull();
    expect(progressBar.getAttribute('aria-valuenow')).toBe('88');
  });
});

describe('MyLeaveTab own-request table: the three state-defect fixes (Phase A1)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.leave.types.mockResolvedValue({ leaveTypes: [] });
    api.leave.balances.mockResolvedValue({ balances: [] });
    api.leave.contactDefaults.mockResolvedValue(emptyContactDefaults);
    // #leave-holiday-visibility: UpcomingHolidays renders unconditionally alongside "ปฏิทินวันลา" -- default to no
    // holidays so this describe block's own assertions stay uninvolved with it.
    api.leave.calendarContext.mockResolvedValue({ calendarContext: { holidays: [], nonWorkingDates: [] } });
  });

  it('DEFECT 3c: a 403 from the list endpoint renders the denied StatePanel, not the generic empty state', async () => {
    api.leave.list.mockRejectedValue(Object.assign(new Error('ไม่มีสิทธิ์เข้าถึงรายการนี้'), { status: 403 }));
    renderMyLeaveTab();

    expect(await screen.findByText('ยังเปิดหน้านี้ไม่ได้')).not.toBeNull();
    expect(screen.queryByText('ยังไม่มีคำขอลา')).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
  });
});

describe('MyLeaveTab Phase A4: self-cancel confirmation + certificate download', () => {
  const ownSubmittedRow = {
    id: 3002,
    employeeId: 1,
    employeeName: 'พนักงาน ทดสอบ',
    employeeCode: 'GLR-001',
    leaveTypeCode: 'VACATION',
    leaveTypeNameTh: 'ลาพักร้อน',
    startDate: '2026-08-15',
    endDate: '2026-08-15',
    totalDays: 1,
    quotaRemainingAfter: 5,
    status: 'SUBMITTED',
    reason: 'ธุระส่วนตัว',
  };

  beforeEach(() => {
    vi.clearAllMocks();
    api.leave.types.mockResolvedValue({ leaveTypes: [] });
    api.leave.balances.mockResolvedValue({ balances: [] });
    api.leave.contactDefaults.mockResolvedValue(emptyContactDefaults);
    // #leave-holiday-visibility: UpcomingHolidays renders unconditionally alongside "ปฏิทินวันลา" -- default to no
    // holidays so this describe block's own assertions stay uninvolved with it.
    api.leave.calendarContext.mockResolvedValue({ calendarContext: { holidays: [], nonWorkingDates: [] } });
  });

  it('clicking ยกเลิก on a SUBMITTED own request opens a ConfirmDialog and does NOT cancel until confirmed', async () => {
    api.leave.list.mockResolvedValue({ requests: [ownSubmittedRow] });
    renderMyLeaveTab();

    await screen.findByText('ธุระส่วนตัว');
    fireEvent.click(screen.getByRole('button', { name: 'ยกเลิก' }));

    // Still just the request -- clicking the row action alone must never cancel by itself.
    expect(api.leave.cancel).not.toHaveBeenCalled();
    const dialogConfirmButton = await screen.findByRole('button', { name: 'ยกเลิกคำขอ' });
    // Optional reason -- an employee cancelling their own request is not held to the mandatory reason an
    // approver's reject requires.
    expect(dialogConfirmButton.disabled).toBe(false);

    fireEvent.click(dialogConfirmButton);
    await waitFor(() => expect(api.leave.cancel).toHaveBeenCalledWith(3002, { reviewerNote: null }));
  });
});

describe('MyLeaveTab bugfix (2026-08): "ของฉัน" always scopes to the actor\'s own employeeId', () => {
  // Regression: with `employeeId` omitted, LeaveService#list serves "self OR reports_to_employee_id
  // = actor" (LeaveRepository#findRequests), so a manager's "ของฉัน" tab received their reports'
  // rows. This is the only fixture where `manager` has a direct report with a request of their own.
  const managerUser = {
    employeeId: 5, name: 'หัวหน้างาน', role: 'employee', manager: true,
  };
  const managerCurrentEmployee = { id: 5, nameTh: 'หัวหน้างาน' };

  const ownRequest = {
    id: 4001,
    employeeId: 5,
    employeeName: 'หัวหน้างาน',
    employeeCode: 'GLR-005',
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
    id: 4002,
    employeeId: 6,
    employeeName: 'ลูกทีม ทดสอบ',
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

  beforeEach(() => {
    vi.clearAllMocks();
    api.leave.types.mockResolvedValue({ leaveTypes: [] });
    api.leave.balances.mockResolvedValue({ balances: [] });
    api.leave.contactDefaults.mockResolvedValue(emptyContactDefaults);
    // #leave-holiday-visibility: UpcomingHolidays renders unconditionally alongside "ปฏิทินวันลา" -- default to no
    // holidays so this describe block's own assertions stay uninvolved with it.
    api.leave.calendarContext.mockResolvedValue({ calendarContext: { holidays: [], nonWorkingDates: [] } });
    // Mirrors the backend contract (LeaveService#list / LeaveRepository#findRequests): an explicit
    // `employeeId` narrows strictly to that employee's rows; omitted returns both rows.
    api.leave.list.mockImplementation((params) => Promise.resolve({
      requests: [ownRequest, directReportRequest].filter((request) => (
        params?.employeeId
          ? Number(request.employeeId) === Number(params.employeeId)
          : Number(request.employeeId) === 5 || request.managerEmployeeId === 5
      )),
    }));
  });

  it('REGRESSION: a manager with a direct report sees only their OWN request in "ของฉัน", never the report\'s', async () => {
    render(
      <MemoryRouter>
        <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
          <MyLeaveTab user={managerUser} currentEmployee={managerCurrentEmployee} showToast={vi.fn()} />
        </QueryClientProvider>
      </MemoryRouter>,
    );

    expect(await screen.findByText('ลาของหัวหน้างานเอง')).not.toBeNull();
    expect(screen.queryByText('ลาป่วยของลูกทีม')).toBeNull();
    expect(screen.queryByText('ลูกทีม ทดสอบ')).toBeNull();

    // The plumbing assertion: requestsQuery must always pass the actor's own employeeId, never omit it.
    await waitFor(() => expect(api.leave.list).toHaveBeenCalledWith(expect.objectContaining({ employeeId: 5 })));
  });
});

