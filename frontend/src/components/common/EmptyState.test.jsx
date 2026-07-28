import React from 'react';
import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import { EmptyState } from './EmptyState.jsx';

globalThis.React = React;

describe('EmptyState', () => {
  it('renders the title un-hidden (no aria-hidden) by default, plus the description and icon', () => {
    const { container } = render(
      <EmptyState icon="users" title="ไม่พบข้อมูล" description="ลองเปลี่ยนตัวกรอง" />,
    );

    const title = container.querySelector('strong');
    expect(title.textContent).toBe('ไม่พบข้อมูล');
    expect(title.hasAttribute('aria-hidden')).toBe(false);

    expect(container.querySelector('span').textContent).toBe('ลองเปลี่ยนตัวกรอง');
    expect(container.querySelector('svg')).toBeTruthy();
  });

  // FIX F10: a caller that already announces this same title text itself via
  // its own `aria-live` region (DataTable's zero-row live region does
  // exactly this) sets `titleAnnouncedElsewhere` so a linear screen-reader
  // pass doesn't hear the title twice. It must hide ONLY the visible title
  // from the accessibility tree — the description (never duplicated
  // elsewhere) and the icon are untouched.
  it('hides only the title when titleAnnouncedElsewhere is set, leaving the description and icon unaffected', () => {
    const { container } = render(
      <EmptyState
        icon="users"
        title="ไม่พบข้อมูล"
        description="ลองเปลี่ยนตัวกรอง"
        titleAnnouncedElsewhere
      />,
    );

    const title = container.querySelector('strong');
    // The title stays visible for sighted users — only removed from the a11y tree.
    expect(title.textContent).toBe('ไม่พบข้อมูล');
    expect(title.getAttribute('aria-hidden')).toBe('true');

    const description = container.querySelector('span');
    expect(description.textContent).toBe('ลองเปลี่ยนตัวกรอง');
    expect(description.hasAttribute('aria-hidden')).toBe(false);

    // The icon is decorative (always aria-hidden via Icon.jsx) regardless of
    // this prop — asserting it is still rendered shows the prop didn't touch it.
    expect(container.querySelector('svg')).toBeTruthy();
  });

  it('omits the description node entirely when none is given, independent of titleAnnouncedElsewhere', () => {
    const { container } = render(<EmptyState title="ไม่พบข้อมูล" titleAnnouncedElsewhere />);

    expect(container.querySelector('span')).toBeNull();
  });
});
