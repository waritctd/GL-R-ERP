import React, { useState } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { Tabs } from './Tabs.jsx';

globalThis.React = React;

const tabs = [
  { id: 'overview', label: 'ภาพรวม' },
  { id: 'pricing', label: 'ราคา' },
  { id: 'documents', label: 'เอกสาร' },
];

function ControlledTabs({ initial = 'overview', visibleTabs = tabs }) {
  const [activeId, setActiveId] = useState(initial);
  return (
    <Tabs tabs={visibleTabs} activeId={activeId} onChange={setActiveId} ariaLabel="พื้นที่ทำงานดีล">
      {(tab) => <p>{tab.id}-panel</p>}
    </Tabs>
  );
}

describe('Tabs', () => {
  it('renders tablist, tabs, and one selected tabpanel with linked ids', () => {
    render(<ControlledTabs />);

    const tablist = screen.getByRole('tablist', { name: 'พื้นที่ทำงานดีล' });
    const overview = screen.getByRole('tab', { name: 'ภาพรวม' });
    const pricing = screen.getByRole('tab', { name: 'ราคา' });
    const panel = screen.getByRole('tabpanel');

    expect(tablist).not.toBeNull();
    expect(overview.getAttribute('aria-selected')).toBe('true');
    expect(overview.getAttribute('tabindex')).toBe('0');
    expect(pricing.getAttribute('aria-selected')).toBe('false');
    expect(pricing.getAttribute('tabindex')).toBe('-1');
    expect(overview.getAttribute('aria-controls')).toBe(panel.id);
    expect(panel.getAttribute('aria-labelledby')).toBe(overview.id);
    expect(screen.getByText('overview-panel')).not.toBeNull();
    expect(screen.queryByText('pricing-panel')).toBeNull();
  });

  it('supports arrow, Home, and End keyboard navigation', () => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
    render(<ControlledTabs />);

    fireEvent.keyDown(screen.getByRole('tablist'), { key: 'ArrowRight' });
    expect(screen.getByRole('tab', { name: 'ราคา' }).getAttribute('aria-selected')).toBe('true');
    expect(screen.getByText('pricing-panel')).not.toBeNull();

    fireEvent.keyDown(screen.getByRole('tablist'), { key: 'End' });
    expect(screen.getByRole('tab', { name: 'เอกสาร' }).getAttribute('aria-selected')).toBe('true');

    fireEvent.keyDown(screen.getByRole('tablist'), { key: 'Home' });
    expect(screen.getByRole('tab', { name: 'ภาพรวม' }).getAttribute('aria-selected')).toBe('true');

    fireEvent.keyDown(screen.getByRole('tablist'), { key: 'ArrowLeft' });
    expect(screen.getByRole('tab', { name: 'เอกสาร' }).getAttribute('aria-selected')).toBe('true');
  });

  it('does not render hidden tabs into the focus order', () => {
    render(<ControlledTabs visibleTabs={[tabs[0], tabs[2]]} />);

    expect(screen.getAllByRole('tab')).toHaveLength(2);
    expect(screen.queryByRole('tab', { name: 'ราคา' })).toBeNull();
  });
});
