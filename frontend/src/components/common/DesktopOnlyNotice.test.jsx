import React from 'react';
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { DesktopOnlyNotice } from './DesktopOnlyNotice.jsx';

globalThis.React = React;

describe('DesktopOnlyNotice', () => {
  it('renders the default bilingual message with role="note"', () => {
    render(<DesktopOnlyNotice />);

    const note = screen.getByRole('note');
    expect(note.textContent).toContain('หน้านี้ออกแบบมาสำหรับเดสก์ท็อป');
    expect(note.textContent).toContain('Optimized for desktop');
  });

  it('renders a custom message prop instead of the default text', () => {
    render(<DesktopOnlyNotice message="Custom notice" />);

    expect(screen.getByRole('note').textContent).toBe('Custom notice');
    expect(screen.queryByText(/Optimized for desktop/)).toBeNull();
  });

  it('renders custom children instead of the default text', () => {
    render(
      <DesktopOnlyNotice>
        <span>Children notice</span>
      </DesktopOnlyNotice>,
    );

    expect(screen.getByText('Children notice')).toBeTruthy();
    expect(screen.queryByText(/Optimized for desktop/)).toBeNull();
  });
});
