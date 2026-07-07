import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { OvertimePage } from './OvertimePage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    overtime: {
      employees: vi.fn(),
      list: vi.fn(),
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

function renderOvertimePage() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <OvertimePage user={user} currentEmployee={currentEmployee} showToast={vi.fn()} />
    </QueryClientProvider>,
  );
}

describe('OvertimePage form validation', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.overtime.employees.mockResolvedValue({
      employees: [{
        employeeId: 1,
        employeeName: 'พนักงาน ทดสอบ',
        employeeCode: 'GLR-001',
        self: true,
        directReport: false,
      }],
    });
    api.overtime.list.mockResolvedValue({ requests: [] });
    api.overtime.create.mockResolvedValue({ request: { id: 1001 } });
  });

  it('blocks submit when planned end is not after planned start', async () => {
    renderOvertimePage();

    const startInput = await screen.findByLabelText('เริ่ม');
    const endInput = screen.getByLabelText('สิ้นสุด');
    const submitButton = screen.getByRole('button', { name: /ส่งคำขอ/ });

    fireEvent.change(startInput, { target: { value: '2026-07-07T20:00' } });
    fireEvent.change(endInput, { target: { value: '2026-07-07T18:00' } });

    expect(await screen.findByText('เวลาสิ้นสุดต้องอยู่หลังเวลาเริ่ม')).not.toBeNull();
    await waitFor(() => expect(submitButton.disabled).toBe(true));

    fireEvent.click(submitButton);

    expect(api.overtime.create).not.toHaveBeenCalled();
  });

  it('sends the existing overtime payload shape for a valid submit', async () => {
    renderOvertimePage();

    fireEvent.change(await screen.findByLabelText('วันที่ทำ OT'), { target: { value: '2026-07-07' } });
    fireEvent.change(screen.getByLabelText('เหตุผลความจำเป็น'), { target: { value: 'ทดสอบระบบ' } });
    fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));

    await waitFor(() => expect(api.overtime.create).toHaveBeenCalledTimes(1));
    expect(api.overtime.create).toHaveBeenCalledWith({
      employeeId: 1,
      workDate: '2026-07-07',
      plannedStartAt: '2026-07-07T18:00:00+07:00',
      plannedEndAt: '2026-07-07T20:00:00+07:00',
      dayType: 'WORKDAY',
      reason: 'ทดสอบระบบ',
    });
  });
});
