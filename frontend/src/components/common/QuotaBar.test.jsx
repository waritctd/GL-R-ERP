import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { QuotaBar } from './QuotaBar.jsx';

globalThis.React = React;

// The reason this component was promoted out of TaxAllowanceForm rather than reimplemented: it is the one
// quota readout in the app that is legible without seeing the bar.
describe('QuotaBar', () => {
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
});
