import React from 'react';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ProfileRequestsPage } from './ProfileRequestsPage.jsx';

globalThis.React = React;

const profileRequests = [
  {
    id: 1,
    employeeId: 10,
    employee: { id: 10, nameTh: 'สมชาย ใจดี', code: 'GLR-010' },
    fieldKey: 'phone',
    fieldLabel: 'เบอร์โทรศัพท์',
    oldValue: '081-000-0000',
    newValue: '081-111-1111',
    requestedAt: '2026-07-01',
    status: 'pending',
  },
];

function renderPage(overrides = {}) {
  const onReview = overrides.onReview ?? vi.fn().mockResolvedValue({});
  render(
    <ProfileRequestsPage
      profileRequests={overrides.profileRequests ?? profileRequests}
      onReview={onReview}
      showToast={overrides.showToast ?? vi.fn()}
    />,
  );
  return { onReview };
}

describe('ProfileRequestsPage confirmation dialogs', () => {
  it('opens a confirmation dialog for reject and does not call onReview until confirmed', () => {
    const { onReview } = renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'ปฏิเสธ' }));

    expect(screen.getByText('ปฏิเสธคำขอแก้ไขข้อมูล')).not.toBeNull();
    expect(onReview).not.toHaveBeenCalled();
  });

  it('blocks reject confirmation until a reason is entered', () => {
    renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'ปฏิเสธ' }));
    const confirmButton = screen.getByRole('button', { name: 'ปฏิเสธคำขอ' });
    expect(confirmButton.disabled).toBe(true);

    fireEvent.change(screen.getByLabelText('เหตุผลการปฏิเสธ'), { target: { value: 'ข้อมูลไม่ถูกต้อง' } });
    expect(confirmButton.disabled).toBe(false);
  });

  it('confirming reject calls onReview with id, status, and the trimmed reason', async () => {
    const { onReview } = renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'ปฏิเสธ' }));
    fireEvent.change(screen.getByLabelText('เหตุผลการปฏิเสธ'), { target: { value: '  ข้อมูลไม่ถูกต้อง  ' } });
    fireEvent.click(screen.getByRole('button', { name: 'ปฏิเสธคำขอ' }));

    await waitFor(() => expect(onReview).toHaveBeenCalledWith(1, 'rejected', 'ข้อมูลไม่ถูกต้อง'));
  });

  it('opens a confirmation dialog for approve and confirming calls onReview with approved', async () => {
    const { onReview } = renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'อนุมัติ' }));
    expect(screen.getByText('ยืนยันการอนุมัติคำขอแก้ไขข้อมูล')).not.toBeNull();
    expect(onReview).not.toHaveBeenCalled();

    const dialog = screen.getByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'อนุมัติ' }));

    await waitFor(() => expect(onReview).toHaveBeenCalledWith(1, 'approved'));
  });

  it('cancel aborts the dialog without calling onReview', () => {
    const { onReview } = renderPage();

    fireEvent.click(screen.getByRole('button', { name: 'ปฏิเสธ' }));
    fireEvent.click(screen.getByRole('button', { name: 'ยกเลิก' }));

    expect(screen.queryByText('ปฏิเสธคำขอแก้ไขข้อมูล')).toBeNull();
    expect(onReview).not.toHaveBeenCalled();
  });

  it('keeps the reject dialog open, shows an error toast, and preserves the typed reason when onReview fails', async () => {
    const onReview = vi.fn().mockRejectedValue(new Error('Profile request has already been reviewed'));
    const showToast = vi.fn();
    renderPage({ onReview, showToast });

    fireEvent.click(screen.getByRole('button', { name: 'ปฏิเสธ' }));
    fireEvent.change(screen.getByLabelText('เหตุผลการปฏิเสธ'), { target: { value: 'ข้อมูลไม่ถูกต้อง' } });
    fireEvent.click(screen.getByRole('button', { name: 'ปฏิเสธคำขอ' }));

    await waitFor(() =>
      expect(showToast).toHaveBeenCalledWith('error', 'Profile request has already been reviewed'),
    );

    expect(screen.getByText('ปฏิเสธคำขอแก้ไขข้อมูล')).not.toBeNull();
    expect(screen.getByLabelText('เหตุผลการปฏิเสธ').value).toBe('ข้อมูลไม่ถูกต้อง');
  });
});
