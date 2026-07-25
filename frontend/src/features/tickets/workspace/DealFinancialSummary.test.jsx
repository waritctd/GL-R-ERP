import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { DealFinancialSummary } from './DealFinancialSummary.jsx';

globalThis.React = React;

describe('DealFinancialSummary', () => {
  it('renders money and billing values as aligned rows instead of metric cards', () => {
    const { container } = render(
      <DealFinancialSummary
        summary={{
          amountPayable: 1000,
          amountPaid: 400,
          amountOutstanding: 600,
          billingDate: '2026-06-20',
          dueDate: '2026-07-01',
          nextFollowUpAt: '2026-07-03',
          overdue: true,
        }}
      />,
    );

    expect(container.querySelector('dl')).not.toBeNull();
    expect(screen.getByText('ยอดที่ต้องชำระ')).not.toBeNull();
    expect(screen.getByText('รับชำระแล้ว')).not.toBeNull();
    expect(screen.getByText('คงเหลือ')).not.toBeNull();
    expect(screen.getByText('วันวางบิล')).not.toBeNull();
    expect(screen.getByText('วันครบกำหนด')).not.toBeNull();
    expect(screen.getByText('ติดตามครั้งถัดไป')).not.toBeNull();
    expect(screen.getByText('฿1,000')).not.toBeNull();
    expect(screen.getByText('฿400')).not.toBeNull();
    expect(screen.getByText('฿600')).not.toBeNull();
    expect(screen.getAllByTestId('financial-summary-row')).toHaveLength(6);
    expect(container.querySelectorAll('.rounded-lg, .shadow-sm')).toHaveLength(0);
  });
});
