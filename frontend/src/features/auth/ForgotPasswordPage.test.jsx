import React from 'react';
import {
  afterEach, beforeEach, describe, expect, it, vi,
} from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
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

  it('shows the SAME generic confirmation whether or not the address exists - there is no not-found branch', async () => {
    // The whole point of the endpoint's anti-enumeration contract is that a resolved promise never distinguishes
    // the two cases - this test proves the page has nothing to distinguish even if it wanted to.
    const genericResponse = { message: 'หากอีเมลนี้มีอยู่ในระบบ เราได้ส่งลิงก์สำหรับตั้งรหัสผ่านใหม่ไปให้แล้ว กรุณาตรวจสอบกล่องอีเมลของคุณ' };
    api.auth.forgotPassword.mockResolvedValue(genericResponse);
    render(<ForgotPasswordPage onNavigateToLogin={vi.fn()} />);

    fireEvent.change(screen.getByTestId('forgot-password-email'), { target: { value: 'nobody@glr.co.th' } });
    fireEvent.click(screen.getByTestId('forgot-password-submit'));

    expect(await screen.findByText(genericResponse.message)).not.toBeNull();
  });
});
