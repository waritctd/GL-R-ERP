import React from 'react';
import { MemoryRouter } from 'react-router-dom';
import { render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { Sidebar } from './Sidebar.jsx';

globalThis.React = React;

// Phase 4A gave every nav item an `aria-label`. Because aria-label REPLACES the
// accessible name, the unread-count badge rendered inside the same link stopped
// being announced — a screen-reader user silently lost information that sighted
// users can see. No seeded mock role carries a badge, so this is covered here
// (where the count can be injected) rather than in the browser suite.
const ITEMS = [
  {
    path: '/requests',
    label: 'คำขอแก้ไขข้อมูล',
    helper: 'Profile requests',
    icon: 'clipboard',
    group: 'hr',
    show: true,
    badge: 4,
  },
  {
    path: '/payroll',
    label: 'เงินเดือน',
    helper: 'Payroll',
    icon: 'badgeDollar',
    group: 'hr',
    show: true,
  },
];

function renderSidebar() {
  return render(
    <MemoryRouter initialEntries={['/payroll']}>
      <Sidebar
        items={ITEMS}
        user={{ role: 'hr', name: 'ทดสอบ' }}
        employee={null}
        onLogout={vi.fn()}
      />
    </MemoryRouter>,
  );
}

describe('Sidebar nav item accessible names', () => {
  it('keeps the unread badge count in the link\'s accessible name', () => {
    renderSidebar();

    const link = screen.getByRole('link', { name: /คำขอแก้ไขข้อมูล/ });
    const label = link.getAttribute('aria-label');
    expect(label).toContain('คำขอแก้ไขข้อมูล');
    expect(label).toContain('Profile requests');
    // The count itself must survive into the announced name.
    expect(label).toContain('4');
  });

  it('hides the visual badge from assistive tech so the count is not announced twice', () => {
    renderSidebar();

    const link = screen.getByRole('link', { name: /คำขอแก้ไขข้อมูล/ });
    const badge = within(link).getByText('4');
    expect(badge.getAttribute('aria-hidden')).toBe('true');
  });

  it('leaves an unbadged item\'s accessible name to label plus helper only', () => {
    renderSidebar();

    const link = screen.getByRole('link', { name: /เงินเดือน/ });
    expect(link.getAttribute('aria-label')).toBe('เงินเดือน (Payroll)');
  });
});
