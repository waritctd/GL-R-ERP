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

// Role-scoped views (docs/role-scoped-views.md): the deal-list tab is gated on
// canViewDealPipeline, not canViewTickets — import and account both lose it, so
// this bar never offers a tab the router would immediately bounce.
//
// The second pipeline tab, ภาพรวม (/ticket-overview), was removed 2026-08-10 by
// owner ruling. What survives here is the consequence: with one pipeline tab
// left, several roles fall to a single destination and the bar hides itself.
describe('SalesTabs (role-scoped views)', () => {
  it('gives ceo the deal-list tab and the pricing queue tab, queue trailing', () => {
    renderTabs('ceo');
    expect(screen.getAllByRole('link').map((link) => link.textContent))
      .toEqual(['ดีลทั้งหมด', 'คิวขอราคา']);
  });

  it('gives sales_manager the deal-list tab and the pricing queue tab, queue trailing', () => {
    renderTabs('sales_manager');
    expect(screen.getAllByRole('link').map((link) => link.textContent))
      .toEqual(['ดีลทั้งหมด', 'คิวขอราคา']);
  });

  // A bar offering ONE destination is chrome that navigates nowhere — the tab is
  // always the page you are already on. Both roles below keep their sidebar
  // entries (/tickets and /pricing-requests are separate AppShell nav items), so
  // nothing becomes unreachable.
  it('renders nothing for sales, whose only tab would be the page it is already on', () => {
    renderTabs('sales');
    expect(screen.queryAllByRole('link')).toHaveLength(0);
    expect(screen.queryByLabelText('งานขาย (Sales)')).toBeNull();
  });

  it('renders nothing for import, left with only the pricing queue', () => {
    renderTabs('import');
    expect(screen.queryByText('ดีลทั้งหมด')).toBeNull();
    expect(screen.queryAllByRole('link')).toHaveLength(0);
  });

  // Account has neither canViewDealPipeline nor canViewPricingRequestQueue, so
  // this bar has nothing to offer it at all — its worklist is its own งานการเงิน
  // page (/finance), not this component.
  it('gives account no tabs at all (its worklist is งานการเงิน, not this bar)', () => {
    renderTabs('account');
    expect(screen.queryAllByRole('link')).toHaveLength(0);
  });

  // The removed tab must not come back by accident, in any role's bar.
  it('never renders a ภาพรวม tab for any role', () => {
    for (const role of ['sales', 'sales_manager', 'ceo', 'import', 'account']) {
      const { unmount } = renderTabs(role);
      expect(screen.queryByText('ภาพรวม')).toBeNull();
      unmount();
    }
  });
});
