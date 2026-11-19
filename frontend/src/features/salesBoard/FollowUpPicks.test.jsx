import React, { useState } from 'react';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { FollowUpPicks, withFollowUp } from './FollowUpPicks.jsx';

globalThis.React = React;

// Slice 6.4: ตามครั้งถัดไป / ตามต่อ on the call and visit dialogs. Nothing is picked until the rep picks; the value is
// '' or an ISO date counted from the Asia/Bangkok `todayIso` the dialog passes.
const TODAY = '2026-11-17'; // a Tuesday

function Controlled({ onValue = () => {}, label }) {
  const [value, setValue] = useState('');
  return (
    <FollowUpPicks
      label={label}
      todayIso={TODAY}
      value={value}
      onChange={(next) => { setValue(next); onValue(next); }}
    />
  );
}

const group = () => screen.getByRole('radiogroup', { name: 'ตามครั้งถัดไป' });
const radio = (name) => within(group()).getByRole('radio', { name });

describe('FollowUpPicks', () => {
  it('each quick choice counts from today in Bangkok and says which day it lands on', () => {
    const onValue = vi.fn();
    render(<Controlled onValue={onValue} />);

    fireEvent.click(radio('3 วัน'));
    expect(onValue).toHaveBeenLastCalledWith('2026-11-20');
    expect(screen.getByText('ตามครั้งถัดไป ศ. 20 พ.ย. · แสดงในข้อมูลงาน')).toBeTruthy();
    fireEvent.click(radio('1 สัปดาห์'));
    expect(onValue).toHaveBeenLastCalledWith('2026-11-24');
    expect(radio('1 สัปดาห์').getAttribute('aria-checked')).toBe('true');
    expect(radio('3 วัน').getAttribute('aria-checked')).toBe('false');
    fireEvent.click(radio('2 สัปดาห์'));
    expect(onValue).toHaveBeenLastCalledWith('2026-12-01');
  });
});

describe('withFollowUp', () => {
  it('adds nextFollowUpAt only when a date was picked, so an unused control leaves the request exactly as before', () => {
    expect(withFollowUp({ note: 'x' }, '')).toStrictEqual({ note: 'x' });
    expect(withFollowUp({ note: 'x' }, undefined)).toStrictEqual({ note: 'x' });
    expect(withFollowUp({ note: 'x' }, '2026-11-24')).toStrictEqual({ note: 'x', nextFollowUpAt: '2026-11-24' });
  });
});
