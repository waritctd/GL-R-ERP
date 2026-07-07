import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ChangePasswordModal } from './ChangePasswordModal.jsx';

globalThis.React = React;

function renderModal(props = {}) {
  const onSubmit = props.onSubmit ?? vi.fn().mockResolvedValue(undefined);
  const onClose = props.onClose ?? vi.fn();
  return {
    onSubmit,
    onClose,
    ...render(
      <ChangePasswordModal
        loading={false}
        onSubmit={onSubmit}
        onClose={onClose}
        onLogout={vi.fn()}
        {...props}
      />,
    ),
  };
}

describe('ChangePasswordModal form validation', () => {
  it('blocks submit and shows the too-short message when new password is under 8 characters', async () => {
    const { onSubmit } = renderModal();

    fireEvent.change(screen.getByLabelText('รหัสผ่านปัจจุบัน'), { target: { value: 'oldpass123' } });
    fireEvent.change(screen.getByLabelText('รหัสผ่านใหม่'), { target: { value: 'short1' } });
    fireEvent.change(screen.getByLabelText('ยืนยันรหัสผ่านใหม่'), { target: { value: 'short1' } });

    expect(await screen.findByText('รหัสผ่านใหม่ต้องมีอย่างน้อย 8 ตัวอักษร')).not.toBeNull();

    const submitButton = screen.getByRole('button', { name: /บันทึกรหัสผ่าน/ });
    await waitFor(() => expect(submitButton.disabled).toBe(true));

    fireEvent.click(submitButton);

    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('blocks submit and shows the required message when current password is empty', async () => {
    const { onSubmit } = renderModal();

    // Leave รหัสผ่านปัจจุบัน empty; fill a valid, matching new/confirm password.
    fireEvent.change(screen.getByLabelText('รหัสผ่านใหม่'), { target: { value: 'newpass123' } });
    fireEvent.change(screen.getByLabelText('ยืนยันรหัสผ่านใหม่'), { target: { value: 'newpass123' } });

    fireEvent.click(screen.getByRole('button', { name: /บันทึกรหัสผ่าน/ }));

    // zod's required check blocks the submit and surfaces the inline message.
    expect(await screen.findByText('กรุณาระบุรหัสผ่านปัจจุบัน')).not.toBeNull();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('blocks submit and shows the mismatch message when confirm password does not match', async () => {
    const { onSubmit } = renderModal();

    fireEvent.change(screen.getByLabelText('รหัสผ่านปัจจุบัน'), { target: { value: 'oldpass123' } });
    fireEvent.change(screen.getByLabelText('รหัสผ่านใหม่'), { target: { value: 'newpass123' } });
    fireEvent.change(screen.getByLabelText('ยืนยันรหัสผ่านใหม่'), { target: { value: 'newpass456' } });

    expect(await screen.findByText('รหัสผ่านใหม่และการยืนยันไม่ตรงกัน')).not.toBeNull();

    const submitButton = screen.getByRole('button', { name: /บันทึกรหัสผ่าน/ });
    await waitFor(() => expect(submitButton.disabled).toBe(true));

    fireEvent.click(submitButton);

    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('sends the existing change-password payload shape for a valid submit', async () => {
    const { onSubmit } = renderModal();

    fireEvent.change(screen.getByLabelText('รหัสผ่านปัจจุบัน'), { target: { value: 'oldpass123' } });
    fireEvent.change(screen.getByLabelText('รหัสผ่านใหม่'), { target: { value: 'newpass123' } });
    fireEvent.change(screen.getByLabelText('ยืนยันรหัสผ่านใหม่'), { target: { value: 'newpass123' } });

    fireEvent.click(screen.getByRole('button', { name: /บันทึกรหัสผ่าน/ }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    expect(onSubmit).toHaveBeenCalledWith({
      currentPassword: 'oldpass123',
      newPassword: 'newpass123',
    });
  });
});
