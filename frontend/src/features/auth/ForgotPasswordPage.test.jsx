import React from 'react';
import {
  afterEach, beforeEach, describe, expect, it, vi,
} from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ForgotPasswordPage } from './ForgotPasswordPage.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      auth: {
        forgotPassword: vi.fn(),
      },
    },
  };
});

describe('ForgotPasswordPage', () => {
  beforeEach(() => {
    api.auth.forgotPassword.mockReset();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('sends the trimmed email and shows the generic confirmation on success', async () => {
    api.auth.forgotPassword.mockResolvedValue({
      message: 'หากอีเมลนี้มีอยู่ในระบบ เราได้ส่งลิงก์สำหรับตั้งรหัสผ่านใหม่ไปให้แล้ว กรุณาตรวจสอบกล่องอีเมลของคุณ',
    });
    const onNavigateToLogin = vi.fn();
    render(<ForgotPasswordPage onNavigateToLogin={onNavigateToLogin} />);

    fireEvent.change(screen.getByTestId('forgot-password-email'), {
      target: { value: '  someone@glr.co.th  ' },
    });
    fireEvent.click(screen.getByTestId('forgot-password-submit'));

    await waitFor(() => expect(api.auth.forgotPassword).toHaveBeenCalledWith({ email: 'someone@glr.co.th' }));
    expect(await screen.findByText(/ตรวจสอบกล่องอีเมลของคุณ/)).not.toBeNull();
    // The form is gone once the confirmation renders - there is no "try a different email"
    // affordance shown in place, only the way back to /login.
    expect(screen.queryByTestId('forgot-password-email')).toBeNull();
  });

  it('shows the SAME generic confirmation whether or not the address exists - there is no not-found branch', async () => {
    // The whole point of the endpoint's anti-enumeration contract is that a resolved promise
    // never distinguishes the two cases - this test proves the page has nothing to distinguish
    // even if it wanted to.
    const genericResponse = { message: 'หากอีเมลนี้มีอยู่ในระบบ เราได้ส่งลิงก์สำหรับตั้งรหัสผ่านใหม่ไปให้แล้ว กรุณาตรวจสอบกล่องอีเมลของคุณ' };
    api.auth.forgotPassword.mockResolvedValue(genericResponse);
    render(<ForgotPasswordPage onNavigateToLogin={vi.fn()} />);

    fireEvent.change(screen.getByTestId('forgot-password-email'), { target: { value: 'nobody@glr.co.th' } });
    fireEvent.click(screen.getByTestId('forgot-password-submit'));

    expect(await screen.findByText(genericResponse.message)).not.toBeNull();
  });

  it('shows a distinct retry message, not the confirmation, when the request itself fails', async () => {
    api.auth.forgotPassword.mockRejectedValue(new Error('network down'));
    render(<ForgotPasswordPage onNavigateToLogin={vi.fn()} />);

    fireEvent.change(screen.getByTestId('forgot-password-email'), { target: { value: 'someone@glr.co.th' } });
    fireEvent.click(screen.getByTestId('forgot-password-submit'));

    expect(await screen.findByText('ส่งคำขอไม่สำเร็จ กรุณาลองใหม่อีกครั้ง')).not.toBeNull();
    // Still on the form - a failed request must not show the "email sent" confirmation.
    expect(screen.getByTestId('forgot-password-email')).not.toBeNull();
  });

  it('navigates back to login from the "กลับไปหน้าเข้าสู่ระบบ" link', () => {
    const onNavigateToLogin = vi.fn();
    render(<ForgotPasswordPage onNavigateToLogin={onNavigateToLogin} />);

    fireEvent.click(screen.getByText('กลับไปหน้าเข้าสู่ระบบ'));

    expect(onNavigateToLogin).toHaveBeenCalledTimes(1);
  });
});
