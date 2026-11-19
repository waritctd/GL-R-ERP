import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ResetPasswordDialog } from './ResetPasswordDialog.jsx';

globalThis.React = React;

// Isolated component tests — no host page, no router, no react-query.

const employee = { id: 9, nameTh: 'สมชาย ใจดี', code: 'EMP009' };

describe('ResetPasswordDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('surfaces a rejected reset inline, stays on the confirm step, and re-enables the confirm button', async () => {
    const onConfirm = vi.fn().mockRejectedValue(new Error('ไม่มีสิทธิ์เข้าถึงรายการนี้'));
    render(<ResetPasswordDialog employee={employee} onConfirm={onConfirm} onClose={vi.fn()} />);

    fireEvent.click(screen.getByRole('button', { name: 'ตั้งรหัสผ่านชั่วคราว' }));

    expect(await screen.findByText('ไม่มีสิทธิ์เข้าถึงรายการนี้')).not.toBeNull();
    expect(screen.queryByTestId('temporary-password-value')).toBeNull();
    const confirmButton = screen.getByRole('button', { name: 'ตั้งรหัสผ่านชั่วคราว' });
    expect(confirmButton.disabled).toBe(false);
  });
});
