import React from 'react';
import { MemoryRouter } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { PageHeader } from './PageHeader.jsx';

globalThis.React = React;

describe('PageHeader', () => {
  it('renders one semantic page heading with optional actions', () => {
    render(
      <PageHeader
        title="งานการเงิน"
        subtitle="ติดตามงานรับเงินและปิดดีล"
        actions={<button type="button">รีเฟรช</button>}
      />,
    );

    const region = screen.getByRole('banner');
    const title = screen.getByRole('heading', { level: 1, name: 'งานการเงิน' });

    expect(region.getAttribute('aria-labelledby')).toBe(title.id);
    expect(screen.getByText('ติดตามงานรับเงินและปิดดีล')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'รีเฟรช' })).toBeTruthy();
  });

  it('can carry breadcrumbs and context without wrapping them in a card', () => {
    render(
      <MemoryRouter>
        <PageHeader
          title="รายละเอียดดีล"
          breadcrumbs={[{ label: 'รายการดีล', to: '/tickets' }, { label: 'D-001' }]}
          context={<span>สถานะ: รอดำเนินการ</span>}
        />
      </MemoryRouter>,
    );

    expect(screen.getByRole('navigation', { name: 'breadcrumb' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'รายการดีล' }).getAttribute('href')).toBe('/tickets');
    expect(screen.getByText('สถานะ: รอดำเนินการ')).toBeTruthy();
  });
});
