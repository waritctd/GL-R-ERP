import React from 'react';
import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { PunchDetail, punchRole } from './AttendancePage.jsx';

globalThis.React = React;

describe('punchRole', () => {
  it('labels the first scan as clock-in (เข้า) and the last as clock-out (ออก)', () => {
    expect(punchRole(0, 4).label).toBe('เข้า');
    expect(punchRole(3, 4).label).toBe('ออก');
    expect(punchRole(1, 4).label).toBe('ระหว่างวัน');
    expect(punchRole(2, 4).label).toBe('ระหว่างวัน');
  });

  it('leaves a lone scan unlabelled — its direction is not asserted', () => {
    expect(punchRole(0, 1).label).toBe('');
  });
});

describe('PunchDetail — first punch is clock-in, last is clock-out', () => {
  // Punches arrive oldest-first (the AttendanceRepository.findPunches contract), so the earliest
  // time must render with เข้า and the latest with ออก regardless of how many scans there are.
  const punches = [
    { punch_id: 1, punch_time: '2024-03-04T08:20:00+07:00', site_code: 'SHOWROOM' },
    { punch_id: 2, punch_time: '2024-03-04T12:03:00+07:00', site_code: 'SHOWROOM' },
    { punch_id: 3, punch_time: '2024-03-04T13:10:00+07:00', site_code: 'SHOWROOM' },
    { punch_id: 4, punch_time: '2024-03-04T17:40:00+07:00', site_code: 'SHOWROOM' },
  ];

  it('binds เข้า to the earliest time and ออก to the latest', () => {
    render(<PunchDetail punches={punches} />);
    const rows = screen.getAllByRole('listitem');

    // getByText throws when the text is absent, so a successful lookup is the assertion.
    const first = within(rows[0]);
    expect(first.getByText('เข้า')).toBeTruthy();
    expect(first.getByText('08:20')).toBeTruthy();

    const last = within(rows[rows.length - 1]);
    expect(last.getByText('ออก')).toBeTruthy();
    expect(last.getByText('17:40')).toBeTruthy();

    // Mid-day scans are neither clock-in nor clock-out.
    expect(within(rows[1]).getByText('ระหว่างวัน')).toBeTruthy();
    expect(within(rows[2]).getByText('ระหว่างวัน')).toBeTruthy();
  });
});
