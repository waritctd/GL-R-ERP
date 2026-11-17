import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { DaysBadge } from './TicketListPage.jsx';

globalThis.React = React;

// Review M5 / spec §7.5: the app has ONE meaning of "quiet" — the 14-day QuietRule served as deal.stale. The list's
// "อัปเดตล่าสุด" column still says how long ago the stage changed, but no longer paints its own 7-day warning.
describe('DaysBadge', () => {
  it('shows days since the stage changed without a 7-day warning', () => {
    const tenDaysAgo = new Date(Date.now() - 10 * 86_400_000).toISOString();
    const { container } = render(<DaysBadge stageUpdatedAt={tenDaysAgo} />);

    expect(screen.getByText(/10 วัน/)).toBeTruthy();
    expect(container.innerHTML).not.toContain('text-warning');
    expect(container.querySelector('svg')).toBeNull();
  });

  it('says วันนี้ for today and a dash when unknown', () => {
    const { rerender } = render(<DaysBadge stageUpdatedAt={new Date().toISOString()} />);
    expect(screen.getByText(/วันนี้/)).toBeTruthy();
    rerender(<DaysBadge stageUpdatedAt={null} />);
    expect(screen.getByText('-')).toBeTruthy();
  });
});
