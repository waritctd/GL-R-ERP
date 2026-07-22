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

// Role-scoped views (Import build, docs/role-scoped-views.md): the pipeline
// tabs (ดีลทั้งหมด/ภาพรวม) are gated on canViewDealPipeline, not
// canViewTickets — import loses them and is left with only its pricing-queue
// tab, so this bar never offers a tab the router would immediately bounce.
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

  it('gives account no dedicated tab (its worklist IS ดีลทั้งหมด) but keeps the pipeline tabs', () => {
    renderTabs('account');
    expect(screen.getByText('ดีลทั้งหมด')).toBeTruthy();
    expect(screen.getByText('ภาพรวม')).toBeTruthy();
    expect(screen.queryByText('คิวขอราคา')).toBeNull();
  });
});
