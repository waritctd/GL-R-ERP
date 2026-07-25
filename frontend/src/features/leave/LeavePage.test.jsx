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
