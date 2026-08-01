import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ConfirmDialog } from './ConfirmDialog.jsx';

globalThis.React = React;

describe('ConfirmDialog', () => {
  it('renders title and message, and fires onConfirm', () => {
    const onConfirm = vi.fn();
    render(
      <ConfirmDialog
        open
        title="ยืนยันการลบ"
        message="คุณต้องการลบรายการนี้หรือไม่?"
        onConfirm={onConfirm}
        onCancel={() => {}}
      />,
    );

    expect(screen.getByText('ยืนยันการลบ')).toBeTruthy();
    expect(screen.getByText('คุณต้องการลบรายการนี้หรือไม่?')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'ยืนยัน' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
  });

  it('requires a non-empty reason before confirming when requireReason is set', () => {
    const onConfirm = vi.fn();
    render(
      <ConfirmDialog
        open
        title="ปฏิเสธคำขอ"
        message="กรุณาระบุเหตุผล"
        requireReason
        onConfirm={onConfirm}
        onCancel={() => {}}
      />,
    );

    const confirmButton = screen.getByRole('button', { name: 'ยืนยัน' });
    expect(confirmButton.disabled).toBe(true);

    fireEvent.change(screen.getByLabelText('เหตุผล'), { target: { value: 'ข้อมูลไม่ถูกต้อง' } });
    expect(confirmButton.disabled).toBe(false);

    fireEvent.click(confirmButton);
    expect(onConfirm).toHaveBeenCalledWith('ข้อมูลไม่ถูกต้อง');
  });

  it('can require a specific typed confirmation phrase', () => {
    const onConfirm = vi.fn();
    render(
      <ConfirmDialog
        open
        title="ประมวลผลเงินเดือน"
        message="ยืนยันการทำรายการ"
        requireReason
        reasonLabel="พิมพ์คำยืนยัน"
        validateReason={(value) => value === 'ประมวลผล'}
        reasonInvalidMessage="พิมพ์คำว่า ประมวลผล"
        onConfirm={onConfirm}
        onCancel={() => {}}
      />,
    );

    const confirmButton = screen.getByRole('button', { name: 'ยืนยัน' });
    const phrase = screen.getByLabelText('พิมพ์คำยืนยัน');

    fireEvent.change(phrase, { target: { value: 'ยืนยัน' } });
    expect(confirmButton.disabled).toBe(true);
    expect(screen.getByText('พิมพ์คำว่า ประมวลผล')).toBeTruthy();

    fireEvent.change(phrase, { target: { value: 'ประมวลผล' } });
    expect(confirmButton.disabled).toBe(false);

    fireEvent.click(confirmButton);
    expect(onConfirm).toHaveBeenCalledWith('ประมวลผล');
  });

  it('renders nothing when closed', () => {
    const { container } = render(
      <ConfirmDialog open={false} title="x" message="y" onConfirm={() => {}} onCancel={() => {}} />,
    );
    expect(container.innerHTML).toBe('');
  });
});
