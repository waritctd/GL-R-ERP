import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { UpcomingHolidays } from './UpcomingHolidays.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, api: { leave: { calendarContext: vi.fn() } } };
});

function renderPanel() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <UpcomingHolidays from="2026-08-08" to="2026-11-06" />
    </QueryClientProvider>,
  );
}

describe('UpcomingHolidays', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('distinguishes a genuinely empty range from a failed load -- they must not look alike', async () => {
    api.leave.calendarContext.mockResolvedValue({ calendarContext: { holidays: [] } });
    const { unmount } = renderPanel();
    expect(await screen.findByText('ยังไม่มีข้อมูลวันหยุด')).not.toBeNull();
    unmount();

    api.leave.calendarContext.mockRejectedValue(new Error('boom'));
    renderPanel();
    await waitFor(() => expect(screen.getByText('โหลดข้อมูลวันหยุดไม่สำเร็จ')).not.toBeNull());
  });
});
