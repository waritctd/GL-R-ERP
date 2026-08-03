import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ReviewQueueTab } from './ReviewQueueTab.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({
  api: {
    leave: {
      list: vi.fn(),
      approve: vi.fn(),
      reject: vi.fn(),
      cancel: vi.fn(),
    },
  },
}));

const manager = { employeeId: 5, name: 'หัวหน้างาน', role: 'employee', manager: true };

const submittedUnderManager = {
  id: 701,
  employeeId: 1,
  employeeName: 'ลูกทีม หนึ่ง',
  employeeCode: 'GLR-001',
  managerEmployeeId: 5,
  leaveTypeCode: 'VACATION',
  leaveTypeNameTh: 'ลาพักร้อน',
  startDate: '2026-08-10',
  endDate: '2026-08-10',
  totalDays: 1,
  quotaRemainingAfter: 5,
  status: 'SUBMITTED',
  reason: 'พักผ่อน',
};

const approvedUnderManager = {
  id: 702,
  employeeId: 2,
  employeeName: 'ลูกทีม สอง',
  employeeCode: 'GLR-002',
  managerEmployeeId: 5,
  leaveTypeCode: 'SICK',
  leaveTypeNameTh: 'ลาป่วย',
  startDate: '2026-08-05',
  endDate: '2026-08-05',
  totalDays: 1,
  quotaRemainingAfter: 29,
  status: 'APPROVED',
  reason: 'ไม่สบาย',
};

const submittedUnderSomeoneElse = {
  id: 703,
  employeeId: 3,
  employeeName: 'พนักงานอื่น',
  employeeCode: 'GLR-003',
  managerEmployeeId: 999,
  leaveTypeCode: 'PERSONAL',
  leaveTypeNameTh: 'ลากิจ',
  startDate: '2026-08-12',
  endDate: '2026-08-12',
  totalDays: 1,
  quotaRemainingAfter: 6,
  status: 'SUBMITTED',
  reason: 'ธุระส่วนตัว',
};

function renderReviewQueueTab(showToast = vi.fn()) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <ReviewQueueTab user={manager} showToast={showToast} />
    </QueryClientProvider>,
  );
  return queryClient;
}

describe('ReviewQueueTab', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows only the requests this user may act on -- excludes another manager\'s report', async () => {
    api.leave.list.mockResolvedValue({
      requests: [submittedUnderManager, approvedUnderManager, submittedUnderSomeoneElse],
    });
    renderReviewQueueTab();

    expect(await screen.findByText('พักผ่อน')).not.toBeNull();
    expect(await screen.findByText('ไม่สบาย')).not.toBeNull();
    expect(screen.queryByText('ธุระส่วนตัว')).toBeNull();
  });

  it('approve mutates the correct id and shows a success toast, no inline error UI', async () => {
    const showToast = vi.fn();
    api.leave.list.mockResolvedValue({ requests: [submittedUnderManager] });
    api.leave.approve.mockResolvedValue({ request: { ...submittedUnderManager, status: 'APPROVED' } });
    renderReviewQueueTab(showToast);

    await screen.findByText('พักผ่อน');
    fireEvent.click(screen.getByRole('button', { name: 'อนุมัติ' }));
    // The row icon-button and the ConfirmDialog's confirm button share the label "อนุมัติ" --
    // the dialog's is the one that just appeared (last in the tree).
    const approveButtons = await screen.findAllByRole('button', { name: 'อนุมัติ' });
    fireEvent.click(approveButtons[approveButtons.length - 1]);

    await waitFor(() => expect(api.leave.approve).toHaveBeenCalledWith(701, {}));
    expect(showToast).toHaveBeenCalledWith('success', 'อนุมัติวันลาแล้ว');
  });

  it('reject requires a reason before it can be confirmed', async () => {
    api.leave.list.mockResolvedValue({ requests: [submittedUnderManager] });
    renderReviewQueueTab();

    await screen.findByText('พักผ่อน');
    fireEvent.click(screen.getByRole('button', { name: 'ปฏิเสธ' }));

    const confirmButton = screen.getByRole('button', { name: 'ปฏิเสธคำขอ' });
    expect(confirmButton.disabled).toBe(true);

    fireEvent.change(screen.getByLabelText('เหตุผลการปฏิเสธ'), { target: { value: 'เอกสารไม่ครบ' } });
    expect(confirmButton.disabled).toBe(false);
    fireEvent.click(confirmButton);

    await waitFor(() => expect(api.leave.reject).toHaveBeenCalledWith(701, { reviewerNote: 'เอกสารไม่ครบ' }));
  });

  it('cancel is offered for an already-APPROVED report even though approve/reject are not', async () => {
    api.leave.list.mockResolvedValue({ requests: [approvedUnderManager] });
    api.leave.cancel.mockResolvedValue({ request: { ...approvedUnderManager, status: 'CANCELLED' } });
    renderReviewQueueTab();

    await screen.findByText('ไม่สบาย');
    expect(screen.queryByRole('button', { name: 'อนุมัติ' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'ยกเลิก' }));
    fireEvent.click(screen.getByRole('button', { name: 'ยกเลิกคำขอ' }));

    await waitFor(() => expect(api.leave.cancel).toHaveBeenCalledWith(702, { reviewerNote: null }));
  });

  it('DEFECT (state split): zero actionable requests shows the empty StatePanel, not a spinner or blank screen', async () => {
    api.leave.list.mockResolvedValue({ requests: [submittedUnderSomeoneElse] });
    renderReviewQueueTab();

    expect(await screen.findByText('ไม่มีคำขอที่ต้องพิจารณาในตอนนี้')).not.toBeNull();
  });

  it('DEFECT (state split): a 403 renders the denied StatePanel, and no toast fires for the load failure', async () => {
    const showToast = vi.fn();
    api.leave.list.mockRejectedValue(Object.assign(new Error('ไม่มีสิทธิ์เข้าถึงรายการนี้'), { status: 403 }));
    renderReviewQueueTab(showToast);

    expect(await screen.findByText('ยังเปิดหน้านี้ไม่ได้')).not.toBeNull();
    expect(showToast).not.toHaveBeenCalled();
  });
});
