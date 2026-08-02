import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { LeavePage } from './LeavePage.jsx';
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
      approve: vi.fn(),
      reject: vi.fn(),
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

function renderLeavePage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <LeavePage user={user} currentEmployee={currentEmployee} showToast={vi.fn()} />
    </QueryClientProvider>,
  );
}

describe('LeavePage form validation', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.leave.employees.mockResolvedValue({
      employees: [{
        employeeId: 1,
        employeeName: 'พนักงาน ทดสอบ',
        employeeCode: 'GLR-001',
        self: true,
        directReport: false,
      }],
    });
    api.leave.types.mockResolvedValue({
      leaveTypes: [
        { code: 'VACATION', nameTh: 'ลาพักร้อน', nameEn: 'Vacation' },
      ],
    });
    api.leave.list.mockResolvedValue({ requests: [] });
    api.leave.balances.mockResolvedValue({ balances: [] });
    // Sub-day leave + paper-form contact block (2026-07-25): no address on file for this test
    // fixture, so autofill leaves every contact field blank -- lets the exact-payload assertion
    // below stay unchanged (empty -> null) without asserting on autofill itself.
    api.leave.contactDefaults.mockResolvedValue({
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
    });
    api.leave.create.mockResolvedValue({ request: { id: 2001, status: 'SUBMITTED' } });
  });

  it('blocks submit when start date is before today', async () => {
    renderLeavePage();

    const startInput = await screen.findByLabelText(/วันที่เริ่ม/);
    fireEvent.change(startInput, { target: { value: '2020-01-01' } });

    expect(await screen.findByText('วันที่เริ่มลาต้องไม่ก่อนวันนี้')).not.toBeNull();

    const submitButton = screen.getByRole('button', { name: /ส่งคำขอ/ });
    await waitFor(() => expect(submitButton.disabled).toBe(true));

    fireEvent.click(submitButton);

    expect(api.leave.create).not.toHaveBeenCalled();
  });

  it('sends the existing leave payload shape for a valid submit', async () => {
    renderLeavePage();

    // Use a far-future date so the startDateInPast rule never makes this test
    // time-dependent (a fixed near-future date would start failing once it passes).
    const futureDate = '2099-12-31';
    const startInput = await screen.findByLabelText(/วันที่เริ่ม/);
    fireEvent.change(startInput, { target: { value: futureDate } });
    fireEvent.change(screen.getByLabelText(/เหตุผลการลา/), { target: { value: 'ทดสอบระบบ' } });
    fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));

    await waitFor(() => expect(api.leave.create).toHaveBeenCalledTimes(1));
    // Sub-day leave + paper-form contact block (2026-07-25): the payload now always carries the
    // new keys; a whole-day submit with no address on file (see beforeEach) normalizes every one
    // of them to null.
    expect(api.leave.create).toHaveBeenCalledWith({
      employeeId: 1,
      leaveTypeCode: 'VACATION',
      startDate: futureDate,
      endDate: futureDate,
      reason: 'ทดสอบระบบ',
      startTime: null,
      endTime: null,
      contactHouseNo: null,
      contactSubdistrict: null,
      contactDistrict: null,
      contactProvince: null,
      contactPhone: null,
      // §5.2 purpose/emergency-filing (V125): only meaningful for PERSONAL -- a VACATION submit
      // always normalizes both to null/false regardless of form state.
      purposeCode: null,
      requestedAsEmergency: null,
      attachmentFile: null,
    });
  });

  it('toggling sub-day leave forces endDate to startDate and sends the chosen times', async () => {
    renderLeavePage();

    const futureDate = '2099-12-31';
    const startInput = await screen.findByLabelText(/วันที่เริ่ม/);
    fireEvent.change(startInput, { target: { value: futureDate } });
    fireEvent.change(screen.getByLabelText(/เหตุผลการลา/), { target: { value: 'หาหมอครึ่งวัน' } });

    fireEvent.click(screen.getByLabelText(/ลาบางส่วนของวัน/));

    const endInput = screen.getByLabelText(/วันที่สิ้นสุด/);
    expect(endInput.value).toBe(futureDate);

    fireEvent.change(screen.getByLabelText(/เวลาเริ่ม/), { target: { value: '08:30' } });
    fireEvent.change(screen.getByLabelText(/เวลาสิ้นสุด/), { target: { value: '12:30' } });

    fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));

    await waitFor(() => expect(api.leave.create).toHaveBeenCalledTimes(1));
    const payload = api.leave.create.mock.calls[0][0];
    expect(payload.startDate).toBe(futureDate);
    expect(payload.endDate).toBe(futureDate);
    expect(payload.startTime).toBe('08:30');
    expect(payload.endTime).toBe('12:30');
  });
});

describe('LeavePage balance grid divider (FIX 1, 2026-07 review)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.leave.employees.mockResolvedValue({
      employees: [{
        employeeId: 1,
        employeeName: 'พนักงาน ทดสอบ',
        employeeCode: 'GLR-001',
        self: true,
        directReport: false,
      }],
    });
    api.leave.types.mockResolvedValue({ leaveTypes: [] });
    api.leave.list.mockResolvedValue({ requests: [] });
    api.leave.contactDefaults.mockResolvedValue({ contactDefaults: {} });
    // The real schema has 4 leave types (SICK/VACATION/PERSONAL/LEAVE_WITHOUT_PAY --
    // see V85__leave_payroll_unpaid_deduction.sql), not the 3 the mock seeds. That gap
    // is exactly what hid the row-wrap divider bug: LEAVE_BALANCE_GRID is 3-column at
    // >=721px, so a 4th item wraps to row 2 column 1, and a `first-child` divider reset
    // only clears item 1 of the whole list, not item 1 of each row. Rendering 4 items
    // here proves the fix (`nth-child(3n+1)`) independently of the mock's item count.
    api.leave.balances.mockResolvedValue({
      balances: [
        { leaveTypeCode: 'PERSONAL', leaveTypeNameTh: 'ลากิจ', remainingDays: 7, approvedDays: 0, pendingDays: 0, annualQuotaDays: 7 },
        { leaveTypeCode: 'SICK', leaveTypeNameTh: 'ลาป่วย', remainingDays: 30, approvedDays: 0, pendingDays: 0, annualQuotaDays: 30 },
        { leaveTypeCode: 'VACATION', leaveTypeNameTh: 'ลาพักร้อน', remainingDays: 6, approvedDays: 0, pendingDays: 0, annualQuotaDays: 6 },
        { leaveTypeCode: 'LEAVE_WITHOUT_PAY', leaveTypeNameTh: 'ลาไม่รับค่าจ้าง', remainingDays: 0, approvedDays: 0, pendingDays: 0, annualQuotaDays: 0 },
      ],
    });
  });

  it('resets the divider per grid row (nth-child(3n+1)), not just the first item of the whole list', async () => {
    renderLeavePage();

    await screen.findByText('ลาไม่รับค่าจ้าง');

    const items = screen.getByText('ลากิจ').closest('.grid.min-w-0').parentElement.children;
    expect(items).toHaveLength(4);

    // Item 1 and item 4 both start a new grid row at >=721px (positions 1 and 4 of a
    // 3-column grid) and must both carry the row-start reset. Items 2 and 3 must not.
    const classNames = [...items].map((el) => el.className);
    expect(classNames[0]).toMatch(/nth-child\(3n\+1\)/);
    expect(classNames[3]).toMatch(/nth-child\(3n\+1\)/);
    // Guard against regressing to a `first:` position-based reset, which only ever
    // matches item 1 and would leave item 4's divider unclosed.
    for (const className of classNames) {
      expect(className).not.toMatch(/min-\[721px\]:first:border-l-0/);
    }
  });
});
