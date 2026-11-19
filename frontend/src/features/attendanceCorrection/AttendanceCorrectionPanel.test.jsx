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

// The section is collapsed by default for every role (owner, 2026-08-10) — it is a correction workflow, rare
// for an employee and batched for the CEO, and expanded it put a stat strip, a filter bar and a full table
// between the attendance table and the bottom of the page.
async function expandSection() {
  const header = await screen.findByRole('button', { name: /คำขอแก้ไขเวลาเข้า-ออกงาน/ });
  if (header.getAttribute('aria-expanded') === 'false') fireEvent.click(header);
  return header;
}

describe('AttendanceCorrectionPanel — CEO review affordance', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.attendanceCorrection.approve.mockResolvedValue({ request: { id: 5, status: 'APPROVED' } });
  });

  it('shows approve/reject for a SUBMITTED request and approves on confirm', async () => {
    api.attendanceCorrection.list.mockResolvedValue({
      requests: [{ ...submittedRequest, canReview: true }],
    });

    renderPanel(ceoUser);
    await expandSection();

    const approveButton = await screen.findByRole('button', { name: 'CEO อนุมัติ' });
    fireEvent.click(approveButton);
    fireEvent.click(screen.getByRole('button', { name: 'อนุมัติ' }));

    await waitFor(() => expect(api.attendanceCorrection.approve).toHaveBeenCalledWith(5, { reviewerNote: null }));
  });
});
