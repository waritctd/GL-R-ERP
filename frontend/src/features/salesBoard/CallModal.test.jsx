import React from 'react';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { afterAll, describe, expect, it, vi } from 'vitest';
import { CallModal } from './CallModal.jsx';

globalThis.React = React;

// Tuesday 17 Nov 2026, 09:00 Bangkok.
vi.setSystemTime(new Date('2026-11-17T02:00:00Z'));
afterAll(() => vi.useRealTimers());

const deal = { ticketId: 31, title: 'โรงแรมริมทะเล · ห้องน้ำชั้น 3' };

function open(onSubmit = vi.fn(), props = {}) {
  render(<CallModal deal={deal} submitting={false} onClose={vi.fn()} onSubmit={onSubmit} {...props} />);
  return { dialog: screen.getByRole('dialog'), onSubmit };
}

// Slice 6.4: โทรแล้ว · ตามครั้งถัดไป (prototype screen E).
describe('CallModal — ตามครั้งถัดไป', () => {
  it('a picked follow-up goes with the note as the second argument', () => {
    const { dialog, onSubmit } = open();
    fireEvent.click(within(dialog).getByRole('radio', { name: 'คุยแล้ว ยังสนใจ' }));
    fireEvent.click(within(dialog).getByRole('radio', { name: '1 สัปดาห์' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));

    expect(onSubmit.mock.calls[0]).toStrictEqual(['คุยแล้ว ยังสนใจ', '2026-11-24']);
  });

  it('a follow-up alone does not unlock saving — an outcome is still required (review M4)', () => {
    const { dialog } = open();
    fireEvent.click(within(dialog).getByRole('radio', { name: '3 วัน' }));
    expect(within(dialog).getByRole('button', { name: 'บันทึก' }).disabled).toBe(true);
  });
});
