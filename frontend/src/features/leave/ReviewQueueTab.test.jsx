import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import {
  afterEach, beforeEach, describe, expect, it, vi,
} from 'vitest';
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
      downloadAttachment: vi.fn(),
    },
  },
}));

vi.mock('../../utils/download.js', () => ({
  downloadBlob: vi.fn(),
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

// `actingUser` overrides the default division-manager persona -- only the pending-approver-note tests need it
// (an hr actor sees a different note than the manager the row is waiting on).
function renderReviewQueueTab(actingUser = manager, showToast = vi.fn()) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <ReviewQueueTab user={actingUser} showToast={showToast} />
      </QueryClientProvider>
    </MemoryRouter>,
  );
  return queryClient;
}

// DataTable renders `mobileCard` only when useIsMobile() is true, and that hook reads window.matchMedia --
// unimplemented in jsdom, so it returns false and the DESKTOP table renders. Confirmed by mutation: with the
// card's gate forced permanently true, the unstubbed version of this test stayed green.
const originalMatchMedia = window.matchMedia;

describe('ReviewQueueTab', () => {
  afterEach(() => {
    if (originalMatchMedia) window.matchMedia = originalMatchMedia;
    else delete window.matchMedia;
  });

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
    renderReviewQueueTab(manager, showToast);

    await screen.findByText('พักผ่อน');
    fireEvent.click(screen.getByRole('button', { name: 'อนุมัติ' }));
    // The row icon-button and the ConfirmDialog's confirm button share the label "อนุมัติ" -- the dialog's is the
    // one that just appeared (last in the tree).
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

  describe('Phase A4: 409 already-decided', () => {
    it('an approve 409 replaces the transient toast with an inline, force-expanded explanation and refetches the row', async () => {
      const showToast = vi.fn();
      api.leave.list
        .mockResolvedValueOnce({ requests: [submittedUnderManager] })
        .mockResolvedValueOnce({ requests: [{ ...submittedUnderManager, status: 'APPROVED' }] });
      api.leave.approve.mockRejectedValue(Object.assign(new Error('คำขอลานี้ได้รับการพิจารณาไปแล้ว'), { status: 409 }));
      renderReviewQueueTab(manager, showToast);

      await screen.findByText('พักผ่อน');
      fireEvent.click(screen.getByRole('button', { name: 'อนุมัติ' }));
      const approveButtons = await screen.findAllByRole('button', { name: 'อนุมัติ' });
      fireEvent.click(approveButtons[approveButtons.length - 1]);

      expect(await screen.findByText('คำขอนี้ถูกพิจารณาไปแล้ว')).not.toBeNull();
      // The row's real, refetched status appears in the inline panel -- not a generic message.
      expect(await screen.findByText(/สถานะปัจจุบัน:.*อนุมัติแล้ว/)).not.toBeNull();
      // No transient toast for this case -- only the inline panel.
      expect(showToast).not.toHaveBeenCalled();
      // list() is called again (query invalidation) so the row can show its real current status.
      await waitFor(() => expect(api.leave.list).toHaveBeenCalledTimes(2));
    });
  });

});
