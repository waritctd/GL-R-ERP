import React from 'react';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { FollowUpList } from './FollowUpList.jsx';

globalThis.React = React;

// ควรตามต่อ rows: a quiet ACTIVE deal, and (owner ruling 2026-11-16) a PAUSED deal whose วันที่จะกลับมาดู has arrived,
// labelled so the rep can tell it is a paused deal coming back rather than a silent active one.

const quiet = {
  ticketId: 1, title: 'งานเงียบ', customerName: 'ลูกค้า ก', boardColumn: 2, value: 1000, currency: 'THB',
  daysQuiet: 21, quiet: true, returning: false, pauseReturnOn: null,
};
const returning = {
  ticketId: 2, title: 'งานที่พักไว้', customerName: 'ลูกค้า ข', boardColumn: 1, value: null, currency: null,
  daysQuiet: 60, quiet: false, returning: true, pauseReturnOn: '2026-11-16',
};

function renderList(props = {}) {
  const handlers = { onLogCall: vi.fn(), onBookVisit: vi.fn(), onCloseDeal: vi.fn(), onResume: vi.fn() };
  render(
    <MemoryRouter>
      <FollowUpList deals={[quiet, returning]} quietDays={14} {...handlers} {...props} />
    </MemoryRouter>,
  );
  return handlers;
}

describe('FollowUpList', () => {
  it('a quiet deal keeps its three one-tap actions; ปิดงานนี้ (was ปิดดีลนี้) opens the lost dialog through onCloseDeal', () => {
    const { onCloseDeal } = renderList();
    const row = screen.getByTestId('follow-up-1');

    expect(within(row).getByText('เงียบ 21 วัน')).toBeTruthy();
    expect(within(row).getByRole('button', { name: /โทรแล้ว/ })).toBeTruthy();
    expect(within(row).getByRole('button', { name: /นัดเข้าพบ/ })).toBeTruthy();
    fireEvent.click(within(row).getByRole('button', { name: 'ปิดงานนี้' }));
    expect(onCloseDeal).toHaveBeenCalledWith(quiet);
  });

  it('a returning paused deal says so, offers ดำเนินการต่อ, and not the actions a paused deal cannot take', () => {
    const { onResume } = renderList();
    const row = screen.getByTestId('follow-up-2');

    expect(within(row).getByText('พักไว้ · ถึงวันกลับมาดู 16 พ.ย. 2569')).toBeTruthy();
    expect(within(row).queryByText(/เงียบ/)).toBeNull();
    // A paused deal cannot be marked lost or visited until it is resumed (TicketService requires ACTIVE).
    expect(within(row).queryByRole('button', { name: 'ปิดงานนี้' })).toBeNull();
    expect(within(row).queryByRole('button', { name: /นัดเข้าพบ/ })).toBeNull();
    fireEvent.click(within(row).getByRole('button', { name: 'ดำเนินการต่อ' }));
    expect(onResume).toHaveBeenCalledWith(returning);
  });
});
