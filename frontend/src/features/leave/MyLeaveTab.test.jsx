import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MyLeaveTab } from './MyLeaveTab.jsx';
import { api } from '../../api/index.js';
import { downloadBlob } from '../../utils/download.js';

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
      // #leave-holiday-visibility: MyLeaveTab now renders UpcomingHolidays unconditionally
      // (alongside "ปฏิทินวันลา"), so every describe block below that renders MyLeaveTab needs
      // its own default resolved value, or the panel's query errors out under every test.
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

// The request-submission form (employee/type picker, dates, sub-day times, contact block,
// attachment) moved to the /leave/new composer (LeaveRequestPage.jsx, Phase A2, #485) -- its own
// test file (LeaveRequestPage.test.jsx) now owns the coverage the three tests that used to live
// here (start-date-past validation, the create() payload shape, sub-day toggling) exercised.
// MyLeaveTab is read-only from this phase on: balances browsing, request history, cancel.

describe('MyLeaveTab balances: one primary card + a single disclosure (owner feedback, 2026-08)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // The balance-preview select (Phase A2: decoupled from the removed submission form) seeds
    // its default from THIS list, not from the balances fixture -- every test below needs at
    // least VACATION here so that seeding effect has something to default to.
    api.leave.types.mockResolvedValue({ leaveTypes: [{ code: 'VACATION', nameTh: 'ลาพักร้อน', nameEn: 'Vacation' }] });
    api.leave.list.mockResolvedValue({ requests: [] });
    api.leave.contactDefaults.mockResolvedValue(emptyContactDefaults);
    // #leave-holiday-visibility: UpcomingHolidays renders unconditionally alongside "ปฏิทินวันลา"
    // -- default to no holidays so this describe block's own assertions stay uninvolved with it.
    api.leave.calendarContext.mockResolvedValue({ calendarContext: { holidays: [], nonWorkingDates: [] } });
  });

  it('DEFECT: the "โควตาคงเหลือ" stat sums ONLY the everyday three, never the rare types', async () => {
    // Regression. This shipped as "โควตาคงเหลือ 564 วัน" on the real mock seed, because the stat
    // reduced over EVERY balance: 7 + 30 + 3 + MATERNITY 98 + MILITARY 366 + ORDINATION 60. The
    // rare-type rows already suppressed those figures; the aggregate was the one place the
    // suppression leaked, so an employee was told they had a year and a half of leave banked.
    //
    // Note the sibling test below asserts `queryByText(/366/)` is null and passed the whole time
    // this bug was live — the buggy sum renders 372, not 366, so matching on the sentinel itself
    // cannot detect it. Asserting the STAT's own value is what distinguishes the two.
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

  it('lists every leave type behind one "โควตาการลาทั้งหมด" disclosure, collapsed by default, and never renders MILITARY\'s sentinel annualQuotaDays/remainingDays', async () => {
    api.leave.balances.mockResolvedValue({
      balances: [
        { leaveTypeCode: 'VACATION', leaveTypeNameTh: 'ลาพักร้อน', remainingDays: 6, approvedDays: 0, pendingDays: 0, annualQuotaDays: 6 },
        // MILITARY's annualQuotaDays (366) is a sentinel, not a real policy figure -- must never
        // reach the DOM as "เหลือ 366 วัน" or "สิทธิ์ 366 วัน", inside the disclosure or out.
        { leaveTypeCode: 'MILITARY', leaveTypeNameTh: 'ลารับราชการทหาร', remainingDays: 366, approvedDays: 10, pendingDays: 2, annualQuotaDays: 366 },
      ],
    });

    renderMyLeaveTab();

    // The default selected type (VACATION) is the primary card; MILITARY is not on screen yet.
    await screen.findByText('ลาพักร้อน');
    expect(screen.queryByText('ลารับราชการทหาร')).toBeNull();
    expect(screen.queryByText(/366/)).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /โควตาการลาทั้งหมด/ }));

    expect(await screen.findByText('ลารับราชการทหาร')).not.toBeNull();
    expect(screen.getByText(/ใช้แล้ว.*10.*วัน/)).not.toBeNull();
    // The sentinel quota must never render, expanded or not.
    expect(screen.queryByText(/366/)).toBeNull();

    // The disclosure body is a definition list, never card chrome (nested cards are always wrong
    // per DESIGN.md's card-diet rule -- see the CollapsibleSection unmount-when-collapsed comment
    // this relies on).
    expect(document.querySelector('.collapsible-body dl')).not.toBeNull();
    expect(document.querySelectorAll('.collapsible-body [class*="card"]')).toHaveLength(0);
  });

  it('updates the primary balance card to follow the "ประเภทการลา" select', async () => {
    api.leave.types.mockResolvedValue({
      leaveTypes: [
        { code: 'VACATION', nameTh: 'ลาพักร้อน', nameEn: 'Vacation' },
        { code: 'SICK', nameTh: 'ลาป่วย', nameEn: 'Sick' },
      ],
    });
    api.leave.balances.mockResolvedValue({
      balances: [
        { leaveTypeCode: 'VACATION', leaveTypeNameTh: 'ลาพักร้อน', remainingDays: 6, approvedDays: 0, pendingDays: 0, annualQuotaDays: 6 },
        { leaveTypeCode: 'SICK', leaveTypeNameTh: 'ลาป่วย', remainingDays: 30, approvedDays: 1, pendingDays: 0, annualQuotaDays: 30 },
      ],
    });

    renderMyLeaveTab();

    // Scoped to the primary card specifically -- "ลาพักร้อน" also appears as an <option> in the
    // "ประเภทการลา" select, so a bare screen.findByText would match both.
    const card = await screen.findByTestId('primary-balance-card');
    await waitFor(() => expect(card.textContent).toMatch(/ลาพักร้อน/));
    expect(card.textContent).toMatch(/\b6\b/);

    fireEvent.change(screen.getByLabelText(/ประเภทการลา/), { target: { value: 'SICK' } });

    await waitFor(() => expect(card.textContent).toMatch(/ลาป่วย/));
    expect(card.textContent).not.toMatch(/ลาพักร้อน/);
    expect(card.textContent).toMatch(/\b30\b/);
  });

  it('MILITARY: the 366-day sentinel never renders as a quota or remaining figure, including once it becomes the primary card', async () => {
    api.leave.types.mockResolvedValue({
      leaveTypes: [
        { code: 'VACATION', nameTh: 'ลาพักร้อน', nameEn: 'Vacation' },
        {
          code: 'MILITARY', nameTh: 'ลารับราชการทหาร', nameEn: 'Military service leave', paidDaysCap: 60, minServiceMonths: 0, oncePerEmployment: false,
        },
      ],
    });
    api.leave.balances.mockResolvedValue({
      balances: [
        { leaveTypeCode: 'VACATION', leaveTypeNameTh: 'ลาพักร้อน', remainingDays: 6, approvedDays: 0, pendingDays: 0, annualQuotaDays: 6 },
        { leaveTypeCode: 'MILITARY', leaveTypeNameTh: 'ลารับราชการทหาร', remainingDays: 366, approvedDays: 10, pendingDays: 2, annualQuotaDays: 366 },
      ],
    });

    renderMyLeaveTab();

    const card = await screen.findByTestId('primary-balance-card');
    await waitFor(() => expect(card.textContent).toMatch(/ลาพักร้อน/));
    fireEvent.change(screen.getByLabelText(/ประเภทการลา/), { target: { value: 'MILITARY' } });

    await waitFor(() => expect(card.textContent).toMatch(/ลารับราชการทหาร/));
    // The sentinel (366 = annualQuotaDays = the un-suppressed remainingDays) must never appear.
    expect(card.textContent).not.toMatch(/366/);
    // The meaningful figure instead is the paid-days cap (60), plus the real used/pending counts.
    expect(card.textContent).toMatch(/\b60\b/);
    expect(card.textContent).toMatch(/ใช้แล้ว.*10.*วัน/);
  });
});

describe('MyLeaveTab own-request table: the three state-defect fixes (Phase A1)', () => {
  const ownRow = {
    id: 3001,
    employeeId: 1,
    employeeName: 'พนักงาน ทดสอบ',
    employeeCode: 'GLR-001',
    leaveTypeCode: 'VACATION',
    leaveTypeNameTh: 'ลาพักร้อน',
    startDate: '2026-08-01',
    endDate: '2026-08-01',
    totalDays: 1,
    quotaRemainingAfter: 5,
    status: 'APPROVED',
    reason: 'พักผ่อนประจำปี',
  };

  beforeEach(() => {
    vi.clearAllMocks();
    api.leave.types.mockResolvedValue({ leaveTypes: [] });
    api.leave.balances.mockResolvedValue({ balances: [] });
    api.leave.contactDefaults.mockResolvedValue(emptyContactDefaults);
    // #leave-holiday-visibility: UpcomingHolidays renders unconditionally alongside "ปฏิทินวันลา"
    // -- default to no holidays so this describe block's own assertions stay uninvolved with it.
    api.leave.calendarContext.mockResolvedValue({ calendarContext: { holidays: [], nonWorkingDates: [] } });
  });

  it('DEFECT 1: a background refetch (isFetching, not isPending) never blanks already-loaded rows into an empty state', async () => {
    let resolveSecondFetch;
    let historyCalls = 0;
    // Param-aware rather than a mockResolvedValueOnce chain: as of the 2026-08-10 restructure this
    // tab issues TWO leave.list calls, and a positional chain would hand this test's fixtures to
    // whichever query happened to fire first. The forward-window query behind
    // "วันลาที่กำลังจะถึง" always sends explicit dates; the history query this test is about sends
    // none by default (empty filters -> hrApi drops them -> backend applies its own window).
    api.leave.list.mockImplementation((params = {}) => {
      if (params.from) return Promise.resolve({ requests: [] });
      historyCalls += 1;
      if (historyCalls === 1) return Promise.resolve({ requests: [ownRow] });
      return new Promise((resolve) => { resolveSecondFetch = resolve; });
    });

    const queryClient = renderMyLeaveTab();
    await screen.findByText('พักผ่อนประจำปี');

    queryClient.refetchQueries({ queryKey: ['leave', 'list'] });
    await waitFor(() => expect(historyCalls).toBe(2));
    // The row is STILL on screen while the second fetch is in flight -- the pre-A1 bug replaced
    // it with an EmptyState on every 60s poll / window-focus refetch.
    expect(screen.getByText('พักผ่อนประจำปี')).not.toBeNull();
    expect(screen.getByText('กำลังอัปเดต…')).not.toBeNull();

    resolveSecondFetch({ requests: [ownRow] });
    await waitFor(() => expect(screen.queryByText('กำลังอัปเดต…')).toBeNull());
    expect(screen.getByText('พักผ่อนประจำปี')).not.toBeNull();
  });

  it('DEFECT 2: a load failure renders DataTable\'s inline error+retry, not a toast', async () => {
    const showToast = vi.fn();
    api.leave.list.mockRejectedValue(Object.assign(new Error('เซิร์ฟเวอร์ขัดข้อง'), { status: 500 }));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    render(
      <MemoryRouter>
        <QueryClientProvider client={queryClient}>
          <MyLeaveTab user={user} currentEmployee={currentEmployee} showToast={showToast} />
        </QueryClientProvider>
      </MemoryRouter>,
    );

    expect(await screen.findByRole('alert')).not.toBeNull();
    expect(screen.getByRole('button', { name: /ลองอีกครั้ง/ })).not.toBeNull();
    // No error toast for a load failure any more -- see the pre-A1 five-useEffect anti-pattern
    // this replaces (CLAUDE.md: showToast has an unstable identity).
    expect(showToast).not.toHaveBeenCalled();
  });

  it('DEFECT 3a: a never-loaded, default-filters empty result shows teaching copy, not a generic message', async () => {
    api.leave.list.mockResolvedValue({ requests: [] });
    renderMyLeaveTab();

    expect(await screen.findByText('ยังไม่มีคำขอลา')).not.toBeNull();
    expect(screen.getByRole('button', { name: /ยื่นคำขอลา/ })).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'ล้างตัวกรอง' })).toBeNull();
  });

  // Leave HR-submit gate (2026-08-03): hr/ceo oversee leave but do not request it for
  // themselves (owner ruling) -- the empty-state's own "ยื่นคำขอลา" action is the SECOND CTA
  // this rule touches (LeaveSurfacePage.jsx's page-header CTA is the first). Presentation only;
  // the real rule is LeaveService#resolveTargetEmployee's server-side 403.
  it('DEFECT 3a (hr/ceo variant): the empty-state CTA does not render for hr or ceo', async () => {
    api.leave.list.mockResolvedValue({ requests: [] });
    renderMyLeaveTab({ employeeId: 99, name: 'ฝ่ายบุคคล', role: 'hr', manager: false });

    expect(await screen.findByText('ยังไม่มีคำขอลา')).not.toBeNull();
    expect(screen.queryByRole('button', { name: /ยื่นคำขอลา/ })).toBeNull();
  });

  it('DEFECT 3b: a filtered-to-zero result offers a clear-filters action distinct from the empty-teaching copy', async () => {
    api.leave.list.mockResolvedValue({ requests: [] });
    renderMyLeaveTab();

    await screen.findByText('ยังไม่มีคำขอลา');

    fireEvent.change(screen.getByLabelText('สถานะ'), { target: { value: 'REJECTED' } });
    fireEvent.click(screen.getByRole('button', { name: /ค้นหา/ }));

    const clearButton = await screen.findByRole('button', { name: 'ล้างตัวกรอง' });
    expect(screen.queryByText('ยังไม่มีคำขอลา')).toBeNull();

    fireEvent.click(clearButton);
    expect(await screen.findByText('ยังไม่มีคำขอลา')).not.toBeNull();
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

  const ownRowWithCertificate = {
    id: 3003,
    employeeId: 1,
    employeeName: 'พนักงาน ทดสอบ',
    employeeCode: 'GLR-001',
    leaveTypeCode: 'SICK',
    leaveTypeNameTh: 'ลาป่วย',
    startDate: '2026-08-16',
    endDate: '2026-08-16',
    totalDays: 1,
    quotaRemainingAfter: 29,
    status: 'APPROVED',
    reason: 'ไข้หวัด',
    attachmentId: 8001,
    attachmentFileName: 'ใบรับรองแพทย์.pdf',
  };

  beforeEach(() => {
    vi.clearAllMocks();
    api.leave.types.mockResolvedValue({ leaveTypes: [] });
    api.leave.balances.mockResolvedValue({ balances: [] });
    api.leave.contactDefaults.mockResolvedValue(emptyContactDefaults);
    // #leave-holiday-visibility: UpcomingHolidays renders unconditionally alongside "ปฏิทินวันลา"
    // -- default to no holidays so this describe block's own assertions stay uninvolved with it.
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
    // Optional reason -- an employee cancelling their own request is not held to the mandatory
    // reason an approver's reject requires.
    expect(dialogConfirmButton.disabled).toBe(false);

    fireEvent.click(dialogConfirmButton);
    await waitFor(() => expect(api.leave.cancel).toHaveBeenCalledWith(3002, { reviewerNote: null }));
  });

  it('a row with no attachment never renders a download button', async () => {
    api.leave.list.mockResolvedValue({ requests: [ownSubmittedRow] });
    renderMyLeaveTab();

    fireEvent.click(await screen.findByRole('button', { name: /ดูรายละเอียด/ }));
    expect(screen.queryByRole('button', { name: /ดาวน์โหลด/ })).toBeNull();
  });

  it('downloads the requester\'s own attachment via GET /api/leave/attachments/{id}', async () => {
    api.leave.list.mockResolvedValue({ requests: [ownRowWithCertificate] });
    const blob = new Blob(['fake-pdf']);
    api.leave.downloadAttachment.mockResolvedValue(blob);
    renderMyLeaveTab();

    fireEvent.click(await screen.findByRole('button', { name: /ดูรายละเอียด/ }));
    fireEvent.click(await screen.findByRole('button', { name: 'ดาวน์โหลด' }));

    await waitFor(() => expect(api.leave.downloadAttachment).toHaveBeenCalledWith(8001));
    await waitFor(() => expect(downloadBlob).toHaveBeenCalledWith(blob, 'leave-attachment-3003', 'pdf'));
  });

  it('a failed download shows an error toast, not a silent no-op', async () => {
    const showToast = vi.fn();
    api.leave.list.mockResolvedValue({ requests: [ownRowWithCertificate] });
    api.leave.downloadAttachment.mockRejectedValue(new Error('ไม่มีสิทธิ์เข้าถึงรายการนี้'));

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <MemoryRouter>
        <QueryClientProvider client={queryClient}>
          <MyLeaveTab user={user} currentEmployee={currentEmployee} showToast={showToast} />
        </QueryClientProvider>
      </MemoryRouter>,
    );

    fireEvent.click(await screen.findByRole('button', { name: /ดูรายละเอียด/ }));
    fireEvent.click(await screen.findByRole('button', { name: 'ดาวน์โหลด' }));

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('error', 'ไม่มีสิทธิ์เข้าถึงรายการนี้'));
    expect(downloadBlob).not.toHaveBeenCalled();
  });
});

describe('MyLeaveTab bugfix (2026-08): "ของฉัน" always scopes to the actor\'s own employeeId', () => {
  // THE regression test for the bug this branch fixes. LeaveService#list -- for any actor
  // outside VIEW_ALL_ROLES (hr/ceo) -- treats an omitted `employeeId` as "self OR
  // reports_to_employee_id = actor" (LeaveRepository#findRequests), so a manager's own
  // "ของฉัน" tab used to genuinely receive their direct reports' rows too. This fixture is
  // the first one in this file where `manager` actually has a direct report with a request
  // of their own -- every fixture above uses `manager: false` and a single-employee actor,
  // so none of them could have caught this.
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
    // #leave-holiday-visibility: UpcomingHolidays renders unconditionally alongside "ปฏิทินวันลา"
    // -- default to no holidays so this describe block's own assertions stay uninvolved with it.
    api.leave.calendarContext.mockResolvedValue({ calendarContext: { holidays: [], nonWorkingDates: [] } });
    // Mirrors the real backend contract (LeaveService#list / LeaveRepository#findRequests):
    // an explicit `employeeId` narrows strictly to that one employee's own rows. Before the
    // fix, MyLeaveTab's requestsQuery omitted `employeeId` whenever no filter value was
    // applied, so this mock would have received `{}` and (per its own self-OR-report
    // semantics below) handed back BOTH rows.
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

    // The plumbing assertion: requestsQuery must always pass the actor's own employeeId,
    // never omit it.
    await waitFor(() => expect(api.leave.list).toHaveBeenCalledWith(expect.objectContaining({ employeeId: 5 })));
  });

  // Was "ปฏิทินวันลา" (restored here by a 2026-08 review fix so that employees with no direct
  // reports kept a leave list at all). Renamed to "วันลาที่กำลังจะถึง" and re-scoped to a FORWARD
  // window by the 2026-08-10 IA restructure -- see UpcomingLeaveList.jsx for why the old name
  // described an intention the data could not deliver (defect D1).
  describe('"วันลาที่กำลังจะถึง" is self-scoped and genuinely forward-looking', () => {
    // Relative to the real clock, deliberately: the panel derives its window from `todayIso()` at
    // render, so a hardcoded fixture date would quietly stop being "upcoming" as time passed and
    // the test would start proving nothing. +/-10 days is far enough from midnight that a
    // UTC-vs-Bangkok day slip cannot flip which side of "today" a fixture falls on.
    function isoDaysFromToday(offset) {
      const date = new Date();
      date.setDate(date.getDate() + offset);
      return date.toISOString().slice(0, 10);
    }

    const plainEmployee = { employeeId: 1, name: 'พนักงาน ทดสอบ', role: 'employee', manager: false };
    const plainEmployeeCurrent = { id: 1, nameTh: 'พนักงาน ทดสอบ' };

    function futureOwnDay(overrides = {}) {
      return {
        id: 5001,
        employeeId: 1,
        employeeName: 'พนักงาน ทดสอบ',
        employeeCode: 'GLR-001',
        leaveTypeCode: 'VACATION',
        leaveTypeNameTh: 'ลาพักร้อน',
        startDate: isoDaysFromToday(10),
        endDate: isoDaysFromToday(10),
        totalDays: 1,
        quotaRemainingAfter: 5,
        status: 'APPROVED',
        reason: 'พักผ่อนของตัวเอง',
        ...overrides,
      };
    }

    // Honours the date window the caller actually asks for, the way LeaveRepository#findRequests
    // does (OVERLAP: `start_date <= :toDate AND end_date >= :fromDate`). Without this the mock
    // would hand every row to both queries and the forward window would never be exercised at all
    // -- the panel would "pass" while showing rows the real backend would have excluded.
    function mockListHonouringWindow(rows) {
      api.leave.list.mockImplementation((params = {}) => Promise.resolve({
        requests: rows.filter((request) => {
          if (params.employeeId && Number(request.employeeId) !== Number(params.employeeId)) return false;
          if (params.from && request.endDate < params.from) return false;
          if (params.to && request.startDate > params.to) return false;
          return true;
        }),
      }));
    }

    function renderFor(renderUser, renderEmployee) {
      render(
        <MemoryRouter>
          <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
            <MyLeaveTab user={renderUser} currentEmployee={renderEmployee} showToast={vi.fn()} />
          </QueryClientProvider>
        </MemoryRouter>,
      );
      // The heading's immediate ancestor is only PanelHeader's wrapper `<div>`, so the list
      // content (a sibling of that div) requires walking up to the Panel's `<section>`.
      return screen.findByRole('heading', { name: 'วันลาที่กำลังจะถึง' })
        .then((heading) => heading.closest('section'));
    }

    it('renders for a plain employee with no direct reports, showing their own leave days', async () => {
      mockListHonouringWindow([futureOwnDay()]);

      const panel = await renderFor(plainEmployee, plainEmployeeCurrent);
      await waitFor(() => expect(panel.textContent).toMatch(/ลาพักร้อน/));
    });

    // THE regression test for defect D1. Before the restructure this panel derived from the
    // history query, whose filter defaulted to `to: todayIso()`; LeaveRepository#findRequests
    // matches `start_date <= :toDate`, so leave starting after today was filtered out server-side
    // and a panel titled "ปฏิทินวันลา" could never show a single upcoming day. The panel now runs
    // its own forward query, and the window-honouring mock above is what makes this test able to
    // fail: point the panel back at a today-bounded window and this row disappears.
    it('D1: shows leave that STARTS AFTER TODAY -- the case the old today-bounded panel could never show', async () => {
      mockListHonouringWindow([futureOwnDay({ reason: 'ลาพักร้อนอนาคต' })]);

      const panel = await renderFor(plainEmployee, plainEmployeeCurrent);
      await waitFor(() => expect(panel.textContent).toMatch(/ลาพักร้อน/));
    });

    // Rejected/cancelled leave is not "upcoming" in any useful sense -- only SUBMITTED/APPROVED.
    it('excludes a future-dated REJECTED request', async () => {
      mockListHonouringWindow([
        futureOwnDay({ id: 5002, status: 'REJECTED', leaveTypeNameTh: 'ลากิจถูกปฏิเสธ' }),
      ]);

      const panel = await renderFor(plainEmployee, plainEmployeeCurrent);
      await waitFor(() => expect(panel.textContent).toMatch(/ยังไม่มีวันลาที่กำลังจะถึง/));
      expect(panel.textContent).not.toMatch(/ลากิจถูกปฏิเสธ/);
    });

    it("REGRESSION: a manager's own upcoming list does NOT include a direct report's day", async () => {
      mockListHonouringWindow([
        { ...ownRequest, startDate: isoDaysFromToday(10), endDate: isoDaysFromToday(10) },
        { ...directReportRequest, startDate: isoDaysFromToday(11), endDate: isoDaysFromToday(11) },
      ]);

      const panel = await renderFor(managerUser, managerCurrentEmployee);
      await waitFor(() => expect(panel.textContent).toMatch(/ลาพักร้อน/));
      // The direct report never appears in the actor's OWN panel -- LeaveService#list's default
      // scoping for an actor with reports is "self OR reports_to = actor", which is why this query
      // must always pass an explicit employeeId.
      expect(panel.textContent).not.toMatch(/ลูกทีม ทดสอบ/);
    });
  });
});

// #leave-holiday-visibility (PR 3): holidays visible up front on this tab, alongside "ปฏิทินวันลา"
// (the actor's own leave days) rather than replacing or disturbing it -- see MyLeaveTab.jsx's own
// comment on why the two panels sit next to each other.
describe('MyLeaveTab holiday visibility (#leave-holiday-visibility, PR 3)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.leave.types.mockResolvedValue({ leaveTypes: [] });
    api.leave.balances.mockResolvedValue({ balances: [] });
    api.leave.contactDefaults.mockResolvedValue(emptyContactDefaults);
    api.leave.list.mockResolvedValue({ requests: [] });
  });

  it('shows the วันหยุดที่จะถึง panel alongside "วันลาที่กำลังจะถึง", without disturbing it', async () => {
    api.leave.calendarContext.mockResolvedValue({
      calendarContext: {
        holidays: [{ holidayDate: '2026-08-15', nameTh: 'วันหยุดทดสอบ' }],
        nonWorkingDates: [],
      },
    });

    renderMyLeaveTab();

    const holidayHeading = await screen.findByRole('heading', { name: 'วันหยุดที่จะถึง' });
    const holidayPanel = holidayHeading.closest('section');
    // The heading itself is present from first render (Panel's title is static); the query
    // result it depends on is not -- waitFor lets the loading EmptyState resolve before asserting.
    await waitFor(() => expect(holidayPanel.textContent).toMatch(/วันหยุดทดสอบ/));

    // The personal leave panel still renders too -- the two "what's coming up" panels sit
    // ALONGSIDE each other, never one in place of the other. (Renamed from "ปฏิทินวันลา" by the
    // 2026-08-10 restructure.)
    const upcomingLeaveHeading = await screen.findByRole('heading', { name: 'วันลาที่กำลังจะถึง' });
    expect(upcomingLeaveHeading.closest('section')).not.toBeNull();
  });

  // Real วันหยุดบริษัท names run up to 149 chars (MOCK_HOLIDAY_DATES in mockApi.js is a verbatim
  // copy of production's hr.holiday, the reason V129 widened name_th from VARCHAR(120) to TEXT).
  // UpcomingHolidays.jsx already renders one holiday per list row rather than a comma-joined
  // sentence -- this pins that the composition holds through THIS caller too, not only in
  // isolation or only in the leave composer (LeaveRequestPage.test.jsx's own long-name coverage).
  it('renders a 149-char real production holiday name intact, not truncated or broken', async () => {
    const LONGEST_REAL_HOLIDAY_NAME =
      'ชดเชยวันคล้ายวันพระบรมราชสมภพ พระบาทสมเด็จพระบรมชนกาธิเบศร มหาภูมิพลอดุลยเดชมหาราช บรมนาถบพิตร วันชาติ และวันพ่อแห่งชาติ (วันเสาร์ที่ 5 ธันวาคม 2569)';
    api.leave.calendarContext.mockResolvedValue({
      calendarContext: {
        holidays: [{ holidayDate: '2026-12-07', nameTh: LONGEST_REAL_HOLIDAY_NAME }],
        nonWorkingDates: [],
      },
    });

    renderMyLeaveTab();

    expect(await screen.findByText(LONGEST_REAL_HOLIDAY_NAME)).not.toBeNull();
  });
});
