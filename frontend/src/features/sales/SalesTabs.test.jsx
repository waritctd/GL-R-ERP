import React from 'react';
import { MemoryRouter } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { SalesTabs } from './SalesTabs.jsx';

globalThis.React = React;

function renderTabs(role) {
  return render(
    <MemoryRouter initialEntries={['/tickets']}>
      <SalesTabs role={role} />
    </MemoryRouter>,
  );
}

// Role-scoped views (docs/role-scoped-views.md): the deal-list tab is gated on canViewDealPipeline, not
// canViewTickets — import and account both lose it, so this bar never offers a tab the router would
// immediately bounce.
describe('SalesTabs (role-scoped views)', () => {
  it('gives ceo the deal-list tab and the pricing queue tab, queue trailing', () => {
    renderTabs('ceo');
    expect(screen.getAllByRole('link').map((link) => link.textContent))
      .toEqual(['งานทั้งหมด', 'คิวขอราคา']);
  });

  // A bar offering ONE destination is chrome that navigates nowhere — the tab is always the page you are already
  // on. Both roles below keep their sidebar entries (/tickets and /pricing-requests are separate AppShell nav
  // items), so nothing becomes unreachable.
  it('renders nothing for sales, whose only tab would be the page it is already on', () => {
    renderTabs('sales');
    expect(screen.queryAllByRole('link')).toHaveLength(0);
    expect(screen.queryByLabelText('งานขาย (Sales)')).toBeNull();
  });

  // Account has neither canViewDealPipeline nor canViewPricingRequestQueue, so this bar has nothing to offer it
  // at all — its worklist is its own งานการเงิน page (/finance), not this component.
  it('gives account no tabs at all (its worklist is งานการเงิน, not this bar)', () => {
    renderTabs('account');
    expect(screen.queryAllByRole('link')).toHaveLength(0);
  });
});
