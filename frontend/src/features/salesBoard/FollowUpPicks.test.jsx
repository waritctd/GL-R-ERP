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
  it('offers 3 วัน · 1 สัปดาห์ · 2 สัปดาห์ · เลือกวัน with nothing preselected and no date shown', () => {
    const onValue = vi.fn();
    render(<Controlled onValue={onValue} />);

    const names = within(group()).getAllByRole('radio').map((r) => r.textContent);
    expect(names).toEqual(['3 วัน', '1 สัปดาห์', '2 สัปดาห์', 'เลือกวัน']);
    for (const r of within(group()).getAllByRole('radio')) expect(r.getAttribute('aria-checked')).toBe('false');
    expect(onValue).not.toHaveBeenCalled();
    expect(screen.queryByText(/แสดงในข้อมูลงาน/)).toBeNull();
    expect(screen.getByText('(ไม่บังคับ)')).toBeTruthy();
  });

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

  it('tapping the picked choice again clears it — the follow-up stays optional', () => {
    const onValue = vi.fn();
    render(<Controlled onValue={onValue} />);

    fireEvent.click(radio('1 สัปดาห์'));
    fireEvent.click(radio('1 สัปดาห์'));

    expect(onValue).toHaveBeenLastCalledWith('');
    expect(radio('1 สัปดาห์').getAttribute('aria-checked')).toBe('false');
    expect(screen.queryByText(/แสดงในข้อมูลงาน/)).toBeNull();
  });

  it('เลือกวัน opens a date field that starts empty, refuses a past day, and takes a typed พ.ศ. date', () => {
    const onValue = vi.fn();
    render(<Controlled onValue={onValue} />);

    fireEvent.click(radio('1 สัปดาห์'));
    fireEvent.click(radio('เลือกวัน'));
    expect(onValue).toHaveBeenLastCalledWith('');
    const field = screen.getByLabelText('วันที่ตามครั้งถัดไป');
    expect(field.value).toBe('');

    fireEvent.change(field, { target: { value: '16/11/2569' } });
    fireEvent.blur(field);
    expect(onValue).toHaveBeenLastCalledWith('');

    fireEvent.change(field, { target: { value: '05/01/2570' } });
    fireEvent.blur(field);
    expect(onValue).toHaveBeenLastCalledWith('2027-01-05');
  });

  it('a later date in another year carries its พ.ศ. year in the line under the choices', () => {
    render(<Controlled />);
    fireEvent.click(radio('เลือกวัน'));
    const field = screen.getByLabelText('วันที่ตามครั้งถัดไป');
    fireEvent.change(field, { target: { value: '05/01/2570' } });
    fireEvent.blur(field);

    expect(screen.getByText('ตามครั้งถัดไป อ. 5 ม.ค. 2570 · แสดงในข้อมูลงาน')).toBeTruthy();
  });

  it('takes the visit dialogs\' label ตามต่อ', () => {
    render(<Controlled label="ตามต่อ" />);
    expect(screen.getByRole('radiogroup', { name: 'ตามต่อ' })).toBeTruthy();
  });

  it('every choice is at least 44px tall on a phone', () => {
    render(<Controlled />);
    for (const r of within(group()).getAllByRole('radio')) expect(r.className).toContain('mobile:min-h-[44px]');
  });
});

describe('withFollowUp', () => {
  it('adds nextFollowUpAt only when a date was picked, so an unused control leaves the request exactly as before', () => {
    expect(withFollowUp({ note: 'x' }, '')).toStrictEqual({ note: 'x' });
    expect(withFollowUp({ note: 'x' }, undefined)).toStrictEqual({ note: 'x' });
    expect(withFollowUp({ note: 'x' }, '2026-11-24')).toStrictEqual({ note: 'x', nextFollowUpAt: '2026-11-24' });
  });
});
