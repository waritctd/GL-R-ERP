import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ResetPasswordDialog } from './ResetPasswordDialog.jsx';

globalThis.React = React;

// Isolated component tests — no host page, no router, no react-query. EmployeeDetailPage.test.jsx
// covers the real wiring (HR-only visibility, the actual api.employees.resetPassword call,
// showToast never carrying the password); this file pins ResetPasswordDialog's own prop contract
// and internal state handling directly, so a break here is attributable to the dialog itself.

const employee = { id: 9, nameTh: 'สมชาย ใจดี', code: 'EMP009' };
const TEMP_PASSWORD = 'Kbn7RtWq3xZmDp';

describe('ResetPasswordDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows the confirm step first and calls neither callback on mount', () => {
    const onConfirm = vi.fn();
    const onClose = vi.fn();
    render(<ResetPasswordDialog employee={employee} onConfirm={onConfirm} onClose={onClose} />);

    expect(screen.getByText(/รหัสผ่านเดิมจะใช้ไม่ได้ทันที/)).not.toBeNull();
    expect(onConfirm).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.queryByTestId('temporary-password-value')).toBeNull();
  });

  it('calls onConfirm with no arguments — the caller binds the employee id, not this component', async () => {
    const onConfirm = vi.fn().mockResolvedValue(TEMP_PASSWORD);
    render(<ResetPasswordDialog employee={employee} onConfirm={onConfirm} onClose={vi.fn()} />);

    fireEvent.click(screen.getByRole('button', { name: 'ตั้งรหัสผ่านชั่วคราว' }));

    await screen.findByTestId('temporary-password-value');
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onConfirm).toHaveBeenCalledWith();
  });

  it('cancelling the confirm step calls onClose and never calls onConfirm', () => {
    const onConfirm = vi.fn();
    const onClose = vi.fn();
    render(<ResetPasswordDialog employee={employee} onConfirm={onConfirm} onClose={onClose} />);

    fireEvent.click(screen.getByRole('button', { name: 'ยกเลิก' }));

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('renders the resolved password as a <code> block, never an <input>', async () => {
    const onConfirm = vi.fn().mockResolvedValue(TEMP_PASSWORD);
    render(<ResetPasswordDialog employee={employee} onConfirm={onConfirm} onClose={vi.fn()} />);

    fireEvent.click(screen.getByRole('button', { name: 'ตั้งรหัสผ่านชั่วคราว' }));

    const value = await screen.findByTestId('temporary-password-value');
    expect(value.tagName).toBe('CODE');
    expect(value.textContent).toBe(TEMP_PASSWORD);
    // An <input> would invite the browser's own password manager to offer to save the value.
    expect(document.querySelector('input')).toBeNull();
  });

  it('dismissing the result step calls onClose', async () => {
    const onClose = vi.fn();
    const onConfirm = vi.fn().mockResolvedValue(TEMP_PASSWORD);
    render(<ResetPasswordDialog employee={employee} onConfirm={onConfirm} onClose={onClose} />);

    fireEvent.click(screen.getByRole('button', { name: 'ตั้งรหัสผ่านชั่วคราว' }));
    await screen.findByTestId('temporary-password-value');

    fireEvent.click(screen.getByRole('button', { name: 'เสร็จสิ้น' }));
    expect(onClose).toHaveBeenCalledTimes(1);
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

  it('falls back to a generic message when the rejected error carries none', async () => {
    const onConfirm = vi.fn().mockRejectedValue(new Error());
    render(<ResetPasswordDialog employee={employee} onConfirm={onConfirm} onClose={vi.fn()} />);

    fireEvent.click(screen.getByRole('button', { name: 'ตั้งรหัสผ่านชั่วคราว' }));

    expect(await screen.findByText('ตั้งรหัสผ่านชั่วคราวไม่สำเร็จ')).not.toBeNull();
  });

  it('guards a missing clipboard API (jsdom has none) without throwing, and offers a manual fallback', async () => {
    const onConfirm = vi.fn().mockResolvedValue(TEMP_PASSWORD);
    render(<ResetPasswordDialog employee={employee} onConfirm={onConfirm} onClose={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: 'ตั้งรหัสผ่านชั่วคราว' }));
    await screen.findByTestId('temporary-password-value');

    fireEvent.click(screen.getByRole('button', { name: 'คัดลอก' }));

    expect(await screen.findByText(/คัดลอกด้วยตนเอง/)).not.toBeNull();
    // The password itself is untouched by the failed copy attempt.
    expect(screen.getByTestId('temporary-password-value').textContent).toBe(TEMP_PASSWORD);
  });

  it('copies the exact password via navigator.clipboard.writeText and shows visible success feedback', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    const onConfirm = vi.fn().mockResolvedValue(TEMP_PASSWORD);
    render(<ResetPasswordDialog employee={employee} onConfirm={onConfirm} onClose={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: 'ตั้งรหัสผ่านชั่วคราว' }));
    await screen.findByTestId('temporary-password-value');

    fireEvent.click(screen.getByRole('button', { name: 'คัดลอก' }));

    expect(writeText).toHaveBeenCalledWith(TEMP_PASSWORD);
    expect(await screen.findByRole('button', { name: /คัดลอกแล้ว/ })).not.toBeNull();
  });
});
