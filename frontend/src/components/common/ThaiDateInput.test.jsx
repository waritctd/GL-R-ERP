import React, { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ThaiDateInput } from './ThaiDateInput.jsx';
import { Modal } from './Modal.jsx';

globalThis.React = React;

// The value contract is the native one — '' or an ISO 'YYYY-MM-DD' (ค.ศ.), what the API stores —
// so every call site keeps its state and payloads. Only what the user SEES and TYPES is พ.ศ.
function Controlled({ initial = '', onValue = () => {}, ...rest }) {
  const [value, setValue] = useState(initial);
  return (
    <>
      <ThaiDateInput
        id="d" aria-label="วันที่" value={value} {...rest}
        onChange={(e) => { setValue(e.target.value); onValue(e.target.value); }}
      />
      <button type="button" onClick={() => setValue(initial)}>reset</button>
    </>
  );
}

const field = () => screen.getByLabelText('วันที่');

describe('ThaiDateInput', () => {
  it('shows a stored ISO date as dd/mm/yyyy with a พ.ศ. year', () => {
    render(<Controlled initial="2027-01-31" />);
    expect(field().value).toBe('31/01/2570');
  });

  it('typing a พ.ศ. date reports the ค.ศ. ISO value (the 31/01/2570 → 3113 bug)', () => {
    const onValue = vi.fn();
    render(<Controlled onValue={onValue} />);
    fireEvent.change(field(), { target: { value: '31/01/2570' } });
    expect(onValue).toHaveBeenLastCalledWith('2027-01-31');
  });

  // Review #1: an invalid edit must CLEAR the value, not leave the previous date in place — else
  // required/zod pass and the old date is saved silently (e.g. วันที่ใบกำกับ defaulting to today).
  it('rejects a ค.ศ. year typed over a valid date: value clears, typed text stays, error shows', () => {
    const onValue = vi.fn();
    render(<Controlled initial="2026-11-03" onValue={onValue} />);
    fireEvent.change(field(), { target: { value: '31/01/2027' } });
    expect(onValue).not.toHaveBeenCalledWith(expect.stringMatching(/^\d{4}-/));
    expect(onValue).toHaveBeenLastCalledWith('');
    expect(field().value).toBe('31/01/2027');
    expect(field().getAttribute('aria-invalid')).toBe('true');
    expect(screen.getByText(/ปี พ\.ศ\./)).not.toBeNull();
  });

  it('rejects an impossible day (31/02) and does not report a date', () => {
    const onValue = vi.fn();
    render(<Controlled onValue={onValue} />);
    fireEvent.change(field(), { target: { value: '31/02/2570' } });
    expect(onValue).not.toHaveBeenCalledWith(expect.stringMatching(/^\d{4}-/));
    expect(field().getAttribute('aria-invalid')).toBe('true');
  });

  // Review #3: the ISO-paste path must not let a พ.ศ. year back in as ค.ศ. 2570 (→ PDF 3113).
  it('rejects an ISO-shaped date carrying a พ.ศ. year (2570-01-31)', () => {
    const onValue = vi.fn();
    render(<Controlled onValue={onValue} />);
    fireEvent.change(field(), { target: { value: '2570-01-31' } });
    expect(onValue).not.toHaveBeenCalledWith('2570-01-31');
    expect(field().getAttribute('aria-invalid')).toBe('true');
    expect(screen.getByText(/ปี พ\.ศ\./)).not.toBeNull();
  });

  // Review #1: partial text clears the value too (Enter can submit without a blur), keeps the
  // text on screen, and only complains once the user leaves the field.
  it('a partly typed date clears the value, keeps the text, and errors only on blur', () => {
    const onValue = vi.fn();
    render(<Controlled initial="2026-11-03" onValue={onValue} />);
    fireEvent.change(field(), { target: { value: '31/01/25' } });
    expect(onValue).toHaveBeenLastCalledWith('');
    expect(field().value).toBe('31/01/25');
    expect(field().getAttribute('aria-invalid')).not.toBe('true');
    fireEvent.blur(field());
    expect(field().getAttribute('aria-invalid')).toBe('true');
    expect(field().value).toBe('31/01/25');
  });

  // Review #5: a parent reset back to its original date replaces leftover partial text.
  it('a reset to the original date replaces partial text', () => {
    render(<Controlled initial="2027-01-31" />);
    fireEvent.change(field(), { target: { value: '5/1/25' } });
    fireEvent.click(screen.getByRole('button', { name: 'reset' }));
    expect(field().value).toBe('31/01/2570');
    expect(field().getAttribute('aria-invalid')).not.toBe('true');
  });

  it('clearing the field reports an empty value', () => {
    const onValue = vi.fn();
    render(<Controlled initial="2027-01-31" onValue={onValue} />);
    fireEvent.change(field(), { target: { value: '' } });
    expect(onValue).toHaveBeenLastCalledWith('');
  });

  it('accepts a pasted ISO date and normalises the display to พ.ศ. on blur', () => {
    const onValue = vi.fn();
    render(<Controlled onValue={onValue} />);
    fireEvent.change(field(), { target: { value: '2026-12-31' } });
    expect(onValue).toHaveBeenLastCalledWith('2026-12-31');
    fireEvent.blur(field());
    expect(field().value).toBe('31/12/2569');
  });

  it('follows a value changed from outside (form reset / copy-from)', () => {
    const { rerender } = render(<ThaiDateInput aria-label="วันที่" value="2026-07-02" onChange={() => {}} />);
    expect(field().value).toBe('02/07/2569');
    rerender(<ThaiDateInput aria-label="วันที่" value="" onChange={() => {}} />);
    expect(field().value).toBe('');
  });

  it('passes the field name through on change (react-hook-form Controller / handlers keyed by name)', () => {
    const onChange = vi.fn();
    render(<ThaiDateInput aria-label="วันที่" name="startDate" value="" onChange={onChange} />);
    fireEvent.change(field(), { target: { value: '01/11/2569' } });
    expect(onChange.mock.calls.at(-1)[0].target).toMatchObject({ name: 'startDate', value: '2026-11-01' });
  });

  it('the calendar popup is in พ.ศ. and picking a day reports the ค.ศ. ISO value', () => {
    const onValue = vi.fn();
    render(<Controlled initial="2027-01-15" onValue={onValue} />);
    fireEvent.click(screen.getByRole('button', { name: 'เลือกวันที่' }));
    const dialog = screen.getByRole('dialog');
    expect(dialog.textContent).toContain('มกราคม 2570');
    expect(dialog.textContent).not.toContain('2027');
    fireEvent.click(screen.getByRole('button', { name: '31 มกราคม 2570' }));
    expect(onValue).toHaveBeenLastCalledWith('2027-01-31');
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('calendar month navigation crosses the year in พ.ศ.', () => {
    render(<Controlled initial="2026-12-10" />);
    fireEvent.click(screen.getByRole('button', { name: 'เลือกวันที่' }));
    fireEvent.click(screen.getByRole('button', { name: 'เดือนถัดไป' }));
    expect(screen.getByRole('dialog').textContent).toContain('มกราคม 2570');
  });

  it('min/max disable days outside the range in the calendar', () => {
    render(<Controlled initial="2026-11-15" min="2026-11-10" max="2026-11-20" />);
    fireEvent.click(screen.getByRole('button', { name: 'เลือกวันที่' }));
    expect(screen.getByRole('button', { name: '9 พฤศจิกายน 2569' }).disabled).toBe(true);
    expect(screen.getByRole('button', { name: '10 พฤศจิกายน 2569' }).disabled).toBe(false);
    expect(screen.getByRole('button', { name: '21 พฤศจิกายน 2569' }).disabled).toBe(true);
  });

  it('Escape closes the calendar without changing the value', () => {
    const onValue = vi.fn();
    render(<Controlled initial="2027-01-15" onValue={onValue} />);
    fireEvent.click(screen.getByRole('button', { name: 'เลือกวันที่' }));
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' });
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(onValue).not.toHaveBeenCalled();
  });

  // Review #2: inside a Modal, opening the calendar moves focus into it, and Escape — even when the
  // browser left focus on <body> (Safari does not focus a clicked button) — closes ONLY the
  // calendar, never the Modal underneath (that would throw the form away).
  it('inside a Modal: focus moves into the calendar and Escape closes only the calendar', () => {
    const onClose = vi.fn();
    render(<Modal title="ฟอร์ม" onClose={onClose}><Controlled initial="2027-01-15" /></Modal>);
    fireEvent.click(screen.getByRole('button', { name: 'เลือกวันที่' }));
    const dialog = screen.getByRole('dialog', { name: 'เลือกวันที่' });
    expect(dialog.contains(document.activeElement)).toBe(true);
    document.body.focus();
    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(screen.queryByRole('dialog', { name: 'เลือกวันที่' })).toBeNull();
    expect(onClose).not.toHaveBeenCalled();
  });

  // Review #6: tabbing out of the open calendar closes it (only a mousedown outside did).
  it('focus leaving the calendar closes it', () => {
    render(<><Controlled initial="2027-01-15" /><input aria-label="อื่น" /></>);
    fireEvent.click(screen.getByRole('button', { name: 'เลือกวันที่' }));
    const dialog = screen.getByRole('dialog');
    fireEvent.blur(dialog.querySelector('button'), { relatedTarget: screen.getByLabelText('อื่น') });
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  // Re-check #1: the open calendar must not pull focus back after the user returned to the text
  // field — a phone keyboard opening fires resize, which re-ran the focus effect.
  it('a resize while the calendar is open does not steal focus from the text field', () => {
    render(<Controlled initial="2027-01-15" />);
    fireEvent.click(screen.getByRole('button', { name: 'เลือกวันที่' }));
    field().focus();
    fireEvent(window, new Event('resize'));
    fireEvent.scroll(window);
    expect(document.activeElement).toBe(field());
  });

  // Re-check #2: focus leaving the WHOLE field (text input → next field) closes the calendar too,
  // else the stale capture-phase Escape listener eats the next Escape meant for the Modal.
  it('focus moving from the text field to another field closes the open calendar', () => {
    render(<><Controlled initial="2027-01-15" /><input aria-label="อื่น" /></>);
    fireEvent.click(screen.getByRole('button', { name: 'เลือกวันที่' }));
    field().focus();
    fireEvent.blur(field(), { relatedTarget: screen.getByLabelText('อื่น') });
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('disabled disables both the text field and the calendar button', () => {
    render(<Controlled initial="2027-01-15" disabled />);
    expect(field().disabled).toBe(true);
    expect(screen.getByRole('button', { name: 'เลือกวันที่' }).disabled).toBe(true);
  });
});
