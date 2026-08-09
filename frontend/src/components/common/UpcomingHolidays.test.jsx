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

// The real 12 Aug 2026 value from production's hr.holiday, 101 characters. Not a stand-in: the
// whole reason this component truncates is that `name_th` holds the official Bank of Thailand
// RECORD rather than a label, and production reaches 149 chars (which is why V129 had to widen the
// column from VARCHAR(120) to TEXT). A tidy 12-char fixture here would let a regression through.
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

  // WHAT THIS FILE CAN AND CANNOT PROVE. The defect that prompted the truncation was a LAYOUT one,
  // measured in a real browser at 360px: a 101-char name wrapped onto its own line starting at
  // x=50 while short names stayed inline at x=132, and row heights ran 44/71/127px. **jsdom has no
  // layout engine** -- every getBoundingClientRect() here returns zeroes -- so no test in this file
  // can catch that alignment regressing. Same shape as jsdom being unable to see implicit form
  // submission. What IS testable, and what these tests pin, is the part that would silently lose
  // data: truncation must be presentational only, never a substring of the stored value.
  it('clamps for display but keeps the FULL official name in the DOM and the title attribute', async () => {
    api.leave.calendarContext.mockResolvedValue({
      calendarContext: { holidays: [{ holidayDate: '2026-08-12', nameTh: REAL_LONG_NAME }] },
    });
    renderPanel();

    const name = await screen.findByText(REAL_LONG_NAME);
    // The rendered text is the whole value -- the clamp is CSS, not a JS slice. If someone
    // "fixes" this by cutting the string, this fails and the data loss is caught. This is the
    // assertion that actually matters and it is unchanged.
    expect(name.textContent).toBe(REAL_LONG_NAME);
    expect(name.getAttribute('title')).toBe(REAL_LONG_NAME);

    // The clamp is now by LINE, not by `truncate` (2026-08-10). `truncate` is
    // `white-space: nowrap` + ellipsis, so it can only ever render ONE line whatever room the
    // column has: measured at 768px it showed 504px of this 551px name, cutting the last 8% --
    // "และวันแม่แห่งชาติ", the only part a reader recognises -- while looking complete. At 390px it
    // showed 158px of 551px, i.e. the generic royal-birthday opening that every such holiday
    // shares, so two different holidays rendered as the same string. A line clamp uses whatever
    // width is available instead, and the row stacks below 720px so the name gets the full card.
    //
    // Asserting the mechanism, not just "some clamp": a bare `overflow-hidden` would pass a
    // looser check while silently reverting to one-line behaviour.
    expect(name.className).toContain('[-webkit-line-clamp:2]');
    expect(name.className).toContain('mobile:[-webkit-line-clamp:3]');
    expect(name.className.split(/\s+/)).not.toContain('truncate');
  });

  it('does not repeat a "วันหยุดบริษัท" badge on every row -- the panel title already says so', async () => {
    api.leave.calendarContext.mockResolvedValue({
      calendarContext: {
        holidays: [
          { holidayDate: '2026-08-12', nameTh: REAL_LONG_NAME },
          { holidayDate: '2026-10-13', nameTh: 'วันนวมินทรมหาราช' },
          { holidayDate: '2026-10-23', nameTh: 'วันปิยมหาราช' },
        ],
      },
    });
    renderPanel();

    await screen.findByText('วันปิยมหาราช');
    // Every row in a panel titled "วันหยุดที่จะถึง" is a company holiday by construction, so a
    // per-row badge carried no information and cost a third of the row's width. The panel heading
    // still says it once.
    expect(screen.queryAllByText('วันหยุดบริษัท')).toHaveLength(0);
    expect(screen.getByText('วันหยุดที่จะถึง')).not.toBeNull();
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
