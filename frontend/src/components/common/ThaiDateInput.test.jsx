import React, { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ThaiDateInput } from './ThaiDateInput.jsx';
import { Modal } from './Modal.jsx';

globalThis.React = React;

// The value contract is the native one — '' or an ISO 'YYYY-MM-DD' (ค.ศ.), what the API stores —
// so every call site keeps its state and payloads. Only what the user SEES and TYPES is พ.ศ.
// Typed text is COMMITTED on blur (or Enter); keystrokes never report half-typed dates.
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
const type = (text) => fireEvent.change(field(), { target: { value: text } });

describe('ThaiDateInput', () => {
  it('shows a stored ISO date as dd/mm/yyyy with a พ.ศ. year', () => {
    render(<Controlled initial="2027-01-31" />);
    expect(field().value).toBe('31/01/2570');
  });

  it('a typed พ.ศ. date is reported as the ค.ศ. ISO value on blur, not per keystroke (the 31/01/2570 → 3113 bug)', () => {
    const onValue = vi.fn();
    render(<Controlled onValue={onValue} />);
    type('31/01/2570');
    expect(onValue).not.toHaveBeenCalled();
    fireEvent.blur(field());
    expect(onValue).toHaveBeenLastCalledWith('2027-01-31');
  });

  // Review 2 #2: editing digit by digit passes through other valid dates (31 → 3 → 30); only the
  // final one may reach the parent, or handlers like the overtime end-date reset fire on 3/01.
  it('editing through an intermediate valid date reports only the final date', () => {
    const onValue = vi.fn();
    render(<Controlled initial="2027-01-31" onValue={onValue} />);
    type('3/01/2570');
    type('30/01/2570');
    fireEvent.blur(field());
    expect(onValue.mock.calls).toEqual([['2027-01-30']]);
  });

  // Review 2 #1: an invalid entry must neither clear the value (a blank hire date gets saved in a
  // noValidate form) nor leave garbage on screen over the old value: it reverts, with the reason.
  it('a ค.ศ. year typed over a valid date is never reported; on blur the field reverts and says why', () => {
    const onValue = vi.fn();
    render(<Controlled initial="2026-11-03" onValue={onValue} />);
    type('31/01/2027');
    fireEvent.blur(field());
    expect(onValue).not.toHaveBeenCalled();
    expect(field().value).toBe('03/11/2569');
    expect(field().getAttribute('aria-invalid')).toBe('true');
    expect(screen.getByText(/ปี พ\.ศ\./)).not.toBeNull();
  });

  it('rejects an impossible day (31/02): nothing reported, field reverts to empty with an error', () => {
    const onValue = vi.fn();
    render(<Controlled onValue={onValue} />);
    type('31/02/2570');
    fireEvent.blur(field());
    expect(onValue).not.toHaveBeenCalled();
    expect(field().value).toBe('');
    expect(field().getAttribute('aria-invalid')).toBe('true');
  });

  it('rejects an ISO-shaped date carrying a พ.ศ. year (2570-01-31)', () => {
    const onValue = vi.fn();
    render(<Controlled onValue={onValue} />);
    type('2570-01-31');
    fireEvent.blur(field());
    expect(onValue).not.toHaveBeenCalled();
    expect(screen.getByText(/ปี พ\.ศ\./)).not.toBeNull();
  });

  it('a partly typed date shows no error while typing; on blur it reverts with an error', () => {
    const onValue = vi.fn();
    render(<Controlled initial="2026-11-03" onValue={onValue} />);
    type('31/01/25');
    expect(field().value).toBe('31/01/25');
    expect(field().getAttribute('aria-invalid')).not.toBe('true');
    fireEvent.blur(field());
    expect(onValue).not.toHaveBeenCalled();
    expect(field().value).toBe('03/11/2569');
    expect(field().getAttribute('aria-invalid')).toBe('true');
  });

  // Review 2 #8: min/max used to be inert on typing — only the calendar honoured them.
  it('a typed date outside min/max is not reported; the field reverts with an error', () => {
    const onValue = vi.fn();
    render(<Controlled initial="2026-11-15" min="2026-11-10" max="2026-11-20" onValue={onValue} />);
    type('21/11/2569');
    fireEvent.blur(field());
    expect(onValue).not.toHaveBeenCalled();
    expect(field().value).toBe('15/11/2569');
    expect(field().getAttribute('aria-invalid')).toBe('true');
  });

  // A stored value already outside a moved bound (effectiveTo after effectiveFrom changed) is not
  // the user's edit: tabbing through must not flag it.
  it('tabbing through an unchanged value that is outside min/max shows no error', () => {
    render(<Controlled initial="2026-11-05" min="2026-11-10" />);
    fireEvent.blur(field());
    expect(field().getAttribute('aria-invalid')).not.toBe('true');
  });

  it('a value changed from outside replaces uncommitted text', () => {
    const { rerender } = render(<ThaiDateInput aria-label="วันที่" value="2027-01-31" onChange={() => {}} />);
    type('5/1/25');
    rerender(<ThaiDateInput aria-label="วันที่" value="2026-07-02" onChange={() => {}} />);
    expect(field().value).toBe('02/07/2569');
  });

  // Review 3: a parent that keeps its value (AttendancePage: `value || today`) must not leave the
  // field blank while the value is still a date.
  it('a parent that keeps its value after a commit leaves the field showing that value', () => {
    function FallsBack() {
      const [value, setValue] = useState('2026-08-15');
      return <ThaiDateInput aria-label="วันที่" value={value} onChange={(e) => setValue(e.target.value || '2026-08-15')} />;
    }
    render(<FallsBack />);
    type('');
    fireEvent.blur(field());
    expect(field().value).toBe('15/08/2569');
  });

  it('clearing the field reports an empty value on blur', () => {
    const onValue = vi.fn();
    render(<Controlled initial="2027-01-31" onValue={onValue} />);
    type('');
    fireEvent.blur(field());
    expect(onValue).toHaveBeenLastCalledWith('');
  });

  it('accepts a pasted ISO date and normalises the display to พ.ศ. on blur', () => {
    const onValue = vi.fn();
    render(<Controlled onValue={onValue} />);
    type('2026-12-31');
    fireEvent.blur(field());
    expect(onValue).toHaveBeenLastCalledWith('2026-12-31');
    expect(field().value).toBe('31/12/2569');
  });

  // Enter would submit the form with the old value still in the parent's state.
  it('Enter on uncommitted text commits it and does not submit; Enter on committed text submits', () => {
    const onValue = vi.fn();
    render(<Controlled initial="2027-01-31" onValue={onValue} />);
    type('01/02/2570');
    const typed = fireEvent.keyDown(field(), { key: 'Enter' });
    expect(typed).toBe(false); // default prevented
    expect(onValue).toHaveBeenLastCalledWith('2027-02-01');
    expect(fireEvent.keyDown(field(), { key: 'Enter' })).toBe(true);
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
    type('01/11/2569');
    fireEvent.blur(field());
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

  // Inside a Modal, opening the calendar moves focus into it, and Escape — even when the browser
  // left focus on <body> (Safari does not focus a clicked button) — closes ONLY the calendar.
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

  it('focus leaving the calendar closes it', () => {
    render(<><Controlled initial="2027-01-15" /><input aria-label="อื่น" /></>);
    fireEvent.click(screen.getByRole('button', { name: 'เลือกวันที่' }));
    const dialog = screen.getByRole('dialog');
    fireEvent.blur(dialog.querySelector('button'), { relatedTarget: screen.getByLabelText('อื่น') });
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  // A phone keyboard opening fires resize; the open calendar must not pull focus back.
  it('a resize while the calendar is open does not steal focus from the text field', () => {
    render(<Controlled initial="2027-01-15" />);
    fireEvent.click(screen.getByRole('button', { name: 'เลือกวันที่' }));
    field().focus();
    fireEvent(window, new Event('resize'));
    fireEvent.scroll(window);
    expect(document.activeElement).toBe(field());
  });

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
