import React from 'react';
import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { CashCard, CASH_SERIES } from './CashCard.jsx';

globalThis.React = React;

const november = {
  month: '2026-11',
  forecast: 1301000,
  received: 121150,
  deliveredAwaiting: 45000,
  deliveriesScheduled: 431000,
  depositsExpected: 768900,
  weeks: [
    { weekStart: '2026-11-02', deliveredAwaiting: 0, deliveriesScheduled: 0, depositsExpected: 0, received: 121150 },
    { weekStart: '2026-11-09', deliveredAwaiting: 45000, deliveriesScheduled: 415000, depositsExpected: 85300, received: 0 },
    { weekStart: '2026-11-16', deliveredAwaiting: 0, deliveriesScheduled: 16000, depositsExpected: 267800, received: 0 },
    { weekStart: '2026-11-23', deliveredAwaiting: 0, deliveriesScheduled: 0, depositsExpected: 319400, received: 0 },
    { weekStart: '2026-11-30', deliveredAwaiting: 0, deliveriesScheduled: 0, depositsExpected: 96400, received: 0 },
  ],
};

describe('CashCard — เงินที่จะเข้า', () => {
  it('titles the month and leads with received of forecast', () => {
    render(<CashCard cash={november} />);
    const card = screen.getByRole('region', { name: 'เงินที่จะเข้า พ.ย.' });
    expect(within(card).getByTestId('cash-received').textContent).toBe('฿121,150');
    expect(within(card).getByText('รับแล้ว จากที่คาด ฿1,301,000')).toBeTruthy();
    const meter = within(card).getByRole('meter', { name: 'รับแล้วเทียบกับที่คาด' });
    expect(meter.getAttribute('aria-valuenow')).toBe('121150');
    expect(meter.getAttribute('aria-valuemax')).toBe('1301000');
  });

  it('draws one stacked bar per week with a direct label of that week’s expected total', () => {
    render(<CashCard cash={november} />);
    const bars = screen.getAllByTestId('cash-week');
    expect(bars).toHaveLength(5);
    expect(within(bars[1]).getByText('ถึง 13 พ.ย.')).toBeTruthy();
    expect(within(bars[1]).getByText('฿545,300')).toBeTruthy();
    expect(within(bars[4]).getByText('ถึง 30 พ.ย.')).toBeTruthy();
    // Segment widths follow each bucket's share of the week (series order: delivered, scheduled, deposits).
    const segs = within(bars[1]).getAllByTestId('cash-seg');
    expect(segs.map((s) => s.getAttribute('data-series'))).toEqual(['deliveredAwaiting', 'deliveriesScheduled', 'depositsExpected']);
    // The longest week fills the track; a smaller week is proportionally shorter.
    expect(within(bars[1]).getByTestId('cash-track').style.width).toBe('100%');
    expect(within(bars[4]).getByTestId('cash-track').style.width).toBe('17.7%');
  });

  it('keeps the validated palette, a legend, and never colours text in a series colour', () => {
    expect(CASH_SERIES.map((s) => s.color)).toEqual(['#4f46e5', '#0284c7', '#14b8a6']);
    render(<CashCard cash={november} />);
    const legend = screen.getByRole('list', { name: 'คำอธิบายสี' });
    expect(within(legend).getAllByRole('listitem').map((li) => li.textContent))
      .toEqual(['ส่งของแล้ว รอเก็บ', 'นัดส่งของ', 'มัดจำที่คาด']);
    for (const el of document.querySelectorAll('[style]')) {
      expect(el.style.color).toBe('');
    }
  });

  it('carries the same numbers as a table for anyone who cannot read the bars', () => {
    render(<CashCard cash={november} />);
    const table = screen.getByRole('table', { name: 'เงินที่จะเข้า รายสัปดาห์' });
    const rows = within(table).getAllByRole('row');
    expect(rows).toHaveLength(1 + 5 + 1); // header, five weeks, total
    expect(within(rows[2]).getAllByRole('cell').map((c) => c.textContent))
      .toEqual(['ถึง 13 พ.ย.', '฿45,000', '฿415,000', '฿85,300', '฿0']);
    expect(within(rows[6]).getAllByRole('cell').map((c) => c.textContent))
      .toEqual(['รวม', '฿45,000', '฿431,000', '฿768,900', '฿121,150']);
  });

  it('says so, calmly, when nothing is expected or received this month', () => {
    const empty = {
      ...november, forecast: 0, received: 0, deliveredAwaiting: 0, deliveriesScheduled: 0, depositsExpected: 0,
      weeks: november.weeks.map((w) => ({ ...w, deliveredAwaiting: 0, deliveriesScheduled: 0, depositsExpected: 0, received: 0 })),
    };
    render(<CashCard cash={empty} />);
    expect(screen.getByText('ยังไม่มีเงินที่คาดว่าจะเข้าเดือนนี้')).toBeTruthy();
    expect(screen.queryAllByTestId('cash-week')).toHaveLength(0);
  });

  it('a received-only month still shows the headline, without bars', () => {
    const receivedOnly = {
      ...november, forecast: 0, deliveredAwaiting: 0, deliveriesScheduled: 0, depositsExpected: 0,
      weeks: november.weeks.map((w) => ({ ...w, deliveredAwaiting: 0, deliveriesScheduled: 0, depositsExpected: 0 })),
    };
    render(<CashCard cash={receivedOnly} />);
    expect(screen.getByTestId('cash-received').textContent).toBe('฿121,150');
    expect(screen.queryByText('ยังไม่มีเงินที่คาดว่าจะเข้าเดือนนี้')).toBeNull();
  });
});
