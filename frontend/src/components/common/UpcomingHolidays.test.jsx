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

// The real 12 Aug 2026 value from production's hr.holiday (101 characters); a short fixture would let a regression through.
const REAL_LONG_NAME =
  'วันเฉลิมพระชนมพรรษาสมเด็จพระนางเจ้าสิริกิติ์ พระบรมราชินีนาถ พระบรมราชชนนีพันปีหลวง และวันแม่แห่งชาติ';

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

  it('clamps for display but keeps the FULL official name in the DOM and the title attribute', async () => {
    api.leave.calendarContext.mockResolvedValue({
      calendarContext: { holidays: [{ holidayDate: '2026-08-12', nameTh: REAL_LONG_NAME }] },
    });
    renderPanel();

    const name = await screen.findByText(REAL_LONG_NAME);
    // Truncation is CSS (line clamp), never a JS slice of the stored value.
    expect(name.textContent).toBe(REAL_LONG_NAME);
    expect(name.getAttribute('title')).toBe(REAL_LONG_NAME);
    expect(name.className).toContain('[-webkit-line-clamp:2]');
    expect(name.className.split(/\s+/)).not.toContain('truncate');
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
