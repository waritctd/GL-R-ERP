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

// Role-scoped views (docs/role-scoped-views.md): the pipeline tabs
// (ดีลทั้งหมด/ภาพรวม) are gated on canViewDealPipeline, not canViewTickets —
// import and account both lose them, so this bar never offers a tab the
// router would immediately bounce.
describe('SalesTabs (role-scoped views)', () => {
  it('drops the deal-pipeline tabs for import, keeping only the pricing queue tab', () => {
    renderTabs('import');
    expect(screen.queryByText('ดีลทั้งหมด')).toBeNull();
    expect(screen.queryByText('ภาพรวม')).toBeNull();
    expect(screen.getByText('คิวขอราคา')).toBeTruthy();
  });

  it('keeps the deal-pipeline tabs for sales (no pricing-queue tab — sales lacks canViewPricingRequestQueue)', () => {
    renderTabs('sales');
    expect(screen.getByText('ดีลทั้งหมด')).toBeTruthy();
    expect(screen.getByText('ภาพรวม')).toBeTruthy();
    expect(screen.queryByText('คิวขอราคา')).toBeNull();
  });

  it('gives ceo both the pipeline tabs and the pricing queue tab, queue trailing', () => {
    renderTabs('ceo');
    const tabs = screen.getAllByRole('link').map((link) => link.textContent);
    expect(tabs).toEqual(['ดีลทั้งหมด', 'ภาพรวม', 'คิวขอราคา']);
  });

  it('gives sales_manager both the pipeline tabs and the pricing queue tab, queue trailing', () => {
    renderTabs('sales_manager');
    const tabs = screen.getAllByRole('link').map((link) => link.textContent);
    expect(tabs).toEqual(['ดีลทั้งหมด', 'ภาพรวม', 'คิวขอราคา']);
  });

  // Account role-scoped views: account has no canViewDealPipeline and no
  // canViewPricingRequestQueue, so this bar renders no tabs at all for it —
  // its worklist is its own งานการเงิน page (/finance), not this component.
  it('gives account no tabs at all (its worklist is งานการเงิน, not this bar)', () => {
    renderTabs('account');
    expect(screen.queryByText('ดีลทั้งหมด')).toBeNull();
    expect(screen.queryByText('ภาพรวม')).toBeNull();
    expect(screen.queryByText('คิวขอราคา')).toBeNull();
    expect(screen.queryAllByRole('link')).toHaveLength(0);
  });
});
