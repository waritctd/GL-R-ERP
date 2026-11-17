import React from 'react';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { afterAll, describe, expect, it, vi } from 'vitest';
import { LogVisitModal, PlanVisitModal, VisitDoneModal } from './VisitDialogs.jsx';

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

  it('has no ตามต่อ for a visit with no deal', () => {
    render(<VisitDoneModal visit={visit({ ticketId: null, ticketTitle: null })} submitting={false} onClose={vi.fn()}
      onSubmit={vi.fn()} />);
    expect(followUp(screen.getByRole('dialog'))).toBeNull();
  });

  it('has no ตามต่อ when the tick stays on the visit because the งาน is someone else\'s', () => {
    render(<VisitDoneModal visit={visit({ dealMovedToName: 'คุณอรุณี', dealMoved: true, dealFollowUpOpen: false })} submitting={false}
      onClose={vi.fn()} onSubmit={vi.fn()} />);
    expect(followUp(screen.getByRole('dialog'))).toBeNull();
  });

  // Fix round 1: the server's own answer (VisitDto.dealFollowUpOpen) — a deal paused after the visit was planned, or a
  // deal whose owner has no Thai name (dealMovedToName null), must not offer a date the server refuses.
  it('has no ตามต่อ when the server says the date is closed, even with dealMovedToName empty', () => {
    render(<VisitDoneModal visit={visit({ dealFollowUpOpen: false, dealMovedToName: null })} submitting={false}
      onClose={vi.fn()} onSubmit={vi.fn()} />);
    expect(followUp(screen.getByRole('dialog'))).toBeNull();
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

describe('LogVisitModal — ตามต่อ', () => {
  it('a planned visit on a deal shows ตามต่อ and sends the picked date with the tick', () => {
    const onSubmit = vi.fn();
    render(<LogVisitModal todaysPlanned={[visit()]} deals={deals} submitting={false} onClose={vi.fn()}
      onSubmit={onSubmit} />);
    const dialog = screen.getByRole('dialog');
    fireEvent.click(within(followUp(dialog)).getByRole('radio', { name: '3 วัน' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));

    expect(onSubmit.mock.calls[0][0]).toStrictEqual({ visit: visit(), note: undefined, nextFollowUpAt: '2026-11-20' });
  });

  it('ไม่ได้นัด without a deal has no ตามต่อ; picking a deal brings it; clearing the deal drops a picked date', () => {
    const onSubmit = vi.fn();
    render(<LogVisitModal todaysPlanned={[]} deals={deals} submitting={false} onClose={vi.fn()} onSubmit={onSubmit} />);
    const dialog = screen.getByRole('dialog');
    expect(followUp(dialog)).toBeNull();

    fireEvent.change(within(dialog).getByLabelText(/พบใคร/), { target: { value: 'Walk-in' } });
    fireEvent.change(within(dialog).getByLabelText(/^งาน/), { target: { value: '32' } });
    fireEvent.click(within(followUp(dialog)).getByRole('radio', { name: '1 สัปดาห์' }));
    fireEvent.change(within(dialog).getByLabelText(/^งาน/), { target: { value: '' } });
    expect(followUp(dialog)).toBeNull();
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));

    expect(onSubmit.mock.calls[0][0]).toStrictEqual({ contactName: 'Walk-in', ticketId: null, note: undefined });
  });

  it('ไม่ได้นัด on a deal sends the date with the one-step log', () => {
    const onSubmit = vi.fn();
    render(<LogVisitModal todaysPlanned={[]} deals={deals} submitting={false} onClose={vi.fn()} onSubmit={onSubmit} />);
    const dialog = screen.getByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText(/พบใคร/), { target: { value: 'Walk-in' } });
    fireEvent.change(within(dialog).getByLabelText(/^งาน/), { target: { value: '32' } });
    fireEvent.click(within(followUp(dialog)).getByRole('radio', { name: '1 สัปดาห์' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));

    expect(onSubmit.mock.calls[0][0]).toStrictEqual({
      contactName: 'Walk-in', ticketId: 32, note: undefined, nextFollowUpAt: '2026-11-24',
    });
  });

  it('a planned visit whose tick stays off the deal has no ตามต่อ, and its log carries no date', () => {
    const onSubmit = vi.fn();
    const moved = visit({ dealMovedToName: 'คุณอรุณี', dealMoved: true, dealFollowUpOpen: false });
    render(<LogVisitModal todaysPlanned={[moved]} deals={deals} submitting={false} onClose={vi.fn()}
      onSubmit={onSubmit} />);
    const dialog = screen.getByRole('dialog');
    expect(followUp(dialog)).toBeNull();
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));
    expect(onSubmit.mock.calls[0][0]).toStrictEqual({ visit: moved, note: undefined });
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

  it('visit B → 2 สัปดาห์ → visit A → back to B: still nothing checked, no date', () => {
    const onSubmit = vi.fn();
    render(<LogVisitModal todaysPlanned={[a, b]} deals={deals} submitting={false} onClose={vi.fn()} onSubmit={onSubmit} />);
    const dialog = screen.getByRole('dialog');
    fireEvent.click(within(dialog).getByRole('radio', { name: /คุณอรุณ/ }));
    fireEvent.click(within(followUp(dialog)).getByRole('radio', { name: '2 สัปดาห์' }));
    fireEvent.click(within(dialog).getByRole('radio', { name: /คุณวรา/ }));
    expect(checked(dialog)).toEqual([]);
    fireEvent.click(within(dialog).getByRole('radio', { name: /คุณอรุณ/ }));
    expect(checked(dialog)).toEqual([]);
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));
    expect(onSubmit.mock.calls[0][0]).toStrictEqual({ visit: b, note: undefined });
  });

  it('ไม่ได้นัด on deal 31 → 1 สัปดาห์ → deal 32: nothing checked, no date', () => {
    const onSubmit = vi.fn();
    render(<LogVisitModal todaysPlanned={[]} deals={deals} submitting={false} onClose={vi.fn()} onSubmit={onSubmit} />);
    const dialog = screen.getByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText(/พบใคร/), { target: { value: 'Walk-in' } });
    fireEvent.change(within(dialog).getByLabelText(/^งาน/), { target: { value: '31' } });
    fireEvent.click(within(followUp(dialog)).getByRole('radio', { name: '1 สัปดาห์' }));
    fireEvent.change(within(dialog).getByLabelText(/^งาน/), { target: { value: '32' } });

    expect(checked(dialog)).toEqual([]);
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));
    expect(onSubmit.mock.calls[0][0]).toStrictEqual({ contactName: 'Walk-in', ticketId: 32, note: undefined });
  });
});

describe('PlanVisitModal', () => {
  it('has no ตามต่อ — a plan is not a visit that happened, and the server refuses a date on one', () => {
    render(<PlanVisitModal deals={deals} initialDeal={deals[0]} todayIso={TODAY} submitting={false} onClose={vi.fn()}
      onSubmit={vi.fn()} />);
    expect(followUp(screen.getByRole('dialog'))).toBeNull();
    expect(screen.queryByRole('radiogroup', { name: 'ตามครั้งถัดไป' })).toBeNull();
  });
});
