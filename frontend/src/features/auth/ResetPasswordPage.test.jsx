import React from 'react';
import {
  afterEach, beforeEach, describe, expect, it, vi,
} from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ResetPasswordPage } from './ResetPasswordPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      auth: {
        resetPassword: vi.fn(),
      },
    },
  };
});

function renderAt(path, props = {}) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <ResetPasswordPage onNavigateToLogin={vi.fn()} onNavigateToForgotPassword={vi.fn()} {...props} />
    </MemoryRouter>,
  );
}

describe('ResetPasswordPage', () => {
  beforeEach(() => {
    api.auth.resetPassword.mockReset();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  describe('no token in the URL', () => {
    it('shows the invalid/expired state immediately, without ever calling the API', () => {
      renderAt('/reset-password');

      expect(screen.getByTestId('reset-password-invalid')).not.toBeNull();
      expect(api.auth.resetPassword).not.toHaveBeenCalled();
    });

    it('offers a way to request a new link', () => {
      const onNavigateToForgotPassword = vi.fn();
      renderAt('/reset-password', { onNavigateToForgotPassword });

      fireEvent.click(screen.getByTestId('reset-password-request-new'));

      expect(onNavigateToForgotPassword).toHaveBeenCalledTimes(1);
    });
  });

  describe('happy path', () => {
    it('submits the token with the new password and shows a success confirmation', async () => {
      api.auth.resetPassword.mockResolvedValue({ message: 'ตั้งรหัสผ่านใหม่เรียบร้อยแล้ว' });
      renderAt('/reset-password?token=abc123');

      fireEvent.change(screen.getByTestId('reset-password-new'), { target: { value: 'BrandNewPass1!' } });
      fireEvent.change(screen.getByTestId('reset-password-confirm'), { target: { value: 'BrandNewPass1!' } });
      fireEvent.click(screen.getByTestId('reset-password-submit'));

      await waitFor(() => expect(api.auth.resetPassword).toHaveBeenCalledWith({
        token: 'abc123',
        newPassword: 'BrandNewPass1!',
      }));
      expect(await screen.findByTestId('reset-password-go-to-login')).not.toBeNull();
    });
  });

  describe('client-side validation', () => {
    it('blocks submit and shows an error when the confirmation does not match, without calling the API', () => {
      renderAt('/reset-password?token=abc123');

      fireEvent.change(screen.getByTestId('reset-password-new'), { target: { value: 'BrandNewPass1!' } });
      fireEvent.change(screen.getByTestId('reset-password-confirm'), { target: { value: 'SomethingElse1!' } });
      fireEvent.click(screen.getByTestId('reset-password-submit'));

      expect(screen.getByText('รหัสผ่านใหม่และการยืนยันไม่ตรงกัน')).not.toBeNull();
      expect(api.auth.resetPassword).not.toHaveBeenCalled();
    });

    it('blocks submit when the new password is under 8 characters', () => {
      renderAt('/reset-password?token=abc123');

      fireEvent.change(screen.getByTestId('reset-password-new'), { target: { value: 'short' } });
      fireEvent.change(screen.getByTestId('reset-password-confirm'), { target: { value: 'short' } });
      fireEvent.click(screen.getByTestId('reset-password-submit'));

      expect(screen.getByText('รหัสผ่านใหม่ต้องมีอย่างน้อย 8 ตัวอักษร')).not.toBeNull();
      expect(api.auth.resetPassword).not.toHaveBeenCalled();
    });
  });

  describe('invalid or expired token rejected by the server', () => {
    it('shows the server\'s own Thai message and a way back to request a new link', async () => {
      api.auth.resetPassword.mockRejectedValue(
        new Error('ลิงก์สำหรับตั้งรหัสผ่านใหม่ไม่ถูกต้องหรือหมดอายุแล้ว กรุณาขอลิงก์ใหม่อีกครั้ง'),
      );
      renderAt('/reset-password?token=stale-token');

      fireEvent.change(screen.getByTestId('reset-password-new'), { target: { value: 'BrandNewPass1!' } });
      fireEvent.change(screen.getByTestId('reset-password-confirm'), { target: { value: 'BrandNewPass1!' } });
      fireEvent.click(screen.getByTestId('reset-password-submit'));

      expect(await screen.findByText('ลิงก์สำหรับตั้งรหัสผ่านใหม่ไม่ถูกต้องหรือหมดอายุแล้ว กรุณาขอลิงก์ใหม่อีกครั้ง')).not.toBeNull();
      // Still on the form (not the success state) - a rejected token must not look like success.
      expect(screen.getByTestId('reset-password-submit')).not.toBeNull();
    });
  });
});
