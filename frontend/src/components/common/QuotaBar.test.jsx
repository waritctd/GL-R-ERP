import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { QuotaBar } from './QuotaBar.jsx';

globalThis.React = React;

// The reason this component was promoted out of TaxAllowanceForm rather than reimplemented: it is
// the one quota readout in the app that is legible without seeing the bar. These pin that, not the
// pixels -- a rewrite that keeps the fill and drops the progressbar role or the numeric readout
// would look identical in a screenshot and be unusable with a screen reader.
describe('QuotaBar', () => {
  it('exposes the fill as a real progressbar with an accessible name', () => {
    render(<QuotaBar label="ค่ารักษาพยาบาล" used={750} cap={3000} />);

    const bar = screen.getByRole('progressbar', { name: /ค่ารักษาพยาบาล/ });
    expect(bar.getAttribute('aria-valuenow')).toBe('25');
    expect(bar.getAttribute('aria-valuemin')).toBe('0');
    expect(bar.getAttribute('aria-valuemax')).toBe('100');
  });

  it('spells out the raw used / cap numbers instead of leaving them implicit in the width', () => {
    render(<QuotaBar label="ค่ารักษาพยาบาล" used={750} cap={3000} />);

    expect(screen.getByText('฿750.00 / ฿3,000.00')).toBeTruthy();
  });

  it('formats non-money quotas through formatValue, for count-based caps', () => {
    render(
      <QuotaBar
        label="ชุดฟอร์มประจำปี"
        used={2}
        cap={4}
        formatValue={(value) => `${value} ชิ้น`}
      />,
    );

    expect(screen.getByText('2 ชิ้น / 4 ชิ้น')).toBeTruthy();
  });

  it('surfaces the over-cap message only once the cap is actually exceeded', () => {
    const message = 'ยอดที่ยื่นเกินวงเงินรวมของกลุ่มนี้';
    const { rerender } = render(
      <QuotaBar label="กลุ่มประกัน" used={100} cap={100} overMessage={message} />,
    );
    // At exactly the cap the employee has not overspent -- an off-by-one here would accuse them.
    expect(screen.queryByText(message)).toBeNull();

    rerender(<QuotaBar label="กลุ่มประกัน" used={101} cap={100} overMessage={message} />);
    expect(screen.getByText(message)).toBeTruthy();
  });

  it('keeps aria-valuenow inside its declared range when usage runs over or below', () => {
    const { rerender } = render(<QuotaBar label="กลุ่มประกัน" used={500} cap={100} />);
    // Over cap the bar pins at 100 rather than reporting 500, which would be outside aria-valuemax.
    expect(screen.getByRole('progressbar').getAttribute('aria-valuenow')).toBe('100');

    // A negative used (a reversal) must not render a negative width or a sub-zero valuenow.
    rerender(<QuotaBar label="กลุ่มประกัน" used={-50} cap={100} />);
    expect(screen.getByRole('progressbar').getAttribute('aria-valuenow')).toBe('0');
  });

  it('renders nothing for an uncapped quota rather than a bar asserting 0% or 100%', () => {
    const { container } = render(<QuotaBar label="อื่นๆ" used={1200} cap={null} />);

    expect(container.firstChild).toBeNull();
  });
});
