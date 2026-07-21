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

// Dates are relative to "now" so these cases keep testing the rule rather than rotting into the past.
function isoDaysFromToday(days) {
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Bangkok',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(new Date()).map((part) => [part.type, part.value]));
  const date = new Date(`${parts.year}-${parts.month}-${parts.day}T00:00:00+07:00`);
  date.setUTCDate(date.getUTCDate() + days);
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Bangkok',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(date);
}

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

    const startInput = await screen.findByLabelText(/^เริ่ม/);
    const endInput = screen.getByLabelText(/สิ้นสุด/);
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
    const workDate = isoDaysFromToday(3);

    fireEvent.change(await screen.findByLabelText(/วันที่ทำ OT/), { target: { value: workDate } });
    fireEvent.change(screen.getByLabelText(/เหตุผลความจำเป็น/), { target: { value: 'ทดสอบระบบ' } });
    fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));

    await waitFor(() => expect(api.overtime.create).toHaveBeenCalledTimes(1));
    expect(api.overtime.create).toHaveBeenCalledWith({
      employeeId: 1,
      workDate,
      plannedStartAt: `${workDate}T18:00:00+07:00`,
      plannedEndAt: `${workDate}T20:00:00+07:00`,
      dayType: 'WORKDAY',
      reason: 'ทดสอบระบบ',
    });
  });

  // Advance notice was removed on CEO instruction. Same-day is now the default the form opens on.
  it('accepts a same-day request', async () => {
    renderOvertimePage();
    const today = isoDaysFromToday(0);

    expect((await screen.findByLabelText(/วันที่ทำ OT/)).value).toBe(today);
    fireEvent.change(screen.getByLabelText(/เหตุผลความจำเป็น/), { target: { value: 'ทดสอบระบบ' } });
    fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));

    await waitFor(() => expect(api.overtime.create).toHaveBeenCalledTimes(1));
    expect(api.overtime.create.mock.calls[0][0].workDate).toBe(today);
  });

  it('accepts a backdated request when the reason explains the delay', async () => {
    renderOvertimePage();
    const workDate = isoDaysFromToday(-3);

    fireEvent.change(await screen.findByLabelText(/วันที่ทำ OT/), { target: { value: workDate } });
    fireEvent.change(screen.getByLabelText(/เหตุผลความจำเป็น/), {
      target: { value: 'ลูกค้าเร่งงานด่วน ทำ OT แล้วยื่นย้อนหลังหลังเลิกกะ' },
    });
    fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));

    await waitFor(() => expect(api.overtime.create).toHaveBeenCalledTimes(1));
    expect(api.overtime.create.mock.calls[0][0].workDate).toBe(workDate);
  });

  it('blocks a backdated request whose reason is too short', async () => {
    renderOvertimePage();

    fireEvent.change(await screen.findByLabelText(/วันที่ทำ OT/), { target: { value: isoDaysFromToday(-3) } });
    fireEvent.change(screen.getByLabelText(/เหตุผลความจำเป็น/), { target: { value: 'OT' } });

    expect(await screen.findByText(/อย่างน้อย 20 ตัวอักษร/)).not.toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));

    expect(api.overtime.create).not.toHaveBeenCalled();
  });

  it('blocks a request backdated beyond the retroactive window', async () => {
    renderOvertimePage();

    fireEvent.change(await screen.findByLabelText(/วันที่ทำ OT/), { target: { value: isoDaysFromToday(-120) } });
    fireEvent.change(screen.getByLabelText(/เหตุผลความจำเป็น/), {
      target: { value: 'พบว่ายังไม่ได้เบิก OT ของกะเก่า จึงยื่นย้อนหลัง' },
    });

    expect(await screen.findByText(/ย้อนหลังได้ไม่เกิน 60 วัน/)).not.toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));

    expect(api.overtime.create).not.toHaveBeenCalled();
  });
});
