import React from 'react';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { afterAll, describe, expect, it, vi } from 'vitest';
import { SampleSentModal } from './SampleSentModal.jsx';

globalThis.React = React;

// Tuesday 17 Nov 2026, 09:00 Bangkok.
vi.setSystemTime(new Date('2026-11-17T02:00:00Z'));
afterAll(() => vi.useRealTimers());

const quiet = { ticketId: 31, title: 'โรงแรมริมทะเล · ห้องน้ำชั้น 3', quiet: true, daysQuiet: 20 };
const returning = { ticketId: 33, title: 'คอนโดริมสวน อาคาร B', returning: true };
const fresh = { ticketId: 32, title: 'BMW Showroom เชียงใหม่', quiet: false };

function openPicker(props = {}) {
  const onSubmit = vi.fn();
  render(
    <SampleSentModal followUp={[quiet, returning]} board={[quiet, fresh]} submitting={false} onClose={vi.fn()}
      onSubmit={onSubmit} {...props} />,
  );
  return { dialog: within(screen.getByRole('dialog', { name: 'ส่งตัวอย่าง' })), onSubmit };
}

// Slice 6d (prototype item 6.7, owner answer 1): one sheet — pick the งาน · what was sent · follow up when.
describe('SampleSentModal — from วันนี้ของฉัน (the rep picks the งาน)', () => {
  it('saving needs a งาน and what was sent; blank text does not count', () => {
    const { dialog } = openPicker();
    const save = dialog.getByRole('button', { name: 'บันทึก' });
    expect(save.disabled).toBe(true);
    fireEvent.change(dialog.getByLabelText(/^งาน/), { target: { value: '32' } });
    expect(save.disabled).toBe(true);
    fireEvent.change(dialog.getByLabelText(/^ส่งอะไร/), { target: { value: '   ' } });
    expect(save.disabled).toBe(true);
    fireEvent.change(dialog.getByLabelText(/^ส่งอะไร/), { target: { value: 'ตัวอย่างกระเบื้อง Raku 3 สี' } });
    expect(save.disabled).toBe(false);
  });

  it('sends the picked งาน, the trimmed text, and no date when none was picked', () => {
    const { dialog, onSubmit } = openPicker();
    fireEvent.change(dialog.getByLabelText(/^งาน/), { target: { value: '32' } });
    fireEvent.change(dialog.getByLabelText(/^ส่งอะไร/), { target: { value: '  แคตตาล็อก Mutina  ' } });
    fireEvent.click(dialog.getByRole('button', { name: 'บันทึก' }));
    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(onSubmit.mock.calls[0]).toStrictEqual([fresh, 'แคตตาล็อก Mutina', undefined]);
  });
});
