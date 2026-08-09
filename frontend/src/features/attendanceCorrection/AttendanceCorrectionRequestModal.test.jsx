import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AttendanceCorrectionRequestModal } from './AttendanceCorrectionRequestModal.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    attendanceCorrection: {
      create: vi.fn(),
    },
  },
}));

function todayIso() {
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Bangkok',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(new Date()).map((part) => [part.type, part.value]));
  return `${parts.year}-${parts.month}-${parts.day}`;
}

function renderModal({ onClose = vi.fn(), showToast = vi.fn() } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <AttendanceCorrectionRequestModal showToast={showToast} onClose={onClose} />
    </QueryClientProvider>,
  );
  return { onClose, showToast };
}

describe('AttendanceCorrectionRequestModal — submit flow', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.attendanceCorrection.create.mockResolvedValue({ request: { id: 1 } });
  });

  it('renders the submit form defaulted to today and CHECK_IN', async () => {
    renderModal();

    const dateInput = await screen.findByLabelText(/วันที่ที่ต้องการแก้ไข/);
    expect(dateInput.value).toBe(todayIso());
    expect(screen.getByLabelText(/เวลาเข้างานที่ถูกต้อง/)).not.toBeNull();
    expect(screen.queryByLabelText(/เวลาออกงานที่ถูกต้อง/)).toBeNull();
  });

  it('submits a CHECK_IN correction with the expected payload shape', async () => {
    renderModal();
    const workDate = todayIso();

    fireEvent.change(await screen.findByLabelText(/วันที่ที่ต้องการแก้ไข/), { target: { value: workDate } });
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
    renderModal();

    fireEvent.change(await screen.findByLabelText(/รายการที่ต้องแก้ไข/), { target: { value: 'BOTH' } });

    expect(await screen.findByLabelText(/เวลาเข้างานที่ถูกต้อง/)).not.toBeNull();
    expect(screen.getByLabelText(/เวลาออกงานที่ถูกต้อง/)).not.toBeNull();
  });

  it('blocks submit when the reason is too short', async () => {
    renderModal();

    fireEvent.change(await screen.findByLabelText(/เหตุผล/), { target: { value: 'ok' } });
    fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));

    await waitFor(() => expect(screen.getByText(/กรุณาระบุเหตุผลให้ชัดเจน/)).not.toBeNull());
    expect(api.attendanceCorrection.create).not.toHaveBeenCalled();
  });

  // New in this split (fix/attendance-correction-on-attendance-page): the old inline panel form
  // just reset itself in place after a successful create. As a modal, it must also close itself,
  // or the just-submitted form stays open with a stale toast as the only feedback.
  it('closes itself after a successful create', async () => {
    const { onClose } = renderModal();
    const workDate = todayIso();

    fireEvent.change(await screen.findByLabelText(/วันที่ที่ต้องการแก้ไข/), { target: { value: workDate } });
    fireEvent.change(screen.getByLabelText(/เวลาเข้างานที่ถูกต้อง/), { target: { value: `${workDate}T08:20` } });
    fireEvent.change(screen.getByLabelText(/เหตุผล/), { target: { value: 'ลืมสแกนนิ้วตอนเข้างาน' } });
    fireEvent.click(screen.getByRole('button', { name: /ส่งคำขอ/ }));

    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
  });
});
