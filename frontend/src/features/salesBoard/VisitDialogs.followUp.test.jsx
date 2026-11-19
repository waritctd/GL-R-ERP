import React from 'react';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { afterAll, describe, expect, it, vi } from 'vitest';
import { LogVisitModal, VisitDoneModal } from './VisitDialogs.jsx';

globalThis.React = React;

// Tuesday 17 Nov 2026, 09:00 Bangkok.
vi.setSystemTime(new Date('2026-11-17T02:00:00Z'));
afterAll(() => vi.useRealTimers());
const TODAY = '2026-11-17';

const visit = (over) => ({
  id: 2, ownerId: 6, ownerName: 'คุณสมหมาย', ticketId: 31, ticketTitle: 'โรงแรมริมทะเล', contactName: 'คุณวรา',
  plannedDate: TODAY, plannedTime: '14:00', status: 'PLANNED', doneAt: null, note: null, dealMovedToName: null,
  dealMoved: false, dealFollowUpOpen: true, ...over,
});
const deals = [{ ticketId: 31, title: 'โรงแรมริมทะเล' }, { ticketId: 32, title: 'คอนโดริมน้ำ' }];
const followUp = (dialog) => within(dialog).queryByRole('radiogroup', { name: 'ตามต่อ' });

// Slice 6.4: บันทึกเข้าพบ · ตามต่อ (prototype screen F). The date is the deal's, so the control shows only when the
// visit's activity lands on a deal — the same condition the server checks (VisitService / requireVisitFollowUpWritable).
describe('VisitDoneModal — ตามต่อ', () => {
  it('shows ตามต่อ, nothing picked, for a visit on a deal', () => {
    render(<VisitDoneModal visit={visit()} submitting={false} onClose={vi.fn()} onSubmit={vi.fn()} />);
    const group = followUp(screen.getByRole('dialog'));
    expect(group).not.toBeNull();
    for (const r of within(group).getAllByRole('radio')) expect(r.getAttribute('aria-checked')).toBe('false');
  });

  it('passes the picked date as the second argument, and nothing when none was picked', () => {
    const onSubmit = vi.fn();
    const { unmount } = render(<VisitDoneModal visit={visit()} submitting={false} onClose={vi.fn()} onSubmit={onSubmit} />);
    let dialog = screen.getByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));
    expect(onSubmit.mock.calls[0]).toStrictEqual([undefined, undefined]);
    unmount();

    render(<VisitDoneModal visit={visit()} submitting={false} onClose={vi.fn()} onSubmit={onSubmit} />);
    dialog = screen.getByRole('dialog');
    fireEvent.click(within(dialog).getByRole('radio', { name: 'ส่งตัวอย่าง' }));
    fireEvent.click(within(dialog).getByRole('radio', { name: '2 สัปดาห์' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));
    expect(onSubmit.mock.calls[1]).toStrictEqual(['ส่งตัวอย่าง', '2026-12-01']);
  });
});

describe('LogVisitModal — switching clears the date and the pill (fix round 1, M2)', () => {
  const a = visit({ id: 2, contactName: 'คุณวรา' });
  const b = visit({ id: 3, contactName: 'คุณอรุณ', ticketId: 32, ticketTitle: 'คอนโดริมน้ำ' });
  const checked = (dialog) => within(followUp(dialog)).getAllByRole('radio')
    .filter((r) => r.getAttribute('aria-checked') === 'true').map((r) => r.textContent);

  it('visit A → 1 สัปดาห์ → visit B: nothing checked, and B is logged without a date', () => {
    const onSubmit = vi.fn();
    render(<LogVisitModal todaysPlanned={[a, b]} deals={deals} submitting={false} onClose={vi.fn()} onSubmit={onSubmit} />);
    const dialog = screen.getByRole('dialog');
    fireEvent.click(within(followUp(dialog)).getByRole('radio', { name: '1 สัปดาห์' }));
    fireEvent.click(within(dialog).getByRole('radio', { name: /คุณอรุณ/ }));

    expect(checked(dialog)).toEqual([]);
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));
    expect(onSubmit.mock.calls[0][0]).toStrictEqual({ visit: b, note: undefined });
  });
});
