import React, { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ThaiDateInput } from './ThaiDateInput.jsx';

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
});
