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
    // ORDINATION 60 = "โควตาคงเหลือ 564 วัน"). The sibling test asserting `queryByText(/366/)` is null
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

  // The option labels are a place MILITARY's sentinel could leak into; it must never reach the DOM.
  it('carries each everyday type\'s remaining days in the "ดูโควตา" options, and never renders MILITARY\'s sentinel', async () => {
    api.leave.types.mockResolvedValue({
      leaveTypes: [
        { code: 'VACATION', nameTh: 'ลาพักร้อน', nameEn: 'Vacation' },
        { code: 'MILITARY', nameTh: 'ลารับราชการทหาร', nameEn: 'Military' },
      ],
    });
    api.leave.balances.mockResolvedValue({
      balances: [
        { leaveTypeCode: 'VACATION', leaveTypeNameTh: 'ลาพักร้อน', remainingDays: 6, approvedDays: 0, pendingDays: 0, annualQuotaDays: 6 },
        // MILITARY's annualQuotaDays/remainingDays (366) is a sentinel, not a real policy figure -- must never reach
        // the DOM as "เหลือ 366 วัน" or "สิทธิ์ 366 วัน", in an option or out.
        { leaveTypeCode: 'MILITARY', leaveTypeNameTh: 'ลารับราชการทหาร', remainingDays: 366, approvedDays: 10, pendingDays: 2, annualQuotaDays: 366 },
      ],
    });

    renderMyLeaveTab();

    const select = await screen.findByLabelText('เลือกประเภทการลาที่ต้องการดูโควตา');
    const optionTexts = () => [...select.querySelectorAll('option')].map((option) => option.textContent);
    // Everyday type: the figure is on the option itself, which is what makes the removed disclosure redundant
    // rather than missed. Re-read inside waitFor -- the labels only gain their "· เหลือ N วัน" suffix once
    // balancesQuery lands, one render after the options exist.
    await waitFor(() => expect(optionTexts()).toContain('ลาพักร้อน · เหลือ 6\u00A0วัน'));
    // Rare type: name only, no derived figure.
    expect(optionTexts()).toContain('ลารับราชการทหาร');
    expect(screen.queryByText(/366/)).toBeNull();
    expect(document.body.textContent).not.toMatch(/366/);

    // The disclosure is gone for good -- neither the trigger nor a second copy of the balances.
    expect(screen.queryByRole('button', { name: /โควตาการลาทั้งหมด/ })).toBeNull();
    expect(document.querySelector('.collapsible-body')).toBeNull();
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

    // Scoped to the primary card specifically -- "ลาพักร้อน" also appears as an <option> in the "ประเภทการลา"
    // select, so a bare screen.findByText would match both.
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
    // #leave-holiday-visibility: UpcomingHolidays renders unconditionally alongside "ปฏิทินวันลา" -- default to no
    // holidays so this describe block's own assertions stay uninvolved with it.
    api.leave.calendarContext.mockResolvedValue({ calendarContext: { holidays: [], nonWorkingDates: [] } });
  });

  it('DEFECT 1: a background refetch (isFetching, not isPending) never blanks already-loaded rows into an empty state', async () => {
    let resolveSecondFetch;
    let historyCalls = 0;
    // Param-aware rather than a mockResolvedValueOnce chain: this tab issues TWO leave.list calls and
    // a positional chain would hand the fixtures to whichever fires first. The forward-window query
    // behind "วันลาที่กำลังจะถึง" sends explicit dates; the history query sends none by default.
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
    // The row is STILL on screen while the second fetch is in flight -- the pre-A1 bug replaced it with an
    // EmptyState on every 60s poll / window-focus refetch.
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
    // No error toast for a load failure any more -- see the pre-A1 five-useEffect anti-pattern this replaces
    // (CLAUDE.md: showToast has an unstable identity).
    expect(showToast).not.toHaveBeenCalled();
  });

  it('DEFECT 3a: a never-loaded, default-filters empty result shows teaching copy, not a generic message', async () => {
    api.leave.list.mockResolvedValue({ requests: [] });
    renderMyLeaveTab();

    expect(await screen.findByText('ยังไม่มีคำขอลา')).not.toBeNull();
    expect(screen.getByRole('button', { name: /ยื่นคำขอลา/ })).not.toBeNull();
    expect(screen.queryByRole('button', { name: 'ล้างตัวกรอง' })).toBeNull();
  });

  // hr/ceo oversee leave but do not request it: the empty-state's "ยื่นคำขอลา" action is the second
  // CTA this rule touches (the first is LeaveSurfacePage.jsx's page-header CTA). Presentation only;
  // the real rule is LeaveService#resolveTargetEmployee's 403.
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

  it('a row with no attachment never renders a certificate download button', async () => {
    api.leave.list.mockResolvedValue({ requests: [ownSubmittedRow] });
    renderMyLeaveTab();

    fireEvent.click(await screen.findByRole('button', { name: /ดูรายละเอียด/ }));
    // Exact match: the panel-level report button (LeaveReportDownload.jsx, "ดาวน์โหลดรายงานของฉัน")
    // is always on screen, so a loose `/ดาวน์โหลด/` would fail for that reason alone.
    expect(screen.queryByRole('button', { name: 'ดาวน์โหลด' })).toBeNull();
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

  // "วันลาที่กำลังจะถึง" reads a FORWARD window (see UpcomingLeaveList.jsx).
  describe('"วันลาที่กำลังจะถึง" is self-scoped and genuinely forward-looking', () => {
    // Build dates relative to today so upcoming-request fixtures remain valid as the calendar advances.
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

    // Honours the date window the caller actually asks for, the way LeaveRepository#findRequests does (OVERLAP:
    // `start_date <= :toDate AND end_date >= :fromDate`).
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
      // The heading's immediate ancestor is only PanelHeader's wrapper `<div>`, so the list content (a sibling of
      // that div) requires walking up to the Panel's `<section>`.
      return screen.findByRole('heading', { name: 'วันลาที่กำลังจะถึง' })
        .then((heading) => heading.closest('section'));
    }

    it('renders for a plain employee with no direct reports, showing their own leave days', async () => {
      mockListHonouringWindow([futureOwnDay()]);

      const panel = await renderFor(plainEmployee, plainEmployeeCurrent);
      await waitFor(() => expect(panel.textContent).toMatch(/ลาพักร้อน/));
    });

    // Regression: the panel once derived from the history query, whose filter defaulted to
    // `to: todayIso()`; findRequests matches `start_date <= :toDate`, so upcoming leave was filtered
    // out server-side. The panel runs its own forward query, and the window-honouring mock above lets
    // this test fail if the panel goes back to a today-bounded window.
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
      // The direct report never appears in the actor's OWN panel -- LeaveService#list's default scoping for an actor
      // with reports is "self OR reports_to = actor", which is why this query must always pass an explicit
      // employeeId.
      expect(panel.textContent).not.toMatch(/ลูกทีม ทดสอบ/);
    });
  });
});

// Holidays are visible up front on this tab, alongside the actor's own leave days (see
// MyLeaveTab.jsx on why the two panels sit together).
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
    // The heading itself is present from first render (Panel's title is static); the query result it depends on is
    // not -- waitFor lets the loading EmptyState resolve before asserting.
    await waitFor(() => expect(holidayPanel.textContent).toMatch(/วันหยุดทดสอบ/));

    // The personal leave panel still renders: the two "what's coming up" panels sit alongside each
    // other, never one in place of the other.
    const upcomingLeaveHeading = await screen.findByRole('heading', { name: 'วันลาที่กำลังจะถึง' });
    expect(upcomingLeaveHeading.closest('section')).not.toBeNull();
  });

  // Real วันหยุดบริษัท names run up to 149 chars (MOCK_HOLIDAY_DATES in mockApi.js is a verbatim copy of
  // production's hr.holiday, the reason V129 widened name_th from VARCHAR(120) to TEXT).
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
