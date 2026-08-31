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

  // Be precise about what this proves: it asserts the component EMITS the title-column floor, and
  // nothing more. jsdom has no layout engine, so it cannot and does not verify that the floor
  // actually keeps the heading on one line — the whole defect this guards against (a title column
  // shrinking to ~20px and breaking mid-word, see PageHeader.jsx) was invisible to all 1,855
  // frontend tests. Layout was verified in a real browser across 360-1440px.
  //
  // It is still worth having: the class reaches the DOM, so unlike a source-text guard it cannot be
  // satisfied by a comment, and it fails loudly if someone reverts the floor to `minmax(0,1fr)` or
  // drops it while refactoring the class list.
  it('gives the title column a width floor so wide action rows cannot starve the heading', () => {
    render(<PageHeader title="งานการเงิน" actions={<button type="button">รีเฟรช</button>} />);

    const header = screen.getByRole('banner');

    expect(header.className).toContain('grid-cols-[minmax(180px,1fr)_auto]');
    expect(header.className).not.toContain('grid-cols-[minmax(0,1fr)_auto]');
    // Below 720px the header collapses to one column, where the floor must NOT apply — the title
    // already owns the full row there.
    expect(header.className).toContain('mobile:grid-cols-[minmax(0,1fr)]');
  });

  // The floor is a DEFAULT, not a lock: a caller passing its own grid-cols must still win, because
  // `cn` is twMerge and both classes live in the same merge group. AttendancePage relied on exactly
  // this before the floor moved into the component.
  it('lets a caller override the grid columns via className', () => {
    render(<PageHeader title="งานการเงิน" className="grid-cols-[minmax(320px,1fr)_auto]" />);

    const header = screen.getByRole('banner');

    expect(header.className).toContain('grid-cols-[minmax(320px,1fr)_auto]');
    expect(header.className).not.toContain('grid-cols-[minmax(180px,1fr)_auto]');
  });
});
