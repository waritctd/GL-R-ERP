import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { DealPaymentHistory } from './DealPaymentHistory.jsx';

globalThis.React = React;

describe('DealPaymentHistory', () => {
  it('renders compact receipt rows and a compact empty row', () => {
    const { rerender, container } = render(
      <DealPaymentHistory
        receipts={[
          {
            receiptId: 1,
            kind: 'DEPOSIT',
            amount: 400,
            receivedAt: '2026-06-20T09:00:00.000Z',
            recordedByName: 'ฝ่ายบัญชี',
            note: 'โอนแล้ว',
          },
        ]}
      />,
    );

    expect(screen.getByTestId('deal-payment-history')).not.toBeNull();
    expect(screen.getByText('DEPOSIT')).not.toBeNull();
    expect(screen.getByText('฿400')).not.toBeNull();
    expect(container.querySelectorAll('.rounded-lg, .shadow-sm')).toHaveLength(0);

    rerender(<DealPaymentHistory receipts={[]} />);
    expect(screen.getByText('ยังไม่มีรายการรับชำระ')).not.toBeNull();
  });
});
