import React from 'react';
import { MemoryRouter } from 'react-router-dom';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { Breadcrumbs } from './Breadcrumbs.jsx';

globalThis.React = React;

describe('Breadcrumbs', () => {
  it('renders route breadcrumbs as links and marks the current page', () => {
    render(
      <MemoryRouter>
        <Breadcrumbs items={[{ label: 'รายการดีล', to: '/tickets' }, { label: 'D-001' }]} />
      </MemoryRouter>,
    );

    expect(screen.getByRole('link', { name: 'รายการดีล' }).getAttribute('href')).toBe('/tickets');
    expect(screen.getByText('D-001').getAttribute('aria-current')).toBe('page');
  });

  it('keeps callback breadcrumbs available for non-route back actions', () => {
    const goBack = vi.fn();

    render(<Breadcrumbs items={[{ label: 'กลับ', onClick: goBack }, { label: 'หน้าเดิม' }]} />);

    fireEvent.click(screen.getByRole('button', { name: 'กลับ' }));
    expect(goBack).toHaveBeenCalledTimes(1);
  });
});
