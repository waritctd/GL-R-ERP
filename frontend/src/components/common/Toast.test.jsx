import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { Toast } from './Toast.jsx';

globalThis.React = React;

describe('Toast', () => {
  it('announces success politely while preserving dismiss button semantics', () => {
    const onDismiss = vi.fn();

    render(<Toast toast={{ kind: 'success', message: 'บันทึกแล้ว' }} onDismiss={onDismiss} />);

    const region = screen.getByRole('status');
    expect(region.getAttribute('aria-live')).toBe('polite');

    fireEvent.click(screen.getByRole('button', { name: 'ปิดข้อความ: บันทึกแล้ว' }));
    expect(onDismiss).toHaveBeenCalledTimes(1);
  });

  it('announces error assertively while preserving dismiss button semantics', () => {
    render(<Toast toast={{ kind: 'error', message: 'บันทึกไม่สำเร็จ' }} onDismiss={vi.fn()} />);

    const region = screen.getByRole('alert');
    expect(region.getAttribute('aria-live')).toBe('assertive');
    expect(screen.getByRole('button', { name: 'ปิดข้อความ: บันทึกไม่สำเร็จ' })).toBeTruthy();
  });
});
