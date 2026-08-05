import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AttendanceCorrectionPanel } from './AttendanceCorrectionPanel.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    attendanceCorrection: {
      list: vi.fn(),
      create: vi.fn(),
      approve: vi.fn(),
      reject: vi.fn(),
      cancel: vi.fn(),
    },
  },
}));

const employeeUser = {
  employeeId: 1,
  name: 'พนักงาน ทดสอบ',
  role: 'employee',
  manager: false,
};

const ceoUser = {
  employeeId: 99,
  name: 'ผู้บริหาร ทดสอบ',
  role: 'ceo',
  manager: false,
};

function todayIso() {
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Bangkok',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(new Date()).map((part) => [part.type, part.value]));
  return `${parts.year}-${parts.month}-${parts.day}`;
}

function renderPanel(user) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <AttendanceCorrectionPanel user={user} showToast={vi.fn()} />
    </QueryClientProvider>,
  );
}

describe('AttendanceCorrectionPanel — employee submit flow', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.attendanceCorrection.list.mockResolvedValue({ requests: [] });
    api.attendanceCorrection.create.mockResolvedValue({ request: { id: 1 } });
  });

  it('renders the submit form defaulted to today and CHECK_IN', async () => {
    renderPanel(employeeUser);

    const dateInput = await screen.findByLabelText(/วันที่ที่ลืมสแกน/);
    expect(dateInput.value).toBe(todayIso());
    expect(screen.getByLabelText(/เวลาเข้างานที่ถูกต้อง/)).not.toBeNull();
    expect(screen.queryByLabelText(/เวลาออกงานที่ถูกต้อง/)).toBeNull();
  });

  it('submits a CHECK_IN correction with the expected payload shape', async () => {
    renderPanel(employeeUser);
    const workDate = todayIso();

    fireEvent.change(await screen.findByLabelText(/วันที่ที่ลืมสแกน/), { target: { value: workDate } });
    fireEvent.change(screen.getByLabelText(/เวลาเข้างานที่ถูกต้อง/), { target: { value: `${workDate}T08:20` } });
    fireEvent.change(screen.getByLabelText(/เหตุผล/), { target: { value: 'ลืมสแกนนิ้วตอนเข้างาน' } });
    fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));

    await waitFor(() => expect(api.attendanceCorrection.create).toHaveBeenCalledTimes(1));
    expect(api.attendanceCorrection.create).toHaveBeenCalledWith({
      workDate,
      correctionType: 'CHECK_IN',
      requestedCheckIn: `${workDate}T08:20:00+07:00`,
      requestedCheckOut: null,
      reason: 'ลืมสแกนนิ้วตอนเข้างาน',
    });
  });

  it('switching to BOTH shows both time fields and requires both', async () => {
    renderPanel(employeeUser);

    fireEvent.change(await screen.findByLabelText(/รายการที่ต้องแก้ไข/), { target: { value: 'BOTH' } });

    expect(await screen.findByLabelText(/เวลาเข้างานที่ถูกต้อง/)).not.toBeNull();
    expect(screen.getByLabelText(/เวลาออกงานที่ถูกต้อง/)).not.toBeNull();
  });

  it('blocks submit when the reason is too short', async () => {
    renderPanel(employeeUser);

    fireEvent.change(await screen.findByLabelText(/เหตุผล/), { target: { value: 'ok' } });
    fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));

    await waitFor(() => expect(screen.getByText(/กรุณาระบุเหตุผลให้ชัดเจน/)).not.toBeNull());
    expect(api.attendanceCorrection.create).not.toHaveBeenCalled();
  });
});

describe('AttendanceCorrectionPanel — own request list', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("lists the employee's own past requests with status", async () => {
    api.attendanceCorrection.list.mockResolvedValue({
      requests: [{
        id: 5,
        employeeId: 1,
        employeeCode: 'GLR-001',
        employeeName: 'พนักงาน ทดสอบ',
        workDate: '2026-07-01',
        correctionType: 'CHECK_IN',
        requestedCheckIn: '2026-07-01T01:20:00Z',
        requestedCheckOut: null,
        reason: 'ลืมสแกนนิ้ว',
        status: 'SUBMITTED',
        canReview: false,
      }],
    });

    renderPanel(employeeUser);

    expect(await screen.findByText('ลืมสแกนนิ้ว')).not.toBeNull();
    // "รอ CEO" also appears as a status-filter <option>; scope to the status badge itself.
    expect(document.querySelector('.status-badge')?.textContent).toBe('รอ CEO');
  });
});

describe('AttendanceCorrectionPanel — CEO review affordance', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.attendanceCorrection.approve.mockResolvedValue({ request: { id: 5, status: 'APPROVED' } });
  });

  it('does not render the submit form for the CEO', async () => {
    api.attendanceCorrection.list.mockResolvedValue({ requests: [] });
    renderPanel(ceoUser);

    await screen.findByText('ยังไม่มีคำขอแก้ไขเวลา');
    expect(screen.queryByLabelText(/วันที่ที่ลืมสแกน/)).toBeNull();
  });

  it('shows approve/reject for a SUBMITTED request and approves on confirm', async () => {
    api.attendanceCorrection.list.mockResolvedValue({
      requests: [{
        id: 5,
        employeeId: 1,
        employeeCode: 'GLR-001',
        employeeName: 'พนักงาน ทดสอบ',
        workDate: '2026-07-01',
        correctionType: 'CHECK_IN',
        requestedCheckIn: '2026-07-01T01:20:00Z',
        requestedCheckOut: null,
        reason: 'ลืมสแกนนิ้ว',
        status: 'SUBMITTED',
        canReview: true,
      }],
    });

    renderPanel(ceoUser);

    const approveButton = await screen.findByRole('button', { name: 'CEO อนุมัติ' });
    fireEvent.click(approveButton);
    fireEvent.click(screen.getByRole('button', { name: 'อนุมัติ' }));

    await waitFor(() => expect(api.attendanceCorrection.approve).toHaveBeenCalledWith(5, { reviewerNote: null }));
  });
});
