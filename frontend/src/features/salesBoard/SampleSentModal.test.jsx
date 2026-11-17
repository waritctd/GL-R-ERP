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
  it('is one sheet with the three drawn fields, in order: งาน, ส่งอะไร, ตามต่อ', () => {
    const { dialog } = openPicker();
    const fields = [dialog.getByLabelText(/^งาน/), dialog.getByLabelText(/^ส่งอะไร/), dialog.getByRole('radiogroup', { name: 'ตามต่อ' })];
    for (let i = 1; i < fields.length; i += 1) {
      expect(fields[i - 1].compareDocumentPosition(fields[i]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    }
    expect(dialog.getByLabelText(/^ส่งอะไร/).getAttribute('maxlength')).toBe('2000');
  });

  it('lists ควรตามต่อ first, then the rep\'s other งาน, each once — the same groups the call picker uses', () => {
    const { dialog } = openPicker();
    const select = dialog.getByLabelText(/^งาน/);
    const groups = [...select.querySelectorAll('optgroup')].map((g) => [g.label, [...g.querySelectorAll('option')].map((o) => o.textContent)]);
    expect(groups).toEqual([
      ['ควรตามต่อ', ['โรงแรมริมทะเล · ห้องน้ำชั้น 3', 'คอนโดริมสวน อาคาร B']],
      ['งานอื่นของฉัน', ['BMW Showroom เชียงใหม่']],
    ]);
    expect(select.value).toBe('');
  });

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

  it('a picked ตามต่อ goes with it (the same FollowUpPicks as โทรแล้ว)', () => {
    const { dialog, onSubmit } = openPicker();
    fireEvent.change(dialog.getByLabelText(/^งาน/), { target: { value: '31' } });
    fireEvent.change(dialog.getByLabelText(/^ส่งอะไร/), { target: { value: 'ตัวอย่างไม้' } });
    fireEvent.click(dialog.getByRole('radio', { name: '1 สัปดาห์' }));
    fireEvent.click(dialog.getByRole('button', { name: 'บันทึก' }));
    expect(onSubmit.mock.calls[0]).toStrictEqual([quiet, 'ตัวอย่างไม้', '2026-11-24']);
  });

  it('a paused งาน coming back is not ACTIVE: no ตามต่อ is offered for it, and none is sent', () => {
    const { dialog, onSubmit } = openPicker();
    fireEvent.change(dialog.getByLabelText(/^งาน/), { target: { value: '31' } });
    fireEvent.click(dialog.getByRole('radio', { name: '3 วัน' }));
    fireEvent.change(dialog.getByLabelText(/^งาน/), { target: { value: '33' } });
    expect(dialog.queryByRole('radiogroup', { name: 'ตามต่อ' })).toBeNull();
    fireEvent.change(dialog.getByLabelText(/^ส่งอะไร/), { target: { value: 'ตัวอย่างหิน' } });
    fireEvent.click(dialog.getByRole('button', { name: 'บันทึก' }));
    expect(onSubmit.mock.calls[0]).toStrictEqual([returning, 'ตัวอย่างหิน', undefined]);
  });

  it('says งาน, never ดีล, and that saving takes the งาน out of ควรตามต่อ', () => {
    openPicker();
    const dialog = screen.getByRole('dialog', { name: 'ส่งตัวอย่าง' });
    expect(dialog.textContent).not.toContain('ดีล');
    expect(dialog.textContent).toContain('บันทึกแล้วงานนี้ออกจาก “ควรตามต่อ”');
  });

  it('while saving, บันทึก is disabled and says so', () => {
    const { dialog } = openPicker({ submitting: true });
    expect(dialog.getByRole('button', { name: 'กำลังบันทึก…' }).disabled).toBe(true);
  });
});

describe('SampleSentModal — from the งาน page (the งาน is fixed)', () => {
  it('names the งาน instead of a picker, and sends it', () => {
    const onSubmit = vi.fn();
    render(<SampleSentModal deal={quiet} submitting={false} onClose={vi.fn()} onSubmit={onSubmit} />);
    const dialog = within(screen.getByRole('dialog', { name: 'ส่งตัวอย่าง' }));
    expect(dialog.queryByLabelText(/^งาน/)).toBeNull();
    expect(screen.getByRole('dialog').textContent).toContain('โรงแรมริมทะเล · ห้องน้ำชั้น 3');
    fireEvent.change(dialog.getByLabelText(/^ส่งอะไร/), { target: { value: 'ตัวอย่างกระเบื้อง' } });
    fireEvent.click(dialog.getByRole('radio', { name: '2 สัปดาห์' }));
    fireEvent.click(dialog.getByRole('button', { name: 'บันทึก' }));
    expect(onSubmit.mock.calls[0]).toStrictEqual([quiet, 'ตัวอย่างกระเบื้อง', '2026-12-01']);
  });

  it('with followUpAllowed={false} there is no ตามต่อ', () => {
    render(<SampleSentModal deal={quiet} followUpAllowed={false} submitting={false} onClose={vi.fn()} onSubmit={vi.fn()} />);
    expect(screen.queryByRole('radiogroup', { name: 'ตามต่อ' })).toBeNull();
  });
});
