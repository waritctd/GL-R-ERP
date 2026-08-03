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
    api.leave.employees.mockResolvedValue({
      employees: [{
        employeeId: 1, employeeName: 'พนักงาน ทดสอบ', employeeCode: 'GLR-001', self: true, directReport: false,
      }],
    });
    // The balance-preview select (Phase A2: decoupled from the removed submission form) seeds
    // its default from THIS list, not from the balances fixture -- every test below needs at
    // least VACATION here so that seeding effect has something to default to.
    api.leave.types.mockResolvedValue({ leaveTypes: [{ code: 'VACATION', nameTh: 'ลาพักร้อน', nameEn: 'Vacation' }] });
    api.leave.list.mockResolvedValue({ requests: [] });
    api.leave.contactDefaults.mockResolvedValue(emptyContactDefaults);
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
    api.leave.employees.mockResolvedValue({
      employees: [{
        employeeId: 1, employeeName: 'พนักงาน ทดสอบ', employeeCode: 'GLR-001', self: true, directReport: false,
      }],
    });
    api.leave.types.mockResolvedValue({ leaveTypes: [] });
    api.leave.balances.mockResolvedValue({ balances: [] });
    api.leave.contactDefaults.mockResolvedValue(emptyContactDefaults);
  });

  it('DEFECT 1: a background refetch (isFetching, not isPending) never blanks already-loaded rows into an empty state', async () => {
    let resolveSecondFetch;
    api.leave.list
      .mockResolvedValueOnce({ requests: [ownRow] })
      .mockImplementationOnce(() => new Promise((resolve) => { resolveSecondFetch = resolve; }));

    const queryClient = renderMyLeaveTab();
    await screen.findByText('พักผ่อนประจำปี');

    queryClient.refetchQueries({ queryKey: ['leave', 'list'] });
    await waitFor(() => expect(api.leave.list).toHaveBeenCalledTimes(2));
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
