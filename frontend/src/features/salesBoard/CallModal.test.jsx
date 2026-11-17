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
  it('offers ตามครั้งถัดไป below the note with nothing picked', () => {
    const { dialog } = open();
    const group = within(dialog).getByRole('radiogroup', { name: 'ตามครั้งถัดไป' });
    for (const r of within(group).getAllByRole('radio')) expect(r.getAttribute('aria-checked')).toBe('false');
  });

  it('saving without a follow-up passes no date — the request stays what it was', () => {
    const { dialog, onSubmit } = open();
    fireEvent.click(within(dialog).getByRole('radio', { name: 'ไม่รับสาย' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));

    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(onSubmit.mock.calls[0]).toStrictEqual(['ไม่รับสาย', undefined]);
  });

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

  // Fix round 1 (M1): never offer a date the server will refuse — and the call itself still records as before.
  it('with followUpAllowed={false} there is no ตามครั้งถัดไป, and the call goes without a date', () => {
    const { dialog, onSubmit } = open(vi.fn(), { followUpAllowed: false });
    expect(within(dialog).queryByRole('radiogroup', { name: 'ตามครั้งถัดไป' })).toBeNull();
    fireEvent.click(within(dialog).getByRole('radio', { name: 'ไม่รับสาย' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'บันทึก' }));
    expect(onSubmit.mock.calls[0]).toStrictEqual(['ไม่รับสาย', undefined]);
  });

  it('keeps one primary action: บันทึก, beside ยกเลิก', () => {
    const { dialog } = open();
    expect(within(dialog).getAllByRole('button', { name: 'บันทึก' })).toHaveLength(1);
    expect(within(dialog).getByRole('button', { name: 'ยกเลิก' })).toBeTruthy();
  });
});
