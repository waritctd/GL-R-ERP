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
    api.leave.create.mockResolvedValue({ request: { id: 2001, status: 'SUBMITTED' } });
  });

  it('blocks submit when start date is before today', async () => {
    renderLeavePage();

    const startInput = await screen.findByLabelText('วันที่เริ่ม');
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
    const startInput = await screen.findByLabelText('วันที่เริ่ม');
    fireEvent.change(startInput, { target: { value: futureDate } });
    fireEvent.change(screen.getByLabelText('เหตุผลการลา'), { target: { value: 'ทดสอบระบบ' } });
    fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));

    await waitFor(() => expect(api.leave.create).toHaveBeenCalledTimes(1));
    expect(api.leave.create).toHaveBeenCalledWith({
      employeeId: 1,
      leaveTypeCode: 'VACATION',
      startDate: futureDate,
      endDate: futureDate,
      reason: 'ทดสอบระบบ',
      attachmentName: null,
      attachmentUrl: null,
    });
  });
});
