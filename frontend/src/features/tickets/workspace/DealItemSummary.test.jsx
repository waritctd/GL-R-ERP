import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { DealItemSummary } from './DealItemSummary.jsx';

globalThis.React = React;

describe('DealItemSummary', () => {
  it('renders item rows without decorative nested cards and keeps price visibility role-driven', () => {
    const { container } = render(
      <DealItemSummary
        items={[
          {
            id: 1,
            brand: 'SCG',
            model: 'A1',
            color: 'ขาว',
            texture: 'ด้าน',
            size: '60x60',
            qty: 10,
            qtyDelivered: 4,
            qtyFromStock: 2,
            proposedPrice: 120,
            approvedPrice: 150,
          },
        ]}
        showProposed
        showApproved
      />,
    );

    expect(screen.getByTestId('deal-item-summary')).not.toBeNull();
    expect(screen.getByText('SCG')).not.toBeNull();
    expect(screen.getByText(/ส่งมอบ 4 \/ 10/)).not.toBeNull();
    expect(screen.getByText(/เสนอ ฿120/)).not.toBeNull();
    expect(container.querySelectorAll('.rounded-lg, .shadow-sm')).toHaveLength(0);
  });
});
