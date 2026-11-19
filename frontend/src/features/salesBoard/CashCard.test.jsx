import React from 'react';
import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { CashCard } from './CashCard.jsx';

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
    // ISO rule: the week of Mon 30 Nov is December's, so November has four weeks; the last runs to 30 Nov.
    { weekStart: '2026-11-23', deliveredAwaiting: 0, deliveriesScheduled: 0, depositsExpected: 416000, received: 0 },
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
    expect(bars).toHaveLength(4);
    expect(within(bars[1]).getByText('ถึง 13 พ.ย.')).toBeTruthy();
    expect(within(bars[1]).getByText('฿545,300')).toBeTruthy();
    expect(within(bars[3]).getByText('ถึง 30 พ.ย.')).toBeTruthy();
    // Segment widths follow each bucket's share of the week (series order: delivered, scheduled, deposits).
    const segs = within(bars[1]).getAllByTestId('cash-seg');
    expect(segs.map((s) => s.getAttribute('data-series'))).toEqual(['deliveredAwaiting', 'deliveriesScheduled', 'depositsExpected']);
    // The longest week fills the track; a smaller week is proportionally shorter.
    expect(within(bars[1]).getByTestId('cash-track').style.width).toBe('100%');
    expect(within(bars[2]).getByTestId('cash-track').style.width).toBe('52%');
  });
});
