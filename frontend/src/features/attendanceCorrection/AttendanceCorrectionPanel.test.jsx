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

const submittedRequest = {
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
};

describe('AttendanceCorrectionPanel — own request list', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("lists the employee's own past requests with status", async () => {
    api.attendanceCorrection.list.mockResolvedValue({
      requests: [{ ...submittedRequest, canReview: false }],
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

  // Split from the former "does not render the submit form for the CEO" (submit-flow) test --
  // the submit FORM moved to AttendanceCorrectionRequestModal.jsx
  // (fix/attendance-correction-on-attendance-page), so this panel never renders one for ANY
  // role any more. That made the old queryByLabelText(/วันที่ที่ลืมสแกน/) assertion pass
  // regardless of who was signed in -- a tautology, unable to fail (adversarial review N1) --
  // so it was dropped along with the rename. What this test actually proves: the review queue
  // (its own empty state) renders for the CEO.
  it('renders the review queue (empty state) for the CEO', async () => {
    api.attendanceCorrection.list.mockResolvedValue({ requests: [] });
    renderPanel(ceoUser);

    await screen.findByText('ยังไม่มีคำขอแก้ไขเวลา');
  });

  it('shows approve/reject for a SUBMITTED request and approves on confirm', async () => {
    api.attendanceCorrection.list.mockResolvedValue({
      requests: [{ ...submittedRequest, canReview: true }],
    });

    renderPanel(ceoUser);

    const approveButton = await screen.findByRole('button', { name: 'CEO อนุมัติ' });
    fireEvent.click(approveButton);
    fireEvent.click(screen.getByRole('button', { name: 'อนุมัติ' }));

    await waitFor(() => expect(api.attendanceCorrection.approve).toHaveBeenCalledWith(5, { reviewerNote: null }));
  });

  // Regression coverage for the deliberate invalidation change this branch makes
  // (fix/attendance-correction-on-attendance-page): approving now writes a real punch
  // (AttendanceCorrectionService#approve -> AttendanceDailyService#applyManualCorrection,
  // backend), and this list sits on the same page as the attendance day table now, so an
  // approval must invalidate BOTH query prefixes or the table above keeps showing stale data.
  it('invalidates both attendanceCorrection and attendance on approve', async () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    api.attendanceCorrection.list.mockResolvedValue({
      requests: [{ ...submittedRequest, canReview: true }],
    });

    render(
      <QueryClientProvider client={queryClient}>
        <AttendanceCorrectionPanel user={ceoUser} showToast={vi.fn()} />
      </QueryClientProvider>,
    );

    const approveButton = await screen.findByRole('button', { name: 'CEO อนุมัติ' });
    fireEvent.click(approveButton);
    fireEvent.click(screen.getByRole('button', { name: 'อนุมัติ' }));

    await waitFor(() => expect(api.attendanceCorrection.approve).toHaveBeenCalledTimes(1));
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['attendanceCorrection'] });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['attendance'] });
  });
});
