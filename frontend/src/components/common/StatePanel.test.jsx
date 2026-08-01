import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { StatePanel } from './StatePanel.jsx';

globalThis.React = React;

describe('StatePanel', () => {
  it('announces loading states with a contextual label', () => {
    render(
      <StatePanel
        state="loading"
        title="กำลังโหลดคำขอราคา"
        description="กำลังดึงรายละเอียดล่าสุด"
      />,
    );

    const panel = screen.getByRole('status');
    expect(panel.getAttribute('aria-busy')).toBe('true');
    expect(screen.getByText('กำลังโหลดคำขอราคา')).toBeTruthy();
    expect(screen.getByText('กำลังดึงรายละเอียดล่าสุด')).toBeTruthy();
  });

  it('renders recovery actions without stealing their accessible names', () => {
    const retry = vi.fn();
    render(
      <StatePanel
        state="error"
        title="โหลดข้อมูลไม่สำเร็จ"
        action={<button type="button" onClick={retry}>ลองใหม่</button>}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'ลองใหม่' }));
    expect(retry).toHaveBeenCalledTimes(1);
  });
});
