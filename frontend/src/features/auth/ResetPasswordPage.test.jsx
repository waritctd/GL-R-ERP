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
  });

});
